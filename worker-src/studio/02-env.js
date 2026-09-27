function LOG() { }

// لاگ تشخیصی امن: فقط وقتی متغیر DEBUG=1 باشد، در دیتابیس D1 (خصوصی) نوشته می‌شود.
// هیچ‌چیز به سیستم لاگ/observability کلادفلر نمی‌رود؛ از طریق /api/logs در پنل دیده می‌شود.
function dbg(env, ctx, line) {
  try {
    if (!env || env.DEBUG !== '1' || !env.DB) return;
    const task = env.DB.prepare("INSERT INTO debug_logs (ts, line) VALUES (?, ?)").bind(Date.now(), String(line)).run();
    if (ctx && ctx.waitUntil) ctx.waitUntil(task.catch(() => { })); else task.catch(() => { });
  } catch (e) { }
}

function isD1Binding(value) {
  return value && typeof value === 'object'
    && typeof value.prepare === 'function'
    && (typeof value.batch === 'function' || typeof value.exec === 'function');
}

function resolveDatabaseBinding(env) {
  if (!env || typeof env !== 'object') return;
  if (isD1Binding(env.DB)) return;
  for (const key of Object.keys(env)) {
    if (isD1Binding(env[key])) {
      try { env.DB = env[key]; } catch (e) { }
      return;
    }
  }
}

// ==========================================================
// ۳. نقطه ورود اصلی ورکر (MAIN FETCH HANDLER)
// ==========================================================
