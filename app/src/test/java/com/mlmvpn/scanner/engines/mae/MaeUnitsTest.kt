package com.mlmvpn.scanner.engines.mae

import com.mlmvpn.scanner.engines.mae.compile.MaeConfigCompiler
import com.mlmvpn.scanner.engines.mae.control.GrpcWire
import com.mlmvpn.scanner.engines.mae.model.Axis
import com.mlmvpn.scanner.engines.mae.model.AxisValue
import com.mlmvpn.scanner.engines.mae.model.Diagnosis
import com.mlmvpn.scanner.engines.mae.model.FailMode
import com.mlmvpn.scanner.engines.mae.model.FamilyPolicy
import com.mlmvpn.scanner.engines.mae.model.ForeignEgressStatus
import com.mlmvpn.scanner.engines.mae.model.Health
import com.mlmvpn.scanner.engines.mae.model.ObservedRequirements
import com.mlmvpn.scanner.engines.mae.model.RouteMetrics
import com.mlmvpn.scanner.engines.mae.model.ServicePolicy
import com.mlmvpn.scanner.engines.mae.model.Tri
import com.mlmvpn.scanner.engines.mae.policy.Candidate
import com.mlmvpn.scanner.engines.mae.policy.Decision
import com.mlmvpn.scanner.engines.mae.policy.RouteScorer
import com.mlmvpn.scanner.engines.mae.registry.DomainNormalizer
import com.mlmvpn.scanner.engines.mae.registry.ServiceRegistry
import com.mlmvpn.scanner.engines.mae.route.CircuitBreaker
import com.mlmvpn.scanner.engines.mae.route.DirectRoute
import com.mlmvpn.scanner.engines.mae.route.FragmentRoute
import com.mlmvpn.scanner.engines.mae.route.RouteKind
import com.mlmvpn.scanner.engines.mae.route.ServerlessRoute
import com.mlmvpn.scanner.engines.mae.route.WorkerRoute
import com.mlmvpn.scanner.engines.mae.store.MaeState
import com.mlmvpn.scanner.engines.mae.store.MaeStateCodec
import com.mlmvpn.scanner.engines.mae.store.MaeStore
import com.mlmvpn.scanner.engines.mae.store.SelectedService
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MaeUnitsTest {
    private val base = File("src/main/assets/serverless_v48_low_delay.json").readText()
    private val worker = WorkerRoute("mae.example.workers.dev", "11111111-1111-1111-1111-111111111111")
    private val warp = com.mlmvpn.scanner.engines.mae.route.WarpRoute("cHJpdg==", "cGVlcg==", "172.16.0.2", "2606:4700:110::2", listOf(1, 2, 3), "162.159.192.1:2408")
    private val providers = listOf(DirectRoute, ServerlessRoute, FragmentRoute, warp, worker)

    // ---------------------------------------------------------------- compiler

    private fun compileSample(): JSONObject {
        val registry = ServiceRegistry.parse(File("src/main/assets/mae/services.json").readText())
        val routes = listOf(
            MaeConfigCompiler.ServiceRoute(registry.get("google")!!, Decision.Use("serverless", FamilyPolicy.BOTH, 1.0, ""), false),
            MaeConfigCompiler.ServiceRoute(registry.get("gemini")!!, Decision.Use("worker", FamilyPolicy.V4_ONLY, 1.0, ""), true),
            MaeConfigCompiler.ServiceRoute(registry.get("telegram")!!, Decision.Use("fragment", FamilyPolicy.BOTH, 1.0, ""), false),
            MaeConfigCompiler.ServiceRoute(registry.get("chatgpt")!!, Decision.Block("none proven"), true),
        )
        return JSONObject(MaeConfigCompiler.compile(base, providers, routes, "/data/mae/api.sock"))
    }

    private fun rules(j: JSONObject) = j.getJSONObject("routing").getJSONArray("rules").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    @Test fun `compiler - one balancer per routed service, selecting the chosen family's tag`() {
        val j = compileSample()
        val bals = j.getJSONObject("routing").getJSONArray("balancers").let { a -> (0 until a.length()).associate { a.getJSONObject(it).getString("tag") to a.getJSONObject(it).getJSONArray("selector").getString(0) } }
        assertEquals("tcp-fragment-tls", bals["svc-google"])
        assertEquals("mae-wk4", bals["svc-gemini"])
        assertEquals("mae-frag", bals["svc-telegram"])
        assertEquals("a blocked service still has a balancer, pointed at block (switchable live)", "block", bals["svc-chatgpt"])
        assertEquals("block", bals["svc-chatgpt-u"])
        assertEquals("fragment carries no UDP", "block", bals["svc-telegram-u"])
        assertEquals("Serverless carries UDP", "tcp-fragment-tls", bals["svc-google-u"])
    }

    @Test fun `compiler - outbound stats and policy are enabled for passive learning`() {
        val j = compileSample()
        assertTrue(j.has("stats"))
        val sys = j.getJSONObject("policy").getJSONObject("system")
        assertTrue(sys.getBoolean("statsOutboundUplink"))
        assertTrue(sys.getBoolean("statsOutboundDownlink"))
        // The base's own policy levels are kept.
        assertTrue(j.getJSONObject("policy").has("levels"))
    }

    @Test fun `compiler - per-family worker outbounds use the proxied-outbound strategy field`() {
        val outs = compileSample().getJSONArray("outbounds").let { a -> (0 until a.length()).associate { a.getJSONObject(it).getString("tag") to a.getJSONObject(it) } }
        assertEquals("ForceIPv4", outs["mae-wk4"]!!.getString("targetStrategy"))
        assertEquals("ForceIPv6", outs["mae-wk6"]!!.getString("targetStrategy"))
        assertFalse(outs["mae-wkd"]!!.has("targetStrategy"))
        // Freedom takes its strategy from sockopt, not targetStrategy.
        assertEquals("UseIP", outs["mae-frag"]!!.getJSONObject("streamSettings").getJSONObject("sockopt").getString("domainStrategy"))
        assertFalse(outs["mae-frag"]!!.has("targetStrategy"))
    }

    @Test fun `compiler - a foreign service's traffic never falls through to direct, including v6 and UDP`() {
        val j = compileSample()
        val r = rules(j)
        val gem = r.filter { it.optJSONArray("domain")?.toString()?.contains("gemini.google.com") == true }
        assertTrue(gem.any { it.optString("network") == "udp" && it.optString("balancerTag") == "svc-gemini-u" })
        assertTrue(gem.any { it.optString("network") == "tcp" && it.optString("balancerTag") == "svc-gemini" })
        val bals = j.getJSONObject("routing").getJSONArray("balancers").let { a -> (0 until a.length()).associate { a.getJSONObject(it).getString("tag") to a.getJSONObject(it).getJSONArray("selector").getString(0) } }
        assertEquals("UDP of a service on a TCP-only foreign exit is blocked, not sent direct", "block", bals["svc-gemini-u"])
    }

    @Test fun `compiler - more specific domains win - gemini's googleapis host before google's googleapis`() {
        val r = rules(compileSample())
        val iGemini = r.indexOfFirst { it.optJSONArray("domain")?.toString()?.contains("domain:generativelanguage.googleapis.com") == true }
        val iGoogle = r.indexOfFirst { it.optJSONArray("domain")?.toString()?.contains("\"domain:googleapis.com\"") == true }
        assertTrue(iGemini >= 0 && iGoogle >= 0 && iGemini < iGoogle)
    }

    @Test fun `compiler - fail-closed service goes to block, Telegram matches by IP range too`() {
        val r = rules(compileSample())
        assertTrue(r.any { it.optJSONArray("domain")?.toString()?.contains("chatgpt.com") == true && it.optString("balancerTag") == "svc-chatgpt" })
        assertTrue(r.any { it.optJSONArray("ip")?.toString()?.contains("149.154.160.0/20") == true && it.optString("balancerTag") == "svc-telegram" })
    }

    @Test fun `compiler - MAE rules sit after DNS plumbing and before Serverless's own rules, API rule first`() {
        val r = rules(compileSample())
        assertEquals("mae-api", r[0].optString("outboundTag"))
        val firstOurs = r.indexOfFirst { it.has("balancerTag") }
        val dnsOut = r.indexOfFirst { it.optString("outboundTag") == "dns-out" }
        val serverlessDirect = r.indexOfFirst { it.optJSONArray("domain")?.toString()?.contains("geosite:openai") == true }
        assertTrue(dnsOut < firstOurs && firstOurs < serverlessDirect)
    }

    @Test fun `compiler - API listens only on a Unix socket, services' domains are added to FakeDNS, version guard dropped`() {
        val j = compileSample()
        val api = j.getJSONArray("inbounds").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.first { it.optString("tag") == "mae-api-in" }
        assertEquals("/data/mae/api.sock", api.getString("listen"))
        assertFalse(j.has("version"))
        assertEquals("MAE", j.getString("remarks"))
        val fake = j.getJSONObject("dns").getJSONArray("servers").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.first { it.optString("address") == "fakedns" }
        assertTrue(fake.getJSONArray("domains").toString().contains("domain:gemini.google.com"))
    }

    @Test fun `probe config - one socks inbound per tag, routed straight to that outbound`() {
        val j = JSONObject(MaeConfigCompiler.compileProbe(base, providers, mapOf("mae-frag" to 30001, "mae-wk4" to 30002)))
        assertEquals(2, j.getJSONArray("inbounds").length())
        val r = rules(j)
        assertTrue(r.any { it.optJSONArray("inboundTag")?.getString(0) == "probe-mae-wk4" && it.optString("outboundTag") == "mae-wk4" })
        assertFalse(j.has("api"))
    }

    // ---------------------------------------------------------------- scorer

    private fun cand(id: String, kind: RouteKind, rtt: Double, bps: Double, usable: Boolean? = true,
                     fams: Set<FamilyPolicy> = if (kind == RouteKind.FOREIGN) emptySet() else setOf(FamilyPolicy.BOTH)) =
        Candidate(id, kind, RouteMetrics(successes = 5, rttMs = rtt, throughputBps = bps, lastOkAt = 1, lastTestedAt = 1), Health.HEALTHY, usable = usable, acceptedFamilies = fams)

    @Test fun `scorer - hysteresis keeps a current route that is only slightly worse`() {
        val cur = cand("serverless", RouteKind.BYPASS, 110.0, 2_000_000.0)
        val chal = cand("fragment", RouteKind.BYPASS, 100.0, 2_200_000.0)
        val d = RouteScorer.decide(ObservedRequirements(), FailMode.OPEN, false, listOf(cur, chal), current = "serverless")
        assertEquals("serverless", (d as Decision.Use).routeId)
        val far = cand("fragment", RouteKind.BYPASS, 40.0, 9_000_000.0)
        val d2 = RouteScorer.decide(ObservedRequirements(), FailMode.OPEN, false, listOf(cur.copy(metrics = cur.metrics.copy(rttMs = 900.0, throughputBps = 50_000.0)), far), current = "serverless")
        assertEquals("fragment", (d2 as Decision.Use).routeId)
    }

    @Test fun `scorer - a fast route with an Iranian IP is never chosen for a service that needs a foreign exit`() {
        val req = ObservedRequirements(needsForeignGeo = AxisValue(Tri.YES, 0.9))
        val fastLocal = cand("serverless", RouteKind.BYPASS, 30.0, 9_000_000.0)
        val slowForeign = cand("worker", RouteKind.FOREIGN, 300.0, 300_000.0, fams = setOf(FamilyPolicy.V4_ONLY))
        val d = RouteScorer.decide(req, FailMode.CLOSED, false, listOf(fastLocal, slowForeign))
        assertEquals("worker", (d as Decision.Use).routeId)
        assertEquals(FamilyPolicy.V4_ONLY, d.family)
    }

    @Test fun `scorer - quota-limited route loses for heavy traffic when a free route works`() {
        val free = cand("fragment", RouteKind.BYPASS, 150.0, 1_500_000.0)
        val quota = cand("worker", RouteKind.FOREIGN, 140.0, 1_600_000.0, fams = setOf(FamilyPolicy.BOTH)).copy(quotaLimited = true, cost = 0.3)
        val d = RouteScorer.decide(ObservedRequirements(), FailMode.OPEN, true, listOf(free, quota))
        assertEquals("fragment", (d as Decision.Use).routeId)
    }

    @Test fun `circuit breaker - opens after repeated failures, backs off exponentially, allows one recovery probe`() {
        val b = CircuitBreaker(threshold = 3, baseBackoffMs = 1000, maxBackoffMs = 60_000)
        repeat(3) { b.failure("worker", 0) }
        assertEquals(Health.UNAVAILABLE, b.health("worker", 500))
        assertFalse(b.allow("worker", 500))
        assertTrue("one recovery probe after the backoff", b.allow("worker", 1500))
        assertFalse("but only one at a time", b.allow("worker", 1600))
        b.failure("worker", 1600)
        assertFalse("backoff doubled", b.allow("worker", 1600 + 1500))
        assertTrue(b.allow("worker", 1600 + 2100))
        b.success("worker")
        assertEquals(Health.HEALTHY, b.health("worker", 5000))
    }

    // ---------------------------------------------------------------- store

    @Test fun `state codec round-trips everything that matters`() {
        val s = MaeState(
            onboarded = true,
            selected = listOf(SelectedService("gemini"), SelectedService("site:example.com", paused = true)),
            customSites = listOf("example.com"),
            diagnoses = mapOf("gemini|n" to Diagnosis(mapOf(Axis.GEO_RESTRICTION to AxisValue(Tri.YES, 0.84, listOf("x"))), 5)),
            policies = mapOf("gemini|n" to ServicePolicy("gemini", "n", "worker", FamilyPolicy.V4_ONLY, 0.7, "why", 9, pinned = true)),
            metrics = mapOf("worker|gemini|n" to RouteMetrics(3, 1, 0, 120.0, 900_000.0, 8, 8)),
            egress = mapOf("gemini|n" to ForeignEgressStatus.Proven("worker", FamilyPolicy.V4_ONLY), "x|n" to ForeignEgressStatus.NoneFound(mapOf("worker:BOTH" to "cf"))),
            liveApiWorks = false,
        )
        assertEquals(s, MaeStateCodec.decode(MaeStateCodec.encode(s)))
    }

    @Test fun `state from a newer schema is refused rather than misread`() {
        val future = JSONObject(MaeStateCodec.encode(MaeState())).put("schema", MaeStateCodec.SCHEMA + 1).toString()
        assertTrue(runCatching { MaeStateCodec.decode(future) }.isFailure)
    }

    @Test fun `corrupted state file is moved aside and MAE starts fresh without crashing`() {
        val dir = Files.createTempDirectory("mae").toFile()
        File(dir, "mae").mkdirs()
        File(dir, "mae/state.json").writeText("{ not json")
        val store = MaeStore(dir, {}, writeAsync = false)
        assertFalse(store.current.onboarded)
        assertTrue(File(dir, "mae/state.corrupt.json").exists())
        store.update { it.copy(onboarded = true) }
        assertTrue(MaeStore(dir, {}, writeAsync = false).current.onboarded)
        dir.deleteRecursively()
    }

    // ---------------------------------------------------------------- registry, domains, wire

    @Test fun `registry parses the bundled file and matches hosts by the longest domain`() {
        val r = ServiceRegistry.parse(File("src/main/assets/mae/services.json").readText())
        assertTrue(r.services.size >= 17)
        assertEquals("gemini", r.forHost("generativelanguage.googleapis.com")!!.id)
        assertEquals("google", r.forHost("www.googleapis.com")!!.id)
        assertEquals("youtube", r.forHost("rr3---sn-abc.googlevideo.com")!!.id)
        assertNull(r.forHost("example.org"))
    }

    @Test fun `domain normalisation`() {
        assertEquals("example.com", DomainNormalizer.normalize("https://WWW.Example.com/path?q=1"))
        assertEquals("sub.example.co.uk", DomainNormalizer.normalize("sub.example.co.uk."))
        assertEquals("xn--mgbh0fb.xn--mgba3a4f16a", DomainNormalizer.normalize("مثال.ایران"))
        assertNull(DomainNormalizer.normalize("localhost"))
        assertNull(DomainNormalizer.normalize("192.168.1.1"))
        assertNull(DomainNormalizer.normalize("not a domain"))
        assertNull(DomainNormalizer.normalize(""))
    }

    @Test fun `gRPC OverrideBalancerTarget request bytes`() {
        val msg = GrpcWire.overrideBalancerTarget("svc-a", "mae-wk4")
        val expected = byteArrayOf(0x0a, 5) + "svc-a".toByteArray() + byteArrayOf(0x12, 7) + "mae-wk4".toByteArray()
        assertTrue(expected.contentEquals(msg))
        val framed = GrpcWire.frame(msg)
        assertEquals(0, framed[0].toInt())
        assertEquals(msg.size, framed[4].toInt())
        assertEquals(msg.size + 5, framed.size)
    }

    @Test fun `bundle - Google's account domains follow Gemini abroad, YouTube keeps its own`() {
        val registry = ServiceRegistry.parse(File("src/main/assets/mae/services.json").readText())
        val gem = registry.get("gemini")!!
        val routes = listOf(
            MaeConfigCompiler.ServiceRoute(registry.get("google")!!, Decision.Use("fragment", FamilyPolicy.BOTH, 1.0, ""), false),
            MaeConfigCompiler.ServiceRoute(registry.get("youtube")!!, Decision.Use("fragment", FamilyPolicy.BOTH, 1.0, ""), false),
            MaeConfigCompiler.ServiceRoute(gem, Decision.Use("worker", FamilyPolicy.V4_ONLY, 1.0, ""), true, gem.bundle),
        )
        val r = rules(JSONObject(MaeConfigCompiler.compile(base, providers, routes)))
        fun owner(domain: String) = r.firstOrNull { it.optJSONArray("domain")?.toString()?.contains("\"domain:$domain\"") == true }?.optString("balancerTag")
        assertEquals("svc-gemini", owner("googleapis.com"))
        assertEquals("svc-gemini", owner("google.com"))
        assertEquals("svc-gemini", owner("robinfrontend-pa.googleapis.com"))
        assertEquals("svc-youtube", owner("youtube.com"))
        // Without the bundle, Google keeps its own domains.
        val plain = rules(JSONObject(MaeConfigCompiler.compile(base, providers, routes.map { it.copy(bundle = emptyList()) })))
        assertEquals("svc-google", plain.firstOrNull { it.optJSONArray("domain")?.toString()?.contains("\"domain:googleapis.com\"") == true }?.optString("balancerTag"))
    }

    @Test fun `no outbound tag is a prefix of another (balancer selectors match by prefix)`() {
        val tags = providers.flatMap { p -> p.families.map { p.tag(it) } }.distinct() + listOf("block", "tcp-direct", "udp-direct", "tcp-fragment")
        val ours = providers.flatMap { p -> p.families.map { p.tag(it) } }.distinct()
        for (a in ours) for (b in tags) if (a != b) assertFalse("$a is a prefix of $b", b.startsWith(a))
    }

    @Test fun `DNS path - the base's DoH server and its rule are repointed, others untouched`() {
        val j = JSONObject(MaeConfigCompiler.compile(base, providers, emptyList(), dns = com.mlmvpn.scanner.engines.mae.model.DnsPath("https://8.8.8.8/dns-query", "mae-dd")))
        val srv = j.getJSONObject("dns").getJSONArray("servers").let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
        assertEquals("https://8.8.8.8/dns-query", srv.first { it.optString("tag") == "no-filter-dns" }.getString("address"))
        assertTrue(srv.any { it.optString("address") == "fakedns" })
        val r = rules(j).first { it.optJSONArray("inboundTag")?.toString()?.contains("no-filter-dns") == true }
        assertEquals("mae-dd", r.getString("outboundTag"))
        // Base path: untouched.
        val b = JSONObject(MaeConfigCompiler.compile(base, providers, emptyList(), dns = com.mlmvpn.scanner.engines.mae.model.DnsPath.BASE))
        assertEquals("tcp-fragment-tls", rules(b).first { it.optJSONArray("inboundTag")?.toString()?.contains("no-filter-dns") == true }.getString("outboundTag"))
    }

    @Test fun `direct route has one outbound per family, freedom strategies`() {
        val outs = DirectRoute.outbounds().associate { it.getString("tag") to it.getJSONObject("streamSettings").getJSONObject("sockopt").getString("domainStrategy") }
        assertEquals(mapOf("mae-d4" to "ForceIPv4", "mae-d6" to "ForceIPv6", "mae-dd" to "UseIP"), outs)
    }

    @Test fun `default route - base fragment rules go direct when fragment is dead, MAE rules untouched`() {
        val registry = ServiceRegistry.parse(File("src/main/assets/mae/services.json").readText())
        val routes = listOf(MaeConfigCompiler.ServiceRoute(registry.get("gemini")!!, Decision.Use("worker", FamilyPolicy.V4_ONLY, 1.0, ""), true))
        val j = JSONObject(MaeConfigCompiler.compile(base, providers, routes, defaultVia = "mae-dd"))
        val r = rules(j)
        assertFalse(r.any { it.optString("outboundTag") in setOf("tcp-fragment-tls", "tcp-fragment") && !it.has("inboundTag") })
        assertTrue(r.any { it.optString("outboundTag") == "mae-dd" && it.optString("network") == "tcp" })
        assertTrue(r.any { it.optString("balancerTag") == "svc-gemini" })
        // DNS plumbing keeps its own route.
        assertEquals("tcp-fragment-tls", r.first { it.optJSONArray("inboundTag")?.toString()?.contains("no-filter-dns") == true }.getString("outboundTag"))
    }

    @Test fun `WARP outbound - WireGuard with noise, per-family strategy, identity fields`() {
        val outs = warp.outbounds().associateBy { it.getString("tag") }
        assertEquals(setOf("mae-wg4", "mae-wg6", "mae-wgd"), outs.keys)
        val o = outs["mae-wg4"]!!
        assertEquals("wireguard", o.getString("protocol"))
        assertEquals("ForceIPv4", o.getJSONObject("settings").getString("domainStrategy"))
        assertEquals("[1,2,3]", o.getJSONObject("settings").getJSONArray("reserved").toString())
        assertEquals("162.159.192.1:2408", o.getJSONObject("settings").getJSONArray("peers").getJSONObject(0).getString("endpoint"))
        assertEquals("noise", o.getJSONObject("streamSettings").getJSONObject("finalmask").getJSONArray("udp").getJSONObject(0).getString("type"))
        assertEquals(com.mlmvpn.scanner.engines.mae.route.RouteKind.BYPASS, warp.kind)
    }

    @Test fun `WARP identity file is parsed from aether's toml`() {
        val toml = listOf("device_id = \"abc\"", "wg_private_key = \"cHJpdg==\"", "ipv4 = \"172.16.0.2\"", "client_id = \"AQID\"")
            .joinToString(System.lineSeparator())
        val t = com.mlmvpn.scanner.engines.mae.egress.MaeWarp.parseToml(toml)
        assertEquals("cHJpdg==", t["wg_private_key"])
        assertEquals("172.16.0.2", t["ipv4"])
        assertEquals("AQID", t["client_id"])
    }

    @Test fun `related hosts - a site's CDN domains are found in its page, noise and itself excluded`() {
        val html = listOf(
            "<html><head><link rel=stylesheet href=\"https://ei.phncdn.com/www-static/css/a.css\">",
            "<script src=\"//cdn1d-static-shared.phncdn.com/js/b.js\"></script>",
            "<meta property=\"og:image\" content=\"https://di.phncdn.com/thumb.jpg\">",
            "<img srcset=\"https://img.example-cdn.net/p.jpg 2x\">",
            "<a href=\"https://www.site.com/about\">x</a><svg xmlns=\"http://www.w3.org/2000/svg\"></svg>",
            "<style>.b{background:url('https://static.site-assets.co.uk/bg.png')}</style>",
            "<script>var u = \"https:\\/\\/media.videocdn.io\\/v.mp4\";</script></head></html>",
        ).joinToString(System.lineSeparator())
        val hosts = com.mlmvpn.scanner.engines.mae.registry.RelatedHosts.extract(html, "site.com")
        assertEquals("phncdn.com", hosts.first())
        assertTrue(hosts.containsAll(listOf("example-cdn.net", "site-assets.co.uk", "videocdn.io")))
        assertFalse("the site itself", hosts.contains("site.com"))
        assertFalse("namespaces are not content", hosts.contains("w3.org"))
        val footer = "<footer><a href=\"https://beian.miit.gov.cn/\">ICP</a> <a class=x href='https://www.zhihu.com/org/x'>zhihu</a></footer>"
        val withLinks = com.mlmvpn.scanner.engines.mae.registry.RelatedHosts.extract(html + footer, "site.com")
        assertFalse("plain links are not loaded resources", withLinks.contains("miit.gov.cn") || withLinks.contains("zhihu.com"))
    }

    @Test fun `related hosts - registrable domain`() {
        val r = com.mlmvpn.scanner.engines.mae.registry.RelatedHosts
        assertEquals("phncdn.com", r.registrable("ei.phncdn.com"))
        assertEquals("bbc.co.uk", r.registrable("img.bbc.co.uk"))
        assertNull(r.registrable("10.0.0.1"))
    }
    @Test fun `installed app - main domain guessed from the package name`() {
        val g = { p: String -> ServiceRegistry.domainGuesses(p) }
        assertEquals("snapchat.com", g("com.snapchat.android").first())
        assertEquals("divar.ir", g("ir.divar").first())
        assertTrue(g("org.thoughtcrime.securesms").contains("thoughtcrime.org"))
        assertTrue(g("com.pinterest").contains("pinterest.com"))
        assertTrue(g("x").isEmpty())
    }

    @Test fun `related hosts - shared CDNs keep the exact host, trackers and brand country domains are dropped`() {
        val html = listOf(
            "<script src=\"https://d1a2b3c4.cloudfront.net/app.js\"></script>",
            "<script src=\"https://cdn.jsdelivr.net/npm/x/dist/x.js\"></script>",
            "<script src=\"https://www.googletagmanager.com/gtm.js\"></script>",
            "<link rel=icon href=\"https://www.google.com.pk/favicon.ico\">",
        ).joinToString(System.lineSeparator())
        val hosts = com.mlmvpn.scanner.engines.mae.registry.RelatedHosts.extract(html, "site.com", knownBrands = setOf("google"))
        assertTrue(hosts.contains("d1a2b3c4.cloudfront.net"))
        assertTrue(hosts.contains("cdn.jsdelivr.net"))
        assertFalse(hosts.contains("cloudfront.net"))
        assertFalse(hosts.contains("googletagmanager.com"))
        assertFalse(hosts.contains("google.com.pk"))
    }
}
