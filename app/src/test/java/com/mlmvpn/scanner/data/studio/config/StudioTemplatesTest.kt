package com.mlmvpn.scanner.data.studio.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The template the worker substitutes into.
 *
 * Worth testing precisely because the failure is silent: a template with a surviving sentinel, or a
 * missing placeholder, produces a config that **imports without complaint and never connects**. The
 * person holding it sees a server in their list that simply does not work, and nothing in the app or
 * the engine reports anything wrong.
 */
class StudioTemplatesTest {

    private val template = StudioTemplates.defaultWsTemplate()

    @Test
    fun `every placeholder the worker fills is present`() {
        for (name in listOf("cred", "host", "port", "sni", "path", "remark")) {
            assertTrue("missing {{$name}} in: $template", template.contains("{{$name}}"))
        }
    }

    @Test
    fun `no sentinel survives into the template`() {
        // A sentinel that escaped the swap ships "AAsniAA" where a server name belongs.
        assertFalse("sentinel left in: $template", template.contains("AA"))
        assertTrue(StudioTemplates.isWellFormed(template))
    }

    @Test
    fun `the shape is what a client expects`() {
        assertTrue(template.startsWith("vless://{{cred}}@{{host}}:{{port}}?"))
        assertTrue(template.contains("type=ws"))
        assertTrue(template.contains("security=tls"))
        assertTrue(template.contains("encryption=none"))
        assertTrue(template.endsWith("#{{remark}}"))
    }

    @Test
    fun `substituting it yields a usable link`() {
        // The same five substitutions the worker performs.
        val rendered = template
            .replace("{{cred}}", "11111111-2222-3333-4444-555555555555")
            .replace("{{host}}", "1.2.3.4")
            .replace("{{port}}", "443")
            .replace("{{sni}}", "edge.example.workers.dev")
            .replace("{{path}}", "/")
            .replace("{{remark}}", "ali%20-%20443")

        assertTrue(rendered.startsWith("vless://11111111-2222-3333-4444-555555555555@1.2.3.4:443?"))
        assertTrue(rendered.contains("sni=edge.example.workers.dev"))
        // The clean IP is dialled, but the SNI stays the worker host -- that is the entire point of
        // using a clean IP, and getting it backwards makes the TLS handshake fail.
        assertFalse(rendered.contains("sni=1.2.3.4"))
        assertFalse(rendered.contains("{{"))
    }

    @Test
    fun `the port placeholder replaces only the port`() {
        // The sentinel port must not collide with a digit run elsewhere in the URI.
        assertEquals(1, Regex("\\{\\{port}}").findAll(template).count())
        assertTrue(template.contains("@{{host}}:{{port}}?"))
    }

    @Test
    fun `h2 never reaches a websocket template`() {
        // Xray's WebSocket transport is HTTP/1.1 only; an emitted config offering h2 is dead on
        // arrival in whatever client opens it.
        assertFalse(template.contains("alpn=h2"))
    }

    // ------------------------------------------------------------------ the builder's drafts

    @Test
    fun `a draft at its defaults is the same template as the fixed one`() {
        // The two-shape dialog and the builder must not drift into producing different links for
        // the same choice, which is exactly what having two code paths invites.
        assertEquals(StudioTemplates.defaultWsTemplate(), StudioTemplates.templateFor(ConfigDraft()))
        assertEquals(
            StudioTemplates.trojanWsTemplate(),
            StudioTemplates.templateFor(ConfigDraft(shape = ConfigShape.TROJAN_WS)),
        )
    }

    @Test
    fun `every offered shape produces a well-formed template`() {
        for (shape in ConfigShape.entries) {
            val t = StudioTemplates.templateFor(ConfigDraft(shape = shape))
            assertTrue("$shape: sentinel survived in $t", StudioTemplates.isWellFormed(t))
            assertTrue("$shape: no remark in $t", t.endsWith("#{{remark}}"))
            assertTrue("$shape: wrong protocol in $t", t.startsWith(shape.protocol.wire + "://"))
        }
    }

    @Test
    fun `a websocket template carries no alpn at all, whatever the operator picks`() {
        // Two rules at once. ConfigBuilder strips h2 from a ws link because Xray's ws transport is
        // HTTP/1.1 only and a negotiated h2 kills the dial; and the draft does not substitute
        // "http/1.1" in its place, because ALPN was never a decision on this transport and adding
        // a parameter the shipping template has never carried would change every config for no
        // reason. Asserted through the surface the operator actually touches.
        for (shape in ConfigShape.entries.filter { !it.alpnIsAChoice }) {
            for (alpn in ConfigAlpn.entries) {
                val t = StudioTemplates.templateFor(ConfigDraft(shape = shape, alpn = alpn))
                assertFalse("$shape/$alpn carried alpn: $t", t.contains("alpn="))
            }
        }
    }

    @Test
    fun `xhttp carries h2 and the mode the entry actually routes`() {
        val t = StudioTemplates.templateFor(
            ConfigDraft(shape = ConfigShape.VLESS_XHTTP, alpn = ConfigAlpn.H2_ONLY)
        )
        assertTrue(t, t.contains("type=xhttp"))
        assertTrue(t, t.contains("alpn=h2"))
        // packet-up is the only mode the worker's entry matches: a POST per chunk against a session
        // uuid. A stream mode would need a duplex body Workers do not provide.
        assertTrue(t, t.contains("mode=packet-up"))
    }

    @Test
    fun `the fingerprint the operator picks is the one in the link`() {
        for (fp in ConfigFingerprint.entries) {
            val t = StudioTemplates.templateFor(ConfigDraft(fingerprint = fp))
            assertTrue("${fp.wire} missing from $t", t.contains("fp=" + fp.wire))
        }
    }

    // ------------------------------------------------------------------ what a draft refuses

    @Test
    fun `a credential that would break a link is refused before it is stored`() {
        val bad = ConfigDraft(shape = ConfigShape.TROJAN_WS, customCredential = "has space")
        assertEquals(ConfigDraft.CredentialProblem.BAD_CHARACTERS, bad.credentialProblem())
        // The userinfo position: an @ splits the authority and the link stops parsing.
        assertEquals(
            ConfigDraft.CredentialProblem.BAD_CHARACTERS,
            bad.copy(customCredential = "a@b").credentialProblem(),
        )
        assertEquals(
            ConfigDraft.CredentialProblem.TOO_SHORT,
            bad.copy(customCredential = "short").credentialProblem(),
        )
        assertNull(bad.copy(customCredential = "a-perfectly-fine-password").credentialProblem())
    }

    @Test
    fun `VLESS insists on a uuid because that is what the data plane matches`() {
        val draft = ConfigDraft(customCredential = "not-a-uuid")
        assertEquals(ConfigDraft.CredentialProblem.NOT_A_UUID, draft.credentialProblem())
        assertNull(draft.copy(customCredential = "11111111-2222-3333-4444-555555555555").credentialProblem())
    }

    @Test
    fun `a path the engine has reserved is refused`() {
        // These are matched above every transport in the worker's entry, so a config claiming one
        // would register a route that no connection can ever reach.
        for (taken in listOf("api", "s", "p", "sub/x", "admin")) {
            assertEquals(
                "$taken should be reserved",
                ConfigDraft.PathProblem.RESERVED,
                ConfigDraft(customPath = taken).pathProblem(),
            )
        }
        assertNull(ConfigDraft(customPath = "u/abc123").pathProblem())
        assertEquals(
            ConfigDraft.PathProblem.BAD_CHARACTERS,
            ConfigDraft(customPath = "has space").pathProblem(),
        )
    }

    @Test
    fun `XHTTP takes no path and no credential of its own`() {
        // Both are properties of how the worker's XHTTP entry authenticates: it matches
        // `users.uuid` at the root and never reads the configs table.
        val draft = ConfigDraft(shape = ConfigShape.VLESS_XHTTP, customPath = "u/abc", customCredential = "x")
        assertNull(StudioCredentials.routeKeyFor(draft))
        assertEquals(
            "11111111-2222-3333-4444-555555555555",
            StudioCredentials.credentialFor(draft, "11111111-2222-3333-4444-555555555555"),
        )
    }

    @Test
    fun `a websocket config takes the path it was given, or a fresh one, or the root`() {
        assertEquals("u/abc", StudioCredentials.routeKeyFor(ConfigDraft(customPath = "/u/abc/")))
        assertNull(StudioCredentials.routeKeyFor(ConfigDraft(useRootPath = true)))
        // Blank and not root: a generated key, so two configs on one person never collide.
        val generated = StudioCredentials.routeKeyFor(ConfigDraft())
        assertTrue(generated, generated!!.startsWith("u/"))
        assertNotEquals(generated, StudioCredentials.routeKeyFor(ConfigDraft()))
    }

    @Test
    fun `a typed credential is used and a blank one is generated`() {
        val typed = ConfigDraft(customCredential = "11111111-2222-3333-4444-555555555555")
        assertEquals("11111111-2222-3333-4444-555555555555", StudioCredentials.credentialFor(typed, null))
        val generated = StudioCredentials.credentialFor(ConfigDraft(), null)
        assertNotEquals(generated, StudioCredentials.credentialFor(ConfigDraft(), null))
    }

    @Test
    fun `only Trojan carries an auth hash`() {
        // The partial unique index on `auth_hash` exists so that every VLESS config, which has
        // none, does not collide with every other one on NULL.
        assertNull(StudioCredentials.authHash(ConfigShape.VLESS_WS, "x"))
        assertNull(StudioCredentials.authHash(ConfigShape.VLESS_XHTTP, "x"))
        val hash = StudioCredentials.authHash(ConfigShape.TROJAN_WS, "password")
        // hex(SHA-224(...)) is 56 characters, which is exactly what the worker matches on the wire.
        assertEquals(56, hash!!.length)
        assertTrue(hash, hash.all { it in "0123456789abcdef" })
    }
}
