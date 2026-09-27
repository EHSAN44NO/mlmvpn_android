package com.mlmvpn.scanner.ui.configstudio

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.studio.StudioState
import com.mlmvpn.scanner.data.studio.StudioStore
import com.mlmvpn.scanner.data.studio.domain.Plan
import com.mlmvpn.scanner.ui.configstudio.design.EmptyState
import com.mlmvpn.scanner.ui.configstudio.design.StudioIcons
import com.mlmvpn.scanner.ui.configstudio.parts.InfoCard
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
 * «بسته‌ها» — the sets of terms this operator hands out.
 *
 * The list itself is small and uninteresting. What earns the screen is the line under each row that
 * says **which accounts actually carry this plan**.
 *
 * A plan lives on every installation in the fleet, written by the app. When one account is
 * unreachable at the moment an edit goes out, it keeps the old terms — and nothing about that is
 * visible anywhere else. The next user created on that account silently gets terms the operator
 * did not choose, and the first sign of it is somebody running out of volume early. So a plan that
 * is not everywhere says so, in place, with the action that fixes it (plan §D, R14).
 *
 * There is no price on this screen, and no column behind it that could grow one (D6).
 */
@Composable
fun StudioPlansScreen(
    store: StudioStore,
    state: StudioState,
    onNewPlan: () -> Unit,
    onEditPlan: (Plan) -> Unit,
    onOpenTemplates: () -> Unit,
    onBack: () -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        store.refreshPlans()
        loading = false
    }

    // The accounts that answered, not the accounts that exist. Drift measured against a fleet that
    // includes one which never replied is drift the operator cannot act on.
    val answered = state.plansAnsweredBy

    IosScreen(
        title = S(R.string.studio_plans),
        onBack = if (busy) null else onBack,
        backLabel = S(R.string.studio_tab_more),
        // Pull down to re-read every account's plans -- the button that used to do it is gone.
        onRefresh = { store.refreshPlans() },
        trailing = {
            Text(
                S(R.string.studio_plan_new),
                color = Ios.Blue,
                fontSize = 16.sp,
                modifier = Modifier
                    .clickable(enabled = !busy, onClick = onNewPlan)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            )
        },
    ) {
        Spacer(Modifier.height(10.dp))
        note?.let { InfoCard(it); Spacer(Modifier.height(12.dp)) }

        // An account that did not answer has plans we did not see. Saying "this plan is missing from
        // account X" when X simply did not reply would send the operator to fix nothing.
        if (state.plansUnreachable.isNotEmpty()) {
            ProblemCard(
                S(R.string.studio_plans_unreachable).replace("%1\$s", state.plansUnreachable.joinToString("، "))
            )
            Spacer(Modifier.height(12.dp))
        }

        when {
            loading -> WizardBusy(S(R.string.studio_loading))

            state.plans.isEmpty() -> EmptyState(
                icon = StudioIcons.Plan,
                title = S(R.string.studio_plans_empty_title),
                body = S(R.string.studio_plans_empty),
                actionLabel = S(R.string.studio_plan_new),
                onAction = onNewPlan,
            )

            else -> {
                SettingsSectionHeader(S(R.string.studio_plans))
                SettingsGroup {
                    state.plans.forEachIndexed { index, plan ->
                        if (index > 0) Separator()
                        SettingsRow(
                            title = plan.name.ifBlank { plan.id },
                            value = termsOf(plan),
                            // The default is said in words, not in a coloured title.
                            subtitle = listOfNotNull(
                                if (plan.isDefault) S(R.string.studio_plan_is_default) else null,
                                driftLine(plan, answered, state.fleetLabels),
                            ).joinToString(" · ").ifBlank { null },
                            icon = StudioIcons.Plan,
                            tint = if (plan.isDefault) Ios.Blue else Ios.Gray,
                            onClick = { onEditPlan(plan) },
                        )
                    }
                }
                SettingsFooter(S(R.string.studio_plans_edit_note))
            }
        }

        // The other half of the pair, one tap away. A plan says what somebody is allowed and a
        // «قالب» says what their link looks like, and a plan can name one — so an operator who has
        // just set the terms is one step from setting the shape that goes with them.
        SettingsSectionHeader(S(R.string.studio_templates))
        SettingsGroup {
            SettingsRow(
                title = S(R.string.studio_templates),
                icon = StudioIcons.Template,
                tint = Ios.Purple,
                value = if (state.templates.isEmpty()) null else faNum(state.templates.size),
                onClick = onOpenTemplates,
            )
        }

        Spacer(Modifier.height(28.dp))
    }
}

/** «۳۰ گیگ · ۳۰ روز» — the terms, in the app's own digits. */
@Composable
internal fun termsOf(plan: Plan): String {
    val parts = mutableListOf<String>()
    parts.add(
        if (plan.isUnlimitedVolume) S(R.string.studio_unlimited)
        else bytesFa(plan.quotaBytes ?: 0)
    )
    parts.add(
        if (plan.isOpenEnded) S(R.string.studio_no_expiry)
        else faNum(plan.durationDays ?: 0) + " " + S(R.string.studio_unit_days)
    )
    return parts.joinToString(" · ")
}

/**
 * The one line that makes this screen worth having.
 *
 * Null when the plan is on every account that answered, because a badge that is always there stops
 * being read. Named accounts rather than a count: "missing from 1 account" sends the operator
 * looking, while "missing from ali@…" tells them where to go.
 *
 * [answered] is the set of installations whose plan list was actually read, and using it rather than
 * the whole fleet is the difference between a true statement and a guess. An account that did not
 * reply has plans nobody has seen; it is reported once, above, as unreachable — naming it here as
 * well would say "this plan is missing from X" about an account that may hold it perfectly well.
 */
@Composable
private fun driftLine(plan: Plan, answered: Set<String>, labels: Map<String, String>): String? {
    if (answered.size <= 1) return null
    val missing = answered.filter { it !in plan.presentOn }.mapNotNull { labels[it] }
    if (missing.isEmpty()) return null
    return S(R.string.studio_plan_missing_on).replace("%1\$s", missing.joinToString("، "))
}
