/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rkr.simplekeyboard.inputmethod.keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.keyboard.KeyboardSwitcher.KeyboardSwitchState
import rkr.simplekeyboard.inputmethod.latin.settings.SettingsValues

/**
 * Hardware-keyboard suppression reads [SettingsValues], which the service leaves null until the
 * first field start builds them. The framework may call onShowInputRequested before that (the
 * first field tap after setup), so the guard must answer without values instead of throwing.
 * LatinIME cannot be instantiated on a plain JVM; the switcher's decision is exercised directly.
 */
class KeyboardSwitcherHardwareKeyboardSuppressionTest {

    private val switcher = KeyboardSwitcher.getInstance()

    @Test
    fun settingsUnavailableBeforeFirstShowIsNotSuppressed() {
        // Regression: fresh install (pm clear) + SetupActivity + first field tap crashed with an
        // NPE reading SettingsValues.mHasHardwareKeyboard from onShowInputRequested.
        assertFalse(switcher.isImeSuppressedByHardwareKeyboard(
                null, KeyboardSwitchState.HIDDEN))
    }

    @Test
    fun loadedHardwareKeyboardIsSuppressedWhileHidden() {
        assertTrue(switcher.isImeSuppressedByHardwareKeyboard(
                settingsValues(hasHardwareKeyboard = true), KeyboardSwitchState.HIDDEN))
    }

    @Test
    fun loadedHardwareKeyboardOnlySuppressesWhileHidden() {
        val values = settingsValues(hasHardwareKeyboard = true)
        assertFalse(switcher.isImeSuppressedByHardwareKeyboard(
                values, KeyboardSwitchState.OTHER))
        assertFalse(switcher.isImeSuppressedByHardwareKeyboard(
                values, KeyboardSwitchState.SYMBOLS_SHIFTED))
    }

    @Test
    fun loadedNoHardwareKeyboardIsNotSuppressed() {
        assertFalse(switcher.isImeSuppressedByHardwareKeyboard(
                settingsValues(hasHardwareKeyboard = false), KeyboardSwitchState.HIDDEN))
    }

    /** SettingsValues only has an Android-resources constructor; build just the field read. */
    private fun settingsValues(hasHardwareKeyboard: Boolean): SettingsValues {
        val unsafe = sunMiscUnsafe()
        val values = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
                .invoke(unsafe, SettingsValues::class.java) as SettingsValues
        SettingsValues::class.java.getDeclaredField("mHasHardwareKeyboard").apply {
            isAccessible = true
        }.setBoolean(values, hasHardwareKeyboard)
        return values
    }

    private fun sunMiscUnsafe(): Any {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        return unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)!!
    }
}
