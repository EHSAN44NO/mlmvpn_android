package com.mlmvpn.scanner.engines.mae.control

import java.io.ByteArrayOutputStream

/**
 * The few bytes of protobuf + gRPC framing the live-routing spike needs, by hand. No protobuf or
 * gRPC library: the spike calls exactly one method, and a dependency that size is not worth it
 * until the method is proven to work on a phone.
 *
 * `xray.app.router.command.OverrideBalancerTargetRequest { string balancerTag = 1; string target = 2; }`
 */
object GrpcWire {
    const val OVERRIDE_BALANCER_PATH = "/xray.app.router.command.RoutingService/OverrideBalancerTarget"

    fun overrideBalancerTarget(balancerTag: String, target: String): ByteArray =
        ByteArrayOutputStream().apply {
            stringField(this, 1, balancerTag)
            stringField(this, 2, target)
        }.toByteArray()

    /** gRPC length-prefixed message: 1 byte "compressed" flag, 4 bytes big-endian length, bytes. */
    fun frame(message: ByteArray): ByteArray {
        val n = message.size
        return byteArrayOf(0, (n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + message
    }

    private fun stringField(out: ByteArrayOutputStream, field: Int, value: String) {
        if (value.isEmpty()) return // proto3 default: omitted
        val bytes = value.toByteArray(Charsets.UTF_8)
        varint(out, (field shl 3 or 2).toLong())
        varint(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun varint(out: ByteArrayOutputStream, v: Long) {
        var x = v
        while (x and 0x7FL.inv() != 0L) {
            out.write(((x and 0x7F) or 0x80).toInt())
            x = x ushr 7
        }
        out.write(x.toInt())
    }
}
