// ==========================================================
// Config Studio  —  /v1/pool   («لوکیشن‌های عمومی»)
// ==========================================================
//
// Build 17. Locations without bringing a server of your own — OPT-IN, and said plainly.
//
// An exit (04j) is a SOCKS5/HTTP server the operator brings. For a country with none, the operator
// may switch this on: the engine then takes a public, per-country SOCKS5 list (by default
// proxifly/free-proxy-list, GPL-3.0, fetched at runtime by the Worker on the operator's own account
// — nothing of it ships in this code) and sends that country's connections through one of them.
//
// Those are STRANGERS' servers. Whoever runs one sees which sites a person reaches and anything that
// is not encrypted end to end, and can change or drop traffic. So:
//
//  * OFF by default. Only the operator turns it on, from the app, after the app has shown the risks
//    (the operator chose this knowingly; see the Android «مولتی لوکیشن» screen).
//  * The person using the config is told too: every config that can leave through a public server
//    carries «(عمومی)» in its name, and the subscription page says what that means (04c/04d).
//  * The operator's own exits always come first; the pool is only asked when a country has none of
//    them, or every one just failed.
//  * No leak, exactly as 04j: a config with a country never falls back to a direct connection.
//  * Health lives in memory and is never written to D1; a list is fetched at most once per country
//    per isolate per quarter hour, and the edge cache shares it between isolates.

const POOL_DEFAULT_SOURCE =
  'https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/countries/{CC}/data.txt';
const POOL_MIRROR_SOURCE =
  'https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/countries/{CC}/data.txt';

/** Countries the default source reliably carries, for the pickers. Any valid code still works. */
const POOL_COUNTRIES = [
  'DE', 'NL', 'FR', 'GB', 'US', 'CA', 'TR', 'AE', 'SE', 'FI', 'PL', 'IT', 'ES', 'CH', 'AT',
  'RU', 'UA', 'RO', 'CZ', 'HU', 'BG', 'GR', 'JP', 'SG', 'KR', 'HK', 'TW', 'IN', 'ID', 'VN',
  'TH', 'MY', 'BR', 'AR', 'MX', 'AU', 'ZA',
];

/** What the person using a pool config is told, in the config name and on the sub page. */
const POOL_WARNING_TAG = '(عمومی)';
const POOL_WARNING_TEXT =
  'کانفیگ‌هایی که «عمومی» دارند از سرورهای عمومی و رایگانِ افراد ناشناس رد می‌شوند. صاحب آن سرور ' +
  'می‌تواند ببیند به چه سایت‌هایی می‌روید و هرچه رمزنگاری‌نشده باشد را بخواند. با آن‌ها وارد حساب بانکی ' +
  'یا حساب‌های مهم نشوید و اطلاعات حساس نفرستید. این سرورها زود عوض یا خاموش می‌شوند و ممکن است کند باشند.';

const POOL_LIST_TTL_MS = 15 * 60 * 1000;
const POOL_MAX_CANDIDATES = 80;
const POOL_DIAL_TIMEOUT_MS = 3500;
const POOL_TRIES = 5;

/** cc -> { at, urls } */
const POOL_LISTS = new Map();
/** url -> { fails, downUntil } — this isolate's view only. */
const POOL_STATE = new Map();
/** cc -> urls that answered lately, newest first. What a new connection tries first. */
const POOL_GOOD = new Map();
/** Settings, read at most once a minute per isolate. OFF until the operator says otherwise. */
let POOL_SETTINGS = { at: 0, enabled: false, source: POOL_DEFAULT_SOURCE };

async function studioPoolSettings(env) {
  const now = Date.now();
  if (now - POOL_SETTINGS.at < 60000) return POOL_SETTINGS;
  let enabled = false, source = POOL_DEFAULT_SOURCE;
  try {
    const res = await env.DB.prepare(
      "SELECT key, value FROM settings WHERE key IN ('pool_enabled', 'pool_source')"
    ).all();
    for (const r of (res && res.results) || []) {
      if (r.key === 'pool_enabled') enabled = r.value === '1';
      if (r.key === 'pool_source' && /^https:\/\/[^\s]+\{CC\}/.test(r.value || '')) source = r.value;
    }
  } catch (e) { /* no settings row: off */ }
  POOL_SETTINGS = { at: now, enabled, source };
  return POOL_SETTINGS;
}

/** `socks5://h:p`, `http://h:p`, or a bare `h:p` taken as SOCKS5. Anything else is skipped. */
function studioPoolParse(text) {
  const out = [];
  const seen = new Set();
  for (const raw of String(text || '').split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    let url = null;
    if (/^socks5h?:\/\//i.test(line)) url = 'socks5://' + line.replace(/^socks5h?:\/\//i, '');
    else if (/^http:\/\//i.test(line)) url = line;
    else if (/^[\w.-]+:\d{2,5}$/.test(line)) url = 'socks5://' + line;
    if (!url || !studioParseExitUrl(url) || seen.has(url)) continue;
    seen.add(url);
    out.push(url);
  }
  // SOCKS5 first: it carries any port, where an HTTP CONNECT proxy often refuses all but 443.
  out.sort((a, b) => (a.startsWith('socks5') === b.startsWith('socks5') ? 0 : a.startsWith('socks5') ? -1 : 1));
  return out.slice(0, POOL_MAX_CANDIDATES);
}

async function studioPoolList(env, cc) {
  const now = Date.now();
  const hit = POOL_LISTS.get(cc);
  if (hit && now - hit.at < POOL_LIST_TTL_MS) return hit.urls;
  const settings = await studioPoolSettings(env);
  const sources = [settings.source];
  if (settings.source === POOL_DEFAULT_SOURCE) sources.push(POOL_MIRROR_SOURCE);
  let urls = [];
  for (const src of sources) {
    try {
      const res = await fetch(src.replace('{CC}', cc), {
        headers: { 'User-Agent': 'mlm-studio-pool' },
        cf: { cacheTtl: 600, cacheEverything: true },
      });
      if (!res.ok) continue;
      urls = studioPoolParse(await res.text());
      if (urls.length) break;
    } catch (e) { /* try the next source */ }
  }
  // An empty answer keeps the last good list: a GitHub hiccup must not read as "no servers".
  if (!urls.length && hit) return hit.urls;
  POOL_LISTS.set(cc, { at: now, urls });
  return urls;
}

function studioPoolNote(cc, url, ok) {
  const now = Date.now();
  const st = POOL_STATE.get(url) || { fails: 0, downUntil: 0 };
  if (ok) {
    st.fails = 0; st.downUntil = 0;
    const good = (POOL_GOOD.get(cc) || []).filter((u) => u !== url);
    good.unshift(url);
    POOL_GOOD.set(cc, good.slice(0, 8));
  } else {
    st.fails++;
    // A dead public server rarely comes back within minutes.
    st.downUntil = now + Math.min(30 * 60000, 60000 * st.fails * st.fails);
    POOL_GOOD.set(cc, (POOL_GOOD.get(cc) || []).filter((u) => u !== url));
  }
  POOL_STATE.set(url, st);
  if (POOL_STATE.size > 4000) POOL_STATE.clear();
}

/** Recently good ones first (sticky on [key] among them), then a stable rotation of the rest. */
function studioPoolOrder(cc, urls, key) {
  const now = Date.now();
  const alive = (u) => { const st = POOL_STATE.get(u); return !(st && st.downUntil > now); };
  const good = (POOL_GOOD.get(cc) || []).filter(alive);
  const rest = urls.filter((u) => alive(u) && !good.includes(u));
  const out = [];
  if (good.length) {
    const s = studioHashStr(key) % good.length;
    for (let i = 0; i < good.length; i++) out.push(good[(s + i) % good.length]);
  }
  if (rest.length) {
    const s = studioHashStr(key + '#') % rest.length;
    for (let i = 0; i < rest.length; i++) out.push(rest[(s + i) % rest.length]);
  }
  return out;
}

/** Whether the pool may be used right now — for the dialler and for labelling configs. */
async function studioPoolEnabled(env) {
  return (await studioPoolSettings(env)).enabled;
}

/**
 * Out through a public server from the raw list, or throw. Never direct.
 *
 * The last resort, for a country with nothing verified yet: it asks for a verification in the
 * background and meanwhile races the listed servers two at a time -- two tries of 3.5 s rather than
 * five in a row, which a client gave up on long before the fifth.
 */
async function studioDialPool(env, ctx, cc, stickyKey, host, port, initialData, pin) {
  if (!(await studioPoolEnabled(env))) throw new Error('no exit for ' + cc);
  studioPoolVerifySoon(env, ctx, cc);
  const order = studioPoolOrder(cc, await studioPoolList(env, cc), stickyKey + '|' + cc)
    .filter((u) => !studioExitDown(u));
  if (!order.length) throw new Error('no public server for ' + cc);
  let lastErr = null;
  for (let i = 0; i < Math.min(order.length, POOL_TRIES - 1); i += 2) {
    const pair = order.slice(i, i + 2).map((url) => ({ url, ip: null, pool: true }));
    try {
      const { sock, cand } = await studioRaceExits(pair, host, port, initialData);
      studioPoolNote(cc, cand.url, true);
      if (pin && pin.configId) studioRepin(env, ctx, pin.configId, cand);
      return sock;
    } catch (e) {
      lastErr = e;
      for (const c of pair) studioPoolNote(cc, c.url, false);
    }
  }
  throw lastErr || new Error('no public server answered for ' + cc);
}

// ---------------------------------------------------------------------------- the verified pool

/**
 * Per country, the public servers that were TESTED and really leave from there (schema 19).
 *
 * The public lists label a server by where its listener is registered, not by where its traffic
 * leaves, and nothing checked before sending people through one -- so a «آلمان» config could come
 * out anywhere, or nowhere. A server enters this list only after a request through it saw an address
 * in that country, and a location config is pinned to one of these (04j › studioPickPin).
 *
 * Stored as one row per country so that verifying costs one write, and cached for a minute per
 * isolate so that reading it costs nothing per connection.
 */
const POOL_VERIFIED = new Map();
/** A verified server older than this is tested again before it is trusted for a new config. */
const POOL_VERIFIED_FRESH_MS = 60 * 60000;
/** Re-verify a country this often while it is in use. */
const POOL_VERIFIED_REFRESH_MS = 20 * 60000;
const POOL_VERIFIED_MAX = 12;
/** cc -> when a background verification last started in this isolate. */
const POOL_VERIFY_AT = new Map();

async function studioPoolVerifiedRow(env, cc) {
  try {
    const row = await env.DB.prepare('SELECT list, checked_at FROM pool_verified WHERE cc = ?').bind(cc).first();
    if (!row) return { at: 0, list: [] };
    const list = JSON.parse(row.list || '[]');
    return { at: Number(row.checked_at) || 0, list: Array.isArray(list) ? list : [] };
  } catch (e) {
    return { at: 0, list: [] };
  }
}

/** The verified servers for [cc] this isolate would use now: empty when the pool is off. */
async function studioPoolVerifiedFor(env, ctx, cc) {
  if (!studioValidCc(cc) || !(await studioPoolEnabled(env))) return [];
  const now = Date.now();
  let hit = POOL_VERIFIED.get(cc);
  if (!hit || now - hit.readAt > 60000) {
    const row = await studioPoolVerifiedRow(env, cc);
    hit = { readAt: now, at: row.at, list: row.list };
    POOL_VERIFIED.set(cc, hit);
  }
  const alive = hit.list.filter((v) => v && v.u && !studioExitDown(v.u));
  if (alive.length < 2 || now - hit.at > POOL_VERIFIED_REFRESH_MS) studioPoolVerifySoon(env, ctx, cc);
  return alive;
}

/** Fastest first; the same person lands on the same one of the three fastest. */
function studioPoolOrderVerified(list, key) {
  const sorted = [...list].sort((a, b) => (a.ms || 9e9) - (b.ms || 9e9));
  const top = sorted.slice(0, 3);
  const out = [];
  if (top.length) {
    const s = studioHashStr(key) % top.length;
    for (let i = 0; i < top.length; i++) out.push(top[(s + i) % top.length]);
  }
  return out.concat(sorted.slice(3));
}

/** A small verification in the background, at most every two minutes per country per isolate. */
function studioPoolVerifySoon(env, ctx, cc) {
  const now = Date.now();
  if (now - (POOL_VERIFY_AT.get(cc) || 0) < 120000) return;
  POOL_VERIFY_AT.set(cc, now);
  const task = studioPoolVerify(env, cc, 6).catch(() => { });
  if (ctx && ctx.waitUntil) ctx.waitUntil(task);
}

/**
 * Test up to [budget] untested public servers for [cc] -- through themselves, asking where they
 * leave from -- and store the best of what passed together with what was already verified.
 *
 * Six at a time and at most [budget] per call, which keeps one request inside what the free plan
 * allows a Worker invocation to open. The app calls this repeatedly (`POST /v1/pool/verify`) while
 * its Locations screen is open, and the data plane asks for small batches when it runs short.
 */
async function studioPoolVerify(env, cc, budget) {
  const now = Date.now();
  const current = await studioPoolVerifiedRow(env, cc);
  const fresh = current.list.filter((v) => v && v.u && now - (Number(v.at) || 0) < POOL_VERIFIED_FRESH_MS);
  const known = new Set(fresh.map((v) => v.u));
  const all = await studioPoolList(env, cc);
  const batch = studioPoolOrder(cc, all, 'verify|' + cc)
    .filter((u) => !known.has(u) && !studioExitDown(u))
    .slice(0, Math.max(1, budget));

  const found = [];
  let next = 0;
  const worker = async () => {
    while (next < batch.length) {
      const url = batch[next++];
      const r = await studioProbeExit(url);
      if (r.ok && r.exit_cc === cc) {
        found.push({ u: url, ip: r.exit_ip || null, cc: r.exit_cc, ms: r.latency_ms || null, at: Date.now() });
        studioPoolNote(cc, url, true);
      } else {
        // Failed or left from somewhere else: not used for this country, not even as a last resort.
        studioPoolNote(cc, url, false);
        EXIT_URL_STATE.set(url, { fails: 2, downUntil: Date.now() + 30 * 60000 });
      }
    }
  };
  await Promise.all(Array.from({ length: Math.min(6, batch.length) }, worker));

  const merged = [...fresh, ...found]
    .sort((a, b) => (a.ms || 9e9) - (b.ms || 9e9))
    .slice(0, POOL_VERIFIED_MAX);
  try {
    await env.DB.prepare('INSERT OR REPLACE INTO pool_verified (cc, list, checked_at) VALUES (?, ?, ?)')
      .bind(cc, JSON.stringify(merged), Date.now()).run();
  } catch (e) { /* schema below 19: the list still serves this isolate */ }
  POOL_VERIFIED.set(cc, { readAt: Date.now(), at: Date.now(), list: merged });
  return { cc, verified: merged, tested: batch.length, found: found.length, candidates: all.length };
}

/** What the app is shown of a verified server: never the proxy's address, only what it measured. */
function studioPoolVerifiedDto(v) {
  return { exit_ip: v.ip || null, exit_cc: v.cc || null, latency_ms: v.ms == null ? null : v.ms, checked_at: v.at || null };
}

/** `GET /v1/pool/test?cc=DE` — find a working server and say where it really leaves from. */
async function studioPoolTest(env, cc) {
  if (!studioValidCc(cc)) return studioErr('bad_request', 'cc must be two capital letters', 400);
  const urls = await studioPoolList(env, cc);
  const order = studioPoolOrder(cc, urls, 'test|' + cc);
  let tried = 0;
  for (const url of order.slice(0, 6)) {
    tried++;
    const r = await studioProbeExit(url);
    if (r.ok) {
      studioPoolNote(cc, url, true);
      return studioJson({ cc, ok: true, candidates: urls.length, tried, exit_ip: r.exit_ip, exit_cc: r.exit_cc, latency_ms: r.latency_ms });
    }
    studioPoolNote(cc, url, false);
  }
  return studioJson({ cc, ok: false, candidates: urls.length, tried });
}

async function studioPoolRoute(request, env, path, method, actor) {
  if (path === '/pool') {
    if (method === 'GET') {
      const s = await studioPoolSettings(env);
      // How many verified servers each country has right now, so the app can say which countries
      // are really ready rather than listing every country the source might carry.
      const verified = {};
      try {
        const res = await env.DB.prepare('SELECT cc, list, checked_at FROM pool_verified').all();
        const now = Date.now();
        for (const r of (res && res.results) || []) {
          let list = [];
          try { list = JSON.parse(r.list || '[]'); } catch (e) { }
          verified[r.cc] = list.filter((v) => v && now - (Number(v.at) || 0) < POOL_VERIFIED_FRESH_MS).length;
        }
      } catch (e) { /* schema below 19 */ }
      return studioJson({
        enabled: s.enabled, source: s.source, default_source: POOL_DEFAULT_SOURCE,
        countries: POOL_COUNTRIES, warning: POOL_WARNING_TEXT, verified,
      });
    }
    if (method === 'PATCH') {
      let body = {};
      try { body = await request.json(); } catch (e) { return studioErr('bad_request', 'body must be JSON', 400); }
      const stmts = [];
      if (typeof body.enabled === 'boolean') {
        // Turning it on is an explicit acknowledgement of the risks; the app sends it only from
        // the confirmation that lists them.
        if (body.enabled && body.risks_acknowledged !== true) {
          return studioErr('bad_request', 'turning the public pool on needs risks_acknowledged', 400);
        }
        stmts.push(env.DB.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES ('pool_enabled', ?)").bind(body.enabled ? '1' : '0'));
      }
      if (typeof body.source === 'string') {
        const src = body.source.trim();
        if (src && !/^https:\/\/[^\s]+\{CC\}/.test(src)) {
          return studioErr('bad_request', 'source must be an https URL containing {CC}', 400);
        }
        stmts.push(env.DB.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES ('pool_source', ?)").bind(src || POOL_DEFAULT_SOURCE));
      }
      if (stmts.length) {
        stmts.push(studioAuditStmt(env, actor, 'pool.settings', null, null, { enabled: body.enabled, source: body.source }));
        await env.DB.batch(stmts);
      }
      POOL_SETTINGS.at = 0;
      POOL_LISTS.clear();
      const s = await studioPoolSettings(env);
      return studioJson({ enabled: s.enabled, source: s.source });
    }
    return studioErr('method_not_allowed', method + ' is not allowed here', 405);
  }
  if (path === '/pool/test' && method === 'GET') {
    const cc = String(new URL(request.url).searchParams.get('cc') || '').toUpperCase();
    return await studioPoolTest(env, cc);
  }
  // Build 18: test a batch of this country's public servers and keep the ones that really leave
  // from it. The app repeats it until the country has enough, while its Locations screen is open.
  if (path === '/pool/verify' && method === 'POST') {
    const cc = String(new URL(request.url).searchParams.get('cc') || '').toUpperCase();
    if (!studioValidCc(cc)) return studioErr('bad_request', 'cc must be two capital letters', 400);
    if (!(await studioPoolEnabled(env))) return studioErr('pool_off', 'the public pool is switched off', 409);
    const r = await studioPoolVerify(env, cc, 12);
    return studioJson({
      cc, tested: r.tested, found: r.found, candidates: r.candidates,
      verified: r.verified.map(studioPoolVerifiedDto),
    });
  }
  return null;
}
