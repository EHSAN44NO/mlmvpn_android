package com.mlmvpn.scanner.gblab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mlmvpn.core.aether.AetherEngine
import com.mlmvpn.core.aether.AetherIp
import com.mlmvpn.core.aether.AetherOptions
import com.mlmvpn.core.aether.AetherProtocol
import com.mlmvpn.core.aether.AetherScan
import com.mlmvpn.scanner.engines.game.booster.model.RegionCatalog
import com.mlmvpn.scanner.engines.game.booster.probe.EchoProtocol
import com.mlmvpn.scanner.engines.game.booster.probe.SocksUdpSession
import com.mlmvpn.scanner.engines.game.booster.probe.StunProtocol
import com.mlmvpn.scanner.engines.game.booster.probe.TrainResult
import com.mlmvpn.scanner.engines.game.booster.probe.UdpVia
import com.mlmvpn.scanner.engines.game.booster.probe.icmpPing
import com.mlmvpn.scanner.engines.game.booster.probe.resolveV4
import com.mlmvpn.scanner.engines.game.booster.probe.tcpTrain
import com.mlmvpn.scanner.engines.game.booster.probe.udpTrain
import com.mlmvpn.scanner.utils.ScanPreflight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.InetSocketAddress

/**
 * DEBUG BUILDS ONLY -- the Game Booster lab (plan phase 0).
 *
 * Runs every probe the booster relies on from inside the app's own uid (which is outside the
 * app's own VPN, so it sees the real line even while a config is connected) and logs one line per
 * measurement under the tag `GameLab`. Nothing here ships: the file lives in src/debug.
 *
 *     adb shell am broadcast -n com.mlmvpn.scanner/.gblab.GameLabReceiver --ez warp true
 *     adb logcat -s GameLab
 *
 * Extras: `warp` (also start Aether SOCKS-only and measure through it), `secs` (seconds per UDP
 * train, default 3), `regions` (comma list of RegionCatalog keys, default all).
 *
 * With `--es game <id>` (codm, pubg, fifamobile, …) it runs the v4 checks for that game instead:
 * the doctor on every part of the game, the region sweep with its advice, and -- for any host
 * refused on country -- every anti-sanction DNS, one line per provider:
 *
 *     adb shell am broadcast -n com.mlmvpn.scanner/.gblab.GameLabReceiver --es game fifamobile
 */
class GameLabReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val warp = intent.getBooleanExtra("warp", false)
        val secs = intent.getIntExtra("secs", 3).coerceIn(1, 20)
        val regionKeys = intent.getStringExtra("regions")?.split(',')?.map { it.trim() }?.toSet()
        val game = intent.getStringExtra("game")
        // Deliberately NOT goAsync(): a receiver held past the broadcast timeout gets the process
        // ANR-killed, and this process also runs the VPN service. The receiver returns at once and
        // the lab carries on in a process-wide scope.
        LAB_SCOPE.launch {
            try {
                if (game != null) runGame(app, game) else run(app, warp, secs, regionKeys)
            } catch (e: Throwable) {
                log("FATAL ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private suspend fun runGame(ctx: Context, gameId: String) {
        val profile = com.mlmvpn.scanner.engines.game.booster.model.GameProfileStore(ctx).get(gameId)
            ?: return log("no such game: $gameId")
        val net = ScanPreflight.underlyingNetwork(ctx)
        log("=== game lab ${profile.id} (${profile.name}) regions=${profile.regions.map { it.key }} parts=${profile.accessHosts()}")

        val t0 = System.currentTimeMillis()
        val report = com.mlmvpn.scanner.engines.game.booster.doctor.GameDoctor.examine(profile.accessHosts(), net)
        log("doctor ${System.currentTimeMillis() - t0}ms kind=${report.kind} coreNeedsTunnel=${report.coreNeedsTunnel} " +
            "pins=${report.pins()}")

        val t1 = System.currentTimeMillis()
        val sweep = com.mlmvpn.scanner.engines.game.booster.session.RegionSweep.run(profile.regions, net)
        sweep.forEach { p ->
            p.echo?.let { log(line("sweep ${p.gameRegion.key}/${p.anchor.key} echo", it)) }
            p.tcp?.let { log(line("sweep ${p.gameRegion.key}/${p.anchor.key} tcp", it)) }
            if (p.echo == null && p.tcp == null) log("sweep ${p.gameRegion.key}/${p.anchor.key}: no endpoint resolved")
        }
        val advice = com.mlmvpn.scanner.engines.game.booster.decide.RegionAdvisor.advise(profile.defaultRegion, sweep.map { it.sample() })
        log("sweep ${System.currentTimeMillis() - t1}ms advice=$advice")

        val geo = report.geoBlocked()
        if (geo.isNotEmpty()) {
            val t2 = System.currentTimeMillis()
            val out = com.mlmvpn.scanner.engines.game.booster.paths.SanctionDns.pick(
                geo.map { it.host }, geo.associate { it.host to it.systemIps }, net, currentId = null)
            log("sanction dns ${System.currentTimeMillis() - t2}ms pick=${out.pick?.provider?.id}@${out.pick?.resolverIp} " +
                "fixed=${out.pick?.fixedHosts} notGeo=${out.notGeo}")
        }
        log("=== game lab end")
    }

    companion object {
        private val LAB_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private suspend fun run(ctx: Context, warp: Boolean, secs: Int, regionKeys: Set<String>?) {
        val net = ScanPreflight.underlyingNetwork(ctx)
        log("=== lab start warp=$warp secs=$secs underlyingNetwork=${net != null}")

        val icmp = icmpPing("8.8.8.8", count = 5)
        log(line("icmp 8.8.8.8", icmp))

        val regions = RegionCatalog.REGIONS.filter { regionKeys == null || it.key in regionKeys }
        for (r in regions) {
            for (e in r.echo) {
                val ip = resolveV4(e.host).firstOrNull()
                if (ip == null) { log("echo ${r.key} ${e.host}: resolve failed"); continue }
                // Ten flows at two packets a second: the beacon's per-flow limit (see udpTrainFlows).
                val res = com.mlmvpn.scanner.engines.game.booster.probe.udpTrainFlows(InetSocketAddress(ip, e.port), EchoProtocol,
                    List(10) { UdpVia.Direct(net) }, ppsPerFlow = 2.0, durationMs = secs * 1000L, warmupMs = 500)
                log(line("echo DIRECT ${r.key} ${e.host}(${ip.hostAddress})", res))
            }
            for (t in r.tcp) {
                val ip = resolveV4(t.host).firstOrNull()
                if (ip == null) { log("tcp ${r.key} ${t.host}: resolve failed"); continue }
                val res = tcpTrain(InetSocketAddress(ip, t.port), count = 6, network = net)
                log(line("tcp DIRECT ${r.key} ${t.host}(${ip.hostAddress})", res))
            }
        }
        for (s in RegionCatalog.STUN.take(2)) {
            val ip = resolveV4(s.host).firstOrNull() ?: continue
            val res = udpTrain(InetSocketAddress(ip, s.port), StunProtocol, UdpVia.Direct(net),
                pps = 20, durationMs = secs * 1000L, warmupMs = 500)
            log(line("stun DIRECT ${s.host}", res))
        }

        if (!warp) { log("=== lab end"); return }

        val engine = AetherEngine.get(ctx)
        val startedHere = if (engine.state.value.connected && engine.isSocksAlive()) {
            log("aether already connected -- measuring through it")
            false
        } else {
            val ok = engine.start(AetherOptions(
                protocol = AetherProtocol.MASQUE, scan = AetherScan.TURBO, ipFamily = AetherIp.V4,
                transport = "h3", keepalive = 5, noize = "light",
            ))
            log("aether start requested ok=$ok")
            ok
        }
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 60_000) {
            val s = engine.state.value
            if (s.connected && engine.isSocksAlive()) break
            if (!s.running && s.stage.name != "IDLE" && s.stage.name != "STARTING" && System.currentTimeMillis() - t0 > 3000) break
            delay(500)
        }
        val st = engine.state.value
        log("aether state connected=${st.connected} stage=${st.stage} server=${st.server} rtt=${st.rtt} after ${System.currentTimeMillis() - t0}ms")
        if (st.connected) {
            val socks = InetSocketAddress("127.0.0.1", AetherEngine.AETHER_SOCKS_PORT)
            for (r in regions) {
                for (t in r.tcp) {
                    val ip = resolveV4(t.host).firstOrNull() ?: continue
                    val res = tcpTrain(InetSocketAddress(ip, t.port), count = 6, socks = socks)
                    log(line("tcp WARP ${r.key} ${t.host}", res))
                }
                for (e in r.echo) {
                    val ip = resolveV4(e.host).firstOrNull() ?: continue
                    val sessions = SocksUdpSession.openMany(socks, 6)
                    val res = try {
                        if (sessions.isEmpty()) { log("echo WARP ${r.key}: socks udp failed"); null }
                        else com.mlmvpn.scanner.engines.game.booster.probe.udpTrainFlows(InetSocketAddress(ip, e.port),
                            EchoProtocol, sessions.map { UdpVia.Socks(it) }, ppsPerFlow = 2.0,
                            durationMs = secs * 1000L, warmupMs = 500)
                    } finally {
                        sessions.forEach { it.close() }
                    }
                    if (res != null) log(line("echo WARP ${r.key} ${e.host}", res))
                }
            }
        }
        if (startedHere) {
            engine.stop()
            log("aether stopped")
        }
        log("=== lab end")
    }

    private fun line(label: String, r: TrainResult): String {
        val s = r.stats
        return "$label proto=${r.proto} n=${s.n}/${s.sent} loss=${s.loss}% min=${s.min} p50=${s.p50} " +
            "p95=${s.p95} jitter=${s.jitter} spread=${s.spread} spikes=${s.spikes} score=${r.score} " +
            "pps=${r.ppsAchieved?.let { String.format(java.util.Locale.US, "%.1f", it) }} err=${r.error}"
    }

    private fun log(msg: String) {
        Log.i("GameLab", msg)
    }
}
