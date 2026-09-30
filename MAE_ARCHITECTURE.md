# MAE — MLM Adaptive Engine: architecture

MAE is a meta-engine. The user names the services they care about. MAE learns, for each service
on each network, which path actually works best, and sends each service down its own path. One
goal overrides the rest: **keep Serverless speed for everything that does not need a foreign IP,
and send only the traffic that does through a foreign exit.** MLMVPN runs no server for this.

Code: `app/src/main/java/com/mlmvpn/scanner/engines/mae/` (control plane) and `ui/mae/` (UI).

## Planes

```
             Control plane (Kotlin, slow path)                    Data plane (Xray, fast path)
 ┌──────────────────────────────────────────────────┐     ┌────────────────────────────────────┐
 │ ServiceRegistry ─ hints                           │     │ TUN ─► sniff (tls/http/quic/fakedns)│
 │ NetContext (netKey)                               │     │     ─► rules: domain / IP range    │
 │ Discovery ─► Prober (NetProber | FakeNet)         │     │     ─► balancer svc-<id> ─► outbound│
 │ Classifier ─► Diagnosis (multi-axis)              │ ──► │     ─► unmatched: Serverless rules │
 │ RouteScorer + PolicyEngine ─► ServicePolicy       │     │ counters: outbound>>>tag>>>traffic │
 │ MaeConfigCompiler ─► one Xray JSON                │ ◄── │ queryStats (in-process)            │
 │ XrayApiClient ─► OverrideBalancerTarget (live)    │ ──► │ gRPC API on Unix socket            │
 │ MaeStore (per-network memory)                     │     └────────────────────────────────────┘
 └──────────────────────────────────────────────────┘
```

- **The data plane is the existing Xray core (v26.3.27, `libv2ray.aar`), not a new core.**
  - Packets never cross JNI.
  - The Go router matches a rule once per connection.
  - After that, the connection is a plain socket pair.
  - Rust was considered and rejected for now, because it would mean rebuilding what Xray already does well. It will be revisited only if benchmarks show MAE adding overhead that Xray itself does not have.
- **The base config is `assets/serverless_v48_low_delay.json`, unchanged.** Anything MAE does not route explicitly keeps exactly the Serverless path and speed. MAE's rules sit after the base's DNS plumbing and before its own rules.

## Components

| Component | File | Role |
|---|---|---|
| Service registry | `registry/ServiceRegistry.kt`, `assets/mae/services.json` | Data only: domains, IP ranges, packages, probe URL plus region/ok signatures, and **hints** (priors). It is versioned and parsed by schema. A remote update must be signed (the `Minisign` infrastructure exists) and can never contain code. |
| Diagnosis | `model/Diagnosis.kt` | Independent axes, each YES/NO/UNKNOWN with a confidence and the evidence behind it. `primary` exists for display only. |
| Requirements | `model/Service.kt` `ObservedRequirements` | What a service needs on **this network**. Seeded weakly from hints and overridden by observation. |
| Prober | `probe/NetProber.kt` | Direct probes go out on the underlying network. Every other route is probed through a **probe core's** SOCKS inbound for that exact outbound, so the probe uses the same transport as the real traffic. Probes are tiny anonymous GETs that read at most 4 KB. |
| Discovery | `policy/Discovery.kt` | The staged budget, described below. It also decides which foreign exits and address families are **proven for this service**. |
| Classifier | `classify/Classifier.kt` | Data-driven weighted rules, combined as 1−Π(1−w). An axis is decided at ≥0.75 with a 0.2 margin; otherwise it stays UNKNOWN. A bare 403 carries a weight of only 0.2. |
| Scorer | `policy/RouteScorer.kt` | Filters by hard requirements, then scores success (EWMA, so old history fades), throughput, RTT, cost, quota, health and failure streak. It applies hysteresis (15% of the current score, at least 0.05) and respects a user pin. A fail-open app is never blocked: with nothing alive it falls back to the network's default route. |
| Policy | `policy/PolicyEngine.kt` | Candidates per netKey, decisions, merging discovery results, feedback, TTL (2–10 h, growing with confidence) and the UDP policy. It also reads the **user's evidence** (7 days: "country refusal", "did not open on route X"), so a routine refresh cannot undo a repair, and on a network never seen it gives a fail-closed app the foreign exit it was proven on elsewhere. |
| Repair ladder | `policy/RepairLadder.kt` | Five rungs per app per network after "didn't open". Rung 1 goes abroad at once for a geo-restricted app on a local route; from rung 2 the user is asked what they see; rung 5 keeps the foreign requirement. Cancelling the question costs no rung. |
| Compiler | `compile/MaeConfigCompiler.kt` | Base + outbounds + one balancer per service + rules ordered from most specific domain down + FakeDNS domains + stats + the API. It also builds the probe config. |
| Live control | `control/XrayApiClient.kt`, `GrpcWire.kt` | Spike: exactly one gRPC call over a filesDir Unix socket, with protobuf encoded by hand. |
| Health | `route/CircuitBreaker.kt` | Per route **and network** (`route@net`): opens after 3 failures, then backs off exponentially. It judges transport health (`RouteHealth`: direct = TCP connects, bypass = anything answered through it, foreign = the exit is alive), never whether a filtered app answered, and a route seen alive in the last 10 minutes is not failed by one bad round. |
| Live canary | `probe/LiveCanary.kt` | One small request through the **running tunnel**, into its own loopback HTTP inbound (`mae-canary-in`, LocalPort+10000), through the rules and the app's balancer. Runs 8 s after connecting, every 5 min with the screen on, and on unlock after 3+ minutes with the screen off. |
| Cloud configs | `egress/MaeCloudConfigs.kt` | Foreign exits from the user's own Cloudflare panels (BPB, EDG, MLM, Netra, then GZG, NVA, NHN), read through the arena's adapters, refreshed every 12 h. Each link is put on a clean IPv6 edge first and on IPv4 as the fallback (per-network family verdict); discovery keeps the fastest. |
| Device signals | `device/DeviceSignals.kt` | SIM / network country and time zone, for apps that check the phone itself (TikTok): no route fixes those, so the row says so. |
| Engine | `MaeEngine.kt` | Queues (incidents, then fail-closed / geo apps, then the rest), probe-core lifecycle, connect/apply, the session observer (whatever started the tunnel), the network and screen watch, the canary and self-healing, passive stats and deployment of the foreign exit. |
| Store | `store/MaeState.kt`, `MaeStore.kt` | `filesDir/mae/state.json` with a schema version (2) and migration. Writes are batched off the caller's thread, go to `.tmp` and are then renamed. A corrupt file is moved aside and MAE relearns. Networks unseen for 30 days are pruned. |

## Route providers

`route/RouteProvider.kt`. Each provider only *describes* itself as outbounds with stable tags. No
tag is a prefix of another, because balancer selectors match by prefix. Adding a foreign exit
means adding one class here; the compiler, scorer and UI do not change.

| id | kind | tag(s) | notes |
|---|---|---|---|
| `direct` | DIRECT | `mae-d4` / `mae-d6` / `mae-dd` | freedom; `sockopt.domainStrategy` |
| `serverless` | BYPASS | `tcp-fragment-tls` (from base) | exactly Serverless's own path |
| `fragment` | BYPASS | `mae-frag` | Game Booster's fragment recipe, as a second strategy |
| `warp` | BYPASS | `mae-wgd` | **One** WireGuard outbound per core (`ForceIP`, UDP noise). Three same-key sessions per core used to knock each other off. The probe core uses a second WARP identity. WARP exits with an Iranian address, so it is never a foreign exit. |
| `worker` | FOREIGN (candidate) | `mae-wk4` / `mae-wk6` / `mae-wkd` | VLESS-WS to the user's Worker, dialled through `tcp-fragment-tls` via `dialerProxy`. It sets the outbound-level `targetStrategy` (`ForceIPv4`/`ForceIPv6`/none). TCP only and quota-limited. **It cannot reach Cloudflare-hosted destinations.** |
| `cfg-<hash>` | FOREIGN (candidate) | `mae-cfg-<hash>-4/6/d` | The user's saved VLESS/Trojan configs, at most 3 at a time. The id is a hash of the config, so proofs and metrics stay with the right server when the list is re-sorted. The window is sticky: only dead ones are replaced. |
| `cfc-<hash>` | FOREIGN (candidate) | `mae-cfc-<hash>-4/6/d` | Configs from the user's own Cloudflare panels (`MaeCloudConfigs`), on a clean IPv6 edge first, IPv4 as the fallback. Probed only for apps that may need to go abroad. |
| `usexit` | FOREIGN (candidate) | `mae-usx` | The US exit (a Durable Object on the user's account), reached through one of their Cloudflare configs. Gemini / Flow. |

Freedom and proxied outbounds use different strategy fields. The compiler never assumes they are
the same. Xray silently ignores unknown JSON fields, so the real check of the chosen family is
behavioural: a V4_ONLY exit whose echo shows an IPv6 address is recorded as not proven.

## Discovery budget

- **Stage A:** every local route gets DNS (system vs DoH, resolved once per discovery), TCP, TLS (with certificate/hostname check) and one small GET. RTT is timed from the TCP connect, so a proxied route is not favoured for skipping DNS. Foreign routes are probed only when geo is not already ruled out, or always for an app marked `requiresForeign` (TikTok); no Worker quota is spent on a service that works from Iran.
- **Stage B:** survivors get 3 RTT samples.
- **Stage C:** only the 2 fastest *routes* (not two families of one route) get a throughput sample (256 KB on mobile data, 1 MB on Wi-Fi) from a target that is not behind Cloudflare, so a Worker can reach it.
- **Exit echo cache:** an exit's IP and country are kept 10 minutes per route × family × network and shared across apps.
- **Offline guard:** on a network that is not validated (or is a captive portal) nothing is learned; the check waits until the network validates.
- **Concurrency:** at most 3 at a time (2 on cellular).
- **After connecting:** learning continues passively from Xray's per-outbound counters (`CoreController.queryStats`, in-process, no gRPC).

## Foreign egress: proof, not assumption

A foreign exit counts **for one service, one family and one network** only when both of these hold:
1. **Seen through the route itself.** The exit IP comes from `api64.ipify.org` (plain TCP, so a Worker `connect()` is exercised as real traffic would), and the country from two unrelated services (ip-api.com and ipinfo.io). If they disagree, no country is claimed.
2. **Accepted by the service.** The service's own acceptance probe is served through that route and family.

Status per service × network: `Untested`, `Proven(route, family)` or `NoneFound(rejected → reason)`.
The UI reports NoneFound honestly. **Success for a geo-restricted service means a provider passed
that service's own probe**, not that an IP checker shows a foreign IP.

Known facts from Cloudflare's documentation and the project's own history:
- Worker `connect()` refuses Cloudflare IPs, so ChatGPT (behind Cloudflare) cannot be reached through the Worker.
- The Worker's IPv6 exit is refused by Gemini.
- A Worker's placement is not the same as its egress country.

That is why providers are pluggable. WARP turned out to exit with an Iranian address, so it is a bypass route. The foreign exits today are the Worker, the user's saved configs, configs from the user's Cloudflare panels, and the US exit.

## Service identification (layered)

1. Sniffed hostname (TLS SNI, HTTP Host, QUIC).
2. FakeDNS: the service's domains are added to the base's `fakedns` server, so the app receives a fake IP and Xray maps it back to the name even when no SNI is readable (ECH). As a side effect, a foreign service's name is resolved at the exit, with no local DNS query.
3. Registry IP ranges, for apps that never resolve a name (Telegram MTProto).

4. **Per-app rules (Android 10+).** For a chosen app that is installed, Xray `process` rules (by
   UID) send all of the app's traffic to its balancers, ahead of the domain rules: every domain,
   fixed IPs and CDN hosts included (TikTok's many APIs, Instagram's video CDN). MyVpnService then
   turns on `routeOnly` sniffing and turns IPv6 off inside the tunnel.

In a browser the service is still identified by domain and destination.

## Live switching

Every service has its own balancer `svc-<id>`. A repair is
`OverrideBalancerTarget(svc-<id>, newTag)`:
- existing connections stay on their old route (affinity);
- new connections take the new one;
- other services are untouched.

A repair that moves an app to another route is applied **by reconnect** instead, because
affinity kept the app's open connections on the route the user just said does not work.

Every service also has a QUIC balancer (`svc-<id>-q`, UDP 443). A route that cannot carry QUIC
(the bypass routes) blocks it there, so the app falls back to TCP at once.

The API listens only on a **Unix socket in filesDir**. It has no authentication, and on a TCP port
any app could add an outbound and steer the user's traffic. The first time MAE connects it runs a
harmless spike (re-pointing a balancer at the target it already has). If that fails, live switching
is retried on the next few connections and again after an app update. If it keeps failing,
MAE recompiles and reconnects instead.

## Self-healing

A route can die under an app while nothing looks: the probe core measures routes with its own
sessions, never the tunnel's. The live canary looks at the tunnel's. It checks one app per
route × family in use, twice. For a dead one on a validated network:
- WARP dead, or every route dead → **reconnect** (at most once in 3 minutes). This gives fresh
  sessions and this network's config.
- Otherwise → **live failover**. The failure is recorded on the route and in the breaker, the next
  proven route takes over through `OverrideBalancerTarget`, and the app is re-checked first.

The row says "Its route had stopped working; MAE fixed it by itself." A region refusal seen by
the canary only queues a re-check.

## Failure behaviour

- **Fail-open** (the default): with no proven route, the service takes the network's default route (direct where direct is proven, else Serverless), then any alive bypass, then any alive route. It is never blocked while a candidate exists.
- **Fail-closed** (services flagged in the registry, e.g. accounts that get banned for an Iranian IP): the service goes to `block` rather than leaving by the wrong path. On a network never seen, it takes the foreign exit it was proven on elsewhere, or waits in `block` until proven. It never takes an Iranian address meanwhile.
- A foreign service on a TCP-only exit has its UDP blocked, so QUIC falls back to TCP instead of leaving directly.
- **Crash isolation:** discovery, the probe core and providers fail inside `runCatching` and the circuit breaker. The tunnel keeps its last good config.
- **Session:** MAE follows MyVpnService's phase however the tunnel was started (its screen, the Quick Settings tile, the service's own reconnect). A scheduled reconnect never restarts a tunnel the user stopped. Auto-switch never replaces a MAE session. The watchdog and the tile compile a fresh config for the current network.
- **Kill switch:** MyVpnService (which MAE uses) has none. The kill switch lives only in TunnelVpnService (MASQUE/WG/…), and MAE changes neither.

## Security and privacy

- Cloudflare credentials are sealed with an Android Keystore AES-GCM key (`data/SecureStore.kt`) and excluded from backup. Crash reports pass through `SecretRedactor`.
- The Worker's UUID, both WARP identities and the Cloudflare panel links are sealed too.
- The compiled config (which carries them unsealed) is not stored for the Quick Settings tile. The tile keeps only the marker `mae` and compiles a fresh config.
- The credential only ever goes to api.cloudflare.com (`CfWorkers`), never to MLMVPN.
- MAE stores only the services the user chose, never visited hosts. Network keys are 8-byte hashes (NetworkKey).
- No TLS interception. Probes read no user content.
- Nothing is sent to an MLMVPN backend. Shared intelligence is not built; if it ever is, it must be opt-in and aggregate (ASN × service → route success) and carry no hosts or identity.
