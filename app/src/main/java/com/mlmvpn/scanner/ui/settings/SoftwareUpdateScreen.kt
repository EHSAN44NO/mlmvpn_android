package com.mlmvpn.scanner.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.update.UpdateChecker
import com.mlmvpn.scanner.utils.S
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings > Software update.
 *
 * Modelled on the platform's own update screen, deliberately: it is the one place in a phone every
 * user has already learned, and an updater that looks like the one they know needs no explaining.
 * Two cards. The first is the action and the setting that governs it; the second is what is
 * already installed.
 *
 * The screen exists because the updater had no home. It only ever appeared as a dialog, on its own
 * schedule, when a check happened to succeed -- so a user who dismissed it once, or whose network
 * blocked GitHub on the one attempt that ran, had no way to ask again. There was no button to
 * check, no way to see whether a check had ever worked, and no way to turn off a background
 * download. All three are here now.
 *
 * Every state the check can end in says something: checking, up to date with when, an update with
 * its size, or a failure with the reason. "Nothing happened" is not one of the options.
 */
@Composable
fun SoftwareUpdateScreen(onDismiss: () -> Unit, backLabel: String, onOpenCheck: () -> Unit) {
    val context = LocalContext.current

    val state by UpdateChecker.state.collectAsState()
    val autoDownloading by UpdateChecker.autoDownloading.collectAsState()
    val progress by UpdateChecker.downloadProgressFlow.collectAsState()

    var autoWifi by remember { mutableStateOf(UpdateChecker.autoDownloadOnWifi(context)) }

    val checking = state is UpdateChecker.State.Checking
    val available = (state as? UpdateChecker.State.Available)?.info
    val downloading = progress != null && (progress ?: 0) < 100
    val readyToInstall = available != null && UpdateChecker.isDownloaded(context, available)

    val lastChecked = UpdateChecker.lastCheckedAt(context)

    IosScreen(title = stringResource(R.string.settings_software_update), onBack = onDismiss, backLabel = backLabel) {
        Spacer(Modifier.height(14.dp))

        // ---- download and install ------------------------------------------------------------
        // A row, not the action itself. Checking is a network call to GitHub on a line that often
        // blocks GitHub, and the result has four different shapes -- checking, up to date, a
        // version with its notes, or a failure worth explaining. None of that fits on one row, so
        // the row opens the page that can hold it. See UpdateCheckScreen.
        SettingsGroup {
            SettingsRow(
                title = stringResource(R.string.update_download_and_install),
                icon = Icons.Default.SystemUpdate,
                tint = if (available != null) Ios.Green else Ios.Blue,
                value = if (available != null) available.versionName else null,
                onClick = onOpenCheck,
            )
        }
        // The status line under the card, exactly the shape the platform screen uses: when it was
        // last checked, then the caveat about mobile data.
        SettingsFooter(
            buildString {
                append(statusText(context, state, lastChecked, readyToInstall, autoDownloading))
                append("\n")
                append(S(R.string.update_mobile_data_warning))
            }
        )

        // ---- auto download --------------------------------------------------------------------
        SettingsGroup {
            SettingsToggle(
                title = stringResource(R.string.update_auto_download_wifi),
                subtitle = stringResource(R.string.update_auto_download_wifi_desc),
                checked = autoWifi,
                onCheckedChange = {
                    autoWifi = it
                    UpdateChecker.setAutoDownloadOnWifi(context, it)
                },
                icon = Icons.Default.Wifi,
                tint = Ios.Teal,
            )
        }
        SettingsFooter(stringResource(R.string.update_auto_download_footer))

        Spacer(Modifier.height(8.dp))

        // ---- what is installed ------------------------------------------------------------------
        SettingsSectionHeader(stringResource(R.string.update_last_installed))
        SettingsGroup {
            SettingsRow(
                title = stringResource(R.string.settings_version),
                icon = Icons.Default.History,
                tint = Ios.Gray,
                value = installedVersion(context),
                showChevron = false,
            )
            Separator()
            SettingsRow(
                title = stringResource(R.string.update_installed_on),
                icon = Icons.Default.CloudDownload,
                tint = Ios.Indigo,
                subtitle = installedOnText(context),
                showChevron = false,
            )
        }
        SettingsFooter(stringResource(R.string.update_last_installed_footer))

        Spacer(Modifier.height(28.dp))
    }
}

/** The one line under the action card: what the last check found, in words. */
@Composable
private fun statusText(
    context: Context,
    state: UpdateChecker.State,
    lastChecked: Long,
    readyToInstall: Boolean,
    autoDownloading: Boolean,
): String = when {
    autoDownloading -> S(R.string.update_auto_downloading)
    readyToInstall -> S(R.string.update_ready_to_install)
    state is UpdateChecker.State.Checking -> S(R.string.update_checking)
    state is UpdateChecker.State.Available ->
        S(R.string.update_available_line, state.info.versionName, formatSize(state.info.apkSizeBytes))
    state is UpdateChecker.State.UpToDate -> S(R.string.update_up_to_date_line, dateTime(state.checkedAt))
    state is UpdateChecker.State.Failed -> S(R.string.update_failed_line, state.reason)
    lastChecked > 0L -> S(R.string.update_last_checked, dateTime(lastChecked))
    else -> S(R.string.update_never_checked)
}

private fun installedVersion(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "-"
}.getOrDefault("-")

/**
 * When this build was installed, from the platform rather than from anything the app records.
 *
 * `lastUpdateTime` is maintained by PackageManager and survives everything the app could lose --
 * cleared data, a restore, an install from outside the app entirely. An app-kept timestamp would
 * be wrong in exactly those cases, which are the ones a user checks this line to understand.
 */
@Composable
private fun installedOnText(context: Context): String {
    val at = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    }.getOrDefault(0L)
    return if (at <= 0L) S(R.string.update_installed_unknown)
    else S(R.string.update_installed_on_line, dateTime(at))
}

private fun dateTime(millis: Long): String =
    SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.US).format(Date(millis))

private fun formatSize(bytes: Long): String =
    if (bytes <= 0) "-" else String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
