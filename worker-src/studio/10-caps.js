/**
 * Whether this account has run out of TIME, as opposed to out of volume.
 *
 * The distinction is not pedantic, and getting it wrong is destructive. `isUserCapped` answers
 * "refuse this connection", and four different conditions can make it true -- but only expiry means
 * the account is finished. A quota, daily or lifetime, is a reason to drop the connection and
 * nothing more: the daily one clears by itself at the next rollover, and the lifetime one clears
 * the moment the operator renews.
 *
 * The WS path reacts to expiry by writing `is_active = 0`. So a version of that code which simply
 * asked `isUserCapped` would deactivate every user who reached their daily limit -- turning a cap
 * that is supposed to lift itself overnight into an account someone has to be asked to restore by
 * hand. This function is what keeps the two answers apart.
 */
/**
 * When this account runs out of time, in absolute milliseconds, or 0 for never.
 *
 * **`expires_at` wins over `expiry_days`, and getting that order wrong cut renewed users off.**
 * The legacy model counted from `created_at`, which is why renewing used to mean deleting and
 * recreating someone — and losing their link. Build 6 stores an absolute `expires_at` and keeps
 * `expiry_days` as a mirror so a rollback to build 5 still sees a limit. But `:renew` writes that
 * mirror as *days remaining from now*, while this function used to measure it *from creation*: a
 * user 29 days into a 30-day term who renewed for 15 more got `expiry_days = 16`, which read as
 * "expired sixteen days after they were created" — so the tunnel refused them immediately, while
 * every screen showed them 15 days of credit. Nothing in the API layer could see it; only the data
 * plane reads these columns.
 *
 * The fallback stays for a build-5 row that was never migrated, where `expires_at` does not exist.
 */
function studioExpiryAt(user) {
  if (!user) return 0;
  if (user.expires_at) return Number(user.expires_at) || 0;
  if (!user.expiry_days || !user.created_at) return 0;
  return new Date(user.created_at).getTime() + (user.expiry_days * 24 * 60 * 60 * 1000);
}

function isUserExpired(user) {
  const at = studioExpiryAt(user);
  return at > 0 && Date.now() > at;
}

/**
 * Roll the daily counter over if a day has passed, so it can be tested meaningfully.
 *
 * Mirrors what the XHTTP path already does before its own admission check (09-xhttp.js). Without
 * it, `daily_used_gb` is a number that only ever goes up and the daily cap becomes a permanent one.
 */
async function resetDailyIfDue(env, user) {
  const now = Date.now();
  if ((now - (user.daily_reset_at || 0)) <= 86400000) return;
  try {
    // BOTH counters. The byte column is what every screen and every subscription header reads, so
    // a rollover that cleared only the GB mirror would leave the number the person sees stuck at
    // yesterday's total while the one that enforces the cap started again at zero.
    // By uid where there is one: the username can change under a live session.
    await env.DB.prepare(
      "UPDATE users SET daily_used_gb = 0, daily_used_bytes = 0, daily_reset_at = ? WHERE " +
      (user.uid ? "uid = ?" : "username = ?")
    ).bind(now, user.uid || user.username).run();
    user.daily_used_gb = 0;
    user.daily_used_bytes = 0;
    user.daily_reset_at = now;
  } catch (e) { }
}

/**
 * Refill the lifetime quota when its period comes round.
 *
 * Distinct from `resetDailyIfDue`, and the two are easy to confuse. That one clears the *daily
 * sub-cap* — a ceiling on how much may be used in a day, on top of the total. This one clears the
 * **total**, and it is what «هر ماه صفر می‌شود» on the subscriber's page means: a monthly
 * arrangement whose volume comes back rather than running out once.
 *
 * Without it, `quota_reset_policy` was a column the operator could set, the page could describe, and
 * nothing acted on — the plan editor offers the choice, so the choice has to be real.
 *
 * The next boundary is computed by **advancing** the stored one rather than adding a period to now,
 * so a user who does not connect for two months lands back on the same day of the cycle instead of
 * drifting by however long they were away.
 */
async function studioResetQuotaIfDue(env, user) {
  const policy = user && user.quota_reset_policy;
  if (!policy || policy === 'none') return;
  const period = policy === 'daily' ? 86400000
    : policy === 'weekly' ? 7 * 86400000
    : policy === 'monthly' ? 30 * 86400000
    : 0;
  if (!period) return;

  const now = Date.now();
  let next = Number(user.quota_reset_at) || 0;
  // First time through: set the boundary and refill nothing. A user whose period has never been
  // recorded has not reached the end of one.
  if (!next) {
    try {
      await env.DB.prepare('UPDATE users SET quota_reset_at = ? WHERE uid = ?').bind(now + period, user.uid).run();
      user.quota_reset_at = now + period;
    } catch (e) { }
    return;
  }
  if (now < next) return;

  while (next <= now) next += period;
  try {
    await env.DB.prepare(
      'UPDATE users SET used_bytes = 0, used_gb = 0, quota_reset_at = ?, updated_at = ? WHERE uid = ?'
    ).bind(next, now, user.uid).run();
    user.used_bytes = 0;
    user.used_gb = 0;
    user.quota_reset_at = next;
  } catch (e) { }
}

function isUserCapped(user) {
  if (!user || user.is_active === 0) return true;
  // In BYTES since build 18 (07a-meter.js › studioQuotaOf). The GB mirrors were the source of truth
  // for admission while every screen read the byte columns, and the two drifted: a plan's daily cap
  // wrote `daily_quota_bytes` without its mirror, so it was shown everywhere and enforced nowhere.
  // The mirrors are still read for a row that has no byte figure, which is a build-5 row.
  const quota = studioQuotaOf(user);
  if (quota > 0 && studioTotalUsedOf(user) >= quota) return true;
  const daily = studioDailyQuotaOf(user);
  if (daily > 0 && studioDailyUsedOf(user) >= daily) return true;
  if (isUserExpired(user)) return true;
  return false;
}

/**
 * How many bytes this user may still move, as of the row just read: the smaller of what is left of
 * the total and of today's cap, or Infinity when neither is set. The caller subtracts what this
 * isolate has counted and not yet written ([pending]).
 */
function studioRoomBytes(user, pending) {
  let room = Infinity;
  const quota = studioQuotaOf(user);
  if (quota > 0) room = Math.min(room, quota - studioTotalUsedOf(user));
  const daily = studioDailyQuotaOf(user);
  if (daily > 0) room = Math.min(room, daily - studioDailyUsedOf(user));
  return room - (pending || 0);
}

/** How much to hold before writing, for this row as it stands. See `studioThresholdFor`. */
function studioCommitThreshold(user) {
  return studioThresholdFor(studioRoomBytes(user, 0));
}

/**
 * Everything that decides whether an authenticated connection may start, for every transport, in
 * the order that matters: switched off, the day's rollover and the quota's own period (BEFORE the
 * test, or a refill that is due still refuses), the quota and the term, the on-first-connect clock,
 * then the device limit.
 *
 * [found] is what `studioCachedUser` returned. A refusal on a CACHED row is re-read once before it
 * stands, so a renewal made seconds ago is honoured by the connection that tries it.
 *
 * Returns `{ ok: true, user, presence, rowAt }` or `{ ok: false, kind, uid, detail }`, where `kind` is
 * the activity-log name the operator reads.
 */
async function studioAdmitConnection(env, ctx, found, request) {
  let user = found && found.user;
  let rowAt = found ? found.at : Date.now();
  if (!user || user.is_active === 0 || user.deleted_at) {
    return {
      ok: false,
      kind: user ? 'tunnel.disabled' : 'tunnel.unknown_credential',
      uid: user && (user.uid || user.username),
      detail: user ? user.username : null,
    };
  }

  await resetDailyIfDue(env, user);
  await studioResetQuotaIfDue(env, user);

  let room = studioMeterRoom(user);
  if ((isUserCapped(user) || room <= 0) && found.cached && found.load) {
    studioDropCachedUser(found.kind, found.key);
    const fresh = await found.load();
    if (fresh) {
      user = fresh;
      rowAt = Date.now();
      await resetDailyIfDue(env, user);
      await studioResetQuotaIfDue(env, user);
      // The fresh row, less what this isolate has counted and not yet written.
      room = studioRoomBytes(user, studioMeterUnwritten(user));
    }
  }

  if (!user || user.is_active === 0) {
    return { ok: false, kind: 'tunnel.disabled', uid: user && (user.uid || user.username), detail: user && user.username };
  }
  if (isUserCapped(user) || room <= 0) {
    // Named per reason rather than logged as one "refused": a quota that clears overnight, a quota
    // that needs a top-up and a term that has ended are three different things for the operator.
    const kind = isUserExpired(user) ? 'tunnel.expired'
      : (studioDailyQuotaOf(user) > 0 && studioDailyUsedOf(user) >= studioDailyQuotaOf(user)
        ? 'tunnel.daily_quota' : 'tunnel.quota');
    // Only expiry deactivates the account. A quota refuses this connection and leaves the account
    // alone: the daily one lifts itself at the rollover, the total one the moment it is renewed.
    if (isUserExpired(user)) {
      try {
        await env.DB.prepare('UPDATE users SET is_active = 0, last_active = 0 WHERE ' + (user.uid ? 'uid = ?' : 'username = ?'))
          .bind(user.uid || user.username).run();
      } catch (e) { }
    }
    return { ok: false, kind, uid: user.uid || user.username, detail: user.username };
  }

  // The clock for an on-first-connect term starts HERE, on the connection that starts it.
  await studioStartOnFirstConnect(env, user);

  // The device limit (13a-session-do.js). Asked only for someone who has one.
  const presence = await studioPresenceAdmit(env, ctx, user, request);
  if (!presence.allow) {
    return { ok: false, kind: 'tunnel.' + (presence.reason || 'refused'), uid: user.uid || user.username, detail: user.username };
  }
  return { ok: true, user, presence, rowAt };
}

/**
 * Start the clock for a user whose term begins at their first connection.
 *
 * One D1 write, once in a user's life, on the connection that starts them — which is the only way
 * "expires N days after they first use it" can be represented at all. Until it fires, the user has
 * no `expires_at` and is therefore not expired: a link that has never been used does not run out,
 * which is the entire point of the mode.
 *
 * The GB mirror moves in the same statement so a rolled-back build 5, which cannot see
 * `expires_at`, still enforces a limit rather than serving an account with no end date.
 */
async function studioStartOnFirstConnect(env, user) {
  if (!user || user.expiry_mode !== 'on_first_connect') return;
  if (user.first_connect_at) return;
  const days = Number(user.activation_days) || 0;
  const now = Date.now();
  const expiresAt = days > 0 ? now + days * 86400000 : null;
  try {
    await env.DB.prepare(
      'UPDATE users SET first_connect_at = ?, expires_at = ?, expiry_days = ?, updated_at = ? WHERE uid = ?'
    ).bind(now, expiresAt, days > 0 ? days : null, now, user.uid).run();
    user.first_connect_at = now;
    user.expires_at = expiresAt;
    user.expiry_days = days > 0 ? days : null;
  } catch (e) { }
}

// ==========================================================
// ۸. توابع کمکی موتور (UTILITIES & HELPERS)
// ==========================================================
