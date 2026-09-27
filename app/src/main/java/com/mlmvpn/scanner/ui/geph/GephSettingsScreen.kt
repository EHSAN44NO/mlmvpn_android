package com.mlmvpn.scanner.ui.geph

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.NoAdultContent
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mlmvpn.core.geph.GephForward
import com.mlmvpn.core.geph.GephSettings
import com.mlmvpn.core.geph.GephSettings.Coverage
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.settings.SettingsTextRow
import com.mlmvpn.scanner.ui.settings.SettingsToggle

/**
 * «تنظیمات گف»: every field of the engine's config that is the user's to decide. Each default is
 * the official app's; each change applies at once -- only the engine restarts, the tunnel itself
 * stays up across it.
 */
@Composable
fun GephSettingsScreen(onBack: () -> Unit, backLabel: String) {
    val ctx = LocalContext.current
    var coverage by remember { mutableStateOf(GephSettings.coverage(ctx)) }
    var allowDirect by remember { mutableStateOf(GephSettings.allowDirect(ctx)) }
    var spoofDns by remember { mutableStateOf(GephSettings.spoofDns(ctx)) }
    var blockAds by remember { mutableStateOf(GephSettings.blockAds(ctx)) }
    var blockAdult by remember { mutableStateOf(GephSettings.blockAdult(ctx)) }
    var allowLan by remember { mutableStateOf(GephSettings.allowLan(ctx)) }
    var listenAll by remember { mutableStateOf(GephSettings.listenAll(ctx)) }
    var pac by remember { mutableStateOf(GephSettings.pac(ctx)) }
    var forwards by remember { mutableStateOf(GephSettings.forwards(ctx)) }
    var newListen by remember { mutableStateOf("127.0.0.1:") }
    var newConnect by remember { mutableStateOf("") }
    var forwardError by remember { mutableStateOf<String?>(null) }

    val host = if (listenAll) (com.mlmvpn.core.tunnel.CoreConfig.localNetworkAddress(ctx) ?: "0.0.0.0") else "127.0.0.1"

    IosScreen(title = stringResource(R.string.geph_settings_title), onBack = onBack, backLabel = backLabel) {
        Spacer(Modifier.height(12.dp))

        // ── coverage ──
        SettingsSectionHeader(stringResource(R.string.geph_coverage_section))
        SettingsGroup {
            SettingsRow(
                title = stringResource(R.string.geph_coverage_vpn),
                icon = if (coverage == Coverage.VPN) Icons.Default.Check else Icons.Default.VpnLock,
                tint = if (coverage == Coverage.VPN) Ios.Green else Ios.Gray,
                subtitle = stringResource(R.string.geph_coverage_vpn_sub),
                showChevron = false,
                onClick = {
                    if (coverage != Coverage.VPN) {
                        coverage = Coverage.VPN
                        GephSettings.setCoverage(ctx, Coverage.VPN)
                        reconnectGephNow(ctx)
                    }
                },
            )
            Separator()
            SettingsRow(
                title = stringResource(R.string.geph_coverage_proxy),
                icon = if (coverage == Coverage.PROXY) Icons.Default.Check else Icons.Default.Router,
                tint = if (coverage == Coverage.PROXY) Ios.Green else Ios.Gray,
                subtitle = stringResource(R.string.geph_coverage_proxy_sub),
                showChevron = false,
                onClick = {
                    if (coverage != Coverage.PROXY) {
                        coverage = Coverage.PROXY
                        GephSettings.setCoverage(ctx, Coverage.PROXY)
                        reconnectGephNow(ctx)
                    }
                },
            )
        }
        SettingsFooter(stringResource(R.string.geph_coverage_footer))

        if (coverage == Coverage.PROXY) {
            SettingsSectionHeader(stringResource(R.string.geph_proxy_section))
            SettingsGroup {
                val socks = "$host:${GephSettings.SOCKS_PORT}"
                val http = "$host:${GephSettings.HTTP_PORT}"
                SettingsRow(
                    title = "SOCKS5", icon = Icons.Default.ContentCopy, tint = Ios.Blue,
                    value = socks, showChevron = false,
                    onClick = { copyToClipboard(ctx, "SOCKS5", socks) },
                )
                Separator()
                SettingsRow(
                    title = "HTTP", icon = Icons.Default.ContentCopy, tint = Ios.Blue,
                    value = http, showChevron = false,
                    onClick = { copyToClipboard(ctx, "HTTP", http) },
                )
                Separator()
                SettingsToggle(
                    title = stringResource(R.string.geph_listen_all),
                    checked = listenAll,
                    onCheckedChange = { listenAll = it; GephSettings.setListenAll(ctx, it); applyGephNow(ctx) },
                    icon = Icons.Default.Lan,
                    tint = Ios.Teal,
                    subtitle = stringResource(R.string.geph_listen_all_sub),
                )
                Separator()
                SettingsToggle(
                    title = stringResource(R.string.geph_pac),
                    checked = pac,
                    onCheckedChange = { pac = it; GephSettings.setPac(ctx, it); applyGephNow(ctx) },
                    icon = Icons.Default.SettingsEthernet,
                    tint = Ios.Indigo,
                    subtitle = if (pac) "http://$host:${GephSettings.PAC_PORT}/proxy.pac" else stringResource(R.string.geph_pac_sub),
                )
            }
            SettingsFooter(stringResource(R.string.geph_proxy_footer))
        }

        // ── speed ──
        SettingsSectionHeader(stringResource(R.string.geph_speed_section))
        SettingsGroup {
            SettingsToggle(
                title = stringResource(R.string.geph_allow_direct),
                checked = allowDirect,
                onCheckedChange = { allowDirect = it; GephSettings.setAllowDirect(ctx, it); applyGephNow(ctx) },
                icon = Icons.Default.SwapHoriz,
                tint = Ios.Orange,
                subtitle = stringResource(R.string.geph_allow_direct_sub),
            )
            Separator()
            SettingsToggle(
                title = stringResource(R.string.geph_spoof_dns),
                checked = spoofDns,
                onCheckedChange = { spoofDns = it; GephSettings.setSpoofDns(ctx, it); applyGephNow(ctx) },
                icon = Icons.Default.Dns,
                tint = Ios.Blue,
                subtitle = stringResource(R.string.geph_spoof_dns_sub),
            )
        }
        SettingsFooter(stringResource(R.string.geph_speed_settings_footer))

        // ── content filtering ──
        SettingsSectionHeader(stringResource(R.string.geph_filter_section))
        SettingsGroup {
            SettingsToggle(
                title = stringResource(R.string.geph_block_ads),
                checked = blockAds,
                onCheckedChange = { blockAds = it; GephSettings.setBlockAds(ctx, it); applyGephNow(ctx) },
                icon = Icons.Default.Block,
                tint = Ios.Red,
            )
            Separator()
            SettingsToggle(
                title = stringResource(R.string.geph_block_adult),
                checked = blockAdult,
                onCheckedChange = { blockAdult = it; GephSettings.setBlockAdult(ctx, it); applyGephNow(ctx) },
                icon = Icons.Default.NoAdultContent,
                tint = Ios.Pink,
            )
        }
        SettingsFooter(stringResource(R.string.geph_filter_footer))

        // ── local network ──
        SettingsSectionHeader(stringResource(R.string.geph_lan_section))
        SettingsGroup {
            SettingsToggle(
                title = stringResource(R.string.geph_allow_lan),
                checked = allowLan,
                onCheckedChange = { allowLan = it; GephSettings.setAllowLan(ctx, it); applyGephNow(ctx) },
                icon = Icons.Default.Lan,
                tint = Ios.Teal,
                subtitle = stringResource(R.string.geph_allow_lan_sub),
            )
        }

        // ── port forwarding ──
        SettingsSectionHeader(stringResource(R.string.geph_forward_section))
        SettingsGroup {
            forwards.forEachIndexed { i, f ->
                if (i > 0) Separator()
                SettingsRow(
                    title = f.listen,
                    icon = Icons.Default.Delete,
                    tint = Ios.Red,
                    value = f.connect,
                    showChevron = false,
                    onClick = {
                        forwards = forwards - f
                        GephSettings.setForwards(ctx, forwards)
                        applyGephNow(ctx)
                    },
                )
            }
            if (forwards.isNotEmpty()) Separator()
            SettingsTextRow(
                title = stringResource(R.string.geph_forward_listen),
                value = newListen,
                onValueChange = { newListen = it },
                icon = Icons.Default.Public,
                tint = Ios.Gray,
                placeholder = "127.0.0.1:2222",
                error = forwardError,
            )
            Separator()
            SettingsTextRow(
                title = stringResource(R.string.geph_forward_connect),
                value = newConnect,
                onValueChange = { newConnect = it },
                icon = Icons.Default.Shield,
                tint = Ios.Gray,
                placeholder = "example.com:22",
            )
            Separator()
            SettingsActionRow(
                label = stringResource(R.string.geph_forward_add),
                icon = Icons.Default.Add,
                tint = Ios.Blue,
                enabled = newListen.isNotBlank() && newConnect.isNotBlank(),
                onClick = {
                    if (!GephSettings.validForward(newListen, newConnect)) {
                        forwardError = ctx.getString(R.string.geph_forward_invalid)
                        return@SettingsActionRow
                    }
                    forwardError = null
                    forwards = forwards + GephForward(newListen.trim(), newConnect.trim())
                    GephSettings.setForwards(ctx, forwards)
                    newListen = "127.0.0.1:"
                    newConnect = ""
                    applyGephNow(ctx)
                },
            )
        }
        SettingsFooter(stringResource(R.string.geph_forward_footer))

        SettingsFooter(stringResource(R.string.geph_settings_apply_footer))
        Spacer(Modifier.height(28.dp))
    }
}
