// ==========================================================
// Config Studio  —  /v1/nodes  («نقطهٔ اتصال»)
// ==========================================================
//
// A **node** is an address a subscription is served on: a clean IP, a port list, and the server name
// to present. An installation has many; a node belongs to exactly one. It is not a shard — that word
// is for a Cloudflare account, and the plan is careful to keep the two apart because either could
// loosely be called a "server" and blurring them makes every capacity conversation ambiguous
// (plan §A.1).
//
// Nodes exist because the alternative is what was there before: the endpoint list lived in
// `users.ips`, **per person**, newline-separated. Changing where traffic went meant editing every
// user, and a clean IP that stopped working had to be replaced one subscriber at a time.
//
// **Backwards compatible on purpose.** A user's own `ips` still wins when they have one, so an
// installation that has been running since before nodes existed serves exactly what it served
// yesterday, and a per-person override stays possible for the one subscriber who needs a different
// address. Nodes are the default, not a replacement.

const STUDIO_NODE_COLUMNS =
  'id, name, group_id, country, city, host, ports, sni, host_header, priority, enabled, health, ' +
  'health_checked_at, latency_ms, last_error';

function studioNodeDto(row) {
  if (!row) return null;
  return {
    id: row.id,
    name: row.name || '',
    country: row.country || null,
    // Free text, and separate from `country` because they answer different questions: the country
    // is what a subscriber recognises, the city is what an operator uses to tell two addresses in
    // the same country apart. Neither is used for routing — nothing here picks an endpoint by
    // where it is.
    city: row.city || null,
    host: row.host || '',
    // A list on the wire, a CSV in the column. The column shape is the legacy one and changing it
    // would be a migration for no gain; the API should still speak in the shape a caller wants.
    ports: String(row.ports || '443').split(',').map((p) => p.trim()).filter(Boolean),
    sni: row.sni || null,
    host_header: row.host_header || null,
    priority: row.priority == null ? 100 : row.priority,
    enabled: !!row.enabled,
    health: row.health || 'unknown',
    health_checked_at: row.health_checked_at || null,
    // What the last probe measured, and why it failed if it did. Null rather than zero when nothing
    // has probed yet: a latency of zero and a node nobody has tested look identical otherwise, and
    // only one of them is worth acting on.
    latency_ms: row.latency_ms == null ? null : row.latency_ms,
    last_error: row.last_error || null,
    group_id: row.group_id || null,
  };
}

function studioNodeFields(body) {
  const host = typeof body.host === 'string' ? body.host.trim() : '';
  const ports = Array.isArray(body.ports)
    ? body.ports.map((p) => parseInt(p, 10)).filter((p) => p > 0 && p < 65536)
    : [];
  return {
    name: typeof body.name === 'string' ? body.name.slice(0, 80) : '',
    country: typeof body.country === 'string' && body.country ? body.country.slice(0, 8) : null,
    city: typeof body.city === 'string' && body.city ? body.city.slice(0, 60) : null,
    // Validated on the same charset every other caller-chosen id uses, because it is written into
    // a column that is joined against `node_groups.id`.
    group_id: typeof body.group_id === 'string' && studioValidPlanId(body.group_id) ? body.group_id : null,
    host,
    ports: (ports.length ? ports : [443]).join(','),
    sni: typeof body.sni === 'string' && body.sni ? body.sni.slice(0, 253) : null,
    host_header: typeof body.host_header === 'string' && body.host_header ? body.host_header.slice(0, 253) : null,
    priority: Number.isFinite(body.priority) ? Math.trunc(body.priority) : 100,
    enabled: body.enabled === false ? 0 : 1,
  };
}

async function studioListNodes(env) {
  // No paging: an installation has a handful of endpoints, and this list is read on every
  // subscription fetch. A hard LIMIT is still there so a database somebody has done something
  // unusual to cannot become an unbounded read.
  const res = await env.DB.prepare(
    `SELECT ${STUDIO_NODE_COLUMNS} FROM nodes ORDER BY priority ASC, name ASC LIMIT 200`
  ).all();
  return studioJson({ items: ((res && res.results) || []).map(studioNodeDto) });
}

/**
 * `PUT /v1/nodes/{id}` — create or replace, under an id the caller chose.
 *
 * Idempotent for the same reason plans are: the app writes the same node to several installations
 * when an operator wants one clean IP used everywhere, and a retry after a partial failure must not
 * produce a second copy.
 */
async function studioPutNode(request, env, id, actor) {
  if (!studioValidPlanId(id)) return studioErr('bad_request', 'invalid node id', 400);

  let body = {};
  try { body = await request.json(); } catch (e) { }
  const f = studioNodeFields(body);
  if (!f.host) return studioErr('bad_request', 'a node needs a host', 400);
  if (!f.name) f.name = f.host;

  const before = await env.DB.prepare(`SELECT ${STUDIO_NODE_COLUMNS} FROM nodes WHERE id = ?`).bind(id).first();
  const now = Date.now();

  await env.DB.batch([
    env.DB.prepare(
      `INSERT INTO nodes (id, name, group_id, country, city, host, ports, sni, host_header,
         capabilities_json, priority, enabled, health, health_checked_at, metadata_json)
       VALUES (?,?,?,?,?,?,?,?,?,NULL,?,?,'unknown',NULL,NULL)
       ON CONFLICT(id) DO UPDATE SET
         name = excluded.name, group_id = excluded.group_id, country = excluded.country,
         city = excluded.city, host = excluded.host,
         ports = excluded.ports, sni = excluded.sni, host_header = excluded.host_header,
         priority = excluded.priority, enabled = excluded.enabled`
    ).bind(
      id, f.name, f.group_id, f.country, f.city, f.host, f.ports, f.sni, f.host_header,
      f.priority, f.enabled,
    ),
    studioAuditStmt(
      env, actor, before ? 'node.update' : 'node.create', id,
      before ? studioNodeDto(before) : null, { id, host: f.host, ports: f.ports }, 'node',
    ),
  ]);

  // The fan-out reads this table through a cache. Without this, an endpoint that was just added,
  // moved to another group or switched off keeps its old behaviour for up to a minute — which the
  // operator reads as the write not having landed.
  studioInvalidateNodeCache();

  const after = await env.DB.prepare(`SELECT ${STUDIO_NODE_COLUMNS} FROM nodes WHERE id = ?`).bind(id).first();
  return studioJson(studioNodeDto(after), before ? 200 : 201);
}

/**
 * `POST /v1/nodes:replace` — the whole endpoint list at once.
 *
 * This is what a scan hands over. Doing it as one call rather than a delete followed by N creates
 * matters: the subscription reads this table, so a window where it is empty is a window where every
 * link resolves to nothing. One batch, so there is no such window.
 *
 * The nodes that are **not** in the payload are disabled rather than deleted, so an address that is
 * dropped today and works again next week comes back as the same row rather than as a new one.
 */
async function studioReplaceNodes(request, env, actor) {
  let body = {};
  try { body = await request.json(); } catch (e) { }
  const items = Array.isArray(body.items) ? body.items.slice(0, 100) : null;
  if (!items) return studioErr('bad_request', 'items is required', 400);

  const now = Date.now();
  const stmts = [env.DB.prepare('UPDATE nodes SET enabled = 0')];
  const kept = [];

  for (const raw of items) {
    const f = studioNodeFields(raw || {});
    if (!f.host) continue;
    if (!f.name) f.name = f.host;
    const id = studioValidPlanId(raw.id) ? raw.id : studioRandomHex(8);
    kept.push(id);
    stmts.push(env.DB.prepare(
      `INSERT INTO nodes (id, name, group_id, country, city, host, ports, sni, host_header,
         capabilities_json, priority, enabled, health, health_checked_at, metadata_json)
       VALUES (?,?,?,?,?,?,?,?,?,NULL,?,1,'unknown',NULL,NULL)
       ON CONFLICT(id) DO UPDATE SET
         name = excluded.name, country = excluded.country, city = excluded.city,
         host = excluded.host,
         ports = excluded.ports, sni = excluded.sni, host_header = excluded.host_header,
         priority = excluded.priority, enabled = 1`
    ).bind(
      id, f.name, f.group_id, f.country, f.city, f.host, f.ports, f.sni, f.host_header, f.priority,
    ));
  }

  // Refused rather than applied. An empty list would disable every node and leave every
  // subscription resolving to an empty document -- which is the single worst thing this endpoint
  // could do, and it is one mistyped payload away.
  if (!kept.length) return studioErr('bad_request', 'a replacement needs at least one node', 400);

  stmts.push(studioAuditStmt(env, actor, 'node.replace', 'nodes', null, { count: kept.length }, 'node'));
  await env.DB.batch(stmts);
  studioInvalidateNodeCache();
  return await studioListNodes(env);
}

/**
 * `POST /v1/nodes/{id}:health` — record what a probe found.
 *
 * **The probe runs on the phone.** A worker that timed its own request to a node would be measuring
 * Cloudflare's network to that address, which is not the number the operator needs: what matters is
 * whether the address is reachable and fast *from Iran*, and the only machine in this system sitting
 * there is the app. So the app measures and posts the result, and this writes it down.
 *
 * Not audited. A health report is a measurement, not a decision somebody made, and writing one row
 * of audit per probe per node would spend the audit log — and the write budget — on telemetry.
 */
async function studioReportNodeHealth(request, env, id) {
  let body = {};
  try { body = await request.json(); } catch (e) { }

  const row = await env.DB.prepare('SELECT id FROM nodes WHERE id = ?').bind(id).first();
  if (!row) return studioErr('not_found', 'no such node', 404);

  const ok = body.ok === true;
  // Clamped rather than trusted. A client clock skew or a bad parse should not put a six-digit
  // millisecond figure on a screen that reads it as a latency.
  const latency = ok && Number.isFinite(body.latency_ms)
    ? Math.max(0, Math.min(60000, Math.trunc(body.latency_ms)))
    : null;
  const error = !ok && typeof body.error === 'string' ? body.error.slice(0, 200) : null;
  const now = Date.now();

  await env.DB.batch([
    env.DB.prepare(
      'UPDATE nodes SET health = ?, health_checked_at = ?, latency_ms = ?, last_error = ? WHERE id = ?'
    ).bind(ok ? 'up' : 'down', now, latency, error, id),
    // The history behind «تاریخچهٔ بررسی» and the uptime figure. One row per probe, and a probe
    // is an operator action over a handful of endpoints — nothing like the volume that made the
    // activity log need a once-per-hour cap.
    env.DB.prepare(
      'INSERT INTO node_health (node_id, ts, up, latency_ms, error) VALUES (?,?,?,?,?)'
    ).bind(id, now, ok ? 1 : 0, latency, error),
    // Pruned from inside the writer, because a Worker has no scheduler. Fifty samples is enough to
    // read a pattern off and small enough that the table never becomes something to think about.
    env.DB.prepare(
      'DELETE FROM node_health WHERE node_id = ? AND id NOT IN ' +
      '(SELECT id FROM node_health WHERE node_id = ? ORDER BY id DESC LIMIT 50)'
    ).bind(id, id),
  ]);

  // Health decides the order under the `healthy` strategy, so a fresh result has to reach the
  // fan-out rather than waiting out the cache it would otherwise sit behind.
  studioInvalidateNodeCache();

  const after = await env.DB.prepare(`SELECT ${STUDIO_NODE_COLUMNS} FROM nodes WHERE id = ?`).bind(id).first();
  return studioJson(studioNodeDto(after));
}

/**
 * `GET /v1/nodes/{id}/health` — what the last fifty probes found, and the uptime they imply.
 *
 * **Uptime here is the fraction of STORED SAMPLES that were up**, not a fraction of wall-clock
 * time, and the difference matters enough that the screen says it too: nobody probes on a
 * schedule the engine controls, so five samples across a week and fifty across an hour both
 * produce a percentage and only one of them means much. The sample count is returned beside it
 * for exactly that reason.
 */
async function studioNodeHealthHistory(env, id) {
  const node = await env.DB.prepare('SELECT id FROM nodes WHERE id = ?').bind(id).first();
  if (!node) return studioErr('not_found', 'no such node', 404);

  const res = await env.DB.prepare(
    'SELECT ts, up, latency_ms, error FROM node_health WHERE node_id = ? ORDER BY id DESC LIMIT 50'
  ).bind(id).all();
  const rows = (res && res.results) || [];

  const up = rows.filter((r) => r.up).length;
  const measured = rows.filter((r) => r.up && r.latency_ms != null).map((r) => r.latency_ms);
  return studioJson({
    node_id: id,
    samples: rows.length,
    up_samples: up,
    // Null rather than zero on a node nobody has probed. Zero per cent and "never measured" are
    // not the same claim, and only one of them should send the operator to replace an address.
    uptime: rows.length ? up / rows.length : null,
    avg_latency_ms: measured.length
      ? Math.round(measured.reduce((a, b) => a + b, 0) / measured.length)
      : null,
    items: rows.map((r) => ({
      ts: r.ts, up: !!r.up, latency_ms: r.latency_ms == null ? null : r.latency_ms,
      error: r.error || null,
    })),
  });
}

async function studioDeleteNode(env, id, actor) {
  const before = await env.DB.prepare(`SELECT ${STUDIO_NODE_COLUMNS} FROM nodes WHERE id = ?`).bind(id).first();
  if (!before) return studioErr('not_found', 'no such node', 404);
  await env.DB.batch([
    env.DB.prepare('DELETE FROM nodes WHERE id = ?').bind(id),
    env.DB.prepare('DELETE FROM node_health WHERE node_id = ?').bind(id),
    studioAuditStmt(env, actor, 'node.delete', id, studioNodeDto(before), null, 'node'),
  ]);
  studioInvalidateNodeCache();
  return studioJson({ id, deleted: true });
}

/**
 * The endpoints a subscription is served on, in priority order.
 *
 * Isolate-cached for a minute. This runs on **every** subscription fetch, and a client polling every
 * twelve hours across a few hundred subscribers is still a read per fetch for a list that changes
 * about once a week. A minute is short enough that a scan's new addresses reach people almost
 * immediately and long enough that a burst of fetches costs one read.
 */
let cachedStudioNodes = null;
let cachedStudioNodesAt = 0;

/**
 * Drop the cached endpoint list.
 *
 * Called by every write that can change what the fan-out serves or the order it serves it in — an
 * endpoint added, moved between groups or switched off, a group's strategy changed, a probe result
 * recorded. Without it the operator makes a change, reloads a subscription to check, sees the old
 * arrangement for up to a minute, and concludes the write did not land.
 *
 * Isolate-local, like the cache itself. Cloudflare runs many isolates and this clears one of them,
 * which is exactly as much as the cache is worth: the others expire on their own within the minute.
 */
function studioInvalidateNodeCache() {
  cachedStudioNodes = null;
  cachedStudioNodesAt = 0;
}

/**
 * Where the fan-out orders endpoints, and the one place any of these strategies mean anything.
 *
 * The client is what actually picks and what actually falls back — a subscription is a list of
 * links and nothing here proxies anything. So every strategy is a rule for the ORDER of that list,
 * and the client's own "try them in turn" behaviour is what turns an order into an outcome.
 *
 * A node with no group is ordered as though it were in a group of strategy `order` at the default
 * priority, which is precisely what every installation did before groups existed.
 *
 * **Nothing is dropped for being unhealthy.** A DOWN endpoint sinks to the bottom of its group. The
 * probe behind that verdict ran on one phone, on one network, at one moment, and the cost of being
 * wrong is not symmetric: a node wrongly kept is a dead entry in a server list, while a node
 * wrongly dropped is somebody's only working link vanishing with nothing to explain it.
 */
function studioOrderNodes(rows) {
  const rank = { up: 0, unknown: 1, down: 2 };
  const withKeys = rows.map((r, i) => {
    const strategy = STUDIO_NODE_STRATEGIES.indexOf(r.strategy) >= 0 ? r.strategy : 'order';
    let within;
    if (strategy === 'healthy') {
      // Unknown sits between up and down rather than with either: an endpoint nobody has probed is
      // not known good and is not known broken, and burying it with the failures would hide a new
      // address until somebody happened to probe it.
      within = (rank[r.health] == null ? 1 : rank[r.health]) * 1000000;
    } else if (strategy === 'fastest') {
      // Never measured sorts last, not first. SQLite would put a NULL latency at the top, which is
      // the ordering that opens the list with the endpoints nothing is known about.
      within = r.latency_ms == null ? 9999999 : r.latency_ms;
    } else {
      within = 0;
    }
    return {
      row: r,
      groupPriority: r.group_priority == null ? 100 : r.group_priority,
      within,
      priority: r.priority == null ? 100 : r.priority,
      seq: i,
    };
  });

  withKeys.sort((a, b) =>
    (a.groupPriority - b.groupPriority) ||
    (a.within - b.within) ||
    (a.priority - b.priority) ||
    (a.seq - b.seq)
  );
  return withKeys.map((k) => k.row);
}

async function studioActiveNodes(env) {
  const now = Date.now();
  if (cachedStudioNodes && (now - cachedStudioNodesAt) < 60000) return cachedStudioNodes;
  try {
    const res = await env.DB.prepare(
      // `id` is here for the per-config endpoint selector (build 9); the group columns are the
      // ordering strategy (build 10). A LEFT JOIN, because a node with no group is the ordinary
      // case and an INNER one would silently serve nothing on an installation that has never made
      // a group — which is every installation until the operator makes one.
      'SELECT n.id, n.host, n.ports, n.sni, n.priority, n.health, n.latency_ms, ' +
      'n.group_id, g.strategy AS strategy, g.priority AS group_priority ' +
      'FROM nodes n LEFT JOIN node_groups g ON g.id = n.group_id ' +
      // A disabled GROUP takes its endpoints out of the fan-out without touching the nodes, which
      // is what makes "switch this whole region off for an hour" one action and one undo.
      'WHERE n.enabled = 1 AND (n.group_id IS NULL OR g.id IS NULL OR g.enabled = 1) ' +
      'ORDER BY n.priority ASC LIMIT 50'
    ).all();
    cachedStudioNodes = studioOrderNodes((res && res.results) || []);
    cachedStudioNodesAt = now;
  } catch (e) {
    // Including the case where `node_groups` does not exist yet — an installation mid-migration
    // serves its endpoints unordered rather than serving none.
    try {
      const plain = await env.DB.prepare(
        'SELECT id, host, ports, sni FROM nodes WHERE enabled = 1 ORDER BY priority ASC LIMIT 50'
      ).all();
      cachedStudioNodes = (plain && plain.results) || [];
    } catch (e2) {
      cachedStudioNodes = [];
    }
    cachedStudioNodesAt = now;
  }
  return cachedStudioNodes;
}

/**
 * The same list, rotated so that not every subscriber starts at the same endpoint.
 *
 * The honest form of load balancing available here. Nothing is being balanced *across* — every
 * endpoint is a different clean IP reaching the SAME worker, so there is no per-node load to move.
 * What this does fix is real: with one fixed order, every client in the fleet tries endpoint one
 * first, and an endpoint that gets blocked takes everybody with it at the same moment.
 *
 * Rotated by a hash of the subscription token, so it is **stable per person**: somebody who reloads
 * their subscription gets the same order they had, which is what keeps a client's own saved
 * preference pointing at the same server. Rotating randomly per fetch would reshuffle their list
 * every twelve hours for no gain.
 *
 * Applied here rather than inside the cache because the cache is shared by every subscriber.
 */
function studioRotateForToken(nodes, token) {
  if (!nodes.length) return nodes;
  // Only the leading run of `spread` endpoints rotates. A group that asked for a fixed order must
  // keep it, and the leading run is the part a client actually reaches first.
  const lead = [];
  let i = 0;
  while (i < nodes.length && nodes[i].strategy === 'spread') { lead.push(nodes[i]); i++; }
  if (lead.length < 2) return nodes;

  let h = 0;
  for (let k = 0; k < token.length; k++) h = ((h * 31) + token.charCodeAt(k)) >>> 0;
  const at = h % lead.length;
  return lead.slice(at).concat(lead.slice(0, at), nodes.slice(i));
}

async function studioNodesRoute(request, env, path, method, actor) {
  if (path === '/nodes') {
    if (method === 'GET') return await studioListNodes(env);
    return studioErr('method_not_allowed', method + ' is not allowed here', 405);
  }
  if (path === '/nodes:replace') {
    if (method !== 'POST') return studioErr('method_not_allowed', 'actions are POST', 405);
    return await studioReplaceNodes(request, env, actor);
  }
  if (!path.startsWith('/nodes/')) return null;
  const tail = path.slice('/nodes/'.length);
  if (!tail) return null;

  // `/nodes/{id}/health` is the only sub-resource; anything else with a slash is not ours. The
  // same shape `/plans/{id}/users` uses, so the dispatcher stays a list rather than a tree.
  if (tail.includes('/')) {
    const parts = tail.split('/');
    if (parts.length === 2 && parts[1] === 'health' && method === 'GET') {
      return await studioNodeHealthHistory(env, parts[0]);
    }
    return null;
  }

  // "{id}:health" -- the verb rides on the path, as it does on users and configs, so a POST body
  // stays the measurement rather than doubling as a command.
  const colon = tail.indexOf(':');
  const id = colon === -1 ? tail : tail.slice(0, colon);
  const action = colon === -1 ? null : tail.slice(colon + 1);
  if (!id) return studioErr('bad_request', 'node id is required', 400);

  if (action === 'health') {
    if (method !== 'POST') return studioErr('method_not_allowed', 'actions are POST', 405);
    return await studioReportNodeHealth(request, env, id);
  }
  if (action) return studioErr('not_found', 'unknown node action', 404);

  if (method === 'PUT') return await studioPutNode(request, env, id, actor);
  if (method === 'DELETE') return await studioDeleteNode(env, id, actor);
  return studioErr('method_not_allowed', method + ' is not allowed here', 405);
}
