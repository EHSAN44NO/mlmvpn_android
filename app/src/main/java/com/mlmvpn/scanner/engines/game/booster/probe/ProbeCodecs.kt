package com.mlmvpn.scanner.engines.game.booster.probe

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.security.SecureRandom

/**
 * What a UDP probe sends and how its answer is recognised.
 *
 * Every protocol here carries a token we chose, so an answer is tied to the exact packet it
 * answers. That is the property the NetDoctor UDP test lacked: it matched answers to whatever it
 * had sent last, so one reply arriving after its timeout was credited to the NEXT probe, and every
 * probe after that read the previous one's answer -- a single late packet turned into a run of
 * false losses.
 */
interface UdpProbeProtocol {
    val name: String
    fun newToken(): ByteArray
    fun build(token: ByteArray): ByteArray
    /** The token inside [msg] (first [length] bytes), or null if it is not an answer of ours. */
    fun match(msg: ByteArray, length: Int): ByteArray?
}

private val random = SecureRandom()

/** STUN binding request (RFC 5389). The 96-bit transaction id comes back verbatim. */
object StunProtocol : UdpProbeProtocol {
    private const val MAGIC_COOKIE = 0x2112A442

    override val name = "stun"

    override fun newToken(): ByteArray = ByteArray(12).also { random.nextBytes(it) }

    override fun build(token: ByteArray): ByteArray {
        require(token.size == 12)
        val b = ByteArray(20)
        b[0] = 0x00; b[1] = 0x01                     // binding request
        b[2] = 0x00; b[3] = 0x00                     // no attributes
        b[4] = 0x21; b[5] = 0x12; b[6] = 0xA4.toByte(); b[7] = 0x42
        System.arraycopy(token, 0, b, 8, 12)
        return b
    }

    override fun match(msg: ByteArray, length: Int): ByteArray? {
        if (length < 20) return null
        // Top two bits of a STUN message type are always zero; anything else is not STUN.
        if (msg[0].toInt() and 0xC0 != 0) return null
        val cookie = ((msg[4].toInt() and 0xFF) shl 24) or ((msg[5].toInt() and 0xFF) shl 16) or
            ((msg[6].toInt() and 0xFF) shl 8) or (msg[7].toInt() and 0xFF)
        if (cookie != MAGIC_COOKIE) return null
        return msg.copyOfRange(8, 20)
    }
}

/**
 * A plain UDP echo: the AWS GameLift ping beacons (`gamelift-ping.<region>.api.aws:7770`) send
 * back exactly what they receive.
 *
 * This is the best target the booster has: a real UDP echo in the same AWS region the Middle East
 * match servers of Call of Duty Mobile and PUBG Mobile run in (Bahrain, me-south-1), answering at
 * a game's cadence. Verified 2026-09-24: the Frankfurt beacon echoed every packet verbatim.
 */
object EchoProtocol : UdpProbeProtocol {
    private val PREFIX = "mlmgb:".toByteArray(Charsets.US_ASCII)

    override val name = "echo"

    override fun newToken(): ByteArray = ByteArray(8).also { random.nextBytes(it) }

    override fun build(token: ByteArray): ByteArray = PREFIX + token

    override fun match(msg: ByteArray, length: Int): ByteArray? {
        if (length != PREFIX.size + 8) return null
        for (i in PREFIX.indices) if (msg[i] != PREFIX[i]) return null
        return msg.copyOfRange(PREFIX.size, PREFIX.size + 8)
    }
}

/**
 * SOCKS5 (RFC 1928) framing: just enough to CONNECT and to UDP ASSOCIATE through the Aether
 * engine's local proxy, which is how the WARP path is measured without taking the VPN.
 */
object Socks5Codec {
    const val VERSION: Byte = 5
    private const val CMD_CONNECT: Byte = 1
    private const val CMD_UDP_ASSOCIATE: Byte = 3
    private const val ATYP_V4: Byte = 1
    private const val ATYP_DOMAIN: Byte = 3
    private const val ATYP_V6: Byte = 4

    /** "Version 5, one method: no authentication." */
    val GREETING = byteArrayOf(VERSION, 1, 0)

    fun greetingAccepted(reply: ByteArray): Boolean =
        reply.size >= 2 && reply[0] == VERSION && reply[1] == 0.toByte()

    fun connectRequest(address: InetAddress, port: Int): ByteArray = request(CMD_CONNECT, address, port)

    fun connectRequest(domain: String, port: Int): ByteArray {
        val name = domain.toByteArray(Charsets.US_ASCII)
        require(name.size in 1..255)
        return byteArrayOf(VERSION, CMD_CONNECT, 0, ATYP_DOMAIN, name.size.toByte()) + name + portBytes(port)
    }

    /**
     * UDP ASSOCIATE with an unspecified client address: "I will send from wherever I send from".
     * The proxy answers with the relay address datagrams must go to.
     */
    fun udpAssociateRequest(): ByteArray =
        byteArrayOf(VERSION, CMD_UDP_ASSOCIATE, 0, ATYP_V4, 0, 0, 0, 0, 0, 0)

    private fun request(cmd: Byte, address: InetAddress, port: Int): ByteArray = when (address) {
        is Inet4Address -> byteArrayOf(VERSION, cmd, 0, ATYP_V4) + address.address + portBytes(port)
        is Inet6Address -> byteArrayOf(VERSION, cmd, 0, ATYP_V6) + address.address + portBytes(port)
        else -> error("unsupported address type")
    }

    /**
     * The fixed-size head of a reply: VER REP RSV ATYP. Returns the reply code (0 = success) and
     * how many more bytes the bound address + port take, or null for a malformed head.
     */
    fun replyHead(head: ByteArray): Pair<Int, Int>? {
        if (head.size < 4 || head[0] != VERSION) return null
        val rep = head[1].toInt() and 0xFF
        val rest = when (head[3]) {
            ATYP_V4 -> 4 + 2
            ATYP_V6 -> 16 + 2
            ATYP_DOMAIN -> -1 // length byte follows; callers read it separately
            else -> return null
        }
        return rep to rest
    }

    /** The bound address and port from the tail of a reply whose head said ATYP v4/v6. */
    fun boundAddress(tail: ByteArray): Pair<InetAddress, Int>? {
        return when (tail.size) {
            6 -> InetAddress.getByAddress(tail.copyOfRange(0, 4)) to port(tail, 4)
            18 -> InetAddress.getByAddress(tail.copyOfRange(0, 16)) to port(tail, 16)
            else -> null
        }
    }

    /** A datagram for the relay: RSV RSV FRAG ATYP DST.ADDR DST.PORT DATA. */
    fun wrapUdp(address: InetAddress, port: Int, payload: ByteArray): ByteArray {
        val head = when (address) {
            is Inet4Address -> byteArrayOf(0, 0, 0, ATYP_V4) + address.address
            is Inet6Address -> byteArrayOf(0, 0, 0, ATYP_V6) + address.address
            else -> error("unsupported address type")
        }
        return head + portBytes(port) + payload
    }

    /** The payload of a datagram from the relay, as (offset, length) into [msg], or null. */
    fun unwrapUdp(msg: ByteArray, length: Int): Pair<Int, Int>? {
        if (length < 4 || msg[2] != 0.toByte()) return null // fragments are never used here
        val headLen = when (msg[3]) {
            ATYP_V4 -> 4 + 4 + 2
            ATYP_V6 -> 4 + 16 + 2
            ATYP_DOMAIN -> if (length < 5) return null else 4 + 1 + (msg[4].toInt() and 0xFF) + 2
            else -> return null
        }
        if (length < headLen) return null
        return headLen to (length - headLen)
    }

    private fun portBytes(port: Int) = byteArrayOf((port shr 8 and 0xFF).toByte(), (port and 0xFF).toByte())

    private fun port(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
}

/**
 * Reads `/system/bin/ping` output, the fallback when an ICMP datagram socket cannot be opened.
 *
 *     64 bytes from 8.8.8.8: icmp_seq=3 ttl=117 time=102 ms
 *     4 packets transmitted, 4 received, 0% packet loss, time 902ms
 */
object PingOutputParser {
    private val REPLY = Regex("""icmp_seq=(\d+)\s.*?time[=<]([\d.]+)\s*ms""")
    private val SUMMARY = Regex("""(\d+) packets transmitted, (\d+) (?:packets )?received""")

    data class Parsed(val samples: List<RttSample>, val transmitted: Int?, val received: Int?)

    fun parse(output: String): Parsed {
        val samples = REPLY.findAll(output).map { m ->
            RttSample(seq = m.groupValues[1].toInt(), rttMs = m.groupValues[2].toDouble())
        }.distinctBy { it.seq }.toList()
        val summary = SUMMARY.find(output)
        return Parsed(
            samples = samples,
            transmitted = summary?.groupValues?.get(1)?.toIntOrNull(),
            received = summary?.groupValues?.get(2)?.toIntOrNull(),
        )
    }
}
