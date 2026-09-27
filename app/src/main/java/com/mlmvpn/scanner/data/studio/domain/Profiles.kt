package com.mlmvpn.scanner.data.studio.domain

/**
 * What a config is, in the shape the rest of Config Studio speaks.
 *
 * The organising rule, and the reason this is not just a flatter `VpnConfig`: **no transport-specific
 * field appears anywhere in [ConfigSpec]**. A transport carries its own options inside an opaque map
 * that only that transport's `when` arm reads, so the model does not grow a `serviceName` the day gRPC
 * arrives, or a `mode` the day XHTTP does.
 *
 * The practical payoff is in [com.mlmvpn.scanner.data.studio.config.ConfigBuilder]: adding a transport
 * is one enum entry and one `when` arm in each of two functions. It touches no data class, no database
 * column, no API field and no screen. `VpnConfig` -- which is the app's *parsed link* type and stays
 * exactly as it is -- takes the opposite approach and has a named field per transport, which is why it
 * has eight of them and needs a ninth for every addition.
 *
 * See `android/docs/CONFIG-STUDIO-PLAN.md` §5.
 */

enum class ProtocolType(val wire: String) {
    VLESS("vless"), TROJAN("trojan"), VMESS("vmess"), SHADOWSOCKS("ss");

    companion object {
        fun from(s: String): ProtocolType? = entries.firstOrNull { it.wire.equals(s, true) }
            ?: if (s.equals("shadowsocks", true)) SHADOWSOCKS else null
    }
}

enum class TransportType(val wire: String) {
    WS("ws"), XHTTP("xhttp"), GRPC("grpc"), HTTPUPGRADE("httpupgrade"), TCP("tcp");

    companion object {
        fun from(s: String): TransportType? = entries.firstOrNull { it.wire.equals(s, true) }
    }
}

enum class SecurityType(val wire: String) {
    NONE("none"), TLS("tls"), REALITY("reality");

    companion object {
        fun from(s: String): SecurityType? = entries.firstOrNull { it.wire.equals(s, true) }
    }
}

data class ProtocolProfile(
    val type: ProtocolType,
    /** The one credential this protocol authenticates with: uuid, trojan password, or ss psk. */
    val credential: String,
    /** `flow` for vless, `alterId`/`security` for vmess, `method` for shadowsocks. */
    val extra: Map<String, String> = emptyMap(),
)

/**
 * @property settings deliberately opaque: `path`, `host`, `serviceName`, `mode`, `extra` -- whatever
 *   this transport needs, read only by its own arm of the builder.
 */
data class TransportProfile(
    val type: TransportType,
    val settings: Map<String, String> = emptyMap(),
)

data class SecurityProfile(
    val type: SecurityType,
    val sni: String? = null,
    val fingerprint: String? = null,
    val alpn: List<String> = emptyList(),
    val allowInsecure: Boolean = false,
    /** REALITY's `pbk` / `sid` / `spx`. */
    val extra: Map<String, String> = emptyMap(),
)

data class ConfigSpec(
    val protocol: ProtocolProfile,
    val transport: TransportProfile,
    val security: SecurityProfile,
    val host: String,
    val port: Int,
    val remark: String = "",
)
