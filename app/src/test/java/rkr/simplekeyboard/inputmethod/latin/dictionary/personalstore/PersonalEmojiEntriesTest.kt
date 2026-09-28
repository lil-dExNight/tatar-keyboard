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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersemFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersemValidator

/**
 * The pure model of the personal-emoji store: insertion, reinforcement, acceptance, removal, the
 * LRU eviction and the pinned lookup order — all without a file, exactly like
 * [PersonalBigramEntriesTest] for the pairs store.
 */
class PersonalEmojiEntriesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun entries(maxEntries: Int = 500): PersonalEmojiEntries =
        PersonalEmojiEntries.empty(maxEntries)

    @Test
    fun upsertInsertsInEntryKeyOrderAndStartsTheCounters() {
        val model = entries()
            .upsert("сәләм", "🌙", 2)
            .upsert("бәйрәм", "🎉", 2)
            .upsert("сәләм", "☀️", 2)

        // Word first, emoji on a tie: (бәйрәм, 🎉) < (сәләм, ☀️) < (сәләм, 🌙) — ☀️ sorts before
        // 🌙 by unsigned UTF-8 bytes (0xE2… < 0xF0…).
        assertEquals(3, model.size)
        assertEquals("бәйрәм", model.wordAt(0))
        assertEquals("сәләм", model.wordAt(1))
        assertEquals("☀️", model.emojiAt(1))
        assertEquals("сәләм", model.wordAt(2))
        assertEquals("🌙", model.emojiAt(2))
        assertEquals(0, model.usageCountAt(1))
        assertEquals(2, model.frequencyCountAt(1))
    }

    @Test
    fun upsertOfAnExistingEntryReinforcesFrequency() {
        val model = entries()
            .upsert("сәләм", "☀️", 2)
            .upsert("сәләм", "☀️", 2)

        assertEquals(1, model.size)
        assertEquals(4, model.frequencyCountAt(0))
        assertEquals(0, model.usageCountAt(0))
    }

    @Test
    fun noteObservationBumpsFrequencyAndNeverCreatesAPhantom() {
        val model = entries().upsert("сәләм", "☀️", 2)
        assertNull(model.noteObservation("сәләм", "🌙")) // absent entry -> no phantom
        assertNull(model.noteObservation("дөнья", "☀️"))
        val touched = requireNotNull(model.noteObservation("сәләм", "☀️"))
        assertEquals(3, touched.frequencyCountAt(0))
        assertEquals(0, touched.usageCountAt(0))
    }

    @Test
    fun noteUseBumpsUsageAndNeverCreatesAPhantom() {
        val model = entries().upsert("сәләм", "☀️", 2)
        assertNull(model.noteUse("сәләм", "🌙"))
        val touched = requireNotNull(model.noteUse("сәләм", "☀️"))
        assertEquals(1, touched.usageCountAt(0))
        assertEquals(2, touched.frequencyCountAt(0))
    }

    @Test
    fun removeDropsTheEntryAndIsANoOpWhenAbsent() {
        val model = entries()
            .upsert("сәләм", "☀️", 2)
            .upsert("бәйрәм", "🎉", 2)
        val reduced = model.remove("сәләм", "☀️")
        assertEquals(1, reduced.size)
        assertFalse(reduced.containsEntry("сәләм", "☀️"))
        assertSame(model, model.remove("сәләм", "🌙")) // absent -> unchanged instance
    }

    @Test
    fun countersSaturateAtTheU16Cap() {
        var model = entries().upsert("сәләм", "☀️", 2)
        val cap = TpersemFormat.MAX_U16.toInt()
        repeat(cap + 10) {
            model = requireNotNull(model.noteObservation("сәләм", "☀️"))
            model = requireNotNull(model.noteUse("сәләм", "☀️"))
        }
        assertEquals(cap, model.frequencyCountAt(0))
        assertEquals(cap, model.usageCountAt(0))
    }

    @Test
    fun overflowEvictsTheLeastRecentlyUsed() {
        var model = entries(maxEntries = 3)
        model = model.upsert("ал", "☀️", 2) // serial 1
        model = model.upsert("сүз", "🌙", 2) // serial 2
        model = model.upsert("кит", "🎉", 2) // serial 3
        // Touch the oldest entry so the SECOND oldest becomes the victim instead.
        model = requireNotNull(model.noteUse("ал", "☀️")) // (ал, ☀️) now has serial 4
        model = model.upsert("күл", "❤️", 2) // evicts (сүз, 🌙)

        assertEquals(3, model.size)
        assertTrue(model.containsEntry("ал", "☀️"))
        assertFalse(model.containsEntry("сүз", "🌙"))
        assertTrue(model.containsEntry("кит", "🎉"))
        assertTrue(model.containsEntry("күл", "❤️"))
    }

    @Test
    fun theKeyBoundaryIsPartOfTheKey() {
        // («а», «бв») and («аб», «в») concatenate to the same bytes; they are different entries.
        // (Plain letters stand in for the emoji half: the model is content-agnostic — the shape
        // checks are the validator's.)
        val model = entries()
            .upsert("а", "бв", 2)
            .upsert("аб", "в", 2)
        assertEquals(2, model.size)
        assertTrue(model.containsEntry("а", "бв"))
        assertTrue(model.containsEntry("аб", "в"))
        // And the order is word first: «а» < «аб» however the emoji halves compare.
        assertEquals("а", model.wordAt(0))
        assertEquals("аб", model.wordAt(1))
    }

    @Test
    fun snapshotRanksEmojiOfOneWordByUsageThenFrequencyThenBytes() {
        var model = entries()
            .upsert("сәләм", "🎉", 2) // frequency 2
            .upsert("сәләм", "☀️", 5) // frequency 5
            .upsert("сәләм", "🌙", 5) // frequency 5, bytes later than ☀️

        // Without a tap: frequency decides, and the byte order breaks the tie (☀️ < 🌙).
        var snapshot = model.toSnapshot("tt_RU")
        assertEquals(listOf("☀️", "🌙", "🎉"), snapshot.emojisFor("сәләм"))
        assertEquals("☀️", snapshot.emojiFor("сәләм"))

        model = requireNotNull(model.noteUse("сәләм", "🎉")) // usage 1 beats every frequency
        snapshot = model.toSnapshot("tt_RU")
        assertEquals(listOf("🎉", "☀️", "🌙"), snapshot.emojisFor("сәләм"))
        assertEquals("🎉", snapshot.emojiFor("сәләм"))

        assertNull(snapshot.emojiFor("дөнья")) // a word with no entries
        assertEquals(emptyList<String>(), snapshot.emojisFor("дөнья"))
    }

    @Test
    fun snapshotRoundTripsThroughTheOnDiskForm() {
        val model = entries()
            .upsert("сәләм", "🌙", 2)
            .upsert("мин", "☀️", 3)
        val snapshot = model.toSnapshot("tt_RU")
        assertEquals(2, snapshot.size)
        // Entry-key order: (мин, ☀️) before (сәләм, 🌙) — «м» sorts before «с».
        assertEquals("мин", snapshot.wordAt(0))
        assertEquals("☀️", snapshot.emojiAt(0))
        assertEquals(3, snapshot.frequencyCountAt(0))
        assertEquals("сәләм", snapshot.wordAt(1))
        assertEquals("🌙", snapshot.emojiAt(1))
        assertEquals(2, snapshot.frequencyCountAt(1))
        assertTrue(snapshot.indexOfEntry("мин", "☀️") >= 0)
        assertTrue(snapshot.indexOfEntry("мин", "🌙") < 0) // membership is the exact (word, emoji) key
        assertTrue(snapshot.indexOfEntry("сәләм", "🌙") >= 0)
    }

    @Test
    fun serializeRoundTripsThroughTheValidator() {
        val model = entries()
            .upsert("сәләм", "🌙", 2)
            .upsert("мин", "☀️", 3)
            .upsert("сәләм", "☀️", 4)
        val file = temporaryFolder.newFile("entries-${System.nanoTime()}.tpersem")
        file.writeBytes(model.serialize("tt_RU"))

        val validated = TpersemValidator().validate(file, "tt_RU")
        assertEquals(3, validated.entryCount)
        assertEquals(listOf("мин", "сәләм", "сәләм"), validated.words)
        assertEquals(listOf("☀️", "☀️", "🌙"), validated.emojiClusters)
        assertEquals(listOf(3, 4, 2), validated.frequencyCounts.toList())
    }

    @Test
    fun estimatedFileSizeTracksTheSerializedForm() {
        val model = entries()
            .upsert("сәләм", "☀️", 2)
            .upsert("бәйрәм", "🎉", 2)
        assertEquals(model.serialize("tt_RU").size.toLong(), model.estimatedFileSize().toLong())
        assertTrue(model.estimatedFileSize().toLong() <= TpersemFormat.MAX_FILE_SIZE)
    }
}
