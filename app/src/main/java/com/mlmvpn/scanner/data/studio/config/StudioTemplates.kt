package com.mlmvpn.scanner.data.studio.config

import com.mlmvpn.scanner.data.studio.domain.ConfigSpec
import com.mlmvpn.scanner.data.studio.domain.ProtocolProfile
import com.mlmvpn.scanner.data.studio.domain.ProtocolType
import com.mlmvpn.scanner.data.studio.domain.SecurityProfile
import com.mlmvpn.scanner.data.studio.domain.SecurityType
import com.mlmvpn.scanner.data.studio.domain.TransportProfile
import com.mlmvpn.scanner.data.studio.domain.TransportType

/**
 * The `uri_template` a config row carries, produced by the same builder the app dials with.
 *
 * The worker builds no URIs: it loads a config, substitutes five placeholders into this string, and
 * joins the results. That is the answer to "how are the two builders kept in step" -- there are not
 * two. But it leaves a small problem worth solving properly rather than around: a template is not a
 * URI, and `ConfigBuilder` only makes URIs.
 *
 * So the template is made **by building a real URI out of sentinels** and then swapping each sentinel
 * for its placeholder. The alternative -- writing the template out by hand -- would put a second
 * description of the URI shape in the codebase, which is exactly the duplication this whole design
 * removes. Doing it this way means a change to how `ConfigBuilder` orders query parameters, escapes
 * a value, or handles ALPN reaches the emitted template automatically.
 *
 * The sentinels are chosen to survive `URLEncoder` untouched (letters and digits only), so they can
 * be found again afterwards.
 */
object StudioTemplates {

    private const val CRED = "AAcredAA"
    private const val HOST = "AAhostAA"
    private const val SNI = "AAsniAA"
    private const val PATH = "AApathAA"
    private const val REMARK = "AAremarkAA"

    /** A port no real endpoint uses, so replacing it cannot hit a digit sequence somewhere else. */
    private const val PORT_SENTINEL = 64999

    /**
     * The Phase 1 default: VLESS over WebSocket with TLS.
     *
     * WebSocket rather than XHTTP, and that is a budget decision rather than a preference: a
     * WebSocket connection is **one** Worker request held open, while XHTTP packet-up bills one per
     * upload chunk. On a free plan's 100,000 requests a day, a hundred users on XHTTP exhaust the
     * account before lunch and take every worker on it down with them.
     */
    fun defaultWsTemplate(): String {
        val spec = ConfigSpec(
            protocol = ProtocolProfile(ProtocolType.VLESS, CRED),
            transport = TransportProfile(TransportType.WS, mapOf("path" to PATH, "host" to SNI)),
            security = SecurityProfile(SecurityType.TLS, sni = SNI, fingerprint = "chrome"),
            host = HOST,
            port = PORT_SENTINEL,
            remark = REMARK,
        )
        return toTemplate(ConfigBuilder.buildUri(spec))
    }

    /**
     * Trojan over WebSocket with TLS.
     *
     * The same transport and the same budget reasoning as [defaultWsTemplate] — what changes is the
     * protocol, and with it what goes on the wire: Trojan sends `hex(SHA-224(password))` rather than
     * the credential itself. That is why a Trojan config carries an `authHash` and a VLESS one does
     * not (see [StudioCredentials]).
     */
    fun trojanWsTemplate(): String {
        val spec = ConfigSpec(
            protocol = ProtocolProfile(ProtocolType.TROJAN, CRED),
            transport = TransportProfile(TransportType.WS, mapOf("path" to PATH, "host" to SNI)),
            security = SecurityProfile(SecurityType.TLS, sni = SNI, fingerprint = "chrome"),
            host = HOST,
            port = PORT_SENTINEL,
            remark = REMARK,
        )
        return toTemplate(ConfigBuilder.buildUri(spec))
    }

    /** The template for a shape at its defaults, kept for the callers that do not offer choices. */
    fun templateFor(shape: ConfigShape): String = templateFor(ConfigDraft(shape = shape))

    /**
     * The template for a fully specified draft.
     *
     * Built the same way the two fixed ones are — a real URI made of sentinels, then swapped for
     * placeholders — so every choice the builder offers reaches the emitted template through
     * `ConfigBuilder` rather than through a second description of the URI shape written here.
     *
     * `host` is set to the SNI sentinel rather than the address one, and that is not a slip: the
     * `Host` header a WebSocket presents has to be the name the TLS handshake asked for, while the
     * address it connects to is a clean IP that is usually a different string. Sending the IP as
     * the Host header is how a config connects to the right machine and gets refused by it.
     */
    fun templateFor(draft: ConfigDraft): String {
        val transport = when (draft.shape.transport) {
            TransportType.XHTTP -> TransportProfile(
                TransportType.XHTTP,
                // packet-up: the mode the entry actually routes, POST per chunk against a session
                // uuid. Stream modes need a duplex body that Workers do not give us.
                mapOf("path" to PATH, "host" to SNI, "mode" to "packet-up"),
            )
            else -> TransportProfile(TransportType.WS, mapOf("path" to PATH, "host" to SNI))
        }
        val spec = ConfigSpec(
            protocol = ProtocolProfile(draft.shape.protocol, CRED),
            transport = transport,
            security = SecurityProfile(
                // TLS and nothing else. A Cloudflare Worker is only reachable over TLS on 443, so
                // `none` would describe a connection that cannot be made and `reality` would need
                // a key pair no Worker can hold.
                SecurityType.TLS,
                sni = SNI,
                fingerprint = draft.fingerprint.wire,
                // **Nothing on WebSocket, and that is not the same as "http/1.1".** `ConfigBuilder`
                // strips h2 from a WebSocket link because Xray's ws transport is HTTP/1.1 only, so
                // passing the operator's choice through would leave `alpn=http%2F1.1` on a link
                // where ALPN was never a decision — a parameter the shipping template has never
                // carried, added to every config, describing a negotiation that happens anyway.
                alpn = if (draft.shape.alpnIsAChoice) draft.alpn.values else emptyList(),
            ),
            host = HOST,
            port = PORT_SENTINEL,
            remark = REMARK,
        )
        return toTemplate(ConfigBuilder.buildUri(spec))
    }

    private fun toTemplate(uri: String): String = uri
        .replace(CRED, "{{cred}}")
        .replace(":$PORT_SENTINEL", ":{{port}}")
        .replace(HOST, "{{host}}")
        .replace(SNI, "{{sni}}")
        .replace(PATH, "{{path}}")
        .replace(REMARK, "{{remark}}")

    /**
     * Every placeholder the worker knows how to fill.
     *
     * Checked after rendering, because a sentinel that survives -- a value `ConfigBuilder` escaped in
     * a way the replace above no longer matches -- would ship a config with `AAsniAA` where the
     * server name belongs. That config imports without complaint and simply never connects.
     */
    fun isWellFormed(template: String): Boolean =
        !template.contains("AA") &&
            template.contains("{{cred}}") &&
            template.contains("{{host}}") &&
            template.contains("{{port}}")
}
