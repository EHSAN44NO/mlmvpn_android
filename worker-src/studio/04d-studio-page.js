// ==========================================================
// Config Studio  —  the page a person opens: /p/{token}
// ==========================================================
//
// The second half of "what the person on the other end sees". The headers (04c) put volume and
// expiry inside their VPN client; this is for everything a header cannot carry, and for the moments
// when something has stopped working and they want to know why.
//
// The old status page had three problems, and all three are why this is a rewrite rather than an
// edit. Its daily bar was fed by `daily_used_gb`, which never accrued on WebSocket, so it sat at
// zero forever. Its URL was `/status/{username}`, which leaked the name and could not be revoked.
// And it said nothing about devices at all.
//
// Two rules govern the writing:
//   * **State things in words, with the reason.** "غیرفعال" alone sends someone to ask a question
//     that the page already had the answer to.
//   * **Never draw a number this installation cannot actually measure.** Device *counting* needs the
//     Durable Object, which is Phase 3 — so the allowance is shown and the count is not invented.

/** Latin digits to Persian, so the page does not mix numerals mid-sentence. */
function studioFa(value) {
  return String(value).replace(/[0-9]/g, (d) => '۰۱۲۳۴۵۶۷۸۹'[Number(d)]);
}

function studioEscape(s) {
  return String(s === null || s === undefined ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

/**
 * Bytes in the unit that fits.
 *
 * MB below a gigabyte, and one decimal above it. Showing "0.03 GB" to someone who has used 30
 * megabytes is technically true and reads as nothing at all.
 */
function studioBytes(n) {
  // The same rule the operator's app uses (StudioDashboardScreen.kt › bytesParts), so the person
  // and the operator never read one figure two ways: rounded, two decimals under 10 GB, one under
  // 100, none above; megabytes with one decimal under 10.
  const b = Number(n) || 0;
  if (b <= 0) return '۰';
  const trim = (s) => (s.indexOf('.') >= 0 ? s.replace(/0+$/, '').replace(/\.$/, '') : s);
  const gb = b / 1073741824;
  if (gb >= 1) return studioFa(trim(gb.toFixed(gb >= 100 ? 0 : gb >= 10 ? 1 : 2))) + ' گیگابایت';
  const mb = b / 1048576;
  if (mb >= 1) return studioFa(trim(mb.toFixed(mb >= 10 ? 0 : 1))) + ' مگابایت';
  return studioFa(String(Math.max(1, Math.round(b / 1024)))) + ' کیلوبایت';
}

function studioFaDate(ms) {
  try {
    return studioFa(new Intl.DateTimeFormat('fa-IR', {
      year: 'numeric', month: 'long', day: 'numeric',
    }).format(new Date(ms)));
  } catch (e) {
    return studioFa(new Date(ms).toISOString().slice(0, 10));
  }
}

/**
 * How long is left, in the largest unit that is still honest: the nearest whole day from a day up
 * -- so a 30-day subscription reads 30 when it is made, not 29 -- whole hours under a day, minutes
 * under an hour. The operator's app counts the same way (StudioDashboardScreen.kt › timeLeftText).
 */
function studioRemainingText(ms) {
  const left = ms - Date.now();
  if (left <= 0) return null;
  if (left >= 86400000) return studioFa(Math.round(left / 86400000)) + ' روز';
  const hours = Math.floor(left / 3600000);
  if (hours >= 1) return studioFa(hours) + ' ساعت';
  return studioFa(Math.max(1, Math.floor(left / 60000))) + ' دقیقه';
}

/**
 * Status, and the reason for it, in words.
 *
 * Ordered by what the person can do about it: an account someone turned off is a different
 * conversation from one that ran out of volume, and both are different from one that expired.
 */
function studioStatusOf(user) {
  if (user.status === 'disabled' || user.is_active === 0) {
    return { tone: 'off', label: 'غیرفعال', reason: 'این اشتراک موقتاً غیرفعال شده است.' };
  }
  if (user.expires_at && Date.now() > user.expires_at) {
    return { tone: 'off', label: 'منقضی شده', reason: 'مدت این اشتراک به پایان رسیده است.' };
  }
  if (user.quota_bytes && (user.used_bytes || 0) >= user.quota_bytes) {
    return { tone: 'off', label: 'حجم تمام شده', reason: 'همهٔ حجم این اشتراک مصرف شده است.' };
  }
  return { tone: 'on', label: 'فعال', reason: null };
}

function studioResetText(policy) {
  switch (policy) {
    case 'daily': return 'حجم روزانه هر شب صفر می‌شود.';
    case 'weekly': return 'حجم هر هفته صفر می‌شود.';
    case 'monthly': return 'حجم هر ماه صفر می‌شود.';
    default: return null;
  }
}

/**
 * The links this subscription is currently handing over, grouped by config.
 *
 * The SAME derivation the subscription itself uses (`studioFanOut`), rather than a second query
 * that counts configs and endpoints separately. That second query is what this function used to
 * be, and it was a figure nobody could check against the thing it described — the page said "six
 * servers" and the client showed whatever it showed.
 *
 * A per-person `ips` override is honoured here exactly as it is there, because the shared helper
 * is the one that honours it.
 */
async function studioPageLinks(env, user, token, url) {
  try {
    return await studioFanOut(env, user, token, url);
  } catch (e) {
    return { groups: [], endpointCount: 0 };
  }
}

/**
 * Live devices, when this installation can actually count them.
 *
 * Null rather than zero where the Durable Object is absent, and the page draws nothing at all in
 * that case. "۰ دستگاه متصل" on an installation that cannot count is a number that is always wrong
 * and always alarming — and the person reading it has no way to tell it apart from a real zero.
 */
async function studioPageDevices(env, uid) {
  if (!env.SESSIONS) return null;
  try {
    const stub = env.SESSIONS.get(env.SESSIONS.idFromName('u:' + uid));
    const res = await stub.fetch('https://do/state', { method: 'POST', body: '{}' });
    const body = await res.json();
    return { live: Number(body.live) || 0, known: Number(body.device_count) || 0 };
  } catch (e) {
    return null;
  }
}

async function studioServePage(env, token, url) {
  const sub = await env.DB.prepare(
    'SELECT id, user_uid, token, revoked_at FROM subscriptions WHERE token = ?'
  ).bind(token).first();
  if (!sub || sub.revoked_at) return new Response('Not Found', { status: 404 });

  const user = await env.DB.prepare(
    'SELECT uid, username, status, is_active, deleted_at, quota_bytes, used_bytes, ' +
    'daily_quota_bytes, daily_used_bytes, quota_reset_policy, expires_at, expiry_mode, ' +
    'activation_days, first_connect_at, device_limit, conn_limit, ips, last_active ' +
    'FROM users WHERE uid = ?'
  ).bind(sub.user_uid).first();
  if (!user || user.deleted_at) return new Response('Not Found', { status: 404 });

  const contact = await DbService.getSetting(env.DB, 'studio_contact');
  const brand = (await DbService.getSetting(env.DB, 'studio_brand')) || 'اشتراک شما';
  const fan = await studioPageLinks(env, user, sub.token, url);
  const devices = await studioPageDevices(env, user.uid);

  return new Response(studioRenderPage(user, sub, url, contact, brand, fan, devices), {
    status: 200,
    headers: { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' },
  });
}

/**
 * "How long ago", in the largest unit that is still honest.
 *
 * Minutes below an hour and hours below a day, because "۰ روز پیش" is what a person reads five
 * minutes after they disconnected, and it tells them nothing.
 */
function studioAgoText(ms) {
  if (!ms) return null;
  const d = Date.now() - ms;
  if (d < 0) return null;
  const mins = Math.floor(d / 60000);
  if (mins < 2) return 'همین الان';
  if (mins < 60) return studioFa(mins) + ' دقیقه پیش';
  const hours = Math.floor(d / 3600000);
  if (hours < 24) return studioFa(hours) + ' ساعت پیش';
  return studioFa(Math.floor(d / 86400000)) + ' روز پیش';
}

/**
 * The one-tap handover into the apps people actually use.
 *
 * The reason this is on the page rather than in an instruction: "copy this link, open your app,
 * find the subscription screen, paste" is four steps and the place where a handover usually dies.
 * Each scheme below is the one its own app documents; a scheme for an app that is not installed
 * simply does nothing, so offering several costs nothing and rescues whoever has any one of them.
 */
function studioImportLinks(subUrl, username) {
  const enc = encodeURIComponent(subUrl);
  const name = encodeURIComponent(username);
  return [
    { label: 'v2rayNG', href: 'v2rayng://install-sub?url=' + enc + '&name=' + name },
    { label: 'Hiddify', href: 'hiddify://install-sub?url=' + enc + '&name=' + name },
    { label: 'sing-box', href: 'sing-box://import-remote-profile?url=' + enc + '#' + name },
    { label: 'Clash', href: 'clash://install-config?url=' + enc + '&name=' + name },
    { label: 'Streisand', href: 'streisand://import/' + enc },
    { label: 'Shadowrocket', href: 'sub://' + studioB64Ascii(subUrl) },
  ];
}

function studioRenderPage(user, sub, url, contact, brand, fan, devices) {
  const st = studioStatusOf(user);
  // The link this page hands over -- and deliberately NOT the bare `/s/{token}` the page may be
  // sitting on right now.
  //
  // `/s/{token}` is one link with two audiences: a client asking for it gets the list, a person
  // tapping it gets this page. That is right for the link the operator sends, and wrong for the
  // link this page offers, for two reasons a person actually hits:
  //
  //  * Somebody who reached this page by opening `/s/{token}` in a browser is being shown, under
  //    the heading «لینک اشتراک», the exact URL already in their address bar. It reads as the
  //    status page's own address, so it does not read as a subscription at all -- and the obvious
  //    conclusion is that they were never given a subscription link.
  //  * The two audiences are told apart by `Accept` and `User-Agent`, which is a guess. It is a
  //    good guess and it is still a guess: an app fetching through a WebView, a system downloader,
  //    or anything that asks for `text/html` behind a borrowed browser agent gets a page of HTML
  //    where its parser expected a list, and tells the person their link is invalid.
  //
  // `?format=base64` is read before any of that guessing (see `studioWantsPage`) and forces the
  // list. So the link this page offers always imports, whoever fetches it and however they do it.
  const subUrl = url.origin + '/s/' + sub.token + '?format=base64';

  // ---- volume ------------------------------------------------------------------
  const used = user.used_bytes || 0;
  const total = user.quota_bytes || 0;
  const pct = total > 0 ? Math.min(100, Math.round((used / total) * 100)) : 0;
  const remaining = total > 0 ? Math.max(0, total - used) : null;
  // The bar turns before the number does. Somebody at ninety per cent has a decision to make and
  // a blue bar does not say so; the colour is the only part of this page read from across a room.
  const volTone = total > 0 && pct >= 90 ? ' low' : (total > 0 && pct >= 75 ? ' warn' : '');

  const volumeBody = total > 0
    ? '<div class="bar' + volTone + '"><span style="width:' + pct + '%"></span></div>' +
      '<div class="pair"><span>مصرف‌شده</span><b>' + studioBytes(used) + '</b></div>' +
      '<div class="pair"><span>باقی‌مانده</span><b class="hi">' + studioBytes(remaining) + '</b></div>' +
      '<div class="pair"><span>کل</span><b>' + studioBytes(total) + '</b></div>'
    : '<div class="pair"><span>مصرف‌شده</span><b>' + studioBytes(used) + '</b></div>' +
      '<div class="note">حجم این اشتراک نامحدود است.</div>';

  // The daily allowance, and only when there is one. It is a separate ceiling from the total and
  // hitting it stops traffic just as hard, so a page that showed only the total would leave
  // somebody staring at eighty gigabytes remaining while nothing connects.
  const dailyTotal = user.daily_quota_bytes || 0;
  const dailyUsed = user.daily_used_bytes || 0;
  const dailyBody = dailyTotal > 0
    ? '<div class="bar' + (dailyUsed >= dailyTotal * 0.9 ? ' low' : '') + '"><span style="width:' +
        Math.min(100, Math.round((dailyUsed / dailyTotal) * 100)) + '%"></span></div>' +
      '<div class="pair"><span>امروز</span><b>' + studioBytes(dailyUsed) +
        ' از ' + studioBytes(dailyTotal) + '</b></div>'
    : '';

  // ---- time --------------------------------------------------------------------
  let timeBody;
  if (user.expiry_mode === 'on_first_connect' && !user.first_connect_at) {
    // Not yet started. Saying "expired" or showing a date would both be wrong.
    timeBody = '<div class="note">با اولین اتصال شروع می‌شود، سپس ' +
      studioFa(user.activation_days || 0) + ' روز اعتبار دارد.</div>';
  } else if (user.expires_at) {
    const left = studioRemainingText(user.expires_at);
    timeBody = '<div class="pair"><span>تاریخ پایان</span><b>' + studioFaDate(user.expires_at) + '</b></div>' +
      (left
        ? '<div class="pair"><span>باقی‌مانده</span><b class="hi">' + left + '</b></div>'
        : '<div class="note">این اشتراک به پایان رسیده است.</div>');
  } else {
    timeBody = '<div class="note">این اشتراک تاریخ پایان ندارد.</div>';
  }

  const reset = studioResetText(user.quota_reset_policy);
  const lastSeen = studioAgoText(user.last_active);

  // ---- devices -----------------------------------------------------------------
  // A real count where the Durable Object exists, and the allowance alone where it does not. The
  // rule is unchanged from the first version of this page: never draw a number this installation
  // cannot measure. What changed is that some installations now can.
  let deviceBody = '';
  if (devices) {
    deviceBody += '<div class="pair"><span>متصل در این لحظه</span><b class="hi">' +
      studioFa(devices.live) + (user.conn_limit ? ' از ' + studioFa(user.conn_limit) : '') + '</b></div>';
    deviceBody += '<div class="pair"><span>دستگاه‌های شناخته‌شده</span><b>' +
      studioFa(devices.known) + (user.device_limit ? ' از ' + studioFa(user.device_limit) : '') + '</b></div>';
  } else if (user.device_limit || user.conn_limit) {
    if (user.device_limit) {
      deviceBody += '<div class="pair"><span>دستگاه‌های مجاز</span><b>تا ' +
        studioFa(user.device_limit) + ' دستگاه</b></div>';
    }
    if (user.conn_limit) {
      deviceBody += '<div class="pair"><span>اتصال هم‌زمان</span><b>تا ' + studioFa(user.conn_limit) + '</b></div>';
    }
  } else {
    deviceBody = '<div class="note">محدودیتی روی تعداد دستگاه‌ها گذاشته نشده است.</div>';
  }
  if (lastSeen) {
    deviceBody += '<div class="pair"><span>آخرین اتصال</span><b>' + lastSeen + '</b></div>';
  }

  // ---- what the link carries ---------------------------------------------------
  //
  // Both figures, because they answer different questions and one alone misleads. **Configs** is
  // how many distinct setups this person was given — usually one, sometimes one per device.
  // **Servers** is how many entries appear in their app, which is configs multiplied by the
  // addresses each is served on. Showing only the second makes one config on six clean addresses
  // read as six subscriptions; showing only the first makes it read as one server that either
  // works or does not.
  const configCount = fan.groups.length;
  const allLinks = fan.groups.reduce((all, g) => all.concat(g.links), []);

  let serverBody;
  if (configCount === 0) {
    // A subscription with no config imports cleanly and then connects to nothing, so this is
    // stated rather than left as an empty section.
    serverBody = '<div class="warnline">هنوز سروری به این اشتراک اضافه نشده است.</div>';
  } else {
    serverBody =
      '<div class="pair"><span>کانفیگ‌ها</span><b>' + studioFa(configCount) + '</b></div>' +
      '<div class="pair"><span>سرورهای داخل لینک</span><b>' + studioFa(allLinks.length) + '</b></div>';

    // Named one by one only when there is more than one to tell apart. A single row repeating the
    // total above it is a row nobody reads.
    // Multi-location: which countries this subscription carries, said once at the top.
    const ccs = [...new Set(fan.groups.map((g) => g.exit_cc).filter(Boolean))];
    if (ccs.length) {
      serverBody += '<div class="pair"><span>مولتی لوکیشن</span><b>' +
        studioEscape(ccs.map(studioCountryLabel).join('، ')) + '</b></div>';
    }

    if (configCount > 1) {
      // A location config is named by its country, flag first -- the same words the person sees
      // in their app, so the two can be matched without guessing.
      serverBody += fan.groups.map((g, i) =>
        '<div class="pair"><span>' + studioEscape(
          g.exit_cc
            ? studioCountryLabel(g.exit_cc) + (g.public ? ' ' + POOL_WARNING_TAG : '')
            : (g.label && !/ · [A-Z]{2}$/.test(g.label) ? g.label : 'بدون لوکیشن')
        ) + '</span><b>' + studioFa(g.links.length) + ' سرور</b></div>'
      ).join('');
    }

    // The links themselves, so a client that cannot take a subscription URL still has a way in --
    // and so does anyone whose app is refusing to refresh. Hidden rather than printed: a wall of
    // `vless://` is what this page was written to replace, and the two hundred characters of one
    // link push everything else off the screen.
    //
    // In the document rather than in a script string, and copied with `textContent`: these links
    // carry a URL-encoded remark and a credential, and hand-escaping them into JavaScript is the
    // kind of quoting bug that produces a link which copies but does not work.
    serverBody +=
      '<div class="cfgs" id="cfgs" hidden>' + studioEscape(allLinks.join('\n')) + '</div>' +
      '<button class="ghost" id="copycfg" type="button" data-src="cfgs" data-done="کپی شد" data-fail="دستی انتخاب کنید">' +
        'کپی کانفیگ‌ها' +
      '</button>' +
      '<div class="note">اگر برنامه‌تان لینک اشتراک را قبول نکرد، این دکمه خودِ کانفیگ‌ها را کپی می‌کند تا دستی وارد کنید. با هر تغییری در اشتراک، لینک بالا خودش بروز می‌شود ولی کانفیگ‌های کپی‌شده نه.</div>' +
      '<div class="note">اگر یکی وصل نشد، سرور بعدی را امتحان کنید — همه‌شان به یک جا می‌رسند.</div>';

    // Public servers (04k) are a stranger's machine. The person using them is told what that
    // means, in so many words, on the page they open to get their link.
    if (fan.groups.some((g) => g.public)) {
      serverBody += '<div class="warnline">' + studioEscape(POOL_WARNING_TEXT) + '</div>';
    }
  }

  const importRow = studioImportLinks(subUrl, user.username)
    .map((a) => '<a class="app" href="' + studioEscape(a.href) + '">' + studioEscape(a.label) + '</a>')
    .join('');

  const contactBlock = contact
    ? '<div class="contact">' + studioEscape(contact) + '</div>'
    : '';

  return '<!DOCTYPE html><html lang="fa" dir="rtl"><head>' +
    '<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">' +
    '<meta name="robots" content="noindex,nofollow">' +
    '<title>' + studioEscape(brand) + '</title>' +
    '<style>' + STUDIO_PAGE_CSS + '</style></head><body>' +
    '<main>' +
      '<header>' +
        '<h1>' + studioEscape(brand) + '</h1>' +
        '<div class="who">' + studioEscape(user.username) + '</div>' +
        '<div class="status ' + st.tone + '">' + st.label + '</div>' +
        (st.reason ? '<p class="reason">' + st.reason + (contact ? ' ' + studioEscape(contact) : '') + '</p>' : '') +
      '</header>' +

      // The link first, and the numbers under it. Somebody opening this on the day they were given
      // it wants to get connected; somebody opening it three weeks later wants to know why it
      // stopped, and that person scrolls. Putting the volume bar first serves the second visit at
      // the cost of the first.
      '<section><h2>لینک اشتراک</h2>' +
        // LTR-pinned. An RTL container reorders an ASCII URL on screen AND in what the person
        // copies -- so a link that looks fine in a screenshot is pasted broken. In something whose
        // only job is to hand over a working link, this is not cosmetic.
        '<div class="link" dir="ltr" id="sub">' + studioEscape(subUrl) + '</div>' +
        '<button id="copy" type="button" data-src="sub" data-done="کپی شد" data-fail="دستی انتخاب کنید">کپی لینک</button>' +
        '<div class="apps-title">یا مستقیم در برنامه باز کنید</div>' +
        '<div class="apps">' + importRow + '</div>' +
        '<div class="note">روی نام برنامه‌ای که دارید بزنید. اگر باز نشد، لینک را کپی کنید و در بخش اشتراک برنامه‌تان بچسبانید.</div>' +
      '</section>' +

      '<section><h2>حجم</h2>' + volumeBody + dailyBody +
        (reset ? '<div class="note">' + reset + '</div>' : '') + '</section>' +

      '<section><h2>زمان</h2>' + timeBody + '</section>' +

      '<section><h2>دستگاه‌ها</h2>' + deviceBody + '</section>' +

      '<section><h2>سرورها</h2>' + serverBody + '</section>' +
      contactBlock +
    '</main>' +
    '<script>' + STUDIO_PAGE_JS + '</script>' +
    '</body></html>';
}

const STUDIO_PAGE_CSS =
  ':root{color-scheme:light dark;--bg:#f2f2f7;--card:#fff;--ink:#1c1c1e;--dim:#8a8a8e;' +
  '--line:rgba(60,60,67,.14);--on:#34c759;--off:#ff3b30;--warn:#ff9f0a;--tint:#0a84ff}' +
  '@media(prefers-color-scheme:dark){:root{--bg:#000;--card:#1c1c1e;--ink:#fff;--dim:#98989d;' +
  '--line:rgba(84,84,88,.5)}}' +
  '*{box-sizing:border-box}' +
  'body{margin:0;background:var(--bg);color:var(--ink);' +
  "font-family:system-ui,-apple-system,'Segoe UI',Vazirmatn,Tahoma,sans-serif;" +
  'font-size:15px;line-height:1.7;padding:20px 14px 40px}' +
  'main{max-width:520px;margin:0 auto}' +
  'header{text-align:center;margin-bottom:22px}' +
  'h1{font-size:20px;margin:0 0 2px}' +
  '.who{color:var(--dim);font-size:13px;margin-bottom:10px}' +
  '.status{display:inline-block;padding:4px 14px;border-radius:999px;font-size:13px;font-weight:600;color:#fff}' +
  '.status.on{background:var(--on)}.status.off{background:var(--off)}' +
  '.reason{color:var(--dim);font-size:13px;margin:10px 0 0}' +
  'section{background:var(--card);border-radius:14px;padding:14px 16px;margin-bottom:12px}' +
  'h2{font-size:13px;color:var(--dim);font-weight:600;margin:0 0 10px}' +
  '.pair{display:flex;justify-content:space-between;align-items:baseline;padding:5px 0;' +
  'border-bottom:1px solid var(--line)}' +
  '.pair:last-child{border-bottom:0}' +
  '.pair span{color:var(--dim);font-size:13px}' +
  '.pair b{font-weight:600;font-size:15px}' +
  '.pair b.hi{color:var(--tint)}' +
  '.bar{height:8px;border-radius:999px;background:var(--line);overflow:hidden;margin:2px 0 10px}' +
  '.bar span{display:block;height:100%;background:var(--tint);border-radius:999px}' +
  '.bar.warn span{background:var(--warn)}.bar.low span{background:var(--off)}' +

  '.note{color:var(--dim);font-size:12.5px;margin-top:8px}' +
  // Hidden until the copy fails, and then it has to be readable: whoever sees this block is
  // someone whose clipboard was refused and who now has to select it by hand.
  '.cfgs{background:var(--bg);border-radius:10px;padding:10px 12px;margin-top:10px;' +
  'font-family:ui-monospace,Menlo,Consolas,monospace;font-size:11px;line-height:1.9;' +
  'word-break:break-all;text-align:left;direction:ltr;white-space:pre-wrap;' +
  'max-height:40vh;overflow:auto;-webkit-user-select:all;user-select:all}' +
  '.link{background:var(--bg);border-radius:10px;padding:10px 12px;font-family:ui-monospace,Menlo,Consolas,monospace;' +
  'font-size:12px;word-break:break-all;text-align:left;margin-bottom:10px}' +
  'button{width:100%;border:0;border-radius:10px;background:var(--tint);color:#fff;' +
  'font:inherit;font-weight:600;padding:11px;cursor:pointer}' +
  'button:active{opacity:.8}' +
  // Secondary, because it is the way in for whoever the primary one did not work for -- an
  // outline rather than a second filled button, so the page still has one obvious first move.
  'button.ghost{background:transparent;color:var(--tint);border:1px solid var(--tint);margin-top:10px}' +
  '.contact{text-align:center;color:var(--dim);font-size:13px;margin-top:16px}' +
  '.warnline{color:var(--off);font-size:13.5px;font-weight:600}' +
  '.apps-title{color:var(--dim);font-size:12.5px;margin:14px 0 8px}' +
  '.apps{display:flex;flex-wrap:wrap;gap:8px}' +
  '.app{flex:1 1 auto;text-align:center;text-decoration:none;color:var(--tint);' +
  'border:1px solid var(--line);border-radius:9px;padding:8px 10px;font-size:13px;font-weight:600;' +
  'min-width:88px}' +
  '.app:active{opacity:.6}';

/**
 * One copy handler, wired to every button that declares a source.
 *
 * `data-src` names the element to copy, `data-done` the word to flash on success and `data-fail`
 * the one on failure, so a third button is markup rather than more script.
 *
 * **Falling back when the clipboard API REJECTS, not only when it is absent.** The first version
 * checked `navigator.clipboard && navigator.clipboard.writeText` and treated its presence as
 * success — but `writeText` exists and then rejects with `NotAllowedError` in exactly the places
 * this page is opened: Android WebViews and in-app browsers, Telegram's included, which is how a
 * subscriber usually taps a link they were sent. Because the promise had no rejection handler, the
 * button did nothing at all: nothing copied, no message, no fallback. Measured, not guessed —
 * `writeText` rejected with "Write permission denied" on a focused, secure-context page.
 *
 * And when even the `execCommand` route fails, the hidden block is **revealed** rather than the
 * failure being swallowed. A person who cannot copy can still select the text by hand; a person
 * told nothing presses the button again.
 */
const STUDIO_PAGE_JS =
  '(function(){' +
  // Off-screen rather than `display:none`: a textarea that is not laid out cannot be selected, and
  // an unselected one copies nothing. `setSelectionRange` is what makes this work on iOS, where
  // `select()` alone does not.
  "function legacy(t,ok,fail){var a=document.createElement('textarea');a.value=t;" +
  "a.setAttribute('readonly','');a.style.position='fixed';a.style.top='-1000px';" +
  'document.body.appendChild(a);a.focus();a.select();' +
  'try{a.setSelectionRange(0,t.length)}catch(e){}' +
  "var did=false;try{did=document.execCommand('copy')}catch(e){}" +
  'document.body.removeChild(a);if(did){ok()}else{fail()}}' +
  "function wire(b){var s=document.getElementById(b.getAttribute('data-src'));if(!s)return;" +
  "b.addEventListener('click',function(){var t=s.textContent;var was=b.textContent;" +
  'function flash(m){b.textContent=m;setTimeout(function(){b.textContent=was},1800)}' +
  "function ok(){flash(b.getAttribute('data-done'))}" +
  "function fail(){s.hidden=false;flash(b.getAttribute('data-fail'))}" +
  'if(navigator.clipboard&&navigator.clipboard.writeText){' +
  'navigator.clipboard.writeText(t).then(ok,function(){legacy(t,ok,fail)})}' +
  'else{legacy(t,ok,fail)}})}' +
  "var all=document.querySelectorAll('button[data-src]');" +
  'for(var i=0;i<all.length;i++)wire(all[i]);})();';
