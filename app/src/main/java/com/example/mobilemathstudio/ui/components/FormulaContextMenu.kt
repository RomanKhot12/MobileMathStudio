package com.example.mobilemathstudio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ContentPasteGo
import androidx.compose.material.icons.outlined.CopyAll
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.mobilemathstudio.R
import com.example.mobilemathstudio.ui.theme.LocalIsDarkTheme
import com.example.mobilemathstudio.ui.theme.WinDanger
import com.example.mobilemathstudio.ui.theme.WinLightCardTop
import com.example.mobilemathstudio.ui.theme.WinLightDanger
import com.example.mobilemathstudio.ui.theme.WinLightTextPrimary
import com.example.mobilemathstudio.ui.theme.WinTextPrimary

@Composable
fun SelectionActionBar(
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPaste: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = LocalIsDarkTheme.current
    val bg = if (dark) Color(0xF01C1C1E) else WinLightCardTop.copy(alpha = 0.97f)
    val border = if (dark) Color.White.copy(alpha = 0.12f) else WinLightTextPrimary.copy(alpha = 0.14f)
    val tint = if (dark) WinTextPrimary else WinLightTextPrimary
    Row(
        modifier = modifier
            .background(bg, RoundedCornerShape(12.dp))
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MenuIcon(Icons.Outlined.ContentCopy, stringResource(R.string.cd_copy), tint, onCopy)
        MenuIcon(Icons.Outlined.ContentPaste, stringResource(R.string.cd_paste), tint, onPaste)
        MenuIcon(Icons.Outlined.ContentCut, stringResource(R.string.cd_cut), tint, onCut)
    }
}

@Composable
fun FormulaContextMenu(
    onCopyLatex: () -> Unit,
    onImportLatex: () -> Unit,
    onPasteText: () -> Unit,
    onDelete: () -> Unit,
    onSelect: (() -> Unit)? = null,
    onPlot: (() -> Unit)? = null,
    /** Present only when the long press landed inside a child slot. */
    onCopySlot: (() -> Unit)? = null,
    onPasteSlot: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val dark = LocalIsDarkTheme.current
    val bg = if (dark) Color(0xF01C1C1E) else WinLightCardTop.copy(alpha = 0.97f)
    val border = if (dark) Color.White.copy(alpha = 0.12f) else WinLightTextPrimary.copy(alpha = 0.14f)
    val tint = if (dark) WinTextPrimary else WinLightTextPrimary
    val danger = if (dark) WinDanger else WinLightDanger
    Row(
        modifier = modifier
            .background(bg, RoundedCornerShape(12.dp))
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        onSelect?.let {
            MenuIcon(Icons.Outlined.Add, stringResource(R.string.cd_select_for_system), tint, it)
        }
        MenuIcon(Icons.Outlined.ContentCopy, stringResource(R.string.cd_copy_latex), tint, onCopyLatex)
        MenuIcon(Icons.Outlined.Functions, stringResource(R.string.cd_import_latex), tint, onImportLatex)
        MenuIcon(Icons.Outlined.ContentPaste, stringResource(R.string.cd_paste_text), tint, onPasteText)
        SlotActions(tint, onCopySlot, onPasteSlot)
        onPlot?.let {
            MenuIcon(Icons.AutoMirrored.Outlined.ShowChart, stringResource(R.string.cd_plot_graph), tint, it)
        }
        MenuIcon(Icons.Outlined.Delete, stringResource(R.string.cd_delete_formula), danger, onDelete)
    }
}

/** Copy / paste that address one child slot instead of the whole block. */
@Composable
private fun SlotActions(
    tint: Color,
    onCopySlot: (() -> Unit)?,
    onPasteSlot: (() -> Unit)?,
) {
    if (onCopySlot == null && onPasteSlot == null) return
    Box(
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .size(width = 1.dp, height = 20.dp)
            .background(tint.copy(alpha = 0.2f)),
    )
    onCopySlot?.let {
        MenuIcon(Icons.Outlined.CopyAll, stringResource(R.string.cd_copy_slot), tint, it)
    }
    onPasteSlot?.let {
        MenuIcon(Icons.Outlined.ContentPasteGo, stringResource(R.string.cd_paste_slot), tint, it)
    }
}

@Composable
fun SystemContextMenu(
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onCopyLatex: () -> Unit,
    onCopyText: () -> Unit,
    onUngroup: () -> Unit,
    onAddEquation: () -> Unit,
    onPlot: (() -> Unit)? = null,
    /** Present only when the long press landed on a specific equation row. */
    onCopySlot: (() -> Unit)? = null,
    onPasteSlot: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val dark = LocalIsDarkTheme.current
    val bg = if (dark) Color(0xF01C1C1E) else WinLightCardTop.copy(alpha = 0.97f)
    val border = if (dark) Color.White.copy(alpha = 0.12f) else WinLightTextPrimary.copy(alpha = 0.14f)
    val tint = if (dark) WinTextPrimary else WinLightTextPrimary
    val danger = if (dark) WinDanger else WinLightDanger
    Row(
        modifier = modifier
            .background(bg, RoundedCornerShape(12.dp))
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MenuIcon(Icons.Outlined.Delete, stringResource(R.string.cd_system_delete), danger, onDelete)
        MenuIcon(Icons.Outlined.ContentCopy, stringResource(R.string.cd_system_copy), tint, onCopy)
        MenuIcon(Icons.Outlined.Functions, stringResource(R.string.cd_system_copy_latex), tint, onCopyLatex)
        MenuIcon(Icons.Outlined.TextFields, stringResource(R.string.cd_system_copy_text), tint, onCopyText)
        MenuIcon(Icons.Outlined.AccountTree, stringResource(R.string.cd_system_ungroup), tint, onUngroup)
        MenuIcon(Icons.Outlined.Add, stringResource(R.string.cd_system_add), tint, onAddEquation)
        onPlot?.let {
            MenuIcon(Icons.AutoMirrored.Outlined.ShowChart, stringResource(R.string.cd_plot_graph), tint, it)
        }
        SlotActions(tint, onCopySlot, onPasteSlot)
    }
}

@Composable
private fun MenuIcon(
    icon: ImageVector,
    description: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .pressableScale(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(18.dp))
    }
}
