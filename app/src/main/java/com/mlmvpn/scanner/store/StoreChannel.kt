package com.mlmvpn.scanner.store

import android.content.Context
import android.os.Build
import com.mlmvpn.core.warp.TweetNaclFast
import com.mlmvpn.scanner.engines.game.booster.crowd.CrowdSnapshot
import org.json.JSONObject
import java.io.File

/**
 * The signed channel: new versions of OUR OWN builds without a new build of the app.
 *
 * The same envelope, the same key and the same maintainer tooling as the Windows store
 * (`store/trust.js`, `scripts/store-channel.js` in the Windows repo), published to this repo's
 * `store-channel` release instead. It carries, per item, the version we tested, where to get it,
 * and its SHA-256 — signed with a key whose private half never leaves the maintainer's machine, so
 * a compromised GitHub account still cannot make the app install anything.
 *
 * It can only move numbers, URLs and digests. How an item is installed, checked and rolled back
 * lives in code the channel cannot touch.
 *
 * Failing to fetch it is not an error: the developers' own releases still work, and the store
 * says when it last managed to look.
 */
object StoreChannel {

    private val URLS = listOf(
        "https://github.com/mlmvpn/mlmvpn_android/releases/download/store-channel/store-channel.json",
        "https://raw.githubusercontent.com/mlmvpn/mlmvpn_android/store-channel/store-channel.json",
    )

    /** Raw Ed25519 public keys, by key id — the same list as the Windows app's trust.js. */
    private val TRUSTED_KEYS = mapOf(
        "mlm-store-2026-1" to "n4IjFvvq0Ef+ynM99cCPwh3jUBsi08SRzt1CNXZHHww=",
    )

    private const val MAX_AGE_MS = 6 * 3600 * 1000L

    data class Manifest(val sequence: Long, val issuedAt: String, val minApp: String?, val items: JSONObject)

    @Volatile private var held: Manifest? = null
    @Volatile private var fetchedAt = 0L
    @Volatile var lastError: String = ""
        private set

    private fun cacheFile(context: Context) = File(context.filesDir, "store/channel.json")

    /** Open and verify an envelope. Throws a sentence naming which check failed. */
    fun open(text: String): Manifest {
        val env = runCatching { JSONObject(text) }.getOrNull()
            ?: throw StoreError(tr("فایل کانال JSON معتبر نیست.", "The channel file is not valid JSON."))
        if (env.optString("format") != "mlm-store-channel" || env.optString("alg") != "ed25519") {
            throw StoreError(tr("این فایل، فایل کانال استور نیست.", "This is not a store channel file."))
        }
        val kid = env.optString("kid")
        val key = TRUSTED_KEYS[kid]?.let { CrowdSnapshot.decodeBase64(it) }
            ?: throw StoreError(tr("کلید امضای «$kid» در این نسخه شناخته نمی‌شود.", "Signing key \"$kid\" is not known to this version."))
        val payload = CrowdSnapshot.decodeBase64(env.optString("payload"))
        val sig = CrowdSnapshot.decodeBase64(env.optString("sig"))
        if (payload == null || payload.isEmpty() || sig == null || sig.size != 64) {
            throw StoreError(tr("امضای فایل کانال ناقص است.", "The channel signature is incomplete."))
        }
        val ok = runCatching { TweetNaclFast.Signature(key, null).detached_verify(payload, sig) }.getOrDefault(false)
        if (!ok) {
            throw StoreError(tr("امضای فایل کانال درست نیست — استفاده نشد.", "The channel signature is wrong — it was not used."))
        }
        val m = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull()
            ?: throw StoreError(tr("محتوای امضاشده JSON معتبر نیست.", "The signed content is not valid JSON."))
        if (m.optInt("schema") != 1 || !m.has("sequence") || m.optJSONObject("items") == null) {
            throw StoreError(tr("ساختار فایل کانال با این نسخهٔ برنامه نمی‌خواند.", "The channel format does not match this version."))
        }
        return Manifest(m.optLong("sequence"), m.optString("issuedAt"), m.optString("minApp").ifBlank { null }, m.getJSONObject("items"))
    }

    private fun loadCached(context: Context): Manifest? {
        held?.let { return it }
        val f = cacheFile(context)
        if (!f.exists()) return null
        return runCatching { open(f.readText()) }
            .onSuccess { held = it; fetchedAt = f.lastModified() }
            .onFailure { lastError = it.message.orEmpty() }
            .getOrNull()
    }

    /** Fetch and verify. Refuses to go backwards: an older sequence is a replay, and is dropped. */
    fun refresh(context: Context, force: Boolean = false): Manifest? {
        val have = loadCached(context)
        if (!force && have != null && System.currentTimeMillis() - fetchedAt < MAX_AGE_MS) return have
        var raw: String? = null
        var err: Exception? = null
        for (url in URLS) {
            try { raw = StoreNet.getText(context, url, 2 * 1024 * 1024); break } catch (e: Exception) { err = e }
        }
        if (raw == null) {
            // No channel published yet is the normal state of a fresh repo, not a failure worth a
            // red line on every item.
            lastError = if (err?.message == "HTTP 404") "" else (err?.message ?: "")
            return have
        }
        val m = try { open(raw) } catch (e: Exception) { lastError = e.message.orEmpty(); return have }
        if (have != null && m.sequence < have.sequence) {
            lastError = tr("نسخهٔ قدیمی‌تری از فایل کانال آمد و پذیرفته نشد.", "An older channel file arrived and was refused.")
            return have
        }
        if (m.minApp != null && StoreVersions.compare(appVersion(context), m.minApp) == -1) {
            lastError = tr("این کانال به نسخهٔ ${m.minApp} برنامه یا بالاتر نیاز دارد.", "This channel needs app version ${m.minApp} or later.")
            return have
        }
        held = m
        fetchedAt = System.currentTimeMillis()
        lastError = ""
        runCatching { cacheFile(context).apply { parentFile?.mkdirs() }.writeText(raw) }
        return m
    }

    /** The channel's offer for one item on this device, or null. */
    fun candidate(context: Context, id: String): StoreCandidate? {
        val m = loadCached(context) ?: return null
        val item = m.items.optJSONObject(id) ?: return null
        val version = item.optString("version").ifBlank { return null }
        // A per-item floor: an entry may need an app that knows how to drive it.
        val minApp = item.optString("minApp")
        if (minApp.isNotBlank() && StoreVersions.compare(appVersion(context), minApp) == -1) return null
        // …and a ceiling: an entry that only makes sense for apps shipping something older.
        val below = item.optString("forAppsBelow")
        if (below.isNotBlank() && StoreVersions.compare(appVersion(context), below) != -1) return null
        val abis = Build.SUPPORTED_ABIS.toList()
        val arr = item.optJSONArray("artifacts") ?: return null
        val all = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        // Per-ABI artifacts: take this device's most preferred ABI that has one.
        val hasAbi = all.any { it.has("abi") }
        val chosen = if (!hasAbi) all else {
            val abi = abis.firstOrNull { a -> all.any { it.optString("abi") == a } } ?: return null
            all.filter { !it.has("abi") || it.optString("abi") == abi }
        }
        val artifacts = chosen.map { a ->
            val urls = a.optJSONArray("urls")
            val extract = a.optJSONObject("extract")
            StoreArtifact(
                name = a.optString("name"),
                urls = (0 until (urls?.length() ?: 0)).map { urls!!.getString(it) },
                sha256 = a.optString("sha256").lowercase().takeIf { it.length == 64 },
                size = a.optLong("size"),
                format = a.optString("format", "raw"),
                extract = extract?.keys()?.asSequence()?.associateWith { extract.getString(it) } ?: emptyMap(),
            )
        }
        // Every channel artifact is pinned by digest. One without is not something to install.
        if (artifacts.isEmpty() || artifacts.any { it.sha256 == null || it.urls.isEmpty() }) return null
        return StoreCandidate(version, item.optString("notes"), item.optString("released"), "channel", artifacts)
    }

    fun status(context: Context): Pair<Long?, Long> = (loadCached(context)?.sequence) to fetchedAt

    fun appVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"
}
