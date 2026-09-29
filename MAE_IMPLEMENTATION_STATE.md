# MAE — implementation state

_Read this first when continuing MAE work. Architecture: `MAE_ARCHITECTURE.md`. Last update: 2026-09-29 (phone session)._

## Current phase
M0–M6 are built and **tested on a real phone**: Samsung A50, Android 11, MCI network over Wi-Fi.
- The MAE suite is 42 tests, and the full project suite passes (306 JVM + 224 Worker tests, 0 failures).
- The debug APK is installed on the phone.
- M5b is still open: WARP / other foreign providers.

## Proven on the phone (MCI Wi-Fi, 2026-09-29)
| What | Result |
|---|---|
| M0 migration | 2 plaintext accounts → `accounts_sealed` (`v1:`…), plaintext deleted, Cloud tab lists both, API works |
| Onboarding | installed apps pre-selected (YouTube, Telegram, ChatGPT, Gemini, Claude, Google, Facebook) |
| DNS path | base DoH (cloudflare-dns via fragment) dead; MAE chose `https://8.8.8.8/dns-query` via direct (1/5 candidates) |
| Serverless / fragment | **dead on MCI**: timeouts for every host, even unfiltered GitHub → Serverless not usable here; default route auto-switched to direct |
| ChatGPT / Claude | IPv4 served (401), **IPv6 refused as Iran** (403 unsupported_country / app-unavailable-in-region) → **direct IPv4** |
| Gemini | refused on both families (Google 403 page) → GEO YES 0.84 → **Worker**, proven BG (v4) / BG (v6), API accepted |
| YouTube, Instagram, Facebook, Telegram | direct filtered (DNS sinkhole + TLS reset) → **Worker**, exits BG/RO/AZ |
| Google, GitHub | direct (GitHub v4 only, no AAAA) |
| Worker deploy | `mlm-mae-egress.<account>.workers.dev`, v1, UUID sealed; 404 for non-VLESS |
| Tunnel traffic | every service answered through its own outbound (`mae-wkd`, `mae-wk4`, `mae-d4`, `mae-dd`, `mae-d6`) |
| Live API spike | **works** (Unix socket + gRPC `OverrideBalancerTarget`) |
| **Acceptance test** | YouTube keep-alive stream **25/25** while Gemini was switched `direct → worker` live; no reconnect, no core restart |
| 👎 feedback | incident recorded, re-probe at top priority, route re-validated, honest message ("re-checked and works" when nothing changed) |
| Custom site | `proof.ovh.net` added from Manage → probed → direct IPv6 (IPv4 dead) |
| Passive stats | `queryStats` counters read per route every 30 s |
| Network drop | Wi-Fi off/on → VPN reconnected in 3 s, all services working again (after fixing the MyVpnService bug below) |
| Overhead | 4 MB CacheFly download: no VPN 13–19 Mbit/s, through MAE 14–19 Mbit/s → no measurable overhead |
| Foreign-exit cost | stage-C samples (1 MB): direct 11–13 Mbit/s, Worker 7.5–10 Mbit/s (v4 and v6) → ~25–40 % slower, paid only by services that need it |

## Bugs found on the phone and fixed
1. **Probes died on dead Serverless DNS.** Fixed by a per-network DNS path (`DnsPath`, `dnsPathFor`).
2. **Direct wasn't split by family.** Now `mae-d4` / `mae-d6` / `mae-dd`, with per-family proofs for every route.
3. **Unknown traffic went to dead fragment.** Now the default route per network is `defaultVia` → direct.
4. **The Worker was dialled through fragment.** It now dials directly (workers.dev is open on MCI).
5. **Wrong registry probes.** ChatGPT/Claude now use their APIs with measured signatures; Gemini uses `generativelanguage.googleapis.com`.
6. **v6 egress showed "country unknown"** because ip-api has no AAAA. The lookup now falls back to direct.
7. **Deploying the exit didn't re-probe fresh services** (an ordering bug).
8. **Stage C throughput never recorded.** OVH answers plain HTTP with a 301; it now uses CacheFly with a User-Agent.
9. **A second gRPC call on a pooled LocalSocket connection** threw UnsupportedOperationException. Now one connection per call plus a retry.
10. **Route changes that touched UDP were structural.** Now every service has a TCP and a UDP balancer, and Block is a balancer target, so every decision change is live.
11. **The UI said "fixed" when nothing changed.** It now shows `Repair.Verified`.
12. **System Back left MAE from sub-pages.** Fixed with `BackHandler`.
13. **Nothing was re-checked after an app restart.** `refreshStale()` now runs on init.
14. **Custom sites spent Worker quota for nothing.** `nothingToProve` fixes that.
15. **MyVpnService (all engines):** the watchdog reconnect was cancelled by `stopSelf` during its own 3 s wait, so after a network drop the VPN stayed down forever. The internal-reconnect STOP no longer stops the service.
16. **The throughput target had to change twice.** CacheFly refuses the Worker's exit and has no IPv6, and OVH is IPv6-only here, so stage C now tries a list of HTTPS targets.
17. **The home lamp lit V2Ray for MAE.** `ActiveEngines` now maps the `mae` node to the MAE tile.

## Session 2 (same day): WARP, related domains, UI
- **WARP provider (`route/WarpRoute`, `egress/MaeWarp`).** MAE registers its own identity through `WarpIdRelay.registerStandalone` (via the relay Worker on MCI) with the key sealed. The endpoint is chosen per network and rotated when dead.
  - Measured on MCI: plain WireGuard is dropped. With Xray `finalmask` UDP noise, every endpoint works.
  - The exit geolocates to **IR** (ip-api "IR Cloudflare", trace `loc=IR`), so OpenAI, Anthropic and Gemini refuse it. It is therefore a **BYPASS** (unmetered, carries UDP), not a foreign exit.
  - Phone result: YouTube, Telegram, Instagram, Facebook and custom sites → **warp**. Gemini and TikTok → Worker. ChatGPT and Claude → direct IPv4.
- **Related domains for custom sites (`registry/RelatedHosts`, `NetProber.pageText`).** User report: a site opened but never finished loading, because its CDN domains went the default route. MAE now reads the site's home page through its working route, when the site is added and on every "didn't open", and adds the page's other registrable domains to the site. Domains belonging to known apps are excluded.
  - Phone test with bbc.com: learned `bbci.co.uk` and others. `static.files.bbci.co.uk` and `ichef.bbci.co.uk` then went `mae-wgd` and answered.
- **Removing an app now erases everything learned about it** (policies, metrics, proofs, site domains).
- **UI:** "سرویس" → "اپلیکیشن" (en: "app"). Launcher icons for installed apps, a letter tile otherwise (`ui/mae/MaeAppIcon.kt`).
- **Throughput stage** tries CacheFly, then OVH, over HTTPS. CacheFly refuses Worker and WARP exits; OVH works on both.
- **Tests:** full suite 311 JVM + 224 Worker, 0 failures.

## Architecture decisions (unchanged, now validated)
- **Data plane:** Xray (the app's core runs 26.6.27, not the 26.3.27 the binary strings suggested). Kotlin is the control plane.
- **Base config:** the Serverless profile, whose own DNS and default path are adapted per network.
- **Diagnosis:** multi-axis; hints are only priors; learning is per `(service, netKey)`.
- **Foreign exits:** proven per service × family × network.
- **Live switching:** via balancers, with reconnect as the fallback. Passive stats are in-process.

## Files
- **New:**
  - `engines/mae/**`, `ui/mae/**`
  - `assets/mae/services.json`, `assets/mae_egress_worker.js`
  - `data/SecureStore.kt`, `utils/SecretRedactor.kt`
  - `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml`
  - tests in `test/.../engines/mae/`, `test/.../utils/SecretRedactorTest.kt`
- **Modified (additive):**
  - `CloudManager.kt` (sealed storage)
  - `CrashReporter.kt` (redaction)
  - `AndroidManifest.xml` (backup rules; Psiphon exclusion carried over)
  - `VlessXrayInjector.kt` (`active` controller)
  - `MyVpnService.kt` (skip Google fix for MAE; reconnect fix)
  - `HomeDestinations.kt`, `AppScreen.kt`
  - `strings.xml` (en/fa)
  - `CHANGELOG.md`

## Developer tools
- `touch files/mae/debug` (run-as) makes the next connect write Xray's info + access log (`GoLog` in logcat). Delete it to turn it off.
- Diagnostics page: long-press the status line under the connect button.

## Known issues / next
- **M5b (foreign exit #2):** WARP turned out to be Iran-geolocated, so it is a bypass, not an exit. A second real foreign exit is still needed for Cloudflare-hosted geo-blocked sites. Candidates: user-imported VLESS/Trojan configs, the Worker plus a proxyIP/SOCKS exit.
- **Adding or removing a service while connected** is still structural and reconnects. `AddOutbound`/`AddRule` would make it live, now that the spike passed.
- **Mobile-data (Irancell) learning** not phone-tested: mobile data was off on the test phone. Per-network separation is covered by the unit test.
- **Worker exit country varies per connection** (BG/RO/AZ). Proofs are per service, so this is fine, but sticky-country affinity is not implemented.
