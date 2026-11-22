package com.example.mobilemathstudio.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.mobilemathstudio.R
import com.example.mobilemathstudio.ui.glass.LiquidGlassBackdropState
import com.example.mobilemathstudio.ui.glass.LiquidGlassPresets
import com.example.mobilemathstudio.ui.glass.LiquidGlassSurface
import com.example.mobilemathstudio.ui.theme.LocalIsDarkTheme
import com.example.mobilemathstudio.ui.theme.LocalLiquidGlassEnabled
import com.example.mobilemathstudio.ui.theme.WinTextPrimary

/**
 * Floating dark-glass island: Settings · LaTeX import · Export · Canvas manager.
 *
 * Uses `Dark Glass = true` in BOTH dark and light themes so the island carries a refined
 * smoky-dark glass tone with crisp light icons (`#F3F3F3`) over both dark and `#E8ECF2` light
 * canvases. When Liquid Glass is disabled in Settings, renders a fast Compose gradient pill
 * with zero `RuntimeShader` and zero `RenderEffect`.
 */
@Composable
fun FloatingTopBar(
    backdropState: LiquidGlassBackdropState,
    onOpenSettings: () -> Unit,
    onOpenCanvasManager: () -> Unit,
    onImportLatex: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = LocalIsDarkTheme.current
    val glassEnabled = LocalLiquidGlassEnabled.current
    val glassParams = remember { LiquidGlassPresets.floatingTopBar(dark = true) }
    // Dark Glass is enabled in both themes -> always use crisp light icons.
    val iconTint = WinTextPrimary
    // On the light #E8ECF2 canvas, add a noble smoky-obsidian tint inside the glass body
    // (between Pass 1 blur/refraction and Pass 2 specular rim) so light icons stay high-contrast
    // while formulas & graphs still refract cleanly through the dark glass.
    val smokyOverlayColor = if (dark) Color.Transparent else Color(0xFF131924).copy(alpha = 0.62f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        val barModifier = Modifier
            .fillMaxWidth()
            .align(Alignment.TopCenter)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )

        if (glassEnabled) {
            LiquidGlassSurface(
                backdropState = backdropState,
                params = glassParams,
                drawOuterShadow = true,
                glassTintOverlay = if (smokyOverlayColor.alpha > 0f) {
                    { drawRect(color = smokyOverlayColor) }
                } else {
                    null
                },
                modifier = barModifier,
            ) {
                TopBarIconsRow(
                    iconTint = iconTint,
                    onOpenSettings = onOpenSettings,
                    onImportLatex = onImportLatex,
                    onExport = onExport,
                    onOpenCanvasManager = onOpenCanvasManager,
                )
            }
        } else {
            // Liquid Glass disabled: pure fast Compose dark smoked pill (0 RuntimeShader / 0 RenderEffect).
            Box(
                modifier = barModifier
                    .clip(RoundedCornerShape(percent = 50))
                    .drawWithCache {
                        val r = size.height * 0.5f
                        val corner = CornerRadius(r, r)
                        val bodyBrush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0xEE1F2633),
                                Color(0xEE141923),
                            ),
                        )
                        val borderBrush = Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.26f),
                                Color.White.copy(alpha = 0.08f),
                                Color.Black.copy(alpha = 0.28f),
                            ),
                            start = Offset.Zero,
                            end = Offset(size.width, size.height),
                        )
                        val stroke = Stroke(width = 1.dp.toPx())
                        val half = stroke.width * 0.5f
                        onDrawBehind {
                            drawRoundRect(brush = bodyBrush, cornerRadius = corner)
                            drawRoundRect(
                                brush = borderBrush,
                                topLeft = Offset(half, half),
                                size = size.copy(
                                    width = (size.width - stroke.width).coerceAtLeast(1f),
                                    height = (size.height - stroke.width).coerceAtLeast(1f),
                                ),
                                cornerRadius = CornerRadius((r - half).coerceAtLeast(0f)),
                                style = stroke,
                            )
                        }
                    },
            ) {
                TopBarIconsRow(
                    iconTint = iconTint,
                    onOpenSettings = onOpenSettings,
                    onImportLatex = onImportLatex,
                    onExport = onExport,
                    onOpenCanvasManager = onOpenCanvasManager,
                )
            }
        }
    }
}

@Composable
private fun TopBarIconsRow(
    iconTint: Color,
    onOpenSettings: () -> Unit,
    onImportLatex: () -> Unit,
    onExport: () -> Unit,
    onOpenCanvasManager: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopBarSlot {
            GlassIconButton(
                icon = Icons.Outlined.Menu,
                contentDescription = stringResource(R.string.cd_settings),
                tint = iconTint,
                onClick = onOpenSettings,
            )
        }
        TopBarSlot {
            GlassIconButton(
                icon = Icons.Outlined.Functions,
                contentDescription = stringResource(R.string.cd_import_latex),
                tint = iconTint,
                onClick = onImportLatex,
            )
        }
        TopBarSlot {
            GlassIconButton(
                icon = Icons.Outlined.IosShare,
                contentDescription = stringResource(R.string.cd_export),
                tint = iconTint,
                onClick = onExport,
            )
        }
        TopBarSlot {
            GlassIconButton(
                icon = Icons.Outlined.Dashboard,
                contentDescription = stringResource(R.string.cd_canvas_manager),
                tint = iconTint,
                onClick = onOpenCanvasManager,
            )
        }
    }
}

@Composable
private fun RowScope.TopBarSlot(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.weight(1f),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun GlassIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 44.dp,
) {
    Box(
        modifier = Modifier
            .size(size)
            .pressableScale(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}
