package com.example.mobilemathstudio.performance

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * GPU cost ladder for glass surfaces.
 *
 * [Full]  — AGSL liquid glass (blur + refraction + Fresnel)
 * [Blur]  — platform Gaussian blur only
 * [Scrim] — translucent dark fill, zero shader work
 */
enum class GlassQuality {
    Full,
    Blur,
    Scrim,
}

val LocalGlassQuality = staticCompositionLocalOf { GlassQuality.Full }
