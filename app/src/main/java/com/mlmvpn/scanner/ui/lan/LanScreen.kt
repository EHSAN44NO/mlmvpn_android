package com.mlmvpn.scanner.ui.lan

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.lan.EngineShare
import com.mlmvpn.scanner.lan.LanBlocker
import com.mlmvpn.scanner.lan.LanClients
import com.mlmvpn.scanner.lan.LanDoctor
import com.mlmvpn.scanner.lan.LanHotspot
import com.mlmvpn.scanner.lan.LanNotification
import com.mlmvpn.scanner.lan.LanRelay
import com.mlmvpn.scanner.lan.LanSetupServer
import com.mlmvpn.scanner.lan.LanSetupServer.ClientOs
import com.mlmvpn.scanner.lan.LanShare
import com.mlmvpn.scanner.lan.LanStatus
import com.mlmvpn.scanner.lan.EngineShare as Engine
import com.mlmvpn.scanner.lan.Medium
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.settings.SettingsToggle
import com.mlmvpn.scanner.utils.NetworkSettings
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// =================================================================================================
// Sharing this phone's tunnel with another device.
//
// The feature already existed as one switch in Settings called "Allow LAN", and that switch was
// the entire user interface for it: no address to type into the other device, no way to tell
// whether anyone had connected, and -- worst -- no sign that the engine currently running might
// publish no listener at all, in which case the switch was on and nothing whatsoever happened.
//
// This screen is built around the four facts in LanShare.status(), in the order they break. The
// top card never says "sharing is on"; it says which of the four is currently false and puts the
// control that fixes it directly underneath. A status that a user cannot act on is the same as no
// status at all, which is what the switch on its own amounted to.
// =================================================================================================

/**
 * @param onConnectVpn where to send a user with no tunnel. The home screen, since which engine to
 *   bring up is a question this screen has no business answering.
 * @param onOpenProxyMode Settings > Proxy mode, for the transports that cannot share in VPN mode.
 */
@Composable
fun LanScreen(
    onBack: () -> Unit,
    onConnectVpn: () -> Unit,
    onOpenProxyMode: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current

    var status by remember { mutableStateOf(LanShare.status(context)) }
    var allowLan by remember { mutableStateOf(NetworkSettings.allowLan(context)) }

    // Hotspot state lives here rather than in LanHotspot because it is screen state: what to
    // draw after the attempt, not what the system currently holds.
    var hotspot by remember { mutableStateOf<LanHotspot.Credentials?>(null) }
    var hotspotFailed by remember { mutableStateOf(false) }
    var hotspotUp by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    var doctorRows by remember { mutableStateOf<List<LanDoctor.Row>?>(null) }
    var doctorRunning by remember { mutableStateOf(false) }

    var lanPassword by remember { mutableStateOf(NetworkSettings.lanPassword(context)) }
    var ownOnly by remember { mutableStateOf(NetworkSettings.lanOwnNetworkOnly(context)) }
    var blocked by remember { mutableStateOf(NetworkSettings.lanBlocked(context)) }
    var shareUntil by remember { mutableStateOf(NetworkSettings.lanShareUntil(context)) }

    // A one-level push rather than a nav graph: this screen has exactly one kind of child
    // page and the back target is always the list it came from.
    var guideOs by remember { mutableStateOf<ClientOs?>(null) }

    fun startHotspot() {
        hotspotFailed = false
        LanHotspot.start(
            context = context,
            onReady = { creds ->
                hotspot = creds
                hotspotUp = true
            },
            onFailed = {
                hotspotFailed = true
                hotspotUp = false
            },
            onStopped = {
                hotspotUp = false
                hotspot = null
            },
        )
    }

    // Asked for at the tap, never at launch -- see the manifest for why Android wants a
    // location permission for a hotspot at all.
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startHotspot() else hotspotFailed = true
    }

    fun onHotspotTapped() {
        when (LanHotspot.precondition(context)) {
            LanHotspot.Precondition.READY -> startHotspot()
            LanHotspot.Precondition.NEEDS_PERMISSION ->
                permissionLauncher.launch(LanHotspot.requiredPermission())
            LanHotspot.Precondition.NEEDS_LOCATION_SERVICE ->
                LanHotspot.openLocationSettings(context)
            LanHotspot.Precondition.UNSUPPORTED -> {
                if (!LanHotspot.openSystemTethering(context)) hotspotFailed = true
            }
        }
    }

    // Two seconds, not sub-second: every field on this screen is a network fact that changes at
    // human speed, and the interface walk behind `address` is the same one the connect path runs
    // once per connect. Polling is the right shape here -- there is no callback for "a laptop
    // opened a socket to our proxy".
    //
    // Not paused out of sight, unlike the other polled pages: this loop is also what keeps the
    // relay and the setup server in step with the tunnel (below). Out of sight it only slows to
    // every ten seconds, and it is back at two the moment the page is shown.
    val onScreen = com.mlmvpn.scanner.ui.rememberOnScreen()
    LaunchedEffect(Unit) {
        while (true) {
            // Off the main thread: LanShare.status walks every network interface (a netlink
            // query) and LanClients reads two files under /proc. Neither is slow, but at one
            // pass every two seconds for as long as this screen is open they are exactly the
            // kind of small main-thread I/O that shows up as dropped frames while scrolling.
            val fresh = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                LanShare.status(context)
            }
            // The relay and the setup server follow the same facts this screen draws, so they
            // are brought up and down here rather than on a schedule of their own -- two
            // watchers of one condition is two things to get out of step. The relay goes first:
            // it owns the port the setup page is about to publish, and its bind result is what
            // decides that port, so a page built before it would advertise the wrong one for a
            // tick.
            val relayPort = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                LanRelay.sync(context, fresh)
            }
            val shared = fresh.copy(relayPort = relayPort)
            LanSetupServer.sync(context, shared)
            LanNotification.sync(context, shared)
            status = shared.copy(
                clients = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    LanClients.snapshot(LanSetupServer.seenClients())
                },
            )
            val wasOnScreen = onScreen.value
            kotlinx.coroutines.withTimeoutOrNull(if (wasOnScreen) 2_000L else 10_000L) {
                androidx.compose.runtime.snapshotFlow { onScreen.value }.first { it != wasOnScreen }
            }
        }
    }

    val pageTitle = stringResource(R.string.lan_page_title)

    guideOs?.let { os ->
        // Declared deeper than AppScreen's handler, so back leaves the guide before it
        // leaves the Local Network screen.
        BackHandler { guideOs = null }
        LanGuideScreen(
            os = os,
            status = status,
            backLabel = pageTitle,
            onBack = { guideOs = null },
        )
        return
    }

    IosScreen(largeTitle = pageTitle, onBack = onBack) {
        Spacer(Modifier.height(8.dp))

        LanStateCard(
            status = status,
            onConnectVpn = onConnectVpn,
            onOpenProxyMode = onOpenProxyMode,
            onStartHotspot = { onHotspotTapped() },
            onOpenTethering = { LanHotspot.openSystemTethering(context) },
            onEnableLan = {
                NetworkSettings.setAllowLan(context, true)
                allowLan = true
                status = LanShare.status(context, status.clients)
            },
        )

        Spacer(Modifier.height(20.dp))
        LanDiagram(status)

        if (hotspotUp || hotspotFailed) {
            Spacer(Modifier.height(22.dp))
            HotspotCard(
                credentials = hotspot,
                failed = hotspotFailed,
                onStop = {
                    LanHotspot.stop()
                    hotspotUp = false
                    hotspot = null
                },
                onOpenTethering = { LanHotspot.openSystemTethering(context) },
            )
        }

        if (status.ready) {
            Spacer(Modifier.height(22.dp))
            SetupCard(
                status = status,
                onCopy = { value ->
                    clipboard.setText(AnnotatedString(value))
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                onShare = { url ->
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, url)
                    }
                    context.startActivity(
                        android.content.Intent.createChooser(send, null).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                },
            )

            Spacer(Modifier.height(22.dp))
            AddressCard(status) { value ->
                clipboard.setText(AnnotatedString(value))
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }

        Spacer(Modifier.height(22.dp))
        SecurityBar(status)

        Spacer(Modifier.height(22.dp))
        ClientsSection(status, blocked) { address ->
            NetworkSettings.toggleLanBlocked(context, address)
            blocked = NetworkSettings.lanBlocked(context)
        }

        Spacer(Modifier.height(22.dp))
        DoctorSection(
            rows = doctorRows,
            running = doctorRunning,
            onRun = {
                doctorRunning = true
                scope.launch {
                    doctorRows = LanDoctor.run(context, status)
                    doctorRunning = false
                }
            },
            onCopy = { rows ->
                clipboard.setText(AnnotatedString(LanDoctor.asText(rows, status)))
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            },
            onFix = { fix ->
                when (fix) {
                    LanDoctor.Fix.CONNECT -> onConnectVpn()
                    LanDoctor.Fix.PROXY_MODE -> onOpenProxyMode()
                    LanDoctor.Fix.ENABLE_LAN -> {
                        NetworkSettings.setAllowLan(context, true)
                        allowLan = true
                    }
                    LanDoctor.Fix.HOTSPOT -> onHotspotTapped()
                    LanDoctor.Fix.BATTERY -> openBatterySettings(context)
                    LanDoctor.Fix.NONE -> Unit
                }
            },
        )

        Spacer(Modifier.height(22.dp))
        SettingsSectionHeader(stringResource(R.string.lan_section_settings))
        SettingsGroup {
            SettingsToggle(
                title = stringResource(R.string.settings_allow_lan),
                subtitle = stringResource(R.string.settings_allow_lan_desc),
                checked = allowLan,
                onCheckedChange = {
                    allowLan = it
                    NetworkSettings.setAllowLan(context, it)
                    status = LanShare.status(context, status.clients)
                },
                icon = Icons.Default.Wifi,
                tint = Ios.Teal,
            )
        }
        SettingsFooter(stringResource(R.string.lan_settings_footer))

        Spacer(Modifier.height(22.dp))
        SettingsGroup {
            // Password first: it is the only one of the three that makes the share safe on a
            // network the user does not own, which is the case the warning bar is about.
            val canAuth = status.engine == Engine.FULL
            SettingsToggle(
                title = stringResource(R.string.lan_sec_password),
                subtitle = if (canAuth) {
                    lanPassword ?: stringResource(R.string.lan_sec_password_desc)
                } else {
                    stringResource(R.string.lan_sec_password_unavailable)
                },
                checked = lanPassword != null,
                onCheckedChange = { on ->
                    // Generated, not typed. A password the user invents on a phone keyboard is
                    // either weak or unmemorable, and it has to travel to the other device
                    // anyway -- which the QR and the setup link already do for them.
                    val next = if (on && canAuth) newLanPassword() else null
                    NetworkSettings.setLanPassword(context, next)
                    lanPassword = next
                },
                icon = Icons.Default.Key,
                tint = if (canAuth) Ios.Orange else Ios.Gray,
            )
            if (lanPassword != null) {
                Separator()
                SettingsRow(
                    title = stringResource(R.string.lan_sec_generate),
                    value = lanPassword,
                    icon = Icons.Default.Key,
                    tint = Ios.Orange,
                    onClick = {
                        val next = newLanPassword()
                        NetworkSettings.setLanPassword(context, next)
                        lanPassword = next
                    },
                )
            }
            Separator()
            SettingsToggle(
                title = stringResource(R.string.lan_sec_own_only),
                subtitle = stringResource(R.string.lan_sec_own_only_desc),
                checked = ownOnly,
                onCheckedChange = {
                    ownOnly = it
                    NetworkSettings.setLanOwnNetworkOnly(context, it)
                    status = LanShare.status(context, status.clients)
                },
                icon = Icons.Default.Shield,
                tint = Ios.Green,
            )
        }
        SettingsFooter(stringResource(R.string.lan_sec_password_footer))

        Spacer(Modifier.height(22.dp))
        ShareTimerCard(
            until = shareUntil,
            onPick = { minutes ->
                val next =
                    if (minutes <= 0) 0L
                    else System.currentTimeMillis() + minutes * 60_000L
                NetworkSettings.setLanShareUntil(context, next)
                shareUntil = next
                status = LanShare.status(context, status.clients)
            },
        )

        Spacer(Modifier.height(22.dp))
        GuideListSection { guideOs = it }

        Spacer(Modifier.height(28.dp))
    }
}

// -------------------------------------------------------------------------------------------
// The state card: what is true right now, and the one control that changes it.
// -------------------------------------------------------------------------------------------

@Composable
private fun LanStateCard(
    status: LanStatus,
    onConnectVpn: () -> Unit,
    onStartHotspot: () -> Unit,
    onOpenTethering: () -> Unit,
    onOpenProxyMode: () -> Unit,
    onEnableLan: () -> Unit,
) {
    val blocker = status.blocker
    val tint = when {
        blocker != null -> Ios.Orange
        status.inUse -> Ios.Green
        else -> Ios.Teal
    }

    SettingsGroup {
        Column(Modifier.fillMaxWidth().padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StateGlyph(tint = tint, pulsing = blocker == null && !status.inUse)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        headline(status),
                        color = Ios.Label,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        detail(status),
                        color = Ios.SecondaryLabel,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                }
            }

            when (blocker) {
                LanBlocker.NO_TUNNEL -> {
                    Spacer(Modifier.height(18.dp))
                    ActionButton(stringResource(R.string.lan_action_connect), tint, onConnectVpn)
                }

                LanBlocker.ENGINE_CANNOT_SHARE -> {
                    Spacer(Modifier.height(18.dp))
                    // Both routes, side by side, because they are genuinely different trades and
                    // the app is in no position to pick: proxy mode keeps the engine the user
                    // chose but stops tunnelling the phone itself, and switching engines keeps
                    // the phone tunnelled but changes what is carrying it.
                    if (status.engine == EngineShare.NEEDS_PROXY_MODE) {
                        ActionButton(
                            stringResource(R.string.lan_action_proxy_mode),
                            tint,
                            onOpenProxyMode,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    ActionButton(
                        stringResource(R.string.lan_action_switch_engine),
                        Ios.Gray,
                        onConnectVpn,
                    )
                }

                LanBlocker.LAN_DISABLED -> {
                    Spacer(Modifier.height(18.dp))
                    ActionButton(stringResource(R.string.lan_action_enable), tint, onEnableLan)
                }

                LanBlocker.NO_LOCAL_NETWORK -> {
                    Spacer(Modifier.height(18.dp))
                    ActionButton(stringResource(R.string.lan_action_hotspot), tint, onStartHotspot)
                    Spacer(Modifier.height(10.dp))
                    // Offered alongside, not only after the in-app attempt fails. Several OEMs
                    // have removed the local-only hotspot API outright, and a user who already
                    // knows their own phone should not have to fail once to reach the route
                    // that works on it.
                    ActionButton(
                        stringResource(R.string.lan_action_hotspot_settings),
                        Ios.Gray,
                        onOpenTethering,
                    )
                }

                LanBlocker.PORT_NOT_LISTENING -> Unit

                null -> Unit
            }
        }
    }
}

/**
 * The status dot, breathing while the phone is ready but nobody has connected yet.
 *
 * Motion only in that one state, deliberately: a finished thing that keeps moving reads as still
 * working, and a blocked thing that pulses reads as busy rather than stuck.
 */
@Composable
private fun StateGlyph(tint: Color, pulsing: Boolean) {
    // Composed only while it pulses, and only on screen: an infinite transition asks for every
    // frame for as long as it exists, read or not -- parked in a hidden tab included.
    val onScreen by com.mlmvpn.scanner.ui.rememberOnScreen()
    val alpha = if (pulsing && onScreen) {
        rememberInfiniteTransition(label = "lan-pulse").animateFloat(
            initialValue = 1f,
            targetValue = 0.45f,
            animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
            label = "lan-pulse-alpha",
        ).value
    } else {
        1f
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .alpha(alpha)
            .background(tint.copy(alpha = 0.18f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Lan, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun ActionButton(label: String, tint: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(tint.copy(alpha = 0.20f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = tint, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * A six-digit password, generated rather than typed.
 *
 * Digits only: it is read off a phone screen or scanned, and a mixed-case string invites the
 * transcription errors this whole feature exists to avoid. The strength that matters here is
 * "not guessable by someone else on this Wi-Fi in the next hour", which six digits and a
 * connection they cannot see the result of comfortably covers.
 */
private fun newLanPassword(): String = (100000..999999).random().toString()

// -------------------------------------------------------------------------------------------
// The share timer: four choices, and what is left of the current one.
// -------------------------------------------------------------------------------------------

@Composable
private fun ShareTimerCard(until: Long, onPick: (Int) -> Unit) {
    val remaining = if (until > 0L) {
        ((until - System.currentTimeMillis()) / 60_000L).toInt().coerceAtLeast(0)
    } else {
        0
    }
    SettingsSectionHeader(stringResource(R.string.lan_sec_timer))
    SettingsGroup {
        listOf(
            0 to R.string.lan_sec_timer_off,
            30 to R.string.lan_sec_timer_30,
            60 to R.string.lan_sec_timer_60,
            120 to R.string.lan_sec_timer_120,
        ).forEachIndexed { index, (minutes, label) ->
            if (index > 0) Separator()
            val active = if (minutes == 0) until <= 0L else remaining in 1..minutes
            SettingsRow(
                title = stringResource(label),
                value = if (active && minutes > 0) {
                    stringResource(R.string.lan_sec_timer_remaining, remaining)
                } else {
                    null
                },
                icon = Icons.Default.Timer,
                tint = if (active) Ios.Blue else Ios.Gray,
                showChevron = false,
                onClick = { onPick(minutes) },
            )
        }
    }
    SettingsFooter(stringResource(R.string.lan_sec_timer_footer))
}

// -------------------------------------------------------------------------------------------
// Diagnostics. Ten rows, each with the finding and the button that fixes it.
// -------------------------------------------------------------------------------------------

@Composable
private fun DoctorSection(
    rows: List<LanDoctor.Row>?,
    running: Boolean,
    onRun: () -> Unit,
    onCopy: (List<LanDoctor.Row>) -> Unit,
    onFix: (LanDoctor.Fix) -> Unit,
) {
    SettingsSectionHeader(stringResource(R.string.lan_section_doctor))
    SettingsGroup {
        SettingsRow(
            title = stringResource(
                if (running) R.string.lan_doc_running else R.string.lan_doc_open
            ),
            subtitle = stringResource(R.string.lan_doc_open_desc),
            icon = Icons.Default.MedicalServices,
            tint = Ios.Blue,
            onClick = if (running) null else onRun,
        )
        rows?.forEach { row ->
            Separator()
            DoctorRow(row, onFix)
        }
        if (rows != null) {
            Separator()
            SettingsRow(
                title = stringResource(R.string.lan_doc_copy),
                icon = Icons.Default.ContentCopy,
                tint = Ios.Gray,
                onClick = { onCopy(rows) },
            )
        }
    }
}

@Composable
private fun DoctorRow(row: LanDoctor.Row, onFix: (LanDoctor.Fix) -> Unit) {
    val (icon, tint) = when (row.verdict) {
        LanDoctor.Verdict.PASS -> Icons.Default.CheckCircle to Ios.Green
        LanDoctor.Verdict.FAIL -> Icons.Default.Cancel to Ios.Red
        LanDoctor.Verdict.WARN -> Icons.Default.HelpOutline to Ios.Orange
        LanDoctor.Verdict.SKIP -> Icons.Default.RemoveCircleOutline to Ios.Gray
    }
    Column {
        SettingsRow(
            title = stringResource(LanDoctor.titleRes(row.check)),
            subtitle = row.detail,
            icon = icon,
            tint = tint,
            showChevron = false,
        )
        // The fix sits under its own row rather than beside it: these labels are sentences
        // ("Turn on proxy mode"), and a trailing button would either truncate them or squeeze
        // the finding they belong to.
        LanDoctor.fixLabelRes(row.fix)?.let { label ->
            Box(Modifier.padding(start = 62.dp, end = 16.dp, bottom = 12.dp)) {
                SmallAction(stringResource(label), icon, tint) { onFix(row.fix) }
            }
        }
    }
}

/** Android's per-app battery page, which is where the background-restriction fix lives. */
private fun openBatterySettings(context: android.content.Context) {
    val targets = listOf(
        android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:" + context.packageName),
        ),
        android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )
    for (intent in targets) {
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent); true }.getOrDefault(false)) return
    }
}

// -------------------------------------------------------------------------------------------
// The hotspot this app started, and how to join it without typing a password.
// -------------------------------------------------------------------------------------------

@Composable
private fun HotspotCard(
    credentials: LanHotspot.Credentials?,
    failed: Boolean,
    onStop: () -> Unit,
    onOpenTethering: () -> Unit,
) {
    SettingsSectionHeader(stringResource(R.string.lan_section_hotspot))
    if (failed) {
        SettingsGroup {
            SettingsRow(
                title = stringResource(R.string.lan_action_hotspot_settings),
                subtitle = stringResource(R.string.lan_hotspot_failed),
                icon = Icons.Default.WifiTethering,
                tint = Ios.Orange,
                onClick = onOpenTethering,
            )
        }
        return
    }

    // The Wi-Fi join code, which both Android's and iOS's cameras act on directly -- so the other
    // device joins without anyone reading a generated password aloud.
    val qr: ImageBitmap? = remember(credentials?.qrPayload) {
        credentials?.qrPayload?.let { payload ->
            LanSetupServer.qrPng(payload, size = 480)?.let { bytes ->
                android.graphics.BitmapFactory
                    .decodeByteArray(bytes, 0, bytes.size)
                    ?.asImageBitmap()
            }
        }
    }

    SettingsGroup {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.lan_hotspot_started),
                color = Ios.Green,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            if (credentials == null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.lan_hotspot_no_creds),
                    color = Ios.SecondaryLabel,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    textAlign = TextAlign.Center,
                )
            } else {
                if (qr != null) {
                    Spacer(Modifier.height(14.dp))
                    Box(
                        modifier = Modifier
                            .background(Color.White, RoundedCornerShape(14.dp))
                            .padding(12.dp),
                    ) {
                        Image(
                            bitmap = qr,
                            contentDescription = null,
                            modifier = Modifier.size(170.dp),
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                LabelledValue(stringResource(R.string.lan_hotspot_ssid), credentials.ssid)
                Spacer(Modifier.height(6.dp))
                LabelledValue(stringResource(R.string.lan_hotspot_password), credentials.password)
            }
            Spacer(Modifier.height(16.dp))
            SmallAction(
                stringResource(R.string.lan_hotspot_stop),
                Icons.Default.WifiTethering,
                Ios.Gray,
                onStop,
            )
        }
    }
    SettingsFooter(stringResource(R.string.lan_hotspot_note))
}

@Composable
private fun LabelledValue(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ios.SecondaryLabel, fontSize = 13.sp)
        Spacer(Modifier.width(10.dp))
        Text(value, color = Ios.Label, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
    }
}

// -------------------------------------------------------------------------------------------
// The setup link, which is the short path: the other device reads a code and follows a page
// written for its own operating system, instead of the user reading numbers off this screen.
// -------------------------------------------------------------------------------------------

@Composable
private fun SetupCard(
    status: LanStatus,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
) {
    val url = status.setupUrl ?: return
    // Regenerated only when the address or port moves. Encoding is a few milliseconds, but this
    // recomposes every two seconds with the client poll and there is no reason to redraw a code
    // that has not changed.
    val qr: ImageBitmap? = remember(url) {
        LanSetupServer.qrPng(url, size = 480)?.let { bytes ->
            android.graphics.BitmapFactory
                .decodeByteArray(bytes, 0, bytes.size)
                ?.asImageBitmap()
        }
    }

    SettingsSectionHeader(stringResource(R.string.lan_section_setup))
    SettingsGroup {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (qr != null) {
                // White plate under the code on purpose: a QR needs light quiet zones to scan,
                // and every surface in this app is dark.
                Box(
                    modifier = Modifier
                        .background(Color.White, RoundedCornerShape(14.dp))
                        .padding(12.dp),
                ) {
                    Image(
                        bitmap = qr,
                        contentDescription = null,
                        modifier = Modifier.size(190.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
            Text(
                url,
                color = Ios.Label,
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallAction(
                    stringResource(R.string.lanweb_copy),
                    Icons.Default.QrCode2,
                    Ios.Teal,
                ) { onCopy(url) }
                SmallAction(
                    stringResource(R.string.lan_share),
                    Icons.Default.Share,
                    Ios.Blue,
                ) { onShare(url) }
            }
        }
    }
    SettingsFooter(stringResource(R.string.lan_setup_hint))
}

@Composable
private fun SmallAction(label: String, icon: ImageVector, tint: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .background(tint.copy(alpha = 0.20f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(7.dp))
        Text(label, color = tint, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

// -------------------------------------------------------------------------------------------
// The address, which is the whole point of the screen.
// -------------------------------------------------------------------------------------------

@Composable
private fun AddressCard(status: LanStatus, onCopy: (String) -> Unit) {
    SettingsSectionHeader(stringResource(R.string.lan_section_address))
    SettingsGroup {
        CopyRow(
            label = stringResource(R.string.lan_field_proxy),
            value = status.proxyEndpoint.orEmpty(),
            icon = Icons.Default.Router,
            tint = Ios.Teal,
            onCopy = onCopy,
        )
        Separator()
        CopyRow(
            label = stringResource(R.string.lan_field_pac),
            value = status.pacUrl.orEmpty(),
            icon = Icons.Default.Lan,
            tint = Ios.Blue,
            onCopy = onCopy,
        )
    }
    SettingsFooter(stringResource(R.string.lan_address_footer))
}

/**
 * A label with a value that copies on tap.
 *
 * The value is monospaced and forced left-to-right. An IP with Persian digits cannot be typed
 * into anything, and a right-to-left run reorders `192.168.43.1:10808` on screen into something
 * that is not the address -- both of which have been shipped before in this app.
 */
@Composable
private fun CopyRow(
    label: String,
    value: String,
    icon: ImageVector,
    tint: Color,
    onCopy: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCopy(value) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(28.dp).background(tint, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = Ios.SecondaryLabel, fontSize = 12.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                value,
                color = Ios.Label,
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Start,
            )
        }
        Icon(
            Icons.Default.ContentCopy,
            contentDescription = null,
            tint = Ios.Chevron,
            modifier = Modifier.size(18.dp),
        )
    }
}

// -------------------------------------------------------------------------------------------
// The warning, which changes meaning entirely with the network underneath.
// -------------------------------------------------------------------------------------------

/**
 * Green on the user's own hotspot, amber on somebody else's Wi-Fi.
 *
 * This replaces a paragraph of text that said both things at once and therefore neither. The LAN
 * proxy has no password by default, so on `ap0` the exposure is exactly the devices the user
 * tethered and on `wlan0` it is everyone in the café -- one sentence cannot be true of both.
 */
@Composable
private fun SecurityBar(status: LanStatus) {
    if (!status.lanEnabled || status.address == null) return
    val trusted = status.medium.isOwnNetwork
    val tint = if (trusted) Ios.Green else Ios.Orange
    SettingsGroup {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                if (trusted) Icons.Default.Shield else Icons.Default.Warning,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(13.dp))
            Column {
                Text(
                    stringResource(mediumLabel(status.medium)),
                    color = tint,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(
                        if (trusted) R.string.lan_warn_own else R.string.lan_warn_public
                    ),
                    color = Ios.SecondaryLabel,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------------------
// Who is actually using it.
// -------------------------------------------------------------------------------------------

@Composable
private fun ClientsSection(
    status: LanStatus,
    blocked: Set<String>,
    onToggleBlock: (String) -> Unit,
) {
    SettingsSectionHeader(stringResource(R.string.lan_section_clients))
    SettingsGroup {
        if (status.clients.isEmpty()) {
            SettingsRow(
                title = stringResource(R.string.lan_clients_none),
                subtitle = stringResource(R.string.lan_clients_none_desc),
                icon = Icons.Default.Devices,
                tint = Ios.Gray,
                showChevron = false,
            )
        } else {
            status.clients.forEachIndexed { index, client ->
                if (index > 0) Separator()
                val connected = client.connections > 0
                val isBlocked = client.address in blocked
                Column {
                    SettingsRow(
                        title = client.address,
                        // Three states, not two. A device that has connected before but has
                        // nothing open right now is the normal resting state of a working
                        // share -- calling that "has not connected" is what sent users off to
                        // re-check settings that were already right.
                        subtitle = when {
                            isBlocked -> stringResource(R.string.lan_client_blocked_desc)
                            connected && client.bytes > 0 -> stringResource(
                                R.string.lan_client_traffic,
                                client.connections,
                                lanBytes(client.bytes),
                            )
                            connected ->
                                stringResource(R.string.lan_client_connections, client.connections)
                            client.everConnected ->
                                stringResource(R.string.lan_client_idle, lanBytes(client.bytes))
                            else -> stringResource(R.string.lan_client_page_only)
                        },
                        icon = when {
                            isBlocked -> Icons.Default.Block
                            connected || client.everConnected -> Icons.Default.CheckCircle
                            else -> Icons.Default.Devices
                        },
                        tint = when {
                            isBlocked -> Ios.Red
                            connected -> Ios.Green
                            client.everConnected -> Ios.Teal
                            else -> Ios.Gray
                        },
                        showChevron = false,
                    )
                    Box(Modifier.padding(start = 62.dp, end = 16.dp, bottom = 12.dp)) {
                        SmallAction(
                            stringResource(
                                if (isBlocked) R.string.lan_sec_unblock else R.string.lan_sec_block
                            ),
                            Icons.Default.Block,
                            if (isBlocked) Ios.Green else Ios.Red,
                        ) { onToggleBlock(client.address) }
                    }
                }
            }
        }
    }
}

/**
 * Bytes relayed for one device, short enough to sit in a settings subtitle.
 *
 * Latin digits and a plain unit even in the Persian layout, for the same reason the addresses on
 * the setup page are: a figure a user may read out to somebody else should not change shape with
 * the locale.
 */
private fun lanBytes(value: Long): String = when {
    value >= 1_000_000_000L -> String.format("%.1f GB", value / 1_000_000_000.0)
    value >= 1_000_000L -> String.format("%.1f MB", value / 1_000_000.0)
    value >= 1_000L -> String.format("%.0f KB", value / 1_000.0)
    else -> "$value B"
}

// -------------------------------------------------------------------------------------------
// Copy that depends on state.
// -------------------------------------------------------------------------------------------

@Composable
private fun headline(status: LanStatus): String = stringResource(
    when (status.blocker) {
        LanBlocker.NO_TUNNEL -> R.string.lan_head_no_tunnel
        LanBlocker.ENGINE_CANNOT_SHARE -> R.string.lan_head_engine
        LanBlocker.LAN_DISABLED -> R.string.lan_head_disabled
        LanBlocker.NO_LOCAL_NETWORK -> R.string.lan_head_no_network
        LanBlocker.PORT_NOT_LISTENING -> R.string.lan_head_no_port
        null -> if (status.inUse) R.string.lan_head_in_use else R.string.lan_head_waiting
    }
)

@Composable
private fun detail(status: LanStatus): String = when (status.blocker) {
    LanBlocker.NO_TUNNEL -> stringResource(R.string.lan_detail_no_tunnel)
    LanBlocker.ENGINE_CANNOT_SHARE -> stringResource(
        when (status.engine) {
            EngineShare.NEEDS_PROXY_MODE -> R.string.lan_detail_needs_proxy_mode
            else -> R.string.lan_detail_impossible
        }
    )
    LanBlocker.LAN_DISABLED -> stringResource(R.string.lan_detail_disabled)
    LanBlocker.NO_LOCAL_NETWORK -> stringResource(R.string.lan_detail_no_network)
    LanBlocker.PORT_NOT_LISTENING -> stringResource(R.string.lan_detail_no_port)
    null -> {
        val active = status.clients.count { it.connections > 0 }
        if (active > 0) {
            stringResource(R.string.lan_detail_in_use, active)
        } else {
            stringResource(R.string.lan_detail_waiting)
        }
    }
}

private fun mediumLabel(medium: Medium): Int = when (medium) {
    Medium.HOTSPOT -> R.string.lan_medium_hotspot
    Medium.USB -> R.string.lan_medium_usb
    Medium.WIFI -> R.string.lan_medium_wifi
    Medium.ETHERNET -> R.string.lan_medium_ethernet
    Medium.UNKNOWN -> R.string.lan_medium_unknown
    Medium.NONE -> R.string.lan_medium_none
}
