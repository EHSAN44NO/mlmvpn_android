package com.mlmvpn.scanner.utils

/**
 * Strips credential-shaped text out of anything that leaves the device or lands in a log.
 *
 * Crash reports go to a GitHub issue (see CrashReporter.upload); an exception message that
 * happened to carry a Cloudflare key or an email would otherwise travel with it. This is a last
 * line of defence, not a licence to put secrets in messages: shapes it does not know pass through.
 *
 * Pure Kotlin so it is unit-tested on the JVM.
 */
object SecretRedactor {
    private const val MASK = "‹redacted›"

    private val rules: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        // Authorization: Bearer <token>
        Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}") to { m -> m.groupValues[1] + MASK },
        // X-Auth-Key / X-Auth-Email header values
        Regex("(?i)(x-auth-(?:key|email)\\s*[:=]\\s*)\\S+") to { m -> m.groupValues[1] + MASK },
        // key=value pairs in URLs, JSON or logs whose name says secret
        Regex("(?i)([\"']?(?:token|api[_-]?key|apikey|secret|password|passwd|pass|masterkey|auth)[\"']?\\s*[:=]\\s*[\"']?)([^\"'&\\s,}]{4,})") to
            { m -> m.groupValues[1] + MASK },
        // Emails
        Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}") to { _ -> MASK },
        // Cloudflare Global API Key: 37 hex chars
        Regex("(?<![A-Za-z0-9])[0-9a-fA-F]{37}(?![A-Za-z0-9])") to { _ -> MASK },
        // Cloudflare API tokens and similar 40-char opaque tokens
        Regex("(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{40}(?![A-Za-z0-9_-])") to { _ -> MASK },
    )

    fun redact(text: String): String {
        var out = text
        for ((re, repl) in rules) out = re.replace(out, repl)
        return out
    }
}
