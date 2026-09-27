package com.mlmvpn.scanner.data.studio

/**
 * Doing one thing to many people, across a fleet with no cap on its size.
 *
 * ### Why there is no bulk endpoint
 *
 * A selection is not a shard. Twenty people chosen off one list can live on twenty different
 * Cloudflare accounts, each with its own worker, its own D1 and its own API key — so "renew these
 * twenty" is twenty requests to N workers whatever the API looks like, and a `POST /v1/users:bulk`
 * would only move the loop to a place that can see one account. The loop belongs on the client,
 * which is the only thing that can see the whole fleet (plan §A.2).
 *
 * ### Three properties this has to have, and each is a choice
 *
 *  * **It reports per person, never as one verdict.** Nineteen of twenty succeeding is the ordinary
 *    outcome — an account is unreachable, a name was deleted from another device — and a single
 *    "failed" would hide which nineteen worked, leaving the operator to run it again and hope the
 *    successful ones are idempotent. [BulkResult] carries the failures by name.
 *  * **It does not stop at the first failure.** The alternative leaves the selection half-applied
 *    with no record of where it stopped.
 *  * **It is sequential, not parallel.** Twenty concurrent requests to one worker is twenty
 *    concurrent D1 transactions on a free plan; the wall-clock saving is a second and the cost is a
 *    failure mode that only appears when the selection is large.
 *
 * ### What is deliberately NOT here
 *
 * The re-read before each write that [StudioStore.withFreshUser] does for a single user. It exists
 * because a screen paints from an index that is minutes old, and acting on a figure the operator has
 * been looking at is the failure the local index was warned about. **Not one bulk operation depends
 * on a client-side figure**: enable, disable and delete are addressed by id, and a renewal is
 * computed by the engine from its OWN row (`before.quota_bytes + bytes`). Adding the re-read would
 * double the request count of every bulk action to protect against nothing.
 */

/**
 * One person a bulk operation will touch.
 *
 * Carries the username as well as the two ids, because a failure has to be reported by the name the
 * operator selected — after a delete, the id resolves to nothing and the row is gone from the index,
 * so a list of failed ids would be unreadable exactly when it matters.
 */
data class UserRef(
    val installationId: String,
    val userId: String,
    val username: String,
) {
    /** Stable across a selection, and the key a lazy list can use. */
    val key: String get() = "$installationId:$userId"
}

/** One person the operation did not reach, and the engine's stable code for why. */
data class BulkFailure(val username: String, val code: String)

/**
 * What happened to the whole selection.
 *
 * [ok] plus [failures] is always the number of people selected: nothing is silently skipped.
 */
data class BulkResult(
    val ok: Int,
    val failures: List<BulkFailure>,
) {
    val total: Int get() = ok + failures.size
    val allSucceeded: Boolean get() = failures.isEmpty()

    companion object {
        val EMPTY = BulkResult(0, emptyList())
    }
}

/**
 * A subscription link collected for one person, or the reason there is none.
 *
 * Both halves are kept because an export of nineteen links and one blank line is a file that looks
 * complete and is not. [error] non-null is the row that must still appear, saying why.
 */
data class BulkLink(
    val username: String,
    val url: String?,
    val error: String? = null,
)
