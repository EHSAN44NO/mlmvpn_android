package com.mlmvpn.scanner.data

/**
 * How much of a subscription is left, as its server says it.
 *
 * Read from `Subscription-Userinfo` (`upload=…; download=…; total=…; expire=…`), the header every
 * common panel sends -- Config Studio, Marzban, 3x-ui, Hiddify -- so any subscription added here can
 * show its remaining volume and days, not only ours. `total=0` means unlimited and `expire=0` means
 * never, which is the convention those panels follow; `expire` is UNIX seconds.
 */
data class SubscriptionUsage(
    val usedBytes: Long,
    /** Null when the subscription has no volume cap. */
    val totalBytes: Long?,
    /** Epoch milliseconds, or null when it never ends. */
    val expireAt: Long?,
) {
    /** What is left of the volume, never negative; null when unlimited. */
    val leftBytes: Long? get() = totalBytes?.let { (it - usedBytes).coerceAtLeast(0) }

    companion object {
        /** The header's value, or null when it carries nothing usable. */
        fun parse(header: String?): SubscriptionUsage? {
            if (header.isNullOrBlank()) return null
            val fields = header.split(';')
                .mapNotNull { part ->
                    val kv = part.split('=', limit = 2)
                    if (kv.size != 2) null else kv[0].trim().lowercase() to kv[1].trim()
                }
                .toMap()
            // A number the server wrote as a float ("1.073741824E9") is still that number.
            fun num(key: String): Long? = fields[key]?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }
            val upload = num("upload")
            val download = num("download")
            val total = num("total")
            val expire = num("expire")
            if (upload == null && download == null && total == null && expire == null) return null
            return SubscriptionUsage(
                usedBytes = (upload ?: 0L).coerceAtLeast(0) + (download ?: 0L).coerceAtLeast(0),
                totalBytes = total?.takeIf { it > 0 },
                expireAt = expire?.takeIf { it > 0 }?.let { it * 1000L },
            )
        }

        /** The all-zero id Config Studio's info entries carry (04c-studio-sub.js › STUDIO_INFO_ID). */
        private const val INFO_ID = "00000000-0000-0000-0000-000000000000"

        /**
         * Whether a subscription line is one of Config Studio's info entries -- a name that states the
         * volume and days left, at 127.0.0.1 with the all-zero id -- rather than a server.
         *
         * Other apps list these as entries, which is the point of them. This app draws the same
         * figures as a card above the group (NodesTab), so it leaves them out of the server list:
         * as a server each would fail every test and could be picked for a connection that goes
         * nowhere.
         */
        fun isInfoEntry(line: String): Boolean {
            val t = line.trim()
            if (!t.startsWith("vless://", ignoreCase = true)) return false
            val rest = t.substringAfter("://")
            val cred = rest.substringBefore('@')
            val host = rest.substringAfter('@', "").substringBefore('?').substringBefore('#')
            return cred.equals(INFO_ID, ignoreCase = true) && host.startsWith("127.0.0.1:")
        }

        /** The text of an info entry, decoded from its name. */
        fun infoText(line: String): String =
            runCatching { java.net.URLDecoder.decode(line.substringAfter('#', ""), "UTF-8") }.getOrDefault("")
    }
}
