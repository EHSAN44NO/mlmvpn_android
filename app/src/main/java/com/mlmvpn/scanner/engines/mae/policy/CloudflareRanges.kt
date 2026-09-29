package com.mlmvpn.scanner.engines.mae.policy

import java.math.BigInteger
import java.net.InetAddress

/**
 * Cloudflare's published address ranges (cloudflare.com/ips). An exit seen from one of these is
 * a Worker, a WARP or a Cloudflare-fronted proxy: whatever country an IP database gives it, some
 * services (Gemini) refuse it.
 */
object CloudflareRanges {
    private val CIDRS = listOf(
        "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22", "141.101.64.0/18",
        "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20", "197.234.240.0/22", "198.41.128.0/17",
        "162.158.0.0/15", "104.16.0.0/13", "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
        "2400:cb00::/32", "2606:4700::/32", "2803:f800::/32", "2405:b500::/32", "2405:8100::/32",
        "2a06:98c0::/29", "2c0f:f248::/32",
        // Not in the published list, but Cloudflare egress (WARP and Workers leaving to the internet).
        "104.28.0.0/16", "2a09:bac0::/29",
    ).map { c ->
        val (a, len) = c.split('/')
        val bytes = InetAddress.getByName(a).address
        Triple(bytes.size, BigInteger(1, bytes), len.toInt())
    }

    /** True for a literal IPv4/IPv6 address inside Cloudflare's ranges; false for anything else. */
    fun contains(ip: String): Boolean {
        if (ip.isBlank() || ip.any { it.isLetter() && it.lowercaseChar() !in 'a'..'f' }) return false
        val bytes = runCatching { InetAddress.getByName(ip.trim('[', ']')).address }.getOrNull() ?: return false
        val v = BigInteger(1, bytes)
        val bits = bytes.size * 8
        return CIDRS.any { (size, net, len) -> size == bytes.size && v.shiftRight(bits - len) == net.shiftRight(bits - len) }
    }
}
