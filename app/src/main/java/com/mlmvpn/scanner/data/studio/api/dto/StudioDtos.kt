package com.mlmvpn.scanner.data.studio.api.dto

import com.google.gson.annotations.SerializedName

/**
 * The wire format, and nothing else.
 *
 * These mirror what the worker sends, snake_case and all. They are mapped to domain types at the
 * boundary (`StudioMappers`) and **must not escape `data/studio/api/`** — a screen holding one of
 * these is a screen that cannot survive an API change.
 *
 * Every field is nullable with a default. The app and the engine version independently: a phone can
 * be talking to an installation one build behind, so a field that does not exist yet must read as
 * absent rather than crash the parse. Gson leaves missing fields null, which is the behaviour that
 * makes this safe — hence the nullable types rather than non-null with defaults, which Gson would
 * happily overwrite with null anyway.
 */

data class PolicyDto(
    @SerializedName("expiry_mode") val expiryMode: String? = null,
    @SerializedName("expires_at") val expiresAt: Long? = null,
    @SerializedName("activation_days") val activationDays: Int? = null,
    @SerializedName("first_connect_at") val firstConnectAt: Long? = null,
    @SerializedName("quota_bytes") val quotaBytes: Long? = null,
    @SerializedName("daily_quota_bytes") val dailyQuotaBytes: Long? = null,
    @SerializedName("reset") val reset: String? = null,
    @SerializedName("quota_reset_at") val quotaResetAt: Long? = null,
    @SerializedName("device_limit") val deviceLimit: Int? = null,
    @SerializedName("conn_limit") val connLimit: Int? = null,
    @SerializedName("ip_limit") val ipLimit: Int? = null,
    @SerializedName("enforcement") val enforcement: String? = null,
)

data class UsageDto(
    @SerializedName("used_bytes") val usedBytes: Long? = null,
    @SerializedName("daily_used_bytes") val dailyUsedBytes: Long? = null,
)

data class UserDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("username") val username: String? = null,
    @SerializedName("credential") val credential: String? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("note") val note: String? = null,
    @SerializedName("tags") val tags: List<String>? = null,
    @SerializedName("group_id") val groupId: String? = null,
    @SerializedName("plan_id") val planId: String? = null,
    @SerializedName("policy") val policy: PolicyDto? = null,
    @SerializedName("usage") val usage: UsageDto? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("updated_at") val updatedAt: Long? = null,
    @SerializedName("deleted_at") val deletedAt: Long? = null,
    @SerializedName("last_active_at") val lastActiveAt: Long? = null,
    /** Build 17: countries of the person's configs. Non-empty = a multi-location user. */
    @SerializedName("locations") val locations: List<String>? = null,
    @SerializedName("ports") val ports: List<Int>? = null,
)

/** The paged list. `next_cursor` is null on the last page, so the app stops rather than re-asking. */
data class UserPageDto(
    @SerializedName("items") val items: List<UserDto>? = null,
    @SerializedName("next_cursor") val nextCursor: String? = null,
    @SerializedName("total_hint") val totalHint: Int? = null,
)

/**
 * The incremental sync page.
 *
 * `items` **includes tombstones** — rows with `deleted_at` set. That is the whole reason the sync
 * exists in this shape: a delete with nothing to carry it is invisible to every other device, so the
 * user reappears from another phone's cached copy on its next pull.
 */
data class UserSyncDto(
    @SerializedName("items") val items: List<UserDto>? = null,
    @SerializedName("next_since") val nextSince: Long? = null,
    /**
     * The second half of the watermark, for rows that share a timestamp.
     *
     * Null from an engine older than schema 10, which paged on the timestamp alone and therefore
     * skipped every row tied with the last one on a full page. The app carries whatever it gets;
     * an engine that sends nothing here behaves exactly as it did before.
     */
    @SerializedName("next_uid") val nextUid: String? = null,
    @SerializedName("complete") val complete: Boolean? = null,
)

/**
 * The handful of installation-scoped values an operator can set.
 *
 * Both are read by `/p/{token}` and, until the engine gained `/v1/settings`, could not be written by
 * anything -- so the contact line the plan promised on an expired subscription never appeared.
 */
data class SettingsDto(
    @SerializedName("contact") val contact: String? = null,
    @SerializedName("brand") val brand: String? = null,
)

/**
 * One device, as the Durable Object counted it.
 *
 * `device_hash` is a heuristic and not an identity: two phones behind one NAT running the same
 * client version collide, and one phone moving between Wi-Fi and mobile data can look like two.
 * Every screen that shows these has to say so.
 */
data class DeviceDto(
    @SerializedName("device_hash") val deviceHash: String? = null,
    @SerializedName("first_seen") val firstSeen: Long? = null,
    @SerializedName("last_seen") val lastSeen: Long? = null,
    @SerializedName("client_hint") val clientHint: String? = null,
    @SerializedName("blocked") val blocked: Int? = null,
    /** Build 18: connected right now (the Durable Object's presence table). Absent before. */
    @SerializedName("online") val online: Boolean? = null,
)

/**
 * `available: false` is a third state, and it is why this is not just a list.
 *
 * An installation with no Durable Object cannot count devices at all. An empty list would read as
 * "this person has never connected", which is a different thing and the operator would act on it.
 */
data class DeviceListDto(
    @SerializedName("available") val available: Boolean? = null,
    @SerializedName("reason") val reason: String? = null,
    @SerializedName("live") val live: Int? = null,
    @SerializedName("device_count") val deviceCount: Int? = null,
    @SerializedName("devices") val devices: List<DeviceDto>? = null,
)

data class TrafficPointDto(
    @SerializedName("ts") val ts: Long? = null,
    @SerializedName("up_bytes") val upBytes: Long? = null,
    @SerializedName("down_bytes") val downBytes: Long? = null,
)

data class TrafficSeriesDto(
    @SerializedName("granularity") val granularity: String? = null,
    @SerializedName("range_days") val rangeDays: Int? = null,
    @SerializedName("points") val points: List<TrafficPointDto>? = null,
    @SerializedName("total_bytes") val totalBytes: Long? = null,
)

data class TopUserDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("username") val username: String? = null,
    @SerializedName("bytes") val bytes: Long? = null,
    @SerializedName("sessions") val sessions: Int? = null,
)

data class TopUsersDto(@SerializedName("items") val items: List<TopUserDto>? = null)

data class AnalyticsSummaryDto(
    @SerializedName("range_days") val rangeDays: Int? = null,
    @SerializedName("new_users") val newUsers: Int? = null,
    @SerializedName("sessions") val sessions: Int? = null,
    @SerializedName("active_users") val activeUsers: Int? = null,
    @SerializedName("refusals") val refusals: Int? = null,
    @SerializedName("refusal_rate") val refusalRate: Float? = null,
    @SerializedName("bytes") val bytes: Long? = null,
)

data class UsageSliceDto(
    @SerializedName("id") val id: String? = null,
    /** The protocol and transport lists key on this instead; only one of the two is ever present. */
    @SerializedName("key") val key: String? = null,
    @SerializedName("label") val label: String? = null,
    @SerializedName("username") val username: String? = null,
    @SerializedName("sessions") val sessions: Int? = null,
    @SerializedName("bytes") val bytes: Long? = null,
)

data class UsageBreakdownDto(
    @SerializedName("range_days") val rangeDays: Int? = null,
    @SerializedName("configs") val configs: List<UsageSliceDto>? = null,
    @SerializedName("protocols") val protocols: List<UsageSliceDto>? = null,
    @SerializedName("transports") val transports: List<UsageSliceDto>? = null,
    @SerializedName("nodes_measurable") val nodesMeasurable: Boolean? = null,
)

data class CountPointDto(
    @SerializedName("ts") val ts: Long? = null,
    @SerializedName("sessions") val sessions: Int? = null,
    @SerializedName("users") val users: Int? = null,
    @SerializedName("bytes") val bytes: Long? = null,
)

data class CountSeriesDto(
    @SerializedName("range_days") val rangeDays: Int? = null,
    @SerializedName("granularity") val granularity: String? = null,
    @SerializedName("points") val points: List<CountPointDto>? = null,
)

data class SessionEntryDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("config_id") val configId: String? = null,
    @SerializedName("config_label") val configLabel: String? = null,
    @SerializedName("protocol") val protocol: String? = null,
    @SerializedName("transport") val transport: String? = null,
    @SerializedName("started_at") val startedAt: Long? = null,
    @SerializedName("ended_at") val endedAt: Long? = null,
    @SerializedName("bytes") val bytes: Long? = null,
    @SerializedName("close_reason") val closeReason: String? = null,
)

data class SessionListDto(@SerializedName("items") val items: List<SessionEntryDto>? = null)


data class AuditEntryDto(
    @SerializedName("id") val id: Long? = null,
    @SerializedName("ts") val ts: Long? = null,
    @SerializedName("actor") val actor: String? = null,
    @SerializedName("action") val action: String? = null,
    @SerializedName("target_type") val targetType: String? = null,
    @SerializedName("target_id") val targetId: String? = null,
    @SerializedName("summary") val summary: String? = null,
)

data class AuditPageDto(
    @SerializedName("items") val items: List<AuditEntryDto>? = null,
    @SerializedName("next_cursor") val nextCursor: Long? = null,
)

data class SubscriptionDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("user_id") val userId: String? = null,
    @SerializedName("token") val token: String? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("page_url") val pageUrl: String? = null,
    @SerializedName("created_at") val createdAt: Long? = null,
    @SerializedName("rotated_at") val rotatedAt: Long? = null,
    @SerializedName("revoked_at") val revokedAt: Long? = null,
    @SerializedName("hit_samples") val hitSamples: Int? = null,
    @SerializedName("last_hit_at") val lastHitAt: Long? = null,
    @SerializedName("last_ua") val lastUa: String? = null,
)

data class ConfigDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("user_id") val userId: String? = null,
    @SerializedName("label") val label: String? = null,
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("protocol") val protocol: String? = null,
    @SerializedName("transport_type") val transportType: String? = null,
    @SerializedName("credential") val credential: String? = null,
    @SerializedName("uri_template") val uriTemplate: String? = null,
    @SerializedName("route_key") val routeKey: String? = null,
    @SerializedName("nodes") val nodes: List<String>? = null,
    @SerializedName("created_at") val createdAt: Long? = null,
    @SerializedName("exit_cc") val exitCc: String? = null,
    @SerializedName("exit_ip") val exitIp: String? = null,
)

data class ConfigListDto(@SerializedName("items") val items: List<ConfigDto>? = null)

data class DashboardUsersDto(
    @SerializedName("total") val total: Int? = null,
    @SerializedName("active") val active: Int? = null,
    @SerializedName("expiring_soon") val expiringSoon: Int? = null,
    @SerializedName("expired") val expired: Int? = null,
    @SerializedName("over_quota") val overQuota: Int? = null,
)

data class DashboardTrafficDto(
    @SerializedName("up_bytes") val upBytes: Long? = null,
    @SerializedName("down_bytes") val downBytes: Long? = null,
)

data class DashboardDto(
    @SerializedName("users") val users: DashboardUsersDto? = null,
    @SerializedName("traffic_24h") val traffic24h: DashboardTrafficDto? = null,
    // Build 7. Every one of these is nullable and that is load-bearing: an installation on an older
    // engine simply omits them, and the mapper must be able to tell "this engine cannot answer"
    // from "the answer is zero".
    @SerializedName("traffic_30d") val traffic30d: DashboardTrafficDto? = null,
    @SerializedName("online") val online: Int? = null,
    @SerializedName("sessions_24h") val sessions24h: Int? = null,
    @SerializedName("configs") val configs: Int? = null,
    @SerializedName("nodes") val nodes: DashboardNodesDto? = null,
    @SerializedName("enforcement") val enforcement: String? = null,
    @SerializedName("generated_at") val generatedAt: Long? = null,
)

data class DashboardNodesDto(
    @SerializedName("total") val total: Int? = null,
    @SerializedName("enabled") val enabled: Int? = null,
    @SerializedName("down") val down: Int? = null,
)

data class NodeDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("country") val country: String? = null,
    @SerializedName("city") val city: String? = null,
    @SerializedName("host") val host: String? = null,
    /**
     * Strings on the wire, not numbers.
     *
     * The column is a CSV and the engine splits it without parsing, so a value it never validated
     * still arrives intact. Parsed on this side, where a bad entry can be dropped instead of
     * crashing a list.
     */
    @SerializedName("ports") val ports: List<String>? = null,
    @SerializedName("sni") val sni: String? = null,
    @SerializedName("host_header") val hostHeader: String? = null,
    @SerializedName("group_id") val groupId: String? = null,
    @SerializedName("priority") val priority: Int? = null,
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("health") val health: String? = null,
    @SerializedName("health_checked_at") val healthCheckedAt: Long? = null,
    @SerializedName("latency_ms") val latencyMs: Int? = null,
    @SerializedName("last_error") val lastError: String? = null,
)

data class NodeListDto(@SerializedName("items") val items: List<NodeDto>? = null)

data class NodeGroupDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("strategy") val strategy: String? = null,
    @SerializedName("priority") val priority: Int? = null,
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("created_at") val createdAt: Long? = null,
    @SerializedName("updated_at") val updatedAt: Long? = null,
)

data class NodeGroupListDto(@SerializedName("items") val items: List<NodeGroupDto>? = null)

/**
 * What the last fifty probes found.
 *
 * `uptime` is nullable on the wire and stays nullable here: the engine sends null for an endpoint
 * nobody has probed, and mapping that to 0f would print "0%" for an address that may be fine.
 */
data class NodeHistoryDto(
    @SerializedName("node_id") val nodeId: String? = null,
    @SerializedName("samples") val samples: Int? = null,
    @SerializedName("up_samples") val upSamples: Int? = null,
    @SerializedName("uptime") val uptime: Float? = null,
    @SerializedName("avg_latency_ms") val avgLatencyMs: Int? = null,
    @SerializedName("items") val items: List<NodeProbeDto>? = null,
)

data class NodeProbeDto(
    @SerializedName("ts") val ts: Long? = null,
    @SerializedName("up") val up: Boolean? = null,
    @SerializedName("latency_ms") val latencyMs: Int? = null,
    @SerializedName("error") val error: String? = null,
)

data class ActivityDto(
    @SerializedName("id") val id: Long? = null,
    @SerializedName("ts") val ts: Long? = null,
    @SerializedName("kind") val kind: String? = null,
    @SerializedName("severity") val severity: String? = null,
    @SerializedName("user_id") val userId: String? = null,
    @SerializedName("username") val username: String? = null,
    @SerializedName("detail") val detail: String? = null,
)

data class ActivityPageDto(
    @SerializedName("items") val items: List<ActivityDto>? = null,
    @SerializedName("next_cursor") val nextCursor: Long? = null,
)

data class HealthDto(
    @SerializedName("version") val version: Int? = null,
    @SerializedName("schema_version") val schemaVersion: Int? = null,
    @SerializedName("d1_ok") val d1Ok: Boolean? = null,
    @SerializedName("do_mode") val doMode: String? = null,
    @SerializedName("migration_error") val migrationError: String? = null,
    @SerializedName("capabilities") val capabilities: List<String>? = null,
    @SerializedName("authenticated") val authenticated: Boolean? = null,
)

data class ApiKeyDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("label") val label: String? = null,
    @SerializedName("created_at") val createdAt: Long? = null,
    @SerializedName("last_used_at") val lastUsedAt: Long? = null,
    @SerializedName("revoked_at") val revokedAt: Long? = null,
)

data class ApiKeyListDto(@SerializedName("items") val items: List<ApiKeyDto>? = null)

/**
 * The error envelope.
 *
 * **The app maps `code`, never `message`.** Codes are stable and machine-readable; messages are for
 * logs and change freely. Anything a person reads is looked up in `values-fa/strings.xml`, so the
 * engine never dictates the wording of a screen — which also means an engine one build ahead cannot
 * put untranslated English in front of the operator.
 */
data class ErrorBodyDto(@SerializedName("error") val error: ErrorDto? = null)

data class ErrorDto(
    @SerializedName("code") val code: String? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("detail") val detail: String? = null,
)

// ---------------------------------------------------------------------------- «بسته»

data class PlanDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("quota_bytes") val quotaBytes: Long? = null,
    @SerializedName("duration_days") val durationDays: Int? = null,
    @SerializedName("policy") val policy: PlanPolicyDto? = null,
    @SerializedName("template_id") val templateId: String? = null,
    @SerializedName("is_default") val isDefault: Boolean? = null,
    @SerializedName("archived") val archived: Boolean? = null,
    @SerializedName("created_at") val createdAt: Long? = null,
    @SerializedName("updated_at") val updatedAt: Long? = null,
)

data class PlanPolicyDto(
    @SerializedName("expiry_mode") val expiryMode: String? = null,
    @SerializedName("daily_quota_bytes") val dailyQuotaBytes: Long? = null,
    @SerializedName("reset") val reset: String? = null,
    @SerializedName("device_limit") val deviceLimit: Int? = null,
    @SerializedName("conn_limit") val connLimit: Int? = null,
    @SerializedName("ip_limit") val ipLimit: Int? = null,
)

data class PlanListDto(@SerializedName("items") val items: List<PlanDto>? = null)

/**
 * A «قالب» on the wire.
 *
 * `transport`, `security` and `advanced` are free-form objects on the engine — it stores them as
 * JSON and never reads inside them. Only the keys this app writes are declared here; an engine one
 * build ahead may carry more, and Gson drops what it does not know rather than failing the parse.
 */
data class TemplateDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("protocol") val protocol: String? = null,
    @SerializedName("transport_type") val transportType: String? = null,
    @SerializedName("security") val security: TemplateSecurityDto? = null,
    @SerializedName("advanced") val advanced: TemplateAdvancedDto? = null,
    @SerializedName("nodes") val nodes: List<String>? = null,
    @SerializedName("is_default") val isDefault: Boolean? = null,
    @SerializedName("archived") val archived: Boolean? = null,
    @SerializedName("created_at") val createdAt: Long? = null,
    @SerializedName("updated_at") val updatedAt: Long? = null,
)

data class TemplateSecurityDto(
    @SerializedName("fingerprint") val fingerprint: String? = null,
    @SerializedName("alpn") val alpn: List<String>? = null,
)

data class TemplateAdvancedDto(
    @SerializedName("own_path") val ownPath: Boolean? = null,
)

data class TemplateListDto(@SerializedName("items") val items: List<TemplateDto>? = null)

/** What `:apply` reports back: how many rows it wrote, and which fields it moved. */
data class PlanApplyDto(
    @SerializedName("plan_id") val planId: String? = null,
    @SerializedName("users") val users: Int? = null,
    @SerializedName("applied") val applied: List<String>? = null,
)

data class PlanUsersDto(
    @SerializedName("plan_id") val planId: String? = null,
    @SerializedName("users") val users: Int? = null,
)

data class ExitDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("cc") val cc: String? = null,
    @SerializedName("label") val label: String? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("display") val display: String? = null,
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("health") val health: String? = null,
    @SerializedName("exit_ip") val exitIp: String? = null,
    @SerializedName("exit_cc") val exitCc: String? = null,
    @SerializedName("latency_ms") val latencyMs: Int? = null,
    @SerializedName("checked_at") val checkedAt: Long? = null,
    @SerializedName("ok") val ok: Boolean? = null,
    @SerializedName("error") val error: String? = null,
)

data class ExitListDto(@SerializedName("items") val items: List<ExitDto>? = null)

data class ExitRejectDto(
    @SerializedName("url") val url: String? = null,
    @SerializedName("reason") val reason: String? = null,
)

data class ExitAddDto(
    @SerializedName("added") val added: List<String>? = null,
    @SerializedName("rejected") val rejected: List<ExitRejectDto>? = null,
)
