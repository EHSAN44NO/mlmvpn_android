package com.mlmvpn.scanner.engines.mae.registry

import com.mlmvpn.scanner.engines.mae.model.FailMode
import com.mlmvpn.scanner.engines.mae.model.ProbeSpec
import com.mlmvpn.scanner.engines.mae.model.ServiceDef
import com.mlmvpn.scanner.engines.mae.model.ServiceHints
import org.json.JSONArray
import org.json.JSONObject
import java.net.IDN

/**
 * The catalogue of known services, parsed from `assets/mae/services.json`.
 *
 * Service knowledge lives in that one data file, not in code: adding a service or a domain is a
 * data change. The file carries a `schema` (parser compatibility) and a `version` (content), so a
 * later signed remote copy can replace the bundled one only when it is newer and parses under a
 * schema this build understands. A remote copy is data only -- nothing in it is ever executed.
 */
class ServiceRegistry private constructor(
    val version: Int,
    val services: List<ServiceDef>,
) {
    private val byId = services.associateBy { it.id }
    fun get(id: String): ServiceDef? = byId[id]

    /**
     * The service a host belongs to, by its registered domains (subdomains included). The longest
     * matching domain wins, so `generativelanguage.googleapis.com` is Gemini, not Google.
     */
    fun forHost(host: String, among: List<ServiceDef> = services): ServiceDef? {
        val h = host.lowercase().trimEnd('.')
        return among.flatMap { s -> s.domains.map { d -> s to d } }
            .filter { (_, d) -> h == d || h.endsWith(".$d") }
            .maxByOrNull { (_, d) -> d.length }?.first
    }

    companion object {
        const val SCHEMA = 1

        fun parse(text: String): ServiceRegistry {
            val root = JSONObject(text)
            require(root.optInt("schema", 0) == SCHEMA) { "unsupported registry schema" }
            val arr = root.getJSONArray("services")
            val list = (0 until arr.length()).mapNotNull { i -> runCatching { parseService(arr.getJSONObject(i)) }.getOrNull() }
            return ServiceRegistry(root.optInt("version", 0), list)
        }

        private fun parseService(o: JSONObject): ServiceDef {
            val h = o.optJSONObject("hints") ?: JSONObject()
            return ServiceDef(
                id = o.getString("id"),
                nameKey = o.optString("nameKey", o.getString("id")),
                displayName = o.optString("displayName", o.getString("id")),
                packages = o.optJSONArray("packages").strings(),
                domains = o.optJSONArray("domains").strings().mapNotNull { DomainNormalizer.normalize(it) },
                bundle = o.optJSONArray("bundle").strings().mapNotNull { DomainNormalizer.normalize(it) },
                ipRanges = o.optJSONArray("ipRanges").strings(),
                probes = o.optJSONArray("probes")?.let { p ->
                    (0 until p.length()).map { j ->
                        val po = p.getJSONObject(j)
                        ProbeSpec(
                            url = po.getString("url"),
                            geoSignatures = po.optJSONArray("geoSignatures").strings().map { it.lowercase() },
                            okSignatures = po.optJSONArray("okSignatures").strings().map { it.lowercase() },
                        )
                    }
                }.orEmpty(),
                hints = ServiceHints(
                    likelyCensored = h.optBoolean("likelyCensored"),
                    likelyGeoRestricted = h.optBoolean("likelyGeoRestricted"),
                    usesUdp = h.optBoolean("usesUdp"),
                    prefersQuic = h.optBoolean("prefersQuic"),
                    affinitySensitive = h.optBoolean("affinitySensitive"),
                    heavy = h.optBoolean("heavy"),
                    refusesCloudflare = h.optBoolean("refusesCloudflare"),
                    failMode = if (h.optString("failMode") == "CLOSED") FailMode.CLOSED else FailMode.OPEN,
                ),
            )
        }

        /** A user-entered site as a service of its own; null when the text is not a usable domain. */
        fun customSite(input: String): ServiceDef? {
            val d = DomainNormalizer.normalize(input) ?: return null
            return ServiceDef(
                id = "site:$d", nameKey = "custom", displayName = d, domains = listOf(d),
                probes = listOf(ProbeSpec("https://$d/")), custom = true,
            )
        }

        /** An installed app the user picked, routed by its main domain and the ones learned later. */
        fun customApp(id: String, pkg: String, label: String, domain: String) = ServiceDef(
            id = id, nameKey = "custom", displayName = label, packages = listOf(pkg), domains = listOf(domain),
            probes = listOf(ProbeSpec("https://$domain/")), custom = true,
        )

        /**
         * Likely main domains for an Android package, most likely first: `com.snapchat.android`
         * -> `snapchat.com`; `ir.divar.android` -> `divar.ir`; `org.thoughtcrime.securesms` ->
         * `thoughtcrime.org`. Only guesses: the caller keeps the first one that resolves.
         */
        fun domainGuesses(pkg: String): List<String> {
            val parts = pkg.lowercase().split('.').filter { it.isNotBlank() }
            if (parts.size < 2) return emptyList()
            val tld = parts[0]
            val names = parts.drop(1).filterNot { it in GENERIC_SEGMENTS }.take(2)
            val tlds = (if (tld.length in 2..4) listOf(tld) else emptyList()) + listOf("com", "app", "io", "net", "org")
            return names.flatMap { n -> tlds.map { "$n.$it" } }.mapNotNull { DomainNormalizer.normalize(it) }.distinct().take(8)
        }

        private val GENERIC_SEGMENTS = setOf("android", "app", "apps", "mobile", "client", "main", "lite", "free", "pro", "messenger")

        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else (0 until length()).map { getString(it) }
    }
}

/**
 * Turns what a user types into a registrable host, or null.
 * `https://Www.Example.com/path?q` -> `example.com`; IDN -> punycode; IPs, localhost, single
 * labels and junk are refused.
 */
object DomainNormalizer {
    private val LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

    fun normalize(input: String): String? {
        var s = input.trim().lowercase()
        if (s.isEmpty() || s.length > 253) return null
        s = s.substringAfter("://")
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringAfterLast('@').substringBefore(':').trimEnd('.')
        if (s.startsWith("www.")) s = s.removePrefix("www.")
        if (s.isEmpty()) return null
        val ascii = runCatching { IDN.toASCII(s, IDN.ALLOW_UNASSIGNED) }.getOrNull()?.lowercase() ?: return null
        val labels = ascii.split('.')
        if (labels.size < 2) return null
        if (labels.all { it.all(Char::isDigit) }) return null // an IPv4 literal
        if (!labels.all { LABEL.matches(it) }) return null
        if (labels.last().all(Char::isDigit)) return null
        return ascii
    }
}
