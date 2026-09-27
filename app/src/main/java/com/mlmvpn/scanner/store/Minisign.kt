package com.mlmvpn.scanner.store

import com.mlmvpn.core.warp.TweetNaclFast

/**
 * Verifies a minisign signature -- the scheme Geph signs its release manifest with, and the one
 * its own client checks (geph5-client updates.rs, `minisign_verify`).
 *
 * Both the file signature and the "global" signature over the trusted comment are checked, so
 * neither the file nor its comment can be swapped. The prehashed form ("ED") signs BLAKE2b-512 of
 * the file; the legacy form ("Ed") signs the file itself.
 */
object Minisign {

    fun verify(data: ByteArray, signatureFile: String, publicKeyB64: String): Boolean = runCatching {
        val pk = decode(publicKeyB64)
        if (pk.size != 42 || pk[0] != 'E'.code.toByte() || pk[1] != 'd'.code.toByte()) return false
        val keyId = pk.copyOfRange(2, 10)
        val key = pk.copyOfRange(10, 42)

        val lines = signatureFile.lines()
        if (lines.size < 4) return false
        val sig = decode(lines[1].trim())
        if (sig.size != 74) return false
        val alg = String(sig, 0, 2, Charsets.ISO_8859_1)
        if (!sig.copyOfRange(2, 10).contentEquals(keyId)) return false
        val signature = sig.copyOfRange(10, 74)
        val message = when (alg) {
            "ED" -> Blake2b.digest512(data)
            "Ed" -> data
            else -> return false
        }
        val verifier = TweetNaclFast.Signature(key, null)
        if (!verifier.detached_verify(message, signature)) return false

        val trusted = lines[2]
        val prefix = "trusted comment: "
        if (!trusted.startsWith(prefix)) return false
        val comment = trusted.substring(prefix.length).toByteArray(Charsets.UTF_8)
        val global = decode(lines[3].trim())
        if (global.size != 64) return false
        verifier.detached_verify(signature + comment, global)
    }.getOrDefault(false)

    /**
     * Standard base64, by hand: `android.util.Base64` is a stub in JVM tests and `java.util.Base64`
     * only exists from Android 8, below this app's minimum.
     */
    internal fun decode(b64: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val clean = b64.filter { !it.isWhitespace() }.trimEnd('=')
        val out = java.io.ByteArrayOutputStream(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = alphabet.indexOf(c)
            require(v >= 0) { "not base64" }
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xff)
            }
        }
        return out.toByteArray()
    }
}

/** BLAKE2b (RFC 7693), unkeyed, 512-bit output -- all minisign's prehash needs. */
object Blake2b {

    private val IV = longArrayOf(
        0x6a09e667f3bcc908L,
        0xbb67ae8584caa73buL.toLong(),
        0x3c6ef372fe94f82bL,
        0xa54ff53a5f1d36f1uL.toLong(),
        0x510e527fade682d1L,
        0x9b05688c2b3e6c1fuL.toLong(),
        0x1f83d9abfb41bd6bL,
        0x5be0cd19137e2179L,
    )

    private val SIGMA = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
        intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
        intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
        intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
        intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
        intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
        intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
        intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
        intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
        intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0),
    )

    fun digest512(input: ByteArray): ByteArray {
        val h = IV.copyOf()
        h[0] = h[0] xor 0x01010040L          // depth 1, fanout 1, no key, 64-byte digest
        val m = LongArray(16)
        val block = ByteArray(128)
        var counter = 0L
        var offset = 0
        // Every block but the last is compressed as it is; the last is flagged final, even when
        // the input is empty or an exact multiple of 128.
        while (input.size - offset > 128) {
            counter += 128
            load(input, offset, m)
            compress(h, m, counter, false)
            offset += 128
        }
        val rest = input.size - offset
        block.fill(0)
        System.arraycopy(input, offset, block, 0, rest)
        counter += rest
        load(block, 0, m)
        compress(h, m, counter, true)

        val out = ByteArray(64)
        for (i in 0 until 8) for (b in 0 until 8) out[i * 8 + b] = (h[i] ushr (8 * b)).toByte()
        return out
    }

    private fun load(src: ByteArray, off: Int, m: LongArray) {
        for (i in 0 until 16) {
            var v = 0L
            for (b in 7 downTo 0) v = (v shl 8) or (src[off + i * 8 + b].toLong() and 0xff)
            m[i] = v
        }
    }

    private fun compress(h: LongArray, m: LongArray, counter: Long, last: Boolean) {
        val v = LongArray(16)
        for (i in 0 until 8) {
            v[i] = h[i]
            v[i + 8] = IV[i]
        }
        v[12] = v[12] xor counter            // the high half of the 128-bit counter stays 0 here
        if (last) v[14] = v[14].inv()
        for (r in 0 until 12) {
            val s = SIGMA[r % 10]
            g(v, 0, 4, 8, 12, m[s[0]], m[s[1]])
            g(v, 1, 5, 9, 13, m[s[2]], m[s[3]])
            g(v, 2, 6, 10, 14, m[s[4]], m[s[5]])
            g(v, 3, 7, 11, 15, m[s[6]], m[s[7]])
            g(v, 0, 5, 10, 15, m[s[8]], m[s[9]])
            g(v, 1, 6, 11, 12, m[s[10]], m[s[11]])
            g(v, 2, 7, 8, 13, m[s[12]], m[s[13]])
            g(v, 3, 4, 9, 14, m[s[14]], m[s[15]])
        }
        for (i in 0 until 8) h[i] = h[i] xor v[i] xor v[i + 8]
    }

    private fun g(v: LongArray, a: Int, b: Int, c: Int, d: Int, x: Long, y: Long) {
        v[a] = v[a] + v[b] + x
        v[d] = java.lang.Long.rotateRight(v[d] xor v[a], 32)
        v[c] = v[c] + v[d]
        v[b] = java.lang.Long.rotateRight(v[b] xor v[c], 24)
        v[a] = v[a] + v[b] + y
        v[d] = java.lang.Long.rotateRight(v[d] xor v[a], 16)
        v[c] = v[c] + v[d]
        v[b] = java.lang.Long.rotateRight(v[b] xor v[c], 63)
    }
}

/** The one block of a developer's `metadata.yaml` the store needs: a track's version, file and digest. */
object SignedManifestYaml {

    data class Entry(val version: String, val sha256: String, val filename: String)

    /**
     * Reads `track:` and its indented `version`, `sha256` and `filename`. Deliberately narrow --
     * the file is only trusted after its signature verified, and this is all of it the store uses.
     */
    fun entry(yaml: String, track: String): Entry? {
        val lines = yaml.lines()
        val start = lines.indexOfFirst { it.trimEnd() == "$track:" }
        if (start < 0) return null
        val fields = HashMap<String, String>()
        for (line in lines.drop(start + 1)) {
            if (line.isBlank()) continue
            if (!line.first().isWhitespace()) break
            val m = Regex("""^\s+([a-z0-9_]+):\s*(\S+)\s*$""").find(line) ?: continue
            fields[m.groupValues[1]] = m.groupValues[2].trim('"', '\'')
        }
        val version = fields["version"] ?: return null
        val sha = fields["sha256"]?.lowercase()?.takeIf { it.length == 64 } ?: return null
        val file = fields["filename"] ?: return null
        return Entry(version, sha, file)
    }
}
