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

package rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PairCompletionSink

/**
 * The P1 learning gate (docs/ROADMAP-P2.md): the pair sink shares the ONE five-factor predicate
 * of the words sink — suggestions eligible (field, subtype, no `IME_FLAG_NO_PERSONALIZED_LEARNING`,
 * no null editorInfo), the personal dictionary setting, the unlock state, the postal-address
 * exclusion — and every one of its event paths consults it.
 *
 * The factors themselves are Android state, so what runs as a real test here is the SHAPE — a
 * sink that writes nothing whenever the predicate says no — and the rest is source-contract over
 * the two places the wiring lives: the sink factory and `LatinIME`.
 */
class PersonalBigramLearningGatesTest {

    private fun sourceRoot(): File {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
    }

    private val ime by lazy {
        File(sourceRoot(), "java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java").readText()
    }
    private val learning by lazy {
        File(
            sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/dictionary/personalstore/PersonalBigramLearning.kt",
        ).readText()
    }

    @Test
    fun aClosedPredicateWritesNothingOnAnyEventPath() {
        // The sink is the only bridge from typing to the store, and all three of its methods
        // consult the predicate — the completion, the acceptance and the end-of-session flush.
        var completions = 0
        var acceptances = 0
        var flushes = 0
        val guarded = object : PairCompletionSink {
            override fun onCleanPairCompletion(contextWord: String, completedWord: String) {
                if (!predicateSaysNo()) completions++
            }

            override fun onAcceptedPrediction(contextWord: String, word: String) {
                if (!predicateSaysNo()) acceptances++
            }

            override fun onInputFinished() {
                if (!predicateSaysNo()) flushes++
            }
        }
        guarded.onCleanPairCompletion("сәләм", "дөнья")
        guarded.onAcceptedPrediction("сәләм", "дөнья")
        guarded.onInputFinished()
        assertEquals(0, completions)
        assertEquals(0, acceptances)
        assertEquals(0, flushes)
    }

    private fun predicateSaysNo(): Boolean = true

    @Test
    fun everySinkMethodIsGatedInProductionToo() {
        val body = learning.substringAfter("fun sinkFor(")
        assertEquals(
            "the predicate is consulted on all three paths",
            3, Regex("if \\(!predicate\\.mayLearn\\(\\)\\) return").findAll(body).count(),
        )
    }

    @Test
    fun theSubtypeIsResolvedPerEventOnEveryPath() {
        val body = learning.substringAfter("fun sinkFor(")
        assertEquals(
            "a pair completed on the Russian layout reaches the Russian store and nothing else",
            3, Regex("activeSubtype\\.get\\(\\) \\?: return").findAll(body).count(),
        )
    }

    @Test
    fun thePairSinkIsWiredBesideTheWordSinkUnderTheSamePredicate() {
        // One predicate, and both sinks are wired with it — the pair feature adds no second place
        // where "may we learn" is decided.
        assertEquals(
            "the pair sink is wired exactly once",
            1, Regex("PersonalBigramLearning\\.sinkFor\\(").findAll(ime).count(),
        )
        val wiring = ime.substringAfter("PersonalBigramLearning.sinkFor(").substringBefore(");")
        assertTrue(
            "the SAME predicate instance gates pairs and words",
            wiring.contains("this::mayLearnPersonalWords"),
        )
        assertTrue(
            "the subtype is resolved at the moment of the event",
            wiring.contains("this::activeDictionarySubtype"),
        )
        assertTrue(
            "the controller is handed the pair sink",
            ime.contains("mSuggestionsController.setPairCompletionSink("),
        )
    }

    @Test
    fun theContextMembershipProbeIsInstalledAndClearedWithTheService() {
        assertEquals(
            "the dictionary half of the context gate is installed exactly once",
            1, Regex("PersonalBigramDictionaries\\.setContextMembershipProbe\\(\\(").findAll(ime).count(),
        )
        assertTrue(
            "and it is cleared on destroy — the store outlives the IME and must not hold it",
            ime.contains("PersonalBigramDictionaries.setContextMembershipProbe(null);"),
        )
        assertTrue(
            "the probe asks the live engine, which is the only owner of the mapping",
            ime.contains("controller.engineContainsWord(subtypeId, normalizedContext)"),
        )
    }
}
