package com.mlmvpn.scanner.engines.flux

import com.mlmvpn.scanner.engines.flux.FluxFixtures.UUID
import com.mlmvpn.scanner.engines.flux.FluxFixtures.cfWs
import com.mlmvpn.scanner.engines.flux.FluxFixtures.hy2
import com.mlmvpn.scanner.engines.flux.FluxFixtures.reality
import com.mlmvpn.scanner.engines.flux.FluxFixtures.trojan
import com.mlmvpn.scanner.engines.flux.core.model.Proto
import com.mlmvpn.scanner.engines.flux.core.model.Security
import com.mlmvpn.scanner.engines.flux.core.model.Transport
import com.mlmvpn.scanner.engines.flux.core.parse.FluxLinkParser
import com.mlmvpn.scanner.engines.flux.core.parse.FluxLinkParser.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class FluxLinkParserTest {

    private fun ok(link: String) = (FluxLinkParser.parse(link) as Result.Ok).node
    private fun reason(link: String) = (FluxLinkParser.parse(link) as Result.Rejected).reason

    @Test fun `cloudflare websocket node keeps its origin names`() {
        val n = ok(cfWs())
        assertEquals(Proto.VLESS, n.proto)
        assertEquals(Transport.WS, n.transport)
        assertEquals(Security.TLS, n.security)
        assertEquals("edge-a.pages.dev", n.host)
        assertEquals("edge-a.pages.dev", n.sni)
        assertEquals("/", n.path)
        assertTrue(n.edgeExpandable)
        assertFalse(n.carriesUdp)
    }

    @Test fun `reality node parsed with its key and vision flow`() {
        val n = ok(reality())
        assertEquals(Security.REALITY, n.security)
        assertEquals("xtls-rprx-vision", n.flow)
        assertEquals("yahoo.com", n.sni)
        assertFalse(n.edgeExpandable)
        assertTrue(n.carriesUdp)
    }

    @Test fun `hysteria2 with salamander`() {
        val n = ok(hy2())
        assertEquals(Proto.HY2, n.proto)
        assertEquals("salamander", n.obfs)
        assertEquals("obfspw", n.obfsPassword)
        assertFalse(n.insecure)
    }

    @Test fun `self-signed hysteria2 is kept but marked, self-signed vless is refused`() {
        assertTrue(ok(hy2(insecure = true)).insecure)
        assertEquals("insecure-tls", reason(cfWs().substringBefore('#') + "&allowInsecure=1"))
    }

    @Test fun `plaintext vless refused`() {
        assertEquals("plaintext", reason("vless://$UUID@1.2.3.4:80?type=ws&host=a.example.com&path=%2F"))
    }

    @Test fun `malformed links refused with a reason`() {
        assertEquals("bad-uuid", reason("vless://not a uuid@1.2.3.4:443?security=tls&sni=a.example.com"))
        assertEquals("bad-port", reason("vless://$UUID@1.2.3.4:70000?security=tls&sni=a.example.com"))
        assertEquals("bad-reality-key", reason("vless://$UUID@1.2.3.4:443?security=reality&sni=a.com&pbk=short"))
        assertEquals("unsupported-scheme", reason("vmess://abc"))
        assertEquals("tls-without-name", reason("vless://$UUID@1.2.3.4:443?security=tls&type=tcp"))
        assertEquals("flow-needs-tcp", reason("vless://$UUID@1.2.3.4:443?security=tls&type=ws&sni=a.example.com&flow=xtls-rprx-vision"))
    }

    @Test fun `node id is stable, ignores the label and never contains the credential`() {
        val a = ok(trojan(pass = "hunter2-secret"))
        val b = ok(trojan(pass = "hunter2-secret").substringBefore('#') + "#another-name")
        assertEquals(a.id, b.id)
        assertFalse(a.id.contains("hunter2"))
        assertFalse(a.toString().contains("hunter2"))
        assertNotEquals(a.id, ok(trojan(pass = "other")).id)
    }

    @Test fun `base64 body parsed and deduplicated`() {
        val body = listOf(cfWs(), cfWs().substringBefore('#') + "#dup", reality(), "# comment", "garbage").joinToString("\n")
        val b64 = Base64.getEncoder().encodeToString(body.toByteArray())
        val batch = FluxLinkParser.parseBody(b64, "s")
        assertEquals(2, batch.nodes.size)
        assertEquals(1, batch.duplicates)
        assertEquals(1, batch.rejected["not-a-link"])
    }

    @Test fun `ipv6 server in brackets`() {
        val n = ok("vless://$UUID@[2606:4700::6810:1]:443?security=tls&type=ws&host=a.example.com&sni=a.example.com")
        assertEquals("2606:4700::6810:1", n.server)
    }

    @Test fun `publisher finalmask and cipher suites are carried`() {
        val n = ok(cfWs().substringBefore('#') + "&cs=TLS_AES_128_GCM_SHA256&fm=%7B%22tcp%22%3A%5B%5D%7D")
        assertEquals("TLS_AES_128_GCM_SHA256", n.cipherSuites)
        assertEquals("{\"tcp\":[]}", n.finalmask)
    }
}
