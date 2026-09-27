let schemaEnsured = false;

// Set once per isolate by DbService.seedAdminHash. See its comment: this is plan R3 step one.
let adminHashSeeded = false;
let cachedPanelPassword = null;

let schemaEnsuring = null;

const DbService = {
  /**
   * One run per isolate, shared. A cold isolate used to run these ten statements once for EACH
   * request that arrived before the first run finished -- and a client that has just connected sends
   * a burst of them, every one waiting on the same schema work in series.
   */
  async ensureSchema(db) {
    if (schemaEnsured) return;
    if (!schemaEnsuring) schemaEnsuring = this.ensureSchemaOnce(db).finally(() => { schemaEnsuring = null; });
    await schemaEnsuring;
  },

  async ensureSchemaOnce(db) {
    if (schemaEnsured) return;
    try {
      await db.prepare(`
        CREATE TABLE IF NOT EXISTS users (
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
          created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )
      `).run();
    } catch (e) { }
    try { await db.prepare("ALTER TABLE users ADD COLUMN is_active INTEGER DEFAULT 1").run(); } catch (e) { }
    try { await db.prepare("ALTER TABLE users ADD COLUMN last_active INTEGER").run(); } catch (e) { }
    try { await db.prepare("ALTER TABLE users ADD COLUMN fingerprint TEXT DEFAULT 'chrome'").run(); } catch (e) { }
    try { await db.prepare("ALTER TABLE users ADD COLUMN daily_limit_gb REAL").run(); } catch (e) { }
    try { await db.prepare("ALTER TABLE users ADD COLUMN daily_used_gb REAL DEFAULT 0").run(); } catch (e) { }
    try { await db.prepare("ALTER TABLE users ADD COLUMN daily_reset_at INTEGER DEFAULT 0").run(); } catch (e) { }
    try { await db.prepare("ALTER TABLE users ADD COLUMN proxy_ip TEXT").run(); } catch (e) { }
    try { await db.prepare("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT)").run(); } catch (e) { }
    try { await db.prepare("CREATE TABLE IF NOT EXISTS debug_logs (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, line TEXT)").run(); } catch (e) { }
    schemaEnsured = true;
  },

  /**
   * One row out of `settings`, by key.
   *
   * `settings` has been a two-column key/value table since build 1, but every reader so far has
   * been a purpose-built function for one key (`getPanelPassword`). Config Studio needs a handful
   * of small installation-scoped values, and eight more one-key functions is eight more places for
   * the same three lines to go subtly wrong.
   */
  async getSetting(db, key) {
    try {
      const row = await db.prepare('SELECT value FROM settings WHERE key = ?').bind(key).first();
      return row ? row.value : null;
    } catch (e) {
      return null;
    }
  },

  async setSetting(db, key, value) {
    await db.prepare('INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)').bind(key, value).run();
  },

  async getPanelPassword(db) {
    if (cachedPanelPassword !== null) return cachedPanelPassword;
    try {
      const row = await db.prepare("SELECT value FROM settings WHERE key = 'panel_password'").first();
      cachedPanelPassword = row ? row.value : "";
      return cachedPanelPassword || null;
    } catch (e) {
      return null;
    }
  },

  async setPanelPassword(db, password) {
    await db.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES ('panel_password', ?)").bind(password).run();
    cachedPanelPassword = password;
  },

  /**
   * Copy the binding password into the database, once, if the database has none.
   *
   * Step one of the ordering in plan R3, and it has to land **before** the deployer stops shipping
   * `ADMIN_PASSWORD`. Today every worker this app has ever deployed carries that binding as
   * `plain_text`, and `getAdminHash` prefers it over the database -- which is also why
   * `/api/change-password` has been a silent no-op on those installs, so most of them have **no**
   * stored password at all.
   *
   * Remove the binding without doing this first and `getAdminHash` falls through to a database that
   * was never written, `verifyApiAuth` finds no hash, and the panel opens to anyone who knows the
   * URL -- on every installation at once. That is the single step in this whole migration that can
   * leak, so it is done first and separately.
   *
   * Isolate-local flag, and API paths only: the data plane has no business writing settings rows.
   */
  async seedAdminHash(env) {
    if (adminHashSeeded) return;
    adminHashSeeded = true;
    if (!env || !env.ADMIN_PASSWORD) return;
    try {
      if (await this.getPanelPassword(env.DB)) return;
      await this.setPanelPassword(env.DB, await this.sha256(String(env.ADMIN_PASSWORD)));
    } catch (e) { }
  },

  async verifyApiAuth(request, env) {
    await this.seedAdminHash(env);
    const storedPasswordHash = await this.getAdminHash(env);
    // FAIL CLOSED. This used to `return true` when nothing was stored, on the reasoning that a
    // panel with no password set yet should be reachable to set one -- but the route that sets it,
    // `/api/setup-password`, guards itself on the same condition, and `handlePanel` shows the setup
    // screen before ever calling this. So the open branch protected nothing and exposed every other
    // endpoint: users, configs, traffic, the lot.
    if (!storedPasswordHash) return false;
    const cookies = request.headers.get('Cookie') || '';
    const sessionCookie = cookies.split(';').find(c => c.trim().startsWith('panel_session='));
    if (!sessionCookie) return false;
    const sessionToken = sessionCookie.split('=')[1].trim();
    return sessionToken === storedPasswordHash;
  },

  // هش رمز مدیریت: اولویت با Secret ورکر (ADMIN_PASSWORD)، در غیر این صورت دیتابیس
  async getAdminHash(env) {
    if (env && env.ADMIN_PASSWORD) return await this.sha256(String(env.ADMIN_PASSWORD));
    return await this.getPanelPassword(env.DB);
  },

  async sha256(message) {
    const msgBuffer = new TextEncoder().encode(message);
    const hashBuffer = await crypto.subtle.digest('SHA-256', msgBuffer);
    const hashArray = Array.from(new Uint8Array(hashBuffer));
    return hashArray.map(b => b.toString(16).padStart(2, '0')).join('');
  }
};

// ==========================================================
// ۶. مدیریت تولید کانفیگ‌ها (SUBSCRIPTION SERVICE)
// ==========================================================
