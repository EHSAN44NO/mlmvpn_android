package com.mlmvpn.scanner.data

import com.mlmvpn.scanner.R

/**
 * The one thing the troubleshooting screen can do about a finding.
 *
 * Each is a single, reversible setting change or a single retry -- never a bundle. A user who is
 * stuck needs to know exactly what changed, so that if it does not help they can put it back.
 */
enum class CloudFixAction {
    /** Nothing this app can change; the fix is on Cloudflare's side. */
    NONE,
    /** Ask Cloudflare again. */
    RETRY,
    /** Send this credential with the other header shape. */
    SWITCH_SCHEME,
    /** Try the workers.dev subdomain create again. */
    CREATE_SUBDOMAIN,
    /** Take the email-verification lock off this account by hand. */
    UNLOCK_EMAIL,
    /** Send what the probe saw. */
    REPORT,
}

/** One finding: what is wrong, why, and the single thing to try. */
data class CloudAdvice(
    val titleRes: Int,
    val bodyRes: Int,
    val action: CloudFixAction,
    val actionLabelRes: Int,
    /** 0 = ordinary, 1 = needs attention, 2 = blocking. Drives the dot colour, nothing else. */
    val severity: Int,
)

/**
 * Turn what the probe saw into advice, using Cloudflare's own error codes.
 *
 * This is the part that stops the app guessing at the user. Every branch below is keyed to a
 * specific HTTP status or a specific documented Cloudflare error code, and anything that matches
 * none of them falls through to "we do not know -- send the report" rather than to a confident
 * wrong answer. That last branch is the whole reason the report exists: the codes we have not seen
 * yet are the ones worth learning about, and the alternative to admitting it is inventing a cause.
 *
 * Ordered most-blocking first, because the screen shows them in order and the first one is what
 * the user will act on.
 */
fun CloudVerifyProbe.advice(): List<CloudAdvice> {
    val out = mutableListOf<CloudAdvice>()
    val subCodes = codesIn(subdomainErrors)
    val tokCodes = codesIn(tokenVerifyErrors)

    // The screen is reachable from a healthy account -- it is next to the delete button, not only
    // behind the lock -- so "nothing is wrong" has to be one of the things it can say. Without this
    // a working account was told its problem was unrecognised, which is alarming and false.
    if (hasSubdomain && subdomainHttp == 200 &&
        (tokenVerifyHttp == 0 || tokenVerifyHttp == 200)
    ) {
        out += CloudAdvice(
            R.string.cf_advice_ok_title,
            R.string.cf_advice_ok_body,
            CloudFixAction.NONE,
            0,
            0,
        )
        return out
    }

    // Nothing reached Cloudflare. Every other reading below is meaningless until this is fixed,
    // and on the networks this app exists for it is the single likeliest cause.
    if (subdomainHttp < 0 || transport.isNotBlank()) {
        out += CloudAdvice(
            R.string.cf_advice_network_title,
            R.string.cf_advice_network_body,
            CloudFixAction.RETRY,
            R.string.cf_advice_retry,
            2,
        )
        return out
    }

    // The credential itself is gone. Changing settings cannot help, and suggesting one would send
    // the user round a loop.
    if (tokenVerifyHttp == 401 || tokenVerifyHttp == 403 ||
        tokCodes.any { it == 1000 || it == 6003 || it == 9109 || it == 10000 }
    ) {
        out += CloudAdvice(
            R.string.cf_advice_token_dead_title,
            R.string.cf_advice_token_dead_body,
            CloudFixAction.NONE,
            0,
            2,
        )
        return out
    }

    // 10037 is what Cloudflare answers when an API token arrives in the Global-Key headers: it
    // authenticates the account and then refuses every resource. 9106 and 6003 are the header
    // errors around the same mistake. All three mean the credential is fine and the envelope is
    // wrong -- which is one setting away.
    if (subCodes.any { it == 10037 || it == 9106 || it == 6003 }) {
        out += CloudAdvice(
            R.string.cf_advice_scheme_title,
            R.string.cf_advice_scheme_body,
            CloudFixAction.SWITCH_SCHEME,
            R.string.cf_advice_switch_scheme,
            2,
        )
    }

    // A live token that is refused this one resource has not been granted it. 9109 is Cloudflare's
    // "unauthorized to access requested resource", and on the Workers subdomain endpoint it means
    // the token carries no Workers permission for this account -- which no setting in this app can
    // grant, so the advice is how to mint one that does.
    if ((subdomainHttp == 403 || subCodes.contains(9109)) &&
        (tokenVerifyHttp == 200 || tokenVerifyHttp == 0)
    ) {
        out += CloudAdvice(
            R.string.cf_advice_scope_title,
            R.string.cf_advice_scope_body,
            CloudFixAction.SWITCH_SCHEME,
            R.string.cf_advice_switch_scheme,
            2,
        )
    }

    if (subdomainHttp == 429) {
        out += CloudAdvice(
            R.string.cf_advice_rate_title,
            R.string.cf_advice_rate_body,
            CloudFixAction.RETRY,
            R.string.cf_advice_retry,
            1,
        )
    }

    // The ordinary case, and the one the app used to mislabel as an unverified email: this
    // account simply has never had a workers.dev subdomain.
    //
    // Both shapes of answer count. The 200 case is an account that HAS the endpoint and an empty
    // subdomain; the 404 with error 10007 is Cloudflare saying so outright -- "You do not have a
    // workers.dev subdomain" -- and only the first was recognised. So the single most common
    // first-run situation fell through every branch to "the cause is not something this version
    // recognises", which asks the user to send a report instead of offering them the button that
    // fixes it. Seven separate people sent that report before the pattern was visible; the answer
    // was in the error code they were sending.
    if ((subdomainHttp == 200 || subCodes.contains(10007)) && !hasSubdomain) {
        out += CloudAdvice(
            R.string.cf_advice_nosub_title,
            R.string.cf_advice_nosub_body,
            CloudFixAction.CREATE_SUBDOMAIN,
            R.string.cf_advice_create_subdomain,
            1,
        )
    }

    // Everything is answering and there is still nothing to do here: whatever is blocking this
    // user is something this build does not recognise.
    if (out.isEmpty()) {
        out += CloudAdvice(
            R.string.cf_advice_unknown_title,
            R.string.cf_advice_unknown_body,
            CloudFixAction.REPORT,
            R.string.cf_advice_send_report,
            1,
        )
    }
    return out
}

/**
 * Advice for a credential that could not be added yet, where the account probe's questions do not
 * apply.
 *
 * "The credentials were rejected" is the whole of what the add screen could say, and it fits four
 * completely different situations with four different fixes: a Global API Key pasted without its
 * email, a revoked or mistyped token, a token that authenticates but is not allowed to see the
 * account, and a phone that never reached Cloudflare at all. Each branch here is the one that
 * separates them, keyed to what the probe saw rather than to the text of the error.
 *
 * See [CloudVerifyProbe]: in this mode `tokenVerify` is the authentication answer and `subdomain`
 * is the account listing.
 */
fun CloudVerifyProbe.credentialAdvice(): List<CloudAdvice> {
    val out = mutableListOf<CloudAdvice>()
    val authOk = tokenVerifyHttp == 200

    /**
     * A **Global API Key** pasted with no email — not merely "the email box is empty".
     *
     * The scheme has to be part of this. An API token carries its own identity and needs no email
     * at all, so a user who correctly pasted a token and had it rejected was being told, as the
     * first and most prominent thing on the screen, to go and fill in the account email. That is
     * advice for a problem they do not have, in front of the one they do — and it was shown on
     * every token failure, because the test was only "is the email box blank".
     *
     * The screen already displays the answer two rows higher ("sent as: API token"), which is
     * [scheme]; it just was not consulted here. `startsWith` rather than `==` because a probe that
     * could not prove which scheme worked records its guess as `global?`.
     */
    val globalKeyWithoutEmail = basis.endsWith("no-email") && scheme.startsWith("global")

    if (transport.isNotBlank() || tokenVerifyHttp < 0) {
        out += CloudAdvice(
            R.string.cf_advice_network_title,
            R.string.cf_advice_network_body,
            CloudFixAction.RETRY,
            R.string.cf_advice_retry,
            2,
        )
        return out
    }

    if (!authOk) {
        // The single most common way to get stuck here, and the one the error text hides: a Global
        // API Key is useless on its own. It goes in a header PAIR with the account email, so
        // pasting one into a form whose email box was left blank fails as "invalid credentials".
        if (globalKeyWithoutEmail) {
            out += CloudAdvice(
                R.string.cf_advice_need_email_title,
                R.string.cf_advice_need_email_body,
                CloudFixAction.NONE,
                0,
                2,
            )
        }
        out += CloudAdvice(
            R.string.cf_advice_token_dead_title,
            R.string.cf_advice_token_dead_body,
            CloudFixAction.NONE,
            0,
            2,
        )
        out += CloudAdvice(
            R.string.cf_advice_unknown_title,
            R.string.cf_advice_cred_report_body,
            CloudFixAction.REPORT,
            R.string.cf_advice_send_report,
            1,
        )
        return out
    }

    // Authenticated, and then shown nothing. Cloudflare treats "who are you" and "what may you
    // see" as separate permissions, so a token can pass the first and list no accounts at all --
    // which the add screen reports as a credential problem, sending the user to re-paste a token
    // that was never the issue.
    if (subdomainHttp != 200 || subdomainErrors.isNotBlank()) {
        out += CloudAdvice(
            R.string.cf_advice_no_accounts_title,
            R.string.cf_advice_no_accounts_body,
            CloudFixAction.RETRY,
            R.string.cf_advice_retry,
            2,
        )
        out += CloudAdvice(
            R.string.cf_advice_unknown_title,
            R.string.cf_advice_cred_report_body,
            CloudFixAction.REPORT,
            R.string.cf_advice_send_report,
            1,
        )
        return out
    }

    // Both questions answered yes. Whatever stopped the add is not in this pair, and saying so is
    // more useful than a guess -- it tells the user to try again rather than to hunt for a better
    // token.
    out += CloudAdvice(
        R.string.cf_advice_cred_ok_title,
        R.string.cf_advice_cred_ok_body,
        CloudFixAction.REPORT,
        R.string.cf_advice_send_report,
        0,
    )
    return out
}

/** The numeric Cloudflare error codes in a flattened `code: message` list. */
private fun codesIn(errors: String): List<Int> =
    Regex("""(\d{4,5}):""").findAll(errors).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
