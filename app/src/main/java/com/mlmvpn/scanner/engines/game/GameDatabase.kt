package com.mlmvpn.scanner.engines.game

import android.content.Context
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

// اطلاعات هر بازی
data class GameInfo(
    val id: String,
    val name: String,
    val packageName: String,
    val alternatePackages: List<String> = emptyList(),
    val iconEmoji: String,
    val servers: List<GameServer>,
    val defaultRegion: String = "ME",
    val category: String = "shooter"
)

// اطلاعات سرور هر بازی
data class GameServer(
    val region: String,
    val displayName: String,
    val testEndpoints: List<String>,
    val port: Int = 443
)

// نتیجه تست بوست
data class BoostResult(
    val mode: BoostMode,
    val pingMs: Long,
    val jitterMs: Long = 0,
    val nodeId: String? = null,
    val nodeName: String? = null,
    val nodeUri: String? = null,
    val details: String = ""
)

enum class BoostMode {
    // NOTE: DIRECT, TUNNEL and WARP are retired from the Game tab (removed from the UI and from
    // the AUTO race). Kept in the enum only so old persisted values/when-branches don't break.
    // The old two hardcoded UAE trial servers ("سرور اول" = WIREGUARD/AmneziaWG trial, "سرور دوم"
    // = WARP_FREE) are gone entirely -- AETHER replaces both with the Aether engine, which picks
    // a healthy endpoint itself from Cloudflare's WARP pool instead of pointing at one fixed host.
    DIRECT,        // (retired) Direct Boost
    TUNNEL,        // (retired) Tunnel Turbo (VLESS/Trojan)
    WARP,          // (retired) WARP خودکار — سیستم چندموتوره (usque/warp-plus)
    DEDICATED_DNS, // DNS اختصاصی کلادفلر (ورکر شخصی + انتخاب کشور)
    // (retired) DNS اختصاصی امارات. The UAE server behind it was decommissioned, so the mode is
    // gone from the UI and from the AUTO race. The value stays only so persisted selections and
    // exhaustive when-branches keep compiling, same as DIRECT/TUNNEL/WARP above.
    UAE_DNS,
    AETHER,        // موتور Aether — تونل کامل دستگاه (MASQUE/WireGuard/WARP-in-WARP)، بدون سرور ثابت
    AUTO           // Auto — بهترین بین کلادفلر/امارات/Aether بر اساس پینگ واقعی
}

// وضعیت کلی Game Booster
enum class BoosterState {
    IDLE,           // آماده
    TESTING,        // در حال تست
    BOOSTED,        // بوست شده و فعال
    CONNECTING,     // در حال اتصال
    FAILED          // خطا
}

object GameDatabase {
    
    val games: List<GameInfo> = listOf(
        // ────────────────── Call of Duty Mobile ──────────────────
        GameInfo(
            id = "codm",
            name = "Call of Duty Mobile",
            packageName = "com.activision.callofduty.shooter",
            alternatePackages = listOf("com.garena.game.codm"),
            iconEmoji = "🔫",
            category = "shooter",
            // NOTE: the old prod.atviservices.com / conntest.activision.com hostnames are dead
            // (NXDOMAIN). These Akamai-fronted endpoints resolve; note CoD is behind Akamai which
            // maps by resolver location and doesn't always honour our ECS override, so region
            // steering has less effect here than for CloudFront-fronted games (PUBG/Blood Strike).
            servers = listOf(
                GameServer("ME", S(R.string.middle_east), listOf("profile.callofduty.com", "cod.activision.com"), 443),
                GameServer("EU", S(R.string.europe), listOf("profile.callofduty.com", "callofduty.com"), 443)
            )
        ),
        
        // ────────────────── PUBG Mobile ──────────────────
        GameInfo(
            id = "pubg",
            name = "PUBG Mobile",
            packageName = "com.tencent.ig",
            alternatePackages = listOf("com.pubg.krmobile", "com.rekoo.pubgm", "com.pubg.imobile"),
            iconEmoji = "🪖",
            category = "battle_royale",
            // pubgmobile.com is AWS CloudFront-fronted and steers cleanly via ECS (different edge
            // IP per region), so it's a strong signal for region comparison.
            servers = listOf(
                GameServer("ME", S(R.string.middle_east), listOf("pubgmobile.com", "www.pubgmobile.com"), 443),
                GameServer("EU", S(R.string.europe), listOf("pubgmobile.com"), 443)
            )
        ),
        
        // ────────────────── EA SPORTS FC Mobile (was FIFA Mobile) ──────────────────
        // Third because it is one of the three games most of the app's players play. The id and
        // package keep their FIFA-era names: both are persisted, and EA kept the package.
        GameInfo(
            id = "fifamobile",
            name = "EA SPORTS FC Mobile",
            packageName = "com.ea.gp.fifamobile",
            iconEmoji = "⚽",
            category = "sports",
            // Akamai-fronted -- resolves fine, but region steering has less effect than CloudFront.
            servers = listOf(
                GameServer("ME", S(R.string.middle_east), listOf("fifa.ea.com", "accounts.ea.com"), 443),
                GameServer("EU", S(R.string.europe), listOf("fifa.ea.com"), 443)
            )
        ),

        // ────────────────── Project R.I.S.E ──────────────────
        // A Tencent/Level Infinite title, so it sits behind the same CDN estate as their other
        // games: the endpoints below are the ones the client actually resolves at login, which is
        // where a region choice makes a difference. Two regions only -- the ME and EU edges are
        // the pair reachable from Iran with meaningfully different RTT.
        GameInfo(
            id = "rise",
            name = "Project R.I.S.E",
            packageName = "com.levelinfinite.projectrise",
            alternatePackages = listOf(
                "com.tencent.projectrise",
                "com.levelinfinite.rise",
            ),
            iconEmoji = "🛰️",
            category = "shooter",
            servers = listOf(
                GameServer("ME", S(R.string.middle_east), listOf("projectrise.levelinfinite.com", "levelinfinite.com"), 443),
                GameServer("EU", S(R.string.europe), listOf("levelinfinite.com", "projectrise.levelinfinite.com"), 443)
            )
        ),

        // ────────────────── Mobile Legends: Bang Bang ──────────────────
        GameInfo(
            id = "mlbb",
            name = "Mobile Legends",
            packageName = "com.mobile.legends",
            iconEmoji = "⚔️",
            category = "moba",
            servers = listOf(
                GameServer("ME", S(R.string.middle_east), listOf("moba.mobilelegends.com", "api.mobilelegends.com"), 443),
                GameServer("AS", S(R.string.asia), listOf("api.mobilelegends.com", "moba.mobilelegends.com"), 443)
            )
        ),
        
        // ────────────────── Free Fire ──────────────────
        GameInfo(
            id = "freefire",
            name = "Free Fire",
            packageName = "com.dts.freefireth",
            alternatePackages = listOf("com.dts.freefiremax"),
            iconEmoji = "🔥",
            category = "battle_royale",
            servers = listOf(
                GameServer("ME", S(R.string.middle_east), listOf("auth.garena.com", "connect.garena.com"), 443),
                GameServer("AS", S(R.string.asia), listOf("connect.garena.com", "auth.garena.com"), 443)
            )
        ),
        
        // ────────────────── Clash Royale ──────────────────
        GameInfo(
            id = "clashroyale",
            name = "Clash Royale",
            packageName = "com.supercell.clashroyale",
            iconEmoji = "👑",
            category = "strategy",
            servers = listOf(
                GameServer("EU", S(R.string.europe), listOf("game.clashroyaleapp.com"), 9339)
            )
        ),
        
        // ────────────────── Clash of Clans ──────────────────
        GameInfo(
            id = "clashofclans",
            name = "Clash of Clans",
            packageName = "com.supercell.clashofclans",
            iconEmoji = "⚔️",
            category = "strategy",
            servers = listOf(
                GameServer("EU", S(R.string.europe), listOf("game.clashofclans.com"), 9339)
            )
        ),
        
        // ────────────────── Genshin Impact ──────────────────
        GameInfo(
            id = "genshin",
            name = "Genshin Impact",
            packageName = "com.miHoYo.GenshinImpact",
            iconEmoji = "🌟",
            category = "rpg",
            servers = listOf(
                GameServer("AS", S(R.string.asia), listOf("dispatchosglobal.yuanshen.com", "osasiadispatch.yuanshen.com"), 443),
                GameServer("EU", S(R.string.europe), listOf("oseurodispatch.yuanshen.com"), 443)
            )
        ),
        
        // ────────────────── Brawl Stars ──────────────────
        GameInfo(
            id = "brawlstars",
            name = "Brawl Stars",
            packageName = "com.supercell.brawlstars",
            iconEmoji = "💥",
            category = "shooter",
            servers = listOf(
                GameServer("EU", S(R.string.europe), listOf("game.brawlstarsgame.com"), 9339)
            )
        ),

        // ────────────────── Blood Strike ──────────────────
        GameInfo(
            id = "bloodstrike",
            name = "Blood Strike",
            packageName = "com.netease.newspike.na",
            alternatePackages = listOf("com.netease.newspike.gl"),
            iconEmoji = "🩸",
            category = "shooter",
            // Old netease newspike hostnames are dead (NXDOMAIN). bloodstrike.com is AWS-fronted
            // and steers via ECS (different edge IP per region).
            servers = listOf(
                GameServer("ME", S(R.string.middle_east), listOf("bloodstrike.com", "www.bloodstrike.com"), 443),
                GameServer("EU", S(R.string.europe), listOf("bloodstrike.com"), 443)
            )
        ),

        // ────────────────── Stumble Guys ──────────────────
        GameInfo(
            id = "stumbleguys",
            name = "Stumble Guys",
            packageName = "com.kitkagames.fallbuddies",
            iconEmoji = "🏃",
            category = "party",
            // CloudFront-fronted, steers cleanly via ECS (different edge IP per region).
            servers = listOf(
                GameServer("ME", S(R.string.middle_east), listOf("stumbleguys.com", "api.stumbleguys.com"), 443),
                GameServer("EU", S(R.string.europe), listOf("stumbleguys.com"), 443)
            )
        ),

        // ────────────────── Apex Legends Mobile ──────────────────
        GameInfo(
            id = "apexmobile",
            name = "Apex Legends Mobile",
            packageName = "com.ea.gp.apexlegendsmobilefps",
            iconEmoji = "🎯",
            category = "battle_royale",
            servers = listOf(
                GameServer("ME", S(R.string.middle_east), listOf("apexlegendsmobile.com", "accounts.ea.com"), 443),
                GameServer("EU", S(R.string.europe), listOf("apexlegendsmobile.com"), 443)
            )
        ),

        // ────────────────── eFootball ──────────────────
        GameInfo(
            id = "efootball",
            name = "eFootball",
            packageName = "jp.konami.pesam",
            iconEmoji = "⚽",
            category = "sports",
            servers = listOf(
                GameServer("ME", S(R.string.middle_east), listOf("efootball-web.konami.net", "www.konami.com"), 443),
                GameServer("EU", S(R.string.europe), listOf("efootball-web.konami.net"), 443)
            )
        ),

        // ────────────────── Roblox ──────────────────
        GameInfo(
            id = "roblox",
            name = "Roblox",
            packageName = "com.roblox.client",
            iconEmoji = "🧱",
            category = "sandbox",
            // gamejoin.roblox.com is the actual join server and geo-varies via ECS.
            servers = listOf(
                GameServer("ME", S(R.string.middle_east), listOf("gamejoin.roblox.com", "clientsettings.roblox.com"), 443),
                GameServer("EU", S(R.string.europe), listOf("gamejoin.roblox.com"), 443)
            )
        )
    )

    // پیدا کردن بازی‌های نصب شده روی دستگاه
    fun getInstalledGames(context: Context): List<GameInfo> {
        val pm = context.packageManager
        return games.filter { game ->
            val allPackages = listOf(game.packageName) + game.alternatePackages
            allPackages.any { pkg ->
                try { pm.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }
            }
        }
    }

    // همه بازی‌ها (نصب‌شده اول، بقیه بعد)
    fun getAllGamesSorted(context: Context): List<Pair<GameInfo, Boolean>> {
        val pm = context.packageManager
        return games.map { game ->
            val allPackages = listOf(game.packageName) + game.alternatePackages
            val isInstalled = allPackages.any { pkg ->
                try { pm.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }
            }
            Pair(game, isInstalled)
        }.sortedByDescending { it.second } // نصب‌شده‌ها اول
    }
}
