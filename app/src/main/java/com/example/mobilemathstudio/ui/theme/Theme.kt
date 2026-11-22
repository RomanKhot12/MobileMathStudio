package com.example.mobilemathstudio.ui.theme

import android.app.Activity
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.mobilemathstudio.performance.GlassQuality
import com.example.mobilemathstudio.performance.LocalGlassQuality
import com.example.mobilemathstudio.performance.PerformanceSnapshot
import com.example.mobilemathstudio.settings.AnswerLayout
import com.example.mobilemathstudio.settings.AppLanguage
import com.example.mobilemathstudio.settings.DecimalDisplay
import com.example.mobilemathstudio.settings.ThemeMode
import com.example.mobilemathstudio.settings.localeTag
import java.util.Locale

val LocalAccentColor = staticCompositionLocalOf { WindowsAccentBlue }
val LocalAnimationsEnabled = staticCompositionLocalOf { true }
val LocalIsDarkTheme = staticCompositionLocalOf { true }
val LocalLiquidGlassEnabled = staticCompositionLocalOf { true }

/** How multi-variable answers are printed wherever a result is shown. */
val LocalAnswerLayout = staticCompositionLocalOf { AnswerLayout.Column }

/** Digits after the decimal used by the ≈ answer toggle. */
val LocalDecimalPlaces = staticCompositionLocalOf { DecimalDisplay.DEFAULT_PLACES }

private fun darkScheme(accent: Color) = darkColorScheme(
    primary = accent,
    onPrimary = Color.White,
    primaryContainer = accent.copy(alpha = 0.22f),
    onPrimaryContainer = Color.White,
    secondary = WinTextSecondary,
    onSecondary = WinBlack,
    background = WinBlack,
    onBackground = WinTextPrimary,
    surface = WinSurface,
    onSurface = WinTextPrimary,
    surfaceVariant = WinSurfaceElevated,
    onSurfaceVariant = WinTextSecondary,
    outline = WinBorderSubtle,
    error = WinDanger,
    onError = Color.White,
)

private fun lightScheme(accent: Color) = lightColorScheme(
    primary = accent,
    onPrimary = Color(0xFFF4F7FB),
    primaryContainer = accent.copy(alpha = 0.14f),
    onPrimaryContainer = WinLightTextPrimary,
    secondary = WinLightTextSecondary,
    onSecondary = WinLightSurface,
    background = WinLightCanvas,
    onBackground = WinLightTextPrimary,
    surface = WinLightSurface,
    onSurface = WinLightTextPrimary,
    surfaceVariant = WinLightSurfaceElevated,
    onSurfaceVariant = WinLightTextSecondary,
    surfaceContainerLowest = WinLightCardTop,
    surfaceContainerLow = WinLightSurface,
    surfaceContainer = WinLightCanvas,
    surfaceContainerHigh = WinLightKeyFunction,
    surfaceContainerHighest = WinLightSurfaceElevated,
    outline = WinLightKeyBorder,
    error = WinLightDanger,
    onError = Color(0xFFF4F7FB),
)

@Composable
fun resolveDarkTheme(themeMode: ThemeMode): Boolean = when (themeMode) {
    ThemeMode.Dark -> true
    ThemeMode.Light -> false
    ThemeMode.System -> isSystemInDarkTheme()
}

@Composable
fun MathCadTheme(
    themeMode: ThemeMode = ThemeMode.System,
    accentColor: Color = WindowsAccentBlue,
    performance: PerformanceSnapshot = PerformanceSnapshot(),
    answerLayout: AnswerLayout = AnswerLayout.Column,
    decimalPlaces: Int = DecimalDisplay.DEFAULT_PLACES,
    language: AppLanguage = AppLanguage.System,
    liquidGlassEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val darkTheme = resolveDarkTheme(themeMode)
    val colorScheme = if (darkTheme) darkScheme(accentColor) else lightScheme(accentColor)
    val effectiveGlassQuality = if (liquidGlassEnabled) performance.glassQuality else GlassQuality.Scrim
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    val localizedContext = rememberLocalizedContext(language)

    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides localizedContext.resources.configuration,
        LocalResources provides localizedContext.resources,
        LocalAccentColor provides accentColor,
        LocalAnimationsEnabled provides performance.animationsEnabled,
        LocalAnswerLayout provides answerLayout,
        LocalDecimalPlaces provides DecimalDisplay.clamp(decimalPlaces),
        LocalGlassQuality provides effectiveGlassQuality,
        LocalLiquidGlassEnabled provides liquidGlassEnabled,
        LocalIsDarkTheme provides darkTheme,
    ) {
        key(language) {
            MaterialTheme(
                colorScheme = colorScheme,
                typography = Typography,
                content = content,
            )
        }
    }
}

@Composable
private fun rememberLocalizedContext(language: AppLanguage): android.content.Context {
    val base = LocalContext.current
    val systemConfig = LocalConfiguration.current
    return remember(language, systemConfig) {
        val tag = language.localeTag() ?: return@remember base
        val locale = Locale.forLanguageTag(tag)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocales(LocaleList(locale))
        base.createConfigurationContext(cfg)
    }
}

/** @deprecated Prefer [MathCadTheme]. Kept so old previews don't break. */
@Composable
fun MobileMathStudioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    MathCadTheme(
        themeMode = if (darkTheme) ThemeMode.Dark else ThemeMode.Light,
        content = content,
    )
}
