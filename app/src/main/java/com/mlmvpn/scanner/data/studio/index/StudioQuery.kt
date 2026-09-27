package com.mlmvpn.scanner.data.studio.index

/**
 * How the user list is narrowed and ordered.
 *
 * Both of these live here rather than in `domain/` because neither describes the product — they
 * describe a **query against the local index**, and every one of them is a clause this file's
 * neighbour knows how to write. Putting them in the domain would invite a screen to filter a list
 * it already holds in memory, which is the thing the index exists to stop: at fleet scale the list
 * a screen holds is one page of several thousand rows, so filtering it in Kotlin filters the page
 * rather than the list, and quietly shows the operator a subset of a subset.
 */

/**
 * The order rows come back in.
 *
 * [NEWEST] stays the default and is the only one with an index behind it
 * (`ix_idx_users_created`). The rest sort a few thousand short rows in a local SQLite file, which is
 * measured in single-digit milliseconds — the cost that made the engine refuse arbitrary sorting
 * (D1 charges for every row an ORDER BY reads) simply does not exist on this side.
 */
enum class UserSort(internal val orderBy: String) {
    NEWEST("created_at DESC, user_id DESC"),
    OLDEST("created_at ASC, user_id ASC"),
    NAME("username_lc ASC, user_id ASC"),

    /**
     * Soonest to run out first, and **never-expiring rows last rather than first**.
     *
     * `expires_at` is null for an open-ended subscription, and SQLite sorts NULL below every number
     * — so the plain ascending sort would open this list on the people who will never need
     * attention. The `CASE` pushes them to the end, which is the only ordering that answers the
     * question the sort was chosen for.
     */
    EXPIRY("CASE WHEN expires_at IS NULL OR expires_at <= 0 THEN 1 ELSE 0 END, expires_at ASC"),

    /** Heaviest first. The list an operator opens when an account is close to its ceiling. */
    USAGE("used_bytes DESC"),

    /** Most recently connected first; someone who never has, last, for the same reason as [EXPIRY]. */
    LAST_ACTIVE("CASE WHEN last_active_at IS NULL OR last_active_at <= 0 THEN 1 ELSE 0 END, last_active_at DESC"),
}

/**
 * Which rows are in the list at all.
 *
 * These are **not** the `status` column with a nicer name. Three of them — [EXPIRED], [EXPIRING],
 * [OUT_OF_VOLUME] — are conditions the engine never writes down: a user whose term ran out yesterday
 * still has `status = 'active'`, because expiry is evaluated when they connect rather than by
 * anything sweeping the table. Reading them off `status` would show an empty «منقضی» list on an
 * installation full of expired people.
 */
enum class UserFilter {
    ALL,
    ACTIVE,
    DISABLED,
    EXPIRED,
    /** Still working, but not for long. The same seven-day horizon the dashboard tile counts. */
    EXPIRING,
    OUT_OF_VOLUME,
    /** Handed a link and never used it — the list that says which hand-offs did not land. */
    NEVER_CONNECTED;

    /**
     * The SQL for this filter, or null when it adds nothing.
     *
     * [now] is passed in rather than read here so a page and the count beside it cannot disagree by
     * the milliseconds between two calls — which is visible as a list of nine under a heading of ten.
     */
    internal fun clause(now: Long): String? = when (this) {
        ALL -> null
        ACTIVE -> "status = 'active' AND (expires_at IS NULL OR expires_at <= 0 OR expires_at > $now)" +
            " AND (quota_bytes IS NULL OR quota_bytes <= 0 OR used_bytes < quota_bytes)"
        DISABLED -> "status = 'disabled'"
        EXPIRED -> "expires_at IS NOT NULL AND expires_at > 0 AND expires_at <= $now"
        EXPIRING -> "expires_at IS NOT NULL AND expires_at > $now AND expires_at <= ${now + 7 * 86_400_000L}"
        OUT_OF_VOLUME -> "quota_bytes IS NOT NULL AND quota_bytes > 0 AND used_bytes >= quota_bytes"
        NEVER_CONNECTED -> "(last_active_at IS NULL OR last_active_at <= 0)"
    }
}
