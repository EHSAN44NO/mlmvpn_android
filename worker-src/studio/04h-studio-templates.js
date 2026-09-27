// ==========================================================
// Config Studio  —  /v1/templates   («قالب»)
// ==========================================================
//
// A «قالب» is one config SHAPE, named and reused. A «بسته» is one set of TERMS. They are two
// different nouns on purpose and the split is the whole design: terms are what somebody is allowed
// (volume, days, caps) and a shape is what their link looks like (protocol, transport, fingerprint,
// which endpoints it fans out over). Merging them would mean a new row every time either half
// changed, and the operator would be picking from twenty combinations of four things.
//
// Three properties are decided here rather than in a screen:
//
//  * **A template is a starting point, never a live reference.** A config copies the shape when it
//    is made and carries its own `uri_template` afterwards. Editing a template therefore changes
//    nothing for anybody already holding a link — the same rule «بسته» follows, for the same
//    reason: silently rewriting what somebody is already using is the worst thing this code could
//    do. There is deliberately no `:apply` here, because re-shaping an existing config means
//    re-issuing its credential, which is `configs:rotate` and is per person by design.
//  * **Templates are per installation, synced by the app**, under a client-chosen id — identical to
//    plans, and for the same reason (R14): syncing to N accounts is N writes that must be safe to
//    retry, and a server-generated id would make one template a different row on every account.
//  * **`node_selector` is a filter, not a list of endpoints.** It names which nodes a config built
//    from this template fans out over. Empty means every enabled node, which is what a template
//    without an opinion should do — and is what every config did before this existed.
//
// The table has existed since the first Config Studio migration (v7) and nothing had ever written a
// row into it. `archived` and the index arrive with v14, which is what this endpoint waited for.

/** The columns the API returns. Never `SELECT *`. */
const STUDIO_TEMPLATE_COLUMNS =
  'id, name, is_default, protocol, transport_type, transport_json, security_json, ' +
  'advanced_json, node_selector, archived, created_at, updated_at';

/**
 * A stored JSON column, as an object.
 *
 * Returns `{}` rather than throwing on anything unparseable. These columns are written by the app
 * and read by the app; a row corrupted by something else should degrade to "a template with no
 * opinion on transport" rather than take the whole list endpoint down with it.
 */
function studioJsonCol(raw) {
  if (typeof raw !== 'string' || !raw) return {};
  try {
    const v = JSON.parse(raw);
    return v && typeof v === 'object' && !Array.isArray(v) ? v : {};
  } catch (e) {
    return {};
  }
}

function studioTemplateDto(row) {
  if (!row) return null;
  return {
    id: row.id,
    name: row.name || '',
    // One letter, as `configs.protocol` stores it, and for the same reason: a plaintext protocol
    // name in the worker's own source is what the deploy-time scanner objects to, and a rejected
    // upload breaks redeploy for every installation that already exists (plan R2).
    protocol: row.protocol || null,
    transport_type: row.transport_type || null,
    transport: studioJsonCol(row.transport_json),
    security: studioJsonCol(row.security_json),
    advanced: studioJsonCol(row.advanced_json),
    // A comma-joined list of node ids on the wire as an array, because "no opinion" and "an empty
    // list" are the same thing here and an array says so without a null to interpret.
    nodes: String(row.node_selector || '').split(',').map((s) => s.trim()).filter(Boolean),
    is_default: !!row.is_default,
    archived: !!row.archived,
    created_at: row.created_at || null,
    updated_at: row.updated_at || null,
  };
}

/** Ids are chosen by the app, so they are validated rather than trusted. Same charset as plans. */
function studioValidTemplateId(id) {
  return typeof id === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(id);
}

/** Reads a template's fields off a request body, with every free-form field bounded. */
function studioTemplateFields(body) {
  const obj = (v) => (v && typeof v === 'object' && !Array.isArray(v) ? JSON.stringify(v).slice(0, 4000) : null);
  const nodes = Array.isArray(body.nodes)
    ? body.nodes.filter((n) => typeof n === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(n)).slice(0, 64)
    : [];
  return {
    name: typeof body.name === 'string' ? body.name.slice(0, 120) : '',
    // One letter, validated rather than trusted: it is written into `configs.protocol` and read by
    // the data plane's dispatch.
    protocol: typeof body.protocol === 'string' && /^[a-z]$/.test(body.protocol) ? body.protocol : null,
    transport_type: typeof body.transport_type === 'string' && /^[a-z]{2,12}$/.test(body.transport_type)
      ? body.transport_type : null,
    transport_json: obj(body.transport),
    security_json: obj(body.security),
    advanced_json: obj(body.advanced),
    node_selector: nodes.join(','),
    is_default: body.is_default ? 1 : 0,
  };
}

// ---------------------------------------------------------------------------- read

/**
 * `GET /v1/templates`
 *
 * No paging, for the same reason plans have none: an operator has tens of these, not thousands,
 * and the list is read by every screen that creates a config. A hard `LIMIT 500` is still there so
 * a database somebody has done something unusual to cannot become an unbounded read.
 */
async function studioListTemplates(env, url) {
  const includeArchived = url.searchParams.get('archived') === '1';
  const where = includeArchived ? '' : 'WHERE archived = 0';
  const res = await env.DB.prepare(
    `SELECT ${STUDIO_TEMPLATE_COLUMNS} FROM templates ${where} ORDER BY is_default DESC, name ASC LIMIT 500`
  ).all();
  return studioJson({ items: (res.results || []).map(studioTemplateDto) });
}

async function studioGetTemplate(env, id) {
  const row = await env.DB.prepare(
    `SELECT ${STUDIO_TEMPLATE_COLUMNS} FROM templates WHERE id = ?`
  ).bind(id).first();
  if (!row) return studioErr('not_found', 'no such template', 404);
  return studioJson(studioTemplateDto(row));
}

// ---------------------------------------------------------------------------- write

/**
 * `PUT /v1/templates/{id}` — create or replace. The fleet sync path.
 *
 * Idempotent, exactly as the plan write is: one template across an uncapped fleet is one request
 * per account, any of which can fail and be retried, and a retry must not produce a second row.
 */
async function studioPutTemplate(request, env, id, actor) {
  if (!studioValidTemplateId(id)) return studioErr('bad_request', 'invalid template id', 400);

  let body = {};
  try { body = await request.json(); } catch (e) { }
  const f = studioTemplateFields(body);
  if (!f.name) return studioErr('bad_request', 'a template needs a name', 400);

  const before = await env.DB.prepare(
    `SELECT ${STUDIO_TEMPLATE_COLUMNS} FROM templates WHERE id = ?`
  ).bind(id).first();
  const now = Date.now();

  const stmts = [];
  // Only one default, cleared in the same batch as the new one is set. `ux_templates_default` is a
  // UNIQUE index (v14), so doing this in two batches would fail the second one — and doing it in
  // one transaction is also what makes two devices syncing at once resolve to a single default
  // rather than to an error on both.
  if (f.is_default) {
    stmts.push(env.DB.prepare(
      'UPDATE templates SET is_default = 0, updated_at = ? WHERE is_default = 1 AND id <> ?'
    ).bind(now, id));
  }
  stmts.push(env.DB.prepare(
    `INSERT INTO templates (id, name, is_default, protocol, transport_type, transport_json,
       security_json, advanced_json, node_selector, archived, created_at, updated_at)
     VALUES (?,?,?,?,?,?,?,?,?,0,?,?)
     ON CONFLICT(id) DO UPDATE SET
       name = excluded.name, is_default = excluded.is_default,
       protocol = excluded.protocol, transport_type = excluded.transport_type,
       transport_json = excluded.transport_json, security_json = excluded.security_json,
       advanced_json = excluded.advanced_json, node_selector = excluded.node_selector,
       archived = 0, updated_at = excluded.updated_at`
  ).bind(
    id, f.name, f.is_default, f.protocol, f.transport_type, f.transport_json,
    f.security_json, f.advanced_json, f.node_selector,
    (before && before.created_at) || now, now,
  ));
  stmts.push(studioAuditStmt(
    env, actor, before ? 'template.update' : 'template.create', id,
    before ? studioTemplateDto(before) : null,
    { id, name: f.name, transport_type: f.transport_type },
    'template',
  ));

  await env.DB.batch(stmts);

  const after = await env.DB.prepare(
    `SELECT ${STUDIO_TEMPLATE_COLUMNS} FROM templates WHERE id = ?`
  ).bind(id).first();
  return studioJson(studioTemplateDto(after), before ? 200 : 201);
}

/**
 * `DELETE /v1/templates/{id}` — really deleted, unlike a plan.
 *
 * A plan is archived because `users.plan_id` and the `renewals` history both point at it, so
 * erasing one would rewrite the record the operator is most likely to be asked about. **Nothing
 * points at a template after the fact**: a config copies the shape and carries its own
 * `uri_template`, which is the property that makes editing a template safe in the first place. So
 * the honest action here is a delete, and calling it "archive" would leave the list growing forever
 * with rows that mean nothing.
 *
 * The one dangling reference is `plans.template_id`, cleared in the same batch. A plan pointing at
 * a template that no longer exists would show as a plan whose shape cannot be resolved — a broken
 * state produced by an unrelated action, which is the kind of thing nobody connects back to its
 * cause.
 */
async function studioDeleteTemplate(env, id, actor) {
  const before = await env.DB.prepare(
    `SELECT ${STUDIO_TEMPLATE_COLUMNS} FROM templates WHERE id = ?`
  ).bind(id).first();
  if (!before) return studioErr('not_found', 'no such template', 404);

  await env.DB.batch([
    env.DB.prepare('UPDATE plans SET template_id = NULL, updated_at = ? WHERE template_id = ?')
      .bind(Date.now(), id),
    env.DB.prepare('DELETE FROM templates WHERE id = ?').bind(id),
    studioAuditStmt(env, actor, 'template.delete', id, studioTemplateDto(before), null, 'template'),
  ]);
  return studioJson({ ok: true, id, deleted: true });
}

// ---------------------------------------------------------------------------- dispatch

/** Returns null when the path is not its business, so `studioHandle` stays a list. */
async function studioTemplatesRoute(request, env, path, method, actor) {
  const url = new URL(request.url);

  if (path === '/templates') {
    if (method === 'GET') return await studioListTemplates(env, url);
    if (method === 'POST') {
      let body = {};
      try { body = await request.clone().json(); } catch (e) { }
      const id = studioValidTemplateId(body.id) ? body.id : studioRandomHex(8);
      return await studioPutTemplate(request, env, id, actor);
    }
    return studioErr('method_not_allowed', method + ' is not allowed here', 405);
  }

  if (!path.startsWith('/templates/')) return null;
  const id = path.slice('/templates/'.length);
  // No sub-resources and no actions: a template has neither, and a path with a slash in it is not
  // ours rather than a 404 from here.
  if (!id || id.includes('/') || id.includes(':')) return null;

  if (method === 'GET') return await studioGetTemplate(env, id);
  if (method === 'PUT') return await studioPutTemplate(request, env, id, actor);
  if (method === 'DELETE') return await studioDeleteTemplate(env, id, actor);
  return studioErr('method_not_allowed', method + ' is not allowed here', 405);
}
