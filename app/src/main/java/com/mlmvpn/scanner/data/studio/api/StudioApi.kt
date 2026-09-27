package com.mlmvpn.scanner.data.studio.api

import com.mlmvpn.scanner.data.studio.domain.DashboardSnapshot
import com.mlmvpn.scanner.data.studio.domain.ConfigTemplate
import com.mlmvpn.scanner.data.studio.domain.Plan
import com.mlmvpn.scanner.data.studio.domain.StudioConfig
import com.mlmvpn.scanner.data.studio.domain.ActivityEntry
import com.mlmvpn.scanner.data.studio.domain.StudioInstallation
import com.mlmvpn.scanner.data.studio.domain.NodeGroup
import com.mlmvpn.scanner.data.studio.domain.NodeHistory
import com.mlmvpn.scanner.data.studio.domain.StudioNode
import com.mlmvpn.scanner.data.studio.domain.StudioUser
import com.mlmvpn.scanner.data.studio.domain.Subscription
import com.mlmvpn.scanner.data.studio.domain.SubscriptionPolicy

/**
 * One installation's control plane, in domain terms.
 *
 * An interface rather than a class so the repository — which fans out across a fleet of these — can
 * be exercised without a network, and so the transport can change without the layers above noticing.
 * Every method returns [StudioResult]: nothing here throws for an ordinary failure, because "the
 * engine said no" and "the phone has no signal" are both things a screen has to render, not crash on.
 */
/**
 * What a renewal does to what is already there.
 *
 * Three, because the first one could only ever give. [RESET_USAGE] is separate from [RESTART] and
 * not a special case of it: "their volume was spent by something that was not them" is answered by
 * zeroing the meter, and answering it with [RESTART] would silently move the expiry too.
 */
enum class RenewMode(val wire: String) {
    EXTEND("extend"), RESTART("restart"), RESET_USAGE("reset_usage");
}

interface StudioApi {

    suspend fun health(): StudioResult<StudioInstallation>

    suspend fun dashboard(): StudioResult<DashboardSnapshot>

    /** One page of the ordinary list, keyset-ordered. `cursor` comes from the previous page. */
    suspend fun listUsers(
        cursor: String? = null,
        limit: Int = 50,
        query: String? = null,
        status: String? = null,
    ): StudioResult<UserPage>

    /**
     * Everything that changed since [since], **tombstones included**.
     *
     * This is what feeds the local index. It is a different call from [listUsers] rather than a flag
     * on it because the two answer different questions and order by different columns — and because
     * a sync that silently dropped deletions would look like it worked.
     */
    suspend fun syncUsers(since: Long, sinceUid: String = "", limit: Int = 200): StudioResult<UserSyncPage>

    suspend fun getUser(id: String): StudioResult<StudioUser>

    suspend fun createUser(
        username: String,
        policy: SubscriptionPolicy,
        note: String? = null,
        tags: List<String> = emptyList(),
        planId: String? = null,
    ): StudioResult<StudioUser>

    suspend fun patchUser(
        id: String,
        username: String? = null,
        note: String? = null,
        tags: List<String>? = null,
        planId: String? = null,
        status: String? = null,
        policy: SubscriptionPolicy? = null,
        /**
         * Policy fields to send as an explicit null -- "remove this cap". The policy body leaves a
         * null field out so a PATCH stays partial, which also meant a cap could never be removed.
         */
        clearPolicyFields: Set<String> = emptySet(),
    ): StudioResult<StudioUser>

    suspend fun deleteUser(id: String): StudioResult<Unit>

    suspend fun setEnabled(id: String, enabled: Boolean): StudioResult<StudioUser>

    /**
     * Change what someone has. **The subscription token does not change**, which is the property the
     * whole flow is built around: the person holding the link does nothing and is not contacted.
     */
    suspend fun renewUser(
        id: String,
        planId: String? = null,
        /**
         * May be NEGATIVE on [RenewMode.EXTEND].
         *
         * Taking a grant back is the same operation as making it, and without this the only way to
         * undo a mistyped 300 is to delete the person — which loses their link, the one thing this
         * flow exists to protect. The engine floors the resulting expiry at now and the resulting
         * quota at what they have already used.
         */
        addDays: Int? = null,
        addBytes: Long? = null,
        mode: RenewMode = RenewMode.EXTEND,
    ): StudioResult<StudioUser>

    suspend fun getSubscription(userId: String): StudioResult<Subscription>

    /** Takes effect immediately. A link the operator believes they revoked must not still work. */
    suspend fun rotateSubscription(userId: String): StudioResult<Subscription>

    suspend fun listConfigs(userId: String): StudioResult<List<StudioConfig>>

    /**
     * @param uriTemplate rendered by `ConfigBuilder` on this side. The worker builds no URIs and
     *   refuses a config without one — that is what keeps the link the app hands out and the
     *   outbound it dials from being built by two different pieces of code.
     */
    suspend fun createConfig(
        userId: String,
        uriTemplate: String,
        label: String? = null,
        protocol: String? = null,
        transportType: String? = null,
        credential: String? = null,
        routeKey: String? = null,
        /**
         * What the engine matches an incoming credential against, for a protocol that sends a
         * derived value rather than the credential itself. Null for VLESS, which sends its UUID.
         */
        authHash: String? = null,
        /**
         * Which endpoints this config is served on. Empty is every enabled one, which is what every
         * config written before build 9 has and what a config with no opinion should do.
         *
         * Needs `configs.nodes` on the installation. An older engine accepts the field and drops
         * it, so the caller checks the capability rather than assuming the choice was kept.
         */
        nodeIds: List<String> = emptyList(),
        /** The country this config leaves from, or null. Needs `exits.v1` on the installation. */
        exitCc: String? = null,
    ): StudioResult<StudioConfig>

    /**
     * Give this config a new credential.
     *
     * Takes effect on the next connection: an open tunnel authenticated once, at its start, and
     * nothing re-checks it — so rotating cuts off the next attempt rather than the current session.
     * Said plainly on the screen rather than implied, because "revoked" that leaves a live tunnel
     * up for another hour is the kind of half-true an operator acts on.
     */
    suspend fun rotateConfig(
        id: String,
        credential: String,
        uriTemplate: String,
        authHash: String? = null,
    ): StudioResult<Unit>

    /**
     * Point one config at a different set of endpoints, leaving its credential alone.
     *
     * The move «ترکیب با آی‌پی تمیز» is made of, and separate from [rotateConfig] for the reason
     * that makes it useful: nothing about the secret changes, so the subscription URL is the same
     * one and every device the person already set up keeps working. What changes is the address
     * their next subscription refresh is handed.
     *
     * Empty means every enabled endpoint — undoing a retarget has to be as easy as doing one.
     *
     * Needs `configs.retarget`. An engine on build 10 answers this with a 404 rather than dropping
     * it silently, so a caller that forgot the capability check fails loudly.
     */
    suspend fun retargetConfig(id: String, nodeIds: List<String>): StudioResult<Unit>

    suspend fun deleteConfig(id: String): StudioResult<Unit>

    // ------------------------------------------------------------------ «بسته»

    /**
     * «قالب» — the config shapes this installation carries.
     *
     * Written by the app to every installation under the same id, exactly as plans are, so a
     * template is one thing across the fleet rather than N unrelated rows (§D, R14).
     */
    suspend fun listTemplates(includeArchived: Boolean = false): StudioResult<List<ConfigTemplate>>

    /** Create or replace, under an id the CALLER chose. Idempotent, so a fleet write can retry. */
    suspend fun putTemplate(template: ConfigTemplate): StudioResult<ConfigTemplate>

    /**
     * Really deleted, unlike a plan.
     *
     * Nothing points at a template after the fact — a config copies the shape and carries its own
     * `uri_template` — so there is no history to rewrite. The engine clears any `plans.template_id`
     * pointing at it in the same batch.
     */
    suspend fun deleteTemplate(id: String): StudioResult<Unit>

    suspend fun listPlans(includeArchived: Boolean = false): StudioResult<List<Plan>>

    /**
     * Create or replace, under an id the **caller** chose.
     *
     * Idempotent, and that is what makes fleet sync possible: writing one plan across an uncapped
     * fleet is one request per account, any of which can fail and be retried, and a retry must not
     * produce a second plan or a conflict.
     */
    suspend fun putPlan(plan: Plan): StudioResult<Plan>

    /** Archives. A plan is never erased: users and renewals both point at its id. */
    suspend fun archivePlan(id: String): StudioResult<Unit>

    /** How many people on this installation are on this plan. */
    suspend fun countPlanUsers(id: String): StudioResult<Int>

    /**
     * Push this plan's terms onto the people already on it. Audited, and never automatic.
     *
     * @param fields which terms to move. Empty means the caps only — volume and time are excluded
     *   by default, because someone topped up by a renewal must not be pulled back down by an
     *   operator who only meant to change a device limit.
     * @return how many users were written.
     */
    suspend fun applyPlan(id: String, fields: List<String> = emptyList()): StudioResult<Int>

    // ------------------------------------------------------------------ devices

    /**
     * The devices this person has connected from, counted by the thing that enforces the cap.
     *
     * Read from the Durable Object rather than from a table, because anything else would be a
     * second number that can disagree with the one doing the refusing.
     */
    suspend fun listDevices(userId: String): StudioResult<DeviceReport>

    /** `block`, `unblock` or `forget`. */
    suspend fun deviceAction(userId: String, deviceHash: String, action: String): StudioResult<Unit>

    // ------------------------------------------------------------------ analytics

    /**
     * Traffic over time, from the rollup tables rather than from `users`.
     *
     * Scoped to ONE installation. There is no fleet-wide chart, and that is a decision rather than a
     * gap: with an uncapped number of accounts a merged series is one request per account per open,
     * and one that quietly drops the accounts that did not answer is worse than none.
     */
    suspend fun traffic(range: String = "7d", daily: Boolean = false): StudioResult<TrafficSeries>

    /** Who used the most **in the range** — not who has the largest lifetime total. */
    suspend fun topUsers(range: String = "7d", limit: Int = 10): StudioResult<List<TopUser>>

    /**
     * The one-number figures, in one round trip.
     *
     * Needs `analytics.v2`. An engine on schema 15 answers with a 404 rather than with zeros,
     * which is the difference the app needs: "this account cannot answer this yet" and "nobody
     * connected this week" are the same picture and opposite instructions.
     */
    suspend fun analyticsSummary(range: String = "7d"): StudioResult<AnalyticsSummary>

    /** What carried the traffic: per config, per protocol, per transport. Needs `analytics.v2`. */
    suspend fun analyticsBreakdown(range: String = "7d", limit: Int = 10): StudioResult<UsageBreakdown>

    /** Connections over time, optionally for one person. Needs `analytics.v2`. */
    suspend fun analyticsSessions(
        range: String = "7d",
        daily: Boolean = true,
        userId: String? = null,
    ): StudioResult<CountSeries>

    /** People added over time, by the day. Needs `analytics.v2`. */
    suspend fun analyticsNewUsers(range: String = "30d"): StudioResult<CountSeries>

    /**
     * One person's connection history, newest first by when each ended.
     *
     * By when it ENDED, not when it started: a session that opened yesterday and closed a minute
     * ago belongs at the top of a history, and ordering by the start would bury it a day down.
     */
    suspend fun userSessions(userId: String, limit: Int = 20): StudioResult<List<SessionEntry>>

    /** What a person did, newest first, keyset-paged. `cursor` comes from the previous page. */
    suspend fun audit(cursor: Long? = null, action: String? = null, limit: Int = 50): StudioResult<AuditPage>

    /**
     * What the engine observed, newest first — refusals, unknown credentials, failures.
     *
     * A different question from [audit], and a different table: that one is what a person decided
     * and is kept, this one is written by the data plane and pruned to a few thousand rows.
     *
     * [severity] of `warn` returns warnings AND errors, because an operator narrowing to "problems"
     * does not mean "problems, but not the serious ones".
     */
    suspend fun activity(
        cursor: Long? = null,
        severity: String? = null,
        limit: Int = 50,
        /**
         * One person's own feed rather than the fleet's.
         *
         * The same rows, narrowed: the data plane already stamps every refusal with the uid, so
         * this is a filter and not a second table. "Why is THIS person being cut off" is the
         * question actually asked, and the unfiltered list buries it on any busy account.
         */
        userId: String? = null,
    ): StudioResult<ActivityPage>

    // ------------------------------------------------------------------ nodes

    /**
     * The addresses this installation serves subscriptions on, in priority order.
     *
     * Unpaged, and that is a property of the thing rather than an oversight: an installation has a
     * handful of endpoints, the engine caps the read at 200, and this same list is what every
     * subscription fetch fans out over.
     */
    suspend fun listNodes(): StudioResult<List<StudioNode>>

    /**
     * Create or replace one node under an id the CALLER chose.
     *
     * Idempotent, for the same reason plans are: an operator who wants one clean IP used across the
     * fleet writes the same node to several installations, and a retry after a partial failure must
     * not leave a second copy behind.
     */
    suspend fun putNode(node: StudioNode): StudioResult<StudioNode>

    suspend fun deleteNode(id: String): StudioResult<Unit>

    /**
     * Record what a probe found.
     *
     * **The probe runs on the phone, not in the worker.** A worker timing its own request to a node
     * measures Cloudflare's network to that address, which is not the number an operator in Iran
     * needs — what matters is whether the address answers, and how fast, from where they are. The
     * only machine in this system standing there is this one.
     */
    suspend fun reportNodeHealth(
        id: String,
        ok: Boolean,
        latencyMs: Int? = null,
        error: String? = null,
    ): StudioResult<StudioNode>

    /**
     * «گروه نقطه» — the sets of endpoints and the order each hands them out in.
     *
     * Needs `node.groups`. An installation without it answers 404, which the store reads as "too
     * old" rather than as a failure.
     */
    suspend fun listNodeGroups(): StudioResult<List<NodeGroup>>

    /** Create or replace under a caller-chosen id, idempotent like every other fleet write. */
    suspend fun putNodeGroup(group: NodeGroup): StudioResult<NodeGroup>

    /** The group goes; its endpoints stay and are ungrouped in the same batch. */
    suspend fun deleteNodeGroup(id: String): StudioResult<Unit>

    /**
     * The last fifty probes on one endpoint, and the uptime they imply.
     *
     * Needs `node.history`. The nodes table only ever held the LAST result, so "is this address
     * flaky or did it fail once" was a question nothing could answer.
     */
    suspend fun nodeHistory(id: String): StudioResult<NodeHistory>

    // ------------------------------------------------------------------ installation settings

    /** What this installation's subscriber page says about who to contact, and what it calls itself. */
    suspend fun getSettings(): StudioResult<StudioSettings>

    /** Null leaves a field alone; an empty string clears it. */
    suspend fun patchSettings(contact: String? = null, brand: String? = null): StudioResult<StudioSettings>
}

/** One page of [ActivityEntry], with the cursor for the next. Null means the walk is over. */
data class ActivityPage(
    val items: List<ActivityEntry> = emptyList(),
    val nextCursor: Long? = null,
)

/**
 * The operator's own words on the page a subscriber opens.
 *
 * Free text on purpose, and the reason is D6: an expired subscription has to be able to say how to
 * get it renewed, and the app must ship none of that wording itself. What the operator arranges
 * with someone is not this product's business.
 */
data class StudioSettings(
    val contact: String? = null,
    val brand: String? = null,
)

/**
 * What an installation can say about one person's devices.
 *
 * [available] is false when this installation has no Durable Object and therefore cannot count
 * them. That is not an empty list: an empty list means the person has never connected, and an
 * operator would act differently on each.
 */
data class DeviceReport(
    val available: Boolean,
    /** Build 18: devices connected right now. Before it, open connections. */
    val live: Int = 0,
    val devices: List<StudioDevice> = emptyList(),
    /**
     * Why [available] is false: `do_unavailable` (this installation has no Durable Object, so it
     * cannot count or refuse), `do_error` (it has one and it did not answer), or [UNREACHABLE] (the
     * request itself failed). Only the first means the limit is not applied; the other two mean
     * "try again", and saying "not applied" for them would be telling the operator something false.
     */
    val reason: String? = null,
) {
    val activeCount: Int get() = devices.count { !it.blocked }

    /** Whether this report is a failure to read rather than a statement about the installation. */
    val failedToRead: Boolean get() = !available && (reason == UNREACHABLE || reason == "do_error")

    companion object {
        const val UNREACHABLE = "unreachable"
    }
}

/**
 * One device — a **heuristic**, not an identity.
 *
 * The hash is `sha256(salt ‖ client hint ‖ /24 of the address)`, so two phones behind one NAT on the
 * same client version collide and one phone crossing between Wi-Fi and mobile data can register
 * twice. Not fixable without something no VPN client sends, so every screen that draws these says
 * so rather than presenting them as a device list they are not.
 */
data class StudioDevice(
    val hash: String,
    val firstSeen: Long,
    val lastSeen: Long,
    val clientHint: String?,
    val blocked: Boolean,
    /** Connected right now -- counted against the limit (build 18). */
    val online: Boolean = false,
)

data class TrafficPoint(val ts: Long, val upBytes: Long, val downBytes: Long) {
    val totalBytes: Long get() = upBytes + downBytes
}

data class TrafficSeries(
    val daily: Boolean,
    val rangeDays: Int,
    val points: List<TrafficPoint>,
    val totalBytes: Long,
) {
    /** The largest point, for scaling a chart. Zero when there is nothing to draw. */
    val peak: Long get() = points.maxOfOrNull { it.totalBytes } ?: 0
}

data class TopUser(val id: String, val username: String?, val bytes: Long, val sessions: Int)

/**
 * The figures that are one number each, over a range.
 *
 * [refusalRate] is refusals over **attempts**, not over sessions: a refused connection never became
 * a session, so a period in which everything was refused would otherwise read as zero per cent.
 */
data class AnalyticsSummary(
    val rangeDays: Int,
    val newUsers: Int,
    val sessions: Int,
    val activeUsers: Int,
    val refusals: Int,
    val refusalRate: Float,
    val bytes: Long,
)

data class UsageSlice(
    /** A config id, or a one-letter protocol, or a transport name — whichever list this came from. */
    val key: String,
    val label: String?,
    val username: String?,
    val sessions: Int,
    val bytes: Long,
)

/**
 * What carried the traffic.
 *
 * [nodesMeasurable] is false and will stay false, and the screen says so rather than drawing an
 * empty section: a Worker never learns which Cloudflare address the client dialled, so per-endpoint
 * traffic is not a figure that is missing — it is one this architecture cannot produce.
 */
data class UsageBreakdown(
    val rangeDays: Int,
    val configs: List<UsageSlice>,
    val protocols: List<UsageSlice>,
    val transports: List<UsageSlice>,
    val nodesMeasurable: Boolean,
)

data class CountPoint(val ts: Long, val count: Int, val bytes: Long = 0)

data class CountSeries(val rangeDays: Int, val points: List<CountPoint>) {
    val peak: Int get() = points.maxOfOrNull { it.count } ?: 0
    val total: Int get() = points.sumOf { it.count }
}

/**
 * One connection somebody actually made.
 *
 * [closeReason] of `no_traffic` is the row worth reading: a connection that authenticated and then
 * carried nothing is what an operator is looking at when somebody says "it connects and nothing
 * happens", and it appears in no other table.
 */
data class SessionEntry(
    val id: String,
    val configId: String?,
    val configLabel: String?,
    val protocol: String?,
    val transport: String?,
    val startedAt: Long,
    val endedAt: Long,
    val bytes: Long,
    val closeReason: String?,
) {
    val durationMs: Long get() = (endedAt - startedAt).coerceAtLeast(0)
    val carriedNothing: Boolean get() = bytes <= 0
}


data class AuditEntry(
    val id: Long,
    val ts: Long,
    val actor: String,
    val action: String,
    val targetType: String?,
    val targetId: String?,
    val summary: String?,
)

data class AuditPage(val items: List<AuditEntry>, val nextCursor: Long?)

data class UserPage(
    val items: List<StudioUser>,
    val nextCursor: String?,
    /** From the counters table, not `COUNT(*)`. It sizes a scrollbar; it is not a fact. */
    val totalHint: Int?,
)

data class UserSyncPage(
    val items: List<StudioUser>,
    val nextSince: Long,
    /**
     * The tie-break half of the watermark.
     *
     * Two rows can carry the same `updated_at` — `:apply` writes one timestamp across every user on
     * a «بسته», and the schema-6 backfill gave every legacy user the same one — so a watermark that
     * is only a timestamp cannot say where inside that group the last page stopped. Empty when the
     * installation is older than schema 10 and does not send one.
     */
    val nextUid: String,
    /** False when the page was full, meaning there is more to pull before the index is current. */
    val complete: Boolean,
)

sealed class StudioResult<out T> {
    data class Ok<out T>(val value: T) : StudioResult<T>()
    data class Err(val error: StudioError) : StudioResult<Nothing>()

    inline fun <R> map(transform: (T) -> R): StudioResult<R> = when (this) {
        is Ok -> Ok(transform(value))
        is Err -> this
    }

    val valueOrNull: T? get() = (this as? Ok)?.value
}

/**
 * A failure, identified by its **code**.
 *
 * The codes are the worker's stable machine names — `unauthorized`, `username_taken`,
 * `quota_exceeded`, `schema_stale`, `do_unavailable` — plus the two the network produces, which the
 * engine can never report because it was never reached.
 */
data class StudioError(
    val code: String,
    val httpStatus: Int = 0,
    /** For logs and bug reports. Never rendered: user-facing wording lives in strings.xml. */
    val detail: String? = null,
) {
    val isAuth: Boolean get() = code == UNAUTHORIZED
    val isOffline: Boolean get() = code == NETWORK

    companion object {
        const val NETWORK = "network"
        const val MALFORMED = "malformed_response"
        const val UNAUTHORIZED = "unauthorized"
        const val NOT_CONFIGURED = "not_configured"

        /** The engine's own code for a row it does not have. Spelled the same on both sides. */
        const val NOT_FOUND = "not_found"
    }
}
