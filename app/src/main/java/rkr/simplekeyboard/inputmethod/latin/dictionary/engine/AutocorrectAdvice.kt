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

/**
 * The autocorrect verdict (D3) for ONE typed word, produced by the very lookup that already fed the
 * suggestion band. There is no second request, no second token and no second edit-distance
 * mechanism: the class #1 (long-press partner) variant generator of E3 is the only source, exactly
 * as the D3 contract requires.
 *
 * Immutable and published through a `@Volatile` reference, because it is computed on the engine
 * worker and read on the UI thread at the moment a word separator is pressed. It carries the word it
 * was computed FOR, so a verdict that belongs to an older word can never be applied to a newer one —
 * the reader compares [typedWord] with the live normalized trailing word and refuses on any
 * mismatch (coalesced lookups, an engine that never answered, a field that changed underneath).
 *
 * NOT a Kotlin `data class`, and [toString] is deliberately mute: this object carries the user's
 * text, and a synthesised `toString` would print it at the first interpolation.
 */
class AutocorrectAdvice(
    /** The typed word, in the NFC lowercase form the lookup was made with. */
    val typedWord: String,
    /** The single class #1 dictionary word [typedWord] may be replaced with (NFC lowercase). */
    val replacement: String,
    /** Frequency of [replacement] in the shipped asset, for the threshold of [AutocorrectPolicy]. */
    val frequency: Long,
) {
    /** Deliberately says nothing: the user's word must never reach a log or exception message. */
    override fun toString(): String = "AutocorrectAdvice"
}

/**
 * The two numbers of D3, in ONE named place, read by both sides of the decision: the index (which
 * prunes work with them) and the controller (which re-checks them fail-closed before it edits text).
 *
 * Both were fixed by the owner on 2026-07-31, BEFORE a single line of phase code and BEFORE any
 * quality run, for the same reason the E5a threshold was: a gate decided after the result is not a
 * gate. They are not revised up or down after the first run.
 */
object AutocorrectPolicy {
    /**
     * Minimum length of the typed word, in code points of its NORMALIZED form. At three letters one
     * edit changes the word far too often, and Tatar has a great many short words; below four no
     * replacement is made at all.
     */
    const val MIN_WORD_CODE_POINTS: Int = 4

    /**
     * Minimum frequency of the candidate. NOT an assigned number: it is the frequency of the word at
     * rank 10 000 as MEASURED in the shipped `tatar_top100k_v1` artifact. Autocorrect changes text
     * the user has already typed, so the candidate has to be a word people actually write; for scale,
     * rank 20 000 in the same artifact is already frequency 153.
     *
     * The tie to the artifact is deliberate and binding: if the asset is ever rebuilt, this number is
     * re-measured the same way and written down again. Quoting "403" after the artifact changes is
     * forbidden by the contract. Re-measured 2026-09-20 (TT-SUGGESTIONS P2): rank 10 000 is
     * frequency 411 (`чиновниклар`), rank 20 000 is 153 (`хәмзин`) — on BOTH the 1.9.1 artifact and
     * the 110 000-entry P2 one (the P2 additions all sit at frequency <= 10, far below this rank;
     * the quoted 403/149 were the 1.8.4 measurements that the 1.9.x repacks had moved without the
     * re-measurement this contract requires).
     */
    const val MIN_CANDIDATE_FREQUENCY: Long = 411L
}
