package com.mlmvpn.scanner.ui.mae

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.CloudManager
import com.mlmvpn.scanner.ui.CloudAddAccountForm
import com.mlmvpn.scanner.ui.CloudTroubleshootScreen
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import kotlinx.coroutines.launch

/**
 * MAE's first step when no Cloudflare account is connected: every foreign exit MAE uses (its own
 * Worker, the US exit, the configs of the user's panels) lives on that account, so MAE does not
 * run without one -- and says so, instead of half-working.
 *
 * The account is added right here, with the Cloud tab's own form and its own store: an account
 * connected here is the Cloud tab's account too, and one connected there opens this gate.
 */
@Composable
internal fun MaeCloudGate(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cloud = remember { CloudManager(context) }
    var adding by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var troubleshoot by remember { mutableStateOf<Pair<String, String>?>(null) }

    troubleshoot?.let { credential ->
        androidx.activity.compose.BackHandler { troubleshoot = null }
        CloudTroubleshootScreen(
            cloudManager = cloud,
            onBack = { troubleshoot = null },
            backLabel = stringResource(R.string.mae_short),
            credential = credential,
        )
        return
    }

    IosScreen(title = stringResource(R.string.mae_title), onBack = onBack, backLabel = stringResource(R.string.home)) {
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The glyph iOS would put here: one large symbol on a soft tile.
            Box(
                Modifier.size(76.dp).clip(RoundedCornerShape(20.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFFFFA94D), Ios.CloudflareOrange))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Cloud, contentDescription = null, tint = Color.White, modifier = Modifier.size(42.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.mae_gate_title),
                color = Ios.Label, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.mae_gate_body),
                color = Ios.SecondaryLabel, fontSize = 14.sp, lineHeight = 21.sp, textAlign = TextAlign.Center,
            )
        }
        CloudAddAccountForm(
            isAdding = adding,
            error = error,
            onTroubleshoot = { email, key -> troubleshoot = key to email },
            onAdd = { email, key ->
                adding = true
                error = null
                scope.launch {
                    val (ok, message) = cloud.addAccount(key, email)
                    adding = false
                    // On success the account list changes and MaeScreen moves past this gate.
                    if (!ok) error = message
                }
            },
        )
    }
}
