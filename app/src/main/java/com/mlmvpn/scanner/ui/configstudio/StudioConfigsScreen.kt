package com.mlmvpn.scanner.ui.configstudio

import com.mlmvpn.scanner.data.studio.config.LocationConfigs
import com.mlmvpn.scanner.data.studio.domain.ExitCountries
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.studio.StudioStore
import com.mlmvpn.scanner.data.studio.api.StudioHttpApi
import com.mlmvpn.scanner.data.studio.api.StudioResult
import com.mlmvpn.scanner.data.studio.config.ConfigDraft
import com.mlmvpn.scanner.data.studio.config.ConfigShape
import com.mlmvpn.scanner.data.studio.config.StudioCredentials
import com.mlmvpn.scanner.data.studio.config.StudioTemplates
import com.mlmvpn.scanner.data.studio.domain.StudioConfig
import com.mlmvpn.scanner.ui.configstudio.design.CountryBadge
import com.mlmvpn.scanner.ui.configstudio.design.EmptyState
import com.mlmvpn.scanner.ui.configstudio.design.StatusLine
import com.mlmvpn.scanner.ui.configstudio.design.StudioBadge
import com.mlmvpn.scanner.ui.configstudio.design.StudioIcons
import com.mlmvpn.scanner.ui.configstudio.design.StudioSeparator
import com.mlmvpn.scanner.ui.configstudio.design.StudioType
import com.mlmvpn.scanner.ui.configstudio.parts.InfoCard
import com.mlmvpn.scanner.ui.configstudio.parts.protocolTint
import com.mlmvpn.scanner.ui.configstudio.parts.ProblemCard
import com.mlmvpn.scanner.ui.configstudio.parts.WizardBusy
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosAlert
import com.mlmvpn.scanner.ui.settings.IosAlertAction
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.utils.S
import kotlinx.coroutines.launch

/**
 * The configs one person holds.
 *
 * Several rather than one, because a config is the unit that can be **revoked on its own**: someone
 * with a phone and a laptop who loses the phone should lose one link, not their subscription. Every
 * config a user has is served by their one subscription link, so adding or rotating one changes what
 * their client picks up on its next fetch and asks nothing of them.
 *
 * Two things this screen is careful to say out loud:
 *
 *  * **Rotating takes effect on the next connection.** A tunnel authenticated once, when it opened,
 *    and nothing re-checks it — so an open session survives the rotation that was meant to end it.
 *    "Revoked" that leaves a live tunnel up for another hour is the kind of half-truth an operator
 *    acts on.
 *  * **The shapes offered are what this engine can serve**, not what the model can express: VLESS
 *    and Trojan over WebSocket, and XHTTP for a network that blocks WebSocket. gRPC, raw TCP and
 *    httpupgrade cannot be Worker inbounds at all.
 *
 * Each row names its protocol and where it leaves from as badges (build 18 redesign), so a person
 * with six configs can be read at a glance rather than config by config.
 */
@Composable
fun StudioConfigsScreen(
    store: StudioStore,
    installationId: String,
    userId: String,
    username: String,
    onBack: () -> Unit,
    /** Opens the builder. [ConfigDraft] non-null means "start from a copy of this one". */
    onAddConfig: (ConfigDraft?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val account = remember(installationId) {
        store.installedAccounts().firstOrNull { it.id == installationId }
    }
    var configs by remember { mutableStateOf<List<StudioConfig>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmRotate by remember { mutableStateOf<StudioConfig?>(null) }
    var confirmDelete by remember { mutableStateOf<StudioConfig?>(null) }
    // «تغییر لوکیشن»: the config being moved, and the countries this account has a working exit in.
    var movingExit by remember { mutableStateOf<StudioConfig?>(null) }
    var countries by remember { mutableStateOf<List<String>>(emptyList()) }
    val xhttpCarriesCountry = store.state.value.installations
        .firstOrNull { it.installationId == installationId }?.can("xhttp.v2") == true
    LaunchedEffect(installationId) {
        val acc = account ?: return@LaunchedEffect
        val canExit = store.state.value.installations.firstOrNull { it.installationId == acc.id }?.can("exits.v1") == true
        if (canExit) countries = LocationConfigs.availableCountries(StudioHttpApi(context, acc))
    }

    suspend fun reload() {
        val acc = account ?: return
        when (val res = StudioHttpApi(context, acc).listConfigs(userId)) {
            is StudioResult.Ok -> { configs = res.value; error = null }
            is StudioResult.Err -> error = messageFor(context, res.error)
        }
    }

    LaunchedEffect(installationId, userId) { reload() }

    IosScreen(
        title = S(R.string.studio_section_configs),
        onBack = if (busy) null else onBack,
        backLabel = username.ifBlank { S(R.string.studio_users) },
        onRefresh = { reload() },
    ) {
        Spacer(Modifier.height(10.dp))
        note?.let { InfoCard(it); Spacer(Modifier.height(12.dp)) }
        error?.let { ProblemCard(it); Spacer(Modifier.height(12.dp)) }

        val list = configs
        when {
            account == null -> InfoCard(S(R.string.studio_engine_unreachable))
            list == null -> WizardBusy(S(R.string.studio_loading))

            else -> {
                if (list.isEmpty()) {
                    // A user with no config has a subscription link that resolves to an EMPTY
                    // document: HTTP 200, zero servers. Their client shows nothing and they have no
                    // way to tell that from a broken link, so this is stated rather than left blank.
                    EmptyState(
                        icon = StudioIcons.Config,
                        title = S(R.string.studio_configs_empty_title),
                        body = S(R.string.studio_configs_none),
                    )
                } else {
                    SettingsSectionHeader(
                        S(R.string.studio_configs_count).replace("%1\$s", faNum(list.size))
                    )
                    SettingsGroup {
                        list.forEachIndexed { index, config ->
                            if (index > 0) StudioSeparator()
                            ConfigRow(config) { confirmRotate = config }
                        }
                    }
                    SettingsFooter(S(R.string.studio_configs_tap_hint))
                    // The paths used to be repeated under the card as a loose column of monospace
                    // lines. They are already the second half of every row's subtitle, and detached
                    // from the rows they could not say WHICH config each one belonged to -- so the
                    // list was both a duplicate and unreadable.
                }

                Spacer(Modifier.height(14.dp))
                SettingsGroup {
                    SettingsActionRow(
                        label = S(R.string.studio_config_add),
                        icon = StudioIcons.Add,
                        tint = Ios.Blue,
                        enabled = !busy,
                    ) { onAddConfig(null) }
                    // A copy of the newest one, which is what "another for their laptop" means:
                    // same shape, same handshake, its own credential and its own path — so it can
                    // be revoked on its own, which is the entire reason to have two.
                    list.orEmpty().firstOrNull { LocationConfigs.isOffered(configShapeOf(it)) }?.let { newest ->
                        Separator()
                        SettingsActionRow(
                            label = S(R.string.studio_builder_clone),
                            icon = StudioIcons.Copy,
                            tint = Ios.Indigo,
                            enabled = !busy,
                        ) { onAddConfig(draftOf(newest)) }
                    }
                }
                InfoCard(S(R.string.studio_configs_note))
            }
        }

        Spacer(Modifier.height(28.dp))
    }

    confirmRotate?.let { config ->
        val acc = account
        IosAlert(
            title = config.label?.let { ExitCountries.stripFlags(it) }?.takeIf { it.isNotBlank() }
                ?: S(R.string.studio_config_unnamed),
            message = S(R.string.studio_config_rotate_body),
            actions = listOf(
                IosAlertAction(S(R.string.studio_cancel), { confirmRotate = null }),
                IosAlertAction(S(R.string.studio_config_rotate), {
                    confirmRotate = null
                    if (acc != null) {
                        busy = true; error = null; note = null
                        scope.launch {
                            // The shape is recovered from the row rather than remembered, because
                            // the config may have been made on another device.
                            val shape = configShapeOf(config)
                            val credential = StudioCredentials.newCredential(shape.protocol)
                            val res = StudioHttpApi(context, acc).rotateConfig(
                                id = config.id,
                                credential = credential,
                                // **The stored template, not a rebuilt one.** The credential is a
                                // `{{cred}}` placeholder, so rotating never needs to touch the
                                // template — and rebuilding it from the shape alone would quietly
                                // reset the fingerprint and ALPN the operator chose, turning a
                                // credential rotation into a silent redesign of the link. The
                                // fallback covers a row written before templates were stored.
                                uriTemplate = config.uriTemplate
                                    ?: StudioTemplates.templateFor(shape),
                                authHash = StudioCredentials.authHash(shape.protocol, credential),
                            )
                            when (res) {
                                is StudioResult.Ok -> { note = context.getString(R.string.studio_rotated); reload() }
                                is StudioResult.Err -> error = messageFor(context, res.error)
                            }
                            busy = false
                        }
                    }
                }, destructive = true),
                IosAlertAction(S(R.string.studio_delete), {
                    confirmDelete = config
                    confirmRotate = null
                }, destructive = true),
            ) + (
                // Before build 18, XHTTP was looked up by the user's uuid, never by config, so it could
                // not carry a country. From build 18 on it is looked up like the others.
                if ((countries.isNotEmpty() || config.exitCc != null) &&
                    (configShapeOf(config) != ConfigShape.VLESS_XHTTP || xhttpCarriesCountry))
                    listOf(IosAlertAction(S(R.string.studio_location_change), { movingExit = config; confirmRotate = null }))
                else emptyList()
            ),
            onDismiss = { confirmRotate = null },
        )
    }

    movingExit?.let { config ->
        val acc = account
        fun move(cc: String?) {
            movingExit = null
            if (acc == null) return
            busy = true; error = null; note = null
            scope.launch {
                when (val res = StudioHttpApi(context, acc).moveConfigExit(config.id, cc)) {
                    is StudioResult.Ok -> { note = context.getString(R.string.studio_saved); reload() }
                    is StudioResult.Err -> error = messageFor(context, res.error)
                }
                busy = false
            }
        }
        IosAlert(
            title = S(R.string.studio_location_change),
            message = S(R.string.studio_location_change_body),
            actions = countries.filter { it != config.exitCc }.map { cc ->
                IosAlertAction(ExitCountries.label(cc), { move(cc) })
            } + listOfNotNull(
                config.exitCc?.let { IosAlertAction(S(R.string.studio_location_none), { move(null) }) },
                IosAlertAction(S(R.string.studio_cancel), { movingExit = null }),
            ),
            onDismiss = { movingExit = null },
        )
    }

    confirmDelete?.let { config ->
        val acc = account
        IosAlert(
            title = S(R.string.studio_config_delete_title),
            message = S(R.string.studio_config_delete_body),
            actions = listOf(
                IosAlertAction(S(R.string.studio_cancel), { confirmDelete = null }),
                IosAlertAction(S(R.string.studio_delete), {
                    confirmDelete = null
                    if (acc != null) {
                        busy = true; error = null
                        scope.launch {
                            when (val res = StudioHttpApi(context, acc).deleteConfig(config.id)) {
                                is StudioResult.Ok -> { note = context.getString(R.string.studio_saved); reload() }
                                is StudioResult.Err -> error = messageFor(context, res.error)
                            }
                            busy = false
                        }
                    }
                }, destructive = true),
            ),
            onDismiss = { confirmDelete = null },
        )
    }
}

/**
 * A draft that would reproduce this config, for the copy action.
 *
 * Read off the row rather than remembered, because the config may have been created on another
 * device — and what the row stores is the protocol letter and the transport name, which is exactly
 * enough to name the shape. The credential and the path are deliberately NOT copied: a copy that
 * shared them would be the same config twice, revocable only together, which is the opposite of
 * why a second one is made.
 */
private fun draftOf(config: StudioConfig): ConfigDraft = ConfigDraft(
    shape = configShapeOf(config),
    label = ExitCountries.stripFlags(config.label.orEmpty()),
)

/**
 * One config: its name (without the flag older builds saved into it), its protocol as a coloured
 * badge, where it leaves from -- a country badge and the address it was measured leaving from, or
 * «مستقیم» -- and its path. A switched-off config says so instead of its path.
 */
@Composable
private fun ConfigRow(config: StudioConfig, onClick: () -> Unit) {
    val shape = configShapeOf(config)
    val tint = protocolTint(shape)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(tint.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(StudioIcons.Config, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                config.label?.let { ExitCountries.stripFlags(it) }?.takeIf { it.isNotBlank() }
                    ?: S(R.string.studio_config_unnamed),
                color = if (config.enabled) Ios.Label else Ios.SecondaryLabel,
                fontSize = StudioType.Callout,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StudioBadge(LocationConfigs.shortName(shape), tint = tint)
                val cc = config.exitCc
                if (!LocationConfigs.isOffered(shape)) {
                    // Kept, and listed, but not served: see LocationConfigs.OFFERED.
                    StatusLine(S(R.string.studio_config_xhttp_paused), Ios.Orange)
                } else if (cc != null) {
                    CountryBadge(cc)
                    Text(
                        ExitCountries.label(cc) + (config.exitIp?.let { " · $it" } ?: ""),
                        color = Ios.SecondaryLabel,
                        fontSize = StudioType.Caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (!config.enabled) {
                    StatusLine(S(R.string.studio_config_off), Ios.Gray)
                } else {
                    Text(
                        S(R.string.studio_created_direct) + " · " + (config.routeKey?.let { "/$it" } ?: "/"),
                        color = Ios.SecondaryLabel,
                        fontSize = StudioType.Caption,
                        fontFamily = if (config.routeKey != null) FontFamily.Monospace else null,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
