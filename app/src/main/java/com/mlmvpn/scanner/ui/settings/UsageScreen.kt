package com.mlmvpn.scanner.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.TrafficManager
import com.mlmvpn.scanner.ui.home.frostedGlass

/**
 * Settings > Total Usage, in the same grouped style as the rest of Settings.
 *
 * Three periods, each a card of three rows, plus the seven-day chart. Rows rather than the old
 * three-column block: a value belongs on the trailing edge of its own label, which is where the
 * eye already goes on every other screen in Settings.
 */
@Composable
fun UsageScreen(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val trafficManager = remember { TrafficManager(context) }

    var today by remember { mutableStateOf(trafficManager.getTodayTraffic()) }
    var weekly by remember { mutableStateOf(trafficManager.getWeeklyTraffic()) }
    var monthly by remember { mutableStateOf(trafficManager.getMonthlyTraffic()) }
    var last7Days by remember { mutableStateOf(trafficManager.getTrafficForDays(7).reversed()) }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(3000) // live update every 3s
            today = trafficManager.getTodayTraffic()
            weekly = trafficManager.getWeeklyTraffic()
            monthly = trafficManager.getMonthlyTraffic()
            last7Days = trafficManager.getTrafficForDays(7).reversed()
        }
    }

    IosScreen(title = stringResource(R.string.usage_title), onBack = onDismiss) {
        Spacer(Modifier.height(10.dp))

        UsagePeriod(
            header = stringResource(R.string.usage_today),
            rx = today.rxBytes,
            tx = today.txBytes,
        )
        UsagePeriod(
            header = stringResource(R.string.usage_last_7_days),
            rx = weekly.rxBytes,
            tx = weekly.txBytes,
        )
        UsagePeriod(
            header = stringResource(R.string.usage_last_30_days),
            rx = monthly.rxBytes,
            tx = monthly.txBytes,
        )

        SettingsSectionHeader(stringResource(R.string.usage_last_7_days))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .frostedGlass(RoundedCornerShape(22.dp))
                .padding(16.dp)
        ) {
            SevenDayChart(last7Days.map { it.rxBytes + it.txBytes })
        }

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun UsagePeriod(header: String, rx: Long, tx: Long) {
    SettingsSectionHeader(header)
    SettingsGroup {
        SettingsRow(
            title = stringResource(R.string.usage_download),
            icon = Icons.Default.Download,
            tint = Ios.Green,
            value = formatBytes(rx),
            showChevron = false,
        )
        Separator()
        SettingsRow(
            title = stringResource(R.string.usage_upload),
            icon = Icons.Default.Upload,
            tint = Ios.Blue,
            value = formatBytes(tx),
            showChevron = false,
        )
        Separator()
        SettingsRow(
            title = stringResource(R.string.usage_total),
            icon = Icons.Default.SwapVert,
            tint = Ios.Gray,
            value = formatBytes(rx + tx),
            showChevron = false,
        )
    }
}

@Composable
private fun SevenDayChart(totals: List<Long>) {
    val max = (totals.maxOrNull() ?: 1L).coerceAtLeast(1L)

    Row(
        modifier = Modifier.fillMaxWidth().height(130.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        totals.forEach { total ->
            val fraction = (total.toFloat() / max.toFloat()).coerceIn(0f, 1f)
            val animated by animateFloatAsState(
                targetValue = fraction,
                animationSpec = tween(700),
                label = "UsageBar",
            )
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    text = shortBytes(total),
                    color = Ios.SecondaryLabel,
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // A day with no traffic still gets a sliver, so the chart reads as seven
                        // days rather than as four days and a gap.
                        .height((6f + animated * 88f).dp)
                        .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(Ios.Blue, Ios.Blue.copy(alpha = 0.35f))
                            )
                        )
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L * 1024L) return "${bytes / 1024} KB"
    val mb = bytes / (1024f * 1024f)
    if (mb < 1024f) return String.format("%.1f MB", mb)
    return String.format("%.2f GB", mb / 1024f)
}

private fun shortBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format("%.1fG", bytes / (1024f * 1024f * 1024f))
    bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)}M"
    else -> ""
}
