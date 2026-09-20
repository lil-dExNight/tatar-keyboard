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

import com.sun.management.ThreadMXBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * TT-NEXTWORD-FILL task A (docs/TT-NEXTWORD-FILL.md): [TdictPrefixIndex.topFrequentWords] — the
 * one-scan top-N frequency read that feeds the NEXT_WORD fallback pool. Pins: the frozen ranking
 * (frequency descending, then code-point ascending — with TIES exercised), the exact top-8 lists
 * of both shipped dictionaries, and proof that the lookup path's zero-allocation contract is
 * untouched by the scan.
 */
class TdictPrefixIndexTopFrequentWordsTest {

    // Code-point sorted, as the fixture format requires. Ties at 50 (бала/китап/сузь) and at 7
    // (дә/һә) exercise the code-point tie-break.
    private val entries = listOf(
        "бала" to 50L,
        "дә" to 7L,
        "китап" to 50L,
        "сузь" to 50L,
        "татар" to 500L,
        "эш" to 100L,
        "һә" to 7L,
        "һәм" to 900L,
    )

    @Test
    fun theTopListIsFrequencyDescendingThenCodePointAscending() {
        val index = EngineTestFixtures.index(entries)
        assertEquals(listOf("һәм", "татар", "эш", "бала", "китап", "сузь"), index.topFrequentWords(6))
        // The 50-tie breaks code-point-ascending (бала < китап < сузь), the 7-tie дә < һә.
        assertEquals(listOf("бала", "китап", "сузь", "дә", "һә"), index.topFrequentWords(8).drop(3))
    }

    @Test
    fun theCountCapsAndZeroIsEmpty() {
        val index = EngineTestFixtures.index(entries)
        assertEquals(listOf("һәм"), index.topFrequentWords(1))
        assertEquals(listOf("һәм", "татар"), index.topFrequentWords(2))
        assertEquals(entries.size, index.topFrequentWords(100).size)
        assertTrue(index.topFrequentWords(0).isEmpty())
    }

    @Test
    fun theScanDoesNotChangeAnyLookupResult() {
        val index = EngineTestFixtures.index(entries)
        index.updateKeyNeighbors(E3bTestFixtures.tatarNeighborTable())
        val before = index.lookup(ImmutableUtf8Prefix.copyOf("ба".toByteArray(Charsets.UTF_8)))
        index.topFrequentWords(8)
        val after = index.lookup(ImmutableUtf8Prefix.copyOf("ба".toByteArray(Charsets.UTF_8)))
        assertEquals(before, after)
    }

    @Test
    fun theLookupAllocationContractIsUntouchedByTheScan() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        val threadBean = bean!!
        threadBean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().id

        val index = EngineTestFixtures.index(entries)
        // The one O(N) scan happens OFF the lookup path; afterwards the lookup must stay within
        // the same <= 8 bytes/lookup contract the E3 zero-alloc test pins. The query returns
        // nothing (no "зы*" word in the fixture) so the measurement isolates the machinery from
        // the result-list materialization, exactly like the existing allocation tests.
        index.topFrequentWords(8)
        val query = ImmutableUtf8Prefix.copyOf("зы".toByteArray(Charsets.UTF_8))
        repeat(50_000) { index.lookup(query) }
        val before = threadBean.getThreadAllocatedBytes(threadId)
        repeat(200_000) { index.lookup(query) }
        val after = threadBean.getThreadAllocatedBytes(threadId)
        val perLookup = (after - before) / 200_000
        assertTrue("perLookup=$perLookup bytes/lookup", abs(perLookup) <= 8L)
    }

    @Test
    fun theRealTatarAssetTop8IsPinned() {
        assertEquals(
            listOf("һәм", "белән", "да", "бу", "дә", "дип", "ул", "өчен"),
            requireNotNull(tatarIndex).topFrequentWords(8),
        )
    }

    @Test
    fun theRealRussianAssetTop8IsPinned() {
        assertEquals(
            listOf("я", "не", "в", "и", "что", "ты", "на", "это"),
            requireNotNull(russianIndex).topFrequentWords(8),
        )
    }

    companion object {
        private var tatarIndex: TdictPrefixIndex? = null
        private var russianIndex: TdictPrefixIndex? = null

        @JvmStatic
        @BeforeClass
        fun loadCommittedDictionaries() {
            tatarIndex = open(DictionaryArtifactSpec.TATAR_TOP100K_V1)
            russianIndex = open(DictionaryArtifactSpec.RUSSIAN_TOP100K_V1)
        }

        private fun open(spec: DictionaryArtifactSpec): TdictPrefixIndex {
            val asset = locate(
                "src/main/assets/${spec.assetPath}",
                "app/src/main/assets/${spec.assetPath}",
            )
            val rawFile = File.createTempFile("tt-fill-top-", ".tdict")
            try {
                rawFile.outputStream().use { output ->
                    TdictValidator().inflateAsset(asset.inputStream(), output, spec)
                }
                TdictValidator().validateRaw(rawFile, spec)
                val raw = rawFile.readBytes()
                val identity = DictionaryIdentity(
                    spec.generation, spec.schemaId, spec.formatVersion,
                    java.security.MessageDigest.getInstance("SHA-256").digest(raw)
                        .joinToString("") { "%02x".format(it) },
                )
                return requireNotNull(
                    TdictPrefixIndex.open(
                        ByteBuffer.wrap(raw), identity, spec.expectedEntryCount, raw.size.toLong(),
                    ),
                )
            } finally {
                rawFile.delete()
            }
        }

        private fun locate(vararg paths: String): File =
            paths.map(::File).firstOrNull(File::isFile)
                ?: error("cannot locate committed dictionary test resource")
    }
}
