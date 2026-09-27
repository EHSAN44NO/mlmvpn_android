# کانفیگ استدیو — Config Studio · Architecture Plan

> **This file is the handoff document.** Written to be read cold by a session that has never seen the
> conversation that produced it. Repo `G:\ip scanner`, Android app under `android/`.
> **Nothing is implemented.** This is the proposal, for review.
>
> **Revision 3 — 2026-09-07.** Q6, Q7 and Q8 are answered (§18). Q8's answer — **no cap on the number
> of Cloudflare accounts** — withdrew revision 2's read design: a k-way merge across shards is O(N)
> requests per page, which cannot work when N is unbounded. §A.3 is replaced by a **local index**, which
> reverses draft 1's "no local mirror" rule and adopts Room, the app's first database. §A.9 is new.
>
> **Revision 2 — 2026-09-07.** The first draft was reviewed against the codebase and four more
> decisions were locked (D6–D9). Three of them are structural and were *not* in draft 1:
> **a fleet of Cloudflare accounts** (§A), **recovering an installation on a new phone instead of
> deploying a second one** (§B), and **no commerce vocabulary anywhere** (D6). §F lists every
> correction made to draft 1's factual claims. Scope is **Android only** — the Windows app and the
> PHP web panel are out of scope by the user's instruction.
>
> Status: exploration complete, architecture drafted, **nine** decisions locked, open questions at §18.

---

> ## ⚠ This section is superseded
>
> **`android/docs/CONFIG-STUDIO-HANDOFF.md` is the current state of play. Read it first.**
>
> Everything below this box is still correct as *architecture* — the locked decisions, the schema,
> the risks and the correction ledger are what they were. What is out of date is the *status*: the
> tables in this section describe the tree as of 2026-09-07, before Phase 1 was finished, before
> the feature was deployed to a live account, and before «بسته» existed. Specifically, the line
> below saying `PanelBuild.MLM` is "deliberately still 5" is **no longer true and must not be acted
> on** — it is 6, it matches `STUDIO_API_VERSION`, and a Gradle task now fails the build if they
> drift. The schema is at **v9**, not v8.

## State of play — as of 2026-09-07 (superseded — see the box above)

**Phase 0 is done and verified. Phase 1 has started.** Last full run: `BUILD SUCCESSFUL`, both
verification tasks green, **16 golden tests, 0 failures**.

### Phase 0 — complete

| | What landed | Verified by |
|---|---|---|
| **F1** | `CloudAddAccountForm` extracted from `CloudAddAccountScreen` (`ui/CloudScreens.kt`) | compiles |
| **F2** | `worker-src/studio/` (15 fragments + `manifest.json`), `scripts/build-studio-worker.js` | the split reproduced the old asset **byte for byte**; Gradle `checkWorkerAsset` on every build; a **parse check** of the assembled file; the R2 literal guard — both guards were deliberately made to fail once, to prove they can |
| **F4** | `data/studio/domain/Profiles.kt`, `data/studio/config/ConfigBuilder.kt`, `VpnConfigBridge.kt`, and **`XrayJsonGenerator` now delegates to it** — all three of its `streamSettings` builders replaced by one `buildStream()` | **29 tests, 0 failures** (`ConfigBuilderGoldenTest` 16, `VpnConfigBridgeTest` 13) |
| **F7** | Gradle `checkStringParity` | runs on every build: *"3153 keys, in step"* |
| **F8** | `data/studio/StudioNaming.kt`, `data/studio/StudioDiscovery.kt` | compiles |

**F3 and F5 moved to build 6/7** (§3, §16). **F6** has nothing to build until Phase 1's store.

> **The golden fixtures earned themselves on the first run.** They caught a real inconsistency
> *inside* `ConfigBuilder`: `buildStreamSettings` forced XHTTP's `packet-up`, while `transportQuery`
> used `?: "packet-up"`, which does not fire on a non-null `"auto"`. The emitted **link** said
> `mode=auto` while the emitted **JSON** said `packet-up` — one object, one file, two answers. That
> is precisely the failure the class exists to remove, reproduced inside it. Both now call one
> `xhttpMode()`.

### Phase 1 — started

| | What landed | State |
|---|---|---|
| **F5 / build 6 schema** | `worker-src/studio/05a-migrations.js`: versioned migrator, legacy adoption, `users` columns + backfill, 11 new tables, 14 indexes | **executed** against real SQLite |
| **build 6 control plane** | `worker-src/studio/04a-studio-api.js`: `/{STUDIO_ROUTE}/v1/*`, bearer auth, bootstrap + re-bootstrap, health, key list/revoke, audit | **executed** |
| **`/v1/users`** | `worker-src/studio/04b-studio-users.js`: keyset list, prefix search, `?since=` sync with tombstones, create/read/patch/soft-delete, `:renew`, `:enable`/`:disable`, counters, audit on every write | **executed** — 30 worker tests total |
| **migration v8** | `ix_users_created_uid` for keyset paging, and the counters seed | **executed** |
| **`/s/{token}` + configs** | `worker-src/studio/04c-studio-sub.js`: the subscription, `Subscription-Userinfo`, `Profile-Title`, config storage as `uri_template`, token rotation | **executed** |
| **`/p/{token}`** | `worker-src/studio/04d-studio-page.js`: the Persian RTL page — volume, time, devices, status-with-reason, LTR-pinned link | **executed** — and rendered and looked at |
| **`/v1/dashboard`** | one call, one `db.batch`, all tiles, no `COUNT(*)` over `users` | **executed** — 61 worker tests total |

| **R3 / M2 — legacy auth hardening** | `05-db.js`: seed the DB from the `ADMIN_PASSWORD` binding on first use, and `verifyApiAuth` now **fails closed** | **executed** — 67 worker tests total |
| **`CloudAccount` studio fields** | eight fields, in all **three** places (model, `loadAccounts`, `saveAccounts`) | compiles |
| **`StudioDeployer.kt`** | discovery-first install/adopt/upgrade, the R16 downgrade guard, `secret_text` bootstrap, no `DEBUG=1` | compiles |
| **domain + API layer** | `domain/StudioModels.kt`, `api/dto/StudioDtos.kt`, `api/StudioApi.kt`, `api/StudioMappers.kt`, `api/StudioHttpApi.kt` | compiles |
| **`checkStudioLayering`** | the DTO/UI boundary, and Compose kept out of the data layer, enforced by the build | runs on every build |
| **local index + store** | `index/StudioIndex.kt` (SQLite, **not Room** — see below), `StudioStore.kt` with `withFreshUser` | compiles |
| **UI foundation** | `ui/configstudio/parts/StudioKit.kt` (wizard chrome extracted from `GstSetupWizard`), `wizard/StudioSetupWizard.kt` | compiles |
| **strings** | 55 keys in **both** locales, past the D6 vocabulary guard | 3208 keys, in step |

**The worker side of Phase 1 is now complete**, except E2/E3, which cannot be executed without a
socket.
| **worker harness** | `scripts/test-studio-worker.mjs` + Gradle `testWorker` | **new.** §19 said no harness existed |
| **E2 + E3** | the daily quota, fixed in both halves on the WS path | written, **still not runtime-tested** — it needs a socket |

`PanelBuild.MLM` is deliberately **still 5**: build 6 is half-written, and bumping it would tell every
existing installation to redeploy into it.

**Three things worth knowing about the migration, all found by reading the live schema:**

- **`users.id` already exists** as `INTEGER PRIMARY KEY AUTOINCREMENT`. §7.2's `id TEXT` column would
  fail with *"duplicate column name: id"* on every database ever deployed. The column is **`uid`**,
  and it is what the API exposes as the user's `id`.
- **Build 6 is split across two migrations** (`v6` columns, `v7` tables+indexes) because D1 allows
  **50 queries per invocation** and the two together exceed it. Each is atomic on its own.
- **A live build-5 database is *adopted*, not replayed.** If `schema_version` is empty but a `users`
  table exists, it is recorded at v5 — replaying the legacy `ALTER`s would fail on "duplicate column
  name" and take the whole batch down with it.

### F4 is complete, and it found two latent bugs on the way

`XrayJsonGenerator` built `streamSettings` in **three** places — `generateConfig`,
`generateAntiSanctionConfig` and `generateMultiConfig` — and they had already drifted apart:

- **Only `generateConfig` handled xhttp.** The other two emitted `network: "xhttp"` with **no
  `xhttpSettings` object at all**, so the mode fell back to a default that cannot work through
  Cloudflare (it buffers request bodies, so anything but `packet-up` hangs). `generateMultiConfig` is
  a measurement path — so an xhttp config was being *measured* through an outbound that could not
  have carried traffic.
- **`generateMultiConfig` sent `User-Agent: Mozilla/5.0`** where the other two sent a full Chrome
  string. A two-token user agent is a fingerprint of its own, and it meant a config was measured with
  a different profile than it would connect with.

Both disappear by there being one builder. `applySecurity` was deleted (its REALITY knowledge moved
into `ConfigBuilder`), and what stays in the generator is only what is about *this device dialling*:
the unfiltering cipher swap and the `sockopt` happy-eyeballs pinning.

`VpnConfigBridgeTest` is a **characterization** suite: every assertion was read off the generator as
it stood before the change, so the delegation is shown to preserve behaviour rather than asserted to.
Two assertions are marked as encoding the fix rather than the old behaviour.

### The worker now has a test harness — §19 is out of date

`scripts/test-studio-worker.mjs` runs **the shipped worker** against a real database. Node has SQLite
built in (`node:sqlite`), so the D1 binding is shimmed over actual SQL rather than mocked: the
migrations execute as real DDL, the indexes are really created, `batch()` is a real transaction that
really rolls back, and the module under test is the assembled asset with **only** its
`cloudflare:sockets` import stubbed. `db.batch` honouring atomicity matters — the migrator depends on
it, and a migration that half-applies leaves a schema nothing later can reason about.

It covers what can be covered without a socket: migration from a **live build-5 database** (adopted
at v5, not replayed — replaying its `ALTER`s would abort the batch on "duplicate column name"),
backfill correctness, idempotency across requests, route precedence in both directions, bearer auth,
bootstrap, re-bootstrap, revocation, forgery, and the audit trail.

> **It found a security bug on its first green run.** Burning the bootstrap secret recorded only the
> **last** consumed hash, so an older deploy secret became valid again the moment its binding came
> back — which is exactly what a rollback or a restored deploy does, turning a captured secret from
> spent into live. Burning is now a set: one `settings` row per consumed hash, checked by primary
> key. That bug would not have been found by reading; it needed the sequence to be executed.

**Out of scope, and honestly so:** anything that opens a socket. The tunnel data plane needs
`connect()` and a real peer, so **E2/E3 remain unexecuted** and the `wrangler dev` pass in §19 is
still the right end-to-end check.

### Still not done, deliberately

### Four checks now run on every build

`checkStringParity` · `checkWorkerAsset` (byte-identity + parse + the R2 literal guard) ·
`checkStudioLayering` · `testWorker` (67) · `testDebugUnitTest` (29).

### Decisions taken while building `/v1/users`

- **A delete is a tombstone, not a `DELETE`.** The row stays with `deleted_at` set, because that is
  the only thing that tells another device the user is gone (§A.3). A hard delete vanishes here and
  **reappears from the next device's cached copy on its next sync** — a deleted user quietly coming
  back, which for something that grants access is the worst direction for a bug to fail in. The
  credential is cleared in the same statement so the tunnel stops admitting them at once, rather than
  at the next sweep.
- **Search is a prefix match, and the test asserts an infix does *not* hit.** `LIKE '%x%'` cannot use
  an index, so it would read the whole table on every keystroke — the exact query shape behind the
  2026-09-06 read outage.
- **Every write also updates the build-5 GB mirrors** (`limit_gb`, `expiry_days`, `used_gb`). Not
  redundancy for its own sake: build 6 must be rollback-able, and build 5's data plane reads *only*
  those columns. A build-6 user served by a rolled-back build 5 would otherwise have no quota and no
  expiry — unlimited access, silently. Build 7 drops them (M6).
- **`updated_at` is written on every mutation.** A write that forgets it lands correctly and then
  never propagates to any other device, which is a bug with no symptom at the point it happens.
- **Migration v8 was added rather than v7 edited**, even though v7 has not shipped anywhere. A
  migration once written down is a fact about databases in the world, and `MlmDeployer` uploads
  whatever asset is in the APK — so "nothing has it yet" is a belief about other people's accounts,
  not something the worker can know.

### §9 — what the person on the other end sees

The brief asked that the link show **exactly how much volume is left, how long it lasts, and how many
devices may use it**. Most of that arrives without a browser, because every mainstream client reads
`Subscription-Userinfo` off the subscription response and renders it in its own UI.

Three conventions the tests pin down, because each is silently wrong in a way that still *looks*
right:

- **`expire` is UNIX seconds**, not the milliseconds everything else in this schema uses. Handing a
  client milliseconds puts the expiry roughly fifty thousand years out — it renders, it looks
  plausible at a glance, and it is wrong.
- **`total=0` means unlimited and `expire=0` means never.** Omitting them makes clients render
  nothing at all, which reads to the person as "broken", not as "unlimited".
- **`download` is the byte counter**, never the GB float. Someone given exactly 30 GB who is shown
  29.7 has a question, and float rounding is not an answer to it.

There is no standard header for a device limit, so it rides in `Profile-Title` — the only place it
reaches someone who never opens a page.

**The token is the secret, not the username.** The legacy `/sub/{username}` made the name the bearer:
guessable, un-rotatable, un-revocable, so a link that got out stayed out for as long as the account
did. Rotation takes effect immediately, with no grace period — a link the operator believes they
revoked must not still work.

**The worker still builds no URIs.** A config row carries a `uri_template` rendered by
`ConfigBuilder` on the Android side — the same builder the app dials with — and the worker
substitutes five placeholders. `POST /v1/configs` refuses a config without one, and says why.

### The wizard's shape: step 2 exists so step 3 can be honest

The old deployer went straight to deploying, which is how an account ends up with two engines — the
second serving nothing anyone holds a link to. The wizard examines the account first, and **what step
3 offers depends on what was found**:

| found | step 3 |
|---|---|
| an installation | «اتصال به همین نصب» — and says the links already handed out keep working |
| nothing | «نصب موتور» |
| a newer engine | refuses, and says to update the app first (R16) |
| **the scan failed** | **no install button at all** |

That last row is the one that matters. Not being able to see the account is precisely the state in
which deploying does damage, so the only offer is «تلاش دوباره», and the card says why in words
rather than showing an error code.

Two other things the wizard states rather than implies: capabilities are listed honestly on the
final step (**device and connection limits are recorded but not enforced** until the Durable Object
ships), and the update path says «موتور داخل خود برنامه است» — a fact the operator would otherwise
have to discover.

**Waiting states are steps**, following `FreeConfigWizard`, so a long deploy is a step the wizard is
*on* rather than a spinner over a step it is not. And there is **one** `BackHandler` at the host,
which also refuses to leave mid-deploy.

### The index earns more than speed

Two things fall out of having a local copy that were not the reason for building it:

- **Search gets better, not just faster.** The engine deliberately refuses infix search — `LIKE '%x%'`
  cannot use an index, and on D1 that reads the whole table on every keystroke, which is the query
  shape behind the 2026-09-06 outage. Locally it is a scan of a few thousand short strings, so the
  index can offer a search the API is right to withhold.
- **"Is this username taken?" becomes O(1) in fleet size.** Asking every installation would be one
  request per account per keystroke; the index answers instantly. It stays *advisory* — the target
  installation's `UNIQUE(username)` is the authority and still returns `username_taken` — but it
  turns the common case into a local lookup.

And one rule about failure: **a shard that does not answer never loses its rows.** Deleting them
because a network call failed is how a user vanishes from the operator's list for a reason that has
nothing to do with them. The list keeps the people in it, shows the age, and names the accounts that
did not answer.

### The API layer, and the boundary that is now checked rather than remembered

`data/studio/api/dto/` holds the wire format — snake_case, nullable everything — and the repository
maps it to domain types at the edge. The mapper costs something; what it buys is that a screen never
holds a type whose field names are an API contract.

That rule survives exactly as long as it is checked. One `import …api.dto.UserDto` in a composable,
added because it was right there and the mapping was one field, and the boundary still exists on
paper while buying nothing. §12.1 says "enforce by grepping"; `checkStudioLayering` **is** that grep,
run by the build. It also checks the reverse — the data layer must not import Compose, because a
repository returning a `Color` has quietly become a screen. Both directions start clean, which is the
right time to add a rule.

Three decisions inside the layer worth keeping:

- **Every mapper is total.** Any DTO, including one whose fields are all null, produces a valid
  domain object. The app and the engine version independently — a phone can be talking to an
  installation a build behind *or ahead* — so an unknown field must read as a default rather than
  crash halfway down a user list. The single exception is an object with no `id`: it cannot be
  patched, renewed or deleted, so it is dropped at the boundary instead of carried as something that
  will fail every later call for a reason no screen can explain.
- **Zero on the wire becomes null in the domain.** `expire=0` means "never", and carrying the zero
  through would make every date formatter render 1970.
- **The engine's error code wins over the HTTP status.** Falling back to the status alone collapses
  `username_taken` and `bootstrap_used` into "409". And `network` is kept distinct from any engine
  error, because "could not reach it" and "it refused" need different sentences on screen.

### R3 is closed, and it was closed *before* the deployer changed

This is the one step in the whole migration that can leak, so it was done first and on its own.

`verifyApiAuth` returned **`true` when no password hash was stored**. That has never leaked in
practice only because every worker this app has ever deployed ships `ADMIN_PASSWORD` as a
`plain_text` binding, and `getAdminHash` prefers the binding over the database — which is also why
`/api/change-password` has been a silent no-op on those installs, so **most of them have no stored
password at all**.

Remove the binding without preparing for it and `getAdminHash` falls through to a database that was
never written, `verifyApiAuth` finds no hash, and the legacy panel opens to anyone who knows the URL
— on every installation at once. Hence the ordering:

1. **Build 6 seeds** the database from the binding on first use, once per isolate, never
   overwriting a password the operator did set.
2. **Build 6 fails closed.** The old open branch protected nothing: `/api/setup-password` guards
   itself on the same condition and `handlePanel` shows the setup screen before ever calling
   `verifyApiAuth`. So it exposed every *other* endpoint for no benefit.
3. **Build 7 only** stops shipping the binding — and `StudioDeployer` therefore still sends it.

Six tests cover it, including the one that matters: seed under build 6, then serve the same database
from a worker with **no binding at all**, and assert the panel is still shut and the seeded hash
still authenticates.

### `StudioDeployer` — three differences from the deployer it replaces

1. **It looks before it deploys.** A discovery scan runs first, and a scan that *fails* aborts the
   deploy rather than proceeding — not being able to see the account is precisely the state in which
   a second engine gets added to one that already has one.
2. **It refuses to go backwards** (R16). Two devices on different app versions sharing one
   installation is the normal case once adoption works, and build 7 onwards is a one-way migration.
   A downgrade returns `WouldDowngrade` and is never resolved silently.
3. **`secret_text`, not `plain_text`,** for the bootstrap hash — and a **fresh secret on every
   deploy**, which is what re-arms bootstrap for a device that cannot read the old one back.
   `DEBUG=1` is simply not sent any more.

### §9.2 — the page, and what it refuses to say

Two rules govern it, and both are about not making the reader ask a question the page already had
the answer to:

- **State things in words, with the reason.** "غیرفعال" alone sends someone to ask why. The page
  distinguishes disabled from expired from out-of-volume, and says which, in a sentence. The reset
  policy is written out («هر ماه صفر می‌شود») rather than implied by a bar that silently refills.
- **Never draw a number this installation cannot measure.** Counting devices needs the Durable
  Object, which is Phase 3 — so the page shows the *allowance* («تا ۳ دستگاه») and does not invent a
  count. A "۰ از ۳" read from a table nothing writes to is a number that is always wrong, and a test
  asserts it never appears.

Volume is shown in the unit that fits: megabytes below a gigabyte, because "۰٫۰۳ گیگابایت" is true
and reads as nothing at all. **The link is LTR-pinned** — an RTL container reorders an ASCII URL both
on screen *and in what the person copies*, so a link that looks right in a screenshot pastes broken;
in something whose only job is to hand over a working link, that is not cosmetic (R9). And a test
greps the rendered page for the D6 and D1 vocabulary, so the ban is enforced on output, not just on
`strings.xml`.

### A harness bug worth recording, and which way it failed

`/v1/dashboard` fetches all five tiles in **one** `db.batch` — nine round trips from a phone on an
Iranian mobile network is not a dashboard, and under an uncapped fleet (§A) the app makes this call
once *per installation*. The first run returned zeros for everything, and the cause was the harness:
the D1 shim's `batch()` returned bare `{success:true}` and dropped the rows, where real D1 returns
`results` per statement.

Worth noting which direction that failed in. The tests went **red on correct worker code**, rather
than green on broken code. A shim less capable than the real thing costs an hour; a shim more
forgiving than the real thing quietly certifies bugs into production.

### A routing bug the tests caught immediately

`studioUsersRoute` claimed the whole `/users/` prefix, so `/users/{id}/subscription` was read as a
user id called `"{id}/subscription"`, looked up, and answered **404 before the subscription route was
ever reached** — ten tests failed at once on one cause. A dispatcher that owns a prefix has to
decline the parts of it that are not its own, not merely handle the paths it recognises. It now
returns `null` for sub-resources and for actions it does not own.

### Environment traps, all now guarded or recorded

- **Kotlin nests block comments** (Java does not): a KDoc containing a route written with a literal
  star opens a nested comment, and the error points past the end of the file.
- **`gradlew.bat` piped in PowerShell 5.1 reports success on a failed build.** Use
  `cmd /c ".\gradlew.bat <task> --console=plain > C:\gradle-tmp\b.log 2>&1"` and read the log.
- **`gradle.properties`' SOCKS proxy (`127.0.0.1:10808`) is dead**, and it blocks every dependency
  download. Maven Central is reachable **directly** and is ~23× faster than either live proxy port.
  Build with `-DsocksProxyHost= -DsocksProxyPort=` until those two lines are fixed.

---

## Context

MLMVPN can already deploy a Cloudflare Worker panel that mints VLESS configs and tracks a handful of
users. It is reached as a row inside «ابری», it is named after its implementation, and it is built for
someone managing a few configs for themselves.

The goal is different: a **product for someone who sells configs**. They need a user list they can
search and act on in bulk, quotas and expiry they can trust, device limits they can enforce, a
subscription their user opens and immediately understands, statistics, an audit trail, and a
builder that is not locked to one protocol on one transport.

### Locked decisions

| # | Decision | Source |
|---|---|---|
| D1 | **The UI must never say "MLM"**, "پنل", or name the legacy implementation. To the user, Config Studio is a separate system that happens to live in the same app. | user |
| D2 | **One Cloudflare account store.** An account added in Config Studio and one added in «ابری» are the same record, same persistence, same auth model. No second store, no sync. | user |
| D3 | **Extend the existing worker in place** — one engine, one D1 database. Not a second worker beside it. | user, explicit choice |
| D4 | **Durable Objects** are the mechanism for device / concurrent-connection / IP limits. | user, explicit choice |
| D5 | Phasing is proposed here (§16) rather than dictated. | user delegated |
| **D6** | **No commerce vocabulary anywhere.** Not «فروش», «فروشنده», «مشتری», «خرید», «قیمت», «تومان»; not *sell, seller, customer, buyer, price, purchase*. The operator is «مدیر» or addressed in the second person; the person given a subscription is «کاربر»; a set of terms is «بسته». **`Plan` therefore carries no price field** (§D). | user |
| **D7** | **A fleet of Cloudflare accounts, not one — and with no cap on how many.** One account fills up; the operator connects more, without a ceiling the app imposes. Each account carries at most one installation = one shard. **This answers §18-Q4 and Q8**, and the *uncapped* part is what forces the local index in §A.3. | user |
| **D8** | **Discovery-first deploy.** Installing from a second device, with the same Cloudflare account, must **find and adopt** the existing installation, never mint a second worker. Identity is recovered from Cloudflare, not from the phone (§B). Engine updates are the same mechanism run forward (§C). | user |
| **D9** | **`workers.dev` reachability is not a design constraint.** The user reports it is reachable from Iran. Custom domains stay a branding option, not a delivery mechanism. The one residual note is in §11. | user |

> **A design review argued against D3.** Its case is recorded in full at §18-Q1, with the cost of each
> path, because it is a real argument and the decision is reversible cheaply *now* and expensively
> later. The plan below implements D3 as chosen.

---

# 1. Existing-system analysis

## 1.1 What is there

| Piece | Path | Size |
|---|---|---|
| Worker source | `android/app/src/main/assets/mlm_worker.js` | **4448 lines**; `HTML_TEMPLATES` spans **2275–4448 = 2173 lines (49%)** |
| Data plane (the valuable part) | same file, ~660–2274 | ~1400 lines of hard-won code |
| Deployer | `engines/mlm/MlmDeployer.kt` | 258 |
| REST client | `engines/mlm/MlmApiManager.kt` | 195 |
| DTOs | `engines/mlm/MlmApiModels.kt` | 83 |
| Users UI | `engines/mlm/MlmUsersScreen.kt` | 619 |
| Settings UI | `engines/mlm/MlmSettingsScreen.kt` | 160 |
| Upgrade mechanism | `data/PanelBuild.kt:37` | `const val MLM = 5` |
| Host / entry points | `ui/CloudTab.kt` rows 1400-1525, modals 429-457, redeploy 675-683 | |
| Dev ancestor + the only prose docs | `cf-xhttp-panel/worker.js`, `schema.sql`, `README.md` | |

Storage is **D1 only** — `users`, `settings`, `debug_logs`. Schema is built lazily on *every request*
with `CREATE TABLE IF NOT EXISTS` plus best-effort `ALTER TABLE` in `try/catch` (`:612-643`). The D1
binding is duck-typed on `.prepare()` (`:69-84`), so it can carry any name.

## 1.2 Two corrections to premises this plan started from

**(a) The panel is not xhttp-only.** `PanelBuild.kt:24-31` records that build 4 moved the *emitted
configs* to WebSocket. Both transports exist server-side today:

- `handleXHTTP` (`:1282`) — stream-one and packet-up modes, **IPv6 supported** (`:1320-1325`)
- `handleWsTunnel` (`:880`) — full VLESS-over-WS, 15s heartbeat re-validating quota, **IPv6 NOT
  supported** (`:1147`)

and the URI builder emits `type=ws` (`:833`). WebSocket is not work to be done — it is what ships.
What is genuinely missing is **gRPC, HTTP Upgrade, raw TCP** and per-user WS paths.

**(b) Worker script size is a non-risk — an earlier draft of this plan had it wrong.** The limit is
**64 MiB uncompressed**, identical on Free and Paid; there is no gzipped ceiling. `mlm_worker.js` is
239,809 bytes = **0.36% of budget**. A control plane three times larger is free. Removed from the risk
register.

## 1.3 The real limitations

| # | Limitation | Evidence | Consequence |
|---|---|---|---|
| 1 | **Session cookie IS `sha256(admin password)`** — no nonce, expiry, revocation | `:661-670`; the Android client reproduces it locally and never logs in, `MlmApiManager.kt:24-43` | Cannot be the auth model of a product that sells access |
| 2 | **`ADMIN_PASSWORD` ships as a `plain_text` binding** defaulting to `"admin"` and **overrides the DB** | `MlmDeployer.kt:196-201` vs `getAdminHash` `:673-676` | Every app-deployed worker has the literal password in its script metadata, readable by anyone with the CF account; `/api/change-password` is a silent no-op |
| 3 | **`DEBUG=1` hardcoded on** | `MlmDeployer.kt:202-206` | One `debug_logs` row per request, forever, unpruned. Burns the scarce D1 **write** budget |
| 4 | **VLESS only** | `atob('dmxlc3M=')` at `:270`, `:582` | Trojan is in the brief; `nahan_worker.js` already implements it |
| 5 | **WS listens only on `pathname === '/'`** | `:182` | Any other path silently returns the fake nginx page with **HTTP 200** — the worst failure mode there is. Blocks per-user paths |
| 6 | **The DAILY quota is inert on WS — the shipping default transport** | `isUserCapped` (`:1658`) tests four things: `is_active`, `limit_gb`, `daily_limit_gb`, `expiry_days`. The WS path never calls it but **inlines three of the four** — at admission (`:1088`) and again on the heartbeat re-validation (`:915-930`) — omitting only `daily_limit_gb`. Its byte commits (`:909`, `:946`) likewise write `used_gb` and never `daily_used_gb` | **Lifetime quota and expiry are enforced on WS and work.** What does not work is the daily quota: the counter never rises, so the limit never trips, and the status page draws a daily bar that can never fill. Fixing it needs **both** halves — accrual (E3) and the missing admission test (E2) — because either alone still does nothing |
| 7 | **`expiry_days` counts from `created_at`** | `:992`, `:1107` | Renew/extend impossible without delete+recreate; "expire after first connection" unrepresentable |
| 8 | **`username` is the API primary key** | `PUT`/`DELETE /api/users/{name}`, `:506`, `:543` | Rename structurally impossible |
| 9 | **No device / concurrent / IP limit anywhere in the repo** | `ACTIVE_CONNECTIONS_COUNT` (`:7`) is a per-isolate `Map` | Entirely net-new; needs durable cross-isolate state (D4) |
| 10 | **URI builder triplicated**, JSON builder duplicated | `:833`, `:3475`, `:4308`; `:715`, `:3552` | A protocol × transport matrix cannot live in four places |
| 11 | **`GET /api/users` is O(all users), no pagination**, and flushes traffic inline | `:548-563` | An operator with 500 users pays for the whole table on every refresh |
| 12 | **Module-global caches** (`schemaEnsured`, `cachedPanelPassword`, `GLOBAL_TRAFFIC_CACHE`) | `:6-8`, `:608-609` | Behaviour differs per isolate until each warms |
| 13 | **`tls` stored but never read**; `port INTEGER` holds a CSV string | `:812-855` | The schema lies about itself; TLS is re-derived from the port in all five builders |
| 14 | **Subscription endpoints unauthenticated** — the username is the bearer secret | `:257-282` | No rotation, no revocation, guessable |
| 15 | **Traffic accounting is lossy and split** | 50 MB flush granularity; XHTTP commits both counters (`:1362`), WS only lifetime (`:909`) | "Exact usage accounting" is not currently true |
| **16** | **A second device deploying the same Cloudflare account mints a *second* worker** | `MlmDeployer.kt:217` — `PanelBuild.scriptName(account,"MLM")` derives the script name from `account.mlmWorkerUrl` (`PanelBuild.kt:91-96`), which is null on a fresh install, so it falls through to `AntiDpi.generateSafeWorkerName()` (`utils/AntiDpi.kt:55-59`) = `<prefix>-<6 random hex>` | **Directly blocks D8.** The D1 *is* found and reused (`MlmDeployer.kt:91-126` searches existing databases by name), so this is not data loss — it is an orphaned worker still serving the old script on the old URL, outside `PanelBuild`'s staleness check, invisible to the app. Fixed in §B |
| **17** | **The legacy name is in the public hostname** | the deployer appends `-mlm`, so every URL is `https://<prefix>-<hex>-mlm.<sub>.workers.dev` | **A direct D1 breach that R10 flags but draft 1 never noticed is already shipped**: the subscription URL a person opens contains the legacy name. Renaming the script changes the URL and **breaks every deployed subscription link**. See §18-Q7 |

## 1.4 Android-side problems

- **No shared worker-deploy helper.** Nine functions each re-implement the same five steps:
  `CloudManager.deployWorker:985`, `deployEdgWorker:1505`, `deployDnsWorker:1741`,
  `deployGstRelayWorker:2044`, `deployVpnGateRelay:2617`, `deployMlmPoolWorker:2748`,
  `NahanDeployer.deployNahan`, `MlmDeployer.deployMlm`, `SubGenDeployer.deploySubWorker`.
- **`saveAccounts()` publishes the same object references** (`CloudManager.kt:216`), so in-place field
  mutation — what every deploy does — is dropped by `StateFlow`'s `distinctUntilChanged`. Three screens
  carry a `justDeployed` workaround; documented at `AntiSanctionScreen.kt:120-128`, also
  `GameTab.kt:147-150`, `VpnGateTab.kt:102`.
- **`CloudAddAccountScreen` is welded** to a full `IosScreen` with a hardcoded "Cloud" back label
  (`ui/CloudScreens.kt:159`) — cannot be hosted inside a wizard step as-is.
- **`CloudTab.kt:59-60` shadows the flow into local mutable state** — anti-pattern, do not copy.
- **The scan→subscription loop exists but is a hack**: a `Button` buried at `ScannerTab.kt:1069-1112`
  that overwrites the user's entire `ips` field and reports by toast.
- **Zero Durable Object references anywhere in `android/app/src/main`.** The deploy path for a DO is
  unprecedented in this repo — see R1.
- **No ViewModels anywhere** in ~50 screens. State is `remember` + singleton `StateFlow`s.
- **No feature screen is D-pad navigable.** `Modifier.tvFocusable` (`ui/home/TvFocus.kt:79`) has exactly
  two call sites, both on the home screen.

## 1.5 What is good and stays

- **`CloudManager` is a true process singleton** (`:20-33`, double-checked `companion.invoke`), so D2
  costs nothing: `collectAsState()` on `accountsFlow` and it is done.
- **`PanelBuild` is a clean upgrade mechanism**: bump the constant → account reads stale → redeploy
  overwrites the script **and reuses `account.mlmDbId`**, so users and traffic survive. This is what
  makes D3 viable at all.
- The `IosSettings.kt` kit is complete and consistent — nothing new to design.
- `FreeConfigWizard.kt` is a proven template for a long, network-heavy, cancellable flow.
- Sharing needs **no new code**: `NodeQrCard` (`ui/NodesScreens.kt:394`) is public and takes any string;
  clipboard + `ACTION_SEND` is at `NodesScreens.kt:268-291`; subscription-link detail UI is
  `ui/sublink/SubLinkDetailScreen.kt:94-167`; save-to-Downloads is `GstSetupWizard.kt:637`.

---

# 2. What can be reused

| Need | Reuse | Path |
|---|---|---|
| Cloudflare account store | `CloudManager` singleton + `accountsFlow` | `data/CloudManager.kt:20-49` |
| Add an account inline | `CloudManager.addAccount(token, email)` — **reversed argument order vs the UI lambda** | `:219-349` |
| Diagnose a bad credential | `probeCredential`, `probeAccountStatus`, `CloudVerifyProbe.advice()/.credentialAdvice()` | `:2353`, `:2245`, `CloudAdvice.kt:48/:184` |
| Troubleshooting page | `CloudTroubleshootScreen` — takes `cloudManager` as a param, drop-in | `ui/CloudTroubleshootScreen.kt:66` |
| Create a workers.dev subdomain | `createSubdomain` | `CloudManager.kt:2478` |
| Deploy shape | `MlmDeployer.deployMlm` — subdomain → D1 → multipart PUT → enable route → persist | `engines/mlm/MlmDeployer.kt:27` |
| Upgrade / staleness / script name | `PanelBuild` (`scriptName()` derives the CF name from the URL, so redeploy overwrites) | `data/PanelBuild.kt` |
| UI kit | `IosScreen`, `SettingsGroup/Row/Toggle/TextRow/ActionRow/Footer/SectionHeader/Slider`, `IosPickerScreen`, `IosTextScreen`, `IosAlert`, `IosPrompt` | `ui/settings/IosSettings.kt`, `IosAlerts.kt` |
| Wizard skeleton | enum steps **including async states**, per-step `IosScreen`, named orchestrators, cooperative cancel, `DoneStep` hand-off | `ui/FreeConfigWizard.kt` |
| Wizard chrome | `StepDots:220`, `StepHeader:247`, `WizardField:276`, `WizardPrimary:322`, `InfoCard:340`, the single `BackHandler:116` | `ui/emergency/GstSetupWizard.kt` |
| LTR pinning for ASCII payloads | `CompositionLocalProvider(LocalLayoutDirection provides Ltr)` | `GstSetupWizard.kt:290`, `:431` |
| Two-pane ≥720dp | the light `stack` + `detail` pattern (~40 lines, not Settings' 700) | `ui/tunnel/TransportHost.kt:25-125` |
| Trojan implementation | already written and shipping | `assets/nahan_worker.js` |
| Absolute expiry model | `expiryMs` instead of days-from-creation | `engines/nahan/NahanApiModels.kt:14` |
| Per-install random API route | the idea, to hide the API surface behind the camouflage page | `nahanApiRoute` in `NahanDeployer.kt` |
| gRPC transport reference | ~59 references | `assets/edg_worker.js` |
| A real `/stats` endpoint shape | | `assets/mlmvpn_pool_worker.js:931`; `x4g/routes.js:164` |
| **Assistant pattern** | `CloudCoach` + `CombineCoach` + `CoachBar` — an 8-step journey that **does** the work and reports what happened | `ui/CloudCoach.kt` |
| Xray outbound emission | correct `ws` (`:276-289`) and `xhttp` (`:292-307`) arms already | `utils/XrayJsonGenerator.kt:230-333` |
| Persian digits | `faCount`, `faDigits` — **do not add a sixth copy** | `ui/NodesScreens.kt:810`, `ui/emergency/EmergencyKit.kt:236` |

---

# 3. What must be refactored first

| # | Refactor | Why it must come first | Size |
|---|---|---|---|
| **F1** | **Extract `CloudAddAccountForm`** from `CloudAddAccountScreen` (`ui/CloudScreens.kt:159`): body `:178-241` becomes a `ColumnScope` composable; the screen becomes a thin `IosScreen { form }` wrapper | Wizard step 1 must host the form inline (D2 + the brief's "no redirect"). Without it: a nav bar inside a nav bar and nested scrolling | ~40 lines moved; `CloudTab.kt:189` untouched |
| **F2** | **Split the worker asset into source fragments + a build step.** `android/worker-src/studio/**` → `node android/scripts/build-studio-worker.js` → `assets/mlm_worker.js`. **Done in Phase 0**, as a byte-identical mechanical split — see the note in §8.1 for why it is not yet the semantic layout | Every later phase edits a 4448-line file that is 49% inline HTML. The cost compounds. Also the only place a **protocol-literal grep guard** (R2) can live | new tree + 1 script |
| **F3** | **Delete the duplicated URI builders.** ~~Phase 0~~ → **build 6 / build 7**, see the note below | A protocol × transport matrix in four places is unmaintainable | net −250 lines |
| **F4** | **`ConfigBuilder.kt` in Kotlin**, and refactor `XrayJsonGenerator.kt:230-333` to *delegate* its `streamSettings` construction to it | The app's **connect** path and the Studio's **emit** path must never disagree. Highest-leverage item in the plan | ~280 lines + golden fixtures |
| **F5** | **Versioned migrator** replacing lazy `ensureSchema` per request | Additive columns are coming; `catch(e){}` cannot distinguish "column exists" from "typo, permanently broken" | ~80 lines |
| **F6** | **Immutable `StudioState`** so `distinctUntilChanged` works, instead of a fourth `justDeployed` flag | Fixes §1.4's blind spot at the root for this feature | design, not lines |

| **F7** | **A build-time string-lockstep check.** A script that fails the build when `values/strings.xml` and `values-fa/strings.xml` disagree on their key set | R11 is treated as a review rule, but Phase 1–5 adds ~450 strings = ~900 hand-edited entries across two files. Drift is a certainty, not a risk, and it shows up as a raw resource name in one locale | ~40 lines |
| **F8** | **Deterministic script naming + the discovery scan** (§B.2.1, §B.2.2) | D8 is impossible without it: today a second device mints a second worker (limitation #16). It must land before anything writes `StudioInstallation`, because adoption is what decides whether an installation exists at all | ~260 lines |

> **F3 cannot be done in Phase 0, and the reason is that the three copies are not three copies of
> the same thing.** `HTML_TEMPLATES` begins at `:2275`, so of the three cited builders only `:833`
> runs **in the Worker**. `:3475` and `:4308` run **in the user's browser**, inside the panel's
> template literals — which is also why they obfuscate differently (`'vle' + 'ss://'` string
> splitting, against the worker's `atob`) and are written in `var`-era JS. They cannot call a
> Worker-side function or read a `configs.uri_template` row; they would have to be *served* the
> result.
>
> So the work splits along that seam and neither half belongs in Phase 0:
>
> - **`:833` is replaced at build 6**, when `configs.uri_template` exists to replace it with. There is
>   nothing to delete it in favour of before then.
> - **`:3475` and `:4308` are deleted at build 7 together with the whole 2,173-line panel** (§8.1).
>   Unifying them first would mean injecting a shared source into a template literal, to be thrown
>   away one build later.
>
> Attempting it now would change the behaviour of a worker that is live on real accounts, with no
> test harness (§19) to catch a regression, in exchange for nothing.

Explicitly **not** refactored now: unifying the nine deploy functions. Right cleanup, wrong time —
it touches every panel in the app and bundling it with a new feature doubles the blast radius.

---

# 4. Platform budgets — the numbers everything else is designed against

Verified against Cloudflare docs during planning. **These are the real constraints, not the script size.**

| Resource | Free-plan ceiling | Notes |
|---|---|---|
| **Worker requests** | **100,000 / day, account-wide** | The true global ceiling |
| Worker CPU | 10 ms / request | Excludes I/O wait; the existing worker fits |
| Subrequests | 50 / request | |
| **D1 row writes** | **100,000 / day** | **The scarce resource.** Not reads |
| D1 row reads | 5,000,000 / day, **account-wide** | The 2026-09-06 outage was here |
| D1 databases | 10 / account, 500 MB each, 5 GB total | Relevant to D3 vs a second worker |
| D1 queries | 50 / Worker invocation | An N+1 in the data plane is a hard failure |
| **DO requests** | **100,000 / day** | Separate budget from Workers |
| **DO row writes** | **100,000 / day** | Separate budget from D1 |
| DO row reads | 5,000,000 / day | |
| DO storage | 5 GB, **SQLite backend only on Free** | KV backend needs Paid |

### 4.1 The transport consequence — this is a product decision, not a detail

- **WebSocket: one Worker request per connection**, held open indefinitely (no duration limit for
  HTTP-triggered Workers). A user connecting 20×/day costs 20 requests.
- **XHTTP packet-up: one request per upload chunk.** `handleRequest:140` matches `/{uuid}/{seq}` and
  every sequence number is a separate billable request. One active session can burn thousands.

That is the real reason build 4 switched to WebSocket. **Config Studio must default to WebSocket and
treat XHTTP as an advanced opt-in with a visible warning in the template UI**, not a comment in code.

An operator with 100 users on one free account:

```
100 users × 15 connections/day       =  1,500 WS requests/day
subscription fetches (12h interval)  =    200 /day
Android admin polling                =   ~500 /day
                                        ───────
                                        ~2,200 / 100,000   ✓ 2%
```

The same 100 users on XHTTP would exhaust the account before lunch.

### 4.2 The D1 read outage, and the rules it leaves

`ORDER BY RANDOM()` makes SQLite materialise and sort **every matching row**, so `rows_read` equals
table size regardless of `LIMIT`. On 2026-09-06 one such query in the pool worker took the
**account-wide** 5M/day read ceiling down, and every D1-backed worker on that account died together.

Rules, enforceable by grep in review:

1. **No `ORDER BY RANDOM()`, ever.** Randomise on an already-uniform indexed column, or pick in JS from a small `LIMIT`ed set.
2. **Every list query has a `LIMIT` the index can stop at**, and an index-covered `WHERE`/`ORDER BY`.
3. **`COUNT(*)` over a whole table is banned in request paths.** Counts come from a `counters` table maintained on write.
4. **No `SELECT *` on `users`** in the data plane — select the columns the admission check needs.
5. `GET /v1/health` returns `meta.rows_read` from its own queries so the app can display drift.

---

# 5. Protocol / transport abstraction — one builder, and it lives in Kotlin

## 5.1 The model

```kotlin
// data/studio/domain/Profiles.kt
enum class ProtocolType  { VLESS, TROJAN, VMESS, SHADOWSOCKS }
enum class TransportType { WS, XHTTP, GRPC, HTTPUPGRADE, TCP }
enum class SecurityType  { NONE, TLS, REALITY }

data class ProtocolProfile(
    val type: ProtocolType,
    val credential: String,                        // uuid | trojan password | ss psk
    val extra: Map<String, String> = emptyMap(),   // vless flow, vmess alterId, ss method
)

data class TransportProfile(
    val type: TransportType,
    val settings: Map<String, String>,             // OPAQUE: path, host, serviceName, mode, …
)

data class SecurityProfile(
    val type: SecurityType,
    val sni: String? = null, val fingerprint: String? = null,
    val alpn: List<String> = emptyList(), val allowInsecure: Boolean = false,
    val extra: Map<String, String> = emptyMap(),   // reality pbk/sid/spx
)

data class ConfigSpec(
    val protocol: ProtocolProfile,
    val transport: TransportProfile,
    val security: SecurityProfile,
    val host: String, val port: Int, val remark: String,
)
```

The brief's requirement — *no transport-specific fields in the main configuration model* — holds
literally: `TransportProfile.settings` is opaque and `ConfigSpec` names no transport concept anywhere.
**Adding gRPC is one `TransportType` entry and one `when` arm in each of two functions. Adding VMess is
one `when` arm in each of two functions.** Neither touches `ConfigSpec`, the D1 schema, the API, or any
screen.

## 5.2 Where it lives — and why the worker builds nothing

```kotlin
// data/studio/config/ConfigBuilder.kt   — two pure functions, the only implementation
fun buildUri(spec: ConfigSpec): String
fun buildXrayOutbound(spec: ConfigSpec): JSONObject
```

`buildXrayOutbound` is **extracted from and then shared with** `utils/XrayJsonGenerator.kt:230-333`,
which already has correct `ws` and `xhttp` arms plus the vless/trojan/vmess/ss outbound shapes. Do not
fork it — refactor it to delegate `streamSettings` to `ConfigBuilder`, so the app's *connect* path and
the Studio's *emit* path cannot disagree. This collapses `:833`, `:3475`, `:4308` and
`XrayJsonGenerator` into one implementation (F3 + F4).

**The worker does not build URIs.** When a config is saved, Kotlin renders a template and stores it:

```
configs.uri_template =
  vless://{{cred}}@{{host}}:{{port}}?type=ws&security=tls&sni={{sni}}&host={{sni}}
         &path={{path}}&fp=chrome&encryption=none#{{remark}}
```

The `/s/{token}` handler becomes ~30 lines: load the user's enabled configs, fan out over
`node.host × node.ports`, substitute five placeholders, join, base64. **There is no protocol logic in
the worker at all** — which is the answer to "how are the two builders kept in step": there are not two.

The one server-side exception is the **route key** — the worker must match an incoming WS path (or gRPC
serviceName) back to a config row. That is `configs.route_key`, also computed in Kotlin, matched by an
exact indexed lookup. Still no protocol logic server-side.

**Safety net:** `android/app/src/test/.../ConfigBuilderGoldenTest.kt` holds ~20 fixtures of
`{spec → expected uri, expected outbound json}` covering vless×{ws,xhttp,grpc,httpupgrade,tcp} and
trojan×same, tls and none. The same JSON fixture file is readable by a Node test over the worker's
substitution function.

## 5.3 Honest transport capabilities

| Transport | Status | Cost |
|---|---|---|
| **WS** | exists, shipping, **the default** | 0 |
| **XHTTP** | exists | 0, but see §4.1 — advanced opt-in with a warning |
| **HTTPUPGRADE** | ~40 lines on top of the WS handler (same 101 response, different `Upgrade` header) | cheap, ship it in Phase 2 |
| **GRPC** | real work; port from `assets/edg_worker.js` | its own phase |
| **TCP** | **not implementable as a Worker inbound.** A Worker cannot accept a non-HTTP TCP listener | exists in the model so a `Node` pointing at a non-Worker endpoint can use it; **greyed out for Worker-hosted nodes with a reason string** |

Also: WS does not support IPv6 destinations (`:1147`) while XHTTP does. Surface it as a node capability
flag, not as a mystery.

---

# 6. Domain model (Kotlin, `data/studio/domain/`)

**The UI never sees a worker response type.** DTOs live in `data/studio/api/dto/` and are mapped at the
boundary. Enforce by grepping for `api.dto` under `ui/`.

```kotlin
CloudAccount                  // EXISTING, reused unchanged — plus a studio field block (§12.3)
StudioFleet                   // §A.2 — the list of installations; one per CloudAccount
Plan                          // §D — reusable terms. NO price field, in any phase (D6)

data class StudioInstallation(
    val accountId: String, val workerUrl: String, val apiRoute: String,
    val dbId: String, val doTag: String?, val keyId: String,
    val build: Int, val schemaVersion: Int,
    val status: InstallStatus,            // NONE, DEPLOYING, HEALTHY, STALE, UNREACHABLE, BROKEN
    val enforcement: EnforcementMode,     // STRICT (DO present) | SOFT (DO absent/advisory)
    val capabilities: Set<Capability>,    // from GET /v1/health — the UI hides what it cannot do
)

data class StudioUser(
    val id: String,                       // ULID — NOT the username, so rename is a PATCH
    val username: String, val note: String?, val tags: List<String>,
    val groupId: String?, val status: UserStatus,
    val policy: SubscriptionPolicy, val usage: TrafficUsage,
    val subscription: Subscription,
    val createdAt: Long, val firstConnectAt: Long?, val lastActiveAt: Long?,
    val onlineNow: Boolean, val deviceCount: Int,
    val installationId: String,           // §A.2 — which shard. CLIENT-SIDE ONLY; no worker knows it
    val planId: String?,                  // §D — which terms this user was created from
)

data class SubscriptionPolicy(
    val expiryMode: ExpiryMode,           // ABSOLUTE | ON_FIRST_CONNECT
    val expiresAt: Long?, val activationDays: Int?,
    val quotaBytes: Long?,                // BYTES, never GB floats
    val dailyQuotaBytes: Long?,
    val reset: TrafficResetPolicy,        // None | Daily | Weekly | Monthly | EveryDays(n)
    val deviceLimit: Int?, val connLimit: Int?, val ipLimit: Int?,
    val enforcement: EnforcementMode,     // per-user STRICT | SOFT — the brief's ask
    val speedLimitKbps: Int? = null,      // reserved, not enforced in any phase
)

data class TrafficUsage(
    val usedBytes: Long, val dailyUsedBytes: Long,
    val quotaResetAt: Long?, val updatedAt: Long,
)

data class Subscription(
    val id: String, val token: String,    // the secret; NOT the username
    val createdAt: Long, val rotatedAt: Long?, val revokedAt: Long?,
    val hits: Int, val lastHitAt: Long?, val lastUa: String?,
    val url: String,                      // derived {workerUrl}/s/{token}
)

data class StudioConfig(
    val id: String, val userId: String, val templateId: String?, val nodeId: String?,
    val label: String, val enabled: Boolean,
    val protocol: ProtocolProfile, val transport: TransportProfile, val security: SecurityProfile,
    val credentialRotatedAt: Long?, val createdAt: Long,
)

data class ConfigTemplate(                // NO credentials, NO quotas — the brief's explicit split
    val id: String, val name: String, val isDefault: Boolean,
    val protocol: ProtocolType, val transport: TransportProfile, val security: SecurityProfile,
    val advanced: Map<String, String>, val nodeSelector: String?,
)

data class StudioNode(
    val id: String, val name: String, val groupId: String?, val country: String?,
    val host: String, val ports: List<Int>, val sni: String?, val hostHeader: String?,
    val protocols: Set<ProtocolType>, val transports: Set<TransportType>,
    val priority: Int, val enabled: Boolean,
    val health: NodeHealth,               // UP | DOWN | DEGRADED | UNKNOWN + checkedAt + rttMs
    val metadata: Map<String, String>,
)
data class NodeGroup(val id: String, val name: String, val strategy: NodeStrategy, val priority: Int)

data class UserDevice(
    val deviceHash: String,               // a heuristic, not an identity — the UI must say so
    val firstSeen: Long, val lastSeen: Long,
    val lastIpHash: String?, val clientHint: String?, val blocked: Boolean,
)

data class UserSession(
    val id: String, val userId: String, val configId: String?, val nodeId: String?,
    val deviceHash: String?, val protocol: String, val transport: String,
    val startedAt: Long, val endedAt: Long?, val up: Long, val down: Long,
    val closeReason: String?,
)

// The two logs, deliberately separate — see §5 of the storage model
data class ActivityEntry(                 // what the SYSTEM observed. High volume, pruned hard
    val id: Long, val ts: Long, val kind: ActivityKind,
    val userId: String?, val configId: String?, val nodeId: String?,
    val severity: Severity, val detail: String?,
)
data class AuditEntry(                    // what a PERSON did. Low volume, retained
    val id: Long, val ts: Long, val actor: String, val actorKeyId: String?,
    val action: String, val targetType: String, val targetId: String,
    val beforeJson: String?, val afterJson: String?,   // ALWAYS both
)
```

---

# 7. D1 schema (build 6, additive on a live database)

## 7.1 Migration mechanism (F5)

Replace `ensureSchema`'s per-request `CREATE TABLE IF NOT EXISTS` + swallowed `ALTER`. That pattern
cannot distinguish "column already exists" (fine) from "typo, permanently broken" — both are eaten by
`catch (e) {}`.

```js
const MIGRATIONS = [ { v: 6, name: 'studio_core', sql: [ /* … */ ] } ];

async function migrate(db) {
  if (migrationsDone) return;                       // isolate-local fast path
  await db.prepare(`CREATE TABLE IF NOT EXISTS schema_version
      (v INTEGER PRIMARY KEY, name TEXT, applied_at INTEGER)`).run();
  const at = (await db.prepare('SELECT MAX(v) AS v FROM schema_version').first())?.v ?? 0;
  for (const m of MIGRATIONS.filter(m => m.v > at)) {
    await db.batch([                                // D1 batch = one implicit transaction
      ...m.sql.map(s => db.prepare(s)),
      db.prepare('INSERT INTO schema_version (v,name,applied_at) VALUES (?,?,?)')
        .bind(m.v, m.name, Date.now()),
    ]);
  }
  migrationsDone = true;
}
```

A migration lands whole or not at all. Failures **propagate** rather than being swallowed;
`GET /v1/health` reports `schema_version` and `migration_error`, and the wizard's **Repair** action
re-runs it and shows the real message.

**`migrate()` is called from the API path only. The data plane must never call it** — that would be a
read on every tunnel connection for no benefit.

## 7.2 Additive columns on `users`

```sql
id TEXT,                                   -- ULID, backfilled; becomes the API key
note TEXT, tags TEXT, group_id TEXT,
status TEXT DEFAULT 'active',              -- active|disabled|expired|suspended
expiry_mode TEXT DEFAULT 'absolute',       -- absolute | on_first_connect
expires_at INTEGER,                        -- absolute ms; supersedes expiry_days
activation_days INTEGER, first_connect_at INTEGER,
quota_bytes INTEGER, used_bytes INTEGER DEFAULT 0,
daily_quota_bytes INTEGER, daily_used_bytes INTEGER DEFAULT 0,
quota_reset_policy TEXT DEFAULT 'none', quota_reset_at INTEGER,
device_limit INTEGER, conn_limit INTEGER, ip_limit INTEGER,
enforcement TEXT DEFAULT 'soft',
updated_at INTEGER
```

**Byte precision matters.** `limit_gb REAL` cannot express "exactly 30 GB" and the operator
committed to it. Someone given exactly 30 GB and sees 29.7 because of float rounding is a support
ticket. Build 6 backfills `quota_bytes = CAST(limit_gb * 1073741824 AS INTEGER)` and **keeps writing
both** so a rollback to build 5 survives. Build 7 drops the old columns.

## 7.3 New tables

```sql
api_keys(id TEXT PRIMARY KEY, label TEXT, secret_hash TEXT NOT NULL,
         scopes TEXT DEFAULT 'admin', created_at INTEGER, expires_at INTEGER,
         last_used_at INTEGER, revoked_at INTEGER)

configs(id TEXT PRIMARY KEY, user_id TEXT NOT NULL, template_id TEXT, node_id TEXT,
        label TEXT, enabled INTEGER DEFAULT 1,
        protocol TEXT NOT NULL, credential TEXT NOT NULL, credential_rotated_at INTEGER,
        transport_type TEXT NOT NULL, transport_json TEXT NOT NULL,
        security_json TEXT NOT NULL,
        uri_template TEXT,                 -- rendered by Kotlin (§5.2)
        route_key TEXT,                    -- the path/serviceName the data plane matches
        created_at INTEGER, updated_at INTEGER)

templates(id TEXT PRIMARY KEY, name TEXT, is_default INTEGER DEFAULT 0,
          protocol TEXT, transport_type TEXT, transport_json TEXT,
          security_json TEXT, advanced_json TEXT, node_selector TEXT,
          created_at INTEGER, updated_at INTEGER)

nodes(id TEXT PRIMARY KEY, name TEXT, group_id TEXT, country TEXT,
      host TEXT, ports TEXT, sni TEXT, host_header TEXT,
      capabilities_json TEXT, priority INTEGER DEFAULT 100, enabled INTEGER DEFAULT 1,
      health TEXT DEFAULT 'unknown', health_checked_at INTEGER, metadata_json TEXT)
node_groups(id TEXT PRIMARY KEY, name TEXT, strategy TEXT DEFAULT 'all', priority INTEGER)

subscriptions(id TEXT PRIMARY KEY, user_id TEXT NOT NULL, token TEXT NOT NULL,
              format TEXT DEFAULT 'base64', created_at INTEGER, rotated_at INTEGER,
              revoked_at INTEGER, expires_at INTEGER,
              hits INTEGER DEFAULT 0, last_hit_at INTEGER, last_ua TEXT)

sessions(id TEXT PRIMARY KEY, user_id TEXT, config_id TEXT, node_id TEXT,
         device_hash TEXT, ip_hash TEXT, protocol TEXT, transport TEXT,
         started_at INTEGER, ended_at INTEGER,
         up_bytes INTEGER DEFAULT 0, down_bytes INTEGER DEFAULT 0, close_reason TEXT)

usage_daily(user_id TEXT, day INTEGER, up_bytes INTEGER, down_bytes INTEGER,
            sessions INTEGER, PRIMARY KEY(user_id, day))
usage_hourly(bucket_ts INTEGER PRIMARY KEY, up_bytes INTEGER, down_bytes INTEGER,
             sessions INTEGER, users_seen INTEGER)          -- account-wide: 24 rows/day, any user count

devices(user_id TEXT, device_hash TEXT, first_seen INTEGER, last_seen INTEGER,
        last_ip_hash TEXT, client_hint TEXT, blocked INTEGER DEFAULT 0,
        PRIMARY KEY(user_id, device_hash))

activity_log(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, kind TEXT,
             user_id TEXT, config_id TEXT, node_id TEXT,
             severity TEXT DEFAULT 'info', detail TEXT)
audit_log(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, actor TEXT, actor_key_id TEXT,
          action TEXT, target_type TEXT, target_id TEXT,
          before_json TEXT, after_json TEXT, ip_hash TEXT)

counters(key TEXT PRIMARY KEY, value INTEGER)   -- users_total, users_active, … (§4.2 rule 3)
```

## 7.4 Indexes — the quota defence, not an optimisation

```sql
CREATE UNIQUE INDEX ux_users_username  ON users(username);
CREATE UNIQUE INDEX ux_users_id        ON users(id);
CREATE INDEX ix_users_status_created   ON users(status, created_at DESC);
CREATE INDEX ix_users_expires          ON users(expires_at) WHERE expires_at IS NOT NULL;
CREATE INDEX ix_users_uuid             ON users(uuid);              -- the data-plane lookup
CREATE INDEX ix_users_updated          ON users(updated_at);        -- the ?since= sync watermark (§A.3)
CREATE UNIQUE INDEX ux_configs_cred    ON configs(protocol, credential);
CREATE UNIQUE INDEX ux_configs_route   ON configs(route_key) WHERE route_key IS NOT NULL;
CREATE INDEX ix_configs_user           ON configs(user_id);
CREATE UNIQUE INDEX ux_sub_token       ON subscriptions(token);
CREATE INDEX ix_sessions_started       ON sessions(started_at DESC);
CREATE INDEX ix_sessions_user          ON sessions(user_id, started_at DESC);
CREATE INDEX ix_activity_ts            ON activity_log(ts DESC);
CREATE INDEX ix_audit_ts               ON audit_log(ts DESC);
```

## 7.5 Retention — what stops the next quota outage

Driven by the DO alarm, hourly, **bounded deletes with `LIMIT`** so a prune never becomes an unbounded
write:

- `debug_logs` — **`DEBUG` defaults off** (the deployer stops hardcoding `1`); capped by rolling delete when an operator turns it on
- `activity_log` — 14 days or 20k rows
- `sessions` — 30 days
- `usage_hourly` — 7 days (168 rows)
- `audit_log` — 180 days. It is the record an operator may need when a figure is questioned
- `usage_daily` — kept; it is the analytics source and it is one row per user per day

---

# 8. Backend architecture

## 8.1 Source layout (F2)

The 4448-line asset becomes a build output. Sources under `android/worker-src/studio/`:

```
index.js                 router + reserved-path guard
data-plane/              vless.js trojan.js  ws.js xhttp.js grpc.js httpupgrade.js
                         admission.js        ← the DO client + verdict cache
                         usage.js            ← the ONE accrual path
control-plane/           auth.js users.js configs.js devices.js sessions.js
                         templates.js nodes.js stats.js activity.js audit.js
                         backup.js settings.js subscription.js
storage/                 d1.js migrations.js retention.js counters.js
do/                      SessionDO.js
panel/                   status.html         ← the user-facing page (§9)
                         nginx.html          ← the camouflage page
build/                   obfuscate.js        ← R2's guard, applied by the build not by hand
```

Build: `node android/scripts/build-studio-worker.js` → `app/src/main/assets/mlm_worker.js`.
**The deployer is untouched** — it still uploads one file, and `PanelBuild.scriptName()` still derives
the CF script name from the stored URL, so redeploy overwrites in place.

> **Built in Phase 0, and deliberately not in the shape above.** The layout in this section is the
> **build 6 target**. What actually shipped in Phase 0 is a *mechanical* split: fourteen numbered
> fragments (`01-globals.js` … `14-panel-html.js`) listed in `worker-src/studio/manifest.json`,
> concatenated verbatim, sharing one top-level scope, with **no imports between them and nothing
> renamed or reordered**. `node scripts/build-studio-worker.js --check` reproduces the previous asset
> **byte for byte** (239,809 bytes), and Gradle's `checkWorkerAsset` runs that on every build.
>
> The restraint is the point. This worker is already deployed on live accounts, and a redeploy
> overwrites the script currently carrying people's traffic — so a refactor whose output cannot be
> *proven* identical is not a refactor, it is an unreviewed rewrite of something in production. There
> is also no test harness for the worker (§19) to catch a regression if one were introduced.
>
> The semantic layout above is then reached **by moving code between fragments as build 6 is
> written**, each move a diff that can be read on its own — rather than as one large reorganisation
> landing at the same time as new behaviour.
>
> Two findings from doing it, both now encoded in the build script:
>
> - **The R2 guard reads string literals, not prose.** Its first version rejected a *comment* in
>   `06-subscription.js` explaining xray's WebSocket behaviour, which would have meant a build that
>   could only pass by deleting a useful explanation.
> - **Cloudflare's scanner is demonstrably not a keyword match.** The asset shipping today carries
>   eight plaintext `VLESS` strings in the panel HTML and that comment, and every upload has been
>   accepted. So `14-panel-html.js` is exempt from the guard (it is deleted at build 7 regardless),
>   while the code fragments are held to the `atob` discipline.

The 2173-line inline HTML panel is **deleted in build 7**, once Config Studio is the UI and the legacy
`MlmUsersScreen`/`MlmSettingsScreen` are gone. That reclaims half the file without forking it.

## 8.2 The Durable Object — `SessionDO`, one per user

`env.SESSIONS.idFromName("u:" + userId)`. **One object per user, not per connection** — that
serialization is exactly the critical section that makes a cap a cap.

Why the alternatives fail:

| | Verdict |
|---|---|
| **KV** | **Unusable.** Eventually consistent, up to 60 s propagation. Two connections in two colos both read `count=0` and both admit. A cap that fails open under exactly the load it exists to limit is not a cap |
| **D1 leases table** | **Works, but the write budget kills it.** open = INSERT, 60 s heartbeat = UPDATE, close = DELETE → one user connected 8 h = **482 D1 writes**. Twelve such users exhaust the entire 100k/day budget, leaving nothing for accounting or CRUD. Also D1 has no cross-statement transactions from a Worker, so the read-then-insert check is racy anyway |
| **Durable Object (SQLite)** | **Recommended (D4).** Serialized single-threaded execution per object gives a real critical section for free. Available on Free |

Internal SQLite (the DO's own storage — **does not touch the D1 budget**):

```sql
leases(lease_id TEXT PRIMARY KEY, config_id TEXT, device_hash TEXT, client_ip TEXT,
       opened_at INTEGER, last_seen INTEGER, up INTEGER, down INTEGER);
known_devices(device_hash TEXT PRIMARY KEY, first_seen INTEGER, last_seen INTEGER,
              last_ip TEXT, client_hint TEXT, blocked INTEGER DEFAULT 0);
policy_cache(k TEXT PRIMARY KEY, v TEXT, fetched_at INTEGER);
```

`open()` does, in one serialized turn: sweep leases older than 120 s → count live leases (concurrency
cap) → count distinct `device_hash` (device cap) → count distinct `client_ip` among live leases (IP cap)
→ check the cached policy/quota snapshot → insert the lease → return `{allow, reason, leaseId}`.

**Accounting cost per connection:**

| Event | DO requests | DO row writes | D1 row writes |
|---|---|---|---|
| open | 1 | 2 | 0 |
| heartbeat / 120 s | 0 *(alarm, not a request)* | 1 batched for all live leases | 0 |
| close | 1 | 2 | 0 |
| flush (alarm, 5 min or last-lease-close) | 0 | 0 | **1–3** |

An 8-hour session ≈ **2 DO requests, ~245 DO row writes, ~3 D1 writes** — versus 482 D1 writes for the
naive design. Against 100k DO writes/day that is **~400 user-connection-hours/day**, roughly 50 users at
8 h each. Widening the alarm to 300 s takes it to ~1000 connection-hours/day.

**Surface this as a capacity meter in the Studio UI**, not as an outage the operator discovers.

**Mandatory fallback.** `env.SESSIONS` may be absent — an older deployment, or DO unavailable on the
account. `admission.js` detects this once and degrades to **soft mode**: devices and sessions still
recorded and reported, caps not enforced, and the policy screen shows an amber "advisory enforcement"
badge with the reason. That fallback is not a hack — it *is* the brief's **strict vs soft device-limit
policy**, so wire the per-user toggle to the same code path.

## 8.3 The four surgical edits to the data plane

| # | Location | Change |
|---|---|---|
| **E1** | `handleRequest:182`, `url.pathname === '/'` | Accept any non-reserved path for WS upgrade; resolve `configs.route_key` → config row. This is what makes per-user WS paths possible. **In the routing hot path, above the camouflage fallback — ordering must be tested explicitly** (R6) |
| **E2** | `handleWsTunnel` credential check (~`:1080`) | Replace the direct `SELECT … FROM users WHERE uuid = ?` with `Admission.open(env, {credential, protocol, clientIp, ctx})` → `{allow, userId, configId, proxyIp, reason}` |
| **E3** | `handleWsTunnel` `addBytes:890` **and** `handleXHTTP:1362` | Both write through a single `Usage.accrue(userId, configId, up, down)`. **This closes limitation #6** — WS finally accrues to daily counters |
| **E4** | `setOffline:926` and the xhttp `finally:1605` | Call `Admission.close(...)` so the lease is released and the session row written |

Everything else in the data plane is untouched, so it stays diff-able if a fix lands elsewhere.

## 8.4 Deployer changes (`MlmDeployer.kt`)

- Add a `durable_object_namespace` binding `SESSIONS` **and** the tagged migration (R1)
- **Stop sending `DEBUG=1`**
- Generate a 32-byte bootstrap secret client-side; upload `sha256(secret)` as a **`secret_text`**
  binding `STUDIO_BOOTSTRAP_HASH` — never `plain_text`
- Store the per-install random `apiRoute` in `settings`
- Handle "DO unavailable on this account" as a **soft** failure that still produces a working install

---

# 9. What the user sees — «سابی که به کاربر می‌دیم»

The explicit ask: the subscription must show **exact volume, remaining time, and how many people can
use it**. Two surfaces, because they reach different people.

## 9.1 In the user's own VPN client — HTTP headers

Every mainstream client (v2rayNG, Hiddify, Streisand, Nekoray, sing-box GUIs) reads these off the
subscription response. This is how volume and expiry appear in the client's own UI with no browser:

```
Subscription-Userinfo: upload=0; download=<usedBytes>; total=<quotaBytes>; expire=<unixSeconds>
Profile-Title: base64(<brandName> — <displayName>)
Profile-Update-Interval: <hours>
```

`total=0` means unlimited by convention; `expire=0` means never. `usedBytes` is the **byte** counter,
not the lossy GB float (§7.2).

**There is no standard header for a device limit** — so it goes in the `Profile-Title` and on the page.

## 9.2 The hosted status page `/p/{token}` — rebuilt

The current `/status/{username}` page (handler `:304`, template `:4180+`) has three problems: the daily
bar is fed by `daily_used_gb`, which **never accrues on WS** (limitation #6), so it sits permanently at
zero; the URL leaks the username; and it says nothing about devices.

Rebuilt, Persian/RTL, matching the app's visual language, in this order:

| Block | Content |
|---|---|
| **حجم** | used / total, exact, unit that fits (MB under 1 GB); a bar; and **remaining**, which is what the user actually wants |
| **زمان** | absolute expiry date **and** time remaining; for an unstarted `on_first_connect` user, «با اولین اتصال شروع می‌شود، سپس N روز» |
| **دستگاه‌ها** | `۲ از ۳ دستگاه` — registered against `device_limit`, plus concurrent-now against `conn_limit`; each device's client hint and last-seen |
| **وضعیت** | active / disabled / expired / quota exceeded, **in words, with the reason** |
| **کانفیگ** | copy sub link, QR, per-node config list, "add to app" deep link |

Reset policy is stated in words («هر ماه صفر می‌شود»), never implied by a bar that silently refills.

---

# 10. API contract

Base `/{apiRoute}/v1/…` where `apiRoute` is a per-install random path segment in `settings` (the idea
copied from Nahan). An unauthenticated prober does not even find the API surface — everything else
falls through to the nginx camouflage page.

## 10.1 Auth

- **Provisioning:** deployer generates a 32-byte secret, uploads `sha256(secret)` as `secret_text`.
  First `POST /v1/auth/bootstrap` with the raw secret exchanges it for a row in `api_keys`, and burns
  the bootstrap.
- **Steady state:** `Authorization: Bearer cs_<keyId>_<secret>`. Look up by indexed `keyId` (1 row read),
  constant-time compare `sha256(secret)`, check `revoked_at`/`expires_at`.
- **Rotation:** issue new → app stores it → verify with `GET /v1/health` → **then** revoke the old.
  Both audited.
- **`last_used_at` written at most once per hour per key**, never per request (write budget).
- **The password-hash-as-cookie scheme is not carried forward.** No `panel_session`, no `/api/login`,
  no password field anywhere in the Studio UI. Reviewer's grep: `panel_session`, `getAdminHash`,
  `ADMIN_PASSWORD` must not appear under `worker-src/studio/control-plane/` or `data/studio/`.

## 10.2 Endpoints

```
GET    /v1/health         → {version, schema_version, migration_error, do_mode:'strict'|'soft',
                             d1_ok, rows_read_sample, capabilities[]}
POST   /v1/auth/bootstrap ; GET/POST/DELETE /v1/auth/keys[/{id}]

GET    /v1/users?cursor=&limit=50&q=&status=&tag=&group=&sort=created_at:desc
       → {items[], next_cursor, total_hint}
       KEYSET pagination on (created_at, id). NOT OFFSET — OFFSET reads and discards.
GET    /v1/users?since=<updated_at>&cursor=&limit=200        -- §A.3 incremental sync into the index.
       Returns changed rows AND tombstones (deleted_at set), ordered by updated_at.
POST   /v1/users ; POST /v1/users:batch {op, items[]}
GET/PATCH/DELETE /v1/users/{id}                 -- id is the ULID → rename is a PATCH
POST   /v1/users/{id}:reset-quota | :extend | :rotate-credentials | :disable | :enable
POST   /v1/users/{id}:renew {plan_id?, add_days?, add_bytes?, mode:'extend'|'restart'}   -- §E

GET/POST /v1/plans ; GET/PATCH/DELETE /v1/plans/{id}       -- §D. No price field, ever (D6)

GET    /v1/users/{id}/configs ; POST /v1/configs ; POST /v1/configs:batch
GET/PATCH/DELETE /v1/configs/{id} ; POST /v1/configs/{id}:rotate | :clone

GET/POST /v1/templates ; GET/PATCH/DELETE /v1/templates/{id}
GET/POST /v1/nodes ; GET/PATCH/DELETE /v1/nodes/{id} ; POST /v1/nodes/{id}:health
GET/POST /v1/groups

GET    /v1/users/{id}/devices
POST   /v1/users/{id}/devices/{hash}:block | :unblock | :forget
GET    /v1/users/{id}/sessions?cursor=
POST   /v1/users/{id}/sessions:kill             -- DO revokes leases; data plane drops next tick

GET    /v1/subscriptions?user_id= ; POST /v1/subscriptions/{id}:rotate | :revoke

GET    /v1/dashboard                            -- ONE call, ONE db.batch, all tiles
GET    /v1/analytics/traffic?range=7d&granularity=hour|day
GET    /v1/analytics/breakdown?by=node|protocol|transport&range=7d
GET    /v1/analytics/top-users?range=7d&limit=10
GET    /v1/activity?cursor=&kind=&severity=
GET    /v1/audit?cursor=&actor=&action=&target_type=
GET    /v1/backup ; POST /v1/restore {mode:'merge'|'replace', dry_run:bool}
GET/POST /v1/settings

# public, unauthenticated, token-gated:
GET    /s/{token}?format=base64|raw|json|clash|singbox
GET    /p/{token}
```

`GET /v1/dashboard` is deliberately **one** endpoint backed by one `db.batch([...])` of ~8 indexed
aggregates against `counters`, `usage_hourly` and `usage_daily`. Nine round trips from a phone on an
Iranian mobile network is a bad dashboard; one is a good one.

Legacy `/api/*` and `/sub/{username}`, `/feed/*`, `/status/*` **stay working** through build 6 and are
removed in build 7 (§15). Old subscription URLs 301 to the token form once the user has a token.

## 10.3 Error envelope

`{error:{code, message, detail?}}` with **stable machine codes** — `quota_exceeded`, `device_limit`,
`conn_limit`, `username_taken`, `d1_write_budget`, `do_unavailable`, `schema_stale`. **The Android layer
maps `code`, never `message`** — messages are localised in `values-fa/strings.xml`.

## 10.4 Backup format

```json
{ "format": "configstudio.backup", "version": 1, "created_at": 0,
  "worker_build": 6, "schema_version": 6,
  "secrets": "sealed",
  "kdf": {"alg":"PBKDF2-SHA256","iter":210000,"salt":"…"},
  "tables": { "users":[…], "configs":[…], "templates":[…], "nodes":[…], "groups":[…],
              "subscriptions":[…], "settings":[…] } }
```

Restore refuses an unknown `version` and a `schema_version` newer than its own — with a real message,
not a 500. Passphrase entered in the app; **the worker never sees it and never stores it**. Declining a
passphrase gives `secrets: "omitted"` and configs restore with freshly generated credentials, announced
up front.

---

# 11. Security and privacy model

| Concern | Design |
|---|---|
| **Panel auth** | Bearer tokens, hashed at rest, revocable, expiring. §10.1 |
| **Admin password** | Does not exist in Config Studio. See R3 for the ordering that removes it safely from the legacy surface |
| **Subscription identity** | 32-char random `token`, not the username. Rotatable and revocable; old tokens 404 immediately |
| **Device identity** | `device_hash = sha256(salt ‖ client_hint ‖ ip_prefix)`. **A heuristic, not an identity** — two phones behind one NAT on the same client version can collide, and **the UI must say so** |
| **Client IPs** | Never stored raw. `ip_hash = sha256(salt ‖ ip)` truncated to 16 bytes, salt in `settings`. IP-limit enforcement without holding a subscriber-IP database on someone else's Cloudflare account |
| **Transport secrecy** | Protocol strings stay obfuscated, applied by the build step not by hand (R2). The camouflage nginx page stays. The API hides behind a random route segment |
| **Backup secrets** | The export *is* the keys. Sealed with a passphrase the worker never sees; offered as a file with an explicit warning; never posted anywhere |
| **Audit** | Every mutating call writes an `audit_log` row with actor = key label and **both** before/after JSON |
| **Losing the phone** | Not solved by an export file — solved by **re-bootstrap** (§B.2.3). Possession of the Cloudflare API token already implies total control of the worker, so re-arming a bootstrap secret grants nothing new; it is not a backdoor and should not be dressed up as one. Each key is labelled with its device, `GET /v1/auth/keys` lists them, and revoking a lost or stolen phone is one tap. **Every re-bootstrap is audited** — a key the operator did not create is the only signal they will get that their Cloudflare token leaked |
| **The emergency proxy** *(known, accepted, no work planned)* | `EmergencyInterceptor` (`emergency/EmergencyInterceptor.kt:15-18`) rewrites every `*.workers.dev` and `api.cloudflare.com` request through `mlm-proxy.vercel.app`. While it is on, **the bearer token and every user list transit a third party.** It defaults off (`EmergencyStateManager.kt:13`) and only a manual toggle turns it on, and under D9 it is not needed for delivery. Recorded so it is a decision rather than a discovery |

---

# 12. Android architecture

## 12.1 Package layout

```
data/studio/
  StudioInstallation.kt  StudioDeployer.kt (~380)  StudioStore.kt (~320)
  api/  StudioApi.kt  StudioHttpApi.kt (~420)  StudioMappers.kt (~260)
        dto/ UserDto ConfigDto NodeDto DashboardDto AnalyticsDto
             ActivityDto AuditDto DeviceDto SessionDto SubscriptionDto PageDto
  domain/  §6
  config/  ConfigBuilder.kt  TemplateRenderer.kt
  repo/    StudioRepository.kt  StudioRepositoryImpl.kt (~450)

ui/configstudio/
  ConfigStudioHost.kt
  wizard/ StudioSetupWizard.kt (~700)  StudioCloudStep.kt
  DashboardScreen UsersScreen UserDetailScreen ConfigsScreen ConfigBuilderWizard
  TemplatesScreen NodesScreen NodeDetailScreen AnalyticsScreen
  ActivityScreen AuditScreen SettingsScreen SubscriptionScreen
  StudioCoach.kt
  parts/ StatTile TrafficChart UserRow QuotaBar PolicyRows
         TransportPicker SecurityPicker CapacityMeter StudioKit
```

**The rule that makes `StudioApi` worth having:** nothing under `ui/configstudio/` may import from
`data.studio.api.dto`. The repository returns domain types only.

**HTTP client:** one `OkHttpClient` built once inside `StudioHttpApi` with 15/20/20 s timeouts **and**
`EmergencyInterceptor(context)` — matching `CloudManager.kt:35-40`. No class builds its own.

## 12.2 State — a `StudioStore` singleton, not ViewModels

**Recommendation: no ViewModels.** Follow `CloudManager`/`NodeManager`.

1. **Consistency.** The app has zero ViewModels across ~50 screens. One feature with a different
   lifecycle model is a permanent seam.
2. **The tab-parking architecture already does the ViewModel's main job.** `AppScreen.kt:281-290` keeps
   every visited tab in composition parked at `x = 10000.dp`, so a half-typed form and an in-flight
   request already survive navigation.
3. **Reachability.** `StudioDeployer` (wizard), Repair (settings) and the dashboard need the same
   installation state. A singleton is reachable from all three; a ViewModel is not.
4. ViewModels would mean introducing a DI story for one feature.

**What would flip this:** wanting rotation survival or process-death restore — but then adopt them
app-wide in a separate pass.

**The one thing that must differ from `CloudManager`** (F6): make the state **immutable** so
`distinctUntilChanged` works and no screen needs a `justDeployed` flag.

```kotlin
data class StudioState(
    val installation: StudioInstallation? = null,
    val users: PagedList<StudioUser> = PagedList(),
    val dashboard: DashboardSnapshot? = null,
    val nodes: List<StudioNode> = emptyList(),
    val templates: List<ConfigTemplate> = emptyList(),
    val busy: Set<String> = emptySet(),
    val lastError: StudioError? = null,
)
// every mutation: _state.update { copy(...) }
```

Screens use `StudioStore.get(ctx).state.collectAsState()` — the Settings/SubLink pattern, **not**
`CloudTab.kt:59-60`'s shadow-into-local-mutable-state.

Cloudflare accounts come from `CloudManager(context).accountsFlow.collectAsState()` — free, because it
is a true process singleton (D2).

## 12.3 Persistence

`StudioInstallation` lives on `CloudAccount` as one new field block, in the existing style:
`studioApiRoute`, `studioKeyId`, `studioApiSecret`, `studioDoTag`, `studioStatus`, `studioVersion`,
and — new in revision 2 — `studioAdopted` (whether this device installed it or found it, §B) and
`studioKeyLabel` (which device this key belongs to, §B.2.3).
Reuses `mlmWorkerUrl` and `mlmDbId` — same worker, same database (D3).

> **The three-place rule.** `models/CloudAccount.kt`, `CloudManager.loadAccounts()` (`:58-130`) **and**
> `CloudManager.saveAccounts()` (`:161-217`). A field added in two of three **vanishes on app restart
> with no error** — the worst kind of bug to find during a demo.

Everything else is **server-authoritative**, and — **revised in revision 3** — users, plans, nodes and
templates are additionally held in a **local Room index** (§A.3). Draft 1 said "do not build a local
mirror", and that was correct for one account; an uncapped fleet (D7) makes read-time fan-out
impossible, so the mirror is now forced. What survives from that objection is the rule that answers it:
**stale for browsing, fresh for acting** — every mutation re-reads its one user from its own shard
before writing (§A.3, R17).

Sessions, devices, activity and audit are **never** indexed: live from the one shard that owns them.
TTL: dashboard snapshot 30 s per shard, analytics daily, the user index by its rolling sync cycle.

---

# 13. Navigation map

**One tab id: `"configstudio"`.**

1. `HomeDestinations.GRID_DEFAULT` — `HomeApp("configstudio", R.string.home_config_studio, …)`, not modal
2. `AppScreen.SELF_HEADED` (`:45-49`) — add `"configstudio"`; it draws its own `IosScreen` bars
3. Tab-host block with the `visitedTabs` + `offset(x = 10000.dp)` parking pattern, **no top/bottom
   padding** — the screen consumes `LocalContentTopInset` itself
4. Because a parked composable reads its arguments only on first composition, "open Config Studio at
   user X" uses the one-shot flow idiom from `ui/NodesFocus.kt:9-23` — a `StudioFocus` object with a
   `MutableSharedFlow<StudioRoute>` collected inside `ConfigStudioHost`

**Config Studio does NOT appear as a row in the Cloud tab.** A row under "MLM / Nahan / EDG" would leak
exactly the association D1 forbids. Cloud is the *plumbing* screen; Config Studio is a *product*. The
only place they touch is the shared `CloudManager` singleton.

```
ConfigStudioHost(onExit)              ← modelled on ui/tunnel/TransportHost.kt:25-125
│
├─ no installation → StudioSetupWizard (owns the whole screen)
│
└─ StudioShell   stack: mutableStateListOf<StudioRoute>(), ONE BackHandler at the host
   ├─ Dashboard        (root, IosScreen(largeTitle))
   ├─ Users            → UserDetail(id) → { Devices, Sessions, Configs, Policy, Subscription }
   ├─ Configs          → ConfigDetail(id) → ConfigShare
   │                   → ConfigBuilderWizard   (own BackHandler while active, full screen)
   ├─ Templates        → TemplateDetail(id)
   ├─ Nodes            → NodeDetail(id) → NodeGroups
   ├─ Analytics        → Breakdown(dimension)
   ├─ Activity  ┐ two tabs, one screen, segmented control
   │   Audit    ┘ → detail(id)
   └─ Settings         → { Endpoints, Defaults, Policies, ApiKeys, Backup,
                           Health/Repair, Upgrade, Uninstall }
```

**Two-pane at ≥720dp**, the lightweight form:

```kotlin
val twoPane = LocalConfiguration.current.screenWidthDp >= 720
val detail: (@Composable () -> Unit)? = when (val r = stack.lastOrNull()) {
    is StudioRoute.UserDetail -> { { UserDetailScreen(r.id, onBack = ::pop) } }
    is StudioRoute.NodeDetail -> { { NodeDetailScreen(r.id, onBack = ::pop) } }
    else -> null
}
if (detail != null && !twoPane) { detail(); return }
StudioShell(section = section, onNavigate = ::push, detail = detail)
```

`StudioShell` renders `Row { list.width(380.dp); Box(1.dp, Ios.Separator); Box(weight(1f)) { detail?.invoke() ?: Placeholder() } }`
— a plain `Row`, so RTL mirroring is free. Only master/detail pairs go in `detail`; both wizards take
the full screen.

### Non-negotiable UI constraints

- **`IosScreen(scrollable = false)`** on every screen whose body holds a `LazyColumn` — Users, Configs,
  Nodes, Activity, Audit, Sessions. Otherwise infinite height and a crash.
- **`CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr)` around every ASCII
  payload** — URIs, UUIDs, tokens, slugs, JSON, API keys. Reference: `GstSetupWizard.kt:290`, `:431`.
  In a tool that hands out configs this is not cosmetic: an RTL-mangled `vless://` string is an unusable product.
- Persian digits via `faCount` / `faDigits`. No sixth copy.
- Alerts via `IosAlert` / `IosPrompt`, never Material `AlertDialog`.
- Every string in **both** `values/strings.xml` and `values-fa/strings.xml` — 3153 entries each, in
  lockstep. Budget ~180 new strings for Phase 1–2, ~450 total.
- **No commerce vocabulary, enforced by grep (D6).** Neither locale may contain «فروش», «فروشنده»,
  «مشتری», «خرید», «قیمت», «تومان», nor *sell / seller / customer / buyer / price / purchase*. The
  operator is «مدیر» or addressed in the second person; the person holding a subscription is «کاربر»;
  a set of terms is «بسته». The same grep runs over `worker-src/studio/**` and the schema — **there is
  no `price` column in any phase** (§D).
- **TV/D-pad is out of scope** — no feature screen in the app is D-pad navigable today. Keep the tile
  reachable; note it for Phase 5.

---

# 14. Setup wizard, Config Builder, user management, scan-combine, assistant

## 14.1 The setup wizard

Base: `FreeConfigWizard.kt`'s enum-step model with async states. Borrow `StepDots`, the single
`BackHandler`, `StepHeader`/`WizardField`/`WizardPrimary`/`InfoCard` from `GstSetupWizard.kt` — these are
private there, so extract them once into `parts/StudioKit.kt`.

| Step | What happens |
|---|---|
| **1 · حساب کلادفلر** | If `accounts.isNotEmpty()`: show them with `CloudflareAccountCard`, pick one. Else **the add form inline** (F1) — **no redirect to «ابری»**. `CloudManager.addAccount(token, email)` — watch the argument order. Failure → `credentialAdvice()` + inline `CloudTroubleshootScreen` |
| **2 · اعتبارسنجی** | `probeCredential` → `probeAccountStatus` → workers.dev subdomain (offer `createSubdomain`) → D1 quota headroom (10/account) → **DO availability probe** |
| **3 · نصب موتور** | `StudioDeployer.deploy()` with progress → bootstrap auth → migrate → verify `/v1/health` → deep health. Failure offers **repair** and **reinstall**, not a toast |
| **4 · نقطهٔ اتصال** | workers.dev host, optional custom domain, ports, SNI, fingerprint, clean-IP list, relay IP, fragment → creates the first `Node` row |
| **5 · پیش‌فرض‌ها + خلاصه** | Default template (protocol, transport, security, quota, expiry, policies) → summary showing the engine URL, database, **capabilities actually detected**, and one primary action: «اولین کاربر را بساز» |

Re-enterable from Settings › Engine for repair/upgrade/reinstall.

## 14.2 Config Builder

A wizard, not a form: **who** → **template** → **protocol** → **transport** (only what `capabilities`
reports; XHTTP carries the §4.1 warning; TCP greyed out with a reason) → **security** → **endpoint** →
**policy** → **preview** → **done**.

Preview renders the real URI and real Xray JSON from `ConfigBuilder`, LTR-pinned. Done hands off with
the existing block: `NodeQrCard` + copy + share, plus "save into this app" via `NodeManager.addNodes`.
Bulk creation is the same wizard with a count and a name pattern on step one.

## 14.3 User management

The list **is** the product. `LazyColumn` with a sticky search field, filter chips (status, tag, group,
expiring soon), a sort menu (name, created, expiry, usage, last seen), and a selection mode whose
toolbar lives in `IosScreen`'s `trailing` slot. Each row: status dot, name, usage bar, days left, device
count. Bulk ops post to `/v1/users:batch`.

Detail is a settings-shaped page: identity, policy, usage, devices, sessions, configs, subscription,
danger zone. Every edit is a `PATCH` and writes an audit row.

## 14.4 «ترکیب با اسکن» — scanner integration

Today: a `Button` buried at `ScannerTab.kt:1069-1112` that overwrites the user's whole `ips` field and
reports by toast. The professional version lives in Config Studio:

- Start a scan **from** a user or a node, scoped to that config's ports and SNI
- Show candidates with measured latency; the operator **chooses** which to keep — not a blind overwrite
- **Merge or replace**, previewing the resulting node list and how many configs the subscription will
  fan out to
- Write once through `PATCH /v1/nodes/{id}`, audited
- **"Apply to a whole group/tag"** — the operation an operator actually wants
- Scheduled re-scan is later, but the model must not preclude it

The existing button is removed when this ships.

## 14.5 The assistant — «دستیار»

Modelled on `CloudCoach` (`ui/CloudCoach.kt`), which is right precisely because it **does the work**
rather than narrating: it runs the install and the fetch itself, and the one step it cannot do (typing
someone's own credentials) it navigates to and waits.

`StudioCoach` takes the same shape — singleton, `Step` enum, a `Request` channel the screen consumes,
`work`/`workProgress`/`failure` flows, persisted, **offered once ever** — and covers:

- **first run:** walk the 5 wizard steps, doing 2–5 itself
- **after install:** "no users yet" → create the first, with a sane default policy
- **ongoing**, as a `CoachBar` on the Dashboard when a condition is *observably* true:
  - users expiring within 3 days → offer bulk extend
  - users over quota → offer reset or top-up
  - engine stale (`PanelBuild.isStale`) → offer upgrade
  - health check failing → run repair
  - no clean IPs and the scanner has fresh results → offer the combine flow (§14.4)
  - **DO or D1 budget approaching the ceiling** → explain, offer the retention sweep, show the capacity meter

Every branch keyed to a condition the app can actually observe — the rule `CloudCoach.diagnose` already
follows, and the reason it is useful rather than decorative.

---

# 15. Migration strategy

| Step | What | Safety |
|---|---|---|
| **M1** | Build 6: versioned migrator, additive columns, dual-write GB↔bytes, `id` + `sub_token` backfilled for every existing user, `/{apiRoute}/v1/*` added, legacy `/api/*` untouched | A build-5 panel that never upgrades keeps working; a build-6 panel can roll back to 5 |
| **M2** | Legacy-auth hardening (R3) — must ship **before** M4 | The only step that can leak |
| **M3** | `PanelBuild.MLM = 6` → existing operators see "redeploy". Redeploy reuses `mlmDbId`, so users and traffic survive | The existing, proven mechanism |
| **M4** | Deployer stops shipping `DEBUG=1`; `ADMIN_PASSWORD` removed in favour of `secret_text` bootstrap | Only after health shows installs seeded |
| **M5** | Config Studio **adopts** an existing installation: an operator who already had the panel finds their users already there, under the new name. **No data migration at all** — same worker, same DB | This is the payoff of D3 |
| **M6** | Build 7: drop `limit_gb`/`used_gb`/`daily_*`; delete the 2173-line inline HTML panel; remove the legacy `/api/*` surface, `MlmUsersScreen`, `MlmSettingsScreen`, and the Cloud row | Only once Config Studio is the only UI |

**The «ابری» row.** Because of D3 the panel row and Config Studio are two faces of one deployment. Until
M6 both are visible, which risks the user seeing the old name — a direct conflict with D1. **Recommended:
at M3 the row becomes a pointer** — its title changes to «کانفیگ استدیو» and tapping it opens the Config
Studio tab. That satisfies D1 without a second engine. See §18-Q3.

---

# Revision 2 — the structural additions

> Sections A–E are new in revision 2 and come from decisions D6–D9. §F is the correction ledger
> against draft 1. Everything numbered §1–§15 above stands except where §F says otherwise.

---

# A. The fleet — many Cloudflare accounts (D7)

Draft 1 asked this as §18-Q4 and warned that retrofitting it "touches every screen". It has now been
answered: **the operator will run out of one free account and connect more.** So it is designed in
from Phase 1, and the cost of that is almost entirely in one place — the repository.

## A.1 Terminology, which must be unambiguous in the UI

Two different things could both be called "server" and the UI must never blur them:

| Term | Persian | What it is |
|---|---|---|
| **Installation / shard** | «حساب» | one Cloudflare account carrying one worker + one D1. `StudioInstallation` |
| **Node** | «نقطهٔ اتصال» | an endpoint *inside* one installation. `StudioNode` (§6) |

An installation holds many nodes. Nodes never span installations.

## A.2 Model

```kotlin
data class StudioFleet(
    val installations: List<StudioInstallation>,   // one per CloudAccount that carries Studio
    val defaultId: String?,                        // where a new user goes unless told otherwise
)
```

`StudioUser` gains `val installationId: String` — **client-side only**. The worker is never told that
other shards exist, never calls another shard, and needs no code for any of this. That is the property
that keeps the whole feature cheap: **shards are completely independent, and every write goes to
exactly one of them.** There is no distributed transaction anywhere in this design.

## A.3 Reads — a local index, because N is unbounded

**This reverses a rule from draft 1**, and the reversal is forced by D7 being uncapped. Draft 1's §12.3
said: *"Do not build a local mirror: a stale local user list that disagrees with the worker is the
single worst failure mode."* That was right when N = 1. It is impossible when N is unbounded — a
dashboard cannot be 50 round trips, and a user list cannot page by asking 50 workers for one screen of
rows.

Revision 2 proposed a k-way merge across per-shard cursors. **That is withdrawn**: it is O(N) requests
per page, which is exactly the thing that fails here.

### What is indexed locally, and what is not

| Data | Where reads come from | Why |
|---|---|---|
| **Users** | **local index** | large, browsed constantly, changes slowly, needs search/sort/filter |
| **Plans, Nodes, Templates** | **local index** | small, read on every screen |
| Dashboard counters | per-shard snapshot cached with its timestamp, **summed locally** | one small object per shard |
| Analytics | per-shard snapshot, refreshed daily, summed locally | same shape, coarser cadence |
| Sessions, devices, a single user's live state | **live, from that one shard** | one request, and it must be current |
| **Activity / Audit** | **live, and never merged across the fleet** | high volume, rarely read. A global merged feed across unbounded N is not a thing that can work — scope it to one shard or one user, and say so in the UI |

### The index

`StudioIndex` — **Room**, which would be the app's first database. Everything else persists as JSON in
SharedPreferences (`CloudManager.kt:42`) or files (`NodeManager`, `GroupManager`,
`SubscriptionManager`), and `CloudManager.kt:44` already carries the note *"later persist to
SharedPreferences or Room"*.

JSON is not an option at this size: 50 accounts × 300 users is 15,000 rows, rewritten whole on every
change, with no index behind a search box. Room is adopted **because unbounded N makes read-time
fan-out impossible**, not for consistency's sake — and that is a narrower and better reason than the one
draft 1 rejected ViewModels for (§12.2), which still stands.

```kotlin
@Entity(tableName = "idx_users",
        indices = [Index("installationId"), Index("username"), Index("expiresAt"),
                   Index(value = ["createdAt", "id"]), Index("syncedAt")])
data class IndexedUser(
    @PrimaryKey val rowId: String,      // installationId + ":" + id — unique across the fleet
    val installationId: String, val id: String,
    val username: String, val status: String,
    val expiresAt: Long?, val quotaBytes: Long?, val usedBytes: Long,
    val planId: String?, val deviceCount: Int, val lastActiveAt: Long?,
    val updatedAt: Long,                // the shard's value — the sync watermark
    val syncedAt: Long,                 // when THIS row was last pulled
)
```

Reads become one indexed SQL query — **O(1) in N**. Search, sort, filter and paging are local and
instant, which is also the only way they can be correct across shards at all.

### Sync — incremental, staggered, per shard

```
GET /v1/users?since=<updated_at watermark>&cursor=&limit=200
```

`since` and an `ix_users_updated` index are **new API and schema surface** (added to §10.2 and §7.4).
Each shard is pulled from its own watermark, so a refresh costs **one request per shard per cycle**, not
per screen, and it returns only what changed.

- **Staggered, never all at once.** A refresh walks shards on a rolling schedule; the visible shard and
  any shard the operator just wrote to are pulled first.
- **Deletes** need tombstones or the index keeps ghosts: `deleted_at` on `users`, returned by `since`
  and applied as a local delete. A hard delete without a tombstone is invisible to every other device.
- **Failure is per shard and visible** — `lastSyncAt` and `syncError` per installation. A shard that has
  not synced shows its age; it never silently disappears from the list.

### The rule that makes a stale index safe

> **Stale for browsing. Fresh for acting.**

Every mutation — edit, renew, disable, rotate, delete — **re-reads that one user from its own shard
first** (`GET /v1/users/{id}`, one request, tens of milliseconds) and writes against what comes back. So
the index can be minutes old for browsing and the write is never based on stale data. If the re-read
disagrees with what the operator was shown, the confirmation sheet shows the difference before
committing.

This is what keeps draft 1's objection answered rather than ignored: the failure it warned about was
*acting* on a stale list, not *looking* at one. R17.

## A.4 Placement — which shard a new user lands on

**Auto-placement is the default, not an option.** Nobody picks by hand from fifty accounts, so the
uncapped fleet makes the automatic path the primary one:

1. **Auto** — the least-loaded healthy shard by the capacity meter (§4.1, §8.2), skipping any that is
   `UNREACHABLE`, `STALE` beyond the compatible range, over its budget, or whose index has not synced
   recently enough to trust its load figure. **This is what runs unless the operator opens the step.**
2. **Manual** — the Config Builder's placement step, with the shards sorted by free capacity.

The choice is recorded on the user and shown as a chip on their row. Auto-placement is shown before it
is committed, never after — placement decides which link the person gets, so it is never silent.

## A.5 Username uniqueness across shards — an honest limitation

The worker enforces `UNIQUE(username)` **per D1 only**; there is no cross-shard constraint and adding
one would require the shards to talk to each other, which A.2 exists to avoid.

- The app checks **the local index** before create — instant, and O(1) in N, which is the only version
  of this check that survives an uncapped fleet. The target shard's `UNIQUE(username)` is the actual
  authority and returns `username_taken` if the index was stale.
- **This is a client-side guarantee.** Two devices creating the same name on two shards in the same
  instant can both succeed. Recorded here rather than solved; it is a two-operator scenario and the
  audit log shows both.
- Rejected: a per-shard username prefix. It leaks the fleet's internal shape into the name a person
  sees, and it is irreversible once a link is handed out.

## A.6 Subscriptions do not span shards

A user's `/s/{token}` is served by **their own shard's** worker.

**Rejected: an aggregator shard** that proxies the others. It re-centralises the failure that D7 exists
to spread, adds a hop to every fetch, and spends the aggregator's request budget on traffic for users it
does not host — which is the exact budget multi-account was adopted to relieve.

The answer to a shard that must be abandoned (account suspended, quota permanently exhausted) is
**«انتقال کاربر»** — recreate on the target shard with the same username, policy and a carried-over
usage opening balance, issue a new subscription token, and mark the old user `migrated` so the old link
can 301 for a grace period. Phase 3, and it is the only cross-shard operation in the product.

## A.7 What the fleet buys

Every ceiling in §4 is **per Cloudflare account**, so N accounts multiply all of them:

```
per account/day :  100k Worker requests · 100k D1 row writes · 100k DO requests · 100k DO row writes
                   5M D1 row reads (account-wide) · 10 D1 databases
```

The capacity meter (§4.1) therefore reports **per shard and in total**, and it is what auto-placement
reads. This is the feature that turns "my panel died at 3pm" into "shard 2 is at 78%".

## A.8 Screens the fleet touches

| Screen | Change |
|---|---|
| Dashboard | aggregate tiles + a per-shard capacity strip |
| Users | shard chip on each row; filter by shard; the error strip from A.3 |
| UserDetail | which shard, and «انتقال به حساب دیگر» (Phase 3) |
| Config Builder | a placement step |
| Settings › «حساب‌ها» | **new screen** — the fleet: add, health, capacity, engine build, upgrade, remove, per shard, plus «بروزرسانی همه». **Its own list must page and search**, because it is uncapped like everything else, and it needs **bulk add** (paste many tokens, queue the deploys, one progress list) — running a five-step wizard fifty times is not a thing anyone will do |
| Setup wizard | unchanged for the first account; «افزودن حساب» re-enters steps 1–3 only |

**Phase split:** the model, the index, the sync loop and the fresh-for-acting rule ship in **Phase 1
with N = 1** — the index is the read path, so building the direct-read version first and replacing it
later is the retrofit trap all over again. The «حساب‌ها» screen, the second account, bulk add,
auto-placement and the staleness surfaces ship in **Phase 2**.

---

## A.9 Operating an uncapped fleet

Things that are fine at N = 3 and are not fine at N = 50, each with what the design does about it:

| At scale | Consequence | Design |
|---|---|---|
| **Sync cost** | one request per shard per cycle. At N = 50 and a 5-minute cycle that is 14,400 requests/day **from the phone** | Rolling schedule, not a sweep: the visible shard and any shard just written to are pulled immediately; the rest are pulled on a long cadence that lengthens with N. Idle shards back off |
| **Cloudflare API limits** | 1,200 requests / 5 min **per token**, and each account has its own token | Per-token limits are never the binding constraint. The phone's own request volume is, which is what the rolling schedule bounds |
| **Health polling** | polling 50 workers for a health dot is 50 requests | Health is a by-product of the sync that already happened. No separate poll |
| **Onboarding** | adding 50 accounts through a 5-step wizard | Bulk add (§A.8), with the discovery scan (§B.2.2) run per account so accounts that already have an installation are adopted rather than deployed |
| **Naming** | 50 rows called "Cloudflare account" | Each installation gets an operator-set label and the account email; the fleet list sorts by free capacity |
| **A dead shard** | its users are unreachable but still in the index | The index keeps them with their shard's `syncError` and age; they are visible, marked, and «انتقال کاربر» (§A.6) is the fix. **They are never deleted locally because a shard failed to answer** |
| **Audit across the fleet** | a merged feed over 50 shards | Not offered. Audit is scoped to one shard or one user, and the UI says so rather than showing a feed that is quietly incomplete (§A.3) |

---

# B. Recovering an installation instead of deploying a second one (D8)

## B.1 The bug, exactly

```kotlin
// engines/mlm/MlmDeployer.kt:217
val workerName = PanelBuild.scriptName(account, "MLM")        // reads it out of account.mlmWorkerUrl
    ?: (AntiDpi.generateSafeWorkerName() + "-mlm")            // "<prefix>-<6 hex>" — random
```

`PanelBuild.scriptName` (`PanelBuild.kt:91-96`) parses the name out of the **stored URL**. On a phone
that has never deployed, `mlmWorkerUrl` is null, so the fallback mints a **random** name and Cloudflare
happily creates a **second worker** beside the first.

It is not data loss — `MlmDeployer.kt:91-126` already searches the account's existing D1 databases by
name and reuses the one it finds, so both workers share one database and serve the same users. What it
is instead: an **orphaned worker**, still answering on the old URL with the old script, outside
`PanelBuild.isStale`, invisible to the app, never upgraded again. Every subscription link handed out
from the first phone points at it.

## B.2 The fix, in three layers

### B.2.1 A deterministic script name

```kotlin
// data/studio/StudioNaming.kt
fun studioScriptName(accountId: String): String {
    val h = sha256(accountId + STUDIO_NAME_SALT)               // salt is a build constant
    val prefix = AntiDpi.SAFE_PREFIXES[(h[0].toInt() and 0xFF) % AntiDpi.SAFE_PREFIXES.size]
    return "$prefix-${h.hex(1, 5)}"                            // "<safe-prefix>-<8 hex>"
}
```

Reproducible on any device from the Cloudflare account alone, and still opaque from outside — which was
`AntiDpi`'s actual goal. The randomness existed to make the name unguessable, not unreproducible;
unreproducibility was an accident, and limitation #16 is what it cost.

**The `-mlm` suffix is dropped** — see §18-Q7 for what that means for installs that already carry it.

### B.2.2 A discovery scan, for installations that already exist

Both Cloudflare calls this needs are already written:

1. `GET /accounts/{id}/workers/scripts` — **`CloudManager.kt:524`**
2. `GET /accounts/{id}/d1/database?per_page=100` — **`CloudManager.kt:701`**

```
list scripts on the account
  → shortlist: the deterministic name, plus anything ending in "-mlm" (legacy)
  → resolve the workers.dev subdomain once
  → PROBE each candidate:  GET /{apiRoute}/v1/health   → a Studio install, build ≥ 6
                           GET /                        → the camouflage page signature (legacy)
  → a positive probe is ADOPTED: url, dbId (from the script's bindings), build → CloudAccount
  → confirm the database against the D1 list
```

**Identify positively, never by name alone (R15).** The same Cloudflare account also carries BPB, EDG,
Nahan, the DNS resolver, the relay and the pool worker. Adopting the wrong script and then redeploying
Studio over it destroys another engine.

### B.2.3 Re-bootstrap — how the new phone gets an API key

The bootstrap secret was burned by the first phone (§10.1), and Cloudflare will not read a
`secret_text` binding back. So:

- The app **redeploys the current build with a fresh `STUDIO_BOOTSTRAP_HASH`**, reusing `mlmDbId`, then
  `POST /v1/auth/bootstrap` → a new row in `api_keys`. A full multipart PUT is used rather than a
  bindings-only PATCH because the full upload is the path this repo has already proven; the PATCH is an
  optimisation for later.
- **This grants nothing new.** Whoever holds the Cloudflare API token can already replace the script
  entirely. Say that plainly in §11 rather than inventing ceremony around it.
- Each key is labelled with its device. `GET /v1/auth/keys` lists every device holding one, so revoking
  a lost phone is one tap — **which is the real answer to "what if I lose my phone", and it is better
  than an export file because it also works when the phone is stolen rather than wiped.**
- **Every re-bootstrap writes an audit row.** A key appearing that the operator did not create is the
  signal that their Cloudflare token has leaked, and it is the only signal they will get.

## B.3 What changes in the wizard

Step 1 (choose or add a Cloudflare account) is followed by a **«بررسی حساب»** pass — the B.2.2 scan —
**before step 3 ever offers to deploy**.

- **Something found** → step 3 becomes «اتصال به نصب موجود», showing URL, database, engine build and
  user count. There is no deploy button on this path. If the build is stale it offers «بروزرسانی» (§C).
- **Nothing found** → step 3 is the deploy it is today.

The same scan runs when a second account is added to the fleet, and on «تعمیر» from Settings.

---

# C. Updating the engine (D8, second half)

The operator must be able to take a worker fix that ships later. The mechanism exists — draft 1 called
`PanelBuild` "a clean upgrade mechanism" (§1.5) — but it was never made a first-class, visible thing,
and under D7 it becomes **per shard**.

- Bump `PanelBuild.MLM` → `isStale` goes true **for each installation independently** → Settings ›
  «حساب‌ها» shows «بروزرسانی موتور» on that row, plus «بروزرسانی همه».
- **The worker asset ships inside the APK.** An engine fix therefore reaches an operator only after they
  update the app. That is how every panel in this app already works, and the UI must say it rather than
  leave it to be discovered: «برای بروزرسانی موتور، اول برنامه را بروز کنید.»
- The update **is** a redeploy: same script name (B.2.1), same D1, so **the URL does not change and every
  subscription link keeps working.** Migrations (§7.1) run on the first API request after the upgrade;
  `GET /v1/health` reports `schema_version` and any `migration_error`.
- **Rollback window, stated explicitly.** Build 6 dual-writes GB ↔ bytes (§7.2), so 6 → 5 is survivable.
  **Build 7 is one-way** — it drops the legacy columns and the legacy `/api/*` surface. Say so in the
  upgrade confirmation for build 7, not in a code comment.
- **Version skew across the fleet is normal and permanent.** Shard A on build 6 while shard B is on 7 is
  an ordinary state, not an error. The repository must therefore branch on **each installation's
  `capabilities` set** (§6, from `/v1/health`) and never on a single global build number. Easy to get
  wrong, expensive to find — R13.

---

# D. «بسته» — the Plan entity (no money in it — D6)

The most-repeated action in this product is "give this person the same terms as the last twenty people".
Draft 1 could not express that: `ConfigTemplate` explicitly excludes quotas ("NO credentials, NO
quotas") and `SubscriptionPolicy` exists only per user. So the terms would be retyped every time and
could never be changed as a set.

```kotlin
// data/studio/domain/Plan.kt
data class Plan(
    val id: String, val name: String,            // «۳۰ گیگ / ۳۰ روزه»
    val quotaBytes: Long?, val durationDays: Int?,
    val expiryMode: ExpiryMode,                  // ABSOLUTE | ON_FIRST_CONNECT
    val dailyQuotaBytes: Long?, val reset: TrafficResetPolicy,
    val deviceLimit: Int?, val connLimit: Int?, val ipLimit: Int?,
    val templateId: String?,                     // which config shape it hands out
    val isDefault: Boolean, val archived: Boolean,
)
```

```sql
plans(id TEXT PRIMARY KEY, name TEXT, quota_bytes INTEGER, duration_days INTEGER,
      expiry_mode TEXT, daily_quota_bytes INTEGER, reset_policy TEXT,
      device_limit INTEGER, conn_limit INTEGER, ip_limit INTEGER,
      template_id TEXT, is_default INTEGER DEFAULT 0, archived INTEGER DEFAULT 0,
      created_at INTEGER, updated_at INTEGER)
```

**No `price`, no `currency`, no `paid_at` — anywhere, in any phase.** That is D6, and it is a schema
rule rather than a UI rule: a column named `price` eventually surfaces in a screen.

Rules that must be decided now because they are irreversible in the data:

- **Editing a plan does not retroactively change existing users.** A user's `SubscriptionPolicy` is
  copied from the plan at creation, not referenced. Silently changing the terms of people who already
  hold a link is the worst possible behaviour here. Bulk re-apply is a deliberate, audited action:
  «اعمال روی کاربران این بسته».
- **Plans are per shard, synced by the app.** They are small and change rarely, so the app writes the
  same plan to every installation. A plan missing on one shard shows «روی همهٔ حساب‌ها نیست» until
  synced — visible drift beats invisible drift. R14.
- Archiving a plan hides it from creation but never touches the users created from it.

---

# E. «تمدید» — renewal (D6-safe wording)

Expiry and quota existed in draft 1; **what happens when they run out did not.** This is the action the
operator performs most often after the first month.

```
POST /v1/users/{id}:renew
     { plan_id?, add_days?, add_bytes?, mode: 'extend' | 'restart' }
```

| mode | expiry | quota |
|---|---|---|
| `extend` | `max(now, expires_at) + days` — an early renewal is not punished | `quota_bytes += bytes` |
| `restart` | `now + days` | `used_bytes = 0`, `quota_bytes = bytes` |

```sql
renewals(id TEXT PRIMARY KEY, user_id TEXT, ts INTEGER, actor TEXT, plan_id TEXT,
         mode TEXT, days INTEGER, bytes INTEGER, before_json TEXT, after_json TEXT)
```

Low volume. `audit_log`'s 180 days is too short here — **keep renewals for the life of the user**; it is
the record the operator will be asked about.

**The subscription token does not change on renewal.** This is the single most important property of the
whole flow: the person holding the link does nothing, installs nothing, and is not contacted. A renewal
that requires re-sending a link is a renewal the operator will avoid doing.

Surfaces:

- **User detail** → «تمدید» → pick a plan, or enter days and volume → preview of the resulting expiry
  and quota → confirm. Audited.
- **Bulk** → select users → «تمدید» with one plan, via `POST /v1/users:batch`.
- **The `/p/{token}` page** for an expired or exhausted user says so in words and shows a contact line
  the operator configures in `settings.contact` — free text, so the operator writes whatever they want
  and **the app ships no commerce wording of its own** (D6).
- **Coach** (§14.5): «۵ کاربر تا ۳ روز دیگر منقضی می‌شوند» → bulk renew with a chosen plan; «۲ کاربر
  حجمشان تمام شده» → renew or reset.

---

# F. Correction ledger against draft 1

| # | Draft 1 said | Verified against the code | Consequence |
|---|---|---|---|
| F-a | "`daily_limit_gb` is inert on WS … enforcement never calls `isUserCapped` (`:1658`)" | **Draft 1 was right and revision 2 was wrong.** Revision 2 read "never calls `isUserCapped`" as "never enforces", and concluded quotas were decorative on every config the app hands out. Reading the WS path properly: it never *calls* the function but **inlines** `is_active`, `limit_gb` and `expiry_days`, at admission and on the heartbeat. Only `daily_limit_gb` is missing — which is exactly what draft 1 said | Limitation #6 restored to the narrower, correct claim. **Lifetime quota and expiry work on WS.** The daily quota does not, in both halves (no accrual, no test). Revision 2's phase argument survives but is smaller than it was stated to be |
| F-b | R10 lists "the CF script name" as something to keep clean | The deployer already appends `-mlm`, so it is **in the public hostname of every existing subscription URL** | A shipped D1 breach. Renaming breaks deployed links → §18-Q7 |
| F-c | §1.5: `PanelBuild` makes redeploy safe | True, **but only when the phone already knows the URL**. From a second device the name is random → limitation #16 | §B exists because of this |
| F-d | §18-Q4: "if the answer is thousands, the architecture needs sharding … much cheaper to know now" | Answered: **yes** | §A. Q4 closed |
| F-e | Phase 1 ≈ 3,900 lines / 26 files; total ≈ 10,000 | Measured against comparable screens: `CloudTab.kt` 2246, `IosSettings.kt` 1090, `NodesScreens.kt` 816, `FreeConfigWizard.kt` 772, **`MlmUsersScreen.kt` 619 for a plain list with no filtering, sorting, selection or bulk** — against 700 budgeted for Host + Dashboard + Users + UserDetail combined | Estimates were **2–3× low**, which matters because §18-Q2 was being answered against a wrong baseline. §16 re-costed |
| F-f | `/feed/*` listed among legacy surfaces to remove | Its only producers are the worker's own inline HTML panel (`:3482`, `:3486`, `:4319`). **No Android consumer** | Removing it at build 7 is safe. Recorded so M6 is not blocked by doubt |
| F-g | §11 security model | Silent about `EmergencyInterceptor` (`emergency/EmergencyInterceptor.kt:15-18`), which rewrites every `*.workers.dev` and `api.cloudflare.com` request through `mlm-proxy.vercel.app` | Under D9 this is not a delivery problem, but **while the operator has emergency mode on, the bearer token and every user list transit a third party**. `EmergencyStateManager.kt:13` defaults it off; only a manual toggle turns it on. Noted in §11; no work planned |
| F-k | F3 in Phase 0: "delete the triplicated URI builders (`:833`, `:3475`, `:4308`)" | `HTML_TEMPLATES` starts at `:2275`. Only `:833` is Worker code; the other two run in the **browser**, inside the panel's template literals, and obfuscate differently (`'vle' + 'ss://'` vs `atob`) | Not one refactor but two, and neither is a Phase 0 item: `:833` is replaced at build 6, the other two are deleted with the panel at build 7 |
| F-l | F5 (versioned migrator) in Phase 0 | Its content is `MIGRATIONS = [{ v: 6, … }]` — it is defined by build 6's schema | Moved into build 6. Introducing it earlier would change the schema path on live build-5 installs for no gain |
| F-i | §12.3: "Do not build a local mirror" | Correct at N = 1; impossible once D7 is uncapped (Q8) | **Reversed in revision 3.** A local Room index is now the read path (§A.3), with fresh-for-acting (R17) as the rule that answers the original objection rather than ignoring it |
| F-j | Revision 2's k-way merge across per-shard cursors (§A.3) | O(N) requests per page | **Withdrawn** before any of it was written. Replaced by the index |
| F-h | 3153 strings per locale, ~450 new, kept in lockstep by review (R11) | Both files verified at exactly 3153 `<string>` entries | ~900 hand-edited entries makes drift a certainty, not a risk. **A build-time check, not a review rule** — F7 |

---

# 16. Implementation phases

Sizes are new lines of Kotlin/JS, excluding strings. **Re-costed in revision 2 (F-e).** Draft 1's
numbers were measured against nothing; these are measured against the closest comparable screens
already in this repo — `MlmUsersScreen.kt` is **619 lines for a plain list with no filtering, sorting,
selection or bulk**, and draft 1 budgeted 700 for Host + Dashboard + Users + UserDetail together.

**One correction reshapes the phase order.** Draft 1 put all enforcement behind the Durable Object
and therefore behind its unknowns (R1). But the DO is only needed for **device, concurrent and IP
caps**. **Quota and expiry need no DO at all**, so they move into Phase 1 and the DO keeps its own
window.

Scoped accurately (F-a): lifetime quota and expiry **already work** on WS. What Phase 1 fixes is the
**daily** quota, which is inert in both halves — `daily_used_gb` is never accrued (E3) and
`daily_limit_gb` is never tested on the WS path (E2). Neither half alone changes anything, which is
probably why it has stayed broken. It is a small change, not a large one, and it is worth doing early
because the status page currently draws a daily bar that can never fill.

### Phase 0 — Foundations (invisible) · ~1,400
**F1 · F2 · F4 · F7 · F8.** Nothing user-visible; nothing can regress.

**F3 and F5 moved into build 6** (Phase 1) once it became clear neither can be done without it: F3's
three "copies" do not share an execution context and its replacement does not exist until
`configs.uri_template` does (see the note in §3), and F5's migrator is defined by the `v: 6` migration
it carries. Doing either early would mean editing a worker that is live on real accounts, with no test
harness, for no benefit before build 6.

**F6 has nothing to build in Phase 0** — it is a rule about how `StudioState` is written, and
`StudioState` arrives with the store in Phase 1.

**F8 must land first** within Phase 0: adoption decides whether an installation exists at all, so
everything that writes a `StudioInstallation` depends on it.

### Phase 1 — Install *or adopt*, and hand out one link that is really enforced · ~7,500
The smallest genuinely demoable product, on **one** account: the operator installs Studio — or the app
finds the installation they already have and adopts it — creates a user, and hands out a WebSocket
VLESS link whose quota and expiry are actually enforced.

| Area | ~Lines |
|---|---|
| Worker build 6 — migrator, `/v1/*`, bearer auth, subscription + `Subscription-Userinfo`, rebuilt `/p/{token}`, retention, `DEBUG` off, **`?since=` + tombstones** | 1,400 |
| **E2 + E3 — quota and expiry enforced and accrued on the WS path** (fixes limitation #6) | 220 |
| `StudioDeployer` + **discovery, adoption and re-bootstrap** (§B) | 700 |
| `StudioApi` + `StudioHttpApi` + DTOs + mappers | 1,000 |
| `StudioStore` + repository + domain | 1,100 |
| **`StudioIndex`** — Room, the sync loop with `?since=` and tombstones, and the fresh-for-acting rule (§A.3). **Written now, at N = 1** | 900 |
| Setup wizard, incl. the «بررسی حساب» step and `CloudAddAccountForm` (F1) | 1,300 |
| `ConfigStudioHost` + Dashboard + Users + UserDetail | 1,600 |
| **«تمدید»** — `:renew`, the detail sheet, `renewals` table (§E) | 220 |
| Home / AppScreen / PanelBuild / CloudAccount registration | 60 |

**In:** install or adopt; bearer auth; versioned migrations; users CRUD with absolute expiry, quota,
enable/disable, search, keyset paging; **real quota and expiry enforcement**; one config per user, VLESS
over WS from one default template; a rotatable subscription token; the rebuilt status page (§9); QR /
copy / share; a dashboard from one `/v1/dashboard`; renewal; audit on every write.

**Out:** the second account (the *plumbing* is in, the UI is not); the DO and therefore device /
concurrent / IP caps — **labelled honestly as not enforced, never shown as a cap that works**; plans;
Trojan; XHTTP and gRPC UI; templates; nodes UI; charts; devices; backup.

### Phase 2 — The fleet and «بسته» · ~3,400
§A's UI: Settings › «حساب‌ها» with its own paging and **bulk add** (§A.8), adding further accounts,
**auto-placement** (§A.4), the per-shard staleness and error surfaces, the capacity meter per shard and
in total. Plus §D: `plans` table, plan CRUD, «ساخت کاربر از بسته», plan
sync across shards and its drift badge, and bulk renew by plan.

Deliberately **before** the DO: this is the user's stated need, it is almost entirely client-side, and
it has no unknowns. Retrofitting the fleet after enforcement would mean touching enforcement twice.

### Phase 3 — Device, concurrent and IP caps · ~3,200
`SessionDO` + data-plane edits E1 and E4; `devices` / `sessions` tables and screens; the strict-vs-soft
toggle; `on_first_connect` expiry; quota reset policies; **Trojan** + httpupgrade; multiple configs per
user; credential rotation; the Configs screen; `usage_daily` / `usage_hourly` rollups.
**R1 lives here and this phase owns its own debugging window.**

### Phase 4 — Templates, nodes, analytics, the assistant · ~3,500
Templates CRUD + `ConfigBuilderWizard`; `Node` / `NodeGroup` with capabilities, priority and health
check; Analytics (traffic over time, breakdown by node/protocol/transport, top users); Activity + Audit
with keyset paging; **`StudioCoach`** and the scan-combine flow (§14.4); **«انتقال کاربر» between
shards** (§A.6).

### Phase 5 — Scale and operations · ~2,800
Bulk ops; tags / groups / notes / advanced filter and sort; versioned backup and restore with sealed
secrets; API-key management UI incl. the per-device list from §B.2.3; upgrade / reinstall / uninstall;
two-pane detail panes; subscription output adapters (Clash, sing-box); M6 retirement of the legacy
surface.

### Phase 6 — gRPC and failover · ~1,600
gRPC inbound ported from `edg_worker.js`; node failover and health monitoring; REALITY if warranted;
per-node fan-out strategies; TV D-pad focus.

**Total ≈ 21,000 lines across ~115 files** — against draft 1's ≈ 10,000 / ~70. The work did not grow
by that much; the estimate was wrong (F-e), and §A, §B, §D and §E added roughly 4,000 of it.

**Do not attempt Phase 1 and 3 together** — the DO deploy path is where the unknowns live.

---

# 17. Risks

**Retired:** ~~worker script size~~ — 240 KB against 64 MiB (§1.2b).

Ranked by days-at-risk.

### R1 — Durable Object deployment via the multipart script-upload API *(highest)*
**Zero DO references exist anywhere in `android/app/src/main`.** The metadata needs both a
`durable_object_namespace` binding **and** a tagged migration:

```json
"migrations": { "old_tag": "", "new_tag": "v1", "new_sqlite_classes": ["SessionDO"] }
```

Re-sending `new_sqlite_classes` for an already-created class **errors**, so `old_tag` must be tracked
per account (`CloudAccount.studioDoTag`) and sent as `{"old_tag":"v1","new_tag":"v1"}` on redeploy. Get
this wrong and every upgrade fails with an opaque error.
**Prototype this against one real account before writing any DO logic.** Half a day of prototype saves
three days of guessing.

### R2 — Cloudflare's deploy-time script scanner
This is why `DEFAULT_RELAY` is assembled from six fragments (`:44`) and protocols are `atob('dmxlc3M=')`.
**Every new protocol string must follow the same discipline** — `trojan`, `vmess`, `shadowsocks`, `grpc`,
`reality` must never appear as static literals. A failure here looks like a generic upload error and is
easily misdiagnosed as a bindings problem, **and it would break redeploy for existing users too**.
Mitigation: the F2 build step applies the obfuscation, and a **build-time grep guard** fails the build
on a plaintext protocol name.

### R3 — Legacy-auth hardening, and the order that must not be got wrong
Limitation #2 must be fixed, but naively removing the `plain_text` binding **opens every deployed panel
to the public**: with no binding, `getAdminHash` falls back to the DB's `panel_password`, which on most
installs was never set (change-password was a no-op), and **`verifyApiAuth` returns `true` when there is
no stored hash** (`:661-663`).

Required order:
1. **Build 6:** on first request after upgrade, if a binding password exists and the DB has none, **seed
   the DB from the binding**. Behaviour otherwise unchanged.
2. **Build 6 also:** `verifyApiAuth` fails closed when no hash is stored, *except* on the first-run setup
   route so a genuinely fresh install can still set a password.
3. **Only in build 7**, once health shows installs seeded, does the deployer stop shipping the binding.

Skipping to step 3 is a mass credential leak across every panel this app has ever deployed.

### R4 — The 100k Workers requests/day ceiling *(product constraint, not a bug)*
§4.1. Ship the capacity meter in Phase 1 and default to WebSocket, or an operator's 200th user takes
the whole account down mid-afternoon.

### R5 — The 100k/day D1 **write** ceiling
Every new write in a hot path must be justified against a budget. Never write `last_used_at`,
`last_active`, or a log row per request. Target: **≤3 D1 writes per closed session** plus 24/day fixed.
If a change pushes it above ~5, it is wrong.

### R6 — `pathname === '/'` (E1) is in the routing hot path
Above the camouflage fallback. Get the ordering wrong and either the API becomes reachable as a tunnel
or every tunnel gets the nginx page with HTTP 200. Cover with explicit route-precedence tests.

### R7 — 10 ms CPU on Free
Excludes I/O wait, and the existing worker demonstrably fits. But bearer verification adds a
`crypto.subtle.digest` per API call. **Keep the hash out of the data plane** — the tunnel authenticates
by credential lookup, not by bearer token.

### R8 — `CloudAccount` three-place persistence
`models/CloudAccount.kt`, `loadAccounts()` (`:58-130`), `saveAccounts()` (`:161-217`). A field added in
two of three vanishes on restart **with no error**.

### R9 — RTL and ASCII payloads
Missing one `LocalLayoutDirection provides Ltr` produces a string that looks right in a screenshot and
is broken when pasted. Audit every `Text` that renders a payload before shipping Phase 1.

### R10 — Product-boundary leakage (D1)
"MLM", "پنل" and the legacy naming must not appear in any Studio string, worker route, D1 database name,
or the CF script name. Grep both `strings.xml` files and the built worker before each release.
Note the Kotlin *package* stays `com.mlmvpn.scanner.*` — unavoidable and invisible to users.

**Revision 2: this is already breached in production.** The deployer appends `-mlm` to the script name,
so the legacy name is in the public hostname of every subscription link already handed out (F-b).
New installs get the clean deterministic name (§B.2.1); what happens to existing ones is **§18-Q7**.
The same grep now also covers the D6 commerce vocabulary (§13).

### R11 — 3153-string lockstep
Both files, every string, or one locale shows raw resource names.

### R12 — A fleet read is only as fresh as its worst shard
Under the index (§A.3) an unreachable installation no longer shortens the list — its rows are simply
**old, and look exactly like current ones unless the UI says otherwise.** That is the more dangerous
version of the original risk: an operator acting on figures from a shard that stopped answering two days
ago, with nothing on screen to say so.

Every installation carries `lastSyncAt` and `syncError`, every list shows the age of the worst shard
feeding it, and rows from a failing shard are marked individually. **A shard that fails to answer never
has its rows deleted locally** (§A.9) — the failure mode to avoid is a user disappearing because a
network call failed. Cover with a test that fails one shard and asserts its rows are still listed, are
marked stale, and that a write against one of them is refused by the re-read in R17.

### R13 — Version skew across the fleet
Shard A on build 6 while shard B is on 7 is an **ordinary** state under D7, not an error. Any code that
branches on one global build number is wrong; branch on each installation's `capabilities` (§C). The
failure is silent and shard-specific — a feature that works on one account and not another, which reads
as a network problem and is not.

### R14 — Plans drifting between shards
Plans are copied to every shard by the app (§D). A copy that fails leaves a plan that exists on two
accounts out of three, and a user created against the missing one gets different terms than the operator
believes they sold. Mitigation: the plan row shows «روی همهٔ حساب‌ها نیست» until every shard confirms,
and creation from an incompletely-synced plan is blocked on the shards that lack it.

### R15 — Discovery adopting the wrong worker
The same Cloudflare account also carries BPB, EDG, Nahan, the DNS resolver, the relay and the pool
worker. Adopting one of those and then redeploying Studio over it **destroys another engine's
deployment.** The scan must identify positively — a successful `/v1/health` or the camouflage-page
signature — and must never adopt on a name match alone (§B.2.2).

### R16 — Re-bootstrap can silently downgrade an installation
Phone A runs app v1 (engine build 6); phone B runs v2 (build 7) and upgrades the shared installation.
Phone A then re-bootstraps — and its redeploy uploads **build 6 over build 7**, taking a one-way
migration backwards (§C). The deployer must read `/v1/health` **before** uploading and refuse to deploy
a build lower than the one installed, unless the operator confirms an explicit downgrade. This is the
same class of bug as limitation #16 and it appears the moment two devices share an account, which D8
makes the normal case.

### R17 — The index disagreeing with the shard
The local index (§A.3) is minutes old by design, and draft 1 was right that acting on a stale user list
is the worst failure here. The mitigation is a rule, so it has to be enforced everywhere rather than
remembered: **every mutation re-reads its one user from its own shard before writing.** A code path that
edits straight from an `IndexedUser` is the bug. Reviewer's grep: no `IndexedUser` may reach a
repository *write* method — writes take a user id and re-fetch.

Second failure in the same family: **a hard delete with no tombstone is invisible to every other
device**, which quietly resurrects deleted users on any phone that has them indexed. `deleted_at` and
the `?since=` contract are not optional parts of the sync.

### R18 — the local index is the app's first database, and it is not Room

There is no Room, no SQLite wrapper and no ORM anywhere in `android/app/src/main`; everything
persists as JSON in SharedPreferences or files. The index changes that, because JSON cannot hold
fifteen thousand rows with a search box on them.

**Revision 3 said Room. That was reversed on measurement.** The project has **no `kapt`, no `ksp` and
no `annotationProcessor`** anywhere, and a full compile already takes two minutes. Room means adding
an annotation-processing plugin, its Gradle classpath entry and three artifacts, and paying an APT
pass on every build — to get compile-checked SQL and a migration framework for **one table and five
queries**. `SQLiteOpenHelper` is in the framework: no dependency, no processor, and the SQL sits
where it can be read.

The requirement was never "use Room". It was "the read path must be O(1) in the size of the fleet",
and this meets it.

What still applies from the original risk:

1. **Schema changes need a version bump and an `onUpgrade`.** The index is a cache, so dropping and
   re-syncing is survivable in a way it would not be for real data — but only if every installation
   is reachable at that moment, which at fleet scale it will not be. So an upgrade resets the
   **watermarks** as well as the table, forcing a full re-pull rather than leaving a half-populated
   index that reports itself as current.
2. **Keep it to the index.** `CloudAccount` and everything in §12.3 stays in the existing JSON store.
   Migrating the app's other stores is a separate, app-wide decision.

### R19 — acting on the index instead of on the engine
The index is minutes old by design, and the plan's original objection to a local mirror was exactly
about acting on stale figures. The rule lives in **one** function — `StudioStore.withFreshUser` —
which re-reads the user from its own installation before every mutation, rather than being something
each call site has to remember.

Reviewer's grep: a `StudioUser` obtained from `page()` or `cached()` must not reach a mutating call.
Writes take a user **id** and re-fetch.


---

# 18. Open questions

### Q1 — D3 was chosen against a design review. Confirm with the cost in view.

The review argued for a **separate `studio_worker.js`** forking the ~1400-line data plane and discarding
the 2173-line HTML control plane. Its three arguments, fairly stated:

1. **The auth model cannot be cleanly migrated in place.** Real bearer tokens *alongside* the
   `sha256(password)` cookie means the vulnerable path stays for as long as the legacy client exists.
   Under D3 that window is **build 6 → build 7**, and it is real.
2. **`ADMIN_PASSWORD` as a `plain_text` binding is the compatibility surface itself**, not an incidental bug.
3. **49% of the file is a control plane being thrown away**, and until M6 every schema change must be
   reflected in a `<script>` block inside a template literal.

**Arguments for D3 as chosen:** one worker and one D1 (the free plan allows **10 databases per account**
— a second engine doubles that footprint for every operator); existing operators are *adopted* rather than
migrated (M5 — no data migration at all, which is the single largest risk removed); and one data plane
means a fix is not ported twice.

**The plan above implements D3**, and time-boxes the vulnerability window explicitly at M2/M6.
If you would rather take the fork, say so now — after Phase 1 the cost of switching is roughly a full
phase.

### Q2 — Narrowed: which caps must be real in the first release?
**Quota and expiry are enforced for real in Phase 1** and are no longer part of this question — F-a
showed they never needed the DO, only the `isUserCapped` call the WS path is missing (§16). So the
question is now only about **device, concurrent-connection and IP caps**, which do need `SessionDO` and
therefore carry R1's unknowns.

Phase 1 ships those three as **soft** — recorded and reported, and labelled in the UI as not enforced,
never drawn as a cap that works. If they must be real in release one, Phase 3 merges into Phase 1 and
the first demo slips by roughly a week, on the phase with the most unknowns in the plan.

### Q3 — Does the «ابری» panel row become a pointer at M3, or stay as-is until M6?
Recommended: pointer (§15). Staying as-is means the old name is visible beside Config Studio for the
whole transition, which conflicts with D1.

### Q4 — CLOSED by D7

*Was: how many end-users per Cloudflare account at the top end?* Answered — the operator connects more
accounts rather than pushing one further, so the ceiling per account stops being the product's ceiling.
The free-plan budgets remain comfortable at ~100 users per shard and become the constraint somewhere
between 300 and 800 depending on session length (§4), which is now a **placement** input (§A.4) rather
than a wall. See Q8 for the number that still matters.

### Q5 — Trojan in Phase 1 or Phase 2? Custom domains in the first release?
Trojan: `nahan_worker.js` implements it, so the port is real but not free — ~300 lines plus a testing
pass. VLESS-only is meaningfully faster to a demo.
Custom domains: need Zone-scoped API permissions (**there is no `/zones` call anywhere in the app
today**) and turn the ENDPOINT step from "confirm" into "configure DNS". For someone running a panel a
branded domain is close to table stakes — if it is required in release one, that step roughly doubles
and needs its own troubleshooting path.

### Q6 — CLOSED: a user belongs to exactly one shard

Confirmed as designed. One user, one installation, one subscription origin, with «انتقال کاربر» (§A.6)
as the escape hatch. It is the principled choice because it is the one that keeps **every write going to
exactly one shard** — no distributed transaction, no cross-worker call, no partial commit, and the
worker needs no code at all for the fleet to exist. The rejected alternative, an aggregator shard
serving one link that fans out across accounts, re-centralises the failure D7 exists to spread and
spends the aggregator's request budget on users it does not host.

### Q7 — CLOSED: new installs get the clean name, existing hostnames are left alone

Option 1, as recommended. **New installations** use the deterministic name from §B.2.1, with no `-mlm`.
**Existing installations keep the hostname they have**, and the discovery scan (§B.2.2) adopts them
under it.

The reason is not tidiness, it is that option 2 breaks links that are already in people's hands with no
way to tell them, which is a worse outcome than a legacy string in a hostname. So the D1 breach (F-b)
persists on already-deployed installations and ends by attrition. **Do not add a "rename" action** —
it would look harmless in a settings screen and would silently break every subscription on that shard.

### Q8 — CLOSED: no cap, and it changed the read architecture

The app imposes no ceiling on the number of Cloudflare accounts. This is the answer that cost the most:
revision 2's per-shard fan-out with a k-way merge is **O(N) requests per screen**, which is fine at
N = 5 and impossible at N = 50.

**§A.3 was rewritten because of this answer** — reads come from a local Room index synced incrementally
per shard, so a list or a dashboard is O(1) in N. That reverses draft 1's "no local mirror" rule (§12.3)
and adds the app's first database (R18), with **stale for browsing, fresh for acting** as the rule that
keeps the original objection answered (R17).

What is genuinely given up at large N, and is stated in the UI rather than hidden: **there is no merged
activity or audit feed across the fleet** (§A.3, §A.9). Those stay scoped to one shard or one user.


---

# 19. Verification

No automated test harness exists for the worker. Verification is:

- **Worker**: `wrangler dev` against a local D1 seeded from **a copy of a real build-5 database** — the
  migration is the risky part and must be exercised on real data, not an empty file. Then a scripted
  pass over `/v1/*` covering auth, keyset pagination, bulk ops and the R3 ordering.
- **DO deploy (R1)**: prototype the multipart metadata against one real Cloudflare account **before**
  writing DO logic. Verify redeploy with `{"old_tag":"v1","new_tag":"v1"}` succeeds.
- **Builders**: `ConfigBuilderGoldenTest.kt` over the shared fixture file, plus a Node test reading the
  same fixtures against the worker's substitution function, so F3 and F4 cannot drift.
- **Android**: `.\gradlew.bat :app:compileDebugKotlin`. **The Gradle daemon dies on an AF_UNIX socket
  unless these are set first** — `GRADLE_OPTS=-Djdk.net.unixdomain.tmpdir=C:\gradle-tmp`,
  `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`, `TEMP=C:\gradle-tmp`, `TMP=C:\gradle-tmp`.
- **Device**: no emulator; a Samsung SM-A505F on Android 11 is the test phone. `adb` is at
  `C:\Users\ehsan computer\AppData\Local\Microsoft\WinGet\Packages\Google.PlatformTools_*\platform-tools\adb.exe`
  (path has spaces — quote it). Use `android/scripts/grab-log.sh` rather than raw logcat.
- **The user-facing surface**: `curl` `/s/{token}` and assert the `Subscription-Userinfo` header; then add
  the link to v2rayNG and confirm volume and expiry render in its own UI.
- **Adoption (§B)**: the test that matters is **two devices, one Cloudflare account.** Install from
  phone A, then run the wizard from phone B and assert that `GET /workers/scripts` grows by **zero**,
  that B lands on A's URL and database, and that A still works afterwards. Then reverse the order.
  Also assert the downgrade guard (R16) refuses a lower build.
- **Fleet (§A)**: run with two accounts and **fail one of them** — assert the user list is annotated
  rather than shortened (R12), and that a write still reaches exactly one shard.
- **Quota**: after a day of use, check D1 rows read/written and Worker requests on the Cloudflare
  dashboard against §4. This is the check that would have caught the 2026-09-06 outage a day early.

---

# 20. Release convention (applies to every phase)

From the project's standing rules:

- Add the items to `ui/ChangelogData.kt` in **both** the Persian and English lists.
- Keep the **English** half of `android/CHANGELOG.md` current (its Persian half was abandoned after 1.2.2).
- Refresh the **draft** GitHub release on `mlmvpn/mlmvpn_android`.
- **Bump `versionName`/`versionCode` only when the newest draft has actually been published.** Run
  `gh release list` first: if the top row is still `Draft`, the turn's items go **into** that draft and
  that version's `ChangelogVersion` block. As of this writing **v1.2.33 is an unpublished draft.**
- `gh` traps: the local branch is `master` but the remote default is **`main`** (`--target main`), and
  the first `gh` call of a session often fails with a connection error — just run it again.

