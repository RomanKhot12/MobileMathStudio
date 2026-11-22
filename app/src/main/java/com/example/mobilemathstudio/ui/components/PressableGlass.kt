package com.example.mobilemathstudio.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.example.mobilemathstudio.performance.Motion
import com.example.mobilemathstudio.ui.theme.LocalAnimationsEnabled

/**
 * Soft press feedback: scale + optional ripple.
 * Fully disabled in Power Saver / reduced performance mode.
 */
@Composable
fun Modifier.pressableScale(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val animationsEnabled = LocalAnimationsEnabled.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (animationsEnabled && pressed) 0.94f else 1f,
        animationSpec = Motion.effects(animationsEnabled, durationMillis = 90),
        label = "press-scale",
    )
    val indication = if (animationsEnabled) {
        ripple(bounded = true, color = Color.White.copy(alpha = 0.18f))
    } else {
        null
    }

    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            enabled = enabled,
            interactionSource = interaction,
            indication = indication,
            onClick = onClick,
        )
}
