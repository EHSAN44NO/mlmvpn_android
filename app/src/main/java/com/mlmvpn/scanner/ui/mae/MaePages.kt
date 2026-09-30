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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.engines.mae.MaeEngine
import com.mlmvpn.scanner.engines.mae.model.ServiceDef
import com.mlmvpn.scanner.engines.mae.model.Tri
import com.mlmvpn.scanner.engines.mae.route.FragmentRoute
import com.mlmvpn.scanner.engines.mae.route.ServerlessRoute
import com.mlmvpn.scanner.engines.mae.route.UserConfigRoute
import com.mlmvpn.scanner.engines.mae.store.MaeState
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.settings.SettingsTextRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Registry apps installed on this phone (or with no app at all), worked out off the main thread. */
@Composable
private fun rememberInstalledRegistryIds(): Set<String> {
    val context = LocalContext.current
    val ids by produceState(emptySet<String>()) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            MaeEngine.registry.services.filter { s ->
                s.packages.isEmpty() || s.packages.any { p -> runCatching { pm.getPackageInfo(p, 0); true }.getOrDefault(false) }
            }.map { it.id }.toSet()
        }
    }
    return ids
}

/** "Which apps and sites do you use most?" -- installed ones first and highlighted. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MaeOnboarding(
    onBack: () -> Unit,
    onDone: (apps: List<String>, sites: List<String>) -> Unit,
    onAddApp: (apps: List<String>, sites: List<String>) -> Unit,
) {
    val context = LocalContext.current
    val all = remember { MaeEngine.registry.services }
    val installed = rememberInstalledRegistryIds()
    // Everything installed starts picked; the user's own taps win once they make any.
    var touched by rememberSaveable { mutableStateOf(false) }
    var chosen by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val picked = if (touched) chosen.toSet() else installed
    var sites by rememberSaveable { mutableStateOf(listOf<String>()) }
    var site by rememberSaveable { mutableStateOf("") }
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
                        chosen = (if (s.id in picked) picked - s.id else picked + s.id).toList()
                        touched = true
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
                onAddApp(picked.toList(), sites)
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
            onClick = { onDone(picked.toList(), sites) },
            enabled = picked.isNotEmpty() || sites.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(52.dp),
        ) {
            Text(stringResource(if (picked.isEmpty() && sites.isEmpty()) R.string.mae_onboard_pick_one else R.string.mae_onboard_continue))
        }
        Spacer(Modifier.height(40.dp))
    }
}

/** A remove that says what goes with it -- everything learned about the app. */
@Composable
private fun ConfirmRemove(title: String, body: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ios.Card,
        title = { Text(title, color = Ios.Label, fontSize = 18.sp) },
        text = { Text(body, color = Ios.SecondaryLabel, fontSize = 14.sp) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text(stringResource(R.string.mae_remove), color = Ios.Red)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.mae_cancel)) }
        },
    )
}

/** Add / remove / pause services, pin a route, and manage the foreign exit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MaeManageScreen(onBack: () -> Unit, openPicker: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The rows, as the main screen has them: worked out off the main thread and emitted only
    // when a row changes -- the raw state changes many times a second while apps are checked,
    // and this page used to be rebuilt on every one of them.
    val views by MaeEngine.viewsFlow.collectAsState()
    val worker by remember { MaeEngine.store.state.map { it.worker }.distinctUntilChanged() }.collectAsState(MaeEngine.store.current.worker)
    var site by rememberSaveable { mutableStateOf("") }
    var siteError by remember { mutableStateOf<String?>(null) }
    var exitBusy by remember { mutableStateOf(false) }
    var exitError by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<ServiceDef?>(null) }
    val invalid = stringResource(R.string.mae_add_site_invalid)
    var showPicker by rememberSaveable { mutableStateOf(openPicker) }
    val installedIds = rememberInstalledRegistryIds()
    val accounts by produceState(emptyList<com.mlmvpn.scanner.models.CloudAccount>(), worker) {
        value = withContext(Dispatchers.IO) { MaeEngine.cloudAccounts() }
    }
    if (showPicker) {
        InstalledAppPicker(onBack = { showPicker = false })
        return
    }

    // The routes a user can pin, named in words (the saved configs by their own names). Built off
    // the main thread, again only when an exit is added or removed.
    val routes by produceState(emptyList<Pair<String, String>>(), worker) {
        value = withContext(Dispatchers.Default) {
            val named = MaeEngine.providers().map { p ->
                p.id to when {
                    p.id == ServerlessRoute.id -> "Serverless"
                    p.id == FragmentRoute.id -> "Fragment"
                    p is UserConfigRoute && !p.id.startsWith(UserConfigRoute.CLOUD_PREFIX) -> "${routeName(context, p.id)} · ${p.label}"
                    else -> routeName(context, p.id)
                }
            }
            // Two routes with one name get a number each, so no chip is ambiguous.
            val counts = named.groupingBy { it.second }.eachCount()
            val seen = HashMap<String, Int>()
            named.map { (id, label) ->
                if ((counts[label] ?: 0) > 1) id to "$label ${seen.merge(label, 1, Int::plus)}" else id to label
            }
        }
    }

    removing?.let { def ->
        val name = serviceName(context, def)
        ConfirmRemove(stringResource(R.string.mae_remove_confirm_title, name), stringResource(R.string.mae_remove_confirm_body),
            onConfirm = { MaeEngine.remove(def.id) }, onDismiss = { removing = null })
    }

    IosScreen(title = stringResource(R.string.mae_manage), onBack = onBack, backLabel = stringResource(R.string.mae_short)) {
        SettingsSectionHeader(stringResource(R.string.mae_your_services))
        SettingsGroup {
            views.forEachIndexed { i, v ->
                val def = v.def
                if (i > 0) Separator()
                val name = serviceName(context, def)
                androidx.compose.runtime.key(def.id) {
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        MaeAppIcon(def, name, 36.dp)
                        Spacer(Modifier.width(12.dp))
                        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                            Text(name, color = Ios.Label, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(R.string.mae_route) + ": " +
                                (if (v.pinned) routes.firstOrNull { it.first == v.routeId }?.second ?: routeName(context, v.routeId)
                                else stringResource(R.string.mae_route_auto) + (v.routeId?.let { " (" + routeName(context, it, v.family, v.country) + ")" } ?: "")),
                                color = Ios.SecondaryLabel, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (v.paused) Text(stringResource(R.string.mae_phase_paused), color = Ios.SecondaryLabel, fontSize = 14.sp)
                    }
                    FlowRow(Modifier.padding(start = 12.dp, bottom = 8.dp)) {
                        ServiceChip(stringResource(if (v.paused) R.string.mae_resume else R.string.mae_pause), false, false) {
                            MaeEngine.setPaused(def.id, !v.paused)
                        }
                        ServiceChip(stringResource(R.string.mae_route_auto), !v.pinned, false) { MaeEngine.pin(def.id, null) }
                        routes.forEach { (id, label) ->
                            ServiceChip(label, v.pinned && v.routeId == id, false) { MaeEngine.pin(def.id, id) }
                        }
                        ServiceChip(stringResource(R.string.mae_remove), false, false) { removing = def }
                    }
                }
            }
        }

        // Services not picked yet
        val unpicked = MaeEngine.registry.services.filter { s -> s.id in installedIds && views.none { it.def.id == s.id } }
        if (unpicked.isNotEmpty()) {
            SettingsSectionHeader(stringResource(R.string.mae_onboard_more))
            FlowRow(Modifier.padding(horizontal = 12.dp)) {
                unpicked.forEach { s ->
                    ServiceChip(serviceName(context, s), false, false, s) {
                        MaeEngine.setSelection(MaeEngine.store.current.selected.map { it.id } + s.id)
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
                val typed = site
                when (val id = MaeEngine.addSite(typed)) {
                    null -> siteError = invalid
                    else -> {
                        site = ""
                        // A known app's site selects the app itself: say so, not silently.
                        if (!id.startsWith("site:")) MaeEngine.serviceDef(id)?.let { def ->
                            android.widget.Toast.makeText(context, context.getString(R.string.mae_site_is_app, typed.trim(), serviceName(context, def)),
                                android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

        SettingsSectionHeader(stringResource(R.string.mae_foreign_exit))
        SettingsGroup {
            val w = worker
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
            if (accounts.isEmpty()) {
                SettingsRow(title = stringResource(R.string.mae_foreign_exit_no_account), showChevron = false)
            } else {
                SettingsActionRow(
                    label = stringResource(if (w == null) R.string.mae_foreign_exit_deploy else R.string.mae_foreign_exit_update),
                    icon = Icons.Default.CloudUpload, tint = Ios.CloudflareOrange, busy = exitBusy,
                ) {
                    if (exitBusy) return@SettingsActionRow
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
                        if (exitBusy) return@SettingsActionRow
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
    // At most twice a second: the whole state is read here, and it changes many times a second
    // while apps are checked.
    val state by remember { MaeEngine.store.state.conflate().onEach { delay(500) } }.collectAsState(MaeEngine.store.current)
    val net = remember { MaeEngine.currentNet() }
    val now = System.currentTimeMillis()

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
        SettingsFooter(listOf(
            "WARP: ${if (state.warp != null) "tunnel" else "—"}${if (state.warpProbe != null) " + probe" else ""}",
            "cloud configs: ${state.cloudLinks.size}",
            "live API failures: ${state.liveApiFails}",
        ).joinToString(" · "))
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
                state.userEvidence[key]?.let { e ->
                    if (e.geo(now)) add("user: country refusal")
                    e.failedRoutes(now).takeIf { it.isNotEmpty() }?.let { add("user: did not open on $it") }
                }
                d?.axes?.forEach { (axis, v) ->
                    if (v.state != Tri.UNKNOWN || v.confidence > 0) add("${axis.name}: ${v.state} ${"%.2f".format(v.confidence)}")
                    v.evidence.forEach { add("   $it") }
                }
                val metrics = state.metrics.filterKeys { it.endsWith("|${def.id}|$net") }
                if (metrics.isNotEmpty()) add(context.getString(R.string.mae_diag_candidates) + ":")
                metrics.forEach { (k, m) ->
                    add("   ${k.substringBefore('|')}: ok ${"%.0f".format(m.successRate * 100)}% (${m.successes}/${m.successes + m.failures})" +
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
private fun InstalledAppPicker(onBack: () -> Unit) {
    val context = LocalContext.current
    fun chosenOf(s: MaeState) = s.selected.mapNotNull { MaeEngine.serviceDef(it.id) }.flatMap { it.packages }.toSet()
    // The ticks change only when the selection does -- not with every probe result, which used to
    // rebuild the whole list many times a second right after a tap.
    val initial = remember { chosenOf(MaeEngine.store.current) }
    val chosen by remember {
        MaeEngine.store.state.map { chosenOf(it) }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }.collectAsState(initial)
    val (apps, loading) = com.mlmvpn.scanner.ui.settings.rememberInstalledApps(initial)
    val noDomain = stringResource(R.string.mae_add_app_no_domain)
    var confirm by remember { mutableStateOf<Pair<String, ServiceDef>?>(null) }

    confirm?.let { (pkgLabel, def) ->
        val name = serviceName(context, def)
        val body = if (def.packages.size > 1) stringResource(R.string.mae_remove_confirm_part, pkgLabel, name)
            else stringResource(R.string.mae_remove_confirm_body)
        ConfirmRemove(stringResource(R.string.mae_remove_confirm_title, name), body,
            onConfirm = { MaeEngine.remove(def.id) }, onDismiss = { confirm = null })
    }

    com.mlmvpn.scanner.ui.settings.AppPickerPage(
        apps = apps,
        isLoading = loading,
        selected = chosen,
        backLabel = stringResource(R.string.mae_short),
        onBack = onBack,
        title = stringResource(R.string.mae_add_app),
        onToggle = { pkg ->
            val label = apps.firstOrNull { it.packageName == pkg }?.name ?: pkg
            val existing = MaeEngine.store.current.selected
                .firstNotNullOfOrNull { sel -> MaeEngine.serviceDef(sel.id)?.takeIf { pkg in it.packages } }
            if (existing != null) {
                // Unticking removes the app and all it learned -- and for an app with several
                // packages (YouTube Music is YouTube) the whole app: asked first.
                confirm = label to existing
            } else if (MaeEngine.addInstalledApp(pkg, label) is MaeEngine.AddApp.NoDomain) {
                android.widget.Toast.makeText(context, noDomain.format(label), android.widget.Toast.LENGTH_LONG).show()
            }
        },
    )
}
