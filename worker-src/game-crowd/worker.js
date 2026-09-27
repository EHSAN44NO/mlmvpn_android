/**
 * Game Booster crowd service -- what every player's boosts taught, for the next player on the
 * same operator.
 *
 * The app sends, for a sample of boost sessions, one short anonymous row: which game, which of its
 * regions, which plan (direct / DNS-only / WARP), whether it worked, the typical ping, the kind of
 * game on that line, and which anti-sanction DNS services opened its sign-in. No address, no
 * device id: the operator is read HERE from Cloudflare's own `request.cf.asn`, and the install id
 * is a random value used only to cap how much one install can write in a day.
 *
 * Rows become counters keyed by (day, ASN, game, region, plan) -- never individual records -- and
 * about every two hours those counters become a snapshot per ASN, signed with Ed25519 so a copy
 * served from anywhere can be trusted. The app reads its own ASN's slice and the global one.
 *
 * Built for the Workers Free plan, whose limits are the design:
 *   - 100,000 requests/day for the whole account (shared with the account's other Workers), so
 *     clients report a sample, batched, at most once a day, and read the snapshot at most every
 *     twelve hours;
 *   - 10 ms CPU per invocation, so aggregation is a GROUP BY inside D1, not a loop in here;
 *   - 50 D1 queries per invocation, so writes are multi-row upserts (≤ 100 bound parameters);
 *   - D1: 100,000 rows written and 5,000,000 read per day, so the snapshot is precomputed per ASN
 *     and read by primary key -- two rows per client read.
 *
 * The snapshot is rebuilt lazily, by the first read that finds it stale, under a lock row -- no
 * Cron Trigger needed (an account has five).
 *
 * Bindings: DB (D1). Secrets: SIGN_JWK (Ed25519 private key, JWK JSON), ADMIN_TOKEN, SALT.
 * Plain var: APP_KEY (the HMAC key the app signs reports with -- shipped in the app, so it only
 * raises the bar; the real protection is the per-install and per-address daily caps).
 */

const REBUILD_AFTER_MS = 2 * 60 * 60 * 1000;
const LOCK_MS = 5 * 60 * 1000;
const WINDOW_DAYS = 14;
const KEEP_DAYS = 30;
const MAX_ROWS = 8;
const MAX_PROV = 16;
const PER_INSTALL_PER_DAY = 4;
// Carriers put thousands of subscribers behind one address (CGNAT): this only stops a flood.
const PER_ADDRESS_PER_DAY = 400;
const MAX_ASNS = 200;
const MIN_N = 3;
const DEFAULT_RATE = 0.3;
const PLANS = new Set(['D', 'DD', 'W3', 'W2', 'WG']);
const TS_WINDOW_MS = 15 * 60 * 1000;
const MAX_BODY = 16 * 1024;

const json = (obj, status = 200, extra = {}) =>
  new Response(JSON.stringify(obj), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store', ...extra },
  });

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    try {
      if (request.method === 'GET' && url.pathname === '/s') return await snapshot(request, env, ctx);
      if (request.method === 'POST' && url.pathname === '/r') return await report(request, env);
      if (url.pathname.startsWith('/admin/')) return await admin(request, env, url);
      return new Response('ok', { status: 200, headers: { 'content-type': 'text/plain' } });
    } catch (e) {
      console.log(JSON.stringify({ level: 'error', path: url.pathname, message: String(e && e.message || e) }));
      return json({ ok: false, error: 'internal' }, 500);
    }
  },
};

// ── reading ─────────────────────────────────────────────────────────────────────────────────

async function snapshot(request, env, ctx) {
  const asn = asnOf(request);
  const { results } = await env.DB
    .prepare('SELECT k, body, sig, at FROM snap WHERE k IN (?1, ?2)')
    .bind('all', 'as' + asn)
    .all();
  const all = results.find((r) => r.k === 'all') || null;
  const mine = results.find((r) => r.k === 'as' + asn) || null;
  const now = Date.now();
  if (!all || now - all.at > REBUILD_AFTER_MS) {
    ctx.waitUntil(rebuild(env, now).catch((e) => console.log(JSON.stringify({ level: 'error', event: 'rebuild', message: String(e && e.message || e) }))));
  }
  return json({
    asn,
    all: all ? { b: all.body, s: all.sig } : null,
    mine: mine ? { b: mine.body, s: mine.sig } : null,
  });
}

// ── writing ─────────────────────────────────────────────────────────────────────────────────

async function report(request, env) {
  const text = await readCapped(request, MAX_BODY);
  if (text === null) return json({ ok: false, error: 'too large' }, 413);
  const id = request.headers.get('x-install') || '';
  const ts = request.headers.get('x-ts') || '';
  const sig = request.headers.get('x-sig') || '';
  if (!/^[a-f0-9]{32}$/.test(id) || !/^\d{10,14}$/.test(ts) || !/^[a-f0-9]{64}$/.test(sig)) {
    return json({ ok: false, error: 'bad headers' }, 400);
  }
  if (Math.abs(Date.now() - Number(ts)) > TS_WINDOW_MS) return json({ ok: false, error: 'clock' }, 400);
  const want = await hmacHex(env.APP_KEY, `${id}.${ts}.${text}`);
  if (!timingSafeEqualHex(want, sig)) return json({ ok: false, error: 'signature' }, 403);

  let body;
  try { body = JSON.parse(text); } catch { return json({ ok: false, error: 'json' }, 400); }
  const rows = Array.isArray(body && body.rows) ? body.rows.slice(0, MAX_ROWS).map(cleanRow).filter(Boolean) : [];
  const asn = asnOf(request);
  const day = dayOf(Date.now());
  const rate = await currentRate(env);
  if (rows.length === 0) return json({ ok: true, asn, rate, stored: 0 });

  // Daily caps first: one statement each, and a refusal costs nothing more.
  const ip = request.headers.get('cf-connecting-ip') || '';
  const ipKey = 'ip:' + (await sha256Hex(`${env.SALT}|${day}|${ip}`)).slice(0, 20);
  const [inst, addr] = await env.DB.batch([
    limiter(env, 'in:' + id, day),
    limiter(env, ipKey, day),
  ]);
  const instN = inst.results[0]?.n ?? 0;
  const addrN = addr.results[0]?.n ?? 0;
  if (instN > PER_INSTALL_PER_DAY || addrN > PER_ADDRESS_PER_DAY) {
    return json({ ok: false, error: 'limit', asn, rate }, 429);
  }

  const stmts = [];
  for (let i = 0; i < rows.length; i += 5) stmts.push(aggUpsert(env, day, asn, rows.slice(i, i + 5)));
  const prov = [];
  for (const r of rows) {
    for (const [p, v] of Object.entries(r.s)) {
      if (prov.length >= MAX_PROV) break;
      prov.push([r.g, p, v > 0 ? 1 : 0, v > 0 ? 0 : 1]);
    }
  }
  if (prov.length) stmts.push(provUpsert(env, day, asn, prov));
  await env.DB.batch(stmts);
  return json({ ok: true, asn, rate, stored: rows.length });
}

/** One row of a report, validated and clipped, or null. Anything unknown is dropped, not trusted. */
function cleanRow(r) {
  if (!r || typeof r !== 'object') return null;
  const g = String(r.g || '');
  const region = String(r.r || '');
  const plan = String(r.p || '');
  if (!/^[a-z0-9_]{1,24}$/.test(g) || !/^[A-Z]{2,4}$/.test(region) || !PLANS.has(plan)) return null;
  const int = (v, lo, hi) => (Number.isFinite(Number(v)) ? Math.max(lo, Math.min(hi, Math.round(Number(v)))) : null);
  const s = {};
  if (r.s && typeof r.s === 'object') {
    for (const [k, v] of Object.entries(r.s).slice(0, 6)) {
      if (/^[a-z0-9]{1,12}$/.test(k)) s[k] = int(v, -1, 1) ?? 0;
    }
  }
  return {
    g, r: region, p: plan,
    f: r.f ? 1 : 0,
    o: int(r.o, -1, 1) ?? 0,
    wm: r.wm ? 1 : 0,
    ww: r.wm && r.ww ? 1 : 0,
    p50: int(r.p50, 1, 1500),
    k: int(r.k, 0, 5) ?? 0,
    s,
  };
}

function limiter(env, key, day) {
  return env.DB.prepare(
    'INSERT INTO lim (k, day, n) VALUES (?1, ?2, 1) ' +
    'ON CONFLICT(k) DO UPDATE SET n = CASE WHEN lim.day = excluded.day THEN lim.n + 1 ELSE 1 END, day = excluded.day ' +
    'RETURNING n',
  ).bind(key, day);
}

/**
 * Up to five rows in one statement (17 parameters each, under D1's 100). A feedback row (f = 1)
 * counts only as a vote -- the session it answers was already counted when it ended.
 */
function aggUpsert(env, day, asn, rows) {
  const values = [];
  const params = [];
  for (const r of rows) {
    const fb = r.f === 1;
    values.push('(' + Array.from({ length: 17 }, () => '?').join(',') + ')');
    params.push(
      day, asn, r.g, r.r, r.p,
      fb ? 0 : 1,
      r.o > 0 ? (fb ? 3 : 1) : 0,
      r.o < 0 ? (fb ? 3 : 1) : 0,
      fb ? 0 : r.wm, fb ? 0 : r.ww,
      !fb && r.p50 ? r.p50 : 0, !fb && r.p50 ? 1 : 0,
      !fb && r.k === 1 ? 1 : 0, !fb && r.k === 2 ? 1 : 0, !fb && r.k === 3 ? 1 : 0,
      !fb && r.k === 4 ? 1 : 0, !fb && r.k === 5 ? 1 : 0,
    );
  }
  return env.DB.prepare(
    'INSERT INTO agg (day, asn, game, region, plan, n, ok, bad, wm, ww, p50s, p50n, k1, k2, k3, k4, k5) VALUES ' +
    values.join(',') +
    ' ON CONFLICT(day, asn, game, region, plan) DO UPDATE SET n = n + excluded.n, ok = ok + excluded.ok, ' +
    'bad = bad + excluded.bad, wm = wm + excluded.wm, ww = ww + excluded.ww, p50s = p50s + excluded.p50s, ' +
    'p50n = p50n + excluded.p50n, k1 = k1 + excluded.k1, k2 = k2 + excluded.k2, k3 = k3 + excluded.k3, ' +
    'k4 = k4 + excluded.k4, k5 = k5 + excluded.k5',
  ).bind(...params);
}

function provUpsert(env, day, asn, prov) {
  const values = prov.map(() => '(?,?,?,?,?,?)').join(',');
  const params = prov.flatMap(([g, p, ok, bad]) => [day, asn, g, p, ok, bad]);
  return env.DB.prepare(
    'INSERT INTO prov (day, asn, game, prov, ok, bad) VALUES ' + values +
    ' ON CONFLICT(day, asn, game, prov) DO UPDATE SET ok = ok + excluded.ok, bad = bad + excluded.bad',
  ).bind(...params);
}

// ── the snapshot ────────────────────────────────────────────────────────────────────────────

/**
 * Counters of the last [WINDOW_DAYS] days → one signed slice per ASN (the busiest [MAX_ASNS]) and
 * one global slice carrying the owner's flags and kill switches. The GROUP BY runs in D1, so the
 * Worker's CPU only formats. Guarded by a lock row: many stale reads start at most one rebuild.
 */
async function rebuild(env, now = Date.now(), force = false) {
  const got = await env.DB.prepare(
    "INSERT INTO meta (k, v) VALUES ('lock', ?1) ON CONFLICT(k) DO UPDATE SET v = excluded.v " +
    'WHERE CAST(meta.v AS INTEGER) < ?2 RETURNING v',
  ).bind(String(now), force ? now + 1 : now - LOCK_MS).first();
  if (!got) return false;

  const today = dayOf(now);
  const since = today - WINDOW_DAYS;
  const [aggRes, provRes, cfgRes] = await env.DB.batch([
    env.DB.prepare(
      'SELECT asn, game, region, plan, SUM(n) n, SUM(ok) ok, SUM(bad) bad, SUM(wm) wm, SUM(ww) ww, ' +
      'SUM(p50s) p50s, SUM(p50n) p50n, SUM(k1) k1, SUM(k2) k2, SUM(k3) k3, SUM(k4) k4, SUM(k5) k5 ' +
      'FROM agg WHERE day >= ?1 GROUP BY asn, game, region, plan',
    ).bind(since),
    env.DB.prepare('SELECT asn, game, prov, SUM(ok) ok, SUM(bad) bad FROM prov WHERE day >= ?1 GROUP BY asn, game, prov').bind(since),
    env.DB.prepare("SELECT v FROM meta WHERE k = 'cfg'"),
  ]);
  let cfg = {};
  try { cfg = JSON.parse(cfgRes.results[0]?.v || '{}'); } catch { cfg = {}; }

  const byAsn = new Map();
  const global = { g: {} };
  const slot = (asn) => {
    if (!byAsn.has(asn)) byAsn.set(asn, { g: {}, total: 0 });
    return byAsn.get(asn);
  };
  for (const r of aggRes.results) {
    for (const target of [slot(r.asn), global]) {
      const g = (target.g[r.game] ||= { n: 0, p: {}, w: [0, 0], r: {}, k: [0, 0, 0, 0, 0], s: {} });
      g.n += r.n;
      const p = (g.p[r.plan] ||= [0, 0, 0]);
      p[0] += r.n; p[1] += r.ok; p[2] += r.bad;
      g.w[0] += r.wm; g.w[1] += r.ww;
      const reg = (g.r[r.region] ||= [0, 0, 0]); // [n, p50 sum, p50 n]
      reg[0] += r.n; reg[1] += r.p50s; reg[2] += r.p50n;
      g.k[0] += r.k1; g.k[1] += r.k2; g.k[2] += r.k3; g.k[3] += r.k4; g.k[4] += r.k5;
      if (target !== global) target.total += r.n;
    }
  }
  for (const r of provRes.results) {
    for (const target of [slot(r.asn), global]) {
      const g = (target.g[r.game] ||= { n: 0, p: {}, w: [0, 0], r: {}, k: [0, 0, 0, 0, 0], s: {} });
      const s = (g.s[r.prov] ||= [0, 0]);
      s[0] += r.ok; s[1] += r.bad;
    }
  }
  const shape = (games) => {
    const out = {};
    for (const [id, g] of Object.entries(games)) {
      if (g.n < MIN_N && Object.keys(g.s).length === 0) continue;
      const regions = {};
      for (const [k, [n, sum, cnt]] of Object.entries(g.r)) regions[k] = [n, cnt ? Math.round(sum / cnt) : null];
      out[id] = { n: g.n, p: g.p, w: g.w, r: regions, k: g.k, s: g.s };
    }
    return out;
  };

  const key = await signingKey(env);
  const slices = [];
  const allBody = JSON.stringify({
    v: 1, k: 'all', at: now,
    rate: typeof cfg.rate === 'number' ? cfg.rate : DEFAULT_RATE,
    flags: cfg.flags || {},
    kill: cfg.kill || {},
    g: shape(global.g),
  });
  slices.push(['all', allBody]);
  const busiest = [...byAsn.entries()].sort((a, b) => b[1].total - a[1].total).slice(0, MAX_ASNS);
  for (const [asn, s] of busiest) {
    const games = shape(s.g);
    if (Object.keys(games).length === 0) continue;
    slices.push(['as' + asn, JSON.stringify({ v: 1, k: 'as' + asn, at: now, asn, g: games })]);
  }

  const writes = [];
  const signed = await Promise.all(slices.map(async ([k, body]) => [k, body, await sign(key, body)]));
  for (let i = 0; i < signed.length; i += 25) {
    const chunk = signed.slice(i, i + 25);
    writes.push(env.DB.prepare(
      'INSERT OR REPLACE INTO snap (k, body, sig, at) VALUES ' + chunk.map(() => '(?,?,?,?)').join(','),
    ).bind(...chunk.flatMap(([k, body, sig]) => [k, body, sig, now])));
  }
  writes.push(env.DB.prepare('DELETE FROM agg WHERE day < ?1').bind(today - KEEP_DAYS));
  writes.push(env.DB.prepare('DELETE FROM prov WHERE day < ?1').bind(today - KEEP_DAYS));
  writes.push(env.DB.prepare('DELETE FROM lim WHERE day < ?1').bind(today - 1));
  await env.DB.batch(writes);
  console.log(JSON.stringify({ level: 'info', event: 'rebuild', slices: slices.length, rows: aggRes.results.length }));
  return true;
}

// ── the owner's switches ────────────────────────────────────────────────────────────────────

async function admin(request, env, url) {
  const auth = request.headers.get('authorization') || '';
  const token = auth.startsWith('Bearer ') ? auth.slice(7) : '';
  if (!env.ADMIN_TOKEN || !timingSafeEqualText(token, env.ADMIN_TOKEN)) return json({ ok: false }, 404);

  if (request.method === 'GET' && url.pathname === '/admin/cfg') {
    const row = await env.DB.prepare("SELECT v FROM meta WHERE k = 'cfg'").first();
    return json({ ok: true, cfg: JSON.parse(row?.v || '{}') });
  }
  if (request.method === 'POST' && url.pathname === '/admin/cfg') {
    const text = await readCapped(request, MAX_BODY);
    let cfg;
    try { cfg = JSON.parse(text || ''); } catch { return json({ ok: false, error: 'json' }, 400); }
    const clean = {
      rate: typeof cfg.rate === 'number' ? Math.max(0, Math.min(1, cfg.rate)) : DEFAULT_RATE,
      flags: cfg.flags && typeof cfg.flags === 'object' ? cfg.flags : {},
      kill: cfg.kill && typeof cfg.kill === 'object' ? cfg.kill : {},
    };
    await env.DB.prepare("INSERT OR REPLACE INTO meta (k, v) VALUES ('cfg', ?1)").bind(JSON.stringify(clean)).run();
    await rebuild(env, Date.now(), true);
    return json({ ok: true, cfg: clean });
  }
  if (request.method === 'POST' && url.pathname === '/admin/rebuild') {
    return json({ ok: await rebuild(env, Date.now(), true) });
  }
  if (request.method === 'GET' && url.pathname === '/admin/stats') {
    const day = dayOf(Date.now());
    const [today, asns] = await env.DB.batch([
      env.DB.prepare('SELECT COUNT(*) rows, SUM(n) sessions, SUM(ok) ok, SUM(bad) bad FROM agg WHERE day = ?1').bind(day),
      env.DB.prepare('SELECT asn, SUM(n) n FROM agg WHERE day >= ?1 GROUP BY asn ORDER BY n DESC LIMIT 15').bind(day - 7),
    ]);
    return json({ ok: true, today: today.results[0], topAsns7d: asns.results });
  }
  return json({ ok: false }, 404);
}

async function currentRate(env) {
  const row = await env.DB.prepare("SELECT v FROM meta WHERE k = 'cfg'").first();
  try {
    const r = JSON.parse(row?.v || '{}').rate;
    return typeof r === 'number' ? r : DEFAULT_RATE;
  } catch {
    return DEFAULT_RATE;
  }
}

// ── helpers ─────────────────────────────────────────────────────────────────────────────────

const dayOf = (ms) => Math.floor(ms / 86400000);

function asnOf(request) {
  const a = Number(request.cf && request.cf.asn);
  return Number.isInteger(a) && a > 0 ? a : 0;
}

/** The body as text, or null past [cap] bytes -- never an unbounded read. */
async function readCapped(request, cap) {
  const len = Number(request.headers.get('content-length') || '0');
  if (len > cap) return null;
  const buf = await request.arrayBuffer();
  if (buf.byteLength > cap) return null;
  return new TextDecoder().decode(buf);
}

async function hmacHex(keyText, message) {
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(keyText), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const mac = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(message));
  return hex(mac);
}

async function sha256Hex(text) {
  return hex(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text)));
}

const hex = (buf) => [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('');

function timingSafeEqualHex(a, b) {
  if (a.length !== b.length) return false;
  const x = new TextEncoder().encode(a);
  const y = new TextEncoder().encode(b);
  return crypto.subtle.timingSafeEqual ? crypto.subtle.timingSafeEqual(x, y) : constantTime(x, y);
}

function timingSafeEqualText(a, b) {
  const x = new TextEncoder().encode(a);
  const y = new TextEncoder().encode(b);
  if (x.byteLength !== y.byteLength) return false;
  return crypto.subtle.timingSafeEqual ? crypto.subtle.timingSafeEqual(x, y) : constantTime(x, y);
}

function constantTime(x, y) {
  let d = 0;
  for (let i = 0; i < x.length; i++) d |= x[i] ^ y[i];
  return d === 0;
}

async function signingKey(env) {
  return crypto.subtle.importKey('jwk', JSON.parse(env.SIGN_JWK), { name: 'Ed25519' }, false, ['sign']);
}

async function sign(key, text) {
  const sig = await crypto.subtle.sign({ name: 'Ed25519' }, key, new TextEncoder().encode(text));
  return btoa(String.fromCharCode(...new Uint8Array(sig)));
}
