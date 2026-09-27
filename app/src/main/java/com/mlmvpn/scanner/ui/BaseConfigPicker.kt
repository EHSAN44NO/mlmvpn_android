package com.mlmvpn.scanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.data.CombineEngine
import com.mlmvpn.scanner.data.GroupManager
import com.mlmvpn.scanner.data.NodeManager
import com.mlmvpn.scanner.data.ScanGuard
import com.mlmvpn.scanner.models.VpnNode
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.settings.SettingsTextRow
import com.mlmvpn.scanner.utils.VpnConfig
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S
import kotlinx.coroutines.launch

// =================================================================================================
// Choosing what to combine.
//
// The scanner's base config was a free-text field: paste a `vless://…` here. Everyone who reaches
// that screen already HAS configs -- they just fetched them from a panel, or they have a list of
// them one tab away -- so the field asked them to go and copy something the app was already
// holding, and the most common way to fail at it was pasting a config that cannot be combined at
// all.
//
// Two of them cannot, and both are excluded here rather than left to fail later:
//
//   * the built-in Iran defaults, which are upstream JSON files whose whole point is being
//     unedited -- rewriting their address is exactly what breaks them, and they carry no
//     `host@address` to rewrite in the first place;
//   * the domain-fronting profile, which already points at a local port.
//
// Manual paste is still there, because someone with a config from outside the app has to be able
// to use it.
//
// And every row can be MEASURED before it is chosen. A base config that does not itself work makes
// the whole scan meaningless -- every clean IP fails verification, and the screen then reports
// "none of the addresses answered", which reads as a blocked range rather than as a dead config.
// One tap on the row's speed pill settles that in a few seconds. Choosing without measuring is
// still one tap on the row itself; the measurement is an offer, not a gate.
// =================================================================================================

/** One thing that can be a base config, with enough context to tell two of them apart. */
data class BaseConfigOption(
    val label: String,
    val detail: String,
    val uri: String,
    /** The cloud group it came from, when it came from one. The coach combines whole groups. */
    val groupId: String?,
)

/**
 * Whether a node can be a combine source.
 *
 * A combine rewrites the endpoint and moves the original host into `sni=`/`host=`. A config with
 * nothing to rewrite -- a JSON blob, or one already aimed at 127.0.0.1 -- has no endpoint to
 * replace, so offering it would produce a list of configs that all silently fail their first
 * handshake. `vmess://` and `ss://` DO qualify even though neither carries a literal
 * `user@host:port`: [CombineEngine.rewrite] reads and rebuilds both formats.
 */
fun isCombinable(node: VpnNode): Boolean {
    if (NodeManager.isProtected(node)) return false
    if (node.id == com.mlmvpn.scanner.mitm.MitmProfile.NODE_ID) return false
    if (node.groupTitle == com.mlmvpn.scanner.mitm.MitmProfile.GROUP) return false
    if (node.uri.trimStart().startsWith("{")) return false
    val config = VpnConfig.parseUri(node.uri) ?: return false
    if (config.address.isBlank()) return false
    if (config.address == "127.0.0.1") return false
    return true
}

/** Everything on this device that could be a base config, cloud groups first. */
fun collectBaseConfigOptions(context: android.content.Context): List<BaseConfigOption> {
    val out = mutableListOf<BaseConfigOption>()

    val groups = GroupManager(context).also { it.loadCloudGroups() }.cloudGroups
    for (group in groups) {
        val usable = group.nodes.filter { isCombinable(it) }
        if (usable.isEmpty()) continue
        val engine = usable.first().engineType
        out += BaseConfigOption(
            label = group.title,
            detail = S(R.string.engine_configs, engine, faCount(usable.size), group.date),
            uri = usable.first().uri,
            groupId = group.id,
        )
    }

    val nodes = NodeManager(context).let { nm -> synchronized(nm.nodes) { nm.nodes.toList() } }
    for (node in nodes.filter { isCombinable(it) }) {
        val config = VpnConfig.parseUri(node.uri)
        out += BaseConfigOption(
            label = node.name,
            detail = listOfNotNull(
                node.groupTitle?.takeIf { it.isNotBlank() },
                config?.let { "${it.address}:${it.port}" },
            ).joinToString(" · "),
            uri = node.uri,
            groupId = null,
        )
    }
    return out
}

/** What a row's measurement is doing, or what it found. */
private sealed class Probe {
    object Idle : Probe()
    object Running : Probe()
    data class Ok(val ms: Long) : Probe()
    object Failed : Probe()
}

/**
 * The picker page.
 *
 * Cloud groups are listed first and as GROUPS, because combining a whole group is what the guided
 * flow does and what gives the best result -- every config in the group against every clean IP.
 * Individual configs from the connection list follow, for the case where the user wants one
 * specific server.
 */
@Composable
fun BaseConfigPickerScreen(
    current: String,
    onPick: (BaseConfigOption) -> Unit,
    onManual: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val options = remember { collectBaseConfigOptions(context) }
    val cloudOptions = options.filter { it.groupId != null }
    val nodeOptions = options.filter { it.groupId == null }

    var manual by remember { mutableStateOf(if (options.none { it.uri == current }) current else "") }
    var showManual by remember { mutableStateOf(false) }

    // Keyed by URI rather than by index, so a measurement stays attached to its config.
    val probes = remember { mutableStateMapOf<String, Probe>() }

    /**
     * Measure one config for real.
     *
     * Through ScanGuard, because this is the same core and the same link a running scan is using:
     * measured underneath one, a healthy config reads "no answer". The guard asks once and then
     * either runs this now or after the user stops the scan.
     */
    fun measure(uri: String) {
        if (probes[uri] == Probe.Running) return
        ScanGuard.run(ScanGuard.Reason.DELAY_TEST) {
            probes[uri] = Probe.Running
            scope.launch {
                val ms = CombineEngine.measureUri(context, uri)
                probes[uri] = if (ms > 0) Probe.Ok(ms) else Probe.Failed
            }
        }
    }

    IosScreen(title = S(R.string.choose_a_base_config), onBack = onBack, backLabel = S(R.string.scan_2)) {
        Spacer(Modifier.height(14.dp))

        if (options.isEmpty() && !showManual) {
            SettingsFooter(
                S(R.string.there_is_no_combinable_config_on_this) +
                    S(R.string.or_enter_your_own_config_by_hand)
            )
        }

        if (cloudOptions.isNotEmpty()) {
            SettingsSectionHeader(S(R.string.groups_taken_from_the_panels))
            SettingsGroup {
                cloudOptions.forEachIndexed { index, option ->
                    if (index > 0) Separator()
                    BaseConfigRow(
                        option = option,
                        selected = option.uri == current,
                        icon = Icons.Default.Cloud,
                        tint = Ios.Orange,
                        probe = probes[option.uri] ?: Probe.Idle,
                        onPick = { onPick(option); onBack() },
                        onMeasure = { measure(option.uri) },
                    )
                }
            }
            SettingsFooter(
                S(R.string.choosing_a_group_gives_the_best_result) +
                    S(R.string.ip_found_not_just_one)
            )
        }

        if (nodeOptions.isNotEmpty()) {
            SettingsSectionHeader(S(R.string.configs_from_the_connection_page))
            SettingsGroup {
                nodeOptions.take(40).forEachIndexed { index, option ->
                    if (index > 0) Separator()
                    BaseConfigRow(
                        option = option,
                        selected = option.uri == current,
                        icon = Icons.Default.Storage,
                        tint = Ios.Gray,
                        probe = probes[option.uri] ?: Probe.Idle,
                        onPick = { onPick(option); onBack() },
                        onMeasure = { measure(option.uri) },
                    )
                }
            }
            SettingsFooter(
                S(R.string.the_built_in_configs_and_the_fronting) +
                    S(R.string.and_combining_them_produces_a_config_that)
            )
        }

        if (options.isNotEmpty()) {
            SettingsFooter(S(R.string.base_config_delay_hint))
        }

        SettingsSectionHeader(S(R.string.manual_2))
        if (showManual) {
            val manualUri = manual.trim()
            val manualValid = manualUri.isNotBlank() && VpnConfig.parseUri(manualUri) != null
            SettingsGroup {
                SettingsTextRow(
                    title = S(R.string.link_3),
                    value = manual,
                    onValueChange = { manual = it },
                    placeholder = "vless://…",
                    error = if (manual.isNotBlank() && !manualValid) {
                        S(R.string.this_link_could_not_be_parsed)
                    } else null,
                )
                Separator()
                // A pasted link is the one most worth measuring -- it came from outside the app
                // and nothing here has ever proved it works.
                SettingsActionRow(
                    label = when (val p = probes[manualUri] ?: Probe.Idle) {
                        is Probe.Running -> S(R.string.base_config_testing)
                        is Probe.Ok -> S(R.string.base_config_delay_ok, faCount(p.ms.toInt()))
                        is Probe.Failed -> S(R.string.base_config_delay_failed)
                        else -> S(R.string.base_config_test_delay)
                    },
                    icon = Icons.Default.Speed,
                    tint = when (probes[manualUri]) {
                        is Probe.Ok -> Ios.Green
                        is Probe.Failed -> Ios.Red
                        else -> Ios.Blue
                    },
                    enabled = manualValid,
                ) {
                    measure(manualUri)
                }
                Separator()
                SettingsActionRow(
                    label = S(R.string.use_this_config),
                    icon = Icons.Default.Check,
                    tint = Ios.Green,
                    enabled = manualValid,
                ) {
                    onManual(manualUri)
                    onBack()
                }
            }
        } else {
            SettingsGroup {
                SettingsActionRow(S(R.string.enter_a_config_by_hand), Icons.Default.Edit) {
                    showManual = true
                }
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}

/**
 * One pickable config, with its own measure button.
 *
 * Two tap targets in one row rather than a row that does both: choosing and testing are different
 * intentions, and a single tap that did both would either make every glance cost a measurement or
 * make measuring cost a selection the user did not want yet.
 */
@Composable
private fun BaseConfigRow(
    option: BaseConfigOption,
    selected: Boolean,
    icon: ImageVector,
    tint: Color,
    probe: Probe,
    onPick: () -> Unit,
    onMeasure: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(58.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onPick)
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (selected) Icons.Default.Check else icon,
                contentDescription = null,
                tint = if (selected) Ios.Blue else tint,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.Center) {
                Text(
                    option.label,
                    color = Ios.Label,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    option.detail,
                    color = Ios.SecondaryLabel,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        val pillColor = when (probe) {
            is Probe.Ok -> Ios.Green
            is Probe.Failed -> Ios.Red
            else -> Ios.Blue
        }
        Box(
            modifier = Modifier
                .padding(end = 16.dp)
                .background(pillColor.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                .border(1.dp, pillColor.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                .clickable(enabled = probe != Probe.Running, onClick = onMeasure)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (probe) {
                is Probe.Running -> CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = pillColor,
                    strokeWidth = 2.dp,
                )
                is Probe.Ok -> Text(
                    faCount(probe.ms.toInt()) + " ms",
                    color = pillColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                is Probe.Failed -> Text(
                    S(R.string.base_config_delay_failed_short),
                    color = pillColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Speed,
                        contentDescription = null,
                        tint = pillColor,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        S(R.string.base_config_test_delay_short),
                        color = pillColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
