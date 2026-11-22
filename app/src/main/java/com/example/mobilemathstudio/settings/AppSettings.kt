package com.example.mobilemathstudio.settings

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.example.mobilemathstudio.performance.PerformanceMode
import com.example.mobilemathstudio.ui.theme.WindowsAccentBlue

enum class AppLanguage { System, English, Russian }

fun AppLanguage.localeTag(): String? = when (this) {
    AppLanguage.System -> null
    AppLanguage.English -> "en"
    AppLanguage.Russian -> "ru"
}

enum class ThemeMode { System, Light, Dark }

enum class HistoryRetention {
    OneHour,
    OneDay,
    TwoDays,
    ThreeDays,
    SevenDays,
    OneWeek,
}

enum class HistoryMode {
    Auto,
    Manual,
    Disabled,
}

@Immutable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val accentColor: Color = WindowsAccentBlue,
    val language: AppLanguage = AppLanguage.System,
    val performanceMode: PerformanceMode = PerformanceMode.Auto,
    val historyRetention: HistoryRetention = HistoryRetention.TwoDays,
    val historyMode: HistoryMode = HistoryMode.Auto,
    val answerLayout: AnswerLayout = AnswerLayout.Column,
    /** Digits after the decimal shown by the ≈ toggle. */
    val decimalPlaces: Int = DecimalDisplay.DEFAULT_PLACES,
    /** Master switch for GPU Liquid Glass shaders & hardware backdrop blur. */
    val liquidGlassEnabled: Boolean = true,
)
