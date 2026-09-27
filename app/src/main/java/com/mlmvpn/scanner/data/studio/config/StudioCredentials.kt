package com.mlmvpn.scanner.data.studio.config

import com.mlmvpn.scanner.data.studio.domain.ProtocolType
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID

/**
 * The secrets a config authenticates with, and the one derived value the engine needs.
 *
 * **Both protocols put something different on the wire**, and that difference is the whole reason
 * this file exists:
 *
 *  * **VLESS** sends the UUID itself, so `users.uuid` is what the data plane matches and there is
 *    nothing to derive.
 *  * **Trojan** sends `hex(SHA-224(password))` and never the password. The worker cannot compute
 *    that — Workers' `crypto.subtle` implements SHA-1, SHA-256, SHA-384 and SHA-512 and **not**
 *    SHA-224 — so the hash is computed here, once, and stored on the config row as `auth_hash` for
 *    an indexed lookup with no crypto on the connection path.
 *
 * Doing it on this side is not a workaround for a missing API. It is the better place regardless:
 * hashing per connection would be CPU on the one path where cost is multiplied by every person
 * using the installation.
 */
object StudioCredentials {

    private val random = SecureRandom()

    /** The credential the person's client will present. */
    fun newCredential(shape: ConfigShape): String = newCredential(shape.protocol)

    fun newCredential(protocol: ProtocolType): String = when (protocol) {
        ProtocolType.VLESS, ProtocolType.VMESS -> UUID.randomUUID().toString()
        // 32 bytes of base16 rather than a passphrase: it travels in a URI, and anything that has
        // to be percent-encoded is a link somebody eventually breaks by copying it out of a chat.
        else -> hex(ByteArray(16).also { random.nextBytes(it) })
    }

    /**
     * What the engine must match this credential against, or null when the credential IS what is
     * sent.
     *
     * Null for VLESS is meaningful rather than missing: the partial unique index on `auth_hash`
     * exists so that every VLESS config, which has none, does not collide with every other one.
     */
    fun authHash(shape: ConfigShape, credential: String): String? = authHash(shape.protocol, credential)

    fun authHash(protocol: ProtocolType, credential: String): String? = when (protocol) {
        ProtocolType.TROJAN -> sha224(credential)
        else -> null
    }

    /**
     * The path this config is served on, or null for the root.
     *
     * A per-config path is what lets one person hold several configs that can be revoked
     * separately. The engine only accepts a path some enabled config claims — everything else still
     * gets the camouflage page — so this is registered rather than merely chosen.
     */
    fun newRouteKey(): String = "u/" + hex(ByteArray(6).also { random.nextBytes(it) })

    /**
     * The route key for a draft: what the operator typed, or a fresh one, or nothing.
     *
     * Nothing — the root — is a real answer and not an oversight. A person's FIRST config answers
     * on `/`, which is what a client with no path configured expects, and every one after it takes
     * a path of its own so it can be revoked without touching the first.
     */
    fun routeKeyFor(draft: ConfigDraft): String? {
        if (!draft.shape.supportsRouteKey || draft.useRootPath) return null
        val typed = draft.customPath.trim().trim('/')
        return if (typed.isNotEmpty()) typed else newRouteKey()
    }

    /** The credential for a draft, honouring a typed one and the shapes that cannot have one. */
    fun credentialFor(draft: ConfigDraft, userCredential: String?): String {
        // XHTTP authenticates against `users.uuid` and never reads the configs table, so anything
        // else here would produce a link that imports fine and cannot connect.
        if (!draft.shape.supportsOwnCredential) {
            return userCredential.orEmpty().ifEmpty { newCredential(draft.shape.protocol) }
        }
        return draft.customCredential.trim().ifEmpty { newCredential(draft.shape.protocol) }
    }

    private fun sha224(s: String): String =
        hex(MessageDigest.getInstance("SHA-224").digest(s.toByteArray(Charsets.UTF_8)))

    // `%02x` and not `%d`: java.util.Formatter localises decimal integers but explicitly does not
    // localise hexadecimal, so this is safe on a Persian device where a `%d` here would not be.
    private fun hex(b: ByteArray): String =
        b.joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xFF) }
}
