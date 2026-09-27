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
import com.mlmvpn.scanner.data.studio.BulkLink
import com.mlmvpn.scanner.data.studio.StudioState
import com.mlmvpn.scanner.data.studio.StudioStore
import com.mlmvpn.scanner.data.studio.api.StudioHttpApi
import com.mlmvpn.scanner.data.studio.api.StudioResult
import com.mlmvpn.scanner.data.studio.config.StudioTemplates
import com.mlmvpn.scanner.data.studio.config.payloadFor
import com.mlmvpn.scanner.data.studio.config.toDraft
import com.mlmvpn.scanner.data.studio.domain.Enforcement
import com.mlmvpn.scanner.data.studio.domain.ExpiryMode
import com.mlmvpn.scanner.data.studio.domain.Plan
import com.mlmvpn.scanner.data.studio.domain.SubscriptionPolicy
import com.mlmvpn.scanner.ui.configstudio.design.ChoiceRow
import com.mlmvpn.scanner.ui.configstudio.design.NoticeCard
import com.mlmvpn.scanner.ui.configstudio.design.ProtocolPicker
import com.mlmvpn.scanner.ui.configstudio.design.StudioIcons
import com.mlmvpn.scanner.ui.configstudio.design.StudioSeparator
import com.mlmvpn.scanner.ui.configstudio.parts.FactsCard
import com.mlmvpn.scanner.ui.configstudio.parts.LinkCountLine
import com.mlmvpn.scanner.ui.configstudio.parts.rememberCountryChoices
import com.mlmvpn.scanner.ui.configstudio.parts.InfoCard
import com.mlmvpn.scanner.ui.configstudio.parts.PageIntro
import com.mlmvpn.scanner.ui.configstudio.parts.ProblemCard
import com.mlmvpn.scanner.ui.configstudio.parts.StudioQrCard
import com.mlmvpn.scanner.ui.configstudio.parts.StudioShare
import com.mlmvpn.scanner.ui.configstudio.parts.WizardBusy
import com.mlmvpn.scanner.ui.configstudio.parts.WizardField
import com.mlmvpn.scanner.ui.configstudio.parts.WizardPrimary
import com.mlmvpn.scanner.ui.configstudio.parts.VolumeField
import com.mlmvpn.scanner.ui.configstudio.parts.LocationPicker
import com.mlmvpn.scanner.data.studio.config.LocationConfigs
import com.mlmvpn.scanner.ui.configstudio.parts.VolumeUnit
import com.mlmvpn.scanner.ui.configstudio.parts.parseVolumeBytes
import com.mlmvpn.scanner.ui.configstudio.parts.volumeToField
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.utils.S
import kotlinx.coroutines.launch

/**
 * Twenty people at once, from a prefix and a count.
 *
 * ### Why this is not a mode on «کاربر تازه»
 *
 * They share three fields and nothing else. One ends by handing over a link and can simply be
 * retried when it fails; this one ends by handing over a **list**, and a run that fails halfway has
 * already created people — so its failure has to be reported by name rather than as an error the
 * operator would read as "nothing happened".
 *
 * ### The three-call shape, repeated
 *
 * Each person costs the same three calls the single-user screen makes, in the same order and for
 * the same reason: create the user, attach a config, then read the subscription back. A
 * subscription with no config resolves to an **empty document** — HTTP 200, zero servers — so a
 * link handed over before the config exists is one that imports cleanly and never connects.
 *
 * ### Placement
 *
 * Every person in one run goes to **one** account, chosen once and shown before the run starts.
 * Spreading a batch across the fleet is defensible and is not what an operator means by "make
 * twenty for this reseller" — and it would make the resulting list span N workers, so the twenty
 * links would carry several different addresses with nothing on screen saying why.
 */
@Composable
fun StudioBulkUsersScreen(
    store: StudioStore,
    state: StudioState,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var prefix by remember { mutableStateOf("") }
    var count by remember { mutableStateOf("10") }
    var startAt by remember { mutableStateOf("1") }
    var volumeGb by remember { mutableStateOf("") }
    var volumeUnit by remember { mutableStateOf(VolumeUnit.GB) }
    var days by remember { mutableStateOf("") }
    var devices by remember { mutableStateOf("") }

    var plans by remember { mutableStateOf<List<Plan>>(emptyList()) }
    var picked by remember { mutableStateOf<Plan?>(null) }

    var placement by remember { mutableStateOf(store.placementFor()) }
    var pickingShard by remember { mutableStateOf(false) }
    val fleet = remember(state.installations) { store.installedAccounts() }

    // The countries the chosen account can send people out of, checked the way the single-user
    // screen checks them (parts/StudioCreateParts.kt).
    val inst = placement?.id?.let { id -> state.installations.firstOrNull { it.installationId == id } }
    val canExit = inst?.can("exits.v1") == true
    val choices = rememberCountryChoices(context, placement, active = canExit)
    val countries = choices.countries
    var pickedCountries by remember { mutableStateOf<Set<String>>(emptySet()) }
    var includeDirect by remember { mutableStateOf(true) }
    LaunchedEffect(countries) { pickedCountries = pickedCountries.filter { it in countries }.toSet() }
    var protocols by remember { mutableStateOf(LocationConfigs.DEFAULT) }

    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0 to 0) }
    var error by remember { mutableStateOf<String?>(null) }
    var made by remember { mutableStateOf<List<BulkLink>?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var qrOf by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        plans = store.refreshPlans().filter { !it.archived }
        picked = plans.firstOrNull { it.isDefault }
        picked?.let { p ->
            volumeToField(p.quotaBytes).let { (v, u) -> volumeGb = v; volumeUnit = u }
            days = p.durationDays?.toString().orEmpty()
            devices = p.deviceLimit?.toString().orEmpty()
        }
    }

    /**
     * The names this run would create, in order.
     *
     * Computed on every keystroke rather than at submit, because it is what the preview card shows
     * — and a preview that only appears after the run is a preview of nothing. Zero-padded to the
     * width of the largest number, so `mlm-008` and `mlm-012` sort next to each other in whatever
     * the operator pastes them into. Capped, because a typed count is a typed count: 3 becomes 3,
     * and a slipped 30000 becomes 500 with the reason said on screen.
     */
    val names = remember(prefix, count, startAt) {
        val n = count.trim().toIntOrNull()?.coerceIn(1, MAX_BATCH) ?: 0
        val from = startAt.trim().toIntOrNull()?.coerceAtLeast(0) ?: 1
        val width = (from + n - 1).toString().length
        val p = prefix.trim()
        if (p.isEmpty() || n == 0) emptyList()
        else (from until from + n).map { p + it.toString().padStart(width, '0') }
    }

    /**
     * Names already in use anywhere in the fleet.
     *
     * Answered from the local index in ONE query rather than one per name: this recomputes on every
     * keystroke, and a batch of five hundred would otherwise be five hundred main-thread lookups
     * per typed digit. The target account's `UNIQUE(username)` is still the authority — this only
     * means the operator finds out while typing rather than nineteen creations in.
     */
    val clashes = remember(names) {
        val taken = store.usernamesTaken(names)
        names.filter { it.lowercase() in taken }
    }

    fun create() {
        val account = placement ?: return
        if (names.isEmpty()) { error = context.getString(R.string.studio_bulk_new_needs_prefix); return }
        if (clashes.isNotEmpty()) { error = context.getString(R.string.studio_bulk_new_clash); return }

        busy = true; error = null; progress = 0 to names.size
        scope.launch {
            val typedQuota = parseVolumeBytes(volumeGb, volumeUnit)
            val typedDays = days.trim().toIntOrNull()?.takeIf { it > 0 }
            val typedDevices = devices.trim().toIntOrNull()?.takeIf { it > 0 }

            // Start from the «بسته», then override only the three numbers this screen shows —
            // exactly as the single-user screen does, and for the same reason: building the policy
            // from the fields alone drops the five terms a plan carries that have no field here.
            val base = picked?.toPolicy() ?: SubscriptionPolicy()
            val onFirstConnect = base.expiryMode == ExpiryMode.ON_FIRST_CONNECT

            val api = StudioHttpApi(context, account)
            val out = mutableListOf<BulkLink>()
            // Resolved once for the run rather than per person: the plan does not change mid-batch,
            // and looking it up two hundred times would be two hundred list scans for one answer.
            val shape = picked?.templateId?.let { id -> state.templates.firstOrNull { it.id == id } }

            names.forEachIndexed { i, name ->
                // Recomputed per person rather than hoisted: under ABSOLUTE expiry the policy holds
                // an absolute date, and a batch of two hundred takes long enough that the first and
                // the last would otherwise be stamped with the same instant — harmless, but it also
                // means a batch started at 23:59 would give everybody yesterday's date.
                val policy = base.copy(
                    quotaBytes = typedQuota,
                    expiresAt = if (onFirstConnect) null
                    else typedDays?.let { System.currentTimeMillis() + it * 86400000L },
                    activationDays = if (onFirstConnect) typedDays else null,
                    deviceLimit = typedDevices,
                    // A device limit typed here applies, exactly as on the single-user screen. Bulk
                    // runs used to create every person as monitor-only, with nothing saying so.
                    enforcement = if (typedDevices != null) Enforcement.STRICT else base.enforcement,
                )

                when (val user = store.createUser(account, name, policy, planId = picked?.id)) {
                    is StudioResult.Err -> out.add(BulkLink(name, null, user.error.code))
                    is StudioResult.Ok -> {
                        // The shape the chosen «بسته» names, rendered per person because the
                        // credential is: two hundred people sharing one uuid is one subscriber's
                        // link working for all of them.
                        // Plus one config per chosen country, each with its own credential.
                        val outcome = LocationConfigs.create(
                            api, user.value, protocols, shape,
                            countries = pickedCountries.toList(),
                            includeDirect = includeDirect,
                            xhttpCarriesCountry = inst?.can("xhttp.v2") == true,
                        )
                        if (outcome.made.isEmpty()) {
                            // The person exists and has nothing to serve. Recorded as a failure with
                            // their name on it rather than as a link, because a link to an empty
                            // document is worse than no link: it imports without complaint.
                            out.add(BulkLink(name, null, outcome.firstError?.code ?: "no_config"))
                        } else {
                            when (val sub = api.getSubscription(user.value.id)) {
                                is StudioResult.Ok -> out.add(BulkLink(name, sub.value.url))
                                is StudioResult.Err -> out.add(BulkLink(name, null, sub.error.code))
                            }
                        }
                    }
                }
                progress = (i + 1) to names.size
            }

            made = out
            busy = false
        }
    }

    IosScreen(
        title = S(R.string.studio_bulk_new_title),
        onBack = if (busy) null else onBack,
        backLabel = S(R.string.studio_users),
    ) {
        val done = made
        if (done != null) {
            // ---- the list, which is the product of this screen -----------------------------
            val ok = done.filter { it.url != null }
            val failed = done.filter { it.url == null }

            Spacer(Modifier.height(10.dp))
            FactsCard(S(R.string.studio_bulk_new_done).replace("%1\$s", faNum(ok.size)))
            if (failed.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                ProblemCard(S(R.string.studio_bulk_new_failed).replace("%1\$s", faNum(failed.size)))
                Spacer(Modifier.height(8.dp))
                FactsCard(failed.joinToString("\n") { it.username })
            }
            note?.let { Spacer(Modifier.height(10.dp)); InfoCard(it) }

            SettingsSectionHeader(S(R.string.studio_bulk_links))
            SettingsGroup {
                SettingsActionRow(
                    label = S(R.string.studio_bulk_copy_all),
                    icon = StudioIcons.Copy,
                    tint = Ios.Blue,
                ) {
                    clipboard.setText(AnnotatedString(asText(done, context)))
                    note = context.getString(R.string.studio_copied)
                }
                Separator()
                SettingsActionRow(
                    label = S(R.string.studio_share),
                    icon = StudioIcons.Share,
                    tint = Ios.Blue,
                ) {
                    StudioShare.shareText(
                        context, asText(done, context), context.getString(R.string.studio_bulk_links)
                    )
                }
                Separator()
                SettingsActionRow(
                    label = S(R.string.studio_export_file),
                    icon = StudioIcons.Export,
                    tint = Ios.Green,
                ) {
                    val saved = StudioShare.shareFile(
                        context,
                        fileName = (prefix.trim().ifBlank { "links" }) + ".txt",
                        content = asText(done, context),
                        subject = context.getString(R.string.studio_bulk_links),
                    )
                    note = context.getString(
                        if (saved) R.string.studio_exported else R.string.studio_export_failed
                    )
                }
            }
            InfoCard(S(R.string.studio_bulk_links_note))

            // One QR at a time, opened from the row. A grid of twenty QR codes is twenty squares
            // too small to scan and no way to tell whose is whose — the useful shape is the list,
            // with one enlarged when it is being handed over.
            SettingsSectionHeader(S(R.string.studio_bulk_new_list))
            SettingsGroup {
                ok.forEachIndexed { index, link ->
                    if (index > 0) Separator()
                    SettingsRow(
                        title = link.username,
                        value = if (qrOf == link.username) S(R.string.studio_qr_hide) else S(R.string.studio_qr),
                        icon = StudioIcons.Qr,
                        tint = Ios.Blue,
                        showChevron = false,
                        onClick = { qrOf = if (qrOf == link.username) null else link.username },
                    )
                }
            }
            qrOf?.let { name ->
                ok.firstOrNull { it.username == name }?.url?.let { url ->
                    Spacer(Modifier.height(10.dp))
                    StudioQrCard(url, S(R.string.studio_qr_too_long))
                }
            }

            Spacer(Modifier.height(18.dp))
            WizardPrimary(S(R.string.studio_done), onClick = onDone)
            Spacer(Modifier.height(28.dp))
            return@IosScreen
        }

        Spacer(Modifier.height(10.dp))
        PageIntro(S(R.string.studio_bulk_new_sub))

        SettingsSectionHeader(S(R.string.studio_bulk_new_prefix))
        WizardField(prefix, { prefix = it; error = null }, S(R.string.studio_bulk_new_prefix_hint))

        SettingsSectionHeader(S(R.string.studio_bulk_new_count))
        WizardField(count, { count = it; error = null }, "10")

        SettingsSectionHeader(S(R.string.studio_bulk_new_start))
        WizardField(startAt, { startAt = it; error = null }, "1")

        // What the run will actually make, before it makes it. The first, the last, and how many —
        // which is what catches a prefix with a typo in it and a count with an extra zero.
        if (names.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FactsCard(
                S(R.string.studio_bulk_new_preview)
                    .replace("%1\$s", names.first())
                    .replace("%2\$s", names.last())
                    .replace("%3\$s", faNum(names.size))
            )
        }
        if (clashes.isNotEmpty()) {
            // Answered from the local index, so it costs nothing and appears while typing. The
            // target account's UNIQUE(username) is still the authority — this only means the
            // operator finds out now rather than nineteen creations in.
            Spacer(Modifier.height(10.dp))
            ProblemCard(
                S(R.string.studio_bulk_new_taken)
                    .replace("%1\$s", faNum(clashes.size))
                    .replace("%2\$s", clashes.take(5).joinToString("، "))
            )
        }

        if (fleet.size > 1) {
            SettingsSectionHeader(S(R.string.studio_placement))
            SettingsGroup {
                if (!pickingShard) {
                    SettingsRow(
                        title = placement?.let { it.name.ifEmpty { it.email } }
                            ?: S(R.string.studio_placement_none),
                        subtitle = S(R.string.studio_bulk_new_placement_note),
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
                            subtitle = i?.users?.takeIf { it >= 0 }?.let { faNum(it) },
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
                            volumeToField(plan.quotaBytes).let { (v, u) -> volumeGb = v; volumeUnit = u }
                            days = plan.durationDays?.toString().orEmpty()
                            devices = plan.deviceLimit?.toString().orEmpty()
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
        VolumeField(volumeGb, { volumeGb = it }, volumeUnit, { volumeUnit = it }, S(R.string.studio_field_volume_hint))

        SettingsSectionHeader(S(R.string.studio_field_days))
        WizardField(days, { days = it }, S(R.string.studio_field_days_hint))

        SettingsSectionHeader(S(R.string.studio_new_devices_title))
        WizardField(devices, { devices = it }, S(R.string.studio_field_devices_hint))
        if (inst?.can("enforcement.strict") == true) {
            com.mlmvpn.scanner.ui.settings.SettingsFooter(S(R.string.studio_new_devices_note))
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

        if (countries.isNotEmpty()) {
            SettingsSectionHeader(S(R.string.studio_locations))
            LocationPicker(
                countries = countries,
                picked = pickedCountries,
                onToggle = { cc ->
                    pickedCountries = if (cc in pickedCountries) pickedCountries - cc else pickedCountries + cc
                    if (cc in pickedCountries) choices.verify(cc)
                },
                includeDirect = includeDirect,
                onIncludeDirect = { includeDirect = it },
                status = choices.status,
            )
        }

        Spacer(Modifier.height(14.dp))
        LinkCountLine(protocols.size, pickedCountries.size, includeDirect || pickedCountries.isEmpty(), 1)
        InfoCard(S(R.string.studio_bulk_new_note))

        Spacer(Modifier.height(14.dp))
        error?.let { ProblemCard(it); Spacer(Modifier.height(12.dp)) }

        if (busy) {
            WizardBusy(
                S(R.string.studio_bulk_run_progress)
                    .replace("%1\$s", faNum(progress.first))
                    .replace("%2\$s", faNum(progress.second))
            )
        } else {
            WizardPrimary(
                S(R.string.studio_create),
                enabled = names.isNotEmpty() && clashes.isEmpty() && placement != null,
            ) { create() }
        }
        Spacer(Modifier.height(28.dp))
    }
}

/**
 * The most people one run will create.
 *
 * Not a technical ceiling — it is a guard against a slipped digit. Five hundred users is five
 * hundred × three requests against one account's hundred-thousand-a-day budget, which is fine; five
 * thousand from a mistyped count is most of a day's budget spent before anyone notices.
 */
private const val MAX_BATCH = 500

/** Name and link per line, tab-separated, so it pastes into a spreadsheet as two columns. */
private fun asText(links: List<BulkLink>, context: android.content.Context): String =
    links.joinToString("\n") {
        it.username + "\t" + (it.url ?: context.getString(R.string.studio_bulk_no_link))
    }
