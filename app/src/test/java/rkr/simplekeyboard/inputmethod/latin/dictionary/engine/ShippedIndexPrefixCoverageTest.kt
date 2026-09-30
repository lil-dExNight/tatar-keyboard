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

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryTestFixtures
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator

/**
 * A prefix the dictionary continues must never produce an empty suggestion strip. The sweep uses
 * the shipped dictionaries: every seventh word of each, and every prefix of it that some other
 * word continues, through the real [TdictPrefixIndex].
 *
 * It runs with the key-neighbor table set, because only then does the autocorrect pass inside
 * `lookup()` execute. That pass starts at [AutocorrectPolicy.MIN_WORD_CODE_POINTS] = 4 code
 * points, and an exception from it would be swallowed by the `catch (_: RuntimeException)` around
 * the lookup, silently discarding candidates the exact pass had found.
 */
class ShippedIndexPrefixCoverageTest {

    @Test
    fun everyPrefixTheDictionaryContinuesIsAnswered() {
        for ((language, bundle) in listOf("ru" to russian, "tt" to tatar)) {
            val loaded = bundle ?: error("index $language did not load")
            loaded.index.updateKeyNeighbors(neighbourTable(language))
            var checked = 0
            val blanks = ArrayList<String>()
            for (wordIndex in loaded.words.indices step 7) {
                val word = loaded.words[wordIndex]
                for (length in 1..word.length) {
                    val prefix = word.substring(0, length)
                    if (!hasStrictContinuation(loaded.words, prefix)) continue
                    checked++
                    val result = loaded.index.lookup(
                        ImmutableUtf8Prefix.copyOf(prefix.toByteArray(Charsets.UTF_8)),
                    )
                    if (result.isEmpty() && blanks.size < 20) blanks.add(prefix)
                }
            }
            println("[$language] prefixes with a continuation: $checked, blank: ${blanks.size}")
            assertTrue("[$language] the sweep covered nothing", checked > 10_000)
            assertEquals("[$language] prefixes the dictionary continues but the index leaves blank",
                emptyList<String>(), blanks)
        }
    }

    /** Four prefixes once seen with an empty strip, named so a regression is easy to read. */
    @Test
    fun theReportedPrefixesAllAnswerFromTheShippedRussianDictionary() {
        val loaded = russian ?: error("ru index did not load")
        loaded.index.updateKeyNeighbors(neighbourTable("ru"))
        for (prefix in listOf("при", "прив", "приве", "привет")) {
            val result = loaded.index.lookup(
                ImmutableUtf8Prefix.copyOf(prefix.toByteArray(Charsets.UTF_8)),
            )
            assertEquals("'$prefix' must fill all three cells", 3, result.size)
        }
    }

    /** True when some word OTHER than [prefix] itself begins with it. */
    private fun hasStrictContinuation(words: List<String>, prefix: String): Boolean {
        var low = 0
        var high = words.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (words[mid] < prefix) low = mid + 1 else high = mid
        }
        var scan = low
        while (scan < words.size && words[scan].startsWith(prefix)) {
            if (words[scan] != prefix) return true
            scan++
        }
        return false
    }

    internal class Loaded(val index: TdictPrefixIndex, val words: List<String>)

    companion object {
        private var russian: Loaded? = null
        private var tatar: Loaded? = null

        @JvmStatic
        @BeforeClass
        fun load() {
            russian = open(DictionaryArtifactSpec.RUSSIAN_TOP100K_V1)
            tatar = open(DictionaryArtifactSpec.TATAR_TOP100K_V1)
        }

        private fun open(spec: DictionaryArtifactSpec): Loaded {
            val asset = locate("src/main/assets/${spec.assetPath}", "app/src/main/assets/${spec.assetPath}")
            val rawFile = File.createTempFile("prefix-coverage-", ".tdict")
            try {
                rawFile.outputStream().use { output ->
                    TdictValidator().inflateAsset(asset.inputStream(), output, spec)
                }
                val validated = TdictValidator().validateRaw(rawFile, spec)
                val raw = rawFile.readBytes()
                val identity = DictionaryIdentity(
                    spec.generation, validated.schemaId, validated.formatVersion, validated.rawSha256,
                )
                val index = TdictPrefixIndex.open(
                    ByteBuffer.wrap(raw), identity, validated.entryCount, validated.rawSize,
                ) ?: error("index did not open for ${spec.family}")
                return Loaded(index, decodeWords(raw))
            } finally {
                rawFile.delete()
            }
        }

        /** Reads the words straight out of the schema-2 file; the reader keeps them private. */
        private fun decodeWords(raw: ByteArray): List<String> = DictionaryTestFixtures.words(raw)

        private fun locate(vararg paths: String): File =
            paths.map(::File).firstOrNull(File::isFile) ?: error("cannot locate ${paths.toList()}")

        /**
         * Letter keys and long-press partners of the shipped Cyrillic layouts, mirroring the
         * layout XML.
         */
        private fun neighbourTable(language: String): KeyNeighborTable {
            val keys = ArrayList<KeyNeighborTable.RawKey>()
            val partners = mapOf(
                'у' to "ү", 'е' to "ё", 'н' to "ң", 'г' to "һ", 'х' to "һ",
                'а' to "ә", 'о' to "ө", 'ж' to "җ", 'э' to "ә", 'ь' to "ъ",
            )
            fun row(letters: String) {
                for (letter in letters) {
                    val partnerChars = partners[letter].orEmpty()
                    keys.add(
                        KeyNeighborTable.RawKey(
                            letter.code,
                            IntArray(partnerChars.length) { partnerChars[it].code },
                        ),
                    )
                }
            }
            if (language == "tt") row("әөүҗңһ")
            row("йцукенгшщзх")
            row("фывапролджэ")
            row("ячсмитьбю")
            return KeyNeighborTable.build(language, true, keys)
        }
    }
}
