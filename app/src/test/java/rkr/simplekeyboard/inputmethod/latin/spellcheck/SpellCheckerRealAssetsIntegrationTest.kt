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

package rkr.simplekeyboard.inputmethod.latin.spellcheck

import android.view.textservice.SuggestionsInfo
import java.io.File
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.TatarLayoutTestFixtures
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalDictionary
import rkr.simplekeyboard.inputmethod.latin.morphology.MorphologyTestSupport
import rkr.simplekeyboard.inputmethod.latin.morphology.TatarMorphologicalAcceptor

/** Integration over the production loader, indexes, acceptor and sentence engine. */
class SpellCheckerRealAssetsIntegrationTest {

    @Test
    fun correctTatarWordIsAcceptedAndTypoGetsValidSuggestion() {
        val checked = engine().checkSentence("бүген сэлам", 4)

        assertAccepted(checked.answers[0])
        assertTypo(checked.answers[1])
        assertTrue(checked.answers[1].suggestions.contains("сәлам"))
        checked.answers[1].suggestions.forEach {
            assertTrue(dictionaries.tatarBaseWord(it) || dictionaries.personalWord("tt_RU", it))
        }
    }

    @Test
    fun reviewedProperNameIsAccepted() {
        assertAccepted(engine().checkWord("Аллаһ", 4))
    }

    @Test
    fun russianIsAcceptedInTatarSentenceAndRussianSentenceIsUnchecked() {
        val tatar = engine().checkSentence("бүген привет", 4)
        assertAccepted(tatar.answers[0])
        assertAccepted(tatar.answers[1])

        val russian = engine().checkSentence("это очень хороший фыва", 4)
        russian.answers.forEach(::assertAccepted)
    }

    @Test
    fun latinDigitsUrlsEmailEmojiAndMixedTextAreAcceptedInRealSentence() {
        val checked = engine().checkSentence(
            "бүген Latin API24 https://мәктәп.татар кирешмә@мәктәп.татар " +
                "сәлам😀 сәламabc",
            4,
        )

        checked.answers.forEach(::assertAccepted)
    }

    @Test
    fun switchOffAndRequestedLimitAreHonoredWithRealAssets() {
        val disabled = SpellCheckerEngine({ false }, { error("disabled load") })
        disabled.checkSentence("бүген сэлам", 4).answers.forEach(::assertAccepted)

        val limited = engine().checkSentence("бүген сэлам", 1)
        assertTypo(limited.answers[1])
        assertTrue(limited.answers[1].suggestions.size <= 1)
    }

    @Test
    fun acceptorLoadFailureLeavesBundledDictionaryLookupUsable() {
        val withoutAcceptor = requireNotNull(loadDictionaries(null))

        assertTrue(withoutAcceptor.tatarBaseWord("бүген"))
        assertAccepted(SpellCheckerEngine({ true }, { withoutAcceptor }).checkWord("бүген", 4))
    }

    @Test
    fun tatarChatSpellingOffersTheTatarCorrection() {
        val checked = engine().checkSentence("бүген рэхмэт", 4)

        assertAccepted(checked.answers[0])
        assertTypo(checked.answers[1])
        assertEquals("рәхмәт", checked.answers[1].suggestions.firstOrNull())
    }

    @Test
    fun tatarChatSpellingRecoversSeveralSubstitutedLetters() {
        val checked = engine().checkSentence("бүген хэбэр", 4)

        assertTypo(checked.answers[1])
        assertEquals("хәбәр", checked.answers[1].suggestions.firstOrNull())
    }

    @Test
    fun chatRecoveryLeavesVerdictsAndRussianTextUnchanged() {
        // The word-only callback leaves a word without a Tatar-specific letter alone, and one such
        // word does not make a sentence Tatar.
        assertAccepted(engine().checkWord("рэхмэт", 4))
        assertAccepted(engine().checkSentence("рэхмэт", 4).answers.single())

        // A Russian sentence stays unchecked, so its words never reach chat recovery.
        engine().checkSentence("очень хороший рэхмэт", 4).answers.forEach(::assertAccepted)
    }

    @Test
    fun concurrentRequestsProduceCorrectAnswers() {
        val sharedEngine = engine()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val jobs = (0 until 24).map { index ->
                Callable {
                    repeat(20) {
                        val checked = sharedEngine.checkSentence(
                            if (index % 2 == 0) "бүген сэлам" else "бүген",
                            4,
                        )
                        if (index % 2 == 0) {
                            assertAccepted(checked.answers[0])
                            assertTypo(checked.answers[1])
                        } else {
                            assertAccepted(checked.answers[0])
                        }
                    }
                }
            }
            executor.invokeAll(jobs).forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun warmTwoHundredWordParagraphReportsMedianAndP95WithinJvmCeiling() {
        val paragraph = paragraph(200)
        val session = SpellCheckerSession(engine())
        val request = SpellCheckerRequest(paragraph, 1, 1)
        assertEquals(200, session.sentence(request, 4).words.size)

        val timings = LongArray(11)
        var answers = 0
        for (sample in timings.indices) {
            val started = System.nanoTime()
            answers += session.sentence(request, 4).words.size
            timings[sample] = System.nanoTime() - started
        }
        timings.sort()
        val median = timings[timings.size / 2]
        val p95 = timings[ceil(timings.size * 0.95).toInt() - 1]
        println(
            "spell checker warm paragraph words=200 " +
                "median=${"%.3f".format(Locale.ROOT, median / 1_000_000.0)} ms " +
                "p95=${"%.3f".format(Locale.ROOT, p95 / 1_000_000.0)} ms answers=$answers",
        )
        assertTrue("p95=${p95 / 1_000_000.0}ms", p95 <= 5_000_000_000L)
    }

    private fun engine(): SpellCheckerEngine = SpellCheckerEngine({ true }, { dictionaries })

    private fun assertAccepted(answer: SpellCheckerAnswer) {
        assertEquals(SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY, answer.attributes)
        assertTrue(answer.suggestions.isEmpty())
    }

    private fun assertTypo(answer: SpellCheckerAnswer) {
        assertTrue(answer.attributes and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO != 0)
    }

    companion object {
        private lateinit var dictionaries: SpellCheckerDictionaries

        @BeforeClass
        @JvmStatic
        fun loadRealAssets() {
            dictionaries = requireNotNull(loadDictionaries(MorphologyTestSupport.realAcceptor()))
        }

        private fun loadDictionaries(
            acceptor: TatarMorphologicalAcceptor?,
        ): SpellCheckerDictionaries? = SpellCheckerDictionaryLoader(
            opener = SpellCheckerAssetOpener { path -> locateAsset(path).inputStream() },
            acceptor = { acceptor },
            personal = PersonalDictionarySource { PersonalDictionary.EMPTY },
            neighbors = TatarLayoutTestFixtures.tatarNeighborTable(),
        ).load()

        private fun locateAsset(path: String): File = listOf(
            File("src/main/assets", path),
            File("app/src/main/assets", path),
        ).firstOrNull(File::isFile) ?: error("missing bundled dictionary asset")

        private fun paragraph(wordCount: Int): String {
            val file = listOf(
                File("src/test/resources/tt_eval_sentences.txt"),
                File("app/src/test/resources/tt_eval_sentences.txt"),
            ).firstOrNull(File::isFile) ?: error("missing Tatar evaluation sentences")
            val text = StringBuilder()
            for (line in file.readLines(Charsets.UTF_8)) {
                if (line.startsWith('#') || line.isBlank()) continue
                if (text.isNotEmpty()) text.append('\n')
                text.append(line)
                val spans = SpellCheckerWords.wordSpans(text)
                if (spans.size >= wordCount) {
                    val end = spans[wordCount - 1].let { it.start + it.length }
                    return text.substring(0, end)
                }
            }
            error("Tatar evaluation text is shorter than requested")
        }
    }
}
