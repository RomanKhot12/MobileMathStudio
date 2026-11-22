package com.example.mobilemathstudio.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mobilemathstudio.R
import com.example.mobilemathstudio.ui.theme.LocalAccentColor
import com.example.mobilemathstudio.ui.theme.LocalIsDarkTheme
import com.example.mobilemathstudio.ui.theme.WinLightSurface
import com.example.mobilemathstudio.ui.theme.WinLightTextMuted
import com.example.mobilemathstudio.ui.theme.WinLightTextPrimary
import com.example.mobilemathstudio.ui.theme.WinLightTextSecondary
import com.example.mobilemathstudio.ui.theme.WinTextMuted
import com.example.mobilemathstudio.ui.theme.WinTextPrimary
import com.example.mobilemathstudio.ui.theme.WinTextSecondary

@Composable
fun OnboardingOverlay(
    onFinished: () -> Unit,
) {
    var step by remember { mutableIntStateOf(0) }
    val accent = LocalAccentColor.current
    val dark = LocalIsDarkTheme.current
    val titles = listOf(
        R.string.onboard_step1_title,
        R.string.onboard_step2_title,
        R.string.onboard_step3_title,
        R.string.onboard_step4_title,
    )
    val bodies = listOf(
        R.string.onboard_step1_body,
        R.string.onboard_step2_body,
        R.string.onboard_step3_body,
        R.string.onboard_step4_body,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f)),
    ) {
        TextButton(
            onClick = onFinished,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.onboard_skip),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(20.dp)
                .fillMaxWidth()
                .background(
                    if (dark) Color(0xF01C1C1E) else WinLightSurface.copy(alpha = 0.98f),
                    RoundedCornerShape(16.dp),
                )
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(titles[step]),
                color = if (dark) WinTextPrimary else WinLightTextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
            )
            Text(
                text = stringResource(bodies[step]),
                color = if (dark) WinTextSecondary else WinLightTextSecondary,
                fontSize = 15.sp,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${step + 1}/${titles.size}",
                    color = if (dark) WinTextMuted else WinLightTextMuted,
                )
                Button(
                    onClick = {
                        if (step >= titles.lastIndex) onFinished()
                        else step++
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                ) {
                    Text(
                        text = stringResource(
                            if (step >= titles.lastIndex) R.string.onboard_done else R.string.onboard_next,
                        ),
                    )
                }
            }
        }
    }
}
