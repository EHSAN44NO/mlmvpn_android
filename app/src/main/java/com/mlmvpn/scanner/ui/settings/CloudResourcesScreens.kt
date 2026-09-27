package com.mlmvpn.scanner.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.CloudManager
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.ui.LocalSystemBottomPadding
import com.mlmvpn.scanner.ui.theme.TextPrimary
import kotlinx.coroutines.launch
import com.mlmvpn.scanner.utils.S

// =================================================================================================
// The Cloudflare account's resources, in the shape iOS gives the Apple Account page.
//
// The workers list used to be a home-screen icon of its own, which put a Cloudflare-only
// maintenance screen next to the transports -- the things the app is actually for. It belongs
// where the account it describes lives, so it moved under the Cloudflare card at the top of
// Settings, and it brought the account's other two resource types with it.
//
// The hub groups by RESOURCE, not by account: the connected accounts sit at the top (tapping one
// opens the Cloud screen where it is managed), then one section per resource type with one row
// per account inside it. With two accounts that reads
//
//     وورکرها
//       وورکرهای a@x.com          3  >
//       وورکرهای b@y.com          1  >
//
// which answers "how many workers do I have, and where" in one glance. Grouping by account
// instead would answer "what does account A have" -- a question nobody arrives at this screen
// with, because they came here to find something to delete.
//
// Each row pushes a full list with per-item detail and three ways to delete: one row, a
// multi-select, or the lot.
// =================================================================================================

/** Which of an account's three resource lists a route points at. */
enum class CloudResource { WORKERS, D1, KV }

/** A per-account count for the hub's trailing grey values. `-1` means the fetch failed. */
private data class ResourceCounts(
    val workers: Int? = null,
    val d1: Int? = null,
    val kv: Int? = null,
)

// =================================================================================================
// The hub
// =================================================================================================

@Composable
fun CloudResourcesHub(
    accounts: List<CloudAccount>,
    backLabel: String,
    onBack: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpen: (accountIndex: Int, resource: CloudResource) -> Unit,
) {
    val context = LocalContext.current
    val cloudManager = remember { CloudManager(context) }
    var counts by remember { mutableStateOf<Map<String, ResourceCounts>>(emptyMap()) }

    // One pass over every account, three calls each. Sequential rather than parallel on purpose:
    // Cloudflare rate-limits per account, and a user with four accounts firing twelve requests at
    // once is how a listing comes back 429 with no rows at all.
    LaunchedEffect(accounts.map { it.id }) {
        for (account in accounts) {
            val workers = cloudManager.getWorkersDetailed(account)
            val d1 = cloudManager.getD1Databases(account)
            val kv = cloudManager.getKvNamespaces(account)
            counts = counts + (
                account.id to ResourceCounts(
                    workers = if (workers.first) workers.second.workers.size else -1,
                    d1 = if (d1.first) d1.second.size else -1,
                    kv = if (kv.first) kv.second.size else -1,
                )
                )
        }
    }

    IosScreen(
        title = stringResource(R.string.cf_resources_title),
        onBack = onBack,
        backLabel = backLabel,
    ) {
        // The profile block: the mark on its own, centred, with what it belongs to under it. This
        // is the whole reason the page reads as "an account" rather than as another settings list.
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_cloudflare_round),
                contentDescription = null,
                modifier = Modifier.size(104.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                when {
                    accounts.isEmpty() -> stringResource(R.string.settings_cf_none_title)
                    accounts.size == 1 ->
                        accounts[0].name.takeIf { it.isNotBlank() } ?: accounts[0].email
                    else -> stringResource(R.string.settings_cf_accounts, accounts.size)
                },
                color = Ios.Label,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                when {
                    accounts.isEmpty() -> stringResource(R.string.settings_cf_none_subtitle)
                    // With one account the address is the more useful second line; the name above
                    // it is often just the account's Cloudflare label.
                    accounts.size == 1 && accounts[0].email.isNotBlank() &&
                        accounts[0].email != accounts[0].name -> accounts[0].email
                    else -> stringResource(R.string.cf_resources_subtitle)
                },
                color = Ios.SecondaryLabel,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(20.dp))
        }

        if (accounts.isEmpty()) {
            EmptyBlock(
                icon = Icons.Default.CloudOff,
                title = stringResource(R.string.cloud_no_account_connected),
                body = stringResource(R.string.cf_resources_no_account_body),
            )
            Spacer(Modifier.height(LocalSystemBottomPadding.current + 24.dp))
            return@IosScreen
        }

        // ---- the accounts themselves ---------------------------------------------------------
        //
        // These do not open a resource list. They open the Cloud screen, which is where an account
        // is added, deployed to and removed -- so this section is a way BACK to the thing the rest
        // of the page is about, not a fourth resource type.
        SettingsSectionHeader(stringResource(R.string.cf_resources_accounts))
        SettingsGroup {
            accounts.forEachIndexed { index, account ->
                if (index > 0) Separator()
                SettingsRow(
                    title = account.email.ifEmpty { account.name },
                    subtitle = stringResource(R.string.cf_resources_manage_in_cloud),
                    icon = Icons.Default.Cloud,
                    tint = Ios.CloudflareOrange,
                    onClick = onOpenAccount,
                )
            }
        }

        // ---- one section per resource type, one row per account ------------------------------
        ResourceSection(
            header = stringResource(R.string.cf_resources_workers),
            icon = Icons.Default.Dns,
            tint = Ios.CloudflareOrange,
            accounts = accounts,
            labelFor = { stringResource(R.string.cf_resources_workers_of, it) },
            countFor = { counts[it.id]?.workers },
            onOpen = { onOpen(it, CloudResource.WORKERS) },
        )
        ResourceSection(
            header = stringResource(R.string.cf_resources_d1),
            icon = Icons.Default.Storage,
            tint = Ios.Blue,
            accounts = accounts,
            labelFor = { stringResource(R.string.cf_resources_d1_of, it) },
            countFor = { counts[it.id]?.d1 },
            onOpen = { onOpen(it, CloudResource.D1) },
        )
        ResourceSection(
            header = stringResource(R.string.cf_resources_kv),
            icon = Icons.Default.VpnKey,
            tint = Ios.Purple,
            accounts = accounts,
            labelFor = { stringResource(R.string.cf_resources_kv_of, it) },
            countFor = { counts[it.id]?.kv },
            onOpen = { onOpen(it, CloudResource.KV) },
        )

        SettingsFooter(stringResource(R.string.cf_resources_footer))
        Spacer(Modifier.height(LocalSystemBottomPadding.current + 24.dp))
    }
}

@Composable
private fun ResourceSection(
    header: String,
    icon: ImageVector,
    tint: Color,
    accounts: List<CloudAccount>,
    labelFor: @Composable (String) -> String,
    countFor: (CloudAccount) -> Int?,
    onOpen: (Int) -> Unit,
) {
    SettingsSectionHeader(header)
    SettingsGroup {
        accounts.forEachIndexed { index, account ->
            if (index > 0) Separator()
            HubRow(
                title = labelFor(account.email.ifEmpty { account.name }),
                icon = icon,
                tint = tint,
                count = countFor(account),
                onClick = { onOpen(index) },
            )
        }
    }
}

/**
 * A hub row: iOS's grouped row with the count where iOS puts the current value.
 *
 * `-1` is the failure sentinel from the fetch, and it shows as a dash rather than as a zero --
 * "the token cannot read this" and "there are none" are different answers, and only one of them
 * is fixed by deleting something.
 */
@Composable
private fun HubRow(
    title: String,
    icon: ImageVector,
    tint: Color,
    count: Int?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsGlyph(icon, tint)
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            color = Ios.Label,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        when {
            count == null -> CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = Ios.SecondaryLabel,
                strokeWidth = 1.5.dp,
            )
            count < 0 -> Text("—", color = Ios.SecondaryLabel, fontSize = 16.sp)
            else -> Text(count.toString(), color = Ios.SecondaryLabel, fontSize = 16.sp)
        }
        Spacer(Modifier.width(6.dp))
        HubChevron()
    }
}

/** The chevron points the way navigation goes, which is leftward in Persian. */
@Composable
private fun HubChevron() {
    Icon(
        Icons.Default.ChevronRight,
        contentDescription = null,
        tint = Ios.Chevron,
        modifier = Modifier
            .size(18.dp)
            .scale(
                scaleX = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f,
                scaleY = 1f,
            ),
    )
}

// =================================================================================================
// The three lists
// =================================================================================================

/** The desktop's `formatNum`: anything over 999 becomes "N.N هزار". */
private fun formatCount(n: Long): String =
    if (n > 999) String.format(java.util.Locale.US, "%.1f", n / 1000.0) + S(R.string.k) else n.toString()

/** Bytes as the dashboard shows them. */
private fun formatBytes(b: Long): String = when {
    b <= 0L -> "0 B"
    b < 1024L -> "$b B"
    b < 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f KB", b / 1024.0)
    else -> String.format(java.util.Locale.US, "%.1f MB", b / (1024.0 * 1024.0))
}

/**
 * Cloudflare's ISO-8601 as a plain date.
 *
 * Truncated rather than parsed. Every one of these fields is `2026-08-14T09:31:02.145Z` and the
 * only part worth a row is the day, so pulling the first ten characters avoids a formatter, a
 * locale, and the timezone question of what "the day" means for a UTC timestamp.
 */
private fun formatDay(iso: String): String = iso.take(10).takeIf { it.length == 10 } ?: ""

/**
 * One account's workers, with the numbers the desktop app has always shown here: the address the
 * worker answers on, and its requests, errors and p99 CPU over the last 24 hours.
 *
 * The stats and the script list come from two different Cloudflare APIs with different scopes, so
 * a token that can list scripts but not read analytics still gets a usable list -- the stats line
 * is simply omitted rather than showing zeroes that look like an idle worker.
 */
@Composable
fun WorkersListPage(account: CloudAccount, backLabel: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val cloudManager = remember { CloudManager(context) }
    val clipboard = LocalClipboardManager.current

    var snapshot by remember { mutableStateOf<CloudManager.WorkersSnapshot?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(account.id, reload) {
        isLoading = true
        failed = false
        val (ok, data) = cloudManager.getWorkersDetailed(account)
        if (ok) snapshot = data else failed = true
        isLoading = false
    }

    val workers = snapshot?.workers.orEmpty()
    val subdomain = snapshot?.subdomain.orEmpty()
    val statsAvailable = snapshot?.statsAvailable == true

    SelectableResourcePage(
        title = stringResource(R.string.cf_resources_workers),
        backLabel = backLabel,
        onBack = onBack,
        isLoading = isLoading,
        failed = failed,
        failureText = stringResource(R.string.workers_fetch_failed),
        ids = workers.map { it.name },
        emptyIcon = Icons.Default.Dns,
        emptyTitle = stringResource(R.string.workers_list_empty),
        emptyBody = stringResource(R.string.cf_resources_workers_empty_body),
        footer = stringResource(R.string.cf_resources_workers_footer),
        confirmTitle = stringResource(R.string.workers_delete_confirm_title),
        confirmBody = { names ->
            if (names.size == 1) {
                stringResource(R.string.workers_delete_confirm_desc) + "\n\n" + names.first()
            } else {
                stringResource(R.string.cf_resources_delete_many_desc, names.size)
            }
        },
        onDelete = { name -> cloudManager.deleteWorker(account, name) },
        onFinished = { reload++ },
        header = {
            SettingsGroup {
                AccountTotals(
                    workerCount = workers.size,
                    requests = workers.sumOf { it.requests },
                    errors = workers.sumOf { it.errors },
                    statsAvailable = statsAvailable,
                    subdomain = subdomain,
                )
            }
            Spacer(Modifier.height(18.dp))
        },
        row = { index, selectionMode, selected, toggle, requestDelete ->
            val worker = workers[index]
            val url = if (subdomain.isNotEmpty()) {
                "${worker.name}.$subdomain.workers.dev"
            } else {
                ""
            }
            ItemRow(
                icon = Icons.Default.Dns,
                tint = Ios.CloudflareOrange,
                name = worker.name,
                secondary = url,
                stats = buildList {
                    if (statsAvailable) {
                        add(
                            StatSpec(
                                Icons.Default.TrendingUp,
                                formatCount(worker.requests),
                                stringResource(R.string.cf_resources_request),
                                Ios.SecondaryLabel,
                            )
                        )
                        add(
                            StatSpec(
                                Icons.Default.ErrorOutline,
                                formatCount(worker.errors),
                                stringResource(R.string.cf_resources_error),
                                if (worker.errors > 0) Ios.Destructive else Ios.SecondaryLabel,
                            )
                        )
                        add(
                            StatSpec(
                                Icons.Default.Memory,
                                String.format(java.util.Locale.US, "%.1f", worker.cpu),
                                stringResource(R.string.cf_resources_ms),
                                Ios.SecondaryLabel,
                            )
                        )
                    }
                    formatDay(worker.modifiedOn).takeIf { it.isNotEmpty() }?.let {
                        add(StatSpec(Icons.Default.Schedule, it, "", Ios.SecondaryLabel))
                    }
                },
                selectionMode = selectionMode,
                selected = selected,
                onToggle = toggle,
                copyText = url.takeIf { it.isNotEmpty() },
                onCopy = {
                    clipboard.setText(AnnotatedString(url))
                    toast(context, context.getString(R.string.cf_resources_copied))
                },
                onDelete = requestDelete,
            )
        },
    )
}

/** One account's D1 databases: name, uuid, table count, size on disk and creation date. */
@Composable
fun D1ListPage(account: CloudAccount, backLabel: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val cloudManager = remember { CloudManager(context) }
    val clipboard = LocalClipboardManager.current

    var items by remember { mutableStateOf<List<CloudManager.D1Info>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(account.id, reload) {
        isLoading = true
        failed = false
        val (ok, list) = cloudManager.getD1Databases(account)
        if (ok) items = list else failed = true
        isLoading = false
    }

    SelectableResourcePage(
        title = stringResource(R.string.cf_resources_d1),
        backLabel = backLabel,
        onBack = onBack,
        isLoading = isLoading,
        failed = failed,
        failureText = stringResource(R.string.cf_resources_d1_failed),
        ids = items.map { it.uuid },
        emptyIcon = Icons.Default.Storage,
        emptyTitle = stringResource(R.string.cf_resources_d1_empty),
        emptyBody = stringResource(R.string.cf_resources_d1_empty_body),
        footer = stringResource(R.string.cf_resources_d1_footer),
        confirmTitle = stringResource(R.string.cf_resources_d1_delete_title),
        confirmBody = { ids ->
            if (ids.size == 1) {
                stringResource(R.string.cf_resources_d1_delete_desc) + "\n\n" +
                    (items.firstOrNull { it.uuid == ids.first() }?.name ?: ids.first())
            } else {
                stringResource(R.string.cf_resources_d1_delete_many_desc, ids.size)
            }
        },
        onDelete = { uuid -> cloudManager.deleteD1Database(account, uuid) },
        onFinished = { reload++ },
        row = { index, selectionMode, selected, toggle, requestDelete ->
            val db = items[index]
            ItemRow(
                icon = Icons.Default.Storage,
                tint = Ios.Blue,
                name = db.name,
                // The uuid is what a wrangler binding names, so it is the identifying line rather
                // than a decorative one -- and the thing worth copying.
                secondary = db.uuid,
                stats = buildList {
                    add(
                        StatSpec(
                            Icons.Default.TableChart,
                            db.tables.toString(),
                            stringResource(R.string.cf_resources_d1_tables),
                            Ios.SecondaryLabel,
                        )
                    )
                    add(StatSpec(Icons.Default.Memory, formatBytes(db.sizeBytes), "", Ios.SecondaryLabel))
                    formatDay(db.createdAt).takeIf { it.isNotEmpty() }?.let {
                        add(StatSpec(Icons.Default.Schedule, it, "", Ios.SecondaryLabel))
                    }
                },
                selectionMode = selectionMode,
                selected = selected,
                onToggle = toggle,
                copyText = db.uuid,
                onCopy = {
                    clipboard.setText(AnnotatedString(db.uuid))
                    toast(context, context.getString(R.string.cf_resources_copied))
                },
                onDelete = requestDelete,
            )
        },
    )
}

/**
 * One account's KV namespaces: the title the dashboard shows, the id a binding uses, and how many
 * keys it holds.
 *
 * The key count is fetched per namespace AFTER the list is on screen, because counting costs one
 * request each and blocking the list on N of them would leave the page spinning for the sake of a
 * number in the corner of a row.
 */
@Composable
fun KvListPage(account: CloudAccount, backLabel: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val cloudManager = remember { CloudManager(context) }
    val clipboard = LocalClipboardManager.current

    var items by remember { mutableStateOf<List<CloudManager.KvInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    val keyCounts: SnapshotStateMap<String, Pair<Int, Boolean>?> = remember { mutableStateMapOf() }

    LaunchedEffect(account.id, reload) {
        isLoading = true
        failed = false
        keyCounts.clear()
        val (ok, list) = cloudManager.getKvNamespaces(account)
        if (ok) items = list else failed = true
        isLoading = false
        // Sequential, for the same rate-limit reason the hub's counts are.
        for (ns in list) {
            keyCounts[ns.id] = cloudManager.getKvKeyCount(account, ns.id)
        }
    }

    SelectableResourcePage(
        title = stringResource(R.string.cf_resources_kv),
        backLabel = backLabel,
        onBack = onBack,
        isLoading = isLoading,
        failed = failed,
        failureText = stringResource(R.string.cf_resources_kv_failed),
        ids = items.map { it.id },
        emptyIcon = Icons.Default.VpnKey,
        emptyTitle = stringResource(R.string.cf_resources_kv_empty),
        emptyBody = stringResource(R.string.cf_resources_kv_empty_body),
        footer = stringResource(R.string.cf_resources_kv_footer),
        confirmTitle = stringResource(R.string.cf_resources_kv_delete_title),
        confirmBody = { ids ->
            if (ids.size == 1) {
                stringResource(R.string.cf_resources_kv_delete_desc) + "\n\n" +
                    (items.firstOrNull { it.id == ids.first() }?.title ?: ids.first())
            } else {
                stringResource(R.string.cf_resources_kv_delete_many_desc, ids.size)
            }
        },
        onDelete = { id -> cloudManager.deleteKvNamespace(account, id) },
        onFinished = { reload++ },
        row = { index, selectionMode, selected, toggle, requestDelete ->
            val ns = items[index]
            val count = keyCounts[ns.id]
            ItemRow(
                icon = Icons.Default.VpnKey,
                tint = Ios.Purple,
                name = ns.title,
                secondary = ns.id,
                stats = buildList {
                    if (keyCounts.containsKey(ns.id)) {
                        add(
                            StatSpec(
                                Icons.Default.Key,
                                when {
                                    count == null -> "—"
                                    count.second -> "${count.first}+"
                                    else -> count.first.toString()
                                },
                                stringResource(R.string.cf_resources_kv_keys),
                                Ios.SecondaryLabel,
                            )
                        )
                    }
                },
                selectionMode = selectionMode,
                selected = selected,
                onToggle = toggle,
                copyText = ns.id,
                onCopy = {
                    clipboard.setText(AnnotatedString(ns.id))
                    toast(context, context.getString(R.string.cf_resources_copied))
                },
                onDelete = requestDelete,
            )
        },
    )
}

// =================================================================================================
// The shared list frame
// =================================================================================================

/**
 * The frame all three lists share: the four page states, the selection mode, and the three ways
 * to delete.
 *
 * Written once because the three lists differ only in what a row LOOKS like and what deleting one
 * calls -- everything else (loading, failure, empty, select-all, the confirmation, the partial
 * failure report, the reload afterwards) is identical, and three copies of it is three places for
 * the empty state to be shown while a fetch is still running.
 *
 * Deletion is sequential and counts its failures rather than stopping at the first one. Cloudflare
 * refuses a delete per resource -- a KV namespace still bound to a live worker, say -- so half a
 * selection succeeding is the normal outcome, not an error, and the list is re-fetched afterwards
 * so what remains is what Cloudflare actually still has.
 */
@Composable
private fun SelectableResourcePage(
    title: String,
    backLabel: String,
    onBack: () -> Unit,
    isLoading: Boolean,
    failed: Boolean,
    failureText: String,
    ids: List<String>,
    emptyIcon: ImageVector,
    emptyTitle: String,
    emptyBody: String,
    footer: String,
    confirmTitle: String,
    confirmBody: @Composable (List<String>) -> String,
    onDelete: suspend (String) -> Pair<Boolean, String>,
    onFinished: () -> Unit,
    header: @Composable (() -> Unit)? = null,
    row: @Composable (
        index: Int,
        selectionMode: Boolean,
        selected: Boolean,
        toggle: () -> Unit,
        requestDelete: () -> Unit,
    ) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectionMode by remember { mutableStateOf(false) }
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    var pendingDelete by remember { mutableStateOf<List<String>?>(null) }
    var busy by remember { mutableStateOf(false) }

    // A refetch can retire an id that is still ticked. Dropping those here keeps "delete 3" from
    // meaning "delete 2 and one that is already gone".
    LaunchedEffect(ids) {
        selected.keys.retainAll(ids.toSet())
        if (ids.isEmpty()) selectionMode = false
    }

    val selectedIds = ids.filter { selected[it] == true }
    val canSelect = !isLoading && !failed && ids.isNotEmpty()

    IosScreen(
        title = title,
        onBack = onBack,
        backLabel = backLabel,
        trailing = if (!canSelect) null else ({
            Text(
                if (selectionMode) stringResource(R.string.common_cancel)
                else stringResource(R.string.cf_resources_select),
                color = Ios.Label,
                fontSize = 16.sp,
                fontWeight = if (selectionMode) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .clickable(enabled = !busy) {
                        selectionMode = !selectionMode
                        if (!selectionMode) selected.clear()
                    }
                    .padding(vertical = 6.dp, horizontal = 2.dp),
            )
        }),
    ) {
        Spacer(Modifier.height(8.dp))
        when {
            isLoading -> Box(
                modifier = Modifier.fillMaxWidth().padding(top = 90.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = Ios.Blue,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(26.dp),
                )
            }

            failed -> EmptyBlock(
                icon = Icons.Default.ErrorOutline,
                title = failureText,
                body = stringResource(R.string.cf_resources_failed_body),
                tint = Ios.Destructive,
            )

            ids.isEmpty() -> EmptyBlock(icon = emptyIcon, title = emptyTitle, body = emptyBody)

            else -> {
                if (header != null && !selectionMode) header()

                // Select-all rides above the list in selection mode, which is where iOS puts it.
                AnimatedVisibility(visible = selectionMode) {
                    Column {
                        SettingsGroup {
                            val allOn = selectedIds.size == ids.size
                            SettingsActionRow(
                                label = if (allOn) stringResource(R.string.cf_resources_select_none)
                                else stringResource(R.string.cf_resources_select_all),
                                icon = Icons.Default.Check,
                                enabled = !busy,
                                onClick = {
                                    if (allOn) selected.clear()
                                    else ids.forEach { selected[it] = true }
                                },
                            )
                        }
                        Spacer(Modifier.height(18.dp))
                    }
                }

                SettingsGroup {
                    ids.forEachIndexed { index, id ->
                        if (index > 0) Separator()
                        row(
                            index,
                            selectionMode,
                            selected[id] == true,
                            { selected[id] = selected[id] != true },
                            { pendingDelete = listOf(id) },
                        )
                    }
                    if (!selectionMode) {
                        Separator()
                        SettingsActionRow(
                            label = stringResource(R.string.cf_resources_delete_all),
                            icon = Icons.Default.Delete,
                            tint = Ios.Destructive,
                            busy = busy,
                            onClick = { pendingDelete = ids },
                        )
                    }
                }

                // In selection mode the destructive action follows the selection instead, and
                // says how many it is about to take.
                AnimatedVisibility(visible = selectionMode) {
                    Column {
                        Spacer(Modifier.height(18.dp))
                        SettingsGroup {
                            SettingsActionRow(
                                label = stringResource(
                                    R.string.cf_resources_delete_n,
                                    selectedIds.size,
                                ),
                                icon = Icons.Default.Delete,
                                tint = Ios.Destructive,
                                busy = busy,
                                enabled = selectedIds.isNotEmpty(),
                                onClick = { pendingDelete = selectedIds },
                            )
                        }
                    }
                }

                SettingsFooter(footer)
            }
        }
        Spacer(Modifier.height(LocalSystemBottomPadding.current + 24.dp))
    }

    val targets = pendingDelete
    if (targets != null) {
        IosAlert(
            title = confirmTitle,
            message = confirmBody(targets),
            onDismiss = { pendingDelete = null },
            actions = listOf(
                IosAlertAction(stringResource(R.string.common_cancel), { pendingDelete = null }),
                IosAlertAction(
                    label = stringResource(R.string.node_delete),
                    destructive = true,
                    onClick = {
                        pendingDelete = null
                        busy = true
                        scope.launch {
                            var failures = 0
                            var lastError = ""
                            for (id in targets) {
                                val res = onDelete(id)
                                if (!res.first) {
                                    failures++
                                    lastError = res.second
                                }
                            }
                            busy = false
                            selectionMode = false
                            selected.clear()
                            toast(
                                context,
                                when {
                                    failures == 0 ->
                                        context.getString(R.string.workers_delete_success)
                                    failures == targets.size && targets.size == 1 -> lastError
                                    else -> context.getString(
                                        R.string.cf_resources_delete_partial,
                                        targets.size - failures,
                                        failures,
                                    )
                                },
                            )
                            onFinished()
                        }
                    },
                ),
            ),
        )
    }
}

// =================================================================================================
// Row parts
// =================================================================================================

/** One `↗ 1.2 هزار درخواست` cell on a row. */
private data class StatSpec(
    val icon: ImageVector,
    val value: String,
    val unit: String,
    val tint: Color,
)

/**
 * A resource row, in both its modes.
 *
 * In selection mode the leading glyph becomes a tick circle and the whole row toggles; the copy
 * and delete controls go away, because a row that both selects and deletes on tap is a row that
 * deletes something the user meant to tick.
 */
@Composable
private fun ItemRow(
    icon: ImageVector,
    tint: Color,
    name: String,
    secondary: String,
    stats: List<StatSpec>,
    selectionMode: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    copyText: String?,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (selectionMode) Modifier.clickable(onClick = onToggle) else Modifier)
            .heightIn(min = 44.dp)
            .padding(start = 16.dp, end = if (selectionMode) 16.dp else 4.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            SelectionTick(selected)
        } else {
            SettingsGlyph(icon, tint)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            LtrText(name, color = TextPrimary, fontSize = 15.sp, weight = FontWeight.SemiBold)
            if (secondary.isNotEmpty()) {
                LtrText(secondary, color = Ios.SecondaryLabel, fontSize = 11.sp)
            }
            if (stats.isNotEmpty()) {
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    stats.forEachIndexed { index, spec ->
                        if (index > 0) Spacer(Modifier.width(12.dp))
                        StatChip(spec)
                    }
                }
            }
        }
        if (!selectionMode) {
            if (copyText != null) {
                IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.cf_resources_copy),
                        tint = Ios.SecondaryLabel,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.node_delete),
                    tint = Ios.SecondaryLabel,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}

/** iOS's selection circle: an empty ring, or a filled blue disc with a tick. */
@Composable
private fun SelectionTick(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .then(
                if (selected) Modifier.background(Ios.Blue)
                else Modifier.border(1.5.dp, Ios.Chevron, CircleShape)
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

@Composable
private fun StatChip(spec: StatSpec) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(spec.icon, contentDescription = null, tint = spec.tint, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(3.dp))
        LtrText(spec.value, color = spec.tint, fontSize = 11.sp)
        if (spec.unit.isNotEmpty()) {
            Spacer(Modifier.width(3.dp))
            Text(spec.unit, color = Ios.SecondaryLabel, fontSize = 11.sp, maxLines = 1)
        }
    }
}

/**
 * The workers page's header totals: how many, and the 24h request and error counts summed across
 * them, plus the `*.workers.dev` subdomain they all answer under.
 *
 * Errors go red only when there ARE errors. A permanently red cell reading "0" trains the eye to
 * ignore the one cell that should mean something.
 */
@Composable
private fun AccountTotals(
    workerCount: Int,
    requests: Long,
    errors: Long,
    statsAvailable: Boolean,
    subdomain: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TotalCell(
                stringResource(R.string.cf_resources_workers),
                workerCount.toString(),
                Ios.Label,
                Modifier.weight(1f),
            )
            TotalDivider()
            TotalCell(
                stringResource(R.string.cf_resources_requests),
                if (statsAvailable) formatCount(requests) else "—",
                if (statsAvailable) Ios.Green else Ios.SecondaryLabel,
                Modifier.weight(1f),
            )
            TotalDivider()
            TotalCell(
                stringResource(R.string.cf_resources_errors),
                if (statsAvailable) formatCount(errors) else "—",
                when {
                    !statsAvailable -> Ios.SecondaryLabel
                    errors > 0 -> Ios.Destructive
                    else -> Ios.Label
                },
                Modifier.weight(1f),
            )
        }
        if (subdomain.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(Ios.Separator))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.cf_resources_subdomain),
                    color = Ios.Label,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f),
                )
                LtrText(
                    "$subdomain.workers.dev",
                    color = Ios.SecondaryLabel,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun TotalCell(label: String, value: String, valueColor: Color, modifier: Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        LtrText(value, color = valueColor, fontSize = 17.sp, weight = FontWeight.SemiBold)
        Spacer(Modifier.height(1.dp))
        Text(label, color = Ios.SecondaryLabel, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun TotalDivider() {
    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .width(0.5.dp)
            .height(28.dp)
            .background(Ios.Separator)
    )
}

/**
 * The shape iOS gives an empty list: a large dimmed glyph, the reason, and what to do about it.
 * A centred grey sentence on its own says only THAT the list is empty, never why.
 */
@Composable
private fun EmptyBlock(
    icon: ImageVector,
    title: String,
    body: String,
    tint: Color = Ios.SecondaryLabel,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 56.dp, start = 32.dp, end = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.07f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            title,
            color = Ios.Label,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            color = Ios.SecondaryLabel,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Text that stays left-to-right inside the Persian layout.
 *
 * Every identifier on these screens -- a script name, a uuid, a dotted hostname, a formatted
 * count -- is a Latin run. Left to the page's direction they right-align and reorder around their
 * own separators, so `bpb.sub.workers.dev` renders with its labels reversed.
 */
@Composable
private fun LtrText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    weight: FontWeight = FontWeight.Normal,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Text(
            text,
            color = color,
            fontSize = fontSize,
            fontWeight = weight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun toast(context: android.content.Context, message: String) {
    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
}
