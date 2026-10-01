package com.mlmvpn.scanner.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.R
import kotlinx.coroutines.launch

/** The folder tile's own squircle -- the same curve every app tile uses. */
internal val FolderShape = RoundedCornerShape(percent = 26)

/** An open folder's panel: iOS's large rounded square. */
private val PanelShape = RoundedCornerShape(36.dp)

/** An open folder holds three by three icons a page, the way iOS does on a phone. */
private const val FOLDER_COLUMNS = 3
private const val FOLDER_ROWS = 3

/**
 * A folder's tile: glass holding a 3x3 miniature of its first members.
 *
 * Glass rather than a colour, because it is a container, not an app: on iOS and on the Windows
 * desktop alike a folder is recognisable at a glance by being the one tile you can see through.
 * The miniatures are the members' real tiles, so a user finds the tool they want by its look
 * before ever opening the folder.
 */
@Composable
internal fun FolderTile(
    folder: BoardItem.Folder,
    tileSize: Dp,
    connected: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.size(tileSize)) {
        Box(
            modifier = Modifier
                .size(tileSize)
                .frostedGlass(FolderShape, overWallpaper = true, underScrim = false),
            contentAlignment = Alignment.Center,
        ) {
            val pad = tileSize * 0.13f
            val gap = tileSize * 0.055f
            val mini = (tileSize - pad * 2 - gap * 2) / 3
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                for (row in 0 until 3) {
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        for (col in 0 until 3) {
                            val app = folder.apps.getOrNull(row * 3 + col)
                            if (app != null) TileFace(app = app, size = mini, shadow = false)
                            else Spacer(Modifier.size(mini))
                        }
                    }
                }
            }
        }
        if (connected) TileLamp(size = tileSize)
    }
}

/** What a folder is called right now: the user's name for it, or the factory one in this language. */
@Composable
internal fun folderTitle(folder: BoardItem.Folder): String =
    folder.title ?: stringResource(HomeDestinations.folderTitleRes(folder.key))

/**
 * An open folder: its apps on a glass panel that grows out of the folder's own tile, over a board
 * gone soft behind it -- the way iOS opens one.
 *
 * Not a dialog and not a new screen: the board stays right there underneath, and a tap anywhere
 * outside the panel shrinks the folder back into its tile. Everything iOS lets you do with a
 * folder can be done here:
 *  - tap an app to open it;
 *  - hold one to start rearranging (or, while rearranging, just drag it) -- the others slide
 *    aside, and more than nine apps page sideways, holding at the panel's edge turning the page;
 *  - drag one out past the panel's edge: the folder closes behind it and it can be put down
 *    anywhere on the board, onto another app (a new folder) or into another folder;
 *  - while rearranging, the name at the top is a text field; holding it does the same otherwise.
 *
 * [apps] and [gap] are what to show: the members, and during a drag the members without the one
 * in the air and the place it would land.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FolderOverlay(
    folder: BoardItem.Folder,
    apps: List<HomeApp>,
    gap: Int?,
    /** False while it closes: it no longer takes drags or gestures. */
    open: Boolean,
    progress: State<Float>,
    /** The folder's tile on the board, for the panel to grow out of and shrink back into. */
    origin: () -> Rect?,
    editMode: Boolean,
    drag: HomeDrag,
    liveEngines: Set<String>,
    onEnterEditMode: () -> Unit,
    onExitEditMode: () -> Unit,
    onOpenApp: (HomeApp) -> Unit,
    onClose: () -> Unit,
    onRename: (String) -> Unit,
) {
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }

    val shownTitle = folderTitle(folder)
    var draft by remember(folder.key) { mutableStateOf(TextFieldValue(shownTitle)) }
    var titleFocused by remember { mutableStateOf(false) }
    // A name changed elsewhere (or the language) shows here, unless the user is typing.
    LaunchedEffect(shownTitle) { if (!titleFocused) draft = TextFieldValue(shownTitle) }

    fun finishRename() {
        if (draft.text.trim() != shownTitle) onRename(draft.text)
        focus.clearFocus()
    }
    // The name is kept the moment editing ends, however it ends.
    val liveFinish by rememberUpdatedState(::finishRename)
    LaunchedEffect(editMode, open) { if (!editMode || !open) { if (titleFocused || draft.text.trim() != shownTitle) liveFinish() } }

    BackHandler(enabled = open) { if (titleFocused) finishRename() else onClose() }

    // Ends the panel's part in drags when it goes; a closing panel already gave it up.
    DisposableEffect(folder.key) {
        onDispose {
            if (drag.folderPanelKey == folder.key) {
                drag.folderPanel = null
                drag.folderGeometry = null
                drag.folderPanelKey = null
            }
        }
    }

    val liveEditMode by rememberUpdatedState(editMode)
    val jiggle: State<Float> = if (editMode && open) {
        // Only while rearranging: an infinite transition left in composition never stops asking
        // for frames (see BoardGrid's note on the RenderThread crash it once caused).
        rememberInfiniteTransition(label = "FolderJiggle").animateFloat(
            initialValue = -1.7f,
            targetValue = 1.7f,
            animationSpec = infiniteRepeatable(
                animation = tween(170, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "FolderJiggleAngle",
        )
    } else {
        remember { mutableStateOf(0f) }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // The dim that a tap outside the panel lands on. During a drag the tracker has the finger,
        // so this never mistakes the end of one for a tap.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress.value }
                .background(Color.Black.copy(alpha = 0.24f))
                .pointerInput(open) {
                    if (open) detectTapGestures { if (titleFocused) finishRename() else onClose() }
                },
        )

        val panelW = minOf(maxWidth - 48.dp, 360.dp)
        val innerPad = 14.dp
        val vPad = 18.dp
        val gridW = panelW - innerPad * 2
        val cellW = gridW / FOLDER_COLUMNS
        val tile = (minOf(cellW - 22.dp, 64.dp) * (Appearance.iconScale / 100f)).coerceIn(40.dp, 80.dp)
        val cellH = tile + 34.dp
        val gridH = cellH * FOLDER_ROWS
        val perPage = FOLDER_COLUMNS * FOLDER_ROWS
        val slots = apps.size + if (gap != null) 1 else 0
        val pageCount = ((slots + perPage - 1) / perPage).coerceAtLeast(1)
        val dotsH = if (pageCount > 1) 15.dp else 0.dp
        val pagerState = rememberPagerState(pageCount = { pageCount })
        val tilePx = with(density) { tile.toPx() }
        val contentHeight = remember { mutableFloatStateOf(0f) }
        var panelRect by remember { mutableStateOf<Rect?>(null) }

        fun publish(coords: LayoutCoordinates? = null) {
            val root = drag.root ?: return
            if (coords != null) panelRect = root.localBoundingBoxOf(coords, clipBounds = false)
            val panel = panelRect ?: return
            if (!open) return
            val padPx = with(density) { innerPad.toPx() }
            val vPadPx = with(density) { vPad.toPx() }
            val grid = Rect(panel.left + padPx, panel.top + vPadPx, panel.right - padPx, panel.top + vPadPx + with(density) { gridH.toPx() })
            val cellHpx = grid.height / FOLDER_ROWS
            val content = contentHeight.floatValue.takeIf { it > 0f } ?: (tilePx + with(density) { 22.dp.toPx() })
            drag.folderPanel = panel
            drag.folderPanelKey = folder.key
            drag.folderGeometry = GridGeometry(
                bounds = grid,
                columns = FOLDER_COLUMNS,
                rows = FOLDER_ROWS,
                page = pagerState.currentPage.coerceIn(0, pageCount - 1),
                pageCount = pageCount,
                tilePx = tilePx,
                tileTopPx = ((cellHpx - content) / 2f).coerceAtLeast(0f),
                rtl = rtl,
            )
        }
        LaunchedEffect(open, pagerState.currentPage, pageCount, tilePx, contentHeight.floatValue) {
            if (open) publish()
            else if (drag.folderPanelKey == folder.key) {
                drag.folderPanel = null
                drag.folderGeometry = null
                drag.folderPanelKey = null
            }
        }
        drag.flipFolder = { step ->
            val to = (pagerState.currentPage + step).coerceIn(0, pageCount - 1)
            scope.launch { pagerState.animateScrollToPage(to) }
        }

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (editMode) {
                Text(
                    text = stringResource(R.string.home_edit_done),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .graphicsLayer { alpha = progress.value }
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.16f))
                        .pointerInput(Unit) {
                            detectTapGestures {
                                finishRename()
                                onExitEditMode()
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 5.dp),
                )
                Spacer(Modifier.height(14.dp))
            }

            FolderName(
                value = draft,
                onValueChange = { draft = it.copy(text = it.text.take(40)) },
                editable = editMode,
                focused = titleFocused,
                maxWidth = panelW,
                focusRequester = focusRequester,
                onFocusChange = { titleFocused = it },
                onDone = { finishRename() },
                onClear = { draft = TextFieldValue("") },
                onHold = {
                    // Holding the name renames it, as on iOS: into edit mode, then into the field.
                    onEnterEditMode()
                    draft = draft.copy(selection = TextRange(0, draft.text.length))
                    scope.launch { runCatching { focusRequester.requestFocus() } }
                },
                modifier = Modifier.graphicsLayer {
                    val t = progress.value
                    alpha = t
                    translationY = (1f - t) * 24f
                },
            )
            Spacer(Modifier.height(18.dp))

            Box(
                modifier = Modifier
                    .width(panelW)
                    .height(gridH + vPad * 2 + dotsH)
                    // Measured BEFORE the layer below, so the panel's place is where it ends up,
                    // not wherever the opening animation has it this frame.
                    .onGloballyPositioned { publish(it) }
                    .graphicsLayer {
                        val t = progress.value
                        val from = origin()
                        val to = panelRect
                        if (from != null && to != null && to.width > 0f) {
                            // Grows out of the tile: at the start it IS the tile, three by three
                            // icons and all, and it opens into the panel around them.
                            val start = from.width / to.width
                            val s = start + (1f - start) * t
                            scaleX = s
                            scaleY = s
                            translationX = (from.center.x - to.center.x) * (1f - t)
                            translationY = (from.center.y - to.center.y) * (1f - t)
                        } else {
                            val s = 0.86f + 0.14f * t
                            scaleX = s
                            scaleY = s
                        }
                        alpha = (t * 4f).coerceIn(0f, 1f)
                    }
                    .frostedGlass(PanelShape, overWallpaper = true, underScrim = false)
                    // Swallow taps on the panel's own background: only outside it closes. Only while
                    // open: a panel shrinking back into its tile leaves the board answering at once.
                    .pointerInput(open) { if (open) detectTapGestures { } }
                    .padding(horizontal = innerPad, vertical = vPad),
            ) {
                Column {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.width(gridW).height(gridH),
                        userScrollEnabled = pageCount > 1 && !drag.active && open,
                    ) { pageIndex ->
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
                            val cellWpx = with(density) { cellW.toPx() }
                            val cellHpx = with(density) { cellH.toPx() }
                            val first = pageIndex * perPage
                            apps.forEachIndexed { index, app ->
                                val visual = if (gap != null && index >= gap) index + 1 else index
                                if (visual < first || visual >= first + perPage) return@forEachIndexed
                                val slot = visual - first
                                val row = slot / FOLDER_COLUMNS
                                val col = slot % FOLDER_COLUMNS
                                val visualCol = if (rtl) FOLDER_COLUMNS - 1 - col else col
                                key(app.id) {
                                    val position = rememberSlotPosition(Offset(visualCol * cellWpx, row * cellHpx))
                                    BoardCell(
                                        item = BoardItem.App(app),
                                        drag = drag,
                                        fromFolder = folder.key,
                                        tileSize = tile,
                                        tilePx = tilePx,
                                        editMode = editMode,
                                        liveEditMode = { liveEditMode },
                                        jiggle = jiggle,
                                        jigglePhase = if (visual % 2 == 0) 1f else -1f,
                                        connected = app.id in liveEngines,
                                        hiddenBy = null,
                                        contentHeight = contentHeight,
                                        onTap = { if (!liveEditMode && open) onOpenApp(app) },
                                        onEnterEditMode = onEnterEditMode,
                                        onRemove = null,
                                        modifier = Modifier
                                            .absoluteOffset { position.value.toIntOffset() }
                                            .size(cellW, cellH),
                                    )
                                }
                            }
                        }
                    }
                    if (pageCount > 1) {
                        PageDots(
                            count = pageCount,
                            current = pagerState.currentPage.coerceIn(0, pageCount - 1),
                            modifier = Modifier.width(gridW),
                        )
                    }
                }
            }

            if (editMode) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.home_folder_drag_out_hint),
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .widthIn(max = panelW)
                        .graphicsLayer { alpha = progress.value },
                )
            }
        }
    }
}

/**
 * The folder's name over its panel. While rearranging it is a text field in a pill, with iOS's
 * clear button; otherwise plain bold text, which a hold turns into the field.
 */
@Composable
private fun FolderName(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    editable: Boolean,
    focused: Boolean,
    maxWidth: Dp,
    focusRequester: FocusRequester,
    onFocusChange: (Boolean) -> Unit,
    onDone: () -> Unit,
    onClear: () -> Unit,
    onHold: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = TextStyle(
        color = Color.White,
        fontSize = 26.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        shadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 1f), 6f),
    )
    if (!editable) {
        Text(
            text = value.text,
            style = style,
            maxLines = 1,
            modifier = modifier
                .widthIn(max = maxWidth)
                .pointerInput(Unit) { detectTapGestures(onLongPress = { onHold() }) },
        )
        return
    }
    val hint = stringResource(R.string.home_folder_name)
    val clearLabel = stringResource(R.string.home_folder_clear_name)
    Row(
        modifier = modifier
            .widthIn(min = 160.dp, max = maxWidth)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.16f))
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = style.copy(shadow = null),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier
                .weight(1f, fill = false)
                .widthIn(min = 120.dp)
                .semantics { contentDescription = hint }
                .focusRequester(focusRequester)
                .onFocusChanged { onFocusChange(it.isFocused) },
        )
        // The clear button iOS puts in a field being edited -- only while there is text to clear.
        Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            if (focused && value.text.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.35f))
                        .semantics { contentDescription = clearLabel }
                        .pointerInput(Unit) { detectTapGestures { onClear() } },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, tint = Color(0xFF2C2C2E), modifier = Modifier.size(13.dp))
                }
            }
        }
    }
}
