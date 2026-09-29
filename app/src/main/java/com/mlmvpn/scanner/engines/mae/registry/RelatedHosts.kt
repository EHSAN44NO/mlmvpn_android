package com.mlmvpn.scanner.engines.mae.registry

/**
 * The other domains a site loads its pages from -- CDNs for images, video, scripts, styles.
 *
 * Why it matters: a custom site routed by its own name opens, and then its images and videos come
 * from `*.somecdn.com`, which MAE did not know belonged to it. Those went the default route, were
 * filtered, and the site "opened but never finished loading" (reported on the phone, 2026-09-29).
 *
 * Read from the site's own public home page, fetched once through the route that already works
 * for it: the hosts in `src` / `href` / `srcset` / `content` / `url(...)` and absolute URLs in inline
 * scripts. Nothing about the user is involved -- it is the page anyone would get.
 *
 * Pure, unit-tested.
 */
object RelatedHosts {
    /** Never a site's own content: specs, schemas and namespaces that appear in every page. */
    private val NOISE = setOf(
        "w3.org", "schema.org", "ogp.me", "purl.org", "xmlns.com", "example.com", "creativecommons.org",
    )
    private val SECOND_LEVEL = setOf("co", "com", "net", "org", "gov", "edu", "ac")
    private val URL_IN_ATTR = Regex("""(?i)(?:src|href|srcset|content|data-src|poster|action)\s*=\s*["']?\s*(?:https?:)?//([a-z0-9.-]+\.[a-z]{2,})""")
    private val URL_ANYWHERE = Regex("""(?i)(?:https?:)?\\?/\\?/([a-z0-9-]+(?:\.[a-z0-9-]+)+\.[a-z]{2,})""")
    private val CSS_URL = Regex("""(?i)url\(\s*["']?(?:https?:)?//([a-z0-9.-]+\.[a-z]{2,})""")

    /**
     * Registrable domains (roughly eTLD+1) the page references, most used first, excluding the
     * site itself and noise, at most [max].
     */
    fun extract(html: String, site: String, max: Int = 20): List<String> {
        val counts = HashMap<String, Int>()
        for (re in listOf(URL_IN_ATTR, CSS_URL, URL_ANYWHERE)) {
            for (m in re.findAll(html)) {
                val d = registrable(m.groupValues[1]) ?: continue
                counts[d] = (counts[d] ?: 0) + 1
            }
        }
        val own = registrable(site)
        return counts.entries
            .filter { (d, _) -> d != own && d !in NOISE }
            .sortedByDescending { it.value }
            .map { it.key }
            .take(max)
    }

    /** `ei.phncdn.com` -> `phncdn.com`; `img.bbc.co.uk` -> `bbc.co.uk`. Null for junk. */
    fun registrable(host: String): String? {
        val h = host.lowercase().trim('.', ' ')
        val labels = h.split('.').filter { it.isNotEmpty() }
        if (labels.size < 2 || labels.last().length < 2 || labels.last().all(Char::isDigit)) return null
        val take = if (labels.size >= 3 && labels[labels.size - 2] in SECOND_LEVEL && labels.last().length == 2) 3 else 2
        return DomainNormalizer.normalize(labels.takeLast(take).joinToString("."))
    }
}
