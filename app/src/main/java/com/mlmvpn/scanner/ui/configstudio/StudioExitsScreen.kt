package com.mlmvpn.scanner.ui.configstudio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.studio.StudioState
import com.mlmvpn.scanner.data.studio.StudioStore
import com.mlmvpn.scanner.data.studio.api.StudioHttpApi
import com.mlmvpn.scanner.data.studio.api.StudioResult
import com.mlmvpn.scanner.data.studio.domain.ExitCountries
import com.mlmvpn.scanner.data.studio.domain.StudioExit
import com.mlmvpn.scanner.ui.configstudio.design.ChoiceRow
import com.mlmvpn.scanner.ui.configstudio.design.CountryBadge
import com.mlmvpn.scanner.ui.configstudio.design.EmptyState
import com.mlmvpn.scanner.ui.configstudio.design.StatusLine
import com.mlmvpn.scanner.ui.configstudio.design.StudioIcons
import com.mlmvpn.scanner.ui.configstudio.design.StudioSeparator
import com.mlmvpn.scanner.ui.configstudio.design.StudioType
import com.mlmvpn.scanner.ui.configstudio.parts.InfoCard
import com.mlmvpn.scanner.ui.configstudio.parts.PageIntro
import com.mlmvpn.scanner.ui.configstudio.parts.ProblemCard
import com.mlmvpn.scanner.ui.configstudio.parts.WizardBusy
import com.mlmvpn.scanner.ui.configstudio.parts.WizardField
import com.mlmvpn.scanner.ui.configstudio.parts.WizardPrimary
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosAlert
import com.mlmvpn.scanner.ui.settings.IosAlertAction
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.utils.S
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * «لوکیشن‌ها» — the exits one account sends people out through, grouped by country.
 *
 * An exit is the operator's own SOCKS5 or HTTP server in some country. Pasted in as a list, tested
 * by the engine itself (dialled THROUGH, so what is measured is what a user will get), and filed
 * under the country the test says it really leaves from when none was given.
 *
 * Per account, not fleet-wide: an exit is reached from one worker, and two accounts having the same
 * server is two rows, each with its own health.
 *
 * Each server states its health as a coloured line -- working with its latency, not answering, not
 * tested, or leaving from a different country than it is filed under -- in place of the check, cross
 * and ellipsis characters it used to print (build 18 redesign).
 */
@Composable
fun StudioExitsScreen(
    store: StudioStore,
    state: StudioState,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fleet = remember(state.installations) {
        store.installedAccounts().filter { acc ->
            state.installations.firstOrNull { it.installationId == acc.id }?.can("exits.v1") == true
        }
    }
    var account by remember { mutableStateOf(fleet.firstOrNull()) }
    var exits by remember { mutableStateOf<List<StudioExit>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var paste by remember { mutableStateOf("") }
    var pasteCc by remember { mutableStateOf<String?>(null) }
    var acting by remember { mutableStateOf<StudioExit?>(null) }

    fun api() = account?.let { StudioHttpApi(context, it) }

    suspend fun reload() {
        val a = api() ?: return
        when (val r = a.listExits()) {
            is StudioResult.Ok -> { exits = r.value; error = null }
            is StudioResult.Err -> error = messageFor(context, r.error)
        }
    }

    /** Four at a time: each test holds a Worker connection open for up to a few seconds. */
    suspend fun testAll(ids: List<String>) {
        val a = api() ?: return
        val gate = Semaphore(4)
        kotlinx.coroutines.coroutineScope {
            ids.map { id -> async { gate.withPermit { a.testExit(id) } } }.awaitAll()
        }
        reload()
    }

    LaunchedEffect(account?.id) {
        loading = true; error = null
        reload()
        loading = false
        // Anything not known-good is re-tested on the way in, so a server that came back is back
        // and one that died says so -- without the operator having to remember to press «تست همه».
        val stale = exits.filter { it.enabled && !it.isUp }.map { it.id }
        if (stale.isNotEmpty()) { busy = true; testAll(stale); busy = false }
    }

    IosScreen(
        title = S(R.string.studio_locations_own),
        onBack = if (busy) null else onBack,
        backLabel = S(R.string.studio_more_locations),
        onRefresh = { reload() },
    ) {
        Spacer(Modifier.height(10.dp))
        PageIntro(S(R.string.studio_exits_intro))

        if (fleet.isEmpty()) {
            InfoCard(S(R.string.studio_exits_needs_update))
            Spacer(Modifier.height(28.dp))
            return@IosScreen
        }

        if (fleet.size > 1) {
            SettingsSectionHeader(S(R.string.studio_placement))
            SettingsGroup {
                fleet.forEachIndexed { i, acc ->
                    if (i > 0) StudioSeparator()
                    ChoiceRow(
                        title = acc.name.ifEmpty { acc.email },
                        selected = account?.id == acc.id,
                        enabled = !busy,
                        onClick = { account = acc },
                    )
                }
            }
        }

        // ---- add
        SettingsSectionHeader(S(R.string.studio_exits_add))
        WizardField(paste, { paste = it; note = null }, S(R.string.studio_exits_paste_hint), monospace = true, lines = 4)
        Spacer(Modifier.height(8.dp))
        SettingsGroup {
            SettingsRow(
                title = S(R.string.studio_exits_country),
                value = pasteCc?.let { ExitCountries.label(it) } ?: S(R.string.studio_exits_country_auto),
                icon = StudioIcons.Locations,
                tint = Ios.Indigo,
                onClick = {
                    // Cycles through "detect" and the common countries: a sheet would be one more
                    // screen for a choice that is usually "detect".
                    val all = listOf<String?>(null) + ExitCountries.common
                    pasteCc = all[(all.indexOf(pasteCc) + 1) % all.size]
                },
            )
        }
        Spacer(Modifier.height(10.dp))
        if (busy) WizardBusy(S(R.string.studio_exits_working))
        else WizardPrimary(S(R.string.studio_exits_add_button), enabled = paste.isNotBlank()) {
            val lines = paste.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            val a = api() ?: return@WizardPrimary
            busy = true; error = null; note = null
            scope.launch {
                // A single https link is a LIST to read, not a server: the engine fetches it.
                val link = lines.singleOrNull()?.takeIf { it.startsWith("https://") }
                val result = if (link != null) a.importExits(link, pasteCc) else a.addExits(lines.map { it to pasteCc })
                when (val r = result) {
                    is StudioResult.Ok -> {
                        paste = ""
                        note = S_added(context, r.value.added.size, r.value.rejected.size)
                        // Tested at once, so a dead line shows up as dead now and an exit added
                        // without a country is filed under the one it really leaves from.
                        testAll(r.value.added)
                    }
                    is StudioResult.Err -> error = messageFor(context, r.error)
                }
                busy = false
            }
        }

        note?.let { InfoCard(it) }
        error?.let { Spacer(Modifier.height(8.dp)); ProblemCard(it) }

        // ---- list
        if (loading) {
            WizardBusy(S(R.string.studio_loading))
        } else if (exits.isEmpty()) {
            EmptyState(
                icon = StudioIcons.OwnServer,
                title = S(R.string.studio_exits_empty_title),
                body = S(R.string.studio_exits_empty),
            )
        } else {
            Spacer(Modifier.height(10.dp))
            SettingsGroup {
                SettingsActionRow(
                    label = S(R.string.studio_exits_test_all),
                    icon = StudioIcons.Test,
                    tint = Ios.Blue,
                    busy = busy,
                    enabled = !busy,
                ) {
                    busy = true; error = null
                    scope.launch { testAll(exits.map { it.id }); busy = false }
                }
            }
            for ((cc, group) in exits.groupBy { it.cc }.toSortedMap()) {
                val up = group.count { it.isUp && it.enabled }
                SettingsSectionHeader(
                    S(R.string.studio_exits_group).replace("%1\$s", ExitCountries.label(cc))
                        .replace("%2\$s", faNum(up)).replace("%3\$s", faNum(group.size))
                )
                SettingsGroup {
                    group.forEachIndexed { i, e ->
                        if (i > 0) StudioSeparator()
                        ExitRow(e) { if (!busy) acting = e }
                    }
                }
            }
        }
        SettingsFooter(S(R.string.studio_exits_noleak))
        Spacer(Modifier.height(28.dp))
    }

    acting?.let { e ->
        val a = api()
        IosAlert(
            title = e.display,
            message = listOfNotNull(ExitCountries.label(e.cc), e.exitIp).joinToString(" · "),
            actions = listOf(
                IosAlertAction(S(R.string.studio_exits_test), {
                    acting = null
                    if (a != null) { busy = true; scope.launch { testAll(listOf(e.id)); busy = false } }
                }),
                IosAlertAction(if (e.enabled) S(R.string.studio_exits_disable) else S(R.string.studio_exits_enable), {
                    acting = null
                    if (a != null) {
                        busy = true
                        scope.launch { a.patchExit(e.id, enabled = !e.enabled); reload(); busy = false }
                    }
                }),
                IosAlertAction(S(R.string.studio_exits_delete), {
                    acting = null
                    if (a != null) {
                        busy = true
                        scope.launch { a.deleteExit(e.id); reload(); busy = false }
                    }
                }, destructive = true),
                IosAlertAction(S(R.string.studio_cancel), { acting = null }),
            ),
            onDismiss = { acting = null },
        )
    }
}

/** One server: its address, its health as a coloured line, and the country it really leaves from. */
@Composable
private fun ExitRow(e: StudioExit, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                e.display.ifBlank { e.id },
                color = if (e.enabled) Ios.Label else Ios.SecondaryLabel,
                fontSize = StudioType.Callout,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            when {
                !e.enabled -> StatusLine(S(R.string.studio_exits_off), Ios.Gray)
                e.isMisfiled -> StatusLine(
                    S(R.string.studio_exits_misfiled).replace("%1\$s", ExitCountries.label(e.exitCc!!)),
                    Ios.Red,
                )
                e.isUp -> StatusLine(
                    listOfNotNull(
                        S(R.string.studio_exit_up),
                        e.latencyMs?.let { S(R.string.studio_exit_ms).replace("%1\$s", faNum(it)) },
                        e.exitIp,
                    ).joinToString(" · "),
                    Ios.Green,
                )
                e.isDown -> StatusLine(S(R.string.studio_exit_down), Ios.Red)
                else -> StatusLine(S(R.string.studio_exit_untested), Ios.Gray)
            }
        }
        val measured = e.exitCc
        if (measured != null && measured != "ZZ") {
            Spacer(Modifier.width(10.dp))
            CountryBadge(measured)
        }
    }
}

private fun S_added(context: android.content.Context, added: Int, rejected: Int): String =
    context.getString(R.string.studio_exits_added).replace("%1\$s", added.toString()).replace("%2\$s", rejected.toString())
