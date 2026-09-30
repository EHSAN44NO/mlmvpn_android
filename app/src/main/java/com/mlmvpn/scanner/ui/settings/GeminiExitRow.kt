package com.mlmvpn.scanner.ui.settings

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.CloudManager
import com.mlmvpn.scanner.utils.S
import com.mlmvpn.scanner.utils.XrayJsonGenerator
import kotlinx.coroutines.launch

/**
 * The Gemini exit, under the Gemini switch: set up once on the user's own Cloudflare account, and
 * from then on Gemini leaves from North America. See CloudManager.deployGeminiExit.
 *
 * A row rather than a page. There is one thing to do (set it up), and doing it again is how it is
 * updated -- the deploy keeps the same user id and path, so nothing already connected breaks.
 */
@Composable
internal fun GeminiExitRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cloud = remember { CloudManager(context) }
    val accounts by cloud.accountsFlow.collectAsState()
    var progress by remember { mutableStateOf<String?>(null) }
    // The account that carries it, and the build it carries: a tap on an exit that is already on
    // this app's build asks before uploading it again.
    val carrier = accounts.firstOrNull { it.geminiExitStatus == "deployed" }
    val deployed = if (XrayJsonGenerator.geminiExit == null) 0 else carrier?.let { cloud.geminiExitBuild(it) } ?: 1

    WorkerSetupRow(
        title = stringResource(R.string.settings_gemini_exit),
        icon = Icons.Default.Public,
        tint = Ios.Indigo,
        deployed = deployed,
        latest = CloudManager.GEMINI_EXIT_VERSION,
        progress = progress,
        subtitleOff = stringResource(R.string.settings_gemini_exit_off),
        subtitleOn = stringResource(R.string.settings_gemini_exit_on),
        onDeploy = deploy@{
            // The account that already carries it, so a second deploy updates rather than duplicates.
            val account = carrier ?: accounts.firstOrNull()
            if (account == null) {
                Toast.makeText(context, S(R.string.gemini_exit_no_account), Toast.LENGTH_LONG).show()
                return@deploy
            }
            progress = S(R.string.gemini_exit_uploading)
            scope.launch {
                val (_, message) = cloud.deployGeminiExit(account) { _, step -> scope.launch { progress = step } }
                progress = null
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        },
    )
}
