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

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarWordUtils

/**
 * The shift-press case cycle over a fake editor buffer: [TatarWordUtils.caseCycleWordLength]
 * finds the word, and the real [RecapitalizeStatus] rotation turns it. The buffer is a plain
 * string; each "shift press" re-derives the word from it, exactly like the InputLogic path that
 * starts a fresh status from the live text on every press.
 */
class RecapitalizeWordCycleTest {

    private val locale = Locale("tt")

    /** One shift press on the buffer: returns the buffer after the cycle, or it unchanged. */
    private fun shiftPress(buffer: String): String {
        val wordLength = TatarWordUtils.caseCycleWordLength(buffer, "", true)
        if (wordLength <= 0) return buffer
        val word = buffer.substring(buffer.length - wordLength)
        val status = RecapitalizeStatus()
        status.enable()
        status.start(buffer.length - wordLength, buffer.length, word, locale)
        status.rotate()
        val cycled = status.recapitalizedString
        if (cycled == word) return buffer
        status.collapseAfterRangeToEnd()
        assertTrue("the collapsed after-state sits at the word end",
            status.isSetAt(buffer.length - wordLength + cycled.length,
                buffer.length - wordLength + cycled.length))
        return buffer.substring(0, buffer.length - wordLength) + cycled
    }

    @Test
    fun theCycleRunsLowerThenCapitalizedThenAllCapsThenLower() {
        var buffer = "китап"
        buffer = shiftPress(buffer)
        assertEquals("Китап", buffer)
        buffer = shiftPress(buffer)
        assertEquals("КИТАП", buffer)
        buffer = shiftPress(buffer)
        assertEquals("китап", buffer)
    }

    @Test
    fun aMixedCaseWordJoinsTheCycleAtLowercase() {
        var buffer = "кИтап"
        buffer = shiftPress(buffer)
        assertEquals("китап", buffer)
        buffer = shiftPress(buffer)
        assertEquals("Китап", buffer)
    }

    @Test
    fun aWordWithoutCasedLettersIsUntouched() {
        assertEquals("сүз.", shiftPress("сүз."))
        assertEquals("—", shiftPress("—"))
        // Caseless letters (Arabic here) make every rotation identical: the press does nothing.
        assertEquals("في", shiftPress("في"))
    }

    @Test
    fun aSingleLetterSkipsTheIdenticalAllCapsState() {
        var buffer = "ә"
        buffer = shiftPress(buffer)
        assertEquals("Ә", buffer)
        buffer = shiftPress(buffer)
        assertEquals("Capitalized and ALL CAPS coincide on one letter, so lowercase follows",
            "ә", buffer)
    }

    @Test
    fun theTatarSpecificLettersCycle() {
        assertEquals("ӘҖӨҮҺҢ", shiftPress(shiftPress("әҗөүһң")))
    }

    @Test
    fun onlyTheTrailingWordCycles() {
        assertEquals("бер Сүз", shiftPress("бер сүз"))
    }
}
