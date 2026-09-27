package com.mlmvpn.scanner.ui.lan

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.lan.LanSetupServer.ClientOs
import com.mlmvpn.scanner.lan.LanStatus
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader

// =================================================================================================
// The same instructions the other device gets, readable on the phone.
//
// The setup page is the better path and always will be -- it lands on the machine being configured,
// where the values can be copied rather than retyped. These exist for the case the page cannot
// cover: the device has no browser (a console, a TV), or the person doing the configuring is
// looking at the phone and telling someone else what to tap.
//
// The step text is the SAME `lanweb_steps_*` resources the served page uses. One source, so a
// correction to a menu path fixes both places, and the phone can never disagree with the page the
// other device is reading.
// =================================================================================================

/** Every system with steps, in the order most users need them. */
val GUIDE_ORDER = listOf(
    ClientOs.WINDOWS,
    ClientOs.MACOS,
    ClientOs.IOS,
    ClientOs.ANDROID,
    ClientOs.LINUX,
    ClientOs.OTHER,
)

fun guideNameRes(os: ClientOs): Int = when (os) {
    ClientOs.WINDOWS -> R.string.lanweb_os_windows
    ClientOs.MACOS -> R.string.lanweb_os_macos
    ClientOs.IOS -> R.string.lanweb_os_ios
    ClientOs.ANDROID -> R.string.lanweb_os_android
    ClientOs.LINUX -> R.string.lanweb_os_linux
    ClientOs.OTHER -> R.string.lanweb_os_other
}

private fun guideStepsRes(os: ClientOs): Int = when (os) {
    ClientOs.WINDOWS -> R.string.lanweb_steps_windows
    ClientOs.MACOS -> R.string.lanweb_steps_macos
    ClientOs.IOS -> R.string.lanweb_steps_ios
    ClientOs.ANDROID -> R.string.lanweb_steps_android
    ClientOs.LINUX -> R.string.lanweb_steps_linux
    ClientOs.OTHER -> R.string.lanweb_steps_other
}

private fun guideIcon(os: ClientOs): ImageVector = when (os) {
    ClientOs.WINDOWS -> Icons.Default.DesktopWindows
    ClientOs.MACOS -> Icons.Default.Laptop
    ClientOs.IOS -> Icons.Default.PhoneIphone
    ClientOs.ANDROID -> Icons.Default.Android
    ClientOs.LINUX -> Icons.Default.Computer
    ClientOs.OTHER -> Icons.Default.Tv
}

/** The list of systems, as a section on the main screen. */
@Composable
fun GuideListSection(onOpen: (ClientOs) -> Unit) {
    SettingsSectionHeader(stringResource(R.string.lan_section_guides))
    SettingsGroup {
        GUIDE_ORDER.forEachIndexed { index, os ->
            if (index > 0) Separator()
            SettingsRow(
                title = stringResource(guideNameRes(os)),
                icon = guideIcon(os),
                tint = Ios.Blue,
                onClick = { onOpen(os) },
            )
        }
    }
    SettingsFooter(stringResource(R.string.lan_guides_footer))
}

/**
 * One system's page: the numbered steps, then the values they refer to.
 *
 * Steps first and values second, which is the opposite of the served page. There the values are
 * what you copy and the steps are context; here nothing is copyable onto the other machine anyway,
 * so the useful thing is the sequence and the numbers are the reference you check against.
 *
 * [ClientOs.OTHER] covers TVs and consoles, which have no field for an automatic configuration URL
 * at all -- so that page shows the host and the port instead of the PAC address. Showing a PAC URL
 * to someone holding a PlayStation controller is worse than showing nothing.
 */
@Composable
fun LanGuideScreen(os: ClientOs, status: LanStatus, backLabel: String, onBack: () -> Unit) {
    val usesPac = os != ClientOs.OTHER
    val steps = stringResource(guideStepsRes(os)).split('\n').filter { it.isNotBlank() }

    IosScreen(title = stringResource(guideNameRes(os)), onBack = onBack, backLabel = backLabel) {
        Spacer(Modifier.height(14.dp))
        SettingsGroup {
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                steps.forEachIndexed { index, step ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .background(Ios.Blue.copy(alpha = 0.22f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${index + 1}",
                                color = Ios.Blue,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(Modifier.width(13.dp))
                        Text(
                            step,
                            color = Ios.Label,
                            fontSize = 14.5.sp,
                            lineHeight = 21.sp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        SettingsSectionHeader(stringResource(R.string.lan_section_address))
        SettingsGroup {
            if (usesPac) {
                GuideValue(stringResource(R.string.lan_field_pac), status.pacUrl.orEmpty())
            } else {
                GuideValue(stringResource(R.string.lanweb_host_label), status.address.orEmpty())
                Separator()
                GuideValue(stringResource(R.string.lanweb_port_label), status.sharePort.toString())
            }
        }
        SettingsFooter(stringResource(R.string.lanweb_note_names))
        Spacer(Modifier.height(28.dp))
    }
}

/** A read-only value. Monospaced and left-to-right, like every other address in this feature. */
@Composable
private fun GuideValue(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp)) {
        Text(label, color = Ios.SecondaryLabel, fontSize = 12.sp)
        Spacer(Modifier.height(3.dp))
        Text(value, color = Ios.Label, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
    }
}
