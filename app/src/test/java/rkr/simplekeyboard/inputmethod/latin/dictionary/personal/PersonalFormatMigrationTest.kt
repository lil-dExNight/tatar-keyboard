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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/** Current s1-f1 fixtures and the fail-closed registration/writer gate for future migrations. */
class PersonalFormatMigrationTest {

    @Test
    fun formatVersionsKeepValueSemanticsForRegistryPaths() {
        val first = PersonalFormatVersion(1, 2)
        val equal = PersonalFormatVersion(1, 2)

        assertEquals(first, equal)
        assertEquals(first.hashCode(), equal.hashCode())
        assertEquals(1, setOf(first, equal).size)
    }

    @Test
    fun everyCurrentS1F1SnapshotRoundTripsThroughTheMigrationReader() {
        val registry = PersonalFormatMigrationRegistry()
        for (fixture in currentFixtures()) {
            val plan = registry.planFor(fixture.kind, PERSONAL_S1_F1)!!

            val restored = registry.migrate(plan, fixture.bytes, fixture.subtypeId)

            assertSame("the no-op path must not rewrite the current representation",
                fixture.bytes, restored)
            assertArrayEquals(fixture.bytes, restored)
            registry.validateTarget(plan, restored, fixture.subtypeId)
        }
    }

    @Test
    fun currentFileNamesAndLegacyNamesStayIdentical() {
        val subtype = PersonalSubtypes.TATAR_RU
        assertEquals("personal-tt_RU-s1-f1.tpers", TpersFormat.personalFileName(subtype))
        assertEquals("personal-bigrams-tt_RU-s1-f1.tpersb",
            TpersbFormat.personalBigramsFileName(subtype))
        assertEquals("personal-emoji-tt_RU-s1-f1.tpersem", TpersemFormat.personalEmojiFileName(subtype))
        assertEquals("personal-refused-tt_RU-s1-f1.tref", TrefFormat.refusedCorrectionsFileName(subtype))
        assertEquals("sentstart-tt_RU-s1-f1.tsent", TsentFormat.sentStartFileName(subtype))
        assertEquals("shortcuts-s1-f1.tcut", TcutFormat.shortcutsFileName())
        for (kind in PersonalStoreFormat.entries) {
            val tag = if (kind == PersonalStoreFormat.SHORTCUTS) null else subtype
            assertEquals(currentFileName(kind, subtype), PersonalFormats.s1f1FileName(kind, tag))
        }
    }

    @Test
    fun everyUnregisteredFutureWriterVersionFailsClosed() {
        val registry = PersonalFormatMigrationRegistry()
        val future = PersonalFormatVersion(1, 2)
        for (kind in PersonalStoreFormat.entries) {
            assertThrows(PersonalFormatMigrationException::class.java) {
                registry.requireWriter(kind, future)
            }
        }
    }

    @Test
    fun aStepWhosePredecessorIsMissingCannotBeRegistered() {
        val registry = PersonalFormatMigrationRegistry()
        val step = opaqueStep(
            source = PersonalFormatVersion(1, 2),
            target = PersonalFormatVersion(1, 3),
        )

        assertThrows(PersonalFormatMigrationException::class.java) { registry.register(step) }
        assertThrows(PersonalFormatMigrationException::class.java) {
            registry.requireWriter(PersonalStoreFormat.WORDS, PersonalFormatVersion(1, 3))
        }
    }

    @Test
    fun aRegisteredPathValidatesItsSourceAndOutputBeforeEnablingTheWriter() {
        val registry = PersonalFormatMigrationRegistry()
        val fixture = currentFixtures().first { it.kind == PersonalStoreFormat.WORDS }
        val target = PersonalFormatVersion(1, 2)
        var sourceReads = 0
        var targetReads = 0
        registry.register(PersonalFormatMigration(
            kind = fixture.kind,
            source = PERSONAL_S1_F1,
            target = target,
            sourceReader = PersonalFormatReader { bytes, subtype ->
                sourceReads++
                TpersValidator().validate(bytes, subtype!!)
            },
            targetReader = PersonalFormatReader { bytes, subtype ->
                targetReads++
                TpersValidator().validate(bytes, subtype!!)
            },
            targetFileName = { "personal-$it-s1-f2.tpers" },
            migrate = { bytes, _ -> bytes.copyOf() },
        ))
        registry.requireWriter(fixture.kind, target)

        val plan = registry.planFor(fixture.kind, target)!!
        val migrated = registry.migrate(plan, fixture.bytes, fixture.subtypeId)

        assertArrayEquals(fixture.bytes, migrated)
        assertEquals(1, sourceReads)
        assertEquals(1, targetReads)
        assertEquals("personal-tt_RU-s1-f2.tpers", plan.targetFileName(fixture.subtypeId))
    }

    @Test
    fun aFailedTargetReaderFailsTheMigrationClosed() {
        val registry = PersonalFormatMigrationRegistry()
        val fixture = currentFixtures().first { it.kind == PersonalStoreFormat.REFUSALS }
        val target = PersonalFormatVersion(1, 2)
        registry.register(PersonalFormatMigration(
            kind = fixture.kind,
            source = PERSONAL_S1_F1,
            target = target,
            sourceReader = registry.currentReader(fixture.kind),
            targetReader = PersonalFormatReader { _, _ ->
                throw PersonalFormatMigrationException("target fixture rejected")
            },
            targetFileName = { "personal-refused-$it-s1-f2.tref" },
            migrate = { bytes, _ -> bytes.copyOf() },
        ))

        val plan = registry.planFor(fixture.kind, target)!!
        assertThrows(PersonalFormatMigrationException::class.java) {
            registry.migrate(plan, fixture.bytes, fixture.subtypeId)
        }
    }

    private fun currentFixtures(): List<Fixture> {
        val subtype = PersonalSubtypes.TATAR_RU
        return listOf(
            Fixture(
                PersonalStoreFormat.WORDS,
                PersonalDictionaryTestFixtures.build(
                    entries = listOf(
                        PersonalDictionaryTestFixtures.Entry("Гүзәл", count = 3, serial = 4),
                        PersonalDictionaryTestFixtures.Entry("абыйлар", count = 1, serial = 2),
                    ),
                    schemaId = PERSONAL_S1_SCHEMA_ID,
                    formatVersion = PERSONAL_F1_FORMAT_VERSION,
                ),
                subtype,
            ),
            Fixture(
                PersonalStoreFormat.BIGRAMS,
                PersonalBigramTestFixtures.build(
                    entries = listOf(
                        PersonalBigramTestFixtures.Entry("китап", "укыйм", usage = 2, serial = 3),
                    ),
                    schemaId = PERSONAL_S1_SCHEMA_ID,
                    formatVersion = PERSONAL_F1_FORMAT_VERSION,
                ),
                subtype,
            ),
            Fixture(
                PersonalStoreFormat.EMOJI,
                PersonalEmojiTestFixtures.build(
                    entries = listOf(
                        PersonalEmojiTestFixtures.Entry("бәйрәм", "🎉", usage = 2, serial = 3),
                    ),
                    schemaId = PERSONAL_S1_SCHEMA_ID,
                    formatVersion = PERSONAL_F1_FORMAT_VERSION,
                ),
                subtype,
            ),
            Fixture(
                PersonalStoreFormat.REFUSALS,
                RefusedCorrectionsTestFixtures.build(
                    RefusedCorrectionsTestFixtures.record("китәп", "китап", 2),
                    entryCount = 1,
                    schemaId = PERSONAL_S1_SCHEMA_ID,
                    formatVersion = PERSONAL_F1_FORMAT_VERSION,
                ),
                subtype,
            ),
            Fixture(
                PersonalStoreFormat.SENTENCE_STARTS,
                SentStartOverlayTestFixtures.build(
                    entries = listOf(
                        SentStartOverlayTestFixtures.Entry("Сәләм", count = 2, serial = 3),
                    ),
                    schemaId = PERSONAL_S1_SCHEMA_ID,
                    formatVersion = PERSONAL_F1_FORMAT_VERSION,
                ),
                subtype,
            ),
            Fixture(
                PersonalStoreFormat.SHORTCUTS,
                TextShortcutTestFixtures.build(
                    entries = listOf(
                        TextShortcutTestFixtures.Entry("кт", "китап"),
                    ),
                    schemaId = PERSONAL_S1_SCHEMA_ID,
                    formatVersion = PERSONAL_F1_FORMAT_VERSION,
                ),
                null,
            ),
        )
    }

    private fun opaqueStep(
        source: PersonalFormatVersion,
        target: PersonalFormatVersion,
    ) = PersonalFormatMigration(
        kind = PersonalStoreFormat.WORDS,
        source = source,
        target = target,
        sourceReader = PersonalFormatReader { _, _ -> },
        targetReader = PersonalFormatReader { _, _ -> },
        targetFileName = { "unused" },
        migrate = { bytes, _ -> bytes },
    )

    private fun currentFileName(kind: PersonalStoreFormat, subtype: String): String = when (kind) {
        PersonalStoreFormat.WORDS -> TpersFormat.personalFileName(subtype)
        PersonalStoreFormat.BIGRAMS -> TpersbFormat.personalBigramsFileName(subtype)
        PersonalStoreFormat.EMOJI -> TpersemFormat.personalEmojiFileName(subtype)
        PersonalStoreFormat.REFUSALS -> TrefFormat.refusedCorrectionsFileName(subtype)
        PersonalStoreFormat.SENTENCE_STARTS -> TsentFormat.sentStartFileName(subtype)
        PersonalStoreFormat.SHORTCUTS -> TcutFormat.shortcutsFileName()
    }

    private class Fixture(
        val kind: PersonalStoreFormat,
        val bytes: ByteArray,
        val subtypeId: String?,
    )
}
