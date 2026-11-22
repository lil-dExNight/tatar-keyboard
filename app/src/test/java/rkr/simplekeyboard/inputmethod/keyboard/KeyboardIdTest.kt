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

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.Subtype

/** Every layout preference is part of the value identity used by the static keyboard cache. */
class KeyboardIdTest {

    @Test
    fun identicalLayoutFlagsHaveTheSameIdentity() {
        val first = keyboardId { mShowMoreKeys = true }
        val second = keyboardId { mShowMoreKeys = true }

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun specialCharactersToggleChangesCacheIdentity() {
        assertFlagChangesCacheIdentity { mShowMoreKeys = true }
    }

    @Test
    fun numberRowToggleChangesCacheIdentity() {
        assertFlagChangesCacheIdentity { mShowNumberRow = true }
    }

    @Test
    fun emojiKeyToggleChangesCacheIdentity() {
        assertFlagChangesCacheIdentity { mShowEmojiKey = true }
    }

    private fun assertFlagChangesCacheIdentity(enableFlag: KeyboardLayoutSet.Params.() -> Unit) {
        val withoutFlag = keyboardId()
        val withFlag = keyboardId(enableFlag)

        assertNotEquals(withoutFlag, withFlag)
        assertNotEquals(withoutFlag.hashCode(), withFlag.hashCode())
        val cache = hashMapOf(withoutFlag to "old layout")
        assertNull("a toggle must not hit the previous layout", cache[withFlag])
    }

    private fun keyboardId(
        configure: KeyboardLayoutSet.Params.() -> Unit = {},
    ): KeyboardId {
        val params = allocate(KeyboardLayoutSet.Params::class.java).apply {
            mEditorInfo = allocate(EditorInfo::class.java)
            mSubtype = Subtype("tt_RU", "tatar", 0, false, null)
            configure()
        }
        return KeyboardId(KeyboardId.ELEMENT_ALPHABET, params)
    }

    /** Android-free value construction; the test reads only fields, never framework methods. */
    private fun <T> allocate(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply {
            isAccessible = true
        }.get(null)
        @Suppress("UNCHECKED_CAST")
        return unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, type) as T
    }
}
