package com.mlmvpn.scanner.ui.configstudio

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.studio.StudioState
import com.mlmvpn.scanner.data.studio.StudioStore
import com.mlmvpn.scanner.data.studio.api.StudioHttpApi
import com.mlmvpn.scanner.data.studio.api.StudioResult
import com.mlmvpn.scanner.data.studio.config.LocationConfigs
import com.mlmvpn.scanner.data.studio.domain.ExitCountries
import com.mlmvpn.scanner.ui.configstudio.design.ChoiceRow
import com.mlmvpn.scanner.ui.configstudio.design.CountryBadge
import com.mlmvpn.scanner.ui.configstudio.design.NoticeCard
import com.mlmvpn.scanner.ui.configstudio.design.StudioIcons
import com.mlmvpn.scanner.ui.configstudio.design.StudioSeparator
import com.mlmvpn.scanner.ui.configstudio.parts.PageIntro
import com.mlmvpn.scanner.ui.configstudio.parts.PayloadCard
import com.mlmvpn.scanner.ui.configstudio.parts.ProblemCard
import com.mlmvpn.scanner.ui.configstudio.parts.WizardField
import com.mlmvpn.scanner.ui.configstudio.parts.WizardPrimary
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosAlert
import com.mlmvpn.scanner.ui.settings.IosAlertAction
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.settings.SettingsToggle
import com.mlmvpn.scanner.utils.S
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * «لوکیشن‌ها» -- where a country's traffic leaves from, on one page (build 18 redesign).
 *
 * Two sources, and the page is honest about the difference:
 *
 *  * **سرورهای خودتان** -- a SOCKS5/HTTP server the operator brings. Safe; theirs. The page offers a
 *    one-line command that turns any VPS into one, and adds it back.
 *  * **سرورهای عمومی** -- strangers' free servers. Off until the operator turns them on through a
 *    confirmation that names the risks. Since build 18 a public server is used only after the engine
 *    has tested it and seen it really leave from its country; each country shows how many passed.
 */
@Composable
fun StudioMultiLocationScreen(
    store: StudioStore,
    state: StudioState,
    onBack: () -> Unit,
    onOpenExits: () -> Unit,
    onNewUser: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val fleet = remember(state.installations) {
        store.installedAccounts().filter { acc ->
            state.installations.firstOrNull { it.installationId == acc.id }?.can("exits.v1") == true
        }
    }
    var account by remember { mutableStateOf(fleet.firstOrNull()) }
    val inst = account?.id?.let { id -> state.installations.firstOrNull { it.installationId == id } }
    val hasPool = inst?.can("pool.v1") == true
    var pool by remember { mutableStateOf<StudioHttpApi.PoolInfo?>(null) }
    var own by remember { mutableStateOf<List<String>>(emptyList()) }
    /** Per country: verified servers and the fastest latency, from the engine's own tests. */
    var verified by remember { mutableStateOf<Map<String, Pair<Int, Long?>>>(emptyMap()) }
    var checking by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var askEnable by remember { mutableStateOf(false) }

    // «سرور خودتان در دو دقیقه»: a random login for the command, and the VPS address to add it by.
    val exitUser = remember { "u" + java.util.UUID.randomUUID().toString().replace("-", "").take(7) }
    val exitPass = remember { java.util.UUID.randomUUID().toString().replace("-", "").take(16) }
    var vpsIp by remember { mutableStateOf("") }
    var added by remember { mutableStateOf<String?>(null) }
    var copiedCmd by remember { mutableStateOf(false) }

    fun api() = account?.let { StudioHttpApi(context, it) }

    suspend fun reload() {
        val a = api() ?: return
        if (hasPool) {
            when (val r = a.getPool()) {
                is StudioResult.Ok -> {
                    pool = r.value
                    verified = r.value.verified.filterValues { it > 0 }.mapValues { (_, n) -> n to null }
                }
                is StudioResult.Err -> error = messageFor(context, r.error)
            }
        }
        own = LocationConfigs.ownCountries(a)
    }

    fun check(ccs: List<String>) {
        val a = api() ?: return
        checking = checking + ccs
        scope.launch {
            val gate = Semaphore(2)
            kotlinx.coroutines.coroutineScope {
                ccs.forEach { cc ->
                    launch {
                        gate.withPermit {
                            when (val r = a.verifyPool(cc)) {
                                is StudioResult.Ok -> verified = verified + (cc to (r.value.verified.size to
                                    r.value.verified.mapNotNull { it.latencyMs }.minOrNull()))
                                is StudioResult.Err -> {
                                    // An engine older than build 18: the one-off test it does have.
                                    val t = (a.testPool(cc) as? StudioResult.Ok)?.value
                                    verified = verified + (cc to ((if (t?.ok == true) 1 else 0) to t?.latencyMs))
                                }
                            }
                            checking = checking - cc
                        }
                    }
                }
            }
        }
    }

    fun setEnabled(on: Boolean) {
        val a = api() ?: return
        busy = true
        scope.launch {
            when (val r = a.setPool(on, risksAcknowledged = on)) {
                is StudioResult.Ok -> reload()
                is StudioResult.Err -> error = messageFor(context, r.error)
            }
            busy = false
        }
    }

    LaunchedEffect(account?.id) {
        error = null
        reload()
    }

    if (askEnable) {
        IosAlert(
            title = S(R.string.studio_pool_enable_title),
            message = S(R.string.studio_pool_enable_body),
            actions = listOf(
                IosAlertAction(S(R.string.studio_cancel), onClick = { askEnable = false }),
                IosAlertAction(S(R.string.studio_pool_enable_confirm), onClick = { askEnable = false; setEnabled(true) }, destructive = true),
            ),
            onDismiss = { askEnable = false },
        )
    }

    IosScreen(
        title = S(R.string.studio_more_locations),
        onBack = if (busy) null else onBack,
        backLabel = S(R.string.studio_tab_more),
        onRefresh = { reload() },
    ) {
        PageIntro(S(R.string.studio_locations_intro))

        if (fleet.isEmpty()) {
            NoticeCard(icon = StudioIcons.EngineUpdate, tint = Ios.Blue, title = S(R.string.studio_home_update_title),
                body = S(R.string.studio_locations_needs_update))
            Spacer(Modifier.height(28.dp))
            return@IosScreen
        }

        if (fleet.size > 1) {
            SettingsSectionHeader(S(R.string.studio_accounts))
            SettingsGroup {
                fleet.forEachIndexed { i, acc ->
                    if (i > 0) StudioSeparator()
                    ChoiceRow(
                        title = acc.name.ifBlank { acc.email },
                        selected = account?.id == acc.id,
                        onClick = { account = acc; verified = emptyMap() },
                    )
                }
            }
        }

        error?.let { ProblemCard(it) }

        // ---- the operator's own servers --------------------------------------------------
        SettingsSectionHeader(S(R.string.studio_locations_own))
        SettingsGroup {
            SettingsRow(
                title = S(R.string.studio_locations_own_row),
                subtitle = if (own.isEmpty()) S(R.string.studio_locations_own_none)
                else own.joinToString("، ") { ExitCountries.name(it) },
                icon = StudioIcons.OwnServer,
                tint = Ios.Green,
                onClick = onOpenExits,
            )
        }

        // «سرور خودتان در دو دقیقه»: one command on any VPS, then the address to add it by.
        SettingsSectionHeader(S(R.string.studio_locations_vps_title))
        val command = "docker run -d --restart=always --name mlm-exit -p 1080:1080 gogost/gost -L \"socks5://$exitUser:$exitPass@:1080\""
        PayloadCard(command)
        Spacer(Modifier.height(8.dp))
        SettingsGroup {
            SettingsActionRow(
                label = if (copiedCmd) S(R.string.studio_copied) else S(R.string.studio_locations_vps_copy),
                icon = StudioIcons.Copy,
                tint = Ios.Blue,
            ) {
                clipboard.setText(AnnotatedString(command))
                copiedCmd = true
            }
        }
        SettingsFooter(S(R.string.studio_locations_vps_note))
        WizardField(vpsIp, { vpsIp = it.trim(); added = null }, S(R.string.studio_locations_vps_ip_hint))
        Spacer(Modifier.height(8.dp))
        WizardPrimary(S(R.string.studio_locations_vps_add), enabled = vpsIp.isNotBlank() && !busy) {
            val a = api() ?: return@WizardPrimary
            busy = true
            scope.launch {
                val url = "socks5://$exitUser:$exitPass@$vpsIp:1080"
                added = when (val r = a.addExits(listOf(url to null))) {
                    is StudioResult.Ok -> {
                        // Tested at once: an exit added without a country is filed by where the engine
                        // sees it leave from, which is the only country worth trusting.
                        r.value.added.firstOrNull()?.let { a.testExit(it) }
                        reload()
                        context.getString(R.string.studio_locations_vps_added)
                    }
                    is StudioResult.Err -> messageFor(context, r.error)
                }
                busy = false
            }
        }
        added?.let { SettingsFooter(it) }

        // ---- public servers --------------------------------------------------------------
        if (hasPool) {
            val p = pool
            SettingsSectionHeader(S(R.string.studio_locations_public))
            SettingsGroup {
                SettingsToggle(
                    title = S(R.string.studio_locations_public_toggle),
                    subtitle = if (p?.enabled == true) S(R.string.studio_locations_public_on) else S(R.string.studio_locations_public_off),
                    checked = p?.enabled == true,
                    onCheckedChange = { on -> if (on) askEnable = true else setEnabled(false) },
                )
            }
            SettingsFooter(S(R.string.studio_locations_public_note))

            if (p?.enabled == true) {
                val countries = p.countries.filter { it !in own }
                SettingsSectionHeader(S(R.string.studio_locations_public_countries).replace("%1\$s", faNum(countries.size)))
                SettingsGroup {
                    countries.forEachIndexed { i, cc ->
                        if (i > 0) Separator()
                        val v = verified[cc]
                        SettingsRow(
                            title = ExitCountries.name(cc),
                            subtitle = when {
                                cc in checking -> S(R.string.studio_country_checking)
                                v == null -> S(R.string.studio_locations_tap_to_check)
                                v.first > 0 -> S(R.string.studio_country_verified).replace("%1\$s", faNum(v.first)) +
                                    (v.second?.let { " · " + faNum(it) + " ms" } ?: "")
                                else -> S(R.string.studio_country_none)
                            },
                            icon = if (v != null && v.first > 0) StudioIcons.Verified else StudioIcons.PublicServer,
                            tint = when {
                                v == null -> Ios.Gray
                                v.first > 0 -> Ios.Green
                                else -> Ios.Orange
                            },
                            value = cc,
                            showChevron = false,
                            onClick = { if (cc !in checking) check(listOf(cc)) },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                SettingsGroup {
                    SettingsActionRow(
                        label = S(R.string.studio_locations_check_all),
                        icon = StudioIcons.Test,
                        tint = Ios.Blue,
                        busy = checking.isNotEmpty(),
                        onClick = { check(countries.filter { it !in checking }) },
                    )
                }
                SettingsFooter(S(R.string.studio_locations_check_note))
            }
        }

        SettingsSectionHeader(S(R.string.studio_locations_use))
        SettingsGroup {
            SettingsActionRow(label = S(R.string.studio_new_user), icon = StudioIcons.NewUser, tint = Ios.Green, onClick = onNewUser)
        }
        SettingsFooter(S(R.string.studio_locations_use_note))
        Spacer(Modifier.height(28.dp))
    }
}

/** A row's leading code badge, for lists that name countries. */
@Composable
internal fun CountryTitle(cc: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CountryBadge(cc)
        Spacer(Modifier.width(8.dp))
        androidx.compose.material3.Text(ExitCountries.name(cc), color = Ios.Label)
    }
}
