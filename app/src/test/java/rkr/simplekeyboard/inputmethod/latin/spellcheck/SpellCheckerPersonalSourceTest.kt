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

package rkr.simplekeyboard.inputmethod.latin.spellcheck

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalDictionaryTestFixtures
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersValidator

class SpellCheckerPersonalSourceTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun directBootDoesNotResolveOrReadPersonalFiles() {
        var unlocked = false
        var directoryReads = 0
        var fileReads = 0
        val directory = temporary.newFolder("personal")
        writePersonal(directory, PersonalSubtypes.TATAR_RU, "шәһәр")
        val source = FilePersonalDictionarySource(
            unlocked = { unlocked },
            directory = { directoryReads++; directory },
            reader = PersonalSnapshotReader { file, subtypeId ->
                fileReads++
                PersonalDictionary.of(TpersValidator().validate(file, subtypeId))
            },
        )

        assertSame(PersonalDictionary.EMPTY, source.snapshot(PersonalSubtypes.TATAR_RU))
        assertEquals(0, directoryReads)
        assertEquals(0, fileReads)

        unlocked = true
        assertTrue(source.snapshot(PersonalSubtypes.TATAR_RU).indexOfNormalized("шәһәр") >= 0)
        assertEquals(1, fileReads)

        unlocked = false
        assertSame(PersonalDictionary.EMPTY, source.snapshot(PersonalSubtypes.TATAR_RU))
        assertEquals(1, fileReads)
    }

    @Test
    fun tatarAndRussianSnapshotsAreReusedIndependentlyUntilTheirOwnFileChanges() {
        val directory = temporary.newFolder("personal")
        writePersonal(directory, PersonalSubtypes.TATAR_RU, "шәһәр")
        writePersonal(directory, PersonalSubtypes.RUSSIAN, "привет")
        val reads = hashMapOf(PersonalSubtypes.TATAR_RU to 0, PersonalSubtypes.RUSSIAN to 0)
        val source = FilePersonalDictionarySource(
            unlocked = { true },
            directory = { directory },
            reader = PersonalSnapshotReader { file, subtypeId ->
                reads[subtypeId] = reads.getValue(subtypeId) + 1
                PersonalDictionary.of(TpersValidator().validate(file, subtypeId))
            },
        )

        val tatar = source.snapshot(PersonalSubtypes.TATAR_RU)
        val russian = source.snapshot(PersonalSubtypes.RUSSIAN)
        assertSame(tatar, source.snapshot(PersonalSubtypes.TATAR_RU))
        assertSame(russian, source.snapshot(PersonalSubtypes.RUSSIAN))
        assertSame(tatar, source.snapshot(PersonalSubtypes.TATAR_RU))
        assertEquals(1, reads.getValue(PersonalSubtypes.TATAR_RU))
        assertEquals(1, reads.getValue(PersonalSubtypes.RUSSIAN))

        val tatarFile = personalFile(directory, PersonalSubtypes.TATAR_RU)
        val oldModified = tatarFile.lastModified()
        writePersonal(directory, PersonalSubtypes.TATAR_RU, "өстәл")
        assertTrue(tatarFile.setLastModified(oldModified + 10_000L))
        val changedTatar = source.snapshot(PersonalSubtypes.TATAR_RU)
        assertSame(russian, source.snapshot(PersonalSubtypes.RUSSIAN))
        assertEquals(2, reads.getValue(PersonalSubtypes.TATAR_RU))
        assertEquals(1, reads.getValue(PersonalSubtypes.RUSSIAN))
        assertTrue(changedTatar.indexOfNormalized("өстәл") >= 0)
    }

    private fun writePersonal(directory: File, subtypeId: String, word: String) {
        personalFile(directory, subtypeId).writeBytes(
            PersonalDictionaryTestFixtures.build(
                listOf(PersonalDictionaryTestFixtures.Entry(word)),
                subtypeTag = subtypeId,
            ),
        )
    }

    private fun personalFile(directory: File, subtypeId: String): File =
        File(directory, TpersFormat.personalFileName(subtypeId))
}
