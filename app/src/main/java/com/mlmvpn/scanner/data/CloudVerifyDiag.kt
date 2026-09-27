package com.mlmvpn.scanner.data

import android.content.Context
import org.json.JSONObject

/**
 * Everything one account-status probe actually saw, rather than the two booleans the UI needs.
 *
 * The cloud panel locks itself behind "verify your email" on a single boolean, and users whose
 * Cloudflare account *is* verified were being locked out by it. Nothing in the app could say why:
 * the probe collapsed a 403, a missing JSON field, a blocked network and a genuinely unverified
 * account into the same `false`, and the "check again" button re-ran the same collapse. So the
 * probe now keeps what it saw, and [report] is what gets sent when a user is stuck.
 *
 * Nothing identifying travels in a report -- see [report]. The token, the email, the account id
 * and the workers.dev subdomain are all excluded; what is kept is HTTP statuses, Cloudflare's own
 * error codes, and which JSON fields came back.
 */
data class CloudVerifyProbe(
    /** Which credential shape was used: `bearer` (API token) or `global` (Global API Key). */
    val scheme: String,
    /** HTTP status of `/accounts/{id}/workers/subdomain`, or -1 when the call never completed. */
    val subdomainHttp: Int,
    /** Cloudflare's own `errors[]` from that call, codes and messages. */
    val subdomainErrors: String,
    val hasSubdomain: Boolean,
    /** `/user/tokens/verify` (token accounts only): is the credential itself still live? */
    val tokenVerifyHttp: Int,
    val tokenVerifyErrors: String,
    /**
     * True when the email is known to be verified, false when Cloudflare said so itself, and
     * **null when nobody knows** -- which is the normal case and must never read as "not verified".
     */
    val emailVerified: Boolean?,
    /** Why [emailVerified] holds what it holds, in one token: `subdomain`, `unknown`, `cf-rejected`. */
    val basis: String,
    /** Exception class and message when a call never reached Cloudflare at all. */
    val transport: String,
    val elapsedMs: Long,
) {

    /**
     * The one line the dashboard groups on.
     *
     * The pool worker hashes the summary into the group key, so this has to name the *failure
     * class* and nothing per-user: two people hitting the same 403 must land in one group, or the
     * dashboard answers "how many reports" instead of "how many users, and which cause".
     */
    fun summary(): String = buildString {
        append("CFVERIFY ")
        append("scheme=").append(scheme)
        append(" sub=").append(statusToken(subdomainHttp, subdomainErrors))
        append(" tok=").append(statusToken(tokenVerifyHttp, tokenVerifyErrors))
        append(" hasSub=").append(if (hasSubdomain) "y" else "n")
        append(" verified=").append(emailVerified?.let { if (it) "y" else "n" } ?: "?")
        append(" basis=").append(basis)
        if (transport.isNotBlank()) append(" transport=").append(transport.take(40))
    }.take(300)

    /** `403/9109`, `200`, `net` -- a status plus the first Cloudflare error code, nothing more. */
    private fun statusToken(http: Int, errors: String): String {
        if (http < 0) return "net"
        if (http == 0) return "skip"
        val code = Regex("""\b(\d{4,5})\b""").find(errors)?.groupValues?.get(1)
        return if (code != null) "$http/$code" else http.toString()
    }

    /**
     * The report body.
     *
     * Deliberately not the whole JSON: a Cloudflare `/user` result carries the account holder's
     * name, email and phone number, and a workers.dev subdomain identifies the person as surely as
     * their email does. Only statuses, error codes and error messages are kept -- Cloudflare's
     * messages are fixed strings from its own catalogue, not user data.
     */
    fun report(context: Context, note: String = ""): String = buildString {
        appendLine("MLM VPN — Cloudflare verification diagnostic")
        appendLine("when       : ${java.util.Date()}")
        appendLine("app        : ${appVersion(context)}")
        appendLine("device     : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
            "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        if (note.isNotBlank()) appendLine("note       : $note")
        appendLine()
        appendLine("auth scheme      : $scheme")
        appendLine("subdomain probe  : HTTP $subdomainHttp")
        appendLine("  errors         : ${subdomainErrors.ifBlank { "-" }}")
        appendLine("  has subdomain  : $hasSubdomain")
        appendLine("token verify     : HTTP $tokenVerifyHttp")
        appendLine("  errors         : ${tokenVerifyErrors.ifBlank { "-" }}")
        appendLine("email verified   : ${emailVerified?.toString() ?: "unknown"}  (basis: $basis)")
        appendLine("transport        : ${transport.ifBlank { "-" }}")
        appendLine("elapsed          : ${elapsedMs}ms")
    }

    private fun appVersion(context: Context): String = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        "${pi.versionName} (${pi.longVersionCode})"
    }.getOrDefault("?")

    companion object {
        /**
         * Cloudflare's `errors` array, flattened to `code: message` pairs.
         *
         * Read from the body of a *failed* call, where the body is the only thing that says what
         * went wrong. Anything unparseable falls back to the first 200 characters, because an
         * error page from a captive portal or a filtering middlebox is itself the answer.
         */
        fun errorsOf(body: String?): String {
            val text = body.orEmpty()
            if (text.isBlank()) return ""
            return runCatching {
                val arr = JSONObject(text).optJSONArray("errors") ?: return@runCatching ""
                (0 until arr.length()).joinToString("; ") { i ->
                    val e = arr.optJSONObject(i) ?: return@joinToString ""
                    "${e.optInt("code")}: ${e.optString("message").take(120)}"
                }
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: text.take(200).replace(Regex("""\s+"""), " ")
        }
    }
}
