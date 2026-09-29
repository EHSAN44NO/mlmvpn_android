package com.mlmvpn.scanner.engines.gozargah

import android.content.Context
import android.util.Log
import com.mlmvpn.scanner.engines.cloud.CfWorkers
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.store.StoreFiles
import com.mlmvpn.scanner.store.StoreNet
import com.mlmvpn.scanner.store.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.SecureRandom

/**
 * «پنل گذرگاه» (github.com/panelgozargah/gozargah, MIT) on the user's own Cloudflare account.
 *
 * A multi-user VLESS/Trojan panel whose only binding is a D1 database, `GZ_DB`; it creates its own
 * tables (`ensureSchema`) and migrates forward itself. Everything else is set through its panel API
 * at `/{panelPath}/api/<action>` behind a session cookie (read from its source, 2026-09-27):
 * `login {password}`, `settings {newPassword, panelPath, subPath}`, `users` (GET lists users with
 * their `subToken`; POST `{name}` makes one). A fresh deploy answers at the shipped defaults --
 * panel `gozargah`, password `admin`, subscriptions under `sub` -- so the first thing the app does
 * is replace all three with random values it keeps. Subscriptions are `/{subPath}/{token}?app=v2ray`
 * (a base64 link list). The developer publishes the bundled `gozargah-worker.js` with each release.
 */
object GozargahPanel {

    private const val TAG = "GozargahPanel"
    const val REPO = "panelgozargah/gozargah"
    const val RELEASE_ASSET = "gozargah-worker.js"
    const val ASSET = "gozargah_worker.js"
    private const val PREFS = "gozargah_panel"
    /** The user the arena and «دریافت کانفیگ» use, so the operator's own users are never touched. */
    const val ARENA_USER = "mlmvpn-arena"

    private const val DEFAULT_PANEL = "gozargah"
    private const val DEFAULT_PASSWORD = "admin"

    data class Install(val script: String, val url: String, val panelPath: String, val subPath: String, val password: String, val d1Id: String)

    fun looksLikeGozargah(code: String) = code.contains("GZ_DB") && code.contains("gozargah")

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun install(context: Context, accountId: String): Install? = runCatching {
        val o = JSONObject(prefs(context).getString("i_$accountId", null) ?: return null)
        Install(o.getString("script"), o.getString("url"), o.getString("panel"), o.getString("sub"), o.getString("pw"), o.optString("d1"))
    }.getOrNull()

    private fun save(context: Context, accountId: String, i: Install) {
        prefs(context).edit().putString("i_$accountId", JSONObject().put("script", i.script).put("url", i.url).put("panel", i.panelPath)
            .put("sub", i.subPath).put("pw", i.password).put("d1", i.d1Id).toString()).apply()
    }

    fun code(context: Context): String =
        runCatching { StoreFiles.readText(context, ASSET) }.getOrNull() ?: run {
            val rel = StoreNet.latestRelease(context, REPO)
            val asset = rel.assets.firstOrNull { it.name == RELEASE_ASSET }
                ?: error(tr("انتشار گذرگاه فایل ورکر ندارد.", "Gozargah's release has no worker file."))
            StoreNet.getText(context, asset.url, 8 * 1024 * 1024)
        }

    private fun random(n: Int): String {
        val abc = "abcdefghijklmnopqrstuvwxyz0123456789"
        val r = SecureRandom()
        return "g" + (1 until n).map { abc[r.nextInt(abc.length)] }.joinToString("")
    }

    private fun client(context: Context): OkHttpClient = com.mlmvpn.scanner.update.UpdateNet.client(context, 15, 30)
    private val JSON = "application/json".toMediaType()

    /** `POST /{panel}/api/login`; the session cookie (name=value) or an error. */
    private fun login(context: Context, url: String, panel: String, password: String): String {
        val req = Request.Builder().url("$url/$panel/api/login").post(JSONObject().put("password", password).toString().toRequestBody(JSON)).build()
        client(context).newCall(req).execute().use { r ->
            if (!r.isSuccessful) error(tr("ورود به پنل گذرگاه نشد", "Could not sign in to Gozargah") + " (HTTP ${r.code})")
            return r.headers("Set-Cookie").firstOrNull()?.substringBefore(';')
                ?: error(tr("پنل گذرگاه کوکی نشست نداد.", "Gozargah returned no session cookie."))
        }
    }

    private fun call(context: Context, url: String, cookie: String, method: String, body: JSONObject? = null): JSONObject {
        val b = Request.Builder().url(url).header("Cookie", cookie)
        if (method == "GET") b.get() else b.method(method, (body ?: JSONObject()).toString().toRequestBody(JSON))
        client(context).newCall(b.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val o = runCatching { JSONObject(text) }.getOrNull()
            if (!r.isSuccessful) error(o?.optString("error")?.ifBlank { null } ?: "HTTP ${r.code}")
            return o ?: JSONObject()
        }
    }

    /** Put Gozargah on [account] (or new code on the one there), then take it off its defaults. */
    suspend fun deploy(context: Context, account: CloudAccount, onStep: (String) -> Unit): Result<Install> = withContext(Dispatchers.IO) {
        runCatching {
            onStep(tr("بررسی زیردامنه…", "Checking the subdomain…"))
            val sub = CfWorkers.subdomain(account)
            val prev = install(context, account.accountId)
            val script = prev?.script ?: (com.mlmvpn.scanner.utils.AntiDpi.generateSafeWorkerName() + "-gzg")
            val url = prev?.url ?: "https://$script.$sub.workers.dev"

            onStep(tr("دریافت کد از گیت‌هاب سازنده…", "Fetching the code from the developer's GitHub…"))
            val code = code(context)
            if (!looksLikeGozargah(code)) error(tr("کد دریافتی پنل گذرگاه نیست.", "The code received is not Gozargah."))

            val d1 = prev?.d1Id?.ifBlank { null } ?: run {
                onStep(tr("ساخت دیتابیس D1…", "Creating the D1 database…"))
                CfWorkers.d1(account, "$script-db")
            }
            onStep(tr("بارگذاری ورکر…", "Uploading the Worker…"))
            CfWorkers.upload(account, script, code, bindings = listOf(CfWorkers.d1Binding("GZ_DB", d1)), compatibilityDate = "2025-01-15")
            onStep(tr("فعال کردن آدرس…", "Enabling the address…"))
            CfWorkers.enableWorkersDev(account, script)

            if (prev != null) return@runCatching prev.copy(d1Id = d1).also { save(context, account.accountId, it) }

            // First run: the panel is on its public defaults until this replaces them.
            onStep(tr("امن کردن پنل…", "Securing the panel…"))
            val panel = random(12)
            val subPath = random(10)
            val password = random(20)
            var cookie: String? = null
            for (attempt in 0 until 6) {     // a new workers.dev name answers after a few seconds
                cookie = runCatching { login(context, url, DEFAULT_PANEL, DEFAULT_PASSWORD) }.getOrNull()
                if (cookie != null) break
                delay(3000)
            }
            cookie ?: error(tr("پنل گذرگاه پس از نصب جواب نداد.", "Gozargah did not answer after the install."))
            call(context, "$url/$DEFAULT_PANEL/api/settings", cookie, "POST",
                JSONObject().put("newPassword", password).put("panelPath", panel).put("subPath", subPath))
            val inst = Install(script, url, panel, subPath, password, d1)
            save(context, account.accountId, inst)
            inst
        }.onFailure { Log.w(TAG, "deploy", it) }
    }

    /** The arena user's links: made once (never one of the operator's users), then its subscription. */
    suspend fun configs(context: Context, inst: Install): List<String> = withContext(Dispatchers.IO) {
        // Right after an install the new name may not answer yet: on the phone the first try
        // failed and the next one worked.
        var last: Exception? = null
        repeat(4) { attempt ->
            try {
                return@withContext configsOnce(context, inst)
            } catch (e: Exception) {
                last = e
                if (attempt < 3) delay(3000)
            }
        }
        throw last ?: IllegalStateException("no configs")
    }

    private fun configsOnce(context: Context, inst: Install): List<String> {
        val cookie = login(context, inst.url, inst.panelPath, inst.password)
        fun find(): JSONObject? {
            val arr = call(context, "${inst.url}/${inst.panelPath}/api/users", cookie, "GET").optJSONArray("users") ?: return null
            return (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { it.optString("name") == ARENA_USER }
        }
        val user = find() ?: run {
            call(context, "${inst.url}/${inst.panelPath}/api/users", cookie, "POST", JSONObject().put("name", ARENA_USER))
            find()
        } ?: error(tr("کاربر مسابقه در گذرگاه ساخته نشد.", "The arena user could not be made in Gozargah."))
        val token = user.optString("subToken").ifBlank { error("no subToken") }
        val req = Request.Builder().url("${inst.url}/${inst.subPath}/$token?app=v2ray").get().build()
        client(context).newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val body = r.body?.string().orEmpty().trim()
            val text = runCatching { String(android.util.Base64.decode(body, android.util.Base64.DEFAULT)) }.getOrDefault(body)
            return text.split("\n").map { it.trim() }.filter { it.startsWith("vless://") || it.startsWith("trojan://") }
                .ifEmpty { error(tr("اشتراک گذرگاه خالی بود.", "Gozargah's subscription was empty.")) }
        }
    }

    suspend fun remove(context: Context, account: CloudAccount): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val inst = install(context, account.accountId) ?: return@runCatching
            CfWorkers.remove(account, inst.script, d1Id = inst.d1Id)
            prefs(context).edit().remove("i_${account.accountId}").apply()
        }
    }
}
