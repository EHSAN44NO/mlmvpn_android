# کانفیگ استدیو — سند تحویل و ادامهٔ کار

**آخرین بروزرسانی: ۲۰۲۶-۰۹-۰۸ · پروژه: `G:\ip scanner` · فقط نرم‌افزار اندروید**

> این سند برای ادامهٔ کار در یک نشست تازهٔ Claude Code نوشته شده. سه بخش دارد:
> ۱) خلاصهٔ فارسی برای شما، ۲) وضعیت دقیق فنی برای دستیار بعدی، ۳) کارهای باقی‌مانده.

---

## ۰. مهم‌ترین نکته: فقط اندروید

**تمام کار روی نرم‌افزار اندروید است.** پوشهٔ کاری `G:\ip scanner\android` است. با نسخهٔ ویندوز
(الکترون، `dist/win-unpacked`، `gst.exe`، `aether.exe`) **هیچ کاری نداریم** — نه کد آن را تغییر
می‌دهیم، نه build می‌کنیم، نه تست. اگر دستیار بعدی به سراغ فایل‌های ویندوز رفت، اشتباه رفته است.

---

## ۱. خلاصهٔ فارسی — چه چیزی ساخته شده

«کانفیگ استدیو» یک محصول کامل داخل اپ اندروید است که روی حساب کلادفلر خودِ کاربر نصب می‌شود و
با آن می‌شود کاربر ساخت، لینک اشتراک داد، حجم و زمان را کنترل کرد و تمدید کرد.

| | |
|---|---|
| **نصب یا پذیرش** | اپ اول حساب کلادفلر را اسکن می‌کند. اگر قبلاً نصب شده باشد **همان را برمی‌دارد** (D8). اگر نتواند حساب را ببیند، دکمهٔ نصب نشان داده نمی‌شود. |
| **ساخت کاربر** | نام، حجم، مدت، دستگاه → کاربر، کانفیگ، و لینک اشتراک. |
| **لینک اشتراک** | `/s/{token}` با `Subscription-Userinfo`. **توکن رمز است، نه نام کاربری.** |
| **صفحهٔ کاربر** | `/p/{token}` فارسی راست‌چین، با **دلیل** قطع‌شدن و خط تماسی که خودتان می‌نویسید. |
| **مدیریت** | تمدید (لینک عوض نمی‌شود)، چرخش توکن، فعال/غیرفعال، حذف. |
| **«بسته»** | مجموعه شرایط با ۸ فیلد کامل، همگام روی همهٔ حساب‌ها، با نمایش رانش. |
| **ناوگان** | چند حساب کلادفلر بدون سقف: فهرست، جست‌وجو، افزودن گروهی، جای‌دهی خودکار، نوار ظرفیت. |
| **سقف دستگاه/اتصال/آی‌پی** | **حالا واقعاً اعمال می‌شود** — Durable Object به ازای هر کاربر، با کلید سخت/نرم برای هر نفر. |

---

## ۲. وضعیت فنی دقیق — برای دستیار بعدی

### 2.1 Read these first, in this order

1. **This file** — what actually landed.
2. `android/docs/CONFIG-STUDIO-PLAN.md` — the architecture. Locked decisions D1–D9, risks R1–R19,
   the correction ledger §F. **Its "state of play" section is superseded by this file.**
3. `android/worker-src/studio/manifest.json` — the worker's fragment order and why it exists.

### 2.2 Locked decisions that must not be relitigated

| | Decision | Source |
|---|---|---|
| **D6** | **No commerce vocabulary anywhere**, and **no `price` / `currency` / `paid_at` column in any phase.** The operator is «مدیر»; the person given a subscription is «کاربر»; a set of terms is «بسته». Enforced by `checkStringParity` over **both** string catalogues **and** `worker-src/studio/**` including the migrations. | user |
| **D7** | Multi-Cloudflare-account fleet, **no cap on the number of accounts**. | user |
| **D8** | The same email on another phone must **find and adopt** the existing installation, never create a second worker. Operators must also be able to **update the engine**. | user |
| **D9** | workers.dev is reachable from Iran — no custom-domain work needed. | user |
| **D2** | One account store (`CloudManager`). Config Studio never keeps a second list of accounts. | plan |

### 2.3 Three constants that move together — `checkEngineVersion` fails the build otherwise

```
PanelBuild.MLM        == STUDIO_API_VERSION (04a-studio-api.js)   → 6
PanelBuild.MLM_SCHEMA == highest v: in 05a-migrations.js          → 10
```

**The schema moves more often than the build**, and that is the normal case: a release adds a table
or an index without changing an endpoint. Settings offers an update when **either** is behind.

### 2.4 What is built

| Layer | Files | Notes |
|---|---|---|
| Worker fragments (`worker-src/studio/`) | 21 | concatenated into `app/src/main/assets/mlm_worker.js` |
| Kotlin (`data/studio/**` + `ui/configstudio/**`) | 27 | |
| Worker test harness (`scripts/test-studio-worker.mjs`) | 1 | **102 tests, 0 failures** |
| Strings (`studio_*`, both locales) | — | ~290 keys × 2 |

**Never edit `app/src/main/assets/mlm_worker.js`.** Edit a fragment, run
`node scripts/build-studio-worker.js`. `checkWorkerAsset` fails the build if the two disagree.

| Fragment | What it holds |
|---|---|
| `03-entry.js` | routing. Studio prefix, `/s/`, `/p/` sit **above** every transport matcher (R6). E1's per-config WS paths are here |
| `04a-studio-api.js` | `STUDIO_API_VERSION`, bearer auth, bootstrap, `/v1/health`, `/v1/dashboard`, `/v1/settings`, keys, the dispatcher |
| `04b-studio-users.js` | `/v1/users` — keyset paging, `?since=` sync with a **`(updated_at, uid)` keyset**, CRUD, `:renew` |
| `04c-studio-sub.js` | configs, `/s/{token}`, `Subscription-Userinfo`, token rotation |
| `04d-studio-page.js` | the Persian RTL `/p/{token}` page |
| `04e-studio-plans.js` | `/v1/plans`, the idempotent `PUT` fleet-sync path, `:apply` |
| `05-db.js` | `DbService`; `verifyApiAuth` **fails closed** |
| `07-traffic.js` | the abandoned-session sweep **and** the `usage_hourly` rollup |
| `10-caps.js` | `isUserCapped`, `studioExpiryAt`, the daily rollover, the quota refill, on-first-connect |
| `13a-session-do.js` | **`SessionDO`** + the admission client, device hashing, the devices API, E1's route lookup |
| `05a-migrations.js` | the versioned migrator, **v5 → v10** |
| `14-panel-html.js` | the legacy panel. Deleted at build 7. Exempt from the R2 literal guard |

**Kotlin layout**

```
data/studio/
  StudioNaming.kt      frozen prefix list; deterministic script name per account
  StudioDiscovery.kt   scan with POSITIVE identification (R15); removeWorker()
  StudioDeployer.kt    discovery-first install/adopt; R16 downgrade guard; the DO metadata + fallback
  StudioStore.kt       the singleton. Immutable state. Fleet fan-out, placement, disconnect
  index/StudioIndex.kt SQLiteOpenHelper (NOT Room — no kapt/ksp in this project). DB_VERSION 2
  domain/              StudioModels.kt (incl. Plan, ShardCapacity), Profiles.kt
  api/                 StudioApi, StudioHttpApi, StudioMappers, dto/StudioDtos
  config/              ConfigBuilder, VpnConfigBridge, StudioTemplates
ui/configstudio/
  ConfigStudioHost.kt          the ONLY navigation. One BackHandler
  StudioDashboardScreen.kt     tiles + the per-shard capacity strip
  StudioUsersScreen.kt         index-backed list, shard chips, shard filter
  StudioUserDetailScreen.kt    renew / rotate / enable / delete / devices / strict-soft toggle
  StudioNewUserScreen.kt       plan → policy → placement → create → config → link
  StudioPlansScreen.kt         «بسته‌ها» + the drift line
  StudioPlanEditScreen.kt      all eight plan fields, apply, archive
  StudioAccountsScreen.kt      «حساب‌ها» — the fleet
  StudioAccountDetailScreen.kt one shard: engine, page wording, extra workers, disconnect
  StudioBulkAddScreen.kt       «افزودن گروهی»
  StudioSettingsScreen.kt      a hub. NOTHING here addresses one account any more
  wizard/StudioSetupWizard.kt  5 steps; `excludeAccountIds` for adding a second account
  parts/StudioKit.kt           shared chrome + MeterBar
```

### 2.5 Bugs that were LIVE on real accounts and are now fixed

Each was invisible from every screen, and each has a test that fails without the fix.

1. **`?since=` sync silently dropped users.** It paged on `updated_at` alone, so rows tied with the
   last row of a full page were skipped **permanently**. `:apply` writes one timestamp across a
   whole «بسته» and the v6 backfill gave every legacy user the same one, so the ties are routine.
   → keyset on `(updated_at, uid)`, index `ix_users_updated_uid` (v10). An engine that does not send
   `next_uid` keeps its old behaviour exactly; the client detects a page that advances neither half.
2. **A renewed user was refused by the tunnel immediately.** `:renew` writes `expiry_days` as days
   remaining *from now*; the data plane read it *from `created_at`*. 29 days into a 30-day term plus
   15 more → `expiry_days = 16` → "expired two weeks ago". → `studioExpiryAt` prefers `expires_at`.
3. **Every usage figure was frozen at the migration snapshot.** `used_bytes` / `daily_used_bytes` —
   what the subscription header, the page, the list and the dashboard all read — were incremented by
   nothing. Enforcement was fine (it reads the GB mirrors). → both families in the same UPDATE.
4. **The dashboard's 24-hour traffic tile always read zero.** `usage_hourly` was created, queried and
   never written. → written, coalesced per ~500 MB.
5. **A deleted username was burned forever, invisibly.** The local index drops tombstones so the app
   said the name was free; the engine refused it. → a tombstone releases its name and stays a
   tombstone.
6. **`GET /v1/users/{id}` returned 200 for a tombstone** — the call made before every mutation.
7. **Every engine update minted a new API key and never revoked the old one.** Ten repairs, ten live
   admin keys. → the previous key for that device is revoked once the new one is proven.
8. **Creating from a «بسته» dropped five of its eight terms.** `Plan.toPolicy()` existed with zero
   call sites, and the plan editor had no field for those five at all — so `:apply` wrote nulls over
   per-user settings.
9. **Discovery reported a worker it could not inspect as "nothing here"**, which made the wizard
   offer to install beside it.

### 2.6 Verification

```bash
cd "G:/ip scanner/android" && node scripts/test-studio-worker.mjs
```

Gradle from a shell needs the environment set first, or it dies on an AF_UNIX loopback error (the
Windows username contains a space, so `TEMP` resolves to an 8.3 short path):

```powershell
$env:GRADLE_OPTS="-Djdk.net.unixdomain.tmpdir=C:\gradle-tmp"; $env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; $env:TEMP="C:\gradle-tmp"; $env:TMP="C:\gradle-tmp"; cmd /c ".\gradlew.bat :app:assembleDebug -DsocksProxyHost= -DsocksProxyPort= --console=plain > C:\gradle-tmp\b.log 2>&1"
```

**`C:\gradle-tmp` must exist before this runs.** Gradle will not create it, and without it the
daemon dies on the same loopback error the incantation exists to avoid — which reads as "the
workaround does not work" rather than as a missing directory. Verified working on 2026-09-08:
`:app:assembleDebug` succeeded from a plain shell.

Three traps that have each cost real time:

- **`gradle.properties` points at a dead SOCKS proxy on 10808.** Bypass with
  `-DsocksProxyHost= -DsocksProxyPort=`.
- **Never trust the exit code.** `$LASTEXITCODE` reads **0 on a `BUILD FAILED`** even through
  `cmd /c`. Always grep the log for `BUILD ` and `^e: `.
- **`gradlew clean assembleDebug` in one invocation fails** in `packageDebug` with "Found a .ap_ for
  split … but no associated manifest file". It is an AGP incremental-split quirk, not a code error:
  run `assembleDebug` on its own afterwards and it succeeds.

Healthy output:

```
engine schema: 10, app and worker agree
engine version: 6, app and worker agree
string catalogues: 3431 keys, in step; worker vocabulary clean
studio layering: clean
worker asset: in step with its sources (392074 bytes, 21 fragments)
102 passed, 0 failed
```

### 2.7 Invariants a new session will otherwise break

- **The three constants in §2.3.** Bump them in the same commit as the change they describe.
- **`StudioDeployer` must keep shipping `ADMIN_PASSWORD` until build 7** (R3 ordering). Removing it
  early opens the legacy panel on every existing installation.
- **`StudioDeployer.DO_TAG` moves only when the set of DO classes changes** — never with a build
  number. Cloudflare tracks what it created against that string per script.
- **The worker harness exposes internals** by appending an `export const __internals = {…}` to the
  patched source at load time. Adding a function that tests need means adding its name there.
- **`checkStringParity` now also rejects a key defined twice in one catalogue.** It used to build a
  `Set` first, so a duplicate only surfaced minutes later in `mergeDebugResources`.

### 2.8 Standing constraints from the user

- **Do NOT bulk-delete the four orphan workers** on the live account
  (`auto-scale-cfd0a6-mlm`, `cdn-edge-f3ae22-mlm`, `db-proxy-63779a-mlm`, `task-run-744bc1-mlm`).
  They share one D1 so they still work, and subscription links handed out from the device that
  created each one point at **that** address. Removal is per-worker, on the account's own screen.
- **No staged rollout is needed.** Each of the ~6,000 users has their own Cloudflare account.
- Reading `shared_prefs/cloud_accounts_prefs.xml` via `adb run-as` to extract the Cloudflare API
  token was blocked by the permission classifier. **Do not work around it.** Cloudflare's own
  deployment history is the rollback path.
- **The repo's `.git` directory is empty** — no history, no branch, no remote. Nothing can be
  committed locally. `gh` still reaches `mlmvpn/mlmvpn_android`.

---

## ۳. کارهای باقی‌مانده — Remaining work

### 3.1 Only the operator can do these

| | What | Why |
|---|---|---|
| **A** | **Update the engine on every live account.** Schema is 10; every installation is on 9 or below and the Plans, settings, devices and enforcement screens need it. In the app: **کانفیگ استدیو › تنظیمات › حساب‌ها › «بروزرسانی موجود است»**, or «بروزرسانی همه». | blocking |
| **B** | Delete the `studio-test-1` test user (40 GB, 44 days). | offered, never done |
| **C** | **Drive the new screens on a real phone.** Plans, Accounts, Bulk add, Devices and the enforcement toggle have compile-time and worker-level proof only. | untested UI |
| **D** | **Watch the first deploy that carries the Durable Object.** R1's metadata has never been run against a real account. The deployer falls back to a working soft install if it fails, and `adb logcat` will carry `deploy with SESSIONS failed, retrying without:` with Cloudflare's own message — **that line is the thing to look for.** | R1 |

### 3.2 Phase 4 — templates, nodes, analytics, the assistant · not started

- Templates CRUD + `ConfigBuilderWizard`
- `Node` / `NodeGroup` with capabilities, priority, health check
- Analytics: traffic over time, breakdown by node/protocol/transport, top users
  (`usage_hourly` is now populated, so the 24-hour figure is real; `usage_daily` still is not)
- Activity + Audit with keyset paging (`audit_log` is written on every mutation; nothing reads it)
- **`StudioCoach`** and the «ترکیب با اسکن» flow (§14.4, §14.5)
- «انتقال کاربر» between shards (§A.6) — the only cross-shard operation in the product

### 3.3 Phase 5 — scale and operations · not started

Bulk ops; tags / groups / notes / advanced filter and sort; versioned backup and restore with sealed
secrets; **API-key management UI** including the per-device list (§B.2.3 — the endpoints exist and
nothing calls them except the deployer's own revoke); upgrade / reinstall / uninstall; two-pane
detail panes; subscription output adapters (Clash, sing-box); **M6 retirement of the legacy
surface** — build 7, which is what unblocks removing `ADMIN_PASSWORD` and deleting `14-panel-html.js`.

### 3.4 Phase 6 — protocols and failover · not started

- **Trojan** (~300 lines, portable from `assets/nahan_worker.js`) and **httpupgrade** (~40 lines on
  the WS handler). `ConfigBuilder` already emits both; the worker's data plane speaks VLESS only.
- Multiple configs per user, credential rotation, the Configs screen. The route-key half is done
  (E1), so a second config can already have its own path — nothing creates one.
- gRPC inbound ported from `edg_worker.js`; node failover and health monitoring; REALITY if
  warranted; per-node fan-out strategies; TV D-pad focus.

### 3.5 Known gaps worth a decision, not just work

- **The Durable Object is untested against a real account.** Everything around it is defensive — the
  deploy falls back, the admission client falls back, the devices endpoint reports `available:
  false` — but the object's own SQL has never executed. §3.1-D.
- **`SessionDO.close()` accumulates bytes in a `pending` table that nothing drains.** The per-user
  counters are written by the data plane exactly as before, so no accounting is lost; the table is
  there for a future per-session rollup and is currently write-only.
- **`usage_daily` is still never written.** Nothing reads it either, so there is no visible gap —
  but per-user analytics will need it.
- **The capacity meter is a user count against a documented band, not a reading.** Cloudflare's
  request counter is not readable from inside a Worker and counting requests in D1 would spend the
  budget it measures. The UI says so; do not quietly upgrade it to a claim.
- **`EmergencyInterceptor`** rewrites every `*.workers.dev` and `api.cloudflare.com` request through
  `mlm-proxy.vercel.app` while emergency mode is on — so the bearer token and every user list
  transit a third party. Off by default, manual only. Recorded in plan §11 (F-g); no work planned,
  but do not remove the note.
- **Username uniqueness across shards is a client-side guarantee** and an honest limitation (§A.5).
  Two devices creating the same name on two shards in the same instant both succeed.

### 3.6 Release state

**`v1.2.33` is still a Draft** on `mlmvpn/mlmvpn_android`, so `versionName`/`versionCode` stay at
`1.2.33` / `65` and this work goes **into that draft**. `ChangelogData.kt` (both lists), the English
half of `CHANGELOG.md`, and the draft release notes are all current as of this document.
Run `gh release list` before bumping anything.
