package com.example.mobilemathstudio.ui.keyboard

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.mobilemathstudio.R
import com.example.mobilemathstudio.performance.Motion
import com.example.mobilemathstudio.ui.components.pressableScale
import com.example.mobilemathstudio.ui.glass.KeyboardGlassRegistry
import com.example.mobilemathstudio.ui.glass.LiquidGlassBackdropState
import com.example.mobilemathstudio.ui.glass.LiquidGlassPresets
import com.example.mobilemathstudio.ui.glass.floatingKeyboardLiquidGlass
import com.example.mobilemathstudio.ui.math.MathKeyGlyph
import com.example.mobilemathstudio.ui.math.MathKeyKind
import com.example.mobilemathstudio.ui.theme.LocalAccentColor
import com.example.mobilemathstudio.ui.theme.LocalAnimationsEnabled
import com.example.mobilemathstudio.ui.theme.LocalIsDarkTheme
import com.example.mobilemathstudio.ui.theme.LocalLiquidGlassEnabled
import com.example.mobilemathstudio.ui.theme.WinLightCardTop
import com.example.mobilemathstudio.ui.theme.WinLightDanger
import com.example.mobilemathstudio.ui.theme.WinLightGridLine
import com.example.mobilemathstudio.ui.theme.WinLightKeyDigit
import com.example.mobilemathstudio.ui.theme.WinLightKeyFunction
import com.example.mobilemathstudio.ui.theme.WinLightKeyUtility
import com.example.mobilemathstudio.ui.theme.WinLightSuccess
import com.example.mobilemathstudio.ui.theme.WinLightTextPrimary
import com.example.mobilemathstudio.ui.theme.WinTextPrimary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val KeyHeight = 46.dp
private val KeyRadius = 14.dp
private val KeyGap = 6.dp
private const val HOLD_MS = 280L

/** Tuned for dark keyboard surfaces. */
private val KeySuccessDark = Color(0xFF6CCB5F)
private val KeyDangerDark = Color(0xFFFF6B6B)

/**
 * Floating math keyboard docked at the bottom with a 100% transparent container (`Color.Transparent`):
 * no opaque dark plate sits behind the keys or collapse chevron, so the infinite canvas, formulas,
 * and graphs shine through both the gaps between keys and through the Liquid Glass keys themselves.
 *
 * GPU / 60 FPS architecture (tuned for Mali-G57 / Redmi Note 12S):
 * - Instead of 38×2 = 76 per-key `RenderEffect` passes, ALL floating keys + the collapse chevron
 *   are rendered in ONE batched 2-pass keyboard Liquid Glass engine (`floatingKeyboardLiquidGlass`):
 *   1 single `0.5×` downsampled Gaussian blur + batched Snell/dispersion/mirror pass, plus 1 single
 *   full-res sharp rim/highlight/Fresnel/shadow pass (`0.94 dp` rim, `51%` edge highlight, `15.9 dp`
 *   reflection axis, `59%` RGB dispersion) with transparent `half4(0)` in gaps.
 * - Preserves all 5 functional key group color filters in both dark and light themes.
 * - When Liquid Glass is disabled in Settings (or Power Saver), renders clean floating matte keys
 *   with zero `RenderEffect` and zero offscreen `GraphicsLayer` recording.
 */
@Composable
fun MathKeyboard(
    visible: Boolean,
    onAction: (KeyAction) -> Unit,
    nestedSlot: Boolean = false,
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    onCollapse: (() -> Unit)? = null,
    backdropState: LiquidGlassBackdropState? = null,
    modifier: Modifier = Modifier,
) {
    var layer by remember { mutableStateOf(KeyboardLayer.Standard) }
    var script by remember { mutableStateOf(LetterScript.Latin) }
    val accent = LocalAccentColor.current
    val dark = LocalIsDarkTheme.current
    val glassEnabled = LocalLiquidGlassEnabled.current
    val animationsEnabled = LocalAnimationsEnabled.current
    val density = LocalDensity.current
    val screenWidthPx = LocalView.current.resources.displayMetrics.widthPixels.toFloat()
    var heightPx by remember { mutableFloatStateOf(with(density) { 340.dp.toPx() }) }
    var hostCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var flyoutVisual by remember { mutableStateOf<FlyoutVisual?>(null) }
    val scrollState = rememberScrollState()
    val glassRegistry = remember { KeyboardGlassRegistry() }
    val keyOptics = remember(dark) { LiquidGlassPresets.keyboardKey(dark) }

    val hiddenFraction = remember { Animatable(if (visible) 0f else 1f) }
    LaunchedEffect(visible, animationsEnabled) {
        val target = if (visible) 0f else 1f
        if (animationsEnabled) {
            hiddenFraction.animateTo(target, Motion.spatial(true))
        } else {
            hiddenFraction.snapTo(target)
        }
    }
    val showPanel = visible || hiddenFraction.value < 0.999f
    if (!showPanel) return

    fun dispatch(action: KeyAction) {
        when (action) {
            is KeyAction.SwitchLayer -> layer = action.layer
            is KeyAction.SwitchScript -> script = action.script
            else -> onAction(action)
        }
    }

    val chevronRadiusPx = with(density) { 18.dp.toPx() }
    val chevronGlassTint = if (dark) Color(0xFF141822) else WinLightKeyFunction
    val chevronGlassAlpha = if (dark) 0.34f else 0.48f
    val chevronFallbackTop = if (dark) Color(0xE01C2029) else WinLightKeyDigit.copy(alpha = 0.94f)
    val chevronFallbackBottom = if (dark) Color(0xE013161D) else WinLightKeyFunction.copy(alpha = 0.94f)
    val chevronRimTop = if (dark) Color.White.copy(alpha = 0.22f) else Color(0xFFF9FBFE)
    val chevronRimBottom = if (dark) Color.White.copy(alpha = 0.05f) else WinLightTextPrimary.copy(alpha = 0.14f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .onGloballyPositioned { coords ->
                hostCoords = coords
                val measured = coords.size.height.toFloat()
                if (measured > 80f) heightPx = measured
            }
            .graphicsLayer { translationY = hiddenFraction.value * heightPx; clip = false }
            .floatingKeyboardLiquidGlass(
                backdropState = backdropState,
                registry = glassRegistry,
                params = keyOptics,
                glassEnabled = glassEnabled,
                hostCoordsProvider = { hostCoords },
                originYOffsetPxProvider = { hiddenFraction.value * heightPx },
            )
            .navigationBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Transparent)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            if (onCollapse != null) {
                var chevronCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
                DisposableEffect(Unit) {
                    onDispose { glassRegistry.remove("chevron") }
                }
                LaunchedEffect(hostCoords, chevronCoords, dark, glassEnabled) {
                    val cc = chevronCoords ?: return@LaunchedEffect
                    glassRegistry.update(
                        id = "chevron",
                        hostCoords = hostCoords,
                        childCoords = cc,
                        cornerRadiusPx = chevronRadiusPx,
                        glassTint = chevronGlassTint,
                        glassTintAlpha = chevronGlassAlpha,
                        fallbackFillTop = chevronFallbackTop,
                        fallbackFillBottom = chevronFallbackBottom,
                        fallbackRimTop = chevronRimTop,
                        fallbackRimBottom = chevronRimBottom,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Transparent)
                        .padding(start = 6.dp, bottom = 6.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    KeyboardChevron(
                        collapsed = false,
                        onClick = onCollapse,
                        modifier = Modifier
                            .onGloballyPositioned { coords ->
                                chevronCoords = coords
                                glassRegistry.update(
                                    id = "chevron",
                                    hostCoords = hostCoords,
                                    childCoords = coords,
                                    cornerRadiusPx = chevronRadiusPx,
                                    glassTint = chevronGlassTint,
                                    glassTintAlpha = chevronGlassAlpha,
                                    fallbackFillTop = chevronFallbackTop,
                                    fallbackFillBottom = chevronFallbackBottom,
                                    fallbackRimTop = chevronRimTop,
                                    fallbackRimBottom = chevronRimBottom,
                                )
                            }
                            .graphicsLayer {
                                alpha = (1f - hiddenFraction.value).coerceIn(0f, 1f)
                            },
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Transparent)
                    .heightIn(max = 380.dp)
                    .verticalScroll(scrollState)
                    // Consume accidental taps inside the 6 dp gaps between keys without painting any background.
                    .clickable(
                        enabled = visible,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                verticalArrangement = Arrangement.spacedBy(KeyGap),
            ) {
                val rows = when (layer) {
                    KeyboardLayer.Standard -> KeyboardLayouts.standard()
                    KeyboardLayer.Advanced -> KeyboardLayouts.advanced()
                    KeyboardLayer.Letters -> KeyboardLayouts.letters(script)
                }
                // Twelve Cyrillic letters per row only fit with a tighter gap.
                val rowGap = if (layer == KeyboardLayer.Letters) 4.dp else KeyGap

                rows.forEachIndexed { rowIndex, row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(rowGap),
                    ) {
                        row.forEachIndexed { colIndex, key ->
                            MathKey(
                                slotId = "$layer-$script-r$rowIndex-c$colIndex",
                                spec = key,
                                accent = accent,
                                dark = dark,
                                glassLook = glassEnabled,
                                registry = glassRegistry,
                                hostCoords = hostCoords,
                                scrollOffsetPx = scrollState.value,
                                enabled = keyEnabled(key.action, canUndo, canRedo),
                                screenWidthPx = screenWidthPx,
                                onClick = { dispatch(key.action) },
                                onFlyoutPick = { dispatch(it.action) },
                                onFlyoutVisual = { flyoutVisual = it },
                                onLongPress = exponentAction(key)?.let { token ->
                                    { dispatch(KeyAction.InsertExponent(token)) }
                                },
                            )
                        }
                        if (layer == KeyboardLayer.Letters && rowIndex == rows.lastIndex) {
                            MathKey(
                                slotId = "$layer-$script-globe",
                                spec = KeySpec(
                                    "🌐",
                                    KeyAction.SwitchScript(nextScript(script)),
                                    icon = KeyIcon.Globe,
                                    weight = 1.5f,
                                ),
                                accent = accent,
                                dark = dark,
                                glassLook = glassEnabled,
                                registry = glassRegistry,
                                hostCoords = hostCoords,
                                scrollOffsetPx = scrollState.value,
                                screenWidthPx = screenWidthPx,
                                onClick = { script = nextScript(script) },
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(KeyGap),
                ) {
                    KeyboardLayouts.utilityRow(layer, nestedSlot).forEachIndexed { utilIndex, key ->
                        MathKey(
                            slotId = "$layer-util-$nestedSlot-$utilIndex",
                            spec = key,
                            accent = accent,
                            dark = dark,
                            glassLook = glassEnabled,
                            registry = glassRegistry,
                            hostCoords = hostCoords,
                            scrollOffsetPx = scrollState.value,
                            enabled = keyEnabled(key.action, canUndo, canRedo),
                            screenWidthPx = screenWidthPx,
                            onClick = { dispatch(key.action) },
                            onFlyoutPick = { dispatch(it.action) },
                        )
                    }
                }
            }
        }

        flyoutVisual?.let { visual ->
            val host = hostCoords
            if (host != null) {
                val geom = placeComparisonFlyout(visual.keyScreen, visual.options.size, screenWidthPx, density)
                val local = host.screenToLocal(geom.screenOrigin)
                ComparisonFlyoutRow(
                    options = visual.options,
                    hoveredLabel = visual.hovered,
                    accent = accent,
                    dark = dark,
                    modifier = Modifier
                        .offset { IntOffset(local.x.roundToInt(), local.y.roundToInt()) }
                        .zIndex(24f)
                        .wrapContentWidth(unbounded = true),
                )
            }
        }
    }
}

private fun exponentAction(spec: KeySpec): String? {
    if (!spec.longPressExponent) return null
    val insert = spec.action as? KeyAction.Insert ?: return null
    return insert.token.takeIf { it.isNotEmpty() }
}

private fun keyEnabled(action: KeyAction, canUndo: Boolean, canRedo: Boolean): Boolean = when (action) {
    KeyAction.Undo -> canUndo
    KeyAction.Redo -> canRedo
    else -> true
}

private fun nextScript(script: LetterScript): LetterScript = when (script) {
    LetterScript.Latin -> LetterScript.Greek
    LetterScript.Greek -> LetterScript.Cyrillic
    LetterScript.Cyrillic -> LetterScript.Latin
}

/** Visual families: the eye finds digits, operators and functions by fill before reading. */
internal enum class KeyRole { Digit, Operator, Template, Function, Utility, Primary, Confirm, Danger }

private val OPERATOR_LABELS = setOf("+", "−", "×", "÷", "=", "%", "(", ")", "()", "[", "]")
private val UPRIGHT_SYMBOLS = setOf("π", "Σ")
private val TEMPLATE_ICONS = setOf(KeyIcon.Fraction, KeyIcon.Power, KeyIcon.Sqrt, KeyIcon.NthRoot, KeyIcon.System)

internal fun keyRole(spec: KeySpec): KeyRole = when {
    spec.danger -> KeyRole.Danger
    spec.icon == KeyIcon.Check -> KeyRole.Confirm
    spec.accent -> KeyRole.Primary
    spec.icon in TEMPLATE_ICONS -> KeyRole.Template
    spec.icon != KeyIcon.None -> KeyRole.Utility
    spec.label == "AC" -> KeyRole.Utility
    spec.label in OPERATOR_LABELS -> KeyRole.Operator
    spec.label.all { it.isDigit() || it == '.' || it == ',' } -> KeyRole.Digit
    else -> KeyRole.Function
}

internal data class KeyPalette(
    val fill: Color,
    val fillBottom: Color,
    val rimTop: Color,
    val rimBottom: Color,
    val content: Color,
    val glassTint: Color,
    val glassTintAlpha: Float,
)

/**
 * Darkens user accent slightly when used as foreground text on soft light keycaps so bright
 * accents maintain comfortable reading contrast without glare.
 */
internal fun darkenForLightInk(color: Color): Color {
    val factor = 0.68f
    return Color(
        red = (color.red * factor).coerceIn(0f, 1f),
        green = (color.green * factor).coerceIn(0f, 1f),
        blue = (color.blue * factor).coerceIn(0f, 1f),
        alpha = 1f,
    )
}

private fun blendOpaque(base: Color, tint: Color, fraction: Float): Color {
    val t = fraction.coerceIn(0f, 1f)
    val inv = 1f - t
    return Color(
        red = (base.red * inv + tint.red * t).coerceIn(0f, 1f),
        green = (base.green * inv + tint.green * t).coerceIn(0f, 1f),
        blue = (base.blue * inv + tint.blue * t).coerceIn(0f, 1f),
        alpha = 1f,
    )
}

/**
 * Resolves both the translucent Liquid Glass color filter (`glassTint` + `glassTintAlpha`) and
 * the flat/scrim fallback palette for each of the 5 functional key groups across Dark and
 * cool slate-blue Light themes.
 */
internal fun keyPalette(
    role: KeyRole,
    accent: Color,
    dark: Boolean,
    glassLook: Boolean,
): KeyPalette {
    val primaryContent = if (accent.luminance() > 0.45f) WinLightTextPrimary else Color(0xFFF4F7FB)
    if (dark) {
        return when (role) {
            KeyRole.Digit -> KeyPalette(
                fill = if (glassLook) Color(0xFF2B303A) else Color(0xFF282B31),
                fillBottom = Color(0xFF1F232B),
                rimTop = Color.White.copy(alpha = 0.24f),
                rimBottom = Color.White.copy(alpha = 0.05f),
                content = WinTextPrimary,
                glassTint = Color(0xFFDDE6F5),
                glassTintAlpha = 0.14f,
            )
            KeyRole.Operator -> KeyPalette(
                fill = blendOpaque(Color(0xFF1C2029), accent, 0.28f),
                fillBottom = blendOpaque(Color(0xFF141821), accent, 0.22f),
                rimTop = accent.copy(alpha = 0.56f),
                rimBottom = accent.copy(alpha = 0.14f),
                content = Color.White,
                glassTint = accent,
                glassTintAlpha = 0.28f,
            )
            KeyRole.Template, KeyRole.Function -> KeyPalette(
                fill = if (glassLook) Color(0xFF1E222A) else Color(0xFF1C1F25),
                fillBottom = Color(0xFF161920),
                rimTop = Color.White.copy(alpha = 0.17f),
                rimBottom = Color.White.copy(alpha = 0.04f),
                content = WinTextPrimary,
                glassTint = Color(0xFF9FB2CC),
                glassTintAlpha = 0.08f,
            )
            KeyRole.Utility -> KeyPalette(
                fill = Color(0xFF14171D),
                fillBottom = Color(0xFF101218),
                rimTop = Color.White.copy(alpha = 0.12f),
                rimBottom = Color.Transparent,
                content = WinTextPrimary.copy(alpha = 0.86f),
                glassTint = Color(0xFF0A0D14),
                glassTintAlpha = 0.44f,
            )
            KeyRole.Primary -> KeyPalette(
                fill = accent,
                fillBottom = blendOpaque(accent, Color.Black, 0.16f),
                rimTop = Color.White.copy(alpha = 0.36f),
                rimBottom = Color.Black.copy(alpha = 0.20f),
                content = primaryContent,
                glassTint = accent,
                glassTintAlpha = 0.74f,
            )
            KeyRole.Confirm -> KeyPalette(
                fill = blendOpaque(Color(0xFF18201B), KeySuccessDark, 0.24f),
                fillBottom = blendOpaque(Color(0xFF121A15), KeySuccessDark, 0.18f),
                rimTop = KeySuccessDark.copy(alpha = 0.54f),
                rimBottom = KeySuccessDark.copy(alpha = 0.12f),
                content = KeySuccessDark,
                glassTint = KeySuccessDark,
                glassTintAlpha = 0.28f,
            )
            KeyRole.Danger -> KeyPalette(
                fill = blendOpaque(Color(0xFF22181A), KeyDangerDark, 0.20f),
                fillBottom = blendOpaque(Color(0xFF1A1214), KeyDangerDark, 0.14f),
                rimTop = KeyDangerDark.copy(alpha = 0.48f),
                rimBottom = KeyDangerDark.copy(alpha = 0.10f),
                content = KeyDangerDark,
                glassTint = KeyDangerDark,
                glassTintAlpha = 0.24f,
            )
        }
    }

    // Calm cool blue-gray light theme (#F2F5F9 digits, #E1E7F0 functions/operators, #CFD7E4 utility, #161B22 ink)
    val lightOperatorInk = darkenForLightInk(accent)
    val lightOperatorFill = blendOpaque(WinLightKeyFunction, accent, 0.16f)
    val lightOperatorGlass = blendOpaque(WinLightKeyFunction, accent, 0.30f)
    return when (role) {
        KeyRole.Digit -> KeyPalette(
            fill = WinLightKeyDigit,
            fillBottom = Color(0xFFE9EEF5),
            rimTop = Color(0xFFF9FBFE),
            rimBottom = WinLightTextPrimary.copy(alpha = 0.14f),
            content = WinLightTextPrimary,
            glassTint = WinLightKeyDigit,
            glassTintAlpha = 0.50f,
        )
        KeyRole.Operator -> KeyPalette(
            fill = lightOperatorFill,
            fillBottom = blendOpaque(Color(0xFFD7DFEC), accent, 0.14f),
            rimTop = accent.copy(alpha = 0.32f),
            rimBottom = accent.copy(alpha = 0.14f),
            content = lightOperatorInk,
            glassTint = lightOperatorGlass,
            glassTintAlpha = 0.46f,
        )
        KeyRole.Template, KeyRole.Function -> KeyPalette(
            fill = WinLightKeyFunction,
            fillBottom = Color(0xFFD7DFEB),
            rimTop = Color(0xFFF4F7FB),
            rimBottom = WinLightTextPrimary.copy(alpha = 0.12f),
            content = WinLightTextPrimary,
            glassTint = WinLightKeyFunction,
            glassTintAlpha = 0.42f,
        )
        KeyRole.Utility -> KeyPalette(
            fill = WinLightKeyUtility,
            fillBottom = Color(0xFFC4CEDD),
            rimTop = Color(0xFFE9EEF6),
            rimBottom = WinLightTextPrimary.copy(alpha = 0.12f),
            content = Color(0xFF2E3746),
            glassTint = WinLightKeyUtility,
            glassTintAlpha = 0.52f,
        )
        KeyRole.Primary -> KeyPalette(
            fill = accent,
            fillBottom = blendOpaque(accent, Color.Black, 0.14f),
            rimTop = Color.White.copy(alpha = 0.34f),
            rimBottom = WinLightTextPrimary.copy(alpha = 0.20f),
            content = primaryContent,
            glassTint = accent,
            glassTintAlpha = 0.80f,
        )
        KeyRole.Confirm -> KeyPalette(
            fill = blendOpaque(WinLightKeyFunction, WinLightSuccess, 0.16f),
            fillBottom = blendOpaque(Color(0xFFD6DFEB), WinLightSuccess, 0.14f),
            rimTop = WinLightSuccess.copy(alpha = 0.34f),
            rimBottom = WinLightSuccess.copy(alpha = 0.14f),
            content = WinLightSuccess,
            glassTint = blendOpaque(WinLightKeyFunction, WinLightSuccess, 0.30f),
            glassTintAlpha = 0.44f,
        )
        KeyRole.Danger -> KeyPalette(
            fill = blendOpaque(WinLightKeyFunction, WinLightDanger, 0.13f),
            fillBottom = blendOpaque(Color(0xFFD6DFEB), WinLightDanger, 0.11f),
            rimTop = WinLightDanger.copy(alpha = 0.32f),
            rimBottom = WinLightDanger.copy(alpha = 0.12f),
            content = WinLightDanger,
            glassTint = blendOpaque(WinLightKeyFunction, WinLightDanger, 0.26f),
            glassTintAlpha = 0.42f,
        )
    }
}

@Composable
private fun RowScope.MathKey(
    slotId: String,
    spec: KeySpec,
    accent: Color,
    dark: Boolean,
    glassLook: Boolean,
    registry: KeyboardGlassRegistry,
    hostCoords: LayoutCoordinates?,
    scrollOffsetPx: Int,
    onClick: () -> Unit,
    enabled: Boolean = true,
    screenWidthPx: Float = 0f,
    onFlyoutPick: (KeySpec) -> Unit = { onClick() },
    onFlyoutVisual: (FlyoutVisual?) -> Unit = {},
    onLongPress: (() -> Unit)? = null,
) {
    val keyKind = keyRole(spec)
    val colors = remember(keyKind, accent, dark, glassLook) {
        keyPalette(keyKind, accent, dark, glassLook)
    }
    val description = when (spec.icon) {
        KeyIcon.Undo -> stringResource(R.string.cd_undo)
        KeyIcon.Redo -> stringResource(R.string.cd_redo)
        KeyIcon.ClearCanvas -> stringResource(R.string.cd_clear_canvas)
        else -> spec.label
    }
    val view = LocalView.current
    val density = LocalDensity.current
    val keyRadiusPx = with(density) { KeyRadius.toPx() }
    val animationsEnabled = LocalAnimationsEnabled.current
    val keyScope = rememberCoroutineScope()
    val hasFlyout = spec.flyout.isNotEmpty()
    val currentClick by rememberUpdatedState(onClick)
    val currentLongPress by rememberUpdatedState(onLongPress)
    val currentPick by rememberUpdatedState(onFlyoutPick)
    val currentVisual by rememberUpdatedState(onFlyoutVisual)
    var pressed by remember { mutableStateOf(false) }
    var flyoutOpen by remember { mutableStateOf(false) }
    var keyCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val press by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = Motion.effects(animationsEnabled, durationMillis = if (pressed) 40 else 140),
        label = "key-press",
    )

    DisposableEffect(slotId) {
        onDispose { registry.remove(slotId) }
    }

    LaunchedEffect(slotId, hostCoords, keyCoords, scrollOffsetPx, colors, enabled) {
        val kc = keyCoords ?: return@LaunchedEffect
        registry.update(
            id = slotId,
            hostCoords = hostCoords,
            childCoords = kc,
            cornerRadiusPx = keyRadiusPx,
            glassTint = colors.glassTint,
            glassTintAlpha = colors.glassTintAlpha,
            fallbackFillTop = colors.fill,
            fallbackFillBottom = colors.fillBottom,
            fallbackRimTop = colors.rimTop,
            fallbackRimBottom = colors.rimBottom,
            contentAlpha = if (enabled) 1f else 0.38f,
        )
    }

    fun publishFlyout(open: Boolean, hover: String?) {
        val keyScreen = keyCoords?.screenRect()
        currentVisual(if (open && keyScreen != null) FlyoutVisual(spec.flyout, keyScreen, hover) else null)
    }

    fun pickAt(screen: Offset): KeySpec? {
        val keyScreen = keyCoords?.screenRect()
        if (flyoutOpen && keyScreen != null) {
            val geom = placeComparisonFlyout(keyScreen, spec.flyout.size, screenWidthPx, density)
            spec.flyout.forEachIndexed { index, opt ->
                if (geom.cellRect(index).inflate(10f).contains(screen)) return opt
            }
        }
        return if (keyScreen?.contains(screen) == true) spec else null
    }

    val pressFlashColor = if (dark) Color.White else WinLightTextPrimary
    val pressFlashPeak = if (dark) 0.22f else 0.12f

    Box(
        modifier = Modifier
            .weight(spec.weight)
            .height(KeyHeight)
            .zIndex(if (flyoutOpen) 8f else 0f)
            .onGloballyPositioned { coords ->
                keyCoords = coords
                registry.update(
                    id = slotId,
                    hostCoords = hostCoords,
                    childCoords = coords,
                    cornerRadiusPx = keyRadiusPx,
                    glassTint = colors.glassTint,
                    glassTintAlpha = colors.glassTintAlpha,
                    fallbackFillTop = colors.fill,
                    fallbackFillBottom = colors.fillBottom,
                    fallbackRimTop = colors.rimTop,
                    fallbackRimBottom = colors.rimBottom,
                    contentAlpha = if (enabled) 1f else 0.38f,
                )
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
                if (!enabled) disabled()
                onClick { currentClick(); true }
            }
            // Fast tactile press flash on top of the batched glass keycap (zero shader invalidation!).
            .drawBehind {
                if (press > 0.001f) {
                    val r = keyRadiusPx.coerceAtMost(minOf(size.width, size.height) * 0.5f)
                    drawRoundRect(
                        color = pressFlashColor.copy(alpha = pressFlashPeak * press),
                        cornerRadius = CornerRadius(r, r),
                    )
                }
            }
            .graphicsLayer {
                val s = if (animationsEnabled) 1f - 0.05f * press else 1f
                scaleX = s
                scaleY = s
            }
            .pointerInput(spec, enabled) {
                if (!enabled) return@pointerInput
                val slop = 12.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    pressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    var held = false
                    val hold = if (hasFlyout || currentLongPress != null) {
                        keyScope.launch {
                            delay(HOLD_MS)
                            held = true
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            if (hasFlyout) {
                                flyoutOpen = true
                                publishFlyout(true, null)
                            }
                        }
                    } else {
                        null
                    }
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            val screen = keyCoords?.localToScreen(change.position) ?: change.position
                            if (held && hasFlyout) publishFlyout(true, pickAt(screen)?.label)
                            if (change.changedToUpIgnoreConsumed()) {
                                hold?.cancel()
                                val p = change.position
                                val inside = p.x >= -slop && p.y >= -slop &&
                                    p.x <= size.width + slop && p.y <= size.height + slop
                                when {
                                    held && hasFlyout -> {
                                        val picked = pickAt(screen)
                                        if (picked != null && picked !== spec) currentPick(picked)
                                        else if (picked === spec) currentClick()
                                    }
                                    held -> currentLongPress?.invoke()
                                    inside -> currentClick()
                                }
                                break
                            }
                            if (!change.pressed) break
                        }
                    } finally {
                        hold?.cancel()
                        pressed = false
                        if (flyoutOpen) {
                            flyoutOpen = false
                            publishFlyout(false, null)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.alpha(if (enabled) 1f else 0.38f)) {
            KeyContent(spec, keyKind, colors.content)
        }
    }
}

@Composable
private fun KeyContent(spec: KeySpec, role: KeyRole, color: Color) {
    when (spec.icon) {
        KeyIcon.None -> {
            val label = spec.label
            val singleLetter = label.codePointCount(0, label.length) == 1 && Character.isLetter(label.codePointAt(0))
            val italicVariable = singleLetter && label !in UPRIGHT_SYMBOLS
            val functionName = role == KeyRole.Function && !singleLetter && label.any { it.isLetter() }
            KeyLabel(
                text = label,
                color = color,
                size = when {
                    role == KeyRole.Digit -> 22.sp
                    role == KeyRole.Operator -> if (label.length > 1) 18.sp else 22.sp
                    role == KeyRole.Utility -> 15.sp
                    italicVariable -> 21.sp
                    functionName && label.length > 3 -> 14.sp
                    functionName -> 16.sp
                    else -> 19.sp
                },
                weight = when (role) {
                    KeyRole.Digit, KeyRole.Operator -> FontWeight.Normal
                    KeyRole.Utility -> FontWeight.SemiBold
                    else -> FontWeight.Medium
                },
                serif = role == KeyRole.Function,
                italic = italicVariable,
            )
        }
        KeyIcon.Send -> PaperPlaneIcon(color)
        KeyIcon.Backspace -> KeyLabel("⌫", color, 19.sp)
        KeyIcon.ArrowLeft -> KeyLabel("←", color, 19.sp)
        KeyIcon.ArrowRight -> KeyLabel("→", color, 19.sp)
        KeyIcon.Layer -> KeyLabel("f/∫", color, 15.sp, serif = true, italic = true)
        KeyIcon.Globe -> KeyLabel("🌐", color, 17.sp)
        KeyIcon.Fraction -> MathKeyGlyph(MathKeyKind.Fraction, color)
        KeyIcon.Power -> MathKeyGlyph(MathKeyKind.Power, color)
        KeyIcon.Sqrt -> MathKeyGlyph(MathKeyKind.Sqrt, color)
        KeyIcon.NthRoot -> MathKeyGlyph(MathKeyKind.NthRoot, color)
        KeyIcon.System -> KeyLabel("{", color, 24.sp, weight = FontWeight.Light)
        KeyIcon.Check -> KeyLabel("✓", color, 20.sp, weight = FontWeight.Bold)
        KeyIcon.Undo -> Icon(Icons.Outlined.History, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        KeyIcon.Redo -> Icon(Icons.Outlined.Update, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        KeyIcon.ClearCanvas -> Icon(Icons.Outlined.DeleteSweep, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun KeyLabel(
    text: String,
    color: Color,
    size: TextUnit,
    weight: FontWeight = FontWeight.Medium,
    serif: Boolean = false,
    italic: Boolean = false,
) {
    Text(
        text = text,
        color = color,
        fontSize = size,
        fontWeight = weight,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        fontFamily = if (serif) FontFamily.Serif else FontFamily.SansSerif,
        maxLines = 1,
        softWrap = false,
    )
}

private data class FlyoutVisual(
    val options: List<KeySpec>,
    val keyScreen: Rect,
    val hovered: String?,
)

private data class FlyoutGeom(
    val screenOrigin: Offset,
    val cell: Float,
    val gap: Float,
    val pad: Float,
    val count: Int,
) {
    fun cellRect(index: Int): Rect {
        val x = screenOrigin.x + pad + index * (cell + gap)
        val y = screenOrigin.y + pad
        return Rect(x, y, x + cell, y + cell)
    }
}

private fun placeComparisonFlyout(
    keyScreen: Rect,
    count: Int,
    screenWidthPx: Float,
    density: Density,
): FlyoutGeom {
    val cell = with(density) { 48.dp.toPx() }
    val gap = with(density) { 6.dp.toPx() }
    val pad = with(density) { 6.dp.toPx() }
    val height = cell + pad * 2
    val width = pad * 2 + count * cell + (count - 1).coerceAtLeast(0) * gap
    val maxX = (screenWidthPx - width - 8f).coerceAtLeast(8f)
    val x = (keyScreen.center.x - width / 2f).coerceIn(8f, maxX)
    val y = (keyScreen.top - height - with(density) { 8.dp.toPx() }).coerceAtLeast(8f)
    return FlyoutGeom(Offset(x, y), cell, gap, pad, count)
}

@Composable
private fun ComparisonFlyoutRow(
    options: List<KeySpec>,
    hoveredLabel: String?,
    accent: Color,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    val bg = if (dark) Color(0xF51C1C1E) else WinLightCardTop
    val border = if (dark) Color.White.copy(alpha = 0.16f) else WinLightGridLine
    val idleCellBg = if (dark) Color.White.copy(alpha = 0.08f) else WinLightKeyFunction
    val hotCellBg = if (dark) accent.copy(alpha = 0.55f) else accent.copy(alpha = 0.22f)
    val textColor = if (dark) WinTextPrimary else WinLightTextPrimary

    Row(
        modifier = modifier
            .wrapContentWidth(unbounded = true)
            .border(1.dp, border, RoundedCornerShape(16.dp))
            .background(bg, RoundedCornerShape(16.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { opt ->
            val hot = hoveredLabel == opt.label
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        if (hot) hotCellBg else idleCellBg,
                        RoundedCornerShape(12.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = opt.label,
                    color = if (hot && !dark) darkenForLightInk(accent) else textColor,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Normal,
                )
            }
        }
    }
}

private fun LayoutCoordinates.screenRect(): Rect {
    val tl = localToScreen(Offset.Zero)
    val br = localToScreen(Offset(size.width.toFloat(), size.height.toFloat()))
    return Rect(tl, br)
}

@Composable
private fun PaperPlaneIcon(tint: Color) {
    Canvas(modifier = Modifier.size(20.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.08f, size.height * 0.55f)
            lineTo(size.width * 0.92f, size.height * 0.18f)
            lineTo(size.width * 0.42f, size.height * 0.88f)
            lineTo(size.width * 0.38f, size.height * 0.55f)
            close()
        }
        drawPath(path, color = tint)
        drawLine(
            color = tint,
            start = Offset(size.width * 0.38f, size.height * 0.55f),
            end = Offset(size.width * 0.70f, size.height * 0.38f),
            strokeWidth = 1.6.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun KeyboardChevron(
    collapsed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = LocalIsDarkTheme.current
    val rotation by animateFloatAsState(
        targetValue = if (collapsed) 180f else 0f,
        animationSpec = Motion.spatial(LocalAnimationsEnabled.current),
        label = "keyboard-chevron",
    )
    val description = stringResource(
        if (collapsed) R.string.cd_expand_keyboard else R.string.cd_collapse_keyboard,
    )
    val strokeColor = if (dark) WinTextPrimary else WinLightTextPrimary.copy(alpha = 0.82f)
    Box(
        modifier = modifier
            .size(36.dp)
            .semantics { contentDescription = description }
            .graphicsLayer { rotationZ = rotation }
            .pressableScale(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(16.dp)) {
            val stroke = 2.2.dp.toPx()
            drawLine(
                color = strokeColor,
                start = Offset(size.width * 0.12f, size.height * 0.32f),
                end = Offset(size.width * 0.5f, size.height * 0.72f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = strokeColor,
                start = Offset(size.width * 0.88f, size.height * 0.32f),
                end = Offset(size.width * 0.5f, size.height * 0.72f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}
