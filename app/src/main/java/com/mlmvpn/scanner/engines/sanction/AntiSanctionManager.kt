package com.mlmvpn.scanner.engines.sanction

import android.content.Context
import androidx.preference.PreferenceManager
import com.mlmvpn.scanner.data.CloudManager
import com.mlmvpn.scanner.utils.VpnConfig
import com.mlmvpn.scanner.utils.XrayJsonGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.InetSocketAddress
import javax.net.ssl.SSLSocketFactory
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

/**
 * "DNS ضد تحریم شخصی" — a domain-based split tunnel that sends ONLY sanctioned domains
 * through the user's own Cloudflare worker (a VLESS-over-workers.dev relay deployed in the
 * Cloud section), so those requests egress from a clean Cloudflare IP and the origin no
 * longer sees an Iranian IP. Everything else stays on the direct connection.
 *
 * Nothing here needs the MITM CA: the client's TLS to the real origin is tunnelled intact
 * through the worker (end-to-end), so it is a real sanction bypass, not a DNS trick.
 */
object AntiSanctionManager {
    private const val PREFS_USER_DOMAINS = "antisanction_user_domains"
    private const val PREFS_REMOVED = "antisanction_removed_defaults"
    private const val PREFS_ACCOUNT_ID = "antisanction_account_id"
    private const val PREFS_APPS = "antisanction_apps"
    private const val PREFS_EXIT = "antisanction_exit"

    // --- Where the un-sanctioned traffic comes out -------------------------
    //
    // A Cloudflare Worker is free, unlimited and fast, and for most sanctioned services -- GitHub,
    // Steam, npm, the AI APIs -- it is all that is needed: they check whether the address is
    // Iranian and nothing else.
    //
    // Some services check a second thing. Gemini refuses datacenter and proxy ranges outright, and
    // a Worker egresses from Cloudflare's own network, so it fails there no matter which country
    // the colo is in. Measured on the user's own account: with the Worker off the site answers
    // 403 (sanctioned), with it on the site answers "not available in your country" (the address
    // is no longer Iranian, and still refused). The EDG worker behaves identically, which rules
    // out the routing and leaves the address itself.
    //
    // VPN Gate is the opposite kind of exit: volunteer-run servers, a large share of them on home
    // broadband in Japan, Korea and Taiwan. Those are residential addresses, which is exactly what
    // the datacenter check is looking for the absence of. It is slower and less reliable than a
    // Worker, which is why it is a choice rather than the default.

    enum class Exit { WORKER, GATEWAY }

    fun exitKind(context: Context): Exit {
        val raw = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREFS_EXIT, Exit.WORKER.name)
        return runCatching { Exit.valueOf(raw ?: "") }.getOrDefault(Exit.WORKER)
    }

    fun setExitKind(context: Context, kind: Exit) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putString(PREFS_EXIT, kind.name).apply()
    }
    private const val PREFS_APPS_SEEDED = "antisanction_apps_seeded"
    const val NODE_ID = "ANTISANCTION"

    // --- Sanctioned apps ---------------------------------------------------
    //
    // Domain routing cannot reach a native app the way it reaches a browser. Three things get in
    // its way, and Gemini hits all three: the app speaks QUIC on UDP/443, it resolves through
    // Google's own resolver rather than the one this tunnel provides (so the fakedns pool never
    // sees the query and the real IP comes back), and its sign-in and token refresh happen inside
    // Google Play Services -- a different package, under a different UID, that no domain rule
    // written for "the Gemini app" will ever match.
    //
    // Selecting an app instead routes it by UID at the VpnService layer, which none of that can
    // slip past. See MyVpnService's builder and [companionsOf].

    /**
     * Apps worth offering first, by package.
     *
     * These are the ones sanctioned by the vendor rather than filtered by the operator -- the
     * case this feature exists for. Anything else the user installs can be added by hand.
     */
    val SUGGESTED_APPS: List<String> = listOf(
        "com.anthropic.claude",
        "com.openai.chatgpt",
        "com.google.android.apps.bard",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.google.android.apps.gemini",
        "com.microsoft.copilot",
        "com.perplexity.app.android",
    )

    /**
     * Packages that must ride along when [pkg] is routed.
     *
     * A Google app does not carry its own account: sign-in, token refresh and much of its API
     * traffic run through Play Services. Routing the app alone leaves those requests going out
     * over the real IP, which is exactly the half the sanction check looks at -- the app then
     * signs in "successfully" and every call it makes afterwards is refused.
     */
    fun companionsOf(pkg: String): List<String> = when {
        pkg.startsWith("com.google.android.apps.") ->
            listOf("com.google.android.gms", "com.android.vending")
        else -> emptyList()
    }

    /** The packages routed through the worker, including the companions they need. */
    fun getApps(context: Context): List<String> {
        seedApps(context)
        return readSet(context, PREFS_APPS).toList().sorted()
    }

    fun routedPackages(context: Context): Set<String> {
        val chosen = getApps(context)
        return (chosen + chosen.flatMap { companionsOf(it) }).toSet()
    }

    fun addApp(context: Context, pkg: String) {
        if (pkg.isBlank()) return
        val set = readSet(context, PREFS_APPS)
        set.add(pkg)
        writeSet(context, PREFS_APPS, set)
    }

    fun removeApp(context: Context, pkg: String) {
        val set = readSet(context, PREFS_APPS)
        set.remove(pkg)
        writeSet(context, PREFS_APPS, set)
    }

    /**
     * On first run, pre-select the suggested apps the user actually has.
     *
     * Only once, and recorded separately from the list itself -- otherwise clearing the list
     * would be undone the next time the screen opened.
     */
    private fun seedApps(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getBoolean(PREFS_APPS_SEEDED, false)) return
        val pm = context.packageManager
        val present = SUGGESTED_APPS.filter { pkg ->
            try { pm.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }
        }
        if (present.isNotEmpty()) writeSet(context, PREFS_APPS, present.toSet())
        prefs.edit().putBoolean(PREFS_APPS_SEEDED, true).apply()
    }

    // --- Domain list (bundled defaults + user edits) ------------------------

    private fun assetDomains(context: Context): List<String> {
        return try {
            val raw = com.mlmvpn.scanner.store.StoreFiles.open(context, "sanction_domains.json").bufferedReader().use { it.readText() }
            val arr = org.json.JSONObject(raw).optJSONArray("domains") ?: JSONArray()
            (0 until arr.length()).map { arr.getString(it).trim().lowercase() }.filter { it.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun readSet(context: Context, key: String): MutableSet<String> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val json = prefs.getString(key, "[]") ?: "[]"
        // Domains are case-insensitive; package names are not, and lowercasing one would quietly
        // stop it matching anything the PackageManager knows about.
        val fold = key != PREFS_APPS
        val out = linkedSetOf<String>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val v = arr.getString(i).trim()
                out.add(if (fold) v.lowercase() else v)
            }
        } catch (_: Exception) {}
        return out
    }

    private fun writeSet(context: Context, key: String, set: Set<String>) {
        val arr = JSONArray()
        set.filter { it.isNotBlank() }.forEach { arr.put(it) }
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(key, arr.toString()).apply()
    }

    /** Effective sanctioned-domain list: (defaults − removed) ∪ user-added, sorted. */
    fun getDomains(context: Context): List<String> {
        val removed = readSet(context, PREFS_REMOVED)
        val defaults = assetDomains(context).filter { it !in removed }
        val user = readSet(context, PREFS_USER_DOMAINS)
        return (defaults + user).distinct().sorted()
    }

    fun addDomain(context: Context, domainRaw: String) {
        val d = normalize(domainRaw) ?: return
        val user = readSet(context, PREFS_USER_DOMAINS)
        user.add(d)
        writeSet(context, PREFS_USER_DOMAINS, user)
        // If it was a previously-removed default, un-remove it.
        val removed = readSet(context, PREFS_REMOVED)
        if (removed.remove(d)) writeSet(context, PREFS_REMOVED, removed)
    }

    fun removeDomain(context: Context, domain: String) {
        val d = domain.trim().lowercase()
        val user = readSet(context, PREFS_USER_DOMAINS)
        if (user.remove(d)) writeSet(context, PREFS_USER_DOMAINS, user)
        // If it's a bundled default, remember the removal so it stays gone.
        if (assetDomains(context).contains(d)) {
            val removed = readSet(context, PREFS_REMOVED)
            removed.add(d)
            writeSet(context, PREFS_REMOVED, removed)
        }
    }

    /** Strip scheme/path and keep just the host. Returns null if empty/invalid. */
    fun normalize(input: String): String? {
        var s = input.trim().lowercase()
        if (s.isEmpty()) return null
        s = s.removePrefix("https://").removePrefix("http://")
        s = s.substringBefore('/')
        s = s.substringBefore(':')
        return s.takeIf { it.contains('.') && !it.contains(' ') }
    }

    /** Xray routing entries: "domain:x" matches x and all its subdomains. */
    fun routingDomains(context: Context): List<String> = getDomains(context).map { "domain:$it" }

    // --- Cloudflare worker (exit) selection --------------------------------

    /** Cloud accounts that already have a deployed EDG (VLESS) worker. */
    fun accountsWithWorker(context: Context) =
        CloudManager(context).accountsFlow.value.filter { !it.edgWorkerUrl.isNullOrEmpty() && !it.edgUuid.isNullOrEmpty() }

    fun selectedAccountId(context: Context): String? =
        PreferenceManager.getDefaultSharedPreferences(context).getString(PREFS_ACCOUNT_ID, null)

    fun setSelectedAccountId(context: Context, id: String) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(PREFS_ACCOUNT_ID, id).apply()
    }

    /**
     * The VLESS URI to use as the proxy exit. Prefers a clean-IP Cloudflare variant (EDG-CF1)
     * over the workers.dev hostname, because workers.dev DNS is blocked on many Iran networks.
     * Returns null when no worker-backed account is configured.
     */
    suspend fun getExitVless(context: Context): String? = withContext(Dispatchers.IO) {
        val cloud = CloudManager(context)
        val accounts = accountsWithWorker(context)
        if (accounts.isEmpty()) return@withContext null
        val chosen = selectedAccountId(context)?.let { id -> accounts.firstOrNull { it.accountId == id } }
            ?: accounts.first()
        val (ok, configs) = cloud.fetchEdgConfigs(chosen)
        if (!ok || configs.isEmpty()) return@withContext null
        // configs = [EDG-Auto(workers.dev), EDG-CF1(clean ip), EDG-CF2(clean ip)]
        configs.getOrNull(1) ?: configs.first()
    }

    /**
     * Build the full Xray split-tunnel JSON: sanctioned domains → the Cloudflare worker,
     * everything else → direct. Returns null if no exit worker is configured.
     */
    suspend fun buildConfig(context: Context, localPort: Int, backendDns: String = "1.1.1.1"): String? {
        val vless = getExitVless(context) ?: return null
        val config = VpnConfig.parseUri(vless) ?: return null
        val domains = routingDomains(context)
        val apps = routedPackages(context)
        // With apps selected, MyVpnService puts ONLY those packages in the tunnel -- so anything
        // arriving here is from an app the user asked to un-sanction, and all of it goes to the
        // worker. That is what makes the routing immune to QUIC, to the app's own resolver, and
        // to traffic that leaves under a companion package's UID.
        if (domains.isEmpty() && apps.isEmpty()) return null
        return XrayJsonGenerator.generateAntiSanctionConfig(
            config = config,
            localPort = localPort,
            sanctionedDomains = domains,
            backendDns = backendDns,
            proxyEverything = apps.isNotEmpty(),
        )
    }

    // --- Sanction vs filter detection (port of sanction-manager.js) ---------

    enum class State { OPEN, FILTERED, SANCTIONED, UNKNOWN }
    data class Report(val domain: String, val state: State, val note: String)

    suspend fun classifyDomain(domainRaw: String): Report = withContext(Dispatchers.IO) {
        val domain = domainRaw.trim().lowercase()
        val tcp = tcpConnect(domain, 443, 6000)
        val https = httpsHead(domain, domain, 6000)

        // Direct open: TCP ok + HTTP < 400
        if (tcp.first && https.first != null && https.first!! < 400) {
            return@withContext Report(domain, State.OPEN, S(R.string.direct_access_works_neither_sanctioned_nor_filtered))
        }
        // Network-layer block (reset/timeout) → filtering, not sanction
        if (!tcp.first && (tcp.second == "reset" || tcp.second == "timeout")) {
            return@withContext Report(domain, State.FILTERED, S(R.string.network_filtering_a_job_for_the_vpn))
        }
        // Geo-block: TLS ok but HTTP 403/451
        if (https.first == 403 || https.first == 451) {
            return@withContext Report(domain, State.SANCTIONED, S(R.string.sanctioned_tap_add_to_open_it_through))
        }
        // TCP ok but TLS reset → SNI filtering
        if (tcp.first && https.first == null && https.second == "reset") {
            return@withContext Report(domain, State.FILTERED, S(R.string.sni_based_filtering_a_job_for_the))
        }
        Report(domain, State.UNKNOWN, S(R.string.unclear_you_can_add_it_to_the))
    }

    private fun tcpConnect(host: String, port: Int, timeoutMs: Int): Pair<Boolean, String> {
        return try {
            java.net.Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                true to "ok"
            }
        } catch (e: java.net.SocketTimeoutException) {
            false to "timeout"
        } catch (e: java.net.ConnectException) {
            false to "reset"
        } catch (e: Exception) {
            false to (e.message ?: "error")
        }
    }

    /** Returns (httpStatus?, reason). Uses SNI = servername; connects to connectHost. */
    private fun httpsHead(connectHost: String, servername: String, timeoutMs: Int): Pair<Int?, String> {
        return try {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val plain = java.net.Socket()
            plain.connect(InetSocketAddress(connectHost, 443), timeoutMs)
            plain.soTimeout = timeoutMs
            val ssl = factory.createSocket(plain, servername, 443, true) as javax.net.ssl.SSLSocket
            // Present the real SNI.
            try {
                val p = ssl.sslParameters
                p.serverNames = listOf(javax.net.ssl.SNIHostName(servername))
                ssl.sslParameters = p
            } catch (_: Exception) {}
            ssl.startHandshake()
            val req = "HEAD / HTTP/1.1\r\nHost: $servername\r\nUser-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n"
            ssl.outputStream.write(req.toByteArray())
            ssl.outputStream.flush()
            val line = ssl.inputStream.bufferedReader().readLine() ?: ""
            ssl.close()
            val m = Regex("^HTTP/[\\d.]+ (\\d{3})").find(line)
            if (m != null) m.groupValues[1].toInt() to "ok" else null to "closed"
        } catch (e: javax.net.ssl.SSLException) {
            null to "reset"
        } catch (e: java.net.SocketTimeoutException) {
            null to "timeout"
        } catch (e: Exception) {
            null to (e.message ?: "error")
        }
    }
}
