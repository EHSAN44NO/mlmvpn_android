package com.mlmvpn.scanner.engines.game.booster

import com.mlmvpn.scanner.engines.game.booster.probe.EchoProtocol
import com.mlmvpn.scanner.engines.game.booster.probe.PathStats
import com.mlmvpn.scanner.engines.game.booster.probe.ProbeStats
import com.mlmvpn.scanner.engines.game.booster.probe.RttSample
import com.mlmvpn.scanner.engines.game.booster.probe.SliceAccumulator
import com.mlmvpn.scanner.engines.game.booster.probe.TrainResult
import com.mlmvpn.scanner.engines.game.booster.probe.TrainSchedule
import com.mlmvpn.scanner.engines.game.booster.probe.UdpVia
import com.mlmvpn.scanner.engines.game.booster.probe.udpTrainFlows
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections

class MultiFlowTrainTest {

    @Test
    fun `ten flows at two packets a second give a game's cadence and respect the beacon`() {
        val s = TrainSchedule.of(flows = 10, ppsPerFlow = 2.0, durationMs = 2000)
        assertEquals(40, s.count)
        assertEquals(2.0, s.ppsPerFlow(), 0.01)
        // Round robin: no flow carries two probes in a row, each carries its share.
        assertTrue((1 until s.count).none { s.flowOf(it) == s.flowOf(it - 1) })
        assertEquals(List(10) { 4 }, (0 until s.count).groupingBy { s.flowOf(it) }.eachCount().values.toList())
    }

    @Test
    fun `slices laid end to end never share a sequence number`() {
        // Two trains whose first probes were trimmed as warm-up and one of which lost a probe
        // before it went out: their samples must stay in send order when merged.
        fun result(rtts: List<Double>, sent: Int, span: Int) =
            TrainResult("t", "echo", ProbeStats.stats(rtts.mapIndexed { i, r -> RttSample(i, r) }, sent),
                rtts.mapIndexed { i, r -> RttSample(i, r) }, sent, null, seqSpan = span)
        val acc = SliceAccumulator()
        acc.add(result(listOf(50.0, 52.0, 51.0), sent = 3, span = 4))
        acc.add(result(listOf(80.0, 81.0), sent = 2, span = 2))
        val seqs = acc.samples.map { it.seq }
        assertEquals(seqs.distinct().size, seqs.size)
        assertEquals(listOf(0, 1, 2, 4, 5), seqs)
        assertEquals(5, acc.sent)
        assertEquals(listOf(51, 81), acc.sliceP50)
    }

    @Test
    fun `a multi-flow train against a local echo uses every flow and loses nothing`() = runBlocking {
        val server = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val sources = Collections.synchronizedSet(HashSet<Int>())
        val echo = Thread {
            val buf = ByteArray(2048)
            val p = DatagramPacket(buf, buf.size)
            try {
                while (!server.isClosed) {
                    p.setData(buf)
                    server.receive(p)
                    sources += p.port
                    server.send(DatagramPacket(p.data, p.length, p.socketAddress))
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }
        try {
            val r = udpTrainFlows(
                InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort), EchoProtocol,
                List(4) { UdpVia.Direct(null) }, ppsPerFlow = 5.0, durationMs = 600, timeoutMs = 300,
            )
            assertEquals(12, r.sent)
            assertEquals(12, r.stats.n)
            assertEquals(0.0, r.stats.loss, 0.0)
            assertEquals(4, sources.size)             // four flows, four source ports
            assertEquals(12, r.seqSpan)
            assertEquals((0 until 12).toList(), r.samples.map { it.seq })
        } finally {
            server.close()
            echo.join(500)
        }
    }

    @Test
    fun `warm-up is trimmed and the numbers restart after it`() = runBlocking {
        val server = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val echo = Thread {
            val buf = ByteArray(2048)
            val p = DatagramPacket(buf, buf.size)
            try {
                while (!server.isClosed) {
                    p.setData(buf); server.receive(p)
                    server.send(DatagramPacket(p.data, p.length, p.socketAddress))
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }
        try {
            val r = udpTrainFlows(
                InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort), EchoProtocol,
                List(2) { UdpVia.Direct(null) }, ppsPerFlow = 5.0, durationMs = 1000, warmupMs = 300, timeoutMs = 300,
            )
            assertTrue("sent ${r.sent}", r.sent in 6..7)
            assertEquals(r.sent, r.stats.n)
            assertEquals(0, r.samples.first().seq)
            assertEquals(r.seqSpan, r.sent)
            val stats: PathStats = r.stats
            assertTrue(stats.answered)
        } finally {
            server.close()
            echo.join(500)
        }
    }
}
