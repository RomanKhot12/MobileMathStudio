package com.example.mobilemathstudio.ui.keyboard

enum class KeyboardLayer {
    Standard,
    Advanced,
    Letters,
}

enum class LetterScript {
    Latin,
    Greek,
    Cyrillic,
}

sealed class KeyAction {
    /**
     * @param cursorBack how many UTF-16 units to move the caret left after insert
     *                   (e.g. Insert("()/", 2) leaves caret inside the first parentheses).
     */
    data class Insert(val token: String, val cursorBack: Int = 0) : KeyAction()
    data class InsertExponent(val token: String) : KeyAction()
    data object InsertFraction : KeyAction()
    data object InsertPower : KeyAction()
    data object InsertSqrt : KeyAction()
    data object InsertNthRoot : KeyAction()
    data object InsertSystem : KeyAction()
    data object ConfirmSlot : KeyAction()
    data object Backspace : KeyAction()
    data object ClearAll : KeyAction()
    data object ClearCanvas : KeyAction()
    data object Undo : KeyAction()
    data object Redo : KeyAction()
    data object CursorLeft : KeyAction()
    data object CursorRight : KeyAction()
    data object Submit : KeyAction()
    data class SwitchLayer(val layer: KeyboardLayer) : KeyAction()
    data class SwitchScript(val script: LetterScript) : KeyAction()
}

data class KeySpec(
    val label: String,
    val action: KeyAction,
    val weight: Float = 1f,
    val accent: Boolean = false,
    val icon: KeyIcon = KeyIcon.None,
    val danger: Boolean = false,
    /** Press-and-drag options shown above the key (empty = tap only). */
    val flyout: List<KeySpec> = emptyList(),
    /** Long-press inserts this atom as an exponent of the value before the caret. */
    val longPressExponent: Boolean = false,
)

enum class KeyIcon {
    None,
    Layer,
    ArrowLeft,
    ArrowRight,
    Undo,
    Redo,
    ClearCanvas,
    Backspace,
    Send,
    Globe,
    Fraction,
    Power,
    Sqrt,
    NthRoot,
    System,
    Check,
}
