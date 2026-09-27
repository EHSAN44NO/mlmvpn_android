package com.mlmvpn.scanner.data.studio

import java.security.MessageDigest
import java.util.Locale

/**
 * What a Config Studio installation is called on Cloudflare, and how to recognise one.
 *
 * The names here are **derived from the Cloudflare account id, not stored on the device**, and that
 * is the whole point of this file. The old MLM deployer picked its worker name at random and could
 * only recover it by parsing the URL it had saved locally (`PanelBuild.scriptName` reads
 * `account.mlmWorkerUrl`). On a phone that had never deployed, that URL is null, so the deployer fell
 * through to a fresh random name and Cloudflare cheerfully created a *second* worker beside the first
 * -- serving the old script on the old URL, outside the staleness check, never upgraded again, and
 * still the target of every subscription link already handed out.
 *
 * A name that any device can recompute from the account alone removes that failure entirely: the
 * second phone asks for the same script name, and Cloudflare hands back the installation that is
 * already there.
 *
 * The randomness in [com.mlmvpn.scanner.utils.AntiDpi.generateSafeWorkerName] existed to make the
 * name *unguessable from outside*, which a hash preserves. Being *unreproducible* was an accident of
 * how it was written, and it is what cost us the duplicate worker.
 *
 * See `android/docs/CONFIG-STUDIO-PLAN.md` §B.
 */
object StudioNaming {

    /**
     * **Frozen. Do not edit, reorder, or add to this list.**
     *
     * The prefix is chosen by hashing the account id into this list, so changing the list changes
     * the name derived for accounts that already have an installation -- and a changed name means
     * discovery stops finding the worker that is already deployed, and the next deploy creates the
     * duplicate this file exists to prevent.
     *
     * It is deliberately a private copy of `AntiDpi.SAFE_PREFIXES` as it stood when this shipped,
     * rather than a reference to it, so that editing the anti-DPI list -- an ordinary thing to want
     * to do -- cannot silently break adoption.
     */
    private val FROZEN_PREFIXES = listOf(
        "app-core", "edge-relay", "main-thunder", "cloud-sync",
        "data-stream", "net-bridge", "api-hub", "web-flow",
        "fast-route", "smart-gate", "node-link", "micro-svc",
        "auto-scale", "load-bal", "cdn-edge", "cache-opt",
        "log-svc", "auth-api", "user-svc", "task-run",
        "event-bus", "msg-queue", "file-io", "db-proxy"
    )

    /** Also frozen, for the same reason. */
    private const val NAME_SALT = "cs.v1.name"
    private const val DB_SALT = "cs.v1.db"

    /**
     * The Cloudflare script name for this account's installation.
     *
     * Carries no product name: the legacy deployer appended `-mlm`, which put the old name into the
     * public hostname of every subscription link (`https://<name>-mlm.<sub>.workers.dev/...`). New
     * installations get this instead. Existing ones keep the hostname they have -- renaming would
     * change the URL and break links that are already in people's hands, with no way to tell them.
     */
    fun scriptName(accountId: String): String {
        val h = sha256(NAME_SALT + accountId)
        val prefix = FROZEN_PREFIXES[(h[0].toInt() and 0xFF) % FROZEN_PREFIXES.size]
        return "$prefix-" + hex(h, 1, 4)
    }

    /** The D1 database name for a new installation. Also carries no product name. */
    fun databaseName(accountId: String): String = "cs_" + hex(sha256(DB_SALT + accountId), 0, 5)

    /**
     * Names that might be an installation deployed before [scriptName] existed.
     *
     * Every worker the legacy deployer created ends in `-mlm`, because that suffix was appended
     * unconditionally. A match here is only a *candidate*: the same Cloudflare account also carries
     * BPB, EDG, Nahan, the DNS resolver, the relay and the pool worker, and adopting one of those
     * and then deploying over it would destroy another engine. Confirm before adopting.
     */
    fun isLegacyScriptName(name: String): Boolean =
        name.lowercase(Locale.US).endsWith("-mlm")

    /** Database names either era might have used. `mlm_db_*` is the legacy deployer's. */
    fun isStudioDatabaseName(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        return n.startsWith("mlm_db_") || n.startsWith("cs_")
    }

    /**
     * A fresh random path segment to hide the API behind, generated once per installation at deploy
     * time and stored as a readable binding so any device can recover it.
     *
     * Deliberately **not** derived from the account id: unlike the script name, nothing needs to
     * recompute this offline, so there is no reason to make it derivable.
     */
    fun newApiRoute(): String {
        val b = ByteArray(8)
        java.security.SecureRandom().nextBytes(b)
        return hex(b, 0, 8)
    }

    private fun sha256(s: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))

    private fun hex(b: ByteArray, from: Int, len: Int): String {
        val sb = StringBuilder(len * 2)
        for (i in from until minOf(from + len, b.size)) sb.append("%02x".format(b[i].toInt() and 0xFF))
        return sb.toString()
    }
}
