package com.mlmvpn.scanner.ui.configstudio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.studio.StudioState
import com.mlmvpn.scanner.data.studio.StudioStore
import com.mlmvpn.scanner.data.studio.domain.NodeGroup
import com.mlmvpn.scanner.data.studio.domain.NodeStrategy
import com.mlmvpn.scanner.ui.configstudio.design.EmptyState
import com.mlmvpn.scanner.ui.configstudio.design.StudioIcons
import com.mlmvpn.scanner.ui.configstudio.parts.InfoCard
import com.mlmvpn.scanner.ui.configstudio.parts.PageIntro
import com.mlmvpn.scanner.ui.configstudio.parts.ProblemCard
import com.mlmvpn.scanner.ui.configstudio.parts.WizardBusy
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.utils.S

/**
 * «گروه‌های نقطه» — sets of endpoints, each with one rule for the order they are handed out in.
 *
 * The narrowness of that sentence is the feature. It is easy to read a group as load balancing or
 * as routing, and it is neither: every endpoint is a different clean IP reaching the **same**
 * worker, so there is no traffic to steer and no per-node load to move. A subscription is a list of
 * links; the client on the far side picks one and falls back when it cannot connect. **The order of
 * that list is the whole of what this controls**, and the screen says so rather than letting the
 * word "failover" imply something the engine cannot do.
 *
 * Not merged across the fleet the way plans and templates are: a group orders the endpoints of the
 * installation it lives on, and two accounts have different endpoints, so the same id on two
 * accounts is two groups.
 */
@Composable
fun StudioNodeGroupsScreen(
    store: StudioStore,
    state: StudioState,
    onNewGroup: () -> Unit,
    onEditGroup: (NodeGroup) -> Unit,
    onBack: () -> Unit,
) {
    var loading by remember { mutableStateOf(state.nodeGroups.isEmpty()) }

    LaunchedEffect(Unit) {
        store.refreshNodeGroups()
        store.refreshNodes()
        loading = false
    }

    IosScreen(
        title = S(R.string.studio_node_groups),
        onBack = onBack,
        backLabel = S(R.string.studio_nodes),
        onRefresh = {
            store.refreshNodeGroups()
            store.refreshNodes()
        },
        trailing = {
            Text(
                S(R.string.studio_node_group_new),
                color = Ios.Blue,
                fontSize = 16.sp,
                modifier = Modifier.clickable(onClick = onNewGroup)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            )
        },
    ) {
        Spacer(Modifier.height(10.dp))
        PageIntro(S(R.string.studio_node_groups_sub))

        if (state.nodeGroupsStale.isNotEmpty()) {
            ProblemCard(
                S(R.string.studio_node_group_engine_old)
                    .replace("%1\$s", state.nodeGroupsStale.joinToString("، "))
            )
            Spacer(Modifier.height(10.dp))
        }

        if (loading) {
            WizardBusy(S(R.string.studio_loading))
            return@IosScreen
        }

        if (state.nodeGroups.isEmpty()) {
            EmptyState(
                icon = StudioIcons.EndpointGroups,
                title = S(R.string.studio_node_groups_empty_title),
                body = S(R.string.studio_node_groups_none),
                actionLabel = S(R.string.studio_node_group_new),
                onAction = onNewGroup,
            )
            Spacer(Modifier.height(28.dp))
            return@IosScreen
        }

        SettingsSectionHeader(S(R.string.studio_node_groups))
        SettingsGroup {
            state.nodeGroups.forEachIndexed { index, group ->
                if (index > 0) Separator()
                val members = state.nodes.count {
                    it.groupId == group.id && it.installationId == group.installationId
                }
                SettingsRow(
                    title = group.name,
                    value = strategyLabel(group.strategy),
                    // How many endpoints, and — when the fleet has more than one account — which
                    // account, because the same name on two accounts is two different groups.
                    subtitle = buildString {
                        append(S(R.string.studio_node_group_members).replace("%1\$s", faNum(members)))
                        if (!group.enabled) append(" · " + S(R.string.studio_node_group_off))
                        state.fleetLabels[group.installationId]
                            ?.takeIf { state.fleetLabels.size > 1 }
                            ?.let { append(" · $it") }
                    },
                    titleColor = if (group.enabled) Ios.Label else Ios.SecondaryLabel,
                    icon = StudioIcons.EndpointGroups,
                    tint = if (group.enabled) Ios.Purple else Ios.Gray,
                    onClick = { onEditGroup(group) },
                )
            }
        }
        SettingsFooter(S(R.string.studio_node_groups_note))

        // Said once, on the screen that would otherwise let the word imply it. The engine cannot
        // steer traffic; it can only decide what a client sees first.
        InfoCard(S(R.string.studio_node_group_truth))
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
internal fun strategyLabel(strategy: NodeStrategy): String = S(
    when (strategy) {
        NodeStrategy.ORDER -> R.string.studio_strategy_order
        NodeStrategy.HEALTHY -> R.string.studio_strategy_healthy
        NodeStrategy.FASTEST -> R.string.studio_strategy_fastest
        NodeStrategy.SPREAD -> R.string.studio_strategy_spread
    }
)

@Composable
internal fun strategyNote(strategy: NodeStrategy): String = S(
    when (strategy) {
        NodeStrategy.ORDER -> R.string.studio_strategy_order_sub
        NodeStrategy.HEALTHY -> R.string.studio_strategy_healthy_sub
        NodeStrategy.FASTEST -> R.string.studio_strategy_fastest_sub
        NodeStrategy.SPREAD -> R.string.studio_strategy_spread_sub
    }
)
