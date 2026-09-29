package com.mlmvpn.scanner.ui.mae

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.engines.mae.MaeEngine
import com.mlmvpn.scanner.engines.mae.model.Tri
import com.mlmvpn.scanner.engines.mae.store.MaeState
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.settings.SettingsTextRow
import kotlinx.coroutines.launch

/** "Which apps and sites do you use most?" -- installed ones first and highlighted. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MaeOnboarding(onBack: () -> Unit, onDone: (List<String>) -> Unit, onAddApp: (List<String>) -> Unit) {
    val context = LocalContext.current
    val all = remember { MaeEngine.registry.services }
    val installed = remember {
        val pm = context.packageManager
        all.filter { s ->
            s.packages.isEmpty() || s.packages.any { p -> runCatching { pm.getPackageInfo(p, 0); true }.getOrDefault(false) }
        }.map { it.id }.toSet()
    }
    var picked by remember { mutableStateOf(installed) }
    var sites by remember { mutableStateOf(listOf<String>()) }
    var site by remember { mutableStateOf("") }
    var siteError by remember { mutableStateOf<String?>(null) }
    val invalid = stringResource(R.string.mae_add_site_invalid)

    IosScreen(title = stringResource(R.string.mae_title), onBack = onBack, backLabel = stringResource(R.string.home)) {
        Text(stringResource(R.string.mae_onboard_title), color = Ios.Label, fontSize = 22.sp,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp))
        Text(stringResource(R.string.mae_onboard_subtitle), color = Ios.SecondaryLabel, fontSize = 14.sp,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp))

        val onPhone = all.filter { it.id in installed }
        if (onPhone.isNotEmpty()) {
            SettingsSectionHeader(stringResource(R.string.mae_onboard_installed))
            FlowRow(Modifier.padding(horizontal = 12.dp)) {
                onPhone.forEach { s ->
                    ServiceChip(serviceName(context, s), s.id in picked, true, s) {
                        picked = if (s.id in picked) picked - s.id else picked + s.id
                    }
                }
            }
        }
        // Apps not on this phone are not offered: the user asked for what they actually use.
        if (sites.isNotEmpty()) {
            FlowRow(Modifier.padding(horizontal = 12.dp)) {
                sites.forEach { d -> ServiceChip(d, true, false) { sites = sites - d } }
            }
        }
        SettingsGroup(modifier = Modifier.padding(top = 16.dp)) {
            SettingsActionRow(label = stringResource(R.string.mae_add_app), icon = Icons.Default.Apps, tint = Ios.Blue) {
                // Whatever is picked so far is kept; the picker then adds to it.
                sites.forEach { MaeEngine.addSite(it) }
                onAddApp(picked.toList())
            }
        }

        SettingsSectionHeader(stringResource(R.string.mae_add_site))
        SettingsGroup {
            SettingsTextRow(title = stringResource(R.string.mae_add_site), value = site,
                onValueChange = { site = it; siteError = null }, placeholder = stringResource(R.string.mae_add_site_hint), error = siteError)
            Separator()
            SettingsActionRow(label = stringResource(R.string.mae_add), icon = Icons.Default.Add, tint = Ios.Blue) {
                val def = com.mlmvpn.scanner.engines.mae.registry.ServiceRegistry.customSite(site)
                if (def == null) siteError = invalid else { sites = (sites + def.displayName).distinct(); site = "" }
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                onDone(picked.toList())
                sites.forEach { MaeEngine.addSite(it) }
            },
            enabled = picked.isNotEmpty() || sites.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(52.dp),
        ) {
            Text(stringResource(if (picked.isEmpty() && sites.isEmpty()) R.string.mae_onboard_pick_one else R.string.mae_onboard_continue))
        }
        Spacer(Modifier.height(40.dp))
    }
}

/** Add / remove / pause services, pin a route, and manage the foreign exit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MaeManageScreen(onBack: () -> Unit, openPicker: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by MaeEngine.store.state.collectAsState()
    var site by remember { mutableStateOf("") }
    var siteError by remember { mutableStateOf<String?>(null) }
    var exitBusy by remember { mutableStateOf(false) }
    var exitError by remember { mutableStateOf<String?>(null) }
    val invalid = stringResource(R.string.mae_add_site_invalid)
    val net = remember { MaeEngine.currentNet() }
    var showPicker by remember { mutableStateOf(openPicker) }
    val installedIds = remember {
        val pm = context.packageManager
        MaeEngine.registry.services.filter { s ->
            s.packages.isEmpty() || s.packages.any { p -> runCatching { pm.getPackageInfo(p, 0); true }.getOrDefault(false) }
        }.map { it.id }.toSet()
    }
    if (showPicker) {
        InstalledAppPicker(onBack = { showPicker = false }, scope = scope)
        return
    }

    IosScreen(title = stringResource(R.string.mae_manage), onBack = onBack, backLabel = stringResource(R.string.mae_short)) {
        SettingsSectionHeader(stringResource(R.string.mae_your_services))
        SettingsGroup {
            state.selected.forEachIndexed { i, sel ->
                val def = MaeEngine.serviceDef(sel.id) ?: return@forEachIndexed
                if (i > 0) Separator()
                val policy = state.policies[MaeState.sk(def.id, net)]
                val name = serviceName(context, def)
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    MaeAppIcon(def, name, 36.dp)
                    Spacer(Modifier.width(12.dp))
                    androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                        Text(name, color = Ios.Label, fontSize = 16.sp)
                        Text(stringResource(R.string.mae_route) + ": " +
                            (if (policy?.pinned == true) policy.routeId else stringResource(R.string.mae_route_auto)),
                            color = Ios.SecondaryLabel, fontSize = 12.sp)
                    }
                    if (sel.paused) Text(stringResource(R.string.mae_phase_paused), color = Ios.SecondaryLabel, fontSize = 14.sp)
                }
                FlowRow(Modifier.padding(start = 12.dp, bottom = 8.dp)) {
                    ServiceChip(stringResource(if (sel.paused) R.string.mae_resume else R.string.mae_pause), false, false) {
                        MaeEngine.setPaused(def.id, !sel.paused)
                    }
                    ServiceChip(stringResource(R.string.mae_route_auto), policy?.pinned != true, false) { MaeEngine.pin(def.id, null) }
                    MaeEngine.providers(state).forEach { p ->
                        ServiceChip(p.id, policy?.pinned == true && policy.routeId == p.id, false) { MaeEngine.pin(def.id, p.id) }
                    }
                    ServiceChip(stringResource(R.string.mae_remove), false, false) { MaeEngine.remove(def.id) }
                }
            }
        }

        // Services not picked yet
        val unpicked = MaeEngine.registry.services.filter { s -> s.id in installedIds && state.selected.none { it.id == s.id } }
        if (unpicked.isNotEmpty()) {
            SettingsSectionHeader(stringResource(R.string.mae_onboard_more))
            FlowRow(Modifier.padding(horizontal = 12.dp)) {
                unpicked.forEach { s ->
                    ServiceChip(serviceName(context, s), false, false, s) {
                        MaeEngine.setSelection(state.selected.map { it.id } + s.id)
                    }
                }
            }
        }

        SettingsGroup(modifier = Modifier.padding(top = 16.dp)) {
            SettingsActionRow(label = stringResource(R.string.mae_add_app), icon = Icons.Default.Apps, tint = Ios.Blue) { showPicker = true }
        }

        SettingsSectionHeader(stringResource(R.string.mae_add_site))
        SettingsGroup {
            SettingsTextRow(title = stringResource(R.string.mae_add_site), value = site,
                onValueChange = { site = it; siteError = null }, placeholder = stringResource(R.string.mae_add_site_hint), error = siteError)
            Separator()
            SettingsActionRow(label = stringResource(R.string.mae_add), icon = Icons.Default.Add, tint = Ios.Blue) {
                if (MaeEngine.addSite(site) == null) siteError = invalid else site = ""
            }
        }

        SettingsSectionHeader(stringResource(R.string.mae_foreign_exit))
        SettingsGroup {
            val w = state.worker
            SettingsRow(
                title = stringResource(R.string.mae_foreign_exit),
                value = when {
                    exitBusy -> stringResource(R.string.mae_foreign_exit_working)
                    w != null -> stringResource(R.string.mae_foreign_exit_ready, "v${w.version}")
                    else -> stringResource(R.string.mae_foreign_exit_none)
                },
                showChevron = false,
            )
            Separator()
            val accounts = remember { MaeEngine.cloudAccounts() }
            if (accounts.isEmpty()) {
                SettingsRow(title = stringResource(R.string.mae_foreign_exit_no_account), showChevron = false)
            } else {
                SettingsActionRow(
                    label = stringResource(if (w == null) R.string.mae_foreign_exit_deploy else R.string.mae_foreign_exit_update),
                    icon = Icons.Default.CloudUpload, tint = Ios.CloudflareOrange, busy = exitBusy,
                ) {
                    exitBusy = true; exitError = null
                    scope.launch {
                        MaeEngine.deployEgress().onFailure { exitError = it.message ?: it.javaClass.simpleName }
                        exitBusy = false
                    }
                }
                if (w != null) {
                    Separator()
                    SettingsActionRow(label = stringResource(R.string.mae_foreign_exit_remove), icon = Icons.Default.Delete,
                        tint = Ios.Red, labelColor = Ios.Red, busy = exitBusy) {
                        exitBusy = true
                        scope.launch { MaeEngine.removeEgress(); exitBusy = false }
                    }
                }
            }
        }
        exitError?.let { SettingsFooter(stringResource(R.string.mae_foreign_exit_failed, it)) }
        SettingsFooter(stringResource(R.string.mae_foreign_exit_footer))
        Spacer(Modifier.height(40.dp))
    }
}

/** Developer view: everything MAE believes, and why it chose what it chose. */
@Composable
internal fun MaeDiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val state by MaeEngine.store.state.collectAsState()
    val net = remember { MaeEngine.currentNet() }

    IosScreen(title = stringResource(R.string.mae_diagnostics), onBack = onBack, backLabel = stringResource(R.string.mae_short)) {
        SettingsGroup(modifier = Modifier.padding(top = 16.dp)) {
            SettingsRow(title = stringResource(R.string.mae_diag_network), value = net, showChevron = false)
            Separator()
            SettingsRow(
                title = stringResource(R.string.mae_diag_live_api),
                value = stringResource(when (state.liveApiWorks) {
                    null -> R.string.mae_diag_live_api_untested
                    true -> R.string.mae_diag_live_api_on
                    false -> R.string.mae_diag_live_api_off
                }),
                showChevron = false,
            )
        }
        state.selected.forEach { sel ->
            val def = MaeEngine.serviceDef(sel.id) ?: return@forEach
            val key = MaeState.sk(def.id, net)
            val d = state.diagnoses[key]
            val p = state.policies[key]
            SettingsSectionHeader(serviceName(context, def))
            SettingsGroup {
                SettingsRow(title = stringResource(R.string.mae_diag_primary), value = d?.primary?.name ?: "—", showChevron = false)
                Separator()
                SettingsRow(title = stringResource(R.string.mae_route), value = (p?.routeId ?: "—") + (p?.let { " · ${it.family} · ${"%.2f".format(it.confidence)}" } ?: ""), showChevron = false)
                Separator()
                SettingsActionRow(label = stringResource(R.string.mae_diag_retest), icon = Icons.Default.Refresh, tint = Ios.Blue) {
                    MaeEngine.enqueue(def.id, incident = true)
                }
            }
            val lines = buildList {
                p?.let { add("${context.getString(R.string.mae_diag_why)}: ${it.why}") }
                d?.axes?.forEach { (axis, v) ->
                    if (v.state != Tri.UNKNOWN || v.confidence > 0) add("${axis.name}: ${v.state} ${"%.2f".format(v.confidence)}")
                    v.evidence.forEach { add("   $it") }
                }
                val metrics = state.metrics.filterKeys { it.endsWith("|${def.id}|$net") }
                if (metrics.isNotEmpty()) add(context.getString(R.string.mae_diag_candidates) + ":")
                metrics.forEach { (k, m) ->
                    add("   ${k.substringBefore('|')}: ok ${m.successes}/${m.successes + m.failures}" +
                        (m.rttMs?.let { ", ${it.toInt()} ms" } ?: "") +
                        (m.throughputBps?.let { ", ${"%.1f".format(it / 125_000)} Mbps" } ?: ""))
                }
                val proofs = state.proofs.filterKeys { it.endsWith("|${def.id}|$net") }
                if (proofs.isNotEmpty()) add(context.getString(R.string.mae_diag_proofs) + ":")
                proofs.values.forEach { pr -> add("   ${pr.routeId}: ${if (pr.serviceAccepted) "PROVEN" else "REJECTED"} — ${pr.reason}") }
            }
            if (lines.isNotEmpty()) SettingsFooter(lines.joinToString("\n"))
        }
        Spacer(Modifier.height(40.dp))
    }
}

/**
 * Every app installed on the phone, to add to MAE. Registry apps are simply selected; any other
 * app is added by its main domain (guessed from its package, confirmed by DNS) and learns the
 * rest of its domains by itself.
 */
@Composable
private fun InstalledAppPicker(onBack: () -> Unit, scope: kotlinx.coroutines.CoroutineScope) {
    val context = LocalContext.current
    val state by MaeEngine.store.state.collectAsState()
    val chosen = remember(state) {
        state.selected.mapNotNull { MaeEngine.serviceDef(it.id) }.flatMap { it.packages }.toSet()
    }
    val (apps, loading) = com.mlmvpn.scanner.ui.settings.rememberInstalledApps(chosen)
    val noDomain = stringResource(R.string.mae_add_app_no_domain)
    com.mlmvpn.scanner.ui.settings.AppPickerPage(
        apps = apps,
        isLoading = loading,
        selected = chosen,
        backLabel = stringResource(R.string.mae_short),
        onBack = onBack,
        title = stringResource(R.string.mae_add_app),
        onToggle = { pkg ->
            val existing = state.selected.firstOrNull { sel -> MaeEngine.serviceDef(sel.id)?.packages?.contains(pkg) == true }
            if (existing != null) {
                MaeEngine.remove(existing.id)
            } else {
                val label = apps.firstOrNull { it.packageName == pkg }?.name ?: pkg
                if (MaeEngine.addInstalledApp(pkg, label) is MaeEngine.AddApp.NoDomain) {
                    android.widget.Toast.makeText(context, noDomain.format(label), android.widget.Toast.LENGTH_LONG).show()
                }
            }
        },
    )
}
