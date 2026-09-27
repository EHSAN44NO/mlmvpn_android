package com.mlmvpn.scanner.openvpn

/** OpenVPN v2 control reset, only for profiles without authenticated control packets. */
object OpenVpnProbePacket {
    fun request(session: ByteArray): ByteArray {
        require(session.size == 8)
        return byteArrayOf(0x38) + session + byteArrayOf(0, 0, 0, 0, 0)
    }

    fun accepts(packet: ByteArray, session: ByteArray): Boolean {
        if (session.size != 8 || packet.size < 26 || packet[0].toInt() and 0xff != 0x40) return false
        val count = packet[9].toInt() and 0xff
        if (count !in 1..32 || packet.size < 10 + count * 4 + 12) return false
        val offset = 10 + count * 4
        return packet.copyOfRange(offset, offset + 8).contentEquals(session) &&
            (0 until count).any { i -> (10 + i * 4 until 14 + i * 4).all { packet[it] == 0.toByte() } }
    }
}
