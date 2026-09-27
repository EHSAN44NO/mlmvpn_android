package com.mlmvpn.scanner.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.models.VpnNode
import androidx.compose.foundation.layout.wrapContentHeight
import com.mlmvpn.scanner.ui.home.DriftingLights
import com.mlmvpn.scanner.ui.home.strongWashLights
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import com.mlmvpn.scanner.ui.home.frostedGlass
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.theme.BadgeShape
import com.mlmvpn.scanner.ui.theme.CardShape
import com.mlmvpn.scanner.ui.theme.ControlShape
import com.mlmvpn.scanner.ui.theme.PanelShape
import com.mlmvpn.scanner.utils.Platform
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

// =================================================================================================
// The parts the connection screen is built from.
//
// The screen this replaces had 2675 lines in one file and one composable, and the pieces below were
// tangled into it: a five-button action slab with hairline dividers, a collapsible header full of
// switches, two different chip strips written twice, a 230dp-tall node card that was mostly empty
// space, and a floating disc parked on top of the list. Each of those is one thing here, and each
// of them uses the app's own glass, radii and palette instead of its own.
// =================================================================================================

/** Where the connection screen currently is. Pushed pages, not dialogs -- see NodesScreens.kt. */
sealed class NodesPage {
    object List : NodesPage()
    object Settings : NodesPage()
    object AutoSwitch : NodesPage()
    object Add : NodesPage()
    data class Detail(val id: String) : NodesPage()
    data class Edit(val id: String) : NodesPage()
}

/**
 * The six things you can do to the whole list, in one glass strip.
 *
 * Six, not five: the settings gear replaces the «بیشتر» drawer that held the three switches and
 * the delete. Labels stay under the glyphs -- a row of six unlabelled icons is a guessing game,
 * and this is the densest control on the screen.
 */
/**
 * One button that takes you to the far end of the list, in the direction you were already going.
 *
 * A list of several hundred configs has two places worth reaching directly and no way to reach
 * either: the top, where the fastest are after a sort, and the bottom. Dragging there is a dozen
 * flings, and the scrollbar on Android is not a thing you can grab.
 *
 * It follows the GESTURE rather than the position, which is what makes one button enough. Scroll
 * down and it offers the end; scroll up and it offers the top. That is the direction you have
 * already said you want to go, so the button is never the opposite of your intent -- and it hides
 * itself at whichever end it would be pointing at, because an arrow to where you already are is
 * just a thing to mis-tap.
 */
@Composable
fun ListJumpButton(
    state: androidx.compose.foundation.lazy.LazyListState,
    itemCount: Int,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var goingDown by remember { mutableStateOf<Boolean?>(null) }

    // The direction comes from consecutive scroll positions rather than from a fling's velocity:
    // it has to be right for a slow drag too, and a drag has no velocity worth reading.
    //
    // ONE number, not the index and the offset compared separately. The offset restarts from zero
    // every time the index changes, so comparing the two fields independently reports "up" on the
    // very frame a new row comes into view while the list is going down. Folding them into a
    // single position that only ever grows with distance travelled removes the case entirely.
    LaunchedEffect(state) {
        var last = -1L
        androidx.compose.runtime.snapshotFlow {
            state.firstVisibleItemIndex.toLong() * 1_000_000L + state.firstVisibleItemScrollOffset
        }.collect { position ->
            if (last >= 0 && position != last) goingDown = position > last
            last = position
        }
    }

    val atTop = state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0
    val atEnd = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= itemCount - 1 } == true
    val down = goingDown
    val show = itemCount > 8 && down != null && (if (down) !atEnd else !atTop)

    androidx.compose.animation.AnimatedVisibility(
        visible = show,
        enter = androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut(),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .frostedGlass(androidx.compose.foundation.shape.CircleShape)
                .clickable {
                    scope.launch {
                        val target = if (down == true) (itemCount - 1).coerceAtLeast(0) else 0
                        // Close the distance, then glide the last stretch.
                        //
                        // `animateScrollToItem` over three hundred rows does not animate the
                        // journey -- it approximates, corrects and approximates again, which
                        // arrives as the list lurching forward in blocks. Animating the whole
                        // distance honestly is no better: it is tens of thousands of pixels, so
                        // either it takes an age or it is a blur nobody can read.
                        //
                        // What reads as smooth is what a good list does: get there, and let the
                        // ARRIVAL be the part you see. The far part is a jump nobody perceives as
                        // motion anyway, and the last few rows glide in on a spring.
                        val approach = if (down == true) (target - 3).coerceAtLeast(0)
                        else (target + 3).coerceAtMost((itemCount - 1).coerceAtLeast(0))
                        val far = kotlin.math.abs(state.firstVisibleItemIndex - target) > 6
                        if (far) state.scrollToItem(approach)
                        state.animateScrollToItem(target)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (down == true) Icons.Default.KeyboardDoubleArrowDown
                else Icons.Default.KeyboardDoubleArrowUp,
                contentDescription = null,
                tint = Ios.Label,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
fun NodeToolbar(
    sortActive: Boolean,
    busy: Boolean,
    onPing: () -> Unit,
    onDelay: () -> Unit,
    onAdd: () -> Unit,
    onSort: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .frostedGlass(PanelShape)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolbarAction(Icons.Default.FlashOn, S(R.string.ping), enabled = !busy, onClick = onPing)
        ToolbarAction(Icons.Default.AccessTime, S(R.string.delay), enabled = !busy, onClick = onDelay)
        // No download-speed test here.
        //
        // It measured a real download through each config, one at a time, over a list that is
        // routinely hundreds long -- minutes of traffic to answer a question the delay test
        // beside it already answers well enough to sort by. The per-config version is still on
        // the config's own page, which is where asking about ONE server belongs.
        ToolbarAction(Icons.Default.Add, S(R.string.add), onClick = onAdd)
        ToolbarAction(Icons.Default.Sort, S(R.string.sort), active = sortActive, onClick = onSort)
        ToolbarAction(Icons.Default.Tune, S(R.string.settings), onClick = onSettings)
    }
}

/**
 * One toolbar button.
 *
 * Internal rather than private because the SNI config list wears the same toolbar, and two
 * implementations of one control is how two screens that should look identical stop being.
 */
@Composable
internal fun ToolbarAction(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    // White, like every other action label in the app. Colour on this screen means state, and the
    // one piece of state a toolbar button has is "sort is on", which the accent says.
    val tint = when {
        !enabled -> Ios.SecondaryLabel.copy(alpha = 0.45f)
        active -> Ios.Green
        else -> Ios.Label
    }
    Column(
        modifier = Modifier
            .clip(ControlShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = tint, fontSize = 10.sp)
    }
}

/**
 * One batch test in flight: what it is, how far along, and a way out.
 *
 * Shown only while running. Three of these used to be permanently in the tree behind
 * `AnimatedVisibility`, each with its own copy of the same row.
 */
@Composable
fun NodeTestProgressRow(
    label: String,
    visible: Boolean,
    progress: Int,
    total: Int,
    onCancel: () -> Unit,
) {
    AnimatedVisibility(visible = visible) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$label ${faCount(progress)}/${faCount(total)}",
                color = Ios.SecondaryLabel,
                fontSize = 11.sp,
                modifier = Modifier.padding(end = 10.dp),
            )
            LinearProgressIndicator(
                progress = if (total > 0) progress.toFloat() / total else 0f,
                modifier = Modifier.weight(1f).height(3.dp).clip(CircleShape),
                color = Ios.Blue,
                trackColor = Color.White.copy(alpha = 0.14f),
            )
            Icon(
                Icons.Default.Close,
                contentDescription = S(R.string.cancel_2),
                tint = Ios.SecondaryLabel,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onCancel),
            )
        }
    }
}

/**
 * A horizontal strip of scopes -- panels, or folders.
 *
 * There were two of these written separately, one a `Row` and one a `LazyRow`, with the same
 * selected-chip treatment copied into both plus a third copy for the platform strip. One
 * composable now, so a change to the chip is a change to every chip.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T> NodeScopeChips(
    items: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onLongPress: ((T) -> Unit)? = null,
    onAdd: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onAdd != null) {
            Box(
                modifier = Modifier
                    .clip(BadgeShape)
                    .background(Color.White.copy(alpha = 0.08f))
                    .clickable(onClick = onAdd)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = S(R.string.new_folder),
                    tint = Ios.Label,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        items.forEach { item ->
            val isSelected = item == selected
            Box(
                modifier = Modifier
                    .clip(BadgeShape)
                    .background(
                        if (isSelected) Color.White.copy(alpha = 0.20f)
                        else Color.White.copy(alpha = 0.07f)
                    )
                    .combinedClickable(
                        onClick = { onSelect(item) },
                        onLongClick = { onLongPress?.invoke(item) },
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(item),
                    color = if (isSelected) Ios.Label else Ios.SecondaryLabel,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The platform strip, when platform mode is on. Same chip, with a glyph in front of the name. */
@Composable
fun NodePlatformChips(selected: Platform?, onSelect: (Platform) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Platform.values().forEach { platform ->
            val isSelected = selected == platform
            Row(
                modifier = Modifier
                    .clip(BadgeShape)
                    .background(
                        if (isSelected) Color.White.copy(alpha = 0.20f)
                        else Color.White.copy(alpha = 0.07f)
                    )
                    .clickable { onSelect(platform) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    platformIcon(platform),
                    contentDescription = null,
                    tint = if (isSelected) Ios.Label else Ios.SecondaryLabel,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    platform.displayName,
                    color = if (isSelected) Ios.Label else Ios.SecondaryLabel,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

private fun platformIcon(platform: Platform): ImageVector = when (platform) {
    Platform.INSTAGRAM -> Icons.Default.CameraAlt
    Platform.YOUTUBE -> Icons.Default.PlayArrow
    Platform.TIKTOK -> Icons.Default.MusicNote
    Platform.TWITTER -> Icons.Default.Close
    Platform.WHATSAPP -> Icons.Default.Chat
    Platform.GEMINI -> Icons.Default.AutoAwesome
    Platform.ANTIGRAVITY -> Icons.Default.Build
    Platform.CLAUDE -> Icons.Default.Face
    Platform.TRAE -> Icons.Default.Edit
    Platform.CAPCUT -> Icons.Default.Movie
}

/** An in-list warning or note, in the app's tinted-glass style rather than a bordered slab. */
@Composable
fun NodeNotice(wrong: Boolean, title: String, body: String) {
    val accent = if (wrong) Ios.Red else Ios.Yellow
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(accent.copy(alpha = 0.12f), PanelShape)
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            if (wrong) Icons.Default.Error else Icons.Default.Info,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, color = accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Text(body, color = Ios.SecondaryLabel, fontSize = 12.sp, lineHeight = 19.sp)
        }
    }
}

/**
 * One config in the list.
 *
 * The card this replaces was around 230dp tall and mostly air: a 40dp grey glyph tile, the name,
 * a coloured badge, a second badge for the protocol, a third for the folder stacked under a
 * kebab button, and a horizontally scrolling strip of `Ping: …` / `Delay: …` / `Speed: …` chips
 * that repeated the word "Ping" on every row of a list where every row has a ping.
 *
 * Here the numbers are three values on one line under the name, in the same order every time, so
 * the eye reads down a column instead of across a paragraph -- and the row is a row, not a card,
 * so twenty of them fit on a screen instead of six.
 */
@Composable
fun NodeRow(
    node: VpnNode,
    isActive: Boolean,
    isConnected: Boolean,
    highlight: Boolean,
    platformDelay: Long?,
    onClick: () -> Unit,
    onOpenDetail: () -> Unit,
) {
    val (badge, badgeTint) = nodeBadge(node)
    // Selected and connected are different states and they need to look different: "this is the
    // one the button will use" is a rim, "this is the one carrying traffic right now" is the
    // green tick on the glyph. The old card said both with the same faint blue wash.
    val rim = when {
        isConnected -> Ios.Green
        highlight -> Ios.Green.copy(alpha = 0.7f)
        isActive -> Ios.Blue
        else -> null
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .frostedGlass(PanelShape)
            .then(
                if (rim != null) Modifier.border(1.5.dp, rim.copy(alpha = 0.55f), PanelShape)
                else Modifier
            )
            .clickable(onClick = onClick)
            .heightIn(min = 62.dp)
            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The state marker doubles as the type glyph: green tick when this is the live tunnel,
        // the badge's own colour otherwise. One object, not a tick AND a tile.
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(
                    if (isConnected) Ios.Green.copy(alpha = 0.22f)
                    else badgeTint.copy(alpha = 0.18f)
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isConnected) Icons.Default.CheckCircle else Icons.Default.Storage,
                contentDescription = null,
                tint = if (isConnected) Ios.Green else badgeTint,
                modifier = Modifier.size(17.dp),
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    node.name,
                    color = if (isActive) Ios.Label else Ios.Label,
                    fontSize = 15.sp,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (node.countryCode != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(getNodeFlagEmoji(node.countryCode), fontSize = 14.sp)
                }
                if (highlight) {
                    Spacer(Modifier.width(6.dp))
                    NodePill(S(R.string.fastest), Ios.Green)
                }
            }

            Spacer(Modifier.height(5.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(badge, color = badgeTint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                MetricDot()
                Text(node.type.uppercase(), color = Ios.SecondaryLabel, fontSize = 10.sp)
                measurementLabel(node.ping)?.let {
                    MetricDot()
                    Text(it, color = measurementTone(node.ping, 150, 500), fontSize = 10.sp)
                }
                measurementLabel(node.delay)?.let {
                    MetricDot()
                    Text(it, color = measurementTone(node.delay, 300, 800), fontSize = 10.sp)
                }
                measurementLabel(node.speed)?.let {
                    MetricDot()
                    Text(it, color = speedTone(node.speed), fontSize = 10.sp)
                }
                if (platformDelay != null) {
                    MetricDot()
                    Text("${faCount(platformDelay.toInt())}ms", color = Ios.Teal, fontSize = 10.sp)
                }
            }
        }

        // Opening the detail page is its own target, so tapping the row can mean "use this one"
        // without a second guess about which half of the row does what.
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onOpenDetail)
                .padding(10.dp),
        ) {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = S(R.string.details_2),
                tint = Ios.Chevron,
                modifier = Modifier
                    .size(18.dp)
                    .scale(
                        scaleX = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f,
                        scaleY = 1f,
                    ),
            )
        }
    }
}

/** The separator between the little facts on a row's second line. */
@Composable
private fun MetricDot() {
    Text(
        " · ",
        color = Ios.SecondaryLabel.copy(alpha = 0.55f),
        fontSize = 10.sp,
    )
}

/**
 * The one control the screen exists for.
 *
 * It was a 56dp glass square floating in the bottom corner ON TOP of the list, with the exit
 * country's flag stacked above it and a second yellow square above that for platform testing --
 * three floating objects covering whichever configs happened to be under them, and none of them
 * saying which server the button would actually use. This is a bar: it names the server, shows
 * where the live tunnel comes out, and is the width of a thumb's travel.
 */
/**
 * The two hues the connect bar is lit by, per state.
 *
 * Taken from NetDoctor's connect dock unchanged, colour for colour. Two per state and never one:
 * a single colour makes a flat wash, and it is the overlap of two moving lights that reads as lit
 * rather than painted.
 *
 * Green for carrying, amber for coming up, red for down -- which is the mapping every one of these
 * screens already implies and none of them showed.
 */
private fun connectWashPair(running: Boolean, connecting: Boolean): Pair<Color, Color> = when {
    // Green and turquoise: near enough in hue to read as one living colour rather than two lamps.
    running -> Color(0xFF35C05A) to Color(0xFF2ED3C6)
    connecting -> Color(0xFFF0912B) to Color(0xFFF2C34B)
    // Red with a warmer second beside it. Red alone reads as an error rather than as "not on".
    else -> Color(0xFFE0526A) to Color(0xFFA968C8)
}

@Composable
fun NodeConnectBar(
    node: VpnNode?,
    isRunning: Boolean,
    isConnecting: Boolean,
    hasFailed: Boolean,
    exitCountry: String?,
    platformMode: Boolean,
    platformTesting: Boolean,
    platformName: String?,
    onPlatformTest: () -> Unit,
    onToggle: () -> Unit,
) {
    val accent by animateColorAsState(
        when {
            isRunning -> Ios.Green
            isConnecting -> Ios.Blue
            hasFailed -> Ios.Orange
            node == null -> Ios.Gray
            else -> Ios.Blue
        },
        label = "connectAccent",
    )

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        if (platformMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Ios.Teal.copy(alpha = 0.14f), ControlShape)
                    .clickable(enabled = !platformTesting, onClick = onPlatformTest)
                    .padding(vertical = 11.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (platformTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        color = Ios.Teal,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        Icons.Default.FlashOn,
                        contentDescription = null,
                        tint = Ios.Teal,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (platformTesting) S(R.string.measuring_3)
                    else S(R.string.measure_servers_for, platformName ?: ""),
                    color = Ios.Label,
                    fontSize = 14.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        // The bar is lit from inside, and the whole of it is the switch.
        //
        // Both are NetDoctor's, taken rather than reinvented. The lights are the piece that is
        // easy to get wrong: not a gradient painted on the glass but four radial fields drifting
        // on separate noise tracks, so what is seen is the mix and not the shapes, and the colour
        // has no edge where it begins. `fade = false` because a bar is ENTIRELY wash -- there is
        // no page above it to dissolve into, and the fade there erases the colour instead, which
        // is what made three states impossible to tell apart in their first attempt.
        //
        // Twice the bar's height and centred, so each light is a broad field crossing it rather
        // than a circle sitting on it. colourSpread 1.8 for the same reason: a light is sized off
        // the shorter side, and the short side of a bar is its height.
        //
        // Tapping anywhere on it toggles. The button was the only live part before -- a full-width
        // bar with one target at the end of it, when everything else printed on the bar is about
        // the connection that target makes.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .frostedGlass(CardShape)
                .clickable(enabled = node != null || isRunning || isConnecting, onClick = onToggle),
        ) {
            val hues = connectWashPair(isRunning, isConnecting)
            // Lit AND moving in all three states.
            //
            // It drifted only while the tunnel was coming up for a while, because the drift used
            // to ask the window for a frame every vsync and this bar is on screen the whole time
            // the config list is. Amber was kept on the theory that movement only says something
            // while it is working on it -- but green and red are the states the bar is actually
            // in, for hours at a time, and a wash that does not move in them is a gradient
            // painted on the glass rather than light behind it. That is the whole difference the
            // wash exists to make.
            //
            // The frames were the real problem and they are fixed where they were caused: the
            // clock in DriftingLights is a `delay` loop read in the draw phase now, not an
            // infinite transition. So there is nothing left to trade the movement for.
            DriftingLights(
                colors = strongWashLights(hues.first, hues.second),
                fade = false,
                colourSpread = 1.8f,
                modifier = Modifier
                    .matchParentSize()
                    .wrapContentHeight(unbounded = true)
                    .height(CONNECT_BAR_LIGHT_HEIGHT)
                    .align(Alignment.Center),
            )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    when {
                        isRunning -> S(R.string.connected)
                        isConnecting -> S(R.string.bringing_the_tunnel_up)
                        hasFailed -> S(R.string.the_tunnel_did_not_come_up)
                        node == null -> S(R.string.no_server_selected)
                        else -> S(R.string.ready_to_connect)
                    },
                    // Same reasoning as the button: the bar is already the colour of the
                    // state, so a coloured caption on it is a second voice saying one thing.
                    color = Ios.Label.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isRunning && exitCountry != null) {
                        Text(getNodeFlagEmoji(exitCountry), fontSize = 14.sp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        node?.name ?: S(R.string.tap_one_from_the_list),
                        color = Ios.Label,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            // Ink, not accent, on both the mark and the word.
            //
            // They were the state colour, which was right on flat glass and is wrong on a bar
            // that is itself red, amber or green: two colours then compete to say the same thing,
            // and the smaller one wins the eye. The wash says which state this is; the button
            // only has to be legible on it. NetDoctor's dock reaches the same conclusion in its
            // own words -- the tint there is the theme's onSurface for exactly this reason.
            Row(
                modifier = Modifier
                    .clip(ControlShape)
                    .background(Color.White.copy(alpha = 0.16f))
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 18.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Ios.Label,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                        tint = Ios.Label,
                        modifier = Modifier.size(17.dp),
                    )
                }
                Spacer(Modifier.width(7.dp))
                Text(
                    when {
                        isRunning -> S(R.string.disconnect)
                        // Tapping mid-handshake stops the attempt, so the button says so rather
                        // than inviting a second connect on top of the first.
                        isConnecting -> S(R.string.cancel_2)
                        hasFailed -> S(R.string.try_again_2)
                        else -> S(R.string.connect)
                    },
                    color = Ios.Label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        }
    }
}

/**
 * How tall the light field behind the bar is drawn.
 *
 * Twice the bar, so the lights' centres wander above and below the visible band and each one
 * crosses it as a broad field. At the bar's own height they come out as small circles sliding
 * across, which is the shapes showing instead of the mix.
 */
private val CONNECT_BAR_LIGHT_HEIGHT = 132.dp
