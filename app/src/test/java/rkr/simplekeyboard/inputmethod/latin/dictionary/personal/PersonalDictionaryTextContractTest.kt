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

package rkr.simplekeyboard.inputmethod.latin.dictionary.personal

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalDictionaryTestFixtures.Entry
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarWordUtils
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarWordUtils.PrefixCasing
import java.io.File

/**
 * One named test per point of the four-point text contract for personal records: stored form
 * versus normalized form, casing at each shift state, and exact-word exclusion.
 */
class PersonalDictionaryTextContractTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val reader = PersonalDictionaryReader()

    /**
     * Point 1: the personal dictionary stores the ORIGINAL form of the record, while its normalized
     * NFC lowercase form drives sorting, dedup, filters and search. Deduplication by the normalized
     * form is additionally pinned by `TpersValidatorTest.rejectsDuplicateNormalizedForms`.
     */
    @Test
    fun point1_storesRawFormWhileNormalizedFormDrivesSearch() {
        val dictionary = reader.read(
            write(listOf(Entry("Гүзәл", count = 5), Entry("китап", count = 9))),
            PersonalSubtypes.TATAR_RU,
        )

        // The raw uppercase form is what is stored and returned...
        assertEquals("Гүзәл", dictionary.rawFormAt(0))
        // ...while search keys off the normalized form: a lowercase prefix finds the capital record.
        assertEquals(listOf("Гүзәл"), dictionary.lookupRawForms("гүз"))
    }

    /**
     * Point 2: at a LOWER prefix the personal record is shown in its own stored casing, so
     * [TatarWordUtils.applyCasing] is not applied — and even if it were, its LOWER branch already
     * returns the candidate unchanged.
     */
    @Test
    fun point2_lowerPrefixLeavesTheStoredCasingUntouched() {
        assertEquals("Гүзәл", TatarWordUtils.applyCasing("Гүзәл", PrefixCasing.LOWER))
    }

    /**
     * Point 3: for personal records the exact-word exclusion compares the NORMALIZED form, not raw
     * bytes. A personal "Гүзәл" is excluded when the user has typed "гүзәл"; a longer form with the
     * same prefix stays a candidate.
     */
    @Test
    fun point3_exactWordExclusionComparesTheNormalizedFormNotRawBytes() {
        val dictionary = reader.read(
            write(listOf(Entry("Гүзәл"), Entry("гүзәллек"))),
            PersonalSubtypes.TATAR_RU,
        )

        // Typed "гүзәл": the record whose normalized form equals it is excluded, "гүзәллек" remains.
        assertEquals(listOf("гүзәллек"), dictionary.lookupRawForms("гүзәл"))
    }

    /**
     * Point 4: at INITIAL_CAPS and ALL_CAPS the same [TatarWordUtils.applyCasing] that runs for
     * dictionary candidates runs for personal records too, deliberately overwriting the stored
     * casing; only at LOWER is the stored form shown verbatim.
     */
    @Test
    fun point4_initialAndAllCapsReapplyCasingOverwritingTheStoredForm() {
        // Applied to the normalized form, exactly as for a dictionary candidate.
        assertEquals("Гүзәл", TatarWordUtils.applyCasing("гүзәл", PrefixCasing.INITIAL_CAPS))
        assertEquals("ГҮЗӘЛ", TatarWordUtils.applyCasing("гүзәл", PrefixCasing.ALL_CAPS))
        // LOWER is the only branch that keeps the stored casing.
        assertEquals("Гүзәл", TatarWordUtils.applyCasing("Гүзәл", PrefixCasing.LOWER))
    }

    private fun write(entries: List<Entry>): File =
        temporaryFolder.newFile("personal-${System.nanoTime()}.tpers")
            .also { it.writeBytes(PersonalDictionaryTestFixtures.build(entries)) }
}
