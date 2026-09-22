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

import java.text.Normalizer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarWordUtils.PrefixCasing

class TatarWordUtilsTest {

    private fun nfd(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)

    @Test
    fun extractTrailingWordReturnsLetterRun() {
        assertEquals("salom", TatarWordUtils.extractTrailingWord("salom"))
    }

    @Test
    fun extractTrailingWordTakesOnlyTheFinalWord() {
        assertEquals("world", TatarWordUtils.extractTrailingWord("hello world"))
    }

    @Test
    fun extractTrailingWordStopsAtPunctuation() {
        assertEquals("def", TatarWordUtils.extractTrailingWord("abc-def"))
    }

    @Test
    fun extractTrailingWordEmptyWhenLastCharIsSpace() {
        assertEquals("", TatarWordUtils.extractTrailingWord("salom "))
    }

    @Test
    fun extractTrailingWordEmptyWhenLastCharIsPunctuation() {
        assertEquals("", TatarWordUtils.extractTrailingWord("done."))
    }

    @Test
    fun extractTrailingWordEmptyWhenLastCharIsDigit() {
        assertEquals("", TatarWordUtils.extractTrailingWord("test123"))
    }

    @Test
    fun extractTrailingWordEmptyForNullAndEmpty() {
        assertEquals("", TatarWordUtils.extractTrailingWord(null))
        assertEquals("", TatarWordUtils.extractTrailingWord(""))
    }

    @Test
    fun extractTrailingWordHandlesRussianCyrillic() {
        assertEquals("привет", TatarWordUtils.extractTrailingWord("скажи привет"))
    }

    @Test
    fun extractTrailingWordHandlesTatarSpecificLetters() {
        // Contains Tatar-specific letters ә, ү, җ, ң, һ, ө.
        assertEquals("сүзләрөҗңһ", TatarWordUtils.extractTrailingWord("яз сүзләрөҗңһ"))
    }

    @Test
    fun extractTrailingWordHandlesMixedScripts() {
        // Latin + Cyrillic are all letters, so the whole trailing run is returned.
        assertEquals("abвг", TatarWordUtils.extractTrailingWord(" abвг"))
    }

    @Test
    fun extractTrailingWordKeepsCombiningMarksInsideDecomposedWord() {
        // In NFD "й" is "и" + U+0306; the mark must not cut the word down to its tail.
        assertEquals(nfd("сәйл"), TatarWordUtils.extractTrailingWord(nfd("яз сәйл")))
    }

    @Test
    fun extractTrailingWordKeepsWordEndingWithCombiningMark() {
        // The user just typed the decomposed "й", so the last character is the mark itself.
        val word = TatarWordUtils.extractTrailingWord(nfd("яз сәй"))
        assertEquals(nfd("сәй"), word)
        assertEquals('\u0306', word[word.length - 1])
    }

    @Test
    fun extractTrailingWordHandlesDecomposedRussianYo() {
        // In NFD "ё" is "е" + U+0308.
        assertEquals(nfd("ёлка"), TatarWordUtils.extractTrailingWord(nfd("бу ёлка")))
    }

    @Test
    fun extractTrailingWordReturnsRawSpanNotNormalizedForm() {
        // The commit path deletes exactly word.length raw characters, so the span must stay
        // decomposed: 4 chars here (с, ә, и, U+0306) against 3 in NFC.
        val word = TatarWordUtils.extractTrailingWord(nfd("яз сәй"))
        assertEquals(4, word.length)
        assertNotEquals("сәй", word)
        assertEquals("сәй", Normalizer.normalize(word, Normalizer.Form.NFC))
    }

    @Test
    fun extractTrailingWordUnchangedForComposedInput() {
        assertEquals("сәйл", TatarWordUtils.extractTrailingWord("яз сәйл"))
        assertEquals("ёлка", TatarWordUtils.extractTrailingWord("бу ёлка"))
    }

    // --- extractNextWordContext (E5d, "Контракт текста" amendment, 2026-08-17) ------------------

    @Test
    fun extractNextWordContextReturnsTheWordBeforeATrailingSpaceRun() {
        assertEquals("сүз", TatarWordUtils.extractNextWordContext("яз сүз "))
    }

    @Test
    fun extractNextWordContextAcceptsMoreThanOneTrailingSpace() {
        assertEquals("сүз", TatarWordUtils.extractNextWordContext("яз сүз   "))
    }

    @Test
    fun extractNextWordContextEmptyWithoutATrailingSpaceAtAll() {
        assertEquals("", TatarWordUtils.extractNextWordContext("яз сүз"))
    }

    @Test
    fun sentenceStartAndPositionsAfterPunctuationNeverBuildAContext() {
        // "Начало предложения и позиция после пунктуации предсказаний не получают" — a mechanical
        // consequence of the separator rule being EXACTLY U+0020, not any non-word character.
        assertEquals("", TatarWordUtils.extractNextWordContext("")) // sentence / field start
        assertEquals("", TatarWordUtils.extractNextWordContext("сүз.")) // right after a period
        assertEquals("", TatarWordUtils.extractNextWordContext("сүз,")) // right after a comma
        assertEquals("", TatarWordUtils.extractNextWordContext("сүз\n")) // right after a newline
        assertEquals("", TatarWordUtils.extractNextWordContext("сүз\t")) // right after a tab
        assertEquals("", TatarWordUtils.extractNextWordContext("сүз ")) // right after an NBSP
    }

    @Test
    fun extractNextWordContextEmptyWhenTheSeparatorItselfReachesTheCacheBoundary() {
        // "если разделитель ... упирается в нулевой индекс кэша" — an all-spaces cache with no
        // letter in front of it at all: the true separator run might extend further back than what
        // this snippet shows, so it cannot be trusted to be exactly "one or more U+0020".
        assertEquals("", TatarWordUtils.extractNextWordContext("   "))
    }

    @Test
    fun extractNextWordContextEmptyWhenTheWordItselfReachesTheCacheBoundary() {
        // "или слово ... упирается в нулевой индекс кэша" — the word touches index 0 of the given
        // text, so it may have been cut off by the real cache limit
        // (Constants.EDITOR_CONTENTS_CACHE_SIZE, 1024 characters) rather than genuinely starting
        // there. The single-argument overload cannot tell and stays conservative; production
        // passes the cache-start knowledge explicitly (see the two-argument tests below).
        assertEquals("", TatarWordUtils.extractNextWordContext("сүз "))
        assertEquals("", TatarWordUtils.extractNextWordContext("сүз ", false))
    }

    // --- extractNextWordContext with cache-start knowledge (docs/NEXTWORD-RACE.md, 2026-09-01) ---

    @Test
    fun extractNextWordContextAtTheProvenTextStartReturnsTheFirstWordOfAField() {
        // A word touching index 0 of a cache that PROVABLY reached the start of the text (a window
        // shorter than the 1024 characters asked for) is whole, not truncated: the first word of
        // an empty field gets its prediction like any other. This is the exact case the single-
        // argument overload had to refuse.
        assertEquals("сүз", TatarWordUtils.extractNextWordContext("сүз ", true))
        assertEquals("сүз", TatarWordUtils.extractNextWordContext("сүз   ", true))
        assertEquals(nfd("сәй"), TatarWordUtils.extractNextWordContext(nfd("сәй "), true))
    }

    @Test
    fun extractNextWordContextCacheStartKnowledgeNeverInventsAContext() {
        // The flag lifts only the word-at-index-0 guard; everything else is untouched: no trailing
        // U+0020 run, an all-spaces cache, an empty one and null all still yield "".
        assertEquals("", TatarWordUtils.extractNextWordContext("сүз", true))
        assertEquals("", TatarWordUtils.extractNextWordContext("   ", true))
        assertEquals("", TatarWordUtils.extractNextWordContext("", true))
        assertEquals("", TatarWordUtils.extractNextWordContext(null, true))
        assertEquals("", TatarWordUtils.extractNextWordContext("сүз.\n ", true))
    }

    @Test
    fun extractNextWordContextUsesTheSameWordBoundaryAlgorithmAsThePrefix() {
        // Combining marks continue the word exactly like extractTrailingWord: "яз " + decomposed
        // "сәй" + a trailing space run must still find the whole decomposed word, not just its tail.
        assertEquals(nfd("сәй"), TatarWordUtils.extractNextWordContext(nfd("бу яз сәй ")))
    }

    @Test
    fun extractNextWordContextEmptyForNullAndEmpty() {
        assertEquals("", TatarWordUtils.extractNextWordContext(null))
        assertEquals("", TatarWordUtils.extractNextWordContext(""))
    }

    // --- isSentenceStartContext (P4, docs/TT-SUGGESTIONS.md) --------------------------------------

    @Test
    fun sentenceStartAfterEachOfTheFourEndingCharacters() {
        assertTrue(TatarWordUtils.isSentenceStartContext("сүз. "))
        assertTrue(TatarWordUtils.isSentenceStartContext("сүз! "))
        assertTrue(TatarWordUtils.isSentenceStartContext("сүз? "))
        assertTrue(TatarWordUtils.isSentenceStartContext("сүз… "))
    }

    @Test
    fun sentenceStartAcceptsRunsOfEndingPunctuationAndSpaces() {
        assertTrue(TatarWordUtils.isSentenceStartContext("сүз... "))
        assertTrue(TatarWordUtils.isSentenceStartContext("нәрсә?!  "))
        assertTrue(TatarWordUtils.isSentenceStartContext("сүз.… "))
    }

    @Test
    fun sentenceStartIsFalseWithoutATrailingSpaceRun() {
        // The prediction moment is the space after the sentence end, not the period itself.
        assertFalse(TatarWordUtils.isSentenceStartContext("сүз."))
        assertFalse(TatarWordUtils.isSentenceStartContext("сүз"))
        assertFalse(TatarWordUtils.isSentenceStartContext("сүз.\n")) // a newline is not U+0020
    }

    @Test
    fun sentenceStartIsFalseAfterAWordOrOtherPunctuation() {
        assertFalse(TatarWordUtils.isSentenceStartContext("яз сүз "))
        assertFalse(TatarWordUtils.isSentenceStartContext("сүз, "))
        assertFalse(TatarWordUtils.isSentenceStartContext("сүз: "))
        assertFalse(TatarWordUtils.isSentenceStartContext("сүз» ")) // closing quote, no period
        assertFalse(TatarWordUtils.isSentenceStartContext("сүз.» ")) // quote after the period
        assertFalse(TatarWordUtils.isSentenceStartContext("(сүз.) "))
        assertFalse(TatarWordUtils.isSentenceStartContext("5. ")) // a number, not a sentence end
    }

    @Test
    fun sentenceStartAtTheFieldStartNeedsTheCacheProvenance() {
        // An empty or all-spaces text is a field start only when the cache provably reached the
        // start of the text; the single-argument overload cannot tell and stays conservative.
        assertTrue(TatarWordUtils.isSentenceStartContext("", true))
        assertTrue(TatarWordUtils.isSentenceStartContext("  ", true))
        assertFalse(TatarWordUtils.isSentenceStartContext(""))
        assertFalse(TatarWordUtils.isSentenceStartContext("  "))
        assertFalse(TatarWordUtils.isSentenceStartContext(null))
        assertFalse(TatarWordUtils.isSentenceStartContext(null, true))
    }

    @Test
    fun sentenceStartWithPunctuationAtIndexZeroNeedsTheCacheProvenance() {
        // ". " is a field that begins with a period only when nothing was cut off before it.
        assertTrue(TatarWordUtils.isSentenceStartContext(". ", true))
        assertFalse(TatarWordUtils.isSentenceStartContext(". "))
        assertFalse(TatarWordUtils.isSentenceStartContext(". ", false))
    }

    @Test
    fun sentenceStartAndNextWordContextNeverAnswerTheSamePosition() {
        // The two detectors are complementary by construction: wherever a context word exists the
        // position is not a sentence start, and wherever a sentence start is detected the context
        // is empty (the frozen NEXT_WORD exclusion of punctuation is unchanged).
        for (text in listOf("сүз. ", "сүз! ", "сүз… ", "сүз.» ", "сүз, ", "")) {
            if (TatarWordUtils.isSentenceStartContext(text, true)) {
                assertEquals("", TatarWordUtils.extractNextWordContext(text, true))
            }
        }
        assertFalse(TatarWordUtils.isSentenceStartContext("яз сүз ", true))
        assertEquals("сүз", TatarWordUtils.extractNextWordContext("яз сүз ", true))
    }

    // --- extractNextWordContext after non-final punctuation (ROADMAP Phase 1, P4) ----------------

    @Test
    fun extractNextWordContextAfterNonFinalPunctuationReturnsTheWordBeforeIt() {
        // Mid-text positions need no provenance: the punctuation is visible right where it
        // matters. One-word fields go through the provenance overload — the index-0 guard is
        // pinned in the boundary test below.
        assertEquals("сүз", TatarWordUtils.extractNextWordContext("яз сүз, "))
        assertEquals("сүз", TatarWordUtils.extractNextWordContext("яз сүз; "))
        assertEquals("сүз", TatarWordUtils.extractNextWordContext("яз сүз: "))
        assertEquals("сүз", TatarWordUtils.extractNextWordContext("яз сүз,  "))
        assertEquals("сүз", TatarWordUtils.extractNextWordContext("яз сүз,, "))
        assertEquals("сүз", TatarWordUtils.extractNextWordContext("сүз, ", true))
        assertEquals("сүз", TatarWordUtils.extractNextWordContext("сүз; ", true))
        assertEquals("сүз", TatarWordUtils.extractNextWordContext("сүз: ", true))
        // The extraction is language-agnostic: the Russian layout gets the same rule.
        assertEquals("слово", TatarWordUtils.extractNextWordContext("слово, ", true))
    }

    @Test
    fun extractNextWordContextAfterNonFinalPunctuationKeepsTheCacheBoundaryRules() {
        // The word touching index 0 of a possibly truncated cache is not trusted — the exact
        // guard the plain-word path has; with the provenance flag the first word of a field is
        // whole.
        assertEquals("", TatarWordUtils.extractNextWordContext("сүз, "))
        assertEquals("", TatarWordUtils.extractNextWordContext("сүз, ", false))
        assertEquals("сүз", TatarWordUtils.extractNextWordContext("сүз, ", true))
    }

    @Test
    fun extractNextWordContextPunctuationEdgesYieldNothing() {
        // Punctuation with nothing before it.
        assertEquals("", TatarWordUtils.extractNextWordContext(", "))
        assertEquals("", TatarWordUtils.extractNextWordContext(", ", true))
        assertEquals("", TatarWordUtils.extractNextWordContext(",, "))
        // A run mixing final and non-final punctuation has no word before the non-final run.
        assertEquals("", TatarWordUtils.extractNextWordContext("сүз.., ", true))
        assertEquals("", TatarWordUtils.extractNextWordContext("сүз. ,", true))
        // No trailing space run at all — the prediction moment is the space after the comma.
        assertEquals("", TatarWordUtils.extractNextWordContext("сүз,"))
        assertEquals("", TatarWordUtils.extractNextWordContext("сүз,тез"))
        // A digit is not a word: numbers after a comma get no context, like anywhere else.
        assertEquals("", TatarWordUtils.extractNextWordContext("5, "))
        // Sentence-final punctuation keeps resetting the context (the sentence-start domain).
        assertEquals("", TatarWordUtils.extractNextWordContext("сүз. ", true))
        assertEquals("", TatarWordUtils.extractNextWordContext("сүз! ", true))
    }

    @Test
    fun nonFinalPunctuationIsNotASentenceStart() {
        // The two domains are disjoint: a comma/semicolon/colon keeps a word context, and the
        // sentence-start detector stays off for them.
        for (text in listOf("сүз, ", "сүз; ", "сүз: ", ", ")) {
            assertFalse(TatarWordUtils.isSentenceStartContext(text, true))
        }
    }

    @Test
    fun extractTrailingWordEmptyForLoneCombiningMark() {
        // A mark with no base letter in the run is an orphan, not a word.
        assertEquals("", TatarWordUtils.extractTrailingWord("сүз \u0306"))
        assertEquals("", TatarWordUtils.extractTrailingWord("\u0306"))
    }

    @Test
    fun extractTrailingWordTrimsOrphanMarkAtRunStart() {
        assertEquals("def", TatarWordUtils.extractTrailingWord("abc-\u0306def"))
    }

    @Test
    fun normalizeForLookupLowercasesLatin() {
        assertEquals("hello", TatarWordUtils.normalizeForLookup("HeLLo"))
    }

    @Test
    fun normalizeForLookupLowercasesTatarSpecificLetters() {
        assertEquals("әөүҗңһ", TatarWordUtils.normalizeForLookup("ӘӨҮҖҢҺ"))
    }

    @Test
    fun normalizeForLookupLowercasesMixedTatarWord() {
        assertEquals("сүзләр", TatarWordUtils.normalizeForLookup("Сүзләр"))
        assertEquals("сүзләр", TatarWordUtils.normalizeForLookup("СҮЗЛӘР"))
    }

    @Test
    fun normalizeForLookupIsIdempotentOnAlreadyNormalized() {
        val normalized = TatarWordUtils.normalizeForLookup("сүзләр")
        assertEquals(normalized, TatarWordUtils.normalizeForLookup(normalized))
    }

    @Test
    fun toLookupBytesEncodesUtf8() {
        assertArrayEquals("сүз".toByteArray(Charsets.UTF_8), TatarWordUtils.toLookupBytes("сүз"))
    }

    @Test
    fun toLookupBytesRoundTripsThroughNormalization() {
        val bytes = TatarWordUtils.toLookupBytes(TatarWordUtils.normalizeForLookup("СҮЗ"))
        assertArrayEquals("сүз".toByteArray(Charsets.UTF_8), bytes)
    }

    @Test
    fun classifyCasingLowerForAllLowercase() {
        assertEquals(PrefixCasing.LOWER, TatarWordUtils.classifyCasing("сүз"))
        assertEquals(PrefixCasing.LOWER, TatarWordUtils.classifyCasing("әни"))
        assertEquals(PrefixCasing.LOWER, TatarWordUtils.classifyCasing("hello"))
        assertEquals(PrefixCasing.LOWER, TatarWordUtils.classifyCasing("с"))
    }

    @Test
    fun classifyCasingLowerForEmptyPrefix() {
        assertEquals(PrefixCasing.LOWER, TatarWordUtils.classifyCasing(""))
    }

    @Test
    fun classifyCasingInitialCapsForSingleUppercaseLetter() {
        // "One uppercase letter" is Initial Caps, not ALL CAPS: the sentence-start case.
        assertEquals(PrefixCasing.INITIAL_CAPS, TatarWordUtils.classifyCasing("С"))
        assertEquals(PrefixCasing.INITIAL_CAPS, TatarWordUtils.classifyCasing("Ә"))
    }

    @Test
    fun classifyCasingInitialCapsForLeadingUppercase() {
        assertEquals(PrefixCasing.INITIAL_CAPS, TatarWordUtils.classifyCasing("Сүз"))
        assertEquals(PrefixCasing.INITIAL_CAPS, TatarWordUtils.classifyCasing("Әни"))
        assertEquals(PrefixCasing.INITIAL_CAPS, TatarWordUtils.classifyCasing("Җырлар"))
    }

    @Test
    fun classifyCasingAllCapsForTwoOrMoreUppercase() {
        assertEquals(PrefixCasing.ALL_CAPS, TatarWordUtils.classifyCasing("СҮЗ"))
        assertEquals(PrefixCasing.ALL_CAPS, TatarWordUtils.classifyCasing("СҮ"))
        assertEquals(PrefixCasing.ALL_CAPS, TatarWordUtils.classifyCasing("ӘӨҮҖҢҺ"))
    }

    @Test
    fun classifyCasingMixedForEverythingElse() {
        assertEquals(PrefixCasing.MIXED, TatarWordUtils.classifyCasing("сҮз"))
        assertEquals(PrefixCasing.MIXED, TatarWordUtils.classifyCasing("СҮз"))
        assertEquals(PrefixCasing.MIXED, TatarWordUtils.classifyCasing("аБ"))
        assertEquals(PrefixCasing.MIXED, TatarWordUtils.classifyCasing("сүЗЛӘР"))
    }

    @Test
    fun classifyCasingIgnoresCaselessCombiningMarks() {
        // Decomposed input must classify exactly like its composed form: the marks are caseless.
        assertEquals(PrefixCasing.INITIAL_CAPS, TatarWordUtils.classifyCasing(nfd("Й")))
        assertEquals(PrefixCasing.INITIAL_CAPS, TatarWordUtils.classifyCasing(nfd("Сәй")))
        assertEquals(PrefixCasing.ALL_CAPS, TatarWordUtils.classifyCasing(nfd("СӘЙ")))
        assertEquals(PrefixCasing.LOWER, TatarWordUtils.classifyCasing(nfd("сәй")))
        assertEquals(PrefixCasing.LOWER, TatarWordUtils.classifyCasing("\u0306"))
    }

    @Test
    fun applyCasingLowerReturnsCandidateUnchanged() {
        assertEquals("сүзләр", TatarWordUtils.applyCasing("сүзләр", PrefixCasing.LOWER))
    }

    @Test
    fun applyCasingInitialCapsUppercasesOnlyTheFirstLetter() {
        assertEquals("Сүзләр", TatarWordUtils.applyCasing("сүзләр", PrefixCasing.INITIAL_CAPS))
    }

    @Test
    fun applyCasingInitialCapsHandlesTatarSpecificFirstLetters() {
        assertEquals("Әни", TatarWordUtils.applyCasing("әни", PrefixCasing.INITIAL_CAPS))
        assertEquals("Өйдә", TatarWordUtils.applyCasing("өйдә", PrefixCasing.INITIAL_CAPS))
        assertEquals("Үзем", TatarWordUtils.applyCasing("үзем", PrefixCasing.INITIAL_CAPS))
        assertEquals("Җыр", TatarWordUtils.applyCasing("җыр", PrefixCasing.INITIAL_CAPS))
        assertEquals("Һава", TatarWordUtils.applyCasing("һава", PrefixCasing.INITIAL_CAPS))
        assertEquals("Ңгы", TatarWordUtils.applyCasing("ңгы", PrefixCasing.INITIAL_CAPS))
    }

    @Test
    fun applyCasingAllCapsUppercasesTatarSpecificLetters() {
        assertEquals("СҮЗЛӘР", TatarWordUtils.applyCasing("сүзләр", PrefixCasing.ALL_CAPS))
        assertEquals("ӘӨҮҖҢҺ", TatarWordUtils.applyCasing("әөүҗңһ", PrefixCasing.ALL_CAPS))
    }

    @Test
    fun applyCasingHandlesEmptyCandidate() {
        assertEquals("", TatarWordUtils.applyCasing("", PrefixCasing.INITIAL_CAPS))
        assertEquals("", TatarWordUtils.applyCasing("", PrefixCasing.ALL_CAPS))
    }

    @Test
    fun applyCasingMixedIsAPassThrough() {
        // Mixed case must be filtered out before this point (0 results); never a crash.
        assertEquals("сүзләр", TatarWordUtils.applyCasing("сүзләр", PrefixCasing.MIXED))
    }

    @Test
    fun startsWithWordCharacterIsFalseForEmptyAndWordEndingContext() {
        assertFalse(TatarWordUtils.startsWithWordCharacter(null))
        assertFalse(TatarWordUtils.startsWithWordCharacter(""))
        assertFalse(TatarWordUtils.startsWithWordCharacter(" дигән"))
        assertFalse(TatarWordUtils.startsWithWordCharacter(", дигән"))
        assertFalse(TatarWordUtils.startsWithWordCharacter("\nсүз"))
        assertFalse(TatarWordUtils.startsWithWordCharacter("123"))
        assertFalse(TatarWordUtils.startsWithWordCharacter("-сүз"))
        assertFalse(TatarWordUtils.startsWithWordCharacter("😀"))
    }

    @Test
    fun startsWithWordCharacterIsTrueInsideAWord() {
        // Cursor in the middle of "китап": committing a replacement would corrupt the text.
        assertTrue(TatarWordUtils.startsWithWordCharacter("тап"))
        assertTrue(TatarWordUtils.startsWithWordCharacter("ә"))
        // Deliberately wider than the Tatar alphabet: fail-closed also for Russian and Latin.
        assertTrue(TatarWordUtils.startsWithWordCharacter("ыть"))
        assertTrue(TatarWordUtils.startsWithWordCharacter("word"))
    }

    @Test
    fun startsWithWordCharacterIsTrueForALeadingCombiningMark() {
        // The cursor sits inside a canonically decomposed letter ("й" = "и" + U+0306), which is
        // inside a word by definition.
        assertTrue(TatarWordUtils.startsWithWordCharacter(nfd("й").substring(1)))
    }

    @Test
    fun needsAutoSpaceAtTheEndOfTheText() {
        assertTrue(TatarWordUtils.needsAutoSpace(null))
        assertTrue(TatarWordUtils.needsAutoSpace(""))
    }

    @Test
    fun needsNoAutoSpaceWhenASpaceIsAlreadyThere() {
        assertFalse(TatarWordUtils.needsAutoSpace(" дигән"))
        assertFalse(TatarWordUtils.needsAutoSpace(" "))
        assertFalse(TatarWordUtils.needsAutoSpace("\tдигән"))
        assertFalse(TatarWordUtils.needsAutoSpace("\nдигән"))
        // Non-breaking space: not isWhitespace(), but visually a space all the same.
        assertFalse(TatarWordUtils.needsAutoSpace(" дигән"))
    }

    @Test
    fun needsNoAutoSpaceBeforePunctuationThatHugsTheWord() {
        // "сүз," must never become "сүз ,".
        assertFalse(TatarWordUtils.needsAutoSpace(", дигән"))
        assertFalse(TatarWordUtils.needsAutoSpace("."))
        assertFalse(TatarWordUtils.needsAutoSpace("!"))
        assertFalse(TatarWordUtils.needsAutoSpace("?"))
        assertFalse(TatarWordUtils.needsAutoSpace(":"))
        assertFalse(TatarWordUtils.needsAutoSpace(";"))
        assertFalse(TatarWordUtils.needsAutoSpace(")"))
        assertFalse(TatarWordUtils.needsAutoSpace("]"))
        assertFalse(TatarWordUtils.needsAutoSpace("-сүз"))
        assertFalse(TatarWordUtils.needsAutoSpace("_сүз"))
        assertFalse(TatarWordUtils.needsAutoSpace("\"дигән\""))
        assertFalse(TatarWordUtils.needsAutoSpace("'дигән'"))
    }

    @Test
    fun needsNoAutoSpaceBeforeNonAsciiTypography() {
        // Exactly what a hardcoded ASCII list would miss: «», the em dash, the ellipsis, curly
        // quotes. All of them are covered because the classification is by Unicode category.
        assertFalse(TatarWordUtils.needsAutoSpace("»"))
        assertFalse(TatarWordUtils.needsAutoSpace("«дигән»"))
        assertFalse(TatarWordUtils.needsAutoSpace("—"))
        assertFalse(TatarWordUtils.needsAutoSpace("…"))
        assertFalse(TatarWordUtils.needsAutoSpace("“дигән”"))
    }

    @Test
    fun needsAutoSpaceBeforeDigitsSymbolsAndLetters() {
        assertTrue(TatarWordUtils.needsAutoSpace("123"))
        assertTrue(TatarWordUtils.needsAutoSpace("5 сум"))
        // Math symbols take spaces around them ("2 + 2"), so they are not hugging punctuation.
        assertTrue(TatarWordUtils.needsAutoSpace("+ 2"))
        assertTrue(TatarWordUtils.needsAutoSpace("= 4"))
        assertTrue(TatarWordUtils.needsAutoSpace("😀"))
        // A letter after the cursor is refused earlier by the commit guard; the predicate itself
        // still has to answer, and "add the space" is the harmless answer.
        assertTrue(TatarWordUtils.needsAutoSpace("дигән"))
    }

    @Test
    fun needsAutoSpaceClassifiesTheFirstCodePointNotTheLeadingSurrogate() {
        // U+10101 AEGEAN WORD SEPARATOR DOT is punctuation (Po) but lives outside the BMP: read as
        // a lone leading surrogate it would be uncategorized and would wrongly get a space.
        val supplementaryPunctuation = String(Character.toChars(0x10101))
        assertEquals(2, supplementaryPunctuation.length)
        assertFalse(TatarWordUtils.needsAutoSpace(supplementaryPunctuation))
        // The mirror case: a supplementary symbol is not punctuation and does take the space.
        assertTrue(TatarWordUtils.needsAutoSpace(String(Character.toChars(0x1F600))))
    }

    @Test
    fun classifyThenApplyPreservesTypedCapitalization() {
        val candidate = "сүзләр"
        assertEquals(
            "Сүзләр",
            TatarWordUtils.applyCasing(candidate, TatarWordUtils.classifyCasing("Сүз")),
        )
        assertEquals(
            "СҮЗЛӘР",
            TatarWordUtils.applyCasing(candidate, TatarWordUtils.classifyCasing("СҮЗ")),
        )
        assertEquals(
            "сүзләр",
            TatarWordUtils.applyCasing(candidate, TatarWordUtils.classifyCasing("сүз")),
        )
        // A single typed capital at a sentence start keeps the capital the user produced.
        assertEquals(
            "Сүзләр",
            TatarWordUtils.applyCasing(candidate, TatarWordUtils.classifyCasing("С")),
        )
    }
}
