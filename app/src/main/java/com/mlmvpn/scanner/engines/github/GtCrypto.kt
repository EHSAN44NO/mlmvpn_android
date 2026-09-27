package com.mlmvpn.scanner.engines.github

import android.util.Base64
import com.mlmvpn.core.warp.TweetNaclFast
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * GitHub Tunnel's cryptography, byte for byte what the Windows app and the runner do.
 *
 * Two jobs:
 *
 *  - **The sealed hand-off.** The runner generates every credential of a session itself and commits
 *    them to `sessions/<id>.json` in the user's private repository, SEALED to a one-time X25519 key
 *    whose public half this app passed as a workflow_dispatch input:
 *
 *        shared = X25519(ephemeral_runner_priv, client_pub)
 *        key    = HKDF-SHA256(shared, salt = epk || client_pub, info = INFO, 32)
 *        ct     = AES-256-GCM(key, iv, JSON, aad = INFO + '|' + sessionId)
 *
 *    (runner/seal.mjs seals; github-tunnel/gt-session-crypto.js opens on Windows; [open] here.)
 *    A key in the dispatch inputs belongs to exactly one run — a repository secret would be read
 *    when a job STARTS, so back-to-back sessions on one account could swap them.
 *
 *  - **The passes.** The Worker on the user's Cloudflare lets a request through to a session's
 *    tunnel only with a pass signed by this installation's secret:
 *    `base64url(JSON{h,s,e}) + "." + base64url(HMAC-SHA256(secret, thatPayloadText))`.
 *
 * X25519 comes from TweetNaCl's `crypto_scalarmult` (already in the app for WARP): Android has no
 * X25519 KeyAgreement below API 31, and the raw scalar multiplication is exactly RFC 7748's.
 */
object GtCrypto {
    const val INFO = "mlmvpn-gt-session-v2"
    const val ALG = "x25519-hkdf-sha256-aes256gcm"

    private val rng = SecureRandom()
    private const val B64U = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

    fun b64u(bytes: ByteArray): String = Base64.encodeToString(bytes, B64U)
    fun unb64u(text: String): ByteArray = Base64.decode(text, B64U)

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }
    fun randomHex(n: Int): String = randomBytes(n).joinToString("") { "%02x".format(it) }

    /** A one-time key pair: `publicKey` goes into the dispatch inputs, `privateKey` stays here. */
    data class KeyPair(val publicKey: String, val privateKey: String)

    fun newKeyPair(): KeyPair {
        val priv = randomBytes(32)
        return KeyPair(publicKey = b64u(publicOf(priv)), privateKey = b64u(priv))
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** RFC 5869 with SHA-256 — what Node's crypto.hkdfSync('sha256', …) computes. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            t = hmac(prk, t + info + byteArrayOf(counter.toByte()))
            out.write(t)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    /**
     * Open what the runner sealed. Throws on anything that is not exactly what we expect — a
     * tampered, truncated, replayed-for-another-session or wrongly-keyed file never yields data.
     */
    fun open(privateKeyB64u: String, sealed: JSONObject, sessionId: String): JSONObject {
        require(sealed.optString("alg") == ALG && sealed.optInt("v") == 2) { "sealed session: unknown format" }
        require(sealed.optString("sid") == sessionId) { "sealed session: written for another session" }
        val text = openRaw(unb64u(privateKeyB64u), unb64u(sealed.getString("epk")), unb64u(sealed.getString("iv")),
            unb64u(sealed.getString("ct")), sessionId)
        return JSONObject(text)
    }

    /** The cryptography of [open], on raw bytes — plain JVM, so a unit test can check it against the runner. */
    fun openRaw(priv: ByteArray, epk: ByteArray, iv: ByteArray, blob: ByteArray, sessionId: String): String {
        require(epk.size == 32 && priv.size == 32) { "sealed session: bad key length" }
        require(iv.size == 12 && blob.size >= 17) { "sealed session: bad ciphertext" }
        val clientPub = ByteArray(32).also { TweetNaclFast.crypto_scalarmult_base(it, priv) }
        val shared = ByteArray(32)
        TweetNaclFast.crypto_scalarmult(shared, priv, epk)
        val key = hkdf(shared, epk + clientPub, INFO.toByteArray(), 32)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD("$INFO|$sessionId".toByteArray())
        // Java's GCM wants the tag at the end of the input — exactly how the runner lays it out.
        return String(cipher.doFinal(blob), Charsets.UTF_8)
    }

    /** The public half of a raw private key (what goes into the dispatch inputs). */
    fun publicOf(priv: ByteArray): ByteArray = ByteArray(32).also { TweetNaclFast.crypto_scalarmult_base(it, priv) }

    /** The signature over a pass payload's TEXT — the Worker recomputes it from those exact bytes. */
    fun passSig(secretHex: String, payloadText: String): ByteArray =
        hmac(secretHex.toByteArray(Charsets.UTF_8), payloadText.toByteArray(Charsets.UTF_8))

    private fun signed(secretHex: String, payload: JSONObject): String {
        val text = b64u(payload.toString().toByteArray(Charsets.UTF_8))
        return "$text.${b64u(passSig(secretHex, text))}"
    }

    /** A pass for the Worker's /p/ route to one quick tunnel (`label`.trycloudflare.com). */
    fun signPass(secretHex: String, label: String, sessionId: String, expiresAt: Long): String =
        signed(secretHex, JSONObject().put("h", label).put("s", sessionId).put("e", expiresAt))

    /** A pass to one of the stable tunnels (the Worker's VPC bindings), `a`, `b` or `c`. */
    fun signSlotPass(secretHex: String, slot: String, sessionId: String, expiresAt: Long): String {
        require(slot in listOf("a", "b", "c")) { "bad slot" }
        return signed(secretHex, JSONObject().put("k", slot).put("s", sessionId).put("e", expiresAt))
    }
}
