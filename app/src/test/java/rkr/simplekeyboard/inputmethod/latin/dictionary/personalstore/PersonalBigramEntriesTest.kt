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
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersbFormat

/**
 * The pure model of the personal-bigram store (P1 of Phase 2, docs/ROADMAP-P2.md): insertion,
 * reinforcement, acceptance, removal, the LRU eviction and the pinned lookup order — all without
 * a file, exactly like [PersonalEntriesTest] for the words store.
 */
class PersonalBigramEntriesTest {

    private fun entries(maxPairs: Int = 1000): PersonalBigramEntries =
        PersonalBigramEntries.empty(maxPairs)

    @Test
    fun upsertInsertsInPairKeyOrderAndStartsTheCounters() {
        val model = entries()
            .upsert("сәләм", "дөнья", "дөнья", 2)
            .upsert("бәйрәм", "котлы", "котлы", 2)
            .upsert("сәләм", "абый", "абый", 2)

        // Context first, successor on a tie: (бәйрәм, котлы) < (сәләм, абый) < (сәләм, дөнья).
        assertEquals(3, model.size)
        assertEquals("бәйрәм", model.contextAt(0))
        assertEquals("сәләм", model.contextAt(1))
        assertEquals("абый", model.successorNormalizedFormAt(1))
        assertEquals("сәләм", model.contextAt(2))
        assertEquals("дөнья", model.successorNormalizedFormAt(2))
        assertEquals(0, model.usageCountAt(1))
        assertEquals(2, model.frequencyCountAt(1))
    }

    @Test
    fun upsertOfAnExistingPairReinforcesFrequencyButKeepsTheStoredCasing() {
        val model = entries()
            .upsert("мин", "Гүзәл", "гүзәл", 2)
            .upsert("мин", "гүзәл", "гүзәл", 2)

        assertEquals(1, model.size)
        assertEquals("Гүзәл", model.successorRawFormAt(0)) // the casing first saved wins
        assertEquals(4, model.frequencyCountAt(0))
        assertEquals(0, model.usageCountAt(0))
    }

    @Test
    fun noteObservationBumpsFrequencyAndNeverCreatesAPhantom() {
        val model = entries().upsert("сәләм", "дөнья", "дөнья", 2)
        assertNull(model.noteObservation("сәләм", "китап")) // absent pair -> no phantom
        val touched = requireNotNull(model.noteObservation("сәләм", "дөнья"))
        assertEquals(3, touched.frequencyCountAt(0))
        assertEquals(0, touched.usageCountAt(0))
    }

    @Test
    fun noteUseBumpsUsageAndNeverCreatesAPhantom() {
        val model = entries().upsert("сәләм", "дөнья", "дөнья", 2)
        assertNull(model.noteUse("сәләм", "китап"))
        val touched = requireNotNull(model.noteUse("сәләм", "дөнья"))
        assertEquals(1, touched.usageCountAt(0))
        assertEquals(2, touched.frequencyCountAt(0))
    }

    @Test
    fun removeDropsThePairAndIsANoOpWhenAbsent() {
        val model = entries()
            .upsert("сәләм", "дөнья", "дөнья", 2)
            .upsert("бәйрәм", "котлы", "котлы", 2)
        val reduced = model.remove("сәләм", "дөнья")
        assertEquals(1, reduced.size)
        assertFalse(reduced.containsPair("сәләм", "дөнья"))
        assertSame(model, model.remove("сәләм", "китап")) // absent -> unchanged instance
    }

    @Test
    fun countersSaturateAtTheU16Cap() {
        var model = entries().upsert("сәләм", "дөнья", "дөнья", 2)
        val cap = TpersbFormat.MAX_U16.toInt()
        repeat(cap + 10) {
            model = requireNotNull(model.noteObservation("сәләм", "дөнья"))
            model = requireNotNull(model.noteUse("сәләм", "дөнья"))
        }
        assertEquals(cap, model.frequencyCountAt(0))
        assertEquals(cap, model.usageCountAt(0))
    }

    @Test
    fun overflowEvictsTheLeastRecentlyUsed() {
        var model = entries(maxPairs = 3)
        model = model.upsert("а", "б", "б", 2) // serial 1
        model = model.upsert("б", "в", "в", 2) // serial 2
        model = model.upsert("г", "д", "д", 2) // serial 3
        // Touch the oldest pair so the SECOND oldest becomes the victim instead.
        model = requireNotNull(model.noteUse("а", "б")) // (а, б) now has serial 4
        model = model.upsert("е", "ж", "ж", 2) // evicts (б, в)

        assertEquals(3, model.size)
        assertTrue(model.containsPair("а", "б"))
        assertFalse(model.containsPair("б", "в"))
        assertTrue(model.containsPair("г", "д"))
        assertTrue(model.containsPair("е", "ж"))
    }

    @Test
    fun thePairKeyBoundaryIsPartOfTheKey() {
        // («аб», «вг») and («абв», «г») concatenate to the same bytes; they are different pairs.
        val model = entries()
            .upsert("аб", "вг", "вг", 2)
            .upsert("абв", "г", "г", 2)
        assertEquals(2, model.size)
        assertTrue(model.containsPair("аб", "вг"))
        assertTrue(model.containsPair("абв", "г"))
        // And the order is context first: «аб» < «абв» however the successors compare.
        assertEquals("аб", model.contextAt(0))
        assertEquals("абв", model.contextAt(1))
    }

    @Test
    fun snapshotOrdersSuccessorsByUsageThenFrequencyThenNormalizedForm() {
        var model = entries()
            .upsert("сәләм", "б", "б", 2) // frequency 2
            .upsert("сәләм", "а", "а", 5) // frequency 5
            .upsert("сәләм", "в", "в", 5) // frequency 5, normalized later than «а»
        model = requireNotNull(model.noteUse("сәләм", "б")) // usage 1 beats every frequency

        val successors = model.toSnapshot("tt_RU").successorsFor("сәләм")
        assertEquals(listOf("б", "а", "в"), successors.map { it.normalizedForm })
    }

    @Test
    fun snapshotRoundTripsThroughTheOnDiskForm() {
        val model = entries()
            .upsert("сәләм", "дөнья", "дөнья", 2)
            .upsert("мин", "Гүзәл", "гүзәл", 3)
        val snapshot = model.toSnapshot("tt_RU")
        assertEquals(2, snapshot.size)
        // Pair-key order: (мин, гүзәл) before (сәләм, дөнья) — «м» sorts before «с».
        assertEquals("мин", snapshot.contextAt(0))
        assertEquals("Гүзәл", snapshot.successorRawFormAt(0))
        assertEquals("гүзәл", snapshot.successorNormalizedFormAt(0))
        assertEquals(3, snapshot.frequencyCountAt(0))
        assertEquals("сәләм", snapshot.contextAt(1))
        assertEquals("дөнья", snapshot.successorRawFormAt(1))
        assertEquals(2, snapshot.frequencyCountAt(1))
        assertTrue(snapshot.indexOfPair("мин", "гүзәл") >= 0)
        assertTrue(snapshot.indexOfPair("мин", "Гүзәл") < 0) // membership is by the normalized form
    }

    @Test
    fun estimatedFileSizeTracksTheSerializedForm() {
        val model = entries()
            .upsert("сәләм", "дөнья", "дөнья", 2)
            .upsert("бәйрәм", "котлы", "котлы", 2)
        assertEquals(model.serialize("tt_RU").size.toLong(), model.estimatedFileSize().toLong())
        assertTrue(model.estimatedFileSize().toLong() <= TpersbFormat.MAX_FILE_SIZE)
    }
}
