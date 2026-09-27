# iOS Redesign — running task list

Requests collected during the 1.2.2 redesign, in the order they were made. Kept here rather than
in a chat scrollback so nothing gets lost between sessions.

## Done

- [x] **Home screen becomes an icon grid.** 4 columns x 6 rows, hamburger drawer removed, all
      twelve drawer features become icons. Glass dock (Scan / V2Ray / Settings / WireGuard),
      home screen only.
- [x] **Drag to rearrange**, iPhone-style, order persisted.
- [x] **Ten wallpapers + own photo** with an adjustable blur.
- [x] **Genuinely edge to edge** — backdrop under the status and navigation bars, no cut line.
- [x] **RTL drag inversion fixed** — `absoluteOffset`/`translationX` instead of direction-aware
      `offset`.
- [x] **Icon dropped mid-drag fixed** — drag delta and wobble read inside `graphicsLayer`, not in
      the composition body, so a touch move no longer recomposes and rebuilds the gesture node.
- [x] **Missing icons fixed** — the grid is six Rows of four weighted cells instead of absolutely
      positioned children.
- [x] **Settings rebuilt iOS-style** — grouped cards, tinted glyphs, Cloudflare account card at
      the top, no Save button.
- [x] **No inline dropdowns** — language, screen-off timeout, backend DNS, local port, MTU,
      routing mode and the per-app list each push their own page.
- [x] **Advanced VPN settings** rebuilt in the same idiom.
- [x] **Total Usage** rebuilt in the same idiom.
- [x] **Dark bar at the top of every screen removed** — the feature chrome bar is transparent.
- [x] **"تنظیمات" as an iOS large title** above the Cloudflare account card.
- [x] **Rounder corners** on the grouped cards (10dp -> 22dp).
- [x] **Icon size setting** in Display. Scales the tile only; captions and cell pitch stay put.
- [x] **Wallpaper-on-every-screen toggle** in Display — off gives every screen except home a
      plain background.
- [x] **Light / dark / automatic** in Display.

- [x] **Frosted glass cards.** Grouped setting cards and the home dock now refract a blurred
      slice of the real backdrop, aligned with `positionInWindow()`. See `ui/home/Glass.kt`.
- [x] **Wallpaper on every screen.** Roots made transparent: Game, Aether, Quick Connect, VPN
      Gate, Fixed IP, Workers, Sub Link, About, Help. Nodes, Scanner and Cloud never painted a
      root background, so they were already showing it.
- [x] **Documentation** — in-app changelog (About), tutorial 19 (Help), CHANGELOG.md, all in both
      languages.

## Standing rule

Every screen matches the home screen and Settings. The pre-redesign look is retired; there is no
screen exempt from it. Text is white in BOTH appearances, so "light" means lighter GLASS, not a
white sheet -- a white sheet under white type would be unreadable.

## Design standard

Radii come from `ui/theme/Radius.kt` -- four tiers, chosen by ROLE: `badge` 8, `control` 12,
`panel` 16, `card` 22. The dock's 30 and the icon tiles' percentage squircle are the only
exceptions, both deliberate.

Accent (iOS blue) is for things you can act on: buttons, selected states, links. Never for
headings, never for back arrows, never for body text. Status colours are green/red/orange.

Text is white in BOTH appearances; "light" is lighter glass, not a white sheet.

## Open

- [x] **Fading top edge.** The chrome bar floats over the content, the content runs full height
      and dissolves under the status bar via a `DstIn` gradient mask (`fadingTopEdge`). Real on
      the Settings family, Quick Connect and Game; the rest are inset rather than faded because
      their headers are fixed, and they need the same restructure one at a time.
- [x] **Account dots** under the Cloudflare card when more than one account is connected.
- [x] **Drag commits on release, not while moving.** Reordering live meant the icon jumped out
      from under the finger and long drags drifted; now the target is tracked, highlighted, and
      applied when the finger lifts.
- [x] **Tap empty space finishes rearranging**, same as the Done button.
- [x] **Dock label toggle** in Display.
- [x] **Intro animation plays on a cold start only** — the flag is process-scoped, not
      composition-scoped, so returning to a backgrounded app no longer replays it.
- [ ] **Dialogs and modals are neither glass nor theme-aware.** Named example: the add-Cloudflare-
      account sheet. Sweep every Dialog/modal: AddNodeModal, BpbSettingsModal, FreeConfigWizard,
      QuickServersScreen, the Cloud tab's own sheets.
- [ ] **Light theme on the Cloud tab is badly unbalanced** — its elements do not agree with each
      other at all. Needs its own colour pass, not just a transparent root.
- [x] **43 cards on the old screens now use the real refraction**, not just the glass colour --
      every `background(SurfaceDark, shape)` became `frostedGlass(shape)`.
- [x] **Scanner and Cloud scroll under the bars** like Settings, Quick Connect and Game. Their
      roots were already scroll containers, so the inset moved from layout padding to content
      padding.
- [x] **The blur reaches the glass and the intro**, not only the wallpaper. The cards were sitting
      on a sharper, brighter slice of the picture than the one behind them.

- [x] **The glass had a race, not a bug in the effect.** It read `LocalView.current.width`, a
      plain field rather than Compose state: a first composition before the view was measured read
      zero and nothing ever asked again. Home recomposes every second for the traffic counter so it
      recovered; quiet screens like Settings never did. The activity root now measures itself and
      publishes `LocalWindowSizePx`.
- [x] **The intro carries its own wallpaper** at the current blur, then its dark field on top --
      so it follows the blur setting and is still opaque enough to hide the home screen. Merely
      translucent, the icons showed straight through it.
- [x] **About floats its header** over a full-height scroll. **Help** keeps its layout (a search
      field and banner sit between its bar and its list) but lost the drop shadow that WAS the
      line.

- [ ] **Fixed-header screens that still do not scroll under the status bar** -- Nodes, Aether,
      VPN Gate, Help, Fixed IP, Workers, Sub Link. None of them shows a cut line any more, so this
      is polish: each needs its header floated over a full-height scroll, one at a time.
- [ ] **`Surface(color = ...)` and `cardColors(containerColor = ...)` sites keep the glass COLOUR
      rather than the refraction** -- 56 of them. They take a Colour, not a Modifier, so each needs
      its shape and modifier threaded by hand.
- [ ] **Real artwork for the icons.** Settings first (macOS gears), then others one at a time as
      the user sends them. Needs an image path in `HomeApp` alongside the current vector glyphs.
- [ ] **Light theme only reaches the rebuilt surfaces.** The home screen, Settings and everything
      under it, Advanced VPN and Total Usage resolve their colours through `Ios.*`, which follows
      the Appearance setting. Every other screen still carries hardcoded dark hex values for its
      *content* (cards, text, chips) even though its root is now transparent. That is why the
      setting defaults to Dark. Converting them is a per-screen pass: replace the literal colours
      with `Ios.*` equivalents.
- [x] **IRANSansX** for the whole app, four weights, Latin-numeral cut (FaNum would render ports
      and IPs in Persian digits). Wired through Material's Typography and the XML theme.
- [x] **Corner radii collapsed** from eighteen values across 383 sites to four tiers.
- [x] **Accent put back where iOS puts it** -- 13 back arrows normalised to the label colour,
      headings de-blued, the connect FAB turned from a solid blue disc into glass with a
      state-coloured ring.
- [x] **The glass blur root cause.** `inSampleSize` only halves, and only while BOTH sides still
      exceed the request, so a decode routinely stopped several times larger than asked. The
      frosted effect blurs by drawing a small copy back at full size -- a copy five times too big
      is barely blurred. That, not the effect, is why cards showed a sharp wallpaper. The decode
      now scales explicitly to the size requested.
- [x] **Doubled outlines removed** -- 18 cards kept their old `.clip().border()` after the swap to
      `frostedGlass`, which draws both itself.
- [x] **Two text-size settings** plus a "follow the system" option, on by default.

- [ ] **Modals still to convert:** BPB settings, the free-config wizard, MLM settings and users,
      Nahan settings, EDG settings. The add-node sheet and the 21 AlertDialogs are done.
- [x] **Frosted glass verified on device** (SM-S948B, Android 16). Three separate causes, all
      fixed: a window size read from a non-observable View field, a backdrop copy in the photo's
      aspect rather than the window's, and an upscale standing in for a blur kernel. See the
      `frosted-glass-blur` memory.

- [ ] **Verify the rest on device** — only the settings screen has been looked at since the sweep.

## دور جدید بازخورد (جلسه جاری)

- [x] آیکون تنظیمات کوچک‌تر از همسایه‌هایش بود — هنر خودش ۹۰٪ بوم را می‌گیرد، روی لایه ۱۰۰/۹۰ بزرگ شد
- [x] دکمه «شروع راه‌اندازی» پوشه دامین‌فرانتینگ — رنگ دکمه و متنش
- [x] چیپ‌های حالت تست پلتفرم — آبی ناهماهنگ، رنگ متن و آیکون
- [x] مودال سوئیچ خودکار (v2ray) و افزودن حساب (ابری) بیش از حد شفاف بودند
- [x] سیستم بررسی آپدیت: انتخاب APK بر اساس ABI دستگاه + تایم‌اوت دانلود + مجوز نصب
- [x] نام «اضطراری ۱» → «تونل ورسل» و «اضطراری ۲» → «گوگل اسکریپت»
- [x] توضیح در صفحه تونل ورسل: کل گوشی را تونل نمی‌کند
- [x] عنوان و آیکون‌های آبی صفحه «درباره ما»
- [x] عنوان آبی صفحه «مرکز آموزش و مستندات»
- [ ] سه صفحه اضطراری کاملاً ios glass شوند
- [ ] صفحه «DNS ضد تحریم شخصی» کاملاً بازطراحی شود — نه فقط شیشه‌ای، همه‌چیزش iOS
- [ ] صفحه «آیپی ثابت»: عنوان «انتخاب کشور» آبی است؛ نام کشور انتخاب‌شده آبی است؛
      دکمه «شروع استعلام» رفته بالای صفحه — باید چسبان و شناور پایین صفحه باشد؛
      رنگ دکمه و رنگ متنش هم غلط است
- [ ] صفحه «گیت‌وی MLM»: دکمه اتصال آبی، آیکون لیست سرورها بالای صفحه باید خاکستری شود،
      بخش انتخاب سرور و مودال راهنمای لیست سرورها زمینه ندارند و شفاف‌اند،
      «لیست سرورهای بیشتر» کاملاً UI قدیمی دارد — متن‌ها و دکمه‌ها ریز به ریز، شیشه‌ای تار
- [ ] صفحه «گیم بوستر»: کل UI انگار مال اپ دیگری است — رنگ‌بندی و استایل هیچ‌کدام هماهنگ نیست.
      نمونه مشخص: منوی بازشوی «انتخاب ریجن» زشت و غیرحرفه‌ای است
- [ ] صفحه «اتصال سریع»: پر از آبی؛ مودال لیست سرورها زمینه ندارد و شفاف شده؛
      کل صفحه UI قدیمی دارد — دکمه‌ها، رنگ‌بندی و کل استایل ریدیزاین شود
- [ ] صفحه «آیپی ثابت»: کارت کشورها و کارت آیپی‌های داخل کشور انتخاب‌شده شیشه‌ای نشده‌اند

## خارج از ریدیزاین
- [x] خطای KV در دیپلوی BPB: اول لیست می‌گرفت نه ایجاد، لیست صفحه‌بندی نداشت (پیش‌فرض ۲۰ تا)،
      و پیام خطا بدنه‌ی خام JSON ایجاد را نشان می‌داد. حالا لیست صفحه‌بندی‌شده → ایجاد فقط در نبود،
      و پیام فارسی با علت واقعی (مثلاً نبودِ دسترسی Workers KV روی توکن)
- [x] تب ابری: فلش سبز انتقال به V2Ray حذف شد؛ جایش اقدام برچسب‌دار با وضعیت + «باز کردن V2Ray»
- [x] پیام انگلیسی «قبلاً منتقل شده» — رشته اصلاً ترجمه فارسی نداشت (به‌علاوه ۳ رشته دیگر)
- [x] کرش دکمه + تب ابری: StackOverflow — پاس فیلدها، بدنه‌ی خودِ iosFieldColors را هم عوض کرده بود
      و تابع خودش را صدا می‌زد. بازتولید و تأیید رفع شد روی گوشی.
- [x] BPB و EDG: شناسه KV روی حساب ذخیره می‌شود و دیپلوی بعدی همان را بازاستفاده می‌کند
- [x] خطای ۱۰۰۳۷ کلادفلر: پیام فارسی با دسترسی‌های لازم + لاگ کامل بدنه پاسخ
- [x] پنج مودال موتورها (BPB/EDG/MLM/کاربران MLM/ناهان) زمینه گرفتند
- [ ] بازی Project R.I.S.E به گیم بوستر اضافه شود
