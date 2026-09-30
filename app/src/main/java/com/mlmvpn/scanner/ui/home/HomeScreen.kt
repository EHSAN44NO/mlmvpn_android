package com.mlmvpn.scanner.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.ui.LocalSystemBottomPadding
import com.mlmvpn.scanner.ui.LocalSystemTopPadding

/**
 * How the board is divided, for the space it actually has.
 *
 * The grid was a fixed 4x6, which is right for a phone held upright and wrong for everything
 * else: turned sideways, on a tablet, in DeX or on a television, four columns of icons ran down
 * the middle of the screen with two thirds of the width empty and the rows squeezed into a strip
 * too short to hold them. The shape is now derived, and [pageCount] is what stops that derivation
 * from ever hiding an icon — anything that does not fit goes onto a second page rather than off
 * the bottom edge.
 */
private data class BoardShape(val columns: Int, val rows: Int) {
    val perPage: Int get() = columns * rows

    fun pageCount(items: Int): Int =
        if (perPage <= 0) 1 else ((items + perPage - 1) / perPage).coerceAtLeast(1)
}

/** Cell pitch below which a row is too cramped to hold a tile and its caption. */
private val MIN_ROW_HEIGHT = 96.dp

/** Cell pitch below which captions start colliding with their neighbours. */
private val MIN_COLUMN_WIDTH = 92.dp

/**
 * Pick a shape for a board of [width] x [height].
 *
 * An upright phone is answered with exactly the 4x6 it has always had, deliberately and by an
 * early return: that layout is what every existing user's saved icon order was arranged against,
 * and re-deriving it from measurements would eventually disagree with it on some screen size for
 * no benefit at all.
 *
 * Everything wider than it is tall — a phone turned sideways, a tablet, DeX, a television — gets
 * as many columns as fit at [MIN_COLUMN_WIDTH], capped at six. The cap is not arithmetic: past
 * six, a 55-inch television at three metres renders icons that are large in pixels and unreadable
 * in practice, and the caption under each one is what actually fails first.
 */
private fun boardShapeFor(width: Dp, height: Dp, items: Int): BoardShape {
    val portraitPhone = width < 600.dp && height >= width
    if (portraitPhone) return BoardShape(columns = 4, rows = 6)

    val columns = (width / MIN_COLUMN_WIDTH).toInt().coerceIn(4, 6)
    val fit = (height / MIN_ROW_HEIGHT).toInt().coerceIn(2, 6)
    // Never more rows than there are icons to put in them. Without this second bound a tablet
    // computed six rows for nineteen icons, packed them into the top four, and left the bottom
    // third of the screen as a void between the last caption and the dock -- the board was
    // "correct" and looked abandoned. Taking the smaller of the two is what spreads the rows that
    // exist across the height available to them.
    val needed = ((items + columns - 1) / columns).coerceAtLeast(1)
    return BoardShape(columns = columns, rows = minOf(fit, needed).coerceAtLeast(2))
}

/**
 * The app's front door: a wallpaper, a grid of icons, and a dock.
 *
 * This replaces both halves of the old navigation at once -- the five-icon floating bar and the
 * hamburger drawer that hid twelve more features behind it. Everything the drawer held is an
 * icon here, so nothing in the app is more than two taps away and nothing is invisible until you
 * go looking for it.
 *
 * The backdrop is NOT drawn here. It is painted at the activity root, behind the system bars, so
 * it runs edge to edge with no cut line under the status icons; this screen is transparent and
 * sits on top of it.
 */
@Composable
fun HomeScreen(
    isRunning: Boolean,
    showTraffic: Boolean,
    trafficDown: Float,
    trafficUp: Float,
    onOpen: (HomeApp) -> Unit,
    /** Settings > Software update. Only reachable while [updateReady] is true. */
    onOpenUpdate: () -> Unit = {},
) {
    val context = LocalContext.current
    var order by remember { mutableStateOf(HomeLayoutStore.load(context)) }
    var editMode by remember { mutableStateOf(false) }
    // The folder open over the board, by key. What it shows is read back out of `order`, so a
    // change made inside it -- a new name, a member dragged out -- is the board's own state and
    // the two can never disagree.
    var openFolder by remember { mutableStateOf<String?>(null) }

    fun commit(items: List<BoardItem>) {
        order = items
        HomeLayoutStore.save(context, items)
    }
    fun commitLayout(layout: BoardLayout?) {
        if (layout != null) commit(HomeLayoutStore.toItems(layout))
    }

    // Leaving edit mode is what back does first, before it would ever offer to exit the app. An
    // open folder answers back itself (it closes, or leaves its own rearranging first).
    BackHandler(enabled = editMode && openFolder == null) { editMode = false }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                top = LocalSystemTopPadding.current,
                bottom = LocalSystemBottomPadding.current,
            )
            // The sides matter only in landscape, and there they matter a lot: the display cutout
            // sits on one edge and the navigation bar on the other, so padding top and bottom
            // alone left the board pushed towards one side -- the icons were centred in the
            // window and visibly off-centre on the screen. `displayCutout` and `navigationBars`
            // rather than `systemBars`, because the status bar is a top inset already applied
            // above and taking it twice would add a margin nothing is behind.
            .windowInsetsPadding(
                WindowInsets.displayCutout
                    .union(WindowInsets.navigationBars)
                    .only(WindowInsetsSides.Horizontal)
            )
    ) {
        // A newer release is announced the iOS way: a red badge on the Settings icon (see
        // AppIconCell) and a row at the top of Settings -- not a glyph in this strip.
        HomeStatusStrip(
            isRunning = isRunning,
            showTraffic = showTraffic,
            trafficDown = trafficDown,
            trafficUp = trafficUp,
            editMode = editMode,
            onDone = { editMode = false },
        )

        IconGrid(
            order = order,
            editMode = editMode,
            onEnterEditMode = { editMode = true },
            onExitEditMode = { editMode = false },
            onReorder = { reordered -> commit(reordered) },
            onMerge = { draggedId, targetId ->
                commitLayout(
                    BoardLayoutRules.merge(
                        HomeLayoutStore.toLayout(order),
                        draggedId,
                        targetId,
                        newKey = "f" + System.currentTimeMillis().toString(36),
                    )
                )
            },
            onOpen = { item ->
                when (item) {
                    // A folder opens in edit mode too -- that is where it is renamed and emptied.
                    is BoardItem.Folder -> openFolder = item.key
                    is BoardItem.App -> if (!editMode) onOpen(item.app)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 8.dp),
        )

        // Centred, because the dock no longer fills the width it is given -- see GlassDock. On a
        // phone this changes nothing (the bar is wider than the screen); on anything larger it is
        // what keeps the dock under the middle of the board instead of pinned to one edge.
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            GlassDock(
                apps = HomeDestinations.DOCK,
                tileSize = 56.dp,
                onOpen = { if (!editMode) onOpen(it) },
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 10.dp),
            )
        }
    }

    val folder = order.firstOrNull { it is BoardItem.Folder && it.key == openFolder } as BoardItem.Folder?
    if (folder != null) {
        val factoryTitle = stringResource(HomeDestinations.folderTitleRes(folder.key))
        FolderOverlay(
            folder = folder,
            startEditing = editMode,
            liveEngines = ActiveEngines.ids(),
            onOpenApp = { app ->
                openFolder = null
                onOpen(app)
            },
            onClose = { openFolder = null },
            onRename = { title ->
                commitLayout(
                    BoardLayoutRules.rename(HomeLayoutStore.toLayout(order), folder.key, title, factoryTitle)
                )
            },
            onReorder = { apps ->
                commitLayout(
                    BoardLayoutRules.reorderInside(HomeLayoutStore.toLayout(order), folder.key, apps.map { it.id })
                )
            },
            onMoveOut = { app ->
                commitLayout(BoardLayoutRules.moveOut(HomeLayoutStore.toLayout(order), folder.key, app.id))
            },
        )
    }
    }
}

/**
 * The strip where a phone would put its clock and battery. Ours carries the one thing a VPN app
 * has to answer at a glance -- am I protected -- plus the session counters when they are on.
 */
@Composable
private fun HomeStatusStrip(
    isRunning: Boolean,
    showTraffic: Boolean,
    trafficDown: Float,
    trafficUp: Float,
    editMode: Boolean,
    onDone: () -> Unit,
) {
    val green = Color(0xFF34C759)
    val muted = Color(0xFFB9BDC2)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Shield,
                contentDescription = null,
                tint = if (isRunning) green else muted.copy(alpha = 0.55f),
                modifier = Modifier.size(16.dp),
            )
        }

        if (editMode) {
            Text(
                text = stringResource(R.string.home_edit_done),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.16f))
                    .pointerInput(Unit) { detectTapGestures { onDone() } }
                    .padding(horizontal = 16.dp, vertical = 5.dp),
            )
        } else if (showTraffic) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = null,
                    tint = if (isRunning) green else muted,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    String.format("%.1f", trafficDown),
                    color = if (isRunning) green else muted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.width(12.dp))
                Icon(
                    Icons.Default.Upload,
                    contentDescription = null,
                    tint = if (isRunning) Color(0xFF5AC8FA) else muted,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    String.format("%.1f", trafficUp),
                    color = if (isRunning) Color(0xFF5AC8FA) else muted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/**
 * A 4 x 6 board, built as six Rows of four weighted cells.
 *
 * The first version placed every icon by absolute pixel offset inside a Box. That is the usual
 * way to build a draggable grid, and it is also how icons went missing: cell position, drag delta
 * and a per-item settle animation all fed one coordinate, so any of them being wrong put an icon
 * somewhere invisible with nothing on screen to say which. Real Rows cannot do that -- an icon is
 * in a cell or the cell is empty -- and they get right-to-left for free, which the manual version
 * had to mirror by hand and got backwards.
 *
 * Only the DRAGGED icon is displaced, and with `graphicsLayer { translationX }` rather than a
 * layout offset: translation is raw and is never mirrored by layout direction, which is exactly
 * what following a finger needs.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun IconGrid(
    order: List<BoardItem>,
    editMode: Boolean,
    onEnterEditMode: () -> Unit,
    onExitEditMode: () -> Unit,
    onReorder: (List<BoardItem>) -> Unit,
    /** An app held over another tile long enough: into that folder, or a new one with that app. */
    onMerge: (draggedId: String, targetId: String) -> Unit,
    onOpen: (BoardItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        // Read once for the whole board rather than per cell: twenty-odd AppIconCells would
        // otherwise each subscribe to the same four engine flows.
        val liveEngines = ActiveEngines.ids()

        val shape = boardShapeFor(maxWidth, maxHeight, order.size)
        val pageCount = shape.pageCount(order.size)
        // Declared here rather than hoisted, because its page count is a function of the measured
        // shape: a rotation changes how many icons fit and therefore how many pages exist.
        val pagerState = rememberPagerState(pageCount = { pageCount })
        // Clamped rather than remembered against the shape: rotating a phone while on the last
        // page of a taller board leaves `page` past the end of a shorter one, and a pager index
        // that no longer exists draws an empty board with no way back.
        val page = pagerState.currentPage.coerceIn(0, pageCount - 1)
        val pageStart = page * shape.perPage

        val cellW = maxWidth / shape.columns
        val cellH = maxHeight / shape.rows
        // 26dp covers the 6dp gap and the 11sp caption, so a tile can never push its own label
        // out of the cell on a short screen.
        // The ceiling rises with the cell. 64dp is right on a phone and far too small on a
        // television, where the same tile sat in a cell three times its size and read as a
        // postage stamp; the floor is unchanged because nothing gets smaller than it was.
        val tileCeiling = if (cellW >= 130.dp) 88.dp else 64.dp
        val baseTile = minOf(cellH - 26.dp, cellW - 14.dp).coerceIn(40.dp, tileCeiling)
        // ONLY the tile scales with the user's size setting. The cell pitch and the caption stay
        // where they are, which is what "make just the icon bigger" means -- scaling the cell as
        // well would merely re-space the board and leave the icons looking the same size. The
        // ceiling stops a large setting from pushing a tile over its own caption.
        val tileSize = (baseTile * (Appearance.iconScale / 100f))
            .coerceAtMost(minOf(cellH - 20.dp, cellW - 4.dp))

        val cellWpx = with(density) { cellW.toPx() }
        val cellHpx = with(density) { cellH.toPx() }

        var draggingId by remember { mutableStateOf<String?>(null) }

        // Where the icon WILL land. Tracked continuously while the finger moves, applied only
        // when it lifts.
        //
        // The first version reordered the list live, on every cell the finger crossed. Two things
        // were wrong with that. It looked like the icon jumping out from under the finger, which
        // is not what dragging should feel like; and because each swap moved the item's own home
        // cell, the offset had to be corrected against a moving origin, so the error compounded
        // and a long drag -- bottom row to top row -- drifted until it stopped tracking at all.
        // Deciding here and committing on release makes the arithmetic one subtraction from a
        // fixed start, so any distance works.
        val dropTarget = remember { mutableStateOf(-1) }

        /**
         * The cell the drag started from, or -1 when nothing is being dragged.
         *
         * Together with [dropTarget] this is enough for every OTHER icon to work out whether it
         * has to step aside, and by how much, without the list itself changing. That is the whole
         * trick: the gap opens visually while the order underneath stays exactly as it was until
         * the finger lifts. Both are read inside the graphics layer only, so opening the gap
         * costs a redraw and no recomposition.
         */
        val dragFrom = remember { mutableStateOf(-1) }

        // Deliberately a State read ONLY inside graphicsLayer, never in the composition body.
        // Reading it here would recompose all 24 cells on every touch move, and rebuilding a
        // node's modifier chain under an in-flight gesture is what dropped icons mid-drag while
        // the finger was still down. Read inside the layer, a move costs one redraw and no
        // recomposition at all.
        val dragDelta = remember { mutableStateOf(Offset.Zero) }

        // Holding an app over the MIDDLE of another tile offers a folder, the way iOS does:
        // [hoverCell] is the tile under the finger's centre zone and when it got there; after
        // [MERGE_DWELL_MS] it becomes [mergeTarget], the board stops making room (the gap closes,
        // so the tile slides back under the finger) and a plate is drawn behind it. Lifting then
        // drops the app INTO it. Moving on before the dwell is an ordinary reorder, so passing
        // over icons on the way somewhere never makes a folder by accident. All three are read
        // only in layers and in the dwell loop, never in the composition body.
        val hoverCell = remember { mutableStateOf(-1) }
        val hoverSince = remember { mutableStateOf(0L) }
        val mergeTarget = remember { mutableStateOf(-1) }
        val mergeRadiusPx = minOf(cellWpx, cellHpx) * 0.26f

        // A State for the same reason: animateFloat changes every frame, and reading it in the
        // composition body would recompose the whole board at 60fps.
        val jiggle: State<Float> = if (editMode) {
            // CRITICAL: this transition exists ONLY while edit mode is on. An infiniteRepeatable
            // left in the composition never stops requesting frames, which pins the whole app at
            // 60fps forever -- the failure documented in AppScreen's emergency vignette, where it
            // destabilised the GPU RenderThread and aborted swapBuffers. Declaring it inside the
            // branch removes it from the slot table the moment edit mode ends.
            val transition = rememberInfiniteTransition(label = "HomeJiggle")
            transition.animateFloat(
                initialValue = -1.7f,
                targetValue = 1.7f,
                animationSpec = infiniteRepeatable(
                    animation = tween(170, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "HomeJiggleAngle",
            )
        } else {
            remember { mutableStateOf(0f) }
        }

        // Read through these inside the gesture handlers, never through the parameters directly.
        // A drag reorders the list, and if `order` were a pointerInput key the modifier would be
        // torn down and rebuilt on the first swap, cancelling the gesture.
        val liveOrder by rememberUpdatedState(order)
        val liveOnReorder by rememberUpdatedState(onReorder)
        val liveOnMerge by rememberUpdatedState(onMerge)
        val liveOnOpen by rememberUpdatedState(onOpen)
        val liveOnEnterEditMode by rememberUpdatedState(onEnterEditMode)
        val liveOnExitEditMode by rememberUpdatedState(onExitEditMode)

        // Both of these speak GLOBAL indices into `order` and page-local pixels, because that
        // is what their two callers each already have: the drag arithmetic works in list indices,
        // and the pointer works in coordinates inside the page it is on. Doing the conversion
        // here keeps `pageStart` out of the drag code entirely.

        /** Which cell a point falls in, given in VISUAL pixels from this page's left edge. */
        fun indexAt(x: Float, y: Float, count: Int): Int {
            if (cellWpx <= 0f || cellHpx <= 0f) return pageStart
            val visualCol = (x / cellWpx).toInt().coerceIn(0, shape.columns - 1)
            val col = if (rtl) shape.columns - 1 - visualCol else visualCol
            val row = (y / cellHpx).toInt().coerceIn(0, shape.rows - 1)
            val local = row * shape.columns + col
            // Bounded by the page as well as by the list: without the first bound a drag towards
            // the empty cells at the end of a part-filled page would report an index belonging to
            // the NEXT page, and the icon would vanish from the board on release.
            return (pageStart + local).coerceIn(pageStart, minOf(pageStart + shape.perPage, count) - 1)
        }

        /** The centre of the cell holding global `index`, in that same visual pixel space. */
        fun centreOf(index: Int): Offset {
            val local = index - pageStart
            val row = local / shape.columns
            val col = local % shape.columns
            val visualCol = if (rtl) shape.columns - 1 - col else col
            return Offset((visualCol + 0.5f) * cellWpx, (row + 0.5f) * cellHpx)
        }

        // Runs only while something is being dragged: keyed on the id, it returns at once when
        // the drag ends, so there is no loop left ticking on an idle board.
        LaunchedEffect(draggingId) {
            if (draggingId == null) return@LaunchedEffect
            while (true) {
                kotlinx.coroutines.delay(60)
                val cell = hoverCell.value
                if (cell >= 0 && mergeTarget.value != cell &&
                    android.os.SystemClock.uptimeMillis() - hoverSince.value >= MERGE_DWELL_MS
                ) {
                    mergeTarget.value = cell
                    dropTarget.value = dragFrom.value
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                // A tap anywhere the icons are not finishes rearranging, the same as the "Done"
                // button. Only the empty space reaches this: an icon's own tap detector consumes
                // the press, so the parent never sees it.
                .then(
                    if (editMode) {
                        Modifier.pointerInput(Unit) {
                            detectTapGestures { liveOnExitEditMode() }
                        }
                    } else {
                        Modifier
                    }
                )
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                // Swiping is off while rearranging: in edit mode a horizontal drag IS the gesture
                // that moves an icon, and letting the pager claim it would turn every attempt to
                // move one sideways into a page turn.
                userScrollEnabled = !editMode && pageCount > 1,
            ) { pageIndex ->
            val pageOffset = pageIndex * shape.perPage
            Column(modifier = Modifier.fillMaxSize()) {
            repeat(shape.rows) { row ->
                Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    repeat(shape.columns) { col ->
                        val index = pageOffset + row * shape.columns + col
                        val item = order.getOrNull(index)
                        if (item == null) {
                            Spacer(modifier = Modifier.weight(1f).fillMaxSize())
                        } else {
                            val isDragging = draggingId == item.id
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .zIndex(if (isDragging) 1f else 0f)
                                    // Shows where the icon will land. Read inside the draw lambda,
                                    // so a new target costs one redraw and no recomposition -- the
                                    // same discipline the drag offset uses.
                                    .drawBehind {
                                        if (mergeTarget.value == index && !isDragging) {
                                            // The folder plate: brighter and larger than the
                                            // landing mark, so "into this" never reads as "here".
                                            val r = size.minDimension * 0.24f
                                            drawRoundRect(
                                                color = Color.White.copy(alpha = 0.24f),
                                                cornerRadius = CornerRadius(r, r),
                                            )
                                        } else if (dropTarget.value == index && !isDragging &&
                                            mergeTarget.value < 0
                                        ) {
                                            val r = size.minDimension * 0.22f
                                            drawRoundRect(
                                                color = Color.White.copy(alpha = 0.10f),
                                                cornerRadius = CornerRadius(r, r),
                                            )
                                        }
                                    }
                                    .graphicsLayer {
                                        if (isDragging) {
                                            translationX = dragDelta.value.x
                                            translationY = dragDelta.value.y
                                            scaleX = 1.12f
                                            scaleY = 1.12f
                                            alpha = 0.92f
                                        } else if (mergeTarget.value == index) {
                                            // Swells a little: this is where it will go INTO.
                                            scaleX = 1.08f
                                            scaleY = 1.08f
                                        } else {
                                            // Step aside so a gap opens where the icon will land.
                                            // Everything between the cell it left and the cell it
                                            // is over shuffles one place towards the vacancy --
                                            // the same shuffle the list will perform for real on
                                            // release, previewed here in the layer.
                                            val from = dragFrom.value
                                            val to = dropTarget.value
                                            if (from >= 0 && to >= 0) {
                                                val shifted = when {
                                                    from < to && index > from && index <= to ->
                                                        index - 1
                                                    from > to && index >= to && index < from ->
                                                        index + 1
                                                    else -> index
                                                }
                                                if (shifted != index) {
                                                    val step = centreOf(shifted) - centreOf(index)
                                                    translationX = step.x
                                                    translationY = step.y
                                                }
                                            }
                                        }
                                        // Alternating phase so the board looks alive rather than
                                        // metronomic.
                                        rotationZ =
                                            if (index % 2 == 0) jiggle.value else -jiggle.value
                                    }
                                    // Added beside the tap detector rather than instead of it:
                                    // a DeX window and a tablet with a keyboard have both a
                                    // pointer and a focus ring, and a phone simply never takes
                                    // focus because nothing moves it there.
                                    .tvFocusable(onPress = { liveOnOpen(item) })
                                    .pointerInput(item.id) {
                                        detectTapGestures { liveOnOpen(item) }
                                    }
                                    .pointerInput(item.id, cellWpx, cellHpx, rtl) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                liveOnEnterEditMode()
                                                draggingId = item.id
                                                dragDelta.value = Offset.Zero
                                                val start =
                                                    liveOrder.indexOfFirst { it.id == item.id }
                                                dragFrom.value = start
                                                dropTarget.value = start
                                                hoverCell.value = -1
                                                mergeTarget.value = -1
                                            },
                                            onDrag = { change, delta ->
                                                change.consume()
                                                dragDelta.value += delta

                                                val ordered = liveOrder
                                                val from = ordered.indexOfFirst { it.id == item.id }
                                                if (from >= 0) {
                                                    // `from` does not move during the drag, so
                                                    // this stays exact however far the finger
                                                    // travels.
                                                    val here = centreOf(from) + dragDelta.value
                                                    val target = indexAt(here.x, here.y, ordered.size)
                                                    // Only an app goes into a folder; a folder is
                                                    // never put inside another.
                                                    val nearCentre = ordered.getOrNull(from) is BoardItem.App &&
                                                        target != from &&
                                                        (here - centreOf(target)).getDistance() < mergeRadiusPx
                                                    val candidate = if (nearCentre) target else -1
                                                    if (candidate != hoverCell.value) {
                                                        hoverCell.value = candidate
                                                        hoverSince.value = android.os.SystemClock.uptimeMillis()
                                                        mergeTarget.value = -1
                                                    }
                                                    dropTarget.value =
                                                        if (mergeTarget.value >= 0) from else target
                                                }
                                            },
                                            onDragEnd = {
                                                val ordered = liveOrder
                                                val from = ordered.indexOfFirst { it.id == item.id }
                                                val target = dropTarget.value
                                                val into = mergeTarget.value
                                                if (from >= 0 && into >= 0 && into != from) {
                                                    ordered.getOrNull(into)?.let { liveOnMerge(item.id, it.id) }
                                                } else if (from >= 0 && target >= 0 && target != from) {
                                                    val moved = ordered.toMutableList()
                                                    moved.add(target, moved.removeAt(from))
                                                    liveOnReorder(moved)
                                                }
                                                draggingId = null
                                                dragDelta.value = Offset.Zero
                                                dropTarget.value = -1
                                                dragFrom.value = -1
                                                hoverCell.value = -1
                                                mergeTarget.value = -1
                                            },
                                            onDragCancel = {
                                                // Cancelled rather than dropped: put it back.
                                                draggingId = null
                                                dragDelta.value = Offset.Zero
                                                dropTarget.value = -1
                                                dragFrom.value = -1
                                                hoverCell.value = -1
                                                mergeTarget.value = -1
                                            },
                                        )
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                when (item) {
                                    is BoardItem.App -> AppIconCell(
                                        app = item.app,
                                        tileSize = tileSize,
                                        connected = item.id in liveEngines,
                                    )
                                    is BoardItem.Folder -> FolderIconCell(
                                        folder = item,
                                        tileSize = tileSize,
                                        connected = item.apps.any { it.id in liveEngines },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            }
            }

            if (pageCount > 1) PageDots(count = pageCount, current = page)
        }
    }
}

/** How long an app has to be held over a tile before the board offers a folder. */
private const val MERGE_DWELL_MS = 420L

/**
 * The row of dots under a board that has more than one page.
 *
 * Drawn only when there IS more than one page. A single dot under a board that cannot be swiped
 * is an invitation to try, and the thing it invites does nothing.
 */
@Composable
private fun PageDots(count: Int, current: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(7.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        Color.White.copy(alpha = if (i == current) 0.85f else 0.28f)
                    )
            )
        }
    }
}
