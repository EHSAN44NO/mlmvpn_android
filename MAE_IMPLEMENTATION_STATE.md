# MAE — implementation state

_Read this first when continuing MAE work. Architecture: `MAE_ARCHITECTURE.md`. Last update: 2026-09-30 (review + self-healing, version 1.2.38)._

## Current phase
M0–M6 are built and **tested on a real phone**: Samsung A50, Android 11, MCI network over Wi-Fi.
- The MAE suite is 42 tests, and the full project suite passes (306 JVM + 224 Worker tests, 0 failures).
- The debug APK is installed on the phone.
- Foreign providers: the Worker, the user's saved configs, configs from the user's Cloudflare panels, and the US exit. WARP exits with an Iranian address, so it is a bypass route.
- Session 4 (below) is **not yet verified on a phone**. The scenarios to run are at the end of that section.

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

## Session 3: Irancell, installed apps, repair ladder
- **Irancell (phone, mobile data):** MAE saw a new network key and learned it separately. The MCI memory was kept.
  - DNS: the base Serverless DoH works here (it was dead on MCI).
  - Routes: YouTube/Facebook/GitHub/Google → fragment; Instagram → Serverless; Gemini/TikTok/Telegram → user configs (`cfg1-3`, BG exit). WARP is mostly dead (UDP blocked; endpoint rotated 3×). The Worker cannot connect here.
  - The YouTube app fully loaded through MAE.
- **Any installed app** (`app:<package>`, `CustomApp`, `ServiceRegistry.domainGuesses`):
  - Selected instantly. The main domain is guessed from the package and confirmed in the background (all guesses in parallel, system DNS first, 8 s cap).
  - Related domains are then learned like a site's. Registry apps not installed are no longer offered.
- **Every app learns its website's related domains on its first check** (not only custom sites), so the user never has to add an app's site separately.
- **`RelatedHosts` v2:**
  - Resources only: `<a href>` links are ignored; URLs inside scripts count only when they name a file.
  - Trackers/ads are dropped, as are other known apps' brand country domains (google.com.pk …).
  - Shared CDNs keep the exact host (`github-cloud.s3.amazonaws.com`, not `amazonaws.com`).
  - A fresh read replaces the old list.
- **Repair ladder (`policy/RepairLadder`), up to 5 rungs per app per network:**
  - Rung 1: no question; the failed route is set aside.
  - From rung 2 the user is asked what is wrong: not opening / partial load / country refusal / slow / login / calls.
  - Each rung is a different plan (excluded routes, forced foreign probing, foreign-geo requirement, UDP-only, throughput-first, stable single-family exit, host relearn, deep check).
  - 👍 resets; the ladder expires after 24 h. The compiled config now follows the stored policy, so a repair's choice sticks.
- **Immediate feedback:** a "didn't open" shows "checking again…" and the testing state at once, even while another app is being checked.
- **Classifier:** "service down" now requires a working foreign exit that also failed (TikTok on Irancell had been mis-read as down while every exit was dead).
- **Fixes for Android 8:** OpenVPN relay crash; Google Script CA install through KeyChain below Android 11.
- **Tests:** 319 JVM (+224 Worker), 0 failures.

## Session 4 (2026-09-30): full review, self-healing, the TikTok case
Three reports from the user's phone drove this session:
1. **TikTok** (refuses Iranian addresses) never opened, even after five 👎.
2. **Instagram** videos stopped playing after ~20 min idle with MAE connected.
3. A manual disconnect/reconnect fixed Instagram.

The user also set a rule for such apps: WARP, WARP-in-WARP, WireGuard and MASQUE all exit with an
Iranian address. Only Cloudflare configs will do: take them from the user's own panels (MLM, BPB,
Netra, EDG), put them on IPv6 first and fall back to IPv4, and use the fastest. YouTube and
Instagram on local routes are right as they are.

**Root causes found**
- **Nothing watched the route in use.** The probe core measures with its own sessions. A route that died under the tunnel stayed dead until the user reconnected.
- **WARP:** three WireGuard outbounds with one key in each core (`mae-wg4/6/d`), plus copies in the probe core. These were same-key sessions that knocked each other off.
- **Changes never reached the live tunnel.** Outbound contents (rotated WARP endpoint, redeployed Worker, edited config) were not part of `structure`. Meanwhile the DoH path's timestamp was, and forced a reconnect every 6 h.
- **Breaker:**
  - `allow()` set a probing flag even for providers that were never probed, which locked them out until restart.
  - Health was judged by whether a *filtered app* answered, so direct went "unavailable" after three filtered apps.
- **Ladder:**
  - The web probe of tiktok.com passes from Iran, so GEO=NO. Rungs 1–2 only rotated local routes (all Iranian addresses), and rung 5 dropped `needsForeign`.
  - The next routine refresh undid the repair.
  - Live switching kept TikTok's open connections on the old route.
  - Learned hosts were never applied to registry apps.
- **TikTok also reads the SIM country and time zone.** No route fixes that, and nothing said so.
- **Session and control:**
  - Lost updates to `testing` left an app "checking" forever.
  - A scheduled reconnect could restart a tunnel the user had stopped.
  - Auto-switch replaced MAE with one node after 15 min.
  - The watchdog and the Quick Settings tile replayed another network's config.
  - `LastEngine` kept the whole compiled config (unsealed secrets) in backed-up prefs.

**Fixes (by area)**
- **Self-healing:**
  - `LiveCanary` runs through the tunnel's own loopback inbound: on unlock after 3+ min idle, every 5 min with the screen on, and 8 s after connecting.
  - WARP dead, or every route dead → reconnect (at most once in 3 min). Otherwise → live failover to the next proven route, plus a priority re-check.
- **WARP:** one outbound (`mae-wgd`, `ForceIP`) per core, and a second identity (`warpProbe`) for the probe core. Without it, WARP is not probed while connected.
- **Outbound hashes in `structure`**; `DnsPath.at` taken out of it.
- **Breaker:**
  - Keyed per `route@net`; `allow()` is pure.
  - `RouteHealth` judges transport (direct: TCP connects; bypass: anything answered; foreign: the exit is alive).
  - A route seen alive in the last 10 min is not failed by one bad round.
- **Offline guard:** no learning on a network that is not validated or is a captive portal. Deferred checks resume when it validates.
- **TikTok:**
  - Registry v7 marks it `requiresForeign` with `clientChecks: [sim, timezone]`, and adds its missing domains.
  - Foreign exits are always probed and required for it.
  - Rung 1 goes abroad for geo-restricted apps on a local route; rung 5 stays foreign.
- **User evidence** (`MaeState.userEvidence`, 7 days) keeps a repair through refreshes. 👍 on the route clears it.
- **A repair that changes the route reconnects**, so open connections move.
- **`DeviceSignals`** reads SIM/network country and time zone. The row explains when the app itself refuses Iran.
- **Cloudflare panel configs as foreign exits** (`MaeCloudConfigs`, `cfc-<hash>`): BPB, EDG, MLM, NTR, then GZG, NVA, NHN, through the arena's adapters. Each link goes on a clean IPv6 edge first, IPv4 as the fallback, gated by the network's family verdict. Discovery keeps the fastest.
- **Per-app `process` rules** (UID, Android 10+) route the whole app. There is also a QUIC balancer per service (blocked on bypass routes).
- **Scoring and policy:**
  - Stable config ids `cfg-<hash>`, with schema 2 migrating away `cfg1..3`.
  - EWMA success rate.
  - Fail-open never blocks. A fail-closed app on a new network takes its proven foreign exit from another network, or blocks until proven.
- **Measurement:**
  - DNS resolved once per discovery; RTT timed from the TCP connect.
  - 10-min exit echo cache.
  - Distinct stage-C finalists.
  - `SERVICE_DOWN` needs an unlimited exit.
  - The data SIM keys cellular networks.
- **Session:**
  - `testing.update {}`, a `wantConnected` guard, and a `CoroutineExceptionHandler`.
  - A session observer on MyVpnService's phase.
  - Auto-switch skips MAE.
  - The watchdog and the tile compile a fresh config; `LastEngine` stores only the marker `mae`.
- **UI:**
  - Engine init off the main thread.
  - Localized route names, and notes on each row instead of toasts.
  - Feedback dimmed while disconnected.
  - The symptom dialog lives in the engine (survives rotation; cancelling costs nothing).
  - Confirmations before removing.
  - A prebuilt, density-sized icon bitmap in the app picker; the diagnostics page throttled to 2 Hz.

**Verification here:**
- No Android SDK is available in this environment, so everything was checked in a scratch harness instead:
  - 72 MAE JVM tests pass (21 new in `MaeIntelligenceTest`).
  - The engine sources compile against `android-all` 14.
  - The MAE UI compiles against Compose Multiplatform 1.5.11 (the Jetpack Compose 1.5.4 / material3 1.1.2 APIs).
- To do: `./gradlew assembleDebug testDebugUnitTest`.

**Phone scenarios to run**
1. TikTok with the Iranian SIM active → the row explains SIM/time zone. With the SIM disabled (or another time zone) → it opens through a Cloudflare panel config.
2. Instagram → screen off for 20 min → unlock → videos play. logcat `MAE`: `canary (unlock)`, and `healing` if a route had died.
3. Wi-Fi ↔ mobile data; a captive portal; switching the data SIM on a dual-SIM phone.
4. 👎 then Cancel on the question → no rung is used.
5. Connect from the Quick Settings tile; auto-switch on together with MAE.

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
- **Foreign exit #2 is done:** the user's own saved configs (`UserConfigRoute`, now `cfg-<hash>`, at most 3). The three lowest-delay VLESS/Trojan links are taken, excluding the Iran/SNI/fronting groups. The window moves on when all three are dead on a network.
  - Phone (MCI): accepted for ChatGPT, Gemini, Claude and TikTok (exits BG/RO/AZ), so they **reach Cloudflare-hosted services**, which the Worker cannot. TikTok moved to `cfg3`.
  - One config's v6 claim was caught as false ("asked for V6_ONLY, exit used the other family").
- **Live add/remove of apps: decided against** (measured reconnect ≈ 1.2 s).
  - Xray's `AddRule` with `shouldAppend=true` appends after the base's catch-all rules, so the new rules never match.
  - `shouldAppend=false` replaces the whole rule set, which needs the base's geosite lists encoded as protobuf by hand. That is too risky for ChatGPT/Claude routing.
  - Route changes, pause/resume and feedback remain live.
- **Worker exit country varies per connection** (BG/RO/AZ). Proofs are per service, so this is fine, but sticky-country affinity is not implemented.
