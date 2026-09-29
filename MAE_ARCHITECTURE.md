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
| Scorer | `policy/RouteScorer.kt` | Filters by hard requirements, then scores success, throughput, RTT, cost, quota, health and failure streak. It applies 15% hysteresis and respects a user pin. |
| Policy | `policy/PolicyEngine.kt` | Candidates per netKey, decisions, merging discovery results, feedback, TTL (2–10 h, growing with confidence) and the UDP policy. |
| Compiler | `compile/MaeConfigCompiler.kt` | Base + outbounds + one balancer per service + rules ordered from most specific domain down + FakeDNS domains + stats + the API. It also builds the probe config. |
| Live control | `control/XrayApiClient.kt`, `GrpcWire.kt` | Spike: exactly one gRPC call over a filesDir Unix socket, with protobuf encoded by hand. |
| Health | `route/CircuitBreaker.kt` | Per provider: opens after 3 failures, then backs off exponentially, allowing one recovery probe at a time. |
| Engine | `MaeEngine.kt` | Queue (incidents first), probe-core lifecycle, connect/apply, the network watch, passive stats and deployment of the foreign exit. |
| Store | `store/MaeState.kt`, `MaeStore.kt` | `filesDir/mae/state.json` with a schema version and migration hook. Writes go to `.tmp` and are then renamed. A corrupt file is moved aside and MAE relearns. |

## Route providers

`route/RouteProvider.kt`. Each provider only *describes* itself as outbounds with stable tags. No
tag is a prefix of another, because balancer selectors match by prefix. Adding a foreign exit
means adding one class here; the compiler, scorer and UI do not change.

| id | kind | tag(s) | notes |
|---|---|---|---|
| `direct` | DIRECT | `mae-direct` | freedom; `sockopt.domainStrategy` |
| `serverless` | BYPASS | `tcp-fragment-tls` (from base) | exactly Serverless's own path |
| `fragment` | BYPASS | `mae-frag` | Game Booster's fragment recipe, as a second strategy |
| `worker` | FOREIGN (candidate) | `mae-wk4` / `mae-wk6` / `mae-wkd` | VLESS-WS to the user's Worker, dialled through `tcp-fragment-tls` via `dialerProxy`. It sets the outbound-level `targetStrategy` (`ForceIPv4`/`ForceIPv6`/none). TCP only and quota-limited. **It cannot reach Cloudflare-hosted destinations.** |

Freedom and proxied outbounds use different strategy fields. The compiler never assumes they are
the same. Xray silently ignores unknown JSON fields, so the real check of the chosen family is
behavioural: a V4_ONLY exit whose echo shows an IPv6 address is recorded as not proven.

## Discovery budget

- **Stage A:** every local route gets DNS (system vs DoH), TCP, TLS (with certificate/hostname check) and one small GET. Foreign routes are probed only when geo is not already ruled out; no Worker quota is spent on a service that works from Iran.
- **Stage B:** survivors get 3 RTT samples.
- **Stage C:** only the 2 fastest get a throughput sample (256 KB on mobile data, 1 MB on Wi-Fi) from OVH, which is not behind Cloudflare so a Worker can reach it.
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

That is why providers are pluggable (M5b). WARP and user-imported configs are the next candidates.

## Service identification (layered)

1. Sniffed hostname (TLS SNI, HTTP Host, QUIC).
2. FakeDNS: the service's domains are added to the base's `fakedns` server, so the app receives a fake IP and Xray maps it back to the name even when no SNI is readable (ECH). As a side effect, a foreign service's name is resolved at the exit, with no local DNS query.
3. Registry IP ranges, for apps that never resolve a name (Telegram MTProto).

Per-app routing is not used for classification, because browsers open everything. The service is
identified by domain and destination.

## Live switching

Every service has its own balancer `svc-<id>`. A repair is
`OverrideBalancerTarget(svc-<id>, newTag)`:
- existing connections stay on their old route (affinity);
- new connections take the new one;
- other services are untouched.

The API listens only on a **Unix socket in filesDir**. It has no authentication, and on a TCP port
any app could add an outbound and steer the user's traffic. The first time MAE connects it runs a
harmless spike (re-pointing a balancer at the target it already has). If that fails, live switching
is turned off permanently for this phone and MAE recompiles and reconnects instead.

## Failure behaviour

- **Fail-open** (the default): with no proven route, the service stays on the fast Serverless base.
- **Fail-closed** (services flagged in the registry, e.g. accounts that get banned for an Iranian IP): the service goes to `block` rather than leaving by the wrong path.
- A foreign service on a TCP-only exit has its UDP blocked, so QUIC falls back to TCP instead of leaving directly.
- **Crash isolation:** discovery, the probe core and providers fail inside `runCatching` and the circuit breaker. The tunnel keeps its last good config.
- **Kill switch:** MyVpnService (which MAE uses) has none. The kill switch lives only in TunnelVpnService (MASQUE/WG/…), and MAE changes neither.

## Security and privacy

- Cloudflare credentials are sealed with an Android Keystore AES-GCM key (`data/SecureStore.kt`) and excluded from backup. Crash reports pass through `SecretRedactor`.
- The Worker's UUID is sealed too.
- The credential only ever goes to api.cloudflare.com (`CfWorkers`), never to MLMVPN.
- MAE stores only the services the user chose, never visited hosts. Network keys are 8-byte hashes (NetworkKey).
- No TLS interception. Probes read no user content.
- Nothing is sent to an MLMVPN backend. Shared intelligence is not built; if it ever is, it must be opt-in and aggregate (ASN × service → route success) and carry no hosts or identity.
