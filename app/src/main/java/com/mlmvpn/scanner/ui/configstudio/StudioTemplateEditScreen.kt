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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.studio.StudioState
import com.mlmvpn.scanner.data.studio.StudioStore
import com.mlmvpn.scanner.data.studio.config.ConfigAlpn
import com.mlmvpn.scanner.data.studio.config.ConfigDraft
import com.mlmvpn.scanner.data.studio.config.ConfigFingerprint
import com.mlmvpn.scanner.data.studio.config.ConfigShape
import com.mlmvpn.scanner.data.studio.config.LocationConfigs
import com.mlmvpn.scanner.data.studio.config.TemplatePreset
import com.mlmvpn.scanner.data.studio.config.toDraft
import com.mlmvpn.scanner.data.studio.config.toTemplate
import com.mlmvpn.scanner.data.studio.domain.ConfigTemplate
import com.mlmvpn.scanner.ui.configstudio.design.ChoiceRow
import com.mlmvpn.scanner.ui.configstudio.design.StudioIcons
import com.mlmvpn.scanner.ui.configstudio.design.StudioSeparator
import com.mlmvpn.scanner.ui.configstudio.parts.FactsCard
import com.mlmvpn.scanner.ui.configstudio.parts.InfoCard
import com.mlmvpn.scanner.ui.configstudio.parts.PageIntro
import com.mlmvpn.scanner.ui.configstudio.parts.ProblemCard
import com.mlmvpn.scanner.ui.configstudio.parts.WizardField
import com.mlmvpn.scanner.ui.configstudio.parts.WizardPrimary
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosAlert
import com.mlmvpn.scanner.ui.settings.IosAlertAction
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.settings.SettingsToggle
import com.mlmvpn.scanner.utils.S
import kotlinx.coroutines.launch

/**
 * One «قالب» — the shape, and which endpoints it is served on.
 *
 * The same controls the builder offers, minus the two that cannot be stored: the credential and the
 * path. Both are per config. A template holding a credential would hand the same uuid to everybody
 * created from it, which is one subscriber's link working for all of them.
 *
 * **Editing this changes nothing for anybody already holding a link.** A config copies the shape
 * when it is made and carries its own URI template afterwards — the same rule «بسته» follows, and
 * the reason there is no «اعمال» button here: re-shaping an existing config means re-issuing its
 * credential, which cuts off the link somebody is using and is a per-person action on purpose.
 */
@Composable
fun StudioTemplateEditScreen(
    store: StudioStore,
    state: StudioState,
    existing: ConfigTemplate?,
    /** Non-null when this page opened as "save what I just built". Pre-fills the shape. */
    fromDraft: ConfigDraft?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val id = remember(existing) { existing?.id ?: StudioStore.newTemplateId() }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var draft by remember {
        mutableStateOf(existing?.toDraft() ?: fromDraft ?: ConfigDraft())
    }
    var isDefault by remember { mutableStateOf(existing?.isDefault ?: false) }
    var nodeIds by remember { mutableStateOf(existing?.nodeIds.orEmpty().toSet()) }

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    // The endpoints to choose from. Read once on entry rather than held, because the node list is
    // edited on a different screen and a stale copy here would offer an address that is gone.
    LaunchedEffect(Unit) { if (state.nodes.isEmpty()) store.refreshNodes() }

    fun save() {
        val label = name.trim()
        if (label.isEmpty()) { error = context.getString(R.string.studio_template_needs_name); return }
        busy = true; error = null
        scope.launch {
            val res = store.saveTemplate(
                draft.toTemplate(id = id, name = label, nodeIds = nodeIds.toList(), isDefault = isDefault)
            )
            busy = false
            if (res.failed.isEmpty()) onSaved()
            else error = context.getString(R.string.studio_account_update_failed)
                .replace("%1\$s", res.failed.joinToString("، ") { it.first })
        }
    }

    // Resolved here: the clone's name is built inside a click handler, which is not a composition.
    val copyName = S(R.string.studio_template_copy_name)

    IosScreen(
        title = if (existing == null) S(R.string.studio_template_new) else S(R.string.studio_template_edit),
        onBack = if (busy) null else onBack,
        backLabel = S(R.string.studio_templates),
    ) {
        Spacer(Modifier.height(10.dp))
        PageIntro(S(R.string.studio_template_edit_sub))

        error?.let { ProblemCard(it); Spacer(Modifier.height(12.dp)) }

        SettingsSectionHeader(S(R.string.studio_template_name))
        WizardField(name, { name = it; error = null }, S(R.string.studio_template_name_hint), ltr = false)

        // ---- start from one of the four -------------------------------------------------
        //
        // Only on a new template, and only when nothing has been chosen yet: a preset row on a
        // template being edited is a button that silently discards what is on screen.
        if (existing == null && fromDraft == null) {
            SettingsSectionHeader(S(R.string.studio_template_presets))
            SettingsGroup {
                TemplatePreset.entries.filter { LocationConfigs.isOffered(it.shape) }.forEachIndexed { index, preset ->
                    if (index > 0) Separator()
                    // Resolved out here rather than inside onClick: `presetName` is @Composable and
                    // a click handler is not a composition, so reading the string in there does not
                    // compile.
                    val label = presetName(preset)
                    SettingsRow(
                        title = label,
                        subtitle = presetNote(preset),
                        value = preset.shape.label,
                        icon = StudioIcons.Template,
                        tint = Ios.Indigo,
                        showChevron = false,
                        onClick = {
                            draft = preset.toDraft()
                            // Only when the operator has not named it themselves — a preset that
                            // overwrote a typed name would be a tap that loses work.
                            if (name.isBlank()) name = label
                        },
                    )
                }
            }
            InfoCard(S(R.string.studio_template_presets_note))
        }

        // ---- the shape ------------------------------------------------------------------
        SettingsSectionHeader(S(R.string.studio_builder_shape))
        SettingsGroup {
            // The offered shapes, plus this template's own when it is one that is not offered any more
            // (XHTTP), so what it is stays visible and can be changed.
            (LocationConfigs.OFFERED + listOf(draft.shape)).distinct().forEachIndexed { index, shape ->
                if (index > 0) StudioSeparator()
                ChoiceRow(
                    title = shape.label,
                    // The cost is on the label rather than discovered: XHTTP bills one Worker
                    // request per upload chunk where WebSocket bills one for the whole connection.
                    subtitle = if (shape.isRequestHungry) S(R.string.studio_builder_xhttp_cost) else null,
                    selected = draft.shape == shape,
                    onClick = { draft = draft.copy(shape = shape) },
                )
            }
        }
        InfoCard(S(R.string.studio_builder_shape_note))

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

        // Offered only where it is a choice. Xray's WebSocket transport speaks HTTP/1.1 and nothing
        // else, so a picker on a WS template would be a control whose value is silently discarded.
        if (draft.shape.alpnIsAChoice) {
            SettingsSectionHeader(S(R.string.studio_builder_alpn))
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
        } else {
            InfoCard(S(R.string.studio_builder_alpn_ws))
        }

        // ---- which endpoints ------------------------------------------------------------
        //
        // The one control here that is not in the builder, and the reason the "gaming" preset is
        // not a lie: nothing in a URI makes a connection faster, but which endpoint it points at
        // decides the number a player feels.
        SettingsSectionHeader(S(R.string.studio_template_nodes))
        if (state.nodes.isEmpty()) {
            InfoCard(S(R.string.studio_template_nodes_none))
        } else {
            SettingsGroup {
                ChoiceRow(
                    title = S(R.string.studio_template_nodes_all),
                    selected = nodeIds.isEmpty(),
                    onClick = { nodeIds = emptySet() },
                )
                state.nodes.forEach { node ->
                    StudioSeparator()
                    ChoiceRow(
                        title = node.name.ifBlank { node.host },
                        subtitle = node.latencyMs?.let {
                            S(R.string.studio_node_latency).replace("%1\$s", faNum(it))
                        },
                        selected = node.id in nodeIds,
                        onClick = {
                            nodeIds = if (node.id in nodeIds) nodeIds - node.id else nodeIds + node.id
                        },
                    )
                }
            }
            InfoCard(S(R.string.studio_template_nodes_note))
        }

        // ---- the default ----------------------------------------------------------------
        SettingsSectionHeader(S(R.string.studio_template_default))
        SettingsGroup {
            SettingsToggle(
                title = S(R.string.studio_template_default),
                subtitle = S(R.string.studio_template_default_sub),
                checked = isDefault,
                onCheckedChange = { isDefault = it },
            )
        }

        // ---- what it will produce -------------------------------------------------------
        //
        // Shown before it is saved, because a template is a thing whose effect is invisible until
        // somebody builds a config from it — and by then it is on somebody's phone.
        FactsCard(
            S(R.string.studio_template_summary)
                .replace("%1\$s", draft.shape.label)
                .replace("%2\$s", draft.fingerprint.wire)
                .replace(
                    "%3\$s",
                    if (nodeIds.isEmpty()) S(R.string.studio_template_nodes_all)
                    else faNum(nodeIds.size)
                )
        )

        Spacer(Modifier.height(14.dp))
        WizardPrimary(S(R.string.studio_save), busy = busy, enabled = name.isNotBlank()) { save() }

        if (existing != null) {
            // Copying is not a dangerous thing to do, so it is not filed under the danger heading.
            Spacer(Modifier.height(18.dp))
            SettingsGroup {
                SettingsActionRow(
                    label = S(R.string.studio_template_clone),
                    icon = StudioIcons.Copy,
                    tint = Ios.Blue,
                    enabled = !busy,
                ) {
                    // A clone is a save under a NEW id, and the screen stays where it is with the
                    // name cleared: the operator is about to change something, or they would not
                    // have cloned it.
                    busy = true; error = null
                    val copy = draft.toTemplate(
                        id = StudioStore.newTemplateId(),
                        name = copyName.replace("%1\$s", name.trim()),
                        nodeIds = nodeIds.toList(),
                        isDefault = false,
                    )
                    scope.launch {
                        val res = store.saveTemplate(copy)
                        busy = false
                        if (res.failed.isEmpty()) onSaved()
                        else error = context.getString(R.string.studio_account_update_failed)
                            .replace("%1\$s", res.failed.joinToString("، ") { it.first })
                    }
                }
            }

            SettingsSectionHeader(S(R.string.studio_section_danger))
            SettingsGroup {
                SettingsActionRow(
                    label = S(R.string.studio_delete),
                    icon = StudioIcons.Delete,
                    tint = Ios.Red,
                    enabled = !busy,
                ) { confirmDelete = true }
            }
            InfoCard(S(R.string.studio_template_delete_note))
        }

        Spacer(Modifier.height(28.dp))
    }

    if (confirmDelete && existing != null) {
        IosAlert(
            title = S(R.string.studio_template_delete_title),
            message = S(R.string.studio_template_delete_body),
            actions = listOf(
                IosAlertAction(S(R.string.studio_cancel), { confirmDelete = false }),
                IosAlertAction(S(R.string.studio_delete), {
                    confirmDelete = false
                    busy = true; error = null
                    scope.launch {
                        val res = store.deleteTemplate(existing.id)
                        busy = false
                        if (res.failed.isEmpty()) onSaved()
                        else error = context.getString(R.string.studio_account_update_failed)
                            .replace("%1\$s", res.failed.joinToString("، ") { it.first })
                    }
                }, destructive = true),
            ),
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
internal fun presetName(preset: TemplatePreset): String = S(
    when (preset) {
        TemplatePreset.STABILITY -> R.string.studio_preset_stability
        TemplatePreset.MOBILE -> R.string.studio_preset_mobile
        TemplatePreset.GAMING -> R.string.studio_preset_gaming
        TemplatePreset.CENSORSHIP -> R.string.studio_preset_censorship
    }
)

@Composable
private fun presetNote(preset: TemplatePreset): String = S(
    when (preset) {
        TemplatePreset.STABILITY -> R.string.studio_preset_stability_sub
        TemplatePreset.MOBILE -> R.string.studio_preset_mobile_sub
        TemplatePreset.GAMING -> R.string.studio_preset_gaming_sub
        TemplatePreset.CENSORSHIP -> R.string.studio_preset_censorship_sub
    }
)
