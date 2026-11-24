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

import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarWordUtils

/** The exact candidates followed by the typo-recovery candidates of one prefix lookup. */
internal class SpellCheckerTypoCandidates(
    val words: List<String>,
    val exactCount: Int,
)

/** Read-only language data for the spell checker. Implementations are thread-safe. */
internal interface SpellCheckerWordSource {
    fun tatarBaseWord(normalizedWord: String): Boolean

    fun russianBaseWord(normalizedWord: String): Boolean

    fun personalWord(subtypeId: String, normalizedWord: String): Boolean

    fun typoCandidates(normalizedWord: String): SpellCheckerTypoCandidates
}

/**
 * Word and sentence verdicts for the Tatar spell checker.
 *
 * The language of a sentence is decided per submitted text, never from the session subtype. Only a
 * Tatar sentence flags words; Russian and English text is left unchecked.
 */
internal class SpellCheckerEngine(
    private val enabled: () -> Boolean,
    private val loadSource: () -> SpellCheckerWordSource?,
) {
    @Volatile
    private var source: SpellCheckerWordSource? = null
    private val sourceLock = Any()

    private fun activeSource(): SpellCheckerWordSource? {
        source?.let { return it }
        return synchronized(sourceLock) {
            source?.let { return@synchronized it }
            try {
                loadSource()?.also { source = it }
            } catch (_: Exception) {
                null
            }
        }
    }

    /** The word-only rule: a word needs a Tatar-specific letter to be flagged without support. */
    fun checkWord(word: String, suggestionsLimit: Int): SpellCheckerAnswer {
        if (!enabled()) return SpellCheckerAnswer.ACCEPTED
        val normalized = TatarWordUtils.normalizeForLookup(word)
        if (normalized.isEmpty() || isExempt(word, normalized)) return SpellCheckerAnswer.ACCEPTED
        val active = activeSource() ?: return SpellCheckerAnswer.ACCEPTED
        val tatar = acceptsTatar(active, normalized)
        if (tatar) return SpellCheckerAnswer.ACCEPTED
        val russian = active.russianBaseWord(normalized) ||
            active.personalWord(PersonalSubtypes.RUSSIAN, normalized)
        if (russian) return SpellCheckerAnswer.ACCEPTED
        if (!hasTatarSpecificLetter(normalized)) {
            // Without a Tatar-only letter the evidence is too weak to call an unknown word a typo,
            // so Russian text is never flagged by the word-only callback.
            return SpellCheckerAnswer.ACCEPTED
        }
        return typoAnswer(active, normalized, word, suggestionsLimit)
    }

    /** One verdict per word of [text], with the offsets the framework underlines. */
    fun checkSentence(text: String, suggestionsLimit: Int): SpellCheckerSentence {
        val spans = SpellCheckerWords.wordSpans(text)
        if (spans.isEmpty()) return SpellCheckerSentence(emptyList(), emptyList())
        if (!enabled()) {
            return SpellCheckerSentence(spans, List(spans.size) { SpellCheckerAnswer.ACCEPTED })
        }
        val active = activeSource()
        if (active == null) {
            return SpellCheckerSentence(spans, List(spans.size) { SpellCheckerAnswer.ACCEPTED })
        }

        val facts = Array(spans.size) { index ->
            val word = text.substring(spans[index].start, spans[index].start + spans[index].length)
            wordFacts(active, word)
        }
        var known = 0
        var tatarKnown = 0
        for (fact in facts) {
            if (fact.exempt) continue
            if (fact.tatarKnown) tatarKnown++
            if (fact.tatarKnown || fact.russianKnown) known++
        }
        val tatarLanguage = facts.any { !it.exempt && it.tatarSpecific } ||
            (known > 0 && tatarKnown * 2 > known)
        if (!tatarLanguage) {
            return SpellCheckerSentence(spans, List(spans.size) { SpellCheckerAnswer.ACCEPTED })
        }
        return SpellCheckerSentence(
            spans,
            List(spans.size) { index ->
                val fact = facts[index]
                if (fact.exempt || fact.tatarKnown || fact.russianKnown) {
                    SpellCheckerAnswer.ACCEPTED
                } else {
                    typoAnswer(active, fact.normalized, fact.rawWord, suggestionsLimit)
                }
            },
        )
    }

    private fun wordFacts(active: SpellCheckerWordSource, word: String): WordFacts {
        val normalized = TatarWordUtils.normalizeForLookup(word)
        if (normalized.isEmpty()) {
            return WordFacts("", word, exempt = true, tatarKnown = false, russianKnown = false,
                tatarSpecific = false)
        }
        if (isExempt(word, normalized)) {
            return WordFacts(normalized, word, exempt = true, tatarKnown = false,
                russianKnown = false, tatarSpecific = hasTatarSpecificLetter(normalized))
        }
        val tatar = acceptsTatar(active, normalized)
        if (tatar) {
            return WordFacts(normalized, word, exempt = false, tatarKnown = true,
                russianKnown = false, tatarSpecific = hasTatarSpecificLetter(normalized))
        }
        val russian = active.russianBaseWord(normalized) ||
            active.personalWord(PersonalSubtypes.RUSSIAN, normalized)
        return WordFacts(normalized, word, exempt = false, tatarKnown = false,
            russianKnown = russian, tatarSpecific = hasTatarSpecificLetter(normalized))
    }

    private fun acceptsTatar(active: SpellCheckerWordSource, normalized: String): Boolean =
        active.tatarBaseWord(normalized) ||
            active.personalWord(PersonalSubtypes.TATAR_RU, normalized)

    private fun typoAnswer(
        active: SpellCheckerWordSource,
        normalized: String,
        rawWord: String,
        suggestionsLimit: Int,
    ): SpellCheckerAnswer {
        val limit = suggestionsLimit.coerceAtLeast(0)
        if (limit == 0) return SpellCheckerAnswer.typo(emptyList())
        val casing = TatarWordUtils.classifyCasing(rawWord)
        if (casing == TatarWordUtils.PrefixCasing.MIXED) {
            return SpellCheckerAnswer.typo(emptyList())
        }
        val candidates = try {
            active.typoCandidates(normalized)
        } catch (_: Exception) {
            return SpellCheckerAnswer.typo(emptyList())
        }
        if (candidates.exactCount >= candidates.words.size) {
            return SpellCheckerAnswer.typo(emptyList())
        }
        val suggestions = ArrayList<String>(minOf(limit, candidates.words.size))
        val seen = HashSet<String>()
        val start = candidates.exactCount.coerceIn(0, candidates.words.size)
        for (index in start until candidates.words.size) {
            val candidate = candidates.words[index]
            val candidateNormalized = TatarWordUtils.normalizeForLookup(candidate)
            if (candidateNormalized == normalized || !seen.add(candidateNormalized)) continue
            val valid = try {
                acceptsTatar(active, candidateNormalized)
            } catch (_: Exception) {
                false
            }
            if (!valid) continue
            suggestions.add(TatarWordUtils.applyCasing(candidate, casing))
            if (suggestions.size == limit) break
        }
        return SpellCheckerAnswer.typo(suggestions)
    }

    private fun isExempt(word: String, normalized: String): Boolean {
        if (word.isEmpty()) return true
        var firstScript: Character.UnicodeScript? = null
        var mixedScripts = false
        var offset = 0
        while (offset < word.length) {
            val codePoint = Character.codePointAt(word, offset)
            if (Character.isDigit(codePoint)) return true
            if (SpellCheckerText.isEmoji(codePoint)) return true
            if (Character.isLetter(codePoint)) {
                val script = Character.UnicodeScript.of(codePoint)
                if (script == Character.UnicodeScript.LATIN) return true
                if (script != Character.UnicodeScript.COMMON &&
                    script != Character.UnicodeScript.INHERITED
                ) {
                    if (firstScript == null) {
                        firstScript = script
                    } else if (firstScript != script) {
                        mixedScripts = true
                    }
                }
            }
            offset += Character.charCount(codePoint)
        }
        if (mixedScripts) return true
        return SpellCheckerText.looksLikeUrlOrEmail(word) ||
            SpellCheckerText.looksLikeUrlOrEmail(normalized)
    }

    private class WordFacts(
        val normalized: String,
        val rawWord: String,
        val exempt: Boolean,
        val tatarKnown: Boolean,
        val russianKnown: Boolean,
        val tatarSpecific: Boolean,
    )

    private companion object {
        const val TATAR_SPECIFIC_LETTERS = "әөүҗңһ"

        fun hasTatarSpecificLetter(normalized: String): Boolean {
            for (letter in TATAR_SPECIFIC_LETTERS) {
                if (normalized.indexOf(letter) >= 0) return true
            }
            return false
        }
    }
}

/** Maps platform requests to verdicts and back, preserving each request's cookie and sequence. */
internal class SpellCheckerSession(private val engine: SpellCheckerEngine) {

    fun suggestions(
        request: SpellCheckerRequest,
        suggestionsLimit: Int,
    ): SpellCheckerWordResponse = SpellCheckerWordResponse(
        WordSpan(0, request.text.length),
        engine.checkWord(request.text, suggestionsLimit),
        request.cookie,
        request.sequence,
    )

    fun sentence(
        request: SpellCheckerRequest,
        suggestionsLimit: Int,
    ): SpellCheckerSentenceResponse {
        val checked = engine.checkSentence(request.text, suggestionsLimit)
        return SpellCheckerSentenceResponse(
            List(checked.spans.size) { index ->
                SpellCheckerWordResponse(
                    checked.spans[index],
                    checked.answers[index],
                    request.cookie,
                    request.sequence,
                )
            },
        )
    }
}
