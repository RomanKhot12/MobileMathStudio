package com.example.mobilemathstudio.export

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.example.mobilemathstudio.math.expr.MathDocument
import com.example.mobilemathstudio.ui.canvas.CellKind
import com.example.mobilemathstudio.ui.canvas.InputCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Placement in the PDF composer is block-level — a system and its answer are
 * laid out, moved and scaled as one — while single rows or the answer line can
 * still be removed on their own.
 */
class ExportAtomicityTest {

    private val size = Size(240f, 140f)

    private fun systemCell(id: String, y: Float) = InputCell(
        id = id,
        position = Offset(0f, y),
        kind = CellKind.System,
        equations = listOf(
            MathDocument.fromPlain("x+y=5"),
            MathDocument.fromPlain("x-y=1"),
        ),
        resultExact = "x = 3\ny = 2",
    )

    private fun layoutOf(cells: List<InputCell>) =
        CanvasExportLayout.auto(cells, { size }, allowMultiplePages = true)

    @Test
    fun aSystemWithItsAnswerBecomesExactlyOneItem() {
        val layout = layoutOf(listOf(systemCell("sys", 0f)))
        assertEquals(1, layout.items.size)
        assertEquals("sys", layout.items.single().cellId)
    }

    @Test
    fun everyCellKeepsExactlyOneItemEvenWhenPagesOverflow() {
        val cells = (0 until 40).map { systemCell("sys-$it", it * 400f) }
        val layout = layoutOf(cells)

        assertTrue("expected an overflow into several pages", layout.pageCount > 1)
        assertEquals(cells.size, layout.items.size)
        assertEquals(cells.size, layout.items.distinctBy { it.cellId }.size)
        cells.forEach { cell ->
            assertEquals(1, layout.items.count { it.cellId == cell.id })
        }
    }

    @Test
    fun deletingABlockRemovesItWholeAndTouchesNothingElse() {
        val layout = layoutOf(listOf(systemCell("sys", 0f), systemCell("other", 400f)))
        val remaining = ExportEditor.deleteBlock(layout, "sys")

        assertEquals(1, remaining.items.size)
        assertEquals("other", remaining.items.single().cellId)
    }

    @Test
    fun deletingWithoutASelectionChangesNothing() {
        val layout = layoutOf(listOf(systemCell("sys", 0f)))
        assertEquals(layout, ExportEditor.deleteBlock(layout, null))
    }

    @Test
    fun theAnswerLineCanBeRemovedWhileTheSystemStays() {
        val cell = systemCell("sys", 0f)
        val layout = layoutOf(listOf(cell))
        val edited = ExportEditor.deletePart(layout, cell, BlockPart.Result)

        val item = edited.items.single()
        assertEquals(setOf<BlockPart>(BlockPart.Result), item.hiddenParts)
        assertEquals(
            listOf(BlockPart.Equation(0), BlockPart.Equation(1)),
            ExportEditor.visiblePartsOf(cell, edited),
        )
    }

    @Test
    fun oneEquationRowCanBeRemovedWithoutTouchingTheOthers() {
        val cell = systemCell("sys", 0f)
        val layout = layoutOf(listOf(cell))
        val edited = ExportEditor.deletePart(layout, cell, BlockPart.Equation(0))

        assertEquals(
            listOf(BlockPart.Equation(1), BlockPart.Result),
            ExportEditor.visiblePartsOf(cell, edited),
        )
        // Removing a piece must not move or rescale what remains.
        assertEquals(layout.items.single().offset, edited.items.single().offset)
        assertEquals(layout.items.single().scale, edited.items.single().scale, 0f)
    }

    @Test
    fun removingEveryPieceDropsTheBlockEntirely() {
        val cell = systemCell("sys", 0f)
        var layout = layoutOf(listOf(cell))
        blockPartsOf(cell).forEach { layout = ExportEditor.deletePart(layout, cell, it) }

        assertTrue(layout.items.isEmpty())
    }

    @Test
    fun movingAndScalingApplyToTheWholeBlockOnly() {
        val layout = layoutOf(listOf(systemCell("sys", 0f), systemCell("other", 400f)))
        val moved = ExportEditor.move(layout, "sys", Offset(30f, 40f))
        val scaled = ExportEditor.scale(moved, "sys", 1.5f)

        val target = scaled.items.single { it.cellId == "sys" }
        val untouched = scaled.items.single { it.cellId == "other" }
        val before = layout.items.single { it.cellId == "sys" }
        val otherBefore = layout.items.single { it.cellId == "other" }

        assertEquals(before.offset + Offset(30f, 40f), target.offset)
        assertEquals(before.scale * 1.5f, target.scale, 0.0001f)
        assertEquals(otherBefore, untouched)
    }

    @Test
    fun scaleStaysWithinReadableBounds() {
        var layout = layoutOf(listOf(systemCell("sys", 0f)))
        repeat(20) { layout = ExportEditor.scale(layout, "sys", 2f) }
        assertTrue(layout.items.single().scale <= 3f)
        repeat(40) { layout = ExportEditor.scale(layout, "sys", 0.5f) }
        assertTrue(layout.items.single().scale >= 0.15f)
    }

    @Test
    fun draggingPastThePageEdgeMovesTheBlockToTheNextPage() {
        val layout = layoutOf(listOf(systemCell("sys", 0f))).copy(pageCount = 2)
        val pushed = ExportEditor.move(layout, "sys", Offset(0f, CanvasExportLayout.PAGE_HEIGHT))
        val settled = ExportEditor.settle(pushed, "sys").items.single()

        assertEquals(1, settled.page)
        assertTrue(settled.offset.y in 0f..CanvasExportLayout.PAGE_HEIGHT)
    }

    @Test
    fun settlingNeverLeavesABlockOutsideThePage() {
        val layout = layoutOf(listOf(systemCell("sys", 0f)))
        val pushed = ExportEditor.move(layout, "sys", Offset(-9000f, -9000f))
        val settled = ExportEditor.settle(pushed, "sys").items.single()

        assertEquals(0, settled.page)
        assertTrue(settled.offset.x >= 0f)
        assertTrue(settled.offset.y >= 0f)
    }

    @Test
    fun aPlainFormulaWithoutAnAnswerHasASinglePiece() {
        val cell = InputCell(
            id = "f",
            position = Offset.Zero,
            document = MathDocument.fromPlain("2+2"),
        )
        assertEquals(listOf(BlockPart.Body), blockPartsOf(cell))
        assertFalse(blockPartsOf(cell).contains(BlockPart.Result))
    }
}
