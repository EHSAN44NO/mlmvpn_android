package com.mlmvpn.scanner.data.studio.api

import com.mlmvpn.scanner.data.studio.api.dto.ConfigDto
import com.mlmvpn.scanner.data.studio.api.dto.DashboardDto
import com.mlmvpn.scanner.data.studio.api.dto.HealthDto
import com.mlmvpn.scanner.data.studio.api.dto.PlanDto
import com.mlmvpn.scanner.data.studio.api.dto.TemplateDto
import com.mlmvpn.scanner.data.studio.api.dto.PolicyDto
import com.mlmvpn.scanner.data.studio.api.dto.SubscriptionDto
import com.mlmvpn.scanner.data.studio.api.dto.UserDto
import com.mlmvpn.scanner.data.studio.api.dto.ActivityDto
import com.mlmvpn.scanner.data.studio.api.dto.NodeDto
import com.mlmvpn.scanner.data.studio.api.dto.NodeGroupDto
import com.mlmvpn.scanner.data.studio.api.dto.NodeHistoryDto
import com.mlmvpn.scanner.data.studio.domain.DashboardSnapshot
import com.mlmvpn.scanner.data.studio.domain.ActivityEntry
import com.mlmvpn.scanner.data.studio.domain.ActivitySeverity
import com.mlmvpn.scanner.data.studio.domain.NodeGroup
import com.mlmvpn.scanner.data.studio.domain.NodeHealth
import com.mlmvpn.scanner.data.studio.domain.NodeHistory
import com.mlmvpn.scanner.data.studio.domain.NodeProbe
import com.mlmvpn.scanner.data.studio.domain.NodeStrategy
import com.mlmvpn.scanner.data.studio.domain.StudioNode
import com.mlmvpn.scanner.data.studio.domain.Enforcement
import com.mlmvpn.scanner.data.studio.domain.ConfigTemplate
import com.mlmvpn.scanner.data.studio.domain.Plan
import com.mlmvpn.scanner.data.studio.domain.ExpiryMode
import com.mlmvpn.scanner.data.studio.domain.ResetPolicy
import com.mlmvpn.scanner.data.studio.domain.StudioConfig
import com.mlmvpn.scanner.data.studio.domain.StudioInstallation
import com.mlmvpn.scanner.data.studio.domain.StudioUser
import com.mlmvpn.scanner.data.studio.domain.Subscription
import com.mlmvpn.scanner.data.studio.domain.SubscriptionPolicy
import com.mlmvpn.scanner.data.studio.domain.TrafficUsage
import com.mlmvpn.scanner.data.studio.domain.UserStatus

/**
 * The boundary. Wire shapes in, domain types out.
 *
 * Deliberately total: every mapper produces a valid domain object from any DTO, including one whose
 * fields are all null. The app and the engine version independently — a phone can be talking to an
 * installation a build behind, or a build ahead — so a missing field has to read as a default, not
 * as a crash halfway down a user list.
 *
 * The one thing that is *not* defaulted is a user's `id`: a user without one cannot be addressed,
 * patched, renewed or deleted, so it is dropped at the boundary rather than carried as an object
 * that will fail every later operation for a reason no screen can explain.
 */
internal object StudioMappers {

    fun user(dto: UserDto, installationId: String): StudioUser? {
        val id = dto.id?.takeIf { it.isNotBlank() } ?: return null
        return StudioUser(
            id = id,
            username = dto.username.orEmpty(),
            credential = dto.credential,
            status = UserStatus.from(dto.status),
            note = dto.note?.takeIf { it.isNotBlank() },
            tags = dto.tags.orEmpty(),
            groupId = dto.groupId?.takeIf { it.isNotBlank() },
            planId = dto.planId?.takeIf { it.isNotBlank() },
            policy = policy(dto.policy),
            usage = TrafficUsage(
                usedBytes = dto.usage?.usedBytes ?: 0,
                dailyUsedBytes = dto.usage?.dailyUsedBytes ?: 0,
            ),
            createdAt = dto.createdAt,
            updatedAt = dto.updatedAt ?: 0,
            deletedAt = dto.deletedAt?.takeIf { it > 0 },
            lastActiveAt = dto.lastActiveAt?.takeIf { it > 0 },
            locations = dto.locations.orEmpty().filter { it.length == 2 },
            ports = dto.ports.orEmpty().filter { it in 1..65535 },
            installationId = installationId,
        )
    }

    fun users(dtos: List<UserDto>?, installationId: String): List<StudioUser> =
        dtos.orEmpty().mapNotNull { user(it, installationId) }

    fun policy(dto: PolicyDto?): SubscriptionPolicy {
        if (dto == null) return SubscriptionPolicy()
        return SubscriptionPolicy(
            expiryMode = ExpiryMode.from(dto.expiryMode),
            // Zero means "never" on the wire, and null means "never" in the domain. Carrying the
            // zero through would make `expiresAt = 0` look like 1970 to every date formatter.
            expiresAt = dto.expiresAt?.takeIf { it > 0 },
            activationDays = dto.activationDays?.takeIf { it > 0 },
            firstConnectAt = dto.firstConnectAt?.takeIf { it > 0 },
            quotaBytes = dto.quotaBytes?.takeIf { it > 0 },
            dailyQuotaBytes = dto.dailyQuotaBytes?.takeIf { it > 0 },
            reset = ResetPolicy.from(dto.reset),
            quotaResetAt = dto.quotaResetAt?.takeIf { it > 0 },
            deviceLimit = dto.deviceLimit?.takeIf { it > 0 },
            connLimit = dto.connLimit?.takeIf { it > 0 },
            ipLimit = dto.ipLimit?.takeIf { it > 0 },
            enforcement = Enforcement.from(dto.enforcement),
        )
    }

    /** The inverse, for create and patch. Only non-null fields are sent, so a PATCH stays partial. */
    fun policyBody(policy: SubscriptionPolicy): Map<String, Any?> = buildMap {
        put("expiry_mode", if (policy.expiryMode == ExpiryMode.ON_FIRST_CONNECT) "on_first_connect" else "absolute")
        policy.expiresAt?.let { put("expires_at", it) }
        policy.activationDays?.let { put("activation_days", it) }
        policy.quotaBytes?.let { put("quota_bytes", it) }
        policy.dailyQuotaBytes?.let { put("daily_quota_bytes", it) }
        put("reset", policy.reset.name.lowercase())
        policy.deviceLimit?.let { put("device_limit", it) }
        policy.connLimit?.let { put("conn_limit", it) }
        policy.ipLimit?.let { put("ip_limit", it) }
        put("enforcement", policy.enforcement.name.lowercase())
    }

    fun subscription(dto: SubscriptionDto): Subscription? {
        val id = dto.id?.takeIf { it.isNotBlank() } ?: return null
        return Subscription(
            id = id,
            userId = dto.userId.orEmpty(),
            token = dto.token.orEmpty(),
            url = dto.url.orEmpty(),
            pageUrl = dto.pageUrl.orEmpty(),
            createdAt = dto.createdAt ?: 0,
            rotatedAt = dto.rotatedAt?.takeIf { it > 0 },
            revokedAt = dto.revokedAt?.takeIf { it > 0 },
            hitSamples = dto.hitSamples ?: 0,
            lastHitAt = dto.lastHitAt?.takeIf { it > 0 },
            lastUserAgent = dto.lastUa?.takeIf { it.isNotBlank() },
        )
    }

    fun config(dto: ConfigDto): StudioConfig? {
        val id = dto.id?.takeIf { it.isNotBlank() } ?: return null
        return StudioConfig(
            id = id,
            userId = dto.userId.orEmpty(),
            label = dto.label?.takeIf { it.isNotBlank() },
            enabled = dto.enabled ?: true,
            protocol = dto.protocol,
            transportType = dto.transportType,
            credential = dto.credential?.takeIf { it.isNotBlank() },
            uriTemplate = dto.uriTemplate,
            routeKey = dto.routeKey?.takeIf { it.isNotBlank() },
            nodeIds = dto.nodes.orEmpty().filter { it.isNotBlank() },
            createdAt = dto.createdAt ?: 0,
            exitCc = dto.exitCc?.takeIf { it.length == 2 },
            exitIp = dto.exitIp?.takeIf { it.isNotBlank() },
        )
    }

    fun dashboard(dto: DashboardDto): DashboardSnapshot = DashboardSnapshot(
        total = dto.users?.total ?: 0,
        active = dto.users?.active ?: 0,
        expiringSoon = dto.users?.expiringSoon ?: 0,
        expired = dto.users?.expired ?: 0,
        overQuota = dto.users?.overQuota ?: 0,
        trafficUpBytes24h = dto.traffic24h?.upBytes ?: 0,
        trafficDownBytes24h = dto.traffic24h?.downBytes ?: 0,
        trafficUpBytes30d = dto.traffic30d?.upBytes ?: 0,
        trafficDownBytes30d = dto.traffic30d?.downBytes ?: 0,
        // `?:` is NOT used on these five. A missing field means an older engine that cannot answer,
        // and null carries that all the way to the screen so it draws nothing rather than a zero.
        online = dto.online,
        sessions24h = dto.sessions24h,
        configs = dto.configs,
        nodesTotal = dto.nodes?.total,
        nodesEnabled = dto.nodes?.enabled,
        nodesDown = dto.nodes?.down,
        enforcement = Enforcement.from(dto.enforcement),
        generatedAt = dto.generatedAt ?: 0,
    )

    /** Null for a row with no id: it cannot be paged past and would break the keyset walk. */
    fun activity(dto: ActivityDto, installationId: String): ActivityEntry? {
        val id = dto.id ?: return null
        return ActivityEntry(
            id = id,
            ts = dto.ts ?: 0,
            kind = dto.kind.orEmpty(),
            severity = ActivitySeverity.from(dto.severity),
            userId = dto.userId?.takeIf { it.isNotBlank() },
            username = dto.username?.takeIf { it.isNotBlank() },
            detail = dto.detail?.takeIf { it.isNotBlank() },
            installationId = installationId,
        )
    }

    fun node(dto: NodeDto, installationId: String): StudioNode = StudioNode(
        id = dto.id.orEmpty(),
        // Falls back to the host, which is what the engine does when a node is written without one,
        // so a row is never a blank line in a list.
        name = dto.name?.takeIf { it.isNotBlank() } ?: dto.host.orEmpty(),
        host = dto.host.orEmpty(),
        // A port the engine stored but this build cannot parse is dropped rather than crashing the
        // list; an empty result falls back to 443, which is what the engine assumes too.
        ports = dto.ports.orEmpty().mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..65535 }
            .ifEmpty { listOf(443) },
        country = dto.country?.takeIf { it.isNotBlank() },
        city = dto.city?.takeIf { it.isNotBlank() },
        sni = dto.sni?.takeIf { it.isNotBlank() },
        hostHeader = dto.hostHeader?.takeIf { it.isNotBlank() },
        groupId = dto.groupId?.takeIf { it.isNotBlank() },
        priority = dto.priority ?: 100,
        enabled = dto.enabled ?: true,
        health = NodeHealth.from(dto.health),
        healthCheckedAt = dto.healthCheckedAt?.takeIf { it > 0 },
        latencyMs = dto.latencyMs?.takeIf { it >= 0 },
        lastError = dto.lastError?.takeIf { it.isNotBlank() },
        installationId = installationId,
    )

    fun nodeGroup(dto: NodeGroupDto, installationId: String): NodeGroup? {
        val id = dto.id?.takeIf { it.isNotBlank() } ?: return null
        return NodeGroup(
            id = id,
            name = dto.name?.takeIf { it.isNotBlank() } ?: id,
            strategy = NodeStrategy.from(dto.strategy),
            priority = dto.priority ?: 100,
            enabled = dto.enabled ?: true,
            createdAt = dto.createdAt ?: 0,
            updatedAt = dto.updatedAt ?: 0,
            installationId = installationId,
        )
    }

    fun nodeHistory(dto: NodeHistoryDto, nodeId: String): NodeHistory = NodeHistory(
        nodeId = dto.nodeId?.takeIf { it.isNotBlank() } ?: nodeId,
        samples = dto.samples ?: 0,
        upSamples = dto.upSamples ?: 0,
        // Kept nullable all the way through. Zero per cent and "never measured" are different
        // claims and only one of them means an address needs replacing.
        uptime = dto.uptime,
        avgLatencyMs = dto.avgLatencyMs?.takeIf { it >= 0 },
        items = dto.items.orEmpty().map {
            NodeProbe(
                ts = it.ts ?: 0,
                up = it.up == true,
                latencyMs = it.latencyMs?.takeIf { v -> v >= 0 },
                error = it.error?.takeIf { e -> e.isNotBlank() },
            )
        },
    )

    fun installation(
        dto: HealthDto,
        installationId: String,
        cloudAccountId: String,
        accountLabel: String,
        workerUrl: String,
        apiRoute: String,
        adopted: Boolean,
    ): StudioInstallation = StudioInstallation(
        installationId = installationId,
        cloudAccountId = cloudAccountId,
        accountLabel = accountLabel,
        workerUrl = workerUrl,
        apiRoute = apiRoute,
        engineBuild = dto.version ?: 0,
        schemaVersion = dto.schemaVersion ?: 0,
        enforcement = Enforcement.from(dto.doMode),
        capabilities = dto.capabilities.orEmpty().toSet(),
        // "present" is what an UNAUTHENTICATED health call returns instead of the real text, so the
        // error is known to exist without its contents being handed to whoever asked.
        migrationError = dto.migrationError?.takeIf { it.isNotBlank() },
        d1Ok = dto.d1Ok ?: true,
        adopted = adopted,
    )

    /**
     * A «بسته» off the wire.
     *
     * Total like the rest, with one exception that mirrors the user mapper: a plan with no `id`
     * cannot be written, applied or synced to another account, so it is dropped at the boundary
     * rather than carried as an object that every later call has to re-check.
     */
    fun template(dto: TemplateDto): ConfigTemplate? {
        val id = dto.id?.takeIf { it.isNotBlank() } ?: return null
        return ConfigTemplate(
            id = id,
            name = dto.name.orEmpty(),
            protocol = dto.protocol?.takeIf { it.isNotBlank() },
            transportType = dto.transportType?.takeIf { it.isNotBlank() },
            fingerprint = dto.security?.fingerprint?.takeIf { it.isNotBlank() },
            alpn = dto.security?.alpn.orEmpty().filter { it.isNotBlank() },
            ownPath = dto.advanced?.ownPath,
            nodeIds = dto.nodes.orEmpty().filter { it.isNotBlank() },
            isDefault = dto.isDefault == true,
            archived = dto.archived == true,
            createdAt = dto.createdAt ?: 0,
            updatedAt = dto.updatedAt ?: 0,
        )
    }

    fun plan(dto: PlanDto): Plan? {
        val id = dto.id?.takeIf { it.isNotBlank() } ?: return null
        val p = dto.policy
        return Plan(
            id = id,
            name = dto.name.orEmpty(),
            quotaBytes = dto.quotaBytes?.takeIf { it > 0 },
            durationDays = dto.durationDays?.takeIf { it > 0 },
            expiryMode = ExpiryMode.from(p?.expiryMode),
            dailyQuotaBytes = p?.dailyQuotaBytes?.takeIf { it > 0 },
            reset = ResetPolicy.from(p?.reset),
            deviceLimit = p?.deviceLimit?.takeIf { it > 0 },
            connLimit = p?.connLimit?.takeIf { it > 0 },
            ipLimit = p?.ipLimit?.takeIf { it > 0 },
            templateId = dto.templateId?.takeIf { it.isNotBlank() },
            isDefault = dto.isDefault == true,
            archived = dto.archived == true,
            createdAt = dto.createdAt ?: 0,
            updatedAt = dto.updatedAt ?: 0,
        )
    }
}
