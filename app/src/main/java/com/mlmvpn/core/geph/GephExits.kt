package com.mlmvpn.core.geph

import android.content.Context
import android.os.SystemClock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** One Geph exit server, as the network lists it (net_status). */
data class GephExit(
    val key: String,
    val country: String,
    val city: String,
    /** 0..1, how busy the server is. */
    val load: Double,
    /** `core` or `streaming`; `auto` only ever picks core. */
    val category: String,
    /** Account levels allowed on it: "Free", "Plus". */
    val levels: Set<String>,
) {
    val streaming: Boolean get() = category == "streaming"
    fun allows(level: GephAccount.Level): Boolean =
        levels.isEmpty() || if (level == GephAccount.Level.FREE) "Free" in levels else "Plus" in levels
}

object GephExits {

    data class City(val name: String, val exits: List<GephExit>) {
        val lightestLoad: Double get() = exits.minOfOrNull { it.load } ?: 1.0
        val streaming: Boolean get() = exits.any { it.streaming }
    }

    data class Country(val code: String, val cities: List<City>) {
        val exits: List<GephExit> get() = cities.flatMap { it.exits }
        fun usable(level: GephAccount.Level): Int = exits.count { it.allows(level) }
    }

    /** net_status: `{exits: {key: [pubkey, ExitDescriptor, ExitMetadata]}}`. */
    fun parse(netStatus: JSONObject): List<GephExit> {
        val exits = netStatus.optJSONObject("exits") ?: return emptyList()
        val out = ArrayList<GephExit>()
        exits.keys().forEach { key ->
            val tuple = exits.optJSONArray(key) ?: return@forEach
            val desc = tuple.optJSONObject(1) ?: return@forEach
            val meta = tuple.optJSONObject(2)
            val levels = meta?.optJSONArray("allowed_levels")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }.toSet()
            }.orEmpty()
            out += GephExit(
                key = key,
                country = desc.optString("country").uppercase(),
                city = desc.optString("city"),
                load = desc.optDouble("load", 0.0),
                category = meta?.optString("category", "core") ?: "core",
                levels = levels,
            )
        }
        return out.filter { it.country.length == 2 }
    }

    /** Grouped by country and city; countries with something this account can use first. */
    fun countries(exits: List<GephExit>, level: GephAccount.Level): List<Country> =
        exits.groupBy { it.country }
            .map { (code, list) ->
                Country(code, list.groupBy { it.city }.map { (city, e) -> City(city, e) }.sortedBy { it.lightestLoad })
            }
            .sortedWith(compareBy<Country> { if (it.usable(level) > 0) 0 else 1 }.thenBy { it.code })

    private fun cacheFile(ctx: Context) = File(GephEngine.dir(ctx), "exits.json")

    /** The last list the network gave, so the picker has something to show at once. */
    fun cached(ctx: Context): List<GephExit> = runCatching {
        parse(JSONObject(cacheFile(ctx).readText()))
    }.getOrDefault(emptyList())

    fun refresh(ctx: Context): Result<List<GephExit>> = runCatching {
        val status = GephEngine.withControl(ctx) { it.netStatus() } ?: throw IllegalStateException("no answer")
        val list = parse(status)
        if (list.isNotEmpty()) cacheFile(ctx).writeText(status.toString())
        list
    }

    // ── the fastest server ──────────────────────────────────────────────────────────────────────

    data class Probe(
        val exit: GephExitChoice,
        val ok: Boolean,
        val connectMs: Long = 0,
        val ttfbMs: Long = 0,
        val mbit: Double = 0.0,
        val error: String? = null,
    )

    private const val PROBE_SOCKS = 21400

    /**
     * Measures each candidate for real, one at a time: connect, time to first byte, and half a
     * megabyte from Google's CDN. Exit choice is Geph's biggest speed lever -- measured on the
     * desktop, the best free exit was 3.5 times faster than the one `auto` picked.
     *
     * One at a time, on a COPY of the account's cache, and never while connected. The copy is
     * the point: it already holds today's connect token, and the network allows twenty of those
     * per account per day (rpc_impl.rs) -- a probe per country, each with a cache of its own,
     * would spend them and could leave the real connection without one for the rest of the day.
     */
    fun findFastest(
        ctx: Context,
        candidates: List<GephExitChoice>,
        onProgress: (index: Int, total: Int, probing: GephExitChoice, done: Probe?) -> Unit,
        cancelled: () -> Boolean,
    ): List<Probe> {
        val app = ctx.applicationContext
        if (GephEngine.isMainAlive) throw IllegalStateException(BUSY)
        val cred = GephAccount.credential(app) ?: throw IllegalStateException(GephEngine.NO_ACCOUNT)
        val dir = File(GephEngine.dir(app), "probe").apply { deleteRecursively(); mkdirs() }
        val db = File(dir, "db")
        val main = GephEngine.cacheFile(app, cred)
        listOf("", "-wal", "-shm").forEach { suffix ->
            val src = File(main.path + suffix)
            if (src.exists()) src.copyTo(File(db.path + suffix), overwrite = true)
        }

        val hadCache = main.exists()
        val results = ArrayList<Probe>()
        candidates.forEachIndexed { i, exit ->
            if (cancelled()) return results
            onProgress(i, candidates.size, exit, null)
            val probe = probeOne(app, cred, exit, db, dir)
            results += probe
            onProgress(i, candidates.size, exit, probe)
        }
        // An account that never connected has just paid for its first token, auth and route in
        // the probe's cache. Keeping that cache makes the first real connect a warm one instead of
        // paying for all of it a second time.
        if (!hadCache && db.exists()) {
            listOf("", "-wal", "-shm").forEach { suffix ->
                val src = File(db.path + suffix)
                if (src.exists()) runCatching { src.copyTo(File(main.path + suffix), overwrite = true) }
            }
        }
        dir.deleteRecursively()
        val ranked = results.sortedWith(compareByDescending<Probe> { it.ok }.thenByDescending { it.mbit }.thenBy { it.ttfbMs })
        ranked.firstOrNull { it.ok }?.let { best ->
            GephSettings.setLastFastest(app, JSONObject()
                .put("at", System.currentTimeMillis())
                .put("country", best.exit.country)
                .put("city", best.exit.city ?: JSONObject.NULL)
                .put("mbit", best.mbit)
                .put("ttfb", best.ttfbMs)
                .put("ranked", JSONArray().apply {
                    ranked.forEach { p ->
                        put(JSONObject()
                            .put("country", p.exit.country).put("city", p.exit.city ?: JSONObject.NULL)
                            .put("ok", p.ok).put("mbit", p.mbit).put("ttfb", p.ttfbMs))
                    }
                }))
        }
        return ranked
    }

    private fun probeOne(ctx: Context, cred: GephCredential, exit: GephExitChoice, db: File, dir: File): Probe {
        val control = File(dir, "probe.sock").apply { delete() }.absolutePath
        val run = GephRun(
            credential = cred,
            exit = exit,
            dryRun = false,
            controlSocket = control,
            cacheFile = db.absolutePath,
            socks5 = "127.0.0.1:$PROBE_SOCKS",
            allowDirect = GephSettings.allowDirect(ctx),
        )
        val cfg = File(dir, "config.json").apply { writeText(GephConfig.build(run).toString()) }
        val started = SystemClock.elapsedRealtime()
        val proc = GephProcess.start(ctx, "probe", cfg, dir, null, onLine = {}, onExit = {})
        val c = GephControl(control)
        try {
            val deadline = started + 30_000
            var connected = false
            while (SystemClock.elapsedRealtime() < deadline && proc.isAlive) {
                if (runCatching { c.connInfo(2_000).connected }.getOrDefault(false)) {
                    connected = true
                    break
                }
                Thread.sleep(300)
            }
            if (!connected) return Probe(exit, ok = false, error = "no session in 30 s")
            val connectMs = SystemClock.elapsedRealtime() - started
            val client = OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", PROBE_SOCKS)))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .callTimeout(40, TimeUnit.SECONDS)
                .build()
            val t0 = SystemClock.elapsedRealtime()
            client.newCall(Request.Builder().url("https://www.gstatic.com/generate_204").build()).execute().use { }
            val ttfb = SystemClock.elapsedRealtime() - t0
            val size = 524_288
            val t1 = SystemClock.elapsedRealtime()
            var got = 0L
            client.newCall(
                Request.Builder()
                    .url("https://dl.google.com" + GephEngine.SPEED_PATH)
                    .header("Range", "bytes=0-${size - 1}")
                    .build(),
            ).execute().use { r ->
                val buf = ByteArray(16_384)
                val input = r.body?.byteStream() ?: return@use
                while (got < size) {
                    val n = input.read(buf)
                    if (n < 0) break
                    got += n
                }
            }
            val ms = SystemClock.elapsedRealtime() - t1
            val mbit = if (ms > 0) got * 8.0 / ms / 1000.0 else 0.0
            return Probe(exit, ok = got > 0, connectMs = connectMs, ttfbMs = ttfb, mbit = mbit)
        } catch (e: Exception) {
            return Probe(exit, ok = false, error = e.message ?: e.javaClass.simpleName)
        } finally {
            proc.stop(c)
        }
    }

    const val BUSY = "GEPH_BUSY"
}
