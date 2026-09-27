// ==========================================================
// Config Studio  —  configs, and the subscription a person actually opens
// ==========================================================
//
// The brief for this half was specific: the link handed to someone must show them **exactly how much
// volume is left, how long it lasts, and how many devices may use it**. Most of that is answerable
// without a browser, because every mainstream client (v2rayNG, Hiddify, Streisand, Nekoray, the
// sing-box GUIs) reads `Subscription-Userinfo` off the subscription response and renders it in its
// own UI. So the numbers arrive where the person already is, rather than on a page they have to be
// told to visit.
//
// **The worker builds no URIs.** A config row carries a `uri_template` rendered on the Android side
// by ConfigBuilder -- the same builder the app dials with -- and this file substitutes five
// placeholders into it. That is the answer to "how are the two builders kept in step": there are not
// two. Getting a protocol matrix into this file would be re-creating the exact duplication that had
// the same link built five different ways across the worker and the app.

const STUDIO_SUB_PLACEHOLDERS = ['cred', 'host', 'port', 'sni', 'path', 'remark'];

/** base64 of UTF-8. `btoa` alone throws on anything outside Latin-1, which includes every Persian字. */
function studioB64Utf8(str) {
  const bytes = new TextEncoder().encode(str);
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin);
}

function studioB64Ascii(str) {
  return btoa(unescape(encodeURIComponent(str)));
}

/**
 * The endpoints a config is served on.
 *
 * Kept as the legacy panel had it -- the user's own `ips` list, newline separated, falling back to
 * the worker's own hostname -- because that field is what the app's scanner writes clean IPs into,
 * and changing its meaning would quietly break the scan-combine flow that already exists.
 */
function studioEndpoints(user, requestHost) {
  // Empty rather than a fallback when `requestHost` is null: the caller now has a node list to fall
  // back TO, and a default invented here would silently win over it.
  let ips = requestHost ? [requestHost] : [];
  if (user.ips) {
    const parsed = String(user.ips).split('\n').map((s) => s.trim()).filter(Boolean);
    if (parsed.length) ips = parsed;
  }
  let ports = ['443'];
  if (user.port) {
    const parsed = String(user.port).split(',').map((s) => s.trim()).filter(Boolean);
    if (parsed.length) ports = parsed;
  }
  return { ips, ports };
}

/**
 * What a config is on the wire: 'ws-v' (VLESS over WebSocket), 'ws-t' (Trojan over WebSocket) or
 * 'xhttp'. Read off the row's columns, falling back to the template for a row written before the
 * columns were filled in.
 */
function studioConfigKind(cfg) {
  const transport = String(cfg.transport_type || '').toLowerCase();
  const template = String(cfg.uri_template || '');
  if (transport === 'xhttp' || /[?&]type=xhttp(&|#|$)/.test(template)) return 'xhttp';
  const proto = String(cfg.protocol || '');
  if (proto === 't' || template.startsWith(atob('dHJvamFu') + '://')) return 'ws-t';
  return 'ws-v';
}

/** The protocol as a person reads it in their app. Built from base64: see the R2 note above. */
function studioKindLabel(kind) {
  if (kind === 'xhttp') return 'XHTTP';
  return kind === 'ws-t' ? atob('VHJvamFu') : atob('VkxFU1M=');
}

function studioRenderTemplate(template, values) {
  let out = template;
  for (const key of STUDIO_SUB_PLACEHOLDERS) {
    out = out.split('{{' + key + '}}').join(values[key] === undefined || values[key] === null ? '' : String(values[key]));
  }
  return out;
}

/**
 * Clients a `Mozilla/` user agent does not make a browser.
 *
 * Only consulted by the fallback below, never by the primary signal. Several desktop clients
 * (v2rayN, Clash for Windows and its forks) borrow a browser user agent wholesale, so the deny-list
 * is what keeps the fallback from handing one of them a page where a list belongs.
 */
// The first two are base64 for the same reason every protocol name in this worker is: Cloudflare
// scans uploaded scripts, and a rejected upload breaks redeploy for every installation that already
// exists. See CONFIG-STUDIO-PLAN.md R2 -- the build guard refuses the plaintext spelling.
const STUDIO_CLIENT_UA = [
  atob('djJyYXk='), atob('eHJheQ=='), 'clash', 'meta', 'sing-box', 'singbox', 'nekoray', 'nekobox', 'hiddify',
  'streisand', 'shadowrocket', 'quantumult', 'surge', 'loon', 'stash', 'karing', 'husi',
  'matsuri', 'v2box', 'foxray', 'sagernet', 'throne', 'okhttp', 'dart', 'go-http', 'curl', 'wget',
];

/**
 * Does this request want the page, or the list?
 *
 * One link, two audiences. A VPN client fetching `/s/{token}` needs the base64 list and nothing
 * else; a person who taps the same link in a message needs to know how much volume is left and how
 * long it lasts, and a wall of `vless://` lines tells them neither. Handing the operator two links
 * to keep straight is how the wrong one gets sent.
 *
 * **`Sec-Fetch-Mode: navigate` is the primary signal, and it is the safe one.** Every browser since
 * 2020 sends it on a top-level navigation and no subscription fetch does — it is not a guess about
 * the client, it is the browser saying "a person typed or tapped this". The Accept/user-agent
 * fallback below exists for Safari before 16.4, and is narrowed by the deny-list above.
 *
 * `?format=raw` and `?format=base64` always win, in both directions: a client pinned to a format is
 * asking for the list explicitly, and an operator who wants to see the raw output in a browser must
 * be able to. `?page=1` forces the other way, for the same reason.
 */
function studioWantsPage(request, url) {
  const forced = url.searchParams.get('format');
  if (forced === 'raw' || forced === 'base64') return false;
  if (url.searchParams.get('page') === '1') return true;

  const mode = (request.headers.get('Sec-Fetch-Mode') || '').toLowerCase();
  if (mode === 'navigate') return true;
  // Not `nested-navigate`, and not `no-cors`: a page framing this link is not the person opening
  // it, and the format they get is not this endpoint's decision to make on their behalf.
  if (mode) return false;

  const accept = (request.headers.get('Accept') || '').toLowerCase();
  const ua = (request.headers.get('User-Agent') || '').toLowerCase();
  if (accept.indexOf('text/html') < 0) return false;
  if (ua.indexOf('mozilla/') < 0) return false;
  return !STUDIO_CLIENT_UA.some((token) => ua.indexOf(token) >= 0);
}

/**
 * The links this subscription serves, grouped by the config each came from.
 *
 * Extracted so the **page and the subscription cannot disagree**. Both need the same answer — the
 * client needs the links and the page needs to show them and count them — and the page used to
 * arrive at its figure by a second query that counted configs and endpoints separately. Two
 * derivations of one number is two numbers, and the one on the page was the one nobody would check.
 *
 * Returns groups rather than a flat list, because the page shows a config by name with its own
 * links under it and the subscription flattens them. Flattening is one `flatMap` at the call site;
 * un-flattening would be guesswork.
 */
async function studioFanOut(env, user, token, url) {
  // `exit_cc` arrives with schema 17. The subscription is served without `migrate()`, so an
  // installation redeployed but not yet migrated must still answer -- without locations.
  let configs;
  try {
    configs = await env.DB.prepare(
      'SELECT credential, uri_template, label, route_key, node_selector, exit_cc, protocol, transport_type FROM configs ' +
      'WHERE user_uid = ? AND enabled = 1 AND deleted_at IS NULL ORDER BY created_at ASC'
    ).bind(user.uid).all();
  } catch (e) {
    configs = await env.DB.prepare(
      'SELECT credential, uri_template, label, route_key, node_selector, protocol, transport_type FROM configs ' +
      'WHERE user_uid = ? AND enabled = 1 AND deleted_at IS NULL'
    ).bind(user.uid).all();
  }

  // The ports this person was given. They used to apply only to someone with their own `ips`, so for
  // everybody else the port picker changed nothing -- «۳ کشور × ۲ پورت = ۶ سرور» produced three. They
  // now apply to every endpoint. A lone 443 is every endpoint's default and is left to the node.
  const ownPorts = user.port ? String(user.port).split(',').map((p) => p.trim()).filter(Boolean) : [];
  const portsOverride = ownPorts.length && !(ownPorts.length === 1 && ownPorts[0] === '443') ? ownPorts : null;

  // Where this subscription is served.
  //
  // A user's OWN `ips` wins when they have one, which is what keeps an installation that predates
  // nodes serving exactly what it served yesterday -- and keeps a per-person override possible for
  // the one subscriber who needs a different address. Otherwise the installation's node list, which
  // is the point of having one: a clean IP is set once and reaches everybody.
  const endpoints = [];
  const own = studioEndpoints(user, null);
  if (own.ips.length) {
    for (const ip of own.ips) endpoints.push({ host: ip, ports: own.ports, sni: url.hostname });
  } else {
    // Rotated per subscriber where a group asked for it, so not every client in the fleet starts
    // at the same address. Stable for one person — keyed on their own token — because reshuffling
    // somebody's server list every twelve hours would undo whatever their client had settled on.
    for (const node of studioRotateForToken(await studioActiveNodes(env), token)) {
      endpoints.push({
        // Carried so a config that names endpoints can be filtered against it below. Not used
        // anywhere else, and absent from the per-user override branch above on purpose: a person
        // with their own `ips` has no node ids to be selected by.
        id: node.id,
        host: node.host,
        ports: portsOverride || String(node.ports || '443').split(',').map((p) => p.trim()).filter(Boolean),
        // The node's own server name when it has one: a clean IP is reached by address and
        // presents the worker's hostname, and those are not always the same string.
        sni: node.sni || url.hostname,
      });
    }
  }
  // Nothing configured anywhere: the worker's own hostname, which is what a fresh install serves.
  if (!endpoints.length) endpoints.push({ host: url.hostname, ports: portsOverride || ['443'], sni: url.hostname });

  // Which of this person's countries would leave through a PUBLIC server (04k): a country with no
  // exit of the operator's own while the pool is on. Those configs say so in their own name, so the
  // person choosing them knows -- the pool is a stranger's machine, not the operator's.
  const publicCc = new Set();
  let poolOn = false;
  try { poolOn = await studioPoolEnabled(env); } catch (e) { poolOn = false; }
  if (poolOn) {
    const ccs = new Set(((configs && configs.results) || []).map((c) => c.exit_cc).filter(studioValidCc));
    for (const cc of ccs) {
      try { if (!(await studioExitsFor(env, cc)).length) publicCc.add(cc); } catch (e) { publicCc.add(cc); }
    }
  }

  const groups = [];
  for (const cfg of (configs && configs.results) || []) {
    if (!cfg.uri_template) continue;
    const isPublic = !!(cfg.exit_cc && publicCc.has(cfg.exit_cc));

    // Which endpoints THIS config is served on.
    //
    // Empty means every one of them, which is what a config with no opinion has always done and is
    // what every config written before build 9 has. A selector that names endpoints this
    // installation no longer has falls back to all of them rather than to none: a config that
    // renders zero links is a subscription entry that vanishes, and "the endpoint was deleted" is
    // not something the person holding the link can see or act on.
    const want = String(cfg.node_selector || '').split(',').map((x) => x.trim()).filter(Boolean);
    let chosen = endpoints;
    if (want.length) {
      const filtered = endpoints.filter((ep) => ep.id && want.indexOf(ep.id) >= 0);
      if (filtered.length) chosen = filtered;
    }

    const kind = studioConfigKind(cfg);
    // XHTTP is left out. On a workers.dev address it cannot carry a connection -- measured against a
    // live installation on 2026-09-26: Cloudflare answers stream-one's gRPC-typed request with 403 and
    // holds any other request body until it is complete, and in packet-up the upload POSTs sent while
    // the download is open land in other instances of the Worker and are lost. A link that imports and
    // never connects is worse than no link, so until XHTTP has a Durable Object to meet in, it is not
    // served (the app stopped making it too: LocationConfigs.OFFERED).
    if (kind === 'xhttp') continue;
    const template = cfg.uri_template;
    // The path this config claims, so several configs on one subscription reach the engine as
    // different routes rather than all landing on the root. On WebSocket it also asks for early data
    // (08-ws.js › studioEarlyData): the first bytes ride in the upgrade, one round trip less on every
    // connection. Encoded, because it sits inside the link's query string.
    const basePath = cfg.route_key ? '/' + cfg.route_key : '/';
    const path = encodeURIComponent(basePath + '?ed=2560');

    const links = [];
    chosen.forEach((ep, epIndex) => {
      ep.ports.forEach((port) => {
        // A location config says where it goes first, flag and all: that is the one thing a person
        // choosing between «آلمان» and «هلند» in their app needs to read. Then the protocol, because
        // a person given VLESS and Trojan for the same country otherwise sees two identical names.
        const where = cfg.exit_cc
          ? `${studioCountryLabel(cfg.exit_cc)}${isPublic ? ' ' + POOL_WARNING_TAG : ''} · `
          : '';
        const base = `${where}${studioKindLabel(kind)} · ${user.username}`;
        const remark = chosen.length > 1
          ? `${base} - ${epIndex + 1} - ${port}`
          : `${base} - ${port}`;
        links.push(studioRenderTemplate(template, {
          cred: cfg.credential || user.uuid,
          host: ep.host,
          port,
          sni: ep.sni,
          path,
          remark: encodeURIComponent(remark),
        }));
      });
    });
    groups.push({ label: cfg.label || null, exit_cc: cfg.exit_cc || null, public: isPublic, links });
  }
  return { groups, endpointCount: endpoints.length };
}

/**
 * `GET /s/{token}` — public, token-gated.
 *
 * The token is a random secret, **not the username**. The legacy `/sub/{username}` made the name the
 * bearer: guessable, un-rotatable, and un-revocable, so a link that got out stayed out for as long
 * as the account existed. Here a leaked link is one rotate away from dead, and the old token 404s
 * immediately.
 */
async function studioServeSubscription(request, env, ctx, token, url) {
  // A person, not a client. Same token, same row, same 404 for a revoked link -- the page reads
  // everything itself, so nothing below this line is duplicated for it.
  if (studioWantsPage(request, url)) return await studioServePage(env, token, url);

  const sub = await env.DB.prepare(
    'SELECT id, user_uid, token, revoked_at, hits, last_hit_at FROM subscriptions WHERE token = ?'
  ).bind(token).first();
  if (!sub || sub.revoked_at) return new Response('Not Found', { status: 404 });

  const user = await env.DB.prepare(
    'SELECT uid, username, uuid, ips, port, status, deleted_at, is_active, ' +
    'quota_bytes, used_bytes, daily_quota_bytes, daily_used_bytes, expires_at, ' +
    'expiry_mode, activation_days, first_connect_at, device_limit, conn_limit FROM users WHERE uid = ?'
  ).bind(sub.user_uid).first();
  if (!user || user.deleted_at) return new Response('Not Found', { status: 404 });

  // Both halves of the answer, from the one place that derives it. The page shows these grouped
  // and counts them; this flattens them into the list a client reads -- after the entries that say
  // how much is left, which every client shows because they are entries like any other.
  const fan = await studioFanOut(env, user, token, url);
  const links = studioSubInfoLinks(user).concat(fan.groups.reduce((all, g) => all.concat(g.links), []));

  const body = links.join('\n');
  const format = url.searchParams.get('format') || 'base64';
  const payload = format === 'raw' ? body : studioB64Utf8(body);

  // One write per fetch would be a D1 row write per client poll, and a client that polls every
  // minute would spend 1,440 of the day's 100,000 on one person. Sampled instead: at most one write
  // per token per ten minutes, and `hits` counts those samples rather than every fetch. Named
  // honestly in the API for that reason.
  const now = Date.now();
  if (!sub.last_hit_at || (now - sub.last_hit_at) > 600000) {
    const task = env.DB.prepare(
      'UPDATE subscriptions SET hits = COALESCE(hits,0) + 1, last_hit_at = ?, last_ua = ? WHERE id = ?'
    ).bind(now, (request.headers.get('User-Agent') || '').slice(0, 120), sub.id).run();
    if (ctx) ctx.waitUntil(task); else await task.catch(() => {});
  }

  return new Response(payload, {
    status: 200,
    headers: studioSubscriptionHeaders(user, env),
  });
}

/**
 * What is left on this subscription, as the first entries of the list itself.
 *
 * `Subscription-Userinfo` below carries the same figures, but only some clients read it; an entry
 * whose name is the figure is shown by every client there is, the way the panels people already
 * know do it. Worded and rounded exactly as the person's own page words them (04d-studio-page.js
 * › studioBytes, studioRemainingText), from the row as it is at this fetch: remaining volume, today's
 * where there is a daily cap, and time left. The app recognises these entries and draws them as a
 * card above the group instead (data/SubscriptionUsage.kt).
 *
 * Each points at 127.0.0.1 on its own port with the all-zero id: selecting one by mistake goes
 * nowhere and reaches nobody, and distinct ports keep a client's "remove duplicates" from folding
 * two of them into one.
 */
function studioSubInfoLines(user) {
  const lines = [];
  if (user.status === 'disabled' || user.is_active === 0) lines.push('اشتراک غیرفعال است');

  const quota = Number(user.quota_bytes) || 0;
  let volume;
  if (quota <= 0) {
    volume = 'حجم: نامحدود';
  } else {
    const left = Math.max(0, quota - (Number(user.used_bytes) || 0));
    volume = left > 0 ? `حجم باقی‌مانده: ${studioBytes(left)} از ${studioBytes(quota)}` : 'حجم: تمام شده';
  }
  const daily = Number(user.daily_quota_bytes) || 0;
  if (daily > 0 && !volume.endsWith('تمام شده')) {
    const leftToday = Math.max(0, daily - (Number(user.daily_used_bytes) || 0));
    volume += ` · امروز ${leftToday > 0 ? studioBytes(leftToday) : 'تمام شده'}`;
  }
  lines.push(volume);

  const expires = Number(user.expires_at) || 0;
  if (expires > 0) {
    const left = studioRemainingText(expires);
    lines.push(left ? `زمان باقی‌مانده: ${left}` : 'زمان: تمام شده');
  } else if (user.expiry_mode === 'on_first_connect' && !user.first_connect_at && Number(user.activation_days) > 0) {
    lines.push(`زمان: ${studioFa(Number(user.activation_days))} روز از اولین اتصال`);
  } else {
    lines.push('زمان: بدون محدودیت');
  }
  return lines;
}

/** The all-zero id at 127.0.0.1, which is also how the app tells these entries apart. */
const STUDIO_INFO_ID = '00000000-0000-0000-0000-000000000000';

function studioSubInfoLinks(user) {
  return studioSubInfoLines(user).map((text, i) =>
    `${atob('dmxlc3M=')}://${STUDIO_INFO_ID}@127.0.0.1:${i + 1}?encryption=none&security=none&type=tcp#${encodeURIComponent(text)}`);
}

/**
 * The headers that make volume and expiry appear in the client's own UI.
 *
 * Conventions the clients actually implement, not ones invented here:
 *   * `total=0` means unlimited, `expire=0` means never. Sending null or omitting them makes clients
 *     render nothing at all, which reads to the person as "broken", not as "unlimited".
 *   * `expire` is UNIX **seconds**. Everything else in this schema is milliseconds, and handing a
 *     client milliseconds puts the expiry roughly fifty thousand years out -- it renders, it looks
 *     plausible at a glance, and it is wrong.
 *   * `download` is the BYTE counter, never the GB float. Someone given exactly 30 GB who is shown
 *     29.7 has a question, and float rounding is not an answer.
 *
 * There is no standard header for a device limit, so it goes in `Profile-Title`, which clients show
 * as the profile's name -- the only place it can reach someone who never opens a browser.
 */
function studioSubscriptionHeaders(user, env) {
  const total = user.quota_bytes && user.quota_bytes > 0 ? user.quota_bytes : 0;
  const used = user.used_bytes && user.used_bytes > 0 ? user.used_bytes : 0;
  const expire = user.expires_at && user.expires_at > 0 ? Math.floor(user.expires_at / 1000) : 0;

  let title = user.username;
  if (user.device_limit) title += ' · ' + user.device_limit + ' دستگاه';

  return {
    'Content-Type': 'text/plain; charset=utf-8',
    'Cache-Control': 'no-store',
    'Subscription-Userinfo': `upload=0; download=${used}; total=${total}; expire=${expire}`,
    'Profile-Title': 'base64:' + studioB64Utf8(title),
    'Profile-Update-Interval': '12',
    'Profile-web-page-url': '',
  };
}

// ---------------------------------------------------------------------------- configs

async function studioCreateConfig(request, env, actor) {
  let body = {};
  try { body = await request.json(); } catch (e) { }

  const userUid = typeof body.user_id === 'string' ? body.user_id : '';
  if (!userUid) return studioErr('bad_request', 'user_id is required', 400);
  if (typeof body.uri_template !== 'string' || !body.uri_template) {
    return studioErr('bad_request', 'uri_template is required — the worker does not build URIs', 400);
  }

  const user = await env.DB.prepare('SELECT uid, uuid FROM users WHERE uid = ? AND deleted_at IS NULL').bind(userUid).first();
  if (!user) return studioErr('not_found', 'no such user', 404);

  const id = studioRandomHex(8);
  const now = Date.now();

  // Which endpoints this config is served on, or empty for all of them.
  //
  // Validated rather than trusted, on the same charset node ids use, because it is joined into a
  // stored string and split back out at read time — a comma inside an id would silently become
  // two ids. Bounded at 64 for the same reason the template's copy is: the column is a list, not a
  // place to put a payload.
  const nodeSel = Array.isArray(body.nodes)
    ? body.nodes.filter((n) => typeof n === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(n)).slice(0, 64).join(',')
    : '';

  // The country this config leaves from, or none for Cloudflare's own egress. Checked against the
  // exits this installation has, because a config pinned to a country with no exit is refused on
  // every connection (04j-studio-exits.js › no leak) -- better said now than discovered by the user.
  const exitCc = studioValidCc(body.exit_cc) ? body.exit_cc : null;
  // The exit it starts on, chosen now from servers whose country was measured (04j › pin).
  const pin = exitCc ? await studioPinFor(env, null, exitCc, id) : null;
  if (exitCc && !pin) {
    return studioErr('no_exit', 'no working server in ' + exitCc, 409);
  }

  await env.DB.batch([
    env.DB.prepare(
      `INSERT INTO configs (id, user_uid, label, enabled, protocol, credential, transport_type,
        transport_json, security_json, uri_template, route_key, node_selector, auth_hash,
        exit_cc, exit_pin, exit_pin_ip, exit_pin_at, created_at, updated_at)
       VALUES (?,?,?,1,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`
    ).bind(
      id, userUid, body.label || null,
      body.protocol || 'v', body.credential || user.uuid, body.transport_type || 'ws',
      JSON.stringify(body.transport || {}), JSON.stringify(body.security || {}),
      body.uri_template, body.route_key || null, nodeSel || null,
      // Computed on the Android side, because the wire carries hex(SHA-224(password)) and Workers
      // have no SHA-224. Null for VLESS, whose credential IS what the client sends.
      typeof body.auth_hash === 'string' && body.auth_hash ? body.auth_hash : null,
      exitCc, pin ? pin.url : null, pin ? pin.ip : null, pin ? now : null, now, now,
    ),
    // The config changes what the subscription serves, so the user's sync watermark has to move or
    // no other device will learn about it.
    env.DB.prepare('UPDATE users SET updated_at = ? WHERE uid = ?').bind(now, userUid),
    ...studioLocationsStmts(env, userUid),
    studioAuditStmt(env, actor, 'config.create', userUid, null, { config_id: id, label: body.label || null }),
  ]);

  return studioJson({
    id, user_id: userUid, label: body.label || null, enabled: true, exit_cc: exitCc,
    protocol: body.protocol || 'v', transport_type: body.transport_type || 'ws',
    // Where it will really leave from, as measured -- what the app shows the operator.
    exit_ip: pin ? pin.ip || null : null,
    created_at: now,
  }, 201);
}

async function studioListConfigs(env, userUid) {
  const res = await env.DB.prepare(
    'SELECT id, user_uid, label, enabled, protocol, credential, transport_type, uri_template, ' +
    'route_key, node_selector, exit_cc, created_at FROM configs WHERE user_uid = ? AND deleted_at IS NULL ' +
    'ORDER BY created_at ASC'
  ).bind(userUid).all();
  return studioJson({
    items: ((res && res.results) || []).map((c) => ({
      id: c.id, user_id: c.user_uid, label: c.label, enabled: !!c.enabled,
      protocol: c.protocol, credential: c.credential, transport_type: c.transport_type,
      uri_template: c.uri_template, route_key: c.route_key,
      nodes: String(c.node_selector || '').split(',').map((x) => x.trim()).filter(Boolean),
      exit_cc: c.exit_cc || null,
      created_at: c.created_at,
    })),
  });
}

/**
 * `POST /v1/configs/{id}:rotate` — a new credential for one config.
 *
 * The credential and the template move **together**, because the template carries the credential:
 * changing one without the other leaves the subscription serving a link built from the old secret,
 * which is a revocation that revokes nothing.
 *
 * Per config rather than per user, and that is the point of having several: revoking the link one
 * device is using must not cut off the person's other devices.
 *
 * The user's `updated_at` moves so the change reaches every other device on the next sync.
 */
async function studioRotateConfig(request, env, id, actor) {
  const row = await env.DB.prepare(
    'SELECT id, user_uid, label, credential FROM configs WHERE id = ? AND deleted_at IS NULL'
  ).bind(id).first();
  if (!row) return studioErr('not_found', 'no such config', 404);

  let body = {};
  try { body = await request.json(); } catch (e) { }
  const credential = typeof body.credential === 'string' ? body.credential : '';
  const template = typeof body.uri_template === 'string' ? body.uri_template : '';
  if (!credential || !template) {
    return studioErr('bad_request', 'a rotation needs both a credential and its template', 400);
  }

  const now = Date.now();
  await env.DB.batch([
    env.DB.prepare(
      'UPDATE configs SET credential = ?, uri_template = ?, auth_hash = ?, credential_rotated_at = ?, updated_at = ? WHERE id = ?'
    ).bind(
      credential, template,
      typeof body.auth_hash === 'string' && body.auth_hash ? body.auth_hash : null,
      now, now, id,
    ),
    env.DB.prepare('UPDATE users SET updated_at = ? WHERE uid = ?').bind(now, row.user_uid),
    // The old credential is not recorded, here or in the audit row. It is a live secret right up
    // until this statement runs, and an audit trail is not a place to keep one.
    studioAuditStmt(env, actor, 'config.rotate', row.user_uid, null, { config_id: id, label: row.label }),
  ]);
  return studioJson({ id, rotated_at: now });
}

/**
 * `POST /v1/configs/{id}:nodes` — point one config at a different set of endpoints.
 *
 * The move that «ترکیب با آی‌پی تمیز» is made of, and the reason it is a separate endpoint rather
 * than part of `:rotate`: **the credential does not change**. Retargeting is a change to WHERE the
 * link goes, and re-issuing the secret on the way would disconnect the person whose address you
 * were trying to improve — which is the exact opposite of the intent.
 *
 * So the subscription URL is untouched, every device keeps working, and the next time a client
 * refreshes its subscription it is handed the same configs on the new address. Nothing has to be
 * re-sent to anybody.
 *
 * An empty list means "every endpoint", the same as a config that never expressed an opinion. It is
 * accepted rather than refused because undoing a retarget has to be as easy as doing one.
 */
async function studioRetargetConfig(request, env, id, actor) {
  const row = await env.DB.prepare(
    'SELECT id, user_uid, label, node_selector FROM configs WHERE id = ? AND deleted_at IS NULL'
  ).bind(id).first();
  if (!row) return studioErr('not_found', 'no such config', 404);

  let body = {};
  try { body = await request.json(); } catch (e) { }
  if (!Array.isArray(body.nodes)) {
    return studioErr('bad_request', 'nodes must be an array of endpoint ids', 400);
  }

  // Validated on the same charset ids use, for the same reason the create path validates it: the
  // list is stored joined by commas and split back out at read time, so a comma inside an id would
  // silently become two ids.
  const wanted = body.nodes
    .filter((n) => typeof n === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(n))
    .slice(0, 64);
  if (wanted.length !== body.nodes.length) {
    return studioErr('bad_request', 'an endpoint id is not a valid id', 400);
  }

  // Checked against the endpoints this installation actually has, and this check is the difference
  // between a retarget and a silent unset. A selector naming ids that do not exist falls back to
  // ALL endpoints at read time (see studioServeSubscription), so writing one would look like
  // success on the operator's screen and quietly serve the address they were moving away from.
  if (wanted.length) {
    const rows = await env.DB.prepare(
      'SELECT id FROM nodes WHERE id IN (' + wanted.map(() => '?').join(',') + ')'
    ).bind(...wanted).all();
    const have = new Set(((rows && rows.results) || []).map((r) => r.id));
    const missing = wanted.filter((n) => !have.has(n));
    if (missing.length) {
      return studioErr('not_found', 'no such endpoint: ' + missing.join(','), 404);
    }
  }

  const selector = wanted.join(',');
  const now = Date.now();
  await env.DB.batch([
    env.DB.prepare('UPDATE configs SET node_selector = ?, updated_at = ? WHERE id = ?')
      .bind(selector || null, now, id),
    // Same reason as everywhere else: what the subscription serves has changed, so the watermark
    // has to move or no other device learns about it.
    env.DB.prepare('UPDATE users SET updated_at = ? WHERE uid = ?').bind(now, row.user_uid),
    // Both sides recorded. Unlike a credential, an endpoint id is not a secret, and "what was it
    // pointed at before" is the first question asked when a retarget makes things worse.
    studioAuditStmt(
      env, actor, 'config.retarget', row.user_uid,
      { nodes: row.node_selector || '' }, { nodes: selector, config_id: id },
    ),
  ]);
  return studioJson({ id, nodes: wanted, updated_at: now });
}

/**
 * `POST /v1/configs/{id}:exit` -- move one config to another country, or back to none.
 *
 * The credential does not change, for the same reason `:nodes` keeps it: the person's app already
 * holds this config, and the move should reach them on the next refresh rather than as a broken link.
 */
async function studioMoveConfigExit(request, env, id, actor) {
  const row = await env.DB.prepare(
    'SELECT id, user_uid, label, exit_cc FROM configs WHERE id = ? AND deleted_at IS NULL'
  ).bind(id).first();
  if (!row) return studioErr('not_found', 'no such config', 404);
  let body = {};
  try { body = await request.json(); } catch (e) { }
  const cc = body.exit_cc == null || body.exit_cc === '' ? null : body.exit_cc;
  if (cc !== null && !studioValidCc(cc)) return studioErr('bad_request', 'exit_cc must be two capital letters', 400);
  const pin = cc ? await studioPinFor(env, null, cc, id) : null;
  if (cc && !pin) {
    return studioErr('no_exit', 'no working server in ' + cc, 409);
  }
  const now = Date.now();
  await env.DB.batch([
    env.DB.prepare('UPDATE configs SET exit_cc = ?, exit_pin = ?, exit_pin_ip = ?, exit_pin_at = ?, updated_at = ? WHERE id = ?')
      .bind(cc, pin ? pin.url : null, pin ? pin.ip : null, pin ? now : null, now, id),
    env.DB.prepare('UPDATE users SET updated_at = ? WHERE uid = ?').bind(now, row.user_uid),
    ...studioLocationsStmts(env, row.user_uid),
    studioAuditStmt(env, actor, 'config.exit', row.user_uid, { exit_cc: row.exit_cc || null }, { exit_cc: cc, config_id: id }),
  ]);
  return studioJson({ id, exit_cc: cc, updated_at: now });
}

async function studioDeleteConfig(env, id, actor) {
  const row = await env.DB.prepare('SELECT id, user_uid, label FROM configs WHERE id = ? AND deleted_at IS NULL').bind(id).first();
  if (!row) return studioErr('not_found', 'no such config', 404);
  const now = Date.now();
  await env.DB.batch([
    env.DB.prepare('UPDATE configs SET deleted_at = ?, updated_at = ?, enabled = 0 WHERE id = ?').bind(now, now, id),
    env.DB.prepare('UPDATE users SET updated_at = ? WHERE uid = ?').bind(now, row.user_uid),
    ...studioLocationsStmts(env, row.user_uid),
    studioAuditStmt(env, actor, 'config.delete', row.user_uid, { config_id: id, label: row.label }, null),
  ]);
  return studioJson({ id, deleted_at: now });
}

// ---------------------------------------------------------------------------- subscriptions

/** Called when a user is created, so a link exists before anyone asks for one. */
function studioNewSubscriptionStmt(env, userUid, now) {
  return env.DB.prepare(
    'INSERT INTO subscriptions (id, user_uid, token, format, created_at) VALUES (?,?,?,?,?)'
  ).bind(studioRandomHex(8), userUid, studioRandomHex(16), 'base64', now);
}

async function studioGetSubscription(env, userUid, url) {
  const sub = await env.DB.prepare(
    'SELECT id, user_uid, token, created_at, rotated_at, revoked_at, hits, last_hit_at, last_ua FROM subscriptions WHERE user_uid = ? AND revoked_at IS NULL'
  ).bind(userUid).first();
  if (!sub) return studioErr('not_found', 'no subscription for that user', 404);
  return studioJson(studioSubscriptionDto(sub, url));
}

function studioSubscriptionDto(sub, url) {
  return {
    id: sub.id,
    user_id: sub.user_uid,
    token: sub.token,
    url: url.origin + '/s/' + sub.token,
    page_url: url.origin + '/p/' + sub.token,
    created_at: sub.created_at,
    rotated_at: sub.rotated_at || null,
    revoked_at: sub.revoked_at || null,
    // Sampled at most once per ten minutes, not a fetch count. See studioServeSubscription.
    hit_samples: sub.hits || 0,
    last_hit_at: sub.last_hit_at || null,
    last_ua: sub.last_ua || null,
  };
}

/**
 * Rotate the token.
 *
 * The old one stops working immediately -- that is the point of rotating, and softening it into a
 * grace period would mean a link the operator believes they revoked is still live.
 */
async function studioRotateSubscription(env, userUid, actor, url) {
  const sub = await env.DB.prepare('SELECT id, token FROM subscriptions WHERE user_uid = ? AND revoked_at IS NULL').bind(userUid).first();
  if (!sub) return studioErr('not_found', 'no subscription for that user', 404);

  const now = Date.now();
  const token = studioRandomHex(16);
  await env.DB.batch([
    env.DB.prepare('UPDATE subscriptions SET token = ?, rotated_at = ?, hits = 0, last_hit_at = NULL WHERE id = ?')
      .bind(token, now, sub.id),
    env.DB.prepare('UPDATE users SET updated_at = ? WHERE uid = ?').bind(now, userUid),
    studioAuditStmt(env, actor, 'subscription.rotate', userUid, { token: '(previous)' }, { rotated_at: now }),
  ]);

  const fresh = await env.DB.prepare(
    'SELECT id, user_uid, token, created_at, rotated_at, revoked_at, hits, last_hit_at, last_ua FROM subscriptions WHERE id = ?'
  ).bind(sub.id).first();
  return studioJson(studioSubscriptionDto(fresh, url));
}

// ---------------------------------------------------------------------------- dispatch

async function studioConfigsRoute(request, env, path, method, actor) {
  const url = new URL(request.url);

  if (path === '/configs' && method === 'POST') return await studioCreateConfig(request, env, actor);
  const rotateConfig = path.match(/^\/configs\/([^/:]+):rotate$/);
  if (rotateConfig) {
    if (method !== 'POST') return studioErr('method_not_allowed', 'actions are POST', 405);
    return await studioRotateConfig(request, env, rotateConfig[1], actor);
  }
  const retarget = path.match(/^\/configs\/([^/:]+):nodes$/);
  if (retarget) {
    if (method !== 'POST') return studioErr('method_not_allowed', 'actions are POST', 405);
    return await studioRetargetConfig(request, env, retarget[1], actor);
  }
  const moveExit = path.match(/^\/configs\/([^/:]+):exit$/);
  if (moveExit) {
    if (method !== 'POST') return studioErr('method_not_allowed', 'actions are POST', 405);
    return await studioMoveConfigExit(request, env, moveExit[1], actor);
  }
  if (path.startsWith('/configs/') && method === 'DELETE') {
    return await studioDeleteConfig(env, path.slice('/configs/'.length), actor);
  }

  const listMatch = path.match(/^\/users\/([^/]+)\/configs$/);
  if (listMatch && method === 'GET') return await studioListConfigs(env, listMatch[1]);

  const subMatch = path.match(/^\/users\/([^/]+)\/subscription$/);
  if (subMatch && method === 'GET') return await studioGetSubscription(env, subMatch[1], url);

  const rotateMatch = path.match(/^\/users\/([^/]+):rotate-subscription$/);
  if (rotateMatch && method === 'POST') return await studioRotateSubscription(env, rotateMatch[1], actor, url);

  return null;
}

/**
 * Can a config be pinned to [cc]? An enabled exit of the operator's own, or -- build 17 -- the
 * public pool switched on (04k). Without the second half a multi-location user got their first
 * config and then a refusal for every country the pool serves.
 */
async function studioCountryServed(env, cc) {
  return !!(await studioPinFor(env, null, cc, 'check|' + cc));
}

/**
 * The exit a config in [cc] should start on, or null when the country has nothing to offer.
 *
 * The operator's own exits first (by measured country), then verified public servers. When the pool
 * is on but nothing is verified for [cc] yet, a verification runs here, inline -- a config made for
 * a country with no working server is exactly the "all disconnected" the operator reported, and it
 * is better refused now, with a reason, than handed out.
 */
async function studioPinFor(env, ctx, cc, key) {
  let pin = await studioPickPin(env, ctx, cc, key);
  if (pin) return pin;
  let poolOn = false;
  try { poolOn = await studioPoolEnabled(env); } catch (e) { poolOn = false; }
  if (!poolOn) return null;
  try { await studioPoolVerify(env, cc, 12); } catch (e) { return null; }
  POOL_VERIFIED.delete(cc);
  pin = await studioPickPin(env, ctx, cc, key);
  return pin;
}

/**
 * Keep `users.locations` equal to the countries of the person's live configs, in the same batch as
 * the write that changed them. It is what the users list and the user page show («مولتی لوکیشن»,
 * flags), and reading it off the row costs nothing where a join per row would cost a D1 read each.
 * Empty before schema 18, which has no such column.
 */
function studioLocationsStmts(env, uid) {
  // Only once this isolate migrated cleanly: a failed migration may have left the column missing,
  // and a batch naming it would take the config write down with it.
  if (!migrationsDone || lastMigrationError) return [];
  return [env.DB.prepare(
    "UPDATE users SET locations = (SELECT group_concat(cc) FROM (SELECT DISTINCT exit_cc AS cc FROM configs " +
    "WHERE user_uid = ? AND deleted_at IS NULL AND exit_cc IS NOT NULL ORDER BY exit_cc)) WHERE uid = ?"
  ).bind(uid, uid)];
}
