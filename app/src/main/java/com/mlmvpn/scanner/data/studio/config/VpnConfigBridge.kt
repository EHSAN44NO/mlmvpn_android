package com.mlmvpn.scanner.data.studio.config

import com.mlmvpn.scanner.data.studio.domain.ConfigSpec
import com.mlmvpn.scanner.data.studio.domain.ProtocolProfile
import com.mlmvpn.scanner.data.studio.domain.ProtocolType
import com.mlmvpn.scanner.data.studio.domain.SecurityProfile
import com.mlmvpn.scanner.data.studio.domain.SecurityType
import com.mlmvpn.scanner.data.studio.domain.TransportProfile
import com.mlmvpn.scanner.data.studio.domain.TransportType
import com.mlmvpn.scanner.utils.VpnConfig

/**
 * `VpnConfig` -> [ConfigSpec], so the app's connect path and Config Studio's emit path can share one
 * builder.
 *
 * The two types are not rivals and neither replaces the other. `VpnConfig` is the app's **parsed
 * link**: a flat bag with a named field per transport (`wsPath`, `xhttpPath`, `xhttpMode`,
 * `serviceName`, …), which is the right shape for something filled in by a URI parser one key at a
 * time. `ConfigSpec` is the **description of a config**, where a transport's options are opaque to
 * everything except that transport. This file is the seam between them, and it is the only place
 * that needs to know both shapes.
 *
 * Keeping it one-directional is deliberate: there is no `ConfigSpec.toVpnConfig()`. Nothing needs
 * one, and adding it would invite two sources of truth for the same connection.
 */
fun VpnConfig.toConfigSpec(): ConfigSpec {
    val protocolType = ProtocolType.from(protocol) ?: ProtocolType.VLESS

    val credential = when (protocolType) {
        // Not a copy-paste slip: the app's URI parser stores a trojan password in `uuid`, because
        // vless and trojan carry their credential in the same position of the URI, and
        // XrayJsonGenerator has always read it from there for both.
        ProtocolType.VLESS, ProtocolType.TROJAN, ProtocolType.VMESS -> uuid
        ProtocolType.SHADOWSOCKS -> password
    }

    val protocolExtra = buildMap {
        if (flow.isNotBlank()) put("flow", flow)
        if (protocolType == ProtocolType.VMESS) {
            put("alterId", alterId.toString())
            put("security", vmessSecurity.ifBlank { "auto" })
        }
        if (protocolType == ProtocolType.SHADOWSOCKS && method.isNotBlank()) put("method", method)
    }

    val transportType = TransportType.from(network) ?: TransportType.TCP
    val transportSettings = when (transportType) {
        TransportType.WS, TransportType.HTTPUPGRADE -> buildMap {
            if (wsPath.isNotBlank()) put("path", wsPath)
            if (wsHost.isNotBlank()) put("host", wsHost)
        }
        TransportType.XHTTP -> buildMap {
            if (xhttpPath.isNotBlank()) put("path", xhttpPath)
            // The generator's own fallback, preserved: an xhttp link that carries no host of its
            // own borrows the ws one, which is what the URI parser leaves behind for links that
            // set `host=` once for both.
            val h = xhttpHost.ifBlank { wsHost }
            if (h.isNotBlank()) put("host", h)
            if (xhttpMode.isNotBlank()) put("mode", xhttpMode)
            if (xhttpExtra.isNotBlank()) put("extra", xhttpExtra)
        }
        TransportType.GRPC -> buildMap {
            if (serviceName.isNotBlank()) put("serviceName", serviceName)
        }
        TransportType.TCP -> emptyMap()
    }

    val securityType = SecurityType.from(tls) ?: if (tls.isBlank()) SecurityType.NONE else SecurityType.TLS

    return ConfigSpec(
        protocol = ProtocolProfile(protocolType, credential, protocolExtra),
        transport = TransportProfile(transportType, transportSettings),
        security = SecurityProfile(
            type = securityType,
            // The generator resolved the TLS server name as "sni, else the ws host". That is kept
            // here rather than in ConfigBuilder, because it is a fact about how this app's parser
            // fills the fields in, not about how TLS works.
            sni = sni.ifBlank { wsHost }.ifBlank { null },
            fingerprint = fingerprint.ifBlank { null },
            alpn = alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            extra = buildMap {
                if (publicKey.isNotBlank()) put("pbk", publicKey)
                put("sid", shortId)
                if (spiderX.isNotBlank()) put("spx", spiderX)
            },
        ),
        host = address,
        port = port,
        remark = name,
    )
}
