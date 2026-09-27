package com.mlmvpn.scanner.engines.game.booster.model

/**
 * Where the booster measures, per region.
 *
 * Match servers do not answer anything we could send them (they speak their own UDP protocol and
 * ignore ICMP), so each region is represented by ANCHORS: hosts that really answer and sit in a
 * datacentre region rather than on anycast, so a measurement to them says something about the
 * geographic path to the game's datacentre.
 *
 *  - **UDP echo** -- the AWS GameLift ping beacons (`gamelift-ping.<region>.api.aws:7770`) echo any
 *    datagram. That is a real UDP round trip into the same AWS region the match servers run in.
 *    A beacon answers about two packets a second per flow, so trains spread over many flows (see
 *    `udpTrainFlows`). Whether each beacon answers from a given network is exactly what the
 *    measurement finds out.
 *  - **TCP 443** -- `ec2.<region>.amazonaws.com` and a second provider in the same city. A SYN →
 *    SYN/ACK is a real round trip and works where UDP is blocked (Irancell), so it carries the
 *    latency when the beacon cannot. The fastest endpoint that answers is used, so one dead name
 *    cannot make a live region read as dark.
 *  - **STUN** -- anycast, so it measures "the nearest instance of a big network", not the region.
 *    Kept only as the last-resort UDP target for jitter and loss, and labelled as such.
 *
 * ## The 2026 map
 *
 * The Gulf datacentres that used to host the Middle East servers of nearly every game -- AWS
 * Bahrain (me-south-1) and AWS UAE (me-central-1) -- were struck on 1–3 March 2026. Bahrain has
 * not come back (as of September 2026) and one of the UAE zones is still out. PUBG Mobile's Gulf
 * servers are offline and its players were moved to Frankfurt and London; other games went to
 * Mumbai or Europe. So Europe leads the list, Bahrain is kept only to show it is down, and the
 * region sweep measures every candidate each time rather than trusting this list: the map moves.
 */
object RegionCatalog {

    data class Endpoint(val host: String, val port: Int)

    /** What is known about a region's datacentres, independent of any one line. */
    enum class Status {
        OK,
        /** Up, but part of it is out -- measured, never recommended on reputation alone. */
        DEGRADED,
        /** Known to be down. Still measured (it may come back), never a default. */
        DOWN,
    }

    data class Region(
        val key: String,
        val labelFa: String,
        val labelEn: String,
        val tcp: List<Endpoint>,
        /** UDP echo beacons (EchoProtocol). Empty where AWS has none. */
        val echo: List<Endpoint>,
        val status: Status = Status.OK,
    )

    val REGIONS: List<Region> = listOf(
        Region(
            key = "aws-eu-central-1",
            labelFa = "فرانکفورت (آلمان)",
            labelEn = "Frankfurt (Germany)",
            tcp = listOf(
                Endpoint("ec2.eu-central-1.amazonaws.com", 443),
                Endpoint("fra-de-ping.vultr.com", 443),
            ),
            echo = listOf(Endpoint("gamelift-ping.eu-central-1.api.aws", 7770)),
        ),
        Region(
            key = "aws-eu-west-2",
            labelFa = "لندن (انگلیس)",
            labelEn = "London (UK)",
            tcp = listOf(
                Endpoint("ec2.eu-west-2.amazonaws.com", 443),
                Endpoint("lon-gb-ping.vultr.com", 443),
            ),
            echo = listOf(Endpoint("gamelift-ping.eu-west-2.api.aws", 7770)),
        ),
        Region(
            key = "aws-ap-south-1",
            labelFa = "بمبئی (هند)",
            labelEn = "Mumbai (India)",
            tcp = listOf(
                Endpoint("ec2.ap-south-1.amazonaws.com", 443),
                Endpoint("bom-in-ping.vultr.com", 443),
            ),
            echo = listOf(Endpoint("gamelift-ping.ap-south-1.api.aws", 7770)),
        ),
        Region(
            key = "aws-ap-southeast-1",
            labelFa = "سنگاپور",
            labelEn = "Singapore",
            tcp = listOf(
                Endpoint("ec2.ap-southeast-1.amazonaws.com", 443),
                Endpoint("sgp-ping.vultr.com", 443),
            ),
            echo = listOf(Endpoint("gamelift-ping.ap-southeast-1.api.aws", 7770)),
        ),
        Region(
            key = "aws-me-central-1",
            labelFa = "امارات",
            labelEn = "UAE",
            tcp = listOf(Endpoint("ec2.me-central-1.amazonaws.com", 443)),
            echo = emptyList(),
            status = Status.DEGRADED,
        ),
        Region(
            key = "aws-me-south-1",
            labelFa = "بحرین",
            labelEn = "Bahrain",
            tcp = listOf(
                Endpoint("ec2.me-south-1.amazonaws.com", 443),
                Endpoint("dynamodb.me-south-1.amazonaws.com", 443),
            ),
            echo = listOf(Endpoint("gamelift-ping.me-south-1.api.aws", 7770)),
            status = Status.DOWN,
        ),
    )

    /** Anycast STUN, for jitter and loss when no beacon answers. Never a latency claim. */
    val STUN: List<Endpoint> = listOf(
        Endpoint("stun.l.google.com", 19302),
        Endpoint("stun.cloudflare.com", 3478),
        Endpoint("global.stun.twilio.com", 3478),
    )

    /** Europe: where most displaced Middle East traffic went in 2026, and up. */
    const val DEFAULT_REGION = "aws-eu-central-1"

    fun byKey(key: String?): Region = REGIONS.firstOrNull { it.key == key } ?: REGIONS.first()

    fun find(key: String?): Region? = REGIONS.firstOrNull { it.key == key }
}
