# Crash reports: where they go and how to read them

> **Read this before touching `handleCrash`, `fileCrashOnGithub`, `CrashReporter` or
> `MlmPoolClient.reportCrash`.** The pipeline crosses an app, a Worker, a D1 database, a KV
> namespace and a second GitHub repository, and no one file explains it.

## The one-line answer

A crash becomes a **GitHub issue in the private repo `mlmvpn/crashes`**, one issue per distinct
crash signature. Read them there. The D1 dashboard at `/crashes` is the second copy and the one
that counts distinct users.

## The whole path

| # | Where | What happens |
| --- | --- | --- |
| 1 | `CrashReporter.install()` — [`CrashReporter.kt`](../app/src/main/java/com/mlmvpn/scanner/CrashReporter.kt) | An uncaught Kotlin/Java throwable is written to `files/crashlogs/` **and** logcat under tag `MLMCrash`. The file is the point: the process dies immediately after, so anything held in memory is gone. |
| 2 | `AppScreen.kt` | On the **next launch**, on the **home screen only**, one dialog offers to send it. Offered once per crash — `markOffered` records it by timestamp, so a burst from one crash prompts once. |
| 3 | `CrashReporter.upload()` → `MlmPoolClient.reportCrash()` | `POST /crash` to the pool Worker, signed with the install's enrolment secret. Resolves through DoH, because Iranian operators poison `*.workers.dev` and a crash reporter that cannot reach its own server is one in name only. |
| 4 | `handleCrash()` — [`mlmvpn_pool_worker.js`](../app/src/main/assets/mlmvpn_pool_worker.js) | Recomputes the signature server-side (a hostile client must not be able to split one bug into many), writes one D1 row per `(install, signature)`, then files on GitHub via `ctx.waitUntil` so the phone is not made to wait. |
| 5 | `fileCrashOnGithub()` | One issue per signature in `GH_REPO`, deduped through the `POOL` KV namespace under `crash:<sig>`, falling back to a label search when KV has no record. Repeat occurrences add a **comment**, at most one per signature per hour. |

## Reading the reports

**Primary — GitHub.** <https://github.com/mlmvpn/crashes/issues>

That repository's README explains the issue format. In short: the title is the exception plus the
first `com.mlmvpn` frame, `sig:xxxxxxxx` is the dedup key, and the full stack (with the last 40
screens the user visited) is in the collapsed `stack` block.

**Secondary — the D1 dashboard.** Ordered by **distinct installs**, which is the question worth
asking first; GitHub cannot answer it.

```
https://mlm-pool-7f3a2c.ehsan2novenic2.workers.dev/crashes?k=<STATS_PASSWORD>&days=14
https://mlm-pool-7f3a2c.ehsan2novenic2.workers.dev/crashes?k=<STATS_PASSWORD>&sig=<signature>
```

`STATS_PASSWORD` is a literal near the top of `mlmvpn_pool_worker.js`.

## On the phone

`files/crashlogs/` holds three kinds of file, pruned by kind:

| File | Written by | Kept |
| --- | --- | --- |
| `crash-*.txt` | the uncaught-exception handler: the stack and the last 40 breadcrumbs | newest 20 |
| `lastexit-*.txt` | the next launch (main process only), from `ApplicationExitInfo`, and only when a run ended badly: a crash of either kind, an ANR, a kill for resource use, or an unasked nonzero exit outside `:tun` | newest 5 |
| `exit-*.txt` | the shutdown hook, on every deliberate exit | newest 5 |

What a user shares (Settings → Crash report, and the fallback when sending fails) is up to three
stacks first, then the newest exit history and shutdown note. Sending from the launch dialog uploads
every stack since the last offer (up to three), not only the newest.

This replaced a scheme that wrote an exit history on **every** launch and shared the newest three
files of any kind. The text users sent then said that a JVM crash had happened and left out the
stack, and pruning to thirty files of any kind deleted the stacks first.

## What does NOT produce a report

- **Native crashes** (SIGSEGV, SIGABRT, fdsan, FORTIFY). Nothing in-process can catch them. What
  `CrashReporter` contributes there is a marker in logcat naming the last screen and action, so the
  line before the `F/libc` abort is not guesswork. The backtrace exists only in logcat and the
  tombstone.
- **A user who dismisses the dialog.** Sending is deliberate and one-shot. There is no silent
  upload, and adding one would be a different decision about the user's data, not a bug fix.
- **A phone that never enrolled with the pool.** `MlmPoolClient.identity()` returns null and
  `reportCrash` refuses before any network call.

## The failure that keeps happening

D1 on the free plan has an **account-wide daily row-read ceiling**. When it is exhausted:

```
D1_ERROR: Your account has exceeded D1's free tier daily row read limit
```

…and it takes down more than the dashboard. `authenticate()` reads the enrolment row on **every**
request, so once reads are gone, `/crash` rejects incoming reports too, `/list` stops serving the
Quick Connect pool, and the day's crashes are lost. The GitHub half was written to survive exactly
this and cannot, because auth fails before `handleCrash` is entered.

Two real fixes, in order of effort: **pay for D1** (the ceiling disappears), or **spend fewer row
reads** (`/list` is the suspect — it runs per client, all day, at pool scale).

What *has* been fixed: the D1 insert inside `handleCrash` is wrapped, so a write failure no longer
throws out of the handler and skips the GitHub filing. The response carries `stored: true|false`
so a hand-run test can tell the two stores apart.

## Configuration

| Secret on `mlm-pool-7f3a2c` | Value |
| --- | --- |
| `GH_REPO` | `mlmvpn/crashes` |
| `GH_TOKEN` | Fine-grained PAT, **that repository only**, `Issues: Read and write`. Nothing else. |

```bash
npx wrangler secret put GH_REPO  --name mlm-pool-7f3a2c
npx wrangler secret put GH_TOKEN --name mlm-pool-7f3a2c
npx wrangler secret list --name mlm-pool-7f3a2c
```

Without both, `fileCrashOnGithub` returns on its first line and says nothing. That silence is by
design — a crash report must not fail because a bug tracker is misconfigured — which also means
**a missing secret looks exactly like a working system from the app's side.** Check the secret list,
not the app.

The repository must stay **private**: a stack trace names internal classes and the screens the user
walked through to get there.

## Deploying a change to the Worker

`mlmvpn_pool_worker.js` is hand-maintained (unlike `mlm_worker.js`, which is generated from
`worker-src/studio/`). It is uploaded by `CloudManager.kt` — search for `mlmvpn_pool_worker.js` —
so a change ships by redeploying the pool from the app, or with wrangler against
`mlm-pool-7f3a2c`.

## Testing it end to end

1. Confirm the secrets are set (`wrangler secret list`).
2. Confirm D1 is not read-limited: `curl '<worker>/crashes?k=<STATS_PASSWORD>&days=1'` must return
   JSON, not `D1_ERROR`.
3. **Server half, on its own.** Enrol a throwaway install and post a signed report. This proves
   the Worker and the GitHub filing without touching a phone:

   ```bash
   node scripts/test-crash-report.js
   ```

   Signing is `HMAC-SHA256(secret, "<install-id>.<ts>.<body>")` as hex, in `X-Install` / `X-Ts` /
   `X-Sig`. The timestamp window is five minutes.

4. **App half, on a debug build.** There is deliberately no crash button in the app, so plant the
   artefact a crash would have left and let the normal flow pick it up:

   ```bash
   adb shell "run-as com.mlmvpn.scanner sh -c 'cat > files/crashlogs/crash-test.txt'" < report.txt
   adb shell "run-as com.mlmvpn.scanner rm -f shared_prefs/crash_diag.xml"   # re-arm the offer
   adb shell am force-stop com.mlmvpn.scanner
   adb shell monkey -p com.mlmvpn.scanner 1
   ```

   The offer appears on the home screen on that launch. Tapping send runs exactly the path a real
   crash runs — same file, same signature, same endpoint.

5. A new issue should appear in `mlmvpn/crashes` within seconds. A repeat of the same signature
   adds a comment instead, and only after an hour has passed.
