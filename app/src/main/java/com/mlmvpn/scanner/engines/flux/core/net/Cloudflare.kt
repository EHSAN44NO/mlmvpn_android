package com.mlmvpn.scanner.engines.flux.core.net

import com.mlmvpn.scanner.engines.flux.core.model.Family
import java.math.BigInteger
import java.net.InetAddress
import kotlin.random.Random

/**
 * Cloudflare's published address ranges (cloudflare.com/ips), and the few helpers FLUX needs
 * around them: is this address Cloudflare's, and which edges to try for a CDN-fronted node.
 *
 * Why this matters for FLUX: on many Iranian networks Cloudflare's edges are cut. A node dialled at
 * a Cloudflare address is then dead on arrival, however healthy its origin -- so the race asks the
 * network first ([com.mlmvpn.scanner.engines.flux.core.model.NetVerdict]) and leaves every such
 * candidate out when the answer is no.
 */
object Cloudflare {

    val V4_RANGES = listOf(
        "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22", "141.101.64.0/18",
        "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20", "197.234.240.0/22", "198.41.128.0/17",
        "162.158.0.0/15", "104.16.0.0/13", "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
    )
    val V6_RANGES = listOf(
        "2400:cb00::/32", "2606:4700::/32", "2803:f800::/32", "2405:b500::/32", "2405:8100::/32",
        "2a06:98c0::/29", "2c0f:f248::/32",
    )

    /**
     * Where edges are sampled from: the ranges the anycast HTTP edge actually answers on. The
     * others (Magic Transit, Spectrum, the 198.41.128/17 tunnel range) are Cloudflare's but do not
     * serve a Worker or a proxied site.
     */
    private val V4_EDGE_POOLS = listOf("104.16.0.0/13", "104.24.0.0/14", "172.64.0.0/13", "188.114.96.0/20", "162.158.0.0/15", "141.101.64.0/18", "108.162.192.0/18", "173.245.48.0/20")
    /**
     * IPv6: the /48s that served Workers on a phone in Iran (the app's own measurement, kept in
     * `data/CfFamily.kt` V6_PREFIXES: 48 of 54 random addresses answered). Any interface id inside
     * one reaches the same edge. Random addresses across whole /32s, which this used before, are
     * mostly unrouted -- they made a working Cloudflare-over-IPv6 look cut.
     */
    val V6_EDGE_PREFIXES = listOf(
        "2606:4700:3030", "2606:4700:3031", "2606:4700:3032", "2606:4700:3033",
        "2606:4700:3034", "2606:4700:3035", "2606:4700:3036", "2606:4700:3037",
        "2606:4700:10", "2606:4700:20", "2606:4700:7", "2606:4700:4400",
        "2803:f800:50", "2a06:98c1:3120", "2a06:98c1:3121", "2400:cb00:2049",
    )

    private data class Cidr(val base: BigInteger, val bits: Int, val prefix: Int) {
        fun contains(a: BigInteger): Boolean = a.shiftRight(bits - prefix) == base.shiftRight(bits - prefix)
    }

    private val v4 = V4_RANGES.map(::cidr)
    private val v6 = V6_RANGES.map(::cidr)

    /** True for a literal address inside Cloudflare's ranges. Domain names are not resolved here. */
    fun isCloudflare(address: String): Boolean {
        val fam = Family.ofLiteral(address) ?: return false
        val n = toBig(address) ?: return false
        return (if (fam == Family.V4) v4 else v6).any { it.contains(n) }
    }

    /**
     * [count] random edge addresses of [family], for a first look at a network FLUX has not learned
     * yet. Spread across pools so one blocked /16 cannot sink the whole sample.
     */
    fun sampleEdges(family: Family, count: Int, random: Random = Random.Default): List<String> {
        if (family == Family.V6) {
            val start = random.nextInt(V6_EDGE_PREFIXES.size)
            return (0 until count).map { i ->
                val p = V6_EDGE_PREFIXES[(start + i) % V6_EDGE_PREFIXES.size]
                val groups = 8 - p.split(':').size
                compressV6(p + ":" + (0 until groups).joinToString(":") { Integer.toHexString(1 + random.nextInt(0xfffe)) })
            }.distinct()
        }
        val pools = V4_EDGE_POOLS.map(::cidr)
        val out = LinkedHashSet<String>()
        var guard = 0
        while (out.size < count && guard++ < count * 20) {
            val pool = pools[random.nextInt(pools.size)]
            val hostBits = pool.bits - pool.prefix
            // Inside a /64 is plenty of variety for IPv6, and keeps the low host part non-zero.
            val take = minOf(hostBits, if (family == Family.V4) hostBits else 64)
            var offset = BigInteger(take, java.util.Random(random.nextLong()))
            if (family == Family.V4) {
                val last = offset.toInt() and 0xff
                // .0 and .255 are not hosts: flipping the low bit stays inside the pool.
                if (last == 0 || last == 255) offset = offset.xor(BigInteger.ONE)
            } else if (offset.signum() == 0) offset = BigInteger.ONE
            out.add(fromBig(pool.base.add(offset), family))
        }
        return out.toList()
    }

    private fun cidr(s: String): Cidr {
        val (addr, p) = s.split('/')
        val fam = Family.ofLiteral(addr)!!
        return Cidr(toBig(addr)!!, if (fam == Family.V4) 32 else 128, p.toInt())
    }

    private fun toBig(address: String): BigInteger? = try {
        // Literal only: InetAddress.getByName on a literal never touches the network.
        if (Family.ofLiteral(address) == null) null else BigInteger(1, InetAddress.getByName(address).address)
    } catch (_: Exception) { null }

    private fun fromBig(n: BigInteger, family: Family): String {
        val len = if (family == Family.V4) 4 else 16
        val raw = n.toByteArray()
        val bytes = ByteArray(len)
        val copy = minOf(len, raw.size)
        System.arraycopy(raw, raw.size - copy, bytes, len - copy, copy)
        return InetAddress.getByAddress(bytes).hostAddress.let { if (family == Family.V6) compressV6(it) else it }
    }

    /** `2606:4700:0:0:0:0:0:1` -> `2606:4700::1`, the form Xray and the logs expect. */
    private fun compressV6(s: String): String {
        val groups = s.split(':').map { it.trimStart('0').ifEmpty { "0" } }
        var bestStart = -1; var bestLen = 0; var i = 0
        while (i < groups.size) {
            if (groups[i] == "0") {
                var j = i
                while (j < groups.size && groups[j] == "0") j++
                if (j - i > bestLen) { bestStart = i; bestLen = j - i }
                i = j
            } else i++
        }
        if (bestLen < 2) return groups.joinToString(":")
        val head = groups.subList(0, bestStart).joinToString(":")
        val tail = groups.subList(bestStart + bestLen, groups.size).joinToString(":")
        return "$head::$tail"
    }
}
