package com.example.mobilemathstudio.performance

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import com.example.mobilemathstudio.ui.theme.LocalAnimationsEnabled

/**
 * Shared motion tokens. Power Saver / Eco snaps every spec to 0ms.
 */
object Motion {

    fun <T> spatial(enabled: Boolean): FiniteAnimationSpec<T> =
        if (enabled) {
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            )
        } else {
            snap()
        }

    fun <T> effects(enabled: Boolean, durationMillis: Int = 280): FiniteAnimationSpec<T> =
        if (enabled) {
            tween(durationMillis = durationMillis, easing = FastOutSlowInEasing)
        } else {
            snap()
        }

    @Composable
    fun <T> spatial(): FiniteAnimationSpec<T> = spatial(LocalAnimationsEnabled.current)

    @Composable
    fun <T> effects(durationMillis: Int = 280): FiniteAnimationSpec<T> =
        effects(LocalAnimationsEnabled.current, durationMillis)
}
