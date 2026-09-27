package com.mlmvpn.scanner.ui.geph

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.text.HtmlCompat
import com.mlmvpn.core.geph.GephAccount
import com.mlmvpn.core.geph.GephEngine
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.ui.emergency.faDigits
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.tunnel.CountryLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** «خبرهای گف»: the network's own announcements, in the app's language when it has them. */
@Composable
fun GephNewsScreen(onBack: () -> Unit, backLabel: String) {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf<List<GephAccount.News>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val lang = if (com.mlmvpn.scanner.utils.AppLocaleManager.isFarsi()) "fa" else "en"
        val r = withContext(Dispatchers.IO) { GephAccount.news(ctx, lang) }
        r.onSuccess { items = it }.onFailure { error = it.message }
    }

    IosScreen(title = stringResource(R.string.geph_news_row), onBack = onBack, backLabel = backLabel) {
        Spacer(Modifier.height(12.dp))
        when {
            error != null -> SettingsFooter(stringResource(R.string.geph_error, error ?: ""))
            items == null -> SettingsFooter(stringResource(R.string.geph_news_loading))
            items!!.isEmpty() -> SettingsFooter(stringResource(R.string.geph_news_none))
        }
        items?.forEach { n ->
            SettingsSectionHeader(gephDate(n.dateUnix))
            SettingsGroup {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        (if (n.important) "❗ " else "") + n.title,
                        color = if (n.important) Ios.Orange else Ios.Label,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        HtmlCompat.fromHtml(n.contents, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim(),
                        color = Ios.SecondaryLabel,
                        fontSize = 13.sp,
                        lineHeight = 21.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

/**
 * «نشست‌ها و گزارش»: the sessions the engine holds to its exit, the traffic the network counted,
 * and the engine's own log -- the three things to look at when Geph is slow or will not connect.
 */
@Composable
fun GephSessionsScreen(onBack: () -> Unit, backLabel: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val live by GephEngine.live.collectAsState()
    var history by remember { mutableStateOf<List<Double>>(emptyList()) }
    var logs by remember { mutableStateOf(GephEngine.logs()) }
    var fullLog by remember { mutableStateOf<List<String>?>(null) }

    // Refresh while the page is open: the sessions change as the engine re-dials.
    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) {
                GephEngine.sample(withConn = true)
                history = GephEngine.history()
            }
            logs = GephEngine.logs()
            delay(3_000)
        }
    }

    IosScreen(title = stringResource(R.string.geph_sessions_row), onBack = onBack, backLabel = backLabel) {
        Spacer(Modifier.height(12.dp))

        SettingsSectionHeader(stringResource(R.string.geph_sessions_section))
        SettingsGroup {
            if (live.sessions.isEmpty()) {
                SettingsRow(
                    title = stringResource(if (live.running) R.string.geph_no_sessions_yet else R.string.geph_not_running),
                    showChevron = false,
                )
            }
            live.sessions.forEachIndexed { i, s ->
                if (i > 0) Separator()
                SettingsRow(
                    title = CountryLabel.withFlag(s.country) + (s.city.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                    icon = Icons.Default.Hub,
                    tint = Ios.Teal,
                    value = s.protocol,
                    subtitle = s.bridge?.let { stringResource(R.string.geph_via_bridge, it) }
                        ?: stringResource(R.string.geph_direct_session),
                    showChevron = false,
                )
            }
        }
        SettingsFooter(stringResource(R.string.geph_sessions_footer))

        if (live.running) {
            SettingsSectionHeader(stringResource(R.string.geph_traffic_section))
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.geph_received),
                    value = faDigits(humanBytes(live.rx)),
                    showChevron = false,
                )
                Separator()
                SettingsRow(
                    title = stringResource(R.string.geph_sent),
                    value = faDigits(humanBytes(live.tx)),
                    showChevron = false,
                )
                if (history.isNotEmpty()) {
                    Separator()
                    TrafficBars(history)
                }
            }
            SettingsFooter(stringResource(R.string.geph_traffic_footer))
        }

        SettingsSectionHeader(stringResource(R.string.geph_log_section))
        SettingsGroup {
            val shown = (fullLog ?: logs).takeLast(200)
            Text(
                shown.joinToString("\n").ifBlank { "—" },
                color = Ios.SecondaryLabel,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
            Separator()
            SettingsActionRow(
                label = stringResource(R.string.geph_full_log),
                icon = Icons.Default.Article,
                tint = Ios.Indigo,
                enabled = live.running,
                onClick = {
                    scope.launch {
                        fullLog = withContext(Dispatchers.IO) {
                            runCatching { GephEngine.mainControl()?.recentLogs() }.getOrNull()
                        }
                    }
                },
            )
            Separator()
            SettingsActionRow(
                label = stringResource(R.string.geph_share_log),
                icon = Icons.Default.Share,
                tint = Ios.Blue,
                onClick = {
                    val text = (fullLog ?: GephEngine.logs()).joinToString("\n")
                    ctx.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                            null,
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            )
        }
        SettingsFooter(stringResource(R.string.geph_log_footer))
        Spacer(Modifier.height(28.dp))
    }
}

/** The last ten minutes of traffic, one bar per second, as the engine counted it. */
@Composable
private fun TrafficBars(series: List<Double>) {
    val green = Ios.Green
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        val bars = series.takeLast(120)
        if (bars.isEmpty()) return@Canvas
        val peak = bars.max().coerceAtLeast(1.0)
        val w = size.width / bars.size
        bars.forEachIndexed { i, v ->
            val h = (v / peak * size.height).toFloat().coerceAtLeast(1f)
            drawRect(
                color = green.copy(alpha = 0.85f),
                topLeft = Offset(i * w, size.height - h),
                size = Size((w - 1f).coerceAtLeast(1f), h),
            )
        }
    }
}

private fun humanBytes(n: Long): String = when {
    n >= 1L shl 30 -> String.format(java.util.Locale.US, "%.2f GB", n / 1073741824.0)
    n >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", n / 1048576.0)
    n >= 1L shl 10 -> String.format(java.util.Locale.US, "%.0f KB", n / 1024.0)
    else -> "$n B"
}
