package com.example.mobilemathstudio.ui.graph

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mobilemathstudio.R
import com.example.mobilemathstudio.math.expr.MathDocument
import com.example.mobilemathstudio.math.graph.GraphViewport
import com.example.mobilemathstudio.ui.math.StructuredMathView
import com.example.mobilemathstudio.ui.theme.LocalIsDarkTheme
import com.example.mobilemathstudio.ui.theme.WinLightGraphAccent
import com.example.mobilemathstudio.ui.theme.WinLightTextMuted
import com.example.mobilemathstudio.ui.theme.WinLightTextPrimary
import com.example.mobilemathstudio.ui.theme.WinTextMuted
import com.example.mobilemathstudio.ui.theme.WinTextPrimary

data class GraphTrace(
    val cellId: String,
    val label: String,
    val color: Color,
    val documents: List<MathDocument> = emptyList(),
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun GraphBoard(
    traces: List<GraphTrace>,
    viewport: GraphViewport,
    onPlotBounds: (LayoutCoordinates) -> Unit,
    onChipRowBounds: (LayoutCoordinates?) -> Unit,
    onRemoveTrace: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = LocalIsDarkTheme.current
    val primary = if (dark) WinTextPrimary else WinLightTextPrimary
    val muted = if (dark) WinTextMuted else WinLightTextMuted
    var pendingDelete by remember { mutableStateOf<GraphTrace?>(null) }
    LaunchedEffect(traces.size) {
        if (traces.isEmpty()) onChipRowBounds(null)
    }
    // Keep one string per linked cell/system so FunctionGraph scopes inequality clipping
    // strictly inside each system `{ ... }`, while `seriesColors` maps every flattened line's `sourceIndex`.
    val groupedExpressions = traces.map { it.label }.filter { it.isNotBlank() }
    val seriesColors = traces.flatMap { trace ->
        trace.label.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.map { trace.color }
    }

    Column(
        modifier = modifier
            .width(300.dp)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.graph_title),
            style = MaterialTheme.typography.labelLarge,
            color = primary,
        )
        val plotBorder = if (dark) GraphLinkYellow.copy(alpha = 0.55f) else WinLightGraphAccent.copy(alpha = 0.45f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, plotBorder, RoundedCornerShape(10.dp))
                .onGloballyPositioned(onPlotBounds),
        ) {
            GraphPlotCanvas(
                expressions = groupedExpressions,
                colors = seriesColors,
                viewport = viewport,
                dark = dark,
            )
        }
        if (traces.isEmpty()) {
            Text(
                text = stringResource(R.string.graph_empty),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
        } else {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { onChipRowBounds(it) },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                traces.forEach { trace ->
                    FunctionChip(
                        trace = trace,
                        dark = dark,
                        primary = primary,
                        onLongPress = { pendingDelete = trace },
                    )
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.graph_remove_title)) },
            text = { Text(stringResource(R.string.graph_remove_body, target.label)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemoveTrace(target.cellId)
                        pendingDelete = null
                    },
                ) {
                    Text(stringResource(R.string.graph_remove_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FunctionChip(
    trace: GraphTrace,
    dark: Boolean,
    primary: Color,
    onLongPress: () -> Unit,
) {
    val chipBg = trace.color.copy(alpha = if (dark) 0.16f else 0.11f)
    val chipBorder = trace.color.copy(alpha = if (dark) 0.22f else 0.28f)
    Row(
        modifier = Modifier
            .widthIn(min = 72.dp, max = 284.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(chipBg)
            .border(1.dp, chipBorder, RoundedCornerShape(8.dp))
            .combinedClickable(onClick = {}, onLongClick = onLongPress)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(trace.color, CircleShape),
        )
        if (trace.documents.isNotEmpty()) {
            Column(
                modifier = Modifier.wrapContentWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                trace.documents.forEach { doc ->
                    StructuredMathView(
                        document = doc,
                        focused = false,
                        textColor = primary,
                        fontSize = 16.sp,
                        captureCaretTaps = false,
                    )
                }
            }
        } else {
            Text(
                text = trace.label,
                color = primary,
                fontSize = 16.sp,
            )
        }
    }
}
