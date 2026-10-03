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

package rkr.simplekeyboard.inputmethod.latin.settings.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * The strict JSON subset behind the backup manifest: what the reader accepts, what it rejects,
 * and that the writer's output reads back unchanged.
 */
class BackupJsonTest {

    @Test
    fun valuesRoundTrip() {
        val value = linkedMapOf(
            "format" to 1L,
            "package" to "org.tatarkeyboard.ime",
            "flag" to true,
            "nothing" to null,
            "entries" to listOf(linkedMapOf("path" to "a/b.zip", "size" to 0L)),
        )
        val text = BackupJson.write(value)
        assertEquals(value, BackupJson.parse(text))
    }

    @Test
    fun escapesRoundTrip() {
        val tricky = "quote\" backslash\\ tab\t newline\n control\u0007 emoji 🎉 cyrillic сәләм"
        assertEquals(tricky, BackupJson.parse(BackupJson.write(tricky)))
    }

    @Test
    fun rejectsDuplicateKeys() {
        assertRejected("{\"a\":1,\"a\":2}")
    }

    @Test
    fun rejectsNonIntegerNumbers() {
        assertRejected("{\"a\":1.5}")
        assertRejected("{\"a\":1e3}")
    }

    @Test
    fun rejectsTrailingContent() {
        assertRejected("{} {}")
        assertRejected("true false")
    }

    @Test
    fun rejectsUnescapedControlCharacters() {
        assertRejected("{\"a\":\"line\nbreak\"}")
    }

    @Test
    fun rejectsTooDeepNesting() {
        val deep = "[".repeat(16) + "0" + "]".repeat(16)
        assertRejected(deep)
    }

    @Test
    fun rejectsLoneSurrogates() {
        // A high surrogate escape without its pair must not parse.
        assertRejected("{\"a\":\"\\uD83D\"}")
    }

    @Test
    fun rejectsUnknownEscapes() {
        assertRejected("{\"a\":\"\\x\"}")
    }

    @Test
    fun rejectsNonStringKeys() {
        assertRejected("{1:2}")
    }

    private fun assertRejected(text: String) {
        try {
            BackupJson.parse(text)
            fail("must reject: $text")
        } catch (expected: BackupJson.JsonException) {
        }
    }
}
