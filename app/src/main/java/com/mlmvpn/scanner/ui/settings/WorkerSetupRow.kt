package com.mlmvpn.scanner.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.mlmvpn.scanner.R

/**
 * A Worker the app puts on the user's Cloudflare account, as one row: not set up, set up and on
 * the build this app ships, or on an older build.
 *
 * Only the first and the last deploy on a tap. A Worker already on this build used to be uploaded
 * again on every tap -- for the user it looked like the whole thing being installed from scratch,
 * and for anything that depended on it (MAE's checks) it was a reason to start over. Now a tap
 * says it is up to date, and "Reinstall" stays available for when it misbehaves.
 */
@Composable
fun WorkerSetupRow(
    title: String,
    icon: ImageVector,
    tint: Color,
    /** The build deployed, 0 when none. */
    deployed: Int,
    /** The build this app ships. */
    latest: Int,
    /** A step while deploying, null otherwise. */
    progress: String?,
    subtitleOff: String,
    subtitleOn: String,
    onDeploy: () -> Unit,
) {
    var askReinstall by remember { mutableStateOf(false) }
    val outdated = deployed in 1 until latest
    SettingsRow(
        title = title,
        subtitle = progress ?: when {
            deployed == 0 -> subtitleOff
            outdated -> stringResource(R.string.worker_update_available, latest, deployed)
            else -> subtitleOn + " · " + stringResource(R.string.worker_version, deployed)
        },
        icon = icon,
        tint = tint,
        value = when {
            progress != null -> "…"
            deployed == 0 -> stringResource(R.string.worker_setup)
            outdated -> stringResource(R.string.worker_update)
            else -> stringResource(R.string.on_2)
        },
        badge = if (outdated && progress == null) 1 else 0,
        onClick = click@{
            if (progress != null) return@click
            if (deployed >= latest) askReinstall = true else onDeploy()
        },
    )
    if (askReinstall) {
        IosAlert(
            title = stringResource(R.string.worker_current_title, title),
            message = stringResource(R.string.worker_current_body, deployed),
            actions = listOf(
                IosAlertAction(stringResource(R.string.worker_reinstall), onClick = { askReinstall = false; onDeploy() }),
                IosAlertAction(stringResource(R.string.worker_ok), onClick = { askReinstall = false }, preferred = true),
            ),
            onDismiss = { askReinstall = false },
        )
    }
}
