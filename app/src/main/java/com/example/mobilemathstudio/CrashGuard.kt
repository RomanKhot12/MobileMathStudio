package com.example.mobilemathstudio

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.roundToInt

/**
 * Drawing, layout and CAS must never take the process down.
 *
 * The main-looper nest catches Compose / view exceptions that escape a
 * composable; background threads log and keep the rest of the app alive.
 * VirtualMachineError (OOM, stack overflow) is still fatal — the VM is
 * already unusable.
 */
object CrashGuard {
    private const val TAG = "MobileMathStudio"

    fun log(t: Throwable, where: String = "swallowed") {
        try {
            Log.e(TAG, where, t)
        } catch (_: Throwable) {
            // android.util.Log is a stub in local unit tests
        }
    }

    inline fun <T> run(fallback: T, block: () -> T): T = try {
        block()
    } catch (t: Throwable) {
        if (t is VirtualMachineError) throw t
        log(t)
        fallback
    }

    inline fun run(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            if (t is VirtualMachineError) throw t
            log(t)
        }
    }

    fun roundPx(value: Float): Int = if (value.isFinite()) value.roundToInt() else 0

    fun finite(value: Float, fallback: Float = 0f): Float =
        if (value.isFinite()) value else fallback

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (error is VirtualMachineError) {
                previous?.uncaughtException(thread, error)
                return@setDefaultUncaughtExceptionHandler
            }
            Log.e(TAG, "uncaught on ${thread.name}", error)
        }
        Handler(Looper.getMainLooper()).post {
            while (true) {
                try {
                    Looper.loop()
                } catch (t: Throwable) {
                    if (t is VirtualMachineError) throw t
                    Log.e(TAG, "main looper", t)
                }
            }
        }
        Log.i(TAG, "crash guard installed (${app.packageName})")
    }
}
