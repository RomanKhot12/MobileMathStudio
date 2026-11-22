package com.example.mobilemathstudio.export

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Undo / redo / reset for the export composer.
 *
 * Discrete actions go through [edit]. Continuous gestures mutate the state with
 * [apply] and register a single entry with [record] when the finger lifts, so a
 * drag costs one undo step rather than a hundred.
 */
class ExportHistory(initial: ExportLayout) {

    var baseline by mutableStateOf(initial)
        private set

    var state by mutableStateOf(initial)
        private set

    private val past = mutableStateListOf<ExportLayout>()
    private val future = mutableStateListOf<ExportLayout>()

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()
    val canReset: Boolean get() = state != baseline

    /** Starts over from a freshly computed layout (new canvas or page format). */
    fun rebase(layout: ExportLayout) {
        baseline = layout
        state = layout
        past.clear()
        future.clear()
    }

    fun edit(transform: (ExportLayout) -> ExportLayout) {
        val next = transform(state)
        if (next == state) return
        push(state)
        state = next
    }

    /** Live gesture update: no history entry. */
    fun apply(next: ExportLayout) {
        state = next
    }

    /** Closes a gesture by registering the state it started from. */
    fun record(previous: ExportLayout) {
        if (previous == state) return
        push(previous)
    }

    fun undo() {
        val previous = past.removeLastOrNull() ?: return
        future += state
        state = previous
    }

    fun redo() {
        val next = future.removeLastOrNull() ?: return
        past += state
        state = next
    }

    fun reset() {
        if (state == baseline) return
        push(state)
        state = baseline
    }

    private fun push(snapshot: ExportLayout) {
        past += snapshot
        future.clear()
        while (past.size > MAX_DEPTH) past.removeAt(0)
    }

    private companion object {
        const val MAX_DEPTH = 60
    }
}
