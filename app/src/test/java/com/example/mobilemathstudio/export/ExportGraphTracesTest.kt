package com.example.mobilemathstudio.export

import androidx.compose.ui.geometry.Offset
import com.example.mobilemathstudio.math.expr.MathDocument
import com.example.mobilemathstudio.math.graph.FunctionGraph
import com.example.mobilemathstudio.math.graph.GraphViewport
import com.example.mobilemathstudio.ui.canvas.CellKind
import com.example.mobilemathstudio.ui.canvas.GraphState
import com.example.mobilemathstudio.ui.canvas.InputCell
import com.example.mobilemathstudio.ui.graph.GraphSeriesPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportGraphTracesTest {

    @Test
    fun linkedFormulasAndSystemsResolveToExpressionsNeverUuids() {
        val f1 = InputCell(
            id = "9063dc95-4f5b-4ec0-a3a1-a509394fbbaa",
            position = Offset.Zero,
            document = MathDocument.fromPlain("y=x^2"),
        )
        val sys = InputCell(
            id = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
            position = Offset(0f, 120f),
            kind = CellKind.System,
            equations = listOf(
                MathDocument.fromPlain("y=2x+1"),
                MathDocument.fromPlain("x=1"),
            ),
        )
        val graph = InputCell(
            id = "graph-1",
            position = Offset(200f, 0f),
            kind = CellKind.Graph,
            graph = GraphState(
                linkedCellIds = listOf(f1.id, sys.id),
                viewport = GraphViewport(-5.0, 5.0, -5.0, 5.0),
            ),
        )
        val byId = listOf(f1, sys, graph).associateBy { it.id }

        val traces = resolveExportGraphTraces(graph, byId)
        assertEquals(2, traces.size)
        assertEquals("y=x^2", traces[0].label)
        assertEquals(GraphSeriesPalette[0], traces[0].color)
        assertEquals(1, traces[0].documents.size)
        assertFalse(traces[0].label.contains("9063dc95"))

        assertEquals("y=2x+1\nx=1", traces[1].label)
        assertEquals(GraphSeriesPalette[1], traces[1].color)
        assertEquals(2, traces[1].documents.size)

        val (labels, colors) = expandGraphSeries(traces)
        assertEquals(listOf("y=x^2", "y=2x+1", "x=1"), labels)
        assertEquals(
            listOf(GraphSeriesPalette[0], GraphSeriesPalette[1], GraphSeriesPalette[1]),
            colors,
        )

        val sampled = FunctionGraph.sampleIn(labels, graph.graph!!.viewport, steps = 120)
        assertEquals(3, sampled.size)
        assertTrue(sampled.all { s -> s.points.any { it.finite } })
    }

    @Test
    fun missingOrBlankLinkedCellsAreSkippedCleanly() {
        val blank = InputCell(id = "blank", position = Offset.Zero, document = MathDocument.empty())
        val valid = InputCell(id = "ok", position = Offset.Zero, document = MathDocument.fromPlain("sin(x)"))
        val graph = InputCell(
            id = "g",
            position = Offset.Zero,
            kind = CellKind.Graph,
            graph = GraphState(linkedCellIds = listOf("missing-id", blank.id, valid.id)),
        )
        val traces = resolveExportGraphTraces(graph, listOf(blank, valid, graph).associateBy { it.id })
        assertEquals(1, traces.size)
        assertEquals("sin(x)", traces.single().label)
        assertEquals(GraphSeriesPalette[0], traces.single().color)
    }

    @Test
    fun nonGraphCellProducesNoTraces() {
        val f = InputCell(id = "f", position = Offset.Zero, document = MathDocument.fromPlain("x+1"))
        assertTrue(resolveExportGraphTraces(f, mapOf(f.id to f)).isEmpty())
    }
}
