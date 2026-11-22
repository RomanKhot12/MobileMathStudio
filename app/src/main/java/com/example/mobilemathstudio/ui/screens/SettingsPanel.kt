package com.example.mobilemathstudio.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.mobilemathstudio.CrashGuard
import com.example.mobilemathstudio.R
import com.example.mobilemathstudio.performance.PerformanceMode
import com.example.mobilemathstudio.performance.PerformanceSnapshot
import com.example.mobilemathstudio.settings.AnswerLayout
import com.example.mobilemathstudio.settings.AppLanguage
import com.example.mobilemathstudio.settings.AppSettings
import com.example.mobilemathstudio.settings.DecimalDisplay
import com.example.mobilemathstudio.settings.ThemeMode
import com.example.mobilemathstudio.settings.formatAnswer
import com.example.mobilemathstudio.ui.theme.LocalIsDarkTheme
import com.example.mobilemathstudio.ui.theme.WinLightSurface
import com.example.mobilemathstudio.ui.theme.WinLightSurfaceElevated
import com.example.mobilemathstudio.ui.theme.WinLightTextMuted
import com.example.mobilemathstudio.ui.theme.WinLightTextPrimary
import com.example.mobilemathstudio.ui.theme.WinLightTextSecondary
import com.example.mobilemathstudio.ui.theme.WinSurface
import com.example.mobilemathstudio.ui.theme.WinTextMuted
import com.example.mobilemathstudio.ui.theme.WinTextPrimary
import com.example.mobilemathstudio.ui.theme.WinTextSecondary
import com.example.mobilemathstudio.ui.theme.WindowsAccentBlue

private val AccentPresets = listOf(
    WindowsAccentBlue,
    Color(0xFF00B7C3),
    Color(0xFF8764B8),
    Color(0xFFD13438),
    Color(0xFFFF8C00),
    Color(0xFF107C10),
    Color(0xFFE3008C),
    Color(0xFFF7630C),
)

@Composable
fun SettingsPanel(
    settings: AppSettings,
    performance: PerformanceSnapshot,
    onSettingsChange: (AppSettings) -> Unit,
    rootModifier: Modifier = Modifier,
) {
    val dark = LocalIsDarkTheme.current
    val surface = if (dark) WinSurface.copy(alpha = 0.97f) else WinLightSurface.copy(alpha = 0.98f)
    val primary = if (dark) WinTextPrimary else WinLightTextPrimary
    val secondary = if (dark) WinTextSecondary else WinLightTextSecondary
    val muted = if (dark) WinTextMuted else WinLightTextMuted
    val divider = if (dark) Color.White.copy(alpha = 0.08f) else WinLightTextPrimary.copy(alpha = 0.08f)

    Column(
        modifier = rootModifier
            .fillMaxHeight()
            .width(320.dp)
            .background(surface)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge, color = primary)
        Text(
            stringResource(R.string.settings_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        SectionLabel(stringResource(R.string.settings_appearance), secondary)
        Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.labelLarge, color = secondary)
        ChipRow(
            options = ThemeMode.entries.map { it to themeLabel(it) },
            selected = settings.themeMode,
            onSelect = { onSettingsChange(settings.copy(themeMode = it)) },
            dark = dark,
        )

        Text(stringResource(R.string.settings_accent), style = MaterialTheme.typography.labelLarge, color = secondary)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AccentPresets.forEach { color ->
                val selected = settings.accentColor == color
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(color)
                        .then(
                            if (selected) Modifier.border(2.dp, if (dark) Color.White else WinLightTextPrimary, CircleShape)
                            else Modifier,
                        )
                        .clickable { onSettingsChange(settings.copy(accentColor = color)) },
                )
            }
        }

        HorizontalDivider(color = divider, modifier = Modifier.padding(vertical = 4.dp))

        SectionLabel(stringResource(R.string.settings_language), secondary)
        ChipRow(
            options = AppLanguage.entries.map { it to languageLabel(it) },
            selected = settings.language,
            onSelect = { onSettingsChange(settings.copy(language = it)) },
            dark = dark,
        )

        SectionLabel(stringResource(R.string.settings_answer_layout), secondary)
        ChipRow(
            options = AnswerLayout.entries.map { it to answerLayoutLabel(it) },
            selected = settings.answerLayout,
            onSelect = { onSettingsChange(settings.copy(answerLayout = it)) },
            dark = dark,
        )
        Text(
            text = formatAnswer("x = 2\ny = 3", settings.answerLayout),
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
        )

        SectionLabel(stringResource(R.string.settings_decimal_precision), secondary)
        DecimalPrecisionRow(
            places = DecimalDisplay.clamp(settings.decimalPlaces),
            dark = dark,
            primary = primary,
            secondary = secondary,
            onSelect = { onSettingsChange(settings.copy(decimalPlaces = it)) },
        )

        HorizontalDivider(color = divider, modifier = Modifier.padding(vertical = 4.dp))

        SectionLabel(stringResource(R.string.settings_performance), secondary)
        LiquidGlassToggleRow(
            enabled = settings.liquidGlassEnabled,
            dark = dark,
            primary = primary,
            muted = muted,
            onToggle = { onSettingsChange(settings.copy(liquidGlassEnabled = it)) },
        )
        ChipRow(
            options = PerformanceMode.entries.map { it to performanceLabel(it) },
            selected = settings.performanceMode,
            onSelect = { onSettingsChange(settings.copy(performanceMode = it)) },
            dark = dark,
        )
        Text(
            text = stringResource(
                R.string.settings_performance_status,
                "%.0f".format(performance.fps),
                performance.effectiveMode.name,
                if (performance.autoDowngraded) stringResource(R.string.settings_auto_downgraded) else "",
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
        )

        HorizontalDivider(color = divider, modifier = Modifier.padding(vertical = 4.dp))

        SectionLabel(stringResource(R.string.settings_support), secondary)
        Text(
            text = stringResource(R.string.settings_support_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
        )
        SupportEmailRow(
            email = stringResource(R.string.settings_support_email),
            primary = primary,
        )
    }
}

@Composable
private fun LiquidGlassToggleRow(
    enabled: Boolean,
    dark: Boolean,
    primary: Color,
    muted: Color,
    onToggle: (Boolean) -> Unit,
) {
    val bg = if (dark) Color.White.copy(alpha = 0.06f) else WinLightSurfaceElevated
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable { onToggle(!enabled) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.settings_liquid_glass),
                style = MaterialTheme.typography.bodyMedium,
                color = primary,
            )
            Text(
                text = stringResource(R.string.settings_liquid_glass_hint),
                style = MaterialTheme.typography.labelSmall,
                color = muted,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
        )
    }
}

@Composable
private fun SectionLabel(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun <T> ChipRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    dark: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    active -> Color.White
                    dark -> WinTextSecondary
                    else -> WinLightTextSecondary
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.88f)
                        else if (dark) Color.White.copy(alpha = 0.06f)
                        else WinLightSurfaceElevated,
                    )
                    .clickable { onSelect(value) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.System -> stringResource(R.string.theme_system)
    ThemeMode.Light -> stringResource(R.string.theme_light)
    ThemeMode.Dark -> stringResource(R.string.theme_dark)
}

@Composable
private fun languageLabel(language: AppLanguage): String = when (language) {
    AppLanguage.System -> stringResource(R.string.lang_system)
    AppLanguage.English -> stringResource(R.string.lang_en)
    AppLanguage.Russian -> stringResource(R.string.lang_ru)
}

@Composable
private fun answerLayoutLabel(layout: AnswerLayout): String = when (layout) {
    AnswerLayout.Column -> stringResource(R.string.answer_layout_column)
    AnswerLayout.Inline -> stringResource(R.string.answer_layout_inline)
}

@Composable
private fun performanceLabel(mode: PerformanceMode): String = when (mode) {
    PerformanceMode.Auto -> stringResource(R.string.perf_auto)
    PerformanceMode.High -> stringResource(R.string.perf_high)
    PerformanceMode.Balanced -> stringResource(R.string.perf_balanced)
    PerformanceMode.PowerSaver -> stringResource(R.string.perf_saver)
}

@Composable
private fun DecimalPrecisionRow(
    places: Int,
    dark: Boolean,
    primary: Color,
    secondary: Color,
    onSelect: (Int) -> Unit,
) {
    // AlertDialog (not DropdownMenu): the settings drawer clips popups, so a
    // dropdown looks like it never opened.
    var precisionMenuOpen by remember { mutableStateOf(false) }
    val chipBg = if (dark) Color.White.copy(alpha = 0.06f) else WinLightSurfaceElevated
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(chipBg)
            .clickable { precisionMenuOpen = true }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.settings_decimal_precision_value, places),
            style = MaterialTheme.typography.bodyMedium,
            color = primary,
        )
        Text(
            text = "▾",
            style = MaterialTheme.typography.labelLarge,
            color = secondary,
        )
    }
    if (precisionMenuOpen) {
        AlertDialog(
            onDismissRequest = { precisionMenuOpen = false },
            title = { Text(stringResource(R.string.settings_decimal_precision)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    DecimalDisplay.ALLOWED.forEach { n ->
                        val active = n == places
                        Text(
                            text = stringResource(R.string.settings_decimal_precision_option, n),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (active) MaterialTheme.colorScheme.primary else primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                    else Color.Transparent,
                                )
                                .clickable {
                                    onSelect(n)
                                    precisionMenuOpen = false
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { precisionMenuOpen = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SupportEmailRow(email: String, primary: Color) {
    val context = LocalContext.current
    Text(
        text = email,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable {
                CrashGuard.run {
                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:$email")
                    }
                    context.startActivity(intent)
                }
            }
            .padding(vertical = 8.dp),
    )
}
