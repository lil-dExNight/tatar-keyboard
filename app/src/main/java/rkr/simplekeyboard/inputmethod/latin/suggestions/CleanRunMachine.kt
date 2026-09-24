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
 * The clean-run machines of [SuggestionsController]: the E4c completed-word machine and the P1
 * (Phase 2, docs/ROADMAP-P2.md) completed-pair machine, which share one run of text. Pure move
 * from `SuggestionsController.kt` (ROADMAP Phase 6, T2), state and transitions verbatim; the only
 * additions are the tiny entry points the controller already performed inline
 * ([observeEmptyResult], [onInputFinished], [trustPairBoundary], [noteAcceptedPrediction]).
 *
 * Nothing here is persisted and nothing leaves this object except the completions handed to the
 * two sinks; with the default sinks those are no-ops.
 */
internal class CleanRunMachine(private val editor: EditorSurface) {

    // --- E4c clean-run state. Nothing here is persisted and nothing leaves this object except one
    // completed word handed to [completionSink]; with the default sink that is a no-op.
    /** The trailing word as last seen. Empty between words. */
    private var runWord: String = ""
    /** False as soon as anything but plain growth happens; a dirty run reports nothing. */
    private var runClean: Boolean = true
    /** Length of the longest proper prefix of the current run that came back with NO candidates. */
    private var runEmptyResultPrefixLength: Int = NO_EMPTY_RESULT

    /** Where clean completions go (E4c). Default writes nothing at all. */
    var completionSink: WordCompletionSink = WordCompletionSink.NONE

    // --- P1 pair-run state (docs/ROADMAP-P2.md). The pair machine shares [runWord] — the text of
    // the run is one — and carries only its own cleanliness bit, because its rules differ from the
    // words machine's in exactly one place: after an ACCEPTED suggestion the next typed word may
    // still be observed for pairs (the tapped word is a legitimate CONTEXT — "typed or tapped" —
    // and the cursor sits provably right after it), while for the words machine that word stays
    // sacrificed. Everything else is the same machine: a fresh word inherits the bit, growth keeps
    // it, a non-growth transition or a dirty event clears it, and a completed boundary re-arms it.
    /** False as soon as anything but plain growth happens in the current run. */
    private var pairRunClean: Boolean = false

    /** Where clean PAIR completions and pair-prediction acceptances go (P1). Default writes nothing. */
    var pairCompletionSink: PairCompletionSink = PairCompletionSink.NONE

    /**
     * The clean-run machine of E4c, computed from the hooks that already exist — no new IPC, no new
     * editor call and nothing kept about the text beyond the current word.
     *
     * A run is CLEAN while the trailing word grows one piece at a time (`w.startsWith(previous) &&
     * w.length > previous.length`) and it ENDS when the trailing word becomes empty. A shortening
     * (backspace), a replacement, a selection change, a cursor gesture, an accepted suggestion, a
     * field or subtype change all mark it dirty, and a dirty run reports nothing.
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
     * The observation the E4c filter is built on: nothing in the dictionary continues this
     * prefix, so no longer word starting with it can be in the dictionary either.
     *
     * The SHORTEST such prefix is remembered, not the longest. The contract asks for a
     * PROPER prefix of the completed word, and the last empty result of a run is usually the
     * whole word itself — keeping the longest would let that one overwrite the very evidence
     * the rule is about, and nothing would ever be learned.
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
     * If no such observation was made — coalescing collapsed the requests, the engine was not ready,
     * the band was ineligible — nothing is reported. Fail-closed towards writing LESS.
     */
    private fun reportCompletionIfClean(word: String) {
        if (!runClean || word.isEmpty()) return
        val observed = runEmptyResultPrefixLength
        if (observed !in 1 until word.length) return
        completionSink.onCleanCompletion(word)
    }

    /**
     * Reports the pair (context word, [word]) as cleanly completed (P1 of Phase 2,
     * docs/ROADMAP-P2.md). The run rules are the words machine's own — [pairRunClean] mirrors
     * [runClean] transition for transition, plus the one recovery a tap-commit earns (see
     * [trustPairBoundary]) — but NOT the words machine's "unknown to the dictionary" filter: a pair
     * whose second half is an ordinary dictionary word is the common case this feature exists for,
     * so the empty-result evidence is not consulted here at all.
     *
     * The context is read from the LIVE editor cache at this exact moment — the text ends with
     * the separator that just completed [word], so the word before it is [word] itself and the
     * word before that is the context, typed or tapped, exactly as the contract asks. No context,
     * no report: a word that opens a field or follows a sentence boundary has no pair to learn.
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
     * P1: the boundary an accepted suggestion establishes — the cursor sits provably right after
     * the committed word and its auto-space — is one the pair machine trusts: the NEXT word the
     * user types out cleanly may form a pair with it ("typed or tapped" context,
     * docs/ROADMAP-P2.md).
     */
    fun trustPairBoundary() {
        pairRunClean = true
    }

    /**
     * P1: an accepted NEXT_WORD cell backed by a learned pair bumps that pair's usage counter (the
     * other half of the pinned usage-then-frequency ranking). The sink decides whether the cell IS
     * a learned pair — a static successor, a word form or a fallback word changes nothing — and a
     * sentence-start band (empty context) is never a pair at all.
     */
    fun noteAcceptedPrediction(context: String, suggestion: String) {
        pairCompletionSink.onAcceptedPrediction(context, suggestion)
    }

    /**
     * An accepted PREFIX cell (2026-09-24 audit, finding 2): the word sink decides whether the
     * tapped word is a saved personal word and bumps its usage counter in memory only — a
     * dictionary word or an unknown one changes nothing, and the file is never rewritten here
     * (the flush boundary is [onInputFinished]).
     */
    fun noteAcceptedSuggestion(suggestion: String) {
        completionSink.onAcceptedSuggestion(suggestion)
    }

    /**
     * The one boundary where the personal stores write what they have accumulated: usage counters
     * and pending hashes, once, and only if something changed. The pair store (P1) flushes at the
     * same boundary and under the same rule.
     */
    fun onInputFinished() {
        completionSink.onInputFinished()
        pairCompletionSink.onInputFinished()
    }

    private companion object {
        /** No proper prefix of the current run has come back empty yet. */
        const val NO_EMPTY_RESULT = -1
    }
}
