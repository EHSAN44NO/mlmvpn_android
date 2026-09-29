package com.mlmvpn.scanner.ui.spider

import android.content.Context
import android.widget.Toast
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.CloudGroup
import com.mlmvpn.scanner.data.GroupManager
import com.mlmvpn.scanner.engines.spider.SpiderPanel
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.settings.Ios
import kotlinx.coroutines.launch

/**
 * The Spider panel's configs, filed the way every other panel's are: one Cloud group per fetch,
 * then offered to the combine assistant (clean IP + these configs), which is what «دریافت کانفیگ»
 * does for BPB, EDG, Nahan and MLM. Used by the Cloud tab's row and by the panel page.
 */
object SpiderConfigs {

    /** The live users that can still connect, as (remark, link). */
    suspend fun links(context: Context, account: CloudAccount): List<Pair<String, String>> {
        val inst = SpiderPanel.install(context, account.accountId)
            ?: error(tr("پنل اسپایدر روی این حساب نصب نیست.", "The Spider panel is not installed on this account."))
        return SpiderPanel.users(context, inst).filter { !it.expired && !it.overQuota }.map { it.remark to SpiderPanel.link(inst, it) }
    }

    /** File [links] as a Spider group of [account] and make the combine offer. */
    fun file(context: Context, groupManager: GroupManager, account: CloudAccount, links: List<Pair<String, String>>): CloudGroup? =
        com.mlmvpn.scanner.ui.cloud.PanelConfigs.file(context, groupManager, account, "SPD", "Spider", links)
}

/**
 * The Spider panel's rows in its Cloud-tab card, the same two the other panels have: get the
 * configs (then the combine offer), and open the panel. Before an install there is one row, to
 * the page that installs it.
 */
@Composable
fun SpiderCloudRows(
    account: CloudAccount,
    groupManager: GroupManager,
    onOpen: () -> Unit,
    onGroupsUpdated: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fetching by remember { mutableStateOf(false) }
    val installed = SpiderPanel.install(context, account.accountId) != null
    Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.035f))) {
        if (installed) {
            SpiderRow(
                icon = Icons.Default.Download,
                label = if (fetching) tr("در حال دریافت کانفیگ‌ها…", "Getting the configs…") else tr("دریافت کانفیگ", "Get configs"),
                busy = fetching,
            ) {
                if (fetching) return@SpiderRow
                fetching = true
                scope.launch {
                    val result = runCatching { SpiderConfigs.links(context, account) }
                    fetching = false
                    result.onSuccess { links ->
                        if (links.isEmpty()) {
                            toast(context, tr("کاربر فعالی ندارد. اول در پنل یک کاربر بسازید.", "No active user. Create one in the panel first."))
                        } else {
                            SpiderConfigs.file(context, groupManager, account, links)
                            onGroupsUpdated()
                            toast(context, context.getString(R.string.cloud_admin_configs_received, links.size))
                        }
                    }.onFailure { toast(context, it.message) }
                }
            }
            Box(Modifier.fillMaxWidth().padding(start = 52.dp).height(0.5.dp).background(Ios.Separator))
        }
        SpiderRow(
            icon = Icons.Default.Settings,
            label = if (installed) tr("مدیریت پنل: کاربران و خروجی‌ها", "Manage: users and exits") else tr("نصب پنل اسپایدر", "Install the Spider panel"),
            busy = false,
            onClick = onOpen,
        )
    }
}

/** One action row, drawn like the other panels' rows in the same card. */
@Composable
private fun SpiderRow(icon: ImageVector, label: String, busy: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !busy, onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Ios.Label, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = Ios.Label, fontSize = 15.sp, modifier = Modifier.weight(1f))
        if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = Ios.Blue, strokeWidth = 2.dp)
    }
}

private fun toast(context: Context, text: String?) {
    if (!text.isNullOrBlank()) Toast.makeText(context, text, Toast.LENGTH_LONG).show()
}
