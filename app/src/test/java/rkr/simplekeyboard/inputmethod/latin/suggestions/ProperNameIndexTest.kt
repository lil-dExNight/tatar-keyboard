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

package rkr.simplekeyboard.inputmethod.latin.suggestions

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProperNameIndexTest {
    @Test
    fun parsesKeysAndDisplayForms() {
        val index = ProperNameIndex.parse(
            "# comment\n\nсанкт-петербург\tСанкт-Петербург\nтатарстан\tТатарстан\n"
        )
        assertEquals(2, index.entryCount)
        assertEquals("Санкт-Петербург", index.displayFor("санкт-петербург"))
        assertEquals("Татарстан", index.displayFor("татарстан"))
        assertNull(index.displayFor("казан"))
    }

    @Test
    fun readsAnInputStream() {
        val index = ProperNameIndex.parse(
            ByteArrayInputStream("казан\tКазан\n".toByteArray(Charsets.UTF_8))
        )
        assertEquals("Казан", index.displayFor("казан"))
    }

    @Test
    fun aDuplicateKeyKeepsTheFirstRow() {
        val index = ProperNameIndex.parse("казан\tКазан\nказан\tКазан\n")
        assertEquals("Казан", index.displayFor("казан"))
    }

    @Test
    fun invalidRowsAreDroppedAndAnEmptyResultFailsClosed() {
        val index = ProperNameIndex.parse(
            """
            КАЗАН	КАЗАН
            казан	Казан
            казан	Казан	хвост
            татарстан	Татарстан
            """.trimIndent()
        )
        assertEquals(2, index.entryCount)
        assertEquals("Казан", index.displayFor("казан"))
        assertEquals("Татарстан", index.displayFor("татарстан"))
        assertTrue(ProperNameIndex.parse("").isEmpty)
        assertTrue(ProperNameIndex.parse("\uD800\uDC00").isEmpty)
    }

    @Test
    fun displayMustLowercaseToTheKey() {
        val index = ProperNameIndex.parse("казан\tКазан\nтатарстан\tМәскәү\n")
        assertEquals(1, index.entryCount)
        assertNull(index.displayFor("татарстан"))
    }
}
