package com.example.mobilemathstudio.ui.glass

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * Shared real-time backdrop capture & shader cache host for the floating top bar.
 *
 * Mali-G57 / 60 FPS optimizations:
 * 1) Records only the live workspace into ONE hardware-backed `backdropLayer` (`RenderNode`) and
 *    NEVER runs a full-screen 1080×2400 blur — only the compact `FloatingTopBar` strip runs
 *    cropped blur inside its own bounded layer (>12× less fragment bandwidth!).
 * 2) When Liquid Glass is disabled in Settings (or `GlassQuality.Scrim` is active), skips
 *    `backdropLayer.record(...)` completely (zero offscreen GPU pass).
 * 3) Compiles the two API 33 AGSL `RuntimeShader`s lazily ONCE, never touching `RuntimeShader`
 *    on API < 33 or when disabled.
 */
@Stable
class LiquidGlassBackdropState(
    val backdropLayer: GraphicsLayer,
    initialGroundColor: Color = Color(0xFF0F0F10),
) {
    var rootCoordinates: LayoutCoordinates? by mutableStateOf(null)
        internal set

    var rootSizePx: IntSize by mutableStateOf(IntSize.Zero)
        internal set

    var opaqueGroundColor: Color by mutableStateOf(initialGroundColor)

    /**
     * Shared API 33 AGSL renderer allocated lazily ONLY when `GlassQuality.Full` is active on
     * Android 13+. Typed as `Any?` so ART on API < 33 never resolves `LiquidGlassApi33Renderer`.
     */
    private var api33RendererHolder: Any? = null
    private var api33Attempted: Boolean = false

    /**
     * Hook invoked inside [FloatingTopBar]'s draw phase (reads pan, zoom, and live cells so any
     * canvas movement refreshes the top bar on the same frame without recomposing; stationary
     * frames do zero shader work).
     */
    var redrawObserver: () -> Unit = {}

    internal var manualInvalidationTick by mutableLongStateOf(0L)

    fun invalidate() {
        manualInvalidationTick++
    }

    fun observeForRedraw() {
        @Suppress("UNUSED_EXPRESSION")
        manualInvalidationTick
        redrawObserver()
    }

    /**
     * Returns true iff running on Android 13+ (API 33+) and the shared AGSL shaders compiled.
     */
    fun isApi33ShaderAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return (obtainApi33Renderer() as? LiquidGlassApi33Renderer)?.isAvailable == true
    }

    /**
     * Returns the lazily compiled shared API 33 renderer, or null if unsupported or failed.
     */
    internal fun obtainApi33Renderer(): Any? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        if (!api33Attempted) {
            api33Attempted = true
            api33RendererHolder = runCatching {
                LiquidGlassApi33Renderer()
            }.getOrNull()
        }
        return api33RendererHolder
    }

    /**
     * Computes a glass element's top-left offset in this backdrop's coordinate space.
     */
    fun localOriginInBackdrop(childCoordinates: LayoutCoordinates?): Offset? {
        val root = rootCoordinates ?: return null
        val child = childCoordinates ?: return null
        if (!root.isAttached || !child.isAttached) return null
        return root.localPositionOf(child, Offset.Zero)
    }

    internal fun recordAndDrawBackdrop(
        scope: ContentDrawScope,
        captureEnabled: Boolean,
    ) {
        if (!captureEnabled) {
            scope.drawContent()
            return
        }
        val w = scope.size.width.roundToInt().coerceAtLeast(1)
        val h = scope.size.height.roundToInt().coerceAtLeast(1)
        if (rootSizePx.width != w || rootSizePx.height != h) {
            rootSizePx = IntSize(w, h)
        }
        with(scope) {
            backdropLayer.record(size = IntSize(w, h)) {
                drawRect(color = opaqueGroundColor)
                scope.drawContent()
            }
            drawLayer(backdropLayer)
        }
    }
}

@Composable
fun rememberLiquidGlassBackdropState(
    opaqueGroundColor: Color = Color(0xFF0F0F10),
): LiquidGlassBackdropState {
    val backdropLayer = rememberGraphicsLayer()
    return remember(backdropLayer) {
        LiquidGlassBackdropState(
            backdropLayer = backdropLayer,
            initialGroundColor = opaqueGroundColor,
        )
    }.also {
        if (it.opaqueGroundColor != opaqueGroundColor) {
            it.opaqueGroundColor = opaqueGroundColor
            it.invalidate()
        }
    }
}

/**
 * Marks a container as the live backdrop source for sibling [LiquidGlassSurface] elements.
 * When [captureEnabled] is false (Liquid Glass disabled or Power Saver / `GlassQuality.Scrim`),
 * skips offscreen `GraphicsLayer` recording with zero GPU overhead.
 */
fun Modifier.liquidGlassBackdropSource(
    state: LiquidGlassBackdropState,
    captureEnabled: Boolean,
): Modifier = this
    .onGloballyPositioned { coords ->
        state.rootCoordinates = coords
    }
    .drawWithContent {
        if (captureEnabled) {
            state.observeForRedraw()
        }
        state.recordAndDrawBackdrop(
            scope = this,
            captureEnabled = captureEnabled,
        )
    }
