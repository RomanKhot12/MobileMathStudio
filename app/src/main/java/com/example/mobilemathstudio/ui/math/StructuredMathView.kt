package com.example.mobilemathstudio.ui.math

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import com.example.mobilemathstudio.CrashGuard
import com.example.mobilemathstudio.math.AnswerMath
import com.example.mobilemathstudio.math.expr.MathDocument
import com.example.mobilemathstudio.math.expr.MathEditor
import com.example.mobilemathstudio.math.expr.MathNode
import com.example.mobilemathstudio.math.expr.MathRange
import com.example.mobilemathstudio.math.expr.linearize
import com.example.mobilemathstudio.ui.theme.LocalAccentColor
import kotlinx.coroutines.delay

/** Accent blended with red: the "armed for insertion" colour of a snap target. */
fun snapTintOf(accent: Color): Color = lerp(accent, Color(0xFFE5484D), 0.45f)

@Composable
fun StructuredMathView(
    document: MathDocument,
    modifier: Modifier = Modifier,
    focused: Boolean = true,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    onHoleClick: (String) -> Unit = {},
    fontSize: TextUnit = 22.sp,
    /** Slot armed for corner-snap insertion, highlighted in accent+red. */
    highlightPath: List<Int>? = null,
    onSlotBounds: (path: List<Int>, empty: Boolean, coords: LayoutCoordinates) -> Unit = { _, _, _ -> },
    /** Opens the long-number viewer for π, repeating fractions, long decimals. */
    onInspect: ((String) -> Unit)? = null,
    /** Places the blinking caret inside a tapped text run. */
    onCaretTap: (path: List<Int>, offset: Int) -> Unit = { _, _ -> },
    /** When false (answers, export) taps are left for inspect / selection. */
    captureCaretTaps: Boolean = false,
    /**
     * Optional cap for tight dialogs. On the infinite canvas this stays null
     * so a formula measures at its natural width instead of wrapping into a
     * one-character column at the right edge.
     */
    maxWidth: Dp? = null,
    selection: MathRange? = null,
    onHandleBounds: (start: Boolean, coords: LayoutCoordinates) -> Unit = { _, _ -> },
) {
    val caretColor = LocalAccentColor.current
    var caretAlpha by remember { mutableFloatStateOf(0f) }
    // One blink loop, and only while this view is focused — an InfiniteTransition
    // on every formula on the canvas was relaying out the caret overlay every
    // frame and taking the process down.
    LaunchedEffect(focused) {
        if (!focused) {
            caretAlpha = 0f
            return@LaunchedEffect
        }
        while (true) {
            caretAlpha = 1f
            delay(530)
            caretAlpha = 0.08f
            delay(400)
        }
    }
    val scope = MathRenderScope(
        caret = document.caret.path,
        caretOffset = document.caret.offset,
        focused = focused,
        textColor = textColor,
        caretColor = caretColor,
        caretAlpha = if (focused && selection == null) caretAlpha else 0f,
        highlightPath = highlightPath,
        snapTint = snapTintOf(caretColor),
        onHoleClick = onHoleClick,
        onSlotBounds = onSlotBounds,
        onInspect = onInspect,
        onCaretTap = onCaretTap,
        captureCaretTaps = captureCaretTaps,
        document = document,
        selection = selection,
        selectionColor = Color(0x663A86FF),
        onHandleBounds = onHandleBounds,
    )
    Box(
        modifier = modifier
            .then(
                if (maxWidth != null) {
                    Modifier.widthIn(max = maxWidth)
                } else {
                    Modifier.wrapContentWidth(align = Alignment.Start, unbounded = true)
                },
            )
            .wrapContentHeight()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        MathNodeView(node = document.root, path = emptyList(), fontSize = fontSize, scope = scope)
    }
}

/** Everything the recursive renderer needs that does not change per node. */
private class MathRenderScope(
    val caret: List<Int>,
    val caretOffset: Int,
    val focused: Boolean,
    val textColor: Color,
    val caretColor: Color,
    val caretAlpha: Float,
    val highlightPath: List<Int>?,
    val snapTint: Color,
    val onHoleClick: (String) -> Unit,
    val onSlotBounds: (List<Int>, Boolean, LayoutCoordinates) -> Unit,
    val onInspect: ((String) -> Unit)?,
    val onCaretTap: (List<Int>, Int) -> Unit,
    val captureCaretTaps: Boolean,
    val document: MathDocument,
    val selection: MathRange?,
    val selectionColor: Color,
    val onHandleBounds: (Boolean, LayoutCoordinates) -> Unit,
)

/**
 * Wraps a child slot so it can be located on screen (corner-snap targets,
 * per-slot copy/paste) and highlighted while insertion is armed.
 */
@Composable
private fun SlotBox(
    node: MathNode,
    path: List<Int>,
    fontSize: TextUnit,
    scope: MathRenderScope,
    modifier: Modifier = Modifier,
) {
    val empty = linearize(node).isBlank()
    val armed = scope.highlightPath == path
    Box(
        modifier = modifier
            .onGloballyPositioned { scope.onSlotBounds(path, empty, it) }
            .then(
                if (armed) {
                    Modifier
                        .background(scope.snapTint.copy(alpha = 0.20f), RoundedCornerShape(4.dp))
                        .border(1.5.dp, scope.snapTint, RoundedCornerShape(4.dp))
                } else {
                    Modifier
                },
            ),
    ) {
        MathNodeView(node = node, path = path, fontSize = fontSize, scope = scope)
    }
}

@Composable
private fun MathNodeView(
    node: MathNode,
    path: List<Int>,
    fontSize: TextUnit,
    scope: MathRenderScope,
) {
    when (node) {
        is MathNode.Hole -> {
            DashedHole(
                selected = scope.focused && path == scope.caret,
                armedColor = scope.snapTint.takeIf { scope.highlightPath == path },
                caretColor = scope.caretColor,
                caretAlpha = if (scope.focused && path == scope.caret) scope.caretAlpha else 0f,
                width = (fontSize.value * 0.58f).dp,
                height = (fontSize.value * 0.95f).dp,
                onClick = { scope.onHoleClick(node.id) },
                modifier = Modifier.onGloballyPositioned { scope.onSlotBounds(path, true, it) },
            )
        }
        is MathNode.Text -> {
            val source = if (!scope.focused) {
                scope.onInspect?.let { AnswerMath.inspectSource(node.value) }
            } else {
                null
            }
            CaretText(
                text = node.value,
                path = path,
                fontSize = fontSize,
                scope = scope,
                inspectSource = source,
            )
        }
        is MathNode.Row -> {
            Row(
                modifier = Modifier.wrapContentWidth(align = Alignment.Start, unbounded = true),
            ) {
                node.children.forEachIndexed { i, child ->
                    val alignMod = when (child) {
                        is MathNode.Fraction, is MathNode.Root ->
                            Modifier.alignBy { it.measuredHeight / 2 }
                        else -> Modifier.alignByBaseline()
                    }
                    Box(modifier = alignMod) {
                        MathNodeView(node = child, path = path + i, fontSize = fontSize, scope = scope)
                    }
                }
            }
        }
        is MathNode.Fraction -> {
            val source = if (scope.onInspect != null && !scope.focused) {
                fractionInspectSource(node)
            } else {
                null
            }
            // IntrinsicSize keeps the rule exactly as wide as the widest part,
            // so a fresh fraction stays small and grows only with its content.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .width(IntrinsicSize.Max)
                    .then(inspectModifier(source, scope)),
            ) {
                SlotBox(node.num, path + 0, fontSize * 0.8f, scope)
                HorizontalDivider(
                    modifier = Modifier
                        .padding(vertical = 1.dp)
                        .fillMaxWidth()
                        .widthIn(min = 12.dp),
                    thickness = 1.2.dp,
                    color = scope.textColor,
                )
                SlotBox(node.den, path + 1, fontSize * 0.8f, scope)
            }
        }
        is MathNode.Power -> {
            val gapPx = with(LocalDensity.current) { 1.dp.roundToPx() }
            Layout(
                modifier = Modifier.wrapContentWidth(align = Alignment.Start, unbounded = true),
                content = {
                    SlotBox(
                        node = node.base,
                        path = path + 0,
                        fontSize = fontSize,
                        scope = scope,
                        modifier = Modifier.layoutId("base"),
                    )
                    SlotBox(
                        node = node.exp,
                        path = path + 1,
                        fontSize = fontSize * 0.65f,
                        scope = scope,
                        modifier = Modifier.layoutId("exp"),
                    )
                },
            ) { measurables, constraints ->
                val loose = constraints.copy(minWidth = 0, minHeight = 0)
                val baseP = measurables.first { it.layoutId == "base" }.measure(loose)
                val expP = measurables.first { it.layoutId == "exp" }.measure(loose)
                val baseBl = baseP[FirstBaseline].let { line ->
                    if (line == AlignmentLine.Unspecified) (baseP.height * 4) / 5 else line
                }
                // Exponent sits top-right; overlap the upper third of the base
                // so it reads as a superscript, not a subscript.
                val overlap = (baseP.height * 0.42f).toInt().coerceAtLeast(gapPx)
                val baseY = (expP.height - overlap).coerceAtLeast(0)
                val width = baseP.width + gapPx + expP.width
                val height = maxOf(baseY + baseP.height, expP.height)
                layout(
                    width,
                    height,
                    alignmentLines = mapOf(FirstBaseline to baseY + baseBl),
                ) {
                    baseP.place(0, baseY)
                    expP.place(baseP.width + gapPx, 0)
                }
            }
        }
        is MathNode.Root -> {
            val source = if (scope.onInspect != null && !scope.focused) {
                AnswerMath.inspectSource(linearize(node))
            } else {
                null
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = inspectModifier(source, scope)
                    .wrapContentWidth(align = Alignment.Start, unbounded = true),
            ) {
                if (node.index != null) {
                    SlotBox(
                        node = node.index,
                        path = path + 0,
                        fontSize = fontSize * 0.55f,
                        scope = scope,
                        modifier = Modifier.padding(bottom = (fontSize.value * 0.55f).dp),
                    )
                }
                RadicalSymbol(color = scope.textColor, height = (fontSize.value * 1.15f).dp)
                SlotBox(
                    node = node.radicand,
                    path = path + if (node.index == null) 0 else 1,
                    fontSize = fontSize,
                    scope = scope,
                    modifier = Modifier
                        .drawBehind {
                            drawLine(
                                color = scope.textColor,
                                start = Offset(0f, 0.75.dp.toPx()),
                                end = Offset(size.width, 0.75.dp.toPx()),
                                strokeWidth = 1.5.dp.toPx(),
                            )
                        }
                        .padding(start = 2.dp, end = 3.dp, top = 2.dp, bottom = 1.dp),
                )
            }
        }
        is MathNode.Parens -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.wrapContentWidth(align = Alignment.Start, unbounded = true),
            ) {
                Text("(", color = scope.textColor, fontSize = fontSize, fontFamily = FontFamily.Serif)
                SlotBox(node.inner, path + 0, fontSize, scope)
                Text(")", color = scope.textColor, fontSize = fontSize, fontFamily = FontFamily.Serif)
            }
        }
    }
}

@Composable
private fun CaretText(
    text: String,
    path: List<Int>,
    fontSize: TextUnit,
    scope: MathRenderScope,
    inspectSource: String?,
) {
    val isVar = text.length == 1 && text[0].isLetter()
    val active = scope.focused && path == scope.caret && scope.selection == null
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val selected = scope.selection?.let { range ->
        MathEditor.selectedChars(scope.document, range, path, text.length)
    }
    val rangeForPadding = scope.selection

    Box(
        modifier = Modifier.padding(
            bottom = if (
                rangeForPadding != null &&
                scope.focused &&
                (path == rangeForPadding.start.path || path == rangeForPadding.end.path)
            ) 28.dp else 0.dp,
        ),
    ) {
        Text(
            text = text,
            color = scope.textColor,
            fontSize = fontSize,
            fontFamily = FontFamily.Serif,
            fontStyle = if (isVar) FontStyle.Italic else FontStyle.Normal,
            fontWeight = if (text == "×") FontWeight.Bold else FontWeight.Normal,
            // A constrained Row otherwise measures the tail with leftover width
            // and wraps it one glyph per line down the right edge.
            softWrap = false,
            maxLines = 1,
            overflow = TextOverflow.Visible,
            onTextLayout = { layout = it },
            modifier = Modifier
                .onGloballyPositioned { scope.onSlotBounds(path, false, it) }
                .then(inspectModifier(inspectSource, scope))
                .then(
                    if (scope.captureCaretTaps) {
                        Modifier.pointerInput(path, text) {
                            detectTapGestures { pos ->
                                CrashGuard.run {
                                    val result = layout ?: return@run
                                    val offset = result.getOffsetForPosition(pos)
                                        .coerceIn(0, text.length)
                                    scope.onCaretTap(path, offset)
                                }
                            }
                        }
                    } else {
                        Modifier
                    },
                )
                .drawBehind {
                    val chars = selected ?: return@drawBehind
                    val measured = layout ?: return@drawBehind
                    val max = measured.layoutInput.text.length
                    for (i in chars) {
                        if (i !in 0 until max) continue
                        val box = measured.getBoundingBox(i)
                        if (!box.left.isFinite() || !box.width.isFinite()) continue
                        drawRect(
                            color = scope.selectionColor,
                            topLeft = Offset(box.left, box.top),
                            size = Size(box.width.coerceAtLeast(1f), box.height),
                        )
                    }
                }
                .drawWithContent {
                    drawContent()
                    if (!active || scope.caretAlpha <= 0f) return@drawWithContent
                    val alpha = scope.caretAlpha
                    if (!alpha.isFinite()) return@drawWithContent
                    val rect = cursorRectOrNull(layout, scope.caretOffset, text.length)
                        ?: return@drawWithContent
                    drawRect(
                        color = scope.caretColor,
                        topLeft = Offset(rect.left, rect.top),
                        size = Size(2.dp.toPx(), rect.height),
                        alpha = alpha.coerceIn(0f, 1f),
                    )
                },
        )
        val range = scope.selection
        if (range != null && scope.focused) {
            if (path == range.start.path) {
                SelectionHandle(
                    layout = layout,
                    offset = range.start.offset,
                    textLength = text.length,
                    start = true,
                    onBounds = { scope.onHandleBounds(true, it) },
                )
            }
            if (path == range.end.path) {
                SelectionHandle(
                    layout = layout,
                    offset = range.end.offset,
                    textLength = text.length,
                    start = false,
                    onBounds = { scope.onHandleBounds(false, it) },
                )
            }
        }
    }
}

@Composable
private fun SelectionHandle(
    layout: TextLayoutResult?,
    offset: Int,
    textLength: Int,
    start: Boolean,
    onBounds: (LayoutCoordinates) -> Unit,
) {
    val rect = cursorRectOrNull(layout, offset, textLength) ?: return
    val x = CrashGuard.roundPx(rect.left - if (start) 16f else 2f)
    val y = CrashGuard.roundPx(rect.bottom)
    Box(
        modifier = Modifier
            .offset { IntOffset(x, y) }
            .size(width = 22.dp, height = 28.dp)
            .onGloballyPositioned(onBounds),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stem = Color(0xFF3A86FF)
            val cx = if (start) size.width * 0.72f else size.width * 0.28f
            val radius = 6.5.dp.toPx()
            val circleY = size.height * 0.64f
            val drop = Path().apply {
                moveTo(cx, 0f)
                lineTo(cx - radius * 0.62f, circleY - radius * 0.28f)
                lineTo(cx + radius * 0.62f, circleY - radius * 0.28f)
                close()
            }
            drawPath(drop, color = stem)
            drawCircle(
                color = stem,
                radius = radius,
                center = Offset(cx, circleY),
            )
        }
    }
}

private fun cursorRectOrNull(
    layout: TextLayoutResult?,
    offset: Int,
    textLength: Int,
): androidx.compose.ui.geometry.Rect? {
    if (layout == null || textLength < 0) return null
    val max = layout.layoutInput.text.length
    if (max <= 0) return null
    val index = offset.coerceIn(0, minOf(textLength, max))
    return CrashGuard.run(null) {
        val rect = layout.getCursorRect(index)
        if (!rect.left.isFinite() || !rect.top.isFinite() || !rect.height.isFinite()) null
        else if (rect.height <= 0f) null
        else rect
    }
}

@Composable
private fun DashedHole(
    selected: Boolean,
    armedColor: Color?,
    caretColor: Color,
    caretAlpha: Float,
    width: Dp,
    height: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stroke = armedColor ?: if (selected) Color(0xFF60A5FA) else Color(0xFF9CA3AF)
    Canvas(
        modifier = modifier
            .size(width = width.coerceAtLeast(10.dp), height = height.coerceAtLeast(14.dp))
            .clickable(onClick = onClick)
            .padding(1.dp),
    ) {
        drawRoundRect(
            color = stroke,
            style = Stroke(
                width = if (armedColor != null) 2.5.dp.toPx() else 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)),
            ),
            cornerRadius = CornerRadius(3.dp.toPx()),
        )
        if (caretAlpha > 0f && caretAlpha.isFinite()) {
            val barW = 2.dp.toPx()
            val barH = size.height * 0.72f
            if (!barW.isFinite() || !barH.isFinite()) return@Canvas
            drawRect(
                color = caretColor,
                topLeft = Offset((size.width - barW) / 2f, (size.height - barH) / 2f),
                size = Size(barW, barH),
                alpha = caretAlpha.coerceIn(0f, 1f),
            )
        }
    }
}

@Composable
private fun RadicalSymbol(color: Color, height: Dp) {
    Canvas(modifier = Modifier.size(width = height * 0.55f, height = height)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(0f, h * 0.55f)
            lineTo(w * 0.28f, h * 0.55f)
            lineTo(w * 0.45f, h * 0.92f)
            lineTo(w * 0.95f, h * 0.08f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

/** Compact glyph for keyboard keys (vertical fraction / power / root). */
@Composable
fun MathKeyGlyph(
    kind: MathKeyKind,
    color: Color,
    modifier: Modifier = Modifier,
) {
    when (kind) {
        MathKeyKind.Fraction -> {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = modifier.padding(2.dp),
            ) {
                MiniHole(color)
                Spacer(Modifier.height(2.dp))
                HorizontalDivider(modifier = Modifier.width(16.dp), thickness = 1.dp, color = color)
                Spacer(Modifier.height(2.dp))
                MiniHole(color)
            }
        }
        MathKeyKind.Power -> {
            Row(verticalAlignment = Alignment.Top, modifier = modifier) {
                MiniHole(color, 12.dp)
                MiniHole(color, 8.dp)
            }
        }
        MathKeyKind.Sqrt -> {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
                RadicalSymbol(color, 18.dp)
                MiniHole(color, 12.dp)
            }
        }
        MathKeyKind.NthRoot -> {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
                MiniHole(color, 8.dp)
                RadicalSymbol(color, 18.dp)
                MiniHole(color, 12.dp)
            }
        }
    }
}

enum class MathKeyKind { Fraction, Power, Sqrt, NthRoot }

@Composable
private fun inspectModifier(source: String?, scope: MathRenderScope): Modifier {
    val inspect = scope.onInspect
    if (source == null || inspect == null) return Modifier
    return Modifier
        .clickable { inspect(source) }
        .drawBehind {
            val y = size.height - 1.dp.toPx()
            drawLine(
                color = scope.textColor.copy(alpha = 0.45f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f)),
            )
        }
}

private fun fractionInspectSource(node: MathNode.Fraction): String? {
    val num = linearize(node.num).trim()
    val den = linearize(node.den).trim()
    if (num.isEmpty() || den.isEmpty()) return null
    return AnswerMath.inspectSource("$num/$den")
}

@Composable
private fun MiniHole(color: Color, size: Dp = 10.dp) {
    Canvas(modifier = Modifier.size(size)) {
        drawRoundRect(
            color = color.copy(alpha = 0.85f),
            style = Stroke(
                width = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 2f)),
            ),
            cornerRadius = CornerRadius(2.dp.toPx()),
        )
    }
}
