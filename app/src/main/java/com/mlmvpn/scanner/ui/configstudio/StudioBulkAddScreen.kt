package com.mlmvpn.scanner.ui.configstudio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.CloudManager
import com.mlmvpn.scanner.data.studio.StudioDeployer
import com.mlmvpn.scanner.data.studio.StudioStore
import com.mlmvpn.scanner.ui.configstudio.parts.InfoCard
import com.mlmvpn.scanner.ui.configstudio.parts.PageIntro
import com.mlmvpn.scanner.ui.configstudio.parts.ProblemCard
import com.mlmvpn.scanner.ui.configstudio.parts.WizardField
import com.mlmvpn.scanner.ui.configstudio.parts.WizardPrimary
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.utils.S
import kotlinx.coroutines.launch

/**
 * «افزودن گروهی» — connect many Cloudflare accounts in one pass.
 *
 * The fleet is uncapped by design (D7), and the wizard is five steps. Running it fifty times is not
 * a thing anyone will do, so an operator with fifty accounts either does not use them or the app
 * gives them this (§A.8).
 *
 * Two properties matter more than the screen does:
 *
 *  * **Each account runs the discovery scan.** This is not a shortcut around `StudioDeployer` — it
 *    is the same call the wizard makes, so an account that already carries an installation is
 *    *adopted* rather than given a second worker (D8). Pasting a token twice is safe.
 *  * **One failure is one row, not the end of the run.** A bad token, an account with no
 *    workers.dev subdomain, an engine newer than this app — each stops that account and nothing
 *    else, and the row says which. A queue that aborts on the first problem is a queue that
 *    finishes once and then never gets used again.
 *
 * The tokens are typed here, not read from anywhere: this screen never touches stored credentials.
 */
private enum class Phase { PASTE, RUNNING, DONE }

private data class Job(
    val email: String,
    val token: String,
    var state: String = "queued",
    var detail: String? = null,
    var adopted: Boolean = false,
)

@Composable
fun StudioBulkAddScreen(
    store: StudioStore,
    onBack: () -> Unit,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cloud = remember { CloudManager(context) }

    var phase by remember { mutableStateOf(Phase.PASTE) }
    var raw by remember { mutableStateOf("") }
    var parseError by remember { mutableStateOf<String?>(null) }
    val jobs = remember { mutableStateListOf<Job>() }
    var current by remember { mutableStateOf(0) }

    // Leaving mid-run would leave half the accounts connected and the operator with no list of
    // which — so the back gesture is refused while the queue is moving, the same rule the setup
    // wizard applies to a deploy in flight.
    BackHandler(enabled = LocalStudioVisible.current) {
        when (phase) {
            Phase.RUNNING -> Unit
            Phase.DONE -> onFinished()
            Phase.PASTE -> onBack()
        }
    }

    fun start() {
        val parsed = parseLines(raw)
        if (parsed.isEmpty()) {
            parseError = context.getString(R.string.studio_bulk_none_parsed)
            return
        }
        parseError = null
        jobs.clear()
        jobs.addAll(parsed)
        phase = Phase.RUNNING

        scope.launch {
            for ((index, job) in jobs.withIndex()) {
                current = index
                jobs[index] = job.copy(state = "adding")

                // Reuse the account if this credential is already in «ابری» — one account store,
                // and adding it twice would give the same Cloudflare account two rows and let the
                // fleet count it twice (D2).
                val existing = cloud.accounts.firstOrNull { it.token == job.token }
                val account = existing ?: run {
                    val (ok, message) = cloud.addAccount(job.token, job.email)
                    if (!ok) {
                        jobs[index] = job.copy(state = "failed", detail = message)
                        null
                    } else {
                        cloud.accounts.lastOrNull()
                    }
                }
                if (account == null) continue

                jobs[index] = job.copy(state = "installing")
                when (val res = StudioDeployer(context).install(account)) {
                    is StudioDeployer.Result.Ready ->
                        jobs[index] = job.copy(state = "ok", adopted = res.adopted)
                    is StudioDeployer.Result.WouldDowngrade ->
                        jobs[index] = job.copy(
                            state = "failed",
                            detail = context.getString(R.string.studio_downgrade_body),
                        )
                    is StudioDeployer.Result.Failed ->
                        jobs[index] = job.copy(state = "failed", detail = res.message)
                }
            }
            store.refreshInstallations()
            store.syncAll()
            store.refreshDashboard()
            phase = Phase.DONE
        }
    }

    IosScreen(
        title = S(R.string.studio_account_bulk_add),
        onBack = if (phase == Phase.RUNNING) null else onBack,
        backLabel = S(R.string.studio_accounts),
    ) {
        Spacer(Modifier.height(10.dp))

        when (phase) {
            Phase.PASTE -> {
                PageIntro(S(R.string.studio_bulk_sub))
                InfoCard(S(R.string.studio_bulk_format))
                Spacer(Modifier.height(12.dp))
                WizardField(
                    raw,
                    { raw = it; parseError = null },
                    S(R.string.studio_bulk_hint),
                    monospace = true,
                    lines = 4,
                )
                SettingsFooter(
                    S(R.string.studio_bulk_counted).replace("%1\$s", faNum(parseLines(raw).size))
                )
                parseError?.let { ProblemCard(it) }
                Spacer(Modifier.height(16.dp))
                WizardPrimary(S(R.string.studio_bulk_start), enabled = raw.isNotBlank()) { start() }
                InfoCard(S(R.string.studio_bulk_adopt_note))
            }

            Phase.RUNNING, Phase.DONE -> {
                PageIntro(
                    if (phase == Phase.RUNNING)
                        S(R.string.studio_bulk_progress)
                            .replace("%1\$s", faNum(current + 1))
                            .replace("%2\$s", faNum(jobs.size))
                    else S(R.string.studio_bulk_done)
                )

                SettingsSectionHeader(S(R.string.studio_accounts))
                SettingsGroup {
                    jobs.forEachIndexed { index, job ->
                        if (index > 0) Separator()
                        JobRow(job)
                    }
                }

                if (phase == Phase.DONE) {
                    val failed = jobs.count { it.state == "failed" }
                    Spacer(Modifier.height(12.dp))
                    if (failed > 0) {
                        ProblemCard(
                            S(R.string.studio_bulk_failed_n).replace("%1\$s", faNum(failed))
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    WizardPrimary(S(R.string.studio_done), onClick = onFinished)
                }
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}

/** One account's progress, on the row metrics the grouped card around it uses. */
@Composable
private fun JobRow(job: Job) {
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(job.email, color = Ios.Label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(
                when (job.state) {
                    "queued" -> S(R.string.studio_bulk_state_queued)
                    "adding" -> S(R.string.studio_bulk_state_adding)
                    "installing" -> S(R.string.studio_bulk_state_installing)
                    // Adoption is called out rather than folded into "done", because it is the
                    // answer to the question an operator re-pasting a token is actually asking.
                    "ok" -> if (job.adopted) S(R.string.studio_bulk_state_adopted)
                    else S(R.string.studio_bulk_state_ok)
                    else -> S(R.string.studio_bulk_state_failed)
                },
                color = when (job.state) {
                    "ok" -> Ios.Green
                    "failed" -> Ios.Red
                    else -> Ios.SecondaryLabel
                },
                fontSize = 13.sp,
            )
        }
        job.detail?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = Ios.SecondaryLabel, fontSize = 11.sp)
        }
    }
}

/**
 * One account per line, `email` and token separated by whatever the operator pasted.
 *
 * Comma, tab, semicolon or spaces all work, because a list like this is pasted out of a spreadsheet,
 * a notes app or a chat message and each of those uses a different one. A line that does not yield
 * both halves is skipped rather than guessed at — a token parsed out of the wrong half fails much
 * later, with an error about the credential rather than about the line.
 */
private fun parseLines(raw: String): List<Job> =
    raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val parts = line.split(',', '\t', ';', ' ')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (parts.size < 2) return@mapNotNull null
            // The email is whichever half looks like one, so the two orders people actually paste
            // both work instead of only the one this file happened to pick.
            val email = parts.firstOrNull { it.contains('@') } ?: return@mapNotNull null
            val token = parts.firstOrNull { it != email } ?: return@mapNotNull null
            Job(email = email, token = token)
        }
        .distinctBy { it.token }
        .toList()
