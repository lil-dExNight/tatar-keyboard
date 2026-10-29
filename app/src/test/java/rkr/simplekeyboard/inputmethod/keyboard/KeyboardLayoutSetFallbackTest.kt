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

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A subtype whose layout set resource is missing must not crash keyboard creation: the
 * KeyboardLayoutSet builder resolves the qwerty layout set instead, and only a missing qwerty
 * resource is an error. The shipped sets (tatar, russian, qwerty) must keep resolving to
 * themselves.
 */
class KeyboardLayoutSetFallbackTest {

    private fun resolver(ids: Map<String, Int>) =
        KeyboardLayoutSet.XmlIdResolver { name -> ids[name] ?: 0 }

    @Test
    fun unknownLayoutSetFallsBackToQwerty() {
        val ids = mapOf("keyboard_layout_set_qwerty" to 7)
        assertEquals(
            7,
            KeyboardLayoutSet.getXmlId(resolver(ids), "keyboard_layout_set_klingon"),
        )
    }

    @Test
    fun shippedLayoutSetsResolveToThemselves() {
        val ids = mapOf(
            "keyboard_layout_set_tatar" to 1,
            "keyboard_layout_set_russian" to 2,
            "keyboard_layout_set_qwerty" to 3,
        )
        for ((name, id) in ids) {
            assertEquals(id, KeyboardLayoutSet.getXmlId(resolver(ids), name))
        }
    }

    @Test(expected = RuntimeException::class)
    fun missingQwertyFallbackThrows() {
        KeyboardLayoutSet.getXmlId(resolver(emptyMap()), "keyboard_layout_set_klingon")
    }

    @Test
    fun shippedLayoutSetResourcesExist() {
        val candidates = listOf(File("src/main/res/xml"), File("app/src/main/res/xml"))
        val dir = candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate res/xml from ${File(".").absolutePath}")
        for (name in listOf("tatar", "russian", "qwerty")) {
            assertTrue(
                "keyboard_layout_set_$name.xml must ship; the builder resolves it by name",
                File(dir, "keyboard_layout_set_$name.xml").isFile,
            )
        }
    }
}
