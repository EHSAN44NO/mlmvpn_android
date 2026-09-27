package com.mlmvpn.scanner.ui.configstudio

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.PanelBuild
import com.mlmvpn.scanner.data.studio.StudioAdmin
import com.mlmvpn.scanner.data.studio.StudioDeployer
import com.mlmvpn.scanner.data.studio.StudioDomain
import com.mlmvpn.scanner.data.studio.StudioPermission
import com.mlmvpn.scanner.data.studio.StudioReachability
import com.mlmvpn.scanner.data.studio.StudioDiscovery
import com.mlmvpn.scanner.data.studio.StudioStore
import com.mlmvpn.scanner.data.studio.api.StudioHttpApi
import com.mlmvpn.scanner.data.studio.api.StudioResult
import com.mlmvpn.scanner.data.studio.domain.StudioInstallation
import com.mlmvpn.scanner.ui.configstudio.design.NoticeCard
import com.mlmvpn.scanner.ui.configstudio.design.StudioIcons
import com.mlmvpn.scanner.ui.configstudio.parts.InfoCard
import com.mlmvpn.scanner.ui.configstudio.parts.PayloadCard
import com.mlmvpn.scanner.ui.configstudio.parts.ProblemCard
import com.mlmvpn.scanner.ui.configstudio.parts.WizardBusy
import com.mlmvpn.scanner.ui.configstudio.parts.WizardField
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
 * One shard of the fleet: what is installed on this Cloudflare account, and everything that is done
 * to that account rather than to the fleet.
 *
 * This is where the per-installation actions moved to. They used to sit on a Settings screen that
 * addressed `installedAccounts().first()` — correct with one account and silently wrong with two,
 * because "repair the engine" would repair whichever account happened to sort first while appearing
 * to be about all of them.
 *
 * The subscriber page's wording lives here too, and it belongs here rather than in a global
 * settings screen for the same reason: `/p/{token}` is served by **that** shard's worker and reads
 * **that** shard's `settings` table, so a contact line saved "for Config Studio" would appear for
 * some subscribers and not others with nothing on screen to explain which.
 */
@Composable
fun StudioAccountDetailScreen(
    store: StudioStore,
    installationId: String,
    onBack: () -> Unit,
    onRemoved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val account = remember(installationId) {
        store.installedAccounts().firstOrNull { it.id == installationId }
    }
    var inst by remember { mutableStateOf<StudioInstallation?>(null) }
    var extras by remember { mutableStateOf<List<String>>(emptyList()) }
    var scanning by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    var confirmDisconnect by remember { mutableStateOf(false) }

    var contact by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf("") }
    var settingsLoaded by remember { mutableStateOf(false) }

    suspend fun refresh() {
        val acc = account ?: return
        scanning = true
        inst = store.refreshInstallations().firstOrNull { it.installationId == installationId }
        // The engine's own settings, so the two fields below show what this shard is actually
        // serving rather than what was last typed into them.
        when (val s = StudioHttpApi(context, acc).getSettings()) {
            is StudioResult.Ok -> {
                contact = s.value.contact.orEmpty()
                brand = s.value.brand.orEmpty()
                settingsLoaded = true
            }
            // An engine older than schema 10 has no `/v1/settings`. Not an error to show — it is a
            // capability this installation does not have yet, and the update row above says so.
            is StudioResult.Err -> settingsLoaded = false
        }
        extras = when (val scan = StudioDiscovery(context).scan(acc)) {
            is StudioDiscovery.Result.Adopted -> scan.found.otherCandidates
            else -> emptyList()
        }
        scanning = false
    }

    // §2. Each of these is asked only when the operator asks for it: they are Cloudflare API
    // round trips, and a screen that fired all of them on entry would make opening one account a
    // four-request wait for answers most visits never look at.
    var permissions by remember { mutableStateOf<List<StudioPermission>>(emptyList()) }
    var domains by remember { mutableStateOf<List<StudioDomain>?>(null) }
    var reach by remember { mutableStateOf<StudioReachability?>(null) }
    var newDomain by remember { mutableStateOf("") }
    var confirmDetach by remember { mutableStateOf<StudioDomain?>(null) }
    var confirmReinstall by remember { mutableStateOf(false) }
    var confirmUninstall by remember { mutableStateOf(false) }
    var uninstallDb by remember { mutableStateOf(false) }
    val admin = remember { StudioAdmin(context) }

    LaunchedEffect(installationId) { refresh() }
    LaunchedEffect(installationId) {
        // The domain list is the exception: it decides whether the address section says "no custom
        // domain" or lists one, and getting that wrong on first paint is worse than one request.
        account?.let { domains = admin.listDomains(it) }
    }

    IosScreen(
        title = account?.name?.ifEmpty { account.email } ?: S(R.string.studio_accounts),
        onBack = if (busy) null else onBack,
        backLabel = S(R.string.studio_accounts),
        onRefresh = { refresh() },
    ) {
        Spacer(Modifier.height(10.dp))
        note?.let { InfoCard(it); Spacer(Modifier.height(12.dp)) }
        error?.let { ProblemCard(it); Spacer(Modifier.height(12.dp)) }

        if (account == null) {
            InfoCard(S(R.string.studio_engine_unreachable))
            return@IosScreen
        }

        val i = inst

        // ---- the engine ---------------------------------------------------------------
        SettingsSectionHeader(S(R.string.studio_section_engine))
        if (i == null) {
            InfoCard(S(R.string.studio_engine_unreachable), underHeader = true)
        } else {
            PayloadCard(i.workerUrl)
            Spacer(Modifier.height(10.dp))
            // Four labelled rows rather than four lines of "Label: value" hand-joined inside a
            // footer. The footer style is the grey caption iOS puts UNDER a card, so in here it
            // was double-indented, undersized and flush against the bottom edge.
            SettingsGroup {
                SettingsRow(
                    title = S(R.string.studio_engine_build),
                    value = faNum(i.engineBuild),
                    showChevron = false,
                )
                Separator()
                SettingsRow(
                    title = S(R.string.studio_engine_schema),
                    value = faNum(i.schemaVersion),
                    showChevron = false,
                )
                Separator()
                SettingsRow(
                    title = S(R.string.studio_users),
                    // A dash, not a zero: an account that could not be read has an unknown
                    // number of people on it, which is not the same as none.
                    value = if (i.users < 0) "—" else faNum(i.users),
                    showChevron = false,
                )
                Separator()
                SettingsRow(
                    title = if (i.isHealthy) S(R.string.studio_engine_healthy)
                    else S(R.string.studio_engine_unreachable),
                    icon = if (i.isHealthy) StudioIcons.Ok else StudioIcons.Warning,
                    tint = if (i.isHealthy) Ios.Green else Ios.Orange,
                    showChevron = false,
                )
                // Whether a device limit set on this account is really applied: true only where the
                // engine has its Durable Object, which is the thing that counts and refuses.
                Separator()
                val devicesOn = i.can("enforcement.strict")
                SettingsRow(
                    title = if (devicesOn) S(R.string.studio_account_devices_on) else S(R.string.studio_account_devices_off),
                    icon = StudioIcons.Devices,
                    tint = if (devicesOn) Ios.Green else Ios.Orange,
                    showChevron = false,
                )
            }
            if (!i.can("enforcement.strict") && i.reachable) {
                NoticeCard(
                    icon = StudioIcons.Devices,
                    tint = Ios.Orange,
                    title = S(R.string.studio_account_devices_off),
                    body = S(R.string.studio_account_devices_off_body),
                )
            }

            if (i.migrationError != null) {
                ProblemCard(S(R.string.studio_engine_migration_failed))
            }

            val stale = i.isStale(PanelBuild.MLM, PanelBuild.MLM_SCHEMA)

            Spacer(Modifier.height(10.dp))
            SettingsGroup {
                SettingsActionRow(
                    label = S(R.string.studio_check_now),
                    icon = StudioIcons.Refresh,
                    tint = Ios.Blue,
                    enabled = !busy,
                ) { scope.launch { refresh() } }

                // The engine ships inside the APK, so "update the engine" means "redeploy what this
                // app is carrying". The SCHEMA is checked as well as the build, and it is the half
                // that moves more often: a release can add a table and an index without changing a
                // single endpoint, leaving the engine build put while the installation quietly
                // lacks the tables a new screen needs.
                Separator()
                SettingsActionRow(
                    label = if (stale) S(R.string.studio_engine_update_available) else S(R.string.studio_repair),
                    icon = if (stale) StudioIcons.EngineUpdate else StudioIcons.Repair,
                    tint = if (stale) Ios.Orange else Ios.Green,
                    enabled = !busy,
                ) {
                    busy = true; error = null; note = null
                    scope.launch {
                        when (val res = StudioDeployer(context).install(account)) {
                            is StudioDeployer.Result.Ready -> {
                                // Said when it happens: an engine that installed without its device
                                // counter is up to date and still not applying device limits.
                                note = context.getString(
                                    if (res.deviceLimits) R.string.studio_engine_up_to_date
                                    else R.string.studio_account_devices_denied
                                )
                                refresh()
                            }
                            is StudioDeployer.Result.WouldDowngrade ->
                                error = context.getString(R.string.studio_downgrade_body) + "\n\n" +
                                    context.getString(R.string.studio_downgrade_versions)
                                        .replace("%1\$s", res.installed.toString())
                                        .replace("%2\$s", res.shipping.toString())
                            is StudioDeployer.Result.Failed -> error = res.message
                        }
                        busy = false
                    }
                }
            }
            if (stale) InfoCard(S(R.string.studio_update_app_first))
        }

        // ---- what the subscriber's page says -------------------------------------------
        SettingsSectionHeader(S(R.string.studio_page_settings))
        if (!settingsLoaded) {
            // Honest about which of the two reasons it is: an engine that predates the endpoint, or
            // one that could not be reached at all. Both are fixed above; neither is fixed here.
            InfoCard(S(R.string.studio_page_settings_unavailable), underHeader = true)
        } else {
            InfoCard(S(R.string.studio_page_contact_note), underHeader = true)
            Spacer(Modifier.height(10.dp))
            WizardField(contact, { contact = it }, S(R.string.studio_page_contact_hint), ltr = false)
            Spacer(Modifier.height(10.dp))
            WizardField(brand, { brand = it }, S(R.string.studio_page_brand_hint), ltr = false)
            Spacer(Modifier.height(10.dp))
            SettingsGroup {
                SettingsActionRow(
                    label = S(R.string.studio_save),
                    icon = StudioIcons.Save,
                    tint = Ios.Blue,
                    enabled = !busy,
                ) {
                    busy = true; error = null; note = null
                    scope.launch {
                        val res = StudioHttpApi(context, account).patchSettings(contact, brand)
                        busy = false
                        when (res) {
                            is StudioResult.Ok -> note = context.getString(R.string.studio_saved)
                            is StudioResult.Err -> error = messageFor(context, res.error)
                        }
                    }
                }
            }
        }

        // ---- the account ---------------------------------------------------------------
        SettingsSectionHeader(S(R.string.studio_section_account))
        SettingsGroup {
            SettingsRow(
                title = S(R.string.email),
                value = account.email,
                showChevron = false,
            )
            Separator()
            SettingsRow(
                title = S(R.string.studio_account_database),
                value = account.mlmDbId ?: "—",
                showChevron = false,
            )
        }

        // ---- workers older versions left behind -----------------------------------------
        SettingsSectionHeader(S(R.string.studio_section_extra_workers))
        when {
            scanning -> WizardBusy(S(R.string.studio_scanning_account))
            extras.isEmpty() -> InfoCard(S(R.string.studio_extra_workers_none), underHeader = true)
            else -> {
                InfoCard(S(R.string.studio_extra_workers_body), underHeader = true)
                // The warning sits ABOVE the list, not inside the confirmation, so it is read
                // before anything is tapped rather than while deciding.
                ProblemCard(S(R.string.studio_extra_workers_warning))
                Spacer(Modifier.height(10.dp))
                extras.forEach { name ->
                    PayloadCard(name)
                    Spacer(Modifier.height(8.dp))
                    SettingsGroup {
                        SettingsActionRow(
                            label = S(R.string.studio_remove_worker),
                            icon = StudioIcons.Delete,
                            tint = Ios.Red,
                            enabled = !busy,
                        ) { confirmRemove = name }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
        }

        // ---- does it answer from here ---------------------------------------------------
        SettingsSectionHeader(S(R.string.studio_reach_title))
        SettingsGroup {
            reach?.let { r ->
                SettingsRow(
                    title = when {
                        r.ok -> S(R.string.studio_reach_ok)
                        r.pageCode != null -> S(R.string.studio_reach_page_only)
                        else -> S(R.string.studio_reach_none)
                    },
                    icon = if (r.ok) StudioIcons.Ok else StudioIcons.Warning,
                    tint = when {
                        r.ok -> Ios.Green
                        r.pageCode != null -> Ios.Orange
                        else -> Ios.Red
                    },
                    value = r.pageLatencyMs?.let {
                        S(R.string.studio_reach_latency).replace("%1\$s", faNum(it))
                    },
                    showChevron = false,
                )
                Separator()
            }
            SettingsActionRow(
                label = if (busy) S(R.string.studio_reach_testing) else S(R.string.studio_reach_test),
                icon = StudioIcons.Test,
                tint = Ios.Green,
                busy = busy,
                enabled = !busy,
            ) {
                busy = true; error = null; note = null
                scope.launch {
                    reach = admin.probeWorker(account)
                    busy = false
                }
            }
        }
        InfoCard(S(R.string.studio_reach_note))

        // ---- where it answers -------------------------------------------------------------
        SettingsSectionHeader(S(R.string.studio_domain_title))
        val domainList = domains
        when {
            domainList == null -> WizardBusy(S(R.string.studio_loading))
            domainList.isEmpty() -> InfoCard(S(R.string.studio_domain_none), underHeader = true)
            else -> SettingsGroup {
                domainList.forEachIndexed { index, d ->
                    if (index > 0) Separator()
                    SettingsRow(
                        title = d.hostname,
                        subtitle = d.zoneName,
                        value = S(R.string.studio_domain_detach),
                        showChevron = false,
                        onClick = { confirmDetach = d },
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        WizardField(newDomain, { newDomain = it; error = null }, S(R.string.studio_domain_hint), monospace = true)
        Spacer(Modifier.height(10.dp))
        SettingsGroup {
            SettingsActionRow(
                label = S(R.string.studio_domain_attach),
                icon = StudioIcons.Domain,
                tint = Ios.Blue,
                enabled = !busy && newDomain.isNotBlank(),
            ) {
                busy = true; error = null; note = null
                scope.launch {
                    val (ok, message) = admin.attachDomain(account, newDomain)
                    if (ok) {
                        note = context.getString(R.string.studio_domain_attached)
                        newDomain = ""
                        domains = admin.listDomains(account)
                    } else {
                        // Cloudflare\'s own words, except for the one case the app can explain
                        // better than a 400 can: a hostname on a domain this account does not hold.
                        error = if (message.startsWith("no zone"))
                            context.getString(R.string.studio_domain_needs_zone) else message
                    }
                    busy = false
                }
            }
        }
        InfoCard(S(R.string.studio_domain_note))

        // ---- what this credential may do ---------------------------------------------------
        SettingsSectionHeader(S(R.string.studio_perm_title))
        if (permissions.isNotEmpty()) {
            SettingsGroup {
                permissions.forEachIndexed { index, perm ->
                    if (index > 0) Separator()
                    SettingsRow(
                        title = S(
                            when (perm.kind) {
                                StudioPermission.WORKERS -> R.string.studio_perm_workers
                                StudioPermission.D1 -> R.string.studio_perm_d1
                                StudioPermission.SUBDOMAIN -> R.string.studio_perm_subdomain
                                else -> R.string.studio_perm_zones
                            }
                        ),
                        value = when (perm.granted) {
                            true -> S(R.string.studio_perm_yes)
                            false -> S(R.string.studio_perm_no)
                            null -> S(R.string.studio_perm_unknown)
                        },
                        titleColor = when (perm.granted) {
                            false -> Ios.Red
                            null -> Ios.SecondaryLabel
                            else -> Ios.Label
                        },
                        showChevron = false,
                    )
                }
            }
            if (permissions.any { it.granted == null }) InfoCard(S(R.string.studio_perm_unknown_note))
        }
        Spacer(Modifier.height(10.dp))
        SettingsGroup {
            SettingsActionRow(
                label = if (busy) S(R.string.studio_perm_checking) else S(R.string.studio_perm_check),
                icon = StudioIcons.Verified,
                tint = Ios.Purple,
                busy = busy,
                enabled = !busy,
            ) {
                busy = true; error = null
                scope.launch { permissions = admin.checkPermissions(account); busy = false }
            }
        }
        InfoCard(S(R.string.studio_perm_note))

        // ---- disconnect, reinstall, remove -------------------------------------------------
        SettingsSectionHeader(S(R.string.studio_section_danger))
        SettingsGroup {
            SettingsActionRow(
                label = S(R.string.studio_reinstall),
                icon = StudioIcons.Reinstall,
                tint = Ios.Orange,
                enabled = !busy,
            ) { confirmReinstall = true }
        }
        InfoCard(S(R.string.studio_reinstall_note))
        Spacer(Modifier.height(10.dp))
        SettingsGroup {
            SettingsActionRow(
                label = S(R.string.studio_uninstall),
                icon = StudioIcons.Uninstall,
                tint = Ios.Red,
                enabled = !busy,
            ) { confirmUninstall = true }
        }
        InfoCard(S(R.string.studio_uninstall_note))
        Spacer(Modifier.height(10.dp))
        SettingsGroup {
            SettingsActionRow(
                label = S(R.string.studio_account_disconnect),
                icon = StudioIcons.Disconnect,
                tint = Ios.Red,
                enabled = !busy,
            ) { confirmDisconnect = true }
        }
        InfoCard(S(R.string.studio_account_disconnect_note))

        Spacer(Modifier.height(28.dp))
    }

    confirmDetach?.let { d ->
        IosAlert(
            title = S(R.string.studio_domain_detach_title),
            message = S(R.string.studio_domain_detach_body) + "\n\n" + d.hostname,
            actions = listOf(
                IosAlertAction(S(R.string.studio_cancel), { confirmDetach = null }),
                IosAlertAction(S(R.string.studio_domain_detach), {
                    confirmDetach = null
                    if (account != null) {
                        busy = true; error = null; note = null
                        scope.launch {
                            val (ok, message) = admin.detachDomain(account, d.id)
                            if (ok) domains = admin.listDomains(account) else error = message
                            busy = false
                        }
                    }
                }, destructive = true),
            ),
            onDismiss = { confirmDetach = null },
        )
    }

    if (confirmReinstall && account != null) {
        IosAlert(
            title = S(R.string.studio_reinstall_title),
            message = S(R.string.studio_reinstall_body),
            actions = listOf(
                IosAlertAction(S(R.string.studio_cancel), { confirmReinstall = false }),
                IosAlertAction(S(R.string.studio_reinstall), {
                    confirmReinstall = false
                    busy = true; error = null; note = null
                    scope.launch {
                        // force = true: this is the one action whose entire point is to write over
                        // whatever is there, including something newer. Repair is the same deploy
                        // with the downgrade guard left on.
                        when (val res = StudioDeployer(context).install(account, force = true)) {
                            is StudioDeployer.Result.Ready -> {
                                note = context.getString(
                                    if (res.deviceLimits) R.string.studio_engine_up_to_date
                                    else R.string.studio_account_devices_denied
                                )
                                refresh()
                            }
                            is StudioDeployer.Result.WouldDowngrade ->
                                error = context.getString(R.string.studio_downgrade_body)
                            is StudioDeployer.Result.Failed -> error = res.message
                        }
                        busy = false
                    }
                }, destructive = true),
            ),
            onDismiss = { confirmReinstall = false },
        )
    }

    if (confirmUninstall && account != null) {
        IosAlert(
            title = S(R.string.studio_uninstall_title),
            message = S(R.string.studio_uninstall_body),
            actions = listOf(
                IosAlertAction(S(R.string.studio_cancel), {
                    confirmUninstall = false
                    uninstallDb = false
                }),
                IosAlertAction(S(R.string.studio_uninstall), {
                    confirmUninstall = false
                    busy = true; error = null; note = null
                    val alsoDb = uninstallDb
                    uninstallDb = false
                    scope.launch {
                        val (ok, message) = admin.uninstall(account, alsoDb)
                        if (ok) {
                            // The local index goes with it. Rows from an account this device can no
                            // longer read are a list that is wrong and has no way to become right.
                            store.disconnect(installationId)
                            onRemoved()
                        } else {
                            error = message
                            busy = false
                        }
                    }
                }, destructive = true),
            ),
            onDismiss = { confirmUninstall = false; uninstallDb = false },
        ) {
            // The database is a second, separate decision inside the confirmation, because it is
            // the one thing here a redeploy cannot rebuild.
            SettingsToggle(
                title = S(R.string.studio_uninstall_with_db),
                checked = uninstallDb,
                subtitle = S(R.string.studio_uninstall_with_db_sub),
                onCheckedChange = { uninstallDb = it },
            )
        }
    }

    confirmRemove?.let { name ->
        IosAlert(
            title = S(R.string.studio_remove_worker_title),
            message = S(R.string.studio_remove_worker_body) + "\n\n" + name,
            actions = listOf(
                IosAlertAction(S(R.string.studio_cancel), { confirmRemove = null }),
                IosAlertAction(S(R.string.studio_remove_worker), {
                    confirmRemove = null
                    if (account != null) {
                        busy = true; error = null; note = null
                        scope.launch {
                            val ok = StudioDiscovery(context).removeWorker(account, name)
                            if (ok) { note = context.getString(R.string.studio_worker_removed); refresh() }
                            else error = context.getString(R.string.studio_worker_remove_failed)
                            busy = false
                        }
                    }
                }, destructive = true),
            ),
            onDismiss = { confirmRemove = null },
        )
    }

    if (confirmDisconnect) {
        IosAlert(
            title = S(R.string.studio_account_disconnect_title),
            message = S(R.string.studio_account_disconnect_body),
            actions = listOf(
                IosAlertAction(S(R.string.studio_cancel), { confirmDisconnect = false }),
                IosAlertAction(S(R.string.studio_account_disconnect), {
                    confirmDisconnect = false
                    busy = true
                    scope.launch {
                        store.disconnect(installationId)
                        busy = false
                        onRemoved()
                    }
                }, destructive = true),
            ),
            onDismiss = { confirmDisconnect = false },
        )
    }
}
