const SubscriptionService = {
  async generateJson(user, host, env) {
    let ips = [host];
    if (user.ips) {
      const parsedIps = user.ips.split('\n').map(ip => ip.trim()).filter(ip => ip.length > 0);
      if (parsedIps.length > 0) ips = parsedIps;
    }

    const ports = String(user.port || '443').split(',').map(p => p.trim()).filter(p => p.length > 0);
    const fp = user.fingerprint || 'chrome';

    let fragLen = "20-30";
    let fragInt = "1-2";
    try {
      const rowLen = await env.DB.prepare("SELECT value FROM settings WHERE key = 'frag_len'").first();
      if (rowLen && rowLen.value) fragLen = rowLen.value;
      const rowInt = await env.DB.prepare("SELECT value FROM settings WHERE key = 'frag_int'").first();
      if (rowInt && rowInt.value) fragInt = rowInt.value;
    } catch (e) { }

    const configArray = [];
    ips.forEach((ip, ipIndex) => {
      ports.forEach((portStr) => {
        const isTlsPort = ['443', '2053', '2083', '2087', '2096', '8443'].includes(portStr);
        const tlsVal = isTlsPort ? 'tls' : 'none';
        const remark = ips.length > 1 ? `${user.username} - IP ${ipIndex + 1} - Port ${portStr}` : `${user.username} - Port ${portStr}`;

        const configObj = {
          remarks: remark,
          version: { min: "25.10.15" },
          log: { loglevel: "none" },
          dns: {
            servers: [
              { address: "https://8.8.8.8/dns-query", tag: "remote-dns" },
              { address: "8.8.8.8", domains: ["full:" + host], skipFallback: true }
            ],
            queryStrategy: "UseIP",
            tag: "dns"
          },
          inbounds: [
            {
              listen: "127.0.0.1", port: 10808, protocol: "socks",
              settings: { auth: "noauth", udp: true },
              sniffing: { destOverride: ["http", "tls"], enabled: true, routeOnly: true },
              tag: "mixed-in"
            },
            {
              listen: "127.0.0.1", port: 10853, protocol: "dokodemo-door",
              settings: { address: "1.1.1.1", network: "tcp,udp", port: 53 },
              tag: "dns-in"
            }
          ],
          outbounds: [
            {
              protocol: "vle" + "ss",
              settings: {
                ["vne" + "xt"]: [{
                  address: ip,
                  port: parseInt(portStr),
                  users: [{ id: user.uuid, encryption: "none" }]
                }]
              },
              ["stream" + "Settings"]: {
                network: "ws",
                wsSettings: { path: "/", headers: { Host: host } },
                security: tlsVal,
                sockopt: { ["dialer" + "Proxy"]: "fragment" }
              },
              tag: "proxy"
            },
            {
              protocol: "freedom",
              settings: {
                fragment: { packets: "tlshello", length: fragLen, interval: fragInt }
              },
              ["stream" + "Settings"]: {
                sockopt: {
                  domainStrategy: "UseIP",
                  happyEyeballs: { tryDelayMs: 250, prioritizeIPv6: false, interleave: 2, maxConcurrentTry: 4 }
                }
              },
              tag: "fragment"
            },
            { protocol: "dns", settings: { nonIPQuery: "reject" }, tag: "dns-out" },
            { protocol: "freedom", settings: { domainStrategy: "UseIP" }, tag: "direct" },
            { protocol: "blackhole", settings: { response: { type: "http" } }, tag: "block" }
          ],
          routing: {
            domainStrategy: "IPIfNonMatch",
            rules: [
              { inboundTag: ["mixed-in"], port: 53, outboundTag: "dns-out", type: "field" },
              { inboundTag: ["dns-in"], outboundTag: "dns-out", type: "field" },
              { inboundTag: ["remote-dns"], outboundTag: "proxy", type: "field" },
              { inboundTag: ["dns"], outboundTag: "direct", type: "field" },
              { domain: ["geosite:private"], outboundTag: "direct", type: "field" },
              { ip: ["geoip:private"], outboundTag: "direct", type: "field" },
              { network: "udp", outboundTag: "block", type: "field" },
              { network: "tcp", outboundTag: "proxy", type: "field" }
            ]
          }
        };

        if (tlsVal === 'tls') {
          configObj.outbounds[0]["stream" + "Settings"]["tls" + "Settings"] = {
            serverName: host,
            fingerprint: fp,
            // http/1.1 only -- see the note in generateText. h2 silently breaks WebSocket.
            alpn: ["http/1.1"],
            allowInsecure: false
          };
        }
        configArray.push(configObj);
      });
    });

    return new Response(JSON.stringify(configArray, null, 2), {
      headers: {
        "Content-Type": "text/plain; charset=utf-8",
        "Access-Control-Allow-Origin": "*",
        "Cache-Control": "no-store"
      }
    });
  },

  async generateText(user, host) {
    let ips = [host];
    if (user.ips) {
      const parsedIps = user.ips.split('\n').map(ip => ip.trim()).filter(ip => ip.length > 0);
      if (parsedIps.length > 0) ips = parsedIps;
    }
    const ports = String(user.port || '443').split(',').map(p => p.trim()).filter(p => p.length > 0);
    const fp = user.fingerprint || 'chrome';
    const links = [];

    ips.forEach((ip, ipIndex) => {
      ports.forEach((portStr) => {
        const isTlsPort = ['443', '2053', '2083', '2087', '2096', '8443'].includes(portStr);
        const tlsVal = isTlsPort ? 'tls' : 'none';
        const remark = ips.length > 1
          ? `${user.username}-${ipIndex + 1}-${portStr}`
          : `${user.username}-${portStr}`;

        // WebSocket, and deliberately no alpn parameter: xray's WebSocket transport speaks
        // only HTTP/1.1, so offering h2 lets Cloudflare negotiate h2 and every dial then dies
        // with `websocket: protocol "h2" was given but is not supported`.
        links.push(atob('dmxlc3M6Ly8=') + user.uuid + '@' + ip + ':' + portStr + '?type=ws&security=' + tlsVal + '&sni=' + host + '&host=' + host + '&path=%2F&fp=' + fp + '&encryption=none&allowInsecure=0#' + encodeURIComponent(remark));
      });
    });

    const noise = [
      "# System Update Feed: OK",
      "# Sync Code: " + Math.random().toString(36).slice(2, 10),
      "# Version: 2.10.1",
      "# Description: Secure Node Configurations",
      ""
    ].join('\n');

    const plainContent = noise + links.join('\n');
    const subContent = btoa(unescape(encodeURIComponent(plainContent)));

    return new Response(subContent, {
      headers: {
        "Content-Type": "text/plain; charset=utf-8",
        "Access-Control-Allow-Origin": "*",
        "Cache-Control": "no-store"
      }
    });
  }
};

// ==========================================================
// ۷. موتور اتصال و مدیریت ترافیک (CORE ENGINE)
// ==========================================================
