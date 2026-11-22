package com.example.mobilemathstudio.ui.components

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.mobilemathstudio.CrashGuard
import com.example.mobilemathstudio.R
import com.example.mobilemathstudio.math.AnswerMath
import com.example.mobilemathstudio.math.DecimalExpansion
import com.example.mobilemathstudio.math.expr.MathDocument
import com.example.mobilemathstudio.ui.math.StructuredMathView
import com.example.mobilemathstudio.ui.theme.LocalAccentColor
import com.example.mobilemathstudio.ui.theme.LocalIsDarkTheme
import com.example.mobilemathstudio.ui.theme.WinLightSurface
import com.example.mobilemathstudio.ui.theme.WinLightTextMuted
import com.example.mobilemathstudio.ui.theme.WinLightTextPrimary
import com.example.mobilemathstudio.ui.theme.WinLightTextSecondary
import com.example.mobilemathstudio.ui.theme.WinSurfaceElevated
import com.example.mobilemathstudio.ui.theme.WinTextMuted
import com.example.mobilemathstudio.ui.theme.WinTextPrimary
import com.example.mobilemathstudio.ui.theme.WinTextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val CHUNK = 80

/**
 * High-precision look at a long or irrational number. Digits keep arriving as
 * the user scrolls right; when the well runs dry the number stays on screen
 * and a quiet note appears underneath.
 *
 * Full screen rotates the activity to landscape so the digit strip uses the
 * long side of the phone; closing or exiting restores portrait.
 */
@Composable
fun LongNumberDialog(
    source: String,
    onDismiss: () -> Unit,
) {
    val dark = LocalIsDarkTheme.current
    val accent = LocalAccentColor.current
    val surface = if (dark) WinSurfaceElevated else WinLightSurface
    val primary = if (dark) WinTextPrimary else WinLightTextPrimary
    val secondary = if (dark) WinTextSecondary else WinLightTextSecondary
    val muted = if (dark) WinTextMuted else WinLightTextMuted

    val scroll = rememberScrollState()
    var wanted by remember(source) { mutableIntStateOf(CHUNK) }
    var digits by remember(source) { mutableStateOf<DecimalExpansion.Digits?>(null) }
    var failed by remember(source) { mutableStateOf(false) }
    var endReached by remember(source) { mutableStateOf(false) }
    var loading by remember(source) { mutableStateOf(true) }
    var fullscreen by remember { mutableStateOf(false) }

    ApplyLongNumberOrientation(fullscreen)

    val cap = remember(source) {
        if (DecimalExpansion.rationalOf(source) != null) {
            DecimalExpansion.RATIONAL_LIMIT
        } else {
            DecimalExpansion.CAS_LIMIT
        }
    }

    LaunchedEffect(source, wanted) {
        loading = digits == null
        val next = withContext(Dispatchers.Default) {
            CrashGuard.run(null) { DecimalExpansion.of(source, wanted) }
        }
        if (next == null) {
            // Keep whatever digits we already have; never blank the field.
            if (digits == null) failed = true
            endReached = digits != null
            loading = false
        } else {
            digits = next
            failed = false
            loading = false
            if (next.complete || wanted >= cap) endReached = true
        }
    }

    val shown = digits
    LaunchedEffect(scroll.value, scroll.maxValue, shown, wanted, endReached) {
        val current = shown ?: return@LaunchedEffect
        if (current.complete || endReached || loading) return@LaunchedEffect
        if (scroll.maxValue > 0 && scroll.maxValue - scroll.value < 96) {
            val next = (wanted + CHUNK).coerceAtMost(cap)
            if (next > wanted) wanted = next else endReached = true
        }
    }

    fun close() {
        onDismiss()
    }

    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val wide = fullscreen && landscape

    // Remount the window when the activity actually rotates, otherwise a
    // Compose Dialog can keep the old portrait width.
    key(fullscreen, configuration.orientation) {
        Dialog(
            onDismissRequest = { close() },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = !fullscreen,
            ),
        ) {
            Column(
                modifier = Modifier
                    .then(
                        if (fullscreen) {
                            Modifier
                                .fillMaxSize()
                                .background(surface)
                                .displayCutoutPadding()
                                .statusBarsPadding()
                                .navigationBarsPadding()
                        } else {
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(surface)
                        },
                    )
                    .padding(
                        horizontal = if (wide) 20.dp else 16.dp,
                        vertical = if (wide) 12.dp else 16.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(if (wide) 8.dp else 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StructuredMathView(
                        document = CrashGuard.run(MathDocument.empty()) {
                            AnswerMath.document(source, mixed = false)
                        },
                        focused = false,
                        textColor = primary,
                        fontSize = if (wide) 22.sp else if (fullscreen) 26.sp else 20.sp,
                        maxWidth = when {
                            wide -> (configuration.screenWidthDp * 0.55f).dp
                            fullscreen -> 480.dp
                            else -> 200.dp
                        },
                        modifier = Modifier.weight(1f),
                    )
                    CircleIcon(
                        image = if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                        label = stringResource(
                            if (fullscreen) R.string.long_number_exit_fullscreen
                            else R.string.long_number_fullscreen,
                        ),
                        tint = primary,
                        onClick = { fullscreen = !fullscreen },
                    )
                    CircleIcon(
                        image = Icons.Outlined.Close,
                        label = stringResource(R.string.long_number_close),
                        tint = primary,
                        onClick = { close() },
                    )
                }

                if (!wide) {
                    Text(
                        text = stringResource(R.string.long_number_hint),
                        color = muted,
                        fontSize = 12.sp,
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (fullscreen) Modifier.weight(1f, fill = true) else Modifier)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accent.copy(alpha = if (dark) 0.12f else 0.08f))
                        .padding(
                            horizontal = if (wide) 16.dp else 12.dp,
                            vertical = if (wide) 18.dp else if (fullscreen) 22.dp else 14.dp,
                        ),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (shown == null && loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = accent,
                        )
                    } else {
                        val body = shown?.render() ?: source
                        Row(
                            modifier = Modifier.horizontalScroll(scroll),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = body,
                                color = primary,
                                fontSize = if (wide) 26.sp else if (fullscreen) 28.sp else 22.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Visible,
                                softWrap = false,
                            )
                            if (loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .padding(start = 10.dp)
                                        .size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = accent,
                                )
                            }
                        }
                    }
                }

                if (failed && shown == null) {
                    Text(
                        text = stringResource(R.string.long_number_unavailable),
                        color = secondary,
                        fontSize = 12.sp,
                    )
                } else if (endReached) {
                    Text(
                        text = stringResource(R.string.long_number_end),
                        color = muted,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun CircleIcon(
    image: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = image,
            contentDescription = label,
            tint = tint,
        )
    }
}
