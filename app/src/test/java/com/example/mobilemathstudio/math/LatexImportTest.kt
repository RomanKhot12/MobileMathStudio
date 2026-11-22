package com.example.mobilemathstudio.math

import com.example.mobilemathstudio.math.expr.MathNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatexImportTest {

    @Test
    fun fractionBecomesAStructuredNode() {
        val doc = LatexImporter.importOne("\\frac{1}{2}")
        val node = doc.root.children.single()
        assertTrue("$node", node is MathNode.Fraction)
        assertEquals("(1)/(2)", doc.toLinear())
    }

    @Test
    fun squareRootKeepsAllDigits() {
        val doc = LatexImporter.importOne("\\sqrt{55}")
        assertEquals("√(55)", doc.toLinear())
        assertEquals("√(55)", (MathEngine.solve(doc.toLinear()) as SolveOutcome.Value).exact)
    }

    @Test
    fun nthRootUsesTheIndex() {
        val doc = LatexImporter.importOne("\\sqrt[3]{27}")
        val node = doc.root.children.single()
        assertTrue("$node", node is MathNode.Root && node.index != null)
        assertEquals("3", (MathEngine.solve(doc.toLinear()) as SolveOutcome.Value).exact)
    }

    @Test
    fun powerRaisesOnlyTheLastToken() {
        val doc = LatexImporter.importOne("2x^{2}+1")
        assertEquals("2x^(2)+1", doc.toLinear())
    }

    @Test
    fun symbolsAndFunctionsAreTranslated() {
        assertEquals("π×2", LatexImporter.importOne("\\pi \\times 2").toLinear().replace(" ", ""))
        assertTrue(LatexImporter.importOne("\\sin(x)").toLinear().contains("sin"))
    }

    @Test
    fun casesBlockBecomesASystem() {
        val imported = LatexImporter.import("\\begin{cases} x+y=5 \\\\ x-y=1 \\end{cases}")
        assertTrue(imported.isSystem)
        assertEquals(2, imported.documents.size)
        assertEquals("x+y=5", imported.documents[0].toLinear().replace(" ", ""))
    }

    @Test
    fun dollarsAndLeftRightAreStripped() {
        assertEquals("(x+1)", LatexImporter.importOne("$\\left(x+1\\right)$").toLinear())
    }

    @Test
    fun slashParenFormBecomesAStructuredFraction() {
        val doc = LatexImporter.importOne("(x-y)/(x+y)")
        val node = doc.root.children.single()
        assertTrue("$node", node is MathNode.Fraction)
        val frac = node as MathNode.Fraction
        assertEquals("x-y", com.example.mobilemathstudio.math.expr.linearize(frac.num).replace(" ", ""))
        assertEquals("x+y", com.example.mobilemathstudio.math.expr.linearize(frac.den).replace(" ", ""))
        assertEquals("\\frac{x-y}{x+y}", LatexCodec.toLatex(doc.root).replace(" ", ""))
    }

    @Test
    fun wrappedDollarsAndSlashBecomeAFraction() {
        val doc = LatexImporter.importOne("\$ (x-y)/(x+y) \$")
        assertTrue(doc.root.children.single() is MathNode.Fraction)
        assertFalse(doc.toLinear().contains("$"))
        assertEquals("(x-y)/(x+y)", doc.toLinear().replace(" ", ""))
    }

    @Test
    fun bareSlashAndOperatorGlyphsAreNormalized() {
        val frac = LatexImporter.importOne("a/b")
        assertTrue(frac.root.children.single() is MathNode.Fraction)
        assertEquals("(a)/(b)", frac.toLinear().replace(" ", ""))

        assertEquals("a·b", LatexImporter.importOne("a*b").toLinear().replace(" ", ""))
        assertEquals("a÷b", LatexImporter.importOne("a:b").toLinear().replace(" ", ""))
        assertEquals("a:=b", LatexImporter.importOne("a:=b").toLinear().replace(" ", ""))
        assertTrue(LatexImporter.importOne("\\((x-y)/(x+y)\\)").root.children.single() is MathNode.Fraction)
    }

    @Test
    fun garbageDoesNotThrow() {
        listOf("", "\\", "{{{", "\\frac{1}", "\\sqrt[", "}}}").forEach { raw ->
            LatexImporter.import(raw)
        }
    }

    @Test
    fun exportProducesRealLatexForStructures() {
        val doc = LatexImporter.importOne("\\frac{1}{2}")
        assertEquals("\\frac{1}{2}", LatexCodec.toLatex(doc.root))
    }

    @Test
    fun exportImportRoundTrip() {
        val original = LatexImporter.importOne("\\frac{\\sqrt{55}}{x^{2}}")
        val latex = LatexCodec.toLatex(original.root)
        val reimported = LatexImporter.importOne(latex)
        assertEquals(original.toLinear(), reimported.toLinear())
    }

    @Test
    fun inequalityCommandsAndRawTextAreImported() {
        assertEquals("x≥2", LatexImporter.importOne("x \\ge 2").toLinear().replace(" ", ""))
        assertEquals("x≤2", LatexImporter.importOne("x \\le 2").toLinear().replace(" ", ""))
        assertEquals("x>1", LatexImporter.importOne("x \\gt 1").toLinear().replace(" ", ""))
        assertEquals("x<1", LatexImporter.importOne("x \\lt 1").toLinear().replace(" ", ""))
        assertEquals("x≥2", LatexImporter.importOne("x >= 2").toLinear().replace(" ", ""))
        assertEquals("x≤2", LatexImporter.importOne("x <= 2").toLinear().replace(" ", ""))
        assertEquals("x>2", LatexImporter.importOne("x > 2").toLinear().replace(" ", ""))
        assertEquals("x<2", LatexImporter.importOne("x < 2").toLinear().replace(" ", ""))
        assertEquals("x≥2", LatexImporter.importOne("x \\geq 2").toLinear().replace(" ", ""))
        assertEquals("x≤2", LatexImporter.importOne("x \\leq 2").toLinear().replace(" ", ""))
    }
}
