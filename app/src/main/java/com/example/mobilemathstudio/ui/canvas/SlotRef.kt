package com.example.mobilemathstudio.ui.canvas

/**
 * Addresses a single input slot: a row of a system (or the whole formula when
 * [equationIndex] is [NO_EQUATION]) plus the node path inside that document.
 */
data class SlotRef(
    val cellId: String,
    val equationIndex: Int = NO_EQUATION,
    val path: List<Int> = emptyList(),
) {
    companion object {
        const val NO_EQUATION = -1
    }
}
