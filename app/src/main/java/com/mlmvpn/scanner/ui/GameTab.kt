package com.mlmvpn.scanner.ui

import com.mlmvpn.scanner.ui.theme.TextPrimary
import com.mlmvpn.scanner.ui.home.frostedGlass
import androidx.compose.foundation.border
import com.mlmvpn.scanner.ui.theme.YellowWarn
import com.mlmvpn.scanner.ui.theme.RedError
import com.mlmvpn.scanner.ui.theme.Primary
import com.mlmvpn.scanner.ui.theme.GreenOk
import com.mlmvpn.scanner.ui.theme.ControlShape
import com.mlmvpn.scanner.ui.theme.CardShape
import com.mlmvpn.scanner.ui.theme.BadgeShape
import com.mlmvpn.scanner.ui.settings.Ios
import androidx.compose.runtime.Composable
import com.mlmvpn.scanner.ui.theme.*
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.engines.game.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.mlmvpn.scanner.utils.S

/**
 * The game booster's palette.
 *
 * Every value forwards to the shared token that means the same thing. The names stay because they
 * are used at a hundred call sites and they still describe the role; only the literals are gone.
 * Those literals were a private set of flat greys with a Material Light Blue accent, which is why
 * this screen read as belonging to a different app than the one around it.
 *
 * Composable getters, because the tokens underneath read the active theme -- so this screen now
 * follows light mode too, which the hardcoded version could not.
 */
private object GameColors {
    /**
     * The page fill. Transparent: the host already paints the wallpaper and its scrim behind
     * every screen, and an opaque sheet here punched a hole through both.
     */
    val BgDark: Color
        @Composable get() = Color.Transparent

    /** Cards and panels: the glass the Settings groups use. */
    val SurfaceDark: Color
        @Composable get() = com.mlmvpn.scanner.ui.theme.SurfaceDark

    /** A panel nested inside another one. */
    val SurfaceVariant: Color
        @Composable get() = Color.White.copy(alpha = 0.07f)

    /** The boost accent. iOS green rather than Material A400, which was the loudest thing here. */
    val GameGreen: Color
        @Composable get() = GreenOk

    val GameGreenDim: Color
        @Composable get() = GreenOk

    val PrimaryBlue: Color
        @Composable get() = Primary

    val TextPrimary: Color
        @Composable get() = com.mlmvpn.scanner.ui.theme.TextPrimary

    val TextMuted: Color
        @Composable get() = com.mlmvpn.scanner.ui.theme.TextMuted

    val BorderDark: Color
        @Composable get() = com.mlmvpn.scanner.ui.theme.BorderDark

    // The ping scale keeps four distinct steps -- it is data, and a reader has to tell 55ms from
    // 140ms at a glance -- but each step is now the iOS colour of that severity.
    val PingGood: Color
        @Composable get() = GreenOk
    val PingMedium: Color
        @Composable get() = YellowWarn
    val PingBad: Color
        @Composable get() = Ios.Orange
    val PingTerrible: Color
        @Composable get() = RedError

    val Gold: Color
        @Composable get() = Ios.Yellow
}

@Composable
private fun pingColor(ping: Long): Color = when {
    ping < 0 -> GameColors.TextMuted
    ping < 60 -> GameColors.PingGood
    ping < 100 -> GameColors.PingMedium
    ping < 150 -> GameColors.PingBad
    else -> GameColors.PingTerrible
}

private fun pingLabel(ping: Long): String = when {
    ping < 0 -> "—"
    else -> "${ping}ms"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameTab(onBack: () -> Unit = {}, onNavigateToCloud: (() -> Unit)? = null) {

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val boosterManager = remember { GameBoosterManager(context) }

    // Dedicated DNS state
    val cloudManager = remember { com.mlmvpn.scanner.data.CloudManager(context) }
    val cloudAccounts by cloudManager.accountsFlow.collectAsState()
    var dnsDeployedLocally by remember { mutableStateOf(false) }
    var dnsRemovedLocally by remember { mutableStateOf(false) }
    val hasDnsWorker = !dnsRemovedLocally && (dnsDeployedLocally ||
        cloudAccounts.any { it.dnsStatus == "deployed" && !it.dnsWorkerUrl.isNullOrEmpty() })
    val hasAnyCloudAccount = cloudAccounts.isNotEmpty()
    val dnsPrefs = remember { context.getSharedPreferences("game_booster_prefs", android.content.Context.MODE_PRIVATE) }
    var dnsEnabled by remember { mutableStateOf(dnsPrefs.getBoolean("dedicated_dns_enabled", true)) }
    var dnsMode by remember { mutableStateOf(dnsPrefs.getString("dedicated_dns_mode", "auto") ?: "auto") }
    var dnsManualRegion by remember { mutableStateOf(dnsPrefs.getString("dedicated_dns_manual_region", com.mlmvpn.scanner.engines.game.DedicatedDnsResolver.DEFAULT_REGION) ?: com.mlmvpn.scanner.engines.game.DedicatedDnsResolver.DEFAULT_REGION) }
    var dnsDeploying by remember { mutableStateOf(false) }
    var dnsDeployProgress by remember { mutableStateOf(0 to "") }
    var dnsProbing by remember { mutableStateOf(false) }
    // Per-region comparison results from the "Test all regions" button (best-ping first).
    var dnsRegionResults by remember { mutableStateOf<List<com.mlmvpn.scanner.engines.game.DedicatedDnsResolver.RegionRaceResult>>(emptyList()) }
    // Bumped after a probe/deploy to force re-reading the per-game cached region/ping below.
    var dnsRefreshTick by remember { mutableStateOf(0) }

    // Which picker is open, if any. Pushed pages rather than a strip and a dropdown.
    var picker by remember { mutableStateOf<String?>(null) }

    val allGamesSorted = remember { GameDatabase.getAllGamesSorted(context) }
    var selectedGame by remember { mutableStateOf(allGamesSorted.firstOrNull { it.second }?.first ?: allGamesSorted.firstOrNull()?.first) }
    var selectedRegion by remember { mutableStateOf(selectedGame?.defaultRegion ?: "ME") }
    var selectedMode by remember { mutableStateOf(BoostMode.AUTO) }

    // Which Aether protocol a boost uses. Seeded from the pref so the choice survives leaving
    // the tab, and written back on every change — GameBoosterManager reads the pref rather
    // than this state, because the AUTO race builds its config without going through the UI.
    var aetherProtocol by remember {
        mutableStateOf(GameBoosterManager.gameAetherProtocol(context))
    }
    var aetherScan by remember {
        mutableStateOf(GameBoosterManager.gameAetherScan(context))
    }

    // Real connect progress from the engine, or null when nothing is in flight.
    val boostProgress by boosterManager.boostProgress.collectAsState()

    // If the DNS-only mode was selected but the worker got turned off/removed, fall back to AUTO
    // so the selector never points at a mode that's no longer offered.
    LaunchedEffect(hasDnsWorker, dnsEnabled) {
        if (selectedMode == BoostMode.DEDICATED_DNS && !(hasDnsWorker && dnsEnabled)) {
            selectedMode = BoostMode.AUTO
        }
    }

    val boosterState by boosterManager.boosterState.collectAsState()
    val bestResult by boosterManager.bestResult.collectAsState()
    val allResults by boosterManager.allResults.collectAsState()
    val testProgress by boosterManager.testProgress.collectAsState()
    val livePing by boosterManager.livePing.collectAsState()
    val originalPing by boosterManager.originalPing.collectAsState()

    var testJob by remember { mutableStateOf<Job?>(null) }
    var livePingJob by remember { mutableStateOf<Job?>(null) }
    var pendingBoostResult by remember { mutableStateOf<BoostResult?>(null) }

    // Engine-conflict modal state (WireGuard/Xray/SNI can't switch families without a process restart).
    var showEngineConflict by remember { mutableStateOf(false) }
    var conflictEngineName by remember { mutableStateOf("") }

    // Resume after an engine-conflict restart: pre-select the mode the user was trying and prompt
    // them to tap Start (now that the process is clean and the old engine is gone).
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("game_booster_prefs", android.content.Context.MODE_PRIVATE)
        val pendingMode = prefs.getString("pending_boost_mode", null)
        if (pendingMode != null && !com.mlmvpn.scanner.MyVpnService.isRunning &&
            !com.mlmvpn.scanner.engines.rstaspoof.RstaSpoofManager.isRunningFlow.value) {
            prefs.edit()
                .remove("pending_boost_mode")
                .remove("pending_boost_game")
                .remove("pending_boost_region")
                .apply()
            runCatching { selectedMode = BoostMode.valueOf(pendingMode) }
            Toast.makeText(context, S(R.string.the_previous_engine_was_turned_off_now), Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(selectedGame) {
        val game = selectedGame ?: return@LaunchedEffect
        val regions = game.servers.map { it.region }
        if (selectedRegion !in regions) {
            selectedRegion = game.defaultRegion.takeIf { it in regions } ?: regions.firstOrNull() ?: "ME"
        }
        // Per-region DNS test results belong to a specific game -- drop them when the game changes.
        dnsRegionResults = emptyList()
    }

    LaunchedEffect(boosterState) {
        if (boosterState == BoosterState.BOOSTED) {
            val server = boosterManager.selectedServer.value
            if (server != null && livePingJob == null) {
                // Tunnel/WARP/Hybrid all expose a local SOCKS inbound on the same port -- ping
                // through it so the health monitor measures the actual tunneled path, not the
                // phone's raw internet (which is what a bare directPing would measure instead).
                // Direct DNS Boost (nodeUri != null) also runs a local VPN with its own SOCKS
                // inbound; the plain "already clean" Direct case has no VPN at all.
                val isVpnBackedMode = bestResult?.mode == BoostMode.TUNNEL ||
                    ((bestResult?.mode == BoostMode.DIRECT ||
                        bestResult?.mode == BoostMode.DEDICATED_DNS) && bestResult?.nodeUri != null)
                val proxyPort = when {
                    // Aether must be measured through ITS OWN SOCKS listener, not directly.
                    //
                    // A game boost routes only the game package through the TUN
                    // (addAllowedApplication), so this app is deliberately OUTSIDE the tunnel. A
                    // direct ping from here therefore measured the raw ISP path — the opposite
                    // of what the card claims to show — and on a filtered line those game
                    // endpoints are usually unreachable that way, so it returned -1 and the UI
                    // sat on "در حال اندازه‌گیری…" forever. Occasionally one endpoint answered
                    // directly, which is why a number sometimes appeared.
                    //
                    // 20810 is the engine's fixed SOCKS port and GamePingTester already speaks
                    // SOCKS5, so this measures the real tunneled round trip.
                    bestResult?.mode == BoostMode.AETHER ->
                        com.mlmvpn.core.aether.AetherEngine.AETHER_SOCKS_PORT

                    isVpnBackedMode -> {
                        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                        com.mlmvpn.scanner.utils.LocalPort.get(context)
                    }

                    else -> null
                }
                livePingJob = scope.launch {
                    boosterManager.startLivePingMonitor(server, proxyPort)
                }
            }
        } else {
            livePingJob?.cancel()
            livePingJob = null
        }
    }

    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val pendingResult = pendingBoostResult
            val game = selectedGame
            if (pendingResult != null && game != null) {
                val allPackages = listOf(game.packageName) + game.alternatePackages
                val pm = context.packageManager
                val installedPkg = allPackages.firstOrNull { pkg ->
                    try { pm.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }
                } ?: game.packageName
                boosterManager.connectWithBestResult(pendingResult, installedPkg)
            }
            pendingBoostResult = null
        } else {
            pendingBoostResult = null
            Toast.makeText(context, S(R.string.vpn_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    // Historically AUTO could pick WireGuard purely from an RTT estimate with no nodeUri yet, and
    // this materialized the real AmneziaWG config from the live 1-hour UAE trial. Aether needs no
    // such trial/account step -- GameBoosterManager.measureAetherReachability already attaches a
    // ready-to-use Aether config to every AETHER result it produces -- so this is now a passthrough.
    // Kept as a named seam in case a future mode needs the same "resolve before connect" shape.
    fun resolveForConnect(result: BoostResult): BoostResult? = result

    fun startBoostNow(result: BoostResult) {
        // Direct mode only needs the VPN permission dialog when it's actually going to start a
        // local pass-through VPN (Direct DNS Boost, nodeUri != null) -- the plain "connection is
        // already clean" case has no VPN and can connect immediately, same as before.
        if (result.mode == BoostMode.DIRECT && result.nodeUri == null) {
            val game = selectedGame ?: return
            val allPackages = listOf(game.packageName) + game.alternatePackages
            val pm = context.packageManager
            val installedPkg = allPackages.firstOrNull { pkg ->
                try { pm.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }
            } ?: game.packageName
            boosterManager.connectWithBestResult(result, installedPkg)
            return
        }

        pendingBoostResult = result
        val vpnIntent = VpnService.prepare(context)
        if (vpnIntent != null) {
            vpnLauncher.launch(vpnIntent)
        } else {
            val game = selectedGame ?: return
            val allPackages = listOf(game.packageName) + game.alternatePackages
            val pm = context.packageManager
            val installedPkg = allPackages.firstOrNull { pkg ->
                try { pm.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }
            } ?: game.packageName
            boosterManager.connectWithBestResult(result, installedPkg)
            pendingBoostResult = null
        }
    }

    /**
     * The button's version.
     *
     * A boost brings up a tunnel, which takes the default route out from under a running IP scan
     * and makes it finish early on a fake, mostly-empty result. ScanGuard asks first and runs the
     * real thing either immediately or after the user chooses to stop the scan.
     */
    fun startBoost(result: BoostResult) {
        com.mlmvpn.scanner.data.ScanGuard.run(
            com.mlmvpn.scanner.data.ScanGuard.Reason.CONNECT_VPN
        ) { startBoostNow(result) }
    }

    if (picker == "game") {
        androidx.activity.compose.BackHandler { picker = null }
        GamePickerPage(
            games = allGamesSorted,
            selectedId = selectedGame?.id,
            onPick = { game ->
                selectedGame = game
                if (boosterState == BoosterState.BOOSTED) boosterManager.disconnect()
                picker = null
            },
            onBack = { picker = null },
        )
        return
    }
    val pickerGame = selectedGame
    if (picker == "region" && pickerGame != null) {
        androidx.activity.compose.BackHandler { picker = null }
        RegionPickerPage(
            game = pickerGame,
            selectedRegion = selectedRegion,
            onPick = { selectedRegion = it; picker = null },
            onBack = { picker = null },
        )
        return
    }

    com.mlmvpn.scanner.ui.settings.IosScreen(
        title = stringResource(R.string.home_game),
        onBack = onBack,
        backLabel = S(R.string.home),
        scrollable = false,
    ) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            // Transparent: AppScreen's host paints the backdrop -- the wallpaper under a scrim,
            // or a plain sheet when "wallpaper on every screen" is off. Painting one here too
            // would cover it.
            .background(Color.Transparent),
        // Top inset as CONTENT padding: the list runs the full height and dissolves under the
        // status bar rather than stopping at it.
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 16.dp,
            bottom = com.mlmvpn.scanner.ui.LocalSystemBottomPadding.current + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── What is being boosted ──
        //
        // Three controls in three shapes -- a tile strip, an outlined dropdown and a chip row --
        // became three rows that name the current choice. The mode row is the one that gained the
        // most: the chips had no label at all, so nothing on the screen said what they selected.
        item {
            val game = selectedGame
            val currentServer = game?.servers?.find { it.region == selectedRegion }
                ?: game?.servers?.firstOrNull()
            val gameIcon = remember(game?.id) { game?.let { getGameIcon(context, it) } }

            com.mlmvpn.scanner.ui.settings.SettingsGroup(horizontal = 0.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { picker = "game" }
                        .heightIn(min = 52.dp)
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (gameIcon != null) {
                        Image(
                            bitmap = gameIcon.toBitmap(48, 48).asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.size(28.dp).clip(BadgeShape),
                        )
                    } else {
                        Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                            Text(game?.iconEmoji ?: "🎮", fontSize = 20.sp)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        S(R.string.game),
                        color = com.mlmvpn.scanner.ui.settings.Ios.Label,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        game?.name ?: "—",
                        color = com.mlmvpn.scanner.ui.settings.Ios.SecondaryLabel,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Default.ChevronLeft,
                        contentDescription = null,
                        tint = com.mlmvpn.scanner.ui.settings.Ios.Chevron,
                        modifier = Modifier.size(18.dp),
                    )
                }

                if (game != null && game.servers.size > 1) {
                    Separator()
                    com.mlmvpn.scanner.ui.settings.SettingsRow(
                        title = S(R.string.region),
                        value = currentServer?.displayName,
                        icon = Icons.Default.Language,
                        tint = com.mlmvpn.scanner.ui.settings.Ios.Gray,
                        onClick = { picker = "region" },
                    )
                }
            }
        }

        // ── Mode ──
        item {
            val modes = buildList {
                add(Triple(BoostMode.AUTO, stringResource(R.string.game_mode_auto),
                    S(R.string.measures_both_methods_and_takes_the_better)))
                if (hasDnsWorker && dnsEnabled) add(Triple(BoostMode.DEDICATED_DNS, S(R.string.cloudflare_dns),
                    S(R.string.through_your_own_private_dns_with_no)))
                add(Triple(BoostMode.AETHER, "Aether", S(R.string.a_dedicated_tunnel_for_this_game_only)))
            }
            com.mlmvpn.scanner.ui.settings.SettingsSectionHeader(horizontal = 4.dp, text = S(R.string.acceleration_method))
            com.mlmvpn.scanner.ui.settings.SettingsGroup(horizontal = 0.dp) {
                modes.forEachIndexed { index, (mode, label, note) ->
                    if (index > 0) Separator()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedMode = mode }
                            .heightIn(min = 52.dp)
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(label, color = com.mlmvpn.scanner.ui.settings.Ios.Label, fontSize = 16.sp)
                            Spacer(Modifier.height(2.dp))
                            Text(note, color = com.mlmvpn.scanner.ui.settings.Ios.SecondaryLabel, fontSize = 12.sp)
                        }
                        if (selectedMode == mode) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = com.mlmvpn.scanner.ui.settings.Ios.Blue,
                                modifier = Modifier.size(19.dp),
                            )
                        }
                    }
                }
            }
        }

        // ── Dedicated DNS Card ──
        item {
            val game = selectedGame
            // The app's own setting, not the device's. Reading Locale.getDefault() here meant
            // this card stayed Persian on an English phone's Persian system locale and, worse,
            // ignored the language picker entirely -- switching to English changed every other
            // screen and left this one behind.
            val isFa = com.mlmvpn.scanner.utils.AppLocaleManager.getResolvedLocale().language == "fa"
            val cachedRegion = remember(game?.id, dnsRefreshTick, dnsMode, dnsManualRegion) {
                if (game == null) null
                else if (dnsMode == "manual") dnsManualRegion
                else dnsPrefs.getString(com.mlmvpn.scanner.engines.game.DedicatedDnsResolver.cacheKeyFor(game.id), null)
            }
            val cachedPing = remember(game?.id, dnsRefreshTick, dnsMode, dnsManualRegion) {
                if (game == null) -1L
                else dnsPrefs.getLong("${com.mlmvpn.scanner.engines.game.DedicatedDnsResolver.cacheKeyFor(game.id)}_ping", -1L)
            }
            DedicatedDnsCard(
                hasAnyCloudAccount = hasAnyCloudAccount,
                isDeployed = hasDnsWorker,
                deploying = dnsDeploying,
                deployProgress = dnsDeployProgress,
                probing = dnsProbing,
                enabled = dnsEnabled,
                mode = dnsMode,
                manualRegion = dnsManualRegion,
                activeRegionCode = cachedRegion,
                activePing = cachedPing,
                regionResults = dnsRegionResults,
                isFa = isFa,
                onNavigateToCloud = onNavigateToCloud,
                onEnabledChange = { on ->
                    dnsEnabled = on
                    dnsPrefs.edit().putBoolean("dedicated_dns_enabled", on).apply()
                },
                onModeChange = { newMode ->
                    dnsMode = newMode
                    dnsPrefs.edit().putString("dedicated_dns_mode", newMode).apply()
                },
                onManualRegionChange = { code ->
                    dnsManualRegion = code
                    dnsPrefs.edit().putString("dedicated_dns_manual_region", code).apply()
                    dnsRefreshTick++
                },
                stale = cloudAccounts.firstOrNull()?.let {
                    com.mlmvpn.scanner.data.PanelBuild.isStale(it, "DNS")
                } ?: false,
                onDeploy = {
                    val account = cloudAccounts.firstOrNull()
                    if (account != null) {
                        scope.launch {
                            dnsDeploying = true
                            dnsDeployProgress = 0 to (if (isFa) "در حال شروع..." else "Starting...")
                            val (ok, msg) = cloudManager.deployDnsWorker(account) { pct, m ->
                                dnsDeployProgress = pct to m
                            }
                            dnsDeploying = false
                            if (ok) {
                                dnsDeployedLocally = true
                                dnsRemovedLocally = false
                                dnsRefreshTick++
                            }
                            Toast.makeText(
                                context,
                                if (ok) {
                                    if (isFa) "DNS اختصاصی روی حساب شما فعال شد" else "Dedicated DNS is live"
                                } else msg,
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                },
                onRedeploy = {
                    val account = cloudAccounts.firstOrNull()
                    if (account != null) {
                        scope.launch {
                            dnsDeploying = true
                            dnsDeployProgress = 0 to (if (isFa) "در حال به‌روزرسانی…" else "Updating…")
                            val (ok, msg) = cloudManager.deployDnsWorker(account) { pct, m ->
                                dnsDeployProgress = pct to m
                            }
                            dnsDeploying = false
                            dnsRefreshTick++
                            Toast.makeText(
                                context,
                                if (ok) {
                                    if (isFa) "به آخرین نسخه به‌روز شد" else "Updated to the latest build"
                                } else msg,
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                },
                onRemove = {
                    val account = cloudAccounts.firstOrNull()
                    if (account != null) {
                        scope.launch {
                            dnsDeploying = true
                            dnsDeployProgress = 0 to (if (isFa) "در حال حذف…" else "Removing…")
                            val (ok, msg) = cloudManager.removePanel(account, "DNS")
                            dnsDeploying = false
                            if (ok) {
                                dnsRemovedLocally = true
                                dnsDeployedLocally = false
                                dnsRegionResults = emptyList()
                                dnsRefreshTick++
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                    }
                },
                onTestAll = {
                    val g = game
                    if (g != null) {
                        scope.launch {
                            dnsProbing = true
                            dnsRegionResults = emptyList()
                            val results = boosterManager.testAllDnsRegions(g, selectedRegion)
                            dnsRegionResults = results
                            dnsProbing = false
                            dnsRefreshTick++ // refresh the cached "best region" badge
                            if (results.isEmpty()) {
                                Toast.makeText(context, if (isFa) "هیچ منطقه‌ای جواب نداد — DNS اختصاصی یا اتصال را بررسی کنید" else S(R.string.no_region_responded), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                },
                onPickRegion = { code ->
                    // Tapping a region in the results list switches to Manual + that region.
                    dnsMode = "manual"
                    dnsManualRegion = code
                    dnsPrefs.edit()
                        .putString("dedicated_dns_mode", "manual")
                        .putString("dedicated_dns_manual_region", code)
                        .apply()
                    dnsRefreshTick++
                }
            )
        }

        // ── Aether Status Card ──
        // No trial/account step needed here (unlike the old WireGuard-tab UAE trial) -- Aether
        // self-enrolls and picks a healthy endpoint from Cloudflare's WARP pool on first connect.
        if (selectedMode == BoostMode.AETHER) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = GameColors.SurfaceDark),
                    shape = CardShape,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Rounded.SportsEsports, contentDescription = null, tint = GameColors.GameGreen, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(S(R.string.aether_engine_only_the_selected_game_goes), color = GameColors.TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center)

                        // Protocol picker. WireGuard is the lower-overhead data plane — no HTTP
                        // framing around each datagram — so for a latency-bound workload it is
                        // worth being able to measure against MASQUE on the actual line rather
                        // than assuming. MASQUE stays the default because it is the mode proven
                        // to carry traffic here.
                        Spacer(Modifier.height(12.dp))
                        Text(S(R.string.protocol), color = GameColors.TextMuted, fontSize = 11.sp)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                com.mlmvpn.core.aether.AetherProtocol.MASQUE to S(R.string.masque_default),
                                com.mlmvpn.core.aether.AetherProtocol.WG to "WireGuard",
                            ).forEach { (proto, label) ->
                                FilterChip(
                                    selected = aetherProtocol == proto,
                                    onClick = {
                                        aetherProtocol = proto
                                        context.getSharedPreferences("game_booster_prefs", android.content.Context.MODE_PRIVATE)
                                            .edit()
                                            .putString(GameBoosterManager.PREF_AETHER_PROTOCOL, proto.name)
                                            .apply()
                                    },
                                    label = { Text(label, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = GameColors.GameGreen.copy(alpha = 0.2f),
                                        selectedLabelColor = GameColors.GameGreen,
                                    ),
                                )
                            }
                        }
                        // Scan mode. TURBO takes the first endpoint that works and is
                        // connected in seconds; BALANCED keeps looking and picks the
                        // lowest-RTT one, which is a real ping win on a line where more than
                        // one endpoint survives — and wasted minutes on a line where none do.
                        // The user's connection decides which is true, so the user chooses.
                        Spacer(Modifier.height(10.dp))
                        Text(S(R.string.scan_mode), color = GameColors.TextMuted, fontSize = 11.sp)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                com.mlmvpn.core.aether.AetherScan.TURBO to S(R.string.turbo_fast),
                                com.mlmvpn.core.aether.AetherScan.BALANCED to S(R.string.balanced_lower_ping),
                            ).forEach { (mode, label) ->
                                FilterChip(
                                    selected = aetherScan == mode,
                                    onClick = {
                                        aetherScan = mode
                                        context.getSharedPreferences("game_booster_prefs", android.content.Context.MODE_PRIVATE)
                                            .edit()
                                            .putString(GameBoosterManager.PREF_AETHER_SCAN, mode.name)
                                            .apply()
                                    },
                                    label = { Text(label, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = GameColors.GameGreen.copy(alpha = 0.2f),
                                        selectedLabelColor = GameColors.GameGreen,
                                    ),
                                )
                            }
                        }

                        Text(
                            S(R.string.both_protocols_run_over_udp_quic_that) +
                                S(R.string.turbo_usually_takes_seconds_balanced_can_take),
                            color = GameColors.TextMuted, fontSize = 10.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp),
                        )

                        // Live connect progress. Without this the button flipped to "بوست شد"
                        // the instant the service was asked to start — before identity
                        // enrolment, before the gateway scan, before any traffic moved.
                        boostProgress?.let { stage ->
                            Spacer(Modifier.height(12.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = GameColors.GameGreen,
                                )
                                Text(stage, color = GameColors.GameGreen, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        item {
            BoostButton(
                state = boosterState,
                progress = testProgress,
                onBoostClick = {
                    val game = selectedGame ?: return@BoostButton
                    if (boosterState == BoosterState.BOOSTED) {
                        boosterManager.disconnect()
                        return@BoostButton
                    }
                    if (boosterState == BoosterState.TESTING) {
                        testJob?.cancel()
                        testJob = null
                        boosterManager.boosterState.value = BoosterState.IDLE
                        return@BoostButton
                    }

                    // Engine-conflict guard: Aether (separate process, owns its own TUN) and Xray
                    // (libgojni, spins up a gomobile Go runtime) cannot both be up at once without
                    // one fighting the other for the TUN/VpnService. So if a different engine family
                    // is already running, warn and require a clean restart before the boost.
                    val activeEngine = activeEngineLabelFa()
                    if (activeEngine != null) {
                        // commit() (synchronous): the confirm path kills the process, so an async
                        // apply() could be lost before the restart and the resume would never fire.
                        context.getSharedPreferences("game_booster_prefs", android.content.Context.MODE_PRIVATE).edit()
                            .putString("pending_boost_mode", selectedMode.name)
                            .putString("pending_boost_game", game.id)
                            .putString("pending_boost_region", selectedRegion)
                            .commit()
                        conflictEngineName = activeEngine
                        showEngineConflict = true
                        return@BoostButton
                    }

                    if (selectedMode == BoostMode.AETHER) {
                        // Aether needs no trial/account step -- it self-enrolls and picks a healthy
                        // endpoint from Cloudflare's WARP pool on its own the first time it connects.
                        //
                        // buildGameConfig(), NOT a locally-assembled AetherOptions. This path used
                        // to construct its own plain MASQUE/TURBO options, so the Game tab's whole
                        // latency profile (h3, ping-ranked scanning, light obfuscation, short
                        // keepalive) applied to the AUTO race and silently skipped the button the
                        // user actually presses. One definition now, in AetherTunEngine.
                        val aetherProto = GameBoosterManager.gameAetherProtocol(context)
                        val aetherScanMode = GameBoosterManager.gameAetherScan(context)
                        // Measure the un-boosted baseline FIRST, while the tunnel is still down.
                        //
                        // Every other mode reaches runBoostTest(), which records this as a side
                        // effect. The Aether button skips that entirely — it builds its result
                        // inline and connects — so originalPing was never written and the card
                        // read "نامشخص (مسدود)" on every Aether boost. It has to happen here,
                        // before the TUN exists, or it measures the tunnel instead of the line.
                        selectedGame?.servers?.find { it.region == selectedRegion }?.let { srv ->
                            scope.launch { boosterManager.measureBaselinePing(srv) }
                        }
                        val aetherResult = BoostResult(
                            mode = BoostMode.AETHER,
                            pingMs = 1L, // placeholder -- real ping only known once connected
                            jitterMs = 0L,
                            nodeId = "game_aether",
                            nodeName = "Aether",
                            nodeUri = com.mlmvpn.core.aether.AetherTunEngine.buildGameConfig(aetherProto, aetherScanMode),
                            details = S(R.string.aether_engine, aetherProto.displayFa)
                        )
                        startBoost(aetherResult)
                    } else {
                        testJob = scope.launch {
                            val result = boosterManager.runBoostTest(game, selectedRegion, selectedMode)
                            if (result != null) {
                                val ready = resolveForConnect(result)
                                if (ready != null) startBoost(ready)
                            }
                        }
                    }
                }
            )
        }

        // ── Test Results ──
        if (allResults.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.game_result_best),
                    color = GameColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            items(allResults) { result ->
                ResultCard(
                    result = result,
                    isBest = result == bestResult,
                    onConnect = { startBoost(result) }
                )
            }
        }

        // ── Live Ping Monitor ──
        if (boosterState == BoosterState.BOOSTED) {
            item {
                LivePingCard(ping = livePing, bestResult = bestResult)
            }
            item {
                val origStr = if (originalPing > 0) "$originalPing" else S(R.string.unknown_blocked)
                // Prefer the live measured ping; fall back to the test result. Ignore the WireGuard
                // placeholder (1ms) so we never show a fake "1".
                val currentStr = when {
                    livePing > 0 -> "$livePing"
                    (bestResult?.pingMs ?: 0L) > 1L -> "${bestResult?.pingMs}"
                    else -> S(R.string.measuring)
                }
                val gameName = selectedGame?.name ?: S(R.string.game)
                
                Surface(
                    color = GameColors.GameGreen.copy(alpha = 0.1f),
                    shape = ControlShape,
                    border = BorderStroke(1.dp, GameColors.GameGreen.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = S(R.string.your_normal_ping_on_your_own_connection, gameName, origStr, currentStr),
                            color = GameColors.GameGreen,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                        
                        val nodeUri = bestResult?.nodeUri ?: ""
                        val isWorker = nodeUri.contains("workers.dev", ignoreCase = true) || nodeUri.contains("pages.dev", ignoreCase = true)
                        val isCdnTunnel = bestResult?.mode == BoostMode.TUNNEL && (
                            nodeUri.contains("workers.dev", ignoreCase = true) ||
                            nodeUri.contains("pages.dev", ignoreCase = true) ||
                            // VLESS/Trojan over WebSocket on Cloudflare CDN = TCP-only, no UDP
                            (nodeUri.contains("type=ws", ignoreCase = true) && !nodeUri.startsWith("{"))
                        )
                        val isTcpOnly = isWorker || isCdnTunnel
                        if (isTcpOnly) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (isWorker) {
                                    S(R.string.the_connected_server_is_a_cloudflare_worker)
                                } else {
                                    S(R.string.the_current_tunnel_node_is_on_a)
                                },
                                color = GameColors.PingTerrible,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Normal,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }

        // ── Failed State ──
        if (boosterState == BoosterState.FAILED) {
            item {
                Surface(
                    color = GameColors.SurfaceDark,
                    shape = ControlShape,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = GameColors.PingTerrible, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.game_failed),
                            color = GameColors.PingTerrible,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
    }

    if (showEngineConflict) {
        EngineConflictDialog(
            engineName = conflictEngineName,
            onConfirm = {
                showEngineConflict = false
                stopAllEnginesAndRestart(context)
            },
            onDismiss = {
                showEngineConflict = false
                // User cancelled -- drop the pending resume so it doesn't fire later.
                context.getSharedPreferences("game_booster_prefs", android.content.Context.MODE_PRIVATE).edit()
                    .remove("pending_boost_mode")
                    .remove("pending_boost_game")
                    .remove("pending_boost_region")
                    .apply()
            }
        )
    }
}

private fun getGameIcon(context: android.content.Context, game: GameInfo): Drawable? {
    val pm = context.packageManager
    val allPackages = listOf(game.packageName) + game.alternatePackages
    for (pkg in allPackages) {
        try { return pm.getApplicationIcon(pkg) } catch (_: PackageManager.NameNotFoundException) {}
    }
    return null
}

// ── Game Card ──
@Composable
private fun GameCard(
    game: GameInfo,
    isInstalled: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val scale by animateFloatAsState(if (isSelected) 1.05f else 1f, label = "scale")
    val borderColor = if (isSelected) GameColors.GameGreen else GameColors.BorderDark
    val gameIcon = remember(game.id) { getGameIcon(context, game) }

    // Glass that refracts the wallpaper, with a hairline that lights up in the accent when
    // chosen. It was an opaque slab with a hard 2dp ring, which is the pre-redesign selected
    // state -- and the only opaque card left on the screen.
    Surface(
        modifier = Modifier
            .width(90.dp)
            .scale(scale)
            .frostedGlass(CardShape)
            .clip(CardShape)
            .clickable(onClick = onClick),
        color = if (isSelected) GameColors.GameGreen.copy(alpha = 0.14f) else Color.Transparent,
        shape = CardShape,
        border = BorderStroke(if (isSelected) 1.5.dp else 0.7.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (gameIcon != null) {
                Image(
                    bitmap = gameIcon.toBitmap(48, 48).asImageBitmap(),
                    contentDescription = game.name,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(BadgeShape)
                )
            } else {
                Text(game.iconEmoji, fontSize = 28.sp)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                game.name,
                color = if (isSelected) GameColors.GameGreen else GameColors.TextPrimary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                lineHeight = 13.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (isInstalled) {
                Box(
                    modifier = Modifier
                        .background(GameColors.GameGreen.copy(alpha = 0.15f), BadgeShape)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("✓", color = GameColors.GameGreen, fontSize = 9.sp)
                }
            } else {
                Text("—", color = GameColors.TextMuted, fontSize = 9.sp)
            }
        }
    }
}

    // ── Pickers ───────────────────────────────────────────────────────────────────────────────────
//
// The game was a horizontal strip of 90dp bordered tiles and the region was an OutlinedButton with
// a DropdownMenu -- two controls that exist nowhere else in the app, side by side, on the screen
// with the fewest decisions on it. Both are a row that names the current choice and a page that
// changes it, which is what every other choice in the app looks like.

/** One row in a picker page: a label, an optional note, and a check when it is the current one. */
@Composable
private fun PickRow(
title: String,
subtitle: String? = null,
leading: (@Composable () -> Unit)? = null,
selected: Boolean,
onClick: () -> Unit,
) {
Row(
    modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)
        .heightIn(min = 48.dp)
        .padding(horizontal = 16.dp, vertical = 9.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    if (leading != null) {
        leading()
        Spacer(Modifier.width(12.dp))
    }
    Column(modifier = Modifier.weight(1f)) {
        Text(title, color = Ios.Label, fontSize = 16.sp)
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = Ios.SecondaryLabel, fontSize = 12.sp)
        }
    }
    if (selected) {
        Icon(
            Icons.Default.Check,
            contentDescription = null,
            tint = Ios.Blue,
            modifier = Modifier.size(19.dp),
        )
    }
}
}

/**
 * Choosing the game.
 *
 * Installed games come first and say so. The old strip put every game on equal footing and marked
 * the ones you do not have with an em dash, so the four titles actually on the phone were mixed in
 * among a scroller of ones that were not.
 */
@Composable
private fun GamePickerPage(
games: List<Pair<GameInfo, Boolean>>,
selectedId: String?,
onPick: (GameInfo) -> Unit,
onBack: () -> Unit,
) {
val context = LocalContext.current
com.mlmvpn.scanner.ui.settings.IosScreen(
    title = stringResource(R.string.game_select_game),
    onBack = onBack,
    backLabel = stringResource(R.string.home_game),
) {
    @Composable
    fun section(header: String, rows: List<Pair<GameInfo, Boolean>>) {
        if (rows.isEmpty()) return
        com.mlmvpn.scanner.ui.settings.SettingsSectionHeader(header)
        com.mlmvpn.scanner.ui.settings.SettingsGroup {
            rows.forEachIndexed { index, (game, _) ->
                if (index > 0) Separator()
                val icon = remember(game.id) { getGameIcon(context, game) }
                PickRow(
                    title = game.name,
                    selected = selectedId == game.id,
                    leading = {
                        if (icon != null) {
                            Image(
                                bitmap = icon.toBitmap(48, 48).asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.size(28.dp).clip(BadgeShape),
                            )
                        } else {
                            Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                                Text(game.iconEmoji, fontSize = 20.sp)
                            }
                        }
                    },
                    onClick = { onPick(game) },
                )
            }
        }
    }

    Spacer(Modifier.height(6.dp))
    section(S(R.string.installed_on_this_phone), games.filter { it.second })
    section(S(R.string.other_games), games.filterNot { it.second })
    com.mlmvpn.scanner.ui.settings.SettingsFooter(
        S(R.string.the_boost_sends_only_this_game_s)
    )
    Spacer(Modifier.height(24.dp))
}
}

/** Choosing the server region for the selected game. */
@Composable
private fun RegionPickerPage(
game: GameInfo,
selectedRegion: String,
onPick: (String) -> Unit,
onBack: () -> Unit,
) {
com.mlmvpn.scanner.ui.settings.IosScreen(
    title = stringResource(R.string.game_select_region),
    onBack = onBack,
    backLabel = stringResource(R.string.home_game),
) {
    Spacer(Modifier.height(14.dp))
    com.mlmvpn.scanner.ui.settings.SettingsGroup {
        game.servers.forEachIndexed { index, server ->
            if (index > 0) Separator()
            PickRow(
                title = server.displayName,
                subtitle = if (server.region == game.defaultRegion) S(R.string.this_game_s_default_region) else null,
                selected = server.region == selectedRegion,
                onClick = { onPick(server.region) },
            )
        }
    }
    com.mlmvpn.scanner.ui.settings.SettingsFooter(
        S(R.string.pick_the_region_you_usually_play_on) +
            S(R.string.servers)
    )
    Spacer(Modifier.height(24.dp))
}
}


// ── Boost Button ──
/**
 * The boost control.
 *
 * This was a hand-built 130dp disc with its own pulse, its own spin and its own state palette --
 * the fourth such disc in the app, and the only one that did not look like the other three. The
 * SNI and Google-Script screens both drive [EmergencyDial]; so does this one now, which means one
 * definition of what "starting" looks like instead of four.
 */
@Composable
private fun BoostButton(
    state: BoosterState,
    progress: Pair<Int, Int>,
    onBoostClick: () -> Unit
) {
    val dialState = when (state) {
        BoosterState.BOOSTED -> com.mlmvpn.scanner.ui.emergency.DialState.RUNNING
        BoosterState.TESTING, BoosterState.CONNECTING -> com.mlmvpn.scanner.ui.emergency.DialState.STARTING
        BoosterState.FAILED -> com.mlmvpn.scanner.ui.emergency.DialState.FAILED
        else -> com.mlmvpn.scanner.ui.emergency.DialState.IDLE
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        com.mlmvpn.scanner.ui.emergency.EmergencyDial(
            state = dialState,
            // The green of the home-screen tile this screen was opened from.
            idleAccent = GreenOk,
            idleIcon = Icons.Default.Bolt,
            idleLabel = stringResource(R.string.game_boost_btn),
            runningLabel = stringResource(R.string.game_boosted),
            onClick = onBoostClick,
        )

        // The test races several candidates; while it runs, say which one is being measured.
        if (state == BoosterState.TESTING && progress.second > 0) {
            Spacer(Modifier.height(10.dp))
            Text(
                S(R.string.measuring_of, faCount(progress.first), faCount(progress.second)),
                color = com.mlmvpn.scanner.ui.settings.Ios.SecondaryLabel,
                fontSize = 12.sp,
            )
        }
    }
}

// ── Result Card ──
@Composable
private fun ResultCard(
    result: BoostResult,
    isBest: Boolean,
    onConnect: () -> Unit
) {
    val modeIcon = when (result.mode) {
        BoostMode.DIRECT -> Icons.Default.FlashOn
        BoostMode.TUNNEL -> Icons.Default.Shield
        BoostMode.WARP -> Icons.Default.Speed
        BoostMode.DEDICATED_DNS -> Icons.Default.Dns
        BoostMode.UAE_DNS -> Icons.Default.Dns
        BoostMode.AUTO -> Icons.Default.AutoAwesome
        BoostMode.AETHER -> Icons.Default.VpnLock
    }
    val modeLabel = when (result.mode) {
        BoostMode.DIRECT -> "Direct"
        BoostMode.TUNNEL -> "Tunnel"
        BoostMode.WARP -> "WARP"
        BoostMode.DEDICATED_DNS -> S(R.string.dedicated_dns)
        BoostMode.UAE_DNS -> S(R.string.uae_dns)
        BoostMode.AUTO -> "Auto"
        BoostMode.AETHER -> "Aether"
    }
    val borderColor = if (isBest) GameColors.Gold else GameColors.BorderDark

    Surface(
        color = GameColors.SurfaceDark,
        shape = ControlShape,
        border = BorderStroke(if (isBest) 2.dp else 1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        if (isBest) GameColors.Gold.copy(alpha = 0.15f) else GameColors.SurfaceVariant,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    modeIcon,
                    contentDescription = null,
                    tint = if (isBest) GameColors.Gold else GameColors.TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(modeLabel, color = GameColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    if (isBest) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("★", color = GameColors.Gold, fontSize = 12.sp)
                    }
                }
                if (result.details.isNotEmpty()) {
                    Text(result.details, color = GameColors.TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            // Ping
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    pingLabel(result.pingMs),
                    color = pingColor(result.pingMs),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                if (result.jitterMs > 0) {
                    Text(
                        "±${result.jitterMs}ms",
                        color = GameColors.TextMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            if (!isBest) {
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = onConnect,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = GameColors.GameGreen, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

// ── Live Ping Card ──
@Composable
private fun LivePingCard(ping: Long, bestResult: BoostResult?) {
    Surface(
        color = GameColors.SurfaceDark,
        shape = ControlShape,
        border = BorderStroke(1.dp, GameColors.GameGreen.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(GameColors.GameGreen, CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.game_ping_live),
                    color = GameColors.GameGreen,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.weight(1f))
                if (bestResult != null) {
                    val modeLabel = when (bestResult.mode) {
                        BoostMode.DIRECT -> "Direct"
                        BoostMode.TUNNEL -> "Tunnel"
                        BoostMode.WARP -> "WARP"
                        BoostMode.DEDICATED_DNS -> S(R.string.dedicated_dns)
                        BoostMode.UAE_DNS -> S(R.string.uae_dns)
                        BoostMode.AUTO -> "Auto"
                        BoostMode.AETHER -> "Aether"
                    }
                    Text(modeLabel, color = GameColors.TextMuted, fontSize = 11.sp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    if (ping > 0) "${ping}" else "—",
                    color = pingColor(ping),
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                if (ping > 0) {
                    Text(
                        "ms",
                        color = pingColor(ping).copy(alpha = 0.7f),
                        fontSize = 16.sp,
                        modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Ping bar
            if (ping > 0) {
                val fraction = (ping.toFloat() / 200f).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(GameColors.SurfaceVariant, BadgeShape)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(6.dp)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(GameColors.PingGood, pingColor(ping))
                                ),
                                BadgeShape
                            )
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
/**
 * The dedicated-DNS block.
 *
 * Same five states as before, in the app's own furniture: a bordered `Surface` holding a Material
 * `Switch`, two `FilterChip`s, an `OutlinedButton`, a filled `Button` and a `DropdownMenu` became
 * a settings group whose explanation sits in the footer where explanations go. The green is gone
 * from the labels -- it says nothing here that the words do not, and on this screen it was also
 * the accent of the boost dial, the selected chip and the region badge all at once.
 */
@Composable
private fun DedicatedDnsCard(
    hasAnyCloudAccount: Boolean,
    isDeployed: Boolean,
    deploying: Boolean,
    deployProgress: Pair<Int, String>,
    probing: Boolean,
    enabled: Boolean,
    mode: String,
    manualRegion: String,
    activeRegionCode: String?,
    activePing: Long,
    regionResults: List<com.mlmvpn.scanner.engines.game.DedicatedDnsResolver.RegionRaceResult>,
    isFa: Boolean,
    onNavigateToCloud: (() -> Unit)?,
    onEnabledChange: (Boolean) -> Unit,
    onModeChange: (String) -> Unit,
    onManualRegionChange: (String) -> Unit,
    onDeploy: () -> Unit,
    onRedeploy: () -> Unit,
    onRemove: () -> Unit,
    stale: Boolean,
    onTestAll: () -> Unit,
    onPickRegion: (String) -> Unit
) {
    val Ios = com.mlmvpn.scanner.ui.settings.Ios
    val title = if (isFa) "DNS اختصاصی" else "Dedicated DNS"

    com.mlmvpn.scanner.ui.settings.SettingsSectionHeader(horizontal = 4.dp, text = title)

    com.mlmvpn.scanner.ui.settings.SettingsGroup(horizontal = 0.dp) {
        when {
            // 1. No Cloudflare account connected
            !hasAnyCloudAccount -> {
                if (onNavigateToCloud != null) {
                    com.mlmvpn.scanner.ui.settings.SettingsActionRow(
                        label = if (isFa) "اتصال حساب کلادفلر" else "Connect Cloudflare Account",
                        icon = Icons.Default.Cloud,
                    ) { onNavigateToCloud() }
                } else {
                    com.mlmvpn.scanner.ui.settings.SettingsRow(
                        title = if (isFa) "حسابی وصل نیست" else "No account connected",
                        icon = Icons.Default.Cloud,
                        tint = Ios.Gray,
                        showChevron = false,
                    )
                }
            }

            // 2. Deploying
            deploying -> {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            strokeWidth = 2.dp,
                            color = Ios.SecondaryLabel,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            deployProgress.second.ifEmpty {
                                if (isFa) "در حال استقرار…" else "Deploying…"
                            },
                            color = Ios.Label,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            S(R.string.str_2, faCount(deployProgress.first)),
                            color = Ios.SecondaryLabel,
                            fontSize = 13.sp,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = (deployProgress.first / 100f).coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                        color = Ios.Blue,
                        trackColor = Color.White.copy(alpha = 0.12f),
                    )
                }
            }

            // 3. Account present, not deployed yet
            !isDeployed -> {
                com.mlmvpn.scanner.ui.settings.SettingsActionRow(
                    label = if (isFa) "فعال‌سازی DNS اختصاصی" else "Deploy Dedicated DNS",
                    icon = Icons.Default.RocketLaunch,
                ) { onDeploy() }
            }

            // 4. Deployed; the master switch decides whether a boost uses it.
            else -> {
                com.mlmvpn.scanner.ui.settings.SettingsToggle(
                    title = if (isFa) "استفاده در بوست" else "Use during boost",
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    icon = Icons.Default.Dns,
                    tint = Ios.Gray,
                )

                if (enabled) {
                    Separator()
                    val region = com.mlmvpn.scanner.engines.game.DedicatedDnsResolver
                        .regionByCode(activeRegionCode)
                    com.mlmvpn.scanner.ui.settings.SettingsRow(
                        title = if (isFa) "منطقه" else "Region",
                        value = if (isFa) region.nameFa else region.nameEn,
                        icon = Icons.Default.Public,
                        tint = Ios.Gray,
                        showChevron = false,
                    )

                    if (activePing > 0) {
                        Separator()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp)
                                .padding(horizontal = 16.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            com.mlmvpn.scanner.ui.settings.SettingsGlyph(Icons.Default.Speed, Ios.Gray)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                if (isFa) "پینگ از این منطقه" else "Ping via this region",
                                color = Ios.Label,
                                fontSize = 16.sp,
                                modifier = Modifier.weight(1f),
                            )
                            // A measurement IS a state, so this one keeps its colour.
                            Text(
                                pingLabel(activePing),
                                color = pingColor(activePing),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    Separator()
                    com.mlmvpn.scanner.ui.settings.SettingsRow(
                        title = if (isFa) "انتخاب منطقه" else "Region choice",
                        value = if (mode == "auto") {
                            if (isFa) "خودکار" else "Auto"
                        } else {
                            val m = com.mlmvpn.scanner.engines.game.DedicatedDnsResolver
                                .regionByCode(manualRegion)
                            if (isFa) m.nameFa else m.nameEn
                        },
                        icon = Icons.Default.Tune,
                        tint = Ios.Gray,
                        onClick = {
                            onModeChange(if (mode == "auto") "manual" else "auto")
                        },
                    )

                    // Manual: the regions themselves, as rows. A DropdownMenu over an
                    // OutlinedButton was the only menu of its kind left on the screen.
                    if (mode == "manual") {
                        com.mlmvpn.scanner.engines.game.DedicatedDnsResolver.ALL_REGIONS.forEach { r ->
                            Separator()
                            val measured = regionResults.firstOrNull { it.region.code == r.code }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onManualRegionChange(r.code) }
                                    .heightIn(min = 44.dp)
                                    .padding(start = 52.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (isFa) r.nameFa else r.nameEn,
                                    color = Ios.Label,
                                    fontSize = 15.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                if (measured != null) {
                                    Text(
                                        pingLabel(measured.pingMs),
                                        color = pingColor(measured.pingMs),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                if (manualRegion == r.code) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Ios.Blue,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }

                    Separator()
                    com.mlmvpn.scanner.ui.settings.SettingsActionRow(
                        label = if (probing) {
                            if (isFa) "در حال تست همهٔ مناطق…" else "Testing all regions…"
                        } else {
                            if (isFa) "تست همهٔ مناطق برای این بازی" else "Test all regions"
                        },
                        icon = Icons.Default.Speed,
                        enabled = !probing,
                    ) { onTestAll() }
                }

                // A deployed resolver is a copy of the script this app shipped at the time, so it
                // ages exactly the way the panels do -- and until now it could be neither replaced
                // nor taken down from here.
                if (stale) {
                    Separator()
                    com.mlmvpn.scanner.ui.settings.SettingsActionRow(
                        label = if (isFa) "به‌روزرسانی به آخرین نسخه" else "Update to the latest build",
                        icon = Icons.Default.Refresh,
                        tint = Ios.Orange,
                    ) { onRedeploy() }
                } else {
                    Separator()
                    com.mlmvpn.scanner.ui.settings.SettingsActionRow(
                        label = if (isFa) "دیپلوی مجدد" else "Redeploy",
                        icon = Icons.Default.Refresh,
                    ) { onRedeploy() }
                }
                Separator()
                com.mlmvpn.scanner.ui.settings.SettingsActionRow(
                    label = if (isFa) "حذف DNS اختصاصی از کلادفلر" else "Remove dedicated DNS",
                    icon = Icons.Default.DeleteOutline,
                    tint = Ios.Red,
                ) { onRemove() }
            }
        }
    }

    // The measured comparison, once there is one.
    if (isDeployed && enabled && regionResults.isNotEmpty()) {
        com.mlmvpn.scanner.ui.settings.SettingsSectionHeader(
            horizontal = 4.dp,
            text = if (isFa) "پینگ واقعی به سرور بازی" else "Real ping to the game server",
        )
        com.mlmvpn.scanner.ui.settings.SettingsGroup(horizontal = 0.dp) {
            regionResults.forEachIndexed { index, r ->
                if (index > 0) Separator()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPickRegion(r.region.code) }
                        .heightIn(min = 44.dp)
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (isFa) r.region.nameFa else r.region.nameEn,
                        color = Ios.Label,
                        fontSize = 15.sp,
                    )
                    if (index == 0) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (isFa) "بهترین" else "best",
                            color = Ios.SecondaryLabel,
                            fontSize = 11.sp,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (r.jitterMs > 0) {
                        Text(
                            (if (isFa) "نوسان " else "jit ") + "${r.jitterMs}ms",
                            color = Ios.SecondaryLabel,
                            fontSize = 11.sp,
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        pingLabel(r.pingMs),
                        color = pingColor(r.pingMs),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (r.region.code == activeRegionCode) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = Ios.Blue,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
        com.mlmvpn.scanner.ui.settings.SettingsFooter(
            if (isFa) "برای انتخاب دستی یک منطقه، روی آن بزنید."
            else "Tap a region to select it manually."
        )
    } else {
        com.mlmvpn.scanner.ui.settings.SettingsFooter(
            when {
                !hasAnyCloudAccount -> if (isFa)
                    S(R.string.a_private_dns_is_deployed_on_your)
                else
                    "A private DNS resolver on your own Cloudflare account, which lowers game ping by steering the region. Connect an account first."

                !isDeployed -> if (isFa)
                    S(R.string.a_private_dns_is_deployed_on_your_2)
                else
                    "Deploys a private DNS resolver on your own Cloudflare account that lowers game ping by smart region steering."

                !enabled -> if (isFa)
                    S(R.string.deployed_but_switched_off_until_you_turn)
                else
                    "Deployed but off — a boost will not use it until you turn it on."

                else -> if (isFa)
                    S(R.string.automatic_finds_the_nearest_region_for_each)
                else
                    "Auto finds the closest region per game. Switch to manual if you prefer to choose."
            }
        )
    }
}
