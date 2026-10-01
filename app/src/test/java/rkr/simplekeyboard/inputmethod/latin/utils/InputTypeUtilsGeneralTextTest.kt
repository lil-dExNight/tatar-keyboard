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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [InputTypeUtils.isGeneralTextInputType] decides where two quick spaces give ". ": every text
 * variation, the other input classes and the flags that must not change the answer.
 */
class InputTypeUtilsGeneralTextTest {

    private val text = InputType.TYPE_CLASS_TEXT

    private val generalVariations = mapOf(
        "normal" to InputType.TYPE_TEXT_VARIATION_NORMAL,
        "email subject" to InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT,
        "short message" to InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
        "long message" to InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE,
        "person name" to InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
        "postal address" to InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS,
        "web edit text" to InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT,
    )

    private val excludedVariations = mapOf(
        "uri" to InputType.TYPE_TEXT_VARIATION_URI,
        "email address" to InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        "web email address" to InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
        "password" to InputType.TYPE_TEXT_VARIATION_PASSWORD,
        "visible password" to InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        "web password" to InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        "phonetic" to InputType.TYPE_TEXT_VARIATION_PHONETIC,
        "filter" to InputType.TYPE_TEXT_VARIATION_FILTER,
    )

    private val flags = listOf(
        0,
        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
        InputType.TYPE_TEXT_FLAG_MULTI_LINE,
        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
    )

    @Test
    fun everyTextVariationIsClassified() {
        // The two maps cover every variation value the text class defines (0x00 to 0xe0).
        val all = (generalVariations.values + excludedVariations.values).toSet()
        assertEquals((0..0xe0 step 0x10).toSet(), all)
    }

    @Test
    fun proseVariationsAreGeneralTextWhateverTheFlags() {
        for ((name, variation) in generalVariations) {
            for (flag in flags) {
                assertEquals("$name with flags 0x${flag.toString(16)}", true,
                    InputTypeUtils.isGeneralTextInputType(text or variation or flag))
            }
        }
    }

    @Test
    fun queryAddressAndPasswordVariationsAreNotWhateverTheFlags() {
        for ((name, variation) in excludedVariations) {
            for (flag in flags) {
                assertEquals("$name with flags 0x${flag.toString(16)}", false,
                    InputTypeUtils.isGeneralTextInputType(text or variation or flag))
            }
        }
    }

    @Test
    fun otherInputClassesAndTypeNullAreNotGeneralText() {
        val types = mapOf(
            "TYPE_NULL" to InputType.TYPE_NULL,
            "number" to InputType.TYPE_CLASS_NUMBER,
            "signed decimal number" to (InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL),
            "number password" to (InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_VARIATION_PASSWORD),
            "phone" to InputType.TYPE_CLASS_PHONE,
            "datetime" to InputType.TYPE_CLASS_DATETIME,
            "date" to (InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE),
            "time" to (InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME),
        )
        for ((name, type) in types) {
            assertFalse(name, InputTypeUtils.isGeneralTextInputType(type))
        }
    }
}
