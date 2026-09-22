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

package rkr.simplekeyboard.inputmethod.latin.suggestions

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.DictionaryIdentity
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.TdictPrefixIndex
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator

/**
 * Contract of the shipped Russian sentence-start asset
 * (`assets/dictionaries/russian_sentstart_v1.txt`, ROADMAP Phase 1 P3b, `docs/ROADMAP-P1.md`):
 * the frequency-ranked table of Russian sentence-initial words built by
 * `scripts/sentstart_pack.py --language rus`, mirroring `TatarSentStartAssetTest` pin for pin:
 * a `#` header carrying the Leipzig attribution, `word<TAB>freq` rows sorted by (frequency
 * desc, word asc), every word in the Russian alphabet and in the exact normalized form the
 * dictionary is keyed by, and — the packer's strict filter — every word present in the
 * shipped Russian dictionary. The record count and the SHA-256 are pinned exactly: a data
 * change is a written decision that re-pins them.
 */
class RussianSentStartAssetTest {

    private fun sourceRoot(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull { it.isDirectory }
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")

    private fun assetFile(): File =
        File(sourceRoot(), "assets/dictionaries/russian_sentstart_v1.txt")

    /** The rows of the shipped table, in file order (= ranking order). */
    private fun readRows(): List<Pair<String, Int>> {
        val text = assetFile().readText()
        assertTrue(text.endsWith("\n"))
        assertFalse(text.contains('\r'))
        val rows = mutableListOf<Pair<String, Int>>()
        var sawHeader = false
        for (line in text.split('\n')) {
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                sawHeader = true
                continue
            }
            val fields = line.split('\t')
            assertEquals("bad field count in: $line", 2, fields.size)
            assertTrue("empty field in: $line", fields.all { it.isNotEmpty() })
            val frequency = fields[1].toIntOrNull()
            assertTrue("frequency is not a number in: $line", frequency != null)
            assertTrue("frequency is not positive in: $line", frequency!! > 0)
            rows.add(fields[0] to frequency)
        }
        assertTrue("the attribution header is missing", sawHeader)
        return rows
    }

    @Test
    fun theShippedTableIsPinned() {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(assetFile().readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals(EXPECTED_ASSET_SHA256, digest)
        assertEquals(EXPECTED_RECORD_COUNT, readRows().size)
    }

    @Test
    fun theHeaderCarriesTheLeipzigAttribution() {
        val header = assetFile().readText().split('\n')
            .filter { it.startsWith("#") }
            .joinToString("\n")
        assertTrue(header.contains("Leipzig"))
        assertTrue(header.contains("CC BY 4.0"))
        assertTrue(header.contains("rus_news_2022_1M"))
        assertTrue(header.contains("rus_news_2019_1M"))
        assertTrue(header.contains("rus_wikipedia_2021_1M"))
    }

    @Test
    fun theRowsAreSortedByFrequencyDescThenWordAsc() {
        val rows = readRows()
        val keys = rows.map { (word, frequency) -> Pair(-frequency, word) }
        assertEquals(keys.sortedWith(compareBy({ it.first }, { it.second })), keys)
        assertEquals("no duplicate words", rows.size, rows.map { it.first }.distinct().size)
    }

    @Test
    fun everyWordIsRussianAlphabetAndInTheNormalizedLookupForm() {
        val alphabet = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя".toSet()
        for ((word, _) in readRows()) {
            assertTrue("outside the Russian alphabet: $word", word.all { it in alphabet })
            assertEquals("not the normalized lookup form: $word",
                TatarWordUtils.normalizeForLookup(word), word)
        }
    }

    /** The packer's strict filter: the table can never offer a word the keyboard does not know. */
    @Test
    fun everyWordIsInTheShippedRussianDictionary() {
        val spec = DictionaryArtifactSpec.RUSSIAN_TOP100K_V1
        val dictAsset = listOf(
            File("src/main/assets/dictionaries/russian_top100k_v1.tdict.zlib"),
            File("app/src/main/assets/dictionaries/russian_top100k_v1.tdict.zlib"),
        ).firstOrNull { it.isFile } ?: error("cannot locate the shipped Russian dictionary")
        val rawFile = File.createTempFile("ru-sentstart-asset-dict-", ".tdict")
        val index: TdictPrefixIndex
        try {
            rawFile.outputStream().use { output ->
                TdictValidator().inflateAsset(dictAsset.inputStream(), output, spec)
            }
            val validated = TdictValidator().validateRaw(rawFile, spec)
            val identity = DictionaryIdentity(
                spec.generation, validated.schemaId, validated.formatVersion, validated.rawSha256,
            )
            index = requireNotNull(
                TdictPrefixIndex.open(
                    java.nio.ByteBuffer.wrap(rawFile.readBytes()), identity,
                    validated.entryCount, validated.rawSize,
                ),
            )
        } finally {
            rawFile.delete()
        }
        for ((word, _) in readRows()) {
            assertTrue("not a shipped dictionary word: $word", index.frequencyOf(word) > 0L)
        }
    }

    /** The runtime reader parses the committed bytes to exactly the ranked word list. */
    @Test
    fun theRuntimeReaderSeesTheSameTable() {
        val rows = readRows()
        val index = SentStartIndex.parse(assetFile().readText())
        assertFalse(index.isEmpty)
        assertEquals(rows.size, index.entryCount)
        assertEquals(rows.map { it.first }, index.topWords(rows.size))
        assertEquals(rows.take(3).map { it.first }, index.topWords(3))
    }

    @Test
    fun theShippedTableIsCreditedInTheNoticeBesideIt() {
        val notice = File(sourceRoot(), "assets/dictionaries/NOTICE.txt").readText()
        assertTrue(notice.contains("russian_sentstart_v1.txt"))
    }

    private companion object {
        // Measured 2026-09-22 on the committed inputs; re-pin consciously when the table changes
        // (rebuild recipe in docs/ROADMAP-P1.md, P3b section).
        const val EXPECTED_RECORD_COUNT = 64
        const val EXPECTED_ASSET_SHA256 =
            "ffab114daf924d8f23f295d833a0d90df7298b16fb139b3ba1bdb87a7783f86d"
    }
}
