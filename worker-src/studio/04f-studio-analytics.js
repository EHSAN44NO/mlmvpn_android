// ==========================================================
// Config Studio  —  /v1/analytics and /v1/audit
// ==========================================================
//
// Everything here reads a **rollup**, never the users table. The distinction is the whole design:
// D1 bills rows read, and an analytics screen is the one place where a careless query looks harmless
// and costs the whole table on every open. `usage_hourly` is 24 rows a day whatever the user count,
// `usage_daily` is one row per person per day they actually connected, and both are indexed by the
// column their range filter uses.
//
// **There is no fleet-wide audit feed and there will not be one.** With an uncapped number of
// Cloudflare accounts a merged log is O(N) requests per page, and a merged log that quietly drops
// the accounts that did not answer is worse than no merged log at all. Audit is scoped to one
// installation, and the screen says so (plan §A.3, §A.9).

/** Ranges the caller may ask for, as days. Anything else is refused rather than clamped silently. */
const STUDIO_RANGES = { '24h': 1, '7d': 7, '30d': 30, '90d': 90 };

/**
 * `GET /v1/analytics/traffic?range=7d&granularity=hour|day`
 *
 * Hourly comes from the account-wide bucket table; daily is summed from the per-user one. Two
 * sources rather than one because they answer different questions and cost differently: the hourly
 * table cannot say who, and the daily table cannot say when within a day.
 */
async function studioAnalyticsTraffic(env, url) {
  const days = STUDIO_RANGES[url.searchParams.get('range') || '7d'];
  if (!days) return studioErr('bad_request', 'unknown range', 400);
  const hourly = url.searchParams.get('granularity') !== 'day';
  const since = Date.now() - days * 86400000;

  const res = hourly
    ? await env.DB.prepare(
        `SELECT bucket_ts AS ts, COALESCE(up_bytes,0) AS up, COALESCE(down_bytes,0) AS down
           FROM usage_hourly WHERE bucket_ts >= ? ORDER BY bucket_ts ASC LIMIT 2200`
      ).bind(since).all()
    // Grouped rather than one row per user per day: this endpoint answers "how much, when", and
    // "who" is `top-users` below. Bounded by the range, and `usage_daily`'s primary key starts with
    // the user, so the day filter is the one that has to keep the scan small -- which the range
    // does, because the table only has a row for a day somebody actually connected.
    : await env.DB.prepare(
        `SELECT day AS ts, COALESCE(SUM(up_bytes),0) AS up, COALESCE(SUM(down_bytes),0) AS down
           FROM usage_daily WHERE day >= ? GROUP BY day ORDER BY day ASC LIMIT 400`
      ).bind(since).all();

  const rows = (res && res.results) || [];
  return studioJson({
    granularity: hourly ? 'hour' : 'day',
    range_days: days,
    points: rows.map((r) => ({ ts: r.ts, up_bytes: r.up, down_bytes: r.down })),
    total_bytes: rows.reduce((sum, r) => sum + (r.up || 0) + (r.down || 0), 0),
  });
}

/**
 * `GET /v1/analytics/top-users?range=7d&limit=10`
 *
 * Summed from `usage_daily`, which is why that table is written at all. The obvious alternative --
 * ordering `users` by `used_bytes` -- answers a different question: it ranks by lifetime total, so
 * the list is whoever has been a subscriber longest rather than whoever is using it now.
 */
async function studioAnalyticsTopUsers(env, url) {
  const days = STUDIO_RANGES[url.searchParams.get('range') || '7d'];
  if (!days) return studioErr('bad_request', 'unknown range', 400);
  const limit = Math.min(Math.max(parseInt(url.searchParams.get('limit') || '10', 10) || 10, 1), 50);
  const since = Date.now() - days * 86400000;

  const res = await env.DB.prepare(
    `SELECT d.user_uid AS uid, u.username AS username,
            COALESCE(SUM(d.up_bytes),0) + COALESCE(SUM(d.down_bytes),0) AS bytes,
            COALESCE(SUM(d.sessions),0) AS sessions
       FROM usage_daily d LEFT JOIN users u ON u.uid = d.user_uid
      WHERE d.day >= ?
      GROUP BY d.user_uid
      ORDER BY bytes DESC
      LIMIT ?`
  ).bind(since, limit).all();

  return studioJson({
    range_days: days,
    items: ((res && res.results) || []).map((r) => ({
      id: r.uid, username: r.username || null, bytes: r.bytes, sessions: r.sessions,
    })),
  });
}

/**
 * `GET /v1/analytics/summary?range=7d`
 *
 * The figures that are one number each, in one round trip. Four `COUNT`s and a `SUM`, every one of
 * them over an index and every one of them bounded by the range — which is what keeps this endpoint
 * off the "never scan a table in a request path" list (§4.2 rule 3).
 *
 * **The error rate is refusals over connections, not errors over requests.** A tunnel that is
 * refused is the only failure this engine can actually observe: a connection that drops halfway
 * looks identical to a person closing their laptop, and counting those would produce a number that
 * rises when people go to bed. So it is named for what it counts.
 */
async function studioAnalyticsSummary(env, url) {
  const days = STUDIO_RANGES[url.searchParams.get('range') || '7d'];
  if (!days) return studioErr('bad_request', 'unknown range', 400);
  const since = Date.now() - days * 86400000;

  const rows = await env.DB.batch([
    // `created_at` is a TEXT timestamp inherited from the legacy schema, so the comparison is made
    // in its own units rather than by casting every row: `datetime(?, 'unixepoch')` is computed
    // once and `ix_users_status_created` covers the scan.
    env.DB.prepare(
      "SELECT COUNT(*) AS n FROM users WHERE deleted_at IS NULL AND created_at >= datetime(?, 'unixepoch')"
    ).bind(Math.floor(since / 1000)),
    env.DB.prepare('SELECT COUNT(*) AS n FROM sessions WHERE ended_at >= ?').bind(since),
    env.DB.prepare('SELECT COUNT(DISTINCT user_uid) AS n FROM sessions WHERE ended_at >= ?').bind(since),
    env.DB.prepare(
      "SELECT COUNT(*) AS n FROM activity_log WHERE ts >= ? AND severity IN ('warn','error')"
    ).bind(since),
    env.DB.prepare(
      'SELECT COALESCE(SUM(up_bytes),0) + COALESCE(SUM(down_bytes),0) AS b FROM sessions WHERE ended_at >= ?'
    ).bind(since),
  ]);
  const at = (i, key) => {
    const r = rows[i] && rows[i].results && rows[i].results[0];
    return (r && r[key]) || 0;
  };

  const sessions = at(1, 'n');
  const refused = at(3, 'n');
  return studioJson({
    range_days: days,
    new_users: at(0, 'n'),
    sessions,
    active_users: at(2, 'n'),
    refusals: refused,
    // Out of attempts, not out of sessions: a refused connection never became a session, so the
    // denominator has to include it or a period where everything was refused reads as zero per cent.
    refusal_rate: sessions + refused > 0 ? refused / (sessions + refused) : 0,
    bytes: at(4, 'b'),
  });
}

/**
 * `GET /v1/analytics/breakdown?range=7d`
 *
 * What the traffic went through: per config, per protocol, per transport — all three from
 * `sessions`, which is the only table that records any of them.
 *
 * **There is no per-endpoint breakdown and there cannot be one.** A Worker never learns which
 * Cloudflare address the client dialled — the edge routes by server name and the address it was
 * reached on is not in the request or in `request.cf`. Rather than omit it silently, the response
 * carries `nodes_measurable: false`, so the screen can say why the section is missing instead of
 * leaving the operator to wonder whether it failed to load.
 */
async function studioAnalyticsBreakdown(env, url) {
  const days = STUDIO_RANGES[url.searchParams.get('range') || '7d'];
  if (!days) return studioErr('bad_request', 'unknown range', 400);
  const since = Date.now() - days * 86400000;
  const limit = Math.min(Math.max(parseInt(url.searchParams.get('limit') || '10', 10) || 10, 1), 50);

  const rows = await env.DB.batch([
    env.DB.prepare(
      `SELECT s.config_id AS id, c.label AS label, u.username AS username,
              COUNT(*) AS sessions,
              COALESCE(SUM(s.up_bytes),0) + COALESCE(SUM(s.down_bytes),0) AS bytes
         FROM sessions s
         LEFT JOIN configs c ON c.id = s.config_id
         LEFT JOIN users u ON u.uid = s.user_uid
        WHERE s.ended_at >= ? AND s.config_id IS NOT NULL
        GROUP BY s.config_id ORDER BY bytes DESC LIMIT ?`
    ).bind(since, limit),
    env.DB.prepare(
      `SELECT protocol AS k, COUNT(*) AS sessions,
              COALESCE(SUM(up_bytes),0) + COALESCE(SUM(down_bytes),0) AS bytes
         FROM sessions WHERE ended_at >= ? AND protocol IS NOT NULL
        GROUP BY protocol ORDER BY bytes DESC LIMIT 10`
    ).bind(since),
    env.DB.prepare(
      `SELECT transport AS k, COUNT(*) AS sessions,
              COALESCE(SUM(up_bytes),0) + COALESCE(SUM(down_bytes),0) AS bytes
         FROM sessions WHERE ended_at >= ? AND transport IS NOT NULL
        GROUP BY transport ORDER BY bytes DESC LIMIT 10`
    ).bind(since),
  ]);
  const list = (i) => (rows[i] && rows[i].results) || [];

  return studioJson({
    range_days: days,
    configs: list(0).map((r) => ({
      id: r.id, label: r.label || null, username: r.username || null,
      sessions: r.sessions, bytes: r.bytes,
    })),
    protocols: list(1).map((r) => ({ key: r.k, sessions: r.sessions, bytes: r.bytes })),
    transports: list(2).map((r) => ({ key: r.k, sessions: r.sessions, bytes: r.bytes })),
    // Not "no data yet". The distinction matters on a screen: one is answered by waiting, and the
    // other never will be.
    nodes_measurable: false,
  });
}

/**
 * `GET /v1/analytics/sessions?range=7d&granularity=day`
 *
 * Connections over time, which `usage_hourly.sessions` can also answer — but only account-wide and
 * only by the hour. This one is bucketed from `sessions` itself, so it can be narrowed to one
 * person, which is what the connection history on a user's page is drawn from.
 */
async function studioAnalyticsSessions(env, url) {
  const days = STUDIO_RANGES[url.searchParams.get('range') || '7d'];
  if (!days) return studioErr('bad_request', 'unknown range', 400);
  const since = Date.now() - days * 86400000;
  const userId = url.searchParams.get('user_id');
  const bucket = url.searchParams.get('granularity') === 'hour' ? 3600000 : 86400000;

  const where = ['ended_at >= ?'];
  const binds = [since];
  if (userId) { where.push('user_uid = ?'); binds.push(userId); }

  const res = await env.DB.prepare(
    `SELECT (ended_at / ?) * ? AS ts, COUNT(*) AS sessions,
            COALESCE(SUM(up_bytes),0) + COALESCE(SUM(down_bytes),0) AS bytes
       FROM sessions WHERE ${where.join(' AND ')}
      GROUP BY ts ORDER BY ts ASC LIMIT 800`
  ).bind(bucket, bucket, ...binds).all();

  return studioJson({
    range_days: days,
    granularity: bucket === 3600000 ? 'hour' : 'day',
    points: ((res && res.results) || []).map((r) => ({
      ts: r.ts, sessions: r.sessions, bytes: r.bytes,
    })),
  });
}

/**
 * `GET /v1/analytics/users?range=30d`
 *
 * How many people were added, by the day. The one chart whose source is `users` rather than a
 * rollup, and it is safe for the same reason the summary's first count is: the range bounds it and
 * `ix_users_status_created` covers it.
 */
async function studioAnalyticsUsers(env, url) {
  const days = STUDIO_RANGES[url.searchParams.get('range') || '30d'];
  if (!days) return studioErr('bad_request', 'unknown range', 400);
  const sinceSec = Math.floor((Date.now() - days * 86400000) / 1000);

  const res = await env.DB.prepare(
    // Grouped by the date text rather than by a computed millisecond bucket, because `created_at`
    // is a TEXT timestamp: `date()` uses the index's own collation and a CAST per row would not.
    `SELECT date(created_at) AS day, COUNT(*) AS n
       FROM users
      WHERE deleted_at IS NULL AND created_at >= datetime(?, 'unixepoch')
      GROUP BY day ORDER BY day ASC LIMIT 400`
  ).bind(sinceSec).all();

  return studioJson({
    range_days: days,
    points: ((res && res.results) || []).map((r) => ({
      // Back to milliseconds, so every chart in the app reads one unit.
      ts: Date.parse(r.day + 'T00:00:00Z') || 0,
      users: r.n,
    })),
  });
}

/**
 * `GET /v1/users/{id}/sessions?limit=20`
 *
 * One person's connection history: when, for how long, through which config, and how much moved.
 *
 * The row that says `no_traffic` is the useful one and is deliberately not filtered out — a
 * connection that authenticated and carried nothing is exactly what an operator is looking at when
 * somebody says "it connects and nothing happens", and it is invisible in every other table.
 */
async function studioUserSessions(env, uid, url) {
  const limit = Math.min(Math.max(parseInt(url.searchParams.get('limit') || '20', 10) || 20, 1), 100);
  const res = await env.DB.prepare(
    `SELECT s.id, s.config_id, c.label AS config_label, s.protocol, s.transport,
            s.started_at, s.ended_at, s.up_bytes, s.down_bytes, s.close_reason
       FROM sessions s LEFT JOIN configs c ON c.id = s.config_id
      WHERE s.user_uid = ? ORDER BY s.ended_at DESC LIMIT ?`
  ).bind(uid, limit).all();

  return studioJson({
    items: ((res && res.results) || []).map((r) => ({
      id: r.id,
      config_id: r.config_id || null,
      config_label: r.config_label || null,
      protocol: r.protocol || null,
      transport: r.transport || null,
      started_at: r.started_at || 0,
      ended_at: r.ended_at || 0,
      bytes: (r.up_bytes || 0) + (r.down_bytes || 0),
      close_reason: r.close_reason || null,
    })),
  });
}

/**
 * `GET /v1/audit?cursor=&action=&target_id=`
 *
 * What a **person** did, as opposed to what the system observed — the two logs are separate tables
 * on purpose, because this one is low volume and worth keeping while `activity_log` is high volume
 * and pruned hard.
 *
 * Keyset on the descending primary key, not OFFSET: an audit log grows without bound and OFFSET
 * makes page 50 cost the price of the first fifty.
 */
async function studioAuditList(env, url) {
  const limit = Math.min(Math.max(parseInt(url.searchParams.get('limit') || '50', 10) || 50, 1), 200);
  const before = parseInt(url.searchParams.get('cursor') || '0', 10) || 0;
  const action = url.searchParams.get('action');
  const target = url.searchParams.get('target_id');

  const where = [];
  const binds = [];
  if (before > 0) { where.push('id < ?'); binds.push(before); }
  if (action) { where.push('action = ?'); binds.push(action); }
  if (target) { where.push('target_id = ?'); binds.push(target); }
  const clause = where.length ? 'WHERE ' + where.join(' AND ') : '';

  const res = await env.DB.prepare(
    `SELECT id, ts, actor, action, target_type, target_id, after_json
       FROM audit_log ${clause} ORDER BY id DESC LIMIT ?`
  ).bind(...binds, limit).all();

  const rows = (res && res.results) || [];
  return studioJson({
    items: rows.map((r) => ({
      id: r.id, ts: r.ts, actor: r.actor, action: r.action,
      target_type: r.target_type, target_id: r.target_id,
      // The before/after payloads are not sent in a list. They can be large, they are the part
      // most likely to contain something an operator would not want on a screen in a cafe, and a
      // list is for finding the row rather than for reading it.
      summary: r.after_json ? String(r.after_json).slice(0, 200) : null,
    })),
    // Null on a short page, so the app stops rather than asking for an empty one.
    next_cursor: rows.length === limit ? rows[rows.length - 1].id : null,
  });
}

/**
 * `GET /v1/activity?severity=error|warn&cursor=&limit=`
 *
 * What the SYSTEM observed: a tunnel refused, a credential nobody owns, a device cap hit. The
 * companion to `/v1/audit`, which holds what a person decided.
 *
 * They are separate endpoints because they are separate questions with separate retention. An
 * operator asking "what did I change last week" and one asking "why is this person being cut off"
 * want different rows, and merging them would bury the second in the first -- this table is
 * written by the data plane and pruned to a few thousand rows, and the audit log is kept.
 *
 * `severity` is a filter rather than a level: passing `warn` returns warnings AND errors, because
 * an operator narrowing down to "problems" does not mean "problems, but not the bad ones".
 */
async function studioActivityList(env, url) {
  const limit = Math.min(Math.max(parseInt(url.searchParams.get('limit') || '50', 10) || 50, 1), 200);
  const before = parseInt(url.searchParams.get('cursor') || '0', 10) || 0;
  const severity = url.searchParams.get('severity');

  const where = [];
  const binds = [];
  // Every column is qualified, and `a.id` in particular is not optional: this query joins `users`,
  // which has an `id` of its own, so an unqualified `id < ?` is ambiguous and SQLite refuses the
  // whole statement. It only shows up on the SECOND page, because the first one passes no cursor
  // and never builds the clause -- which is exactly the kind of bug that ships.
  if (before > 0) { where.push('a.id < ?'); binds.push(before); }
  // One person's own feed. The fleet-wide list answers "what is going wrong"; this answers "why
  // is THIS person being cut off", which is the question actually asked, and it is the same rows
  // rather than a second table -- the data plane already stamps every refusal with the uid.
  const userId = url.searchParams.get('user_id');
  if (userId) { where.push('a.user_uid = ?'); binds.push(userId); }
  if (severity === 'error') { where.push("a.severity = 'error'"); }
  else if (severity === 'warn') { where.push("a.severity IN ('warn','error')"); }
  const clause = where.length ? 'WHERE ' + where.join(' AND ') : '';

  const res = await env.DB.prepare(
    `SELECT a.id, a.ts, a.kind, a.user_uid, a.severity, a.detail, u.username
       FROM activity_log a LEFT JOIN users u ON u.uid = a.user_uid
       ${clause} ORDER BY a.id DESC LIMIT ?`
  ).bind(...binds, limit).all();

  const rows = (res && res.results) || [];
  return studioJson({
    items: rows.map((r) => ({
      id: r.id, ts: r.ts, kind: r.kind, severity: r.severity || 'info',
      user_id: r.user_uid || null,
      // Resolved here rather than on the client: the app holds a local index of users, but a row
      // about someone deleted since would otherwise show a bare uid nobody can read.
      username: r.username || null,
      detail: r.detail || null,
    })),
    next_cursor: rows.length === limit ? rows[rows.length - 1].id : null,
  });
}

/** Returns null when the path is not its business, so `studioHandle` stays a list. */
async function studioAnalyticsRoute(request, env, path, method) {
  if (method !== 'GET') return null;
  const url = new URL(request.url);
  if (path === '/analytics/traffic') return await studioAnalyticsTraffic(env, url);
  if (path === '/analytics/top-users') return await studioAnalyticsTopUsers(env, url);
  if (path === '/analytics/summary') return await studioAnalyticsSummary(env, url);
  if (path === '/analytics/breakdown') return await studioAnalyticsBreakdown(env, url);
  if (path === '/analytics/sessions') return await studioAnalyticsSessions(env, url);
  if (path === '/analytics/users') return await studioAnalyticsUsers(env, url);
  const userSessions = path.match(/^\/users\/([^/]+)\/sessions$/);
  if (userSessions) return await studioUserSessions(env, userSessions[1], url);
  if (path === '/audit') return await studioAuditList(env, url);
  if (path === '/activity') return await studioActivityList(env, url);
  return null;
}
