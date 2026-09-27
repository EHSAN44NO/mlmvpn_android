package com.mlmvpn.scanner.ui.configstudio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.studio.StudioState
import com.mlmvpn.scanner.data.studio.StudioStore
import com.mlmvpn.scanner.data.studio.api.AnalyticsSummary
import com.mlmvpn.scanner.data.studio.api.CountSeries
import com.mlmvpn.scanner.data.studio.api.StudioHttpApi
import com.mlmvpn.scanner.data.studio.api.StudioResult
import com.mlmvpn.scanner.data.studio.api.TopUser
import com.mlmvpn.scanner.data.studio.api.TrafficSeries
import com.mlmvpn.scanner.data.studio.api.UsageBreakdown
import com.mlmvpn.scanner.data.studio.api.UsageSlice
import com.mlmvpn.scanner.data.studio.domain.ProtocolType
import com.mlmvpn.scanner.data.studio.domain.TransportType
import com.mlmvpn.scanner.ui.configstudio.parts.InfoCard
import com.mlmvpn.scanner.ui.configstudio.parts.ProblemCard
import com.mlmvpn.scanner.ui.configstudio.parts.StudioChip
import com.mlmvpn.scanner.ui.configstudio.parts.WizardBusy
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.utils.S

/**
 * Traffic over time, who used it, and what carried it.
 *
 * **Scoped to one account, and it says so.** There is no fleet-wide chart, and that is a decision
 * rather than a gap: with an uncapped number of Cloudflare accounts a merged series is one request
 * per account every time the screen opens, and one that quietly drops the accounts that did not
 * answer is worse than none at all. The account picker at the top is how the operator moves between
 * them, so what is on screen always belongs to something named.
 *
 * Three things about the numbers:
 *
 *  * **They come from rollup tables**, never from the users table. D1 bills rows read, and an
 *    analytics screen is exactly where a careless query looks harmless and costs the whole table on
 *    every open.
 *  * **"Top users" means in this range**, not by lifetime total. Ordering by the lifetime counter
 *    would rank whoever has been a subscriber longest, which is a different question and a less
 *    useful one.
 *  * **There is no per-endpoint section, and the screen says why.** A Worker never learns which
 *    Cloudflare address a client dialled, so that figure is not missing — it is one this
 *    architecture cannot produce, and drawing an empty section would invite waiting for it.
 */
@Composable
fun StudioAnalyticsScreen(
    store: StudioStore,
    state: StudioState,
    /** Null when this is the Stats tab itself. */
    onBack: (() -> Unit)?,
) {
    val context = LocalContext.current
    val accounts = remember(state.installations) { store.installedAccounts() }

    var shard by remember { mutableStateOf(accounts.firstOrNull()?.id) }
    var range by remember { mutableStateOf("7d") }
    var daily by remember { mutableStateOf(false) }
    var series by remember { mutableStateOf<TrafficSeries?>(null) }
    var top by remember { mutableStateOf<List<TopUser>>(emptyList()) }
    var summary by remember { mutableStateOf<AnalyticsSummary?>(null) }
    var breakdown by remember { mutableStateOf<UsageBreakdown?>(null) }
    var sessions by remember { mutableStateOf<CountSeries?>(null) }
    var newUsers by remember { mutableStateOf<CountSeries?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // Build 12. Asked per installation rather than globally, for the reason R13 gives: one account
    // on an older engine while another is current is an ordinary state in a fleet. Where it is
    // false the extra sections are not drawn at all, because an engine on schema 15 has a
    // `sessions` table that nothing ever wrote to — every one of those endpoints would answer with
    // an empty list that looks exactly like a quiet week.
    val canV2 = state.installations.firstOrNull { it.installationId == shard }?.can("analytics.v2") == true

    // One loader, for the first open, every change of account or range, and pull-to-refresh.
    suspend fun load(quiet: Boolean) {
        val acc = accounts.firstOrNull { it.id == shard } ?: return
        if (!quiet) loading = true
        error = null
        val api = StudioHttpApi(context, acc)
        when (val res = api.traffic(range, daily)) {
            is StudioResult.Ok -> series = res.value
            is StudioResult.Err -> {
                error = messageFor(context, res.error)
                series = null
            }
        }
        top = (api.topUsers(range) as? StudioResult.Ok)?.value.orEmpty()
        if (canV2) {
            summary = api.analyticsSummary(range).valueOrNull
            breakdown = api.analyticsBreakdown(range).valueOrNull
            sessions = api.analyticsSessions(range, daily = range != "24h").valueOrNull
            newUsers = api.analyticsNewUsers(if (range == "24h") "7d" else range).valueOrNull
        } else {
            summary = null; breakdown = null; sessions = null; newUsers = null
        }
        loading = false
    }

    LaunchedEffect(shard, range, daily, canV2) { load(quiet = false) }

    // Before build 18 the per-person figures below were written only when a session ended, so on an
    // older engine they read low against people's own volume. Said, rather than left to be noticed.
    val usageLive = state.installations.firstOrNull { it.installationId == shard }?.can("usage.live") == true

    IosScreen(
        title = if (onBack != null) S(R.string.studio_analytics) else null,
        largeTitle = if (onBack == null) S(R.string.studio_tab_stats) else null,
        onBack = onBack,
        backLabel = if (onBack != null) S(R.string.studio_title) else null,
        onRefresh = { load(quiet = true) },
    ) {
        Spacer(Modifier.height(10.dp))

        // Only when there is more than one account, for the same reason as everywhere else: a
        // control that always shows the same value stops being read.
        if (accounts.size > 1) {
            SettingsSectionHeader(S(R.string.studio_accounts))
            SettingsGroup {
                accounts.forEachIndexed { index, acc ->
                    if (index > 0) com.mlmvpn.scanner.ui.configstudio.design.StudioSeparator()
                    com.mlmvpn.scanner.ui.configstudio.design.ChoiceRow(
                        title = acc.name.ifEmpty { acc.email },
                        selected = shard == acc.id,
                        onClick = { shard = acc.id },
                    )
                }
            }
            InfoCard(S(R.string.studio_analytics_scope))
            Spacer(Modifier.height(12.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("24h", "7d", "30d").forEach { r ->
                // Latin, because these are unit abbreviations rather than quantities — "۲۴h" reads
                // as neither one language nor the other.
                StudioChip(r, range == r, Modifier.weight(1f)) {
                    range = r
                    // An hourly series over thirty days is 720 points on a phone-width chart, so
                    // the granularity follows the range rather than being a second thing to set.
                    daily = r != "24h"
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        error?.let { ProblemCard(it); Spacer(Modifier.height(12.dp)) }

        when {
            loading -> WizardBusy(S(R.string.studio_loading))

            series == null || series!!.points.isEmpty() -> {
                // Empty is a fact about the range, not a failure — and the two read the same on a
                // blank chart, so it is said in words.
                InfoCard(S(R.string.studio_analytics_empty))
            }

            else -> {
                val s = series!!
                SettingsSectionHeader(
                    S(
                        when (range) {
                            "24h" -> R.string.studio_stats_traffic_24h
                            "30d" -> R.string.studio_stats_traffic_30d
                            else -> R.string.studio_stats_traffic_7d
                        }
                    )
                )
                TimeChart(
                    values = s.points.map { it.totalBytes.toFloat() },
                    stamps = s.points.map { it.ts },
                    daily = s.daily,
                    tint = Ios.Blue,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                // Under the chart, not inside a card of its own. A footer is the grey line iOS
                // puts BELOW content, and wrapping it in a group gave it a card whose text was
                // double-indented and pressed against the bottom edge.
                SettingsFooter(
                    S(R.string.studio_analytics_total).replace("%1\$s", bytesFa(s.totalBytes))
                )
            }
        }

        if (!loading) {
            summary?.let { SummarySection(it) }

            sessions?.takeIf { it.points.isNotEmpty() }?.let { c ->
                SettingsSectionHeader(S(R.string.studio_chart_sessions))
                TimeChart(
                    values = c.points.map { it.count.toFloat() },
                    stamps = c.points.map { it.ts },
                    daily = range != "24h",
                    tint = Ios.Teal,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                SettingsFooter(
                    S(R.string.studio_chart_sessions_note).replace("%1\$s", faNum(c.total))
                )
            }

            newUsers?.takeIf { it.points.isNotEmpty() }?.let { c ->
                SettingsSectionHeader(S(R.string.studio_chart_new_users))
                TimeChart(
                    values = c.points.map { it.count.toFloat() },
                    stamps = c.points.map { it.ts },
                    daily = true,
                    tint = Ios.Green,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                SettingsFooter(
                    S(R.string.studio_chart_new_users_note).replace("%1\$s", faNum(c.total))
                )
            }
        }

        if (top.isNotEmpty()) {
            SettingsSectionHeader(S(R.string.studio_analytics_top))
            SettingsGroup {
                top.forEachIndexed { index, u ->
                    if (index > 0) Separator()
                    SettingsRow(
                        title = u.username ?: S(R.string.studio_config_unnamed),
                        value = bytesFa(u.bytes),
                        subtitle = S(R.string.studio_analytics_sessions)
                            .replace("%1\$s", faNum(u.sessions)),
                        showChevron = false,
                    )
                }
            }
            InfoCard(S(R.string.studio_analytics_top_note))
            if (!usageLive) InfoCard(S(R.string.studio_stats_old_engine))
        }

        breakdown?.let { BreakdownSections(it) }

        if (!loading && !canV2 && accounts.isNotEmpty()) {
            InfoCard(S(R.string.studio_analytics_engine_old))
        }

        Spacer(Modifier.height(28.dp))
    }
}

// ---------------------------------------------------------------------------- summary

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.SummarySection(s: AnalyticsSummary) {
    SettingsSectionHeader(S(R.string.studio_analytics_summary))
    SettingsGroup {
        SettingsRow(
            title = S(R.string.studio_analytics_new_users),
            value = faNum(s.newUsers),
            showChevron = false,
        )
        Separator()
        SettingsRow(
            title = S(R.string.studio_analytics_active_users),
            value = faNum(s.activeUsers),
            showChevron = false,
        )
        Separator()
        SettingsRow(
            title = S(R.string.studio_analytics_connections),
            value = faNum(s.sessions),
            showChevron = false,
        )
        Separator()
        SettingsRow(
            title = S(R.string.studio_analytics_refusals),
            // The count and the share together. A rate on its own hides whether it is one refusal
            // out of four or four hundred out of sixteen hundred, and those are different days.
            value = faNum(s.refusals) + " · " + faNum((s.refusalRate * 100).toInt()) + "٪",
            // Red only when it is actually worth acting on. Every account refuses somebody
            // occasionally — an expired subscriber whose client keeps retrying is one row a day.
            titleColor = if (s.refusalRate >= 0.2f) Ios.Red else Ios.Label,
            showChevron = false,
        )
    }
    InfoCard(S(R.string.studio_analytics_refusal_note))
}

// ---------------------------------------------------------------------------- breakdown

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.BreakdownSections(b: UsageBreakdown) {
    if (b.configs.isNotEmpty()) {
        SettingsSectionHeader(S(R.string.studio_analytics_by_config))
        SliceGroup(b.configs) { it.label ?: S(R.string.studio_config_unnamed) }
    }
    if (b.protocols.isNotEmpty()) {
        SettingsSectionHeader(S(R.string.studio_analytics_by_protocol))
        SliceGroup(b.protocols) { protocolLabel(it.key) }
    }
    if (b.transports.isNotEmpty()) {
        SettingsSectionHeader(S(R.string.studio_analytics_by_transport))
        SliceGroup(b.transports) { transportLabel(it.key) }
    }
    // Said once, where the missing section would have been. "Not built yet" and "cannot be
    // measured" look identical as an absence, and only one of them is worth waiting for.
    if (!b.nodesMeasurable && (b.configs.isNotEmpty() || b.protocols.isNotEmpty())) {
        InfoCard(S(R.string.studio_analytics_no_nodes))
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.SliceGroup(
    rows: List<UsageSlice>,
    title: (UsageSlice) -> String,
) {
    SettingsGroup {
        rows.forEachIndexed { index, slice ->
            if (index > 0) Separator()
            SettingsRow(
                title = title(slice),
                value = bytesFa(slice.bytes),
                subtitle = listOfNotNull(
                    slice.username,
                    S(R.string.studio_analytics_sessions).replace("%1\$s", faNum(slice.sessions)),
                ).joinToString(" · "),
                showChevron = false,
            )
        }
    }
}

/**
 * One letter to a name.
 *
 * The engine stores a single letter rather than the protocol's name, and not for brevity: a
 * plaintext protocol name in the worker's own code is what Cloudflare's deploy-time scanner objects
 * to, and a rejected upload breaks redeploy for every installation that already exists (R2). The
 * name is not translated and is not in the catalogue for the same reason it is not in the worker —
 * it is a wire identifier that clients spell exactly one way.
 */
private fun protocolLabel(key: String): String =
    ProtocolType.entries.firstOrNull { it.wire.startsWith(key.lowercase()) }
        ?.wire?.uppercase() ?: key

private fun transportLabel(key: String): String =
    TransportType.from(key)?.wire?.uppercase() ?: key

// ---------------------------------------------------------------------------- the chart

/**
 * One series of non-negative numbers, as bars, with the ends of the axis labelled.
 *
 * A `Canvas` rather than a charting library: the whole drawing is a loop over rectangles, and
 * pulling in a dependency for that would add it to every build of the app to save fifteen lines.
 *
 * Drawn **left to right in time regardless of layout direction**. An RTL container would otherwise
 * mirror the axis, and a time series that runs right-to-left is not a translation of one that runs
 * left-to-right — it is a different claim about the same data. The two labels underneath follow the
 * same rule and are placed by position rather than by `start`/`end`.
 *
 * The labels are the first and last bucket and nothing in between: a phone-width chart with thirty
 * bars has no room for thirty dates, and a chart whose axis says only "some time" is a chart nobody
 * can act on.
 */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.TimeChart(
    values: List<Float>,
    stamps: List<Long>,
    daily: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    if (values.isEmpty()) return
    val peak = values.maxOrNull()?.coerceAtLeast(1f) ?: 1f
    val ground = Ios.Separator

    Canvas(modifier = modifier.fillMaxWidth().height(120.dp)) {
        val n = values.size
        val slot = size.width / n
        val width = (slot * 0.7f).coerceAtLeast(1f)

        drawRect(color = ground, topLeft = Offset(0f, size.height - 1f), size = Size(size.width, 1f))

        values.forEachIndexed { i, v ->
            val h = (v / peak) * (size.height - 2f)
            drawRect(
                color = tint,
                topLeft = Offset(i * slot + (slot - width) / 2f, size.height - h - 1f),
                size = Size(width, h.coerceAtLeast(0f)),
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(
            chartStamp(stamps.first(), daily),
            color = Ios.SecondaryLabel,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Start,
        )
        Text(
            chartStamp(stamps.last(), daily),
            color = Ios.SecondaryLabel,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )
    }
}

/** A bucket's own label: the hour when the chart is hourly, the date when it is not. */
private fun chartStamp(ts: Long, daily: Boolean): String {
    if (ts <= 0) return ""
    val date = java.util.Date(ts)
    val fmt = java.text.SimpleDateFormat(
        if (daily) "MM/dd" else "HH:mm",
        java.util.Locale.US,
    )
    return faDigits(fmt.format(date))
}

/** Latin digits to Persian, so a chart axis does not mix numerals with the text around it. */
private fun faDigits(s: String): String =
    s.map { c -> if (c in '0'..'9') "۰۱۲۳۴۵۶۷۸۹"[c - '0'] else c }.joinToString("")
