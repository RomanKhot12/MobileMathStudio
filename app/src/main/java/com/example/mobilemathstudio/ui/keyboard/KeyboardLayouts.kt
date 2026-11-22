package com.example.mobilemathstudio.ui.keyboard

/**
 * Layer 1 (Standard) and Layer 2 (Advanced) fully wired to the math engine.
 * Every visible key inserts a token the parser can evaluate (or a structured template).
 */
object KeyboardLayouts {
    private fun atom(label: String, token: String = label, weight: Float = 1f) = KeySpec(
        label = label,
        action = KeyAction.Insert(token),
        weight = weight,
        longPressExponent = true,
    )

    fun standard(): List<List<KeySpec>> = listOf(
        listOf(
            KeySpec("AC", KeyAction.ClearAll),
            KeySpec("%", KeyAction.Insert("%")),
            atom("𝑥", "x"),
            atom("𝑦", "y"),
            KeySpec("√", KeyAction.InsertSqrt, icon = KeyIcon.Sqrt),
        ),
        listOf(
            KeySpec("𝑥ⁿ", KeyAction.InsertPower, icon = KeyIcon.Power),
            KeySpec("𝑎/𝑏", KeyAction.InsertFraction, icon = KeyIcon.Fraction),
            KeySpec("{", KeyAction.InsertSystem, icon = KeyIcon.System),
            KeySpec("(", KeyAction.Insert("(")),
            KeySpec(")", KeyAction.Insert(")")),
        ),
        listOf(
            atom("7"),
            atom("8"),
            atom("9"),
            KeySpec("−", KeyAction.Insert("−")),
            KeySpec("×", KeyAction.Insert("×")),
        ),
        listOf(
            atom("4"),
            atom("5"),
            atom("6"),
            KeySpec("+", KeyAction.Insert("+")),
            KeySpec("ⁿ√", KeyAction.InsertNthRoot, icon = KeyIcon.NthRoot),
        ),
        listOf(
            atom("1"),
            atom("2"),
            atom("3"),
            KeySpec(
                "=",
                KeyAction.Insert("="),
                flyout = listOf(
                    KeySpec(">", KeyAction.Insert(">")),
                    KeySpec("<", KeyAction.Insert("<")),
                    KeySpec("≥", KeyAction.Insert("≥")),
                    KeySpec("≤", KeyAction.Insert("≤")),
                    KeySpec("=", KeyAction.Insert("=")),
                ),
            ),
            KeySpec("÷", KeyAction.Insert("÷")),
        ),
        listOf(
            atom("0", weight = 2f),
            KeySpec(".", KeyAction.Insert(".")),
            KeySpec(",", KeyAction.Insert(",")),
            KeySpec("()", KeyAction.Insert("()", cursorBack = 1)),
        ),
    )

    fun advanced(): List<List<KeySpec>> = listOf(
        listOf(
            KeySpec("sin", KeyAction.Insert("sin(")),
            KeySpec("cos", KeyAction.Insert("cos(")),
            KeySpec("tan", KeyAction.Insert("tan(")),
            KeySpec("cot", KeyAction.Insert("cot(")),
            atom("π"),
            atom("i"),
        ),
        listOf(
            KeySpec("asin", KeyAction.Insert("asin(")),
            KeySpec("acos", KeyAction.Insert("acos(")),
            KeySpec("atan", KeyAction.Insert("atan(")),
            atom("e"),
            atom("∞"),
        ),
        listOf(
            KeySpec("log", KeyAction.Insert("log(")),
            KeySpec("ln", KeyAction.Insert("ln(")),
            KeySpec("exp", KeyAction.Insert("exp(")),
            KeySpec("|𝑥|", KeyAction.Insert("||", cursorBack = 1)),
            KeySpec("𝑛!", KeyAction.Insert("!")),
        ),
        listOf(
            KeySpec("lim", KeyAction.Insert("lim(,)", cursorBack = 2)),
            KeySpec("d/dx", KeyAction.Insert("d/dx(")),
            KeySpec("∫", KeyAction.Insert("∫(")),
            KeySpec("Σ", KeyAction.Insert("Σ(,)", cursorBack = 2)),
            KeySpec("𝑥ⁿ", KeyAction.InsertPower, icon = KeyIcon.Power),
        ),
        listOf(
            KeySpec("[", KeyAction.Insert("[")),
            KeySpec("]", KeyAction.Insert("]")),
            KeySpec("𝑎/𝑏", KeyAction.InsertFraction, icon = KeyIcon.Fraction),
            KeySpec("ⁿ√", KeyAction.InsertNthRoot, icon = KeyIcon.NthRoot),
            KeySpec("√", KeyAction.InsertSqrt, icon = KeyIcon.Sqrt),
        ),
    )

    fun letters(script: LetterScript): List<List<KeySpec>> {
        val rows = when (script) {
            LetterScript.Latin -> listOf(
                "qwertyuiop",
                "asdfghjkl",
                "zxcvbnm",
            )
            LetterScript.Greek -> listOf(
                "αβγδεζηθικ",
                "λμνξοπρστυ",
                "φχψω",
            )
            LetterScript.Cyrillic -> listOf(
                "йцукенгшщзхъ",
                "фывапролджэ",
                "ячсмитьбю",
            )
        }
        return rows.map { row ->
            row.map { ch -> atom(ch.toString()) }
        }
    }

    fun utilityRow(layer: KeyboardLayer, nestedSlot: Boolean = false): List<KeySpec> = buildList {
        add(KeySpec("f", KeyAction.SwitchLayer(nextLayer(layer)), icon = KeyIcon.Layer))
        add(KeySpec("←", KeyAction.CursorLeft, icon = KeyIcon.ArrowLeft))
        add(KeySpec("→", KeyAction.CursorRight, icon = KeyIcon.ArrowRight))
        if (nestedSlot) {
            add(KeySpec("✓", KeyAction.ConfirmSlot, accent = true, icon = KeyIcon.Check))
        }
        add(KeySpec("Undo", KeyAction.Undo, icon = KeyIcon.Undo))
        add(KeySpec("Redo", KeyAction.Redo, icon = KeyIcon.Redo))
        add(KeySpec("Clear", KeyAction.ClearCanvas, danger = true, icon = KeyIcon.ClearCanvas))
        add(KeySpec("⌫", KeyAction.Backspace, icon = KeyIcon.Backspace))
        add(KeySpec("➤", KeyAction.Submit, accent = true, icon = KeyIcon.Send))
    }

    private fun nextLayer(current: KeyboardLayer): KeyboardLayer = when (current) {
        KeyboardLayer.Standard -> KeyboardLayer.Advanced
        KeyboardLayer.Advanced -> KeyboardLayer.Letters
        KeyboardLayer.Letters -> KeyboardLayer.Standard
    }
}
