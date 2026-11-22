package com.example.mobilemathstudio.ui.canvas

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TypeCanvasStateTest {

    @Test
    fun untouchedCellDisappearsWhenAnotherIsCreated() {
        val state = TypeCanvasState()
        state.createCellAtScreen(Offset(10f, 10f))
        assertEquals(1, state.cells.size)

        state.createCellAtScreen(Offset(200f, 300f))

        assertEquals(1, state.cells.size)
        assertEquals(Offset(200f, 300f), state.cells.single().position)
    }

    @Test
    fun typedCellSurvivesFocusChange() {
        val state = TypeCanvasState()
        state.createCellAtScreen(Offset(10f, 10f))
        state.insert("2")

        state.createCellAtScreen(Offset(200f, 300f))

        assertEquals(2, state.cells.size)
    }

    @Test
    fun untouchedCellDisappearsWhenFocusIsCleared() {
        val state = TypeCanvasState()
        state.createCellAtScreen(Offset(10f, 10f))

        state.clearFocus()

        assertTrue(state.cells.isEmpty())
        assertNull(state.focusedId)
    }

    @Test
    fun focusingAnotherCellDropsTheEmptyOne() {
        val state = TypeCanvasState()
        state.createCellAtScreen(Offset(10f, 10f))
        state.insert("x")
        val filledId = state.cells.single().id
        state.createCellAtScreen(Offset(200f, 300f))

        state.focus(filledId)

        assertEquals(1, state.cells.size)
        assertEquals(filledId, state.focusedId)
    }

    @Test
    fun emptyCellIsNeverPersisted() {
        val state = TypeCanvasState()
        state.createCellAtScreen(Offset(10f, 10f))

        assertEquals(1, state.cells.size)
        assertTrue(state.snapshotCells().isEmpty())
    }

    @Test
    fun nanPanAndZoomNeverPropagate() {
        val state = TypeCanvasState()
        state.panBy(Offset(Float.NaN, 10f))
        assertEquals(Offset.Zero, state.pan)

        state.panBy(Offset(8f, 4f))
        assertEquals(Offset(8f, 4f), state.pan)

        state.zoomAround(Offset(10f, 10f), Float.NaN)
        assertEquals(1f, state.zoom, 0.0001f)

        state.moveCellBy("missing", Offset(Float.NaN, 0f))
        state.createCellAtScreen(Offset(10f, 10f))
        state.insert("1")
        val id = state.cells.single().id
        val before = state.cells.single().position
        state.moveCellBy(id, Offset(Float.NaN, 5f))
        assertEquals(before, state.cells.single().position)
    }

    @Test
    fun restoreDropsNonFiniteGeometry() {
        val state = TypeCanvasState()
        val broken = InputCell(
            position = Offset(Float.NaN, Float.POSITIVE_INFINITY),
            scale = Float.NaN,
        )
        state.restore(listOf(broken), Offset(Float.NaN, 3f), zoom = Float.NaN)
        val cell = state.cells.single()
        assertEquals(Offset.Zero, cell.position)
        assertEquals(1f, cell.scale, 0.0001f)
        assertEquals(Offset(0f, 3f), state.pan)
        assertEquals(1f, state.zoom, 0.0001f)
    }

    @Test
    fun insertThenUndoAndRedoRestoreTheExpression() {
        val state = TypeCanvasState()
        state.createCellAtScreen(Offset(10f, 10f))
        state.insert("12")

        assertTrue(state.canUndo)
        assertFalse(state.canRedo)
        assertEquals("12", state.cells.single().document.toLinear())

        state.undo()
        assertTrue(state.cells.single().document.isBlank())
        assertFalse(state.canUndo)
        assertTrue(state.canRedo)

        state.redo()
        assertEquals("12", state.cells.single().document.toLinear())
        assertTrue(state.canUndo)
        assertFalse(state.canRedo)
    }

    @Test
    fun caretMovesDoNotCreateUndoSteps() {
        val state = TypeCanvasState()
        state.createCellAtScreen(Offset(10f, 10f))
        state.insert("12")
        state.moveCursor(-1)
        state.placeCaret(listOf(0), 0)

        state.undo()
        assertTrue(state.cells.single().document.isBlank())
        assertFalse(state.canUndo)
    }

    @Test
    fun clearCanvasWipesEveryFormulaAndUndoRestoresThem() {
        val state = TypeCanvasState()
        state.createCellAtScreen(Offset(10f, 10f))
        state.insert("1")
        val first = state.cells.single().id
        state.createCellAtScreen(Offset(200f, 300f))
        state.insert("2")

        assertEquals(2, state.cells.size)
        state.clearCanvas()
        assertTrue(state.cells.isEmpty())
        assertNull(state.focusedId)
        assertTrue(state.canUndo)

        state.undo()
        assertEquals(2, state.cells.size)
        assertEquals("1", state.cells.first { it.id == first }.document.toLinear())
        assertEquals("2", state.cells.first { it.id != first }.document.toLinear())
    }

    @Test
    fun clearAllWipesOnlyTheFocusedFormula() {
        val state = TypeCanvasState()
        state.createCellAtScreen(Offset(10f, 10f))
        state.insert("1")
        val first = state.cells.single().id
        state.createCellAtScreen(Offset(200f, 300f))
        state.insert("2")

        state.clearAll()

        assertEquals(2, state.cells.size)
        assertEquals("1", state.cells.first { it.id == first }.document.toLinear())
        assertTrue(state.cells.first { it.id != first }.document.isBlank())
        assertEquals(state.cells.first { it.id != first }.id, state.focusedId)
    }

    @Test
    fun restoreClearsTheUndoStack() {
        val state = TypeCanvasState()
        state.createCellAtScreen(Offset(10f, 10f))
        state.insert("9")
        assertTrue(state.canUndo)

        state.restore(emptyList(), Offset.Zero)
        assertFalse(state.canUndo)
        assertFalse(state.canRedo)
        assertTrue(state.cells.isEmpty())
    }

    @Test
    fun longPressOnAnEmptyTemplateSlotDeletesTheWholeTemplate() {
        val state = TypeCanvasState()
        state.createCellAtScreen(Offset(10f, 10f))
        state.insertFraction()
        state.insert("1")
        state.confirmNestedSlot()
        val id = state.focusedId!!
        val emptyDen = SlotRef(id, SlotRef.NO_EQUATION, listOf(0, 1))
        val filledNum = SlotRef(id, SlotRef.NO_EQUATION, listOf(0, 0))

        assertEquals("(1)/()", state.cells.single().document.toLinear())
        assertFalse(state.deleteTemplateAtEmptySlot(filledNum))
        assertEquals("(1)/()", state.cells.single().document.toLinear())

        assertTrue(state.deleteTemplateAtEmptySlot(emptyDen))
        assertTrue(state.cells.single().document.isBlank())
    }

    @Test
    fun longPressOnARootHoleDoesNotInventATemplateDelete() {
        val state = TypeCanvasState()
        state.createCellAtScreen(Offset(10f, 10f))
        val id = state.focusedId!!
        val rootHole = SlotRef(id, SlotRef.NO_EQUATION, listOf(0))

        assertFalse(state.deleteTemplateAtEmptySlot(rootHole))
        assertEquals(1, state.cells.size)
    }

    @Test
    fun insertingThenDeletingATemplateAfterANumberLeavesOneEditableRun() {
        val state = TypeCanvasState()
        state.createCellAtScreen(Offset(10f, 10f))
        state.insert("5")
        state.insertFraction()
        state.backspace()

        val doc = state.cells.single().document
        assertEquals("5", doc.toLinear())
        assertEquals(1, doc.root.children.size)
        assertTrue(doc.root.children.single() is com.example.mobilemathstudio.math.expr.MathNode.Text)
        assertEquals(1, doc.caret.offset)

        state.backspace()
        assertTrue(state.cells.single().document.isBlank())
    }

    @Test
    fun longPressSelectionKeepsFocusAndBackspaceDeletesTheRange() {
        val state = TypeCanvasState()
        state.createCellAtScreen(Offset(10f, 10f))
        state.insert("abcdef")
        val id = state.focusedId!!

        assertTrue(state.beginSelectionAt(id, SlotRef(id, SlotRef.NO_EQUATION, listOf(0))))
        assertNotNull(state.selection)
        assertEquals(id, state.focusedId)
        assertTrue(state.isKeyboardVisible)
        assertEquals("abcdef", state.selectionText())

        state.backspace()
        assertTrue(state.cells.single().document.isBlank())
        assertNull(state.selection)
        assertEquals(id, state.focusedId)
    }

    @Test
    fun collapsingTheKeyboardDoesNotClearFocus() {
        val state = TypeCanvasState()
        state.createCellAtScreen(Offset(10f, 10f))
        state.insert("1")
        assertTrue(state.isKeyboardVisible)

        state.collapseKeyboard()
        assertTrue(state.hasFocus)
        assertFalse(state.isKeyboardVisible)

        state.expandKeyboard()
        assertTrue(state.isKeyboardVisible)
        assertEquals("1", state.cells.single().document.toLinear())
    }

    @Test
    fun pastingSlashLatexBecomesAStructuredFraction() {
        val state = TypeCanvasState()
        state.createCellAtScreen(Offset(10f, 10f))
        val id = state.focusedId!!
        state.replaceText(id, "\$ (x-y)/(x+y) \$", asLatex = true)
        val root = state.cells.single().document.root.children.single()
        assertTrue(root is com.example.mobilemathstudio.math.expr.MathNode.Fraction)
        assertFalse(state.cells.single().document.toLinear().contains("$"))
        assertEquals("(x-y)/(x+y)", state.cells.single().document.toLinear().replace(" ", ""))
        assertTrue(state.isKeyboardVisible)
    }

    @Test
    fun draggingASelectionHandleDoesNotHideTheKeyboard() {
        val state = TypeCanvasState()
        state.createCellAtScreen(Offset(10f, 10f))
        state.insert("abcdef")
        val id = state.focusedId!!
        assertTrue(state.beginSelectionAt(id, SlotRef(id, SlotRef.NO_EQUATION, listOf(0))))
        assertTrue(state.isKeyboardVisible)
        state.moveSelectionEdge(startHandle = false, deltaChars = -2)
        assertTrue(state.isKeyboardVisible)
        assertEquals(id, state.focusedId)
        assertFalse(state.keyboardCollapsed)
        assertEquals("abcd", state.selectionText())
    }

    @Test
    fun deletingAGraphUnlinksFormulasAndDropsTheWidget() {
        val state = TypeCanvasState()
        state.createCellAtScreen(Offset(10f, 10f))
        state.insert("y=x")
        val formulaId = state.cells.single().id
        state.createGraphFrom(formulaId)
        val graphId = state.cells.first { it.kind == CellKind.Graph }.id
        assertTrue(formulaId in state.graphLinkedIds())
        assertEquals(2, state.cells.size)

        state.deleteCell(graphId)

        assertTrue(state.cells.none { it.kind == CellKind.Graph })
        assertEquals(listOf(formulaId), state.cells.map { it.id })
        assertTrue(state.graphLinkedIds().isEmpty())
    }

    @Test
    fun droppingAFormulaOnAGraphLinksItWithoutDeletingTheFormula() {
        val state = TypeCanvasState()
        state.createCellAtScreen(Offset(10f, 10f))
        state.insert("y=x")
        val first = state.cells.single().id
        state.createGraphFrom(first)
        val graphId = state.cells.first { it.kind == CellKind.Graph }.id

        state.createCellAtScreen(Offset(200f, 10f))
        state.insert("y=2x")
        val second = state.focusedId!!

        assertTrue(state.canLinkToGraph(second, graphId))
        state.beginDrag(second)
        state.armGraphDrop(graphId)
        assertTrue(state.endDrag())

        assertEquals(listOf(first, second), state.cells.first { it.id == graphId }.graph?.linkedCellIds)
        assertEquals(3, state.cells.size)
        assertTrue(state.cells.any { it.id == second && it.kind == CellKind.Formula })
    }

    @Test
    fun focusingAGraphHidesTheKeyboardAndReturningToAFormulaOrSystemReopensIt() {
        val state = TypeCanvasState()
        state.createCellAtScreen(Offset(10f, 10f))
        state.insert("y=x^2")
        val formulaId = state.cells.single().id
        assertTrue(state.isKeyboardVisible)

        state.createGraphFrom(formulaId)
        val graphId = state.cells.first { it.kind == CellKind.Graph }.id
        assertFalse(state.isKeyboardVisible)

        state.focus(formulaId)
        assertTrue(state.isKeyboardVisible)

        state.focus(graphId)
        assertFalse(state.isKeyboardVisible)

        val sys = InputCell(
            id = "sys-1",
            position = Offset(200f, 200f),
            kind = CellKind.System,
            equations = listOf(
                com.example.mobilemathstudio.math.expr.MathDocument.fromPlain("x+y=2"),
                com.example.mobilemathstudio.math.expr.MathDocument.fromPlain("x-y=0"),
            ),
        )
        state.restore(state.cells + sys, Offset.Zero)
        state.focus(graphId)
        assertFalse(state.isKeyboardVisible)

        state.setActiveEquation("sys-1", 1)
        assertTrue(state.isKeyboardVisible)
    }

    @Test
    fun creatingANewEmptyCellAfterFocusingAGraphAutoExpandsKeyboard() {
        val state = TypeCanvasState()
        state.createCellAtScreen(Offset(10f, 10f))
        state.insert("y=sin(x)")
        val formulaId = state.cells.single().id
        assertTrue(state.isKeyboardVisible)

        // Focus a graph -> keyboard collapses.
        state.createGraphFrom(formulaId)
        val graphId = state.cells.first { it.kind == CellKind.Graph }.id
        assertFalse(state.isKeyboardVisible)
        assertTrue(state.keyboardCollapsed)

        // Tapping empty canvas spawns a brand-new empty cell -> keyboard MUST expand automatically.
        state.createCellAtScreen(Offset(320f, 420f))
        assertFalse(state.keyboardCollapsed)
        assertTrue(state.isKeyboardVisible)

        // Re-focusing the graph collapses again, then centered empty creation must also expand.
        state.focus(graphId)
        assertFalse(state.isKeyboardVisible)
        kotlinx.coroutines.runBlocking {
            state.createCellCentered()
        }
        assertFalse(state.keyboardCollapsed)
        assertTrue(state.isKeyboardVisible)

        // Even after manual collapse, spawning a new empty cell auto-expands the keyboard.
        state.collapseKeyboard()
        assertFalse(state.isKeyboardVisible)
        state.createCellAtScreen(Offset(120f, 520f))
        assertFalse(state.keyboardCollapsed)
        assertTrue(state.isKeyboardVisible)
    }
}
