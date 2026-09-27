package com.mlmvpn.scanner.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * The Version Negotiation answer [QuicRefuser] gives, byte for byte.
 *
 * The reference is what desktop Chrome sent and accepted when this was measured: a QUIC v1
 * Initial with an 8-byte destination ID and no source ID, answered with a 19-byte packet that
 * made Chrome close the attempt with QUIC_INVALID_VERSION ~20 ms after its first packet.
 */
class QuicRefuserTest {

    private val chromeDcid = byteArrayOf(0x64, 0x80.toByte(), 0x65, 0xC1.toByte(), 0x20, 0x01, 0x0E, 0x8A.toByte())

    /** A 1250-byte Initial the way Chrome sends it: long header, v1, DCID of 8, SCID of 0. */
    private fun chromeInitial(): ByteArray = ByteArray(1250).also { datagram ->
        byteArrayOf(0xC6.toByte(), 0, 0, 0, 1, 8).copyInto(datagram)
        chromeDcid.copyInto(datagram, 6)
        datagram[14] = 0 // SCID length
    }

    @Test
    fun `answers Chrome's Initial with the packet Chrome accepted`() {
        val answer = QuicRefuser.versionNegotiation(chromeInitial(), 1250)
        assertNotNull(answer)
        answer!!
        assertEquals(19, answer.size)
        assertTrue("long header", answer[0].toInt() and 0x80 != 0)
        assertTrue("fixed bit", answer[0].toInt() and 0x40 != 0)
        assertArrayEquals("version 0 marks Version Negotiation", ByteArray(4), answer.copyOfRange(1, 5))
        assertEquals("destination = the client's (empty) source ID", 0, answer[5].toInt())
        assertEquals("source = the client's destination ID", 8, answer[6].toInt())
        assertArrayEquals(chromeDcid, answer.copyOfRange(7, 15))
        assertArrayEquals(byteArrayOf(0x1A, 0x2A, 0x3A, 0x4A), answer.copyOfRange(15, 19))
    }

    @Test
    fun `swaps both connection IDs when the client sends a source ID`() {
        val dcid = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val scid = byteArrayOf(11, 12, 13, 14, 15)
        val datagram = byteArrayOf(0xC0.toByte(), 0, 0, 0, 1, dcid.size.toByte()) + dcid +
            byteArrayOf(scid.size.toByte()) + scid + ByteArray(1200)

        val answer = QuicRefuser.versionNegotiation(datagram, datagram.size)!!

        assertEquals(1 + 4 + 1 + scid.size + 1 + dcid.size + 4, answer.size)
        assertEquals(scid.size, answer[5].toInt())
        assertArrayEquals(scid, answer.copyOfRange(6, 6 + scid.size))
        assertEquals(dcid.size, answer[6 + scid.size].toInt())
        assertArrayEquals(dcid, answer.copyOfRange(7 + scid.size, 7 + scid.size + dcid.size))
    }

    @Test
    fun `leaves alone what it must not answer`() {
        // Short header: a connection that was up before the tunnel; no IDs to echo.
        assertNull(QuicRefuser.versionNegotiation(byteArrayOf(0x40, 1, 2, 3, 4, 5, 6, 7, 8, 9), 10))
        // A Version Negotiation packet itself: two refusers must never talk to each other.
        val vn = byteArrayOf(0xC0.toByte(), 0, 0, 0, 0, 0, 8) + chromeDcid + byteArrayOf(0x1A, 0x2A, 0x3A, 0x4A)
        assertNull(QuicRefuser.versionNegotiation(vn, vn.size))
        // Too short to hold a long header.
        assertNull(QuicRefuser.versionNegotiation(byteArrayOf(0xC0.toByte(), 0, 0, 0, 1, 0), 6))
    }

    @Test
    fun `refuses to read past a connection ID that does not fit`() {
        // DCID claims 20 bytes, the datagram carries 10.
        val cut = byteArrayOf(0xC0.toByte(), 0, 0, 0, 1, 20) + ByteArray(10)
        assertNull(QuicRefuser.versionNegotiation(cut, cut.size))
        // The buffer is bigger than what arrived: only [length] counts, not the zeros after it.
        val buffer = ByteArray(2048)
        byteArrayOf(0xC0.toByte(), 0, 0, 0, 1, 8).copyInto(buffer)
        assertNull(QuicRefuser.versionNegotiation(buffer, 10))
    }

    @Test
    fun `the running responder answers over a real socket`() {
        val port = QuicRefuser.port()
        assertNotNull(port)
        assertEquals("opened once, then reused", port, QuicRefuser.port())

        DatagramSocket().use { client ->
            client.soTimeout = 2000
            val initial = chromeInitial()
            val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
            client.send(DatagramPacket(initial, initial.size, loopback, port!!))
            val reply = DatagramPacket(ByteArray(64), 64)
            client.receive(reply)
            assertArrayEquals(
                QuicRefuser.versionNegotiation(initial, initial.size)!!.copyOfRange(1, 19),
                reply.data.copyOfRange(1, reply.length),
            )
        }
    }
}
