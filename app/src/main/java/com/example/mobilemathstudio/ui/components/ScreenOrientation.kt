package com.example.mobilemathstudio.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import com.example.mobilemathstudio.CrashGuard

/** Portrait for the compact sheet, sensor-landscape for the full-width digit strip. */
fun longNumberViewerOrientation(fullscreen: Boolean): Int =
    if (fullscreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Locks the host activity to landscape while the long-number viewer is
 * fullscreen, then always restores portrait when that viewer leaves.
 */
@Composable
fun ApplyLongNumberOrientation(fullscreen: Boolean) {
    val activity = LocalContext.current.findActivity()
    SideEffect {
        CrashGuard.run {
            activity?.requestedOrientation = longNumberViewerOrientation(fullscreen)
        }
    }
    DisposableEffect(activity) {
        onDispose {
            CrashGuard.run {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
    }
}
