package com.mlmvpn.scanner.engines.flux.core.net

import com.mlmvpn.scanner.engines.flux.core.model.Family
import java.math.BigInteger
import java.net.InetAddress

/**
 * Addresses a filtered resolver hands out instead of the real one. Iran's resolvers answer a
 * blocked name with the block page (`10.10.34.x`, and over IPv6 `2001:4188:2:600::/64` -- a device
 * log showed Hysteria2 servers "resolving" there) or with a private address. A node whose name
 * resolves to one of these is not dead; its name was poisoned, and DoH is asked instead.
 */
object Poison {

    private val V6_BLOCK = BigInteger(1, InetAddress.getByName("2001:4188:2:600::").address).shiftRight(64)

    fun isPoisoned(ip: String): Boolean {
        val fam = Family.ofLiteral(ip) ?: return false
        val a = runCatching { InetAddress.getByName(ip) }.getOrNull() ?: return true
        if (a.isSiteLocalAddress || a.isLoopbackAddress || a.isAnyLocalAddress || a.isLinkLocalAddress) return true
        return when (fam) {
            Family.V4 -> ip.startsWith("10.10.34.") || ip.startsWith("0.")
            Family.V6 -> {
                val b = a.address
                (b[0].toInt() and 0xfe) == 0xfc || BigInteger(1, b).shiftRight(64) == V6_BLOCK
            }
        }
    }
}
