// ==========================================================
// Config Studio  —  /v1/plans   («بسته»)
// ==========================================================
//
// A «بسته» is one set of terms handed to many people: so much volume, so many days, and whatever
// caps go with it. Without it the terms get retyped for every user and can never be changed as a
// set, which is the single most repeated action in this product.
//
// Three properties are decided here rather than in a screen, because they are irreversible in the
// data (plan §D):
//
//  * **No money, in any column, in any phase.** No `price`, no `currency`, no `paid_at`. This is
//    D6, and it is a schema rule rather than a UI rule -- a column named `price` eventually
//    surfaces in a screen.
//  * **Editing a plan does not change the people who already hold one.** A user's terms are
//    *copied* from the plan when they are created, never referenced. Silently rewriting the terms
//    of someone already holding a link is the worst thing this code could do. Re-applying is a
//    separate, named, audited action: `:apply`.
//  * **Plans are per installation, synced by the app.** They are small and change rarely, so the
//    app writes the same plan, under the same id, to every installation in the fleet. That is why
//    the write path is an idempotent `PUT /v1/plans/{id}` with a client-chosen id: syncing to N
//    accounts means N writes that must be safe to retry, and a server-generated id would make the
//    same plan a different row on every account (R14).

/** The columns the API returns. Never `SELECT *`. */
const STUDIO_PLAN_COLUMNS =
  'id, name, quota_bytes, duration_days, expiry_mode, daily_quota_bytes, reset_policy, ' +
  'device_limit, conn_limit, ip_limit, template_id, is_default, archived, created_at, updated_at';

function studioPlanDto(row) {
  if (!row) return null;
  return {
    id: row.id,
    name: row.name || '',
    // BYTES, never GB floats -- the same rule as `users`. A plan shown as 29.7 GB to someone who
    // was told 30 costs more to explain than the column costs to carry.
    quota_bytes: row.quota_bytes || null,
    duration_days: row.duration_days || null,
    policy: {
      expiry_mode: row.expiry_mode || 'absolute',
      daily_quota_bytes: row.daily_quota_bytes || null,
      reset: row.reset_policy || 'none',
      device_limit: row.device_limit || null,
      conn_limit: row.conn_limit || null,
      ip_limit: row.ip_limit || null,
    },
    template_id: row.template_id || null,
    is_default: !!row.is_default,
    archived: !!row.archived,
    created_at: row.created_at || null,
    updated_at: row.updated_at || null,
  };
}

/**
 * Plan ids are chosen by the app, so they are validated rather than trusted.
 *
 * They travel in a URL path and land in `users.plan_id`, so the character set is deliberately
 * narrow: anything outside it is rejected instead of escaped.
 */
function studioValidPlanId(id) {
  return typeof id === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(id);
}

/** Reads a plan's fields off a request body, with every numeric field clamped to a sane shape. */
function studioPlanFields(body) {
  const int = (v) => (Number.isFinite(v) && v > 0 ? Math.trunc(v) : null);
  return {
    name: typeof body.name === 'string' ? body.name.slice(0, 120) : '',
    quota_bytes: int(body.quota_bytes),
    duration_days: int(body.duration_days),
    expiry_mode: body.expiry_mode === 'on_first_connect' ? 'on_first_connect' : 'absolute',
    daily_quota_bytes: int(body.daily_quota_bytes),
    reset_policy: ['daily', 'monthly', 'none'].indexOf(body.reset_policy) >= 0 ? body.reset_policy : 'none',
    device_limit: int(body.device_limit),
    conn_limit: int(body.conn_limit),
    ip_limit: int(body.ip_limit),
    template_id: typeof body.template_id === 'string' && body.template_id ? body.template_id : null,
    is_default: body.is_default ? 1 : 0,
  };
}

// ---------------------------------------------------------------------------- read

/**
 * `GET /v1/plans`
 *
 * No paging, and that is a decision rather than an omission: a fleet operator has tens of plans,
 * not thousands, and the table is read on every screen that creates or renews a user. Paging it
 * would cost a round trip to save nothing. A hard `LIMIT 500` is still there so that a database
 * someone has done something unusual to cannot become an unbounded read.
 */
async function studioListPlans(env, url) {
  const includeArchived = url.searchParams.get('archived') === '1';
  const where = includeArchived ? '' : 'WHERE archived = 0';
  const res = await env.DB.prepare(
    `SELECT ${STUDIO_PLAN_COLUMNS} FROM plans ${where} ORDER BY is_default DESC, name ASC LIMIT 500`
  ).all();
  return studioJson({ items: (res.results || []).map(studioPlanDto) });
}

async function studioGetPlan(env, id) {
  const row = await env.DB.prepare(`SELECT ${STUDIO_PLAN_COLUMNS} FROM plans WHERE id = ?`).bind(id).first();
  if (!row) return studioErr('not_found', 'no such plan', 404);
  return studioJson(studioPlanDto(row));
}

/**
 * `GET /v1/plans/{id}/users` — how many people are on this plan.
 *
 * A `COUNT(*)` in a request path is banned by §4.2 rule 3, and this one is the exception the rule
 * allows: it is filtered on `plan_id`, which migration v9 indexed, so it reads the matching rows
 * and not the table. Without that index this endpoint would be the outage.
 */
async function studioCountPlanUsers(env, id) {
  const row = await env.DB.prepare(
    'SELECT COUNT(*) AS n FROM users WHERE plan_id = ? AND deleted_at IS NULL'
  ).bind(id).first();
  return studioJson({ plan_id: id, users: (row && row.n) || 0 });
}

// ---------------------------------------------------------------------------- write

/**
 * `PUT /v1/plans/{id}` — create or replace. The fleet sync path.
 *
 * Idempotent on purpose. Writing one plan across an uncapped fleet means one request per account,
 * any of which can fail and be retried, and a retry must not produce a second plan or a conflict.
 */
async function studioPutPlan(request, env, id, actor) {
  if (!studioValidPlanId(id)) return studioErr('bad_request', 'invalid plan id', 400);

  let body = {};
  try { body = await request.json(); } catch (e) { }
  const f = studioPlanFields(body);
  if (!f.name) return studioErr('bad_request', 'a plan needs a name', 400);

  const before = await env.DB.prepare(`SELECT ${STUDIO_PLAN_COLUMNS} FROM plans WHERE id = ?`).bind(id).first();
  const now = Date.now();

  const stmts = [];
  // `ux_plans_default` is a UNIQUE index, so the old default has to go in the same batch as the
  // new one or the batch fails. Doing it in the same transaction is also what makes two devices
  // syncing at once resolve to one default rather than to an error.
  if (f.is_default) {
    stmts.push(env.DB.prepare('UPDATE plans SET is_default = 0, updated_at = ? WHERE is_default = 1 AND id <> ?').bind(now, id));
  }
  stmts.push(env.DB.prepare(
    `INSERT INTO plans (id, name, quota_bytes, duration_days, expiry_mode, daily_quota_bytes,
       reset_policy, device_limit, conn_limit, ip_limit, template_id, is_default, archived,
       created_at, updated_at)
     VALUES (?,?,?,?,?,?,?,?,?,?,?,?,0,?,?)
     ON CONFLICT(id) DO UPDATE SET
       name = excluded.name, quota_bytes = excluded.quota_bytes,
       duration_days = excluded.duration_days, expiry_mode = excluded.expiry_mode,
       daily_quota_bytes = excluded.daily_quota_bytes, reset_policy = excluded.reset_policy,
       device_limit = excluded.device_limit, conn_limit = excluded.conn_limit,
       ip_limit = excluded.ip_limit, template_id = excluded.template_id,
       is_default = excluded.is_default, updated_at = excluded.updated_at`
  ).bind(
    id, f.name, f.quota_bytes, f.duration_days, f.expiry_mode, f.daily_quota_bytes,
    f.reset_policy, f.device_limit, f.conn_limit, f.ip_limit, f.template_id, f.is_default,
    (before && before.created_at) || now, now,
  ));
  stmts.push(studioAuditStmt(
    env, actor, before ? 'plan.update' : 'plan.create', id,
    before ? studioPlanDto(before) : null, { id, name: f.name, quota_bytes: f.quota_bytes, duration_days: f.duration_days },
    'plan',
  ));

  await env.DB.batch(stmts);

  const after = await env.DB.prepare(`SELECT ${STUDIO_PLAN_COLUMNS} FROM plans WHERE id = ?`).bind(id).first();
  return studioJson(studioPlanDto(after), before ? 200 : 201);
}

/**
 * `DELETE /v1/plans/{id}` — archive, never erase.
 *
 * Users carry `plan_id` and the `renewals` table records which plan a renewal used, so a hard
 * delete would leave both pointing at nothing and would silently rewrite the history the operator
 * is most likely to be asked about. Archiving hides the plan from creation and touches no user.
 */
async function studioArchivePlan(env, id, actor) {
  const before = await env.DB.prepare(`SELECT ${STUDIO_PLAN_COLUMNS} FROM plans WHERE id = ?`).bind(id).first();
  if (!before) return studioErr('not_found', 'no such plan', 404);

  await env.DB.batch([
    env.DB.prepare('UPDATE plans SET archived = 1, is_default = 0, updated_at = ? WHERE id = ?').bind(Date.now(), id),
    studioAuditStmt(env, actor, 'plan.archive', id, studioPlanDto(before), null, 'plan'),
  ]);
  return studioJson({ ok: true, id, archived: true });
}

/**
 * `POST /v1/plans/{id}:apply` — push this plan's terms onto the people already on it.
 *
 * The dangerous half of plans, so it is opt-in field by field rather than "apply everything".
 *
 * **The caps are re-applied by default; volume and time are not.** A device or connection limit is
 * a property of the arrangement and re-applying it is what the operator means. `quota_bytes` is
 * not: someone who was topped up to 80 GB by a renewal last week is on this plan, and an apply that
 * silently included volume would pull them back down to 50 without anyone deciding that. Same for
 * expiry -- `duration_days` is measured from a moment, and re-applying it would either extend or
 * cut short every user's remaining time depending on when they happened to be created.
 *
 * So volume is included only when the caller asks for it by name, in `fields`, and the screen that
 * asks says what it will do.
 */
async function studioApplyPlan(request, env, id, actor) {
  const plan = await env.DB.prepare(`SELECT ${STUDIO_PLAN_COLUMNS} FROM plans WHERE id = ?`).bind(id).first();
  if (!plan) return studioErr('not_found', 'no such plan', 404);

  let body = {};
  try { body = await request.json(); } catch (e) { }
  const asked = Array.isArray(body.fields) ? body.fields : [];
  const wants = (name) => asked.length === 0 ? DEFAULT_APPLY_FIELDS.indexOf(name) >= 0 : asked.indexOf(name) >= 0;

  const sets = [];
  const binds = [];
  const changed = {};
  const put = (col, value, label) => { sets.push(col + ' = ?'); binds.push(value); changed[label || col] = value; };

  if (wants('device_limit')) {
    put('device_limit', plan.device_limit);
    // A device limit on a plan is a limit that applies (build 18): the operator typed a number.
    if (plan.device_limit > 0) { sets.push("enforcement = 'strict'"); }
  }
  if (wants('conn_limit')) put('conn_limit', plan.conn_limit);
  if (wants('ip_limit')) put('ip_limit', plan.ip_limit);
  if (wants('daily_quota_bytes')) {
    put('daily_quota_bytes', plan.daily_quota_bytes);
    // Its mirror too. It was left out, and while admission read the mirrors that made a plan's daily
    // cap one the page showed and nothing enforced.
    sets.push('daily_limit_gb = ?');
    binds.push(plan.daily_quota_bytes ? plan.daily_quota_bytes / 1073741824 : null);
  }
  if (wants('reset')) put('quota_reset_policy', plan.reset_policy, 'reset');
  if (wants('expiry_mode')) put('expiry_mode', plan.expiry_mode);
  if (wants('quota_bytes')) {
    put('quota_bytes', plan.quota_bytes);
    // The build-5 mirror, kept in step so a rollback does not hand out an unlimited account.
    sets.push('limit_gb = ?');
    binds.push(plan.quota_bytes ? plan.quota_bytes / 1073741824 : null);
  }
  if (!sets.length) return studioErr('bad_request', 'nothing to apply', 400);

  const now = Date.now();
  sets.push('updated_at = ?');
  binds.push(now);

  // Counted first, and reported, because a bulk write is charged per row against a 100k/day budget
  // and the operator is entitled to know what it cost. The count is indexed (v9).
  const countRow = await env.DB.prepare(
    'SELECT COUNT(*) AS n FROM users WHERE plan_id = ? AND deleted_at IS NULL'
  ).bind(id).first();
  const n = (countRow && countRow.n) || 0;

  await env.DB.batch([
    env.DB.prepare(`UPDATE users SET ${sets.join(', ')} WHERE plan_id = ? AND deleted_at IS NULL`).bind(...binds, id),
    studioAuditStmt(env, actor, 'plan.apply', id, null, { users: n, applied: changed }, 'plan'),
  ]);

  return studioJson({ ok: true, plan_id: id, users: n, applied: Object.keys(changed) });
}

/** What `:apply` touches when the caller names no fields. Volume and time are deliberately absent. */
const DEFAULT_APPLY_FIELDS = ['device_limit', 'conn_limit', 'ip_limit', 'daily_quota_bytes', 'reset', 'expiry_mode'];

// ---------------------------------------------------------------------------- dispatch

/** Returns null when the path is not its business, so `studioHandle` stays a list. */
async function studioPlansRoute(request, env, path, method, actor) {
  const url = new URL(request.url);

  if (path === '/plans') {
    if (method === 'GET') return await studioListPlans(env, url);
    // POST is accepted for symmetry with the rest of the surface, but the id still comes from the
    // caller when it has one: the fleet needs the same plan to be the same row on every account.
    if (method === 'POST') {
      let body = {};
      try { body = await request.clone().json(); } catch (e) { }
      const id = studioValidPlanId(body.id) ? body.id : studioRandomHex(8);
      return await studioPutPlan(request, env, id, actor);
    }
    return studioErr('method_not_allowed', method + ' is not allowed here', 405);
  }

  if (!path.startsWith('/plans/')) return null;
  const tail = path.slice('/plans/'.length);

  // `/plans/{id}/users` is the only sub-resource; anything else with a slash is not ours.
  if (tail.includes('/')) {
    const parts = tail.split('/');
    if (parts.length === 2 && parts[1] === 'users' && method === 'GET') {
      return await studioCountPlanUsers(env, parts[0]);
    }
    return null;
  }

  const colon = tail.indexOf(':');
  const id = colon === -1 ? tail : tail.slice(0, colon);
  const action = colon === -1 ? null : tail.slice(colon + 1);
  if (!id) return studioErr('bad_request', 'plan id is required', 400);

  if (action) {
    if (method !== 'POST') return studioErr('method_not_allowed', 'actions are POST', 405);
    if (action === 'apply') return await studioApplyPlan(request, env, id, actor);
    return null;
  }

  if (method === 'GET') return await studioGetPlan(env, id);
  // PUT, and deliberately no PATCH. A plan is a handful of fields that the app always holds in
  // full, so a partial update would only buy the chance for two devices to merge into terms
  // neither of them chose. Read, modify, write the whole thing.
  if (method === 'PUT') return await studioPutPlan(request, env, id, actor);
  if (method === 'DELETE') return await studioArchivePlan(env, id, actor);
  return studioErr('method_not_allowed', method + ' is not allowed here', 405);
}
