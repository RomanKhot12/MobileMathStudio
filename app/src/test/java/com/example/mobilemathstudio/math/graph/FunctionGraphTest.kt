package com.example.mobilemathstudio.math.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FunctionGraphTest {

    @Test
    fun samplesAnExplicitLine() {
        val series = FunctionGraph.sample(listOf("y=x"), steps = 40)
        assertEquals(1, series.size)
        val pts = series.first().points.filter { it.finite }
        assertTrue(pts.size > 10)
        assertTrue(pts.any { abs(it.x - it.y) < 0.05 })
    }

    @Test
    fun samplesAParabolaInTheVisibleWindow() {
        val series = FunctionGraph.sample(listOf("y=x^2-4"), xMin = -3.0, xMax = 3.0, steps = 60)
        val pts = series.single().points.filter { it.finite }
        val atZero = pts.minBy { abs(it.x) }
        assertTrue(abs(atZero.y + 4.0) < 0.2)
        val atTwo = pts.minBy { abs(it.x - 2.0) }
        assertTrue(abs(atTwo.y) < 0.25)
    }

    @Test
    fun samplesALinearPolynomialWithoutY() {
        val line = FunctionGraph.sample(listOf("2x+3"), steps = 20)
        val atZero = line.single().points.minBy { abs(it.x) }
        assertTrue(abs(atZero.y - 3.0) < 0.2)
    }

    @Test
    fun samplesASystemAsSeveralSeries() {
        val series = FunctionGraph.sample(listOf("y=x", "y=-x"), steps = 20)
        assertEquals(2, series.size)
        val a = series[0].points.first { it.finite && abs(it.x - 2.0) < 0.6 }
        val b = series[1].points.first { it.finite && abs(it.x - 2.0) < 0.6 }
        assertTrue(abs(a.y - a.x) < 0.2)
        assertTrue(abs(b.y + b.x) < 0.2)
    }

    @Test
    fun inclusiveIntervalGetsFilledEndpoints() {
        val series = FunctionGraph.sample(listOf("y=x, [0,2]"), xMin = -5.0, xMax = 5.0, steps = 80)
        val markers = series.single().markers
        assertTrue(markers.toString(), markers.any { abs(it.x) < 0.05 && it.style == GraphMarkerStyle.Filled })
        assertTrue(markers.toString(), markers.any { abs(it.x - 2.0) < 0.05 && it.style == GraphMarkerStyle.Filled })
        assertTrue(series.single().points.none { it.finite && it.x < -0.2 })
    }

    @Test
    fun exclusiveIntervalGetsHollowEndpoints() {
        val series = FunctionGraph.sample(listOf("y=x, (0,2)"), xMin = -5.0, xMax = 5.0, steps = 80)
        val markers = series.single().markers
        assertTrue(markers.toString(), markers.any { abs(it.x) < 0.05 && it.style == GraphMarkerStyle.Hollow })
        assertTrue(markers.toString(), markers.any { abs(it.x - 2.0) < 0.05 && it.style == GraphMarkerStyle.Hollow })
    }

    /** No chord may join two finite points that lie on opposite sides of [x]. */
    private fun bridges(points: List<GraphPoint>, x: Double): Boolean =
        points.zipWithNext().any { (a, b) -> a.finite && b.finite && a.x < x && b.x > x }

    @Test
    fun asymptoteOfReciprocalBreaksTheContourWithoutDots() {
        for (steps in listOf(120, 121, 360)) {
            val series = FunctionGraph.sample(listOf("1/x"), xMin = -4.0, xMax = 4.0, steps = steps)
            val pts = series.single().points
            assertTrue("steps=$steps", !bridges(pts, 0.0))
            assertTrue(series.single().markers.toString(), series.single().markers.isEmpty())
            assertTrue(pts.any { it.finite && it.x < 0 && it.y < -50 })
            assertTrue(pts.any { it.finite && it.x > 0 && it.y > 50 })
        }
    }

    @Test
    fun tangentBreaksAtEveryAsymptote() {
        val series = FunctionGraph.sample(listOf("y=tan(x)"), xMin = -5.0, xMax = 5.0, steps = 300)
        val pts = series.single().points
        for (k in listOf(-1, 0, 1)) {
            val pole = Math.PI / 2 + k * Math.PI
            assertTrue("pole $pole", !bridges(pts, pole))
        }
        assertTrue(series.single().markers.isEmpty())
    }

    @Test
    fun squareRootStartsWithAFilledDotAtTheOrigin() {
        val series = FunctionGraph.sample(listOf("y=√(x)"), xMin = -5.0, xMax = 5.0, steps = 77)
        val markers = series.single().markers
        assertTrue(markers.toString(), markers.any { abs(it.x) < 1e-6 && abs(it.y) < 1e-3 && it.style == GraphMarkerStyle.Filled })
        assertTrue(series.single().points.none { it.finite && it.x < -1e-9 })
        assertTrue(series.single().points.any { it.finite && it.x < 1e-4 })
    }

    @Test
    fun logarithmRunsToItsAsymptoteWithoutADot() {
        val series = FunctionGraph.sample(listOf("y=ln(x)"), xMin = -3.0, xMax = 3.0, steps = 90, yMin = -5.0, yMax = 5.0)
        assertTrue(series.single().markers.toString(), series.single().markers.isEmpty())
        assertTrue(series.single().points.any { it.finite && it.y < -6.0 })
    }

    @Test
    fun removableHoleGetsOneHollowDot() {
        val series = FunctionGraph.sample(listOf("y=sin(x)/x"), xMin = -6.0, xMax = 6.0, steps = 120)
        val markers = series.single().markers
        assertEquals(markers.toString(), 1, markers.size)
        assertEquals(GraphMarkerStyle.Hollow, markers.single().style)
        assertEquals(0.0, markers.single().x, 1e-6)
        assertEquals(1.0, markers.single().y, 1e-3)
    }

    @Test
    fun semicircleEndsAreFilled() {
        val series = FunctionGraph.sample(listOf("y=√(4-x^2)"), xMin = -5.0, xMax = 5.0, steps = 93)
        val markers = series.single().markers
        assertTrue(markers.toString(), markers.any { abs(it.x + 2) < 1e-6 && it.style == GraphMarkerStyle.Filled })
        assertTrue(markers.toString(), markers.any { abs(it.x - 2) < 1e-6 && it.style == GraphMarkerStyle.Filled })
    }

    @Test
    fun implicitCircleIsOneClosedContour() {
        val series = FunctionGraph.sample(listOf("x^2+y^2=4"), xMin = -5.0, xMax = 5.0, steps = 200)
        assertEquals(1, series.size)
        val pts = series.single().points
        assertTrue(pts.all { !it.finite || abs(it.x * it.x + it.y * it.y - 4.0) < 0.05 })
        val runs = pts.count { !it.finite } + 1
        assertEquals(1, runs)
        assertTrue(abs(pts.first().x - pts.last().x) < 1e-9 && abs(pts.first().y - pts.last().y) < 1e-9)
        assertTrue(pts.any { it.x > 1.95 } && pts.any { it.x < -1.95 })
    }

    @Test
    fun implicitHyperbolaDoesNotDrawAFakeLineAcrossThePole() {
        val series = FunctionGraph.sample(listOf("x*y=1"), xMin = -4.0, xMax = 4.0, steps = 160)
        val pts = series.single().points.filter { it.finite }
        assertTrue(pts.all { abs(it.x * it.y - 1.0) < 0.1 })
    }

    @Test
    fun seriesKeepTheirInputLineForColouring() {
        val series = FunctionGraph.sample(listOf("y=x\ny=-x", "x^2+y^2=1"), steps = 80)
        assertEquals(listOf(0, 1, 2), series.map { it.sourceIndex })
    }

    @Test
    fun viewportZoomInShrinksTheWindowAroundThePivot() {
        val start = GraphViewport(-10.0, 10.0, -10.0, 10.0)
        val zoomed = start.zoom(2.0, 0.0, 0.0)
        assertTrue(zoomed.xSpan < start.xSpan)
        assertTrue(abs(zoomed.xMin + zoomed.xMax) < 1e-6)
    }

    @Test
    fun membershipIntervalIsParsed() {
        val (body, domain) = GraphDomainParser.split("y=x^2, x∈[1,4]")
        assertEquals("y=x^2", body)
        assertEquals(1.0, domain!!.xMin!!, 0.0)
        assertEquals(4.0, domain.xMax!!, 0.0)
        assertTrue(domain.minInclusive)
        assertTrue(domain.maxInclusive)
    }

    @Test
    fun pairedConstantsRenderAsASingleFilledPoint() {
        val series = FunctionGraph.sample(listOf("x = 2", "y = 3"), xMin = -10.0, xMax = 10.0, steps = 40)
        assertEquals(series.toString(), 1, series.size)
        val pts = series.single().points.filter { it.finite }
        assertEquals(1, pts.size)
        assertEquals(2.0, pts.single().x, 1e-9)
        assertEquals(3.0, pts.single().y, 1e-9)
        assertEquals(GraphMarkerStyle.Filled, series.single().markers.single().style)
        assertTrue(series.none { item -> item.points.count { it.finite } >= 2 })
    }

    @Test
    fun commaPairAndParenPairArePoints() {
        for (src in listOf("x=2, y=3", "y=3, x=2", "(2, 3)", "(2;3)")) {
            val series = FunctionGraph.sample(listOf(src), xMin = -10.0, xMax = 10.0, steps = 20)
            val pts = series.single().points.filter { it.finite }
            assertEquals(src, 1, pts.size)
            assertEquals(src, 2.0, pts.single().x, 1e-9)
            assertEquals(src, 3.0, pts.single().y, 1e-9)
            assertEquals(src, GraphMarkerStyle.Filled, series.single().markers.single().style)
        }
    }

    @Test
    fun loneYEqualsThreeIsStillAHorizontalLine() {
        val series = FunctionGraph.sample(listOf("y=3"), xMin = -10.0, xMax = 10.0, steps = 40)
        val pts = series.single().points.filter { it.finite }
        assertTrue(pts.size >= 2)
        assertTrue(pts.any { abs(it.x + 10.0) < 0.2 })
        assertTrue(pts.any { abs(it.x - 10.0) < 0.2 })
        assertTrue(pts.all { abs(it.y - 3.0) < 1e-6 })
        assertTrue(series.single().markers.isEmpty())
    }

    @Test
    fun restrictedLinearFunctionIsASegmentWithEndpoints() {
        val series = FunctionGraph.sample(listOf("y=2x, x∈[1,5]"), xMin = -10.0, xMax = 10.0, steps = 80)
        val pts = series.single().points.filter { it.finite }
        assertTrue(pts.isNotEmpty())
        assertTrue(pts.none { it.x < 0.8 || it.x > 5.2 })
        assertTrue(pts.any { abs(it.x - 1.0) < 0.15 && abs(it.y - 2.0) < 0.3 })
        assertTrue(pts.any { abs(it.x - 5.0) < 0.15 && abs(it.y - 10.0) < 0.3 })
        val markers = series.single().markers
        assertTrue(markers.toString(), markers.any { abs(it.x - 1.0) < 0.05 && it.style == GraphMarkerStyle.Filled })
        assertTrue(markers.toString(), markers.any { abs(it.x - 5.0) < 0.05 && it.style == GraphMarkerStyle.Filled })
    }

    @Test
    fun inequalityDomainAlsoClipsTheSegment() {
        val series = FunctionGraph.sample(listOf("y=2x, 1≤x≤5"), xMin = -8.0, xMax = 8.0, steps = 80)
        assertTrue(series.single().points.none { it.finite && (it.x < 0.8 || it.x > 5.2) })
    }

    @Test
    fun unrestrictedFunctionStillSpansTheVisibleWindow() {
        val series = FunctionGraph.sample(listOf("y=3x-1"), xMin = -10.0, xMax = 10.0, steps = 40)
        val pts = series.single().points.filter { it.finite }
        assertTrue(pts.any { it.x < -8.0 })
        assertTrue(pts.any { it.x > 8.0 })
    }

    @Test
    fun systemWithStrictInequalityClipsRayAndDrawsHollowEndpoint() {
        val series = FunctionGraph.sample(
            listOf("x = y\nx > 3"),
            xMin = -10.0,
            xMax = 10.0,
            steps = 120,
            yMin = -10.0,
            yMax = 10.0,
        )
        assertEquals("System must produce only the clipped curve, not a separate half-plane", 1, series.size)
        val trace = series.single()
        val pts = trace.points.filter { it.finite }
        assertTrue(pts.isNotEmpty())
        assertTrue("No point may lie at x <= 3", pts.none { it.x <= 3.0 })
        assertTrue("Ray must continue to the right of x = 3", pts.any { it.x > 9.0 && abs(it.y - it.x) < 0.05 })
        assertTrue("Clipped curve must not shade a half-plane", trace.fillPolygons.isEmpty())
        assertEquals(1, trace.markers.size)
        val m = trace.markers.single()
        assertEquals(3.0, m.x, 1e-4)
        assertEquals(3.0, m.y, 1e-4)
        assertEquals(GraphMarkerStyle.Hollow, m.style)
    }

    @Test
    fun systemWithNonStrictInequalityClipsRayAndDrawsFilledEndpoint() {
        val series = FunctionGraph.sample(
            listOf("x = y\nx ≥ 3"),
            xMin = -10.0,
            xMax = 10.0,
            steps = 120,
            yMin = -10.0,
            yMax = 10.0,
        )
        assertEquals(1, series.size)
        val trace = series.single()
        val pts = trace.points.filter { it.finite }
        assertTrue(pts.none { it.x < 3.0 - 1e-6 })
        assertEquals(1, trace.markers.size)
        val m = trace.markers.single()
        assertEquals(3.0, m.x, 1e-4)
        assertEquals(3.0, m.y, 1e-4)
        assertEquals(GraphMarkerStyle.Filled, m.style)
    }

    @Test
    fun systemWithChainedInequalityClipsParabolaWithMixedEndpoints() {
        val series = FunctionGraph.sample(
            listOf("y = x^2\n-2 ≤ x < 4"),
            xMin = -10.0,
            xMax = 10.0,
            steps = 120,
            yMin = -2.0,
            yMax = 20.0,
        )
        assertEquals(1, series.size)
        val trace = series.single()
        val pts = trace.points.filter { it.finite }
        assertTrue(pts.none { it.x < -2.0 - 1e-6 || it.x >= 4.0 })
        assertTrue(trace.markers.any { abs(it.x + 2.0) < 1e-3 && abs(it.y - 4.0) < 1e-2 && it.style == GraphMarkerStyle.Filled })
        assertTrue(trace.markers.any { abs(it.x - 4.0) < 1e-3 && abs(it.y - 16.0) < 1e-1 && it.style == GraphMarkerStyle.Hollow })
    }

    @Test
    fun systemWithYBoundClipsCurveAndPlacesExactBoundaryMarkers() {
        val series = FunctionGraph.sample(
            listOf("y = x^2\ny ≤ 4"),
            xMin = -10.0,
            xMax = 10.0,
            steps = 160,
            yMin = -2.0,
            yMax = 10.0,
        )
        assertEquals(1, series.size)
        val trace = series.single()
        val pts = trace.points.filter { it.finite }
        assertTrue(pts.isNotEmpty())
        assertTrue("All points must satisfy y <= 4", pts.all { it.y <= 4.0 + 1e-4 })
        assertTrue(trace.markers.any { abs(it.x + 2.0) < 1e-2 && abs(it.y - 4.0) < 1e-2 && it.style == GraphMarkerStyle.Filled })
        assertTrue(trace.markers.any { abs(it.x - 2.0) < 1e-2 && abs(it.y - 4.0) < 1e-2 && it.style == GraphMarkerStyle.Filled })
    }

    @Test
    fun systemWithXOfYAndStrictYBoundClipsAndMarksHollowEndpoint() {
        val series = FunctionGraph.sample(
            listOf("x = y^2\ny > -1"),
            xMin = -5.0,
            xMax = 10.0,
            steps = 120,
            yMin = -5.0,
            yMax = 5.0,
        )
        assertEquals(1, series.size)
        val trace = series.single()
        val pts = trace.points.filter { it.finite }
        assertTrue(pts.isNotEmpty())
        assertTrue("No point may have y <= -1", pts.none { it.y <= -1.0 })
        assertTrue(trace.markers.any { abs(it.x - 1.0) < 1e-2 && abs(it.y + 1.0) < 1e-2 && it.style == GraphMarkerStyle.Hollow })
    }

    @Test
    fun systemInequalityClipDoesNotLeakIntoSeparateTraces() {
        val series = FunctionGraph.sample(
            listOf("x = y\nx > 3", "y = -x"),
            xMin = -10.0,
            xMax = 10.0,
            steps = 80,
        )
        assertEquals(2, series.size)
        assertTrue("First trace clipped to x > 3", series[0].points.filter { it.finite }.none { it.x <= 3.0 })
        val secondPts = series[1].points.filter { it.finite }
        assertTrue("Second trace must remain unclipped on left", secondPts.any { it.x < -8.0 })
        assertTrue("Second trace must remain unclipped on right", secondPts.any { it.x > 8.0 })
    }

    @Test
    fun standaloneInequalitiesShadeHalfPlaneWithDashedOrSolidBoundary() {
        val strict = FunctionGraph.sample(listOf("y > x + 1"), xMin = -10.0, xMax = 10.0, steps = 80)
        assertEquals(1, strict.size)
        assertTrue("Strict inequality boundary must be dashed", strict.single().dashed)
        assertTrue("Strict inequality must shade half-plane", strict.single().fillPolygons.isNotEmpty())

        val nonStrict = FunctionGraph.sample(listOf("x ≤ 2"), xMin = -10.0, xMax = 10.0, steps = 80)
        assertEquals(1, nonStrict.size)
        assertTrue("Non-strict inequality boundary must be solid", !nonStrict.single().dashed)
        assertTrue("Non-strict inequality must shade half-plane", nonStrict.single().fillPolygons.isNotEmpty())
    }
}
