package com.mlmvpn.scanner.ui.configstudio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.PanelBuild
import com.mlmvpn.scanner.data.studio.StudioState
import com.mlmvpn.scanner.data.studio.StudioStore
import com.mlmvpn.scanner.data.studio.domain.NodeHealth
import com.mlmvpn.scanner.data.studio.domain.StudioNode
import com.mlmvpn.scanner.ui.configstudio.design.EmptyState
import com.mlmvpn.scanner.ui.configstudio.design.StudioIcons
import com.mlmvpn.scanner.ui.configstudio.parts.InfoCard
import com.mlmvpn.scanner.ui.configstudio.parts.PayloadText
import com.mlmvpn.scanner.ui.configstudio.parts.ProblemCard
import com.mlmvpn.scanner.ui.configstudio.parts.StatusDot
import com.mlmvpn.scanner.ui.configstudio.parts.StudioListRow
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosRefreshIndicator
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.settings.rememberIosRefreshState
import com.mlmvpn.scanner.utils.S
import kotlinx.coroutines.launch

/**
 * «نقطه‌های اتصال» — the addresses a subscription is actually served on.
 *
 * A node is not a shard, and the two words are kept apart everywhere in this feature: a shard is a
 * Cloudflare account, a node is a clean IP with a port list. One account carries many, and the same
 * address written to three accounts is **three rows here**, not one. Deduplicating them would read
 * tidier and would cost the operator the only thing this screen is for — when an address stops
 * answering, which account's worker is the one serving it.
 *
 * The test button is the reason this screen is worth having rather than a settings page. It opens a
 * TCP connection **from this phone, on this network**, and posts the result back to that node's
 * installation. A worker cannot do this: it would be timing Cloudflare's own path to the address,
 * which is not the question. The question is whether an address answers from Iran, and the only
 * machine in the system standing there is the one in the operator's hand.
 */
@Composable
fun StudioNodesScreen(
    store: StudioStore,
    state: StudioState,
    onNewNode: () -> Unit,
    onOpenNode: (StudioNode) -> Unit,
    onOpenGroups: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        store.refreshNodes()
        store.refreshNodeGroups()
        // The honest form of an automatic health check, and its limit is worth knowing: a
        // Cloudflare Worker has no scheduler here, and the probe has to run from the operator's own
        // network anyway — the question is whether an address answers *from Iran*. So the only
        // machine that can do it is this one, and the only time is while the app is open. The store
        // throttles it, so opening this screen four times in a minute is one pass, not four.
        val probed = store.autoProbeNodes()
        if (probed > 0) note = context.getString(R.string.studio_node_auto_probed)
            .replace("%1\$s", faNum(probed))
    }

    // Probing is a build-7 endpoint. Offering the button against an older engine would spend four
    // seconds per node measuring something nothing will record.
    val canProbe = state.installations.isNotEmpty() &&
        state.installations.all { it.can("nodes.health") }

    // Pull down: re-read every account's endpoints, and test from this phone the ones that are due
    // (the store throttles it, so pulling twice in a minute is one pass).
    val refresh = rememberIosRefreshState {
        store.refreshNodes()
        store.refreshNodeGroups()
        val probed = store.autoProbeNodes()
        if (probed > 0) note = context.getString(R.string.studio_node_auto_probed)
            .replace("%1\$s", faNum(probed))
    }

    // scrollable = false: the body holds a LazyColumn, and a LazyColumn measured inside a
    // vertically scrolling Column gets infinite height and crashes.
    IosScreen(
        title = S(R.string.studio_nodes),
        onBack = if (busy) null else onBack,
        // Reached from «بیشتر» and from «تنظیمات», so the chevron alone rather than a wrong name.
        backLabel = null,
        scrollable = false,
        trailing = {
            Text(
                S(R.string.studio_node_new),
                color = Ios.Blue,
                fontSize = 16.sp,
                modifier = Modifier
                    .clickable(enabled = !busy, onClick = onNewNode)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            )
        },
    ) {
        Spacer(Modifier.height(10.dp))
        note?.let { InfoCard(it) }

        // The accounts that did not answer are named, and their nodes are simply absent rather than
        // reported as zero — the same rule the user list follows.
        if (state.nodesUnreachable.isNotEmpty()) {
            ProblemCard(
                S(R.string.studio_node_unreachable_n)
                    .replace("%1\$s", state.nodesUnreachable.joinToString("، "))
            )
        }

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f).nestedScroll(refresh.connection)) {
            item { IosRefreshIndicator(refresh) }
            if (state.nodes.isEmpty()) {
                item {
                    EmptyState(
                        icon = StudioIcons.Endpoints,
                        title = S(R.string.studio_node_none_title),
                        body = S(R.string.studio_node_none_body),
                        actionLabel = S(R.string.studio_node_new),
                        onAction = onNewNode,
                    )
                }
                return@LazyColumn
            }
            item {
                InfoCard(S(R.string.studio_nodes_sub))
                Spacer(Modifier.height(10.dp))
            }

            // Grouped, and in the order the fan-out itself uses: group priority, then whatever the
            // group's strategy decides, then the endpoint's own priority. Ungrouped endpoints go
            // last under their own heading, because that is where the engine puts them.
            val grouped = state.nodes.groupBy { node ->
                state.nodeGroups.firstOrNull {
                    it.id == node.groupId && it.installationId == node.installationId
                }
            }
            val ordered = grouped.entries.sortedWith(
                compareBy({ it.key == null }, { it.key?.priority ?: 100 }, { it.key?.name ?: "" })
            )

            for ((group, nodes) in ordered) {
                item(key = "h:" + (group?.installationId.orEmpty()) + ":" + (group?.id ?: "-")) {
                    Spacer(Modifier.height(6.dp))
                    SettingsSectionHeader(
                        (group?.name ?: S(R.string.studio_node_group_ungrouped)) +
                            (group?.let { " · " + strategyLabel(it.strategy) } ?: "")
                    )
                    // A group that is switched off keeps its endpoints on screen and says so.
                    // Hiding them would make an address the operator switched off look deleted.
                    if (group != null && !group.enabled) {
                        InfoCard(S(R.string.studio_node_group_off_note), underHeader = true)
                        Spacer(Modifier.height(6.dp))
                    }
                }
                items(nodes, key = { it.installationId + ":" + it.id }) { node ->
                    NodeRow(
                        node = node,
                        // The account's own name on the row, but only when there is more than one to
                        // tell apart — a label that is identical on every row is noise on every row.
                        shard = if (state.installations.size > 1) state.fleetLabels[node.installationId] else null,
                    ) { onOpenNode(node) }
                }
            }

            item {
                Spacer(Modifier.height(14.dp))
                SettingsGroup {
                    SettingsRow(
                        title = S(R.string.studio_node_groups),
                        icon = StudioIcons.EndpointGroups,
                        tint = Ios.Purple,
                        value = state.nodeGroups.size.takeIf { it > 0 }?.let { faNum(it) },
                        onClick = onOpenGroups,
                    )
                }
            }

            item {
                Spacer(Modifier.height(14.dp))
                SettingsGroup {
                    SettingsActionRow(
                        label = S(R.string.studio_node_new),
                        icon = StudioIcons.Add,
                        tint = Ios.Blue,
                        enabled = !busy,
                        onClick = onNewNode,
                    )
                    if (canProbe) {
                        Separator()
                        SettingsActionRow(
                            label = if (busy) S(R.string.studio_node_testing)
                            else S(R.string.studio_node_test_all),
                            icon = StudioIcons.Test,
                            tint = Ios.Green,
                            busy = busy,
                            enabled = !busy,
                        ) {
                            busy = true; note = null
                            scope.launch {
                                val total = state.nodes.size
                                val up = store.probeAllNodes()
                                note = context.getString(R.string.studio_node_tested_n)
                                    .replace("%1\$s", faDigitsOfInt(up))
                                    .replace("%2\$s", faDigitsOfInt(total))
                                busy = false
                            }
                        }
                    }
                }
                if (canProbe) InfoCard(S(R.string.studio_node_probe_note))
                else InfoCard(S(R.string.studio_node_engine_old))
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

/**
 * One address, and the three things worth knowing about it at a glance.
 *
 * The lamp is grey for a node nobody has tested, and that is a third state rather than a shade of
 * "bad": an untested address is not a broken one, and colouring it red would send the operator to
 * replace something that works.
 */
@Composable
private fun NodeRow(node: StudioNode, shard: String?, onClick: () -> Unit) {
    StudioListRow(onClick = onClick) {
        StatusDot(
            when {
                !node.enabled -> Ios.SecondaryLabel
                node.health == NodeHealth.UP -> Ios.Green
                node.health == NodeHealth.DOWN -> Ios.Red
                else -> Ios.SecondaryLabel
            }
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    node.name,
                    color = if (node.enabled) Ios.Label else Ios.SecondaryLabel,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                // The measured figure, when there is one. A node that failed shows why instead, on
                // the line below, because a latency of nothing is not a number worth a slot here.
                node.latencyMs?.takeIf { node.health == NodeHealth.UP }?.let {
                    Text(
                        S(R.string.studio_node_latency).replace("%1\$s", faNum(it)),
                        color = Ios.Green,
                        fontSize = 13.sp,
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            // The address itself is an ASCII payload and is LTR-pinned like every other one: an RTL
            // container reorders a dotted quad on screen, and this is the value an operator reads
            // back over a phone call.
            PayloadText(node.host + ":" + node.ports.joinToString(","))
            Spacer(Modifier.height(3.dp))
            Text(
                listOfNotNull(shard, node.country, healthLabel(node)).joinToString(" · "),
                color = if (node.isDown) Ios.Red else Ios.SecondaryLabel,
                fontSize = 12.sp,
            )
        }
    }
}

/**
 * What the last probe found, in one phrase.
 *
 * The failure's own words are shown rather than a translated summary, because "refused",
 * "unreachable" and "timed out" need different actions and a single «خطا» hides which it was.
 */
@Composable
private fun healthLabel(node: StudioNode): String = when {
    node.health == NodeHealth.DOWN -> node.lastError ?: S(R.string.studio_node_down)
    node.health == NodeHealth.UP -> node.healthCheckedAt?.let {
        S(R.string.studio_node_checked).replace("%1\$s", agoText(it))
    } ?: S(R.string.studio_node_up)
    else -> S(R.string.studio_node_untested)
}

@Composable
private fun agoText(ts: Long): String {
    val minutes = ((System.currentTimeMillis() - ts) / 60000).coerceAtLeast(0)
    return when {
        minutes < 5 -> S(R.string.studio_device_now)
        minutes < 60 -> S(R.string.studio_sync_minutes).replace("%1\$s", faNum(minutes))
        else -> S(R.string.studio_sync_hours).replace("%1\$s", faNum(minutes / 60))
    }
}
