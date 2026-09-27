package com.mlmvpn.scanner.ui.configstudio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.studio.StudioState
import com.mlmvpn.scanner.data.studio.StudioStore
import com.mlmvpn.scanner.data.studio.domain.ConfigTemplate
import com.mlmvpn.scanner.ui.configstudio.design.EmptyState
import com.mlmvpn.scanner.ui.configstudio.design.StudioIcons
import com.mlmvpn.scanner.ui.configstudio.parts.InfoCard
import com.mlmvpn.scanner.ui.configstudio.parts.PageIntro
import com.mlmvpn.scanner.ui.configstudio.parts.ProblemCard
import com.mlmvpn.scanner.ui.configstudio.parts.WizardBusy
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.utils.S

/**
 * «قالب‌ها» — the config shapes this fleet carries.
 *
 * The companion to «بسته‌ها» and laid out the same way on purpose: a plan is what somebody is
 * allowed, a template is what their link looks like, and an operator who has learned one screen has
 * learned both.
 *
 * The drift line is the same idea too and matters for the same reason. A template missing from one
 * account is a config built there that quietly gets a different shape — invisible unless something
 * says so, and one tap to fix when it does (plan R14).
 */
@Composable
fun StudioTemplatesScreen(
    store: StudioStore,
    state: StudioState,
    onNewTemplate: () -> Unit,
    onEditTemplate: (ConfigTemplate) -> Unit,
    onBack: () -> Unit,
) {
    var loading by remember { mutableStateOf(state.templates.isEmpty()) }

    LaunchedEffect(Unit) {
        store.refreshTemplates()
        loading = false
    }

    // An engine that has no templates endpoint at all. Named separately from "could not be read",
    // because they need different actions: one is an update, the other is a network.
    val stale = remember(state.installations) {
        state.installations.filter { !it.can("templates.v1") && it.engineBuild > 0 }
    }

    IosScreen(
        title = S(R.string.studio_templates),
        onBack = onBack,
        // Reached from «بیشتر» and from «بسته‌ها», so the chevron alone rather than a wrong name.
        backLabel = null,
        onRefresh = { store.refreshTemplates() },
        trailing = {
            Text(
                S(R.string.studio_template_new),
                color = Ios.Blue,
                fontSize = 16.sp,
                modifier = Modifier.clickable(onClick = onNewTemplate)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            )
        },
    ) {
        Spacer(Modifier.height(10.dp))
        PageIntro(S(R.string.studio_templates_sub))

        if (stale.isNotEmpty()) {
            ProblemCard(
                S(R.string.studio_template_engine_old)
                    .replace("%1\$s", stale.joinToString("، ") { it.accountLabel })
            )
            Spacer(Modifier.height(10.dp))
        }
        if (state.templatesUnreachable.isNotEmpty()) {
            ProblemCard(
                S(R.string.studio_account_unreachable)
                    .replace("%1\$s", state.templatesUnreachable.joinToString("، "))
            )
            Spacer(Modifier.height(10.dp))
        }

        if (loading) {
            WizardBusy(S(R.string.studio_loading))
            return@IosScreen
        }

        if (state.templates.isEmpty()) {
            EmptyState(
                icon = StudioIcons.Template,
                title = S(R.string.studio_templates_empty_title),
                body = S(R.string.studio_templates_none),
                actionLabel = S(R.string.studio_template_new),
                onAction = onNewTemplate,
            )
            Spacer(Modifier.height(28.dp))
            return@IosScreen
        }

        SettingsSectionHeader(S(R.string.studio_templates))
        SettingsGroup {
            state.templates.forEachIndexed { index, template ->
                if (index > 0) Separator()
                SettingsRow(
                    title = template.name.ifBlank { template.id },
                    value = shapeOf(template),
                    subtitle = listOfNotNull(
                        if (template.isDefault) S(R.string.studio_template_is_default) else null,
                        driftLine(template, state),
                    ).joinToString(" · ").ifBlank { null },
                    icon = StudioIcons.Template,
                    tint = if (template.isDefault) Ios.Blue else Ios.Gray,
                    onClick = { onEditTemplate(template) },
                )
            }
        }
        SettingsFooter(S(R.string.studio_templates_note))
        Spacer(Modifier.height(28.dp))
    }
}

/**
 * What this template actually produces, in one short line.
 *
 * The protocol and transport, because those are the two choices that decide whether a link connects
 * at all. Everything else is a refinement and belongs on the page that edits it.
 */
@Composable
internal fun shapeOf(template: ConfigTemplate): String {
    val protocol = when (template.protocol) {
        "t" -> "Trojan"
        "v" -> "VLESS"
        else -> "—"
    }
    val transport = when (template.transportType) {
        "xhttp" -> "XHTTP"
        "ws" -> "WebSocket"
        else -> template.transportType.orEmpty()
    }
    return if (transport.isEmpty()) protocol else "$protocol · $transport"
}

/**
 * Which accounts are missing this template, or null when none are.
 *
 * Measured against the installations that actually **answered**, never against the whole fleet: an
 * account that did not reply has templates nobody has seen, and reporting those as missing names an
 * account that may hold every one of them perfectly well.
 */
@Composable
private fun driftLine(template: ConfigTemplate, state: StudioState): String? {
    val missing = state.templatesAnsweredBy - template.presentOn
    if (missing.isEmpty()) return null
    return S(R.string.studio_template_drift)
        .replace("%1\$s", missing.joinToString("، ") { state.fleetLabels[it] ?: it })
}
