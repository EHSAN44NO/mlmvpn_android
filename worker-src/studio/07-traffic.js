/**
 * The legacy panel's sweep, kept for its one caller (`GET /api/users`). Since build 18 every byte
 * lives in the meter (07a-meter.js), which writes on its own timer, so this only hurries it along.
 */
async function flushExpiredTraffic(env) {
  studioMeterFlushAll(env, null);
}

// ==========================================================
// Account-wide traffic, by the hour
// ==========================================================
//
// `usage_hourly` is what the dashboard's 24-hour figure is summed from, and **nothing wrote it**.
// The table was created by the migration, the query was written against it, and the tile therefore
// reported zero on every installation regardless of how much traffic had actually passed — a number
// on screen that no amount of use could move.
//
// It is 24 rows a day whatever the user count, which is the property that makes the dashboard
// answerable without touching per-user rows at all.
//
// **Coalesced before writing, deliberately.** The per-user flush already fires once per 50 MB, so
// upserting a bucket row on each one would double the cost of accounting — and the plan's write
// budget is explicit that this path is where the scarcest resource in the system gets spent. So the
// bytes accumulate in the isolate and reach D1 once per ~500 MB, or when a session ends. An isolate
// that dies takes its pending tail with it: acceptable here and nowhere else, because this feeds a
// CHART. Quotas are never served from it — they come from the per-user counters, which are exact.
/**
 * When each (user, kind) pair last had an activity row written, per isolate.
 *
 * This is the whole reason `activity_log` can be written at all. A client that is being refused
 * does not stop -- it reconnects, immediately and forever -- so a row per refusal would let one
 * expired subscriber spend the account's entire 100,000-write daily budget by leaving their phone
 * on. One row per user per kind per hour turns "this keeps happening" into one line, which is also
 * what an operator actually wants to read.
 */
const GLOBAL_ACTIVITY_WRITE = new Map();
const STUDIO_ACTIVITY_EVERY_MS = 3600000;
/** Rows kept. Beyond this the oldest go, because this log is diagnostic and not a record. */
const STUDIO_ACTIVITY_KEEP = 4000;
let studioActivityWrites = 0;

/**
 * Record something the SYSTEM observed, as opposed to something a person did.
 *
 * Two logs, two tables, and the split is deliberate: `audit_log` holds decisions -- who changed
 * what -- and is kept for the life of the installation because it is the record an operator gets
 * asked about. This one holds observations -- a tunnel refused, a path claimed by nothing, a write
 * that failed -- which are high volume, useful for about a week, and pruned hard.
 *
 * Fire-and-forget through `ctx.waitUntil` where there is a ctx: nothing on the connection path may
 * wait on a diagnostic write, and a refusal that failed to be logged is still a refusal.
 */
function studioLogActivity(env, ctx, opts) {
  if (!env || !env.DB || !opts || !opts.kind) return;
  const key = (opts.user_uid || '-') + ':' + opts.kind;
  const now = Date.now();
  if ((now - (GLOBAL_ACTIVITY_WRITE.get(key) || 0)) < STUDIO_ACTIVITY_EVERY_MS) return;
  GLOBAL_ACTIVITY_WRITE.set(key, now);

  const task = async () => {
    try {
      await env.DB.prepare(
        `INSERT INTO activity_log (ts, kind, user_uid, config_id, node_id, severity, detail)
         VALUES (?,?,?,?,?,?,?)`
      ).bind(
        now, String(opts.kind).slice(0, 40), opts.user_uid || null,
        opts.config_id || null, opts.node_id || null,
        opts.severity === 'error' ? 'error' : (opts.severity === 'warn' ? 'warn' : 'info'),
        opts.detail ? String(opts.detail).slice(0, 200) : null,
      ).run();

      // Pruned from inside the writer rather than on a schedule, because a Worker has no
      // scheduler here. One delete per two hundred writes keeps the table bounded at a cost that
      // rounds to nothing, and the subquery is over the primary key.
      studioActivityWrites++;
      if (studioActivityWrites % 200 === 0) {
        await env.DB.prepare(
          `DELETE FROM activity_log WHERE id < (SELECT MAX(id) - ? FROM activity_log)`
        ).bind(STUDIO_ACTIVITY_KEEP).run();
      }
    } catch (e) {
      // Swallowed on purpose, and this is the one place that is right: the whole point of this
      // function is to record that something went wrong, and letting it throw would turn a
      // logged problem into a second, larger one on the connection path.
    }
  };
  if (ctx && ctx.waitUntil) ctx.waitUntil(task()); else task();
}

/**
 * One closed connection, as a row.
 *
 * `sessions` was created by the third Config Studio migration and **nothing had ever inserted into
 * it** — so three separate questions had a table and no data: what a person's own connection
 * history looks like, how much traffic each config carried, and which protocol and transport are
 * actually in use. All three are answered by this one row, which is why it is worth the write.
 *
 * **`node_id` is left null on purpose and will stay null.** A Worker cannot see which Cloudflare
 * edge address the client dialled: the edge routes by server name, and the address it was reached
 * on appears neither in the request nor in `request.cf`. So per-endpoint traffic is not a feature
 * that has not been built — it is a measurement this architecture cannot make, and inventing it by
 * attributing a config's traffic to whatever endpoint it is pinned to would produce a number that
 * looks precise and is a guess.
 *
 * Written in the SAME batch as the closing byte flush, so it costs one more row write and no extra
 * round trip. That takes a closed session from three row writes to four, which is over the "≤3 per
 * closed session" line in R5 — taken deliberately, because a session is the only event in the
 * system that can carry this and the alternative is three permanently unanswerable screens. The
 * per-50 MB flush is untouched: this fires once, at the end.
 *
 * The id is random rather than the lease id, which is null on an installation with no Durable
 * Object — and a null primary key would collapse every session on such an account into one row.
 * Nothing orders by it: `ended_at` is what recency means here, and it is what both indexes carry.
 */
function studioSessionRow(env, s, bytes) {
  if (!s || !s.uid) return null;
  const now = Date.now();
  return env.DB.prepare(
    `INSERT INTO sessions (id, user_uid, config_id, node_id, device_hash, ip_hash,
       protocol, transport, started_at, ended_at, up_bytes, down_bytes, close_reason)
     VALUES (?,?,?,NULL,NULL,NULL,?,?,?,?,0,?,?)`
  ).bind(
    studioRandomHex(10), s.uid, s.configId || null,
    s.protocol || null, s.transport || null,
    s.startedAt || now, now, bytes > 0 ? bytes : 0, s.reason || 'closed',
  );
}

/**
 * Keep `sessions` from growing without limit, from inside the writer.
 *
 * A Worker has no scheduler, so this is the shape `activity_log` already uses: prune occasionally
 * rather than on a timer, and cheaply. One in every fifty closes runs it, which on an account with
 * traffic is often enough to hold the ceiling and rare enough to disappear into the write budget.
 *
 * By `ended_at` and not by `id`. `activity_log.id` is an AUTOINCREMENT integer, so `MAX(id) - N`
 * is the newest N there; `sessions.id` is a random hex string and ordering by it would delete an
 * arbitrary subset — the rows whose random id happened to sort low, which is not the same as the
 * old ones and would leave somebody's history full of holes.
 *
 * Five thousand rather than four: this table is the only record of a person's own connection
 * history, and a fleet where one account carries all the traffic is the ordinary shape.
 */
const STUDIO_SESSION_KEEP = 5000;
let studioSessionCloses = 0;
function studioPruneSessions(env, ctx) {
  studioSessionCloses++;
  if (studioSessionCloses % 50 !== 0) return;
  const task = env.DB.prepare(
    `DELETE FROM sessions WHERE ended_at < (
       SELECT ended_at FROM sessions ORDER BY ended_at DESC LIMIT 1 OFFSET ?)`
  ).bind(STUDIO_SESSION_KEEP).run().catch(() => {});
  if (ctx && ctx.waitUntil) ctx.waitUntil(task);
}

const STUDIO_HOURLY_FLUSH_BYTES = 500 * 1024 * 1024;
const GLOBAL_HOURLY_PENDING = new Map();
/**
 * Sessions closed in the current hour but not yet written.
 *
 * Separate from the byte counter because the two are not the same event. `usage_hourly.sessions`
 * was created by the schema and incremented by nothing, so every connection count in the product
 * read zero -- and the obvious fix, folding it into the byte upsert, would still have missed the
 * session that ends having moved nothing, because that one returns before the write.
 */
const GLOBAL_HOURLY_SESSIONS = new Map();

function studioHourBucket(now) {
  return Math.floor(now / 3600000) * 3600000;
}

/** Days are UTC, so a chart does not shift when the operator travels. */
function studioDayBucket(now) {
  return Math.floor(now / 86400000) * 86400000;
}

/**
 * One person's traffic, by the day.
 *
 * Written by the meter (07a-meter.js) with EVERY byte it writes to the user row -- in the same batch,
 * at most every five minutes and always when the person's last connection closes. Until build 18
 * only the close-time tail came here, so the per-person statistics were always a fraction of the
 * volume on the same person's row, and never included XHTTP at all.
 *
 * [countSession] adds one to the day's session count; only the flush that closes a session does.
 * A session spanning midnight lands in the day it was written, which for a usage history is the
 * right trade against a row write per boundary.
 */
function studioAccrueDaily(env, userUid, bytes, countSession) {
  if (!userUid || !(bytes > 0)) return null;
  const s = countSession ? 1 : 0;
  return env.DB.prepare(
    `INSERT INTO usage_daily (user_uid, day, up_bytes, down_bytes, sessions)
     VALUES (?, ?, 0, ?, ?)
     ON CONFLICT(user_uid, day) DO UPDATE SET
       down_bytes = COALESCE(down_bytes,0) + ?, sessions = COALESCE(sessions,0) + ?`
  ).bind(userUid, studioDayBucket(Date.now()), bytes, s, bytes, s);
}

/**
 * Add bytes to the current hour, and write when enough have piled up.
 *
 * @param force set when a session is ending, so its tail is not left waiting for a threshold that
 *   may never be reached on an idle worker. A forced call also **counts one session**, which is
 *   what makes every connection figure in the product non-zero: `usage_hourly.sessions` existed
 *   from the first migration and nothing had ever incremented it.
 *
 * A session that ends having moved no bytes is still a session, so the early return now guards the
 * byte half only -- returning before the counter is exactly how a column stays at zero forever.
 */
/**
 * Written at least this often while anything is pending. Without a timer the chart only moved in
 * 500 MB steps or when a session ended, and an idle worker could hold an hour's traffic forever.
 */
const STUDIO_HOURLY_FLUSH_MS = 300000;
let studioHourlyFlushedAt = Date.now();

function studioAccrueHourly(env, bytes, ctx, force) {
  const add = bytes > 0 ? bytes : 0;
  if (!add && !force) return;

  const now = Date.now();
  const bucket = studioHourBucket(now);
  GLOBAL_HOURLY_PENDING.set(bucket, (GLOBAL_HOURLY_PENDING.get(bucket) || 0) + add);
  if (force) GLOBAL_HOURLY_SESSIONS.set(bucket, (GLOBAL_HOURLY_SESSIONS.get(bucket) || 0) + 1);

  let total = 0;
  for (const v of GLOBAL_HOURLY_PENDING.values()) total += v;
  if (!force && total < STUDIO_HOURLY_FLUSH_BYTES && now - studioHourlyFlushedAt < STUDIO_HOURLY_FLUSH_MS) return;
  studioHourlyFlushedAt = now;

  // EVERY pending hour, not only the current one. A session that started at 10:58 and ended at
  // 11:03 left its 10:00 bytes behind when only the current bucket was flushed, and the dashboard's
  // 24-hour figure read low by exactly those tails.
  const buckets = new Set([...GLOBAL_HOURLY_PENDING.keys(), ...GLOBAL_HOURLY_SESSIONS.keys()]);
  const rows = [];
  for (const b of buckets) {
    rows.push({ b, bytes: GLOBAL_HOURLY_PENDING.get(b) || 0, sessions: GLOBAL_HOURLY_SESSIONS.get(b) || 0 });
    GLOBAL_HOURLY_PENDING.delete(b);
    GLOBAL_HOURLY_SESSIONS.delete(b);
  }
  if (!rows.length) return;

  const task = async () => {
    try {
      await env.DB.batch(rows.map((r) => env.DB.prepare(
        `INSERT INTO usage_hourly (bucket_ts, up_bytes, down_bytes, sessions, users_seen)
         VALUES (?, 0, ?, ?, 0)
         ON CONFLICT(bucket_ts) DO UPDATE SET
           down_bytes = COALESCE(down_bytes,0) + ?,
           sessions = COALESCE(sessions,0) + ?`
      ).bind(r.b, r.bytes, r.sessions, r.bytes, r.sessions)));
    } catch (e) {
      // Put everything back rather than losing it, and let the next flush carry it.
      for (const r of rows) {
        GLOBAL_HOURLY_PENDING.set(r.b, (GLOBAL_HOURLY_PENDING.get(r.b) || 0) + r.bytes);
        GLOBAL_HOURLY_SESSIONS.set(r.b, (GLOBAL_HOURLY_SESSIONS.get(r.b) || 0) + r.sessions);
      }
    }
  };
  if (ctx && ctx.waitUntil) ctx.waitUntil(task()); else task();
}
