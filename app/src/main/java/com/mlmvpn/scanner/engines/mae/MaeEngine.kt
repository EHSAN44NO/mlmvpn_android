package com.mlmvpn.scanner.engines.mae

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.mlmvpn.core.warp.VlessXrayInjector
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.data.CloudManager
import com.mlmvpn.scanner.engines.game.booster.memory.NetworkKey
import com.mlmvpn.scanner.engines.mae.compile.MaeConfigCompiler
import com.mlmvpn.scanner.engines.mae.control.XrayApiClient
import com.mlmvpn.scanner.engines.mae.egress.MaeEgressDeployer
import com.mlmvpn.scanner.engines.mae.egress.MaeWarp
import com.mlmvpn.scanner.engines.mae.model.Diagnosis
import com.mlmvpn.scanner.engines.mae.model.DnsPath
import com.mlmvpn.scanner.engines.mae.model.FamilyPolicy
import com.mlmvpn.scanner.engines.mae.model.ForeignEgressStatus
import com.mlmvpn.scanner.engines.mae.model.ServiceDef
import com.mlmvpn.scanner.engines.mae.policy.Decision
import com.mlmvpn.scanner.engines.mae.policy.Discovery
import com.mlmvpn.scanner.engines.mae.policy.PolicyEngine
import com.mlmvpn.scanner.engines.mae.policy.ProbeBudget
import com.mlmvpn.scanner.engines.mae.probe.NetProber
import com.mlmvpn.scanner.engines.mae.probe.ProbeRoute
import com.mlmvpn.scanner.engines.mae.registry.ServiceRegistry
import com.mlmvpn.scanner.engines.mae.route.CircuitBreaker
import com.mlmvpn.scanner.engines.mae.route.DirectRoute
import com.mlmvpn.scanner.engines.mae.route.FragmentRoute
import com.mlmvpn.scanner.engines.mae.route.RouteKind
import com.mlmvpn.scanner.engines.mae.route.UsExitRoute
import com.mlmvpn.scanner.engines.mae.route.RouteProvider
import com.mlmvpn.scanner.engines.mae.route.ServerlessRoute
import com.mlmvpn.scanner.engines.mae.route.UserConfigRoute
import com.mlmvpn.scanner.engines.mae.route.WarpRoute
import com.mlmvpn.scanner.engines.mae.route.WorkerRoute
import com.mlmvpn.scanner.engines.mae.store.MaeState
import com.mlmvpn.scanner.engines.mae.store.MaeStore
import com.mlmvpn.scanner.engines.mae.store.SelectedService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import org.json.JSONObject
import java.net.ServerSocket

/**
 * MAE's control plane. The data plane is the existing Xray core run by [MyVpnService]; this object
 * decides what that core is told:
 *
 *  - keeps the user's services and what was learned about them per network ([MaeStore]);
 *  - discovers routes in the background with a staged, budgeted prober, one service at a time,
 *    user-reported failures first ([enqueue] with priority);
 *  - compiles one Xray config (Serverless base + per-service balancers) and connects;
 *  - applies route changes live through the Xray API when the spike proved it works on this
 *    phone, otherwise by recompiling and reconnecting;
 *  - learns passively from Xray's per-outbound counters while connected.
 *
 * Every failure inside MAE is contained here: a provider that throws marks itself unhealthy, a
 * discovery that throws marks the service UNKNOWN, and the tunnel keeps its last good config.
 */
object MaeEngine {
    private const val TAG = "MAE"
    const val NODE_ID = "mae"
    private const val BASE_ASSET = "serverless_v48_low_delay.json"
    private const val DNS_TTL_MS = 6 * 3600_000L

    enum class Phase { WAITING, TESTING, READY, NO_FOREIGN_ROUTE, BLOCKED, PAUSED }

    data class ServiceView(
        val def: ServiceDef,
        val phase: Phase,
        val routeId: String?,
        val primary: Diagnosis.Primary,
        val paused: Boolean,
    )

    sealed class Repair {
        /** A different route now carries the service. */
        data class Fixed(val serviceId: String) : Repair()
        /** The route was re-checked and still works: nothing needed changing. */
        data class Verified(val serviceId: String) : Repair()
        data class NotFound(val serviceId: String) : Repair()
        data class Busy(val serviceId: String) : Repair()
        /** The repair is on its way (it may wait for another app's check to finish). */
        data class Started(val serviceId: String) : Repair()
        /** Second "didn't open" on: ask the user what is wrong before trying again. */
        data class Ask(val serviceId: String, val level: Int, val previous: com.mlmvpn.scanner.engines.mae.policy.Symptom?) : Repair()
    }

    /** The rung the last repair of this app was on, for the message ("approach 3 of 5"). */
    fun repairLevel(serviceId: String): Int =
        store.current.repairs[MaeState.sk(serviceId, currentNet())]?.level ?: 0

    private lateinit var app: Context
    lateinit var store: MaeStore
        private set
    lateinit var registry: ServiceRegistry
        private set
    private val breaker = CircuitBreaker()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val applyLock = Mutex()

    private val testing = MutableStateFlow<Set<String>>(emptySet())
    /** Apps being checked now, or waiting their turn after a "didn't open": both show as testing. */
    val testingFlow: StateFlow<Set<String>> = testing
    private val _repairs = MutableSharedFlow<Repair>(extraBufferCapacity = 8)
    val repairs: SharedFlow<Repair> = _repairs

    private data class Job2(val serviceId: String, val priority: Boolean, val incident: Boolean)
    private val queue = Channel<Job2>(Channel.UNLIMITED)
    private val urgent = Channel<Job2>(Channel.UNLIMITED)
    private var worker: Job? = null
    private var statsJob: Job? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastNet: String = ""

    @Synchronized
    fun init(context: Context) {
        if (this::app.isInitialized) return
        app = context.applicationContext
        registry = runCatching {
            ServiceRegistry.parse(app.assets.open("mae/services.json").bufferedReader().use { it.readText() })
        }.getOrElse {
            Log.e(TAG, "registry unreadable: ${it.javaClass.simpleName}")
            ServiceRegistry.parse("{\"schema\":1,\"services\":[]}")
        }
        store = MaeStore(app.filesDir)
        worker = scope.launch { runQueue() }
        // Whatever went stale while the app was closed is re-checked in the background, so the
        // list is current before the user taps Connect.
        scope.launch { refreshStale() }
    }

    // ---------------------------------------------------------------- services

    fun serviceDef(id: String): ServiceDef? {
        registry.get(id)?.let { return it }
        val base = when {
            id.startsWith("site:") -> ServiceRegistry.customSite(id.removePrefix("site:"))
            id.startsWith("app:") -> store.current.customApps[id]?.let { a -> ServiceRegistry.customApp(id, a.pkg, a.label, a.domain) }
            else -> null
        } ?: return null
        // A custom site or app covers the domains its pages were seen loading from, too.
        val extra = store.current.siteHosts[id].orEmpty()
        return if (extra.isEmpty()) base else base.copy(domains = (base.domains + extra).distinct())
    }

    sealed class AddApp {
        data class Added(val id: String) : AddApp()
        /** No domain could be found for it: nothing to route by yet. */
        object NoDomain : AddApp()
    }

    /**
     * Adds an app installed on the phone -- at once: the tap selects it immediately, with the most
     * likely main domain for its package. The domain is then confirmed in the background (all
     * guesses at the same time, the phone's own DNS first, DoH only as a fallback, 8 s at most)
     * and replaced if a better one answers. Measured on Irancell (2026-09-29): checking guesses
     * one by one over DoH took minutes, and the tap looked dead the whole time.
     */
    fun addInstalledApp(pkg: String, label: String): AddApp {
        registry.services.firstOrNull { pkg in it.packages }?.let { known ->
            setSelection(store.current.selected.map { it.id } + known.id)
            return AddApp.Added(known.id)
        }
        val guesses = ServiceRegistry.domainGuesses(pkg)
        val first = guesses.firstOrNull() ?: return AddApp.NoDomain
        val id = "app:$pkg"
        store.update { s ->
            s.copy(
                customApps = s.customApps + (id to (s.customApps[id] ?: com.mlmvpn.scanner.engines.mae.store.CustomApp(pkg, label, first))),
                selected = if (s.selected.any { it.id == id }) s.selected else s.selected + SelectedService(id),
            )
        }
        scope.launch { confirmAppDomain(id, pkg, label, guesses) }
        return AddApp.Added(id)
    }

    private suspend fun confirmAppDomain(id: String, pkg: String, label: String, guesses: List<String>) {
        val prober = NetProber(app)
        val network = runCatching { com.mlmvpn.scanner.engines.game.booster.session.GameNetwork.pick(app)?.network }.getOrNull()
        fun resolves(d: String): Boolean {
            val system = runCatching { (network?.getAllByName(d) ?: java.net.InetAddress.getAllByName(d)).map { it.hostAddress.orEmpty() } }
                .getOrDefault(emptyList())
            if (system.any { !com.mlmvpn.scanner.engines.mae.probe.DnsEvidence.isBogus(it) }) return true
            return prober.dohResolve(d, 1).isNotEmpty()
        }
        val found = withTimeoutOrNull(8_000) {
            kotlinx.coroutines.coroutineScope {
                val checks = guesses.map { d -> async(Dispatchers.IO) { d to runCatching { resolves(d) }.getOrDefault(false) } }
                // The first guess (most likely) that resolves, in guess order.
                checks.map { it.await() }.firstOrNull { it.second }?.first
            }
        }
        val domain = found ?: guesses.first()
        store.update { s ->
            val cur = s.customApps[id] ?: return@update s
            if (cur.domain == domain) s
            else s.copy(customApps = s.customApps + (id to cur.copy(domain = domain)),
                // A different main domain: what was learned about the old guess does not apply.
                diagnoses = s.diagnoses.filterKeys { !it.startsWith("$id|") }, siteHosts = s.siteHosts - id)
        }
        Log.i(TAG, "app $pkg -> $domain${if (found == null) " (unconfirmed)" else ""}")
        enqueue(id)
        applyIfConnected()
    }

    /**
     * Reads a custom site's home page through the route that works for it and adds the other
     * domains the page loads from to the site. Runs when the site is first checked and again on
     * every "didn't open", so a site that opened but did not finish loading repairs itself.
     */
    private suspend fun learnSiteHosts(def: ServiceDef, net: String, ports: Map<String, Int>, providers: List<RouteProvider>): Int {
        val policy = store.current.policies[MaeState.sk(def.id, net)] ?: return 0
        val p = providers.firstOrNull { it.id == policy.routeId } ?: return 0
        val route = if (p.kind == RouteKind.DIRECT) ProbeRoute(p.id, p.kind, policy.family)
            else ProbeRoute(p.id, p.kind, policy.family, ports[p.tag(policy.family)] ?: return 0)
        val site = def.domains.firstOrNull() ?: return 0
        val html = NetProber(app).pageText(route, "https://$site/") ?: return 0
        // Domains that are another known app's (a page linking to its Instagram or YouTube) stay
        // with that app: they are not this site's content and must keep their own route.
        // Brands of other known apps (google, youtube, …): their country domains stay theirs.
        val brands = registry.services.filter { it.id != def.id }.flatMap { s -> s.domains.map { it.substringBefore('.') } }.toSet()
        val found = com.mlmvpn.scanner.engines.mae.registry.RelatedHosts.extract(html, site, knownBrands = brands)
            .filter { registry.forHost(it) == null }
        if (found.isEmpty()) return 0
        val before = store.current.siteHosts[def.id].orEmpty()
        // The page as it is now is the truth: a fresh read replaces the old list, so a wrong
        // domain learned earlier does not stay forever.
        val merged = found.distinct().take(40)
        store.update { it.copy(siteHosts = it.siteHosts + (def.id to merged)) }
        val added = merged.count { it !in before }
        Log.i(TAG, "site ${def.id}: ${merged.size} related domain(s), $added new")
        return added
    }

    fun selectedDefs(state: MaeState = store.current): List<ServiceDef> =
        state.selected.mapNotNull { serviceDef(it.id) }

    fun views(state: MaeState, testingIds: Set<String>): List<ServiceView> {
        val net = currentNet()
        return state.selected.mapNotNull { sel ->
            val def = serviceDef(sel.id) ?: return@mapNotNull null
            val key = MaeState.sk(def.id, net)
            val d = state.diagnoses[key] ?: Diagnosis()
            val policy = state.policies[key]
            val egress = state.egress[key]
            val phase = when {
                sel.paused -> Phase.PAUSED
                def.id in testingIds -> Phase.TESTING
                egress is ForeignEgressStatus.NoneFound && d.primary in setOf(Diagnosis.Primary.GEO_RESTRICTED, Diagnosis.Primary.CENSORED_AND_GEO) -> Phase.NO_FOREIGN_ROUTE
                policy != null && proven(state, def.id, net, policy) -> Phase.READY
                d.axes.isNotEmpty() -> Phase.BLOCKED
                else -> Phase.WAITING
            }
            ServiceView(def, phase, policy?.routeId, d.primary, sel.paused)
        }
    }

    /** The chosen route really served this service on this network, on the chosen family. */
    private fun proven(state: MaeState, id: String, net: String, p: com.mlmvpn.scanner.engines.mae.model.ServicePolicy): Boolean {
        if (p.pinned) return true
        val m = state.metrics[MaeState.rk(p.routeId, id, net)] ?: return false
        return m.lastTestedAt > 0 && m.lastOkAt == m.lastTestedAt
    }

    fun setSelection(ids: List<String>) {
        store.update { s ->
            val keep = s.selected.associateBy { it.id }
            s.copy(onboarded = true, selected = ids.distinct().map { keep[it] ?: SelectedService(it) })
        }
        ids.forEach { enqueue(it) }
        scope.launch { applyIfConnected() }
    }

    /** Returns the normalised site id, or null when the text is not a domain. */
    fun addSite(input: String): String? {
        val def = ServiceRegistry.customSite(input) ?: return null
        store.update { s ->
            if (s.selected.any { it.id == def.id }) s
            else s.copy(selected = s.selected + SelectedService(def.id), customSites = (s.customSites + def.displayName).distinct())
        }
        enqueue(def.id)
        return def.id
    }

    /** Removes the app and everything learned about it: MAE keeps nothing about apps the user dropped. */
    fun remove(id: String) {
        store.update { s ->
            fun <T> Map<String, T>.drop() = filterKeys { k -> k.split('|').none { it == id } }
            s.copy(
                selected = s.selected.filterNot { it.id == id },
                customSites = s.customSites.filterNot { "site:$it" == id },
                diagnoses = s.diagnoses.drop(), policies = s.policies.drop(), metrics = s.metrics.drop(),
                proofs = s.proofs.drop(), egress = s.egress.drop(), siteHosts = s.siteHosts - id, customApps = s.customApps - id,
                incidents = s.incidents.filterNot { it.serviceId == id },
            )
        }
        scope.launch { applyIfConnected() }
    }

    fun setPaused(id: String, paused: Boolean) {
        store.update { s -> s.copy(selected = s.selected.map { if (it.id == id) it.copy(paused = paused) else it }) }
        scope.launch { applyIfConnected() }
    }

    fun pin(id: String, routeId: String?) {
        val net = currentNet()
        store.update { s ->
            val key = MaeState.sk(id, net)
            val p = s.policies[key]
            val next = if (routeId == null) p?.copy(pinned = false)
            else (p ?: com.mlmvpn.scanner.engines.mae.model.ServicePolicy(id, net, routeId)).copy(routeId = routeId, pinned = true, why = "pinned by user")
            if (next == null) s else s.copy(policies = s.policies + (key to next))
        }
        scope.launch { applyIfConnected() }
    }

    // ---------------------------------------------------------------- network

    fun currentNet(): String = runCatching { NetworkKey.current(app).key }.getOrDefault(NetworkKey.UNKNOWN).ifEmpty { "unknown" }

    private fun onCellular(): Boolean = runCatching { NetworkKey.current(app).isCellular }.getOrDefault(false)

    // ---------------------------------------------------------------- providers

    fun providers(state: MaeState = store.current, net: String = currentNet()): List<RouteProvider> = buildList {
        add(DirectRoute); add(ServerlessRoute); add(FragmentRoute)
        state.warp?.let { w -> MaeWarp.route(w, state.warpEndpoint[net] ?: 0)?.let { add(it) } }
        state.worker?.let { w -> MaeEgressDeployer.uuidOf(w)?.let { add(WorkerRoute(w.url, it)) } }
        val configs = userConfigRoutes(state, net)
        addAll(configs)
        usExitRoute(state, net, configs)?.let { add(it) }
    }

    /**
     * The US exit, when one is set up: reached through the user's config that has most recently
     * carried anything on this network (its clean address works on this line), else the first.
     */
    private fun usExitRoute(state: MaeState, net: String, configs: List<RouteProvider>): RouteProvider? {
        val exit = com.mlmvpn.scanner.utils.NetworkSettings.geminiExit(app) ?: return null
        if (configs.isEmpty()) return null
        fun lastOk(id: String) = state.metrics.filterKeys { it.startsWith("$id|") && it.endsWith("|$net") }.values.maxOfOrNull { it.lastOkAt } ?: 0L
        val via = configs.maxByOrNull { lastOk(it.id) } ?: return null
        // The both-families variant: the exit resolves names itself, from North America, to IPv4.
        val tunnel = via.outbounds().firstOrNull { it.optString("tag") == via.tag(FamilyPolicy.BOTH) } ?: return null
        return com.mlmvpn.scanner.utils.XrayJsonGenerator.geminiExitOutboundVia(tunnel, exit)?.let { UsExitRoute(it) }
    }

    @Volatile private var usExitFailedAt = 0L

    /** Bumped whenever assets/gemini_exit_worker.js changes in a way a deployed exit must get. */
    private val USX_SCRIPT_VERSION = 2
    private val KEY_USX_VER = "usx_script_ver"

    /**
     * Sets up the US exit on the user's Cloudflare account the first time an app that needs it
     * (Gemini, Google Flow) is checked -- the same helper as the app's «خروجی آمریکا برای جمنای»,
     * so a setup from either place serves both. Failure is retried after an hour.
     */
    private suspend fun ensureUsExit(def: ServiceDef, ports: Map<String, Int>, providers: List<RouteProvider>): Boolean {
        if (!def.hints.usExit) return false
        val prefs = app.getSharedPreferences("mae", Context.MODE_PRIVATE)
        // An exit set up with an older script is set up again (same name, path and user id, so
        // nothing that points at it changes).
        if (com.mlmvpn.scanner.utils.NetworkSettings.geminiExit(app) != null && prefs.getInt(KEY_USX_VER, 0) >= USX_SCRIPT_VERSION) return false
        if (System.currentTimeMillis() - usExitFailedAt < 3600_000L) return false
        val manager = CloudManager(app)
        val account = manager.accounts.firstOrNull() ?: run { Log.i(TAG, "US exit: no Cloudflare account"); return false }
        // Cloudflare's API is blocked on some networks (Irancell, 2026-09-30: timeout directly):
        // through the bypass routes first (they reach Cloudflare fastest), then directly, then
        // through the foreign exits.
        fun proxies(kind: RouteKind) = providers.filter { it.kind == kind }
            .mapNotNull { p -> ports[p.tag(FamilyPolicy.BOTH)] ?: p.families.firstNotNullOfOrNull { ports[p.tag(it)] } }
            .distinct()
            .map { java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", it)) }
        val vias: List<java.net.Proxy?> = proxies(RouteKind.BYPASS) + listOf(null) + proxies(RouteKind.FOREIGN)
        for (via in vias) {
            val (ok, why) = manager.deployGeminiExit(account, via = via)
            if (ok) {
                prefs.edit().putInt(KEY_USX_VER, USX_SCRIPT_VERSION).apply()
                Log.i(TAG, "US exit set up (${if (via == null) "directly" else "through a route"})")
                return true
            }
            Log.w(TAG, "US exit not set up ${if (via == null) "directly" else "through a route"}: ${why.take(80)}")
        }
        usExitFailedAt = System.currentTimeMillis()
        return false
    }

    private val userOutboundCache = HashMap<String, JSONObject?>()

    /**
     * A few of the user's own saved configs as foreign-exit candidates: VLESS/Trojan links only,
     * not the Iranian / SNI / domain-fronting groups (those do not exit abroad), lowest measured
     * delay first. [MaeState.userConfigOffset] moves the window on when the current ones proved
     * useless on this network.
     */
    private fun userConfigRoutes(state: MaeState, net: String): List<RouteProvider> {
        val nodes = runCatching { com.mlmvpn.scanner.data.NodeManager(app).nodesFlow.value }.getOrDefault(emptyList())
        val eligible = nodes.filter { n ->
            (n.uri.startsWith("vless://") || n.uri.startsWith("trojan://")) &&
                !com.mlmvpn.scanner.data.NodeManager.isProtected(n) &&
                n.groupTitle?.let { g -> g == com.mlmvpn.scanner.data.NodeManager.IRAN_GROUP || g.contains("sni", true) || g.contains("فرانتینگ") } != true
        }.sortedBy { n -> Regex("[0-9]+").find(n.delay)?.value?.toIntOrNull()?.takeIf { it > 0 } ?: Int.MAX_VALUE }
        if (eligible.isEmpty()) return emptyList()
        val offset = (state.userConfigOffset[net] ?: 0) * UserConfigRoute.MAX
        val window = (eligible.drop(offset % eligible.size) + eligible).distinctBy { it.id }.take(UserConfigRoute.MAX)
        return window.mapIndexedNotNull { i, n ->
            val proxy = synchronized(userOutboundCache) {
                userOutboundCache.getOrPut(n.id) {
                    runCatching {
                        val cfg = com.mlmvpn.scanner.utils.VpnConfig.parseUri(n.uri) ?: return@runCatching null
                        val full = JSONObject(com.mlmvpn.scanner.utils.XrayJsonGenerator.generateConfig(cfg, 10808, includeTun = false))
                        val outs = full.getJSONArray("outbounds")
                        (0 until outs.length()).map { outs.getJSONObject(it) }.firstOrNull { it.optString("tag") == "proxy" }
                            ?.takeIf { !it.toString().contains("dialerProxy") }
                    }.getOrNull()
                }
            } ?: return@mapIndexedNotNull null
            UserConfigRoute("${UserConfigRoute.PREFIX}${i + 1}", proxy, n.name)
        }
    }

    /**
     * Makes MAE's WARP identity the first time it is needed. Failure is remembered and retried
     * after [MaeWarp.RETRY_MS]; discovery goes on without WARP meanwhile.
     */
    private suspend fun ensureWarp() {
        val s = store.current
        if (s.warp != null || System.currentTimeMillis() - s.warpFailedAt < MaeWarp.RETRY_MS) return
        runCatching { MaeWarp.register(app) }
            .onSuccess { w -> Log.i(TAG, "WARP identity made (${w.via})"); store.update { it.copy(warp = w) } }
            .onFailure { e -> Log.w(TAG, "WARP identity not made: ${e.javaClass.simpleName}"); store.update { it.copy(warpFailedAt = System.currentTimeMillis()) } }
    }

    fun routeLabel(routeId: String?): String = routeId ?: "—"

    // ---------------------------------------------------------------- discovery queue

    /** Queue a service for discovery. [incident] = user said it did not open: goes first, always runs. */
    fun enqueue(serviceId: String, incident: Boolean = false) {
        if (incident) {
            // Shown at once, not when the queue gets to it: a tap must never look ignored.
            testing.value = testing.value + serviceId
            _repairs.tryEmit(Repair.Started(serviceId))
        }
        val j = Job2(serviceId, incident, incident)
        if (incident) urgent.trySend(j) else queue.trySend(j)
    }

    /** Re-verify every stale service; called on connect and on network change. */
    fun refreshStale() {
        val net = currentNet()
        val now = System.currentTimeMillis()
        val s = store.current
        s.selected.filterNot { it.paused }.forEach { sel ->
            if (PolicyEngine.isStale(s.policies[MaeState.sk(sel.id, net)], now)) enqueue(sel.id)
        }
    }

    private suspend fun runQueue() {
        while (scope.isActive) {
            val job = urgent.tryReceive().getOrNull() ?: queue.tryReceive().getOrNull()
                ?: withTimeoutOrNull(60_000) { kotlinx.coroutines.selects.select<Job2> {
                    urgent.onReceive { it }
                    queue.onReceive { it }
                } }
            if (job == null) { stopProbeCore(); continue }
            runCatching { discover(job) }.onFailure { Log.w(TAG, "discovery ${job.serviceId} failed: ${it.javaClass.simpleName}") }
        }
    }

    private var probeCore: VlessXrayInjector? = null
    private var probePorts: Map<String, Int> = emptyMap()
    private var probeProvidersKey: String = ""

    private suspend fun ensureProbeCore(providers: List<RouteProvider>, dns: DnsPath?): Map<String, Int> {
        // Keyed on the outbounds themselves, so a changed WARP endpoint or Worker rebuilds the core.
        val key = providers.joinToString { it.id + ":" + it.outbounds().toString().hashCode() } + "#" + dns
        if (probeCore != null && key == probeProvidersKey) return probePorts
        stopProbeCore()
        val tags = providers.filter { it.kind != RouteKind.DIRECT }.flatMap { p -> p.families.map { p.tag(it) } }.distinct()
        val ports = tags.associateWith { freePort() }
        val cfg = MaeConfigCompiler.compileProbe(base(), providers, ports, dns)
        val core = VlessXrayInjector(0)
        if (!core.start(app, cfg, 0)) error("probe core did not start")
        probeCore = core; probePorts = ports; probeProvidersKey = key
        return ports
    }

    /**
     * The tunnel's own DNS path for this network: the base's choice if it works, otherwise the
     * first DoH-by-IP that answers directly, otherwise one through a fragment. Chosen once per
     * network and re-checked after [DNS_TTL_MS].
     */
    private suspend fun dnsPathFor(net: String, providers: List<RouteProvider>): DnsPath {
        val now = System.currentTimeMillis()
        store.current.dnsPaths[net]?.takeIf { now - it.at < DNS_TTL_MS }?.let { return it }
        val prober = NetProber(app)
        val ports = ensureProbeCore(providers, null)
        val direct = ProbeRoute(DirectRoute.id, RouteKind.DIRECT)
        fun via(tag: String) = ports[tag]?.let { ProbeRoute(tag, RouteKind.BYPASS, socksPort = it) }
        val candidates = listOfNotNull(
            via(ServerlessRoute.TAG)?.let { Triple(DnsPath.BASE, "https://cloudflare-dns.com/dns-query", it) },
            Triple(DnsPath("https://8.8.8.8/dns-query", DirectRoute.TAG), "https://8.8.8.8/dns-query", direct),
            Triple(DnsPath("https://1.1.1.1/dns-query", DirectRoute.TAG), "https://1.1.1.1/dns-query", direct),
            via(ServerlessRoute.TAG)?.let { Triple(DnsPath("https://8.8.8.8/dns-query", ServerlessRoute.TAG), "https://8.8.8.8/dns-query", it) },
            via(FragmentRoute.TAG)?.let { Triple(DnsPath("https://1.1.1.1/dns-query", FragmentRoute.TAG), "https://1.1.1.1/dns-query", it) },
        )
        val works = kotlinx.coroutines.coroutineScope {
            candidates.map { (path, url, route) -> async { path to prober.dohWorks(url, route) } }.map { it.await() }
        }
        val chosen = (works.firstOrNull { it.second }?.first ?: DnsPath.BASE).copy(at = now)
        Log.i(TAG, "dns path for this network: ${chosen.url ?: "base"} via ${chosen.via} (${works.count { it.second }}/${works.size} worked)")
        store.update { it.copy(dnsPaths = it.dnsPaths + (net to chosen)) }
        return chosen
    }

    private fun stopProbeCore() {
        probeCore?.let { runCatching { it.stop() } }
        probeCore = null; probePorts = emptyMap(); probeProvidersKey = ""
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private suspend fun discover(job: Job2) {
        val def = serviceDef(job.serviceId) ?: run { testing.value = testing.value - job.serviceId; return }
        val state0 = store.current
        if (state0.selected.none { it.id == def.id && !it.paused }) { testing.value = testing.value - def.id; return }
        val net = currentNet()
        val now = System.currentTimeMillis()
        if (!job.incident && !PolicyEngine.isStale(state0.policies[MaeState.sk(def.id, net)], now) &&
            state0.diagnoses.containsKey(MaeState.sk(def.id, net))) return

        testing.value = testing.value + def.id
        try {
            ensureWarp()
            var all = providers(store.current, net)
            val dns = dnsPathFor(net, all)
            var ports = ensureProbeCore(all, dns)
            if (ensureUsExit(def, ports, all)) {
                all = providers(store.current, net)
                ports = ensureProbeCore(all, dns)
            }
            val plan = if (job.incident) store.current.repairs[MaeState.sk(def.id, net)]?.let { com.mlmvpn.scanner.engines.mae.policy.RepairLadder.plan(it) } else null
            // A deep repair tries routes still in backoff, too: the user is waiting on this app.
            val providers = if (plan?.deep == true) all else all.filter { breaker.allow(it.id, now) }
            plan?.let { Log.i(TAG, "repair ${def.id} rung ${it.level}: ${it.why}") }
            // Families are probed one by one (both-families is derived: usable when each was).
            val routes = providers.flatMap { p ->
                val fams = p.families.filter { it != FamilyPolicy.BOTH }.ifEmpty { listOf(FamilyPolicy.BOTH) }
                if (p.kind == RouteKind.DIRECT) fams.map { f -> ProbeRoute(p.id, p.kind, f) }
                else fams.map { f -> ProbeRoute(p.id, p.kind, f, ports[p.tag(f)]) }
            }
            val prior = providers.associate { p -> p.id to (state0.metrics[MaeState.rk(p.id, def.id, net)] ?: com.mlmvpn.scanner.engines.mae.model.RouteMetrics()) }
            val budget = when {
                plan?.deep == true || plan?.preferThroughput == true ->
                    ProbeBudget(maxConcurrent = 3, rttSamples = 5, finalists = 3, throughputBytes = if (onCellular()) 512 * 1024 else 2_000_000)
                onCellular() -> ProbeBudget.CELLULAR
                else -> ProbeBudget.WIFI
            }
            val result = Discovery(NetProber(app), budget).run(def, routes, prior, forceForeign = plan?.forceForeign == true)

            // Provider health: a provider none of whose families answered anything is failing.
            for (p in providers) {
                val mine = result.observations.filter { it.routeId == p.id }
                if (mine.isEmpty()) continue
                if (mine.any { it.answered }) breaker.success(p.id) else breaker.failure(p.id, now)
                // WARP's endpoint is per network: when this one carries nothing, the next
                // discovery tries the next endpoint instead of waiting out the backoff on a dead one.
                if (p.id.startsWith(UserConfigRoute.PREFIX) && providers.filter { it.id.startsWith(UserConfigRoute.PREFIX) }
                        .all { breaker.health(it.id, now) == com.mlmvpn.scanner.engines.mae.model.Health.UNAVAILABLE }) {
                    // Every user config in the window is dead here: try the next few next time.
                    store.update { it.copy(userConfigOffset = it.userConfigOffset + (net to ((it.userConfigOffset[net] ?: 0) + 1))) }
                    providers.filter { it.id.startsWith(UserConfigRoute.PREFIX) }.forEach { breaker.success(it.id) }
                    Log.i(TAG, "user-config window moved on for this network")
                }
                if (p.id == WarpRoute.ID && breaker.health(p.id, now) == com.mlmvpn.scanner.engines.mae.model.Health.UNAVAILABLE) {
                    store.update { it.copy(warpEndpoint = it.warpEndpoint + (net to ((it.warpEndpoint[net] ?: 0) + 1))) }
                    breaker.success(p.id)
                    Log.i(TAG, "WARP endpoint rotated for this network")
                }
            }

            val before = store.current.policies[MaeState.sk(def.id, net)]
            store.update { s ->
                PolicyEngine.mergeDiscovery(s, def, net, result, { providers(it) }, { breaker.health(it, now) }, now, job.incident, plan).first
            }
            var learned = 0
            // Every app, not only hand-added sites: an app's website loads from domains its
            // registry entry may not list, and the user should not have to add the site again.
            // Once when the app is first checked, and again on every repair that asks for it.
            if (job.incident || store.current.siteHosts[def.id] == null || plan?.learnHosts == true) {
                learned = runCatching { learnSiteHosts(def, net, ports, all) }
                    .onFailure { Log.w(TAG, "site hosts for ${def.id}: ${it.javaClass.simpleName}") }
                    .getOrDefault(0)
            }
            applyIfConnected()
            if (job.incident) {
                val after = store.current.policies[MaeState.sk(def.id, net)]
                val ok = after != null && result.observations.any { it.usable }
                _repairs.tryEmit(when {
                    !ok -> Repair.NotFound(def.id)
                    learned > 0 -> Repair.Fixed(def.id)
                    before != null && before.routeId == after!!.routeId && before.family == after.family -> Repair.Verified(def.id)
                    else -> Repair.Fixed(def.id)
                })
            }
        } finally {
            testing.value = testing.value - def.id
        }
    }

    // ---------------------------------------------------------------- feedback

    /** "Opened": a bounded confidence boost for the current route. Never locks it in. */
    fun feedbackOk(serviceId: String) {
        val net = currentNet()
        store.update {
            // It works now: the repair ladder starts over next time.
            PolicyEngine.recordFeedback(it, serviceId, net, opened = true, now = System.currentTimeMillis())
                .let { s -> s.copy(repairs = s.repairs - MaeState.sk(serviceId, net)) }
        }
    }

    /**
     * "Didn't open": an incident, not a rating. The current route takes a failure on its record,
     * then the service is re-discovered ahead of everything else and the result applied live.
     */
    fun feedbackFailed(serviceId: String) {
        val net = currentNet()
        val now = System.currentTimeMillis()
        if (serviceId in testing.value) { _repairs.tryEmit(Repair.Busy(serviceId)); return }
        // The user said it fails: a route they pinned by hand is not sacred any more.
        val key = MaeState.sk(serviceId, net)
        val updated = store.update { s ->
            val failed = s.policies[key]?.let { "${it.routeId}:${it.family}" }
            val rung = com.mlmvpn.scanner.engines.mae.policy.RepairLadder.next(s.repairs[key], failed, now)
            PolicyEngine.recordFeedback(s, serviceId, net, opened = false, now = now)
                .copy(repairs = s.repairs + (key to rung))
        }
        val rung = updated.repairs[key] ?: return
        if (com.mlmvpn.scanner.engines.mae.policy.RepairLadder.needsQuestion(rung)) {
            // From the second time on, what the user saw steers the next approach.
            _repairs.tryEmit(Repair.Ask(serviceId, rung.level, rung.symptom))
        } else {
            enqueue(serviceId, incident = true)
        }
    }

    /** The user's answer to "what's wrong?": remembered for this app and its next rungs. */
    fun answerSymptom(serviceId: String, symptom: com.mlmvpn.scanner.engines.mae.policy.Symptom) {
        val key = MaeState.sk(serviceId, currentNet())
        store.update { s -> s.repairs[key]?.let { r -> s.copy(repairs = s.repairs + (key to r.copy(symptom = symptom))) } ?: s }
        enqueue(serviceId, incident = true)
    }

    // ---------------------------------------------------------------- config + connect

    private fun base(): String = app.assets.open(BASE_ASSET).bufferedReader().use { it.readText() }

    private fun apiSocket(): File = File(File(app.filesDir, "mae"), "api.sock")

    private data class Compiled(val config: String, val structure: String, val targets: Map<String, String>)

    private fun compileNow(withApi: Boolean): Compiled {
        val state = store.current
        val net = currentNet()
        val now = System.currentTimeMillis()
        val providers = providers(state)
        val routes = state.selected.filterNot { it.paused }.mapNotNull { sel ->
            val def = serviceDef(sel.id) ?: return@mapNotNull null
            // The stored policy IS the decision (a repair or a pin chose it deliberately);
            // re-deciding here would quietly undo a repair. Only without one is it decided now.
            val stored = state.policies[MaeState.sk(def.id, net)]?.takeIf { p -> providers.any { it.id == p.routeId } }
            val decision = stored?.let { Decision.Use(it.routeId, it.family, 0.0, it.why) }
                ?: PolicyEngine.decide(state, def, net, providers, { breaker.health(it, now) }, now).first
            val req = PolicyEngine.requirements(state, def, net)
            val abroad = (decision as? Decision.Use)?.let { d -> providers.firstOrNull { it.id == d.routeId }?.kind == RouteKind.FOREIGN } == true
            MaeConfigCompiler.ServiceRoute(def, decision, PolicyEngine.blockUdp(decision, providers, req), if (abroad) def.bundle else emptyList())
        }
        val config = MaeConfigCompiler.compile(base(), providers, routes, if (withApi) apiSocket().absolutePath else null,
            state.dnsPaths[net], defaultVia(state, net), verbose = File(File(app.filesDir, "mae"), "debug").exists())
        // Structure = what needs a new config; everything else is a balancer target, switched live.
        val structure = providers.joinToString { it.id } + "#" + state.dnsPaths[net] + "#" + defaultVia(state, net) +
            "#" + routes.filter { MaeConfigCompiler.targetsFor(it, providers) != null }.joinToString { it.service.id + ":" + it.service.domains.hashCode() + ":" + it.service.ipRanges.hashCode() + ":" + it.bundle.hashCode() }
        val targets = routes.flatMap { r ->
            val (tcp, udp) = MaeConfigCompiler.targetsFor(r, providers) ?: return@flatMap emptyList()
            listOf(MaeConfigCompiler.balancerTag(r.service.id) to tcp, MaeConfigCompiler.udpBalancerTag(r.service.id) to udp)
        }.toMap()
        return Compiled(config, structure, targets)
    }

    /**
     * The route for traffic MAE knows nothing about. The Serverless base, unless Serverless was
     * tried on this network and served nothing -- then direct, so ordinary sites keep working
     * (MCI, 2026-09-29: fragment reached nothing, not even unfiltered GitHub).
     */
    fun defaultVia(state: MaeState, net: String): String? {
        val tried = state.proofs.filterKeys { it.startsWith("${ServerlessRoute.id}:") && it.endsWith("|$net") }
        if (tried.isEmpty() || tried.values.any { it.serviceAccepted }) return null
        val directWorks = state.proofs.any { (k, p) -> k.startsWith("${DirectRoute.id}:") && k.endsWith("|$net") && p.serviceAccepted }
        return if (directWorks) DirectRoute.TAG else null
    }

    @Volatile private var applied: Compiled? = null

    /** The config to connect with. Uses cached policies only: tap → connected, no probing first. */
    fun buildConfig(): String {
        runCatching { apiSocket().delete() }
        val c = compileNow(withApi = store.current.liveApiWorks != false)
        applied = c
        // Debug builds with the `debug` flag keep the last config for inspection (no secrets
        // beyond what the app already holds in its own private files).
        val dir = File(app.filesDir, "mae")
        if (File(dir, "debug").exists()) runCatching { File(dir, "last_config.json").writeText(c.config) }
        return c.config
    }

    fun isConnected(): Boolean =
        MyVpnService.connectedNodeIdFlow.value == NODE_ID && MyVpnService.connectionPhaseFlow.value == MyVpnService.Phase.CONNECTED

    fun startTunnel(context: Context) {
        val cfg = buildConfig()
        context.startService(Intent(context, MyVpnService::class.java).apply {
            putExtra("NODE_URI", cfg)
            putExtra("NODE_ID", NODE_ID)
            putExtra("PROXY_MODE", false)
            putExtra("LOCAL_PORT", com.mlmvpn.scanner.utils.LocalPort.getString(context))
        })
        onConnected()
    }

    fun stopTunnel(context: Context) {
        context.startService(Intent(context, MyVpnService::class.java).apply { action = "STOP" })
        onDisconnected()
    }

    private fun onConnected() {
        refreshStale()
        watchNetwork()
        statsJob?.cancel()
        statsJob = scope.launch {
            // Wait for the tunnel, then run the live-API spike once per install, then sample stats.
            repeat(30) { if (isConnected()) return@repeat; delay(1000) }
            if (isConnected() && store.current.liveApiWorks == null) spikeLiveApi()
            while (isActive && isConnected()) {
                samplePassiveStats()
                delay(30_000)
            }
        }
    }

    private fun onDisconnected() {
        statsJob?.cancel(); statsJob = null
        netCallback?.let { cb -> runCatching { (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb) } }
        netCallback = null
    }

    /**
     * The live-routing spike. Harmless by construction: it re-points one balancer at the target
     * it already has. Success proves the Unix-socket API, HTTP/2 and the method all work on this
     * phone; any failure turns live switching off for good and MAE reconnects instead.
     */
    private suspend fun spikeLiveApi() {
        val c = applied ?: return
        val (bal, target) = c.targets.entries.firstOrNull()?.toPair() ?: return
        val r = XrayApiClient(apiSocket().absolutePath).overrideBalancerTarget(bal, target)
        val works = r is XrayApiClient.Result.Ok
        Log.i(TAG, "live API spike: ${if (works) "works" else "failed ($r)"}")
        store.update { it.copy(liveApiWorks = works) }
    }

    /** Push the current decisions into a running tunnel: live when possible, reconnect otherwise. */
    suspend fun applyIfConnected() = applyLock.withLock {
        if (!isConnected()) return@withLock
        val next = compileNow(withApi = store.current.liveApiWorks != false)
        val prev = applied
        if (prev != null && prev.structure == next.structure && store.current.liveApiWorks == true) {
            val api = XrayApiClient(apiSocket().absolutePath)
            var allOk = true
            for ((bal, target) in next.targets) {
                if (prev.targets[bal] == target) continue
                // One retry before giving up on live switching: a reconnect costs every service a blip.
                val r = api.overrideBalancerTarget(bal, target).let { first ->
                    if (first is XrayApiClient.Result.Ok) first else api.overrideBalancerTarget(bal, target)
                }
                if (r !is XrayApiClient.Result.Ok) { allOk = false; Log.w(TAG, "live switch $bal failed: $r"); break }
                Log.i(TAG, "live switch $bal -> $target")
            }
            if (allOk) { applied = next.copy(config = prev.config); return@withLock }
        }
        if (prev != null && prev.config == next.config) return@withLock
        Log.i(TAG, "applying by reconnect")
        startTunnel(app)
    }

    private fun watchNetwork() {
        if (netCallback != null) return
        lastNet = currentNet()
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = changed()
            override fun onLost(network: Network) = changed()
            private fun changed() {
                scope.launch {
                    delay(2000) // let the new network settle
                    val now = currentNet()
                    if (now == lastNet) return@launch
                    lastNet = now
                    Log.i(TAG, "network changed; re-deciding from this network's memory")
                    refreshStale()
                    applyIfConnected()
                }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb); netCallback = cb }
    }

    /**
     * Passive learning: bytes each MAE outbound moved since the last sample (queryStats resets the
     * counter). A busy window is a lower bound on what the route can carry; it only ever raises
     * the throughput estimate of routes that carried real traffic, never invents one.
     */
    private fun samplePassiveStats() {
        val ctl = VlessXrayInjector.active ?: return
        val net = currentNet()
        val state = store.current
        val providers = providers(state)
        val windowSecs = 30.0
        for (p in providers) {
            val bytes = p.families.map { p.tag(it) }.distinct().sumOf { tag ->
                runCatching { ctl.queryStats(tag, "downlink") + ctl.queryStats(tag, "uplink") }.getOrDefault(0L)
            }
            if (bytes > 0) Log.i(TAG, "passive: ${p.id} moved $bytes bytes in ${windowSecs.toInt()} s")
            if (bytes < 2_000_000) continue
            val bps = bytes / windowSecs
            store.update { s ->
                var metrics = s.metrics
                s.policies.values.filter { it.netKey == net && it.routeId == p.id }.forEach { pol ->
                    val k = MaeState.rk(p.id, pol.serviceId, net)
                    val m = metrics[k] ?: return@forEach
                    if ((m.throughputBps ?: 0.0) < bps) metrics = metrics + (k to m.withThroughput(bps))
                }
                s.copy(metrics = metrics)
            }
        }
    }

    // ---------------------------------------------------------------- foreign egress

    fun cloudAccounts() = CloudManager(app).accounts.toList()

    suspend fun deployEgress(accountIndex: Int = 0): Result<Unit> = runCatching {
        val account = cloudAccounts().getOrNull(accountIndex) ?: error("no Cloudflare account")
        val w = MaeEgressDeployer.deploy(app, account, store.current.worker)
        store.update { s ->
            // A new exit invalidates what was concluded without it: forget those verdicts and
            // make every policy stale BEFORE queueing, or the queue skips them as fresh.
            s.copy(
                worker = w,
                egress = s.egress.filterValues { it !is ForeignEgressStatus.NoneFound },
                policies = s.policies.mapValues { (_, p) -> p.copy(decidedAt = 0) },
            )
        }
        store.current.selected.forEach { enqueue(it.id) }
    }

    suspend fun removeEgress(): Result<Unit> = runCatching {
        val w = store.current.worker ?: return@runCatching
        cloudAccounts().firstOrNull { it.accountId == w.accountId }?.let { MaeEgressDeployer.remove(it) }
        store.update { it.copy(worker = null) }
        applyIfConnected()
    }
}
