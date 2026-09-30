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

import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PairCompletionSink
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.WordCompletionSink

/**
 * The clean-run machines of [SuggestionsController]: the completed-word machine (personal
 * dictionary) and the completed-pair machine (learned word pairs), which share one run of text.
 *
 * Nothing here is persisted and nothing leaves this object except the completions handed to the
 * two sinks; with the default sinks those are no-ops.
 */
internal class CleanRunMachine(private val editor: EditorSurface) {

    // --- Word clean-run state.
    /** The trailing word as last seen. Empty between words. */
    private var runWord: String = ""
    /** False as soon as anything but plain growth happens; a dirty run reports nothing. */
    private var runClean: Boolean = true
    /** Length of the longest proper prefix of the current run that came back with NO candidates. */
    private var runEmptyResultPrefixLength: Int = NO_EMPTY_RESULT

    /** Where clean word completions go. Default writes nothing. */
    var completionSink: WordCompletionSink = WordCompletionSink.NONE

    // --- Pair clean-run state. The pair machine shares [runWord] and keeps only its own
    // cleanliness bit, because its rules differ from the word machine's in one place: after an
    // accepted suggestion the next typed word may still be observed for pairs (a tapped word is a
    // valid context, and the cursor is known to sit right after it). Otherwise the rules are the
    // same: a fresh word inherits the bit, growth keeps it, a non-growth transition or a dirty
    // event clears it, and a completed boundary re-arms it.
    /** False as soon as anything but plain growth happens in the current run. */
    private var pairRunClean: Boolean = false

    /** Where clean pair completions and accepted pair predictions go. Default writes nothing. */
    var pairCompletionSink: PairCompletionSink = PairCompletionSink.NONE

    /**
     * Advances both machines from the existing hooks: no extra IPC, no extra editor call, and
     * nothing kept about the text beyond the current word.
     *
     * A run is CLEAN while the trailing word grows one piece at a time (`w.startsWith(previous) &&
     * w.length > previous.length`) and it ENDS when the trailing word becomes empty. A shortening
     * (backspace), a replacement, a selection change, a cursor gesture, an accepted suggestion, a
     * field or subtype change all mark it dirty, and a dirty run reports nothing. So does a fresh
     * word whose FIRST observation already carries more than one keystroke's worth of text (see
     * [MAX_FIRST_OBSERVATION_UNITS]): that is a paste or a replacement, and learning uses typing,
     * not clipboard contents.
     */
    fun trackCleanRun(word: String) {
        val previous = runWord
        if (word == previous) return
        if (word.isEmpty()) {
            reportCompletionIfClean(previous)
            reportPairCompletionIfClean(previous)
            runWord = ""
            runClean = true
            // A completed boundary is a position the pair machine trusts: whatever state the run
            // that just ended was in, the NEXT word grows from nothing under our eyes.
            pairRunClean = true
            runEmptyResultPrefixLength = NO_EMPTY_RESULT
            return
        }
        if (previous.isEmpty()) {
            // A fresh word begins; whether it stays clean is decided by what follows.
            runWord = word
            if (word.length > MAX_FIRST_OBSERVATION_UNITS) {
                // Privacy: one observation may carry at most one keystroke of new text (1-2
                // UTF-16 units; a surrogate pair is one key). More is a paste or a replacement,
                // so the run starts dirty on both machines and a pasted word is never learned.
                runClean = false
                pairRunClean = false
            }
            runEmptyResultPrefixLength = NO_EMPTY_RESULT
            return
        }
        if (word.startsWith(previous) && word.length > previous.length) {
            runWord = word
            return
        }
        // Anything else — backspace, a swipe-delete, a replacement — is not growth.
        runWord = word
        runClean = false
        pairRunClean = false
        runEmptyResultPrefixLength = NO_EMPTY_RESULT
    }

    /**
     * Records that nothing in the dictionary continues [prefix], so no longer word starting with
     * it is in the dictionary either. The shortest such prefix is kept: the rule needs a proper
     * prefix of the completed word, and the last empty result of a run is usually the whole word.
     */
    fun observeEmptyResult(prefix: String) {
        if (runClean && prefix.isNotEmpty()) {
            val length = prefix.length
            if (runEmptyResultPrefixLength == NO_EMPTY_RESULT ||
                length < runEmptyResultPrefixLength
            ) {
                runEmptyResultPrefixLength = length
            }
        }
    }

    /**
     * Reports [word] as cleanly completed, but only when the run also proved the word is NOT in the
     * shipped dictionary: some PROPER prefix of it, actually requested during this same run, came
     * back with an empty result. An empty result for p means no dictionary word other than p itself
     * begins with p, so a longer word starting with p cannot be in the dictionary either.
     *
     * If no such observation was made (requests were coalesced, the engine was not ready, the strip
     * was ineligible), nothing is reported: when in doubt, learn less.
     */
    private fun reportCompletionIfClean(word: String) {
        if (!runClean || word.isEmpty()) return
        val observed = runEmptyResultPrefixLength
        if (observed !in 1 until word.length) return
        completionSink.onCleanCompletion(word)
    }

    /**
     * Reports the pair (context word, [word]) as cleanly completed. [pairRunClean] follows
     * [runClean] plus the recovery a tap-commit earns ([trustPairBoundary]), but the word machine's
     * "unknown to the dictionary" filter does not apply: pairs of ordinary dictionary words are the
     * common case.
     *
     * The context is read from the live editor cache now: the text ends with the separator that
     * just completed [word], so the word before [word] is the context (typed or tapped). A word
     * that opens a field or follows a sentence boundary has no context and no pair.
     */
    private fun reportPairCompletionIfClean(word: String) {
        if (!pairRunClean || word.isEmpty()) return
        if (pairCompletionSink === PairCompletionSink.NONE) return
        val context = editor.cachedWordBeforeTrailingWord()
        if (context.isEmpty()) return
        pairCompletionSink.onCleanPairCompletion(context, word)
    }

    fun markRunDirty() {
        runWord = ""
        runClean = false
        pairRunClean = false
        runEmptyResultPrefixLength = NO_EMPTY_RESULT
    }

    /**
     * Marks the boundary after an accepted suggestion as trusted for pairs: the cursor is known to
     * sit right after the committed word and its auto-space, so the next cleanly typed word may
     * form a pair with it.
     */
    fun trustPairBoundary() {
        pairRunClean = true
    }

    /**
     * An accepted NEXT_WORD cell backed by a learned pair bumps that pair's usage counter (the
     * ranking is usage, then frequency). The sink decides whether the cell is a learned pair; a
     * sentence-start strip (empty context) is never a pair.
     */
    fun noteAcceptedPrediction(context: String, suggestion: String) {
        pairCompletionSink.onAcceptedPrediction(context, suggestion)
    }

    /**
     * An accepted PREFIX cell: the word sink decides whether the tapped word is a saved personal
     * word and bumps its usage counter in memory only; the file is written at [onInputFinished].
     */
    fun noteAcceptedSuggestion(suggestion: String) {
        completionSink.onAcceptedSuggestion(suggestion)
    }

    /**
     * The one boundary where the personal dictionary and learned pairs write what they have
     * accumulated (usage counters and pending hashes), once, and only if something changed.
     */
    fun onInputFinished() {
        completionSink.onInputFinished()
        pairCompletionSink.onInputFinished()
    }

    private companion object {
        /** No proper prefix of the current run has come back empty yet. */
        const val NO_EMPTY_RESULT = -1
        /**
         * The most text a fresh word's FIRST observation may carry and still be typing: two UTF-16
         * units, i.e. one keystroke even when the key produces a surrogate pair. More in a single
         * event is a paste or a replacement.
         */
        const val MAX_FIRST_OBSERVATION_UNITS = 2
    }
}
