package com.mlmvpn.scanner.engines.flux

import com.mlmvpn.scanner.engines.flux.core.model.Family
import com.mlmvpn.scanner.engines.flux.core.model.FluxCandidate
import com.mlmvpn.scanner.engines.flux.core.model.FluxNode
import com.mlmvpn.scanner.engines.flux.core.parse.FluxLinkParser

/** Links shaped like the real sources' (credentials are made up). */
object FluxFixtures {
    const val UUID = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"

    /** Free-Configs style: VLESS WS/TLS dialled at a Cloudflare address, origin named by Host/SNI. */
    fun cfWs(host: String = "edge-a.pages.dev", ip: String = "188.114.97.6", uuid: String = UUID) =
        "vless://$uuid@$ip:443?security=tls&type=ws&host=$host&path=%2F&sni=$host&alpn=http%2F1.1&fp=chrome#CA%20%F0%9F%87%A8%F0%9F%87%A6"

    fun reality(ip: String = "169.40.42.235", uuid: String = UUID, sni: String = "yahoo.com") =
        "vless://$uuid@$ip:443?security=reality&encryption=none&pbk=e2RLf57Li_-MDZGE9ss1BWPgP54mqRb5PfXhW2jcVVg&headerType=none&fp=ios&type=tcp&flow=xtls-rprx-vision&sni=$sni&sid=c39cc7310a#US"

    fun hy2(ip: String = "91.99.225.11", pass: String = "secretpass", insecure: Boolean = false) =
        "hysteria2://$pass@$ip:443?insecure=${if (insecure) 1 else 0}&sni=refersion.com&obfs=salamander&obfs-password=obfspw#DE"

    fun trojan(ip: String = "5.6.7.8", pass: String = "trojanpass", sni: String = "t.example.org") =
        "trojan://$pass@$ip:443?security=tls&type=tcp&sni=$sni#NL"

    fun node(link: String): FluxNode = (FluxLinkParser.parse(link) as FluxLinkParser.Result.Ok).node

    fun cand(link: String, family: Family = Family.V4, edge: String? = null): FluxCandidate {
        val n = node(link)
        return FluxCandidate(n, edge, family, dialAddress = edge ?: n.server)
    }
}
