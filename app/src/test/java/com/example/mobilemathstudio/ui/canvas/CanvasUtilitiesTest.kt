package com.example.mobilemathstudio.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Copy / paste / delete / layer switching / zoom behaviour of the canvas. */
class CanvasUtilitiesTest {

    private fun stateWithCell(): Pair<TypeCanvasState, String> {
        val state = TypeCanvasState()
        state.createCellAtScreen(Offset(100f, 100f))
        return state to state.focusedId!!
    }

    @Test
    fun copyProducesStructuredLatex() {
        val (state, id) = stateWithCell()
        state.replaceText(id, "\\frac{1}{2}", asLatex = true)
        assertEquals("\\frac{1}{2}", state.latexOf(id))
    }

    @Test
    fun pastingLatexKeepsStructureNotRawText() {
        val (state, id) = stateWithCell()
        state.replaceText(id, "\\sqrt{55}", asLatex = true)
        assertEquals("√(55)", state.cells.first { it.id == id }.document.toLinear())
    }

    @Test
    fun pastingPlainTextIsNotParsedAsLatex() {
        val (state, id) = stateWithCell()
        state.replaceText(id, "1+2", asLatex = false)
        assertEquals("1+2", state.cells.first { it.id == id }.document.toLinear())
    }

    @Test
    fun deleteRemovesOnlyTheTargetCell() {
        val state = TypeCanvasState()
        state.createCellAtScreen(Offset(10f, 10f))
        val first = state.focusedId!!
        state.insert("1", 0)
        state.createCellAtScreen(Offset(10f, 200f))
        val second = state.focusedId!!
        state.insert("2", 0)

        state.deleteCell(first)

        assertEquals(1, state.cells.size)
        assertEquals(second, state.cells.single().id)
    }

    @Test
    fun systemLayerSwitchingTargetsTheRightEquation() = runBlocking {
        val state = TypeCanvasState()
        state.createOrWrapSystem()
        val system = state.cells.single { it.kind == CellKind.System }
        state.setActiveEquation(system.id, 1)
        state.insert("7", 0)

        val updated = state.cells.single { it.kind == CellKind.System }
        assertEquals(1, updated.activeEquationIndex)
        assertEquals("", updated.equations[0].toLinear())
        assertEquals("7", updated.equations[1].toLinear())
    }

    @Test
    fun formulaScaleIsClamped() {
        val (state, id) = stateWithCell()
        repeat(20) { state.scaleCellBy(id, 2f) }
        assertTrue(state.cells.single().scale <= 3f)
        repeat(40) { state.scaleCellBy(id, 0.5f) }
        assertTrue(state.cells.single().scale >= 0.5f)
    }

    @Test
    fun canvasZoomKeepsThePivotAnchored() {
        val state = TypeCanvasState()
        val pivot = Offset(500f, 800f)
        val worldBefore = state.screenToWorld(pivot)
        state.zoomAround(pivot, 1.8f)
        val worldAfter = state.screenToWorld(pivot)
        assertEquals(worldBefore.x, worldAfter.x, 0.01f)
        assertEquals(worldBefore.y, worldAfter.y, 0.01f)
        assertEquals(1.8f, state.zoom, 0.001f)
    }

    @Test
    fun canvasZoomIsClamped() {
        val state = TypeCanvasState()
        repeat(20) { state.zoomAround(Offset.Zero, 2f) }
        assertTrue(state.zoom <= 3.5f)
    }

    @Test
    fun smartSpawnAvoidsAnOccupiedCentre() = runBlocking {
        val state = TypeCanvasState()
        state.viewport = Size(1000f, 2000f)
        val occupant = InputCell(position = Offset(400f, 950f))
        state.restore(listOf(occupant), Offset.Zero)
        state.reportCellSize(occupant.id, Size(220f, 100f))

        state.createCellCentered()

        val spawned = state.cells.last()
        val occupied = Rect(occupant.position, Size(220f, 100f))
        val fresh = Rect(spawned.position, Size(180f, 64f))
        assertFalse("spawned on top of an existing formula", occupied.overlaps(fresh))
    }

    @Test
    fun latexImportCreatesASystemForCases() = runBlocking {
        val state = TypeCanvasState()
        state.importLatex("\\begin{cases} x+y=5 \\\\ x-y=1 \\end{cases}")
        val cell = state.cells.single()
        assertEquals(CellKind.System, cell.kind)
        assertEquals(2, cell.equations.size)
    }

    @Test
    fun latexImportCreatesAPlainFormulaOtherwise() = runBlocking {
        val state = TypeCanvasState()
        state.importLatex("\\frac{1}{2}")
        val cell = state.cells.single()
        assertEquals(CellKind.Formula, cell.kind)
        assertEquals("(1)/(2)", cell.document.toLinear())
    }

    /** End-to-end path of the reported bug: typed rows → Symja → result row. */
    @Test
    fun systemCellSolvesIndependentVariablesAndOffersDecimals() = runBlocking {
        val state = TypeCanvasState()
        state.createOrWrapSystem()
        val id = state.cells.single { it.kind == CellKind.System }.id
        state.setActiveEquation(id, 0)
        state.insert("55-66=9x")
        state.setActiveEquation(id, 1)
        state.insert("55-66=9y")

        state.submitFocused()

        val cell = state.cells.single()
        assertNull(cell.errorMessage)
        assertEquals("x = -11/9\ny = -11/9", cell.resultExact)
        assertTrue(cell.canToggleExactDecimal)
        assertEquals(2, cell.resultDecimal!!.lines().size)
        assertTrue(cell.resultDecimal!!, cell.resultDecimal!!.contains("-1.2222"))
    }

    @Test
    fun menuStateIsExclusiveAndDismissable() {
        val (state, id) = stateWithCell()
        state.insert("1", 0)
        state.showMenu(id)
        assertNotNull(state.menuCellId)
        state.dismissMenu()
        assertNull(state.menuCellId)
    }
}
