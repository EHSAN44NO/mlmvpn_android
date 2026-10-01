# Crash reports: where they go and how to read them

> **Read this before touching `CrashReporter`, `crash/CrashClient`, `crash/CrashUploadJob`,
> `worker-src/crash/worker.js`, or `handleCrash` in `mlmvpn_pool_worker.js`.** The pipeline
> crosses the app, two Workers on two Cloudflare accounts, KV, D1 and a private GitHub repository,
> and no one file explains it.

## The one-line answer

Every crash becomes a **GitHub issue in the private repo `mlmvpn/crashes`**, one issue per distinct
crash signature. Read them there: <https://github.com/mlmvpn/crashes/issues>.

## What gets reported

| Failure | How it is caught | Report |
| --- | --- | --- |
| JVM crash (uncaught exception, any process) | `CrashReporter`'s uncaught-exception handler | the stack and the last 40 breadcrumbs |
| Native crash (SIGSEGV, SIGABRT, fdsan, FORTIFY, Rust panic) | the next launch reads `ApplicationExitInfo` | signal, library, abort message, the readable part of the tombstone |
| ANR | the next launch reads `ApplicationExitInfo` | the system's description and the thread dump |
| JVM crash whose handler could not write; start-up timeout; killed for resource use | the next launch reads `ApplicationExitInfo` | what the system recorded |

Native crashes and ANRs also carry the **process state summary**: `CrashReporter.note` hands the
system a line with the app version and the last breadcrumb ("openTab home -> mae"), and the
system returns it with the record of the death. Breadcrumbs are otherwise lost with the process.

## The whole path

| # | Where | What happens |
| --- | --- | --- |
| 1 | `CrashReporter` | The report is written to `files/crashlogs/crash-*.txt` (redacted by `SecretRedactor`) and a marker is put in `crashlogs/outbox/`. A JVM crash schedules `CrashUploadJob` before the process dies. |
| 2 | `CrashUploadJob` (JobScheduler) | Runs as soon as there is a network, whether or not the app is opened again. Sends up to five reports per run. Until every report is filed it asks to be rescheduled, with exponential backoff, across reboots. |
| 3 | `CrashClient.send` | `POST /v1/crash` to the **crash collector** (`worker-src/crash`). If the collector does not file it, the report goes to the pool's `POST /crash` instead. Only a 2xx from one of them counts as delivered; anything else leaves the report in the outbox. Both resolve through DoH and `WorkerRoute`, because operators poison `*.workers.dev`. |
| 4 | the collector | Checks the app key and the per-install, per-address and new-issue limits, then **files on GitHub before answering**. One issue per signature, at most one comment an hour with the report in full, and a closed issue is reopened when a newer build hits it. |
| 5 | the pool, as fallback | Stores a D1 row and files on GitHub through the same token. See `handleCrash`. |

Both Workers compute the same signature (sha256 of the summary line: the exception and the first
`com.mlmvpn` frame, or a native or ANR report's `error:` headline). A bug therefore stays one issue
whichever route its reports took.

## The user's choice

Sending is **automatic unless the user turns it off** (Settings → Crash report → "Send crash
reports automatically"). The first time a report is sent automatically, the next launch shows a
one-time notice saying so, with a button that turns it off.

With it off, the old flow applies. The launch after a crash asks; Send queues the reports and
delivers them. If that fails, the share sheet opens, and the job keeps trying.

Turning it off also withdraws anything queued but not yet sent. The setting is a file
(`crashlogs/.manual-send`), so every process of the app reads it the same way.

Reports contain the error, the breadcrumbs (screens and actions), the device model and the app
version. They contain nothing from configs or traffic, and they pass through `SecretRedactor`
before they are written, shared or sent.

## On the phone

| File | Written by | Kept |
| --- | --- | --- |
| `crash-*.txt` | every report above | newest 20 |
| `lastexit-*.txt` | the main process at launch, only when a run ended badly | newest 5 |
| `exit-*.txt` | the shutdown hook, on every deliberate exit | newest 5 |
| `outbox/<name>` | a report waiting to be delivered (given up after 30 days) | until delivered |
| `sent/<name>` | a report that was delivered, so it is never sent twice | as long as the report |

Settings → Crash report shares up to three reports, then the newest exit history and shutdown
note. That is the manual route, for example to Telegram.

## Deploying the collector

```bash
node scripts/deploy-crash-worker.mjs <credentials.txt> <secrets.json>
```

- `credentials.txt`: line 1 the Cloudflare e-mail, line 2 the Global API Key. Use an account that
  does **not** run the Quick Connect pool. Worker requests (100k a day on the free plan) and D1
  reads are counted per account, and the pool's traffic is what used to take crash reporting down.
  The collector runs on its own account (`mlm-770adbce.workers.dev`, set up 2026-10-01), used for nothing else.
- `secrets.json`: created by the first run. Fill in `ghToken` (a fine-grained token with
  **Issues: Read and write** on `mlmvpn/crashes` and nothing else) and run the script again. Keep
  this file out of git.

Before uploading, the script checks that the token reaches the repository and that the repository
is **private**. After uploading, it reads `/health`. If the address it got differs from
`CrashClient.ENDPOINT`, it rewrites that constant; commit that change and build.

## Checking it

- **Collector:** `https://<collector>/health?k=<adminToken from secrets.json>` shows whether the
  GitHub secrets are set, the time of the last successful GitHub call, and the last GitHub error
  with its status.
- **Token expiry.** Fine-grained tokens expire. When one does, `/health` shows `401 Bad
  credentials`, and reports wait on the phones (the collector answers 502). Replace the token in
  `secrets.json`, redeploy, and the waiting reports arrive on the phones' next retries.
- **Pool:** `https://mlm-pool-7f3a2c.ehsan2novenic2.workers.dev/crashes?k=<STATS_PASSWORD>&days=14`
  lists the D1 copy by distinct installs. It also has a `github` block with the same health fields,
  and a `d1_error` instead of failing when D1 is out of reads. `STATS_PASSWORD` is a literal near
  the top of `mlmvpn_pool_worker.js`.

## Tests

```bash
node scripts/test-crash-worker.mjs        # the collector, offline: GitHub and KV are mocked
node scripts/test-pool-crash-worker.mjs   # the pool's crash path, offline, against real SQL
node scripts/test-crash-report.js         # the deployed pool, for real: files a test issue
```

`CrashTextTest` covers the headlines and signatures: one bug must be one issue, and two bugs must
not share one.

To exercise the app half on a debug build, plant a report and let the normal flow pick it up:

```bash
adb shell "run-as com.mlmvpn.scanner sh -c 'cat > files/crashlogs/crash-test.txt'" < report.txt
adb shell "run-as com.mlmvpn.scanner sh -c 'mkdir -p files/crashlogs/outbox && touch files/crashlogs/outbox/crash-test.txt'"
adb shell cmd jobscheduler run -f com.mlmvpn.scanner 817758   # CrashUploadJob (0x0C7A5E)
```

## What the pool learned the hard way

These limits are why the collector exists.

- **D1's daily read ceiling.** It is account-wide on the free plan, and the pool reaches it. When it
  does, `authenticate()` cannot read the enrolment row. The pool now verifies a crash report from
  an install that the same isolate has already seen against the secret in memory. Anything else
  gets a 503, and the app retries later. The schema check no longer throws Cloudflare's error page
  for every route.
- **The shared rate limit.** Crash reports used to share Quick Connect's 60 calls a day, so a heavy
  user's crash got a 429. They now have their own 30 a day (`crash_day` and `crash_calls` in
  `installs`).
- **KV's daily write ceiling.** When KV was out of writes, every report of a known bug commented
  again. Each isolate now keeps the dedup records in memory, in front of KV.
- **Silent GitHub failures.** Filing ran after the response, so a bad token showed up nowhere. Its
  health is now recorded and shown in `/crashes`.
