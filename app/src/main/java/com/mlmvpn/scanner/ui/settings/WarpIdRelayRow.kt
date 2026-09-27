package com.mlmvpn.scanner.ui.settings

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.mlmvpn.core.warp.WarpIdRelay
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.CloudManager
import com.mlmvpn.scanner.utils.S
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The WARP identity relay, set up once on the user's own Cloudflare account — the Gemini exit's
 * twin. From then on WireGuard, WARP-in-WARP and MASQUE get their identity through it. See
 * WarpIdRelay. Tapping again redeploys in place (same name, same key).
 */
@Composable
internal fun WarpIdRelayRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cloud = remember { CloudManager(context) }
    val accounts by cloud.accountsFlow.collectAsState()
    var ready by remember { mutableStateOf(WarpIdRelay.isReady(context)) }
    var progress by remember { mutableStateOf<String?>(null) }

    SettingsRow(
        title = stringResource(R.string.settings_warp_id),
        subtitle = progress ?: stringResource(if (ready) R.string.settings_warp_id_on else R.string.settings_warp_id_off),
        icon = Icons.Default.VpnKey,
        tint = Ios.Orange,
        value = when {
            progress != null -> "…"
            ready -> stringResource(R.string.on_2)
            else -> stringResource(R.string.settings_warp_id_setup)
        },
        onClick = click@{
            if (progress != null) return@click
            val account = accounts.firstOrNull { it.accountId == WarpIdRelay.accountId(context) } ?: accounts.firstOrNull()
            if (account == null) {
                Toast.makeText(context, S(R.string.warp_id_no_account), Toast.LENGTH_LONG).show()
                return@click
            }
            progress = "…"
            scope.launch {
                val (_, message) = withContext(Dispatchers.IO) {
                    WarpIdRelay.deploy(context, account) { step -> scope.launch { progress = step } }
                }
                progress = null
                ready = WarpIdRelay.isReady(context)
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        },
    )
}
