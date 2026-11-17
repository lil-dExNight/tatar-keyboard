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

package rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes

class PersonalBigramWordFilterTest {
    private val alphabet = requireNotNull(PersonalSubtypes.alphabetFor(PersonalSubtypes.TATAR_RU))

    @Test
    fun anInternalAsciiHyphenSurvivesNormalization() {
        assertEquals(
            "азык-төлек",
            PersonalBigramWordFilter.acceptedNormalizedForm("азык-төлек", alphabet),
        )
        assertEquals(
            "азык-төлек",
            PersonalBigramWordFilter.acceptedNormalizedForm("Азык-төлек", alphabet),
        )
        assertEquals(
            "азык-төлек",
            PersonalBigramWordFilter.acceptedNormalizedForm("АЗЫК-ТӨЛЕК", alphabet),
        )
    }

    @Test
    fun hyphensOutsideTwoAlphabetLettersAndOtherDashesAreRejected() {
        val words = listOf(
            "-азык",
            "азык-",
            "азык--төлек",
            "азык–төлек",
            "азык—төлек",
            "азык‐төлек",
            "азык'төлек",
            "азык’төлек",
        )
        for (word in words) {
            assertNull(word, PersonalBigramWordFilter.acceptedNormalizedForm(word, alphabet))
        }
    }

    @Test
    fun normalizationDoesNotFoldDashesOrRemoveCombiningMarks() {
        assertEquals("азык–төлек", PersonalBigramWordFilter.normalize("АЗЫК–ТӨЛЕК"))
        assertNull(PersonalBigramWordFilter.acceptedNormalizedForm("АЗЫК–ТӨЛЕК", alphabet))
        assertNull(PersonalBigramWordFilter.acceptedNormalizedForm("азык\u0301", alphabet))
    }
}
