// ==========================================================
// Versioned schema migrations (Config Studio, build 6)
// ==========================================================
//
// Replaces the lazy `DbService.ensureSchema` pattern above, which ran `CREATE TABLE IF NOT EXISTS`
// plus a column of best-effort `ALTER`s on *every request*, each wrapped in `try {} catch (e) {}`.
// That cannot tell "this column already exists" (fine, every request after the first) from "this
// statement has a typo and this database will never have that column" (permanently broken, silently)
// -- both are swallowed identically. It also spent D1 reads on schema work in the hot path.
//
// Rules this file follows, all of them from CONFIG-STUDIO-PLAN.md §7.1:
//
//   * A migration lands whole or not at all -- `db.batch()` is one implicit transaction.
//   * Failures PROPAGATE. `/v1/health` reports `migration_error` and the wizard's Repair action
//     re-runs the migration and shows the real message, instead of a panel that half-works forever.
//   * `migrate()` is called from the API path ONLY. The data plane must never call it: that would be
//     a read on every tunnel connection to answer a question that changes about once a year.

let migrationsDone = false;

// The legacy schema, for a database that has never been deployed to.
//
// An account that already ran build 5 is *seeded* at this version instead (see `migrate`), because
// re-running these ALTERs against a live database fails on "duplicate column name" and would take
// the whole batch -- and therefore build 6 -- down with it.
const MIGRATION_LEGACY_BASE = {
  v: 5,
  name: 'legacy_base',
  sql: [
    `CREATE TABLE IF NOT EXISTS users (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      username TEXT UNIQUE,
      uuid TEXT,
      limit_gb REAL,
      expiry_days INTEGER,
      ips TEXT,
      connection_type TEXT,
      tls TEXT,
      port INTEGER,
      used_gb REAL DEFAULT 0,
      is_active INTEGER DEFAULT 1,
      last_active INTEGER,
      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
      fingerprint TEXT DEFAULT 'chrome',
      daily_limit_gb REAL,
      daily_used_gb REAL DEFAULT 0,
      daily_reset_at INTEGER DEFAULT 0,
      proxy_ip TEXT
    )`,
    `CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT)`,
    `CREATE TABLE IF NOT EXISTS debug_logs (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, line TEXT)`,
  ],
};

// Build 6, part one: the columns Config Studio adds to `users`, and the backfill.
//
// Split from part two because D1 allows **50 queries per Worker invocation** and the two halves
// together are over it. Each half is atomic on its own, and `schema_version` records them
// separately -- so if part two fails, part one stays applied and part two simply runs again on the
// next API request rather than the whole thing rolling back and retrying forever.
//
// NOTE ON `uid`: the plan's §7.2 calls this column `id`, which cannot work -- `users.id` already
// exists as `INTEGER PRIMARY KEY AUTOINCREMENT` from the legacy schema, so `ADD COLUMN id` fails
// with "duplicate column name: id" on every database that has ever been deployed. The column is
// `uid` here and is what the API exposes as the user's `id`; the legacy integer stays as the
// physical key and is never sent to a client.
const MIGRATION_STUDIO_COLUMNS = {
  v: 6,
  name: 'studio_columns',
  sql: [
    `ALTER TABLE users ADD COLUMN uid TEXT`,
    `ALTER TABLE users ADD COLUMN note TEXT`,
    `ALTER TABLE users ADD COLUMN tags TEXT`,
    `ALTER TABLE users ADD COLUMN group_id TEXT`,
    `ALTER TABLE users ADD COLUMN plan_id TEXT`,
    `ALTER TABLE users ADD COLUMN status TEXT DEFAULT 'active'`,
    `ALTER TABLE users ADD COLUMN expiry_mode TEXT DEFAULT 'absolute'`,
    `ALTER TABLE users ADD COLUMN expires_at INTEGER`,
    `ALTER TABLE users ADD COLUMN activation_days INTEGER`,
    `ALTER TABLE users ADD COLUMN first_connect_at INTEGER`,
    `ALTER TABLE users ADD COLUMN quota_bytes INTEGER`,
    `ALTER TABLE users ADD COLUMN used_bytes INTEGER DEFAULT 0`,
    `ALTER TABLE users ADD COLUMN daily_quota_bytes INTEGER`,
    `ALTER TABLE users ADD COLUMN daily_used_bytes INTEGER DEFAULT 0`,
    `ALTER TABLE users ADD COLUMN quota_reset_policy TEXT DEFAULT 'none'`,
    `ALTER TABLE users ADD COLUMN quota_reset_at INTEGER`,
    `ALTER TABLE users ADD COLUMN device_limit INTEGER`,
    `ALTER TABLE users ADD COLUMN conn_limit INTEGER`,
    `ALTER TABLE users ADD COLUMN ip_limit INTEGER`,
    `ALTER TABLE users ADD COLUMN enforcement TEXT DEFAULT 'soft'`,
    `ALTER TABLE users ADD COLUMN updated_at INTEGER`,

    // The tombstone. Without it a delete is invisible to every other device that has this user in
    // its local index, so it comes back on the next sync (§A.3).
    `ALTER TABLE users ADD COLUMN deleted_at INTEGER`,

    // Backfill. Bytes, not GB floats: `limit_gb REAL` cannot express exactly 30 GB, and someone
    // who was given 30 and is shown 29.7 has a question that costs more to answer than this
    // column costs to carry. Build 6 writes BOTH so a rollback to build 5 still works; build 7
    // drops the REAL columns.
    `UPDATE users SET quota_bytes = CAST(limit_gb * 1073741824 AS INTEGER)
       WHERE quota_bytes IS NULL AND limit_gb IS NOT NULL AND limit_gb > 0`,
    `UPDATE users SET used_bytes = CAST(used_gb * 1073741824 AS INTEGER)
       WHERE (used_bytes IS NULL OR used_bytes = 0) AND used_gb IS NOT NULL AND used_gb > 0`,
    `UPDATE users SET daily_quota_bytes = CAST(daily_limit_gb * 1073741824 AS INTEGER)
       WHERE daily_quota_bytes IS NULL AND daily_limit_gb IS NOT NULL AND daily_limit_gb > 0`,

    // expiry_days counted from created_at, so renewing meant delete-and-recreate -- which loses the
    // user's link. An absolute timestamp is what makes "extend by 30 days" a single UPDATE.
    `UPDATE users SET expires_at =
       (CAST(strftime('%s', created_at) AS INTEGER) * 1000) + (expiry_days * 86400000)
       WHERE expires_at IS NULL AND expiry_days IS NOT NULL AND expiry_days > 0`,

    // Opaque per-user id, so a rename is a PATCH rather than being structurally impossible.
    // randomblob(12) is 96 bits -- collision-free in practice for any realistic user count, and it
    // is generated by SQLite so the backfill needs no round trip per row.
    `UPDATE users SET uid = lower(hex(randomblob(12))) WHERE uid IS NULL OR uid = ''`,
    `UPDATE users SET status = CASE WHEN is_active = 0 THEN 'disabled' ELSE 'active' END
       WHERE status IS NULL`,
    `UPDATE users SET updated_at = COALESCE(last_active, CAST(strftime('%s','now') AS INTEGER) * 1000)
       WHERE updated_at IS NULL`,
  ],
};

// Build 6, part two: everything that is a new table, plus the indexes.
//
// The indexes are not an optimisation, they are the quota defence. D1 bills **rows read**, and a
// query without an index the planner can stop at reads the whole table however small the LIMIT is.
// One `ORDER BY RANDOM()` in a sibling worker took this account's entire 5M/day read ceiling down on
// 2026-09-06 and every D1-backed worker on the account died together (§4.2).
const MIGRATION_STUDIO_TABLES = {
  v: 7,
  name: 'studio_tables',
  sql: [
    `CREATE TABLE IF NOT EXISTS api_keys (
      id TEXT PRIMARY KEY, label TEXT, secret_hash TEXT NOT NULL,
      scopes TEXT DEFAULT 'admin', created_at INTEGER, expires_at INTEGER,
      last_used_at INTEGER, revoked_at INTEGER)`,

    `CREATE TABLE IF NOT EXISTS configs (
      id TEXT PRIMARY KEY, user_uid TEXT NOT NULL, template_id TEXT, node_id TEXT,
      label TEXT, enabled INTEGER DEFAULT 1,
      protocol TEXT NOT NULL, credential TEXT NOT NULL, credential_rotated_at INTEGER,
      transport_type TEXT NOT NULL, transport_json TEXT NOT NULL, security_json TEXT NOT NULL,
      uri_template TEXT, route_key TEXT,
      created_at INTEGER, updated_at INTEGER, deleted_at INTEGER)`,

    `CREATE TABLE IF NOT EXISTS subscriptions (
      id TEXT PRIMARY KEY, user_uid TEXT NOT NULL, token TEXT NOT NULL,
      format TEXT DEFAULT 'base64', created_at INTEGER, rotated_at INTEGER,
      revoked_at INTEGER, expires_at INTEGER,
      hits INTEGER DEFAULT 0, last_hit_at INTEGER, last_ua TEXT)`,

    // Reusable terms. NO price column, in this or any later migration -- the operator's own
    // arrangements are not this product's business and a column named `price` would eventually
    // surface in a screen.
    `CREATE TABLE IF NOT EXISTS plans (
      id TEXT PRIMARY KEY, name TEXT, quota_bytes INTEGER, duration_days INTEGER,
      expiry_mode TEXT DEFAULT 'absolute', daily_quota_bytes INTEGER,
      reset_policy TEXT DEFAULT 'none',
      device_limit INTEGER, conn_limit INTEGER, ip_limit INTEGER,
      template_id TEXT, is_default INTEGER DEFAULT 0, archived INTEGER DEFAULT 0,
      created_at INTEGER, updated_at INTEGER)`,

    // Kept for the life of the user, not pruned with the other logs: this is the record the
    // operator gets asked about.
    `CREATE TABLE IF NOT EXISTS renewals (
      id TEXT PRIMARY KEY, user_uid TEXT, ts INTEGER, actor TEXT, plan_id TEXT,
      mode TEXT, days INTEGER, bytes INTEGER, before_json TEXT, after_json TEXT)`,

    `CREATE TABLE IF NOT EXISTS templates (
      id TEXT PRIMARY KEY, name TEXT, is_default INTEGER DEFAULT 0,
      protocol TEXT, transport_type TEXT, transport_json TEXT,
      security_json TEXT, advanced_json TEXT, node_selector TEXT,
      created_at INTEGER, updated_at INTEGER)`,

    `CREATE TABLE IF NOT EXISTS nodes (
      id TEXT PRIMARY KEY, name TEXT, group_id TEXT, country TEXT,
      host TEXT, ports TEXT, sni TEXT, host_header TEXT,
      capabilities_json TEXT, priority INTEGER DEFAULT 100, enabled INTEGER DEFAULT 1,
      health TEXT DEFAULT 'unknown', health_checked_at INTEGER, metadata_json TEXT)`,

    `CREATE TABLE IF NOT EXISTS sessions (
      id TEXT PRIMARY KEY, user_uid TEXT, config_id TEXT, node_id TEXT,
      device_hash TEXT, ip_hash TEXT, protocol TEXT, transport TEXT,
      started_at INTEGER, ended_at INTEGER,
      up_bytes INTEGER DEFAULT 0, down_bytes INTEGER DEFAULT 0, close_reason TEXT)`,

    `CREATE TABLE IF NOT EXISTS devices (
      user_uid TEXT, device_hash TEXT, first_seen INTEGER, last_seen INTEGER,
      last_ip_hash TEXT, client_hint TEXT, blocked INTEGER DEFAULT 0,
      PRIMARY KEY (user_uid, device_hash))`,

    `CREATE TABLE IF NOT EXISTS usage_daily (
      user_uid TEXT, day INTEGER, up_bytes INTEGER, down_bytes INTEGER,
      sessions INTEGER, PRIMARY KEY (user_uid, day))`,

    // Account-wide: 24 rows a day whatever the user count, which is what makes the dashboard's
    // traffic chart answerable without touching per-user rows.
    `CREATE TABLE IF NOT EXISTS usage_hourly (
      bucket_ts INTEGER PRIMARY KEY, up_bytes INTEGER, down_bytes INTEGER,
      sessions INTEGER, users_seen INTEGER)`,

    `CREATE TABLE IF NOT EXISTS activity_log (
      id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, kind TEXT,
      user_uid TEXT, config_id TEXT, node_id TEXT,
      severity TEXT DEFAULT 'info', detail TEXT)`,

    `CREATE TABLE IF NOT EXISTS audit_log (
      id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, actor TEXT, actor_key_id TEXT,
      action TEXT, target_type TEXT, target_id TEXT,
      before_json TEXT, after_json TEXT, ip_hash TEXT)`,

    // COUNT(*) over a whole table is banned in a request path (§4.2 rule 3); counts come from here,
    // maintained on write.
    `CREATE TABLE IF NOT EXISTS counters (key TEXT PRIMARY KEY, value INTEGER)`,

    `CREATE UNIQUE INDEX IF NOT EXISTS ux_users_uid ON users(uid)`,
    `CREATE INDEX IF NOT EXISTS ix_users_status_created ON users(status, created_at DESC)`,
    `CREATE INDEX IF NOT EXISTS ix_users_expires ON users(expires_at)`,
    // The ?since= sync watermark. Every device's incremental pull orders by this column, so
    // without the index each sync reads the whole users table -- per device, per cycle (§A.3).
    `CREATE INDEX IF NOT EXISTS ix_users_updated ON users(updated_at)`,
    // The data-plane lookup: one indexed read per tunnel connection, and the single hottest query
    // in the worker.
    `CREATE INDEX IF NOT EXISTS ix_users_uuid ON users(uuid)`,
    `CREATE UNIQUE INDEX IF NOT EXISTS ux_configs_route ON configs(route_key)`,
    `CREATE INDEX IF NOT EXISTS ix_configs_user ON configs(user_uid)`,
    `CREATE UNIQUE INDEX IF NOT EXISTS ux_sub_token ON subscriptions(token)`,
    `CREATE INDEX IF NOT EXISTS ix_sub_user ON subscriptions(user_uid)`,
    `CREATE INDEX IF NOT EXISTS ix_sessions_started ON sessions(started_at DESC)`,
    `CREATE INDEX IF NOT EXISTS ix_sessions_user ON sessions(user_uid, started_at DESC)`,
    `CREATE INDEX IF NOT EXISTS ix_activity_ts ON activity_log(ts DESC)`,
    `CREATE INDEX IF NOT EXISTS ix_audit_ts ON audit_log(ts DESC)`,
    `CREATE INDEX IF NOT EXISTS ix_renewals_user ON renewals(user_uid, ts DESC)`,
  ],
};

// Build 6, part three: what paging and counting need.
//
// A separate migration rather than more statements in v7, even though v7 has not shipped anywhere
// yet. The whole point of a versioned migrator is that a migration, once written down, is a fact
// about databases in the world -- and `MlmDeployer` uploads whatever asset is in the APK, so
// "nothing has it yet" is a belief about other people's accounts, not something this file can know.
// Appending is free; editing history is only free until it is not.
const MIGRATION_STUDIO_PAGING = {
  v: 8,
  name: 'studio_paging',
  sql: [
    // Keyset pagination orders by (created_at DESC, uid DESC). Without an index in exactly that
    // shape SQLite sorts the whole table for every page, and D1 bills rows READ -- so the cost of
    // page 1 would be the cost of the entire user list, on every refresh, for every device.
    // `ix_users_status_created` cannot serve it: `status` is its leading column, so it only helps
    // a query that filters on status first.
    `CREATE INDEX IF NOT EXISTS ix_users_created_uid ON users(created_at DESC, uid DESC)`,

    // COUNT(*) over a whole table is banned in a request path (§4.2 rule 3), so the count comes
    // from here and is maintained on write. This is the one COUNT(*) that is allowed: it runs once,
    // in a migration, to seed the counter from whatever is already there.
    `INSERT OR REPLACE INTO counters (key, value)
       SELECT 'users_total', COUNT(*) FROM users WHERE deleted_at IS NULL`,
    `INSERT OR REPLACE INTO counters (key, value)
       SELECT 'users_active', COUNT(*) FROM users WHERE deleted_at IS NULL AND status = 'active'`,
  ],
};

// A «بسته» is a set of terms handed to many people, so two questions get asked about every one of
// them: how many users are on it, and re-apply it to those users. Both filter `users` by `plan_id`,
// which nothing indexed -- so both would have read the entire user table, which is the exact shape
// of the 2026-09-06 outage (§4.2). One index, added before the first screen that can ask.
const MIGRATION_STUDIO_PLANS = {
  v: 9,
  name: 'studio_plans',
  sql: [
    `CREATE INDEX IF NOT EXISTS ix_users_plan ON users(plan_id)`,

    // Only one plan may be the default. The invariant is enforced on write in `studioPutPlan`, but
    // a partial unique index makes it true of the data rather than of the code path that happens
    // to be running -- two devices syncing the fleet concurrently is a real race here (R14).
    `CREATE UNIQUE INDEX IF NOT EXISTS ux_plans_default
       ON plans(is_default) WHERE is_default = 1 AND archived = 0`,
  ],
};

// The sync watermark needs a tie-break, and until this index existed it did not have one.
//
// `?since=` paged on `updated_at` alone: a page ended, its last row's timestamp became the next
// watermark, and the next request asked for `updated_at > that`. Every row sharing that timestamp
// beyond the page limit was then skipped -- not retried later, skipped for good, because the
// watermark had already moved past them.
//
// That is not a theoretical tie. Two paths write one identical timestamp to many rows at once:
// `:apply` sets a single `now` across every user on a «بسته», and the v6 backfill gave every legacy
// user with no `last_active` the same value. So an account with more users on one timestamp than a
// page holds had a local index that was quietly missing them, while looking complete.
//
// The fix is a keyset on `(updated_at, uid)`, which needs the index in exactly that shape or the
// comparison sorts the table -- and D1 bills rows READ, so an unindexed sync would cost the whole
// user list per device per cycle (§4.2).
const MIGRATION_STUDIO_SYNC_KEYSET = {
  v: 10,
  name: 'studio_sync_keyset',
  sql: [
    `CREATE INDEX IF NOT EXISTS ix_users_updated_uid ON users(updated_at, uid)`,
  ],
};

// Trojan needs a lookup the schema could not express.
//
// VLESS puts a UUID on the wire and `users.uuid` is indexed for it. Trojan puts
// **hex(SHA-224(password))** on the wire — never the password — so the data plane has to match on
// the hash, and it cannot compute it: Workers' `crypto.subtle` implements SHA-1, SHA-256, SHA-384
// and SHA-512 and **not** SHA-224. Implementing SHA-224 in JS to run it on every connection would
// be both a hot-path cost and a second implementation of something the JVM already has.
//
// So the app computes it once, when the config is created, and stores it here. The worker does one
// indexed lookup and no hashing at all — which is also the right shape for any future protocol that
// authenticates with a derived value rather than a raw one.
//
// The index is partial so that every VLESS config, which has no `auth_hash`, does not collide with
// every other VLESS config on NULL.
const MIGRATION_STUDIO_AUTH_HASH = {
  v: 11,
  name: 'studio_auth_hash',
  sql: [
    `ALTER TABLE configs ADD COLUMN auth_hash TEXT`,
    `CREATE UNIQUE INDEX IF NOT EXISTS ux_configs_auth ON configs(auth_hash) WHERE auth_hash IS NOT NULL`,
  ],
};

// Build 7: the three counts a dashboard wants, and the two columns a node health probe writes.
//
// Every statement here exists to make a figure CHEAP rather than to make it possible. The rule the
// rest of this schema follows -- no unbounded scan in a request path -- is what decides the shape:
//
//  * `ix_users_last_active` turns "how many people are connected right now" from a full users scan
//    into a bounded index range. `last_active` is written at most once per 65 seconds per user by
//    the data plane, so it was already there and already accurate; nothing could afford to read it.
//  * `ix_configs_live` is a PARTIAL index over live rows only, so counting configs reads the index
//    and never the table. A tombstoned config costs nothing because it is not in the index at all.
//  * `latency_ms` and `last_error` hold what a probe measured. The probe runs on the PHONE, not in
//    the worker: a worker measuring the round trip to a node measures Cloudflare's own network,
//    which is not the number an operator in Tehran needs. So the app measures and reports, and
//    these columns are the record of the last report.
const MIGRATION_STUDIO_NODE_HEALTH = {
  v: 12,
  name: 'studio_node_health',
  sql: [
    `ALTER TABLE nodes ADD COLUMN latency_ms INTEGER`,
    `ALTER TABLE nodes ADD COLUMN last_error TEXT`,
    `CREATE INDEX IF NOT EXISTS ix_nodes_enabled ON nodes(enabled, priority)`,
    `CREATE INDEX IF NOT EXISTS ix_users_last_active ON users(last_active)`,
    `CREATE INDEX IF NOT EXISTS ix_configs_live ON configs(id) WHERE deleted_at IS NULL`,
  ],
};

// Build 7, part two: what makes `activity_log` readable.
//
// The table has existed since the first Config Studio migration and **nothing had ever written a
// row to it** -- so "recent errors" was a screen with no source. Writing it is a code change; being
// able to ask "show me only the failures" without reading the whole table is this index.
//
// Descending on `id` rather than on `ts`, because `id` is the primary key and already the order the
// rows come out in. A composite on (severity, id DESC) turns the filtered read into a range scan
// that stops at the page size, where (severity, ts) would still have to sort.
const MIGRATION_STUDIO_ACTIVITY = {
  v: 13,
  name: 'studio_activity_index',
  sql: [
    `CREATE INDEX IF NOT EXISTS ix_activity_severity ON activity_log(severity, id DESC)`,
  ],
};

/**
 * Build 9. What the `templates` table needed before anything could be written to it.
 *
 * The table itself has existed since v7 and **not one row was ever inserted** — it was created
 * alongside the tables that were going to be used, and then the feature that would have used it was
 * deferred twice. Three things were missing:
 *
 *  * `archived`, so a template can be taken out of circulation the way a plan can. It is not used
 *    by DELETE (a template really is deleted — nothing points at one after a config is made) but
 *    the column is what lets the list endpoint have the same shape as the plan one, and what a
 *    later "keep it but stop offering it" needs.
 *  * `ux_templates_default`, so "the default template" is one row rather than a convention. The
 *    plan table learned this the hard way: without the UNIQUE index, two devices syncing at once
 *    each set their own default and the list then had two.
 *  * `configs.node_selector`, which is the per-config half of the template's own selector. Without
 *    it a template could name which endpoints it fans out over and a config built from it could
 *    not remember, so the choice would be discarded the moment it was made.
 *
 * The partial index on `is_default` rather than a plain UNIQUE: SQLite treats NULLs as distinct in
 * a UNIQUE index, and `is_default` is 0 for almost every row — a plain unique index over the
 * column would refuse the second non-default template.
 */
const MIGRATION_STUDIO_TEMPLATES = {
  v: 14,
  name: 'studio_templates',
  sql: [
    `ALTER TABLE templates ADD COLUMN archived INTEGER DEFAULT 0`,
    `CREATE UNIQUE INDEX IF NOT EXISTS ux_templates_default ON templates(is_default) WHERE is_default = 1`,
    `CREATE INDEX IF NOT EXISTS ix_templates_archived ON templates(archived, name)`,
    `ALTER TABLE configs ADD COLUMN node_selector TEXT`,
  ],
};

/**
 * Build 10. Groups, a place on the map, and a probe history.
 *
 * `nodes.group_id` has existed since v7 and **nothing had ever written to it**, because there was
 * no table for it to point at. The three additions here are what a group needs to mean anything:
 *
 *  * `node_groups`, carrying the one thing a group is for — a **strategy**, which is a rule for
 *    the ORDER endpoints are handed out in. Not a routing table: every endpoint is a different
 *    clean IP reaching the same worker, so there is nothing to route between and the client on the
 *    far side is what picks. Ordering is the whole of what the engine controls.
 *  * `nodes.city`, which is free text and is used by nothing except the operator's own eyes. It is
 *    here because two clean IPs in the same country are otherwise told apart by their address.
 *  * `node_health`, fifty probes per endpoint. It is what «تاریخچهٔ بررسی» reads and what the
 *    uptime figure is computed from — the nodes table only ever held the LAST result, so "is this
 *    address flaky or did it fail once" was a question nothing could answer.
 *
 * The index is on `(node_id, id DESC)` rather than on `ts`: `id` is the primary key and already the
 * insertion order, so the history read and the prune both become range scans that stop at fifty.
 */
const MIGRATION_STUDIO_NODE_GROUPS = {
  v: 15,
  name: 'studio_node_groups',
  sql: [
    `CREATE TABLE IF NOT EXISTS node_groups (
      id TEXT PRIMARY KEY, name TEXT, strategy TEXT DEFAULT 'order',
      priority INTEGER DEFAULT 100, enabled INTEGER DEFAULT 1,
      created_at INTEGER, updated_at INTEGER)`,
    `CREATE INDEX IF NOT EXISTS ix_node_groups_priority ON node_groups(priority, name)`,
    `ALTER TABLE nodes ADD COLUMN city TEXT`,
    `CREATE INDEX IF NOT EXISTS ix_nodes_group ON nodes(group_id)`,
    `CREATE TABLE IF NOT EXISTS node_health (
      id INTEGER PRIMARY KEY AUTOINCREMENT, node_id TEXT NOT NULL, ts INTEGER,
      up INTEGER, latency_ms INTEGER, error TEXT)`,
    `CREATE INDEX IF NOT EXISTS ix_node_health_node ON node_health(node_id, id DESC)`,
  ],
};

/**
 * Build 12: the credential index that makes a per-config secret work at all, and the two indexes
 * `sessions` needs now that something finally writes to it.
 *
 * **`ix_configs_credential` is a bug fix, not an optimisation.** The WebSocket path authenticated
 * with `SELECT * FROM users WHERE uuid = ?` and never looked at `configs` — so a VLESS config
 * carrying a credential of its own, which is what the builder generates by default for the most
 * common shape there is, produced a link that imported cleanly into any client and could never
 * connect. It failed silently and in the worst possible place: the operator sees a config, the
 * subscriber sees a server, and nothing anywhere says the secret is not one the engine knows.
 * Trojan never had the problem because `studioTrojanUser` has always joined `configs`.
 *
 * `sessions` was created by the third migration and **nothing had ever inserted a row into it**,
 * so per-config traffic, the popular-protocol figures, and a person's own connection history were
 * all questions with a table and no data. The two indexes are the read patterns that exist:
 * `(user_uid, id DESC)` for one person's history and the prune, and `(ended_at)` for every
 * range-filtered aggregate.
 *
 * `ix_sessions_user` already existed on `(user_uid, started_at DESC)` and is the wrong column for
 * this: `started_at` is when the connection OPENED, so a session that began yesterday and ended a
 * minute ago sorts a day back and a history ordered by it is not in the order anything happened.
 * `ended_at` is when the row exists, which for a table written only on close is what recency means.
 * The table's own `id` cannot be used either — it is a random hex string, not a sequence.
 */
const MIGRATION_STUDIO_SESSIONS = {
  v: 16,
  name: 'studio_sessions',
  sql: [
    `CREATE INDEX IF NOT EXISTS ix_configs_credential ON configs(credential)`,
    `CREATE INDEX IF NOT EXISTS ix_sessions_user_ended ON sessions(user_uid, ended_at DESC)`,
    `CREATE INDEX IF NOT EXISTS ix_sessions_ended ON sessions(ended_at DESC)`,
  ],
};

/**
 * Build 16: locations.
 *
 * `exits` is the operator's list of SOCKS5 / HTTP-CONNECT servers, one country each (04j). The data
 * plane reads it by `(cc, enabled)`, so that is the index. `configs.exit_cc` is the country a config
 * leaves from -- null for every config written before this, which keeps them exactly as they were.
 */
const MIGRATION_STUDIO_EXITS = {
  v: 17,
  name: 'studio_exits',
  sql: [
    `CREATE TABLE IF NOT EXISTS exits (
      id TEXT PRIMARY KEY, cc TEXT NOT NULL, label TEXT, url TEXT NOT NULL,
      enabled INTEGER DEFAULT 1, health TEXT DEFAULT 'unknown', exit_ip TEXT, exit_cc TEXT,
      latency_ms INTEGER, fails INTEGER DEFAULT 0, checked_at INTEGER,
      created_at INTEGER, updated_at INTEGER)`,
    `CREATE INDEX IF NOT EXISTS ix_exits_cc ON exits(cc, enabled)`,
    `ALTER TABLE configs ADD COLUMN exit_cc TEXT`,
  ],
};

/**
 * v18 -- build 17: `users.locations`, the countries of a person's live configs ("DE,NL"), kept in
 * step by every config write (04c › studioLocationsStmts). Backfilled here so people created with
 * locations before this build show them too.
 */
const MIGRATION_STUDIO_USER_LOCATIONS = {
  v: 18,
  name: 'studio_user_locations',
  sql: [
    `ALTER TABLE users ADD COLUMN locations TEXT`,
    `UPDATE users SET locations = (SELECT group_concat(cc) FROM (SELECT DISTINCT exit_cc AS cc FROM configs
       WHERE configs.user_uid = users.uid AND configs.deleted_at IS NULL AND configs.exit_cc IS NOT NULL ORDER BY exit_cc))`,
  ],
};

/**
 * v19 -- build 18.
 *
 *  * `configs.exit_pin*`: the one exit a location config leaves through, chosen from servers whose
 *    country was MEASURED, and written back when it fails over (04j). Before this the exit was picked
 *    per connection and per isolate, so one person hopped between addresses -- and countries.
 *  * `pool_verified`: per country, the public servers that were tested and really leave from there
 *    (04k). The public lists label a server by where its listener is registered, not by where
 *    traffic leaves, and nothing checked before sending people through it.
 *  * `ix_exits_measured`: exits are chosen by the country a test measured, not the one they were
 *    filed under.
 *  * Every user with a device limit becomes `strict`. The limit was recorded and not applied for
 *    everyone the new-user screen never switched over (bulk-created users always), and the operator
 *    asked for the number they type to be the number that applies. Monitor-only stays available
 *    per user.
 *  * A uid for any row the legacy panel created without one.
 */
const MIGRATION_STUDIO_BUILD18 = {
  v: 19,
  name: 'studio_meter_presence_pins',
  sql: [
    `ALTER TABLE configs ADD COLUMN exit_pin TEXT`,
    `ALTER TABLE configs ADD COLUMN exit_pin_ip TEXT`,
    `ALTER TABLE configs ADD COLUMN exit_pin_at INTEGER`,
    `CREATE TABLE IF NOT EXISTS pool_verified (cc TEXT PRIMARY KEY, list TEXT, checked_at INTEGER)`,
    `CREATE INDEX IF NOT EXISTS ix_exits_measured ON exits(exit_cc, enabled)`,
    `UPDATE users SET enforcement = 'strict'
       WHERE device_limit > 0 AND (enforcement IS NULL OR enforcement = 'soft')`,
    `UPDATE users SET uid = lower(hex(randomblob(12))) WHERE uid IS NULL OR uid = ''`,
  ],
};

const MIGRATIONS = [
  MIGRATION_LEGACY_BASE,
  MIGRATION_STUDIO_COLUMNS,
  MIGRATION_STUDIO_TABLES,
  MIGRATION_STUDIO_PAGING,
  MIGRATION_STUDIO_PLANS,
  MIGRATION_STUDIO_SYNC_KEYSET,
  MIGRATION_STUDIO_AUTH_HASH,
  MIGRATION_STUDIO_NODE_HEALTH,
  MIGRATION_STUDIO_ACTIVITY,
  MIGRATION_STUDIO_TEMPLATES,
  MIGRATION_STUDIO_NODE_GROUPS,
  MIGRATION_STUDIO_SESSIONS,
  MIGRATION_STUDIO_EXITS,
  MIGRATION_STUDIO_USER_LOCATIONS,
  MIGRATION_STUDIO_BUILD18,
];

// Set when a migration throws, and reported by /v1/health so the app can show the real message
// instead of the panel simply not working.
let lastMigrationError = null;

async function migrate(db) {
  if (migrationsDone) return;

  await db.prepare(
    `CREATE TABLE IF NOT EXISTS schema_version (v INTEGER PRIMARY KEY, name TEXT, applied_at INTEGER)`
  ).run();

  let at = 0;
  const row = await db.prepare('SELECT MAX(v) AS v FROM schema_version').first();
  if (row && row.v != null) {
    at = row.v;
  } else if (await legacySchemaExists(db)) {
    // A database that has been serving build 5 since before any of this existed. Its schema is
    // already at v5, so record that rather than replaying MIGRATION_LEGACY_BASE, whose ALTERs
    // would fail on "duplicate column name" and abort the batch.
    await db.prepare('INSERT OR IGNORE INTO schema_version (v, name, applied_at) VALUES (?, ?, ?)')
      .bind(MIGRATION_LEGACY_BASE.v, 'legacy_base_adopted', Date.now()).run();
    at = MIGRATION_LEGACY_BASE.v;
  }

  for (const m of MIGRATIONS.filter((m) => m.v > at)) {
    try {
      await db.batch([
        ...m.sql.map((s) => db.prepare(s)),
        db.prepare('INSERT INTO schema_version (v, name, applied_at) VALUES (?, ?, ?)')
          .bind(m.v, m.name, Date.now()),
      ]);
    } catch (e) {
      // Deliberately NOT swallowed. A migration that cannot land leaves the panel in a state the
      // operator has to know about, and "Repair" in the app re-runs this and shows the message.
      lastMigrationError = `v${m.v} ${m.name}: ${e && e.message ? e.message : String(e)}`;
      throw e;
    }
  }

  lastMigrationError = null;
  migrationsDone = true;
}

/** Whether this database was created by build 5 or earlier. */
async function legacySchemaExists(db) {
  try {
    const t = await db.prepare(
      `SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'users'`
    ).first();
    return !!t;
  } catch (e) {
    return false;
  }
}
