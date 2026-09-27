package com.mlmvpn.scanner.ui.sublink

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.data.CloudManager
import com.mlmvpn.scanner.data.GroupManager
import com.mlmvpn.scanner.data.NodeManager
import com.mlmvpn.scanner.data.SubscriptionManager
import com.mlmvpn.scanner.engines.subgenerator.SubGenAccountData
import com.mlmvpn.scanner.engines.subgenerator.SubGenDeployer
import com.mlmvpn.scanner.engines.subgenerator.SubGenManager
import com.mlmvpn.scanner.engines.subgenerator.SubLinkConfig
import com.mlmvpn.scanner.ui.faCount
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosAlert
import com.mlmvpn.scanner.ui.settings.IosAlertAction
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGlyph
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.theme.BadgeShape
import com.mlmvpn.scanner.ui.theme.ControlShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

// =================================================================================================
// «لینک ساب» -- a Cloudflare Worker of the user's own that serves their configs as a subscription
// URL other clients can import.
//
// The screen lived in `engines/subgenerator/`, the only one in the app outside `ui/`, and it was
// the last one still written entirely in the pre-redesign vocabulary: a raw Material
// `ScrollableTabRow`, three raw `AlertDialog`s, a top-level `Color(0xFF121212)` that punched an
// opaque hole through the wallpaper, hardcoded Material-2 error hex, and two untranslated English
// toasts. What was wrapped around it was an `IosScreen`, and nothing inside it.
// =================================================================================================

private sealed class SubLinkPage {
    object List : SubLinkPage()

    /** Create when the draft's `editing` is null, edit otherwise. One form, two jobs. */
    object Form : SubLinkPage()
    object FormGroup : SubLinkPage()
    data class Detail(val slug: String) : SubLinkPage()
    object Help : SubLinkPage()
}

@Composable
fun SubLinkScreen(
    cloudManager: CloudManager,
    nodeManager: NodeManager,
    onNavigateToCloud: () -> Unit,
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val subGenManager = remember { SubGenManager(context) }
    val deployer = remember { SubGenDeployer(context) }
    // One instance for the screen. This used to be constructed inside the list's `items` lambda,
    // so every row built a GroupManager of its own.
    val groupManager = remember { GroupManager(context) }
    val subscriptionManager = remember { SubscriptionManager(context) }

    val accounts by cloudManager.accountsFlow.collectAsState()

    var selectedAccountId by remember { mutableStateOf<String?>(null) }
    val activeAccount = accounts.find { it.id == selectedAccountId } ?: accounts.firstOrNull()

    LaunchedEffect(activeAccount?.id) {
        if (activeAccount != null && selectedAccountId != activeAccount.id) {
            selectedAccountId = activeAccount.id
        }
    }

    var isChecking by remember { mutableStateOf(true) }
    var accountData by remember { mutableStateOf<SubGenAccountData?>(null) }
    var subLinks by remember { mutableStateOf(emptyList<SubLinkConfig>()) }

    var isDeploying by remember { mutableStateOf(false) }
    var deployProgress by remember { mutableStateOf(0) }
    var deployStatus by remember { mutableStateOf("") }
    var deployError by remember { mutableStateOf<String?>(null) }

    var page by remember { mutableStateOf<SubLinkPage>(SubLinkPage.List) }
    val draft = remember { SubLinkDraft() }
    var snack by remember { mutableStateOf<String?>(null) }

    fun reloadLinks() {
        val id = activeAccount?.id ?: return
        subLinks = subGenManager.getSubLinks().filter { it.accountId == id || it.accountId.isEmpty() }
    }

    LaunchedEffect(activeAccount) {
        if (activeAccount != null) {
            isChecking = true
            val data = subGenManager.checkWorkerExistsOnline(activeAccount)
            if (data != null) {
                subGenManager.syncSubLinksOnline(activeAccount, data)
            }
            reloadLinks()
            accountData = data
            isChecking = false
        } else {
            isChecking = false
            accountData = null
            subLinks = emptyList()
        }
    }

    LaunchedEffect(snack) {
        if (snack != null) {
            delay(2600)
            snack = null
        }
    }

    fun deploy() {
        val account = activeAccount ?: return
        isDeploying = true
        deployProgress = 0
        scope.launch {
            val result = deployer.deploySubWorker(account) { prog, stat ->
                scope.launch(Dispatchers.Main) {
                    deployProgress = prog
                    deployStatus = stat
                }
            }
            if (result.first) {
                accountData = subGenManager.getAccountData(account.id)
            } else {
                deployError = result.second
            }
            isDeploying = false
        }
    }

    androidx.activity.compose.BackHandler(enabled = page != SubLinkPage.List) {
        page = when (page) {
            is SubLinkPage.FormGroup -> SubLinkPage.Form
            else -> SubLinkPage.List
        }
    }

    // ---- pushed pages ----------------------------------------------------------------
    val data = accountData
    val account = activeAccount

    when (val current = page) {
        is SubLinkPage.Form -> {
            if (data != null && account != null) {
                SubLinkFormScreen(
                    draft = draft,
                    existingSlugs = subLinks.map { it.slug },
                    subGenManager = subGenManager,
                    accountData = data,
                    account = account,
                    onOpenGroupPicker = { page = SubLinkPage.FormGroup },
                    onDone = { message ->
                        reloadLinks()
                        snack = message
                        page = SubLinkPage.List
                    },
                    onBack = { page = SubLinkPage.List },
                )
                return
            }
            page = SubLinkPage.List
        }

        is SubLinkPage.FormGroup -> {
            SubLinkGroupScreen(
                nodeManager = nodeManager,
                groupManager = groupManager,
                subscriptionManager = subscriptionManager,
                selectedId = draft.group?.id,
                onSelect = { draft.group = it },
                onBack = { page = SubLinkPage.Form },
            )
            return
        }

        is SubLinkPage.Detail -> {
            val link = subLinks.firstOrNull { it.slug == current.slug }
            if (link != null && data != null && account != null) {
                SubLinkDetailScreen(
                    link = link,
                    accountData = data,
                    account = account,
                    subGenManager = subGenManager,
                    nodeManager = nodeManager,
                    groupManager = groupManager,
                    onEdit = {
                        draft.name = link.name
                        draft.slug = link.slug
                        draft.expiryDays = expiryDaysOf(link)
                        draft.editing = link.slug
                        draft.group = buildSubGenGroups(
                            nodeManager.nodes,
                            groupManager.cloudGroups,
                            groupManager.scannerGroups,
                            subscriptionManager.subscriptionsFlow.value,
                        ).firstOrNull { it.id == link.mappedGroupName }
                        page = SubLinkPage.Form
                    },
                    onChanged = { message ->
                        reloadLinks()
                        snack = message
                    },
                    onDeleted = { message ->
                        reloadLinks()
                        snack = message
                        page = SubLinkPage.List
                    },
                    onBack = { page = SubLinkPage.List },
                )
                return
            }
            // The row it was opened from is gone underneath it.
            page = SubLinkPage.List
        }

        is SubLinkPage.Help -> {
            SubLinkHelpScreen(onBack = { page = SubLinkPage.List })
            return
        }

        is SubLinkPage.List -> Unit
    }

    // ---- the list page ---------------------------------------------------------------
    IosScreen(
        title = S(R.string.sub_link),
        onBack = onBack,
        backLabel = S(R.string.home_r2),
        trailing = {
            if (account != null && data != null) {
                androidx.compose.material3.Icon(
                    Icons.Default.HelpOutline,
                    contentDescription = S(R.string.guide),
                    tint = Ios.Label,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .clickable { page = SubLinkPage.Help }
                        .padding(5.dp),
                )
            }
        },
    ) {
        Spacer(Modifier.height(10.dp))

        // The account strip. Was a Material `ScrollableTabRow` painted `SurfaceDark` with a blue
        // indicator -- the one tab bar left in an app that uses chips for exactly this everywhere
        // else.
        if (accounts.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                accounts.forEachIndexed { index, item ->
                    val isSelected = account?.id == item.id
                    Box(
                        modifier = Modifier
                            .clip(BadgeShape)
                            .background(
                                if (isSelected) Color.White.copy(alpha = 0.20f)
                                else Color.White.copy(alpha = 0.07f)
                            )
                            .clickable { selectedAccountId = item.id }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(
                            item.email.ifEmpty { S(R.string.account_r2) + faCount(index + 1) },
                            color = if (isSelected) Ios.Label else Ios.SecondaryLabel,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        when {
            // ---- 1 · no Cloudflare account -------------------------------------------
            account == null -> {
                StateBlock(
                    icon = { SettingsGlyph(Icons.Default.CloudOff, Ios.Gray) },
                    title = S(R.string.no_cloudflare_account_connected),
                    body = S(R.string.sub_link_runs_on_a_cloudflare_worker),
                )
                SettingsGroup {
                    SettingsActionRow(
                        label = S(R.string.go_to_the_cloud_section),
                        icon = Icons.Default.Cloud,
                        tint = Ios.CloudflareOrange,
                        onClick = onNavigateToCloud,
                    )
                }
            }

            // ---- 2 · checking --------------------------------------------------------
            isChecking -> {
                Spacer(Modifier.height(60.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Ios.SecondaryLabel,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(S(R.string.checking_the_worker_s_status), color = Ios.SecondaryLabel, fontSize = 14.sp)
                }
            }

            // ---- 3 · worker not deployed ---------------------------------------------
            data == null -> {
                StateBlock(
                    icon = { SettingsGlyph(Icons.Default.CloudUpload, Ios.Orange) },
                    title = S(R.string.the_sub_link_worker_is_not_deployed),
                    body = S(R.string.it_is_installed_once_on_your_cloudflare),
                )
                if (isDeploying) {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                deployStatus.ifBlank { S(R.string.deploying) },
                                color = Ios.SecondaryLabel,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                faCount(deployProgress) + S(R.string.str_r2),
                                color = Ios.SecondaryLabel,
                                fontSize = 12.sp,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = deployProgress / 100f,
                            modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                            color = Ios.Blue,
                            trackColor = Color.White.copy(alpha = 0.14f),
                        )
                    }
                } else {
                    SettingsGroup {
                        SettingsActionRow(
                            label = S(R.string.set_up_the_worker),
                            icon = Icons.Default.CloudUpload,
                            tint = Ios.Blue,
                            onClick = ::deploy,
                        )
                    }
                }
            }

            // ---- 4 · the links -------------------------------------------------------
            else -> {
                SettingsSectionHeader(
                    if (subLinks.isEmpty()) S(R.string.your_links)
                    else faCount(subLinks.size) + S(R.string.sub_links)
                )
                if (subLinks.isEmpty()) {
                    SettingsGroup {
                        SettingsActionRow(
                            label = S(R.string.create_a_sub_link),
                            icon = Icons.Default.Add,
                            tint = Ios.Blue,
                        ) {
                            draft.reset(freshSlug())
                            page = SubLinkPage.Form
                        }
                    }
                    SettingsFooter(
                        S(R.string.you_have_not_created_a_link_yet) +
                            S(R.string.to_any_app_and_updates_itself_when) +
                            S(R.string.you_only_have_to_give_it_to)
                    )
                } else {
                    SettingsGroup {
                        subLinks.forEachIndexed { index, link ->
                            if (index > 0) Separator()
                            SubLinkRow(link) { page = SubLinkPage.Detail(link.slug) }
                        }
                        Separator()
                        SettingsActionRow(
                            label = S(R.string.new_sub_link),
                            icon = Icons.Default.Add,
                            tint = Ios.Blue,
                        ) {
                            draft.reset(freshSlug())
                            page = SubLinkPage.Form
                        }
                    }
                    SettingsFooter(
                        S(R.string.each_link_s_configs_are_updated_on) +
                            S(R.string.tap_a_link_to_see_its_address)
                    )
                }
            }
        }

        AnimatedVisibility(visible = snack != null) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                com.mlmvpn.scanner.ui.GatewaySnack(snack.orEmpty())
            }
        }

        Spacer(Modifier.height(40.dp))
    }

    val failure = deployError
    if (failure != null) {
        // Was a raw Material AlertDialog holding a one-item LazyColumn and a single "باشه" -- the
        // deploy takes a minute and fails for transient reasons (a subdomain not yet propagated,
        // a rate limit), so the one thing it must offer is another go.
        IosAlert(
            title = S(R.string.the_worker_could_not_be_deployed),
            message = failure,
            onDismiss = { deployError = null },
            actions = listOf(
                IosAlertAction(S(R.string.close_r2), onClick = { deployError = null }),
                IosAlertAction(
                    S(R.string.try_again_2_r2),
                    onClick = { deployError = null; deploy() },
                    preferred = true,
                ),
            ),
        )
    }
}

/** One link in the list: what it is, where it points, and whether it is still alive. */
@Composable
private fun SubLinkRow(link: SubLinkConfig, onClick: () -> Unit) {
    val (expiry, expiryTone) = expiryLabel(link.expiryTimestamp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsGlyph(Icons.Default.Link, if (expiryTone == Ios.Red) Ios.Gray else Ios.Blue)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                link.name,
                color = Ios.Label,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                link.mappedGroupName?.let { groupShortName(it) } ?: S(R.string.no_group),
                color = Ios.SecondaryLabel,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (expiry != null) {
            Text(
                expiry,
                color = expiryTone,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(BadgeShape)
                    .background(expiryTone.copy(alpha = 0.14f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        androidx.compose.material3.Icon(
            Icons.Default.ChevronLeft,
            contentDescription = null,
            tint = Ios.Chevron,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** The heading + explanation the three pre-dashboard states share. */
@Composable
private fun StateBlock(icon: @Composable () -> Unit, title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon()
        Spacer(Modifier.height(14.dp))
        Text(
            title,
            color = Ios.Label,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            color = Ios.SecondaryLabel,
            fontSize = 13.sp,
            lineHeight = 21.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

// =================================================================================================
// Shared helpers
// =================================================================================================

/** A short, URL-safe id for a fresh link. */
fun freshSlug(): String = java.util.UUID.randomUUID().toString().substring(0, 8)

/**
 * How long a link has left.
 *
 * An expired link used to render as "انقضا: ۰ روز دیگر" in red -- literally counting down past
 * zero and stopping, which reads as "expires today" rather than "already dead".
 */
@Composable
fun expiryLabel(expiryTimestamp: Long): Pair<String?, Color> {
    if (expiryTimestamp <= 0L) return null to Ios.SecondaryLabel
    val remaining = expiryTimestamp - System.currentTimeMillis()
    if (remaining <= 0L) return S(R.string.expired) to Ios.Red
    val days = remaining / (1000L * 60 * 60 * 24)
    return when {
        days < 1 -> S(R.string.today) to Ios.Orange
        days < 7 -> faCount(days.toInt()) + S(R.string.days_r2) to Ios.Orange
        else -> faCount(days.toInt()) + S(R.string.days_r2) to Ios.SecondaryLabel
    }
}

/** Days left, as the form's numeric field wants them. Empty when the link never expires. */
fun expiryDaysOf(link: SubLinkConfig): String {
    if (link.expiryTimestamp <= 0L) return ""
    val days = (link.expiryTimestamp - System.currentTimeMillis()) / (1000L * 60 * 60 * 24)
    return if (days > 0) days.toString() else ""
}

/**
 * The readable half of a composite group id.
 *
 * The list only has room for one line, and `manual:Manual:ایران ۱` is not it. The detail page
 * shows the resolved group properly; this is the summary.
 */
fun groupShortName(mappedGroupName: String): String {
    val parts = mappedGroupName.split(":")
    if (parts.size < 2) return mappedGroupName
    val tail = parts.getOrNull(2)?.takeIf { it != "null" && it.isNotBlank() }
    return when (parts[0]) {
        "manual" -> tail ?: parts[1]
        "cloud" -> S(R.string.cloud_r2) + (tail ?: parts[1])
        "scanner" -> S(R.string.scanner) + (tail ?: parts[1])
        else -> mappedGroupName
    }
}
