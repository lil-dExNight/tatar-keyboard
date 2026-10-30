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

package rkr.simplekeyboard.inputmethod.accessibility

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.common.Constants

/** TalkBack on Android 7.x hears "dot" for password characters unless the user opted in. */
class PasswordKeyObscuringTest {

    @Test
    fun androidSevenObscuresAHiddenPasswordThroughTheSpeaker() {
        for (sdk in listOf(24, 25)) {
            assertTrue(KeyDescriptionMapper.shouldObscurePasswordKey(
                sdk, hiddenPasswordField = true, speakPasswords = false, headphonesOn = false))
        }
    }

    @Test
    fun theUsersOptInOrHeadphonesKeepFullDescriptions() {
        assertFalse(KeyDescriptionMapper.shouldObscurePasswordKey(
            24, hiddenPasswordField = true, speakPasswords = true, headphonesOn = false))
        assertFalse(KeyDescriptionMapper.shouldObscurePasswordKey(
            24, hiddenPasswordField = true, speakPasswords = false, headphonesOn = true))
    }

    @Test
    fun otherFieldsAndNewerAndroidKeepFullDescriptions() {
        assertFalse(KeyDescriptionMapper.shouldObscurePasswordKey(
            25, hiddenPasswordField = false, speakPasswords = false, headphonesOn = false))
        for (sdk in listOf(26, 30, 35)) {
            assertFalse(KeyDescriptionMapper.shouldObscurePasswordKey(
                sdk, hiddenPasswordField = true, speakPasswords = false, headphonesOn = false))
        }
    }

    @Test
    fun onlyKeysThatTypeTextAreObscured() {
        for (code in listOf('a'.code, 'ә'.code, 'Я'.code, '7'.code, '@'.code,
                Constants.CODE_OUTPUT_TEXT)) {
            assertTrue("code $code", KeyDescriptionMapper.isTypedCharacterCode(code))
        }
        for (code in listOf(Constants.CODE_SHIFT, Constants.CODE_DELETE, Constants.CODE_SPACE,
                Constants.CODE_ENTER, Constants.CODE_TAB, Constants.CODE_SHIFT_ENTER,
                Constants.CODE_SWITCH_ALPHA_SYMBOL, Constants.CODE_LANGUAGE_SWITCH,
                Constants.CODE_EMOJI, Constants.CODE_SETTINGS)) {
            assertFalse("code $code", KeyDescriptionMapper.isTypedCharacterCode(code))
        }
    }

    @Test
    fun bothKeyDelegatesDescribeKeysThroughTheObscuringMapper() {
        val root = listOf(File("src/main"), File("app/src/main")).first(File::isDirectory)
        val dir = File(root, "java/rkr/simplekeyboard/inputmethod/accessibility")
        val mapper = File(dir, "KeyDescriptionMapper.kt").readText()
        val describe = mapper.substringAfter("fun getDescription(")
        val obscure = describe.indexOf(
            "if (isTypedCharacterCode(code) && obscuresPasswordKeys(context, keyboard)) {")
        assertTrue("the obscuring check runs before any label is resolved",
            obscure in 0 until describe.indexOf("TATAR_LETTERS[code]"))
        for (name in listOf("KeyboardAccessibilityDelegate.kt",
                "MoreKeysKeyboardAccessibilityDelegate.kt")) {
            val delegate = File(dir, name).readText()
            assertEquals(name, 1, delegate.split("KeyDescriptionMapper.getDescription(").size - 1)
        }
        for (locale in listOf("values", "values-ru", "values-tt")) {
            assertTrue(locale, File(root, "res/$locale/strings-a11y.xml").readText()
                .contains("name=\"spoken_description_dot\""))
        }
    }
}
