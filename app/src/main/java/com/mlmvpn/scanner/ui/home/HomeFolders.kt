package com.mlmvpn.scanner.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.runtime.State
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.mlmvpn.scanner.R

/** The folder tile's own squircle -- the same curve every app tile uses. */
private val FolderShape = RoundedCornerShape(percent = 26)

/**
 * A folder on the board: a glass tile holding a 3x3 miniature of its members, and its name.
 *
 * Glass rather than a colour, because it is a container, not an app: on iOS and on the Windows
 * desktop alike a folder is recognisable at a glance by being the one tile you can see through.
 * The miniatures are the members' real tiles, so a user finds the tool they want by its look
 * before ever opening the folder.
 */
@Composable
internal fun FolderIconCell(
    folder: BoardItem.Folder,
    tileSize: Dp,
    connected: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.size(tileSize)) {
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
        Spacer(Modifier.height(6.dp))
        TileCaption(folderTitle(folder))
    }
}

/** What a folder is called right now: the user's name for it, or the factory one in this language. */
@Composable
internal fun folderTitle(folder: BoardItem.Folder): String =
    folder.title ?: stringResource(HomeDestinations.folderTitleRes(folder.key))

/**
 * An open folder: its members on a glass panel over a dimmed board, the way iOS opens one.
 *
 * Not a modal dialog and not a new screen -- the board stays right there underneath, dimmed, and a
 * tap anywhere outside the panel puts it back. Everything a user can do to a folder on the Windows
 * desktop can be done here:
 *  - tap a member to open it;
 *  - hold one to start rearranging, then drag it to a new place inside the panel;
 *  - drag it OUT of the panel to take it out of the folder (it lands on the board beside it);
 *  - while rearranging, the name at the top is a text field.
 *
 * Moves are decided when the finger lifts, never while it travels -- the same rule the board's own
 * drag follows, for the same reason: a list that changes under a moving finger drifts.
 */
@Composable
internal fun FolderOverlay(
    folder: BoardItem.Folder,
    startEditing: Boolean,
    liveEngines: Set<String>,
    onOpenApp: (HomeApp) -> Unit,
    onClose: () -> Unit,
    onRename: (String) -> Unit,
    onReorder: (List<HomeApp>) -> Unit,
    onMoveOut: (HomeApp) -> Unit,
) {
    var editing by remember(folder.key) { mutableStateOf(startEditing) }
    val shownTitle = folderTitle(folder)
    var draft by remember(folder.key) { mutableStateOf(shownTitle) }
    val focus = LocalFocusManager.current

    fun finishEditing() {
        if (draft.trim() != shownTitle) onRename(draft)
        focus.clearFocus()
        editing = false
    }

    BackHandler { if (editing) finishEditing() else onClose() }

    val appear = remember(folder.key) { Animatable(0f) }
    LaunchedEffect(folder.key) { appear.animateTo(1f, tween(durationMillis = 170)) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = appear.value }
            .background(Color.Black.copy(alpha = 0.42f))
            .pointerInput(folder.key) {
                detectTapGestures { if (editing) finishEditing() else onClose() }
            },
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val panelW = minOf(maxWidth - 40.dp, 380.dp)
            val columns = 3
            val innerPad = 12.dp
            val cellW = (panelW - innerPad * 2) / columns
            val tile = (minOf(cellW - 20.dp, 64.dp) * (Appearance.iconScale / 100f))
                .coerceIn(40.dp, 80.dp)
            val cellH = tile + 34.dp

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer {
                    // Grows out of the board a little, the way a folder opens on iOS.
                    val s = 0.88f + 0.12f * appear.value
                    scaleX = s
                    scaleY = s
                },
            ) {
                if (editing) {
                    Text(
                        text = stringResource(R.string.home_edit_done),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(alpha = 0.16f))
                            .pointerInput(Unit) { detectTapGestures { finishEditing() } }
                            .padding(horizontal = 16.dp, vertical = 5.dp),
                    )
                    Spacer(Modifier.height(14.dp))
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it.take(40) },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        ),
                        cursorBrush = SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { finishEditing() }),
                        modifier = Modifier
                            .widthIn(min = 140.dp, max = panelW)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.16f))
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                } else {
                    Text(
                        text = shownTitle,
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        style = TextStyle(
                            shadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 1f), 6f),
                        ),
                    )
                }
                Spacer(Modifier.height(16.dp))

                Box(
                    modifier = Modifier
                        .width(panelW)
                        .frostedGlass(RoundedCornerShape(34.dp), overWallpaper = true, underScrim = false)
                        // Swallow taps on the panel's own background: only outside it closes.
                        .pointerInput(Unit) { detectTapGestures { } }
                        .padding(horizontal = innerPad, vertical = 16.dp),
                ) {
                    FolderGrid(
                        apps = folder.apps,
                        columns = columns,
                        cellW = cellW,
                        cellH = cellH,
                        tile = tile,
                        editing = editing,
                        liveEngines = liveEngines,
                        onStartEditing = { editing = true },
                        onOpen = { if (!editing) onOpenApp(it) },
                        onReorder = onReorder,
                        onMoveOut = onMoveOut,
                    )
                }

                if (editing) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.home_folder_drag_out_hint),
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = panelW),
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderGrid(
    apps: List<HomeApp>,
    columns: Int,
    cellW: Dp,
    cellH: Dp,
    tile: Dp,
    editing: Boolean,
    liveEngines: Set<String>,
    onStartEditing: () -> Unit,
    onOpen: (HomeApp) -> Unit,
    onReorder: (List<HomeApp>) -> Unit,
    onMoveOut: (HomeApp) -> Unit,
) {
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val rows = (apps.size + columns - 1) / columns
    val cellWpx = with(density) { cellW.toPx() }
    val cellHpx = with(density) { cellH.toPx() }
    val gridWpx = cellWpx * columns
    val gridHpx = cellHpx * rows
    // How far past the grid's edge a drop has to land to count as "out of the folder": the
    // panel's own margin, so the whole panel is "inside" and everything beyond it is "out".
    val outMarginPx = with(density) { 14.dp.toPx() }

    var draggingId by remember { mutableStateOf<String?>(null) }
    // Read only inside draw / graphics layers, never in the composition body -- the same
    // discipline IconGrid keeps, so a moving finger costs a redraw and no recomposition.
    val dragDelta = remember { mutableStateOf(Offset.Zero) }
    val dropTarget = remember { mutableStateOf(-1) }
    val dropOut = remember { mutableStateOf(false) }

    val liveApps by rememberUpdatedState(apps)
    val liveOnReorder by rememberUpdatedState(onReorder)
    val liveOnMoveOut by rememberUpdatedState(onMoveOut)
    val liveOnOpen by rememberUpdatedState(onOpen)
    val liveOnStartEditing by rememberUpdatedState(onStartEditing)

    val jiggle: State<Float> = if (editing) {
        // Only while rearranging: an infinite transition left in composition never stops asking
        // for frames (see IconGrid's note on the RenderThread crash it once caused).
        val transition = rememberInfiniteTransition(label = "FolderJiggle")
        transition.animateFloat(
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

    fun centreOf(index: Int): Offset {
        val row = index / columns
        val col = index % columns
        val visualCol = if (rtl) columns - 1 - col else col
        return Offset((visualCol + 0.5f) * cellWpx, (row + 0.5f) * cellHpx)
    }

    fun indexAt(p: Offset, count: Int): Int {
        val visualCol = (p.x / cellWpx).toInt().coerceIn(0, columns - 1)
        val col = if (rtl) columns - 1 - visualCol else visualCol
        val row = (p.y / cellHpx).toInt().coerceIn(0, rows - 1)
        return (row * columns + col).coerceIn(0, count - 1)
    }

    fun isOut(p: Offset): Boolean =
        p.x < -outMarginPx || p.x > gridWpx + outMarginPx ||
            p.y < -outMarginPx || p.y > gridHpx + outMarginPx

    Column {
        for (row in 0 until rows) {
            Row {
                for (col in 0 until columns) {
                    val index = row * columns + col
                    val app = apps.getOrNull(index)
                    if (app == null) {
                        Spacer(Modifier.width(cellW).height(cellH))
                        continue
                    }
                    val isDragging = draggingId == app.id
                    Box(
                        modifier = Modifier
                            .width(cellW)
                            .height(cellH)
                            .zIndex(if (isDragging) 1f else 0f)
                            .drawBehind {
                                if (dropTarget.value == index && !isDragging && !dropOut.value) {
                                    val r = size.minDimension * 0.22f
                                    drawRoundRect(
                                        color = Color.White.copy(alpha = 0.12f),
                                        cornerRadius = CornerRadius(r, r),
                                    )
                                }
                            }
                            .graphicsLayer {
                                if (isDragging) {
                                    translationX = dragDelta.value.x
                                    translationY = dragDelta.value.y
                                    val s = if (dropOut.value) 1.0f else 1.12f
                                    scaleX = s
                                    scaleY = s
                                    alpha = if (dropOut.value) 0.75f else 0.92f
                                }
                                rotationZ = if (index % 2 == 0) jiggle.value else -jiggle.value
                            }
                            .tvFocusable(onPress = { liveOnOpen(app) })
                            .pointerInput(app.id) {
                                detectTapGestures { liveOnOpen(app) }
                            }
                            .pointerInput(app.id, cellWpx, cellHpx, rtl, rows) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        liveOnStartEditing()
                                        draggingId = app.id
                                        dragDelta.value = Offset.Zero
                                        dropTarget.value = liveApps.indexOfFirst { it.id == app.id }
                                        dropOut.value = false
                                    },
                                    onDrag = { change, delta ->
                                        change.consume()
                                        dragDelta.value += delta
                                        val list = liveApps
                                        val from = list.indexOfFirst { it.id == app.id }
                                        if (from >= 0) {
                                            val here = centreOf(from) + dragDelta.value
                                            dropOut.value = isOut(here)
                                            dropTarget.value = if (dropOut.value) -1 else indexAt(here, list.size)
                                        }
                                    },
                                    onDragEnd = {
                                        val list = liveApps
                                        val from = list.indexOfFirst { it.id == app.id }
                                        val target = dropTarget.value
                                        when {
                                            from < 0 -> Unit
                                            dropOut.value -> liveOnMoveOut(list[from])
                                            target >= 0 && target != from -> {
                                                val moved = list.toMutableList()
                                                moved.add(target, moved.removeAt(from))
                                                liveOnReorder(moved)
                                            }
                                        }
                                        draggingId = null
                                        dragDelta.value = Offset.Zero
                                        dropTarget.value = -1
                                        dropOut.value = false
                                    },
                                    onDragCancel = {
                                        draggingId = null
                                        dragDelta.value = Offset.Zero
                                        dropTarget.value = -1
                                        dropOut.value = false
                                    },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        AppIconCell(
                            app = app,
                            tileSize = tile,
                            connected = app.id in liveEngines,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
