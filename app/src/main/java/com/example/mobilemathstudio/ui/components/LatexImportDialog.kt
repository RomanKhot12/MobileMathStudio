package com.example.mobilemathstudio.ui.components

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mobilemathstudio.R
import com.example.mobilemathstudio.math.LatexImporter
import com.example.mobilemathstudio.ui.math.StructuredMathView
import com.example.mobilemathstudio.ui.theme.LocalIsDarkTheme
import com.example.mobilemathstudio.ui.theme.WinLightSurfaceElevated
import com.example.mobilemathstudio.ui.theme.WinLightTextPrimary
import com.example.mobilemathstudio.ui.theme.WinLightTextSecondary
import com.example.mobilemathstudio.ui.theme.WinTextPrimary
import com.example.mobilemathstudio.ui.theme.WinTextSecondary

/**
 * Raw LaTeX in, rendered formula out. Shows a live preview of exactly what
 * will land on the canvas.
 */
@Composable
fun LatexImportDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
) {
    val context = LocalContext.current
    val dark = LocalIsDarkTheme.current
    val primary = if (dark) WinTextPrimary else WinLightTextPrimary
    val secondary = if (dark) WinTextSecondary else WinLightTextSecondary

    var text by remember { mutableStateOf(clipboardLatex(context).orEmpty()) }
    val preview = remember(text) {
        runCatching { LatexImporter.import(text) }.getOrNull()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.latex_import_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.latex_import_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = secondary,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 2,
                    maxLines = 5,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    placeholder = { Text("\\frac{1}{2}+\\sqrt{55}") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.latex_import_preview),
                    style = MaterialTheme.typography.labelMedium,
                    color = secondary,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp, max = 160.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (dark) Color.White.copy(alpha = 0.05f) else WinLightSurfaceElevated.copy(alpha = 0.72f),
                            RoundedCornerShape(10.dp),
                        )
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp),
                ) {
                    Column(
                        modifier = Modifier.wrapContentWidth(
                            align = Alignment.Start,
                            unbounded = true,
                        ),
                    ) {
                        preview?.documents?.forEach { doc ->
                            StructuredMathView(
                                document = doc,
                                focused = false,
                                textColor = primary,
                                fontSize = 20.sp,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(text.trim()) },
                enabled = text.isNotBlank(),
            ) {
                Text(stringResource(R.string.latex_import_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private fun clipboardLatex(context: Context): String? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = cm.primaryClip ?: return null
    if (clip.itemCount <= 0) return null
    val raw = clip.getItemAt(0).coerceToText(context)?.toString()?.trim().orEmpty()
    // Only prefill when it actually looks like LaTeX.
    return raw.takeIf { it.contains('\\') || it.contains('^') || it.contains('{') }
}
