package com.example.mobilemathstudio.math.expr

import java.util.UUID

/**
 * Structured math AST for LaTeX-like on-canvas rendering.
 * Empty [Hole] nodes render as dashed input boxes.
 */
sealed class MathNode {
    data class Hole(val id: String = UUID.randomUUID().toString()) : MathNode()
    data class Text(val value: String) : MathNode()
    data class Row(val children: List<MathNode>) : MathNode()
    data class Fraction(val num: MathNode, val den: MathNode) : MathNode()
    data class Power(val base: MathNode, val exp: MathNode) : MathNode()
    data class Root(val index: MathNode?, val radicand: MathNode) : MathNode()
    data class Parens(val inner: MathNode) : MathNode()
}

/** Cursor path: list of child indices from root Row into nested nodes. */
data class MathCaret(
    val path: List<Int> = emptyList(),
    /** Index inside a Text node, or child index before which we insert in a Row. */
    val offset: Int = 0,
)

/** Ordered half-open range of carets used by text selection. */
data class MathRange(
    val start: MathCaret,
    val end: MathCaret,
)

data class MathDocument(
    val root: MathNode.Row = MathNode.Row(listOf(MathNode.Hole())),
    val caret: MathCaret = MathCaret(path = listOf(0), offset = 0),
) {
    fun toLinear(): String = linearize(root)

    fun isBlank(): Boolean = toLinear().isBlank()

    companion object {
        fun empty() = MathDocument()

        /** Flat paste / LaTeX-import fallback: one text run. */
        fun fromPlain(text: String): MathDocument {
            if (text.isEmpty()) return empty()
            return MathDocument(
                root = MathNode.Row(listOf(MathNode.Text(text))),
                caret = MathCaret(path = listOf(0), offset = text.length),
            )
        }
    }
}

fun linearize(node: MathNode): String = when (node) {
    is MathNode.Hole -> ""
    is MathNode.Text -> node.value
    is MathNode.Row -> node.children.joinToString("") { linearize(it) }
    is MathNode.Fraction -> "(${linearize(node.num)})/(${linearize(node.den)})"
    is MathNode.Power -> {
        val b = linearize(node.base).let { if (it.length <= 1) it else "($it)" }
        val e = linearize(node.exp)
        "$b^($e)"
    }
    is MathNode.Root -> {
        val rad = linearize(node.radicand)
        if (node.index == null) "√($rad)"
        else {
            val idx = linearize(node.index)
            "($rad)^(1/($idx))"
        }
    }
    is MathNode.Parens -> "(${linearize(node.inner)})"
}

object MathEditor {
    fun insertText(doc: MathDocument, text: String): MathDocument {
        // Operators stay inside the active slot — never auto-exit structures.
        val (_, node) = resolve(doc.root, doc.caret.path) ?: return doc
        return when (node) {
            is MathNode.Hole -> {
                val replacement = MathNode.Text(text)
                val newRoot = replaceAt(doc.root, doc.caret.path, replacement) as MathNode.Row
                doc.copy(
                    root = newRoot,
                    caret = MathCaret(doc.caret.path, text.length),
                )
            }
            is MathNode.Text -> {
                val left = node.value.take(doc.caret.offset)
                val right = node.value.drop(doc.caret.offset)
                val next = left + text + right
                val newRoot = replaceAt(doc.root, doc.caret.path, MathNode.Text(next)) as MathNode.Row
                doc.copy(root = newRoot, caret = doc.caret.copy(offset = left.length + text.length))
            }
            is MathNode.Row -> {
                val kids = node.children.toMutableList()
                val idx = doc.caret.offset.coerceIn(0, kids.size)
                kids.add(idx, MathNode.Text(text))
                val newRoot = replaceAt(doc.root, doc.caret.path, MathNode.Row(kids)) as MathNode.Row
                doc.copy(
                    root = newRoot,
                    caret = MathCaret(doc.caret.path + idx, text.length),
                )
            }
            else -> doc
        }
    }

    fun insertFraction(doc: MathDocument): MathDocument {
        val frac = MathNode.Fraction(MathNode.Hole(), MathNode.Hole())
        return insertNode(doc, frac, focusPathSuffix = listOf(0))
    }

    fun insertPower(doc: MathDocument): MathDocument {
        val pow = MathNode.Power(MathNode.Hole(), MathNode.Hole())
        return insertNode(doc, pow, focusPathSuffix = listOf(0))
    }

    /**
     * Wraps the atom before the caret as a power base and puts [token] in the
     * exponent: `4` + long-press `8` → `4⁸`.
     */
    fun insertExponent(doc: MathDocument, token: String): MathDocument {
        if (token.isEmpty()) return doc
        val exp = MathNode.Text(token)
        val path = doc.caret.path
        val (_, current) = resolve(doc.root, path) ?: return doc
        return when (current) {
            is MathNode.Text -> {
                val left = current.value.take(doc.caret.offset)
                val right = current.value.drop(doc.caret.offset)
                val (prefix, atom) = splitTrailingAtom(left)
                val base: MathNode = if (atom.isEmpty()) MathNode.Hole() else MathNode.Text(atom)
                splicePower(doc, path, prefix, base, exp, right)
            }
            is MathNode.Hole -> wrapPreviousOrFillHole(doc, path, exp)
            is MathNode.Row -> {
                val idx = doc.caret.offset.coerceIn(0, current.children.size)
                val prev = current.children.getOrNull(idx - 1)
                if (prev != null && prev !is MathNode.Hole) {
                    val kids = current.children.toMutableList()
                    kids[idx - 1] = MathNode.Power(prev, exp)
                    val newRoot = replaceAt(doc.root, path, MathNode.Row(kids)) as MathNode.Row
                    doc.copy(root = newRoot, caret = MathCaret(path + (idx - 1) + 1, token.length))
                } else {
                    val kids = current.children.toMutableList()
                    kids.add(idx, MathNode.Power(MathNode.Hole(), exp))
                    val newRoot = replaceAt(doc.root, path, MathNode.Row(kids)) as MathNode.Row
                    doc.copy(root = newRoot, caret = MathCaret(path + idx + 1, token.length))
                }
            }
            else -> insertNode(doc, MathNode.Power(MathNode.Hole(), exp), focusPathSuffix = listOf(1))
        }
    }

    fun insertSqrt(doc: MathDocument): MathDocument {
        val root = MathNode.Root(index = null, radicand = MathNode.Hole())
        return insertNode(doc, root, focusPathSuffix = listOf(0))
    }

    fun insertNthRoot(doc: MathDocument): MathDocument {
        val root = MathNode.Root(index = MathNode.Hole(), radicand = MathNode.Hole())
        return insertNode(doc, root, focusPathSuffix = listOf(0))
    }

    fun backspace(doc: MathDocument): MathDocument {
        val templatePath = enclosingTemplatePath(doc)
        if (templatePath != null) {
            val template = resolve(doc.root, templatePath)?.second
            if (template != null && isTemplateBlank(template)) {
                return removeStructure(doc, templatePath)
            }
        }
        val (_, node) = resolve(doc.root, doc.caret.path) ?: return doc
        return when (node) {
            is MathNode.Text -> {
                if (doc.caret.offset <= 0) {
                    replaceWithHole(doc)
                } else {
                    val next = node.value.removeRange(doc.caret.offset - 1, doc.caret.offset)
                    if (next.isEmpty()) replaceWithHole(doc)
                    else {
                        val newRoot = replaceAt(doc.root, doc.caret.path, MathNode.Text(next)) as MathNode.Row
                        doc.copy(root = newRoot, caret = doc.caret.copy(offset = doc.caret.offset - 1))
                    }
                }
            }
            // An empty slot in a still-filled 2D template must not eat the template.
            // A dangling hole beside real content (the leftover of a deleted
            // template) is an orphan and must be dropped so Backspace can
            // keep eating the remaining text.
            is MathNode.Hole -> {
                val parentPath = doc.caret.path.dropLast(1)
                val parent = resolve(doc.root, parentPath)?.second
                if (parent is MathNode.Row && parent.children.size > 1) {
                    removeStructure(doc, doc.caret.path)
                } else {
                    doc
                }
            }
            else -> doc
        }
    }

    /** Innermost fraction / power / root that contains [path]. */
    fun enclosingTemplatePath(doc: MathDocument, path: List<Int> = doc.caret.path): List<Int>? {
        for (depth in path.size downTo 1) {
            val ancPath = path.take(depth)
            val anc = resolve(doc.root, ancPath)?.second ?: continue
            if (isTwoDTemplate(anc)) return ancPath
        }
        return null
    }

    fun isTwoDTemplate(node: MathNode): Boolean =
        node is MathNode.Fraction || node is MathNode.Power || node is MathNode.Root

    /**
     * True when every input window of a 2D template is empty.
     * Whole-tree [linearize] is the wrong test: an empty fraction prints as `()/()`.
     */
    fun isTemplateBlank(node: MathNode): Boolean = when (node) {
        is MathNode.Fraction -> slotBlank(node.num) && slotBlank(node.den)
        is MathNode.Power -> slotBlank(node.base) && slotBlank(node.exp)
        is MathNode.Root -> {
            val indexBlank = node.index == null || slotBlank(node.index)
            slotBlank(node.radicand) && indexBlank
        }
        else -> false
    }

    /** Drops the enclosing 2D template even when some of its slots still have content. */
    fun deleteEnclosingTemplate(doc: MathDocument, path: List<Int> = doc.caret.path): MathDocument {
        val templatePath = enclosingTemplatePath(doc, path) ?: return doc
        return removeStructure(doc, templatePath)
    }

    fun clear(doc: MathDocument): MathDocument = MathDocument.empty()

    /** First and last text carets covering the subtree at [path] (empty = whole formula). */
    fun selectionCovering(doc: MathDocument, path: List<Int>): MathRange? {
        val node = if (path.isEmpty()) doc.root else nodeAt(doc, path) ?: return null
        val leaves = mutableListOf<Pair<List<Int>, MathNode>>()
        collectLeaves(node, path, leaves)
        val texts = leaves.filter { it.second is MathNode.Text }
        if (texts.isEmpty()) return null
        val first = texts.first()
        val last = texts.last()
        val lastLen = (last.second as MathNode.Text).value.length
        val range = MathRange(MathCaret(first.first, 0), MathCaret(last.first, lastLen))
        return if (rangeIsCollapsed(doc, range)) null else orderedRange(doc, range)
    }

    fun orderedRange(doc: MathDocument, range: MathRange): MathRange {
        return if (compareCarets(doc, range.start, range.end) <= 0) range
        else MathRange(range.end, range.start)
    }

    fun rangeIsCollapsed(doc: MathDocument, range: MathRange): Boolean =
        compareCarets(doc, range.start, range.end) == 0

    fun compareCarets(doc: MathDocument, a: MathCaret, b: MathCaret): Int {
        val leaves = mutableListOf<Pair<List<Int>, MathNode>>()
        collectLeaves(doc.root, emptyList(), leaves)
        val ia = leafIndexOf(leaves, a.path)
        val ib = leafIndexOf(leaves, b.path)
        if (ia != ib) return ia.compareTo(ib)
        return a.offset.compareTo(b.offset)
    }

    fun extractLinear(doc: MathDocument, range: MathRange): String {
        val ordered = orderedRange(doc, range)
        val leaves = mutableListOf<Pair<List<Int>, MathNode>>()
        collectLeaves(doc.root, emptyList(), leaves)
        val out = StringBuilder()
        for ((path, node) in leaves) {
            val text = (node as? MathNode.Text)?.value ?: continue
            val slice = selectedChars(doc, ordered, path, text.length) ?: continue
            if (!slice.isEmpty()) out.append(text.substring(slice.first, slice.last + 1))
        }
        return out.toString()
    }

    /**
     * Characters of [path] covered by [range], inclusive end, or null if none.
     * [range] must already be ordered.
     */
    fun selectedChars(
        doc: MathDocument,
        range: MathRange,
        path: List<Int>,
        length: Int,
    ): IntRange? {
        if (length <= 0) return null
        val leaves = mutableListOf<Pair<List<Int>, MathNode>>()
        collectLeaves(doc.root, emptyList(), leaves)
        val index = leafIndexOf(leaves, path)
        val startIndex = leafIndexOf(leaves, range.start.path)
        val endIndex = leafIndexOf(leaves, range.end.path)
        val from = when {
            index < startIndex || index > endIndex -> return null
            index == startIndex -> range.start.offset.coerceIn(0, length)
            else -> 0
        }
        val toExclusive = when {
            index == endIndex -> range.end.offset.coerceIn(0, length)
            else -> length
        }
        if (toExclusive <= from) return null
        return from until toExclusive
    }

    fun deleteRange(doc: MathDocument, range: MathRange): MathDocument {
        val ordered = orderedRange(doc, range)
        if (rangeIsCollapsed(doc, ordered)) return doc
        val pruned = pruneRange(doc.root, emptyList(), doc, ordered) as? MathNode.Row
            ?: MathNode.Row(listOf(MathNode.Hole()))
        val cleaned = if (pruned.children.isEmpty()) {
            MathNode.Row(listOf(MathNode.Hole()))
        } else {
            pruned
        }
        val consolidated = consolidateRoot(cleaned)
        val caret = findFirstHole(consolidated, emptyList())
            ?: lastEditableCaret(consolidated)
            ?: MathCaret(listOf(0), 0)
        return clampCaret(consolidated, caret)
    }

    fun moveCaretFrom(doc: MathDocument, caret: MathCaret, delta: Int): MathCaret {
        if (delta == 0) return caret
        return MathEditor.moveCaret(doc.copy(caret = caret), delta).caret
    }

    private fun pruneRange(
        node: MathNode,
        path: List<Int>,
        doc: MathDocument,
        range: MathRange,
    ): MathNode? = when (node) {
        is MathNode.Text -> {
            val slice = selectedChars(doc, range, path, node.value.length) ?: return node
            val kept = node.value.removeRange(slice.first, slice.last + 1)
            if (kept.isEmpty()) null else MathNode.Text(kept)
        }
        is MathNode.Hole -> {
            val leaves = mutableListOf<Pair<List<Int>, MathNode>>()
            collectLeaves(doc.root, emptyList(), leaves)
            val index = leafIndexOf(leaves, path)
            val startIndex = leafIndexOf(leaves, range.start.path)
            val endIndex = leafIndexOf(leaves, range.end.path)
            if (index in startIndex..endIndex && range.start.path != path && range.end.path != path) {
                null
            } else {
                node
            }
        }
        is MathNode.Row -> {
            val kids = node.children.mapIndexedNotNull { i, child ->
                pruneRange(child, path + i, doc, range)
            }
            when {
                kids.isEmpty() -> null
                else -> MathNode.Row(kids)
            }
        }
        is MathNode.Fraction -> {
            val n = pruneRange(node.num, path + 0, doc, range) ?: MathNode.Hole()
            val d = pruneRange(node.den, path + 1, doc, range) ?: MathNode.Hole()
            MathNode.Fraction(n, d)
        }
        is MathNode.Power -> {
            val b = pruneRange(node.base, path + 0, doc, range) ?: MathNode.Hole()
            val e = pruneRange(node.exp, path + 1, doc, range) ?: MathNode.Hole()
            MathNode.Power(b, e)
        }
        is MathNode.Root -> {
            val idx = node.index?.let { pruneRange(it, path + 0, doc, range) ?: MathNode.Hole() }
            val radIdx = if (node.index == null) 0 else 1
            val rad = pruneRange(node.radicand, path + radIdx, doc, range) ?: MathNode.Hole()
            when {
                idx == null -> MathNode.Root(null, rad)
                else -> MathNode.Root(idx, rad)
            }
        }
        is MathNode.Parens -> {
            val inner = pruneRange(node.inner, path + 0, doc, range) ?: MathNode.Hole()
            if (slotBlank(inner)) MathNode.Parens(MathNode.Hole()) else MathNode.Parens(inner)
        }
    }

    fun moveCaret(doc: MathDocument, delta: Int): MathDocument {
        if (delta == 0) return doc
        val leaves = mutableListOf<Pair<List<Int>, MathNode>>()
        collectLeaves(doc.root, emptyList(), leaves)
        if (leaves.isEmpty()) return doc

        var index = leafIndexOf(leaves, doc.caret.path)
        var offset = doc.caret.offset
        var remaining = delta

        while (remaining != 0) {
            val (path, node) = leaves[index]
            if (remaining > 0) {
                val room = when (node) {
                    is MathNode.Text -> (node.value.length - offset).coerceAtLeast(0)
                    else -> 0
                }
                if (room > 0) {
                    val step = minOf(remaining, room)
                    offset += step
                    remaining -= step
                    continue
                }
                if (index >= leaves.lastIndex) break
                index++
                offset = 0
                remaining--
            } else {
                if (node is MathNode.Text && offset > 0) {
                    val step = minOf(-remaining, offset)
                    offset -= step
                    remaining += step
                    continue
                }
                if (index <= 0) break
                index--
                val prev = leaves[index].second
                offset = (prev as? MathNode.Text)?.value?.length ?: 0
                remaining++
            }
        }

        val (path, node) = leaves[index]
        val clamped = when (node) {
            is MathNode.Text -> offset.coerceIn(0, node.value.length)
            else -> 0
        }
        return doc.copy(caret = MathCaret(path, clamped))
    }

    /** Puts the caret on a tapped node, clamping the offset to that node. */
    fun placeCaret(doc: MathDocument, path: List<Int>, offset: Int): MathDocument {
        val node = resolve(doc.root, path)?.second ?: return doc
        val clamped = when (node) {
            is MathNode.Text -> offset.coerceIn(0, node.value.length)
            is MathNode.Row -> offset.coerceIn(0, node.children.size)
            else -> 0
        }
        return doc.copy(caret = MathCaret(path, clamped))
    }

    fun focusHole(doc: MathDocument, holeId: String): MathDocument {
        val path = findHole(doc.root, holeId, emptyList()) ?: return doc
        return doc.copy(caret = MathCaret(path, 0))
    }

    /** Sub-tree living at [path], or null when the path does not address a node. */
    fun nodeAt(doc: MathDocument, path: List<Int>): MathNode? =
        resolve(doc.root, path)?.second

    /** True when the slot at [path] is an empty input box. */
    fun isSlotEmpty(doc: MathDocument, path: List<Int>): Boolean {
        val node = nodeAt(doc, path) ?: return false
        return linearize(node).isBlank()
    }

    /** Drops [node] into the slot at [path], keeping the caret inside it. */
    fun replaceNodeAt(doc: MathDocument, path: List<Int>, node: MathNode): MathDocument {
        if (path.isEmpty()) {
            val row = node as? MathNode.Row ?: MathNode.Row(listOf(node))
            return MathDocument(root = row, caret = findFirstHole(row, emptyList()) ?: MathCaret())
        }
        if (resolve(doc.root, path) == null) return doc
        val root = replaceAt(doc.root, path, node) as? MathNode.Row ?: return doc
        val caret = findFirstHole(node, path) ?: MathCaret(path, 0)
        return doc.copy(root = root, caret = caret)
    }

    /** True when caret is inside fraction / power / root / parens. */
    fun isInNestedSlot(doc: MathDocument): Boolean {
        val path = doc.caret.path
        for (depth in 1..path.size) {
            val anc = resolve(doc.root, path.take(depth))?.second ?: continue
            if (anc is MathNode.Fraction || anc is MathNode.Power ||
                anc is MathNode.Root || anc is MathNode.Parens
            ) {
                return true
            }
        }
        return false
    }

    /**
     * Confirm the current nested slot: jump to the next hole of the *innermost*
     * template (power inside a fraction first returns to that fraction slot),
     * and only then to the next sibling template slot.
     */
    fun confirmSlot(doc: MathDocument): MathDocument {
        if (!isInNestedSlot(doc)) return doc
        val path = doc.caret.path
        var structurePath: List<Int>? = null
        for (depth in 1..path.size) {
            val ancPath = path.take(depth)
            val anc = resolve(doc.root, ancPath)?.second ?: continue
            if (anc is MathNode.Fraction || anc is MathNode.Power ||
                anc is MathNode.Root || anc is MathNode.Parens
            ) {
                structurePath = ancPath
            }
        }
        val sp = structurePath ?: return doc
        val structure = resolve(doc.root, sp)?.second ?: return doc

        val holes = mutableListOf<List<Int>>()
        collectHoles(structure, sp, holes)
        val currentIdx = indexOfCaretAmongHoles(holes, path)
        val nextHole = when {
            holes.isEmpty() -> null
            currentIdx < 0 -> holes.firstOrNull { hole -> !path.startsWith(hole) }
                ?: holes.firstOrNull()
            currentIdx + 1 < holes.size -> holes[currentIdx + 1]
            else -> null
        }
        if (nextHole != null) {
            return doc.copy(caret = MathCaret(nextHole, 0))
        }
        return exitAfterStructure(doc, sp)
    }

    private fun indexOfCaretAmongHoles(holes: List<List<Int>>, path: List<Int>): Int {
        val exact = holes.indexOfFirst { it == path }
        if (exact >= 0) return exact
        val nested = holes.indexOfFirst { hole -> path.startsWith(hole) || hole.startsWith(path) }
        if (nested >= 0) return nested
        return holes.indexOfFirst { hole -> hole.size >= path.size && path.take(hole.size) == hole }
    }

    /** Leave [sp] and land just after it in the parent slot, so typing can continue there. */
    private fun exitAfterStructure(doc: MathDocument, sp: List<Int>): MathDocument {
        if (sp.isEmpty()) return doc
        val parentPath = sp.dropLast(1)
        val index = sp.last()
        val parent = resolve(doc.root, parentPath)?.second
        return when (parent) {
            is MathNode.Row -> {
                val kids = parent.children.toMutableList()
                val insertAt = index + 1
                val next = kids.getOrNull(insertAt)
                when (next) {
                    is MathNode.Hole -> doc.copy(caret = MathCaret(parentPath + insertAt, 0))
                    is MathNode.Text -> doc.copy(caret = MathCaret(parentPath + insertAt, 0))
                    null -> {
                        kids.add(MathNode.Hole())
                        val newRoot = replaceAt(doc.root, parentPath, MathNode.Row(kids)) as MathNode.Row
                        doc.copy(root = newRoot, caret = MathCaret(parentPath + insertAt, 0))
                    }
                    else -> {
                        kids.add(insertAt, MathNode.Hole())
                        val newRoot = replaceAt(doc.root, parentPath, MathNode.Row(kids)) as MathNode.Row
                        doc.copy(root = newRoot, caret = MathCaret(parentPath + insertAt, 0))
                    }
                }
            }
            is MathNode.Fraction, is MathNode.Power, is MathNode.Root, is MathNode.Parens -> {
                val node = resolve(doc.root, sp)?.second ?: return doc
                when (node) {
                    is MathNode.Row -> {
                        val kids = node.children.toMutableList()
                        val lastIdx = kids.lastIndex
                        if (kids.getOrNull(lastIdx) is MathNode.Hole && lastIdx >= 0) {
                            return doc.copy(caret = MathCaret(sp + lastIdx, 0))
                        }
                        kids.add(MathNode.Hole())
                        val newRoot = replaceAt(doc.root, sp, MathNode.Row(kids)) as MathNode.Row
                        doc.copy(root = newRoot, caret = MathCaret(sp + (kids.size - 1), 0))
                    }
                    else -> {
                        val wrapper = MathNode.Row(listOf(node, MathNode.Hole()))
                        val newRoot = replaceAt(doc.root, sp, wrapper) as MathNode.Row
                        doc.copy(root = newRoot, caret = MathCaret(sp + 1, 0))
                    }
                }
            }
            else -> doc
        }
    }

    /** Collapse incomplete structures when focus leaves the cell. */
    fun collapseEmpty(doc: MathDocument): MathDocument {
        val collapsed = collapseNode(doc.root) as? MathNode.Row
            ?: MathNode.Row(listOf(MathNode.Hole()))
        val cleaned = if (collapsed.children.isEmpty()) {
            MathNode.Row(listOf(MathNode.Hole()))
        } else {
            collapsed
        }
        val caret = findFirstHole(cleaned, emptyList())
            ?: MathCaret(listOf(0), 0)
        return MathDocument(root = cleaned, caret = caret)
    }

    private fun slotBlank(node: MathNode): Boolean = linearize(node).isBlank()

    private fun List<Int>.startsWith(prefix: List<Int>): Boolean =
        size >= prefix.size && take(prefix.size) == prefix

    private fun collectHoles(node: MathNode, path: List<Int>, out: MutableList<List<Int>>) {
        when (node) {
            is MathNode.Hole -> out += path
            is MathNode.Text -> Unit
            is MathNode.Row -> node.children.forEachIndexed { i, c -> collectHoles(c, path + i, out) }
            is MathNode.Fraction -> {
                collectHoles(node.num, path + 0, out)
                collectHoles(node.den, path + 1, out)
            }
            is MathNode.Power -> {
                collectHoles(node.base, path + 0, out)
                collectHoles(node.exp, path + 1, out)
            }
            is MathNode.Root -> {
                node.index?.let { collectHoles(it, path + 0, out) }
                val radIdx = if (node.index == null) 0 else 1
                collectHoles(node.radicand, path + radIdx, out)
            }
            is MathNode.Parens -> collectHoles(node.inner, path + 0, out)
        }
    }

    private fun collapseNode(node: MathNode): MathNode = when (node) {
        is MathNode.Hole, is MathNode.Text -> node
        is MathNode.Row -> {
            val kids = node.children.map { collapseNode(it) }.filterNot { child ->
                child is MathNode.Hole && node.children.size > 1
            }
            MathNode.Row(kids.ifEmpty { listOf(MathNode.Hole()) })
        }
        // A structure survives as long as any part of it was filled in: its
        // remaining holes stay on screen as real input slots (and as drop
        // targets for corner-snap insertion). Only wholly empty ones collapse.
        is MathNode.Fraction -> {
            val n = collapseNode(node.num)
            val d = collapseNode(node.den)
            if (linearize(n).isBlank() && linearize(d).isBlank()) MathNode.Hole()
            else MathNode.Fraction(n, d)
        }
        is MathNode.Power -> {
            val b = collapseNode(node.base)
            val e = collapseNode(node.exp)
            if (linearize(b).isBlank() && linearize(e).isBlank()) MathNode.Hole()
            else MathNode.Power(b, e)
        }
        is MathNode.Root -> {
            val rad = collapseNode(node.radicand)
            val idx = node.index?.let { collapseNode(it) }
            val idxBlank = idx == null || linearize(idx).isBlank()
            when {
                linearize(rad).isBlank() && idxBlank -> MathNode.Hole()
                idxBlank -> MathNode.Root(null, rad)
                else -> MathNode.Root(idx, rad)
            }
        }
        is MathNode.Parens -> {
            val inner = collapseNode(node.inner)
            if (linearize(inner).isBlank()) MathNode.Hole() else MathNode.Parens(inner)
        }
    }

    private fun splitTrailingAtom(left: String): Pair<String, String> {
        if (left.isEmpty()) return "" to ""
        for (symbol in listOf("∞", "π")) {
            if (left.endsWith(symbol)) return left.dropLast(symbol.length) to symbol
        }
        val last = left.last()
        if (last.isDigit()) {
            var i = left.lastIndex
            while (i >= 0 && left[i].isDigit()) i--
            return left.take(i + 1) to left.drop(i + 1)
        }
        if (last.isLetter()) return left.dropLast(1) to last.toString()
        return left to ""
    }

    private fun wrapPreviousOrFillHole(
        doc: MathDocument,
        path: List<Int>,
        exp: MathNode.Text,
    ): MathDocument {
        val parentPath = path.dropLast(1)
        val parent = resolve(doc.root, parentPath)?.second
        val idx = path.last()
        if (parent is MathNode.Row && idx > 0) {
            val prev = parent.children.getOrNull(idx - 1)
            if (prev != null && prev !is MathNode.Hole) {
                val kids = parent.children.toMutableList()
                kids[idx - 1] = MathNode.Power(prev, exp)
                val newRoot = replaceAt(doc.root, parentPath, MathNode.Row(kids)) as MathNode.Row
                return doc.copy(
                    root = newRoot,
                    caret = MathCaret(parentPath + (idx - 1) + 1, exp.value.length),
                )
            }
        }
        val newRoot = replaceAt(doc.root, path, MathNode.Power(MathNode.Hole(), exp)) as MathNode.Row
        return doc.copy(root = newRoot, caret = MathCaret(path + 1, exp.value.length))
    }

    private fun splicePower(
        doc: MathDocument,
        textPath: List<Int>,
        prefix: String,
        base: MathNode,
        exp: MathNode.Text,
        right: String,
    ): MathDocument {
        val pow = MathNode.Power(base, exp)
        val pieces = buildList {
            if (prefix.isNotEmpty()) add(MathNode.Text(prefix))
            add(pow)
            if (right.isNotEmpty()) add(MathNode.Text(right))
        }
        val structureIndex = if (prefix.isNotEmpty()) 1 else 0
        val parentPath = textPath.dropLast(1)
        val parent = resolve(doc.root, parentPath)?.second
        val newRoot: MathNode.Row
        val structurePath: List<Int>
        if (parent is MathNode.Row) {
            val kids = parent.children.toMutableList()
            val idx = textPath.last()
            kids.removeAt(idx)
            kids.addAll(idx, pieces)
            newRoot = replaceAt(doc.root, parentPath, MathNode.Row(kids)) as MathNode.Row
            structurePath = parentPath + (idx + structureIndex)
        } else {
            val wrapper = if (pieces.size == 1) pieces.first() else MathNode.Row(pieces)
            newRoot = replaceAt(doc.root, textPath, wrapper) as MathNode.Row
            structurePath = if (wrapper is MathNode.Row) textPath + structureIndex else textPath
        }
        return doc.copy(root = newRoot, caret = MathCaret(structurePath + 1, exp.value.length))
    }

    private fun insertNode(doc: MathDocument, node: MathNode, focusPathSuffix: List<Int>): MathDocument {
        val path = doc.caret.path
        val (_, current) = resolve(doc.root, path) ?: return doc
        val newRoot: MathNode.Row
        val structurePath: List<Int>
        when (current) {
            is MathNode.Hole -> {
                newRoot = replaceAt(doc.root, path, node) as MathNode.Row
                structurePath = path
            }
            is MathNode.Text -> {
                val left = current.value.take(doc.caret.offset)
                val right = current.value.drop(doc.caret.offset)
                val pieces = buildList {
                    if (left.isNotEmpty()) add(MathNode.Text(left))
                    add(node)
                    if (right.isNotEmpty()) add(MathNode.Text(right))
                }
                val structureIndex = if (left.isNotEmpty()) 1 else 0
                val parentPath = path.dropLast(1)
                val parent = resolve(doc.root, parentPath)?.second
                if (parent is MathNode.Row) {
                    val kids = parent.children.toMutableList()
                    val idx = path.last()
                    kids.removeAt(idx)
                    kids.addAll(idx, pieces)
                    newRoot = replaceAt(doc.root, parentPath, MathNode.Row(kids)) as MathNode.Row
                    structurePath = parentPath + (idx + structureIndex)
                } else {
                    val wrapper = if (pieces.size == 1) pieces.first() else MathNode.Row(pieces)
                    newRoot = replaceAt(doc.root, path, wrapper) as MathNode.Row
                    structurePath = if (wrapper is MathNode.Row) path + structureIndex else path
                }
            }
            is MathNode.Row -> {
                val kids = current.children.toMutableList()
                val idx = doc.caret.offset.coerceIn(0, kids.size)
                kids.add(idx, node)
                newRoot = replaceAt(doc.root, path, MathNode.Row(kids)) as MathNode.Row
                structurePath = path + idx
            }
            else -> return doc
        }
        val inserted = resolve(newRoot, structurePath)?.second ?: node
        val focused = findFirstHole(inserted, structurePath)
            ?: MathCaret(structurePath + focusPathSuffix, 0)
        return doc.copy(root = newRoot, caret = focused)
    }

    /**
     * Drops [path] from its parent and consolidates the tree: nested rows
     * unwrap, adjacent text runs merge, leftover holes beside real content
     * disappear. The caret lands at the end of the preceding text.
     */
    private fun removeStructure(doc: MathDocument, path: List<Int>): MathDocument {
        if (path.isEmpty()) return MathDocument.empty()
        val parentPath = path.dropLast(1)
        val index = path.last()
        val parent = resolve(doc.root, parentPath)?.second ?: return doc
        return when (parent) {
            is MathNode.Row -> {
                if (index !in parent.children.indices) return doc
                val kids = parent.children.toMutableList()
                kids.removeAt(index)
                val joinOffset = textLengthOf(kids.take(index))
                val compact = flattenAndMergeTexts(kids).ifEmpty { listOf(MathNode.Hole()) }
                val replacement: MathNode = when {
                    parentPath.isEmpty() -> MathNode.Row(compact)
                    compact.size == 1 -> compact.first()
                    else -> MathNode.Row(compact)
                }
                val newRoot = replaceAt(doc.root, parentPath, replacement) as MathNode.Row
                val consolidated = consolidateRoot(newRoot)
                val caret = caretAfterRemove(parentPath, compact, joinOffset, replacement)
                clampCaret(consolidated, caret)
            }
            else -> {
                val newRoot = replaceAt(doc.root, path, MathNode.Hole()) as MathNode.Row
                doc.copy(root = consolidateRoot(newRoot), caret = MathCaret(path, 0))
            }
        }
    }

    private fun caretAfterRemove(
        parentPath: List<Int>,
        compact: List<MathNode>,
        joinOffset: Int,
        replacement: MathNode,
    ): MathCaret {
        val textIndex = compact.indexOfFirst { it is MathNode.Text }
        if (textIndex >= 0) {
            val text = compact[textIndex] as MathNode.Text
            val offset = joinOffset.coerceIn(0, text.value.length)
            return if (replacement is MathNode.Row) {
                MathCaret(parentPath + textIndex, offset)
            } else {
                MathCaret(parentPath, offset)
            }
        }
        return if (replacement is MathNode.Row) {
            MathCaret(parentPath + 0, 0)
        } else {
            MathCaret(parentPath, 0)
        }
    }

    private fun textLengthOf(nodes: List<MathNode>): Int = nodes.sumOf { node ->
        when (node) {
            is MathNode.Text -> node.value.length
            is MathNode.Row -> textLengthOf(node.children)
            else -> 0
        }
    }

    /** Flattens nested rows and joins neighbouring text runs. */
    private fun flattenAndMergeTexts(children: List<MathNode>): List<MathNode> {
        val flat = ArrayList<MathNode>(children.size)
        fun walk(node: MathNode) {
            when (node) {
                is MathNode.Row -> node.children.forEach(::walk)
                else -> flat += node
            }
        }
        children.forEach(::walk)
        val merged = ArrayList<MathNode>(flat.size)
        for (node in flat) {
            val last = merged.lastOrNull()
            when {
                node is MathNode.Text && node.value.isEmpty() -> Unit
                node is MathNode.Text && last is MathNode.Text -> {
                    merged[merged.lastIndex] = MathNode.Text(last.value + node.value)
                }
                node is MathNode.Hole && last is MathNode.Hole -> Unit
                else -> merged += node
            }
        }
        return merged
    }

    private fun consolidateRoot(root: MathNode.Row): MathNode.Row {
        val kids = flattenAndMergeTexts(root.children.map { consolidateNode(it) })
        return MathNode.Row(kids.ifEmpty { listOf(MathNode.Hole()) })
    }

    private fun consolidateNode(node: MathNode): MathNode = when (node) {
        is MathNode.Hole, is MathNode.Text -> node
        is MathNode.Row -> {
            val kids = flattenAndMergeTexts(node.children.map { consolidateNode(it) })
            when {
                kids.isEmpty() -> MathNode.Hole()
                kids.size == 1 -> kids.first()
                else -> MathNode.Row(kids)
            }
        }
        is MathNode.Fraction -> MathNode.Fraction(consolidateNode(node.num), consolidateNode(node.den))
        is MathNode.Power -> MathNode.Power(consolidateNode(node.base), consolidateNode(node.exp))
        is MathNode.Root -> MathNode.Root(
            index = node.index?.let { consolidateNode(it) },
            radicand = consolidateNode(node.radicand),
        )
        is MathNode.Parens -> {
            val inner = consolidateNode(node.inner)
            if (inner is MathNode.Hole) inner else MathNode.Parens(inner)
        }
    }

    private fun clampCaret(root: MathNode.Row, caret: MathCaret): MathDocument {
        val node = resolve(root, caret.path)?.second
        val clamped = when (node) {
            is MathNode.Text -> caret.copy(offset = caret.offset.coerceIn(0, node.value.length))
            is MathNode.Hole -> caret.copy(offset = 0)
            is MathNode.Row -> caret.copy(offset = caret.offset.coerceIn(0, node.children.size))
            null -> lastEditableCaret(root) ?: MathCaret(listOf(0), 0)
            else -> caret.copy(offset = 0)
        }
        return MathDocument(root = root, caret = clamped)
    }

    private fun lastEditableCaret(node: MathNode, path: List<Int> = emptyList()): MathCaret? {
        when (node) {
            is MathNode.Text -> return MathCaret(path, node.value.length)
            is MathNode.Hole -> return MathCaret(path, 0)
            is MathNode.Row -> {
                for (i in node.children.indices.reversed()) {
                    lastEditableCaret(node.children[i], path + i)?.let { return it }
                }
            }
            is MathNode.Fraction -> {
                lastEditableCaret(node.den, path + 1)?.let { return it }
                lastEditableCaret(node.num, path + 0)?.let { return it }
            }
            is MathNode.Power -> {
                lastEditableCaret(node.exp, path + 1)?.let { return it }
                lastEditableCaret(node.base, path + 0)?.let { return it }
            }
            is MathNode.Root -> {
                val radIdx = if (node.index == null) 0 else 1
                lastEditableCaret(node.radicand, path + radIdx)?.let { return it }
                node.index?.let { lastEditableCaret(it, path + 0)?.let { caret -> return caret } }
            }
            is MathNode.Parens -> lastEditableCaret(node.inner, path + 0)?.let { return it }
        }
        return null
    }

    private fun replaceWithHole(doc: MathDocument): MathDocument {
        if (doc.caret.path.isEmpty()) return MathDocument.empty()
        val parentPath = doc.caret.path.dropLast(1)
        val index = doc.caret.path.last()
        val parent = resolve(doc.root, parentPath)?.second
        return when (parent) {
            is MathNode.Row -> {
                if (index in parent.children.indices) removeStructure(doc, doc.caret.path) else doc
            }
            is MathNode.Fraction, is MathNode.Power, is MathNode.Root, is MathNode.Parens -> {
                val newRoot = replaceAt(doc.root, doc.caret.path, MathNode.Hole()) as MathNode.Row
                doc.copy(root = newRoot, caret = MathCaret(doc.caret.path, 0))
            }
            else -> MathDocument.empty()
        }
    }

    private fun findFirstHole(node: MathNode, basePath: List<Int>): MathCaret? {
        when (node) {
            is MathNode.Hole -> return MathCaret(basePath, 0)
            is MathNode.Text -> return MathCaret(basePath, node.value.length)
            is MathNode.Row -> node.children.forEachIndexed { i, c ->
                findFirstHole(c, basePath + i)?.let { return it }
            }
            is MathNode.Fraction -> {
                findFirstHole(node.num, basePath + 0)?.let { return it }
                findFirstHole(node.den, basePath + 1)?.let { return it }
            }
            is MathNode.Power -> {
                findFirstHole(node.base, basePath + 0)?.let { return it }
                findFirstHole(node.exp, basePath + 1)?.let { return it }
            }
            is MathNode.Root -> {
                node.index?.let { findFirstHole(it, basePath + 0)?.let { c -> return c } }
                val radIdx = if (node.index == null) 0 else 1
                findFirstHole(node.radicand, basePath + radIdx)?.let { return it }
            }
            is MathNode.Parens -> findFirstHole(node.inner, basePath + 0)?.let { return it }
        }
        return null
    }

    private fun collectLeaves(
        node: MathNode,
        path: List<Int>,
        out: MutableList<Pair<List<Int>, MathNode>>,
    ) {
        when (node) {
            is MathNode.Text, is MathNode.Hole -> out += path to node
            is MathNode.Row -> node.children.forEachIndexed { i, child ->
                collectLeaves(child, path + i, out)
            }
            is MathNode.Fraction -> {
                collectLeaves(node.num, path + 0, out)
                collectLeaves(node.den, path + 1, out)
            }
            is MathNode.Power -> {
                collectLeaves(node.base, path + 0, out)
                collectLeaves(node.exp, path + 1, out)
            }
            is MathNode.Root -> {
                node.index?.let { collectLeaves(it, path + 0, out) }
                collectLeaves(node.radicand, path + if (node.index == null) 0 else 1, out)
            }
            is MathNode.Parens -> collectLeaves(node.inner, path + 0, out)
        }
    }

    private fun leafIndexOf(
        leaves: List<Pair<List<Int>, MathNode>>,
        path: List<Int>,
    ): Int {
        val exact = leaves.indexOfFirst { it.first == path }
        if (exact >= 0) return exact
        val nested = leaves.indexOfFirst { leaf ->
            leaf.first.size >= path.size && leaf.first.take(path.size) == path
        }
        return nested.coerceAtLeast(0)
    }

    private fun findHole(node: MathNode, id: String, path: List<Int>): List<Int>? {
        when (node) {
            is MathNode.Hole -> return if (node.id == id) path else null
            is MathNode.Row -> node.children.forEachIndexed { i, c ->
                findHole(c, id, path + i)?.let { return it }
            }
            is MathNode.Fraction -> {
                findHole(node.num, id, path + 0)?.let { return it }
                findHole(node.den, id, path + 1)?.let { return it }
            }
            is MathNode.Power -> {
                findHole(node.base, id, path + 0)?.let { return it }
                findHole(node.exp, id, path + 1)?.let { return it }
            }
            is MathNode.Root -> {
                node.index?.let { findHole(it, id, path + 0)?.let { p -> return p } }
                val radIdx = if (node.index == null) 0 else 1
                findHole(node.radicand, id, path + radIdx)?.let { return it }
            }
            is MathNode.Parens -> findHole(node.inner, id, path + 0)?.let { return it }
            is MathNode.Text -> Unit
        }
        return null
    }

    private fun resolve(root: MathNode, path: List<Int>): Pair<List<Int>, MathNode>? {
        var node: MathNode = root
        for (idx in path) {
            node = when (node) {
                is MathNode.Row -> node.children.getOrNull(idx) ?: return null
                is MathNode.Fraction -> when (idx) {
                    0 -> node.num
                    1 -> node.den
                    else -> return null
                }
                is MathNode.Power -> when (idx) {
                    0 -> node.base
                    1 -> node.exp
                    else -> return null
                }
                is MathNode.Root -> {
                    if (node.index == null) {
                        if (idx == 0) node.radicand else return null
                    } else when (idx) {
                        0 -> node.index
                        1 -> node.radicand
                        else -> return null
                    }
                }
                is MathNode.Parens -> if (idx == 0) node.inner else return null
                else -> return null
            }
        }
        return path to node
    }

    private fun replaceAt(root: MathNode, path: List<Int>, replacement: MathNode): MathNode {
        if (path.isEmpty()) return replacement
        val idx = path.first()
        val rest = path.drop(1)
        return when (root) {
            is MathNode.Row -> {
                val kids = root.children.toMutableList()
                kids[idx] = replaceAt(kids[idx], rest, replacement)
                MathNode.Row(kids)
            }
            is MathNode.Fraction -> when (idx) {
                0 -> root.copy(num = replaceAt(root.num, rest, replacement))
                1 -> root.copy(den = replaceAt(root.den, rest, replacement))
                else -> root
            }
            is MathNode.Power -> when (idx) {
                0 -> root.copy(base = replaceAt(root.base, rest, replacement))
                1 -> root.copy(exp = replaceAt(root.exp, rest, replacement))
                else -> root
            }
            is MathNode.Root -> {
                if (root.index == null) {
                    if (idx == 0) root.copy(radicand = replaceAt(root.radicand, rest, replacement)) else root
                } else when (idx) {
                    0 -> root.copy(index = replaceAt(root.index, rest, replacement))
                    1 -> root.copy(radicand = replaceAt(root.radicand, rest, replacement))
                    else -> root
                }
            }
            is MathNode.Parens -> if (idx == 0) root.copy(inner = replaceAt(root.inner, rest, replacement)) else root
            else -> replacement
        }
    }
}
