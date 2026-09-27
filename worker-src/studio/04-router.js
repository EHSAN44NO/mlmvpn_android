/**
 * The relay setting, read at most once a minute per isolate.
 *
 * It was read from D1 on every tunnel connection, before the 101 -- a row that changes about once a
 * year, paid for by every site a person loads. A D1 error keeps the last value rather than falling
 * back to the public relay, which would silently change where somebody's traffic goes.
 */
const PROXY_IP_CACHE = { at: 0, value: null };
async function studioProxyIp(env) {
  const now = Date.now();
  if (PROXY_IP_CACHE.at && now - PROXY_IP_CACHE.at < 60000) return PROXY_IP_CACHE.value;
  let value = DEFAULT_RELAY;
  try {
    const row = await env.DB.prepare("SELECT value FROM settings WHERE key = 'proxy_ip'").first();
    if (row && row.value) value = row.value;
  } catch (e) {
    if (PROXY_IP_CACHE.at) return PROXY_IP_CACHE.value;
  }
  PROXY_IP_CACHE.at = now;
  PROXY_IP_CACHE.value = value;
  return value;
}

const Router = {
  isWebSocketUpgrade(request) {
    const upgradeHeader = (request.headers.get('Upgrade') || '').toLowerCase();
    return upgradeHeader === 'websocket';
  },

  isSubscriptionPath(pathname) {
    return pathname.startsWith('/sub/') || pathname.startsWith('/feed/');
  },

  async handleWebSocket(request, env, ctx) {
    try {
      const mockStoredData = { proxy_ip: await studioProxyIp(env) };
      // The request goes through so the admission check can hash the device and the address, and
      // so early data in `Sec-WebSocket-Protocol` can be read. It is the only thing here that knows.
      return handleWsTunnel(env, mockStoredData, ctx, request);
    } catch (e) {
      return new Response("Internal Server Error", { status: 500 });
    }
  },

  async handleTransport(request, env, ctx) {
    try {
      return await handleXHTTP(request, env, { proxy_ip: await studioProxyIp(env) }, ctx);
    } catch (e) {
      // پاسخ خنثی تا رفتار مانند یک وب‌سرور عادی بماند
      return new Response("Bad Request", { status: 400 });
    }
  },

  async handleSubscription(url, env) {
    const isSubPath = url.pathname.startsWith('/sub/');
    const offset = isSubPath ? 5 : 6;
    let subUser = decodeURIComponent(url.pathname.slice(offset));
    const host = url.hostname;

    const isJson = !isSubPath && subUser.startsWith('json/');
    if (isJson) {
      subUser = subUser.slice(5);
    }

    try {
      const user = await env.DB.prepare("SELECT * FROM users WHERE username = ? OR uuid = ?").bind(subUser, subUser).first();
      if (!user || user.connection_type !== atob('dmxlc3M=')) {
        return new Response("Not Found", { status: 404 });
      }

      if (isJson) {
        return await SubscriptionService.generateJson(user, host, env);
      } else {
        return await SubscriptionService.generateText(user, host);
      }
    } catch (err) {
      return new Response("Error building config: " + err.message, { status: 500 });
    }
  },

  async handlePanel(request, env) {
    const hasPassword = await DbService.getAdminHash(env);
    if (!hasPassword) {
      return new Response(HTML_TEMPLATES.setup, {
        headers: { "Content-Type": "text/html; charset=utf-8" }
      });
    }

    const authorized = await DbService.verifyApiAuth(request, env);
    if (!authorized) {
      return new Response(HTML_TEMPLATES.login, {
        headers: { "Content-Type": "text/html; charset=utf-8" }
      });
    }

    return new Response(HTML_TEMPLATES.panel, {
      headers: { "Content-Type": "text/html; charset=utf-8" }
    });
  },

  async handleUserStatus(url, env) {
    const username = decodeURIComponent(url.pathname.slice(8));
    if (!username) {
      return new Response("Username is required", { status: 400 });
    }
    try {
      const user = await env.DB.prepare("SELECT * FROM users WHERE username = ? OR uuid = ?").bind(username, username).first();
      if (!user) {
        return new Response("User not found", { status: 404 });
      }
      const userJson = JSON.stringify({
        username: user.username,
        uuid: user.uuid,
        limit_gb: user.limit_gb,
        daily_limit_gb: user.daily_limit_gb,
        daily_used_gb: user.daily_used_gb,
        expiry_days: user.expiry_days,
        used_gb: user.used_gb,
        is_active: user.is_active,
        created_at: user.created_at,
        tls: user.tls,
        port: user.port,
        ips: user.ips,
        proxy_ip: user.proxy_ip || '',
        fingerprint: user.fingerprint || 'chrome'
      });
      const html = HTML_TEMPLATES.status.replace(
        "/* {{USER_DATA_PLACEHOLDER}} */",
        `window.statusUser = ${userJson};`
      );
      return new Response(html, {
        headers: { "Content-Type": "text/html; charset=utf-8" }
      });
    } catch (err) {
      return new Response("Error: " + err.message, { status: 500 });
    }
  },

  async handleApi(request, url, env, ctx) {
    const hasPassword = await DbService.getAdminHash(env);

    // API: تعریف رمز عبور اولیه
    if (url.pathname === '/api/setup-password' && request.method === 'POST') {
      if (hasPassword) {
        return new Response(JSON.stringify({ error: "رمز عبور از قبل تعریف شده است" }), {
          status: 400, headers: { "Content-Type": "application/json; charset=utf-8" }
        });
      }
      const { password } = await request.json();
      if (!password || password.length < 4) {
        return new Response(JSON.stringify({ error: "رمز عبور باید حداقل ۴ کاراکتر باشد" }), {
          status: 400, headers: { "Content-Type": "application/json; charset=utf-8" }
        });
      }
      const hashed = await DbService.sha256(password);
      await DbService.setPanelPassword(env.DB, hashed);
      return new Response(JSON.stringify({ success: true }), {
        headers: {
          "Content-Type": "application/json; charset=utf-8",
          "Set-Cookie": "panel_session=" + hashed + "; Path=/; HttpOnly; Secure; SameSite=Lax"
        }
      });
    }

    // API: ورود به پنل
    if (url.pathname === '/api/login' && request.method === 'POST') {
      const { password } = await request.json();
      const hashedInput = await DbService.sha256(password);
      const storedHash = await DbService.getAdminHash(env);
      if (storedHash === hashedInput) {
        return new Response(JSON.stringify({ success: true }), {
          headers: {
            "Content-Type": "application/json; charset=utf-8",
            "Set-Cookie": "panel_session=" + storedHash + "; Path=/; HttpOnly; Secure; SameSite=Lax"
          }
        });
      }
      return new Response(JSON.stringify({ error: "رمز عبور اشتباه است" }), {
        status: 401, headers: { "Content-Type": "application/json; charset=utf-8" }
      });
    }

    // API: خروج از پنل
    if (url.pathname === '/api/logout' && request.method === 'POST') {
      return new Response(JSON.stringify({ success: true }), {
        headers: {
          "Content-Type": "application/json; charset=utf-8",
          "Set-Cookie": "panel_session=; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT; HttpOnly; Secure; SameSite=Lax"
        }
      });
    }

    // بررسی عمومی احراز هویت برای بقیه APIها
    const authorized = await DbService.verifyApiAuth(request, env);
    if (!authorized) {
      // Nothing about the expected credential goes in this body. It used to return
      // `expected: <the admin hash>`, and that hash IS the session cookie -- verifyApiAuth
      // compares the cookie to it directly -- so every unauthenticated request was answered
      // with a working key to the panel. Anyone who knew the worker URL had full admin.
      return new Response(JSON.stringify({ error: "Unauthorized" }), {
        status: 401, headers: { "Content-Type": "application/json; charset=utf-8" }
      });
    }

    // API: لاگ تشخیصی (فقط برای مدیر) — مشاهده و پاک‌سازی
    if (url.pathname === '/api/logs') {
      if (request.method === 'DELETE') {
        try { await env.DB.prepare("DELETE FROM debug_logs").run(); } catch (e) { }
        return new Response(JSON.stringify({ success: true }), { headers: { "Content-Type": "application/json" } });
      }
      let lines = [];
      try {
        const { results } = await env.DB.prepare("SELECT ts, line FROM debug_logs ORDER BY id DESC LIMIT 400").all();
        lines = (results || []).map(r => new Date(r.ts).toISOString().replace('T', ' ').replace('Z', '') + '  ' + r.line);
      } catch (e) { lines = ['(no logs / table empty)']; }
      const dbgOn = (env.DEBUG === '1');
      const header = 'DEBUG=' + (dbgOn ? 'ON' : 'OFF') + '  |  ' + lines.length + ' lines  |  newest first\n' +
        (dbgOn ? '' : '⚠ DEBUG غیرفعال است. برای ثبت لاگ، متغیر DEBUG=1 را در تنظیمات ورکر ست کن.\n') +
        '────────────────────────────────────────\n';
      return new Response(header + lines.join('\n'), {
        headers: { "Content-Type": "text/plain; charset=utf-8", "Cache-Control": "no-store" }
      });
    }

    // API: تغییر رمز عبور مدیریت
    if (url.pathname === '/api/change-password' && request.method === 'POST') {
      const { current_password, new_password } = await request.json();
      if (!current_password || !new_password) {
        return new Response(JSON.stringify({ error: "رمز عبور فعلی و جدید الزامی هستند" }), {
          status: 400, headers: { "Content-Type": "application/json; charset=utf-8" }
        });
      }
      const currentHash = await DbService.sha256(current_password);
      const storedHash = await DbService.getAdminHash(env);
      if (storedHash && storedHash !== currentHash) {
        return new Response(JSON.stringify({ error: "رمز عبور فعلی اشتباه است" }), {
          status: 401, headers: { "Content-Type": "application/json; charset=utf-8" }
        });
      }
      if (new_password.length < 4) {
        return new Response(JSON.stringify({ error: "رمز عبور جدید باید حداقل ۴ کاراکتر باشد" }), {
          status: 400, headers: { "Content-Type": "application/json; charset=utf-8" }
        });
      }
      const newHash = await DbService.sha256(new_password);
      await DbService.setPanelPassword(env.DB, newHash);
      return new Response(JSON.stringify({ success: true }), {
        headers: {
          "Content-Type": "application/json; charset=utf-8",
          "Set-Cookie": "panel_session=" + newHash + "; Path=/; HttpOnly; Secure; SameSite=Lax"
        }
      });
    }

    // API: دریافت موقعیت‌های جغرافیایی کلودفلر
    if (url.pathname === '/locations') {
      try {
        const response = await fetch('https://speed.cloudflare.com/locations', {
          headers: { 'Referer': 'https://speed.cloudflare.com/' }
        });
        const data = await response.json();
        return new Response(JSON.stringify(data), {
          headers: { "Content-Type": "application/json; charset=utf-8", "Access-Control-Allow-Origin": "*" }
        });
      } catch (e) {
        return new Response(JSON.stringify({ error: e.message }), { status: 500, headers: { "Content-Type": "application/json" } });
      }
    }

    // API: تنظیمات آی‌پی پروکسی (GET & POST)
    if (url.pathname === '/api/proxy-ip') {
      if (request.method === 'POST') {
        const { proxy_ip, iata, frag_len, frag_int } = await request.json();
        if (proxy_ip !== undefined) await env.DB.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES ('proxy_ip', ?)").bind(proxy_ip).run();
        if (iata !== undefined) await env.DB.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES ('proxy_location_iata', ?)").bind(iata).run();
        if (frag_len !== undefined) await env.DB.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES ('frag_len', ?)").bind(frag_len).run();
        if (frag_int !== undefined) await env.DB.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES ('frag_int', ?)").bind(frag_int).run();
        studioInvalidateHotCaches();
        return new Response(JSON.stringify({ success: true }), { headers: { "Content-Type": "application/json" } });
      }

      if (request.method === 'GET') {
        const rowIp = await env.DB.prepare("SELECT value FROM settings WHERE key = 'proxy_ip'").first();
        const rowIata = await env.DB.prepare("SELECT value FROM settings WHERE key = 'proxy_location_iata'").first();
        const rowLen = await env.DB.prepare("SELECT value FROM settings WHERE key = 'frag_len'").first();
        const rowInt = await env.DB.prepare("SELECT value FROM settings WHERE key = 'frag_int'").first();
        return new Response(JSON.stringify({
          proxy_ip: rowIp ? rowIp.value : DEFAULT_RELAY,
          iata: rowIata ? rowIata.value : "",
          frag_len: rowLen ? rowLen.value : "20-30",
          frag_int: rowInt ? rowInt.value : "1-2"
        }), { headers: { "Content-Type": "application/json" } });
      }
    }

    // API: مدیریت کاربران
    if (url.pathname.startsWith('/api/users')) {
      const pathParts = url.pathname.split('/');
      const isUserAction = pathParts.length > 3; // /api/users/username

      if (isUserAction) {
        const username = decodeURIComponent(pathParts.pop());

        if (request.method === 'PUT') {
          const body = await request.json();
          if (body.toggle_only !== undefined) {
            await env.DB.prepare(
              "UPDATE users SET is_active = CASE WHEN is_active = 1 THEN 0 ELSE 1 END WHERE username = ?"
            ).bind(username).run();
            // Config Studio syncs on `updated_at`; a switch that left it alone was never seen there.
            try {
              await env.DB.prepare("UPDATE users SET updated_at = ? WHERE username = ?").bind(Date.now(), username).run();
            } catch (e) { /* a build-5 schema has no updated_at */ }
            studioInvalidateHotCaches();
            return new Response(JSON.stringify({ success: true }), { headers: { "Content-Type": "application/json" } });
          } else {
            const { limit_gb, daily_limit_gb, expiry_days, ips, tls, port, fingerprint, proxy_ip } = body;
            // Every value is coerced past `undefined` before it reaches bind(). D1 throws on an
            // undefined parameter, the throw escapes the handler, and the caller gets a bare 503
            // with nothing to go on -- so a PUT that merely omits a field looked like the whole
            // worker was down. Missing now means "leave it empty", which is what the panel's own
            // edit form intends when it clears a box.
            const row = await env.DB.prepare("SELECT tls, port FROM users WHERE username = ?").bind(username).first();
            if (!row) {
              return new Response(JSON.stringify({ error: "user not found" }), { status: 404, headers: { "Content-Type": "application/json" } });
            }
            await env.DB.prepare(
              "UPDATE users SET limit_gb = ?, daily_limit_gb = ?, expiry_days = ?, ips = ?, tls = ?, port = ?, fingerprint = ?, proxy_ip = ? WHERE username = ?"
            ).bind(
              limit_gb ? parseFloat(limit_gb) : null,
              daily_limit_gb ? parseFloat(daily_limit_gb) : null,
              expiry_days ? parseInt(expiry_days) : null,
              ips || null,
              // tls and port decide whether the config is even usable, so an omitted one keeps
              // what the user already had rather than being nulled into an unusable state.
              tls === undefined || tls === null ? row.tls : tls,
              port === undefined || port === null ? row.port : port,
              fingerprint || 'chrome',
              proxy_ip === 'none' ? 'none' : (proxy_ip || null),
              username
            ).run();
            // The byte columns follow the GB ones. Since build 18 admission reads the bytes, so an
            // edit made here that left them behind would be shown and not enforced.
            //
            // `expires_at` is deliberately NOT derived from `expiry_days` here. Where Config Studio
            // has migrated the database, `expiry_days` is the days LEFT at the last renewal, not
            // days from creation, and reading it this panel's way would end a renewed person's
            // term in the past; the app sends such an installation's user list to Config Studio.
            // Where it has not, the column does not exist and the data plane counts from
            // `created_at`, which is exactly what this panel means.
            try {
              await env.DB.prepare(
                "UPDATE users SET quota_bytes = CASE WHEN limit_gb > 0 THEN CAST(limit_gb * 1073741824 AS INTEGER) ELSE NULL END, " +
                "daily_quota_bytes = CASE WHEN daily_limit_gb > 0 THEN CAST(daily_limit_gb * 1073741824 AS INTEGER) ELSE NULL END, " +
                "updated_at = ? WHERE username = ?"
              ).bind(Date.now(), username).run();
            } catch (e) { /* a build-5 schema has no byte columns, and needs none */ }
            studioInvalidateHotCaches();
            return new Response(JSON.stringify({ success: true }), { headers: { "Content-Type": "application/json" } });
          }
        }

        if (request.method === 'DELETE') {
          await env.DB.prepare("DELETE FROM users WHERE username = ?").bind(username).run();
          studioInvalidateHotCaches();
          return new Response(JSON.stringify({ success: true }), { headers: { "Content-Type": "application/json" } });
        }
      } else {
        if (request.method === 'GET') {
          try {
            await flushExpiredTraffic(env);
          } catch (e) { }
          // Without the people Config Studio deleted: it keeps their row (with the uuid cleared)
          // so its own sync can say they are gone, and this list used to show them as users.
          let results;
          try {
            ({ results } = await env.DB.prepare("SELECT * FROM users WHERE deleted_at IS NULL ORDER BY id DESC").all());
          } catch (e) {
            ({ results } = await env.DB.prepare("SELECT * FROM users ORDER BY id DESC").all());
          }
          const now = Date.now();
          // The window Config Studio uses. Since build 18 `last_active` moves with each usage
          // flush (about every 90 s), so the old 65-second window showed connected people offline.
          const enrichedUsers = (results || []).map(user => ({
            ...user,
            is_online: (user.last_active && (now - user.last_active) < STUDIO_ONLINE_WINDOW_MS) ? 1 : 0
          }));
          return new Response(JSON.stringify({ users: enrichedUsers, serverTime: now }), {
            headers: {
              "Content-Type": "application/json",
              "Cache-Control": "no-store, no-cache, must-revalidate, max-age=0"
            }
          });
        }

        if (request.method === 'POST') {
          const { username, limit_gb, daily_limit_gb, expiry_days, ips, tls, port, fingerprint, proxy_ip } = await request.json();
          if (!username) {
            return new Response(JSON.stringify({ error: "نام کاربری اجباری است" }), { status: 400, headers: { "Content-Type": "application/json" } });
          }
          const uuid = crypto.randomUUID();
          try {
            await env.DB.prepare(
              "INSERT INTO users (username, uuid, limit_gb, daily_limit_gb, expiry_days, ips, connection_type, tls, port, fingerprint, proxy_ip, daily_reset_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            ).bind(
              username,
              uuid,
              limit_gb ? parseFloat(limit_gb) : null,
              daily_limit_gb ? parseFloat(daily_limit_gb) : null,
              expiry_days ? parseInt(expiry_days) : null,
              ips || null,
              atob('dmxlc3M='),
              tls,
              port,
              fingerprint || 'chrome',
              proxy_ip === 'none' ? 'none' : (proxy_ip || null),
              Date.now()
            ).run();
            // A uid, the id everything since build 6 keys on. Without one the data plane had no
            // stable way to find this row again, and a session on it was dropped after a minute.
            try {
              await env.DB.prepare(
                "UPDATE users SET uid = lower(hex(randomblob(12))), updated_at = ?, " +
                "quota_bytes = CASE WHEN limit_gb > 0 THEN CAST(limit_gb * 1073741824 AS INTEGER) ELSE NULL END, " +
                "daily_quota_bytes = CASE WHEN daily_limit_gb > 0 THEN CAST(daily_limit_gb * 1073741824 AS INTEGER) ELSE NULL END, " +
                "expires_at = " + LEGACY_EXPIRES_AT_SQL + " " +
                "WHERE username = ? AND (uid IS NULL OR uid = '')"
              ).bind(Date.now(), username).run();
            } catch (e) { /* a build-5 schema has no uid column */ }
            return new Response(JSON.stringify({ success: true }), { headers: { "Content-Type": "application/json" } });
          } catch (err) {
            let errorMsg = err.message;
            if (errorMsg.includes("UNIQUE constraint failed")) {
              errorMsg = "این نام کاربری از قبل وجود دارد.";
            }
            return new Response(JSON.stringify({ error: errorMsg }), { status: 500, headers: { "Content-Type": "application/json" } });
          }
        }
      }
    }

    return new Response(JSON.stringify({ error: "Not Found" }), { status: 404 });
  }
};

/**
 * `expires_at` from the legacy panel's `expiry_days`, which it counts from creation -- the formula
 * migration 6 used to fill the column in. A row with no readable `created_at` counts from now
 * rather than being left without an end.
 */
const LEGACY_EXPIRES_AT_SQL =
  "CASE WHEN expiry_days > 0 THEN (COALESCE(CAST(strftime('%s', created_at) AS INTEGER), CAST(strftime('%s', 'now') AS INTEGER)) * 1000) + (expiry_days * 86400000) ELSE NULL END";

// ==========================================================
// ۵. مدیریت دیتابیس و اعتبارسنجی (DATABASE SERVICE)
// ==========================================================
