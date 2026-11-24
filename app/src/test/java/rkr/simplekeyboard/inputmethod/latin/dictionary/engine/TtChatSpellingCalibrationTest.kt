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
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarChatSpellingRules
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarSuffixRules

/**
 * Measures the synthetic Russian-letter probe set through the shipped Tatar engine: recovery in
 * the visible strip, autocorrect silence, budget cleanliness and host latency. The typed words
 * are probe data, not dictionary admissions.
 */
class TtChatSpellingCalibrationTest {

    @Test
    fun chatSubstitutionRecoversTheMultiSubstitutionProbes() {
        val index = requireNotNull(tatarIndex)
        val rows = PROBES.map { probe ->
            val prefix = ImmutableUtf8Prefix.copyOf(probe.typed.toByteArray(Charsets.UTF_8))
            val started = System.nanoTime()
            val results = index.lookup(prefix)
            val elapsed = System.nanoTime() - started
            val engineRank = results.indexOf(probe.intended) + 1
            val stripRank = if (index.lastExactCount == 0 && engineRank > 0) engineRank + 1
            else engineRank
            val advice = index.lastAutocorrectAdvice
            val harmful = advice != null && advice.replacement != probe.intended
            check(!harmful) { "harmful autocorrect on ${probe.typed}->${advice?.replacement}" }
            check(!index.lastFuzzyOverBudget) { "budget trip on ${probe.typed}" }
            ChatRow(probe, engineRank, stripRank, elapsed, advice != null)
        }

        for (group in listOf("1", "2", "3")) {
            val rowsInGroup = rows.filter {
                if (group == "3") it.probe.substitutions >= 3
                else it.probe.substitutions.toString() == group
            }
            val visible = rowsInGroup.count { it.stripRank in 1..3 }
            val autocorrect = rowsInGroup.count { it.autocorrected }
            println(
                "CHAT|recovery|substitutions=$group|rows=${rowsInGroup.size}|" +
                    "visible_top3=$visible|autocorrect_intended=$autocorrect",
            )
        }
        val multi = rows.filter { it.probe.substitutions >= 2 }
        assertTrue("multi-substitution recovery is empty", multi.any { it.stripRank in 1..3 })
        val timings = rows.map { it.elapsedNanos }.sorted()
        val p95 = timings[ceil(timings.size * 0.95).toInt() - 1] / 1_000_000.0
        println("CHAT|latency|samples=${timings.size}|p95_ms=%.3f".format(p95))
        assertTrue("chat probe p95=$p95 ms exceeds 5 ms", p95 <= 5.0)
        assertEquals(PROBES.size, rows.size)
        assertEquals(PIN_AUTOCORRECT_HARMFUL, 0)
        assertEquals(
            PIN_AUTOCORRECT_FIRES,
            rows.count { it.autocorrected },
        )
        assertEquals(
            PIN_VISIBLE_TOP3_MULTI,
            multi.count { it.stripRank in 1..3 },
        )
    }

    @Test
    fun homographsAreNeverChatCorrections() {
        val index = requireNotNull(tatarIndex)
        val word = ImmutableUtf8Prefix.copyOf("монда".toByteArray(Charsets.UTF_8))
        val results = index.lookup(word)
        assertEquals(listOf("мондамы", "мондарак", "мондагы", "мондагылар"), results)
        assertNull(index.lastAutocorrectAdvice)
        assertFalse(index.lastFuzzyOverBudget)
    }

    private class ChatRow(
        val probe: Probe,
        val engineRank: Int,
        val stripRank: Int,
        val elapsedNanos: Long,
        val autocorrected: Boolean,
    )

    private class Probe(val typed: String, val intended: String, val substitutions: Int)

    companion object {
        private const val PIN_AUTOCORRECT_HARMFUL = 0
        private const val PIN_AUTOCORRECT_FIRES = 2
        private const val PIN_VISIBLE_TOP3_MULTI = 24
        private val neighborTable = TatarLayoutTestFixtures.tatarNeighborTable()
        private var tatarIndex: TdictPrefixIndex? = null

        private val PROBES = listOf(
            Probe("сэлам", "сәлам", 1),
            Probe("унай", "уңай", 1),
            Probe("кон", "көн", 1),
            Probe("узе", "үзе", 1),
            Probe("белэн", "белән", 1),
            Probe("очен", "өчен", 1),
            Probe("тугел", "түгел", 1),
            Probe("буген", "бүген", 1),
            Probe("хэзер", "хәзер", 1),
            Probe("кирэк", "кирәк", 1),
            Probe("ана", "аңа", 1),
            Probe("шуна", "шуңа", 1),
            Probe("момкин", "мөмкин", 1),
            Probe("сойли", "сөйли", 1),
            Probe("хава", "һава", 1),
            Probe("жыр", "җыр", 1),
            Probe("аллах", "аллаһ", 1),
            Probe("ярдэм", "ярдәм", 1),
            Probe("рэхмэт", "рәхмәт", 2),
            Probe("хэм", "һәм", 2),
            Probe("йорэк", "йөрәк", 2),
            Probe("хэбэр", "хәбәр", 2),
            Probe("курэ", "күрә", 2),
            Probe("узенен", "үзенең", 2),
            Probe("нэрсэ", "нәрсә", 2),
            Probe("мэктэп", "мәктәп", 2),
            Probe("мохим", "мөһим", 2),
            Probe("тубэн", "түбән", 2),
            Probe("бэйрэм", "бәйрәм", 2),
            Probe("кубрэк", "күбрәк", 2),
            Probe("узэк", "үзәк", 2),
            Probe("кондэ", "көндә", 2),
            Probe("эйтэ", "әйтә", 2),
            Probe("сузлэр", "сүзләр", 2),
            Probe("кунел", "күңел", 2),
            Probe("дэулэт", "дәүләт", 3),
            Probe("шэхэр", "шәһәр", 3),
            Probe("узгэрту", "үзгәртү", 3),
            Probe("элбэттэ", "әлбәттә", 3),
            Probe("мэскэу", "мәскәү", 3),
            Probe("мэсэлэн", "мәсәлән", 3),
            Probe("шэхэре", "шәһәре", 3),
            Probe("морэжэгать", "мөрәҗәгать", 4),
            Probe("искэрмэлэр", "искәрмәләр", 3),
            Probe("олкэсендэ", "өлкәсендә", 3),
            Probe("курсэтэ", "күрсәтә", 3),
            Probe("жину", "җиңү", 3),
            Probe("килэчэктэ", "киләчәктә", 3),
            Probe("шэхэрендэ", "шәһәрендә", 4),
        )

        @JvmStatic
        @BeforeClass
        fun loadCommittedAssets() {
            val dictionarySpec = DictionaryArtifactSpec.TATAR_TOP100K_V1
            val dictionaryAsset = locate("src/main/assets/${dictionarySpec.assetPath}")
            val dictionaryRaw = File.createTempFile("chat-calibration-dictionary-", ".tdict")
            try {
                dictionaryRaw.outputStream().use { output ->
                    TdictValidator().inflateAsset(dictionaryAsset.inputStream(), output, dictionarySpec)
                }
                val validated = TdictValidator().validateRaw(dictionaryRaw, dictionarySpec)
                val identity = DictionaryIdentity(
                    dictionarySpec.generation, validated.schemaId, validated.formatVersion,
                    validated.rawSha256,
                )
                tatarIndex = requireNotNull(
                    TdictPrefixIndex.open(
                        ByteBuffer.wrap(dictionaryRaw.readBytes()), identity,
                        validated.entryCount, validated.rawSize, TatarSuffixRules,
                        FuzzyEditPolicy.TATAR, AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_TT,
                        chatSubstitutions = TatarChatSpellingRules,
                    ),
                ).also { it.updateKeyNeighbors(neighborTable) }
            } finally {
                dictionaryRaw.delete()
            }

            // This is a prefix-path measurement; next-word prediction is covered by the existing
            // bigram suites.
        }

        private fun locate(vararg paths: String): File =
            paths.map(::File).firstOrNull(File::isFile)
                ?: error("cannot locate committed asset from ${File(".").absolutePath}")
    }
}
