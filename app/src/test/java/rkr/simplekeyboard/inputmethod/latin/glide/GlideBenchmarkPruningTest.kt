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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parity between target-only bridge diagnostics and the production е/ё pruning paths. */
class GlideBenchmarkPruningTest {
    private val geometry = GlideKeyGeometry.build(
        listOf(
            GlideKeyGeometry.RawKey('е'.code, 0, 0, 10, 10),
            GlideKeyGeometry.RawKey('ё'.code, 100, 0, 110, 10),
            GlideKeyGeometry.RawKey('а'.code, 300, 0, 310, 10),
            GlideKeyGeometry.RawKey('б'.code, 500, 0, 510, 10),
        ),
    )
    private val inventory = ListGlideInventory(
        listOf("ёа" to 100L, "еа" to 10L, "ба" to 1L),
    )
    private val index = GlideWordIndex.build(inventory, geometry)
    private val constants = GlideDecoder.GlideConstants(
        startExtremityNeighbors = 1,
        endExtremityNeighbors = 1,
        // Separate the ёа original length (200) from its е-routed length (300).
        lengthThreshold = 0.5f,
    )

    @Test
    fun directYoTargetUsesItsOriginalEndpointAndLength() {
        val path = path(from = 'ё', to = 'а')
        val diagnostics = diagnostics(path, targetEntry = 0)

        assertFalse(diagnostics.startPruned)
        assertFalse(diagnostics.endPruned)
        assertFalse(diagnostics.lengthPruned)
        assertTrue(decode(path).contains("ёа"))
    }

    @Test
    fun eRoutedYoTargetUsesCounterpartBucketAndFoldedLength() {
        val path = path(from = 'е', to = 'а')
        val diagnostics = diagnostics(path, targetEntry = 0)

        // The original ё→а length is 100 units away from this е→а gesture. Production still
        // returns ёа through its е endpoint bucket and folded ё-on-е length, so neither may be
        // reported as pruned by the bridge.
        assertTrue(index.foldedYoPlainLengthAt(0) >= 0f)
        assertFalse(diagnostics.startPruned)
        assertFalse(diagnostics.endPruned)
        assertFalse(diagnostics.lengthPruned)
        assertTrue(decode(path).contains("ёа"))
    }

    @Test
    fun plainETargetKeepsOnlyThePlainLengthRequirement() {
        val path = path(from = 'е', to = 'а')
        val diagnostics = diagnostics(path, targetEntry = 1)

        assertTrue(index.foldedYoPlainLengthAt(1) < 0f)
        assertFalse(diagnostics.startPruned)
        assertFalse(diagnostics.endPruned)
        assertFalse(diagnostics.lengthPruned)
    }

    @Test
    fun unrelatedTargetStillReportsEndpointAndLengthPruning() {
        val diagnostics = diagnostics(path(from = 'е', to = 'а'), targetEntry = 2)

        assertTrue(diagnostics.startPruned)
        assertFalse(diagnostics.endPruned)
        assertTrue(diagnostics.lengthPruned)
    }

    private fun diagnostics(path: GlidePath, targetEntry: Int) =
        GlideBenchmark.targetPruningDiagnostics(path, targetEntry, index, geometry, constants)

    private fun decode(path: GlidePath): List<String> {
        val decoder = GlideDecoder(geometry, inventory, constants)
        decoder.preloadIndex(index)
        val result = GlideResult()
        val count = decoder.decode(path, result)
        return (0 until count).map { result.words[it]!! }
    }

    private fun path(from: Char, to: Char): GlidePath {
        val fromKey = geometry.keyIndexOfLetter(from.code)
        val toKey = geometry.keyIndexOfLetter(to.code)
        return GlidePath(2).apply {
            addPoint(geometry.centerX(fromKey), geometry.centerY(fromKey), 0f)
            addPoint(geometry.centerX(toKey), geometry.centerY(toKey), 100f)
        }
    }
}
