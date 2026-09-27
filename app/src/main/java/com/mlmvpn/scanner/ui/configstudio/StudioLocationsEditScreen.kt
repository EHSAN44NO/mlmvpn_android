package com.mlmvpn.scanner.ui.configstudio

import android.widget.Toast
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import com.mlmvpn.scanner.data.studio.StudioStore
import com.mlmvpn.scanner.data.studio.api.StudioError
import com.mlmvpn.scanner.data.studio.api.StudioHttpApi
import com.mlmvpn.scanner.data.studio.api.StudioResult
import com.mlmvpn.scanner.data.studio.config.ConfigShape
import com.mlmvpn.scanner.data.studio.config.LocationConfigs
import com.mlmvpn.scanner.data.studio.domain.StudioConfig
import com.mlmvpn.scanner.ui.configstudio.design.NoticeCard
import com.mlmvpn.scanner.ui.configstudio.design.ProtocolPicker
import com.mlmvpn.scanner.ui.configstudio.design.StudioIcons
import com.mlmvpn.scanner.ui.configstudio.parts.LocationPicker
import com.mlmvpn.scanner.ui.configstudio.parts.PortPicker
import com.mlmvpn.scanner.ui.configstudio.parts.ProblemCard
import com.mlmvpn.scanner.ui.configstudio.parts.WizardBusy
import com.mlmvpn.scanner.ui.configstudio.parts.WizardPrimary
import com.mlmvpn.scanner.ui.configstudio.parts.rememberCountryChoices
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.utils.S
import kotlinx.coroutines.launch

/**
 * «کشورها و پورت‌ها» for someone who exists: the same pickers as «کاربر جدید» (build 18 redesign).
 *
 * Saving applies the difference and nothing else: a country added gets a config for each of the
 * person's protocols, a country removed loses exactly its configs, and the «direct» switch now does
 * what it says -- it used to be drawn and ignored.
 */
@Composable
fun StudioLocationsEditScreen(
    store: StudioStore,
    installationId: String,
    userId: String,
    username: String,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val account = remember { store.installedAccounts().firstOrNull { it.id == installationId } }
    val api = remember(account) { account?.let { StudioHttpApi(context, it) } }
    val inst = store.state.value.installations.firstOrNull { it.installationId == installationId }

    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var configs by remember { mutableStateOf<List<StudioConfig>>(emptyList()) }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var ports by remember { mutableStateOf(setOf(443)) }
    var includeDirect by remember { mutableStateOf(true) }
    var protocols by remember { mutableStateOf(LocationConfigs.DEFAULT) }
    val choices = rememberCountryChoices(context, account, active = !loading)

    LaunchedEffect(api) {
        val a = api ?: return@LaunchedEffect
        when (val u = a.getUser(userId)) {
            is StudioResult.Ok -> ports = u.value.ports.toSet().ifEmpty { setOf(443) }
            is StudioResult.Err -> error = messageFor(context, u.error)
        }
        when (val c = a.listConfigs(userId)) {
            is StudioResult.Ok -> configs = c.value
            is StudioResult.Err -> error = messageFor(context, c.error)
        }
        picked = configs.mapNotNull { it.exitCc }.toSet()
        includeDirect = configs.any { it.exitCc == null }
        // The person's own protocols, so a country added now gets what their other configs are.
        protocols = configs.map { configShapeOf(it) }.filter { LocationConfigs.isOffered(it) }.toSet()
            .ifEmpty { LocationConfigs.DEFAULT }
        loading = false
    }

    fun save() {
        val a = api ?: return
        busy = true; error = null
        scope.launch {
            val current = configs.mapNotNull { it.exitCc }.toSet()
            val added = picked - current
            val removed = current - picked
            val hadDirect = configs.any { it.exitCc == null }
            // Removed first: nothing the person keeps is touched, and a failure part-way leaves them
            // with fewer countries rather than a half-written extra one.
            val drop = configs.filter { it.exitCc in removed } +
                (if (hadDirect && !includeDirect && picked.isNotEmpty()) configs.filter { it.exitCc == null } else emptyList())
            for (cfg in drop) {
                val r = a.deleteConfig(cfg.id)
                if (r is StudioResult.Err) { busy = false; error = messageFor(context, r.error); return@launch }
            }
            val addDirect = includeDirect && !hadDirect
            if (added.isNotEmpty() || addDirect) {
                val user = (a.getUser(userId) as? StudioResult.Ok)?.value
                if (user == null) { busy = false; error = messageFor(context, StudioError(StudioError.NETWORK)); return@launch }
                val outcome = LocationConfigs.create(
                    a, user, protocols, template = null,
                    countries = added.toList(),
                    includeDirect = addDirect,
                    xhttpCarriesCountry = inst?.can("xhttp.v2") == true,
                )
                if (outcome.made.isEmpty() && outcome.firstError != null) {
                    busy = false; error = messageFor(context, outcome.firstError!!); return@launch
                }
            }
            val pr = a.setPorts(userId, ports.sorted())
            if (pr is StudioResult.Err) { busy = false; error = messageFor(context, pr.error); return@launch }
            runCatching { store.syncAll() }
            busy = false
            Toast.makeText(context, context.getString(R.string.studio_locations_saved), Toast.LENGTH_SHORT).show()
            onDone()
        }
    }

    IosScreen(title = S(R.string.studio_locations_edit_title), onBack = if (busy) null else onBack, backLabel = username) {
        Spacer(Modifier.height(10.dp))
        if (loading) { WizardBusy(S(R.string.studio_loading)); return@IosScreen }
        error?.let { ProblemCard(it) }

        SettingsSectionHeader(S(R.string.studio_locations_countries))
        val countries = (choices.countries + picked).distinct().sorted()
        if (countries.isEmpty()) {
            NoticeCard(
                icon = StudioIcons.Locations,
                tint = Ios.Orange,
                title = S(R.string.studio_new_no_countries_title),
                body = S(R.string.studio_new_no_countries_body),
            )
        } else {
            LocationPicker(
                countries = countries,
                picked = picked,
                onToggle = { cc ->
                    picked = if (cc in picked) picked - cc else picked + cc
                    if (cc in picked) choices.verify(cc)
                },
                includeDirect = includeDirect,
                onIncludeDirect = { includeDirect = it },
                status = choices.status,
            )
            SettingsFooter(S(R.string.studio_locations_edit_note))
        }

        SettingsSectionHeader(S(R.string.studio_locations_new_protocols))
        ProtocolPicker(
            selected = protocols,
            onChange = { protocols = it },
        )

        SettingsSectionHeader(S(R.string.studio_new_ports))
        PortPicker(picked = ports, onToggle = { p -> ports = if (p in ports) (ports - p).ifEmpty { setOf(443) } else ports + p })

        Spacer(Modifier.height(20.dp))
        if (busy) WizardBusy(S(R.string.studio_saving))
        else WizardPrimary(S(R.string.studio_save)) { save() }
        Spacer(Modifier.height(28.dp))
    }
}

/** Which of the offered protocols a stored config is. */
internal fun configShapeOf(config: StudioConfig): ConfigShape = when {
    config.transportType.equals("xhttp", ignoreCase = true) -> ConfigShape.VLESS_XHTTP
    config.protocol == "t" -> ConfigShape.TROJAN_WS
    else -> ConfigShape.VLESS_WS
}
