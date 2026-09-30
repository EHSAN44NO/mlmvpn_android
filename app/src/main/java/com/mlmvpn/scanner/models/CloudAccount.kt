package com.mlmvpn.scanner.models

data class CloudAccount(
    val id: String,
    val token: String,
    val email: String,
    val name: String,
    val accountId: String,
    var status: String, // 'active', 'deployed'
    val addedAt: String,
    // BPB Engine fields
    var workerUrl: String? = null,
    var uuid: String? = null,
    var trPass: String? = null,
    var subPath: String? = null,
    /**
     * The KV namespace this account's BPB worker is bound to.
     *
     * Recorded so a redeploy rebinds the same namespace rather than asking Cloudflare for
     * another one. A Cloudflare account caps how many KV namespaces it may hold, and an account
     * that also runs EDG and the dedicated DNS resolver can reach that cap -- at which point a
     * deploy that only needed the namespace it already had would fail asking for a new one.
     */
    var kvNamespaceId: String? = null,
    /**
     * Which Cloudflare authentication scheme this credential actually works with.
     *
     * `"bearer"` for an API token, `"global"` for a Global API Key. Established by probing at
     * add time rather than inferred, because every way of inferring it is wrong for some real
     * credential -- and a wrong guess does not fail cleanly, it fails later with error 10037 on
     * every resource. Null on accounts saved before this existed; those fall back to the shape
     * check, which is right for the common cases.
     */
    var authScheme: String? = null,
    // EDG (Edgetunnel) Engine fields
    var edgWorkerUrl: String? = null,
    var edgUuid: String? = null,
    var edgAdminPass: String? = null,
    var edgKvNamespaceId: String? = null,
    var edgStatus: String = "idle", // 'idle', 'deployed'
    
    // Nahan Engine fields
    var nahanWorkerUrl: String? = null,
    var nahanDbId: String? = null,
    var nahanApiRoute: String = "sync",
    var nahanMasterKey: String? = null,
    var nahanStatus: String = "idle", // 'idle', 'deployed'

    // MLM Engine fields
    var mlmWorkerUrl: String? = null,
    var mlmDbId: String? = null,
    var mlmAdminPassword: String? = null,
    var mlmStatus: String = "idle", // 'idle', 'deployed'

    /**
     * Which build of each panel is actually deployed on this account.
     *
     * 0 means "deployed before the app recorded this", which is treated as stale -- see
     * [com.mlmvpn.scanner.data.PanelBuild].
     */
    var bpbVersion: Int = 0,
    var edgVersion: Int = 0,
    var nahanVersion: Int = 0,
    var mlmVersion: Int = 0,

    // ── Config Studio ──────────────────────────────────────────────────────────────────────
    //
    // The same worker and the same D1 as the MLM fields above -- Config Studio extends the
    // installation rather than standing up a second one, so `mlmWorkerUrl` and `mlmDbId` are
    // reused and only the build-6 additions live here.
    //
    // THREE PLACES. A field added here and forgotten in either CloudManager.loadAccounts() or
    // saveAccounts() vanishes on the next app restart with no error at all -- the deploy works,
    // the panel works, and the account is simply empty the next morning. Grep for one of these
    // names before adding another and make sure it appears three times.

    /** The random path segment the control plane hides behind, recovered from the worker binding. */
    var studioApiRoute: String? = null,

    /** Identifies which API key this device holds, so a lost phone can be revoked on its own. */
    var studioKeyId: String? = null,

    /**
     * The bearer secret. Held only here, and not recoverable from Cloudflare by design.
     *
     * A device that does not have it re-arms the bootstrap by redeploying with a fresh secret
     * (plan §B.2.3) rather than there being a second, weaker way in.
     */
    var studioApiSecret: String? = null,

    /** What this key is called in the key list, so the operator can tell their devices apart. */
    var studioKeyLabel: String? = null,

    /** Whether this device installed the engine or found one already on the account (§B.2.2). */
    var studioAdopted: Boolean = false,

    /** The Durable Object migration tag, once enforcement ships. Re-sending a tag errors (R1). */
    var studioDoTag: String? = null,

    var studioStatus: String = "idle", // idle | deployed | adopted | broken

    /** Engine build as last seen by /v1/health. Per installation: a fleet runs mixed builds (R13). */
    var studioVersion: Int = 0,
    var dnsVersion: Int = 0,
    var relayVersion: Int = 0,
    var poolVersion: Int = 0,

    /**
     * The VPN Gate relay worker.
     *
     * VPN Gate's own domain is unreachable from the networks this app is used on, so the server
     * list is fetched through a Worker on the user's own account. See assets/vpngate_relay_worker.js.
     */
    var relayWorkerUrl: String? = null,
    var relayStatus: String = "idle",

    /**
     * The MLMVPN shared-pool worker.
     *
     * Deployed once, on one account, and shared by every install -- unlike the panels, which are
     * per-user. See assets/mlmvpn_pool_worker.js.
     */
    var poolWorkerUrl: String? = null,
    var poolDbId: String? = null,
    var poolKvId: String? = null,
    var poolStatus: String = "idle",

    // Dedicated DNS (per-user ECS-steering resolver worker) fields
    var dnsWorkerUrl: String? = null,
    var dnsKvNamespaceId: String? = null,
    var dnsStatus: String = "idle", // 'idle', 'deployed'

    // GST relay accelerator (Cloudflare Worker that speeds up / stabilizes the
    // Google Apps Script tunnel by relaying the same protocol on the CF edge)
    var gstRelayWorkerUrl: String? = null,
    var gstRelayStatus: String = "idle", // 'idle', 'deployed'

    /**
     * The Gemini exit: a Worker whose Durable Object runs in North America, so Google sees a US
     * address for Gemini whichever Cloudflare data centre the phone reaches. See
     * assets/gemini_exit_worker.js.
     */
    var geminiExitUrl: String? = null,
    /** The VLESS user id the exit accepts. Stored so a redeploy keeps the same one. */
    var geminiExitId: String? = null,
    /** The exit's WebSocket path, without the leading slash. */
    var geminiExitPath: String? = null,
    var geminiExitStatus: String = "idle", // 'idle', 'deployed'
    /** The Durable Object migration tag, once created. Re-sending the creating migration errors. */
    var geminiExitDoTag: String? = null,
    /** The exit's build as deployed (CloudManager.GEMINI_EXIT_VERSION); 0 = deployed before builds were kept. */
    var geminiExitVersion: Int = 0,

    // Smart Verification fields
    var isEmailVerified: Boolean = true,
    var hasSubdomain: Boolean = false
) {
    /**
     * Whether this account's MLM engine is the one Config Studio manages.
     *
     * It is one engine: the Cloud tab's MLM panel and Config Studio run the same Worker on the same
     * database. What differs is who installed it -- Config Studio's installer adds its own route, its
     * key and the Durable Object that enforces device limits -- so everything that deploys, removes
     * or lists users on such an account goes through Config Studio rather than the old panel.
     */
    val isStudioManaged: Boolean
        get() = !mlmWorkerUrl.isNullOrEmpty() && !studioApiRoute.isNullOrEmpty()
}
