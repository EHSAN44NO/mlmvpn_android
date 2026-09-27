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
import com.mlmvpn.scanner.data.studio.domain.NodeGroup
import com.mlmvpn.scanner.data.studio.domain.NodeStrategy
import com.mlmvpn.scanner.ui.configstudio.design.ChoiceRow
import com.mlmvpn.scanner.ui.configstudio.design.StudioIcons
import com.mlmvpn.scanner.ui.configstudio.design.StudioSeparator
import com.mlmvpn.scanner.ui.configstudio.parts.FactsCard
import com.mlmvpn.scanner.ui.configstudio.parts.InfoCard
import com.mlmvpn.scanner.ui.configstudio.parts.PageIntro
import com.mlmvpn.scanner.ui.configstudio.parts.ProblemCard
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
 * One «گروه نقطه»: the endpoints in it, and the order they are handed out in.
 *
 * The strategy rows carry their own explanation because three of the four are easy to read as
 * something stronger than they are. What every one of them changes is the **order of the list a
 * subscription hands over** — the client is what picks and what falls back, and no engine in this
 * design proxies anything.
 */
@Composable
fun StudioNodeGroupEditScreen(
    store: StudioStore,
    state: StudioState,
    existing: NodeGroup?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val id = remember(existing) { existing?.id ?: StudioStore.newNodeId() }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var strategy by remember { mutableStateOf(existing?.strategy ?: NodeStrategy.ORDER) }
    var priority by remember { mutableStateOf((existing?.priority ?: 100).toString()) }
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val members = remember(state.nodes, existing) {
        existing?.let { g ->
            state.nodes.filter { it.groupId == g.id && it.installationId == g.installationId }
        }.orEmpty()
    }

    fun save() {
        val label = name.trim()
        if (label.isEmpty()) { error = context.getString(R.string.studio_node_group_needs_name); return }
        busy = true; error = null
        scope.launch {
            val res = store.saveNodeGroup(
                NodeGroup(
                    id = id,
                    name = label,
                    strategy = strategy,
                    priority = priority.trim().toIntOrNull() ?: 100,
                    enabled = enabled,
                    installationId = existing?.installationId.orEmpty(),
                ),
                // A NEW group goes everywhere; an edit addresses the account that holds it. Same
                // split as an endpoint, and for the same reason: an edit written fleet-wide would
                // create copies on accounts that never had the row.
                toWholeFleet = existing == null,
            )
            busy = false
            if (res.failed.isEmpty()) onSaved()
            else error = context.getString(R.string.studio_account_update_failed)
                .replace("%1\$s", res.failed.joinToString("، ") { it.first })
        }
    }

    IosScreen(
        title = if (existing == null) S(R.string.studio_node_group_new) else S(R.string.studio_node_group_edit),
        onBack = if (busy) null else onBack,
        backLabel = S(R.string.studio_node_groups),
    ) {
        Spacer(Modifier.height(10.dp))
        PageIntro(S(R.string.studio_node_group_edit_sub))

        error?.let { ProblemCard(it); Spacer(Modifier.height(12.dp)) }

        SettingsSectionHeader(S(R.string.studio_node_group_name))
        WizardField(name, { name = it; error = null }, S(R.string.studio_node_group_name_hint), ltr = false)

        // ---- the order --------------------------------------------------------------------
        SettingsSectionHeader(S(R.string.studio_node_group_strategy))
        SettingsGroup {
            NodeStrategy.entries.forEachIndexed { index, option ->
                if (index > 0) StudioSeparator()
                ChoiceRow(
                    title = strategyLabel(option),
                    // On the row, not in a footnote. Three of the four are easy to read as
                    // something stronger than they are, and the correction has to be where the
                    // choice is made.
                    subtitle = strategyNote(option),
                    selected = strategy == option,
                    onClick = { strategy = option },
                )
            }
        }
        InfoCard(S(R.string.studio_node_group_truth))

        SettingsSectionHeader(S(R.string.studio_node_priority))
        WizardField(priority, { priority = it }, "100")
        InfoCard(S(R.string.studio_node_group_priority_note))

        // ---- on or off --------------------------------------------------------------------
        Spacer(Modifier.height(12.dp))
        SettingsGroup {
            SettingsToggle(
                title = S(R.string.studio_node_group_enabled),
                subtitle = S(R.string.studio_node_group_enabled_sub),
                checked = enabled,
                onCheckedChange = { enabled = it },
            )
        }

        // ---- what is in it ----------------------------------------------------------------
        //
        // Read-only here. An endpoint is put into a group on the endpoint's own page, because that
        // is where its address, ports and priority are — and a second place to set the same field
        // is a second place for the two to disagree.
        if (existing != null) {
            SettingsSectionHeader(S(R.string.studio_node_group_members_title))
            if (members.isEmpty()) {
                InfoCard(S(R.string.studio_node_group_empty), underHeader = true)
            } else {
                SettingsGroup {
                    members.forEachIndexed { index, node ->
                        if (index > 0) Separator()
                        SettingsRow(
                            title = node.name.ifBlank { node.host },
                            value = node.latencyMs?.let {
                                S(R.string.studio_node_latency).replace("%1\$s", faNum(it))
                            },
                            subtitle = listOfNotNull(node.country, node.city)
                                .joinToString(" · ").ifBlank { null },
                            showChevron = false,
                        )
                    }
                }
                InfoCard(S(R.string.studio_node_group_members_note))
            }
        }

        FactsCard(
            S(R.string.studio_node_group_summary)
                .replace("%1\$s", strategyLabel(strategy))
                .replace("%2\$s", faNum(members.size))
        )

        Spacer(Modifier.height(14.dp))
        WizardPrimary(S(R.string.studio_save), busy = busy, enabled = name.isNotBlank()) { save() }

        if (existing != null) {
            SettingsSectionHeader(S(R.string.studio_section_danger))
            SettingsGroup {
                SettingsActionRow(
                    label = S(R.string.studio_delete),
                    icon = StudioIcons.Delete,
                    tint = Ios.Red,
                    enabled = !busy,
                ) { confirmDelete = true }
            }
            InfoCard(S(R.string.studio_node_group_delete_note))
        }

        Spacer(Modifier.height(28.dp))
    }

    if (confirmDelete && existing != null) {
        IosAlert(
            title = S(R.string.studio_node_group_delete_title),
            message = S(R.string.studio_node_group_delete_body),
            actions = listOf(
                IosAlertAction(S(R.string.studio_cancel), { confirmDelete = false }),
                IosAlertAction(S(R.string.studio_delete), {
                    confirmDelete = false
                    busy = true; error = null
                    scope.launch {
                        val res = store.deleteNodeGroup(existing)
                        busy = false
                        if (res is com.mlmvpn.scanner.data.studio.api.StudioResult.Ok) onSaved()
                        else error = context.getString(R.string.studio_err_generic)
                    }
                }, destructive = true),
            ),
            onDismiss = { confirmDelete = false },
        )
    }
}
