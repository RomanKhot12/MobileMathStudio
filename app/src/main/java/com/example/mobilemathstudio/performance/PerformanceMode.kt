package com.example.mobilemathstudio.performance

/**
 * App-wide visual fidelity mode.
 *
 * [Auto]       — controller picks based on FPS / power-save with hysteresis
 * [High]       — force full liquid glass + micro-animations
 * [Balanced]   — blur-only glass, keep light animations
 * [PowerSaver] — scrim glass, every motion spec snaps to 0ms
 */
enum class PerformanceMode {
    Auto,
    High,
    Balanced,
    PowerSaver,
}

data class PerformanceSnapshot(
    val requestedMode: PerformanceMode = PerformanceMode.Auto,
    val effectiveMode: PerformanceMode = PerformanceMode.High,
    val glassQuality: GlassQuality = GlassQuality.Full,
    val animationsEnabled: Boolean = true,
    val fps: Float = 60f,
    val isPowerSave: Boolean = false,
    val autoDowngraded: Boolean = false,
    val interactionLocked: Boolean = false,
) {
    companion object {
        /** Immediate mapping without hysteresis (manual modes / power-save). */
        fun mapEffective(mode: PerformanceMode, fps: Float, isPowerSave: Boolean): PerformanceMode {
            return when (mode) {
                PerformanceMode.High -> PerformanceMode.High
                PerformanceMode.Balanced -> PerformanceMode.Balanced
                PerformanceMode.PowerSaver -> PerformanceMode.PowerSaver
                PerformanceMode.Auto -> when {
                    isPowerSave -> PerformanceMode.PowerSaver
                    fps in 1f..<45f -> PerformanceMode.PowerSaver
                    fps in 45f..<55f -> PerformanceMode.Balanced
                    else -> PerformanceMode.High
                }
            }
        }

        fun fromEffective(
            requested: PerformanceMode,
            effective: PerformanceMode,
            fps: Float,
            isPowerSave: Boolean,
            interactionLocked: Boolean = false,
        ): PerformanceSnapshot {
            val quality = when (effective) {
                PerformanceMode.High -> GlassQuality.Full
                PerformanceMode.Balanced -> GlassQuality.Blur
                PerformanceMode.PowerSaver -> GlassQuality.Scrim
                PerformanceMode.Auto -> GlassQuality.Full
            }
            return PerformanceSnapshot(
                requestedMode = requested,
                effectiveMode = effective,
                glassQuality = quality,
                animationsEnabled = effective != PerformanceMode.PowerSaver,
                fps = fps,
                isPowerSave = isPowerSave,
                autoDowngraded = requested == PerformanceMode.Auto && effective != PerformanceMode.High,
                interactionLocked = interactionLocked,
            )
        }
    }
}

/**
 * Hysteresis + debounce for Auto performance mode.
 * Requires stable target for [STABLE_MS] before switching; freezes during interaction.
 */
class AutoPerformanceController {
    companion object {
        const val STABLE_MS = 2800L
        const val INTERACTION_LOCK_MS = 3500L
    }

    @Volatile
    private var lockedEffective: PerformanceMode = PerformanceMode.High

    @Volatile
    private var candidate: PerformanceMode? = null

    @Volatile
    private var candidateSinceMs: Long = 0L

    @Volatile
    private var interactionUntilMs: Long = 0L

    fun notifyInteraction(nowMs: Long = System.currentTimeMillis()) {
        interactionUntilMs = nowMs + INTERACTION_LOCK_MS
    }

    fun isInteractionLocked(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs < interactionUntilMs

    fun resolve(
        requested: PerformanceMode,
        fps: Float,
        isPowerSave: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): PerformanceSnapshot {
        if (requested != PerformanceMode.Auto) {
            val eff = PerformanceSnapshot.mapEffective(requested, fps, isPowerSave)
            lockedEffective = eff
            candidate = null
            return PerformanceSnapshot.fromEffective(requested, eff, fps, isPowerSave, false)
        }

        val interactionLocked = nowMs < interactionUntilMs
        if (interactionLocked) {
            return PerformanceSnapshot.fromEffective(
                requested,
                lockedEffective,
                fps,
                isPowerSave,
                interactionLocked = true,
            )
        }

        // Power-save switches immediately (system intent).
        if (isPowerSave) {
            lockedEffective = PerformanceMode.PowerSaver
            candidate = null
            return PerformanceSnapshot.fromEffective(requested, lockedEffective, fps, true, false)
        }

        val target = PerformanceSnapshot.mapEffective(PerformanceMode.Auto, fps, false)
        if (target == lockedEffective) {
            candidate = null
            return PerformanceSnapshot.fromEffective(requested, lockedEffective, fps, false, false)
        }

        if (candidate != target) {
            candidate = target
            candidateSinceMs = nowMs
        } else if (nowMs - candidateSinceMs >= STABLE_MS) {
            lockedEffective = target
            candidate = null
        }

        return PerformanceSnapshot.fromEffective(requested, lockedEffective, fps, false, false)
    }
}
