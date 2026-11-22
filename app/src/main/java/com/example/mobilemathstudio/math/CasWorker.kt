package com.example.mobilemathstudio.math

import com.example.mobilemathstudio.CrashGuard
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * The single thread every CAS computation runs on.
 *
 * - Keeps Symja (seconds to initialise, not thread-safe) off the main thread.
 * - Serialises solves so two cells never share the evaluator concurrently.
 * - Gets a large stack: Symja's rewriting is deeply recursive and the default
 *   1 MB stack of a background thread overflows on moderately nested input.
 */
object CasWorker {
    private const val STACK_BYTES = 32L shl 20

    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(null, task, "cas-worker", STACK_BYTES).apply { isDaemon = true }
    }

    private val dispatcher = executor.asCoroutineDispatcher()

    suspend fun <T> run(block: () -> T): T = withContext(dispatcher) { block() }

    /** Queues a low-priority Symja warm-up; a real solve queued later waits for it. */
    fun warmUpInBackground() {
        if (SymjaEngine.isReady) return
        executor.execute {
            val thread = Thread.currentThread()
            val previous = thread.priority
            thread.priority = Thread.MIN_PRIORITY
            try {
                // A failed warm-up only means the first solve initialises Symja itself.
                CrashGuard.run { SymjaEngine.warmUp() }
            } finally {
                thread.priority = previous
            }
        }
    }
}
