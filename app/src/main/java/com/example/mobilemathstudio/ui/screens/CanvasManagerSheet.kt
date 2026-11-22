package com.example.mobilemathstudio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.mobilemathstudio.R
import com.example.mobilemathstudio.ui.theme.LocalIsDarkTheme
import com.example.mobilemathstudio.ui.theme.WinDanger
import com.example.mobilemathstudio.ui.theme.WinLightDanger
import com.example.mobilemathstudio.ui.theme.WinLightSurface
import com.example.mobilemathstudio.ui.theme.WinLightTextMuted
import com.example.mobilemathstudio.ui.theme.WinLightTextPrimary
import com.example.mobilemathstudio.ui.theme.WinLightTextSecondary
import com.example.mobilemathstudio.ui.theme.WinTextMuted
import com.example.mobilemathstudio.ui.theme.WinTextPrimary
import com.example.mobilemathstudio.ui.theme.WinTextSecondary

data class CanvasItem(
    val id: String,
    val name: String,
    val updatedLabel: String,
    val pinned: Boolean = false,
)

@Composable
fun CanvasManagerContent(
    canvases: List<CanvasItem>,
    activeId: String?,
    onSelect: (CanvasItem) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (CanvasItem, String) -> Unit = { _, _ -> },
    onDuplicate: (CanvasItem) -> Unit = {},
    onTogglePin: (CanvasItem) -> Unit = {},
    onDelete: (CanvasItem) -> Unit = {},
    rootModifier: Modifier = Modifier,
) {
    val dark = LocalIsDarkTheme.current
    val primary = if (dark) WinTextPrimary else WinLightTextPrimary
    val secondary = if (dark) WinTextSecondary else WinLightTextSecondary
    val muted = if (dark) WinTextMuted else WinLightTextMuted

    var showCreateDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<CanvasItem?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<CanvasItem?>(null) }

    Column(
        modifier = rootModifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.canvas_manager_title),
            style = MaterialTheme.typography.titleLarge,
            color = primary,
        )
        Text(
            text = stringResource(R.string.canvas_manager_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                .clickable {
                    newName = ""
                    showCreateDialog = true
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                text = stringResource(R.string.canvas_create),
                style = MaterialTheme.typography.titleMedium,
                color = primary,
            )
        }

        Text(
            text = stringResource(R.string.canvas_recent),
            style = MaterialTheme.typography.labelLarge,
            color = secondary,
            modifier = Modifier.padding(top = 4.dp),
        )

        if (canvases.isEmpty()) {
            Text(
                text = stringResource(R.string.canvas_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            canvases.forEach { canvas ->
                CanvasRow(
                    canvas = canvas,
                    active = canvas.id == activeId,
                    dark = dark,
                    primary = primary,
                    secondary = secondary,
                    muted = muted,
                    onSelect = { onSelect(canvas) },
                    onRenameRequest = {
                        renameValue = canvas.name
                        renameTarget = canvas
                    },
                    onDuplicate = { onDuplicate(canvas) },
                    onTogglePin = { onTogglePin(canvas) },
                    onDeleteRequest = { deleteTarget = canvas },
                )
            }
        }
    }

    if (showCreateDialog) {
        NameDialog(
            titleRes = R.string.canvas_create_dialog_title,
            confirmRes = R.string.action_create,
            value = newName,
            onValueChange = { newName = it },
            onConfirm = {
                onCreate(newName.trim())
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }

    renameTarget?.let { target ->
        NameDialog(
            titleRes = R.string.canvas_rename_dialog_title,
            confirmRes = R.string.action_save,
            value = renameValue,
            onValueChange = { renameValue = it },
            onConfirm = {
                onRename(target, renameValue.trim())
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.canvas_delete_dialog_title)) },
            text = { Text(stringResource(R.string.canvas_delete_dialog_body, target.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(target)
                        deleteTarget = null
                    },
                ) {
                    Text(stringResource(R.string.action_delete), color = if (dark) WinDanger else WinLightDanger)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun CanvasRow(
    canvas: CanvasItem,
    active: Boolean,
    dark: Boolean,
    primary: Color,
    secondary: Color,
    muted: Color,
    onSelect: () -> Unit,
    onRenameRequest: () -> Unit,
    onDuplicate: () -> Unit,
    onTogglePin: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val highlight = if (dark) Color.White.copy(alpha = 0.08f) else WinLightTextPrimary.copy(alpha = 0.06f)
    val danger = if (dark) WinDanger else WinLightDanger

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) highlight else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = if (canvas.pinned) Icons.Outlined.PushPin else Icons.Outlined.Layers,
            contentDescription = null,
            tint = if (canvas.pinned) MaterialTheme.colorScheme.primary else secondary,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(canvas.name, style = MaterialTheme.typography.bodyLarge, color = primary)
            Text(canvas.updatedLabel, style = MaterialTheme.typography.labelMedium, color = muted)
        }

        Box {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { menuOpen = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = stringResource(R.string.cd_canvas_more),
                    tint = secondary,
                    modifier = Modifier.size(20.dp),
                )
            }

            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = if (dark) Color(0xFF232326) else WinLightSurface,
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.canvas_rename), color = primary) },
                    leadingIcon = { Icon(Icons.Outlined.Edit, null, tint = secondary) },
                    onClick = {
                        menuOpen = false
                        onRenameRequest()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.canvas_duplicate), color = primary) },
                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, null, tint = secondary) },
                    onClick = {
                        menuOpen = false
                        onDuplicate()
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(
                                if (canvas.pinned) R.string.canvas_unpin else R.string.canvas_pin,
                            ),
                            color = primary,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.PushPin,
                            null,
                            tint = if (canvas.pinned) MaterialTheme.colorScheme.primary else secondary,
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onTogglePin()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.canvas_delete), color = danger) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = danger) },
                    onClick = {
                        menuOpen = false
                        onDeleteRequest()
                    },
                )
            }
        }
    }
}

@Composable
private fun NameDialog(
    titleRes: Int,
    confirmRes: Int,
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                label = { Text(stringResource(R.string.canvas_name_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = value.trim().isNotEmpty()) {
                Text(stringResource(confirmRes))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
