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

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec

/**
 * T5 (ROADMAP Phase 1, `docs/ROADMAP-P1.md`): the artifact registry
 * (`DictionaryArtifactSpec.ALL`) is the ONE list of shipped languages. `PersonalSubtypes`
 * carries only DATA (the tag constants, the alphabet sets); every predicate — its own
 * [PersonalSubtypes.alphabetFor]/[PersonalSubtypes.isSupported], the suggestions eligibility
 * in LatinIME, the settings screens — resolves through the registry, so a second
 * hand-maintained language list cannot drift back in.
 *
 * Two pin layers: the runtime agreement (registry languages are exactly the supported
 * subtypes, with the very alphabet instances the specs carry) and the source shape (the
 * delegation is present, the `when`-enumeration it replaced is gone, and each spec entry
 * carries its alphabet).
 */
class PersonalSubtypeRegistryContractTest {

    @Test
    fun theRegistryAndThePersonalPredicateAnswerTheSameLanguages() {
        for (spec in DictionaryArtifactSpec.ALL) {
            assertTrue("${spec.languageTag} must be personal-supported",
                PersonalSubtypes.isSupported(spec.languageTag))
            assertSame("${spec.languageTag} must expose its spec's own alphabet",
                spec.personalAlphabet, PersonalSubtypes.alphabetFor(spec.languageTag))
            assertTrue("${spec.languageTag} must carry a personal alphabet in the registry",
                spec.personalAlphabet != null)
        }
        // The probe side: a subtype absent from the registry is unsupported everywhere.
        for (probe in listOf("en_US", "ru_RU", "tt", "en", "")) {
            assertFalse("isSupported($probe)", PersonalSubtypes.isSupported(probe))
            assertEquals(probe, null, DictionaryArtifactSpec.forSubtype(probe))
        }
    }

    /** The pre-T5 behavior, pinned identical: exact alphabets per shipped language. */
    @Test
    fun theAlphabetsAreTheOnesTheStoresHaveAlwaysFilteredBy() {
        assertEquals(39, PersonalSubtypes.TATAR_RU_ALPHABET.size)
        assertEquals(33, PersonalSubtypes.RUSSIAN_ALPHABET.size)
        // The marker letters: the Tatar set carries its six specific letters, the Russian one
        // carries ё, and neither carries the other's markers.
        for (marker in "әөүҗңһ") {
            assertTrue("$marker must be a Tatar letter", marker.code in PersonalSubtypes.TATAR_RU_ALPHABET)
            assertFalse("$marker must not be a Russian letter", marker.code in PersonalSubtypes.RUSSIAN_ALPHABET)
        }
        assertTrue('ё'.code in PersonalSubtypes.RUSSIAN_ALPHABET)
    }

    @Test
    fun personalSubtypesHoldsDataAndDelegatesEveryPredicateToTheRegistry() {
        val source = File(main(), REL_PATH).readText()
        // The delegation is there...
        assertTrue(source.contains("DictionaryArtifactSpec.forSubtype(subtypeId)?.personalAlphabet"))
        // ...and the hand-maintained enumeration it replaced is gone for good.
        assertFalse("a subtype switch is a second language list",
            source.contains("when (subtypeId)"))
        assertFalse("an arrow branch off TATAR_RU is a second language list",
            source.contains("TATAR_RU ->"))
        assertFalse("an arrow branch off RUSSIAN is a second language list",
            source.contains("RUSSIAN ->"))
    }

    @Test
    fun everyRegistryEntryCarriesItsAlphabetFromTheOneDataSource() {
        val contracts = File(main(), CONTRACTS_REL_PATH).readText()
        for (spec in DictionaryArtifactSpec.ALL) {
            val assignment = "personalAlphabet = PersonalSubtypes."
            assertTrue("${spec.languageTag}: the spec must name its alphabet in the registry",
                contracts.contains(assignment))
        }
        // Both shipped entries, pinned exactly — a dropped alphabet here is a silent
        // "personal dictionary off" for that language.
        assertTrue(contracts.contains("personalAlphabet = PersonalSubtypes.TATAR_RU_ALPHABET"))
        assertTrue(contracts.contains("personalAlphabet = PersonalSubtypes.RUSSIAN_ALPHABET"))
    }

    private fun main(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")

    private companion object {
        const val REL_PATH =
            "java/rkr/simplekeyboard/inputmethod/latin/dictionary/personal/PersonalSubtypes.kt"
        const val CONTRACTS_REL_PATH =
            "java/rkr/simplekeyboard/inputmethod/latin/dictionary/storage/DictionaryStorageContracts.kt"
    }
}
