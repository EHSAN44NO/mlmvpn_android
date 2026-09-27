package com.mlmvpn.scanner.ui.configstudio

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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.studio.StudioState
import com.mlmvpn.scanner.data.studio.StudioStore
import com.mlmvpn.scanner.data.studio.api.AuditEntry
import com.mlmvpn.scanner.data.studio.api.StudioHttpApi
import com.mlmvpn.scanner.data.studio.api.StudioResult
import com.mlmvpn.scanner.ui.configstudio.design.ChoiceRow
import com.mlmvpn.scanner.ui.configstudio.design.EmptyState
import com.mlmvpn.scanner.ui.configstudio.design.StudioIcons
import com.mlmvpn.scanner.ui.configstudio.design.StudioSeparator
import com.mlmvpn.scanner.ui.configstudio.parts.InfoCard
import com.mlmvpn.scanner.ui.configstudio.parts.ProblemCard
import com.mlmvpn.scanner.ui.configstudio.parts.StudioListRow
import com.mlmvpn.scanner.ui.configstudio.parts.WizardBusy
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosRefreshIndicator
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.settings.rememberIosRefreshState
import com.mlmvpn.scanner.utils.S
import kotlinx.coroutines.launch

/**
 * What a person did, on one installation.
 *
 * Every mutating call has been writing one of these rows since the control plane existed, with the
 * key's label as the actor and both the before and after state. Nothing read them until now.
 *
 * **Scoped to one account, and the screen says so** rather than showing a merged feed that is
 * quietly incomplete. With an uncapped number of Cloudflare accounts a merged log is one request per
 * account per page, and the moment one of them fails to answer the feed is wrong in a way nobody can
 * see (plan §A.3, §A.9).
 *
 * The row that matters most here is `auth.bootstrap`. A key appearing that the operator did not
 * create is the **only** signal they will get that their Cloudflare token has leaked — whoever holds
 * that token can already replace the whole worker — so it is called out rather than left as one line
 * among many.
 */
@Composable
fun StudioAuditScreen(
    store: StudioStore,
    state: StudioState,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accounts = remember(state.installations) { store.installedAccounts() }

    var shard by remember { mutableStateOf(accounts.firstOrNull()?.id) }
    val rows = remember { mutableStateListOf<AuditEntry>() }
    var cursor by remember { mutableStateOf<Long?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    /** [quiet]: a pull-to-refresh, which keeps the rows on screen under its own spinner. */
    suspend fun load(more: Boolean, quiet: Boolean = false) {
        val acc = accounts.firstOrNull { it.id == shard } ?: return
        if (more && cursor == null) return
        if (more) loadingMore = true else if (!quiet) loading = true
        error = null
        when (val res = StudioHttpApi(context, acc).audit(cursor = if (more) cursor else null)) {
            is StudioResult.Ok -> {
                if (!more) rows.clear()
                rows.addAll(res.value.items)
                cursor = res.value.nextCursor
            }
            is StudioResult.Err -> error = messageFor(context, res.error)
        }
        loading = false; loadingMore = false
    }

    LaunchedEffect(shard) { cursor = null; load(false) }
    val refresh = rememberIosRefreshState { cursor = null; load(more = false, quiet = true) }

    // scrollable = false: the body holds a LazyColumn, which gets infinite height inside a
    // vertically scrolling Column and crashes.
    IosScreen(
        title = S(R.string.studio_audit),
        onBack = onBack,
        // Reached from «بیشتر» and from «تنظیمات», so the chevron alone rather than a wrong name.
        backLabel = null,
        scrollable = false,
    ) {
        // Everything in the one list, so the pull that reloads it works from anywhere on the page.
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f).nestedScroll(refresh.connection)) {
            item { IosRefreshIndicator(refresh) }
            item {
                Spacer(Modifier.height(10.dp))
                if (accounts.size > 1) {
                    // Named, as on «آمار». The picker was a card of accounts with nothing saying
                    // what it picked -- three e-mail addresses under a title bar.
                    SettingsSectionHeader(S(R.string.studio_accounts))
                    SettingsGroup {
                        accounts.forEachIndexed { index, acc ->
                            if (index > 0) StudioSeparator()
                            ChoiceRow(
                                title = acc.name.ifEmpty { acc.email },
                                selected = shard == acc.id,
                                onClick = { shard = acc.id },
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                InfoCard(S(R.string.studio_audit_scope))
                Spacer(Modifier.height(8.dp))
                error?.let { ProblemCard(it); Spacer(Modifier.height(8.dp)) }
            }

            when {
                loading -> item { WizardBusy(S(R.string.studio_loading)) }
                rows.isEmpty() -> item {
                    EmptyState(icon = StudioIcons.Audit, title = S(R.string.studio_audit_empty))
                }
                else -> {
                    items(rows, key = { it.id }) { entry -> AuditRow(entry) }
                    item {
                        if (cursor != null) {
                            Spacer(Modifier.height(6.dp))
                            SettingsGroup {
                                SettingsActionRow(
                                    label = S(R.string.studio_audit_more),
                                    icon = StudioIcons.Activity,
                                    tint = Ios.Blue,
                                    busy = loadingMore,
                                    enabled = !loadingMore,
                                ) { scope.launch { load(true) } }
                            }
                        }
                        Spacer(Modifier.height(28.dp))
                    }
                }
            }
        }
    }
}

/**
 * One entry, on the same card every other list in this feature uses.
 *
 * It had no card at all: a bare column at a 20dp inset, so a page whose every other element was a
 * glass surface scrolled into a wall of text lying directly on the wallpaper.
 */
@Composable
private fun AuditRow(entry: AuditEntry) {
    // The one row an operator must not scroll past. Everything else is a record; this is a warning.
    val isBootstrap = entry.action == "auth.bootstrap"
    StudioListRow {
        Column(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    actionLabel(entry.action),
                    color = if (isBootstrap) Ios.Orange else Ios.Label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text(whenText(entry.ts), color = Ios.SecondaryLabel, fontSize = 12.sp)
            }
            Spacer(Modifier.height(3.dp))
            Text(
                entry.actor.ifBlank { S(R.string.studio_device_unknown) },
                color = Ios.SecondaryLabel,
                fontSize = 12.sp,
            )
            if (isBootstrap) {
                Spacer(Modifier.height(4.dp))
                Text(S(R.string.studio_audit_bootstrap_note), color = Ios.Orange, fontSize = 11.sp)
            }
        }
    }
}

/**
 * The engine's stable action code, in the app's own words.
 *
 * Mapped rather than printed, for the same reason error codes are: the code is a machine name that
 * must not change, and what a person reads is a sentence that can.
 */
@Composable
private fun actionLabel(action: String): String = S(
    when (action) {
        "user.create" -> R.string.studio_audit_user_create
        "user.update" -> R.string.studio_audit_user_update
        "user.delete" -> R.string.studio_audit_user_delete
        "user.renew" -> R.string.studio_audit_user_renew
        "subscription.rotate" -> R.string.studio_audit_sub_rotate
        "config.create" -> R.string.studio_audit_config_create
        "config.delete" -> R.string.studio_audit_config_delete
        "config.rotate" -> R.string.studio_audit_config_rotate
        "plan.create", "plan.update" -> R.string.studio_audit_plan_save
        "plan.apply" -> R.string.studio_audit_plan_apply
        "plan.archive" -> R.string.studio_audit_plan_archive
        "auth.bootstrap" -> R.string.studio_audit_key_new
        "auth.revoke_key" -> R.string.studio_audit_key_revoked
        "settings.update" -> R.string.studio_audit_settings
        else -> R.string.studio_audit_other
    }
)

// `whenText` lives in StudioDashboardScreen.kt. It was copied here verbatim, and one of the two
// had to go before either could be shared with the user page.
