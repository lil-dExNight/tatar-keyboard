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
 * The autocorrect preview of P2 (Phase 3, docs/ROADMAP-P3.md): the painted form of a coming
 * replacement and the decision whether the separator-time policy would fire on a given word.
 * Pure move from `SuggestionsController.kt` (ROADMAP Phase 6, T2); the decision reads its inputs
 * as parameters now instead of controller fields, in the same order and at the same moments —
 * the advice in particular is still read lazily, only once the cheap checks have passed.
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

// P2 (docs/ROADMAP-P3.md): the preview band's fixed layout — the typed word leads, the
// correction follows, emphasized; the third cell stays empty so the two read as a
// decision ("keep this" / "this is coming"), not as a ranking.
internal const val PREVIEW_EMPHASIZED_CELL = 1

/**
 * The preview half of the D3 decision (P2 of Phase 3, docs/ROADMAP-P3.md): would the
 * separator-time policy fire on [word], the prefix this result was computed for? Every condition
 * of the controller's maybeAutocorrectBeforeSeparator is mirrored here — the gate, the length
 * floor, the casing, the verdict's provenance and freshness, the frequency floor, the
 * "replacement is not the word itself" guard — so what the strip announces is EXACTLY what a
 * separator would do, never more. The two editor-side conditions (known cursor, cursor not inside
 * a word) are not re-checked: a PREFIX result can only be applied for a trailing word at a known
 * cursor, which the request path established before the request went out.
 *
 * Costs one volatile read and a handful of comparisons — no lookup of its own: the verdict was
 * computed by the very lookup this band is the answer to. The checks are ordered cheapest-first
 * so the common band (a short word, a dictionary word with no verdict) never allocates: the
 * normalization runs only once a verdict exists, i.e. when a preview is genuinely about to fire.
 * The raw length pre-check is a conservative fast path — NFC never grows the code-point count —
 * and the floor is still re-checked on the normalized form after provenance, mirroring the
 * separator path exactly.
 */
internal fun computeAutocorrectPreview(
    gate: AutocorrectGate,
    word: String,
    suppressedWord: String?,
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
