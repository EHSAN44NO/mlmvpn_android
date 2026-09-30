package com.mlmvpn.scanner.ui.mae

import android.app.Activity
import android.content.Context
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.engines.mae.MaeEngine
import com.mlmvpn.scanner.engines.mae.device.DeviceSignals
import com.mlmvpn.scanner.engines.mae.model.FamilyPolicy
import com.mlmvpn.scanner.engines.mae.model.ServiceDef
import com.mlmvpn.scanner.engines.mae.policy.RepairLadder
import com.mlmvpn.scanner.engines.mae.policy.Symptom
import com.mlmvpn.scanner.engines.mae.route.DirectRoute
import com.mlmvpn.scanner.engines.mae.route.FragmentRoute
import com.mlmvpn.scanner.engines.mae.route.RouteKind
import com.mlmvpn.scanner.engines.mae.route.ServerlessRoute
import com.mlmvpn.scanner.engines.mae.route.UsExitRoute
import com.mlmvpn.scanner.engines.mae.route.UserConfigRoute
import com.mlmvpn.scanner.engines.mae.route.WarpRoute
import com.mlmvpn.scanner.engines.mae.route.WorkerRoute
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader

/** A service's display name: its localized string, or the domain for a custom site. */
fun serviceName(context: Context, def: ServiceDef): String {
    if (def.custom) return def.displayName
    val id = context.resources.getIdentifier("mae_service_${def.nameKey}", "string", context.packageName)
    return if (id != 0) context.getString(id) else def.displayName
}

/** A route in the words the user knows -- never an id like `cfg-3a9f1c`. */
fun routeName(context: Context, routeId: String?, family: FamilyPolicy? = null, country: String? = null): String {
    if (routeId == null) return "—"
    val base = when {
        routeId == DirectRoute.id -> context.getString(R.string.mae_route_direct)
        routeId == ServerlessRoute.id || routeId == FragmentRoute.id -> context.getString(R.string.mae_route_bypass)
        routeId == WarpRoute.ID -> context.getString(R.string.mae_route_warp)
        routeId == WorkerRoute.ID -> context.getString(R.string.mae_route_worker)
        routeId == UsExitRoute.ID -> context.getString(R.string.mae_route_usexit)
        routeId.startsWith(UserConfigRoute.CLOUD_PREFIX) -> context.getString(R.string.mae_route_cloud)
        routeId.startsWith(UserConfigRoute.PREFIX) -> context.getString(R.string.mae_route_config)
        else -> routeId
    }
    // The family matters where it was chosen for a reason: direct and exits abroad.
    val fam = if (routeId == DirectRoute.id || routeId == WorkerRoute.ID || UserConfigRoute.isConfigRoute(routeId)) when (family) {
        FamilyPolicy.V4_ONLY -> " · IPv4"
        FamilyPolicy.V6_ONLY -> " · IPv6"
        else -> ""
    } else ""
    return base + fam + (country?.let { " · ${flag(it)}" } ?: "")
}

/** A country's flag from its two-letter code. */
private fun flag(cc: String): String =
    if (cc.length != 2 || !cc.all { it.isLetter() }) cc
    else cc.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")

private enum class Page { MAIN, MANAGE, PICKER, DIAGNOSTICS }

/**
 * MAE's screen. First run: onboarding ("which apps do you use most?"). After that: one connect
 * button and the user's apps, each with its route in plain words, what just happened to it, and
 * "opened / didn't open". Everything technical sits on the diagnostics page, reached by a long
 * press on the status line.
 */
@Composable
fun MaeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val ready by MaeEngine.ready.collectAsState()
    // The registry and the state are read off the main thread: the file grows with every network.
    LaunchedEffect(Unit) { MaeEngine.initInBackground(context) }
    if (!ready) {
        IosScreen(title = stringResource(R.string.mae_title), onBack = onBack, backLabel = stringResource(R.string.home)) {
            Box(Modifier.fillMaxWidth().padding(top = 96.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Ios.Blue)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.mae_loading), color = Ios.SecondaryLabel, fontSize = 14.sp)
                }
            }
        }
        return
    }
    MaeContent(onBack)
}

@Composable
private fun MaeContent(onBack: () -> Unit) {
    val context = LocalContext.current
    // Only whether onboarding is done: the whole state changes many times a second during checks.
    val onboarded by remember { MaeEngine.store.state.map { it.onboarded }.distinctUntilChanged() }
        .collectAsState(MaeEngine.store.current.onboarded)
    var page by rememberSaveable { mutableStateOf(Page.MAIN) }
    val ask by MaeEngine.pendingAsk.collectAsState()

    ask?.let { a ->
        SymptomDialog(a, onAnswer = { symptom -> MaeEngine.answerSymptom(a.serviceId, symptom) }, onDismiss = { MaeEngine.cancelAsk() })
    }

    if (!onboarded) {
        MaeOnboarding(onBack = onBack, onDone = { ids, sites ->
            MaeEngine.setSelection(ids)
            sites.forEach { MaeEngine.addSite(it) }
        }, onAddApp = { ids, sites ->
            // The apps first, then the sites: setSelection replaces the list, so sites added
            // before it were silently dropped.
            MaeEngine.setSelection(ids)
            sites.forEach { MaeEngine.addSite(it) }
            page = Page.PICKER
        })
        return
    }
    // System Back closes a MAE sub-page before it leaves MAE.
    androidx.activity.compose.BackHandler(enabled = page != Page.MAIN) { page = Page.MAIN }
    when (page) {
        Page.MANAGE -> { MaeManageScreen(onBack = { page = Page.MAIN }); return }
        Page.PICKER -> { MaeManageScreen(onBack = { page = Page.MAIN }, openPicker = true); return }
        Page.DIAGNOSTICS -> { MaeDiagnosticsScreen(onBack = { page = Page.MAIN }); return }
        Page.MAIN -> Unit
    }

    val phase by MyVpnService.connectionPhaseFlow.collectAsState()
    val nodeId by MyVpnService.connectedNodeIdFlow.collectAsState()
    val testing by MaeEngine.testingFlow.collectAsState()
    val views by MaeEngine.viewsFlow.collectAsState()
    val ours = nodeId == MaeEngine.NODE_ID
    val connected = ours && phase == MyVpnService.Phase.CONNECTED
    val connecting = ours && phase == MyVpnService.Phase.CONNECTING
    // Set on the tap, cleared when the tunnel's state moves: building the config takes a moment,
    // and a button that shows nothing invites a second tap.
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(phase, nodeId) { busy = false }
    LaunchedEffect(busy) { if (busy) { delay(12_000); busy = false } }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) MaeEngine.startTunnelAsync(context) else busy = false
    }
    fun connect() = com.mlmvpn.scanner.data.ScanGuard.run(com.mlmvpn.scanner.data.ScanGuard.Reason.CONNECT_VPN) {
        val prep = try { VpnService.prepare(context) } catch (e: Exception) { null }
        if (prep != null) launcher.launch(prep) else MaeEngine.startTunnelAsync(context)
    }

    IosScreen(title = stringResource(R.string.mae_title), onBack = onBack, backLabel = stringResource(R.string.home)) {
        Spacer(Modifier.height(24.dp))
        ConnectButton(connected, connecting || busy) {
            if (busy) return@ConnectButton
            busy = true
            if (connected || connecting) MaeEngine.stopTunnelAsync(context) else connect()
        }
        Spacer(Modifier.height(12.dp))
        val active = views.count { !it.paused }
        val readyCount = views.count { it.phase == MaeEngine.Phase.READY }
        Text(
            text = when {
                connected && testing.isNotEmpty() -> stringResource(R.string.mae_status_checking, testing.size)
                connected -> stringResource(R.string.mae_status_connected, readyCount, active)
                connecting || busy -> stringResource(R.string.mae_connecting)
                testing.isNotEmpty() -> stringResource(R.string.mae_status_checking, testing.size)
                else -> stringResource(R.string.mae_status_idle)
            },
            color = Ios.SecondaryLabel,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            // A developer's door, not a button: no click role for a screen reader to announce.
            modifier = Modifier.fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures(onLongPress = { page = Page.DIAGNOSTICS }) },
        )

        SettingsSectionHeader(stringResource(R.string.mae_your_services))
        SettingsGroup {
            views.forEachIndexed { i, v ->
                if (i > 0) Separator()
                androidx.compose.runtime.key(v.def.id) {
                    ServiceRow(v, connected, onOk = {
                        MaeEngine.feedbackOk(v.def.id)
                        if (connected) Toast.makeText(context, context.getString(R.string.mae_thanks), Toast.LENGTH_SHORT).show()
                    }, onFail = { MaeEngine.feedbackFailed(v.def.id) })
                }
            }
        }
        SettingsGroup(modifier = Modifier.padding(top = 20.dp)) {
            SettingsRow(title = stringResource(R.string.mae_manage), icon = Icons.Default.Tune, tint = Ios.Blue,
                onClick = { page = Page.MANAGE })
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun ConnectButton(connected: Boolean, connecting: Boolean, onClick: () -> Unit) {
    val ring = when {
        connected -> Ios.Green
        connecting -> Ios.Orange
        else -> Ios.Gray
    }
    val label = stringResource(if (connected || connecting) R.string.mae_disconnect else R.string.mae_connect)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(132.dp)
                .clip(CircleShape)
                .background(ring.copy(alpha = 0.18f))
                .border(3.dp, ring, CircleShape)
                .semantics { contentDescription = label }
                .combinedClickableCompat(onClick),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = ring, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(4.dp))
                Text(label, color = Ios.Label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit) =
    this.combinedClickable(role = Role.Button, onClick = onClick)

/** How long a note stays on its row. The ones that ask the user to act stay longer. */
private fun noteLifeMs(kind: MaeEngine.NoteKind): Long? = when (kind) {
    MaeEngine.NoteKind.CHECKING -> null // replaced by the outcome
    MaeEngine.NoteKind.NO_FOREIGN_EXIT, MaeEngine.NoteKind.NOT_FOUND, MaeEngine.NoteKind.FIXED -> 10 * 60_000L
    else -> 90_000L
}

@Composable
private fun noteText(v: MaeEngine.ServiceView, n: MaeEngine.Note): String {
    val context = LocalContext.current
    val route = routeName(context, n.routeId ?: v.routeId, v.family, v.country)
    return when (n.kind) {
        MaeEngine.NoteKind.CHECKING -> stringResource(R.string.mae_note_checking)
        MaeEngine.NoteKind.FIXED -> if (n.level >= 2) stringResource(R.string.mae_note_fixed_level, route, n.level)
            else stringResource(R.string.mae_note_fixed, route)
        MaeEngine.NoteKind.VERIFIED -> stringResource(R.string.mae_note_verified)
        MaeEngine.NoteKind.NOT_FOUND -> if (n.level >= RepairLadder.MAX) stringResource(R.string.mae_note_exhausted)
            else stringResource(R.string.mae_note_not_found)
        MaeEngine.NoteKind.NO_FOREIGN_EXIT -> stringResource(R.string.mae_note_no_foreign)
        MaeEngine.NoteKind.HEALED -> stringResource(R.string.mae_note_healed)
        MaeEngine.NoteKind.OFFLINE -> stringResource(R.string.mae_note_offline)
        MaeEngine.NoteKind.NOT_CONNECTED -> stringResource(R.string.mae_note_not_connected)
    }
}

@Composable
private fun ServiceRow(v: MaeEngine.ServiceView, connected: Boolean, onOk: () -> Unit, onFail: () -> Unit) {
    val context = LocalContext.current
    val name = serviceName(context, v.def)
    val (statusRes, dot) = when (v.phase) {
        MaeEngine.Phase.READY -> R.string.mae_phase_ready to Ios.Green
        MaeEngine.Phase.TESTING -> R.string.mae_phase_testing to Ios.Orange
        MaeEngine.Phase.WAITING -> R.string.mae_phase_waiting to Ios.Gray
        MaeEngine.Phase.NO_FOREIGN_ROUTE -> R.string.mae_phase_no_foreign to Ios.Red
        MaeEngine.Phase.BLOCKED -> R.string.mae_phase_blocked to Ios.Red
        MaeEngine.Phase.PAUSED -> R.string.mae_phase_paused to Ios.Gray
    }
    val status = buildString {
        append(stringResource(statusRes))
        if (v.routeId != null && v.phase != MaeEngine.Phase.PAUSED && v.phase != MaeEngine.Phase.TESTING) {
            append(" · ").append(routeName(context, v.routeId, v.family, v.country))
            if (v.pinned) append(" · ").append(stringResource(R.string.mae_route_manual))
        }
    }
    val note = v.note
    // A note fades by itself; the screen tells the engine so the row goes back to its status.
    LaunchedEffect(note?.at) {
        val n = note ?: return@LaunchedEffect
        val life = noteLifeMs(n.kind) ?: return@LaunchedEffect
        delay((life - (System.currentTimeMillis() - n.at)).coerceAtLeast(0))
        MaeEngine.clearNote(v.def.id)
    }
    Column {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The app's icon, with its state as a small dot on the corner.
            Box {
                MaeAppIcon(v.def, name, 36.dp)
                Box(
                    Modifier.align(Alignment.BottomEnd).size(12.dp).clip(CircleShape)
                        .background(Ios.Card).padding(2.dp).clip(CircleShape).background(dot),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = Ios.Label, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(status, color = Ios.SecondaryLabel, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (!v.paused) {
                // Feedback is about MAE's route: without MAE connected it would grade a route
                // that carried nothing. Dimmed, and a tap says why.
                val a = if (connected) 1f else 0.35f
                IconButton(onClick = onOk, modifier = Modifier.size(48.dp).alpha(a)) {
                    Icon(Icons.Outlined.ThumbUp, contentDescription = stringResource(R.string.mae_feedback_opened_cd, name), tint = Ios.Green)
                }
                IconButton(onClick = onFail, modifier = Modifier.size(48.dp).alpha(a)) {
                    Icon(Icons.Outlined.ThumbDown, contentDescription = stringResource(R.string.mae_feedback_failed_cd, name), tint = Ios.Red)
                }
            }
        }
        val lines = buildList {
            note?.let { add(noteText(v, it)) }
            // What the phone itself says that the app checks: shown whenever it says Iran, so the
            // user knows before trying route after route.
            val blockers = (note?.blockers.orEmpty() + v.blockers).distinct()
            if (!v.paused && blockers.isNotEmpty()) {
                if (DeviceSignals.SIM in blockers) add(stringResource(R.string.mae_blocker_sim))
                if (DeviceSignals.TIMEZONE in blockers) add(stringResource(R.string.mae_blocker_timezone))
                add(stringResource(R.string.mae_blocker_hint))
            }
        }
        if (lines.isNotEmpty()) {
            val warn = v.blockers.isNotEmpty() ||
                note?.kind?.let { it == MaeEngine.NoteKind.NO_FOREIGN_EXIT || it == MaeEngine.NoteKind.NOT_FOUND || it == MaeEngine.NoteKind.OFFLINE } == true
            Text(
                lines.joinToString("\n"),
                color = if (warn) Ios.Orange else Ios.SecondaryLabel,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.fillMaxWidth().padding(start = 64.dp, end = 16.dp, bottom = 10.dp),
            )
        }
    }
}

/** A selectable chip for onboarding. */
@Composable
internal fun ServiceChip(label: String, selected: Boolean, highlighted: Boolean, def: ServiceDef? = null, onClick: () -> Unit) {
    val bg = if (selected) Ios.Blue else Color.Transparent
    val border = when {
        selected -> Ios.Blue
        highlighted -> Ios.Green
        else -> Ios.Separator
    }
    Box(
        modifier = Modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(20.dp))
            .combinedClickableCompat(onClick)
            .semantics { contentDescription = label }
            .padding(start = if (def != null) 8.dp else 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (def != null) {
                MaeAppIcon(def, label, 28.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(label, color = Ios.Label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * "What's wrong?" -- asked from the second "didn't open" on, so the next attempt is a different
 * approach aimed at what the user actually sees, not the same check again. Cancelling costs
 * nothing: no rung is used and no route is marked until an answer is given.
 */
@Composable
private fun SymptomDialog(
    ask: MaeEngine.Ask,
    onAnswer: (Symptom) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val name = MaeEngine.serviceDef(ask.serviceId)?.let { serviceName(context, it) } ?: ask.serviceId
    val options = listOf(
        Symptom.NOT_OPENING to R.string.mae_symptom_not_opening,
        Symptom.APP_SAYS_OFFLINE to R.string.mae_symptom_app_offline,
        Symptom.GEO_BLOCKED to R.string.mae_symptom_geo,
        Symptom.PARTIAL_LOAD to R.string.mae_symptom_partial,
        Symptom.SLOW to R.string.mae_symptom_slow,
        Symptom.LOGIN to R.string.mae_symptom_login,
        Symptom.MEDIA_CALLS to R.string.mae_symptom_calls,
    )
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ios.Card,
        title = { Text(stringResource(R.string.mae_ask_title, name), color = Ios.Label, fontSize = 18.sp) },
        text = {
            Column {
                Text(stringResource(R.string.mae_ask_subtitle, ask.level), color = Ios.SecondaryLabel, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                options.forEach { (symptom, label) ->
                    val chosenBefore = symptom == ask.previous
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(if (chosenBefore) Ios.Blue.copy(alpha = 0.15f) else Color.Transparent)
                            .combinedClickableCompat { onAnswer(symptom) }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(label), color = Ios.Label, fontSize = 15.sp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.mae_cancel)) }
        },
    )
}
