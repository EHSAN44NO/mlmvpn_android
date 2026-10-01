# FLUX — MLM FLUX Engine: architecture

FLUX is an adaptive proxy engine. It is not a new protocol. The user picks three things:

- a **country**, or Automatic;
- an **IP version**: IPv4, IPv6 or Both;
- **Connect**.

Everything else is FLUX's decision: protocol, node, Cloudflare edge, address family, fragmenting,
mux, DNS path, standby routes, when to fail over and when to re-test.

FLUX runs no server of its own. The nodes are public, plus any subscription the user adds.

Code:

- `app/src/main/java/com/mlmvpn/scanner/engines/flux/core/`: the control plane. Pure Kotlin, tested on the JVM.
- `engines/flux/`: the Android glue.
- `ui/flux/`: the screen.

## Planes

```
 Control plane (Kotlin)                                          Data plane (existing Xray core)
 ┌───────────────────────────────────────────────────────┐      ┌─────────────────────────────────┐
 │ FluxSources ─► FluxLinkParser (strict) ─► nodes        │      │ TUN (MyVpnService)               │
 │ FluxNet: network key, Cloudflare/IPv6/UDP verdict      │      │  ─► FakeDNS + DoH via the tunnel │
 │ FluxPlanner: warm route? else candidates (gated)       │ ───► │  ─► balancer "flux"              │
 │ FluxRacer: stage 1 reach ─► stage 2 real ─► grace      │      │       flux-0 (primary)           │
 │ EgressVerifier: country measured through the route     │      │       flux-1, flux-2 (standbys)  │
 │ FluxScorer + FluxLearner ─► FluxMemory (per network)   │      │  ─► burstObservatory (failover)  │
 │ FluxConfigCompiler ─► one Xray JSON                     │      └─────────────────────────────────┘
 │ FluxEngine: connect / verify / watch / network change │
 └───────────────────────────────────────────────────────┘
```

- **The data plane is the app's Xray core (libv2ray 26.3.27), unchanged.**
  - It already has VLESS, Trojan, Hysteria2, REALITY, XHTTP, WebSocket, finalmask fragmenting and the burst observatory.
  - No packet crosses JNI, and no scoring runs on the packet path.
- **The Xray core has no AnyTLS.** AnyTLS needs a second core (sing-box or the AnyTLS Go client as an .aar). It is deferred, and the provider interface is ready for it.

## The Cloudflare question

On many Iranian networks Cloudflare's edges are cut. Cloudflare-fronted nodes are then dead on
arrival, however healthy their origin is. FLUX handles this in four places:

1. **Measuring.** `FluxNet.measureVerdict` makes a TCP connect to three random Cloudflare edges per family, from the
   real network, in about 1.5 s. It runs side by side with name resolution, and only when the
   verdict is older than 30 minutes.
2. **Gating.** `FluxPlanner` produces no Cloudflare candidate when the verdict says cut. The race
   is then spent only on direct routes: VLESS REALITY, Trojan/VLESS TLS and Hysteria2 over UDP.
3. **Learning.** `FluxLearner` updates the verdict from the race itself:
   - any Cloudflare candidate that answered means "reachable";
   - three or more that all failed at TCP mean "cut";
   - one dead edge proves nothing.
4. **Sources.** The sources mix both kinds on purpose:
   - Free-Configs (patterniha): Cloudflare-fronted, tested from Iran by its publisher;
   - MatinGhanbari's filtered lists: REALITY, Hysteria2 and Trojan nodes, none behind Cloudflare.

   None of these is a list that Free Configs or Quick Connect reads. FLUX is a separate system.

Where Cloudflare does reach, a CDN-fronted node is expanded over edges. The node's Host, SNI and path
stay the same; only the dialled address changes. Each network remembers its own best edges, up to 8 per
family.

## Connect

```
Connect
  ├─ known good route on this network for (country, IP mode)?  ── yes ─► tunnel (primary + 2 standbys)
  │                                                                       └─ verify ─► Connected
  └─ no (or it failed verification)
       ├─ verdict (cached ≤30 min, else measured) ‖ resolve server names (≤3 s)
       ├─ candidates: gated by Cloudflare / IPv6 / UDP, by the circuit breaker, by proven country
       ├─ wave 1: verified TLS only ─► race ─► tunnel ─► verify
       └─ wave 2: + self-signed Hysteria2 (only if wave 1 found nothing)
```

- **Warm start:** a second connect on the same network uses the stored route without any race.
- **Verification:** "Connected" is shown only after a real request through the running tunnel
  succeeds. The canary goes through the tunnel's own HTTP inbound at LocalPort+10000.
  `MyVpnService` reports CONNECTED when the core *starts*, so FLUX does not take that as proof.

## Race (`FluxRacer`)

- **Stage 0:** strict parsing. Rejected:
  - malformed links;
  - plaintext VLESS;
  - certificate checks off (VLESS/Trojan);
  - REALITY without a key;
  - unsupported transports.

  Duplicates are removed. Node ids are a hash, and a credential is never stored or logged in clear.
- **Stage 1:** a TCP connect plus a TLS handshake with the node's own SNI. This is where Iranian
  filtering usually strikes, with a reset after the ClientHello. Concurrency:
  - Wi-Fi: 12;
  - mobile: 8;
  - Battery Saver or a low-RAM phone: 4.
- **Stage 2:** one probe core holds all the survivors. Each has its own SOCKS port, mapped
  **explicitly** from candidate id to port, so a probe can never measure the wrong node. Each candidate gets:
  - a real `generate_204` request, for the round trip;
  - two echo requests (Cloudflare trace and ip-api), for the exit's country.
- **Winner:** the first success is only provisional. A 250 ms grace window lets a better
  candidate still win. Ranking is by score, not by arrival.

## Score, memory, breaker

- **Score** = RTT, success EWMA, throughput, stability (p95/p50), handshake and freshness.
  - Minus consecutive failures, a small cost for fragmenting, and a large one for self-signed.
  - Switching needs a 15% margin (hysteresis).
- **Memory is per network**: `net|candidate` → metrics; `net|country|mode` → routes. Changing the country
  forgets nothing.
- **Circuit breaker:**
  - 3 failures in a row → exponential cooldown, from 1 min up to 6 h;
  - often failing and almost never working → quarantined for 24 h;
  - a route that failed with the tunnel up is benched at once.
- **Country:** `nodeId@edge` → the exit identity:
  - two agreeing sources → confidence 1;
  - one source → 0.6;
  - disagreement → no country;
  - the proof expires after 24 h.

  The country is kept per edge because a Worker-based node exits near the edge that carried it.
  The node's label is never used.

## Tunnel config (`FluxConfigCompiler`) — no leaks

- **Routes:** primary plus 2 standbys behind one `leastLoad` balancer. The standbys cost more, and
  `burstObservatory` checks each route every 1 min (2 min on mobile data). When the primary dies,
  Xray moves new connections to a standby in under a second, without restarting the TUN.
- **DNS:** FakeDNS answers apps. The real resolver is DoH (8.8.8.8, then 1.1.1.1) and is reached
  through the balancer. Port-53 traffic goes to Xray's DNS, so no plaintext lookup leaves the phone.
- **Dialling:** candidates dial literal IPs only, so the tunnel never needs a resolver to come up.
- **Direct routing:** only LAN ranges go direct. The FakeDNS pools never do. Iran's block page is blackholed.
- **IPv4 mode:** remarks are `mlm-flux-v4` and the DNS asks for A records only, with no IPv6 pool.
  `MyVpnService` adds no IPv6 address, so Android **blocks** IPv6 instead of letting it leak.
- **QUIC:** refused through the app's QuicRefuser when the primary cannot carry UDP (WebSocket/CDN),
  so apps fall back to TCP at once.

## IP selector semantics

- **IPv4 / IPv6:** the family used from the user's network to the server or edge, and the family
  apps are offered inside the tunnel.
- **Both:** both families are raced (happy-eyeballs style) and the score decides per network.
- **Exit family:** the families the exit really has are measured and shown in diagnostics.

## Fragment and mux

- **Fragment** is a tool, not a default. When a network has seen SNI resets, the planner adds a
  fragmented twin (publisher-tested `fm` or a built-in profile). The learner keeps fragmenting for
  that network only while plain fails, and drops it as soon as plain works again.
- **Mux** is off by default. It is turned on per network only after a measured gain of 15% or more
  (`FluxLearner.decideMux`). The on-device benchmark that drives it is still pending.

## Watching

- **Health check:**
  - while connected and only with the screen on;
  - at 1 min, stretching to 5 min while the route is healthy;
  - two failures in a row → the primary is benched and FLUX re-plans (standby or race).
- **Network change:** `MyVpnService`'s reconnect asks `FluxEngine.freshConfig` for the new network's
  route. Six seconds later FLUX checks that the tunnel works; if it does not, it races.
- **Background, after a connect** (never in the way of one):
  - a second-source country check;
  - a throughput sample (1 MB on Wi-Fi, 256 KB on mobile, inside a 3 MB/day budget);
  - a verdict refresh.

## MAE integration

- `FluxRoute : RouteProvider` (kind FOREIGN, ids `flx-xxxxxx`) adds up to 2 of FLUX's proven routes on the
  current network to `MaeEngine.providers()`.
- They are **read from FLUX's store only**. FLUX never probes, races or starts a tunnel for MAE.
- MAE measures these exits with its own probe core, per service, like any other exit. That rules
  out an MAE → FLUX → MAE loop and two owners for one TUN:
  - standalone, FLUX owns the TUN;
  - inside MAE, FLUX only contributes outbounds.

## Persistence

`filesDir/flux/state.json`:

- schema 1, with a hand-written codec;
- written to `.tmp` and renamed, off-thread and batched;
- moved aside if corrupt;
- networks unseen for 30 days are pruned.

Source caches live in `filesDir/flux/src/`. The `flux/` directory is excluded from backups.

## What the user sees

- **Main screen:** title, country, IPv4/IPv6/Both, Connect, and one honest status line. Examples:
  - «در حال پیدا کردن بهترین مسیر…» while searching;
  - "Connected · 🇩🇪 Germany · IPv6 · 42 ms" when connected;
  - "No route for this country here — available: Netherlands · France" on failure.
- **Country list:** only countries with a proven exit. Those not yet checked on this network are marked.
- **Diagnostics:** a long press on the FLUX title opens them:
  - network verdict;
  - the routes in the tunnel and why each was chosen;
  - the last race (redacted);
  - failovers;
  - the user's own subscriptions.
