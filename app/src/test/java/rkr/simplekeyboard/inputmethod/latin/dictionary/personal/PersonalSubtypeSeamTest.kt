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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalDictionaryTestFixtures.Entry
import java.io.File

/**
 * Guards the subtype seam: one source of truth for the subtype id, everything personal keyed by
 * it, and a re-checked guard for a subtypeId that does not match the file it is asked to read.
 */
class PersonalSubtypeSeamTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun theActiveSubtypeIdentifierHasExactlyOneSourceOfTruth() {
        assertEquals("tt_RU", PersonalSubtypes.TATAR_RU)

        val controller = File(
            main(),
            "java/rkr/simplekeyboard/inputmethod/latin/suggestions/SuggestionsController.kt",
        ).readText()
        val latinIme = File(
            main(),
            "java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java",
        ).readText()

        // Both former literal sites still read the single constant, and neither keeps a bare
        // literal. The controller's use of it narrowed when the dictionary became multilingual:
        // the subtype a lookup is keyed by is now the ACTIVE one, and the constant is only the
        // language a call that names none means.
        assertTrue(controller.contains("DEFAULT_LANGUAGE = PersonalSubtypes.TATAR_RU"))
        assertFalse("SuggestionsController still holds a bare \"tt_RU\" literal",
            controller.contains("\"tt_RU\""))
        // LatinIME no longer compares against one language at all: it asks which dictionary the
        // live subtype has, so a third language is a spec and not another branch here.
        assertTrue(latinIme.contains("DictionaryArtifactSpec.forSubtype(locale)"))
        assertFalse("LatinIME still holds a bare \"tt_RU\" literal", latinIme.contains("\"tt_RU\""))
    }

    @Test
    fun everyNewStorageSurfaceIsKeyedBySubtypeAndHasNoDefaultStore() {
        // The reader takes a subtypeId; an unsupported subtype disables the feature outright.
        assertTrue(PersonalSubtypes.isSupported(PersonalSubtypes.TATAR_RU))
        assertFalse(PersonalSubtypes.isSupported("ru_RU"))
        assertFalse(PersonalSubtypes.isSupported("en_US"))
        // The file name and the in-file tag both carry the subtype.
        assertEquals("personal-tt_RU-s1-f1.tpers", TpersFormat.personalFileName(PersonalSubtypes.TATAR_RU))
    }

    @Test
    fun subtypeIdMismatchGuardRejectsAForeignFile() {
        val foreign = PersonalDictionaryTestFixtures.build(
            listOf(Entry("китап"), Entry("сүзлек")),
            subtypeTag = "ru_RU",
        )
        val file = temporaryFolder.newFile("personal-foreign.tpers").also { it.writeBytes(foreign) }

        // The validator rejects it, and the reader turns that into an empty dictionary.
        try {
            TpersValidator().validate(file, PersonalSubtypes.TATAR_RU)
            org.junit.Assert.fail("expected a subtype mismatch to be rejected")
        } catch (expected: PersonalDictionaryValidationException) {
            assertTrue(expected.message.orEmpty().isNotEmpty())
        }
        assertTrue(PersonalDictionaryReader().read(file, PersonalSubtypes.TATAR_RU).isEmpty)
    }

    private fun main(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
}
