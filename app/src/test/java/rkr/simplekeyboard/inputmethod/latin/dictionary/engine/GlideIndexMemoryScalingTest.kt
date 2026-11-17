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

package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import rkr.simplekeyboard.inputmethod.latin.glide.GlideTestFixtures
import rkr.simplekeyboard.inputmethod.latin.glide.GlideWordIndex
import rkr.simplekeyboard.inputmethod.latin.glide.GlideWordInventory
import rkr.simplekeyboard.inputmethod.latin.glide.GlideWordUtf8Visitor
import rkr.simplekeyboard.inputmethod.latin.glide.GlideWordVisitor
import java.io.File
import java.nio.ByteBuffer

class GlideIndexMemoryScalingTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun shippedIndexMemoryScalesLinearlyWithinThePerEntryBudget() {
        val spec = DictionaryArtifactSpec.TATAR_TOP100K_V1
        val file = temporaryFolder.newFile("memory-scaling.tdict")
        listOf(
            File("src/main/assets", spec.assetPath),
            File("app/src/main/assets", spec.assetPath),
        ).firstOrNull(File::isFile)?.inputStream()?.use { input ->
            file.outputStream().use { TdictValidator().inflateAsset(input, it, spec) }
        } ?: error("cannot locate ${spec.assetPath}")
        val validated = TdictValidator().validateRaw(file, spec)
        val identity = DictionaryIdentity(
            spec.generation, validated.schemaId, validated.formatVersion, validated.rawSha256,
        )
        val dictionary = requireNotNull(
            TdictPrefixIndex.open(
                ByteBuffer.wrap(file.readBytes()), identity,
                spec.expectedEntryCount, spec.expectedRawSize,
                null, FuzzyEditPolicy.TATAR, AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_TT,
            )
        )
        val inventory = TdictGlideInventory(dictionary)
        val geometry = GlideTestFixtures.tatarGeometry()

        val full = GlideWordIndex.build(inventory, geometry)
        val half = GlideWordIndex.build(LimitedInventory(inventory, dictionary.entryCount / 2), geometry)

        // A hyphenated compound has no key for U+002D, so the glide index skips it.
        assertEquals(dictionary.entryCount, full.wordCount + full.skippedWordCount)
        assertEquals(dictionary.entryCount / 2, half.wordCount + half.skippedWordCount)
        // The pair-offset table is fixed by the layout; all other retained arrays scale by entry.
        val fixedBytes = geometry.keyCount * geometry.keyCount * 4L
        val fullVariable = full.retainedByteEstimate - fixedBytes
        val halfVariable = half.retainedByteEstimate - fixedBytes
        val tolerance = dictionary.entryCount.toLong()
        assertTrue(
            "full=$fullVariable half=$halfVariable is not linear within one byte per entry",
            kotlin.math.abs(fullVariable - halfVariable * 2) <= tolerance,
        )
        assertTrue(
            "full index uses $fullVariable variable bytes for ${dictionary.entryCount} entries",
            fullVariable <= dictionary.entryCount * MAX_VARIABLE_BYTES_PER_ENTRY,
        )
    }

    private class LimitedInventory(
        private val delegate: TdictGlideInventory,
        override val entryCount: Int,
    ) : GlideWordInventory {
        override fun wordAt(index: Int): String = delegate.wordAt(index)

        override fun forEachWord(visitor: GlideWordVisitor) {
            var remaining = entryCount
            delegate.forEachWord { word, frequency ->
                if (remaining > 0) visitor.visit(word, frequency)
                remaining--
            }
        }

        override fun forEachWordUtf8(visitor: GlideWordUtf8Visitor) {
            var remaining = entryCount
            delegate.forEachWordUtf8 { bytes, length, frequency ->
                if (remaining > 0) visitor.visit(bytes, length, frequency)
                remaining--
            }
        }
    }

    private companion object {
        const val MAX_VARIABLE_BYTES_PER_ENTRY = 48L
    }
}
