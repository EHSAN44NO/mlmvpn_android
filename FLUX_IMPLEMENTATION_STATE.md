# FLUX — implementation state

## Current milestone

- **Done:** F0–F9 implemented, plus the fragment part of F10.
- **Not yet done:** the mux benchmark, and measurement on real devices.

## Files

| Area | Files |
|---|---|
| Core (pure Kotlin) | `engines/flux/core/{model,parse,net,outbound,race,score,memory,country,compile,source,plan}/` |
| Android | `engines/flux/FluxEngine.kt`, `FluxAndroidProbe.kt`, `FluxNet.kt`, `FluxSourceRepo.kt`, `FluxStore.kt`, `FluxRoute.kt` |
| UI | `ui/flux/FluxScreen.kt`, `res/drawable-nodpi/ic_app_flux.png`, `flux_*` strings (en, fa) |
| Wiring | `MyVpnService.kt` (IPv4 mode blocks v6, no Google fix, reconnect uses `freshConfig`), `LastEngine.kt` + `VpnTileService.kt` (tile), `MaeEngine.kt` (`FluxRoute` provider), `MaeScreen.kt` (route name), `HomeDestinations.kt`, `AppScreen.kt`, `ActiveEngines.kt`, `XrayJsonGenerator.kt` (QUIC refusal helpers), backup rules |
| Tests | `app/src/test/java/com/mlmvpn/scanner/engines/flux/` — 57 JVM tests |

## Decisions

- **Data plane:** the existing Xray core. No new core and no Rust, because nothing has been measured that calls for one.
- **Separation:** FLUX's sources, store and screen are its own. It shares nothing with Free Configs or Quick Connect.
- **Cloudflare:** treated as optional.
  - The verdict is per network, and gates Cloudflare-fronted candidates.
  - Direct sources (REALITY, Hysteria2, Trojan) carry networks where Cloudflare is cut.
- **Failover:** Xray's balancer plus the burst observatory, with no Kotlin in the packet path.
- **"Connected":** shown only after a request through the tunnel succeeds.
- **Security:**
  - Self-signed nodes: VLESS/Trojan are refused. Hysteria2 nodes (nearly all public ones are
    self-signed) are kept but raced only in wave 2.
  - Plaintext VLESS is refused.
  - Stage 1 never trusts a certificate: a certificate error only proves the server answered.
- **MAE:** reads FLUX's proven routes from the store only. FLUX never probes for MAE.

## Verification so far

- **Core:** 57 JVM unit tests pass. They cover:
  - racing: fast fail / slow success / fast success / better within grace / later than grace;
  - country: wrong label rejected, disagreeing sources;
  - IPv4/IPv6: per family, and v6 scoring higher when faster;
  - memory per network; failover to a standby; breaker and quarantine;
  - fragment helps / hurts; Cloudflare cut; UDP blocked;
  - explicit port mapping; no secrets; no DNS leak; IPv4 config has no v6.
- **Parser on today's real lists** (2026-10-01):

  | Source | Usable nodes | Breakdown |
  |---|---|---|
  | Free-Configs | 5 | Cloudflare |
  | MatinGhanbari vless | 169 | 100 REALITY, 69 TLS |
  | MatinGhanbari hy2 | 135 | 17 verified, 118 self-signed |
  | MatinGhanbari trojan | 179 | |

- **Android glue** type-checks against the real Android API (android-all 34), using signature
  stubs for the app classes it calls.
- **Not compiled here:** `FluxScreen.kt` (Compose), and the full Gradle build. The container cannot
  reach Google's Maven, so the APK build is the owner's.

## Bugs and risks to watch on device

- **Balancer:** the `leastLoad` settings (costs, baselines) are not measured yet. If the standby is
  picked while the primary is alive, raise the standby costs or switch to `fallbackTag` only.
- **Hysteria2:** the outbound uses the `hysteria` protocol, `version: 2`, and finalmask `salamander`.
  These are confirmed present in this core's binary, but not exercised on a device yet.
- **Exit country:** the trace check on 1.1.1.1 cannot pass through a Cloudflare Worker exit.
  ip-api is then the only source, so the country is held at confidence 0.6.

## Next actions

1. Build the APK and test on MCI, Irancell and Wi-Fi:
   - a network where Cloudflare works, and one where it is cut;
   - IPv4, IPv6 and Both;
   - a country choice.
2. Measure and tune:
   - cold and warm connect times;
   - failover time;
   - the scorer weights and the `leastLoad` settings.
3. Mux benchmark on a device (mux on vs off for the primary), feeding `FluxLearner.decideMux`.
4. AnyTLS provider: needs a second core (`.aar`). Plug it in as a new `Proto` plus an outbound
   builder, and a separate local process with an explicit port map.
5. Optional: a signed remote source catalog, so lists can change without a release.
