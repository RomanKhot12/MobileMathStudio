package com.example.mobilemathstudio.export

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.example.mobilemathstudio.ui.canvas.CellKind
import com.example.mobilemathstudio.ui.canvas.InputCell

/** A separately removable piece of a block. */
sealed interface BlockPart {
    /** The expression of a plain formula. */
    data object Body : BlockPart

    /** One row of a system of equations. */
    data class Equation(val index: Int) : BlockPart

    /** The answer line printed under the block. */
    data object Result : BlockPart
}

/** One formula placed on a page, in page-space units (see [CanvasExportLayout.PAGE_WIDTH]). */
data class PageItem(
    val cellId: String,
    val page: Int,
    val offset: Offset,
    val scale: Float,
    /** Pieces the user removed from this block; the rest stays intact. */
    val hiddenParts: Set<BlockPart> = emptySet(),
)

/** Full editable state of the composer: how many pages, and what sits on them. */
data class ExportLayout(
    val pageCount: Int,
    val items: List<PageItem>,
) {
    fun itemsOn(page: Int): List<PageItem> = items.filter { it.page == page }

    fun itemFor(cellId: String?): PageItem? = items.firstOrNull { it.cellId == cellId }
}

/** Pieces a block is made of, in the order they are printed. */
fun blockPartsOf(cell: InputCell): List<BlockPart> = buildList {
    when (cell.kind) {
        CellKind.Formula -> add(BlockPart.Body)
        CellKind.System -> cell.equations.indices.forEach { add(BlockPart.Equation(it)) }
        CellKind.Graph -> add(BlockPart.Body)
    }
    if (!cell.resultText.isNullOrBlank()) add(BlockPart.Result)
}

/**
 * Fits the canvas onto printable pages.
 *
 * Everything goes on one page while it stays legible; once the required
 * shrink passes [MIN_READABLE_SCALE] the formulas reflow onto further pages
 * instead of turning into unreadable dust.
 *
 * Each cell yields exactly one [PageItem], so a block is never split across
 * pages by the layout itself. Removing single pieces of a block afterwards is
 * the editor's job (see [PageItem.hiddenParts]).
 */
object CanvasExportLayout {
    /** A4 portrait, in arbitrary page units. */
    const val PAGE_WIDTH = 1000f
    const val PAGE_HEIGHT = PAGE_WIDTH * 297f / 210f

    private const val MARGIN = 0.06f * PAGE_WIDTH
    private const val GAP = 0.02f * PAGE_WIDTH
    private const val MIN_READABLE_SCALE = 0.55f
    private const val MAX_SCALE = 1.6f

    fun auto(
        cells: List<InputCell>,
        sizeOf: (String) -> Size,
        allowMultiplePages: Boolean,
    ): ExportLayout {
        if (cells.isEmpty()) return ExportLayout(1, emptyList())

        val boxes = cells.associate { cell ->
            val size = sizeOf(cell.id)
            cell.id to Size(size.width * cell.scale, size.height * cell.scale)
        }
        val bounds = cells.fold(null as Rect?) { acc, cell ->
            val rect = Rect(cell.position, boxes.getValue(cell.id))
            acc?.expandToInclude(rect) ?: rect
        } ?: return ExportLayout(1, emptyList())

        val usableW = PAGE_WIDTH - 2 * MARGIN
        val usableH = PAGE_HEIGHT - 2 * MARGIN
        val fit = minOf(
            usableW / bounds.width.coerceAtLeast(1f),
            usableH / bounds.height.coerceAtLeast(1f),
        )

        if (fit >= MIN_READABLE_SCALE || !allowMultiplePages) {
            val scale = fit.coerceIn(0.08f, MAX_SCALE)
            val items = cells.map { cell ->
                PageItem(
                    cellId = cell.id,
                    page = 0,
                    offset = Offset(
                        MARGIN + (cell.position.x - bounds.left) * scale,
                        MARGIN + (cell.position.y - bounds.top) * scale,
                    ),
                    scale = scale * cell.scale,
                )
            }
            return ExportLayout(1, items)
        }

        // Overflow: reading-order reflow, one column per page.
        val scale = MIN_READABLE_SCALE
        val ordered = cells.sortedWith(compareBy({ it.position.y }, { it.position.x }))
        val items = mutableListOf<PageItem>()
        var page = 0
        var cursorY = MARGIN
        for (cell in ordered) {
            val size = boxes.getValue(cell.id)
            val height = size.height * scale
            if (cursorY > MARGIN && cursorY + height > PAGE_HEIGHT - MARGIN) {
                page++
                cursorY = MARGIN
            }
            items += PageItem(
                cellId = cell.id,
                page = page,
                offset = Offset(MARGIN, cursorY),
                scale = scale * cell.scale,
            )
            cursorY += height + GAP
        }
        return ExportLayout(page + 1, items)
    }

    private fun Rect.expandToInclude(other: Rect) = Rect(
        left = minOf(left, other.left),
        top = minOf(top, other.top),
        right = maxOf(right, other.right),
        bottom = maxOf(bottom, other.bottom),
    )
}
