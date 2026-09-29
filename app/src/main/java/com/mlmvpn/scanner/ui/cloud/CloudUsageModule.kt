package com.mlmvpn.scanner.ui.cloud

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.engines.cloud.CfUsage
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.faGrouped
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import kotlinx.coroutines.launch

/** One colour per account, in the order the accounts are listed, the way Screen Time colours its categories. */
@Composable
fun accountColor(index: Int): Color = when (index % 6) {
    0 -> Ios.Blue
    1 -> Ios.Teal
    2 -> Ios.Indigo
    3 -> Ios.Orange
    4 -> Ios.Pink
    else -> Ios.Purple
}

private fun tehranDate(key: String): java.util.Date =
    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = CfUsage.TEHRAN }.parse(key)
        ?.let { java.util.Date(it.time + 12 * 3_600_000L) } ?: java.util.Date()   // noon: clear of any day boundary

/** Iran's week letters, Saturday first: ش ی د س چ پ ج (English S M T W T F S from Saturday). */
private fun shortDay(key: String): String {
    val cal = java.util.Calendar.getInstance(CfUsage.TEHRAN).apply { time = tehranDate(key) }
    val i = cal.get(java.util.Calendar.DAY_OF_WEEK) - 1 // 0 = Sunday
    return tr(listOf("ی", "د", "س", "چ", "پ", "ج", "ش")[i], listOf("S", "M", "T", "W", "T", "F", "S")[i])
}

/** «امروز · دوشنبه ۶ مهر» / «شنبه ۴ مهر»: the Persian calendar in Tehran time (Gregorian in English). */
private fun longDay(key: String, isToday: Boolean): String {
    val date = tehranDate(key)
    val farsi = com.mlmvpn.scanner.utils.AppLocaleManager.isFarsi()
    val f = if (farsi) android.icu.text.SimpleDateFormat("EEEE d MMMM", android.icu.util.ULocale("fa_IR@calendar=persian"))
        else android.icu.text.SimpleDateFormat("EEEE, MMMM d", android.icu.util.ULocale.ENGLISH)
    f.timeZone = android.icu.util.TimeZone.getTimeZone("Asia/Tehran")
    val text = f.format(date)
    return if (isToday) tr("امروز · ", "Today · ") + text else text
}

private fun grouped(v: Long) = faGrouped(v.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())

/**
 * «مصرف روزانه»: the Cloud tab's opening module. Worker requests across every account for the last
 * seven UTC days, stacked by account; the big number is the selected day's total, and each
 * account's row below says how much of its own free 100,000 that day took.
 */
@Composable
fun CloudUsageModule(accounts: List<CloudAccount>) {
    val snap by CfUsage.state.collectAsState()
    val loading by CfUsage.loading.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(accounts.map { it.id }) { runCatching { CfUsage.refresh(accounts) } }

    val keys = snap?.dayKeys ?: CfUsage.dayKeys()
    var selected by remember { mutableStateOf(-1) }
    val today = CfUsage.todayIndex()
    val sel = if (selected in 0..today) selected else today
    val perAccount = accounts.map { snap?.accounts?.get(it.id) }
    val totals = keys.indices.map { d -> perAccount.sumOf { it?.days?.getOrNull(d) ?: 0L } }
    val maxDay = (totals.maxOrNull() ?: 0L).coerceAtLeast(1L)
    // Over the days of this week that have happened, not the empty ones still to come.
    val average = totals.take(today + 1).let { if (it.isEmpty()) 0L else it.sum() / it.size }

    SettingsSectionHeader(tr("مصرف روزانه", "Daily usage"))
    SettingsGroup {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(longDay(keys[sel], sel == today), color = Ios.SecondaryLabel, fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(if (snap == null) "—" else grouped(totals[sel]), color = Ios.Label, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp))
                        Text(tr("درخواست", "requests"), color = Ios.SecondaryLabel, fontSize = 15.sp, modifier = Modifier.padding(bottom = 6.dp))
                    }
                }
                if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = Ios.SecondaryLabel, strokeWidth = 2.dp)
                else Icon(Icons.Default.Refresh, tr("تازه کردن", "Refresh"), tint = Ios.Blue,
                    modifier = Modifier.size(24.dp).clickable { scope.launch { runCatching { CfUsage.refresh(accounts, force = true) } } })
            }
            Spacer(Modifier.height(14.dp))
            // Bars: one column per day, each stacked by account, today's the fullest colour.
            Box(Modifier.fillMaxWidth().height(132.dp)) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                    keys.indices.forEach { d ->
                        val frac by animateFloatAsState((totals[d].toFloat() / maxDay).coerceIn(0f, 1f), tween(600), label = "bar$d")
                        val dim = d != sel
                        Column(
                            Modifier.weight(1f).fillMaxHeight()
                                .clickable(enabled = d <= today, interactionSource = remember { MutableInteractionSource() }, indication = null) { selected = d },
                            verticalArrangement = Arrangement.Bottom,
                        ) {
                            if (totals[d] == 0L || snap == null) {
                                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Ios.Gray.copy(alpha = 0.3f)))
                            } else {
                                Column(Modifier.fillMaxWidth().fillMaxHeight(frac.coerceAtLeast(0.02f)).clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp, bottomStart = 2.dp, bottomEnd = 2.dp))) {
                                    // Top of the stack is the last account, so the first sits on the baseline.
                                    perAccount.indices.reversed().forEach { a ->
                                        val v = perAccount[a]?.days?.getOrNull(d) ?: 0L
                                        if (v > 0) Box(Modifier.fillMaxWidth().weight(v.toFloat())
                                            .background(accountColor(a).copy(alpha = if (dim) 0.45f else 1f)))
                                    }
                                }
                            }
                        }
                    }
                }
                // The average, dashed, as Screen Time draws it.
                if (snap != null && average > 0) {
                    val avgColor = Ios.Green
                    Canvas(Modifier.fillMaxSize()) {
                        val y = size.height * (1f - (average.toFloat() / maxDay).coerceIn(0f, 1f))
                        drawLine(avgColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                keys.indices.forEach { d ->
                    Text(shortDay(keys[d]), color = when { d == sel -> Ios.Label; d > today -> Ios.SecondaryLabel.copy(alpha = 0.45f); else -> Ios.SecondaryLabel }, fontSize = 11.sp,
                        fontWeight = if (d == sel) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            if (snap != null && average > 0) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(14.dp).height(2.dp).background(Ios.Green))
                Spacer(Modifier.width(6.dp))
                Text(tr("میانگین روزانه ", "Daily average ") + grouped(average), color = Ios.SecondaryLabel, fontSize = 12.sp)
            }
        }
        // One row per account: its colour, its address, the selected day's figure against its own 100,000.
        accounts.forEachIndexed { i, acc ->
            Separator()
            val u = perAccount[i]
            val v = u?.days?.getOrNull(sel) ?: 0L
            val f = (v.toFloat() / CfUsage.FREE_DAILY).coerceIn(0f, 1f)
            val barColor = when { f >= 0.95f -> Ios.Red; f >= 0.8f -> Ios.Orange; else -> accountColor(i) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(accountColor(i)))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(acc.email.ifBlank { acc.name }, color = Ios.Label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Text(when {
                            u == null -> "—"
                            u.failed != null -> tr("آمار در دسترس نیست", "No statistics")
                            else -> grouped(v)
                        }, color = Ios.SecondaryLabel, fontSize = 14.sp)
                    }
                    if (u != null && u.failed == null) {
                        Spacer(Modifier.height(6.dp))
                        val w by animateFloatAsState(f, tween(600), label = "acc$i")
                        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Ios.Gray.copy(alpha = 0.22f))) {
                            Box(Modifier.fillMaxWidth(w).height(4.dp).clip(CircleShape).background(barColor))
                        }
                    }
                }
            }
        }
    }
    SettingsFooter(tr(
        "درخواست‌های همهٔ ورکرهای هر حساب، روزبه‌روز در هفتهٔ جاری (شنبه تا جمعه، به وقت تهران). هر حساب رایگان روزی ۱۰۰٬۰۰۰ درخواست دارد و کلادفلر آن را ساعت ۳:۳۰ بامداد به وقت تهران صفر می‌کند؛ نوار هر حساب سهم آن روز را نشان می‌دهد. روی هر روز بزنید تا عددهای همان روز را ببینید.",
        "Requests to all of each account's Workers, day by day this week (Saturday to Friday, Tehran time). Each free account gets 100,000 a day, reset by Cloudflare at 03:30 Tehran time; each account's bar shows that day's share. Tap a day to see its figures."))
}
