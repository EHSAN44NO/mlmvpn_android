package com.mlmvpn.scanner.data

import com.mlmvpn.scanner.models.CloudAccount
import okhttp3.Headers

/**
 * How to authenticate to the Cloudflare API with a given credential.
 *
 * Cloudflare accepts two, and they take different headers:
 *
 *  - a **Global API Key** goes in `X-Auth-Key` alongside `X-Auth-Email`. It is exactly 37
 *    characters of lowercase hex, and it is useless without the account's email address.
 *  - an **API token** goes in `Authorization: Bearer`. It carries its own identity, so no email
 *    is involved, and sending one as `X-Auth-Key` does not fail cleanly -- Cloudflare
 *    authenticates the account and then refuses every resource with error 10037.
 *
 * The old rule chose between them by asking whether the email FIELD was filled in. A user who
 * pasted a scoped token and also typed their email -- which the form asks for first -- was
 * silently switched to the Global API Key headers, and every deploy failed with 10037 at once.
 * The credential's own shape is what decides here.
 */
object CloudAuth {

    /**
     * The classic Global API Key shape: 37 lowercase hex characters.
     *
     * A GUESS, and known to be narrower than reality -- a working global key of 52 characters has
     * been observed on a real account. It is only ever consulted for a credential whose scheme was
     * never proven (an account saved before [CloudAccount.authScheme] existed); anything the probe
     * has actually tested is answered from that instead, and must be, because this test would call
     * that 52-character key a token and send it in the wrong header.
     */
    private val GLOBAL_KEY = Regex("^[0-9a-f]{37}$")

    fun isGlobalKey(token: String, email: String): Boolean =
        email.isNotBlank() && GLOBAL_KEY.matches(token.trim())

    /** The headers for this credential. */
    fun headers(token: String, email: String): Headers = Headers.Builder().apply {
        if (isGlobalKey(token, email)) {
            add("X-Auth-Email", email.trim())
            add("X-Auth-Key", token.trim())
        } else {
            add("Authorization", "Bearer ${token.trim()}")
        }
        add("Content-Type", "application/json")
    }.build()

    /**
     * The headers for an account, preferring what was actually proven to work.
     *
     * [CloudAccount.authScheme] is set by the probe in `addAccount`. Accounts saved before that
     * existed carry null and fall back to the shape check.
     */
    fun headers(account: CloudAccount): Headers =
        if (useBearer(account)) {
            Headers.Builder()
                .add("Authorization", "Bearer ${account.token.trim()}")
                .add("Content-Type", "application/json")
                .build()
        } else {
            Headers.Builder()
                .add("X-Auth-Email", account.email.trim())
                .add("X-Auth-Key", account.token.trim())
                .add("Content-Type", "application/json")
                .build()
        }

    /**
     * True when the Bearer form applies.
     *
     * Kept because several call sites build their headers inline and only need the branch; they
     * read better as `if (useBearer(account))` than as `if (!isGlobalKey(...))`.
     */
    fun useBearer(account: CloudAccount): Boolean = when (account.authScheme) {
        "bearer" -> true
        "global" -> false
        else -> !isGlobalKey(account.token, account.email)
    }
}
