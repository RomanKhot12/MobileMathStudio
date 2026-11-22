package com.example.mobilemathstudio.ui.export

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mobilemathstudio.CrashGuard
import com.example.mobilemathstudio.R
import com.example.mobilemathstudio.export.BlockPart
import com.example.mobilemathstudio.export.CanvasExportLayout
import com.example.mobilemathstudio.export.ExportEditor
import com.example.mobilemathstudio.export.ExportFormat
import com.example.mobilemathstudio.export.ExportHistory
import com.example.mobilemathstudio.export.ExportLayout
import com.example.mobilemathstudio.export.ExportWriter
import com.example.mobilemathstudio.export.PageItem
import com.example.mobilemathstudio.export.expandGraphSeries
import com.example.mobilemathstudio.export.resolveExportGraphTraces
import com.example.mobilemathstudio.math.graph.GraphViewport
import com.example.mobilemathstudio.settings.AnswerLayout
import com.example.mobilemathstudio.ui.canvas.CellKind
import com.example.mobilemathstudio.ui.canvas.InputCell
import com.example.mobilemathstudio.ui.graph.GraphLinkYellow
import com.example.mobilemathstudio.ui.graph.GraphPlotCanvas
import com.example.mobilemathstudio.ui.graph.GraphTrace
import com.example.mobilemathstudio.ui.math.AnswerView
import com.example.mobilemathstudio.ui.math.StructuredMathView
import com.example.mobilemathstudio.ui.theme.LocalAccentColor
import com.example.mobilemathstudio.ui.theme.LocalAnswerLayout
import kotlinx.coroutines.launch

private val PAGE_INK = Color(0xFF14141A)
private val PAGE_RESULT_INK = Color(0xFF0B5CAB)

/** What the delete button will remove: a whole block, or one piece of it. */
private data class ExportSelection(val cellId: String, val part: BlockPart? = null)

/**
 * Interactive page composer: auto-fits the canvas, then lets the user drag
 * formulas between pages, pinch them, strip single rows out of a block, manage
 * pages and undo any of it before saving.
 */
@Composable
fun ExportScreen(
    cells: List<InputCell>,
    sizeOf: (String) -> Size,
    canvasName: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val accent = LocalAccentColor.current
    val scope = rememberCoroutineScope()
    val cellsById = remember(cells) { cells.associateBy { it.id } }

    var format by remember { mutableStateOf(ExportFormat.Pdf) }
    var selection by remember { mutableStateOf<ExportSelection?>(null) }
    var pageSelectMode by remember { mutableStateOf(false) }
    var checkedPages by remember { mutableStateOf(emptySet<Int>()) }
    var busy by remember { mutableStateOf(false) }

    val history = remember {
        ExportHistory(CanvasExportLayout.auto(cells, sizeOf, allowMultiplePages = true))
    }
    val multiPage = format == ExportFormat.Pdf
    LaunchedEffect(cells, multiPage) {
        history.rebase(CanvasExportLayout.auto(cells, sizeOf, allowMultiplePages = multiPage))
        selection = null
        pageSelectMode = false
        checkedPages = emptySet()
    }

    val layout = history.state
    val pageLayers = remember { mutableMapOf<Int, GraphicsLayer>() }
    // Snapshot taken when a finger lands, so a whole drag costs one undo step.
    var gestureStart by remember { mutableStateOf<ExportLayout?>(null) }
    // Where each block sits on its page, so the action bar can follow the
    // selected one even when the selection jumps to a block that never moved.
    val itemBounds = remember { mutableStateMapOf<String, Rect>() }

    fun leavePageSelection() {
        pageSelectMode = false
        checkedPages = emptySet()
    }

    fun deleteSelection() {
        val target = selection ?: return
        val cell = cellsById[target.cellId]
        history.edit { current ->
            val part = target.part
            if (part != null && cell != null) {
                ExportEditor.deletePart(current, cell, part)
            } else {
                ExportEditor.deleteBlock(current, target.cellId)
            }
        }
        selection = null
    }

    BackHandler(enabled = !busy) {
        if (pageSelectMode) leavePageSelection() else onClose()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF16161A))
            // Swallow every touch: the canvas underneath must stay untouched.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        if (event.changes.none { it.pressed }) break
                    }
                }
            },
    ) {
        ExportTopBar(
            format = format,
            busy = busy,
            accent = accent,
            onFormatChange = { format = it },
            onClose = onClose,
            onExport = {
                if (busy) return@ExportTopBar
                busy = true
                selection = null
                leavePageSelection()
                scope.launch {
                    // Let the selection outlines disappear before capturing.
                    withFrameNanos { }
                    withFrameNanos { }
                    val bitmaps = (0 until layout.pageCount).mapNotNull { index ->
                        runCatching { pageLayers[index]?.toImageBitmap()?.asAndroidBitmap() }.getOrNull()
                    }
                    val saved = ExportWriter.write(context, bitmaps, format, canvasName)
                    busy = false
                    Toast.makeText(
                        context,
                        saved?.let { context.getString(R.string.export_saved, it) }
                            ?: context.getString(R.string.export_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
        )

        EditorToolbar(
            canUndo = history.canUndo,
            canRedo = history.canRedo,
            canReset = history.canReset,
            onUndo = {
                history.undo()
                selection = null
            },
            onRedo = {
                history.redo()
                selection = null
            },
            onReset = {
                history.reset()
                selection = null
            },
            accent = accent,
            pageSelectMode = pageSelectMode,
            checkedCount = checkedPages.size,
            onDeletePages = {
                history.edit { ExportEditor.deletePages(it, checkedPages) }
                selection = null
                leavePageSelection()
            },
            onKeepPages = {
                history.edit { ExportEditor.keepPages(it, checkedPages) }
                selection = null
                leavePageSelection()
            },
        )

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            val pageWidthDp = maxWidth
            val pageWidthPx = with(density) { pageWidthDp.toPx() }
            val render = pageWidthPx / CanvasExportLayout.PAGE_WIDTH

            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(layout.pageCount) { index ->
                    key(index) {
                        val graphicsLayer = rememberGraphicsLayer()
                        DisposableEffect(index, graphicsLayer) {
                            pageLayers[index] = graphicsLayer
                            onDispose { pageLayers.remove(index) }
                        }
                        val selectedHere = selection?.let { current ->
                            layout.items.any { it.cellId == current.cellId && it.page == index }
                        } == true
                        PageSurface(
                            index = index,
                            layer = graphicsLayer,
                            accent = accent,
                            checkable = pageSelectMode,
                            checked = index in checkedPages,
                            onCheckedChange = { on ->
                                checkedPages = if (on) checkedPages + index else checkedPages - index
                            },
                            onBackgroundTap = { selection = null },
                            overlay = {
                                if (selectedHere) {
                                    SelectionBar(
                                        anchor = itemBounds[selection?.cellId] ?: Rect.Zero,
                                        page = Size(pageWidthPx, pageWidthPx * 297f / 210f),
                                        wholeBlock = selection?.part == null,
                                        accent = accent,
                                        onDelete = { deleteSelection() },
                                        onDismiss = { selection = null },
                                    )
                                }
                            },
                            modifier = Modifier.width(pageWidthDp),
                        ) {
                            layout.itemsOn(index).forEach { item ->
                                val cell = cellsById[item.cellId] ?: return@forEach
                                key(item.cellId) {
                                    PageFormula(
                                        item = item,
                                        cell = cell,
                                        cellsById = cellsById,
                                        render = render,
                                        accent = accent,
                                        interactive = !pageSelectMode,
                                        selection = selection?.takeIf { it.cellId == item.cellId },
                                        onBounds = { bounds ->
                                            if (itemBounds[item.cellId] != bounds) {
                                                itemBounds[item.cellId] = bounds
                                            }
                                        },
                                        onTap = { part ->
                                            val alreadyOnBlock = selection?.cellId == item.cellId
                                            selection = ExportSelection(
                                                cellId = item.cellId,
                                                part = part.takeIf { alreadyOnBlock },
                                            )
                                        },
                                        onDragStart = { gestureStart = history.state },
                                        onDrag = { delta ->
                                            history.apply(
                                                ExportEditor.move(history.state, item.cellId, delta),
                                            )
                                        },
                                        onPinch = { factor ->
                                            history.apply(ExportEditor.scale(history.state, item.cellId, factor))
                                        },
                                        onDragEnd = {
                                            history.apply(ExportEditor.settle(history.state, item.cellId))
                                            gestureStart?.let { history.record(it) }
                                            gestureStart = null
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        ExportBottomBar(
            pageCount = layout.pageCount,
            multiPage = multiPage,
            pageSelectMode = pageSelectMode,
            hint = when {
                pageSelectMode -> R.string.export_pick_pages
                selection?.part == null && selection != null -> R.string.export_pick_part
                else -> null
            },
            onToggleSelectMode = {
                if (pageSelectMode) leavePageSelection() else pageSelectMode = true
                selection = null
            },
            onAddPage = { history.edit { current -> ExportEditor.addPage(current) } },
        )
    }
}

@Composable
private fun PageSurface(
    index: Int,
    layer: GraphicsLayer,
    accent: Color,
    checkable: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onBackgroundTap: () -> Unit,
    overlay: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier) {
        // Unbounded child measure lets graphs (300dp) and long formulas keep their
        // natural canvas dimensions before PageFormula's graphicsLayer scales them.
        Layout(
            content = content,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(210f / 297f)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White)
                .drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                }
                // Formulas consume their own taps, so only empty paper lands here.
                .pointerInput(Unit) { detectTapGestures { onBackgroundTap() } },
        ) { measurables, constraints ->
            val unbounded = Constraints()
            val placeables = measurables.map { it.measure(unbounded) }
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeables.forEach { it.place(0, 0) }
            }
        }
        // Everything below is outside the recorded layer, so it never exports.
        overlay()
        if (checkable) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xCC1E1E24)),
            ) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    colors = CheckboxDefaults.colors(
                        checkedColor = accent,
                        uncheckedColor = Color.White.copy(alpha = 0.7f),
                    ),
                )
            }
        }
        Text(
            text = "${index + 1}",
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(top = 2.dp),
        )
    }
}

@Composable
private fun PageFormula(
    item: PageItem,
    cell: InputCell,
    cellsById: Map<String, InputCell>,
    render: Float,
    accent: Color,
    interactive: Boolean,
    selection: ExportSelection?,
    onBounds: (Rect) -> Unit,
    onTap: (BlockPart?) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onPinch: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    // Parts are hit-tested by the block's own gesture handler, which keeps
    // dragging and part picking in one place instead of nesting detectors.
    var blockCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val partCoords = remember { mutableMapOf<BlockPart, LayoutCoordinates>() }
    val selected = selection != null
    // The gesture coroutine outlives recompositions, so everything it reads has
    // to come from a live handle rather than a captured copy.
    val live by rememberUpdatedState(item)
    val tap by rememberUpdatedState(onTap)
    val dragStart by rememberUpdatedState(onDragStart)
    val drag by rememberUpdatedState(onDrag)
    val pinch by rememberUpdatedState(onPinch)
    val dragEnd by rememberUpdatedState(onDragEnd)

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    CrashGuard.roundPx(item.offset.x * render),
                    CrashGuard.roundPx(item.offset.y * render),
                )
            }
            .graphicsLayer {
                val scale = CrashGuard.finite(item.scale * render, 1f)
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .onGloballyPositioned {
                blockCoords = it
                onBounds(it.boundsInParent())
            }
            .then(
                if (!interactive) {
                    Modifier
                } else {
                    Modifier.pointerInput(item.cellId, render) {
                        val slop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var moved = false
                            // A finger never holds perfectly still, so movement only
                            // becomes a drag past the slop — otherwise every tap would
                            // nudge the block instead of selecting it.
                            var travel = 0f
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) {
                                    // Claim the release so the page below keeps its
                                    // background tap for genuinely empty paper.
                                    event.changes.forEach { it.consume() }
                                    break
                                }
                                if (pressed.size >= 2) {
                                    val zoom = event.calculateZoom()
                                    if (zoom != 1f) {
                                        if (!moved) dragStart()
                                        moved = true
                                        pinch(zoom)
                                    }
                                } else {
                                    // Deltas arrive in the block's own scaled space;
                                    // ×scale turns them back into page units.
                                    val delta = pressed.first().positionChange()
                                    if (!moved) {
                                        travel += delta.getDistance()
                                        if (travel > slop) {
                                            moved = true
                                            dragStart()
                                            drag(delta * live.scale)
                                        }
                                    } else if (delta != Offset.Zero) {
                                        drag(delta * live.scale)
                                    }
                                }
                                if (moved) event.changes.forEach { it.consume() }
                            }
                            if (moved) {
                                dragEnd()
                            } else {
                                val root = blockCoords
                                tap(
                                    if (root == null) {
                                        null
                                    } else {
                                        partCoords.entries
                                            .filter { it.key !in live.hiddenParts && it.value.isAttached }
                                            .map { it.key to root.localBoundingBoxOf(it.value, false) }
                                            .filter { it.second.contains(down.position) }
                                            .minByOrNull { it.second.width * it.second.height }
                                            ?.first
                                    },
                                )
                            }
                        }
                    }
                },
            )
            // The outline wraps the entire block; a second tap narrows the
            // selection down to the single row or answer line under the finger.
            .then(
                if (selected) {
                    Modifier
                        .background(accent.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                        .border(1.dp, accent.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                } else {
                    Modifier
                },
            )
            .padding(2.dp),
    ) {
        ExportFormulaBody(
            cell = cell,
            cellsById = cellsById,
            hidden = item.hiddenParts,
            activePart = selection?.part,
            accent = accent,
            onPartBounds = { part, coords -> partCoords[part] = coords },
        )
    }
}

/**
 * Renders the visible pieces of a block. Rows and the answer line are laid out
 * together so the block still moves as one, while each piece can be picked out
 * and removed on its own.
 */
@Composable
private fun ExportFormulaBody(
    cell: InputCell,
    cellsById: Map<String, InputCell>,
    hidden: Set<BlockPart>,
    activePart: BlockPart?,
    accent: Color,
    onPartBounds: (BlockPart, LayoutCoordinates) -> Unit,
) {
    Column {
        when (cell.kind) {
            CellKind.Formula -> if (BlockPart.Body !in hidden) {
                PartBox(BlockPart.Body, activePart, accent, onPartBounds) {
                    StructuredMathView(
                        document = cell.document,
                        focused = false,
                        textColor = PAGE_INK,
                        fontSize = 22.sp,
                    )
                }
            }
            CellKind.System -> {
                val rows = cell.equations.withIndex()
                    .filterNot { BlockPart.Equation(it.index) in hidden }
                if (rows.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "{",
                            color = PAGE_INK,
                            fontSize = (28 + rows.size * 4).coerceAtMost(48).sp,
                            fontWeight = FontWeight.Light,
                            fontFamily = FontFamily.Serif,
                        )
                        Column {
                            rows.forEach { (index, equation) ->
                                PartBox(BlockPart.Equation(index), activePart, accent, onPartBounds) {
                                    StructuredMathView(
                                        document = equation,
                                        focused = false,
                                        textColor = PAGE_INK,
                                        fontSize = 20.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            CellKind.Graph -> if (BlockPart.Body !in hidden) {
                PartBox(BlockPart.Body, activePart, accent, onPartBounds) {
                    ExportGraphCard(
                        cell = cell,
                        cellsById = cellsById,
                    )
                }
            }
        }
        val result = cell.resultText
        if (!result.isNullOrBlank() && BlockPart.Result !in hidden) {
            val layout = LocalAnswerLayout.current
            PartBox(BlockPart.Result, activePart, accent, onPartBounds) {
                AnswerView(
                    result = result,
                    layout = layout,
                    mixed = layout == AnswerLayout.Column && !cell.showDecimal,
                    color = PAGE_RESULT_INK,
                    fontSize = 16.sp,
                    exact = cell.resultExact,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                )
            }
        }
    }
}

/**
 * Print-ready counterpart of `GraphBoard`: same 300×260 geometry, exact saved
 * viewport, live [GraphPlotCanvas] (grid, axes, curves, markers) and linked
 * formula chips, without gesture interceptors so the page composer owns drags.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExportGraphCard(
    cell: InputCell,
    cellsById: Map<String, InputCell>,
) {
    val traces = remember(cell.graph, cellsById) { resolveExportGraphTraces(cell, cellsById) }
    val (seriesLabels, seriesColors) = remember(traces) { expandGraphSeries(traces) }
    val viewport = cell.graph?.viewport ?: GraphViewport()

    Column(
        modifier = Modifier
            .width(300.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFFAFAFC))
            .border(1.dp, Color(0x24000000), RoundedCornerShape(12.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.graph_title),
            style = MaterialTheme.typography.labelLarge,
            color = PAGE_INK,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, GraphLinkYellow.copy(alpha = 0.65f), RoundedCornerShape(10.dp)),
        ) {
            GraphPlotCanvas(
                expressions = seriesLabels,
                colors = seriesColors,
                viewport = viewport,
                dark = false,
            )
        }
        if (traces.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                traces.forEach { trace ->
                    ExportFunctionChip(trace)
                }
            }
        }
    }
}

@Composable
private fun ExportFunctionChip(trace: GraphTrace) {
    Row(
        modifier = Modifier
            .widthIn(min = 72.dp, max = 284.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(trace.color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(trace.color, CircleShape),
        )
        if (trace.documents.isNotEmpty()) {
            Column(
                modifier = Modifier.wrapContentWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                trace.documents.forEach { doc ->
                    StructuredMathView(
                        document = doc,
                        focused = false,
                        textColor = PAGE_INK,
                        fontSize = 16.sp,
                        captureCaretTaps = false,
                    )
                }
            }
        } else {
            Text(
                text = trace.label,
                color = PAGE_INK,
                fontSize = 16.sp,
            )
        }
    }
}

@Composable
private fun PartBox(
    part: BlockPart,
    activePart: BlockPart?,
    accent: Color,
    onPartBounds: (BlockPart, LayoutCoordinates) -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .onGloballyPositioned { onPartBounds(part, it) }
            .then(
                if (part == activePart) {
                    Modifier.background(accent.copy(alpha = 0.22f), RoundedCornerShape(3.dp))
                } else {
                    Modifier
                },
            ),
    ) {
        content()
    }
}

@Composable
private fun ExportTopBar(
    format: ExportFormat,
    busy: Boolean,
    accent: Color,
    onFormatChange: (ExportFormat) -> Unit,
    onClose: () -> Unit,
    onExport: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Close, stringResource(R.string.action_cancel), tint = Color.White)
        }
        Text(
            text = stringResource(R.string.export_title),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            modifier = Modifier.weight(1f),
        )
        ExportFormat.entries.forEach { option ->
            val active = option == format
            Text(
                text = option.name.uppercase(),
                fontSize = 12.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = if (active) Color.White else Color.White.copy(alpha = 0.55f),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) accent.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.08f))
                    .clickable { onFormatChange(option) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(accent.copy(alpha = 0.85f))
                .clickable(enabled = !busy, onClick = onExport),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Icon(Icons.Outlined.Save, stringResource(R.string.export_action), tint = Color.White)
            }
        }
    }
}

@Composable
private fun EditorToolbar(
    canUndo: Boolean,
    canRedo: Boolean,
    canReset: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onReset: () -> Unit,
    accent: Color,
    pageSelectMode: Boolean,
    checkedCount: Int,
    onDeletePages: () -> Unit,
    onKeepPages: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ToolIcon(
            icon = Icons.AutoMirrored.Outlined.Undo,
            label = stringResource(R.string.export_undo),
            enabled = canUndo,
            onClick = onUndo,
        )
        ToolIcon(
            icon = Icons.AutoMirrored.Outlined.Redo,
            label = stringResource(R.string.export_redo),
            enabled = canRedo,
            onClick = onRedo,
        )
        ToolIcon(
            icon = Icons.Outlined.Refresh,
            label = stringResource(R.string.export_reset),
            enabled = canReset,
            onClick = onReset,
        )
        Box(modifier = Modifier.weight(1f))
        if (pageSelectMode) {
            Text(
                text = stringResource(R.string.export_pages_selected, checkedCount),
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 12.sp,
                modifier = Modifier.padding(end = 4.dp),
            )
            ToolIcon(
                icon = Icons.Outlined.Delete,
                label = stringResource(R.string.export_delete_pages),
                enabled = checkedCount > 0,
                tint = Color(0xFFFF6B6B),
                onClick = onDeletePages,
            )
            ToolIcon(
                icon = Icons.Outlined.CenterFocusStrong,
                label = stringResource(R.string.export_keep_pages),
                enabled = checkedCount > 0,
                tint = accent,
                onClick = onKeepPages,
            )
        }
    }
}

@Composable
private fun ToolIcon(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    tint: Color = Color.White,
    background: Color = Color.White.copy(alpha = if (enabled) 0.08f else 0.03f),
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (enabled) tint else tint.copy(alpha = 0.3f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun ExportBottomBar(
    pageCount: Int,
    multiPage: Boolean,
    pageSelectMode: Boolean,
    @StringRes hint: Int?,
    onToggleSelectMode: () -> Unit,
    onAddPage: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E24))
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Icon-only actions keep the bar one line tall whatever the locale does
        // to the labels; the single caption is the only thing allowed to shrink.
        Text(
            text = hint?.let { stringResource(it) } ?: stringResource(R.string.export_pages, pageCount),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (multiPage) {
            ToolIcon(
                icon = Icons.Outlined.Checklist,
                label = stringResource(R.string.export_select_pages),
                enabled = true,
                tint = if (pageSelectMode) Color(0xFF16161A) else Color.White,
                background = if (pageSelectMode) {
                    Color.White.copy(alpha = 0.85f)
                } else {
                    Color.White.copy(alpha = 0.08f)
                },
                onClick = onToggleSelectMode,
            )
            ToolIcon(
                icon = Icons.AutoMirrored.Outlined.NoteAdd,
                label = stringResource(R.string.export_add_page),
                enabled = true,
                onClick = onAddPage,
            )
        }
    }
}

/**
 * Floating bar that follows the selected block: a compact horizontal row placed
 * just above it, or below when the block sits at the very top of the page.
 */
@Composable
private fun BoxScope.SelectionBar(
    anchor: Rect,
    page: Size,
    wholeBlock: Boolean,
    accent: Color,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var barSize by remember { mutableStateOf(IntSize.Zero) }
    val gap = with(LocalDensity.current) { 6.dp.toPx() }

    Row(
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset {
                val above = anchor.top - barSize.height - gap
                val below = (anchor.bottom + gap).coerceAtMost(page.height - barSize.height)
                IntOffset(
                    x = CrashGuard.roundPx(
                        CrashGuard.finite(anchor.left).let { left ->
                            val maxX = (page.width - barSize.width).coerceAtLeast(0f)
                            if (maxX.isFinite()) left.coerceIn(0f, maxX) else left
                        },
                    ),
                    y = CrashGuard.roundPx(
                        CrashGuard.finite(if (above >= 0f) above else below).coerceAtLeast(0f),
                    ),
                )
            }
            .onGloballyPositioned { barSize = it.size }
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xF21E1E24))
            .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(
                if (wholeBlock) R.string.export_selection_block else R.string.export_selection_part,
            ),
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 12.sp,
            maxLines = 1,
            softWrap = false,
        )
        BarIcon(
            icon = Icons.Outlined.Delete,
            label = stringResource(
                if (wholeBlock) R.string.export_delete_block else R.string.export_delete_part,
            ),
            tint = Color(0xFFFF6B6B),
            onClick = onDelete,
        )
        BarIcon(
            icon = Icons.Outlined.Close,
            label = stringResource(R.string.export_deselect),
            tint = Color.White.copy(alpha = 0.75f),
            onClick = onDismiss,
        )
    }
}

@Composable
private fun BarIcon(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(17.dp))
    }
}
