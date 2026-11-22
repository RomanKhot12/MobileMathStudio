package com.example.mobilemathstudio.export

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.example.mobilemathstudio.ui.canvas.InputCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasExportLayoutTest {

    private val cellSize = Size(300f, 120f)
    private fun sizeOf(@Suppress("UNUSED_PARAMETER") id: String) = cellSize

    private fun column(count: Int, gap: Float) = List(count) { index ->
        InputCell(position = Offset(0f, index * gap))
    }

    @Test
    fun emptyCanvasStillHasOnePage() {
        val layout = CanvasExportLayout.auto(emptyList(), ::sizeOf, allowMultiplePages = true)
        assertEquals(1, layout.pageCount)
        assertTrue(layout.items.isEmpty())
    }

    @Test
    fun aFewFormulasFitOnASinglePage() {
        val layout = CanvasExportLayout.auto(column(3, 200f), ::sizeOf, allowMultiplePages = true)
        assertEquals(1, layout.pageCount)
        assertEquals(3, layout.items.size)
        assertTrue(layout.items.all { it.page == 0 })
    }

    @Test
    fun overflowingCanvasSpillsOntoFurtherPages() {
        val layout = CanvasExportLayout.auto(column(40, 400f), ::sizeOf, allowMultiplePages = true)
        assertTrue("pages=${layout.pageCount}", layout.pageCount > 1)
        assertEquals(40, layout.items.size)
        assertTrue(layout.items.any { it.page == 1 })
    }

    @Test
    fun everyItemStaysInsideItsPage() {
        val layout = CanvasExportLayout.auto(column(40, 400f), ::sizeOf, allowMultiplePages = true)
        layout.items.forEach { item ->
            assertTrue("$item", item.offset.y >= 0f)
            assertTrue("$item", item.offset.y < CanvasExportLayout.PAGE_HEIGHT)
            assertTrue("$item", item.offset.x >= 0f)
        }
    }

    @Test
    fun imageExportNeverSplitsPages() {
        val layout = CanvasExportLayout.auto(column(40, 400f), ::sizeOf, allowMultiplePages = false)
        assertEquals(1, layout.pageCount)
        assertTrue(layout.items.all { it.page == 0 })
    }

    @Test
    fun pagesAreNumberedWithoutGaps() {
        val layout = CanvasExportLayout.auto(column(40, 400f), ::sizeOf, allowMultiplePages = true)
        val used = layout.items.map { it.page }.distinct().sorted()
        assertEquals((0 until layout.pageCount).toList(), used)
    }
}
