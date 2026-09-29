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
    /**
     * Analytics and ads: loaded by nearly every page, never what makes the app work. Routing them
     * would only drag trackers onto a foreign exit (measured: Gemini's page named four of them).
     */
    private val TRACKERS = setOf(
        "googletagmanager.com", "google-analytics.com", "doubleclick.net", "googlesyndication.com",
        "googleadservices.com", "ads-twitter.com", "hotjar.com", "clarity.ms", "facebook.net",
        "segment.io", "segment.com", "mixpanel.com", "amplitude.com", "sentry.io", "newrelic.com",
        "nr-data.net", "adsrvr.org", "criteo.com", "taboola.com", "outbrain.com", "yandex.ru", "mc.yandex.ru",
    )
    /**
     * Infrastructure shared by the whole internet. The registrable domain would route thousands of
     * unrelated sites with this app, so for these the exact host is kept (`d1a2b3.cloudfront.net`,
     * `cdn.jsdelivr.net`), never the whole domain.
     */
    private val SHARED = setOf(
        "cloudfront.net", "amazonaws.com", "jsdelivr.net", "unpkg.com", "cloudflare.com", "akamaihd.net",
        "akamaized.net", "akamai.net", "fastly.net", "azureedge.net", "b-cdn.net", "googleusercontent.com",
        "gstatic.com", "googleapis.com", "cloudinary.com", "imgix.net", "website-files.com", "vercel.app",
        "netlify.app", "github.io", "pages.dev", "workers.dev", "herokuapp.com", "firebaseapp.com", "wp.com",
    )
    private val SECOND_LEVEL = setOf("co", "com", "net", "org", "gov", "edu", "ac")
    /** Resource attributes. `href` counts only on <link> (stylesheets, preloads): <a> tags are removed first. */
    private val URL_IN_ATTR = Regex("""(?i)(?:src|href|srcset|data-src|poster)\s*=\s*["']?\s*(?:https?:)?//([a-z0-9.-]+\.[a-z]{2,})""")
    private val OG_IMAGE = Regex("""(?i)content\s*=\s*["']\s*https?://([a-z0-9.-]+\.[a-z]{2,})/[^"']*\.(?:png|jpe?g|webp|gif|svg)""")
    /**
     * Absolute URLs anywhere else (inline scripts, JSON, possibly escaped as `\/\/`) count only
     * when they name a FILE: a script, style, image, font, media or manifest. A bare site URL in
     * a script is a link (DeepSeek's page lists its licence and social pages in its JS bundle).
     */
    private val ASSET_URL = Regex("""(?i)(?:https?:)?\\?/\\?/([a-z0-9-]+(?:\.[a-z0-9-]+)+\.[a-z]{2,})((?:\\?/[^"'\s<>()\\]*)+?)\.(?:js|mjs|css|png|jpe?g|webp|gif|svg|ico|avif|woff2?|ttf|otf|mp4|webm|m3u8|mpd|ts|m4s|mp3|json|wasm)\b""")
    private val ANCHOR = Regex("""(?is)<a\s[^>]*>""")
    private val CSS_URL = Regex("""(?i)url\(\s*["']?(?:https?:)?//([a-z0-9.-]+\.[a-z]{2,})""")

    /**
     * Registrable domains (roughly eTLD+1) the page loads resources from, most used first,
     * excluding the site itself, noise, trackers and [knownBrands]' country variants, at most [max].
     *
     * [knownBrands] are the first labels of domains that already belong to some app (`google` of
     * google.com): `google.com.pk` is Google in Pakistan, not part of the site being learned.
     */
    fun extract(html: String, site: String, max: Int = 20, knownBrands: Set<String> = emptySet()): List<String> {
        // Plain links (<a href>) are places the user MIGHT go, not things the page loads: a
        // footer full of licence and social links (deepseek.com: miit.gov.cn, zhihu.com, …, seen
        // on the phone 2026-09-29) must not pull those sites onto this app's route.
        val loaded = ANCHOR.replace(html, " ")
        val counts = HashMap<String, Int>()
        for (re in listOf(URL_IN_ATTR, OG_IMAGE, CSS_URL, ASSET_URL)) {
            for (m in re.findAll(loaded)) {
                val d = key(m.groupValues[1]) ?: continue
                counts[d] = (counts[d] ?: 0) + 1
            }
        }
        val own = registrable(site)
        val ownBrand = own?.substringBefore('.')
        return counts.entries
            .filter { (d, _) -> d != own && d !in NOISE && d !in TRACKERS }
            .filter { (d, _) -> val brand = brandOf(d); brand != ownBrand || d == own }
            .filter { (d, _) -> brandOf(d) !in knownBrands }
            .sortedByDescending { it.value }
            .map { it.key }
            .take(max)
    }

    /** What a host is routed by: its registrable domain, or the exact host on shared infrastructure. */
    fun key(host: String): String? {
        val r = registrable(host) ?: return null
        return if (r in SHARED) DomainNormalizer.normalize(host.lowercase().trim('.')) else r
    }

    private fun brandOf(d: String): String = (registrable(d) ?: d).substringBefore('.')

    /** `ei.phncdn.com` -> `phncdn.com`; `img.bbc.co.uk` -> `bbc.co.uk`. Null for junk. */
    fun registrable(host: String): String? {
        val h = host.lowercase().trim('.', ' ')
        val labels = h.split('.').filter { it.isNotEmpty() }
        if (labels.size < 2 || labels.last().length < 2 || labels.last().all(Char::isDigit)) return null
        val take = if (labels.size >= 3 && labels[labels.size - 2] in SECOND_LEVEL && labels.last().length == 2) 3 else 2
        return DomainNormalizer.normalize(labels.takeLast(take).joinToString("."))
    }
}
