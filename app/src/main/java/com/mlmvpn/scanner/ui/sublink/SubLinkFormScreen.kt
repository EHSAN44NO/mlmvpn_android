package com.mlmvpn.scanner.ui.sublink

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.data.GroupManager
import com.mlmvpn.scanner.data.NodeManager
import com.mlmvpn.scanner.data.SubscriptionManager
import com.mlmvpn.scanner.engines.subgenerator.SubGenAccountData
import com.mlmvpn.scanner.engines.subgenerator.SubGenManager
import com.mlmvpn.scanner.engines.subgenerator.SubLinkConfig
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.ui.faCount
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGlyph
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.settings.SettingsTextRow
import com.mlmvpn.scanner.ui.theme.BadgeShape
import com.mlmvpn.scanner.ui.theme.ControlShape
import kotlinx.coroutines.launch
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

/**
 * Making a link, or changing one.
 *
 * A page, not the `AlertDialog` this used to be. Four fields and a horizontally-scrolling list of
 * every config group in the app do not fit in a dialog, and the group row is why: its labels ran
 * to "دستی: 📁 ایران ۱ (۱۲ کانفیگ)" and there could be dozens, side by side, inside a box narrower
 * than one of them.
 *
 * Editing did not exist at all before. A link's name, group and expiry were fixed at creation, so
 * pointing an already-shared URL at a different folder meant deleting it and making a new one --
 * with a new URL, which defeats the point of having shared it.
 */
@Composable
fun SubLinkFormScreen(
    draft: SubLinkDraft,
    /** Every slug already in use, so a create cannot silently overwrite one. */
    existingSlugs: List<String>,
    subGenManager: SubGenManager,
    accountData: SubGenAccountData,
    account: CloudAccount,
    onOpenGroupPicker: () -> Unit,
    onDone: (String) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    val editing = draft.editing
    val trimmedSlug = draft.slug.trim()

    // The bug this closes: `addSubLink` does `removeAll { it.slug == link.slug }` and the upload
    // PUTs over `sub_<slug>` in KV, so typing a slug that already existed destroyed the other
    // link -- its configs, its stats and its row -- with no warning of any kind.
    val slugTaken = trimmedSlug.isNotEmpty() &&
        trimmedSlug != editing &&
        existingSlugs.any { it.equals(trimmedSlug, ignoreCase = true) }

    val slugError = when {
        slugTaken -> S(R.string.this_address_is_already_in_use_if)
        trimmedSlug.isNotEmpty() && trimmedSlug.length < 4 -> S(R.string.at_least_4_characters)
        else -> null
    }

    val group = draft.group
    val nameOk = draft.name.isNotBlank()
    val slugOk = trimmedSlug.length >= 4 && !slugTaken
    val groupOk = group != null && group.nodes.isNotEmpty()
    val canSave = nameOk && slugOk && groupOk && !saving

    fun save() {
        val chosen = draft.group ?: return
        saving = true
        scope.launch {
            val days = draft.expiryDays.trim().toLongOrNull()
            val expiryTs = if (days != null && days > 0) {
                System.currentTimeMillis() + days * 24 * 60 * 60 * 1000
            } else {
                0L
            }
            val configs = chosen.nodes.joinToString("\n") { it.uri }
            val result = subGenManager.uploadConfigs(
                account, accountData, trimmedSlug, configs, expiryTs,
            )
            if (result.first) {
                // An edit that renamed the slug leaves the old KV entry and the old row behind,
                // so the old one is retired explicitly rather than orphaned.
                if (editing != null && editing != trimmedSlug) {
                    subGenManager.deleteSubLinkFromKV(account, accountData, editing)
                    subGenManager.removeSubLink(editing)
                }
                subGenManager.addSubLink(
                    SubLinkConfig(
                        slug = trimmedSlug,
                        name = draft.name.trim(),
                        mappedGroupName = chosen.id,
                        expiryTimestamp = expiryTs,
                        accountId = account.id,
                    )
                )
                onDone(if (editing != null) S(R.string.link_updated) else S(R.string.sub_link_created))
            } else {
                failure = result.second
            }
            saving = false
        }
    }

    IosScreen(
        title = if (editing != null) S(R.string.edit_link) else S(R.string.new_sub_link_2),
        onBack = onBack,
        backLabel = S(R.string.sub_link_2),
        trailing = {
            if (saving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Ios.SecondaryLabel,
                )
            } else {
                Text(
                    S(R.string.save_2),
                    color = if (canSave) Ios.Label else Ios.SecondaryLabel.copy(alpha = 0.5f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(ControlShape)
                        .clickable(enabled = canSave) { save() }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        },
    ) {
        Spacer(Modifier.height(14.dp))

        SettingsGroup {
            SettingsTextRow(
                title = S(R.string.name_2),
                value = draft.name,
                onValueChange = { draft.name = it },
                icon = Icons.Default.Label,
                placeholder = S(R.string.e_g_family_link),
            )
            Separator()
            SettingsTextRow(
                title = S(R.string.address_2),
                value = draft.slug,
                onValueChange = { draft.slug = it.replace(Regex("[^a-zA-Z0-9_-]"), "") },
                icon = Icons.Default.Link,
                placeholder = "my-link",
                error = slugError,
            )
        }
        SettingsFooter(
            S(R.string.the_address_is_the_part_at_the) +
                S(R.string.latin_letters_digits_hyphens_and_underscores_only)
        )

        SettingsSectionHeader(S(R.string.configs_2_r2))
        SettingsGroup {
            SettingsRow(
                title = S(R.string.group),
                icon = Icons.Default.Folder,
                tint = Ios.Blue,
                value = group?.displayName ?: S(R.string.choose_2),
                subtitle = group?.let {
                    it.source.label + " · " + faCount(it.nodes.size) + S(R.string.configs_3)
                },
                onClick = onOpenGroupPicker,
            )
        }
        SettingsFooter(
            if (group != null && group.nodes.isEmpty()) {
                S(R.string.this_group_has_no_configs_so_the)
            } else {
                S(R.string.whenever_this_group_s_configs_change_the)
            }
        )

        SettingsSectionHeader(S(R.string.expiry))
        SettingsGroup {
            SettingsTextRow(
                title = S(R.string.days_2),
                value = draft.expiryDays,
                onValueChange = { draft.expiryDays = it.filter { c -> c.isDigit() } },
                icon = Icons.Default.Schedule,
                placeholder = S(R.string.never_expires),
                numeric = true,
            )
        }
        SettingsFooter(
            if (editing != null) {
                S(R.string.leave_it_empty_to_never_expire_the) +
                    S(R.string.not_from_when_the_link_was_created)
            } else {
                S(R.string.leave_it_empty_to_never_expire_after) +
                    S(R.string.configs_4)
            }
        )

        if (!canSave && !saving) {
            SettingsFooter(
                when {
                    !nameOk -> S(R.string.enter_a_name_to_save)
                    !slugOk -> S(R.string.enter_a_unique_address_of_at_least)
                    group == null -> S(R.string.choose_a_config_group_to_save)
                    else -> S(R.string.the_selected_group_has_no_configs)
                }
            )
        }

        Spacer(Modifier.height(40.dp))
    }

    val error = failure
    if (error != null) {
        com.mlmvpn.scanner.ui.settings.IosAlert(
            title = S(R.string.could_not_save_to_cloudflare),
            message = error,
            onDismiss = { failure = null },
            actions = listOf(
                com.mlmvpn.scanner.ui.settings.IosAlertAction(S(R.string.close_2), onClick = { failure = null }),
                com.mlmvpn.scanner.ui.settings.IosAlertAction(
                    S(R.string.try_again_2_r3),
                    onClick = { failure = null; save() },
                    preferred = true,
                ),
            ),
        )
    }
}

/**
 * Which folder of configs the link serves.
 *
 * Was a `LazyRow` of chips inside the create dialog. A horizontal strip is the right shape for
 * three or four short options and the wrong one for every config group in the app -- and the
 * category ("دستی", "ابری", "اسکنر") was baked into each label rather than being the heading it
 * always was.
 */
@Composable
fun SubLinkGroupScreen(
    nodeManager: NodeManager,
    groupManager: GroupManager,
    subscriptionManager: SubscriptionManager,
    selectedId: String?,
    onSelect: (SubGenGroup) -> Unit,
    onBack: () -> Unit,
) {
    val nodes by nodeManager.nodesFlow.collectAsState()
    val cloudGroups by groupManager.cloudGroupsFlow.collectAsState()
    val scannerGroups by groupManager.scannerGroupsFlow.collectAsState()
    val subscriptions by subscriptionManager.subscriptionsFlow.collectAsState()
    var query by remember { mutableStateOf("") }

    val groups = remember(nodes, cloudGroups, scannerGroups, subscriptions) {
        buildSubGenGroups(nodes, cloudGroups, scannerGroups, subscriptions)
    }

    val shown = remember(groups, query) {
        val q = query.trim()
        if (q.isBlank()) groups
        else groups.filter {
            it.displayName.contains(q, true) ||
                it.subtitle.contains(q, true) ||
                it.source.label.contains(q, true)
        }
    }

    val bySource = remember(shown) {
        shown.groupBy { it.source }.toSortedMap(compareBy { it.ordinal })
    }

    IosScreen(title = S(R.string.config_group), onBack = onBack, backLabel = S(R.string.link_2), scrollable = false) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(ControlShape)
                .background(Color.White.copy(alpha = 0.10f))
                .heightIn(min = 36.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = Ios.SecondaryLabel,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(7.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = Ios.Label, fontSize = 16.sp),
                cursorBrush = SolidColor(Ios.Blue),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) {
                            Text(S(R.string.search_groups), color = Ios.SecondaryLabel, fontSize = 16.sp)
                        }
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = S(R.string.clear_2_r2),
                    tint = Ios.SecondaryLabel,
                    modifier = Modifier
                        .size(26.dp)
                        .clip(BadgeShape)
                        .clickable { query = "" }
                        .padding(5.dp),
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = 4.dp,
                bottom = com.mlmvpn.scanner.ui.LocalSystemBottomPadding.current + 32.dp,
            ),
        ) {
            if (groups.isEmpty()) {
                item {
                    com.mlmvpn.scanner.ui.GatewayEmptyState(
                        title = S(R.string.no_configs_found),
                        body = S(R.string.add_configs_in_v2ray_or_cloud_first),
                    )
                }
            } else if (shown.isEmpty()) {
                item {
                    com.mlmvpn.scanner.ui.GatewayEmptyState(
                        title = S(R.string.no_group_found),
                        body = S(R.string.no_group_matched_query, query),
                    )
                }
            }

            bySource.forEach { (source, list) ->
                item(key = "h_${source.name}") { SettingsSectionHeader(source.label) }
                item(key = "g_${source.name}") {
                    SettingsGroup {
                        list.forEachIndexed { index, item ->
                            if (index > 0) Separator()
                            GroupRow(
                                group = item,
                                selected = item.id == selectedId,
                                onClick = { onSelect(item); onBack() },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupRow(group: SubGenGroup, selected: Boolean, onClick: () -> Unit) {
    val empty = group.nodes.isEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !empty, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsGlyph(Icons.Default.Folder, if (empty) Ios.Gray else Ios.Blue)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                group.displayName,
                color = if (empty) Ios.SecondaryLabel else Ios.Label,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (group.subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(group.subtitle, color = Ios.SecondaryLabel, fontSize = 12.sp)
            }
        }
        // The count is a state -- an empty group cannot serve a link -- so it keeps its colour.
        Text(
            if (empty) S(R.string.empty) else faCount(group.nodes.size) + S(R.string.configs_3),
            color = if (empty) Ios.Orange else Ios.SecondaryLabel,
            fontSize = 14.sp,
        )
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = Ios.Blue,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
