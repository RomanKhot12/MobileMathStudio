package com.example.mobilemathstudio.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.example.mobilemathstudio.CrashGuard
import com.example.mobilemathstudio.R
import com.example.mobilemathstudio.settings.AnswerLayout
import com.example.mobilemathstudio.settings.DecimalDisplay
import com.example.mobilemathstudio.ui.canvas.CellKind
import com.example.mobilemathstudio.ui.canvas.InputCell
import com.example.mobilemathstudio.ui.canvas.SlotRef
import com.example.mobilemathstudio.ui.canvas.TypeCanvasState
import com.example.mobilemathstudio.ui.components.FormulaContextMenu
import com.example.mobilemathstudio.ui.components.LatexImportDialog
import com.example.mobilemathstudio.ui.components.LongNumberDialog
import com.example.mobilemathstudio.ui.components.SelectionActionBar
import com.example.mobilemathstudio.ui.components.SystemContextMenu
import com.example.mobilemathstudio.ui.glass.CardLiquidGlassBackdrop
import com.example.mobilemathstudio.ui.graph.GraphBoard
import com.example.mobilemathstudio.ui.graph.GraphLinkYellow
import com.example.mobilemathstudio.ui.graph.GraphSeriesPalette
import com.example.mobilemathstudio.ui.graph.GraphTrace
import com.example.mobilemathstudio.math.graph.GraphViewport
import com.example.mobilemathstudio.ui.math.AnswerView
import com.example.mobilemathstudio.ui.math.StructuredMathView
import com.example.mobilemathstudio.ui.math.snapTintOf
import com.example.mobilemathstudio.ui.theme.GraphSeriesPaletteLight
import com.example.mobilemathstudio.ui.theme.LocalAccentColor
import com.example.mobilemathstudio.ui.theme.LocalAnswerLayout
import com.example.mobilemathstudio.ui.theme.LocalDecimalPlaces
import com.example.mobilemathstudio.ui.theme.LocalIsDarkTheme
import com.example.mobilemathstudio.ui.theme.WinCanvas
import com.example.mobilemathstudio.ui.theme.WinDanger
import com.example.mobilemathstudio.ui.theme.WinLightCanvas
import com.example.mobilemathstudio.ui.theme.WinLightDanger
import com.example.mobilemathstudio.ui.theme.WinLightGraphAccent
import com.example.mobilemathstudio.ui.theme.WinLightGridLine
import com.example.mobilemathstudio.ui.theme.WinLightTextMuted
import com.example.mobilemathstudio.ui.theme.WinLightTextPrimary
import com.example.mobilemathstudio.ui.theme.WinTextMuted
import com.example.mobilemathstudio.ui.theme.WinTextSecondary
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Screen rectangle of one input slot, used for snapping and slot menus. */
private data class SlotInfo(val rect: Rect, val empty: Boolean)

@Composable
fun TypeModeWorkspace(
    state: TypeCanvasState,
    bottomInsetDp: Float = 0f,
    topInsetDp: Float = 92f,
    rootModifier: Modifier = Modifier,
) {
    val accent = LocalAccentColor.current
    val dark = LocalIsDarkTheme.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cellBounds = remember { mutableStateMapOf<String, Rect>() }
    val slotBounds = remember { mutableStateMapOf<SlotRef, SlotInfo>() }
    var rootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var menuRect by remember { mutableStateOf(Rect.Zero) }
    var menuSize by remember { mutableStateOf(IntSize.Zero) }
    var latexTargetId by remember { mutableStateOf<String?>(null) }
    var inspecting by remember { mutableStateOf<String?>(null) }
    var inspectToken by remember { mutableStateOf(0L) }
    val graphPlotBounds = remember { mutableStateMapOf<String, Rect>() }
    val graphChipBounds = remember { mutableStateMapOf<String, Rect>() }
    var pendingDeleteGraphId by remember { mutableStateOf<String?>(null) }
    var startHandleRect by remember { mutableStateOf(Rect.Zero) }
    var endHandleRect by remember { mutableStateOf(Rect.Zero) }
    val canvasBg = if (dark) WinCanvas else WinLightCanvas
    val muted = if (dark) WinTextMuted else WinLightTextMuted
    // Deep graphite #181A1F ink for formulas in light theme (calm, high legibility, zero #000000).
    val formulaInk = if (dark) WinTextSecondary else WinLightTextPrimary
    val currentBottomInset by rememberUpdatedState(bottomInsetDp)

    // A newly spawned cell opens the keyboard, so always reserve its height when
    // deciding where "the middle of the screen" is.
    val density = LocalDensity.current
    LaunchedEffect(density, topInsetDp, bottomInsetDp) {
        state.topReservePx = with(density) { topInsetDp.dp.toPx() }
        state.bottomReservePx = with(density) { bottomInsetDp.dp.toPx() }
    }

    // Drop hit-test rectangles of cells that no longer exist so a removed empty
    // line cannot swallow the next tap. Keyed on size to stay idle while dragging.
    LaunchedEffect(state.cells.size) {
        val live = state.cells.mapTo(HashSet()) { it.id }
        cellBounds.keys.retainAll(live)
        slotBounds.keys.retainAll { it.cellId in live }
        graphPlotBounds.keys.retainAll(live)
        graphChipBounds.keys.retainAll(live)
        if (pendingDeleteGraphId !in live) pendingDeleteGraphId = null
    }

    val accentSnap = snapTintOf(accent)

    fun reportSlot(ref: SlotRef, empty: Boolean, coords: LayoutCoordinates) {
        CrashGuard.run {
            val root = rootCoords ?: return@run
            val rect = Rect(
                root.localPositionOf(coords, Offset.Zero),
                root.localPositionOf(
                    coords,
                    Offset(coords.size.width.toFloat(), coords.size.height.toFloat()),
                ),
            )
            if (!rect.left.isFinite() || !rect.top.isFinite() ||
                !rect.width.isFinite() || !rect.height.isFinite()
            ) return@run
            val known = slotBounds[ref]
            if (known == null || known.rect != rect || known.empty != empty) {
                slotBounds[ref] = SlotInfo(rect, empty)
            }
        }
    }

    fun reportHandle(start: Boolean, coords: LayoutCoordinates) {
        CrashGuard.run {
            val root = rootCoords ?: return@run
            val topLeft = root.localPositionOf(coords, Offset.Zero)
            val bottomRight = root.localPositionOf(
                coords,
                Offset(coords.size.width.toFloat(), coords.size.height.toFloat()),
            )
            val rect = Rect(topLeft, bottomRight)
            if (!rect.left.isFinite() || !rect.top.isFinite()) return@run
            if (start) startHandleRect = rect else endHandleRect = rect
        }
    }

    fun reportGraphPlot(id: String, coords: LayoutCoordinates) {
        CrashGuard.run {
            val root = rootCoords ?: return@run
            val rect = Rect(
                root.localPositionOf(coords, Offset.Zero),
                root.localPositionOf(
                    coords,
                    Offset(coords.size.width.toFloat(), coords.size.height.toFloat()),
                ),
            )
            if (!rect.left.isFinite() || !rect.top.isFinite()) return@run
            graphPlotBounds[id] = rect
        }
    }

    fun reportGraphChips(id: String, coords: LayoutCoordinates?) {
        if (coords == null) {
            graphChipBounds.remove(id)
            return
        }
        CrashGuard.run {
            val root = rootCoords ?: return@run
            val rect = Rect(
                root.localPositionOf(coords, Offset.Zero),
                root.localPositionOf(
                    coords,
                    Offset(coords.size.width.toFloat(), coords.size.height.toFloat()),
                ),
            )
            if (!rect.left.isFinite() || !rect.top.isFinite()) return@run
            graphChipBounds[id] = rect
        }
    }

    /** Deepest (smallest) slot of [cellId] under [point]; stale paths are dropped. */
    fun slotAt(cellId: String, point: Offset): SlotRef? = slotBounds.entries
        .filter { (ref, info) ->
            ref.cellId == cellId && info.rect.contains(point) && state.slotText(ref) != null
        }
        .minByOrNull { it.value.rect.width * it.value.rect.height }
        ?.key

    val menuCell = state.cells.firstOrNull { it.id == state.menuCellId && it.kind == CellKind.Formula }
    val systemMenuCell = state.cells.firstOrNull { it.systemMenuOpen && it.kind == CellKind.System }
    val selectionCell = state.cells.firstOrNull { it.id == state.selectionCellId }
    val anchorCell = selectionCell ?: menuCell ?: systemMenuCell

    LaunchedEffect(state.selection) {
        if (state.selection == null) {
            startHandleRect = Rect.Zero
            endHandleRect = Rect.Zero
        }
    }

    // Without this reset a stale rectangle would keep swallowing canvas taps.
    LaunchedEffect(anchorCell?.id) {
        if (anchorCell == null) {
            menuRect = Rect.Zero
            menuSize = IntSize.Zero
        }
    }

    Box(
        modifier = rootModifier
            .fillMaxSize()
            .background(canvasBg)
            .onGloballyPositioned {
                rootCoords = it
                rootSize = it.size
                state.viewport = it.size.toSize()
            }
            // Keyed on Unit on purpose: re-keying on pan/cells would restart the
            // detector mid-gesture and tear the drag when the finger is still down.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val bottomLimit = size.height - currentBottomInset.dp.toPx()
                    if (down.position.y > bottomLimit) return@awaitEachGesture
                    if (menuRect.contains(down.position)) return@awaitEachGesture

                    val handleHit = if (state.selection != null) {
                        when {
                            startHandleRect.inflate(16f).contains(down.position) -> 0
                            endHandleRect.inflate(16f).contains(down.position) -> 1
                            else -> -1
                        }
                    } else {
                        -1
                    }
                    if (handleHit >= 0) {
                        var acc = 0f
                        val charPx = (14f * state.zoom).coerceAtLeast(8f)
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            acc += pressed.first().positionChange().x
                            val steps = (acc / charPx).toInt()
                            if (steps != 0) {
                                acc -= steps * charPx
                                state.moveSelectionEdge(startHandle = handleHit == 0, deltaChars = steps)
                            }
                            event.changes.forEach { it.consume() }
                        }
                        return@awaitEachGesture
                    }

                    // Topmost cell wins; iterating live cells ignores stale bounds.
                    val hitCell = state.cells.lastOrNull { cell ->
                        cellBounds[cell.id]?.contains(down.position) == true
                    }
                    val hitGraphPlot = hitCell?.kind == CellKind.Graph &&
                        graphPlotBounds[hitCell.id]?.contains(down.position) == true

                    var dragged = false
                    var longPressed = false
                    var extendingSelection = false
                    var pinching = false
                    var pinchCellId: String? = null
                    var pending = Offset.Zero
                    val slop = viewConfiguration.touchSlop
                    var longJob: Job? = null

                    // The bottom-left grab point is the probe: whichever empty slot
                    // it sits inside becomes the insertion target, and leaving that
                    // slot disarms it again.
                    fun refreshSnap(cellId: String) {
                        val corner = state.grabCorner(cellId)?.let { state.worldToScreen(it) }
                        val armed = corner?.let {
                            slotBounds.entries
                                .filter { (ref, info) ->
                                    info.empty && ref.cellId != cellId && info.rect.contains(it)
                                }
                                .minByOrNull { entry -> entry.value.rect.width * entry.value.rect.height }
                                ?.key
                        }
                        state.armDropTarget(armed?.takeIf { state.canDropInto(cellId, it) })
                    }

                    fun refreshGraphDrop(draggedId: String, finger: Offset) {
                        val src = cellBounds[draggedId]
                        val graphId = state.cells.lastOrNull { cell ->
                            if (cell.kind != CellKind.Graph || cell.id == draggedId) return@lastOrNull false
                            if (!state.canLinkToGraph(draggedId, cell.id)) return@lastOrNull false
                            val rect = cellBounds[cell.id] ?: return@lastOrNull false
                            rect.contains(finger) || (src != null && rect.overlaps(src))
                        }?.id
                        state.armGraphDrop(graphId)
                    }

                    fun applyDrag(delta: Offset, finger: Offset) {
                        if (hitCell == null) {
                            state.panBy(delta)
                            return
                        }
                        if (hitGraphPlot) {
                            val plot = graphPlotBounds[hitCell.id] ?: return
                            val live = state.cells.firstOrNull { it.id == hitCell.id }?.graph?.viewport
                                ?: GraphViewport()
                            if (plot.width > 1f && plot.height > 1f) {
                                state.panGraph(
                                    hitCell.id,
                                    -delta.x / plot.width * live.xSpan,
                                    delta.y / plot.height * live.ySpan,
                                )
                            }
                            return
                        }
                        state.moveCellBy(hitCell.id, delta / state.zoom)
                        if (hitCell.kind == CellKind.Formula) refreshSnap(hitCell.id)
                        if (hitCell.kind != CellKind.Graph) refreshGraphDrop(hitCell.id, finger)
                    }

                    fun abortIdleActions() {
                        longJob?.cancel()
                        state.dismissMenu()
                        state.dismissAllSystemMenus()
                        pendingDeleteGraphId = null
                    }

                    if (hitCell != null && hitCell.kind == CellKind.Graph) {
                        longJob = scope.launch {
                            delay(420)
                            if (!dragged) {
                                longPressed = true
                                val onChips = graphChipBounds[hitCell.id]?.contains(down.position) == true
                                if (!onChips) pendingDeleteGraphId = hitCell.id
                            }
                        }
                    } else if (hitCell != null) {
                        longJob = scope.launch {
                            delay(420)
                            if (!dragged) {
                                longPressed = true
                                // Targeting the pressed slot is what makes copy /
                                // paste work per row instead of per whole block.
                                val slot = slotAt(hitCell.id, down.position)
                                if (slot != null && state.deleteTemplateAtEmptySlot(slot)) {
                                    return@launch
                                }
                                if (hitCell.kind == CellKind.System) {
                                    if (state.beginSelectionAt(hitCell.id, slot)) {
                                        return@launch
                                    }
                                    state.showSystemMenu(hitCell.id, slot)
                                } else {
                                    state.showMenu(hitCell.id, slot)
                                }
                            }
                        }
                    }

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break

                        if (pressed.size >= 2) {
                            if (!pinching) {
                                pinching = true
                                dragged = true
                                abortIdleActions()
                                state.cancelDrag()
                                // Both fingers inside one formula → scale that formula.
                                // Anything else (a finger on empty space) → scale the canvas.
                                pinchCellId = hitCell?.id?.takeIf { id ->
                                    pressed.all { cellBounds[id]?.contains(it.position) == true }
                                }
                            }
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val target = pinchCellId
                            if (target != null) {
                                val targetCell = state.cells.firstOrNull { it.id == target }
                                if (targetCell?.kind == CellKind.Graph) {
                                    val plot = graphPlotBounds[target]
                                    val vp = targetCell.graph?.viewport ?: GraphViewport()
                                    if (plot != null && plot.width > 1f && plot.height > 1f) {
                                        if (zoomChange != 1f) {
                                            val local = event.calculateCentroid(useCurrent = false) - plot.topLeft
                                            state.zoomGraph(
                                                target,
                                                zoomChange,
                                                vp.xOf(local.x, plot.width),
                                                vp.yOf(local.y, plot.height),
                                            )
                                        }
                                        if (panChange != Offset.Zero) {
                                            state.panGraph(
                                                target,
                                                -panChange.x / plot.width * vp.xSpan,
                                                panChange.y / plot.height * vp.ySpan,
                                            )
                                        }
                                    }
                                } else {
                                    if (zoomChange != 1f) state.scaleCellBy(target, zoomChange)
                                    if (panChange != Offset.Zero) {
                                        state.moveCellBy(target, panChange / state.zoom)
                                    }
                                }
                            } else {
                                // Previous centroid keeps the anchored world point
                                // exact once the pan delta is applied on top.
                                state.zoomAround(
                                    pivot = event.calculateCentroid(useCurrent = false),
                                    factor = zoomChange,
                                    panDelta = panChange,
                                )
                            }
                        } else if (!pinching) {
                            val delta = pressed.first().positionChange()
                            if (!dragged) {
                                pending += delta
                                if (pending.getDistance() > slop) {
                                    dragged = true
                                    abortIdleActions()
                                    if (longPressed && state.selection != null) {
                                        extendingSelection = true
                                    } else if (hitCell != null) {
                                        if (!hitGraphPlot) state.beginDrag(hitCell.id)
                                        applyDrag(pending, pressed.first().position)
                                    } else {
                                        applyDrag(pending, pressed.first().position)
                                    }
                                }
                            } else if (extendingSelection) {
                                val charPx = (14f * state.zoom).coerceAtLeast(8f)
                                val steps = (delta.x / charPx).toInt()
                                if (steps != 0) {
                                    state.moveSelectionEdge(startHandle = false, deltaChars = steps)
                                }
                            } else {
                                applyDrag(delta, pressed.first().position)
                            }
                        }
                        event.changes.forEach { it.consume() }
                    }
                    longJob?.cancel()
                    // Releasing over an armed slot inserts; releasing anywhere else
                    // simply leaves the formula where the finger let go.
                    state.endDrag()

                    if (!dragged && !longPressed) {
                        val pick = state.graphPickId
                        if (pick != null) {
                            if (hitCell != null && hitCell.kind != CellKind.Graph) {
                                state.addGraphSource(pick, hitCell.id)
                            } else {
                                state.cancelGraphPick()
                            }
                        } else if (hitCell != null) {
                            state.focus(hitCell.id)
                        } else {
                            state.createCellAtScreen(down.position)
                        }
                    }
                }
            },
    ) {
        SubtleGridBackground(
            alpha = if (dark) 0.05f else 0.90f,
            dark = dark,
            pan = state.pan,
            zoom = state.zoom,
        )

        if (state.cells.isEmpty()) {
            Text(
                text = stringResource(R.string.type_placeholder_title),
                style = MaterialTheme.typography.titleMedium,
                color = muted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
            )
        }

        Layout(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val scale = CrashGuard.finite(state.zoom, 1f).coerceAtLeast(0.01f)
                    scaleX = scale
                    scaleY = scale
                    translationX = CrashGuard.finite(state.pan.x)
                    translationY = CrashGuard.finite(state.pan.y)
                    transformOrigin = TransformOrigin(0f, 0f)
                },
            content = {
                state.cells.forEach { cell ->
                    key(cell.id) {
                        CanvasCellCard(
                            cell = cell,
                            state = state,
                            dark = dark,
                            accent = accent,
                            accentSnap = accentSnap,
                            secondary = formulaInk,
                            rootCoords = rootCoords,
                            cellBounds = cellBounds,
                            reportSlot = ::reportSlot,
                            reportHandle = ::reportHandle,
                            reportGraphPlot = { coords -> reportGraphPlot(cell.id, coords) },
                            reportGraphChips = { coords -> reportGraphChips(cell.id, coords) },
                            onInspect = { source ->
                                inspecting = source
                                inspectToken += 1
                            },
                        )
                    }
                }
            },
        ) { measurables, constraints ->
            // Unbounded measure is what lets the focus border match the full
            // formula: a fillMaxSize parent would otherwise cap every card at
            // the viewport width and leave the tail outside the outline.
            val unbounded = Constraints()
            val placeables = measurables.map { child -> child.measure(unbounded) }
            val placed = state.cells
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeables.forEachIndexed { index, placeable ->
                    val cell = placed.getOrNull(index) ?: return@forEachIndexed
                    placeable.place(
                        x = CrashGuard.roundPx(cell.position.x),
                        y = CrashGuard.roundPx(cell.position.y),
                    )
                }
            }
        }

        // Context menus live at the root so they can be clamped to the screen
        // instead of being dragged off-view by their anchor cell.
        if (anchorCell != null) {
            val anchor = cellBounds[anchorCell.id] ?: Rect.Zero
            Box(
                modifier = Modifier
                    .offset {
                        clampedMenuOffset(
                            anchor = anchor,
                            menu = menuSize,
                            root = rootSize,
                            gapPx = 10.dp.toPx(),
                            marginPx = 8.dp.toPx(),
                            topInsetPx = topInsetDp.dp.toPx(),
                            bottomInsetPx = currentBottomInset.dp.toPx(),
                        )
                    }
                    .graphicsLayer { alpha = if (menuSize.width > 0) 1f else 0f }
                    .onGloballyPositioned { coords ->
                        CrashGuard.run {
                            menuSize = coords.size
                            val root = rootCoords ?: return@run
                            val origin = root.localPositionOf(coords, Offset.Zero)
                            if (!origin.x.isFinite() || !origin.y.isFinite()) return@run
                            menuRect = Rect(origin, coords.size.toSize())
                        }
                    },
            ) {
                // Slot actions appear only when the long press landed inside a
                // child slot, so copy / paste can address one row of a system or
                // one half of a fraction.
                val slot = state.menuSlot
                val copySlot: (() -> Unit)? = slot?.let { ref ->
                    {
                        state.slotLatex(ref)?.let { copyToClipboard(context, it) }
                        state.dismissMenu()
                        state.dismissAllSystemMenus()
                    }
                }
                val pasteSlot: (() -> Unit)? = slot?.let { ref ->
                    {
                        pasteFromClipboard(context)?.let { state.pasteIntoSlot(ref, it) }
                        state.dismissAllSystemMenus()
                    }
                }

                if (state.selection != null && selectionCell != null) {
                    SelectionActionBar(
                        onCopy = {
                            state.selectionText()?.let { copyToClipboard(context, it) }
                        },
                        onPaste = {
                            pasteFromClipboard(context)?.let { state.replaceSelection(it) }
                        },
                        onCut = {
                            state.selectionText()?.let { copyToClipboard(context, it) }
                            state.deleteSelection()
                        },
                    )
                } else if (menuCell != null) {
                    FormulaContextMenu(
                        onCopyLatex = {
                            copyToClipboard(context, state.latexOf(menuCell.id))
                            state.dismissMenu()
                        },
                        onImportLatex = {
                            latexTargetId = menuCell.id
                            state.dismissMenu()
                        },
                        onPasteText = {
                            pasteFromClipboard(context)?.let {
                                state.replaceText(menuCell.id, it, asLatex = true)
                            }
                        },
                        onDelete = { state.deleteCell(menuCell.id) },
                        onSelect = { state.toggleSelect(menuCell.id) },
                        onPlot = {
                            state.createGraphFrom(menuCell.id)
                        },
                        onCopySlot = copySlot,
                        onPasteSlot = pasteSlot,
                    )
                } else if (systemMenuCell != null) {
                    SystemContextMenu(
                        onDelete = { state.deleteCell(systemMenuCell.id) },
                        onCopy = {
                            copyToClipboard(context, systemMenuCell.toPlainText())
                            state.dismissAllSystemMenus()
                        },
                        onCopyLatex = {
                            copyToClipboard(context, systemMenuCell.toLatexCases())
                            state.dismissAllSystemMenus()
                        },
                        onCopyText = {
                            copyToClipboard(context, systemMenuCell.toPlainText())
                            state.dismissAllSystemMenus()
                        },
                        onUngroup = { state.ungroupSystem(systemMenuCell.id) },
                        onAddEquation = { state.addEquationToSystem(systemMenuCell.id) },
                        onPlot = {
                            state.createGraphFrom(systemMenuCell.id)
                        },
                        onCopySlot = copySlot,
                        onPasteSlot = pasteSlot,
                    )
                }
            }
        }
    }

    latexTargetId?.let { targetId ->
        LatexImportDialog(
            onDismiss = { latexTargetId = null },
            onImport = { latex ->
                state.replaceText(targetId, latex, asLatex = true)
                latexTargetId = null
            },
        )
    }

    inspecting?.let { source ->
        key(inspectToken) {
            LongNumberDialog(
                source = source,
                onDismiss = { inspecting = null },
            )
        }
    }

    pendingDeleteGraphId?.let { graphId ->
        AlertDialog(
            onDismissRequest = { pendingDeleteGraphId = null },
            title = { Text(stringResource(R.string.graph_delete_title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.deleteCell(graphId)
                        pendingDeleteGraphId = null
                    },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteGraphId = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** Keeps a popup fully on screen: flip below the anchor, then clamp to the edges. */
private fun clampedMenuOffset(
    anchor: Rect,
    menu: IntSize,
    root: IntSize,
    gapPx: Float,
    marginPx: Float,
    topInsetPx: Float,
    bottomInsetPx: Float,
): IntOffset {
    if (menu.width == 0 || root.width == 0) return IntOffset.Zero
    val minX = marginPx
    val maxX = (root.width - menu.width - marginPx).coerceAtLeast(minX)
    val minY = topInsetPx + marginPx
    val maxY = (root.height - bottomInsetPx - menu.height - marginPx).coerceAtLeast(minY)

    val x = CrashGuard.finite(anchor.center.x - menu.width / 2f).let { raw ->
        if (minX.isFinite() && maxX.isFinite() && minX <= maxX) raw.coerceIn(minX, maxX) else raw
    }
    val above = CrashGuard.finite(anchor.top - menu.height - gapPx)
    val below = CrashGuard.finite(anchor.bottom + gapPx)
    val y = if (above >= minY) above else if (below <= maxY) below else {
        if (minY.isFinite() && maxY.isFinite() && minY <= maxY) above.coerceIn(minY, maxY) else above
    }
    val clampedY = if (minY.isFinite() && maxY.isFinite() && minY <= maxY) {
        CrashGuard.finite(y).coerceIn(minY, maxY)
    } else {
        CrashGuard.finite(y)
    }
    return IntOffset(CrashGuard.roundPx(x), CrashGuard.roundPx(clampedY))
}

@Composable
private fun CanvasCellCard(
    cell: InputCell,
    state: TypeCanvasState,
    dark: Boolean,
    accent: Color,
    accentSnap: Color,
    secondary: Color,
    rootCoords: LayoutCoordinates?,
    cellBounds: MutableMap<String, Rect>,
    reportSlot: (SlotRef, Boolean, LayoutCoordinates) -> Unit,
    reportHandle: (Boolean, LayoutCoordinates) -> Unit,
    reportGraphPlot: (LayoutCoordinates) -> Unit,
    reportGraphChips: (LayoutCoordinates?) -> Unit,
    onInspect: (String) -> Unit,
) {
    val focused = cell.id == state.focusedId
    val menuOpen = cell.id == state.menuCellId
    val dragging = cell.id == state.draggingCellId
    val armed = dragging && state.dropTarget != null
    val graphHot = cell.kind == CellKind.Graph && state.graphDropId == cell.id
    val linked = cell.kind == CellKind.Graph || cell.id in state.graphLinkedIds()
    val pickingCandidate = state.graphPickId != null && cell.kind != CellKind.Graph
    val borderWidth = when {
        armed || graphHot -> 2.5.dp
        linked || pickingCandidate -> 2.dp
        cell.systemMenuOpen || menuOpen -> 2.5.dp
        cell.selected -> 2.dp
        focused -> 1.5.dp
        else -> 0.dp
    }
    val graphLinkColor = if (dark) GraphLinkYellow else WinLightGraphAccent
    val dangerColor = if (dark) WinDanger else WinLightDanger
    val borderColor = when {
        armed -> accentSnap
        graphHot || linked || pickingCandidate -> graphLinkColor
        cell.selected -> accent.copy(alpha = 0.95f)
        else -> accent.copy(alpha = 0.85f)
    }
    val glassTintColor = when {
        armed -> accentSnap
        graphHot || linked || pickingCandidate -> graphLinkColor
        else -> accent
    }
    val glassTintAlpha = when {
        armed -> 0.22f
        graphHot -> 0.18f
        linked || pickingCandidate -> 0.07f
        focused || menuOpen || cell.selected || cell.systemMenuOpen -> if (dark) 0.10f else 0.06f
        else -> 0f
    }

    Box(
        modifier = Modifier
            .graphicsLayer {
                val scale = CrashGuard.finite(cell.scale, 1f)
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .wrapContentWidth(align = Alignment.Start, unbounded = true)
            .wrapContentHeight(unbounded = true)
            .widthIn(min = 44.dp)
            .onGloballyPositioned { coords ->
                CrashGuard.run {
                    state.reportCellSize(cell.id, coords.size.toSize())
                    val root = rootCoords ?: return@run
                    val topLeft = root.localPositionOf(coords, Offset.Zero)
                    val bottomRight = root.localPositionOf(
                        coords,
                        Offset(coords.size.width.toFloat(), coords.size.height.toFloat()),
                    )
                    if (!topLeft.x.isFinite() || !topLeft.y.isFinite() ||
                        !bottomRight.x.isFinite() || !bottomRight.y.isFinite()
                    ) return@run
                    cellBounds[cell.id] = Rect(topLeft, bottomRight)
                }
            },
    ) {
        CardLiquidGlassBackdrop(
            dark = dark,
            accent = accent,
            tintColor = glassTintColor,
            tintAlpha = glassTintAlpha,
            borderColor = borderColor,
            borderWidth = borderWidth,
            cornerRadius = 14.dp,
            modifier = Modifier.matchParentSize(),
        )

        Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            val drop = state.dropTarget
            when (cell.kind) {
                CellKind.Formula -> FormulaCellBody(
                    cell = cell,
                    focused = focused,
                    secondary = secondary,
                    danger = dangerColor,
                    accent = accent,
                    state = state,
                    highlightPath = drop
                        ?.takeIf { it.cellId == cell.id && it.equationIndex < 0 }
                        ?.path,
                    onSlotBounds = { path, empty, coords ->
                        reportSlot(SlotRef(cell.id, SlotRef.NO_EQUATION, path), empty, coords)
                    },
                    onInspect = onInspect,
                    onHandleBounds = reportHandle,
                )
                CellKind.System -> SystemCellBody(
                    cell = cell,
                    focused = focused,
                    secondary = secondary,
                    danger = dangerColor,
                    accent = accent,
                    snapTint = accentSnap,
                    state = state,
                    dropTarget = drop?.takeIf { it.cellId == cell.id },
                    onSlotBounds = { index, path, empty, coords ->
                        reportSlot(SlotRef(cell.id, index, path), empty, coords)
                    },
                    onInspect = onInspect,
                    onHandleBounds = reportHandle,
                )
                CellKind.Graph -> {
                    val tracePalette = if (dark) GraphSeriesPalette else GraphSeriesPaletteLight
                    GraphBoard(
                        traces = state.tracesForGraph(cell).mapIndexed { index, (id, label) ->
                            val src = state.cells.firstOrNull { it.id == id }
                            GraphTrace(
                                cellId = id,
                                label = label,
                                color = tracePalette[index % tracePalette.size],
                                documents = when (src?.kind) {
                                    CellKind.Formula -> listOf(src.document)
                                    CellKind.System -> src.equations
                                    else -> emptyList()
                                },
                            )
                        },
                        viewport = cell.graph?.viewport ?: GraphViewport(),
                        onPlotBounds = reportGraphPlot,
                        onChipRowBounds = reportGraphChips,
                        onRemoveTrace = { sourceId -> state.removeGraphSource(cell.id, sourceId) },
                    )
                }
            }
        }

        if (dragging) {
            CornerGrabDot(
                color = if (armed) accentSnap else accent,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }
}

/**
 * Grab point shown while dragging: the probe used for slot snapping. Placed
 * directly in the unpadded card box so half its 14dp size centres it on the
 * exact bottom-left corner that the hit test uses.
 */
@Composable
private fun CornerGrabDot(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .offset(x = (-7).dp, y = 7.dp)
            .size(14.dp)
            .background(Color.White.copy(alpha = 0.9f), CircleShape)
            .padding(2.5.dp)
            .background(color, CircleShape),
    )
}

@Composable
private fun FormulaCellBody(
    cell: InputCell,
    focused: Boolean,
    secondary: Color,
    danger: Color,
    accent: Color,
    state: TypeCanvasState,
    highlightPath: List<Int>?,
    onSlotBounds: (List<Int>, Boolean, LayoutCoordinates) -> Unit,
    onInspect: (String) -> Unit,
    onHandleBounds: (Boolean, LayoutCoordinates) -> Unit,
) {
    Column(
        modifier = Modifier
            .wrapContentWidth(align = Alignment.Start, unbounded = true)
            .wrapContentHeight(),
    ) {
        StructuredMathView(
            document = cell.document,
            focused = focused,
            textColor = if (cell.errorMessage != null) danger else secondary,
            onHoleClick = { holeId ->
                state.focus(cell.id)
                state.focusHole(holeId)
            },
            fontSize = 22.sp,
            highlightPath = highlightPath,
            onSlotBounds = onSlotBounds,
            onCaretTap = { path, offset ->
                state.focus(cell.id)
                state.placeCaret(path, offset)
            },
            captureCaretTaps = true,
            selection = state.selection.takeIf { state.selectionCellId == cell.id },
            onHandleBounds = onHandleBounds,
        )
        if (cell.id in state.solvingIds) SolvingHint(accent)
        cell.resultText?.let { result ->
            ResultRow(cell = cell, result = result, accent = accent, onInspect = onInspect) {
                state.toggleExactDecimal(cell.id)
            }
        }
        cell.errorMessage?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.labelMedium,
                color = danger,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun SystemCellBody(
    cell: InputCell,
    focused: Boolean,
    secondary: Color,
    danger: Color,
    accent: Color,
    snapTint: Color,
    state: TypeCanvasState,
    dropTarget: SlotRef?,
    onSlotBounds: (equationIndex: Int, path: List<Int>, empty: Boolean, coords: LayoutCoordinates) -> Unit,
    onInspect: (String) -> Unit,
    onHandleBounds: (Boolean, LayoutCoordinates) -> Unit,
) {
    // IntrinsicSize.Min lets the brace track the real content height instead of
    // a fixed 36dp per row, so a fresh system starts compact.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .wrapContentWidth(align = Alignment.Start, unbounded = true)
            .height(IntrinsicSize.Min),
    ) {
        // Curly brace hit target
        Box(
            modifier = Modifier
                .width(20.dp)
                .fillMaxHeight()
                .clickable { state.showSystemMenu(cell.id) },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "{",
                color = accent,
                fontSize = (28 + cell.equations.size * 4).coerceAtMost(48).sp,
                fontWeight = FontWeight.Light,
                fontFamily = FontFamily.Serif,
            )
        }
        Column(modifier = Modifier.padding(start = 4.dp)) {
            cell.equations.forEachIndexed { index, doc ->
                val eqFocused = focused && cell.activeEquationIndex == index
                val rowDrop = dropTarget?.takeIf { it.equationIndex == index }
                val rowArmed = rowDrop?.path?.isEmpty() == true
                Box(
                    modifier = Modifier
                        .padding(vertical = 1.dp)
                        .then(
                            when {
                                rowArmed -> Modifier.border(1.5.dp, snapTint, RoundedCornerShape(4.dp))
                                eqFocused -> Modifier.border(
                                    1.dp,
                                    accent.copy(alpha = 0.45f),
                                    RoundedCornerShape(4.dp),
                                )
                                else -> Modifier
                            },
                        )
                        .clickable { state.setActiveEquation(cell.id, index) }
                        .onGloballyPositioned { onSlotBounds(index, emptyList(), doc.isBlank(), it) }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    StructuredMathView(
                        document = doc,
                        focused = eqFocused,
                        textColor = if (cell.errorMessage != null) danger else secondary,
                        onHoleClick = { holeId ->
                            state.setActiveEquation(cell.id, index)
                            state.focusHole(holeId)
                        },
                        fontSize = 20.sp,
                        highlightPath = rowDrop?.path?.takeIf { it.isNotEmpty() },
                        onSlotBounds = { path, empty, coords -> onSlotBounds(index, path, empty, coords) },
                        onCaretTap = { path, offset ->
                            state.setActiveEquation(cell.id, index)
                            state.placeCaret(path, offset)
                        },
                        captureCaretTaps = true,
                        selection = state.selection.takeIf {
                            state.selectionCellId == cell.id && cell.activeEquationIndex == index
                        },
                        onHandleBounds = onHandleBounds,
                    )
                }
            }
            if (cell.id in state.solvingIds) SolvingHint(accent)
            cell.resultText?.let { result ->
                ResultRow(cell = cell, result = result, accent = accent, onInspect = onInspect) {
                    state.toggleExactDecimal(cell.id)
                }
            }
            cell.errorMessage?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.labelMedium,
                    color = danger,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SolvingHint(accent: Color) {
    Row(
        modifier = Modifier.padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            color = accent,
            strokeWidth = 2.dp,
        )
        Text(
            text = stringResource(R.string.solving),
            style = MaterialTheme.typography.labelMedium,
            color = accent.copy(alpha = 0.85f),
        )
    }
}

@Composable
private fun ResultRow(
    cell: InputCell,
    result: String,
    accent: Color,
    onInspect: (String) -> Unit,
    onToggle: () -> Unit,
) {
    val layout = LocalAnswerLayout.current
    val places = LocalDecimalPlaces.current
    val mixed = layout == AnswerLayout.Column && !cell.showDecimal
    val display = if (cell.showDecimal) DecimalDisplay.applyPlaces(result, places) else result
    val toggleDescription = stringResource(
        if (cell.showDecimal) R.string.cd_show_exact else R.string.cd_show_decimal,
    )
    // No weight(1f) here: inside the unbounded canvas a weighted child is
    // measured with maxWidth 0 and the answer overflows under the toggle.
    Row(
        modifier = Modifier
            .padding(top = 6.dp)
            .wrapContentWidth(align = Alignment.Start, unbounded = true)
            .wrapContentHeight(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnswerView(
            result = display,
            layout = layout,
            mixed = mixed,
            color = accent,
            fontSize = 18.sp,
            exact = cell.resultExact,
            onInspect = onInspect,
            modifier = Modifier
                .wrapContentWidth(align = Alignment.Start, unbounded = true)
                .wrapContentHeight(),
        )
        if (cell.canToggleExactDecimal) {
            Box(
                modifier = Modifier
                    .height(32.dp)
                    .widthIn(min = 40.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(accent.copy(alpha = 0.12f))
                    .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                    .clickable(onClick = onToggle)
                    .semantics { contentDescription = toggleDescription }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (cell.showDecimal) "=" else "≈",
                    color = accent,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/** Infinite grid: the pattern follows pan and zoom without being clipped by them. */
@Composable
private fun SubtleGridBackground(
    alpha: Float = 0.05f,
    dark: Boolean = true,
    pan: Offset = Offset.Zero,
    zoom: Float = 1f,
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val step = (28.dp.toPx() * zoom).coerceAtLeast(8f)
        val color = (if (dark) Color.White else WinLightGridLine).copy(alpha = alpha)
        val effect = PathEffect.dashPathEffect(floatArrayOf(2f, 10f), 0f)
        var x = pan.x.mod(step) - step
        while (x < size.width) {
            drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f, pathEffect = effect)
            x += step
        }
        var y = pan.y.mod(step) - step
        while (y < size.height) {
            drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f, pathEffect = effect)
            y += step
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("mathcad", text))
}

private fun pasteFromClipboard(context: Context): String? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = cm.primaryClip ?: return null
    if (clip.itemCount <= 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}
