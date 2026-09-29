package com.mlmvpn.scanner.ui.cloud

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
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInBrowser
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
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.models.VpnNode
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosAlert
import com.mlmvpn.scanner.ui.settings.IosAlertAction
import kotlinx.coroutines.launch

/**
 * Filing a third-party panel's configs, the way every panel's are filed: one Cloud group per
 * fetch, then the combine offer (clean IP + these configs). Spider, Netra and Gozargah use it.
 */
object PanelConfigs {
    fun file(
        context: Context, groupManager: GroupManager, account: CloudAccount,
        engine: String, label: String, links: List<Pair<String, String>>,
    ): CloudGroup? {
        if (links.isEmpty()) return null
        val stamp = System.currentTimeMillis()
        val nodes = links.mapIndexed { index, (name, uri) ->
            VpnNode(id = "${engine.lowercase()}_${account.id}_${stamp}_$index", name = "$label · $name",
                uri = uri, type = if (uri.startsWith("trojan://")) "trojan" else "vless", engineType = engine)
        }
        val group = CloudGroup(
            id = "${engine.lowercase()}_${account.id}_$stamp",
            accountId = account.id,
            date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()),
            title = com.mlmvpn.scanner.utils.NamingHelper.generateMythName(),
            nodes = nodes,
        )
        groupManager.cloudGroups.add(0, group)
        groupManager.saveCloudGroups()
        com.mlmvpn.scanner.ui.CombineCoach.offer(context, group.id, nodes.first().uri)
        return group
    }

    /** A link's own name (the part after '#'), for the group row. */
    fun nameOf(uri: String): String = runCatching { java.net.URLDecoder.decode(uri.substringAfterLast('#', ""), "UTF-8") }
        .getOrDefault("").ifBlank { uri.substringAfter("://").substringAfter('@').substringBefore('?') }
}

/**
 * A third-party panel's rows in its Cloud-tab card: install (with the deploy's own steps), then
 * «دریافت کانفیگ» (+ the combine offer), an optional page or browser action, and removal.
 */
@Composable
fun PanelRows(
    account: CloudAccount,
    groupManager: GroupManager,
    engine: String,
    label: String,
    installed: Boolean,
    install: suspend (onStep: (String) -> Unit) -> Result<*>,
    links: suspend () -> List<Pair<String, String>>,
    remove: suspend () -> Result<*>,
    onGroupsUpdated: () -> Unit,
    onChanged: () -> Unit,
    extra: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<String?>(null) }
    var fetching by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.035f))) {
        if (!installed) {
            ActionRow(Icons.Default.CloudUpload, step ?: tr("نصب روی کلادفلر", "Install on Cloudflare"), busy = step != null) {
                if (step != null) return@ActionRow
                step = tr("آماده‌سازی…", "Preparing…")
                scope.launch {
                    install { s -> step = s }
                        .onSuccess { toast(context, tr("$label روی حساب نصب شد.", "$label was installed on the account.")); onChanged() }
                        .onFailure { toast(context, it.message) }
                    step = null
                }
            }
        } else {
        ActionRow(Icons.Default.Download, if (fetching) tr("در حال دریافت کانفیگ‌ها…", "Getting the configs…") else tr("دریافت کانفیگ", "Get configs"), busy = fetching) {
            if (fetching) return@ActionRow
            fetching = true
            scope.launch {
                val result = runCatching { links() }
                fetching = false
                result.onSuccess { list ->
                    if (PanelConfigs.file(context, groupManager, account, engine, label, list) != null) {
                        onGroupsUpdated()
                        toast(context, context.getString(R.string.cloud_admin_configs_received, list.size))
                    } else toast(context, tr("کانفیگی نیامد.", "No configs came back."))
                }.onFailure {
                    android.util.Log.w("PanelRows", "$engine configs", it)
                    toast(context, it.message)
                }
            }
        }
        extra?.let { Divider(); it() }
        Divider()
        ActionRow(Icons.Default.DeleteForever, tr("حذف از کلادفلر", "Remove from Cloudflare"), busy = false, tint = Ios.Red) { confirmRemove = true }
        }
    }
    if (confirmRemove) IosAlert(
        title = tr("حذف پنل $label؟", "Remove the $label panel?"),
        message = tr("ورکر و فضای داده‌اش از حساب کلادفلر پاک می‌شوند و کانفیگ‌هایی که از آن گرفته‌اید کار نمی‌کنند.",
            "The Worker and its storage are deleted from the Cloudflare account, and configs taken from it stop working."),
        actions = listOf(
            IosAlertAction(tr("انصراف", "Cancel"), { confirmRemove = false }),
            IosAlertAction(tr("حذف", "Remove"), {
                confirmRemove = false
                scope.launch { remove().onSuccess { onChanged() }.onFailure { toast(context, it.message) } }
            }, destructive = true),
        ),
        onDismiss = { confirmRemove = false },
    )
}

/** «باز کردن پنل» for a panel with its own web page. */
@Composable
fun OpenPanelRow(url: String, note: String? = null, beforeOpen: (() -> Unit)? = null) {
    val context = LocalContext.current
    ActionRow(Icons.Default.OpenInBrowser, tr("باز کردن پنل وب", "Open the web panel"), busy = false) {
        beforeOpen?.invoke()
        note?.let { toast(context, it) }
        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().padding(start = 52.dp).height(0.5.dp).background(Ios.Separator))
}

@Composable
fun ActionRow(icon: ImageVector, label: String, busy: Boolean, tint: Color = Ios.Label, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !busy, onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = tint, fontSize = 15.sp, modifier = Modifier.weight(1f))
        if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = Ios.Blue, strokeWidth = 2.dp)
    }
}

private fun toast(context: Context, text: String?) {
    if (!text.isNullOrBlank()) Toast.makeText(context, text, Toast.LENGTH_LONG).show()
}
