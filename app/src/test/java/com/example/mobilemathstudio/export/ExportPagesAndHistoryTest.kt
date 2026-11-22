package com.example.mobilemathstudio.export

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Page management and the undo stack of the export composer. */
class ExportPagesAndHistoryTest {

    private fun item(id: String, page: Int) = PageItem(
        cellId = id,
        page = page,
        offset = Offset(10f, 20f),
        scale = 1f,
    )

    private fun layout(pages: Int) = ExportLayout(
        pageCount = pages,
        items = (0 until pages).map { item("cell-$it", it) },
    )

    @Test
    fun deletingPagesRenumbersTheSurvivorsAndDropsTheirContent() {
        val edited = ExportEditor.deletePages(layout(4), setOf(1, 2))

        assertEquals(2, edited.pageCount)
        assertEquals(listOf("cell-0", "cell-3"), edited.items.map { it.cellId })
        assertEquals(listOf(0, 1), edited.items.map { it.page })
    }

    @Test
    fun keepingSelectedPagesRemovesEverythingElse() {
        val edited = ExportEditor.keepPages(layout(5), setOf(3))

        assertEquals(1, edited.pageCount)
        assertEquals("cell-3", edited.items.single().cellId)
        assertEquals(0, edited.items.single().page)
    }

    @Test
    fun theDocumentNeverDropsBelowOnePage() {
        val edited = ExportEditor.deletePages(layout(3), setOf(0, 1, 2))

        assertEquals(1, edited.pageCount)
        assertTrue(edited.items.isEmpty())
    }

    @Test
    fun emptySelectionsAreNoOps() {
        val start = layout(3)
        assertEquals(start, ExportEditor.deletePages(start, emptySet()))
        assertEquals(start, ExportEditor.keepPages(start, emptySet()))
    }

    @Test
    fun addingAPageLeavesExistingContentAlone() {
        val start = layout(2)
        val edited = ExportEditor.addPage(start)

        assertEquals(3, edited.pageCount)
        assertEquals(start.items, edited.items)
    }

    @Test
    fun undoAndRedoWalkThroughDiscreteEdits() {
        val start = layout(2)
        val history = ExportHistory(start)
        assertFalse(history.canUndo)

        history.edit { ExportEditor.addPage(it) }
        history.edit { ExportEditor.deleteBlock(it, "cell-0") }
        assertEquals(3, history.state.pageCount)
        assertEquals(1, history.state.items.size)

        history.undo()
        assertEquals(2, history.state.items.size)
        history.undo()
        assertEquals(start, history.state)
        assertFalse(history.canUndo)

        history.redo()
        assertEquals(3, history.state.pageCount)
        history.redo()
        assertEquals(1, history.state.items.size)
        assertFalse(history.canRedo)
    }

    @Test
    fun aGestureCostsExactlyOneUndoStep() {
        val history = ExportHistory(layout(1))
        val before = history.state

        repeat(20) {
            history.apply(ExportEditor.move(history.state, "cell-0", Offset(1f, 1f)))
        }
        history.record(before)

        assertTrue(history.canUndo)
        history.undo()
        assertEquals(before, history.state)
        assertFalse(history.canUndo)
    }

    @Test
    fun resetReturnsToTheAutomaticLayoutAndIsItselfUndoable() {
        val start = layout(2)
        val history = ExportHistory(start)
        history.edit { ExportEditor.deletePages(it, setOf(0)) }
        assertTrue(history.canReset)

        history.reset()
        assertEquals(start, history.state)
        assertFalse(history.canReset)

        history.undo()
        assertEquals(1, history.state.pageCount)
    }

    @Test
    fun aFreshEditDropsTheRedoBranch() {
        val history = ExportHistory(layout(2))
        history.edit { ExportEditor.addPage(it) }
        history.undo()
        assertTrue(history.canRedo)

        history.edit { ExportEditor.deleteBlock(it, "cell-1") }
        assertFalse(history.canRedo)
    }

    @Test
    fun rebasingForgetsEverything() {
        val history = ExportHistory(layout(2))
        history.edit { ExportEditor.addPage(it) }
        history.undo()

        val fresh = layout(4)
        history.rebase(fresh)

        assertEquals(fresh, history.state)
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertFalse(history.canReset)
    }
}
