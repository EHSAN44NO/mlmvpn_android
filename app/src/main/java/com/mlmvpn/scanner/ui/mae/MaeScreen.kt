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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.engines.mae.MaeEngine
import com.mlmvpn.scanner.engines.mae.model.ServiceDef
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader

/** A service's display name: its localized string, or the domain for a custom site. */
fun serviceName(context: Context, def: ServiceDef): String {
    if (def.custom) return def.displayName
    val id = context.resources.getIdentifier("mae_service_${def.nameKey}", "string", context.packageName)
    return if (id != 0) context.getString(id) else def.displayName
}

private enum class Page { MAIN, MANAGE, DIAGNOSTICS }

/**
 * MAE's screen. First run: onboarding ("which apps do you use most?"). After that: one connect
 * button and the user's services, each with "opened / didn't open". Everything technical sits on
 * the diagnostics page, reached by a long press on the status line.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MaeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    remember { MaeEngine.init(context); true }
    val state by MaeEngine.store.state.collectAsState()
    val testing by MaeEngine.testingFlow.collectAsState()
    var page by remember { mutableStateOf(Page.MAIN) }

    LaunchedEffect(Unit) {
        MaeEngine.repairs.collect { r ->
            val name = { id: String -> MaeEngine.serviceDef(id)?.let { serviceName(context, it) } ?: id }
            val text = when (r) {
                is MaeEngine.Repair.Fixed -> context.getString(R.string.mae_repair_fixed, name(r.serviceId))
                is MaeEngine.Repair.Verified -> context.getString(R.string.mae_repair_verified, name(r.serviceId))
                is MaeEngine.Repair.NotFound -> context.getString(R.string.mae_repair_not_found, name(r.serviceId))
                is MaeEngine.Repair.Busy -> context.getString(R.string.mae_repair_busy, name(r.serviceId))
            }
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }

    if (!state.onboarded) {
        MaeOnboarding(onBack = onBack, onDone = { ids -> MaeEngine.setSelection(ids) })
        return
    }
    // System Back closes a MAE sub-page before it leaves MAE.
    androidx.activity.compose.BackHandler(enabled = page != Page.MAIN) { page = Page.MAIN }
    when (page) {
        Page.MANAGE -> { MaeManageScreen(onBack = { page = Page.MAIN }); return }
        Page.DIAGNOSTICS -> { MaeDiagnosticsScreen(onBack = { page = Page.MAIN }); return }
        Page.MAIN -> Unit
    }

    val phase by MyVpnService.connectionPhaseFlow.collectAsState()
    val nodeId by MyVpnService.connectedNodeIdFlow.collectAsState()
    val ours = nodeId == MaeEngine.NODE_ID
    val connected = ours && phase == MyVpnService.Phase.CONNECTED
    val connecting = ours && phase == MyVpnService.Phase.CONNECTING

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) MaeEngine.startTunnel(context)
    }
    fun connect() = com.mlmvpn.scanner.data.ScanGuard.run(com.mlmvpn.scanner.data.ScanGuard.Reason.CONNECT_VPN) {
        val prep = try { VpnService.prepare(context) } catch (e: Exception) { null }
        if (prep != null) launcher.launch(prep) else MaeEngine.startTunnel(context)
    }

    IosScreen(title = stringResource(R.string.mae_title), onBack = onBack, backLabel = stringResource(R.string.home)) {
        Spacer(Modifier.height(24.dp))
        ConnectButton(connected, connecting) {
            if (connected || connecting) MaeEngine.stopTunnel(context) else connect()
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = when {
                connected -> stringResource(R.string.mae_title)
                connecting -> stringResource(R.string.mae_connecting)
                testing.isNotEmpty() -> stringResource(R.string.mae_preparing)
                else -> stringResource(R.string.mae_title)
            },
            color = Ios.SecondaryLabel,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
                .combinedClickable(onClick = {}, onLongClick = { page = Page.DIAGNOSTICS }),
        )

        SettingsSectionHeader(stringResource(R.string.mae_your_services))
        val views = MaeEngine.views(state, testing)
        SettingsGroup {
            views.forEachIndexed { i, v ->
                if (i > 0) Separator()
                ServiceRow(v, onOk = {
                    MaeEngine.feedbackOk(v.def.id)
                    Toast.makeText(context, context.getString(R.string.mae_thanks), Toast.LENGTH_SHORT).show()
                }, onFail = { MaeEngine.feedbackFailed(v.def.id) })
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

@Composable
private fun ServiceRow(v: MaeEngine.ServiceView, onOk: () -> Unit, onFail: () -> Unit) {
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
            Text(name, color = Ios.Label, fontSize = 16.sp)
            Text(stringResource(statusRes), color = Ios.SecondaryLabel, fontSize = 12.sp)
        }
        if (!v.paused) {
            IconButton(onClick = onOk, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.ThumbUp, contentDescription = stringResource(R.string.mae_feedback_opened_cd, name), tint = Ios.Green)
            }
            IconButton(onClick = onFail, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.ThumbDown, contentDescription = stringResource(R.string.mae_feedback_failed_cd, name), tint = Ios.Red)
            }
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
            Text(label, color = Ios.Label, fontSize = 15.sp)
        }
    }
}
