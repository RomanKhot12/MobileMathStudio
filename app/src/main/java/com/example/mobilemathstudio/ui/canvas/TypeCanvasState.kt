package com.example.mobilemathstudio.ui.canvas

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import com.example.mobilemathstudio.CrashGuard
import com.example.mobilemathstudio.math.CasWorker
import com.example.mobilemathstudio.math.LatexCodec
import com.example.mobilemathstudio.math.LatexImporter
import com.example.mobilemathstudio.math.MathEngine
import com.example.mobilemathstudio.math.SolveOutcome
import com.example.mobilemathstudio.math.SymjaEngine
import com.example.mobilemathstudio.math.expr.MathDocument
import com.example.mobilemathstudio.math.expr.MathEditor
import com.example.mobilemathstudio.math.expr.MathRange
import com.example.mobilemathstudio.math.expr.linearize
import com.example.mobilemathstudio.math.graph.GraphViewport
import kotlinx.coroutines.currentCoroutineContext
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

enum class CellKind { Formula, System, Graph }

data class GraphState(
    val linkedCellIds: List<String> = emptyList(),
    val viewport: GraphViewport = GraphViewport(),
)

data class InputCell(
    val id: String = UUID.randomUUID().toString(),
    val position: Offset,
    val kind: CellKind = CellKind.Formula,
    val document: MathDocument = MathDocument.empty(),
    /** Used when [kind] == [CellKind.System]. */
    val equations: List<MathDocument> = emptyList(),
    val activeEquationIndex: Int = 0,
    /** Used when [kind] == [CellKind.Graph]. */
    val graph: GraphState? = null,
    /** Per-formula pinch zoom, independent of the canvas zoom. */
    val scale: Float = 1f,
    val selected: Boolean = false,
    val systemMenuOpen: Boolean = false,
    val resultExact: String? = null,
    val resultDecimal: String? = null,
    val showDecimal: Boolean = false,
    val errorStart: Int? = null,
    val errorEnd: Int? = null,
    val errorMessage: String? = null,
) {
    val activeDocument: MathDocument
        get() = when (kind) {
            CellKind.Formula -> document
            CellKind.System -> equations.getOrElse(activeEquationIndex) { MathDocument.empty() }
            CellKind.Graph -> MathDocument.empty()
        }

    val text: String
        get() = when (kind) {
            CellKind.Formula -> document.toLinear()
            CellKind.System -> equations.joinToString("\n") { it.toLinear() }
            CellKind.Graph -> "Graph"
        }

    val filledEquationCount: Int
        get() = equations.count { !it.isBlank() }

    val resultText: String?
        get() = when {
            kind == CellKind.Graph -> null
            resultExact == null -> null
            showDecimal && !resultDecimal.isNullOrBlank() -> resultDecimal
            else -> resultExact
        }

    val canToggleExactDecimal: Boolean
        get() = kind != CellKind.Graph &&
            !resultExact.isNullOrBlank() &&
            !resultDecimal.isNullOrBlank() &&
            resultExact != resultDecimal

    fun toLatexCases(): String {
        if (kind != CellKind.System) return LatexCodec.toLatex(document.root)
        val body = equations.joinToString(" \\\\\n") { LatexCodec.toLatex(it.root) }
        return "\\begin{cases}\n$body\n\\end{cases}"
    }

    fun toPlainText(): String = when (kind) {
        CellKind.Formula -> document.toLinear()
        CellKind.System -> equations.joinToString("\n") { it.toLinear() }
        CellKind.Graph -> graph?.linkedCellIds?.joinToString(", ").orEmpty()
    }

    fun plotExpressions(): List<String> = when (kind) {
        CellKind.Formula -> listOfNotNull(document.toLinear().takeIf { it.isNotBlank() })
        CellKind.System -> equations.map { it.toLinear() }.filter { it.isNotBlank() }
        CellKind.Graph -> emptyList()
    }
}

/**
 * Type-mode infinite canvas: pan, cell drag, systems, structured math, solve results.
 */
class TypeCanvasState {
    var cells by mutableStateOf<List<InputCell>>(emptyList())
        private set

    var focusedId by mutableStateOf<String?>(null)
        private set

    var menuCellId by mutableStateOf<String?>(null)
        private set

    /** Slot the open context menu acts on, when the press landed inside one. */
    var menuSlot by mutableStateOf<SlotRef?>(null)
        private set

    /** Cell currently held by the finger; drives the corner grab indicator. */
    var draggingCellId by mutableStateOf<String?>(null)
        private set

    /** Empty slot the grab point hovers over, armed for insertion on release. */
    var dropTarget by mutableStateOf<SlotRef?>(null)
        private set

    var pan by mutableStateOf(Offset.Zero)
        private set

    var zoom by mutableStateOf(1f)
        private set

    /** Viewport size in px, reported by the workspace. */
    var viewport by mutableStateOf(Size.Zero)

    /** Chrome that will cover the canvas (top bar / keyboard), in px. */
    var topReservePx by mutableStateOf(0f)
    var bottomReservePx by mutableStateOf(0f)

    /** Measured layout size of each cell in world units (excludes cell scale). */
    private val cellSizes = mutableStateMapOf<String, Size>()

    val focusedCell: InputCell?
        get() = cells.firstOrNull { it.id == focusedId }

    val isKeyboardVisible: Boolean
        get() = focusedId != null && !keyboardCollapsed && focusedCell?.kind != CellKind.Graph

    val hasFocus: Boolean
        get() = focusedId != null

    val isInNestedSlot: Boolean
        get() = focusedCell?.let { MathEditor.isInNestedSlot(it.activeDocument) } == true

    /** Graph currently waiting for the user to tap a formula on the canvas. */
    var graphPickId by mutableStateOf<String?>(null)
        private set

    /** Graph under a dragged formula, armed for drop-to-link. */
    var graphDropId by mutableStateOf<String?>(null)
        private set

    var canUndo by mutableStateOf(false)
        private set

    var canRedo by mutableStateOf(false)
        private set

    var keyboardCollapsed by mutableStateOf(false)
        private set

    /** Mirrors [PerformanceSnapshot.animationsEnabled]; Power Saver skips canvas glides. */
    var animationsEnabled: Boolean = true

    var selection by mutableStateOf<MathRange?>(null)
        private set

    var selectionCellId by mutableStateOf<String?>(null)
        private set

    private var past: List<EditSnapshot> = emptyList()
    private var future: List<EditSnapshot> = emptyList()
    private var historyDepth = 0

    fun screenToWorld(screen: Offset): Offset = (screen - pan) / zoom

    fun worldToScreen(world: Offset): Offset = world * zoom + pan

    fun panBy(delta: Offset) {
        if (!delta.x.isFinite() || !delta.y.isFinite()) return
        pan = (pan + delta).sanitized()
    }

    /** Pinch on empty canvas: scale everything, keeping [pivot] anchored. */
    fun zoomAround(pivot: Offset, factor: Float, panDelta: Offset = Offset.Zero) {
        if (!factor.isFinite() || factor <= 0f) return
        if (!pivot.x.isFinite() || !pivot.y.isFinite()) return
        val current = zoom.takeIf { it.isFinite() && it > 0f } ?: 1f
        val next = (current * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val origin = pan.sanitized()
        val world = (pivot - origin) / current
        val shift = if (panDelta.x.isFinite() && panDelta.y.isFinite()) panDelta else Offset.Zero
        pan = (pivot + shift - world * next).sanitized()
        zoom = next
    }

    /** Pinch on a formula: scale only that formula, around its own centre. */
    fun scaleCellBy(id: String, factor: Float) {
        if (!factor.isFinite() || factor <= 0f) return
        cells = cells.map { cell ->
            if (cell.id != id) return@map cell
            val current = cell.scale.takeIf { it.isFinite() && it > 0f } ?: 1f
            val next = (current * factor).coerceIn(MIN_CELL_SCALE, MAX_CELL_SCALE)
            if (next == current) return@map cell
            // The layer scales from the top-left, so shift the origin to keep the
            // formula visually anchored where the fingers are.
            val size = sizeOf(cell.id)
            val shift = Offset(
                size.width * (current - next) / 2f,
                size.height * (current - next) / 2f,
            )
            cell.copy(scale = next, position = (cell.position + shift).sanitized())
        }
    }

    fun reportCellSize(id: String, size: Size) {
        if (!size.width.isFinite() || !size.height.isFinite()) return
        if (cellSizes[id] != size) cellSizes[id] = size
    }

    fun sizeOf(id: String): Size = cellSizes[id] ?: DEFAULT_CELL_SIZE

    fun restore(cells: List<InputCell>, pan: Offset, zoom: Float = 1f) {
        this.cells = cells.map { it.sanitized() }
        this.pan = pan.sanitized()
        this.zoom = (zoom.takeIf { it.isFinite() } ?: 1f).coerceIn(MIN_ZOOM, MAX_ZOOM)
        cellSizes.keys.retainAll(cells.mapTo(HashSet()) { it.id })
        focusedId = null
        menuCellId = null
        menuSlot = null
        draggingCellId = null
        dropTarget = null
        graphPickId = null
        graphDropId = null
        past = emptyList()
        future = emptyList()
        canUndo = false
        canRedo = false
        keyboardCollapsed = false
        clearSelection()
    }

    /** Never persists a still-empty input line, even if it is currently focused. */
    fun snapshotCells(): List<InputCell> = cells.filterNot { it.isDiscardableEmpty() }

    fun createCellAtScreen(screen: Offset) {
        dismissMenu()
        settleFocused()
        val cell = InputCell(position = screenToWorld(screen))
        cells = cells + cell
        focusedId = cell.id
        keyboardCollapsed = false
    }

    /**
     * Spawns a cell in the middle of the viewport. When the middle is taken the
     * canvas glides to the nearest free patch first, so nothing ever overlaps.
     */
    suspend fun createCellCentered(
        document: MathDocument = MathDocument.empty(),
        kind: CellKind = CellKind.Formula,
        equations: List<MathDocument> = emptyList(),
    ): String {
        dismissMenu()
        settleFocused()
        val need = if (kind == CellKind.System) {
            Size(DEFAULT_CELL_SIZE.width, 38f * equations.size.coerceAtLeast(2))
        } else {
            DEFAULT_CELL_SIZE
        }
        val spot = freeSpotFor(need)
        glideTo(spot, need)
        val cell = InputCell(
            position = spot,
            kind = kind,
            document = document,
            equations = equations,
        )
        commitEdit {
            cells = cells + cell
            focusedId = cell.id
            keyboardCollapsed = kind == CellKind.Graph
        }
        return cell.id
    }

    /** Renders raw LaTeX into a new structured formula (or a system for `cases`). */
    suspend fun importLatex(latex: String) {
        val imported = LatexImporter.import(latex)
        if (imported.isSystem) {
            createCellCentered(kind = CellKind.System, equations = imported.documents)
        } else {
            createCellCentered(document = imported.documents.first())
        }
    }

    /** Centre of the area that stays visible once the keyboard slides in. */
    private fun visibleCenter(): Offset {
        val top = topReservePx
        val bottom = (viewport.height - bottomReservePx).coerceAtLeast(top + 1f)
        return Offset(viewport.width / 2f, (top + bottom) / 2f)
    }

    private fun freeSpotFor(need: Size): Offset {
        if (viewport == Size.Zero) return Offset(80f, 160f)
        val center = screenToWorld(visibleCenter())
        val origin = Offset(center.x - need.width / 2f, center.y - need.height / 2f)
        if (isFree(origin, need)) return origin
        val stepX = need.width + SPAWN_GAP
        val stepY = need.height + SPAWN_GAP
        for (ring in 1..12) {
            for (dy in -ring..ring) {
                for (dx in -ring..ring) {
                    if (max(abs(dx), abs(dy)) != ring) continue
                    val candidate = origin + Offset(dx * stepX, dy * stepY)
                    if (isFree(candidate, need)) return candidate
                }
            }
        }
        return origin + Offset(0f, 13 * stepY)
    }

    private fun isFree(topLeft: Offset, need: Size): Boolean {
        val target = Rect(topLeft, need).inflate(SPAWN_GAP / 2f)
        return cells.none { cell ->
            val size = sizeOf(cell.id) * cell.scale
            target.overlaps(Rect(cell.position, size))
        }
    }

    private suspend fun glideTo(worldTopLeft: Offset, need: Size) {
        if (viewport == Size.Zero) return
        val worldCenter = worldTopLeft + Offset(need.width / 2f, need.height / 2f)
        val target = visibleCenter() - worldCenter * zoom
        if ((target - pan).getDistance() < 1f) return
        if (currentCoroutineContext()[MonotonicFrameClock] == null) {
            // No frame clock (unit tests / background call): jump instead of hanging.
            pan = target.sanitized()
            return
        }
        val from = pan.sanitized()
        val dest = target.sanitized()
        val spec = if (animationsEnabled) {
            tween<Float>(durationMillis = 300, easing = FastOutSlowInEasing)
        } else {
            snap()
        }
        Animatable(0f).animateTo(1f, spec) {
            pan = lerp(from, dest, value).sanitized()
        }
    }

    fun focus(id: String) {
        if (focusedId != null && focusedId != id) settleFocused()
        if (cells.any { it.id == id }) {
            if (focusedId != id) clearSelection()
            focusedId = id
            keyboardCollapsed = cells.firstOrNull { it.id == id }?.kind == CellKind.Graph
            clearCellError(id)
            dismissSystemMenusExcept(id)
        }
    }

    fun clearFocus() {
        settleFocused()
        focusedId = null
        keyboardCollapsed = false
        clearSelection()
        dismissMenu()
        dismissAllSystemMenus()
    }

    fun collapseKeyboard() {
        if (focusedId != null) keyboardCollapsed = true
    }

    fun expandKeyboard() {
        keyboardCollapsed = false
    }

    fun clearSelection() {
        selection = null
        selectionCellId = null
    }

    /**
     * Long-press on a formula: select the tapped subtree (or the whole formula)
     * without dropping keyboard focus.
     */
    fun beginSelectionAt(cellId: String, slot: SlotRef?): Boolean {
        val cell = cells.firstOrNull { it.id == cellId } ?: return false
        val eq = slot?.equationIndex ?: SlotRef.NO_EQUATION
        val doc = if (eq >= 0) cell.equations.getOrNull(eq) else cell.document
        doc ?: return false
        val path = slot?.path.orEmpty()
        val range = MathEditor.selectionCovering(doc, path) ?: return false
        if (eq >= 0) setActiveEquation(cellId, eq)
        focusedId = cellId
        selection = range
        selectionCellId = cellId
        dismissMenu()
        dismissAllSystemMenus()
        return true
    }

    fun moveSelectionEdge(startHandle: Boolean, deltaChars: Int) {
        if (deltaChars == 0) return
        val range = selection ?: return
        val id = selectionCellId ?: focusedId ?: return
        val doc = cells.firstOrNull { it.id == id }?.activeDocument ?: return
        val moved = if (startHandle) {
            MathEditor.moveCaretFrom(doc, range.start, deltaChars)
        } else {
            MathEditor.moveCaretFrom(doc, range.end, deltaChars)
        }
        val next = if (startHandle) MathRange(moved, range.end) else MathRange(range.start, moved)
        selection = MathEditor.orderedRange(doc, next)
    }

    fun selectionText(): String? {
        val range = selection ?: return null
        val id = selectionCellId ?: return null
        val doc = cells.firstOrNull { it.id == id }?.activeDocument ?: return null
        return MathEditor.extractLinear(doc, range).ifBlank { null }
    }

    fun deleteSelection(): Boolean {
        val range = selection ?: return false
        val id = selectionCellId ?: focusedId ?: return false
        updateActiveDoc(id) { MathEditor.deleteRange(it, range) }
        clearSelection()
        return true
    }

    fun replaceSelection(text: String): Boolean {
        if (text.isBlank()) return false
        val range = selection
        val id = selectionCellId ?: focusedId ?: return false
        if (range != null) {
            updateActiveDoc(id) { doc ->
                val cleared = MathEditor.deleteRange(doc, range)
                val imported = LatexImporter.importOne(text)
                val payload = imported.root.children.singleOrNull() ?: imported.root
                MathEditor.replaceNodeAt(cleared, cleared.caret.path, payload)
            }
            clearSelection()
            return true
        }
        replaceText(id, text, asLatex = true)
        return true
    }

    fun moveCellBy(id: String, delta: Offset) {
        if (!delta.x.isFinite() || !delta.y.isFinite()) return
        cells = cells.map { cell ->
            if (cell.id != id) cell
            else cell.copy(position = (cell.position + delta).sanitized())
        }
    }

    fun focusHole(holeId: String) {
        val id = focusedId ?: return
        clearSelection()
        updateActiveDoc(id, recordHistory = false) { MathEditor.focusHole(it, holeId) }
    }

    fun insert(token: String, cursorBack: Int = 0) {
        val id = focusedId ?: return
        val range = selection.takeIf { selectionCellId == id }
        clearSelection()
        updateActiveDoc(id) { doc ->
            var next = if (range != null) MathEditor.deleteRange(doc, range) else doc
            next = MathEditor.insertText(next, token)
            if (cursorBack > 0) {
                repeat(cursorBack) { next = MathEditor.moveCaret(next, -1) }
            }
            next
        }
    }

    fun insertFraction() = updateFocusedActive(MathEditor::insertFraction)
    fun insertPower() = updateFocusedActive(MathEditor::insertPower)
    fun insertExponent(token: String) = updateFocusedActive { MathEditor.insertExponent(it, token) }
    fun insertSqrt() = updateFocusedActive(MathEditor::insertSqrt)
    fun insertNthRoot() = updateFocusedActive(MathEditor::insertNthRoot)

    fun confirmNestedSlot() {
        val id = focusedId ?: return
        updateActiveDoc(id) { MathEditor.confirmSlot(it) }
    }

    fun backspace() {
        val id = focusedId ?: return
        if (selection != null && selectionCellId == id) {
            deleteSelection()
            return
        }
        updateActiveDoc(id) { MathEditor.backspace(it) }
    }

    /** Clears only the focused formula. Call without a confirmation dialog. */
    fun clearAll() {
        val id = focusedId ?: return
        clearSelection()
        updateActiveDoc(id) { MathEditor.clear(it) }
    }

    /** Wipes every formula on the canvas. Call only after the user confirms. */
    fun clearCanvas() {
        if (cells.isEmpty()) return
        commitEdit {
            cells = emptyList()
            focusedId = null
            menuCellId = null
            menuSlot = null
            draggingCellId = null
            dropTarget = null
            dismissAllSystemMenus()
            keyboardCollapsed = false
            clearSelection()
        }
    }

    fun undo() {
        val snap = past.lastOrNull() ?: return
        past = past.dropLast(1)
        future = future + EditSnapshot(cells, focusedId)
        applySnapshot(snap)
        canUndo = past.isNotEmpty()
        canRedo = true
    }

    fun redo() {
        val snap = future.lastOrNull() ?: return
        future = future.dropLast(1)
        past = past + EditSnapshot(cells, focusedId)
        applySnapshot(snap)
        canUndo = true
        canRedo = future.isNotEmpty()
    }

    /**
     * Long-press on an empty window of a 2D template: drop the whole template
     * (including filled sibling slots) and leave the caret in its place.
     */
    fun deleteTemplateAtEmptySlot(ref: SlotRef): Boolean {
        val doc = documentAt(ref) ?: return false
        if (!MathEditor.isSlotEmpty(doc, ref.path)) return false
        if (MathEditor.enclosingTemplatePath(doc, ref.path) == null) return false
        val next = MathEditor.deleteEnclosingTemplate(doc, ref.path)
        var applied = false
        commitEdit {
            applied = updateDocumentAt(ref) { next }
            if (applied) {
                focusedId = ref.cellId
                keyboardCollapsed = false
                if (ref.equationIndex >= 0) setActiveEquation(ref.cellId, ref.equationIndex)
                dismissMenu()
                dismissAllSystemMenus()
            }
        }
        return applied
    }

    fun moveCursor(delta: Int) {
        val id = focusedId ?: return
        updateActiveDoc(id, clearResults = false, recordHistory = false) {
            MathEditor.moveCaret(it, delta)
        }
    }

    fun placeCaret(path: List<Int>, offset: Int) {
        val id = focusedId ?: return
        clearSelection()
        updateActiveDoc(id, clearResults = false, recordHistory = false) {
            MathEditor.placeCaret(it, path, offset)
        }
    }

    fun deleteCell(id: String) {
        if (cells.none { it.id == id }) return
        commitEdit {
            cells = cells.filterNot { it.id == id }
            if (focusedId == id) focusedId = null
            if (menuCellId == id) menuCellId = null
            if (menuSlot?.cellId == id) menuSlot = null
            if (draggingCellId == id) draggingCellId = null
            if (dropTarget?.cellId == id) dropTarget = null
            if (selectionCellId == id) clearSelection()
            if (graphPickId == id) graphPickId = null
            cells = cells.map { cell ->
                if (cell.kind != CellKind.Graph) cell
                else {
                    val nextLinks = cell.graph?.linkedCellIds.orEmpty().filterNot { it == id }
                    cell.copy(graph = (cell.graph ?: GraphState()).copy(linkedCellIds = nextLinks))
                }
            }
        }
    }

    fun showMenu(id: String, slot: SlotRef? = null) {
        menuCellId = id
        menuSlot = slot?.takeIf { it.cellId == id }
        focusedId = id
    }

    fun dismissMenu() {
        menuCellId = null
        menuSlot = null
    }

    fun createGraphFrom(sourceId: String) {
        val source = cells.firstOrNull { it.id == sourceId } ?: return
        if (source.kind == CellKind.Graph) return
        val size = sizeOf(sourceId)
        val pos = Offset(
            source.position.x + size.width * source.scale + 28f,
            source.position.y,
        )
        val graph = InputCell(
            position = pos.sanitized(),
            kind = CellKind.Graph,
            graph = GraphState(linkedCellIds = listOf(sourceId)),
        )
        commitEdit {
            cells = cells + graph
            focusedId = graph.id
            keyboardCollapsed = true
        }
        graphPickId = null
        dismissMenu()
        dismissAllSystemMenus()
    }

    fun beginGraphPick(graphId: String) {
        if (cells.none { it.id == graphId && it.kind == CellKind.Graph }) return
        graphPickId = graphId
        focusedId = graphId
        keyboardCollapsed = true
        dismissMenu()
    }

    fun cancelGraphPick() {
        graphPickId = null
    }

    fun addGraphSource(graphId: String, sourceId: String) {
        if (graphId == sourceId) return
        val source = cells.firstOrNull { it.id == sourceId } ?: return
        if (source.kind == CellKind.Graph) return
        commitEdit {
            cells = cells.map { cell ->
                if (cell.id != graphId || cell.kind != CellKind.Graph) cell
                else {
                    val current = cell.graph ?: GraphState()
                    if (sourceId in current.linkedCellIds) cell
                    else cell.copy(graph = current.copy(linkedCellIds = current.linkedCellIds + sourceId))
                }
            }
        }
        graphPickId = null
    }

    fun removeGraphSource(graphId: String, sourceId: String) {
        commitEdit {
            cells = cells.map { cell ->
                if (cell.id != graphId || cell.kind != CellKind.Graph) cell
                else {
                    val current = cell.graph ?: GraphState()
                    cell.copy(graph = current.copy(linkedCellIds = current.linkedCellIds.filterNot { it == sourceId }))
                }
            }
        }
    }

    fun panGraph(id: String, dxMath: Double, dyMath: Double) {
        cells = cells.map { cell ->
            if (cell.id != id || cell.kind != CellKind.Graph) cell
            else {
                val g = cell.graph ?: GraphState()
                cell.copy(graph = g.copy(viewport = g.viewport.pan(dxMath, dyMath).sanitized()))
            }
        }
    }

    fun zoomGraph(id: String, factor: Float, pivotX: Double, pivotY: Double) {
        if (!factor.isFinite() || factor <= 0f) return
        cells = cells.map { cell ->
            if (cell.id != id || cell.kind != CellKind.Graph) cell
            else {
                val g = cell.graph ?: GraphState()
                cell.copy(graph = g.copy(viewport = g.viewport.zoom(factor.toDouble(), pivotX, pivotY)))
            }
        }
    }

    fun graphLinkedIds(): Set<String> = cells
        .filter { it.kind == CellKind.Graph }
        .flatMap { it.graph?.linkedCellIds.orEmpty() }
        .toSet()

    fun tracesForGraph(graph: InputCell): List<Pair<String, String>> {
        val ids = graph.graph?.linkedCellIds.orEmpty()
        return ids.mapNotNull { id ->
            val src = cells.firstOrNull { it.id == id } ?: return@mapNotNull null
            val labels = src.plotExpressions()
            if (labels.isEmpty()) return@mapNotNull null
            id to labels.joinToString("\n")
        }
    }

    /** Bottom-left grab point of a cell, in world units. */
    fun grabCorner(id: String): Offset? {
        val cell = cells.firstOrNull { it.id == id } ?: return null
        return cell.position + Offset(0f, sizeOf(id).height * cell.scale)
    }

    fun beginDrag(id: String) {
        draggingCellId = id
        dropTarget = null
        graphDropId = null
    }

    /** Abandons an in-flight drag without inserting anything (e.g. pinch takes over). */
    fun cancelDrag() {
        draggingCellId = null
        dropTarget = null
        graphDropId = null
    }

    /** Arms (or disarms) slot insertion while the finger is still down. */
    fun armDropTarget(target: SlotRef?) {
        if (dropTarget != target) dropTarget = target
    }

    fun armGraphDrop(graphId: String?) {
        if (graphDropId != graphId) graphDropId = graphId
    }

    /**
     * Releases the drag: links onto a graph, inserts into an armed slot, or
     * leaves the block where the finger let go.
     */
    fun endDrag(): Boolean {
        val source = draggingCellId
        val target = dropTarget
        val graphTarget = graphDropId
        draggingCellId = null
        dropTarget = null
        graphDropId = null
        if (source != null && graphTarget != null && canLinkToGraph(source, graphTarget)) {
            addGraphSource(graphTarget, source)
            return true
        }
        if (source == null || target == null) return false
        return insertCellIntoSlot(source, target)
    }

    fun canLinkToGraph(sourceId: String, graphId: String): Boolean {
        if (sourceId == graphId) return false
        val source = cells.firstOrNull { it.id == sourceId } ?: return false
        val graph = cells.firstOrNull { it.id == graphId } ?: return false
        if (graph.kind != CellKind.Graph || source.kind == CellKind.Graph) return false
        return source.plotExpressions().isNotEmpty()
    }

    /** Only whole formulas travel into slots; systems stay indivisible. */
    fun canDropInto(sourceId: String, target: SlotRef): Boolean {
        if (target.cellId == sourceId) return false
        val source = cells.firstOrNull { it.id == sourceId } ?: return false
        if (source.kind != CellKind.Formula || source.document.isBlank()) return false
        val doc = documentAt(target) ?: return false
        return MathEditor.isSlotEmpty(doc, target.path)
    }

    private fun insertCellIntoSlot(sourceId: String, target: SlotRef): Boolean {
        if (!canDropInto(sourceId, target)) return false
        val source = cells.firstOrNull { it.id == sourceId } ?: return false
        val payload = MathEditor.collapseEmpty(source.document).root
        var applied = false
        commitEdit {
            applied = updateDocumentAt(target) { doc ->
                MathEditor.replaceNodeAt(doc, target.path, payload)
            }
            if (applied) {
                cells = cells.filterNot { it.id == sourceId }
                if (focusedId == sourceId) focusedId = target.cellId
            }
        }
        return applied
    }

    /** LaTeX of a single slot, for targeted copying inside composite blocks. */
    fun slotLatex(ref: SlotRef): String? {
        val doc = documentAt(ref) ?: return null
        val node = MathEditor.nodeAt(doc, ref.path) ?: return null
        return LatexCodec.toLatex(node)
    }

    fun slotText(ref: SlotRef): String? {
        val doc = documentAt(ref) ?: return null
        return MathEditor.nodeAt(doc, ref.path)?.let { linearize(it) }
    }

    /** Pastes into one child slot instead of overwriting the whole block. */
    fun pasteIntoSlot(ref: SlotRef, text: String): Boolean {
        if (text.isBlank()) return false
        val node = LatexImporter.importOne(text).root
        var applied = false
        commitEdit {
            applied = updateDocumentAt(ref) { doc -> MathEditor.replaceNodeAt(doc, ref.path, node) }
            if (applied) {
                focusedId = ref.cellId
                keyboardCollapsed = false
                if (ref.equationIndex >= 0) setActiveEquation(ref.cellId, ref.equationIndex)
                dismissMenu()
            }
        }
        return applied
    }

    private fun documentAt(ref: SlotRef): MathDocument? {
        val cell = cells.firstOrNull { it.id == ref.cellId } ?: return null
        return if (ref.equationIndex >= 0) cell.equations.getOrNull(ref.equationIndex) else cell.document
    }

    private fun updateDocumentAt(ref: SlotRef, transform: (MathDocument) -> MathDocument): Boolean {
        var applied = false
        cells = cells.map { cell ->
            if (cell.id != ref.cellId) return@map cell
            if (ref.equationIndex >= 0) {
                val eqs = cell.equations.toMutableList()
                val index = ref.equationIndex
                if (index !in eqs.indices) return@map cell
                eqs[index] = transform(eqs[index])
                applied = true
                cell.copy(
                    equations = eqs,
                    activeEquationIndex = index,
                    resultExact = null,
                    resultDecimal = null,
                    showDecimal = false,
                )
            } else {
                applied = true
                cell.copy(
                    document = transform(cell.document),
                    resultExact = null,
                    resultDecimal = null,
                    showDecimal = false,
                    errorStart = null,
                    errorEnd = null,
                    errorMessage = null,
                )
            }
        }
        return applied
    }

    fun toggleSelect(id: String) {
        cells = cells.map {
            if (it.id == id) it.copy(selected = !it.selected) else it
        }
        dismissMenu()
    }

    suspend fun createOrWrapSystem() {
        dismissMenu()
        val selected = cells.filter { it.selected && it.kind == CellKind.Formula }.take(2)
        val focused = focusedCell?.takeIf { it.kind == CellKind.Formula && !it.document.isBlank() }

        val sources: List<MathDocument> = when {
            selected.isNotEmpty() -> selected.map { it.document }
            focused != null -> listOf(focused.document)
            else -> emptyList()
        }

        if (sources.isEmpty()) {
            cells = cells.map { it.copy(selected = false) }
            createCellCentered(
                kind = CellKind.System,
                equations = listOf(MathDocument.empty(), MathDocument.empty()),
            )
            return
        }

        val equations = if (sources.size == 1) {
            listOf(sources[0], MathDocument.empty())
        } else {
            sources
        }

        val pos = selected.firstOrNull()?.position ?: focused?.position ?: Offset(100f, 200f)

        val removeIds = buildSet {
            addAll(selected.map { it.id })
            if (focused != null && selected.isEmpty()) add(focused.id)
        }

        val system = InputCell(
            position = pos,
            kind = CellKind.System,
            equations = equations,
            activeEquationIndex = 0,
        )
        commitEdit {
            cells = (cells.filterNot { it.id in removeIds } + system).map { it.copy(selected = false) }
            focusedId = system.id
            keyboardCollapsed = false
        }
    }

    fun showSystemMenu(id: String, slot: SlotRef? = null) {
        cells = cells.map {
            it.copy(systemMenuOpen = it.id == id)
        }
        focusedId = id
        dismissMenu()
        menuSlot = slot?.takeIf { it.cellId == id }
    }

    fun dismissAllSystemMenus() {
        cells = cells.map { it.copy(systemMenuOpen = false) }
        menuSlot = null
    }

    fun dismissSystemMenusExcept(id: String) {
        cells = cells.map { it.copy(systemMenuOpen = it.systemMenuOpen && it.id == id) }
    }

    fun addEquationToSystem(id: String) {
        commitEdit {
            cells = cells.map { cell ->
                if (cell.id != id || cell.kind != CellKind.System) cell
                else cell.copy(
                    equations = cell.equations + MathDocument.empty(),
                    activeEquationIndex = cell.equations.size,
                    systemMenuOpen = false,
                )
            }
            focusedId = id
            keyboardCollapsed = false
        }
    }

    fun ungroupSystem(id: String) {
        val cell = cells.firstOrNull { it.id == id && it.kind == CellKind.System } ?: return
        val spawned = cell.equations.mapIndexed { index, doc ->
            InputCell(
                position = cell.position + Offset(0f, index * 56f),
                kind = CellKind.Formula,
                document = doc,
            )
        }
        commitEdit {
            cells = cells.filterNot { it.id == id } + spawned
            focusedId = spawned.firstOrNull()?.id
            keyboardCollapsed = false
        }
    }

    fun setActiveEquation(id: String, index: Int) {
        cells = cells.map { cell ->
            if (cell.id != id || cell.kind != CellKind.System) cell
            else cell.copy(activeEquationIndex = index.coerceIn(0, (cell.equations.size - 1).coerceAtLeast(0)))
        }
        focusedId = id
        keyboardCollapsed = false
    }

    fun replaceText(id: String, text: String, asLatex: Boolean) {
        // LaTeX keeps its structure (fractions, roots); plain text stays a run.
        val doc = if (asLatex) LatexImporter.importOne(text) else MathDocument.fromPlain(text)
        commitEdit {
            cells = cells.map { cell ->
                if (cell.id != id) cell
                else if (cell.kind == CellKind.Graph) cell
                else if (cell.kind == CellKind.System) {
                    val eqs = cell.equations.toMutableList()
                    val idx = cell.activeEquationIndex.coerceIn(0, (eqs.size - 1).coerceAtLeast(0))
                    if (eqs.isEmpty()) eqs += doc
                    else eqs[idx] = doc
                    cell.copy(equations = eqs, resultExact = null, resultDecimal = null, showDecimal = false)
                } else {
                    cell.copy(
                        document = doc,
                        resultExact = null,
                        resultDecimal = null,
                        showDecimal = false,
                        errorStart = null,
                        errorEnd = null,
                        errorMessage = null,
                    )
                }
            }
            focusedId = id
            keyboardCollapsed = cells.firstOrNull { it.id == id }?.kind == CellKind.Graph
            dismissMenu()
            clearSelection()
        }
    }

    fun latexOf(id: String): String {
        val cell = cells.firstOrNull { it.id == id } ?: return ""
        return cell.toLatexCases()
    }

    fun toggleExactDecimal(id: String) {
        cells = cells.map { cell ->
            if (cell.id != id || !cell.canToggleExactDecimal) cell
            else cell.copy(showDecimal = !cell.showDecimal)
        }
    }

    /** Cells whose CAS request is still running; the UI shows a progress hint. */
    var solvingIds by mutableStateOf<Set<String>>(emptySet())
        private set

    /**
     * Solves the focused cell on [CasWorker] — never on the caller's thread — and
     * applies the answer only if the input is still what was submitted.
     */
    suspend fun submitFocused() {
        val id = focusedId ?: return
        val cell = cells.firstOrNull { it.id == id } ?: return
        if (cell.kind == CellKind.Graph) return
        val input = cell.text
        val request: () -> SolveOutcome = if (cell.kind == CellKind.System) {
            val filled = cell.equations.map { it.toLinear().trim() }.filter { it.isNotEmpty() }
            if (filled.size < 2) {
                updateError(id, "System needs at least 2 equations", 0, 0)
                return
            }
            ({ MathEngine.solveSystem(filled) })
        } else {
            val linear = cell.document.toLinear()
            ({ MathEngine.solve(linear) })
        }
        solvingIds = solvingIds + id
        try {
            val resolved = CasWorker.run {
                CrashGuard.run(Resolved.Error("Could not evaluate", 0, 0)) { resolve(request()) }
            }
            val current = cells.firstOrNull { it.id == id } ?: return
            if (current.text != input) return
            when (resolved) {
                is Resolved.Answer -> updateResult(id, resolved.exact, resolved.decimal)
                is Resolved.Error -> updateError(id, resolved.message, resolved.start, resolved.end)
            }
        } finally {
            solvingIds = solvingIds - id
        }
    }

    private sealed class Resolved {
        data class Answer(val exact: String, val decimal: String) : Resolved()
        data class Error(val message: String, val start: Int, val end: Int) : Resolved()
    }

    /** Runs on the CAS thread: decimal forms may need Symja too. */
    private fun resolve(outcome: SolveOutcome): Resolved = when (outcome) {
        is SolveOutcome.Value -> Resolved.Answer(
            exact = outcome.exact,
            decimal = outcome.decimal ?: SymjaEngine.toDecimal(outcome.exact) ?: outcome.exact,
        )
        is SolveOutcome.Solutions -> {
            // One solution per line; lines that cannot be numified keep their
            // exact form so the two columns always line up.
            val exact = outcome.exactLines.joinToString("\n")
            val decimal = outcome.decimalLines?.joinToString("\n")
                ?: outcome.exactLines.joinToString("\n") { line ->
                    val rhs = line.substringAfter('=', missingDelimiterValue = "").trim()
                    if (rhs.isBlank()) line
                    else SymjaEngine.toDecimal(rhs)?.let { "${line.substringBefore('=')}= $it" } ?: line
                }
            Resolved.Answer(exact, decimal)
        }
        is SolveOutcome.Info -> Resolved.Answer(outcome.message, outcome.message)
        is SolveOutcome.Error -> Resolved.Error(outcome.message, outcome.start, outcome.end)
    }

    /**
     * Called whenever focus leaves a cell: collapses half-built structures and
     * discards the cell entirely if the user never typed anything into it.
     */
    private fun settleFocused() {
        val id = focusedId ?: return
        collapseFocusedEmpty()
        val cell = cells.firstOrNull { it.id == id } ?: return
        if (!cell.isDiscardableEmpty()) return
        cells = cells.filterNot { it.id == id }
        focusedId = null
        if (menuCellId == id) menuCellId = null
    }

    private fun InputCell.isDiscardableEmpty(): Boolean {
        if (resultExact != null || errorMessage != null) return false
        return when (kind) {
            CellKind.Formula -> document.isBlank()
            CellKind.System -> equations.all { it.isBlank() }
            CellKind.Graph -> false
        }
    }

    private fun collapseFocusedEmpty() {
        val id = focusedId ?: return
        updateActiveDoc(id, clearResults = false, recordHistory = false) {
            MathEditor.collapseEmpty(it)
        }
    }

    private fun updateFocusedActive(transform: (MathDocument) -> MathDocument) {
        val id = focusedId ?: return
        val range = selection.takeIf { selectionCellId == id }
        clearSelection()
        updateActiveDoc(id) { doc ->
            transform(if (range != null) MathEditor.deleteRange(doc, range) else doc)
        }
    }

    private fun updateActiveDoc(
        id: String,
        clearResults: Boolean = true,
        recordHistory: Boolean = true,
        transform: (MathDocument) -> MathDocument,
    ) {
        val apply = {
            cells = cells.map { cell ->
                if (cell.id != id) cell
                else {
                    val next = when (cell.kind) {
                        CellKind.Formula -> cell.copy(
                            document = CrashGuard.run(cell.document) { transform(cell.document) },
                            resultExact = if (clearResults) null else cell.resultExact,
                            resultDecimal = if (clearResults) null else cell.resultDecimal,
                            showDecimal = if (clearResults) false else cell.showDecimal,
                            errorStart = null,
                            errorEnd = null,
                            errorMessage = null,
                        )
                        CellKind.System -> {
                            val eqs = cell.equations.toMutableList()
                            if (eqs.isEmpty()) eqs += MathDocument.empty()
                            val idx = cell.activeEquationIndex.coerceIn(0, eqs.lastIndex)
                            eqs[idx] = CrashGuard.run(eqs[idx]) { transform(eqs[idx]) }
                            cell.copy(
                                equations = eqs,
                                resultExact = if (clearResults) null else cell.resultExact,
                                resultDecimal = if (clearResults) null else cell.resultDecimal,
                                showDecimal = if (clearResults) false else cell.showDecimal,
                                errorStart = null,
                                errorEnd = null,
                                errorMessage = null,
                            )
                        }
                        CellKind.Graph -> cell
                    }
                    next
                }
            }
            focusedId = id
        }
        if (recordHistory) commitEdit(apply) else apply()
    }

    private fun commitEdit(block: () -> Unit) {
        val outer = historyDepth == 0
        val beforeCells = cells
        val beforeFocus = focusedId
        historyDepth++
        try {
            block()
        } finally {
            historyDepth--
        }
        if (!outer) return
        if (sameExpressionContent(beforeCells, cells)) return
        past = (past + EditSnapshot(beforeCells, beforeFocus)).takeLast(MAX_HISTORY)
        future = emptyList()
        canUndo = true
        canRedo = false
    }

    private fun applySnapshot(snap: EditSnapshot) {
        cells = snap.cells
        focusedId = snap.focusedId
        keyboardCollapsed = snap.focusedId != null &&
            snap.cells.firstOrNull { it.id == snap.focusedId }?.kind == CellKind.Graph
        menuCellId = null
        menuSlot = null
        draggingCellId = null
        dropTarget = null
        dismissAllSystemMenus()
        clearSelection()
    }

    private fun sameExpressionContent(a: List<InputCell>, b: List<InputCell>): Boolean {
        if (a.size != b.size) return false
        return a.zip(b).all { (x, y) ->
            x.id == y.id &&
                x.kind == y.kind &&
                x.document.root == y.document.root &&
                x.equations.map { it.root } == y.equations.map { it.root }
        }
    }

    private fun updateResult(id: String, exact: String, decimal: String?) {
        cells = cells.map { cell ->
            if (cell.id != id) cell
            else cell.copy(
                resultExact = exact,
                resultDecimal = decimal,
                showDecimal = false,
                errorStart = null,
                errorEnd = null,
                errorMessage = null,
            )
        }
    }

    private fun updateError(id: String, message: String, start: Int, end: Int) {
        cells = cells.map { cell ->
            if (cell.id != id) cell
            else {
                val len = cell.text.length
                cell.copy(
                    resultExact = null,
                    resultDecimal = null,
                    showDecimal = false,
                    errorMessage = message,
                    errorStart = start.coerceIn(0, len),
                    errorEnd = end.coerceIn(start.coerceIn(0, len), len),
                )
            }
        }
    }

    private fun clearCellError(id: String) {
        cells = cells.map { cell ->
            if (cell.id != id) cell
            else cell.copy(errorStart = null, errorEnd = null, errorMessage = null)
        }
    }

    private companion object {
        const val MIN_ZOOM = 0.35f
        const val MAX_ZOOM = 3.5f
        const val MIN_CELL_SCALE = 0.5f
        const val MAX_CELL_SCALE = 3f
        const val SPAWN_GAP = 24f
        const val MAX_HISTORY = 60
        val DEFAULT_CELL_SIZE = Size(180f, 64f)
    }
}

private data class EditSnapshot(
    val cells: List<InputCell>,
    val focusedId: String?,
)

private fun Offset.sanitized(): Offset = Offset(
    if (x.isFinite()) x else 0f,
    if (y.isFinite()) y else 0f,
)

private fun InputCell.sanitized(): InputCell {
    val scale = if (scale.isFinite() && scale > 0f) scale else 1f
    val position = position.sanitized()
    return if (scale == this.scale && position == this.position) this
    else copy(position = position, scale = scale)
}
