package com.mlmvpn.scanner.ui.configstudio

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.CloudManager
import com.mlmvpn.scanner.data.studio.StudioCombine
import com.mlmvpn.scanner.data.studio.StudioCombineHandoff
import com.mlmvpn.scanner.data.studio.StudioStore
import com.mlmvpn.scanner.data.studio.api.StudioError
import com.mlmvpn.scanner.data.studio.api.StudioResult
import com.mlmvpn.scanner.ui.configstudio.parts.FactsCard
import com.mlmvpn.scanner.ui.configstudio.parts.InfoCard
import com.mlmvpn.scanner.ui.configstudio.parts.PageIntro
import com.mlmvpn.scanner.ui.configstudio.parts.ProblemCard
import com.mlmvpn.scanner.ui.configstudio.parts.WizardBusy
import com.mlmvpn.scanner.ui.configstudio.parts.WizardPrimary
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.utils.S

/**
 * The one screen between picking a person and the scanner.
 *
 * It exists because the handover has a step that can fail and a sentence that has to be read. The
 * failing step is reading the person's configs — done here, where there is an API key and a
 * network, because the scanner has neither and a combine sheet that made a live Cloudflare call
 * would fail in exactly the conditions the scanner exists for.
 *
 * The sentence is what happens next: the operator is about to leave Config Studio for a different
 * tab, and a screen that simply switched under them would read as the app losing its place. So this
 * one says where they are going, what will be waiting for them there, and what to press when the
 * scan is done.
 *
 * **No scanning happens here and none is offered.** An earlier version of «ترکیب» had a sweep of
 * its own; it worked, and it was a second scanner with a second archive and a second set of
 * measurements to keep honest. The scanner the operator already uses is the one that should do
 * this.
 */
@Composable
fun StudioCombineHandover(
    store: StudioStore,
    installationId: String,
    userId: String,
    username: String,
    onBack: () -> Unit,
    onReady: () -> Unit,
) {
    val context = LocalContext.current
    val account = remember(installationId) {
        CloudManager(context).accounts.firstOrNull { it.id == installationId }
    }

    var target by remember { mutableStateOf<StudioCombineHandoff.Target?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(installationId, userId) {
        val acc = account
        if (acc == null) {
            error = context.getString(R.string.studio_combine_no_account)
            loading = false
            return@LaunchedEffect
        }
        when (val res = StudioCombine.prepare(store, acc, userId, username)) {
            is StudioResult.Ok -> target = res.value
            is StudioResult.Err -> error = context.getString(
                when (res.error.code) {
                    StudioError.NETWORK -> R.string.studio_err_network
                    StudioError.UNAUTHORIZED -> R.string.studio_err_unauthorized
                    StudioError.NOT_FOUND -> R.string.studio_combine_no_configs
                    else -> R.string.studio_err_generic
                }
            )
        }
        loading = false
    }

    IosScreen(
        title = S(R.string.studio_combine),
        onBack = onBack,
        backLabel = username,
    ) {
        Spacer(Modifier.height(10.dp))
        PageIntro(S(R.string.studio_handover_sub).replace("%1\$s", username))

        when {
            loading -> WizardBusy(S(R.string.studio_loading))

            error != null -> ProblemCard(error!!)

            else -> {
                val t = target
                FactsCard(
                    S(R.string.studio_handover_facts)
                        .replace("%1\$s", username)
                        .replace("%2\$s", faNum(t?.nodes?.size ?: 0))
                )
                // The three steps, in the order they will happen, on the screen before the one
                // where the first of them starts. The operator is about to change tabs; being told
                // afterwards what to look for is being told too late.
                InfoCard(S(R.string.studio_handover_steps).replace("%1\$s", username))
                Spacer(Modifier.height(14.dp))
                WizardPrimary(
                    S(R.string.studio_handover_go),
                    enabled = t != null && t.isUsable,
                ) {
                    t?.let {
                        StudioCombineHandoff.arm(context, it)
                        // The assistant travels with them. Arming alone put the operator on a
                        // scanner with the right base config and no idea that the rest of the job
                        // -- combine, measure, write the subscription -- was going to be theirs to
                        // find; this is the same coach the cloud combine uses, and it does those
                        // three itself.
                        com.mlmvpn.scanner.ui.CombineCoach.startStudio(
                            context, it.username, it.nodes.first().uri,
                        )
                        onReady()
                    }
                }
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}
