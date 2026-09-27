// ==========================================================
// Config Studio  —  /v1/exits   («لوکیشن»)
// ==========================================================
//
// A Worker leaves Cloudflare from Cloudflare's own addresses, so on its own it has exactly one
// "location": wherever the nearest colo happens to be. An EXIT is a SOCKS5 or HTTP-CONNECT server
// the operator owns (or rents) in some country; a config pinned to a country sends every one of its
// connections out through an exit in that country. One user can hold several configs, one per
// country, and pick a location by picking a config.
//
// Three rules this file keeps, and they are the reason it is shaped the way it is:
//
//  * **No leak.** A config with a country NEVER falls back to a direct connection. If no exit in
//    that country answers, the connection is refused. A direct fallback would quietly hand the
//    person a Cloudflare address in a different country while their app still says «آلمان» -- the
//    exact failure a location exists to prevent.
//  * **Sticky.** The same person on the same country lands on the same exit for as long as it is
//    healthy. Sites tie sessions to an address, and hopping between exits per connection logs people
//    out of their bank.
//  * **Cheap.** The exit list is read once per country per isolate per minute, never per connection,
//    and health is written to D1 only when it CHANGES.
//
// An exit is also what keeps abuse reports off the Cloudflare account: the far side of every
// connection sees the exit's address, not Cloudflare's (see 10b-guard.js for the rest of that).

const STUDIO_EXIT_COLUMNS =
  'id, cc, label, url, enabled, health, exit_ip, exit_cc, latency_ms, fails, checked_at, created_at, updated_at';

/** cc -> { at, rows }. A minute is the longest an operator waits for a new exit to be used. */
const EXIT_CACHE = new Map();
/** exit id -> { fails, downUntil, wroteAt, health } — this isolate's view, ahead of D1. */
const EXIT_STATE = new Map();
const EXIT_CACHE_TTL_MS = 60000;
const EXIT_DIAL_TIMEOUT_MS = 5000;

function studioInvalidateExitCache() { EXIT_CACHE.clear(); }

function studioValidCc(cc) { return typeof cc === 'string' && /^[A-Z]{2}$/.test(cc); }

/**
 * `socks5://user:pass@host:port`, `http://user:pass@host:port`, bare `host:port` (SOCKS5), and the
 * common `host:port:user:pass`. Null for anything else -- including `https://`, which would need TLS
 * to the proxy itself and is refused rather than silently sent in the clear.
 */
function studioParseExitUrl(raw) {
  let s = String(raw || '').trim();
  if (!s || s.length > 400) return null;
  let scheme = 'socks5';
  const m = s.match(/^([a-z0-9]+):\/\//i);
  if (m) { scheme = m[1].toLowerCase(); s = s.slice(m[0].length); }
  if (scheme === 'socks' || scheme === 'socks5h') scheme = 'socks5';
  if (scheme !== 'socks5' && scheme !== 'http') return null;
  s = s.replace(/[/?#].*$/, '');
  let user = '', pass = '';
  try {
    const at = s.lastIndexOf('@');
    if (at >= 0) {
      const cred = s.slice(0, at);
      s = s.slice(at + 1);
      const c = cred.indexOf(':');
      user = decodeURIComponent(c >= 0 ? cred.slice(0, c) : cred);
      pass = decodeURIComponent(c >= 0 ? cred.slice(c + 1) : '');
    } else if (!s.startsWith('[')) {
      const parts = s.split(':');
      if (parts.length === 4) { s = parts[0] + ':' + parts[1]; user = parts[2]; pass = parts[3]; }
    }
  } catch (e) { return null; }
  let host, port;
  if (s.startsWith('[')) {
    const e = s.indexOf(']');
    if (e < 0) return null;
    host = s.slice(1, e);
    port = s.slice(e + 2);
  } else {
    const c = s.lastIndexOf(':');
    if (c <= 0) return null;
    host = s.slice(0, c);
    port = s.slice(c + 1);
  }
  port = parseInt(port, 10);
  if (!host || !/^[A-Za-z0-9.:-]+$/.test(host) || !(port > 0 && port < 65536)) return null;
  if (user.length > 255 || pass.length > 255) return null;
  return { scheme, host, port, user, pass };
}

/** The same exit, without its password, for anything that is shown or logged. */
function studioRedactExitUrl(raw) {
  const p = studioParseExitUrl(raw);
  if (!p) return '';
  return `${p.scheme}://${p.user ? p.user + ':***@' : ''}${p.host.includes(':') ? '[' + p.host + ']' : p.host}:${p.port}`;
}

// ---------------------------------------------------------------------------- dialling

/**
 * A socket whose readable side starts with bytes that arrived with the proxy's own reply.
 *
 * A server that speaks first (SSH, SMTP, FTP) can put its banner in the same packet as the proxy's
 * "connected", and dropping those bytes breaks the protocol in a way that looks like a bad exit.
 */
function studioSocketWithPrefix(sock, reader, leftover) {
  const readable = new ReadableStream({
    start(c) { if (leftover && leftover.byteLength) c.enqueue(leftover); },
    async pull(c) {
      try {
        const { value, done } = await reader.read();
        if (done) c.close(); else c.enqueue(value);
      } catch (e) { c.error(e); }
    },
    cancel(reason) { try { reader.cancel(reason); } catch (e) { } },
  });
  return { readable, writable: sock.writable, closed: sock.closed, opened: sock.opened, close: () => sock.close() };
}

/** Reads from [reader] until [buf] holds at least [n] bytes. */
async function studioReadAtLeast(reader, buf, n) {
  while (buf.byteLength < n) {
    const { value, done } = await reader.read();
    if (done) throw new Error('exit closed during handshake');
    buf = concatBytes(buf, convertToUint8Array(value));
  }
  return buf;
}

async function studioSocks5Handshake(reader, writer, p, host, port) {
  const enc = new TextEncoder();
  const auth = !!(p.user || p.pass);
  await writer.write(new Uint8Array(auth ? [5, 2, 0, 2] : [5, 1, 0]));
  let buf = await studioReadAtLeast(reader, new Uint8Array(0), 2);
  if (buf[0] !== 5) throw new Error('not a SOCKS5 server');
  const method = buf[1];
  buf = buf.slice(2);
  if (method === 2) {
    const u = enc.encode(p.user), w = enc.encode(p.pass);
    await writer.write(new Uint8Array([1, u.length, ...u, w.length, ...w]));
    buf = await studioReadAtLeast(reader, buf, 2);
    if (buf[1] !== 0) throw new Error('exit refused the credentials');
    buf = buf.slice(2);
  } else if (method !== 0) {
    throw new Error('exit wants an auth method we do not speak');
  }

  let addr;
  if (isIPv4(host)) addr = [1, ...host.split('.').map((x) => parseInt(x, 10))];
  else {
    const h = enc.encode(host);
    if (h.length > 255) throw new Error('host name too long');
    addr = [3, h.length, ...h];
  }
  await writer.write(new Uint8Array([5, 1, 0, ...addr, (port >> 8) & 255, port & 255]));
  buf = await studioReadAtLeast(reader, buf, 5);
  if (buf[1] !== 0) throw new Error('exit could not reach the destination (' + buf[1] + ')');
  const atyp = buf[3];
  const replyLen = atyp === 1 ? 10 : atyp === 4 ? 22 : atyp === 3 ? 7 + buf[4] : 0;
  if (!replyLen) throw new Error('bad SOCKS5 reply');
  buf = await studioReadAtLeast(reader, buf, replyLen);
  return buf.slice(replyLen);
}

async function studioHttpConnectHandshake(reader, writer, p, host, port) {
  const target = (host.includes(':') ? '[' + host + ']' : host) + ':' + port;
  let req = `CONNECT ${target} HTTP/1.1\r\nHost: ${target}\r\n`;
  if (p.user || p.pass) req += `Proxy-Authorization: Basic ${btoa(p.user + ':' + p.pass)}\r\n`;
  await writer.write(new TextEncoder().encode(req + '\r\n'));
  let buf = new Uint8Array(0);
  const dec = new TextDecoder();
  while (true) {
    const { value, done } = await reader.read();
    if (done) throw new Error('exit closed during handshake');
    buf = concatBytes(buf, convertToUint8Array(value));
    const text = dec.decode(buf.slice(0, Math.min(buf.byteLength, 8192)));
    const end = text.indexOf('\r\n\r\n');
    if (end >= 0) {
      if (!/^HTTP\/1\.[01] 2\d\d/.test(text)) throw new Error('exit refused: ' + text.split('\r\n')[0].slice(0, 60));
      return buf.slice(new TextEncoder().encode(text.slice(0, end + 4)).byteLength);
    }
    if (buf.byteLength > 8192) throw new Error('exit reply too long');
  }
}

/** One connection to [host]:[port] through one exit. Throws on anything short of "connected". */
async function studioDialVia(exitUrl, host, port, initialData, timeoutMs = EXIT_DIAL_TIMEOUT_MS) {
  const p = studioParseExitUrl(exitUrl);
  if (!p) throw new Error('unreadable exit');
  const sock = connect({ hostname: p.host, port: p.port });
  let timer;
  try {
    const leftover = await Promise.race([
      (async () => {
        await sock.opened;
        const reader = sock.readable.getReader();
        const writer = sock.writable.getWriter();
        try {
          const rest = p.scheme === 'http'
            ? await studioHttpConnectHandshake(reader, writer, p, host, port)
            : await studioSocks5Handshake(reader, writer, p, host, port);
          if (initialData && initialData.byteLength) await writer.write(convertToUint8Array(initialData));
          return { reader, rest };
        } finally {
          writer.releaseLock();
        }
      })(),
      new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('exit timeout')), timeoutMs); }),
    ]);
    return studioSocketWithPrefix(sock, leftover.reader, leftover.rest);
  } catch (e) {
    try { sock.close(); } catch (x) { }
    throw e;
  } finally {
    clearTimeout(timer);
  }
}

/**
 * The operator's enabled exits for [cc], by the country a test MEASURED where there is one.
 *
 * Until build 18 an exit was chosen by the country it was filed under, and a test only re-filed an
 * exit stored as unknown -- so a server filed as Germany that actually leaves from the Netherlands
 * kept serving everyone's «آلمان» config from the Netherlands. The filed country now only counts for
 * an exit nothing has measured yet.
 */
async function studioExitsFor(env, cc) {
  const now = Date.now();
  const hit = EXIT_CACHE.get(cc);
  if (hit && now - hit.at < EXIT_CACHE_TTL_MS) return hit.rows;
  let rows = [];
  try {
    // '*' is every enabled exit, for the relay fallback (studioDialOwnRelay).
    const res = cc === '*'
      ? await env.DB.prepare("SELECT id, url, health, cc, exit_cc, exit_ip, latency_ms FROM exits WHERE enabled = 1 AND cc <> 'ZZ' LIMIT 64").all()
      : await env.DB.prepare(
        "SELECT id, url, health, cc, exit_cc, exit_ip, latency_ms FROM exits WHERE enabled = 1 AND " +
        "(exit_cc = ? OR ((exit_cc IS NULL OR exit_cc = '') AND cc = ?)) LIMIT 64"
      ).bind(cc, cc).all();
    rows = (res && res.results) || [];
  } catch (e) {
    // Serve the last list we had rather than none: a D1 hiccup must not look like "no exits".
    if (hit) return hit.rows;
  }
  EXIT_CACHE.set(cc, { at: now, rows });
  return rows;
}

function studioHashStr(s) {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); }
  return h >>> 0;
}

/** Healthy first, then untested, then known-bad; within a tier, a stable rotation keyed on [key]. */
function studioOrderExits(rows, key) {
  const now = Date.now();
  const tier = (r) => {
    const st = EXIT_STATE.get(r.id);
    if (st && st.downUntil > now) return 3;
    const h = (st && st.health) || r.health;
    return h === 'up' ? 0 : h === 'down' ? 2 : 1;
  };
  const tiers = [[], [], [], []];
  for (const r of rows) tiers[tier(r)].push(r);
  const out = [];
  for (const t of tiers) {
    if (!t.length) continue;
    t.sort((a, b) => (a.id < b.id ? -1 : 1));
    const start = studioHashStr(key) % t.length;
    for (let i = 0; i < t.length; i++) out.push(t[(start + i) % t.length]);
  }
  return out;
}

/** Record what a dial found, and write it to D1 only when the answer changed. */
function studioNoteExit(env, ctx, id, ok) {
  const now = Date.now();
  const st = EXIT_STATE.get(id) || { fails: 0, downUntil: 0, wroteAt: 0, health: null };
  let health = st.health;
  if (ok) { st.fails = 0; st.downUntil = 0; health = 'up'; }
  else {
    st.fails++;
    if (st.fails >= 2) { st.downUntil = now + 120000; health = 'down'; }
  }
  const changed = health && health !== st.health;
  st.health = health;
  EXIT_STATE.set(id, st);
  if (changed && now - st.wroteAt > 60000) {
    st.wroteAt = now;
    const task = env.DB.prepare(
      'UPDATE exits SET health = ?, fails = ?, checked_at = ? WHERE id = ?'
    ).bind(health, st.fails, now, id).run().catch(() => { });
    if (ctx) ctx.waitUntil(task);
  }
}

// ---------------------------------------------------------------------------- the pinned exit

/** url -> { fails, downUntil }: this isolate's view of every exit it has dialled, own or public. */
const EXIT_URL_STATE = new Map();
/** configId -> when its pin was last written, so a flapping exit costs at most a write a minute. */
const PIN_WRITES = new Map();
const EXIT_PIN_TIMEOUT_MS = 4000;
const EXIT_RACE_TIMEOUT_MS = 3500;

function studioExitDown(url) {
  const st = EXIT_URL_STATE.get(url);
  return !!(st && st.downUntil > Date.now());
}

function studioNoteExitUrl(url, ok) {
  const st = EXIT_URL_STATE.get(url) || { fails: 0, downUntil: 0 };
  if (ok) { st.fails = 0; st.downUntil = 0; }
  else if (++st.fails >= 2) st.downUntil = Date.now() + Math.min(30 * 60000, 60000 * st.fails);
  EXIT_URL_STATE.set(url, st);
  if (EXIT_URL_STATE.size > 4000) EXIT_URL_STATE.clear();
}

/**
 * Dial several exits at once and keep the first that connects.
 *
 * The first bytes are written to the WINNER only, after it is chosen. Racing with them attached
 * would deliver the same request through two exits -- harmless for a TLS hello, not for a plain
 * HTTP request that does something.
 */
async function studioRaceExits(cands, host, port, initialData) {
  const attempts = cands.map((c) => studioDialVia(c.url, host, port, null, EXIT_RACE_TIMEOUT_MS).then(
    (sock) => { studioNoteExitUrl(c.url, true); return { sock, cand: c }; },
    (err) => { studioNoteExitUrl(c.url, false); throw err; },
  ));
  let winner;
  try {
    winner = await Promise.any(attempts);
  } catch (e) {
    throw (e && e.errors && e.errors[0]) || new Error('no exit answered');
  }
  // A loser that connected as well is closed rather than left holding a connection slot.
  for (const a of attempts) {
    a.then(({ sock }) => { if (sock !== winner.sock) { try { sock.close(); } catch (x) { } } }, () => { });
  }
  if (initialData && initialData.byteLength) {
    const w = winner.sock.writable.getWriter();
    await w.write(convertToUint8Array(initialData));
    w.releaseLock();
  }
  return winner;
}

/**
 * Write a config's new exit back, so every isolate follows it -- and update this isolate's cached
 * rows at once, so its next connection does not try the dead one first.
 */
function studioRepin(env, ctx, configId, cand) {
  if (!configId || !cand || !cand.url) return;
  for (const hit of CRED_CACHE.values()) {
    if (hit.user && hit.user.studio_config_id === configId) hit.user.studio_exit_pin = cand.url;
  }
  const now = Date.now();
  if (now - (PIN_WRITES.get(configId) || 0) < 60000) return;
  PIN_WRITES.set(configId, now);
  if (PIN_WRITES.size > 4000) PIN_WRITES.clear();
  const task = env.DB.prepare('UPDATE configs SET exit_pin = ?, exit_pin_ip = ?, exit_pin_at = ? WHERE id = ?')
    .bind(cand.url, cand.ip || null, now, configId).run().catch(() => { });
  if (ctx && ctx.waitUntil) ctx.waitUntil(task);
}

/**
 * The exits a config in [cc] may use, best first: the operator's own (by measured country), then
 * verified public servers when the pool is on. [avoid] is the pin that just failed.
 */
async function studioExitCandidates(env, ctx, cc, stickyKey, avoid) {
  const own = studioOrderExits(await studioExitsFor(env, cc), stickyKey + '|' + cc)
    .filter((r) => r.url !== avoid && !studioExitDown(r.url))
    .map((r) => ({ url: r.url, id: r.id, ip: r.exit_ip || null }));
  if (own.length) return own;
  const verified = await studioPoolVerifiedFor(env, ctx, cc);
  return studioPoolOrderVerified(verified, stickyKey + '|' + cc)
    .filter((v) => v.u !== avoid)
    .map((v) => ({ url: v.u, ip: v.ip || null, pool: true }));
}

/** The exit a new config in [cc] should be pinned to, or null when there is none to offer. */
async function studioPickPin(env, ctx, cc, key) {
  const cands = await studioExitCandidates(env, ctx, cc, key, null);
  return cands.length ? cands[0] : null;
}

/**
 * Out through an exit in [cc], or not at all.
 *
 * **The pinned exit first** (build 18). A location config keeps one exit -- its `exit_pin`, which
 * every isolate reads from the same row -- for as long as it answers, so a person keeps one address
 * in their chosen country. Until build 18 the exit was chosen per connection and per isolate, so the
 * same person hopped between addresses, and with the public pool between countries.
 *
 * When the pin fails, two candidates of the same measured country are raced and the winner becomes
 * the new pin. There is deliberately no direct fallback (see the top).
 */
async function studioDialExit(env, ctx, cc, stickyKey, host, port, initialData, pin) {
  const pinUrl = pin && pin.url;
  if (pinUrl && !studioExitDown(pinUrl)) {
    try {
      const s = await studioDialVia(pinUrl, host, port, initialData, EXIT_PIN_TIMEOUT_MS);
      studioNoteExitUrl(pinUrl, true);
      return s;
    } catch (e) {
      studioNoteExitUrl(pinUrl, false);
    }
  }

  // The person's usual candidate alone first -- the ordering is a stable rotation on who they are, so
  // this is what keeps a config with no pin yet on one exit -- then the next ones two at a time.
  const cands = await studioExitCandidates(env, ctx, cc, stickyKey, pinUrl);
  let lastErr = null;
  const groups = cands.length ? [cands.slice(0, 1), cands.slice(1, 3), cands.slice(3, 5)].filter((g) => g.length) : [];
  for (const group of groups) {
    try {
      const { sock, cand } = await studioRaceExits(group, host, port, initialData);
      if (cand.id) studioNoteExit(env, ctx, cand.id, true);
      if (pin && pin.configId) studioRepin(env, ctx, pin.configId, cand);
      return sock;
    } catch (e) {
      lastErr = e;
      for (const c of group) if (c.id) studioNoteExit(env, ctx, c.id, false);
    }
  }

  // Nothing verified to offer yet (a cold pool): the public list itself, raced, while verification
  // runs in the background. It throws when the pool is off -- refuse, never go direct.
  try { return await studioDialPool(env, ctx, cc, stickyKey, host, port, initialData, pin); } catch (e) { if (!lastErr) lastErr = e; }
  throw lastErr || new Error('no exit answered');
}

/**
 * The relay for a destination a Worker cannot reach directly (Cloudflare's own addresses), through
 * the operator's OWN exits -- or null when there are none, and the caller falls back as before.
 *
 * Without this, every such connection went through a public relay run by a stranger: a leak of the
 * person's destinations to a third party, and an address shared with every other panel on the
 * internet, which is exactly the kind of address abuse reports are about. Only healthy exits are
 * tried, and one failure hands back to the caller rather than spending seconds here.
 */
async function studioDialOwnRelay(env, ctx, stickyKey, host, port, initialData) {
  let rows;
  try { rows = await studioExitsFor(env, '*'); } catch (e) { return null; }
  const healthy = studioOrderExits(rows, stickyKey + '|*').filter((r) => {
    const st = EXIT_STATE.get(r.id);
    return !(st && st.downUntil > Date.now()) && ((st && st.health) || r.health) !== 'down';
  });
  for (const r of healthy.slice(0, 2)) {
    try {
      const s = await studioDialVia(r.url, host, port, initialData);
      studioNoteExit(env, ctx, r.id, true);
      return s;
    } catch (e) {
      studioNoteExit(env, ctx, r.id, false);
    }
  }
  return null;
}

/**
 * Where an exit actually leaves from, asked THROUGH it.
 *
 * Plain HTTP on purpose: a TLS handshake through the tunnel would need its own client here, and the
 * answer is the address and the country, neither of which is a secret.
 */
async function studioProbeExit(exitUrl) {
  const t0 = Date.now();
  const tries = [
    { host: 'ip-api.com', path: '/json/?fields=status,countryCode,query', ip: 'query', cc: 'countryCode' },
    { host: 'ipinfo.io', path: '/json', ip: 'ip', cc: 'country' },
  ];
  let lastErr = 'no answer';
  for (const t of tries) {
    let s = null;
    try {
      const req = new TextEncoder().encode(
        `GET ${t.path} HTTP/1.1\r\nHost: ${t.host}\r\nUser-Agent: curl/8.5.0\r\nAccept: application/json\r\nConnection: close\r\n\r\n`);
      s = await studioDialVia(exitUrl, t.host, 80, req);
      const latency = Date.now() - t0;
      const reader = s.readable.getReader();
      const dec = new TextDecoder();
      let text = '';
      const until = Date.now() + 5000;
      while (Date.now() < until && text.length < 16384) {
        const { value, done } = await Promise.race([
          reader.read(),
          new Promise((r) => setTimeout(() => r({ done: true }), Math.max(1, until - Date.now()))),
        ]);
        if (done) break;
        text += dec.decode(value, { stream: true });
        if (text.includes('}')) break;
      }
      const body = text.slice(text.indexOf('{'), text.lastIndexOf('}') + 1);
      const j = JSON.parse(body);
      const ip = j[t.ip], cc = String(j[t.cc] || '').toUpperCase();
      if (ip) return { ok: true, latency_ms: latency, exit_ip: String(ip).slice(0, 64), exit_cc: studioValidCc(cc) ? cc : null };
      lastErr = 'no address in the answer';
    } catch (e) {
      lastErr = (e && e.message) || String(e);
    } finally {
      try { s && s.close(); } catch (e) { }
    }
  }
  return { ok: false, error: String(lastErr).slice(0, 120), latency_ms: null };
}

// ---------------------------------------------------------------------------- API

function studioExitDto(row) {
  if (!row) return null;
  return {
    id: row.id,
    cc: row.cc,
    label: row.label || '',
    // The operator's own server, so the app is given the real URL -- it is the only way to edit
    // one -- but only behind the bearer key every /v1 call needs.
    url: row.url,
    display: studioRedactExitUrl(row.url),
    enabled: !!row.enabled,
    health: row.health || 'unknown',
    exit_ip: row.exit_ip || null,
    exit_cc: row.exit_cc || null,
    latency_ms: row.latency_ms == null ? null : row.latency_ms,
    checked_at: row.checked_at || null,
    created_at: row.created_at || null,
    updated_at: row.updated_at || null,
  };
}

async function studioListExits(env) {
  const res = await env.DB.prepare(
    `SELECT ${STUDIO_EXIT_COLUMNS} FROM exits ORDER BY cc ASC, created_at ASC LIMIT 500`
  ).all();
  return studioJson({ items: ((res && res.results) || []).map(studioExitDto) });
}

/**
 * `POST /v1/exits` — add many at once: `{ items: [{ url, cc?, label? }] }`.
 *
 * Pasting a list is how exits arrive in practice. A line that cannot be read, or that is already
 * there, is reported back rather than failing the rest. An exit without a country is stored under
 * `ZZ` until its first test tells us where it really leaves from.
 */
async function studioAddExits(request, env, actor) {
  let body = {};
  try { body = await request.json(); } catch (e) { }
  const items = Array.isArray(body.items) ? body.items.slice(0, 200) : [];
  if (!items.length) return studioErr('bad_request', 'items is required', 400);
  return studioJson(await studioInsertExits(env, actor, items), 201);
}

/**
 * `POST /v1/exits:import` — `{ url, cc? }`: read a list from a link and add what is in it.
 *
 * Fetched by the engine, not the phone: the list usually lives on GitHub, which the phone may not
 * be able to reach from where it is, and the engine always can. HTTPS only, and capped, because this
 * is a URL the operator typed and the answer is parsed rather than trusted.
 */
async function studioImportExits(request, env, actor) {
  let body = {};
  try { body = await request.json(); } catch (e) { }
  const src = typeof body.url === 'string' ? body.url.trim() : '';
  if (!/^https:\/\/\S+$/.test(src) || src.length > 500) return studioErr('bad_request', 'an https link is required', 400);
  let text = '';
  try {
    const res = await fetch(src, { signal: AbortSignal.timeout(10000), headers: { 'User-Agent': 'curl/8.5.0' } });
    if (!res.ok) return studioErr('fetch_failed', 'the link answered ' + res.status, 502);
    text = (await res.text()).slice(0, 512 * 1024);
  } catch (e) {
    return studioErr('fetch_failed', 'the link could not be read', 502);
  }
  const cc = studioValidCc(body.cc) ? body.cc : null;
  const lines = text.split(/\r?\n/).map((l) => l.trim()).filter((l) => l && !l.startsWith('#')).slice(0, 200);
  if (!lines.length) return studioErr('bad_request', 'the link holds no servers', 400);
  return studioJson(await studioInsertExits(env, actor, lines.map((url) => ({ url, cc }))), 201);
}

async function studioInsertExits(env, actor, items) {

  const existing = await env.DB.prepare('SELECT url FROM exits').all();
  const have = new Set(((existing && existing.results) || []).map((r) => r.url));
  const now = Date.now();
  const stmts = [];
  const added = [];
  const rejected = [];
  for (const it of items) {
    const url = typeof it === 'string' ? it.trim() : String((it && it.url) || '').trim();
    const p = studioParseExitUrl(url);
    if (!p) { rejected.push({ url: url.slice(0, 80), reason: 'unreadable' }); continue; }
    if (have.has(url)) { rejected.push({ url: studioRedactExitUrl(url), reason: 'duplicate' }); continue; }
    have.add(url);
    const cc = studioValidCc(it && it.cc) ? it.cc : 'ZZ';
    const id = studioRandomHex(8);
    stmts.push(env.DB.prepare(
      `INSERT INTO exits (id, cc, label, url, enabled, health, fails, created_at, updated_at)
       VALUES (?,?,?,?,1,'unknown',0,?,?)`
    ).bind(id, cc, typeof it.label === 'string' ? it.label.slice(0, 60) : null, url, now, now));
    added.push(id);
  }
  if (stmts.length) {
    stmts.push(studioAuditStmt(env, actor, 'exit.add', 'exits', null, { count: added.length }, 'exit'));
    await env.DB.batch(stmts);
  }
  studioInvalidateExitCache();
  return { added, rejected };
}

async function studioPatchExit(request, env, id, actor) {
  const before = await env.DB.prepare(`SELECT ${STUDIO_EXIT_COLUMNS} FROM exits WHERE id = ?`).bind(id).first();
  if (!before) return studioErr('not_found', 'no such exit', 404);
  let body = {};
  try { body = await request.json(); } catch (e) { }
  const sets = [];
  const binds = [];
  if ('cc' in body) {
    if (!studioValidCc(body.cc)) return studioErr('bad_request', 'cc must be two capital letters', 400);
    sets.push('cc = ?'); binds.push(body.cc);
  }
  if ('label' in body) { sets.push('label = ?'); binds.push(body.label ? String(body.label).slice(0, 60) : null); }
  if ('enabled' in body) { sets.push('enabled = ?'); binds.push(body.enabled ? 1 : 0); }
  if ('url' in body) {
    if (!studioParseExitUrl(body.url)) return studioErr('bad_request', 'unreadable exit', 400);
    sets.push('url = ?', "health = 'unknown'", 'fails = 0'); binds.push(String(body.url).trim());
  }
  if (!sets.length) return studioErr('bad_request', 'nothing to update', 400);
  sets.push('updated_at = ?'); binds.push(Date.now());
  await env.DB.batch([
    env.DB.prepare(`UPDATE exits SET ${sets.join(', ')} WHERE id = ?`).bind(...binds, id),
    studioAuditStmt(env, actor, 'exit.update', id, { cc: before.cc, enabled: !!before.enabled },
      { cc: body.cc, enabled: body.enabled }, 'exit'),
  ]);
  studioInvalidateExitCache();
  EXIT_STATE.delete(id);
  const after = await env.DB.prepare(`SELECT ${STUDIO_EXIT_COLUMNS} FROM exits WHERE id = ?`).bind(id).first();
  return studioJson(studioExitDto(after));
}

async function studioDeleteExit(env, id, actor) {
  const before = await env.DB.prepare('SELECT id, cc FROM exits WHERE id = ?').bind(id).first();
  if (!before) return studioErr('not_found', 'no such exit', 404);
  await env.DB.batch([
    env.DB.prepare('DELETE FROM exits WHERE id = ?').bind(id),
    studioAuditStmt(env, actor, 'exit.delete', id, { cc: before.cc }, null, 'exit'),
  ]);
  studioInvalidateExitCache();
  EXIT_STATE.delete(id);
  return studioJson({ ok: true, id, deleted: true });
}

/**
 * `POST /v1/exits/{id}:test` — dial through it, and learn where it really leaves from.
 *
 * An exit filed under a country it does not leave from is the worst kind of wrong: it works, and
 * everybody on that "location" is somewhere else. So the measured country is stored, and an exit
 * added without one (`ZZ`) is filed under it.
 */
async function studioTestExit(env, id) {
  const row = await env.DB.prepare(`SELECT ${STUDIO_EXIT_COLUMNS} FROM exits WHERE id = ?`).bind(id).first();
  if (!row) return studioErr('not_found', 'no such exit', 404);
  const r = await studioProbeExit(row.url);
  const now = Date.now();
  const cc = row.cc === 'ZZ' && r.exit_cc ? r.exit_cc : row.cc;
  await env.DB.prepare(
    'UPDATE exits SET health = ?, fails = ?, exit_ip = ?, exit_cc = ?, latency_ms = ?, checked_at = ?, cc = ? WHERE id = ?'
  ).bind(r.ok ? 'up' : 'down', r.ok ? 0 : (row.fails || 0) + 1, r.exit_ip || row.exit_ip || null,
    r.exit_cc || row.exit_cc || null, r.latency_ms, now, cc, id).run();
  studioInvalidateExitCache();
  EXIT_STATE.delete(id);
  const after = await env.DB.prepare(`SELECT ${STUDIO_EXIT_COLUMNS} FROM exits WHERE id = ?`).bind(id).first();
  return studioJson({ ...studioExitDto(after), ok: r.ok, error: r.error || null });
}

/** Returns null when the path is not its business, so `studioHandle` stays a list. */
async function studioExitsRoute(request, env, path, method, actor) {
  if (path === '/exits:import') {
    if (method !== 'POST') return studioErr('method_not_allowed', 'actions are POST', 405);
    return await studioImportExits(request, env, actor);
  }
  if (path === '/exits') {
    if (method === 'GET') return await studioListExits(env);
    if (method === 'POST') return await studioAddExits(request, env, actor);
    return studioErr('method_not_allowed', method + ' is not allowed here', 405);
  }
  if (!path.startsWith('/exits/')) return null;
  const rest = path.slice('/exits/'.length);
  if (rest.endsWith(':test') && method === 'POST') return await studioTestExit(env, rest.slice(0, -5));
  if (!rest || rest.includes('/') || rest.includes(':')) return null;
  if (method === 'PATCH') return await studioPatchExit(request, env, rest, actor);
  if (method === 'DELETE') return await studioDeleteExit(env, rest, actor);
  return studioErr('method_not_allowed', method + ' is not allowed here', 405);
}

/** Persian names for the countries a location is usually picked from; others show their code. */
const STUDIO_CC_FA = {
  DE: 'آلمان', NL: 'هلند', GB: 'انگلیس', FR: 'فرانسه', FI: 'فنلاند', SE: 'سوئد', US: 'آمریکا', CA: 'کانادا',
  TR: 'ترکیه', AE: 'امارات', AM: 'ارمنستان', RU: 'روسیه', JP: 'ژاپن', SG: 'سنگاپور', IT: 'ایتالیا',
  ES: 'اسپانیا', PL: 'لهستان', AT: 'اتریش', CH: 'سوئیس', RO: 'رومانی', NO: 'نروژ', DK: 'دانمارک',
  BG: 'بلغارستان', LV: 'لتونی', KZ: 'قزاقستان', IN: 'هند', HK: 'هنگ‌کنگ', KR: 'کره جنوبی', AU: 'استرالیا',
  BR: 'برزیل', QA: 'قطر', SA: 'عربستان', OM: 'عمان', IQ: 'عراق', GE: 'گرجستان', UA: 'اوکراین', CZ: 'چک',
  HU: 'مجارستان', PT: 'پرتغال', IE: 'ایرلند', LU: 'لوکزامبورگ', BE: 'بلژیک', GR: 'یونان', CY: 'قبرس',
  TH: 'تایلند', MY: 'مالزی', ID: 'اندونزی', VN: 'ویتنام', UZ: 'ازبکستان', AZ: 'آذربایجان', TW: 'تایوان',
  MX: 'مکزیک', ZA: 'آفریقای جنوبی', AR: 'آرژانتین',
};

/** «🇩🇪 آلمان» — what a person reads to tell one location config from another. */
function studioCountryLabel(cc) {
  if (!studioValidCc(cc)) return '';
  return studioFlag(cc) + ' ' + (STUDIO_CC_FA[cc] || cc);
}

/** A regional-indicator flag for a country code, or '' for anything that is not one. */
function studioFlag(cc) {
  if (!studioValidCc(cc)) return '';
  return String.fromCodePoint(0x1F1E6 + cc.charCodeAt(0) - 65, 0x1F1E6 + cc.charCodeAt(1) - 65);
}
