// ==========================================================
// Config Studio  —  /v1/node-groups   («گروه نقطه»)
// ==========================================================
//
// A group is a set of endpoints and **one rule for the order they are handed out in**. That is the
// whole feature, and the narrowness is deliberate — it is easy to read this as "load balancing" and
// it is not, because of what a node actually is here.
//
// ### What the engine can and cannot do
//
// A node is a clean IP that reaches THIS worker. Every node in an installation is a different door
// into the same room. So there is no per-node load to balance and no traffic to steer: a
// subscription is a **list of links**, and the client on the far side is what picks one and what
// falls back when it cannot connect.
//
// What the engine controls, then, is exactly one thing: **which links appear, and in what order**.
// Every strategy below is a rule for that ordering, and every one of them is something the client's
// own "try them in order" behaviour turns into a real outcome:
//
//  * `order`    — the operator's own priority. The default, and what every installation does today.
//  * `healthy`  — endpoints the last probe found DOWN sink to the bottom, so a client reaches them
//                 only after everything believed working has failed.
//  * `fastest`  — ordered by the latency the operator's phone measured. Said plainly on the screen,
//                 because that is a measurement from ONE device on ONE network, not from each
//                 subscriber.
//  * `spread`   — the first endpoint rotates per subscriber. The honest form of load balancing
//                 available here: nothing is being balanced across, but it does stop every client
//                 in the fleet piling onto whichever endpoint sorts first.
//
// ### Nothing is ever dropped for being unhealthy
//
// A DOWN endpoint sinks; it is never removed. The probe ran on one phone, on one network, at one
// moment — and the cost of being wrong is asymmetric: a node wrongly kept is one dead entry in a
// client's server list, while a node wrongly dropped is somebody's only working link disappearing
// with nothing on their screen to explain it.

/** The columns the API returns. Never `SELECT *`. */
const STUDIO_NODE_GROUP_COLUMNS =
  'id, name, strategy, priority, enabled, created_at, updated_at';

/** The orderings a group can ask for. Anything else stored is read as `order`. */
const STUDIO_NODE_STRATEGIES = ['order', 'healthy', 'fastest', 'spread'];

function studioNodeGroupDto(row) {
  if (!row) return null;
  return {
    id: row.id,
    name: row.name || '',
    strategy: STUDIO_NODE_STRATEGIES.indexOf(row.strategy) >= 0 ? row.strategy : 'order',
    // Lower goes first, same meaning as a node's own priority and the order groups fan out in.
    priority: row.priority == null ? 100 : row.priority,
    enabled: !!row.enabled,
    created_at: row.created_at || null,
    updated_at: row.updated_at || null,
  };
}

function studioNodeGroupFields(body) {
  return {
    name: typeof body.name === 'string' ? body.name.slice(0, 80) : '',
    strategy: STUDIO_NODE_STRATEGIES.indexOf(body.strategy) >= 0 ? body.strategy : 'order',
    priority: Number.isFinite(body.priority) ? Math.trunc(body.priority) : 100,
    // Disabling a group takes every endpoint in it out of the fan-out without touching the nodes
    // themselves, which is what makes "switch this whole region off for an hour" one action rather
    // than six edits that have to be remembered and undone.
    enabled: body.enabled === false ? 0 : 1,
  };
}

async function studioListNodeGroups(env) {
  const res = await env.DB.prepare(
    `SELECT ${STUDIO_NODE_GROUP_COLUMNS} FROM node_groups ORDER BY priority ASC, name ASC LIMIT 100`
  ).all();
  return studioJson({ items: ((res && res.results) || []).map(studioNodeGroupDto) });
}

/**
 * `PUT /v1/node-groups/{id}` — create or replace, under an id the caller chose.
 *
 * Idempotent for the reason every other fleet-written row is: the app writes the same group to
 * several installations when an operator wants the same arrangement everywhere, and a retry after a
 * partial failure must not produce a second copy.
 */
async function studioPutNodeGroup(request, env, id, actor) {
  if (!studioValidPlanId(id)) return studioErr('bad_request', 'invalid group id', 400);

  let body = {};
  try { body = await request.json(); } catch (e) { }
  const f = studioNodeGroupFields(body);
  if (!f.name) return studioErr('bad_request', 'a group needs a name', 400);

  const before = await env.DB.prepare(
    `SELECT ${STUDIO_NODE_GROUP_COLUMNS} FROM node_groups WHERE id = ?`
  ).bind(id).first();
  const now = Date.now();

  await env.DB.batch([
    env.DB.prepare(
      `INSERT INTO node_groups (id, name, strategy, priority, enabled, created_at, updated_at)
       VALUES (?,?,?,?,?,?,?)
       ON CONFLICT(id) DO UPDATE SET
         name = excluded.name, strategy = excluded.strategy, priority = excluded.priority,
         enabled = excluded.enabled, updated_at = excluded.updated_at`
    ).bind(id, f.name, f.strategy, f.priority, f.enabled, (before && before.created_at) || now, now),
    studioAuditStmt(
      env, actor, before ? 'nodegroup.update' : 'nodegroup.create', id,
      before ? studioNodeGroupDto(before) : null,
      { id, name: f.name, strategy: f.strategy, enabled: !!f.enabled }, 'node',
    ),
  ]);

  // The fan-out order is derived from this table, so a stale cache would keep serving the old
  // arrangement for up to a minute after the operator changed it — long enough to look broken.
  studioInvalidateNodeCache();

  const after = await env.DB.prepare(
    `SELECT ${STUDIO_NODE_GROUP_COLUMNS} FROM node_groups WHERE id = ?`
  ).bind(id).first();
  return studioJson(studioNodeGroupDto(after), before ? 200 : 201);
}

/**
 * `DELETE /v1/node-groups/{id}` — the group goes, the endpoints stay.
 *
 * `nodes.group_id` is cleared in the same batch. Deleting the endpoints with the group would be
 * the destructive reading of a word that means "ungroup these" everywhere else, and it is not
 * recoverable — the addresses themselves are what took work to find.
 */
async function studioDeleteNodeGroup(env, id, actor) {
  const before = await env.DB.prepare(
    `SELECT ${STUDIO_NODE_GROUP_COLUMNS} FROM node_groups WHERE id = ?`
  ).bind(id).first();
  if (!before) return studioErr('not_found', 'no such group', 404);

  await env.DB.batch([
    env.DB.prepare('UPDATE nodes SET group_id = NULL WHERE group_id = ?').bind(id),
    env.DB.prepare('DELETE FROM node_groups WHERE id = ?').bind(id),
    studioAuditStmt(env, actor, 'nodegroup.delete', id, studioNodeGroupDto(before), null, 'node'),
  ]);
  studioInvalidateNodeCache();
  return studioJson({ ok: true, id, deleted: true });
}

/** Returns null when the path is not its business, so `studioHandle` stays a list. */
async function studioNodeGroupsRoute(request, env, path, method, actor) {
  if (path === '/node-groups') {
    if (method === 'GET') return await studioListNodeGroups(env);
    if (method === 'POST') {
      let body = {};
      try { body = await request.clone().json(); } catch (e) { }
      const id = studioValidPlanId(body.id) ? body.id : studioRandomHex(8);
      return await studioPutNodeGroup(request, env, id, actor);
    }
    return studioErr('method_not_allowed', method + ' is not allowed here', 405);
  }

  if (!path.startsWith('/node-groups/')) return null;
  const id = path.slice('/node-groups/'.length);
  if (!id || id.includes('/') || id.includes(':')) return null;

  if (method === 'PUT') return await studioPutNodeGroup(request, env, id, actor);
  if (method === 'DELETE') return await studioDeleteNodeGroup(env, id, actor);
  return studioErr('method_not_allowed', method + ' is not allowed here', 405);
}
