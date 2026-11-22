package com.example.mobilemathstudio.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardLayoutTest {

    @Test
    fun utilityRowReplacesEnterWithUndoAndRedo() {
        val keys = KeyboardLayouts.utilityRow(KeyboardLayer.Standard, nestedSlot = false)
        val actions = keys.map { it.action }

        assertTrue(KeyAction.Undo in actions)
        assertTrue(KeyAction.Redo in actions)
        assertTrue(KeyAction.Backspace in actions)
        assertFalse(keys.any { it.icon == KeyIcon.Backspace && it.label == "↵" })
        assertFalse(keys.any { it.label == "↵" })
        assertTrue(keys.any { it.icon == KeyIcon.Undo })
        assertTrue(keys.any { it.icon == KeyIcon.Redo })
    }

    @Test
    fun nestedUtilityRowKeepsConfirmAlongsideUndoRedo() {
        val keys = KeyboardLayouts.utilityRow(KeyboardLayer.Standard, nestedSlot = true)
        val actions = keys.map { it.action }

        assertTrue(KeyAction.ConfirmSlot in actions)
        assertTrue(KeyAction.Undo in actions)
        assertTrue(KeyAction.Redo in actions)
        assertFalse(keys.any { it.label == "↵" })
    }

    @Test
    fun acClearsTheActiveFormulaAndIsNotDanger() {
        val ac = KeyboardLayouts.standard().flatten()
            .single { it.action == KeyAction.ClearAll }
        assertFalse(ac.danger)
        assertFalse(ac.accent)
        assertFalse(
            KeyboardLayouts.utilityRow(KeyboardLayer.Standard)
                .any { it.action == KeyAction.ClearAll },
        )
    }

    @Test
    fun canvasClearSitsNextToUndoRedoAndIsDanger() {
        val keys = KeyboardLayouts.utilityRow(KeyboardLayer.Standard, nestedSlot = false)
        val undo = keys.indexOfFirst { it.action == KeyAction.Undo }
        val redo = keys.indexOfFirst { it.action == KeyAction.Redo }
        val clear = keys.indexOfFirst { it.action == KeyAction.ClearCanvas }

        assertTrue(undo >= 0 && redo == undo + 1 && clear == redo + 1)
        assertTrue(keys[clear].danger)
        assertTrue(keys[clear].icon == KeyIcon.ClearCanvas)
    }

    @Test
    fun equalsKeyOffersInequalityFlyout() {
        val eq = KeyboardLayouts.standard().flatten().single { it.label == "=" }
        val labels = eq.flyout.map { it.label }
        assertTrue(labels.containsAll(listOf(">", "<", "≥", "≤", "=")))
        eq.flyout.forEach { opt ->
            assertTrue(opt.action is KeyAction.Insert)
        }
    }

    @Test
    fun advancedLayerHasImaginaryUnitNextToConstants() {
        val keys = KeyboardLayouts.advanced().flatten()
        val labels = keys.map { it.label }
        assertTrue(labels.contains("i"))
        assertTrue(labels.contains("π"))
        assertTrue(labels.contains("e"))
        val i = keys.single { it.label == "i" }
        assertEquals("i", (i.action as KeyAction.Insert).token)
        assertTrue(i.longPressExponent)
    }

    @Test
    fun digitsAndVariablesLongPressAsExponents() {
        val standard = KeyboardLayouts.standard().flatten()
        assertTrue(standard.filter { it.label in listOf("0", "1", "2", "x", "y", "𝑥", "𝑦") }.all { it.longPressExponent })
        assertFalse(standard.single { it.label == "+" }.longPressExponent)
    }

    @Test
    fun keysFallIntoDistinctVisualFamilies() {
        val standard = KeyboardLayouts.standard().flatten().associateBy { it.label }
        listOf("0", "1", "9", ".", ",").forEach { assertEquals(it, KeyRole.Digit, keyRole(standard.getValue(it))) }
        listOf("+", "−", "×", "÷", "=", "(", ")", "%").forEach { assertEquals(it, KeyRole.Operator, keyRole(standard.getValue(it))) }
        listOf("√", "𝑎/𝑏", "𝑥ⁿ", "{").forEach { assertEquals(it, KeyRole.Template, keyRole(standard.getValue(it))) }
        assertEquals(KeyRole.Function, keyRole(standard.getValue("𝑥")))
        assertEquals(KeyRole.Utility, keyRole(standard.getValue("AC")))

        val advanced = KeyboardLayouts.advanced().flatten().associateBy { it.label }
        listOf("sin", "log", "π", "lim", "|𝑥|").forEach { assertEquals(it, KeyRole.Function, keyRole(advanced.getValue(it))) }

        val utility = KeyboardLayouts.utilityRow(KeyboardLayer.Standard, nestedSlot = true).associateBy { it.action }
        assertEquals(KeyRole.Primary, keyRole(utility.getValue(KeyAction.Submit)))
        assertEquals(KeyRole.Confirm, keyRole(utility.getValue(KeyAction.ConfirmSlot)))
        assertEquals(KeyRole.Danger, keyRole(utility.getValue(KeyAction.ClearCanvas)))
        assertEquals(KeyRole.Utility, keyRole(utility.getValue(KeyAction.Backspace)))
    }
}
