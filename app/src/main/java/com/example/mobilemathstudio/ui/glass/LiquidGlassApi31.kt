package com.example.mobilemathstudio.ui.glass

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.RenderEffect as ComposeRenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect

/**
 * Isolated Android 12–12L (API 31–32) hardware blur fallback helper.
 * Never touches `RuntimeShader` (API 33), preventing class verification issues on older devices.
 */
@RequiresApi(Build.VERSION_CODES.S)
internal object LiquidGlassApi31Fallback {

    fun createBlurOnlyEffect(blurPx: Float): ComposeRenderEffect? {
        if (blurPx <= 0.5f) return null
        return runCatching {
            RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
                .asComposeRenderEffect()
        }.getOrNull()
    }
}
