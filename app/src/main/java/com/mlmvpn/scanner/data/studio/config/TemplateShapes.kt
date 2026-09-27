package com.mlmvpn.scanner.data.studio.config

import com.mlmvpn.scanner.data.studio.ConfigPayload
import com.mlmvpn.scanner.data.studio.domain.ConfigTemplate
import com.mlmvpn.scanner.data.studio.domain.ProtocolType
import com.mlmvpn.scanner.data.studio.domain.StudioUser
import com.mlmvpn.scanner.data.studio.domain.TransportType

/**
 * The bridge between a stored «قالب» and the draft the builder edits.
 *
 * Two representations of the same thing, and they are separate on purpose. [ConfigDraft] is what a
 * screen holds while somebody is choosing — it validates as you type, it knows which controls are
 * a choice on which shape, and it is thrown away. [ConfigTemplate] is a row: it survives, it is
 * written to every account in the fleet, and it has to tolerate a field arriving from an engine one
 * build ahead. Mapping between them in one place is what keeps a screen from ever holding a row.
 *
 * **Nothing here fails.** A template naming a shape this engine cannot serve — a protocol added
 * later, a transport removed — resolves to the nearest one it can, rather than throwing on a list
 * screen. The alternative is a stored row that makes the templates page blank, with no way from
 * inside the app to find out which row did it.
 */

/** The draft this template starts a builder at. */
fun ConfigTemplate.toDraft(): ConfigDraft {
    // Named apart from the properties they are read from, because a local `val protocol` shadowing
    // `this.protocol` inside its own initializer does not compile and reads as if it should.
    val wantProtocol = ProtocolType.entries.firstOrNull { it.wire.take(1) == protocol }
        ?: ProtocolType.VLESS
    val wantTransport = transportType?.let { t -> TransportType.entries.firstOrNull { it.wire == t } }
        ?: TransportType.WS
    // The nearest shape this engine can actually serve. `ConfigShape` is the list of what the data
    // plane speaks, which is narrower than what a link can express, and a template written by a
    // newer app must not put an unservable shape in front of the operator.
    val shape = ConfigShape.entries
        .firstOrNull { it.protocol == wantProtocol && it.transport == wantTransport }
        ?: ConfigShape.entries.firstOrNull { it.protocol == wantProtocol }
        ?: ConfigShape.VLESS_WS
    return ConfigDraft(
        shape = shape,
        label = name,
        fingerprint = ConfigFingerprint.from(fingerprint),
        alpn = if (alpn.isEmpty()) ConfigAlpn.H2_AND_HTTP1 else ConfigAlpn.from(alpn),
        // `ownPath == null` means the template has no opinion, and the builder's own default —
        // the first config answers on the root — is what should then apply.
        useRootPath = ownPath?.not() ?: false,
    )
}

/**
 * The template this draft would be saved as.
 *
 * The credential and the path are deliberately **not** carried. Both are per config: a template
 * holding a credential would hand the same uuid to everybody created from it, which is one
 * subscriber's link working for all of them.
 */
fun ConfigDraft.toTemplate(
    id: String,
    name: String,
    nodeIds: List<String> = emptyList(),
    isDefault: Boolean = false,
): ConfigTemplate = ConfigTemplate(
    id = id,
    name = name,
    protocol = shape.protocol.wire.take(1),
    transportType = shape.transport.wire,
    fingerprint = fingerprint.wire,
    // Stored only where it is a choice. On WebSocket, Xray speaks HTTP/1.1 and nothing else, so a
    // stored ALPN would be a value the builder discards on every use — a field that looks like a
    // setting and is not.
    alpn = if (shape.alpnIsAChoice) alpn.values else emptyList(),
    ownPath = !useRootPath,
    nodeIds = nodeIds,
    isDefault = isDefault,
)

/**
 * Everything about one config for one person, rendered on this side.
 *
 * The worker builds no URIs and refuses a config without a template string, which is what keeps the
 * link handed out and the outbound this app dials from being produced by two different pieces of
 * code. This is that rendering, factored out of the builder screen so a bulk run can do exactly what
 * one person's screen does rather than a second version of it.
 */
fun ConfigTemplate.payloadFor(user: StudioUser): ConfigPayload {
    val draft = toDraft()
    val credential = StudioCredentials.credentialFor(draft, user.credential)
    return ConfigPayload(
        uriTemplate = StudioTemplates.templateFor(draft),
        credential = credential,
        protocol = draft.shape.protocol.wire.take(1),
        transportType = draft.shape.transport.wire,
        routeKey = StudioCredentials.routeKeyFor(draft),
        authHash = StudioCredentials.authHash(draft.shape.protocol, credential),
    )
}

/**
 * The four starting points offered on an empty templates screen.
 *
 * Every one of them sets values that are **actually different on the wire**. That constraint is
 * what this list is for: a picker of named presets that all produce the same link would be four
 * ways to do one thing, and the operator would learn to distrust the names.
 *
 * There is deliberately no "gaming protocol" or "gaming transport", because there is no such field
 * in a `vless://` URI and inventing one would be a label over nothing. What actually decides latency
 * for a game is **which endpoint** the link points at, so that preset is the one that carries an
 * endpoint selector and nothing else unusual.
 */
enum class TemplatePreset(val shape: ConfigShape, val fingerprint: ConfigFingerprint) {
    /**
     * The default, and the cheapest on the request budget: one Worker request held open for the
     * life of the connection.
     */
    STABILITY(ConfigShape.VLESS_WS, ConfigFingerprint.CHROME),

    /**
     * A phone.
     *
     * Trojan sends `hex(SHA-224(password))` rather than a uuid, and the handshake presents an iOS
     * fingerprint — which on a mobile carrier is what the traffic around it looks like.
     */
    MOBILE(ConfigShape.TROJAN_WS, ConfigFingerprint.IOS),

    /**
     * A game.
     *
     * The same shape as [STABILITY], because nothing in the URI makes a connection faster. What
     * this preset is *for* is the endpoint selector: pointed at the operator's lowest-latency
     * endpoint, it is the only choice here that changes the number a player feels.
     */
    GAMING(ConfigShape.VLESS_WS, ConfigFingerprint.CHROME),

    /**
     * A carrier that kills WebSocket upgrades.
     *
     * XHTTP is the only answer when the upgrade is being stripped, and it bills one Worker request
     * per upload chunk where WebSocket bills one for the whole connection — the difference between
     * a few hundred users on a free account and a few dozen. Offered with the price on the label
     * rather than withheld, because an operator whose upgrades are blocked has nothing else.
     */
    CENSORSHIP(ConfigShape.VLESS_XHTTP, ConfigFingerprint.CHROME);

    /** The draft this preset starts from. The name is supplied by the screen, in the user's words. */
    fun toDraft(): ConfigDraft = ConfigDraft(shape = shape, fingerprint = fingerprint)
}
