package com.example.mobilemathstudio.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Targeted copy / paste inside composite blocks and the corner-snap insertion
 * that drops a whole formula into an empty child slot.
 */
class SlotOperationsTest {

    private fun canvasWith(latex: String): Pair<TypeCanvasState, String> {
        val state = TypeCanvasState()
        state.createCellAtScreen(Offset(100f, 100f))
        val id = state.focusedId!!
        state.replaceText(id, latex, asLatex = true)
        return state to id
    }

    private fun system(): Pair<TypeCanvasState, String> = runBlocking {
        val state = TypeCanvasState()
        state.createOrWrapSystem()
        val id = state.cells.single { it.kind == CellKind.System }.id
        state.setActiveEquation(id, 0)
        state.insert("x+y=5")
        state to id
    }

    @Test
    fun copiesOneHalfOfAFractionInsteadOfTheWholeBlock() {
        val (state, id) = canvasWith("\\frac{1+2}{7}")
        val numerator = SlotRef(id, SlotRef.NO_EQUATION, listOf(0, 0))
        val denominator = SlotRef(id, SlotRef.NO_EQUATION, listOf(0, 1))

        assertEquals("1+2", state.slotText(numerator))
        assertEquals("7", state.slotText(denominator))
        assertEquals("7", state.slotLatex(denominator))
    }

    @Test
    fun pastingIntoASlotLeavesTheRestOfTheBlockIntact() {
        val (state, id) = canvasWith("\\frac{1}{2}")
        val pasted = state.pasteIntoSlot(SlotRef(id, SlotRef.NO_EQUATION, listOf(0, 0)), "\\sqrt{9}")

        assertTrue(pasted)
        assertEquals("(√(9))/(2)", state.cells.single().document.toLinear())
    }

    @Test
    fun copiesAndPastesASingleSystemRow() {
        val (state, id) = system()
        val firstRow = SlotRef(id, 0)
        val secondRow = SlotRef(id, 1)

        assertEquals("x+y=5", state.slotText(firstRow))
        assertTrue(state.pasteIntoSlot(secondRow, "x-y=1"))

        val cell = state.cells.single()
        assertEquals("x+y=5", cell.equations[0].toLinear())
        assertEquals("x-y=1", cell.equations[1].toLinear())
    }

    @Test
    fun cornerSnapInsertsTheDraggedFormulaAndRemovesIt() {
        val (state, hostId) = canvasWith("\\frac{1}{\\square}")
        state.createCellAtScreen(Offset(400f, 400f))
        val dragged = state.focusedId!!
        state.replaceText(dragged, "42", asLatex = false)

        state.beginDrag(dragged)
        state.armDropTarget(SlotRef(hostId, SlotRef.NO_EQUATION, listOf(0, 1)))
        val inserted = state.endDrag()

        assertTrue(inserted)
        assertEquals(1, state.cells.size)
        assertEquals("(1)/(42)", state.cells.single().document.toLinear())
    }

    @Test
    fun movingAwayFromTheSlotCancelsTheInsertion() {
        val (state, hostId) = canvasWith("\\frac{1}{\\square}")
        state.createCellAtScreen(Offset(400f, 400f))
        val dragged = state.focusedId!!
        state.replaceText(dragged, "42", asLatex = false)

        state.beginDrag(dragged)
        state.armDropTarget(SlotRef(hostId, SlotRef.NO_EQUATION, listOf(0, 1)))
        // Finger slides off the slot before lifting.
        state.armDropTarget(null)
        val inserted = state.endDrag()

        assertFalse(inserted)
        assertNull(state.dropTarget)
        assertEquals(2, state.cells.size)
        assertEquals("(1)/()", state.cells.first { it.id == hostId }.document.toLinear())
    }

    @Test
    fun filledSlotsAndSelfDropsAreRejected() {
        val (state, hostId) = canvasWith("\\frac{1}{2}")
        state.createCellAtScreen(Offset(400f, 400f))
        val dragged = state.focusedId!!
        state.replaceText(dragged, "42", asLatex = false)

        val filled = SlotRef(hostId, SlotRef.NO_EQUATION, listOf(0, 1))
        assertFalse(state.canDropInto(dragged, filled))
        assertFalse(state.canDropInto(dragged, SlotRef(dragged, SlotRef.NO_EQUATION, emptyList())))
    }

    @Test
    fun aSystemIsNeverDraggedIntoASlot() = runBlocking {
        val (state, hostId) = canvasWith("\\frac{1}{\\square}")
        state.createOrWrapSystem()
        val systemId = state.cells.single { it.kind == CellKind.System }.id

        assertFalse(
            state.canDropInto(systemId, SlotRef(hostId, SlotRef.NO_EQUATION, listOf(0, 1))),
        )
    }

    @Test
    fun droppingIntoAnEmptySystemRowFillsThatRowOnly() = runBlocking {
        val state = TypeCanvasState()
        state.createOrWrapSystem()
        val systemId = state.cells.single { it.kind == CellKind.System }.id
        state.setActiveEquation(systemId, 0)
        state.insert("x+y=5")

        state.createCellAtScreen(Offset(600f, 600f))
        val dragged = state.focusedId!!
        state.replaceText(dragged, "x-y=1", asLatex = false)

        state.beginDrag(dragged)
        state.armDropTarget(SlotRef(systemId, 1, listOf(0)))
        assertTrue(state.endDrag())

        val cell = state.cells.single()
        assertEquals("x+y=5", cell.equations[0].toLinear())
        assertEquals("x-y=1", cell.equations[1].toLinear())
    }

    @Test
    fun grabCornerSitsAtTheBottomLeftOfTheFormula() {
        val state = TypeCanvasState()
        state.viewport = Size(1000f, 2000f)
        val cell = InputCell(position = Offset(120f, 240f), scale = 2f)
        state.restore(listOf(cell), Offset.Zero)
        state.reportCellSize(cell.id, Size(200f, 60f))

        val corner = state.grabCorner(cell.id)!!
        assertEquals(120f, corner.x, 0.01f)
        assertEquals(240f + 60f * 2f, corner.y, 0.01f)
    }

    @Test
    fun menuSlotIsClearedWithTheMenu() {
        val (state, id) = canvasWith("\\frac{1}{2}")
        val slot = SlotRef(id, SlotRef.NO_EQUATION, listOf(0, 0))

        state.showMenu(id, slot)
        assertEquals(slot, state.menuSlot)

        state.dismissMenu()
        assertNull(state.menuSlot)
    }
}
