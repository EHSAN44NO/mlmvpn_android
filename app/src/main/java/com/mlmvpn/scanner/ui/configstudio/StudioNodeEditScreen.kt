package com.mlmvpn.scanner.ui.configstudio

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.studio.StudioState
import com.mlmvpn.scanner.data.studio.StudioStore
import com.mlmvpn.scanner.data.studio.api.StudioResult
import com.mlmvpn.scanner.data.studio.domain.NodeHealth
import com.mlmvpn.scanner.data.studio.domain.NodeHistory
import com.mlmvpn.scanner.data.studio.domain.StudioNode
import com.mlmvpn.scanner.ui.configstudio.design.ChoiceRow
import com.mlmvpn.scanner.ui.configstudio.design.StudioIcons
import com.mlmvpn.scanner.ui.configstudio.design.StudioSeparator
import com.mlmvpn.scanner.ui.configstudio.parts.FactsCard
import com.mlmvpn.scanner.ui.configstudio.parts.InfoCard
import com.mlmvpn.scanner.ui.configstudio.parts.PageIntro
import com.mlmvpn.scanner.ui.configstudio.parts.ProblemCard
import com.mlmvpn.scanner.ui.configstudio.parts.WizardBusy
import com.mlmvpn.scanner.ui.configstudio.parts.WizardField
import com.mlmvpn.scanner.ui.configstudio.parts.WizardPrimary
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosAlert
import com.mlmvpn.scanner.ui.settings.IosAlertAction
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.settings.SettingsToggle
import com.mlmvpn.scanner.utils.S
import kotlinx.coroutines.launch

/**
 * One endpoint's terms.
 *
 * The one decision on this page that is not a text field is **where it gets written**. A brand-new
 * node has no account yet, and the useful default is the one an operator means by "add this clean
 * IP": every connected account, so a subscriber lands on it wherever they were placed. An existing
 * node is addressed at the account that holds it, because that is the worker serving it — and the
 * toggle disappears, because "write this edit to the whole fleet" would create copies on accounts
 * that never had it.
 *
 * Ports are typed as a list and parsed here rather than validated on the far side. The engine takes
 * whatever survives its own filter and silently falls back to 443, so a typo would produce a node
 * that looks right on screen and serves a port nobody asked for.
 */
@Composable
fun StudioNodeEditScreen(
    store: StudioStore,
    state: StudioState,
    existing: StudioNode?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var host by remember { mutableStateOf(existing?.host.orEmpty()) }
    var ports by remember { mutableStateOf(existing?.ports?.joinToString(", ") ?: "443") }
    var country by remember { mutableStateOf(existing?.country.orEmpty()) }
    var city by remember { mutableStateOf(existing?.city.orEmpty()) }
    var groupId by remember { mutableStateOf(existing?.groupId) }
    var sni by remember { mutableStateOf(existing?.sni.orEmpty()) }
    var hostHeader by remember { mutableStateOf(existing?.hostHeader.orEmpty()) }
    var priority by remember { mutableStateOf((existing?.priority ?: 100).toString()) }
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }
    var wholeFleet by remember { mutableStateOf(existing == null) }

    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var probed by remember { mutableStateOf(existing) }

    /** The last fifty probes, once asked for. Null until then, and on an engine too old to keep them. */
    var history by remember { mutableStateOf<NodeHistory?>(null) }
    var loadingHistory by remember { mutableStateOf(false) }

    val install = existing?.let { e ->
        state.installations.firstOrNull { it.installationId == e.installationId }
    }
    val canProbe = existing != null && install?.can("nodes.health") == true
    val canGroup = install?.can("node.groups") != false
    val canHistory = existing != null && install?.can("node.history") == true

    // Only the groups on THIS endpoint's account. A group orders the endpoints of the installation
    // it lives on, so offering another account's groups would set an id that resolves to nothing.
    val groups = remember(state.nodeGroups, existing) {
        state.nodeGroups.filter { existing == null || it.installationId == existing.installationId }
    }

    fun parsedPorts(): List<Int>? {
        val parts = ports.split(',', '،', ' ', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return listOf(443)
        val nums = parts.map { it.toIntOrNull() }
        // All or nothing. Dropping the bad one and saving the rest is how a node quietly ends up
        // serving two ports when the operator typed three.
        if (nums.any { it == null || it !in 1..65535 }) return null
        return nums.filterNotNull()
    }

    fun save() {
        val trimmedHost = host.trim()
        if (trimmedHost.isEmpty()) {
            error = context.getString(R.string.studio_node_needs_host)
            return
        }
        val portList = parsedPorts()
        if (portList == null) {
            error = context.getString(R.string.studio_node_bad_ports)
            return
        }

        busy = true; error = null; note = null
        val node = StudioNode(
            id = existing?.id ?: StudioStore.newNodeId(),
            name = name.trim().ifEmpty { trimmedHost },
            host = trimmedHost,
            ports = portList,
            country = country.trim().takeIf { it.isNotEmpty() },
            city = city.trim().takeIf { it.isNotEmpty() },
            groupId = groupId,
            sni = sni.trim().takeIf { it.isNotEmpty() },
            hostHeader = hostHeader.trim().takeIf { it.isNotEmpty() },
            priority = priority.trim().toIntOrNull() ?: 100,
            enabled = enabled,
            installationId = existing?.installationId.orEmpty(),
        )
        scope.launch {
            val res = store.saveNode(node, toWholeFleet = existing == null && wholeFleet)
            busy = false
            if (res.ok) onSaved()
            else error = context.getString(R.string.studio_account_update_failed)
                .replace("%1\$s", res.failed.joinToString("، ") { it.first })
        }
    }

    IosScreen(
        title = if (existing == null) S(R.string.studio_node_new) else S(R.string.studio_node_edit),
        onBack = if (busy) null else onBack,
        backLabel = S(R.string.studio_nodes),
    ) {
        Spacer(Modifier.height(10.dp))
        PageIntro(S(R.string.studio_nodes_sub))

        note?.let { InfoCard(it) }

        SettingsSectionHeader(S(R.string.studio_node_name))
        WizardField(name, { name = it }, S(R.string.studio_node_name_hint), ltr = false)

        SettingsSectionHeader(S(R.string.studio_node_host))
        WizardField(host, { host = it; error = null }, S(R.string.studio_node_host_hint), monospace = true)

        SettingsSectionHeader(S(R.string.studio_node_ports))
        WizardField(ports, { ports = it; error = null }, S(R.string.studio_node_ports_hint), monospace = true)

        SettingsSectionHeader(S(R.string.studio_node_country))
        WizardField(country, { country = it }, S(R.string.studio_node_country_hint))

        SettingsSectionHeader(S(R.string.studio_node_city))
        WizardField(city, { city = it }, S(R.string.studio_node_city_hint), ltr = false)
        InfoCard(S(R.string.studio_node_city_note))

        // ---- which group ----------------------------------------------------------------
        //
        // Set here rather than on the group's own page, because this is where the endpoint's
        // address, ports and priority already are — and a second place to set the same field is a
        // second place for the two to disagree.
        if (canGroup) {
            SettingsSectionHeader(S(R.string.studio_node_group))
            if (groups.isEmpty()) {
                InfoCard(S(R.string.studio_node_group_none), underHeader = true)
            } else {
                SettingsGroup {
                    ChoiceRow(
                        title = S(R.string.studio_node_group_ungrouped),
                        selected = groupId == null,
                        onClick = { groupId = null },
                    )
                    groups.forEach { group ->
                        StudioSeparator()
                        ChoiceRow(
                            title = group.name,
                            subtitle = strategyLabel(group.strategy),
                            selected = groupId == group.id,
                            onClick = { groupId = group.id },
                        )
                    }
                }
                InfoCard(S(R.string.studio_node_group_pick_note))
            }
        }

        SettingsSectionHeader(S(R.string.studio_node_sni))
        WizardField(sni, { sni = it }, S(R.string.studio_node_sni_hint), monospace = true)

        SettingsSectionHeader(S(R.string.studio_node_host_header))
        WizardField(hostHeader, { hostHeader = it }, S(R.string.studio_node_host_header_hint), monospace = true)

        SettingsSectionHeader(S(R.string.studio_node_priority))
        WizardField(priority, { priority = it }, S(R.string.studio_node_priority_hint))
        InfoCard(S(R.string.studio_node_priority_note))

        Spacer(Modifier.height(14.dp))
        SettingsGroup {
            SettingsToggle(
                title = S(R.string.studio_node_enabled),
                checked = enabled,
                subtitle = S(R.string.studio_node_enabled_sub),
                onCheckedChange = { enabled = it },
            )
            // Only for a node that has no account yet. Offering it on an edit would write copies of
            // this row onto accounts that never carried it.
            if (existing == null) {
                Separator()
                SettingsToggle(
                    title = S(R.string.studio_node_whole_fleet),
                    checked = wholeFleet,
                    subtitle = S(R.string.studio_node_whole_fleet_sub),
                    onCheckedChange = { wholeFleet = it },
                )
            }
        }

        // ---- what the last probe found, and one to run now ------------------------------
        if (existing != null) {
            SettingsSectionHeader(S(R.string.studio_node_test))
            val current = probed ?: existing
            SettingsGroup {
                SettingsRow(
                    title = when (current.health) {
                        NodeHealth.UP -> S(R.string.studio_node_up)
                        NodeHealth.DOWN -> S(R.string.studio_node_down)
                        NodeHealth.UNKNOWN -> S(R.string.studio_node_untested)
                    },
                    icon = when (current.health) {
                        NodeHealth.UP -> StudioIcons.Ok
                        NodeHealth.DOWN -> StudioIcons.Error
                        NodeHealth.UNKNOWN -> StudioIcons.Info
                    },
                    tint = when (current.health) {
                        NodeHealth.UP -> Ios.Green
                        NodeHealth.DOWN -> Ios.Red
                        NodeHealth.UNKNOWN -> Ios.Gray
                    },
                    value = current.latencyMs?.takeIf { current.health == NodeHealth.UP }
                        ?.let { S(R.string.studio_node_latency).replace("%1\$s", faNum(it)) },
                    // The failure's own words. "refused", "unreachable" and "timed out" call for
                    // three different actions, and one translated «خطا» hides which it was.
                    subtitle = current.lastError,
                    showChevron = false,
                )
                if (canProbe) {
                    Separator()
                    SettingsActionRow(
                        label = if (busy) S(R.string.studio_node_testing) else S(R.string.studio_node_test),
                        icon = StudioIcons.Test,
                        tint = Ios.Green,
                        busy = busy,
                        enabled = !busy,
                    ) {
                        busy = true; error = null; note = null
                        scope.launch {
                            // The row on screen, not the one this screen opened with: an operator
                            // who typed a new address expects the test to use it.
                            val target = existing.copy(
                                host = host.trim().ifEmpty { existing.host },
                                ports = parsedPorts() ?: existing.ports,
                            )
                            when (val res = store.probeNode(target)) {
                                is StudioResult.Ok -> probed = res.value
                                is StudioResult.Err -> error = messageFor(context, res.error)
                            }
                            busy = false
                        }
                    }
                }
            }
            InfoCard(
                if (canProbe) S(R.string.studio_node_probe_note)
                else S(R.string.studio_node_engine_old)
            )
        }

        error?.let { ProblemCard(it) }

        Spacer(Modifier.height(16.dp))
        WizardPrimary(S(R.string.studio_save), busy = busy) { save() }

        if (existing != null) {
            // ---- what the probes have found -------------------------------------------------
            //
            // The nodes table only ever held the LAST result, so "is this address flaky or did it fail
            // once" was a question nothing could answer. Fifty samples is what it takes to see a
            // pattern; the count is printed beside the percentage because nobody probes on a schedule
            // the engine controls, and five samples across a week is also a percentage.
            if (canHistory) {
                SettingsSectionHeader(S(R.string.studio_node_history))
                val h = history
                when {
                    loadingHistory -> WizardBusy(S(R.string.studio_loading))

                    h == null -> SettingsGroup {
                        SettingsActionRow(
                            label = S(R.string.studio_node_history_load),
                            icon = StudioIcons.ProbeHistory,
                            tint = Ios.Blue,
                            enabled = !busy,
                        ) {
                            loadingHistory = true
                            scope.launch {
                                history = store.nodeHistory(existing)
                                loadingHistory = false
                            }
                        }
                    }

                    h.samples == 0 -> InfoCard(S(R.string.studio_node_history_none), underHeader = true)

                    else -> {
                        FactsCard(
                            S(R.string.studio_node_uptime)
                                .replace("%1\$s", faNum(((h.uptime ?: 0f) * 100).toInt()))
                                .replace("%2\$s", faNum(h.samples)) +
                                (h.avgLatencyMs?.let {
                                    "\n" + S(R.string.studio_node_avg_latency).replace("%1\$s", faNum(it))
                                } ?: "")
                        )
                        Spacer(Modifier.height(10.dp))
                        SettingsGroup {
                            h.items.take(12).forEachIndexed { index, probe ->
                                if (index > 0) Separator()
                                SettingsRow(
                                    title = if (probe.up) S(R.string.studio_node_up) else S(R.string.studio_node_down),
                                    icon = if (probe.up) StudioIcons.Ok else StudioIcons.Error,
                                    tint = if (probe.up) Ios.Green else Ios.Red,
                                    // The failure keeps its own words: "refused", "unreachable" and
                                    // "timed out" call for three different actions.
                                    value = if (probe.up) probe.latencyMs?.let {
                                        S(R.string.studio_node_latency).replace("%1\$s", faNum(it))
                                    } else probe.error?.take(30),
                                    subtitle = probeAge(probe.ts),
                                    showChevron = false,
                                )
                            }
                        }
                        InfoCard(S(R.string.studio_node_history_note))
                    }
                }
            }

            SettingsSectionHeader(S(R.string.studio_section_danger))
            SettingsGroup {
                SettingsActionRow(
                    label = S(R.string.studio_node_delete),
                    icon = StudioIcons.Delete,
                    tint = Ios.Red,
                    enabled = !busy,
                ) { confirmDelete = true }
            }
        }

        Spacer(Modifier.height(28.dp))
    }

    if (confirmDelete && existing != null) {
        IosAlert(
            title = S(R.string.studio_node_delete_title),
            message = S(R.string.studio_node_delete_body),
            actions = listOf(
                IosAlertAction(S(R.string.studio_cancel), { confirmDelete = false }),
                IosAlertAction(S(R.string.studio_delete), {
                    confirmDelete = false
                    busy = true; error = null
                    scope.launch {
                        when (val res = store.deleteNode(existing)) {
                            is StudioResult.Ok -> onSaved()
                            is StudioResult.Err -> { error = messageFor(context, res.error); busy = false }
                        }
                    }
                }, destructive = true),
            ),
            onDismiss = { confirmDelete = false },
        )
    }
}

/**
 * How long ago a probe ran, in the app's own digits.
 *
 * Relative rather than a timestamp: the question in front of a history list is "is this recent",
 * and a date makes the reader do the subtraction.
 */
@Composable
private fun probeAge(ts: Long): String {
    val minutes = ((System.currentTimeMillis() - ts) / 60000).coerceAtLeast(0)
    return when {
        ts <= 0 -> S(R.string.studio_device_unknown)
        minutes < 5 -> S(R.string.studio_device_now)
        minutes < 60 -> S(R.string.studio_sync_minutes).replace("%1\$s", faNum(minutes))
        minutes < 60 * 24 -> S(R.string.studio_sync_hours).replace("%1\$s", faNum(minutes / 60))
        else -> S(R.string.studio_days_left).replace("%1\$s", faNum(minutes / (60 * 24)))
    }
}
