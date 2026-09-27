package com.mlmvpn.scanner.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.LocalPort
import com.mlmvpn.scanner.utils.NetworkSettings
import com.mlmvpn.scanner.utils.S

// =================================================================================================
// The four network settings, each on a page of its own with the control AND the explanation.
//
// They were four rows on the main Settings list: two text fields and two switches, each with one
// line of subtitle. That is enough for a setting whose name says what it does. It is not enough for
// any of these four -- "Proxy mode" and "Allow LAN" are decisions with consequences the user cannot
// guess, and a wrong guess is either a phone whose traffic is not tunnelled at all or an open proxy
// on a public Wi-Fi. A one-line subtitle cannot carry that, and a FAQ entry three screens away is
// not read by the person about to flip the switch.
//
// So each one opens. The control is at the top, and under it is what it does, when to change it,
// and what goes wrong if it is set incorrectly -- at the moment the user is looking at it.
//
// Every page writes through NetworkSettings, which is the single store all engines read. There is
// no Save: each control applies as it changes, like the rest of Settings.
// =================================================================================================

/**
 * Which resolver answers name lookups, for every engine.
 */
@Composable
fun BackendDnsPage(
    value: String,
    onValueChange: (String) -> Unit,
    backLabel: String,
    onBack: () -> Unit,
) {
    IosTextScreen(
        title = stringResource(R.string.settings_backend_dns),
        value = value,
        backLabel = backLabel,
        placeholder = NetworkSettings.DEFAULT_DNS,
        onBack = onBack,
        onValueChange = onValueChange,
        footer = S(R.string.help_dns_body),
    )
}

/**
 * The port the app publishes its proxy on, for every engine.
 */
@Composable
fun LocalPortPage(
    value: String,
    onValueChange: (String) -> Unit,
    backLabel: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val port = value.trim().toIntOrNull() ?: LocalPort.DEFAULT
    IosTextScreen(
        title = stringResource(R.string.settings_local_port),
        value = value,
        backLabel = backLabel,
        placeholder = LocalPort.DEFAULT.toString(),
        numeric = true,
        error = LocalPort.validate(value),
        onBack = onBack,
        onValueChange = onValueChange,
        // The three derived ports are spelled out, because the whole reason this setting exists
        // is that somebody is typing it into another device and needs to know what to type.
        footer = S(
            R.string.help_port_body,
            port,
            port,
            port + 1,
            port + 2,
            port + LocalPort.PROBE_OFFSET,
        ),
    )
}

/**
 * Tunnel the whole device, or publish a proxy and leave the rest of the phone alone.
 */
@Composable
fun ProxyModePage(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    backLabel: String,
    onBack: () -> Unit,
) {
    IosScreen(
        title = stringResource(R.string.settings_proxy_mode),
        onBack = onBack,
        backLabel = backLabel,
    ) {
        Spacer(Modifier.height(14.dp))
        SettingsGroup {
            SettingsToggle(
                title = stringResource(R.string.settings_proxy_mode),
                subtitle = stringResource(R.string.settings_proxy_mode_desc),
                checked = checked,
                onCheckedChange = onCheckedChange,
                icon = Icons.Default.SwapHoriz,
                tint = Ios.Purple,
            )
        }
        SettingsFooter(S(R.string.help_proxy_mode_body))
        Spacer(Modifier.height(28.dp))
    }
}

/**
 * Whether other devices on the same network may use this phone's tunnel.
 *
 * The one page here whose text is a warning rather than an explanation, because this setting is
 * on by default and what it opens is real: an unauthenticated proxy on every interface. A user who
 * never opens this page still gets the behaviour, so the page has to be worth opening.
 */
@Composable
fun AllowLanPage(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    backLabel: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val port = NetworkSettings.localPort(context)
    IosScreen(
        // The page is titled for the SUBJECT, not for the switch: "Local Network" covers the
        // address, the port and the clients as well as the on/off, and the row that opens it
        // already says which setting it carries.
        title = stringResource(R.string.lan_page_title),
        onBack = onBack,
        backLabel = backLabel,
    ) {
        Spacer(Modifier.height(14.dp))
        SettingsGroup {
            SettingsToggle(
                title = stringResource(R.string.settings_allow_lan),
                subtitle = stringResource(R.string.settings_allow_lan_desc),
                checked = checked,
                onCheckedChange = onCheckedChange,
                icon = Icons.Default.Wifi,
                tint = Ios.Teal,
            )
        }
        SettingsFooter(S(R.string.help_lan_body, port))
        Spacer(Modifier.height(28.dp))
    }
}
