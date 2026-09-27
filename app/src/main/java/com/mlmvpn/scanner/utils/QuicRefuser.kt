package com.mlmvpn.scanner.utils

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Makes QUIC fail at once instead of slowly, so apps fall straight back to TCP.
 *
 * ## Why this exists
 *
 * A Cloudflare Worker has no UDP, so no config from a panel (BPB, EDG, MLM, Nahan) can carry
 * QUIC. The generated config already sent QUIC to Xray's `blackhole`, which reads like a refusal
 * and is not one: blackhole swallows the packets and answers nothing, and Xray's TUN never sends
 * the ICMP "port unreachable" a real refusal produces (`proxy/tun` only relays payloads). A client
 * cannot tell that silence from a slow network, so it waits out its own timer before trying TCP.
 * Chromium's stack -- Cronet, which Gemini, Play Store, Play services and YouTube all use -- waits
 * the longest, and some of those apps never get past it: Gemini's backend
 * (`robinfrontend-pa.googleapis.com`) tries QUIC first and the app just hangs.
 *
 * NetDoctor (Dr.Net) fixed the same thing in sing-box with `reject` + `method: default`, which
 * answers UDP with an ICMP port-unreachable. Xray has no reject, but it can relay a UDP answer: a
 * `freedom` outbound redirected here hands this socket every QUIC packet, and it answers each one
 * with a QUIC **Version Negotiation** packet offering no version the client speaks. RFC 9000 §6.2:
 * a client that receives one MUST abandon the attempt. Chromium closes the connection with
 * `QUIC_INVALID_VERSION` on the spot, marks QUIC broken for that host and carries on over TCP.
 *
 * Measured with desktop Chrome forced onto QUIC against a copy of this responder: answered, the
 * attempt died about 20 ms after the first packet (`QUIC_SESSION_VERSION_NEGOTIATION_PACKET_RECEIVED`
 * then `QUIC_INVALID_VERSION` in the net log); unanswered, like blackhole, Chrome spent 4 s
 * retransmitting first.
 *
 * ## Why the answer reaches the right app
 *
 * With `redirect` set, freedom sends every packet here and does NOT stamp the answer with this
 * socket's address (`PacketReader.IsOverridden` in xray-core), so the TUN writes it back with the
 * flow's ORIGINAL destination as the source -- the address the app's socket is connected to, so it
 * is accepted. Each app flow gets its own freedom socket, so answering the sender answers that flow.
 */
object QuicRefuser {

    private const val TAG = "QuicRefuser"

    /** Room for a whole Initial datagram. Only the first ~50 bytes are read; longer ones are cut. */
    private const val RECEIVE_BUFFER = 2048

    /**
     * The one version this answer offers. `0x?a?a?a?a` is the pattern RFC 9000 §15 reserves so
     * that no implementation ever speaks it -- which is the point: a client finds nothing in
     * common and gives up, rather than retrying with a version it happens to share.
     */
    private val OFFERED_VERSION = byteArrayOf(0x1A, 0x2A, 0x3A, 0x4A)

    @Volatile
    private var socket: DatagramSocket? = null

    /**
     * The loopback port that answers QUIC with a refusal, opening it on first use; null when it
     * cannot be opened, in which case the caller keeps the old silent drop.
     *
     * Kept open for the life of the process once opened. It is one idle thread blocked on a
     * receive, and closing it under a running core would turn every refusal back into silence.
     */
    fun port(): Int? {
        socket?.let { if (!it.isClosed) return it.localPort }
        synchronized(this) {
            socket?.let { if (!it.isClosed) return it.localPort }
            return try {
                val bound = DatagramSocket(
                    InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
                )
                socket = bound
                Thread({ serve(bound) }, "quic-refuser").apply { isDaemon = true }.start()
                bound.localPort
            } catch (e: Exception) {
                android.util.Log.w(TAG, "could not open the QUIC refuser: ${e.message}")
                null
            }
        }
    }

    private fun serve(bound: DatagramSocket) {
        val buffer = ByteArray(RECEIVE_BUFFER)
        val packet = DatagramPacket(buffer, buffer.size)
        while (!bound.isClosed) {
            try {
                packet.length = buffer.size
                bound.receive(packet)
                val answer = versionNegotiation(buffer, packet.length) ?: continue
                bound.send(DatagramPacket(answer, answer.size, packet.socketAddress))
            } catch (_: Exception) {
                if (bound.isClosed) return
                // One failed send -- a flow the core has already let go of -- is no reason to
                // stop answering every other app.
            }
        }
    }

    /**
     * A Version Negotiation packet answering [datagram], or null when there is nothing to answer.
     *
     * Only a long-header packet names the connection IDs an answer must echo, so a short-header
     * packet (a connection that was already up before the tunnel was) gets nothing. Neither does
     * a Version Negotiation packet -- answering one with another is how two of these would talk
     * to each other forever.
     *
     * RFC 9000 §17.2.1: the answer's destination ID is the packet's source ID and its source ID
     * the packet's destination ID. Chromium checks both, and drops an answer that swaps them.
     */
    internal fun versionNegotiation(datagram: ByteArray, length: Int): ByteArray? {
        val size = minOf(length, datagram.size)
        if (size < 7) return null
        if (datagram[0].toInt() and 0x80 == 0) return null
        if (datagram[1].toInt() or datagram[2].toInt() or datagram[3].toInt() or datagram[4].toInt() == 0) {
            return null
        }
        val dcidLength = datagram[5].toInt() and 0xFF
        val scidLengthAt = 6 + dcidLength
        if (scidLengthAt >= size) return null
        val scidLength = datagram[scidLengthAt].toInt() and 0xFF
        val scidAt = scidLengthAt + 1
        if (scidAt + scidLength > size) return null

        val answer = ByteArray(1 + 4 + 1 + scidLength + 1 + dcidLength + OFFERED_VERSION.size)
        var at = 0
        // Header form set, and the fixed bit too: RFC 9000 asks for it so the packet still looks
        // like QUIC to anything demultiplexing it. The low bits are arbitrary by definition.
        answer[at++] = (0xC0 or (System.nanoTime().toInt() and 0x3F)).toByte()
        at += 4 // version 0 marks a Version Negotiation packet; the array is already zeroed
        answer[at++] = scidLength.toByte()
        System.arraycopy(datagram, scidAt, answer, at, scidLength)
        at += scidLength
        answer[at++] = dcidLength.toByte()
        System.arraycopy(datagram, 6, answer, at, dcidLength)
        at += dcidLength
        System.arraycopy(OFFERED_VERSION, 0, answer, at, OFFERED_VERSION.size)
        return answer
    }
}
