package com.mlmvpn.scanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.theme.BadgeShape
import com.mlmvpn.scanner.ui.theme.ControlShape
import com.mlmvpn.scanner.ui.theme.PanelShape
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

// =================================================================================================
// The tutorials screen.
//
// What this replaces: nineteen numbered `Card`s in one flat list, each of which EXPANDED IN PLACE
// into a wall of raw text -- `**bold**` markers stripped rather than rendered, every line the same
// size and weight, warnings detected by searching for an emoji, and the FAQ behind a translucent
// `Dialog` at 90% × 85% of the screen.
//
// Reading a tutorial and choosing one are two different jobs, and the old screen did both in the
// same scroll: expanding article 14 pushed articles 15-19 off the bottom and left you scrolling
// through prose to get back to the list. So the list is a list -- grouped, so nineteen items are
// four short sections -- and an article is a page.
//
// The content strings themselves are untouched. They were always written in a small markdown
// dialect; the difference is that it is now rendered instead of stripped.
// =================================================================================================

data class HelpArticle(
    val id: String,
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val content: String
)

/**
 * Which section an article belongs to.
 *
 * Keyed by id rather than by position so the two language lists cannot drift apart, and so adding
 * an article is one line here instead of a renumbering of every title after it.
 */
private fun sectionOf(id: String): Int = when (id) {
    "base", "home_screen", "quick_connect" -> 0
    "nahan_users", "nahan_settings_guide", "sort_panel", "mythological_names", "cloudflare_limits" -> 1
    "scanner_pro", "ip_archive", "fixed_ip", "edg_fixed_ip", "bpb_fixed_ip", "flag_logic" -> 2
    else -> 3
}

private fun sectionTitles(isFa: Boolean): List<String> =
    if (isFa) listOf("شروع کنید", "پنل‌ها و کانفیگ", "سرعت و آی‌پی ثابت", "ابزارهای دیگر")
    else listOf("Getting started", "Panels & configs", "Speed & fixed IP", "Other tools")

@Composable
fun HelpCenterScreen(onDismiss: () -> Unit) {
    val isFa = com.mlmvpn.scanner.utils.AppLocaleManager.getResolvedLocale().language == "fa"

    var searchQuery by remember { mutableStateOf("") }
    var openArticleId by remember { mutableStateOf<String?>(null) }
    var showFaq by remember { mutableStateOf(false) }

    val articles = remember(isFa) { if (isFa) getHelpArticlesFa() else getHelpArticlesEn() }

    // ---- pushed pages -----------------------------------------------------------------------
    val openArticle = articles.firstOrNull { it.id == openArticleId }
    if (openArticle != null) {
        androidx.activity.compose.BackHandler { openArticleId = null }
        HelpArticleScreen(
            article = openArticle,
            isFa = isFa,
            onBack = { openArticleId = null },
        )
        return
    }
    if (showFaq) {
        androidx.activity.compose.BackHandler { showFaq = false }
        HelpFaqScreen(isFa = isFa, onBack = { showFaq = false })
        return
    }

    val query = searchQuery.trim()
    val matches = remember(query, isFa) {
        if (query.isBlank()) emptyList()
        else articles.filter {
            it.title.contains(query, ignoreCase = true) || it.content.contains(query, ignoreCase = true)
        }
    }
    val sections = sectionTitles(isFa)

    IosScreen(
        title = if (isFa) "آموزش‌ها" else "Tutorials",
        onBack = onDismiss,
        backLabel = if (isFa) "خانه" else "Home",
    ) {
        Spacer(Modifier.height(10.dp))

        HelpSearchField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = if (isFa) "جست‌وجو در آموزش‌ها" else "Search tutorials",
        )

        Spacer(Modifier.height(18.dp))

        if (query.isNotBlank()) {
            SettingsSectionHeader(
                if (matches.isEmpty()) {
                    if (isFa) "چیزی پیدا نشد" else "No results"
                } else {
                    if (isFa) "${faCount(matches.size)} نتیجه" else "${matches.size} results"
                }
            )
            if (matches.isEmpty()) {
                Text(
                    if (isFa) {
                        S(R.string.no_tutorial_matched_that_phrase_the_search) +
                            S(R.string.so_a_shorter_word_may_do_better)
                    } else {
                        "Nothing matched. Search covers both titles and article text, so a shorter " +
                            "word may work better."
                    },
                    color = Ios.SecondaryLabel,
                    fontSize = 13.sp,
                    lineHeight = 21.sp,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            } else {
                SettingsGroup {
                    matches.forEachIndexed { index, article ->
                        if (index > 0) Separator()
                        SettingsRow(
                            title = article.title,
                            subtitle = sections[sectionOf(article.id)],
                            icon = article.icon,
                            tint = Ios.Gray,
                            onClick = { openArticleId = article.id },
                        )
                    }
                }
            }
        } else {
            // The FAQ first: it answers the questions people arrive with, before they go looking
            // for the article that might contain the answer.
            SettingsGroup {
                SettingsRow(
                    title = if (isFa) "پرسش‌های پرتکرار" else "Frequently asked questions",
                    subtitle = if (isFa) "جواب کوتاه سؤال‌هایی که زیاد پرسیده می‌شود" else "Short answers to common questions",
                    icon = Icons.Default.LiveHelp,
                    tint = Ios.Teal,
                    onClick = { showFaq = true },
                )
            }

            sections.forEachIndexed { sectionIndex, sectionTitle ->
                val inSection = articles.filter { sectionOf(it.id) == sectionIndex }
                if (inSection.isEmpty()) return@forEachIndexed
                Spacer(Modifier.height(20.dp))
                SettingsSectionHeader(sectionTitle)
                SettingsGroup {
                    inSection.forEachIndexed { index, article ->
                        if (index > 0) Separator()
                        SettingsRow(
                            title = article.title,
                            icon = article.icon,
                            tint = Ios.Gray,
                            onClick = { openArticleId = article.id },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * The search field.
 *
 * An `OutlinedTextField` with a Material border sat at the top of this screen and nowhere else in
 * the app. This is the same rounded, borderless control the rest of the app uses, and its clear
 * button only exists while there is something to clear.
 */
@Composable
private fun HelpSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(ControlShape)
            .background(Color.White.copy(alpha = 0.07f))
            .heightIn(min = 40.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Search,
            contentDescription = null,
            tint = Ios.SecondaryLabel,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = Ios.Label, fontSize = 15.sp),
            cursorBrush = SolidColor(Ios.Blue),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(placeholder, color = Ios.SecondaryLabel.copy(alpha = 0.7f), fontSize = 15.sp)
                }
                inner()
            },
        )
        if (value.isNotEmpty()) {
            Icon(
                Icons.Default.Cancel,
                contentDescription = null,
                tint = Ios.SecondaryLabel,
                modifier = Modifier
                    .size(17.dp)
                    .clip(CircleShape)
                    .clickable { onValueChange("") },
            )
        }
    }
}

/** One tutorial, on its own page. */
@Composable
fun HelpArticleScreen(article: HelpArticle, isFa: Boolean, onBack: () -> Unit) {
    IosScreen(
        title = article.title,
        onBack = onBack,
        backLabel = if (isFa) "آموزش‌ها" else "Tutorials",
    ) {
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    article.icon,
                    contentDescription = null,
                    tint = Ios.Label,
                    modifier = Modifier.size(21.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                article.title,
                color = Ios.Label,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 27.sp,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(18.dp))
        HelpBody(article.content)
        Spacer(Modifier.height(32.dp))
    }
}

/** The FAQ, on a page rather than in a 90%-of-the-screen dialog pretending to be one. */
@Composable
fun HelpFaqScreen(isFa: Boolean, onBack: () -> Unit) {
    val faqs = remember(isFa) { if (isFa) getFaqsFa() else getFaqsEn() }
    IosScreen(
        title = if (isFa) "پرسش‌های پرتکرار" else "FAQ",
        onBack = onBack,
        backLabel = if (isFa) "آموزش‌ها" else "Tutorials",
    ) {
        Spacer(Modifier.height(14.dp))
        faqs.forEachIndexed { index, faq ->
            if (index > 0) Spacer(Modifier.height(10.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(PanelShape)
                    .background(Color.White.copy(alpha = 0.05f))
                    .padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Default.HelpOutline,
                        contentDescription = null,
                        tint = Ios.SecondaryLabel,
                        modifier = Modifier.size(17.dp).padding(top = 2.dp),
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        faq.q,
                        color = Ios.Label,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 22.sp,
                    )
                }
                Spacer(Modifier.height(9.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Default.CheckCircleOutline,
                        contentDescription = null,
                        tint = Ios.Green,
                        modifier = Modifier.size(17.dp).padding(top = 2.dp),
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        faq.a,
                        color = Ios.SecondaryLabel,
                        fontSize = 13.sp,
                        lineHeight = 22.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

// -------------------------------------------------------------------------------------------------
// The renderer.
//
// The old one did `line.replace("**", "")` with a comment admitting it was a placeholder, so every
// heading, every field name and every emphasised warning in nineteen articles came out as ordinary
// body text. The markup was always there; nothing rendered it.
// -------------------------------------------------------------------------------------------------

/** `**bold**` becomes bold. Everything else is left exactly as written. */
private fun markdownLine(text: String, base: Color, bold: Color) = buildAnnotatedString {
    var rest = text
    while (true) {
        val open = rest.indexOf("**")
        if (open == -1) break
        val close = rest.indexOf("**", open + 2)
        if (close == -1) break
        withStyle(SpanStyle(color = base)) { append(rest.substring(0, open)) }
        withStyle(SpanStyle(color = bold, fontWeight = FontWeight.SemiBold)) {
            append(rest.substring(open + 2, close))
        }
        rest = rest.substring(close + 2)
    }
    withStyle(SpanStyle(color = base)) { append(rest) }
}

/** A line that is nothing but one bold run is a heading, not a sentence in bold. */
private fun isHeading(line: String): Boolean {
    val t = line.trim()
    return t.startsWith("**") && t.endsWith("**") && t.count { it == '*' } == 4 && t.length > 4
}

private val STEP = Regex(S(R.string.s_0_9_s))

@Composable
private fun HelpBody(content: String) {
    val lines = content.split("\n")
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        var previousWasBlank = true
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) {
                previousWasBlank = true
                continue
            }

            when {
                // Warnings were detected by looking for the emoji and then tinted orange -- still
                // one more line of running text. A callout is the shape that actually stops the eye.
                line.contains("⚠️") -> {
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(PanelShape)
                            .background(Ios.Orange.copy(alpha = 0.13f))
                            .padding(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = Ios.Orange,
                            modifier = Modifier.size(17.dp).padding(top = 1.dp),
                        )
                        Spacer(Modifier.width(9.dp))
                        Text(
                            markdownLine(
                                line.replace("⚠️", "").trim(),
                                Ios.Label.copy(alpha = 0.92f),
                                Ios.Label,
                            ),
                            fontSize = 13.sp,
                            lineHeight = 22.sp,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }

                isHeading(line) -> {
                    Spacer(Modifier.height(if (previousWasBlank) 16.dp else 10.dp))
                    Text(
                        line.trim('*'),
                        color = Ios.Label,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 24.sp,
                    )
                    Spacer(Modifier.height(7.dp))
                }

                STEP.matches(line) -> {
                    val m = STEP.find(line)!!
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(19.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.10f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                m.groupValues[1],
                                color = Ios.SecondaryLabel,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            markdownLine(m.groupValues[2], Ios.SecondaryLabel, Ios.Label),
                            fontSize = 13.sp,
                            lineHeight = 23.sp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                else -> {
                    Spacer(Modifier.height(if (previousWasBlank) 8.dp else 5.dp))
                    Text(
                        markdownLine(line, Ios.SecondaryLabel, Ios.Label),
                        fontSize = 13.sp,
                        lineHeight = 23.sp,
                    )
                }
            }
            previousWasBlank = false
        }
    }
}

fun getHelpArticlesFa(): List<HelpArticle> {
    return listOf(
        HelpArticle(
            id = "base",
            title = S(R.string.basic_guide_start_to_finish),
            icon = Icons.Default.School,
            content = """
                **آموزش قدم به قدم استفاده از برنامه:**
                
                ۱. **کلادفلر:** وارد سایت cloudflare.com شوید و در صورت نداشتن اکانت ثبت‌نام کنید یا وارد شوید.
                ۲. **دریافت API:** به بخش پروفایل (My Profile) > API Tokens بروید. در قسمت Global API Key روی دکمه View کلیک کرده و کد را دریافت کنید.
                ۳. **جای‌گذاری:** در تب «ابری» برنامه، ایمیل کلادفلر و Global API Key را وارد کنید و روی دکمه بررسی/ورود کلیک کنید.
                ۴. **دیپلوی پنل:** پس از ورود، در صورت نیاز یک ساب‌دامین بسازید. سپس روی گزینه «دیپلوی» کلیک کنید تا پنل شخصی شما روی کلادفلر ساخته شود.
                ۵. **دریافت نود:** پس از پایان دیپلوی، روی دکمه «دریافت نود» کلیک کنید تا کانفیگ‌های اولیه ذخیره شوند.
                ۶. **اسکن آی‌پی:** به تب «اسکنر» بروید. در این بخش با وارد کردن آی‌پی یا ساب‌نت، روی شروع اسکن کلیک کنید تا آی‌پی‌های تمیز پیدا شوند.
                ۷. **ترکیب:** پس از پیدا شدن آی‌پی‌های سالم، در پایین صفحه روی دکمه «ترکیب با نودهای ابری» کلیک کنید.
                ۸. **انتقال به نود:** به تب «نودها» بروید. در اینجا کانفیگ‌های جدیدی که از ترکیب آی‌پی‌های تمیز و پنل شما ساخته شده‌اند را مشاهده می‌کنید.
                ۹. **پینگ و سرعت:** در تب نودها، کیفیت تمام نودها را بررسی کنید.
                ۱۰. **اتصال:** بهترین نود را انتخاب کرده، به تب اصلی (اتصال) برگردید و دکمه اتصال را لمس کنید.
            """.trimIndent()
        ),
        HelpArticle(
            id = "fixed_ip",
            title = S(R.string.fixed_ip_and_location_switching_nahan_exclusive),
            icon = Icons.Default.LocationOn,
            content = """
                **چگونه لوکیشن کانفیگ خود را برای همیشه روی یک کشور (مثلاً آمریکا) قفل کنیم؟**
                
                در آپدیت جدید برنامه، کدهای پروژه اصلی Nahan در اپلیکیشن ما به صورت کاملاً اختصاصی تغییر یافته‌اند. این تغییر به ما اجازه می‌دهد که علاوه بر تغییر آدرس اتصال (Ingress)، تونل خروجی وورکر (Egress ProxyIP) را هم کنترل کنیم.
                
                **حالت اول: برای یک کاربر خاص (مثلاً user1) - روش پیشنهادی**
                ۱. در تب ابری، بخش تنظیمات NHN، به لیست کاربران بروید.
                ۲. روی آیکون مداد ✏️ (ویرایش) کنار نام کاربر کلیک کنید.
                ۳. آی‌پی کشور مورد نظر (مثلاً آمریکا) را در فیلد جدیدِ **«آی‌پی ثابت اختصاصی کاربر (Proxy IP)»** قرار دهید و ذخیره کنید.
                *نتیجه:* با این کار، هم آدرس اولیه کانفیگ و هم تونل خروجی این کاربر روی آی‌پی آمریکا قفل می‌شود.
                
                **حالت دوم: برای کل پنل (همه کاربران به صورت پیش‌فرض)**
                اگر می‌خواهید همه کاربران پنل آی‌پی ثابت آمریکا را داشته باشند:
                ۱. در تنظیمات شبکه NHN، فیلد **«ریلی اختصاصی (Custom Relay)»** را با آی‌پی آمریکا پر کنید. (این فیلد لوکیشن خروجی وورکر را به اجبار به آمریکا می‌فرستد).
                ۲. فیلد **«آی‌پی ثابت کل پنل / فرگمنت»** را هم با همان آی‌پی پر کنید. (این فیلد آدرسِ خامِ کانفیگی که دریافت می‌کنید را آمریکا می‌کند).
                
                **⚠️ قدم بسیار مهم (استقرار مجدد):**
                از آنجا که کدهای هسته وورکر تغییر کرده، باید یک بار از منوی ابری (سه نقطه کنار اکانت) روی **"استقرار مجدد" (Redeploy)** کلیک کنید تا کدهای جدیدِ وورکر روی کلادفلر شما نصب شوند.
            """.trimIndent()
        ),
        HelpArticle(
            id = "nahan_users",
            title = S(R.string.managing_nahan_users_making_configs_for_friends),
            icon = Icons.Default.GroupAdd,
            content = """
                **چگونه به صورت نامحدود برای دوستان و خانواده کانفیگ بسازیم؟**
                
                شما می‌توانید از طریق پنل ابری خود، کاربران مجزا با ترافیک‌های محدود یا نامحدود بسازید:
                
                ۱. در تب «ابری»، زیر اکانت NHN دیپلوی شده خود، روی دکمه مدیریت کاربران (آیکون چند کاربر) کلیک کنید.
                ۲. لیستی از کاربران فعلی (از جمله اکانت پیش‌فرض خودتان) را می‌بینید. روی دکمه شناور **افزودن (+)** در پایین صفحه کلیک کنید.
                ۳. **نام کاربری:** یک نام دلخواه (مثلا Ali) وارد کنید.
                ۴. **محدودیت ترافیک:** می‌توانید حجم مشخصی (مثلا 50 گیگابایت) و تعداد روز انقضا تعیین کنید. اگر 0 بگذارید، نامحدود خواهد بود.
                ۵. پس از ذخیره، کاربر ساخته می‌شود.
                ۶. برای ارسال کانفیگ به دوستتان، روی آیکون **اشتراک‌گذاری** (Share) کنار نام او کلیک کنید. لینک اتصال VLESS کپی می‌شود و می‌توانید آن را در تلگرام یا واتس‌اپ بفرستید.
            """.trimIndent()
        ),
        HelpArticle(
            id = "flag_logic",
            title = S(R.string.how_does_the_node_flag_work),
            icon = Icons.Default.Flag,
            content = """
                **چرا با وجود اینکه آی‌پی کلادفلر را می‌دهیم، پرچم کشور واقعی سرور نمایش داده می‌شود؟**
                
                یکی از قابلیت‌های هوشمند این اپلیکیشن، سیستم تشخیص مسیر واقعی دیتای شماست.
                وقتی شما کانفیگ‌های خود را با آی‌پی‌های تمیز اسکنر (Scanner) یا فرگمنت ترکیب می‌کنید، آدرس ظاهری نود (کانفیگ) تغییر می‌کند (مثلاً به یک آی‌پی آلمان). 
                
                اما سرور واقعی وورکر کلادفلر شما ممکن است در فرانسه یا آمریکا باشد.
                
                **نحوه عملکرد فنی:**
                اپلیکیشن ما در پس‌زمینه، به جای نگاه کردن به نام ظاهری، به صورت مستقیم با سرور واقعی که ترافیک از آن عبور می‌کند تماس می‌گیرد. 
                حتی برای آی‌پی‌های نسخه ۶ (IPv6) که رزولوشن آن‌ها سخت‌تر است، برنامه از طریق ارتباط ایمن DNS over HTTPS (DoH) کلادفلر، مسیر را رمزگشایی کرده و **پرچم دقیق و واقعی آن کشوری که در نهایت سایت‌ها شما را با آن می‌شناسند** به شما نمایش می‌دهد. این پرچم نشان‌دهنده مسیر فیزیکی خروجی دیتاست، نه فقط یک آدرس ظاهری.
            """.trimIndent()
        ),
        HelpArticle(
            id = "cloudflare_limits",
            title = S(R.string.cloudflare_s_100_000_requests_a_day),
            icon = Icons.Default.Warning,
            content = """
                **سقف مصرف کلادفلر چیست و چه زمانی با آن مواجه می‌شوید؟**
                
                سرویس رایگان Cloudflare Workers که پنل شما روی آن نصب می‌شود، محدودیت **۱۰۰,۰۰۰ درخواست در روز** (به ازای هر اکانت) دارد.
                
                **جزئیات بسیار مهم:**
                - **درخواست چیست؟** در دنیای وب، دانلود یک فایل ۱ گیگابایتی یک درخواست نیست! هر بار که صفحه‌ای باز می‌شود، صدها درخواست (برای عکس‌ها، کدها و...) ارسال می‌شود. تماشای ویدیو در یوتیوب یا دانلود فایل با دانلود منجر، می‌تواند ده‌ها هزار درخواست در دقیقه تولید کند.
                - **اگر به سقف برسیم چه می‌شود؟** پنل شما و تمام نودهای متصل به آن فوراً قطع خواهند شد و خطای 1045 یا عدم اتصال دریافت می‌کنید.
                - **چه زمانی ریست می‌شود؟** این محدودیت هر روز ساعت ۳:۳۰ بامداد (به وقت ایران) که معادل 00:00 به وقت جهانی (UTC) است، صفر می‌شود و کانفیگ‌ها دوباره وصل می‌شوند.
                
                **راه‌حل حرفه‌ای:**
                اگر مصرف شما و دوستانتان سنگین است، تنها راه رایگان این است که چند اکانت جیمیل و کلادفلر مختلف بسازید، API جدید بگیرید، و در تب ابریِ اپلیکیشن، پنل‌های مجزا (اکانت ابری جدید) دیپلوی کنید تا بار مصرفی بین آن‌ها تقسیم شود.
            """.trimIndent()
        ),
        HelpArticle(
            id = "sort_panel",
            title = S(R.string.the_sort_by_panel_feature),
            icon = Icons.Default.Sort,
            content = """
                **چگونه صدها کانفیگ ابری را بدون سردرگمی مدیریت کنیم؟**
                
                اگر شما از چندین اکانت ابری یا پنل‌های مختلف (مثل EDG, BPB, NHN و حتی نودهای کاستوم) به صورت همزمان استفاده می‌کنید، لیست نودهای شما در تب «نودها» به سرعت شلوغ و گیج‌کننده می‌شود.
                
                **توضیح فنی سوئیچ مرتب‌سازی:**
                - در بالای لیست نودها، کنار آیکون سطل زباله، یک سوئیچ به نام «مرتب‌سازی بر اساس پنل» وجود دارد.
                - **وقتی خاموش است:** تمام نودها به صورت یکپارچه زیر هم ردیف می‌شوند.
                - **وقتی روشن است:** اپلیکیشن وارد کدهای کانفیگ‌ها شده و آن‌ها را بر اساس «موتور تولیدکننده» (Engine Type) دسته‌بندی می‌کند. 
                - در این حالت، برای هر پنل یک **تب اختصاصی (Segmented Tab)** در بالای لیست ظاهر می‌شود. با کلیک روی هر تب، فقط نودهای مربوط به همان پنل نمایش داده می‌شوند.
                
                **مزیت اصلی:** این قابلیت نه تنها باعث زیبایی و دسترسی سریع‌تر می‌شود، بلکه وقتی روی دکمه «پینگ همه» کلیک می‌کنید، فقط کانفیگ‌های همان پنلی که در حال مشاهده آن هستید پینگ می‌شوند و بار اضافی به سایر پنل‌ها وارد نمی‌شود.
            """.trimIndent()
        ),
        HelpArticle(
            id = "scanner_pro",
            title = S(R.string.a_full_guide_to_the_two_stage),
            icon = Icons.Default.Radar,
            content = """
                **چرا اسکنر این اپلیکیشن با سایر اسکنرهای ساده متفاوت است؟**
                
                اسکنر تعبیه شده در این اپلیکیشن از یک معماری دومرحله‌ای و مبتنی بر هسته Xray استفاده می‌کند تا دقیق‌ترین نتیجه را بدهد:
                
                **فاز اول: شبکه خام (TCP/TLS Ping)**
                شما در بخش اسکنر یک یا چند رنج آی‌پی (CIDR مثل 104.20.0.0/24) را وارد می‌کنید. اپلیکیشن ابتدا هزاران آی‌پی از این رنج تولید کرده و با سرعت بسیار بالا، آن‌ها را در سطح شبکه خام پینگ می‌کند. این کار فقط آی‌پی‌هایی که روشن و زنده هستند را جدا می‌کند.
                
                **فاز دوم: تست مسدودیت زیر بار اینترنت ملی (Xray Testing)**
                آی‌پی‌های زنده‌ای که از فاز اول عبور کردند، مستقیماً وارد هسته Xray اندروید می‌شوند. اپلیکیشن در پس‌زمینه یک تونل واقعی می‌سازد تا ببیند آیا این آی‌پی‌ها توسط فیلترینگ مسدود شده‌اند یا خیر. اگر پورت کلادفلرِ یک آی‌پی روی شبکه اینترنت شما بسته باشد، در این مرحله شناسایی و حذف می‌شود.
                
                **مرحله پایانی (ترکیب):**
                آی‌پی‌هایی که از هر دو فاز زنده بیرون می‌آیند، آی‌پی‌های طلایی هستند! با کلیک روی گزینه **«ترکیب با نودهای ابری»** در پایین صفحه اسکنر، این آی‌پی‌ها روی کانفیگ‌های پنل شما سوار می‌شوند و ده‌ها کانفیگ بدون فیلتر و پرسرعت در تب «نودها» برای شما آماده اتصال می‌شوند.
            """.trimIndent()
        ),
        HelpArticle(
            id = "ip_archive",
            title = S(R.string.the_ip_archive_and_retesting),
            icon = Icons.Default.Storage,
            content = """
                **چگونه بدون صرف زمان برای اسکن‌های طولانی، همیشه آی‌پی تمیز داشته باشیم؟**
                
                اپلیکیشن به یک سیستم حرفه‌ای به نام **آرشیو اسکنر** مجهز است.
                
                **ذخیره خودکار:** 
                هر بار که شما با موفقیت اسکن می‌کنید و در نهایت دکمه «ترکیب» را می‌زنید، اپلیکیشن به صورت خودکار آن دسته از آی‌پی‌های سالمی که پیدا کرده بود را در بخش «آرشیو اسکنر» (در تب اسکنر) ذخیره می‌کند.
                
                **قابلیت جادویی تست مجدد (Retest):**
                پس از چند روز، ممکن است آی‌پی‌های تمیزی که پیدا کرده بودید توسط سیستم فیلترینگ مسدود شوند و کانفیگ‌هایتان کار نکنند. به جای اینکه دوباره رنج‌های طولانی را از صفر اسکن کنید:
                ۱. به بخش اسکنر بروید و یکی از گروه‌های قدیمیِ آرشیو را باز کنید.
                ۲. روی آیکون رفرش/تست مجدد کلیک کنید.
                ۳. اپلیکیشن فقط همان چند آی‌پی قدیمی را دوباره پینگ می‌کند و مرده‌ها را بلافاصله دور می‌ریزد.
                ۴. با زدن دکمه ترکیب، شما در عرض چند ثانیه با استفاده از آی‌پی‌هایی که هنوز سالم مانده‌اند، کانفیگ‌های جدید می‌سازید!
            """.trimIndent()
        ),
        HelpArticle(
            id = "nahan_settings_guide",
            title = S(R.string.a_full_guide_to_nahan_s_advanced),
            icon = Icons.Default.Settings,
            content = """
                **راهنمای کامل فیلدهای تنظیمات پنل نهان**
                
                برای دسترسی به تنظیمات پیشرفته، در تب "ابری" روی آیکون چرخ‌دنده (تنظیمات) در زیر اکانت نهان کلیک کنید. در این صفحه فیلدهای مختلفی وجود دارد که هرکدام کاربرد خاصی دارند:
                
                **۱. پروتکل خروجی (Output Protocol):**
                این فیلد به شما اجازه می‌دهد نوع کانفیگ ساخته شده را تعیین کنید. می‌توانید آن را روی `VLESS` یا `Trojan` قرار دهید. اگر آن را روی `Both` بگذارید، وورکر شما همزمان از هر دو پروتکل پشتیبانی خواهد کرد و کانفیگ‌های متنوع‌تری به شما می‌دهد.
                
                **۲. پسورد (UUID/Password):**
                این کد طولانی، قلب تپنده و شناسه امنیتی کانفیگ شماست. این پسورد هم برای ورود به پنل تحت وب نهان و هم به عنوان رمز اتصال در کانفیگ‌های VLESS و Trojan استفاده می‌شود. نیازی به تغییر آن نیست مگر آنکه بخواهید رمز اختصاصی خود را داشته باشید.
                
                **۳. ریلی اختصاصی (Custom Relay / ProxyIP):**
                این فیلد یکی از مهم‌ترین بخش‌ها برای ثابت کردن آی‌پی خروجی (Egress) است. اگر آی‌پی یک سرور تمیز از کشور دلخواه (مثلا آمریکا) را در اینجا قرار دهید، ترافیک شما بعد از کلادفلر به آن سرور منتقل می‌شود. نتیجه این است که سایت‌ها لوکیشن شما را "آمریکا" تشخیص می‌دهند. اگر خالی بماند، کلادفلر خودش به صورت خودکار لوکیشن را تعیین می‌کند.
                
                **۴. آی‌پی ثابت کل پنل / فرگمنت:**
                این آی‌پی همان آدرسی است که در ظاهر کانفیگ‌های شما (بخش Address در V2ray) قرار می‌گیرد (Ingress). این آی‌پی به کلادفلر وصل می‌شود. اگر آن را تغییر دهید، تمامی کانفیگ‌هایی که از این پنل دریافت می‌کنید، از همین آی‌پی برای دور زدن فیلترینگ استفاده خواهند کرد. (همچنین می‌توانید به جای آی‌پی، یک دامنه تمیز یا آدرس فرگمنت قرار دهید).
                
                *نکته مهم:* بعد از تغییر هرکدام از این تنظیمات و کلیک روی دکمه "ذخیره تنظیمات"، حتماً یک بار از منوی سه نقطه پنل ابری خود، روی **"استقرار مجدد"** کلیک کنید تا تنظیمات جدید به طور کامل روی سرورهای کلادفلر اعمال شوند.
            """.trimIndent()
        ),
        HelpArticle(
            id = "edg_fixed_ip",
            title = S(R.string.fixed_ip_in_the_edge_edg_panel),
            icon = Icons.Default.SettingsEthernet,
            content = """
                **چگونه لوکیشن کانفیگ‌های پنل EDG را تغییر دهیم؟**
                
                برای اینکه ترافیک خروجی پنل ادج از آی‌پی آلمانِ کلادفلر خارج نشود و به لوکیشن دلخواه شما (مثل فنلاند، آمریکا، ترکیه و...) منتقل شود، می‌توانید از قابلیت "آی‌پی ثابت" استفاده کنید.
                
                **مراحل تنظیم آی‌پی ثابت:**
                ۱. در تب "ابری"، روی آیکون چرخ‌دنده‌ی زیر پنل EDG کلیک کنید.
                ۲. در فیلد "آی‌پی ثابت (Proxy IP)"، آی‌پی تمیز و سالم خود را وارد کنید (مثال: `1.2.3.4`).
                ۳. روی دکمه ذخیره کلیک کنید تا تنظیمات در دیتابیس کلادفلر ثبت شود.
                ۴. حالا به تب "اسکن" بروید و مانند گذشته دکمه "ترکیب با نودهای ابری" را بزنید. 
                ۵. کانفیگ‌های جدیدی که ساخته می‌شوند، همگی ترافیک شما را از آی‌پی جدید عبور خواهند داد و سایت‌ها لوکیشن جدید را تشخیص می‌دهند.
                
                *نکته:* نیازی به استقرار مجدد پنل پس از تغییر آی‌پی ثابت نیست و تغییرات به صورت آنی در کانفیگ‌های جدید اعمال می‌شوند.
            """.trimIndent()
        ),
        HelpArticle(
            id = "bpb_fixed_ip",
            title = S(R.string.fixed_ip_in_the_bpb_panel),
            icon = Icons.Default.SettingsEthernet,
            content = """
                **چگونه آی‌پی ثابت کانفیگ‌های پنل BPB را تنظیم کنیم؟**
                
                در پنل BPB برای قفل کردن لوکیشن روی یک کشور خاص و جلوگیری از شناسایی توسط سیستم‌های تحریم، باید آی‌پی ثابت را تنظیم کنید:
                
                **مراحل تنظیم آی‌پی ثابت:**
                ۱. ابتدا باید یک آی‌پی تمیز از نوع IPv4 داشته باشید. می‌توانید این آی‌پی را از کانال‌ها و سورس‌های مختلف دریافت کنید یا به راحتی از سیستم اسکنر خودِ اپلیکیشن (تب اسکن) یک آی‌پی سالم پیدا کنید.
                ۲. در تب "ابری"، روی آیکون چرخ‌دنده‌ی تنظیمات زیر پنل BPB کلیک کنید.
                ۳. آی‌پی سالمی که پیدا کردید را در فیلد "آی‌پی ثابت (Proxy IP)" قرار دهید.
                ۴. (اختیاری) می‌توانید در فیلد "آی‌پی کل پنل / فرگمنت" نیز همان آی‌پی یا آدرس تمیز دیگری تنظیم کنید تا اینگرس تغییر کند.
                ۵. تنظیمات را ذخیره کنید.
                ۶. به تب اسکن رفته و "ترکیب با نودهای ابری" را بزنید. کانفیگ‌های تولید شده، ترافیک شما را مستقیماً از آی‌پی ثابتی که تنظیم کردید عبور می‌دهند.
            """.trimIndent()
        ),
        HelpArticle(
            id = "routing_mode",
            title = S(R.string.a_guide_to_routing_mode_split_tunnelling),
            icon = Icons.Default.AltRoute,
            content = """
                **چگونه فقط بعضی برنامه‌ها را از VPN عبور دهیم؟**
                
                اگر می‌خواهید فیلترشکن فقط روی برنامه‌های خاصی (مثل تلگرام یا اینستاگرام) کار کند و بقیه برنامه‌ها (مثل همراه بانک‌ها) از اینترنت عادی استفاده کنند، باید از قابلیت "حالت مسیریابی" استفاده کنید.
                
                **آموزش تنظیم حالت مسیریابی:**
                ۱. در تب اصلی نرم‌افزار (اتصال)، روی آیکون چرخ‌دنده (تنظیمات) کلیک کنید.
                ۲. وارد بخش "حالت مسیریابی" شوید.
                ۳. در اینجا سه حالت وجود دارد:
                   - **همه (ALL):** تمام ترافیک گوشی از VPN عبور می‌کند.
                   - **مجاز (ALLOW):** فقط برنامه‌هایی که تیک می‌زنید از VPN عبور می‌کنند (بقیه برنامه‌ها با اینترنت عادی کار می‌کنند). این حالت بهترین انتخاب برای جلوگیری از مسدود شدن همراه بانک‌هاست.
                   - **غیرمجاز (BYPASS):** تمام گوشی از VPN عبور می‌کند، به‌جز برنامه‌هایی که تیک می‌زنید (آن برنامه‌ها از VPN رد نمی‌شوند).
                ۴. بعد از انتخاب حالت مورد نظر (مثلاً مجاز)، صبر کنید تا لیست برنامه‌های گوشی لود شود و سپس تیک برنامه‌های مد نظرتان (مثل تلگرام، اینستاگرام، کروم و یوتیوب) را روشن کنید.
                ۵. به محض بازگشت، تنظیمات ذخیره می‌شود و در اتصال بعدی، مسیریابی جدید اعمال می‌گردد.
            """.trimIndent()
        ),
        HelpArticle(
            id = "mythological_names",
            title = S(R.string.where_do_the_mythological_config_names_come),
            icon = Icons.Default.AutoAwesome,
            content = """
                **چرا اسامی اساطیری روی گروه‌های من نمایش داده می‌شود؟**
                
                برای نظم‌دهی بهتر و زیبایی بصری، هر زمان که شما کانفیگ‌های جدیدی از پنل‌هایی مانند EDG یا BPB دریافت و اسکن می‌کنید، اپلیکیشن به صورت هوشمند و کاملاً تصادفی یکی از اسامی زیبای اساطیر، مشاهیر و مکان‌های باستانی ایران را به عنوان "نام گروه" برای آن کانفیگ‌ها انتخاب می‌کند. (البته برای پنل نهان، در صورتی که کانفیگ مختص کاربری خاص باشد، نام همان کاربر نمایش داده می‌شود).
                
                **آشنایی با اسامی اساطیری استفاده شده در اپلیکیشن:**
                
                - **رستم:** بزرگترین پهلوان شاهنامه، نماد قدرت و دفاع از ایران.
                - **سهراب:** پسر رستم، جوانی دلاور و جنگجو که به شکلی تراژیک کشته شد.
                - **کاوه:** آهنگر دلاوری که در برابر ظلم ضحاک قیام کرد و درفش کاویانی را برافراشت.
                - **آرش:** کماندار اسطوره‌ای که جانش را در کمان گذاشت تا مرز ایران را تعیین کند.
                - **سیاوش:** شاهزاده‌ای پاکدامان و بی‌گناه که نماد مظلومیت و وفاداری است.
                - **فریدون:** پادشاهی عادل که ضحاک را شکست داد و پادشاهی را به سه پسرش تقسیم کرد.
                - **اسفندیار:** شاهزاده‌ای رویین‌تن و پهلوان که تنها نقطه ضعفش چشمانش بود.
                - **جمشید:** از بزرگترین پادشاهان باستانی که جام جهان‌بین داشت و نوروز را پایه‌گذاری کرد.
                - **کیخسرو:** پادشاهی آرمانی و مقدس در شاهنامه که انتقام خون سیاوش را گرفت.
                - **گرشاسپ:** پهلوانی اژدهاکش و نیاکان رستم، مظهر شجاعت و دلاوری باستانی.
                - **بهرام:** ایزد پیروزی و جنگاوری در ایران باستان و همچنین نام پادشاهی قدرتمند (بهرام گور).
                - **زال:** پدر رستم، پهلوانی خردمند که با موهای سپید به دنیا آمد و سیمرغ او را بزرگ کرد.
                - **تهمینه:** همسر رستم و مادر سهراب، زنی زیبا و خردمند از سرزمین سمنگان.
                - **گردآفرید:** شیرزن و پهلوان‌بانوی ایرانی که دلاورانه با سهراب جنگید.
                - **رودابه:** مادر رستم و همسر زال، بانویی زیبارو که داستان عشق او از زیباترین داستان‌های شاهنامه است.
                - **گردیه:** زنی دلاور و خردمند، خواهر بهرام چوبین که در سوارکاری و نبرد همتا نداشت.
                - **آناهیتا:** ایزدبانوی آب‌ها، پاکی و باروری در ایران باستان که معابد بزرگی داشت.
                - **پاسارگاد:** پایتخت کوروش بزرگ و نماد شکوه معماری و امپراتوری هخامنشی.
                - **یوتاب:** خواهر آریوبرزن، سردار زن دلاوری که در کنار برادرش در برابر اسکندر جنگید.
                - **زربانو:** پهلوان‌بانوی ایرانی و دختر رستم که دوشادوش مردان در میدان نبرد می‌جنگید.
                - **آرتمیس:** نخستین دریاسالار زن جهان که فرماندهی نیروی دریایی خشایارشا را بر عهده داشت.
                - **آتوسا:** شهبانوی قدرتمند هخامنشی، دختر کوروش بزرگ و همسر داریوش.
                - **آرتادخت:** اقتصاددان و وزیر خزانه‌داری در دوره اشکانی که نماد تدبیر و خرد مالی بود.
                - **آذرآناهید:** از ملکه‌های قدرتمند و بانفوذ امپراتوری ساسانی.
                - **کاساندان:** همسر محبوب کوروش بزرگ که شریک و مشاور او در اداره امپراتوری بود.
            """.trimIndent()
        ),
        HelpArticle(
            id = "sni_engine",
            title = S(R.string.setting_up_the_sni_anti_filter_engine),
            icon = Icons.Default.RocketLaunch,
            content = """
                **چگونه با استفاده از موتور ضد فیلتر SNI یک اینترنت بدون فیلتر و پرسرعت داشته باشیم؟**
                
                اگر به دنبال دور زدن فیلترینگ با بالاترین سرعت و پایداری ممکن هستید، سیستم SNI یکی از بهترین روش‌هاست. برای استفاده از این قابلیت، مراحل ساده‌ی زیر را به ترتیب انجام دهید:
                
                ۱. **ورود به بخش اضطراری:** از منوی اصلی اپلیکیشن، گزینه «اضطراری سوم (SNI)» را انتخاب کنید.
                ۲. **اسکن و اتصال اولیه:** در بالای صفحه روی آیکون وای‌فای (اسکن/پینگ) کلیک کنید تا بهترین مسیرها بررسی شوند. سپس دکمه بزرگ «اتصال» را لمس کنید تا موتور ضد فیلتر در پس‌زمینه فعال شود.
                ۳. **بازگشت به تب اتصال:** از این صفحه خارج شده و به تب اصلی نرم‌افزار (تب اتصال) برگردید.
                ۴. **افزودن کانفیگ‌های SNI:** در بالای صفحه، روی دکمه «افزودن (+ )» کلیک کرده و گزینه «افزودن کانفیگ SNI» را انتخاب کنید.
                ۵. **تعیین تعداد:** در پنجره باز شده، تعداد کانفیگ‌هایی که می‌خواهید بسازید را انتخاب کنید (توصیه می‌کنیم روی حداکثر مقدار تنظیم کنید) و دکمه افزودن را بزنید.
                ۶. **تست و اتصال نهایی:** حالا به تب «نودها» بروید و **حتماً فقط از دکمه «تست دیلی (Delay)» استفاده کنید**. در این بخش فرقی نمی‌کند چه عدد دیلی به شما بدهد، این تست صرفاً برای تشخیص متصل بودن کانفیگ‌هاست. اما پیشنهاد می‌شود موردی که عدد کمتری دارد را انتخاب کنید (بی‌تاثیر نیست). پس از سبز شدن دیلی، به آن متصل شوید.
                
                ✅ **تبریک می‌گوییم! اینترنت شما فضایی شد 😍**
            """.trimIndent()
        ),
        HelpArticle(
            id = "sublink_generator",
            title = S(R.string.building_a_sub_link_with_cloudflare),
            icon = Icons.Default.Link,
            content = """
                **چگونه برای دوستانمان لینک ساب (Subscription Link) اختصاصی بسازیم؟**
                
                با استفاده از قابلیت جدید «ساخت لینک ساب»، شما می‌توانید کانفیگ‌های خود را در قالب یک لینک دائمی به دوستان خود بدهید تا آن‌ها با وارد کردن آن در کلاینت‌های V2ray (مثل V2rayNG) همیشه به آخرین کانفیگ‌های شما دسترسی داشته باشند.
                
                **مراحل ساخت لینک ساب:**
                ۱. ابتدا از صفحه‌ی اصلی، آیکون **«لینک ساب»** را بزنید.
                ۲. اگر حساب کلادفلر متصل نباشد، باید آن را در تب ابری متصل کنید.
                ۳. در اولین ورود، روی دکمه **«راه‌اندازی سیستم (Deploy)»** کلیک کنید. سیستم به صورت خودکار یک وورکر فوق‌سریع برای شما در کلادفلر می‌سازد و فضای ذخیره‌سازی ابری (KV) را تنظیم می‌کند.
                ۴. پس از آماده‌سازی، روی دکمه (+) کلیک کنید تا یک لینک جدید بسازید.
                ۵. در صفحه جدید، نام لینک، یک عبارت دلخواه کوتاه (Slug) برای آدرس، و گروهی از کانفیگ‌هایتان (مثلاً BPB یا VLESS) که می‌خواهید در این لینک قرار گیرند را انتخاب کنید.
                ۶. می‌توانید برای لینک **تاریخ انقضا (به روز)** تعیین کنید. اگر این فیلد را پر کنید، پس از گذشت آن زمان، لینک از کار می‌افتد و کاربر به جای کانفیگ، یک پیام «منقضی شده است» دریافت می‌کند.
                ۷. لینک را کپی کرده و به دوستانتان بدهید.
                
                **قابلیت آپدیت خودکار و آمارگیری:**
                - هر زمان که کانفیگ‌های آن گروه در اپلیکیشن شما تغییر کنند (مثلاً اسکن جدید بزنید)، لینک ساب به صورت **خودکار در پس‌زمینه** آپدیت می‌شود و دوستان شما با زدن دکمه آپدیت در V2rayNG، کانفیگ‌های جدید را دریافت می‌کنند.
                - با کلیک روی دکمه **«آمار (📊)»** در کنار هر لینک، می‌توانید ببینید تا الان چند بار آن لینک توسط دیگران آپدیت و دانلود شده است!
            """.trimIndent()
        ),
        HelpArticle(
            id = "dedicated_dns",
            title = S(R.string.dedicated_dns_for_lower_game_ping),
            icon = Icons.Default.Dns,
            content = """
                **DNS اختصاصی چیست؟**
                یک سرور DNS کاملاً شخصی که روی حساب Cloudflare **خودتان** (نه حساب مشترک) مستقر می‌شود و فقط مال شماست. کاملاً رایگان و بدون نیاز به کارت بانکی جهانی.

                **چرا به حساب Cloudflare نیاز دارد؟**
                این DNS باید روی زیرساخت شخصی خودتان اجرا شود تا هیچ‌کس دیگری از آن استفاده نکند و ترافیک/محدودیت آن فقط برای شما باشد » دقیقاً مثل پنل‌های EDG یا نهان که قبلاً مستقر کرده‌اید. برای همین ابتدا باید یک حساب Cloudflare را در **تب کلاد** وصل کنید.

                **تفاوتش با DNS معمولی چیست؟**
                یک DNS معمولی (مثل 1.1.1.1) فقط آدرس سرور بازی را برمی‌گرداند. اما این DNS اختصاصی با تکنیکی به نام **ECS (EDNS Client Subnet)** به سرور بالادست می‌گوید «انگار از منطقه X پرسیده می‌شود» تا نزدیک‌ترین و کم‌تاخیرترین سرور بازیِ آن منطقه را تحویل بگیرد. این یعنی بسته‌های بازی مسیر کوتاه‌تری طی می‌کنند و پینگ پایین می‌آید.

                **حالت خودکار (Auto):**
                برنامه چند منطقه را همزمان امتحان می‌کند، به سرورهای برگشتی واقعاً پینگ می‌زند، بهترین منطقه را انتخاب می‌کند و برای **هر بازی به‌صورت جداگانه** به خاطر می‌سپارد.

                **حالت دستی (Manual):**
                خودتان یک منطقه ثابت (مثلاً امارات یا ترکیه) را همیشه انتخاب می‌کنید.

                **چرا امارات پیش‌فرض است؟**
                معمولاً نزدیک‌ترین و کم‌تاخیرترین منطقه به کاربران ایرانی برای اکثر سرورهای بازی است؛ اگر مسیر بهتری پیدا نشود، امارات انتخاب می‌شود.

                **نکته مهم:**
                این قابلیت روی **همه‌ی حالت‌های بوست** (مستقیم، تونل، وارپ و هیبرید) و **همه‌ی بازی‌های موجود در لیست** به‌صورت خودکار اعمال می‌شود و نیازی به تنظیم جداگانه برای هر بازی ندارید. کافی است یک‌بار روی دکمه «فعال‌سازی DNS اختصاصی» در تب گیمینگ بزنید.
            """.trimIndent()
        ),
        HelpArticle(
            id = "domain_fronting",
            title = S(R.string.domain_fronting_no_server_installing_and_removing),
            icon = Icons.Default.Shield,
            content = """
                **این روش چیست؟**
                یک کانفیگ که یوتیوب، اینستاگرام، واتس‌اپ، فیسبوک و ردیت را **بدون هیچ سرور و بدون هیچ ورکر کلادفلری** باز می‌کند. ترافیک از گوشی خودتان مستقیم به سرور اصلی همان سایت می‌رود، فقط با یک نام جعلی و بلاک‌نشده. یعنی چیزی برای دیپلوی کردن نیست، هزینه‌ای ندارد، و هرچقدر کاربر زیاد شود کند نمی‌شود.

                **⚠️ محدودیت مهم — قبل از هر کاری بخوانید:**
                این روش **فقط داخل مرورگر** کار می‌کند (کروم، اج، بریو و هر مرورگر مبتنی بر کرومیوم). **اپلیکیشن یوتیوب و اینستاگرام با این روش باز نمی‌شوند.** این محدودیت خود اندروید ۷ به بالاست: اندروید اجازه نمی‌دهد اپ‌های معمولی به گواهی‌هایی که کاربر نصب کرده اعتماد کنند. هیچ راه دور زدنی هم ندارد (به‌جز روت کردن گوشی). اگر هدفتان باز شدن خود اپ‌ها است، از کانفیگ‌های پنل ابری یا کانفیگ‌های ایران استفاده کنید.

                **راه‌اندازی (چند دقیقه، یک‌بار برای همیشه):**
                ۱. تب **اتصال** ← پوشه‌ی **«دامین‌فرانتینگ (بدون سرور)»** را انتخاب کنید.
                ۲. دکمه‌ی **«شروع راه‌اندازی»** را بزنید. برنامه یک گواهی مخصوص همین گوشی می‌سازد و کانفیگ را در همان پوشه اضافه می‌کند.
                ۳. دکمه‌ی **«ذخیره گواهی در پوشه دانلود»** را بزنید. فایل `MLM-VPN-Certificate.crt` در پوشه Download ذخیره می‌شود.
                ۴. دکمه‌ی **«باز کردن تنظیمات اندروید»** را بزنید و از مسیر برند گوشی خودتان (پایین‌تر) گواهی را نصب کنید.
                ۵. به برنامه برگردید. مرحله‌ی دوم **خودش تیک می‌خورد** و دکمه‌ها محو می‌شوند. لازم نیست برنامه را ببندید.
                ۶. کانفیگ داخل همان پوشه را انتخاب و اتصال را بزنید. حالا در **کروم** سایت‌ها را باز کنید.

                **🔒 هشدار امنیتی (جدی بگیرید):**
                گواهی‌ای که برنامه می‌سازد **مخصوص همین گوشی** است و هیچ‌جا ارسال نمی‌شود. **هرگز فایل گواهی کسی دیگر را نصب نکنید** و **فایل گواهی خودتان را به هیچ‌کس ندهید.** هر کسی که فایل کلید شما را داشته باشد می‌تواند ترافیک اینترنت‌بانک و ایمیل شما را بخواند. برای همین ما یک گواهی آماده داخل برنامه نفرستادیم و هر گوشی گواهی خودش را می‌سازد.

                ────────────────────────
                **📥 مسیر نصب گواهی بر اساس برند**

                سریع‌ترین راه در همه‌ی گوشی‌ها: در **جستجوی خود تنظیمات** بنویسید `certificate` یا `گواهی` یا `credentials`.

                • **سامسونگ (One UI):** تنظیمات ← بیومتریک و امنیت (Biometrics and security) ← تنظیمات امنیتی دیگر (Other security settings) ← نصب از حافظه دستگاه (Install from device storage) ← گواهی CA
                • **شیائومی / ردمی / پوکو (MIUI و HyperOS):** تنظیمات ← رمزها و امنیت (Passwords & security) ← امنیت سیستم / حریم خصوصی ← رمزگذاری و اطلاعات ورود (Encryption & credentials) ← نصب گواهی از حافظه
                • **پیکسل و اندروید خام (۱۲ و بالاتر):** تنظیمات ← امنیت و حریم خصوصی (Security & privacy) ← تنظیمات بیشتر امنیت (More security settings) ← رمزگذاری و اطلاعات ورود ← نصب گواهی ← گواهی CA
                • **پیکسل و اندروید خام (۱۱):** تنظیمات ← امنیت (Security) ← رمزگذاری و اطلاعات ورود ← نصب گواهی ← گواهی CA
                • **هواوی و آنر (EMUI / MagicOS):** تنظیمات ← امنیت (Security) ← تنظیمات بیشتر (More settings) ← رمزگذاری و اطلاعات ورود ← نصب گواهی از حافظه
                • **اوپو / ریلمی / وان‌پلاس (ColorOS و OxygenOS):** تنظیمات ← رمز و امنیت (Password & security) ← امنیت سیستم (System security) ← رمزگذاری و اطلاعات ورود ← نصب از حافظه
                • **ویوو (Funtouch OS / OriginOS):** تنظیمات ← تنظیمات بیشتر (More settings) ← امنیت و حریم خصوصی ← رمزگذاری و اطلاعات ورود ← نصب گواهی
                • **موتورولا، نوکیا، ایسوس، سونی:** همان مسیر اندروید خام

                وقتی پرسید نوع گواهی چیست، **«گواهی CA / CA certificate»** را انتخاب کنید. اگر اخطار داد، **Install anyway** را بزنید. در آخر از پوشه **Download** فایل `MLM-VPN-Certificate.crt` را انتخاب کنید.

                ────────────────────────
                **🗑 مسیر حذف گواهی بر اساس برند**

                هر وقت خواستید این روش را کنار بگذارید، یا گواهی جدید ساختید و می‌خواهید قبلی را پاک کنید، گواهی را حذف کنید. گواهی نصب‌شده تا خودتان پاکش نکنید باقی می‌ماند.

                سریع‌ترین راه در همه‌ی گوشی‌ها: در جستجوی تنظیمات بنویسید `trusted credentials` یا `گواهی` و بعد سربرگ **«کاربر / User»** را انتخاب کنید. گواهی ما با نامی مثل **«MLM VPN Local CA …»** آنجاست؛ رویش بزنید و **حذف / Remove** را انتخاب کنید.

                • **سامسونگ (One UI):** تنظیمات ← بیومتریک و امنیت ← تنظیمات امنیتی دیگر ← **مشاهده گواهی‌های امنیتی (View security certificates)** ← سربرگ **User** ← گواهی را انتخاب و **Remove** بزنید
                • **شیائومی / ردمی / پوکو:** تنظیمات ← رمزها و امنیت ← حریم خصوصی / امنیت سیستم ← رمزگذاری و اطلاعات ورود ← **اطلاعات ورود مورد اعتماد (Trusted credentials)** ← سربرگ **User** ← حذف
                • **پیکسل و اندروید خام (۱۲ و بالاتر):** تنظیمات ← امنیت و حریم خصوصی ← تنظیمات بیشتر امنیت ← رمزگذاری و اطلاعات ورود ← **Trusted credentials** ← سربرگ **User** ← حذف
                • **پیکسل و اندروید خام (۱۱):** تنظیمات ← امنیت ← رمزگذاری و اطلاعات ورود ← Trusted credentials ← سربرگ User ← حذف
                • **هواوی و آنر:** تنظیمات ← امنیت ← تنظیمات بیشتر ← رمزگذاری و اطلاعات ورود ← Trusted credentials ← سربرگ User ← حذف
                • **اوپو / ریلمی / وان‌پلاس:** تنظیمات ← رمز و امنیت ← امنیت سیستم ← رمزگذاری و اطلاعات ورود ← Trusted credentials ← سربرگ User ← حذف
                • **ویوو:** تنظیمات ← تنظیمات بیشتر ← امنیت و حریم خصوصی ← رمزگذاری و اطلاعات ورود ← Trusted credentials ← سربرگ User ← حذف

                **راه سریع پاک کردن همه‌ی گواهی‌های کاربر با هم:** در همان صفحه‌ی «رمزگذاری و اطلاعات ورود» گزینه‌ای به نام **«پاک کردن اطلاعات ورود (Clear credentials)»** هست. توجه: این گزینه **تمام** گواهی‌های نصب‌شده توسط کاربر را پاک می‌کند، نه فقط گواهی ما.

                بعد از حذف، در پوشه‌ی دامین‌فرانتینگ برنامه، تیک مرحله‌ی دوم خودش برداشته می‌شود و دکمه‌های نصب برمی‌گردند. فایل `MLM-VPN-Certificate.crt` را هم می‌توانید از پوشه Download پاک کنید.

                ────────────────────────
                **رفع اشکال**

                • **مرحله‌ی نصب گواهی تیک نمی‌خورد:** یعنی اندروید هنوز گواهی را قبول نکرده. مطمئن شوید در مرحله‌ی انتخاب نوع، **«گواهی CA»** را زده‌اید (نه VPN و نه Wi-Fi) و فایل درست (`MLM-VPN-Certificate.crt`) را انتخاب کرده‌اید.
                • **اندروید می‌گوید اول قفل صفحه بگذارید:** شرط خود اندروید است؛ تا رمز یا الگو یا اثر انگشت نگذارید گواهی نصب نمی‌شود.
                • **پیام «نصب گواهی‌های CA ممکن نبود … از null»:** این پیام یعنی از مسیر اشتباه رفته‌اید. اندروید ۱۱ به بالا اجازه نمی‌دهد برنامه‌ها خودشان گواهی نصب کنند؛ باید از **تنظیمات** و از روی **فایل** نصب شود، دقیقاً همان کاری که مرحله‌ی ۳ و ۴ بالا می‌گوید.
                • **وصل می‌شود ولی سایت‌ها باز نمی‌شوند:** احتمالاً در اپ سایت را باز می‌کنید نه مرورگر. در کروم امتحان کنید.
                • **در فایرفاکس کار نمی‌کند:** فایرفاکس به‌صورت پیش‌فرض گواهی‌های کاربر را قبول نمی‌کند. About Firefox ← پنج بار روی لوگو بزنید ← Settings ← Secret Settings ← گزینه‌ی **Use third party CA certificates** را روشن کنید.
                • **بعد از ساخت گواهی جدید کار نمی‌کند:** گواهی قبلی را از تنظیمات حذف و گواهی جدید را نصب کنید؛ دو گواهی هم‌نام گیج‌کننده می‌شود.
                • **کانفیگ وصل نمی‌شود:** پورت محلی باید `10808` باشد. برنامه خودش این را تنظیم می‌کند، ولی اگر دستی عوضش کرده‌اید برگردانید.
            """.trimIndent()
        ),
        HelpArticle(
            id = "quick_connect",
            title = S(R.string.quick_connect_the_ready_made_server_list),
            icon = Icons.Default.FlashOn,
            content = """
                **این بخش چیست؟**
                «اتصال سریع» اولین آیکون صفحه‌ی اصلی برنامه است. یک فهرست آماده از هزاران سرور عمومی که خود برنامه دانلود می‌کند. **هیچ اکانتی نمی‌خواهد، هیچ پنلی نباید بسازید، هیچ کانفیگی نباید دستی وارد کنید و هیچ هزینه‌ای ندارد.**

                این ساده‌ترین راه وصل شدن است. بقیه‌ی بخش‌های برنامه (پنل ابری، اسکنر، نودها) وقتی به کار می‌آیند که سرور شخصی خودتان را بخواهید؛ اتصال سریع برای وقتی است که فقط می‌خواهید همین حالا آنلاین شوید.

                **ساده‌ترین حالت — فقط یک لمس:**
                برنامه را باز کنید و **دکمه‌ی بزرگ وسط صفحه** را بزنید. تمام.

                • **اگر سرور ذخیره‌شده دارید:** برنامه اصلاً سراغ مخزن نمی‌رود. فقط همان فهرست خودتان را **موازی** تست می‌کند (چند ثانیه) و به سریع‌ترین سرور زنده وصل می‌شود. دوباره تست می‌شوند و به عدد قبلی اعتماد نمی‌شود، چون یک سرور ممکن است از دیروز مرده باشد.
                • **اگر هنوز سروری ندارید:** برنامه در مخزن می‌گردد و **به اولین سرور خوبی که پیدا کند وصل می‌شود** — نه اولین سروری که صرفاً جواب می‌دهد. اگر تأخیرش زیر آستانه بود فوراً وصل می‌شود؛ اگر کند بود چند ثانیه‌ی دیگر دنبال بهترش می‌گردد و در نهایت بهترین چیزی را که پیدا کرده برمی‌دارد. آن سرور هم ذخیره می‌شود تا دفعه‌ی بعد از حالت اول استفاده کنید.

                برای قطع کردن، همان دکمه را دوباره بزنید.

                **دکمه‌ی اتصال چهار حالت دارد و هرکدام ظاهر خودش را دارد:**
                • **آماده (خاکستری، آرام نفس می‌کشد):** وصل نیستید. بزنید تا وصل شوید.
                • **در حال جست‌وجو / اتصال (آبی، کمان چرخان):** برنامه دارد کار می‌کند. زیر دکمه نوشته می‌شود دقیقاً در چه مرحله‌ای است. اگر در حال جست‌وجو بود و پشیمان شدید، دوباره بزنید تا لغو شود.
                • **متصل (سبز، حلقه‌ی کامل و ثابت):** آنلاین هستید. حرکت ندارد، چون روی حالتی که تمام شده حرکت فقط سر و صداست.
                • **در حال قطع (قرمز، کمان در جهت مخالف):** دارد قطع می‌شود.

                **«سرورهای من» — فهرست زیر دکمه:**
                هر سروری که تست شده و کار کرده اینجا می‌ماند، و بعد از بستن برنامه هم سر جایش است. مرتب‌شده از سریع‌ترین به کندترین.
                • روی نام هر سرور بزنید تا مستقیم به همان وصل شوید.
                • آیکون **چرخش** کنار هر ردیف: همان سرور را دوباره تست می‌کند.
                • آیکون **ضربدر** کنار هر ردیف: آن سرور را حذف می‌کند.
                • بالای فهرست، آیکون **تست شبکه**: همه را یک‌جا دوباره تست می‌کند (با نوار پیشرفت).
                • بالای فهرست، آیکون **سطل**: می‌پرسد «فقط قطع‌شده‌ها» یا «همه».
                • برچسب **«جدید»** یعنی تازه اضافه شده و هنوز ندیده بودیدش؛ برچسب **«قطع»** یعنی آخرین تست شکست خورده.

                **مهم:** سروری که حذف می‌کنید **دیگر برنمی‌گردد** — نه در فهرست دیده می‌شود، نه در تست‌ها شرکت می‌کند، و در به‌روزرسانی‌های بعدی هم دوباره دانلود نمی‌شود. پس سرورهای قطع‌شده را با خیال راحت حذف کنید. (اگر پشیمان شدید، در صفحه‌ی «فهرست سرورها» پایین صفحه دکمه‌ی «بازگرداندن» هست.)

                **صفحه‌ی «فهرست سرورها» — وقتی کشور خاصی می‌خواهید:**
                پایین صفحه‌ی اتصال، دکمه‌ی **«فهرست سرورها و بررسی کشورها»** را بزنید.
                ۱. روی کادر **«همه کشورها»** بزنید. فهرست پرچم‌ها باز می‌شود؛ جلوی هر کشور تعداد سرورهایش نوشته شده و بالای صفحه یک کادر **جست‌وجوی کشور** هست.
                ۲. یکی از این دو را بزنید:
                   • **«بررسی سریع»** — تا ۲۰ سرور سالم پیدا می‌کند و می‌ایستد. برای وقتی که فقط می‌خواهید وصل شوید.
                   • **«بررسی همه سرورها»** — تک‌تک سرورهای آن کشور را تست می‌کند. کامل‌تر است ولی اگر کشور هزاران سرور داشته باشد چند دقیقه طول می‌کشد. هر لحظه می‌توانید «توقف» بزنید.
                ۳. بعد از تست، دکمه‌ی سبز **«افزودن N سرور به صفحه‌ی اتصال»** همه‌ی نتیجه‌ها را به «سرورهای من» منتقل می‌کند.

                در این صفحه جلوی هر سرور نوشته شده **«قبلاً تست‌شده»** یا **«جدید»**، پس اگر یک کشور را دوباره بررسی کنید، یک نگاه کافی است تا ببینید چه چیزی واقعاً اضافه شده.

                **می‌توانید وسط بررسی از صفحه بیرون بروید:** جست‌وجو مستقل از صفحه اجرا می‌شود، پس دکمه‌ی برگشت فقط شما را از صفحه خارج می‌کند و بررسی در پس‌زمینه ادامه پیدا می‌کند. هر وقت برگردید، همان جست‌وجو با نتیجه‌های تا آن لحظه سر جایش است. برای تمام کردنش فقط دکمه‌ی **«توقف»** را بزنید.

                **جست‌وجوی هدف‌دار — «۵۰ سرور از ۵ کشور»:**
                پایین همان صفحه، یک کادر هست که در آن **تعداد سرور** (۱۰/۲۵/۵۰/۱۰۰) و **تعداد کشور** (۱/۳/۵/۱۰) را انتخاب می‌کنید و دکمه را می‌زنید. برنامه دقیقاً همان تعداد را می‌آورد، همه با اتصال واقعی تأییدشده، و به‌طور مساوی بین کشورها پخش‌شده — «۵۰ سرور از ۵ کشور» یعنی ۱۰ تا از هر کشور، نه ۵۰ تا که اتفاقاً همه‌شان از یک کشور باشند.

                کشورها هم‌زمان بررسی می‌شوند نه یکی‌یکی، و اگر کشوری نتواند سهمش را پر کند جای خالی از کشورهای دیگر تکمیل می‌شود. کشورها هم خودکار انتخاب می‌شوند: آن‌هایی که بیشترین سرور را دارند، چون کشوری با ۱۲ سرور نمی‌تواند سهم ۱۰ تایی را مطمئن تأمین کند.

                **جست‌وجو چه می‌کند و چرا دو مرحله دارد؟**
                نوار پیشرفت دو مرحله را نشان می‌دهد:
                • **مرحله ۱ — بررسی دسترسی:** فقط می‌بیند پورت سرور اصلاً باز است یا نه. این کار خیلی ارزان است و بیشتر سرورهای مرده همین‌جا حذف می‌شوند.
                • **مرحله ۲ — اتصال واقعی:** فقط سرورهای بازمانده یک اتصال واقعی را امتحان می‌کنند. این مرحله لازم است چون روی اینترنت فیلترشده **بیشتر سرورها اتصال را قبول می‌کنند ولی هیچ ترافیکی رد نمی‌کنند** — تنها مرحله‌ی دوم ثابت می‌کند سرور واقعاً کار می‌کند.

                سرورهایی که سریع‌تر جواب داده‌اند اول امتحان می‌شوند، پس معمولاً چند سرور خوب در همان ثانیه‌های اول پیدا می‌شود. هر لحظه می‌توانید دکمه‌ی **«توقف»** را بزنید؛ جست‌وجو تمام می‌شود و هرچه تا آن لحظه پیدا شده سر جایش می‌ماند.

                **پرچم‌ها از کجا می‌آیند؟**
                کشور هر سرور از روی **خود اسم سرور** تشخیص داده می‌شود: پرچمی که در نامش آمده، الگوهایی مثل «DE1» و «NL12»، یا نام کشور و شهر (فرانکفورت، آمستردام، استانبول و…). همه‌ی این کار روی گوشی و بدون اینترنت انجام می‌شود، برای همین پرچم‌ها فوراً ظاهر می‌شوند.

                از دیتابیس IP عمداً استفاده نمی‌کنیم: روی یک فهرست ۲۸۲۷ سروری، دیتابیس IP فقط در ۳۴٪ موارد با کشور واقعی سرور می‌خواند. مثلاً آدرس‌های کلادفلر در آمریکا ثبت شده‌اند ولی از فرانکفورت جواب می‌دهند.

                **علامت تیک سبز کنار اسم سرور یعنی چه؟**
                یعنی کشور آن سرور دیگر ادعا نیست، **اندازه‌گیری شده** است. بعد از اینکه واقعاً به یک سرور وصل شدید، برنامه از داخل همان تونل از کلادفلر می‌پرسد ترافیک از کجا بیرون آمده. اگر جواب با پرچم قبلی فرق داشت، پیامی می‌بینید که آن سرور به کشور واقعی‌اش منتقل شد.

                این نتیجه ذخیره می‌شود، یعنی از این به بعد آن سرور فقط زیر کشور واقعی‌اش دیده می‌شود. دلیلش این است که وقتی کسی کشوری را انتخاب می‌کند، هدفش این است که **از همان کشور بیرون بیاید** — سروری که زیر پرچم اشتباه باشد، دقیقاً همان یک چیزی را که این فهرست برایش ساخته شده خراب می‌کند.

                **نوار سبز بالای فهرست بعد از اتصال:**
                • «خروج واقعی: 🇩🇪 آلمان · FRA · …» یعنی ترافیک شما واقعاً از آلمان بیرون می‌رود.
                • «مسیر تأیید شد (WARP)» یعنی اتصال برقرار است ولی کشور خروج از این راه قابل اثبات نیست — چون وقتی مسیر از WARP رد می‌شود، کلادفلر **عمداً کشور خود شما** را گزارش می‌کند نه کشور سرور را. در این حالت هیچ کشوری ثبت نمی‌شود تا یک نتیجه‌ی اشتباه در فهرست ننشیند.

                **دکمه‌ی تازه‌سازی (بالای صفحه‌ی فهرست سرورها):**
                فهرست نیم‌ساعت روی گوشی ذخیره می‌ماند تا هر بار باز کردن صفحه چند مگابایت دانلود نشود. اگر می‌خواهید همین حالا فهرست تازه بگیرید این دکمه را بزنید.

                اگر دریافت تازه ناموفق شد، پیام زرد «فهرست ذخیره‌شده نمایش داده می‌شود» را می‌بینید و **فهرست قبلی سر جایش می‌ماند** — چون فهرست دیروز می‌تواند شما را وصل کند، ولی یک دانلود ناموفق نمی‌تواند.

                **سرورها از کجا می‌آیند و هر چند وقت به‌روز می‌شوند؟**
                فهرست از پنج منبع عمومی ساخته می‌شود که **هر ۱۵ دقیقه** به‌روز می‌شوند. چهارتای آن‌ها از یک تجمیع‌کننده می‌آید که حدود ۲۱ منبع عمومی را ادغام می‌کند و بر اساس نتیجه‌ی تست خودش دسته‌بندی می‌کند (تأییدشده، سریع، امن، همه). سرورهای تکراری بین منابع یک‌بار شمرده می‌شوند. در پایین صفحه‌ی «فهرست سرورها» می‌بینید هر منبع چند سرور داده و کدام‌شان ناموفق بوده.

                پروتکل‌های پشتیبانی‌شده: **VLESS، Trojan، VMess و Shadowsocks**. (کانفیگ‌های Shadowsocks که به افزونه‌ی جانبی نیاز دارند عمداً پذیرفته نمی‌شوند، چون هسته آن‌ها را اجرا می‌کند ولی ترافیکی رد نمی‌شود.)

                **مشکلات رایج:**
                • **«هیچ سرور پاسخ‌گویی پیدا نشد»** — معمولاً یعنی کشوری که انتخاب کرده‌اید سرور سالم کمی دارد. «همه کشورها» را انتخاب کنید و دوباره بزنید.
                • **«هیچ سروری دریافت نشد»** — دسترسی به منبع فهرست بسته است. یکی از گزینه‌های «ضد تحریم» یا کانفیگ‌های ایران را وصل کنید و بعد دکمه‌ی تازه‌سازی را بزنید.
                • **وصل می‌شود ولی سایت باز نمی‌شود** — سرور را عوض کنید؛ فهرست عمومی است و کیفیتش تضمینی نیست. برای اتصال پایدارتر از پنل ابری خودتان استفاده کنید.
                • **پرچم یک سرور اشتباه است** — یک بار به آن وصل شوید؛ خودش اصلاح و ثبت می‌شود.
                • **می‌خواهم همه‌ی اصلاح‌ها پاک شود** — فهرست کشورها را باز کنید و پایین صفحه «پاک کردن کشورهای اندازه‌گیری‌شده» را بزنید.

                **گیت‌وی MLM کجا رفت؟**
                گیت‌وی MLM (همان VPN Gate) **آیکون خودش را روی صفحه‌ی اصلی** دارد و هیچ تغییری نکرده.
            """.trimIndent()
        ),
        HelpArticle(
            id = "home_screen",
            title = S(R.string.the_home_screen_icons_dock_wallpaper),
            icon = Icons.Default.Apps,
            content = """
                **منوی همبرگری حذف شد — هر چه داخلش بود روی صفحه‌ی اول آمد**

                دیگر هیچ قابلیتی پشت منو پنهان نیست. آیپی ثابت، لیست ورکرها، DNS ضد تحریم، لینک ساب، مصرف، آموزش‌ها، درباره ما، هر سه حالت اضطراری، گیت‌وی MLM، اتصال سریع، کانفیگ رایگان، ابری و گیم بوستر — همه روی صفحه‌ی اصلی آیکون دارند.

                **داک پایین صفحه**

                چهار مورد پرکاربرد در یک حباب شیشه‌ای می‌مانند: **اسکن، V2Ray، تنظیمات و وایرگارد**. مثل آیفون، این داک فقط روی صفحه‌ی اصلی دیده می‌شود؛ وقتی وارد یک بخش می‌شوید، آن بخش تمام‌صفحه باز می‌شود و با دکمه‌ی برگشت گوشی یا فلش بالای صفحه به صفحه‌ی اصلی برمی‌گردید.

                **جابه‌جا کردن آیکون‌ها**

                ۱. انگشتتان را روی هر آیکونی **نگه دارید** تا آیکون‌ها شروع به لرزیدن کنند.
                ۲. همان آیکون را **بکشید** هر جا که می‌خواهید؛ بقیه خودشان کنار می‌روند.
                ۳. انگشت را بردارید — ترتیب همان لحظه ذخیره می‌شود.
                ۴. برای خروج، دکمه‌ی **«تمام»** بالای صفحه یا دکمه‌ی برگشت گوشی.

                چیدمان شما بعد از بستن برنامه هم سر جایش می‌ماند، و اگر در آپدیتی قابلیت تازه‌ای اضافه شود، آیکونش فقط به انتهای چیدمان شما اضافه می‌شود و چیزی ریست نمی‌شود. داک ثابت است و جابه‌جا نمی‌شود.

                **سه تنظیم دیگر در «صفحه نمایش»**

                • **اندازهٔ آیکون** — فقط خود آیکون‌ها را بزرگ و کوچک می‌کند؛ نوشته‌ها و فاصله‌ها ثابت می‌مانند. بالای اسلایدر چند آیکون نمونه زنده عوض می‌شوند.
                • **تصویر زمینه در همه صفحات** — خاموش کنید تا هر صفحه‌ای به‌جز صفحهٔ اصلی پس‌زمینهٔ ساده بگیرد.
                • **ظاهر برنامه** — روشن، تاریک یا خودکار. در این نسخه فقط صفحه‌های بازسازی‌شده رنگشان را از این تنظیم می‌گیرند؛ بقیه هنوز تیره می‌مانند.

                **تنظیمات**

                تنظیمات هم مثل آیفون گروه‌بندی شده است: **شبکه** (پورت محلی، DNS، حالت پروکسی، شبکه‌ی محلی، تنظیمات پیشرفته‌ی VPN)، **صفحه نمایش** (تصویر زمینه، نمایش ترافیک، زبان)، **مصرف**، **سیستم** و **درباره**.

                بالای صفحه، همان‌جا که آیفون حساب اپل را نشان می‌دهد، **حساب کلادفلر متصل شما** است. اگر حسابی وصل کرده باشید، نام و ایمیلش را می‌بینید؛ اگر نه، زدنش شما را به تب ابری می‌برد تا وصل کنید.

                **دکمه‌ی ذخیره دیگر وجود ندارد.** هر تغییری همان لحظه اعمال می‌شود. تنها استثنا پورت محلی است: تا وقتی عددی که نوشته‌اید معتبر نباشد، قرمز می‌شود، دلیلش را زیرش می‌نویسد و ذخیره نمی‌شود.

                **عوض کردن تصویر زمینه**

                به **تنظیمات ٔ صفحه نمایش ٔ تصویر زمینه** بروید. ده طرح آماده هست — شامگاه، کهربا، نیلی، زغالی، مه صبح، ارغوانی، اقیانوس، خاکستری، غروب و جنگل — که با یک ضربه اعمال می‌شوند. گزینه‌ی اول **«انتخاب از گالری»** است تا عکس خودتان را بگذارید.

                انتخابگر عکس **مال خود اندروید** است، نه برنامه: برنامه هیچ دسترسی به گالری از شما نمی‌خواهد و فقط همان یک عکسی که انتخاب کردید را می‌گیرد. آن عکس داخل خود برنامه کپی می‌شود تا اگر بعداً از گالری پاکش کردید، تصویر زمینه‌تان از بین نرود.

                **نوار بالای صفحه‌ی اصلی**

                سپر سمت راست وضعیت اتصال را نشان می‌دهد (سبز = متصل)، و طرف دیگر مصرف لحظه‌ای این نشست است. اگر شمارنده‌ی مصرف را نمی‌خواهید، از تنظیمات خاموشش کنید.

                **سوال‌های رایج**

                • **آیکون گیم بوستر کجا بود؟** پیش‌تر پشت یک کلید در تنظیمات خاموش بود، چون در نوار پایین جا نبود. حالا خانه‌ی دائمی دارد و آن کلید حذف شد.
                • **آیکونی را گم کردم.** شاید موقع جابه‌جایی جایش را عوض کرده‌اید؛ دوباره نگه دارید و ببریدش سر جای اول. هیچ آیکونی حذف نمی‌شود — فقط جابه‌جا می‌شود.
                • **صفحه بالا و پایین نمی‌رود.** درست است؛ همه‌ی آیکون‌ها در یک صفحه جا می‌شوند.
            """.trimIndent()
        )
    )
}

fun getHelpArticlesEn(): List<HelpArticle> {
    return listOf(
        HelpArticle(
            id = "base",
            title = "Basic Tutorial (End-to-End Usage)",
            icon = Icons.Default.School,
            content = """
                **Step-by-step app usage tutorial:**
                
                1. **Cloudflare:** Go to cloudflare.com and sign up or log in.
                2. **Get API:** Go to My Profile > API Tokens. In the Global API Key section, click View and get the code.
                3. **Insert:** In the "Cloud" tab of the app, enter your Cloudflare email and Global API Key, then click Check/Login.
                4. **Deploy Panel:** After login, create a subdomain if needed. Then click "Deploy" to create your personal panel on Cloudflare.
                5. **Get Node:** After deployment finishes, click "Get Node" to save initial configs.
                6. **Scan IP:** Go to the "Scanner" tab. Enter an IP or subnet and click Start Scan to find clean IPs.
                7. **Combine:** After finding healthy IPs, click "Combine with Cloud Nodes" at the bottom.
                8. **Move to Nodes:** Go to the "Nodes" tab. Here you will see new configs created from the combination of clean IPs and your panel.
                9. **Ping and Speed:** In the Nodes tab, check the quality of all nodes.
                10. **Connect:** Select the best node, return to the main tab (Connect), and tap the connect button.
            """.trimIndent()
        ),
        HelpArticle(
            id = "fixed_ip",
            title = "Fixed IP System & Location Change (Nahan Exclusive)",
            icon = Icons.Default.LocationOn,
            content = """
                **How to lock your config's location to a specific country (e.g., US) forever?**
                
                In the new app update, the core Nahan project codes have been customized exclusively. This allows us to control the Egress ProxyIP as well as the Ingress address.
                
                **Case 1: For a specific user (e.g., user1) - Recommended**
                1. In the Cloud tab, Nahan Settings section, go to the users list.
                2. Click the Edit (✏️) icon next to the user's name.
                3. Put the target country's IP (e.g., US) in the new **"User Proxy IP"** field and save.
                *Result:* Both the initial config address and this user's egress tunnel will be locked to the US IP.
                
                **Case 2: For the entire panel (All users by default)**
                If you want all panel users to have a US fixed IP:
                1. In Nahan Network Settings, fill the **"Custom Relay"** field with a US IP. (This forces the worker's egress location to the US).
                2. Fill the **"Clean IPs/Proxy IPs"** field with the same IP. (This sets the raw config address you receive to the US).
                
                **⚠️ Very Important Step (Redeploy):**
                Since the core worker codes have changed, you must click **"Redeploy"** from the cloud panel menu (three dots) once to install the new worker codes on your Cloudflare.
            """.trimIndent()
        ),
        HelpArticle(
            id = "nahan_users",
            title = "Nahan User Management (Creating Configs for Friends)",
            icon = Icons.Default.GroupAdd,
            content = """
                **How to create unlimited configs for friends and family?**
                
                You can create separate users with limited or unlimited traffic through your cloud panel:
                
                1. In the "Cloud" tab, under your deployed NHN account, click the User Management button (multi-user icon).
                2. You will see a list of current users. Click the floating **Add (+)** button at the bottom.
                3. **Username:** Enter a desired name (e.g., Ali).
                4. **Traffic Limit:** You can set a specific volume (e.g., 50 GB) and expiry days. If set to 0, it will be unlimited.
                5. After saving, the user is created.
                6. To send the config to a friend, click the **Share** icon next to their name. The VLESS connection link will be copied, and you can send it via Telegram or WhatsApp.
            """.trimIndent()
        ),
        HelpArticle(
            id = "flag_logic",
            title = "How do Node Flags Work?",
            icon = Icons.Default.Flag,
            content = """
                **Why is the real server's country flag shown even when we provide a Cloudflare IP?**
                
                One of the smart features of this app is its real data path detection system.
                When you combine your configs with scanner or fragment clean IPs, the node's apparent address changes (e.g., to a German IP).
                
                However, your actual Cloudflare worker server might be in France or the US.
                
                **Technical Operation:**
                In the background, instead of looking at the apparent name, our app directly contacts the real server through which the traffic passes.
                Even for IPv6 addresses that are harder to resolve, the app decrypts the path via Cloudflare's secure DNS over HTTPS (DoH) connection and displays **the exact, real flag of the country that websites ultimately recognize you as**. This flag represents the physical egress path of the data, not just an apparent address.
            """.trimIndent()
        ),
        HelpArticle(
            id = "cloudflare_limits",
            title = "Cloudflare's 100K Daily Request Limit",
            icon = Icons.Default.Warning,
            content = """
                **What is Cloudflare's usage cap and when will you face it?**
                
                The free Cloudflare Workers service, where your panel is installed, has a limit of **100,000 requests per day** (per account).
                
                **Crucial Details:**
                - **What is a request?** In the web world, downloading a 1GB file is not one request! Every time a page is opened, hundreds of requests (for images, codes, etc.) are sent. Watching YouTube or downloading with a download manager can generate tens of thousands of requests per minute.
                - **What happens if we reach the cap?** Your panel and all connected nodes will instantly disconnect, and you will receive a 1045 error or no connection.
                - **When does it reset?** This limit resets to zero every day at 00:00 UTC, and configs will connect again.
                
                **Pro Solution:**
                If you and your friends consume a lot, the only free way is to create several different Gmail and Cloudflare accounts, get new APIs, and deploy separate panels (new cloud accounts) in the app's Cloud tab to distribute the load among them.
            """.trimIndent()
        ),
        HelpArticle(
            id = "sort_panel",
            title = "Powerful 'Sort by Panel' Feature",
            icon = Icons.Default.Sort,
            content = """
                **How to manage hundreds of cloud configs without confusion?**
                
                If you use multiple cloud accounts or different panels (like EDG, BPB, NHN, and even custom nodes) simultaneously, your node list in the "Nodes" tab gets cluttered very quickly.
                
                **Technical Explanation of the Sort Switch:**
                - At the top of the node list, next to the trash icon, there is a switch named "Sort by Panel".
                - **When off:** All nodes are listed sequentially.
                - **When on:** The app categorizes them based on the "generator engine" (Engine Type).
                - In this mode, a **Segmented Tab** appears at the top for each panel. Clicking each tab shows only the nodes for that panel.
                
                **Main Advantage:** This feature not only enhances aesthetics and faster access, but when you click "Ping All", only the configs of the currently viewed panel are pinged, preventing extra load on other panels.
            """.trimIndent()
        ),
        HelpArticle(
            id = "scanner_pro",
            title = "Comprehensive Guide to Two-Stage Smart Scanner",
            icon = Icons.Default.Radar,
            content = """
                **Why is this app's scanner different from other simple scanners?**
                
                The built-in scanner in this app uses a two-stage, Xray-core-based architecture to provide the most accurate results:
                
                **Phase 1: Raw Network (TCP/TLS Ping)**
                You enter one or more IP ranges (CIDR like 104.20.0.0/24). The app generates thousands of IPs from this range and pings them at the raw network level very quickly. This only isolates the alive and reachable IPs.
                
                **Phase 2: Blockage Test Under National Internet Load (Xray Testing)**
                Alive IPs from phase 1 are directly fed into the Android Xray core. The app builds a real tunnel in the background to see if these IPs are blocked by filtering. If a Cloudflare port for an IP is blocked on your network, it's identified and removed here.
                
                **Final Stage (Combination):**
                IPs surviving both phases are golden IPs! By clicking **"Combine with Cloud Nodes"** at the bottom, these IPs are mounted onto your panel's configs, readying dozens of unblocked, high-speed configs in the "Nodes" tab for connection.
            """.trimIndent()
        ),
        HelpArticle(
            id = "ip_archive",
            title = "IP Archive System & Retest",
            icon = Icons.Default.Storage,
            content = """
                **How to always have clean IPs without spending time on long scans?**
                
                The app is equipped with a professional system called **Scanner Archive**.
                
                **Auto Save:**
                Every time you successfully scan and finally hit "Combine", the app automatically saves those healthy IPs in the "Scanner Archive" section (in the Scanner tab).
                
                **Magical Retest Feature:**
                After a few days, clean IPs you found might get blocked and configs stop working. Instead of scanning long ranges from scratch:
                1. Go to the Scanner section and open an old archive group.
                2. Click the Refresh/Retest icon.
                3. The app only repings those few old IPs and instantly discards dead ones.
                4. By hitting combine, you create new configs in seconds using the still-healthy IPs!
            """.trimIndent()
        ),
        HelpArticle(
            id = "nahan_settings_guide",
            title = "Complete Guide to Nahan Panel Advanced Settings",
            icon = Icons.Default.Settings,
            content = """
                **Complete guide to Nahan panel settings fields**
                
                To access advanced settings, click the gear (settings) icon under the Nahan account in the "Cloud" tab. There are various fields here, each with a specific purpose:
                
                **1. Output Protocol:**
                Determines the type of config generated. You can set it to `VLESS` or `Trojan`. If set to `Both`, your worker will support both protocols simultaneously, giving you more varied configs.
                
                **2. Password (UUID/Password):**
                This long code is the beating heart and security identifier of your config. It's used both for web panel login and as the connection password in VLESS and Trojan configs. No need to change it unless you want your own custom password.
                
                **3. Custom Relay / ProxyIP:**
                One of the most important fields to fix the egress IP. If you put a clean server IP from a desired country (e.g., US) here, your traffic transfers there after Cloudflare. Result: websites detect your location as "US". If left empty, Cloudflare determines the location automatically.
                
                **4. Clean IPs/Proxy IPs:**
                This is the address that appears in the Address section of your configs (Ingress). This connects to Cloudflare. If changed, all configs you get from this panel will use this IP to bypass filtering. (You can also use a clean domain or fragment address).
                
                *Important Note:* After changing any setting and saving, make sure to click **"Redeploy"** from your cloud panel's three-dot menu once so the new settings fully apply on Cloudflare servers.
            """.trimIndent()
        ),
        HelpArticle(
            id = "edg_fixed_ip",
            title = "Fixed IP Guide for EDG Panel",
            icon = Icons.Default.SettingsEthernet,
            content = """
                **How to change the location of EDG panel configs?**
                
                To prevent your EDG panel traffic from exiting via a German Cloudflare IP and route it to your desired location (like Finland, US, Turkey), use the "Proxy IP" feature.
                
                **Setup Steps:**
                1. In the "Cloud" tab, click the gear icon under the EDG panel.
                2. In the "Proxy IP" field, enter your clean, healthy IP (e.g., `1.2.3.4`).
                3. Click Save to register settings in the Cloudflare database.
                4. Go to the "Scan" tab and hit "Combine with Cloud Nodes" as usual.
                5. The newly generated configs will all pass your traffic through the new IP, and websites will detect the new location.
                
                *Note:* No redeployment is needed after changing the fixed IP; changes apply instantly to new configs.
            """.trimIndent()
        ),
        HelpArticle(
            id = "bpb_fixed_ip",
            title = "Fixed IP Guide for BPB Panel",
            icon = Icons.Default.SettingsEthernet,
            content = """
                **How to set a fixed IP for BPB panel configs?**
                
                In the BPB panel, to lock the location to a specific country and avoid detection by sanction systems, you must set a fixed IP:
                
                **Setup Steps:**
                1. First, get a clean IPv4 address. You can get this from channels or easily find a healthy IP using the app's scanner system.
                2. In the "Cloud" tab, click the settings gear icon under the BPB panel.
                3. Put the healthy IP you found in the "Proxy IP" field.
                4. (Optional) You can also set the same IP or another clean address in the "Clean IPs" field to change the ingress.
                5. Save settings.
                6. Go to the scan tab and hit "Combine with Cloud Nodes". The generated configs will route your traffic directly through your configured fixed IP.
            """.trimIndent()
        ),
        HelpArticle(
            id = "routing_mode",
            title = "Split Tunneling Guide",
            icon = Icons.Default.AltRoute,
            content = """
                **How to route only specific apps through the VPN?**
                
                If you want the VPN to work only on certain apps (like Telegram or Instagram) while others (like banking apps) use normal internet, you should use the "Routing Mode" feature.
                
                **Setup Tutorial:**
                1. In the main app tab (Connect), click the gear icon (Settings).
                2. Enter the "Routing Mode" section.
                3. There are three modes here:
                   - **ALL:** All phone traffic passes through the VPN.
                   - **ALLOW:** Only checked apps pass through the VPN (others use normal internet). This is the best choice to prevent banking apps from blocking you.
                   - **BYPASS:** The entire phone passes through the VPN, EXCEPT the checked apps (they bypass the VPN).
                4. After choosing the desired mode (e.g., ALLOW), wait for the app list to load, then check your target apps (like Telegram, Instagram, Chrome, YouTube).
                5. Upon returning, settings are saved and the new routing is applied on the next connection.
            """.trimIndent()
        ),
        HelpArticle(
            id = "mythological_names",
            title = "The Story of Mythological Config Names",
            icon = Icons.Default.AutoAwesome,
            content = """
                **Why are mythological names displayed on my groups?**
                
                For better organization and visual appeal, whenever you receive and scan new configs from panels like EDG or BPB, the app intelligently and randomly selects one of the beautiful names of Iranian mythology, famous figures, or ancient places as the "Group Name". (For the Nahan panel, if the config is for a specific user, that user's name is displayed instead).
                
                **Introduction to mythological names used:**
                
                - **Rostam:** The greatest hero of the Shahnameh, symbol of strength and defense of Iran.
                - **Sohrab:** Rostam's son, a brave warrior youth tragically killed.
                - **Kaveh:** The brave blacksmith who rebelled against the tyrant Zahhak.
                - **Arash:** The legendary archer who put his life into his bow to set Iran's border.
                - **Siavash:** An innocent prince symbolizing purity and loyalty.
                - **Fereydoun:** A just king who defeated Zahhak.
                - **Esfandiar:** An invulnerable prince and hero whose only weakness was his eyes.
                - **Jamshid:** One of the greatest ancient kings who founded Nowruz.
                - **Kay Khosrow:** An ideal, sacred king in the Shahnameh.
                - **Garshasp:** A dragon-slaying hero and ancestor of Rostam.
                - **Bahram:** The god of victory and war, also a powerful king.
                - **Zal:** Rostam's father, a wise hero raised by the Simurgh.
                - **Tahmineh:** Rostam's wife and Sohrab's mother, a wise and beautiful woman.
                - **Gordafarid:** An Iranian heroine who bravely fought Sohrab.
                - **Rudabeh:** Rostam's mother and Zal's wife, a beautiful lady with a famous love story.
                - **Gordieh:** A brave, wise woman, sister of Bahram Chobin, peerless in battle.
                - **Anahita:** The goddess of water, purity, and fertility.
                - **Pasargadae:** The capital of Cyrus the Great, symbol of Achaemenid glory.
                - **Youtab:** Sister of Ariobarzanes, a brave female commander fighting Alexander.
                - **Zarbanu:** An Iranian heroine and Rostam's daughter who fought alongside men.
                - **Artemis:** The world's first female admiral who commanded Xerxes' navy.
                - **Atossa:** A powerful Achaemenid queen, daughter of Cyrus the Great.
                - **Artadokht:** An economist and treasury minister in the Parthian era.
                - **Azaranahid:** One of the powerful and influential queens of the Sassanid Empire.
                - **Cassandane:** The beloved wife of Cyrus the Great and his advisor.
            """.trimIndent()
        ),
        HelpArticle(
            id = "sni_engine",
            title = "How to Setup SNI Anti-Filter Engine (Emergency Level 3)",
            icon = Icons.Default.RocketLaunch,
            content = """
                **How to get high-speed, uncensored internet using the SNI Anti-Filter Engine?**
                
                If you are looking to bypass censorship with the highest possible speed and stability, the SNI system is one of the best methods. Follow these simple steps:
                
                1. **Enter Emergency Mode:** From the app's main drawer, select "Emergency Level 3 (SNI)".
                2. **Scan and Initial Connect:** Tap the WiFi (Scan/Ping) icon at the top to find the best routes. Then tap the large "Connect" button to start the anti-filter engine in the background.
                3. **Return to Connect Tab:** Leave this screen and go back to the app's main tab (Connect tab).
                4. **Add SNI Configs:** At the top of the screen, tap the "Add (+)" button and select "Add SNI Config".
                5. **Set Quantity:** In the dialog, select the number of configs you want to generate (we recommend setting it to maximum) and tap Add.
                6. **Test and Final Connection:** Go to the "Nodes" tab and **make sure to ONLY use the "Delay" test**. It doesn't matter what delay number you get; this test is purely to identify which configs are successfully connected. However, choosing a lower number can be slightly better. Once you get a green delay number, connect to it.
                
                ✅ **Congratulations! Your internet is now space-speed! 😍**
            """.trimIndent()
        ),
        HelpArticle(
            id = "sublink_generator",
            title = "How to Create Sub Links with Cloudflare",
            icon = Icons.Default.Link,
            content = """
                **How to create a dedicated Subscription Link for friends?**
                
                Using the new "Create Sub Link" feature, you can provide your configs as a permanent link to your friends. They can add it to their V2ray clients (like V2rayNG) and always have access to your latest configs.
                
                **Steps to create a sub link:**
                1. From the home screen, tap the **"Sub Link"** icon.
                2. If your Cloudflare account is not connected, you must connect it in the Cloud tab.
                3. On your first visit, tap **"Deploy System"**. The app will automatically build a high-speed worker and setup KV storage for you on Cloudflare.
                4. After deployment, tap the (+) button to create a new link.
                5. Enter a name for the link, a short custom slug for the address, and select a config group (e.g. BPB or VLESS).
                6. You can set an **Expiry date (in days)**. After this time, the link will stop working and users will receive an "Expired" message.
                7. Copy the link and share it with your friends.
                
                **Auto-Sync and Stats:**
                - Whenever the configs in that group change (e.g. you run a new scan), the sub link will be updated **automatically in the background**. Your friends just need to tap Update in V2rayNG to get the new configs.
                - By tapping the **"Stats (📊)"** button next to each link, you can see how many times that link has been updated and downloaded by others!
            """.trimIndent()
        ),
        HelpArticle(
            id = "dedicated_dns",
            title = "Dedicated DNS for Lower Game Ping",
            icon = Icons.Default.Dns,
            content = """
                **What is Dedicated DNS?**
                A fully private DNS resolver deployed on **your own** Cloudflare account (not a shared one), dedicated to you alone. Completely free, no international bank card required.

                **Why does it need a Cloudflare account?**
                It must run on your own infrastructure so no one else shares it and its traffic/quota is yours only » exactly like the EDG or Nahan panels you may have deployed before. So first connect a Cloudflare account in the **Cloud tab**.

                **How is it different from a normal DNS?**
                A normal DNS (like 1.1.1.1) just returns the game server address. This dedicated DNS uses a technique called **ECS (EDNS Client Subnet)** to tell the upstream resolver "answer as if I'm in region X," so it returns the closest, lowest-latency game server for that region. That means game packets travel a shorter path and ping drops.

                **Auto mode:**
                The app races several regions in parallel, actually pings the returned servers, picks the fastest region, and remembers it **separately for each game**.

                **Manual mode:**
                You pick one fixed region yourself (e.g. UAE or Turkey) and it's always used.

                **Why is UAE the default?**
                It's usually the closest, lowest-latency region to Iran-based players for most game servers; if no better route is found, UAE is chosen.

                **Important:**
                This applies automatically across **all boost modes** (Direct, Tunnel, WARP, Hybrid) and **every game in the list**, with no per-game setup. Just tap "Deploy Dedicated DNS" once in the Gaming tab.
            """.trimIndent()
        ),
        HelpArticle(
            id = "domain_fronting",
            title = "Domain Fronting (server-less) — installing and removing the certificate",
            icon = Icons.Default.Shield,
            content = """
                **What is this?**
                A config that opens YouTube, Instagram, WhatsApp, Facebook and Reddit with **no server and no Cloudflare worker anywhere in the path**. Traffic goes straight from your phone to the real server of the site, just under a different, unblocked name. So there is nothing to deploy, nothing to pay for, and it does not slow down as more people use it.

                **⚠️ Important limitation — read before anything else:**
                This works **in a browser only** (Chrome, Edge, Brave, any Chromium-based browser). **The YouTube and Instagram apps do not work with this method.** That is an Android 7+ restriction: Android does not let ordinary apps trust certificates the user installed. There is no way around it short of rooting the device. If your goal is to make the apps themselves work, use the cloud-panel configs or the built-in Iran configs instead.

                **Setup (a few minutes, once):**
                1. **Connection** tab → open the **"دامین‌فرانتینگ (بدون سرور)"** (Domain Fronting) folder.
                2. Tap **"Start setup"**. The app mints a certificate unique to this device and adds the config to the same folder.
                3. Tap **"Save certificate to Downloads"**. The file `MLM-VPN-Certificate.crt` lands in your Download folder.
                4. Tap **"Open Android settings"** and install it using the path for your phone brand (below).
                5. Come back to the app. Step 2 **ticks itself** and the buttons disappear. No need to restart the app.
                6. Select the config in that folder and connect. Now open the sites **in Chrome**.

                **🔒 Security warning (take this seriously):**
                The certificate the app creates belongs to **this device only** and is never sent anywhere. **Never install someone else's certificate file**, and **never give yours to anyone.** Whoever holds your key file can read your banking and email traffic. That is exactly why no ready-made certificate ships inside the app and every device generates its own.

                ────────────────────────
                **📥 Install path by brand**

                Fastest route on any phone: use the **Settings search** and type `certificate` or `credentials`.

                • **Samsung (One UI):** Settings → Biometrics and security → Other security settings → Install from device storage → CA certificate
                • **Xiaomi / Redmi / Poco (MIUI, HyperOS):** Settings → Passwords & security → System security / Privacy → Encryption & credentials → Install a certificate from storage
                • **Pixel / stock Android (12 and up):** Settings → Security & privacy → More security settings → Encryption & credentials → Install a certificate → CA certificate
                • **Pixel / stock Android (11):** Settings → Security → Encryption & credentials → Install a certificate → CA certificate
                • **Huawei / Honor (EMUI, MagicOS):** Settings → Security → More settings → Encryption and credentials → Install certificates from storage
                • **Oppo / Realme / OnePlus (ColorOS, OxygenOS):** Settings → Password & security → System security → Encryption & credentials → Install from storage
                • **Vivo (Funtouch OS, OriginOS):** Settings → More settings → Security & privacy → Encryption & credentials → Install a certificate
                • **Motorola, Nokia, Asus, Sony:** same as stock Android

                When asked what kind of certificate it is, pick **CA certificate** (not VPN, not Wi-Fi). If it warns you, tap **Install anyway**. Finally pick `MLM-VPN-Certificate.crt` from the **Download** folder.

                ────────────────────────
                **🗑 Removal path by brand**

                Remove the certificate whenever you stop using this method, or after generating a new one so the old entry does not linger. An installed certificate stays until you remove it yourself.

                Fastest route on any phone: search Settings for `trusted credentials`, then open the **User** tab. Our certificate is listed as **"MLM VPN Local CA …"** — tap it and choose **Remove**.

                • **Samsung (One UI):** Settings → Biometrics and security → Other security settings → **View security certificates** → **User** tab → select it → **Remove**
                • **Xiaomi / Redmi / Poco:** Settings → Passwords & security → Privacy / System security → Encryption & credentials → **Trusted credentials** → **User** tab → remove
                • **Pixel / stock Android (12 and up):** Settings → Security & privacy → More security settings → Encryption & credentials → **Trusted credentials** → **User** tab → remove
                • **Pixel / stock Android (11):** Settings → Security → Encryption & credentials → Trusted credentials → User tab → remove
                • **Huawei / Honor:** Settings → Security → More settings → Encryption and credentials → Trusted credentials → User tab → remove
                • **Oppo / Realme / OnePlus:** Settings → Password & security → System security → Encryption & credentials → Trusted credentials → User tab → remove
                • **Vivo:** Settings → More settings → Security & privacy → Encryption & credentials → Trusted credentials → User tab → remove

                **Shortcut to wipe all user certificates at once:** the same "Encryption & credentials" screen has **Clear credentials**. Note this removes **every** user-installed certificate, not just ours.

                After removal, step 2 in the Domain Fronting folder un-ticks itself and the install buttons come back. You can also delete `MLM-VPN-Certificate.crt` from your Download folder.

                ────────────────────────
                **Troubleshooting**

                • **Step 2 never ticks:** Android has not accepted the certificate yet. Make sure you chose **CA certificate** (not VPN or Wi-Fi) and picked the right file (`MLM-VPN-Certificate.crt`).
                • **Android asks you to set a screen lock first:** that is Android's own requirement — no PIN/pattern/fingerprint, no certificate install.
                • **"CA certificates could not be installed … from null":** you took the wrong route. Android 11+ does not let apps install certificates themselves; it has to be installed from **Settings**, from the **file** — exactly what steps 3 and 4 above do.
                • **It connects but sites do not open:** you are probably using the app rather than a browser. Try Chrome.
                • **Does not work in Firefox:** Firefox ignores user-installed certificates by default. About Firefox → tap the logo five times → Settings → Secret Settings → turn on **Use third party CA certificates**.
                • **Stopped working after generating a new certificate:** remove the old one in Settings and install the new one; two identically named entries get confusing.
                • **The config will not connect:** the local port must be `10808`. The app sets this itself, but restore it if you changed it manually.
            """.trimIndent()
        ),
        HelpArticle(
            id = "quick_connect",
            title = "Quick Connect — the ready-made server list and country picker",
            icon = Icons.Default.FlashOn,
            content = """
                **What is this?**
                Quick Connect is the first icon on the app's home screen. It carries a ready-made list of thousands of public servers that the app downloads itself. **No account, no panel to deploy, no config to paste, and no cost.**

                This is the simplest way to get online. The rest of the app (cloud panel, scanner, nodes) is for when you want a server of your own; Quick Connect is for when you just want to be online right now.

                **The simplest case — one tap**
                Open the app and press the **big button in the middle of the screen**. That is all.

                • **If you have saved servers:** the app does not touch the pool at all. It tests just your own list, **in parallel** (a few seconds), and connects to the fastest live one. They are re-tested rather than trusted, because a server may have died since yesterday.
                • **If you have no servers yet:** the app searches the pool and **connects to the first good server it finds** — not the first one that merely answers. If its delay is under the threshold it connects immediately; if it is slow, it keeps looking for a few more seconds and then settles for the best it found. That server is saved, so the next press uses the first case above.

                To disconnect, press the same button again.

                **The connect button has four states, each with its own look**
                • **Ready (grey, breathing slowly):** you are not connected. Press to connect.
                • **Searching / connecting (blue, rotating arc):** the app is working. The line under the button says exactly which stage it is in. If it is searching and you change your mind, press again to cancel.
                • **Connected (green, a full steady ring):** you are online. It does not move, because motion on a settled state is just noise.
                • **Disconnecting (red, an arc sweeping the other way).**

                **"My servers" — the list under the button**
                Every server that has been tested and worked stays here, and is still there after the app is closed. Sorted fastest first.
                • Tap a server's name to connect straight to it.
                • The **refresh** icon on a row re-tests that one server.
                • The **cross** icon on a row deletes it.
                • The **network-check** icon in the header re-tests them all, with a progress bar.
                • The **bin** icon in the header asks whether you mean "only the dead ones" or "all".
                • The **"new"** badge means it was just added and you had not seen it yet; **"down"** means the last test failed.

                **Important:** a server you delete **does not come back** — it is not shown, not tested, and not re-downloaded on later refreshes. So delete dead servers freely. (If you change your mind, the "restore" button is at the bottom of the Server list screen.)

                **The "Server list" screen — when you want a specific country**
                At the bottom of the connect screen, press **"Server list and country check"**.
                1. Tap the **"All countries"** box. A flag list opens, each row showing how many servers that country has, with a **country search** box at the top.
                2. Press one of these:
                   • **"Quick check"** — finds up to 20 working servers and stops. For when you just want to be online.
                   • **"Check every server"** — tests every single server in that country. More thorough, but if the country has thousands it takes minutes. You can press "Stop" at any moment.
                3. Once tested, the green **"Add N servers to the connect screen"** button moves every result into "My servers".

                On that screen each row is marked **"already tested"** or **"new"**, so re-checking a country shows at a glance what it actually added.

                **You can leave the screen mid-check:** the search runs independently of the screen, so back simply takes you out of it and the check carries on in the background. Whenever you come back, the same run is there with its results so far. To end it, press **"Stop"**.

                **Targeted search — "50 servers from 5 countries"**
                Lower on the same screen there is a panel where you pick a **server count** (10/25/50/100) and a **country count** (1/3/5/10), then press the button. The app returns exactly that many, every one proven by a real connection, spread evenly across the countries — "50 servers from 5 countries" means 10 from each, not 50 that happen to all come from one.

                The countries are checked concurrently rather than one after another, and a country that cannot fill its share is topped up from the others. The countries are chosen automatically: the ones with the most servers, since a country holding 12 servers cannot reliably supply a share of 10.

                **What the search does, and why it has two stages**
                The progress strip shows two stages:
                • **Stage 1 — reachability:** it only checks whether the server's port is open at all. This is very cheap, and most dead servers are eliminated here.
                • **Stage 2 — a real connection:** only the survivors attempt a real connection. This stage is necessary because on a filtered line **most hosts accept the connection and then carry nothing** — only stage 2 proves the server actually works.

                Servers that answered fastest are tried first, so a few good ones usually appear within the first seconds. You can press **"That's enough"** at any moment; the search ends and everything found so far stays.

                **Where do the flags come from?**
                A server's country is read from **its own name**: a flag character inside the name, patterns like "DE1" and "NL12", or a country or city name (Frankfurt, Amsterdam, Istanbul and so on). All of this happens on the phone with no network request, which is why the flags appear instantly.

                An IP database is deliberately not used: across a 2827-server feed it matched the server's real country only 34% of the time. Cloudflare addresses, for instance, are registered in the US but answer from Frankfurt.

                **What does the green tick next to a server name mean?**
                It means that server's country is no longer a claim — it has been **measured**. After you actually connect to a server, the app asks Cloudflare, through that same tunnel, where the traffic came out. If the answer differs from the previous flag, you get a message saying that server has been moved to its real country.

                The result is stored, so from then on that server only appears under its real country. The reason: when someone picks a country, the point is to **come out in that country** — a server filed under the wrong flag breaks the one thing this list exists for.

                **The green banner above the list after connecting**
                • "Real exit: 🇩🇪 Germany · FRA · …" means your traffic really does leave from Germany.
                • "Route verified (WARP)" means the connection is up but the exit country cannot be proven this way — when the path runs over WARP, Cloudflare **deliberately reports your own country**, not the server's. No country is recorded in that case, so a wrong result never enters the list.

                **The refresh button (top of the Server list screen)**
                The list is kept on the phone for half an hour so that opening the screen does not re-download several megabytes each time. Press this button if you want a fresh list right now.

                If the fresh download fails you will see the yellow "showing the saved list" notice and **the previous list stays** — yesterday's servers can get you online, a failed download cannot.

                **Where do the servers come from, and how often do they refresh?**
                The list is built from five public sources that refresh **every 15 minutes**. Four of them come from an aggregator that merges around 21 public sources and files them by its own test results (verified, fast, secure, all). Servers appearing in more than one source are counted once. At the bottom of the Server list screen you can see how many each source contributed and which ones failed.

                Supported protocols: **VLESS, Trojan, VMess and Shadowsocks**. (Shadowsocks configs needing a side plugin are deliberately refused, because the core will start them and then carry no traffic.)

                **Common problems**
                • **"No reachable server found"** — usually the country you chose has few healthy servers. Switch to "All countries" and try again.
                • **"No servers were received"** — the list source is blocked. Connect one of the anti-sanction options or an Iran config first, then press refresh.
                • **It connects but sites do not open** — change server; this is a public list and its quality is not guaranteed. For something more stable, use your own cloud panel.
                • **A server's flag is wrong** — connect to it once; it corrects and records itself.
                • **I want all corrections cleared** — open the country list and press "Clear measured countries" at the bottom.

                **Where did the MLM Gateway go?**
                The MLM Gateway (VPN Gate) has **its own icon on the home screen** now. It is otherwise unchanged.
            """.trimIndent()
        ),
        HelpArticle(
            id = "home_screen",
            title = "The home screen — icons, dock, wallpaper",
            icon = Icons.Default.Apps,
            content = """
                **The hamburger drawer is gone — everything it held is on the home screen**

                No feature is hidden behind a menu any more. Fixed IP, Workers, anti-sanction DNS,
                Sub Link, Usage, Tutorials, About, all three emergency modes, MLM Gateway, Quick
                Connect, Free Configs, Cloud and Game Boost all have icons on the home screen.

                **The dock**

                The four you reach for most sit in a glass bubble at the bottom: **Scanner, V2Ray,
                Settings and WireGuard**. Like an iPhone, the dock is only on the home screen; open
                a feature and it takes the full screen, and the phone's back button or the arrow at
                the top brings you home.

                **Rearranging the icons**

                1. **Hold** your finger on any icon until the icons start to wobble.
                2. **Drag** it wherever you want; the others move out of the way.
                3. Let go — the order is saved right then.
                4. Leave the mode with **"Done"** at the top, or the phone's back button.

                Your layout survives closing the app, and if a later update adds a feature its icon
                simply joins the end of your layout rather than resetting it. The dock is fixed and
                does not rearrange.

                **Three more settings under Display**

                • **Icon Size** scales the icons only; captions and spacing stay put. A live sample
                above the slider shows what you are getting.
                • **Wallpaper on every screen** can be turned off to give every screen except the
                home screen a plain background.
                • **Appearance** switches between light, dark and automatic. In this release only the
                rebuilt screens follow it; the rest are still dark and are being converted one at a
                time.

                **Settings**

                Settings is grouped the iOS way too: **Network** (local port, DNS, proxy mode, local
                network, advanced VPN settings), **Display** (wallpaper, traffic counters, language),
                **Usage**, **System** and **About**.

                At the top, where iOS shows your Apple Account, sits **your connected Cloudflare
                account**. If you have one connected you see its name and email; if not, tapping the
                card takes you to the Cloud tab to connect one.

                **There is no Save button any more.** Every change applies the moment you make it.
                The one exception is the local port: while the number you have typed is not valid it
                turns red, says why underneath, and is not saved.

                **Changing the wallpaper**

                Go to **Settings > Display > Wallpaper**. Ten ready-made backdrops are there — Dusk,
                Amber, Indigo, Charcoal, Morning Mist, Violet, Ocean, Slate, Sunset and Forest —
                and a tap applies one. The first tile, **"From gallery"**, lets you use your own photo.

                The photo picker is **Android's own**, not the app's: the app never asks for gallery
                access and only ever receives the single image you chose. That image is copied into
                the app, so deleting it from your gallery later does not take your wallpaper with it.

                **The strip at the top of the home screen**

                The shield shows connection state (green means connected), and the other side shows
                this session's live traffic. Turn the counters off in Settings if you would rather
                not see them.

                **Common questions**

                • **Where did the Game Boost icon come from?** It used to be off behind a switch in
                Settings because the old bottom bar had no room for it. It has a permanent home now,
                and that switch is gone.
                • **I lost an icon.** You probably moved it while rearranging; hold and drag it back.
                Nothing is ever removed — only moved.
                • **The page does not scroll.** Correct; every icon fits on one screen.
            """.trimIndent()
        )
    )
}

data class FaqItem(val q: String, val a: String)

fun getFaqsFa(): List<FaqItem> = listOf(
    FaqItem(S(R.string.why_will_the_new_version_not_install), S(R.string.that_is_usually_a_clash_with_the)),
    FaqItem(S(R.string.does_the_app_work_on_old_android), S(R.string.the_app_is_tuned_for_most_android)),
    FaqItem(S(R.string.can_it_be_installed_on_an_android), S(R.string.yes_since_version_1_0_5_it)),
    FaqItem(S(R.string.why_does_nothing_happen_after_the_ip), S(R.string.that_can_be_down_to_poor_internet)),
    FaqItem(S(R.string.why_do_i_have_a_green_ping), S(R.string.a_green_ping_does_not_always_mean)),
    FaqItem(S(R.string.in_the_nhn_section_when_i_create), S(R.string.that_was_fixed_in_update_1_0)),
    FaqItem(S(R.string.what_does_an_invalid_api_token_error), S(R.string.that_error_means_your_cloudflare_token_is)),
    FaqItem(S(R.string.is_there_a_per_app_setting_choosing), S(R.string.yes_the_app_has_it_go_to)),
    FaqItem(S(R.string.why_are_videos_and_bots_slow_to), S(R.string.that_is_usually_down_to_dns_or)),

    FaqItem(S(R.string.how_does_the_quick_connect_button_in), S(R.string.you_can_add_the_mlmvpn_tile_to)),

    FaqItem(S(R.string.how_do_i_remove_the_domain_fronting), S(R.string.in_your_phone_s_settings_search_type)),

    FaqItem(S(R.string.i_connected_with_domain_fronting_but_the), S(R.string.nothing_is_wrong_this_method_was_only)),

    FaqItem(S(R.string.while_installing_the_certificate_i_get_couldn), S(R.string.that_message_means_you_took_the_wrong)),

    FaqItem(S(R.string.the_install_certificate_step_will_not_tick), S(R.string.it_means_android_has_not_accepted_the)),

    FaqItem(S(R.string.is_installing_this_certificate_dangerous_for_my), S(R.string.the_certificate_the_app_builds_on_your)),

    FaqItem(S(R.string.domain_fronting_does_not_work_in_firefox), S(R.string.firefox_ignores_user_installed_certificates_by_default)),

    FaqItem(S(R.string.how_do_iran_configs_7_and_8), S(R.string.numbers_1_to_6_are_built_on)),

    FaqItem(S(R.string.which_mode_should_i_use_for_what), S(R.string.iran_configs_1_to_8_no_server)),

    FaqItem(S(R.string.why_did_youtube_and_instagram_videos_used), S(R.string.because_the_app_used_to_send_quic)),

    FaqItem(S(R.string.why_were_the_delay_and_speed_numbers), S(R.string.the_delay_test_opens_a_real_connection)),

    FaqItem(S(R.string.what_is_quick_connect_and_how_does), S(R.string.quick_connect_is_one_of_the_home)),

    FaqItem(S(R.string.where_is_the_mlm_gateway_vpn_gate), S(R.string.on_the_home_screen_an_icon_named)),

    FaqItem(S(R.string.in_quick_connect_what_does_the_green), S(R.string.it_means_that_server_s_country_is)),

    FaqItem(S(R.string.after_connecting_why_does_it_say_route), S(R.string.because_when_the_route_goes_through_cloudflare)),

    FaqItem(S(R.string.in_quick_connect_i_get_no_responding), S(R.string.that_usually_means_the_country_you_picked)),

    FaqItem(S(R.string.what_is_the_local_port_in_settings), S(R.string.it_is_the_port_the_app_opens)),

    FaqItem(S(R.string.i_changed_the_local_port_and_now), S(R.string.go_back_to_settings_and_set_the)),

    FaqItem(S(R.string.why_do_you_not_use_an_ip), S(R.string.because_it_is_not_accurate_we_tested))
)

fun getFaqsEn(): List<FaqItem> = listOf(
    FaqItem("Why can't I install the new version, getting a \"Package conflicts\" error?", "This usually happens due to a conflict with the previous version. Please completely uninstall the old version first, then install the new one."),
    FaqItem("Does the app work on older Android versions or very new ones (like Android 14 and 15)?", "The app is optimized for most Android versions. If you encounter issues on specific versions, ensure you are using the latest update or try clearing the app cache."),
    FaqItem("Can it be installed on Android Smart TVs?", "Yes, since version 1.0.5, the app is fully optimized for Android Smart TVs."),
    FaqItem("Why does nothing happen after IP scanning finishes, and no IPs or mixed configs are shown?", "This could be due to poor internet quality during the scan or operator restrictions. We suggest testing with another operator or running the scanner again. However, the main issue is usually using inappropriate configs; you must strictly use configs generated from NHN, Edge, or BPB panels, specifically VLESS configs with port 443."),
    FaqItem("Why do I only have upload but no download despite having a green ping?", "A green ping doesn't always mean data is passing through. The scanned IP might be half-open or the port might be blocked by your operator. Try other IPs."),
    FaqItem("Why doesn't the output config work when I create a user with specific volume/time in the NHN panel?", "This issue has been resolved in the new 1.0.5 update."),
    FaqItem("What does the \"Invalid API Token\" or code 9103 error mean?", "This error means your Cloudflare token is either incorrect or expired. Please recreate the token following the video tutorials and enter it again in the app."),
    FaqItem("Is there a \"Per-app proxy\" feature to select specific apps to bypass the VPN?", "Yes, this feature is available. Please go to App Settings > VPN Settings."),
    FaqItem("Why is loading videos or bots slow in Telegram or Instagram even though I'm connected?", "This is usually due to DNS or MTU settings. We've tried to improve this in newer versions, but changing the protocol (e.g., from BPB to Edge or vice versa) can help."),

    FaqItem("How does the Quick Settings VPN tile work?", "You can add the 'mlmvpn' tile from your phone's Quick Settings panel (swipe down from the top → edit/add tiles). One tap toggles the VPN; when turning on, it delay-tests your most recent servers and connects to the fastest. You can change how many servers are tested (default 20) in Settings → VPN Settings. Note: the first time, connect once from inside the app to grant VPN permission."),

    FaqItem("What is \"Quick Connect\" and how is it different from the rest of the app?", "Quick Connect is one of the icons on the app's home screen: a ready-made list of thousands of public servers that the app downloads itself. No account, no panel to deploy, no config to paste. The difference from the cloud panel is that there the server is your own, on your own Cloudflare account (more stable, but you have to deploy it once), while here the servers are public and shared (instant, but their quality is not guaranteed). Full walkthrough in Tutorials, number 18."),

    FaqItem("Where is the MLM Gateway (VPN Gate)?", "On the home screen, as an icon labelled \"MLM Gateway\". It is otherwise unchanged and every one of its features is where it was. If you cannot find it, you may have moved it yourself."),

    FaqItem("In Quick Connect, what does the green tick next to a server mean?", "It means that server's country is no longer the list's claim — it has been measured. After you actually connect, the app asks Cloudflare through that same tunnel where the traffic came out. If the answer differs from the previous flag, the server moves to its real country for good and you are told. The reason: when someone picks a country, the point is to come out in that country."),

    FaqItem("After connecting it says \"Route verified (WARP)\" and shows no country. Why?", "Because when the path runs over Cloudflare WARP, Cloudflare deliberately reports YOUR country rather than the server's — a session from Tehran that really does exit in Frankfurt reads as \"Iran\". So no country is recorded in that case; otherwise every server would be wrongly filed under Iran. Your connection is perfectly fine, the exit country just cannot be proven this way."),

    FaqItem("Quick Connect says \"No reachable server found\". What should I do?", "Usually it means the country you picked has few healthy servers. Set the country box to \"All countries\" and search again. If the message is \"No servers were received\" the problem is different: the list source is blocked, so connect one of the anti-sanction options or an Iran config first, then press the refresh button next to the country picker."),

    FaqItem("What is \"Local Port\" in settings, and what should I set it to?", "It is the port the app opens on the phone itself to carry traffic. The default is 10808 and there is no reason to change it unless you have a specific one. The thing to know is that besides the number you enter, the app also uses \"that number + 10000\" (for the connection-status and country check). That is why some numbers cause trouble despite looking valid \u2014 21000, for instance, collides with the range the app uses for testing servers. If you enter an unsuitable number, the problem is explained right under the field and Save stays disabled until you fix it."),

    FaqItem("I changed the local port and now every server tests as \"down\". What do I do?", "Go back to settings and set the local port to 10808. In earlier versions this field accepted any number, and some numbers silently collided with the app's internal ports \u2014 which showed up as exactly this: every server testing as dead, or the country never appearing in the status bar. This version refuses such a number outright, but a bad value saved by an earlier version has to be corrected by hand once."),

    FaqItem("Why don't you use an IP database for server flags?", "Because it is not accurate. We tested it across a 2827-server feed: an IP database agreed with the country the server itself declared only 34% of the time. Cloudflare addresses are registered in the US but answer from Frankfurt, and OVH and Oracle ranges are registered in one country and served from another — the database tells you where a range was registered, not where it answers. Instead the country is read from the server's own name (instant, no network) and then corrected by a real measurement after you connect.")
)
