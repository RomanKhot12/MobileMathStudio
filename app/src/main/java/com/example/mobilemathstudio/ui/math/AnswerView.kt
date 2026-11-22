package com.example.mobilemathstudio.ui.math

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mobilemathstudio.CrashGuard
import com.example.mobilemathstudio.math.AnswerLine
import com.example.mobilemathstudio.math.AnswerMath
import com.example.mobilemathstudio.math.expr.MathDocument
import com.example.mobilemathstudio.settings.AnswerLayout

/**
 * Exact answers as real mathematics: stacked fractions, mixed numbers in column
 * mode, one variable per line (or a comma-separated row in inline mode).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnswerView(
    result: String,
    layout: AnswerLayout,
    mixed: Boolean,
    color: Color,
    fontSize: TextUnit = 18.sp,
    exact: String? = null,
    onInspect: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val lines = AnswerMath.split(result)
    if (lines.isEmpty()) return
    val exactLines = exact?.let { AnswerMath.split(it) }.orEmpty()
    val useMixed = mixed && layout == AnswerLayout.Column

    when (layout) {
        AnswerLayout.Column -> {
            Column(
                modifier = modifier,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                lines.forEachIndexed { index, line ->
                    AnswerLineView(
                        line = line,
                        mixed = useMixed,
                        color = color,
                        fontSize = fontSize,
                        inspect = inspectOf(line, exactLines.getOrNull(index), onInspect),
                    )
                }
            }
        }
        AnswerLayout.Inline -> {
            // No horizontalScroll: canvas cells are measured with unbounded width,
            // where a scroll container throws. FlowRow stays on one line there and
            // wraps on bounded surfaces such as the export page.
            FlowRow(
                modifier = modifier,
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                lines.forEachIndexed { index, line ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AnswerLineView(
                            line = line,
                            mixed = false,
                            color = color,
                            fontSize = fontSize,
                            inspect = inspectOf(line, exactLines.getOrNull(index), onInspect),
                        )
                        if (index < lines.lastIndex) {
                            Text(
                                text = ", ",
                                color = color,
                                fontSize = fontSize,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnswerLineView(
    line: AnswerLine,
    mixed: Boolean,
    color: Color,
    fontSize: TextUnit,
    inspect: (() -> Unit)?,
) {
    StructuredMathView(
        document = CrashGuard.run(MathDocument.empty()) {
            AnswerMath.lineDocument(line, mixed)
        },
        focused = false,
        textColor = color,
        fontSize = fontSize,
        modifier = if (inspect != null) {
            Modifier
                .clickable(onClick = inspect)
                .padding(end = 2.dp)
        } else {
            Modifier
        },
    )
}

private fun inspectOf(
    display: AnswerLine,
    exact: AnswerLine?,
    onInspect: ((String) -> Unit)?,
): (() -> Unit)? {
    if (onInspect == null) return null
    val source = AnswerMath.inspectSource(display.value)
        ?: exact?.value?.let { AnswerMath.inspectSource(it) }
        ?: return null
    return { onInspect(source) }
}
