package com.mlmvpn.scanner.ui.cloud

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.data.CloudGroup
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.PanelBadge
import com.mlmvpn.scanner.ui.faCount
import com.mlmvpn.scanner.ui.panelMonogram
import com.mlmvpn.scanner.ui.panelTint
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader

/**
 * «کانفیگ‌های دریافتی»: every group of configs the panels handed over, from every account, in one
 * module. It lived at the foot of each account card, which a folded card now hides; here each row
 * carries its panel's mark and, with several accounts, the account's colour from the usage chart.
 * A row opens the group's page (send to V2Ray, combine, delete), exactly as before.
 */
@Composable
fun CloudReceivedModule(
    accounts: List<CloudAccount>,
    groups: List<CloudGroup>,
    onOpenGroup: (String) -> Unit,
) {
    if (groups.isEmpty()) return
    val limit = 8
    var showAll by remember { mutableStateOf(false) }
    val shown = if (showAll) groups else groups.take(limit)
    SettingsSectionHeader(tr("کانفیگ‌های دریافتی", "Received configs") + " · " + faCount(groups.size))
    SettingsGroup {
        shown.forEachIndexed { i, g ->
            if (i > 0) Separator()
            val engine = g.nodes.firstOrNull()?.engineType ?: "BPB"
            val tint = panelTint(engine)
            val accountIndex = accounts.indexOfFirst { it.id == g.accountId }
            Row(
                Modifier.fillMaxWidth().clickable { onOpenGroup(g.id) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))
                        .background(Brush.verticalGradient(listOf(tint, tint.copy(alpha = 0.78f)))),
                    contentAlignment = Alignment.Center,
                ) { Text(panelMonogram(engine), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(g.title, color = Ios.Label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PanelBadge(engine)
                        if (accounts.size > 1 && accountIndex >= 0) {
                            Spacer(Modifier.width(6.dp))
                            Box(Modifier.size(7.dp).clip(CircleShape).background(accountColor(accountIndex)))
                        }
                        Spacer(Modifier.width(6.dp))
                        // "2026-09-02 20:49" is Latin content; in an RTL line the clock would walk in front of the date.
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(g.date, color = Ios.SecondaryLabel, fontSize = 11.sp)
                        }
                    }
                }
                Text(faCount(g.nodes.size), color = Ios.SecondaryLabel, fontSize = 14.sp)
                Spacer(Modifier.width(6.dp))
                val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                Icon(Icons.Default.ChevronRight, null, tint = Ios.Chevron, modifier = Modifier.size(17.dp).scale(if (rtl) -1f else 1f, 1f))
            }
        }
        if (groups.size > limit) {
            Separator()
            Text(
                if (showAll) tr("کمتر", "Show less") else tr("همه (${faCount(groups.size)})", "All (${groups.size})"),
                color = Ios.Blue, fontSize = 15.sp,
                modifier = Modifier.fillMaxWidth().clickable { showAll = !showAll }.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
    SettingsFooter(tr(
        "هر گروه را باز کنید تا به V2Ray بفرستید، با آی‌پی تمیز ترکیب کنید یا حذفش کنید. عدد کنار هر ردیف، تعداد کانفیگ‌های آن گروه است.",
        "Open a group to send it to V2Ray, combine it with a clean IP, or delete it. The number on each row is how many configs it holds."))
}
