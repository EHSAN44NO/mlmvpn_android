package com.mlmvpn.scanner.ui.sublink

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.data.GroupManager
import com.mlmvpn.scanner.data.NodeManager
import com.mlmvpn.scanner.engines.subgenerator.SubGenAccountData
import com.mlmvpn.scanner.engines.subgenerator.SubGenManager
import com.mlmvpn.scanner.engines.subgenerator.SubLinkConfig
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.ui.NodeQrCard
import com.mlmvpn.scanner.ui.faCount
import com.mlmvpn.scanner.ui.faGrouped
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosAlert
import com.mlmvpn.scanner.ui.settings.IosAlertAction
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGlyph
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.theme.ControlShape
import kotlinx.coroutines.launch
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

/**
 * Everything one link is and does.
 *
 * The old card tried to hold all of it in the list: name, group, expiry, a delete button, the full
 * URL, an update button and a stats button, in a 16dp-padded slab repeated per link. Nothing had
 * room -- the URL was an 11sp single line, the two buttons split the width between them, and the
 * one thing a link is FOR, handing the address to someone, had no QR and no share.
 */
@Composable
fun SubLinkDetailScreen(
    link: SubLinkConfig,
    accountData: SubGenAccountData,
    account: CloudAccount,
    subGenManager: SubGenManager,
    nodeManager: NodeManager,
    groupManager: GroupManager,
    onEdit: () -> Unit,
    onChanged: (String) -> Unit,
    onDeleted: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var updating by remember { mutableStateOf(false) }
    var fetchingStats by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    // When the hit count on screen was last actually fetched. The old button was both the label
    // and the trigger, so the number sat there with no indication of whether it was from this
    // minute or from three weeks ago.
    var statsFetchedAt by remember { mutableStateOf(0L) }
    var hits by remember(link.slug) { mutableStateOf(link.hits) }

    val fullUrl = "${accountData.workerUrl}/sub/${link.slug}"
    val (expiry, expiryTone) = expiryLabel(link.expiryTimestamp)

    fun copy() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Sub Link", fullUrl))
        onChanged(S(R.string.link_copied_2))
    }

    IosScreen(
        title = link.name,
        onBack = onBack,
        backLabel = S(R.string.sub_link_2),
        trailing = {
            Icon(
                Icons.Default.Edit,
                contentDescription = S(R.string.edit_2),
                tint = Ios.Label,
                modifier = Modifier
                    .size(30.dp)
                    .clip(ControlShape)
                    .clickable(onClick = onEdit)
                    .padding(5.dp),
            )
        },
    ) {
        Spacer(Modifier.height(14.dp))

        // ---- the address -------------------------------------------------------------
        SettingsSectionHeader(S(R.string.link_address))
        SettingsGroup {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { copy() }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    fullUrl,
                    color = Ios.Label,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                )
            }
            Separator()
            SettingsActionRow(
                label = S(R.string.copy_the_address),
                icon = Icons.Default.ContentCopy,
            ) { copy() }
            Separator()
            SettingsActionRow(
                label = S(R.string.share_2),
                icon = Icons.Default.Share,
            ) {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, fullUrl)
                }
                context.startActivity(android.content.Intent.createChooser(send, S(R.string.send_sub_link)))
            }
            Separator()
            SettingsActionRow(
                label = if (showQr) S(R.string.hide_the_qr_code) else S(R.string.show_the_qr_code),
                icon = Icons.Default.QrCode,
            ) { showQr = !showQr }
        }
        SettingsFooter(
            S(R.string.enter_this_address_in_any_app_that) +
                S(R.string.the_full_address_is_on_screen_one)
        )

        if (showQr) {
            Spacer(Modifier.height(12.dp))
            NodeQrCard(fullUrl)
        }

        // ---- what it serves ----------------------------------------------------------
        SettingsSectionHeader(S(R.string.content))
        SettingsGroup {
            SettingsRow(
                title = S(R.string.config_group),
                icon = Icons.Default.Folder,
                tint = Ios.Blue,
                value = link.mappedGroupName?.let { groupShortName(it) } ?: S(R.string.no_group_2),
                showChevron = false,
            )
            Separator()
            SettingsRow(
                title = S(R.string.expiry),
                icon = Icons.Default.Schedule,
                tint = if (expiryTone == Ios.Red) Ios.Red else Ios.Gray,
                value = expiry ?: S(R.string.never_expires),
                titleColor = Ios.Label,
                showChevron = false,
            )
            Separator()
            SettingsActionRow(
                label = if (updating) S(R.string.updating_2_r2) else S(R.string.update_the_configs),
                icon = Icons.Default.Refresh,
                busy = updating,
            ) {
                val mapped = link.mappedGroupName ?: return@SettingsActionRow
                updating = true
                scope.launch {
                    // One implementation of the composite-id parsing, in the manager. The screen
                    // used to carry a hand-copied second copy of the same `when` block.
                    val nodes = subGenManager.resolveGroupNodes(
                        mapped, nodeManager.nodes, groupManager,
                    )
                    val configs = nodes.joinToString("\n") { it.uri }
                    val result = subGenManager.uploadConfigs(
                        account, accountData, link.slug, configs, link.expiryTimestamp,
                    )
                    onChanged(
                        if (result.first) faCount(nodes.size) + S(R.string.configs_updated_on_cloudflare)
                        else S(R.string.update_failed) + result.second
                    )
                    updating = false
                }
            }
        }
        SettingsFooter(
            S(R.string.the_configs_normally_update_themselves_a_few) +
                S(R.string.this_button_is_for_when_you_want)
        )

        // ---- usage -------------------------------------------------------------------
        SettingsSectionHeader(S(R.string.statistics))
        SettingsGroup {
            SettingsRow(
                title = S(R.string.views),
                icon = Icons.Default.BarChart,
                tint = Ios.Gray,
                value = faGrouped(hits),
                subtitle = when {
                    statsFetchedAt > 0L -> S(R.string.updated_just_now_2)
                    else -> S(R.string.the_last_figure_fetched)
                },
                showChevron = false,
            )
            Separator()
            SettingsActionRow(
                label = if (fetchingStats) S(R.string.fetching_statistics) else S(R.string.fetch_fresh_statistics),
                icon = Icons.Default.Refresh,
                busy = fetchingStats,
            ) {
                fetchingStats = true
                scope.launch {
                    hits = subGenManager.fetchStats(account, accountData, link.slug)
                    statsFetchedAt = System.currentTimeMillis()
                    fetchingStats = false
                }
            }
        }
        SettingsFooter(S(R.string.how_many_times_this_link_has_been))

        // ---- destructive -------------------------------------------------------------
        Spacer(Modifier.height(18.dp))
        SettingsGroup {
            SettingsActionRow(
                label = S(R.string.delete_this_link),
                icon = Icons.Default.Delete,
                tint = Ios.Red,
            ) { confirmDelete = true }
        }
        SettingsFooter(S(R.string.the_link_is_removed_from_cloudflare_and))

        Spacer(Modifier.height(40.dp))
    }

    if (confirmDelete) {
        IosAlert(
            title = S(R.string.delete_2_r2, link.name),
            message = S(R.string.this_link_is_removed_from_cloudflare_anyone),
            onDismiss = { confirmDelete = false },
            actions = listOf(
                IosAlertAction(S(R.string.cancel_2_r2), onClick = { confirmDelete = false }),
                IosAlertAction(
                    S(R.string.delete_3_r2),
                    onClick = {
                        confirmDelete = false
                        scope.launch {
                            val ok = subGenManager.deleteSubLinkFromKV(
                                account, accountData, link.slug,
                            )
                            if (ok) {
                                subGenManager.removeSubLink(link.slug)
                                onDeleted(S(R.string.link_deleted))
                            } else {
                                onChanged(S(R.string.could_not_delete_it_from_cloudflare))
                            }
                        }
                    },
                    destructive = true,
                ),
            ),
        )
    }
}

/**
 * What a sub link is, for someone who has never used one.
 *
 * There was no help of any kind on this screen -- the concept (a URL of your own that other apps
 * subscribe to, which updates itself when your folder changes) is not guessable from a form with
 * four fields in it.
 */
@Composable
fun SubLinkHelpScreen(onBack: () -> Unit) {
    IosScreen(title = S(R.string.sub_link_guide), onBack = onBack, backLabel = S(R.string.sub_link_2)) {
        Spacer(Modifier.height(14.dp))

        SettingsFooter(
            S(R.string.a_sub_link_is_a_web_address) +
                S(R.string.instead_of_sending_someone_dozens_of_configs) +
                S(R.string.the_configs_that_same_address_refreshes_itself)
        )

        SettingsSectionHeader(S(R.string.how_it_works))
        SettingsGroup {
            HelpRow(
                Icons.Default.Folder,
                Ios.Blue,
                S(R.string.you_choose_a_group),
                S(R.string.any_folder_from_v2ray_any_cloud_or),
            )
            Separator()
            HelpRow(
                Icons.Default.Share,
                Ios.Green,
                S(R.string.you_hand_out_the_address),
                S(R.string.the_address_runs_on_your_own_cloudflare),
            )
            Separator()
            HelpRow(
                Icons.Default.Refresh,
                Ios.Gray,
                S(R.string.it_keeps_itself_up_to_date),
                S(R.string.a_few_seconds_after_any_change_to),
            )
        }

        SettingsSectionHeader(S(R.string.notes))
        SettingsGroup {
            HelpRow(
                Icons.Default.Schedule,
                Ios.Orange,
                S(R.string.expiry),
                S(R.string.you_can_make_a_link_stop_working),
            )
            Separator()
            HelpRow(
                Icons.Default.BarChart,
                Ios.Gray,
                S(R.string.views),
                S(R.string.it_tells_you_how_many_times_the),
            )
            Separator()
            HelpRow(
                Icons.Default.Delete,
                Ios.Red,
                S(R.string.delete_3_r2),
                S(R.string.removes_the_link_from_cloudflare_it_cannot),
            )
        }

        SettingsFooter(
            S(R.string.if_an_address_reaches_the_wrong_hands) +
                S(R.string.the_address_is_the_only_thing_that)
        )

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun HelpRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    title: String,
    body: String,
) {
    SettingsRow(title = title, icon = icon, tint = tint, subtitle = body, showChevron = false)
}
