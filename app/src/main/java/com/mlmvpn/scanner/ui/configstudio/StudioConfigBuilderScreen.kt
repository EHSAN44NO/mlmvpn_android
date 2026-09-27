package com.mlmvpn.scanner.ui.configstudio

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
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
import com.mlmvpn.scanner.data.studio.StudioAdmin
import com.mlmvpn.scanner.data.studio.StudioTransportProbe
import com.mlmvpn.scanner.data.studio.api.StudioHttpApi
import com.mlmvpn.scanner.data.studio.api.StudioResult
import com.mlmvpn.scanner.data.studio.config.ConfigAlpn
import com.mlmvpn.scanner.data.studio.config.ConfigDraft
import com.mlmvpn.scanner.data.studio.config.ConfigFingerprint
import com.mlmvpn.scanner.data.studio.config.ConfigShape
import com.mlmvpn.scanner.data.studio.config.LocationConfigs
import com.mlmvpn.scanner.data.studio.config.StudioCredentials
import com.mlmvpn.scanner.data.studio.config.StudioTemplates
import com.mlmvpn.scanner.data.studio.config.toDraft
import com.mlmvpn.scanner.data.studio.domain.TransportType
import com.mlmvpn.scanner.ui.configstudio.design.ChoiceRow
import com.mlmvpn.scanner.ui.configstudio.design.StudioIcons
import com.mlmvpn.scanner.ui.configstudio.design.StudioSeparator
import com.mlmvpn.scanner.ui.configstudio.parts.InfoCard
import com.mlmvpn.scanner.ui.configstudio.parts.PageIntro
import com.mlmvpn.scanner.ui.configstudio.parts.PayloadCard
import com.mlmvpn.scanner.ui.configstudio.parts.ProblemCard
import com.mlmvpn.scanner.ui.configstudio.parts.WizardBusy
import com.mlmvpn.scanner.ui.configstudio.parts.WizardField
import com.mlmvpn.scanner.ui.configstudio.parts.WizardPrimary
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.settings.SettingsToggle
import com.mlmvpn.scanner.utils.S
import kotlinx.coroutines.launch

/**
 * Every choice that goes into one config, on one page, with the summary of what will be made.
 *
 * It replaces a dialog that offered two fixed shapes and nothing else. The organising decision is
 * that **this screen offers only what the engine can serve**: three shapes, one security type, and
 * the two handshake details that actually travel in the link. A control for a thing the worker
 * cannot answer would produce a config that imports into any client and never connects — which is
 * indistinguishable, from the subscriber's side, from the operator having done nothing.
 *
 * Address, port and server name are deliberately absent. They belong to an ENDPOINT: the engine
 * renders one link per config per endpoint per port, so a host typed here would be overwritten by
 * every node in the list. The screen says where they live instead of offering three dead fields.
 */
@Composable
fun StudioConfigBuilderScreen(
    store: StudioStore,
    state: com.mlmvpn.scanner.data.studio.StudioState,
    installationId: String,
    userId: String,
    username: String,
    /** The config this one is cloned from, when the operator asked for a copy of an existing one. */
    cloneOf: ConfigDraft? = null,
    /** Hands what is on screen to the template editor, so a shape worth repeating can be kept. */
    onSaveAsTemplate: (ConfigDraft) -> Unit = {},
    onBack: () -> Unit,
    onCreated: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // A copy of a shape that is no longer offered (an XHTTP config) starts as the default instead.
    var draft by remember {
        mutableStateOf((cloneOf ?: ConfigDraft()).let { if (LocationConfigs.isOffered(it.shape)) it else it.copy(shape = ConfigShape.VLESS_WS) })
    }

    /**
     * Which endpoints the config will be served on, from the template it was started from.
     *
     * Not editable here, and that is deliberate: the endpoint list belongs to a «قالب», where it
     * is chosen once for every config made from it. Offering it again per config would be a fourth
     * place the same decision lives.
     */
    var nodeIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var startedFrom by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reviewing by remember { mutableStateOf(false) }
    var probe by remember { mutableStateOf<StudioTransportProbe?>(null) }
    val admin = remember { StudioAdmin(context) }

    val account = remember(installationId) {
        store.installedAccounts().firstOrNull { it.id == installationId }
    }
    val user = remember(installationId, userId) { store.cached(installationId, userId) }

    // The first config a person holds answers on the root, which is what a client configured with
    // no path expects. Every one after it takes a path of its own so it can be revoked alone.
    var configCount by remember { mutableStateOf<Int?>(null) }
    androidx.compose.runtime.LaunchedEffect(installationId, userId) {
        val acc = account ?: return@LaunchedEffect
        val res = StudioHttpApi(context, acc).listConfigs(userId)
        if (res is StudioResult.Ok) {
            configCount = res.value.size
            if (res.value.isEmpty() && cloneOf == null) draft = draft.copy(useRootPath = true)
        }
    }

    fun create() {
        val acc = account ?: return
        busy = true; error = null
        scope.launch {
            val credential = StudioCredentials.credentialFor(draft, user?.credential)
            val res = StudioHttpApi(context, acc).createConfig(
                userId = userId,
                uriTemplate = StudioTemplates.templateFor(draft),
                label = draft.label.trim().ifEmpty { draft.shape.label },
                protocol = draft.shape.protocol.wire.take(1),
                transportType = draft.shape.transport.wire,
                credential = credential,
                routeKey = StudioCredentials.routeKeyFor(draft),
                authHash = StudioCredentials.authHash(draft.shape.protocol, credential),
                // Empty unless this was started from a template that named endpoints. An engine
                // without `configs.nodes` accepts the body and drops the field, which is why the
                // row above says whether the choice will actually be kept.
                nodeIds = nodeIds,
            )
            busy = false
            when (res) {
                is StudioResult.Ok -> onCreated()
                is StudioResult.Err -> error = messageFor(context, res.error)
            }
        }
    }

    IosScreen(
        title = S(R.string.studio_config_add),
        onBack = if (busy) null else onBack,
        backLabel = username.ifBlank { S(R.string.studio_section_configs) },
    ) {
        Spacer(Modifier.height(10.dp))

        if (account == null) {
            InfoCard(S(R.string.studio_engine_unreachable))
            return@IosScreen
        }

        if (reviewing) {
            ReviewStep(
                draft = draft,
                username = username,
                busy = busy,
                error = error,
                probe = probe,
                onTest = {
                    busy = true; error = null
                    scope.launch {
                        // The first enabled endpoint, or the worker's own address when there is
                        // none — which is exactly the order the engine itself renders links in.
                        val node = store.state.value.nodes
                            .firstOrNull { it.enabled && it.installationId == installationId }
                        val host = node?.host
                            ?: account.mlmWorkerUrl.orEmpty()
                                .removePrefix("https://").removePrefix("http://").trimEnd('/')
                        probe = admin.probeTransport(
                            account = account,
                            host = host,
                            port = node?.ports?.firstOrNull() ?: 443,
                            serverName = node?.sni,
                            websocket = draft.shape.transport == TransportType.WS,
                        )
                        busy = false
                    }
                },
                onEdit = { reviewing = false },
                onConfirm = ::create,
            )
            return@IosScreen
        }

        PageIntro(S(R.string.studio_builder_sub))

        // ---- start from a stored shape -------------------------------------------------
        //
        // Above the shape picker, because it fills the shape picker in. Only when there is
        // something to start from: a section offering an empty list is a section that teaches the
        // operator this screen has a part that never works.
        if (state.templates.isNotEmpty() && cloneOf == null) {
            SettingsSectionHeader(S(R.string.studio_builder_from_template))
            SettingsGroup {
                state.templates.filter { LocationConfigs.isOffered(it.toDraft().shape) }.forEachIndexed { index, template ->
                    if (index > 0) StudioSeparator()
                    ChoiceRow(
                        title = template.name.ifBlank { template.id },
                        subtitle = shapeOf(template),
                        selected = startedFrom == template.id,
                        onClick = {
                            // The path decision is NOT taken from the template: whether this config
                            // answers on the root depends on whether this person already has one,
                            // which the screen worked out above and the template cannot know.
                            val fromTemplate = template.toDraft()
                            draft = fromTemplate.copy(
                                useRootPath = draft.useRootPath,
                                customPath = draft.customPath,
                                customCredential = draft.customCredential,
                            )
                            nodeIds = template.nodeIds
                            startedFrom = template.id
                        },
                    )
                }
            }
            if (nodeIds.isNotEmpty()) {
                InfoCard(
                    S(R.string.studio_builder_template_nodes).replace("%1\$s", faNum(nodeIds.size))
                )
            } else {
                InfoCard(S(R.string.studio_builder_from_template_note))
            }
        }

        // ---- what it is ---------------------------------------------------------------
        SettingsSectionHeader(S(R.string.studio_builder_shape))
        SettingsGroup {
            LocationConfigs.OFFERED.forEachIndexed { index, shape ->
                if (index > 0) StudioSeparator()
                ChoiceRow(
                    title = shape.label,
                    // The cost is on the row that carries it, not in a footnote under the card. An
                    // operator picking XHTTP is usually picking it because WebSocket is blocked,
                    // and they should read what it spends while they are choosing.
                    subtitle = if (shape.isRequestHungry) S(R.string.studio_builder_xhttp_cost) else null,
                    selected = draft.shape == shape,
                    onClick = { draft = draft.copy(shape = shape) },
                )
            }
        }
        InfoCard(S(R.string.studio_builder_shape_note))

        // ---- how the handshake looks --------------------------------------------------
        SettingsSectionHeader(S(R.string.studio_builder_security))
        SettingsGroup {
            SettingsRow(
                title = S(R.string.studio_builder_tls),
                subtitle = S(R.string.studio_builder_tls_sub),
                showChevron = false,
            )
        }

        SettingsSectionHeader(S(R.string.studio_builder_fingerprint))
        SettingsGroup {
            ConfigFingerprint.entries.forEachIndexed { index, fp ->
                if (index > 0) StudioSeparator()
                ChoiceRow(
                    title = fp.wire,
                    subtitle = if (fp == ConfigFingerprint.RANDOM) S(R.string.studio_builder_fp_random) else null,
                    selected = draft.fingerprint == fp,
                    onClick = { draft = draft.copy(fingerprint = fp) },
                )
            }
        }
        InfoCard(S(R.string.studio_builder_fp_note))

        SettingsSectionHeader(S(R.string.studio_builder_alpn))
        if (!draft.shape.alpnIsAChoice) {
            // Stated as a fact rather than offered as a control. See ConfigShape.alpnIsAChoice: a
            // WebSocket link that offers h2 is dead at dial time, so the builder strips it — and a
            // picker whose value is silently discarded is worse than no picker.
            SettingsGroup {
                SettingsRow(
                    title = S(R.string.studio_builder_alpn_none),
                    subtitle = S(R.string.studio_builder_alpn_ws),
                    showChevron = false,
                )
            }
        } else {
            SettingsGroup {
                ConfigAlpn.entries.forEachIndexed { index, alpn ->
                    if (index > 0) StudioSeparator()
                    ChoiceRow(
                        title = alpn.values.joinToString(", "),
                        selected = draft.alpn == alpn,
                        onClick = { draft = draft.copy(alpn = alpn) },
                    )
                }
            }
        }

        // ---- where it answers ---------------------------------------------------------
        SettingsSectionHeader(S(R.string.studio_builder_path))
        if (!draft.shape.supportsRouteKey) {
            InfoCard(S(R.string.studio_builder_path_xhttp), underHeader = true)
        } else {
            SettingsGroup {
                SettingsToggle(
                    title = S(R.string.studio_builder_root_path),
                    checked = draft.useRootPath,
                    subtitle = if (configCount == 0) S(R.string.studio_builder_root_first)
                    else S(R.string.studio_builder_root_sub),
                    onCheckedChange = { draft = draft.copy(useRootPath = it) },
                )
            }
            if (!draft.useRootPath) {
                Spacer(Modifier.height(10.dp))
                WizardField(
                    draft.customPath,
                    { draft = draft.copy(customPath = it) },
                    S(R.string.studio_builder_path_hint),
                    monospace = true,
                )
                draft.pathProblem()?.let {
                    ProblemCard(
                        S(
                            when (it) {
                                ConfigDraft.PathProblem.RESERVED -> R.string.studio_builder_path_reserved
                                else -> R.string.studio_builder_path_bad
                            }
                        )
                    )
                }
            }
        }

        // ---- what it authenticates with -----------------------------------------------
        SettingsSectionHeader(S(R.string.studio_builder_credential))
        if (draft.credentialIsForced) {
            InfoCard(S(R.string.studio_builder_cred_forced), underHeader = true)
        } else {
            WizardField(
                draft.customCredential,
                { draft = draft.copy(customCredential = it) },
                S(R.string.studio_builder_cred_hint),
                monospace = true,
            )
            draft.credentialProblem()?.let {
                ProblemCard(
                    S(
                        when (it) {
                            ConfigDraft.CredentialProblem.NOT_A_UUID -> R.string.studio_builder_cred_uuid
                            ConfigDraft.CredentialProblem.TOO_SHORT -> R.string.studio_builder_cred_short
                            else -> R.string.studio_builder_cred_chars
                        }
                    )
                )
            }
            Spacer(Modifier.height(10.dp))
            SettingsGroup {
                SettingsActionRow(
                    label = S(R.string.studio_builder_cred_generate),
                    icon = StudioIcons.Credential,
                    tint = Ios.Green,
                ) {
                    draft = draft.copy(
                        customCredential = StudioCredentials.newCredential(draft.shape.protocol)
                    )
                }
            }
            InfoCard(S(R.string.studio_builder_cred_note))
        }

        // ---- keep this shape ------------------------------------------------------------
        //
        // The other direction from the row above: a shape arrived at by choosing is worth keeping,
        // and retyping it for the next person is how two configs that were meant to be the same
        // end up differing by a fingerprint nobody remembers setting.
        SettingsSectionHeader(S(R.string.studio_builder_save_template))
        SettingsGroup {
            SettingsActionRow(
                label = S(R.string.studio_builder_save_template),
                icon = StudioIcons.Template,
                tint = Ios.Purple,
                enabled = !busy,
            ) { onSaveAsTemplate(draft) }
        }
        InfoCard(S(R.string.studio_builder_save_template_note))

        // ---- what to call it ----------------------------------------------------------
        SettingsSectionHeader(S(R.string.studio_builder_label))
        WizardField(
            draft.label,
            { draft = draft.copy(label = it) },
            draft.shape.label,
            ltr = false,
        )

        Spacer(Modifier.height(16.dp))
        error?.let { ProblemCard(it) }
        WizardPrimary(S(R.string.studio_builder_review), enabled = draft.isValid) { reviewing = true }
        Spacer(Modifier.height(28.dp))
    }
}

/**
 * What is about to be made, before it is made.
 *
 * A step rather than a paragraph under the button, because the thing being confirmed is a link that
 * gets handed to a person: once it exists somebody may already be holding it, and "undo" is a
 * revoke that has to reach them. The rendered template is shown with its placeholders intact —
 * `{{host}}` and `{{port}}` are filled per endpoint by the engine, and showing an invented address
 * here would be showing a link that will never exist in that form.
 */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ReviewStep(
    draft: ConfigDraft,
    username: String,
    busy: Boolean,
    error: String?,
    probe: StudioTransportProbe?,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onConfirm: () -> Unit,
) {
    PageIntro(S(R.string.studio_builder_review_sub))

    SettingsGroup {
        SettingsRow(title = S(R.string.studio_builder_shape), value = draft.shape.label, showChevron = false)
        Separator()
        SettingsRow(title = S(R.string.studio_builder_for), value = username, showChevron = false)
        Separator()
        SettingsRow(
            title = S(R.string.studio_builder_fingerprint),
            value = draft.fingerprint.wire,
            showChevron = false,
        )
        Separator()
        SettingsRow(
            title = S(R.string.studio_builder_alpn),
            // What the LINK will carry, not what was picked. On WebSocket the builder drops h2, and
            // a summary that repeated the choice would confirm something untrue right before the
            // operator commits to it.
            value = if (draft.shape.alpnIsAChoice) draft.alpn.values.joinToString(", ")
            else S(R.string.studio_builder_alpn_none),
            showChevron = false,
        )
        Separator()
        SettingsRow(
            title = S(R.string.studio_builder_path),
            value = when {
                !draft.shape.supportsRouteKey -> S(R.string.studio_builder_path_session)
                draft.useRootPath -> "/"
                draft.customPath.isNotBlank() -> "/" + draft.customPath.trim().trim('/')
                else -> S(R.string.studio_builder_path_auto)
            },
            showChevron = false,
        )
        Separator()
        SettingsRow(
            title = S(R.string.studio_builder_credential),
            value = when {
                draft.credentialIsForced -> S(R.string.studio_builder_cred_user)
                draft.customCredential.isNotBlank() -> S(R.string.studio_builder_cred_custom)
                else -> S(R.string.studio_builder_cred_auto)
            },
            showChevron = false,
        )
    }

    SettingsSectionHeader(S(R.string.studio_builder_template))
    PayloadCard(StudioTemplates.templateFor(draft))
    InfoCard(S(R.string.studio_builder_template_note))

    // ---- will this survive the network it has to cross ----------------------------
    SettingsSectionHeader(S(R.string.studio_builder_test))
    SettingsGroup {
        probe?.let { p ->
            SettingsRow(
                title = when {
                    p.upgraded -> S(R.string.studio_builder_test_upgraded)
                    p.reachable -> S(R.string.studio_builder_test_open)
                    else -> S(R.string.studio_builder_test_blocked)
                },
                icon = if (p.reachable) StudioIcons.Ok else StudioIcons.Error,
                tint = if (p.reachable) Ios.Green else Ios.Red,
                subtitle = p.error,
                value = p.latencyMs?.let { S(R.string.studio_node_latency).replace("%1\$s", faNum(it)) },
                showChevron = false,
            )
            Separator()
        }
        SettingsActionRow(
            label = if (busy) S(R.string.studio_node_testing) else S(R.string.studio_builder_test_run),
            icon = StudioIcons.Test,
            tint = Ios.Green,
            busy = busy,
            enabled = !busy,
            onClick = onTest,
        )
    }
    InfoCard(S(R.string.studio_builder_test_note))

    Spacer(Modifier.height(16.dp))
    error?.let { ProblemCard(it) }

    if (busy) {
        WizardBusy(S(R.string.studio_creating))
    } else {
        WizardPrimary(S(R.string.studio_create), onClick = onConfirm)
        Spacer(Modifier.height(10.dp))
        SettingsGroup {
            SettingsActionRow(
                label = S(R.string.studio_builder_edit),
                icon = StudioIcons.Edit,
                tint = Ios.Gray,
                onClick = onEdit,
            )
        }
    }
    Spacer(Modifier.height(28.dp))
}
