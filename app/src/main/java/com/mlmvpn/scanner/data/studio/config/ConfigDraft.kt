package com.mlmvpn.scanner.data.studio.config

import com.mlmvpn.scanner.data.studio.domain.ProtocolType
import com.mlmvpn.scanner.data.studio.domain.TransportType

/**
 * What this engine can actually serve, and what each of those costs.
 *
 * The list is short and every absence from it has a reason in the data plane rather than in the
 * model. `ProtocolType` and `TransportType` keep every value they have — they describe what a *link*
 * can be, which is a wider question than what *this worker* can answer:
 *
 *  * **gRPC, raw TCP and httpupgrade** are not implementable as Cloudflare Worker inbounds at all.
 *    A Worker gets an HTTP request; it cannot accept a raw socket or terminate a gRPC stream.
 *  * **VMess and Shadowsocks** have working URI builders here and no server side: the tunnel reads
 *    a VLESS header or a Trojan one and nothing else. Offering them would produce a link that
 *    imports cleanly into any client and never connects, which is the worst kind of broken.
 *  * **XHTTP** is real and is offered, with its three constraints attached rather than discovered.
 *
 * Growing this enum is how the feature grows. Nothing else in the builder has to change.
 */
enum class ConfigShape(
    val protocol: ProtocolType,
    val transport: TransportType,
    val label: String,
) {
    VLESS_WS(ProtocolType.VLESS, TransportType.WS, "VLESS · WebSocket"),
    TROJAN_WS(ProtocolType.TROJAN, TransportType.WS, "Trojan · WebSocket"),

    /**
     * VLESS over XHTTP.
     *
     * Three things are true of this shape and of no other, and all three are enforced by
     * [ConfigDraft] rather than left for the operator to discover:
     *
     *  1. **It authenticates against the USER's uuid**, not a per-config credential. The XHTTP path
     *     looks the connection up with `SELECT ... FROM users WHERE uuid = ?` and never reads the
     *     configs table, so a config with its own credential simply would not connect.
     *  2. **It cannot claim a path.** Its path is the session uuid, matched at the root — a
     *     per-config route key would never be reached.
     *  3. **It bills one Worker request per upload chunk**, where a WebSocket is one request held
     *     open. On a free account's hundred thousand requests a day that is the difference between
     *     a few hundred users and a few dozen.
     *
     * It exists because an operator whose WebSocket upgrades are being blocked has nothing else,
     * and refusing to offer it would be a worse answer than offering it with the price on the label.
     */
    VLESS_XHTTP(ProtocolType.VLESS, TransportType.XHTTP, "VLESS · XHTTP"),

    ;

    /** Whether this shape can carry a credential of its own. See [VLESS_XHTTP]. */
    val supportsOwnCredential: Boolean get() = transport != TransportType.XHTTP

    /** Whether this shape can be served on a path of its own. See [VLESS_XHTTP]. */
    val supportsRouteKey: Boolean get() = transport != TransportType.XHTTP

    /** Whether choosing this shape spends the request budget faster than the others. */
    val isRequestHungry: Boolean get() = transport == TransportType.XHTTP

    /**
     * Whether ALPN is a choice on this shape, or a fact about it.
     *
     * **Xray's WebSocket transport speaks HTTP/1.1 and nothing else.** Offer h2 and Cloudflare
     * negotiates it, and the dial dies with `websocket: protocol "h2" was given but is not
     * supported` — a config that validates fine, imports fine, and fails only when someone tries to
     * use it. `ConfigBuilder` already strips h2 from a WebSocket link for exactly this reason, so a
     * picker here would be a control whose value is silently discarded: the operator chooses
     * "h2, http/1.1", the link says http/1.1, and nothing explains the difference.
     */
    val alpnIsAChoice: Boolean get() = transport != TransportType.WS
}

/**
 * The TLS fingerprint a client presents.
 *
 * These are the values Xray-family clients accept, and they are not cosmetic: the fingerprint is
 * what a middlebox sees before any payload, and a config whose fingerprint does not match a real
 * browser is the one thing about a link that can be distinguished without decrypting anything.
 *
 * `random` is offered last rather than first. It rotates per connection, which defeats a censor
 * fingerprinting one value — and also means two connections from one person look like two different
 * clients, which some networks treat as more suspicious rather than less.
 */
enum class ConfigFingerprint(val wire: String) {
    CHROME("chrome"), FIREFOX("firefox"), SAFARI("safari"),
    IOS("ios"), EDGE("edge"), RANDOM("random");

    companion object {
        fun from(s: String?): ConfigFingerprint =
            entries.firstOrNull { it.wire.equals(s, true) } ?: CHROME
    }
}

/**
 * Which application protocols the client offers in the TLS handshake.
 *
 * [H2_AND_HTTP1] is the default because it is what a browser sends, and looking like a browser is
 * the whole point of the fingerprint above it. The single-protocol options exist for a network that
 * treats one of them differently — some carriers throttle HTTP/2 — and that is a real thing an
 * operator needs to be able to change without editing a link by hand.
 */
enum class ConfigAlpn(val values: List<String>) {
    H2_AND_HTTP1(listOf("h2", "http/1.1")),
    H2_ONLY(listOf("h2")),
    HTTP1_ONLY(listOf("http/1.1"));

    companion object {
        fun from(list: List<String>): ConfigAlpn = when {
            list.size == 1 && list.first() == "h2" -> H2_ONLY
            list.size == 1 && list.first().startsWith("http/1") -> HTTP1_ONLY
            else -> H2_AND_HTTP1
        }
    }
}

/**
 * Everything the operator chooses about one config, before it exists.
 *
 * **What is NOT here is as deliberate as what is.** Address, port and server name are properties of
 * an ENDPOINT, not of a config: the engine renders one link per config per endpoint per port, so a
 * host typed here would be overwritten by every node in the list. Putting fields for them on this
 * screen would be offering three controls that quietly do nothing.
 *
 * DNS and routing are absent for a different reason: a `vless://` or `trojan://` URI has no field
 * for either. They are settings in the client application that receives the link, and this product
 * hands out links.
 */
data class ConfigDraft(
    val shape: ConfigShape = ConfigShape.VLESS_WS,
    val label: String = "",
    val fingerprint: ConfigFingerprint = ConfigFingerprint.CHROME,
    val alpn: ConfigAlpn = ConfigAlpn.H2_AND_HTTP1,
    /** Blank means "generate one". A typed value is used as-is, after [credentialProblem]. */
    val customCredential: String = "",
    /** Blank means "generate one". Ignored entirely when the shape cannot carry a path. */
    val customPath: String = "",
    /** The first config a person gets answers on the root, which is what a fresh client expects. */
    val useRootPath: Boolean = false,
) {
    /** True when this draft would produce a config the engine cannot authenticate. */
    val credentialIsForced: Boolean get() = !shape.supportsOwnCredential

    /**
     * Why this credential cannot be used, or null when it can.
     *
     * Checked here rather than at the API, because the failure the API would give is a 500 from a
     * tunnel that cannot parse a header — days later, on someone else's phone.
     */
    fun credentialProblem(): CredentialProblem? {
        val c = customCredential.trim()
        if (c.isEmpty()) return null
        return when {
            // The URI carries it in the userinfo position; anything needing percent-encoding is a
            // link somebody eventually breaks by copying it out of a chat.
            c.any { it.isWhitespace() || it in "@/?#:&=%" } -> CredentialProblem.BAD_CHARACTERS
            shape.protocol == ProtocolType.VLESS && !UUID_RE.matches(c) -> CredentialProblem.NOT_A_UUID
            shape.protocol == ProtocolType.TROJAN && c.length < 8 -> CredentialProblem.TOO_SHORT
            else -> null
        }
    }

    /**
     * Why this path cannot be used, or null when it can.
     *
     * The engine matches a claimed path exactly and serves the camouflage page to everything else,
     * so a path with a slash in the middle would register a route no request can reach.
     */
    fun pathProblem(): PathProblem? {
        val p = customPath.trim().trim('/')
        if (p.isEmpty()) return null
        return when {
            !PATH_RE.matches(p) -> PathProblem.BAD_CHARACTERS
            // Reserved at the entry, above every transport matcher. A config claiming one of these
            // would be shadowed by the control plane and never see a connection.
            p.substringBefore('/') in RESERVED -> PathProblem.RESERVED
            else -> null
        }
    }

    val isValid: Boolean get() = credentialProblem() == null && pathProblem() == null

    enum class CredentialProblem { BAD_CHARACTERS, NOT_A_UUID, TOO_SHORT }
    enum class PathProblem { BAD_CHARACTERS, RESERVED }

    private companion object {
        val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        val PATH_RE = Regex("^[A-Za-z0-9._~/-]{1,64}$")
        val RESERVED = setOf("api", "sub", "feed", "status", "admin", "locations", "s", "p")
    }
}
