package com.example.mobilemathstudio.export

import androidx.compose.ui.graphics.Color
import com.example.mobilemathstudio.ui.canvas.CellKind
import com.example.mobilemathstudio.ui.canvas.InputCell
import com.example.mobilemathstudio.ui.graph.GraphSeriesPalette
import com.example.mobilemathstudio.ui.graph.GraphTrace

/**
 * Resolves a graph cell's linked formula/system IDs into renderable [GraphTrace]s
 * using the same colour order and document list as the live canvas.
 */
fun resolveExportGraphTraces(
    graph: InputCell,
    cellsById: Map<String, InputCell>,
): List<GraphTrace> {
    if (graph.kind != CellKind.Graph) return emptyList()
    val ids = graph.graph?.linkedCellIds.orEmpty()
    val resolved = ArrayList<GraphTrace>(ids.size)
    for (id in ids) {
        val src = cellsById[id] ?: continue
        val labels = src.plotExpressions()
        if (labels.isEmpty()) continue
        val index = resolved.size
        resolved += GraphTrace(
            cellId = id,
            label = labels.joinToString("\n"),
            color = GraphSeriesPalette[index % GraphSeriesPalette.size],
            documents = when (src.kind) {
                CellKind.Formula -> listOf(src.document)
                CellKind.System -> src.equations
                CellKind.Graph -> emptyList()
            },
        )
    }
    return resolved
}

/**
 * Expands multi-line traces (such as a linked system of equations) into parallel
 * expression and colour lists matching `GraphBoard`.
 */
fun expandGraphSeries(traces: List<GraphTrace>): Pair<List<String>, List<Color>> {
    val labels = ArrayList<String>(traces.size)
    val colors = ArrayList<Color>(traces.size)
    for (trace in traces) {
        for (raw in trace.label.split('\n')) {
            val trimmed = raw.trim()
            if (trimmed.isNotEmpty()) {
                labels += trimmed
                colors += trace.color
            }
        }
    }
    return labels to colors
}
