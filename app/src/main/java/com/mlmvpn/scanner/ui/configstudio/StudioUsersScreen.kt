package com.mlmvpn.scanner.ui.configstudio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.studio.StudioState
import com.mlmvpn.scanner.data.studio.StudioStore
import com.mlmvpn.scanner.data.studio.UserRef
import com.mlmvpn.scanner.data.studio.domain.StoppedReason
import com.mlmvpn.scanner.data.studio.domain.StudioUser
import com.mlmvpn.scanner.data.studio.index.UserFilter
import com.mlmvpn.scanner.data.studio.index.UserSort
import com.mlmvpn.scanner.ui.configstudio.design.CountryBadge
import com.mlmvpn.scanner.ui.configstudio.design.EmptyState
import com.mlmvpn.scanner.ui.configstudio.design.NoticeCard
import com.mlmvpn.scanner.ui.configstudio.design.StudioBadge
import com.mlmvpn.scanner.ui.configstudio.design.StudioIcons
import com.mlmvpn.scanner.ui.configstudio.design.StudioType
import com.mlmvpn.scanner.ui.configstudio.parts.MeterBar
import com.mlmvpn.scanner.ui.configstudio.parts.PageIntro
import com.mlmvpn.scanner.ui.configstudio.parts.StatusDot
import com.mlmvpn.scanner.ui.configstudio.parts.StudioChip
import com.mlmvpn.scanner.ui.configstudio.parts.StudioListRow
import com.mlmvpn.scanner.ui.configstudio.parts.WizardField
import com.mlmvpn.scanner.ui.configstudio.parts.WizardPrimary
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosAlert
import com.mlmvpn.scanner.ui.settings.IosAlertAction
import com.mlmvpn.scanner.ui.settings.IosRefreshIndicator
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.rememberIosRefreshState
import com.mlmvpn.scanner.utils.S

/**
 * «کاربران» -- the list, read entirely from the local index (build 18 redesign).
 *
 * A tab root when [onBack] is null, a picker when [pickTitle] is set. The whole page scrolls --
 * search, filters and all -- so pull-to-refresh works from anywhere on it, and the header does not sit
 * frozen over a list that moves beneath it.
 *
 * The search is an **infix** match, which the engine deliberately refuses to serve: `LIKE '%x%'`
 * cannot use an index and on D1 would read the whole table on every keystroke. Locally it walks a
 * few thousand short strings.
 */
@Composable
fun StudioUsersScreen(
    store: StudioStore,
    state: StudioState,
    onOpenUser: (installationId: String, userId: String) -> Unit,
    onNewUser: () -> Unit,
    /** Null when this is the Users tab itself, which has nowhere to go back to. */
    onBack: (() -> Unit)?,
    /** Where a selection goes. Null turns selection off, which is what a picker wants. */
    onBulk: ((List<UserRef>) -> Unit)? = null,
    pickTitle: String? = null,
    pickSubtitle: String? = null,
    /** Pull down to reload: a sync of every account, then the list re-reads itself. */
    onRefresh: (suspend () -> Unit)? = null,
    /** A filter to open with, set by a Home tile. Applied once, then [onPresetConsumed]. */
    preset: UserFilter? = null,
    onPresetConsumed: () -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<StudioUser>>(emptyList()) }
    var matched by remember { mutableStateOf(0) }
    var shardFilter by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf(UserFilter.ALL) }
    var sort by remember { mutableStateOf(UserSort.NEWEST) }
    var tag by remember { mutableStateOf<String?>(null) }
    var sortSheet by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateMapOf<String, UserRef>() }
    /** Bumped by a pull, so the list re-reads even when the counters did not move. */
    var reloads by remember { mutableStateOf(0) }

    LaunchedEffect(preset) {
        if (preset != null) {
            filter = preset
            onPresetConsumed()
        }
    }

    val fleetLabels = remember(state.installations) {
        state.installations.associate { it.installationId to it.accountLabel }
    }
    val multiShard = fleetLabels.size > 1

    var tags by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(state.indexedUsers, state.indexedActive, reloads) { tags = store.tagsInUse() }
    LaunchedEffect(tags) { if (tag != null && tag !in tags) tag = null }

    LaunchedEffect(query, shardFilter, filter, sort, tag, state.indexedUsers, state.indexedActive, reloads) {
        rows = store.page(
            limit = 200,
            query = query.ifBlank { null },
            installationId = shardFilter,
            filter = filter,
            sort = sort,
            tag = tag,
        )
        matched = store.countMatching(
            query = query.ifBlank { null },
            installationId = shardFilter,
            filter = filter,
            tag = tag,
        )
    }

    val picking = pickTitle != null
    val pullAction: (suspend () -> Unit)? = onRefresh?.let { action ->
        val f: suspend () -> Unit = {
            action()
            reloads++
        }
        f
    }
    val refresh = rememberIosRefreshState(pullAction)

    val trailingBar: (@Composable () -> Unit)? = if (picking) null else ({
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBulk != null) {
                    BarIcon(
                        icon = StudioIcons.Select,
                        description = if (selecting) S(R.string.studio_select_done) else S(R.string.studio_select),
                        tint = if (selecting) Ios.Blue else Ios.Label,
                    ) {
                        selecting = !selecting
                        if (!selecting) selected.clear()
                    }
                }
                if (!selecting) {
                    BarIcon(StudioIcons.NewUser, S(R.string.studio_new_user), Ios.Label, onNewUser)
                }
            }
        })

    IosScreen(
        title = pickTitle ?: S(R.string.studio_users),
        onBack = onBack,
        backLabel = if (onBack != null) S(R.string.studio_title) else null,
        scrollable = false,
        trailing = trailingBar,
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .then(if (onRefresh != null) Modifier.nestedScroll(refresh.connection) else Modifier),
        ) {
            item { IosRefreshIndicator(refresh) }
            item {
                Spacer(Modifier.height(10.dp))
                pickSubtitle?.let { PageIntro(it) }
                WizardField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = S(R.string.studio_search_users),
                    ltr = false,
                )
                Spacer(Modifier.height(6.dp))
            }
            if (multiShard) {
                item {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item { StudioChip(S(R.string.studio_shard_all), shardFilter == null) { shardFilter = null } }
                        items(fleetLabels.entries.toList(), key = { it.key }) { (id, label) ->
                            StudioChip(label, shardFilter == id) { shardFilter = if (shardFilter == id) null else id }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            item {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(UserFilter.entries.toList(), key = { it.name }) { f ->
                        StudioChip(filterLabel(f), filter == f) { filter = f }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            if (tags.isNotEmpty()) {
                item {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(tags, key = { it }) { t ->
                            StudioChip(t, tag == t) { tag = if (tag == t) null else t }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        S(R.string.studio_matched_n).replace("%1\$s", faNum(matched)),
                        color = Ios.SecondaryLabel,
                        fontSize = StudioType.Caption,
                        modifier = Modifier.weight(1f),
                    )
                    if (selecting) {
                        val allChosen = rows.isNotEmpty() && rows.all { selected.containsKey(refOf(it).key) }
                        Text(
                            if (allChosen) S(R.string.studio_select_none) else S(R.string.studio_select_all),
                            color = Ios.Blue,
                            fontSize = StudioType.Caption,
                            modifier = Modifier
                                .clickable {
                                    if (allChosen) rows.forEach { selected.remove(refOf(it).key) }
                                    else rows.forEach { val r = refOf(it); selected[r.key] = r }
                                }
                                .padding(vertical = 6.dp),
                        )
                    } else {
                        Text(
                            sortLabel(sort),
                            color = Ios.Blue,
                            fontSize = StudioType.Caption,
                            modifier = Modifier.clickable { sortSheet = true }.padding(vertical = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            if (state.unreachable.isNotEmpty()) {
                item {
                    NoticeCard(
                        icon = StudioIcons.Error,
                        tint = Ios.Red,
                        title = S(R.string.studio_home_unreachable_title),
                        body = S(R.string.studio_account_unreachable).replace("%1\$s", state.unreachable.joinToString("، ")),
                    )
                }
            }
            if (rows.isEmpty()) {
                item {
                    val searching = query.isNotBlank() || picking || filter != UserFilter.ALL || tag != null
                    if (searching) {
                        EmptyState(icon = StudioIcons.Search, title = S(R.string.studio_no_results))
                    } else {
                        EmptyState(
                            icon = StudioIcons.Users,
                            title = S(R.string.studio_no_users_title),
                            body = S(R.string.studio_no_users_body),
                            actionLabel = S(R.string.studio_new_user),
                            onAction = onNewUser,
                        )
                    }
                }
            } else {
                items(rows, key = { it.installationId + ":" + it.id }) { user ->
                    val ref = refOf(user)
                    UserRow(
                        user = user,
                        shard = if (multiShard && shardFilter == null) fleetLabels[user.installationId] else null,
                        chosen = if (selecting) selected.containsKey(ref.key) else null,
                    ) {
                        if (selecting) {
                            if (selected.remove(ref.key) == null) selected[ref.key] = ref
                        } else {
                            onOpenUser(user.installationId, user.id)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(28.dp + com.mlmvpn.scanner.ui.LocalSystemBottomPadding.current)) }
        }

        if (selecting && onBulk != null) {
            Spacer(Modifier.height(8.dp))
            WizardPrimary(
                S(R.string.studio_selected_n).replace("%1\$s", faNum(selected.size)),
                enabled = selected.isNotEmpty(),
            ) {
                onBulk(rows.map { refOf(it) }.filter { selected.containsKey(it.key) }
                    .plus(selected.values.filter { chosen -> rows.none { refOf(it).key == chosen.key } })
                    .distinctBy { it.key })
            }
            Spacer(Modifier.height(14.dp + com.mlmvpn.scanner.ui.LocalSystemBottomPadding.current))
        }
    }

    if (sortSheet) {
        IosAlert(
            title = S(R.string.studio_sort),
            message = S(R.string.studio_sort_sub),
            actions = UserSort.entries.map { option ->
                IosAlertAction(sortLabel(option), { sort = option; sortSheet = false })
            } + IosAlertAction(S(R.string.studio_cancel), { sortSheet = false }),
            onDismiss = { sortSheet = false },
        )
    }
}

/** An icon button in a navigation bar: a 22dp glyph in a round hit area. */
@Composable
internal fun BarIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: androidx.compose.ui.graphics.Color = Ios.Label,
    onClick: () -> Unit,
) {
    Icon(
        icon,
        contentDescription = description,
        tint = tint,
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick).padding(8.dp).size(22.dp),
    )
}

@Composable
private fun filterLabel(f: UserFilter): String = S(
    when (f) {
        UserFilter.ALL -> R.string.studio_filter_all
        UserFilter.ACTIVE -> R.string.studio_filter_active
        UserFilter.DISABLED -> R.string.studio_filter_disabled
        UserFilter.EXPIRED -> R.string.studio_filter_expired
        UserFilter.EXPIRING -> R.string.studio_filter_expiring
        UserFilter.OUT_OF_VOLUME -> R.string.studio_filter_out_of_volume
        UserFilter.NEVER_CONNECTED -> R.string.studio_filter_never
    }
)

@Composable
private fun sortLabel(s: UserSort): String = S(
    when (s) {
        UserSort.NEWEST -> R.string.studio_sort_newest
        UserSort.OLDEST -> R.string.studio_sort_oldest
        UserSort.NAME -> R.string.studio_sort_name
        UserSort.EXPIRY -> R.string.studio_sort_expiry
        UserSort.USAGE -> R.string.studio_sort_usage
        UserSort.LAST_ACTIVE -> R.string.studio_sort_active
    }
)

/** The ref this row would contribute to a selection. One place, so the key cannot drift. */
private fun refOf(user: StudioUser) = UserRef(user.installationId, user.id, user.username)

@Composable
private fun UserRow(
    user: StudioUser,
    shard: String?,
    /** Null means the list is not selecting. False means selecting and this row is not chosen. */
    chosen: Boolean? = null,
    onClick: () -> Unit,
) {
    val stopped = user.stoppedReason()
    StudioListRow(onClick = onClick) {
        if (chosen != null) {
            Icon(
                imageVector = if (chosen) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (chosen) Ios.Blue else Ios.SecondaryLabel.copy(alpha = 0.5f),
                modifier = Modifier.size(21.dp),
            )
        } else {
            StatusDot(
                when (stopped) {
                    null -> Ios.Green
                    StoppedReason.EXPIRED, StoppedReason.OUT_OF_VOLUME -> Ios.Orange
                    else -> Ios.SecondaryLabel
                }
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    user.username,
                    color = Ios.Label,
                    fontSize = StudioType.Body,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                UserKindBadge(user)
            }
            Spacer(Modifier.height(3.dp))
            Text(
                (shard?.let { "$it · " } ?: "") + subtitleFor(user, stopped),
                color = Ios.SecondaryLabel,
                fontSize = StudioType.Caption,
            )
            user.usage.fractionOf(user.policy.quotaBytes)?.let { fraction ->
                Spacer(Modifier.height(6.dp))
                MeterBar(fraction = fraction, tint = if (fraction >= 1f) Ios.Red else Ios.Blue)
            }
        }
    }
}

@Composable
private fun subtitleFor(user: StudioUser, stopped: StoppedReason?): String {
    if (stopped != null) return when (stopped) {
        StoppedReason.DISABLED -> S(R.string.studio_row_disabled)
        StoppedReason.EXPIRED -> S(R.string.studio_row_expired)
        StoppedReason.OUT_OF_VOLUME -> S(R.string.studio_row_out_of_volume)
        StoppedReason.DELETED -> S(R.string.studio_row_expired)
    }

    val volume = user.policy.quotaBytes?.let {
        S(R.string.studio_of_volume)
            .replace("%1\$s", bytesFa(user.usage.usedBytes))
            .replace("%2\$s", bytesFa(it))
    } ?: S(R.string.studio_row_unlimited)

    val time = user.policy.expiresAt?.let { timeLeftText(it) ?: S(R.string.studio_row_expired) }
        ?: S(R.string.studio_row_no_expiry)

    return "$volume · $time"
}

/**
 * Which kind of person this is, without opening them: the countries of their configs as code
 * badges, or «ساده» for someone with none. Read off `locations`, which the engine keeps equal to
 * their configs.
 */
@Composable
internal fun UserKindBadge(user: StudioUser) {
    if (user.locations.isEmpty()) {
        StudioBadge(S(R.string.studio_kind_simple))
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        user.locations.take(3).forEach { CountryBadge(it) }
        if (user.locations.size > 3) {
            Text("+" + faNum(user.locations.size - 3), color = Ios.SecondaryLabel, fontSize = StudioType.Tiny)
        }
    }
}
