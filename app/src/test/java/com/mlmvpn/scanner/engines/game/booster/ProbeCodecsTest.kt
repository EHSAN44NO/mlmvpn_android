package com.mlmvpn.scanner.engines.game.booster

import com.mlmvpn.scanner.engines.game.booster.probe.EchoProtocol
import com.mlmvpn.scanner.engines.game.booster.probe.PingOutputParser
import com.mlmvpn.scanner.engines.game.booster.probe.Socks5Codec
import com.mlmvpn.scanner.engines.game.booster.probe.StunProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class ProbeCodecsTest {

    @Test
    fun `stun request carries the cookie and the answer returns our token`() {
        val token = StunProtocol.newToken()
        val req = StunProtocol.build(token)
        assertEquals(20, req.size)
        assertEquals(0x01, req[1].toInt())
        // A binding success response (0x0101) echoing the same transaction id.
        val resp = req.copyOf(32).also { it[0] = 0x01; it[1] = 0x01; it[3] = 12 }
        assertArrayEquals(token, StunProtocol.match(resp, 32))
    }

    @Test
    fun `stun rejects a wrong cookie, a short packet and non-stun bits`() {
        val req = StunProtocol.build(StunProtocol.newToken())
        assertNull(StunProtocol.match(req, 19))
        assertNull(StunProtocol.match(req.copyOf().also { it[4] = 0 }, 20))
        assertNull(StunProtocol.match(req.copyOf().also { it[0] = 0xC0.toByte() }, 20))
    }

    @Test
    fun `echo matches only its own exact payload`() {
        val token = EchoProtocol.newToken()
        val pkt = EchoProtocol.build(token)
        assertArrayEquals(token, EchoProtocol.match(pkt, pkt.size))
        assertNull(EchoProtocol.match(pkt, pkt.size - 1))
        assertNull(EchoProtocol.match("ping 5b6c8a83875c653b".toByteArray(), 20))
    }

    @Test
    fun `socks udp wrap and unwrap are inverse`() {
        val payload = byteArrayOf(9, 8, 7)
        val wrapped = Socks5Codec.wrapUdp(InetAddress.getByName("15.185.176.195"), 7770, payload)
        assertEquals(10 + 3, wrapped.size)
        val (off, len) = Socks5Codec.unwrapUdp(wrapped, wrapped.size)!!
        assertArrayEquals(payload, wrapped.copyOfRange(off, off + len))
    }

    @Test
    fun `socks reply head and bound address parse`() {
        val head = byteArrayOf(5, 0, 0, 1)
        assertEquals(0 to 6, Socks5Codec.replyHead(head))
        val (addr, port) = Socks5Codec.boundAddress(byteArrayOf(127, 0, 0, 1, 0x51, 0x2A))!!
        assertEquals("127.0.0.1", addr.hostAddress)
        assertEquals(20778, port)
        assertNull(Socks5Codec.replyHead(byteArrayOf(4, 0, 0, 1)))
    }

    @Test
    fun `ping output parses replies and the summary`() {
        val out = """
            PING 8.8.8.8 (8.8.8.8) 56(84) bytes of data.
            64 bytes from 8.8.8.8: icmp_seq=1 ttl=117 time=102 ms
            64 bytes from 8.8.8.8: icmp_seq=2 ttl=117 time=110.5 ms
            64 bytes from 8.8.8.8: icmp_seq=4 ttl=117 time=117 ms

            --- 8.8.8.8 ping statistics ---
            4 packets transmitted, 3 received, 25% packet loss, time 902ms
        """.trimIndent()
        val p = PingOutputParser.parse(out)
        assertEquals(3, p.samples.size)
        assertEquals(110.5, p.samples[1].rttMs, 1e-9)
        assertEquals(4, p.transmitted)
        assertEquals(3, p.received)
    }

    @Test
    fun `ping output with no answers yields nothing, not a crash`() {
        val p = PingOutputParser.parse("6 packets transmitted, 0 received, 100% packet loss, time 1612ms")
        assertTrue(p.samples.isEmpty())
        assertNotNull(p.transmitted)
    }
}
