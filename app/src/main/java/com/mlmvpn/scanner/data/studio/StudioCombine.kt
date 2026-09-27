package com.mlmvpn.scanner.data.studio

import com.mlmvpn.scanner.models.VpnNode
import com.mlmvpn.scanner.data.studio.api.StudioError
import com.mlmvpn.scanner.data.studio.api.StudioResult
import com.mlmvpn.scanner.data.studio.domain.StudioConfig
import com.mlmvpn.scanner.data.studio.domain.StudioNode
import com.mlmvpn.scanner.models.CloudAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * «ترکیب کاربر با آی‌پی تمیز» — the two ends of a combine that the SCANNER does the middle of.
 *
 * There is no scanning here and no combining. The app already has a scanner the operator uses, with
 * its own archive, its own per-address measurement and its own combine sheet, and an earlier
 * version of this feature carried a second one — which worked and was a second thing to learn and a
 * second thing to keep honest. So this file is only the two ends that the scanner cannot do,
 * because they need a Cloudflare API key:
 *
 *  * [prepare] reads one subscriber's configs and renders them as combine sources, in Config Studio
 *    where there is a key and a network. The scanner has neither.
 *  * [publishGroup] takes a finished group's addresses and puts the person on them.
 *
 * **Neither end changes a URI.** The user-side combine ([com.mlmvpn.scanner.data.CombineEngine])
 * rewrites links on this phone; a Studio subscription is rendered by the engine at fetch time from
 * the config rows crossed with the endpoint list, so putting somebody on a clean address means
 * adding it as an endpoint and pointing their configs at it. Their existing link then serves it,
 * unchanged, on the next refresh.
 *
 * That is the whole reason this is worth doing rather than re-issuing configs:
 *
 *  * **The link does not change.** Nothing has to be re-sent, and nobody has to be told anything.
 *  * **The credential does not change.** Every device the person already set up keeps working —
 *    which matters because the reason for combining is usually that they cannot connect, and a
 *    fix that disconnects them is not a fix.
 *  * **It is reversible.** Retargeting to an empty list puts the config back on every endpoint.
 */
object StudioCombine {

    /** What a finished combine did, per config, so a partial result can be read as one. */
    data class Result(
        val node: StudioNode,
        val moved: List<StudioConfig>,
        /** Config label (or id) paired with why it did not move. */
        val failed: List<Pair<String, StudioError>>,
        /** Accounts the endpoint could not be written to, when it was written fleet-wide. */
        val endpointFailures: List<Pair<String, StudioError>>,
        /**
         * Config ids that moved, for the path that works from ids rather than from rows.
         *
         * [publishGroup] has ids and no rows: it is called from the scanner, which holds a group
         * of combined links and not the config objects they came from. Reporting an empty [moved]
         * there and the count here keeps one result type instead of two.
         */
        val movedIds: List<String> = emptyList(),
        /** How many endpoints the person is now served on. */
        val endpointCount: Int = 0,
    ) {
        val ok: Boolean get() = failed.isEmpty() && (moved.isNotEmpty() || movedIds.isNotEmpty())
        val movedCount: Int get() = if (moved.isNotEmpty()) moved.size else movedIds.size
    }

    /**
     * Render one config's own link, so it can be combined against clean addresses.
     *
     * The same five placeholders `studioRenderTemplate` substitutes in the worker, and deliberately
     * the same five: the template is written by `ConfigBuilder` on this side and rendered by the
     * engine on that one, so a renderer here that filled in a sixth thing would produce a link the
     * subscription never serves.
     *
     * The host is the installation's own name rather than an endpoint's address. That is the point:
     * this link is a **base** for combining, and the combine rewrites the host to a clean IP while
     * keeping the original as `sni` and `host` — which is the pairing that makes a clean IP work at
     * all. Starting from a link that already pointed at an endpoint would carry that endpoint's
     * address into the SNI and produce a handshake for a name Cloudflare does not answer to.
     */
    private fun renderConfig(config: StudioConfig, workerHost: String, username: String): String? {
        val template = config.uriTemplate?.takeIf { it.isNotBlank() } ?: return null
        val credential = config.credential?.takeIf { it.isNotBlank() } ?: return null
        return template
            .replace("{{cred}}", credential)
            .replace("{{host}}", workerHost)
            .replace("{{port}}", "443")
            .replace("{{sni}}", workerHost)
            .replace("{{path}}", config.routeKey?.let { "/$it" } ?: "/")
            .replace("{{remark}}", java.net.URLEncoder.encode(displayName(config, username), "UTF-8"))
    }

    /**
     * What a config is called in the scanner and in every combined link: a location config by its
     * flag and country first («🇩🇪 آلمان · ali»), so after combining with clean IPs the operator —
     * and the person — can still tell which country each one leaves from.
     */
    private fun displayName(config: StudioConfig, username: String): String {
        val cc = config.exitCc
        if (cc != null) return com.mlmvpn.scanner.data.studio.domain.ExitCountries.label(cc) + " · " + username
        return username + (config.label?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
    }

    /**
     * Everything the scanner needs to combine for one person, read while there is still an API key.
     *
     * One node per CONFIG, not per link. The subscription emits a link per config per endpoint per
     * port, and combining that against ten clean addresses would multiply an existing fan-out by
     * ten — sixty configs where six were meant, every one of them a duplicate differing only in the
     * host that is about to be rewritten anyway.
     *
     * Disabled configs are left out: they are not in the subscription, so combining them would put
     * something in the group that no client will ever be served.
     */
    suspend fun prepare(
        store: StudioStore,
        account: CloudAccount,
        userId: String,
        username: String,
    ): StudioResult<StudioCombineHandoff.Target> = withContext(Dispatchers.IO) {
        val host = hostOf(account)
            ?: return@withContext StudioResult.Err(
                StudioError(StudioError.NOT_CONFIGURED, detail = "installation has no address")
            )
        val configs = when (val res = store.apiFor(account).listConfigs(userId)) {
            is StudioResult.Ok -> res.value.filter { it.enabled }
            is StudioResult.Err -> return@withContext StudioResult.Err(res.error)
        }
        val nodes = configs.mapNotNull { config ->
            val uri = renderConfig(config, host, username) ?: return@mapNotNull null
            VpnNode(
                id = config.id,
                name = displayName(config, username),
                uri = uri,
                type = if (config.protocol?.startsWith("t", true) == true) "TROJAN" else "VLESS",
                // Named so the scanner's own group card can say where these came from. The engine
                // key the rest of the app uses for this worker.
                engineType = "MLM",
            )
        }
        if (nodes.isEmpty()) {
            return@withContext StudioResult.Err(
                StudioError(StudioError.NOT_FOUND, detail = "this person has no config to combine")
            )
        }
        StudioResult.Ok(
            StudioCombineHandoff.Target(
                installationId = account.id,
                userId = userId,
                username = username,
                configIds = nodes.map { it.id },
                nodes = nodes,
            )
        )
    }

    /**
     * The worker's own hostname, which every rendered link presents and every combine preserves.
     *
     * Taken from the stored deployment URL rather than rebuilt from the account name: an
     * installation adopted from an existing worker can be on any hostname, and a reconstructed one
     * would put a name in the SNI that this account does not answer to.
     */
    fun hostOf(account: CloudAccount?): String? {
        val raw = account?.mlmWorkerUrl?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return try {
            java.net.URI(if (raw.contains("://")) raw else "https://$raw").host
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Put a finished scanner group's addresses into one person's subscription.
     *
     * The button behind «بروزرسانی ساب», and what it does is not what the words suggest on the
     * surface: it does **not** upload the combined links. A Studio subscription is rendered by the
     * engine from the config rows crossed with the endpoint list, so the way to make it serve these
     * addresses is to make them endpoints and point this person's configs at them. The result is
     * the same links the operator is looking at, and the subscription URL and every credential stay
     * exactly as they were.
     *
     * Uploading the combined links instead would mean storing a host inside each config's template,
     * which is the one thing the endpoint model exists to avoid: the next clean IP would then need
     * every config re-issued rather than one endpoint edited.
     *
     * Existing endpoints with the same address are reused rather than duplicated — combining twice
     * in a week is the ordinary case, and the second run should move the person, not grow the list.
     */
    suspend fun publishGroup(
        store: StudioStore,
        account: CloudAccount,
        userId: String,
        configIds: List<String>,
        addresses: List<Pair<String, Int>>,
        username: String,
    ): StudioResult<Result> = withContext(Dispatchers.IO) {
        if (addresses.isEmpty()) {
            return@withContext StudioResult.Err(
                StudioError(StudioError.NOT_FOUND, detail = "no address in this group")
            )
        }
        val existing = store.state.value.nodes.filter { it.installationId == account.id }
        val nodeIds = mutableListOf<String>()
        var firstNode: StudioNode? = null

        for ((ip, port) in addresses) {
            val already = existing.firstOrNull { it.host == ip }
            if (already != null) {
                nodeIds.add(already.id)
                if (firstNode == null) firstNode = already
                continue
            }
            val node = StudioNode(
                id = StudioStore.newNodeId(),
                name = "تمیز $ip",
                host = ip,
                ports = listOf(port),
                // Left null so the engine presents the worker's own hostname, which is what these
                // addresses were combined against.
                sni = null,
                installationId = account.id,
            )
            val write = store.saveNode(node, toWholeFleet = false)
            if (write.wroteTo.contains(account.id)) {
                nodeIds.add(node.id)
                if (firstNode == null) firstNode = node
            }
        }
        if (nodeIds.isEmpty()) {
            return@withContext StudioResult.Err(
                StudioError(StudioError.NOT_CONFIGURED, detail = "no endpoint was written")
            )
        }

        val api = store.apiFor(account)
        // The ids the group was combined from, when they are still there. A config deleted since
        // the scan started is skipped rather than failing the whole update.
        val live = when (val res = api.listConfigs(userId)) {
            is StudioResult.Ok -> res.value.map { it.id }.toSet()
            is StudioResult.Err -> return@withContext StudioResult.Err(res.error)
        }
        val targets = configIds.filter { it in live }.ifEmpty { live.toList() }

        val moved = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, StudioError>>()
        for (id in targets) {
            when (val res = api.retargetConfig(id, nodeIds)) {
                is StudioResult.Ok -> moved.add(id)
                is StudioResult.Err -> failed.add(id to res.error)
            }
        }
        StudioResult.Ok(
            Result(
                node = firstNode ?: StudioNode(
                    id = nodeIds.first(), name = username, host = addresses.first().first,
                    installationId = account.id,
                ),
                moved = emptyList(),
                failed = failed,
                endpointFailures = emptyList(),
                movedIds = moved,
                endpointCount = nodeIds.size,
            )
        )
    }
}
