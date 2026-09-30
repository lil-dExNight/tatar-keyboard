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

import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.AutocorrectAdvice
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.AutocorrectPolicy

/**
 * The autocorrect preview: the painted form of a coming replacement and the decision whether the
 * separator-time policy would fire on a given word. The advice is read lazily, only once the
 * cheap checks have passed.
 */

/**
 * The painted form of a coming replacement: the typed word as the user sees it and the
 * correction as it would be inserted. Deliberately mute, like [RevertWindow.Replacement] — this
 * object carries the user's text.
 */
internal class AutocorrectPreview(
    val typedShown: String,
    val correctionShown: String,
) {
    override fun toString(): String = "AutocorrectPreview"
}

// Fixed layout of the preview strip: the typed word leads, the emphasized correction follows,
// and the third cell stays empty, so the two read as a choice ("keep this" / "this is coming")
// rather than a ranking.
internal const val PREVIEW_EMPHASIZED_CELL = 1

/**
 * Would the separator-time autocorrect fire on [word], the prefix this result was computed for?
 * Mirrors every condition of the controller's maybeAutocorrectBeforeSeparator (gate, length floor,
 * casing, verdict provenance and freshness, frequency floor, "replacement is not the word
 * itself"), so the strip announces exactly what a separator would do. The editor-side conditions
 * (known cursor, cursor not inside a word) were already established by the request path.
 *
 * No lookup of its own: the verdict comes from the lookup this strip answers. Checks run
 * cheapest-first, so the common case never allocates; normalization runs only once a verdict
 * exists. The raw length pre-check is a safe fast path (NFC never grows the code-point count),
 * and the floor is re-checked on the normalized form, like the separator path.
 */
internal fun computeAutocorrectPreview(
    gate: AutocorrectGate,
    word: String,
    suppressedWord: String?,
    refusedWords: Set<String> = emptySet(),
    adviceProvider: () -> AutocorrectAdvice?,
): AutocorrectPreview? {
    if (!gate.isOn()) return null
    if (word.isEmpty()) return null
    // A refused advice stays refused for as long as this occurrence stands.
    if (word == suppressedWord) return null
    val casing = TatarWordUtils.classifyCasing(word)
    if (casing == TatarWordUtils.PrefixCasing.MIXED) return null
    if (word.codePointCount(0, word.length) < AutocorrectPolicy.MIN_WORD_CODE_POINTS) {
        return null
    }
    val advice = adviceProvider() ?: return null
    val normalized = TatarWordUtils.normalizeForLookup(word)
    if (advice.typedWord != normalized) return null
    // A correction the user undid in this field session is not announced again.
    if (normalized in refusedWords) return null
    if (normalized.codePointCount(0, normalized.length) <
        AutocorrectPolicy.MIN_WORD_CODE_POINTS
    ) {
        return null
    }
    if (advice.frequency < AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY) return null
    val correction = TatarWordUtils.applyCasing(advice.replacement, casing)
    if (correction == word) return null
    return AutocorrectPreview(word, correction)
}
