package com.mlmvpn.scanner.ui.tunnel

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.core.tunnel.ConnectionLog
import com.mlmvpn.scanner.ui.home.frostedGlass
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.theme.CardShape
import com.mlmvpn.scanner.ui.theme.ControlShape
import kotlinx.coroutines.delay
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

/**
 * What the tunnel actually did, in its own words.
 *
 * Polled rather than observed. The log is a ring buffer written from three threads at a few
 * hundred lines a second during a Psiphon connect; turning that into a flow would mean a
 * recomposition per line, and the difference between "live" and "refreshed every second" is
 * invisible to a reader while the difference in cost is not.
 *
 * The lines are deliberately unstyled and monospaced: this is the one screen where the exact
 * text matters, because it is what gets pasted into a bug report.
 */
@Composable
fun TunnelLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var lines by remember { mutableStateOf(ConnectionLog.snapshot()) }
    var confirmClear by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    com.mlmvpn.scanner.ui.LaunchedWhileVisible(Unit) {
        while (true) {
            val next = ConnectionLog.snapshot()
            if (next.size != lines.size) {
                lines = next
                // Follow the tail only while the reader is already there. Yanking the list down
                // under someone who has scrolled up to read an earlier failure is the fastest
                // way to make a log screen useless.
                if (listState.firstVisibleItemIndex >= (lines.size - 12).coerceAtLeast(0)) {
                    runCatching { listState.animateScrollToItem((next.size - 1).coerceAtLeast(0)) }
                }
            }
            delay(1000)
        }
    }

    IosScreen(title = S(R.string.connection_report_2), onBack = onBack, backLabel = S(R.string.back_3), scrollable = false) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LogAction(
                label = S(R.string.subscription),
                icon = Icons.Default.Share,
                tint = Ios.Blue,
                modifier = Modifier.weight(1f),
            ) {
                val text = ConnectionLog.snapshot().joinToString("\n")
                if (text.isNotBlank()) {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(share, S(R.string.connection_report_2)))
                }
            }
            LogAction(
                label = if (confirmClear) S(R.string.are_you_sure_2) else S(R.string.clear_3),
                icon = Icons.Default.DeleteOutline,
                tint = Ios.Red,
                modifier = Modifier.weight(1f),
            ) {
                if (confirmClear) {
                    ConnectionLog.clear()
                    lines = emptyList()
                    confirmClear = false
                } else {
                    confirmClear = true
                }
            }
        }

        if (confirmClear) {
            Text(
                S(R.string.this_is_the_only_record_left_of) +
                    S(R.string.means_throwing_away_the_very_thing_that),
                color = Ios.SecondaryLabel,
                fontSize = 13.sp,
                lineHeight = 21.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }

        if (lines.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().height(220.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    S(R.string.nothing_recorded_yet_nconnect_once_and_every),
                    color = Ios.SecondaryLabel,
                    fontSize = 14.sp,
                    lineHeight = 24.sp,
                    textAlign = TextAlign.Center,
                )
            }
            return@IosScreen
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .frostedGlass(CardShape),
            contentPadding = PaddingValues(12.dp),
        ) {
            items(lines) { line ->
                Text(
                    line,
                    color = Ios.Label,
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun LogAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .background(tint.copy(alpha = 0.14f), ControlShape)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
        Spacer(Modifier.size(6.dp))
        Text(label, color = tint, fontSize = 14.sp)
    }
}
