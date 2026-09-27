package com.mlmvpn.scanner.store

import android.content.Context
import com.mlmvpn.scanner.update.UpdateNet
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** Dotted-numeric comparison that also orders the date-stamped versions edgetunnel uses. */
object StoreVersions {

    private fun parts(v: String): List<Long> =
        Regex("""\d+""").findAll(v).map { it.value.toLongOrNull() ?: 0L }.toList()

    /** -1, 0, 1 — or null when either side carries no number at all. */
    fun compare(a: String?, b: String?): Int? {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return null
        val x = parts(a)
        val y = parts(b)
        if (x.isEmpty() || y.isEmpty()) return null
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0L }
            val q = y.getOrElse(i) { 0L }
            if (p != q) return if (p > q) 1 else -1
        }
        return 0
    }

    fun newer(a: String?, b: String?): Boolean = compare(a, b) == 1

    fun major(v: String?): Long? = v?.let { parts(it).firstOrNull() }
}

/**
 * Every network call the store makes.
 *
 * Through [UpdateNet], for the reason written there: GitHub's two hostnames are not treated alike
 * by a filtering network, and the app excludes itself from its own tunnel, so the only way to use
 * the tunnel from here is the local proxy — which UpdateNet already points at when one is up.
 */
object StoreNet {

    private const val API = "https://api.github.com"

    data class Asset(val name: String, val url: String, val size: Long, val sha256: String?)
    data class Release(
        val tag: String,
        val name: String,
        val body: String,
        val publishedAt: String,
        val url: String,
        val assets: List<Asset>,
    )
    data class Commit(val sha: String, val date: String, val message: String, val url: String)

    fun getText(context: Context, url: String, maxBytes: Int = 4 * 1024 * 1024): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "mlmvpn-android-store")
            .header("Accept", "application/vnd.github+json, */*")
            .build()
        UpdateNet.client(context, 10, 25).newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw StoreError(
                    if (resp.code == 403 || resp.code == 429) {
                        tr("گیت‌هاب فعلاً درخواست بیشتری نمی‌پذیرد (محدودیت ساعتی). کمی بعد دوباره بزنید.",
                            "GitHub is rate-limiting requests right now. Try again a little later.")
                    } else "HTTP ${resp.code}"
                )
            }
            val body = resp.body ?: throw StoreError("empty response")
            val bytes = body.byteStream().use { it.readNBytesCompat(maxBytes + 1) }
            if (bytes.size > maxBytes) throw StoreError("response too large")
            return String(bytes, Charsets.UTF_8)
        }
    }

    fun latestRelease(context: Context, repo: String): Release {
        val j = JSONObject(getText(context, "$API/repos/$repo/releases/latest"))
        val assets = j.optJSONArray("assets") ?: JSONArray()
        return Release(
            tag = j.optString("tag_name"),
            name = j.optString("name"),
            body = j.optString("body"),
            publishedAt = j.optString("published_at").take(10),
            url = j.optString("html_url"),
            assets = (0 until assets.length()).map { i ->
                val a = assets.getJSONObject(i)
                Asset(
                    name = a.optString("name"),
                    url = a.optString("browser_download_url"),
                    size = a.optLong("size"),
                    // GitHub computes this at upload for every asset published since mid-2025.
                    sha256 = a.optString("digest").takeIf { it.startsWith("sha256:") }
                        ?.removePrefix("sha256:")?.lowercase(),
                )
            },
        )
    }

    fun latestCommit(context: Context, repo: String, branch: String, path: String): Commit {
        val arr = JSONArray(getText(context, "$API/repos/$repo/commits?sha=$branch&path=$path&per_page=1"))
        if (arr.length() == 0) throw StoreError("no commits for $path")
        val c = arr.getJSONObject(0)
        val commit = c.optJSONObject("commit")
        return Commit(
            sha = c.optString("sha"),
            date = commit?.optJSONObject("committer")?.optString("date").orEmpty().take(10),
            message = commit?.optString("message").orEmpty().lineSequence().firstOrNull().orEmpty(),
            url = c.optString("html_url"),
        )
    }

    /**
     * Download to [dest], checking size and digest before the file takes its real name.
     *
     * Every URL is tried in turn — the channel lists a mirror beside the original for hosts that
     * are blocked from Iran, and the digest is what decides either way.
     */
    fun download(
        context: Context,
        artifact: StoreArtifact,
        dest: File,
        onProgress: (Long, Long) -> Unit,
        cancelled: () -> Boolean = { false },
    ): File {
        dest.parentFile?.mkdirs()
        var last: Exception? = null
        for (url in artifact.urls) {
            val part = File(dest.path + ".part")
            try {
                part.delete()
                val req = Request.Builder().url(url).header("User-Agent", "mlmvpn-android-store").build()
                UpdateNet.client(context, 15, 60).newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw StoreError("HTTP ${resp.code}")
                    val body = resp.body ?: throw StoreError("empty response")
                    val total = body.contentLength().takeIf { it > 0 } ?: artifact.size
                    val md = MessageDigest.getInstance("SHA-256")
                    var got = 0L
                    body.byteStream().use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                if (cancelled()) throw StoreError(tr("لغو شد", "Cancelled"))
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                md.update(buf, 0, n)
                                got += n
                                onProgress(got, total)
                            }
                        }
                    }
                    if (artifact.size > 0 && got != artifact.size) {
                        throw StoreError(tr("دانلود ناقص ماند", "The download was incomplete") + " ($got/${artifact.size})")
                    }
                    val digest = md.digest().joinToString("") { "%02x".format(it) }
                    if (artifact.sha256 != null && digest != artifact.sha256) {
                        throw StoreError(
                            tr("هش فایل دانلودشده با نسخهٔ منتشرشده نمی‌خواند — فایل استفاده نشد.",
                                "The downloaded file does not match the published digest — it was not used.")
                        )
                    }
                }
                dest.delete()
                if (!part.renameTo(dest)) throw StoreError("rename failed")
                return dest
            } catch (e: Exception) {
                part.delete()
                last = e
                if (cancelled()) break
            }
        }
        throw last ?: StoreError("no URL")
    }

    /** Pull the wanted entries out of a downloaded archive. Returns installed-name -> file. */
    fun extract(archive: File, format: String, wanted: Map<String, String>, outDir: File): Map<String, File> {
        outDir.mkdirs()
        val found = HashMap<String, File>()
        fun take(entryName: String, input: InputStream) {
            val clean = entryName.removePrefix("./").trimStart('/')
            val target = wanted[clean] ?: return
            val f = File(outDir, target)
            f.outputStream().use { input.copyTo(it) }
            found[target] = f
        }
        when (format) {
            "tar.gz", "tgz" -> GZIPInputStream(archive.inputStream().buffered()).use { readTar(it, ::take) }
            "zip" -> ZipInputStream(archive.inputStream().buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (!e.isDirectory) take(e.name, z)
                }
            }
            else -> throw StoreError("unknown archive format $format")
        }
        val missing = wanted.values.filter { it !in found }
        if (missing.isNotEmpty()) throw StoreError(tr("این فایل‌ها در بسته نبودند: ", "Not in the archive: ") + missing.joinToString())
        return found
    }

    /** A minimal ustar reader: regular files only, GNU long names, nothing else needed. */
    private fun readTar(input: InputStream, onFile: (String, InputStream) -> Unit) {
        val header = ByteArray(512)
        var longName: String? = null
        while (true) {
            if (!readFully(input, header)) return
            if (header.all { it == 0.toByte() }) return
            fun str(off: Int, len: Int) = String(header, off, len, Charsets.UTF_8).substringBefore('\u0000')
            val size = str(124, 12).trim().ifEmpty { "0" }.toLong(8)
            val type = header[156].toInt().toChar()
            val prefix = str(345, 155)
            val name = longName ?: (if (prefix.isNotEmpty()) "$prefix/${str(0, 100)}" else str(0, 100))
            longName = null
            val padded = (size + 511) / 512 * 512
            val bounded = BoundedStream(input, size)
            when (type) {
                'L' -> longName = String(bounded.readBytes(), Charsets.UTF_8).trimEnd('\u0000')
                '0', '\u0000' -> onFile(name, bounded)
                else -> Unit
            }
            bounded.skipRest()
            skipFully(input, padded - size)
        }
    }

    private class BoundedStream(private val src: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0) return -1
            val r = src.read()
            if (r >= 0) left--
            return r
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = src.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
        fun skipRest() { skipFully(src, left); left = 0 }
        override fun close() {}
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        val buf = ByteArray(8192)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) return
            left -= n
        }
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val n = read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}

/** An error whose message is already a sentence for the user. */
class StoreError(message: String) : Exception(message)

/** Persian or English, following the language the user picked. */
fun tr(fa: String, en: String): String =
    if (com.mlmvpn.scanner.utils.AppLocaleManager.isFarsi()) fa else en
