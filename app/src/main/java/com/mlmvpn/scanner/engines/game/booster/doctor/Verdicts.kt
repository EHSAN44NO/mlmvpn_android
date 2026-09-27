package com.mlmvpn.scanner.engines.game.booster.doctor

import com.mlmvpn.scanner.engines.game.booster.model.TrafficClass

/** What stands between this line and one of the game's hosts. */
enum class Obstacle {
    OK,
    /** The system resolver lies (block page, bogus answer); the real address works. Clean DNS fixes it. */
    DNS_POISONED,
    /** Filtered on the TLS name: even the right address is cut at the handshake. */
    SNI_BLOCKED,
    /** Filtered on the address: nothing at TCP. */
    IP_BLOCKED,
    /** The host itself refuses this line's country (403/451): a sanction, not a filter. */
    GEO_BLOCKED,
    /** Could not tell: the name does not resolve anywhere, or the checks ran out of time. */
    UNKNOWN,
}

/**
 * The five kinds of game on an Iranian line, as the players name them -- worked out per game AND
 * per line, because the same game is a different kind on another operator.
 */
enum class GameKind {
    /** 1: nothing blocks it; what is left is ping, jitter and loss. */
    DIRECT,
    /** 2: the game will not sign in from Iran -- a sanction. */
    SANCTIONED,
    /** 3: the game's servers are filtered on this line. */
    FILTERED,
    /** 4: the game plays, some parts (store, updates, Google sign-in…) are sanctioned or filtered. */
    PARTIAL,
    /** 5: sanctioned and filtered. */
    BOTH,
}

/** One host, checked. */
data class HostCheck(
    val host: String,
    val cls: TrafficClass,
    /** What the system resolver answered (IPv4). */
    val systemIps: List<String>,
    /** How the host answered through the system's address, when there was a usable one. */
    val systemOutcome: SanctionProbe.Outcome?,
    /** A clean answer (DNS over HTTPS by IP), fetched only when the system's did not serve. */
    val cleanIps: List<String>,
    val cleanOutcome: SanctionProbe.Outcome?,
    val obstacle: Obstacle,
    /**
     * For a host filtered on its TLS name: the address at which a fragmented ClientHello was
     * proven to open it from this line ([FragmentProbe]); null when not tried or it did not help.
     */
    val fragmentIp: String? = null,
)

object HostVerdict {

    /**
     * What one host's checks add up to. Pure: the doctor gathers, this decides.
     *
     * The system's answer is tried first. If it serves (or refuses on country) that is the answer.
     * Otherwise the clean answer says why: if it serves, only the DNS was wrong; if it too is cut
     * at the handshake or at TCP, the host itself is filtered. With nothing to go on -- the name
     * resolves nowhere -- the host is UNKNOWN, never "blocked": a dead name in the catalog must
     * not push a game into a tunnel.
     */
    fun classify(
        systemUsable: Boolean,
        systemOutcome: SanctionProbe.Outcome?,
        cleanOutcome: SanctionProbe.Outcome?,
    ): Obstacle {
        if (systemUsable) {
            when (systemOutcome) {
                SanctionProbe.Outcome.OPEN -> return Obstacle.OK
                SanctionProbe.Outcome.GEO_BLOCKED -> return Obstacle.GEO_BLOCKED
                else -> Unit
            }
        }
        return when (cleanOutcome) {
            SanctionProbe.Outcome.OPEN -> Obstacle.DNS_POISONED
            SanctionProbe.Outcome.GEO_BLOCKED -> Obstacle.GEO_BLOCKED
            SanctionProbe.Outcome.TLS_RESET, SanctionProbe.Outcome.CERT_MISMATCH -> Obstacle.SNI_BLOCKED
            SanctionProbe.Outcome.TCP_RESET, SanctionProbe.Outcome.TCP_TIMEOUT -> Obstacle.IP_BLOCKED
            SanctionProbe.Outcome.ERROR, null -> when (if (systemUsable) systemOutcome else null) {
                SanctionProbe.Outcome.TLS_RESET, SanctionProbe.Outcome.CERT_MISMATCH -> Obstacle.SNI_BLOCKED
                SanctionProbe.Outcome.TCP_RESET, SanctionProbe.Outcome.TCP_TIMEOUT -> Obstacle.IP_BLOCKED
                else -> Obstacle.UNKNOWN
            }
        }
    }

    /**
     * Answers a filtering resolver gives instead of the real one: the block page, zero and loopback
     * addresses, the fake-IP pool, and the root servers (seen from one Iranian resolver in 2026).
     */
    fun isFakeAnswer(ip: String): Boolean =
        ip.startsWith("10.10.34.") || ip.startsWith("10.10.35.") || ip.startsWith("10.10.36.") ||
            ip.startsWith("0.") || ip.startsWith("127.") || ip.startsWith("198.18.") || ip.startsWith("198.19.") ||
            ip in ROOT_SERVERS

    /** Private ranges: never a public host's address when asked of the system resolver. */
    fun isPrivate(ip: String): Boolean =
        ip.startsWith("10.") || ip.startsWith("192.168.") ||
            Regex("""^172\.(1[6-9]|2\d|3[01])\.""").containsMatchIn(ip)

    private val ROOT_SERVERS = setOf(
        "198.41.0.4", "170.247.170.2", "199.9.14.201", "192.33.4.12", "199.7.91.13", "192.203.230.10",
        "192.5.5.241", "192.112.36.4", "198.97.190.53", "192.36.148.17", "192.58.128.30", "193.0.14.129",
        "199.7.83.42", "202.12.27.33",
    )
}

object KindClassifier {

    /**
     * The kind of game this is on this line, from its checked hosts; null when nothing could be
     * checked. A sign-in or game service refused on country makes it SANCTIONED -- publishers
     * refuse a country everywhere or nowhere. It is FILTERED only when EVERY checked core host is
     * filtered: one dead name among working ones is a catalog problem, not a filtered game.
     */
    fun classify(hosts: List<HostCheck>): GameKind? {
        val known = hosts.filter { it.obstacle != Obstacle.UNKNOWN }
        if (known.isEmpty()) return null
        val core = known.filter { it.cls.isCore }
        val sanctionedCore = core.any { it.obstacle == Obstacle.GEO_BLOCKED }
        val filteredCore = core.isNotEmpty() && core.all { it.obstacle.isFilter() }
        return when {
            sanctionedCore && (filteredCore || known.any { it.cls.isCore && it.obstacle.isFilter() }) -> GameKind.BOTH
            sanctionedCore -> GameKind.SANCTIONED
            filteredCore -> GameKind.FILTERED
            known.any { it.obstacle != Obstacle.OK } -> GameKind.PARTIAL
            else -> GameKind.DIRECT
        }
    }

    /** The worst obstacle of each checked part, for the card's per-part lines. UNKNOWN parts are left out. */
    fun perClass(hosts: List<HostCheck>): Map<TrafficClass, Obstacle> =
        hosts.filter { it.obstacle != Obstacle.UNKNOWN }.groupBy { it.cls }
            .mapValues { (_, hs) -> hs.map { it.obstacle }.maxByOrNull { SEVERITY.getValue(it) } ?: Obstacle.OK }

    private val SEVERITY = mapOf(
        Obstacle.OK to 0, Obstacle.UNKNOWN to 0, Obstacle.DNS_POISONED to 1, Obstacle.GEO_BLOCKED to 2,
        Obstacle.SNI_BLOCKED to 3, Obstacle.IP_BLOCKED to 4,
    )

    private fun Obstacle.isFilter() =
        this == Obstacle.DNS_POISONED || this == Obstacle.SNI_BLOCKED || this == Obstacle.IP_BLOCKED
}
