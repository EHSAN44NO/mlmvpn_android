package com.mlmvpn.scanner.ui.mitm

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Rocket
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mlmvpn.scanner.mitm.MitmCertManager
import com.mlmvpn.scanner.mitm.MitmProfile
import com.mlmvpn.scanner.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

/**
 * The whole setup flow for the MITM domain-fronting profile, as a single card that lives at the
 * top of its own folder in the connection tab. Deliberately not a separate screen: the user
 * never has to leave the folder, and once the two steps are green the config is sitting right
 * below the card ready to be tapped.
 *
 * Three states, one primary button each:
 *   1. nothing yet          -> "شروع راه‌اندازی"  (mints the certificate + registers the config)
 *   2. certificate untrusted -> "نصب گواهی"       (opens Android's own installer dialog)
 *   3. all green            -> hint to pick the config below
 *
 * Trust state is re-read on every ON_RESUME, so returning from the system settings screen
 * flips the step to green without the user having to refresh anything.
 */
@Composable
fun MitmSetupCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var certExists by remember { mutableStateOf(false) }
    var certTrusted by remember { mutableStateOf(false) }
    var configReady by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var guideExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    /** Non-null once the certificate has been copied where Settings' file picker can reach it. */
    var exportedName by remember { mutableStateOf<String?>(null) }

    /** Bumped on every ON_RESUME to re-trigger the status read. */
    var resumeTick by remember { mutableStateOf(0) }
    /**
     * Accordion state. Starts as null and is decided once the first status read lands: open while
     * there is still setup to do, collapsed when everything is already green so a returning user
     * gets a slim header instead of a wall of finished steps. After that it is the user's to
     * toggle -- nothing reopens or recloses it behind their back.
     */
    var cardOpenState by remember { mutableStateOf<Boolean?>(null) }
    val cardOpenValue = cardOpenState ?: true

    suspend fun refresh() {
        val hasCert = MitmCertManager.exists(context)
        // The keystore lookup touches the filesystem, so keep it off the main thread.
        val trusted = hasCert && withContext(Dispatchers.IO) { MitmCertManager.isTrusted(context) }
        certExists = hasCert
        certTrusted = trusted
        configReady = MitmProfile.isInstalled(context)
        if (cardOpenState == null) cardOpenState = !(hasCert && trusted && configReady)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(resumeTick) { refresh() }

    // While the certificate exists but is not trusted yet, keep watching. Coming back from the
    // Settings screen already triggers ON_RESUME, but on some OEM skins the install completes
    // without the app being backgrounded at all, and the user should not have to restart the app
    // (or even tap anything) for the step to tick over.
    LaunchedEffect(certExists, certTrusted) {
        while (certExists && !certTrusted) {
            kotlinx.coroutines.delay(1500)
            refresh()
        }
    }

    val allDone = certExists && certTrusted && configReady
    val accent = if (allDone) GreenOk else Primary

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    listOf(accent.copy(alpha = 0.13f), SurfaceDark.copy(alpha = 0.55f))
                )
            )
            .border(1.dp, accent.copy(alpha = 0.40f), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        // ---- header (also the accordion toggle) ------------------------------------
        val cardArrow by animateFloatAsState(if (cardOpenValue) 180f else 0f, tween(200), label = "cardArrow")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ControlShape)
                .clickable { cardOpenState = !cardOpenValue },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (allDone) Icons.Default.VerifiedUser else Icons.Default.Shield,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(S(R.string.domain_fronting_no_server), color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (allDone && !cardOpenValue) S(R.string.ready_pick_the_config_below)
                    else S(R.string.youtube_instagram_whatsapp_facebook_and_reddit_with),
                    color = TextMuted, fontSize = 11.sp, lineHeight = 15.sp
                )
            }
            if (allDone) {
                Box(
                    modifier = Modifier
                        .clip(CardShape)
                        .background(GreenOk.copy(alpha = 0.18f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(S(R.string.ready_2), color = GreenOk, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Default.ExpandMore, contentDescription = if (cardOpenValue) S(R.string.collapse) else S(R.string.expand),
                tint = TextMuted, modifier = Modifier.size(20.dp).rotate(cardArrow)
            )
        }

        AnimatedVisibility(visible = cardOpenValue, enter = expandVertically(), exit = shrinkVertically()) {
          Column {
        Spacer(Modifier.height(14.dp))

        // ---- steps -----------------------------------------------------------------
        StepRow(
            index = 1,
            title = S(R.string.create_a_certificate_for_this_phone),
            subtitle = S(R.string.the_app_builds_it_itself_nothing_is),
            done = certExists,
            active = !certExists
        )
        StepRow(
            index = 2,
            title = S(R.string.install_the_certificate_in_android),
            subtitle = if (MitmCertManager.canUseDirectInstaller)
                S(R.string.the_one_step_android_will_not_let)
            else
                S(R.string.android_11_and_above_we_save_the),
            done = certTrusted,
            active = certExists && !certTrusted
        )
        StepRow(
            index = 3,
            title = S(R.string.add_the_config_to_this_folder),
            subtitle = S(R.string.after_this_the_config_appears_at_the),
            done = configReady,
            active = certExists && certTrusted && !configReady,
            isLast = true
        )

        Spacer(Modifier.height(14.dp))

        // ---- primary action --------------------------------------------------------
        when {
            !certExists || !configReady -> {
                ActionButton(
                    text = if (busy) S(R.string.preparing) else S(R.string.start_setup),
                    icon = Icons.Default.Rocket,
                    enabled = !busy,
                    accent = Primary
                ) {
                    busy = true
                    error = null
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { MitmProfile.setUp(context) }
                        busy = false
                        if (!ok) error = S(R.string.building_the_certificate_or_reading_the_config)
                        refresh()
                    }
                }
            }
            !certTrusted && MitmCertManager.canUseDirectInstaller -> {
                // Android 10 and below: the system installer still accepts a certificate handed
                // to it by an app, so this is a single confirmation for the user.
                ActionButton(
                    text = S(R.string.install_the_certificate),
                    icon = Icons.Default.Shield,
                    enabled = true,
                    accent = YellowWarn
                ) {
                    error = null
                    val intent = MitmCertManager.installIntent(context)
                    val started = intent != null && runCatching { context.startActivity(intent) }.isSuccess
                    if (!started) {
                        runCatching { context.startActivity(MitmCertManager.securitySettingsIntent()) }
                        error = S(R.string.the_installer_did_not_open_install_the)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    S(R.string.in_the_window_that_opens_accept_the),
                    color = TextMuted, fontSize = 11.sp, lineHeight = 16.sp
                )
            }
            !certTrusted -> {
                // Android 11+ refuses a certificate passed in by an app ("this certificate from
                // null must be installed in Settings"), so the only route left is the user
                // picking the file themselves. We put the file somewhere the picker can see it
                // and spell out the taps -- there is no public deep link to that screen.
                ActionButton(
                    text = if (exportedName != null) S(R.string.open_android_settings) else S(R.string.save_the_certificate_to_downloads),
                    icon = if (exportedName != null) Icons.Default.Shield else Icons.Default.Download,
                    enabled = !busy,
                    accent = YellowWarn
                ) {
                    error = null
                    if (exportedName == null) {
                        busy = true
                        scope.launch {
                            val name = withContext(Dispatchers.IO) { MitmCertManager.exportToDownloads(context) }
                            busy = false
                            if (name == null) error = S(R.string.could_not_save_the_certificate_file)
                            else exportedName = name
                        }
                    } else {
                        val ok = runCatching { context.startActivity(MitmCertManager.securitySettingsIntent()) }.isSuccess
                        if (!ok) runCatching { context.startActivity(MitmCertManager.allSettingsIntent()) }
                    }
                }

                Spacer(Modifier.height(10.dp))
                if (exportedName == null) {
                    Text(
                        S(R.string.android_11_and_above_does_not_let),
                        color = TextMuted, fontSize = 11.sp, lineHeight = 16.sp
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(ControlShape)
                            .background(SurfaceDark.copy(alpha = 0.7f))
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Check, null, tint = GreenOk, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                S(R.string.file_saved_download_folder_exportedname, exportedName),
                                color = GreenOk, fontSize = 11.sp, fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(S(R.string.now_in_android_settings), color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(5.dp))
                        listOf(
                            S(R.string.security_more_security_settings),
                            S(R.string.encryption_credentials),
                            S(R.string.install_a_certificate),
                            S(R.string.choose_ca_certificate_and_tap_install_anyway),
                            S(R.string.pick_exportedname_from_the_download_folder, exportedName)
                        ).forEachIndexed { i, line ->
                            Row(modifier = Modifier.padding(bottom = 3.dp), verticalAlignment = Alignment.Top) {
                                Text("${i + 1}.", color = YellowWarn, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(5.dp))
                                Text(line, color = TextMuted, fontSize = 10.sp, lineHeight = 15.sp)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            S(R.string.if_these_options_sit_somewhere_slightly_different),
                            color = TextDim, fontSize = 10.sp, lineHeight = 14.sp
                        )
                    }
                }
            }
            else -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ControlShape)
                        .background(GreenOk.copy(alpha = 0.12f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Check, null, tint = GreenOk, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        S(R.string.everything_is_ready_pick_the_config_below),
                        color = GreenOk, fontSize = 12.sp, fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        error?.let {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.ErrorOutline, null, tint = RedError, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(it, color = RedError, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }

        // ---- the one limitation the user must know before connecting ---------------
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ControlShape)
                .background(YellowWarn.copy(alpha = 0.09f))
                .border(1.dp, YellowWarn.copy(alpha = 0.30f), ControlShape)
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(Icons.Default.Language, null, tint = YellowWarn, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(S(R.string.works_in_the_browser_only), color = YellowWarn, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
                Text(
                    S(R.string.open_the_sites_in_chrome_or_any),
                    color = TextMuted, fontSize = 11.sp, lineHeight = 16.sp
                )
            }
        }

        // ---- per-brand install guide -----------------------------------------------
        // Every OEM skin puts the CA-install entry somewhere different and renames it, so a
        // single generic path would be wrong for most users. The search keyword at the end is the
        // reliable escape hatch on any device.
        Spacer(Modifier.height(10.dp))
        val guideArrow by animateFloatAsState(if (guideExpanded) 180f else 0f, tween(200), label = "guideArrow")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { guideExpanded = !guideExpanded }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.HelpOutline, null, tint = TextMuted, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                S(R.string.certificate_installation_by_phone_brand),
                color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Default.ExpandMore, null, tint = TextMuted,
                modifier = Modifier.size(18.dp).rotate(guideArrow)
            )
        }

        AnimatedVisibility(visible = guideExpanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ControlShape)
                    .background(SurfaceDark.copy(alpha = 0.7f))
                    .padding(12.dp)
            ) {
                Text(
                    S(R.string.the_quickest_way_on_any_phone_type),
                    color = TextPrimary, fontSize = 11.sp, lineHeight = 16.sp
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("certificate", S(R.string.certificate), "credentials").forEach { kw ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Primary.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(kw, color = TextPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    S(R.string.then_tap_something_like_install_a_certificate, MitmCertManager.EXPORT_FILE_NAME),
                    color = TextMuted, fontSize = 10.sp, lineHeight = 15.sp
                )

                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(BorderDark))
                Spacer(Modifier.height(10.dp))
                Text(S(R.string.the_manual_path_by_brand), color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))

                BrandPath(
                    S(R.string.samsung_one_ui),
                    S(R.string.settings_biometrics_and_security_other_security_settings)
                )
                BrandPath(
                    S(R.string.xiaomi_redmi_poco_miui_and_hyperos),
                    S(R.string.settings_passwords_security_system_security_privacy_encrypti)
                )
                BrandPath(
                    S(R.string.pixel_and_stock_android_12_and_above),
                    S(R.string.settings_security_privacy_more_security_settings_encryption)
                )
                BrandPath(
                    S(R.string.pixel_and_stock_android_11),
                    S(R.string.settings_security_encryption_credentials_install_a_certifica)
                )
                BrandPath(
                    S(R.string.huawei_and_honor_emui_magicos),
                    S(R.string.settings_security_more_settings_encryption_credentials_insta)
                )
                BrandPath(
                    S(R.string.honor_oppo_realme_oneplus_coloros_and_oxygenos),
                    S(R.string.settings_password_security_system_security_encryption_creden)
                )
                BrandPath(
                    S(R.string.vivo_funtouch_os_originos),
                    S(R.string.settings_more_settings_security_privacy_encryption_credentia)
                )
                BrandPath(
                    S(R.string.motorola_nokia_asus_sony),
                    S(R.string.the_same_as_stock_android_settings_security),
                    isLast = true
                )

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Info, null, tint = YellowWarn, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        S(R.string.if_your_phone_has_no_screen_lock),
                        color = TextMuted, fontSize = 10.sp, lineHeight = 15.sp
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Language, null, tint = TextDim, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        S(R.string.firefox_does_not_accept_user_installed_certificates),
                        color = TextDim, fontSize = 10.sp, lineHeight = 15.sp
                    )
                }
            }
        }

        // ---- details ---------------------------------------------------------------
        Spacer(Modifier.height(10.dp))
        val arrow by animateFloatAsState(if (expanded) 180f else 0f, tween(200), label = "arrow")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(S(R.string.certificate_details_and_management), color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Default.ExpandMore, null, tint = TextMuted,
                modifier = Modifier.size(18.dp).rotate(arrow)
            )
        }

        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column {
                Spacer(Modifier.height(6.dp))
                InfoLine(S(R.string.certificate_name), MitmCertManager.commonName(context) ?: "—")
                val fp = remember(certExists) { MitmCertManager.fingerprint(context) }
                if (fp != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(S(R.string.fingerprint_sha_256), color = TextDim, fontSize = 10.sp)
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            fp, color = TextMuted, fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace, lineHeight = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { clipboard.setText(AnnotatedString(fp)) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.ContentCopy, "copy", tint = TextMuted, modifier = Modifier.size(14.dp))
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Info, null, tint = TextDim, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        S(R.string.this_certificate_belongs_to_this_phone_alone),
                        color = TextDim, fontSize = 10.sp, lineHeight = 15.sp
                    )
                }

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            busy = true
                            error = null
                            exportedName = null // the exported copy is now the wrong certificate
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    MitmCertManager.deleteLocal(context)
                                    MitmProfile.setUp(context)
                                }
                                busy = false
                                if (!ok) error = S(R.string.could_not_build_a_new_certificate)
                                refresh()
                            }
                        },
                        enabled = !busy,
                        shape = ControlShape,
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Refresh, null, tint = TextMuted, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(S(R.string.build_a_new_certificate), color = TextMuted, fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = { runCatching { context.startActivity(MitmCertManager.securitySettingsIntent()) } },
                        shape = ControlShape,
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(S(R.string.security_settings), color = TextMuted, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    S(R.string.if_you_build_a_new_certificate_you),
                    color = TextDim, fontSize = 10.sp, lineHeight = 14.sp
                )
            }
        }
          } // accordion body
        }
    }
}

@Composable
private fun StepRow(
    index: Int,
    title: String,
    subtitle: String,
    done: Boolean,
    active: Boolean,
    isLast: Boolean = false
) {
    val color = when {
        done -> GreenOk
        active -> Primary
        else -> TextDim
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (done) GreenOk.copy(alpha = 0.20f) else Color.Transparent)
                    .border(1.dp, color.copy(alpha = if (done) 0f else 0.55f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (done) {
                    Icon(Icons.Default.Check, null, tint = GreenOk, modifier = Modifier.size(13.dp))
                } else {
                    Text("$index", color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(26.dp)
                        .background(if (done) GreenOk.copy(alpha = 0.35f) else BorderDark)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.padding(bottom = if (isLast) 0.dp else 6.dp)) {
            Text(
                title,
                color = if (done || active) TextPrimary else TextMuted,
                fontSize = 12.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
            )
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = TextDim, fontSize = 10.sp, lineHeight = 14.sp)
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = ControlShape,
        // Tinted glass with an accent rim and accent text -- the primary-action treatment used by
        // Save in the add-node form and by the connect button. The old solid accent fill with
        // BgDark text on it was the last survivor of the pre-redesign button style, and on the
        // yellow accent it put near-black text on near-yellow, which is what was reported.
        colors = ButtonDefaults.buttonColors(
            containerColor = accent.copy(alpha = 0.22f),
            contentColor = accent,
            disabledContainerColor = accent.copy(alpha = 0.08f),
            disabledContentColor = TextDim,
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.5.dp,
            color = if (enabled) accent else BorderDark,
        ),
        modifier = Modifier.fillMaxWidth().height(46.dp)
    ) {
        Icon(icon, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BrandPath(brand: String, path: String, isLast: Boolean = false) {
    Column(modifier = Modifier.padding(bottom = if (isLast) 0.dp else 9.dp)) {
        Text(brand, color = TextPrimary, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(path, color = TextMuted, fontSize = 10.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column {
        Text(label, color = TextDim, fontSize = 10.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, color = TextMuted, fontSize = 11.sp, textAlign = TextAlign.Start)
    }
}
