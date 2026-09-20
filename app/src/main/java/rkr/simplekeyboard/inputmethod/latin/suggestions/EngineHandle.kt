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
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.FallbackWordsFactory
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.FuzzyEditPolicy
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.LookupKind
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.LookupToken
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.KeyNeighborTable
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.MappedDictionaryEngine
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.ResultHandoff
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalCandidateSource
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedBigramTableCatalog
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedDictionaryCatalog
import java.util.concurrent.TimeUnit

/**
 * Worker-thread result delivery from an [EngineHandle] to the controller.
 *
 * Invoked off the UI thread (on the engine's own worker thread). The receiver is responsible for
 * marshaling to the UI thread and for re-checking [EngineHandle.isCurrent] before applying, exactly
 * as the underlying engine's [ResultHandoff] contract requires. [token] is opaque; hand it straight
 * back to [EngineHandle.isCurrent].
 *
 * [kind] is E5d's addition: the owner of state (the controller) must know which kind of result it
 * holds to choose the right commit path and the right display rule (NEXT_WORD skips the casing
 * re-application PREFIX needs) — PROPOSALS.md, "E5c. Вид запроса" / "Контракт текста" amendment.
 */
fun interface ResultCallback {
    fun onResult(token: Any, suggestions: List<String>, kind: LookupKind)
}

/**
 * Thin, unit-testable abstraction over the dictionary engine. Keeps [SuggestionsController]
 * independent of the mmap/engine machinery so it can be driven with fakes in plain JVM tests.
 *
 * Tokens are opaque [Any] values produced by [request] and only ever interpreted by the same
 * handle via [isCurrent].
 */
interface EngineHandle {
    /** Enqueues a lookup. Returns an opaque token, or null if the request was rejected. */
    fun request(editorSessionId: Long, subtypeId: String, prefixUtf8: ByteArray): Any?

    /**
     * E5c NEXT_WORD sibling of [request] — same underlying engine, token and executor, a
     * different kind (PROPOSALS.md, "E5c. Вид запроса"). Default null so a fake handle written
     * before E5c keeps compiling and simply never predicts a next word.
     */
    fun requestNextWord(editorSessionId: Long, subtypeId: String, contextWordUtf8: ByteArray): Any? = null

    /**
     * E5c two-stage readiness: wires a bigram source into an ALREADY-published handle. Call off
     * the UI thread — this performs mmap I/O. Returns false (and leaves [requestNextWord]
     * answering empty) if the table is unavailable or invalid; default false so a fake handle
     * written before E5c keeps compiling.
     */
    fun attachBigramSource(catalog: PublishedBigramTableCatalog): Boolean = false

    /** True only if [token] identifies the newest still-active request on this handle. */
    fun isCurrent(token: Any): Boolean

    /** Idles the engine and invalidates any in-flight generation. */
    fun finishInput()

    /**
     * Pushes the current key-neighbor table used by the fuzzy suggestion pass. Default no-op so
     * fakes that predate fuzzy suggestions keep compiling; the real handle forwards it to the
     * engine.
     */
    fun updateKeyNeighbors(table: KeyNeighborTable?) {}

    /**
     * The D3 autocorrect verdict of the newest completed lookup, or null when nothing may be
     * replaced. Read on the UI thread at the moment a word separator is pressed; there is no request
     * and no token, because the verdict was produced by the lookup the band already paid for.
     *
     * Default null so a handle that predates D3 keeps compiling and simply never autocorrects.
     */
    fun autocorrectAdvice(): AutocorrectAdvice? = null

    /** Bounded teardown; returns true if the engine fully released within [timeoutMs]. */
    fun destroy(timeoutMs: Long): Boolean
}

/**
 * Real [EngineHandle] backed by [MappedDictionaryEngine].
 *
 * Construction performs file mapping I/O, so [start] MUST be called off the UI thread (the
 * controller submits it on its background executor). Results arrive on the engine's worker thread
 * and are forwarded verbatim to [callback], which is responsible for UI marshaling.
 */
class MappedEngineHandle private constructor(
    private val engine: MappedDictionaryEngine,
) : EngineHandle {

    override fun request(editorSessionId: Long, subtypeId: String, prefixUtf8: ByteArray): Any? =
        engine.request(editorSessionId, subtypeId, prefixUtf8)

    override fun requestNextWord(editorSessionId: Long, subtypeId: String, contextWordUtf8: ByteArray): Any? =
        engine.requestNextWord(editorSessionId, subtypeId, contextWordUtf8)

    override fun attachBigramSource(catalog: PublishedBigramTableCatalog): Boolean =
        engine.attachBigramSource(catalog)

    override fun isCurrent(token: Any): Boolean =
        token is LookupToken && engine.isCurrent(token)

    override fun finishInput() = engine.finishInput()

    override fun updateKeyNeighbors(table: KeyNeighborTable?) = engine.updateKeyNeighbors(table)

    override fun autocorrectAdvice(): AutocorrectAdvice? = engine.autocorrectAdvice

    override fun destroy(timeoutMs: Long): Boolean =
        engine.destroy(timeoutMs, TimeUnit.MILLISECONDS)

    companion object {
        /**
         * Acquires a catalog lease and maps the newest dictionary. Performs catalog validation and
         * file mapping I/O, so this MUST be called off the UI thread; the controller invokes it on
         * its background executor only after the dictionary is ready. Returns null if no dictionary
         * is safe to activate.
         *
         * The [catalog] is the one the controller already owns
         * ([SuggestionsController.engineCatalog]): the engine neither builds a second store nor
         * spawns a throwaway executor of its own.
         *
         * [suffixRules] is the P3 word-form wiring (docs/TT-SUGGESTIONS.md): the one object carries
         * both engine addons — the same-stem boost table of the prefix pass and the after-word
         * forms of the NEXT_WORD slot. The Tatar engine is started with it, the Russian engine with
         * null, and neither behavior exists without it.
         *
         * [fuzzyEditPolicy] is the TT-TYPO-NEXT wiring (docs/TT-TYPO-NEXT.md): the Tatar engine
         * ships [FuzzyEditPolicy.TATAR] — class #1 (always) plus class #4 (probe-first full
         * single substitution, gated on an empty exact pass at >= 4 code points) with the
         * same-length bonus, the configuration the corrected C2 gates measured and passed
         * (2026-09-20). Engines started with null run [FuzzyEditPolicy.DEFAULT], bit-identical
         * to the pre-Phase-B behavior — the Russian engine included.
         *
         * [fallbackWordsFactory] is the TT-NEXTWORD-FILL wiring (docs/TT-NEXTWORD-FILL.md): both
         * shipped languages get the factory — it builds the top-frequency pool from the engine's
         * own dictionary at startup, so each language's NEXT_WORD fallback is its own.
         */
        @JvmStatic
        @JvmOverloads
        fun start(
            catalog: PublishedDictionaryCatalog,
            callback: ResultCallback,
            personalCandidates: PersonalCandidateSource = PersonalCandidateSource.EMPTY,
            suffixRules: TatarSuffixRules? = null,
            fuzzyEditPolicy: FuzzyEditPolicy? = null,
            fallbackWordsFactory: FallbackWordsFactory? = null,
        ): MappedEngineHandle? {
            val handoff = ResultHandoff { result ->
                callback.onResult(result.token, result.suggestions, result.kind)
            }
            val engine = MappedDictionaryEngine.start(
                catalog, handoff,
                personalCandidates = personalCandidates,
                suffixTable = suffixRules,
                afterWordFormsFactory = suffixRules,
                fuzzyEditPolicy = fuzzyEditPolicy,
                fallbackWordsFactory = fallbackWordsFactory,
            ) ?: return null
            return MappedEngineHandle(engine)
        }
    }
}
