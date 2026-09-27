package com.mlmvpn.scanner.engines.github

import android.content.Context
import com.mlmvpn.core.tunnel.SecureStore
import org.json.JSONArray
import org.json.JSONObject

/** One GitHub account in the pool. The token is kept encrypted by the Android Keystore. */
data class GtAccount(
    val id: String,
    val login: String,
    val name: String = login,
    val avatarUrl: String = "",
    val scopes: String = "",
    val tokenEnc: String = "",
    val addedAt: Long = System.currentTimeMillis(),
    /** OK | EXHAUSTED | AUTH_REQUIRED | RATE_LIMITED | REPO_ERROR | DISPATCH_FAILED */
    val health: String = "OK",
    val healthReason: String = "",
    val cooldownUntil: Long = 0,
    val disabled: Boolean = false,
    val repository: String = "",
    val defaultBranch: String = "main",
    val lastUsedAt: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("login", login).put("name", name).put("avatarUrl", avatarUrl)
        .put("scopes", scopes).put("tokenEnc", tokenEnc).put("addedAt", addedAt)
        .put("health", health).put("healthReason", healthReason).put("cooldownUntil", cooldownUntil)
        .put("disabled", disabled).put("repository", repository).put("defaultBranch", defaultBranch)
        .put("lastUsedAt", lastUsedAt)

    companion object {
        fun from(o: JSONObject) = GtAccount(
            id = o.optString("id"), login = o.optString("login"), name = o.optString("name"),
            avatarUrl = o.optString("avatarUrl"), scopes = o.optString("scopes"),
            tokenEnc = o.optString("tokenEnc"), addedAt = o.optLong("addedAt"),
            health = o.optString("health", "OK"), healthReason = o.optString("healthReason"),
            cooldownUntil = o.optLong("cooldownUntil"), disabled = o.optBoolean("disabled"),
            repository = o.optString("repository"), defaultBranch = o.optString("defaultBranch", "main"),
            lastUsedAt = o.optLong("lastUsedAt"),
        )
    }
}

/**
 * One cloud session. Its status walks the same states the Windows app uses:
 * SETTING_UP → STARTING → INSTALLING → CONNECTING_NETWORK → READY → ACTIVE → EXPIRING_SOON → EXPIRED,
 * with FAILED from any step, and STANDBY / ENDING around a seamless renewal.
 */
data class GtSession(
    val id: String,
    val accountId: String,
    val accountLogin: String,
    val repository: String = "",
    val runId: Long = 0,
    val status: String = "SETTING_UP",
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = 0,
    val runStartedAt: Long = 0,
    /** The one-time X25519 private key the runner sealed to — Keystore-encrypted. */
    val sealKeyEnc: String = "",
    /** The sealed transport exactly as the runner published it (ciphertext). */
    val sealed: String = "",
    val rev: Int = 0,
    val runnerCountry: String = "",
    val runnerCity: String = "",
    val hosts: Int = 0,
    val lastError: String = "",
    val endedAt: Long = 0,
) {
    val live: Boolean get() = status in LIVE

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("accountId", accountId).put("accountLogin", accountLogin)
        .put("repository", repository).put("runId", runId).put("status", status)
        .put("createdAt", createdAt).put("expiresAt", expiresAt).put("runStartedAt", runStartedAt)
        .put("sealKeyEnc", sealKeyEnc).put("sealed", sealed).put("rev", rev)
        .put("runnerCountry", runnerCountry).put("runnerCity", runnerCity).put("hosts", hosts)
        .put("lastError", lastError).put("endedAt", endedAt)

    companion object {
        val LIVE = setOf("READY", "ACTIVE", "EXPIRING_SOON")
        fun from(o: JSONObject) = GtSession(
            id = o.optString("id"), accountId = o.optString("accountId"), accountLogin = o.optString("accountLogin"),
            repository = o.optString("repository"), runId = o.optLong("runId"), status = o.optString("status"),
            createdAt = o.optLong("createdAt"), expiresAt = o.optLong("expiresAt"), runStartedAt = o.optLong("runStartedAt"),
            sealKeyEnc = o.optString("sealKeyEnc"), sealed = o.optString("sealed"), rev = o.optInt("rev"),
            runnerCountry = o.optString("runnerCountry"), runnerCity = o.optString("runnerCity"), hosts = o.optInt("hosts"),
            lastError = o.optString("lastError"), endedAt = o.optLong("endedAt"),
        )
    }
}

/**
 * One rule: these sites and these apps leave through `country` instead of the default exit.
 * Apps are kept as package names — stable across updates and reinstalls; the connection turns them
 * into UIDs when it builds its config, since the core's owner lookup answers with a UID.
 */
data class GtExitRule(
    val country: String,
    val provider: String = "",
    val domains: List<String> = emptyList(),
    val apps: List<String> = emptyList(),
) {
    val empty: Boolean get() = domains.isEmpty() && apps.isEmpty()
}

/**
 * The exit country (runner/exits.mjs): `country` empty = «حداکثر سرعت», the runner's own address.
 * `provider` empty = automatic (VPN Gate first, Psiphon if that fails), or `vpngate` / `psiphon`.
 */
data class GtExitPrefs(val country: String = "", val provider: String = "", val rules: List<GtExitRule> = emptyList()) {
    fun toJson(): JSONObject = JSONObject().put("country", country).put("provider", provider)
        .put("rules", JSONArray().apply {
            rules.forEach { r ->
                put(JSONObject().put("country", r.country).put("provider", r.provider)
                    .put("domains", JSONArray(r.domains)).put("apps", JSONArray(r.apps)))
            }
        })

    /** The runner's `exits` dispatch input, so it starts them at boot: `JP` or `JP:psiphon`, up to six. */
    fun dispatchInput(): String {
        val out = mutableListOf<String>()
        fun add(cc: String, p: String) { val v = if (p.isBlank()) cc else "$cc:$p"; if (cc.isNotBlank() && v !in out) out.add(v) }
        add(country, provider)
        rules.forEach { add(it.country, it.provider) }
        return out.take(6).joinToString(",")
    }

    /**
     * `rule` saved in place of the one at `replacing` (-1: a new one). A site or an app lives in ONE
     * rule — taking it here takes it out of every other, and a rule left with nothing is dropped; a
     * second rule for the same country and provider is folded into the first instead of costing
     * another of the server's six exit slots. An empty `rule` is a delete.
     */
    fun withRule(rule: GtExitRule, replacing: Int = -1): GtExitPrefs {
        val taken = rule.domains.toSet()
        val takenApps = rule.apps.toSet()
        val others = rules.withIndex().filter { it.index != replacing }
            .map { (i, r) -> i to r.copy(domains = r.domains - taken, apps = r.apps - takenApps) }
            .filter { !it.second.empty }
        if (rule.empty) return copy(rules = others.map { it.second })
        val same = others.indexOfFirst { it.second.country == rule.country && it.second.provider == rule.provider }
        val next = others.map { it.second }.toMutableList()
        if (same >= 0) {
            val r = next[same]
            next[same] = r.copy(domains = (r.domains + rule.domains).distinct().take(MAX_DOMAINS),
                apps = (r.apps + rule.apps).distinct().take(MAX_APPS))
        } else {
            // An edit stays where it was in the list; a new rule goes last.
            val at = if (replacing >= 0) others.count { it.first < replacing } else next.size
            next.add(at, rule)
        }
        return copy(rules = next.take(MAX_RULES))
    }

    companion object {
        /** Five rules + the default country = the runner's six exit slots. */
        const val MAX_RULES = 5
        const val MAX_DOMAINS = 200
        const val MAX_APPS = 200
        private val CC = Regex("^[A-Z]{2}$")
        private val DOMAIN = Regex("^(?=.{3,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$", RegexOption.IGNORE_CASE)
        private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
        fun cleanCc(v: String) = v.trim().uppercase().takeIf { CC.matches(it) }.orEmpty()
        fun cleanProvider(v: String) = v.takeIf { it == "vpngate" || it == "psiphon" }.orEmpty()
        /** What a person types for a site, as the rule wants it: no scheme, no path, no `www.`/`*.`. */
        fun cleanDomains(text: String): List<String> = text.split(Regex("[\\s,،]+"))
            .map { it.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/').removePrefix("*.").removePrefix("www.") }
            .filter { DOMAIN.matches(it) }.distinct().take(MAX_DOMAINS)
        fun cleanApps(list: Collection<String>): List<String> = list.map { it.trim() }.filter { PACKAGE.matches(it) }.distinct().take(MAX_APPS)

        fun from(o: JSONObject?): GtExitPrefs {
            if (o == null) return GtExitPrefs()
            val arr = o.optJSONArray("rules") ?: JSONArray()
            fun strings(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
            val rules = (0 until arr.length()).mapNotNull { i ->
                val r = arr.optJSONObject(i) ?: return@mapNotNull null
                val cc = cleanCc(r.optString("country"))
                val rule = GtExitRule(cc, cleanProvider(r.optString("provider")),
                    strings(r.optJSONArray("domains")).take(MAX_DOMAINS), cleanApps(strings(r.optJSONArray("apps"))))
                if (cc.isBlank() || rule.empty) null else rule
            }.take(MAX_RULES)
            return GtExitPrefs(cleanCc(o.optString("country")), cleanProvider(o.optString("provider")), rules)
        }
    }
}

/** The relay Worker this installation deployed on the user's Cloudflare account. */
data class GtBrokerState(
    val workerName: String = "",
    val url: String = "",
    val customUrl: String = "",
    /** CloudAccount.id (the app's own id for the saved Cloudflare account). */
    val cloudAccountId: String = "",
    val cloudAccountName: String = "",
    val version: Int = 0,
    val deployedAt: Long = 0,
) {
    val deployed: Boolean get() = url.isNotBlank()
    val effectiveUrl: String get() = customUrl.ifBlank { url }.trimEnd('/')

    fun toJson(): JSONObject = JSONObject()
        .put("workerName", workerName).put("url", url).put("customUrl", customUrl)
        .put("cloudAccountId", cloudAccountId).put("cloudAccountName", cloudAccountName)
        .put("version", version).put("deployedAt", deployedAt)

    companion object {
        fun from(o: JSONObject?) = if (o == null) GtBrokerState() else GtBrokerState(
            workerName = o.optString("workerName"), url = o.optString("url"), customUrl = o.optString("customUrl"),
            cloudAccountId = o.optString("cloudAccountId"), cloudAccountName = o.optString("cloudAccountName"),
            version = o.optInt("version"), deployedAt = o.optLong("deployedAt"),
        )
    }
}

/**
 * Everything GitHub Tunnel keeps, in one preferences file. Tokens, the install secret and each
 * session's sealing key go through [SecureStore] (Android Keystore, AES-GCM); the sealed transport
 * is ciphertext already.
 */
class GtStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("github_tunnel", Context.MODE_PRIVATE)
    private val lock = Any()

    // ── accounts ─────────────────────────────────────────────────────────────────
    fun accounts(): List<GtAccount> = synchronized(lock) {
        val arr = try { JSONArray(prefs.getString(K_ACCOUNTS, "[]")) } catch (_: Exception) { JSONArray() }
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(GtAccount::from) }
    }

    private fun writeAccounts(list: List<GtAccount>) {
        prefs.edit().putString(K_ACCOUNTS, JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()).apply()
    }

    /** Add or refresh (same login → the same account, new token). Returns (account, created). */
    fun upsertAccount(login: String, token: String, name: String, avatarUrl: String, scopes: String): Pair<GtAccount, Boolean> =
        synchronized(lock) {
            val list = accounts().toMutableList()
            val i = list.indexOfFirst { it.login.equals(login, ignoreCase = true) }
            val enc = SecureStore.encrypt(token)
            return if (i >= 0) {
                val a = list[i].copy(tokenEnc = enc, name = name, avatarUrl = avatarUrl, scopes = scopes,
                    health = "OK", healthReason = "", cooldownUntil = 0)
                list[i] = a
                writeAccounts(list)
                a to false
            } else {
                val a = GtAccount(id = "a" + GtCrypto.randomHex(6), login = login, name = name, avatarUrl = avatarUrl,
                    scopes = scopes, tokenEnc = enc)
                list.add(a)
                writeAccounts(list)
                a to true
            }
        }

    fun updateAccount(id: String, change: (GtAccount) -> GtAccount) = synchronized(lock) {
        writeAccounts(accounts().map { if (it.id == id) change(it) else it })
    }

    fun removeAccount(id: String) = synchronized(lock) { writeAccounts(accounts().filterNot { it.id == id }) }

    fun token(accountId: String): String =
        accounts().firstOrNull { it.id == accountId }?.tokenEnc?.let { SecureStore.decrypt(it) }.orEmpty()

    // ── sessions ─────────────────────────────────────────────────────────────────
    fun sessions(): List<GtSession> = synchronized(lock) {
        val arr = try { JSONArray(prefs.getString(K_SESSIONS, "[]")) } catch (_: Exception) { JSONArray() }
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(GtSession::from) }
    }

    private fun writeSessions(list: List<GtSession>) {
        // The newest dozen: an ended session is kept only long enough to say how it ended.
        val keep = list.sortedByDescending { it.createdAt }.take(12)
        prefs.edit().putString(K_SESSIONS, JSONArray().apply { keep.forEach { put(it.toJson()) } }.toString()).apply()
    }

    fun session(id: String): GtSession? = sessions().firstOrNull { it.id == id }

    fun addSession(s: GtSession): GtSession = synchronized(lock) { writeSessions(sessions() + s); s }

    fun updateSession(id: String, change: (GtSession) -> GtSession): GtSession? = synchronized(lock) {
        var out: GtSession? = null
        writeSessions(sessions().map { if (it.id == id) change(it).also { n -> out = n } else it })
        out
    }

    /** The session in use: live, newest first. */
    fun activeSession(): GtSession? = sessions().filter { it.live }.maxByOrNull { it.createdAt }

    // ── the relay ────────────────────────────────────────────────────────────────
    fun broker(): GtBrokerState =
        GtBrokerState.from(prefs.getString(K_BROKER, null)?.let { try { JSONObject(it) } catch (_: Exception) { null } })

    fun saveBroker(b: GtBrokerState) { prefs.edit().putString(K_BROKER, b.toJson().toString()).apply() }

    /**
     * The per-install signing secret the Worker checks every pass against: 32 random bytes as hex,
     * made on first use. Deploying the Worker hands it the same value.
     */
    fun installSecret(): String = synchronized(lock) {
        val have = prefs.getString(K_SECRET, "").orEmpty().let { if (it.isBlank()) "" else SecureStore.decrypt(it) }
        if (have.length >= 32 && have.all { it in "0123456789abcdef" }) return have
        val fresh = GtCrypto.randomHex(32)
        prefs.edit().putString(K_SECRET, SecureStore.encrypt(fresh)).apply()
        fresh
    }

    // ── preferences ──────────────────────────────────────────────────────────────
    var autoRenew: Boolean
        get() = prefs.getBoolean(K_AUTORENEW, true)
        set(v) { prefs.edit().putBoolean(K_AUTORENEW, v).apply() }

    var exitPrefs: GtExitPrefs
        get() = GtExitPrefs.from(prefs.getString(K_EXIT, null)?.let { try { JSONObject(it) } catch (_: Exception) { null } })
        set(v) { prefs.edit().putString(K_EXIT, v.toJson().toString()).apply() }

    /**
     * The last runner's offer: countries VPN Gate lists (with how many servers) and the ones
     * Psiphon reaches. Kept so the country picker can say what each country carries — UDP or not —
     * before a session is connected; the next runner's own list replaces it.
     */
    fun exitCatalog(): Pair<Map<String, Int>, List<String>> = try {
        val o = JSONObject(prefs.getString(K_CATALOG, null) ?: "{}")
        val vg = o.optJSONObject("vpngate")
        val ps = o.optJSONArray("psiphon")
        Pair(vg?.keys()?.asSequence()?.associateWith { vg.optInt(it) } ?: emptyMap(),
            if (ps == null) emptyList() else (0 until ps.length()).map { ps.optString(it) })
    } catch (_: Exception) { Pair(emptyMap(), emptyList()) }

    fun saveExitCatalog(vpngate: Map<String, Int>, psiphon: List<String>) {
        prefs.edit().putString(K_CATALOG, JSONObject().put("vpngate", JSONObject(vpngate)).put("psiphon", JSONArray(psiphon))
            .put("at", System.currentTimeMillis()).toString()).apply()
    }

    /** Clean Cloudflare addresses that carried this app to its Worker, per network. */
    fun cleanIps(network: String): List<String> {
        val o = try { JSONObject(prefs.getString(K_CLEANIP, "{}")) } catch (_: Exception) { JSONObject() }
        val arr = o.optJSONObject(network)?.optJSONArray("ips") ?: return emptyList()
        return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
    }

    fun rememberCleanIps(network: String, ips: List<String>) {
        val o = try { JSONObject(prefs.getString(K_CLEANIP, "{}")) } catch (_: Exception) { JSONObject() }
        o.put(network, JSONObject().put("ips", JSONArray(ips.take(6))).put("at", System.currentTimeMillis()))
        // A handful of networks is plenty; the oldest go.
        while (o.length() > 12) {
            val oldest = o.keys().asSequence().minByOrNull { o.optJSONObject(it)?.optLong("at") ?: 0L } ?: break
            o.remove(oldest)
        }
        prefs.edit().putString(K_CLEANIP, o.toString()).apply()
    }

    /** Everything, as «ریست کامل» asks. The cloud side (repo, Worker) is left alone. */
    fun resetAll() { prefs.edit().clear().apply() }

    private companion object {
        const val K_ACCOUNTS = "accounts"
        const val K_SESSIONS = "sessions"
        const val K_BROKER = "broker"
        const val K_SECRET = "install_secret"
        const val K_AUTORENEW = "auto_renew"
        const val K_CLEANIP = "clean_ips"
        const val K_EXIT = "exit_prefs"
        const val K_CATALOG = "exit_catalog"
    }
}
