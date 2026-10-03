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

package rkr.simplekeyboard.inputmethod.latin.lab

import rkr.simplekeyboard.inputmethod.R
import rkr.simplekeyboard.inputmethod.latin.utils.SubtypeLocaleUtils

/**
 * The three arms of the fifth-row order experiment. Arm A is the shipped order; arms B and C
 * reorder the six extra letters only. The arm is a developer setting, applied when an alphabet
 * keyboard of the Tatar layout set is built; the per-arm letter orders are pinned by
 * FifthRowArmTest.
 */
object FifthRowArm {
    const val ARM_A = 0
    const val ARM_B = 1
    const val ARM_C = 2

    /** The arm every build ships with and every unknown stored value resolves to. */
    const val DEFAULT = ARM_A

    /** The resource name KeyboardLayoutSet builds for the Tatar layout set. */
    private val TATAR_LAYOUT_SET = "keyboard_layout_set_" + SubtypeLocaleUtils.LAYOUT_TATAR

    /** Unknown stored values resolve to the shipped arm. */
    @JvmStatic
    fun normalize(stored: Int): Int = if (stored in ARM_A..ARM_C) stored else DEFAULT

    /**
     * The alphabet keyboard resource for [layoutSetName] under [arm], or [defaultXmlId] when the
     * layout set is not part of the experiment or the arm is the shipped one.
     */
    @JvmStatic
    fun alphabetKeyboardXmlId(layoutSetName: String, defaultXmlId: Int, arm: Int): Int {
        if (layoutSetName != TATAR_LAYOUT_SET) return defaultXmlId
        return when (arm) {
            ARM_B -> R.xml.kbd_tatar_b
            ARM_C -> R.xml.kbd_tatar_c
            else -> defaultXmlId
        }
    }
}
