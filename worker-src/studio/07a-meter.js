// ==========================================================
// The usage meter (build 18): one per person per isolate
// ==========================================================
//
// Every byte a person moves is counted here, whatever carried it -- VLESS or Trojan over WebSocket,
// or XHTTP -- and reaches D1 on three occasions: when enough has piled up for what is left of their
// quota, every 90 seconds while anything is pending, and when their last connection in this isolate
// closes.
//
// It replaces three copies of the same bookkeeping (08-ws.js, 09-xhttp.js and the legacy sweep), and
// the differences between those copies were the bugs an operator could see:
//
//  * The tail below the 50 MB commit was written only when a person's LAST connection in the isolate
//    closed, and several close paths never said so. A phone always keeps one connection open, so its
//    usage reached D1 in 50 MB steps or not at all -- ten megabytes used showed as sixty kilobytes.
//  * Nothing moved `updated_at`, which is what the app's `?since=` sync follows, so the operator's
//    list froze at the figure it read the day the person was created.
//  * `usage_daily`, which the per-person statistics read, only ever received the close-time tail,
//    so «آمار» and the volume on the person's own row were two different numbers.
//  * The room a quota had left was a per-isolate guess refreshed once a minute and committed a tenth
//    of the quota at a time, so a 100 MB user could run to 108 before anything noticed.
//
// Keyed by uid rather than username: a rename while connected used to send every later flush to a
// row that no longer had that name, where it matched nothing and was dropped without an error.

const METER = new Map();

/** A user-row flush at least this often while bytes are pending: what the operator's list lags by. */
const METER_FLUSH_MS = 90000;

/**
 * `usage_daily` and the hourly rollup. Coarser than the user row on purpose: they feed charts, and
 * each is one more row write per flush on the one path where D1 writes are the scarce resource.
 * The last close always writes them, so nothing is lost -- they just arrive in five-minute steps.
 */
const METER_ROLLUP_MS = 300000;

/** How often an open session re-reads its person's row when no flush has brought it back. */
const METER_POLICY_MS = 60000;

const METER_MIN_THRESHOLD = 64 * 1024;
const METER_MAX_THRESHOLD = 32 * 1024 * 1024;
const STUDIO_GIB = 1073741824;

/**
 * What a flush reads back in the same statement, so the room left is refreshed by the write itself
 * rather than by a second query. Everything admission needs, and nothing else.
 */
const METER_RETURNING =
  'uid, username, is_active, deleted_at, quota_bytes, used_bytes, daily_quota_bytes, daily_used_bytes, ' +
  'limit_gb, used_gb, daily_limit_gb, daily_used_gb, expires_at, expiry_days, created_at';

/**
 * Set if this D1 ever refuses `RETURNING`. It is supported, and this is still not left to chance:
 * an accounting write that fails for a syntax reason fails on every flush, forever, and each failure
 * puts the bytes back to be retried -- so a wrong assumption here would stop all usage from being
 * recorded. Without it, the room is refreshed by the policy read instead.
 */
let METER_NO_RETURNING = false;

// ---------------------------------------------------------------------------- the arithmetic

/** A byte figure from the byte column when the row has one, and from the build-5 GB mirror if not. */
function studioLimitOf(row, bytesCol, gbCol) {
  if (!row) return 0;
  const b = Number(row[bytesCol]);
  if (b > 0) return b;
  const g = Number(row[gbCol]);
  return g > 0 ? Math.round(g * STUDIO_GIB) : 0;
}

/**
 * Usage, as the larger of the two columns.
 *
 * During a rollout an isolate still running build 17 writes both, and one that predates build 6's
 * byte columns writes only the GB mirror -- so either can be the one that moved last. The larger is
 * the one that has seen every write; the smaller is, at worst, behind by the writes it missed.
 */
function studioUsedOf(row, bytesCol, gbCol) {
  if (!row) return 0;
  return Math.max(Number(row[bytesCol]) || 0, Math.round((Number(row[gbCol]) || 0) * STUDIO_GIB));
}

function studioQuotaOf(row) { return studioLimitOf(row, 'quota_bytes', 'limit_gb'); }
function studioDailyQuotaOf(row) { return studioLimitOf(row, 'daily_quota_bytes', 'daily_limit_gb'); }
function studioTotalUsedOf(row) { return studioUsedOf(row, 'used_bytes', 'used_gb'); }
function studioDailyUsedOf(row) { return studioUsedOf(row, 'daily_used_bytes', 'daily_used_gb'); }

/**
 * How much to hold before writing, as a function of what is LEFT rather than of the quota.
 *
 * An eighth of the remaining room, between 64 KB and 32 MB. Far from the limit that is large, so a
 * heavy user costs few writes; near it the step shrinks with the room, so the last few megabytes are
 * written almost as they are used and a second isolate sees them before it spends them too. That
 * shrinking is what bounds the overshoot, not a smaller fixed step: a tenth of the quota -- the old
 * rule -- is ten megabytes of blindness at the one moment it matters.
 */
function studioThresholdFor(room) {
  if (!Number.isFinite(room)) return METER_MAX_THRESHOLD;
  return Math.max(METER_MIN_THRESHOLD, Math.min(METER_MAX_THRESHOLD, Math.floor(Math.max(room, 0) / 8)));
}

// ---------------------------------------------------------------------------- the meter

/**
 * A connection's handle on its person's meter.
 *
 * [stop] is how the meter ends this connection -- when the account runs out, is switched off or
 * expires, every connection of that person in this isolate is ended together, rather than each one
 * finding out on its own next re-check while the others carry on spending.
 *
 * [rowAt] is when [user] was read. A row served from the credential cache can be a few seconds old,
 * and must not overwrite a room the last flush refreshed more recently.
 */
function studioMeterJoin(user, stop, info, rowAt) {
  const key = user.uid || ('n:' + user.username);
  const now = Date.now();
  let m = METER.get(key);
  if (!m) {
    m = {
      key, uid: user.uid || null, username: user.username,
      pending: 0, inflight: 0, rollup: 0, session: 0, sessionOpen: false,
      lastFlush: now, lastRollup: now, lastPolicy: now, rowAt: 0,
      room: Infinity, threshold: METER_MAX_THRESHOLD,
      conns: new Set(), devices: new Map(),
      flushing: null, again: null, policyBusy: false,
      startedAt: now, configId: null, protocol: null, transport: null,
    };
    METER.set(key, m);
  }
  m.username = user.username;
  // A session is the stretch during which this person has at least one connection in this isolate;
  // the row for it is written by whichever connection leaves last.
  if (!m.sessionOpen) {
    m.sessionOpen = true;
    m.startedAt = now;
  }
  studioMeterRefresh(m, user, rowAt || now);
  if (info) {
    if (info.configId) m.configId = info.configId;
    if (info.protocol) m.protocol = info.protocol;
    if (info.transport) m.transport = info.transport;
  }
  const conn = { stop, closed: false };
  m.conns.add(conn);
  return { meter: m, conn };
}

/** The room this person has in this isolate right now, or Infinity when nothing is limited. */
function studioMeterRoom(user) {
  const m = METER.get(user.uid || ('n:' + user.username));
  return m ? m.room : studioRoomBytes(user, 0);
}

/** Bytes this isolate has counted for [user] and not yet seen land in D1. */
function studioMeterUnwritten(user) {
  const m = METER.get(user.uid || ('n:' + user.username));
  return m ? m.pending + m.inflight : 0;
}

/** A fresher view of the row: the room left, less what this isolate has counted and not written. */
function studioMeterRefresh(m, row, at) {
  if (!row || at < m.rowAt) return;
  m.rowAt = at;
  m.room = studioRoomBytes(row, m.pending + m.inflight);
  m.threshold = studioThresholdFor(m.room);
}

/** Count bytes. Ends every connection of this person here when the account runs out. */
function studioMeterAdd(env, ctx, h, bytes) {
  const m = h && h.meter;
  if (!m || !(bytes > 0)) return;
  m.pending += bytes;
  m.rollup += bytes;
  m.session += bytes;
  if (Number.isFinite(m.room)) {
    m.room -= bytes;
    if (m.room <= 0) {
      // Written at once, so the next admission anywhere sees the account as spent.
      studioMeterFlush(env, ctx, m, false);
      studioMeterStop(m, 'quota');
      return;
    }
  }
  if (m.pending >= m.threshold) studioMeterFlush(env, ctx, m, false);
}

function studioMeterStop(m, reason) {
  for (const c of [...m.conns]) {
    try { c.stop(reason); } catch (e) { }
  }
}

/** This connection is over. The last one out writes everything, including the session row. */
function studioMeterLeave(env, ctx, h) {
  if (!h || h.conn.closed) return;
  h.conn.closed = true;
  const m = h.meter;
  m.conns.delete(h.conn);
  if (m.conns.size === 0) studioMeterFlush(env, ctx, m, true);
}

function studioMeterUserStmt(env, m, bytes, now, returning) {
  const gb = bytes / STUDIO_GIB;
  return env.DB.prepare(
    'UPDATE users SET used_bytes = COALESCE(used_bytes,0) + ?, daily_used_bytes = COALESCE(daily_used_bytes,0) + ?, ' +
    'used_gb = COALESCE(used_gb,0) + ?, daily_used_gb = COALESCE(daily_used_gb,0) + ?, ' +
    // `updated_at` is what makes the change reach the operator's phone: the app's sync is
    // `?since=updated_at`, and a write that leaves it alone is invisible to every device.
    'updated_at = ?, last_active = ? WHERE ' + (m.uid ? 'uid = ?' : 'username = ?') +
    (returning ? ' RETURNING ' + METER_RETURNING : '')
  ).bind(bytes, bytes, gb, gb, now, now, m.uid || m.username);
}

/**
 * Write what is pending. One batch, so the user row and the daily row move together or not at all:
 * a flush that landed in one and not the other is exactly how «آمار» and the volume came apart.
 *
 * One flush at a time per person. A second request while one is in flight is remembered and run
 * after it, rather than racing it with the same bytes.
 */
function studioMeterFlush(env, ctx, m, final) {
  if (m.flushing) {
    m.again = final || m.again === 'final' ? 'final' : 'more';
    return m.flushing;
  }
  const now = Date.now();
  const n = m.pending;
  const rollupDue = final || (now - m.lastRollup >= METER_ROLLUP_MS);
  const r = rollupDue ? m.rollup : 0;
  // The session row is written once per session, by the flush that closes it. A connection that
  // authenticated and moved nothing still gets one: it is exactly what an operator is looking at
  // when somebody says "it connects and nothing happens".
  const closing = final && m.conns.size === 0 && m.sessionOpen;
  if (n <= 0 && r <= 0 && !closing) {
    if (final && m.conns.size === 0 && m.inflight === 0 && !m.sessionOpen) METER.delete(m.key);
    return null;
  }

  m.pending = 0;
  m.inflight += n;
  m.rollup -= r;
  m.lastFlush = now;
  if (rollupDue) m.lastRollup = now;
  if (closing) m.sessionOpen = false;
  const sessionBytes = closing ? m.session : 0;
  const returning = !METER_NO_RETURNING;

  const stmts = [];
  if (n > 0) stmts.push(studioMeterUserStmt(env, m, n, now, returning));
  if (r > 0 && m.uid) stmts.push(studioAccrueDaily(env, m.uid, r, closing));
  if (closing && m.uid) {
    const s = studioSessionRow(env, {
      uid: m.uid, configId: m.configId, protocol: m.protocol, transport: m.transport,
      startedAt: m.startedAt, reason: sessionBytes > 0 ? 'closed' : 'no_traffic',
    }, sessionBytes);
    if (s) stmts.push(s);
  }

  const task = (async () => {
    // A closing flush is the last chance these bytes get: nothing else in this isolate will ask for
    // them again. So it retries a D1 hiccup a couple of times, inside the same waitUntil, rather than
    // either giving up or looping on a database that is down.
    const attempts = closing ? 3 : 1;
    for (let i = 0; i < attempts; i++) {
      try {
        const res = stmts.length ? await env.DB.batch(stmts) : [];
        m.inflight -= n;
        if (closing) {
          m.session -= sessionBytes;
          studioPruneSessions(env, ctx);
        }
        const row = n > 0 && returning && res[0] && res[0].results ? res[0].results[0] : null;
        if (row) {
          studioMeterRefresh(m, row, now);
          if (m.conns.size && (row.is_active === 0 || row.deleted_at || isUserCapped(row) || m.room <= 0)) {
            studioMeterStop(m, 'capped');
          }
        }
        // Account-wide, by the hour. Coalesced in the isolate (07-traffic.js), so this is not a second
        // D1 write per flush.
        studioAccrueHourly(env, n, ctx, closing);
        break;
      } catch (e) {
        if (returning && /returning/i.test(String(e && e.message))) METER_NO_RETURNING = true;
        if (i + 1 < attempts) {
          await new Promise((res) => setTimeout(res, 2000 * (i + 1)));
          continue;
        }
        m.inflight -= n;
        m.pending += n;
        m.rollup += r;
        if (closing) m.sessionOpen = true;
      }
    }
    m.flushing = null;
    const again = m.again;
    m.again = null;
    if (again === 'final') {
      studioMeterFlush(env, ctx, m, true);
    } else if (again && m.pending >= m.threshold) {
      studioMeterFlush(env, ctx, m, false);
    } else if (m.conns.size === 0 && m.pending === 0 && m.inflight === 0 && m.rollup === 0 && !m.sessionOpen) {
      METER.delete(m.key);
    }
  })();
  m.flushing = task;
  if (ctx && ctx.waitUntil) ctx.waitUntil(task);
  return task;
}

/** The row, by uid -- the only identifier that survives a rename and is certain to find it. */
async function studioMeterReadRow(env, m) {
  return await env.DB.prepare(
    'SELECT uid, username, is_active, deleted_at, limit_gb, used_gb, daily_limit_gb, daily_used_gb, daily_reset_at, ' +
    'quota_bytes, used_bytes, daily_quota_bytes, daily_used_bytes, expiry_days, expires_at, created_at, ' +
    'quota_reset_policy, quota_reset_at FROM users WHERE ' + (m.uid ? 'uid = ?' : 'username = ?')
  ).bind(m.uid || m.username).first();
}

/**
 * The periodic half, driven by each connection's own heartbeat and rate-limited here, so a person
 * with forty open connections costs one re-read a minute rather than forty.
 *
 * Writes the pending tail on a timer -- the part that was missing: without it, a connection that
 * stays open never reaches D1 below its threshold -- and re-reads the row so an account that was
 * switched off, renewed, reset or has expired is acted on while the session is open.
 */
async function studioMeterTick(env, ctx, h) {
  const m = h && h.meter;
  if (!m) return;
  const now = Date.now();
  if (m.pending > 0 && now - m.lastFlush >= METER_FLUSH_MS) studioMeterFlush(env, ctx, m, false);
  else if (m.rollup > 0 && now - m.lastRollup >= METER_ROLLUP_MS) studioMeterFlush(env, ctx, m, false);

  studioPresenceTouchAll(env, ctx, m);

  if (m.policyBusy || now - m.lastPolicy < METER_POLICY_MS) return;
  m.policyBusy = true;
  m.lastPolicy = now;
  try {
    const row = await studioMeterReadRow(env, m);
    if (!row || row.is_active === 0 || row.deleted_at) {
      studioMeterStop(m, 'disabled');
      return;
    }
    await resetDailyIfDue(env, row);
    await studioResetQuotaIfDue(env, row);
    studioMeterRefresh(m, row, now);
    if (isUserCapped(row) || m.room <= 0) {
      // Only expiry deactivates. A quota -- daily or total -- ends the sessions and leaves the account
      // alone: the daily one lifts itself at the rollover, the total one the moment it is renewed.
      if (isUserExpired(row)) {
        try {
          await env.DB.prepare('UPDATE users SET is_active = 0, last_active = 0 WHERE ' + (m.uid ? 'uid = ?' : 'username = ?'))
            .bind(m.uid || m.username).run();
        } catch (e) { }
      }
      studioMeterStop(m, 'capped');
    }
  } catch (e) {
    // A D1 hiccup is not a reason to end anybody's session. The next tick asks again.
  } finally {
    m.policyBusy = false;
  }
}

/** Everything this isolate holds, written now. What the legacy sweep and a shutting-down path call. */
function studioMeterFlushAll(env, ctx) {
  for (const m of METER.values()) {
    if (m.pending > 0 || m.rollup > 0) studioMeterFlush(env, ctx, m, m.conns.size === 0);
  }
}
