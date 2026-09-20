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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fail-closed reader of the sentence-start table (`SentStartIndex`, P4 of
 * docs/TT-SUGGESTIONS.md): file order is the ranking, comment and blank lines are skipped,
 * malformed rows are dropped, a fully unreadable input is [SentStartIndex.EMPTY], and no
 * exception ever escapes — the exact `EmojiSuggestIndex` posture.
 */
class SentStartIndexTest {

    @Test
    fun theFileOrderIsTheRanking() {
        val index = SentStartIndex.parse("# header\nбу\t72870\nул\t45080\nәмма\t18443\n")
        assertEquals(3, index.entryCount)
        assertEquals(listOf("бу", "ул", "әмма"), index.topWords(3))
    }

    @Test
    fun topWordsCapsAtTheRequestAndAtTheTable() {
        val index = SentStartIndex.parse("бу\t9\nул\t5\nәмма\t3\n")
        assertEquals(listOf("бу", "ул"), index.topWords(2))
        assertEquals(listOf("бу", "ул", "әмма"), index.topWords(64))
    }

    @Test
    fun commentsBlanksAndCrlfAreSkipped() {
        val index = SentStartIndex.parse("# a\r\n\r\nбу\t1\r\n# b\nул\t2\n")
        assertEquals(listOf("бу", "ул"), index.topWords(10))
    }

    @Test
    fun malformedRowsAreDroppedAndTheRestSurvives() {
        val index = SentStartIndex.parse(
            "бу\t9\n" +
                "\t5\n" + // no word
                "ул\t\n" + // no frequency
                "ә\tнечисло\n" + // not a number
                "шулай\t0\n" + // not positive
                "шул\t-3\n" + // not positive
                "әмма\t4\textra\n" + // a third field
                "notab\n" +
                "э\t2\n",
        )
        assertEquals(listOf("бу", "э"), index.topWords(10))
    }

    @Test
    fun aDuplicateWordKeepsItsFirstRow() {
        val index = SentStartIndex.parse("бу\t9\nул\t5\nбу\t1\n")
        assertEquals(listOf("бу", "ул"), index.topWords(10))
    }

    @Test
    fun anUnusableInputIsEmptyNeverAnException() {
        assertTrue(SentStartIndex.parse("").isEmpty)
        assertTrue(SentStartIndex.parse("# only a header\n").isEmpty)
        assertTrue(SentStartIndex.parse("notab\n\t\t\n").isEmpty)
        assertTrue(SentStartIndex.EMPTY.isEmpty)
        assertTrue(SentStartIndex.EMPTY.topWords(3).isEmpty())
    }

    @Test
    fun anOverlongRowIsJunk() {
        val index = SentStartIndex.parse("бу\t9\n${"а".repeat(600)}\t1\nул\t5\n")
        assertEquals(listOf("бу", "ул"), index.topWords(10))
    }
}
