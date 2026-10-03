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
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalBigramSource
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalCandidateSource
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedBigramTableCatalog
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedDictionaryCatalog
import rkr.simplekeyboard.inputmethod.latin.glide.GlideKeyGeometry
import rkr.simplekeyboard.inputmethod.latin.glide.GlidePath
import java.util.concurrent.TimeUnit

/**
 * Worker-thread result delivery from an [EngineHandle] to the controller.
 *
 * Invoked off the UI thread (on the engine's own worker thread). The receiver is responsible for
 * marshaling to the UI thread and for re-checking [EngineHandle.isCurrent] before applying, exactly
 * as the underlying engine's [ResultHandoff] contract requires. [token] is opaque; hand it straight
 * back to [EngineHandle.isCurrent].
 *
 * [kind] tells the controller which commit path and display rule apply (NEXT_WORD skips the
 * casing re-application that PREFIX needs).
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
     * NEXT_WORD variant of [request]: same engine, token and executor, a different kind. Default
     * null: a fake handle never predicts a next word.
     */
    fun requestNextWord(editorSessionId: Long, subtypeId: String, contextWordUtf8: ByteArray): Any? = null

    /**
     * GLIDE variant of [request]. The engine snapshots [path] before returning, so the caller's
     * buffer can stay the PointerTracker's live one. Returns a token, or null if rejected (the
     * default, so a fake handle never decodes a glide).
     */
    fun requestGlide(editorSessionId: Long, subtypeId: String, path: GlidePath): Any? = null

    /**
     * Pushes the live layout's key geometry to the glide decoder. Null disables glide decoding.
     * Default no-op; the real handle forwards it to the engine.
     */
    fun updateGlideGeometry(geometry: GlideKeyGeometry?) {}

    /**
     * Hands the published bigram catalog over without opening the table: the engine maps and
     * attaches it lazily on its worker at the first [requestNextWord] lookup, so a session that
     * never predicts a next word never pays the mapping. Default no-op: a fake handle has no table.
     */
    fun deferBigramAttach(catalog: PublishedBigramTableCatalog) {}

    /** True only if [token] identifies the newest still-active request on this handle. */
    fun isCurrent(token: Any): Boolean

    /** Idles the engine and invalidates any in-flight generation. */
    fun finishInput()

    /**
     * Pushes the current key-neighbor table used by typo recovery. Default no-op; the real handle
     * forwards it to the engine.
     */
    fun updateKeyNeighbors(table: KeyNeighborTable?) {}

    /**
     * The autocorrect verdict of the newest completed lookup, or null when nothing may be replaced.
     * Read on the UI thread when a word separator is pressed; no request or token is needed, since
     * the lookup behind the current strip produced it. Default null: never autocorrects.
     */
    fun autocorrectAdvice(): AutocorrectAdvice? = null

    /**
     * The normalized prefix of the newest completed lookup whose exact dictionary pass was empty,
     * or null. Read on the UI thread with the lookup's result; the reader compares it with its own
     * prefix. Default null: no exact pass is ever reported empty.
     */
    fun exactMissPrefix(): String? = null

    /**
     * Exact whole-word membership of [normalizedWord] in this engine's dictionary: the dictionary
     * half of the learned-pair context check. Safe from any thread (a cache-free read of the
     * read-only mapping). Default false, which leaves the personal half of the check to decide.
     */
    fun containsWord(normalizedWord: String): Boolean = false

    /**
     * Releases the glide word index while idle; the next decode rebuilds it. The real handle
     * posts the drop onto the engine's serialized worker; default no-op.
     */
    fun releaseGlideIndex() {}

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

    override fun requestGlide(editorSessionId: Long, subtypeId: String, path: GlidePath): Any? =
        engine.requestGlide(editorSessionId, subtypeId, path)

    override fun updateGlideGeometry(geometry: GlideKeyGeometry?) =
        engine.updateGlideGeometry(geometry)

    override fun deferBigramAttach(catalog: PublishedBigramTableCatalog) =
        engine.deferBigramAttach(catalog)

    override fun isCurrent(token: Any): Boolean =
        token is LookupToken && engine.isCurrent(token)

    override fun finishInput() = engine.finishInput()

    override fun updateKeyNeighbors(table: KeyNeighborTable?) = engine.updateKeyNeighbors(table)

    override fun autocorrectAdvice(): AutocorrectAdvice? = engine.autocorrectAdvice

    override fun exactMissPrefix(): String? = engine.exactMissPrefix

    override fun containsWord(normalizedWord: String): Boolean = engine.containsWord(normalizedWord)

    override fun releaseGlideIndex() = engine.releaseGlideIndex()

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
         * [suffixRules] carries the word-form addons: the same-stem boost table of the prefix pass
         * and the after-word forms of NEXT_WORD. The Tatar engine gets it; the Russian engine gets
         * null and has neither.
         *
         * [fuzzyEditPolicy]: the Tatar engine uses [FuzzyEditPolicy.TATAR]; null means
         * [FuzzyEditPolicy.DEFAULT] (the Russian engine).
         *
         * [fallbackWordsFactory] builds the top-frequency NEXT_WORD fallback from the engine's own
         * dictionary at startup, so each language has its own.
         *
         * [personalBigrams] supplies the user's learned word pairs for NEXT_WORD, per subtype and
         * gated live on the personal-dictionary setting; [PersonalBigramSource.EMPTY] disables them.
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
            personalBigrams: PersonalBigramSource = PersonalBigramSource.EMPTY,
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
                personalBigrams = personalBigrams,
            ) ?: return null
            return MappedEngineHandle(engine)
        }
    }
}
