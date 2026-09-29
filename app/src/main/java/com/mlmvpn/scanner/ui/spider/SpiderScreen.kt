package com.mlmvpn.scanner.ui.spider

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.engines.spider.SpiderPanel
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosAlert
import com.mlmvpn.scanner.ui.settings.IosAlertAction
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.settings.SettingsTextRow
import kotlinx.coroutines.launch

/**
 * «پنل اسپایدر» for one Cloudflare account: the install, what its exits are doing, the users, and
 * the exit pool -- only what SpiderPanel's Worker does that the app's other panels do not. A page,
 * not a modal; its two editors (new user, new exit) are pages of it too.
 *
 * @param onAddConfigs hands the users' links to the Cloud tab, which files them as a group the
 *   way every other panel's configs are filed.
 */
@Composable
fun SpiderScreen(account: CloudAccount, onBack: () -> Unit, onAddConfigs: (List<Pair<String, String>>) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var inst by remember { mutableStateOf(SpiderPanel.install(context, account.accountId)) }
    var users by remember { mutableStateOf<List<SpiderPanel.User>>(emptyList()) }
    var status by remember { mutableStateOf<SpiderPanel.Status?>(null) }
    var exits by remember { mutableStateOf(SpiderPanel.exits(context, account.accountId)) }
    var step by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<SpiderPanel.User?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    var probing by remember { mutableStateOf(false) }

    suspend fun reload(probe: Boolean = false) {
        val i = inst ?: return
        error = null
        runCatching { users = SpiderPanel.users(context, i) }.onFailure { error = it.message }
        runCatching { status = SpiderPanel.status(context, i, probe) }.onFailure { if (error == null) error = it.message }
    }
    LaunchedEffect(inst) {
        // A workers.dev name answers a few seconds after the deploy that made it; the first
        // status on the phone came back empty. Ask again a few times rather than show dashes.
        for (attempt in 0 until 5) {
            reload()
            if (status != null || inst == null) break
            kotlinx.coroutines.delay(3000)
        }
    }

    BackHandler(enabled = page.isNotEmpty()) { page = "" }
    when (page) {
        "user" -> { NewUserPage(onBack = { page = "" }) { remark, gb, days, ips ->
            val i = inst ?: return@NewUserPage
            scope.launch {
                runCatching { SpiderPanel.addUser(context, i, remark, gb, days, ips) }
                    .onSuccess { page = ""; reload() }
                    .onFailure { toast(context, it.message) }
            }
        }; return }
        "exit" -> { NewExitPage(onBack = { page = "" }) { proxy, cc ->
            val list = exits + SpiderPanel.Exit(proxy, cc)
            val i = inst ?: return@NewExitPage
            scope.launch {
                runCatching { SpiderPanel.pushExits(context, i, list) }
                    .onSuccess { exits = list; SpiderPanel.saveExits(context, account.accountId, list); page = ""; reload(probe = true) }
                    .onFailure { toast(context, it.message) }
            }
        }; return }
    }

    fun deploy() {
        scope.launch {
            step = tr("آماده‌سازی…", "Preparing…")
            SpiderPanel.deploy(context, account) { s -> step = s }
                .onSuccess { inst = it; toast(context, tr("پنل اسپایدر روی حساب نصب شد.", "Spider was installed on the account.")) }
                .onFailure { error = it.message }
            step = null
        }
    }

    IosScreen(title = tr("پنل اسپایدر", "Spider panel"), onBack = onBack, onRefresh = { reload() }) {
        val i = inst
        if (i == null) {
            SettingsSectionHeader(tr("نصب", "Install"))
            SettingsGroup {
                SettingsActionRow(tr("نصب روی کلادفلر", "Install on Cloudflare"), Icons.Default.CloudUpload, Ios.Blue,
                    busy = step != null, onClick = { deploy() })
            }
            step?.let { SettingsFooter(it) }
            error?.let { SettingsFooter(it) }
            SettingsFooter(tr(
                "ورکر خود پنل اسپایدر، مستقیم از گیت‌هاب سازنده، روی حساب ${account.email} نصب می‌شود و این برنامه پنل آن است. چیزی که بقیهٔ پنل‌ها ندارند: هر کاربر محدودیت IP هم‌زمان دارد، و خروجی از بین چند پراکسی شما مسابقه‌ای انتخاب می‌شود — سریع‌ترین برای کشور و دیتاسنتر کاربر، با بررسی سلامت و چسبیدن به مسیر سالم.",
                "SpiderPanel's own Worker, straight from the developer's GitHub, is installed on ${account.email} with this app as its panel. What the other panels lack: a concurrent-IP limit per user, and an exit raced among several of your proxies — the fastest for the user's country and data centre, health-checked and kept sticky."))
            Spacer(Modifier.height(40.dp))
            return@IosScreen
        }

        // ── what the exits are doing ────────────────────────────────────────────────────────
        SettingsSectionHeader(tr("وضعیت", "Status"))
        SettingsGroup {
            val s = status
            SettingsRow(tr("کاربران فعال", "Active users"), icon = Icons.Default.Person, tint = Ios.Green,
                value = s?.let { "${it.online} / ${it.users}" } ?: "—", showChevron = false)
            Separator()
            SettingsRow(tr("مصرف کل", "Total usage"), icon = Icons.Default.Speed, tint = Ios.Teal,
                value = s?.let { bytes(it.traffic) } ?: "—", showChevron = false)
            Separator()
            SettingsRow(tr("خروجی‌های سالم", "Healthy exits"), icon = Icons.Default.Hub, tint = if ((s?.healthy ?: 0) > 0) Ios.Green else Ios.Orange,
                value = when {
                    exits.isEmpty() -> tr("مستقیم", "Direct")
                    s == null -> "—"
                    else -> "${s.healthy} / ${s.tracked.coerceAtLeast(exits.size)}"
                },
                subtitle = s?.edge?.ifBlank { null }?.let { tr("از دید دیتاسنتر ", "Seen from ") + it },
                showChevron = false)
            Separator()
            SettingsActionRow(tr("سنجش خروجی‌ها", "Check the exits"), Icons.Default.Radar, Ios.Blue, busy = probing, enabled = exits.isNotEmpty(), onClick = {
                scope.launch { probing = true; reload(probe = true); probing = false }
            })
        }
        error?.let { SettingsFooter(it) }

        // ── the exit pool ───────────────────────────────────────────────────────────────────
        SettingsSectionHeader(tr("خروجی‌ها", "Exits"))
        SettingsGroup {
            exits.forEachIndexed { idx, x ->
                if (idx > 0) Separator()
                val m = status?.routes?.firstOrNull { it.key.contains("|" + host(x.proxy) + "|") }
                SettingsRow(
                    title = (if (x.country.isNotBlank()) com.mlmvpn.scanner.ui.tunnel.CountryLabel.withFlag(x.country) + " · " else "") + host(x.proxy),
                    icon = Icons.Default.Public,
                    tint = when { m == null -> Ios.Gray; m.cooling -> Ios.Red; else -> Ios.Green },
                    subtitle = m?.let {
                        if (it.cooling) tr("فعلاً کنار گذاشته شده", "Benched for now")
                        else tr("${it.latencyMs} میلی‌ثانیه، لرزش ${it.jitterMs}", "${it.latencyMs} ms, jitter ${it.jitterMs}")
                    },
                    onClick = {
                        val list = exits.filterIndexed { k, _ -> k != idx }
                        scope.launch {
                            runCatching { SpiderPanel.pushExits(context, i, list) }
                                .onSuccess { exits = list; SpiderPanel.saveExits(context, account.accountId, list); reload() }
                                .onFailure { toast(context, it.message) }
                        }
                    },
                )
            }
            if (exits.isNotEmpty()) Separator()
            SettingsActionRow(tr("افزودن خروجی", "Add an exit"), Icons.Default.Add, Ios.Green, onClick = { page = "exit" })
        }
        SettingsFooter(tr(
            "پراکسی‌های SOCKS5 یا HTTP خودتان (مثلاً سروری که با دستور صفحهٔ لوکیشن‌های کانفیگ استدیو روی VPS ساخته‌اید). ورکر برای هر اتصال دو تا را هم‌زمان امتحان می‌کند، سریع‌ترین برای کشور و دیتاسنتر کاربر را نگه می‌دارد و خراب‌ها را موقتاً کنار می‌گذارد. بدون خروجی، اتصال مستقیم از کلادفلر می‌رود و سایت‌هایی که خودشان پشت کلادفلرند باز نمی‌شوند: ورکر نمی‌تواند به نشانی‌های خود کلادفلر وصل شود (روی گوشی سنجیده شد؛ بقیهٔ سایت‌ها با ۸٫۶ مگابیت باز شدند). برای حذف روی هر خروجی بزنید.",
            "Your own SOCKS5 or HTTP proxies (for example a server made with the command on Config Studio's Locations page). For each connection the Worker tries two at once, keeps the fastest for the user's country and data centre, and benches broken ones for a while. With no exit, traffic leaves Cloudflare directly, and sites that are themselves behind Cloudflare do not open: a Worker cannot connect to Cloudflare's own addresses (measured on a phone; other sites opened at 8.6 Mbit/s). Tap an exit to remove it."))

        // ── users ───────────────────────────────────────────────────────────────────────────
        SettingsSectionHeader(tr("کاربران", "Users"))
        SettingsGroup {
            users.forEachIndexed { idx, u ->
                if (idx > 0) Separator()
                SettingsRow(
                    title = u.remark, icon = Icons.Default.Person,
                    tint = if (u.expired || u.overQuota) Ios.Red else Ios.Blue,
                    subtitle = listOfNotNull(
                        bytes(u.usedBytes) + (if (u.limitBytes > 0) " / " + bytes(u.limitBytes) else ""),
                        if (u.expire > 0) daysLeft(u.expire) else null,
                        if (u.ipLimit > 0) tr("${u.ipLimit} IP", "${u.ipLimit} IP") else null,
                    ).joinToString(" · "),
                    onClick = { menuFor = u },
                )
            }
            if (users.isNotEmpty()) Separator()
            SettingsActionRow(tr("کاربر تازه", "New user"), Icons.Default.Add, Ios.Green, onClick = { page = "user" })
            if (users.isNotEmpty()) {
                Separator()
                SettingsActionRow(tr("افزودن کانفیگ‌ها به برنامه", "Add the configs to the app"), Icons.Default.PlaylistAdd, Ios.Blue, onClick = {
                    onAddConfigs(users.filter { !it.expired && !it.overQuota }.map { it.remark to SpiderPanel.link(i, it) })
                })
            }
        }
        SettingsFooter(tr(
            "محدودیت IP یعنی چند نشانی هم‌زمان می‌توانند با این کاربر وصل باشند؛ نشانیِ بی‌کار بعد از ۱۵ دقیقه آزاد می‌شود. با تمام شدن حجم یا زمان، ورکر خودش اتصال را می‌بندد.",
            "The IP limit is how many addresses can use this user at once; an idle address is released after 15 minutes. When the quota or the time runs out, the Worker closes the connection itself."))

        // ── the install ─────────────────────────────────────────────────────────────────────
        SettingsSectionHeader(tr("نصب", "Install"))
        SettingsGroup {
            SettingsRow(tr("آدرس ورکر", "Worker address"), icon = Icons.Default.Link, tint = Ios.Indigo,
                subtitle = i.url.removePrefix("https://"), showChevron = false, onClick = { copy(context, i.url) })
            Separator()
            SettingsActionRow(tr("نصب دوباره با آخرین نسخهٔ سازنده", "Reinstall with the developer's latest"), Icons.Default.Autorenew, Ios.Blue,
                busy = step != null, onClick = { deploy() })
            Separator()
            SettingsActionRow(tr("حذف از کلادفلر", "Remove from Cloudflare"), Icons.Default.DeleteForever, Ios.Red, labelColor = Ios.Red,
                onClick = { confirmRemove = true })
        }
        step?.let { SettingsFooter(it) }
        SettingsFooter(tr("نسخهٔ تازهٔ ورکر را استور از گیت‌هاب سازنده می‌پرسد و کلید و آدرس همین پنل را به کد تازه منتقل می‌کند.",
            "The store asks the developer's GitHub for new versions of the Worker and carries this panel's key and address to the new code."))
        Spacer(Modifier.height(40.dp))
    }

    menuFor?.let { u ->
        val i = inst
        IosAlert(
            title = u.remark,
            message = listOfNotNull(
                bytes(u.usedBytes) + (if (u.limitBytes > 0) " / " + bytes(u.limitBytes) else ""),
                if (u.expire > 0) daysLeft(u.expire) else null,
            ).joinToString(" · "),
            actions = listOfNotNull(
                i?.let { IosAlertAction(tr("کپی لینک", "Copy link"), { menuFor = null; copy(context, SpiderPanel.link(it, u)) }) },
                i?.let { IosAlertAction(tr("صفر کردن مصرف", "Reset usage"), {
                    menuFor = null
                    scope.launch { runCatching { SpiderPanel.updateUser(context, it, u.copy(usedBytes = 0)) }.onSuccess { reload() }.onFailure { e -> toast(context, e.message) } }
                }) },
                i?.let { IosAlertAction(tr("حذف", "Delete"), {
                    menuFor = null
                    scope.launch { runCatching { SpiderPanel.deleteUser(context, it, u.uuid) }.onSuccess { reload() }.onFailure { e -> toast(context, e.message) } }
                }, destructive = true) },
                IosAlertAction(tr("انصراف", "Cancel"), { menuFor = null }, preferred = true),
            ),
            onDismiss = { menuFor = null },
        )
    }
    if (confirmRemove) IosAlert(
        title = tr("حذف پنل اسپایدر؟", "Remove the Spider panel?"),
        message = tr("ورکر و KV آن، با همهٔ کاربرانش، از حساب کلادفلر پاک می‌شوند.", "The Worker and its KV, with all its users, are deleted from the Cloudflare account."),
        actions = listOf(
            IosAlertAction(tr("انصراف", "Cancel"), { confirmRemove = false }),
            IosAlertAction(tr("حذف", "Remove"), {
                confirmRemove = false
                scope.launch {
                    SpiderPanel.remove(context, account).onSuccess { inst = null; users = emptyList(); status = null; exits = emptyList() }
                        .onFailure { toast(context, it.message) }
                }
            }, destructive = true),
        ),
        onDismiss = { confirmRemove = false },
    )
}

@Composable
private fun NewUserPage(onBack: () -> Unit, onSave: (String, Double, Int, Int) -> Unit) {
    var remark by remember { mutableStateOf("") }
    var gb by remember { mutableStateOf("") }
    var days by remember { mutableStateOf("30") }
    var ips by remember { mutableStateOf("2") }
    IosScreen(title = tr("کاربر تازه", "New user"), onBack = onBack) {
        SettingsGroup(modifier = Modifier.padding(top = 20.dp)) {
            SettingsTextRow(tr("نام", "Name"), remark, { remark = it }, placeholder = "user")
            Separator()
            SettingsTextRow(tr("حجم (گیگابایت)", "Quota (GB)"), gb, { gb = it }, placeholder = tr("بی‌حد", "Unlimited"), numeric = true)
            Separator()
            SettingsTextRow(tr("مدت (روز)", "Days"), days, { days = it }, placeholder = tr("بی‌حد", "Unlimited"), numeric = true)
            Separator()
            SettingsTextRow(tr("IP هم‌زمان", "Concurrent IPs"), ips, { ips = it }, placeholder = tr("بی‌حد", "Unlimited"), numeric = true)
        }
        SettingsFooter(tr("خالی یا صفر یعنی بی‌حد.", "Empty or zero means unlimited."))
        SettingsGroup(modifier = Modifier.padding(top = 16.dp)) {
            SettingsActionRow(tr("ساخت", "Create"), Icons.Default.Add, Ios.Blue, onClick = {
                onSave(remark.trim(), num(gb).toDouble(), num(days).toInt(), num(ips).toInt())
            })
        }
    }
}

@Composable
private fun NewExitPage(onBack: () -> Unit, onSave: (String, String) -> Unit) {
    var proxy by remember { mutableStateOf("") }
    var cc by remember { mutableStateOf("") }
    val ok = Regex("""^(socks5|https?)://.+:\d{1,5}$""", RegexOption.IGNORE_CASE).matches(proxy.trim())
    IosScreen(title = tr("خروجی تازه", "New exit"), onBack = onBack) {
        SettingsGroup(modifier = Modifier.padding(top = 20.dp)) {
            SettingsTextRow(tr("پراکسی", "Proxy"), proxy, { proxy = it }, placeholder = "socks5://user:pass@1.2.3.4:1080",
                error = if (proxy.isNotBlank() && !ok) tr("به شکل socks5://… یا http://… با درگاه", "socks5://… or http://… with a port") else null)
            Separator()
            SettingsTextRow(tr("کد کشور", "Country code"), cc, { cc = it.take(2).uppercase() }, placeholder = "DE")
        }
        SettingsFooter(tr(
            "HTTP باید CONNECT را بپذیرد. فهرست روزانهٔ «ProxyIP» به کار نمی‌آید: آن نشانی‌ها رلهٔ کلادفلرند و به CONNECT جواب نمی‌دهند (سنجیده شد).",
            "HTTP proxies must accept CONNECT. The daily «ProxyIP» lists do not work here: those addresses are Cloudflare relays and do not answer CONNECT (measured)."))
        SettingsGroup(modifier = Modifier.padding(top = 16.dp)) {
            SettingsActionRow(tr("افزودن", "Add"), Icons.Default.Add, Ios.Blue, enabled = ok, onClick = { onSave(proxy.trim(), cc.trim()) })
        }
    }
}

private fun num(s: String): Number = s.trim().replace('٫', '.').let { t ->
    val latin = t.map { c -> if (c in '۰'..'۹') '0' + (c - '۰') else if (c in '٠'..'٩') '0' + (c - '٠') else c }.joinToString("")
    latin.toDoubleOrNull() ?: 0.0
}

private fun host(proxy: String): String = proxy.substringAfter("://").substringAfterLast('@').substringBeforeLast(':')

private fun bytes(b: Long): String = when {
    b >= 1L shl 30 -> String.format(java.util.Locale.US, "%.2f GB", b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", b / (1L shl 20).toDouble())
    else -> "${b / 1024} KB"
}

private fun daysLeft(expire: Long): String {
    val left = expire - System.currentTimeMillis() / 1000
    return if (left <= 0) tr("منقضی", "Expired") else tr("${(left + 86_399) / 86_400} روز", "${(left + 86_399) / 86_400} days")
}

private fun copy(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("spider", text))
    toast(context, tr("کپی شد", "Copied"))
}

private fun toast(context: Context, text: String?) {
    if (!text.isNullOrBlank()) Toast.makeText(context, text, Toast.LENGTH_LONG).show()
}
