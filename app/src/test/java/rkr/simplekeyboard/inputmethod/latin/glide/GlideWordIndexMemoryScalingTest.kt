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

package rkr.simplekeyboard.inputmethod.latin.glide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The explicit memory-scaling gate for the glide word index: enlarging the Tatar model must not
 * silently multiply the index's heap. Two properties are pinned without any asset dependency:
 *
 *  - LINEAR GROWTH: every retained structure except the layout-fixed pair-offset table grows by
 *    the entry. Halving the entry count must halve the variable heap (within one byte per entry),
 *    so an accidental per-entry object or quadratic table fails here;
 *  - PER-ENTRY BUDGET: the variable heap of the CURRENT 110 000-entry model stays under
 *    [MAX_VARIABLE_BYTES_PER_ENTRY]. Raising the budget is a deliberate, reviewable act — exactly
 *    the check P0§6 asks for before the Tatar model grows.
 *
 * The real shipped dictionary is covered separately by the engine-level scaling test; this one
 * keeps the invariant testable on any host and independent of asset pins.
 */
class GlideWordIndexMemoryScalingTest {

    private val geometry = GlideTestFixtures.tatarGeometry()

    /** The pair-offset table is sized by the layout, not by the dictionary. */
    private val fixedBytes: Long = geometry.keyCount * geometry.keyCount * 4L

    // The Tatar row's 39 letters (the eight special letters on the extra row, then ЙЦУКЕН).
    private val alphabet =
        "әөүҗңһёъйцукенгшщзхфывапролджэячсмитьбю"

    /**
     * A deterministic reference-shape inventory: unique words of 5..11 letters over the layout's
     * alphabet, in dictionary order with descending frequencies.
     */
    private fun referenceEntries(count: Int): List<Pair<String, Long>> {
        val entries = ArrayList<Pair<String, Long>>(count)
        val base = alphabet.length
        for (index in 0 until count) {
            val length = 5 + index % 7
            val builder = StringBuilder(length)
            var value = index
            repeat(length) {
                builder.append(alphabet[value % base])
                value /= base
            }
            entries.add(builder.toString() to (4_000_000L / (index + 1) + 1))
        }
        return entries
    }

    private fun build(count: Int): GlideWordIndex =
        GlideWordIndex.build(ListGlideInventory(referenceEntries(count)), geometry)

    @Test
    fun variableHeapHalvesWithTheEntryCount() {
        val full = build(40_000)
        val half = build(20_000)
        assertEquals(40_000, full.wordCount)
        assertEquals(20_000, half.wordCount)
        val fullVariable = full.retainedByteEstimate - fixedBytes
        val halfVariable = half.retainedByteEstimate - fixedBytes
        assertTrue(
            "full=$fullVariable half=$halfVariable is not linear within one byte per entry",
            abs(fullVariable - halfVariable * 2) <= 40_000L,
        )
        // Doubling again must not introduce a superlinear structure either.
        val quarter = build(10_000)
        val quarterVariable = quarter.retainedByteEstimate - fixedBytes
        assertTrue(
            "half=$halfVariable quarter=$quarterVariable is not linear within one byte per entry",
            abs(halfVariable - quarterVariable * 2) <= 20_000L,
        )
    }

    @Test
    fun theCurrentModelStaysWithinThePerEntryBudget() {
        // 110 000 entries is the shipped Tatar dictionary size; the budget is the explicit gate
        // before any enlargement of the model.
        val currentModelEntries = 110_000
        val index = build(currentModelEntries)
        assertEquals(currentModelEntries, index.wordCount)
        val variable = index.retainedByteEstimate - fixedBytes
        assertTrue(
            "variable heap $variable bytes for $currentModelEntries entries exceeds " +
                "$MAX_VARIABLE_BYTES_PER_ENTRY bytes/entry",
            variable <= currentModelEntries * MAX_VARIABLE_BYTES_PER_ENTRY,
        )
    }

    @Test
    fun theRetainedEstimateCoversEveryFlatArray() {
        // A guard on the estimate itself: an array added to the index without being counted would
        // make the budget above blind. Every array's size is folded into the estimate by
        // GlideWordIndex.retainedByteEstimate; this pins the relation at the array granularity.
        val entryCount = 1_000
        val index = build(entryCount)
        val poolSize = index.keySeqStart(entryCount).toLong() // keySeqStarts[entryCount]
        val expected = (entryCount + 1) * 4L + // keySeqStarts: entryCount + 1 ints
            poolSize + // keySeqPool: one byte per key position
            (poolSize + 63) / 64 * 8L + // sameLetterAsPrevious bits
            entryCount * 4L + // frequencies
            entryCount * 4L * 3L + // plain/loop/normative durations
            (geometry.keyCount * geometry.keyCount + 1) * 4L + // pairOffsets
            entryCount * 4L + // pairEntries
            (entryCount + 63) / 64 * 8L // twinless bits
        assertEquals(expected, index.retainedByteEstimate)
    }

    private companion object {
        /** Pinned variable bytes per entry for the current model shape (measured ≈33). */
        const val MAX_VARIABLE_BYTES_PER_ENTRY = 48L
    }
}
