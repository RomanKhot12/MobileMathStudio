package com.example.mobilemathstudio.export

import androidx.compose.ui.geometry.Offset
import com.example.mobilemathstudio.ui.canvas.InputCell

/**
 * Editing rules of the export composer.
 *
 * Placement stays block-level — a system and its answer move and scale as one
 * piece — while [deletePart] lets the user drop individual rows or the answer
 * line without disturbing the rest of the block.
 */
object ExportEditor {
    private const val MIN_SCALE = 0.15f
    private const val MAX_SCALE = 3f

    /** Margin kept between a formula and the page edge, in page units. */
    private const val EDGE = 40f

    fun move(layout: ExportLayout, cellId: String?, delta: Offset): ExportLayout =
        mapBlock(layout, cellId) { it.copy(offset = it.offset + delta) }

    fun scale(layout: ExportLayout, cellId: String?, factor: Float): ExportLayout =
        mapBlock(layout, cellId) {
            it.copy(scale = (it.scale * factor).coerceIn(MIN_SCALE, MAX_SCALE))
        }

    /** Dragging past a page edge hands the whole block to the neighbouring page. */
    fun settle(layout: ExportLayout, cellId: String?): ExportLayout =
        mapBlock(layout, cellId) { item ->
            var page = item.page
            var y = item.offset.y
            while (y < -EDGE && page > 0) {
                page--
                y += CanvasExportLayout.PAGE_HEIGHT
            }
            while (y > CanvasExportLayout.PAGE_HEIGHT - EDGE && page < layout.pageCount - 1) {
                page++
                y -= CanvasExportLayout.PAGE_HEIGHT
            }
            item.copy(
                page = page.coerceIn(0, (layout.pageCount - 1).coerceAtLeast(0)),
                offset = Offset(
                    item.offset.x.coerceIn(0f, CanvasExportLayout.PAGE_WIDTH - 60f),
                    y.coerceIn(0f, CanvasExportLayout.PAGE_HEIGHT - EDGE),
                ),
            )
        }

    fun deleteBlock(layout: ExportLayout, cellId: String?): ExportLayout {
        if (cellId == null) return layout
        return layout.copy(items = layout.items.filterNot { it.cellId == cellId })
    }

    /**
     * Removes one piece of a block — an equation row or the answer line — and
     * drops the block entirely once nothing visible is left.
     */
    fun deletePart(layout: ExportLayout, cell: InputCell, part: BlockPart): ExportLayout {
        val item = layout.itemFor(cell.id) ?: return layout
        if (part in item.hiddenParts) return layout
        val hidden = item.hiddenParts + part
        if (blockPartsOf(cell).all { it in hidden }) return deleteBlock(layout, cell.id)
        return mapBlock(layout, cell.id) { it.copy(hiddenParts = hidden) }
    }

    fun visiblePartsOf(cell: InputCell, layout: ExportLayout): List<BlockPart> {
        val hidden = layout.itemFor(cell.id)?.hiddenParts ?: emptySet()
        return blockPartsOf(cell).filterNot { it in hidden }
    }

    fun addPage(layout: ExportLayout): ExportLayout = layout.copy(pageCount = layout.pageCount + 1)

    /** Drops the given pages and closes the gap; at least one page always remains. */
    fun deletePages(layout: ExportLayout, pages: Set<Int>): ExportLayout {
        if (pages.isEmpty()) return layout
        val kept = (0 until layout.pageCount).filterNot { it in pages }
        if (kept.isEmpty()) return ExportLayout(pageCount = 1, items = emptyList())
        val renumbered = kept.withIndex().associate { (fresh, old) -> old to fresh }
        return ExportLayout(
            pageCount = kept.size,
            items = layout.items.mapNotNull { item ->
                renumbered[item.page]?.let { item.copy(page = it) }
            },
        )
    }

    fun keepPages(layout: ExportLayout, pages: Set<Int>): ExportLayout {
        if (pages.isEmpty()) return layout
        return deletePages(layout, (0 until layout.pageCount).toSet() - pages)
    }

    private fun mapBlock(
        layout: ExportLayout,
        cellId: String?,
        transform: (PageItem) -> PageItem,
    ): ExportLayout {
        if (cellId == null) return layout
        return layout.copy(
            items = layout.items.map { if (it.cellId == cellId) transform(it) else it },
        )
    }
}
