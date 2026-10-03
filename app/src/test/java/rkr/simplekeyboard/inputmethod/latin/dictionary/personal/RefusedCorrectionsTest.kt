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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The refused-corrections snapshot: the threshold crossing, the exact-pair scoping, the refusal
 * order with its eviction, and the serialize round trip through the strict validator. Plain JVM.
 */
class RefusedCorrectionsTest {

    @Test
    fun aPairIsRefusedOnlyAtTheThreshold() {
        val once = RefusedCorrections.EMPTY.noting("китәп", "китап", MAX)
        assertFalse("one undo can be an accident", once.isRefused("китәп", "китап"))
        assertEquals(1, once.refusalCountAt(0))

        val twice = once.noting("китәп", "китап", MAX)
        assertTrue(twice.isRefused("китәп", "китап"))
        assertEquals(1, twice.size)
        assertEquals(RefusedCorrections.REFUSAL_THRESHOLD, twice.refusalCountAt(0))
    }

    @Test
    fun theRefusalIsScopedToTheExactPair() {
        val pairs = RefusedCorrections.EMPTY
            .noting("китәп", "китап", MAX)
            .noting("китәп", "китап", MAX)
        assertTrue(pairs.isRefused("китәп", "китап"))
        assertFalse("another replacement for the same word still fires",
            pairs.isRefused("китәп", "китеб"))
        assertFalse("the reverse direction is a different pair",
            pairs.isRefused("китап", "китәп"))
        assertFalse("the same replacement for another word still fires",
            pairs.isRefused("дәфтар", "китап"))
        assertFalse("casing is folded away by the normalization before the lookup",
            pairs.isRefused("Китәп", "китап"))
    }

    @Test
    fun notingAnAlreadySuppressedPairChangesNothing() {
        val twice = RefusedCorrections.EMPTY
            .noting("китәп", "китап", MAX)
            .noting("китәп", "китап", MAX)
        assertSame(twice, twice.noting("китәп", "китап", MAX))
    }

    @Test
    fun aRepeatedRefusalMovesThePairToTheBack() {
        val pairs = RefusedCorrections.EMPTY
            .noting("бала", "бәлә", MAX)
            .noting("китәп", "китап", MAX)
            .noting("бала", "бәлә", MAX)
        assertEquals(listOf("китәп", "бала"), (0 until pairs.size).map(pairs::typedWordAt))
        assertEquals(listOf(1, 2), (0 until pairs.size).map(pairs::refusalCountAt))
    }

    @Test
    fun pastTheCapTheOldestRefusalIsEvictedFirst() {
        var pairs = RefusedCorrections.EMPTY
        pairs = pairs.noting("бала", "бәлә", 2)
        pairs = pairs.noting("китәп", "китап", 2)
        pairs = pairs.noting("бала", "бәлә", 2) // refreshed: now the youngest
        pairs = pairs.noting("дөнья", "донъя", 2) // evicts "китәп", the oldest untouched refusal

        assertEquals(2, pairs.size)
        assertEquals(listOf("бала", "дөнья"), (0 until pairs.size).map(pairs::typedWordAt))
    }

    @Test
    fun theRoundTripThroughTheValidatorKeepsTheOrderAndTheCounts() {
        val pairs = RefusedCorrections.EMPTY
            .noting("бала", "бәлә", MAX)
            .noting("китәп", "китап", MAX)
            .noting("китәп", "китап", MAX)

        val validated = TrefValidator().validate(pairs.serialize(PersonalSubtypes.TATAR_RU),
            PersonalSubtypes.TATAR_RU)
        val restored = RefusedCorrections.of(validated)

        assertEquals(pairs.size, restored.size)
        for (index in 0 until pairs.size) {
            assertEquals(pairs.typedWordAt(index), restored.typedWordAt(index))
            assertEquals(pairs.replacementAt(index), restored.replacementAt(index))
            assertEquals(pairs.refusalCountAt(index), restored.refusalCountAt(index))
        }
        assertTrue(restored.isRefused("китәп", "китап"))
        assertFalse(restored.isRefused("бала", "бәлә"))
    }

    @Test
    fun theEmptySnapshotRefusesNothingAndSerializesToABareHeader() {
        assertFalse(RefusedCorrections.EMPTY.isRefused("китәп", "китап"))
        val bytes = RefusedCorrections.EMPTY.serialize(PersonalSubtypes.RUSSIAN)
        assertEquals(TrefFormat.HEADER_SIZE, bytes.size)
        val validated = TrefValidator().validate(bytes, PersonalSubtypes.RUSSIAN)
        assertEquals(0, validated.entryCount)
    }

    private companion object {
        const val MAX = 500
    }
}
