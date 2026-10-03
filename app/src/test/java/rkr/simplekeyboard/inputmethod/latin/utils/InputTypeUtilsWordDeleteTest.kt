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

package rkr.simplekeyboard.inputmethod.latin.utils

import android.text.InputType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [InputTypeUtils.isWordDeleteAllowedType]: the word-delete flick edits prose fields and stays
 * out of every password variation and of the number-family classes, where a last "word" is not
 * a meaningful unit.
 */
class InputTypeUtilsWordDeleteTest {

    @Test
    fun proseFieldsAllowTheWordDelete() {
        assertTrue(InputTypeUtils.isWordDeleteAllowedType(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL))
        assertTrue(InputTypeUtils.isWordDeleteAllowedType(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE
                or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        assertTrue("an email field holds prose-like text",
            InputTypeUtils.isWordDeleteAllowedType(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertTrue("a raw editor reports no class and keeps the gesture",
            InputTypeUtils.isWordDeleteAllowedType(InputType.TYPE_NULL))
    }

    @Test
    fun passwordVariationsBlockTheWordDelete() {
        assertFalse(InputTypeUtils.isWordDeleteAllowedType(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertFalse(InputTypeUtils.isWordDeleteAllowedType(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertFalse(InputTypeUtils.isWordDeleteAllowedType(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertFalse(InputTypeUtils.isWordDeleteAllowedType(
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
    }

    @Test
    fun numberFamilyClassesBlockTheWordDelete() {
        assertFalse(InputTypeUtils.isWordDeleteAllowedType(InputType.TYPE_CLASS_NUMBER))
        assertFalse(InputTypeUtils.isWordDeleteAllowedType(InputType.TYPE_CLASS_PHONE))
        assertFalse(InputTypeUtils.isWordDeleteAllowedType(
            InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE))
    }
}
