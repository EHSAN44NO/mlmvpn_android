package com.mlmvpn.scanner.ui.configstudio

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.PanelBuild
import com.mlmvpn.scanner.data.studio.StudioState
import com.mlmvpn.scanner.ui.configstudio.design.StudioIcons
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.utils.S

/**
 * «بیشتر» -- every place in Config Studio that is not a person or a figure, once, under the name
 * the operator would look for it by (build 18 redesign).
 *
 * Before this, several of these had three ways in (the exits page from the dashboard, from settings
 * and from multi-location; endpoints from a tile and from settings) and a back label that named only
 * one of them. Each now has exactly one entry, grouped by what it is for:
 *
 *   links    --  how configs are shaped and what terms people get
 *   network  --  where traffic leaves from, and the addresses links point at
 *   tools    --  the things done to many people at once, or to one person's address
 *   system   --  the Cloudflare accounts, the engine on them, and what happened
 */
@Composable
fun StudioMoreScreen(
    state: StudioState,
    onOpenTemplates: () -> Unit,
    onOpenPlans: () -> Unit,
    onOpenLocations: () -> Unit,
    onOpenNodes: () -> Unit,
    onNewConfig: () -> Unit,
    onCombine: () -> Unit,
    onBulkUsers: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAudit: () -> Unit,
) {
    val stale = state.installations.count { it.isStale(PanelBuild.MLM, PanelBuild.MLM_SCHEMA) }

    IosScreen(largeTitle = S(R.string.studio_tab_more)) {
        SettingsSectionHeader(S(R.string.studio_more_links))
        SettingsGroup {
            SettingsRow(
                title = S(R.string.studio_plans),
                subtitle = S(R.string.studio_more_plans_sub),
                icon = StudioIcons.Plan,
                tint = Ios.Orange,
                onClick = onOpenPlans,
            )
            Separator()
            SettingsRow(
                title = S(R.string.studio_templates),
                subtitle = S(R.string.studio_more_templates_sub),
                icon = StudioIcons.Template,
                tint = Ios.Purple,
                onClick = onOpenTemplates,
            )
        }

        SettingsSectionHeader(S(R.string.studio_more_network))
        SettingsGroup {
            SettingsRow(
                title = S(R.string.studio_more_locations),
                subtitle = S(R.string.studio_more_locations_sub),
                icon = StudioIcons.Locations,
                tint = Ios.Indigo,
                onClick = onOpenLocations,
            )
            Separator()
            SettingsRow(
                title = S(R.string.studio_nodes),
                subtitle = S(R.string.studio_more_nodes_sub),
                value = state.nodes.takeIf { it.isNotEmpty() }?.let { faNum(it.size) },
                icon = StudioIcons.Endpoints,
                tint = Ios.Teal,
                onClick = onOpenNodes,
            )
        }

        SettingsSectionHeader(S(R.string.studio_more_tools))
        SettingsGroup {
            SettingsRow(
                title = S(R.string.studio_config_for),
                icon = StudioIcons.Config,
                tint = Ios.Blue,
                onClick = onNewConfig,
            )
            Separator()
            SettingsRow(
                title = S(R.string.studio_bulk_new_title),
                icon = StudioIcons.BulkUsers,
                tint = Ios.Green,
                onClick = onBulkUsers,
            )
            Separator()
            SettingsRow(
                title = S(R.string.studio_combine),
                icon = StudioIcons.Combine,
                tint = Ios.Purple,
                onClick = onCombine,
            )
        }

        SettingsSectionHeader(S(R.string.studio_more_system))
        SettingsGroup {
            SettingsRow(
                title = S(R.string.studio_more_accounts),
                subtitle = if (stale > 0) S(R.string.studio_more_accounts_stale).replace("%1\$s", faNum(stale)) else null,
                value = faNum(state.installations.size),
                icon = StudioIcons.Accounts,
                tint = if (stale > 0) Ios.Blue else Ios.Gray,
                onClick = onOpenAccounts,
            )
            Separator()
            SettingsRow(
                title = S(R.string.studio_settings_title),
                icon = StudioIcons.Settings,
                tint = Ios.Gray,
                onClick = onOpenSettings,
            )
            Separator()
            SettingsRow(
                title = S(R.string.studio_more_history),
                subtitle = S(R.string.studio_more_history_sub),
                icon = StudioIcons.Audit,
                tint = Ios.Teal,
                onClick = onOpenAudit,
            )
        }
        SettingsFooter(S(R.string.studio_more_footer))
        Spacer(Modifier.height(28.dp))
    }
}
