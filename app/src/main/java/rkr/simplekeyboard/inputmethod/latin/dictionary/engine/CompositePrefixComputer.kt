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

import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalBigramSource
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalCandidate
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalCandidateSource
import rkr.simplekeyboard.inputmethod.latin.glide.GlideComputer
import rkr.simplekeyboard.inputmethod.latin.glide.GlideGeometrySink
import rkr.simplekeyboard.inputmethod.latin.glide.GlideIndexReleaser
import rkr.simplekeyboard.inputmethod.latin.glide.GlideKeyGeometry
import rkr.simplekeyboard.inputmethod.latin.glide.GlidePath

/**
 * After-word forms: word forms of the just-committed context word that fill the strip cells the
 * bigram successors leave free in a NEXT_WORD answer. The Tatar engine uses
 * `TatarSuffixRules`-backed forms; the Russian engine has none.
 */
fun interface AfterWordForms {
    /**
     * Forms of [contextWord] to append after the bigram successors: disjoint from [alreadyShown],
     * at most [maxOut], frequency descending. Empty when the word has no attested forms.
     */
    fun formsOf(
        contextWord: ImmutableUtf8Prefix,
        alreadyShown: List<String>,
        maxOut: Int,
    ): List<String>
}

/**
 * Builds the [AfterWordForms] of one engine against that engine's own dictionary. A factory
 * because the dictionary index exists only inside engine startup; the rules are stateless.
 */
fun interface AfterWordFormsFactory {
    fun createAfterWordForms(dictionary: WordFrequencySource): AfterWordForms
}

/**
 * Merges dictionary candidates and one personal-dictionary word into the three strip cells:
 *
 *  1. exact dictionary candidates, in their order (frequency descending, then code point);
 *  2. at most one personal-only word: index 0 when there are no exact candidates, index 1
 *     otherwise (it pushes the third exact candidate out of the strip);
 *  3. typo-recovery candidates, in their own order.
 *
 * A word in both the dictionary and the personal list takes one cell; when its saved spelling
 * differs from the normalized form (for example, capitalized), the personal spelling is shown.
 *
 * With the personal source empty (feature off, device locked, nothing saved) this returns the
 * primary's list itself, not a copy; the empty check comes before the prefix is decoded.
 *
 * Casing is not applied here: the controller applies display casing after ranking. In lower-case
 * mode `applyCasing` keeps a saved spelling as is; the capitalized modes re-case it like any word.
 */
internal class CompositePrefixComputer(
    private val primary: ClassifiedPrefixComputer,
    private val personal: PersonalCandidateSource,
    // After-word forms for NEXT_WORD answers. Null for an engine without word-form rules (the
    // Russian one).
    private val afterWordForms: AfterWordForms? = null,
    // Top-frequency fill of the NEXT_WORD cells still empty after bigrams and forms. Null: no
    // fill.
    private val fallbackWords: FallbackWords? = null,
    // Learned word pairs for NEXT_WORD answers. Separate from the prefix path's
    // PersonalCandidateSource, which predict never reads. EMPTY: no learned pairs.
    private val personalBigrams: PersonalBigramSource = PersonalBigramSource.EMPTY,
    // The glide decode side. Null: glide typing returns no candidates. The host wraps the
    // personal dictionary itself (see GlideDecoderHost).
    private val glideHost: GlideDecoderHost? = null,
) : PrefixComputer, KeyNeighborSink, NextWordComputer, GlideComputer, GlideGeometrySink,
    GlideIndexReleaser {

    /**
     * The autocorrect verdict as it leaves the engine: the primary's, unless the typed word is in
     * the personal dictionary. Written by the worker after each lookup, read on the UI thread.
     */
    @Volatile
    var lastAutocorrectAdvice: AutocorrectAdvice? = null
        private set

    /**
     * The normalized prefix of the newest lookup whose exact pass found no dictionary word
     * continuing it, else null. Written by the worker, read on the UI thread; the reader checks it
     * against its own prefix.
     */
    @Volatile
    var lastExactMissPrefix: String? = null
        private set

    /** Drops the current verdicts; called when the engine idles or is torn down. */
    fun clearLookupVerdicts() {
        lastAutocorrectAdvice = null
        lastExactMissPrefix = null
    }

    /**
     * Two-stage readiness: the bigram table is not open yet when this computer is handed to
     * [LatestOnlyPrefixEngine], and publishing the engine must not wait for it.
     * [attachBigramSource] wires it in eagerly; [deferBigramAttach] hands over a deferred attach
     * that the first [predict] runs on the engine worker. Written off the worker, read on it.
     */
    @Volatile
    private var bigramSource: NextWordComputer? = null

    /** The deferred attach handed to [deferBigramAttach]; consumed on the engine worker only. */
    @Volatile
    private var pendingBigramAttach: (() -> Unit)? = null

    fun attachBigramSource(source: NextWordComputer) {
        // An eager attach replaces a deferred one: predict reads bigramSource first, so the
        // pending attach could never fire anyway, but keeping it would pin its catalog.
        pendingBigramAttach = null
        bigramSource = source
    }

    /**
     * Defers the bigram attach to the first [predict], which runs it on the engine worker: the
     * mapping and the table walk happen there. One-shot; a failed attach is terminal, as with an
     * eager attach.
     */
    fun deferBigramAttach(attach: () -> Unit) {
        pendingBigramAttach = attach
    }

    /**
     * Next-word prediction. The prefix-path personal source ([personal]) is never read here;
     * learned word pairs come through [personalBigrams]. The chain is: bigram successors >
     * learned pairs > word forms > fallback. A later source never displaces or duplicates an
     * earlier one and never goes past the three strip cells:
     *
     *  - bigram successors come first and are never displaced;
     *  - learned pairs fill free cells, at most [MAX_PERSONAL_BIGRAM_CELLS], in their own order
     *    (usage, then frequency, descending); a pair already shown as a successor is skipped, so
     *    the bundled spelling wins;
     *  - word forms and the fallback fill what is still free, excluding everything shown.
     *
     * With no bigram source attached (none given yet, the deferred attach not yet run, or the
     * table failed to open) this returns an empty list, with no learned pairs, forms or fallback.
     * The first predict after a deferred attach was set runs it inline, so only a request that
     * predates it answers empty; the controller re-requests such a moment only while the strip
     * holds no active-language word.
     */
    override fun predict(normalizedContextWordUtf8: ImmutableUtf8Prefix): List<String> {
        val source = bigramSource ?: runPendingBigramAttach() ?: return emptyList()
        var result = source.predict(normalizedContextWordUtf8)
        if (result.size < CELL_COUNT) {
            result = withPersonalPairs(result, normalizedContextWordUtf8)
        }
        if (result.size < CELL_COUNT) {
            val forms = afterWordForms
            if (forms != null) {
                // Broken forms leave the bigram successors intact.
                val extras = try {
                    forms.formsOf(normalizedContextWordUtf8, result, CELL_COUNT - result.size)
                } catch (_: RuntimeException) {
                    null
                }
                if (!extras.isNullOrEmpty()) result = result + extras
            }
        }
        if (result.size < CELL_COUNT) {
            val fallback = fallbackWords
            if (fallback != null) {
                // A broken fallback leaves the earlier results intact.
                val extras = try {
                    fallback.fallbackWords(normalizedContextWordUtf8, result, CELL_COUNT - result.size)
                } catch (_: RuntimeException) {
                    null
                }
                if (!extras.isNullOrEmpty()) result = result + extras
            }
        }
        return result
    }

    /**
     * Runs the deferred bigram attach, once, inside the calling [predict]. Consumed before
     * running, so a failing attach never retries: the source stays null and [predict] keeps
     * answering an empty list.
     */
    private fun runPendingBigramAttach(): NextWordComputer? {
        val pending = pendingBigramAttach ?: return null
        pendingBigramAttach = null
        try {
            pending()
        } catch (_: Throwable) {
            // The attach reports its own failure; prediction keeps answering empty.
        }
        return bigramSource
    }

    /**
     * Adds the context word's learned pairs to the cells the bigram successors left free. Returns
     * [result] itself, not a copy, when the feature is off, the source throws, or no pair fits.
     */
    private fun withPersonalPairs(
        result: List<String>,
        normalizedContextWordUtf8: ImmutableUtf8Prefix,
    ): List<String> {
        if (personalBigrams.isEmpty()) return result
        val room = minOf(MAX_PERSONAL_BIGRAM_CELLS, CELL_COUNT - result.size)
        if (room <= 0) return result
        val matches = try {
            personalBigrams.successorsFor(normalizedContextWordUtf8.decodeUtf8())
        } catch (_: RuntimeException) {
            // A broken personal source leaves the bigram successors intact.
            return result
        }
        if (matches.isEmpty()) return result
        val extras = ArrayList<String>(room)
        for (match in matches) {
            if (extras.size >= room) break
            // Shown once, and the bundled spelling wins: duplicates are compared on the
            // normalized form, as in the prefix merge.
            if (result.contains(match.normalizedForm)) continue
            if (extras.contains(match.normalizedForm) || extras.contains(match.rawForm)) continue
            extras.add(match.rawForm)
        }
        return if (extras.isEmpty()) result else result + extras
    }

    override fun updateKeyNeighbors(table: KeyNeighborTable?) {
        (primary as? KeyNeighborSink)?.updateKeyNeighbors(table)
    }

    override fun updateGlideGeometry(geometry: GlideKeyGeometry?) {
        glideHost?.updateGlideGeometry(geometry)
    }

    override fun decodeGlide(path: GlidePath): List<String> =
        glideHost?.decodeGlide(path) ?: emptyList()

    /**
     * Idle memory release: the host drops the lazily built glide word index; the next decode
     * rebuilds it. Runs on the engine worker, like [decodeGlide].
     */
    override fun releaseGlideIndex() {
        glideHost?.releaseIndex()
    }

    override fun lookup(normalizedPrefixUtf8: ImmutableUtf8Prefix): List<String> {
        val dictionary = primary.lookup(normalizedPrefixUtf8)
        lastAutocorrectAdvice = withoutPersonalWords(primary.lastAutocorrectAdvice)
        // Decoded only on an empty exact pass, which the learning rule needs whatever typo
        // recovery added after it.
        lastExactMissPrefix =
            if (primary.lastExactCount == 0) normalizedPrefixUtf8.decodeUtf8() else null
        if (personal.isEmpty()) return dictionary
        val matches = try {
            personal.candidatesFor(normalizedPrefixUtf8.decodeUtf8())
        } catch (_: RuntimeException) {
            // A broken personal source leaves the dictionary suggestions intact.
            return dictionary
        }
        if (matches.isEmpty()) return dictionary
        val exactCount = primary.lastExactCount.coerceIn(0, dictionary.size)
        return merge(dictionary, exactCount, matches)
    }

    /**
     * A word in the personal dictionary is never autocorrected: the user has saved it as theirs.
     * Membership is checked on the normalized form (`PersonalDictionary.indexOfNormalized`).
     *
     * With the personal dictionary off the source publishes an empty snapshot, so nothing is read;
     * a disabled personal dictionary costs the lookup path nothing. If the source throws, no advice
     * is returned, so the user's text is not edited.
     */
    private fun withoutPersonalWords(advice: AutocorrectAdvice?): AutocorrectAdvice? {
        if (advice == null) return null
        if (personal.isEmpty()) return advice
        return try {
            if (personal.containsNormalized(advice.typedWord)) null else advice
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun merge(
        dictionary: List<String>,
        exactCount: Int,
        matches: List<PersonalCandidate>,
    ): List<String> {
        val personalOnly = firstPersonalOnly(dictionary, matches)
        if (personalOnly == null && !anyDuplicateOverridesCasing(dictionary, matches)) {
            return dictionary
        }

        val cells = ArrayList<String>(CELL_COUNT)
        if (personalOnly != null && exactCount > 0) {
            cells.add(displayFormOf(dictionary[0], matches))
        }
        if (personalOnly != null) {
            cells.add(personalOnly.rawForm)
        }
        val firstRemainingExact = if (personalOnly != null && exactCount > 0) 1 else 0
        for (index in firstRemainingExact until exactCount) {
            if (cells.size >= CELL_COUNT) return cells
            cells.add(displayFormOf(dictionary[index], matches))
        }
        for (index in exactCount until dictionary.size) {
            if (cells.size >= CELL_COUNT) return cells
            cells.add(displayFormOf(dictionary[index], matches))
        }
        return cells
    }

    /**
     * The personal word that gets a cell of its own: the first match whose normalized form is not
     * among the dictionary candidates. A duplicate only changes how its shared cell is spelled.
     */
    private fun firstPersonalOnly(
        dictionary: List<String>,
        matches: List<PersonalCandidate>,
    ): PersonalCandidate? {
        for (match in matches) {
            if (!dictionary.contains(match.normalizedForm)) return match
        }
        return null
    }

    /**
     * Display form of one dictionary candidate: its own text, unless a personal record has the same
     * normalized form and a different saved spelling, which then wins.
     */
    private fun displayFormOf(dictionaryWord: String, matches: List<PersonalCandidate>): String {
        for (match in matches) {
            if (match.normalizedForm == dictionaryWord && match.rawForm != match.normalizedForm) {
                return match.rawForm
            }
        }
        return dictionaryWord
    }

    private fun anyDuplicateOverridesCasing(
        dictionary: List<String>,
        matches: List<PersonalCandidate>,
    ): Boolean {
        for (match in matches) {
            if (match.rawForm != match.normalizedForm && dictionary.contains(match.normalizedForm)) {
                return true
            }
        }
        return false
    }

    companion object {
        /**
         * The strip has three cells (`SuggestionStripState.CELL_COUNT`) and the index returns at
         * most three candidates (`TdictPrefixIndex.MAX_RESULTS`).
         */
        internal const val CELL_COUNT = 3

        /**
         * Most cells learned pairs may take in one NEXT_WORD answer. With all three cells free one
         * is always left for word forms and the fallback.
         */
        internal const val MAX_PERSONAL_BIGRAM_CELLS = 2
    }
}
