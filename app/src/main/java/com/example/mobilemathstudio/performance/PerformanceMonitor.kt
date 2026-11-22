package com.example.mobilemathstudio.performance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.view.Choreographer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Tracks rolling FPS via [Choreographer] and system power-save state.
 * Auto mode decisions go through [AutoPerformanceController] (hysteresis + interaction lock).
 */
@Stable
class PerformanceMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    val autoController = AutoPerformanceController()

    private val _fps = MutableStateFlow(60f)
    val fps: StateFlow<Float> = _fps.asStateFlow()

    private val _powerSave = MutableStateFlow(powerManager.isPowerSaveMode)
    val powerSave: StateFlow<Boolean> = _powerSave.asStateFlow()

    private var lastFrameNs = 0L
    private var emaFrameMs = 16.6f
    private var started = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (lastFrameNs != 0L) {
                val dtMs = ((frameTimeNanos - lastFrameNs) / 1_000_000f).coerceIn(1f, 100f)
                emaFrameMs = emaFrameMs * 0.9f + dtMs * 0.1f
                _fps.value = (1000f / emaFrameMs).coerceIn(1f, 120f)
            }
            lastFrameNs = frameTimeNanos
            if (started) {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            _powerSave.update { powerManager.isPowerSaveMode }
        }
    }

    fun notifyInteraction() = autoController.notifyInteraction()

    fun start() {
        if (started) return
        started = true
        lastFrameNs = 0L
        _powerSave.value = powerManager.isPowerSaveMode
        appContext.registerReceiver(
            powerReceiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
        )
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun stop() {
        if (!started) return
        started = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        runCatching { appContext.unregisterReceiver(powerReceiver) }
    }
}

@Composable
fun rememberPerformanceMonitor(): PerformanceMonitor {
    val context = LocalContext.current
    val monitor = remember { PerformanceMonitor(context) }
    DisposableEffect(monitor) {
        monitor.start()
        onDispose { monitor.stop() }
    }
    return monitor
}

@Composable
fun rememberPerformanceSnapshot(
    requestedMode: PerformanceMode,
    monitor: PerformanceMonitor = rememberPerformanceMonitor(),
): PerformanceSnapshot {
    val fps by monitor.fps.collectAsStateWithLifecycle()
    val powerSave by monitor.powerSave.collectAsStateWithLifecycle()
    return remember(requestedMode, fps, powerSave) {
        monitor.autoController.resolve(requestedMode, fps, powerSave)
    }
}
