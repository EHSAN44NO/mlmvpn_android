package com.mlmvpn.scanner.ui.configstudio

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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.studio.StudioStore
import com.mlmvpn.scanner.data.studio.api.StudioError
import com.mlmvpn.scanner.data.studio.api.StudioHttpApi
import com.mlmvpn.scanner.data.studio.api.StudioResult
import com.mlmvpn.scanner.data.studio.config.ConfigShape
import com.mlmvpn.scanner.data.studio.config.LocationConfigs
import com.mlmvpn.scanner.data.studio.config.toDraft
import com.mlmvpn.scanner.data.studio.domain.Enforcement
import com.mlmvpn.scanner.data.studio.domain.ExitCountries
import com.mlmvpn.scanner.data.studio.domain.ExpiryMode
import com.mlmvpn.scanner.data.studio.domain.Plan
import com.mlmvpn.scanner.data.studio.domain.StudioUser
import com.mlmvpn.scanner.data.studio.domain.Subscription
import com.mlmvpn.scanner.data.studio.domain.SubscriptionPolicy
import com.mlmvpn.scanner.ui.configstudio.design.ChoiceRow
import com.mlmvpn.scanner.ui.configstudio.design.NoticeCard
import com.mlmvpn.scanner.ui.configstudio.design.ProtocolPicker
import com.mlmvpn.scanner.ui.configstudio.design.SegmentedControl
import com.mlmvpn.scanner.ui.configstudio.design.StudioIcons
import com.mlmvpn.scanner.ui.configstudio.design.StudioSeparator
import com.mlmvpn.scanner.ui.configstudio.parts.CreatedConfigsCard
import com.mlmvpn.scanner.ui.configstudio.parts.LinkCountLine
import com.mlmvpn.scanner.ui.configstudio.parts.LocationPicker
import com.mlmvpn.scanner.ui.configstudio.parts.PageIntro
import com.mlmvpn.scanner.ui.configstudio.parts.PayloadCard
import com.mlmvpn.scanner.ui.configstudio.parts.PortPicker
import com.mlmvpn.scanner.ui.configstudio.parts.ProblemCard
import com.mlmvpn.scanner.ui.configstudio.parts.StudioQrCard
import com.mlmvpn.scanner.ui.configstudio.parts.StudioShare
import com.mlmvpn.scanner.ui.configstudio.parts.VolumeField
import com.mlmvpn.scanner.ui.configstudio.parts.VolumeUnit
import com.mlmvpn.scanner.ui.configstudio.parts.WizardBusy
import com.mlmvpn.scanner.ui.configstudio.parts.WizardField
import com.mlmvpn.scanner.ui.configstudio.parts.WizardPrimary
import com.mlmvpn.scanner.ui.configstudio.parts.parseVolumeBytes
import com.mlmvpn.scanner.ui.configstudio.parts.rememberCountryChoices
import com.mlmvpn.scanner.ui.configstudio.parts.volumeToField
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.utils.S
import kotlinx.coroutines.launch

/**
 * «کاربر جدید» -- a person, their configs and their link, in one screen (build 18 redesign).
 *
 * The operator chooses, in this order: a name, the terms (a «بسته» fills them in), the PROTOCOLS
 * (VLESS and Trojan ticked, XHTTP offered), and whether they connect directly or from chosen
 * countries. Then one button, and the screen turns into what was made: every config by protocol and
 * country, and the link to hand over. Until build 18 the protocol came silently from a plan's template
 * and the result was a toast.
 *
 * Three engine calls behind one button, in an order that matters: the user, then the configs, then
 * the link -- a subscription with no config resolves to an empty document, which looks like a broken
 * link to the person holding it.
 */
@Composable
fun StudioNewUserScreen(
    store: StudioStore,
    state: com.mlmvpn.scanner.data.studio.StudioState,
    onBack: () -> Unit,
    /** Their page, from the result view. */
    onOpenUser: (installationId: String, userId: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var username by remember { mutableStateOf("") }
    var volume by remember { mutableStateOf("") }
    var volumeUnit by remember { mutableStateOf(VolumeUnit.GB) }
    var days by remember { mutableStateOf("") }
    var devices by remember { mutableStateOf("") }
    var plans by remember { mutableStateOf<List<Plan>>(emptyList()) }
    var picked by remember { mutableStateOf<Plan?>(null) }
    var protocols by remember { mutableStateOf(LocationConfigs.DEFAULT) }

    var placement by remember { mutableStateOf(store.placementFor()) }
    var pickingShard by remember { mutableStateOf(false) }
    val fleet = remember(state.installations) { store.installedAccounts() }
    val inst = placement?.id?.let { id -> state.installations.firstOrNull { it.installationId == id } }

    LaunchedEffect(Unit) {
        plans = store.refreshPlans().filter { !it.archived }
        picked = plans.firstOrNull { it.isDefault }
        picked?.let { applyPlanToFields(it) { v, u, d, dev -> volume = v; volumeUnit = u; days = d; devices = dev } }
    }

    // «از کجا وصل شوند»: directly, or from countries. A segmented choice rather than the old
    // «ساده / مولتی لوکیشن» rows, which named an implementation rather than the question.
    var withCountries by remember { mutableStateOf(false) }
    var pickedCountries by remember { mutableStateOf<Set<String>>(emptySet()) }
    var includeDirect by remember { mutableStateOf(true) }
    var ports by remember { mutableStateOf(setOf(443)) }
    val canExit = inst?.can("exits.v1") == true
    val choices = rememberCountryChoices(context, placement, active = withCountries && canExit)
    LaunchedEffect(choices.countries) { pickedCountries = pickedCountries.filter { it in choices.countries }.toSet() }

    val canEnforce = inst?.can("enforcement.strict") == true
    val xhttpCarries = inst?.can("xhttp.v2") == true

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<Created?>(null) }
    var copied by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }

    fun create() {
        val name = username.trim()
        if (name.isEmpty()) { error = context.getString(R.string.studio_username_required); return }
        if (store.usernameTaken(name)) { error = context.getString(R.string.studio_username_taken_here); return }
        val account = placement ?: return
        busy = true
        error = null
        scope.launch {
            val typedQuota = parseVolumeBytes(volume, volumeUnit)
            val typedDays = days.trim().toIntOrNull()?.takeIf { it > 0 }
            val typedDevices = devices.trim().toIntOrNull()?.takeIf { it > 0 }

            // Start from the «بسته» and override only what this screen shows, so the terms a plan
            // carries that have no field here are not lost.
            val base = picked?.toPolicy() ?: SubscriptionPolicy()
            val onFirstConnect = base.expiryMode == ExpiryMode.ON_FIRST_CONNECT
            val policy = base.copy(
                quotaBytes = typedQuota,
                expiresAt = if (onFirstConnect) null else typedDays?.let { System.currentTimeMillis() + it * 86400000L },
                activationDays = if (onFirstConnect) typedDays else null,
                deviceLimit = typedDevices,
                // A number typed here is a limit that applies (build 18). It is written as such even
                // where this account cannot count devices yet, so it takes effect the moment the
                // engine can -- the notice above says which is the case.
                enforcement = if (typedDevices != null) Enforcement.STRICT else base.enforcement,
            )

            when (val user = store.createUser(account, name, policy, planId = picked?.id)) {
                is StudioResult.Err -> {
                    busy = false
                    error = messageFor(context, user.error)
                }
                is StudioResult.Ok -> {
                    val api = StudioHttpApi(context, account)
                    val template = picked?.templateId?.let { id -> state.templates.firstOrNull { it.id == id } }
                    val outcome = LocationConfigs.create(
                        api, user.value, protocols, template,
                        countries = if (withCountries) pickedCountries.toList() else emptyList(),
                        includeDirect = if (withCountries) includeDirect else true,
                        xhttpCarriesCountry = xhttpCarries,
                    )
                    if (withCountries && outcome.made.isNotEmpty() && ports != setOf(443) && ports.isNotEmpty()) {
                        api.setPorts(user.value.id, ports.sorted())
                    }
                    if (outcome.made.isEmpty()) {
                        busy = false
                        error = messageFor(context, outcome.firstError ?: StudioError(StudioError.NETWORK))
                        return@launch
                    }
                    val sub = (api.getSubscription(user.value.id) as? StudioResult.Ok)?.value
                    store.syncAll()
                    busy = false
                    result = Created(account.id, user.value, outcome, sub)
                }
            }
        }
    }

    IosScreen(
        title = S(R.string.studio_new_user_title),
        onBack = if (busy) null else onBack,
        backLabel = S(R.string.studio_users),
    ) {
        val done = result
        if (done != null) {
            ResultView(
                done = done,
                copied = copied,
                showQr = showQr,
                onCopy = { done.subscription?.let { clipboard.setText(AnnotatedString(it.url)); copied = true } },
                onShare = { done.subscription?.let { StudioShare.shareText(context, it.url, done.user.username) } },
                onToggleQr = { showQr = !showQr },
                onOpenUser = { onOpenUser(done.installationId, done.user.id) },
                onDone = onBack,
            )
            return@IosScreen
        }

        PageIntro(S(R.string.studio_new_user_sub))

        SettingsSectionHeader(S(R.string.studio_field_username))
        WizardField(username, { username = it; error = null }, S(R.string.studio_field_username_hint))

        if (fleet.size > 1) {
            SettingsSectionHeader(S(R.string.studio_placement))
            SettingsGroup {
                if (!pickingShard) {
                    SettingsRow(
                        title = placement?.let { it.name.ifEmpty { it.email } } ?: S(R.string.studio_placement_none),
                        subtitle = S(R.string.studio_placement_auto),
                        icon = StudioIcons.Accounts,
                        tint = Ios.Gray,
                        onClick = { pickingShard = true },
                    )
                } else {
                    fleet.forEachIndexed { index, acc ->
                        if (index > 0) StudioSeparator()
                        val i = state.installations.firstOrNull { it.installationId == acc.id }
                        ChoiceRow(
                            title = acc.name.ifEmpty { acc.email },
                            subtitle = shardPlacementNote(i),
                            selected = placement?.id == acc.id,
                            onClick = { placement = acc; pickingShard = false },
                        )
                    }
                }
            }
        }

        if (plans.isNotEmpty()) {
            SettingsSectionHeader(S(R.string.studio_plan_pick))
            SettingsGroup {
                plans.forEachIndexed { index, plan ->
                    if (index > 0) StudioSeparator()
                    ChoiceRow(
                        title = plan.name.ifBlank { plan.id },
                        subtitle = termsOf(plan),
                        selected = picked?.id == plan.id,
                        onClick = {
                            picked = plan
                            applyPlanToFields(plan) { v, u, d, dev -> volume = v; volumeUnit = u; days = d; devices = dev }
                            // A plan whose template is a protocol not ticked yet adds it, so choosing
                            // «سانسور شدید» still gets its XHTTP -- visibly, in the picker below.
                            plan.templateId?.let { id -> state.templates.firstOrNull { it.id == id } }
                                ?.toDraft()?.shape?.takeIf { LocationConfigs.isOffered(it) }?.let { protocols = protocols + it }
                        },
                    )
                }
                StudioSeparator()
                ChoiceRow(
                    title = S(R.string.studio_plan_none),
                    selected = picked == null,
                    onClick = { picked = null },
                )
            }
        }

        SettingsSectionHeader(S(R.string.studio_field_volume))
        VolumeField(volume, { volume = it }, volumeUnit, { volumeUnit = it }, S(R.string.studio_field_volume_hint))

        SettingsSectionHeader(S(R.string.studio_field_days))
        WizardField(days, { days = it }, S(R.string.studio_field_days_hint))

        SettingsSectionHeader(S(R.string.studio_new_devices_title))
        WizardField(devices, { devices = it }, S(R.string.studio_field_devices_hint))
        if (canEnforce) {
            SettingsFooter(S(R.string.studio_new_devices_note))
        } else {
            NoticeCard(
                icon = StudioIcons.Devices,
                tint = Ios.Orange,
                title = S(R.string.studio_new_devices_off_title),
                body = S(R.string.studio_new_devices_off_body),
            )
        }

        SettingsSectionHeader(S(R.string.studio_new_proto_header))
        ProtocolPicker(
            selected = protocols,
            onChange = { protocols = it },
        )

        SettingsSectionHeader(S(R.string.studio_new_where))
        SegmentedControl(
            options = listOf(S(R.string.studio_new_where_direct), S(R.string.studio_new_where_countries)),
            selectedIndex = if (withCountries) 1 else 0,
            onSelect = { withCountries = it == 1 },
        )
        if (withCountries) {
            if (!canExit) {
                NoticeCard(icon = StudioIcons.EngineUpdate, tint = Ios.Blue, title = S(R.string.studio_home_update_title),
                    body = S(R.string.studio_new_where_old_engine))
            } else if (choices.countries.isEmpty()) {
                NoticeCard(
                    icon = StudioIcons.Locations,
                    tint = Ios.Orange,
                    title = S(R.string.studio_new_no_countries_title),
                    body = S(R.string.studio_new_no_countries_body),
                )
            } else {
                Spacer(Modifier.height(6.dp))
                LocationPicker(
                    countries = choices.countries,
                    picked = pickedCountries,
                    onToggle = { cc ->
                        pickedCountries = if (cc in pickedCountries) pickedCountries - cc else pickedCountries + cc
                        if (cc in pickedCountries) choices.verify(cc)
                    },
                    includeDirect = includeDirect,
                    onIncludeDirect = { includeDirect = it },
                    status = choices.status,
                )
                SettingsSectionHeader(S(R.string.studio_new_ports))
                PortPicker(picked = ports, onToggle = { p ->
                    ports = if (p in ports) (ports - p).ifEmpty { setOf(443) } else ports + p
                })
            }
        }

        Spacer(Modifier.height(14.dp))
        LinkCountLine(
            shapes = protocols.size,
            countries = if (withCountries) pickedCountries.size else 0,
            includeDirect = !withCountries || includeDirect,
            ports = if (withCountries) ports.size else 1,
        )

        Spacer(Modifier.height(14.dp))
        error?.let { ProblemCard(it); Spacer(Modifier.height(12.dp)) }
        if (busy) {
            WizardBusy(S(R.string.studio_creating))
        } else {
            WizardPrimary(
                S(R.string.studio_create),
                enabled = username.isNotBlank() && (!withCountries || pickedCountries.isNotEmpty()),
            ) { create() }
        }
        Spacer(Modifier.height(28.dp))
    }
}

/** What one create made: the person, their configs, and the link that carries them. */
private data class Created(
    val installationId: String,
    val user: StudioUser,
    val outcome: LocationConfigs.Outcome,
    val subscription: Subscription?,
)

@Composable
private fun ResultView(
    done: Created,
    copied: Boolean,
    showQr: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onToggleQr: () -> Unit,
    onOpenUser: () -> Unit,
    onDone: () -> Unit,
) {
    Spacer(Modifier.height(6.dp))
    NoticeCard(
        icon = StudioIcons.Ok,
        tint = Ios.Green,
        title = S(R.string.studio_new_ready_title).replace("%1\$s", done.user.username),
        body = S(R.string.studio_new_ready_body).replace("%1\$s", faNum(done.outcome.made.size)),
    )
    val failed = done.outcome.failed.mapNotNull { it.first }.distinct() + done.outcome.skippedXhttpCountries
    if (failed.isNotEmpty()) {
        NoticeCard(
            icon = StudioIcons.Warning,
            tint = Ios.Orange,
            title = S(R.string.studio_new_ready_partial),
            body = S(R.string.studio_new_ready_partial_body)
                .replace("%1\$s", failed.distinct().joinToString("، ") { ExitCountries.name(it) }),
        )
    }

    SettingsSectionHeader(S(R.string.studio_new_ready_configs))
    CreatedConfigsCard(done.outcome.made)

    done.subscription?.let { sub ->
        SettingsSectionHeader(S(R.string.studio_sub_link))
        PayloadCard(sub.url)
        Spacer(Modifier.height(10.dp))
        SettingsGroup {
            SettingsActionRow(
                label = if (copied) S(R.string.studio_copied) else S(R.string.studio_copy_link),
                icon = StudioIcons.Copy,
                tint = Ios.Blue,
                onClick = onCopy,
            )
            Separator()
            SettingsActionRow(label = S(R.string.studio_share), icon = StudioIcons.Share, tint = Ios.Blue, onClick = onShare)
            Separator()
            SettingsActionRow(label = S(R.string.studio_qr), icon = StudioIcons.Qr, tint = Ios.Blue, onClick = onToggleQr)
        }
        if (showQr) {
            Spacer(Modifier.height(10.dp))
            StudioQrCard(sub.url, S(R.string.studio_qr_too_long))
        }
    }

    Spacer(Modifier.height(18.dp))
    WizardPrimary(S(R.string.studio_new_open_page), onClick = onOpenUser)
    Spacer(Modifier.height(10.dp))
    SettingsGroup {
        SettingsActionRow(label = S(R.string.studio_done), icon = StudioIcons.Ok, tint = Ios.Gray, onClick = onDone)
    }
    Spacer(Modifier.height(28.dp))
}

/** A failure, in the app's own words -- from the engine's stable code, never its message. */
internal fun messageFor(context: android.content.Context, error: StudioError): String = context.getString(
    when (error.code) {
        StudioError.NETWORK -> R.string.studio_err_network
        StudioError.UNAUTHORIZED -> R.string.studio_err_unauthorized
        "username_taken" -> R.string.studio_err_username_taken
        "schema_stale" -> R.string.studio_err_schema_stale
        "no_exit" -> R.string.studio_err_no_exit
        else -> R.string.studio_err_generic
    }
)

@Composable
private fun shardPlacementNote(inst: com.mlmvpn.scanner.data.studio.domain.StudioInstallation?): String? = when {
    inst == null -> null
    !inst.reachable -> S(R.string.studio_shard_unreachable)
    inst.isStale(com.mlmvpn.scanner.data.PanelBuild.MLM, com.mlmvpn.scanner.data.PanelBuild.MLM_SCHEMA) ->
        S(R.string.studio_engine_update_available)
    inst.isCrowded -> S(R.string.studio_shard_crowded)
    else -> null
}

/**
 * Put a plan's terms into the form's fields, as the text a person would have typed, in Latin
 * digits -- the text is parsed back on save.
 */
private inline fun applyPlanToFields(plan: Plan, set: (String, VolumeUnit, String, String) -> Unit) {
    val (v, unit) = volumeToField(plan.quotaBytes)
    set(v, unit, plan.durationDays?.toString().orEmpty(), plan.deviceLimit?.toString().orEmpty())
}
