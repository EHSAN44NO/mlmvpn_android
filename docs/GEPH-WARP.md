# Geph («گف») and standalone WARP («وارپ») on Android

Both shipped in 1.2.36. This is the contract each engine depends on, and how to update it.

## Geph

### Binary
- `jniLibs/{arm64-v8a,armeabi-v7a}/libgeph.so` is **Geph's own Android build, unmodified**, cut out of the official APK.
- Update it with `node scripts/install-geph-binary.js`. The script:
  1. fetches `metadata.yaml` and `.minisig` from Geph's release mirrors;
  2. verifies the minisign signature against Geph's key (the one hard-coded in upstream `updates.rs`);
  3. downloads `android-stable/<ver>/geph-android.apk`, checks its signed sha256, and extracts both `lib/*/libgeph.so`.
- The store runs the same chain at runtime (`StoreSource.SignedManifest`, `scanner/store/Minisign.kt`: Ed25519 over a BLAKE2b-512 prehash).
- The binary has no `--version`; the store probe is `--help`.

### Config (`core/geph/GephConfig.kt`)
- The upstream struct is `deny_unknown_fields`. **Any extra key kills the engine at start.** `GephConfigTest` pins the exact key set; update it together with upstream.
- `broker`, `tunneled_broker` and `broker_keys` are copied verbatim from upstream `default-config.yaml`.
- `cache` is one **file** per credential (`no_backup/geph/cache/db-<sha256[:32]>`).
  - The network hands out about 20 connect tokens per day, and a cold cache spends one on every start.
  - Never point two running engines at one file. `GephExits.findFastest` probes on a copy.
- `control_listen_unix` carries control as newline JSON-RPC over a LocalSocket. There is never a TCP control port, because that port would have no authentication.
- Methods used: `conn_info`, `stat_num`, `stat_history`, `recent_logs`, `net_status`, `start_registration`, `poll_registration`, and `broker_rpc`.
  - The `broker_rpc` calls are `get_user_info_by_cred`, `get_account_secret_status`, `rotate_account_secret`, `get_free_voucher`, `redeem_voucher`, and `get_news`.
- `RUST_LOG=geph=info`. The debug default writes an SQLite row per log line.

### Tunnel (`TunnelVpnService`, protocol `GEPH`)
- **TUN addressing:** `100.64.89.64/10` + `fd00::1/64`, routes `0.0.0.0/0` + `::/0`, DNS `1.1.1.1`, MTU 16384 (Geph's ipstack segment size). The app excludes itself.
- **API 26 and up:** under a global lock, `Os.dup2(tunFd, 0)`, then spawn with `Redirect.INHERIT` and `--vpn-fd 0`, then restore fd 0 to `/dev/null`.
- **API 24–25:** tun2proxy feeds Geph's SOCKS port (20850).
- **"Connected"** requires `conn_info` = Connected **and** a real TLS request through an internal `port_forward` to `www.gstatic.com:443`. No general proxy is opened for this.
- **Recovery:**
  - A process death or a default-network change restarts the engine with the TUN kept, up to `GEPH_MAX_RESTARTS=4`.
  - A send-without-receive stall is tested, then restarted.
- **Proxy-only coverage** runs in the same service without a TUN. Ports: SOCKS 20850, HTTP 20851, PAC 20852.

## WARP (standalone)
- **Identity:** `WarpIdRelay.registerStandalone` registers with WARP for Android client headers.
  - It tries a direct request first and falls back to the user's Worker.
  - It then sends `PATCH {"warp_enabled":true}`. A fresh registration passes nothing until this is sent.
  - The identity is kept apart from the identity of the three aether transports.
- **Engine:** `libaether.so` (the same binary as MASQUE/WireGuard) with `AETHER_PROTOCOL=wg`, its own data dir, and SOCKS on 20870 (`0.0.0.0` when LAN sharing).
- **Connect:** `cdn-cgi/trace` through the SOCKS port must answer `warp=on` within 150 s. **Only then** is the TUN opened, with tun2proxy in front.
- **Watchdog:** a re-verify every 45 s. After two failures it runs `dropLastEndpoint` (deletes `aether-lastconn.toml`) and restarts the engine with the TUN kept.

## Measured on the phone (2026-09-27, Iran, Wi-Fi)
- **Geph:** connected in under 2 s, exit LT (`88.216.197.28`). IPv4 and IPv6 both leave from Geph's exit (no leak), DNS on `tun0` is `1.1.1.1`. Download from `proof.ovh.net`: 1.6 Mbit/s in one stream, and 0.51 + 0.57 + 0.51 = 1.6 over three at once. That is the Free plan's account-wide cap (`level=Free` in the auth log), not the route; nothing on our side raises it. The exit refuses UDP 123 and TCP 7011 (`Proxying ... is not allowed`).
- **WARP identity:** the direct API probe times out. With `api.cloudflareclient.com` as the SNI **no** Cloudflare address answers the ClientHello (141.101.113.x, 104.16.x, 162.159.137.105), whole or cut into 1–29 byte segments with pauses: the filter reassembles. `www.cloudflare.com` on the same addresses completes in under a second. No SNI also gets no reply, and a mismatched SNI/Host is refused by Cloudflare with 403. So on this network only the user's Worker can make the identity; `registerStandalone` tries it before the front. Through the Worker (healthy, `/_health` 200) Cloudflare first answered 429 / error 1015 for about three minutes, then registered; a 429 now earns one more ask after the front. Once made: `warp=on` 38 s after the tap (endpoint `188.114.96.253:2506`, rtt 432 ms, profile balanced), 40 Mbit/s down from `proof.ovh.net`, IPv4 `104.28.x` and IPv6 `2a09:bac5:…` (both WARP), DNS `1.1.1.1`. The probe used for this is a dex run with `app_process` from `/data/local/tmp` (shell uid, the phone's own network).

## Still to measure on a device
- Geph:
  - account by proof-of-work, cold and warm connect time;
  - exit country, speed;
  - leak checks for IPv4, IPv6 and DNS;
  - network switch;
  - proxy mode, LAN sharing, and the fastest finder.
- WARP:
  - on the bundled aether 1.4.0 and on 2.1.0 from the store;
  - bump the bundled baseline only if 1.4.0 carries no data.
- Whether Worker-made identities of the three aether transports also come back `warp_enabled=false`. Add the PATCH there only if that is measured.
