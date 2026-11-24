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

import android.view.textservice.SuggestionsInfo
import java.nio.ByteBuffer
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.ChatSubstitutionTable
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.DictionaryIdentity
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.FuzzyEditPolicy
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.TatarLayoutTestFixtures
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.TdictPrefixIndex
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryTestFixtures
import rkr.simplekeyboard.inputmethod.latin.morphology.MorphologyTestSupport
import rkr.simplekeyboard.inputmethod.latin.morphology.TatarMorphologicalAcceptor
import rkr.simplekeyboard.inputmethod.latin.suggestions.ProperNameIndex
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarChatSpellingRules

/**
 * Class-#5 recovery through the correction seam. The dictionary holds only the stem, so the
 * corrected word form exists solely through the acceptor the seam publishes to the index.
 */
class SpellCheckerChatRecoveryTest {

    @Test
    fun chatSpellingOffersAnAcceptorOnlyCorrection() {
        val dictionaries = dictionaries(MorphologyTestSupport.realAcceptor())
        assertTrue(dictionaries.tatarBaseWord("бәйрәмне"))

        val checked = SpellCheckerEngine({ true }, { dictionaries })
            .checkSentence("бәйрәм бэйрэмне", 4)

        val answer = checked.answers[1]
        assertTrue(answer.attributes and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO != 0)
        assertTrue("suggestions=${answer.suggestions}", answer.suggestions.contains("бәйрәмне"))
    }

    private fun dictionaries(acceptor: TatarMorphologicalAcceptor): SpellCheckerDictionaries =
        SpellCheckerDictionaries(
            tatar = index(
                listOf("бәйрәм" to 100L),
                FuzzyEditPolicy.TATAR,
                TatarChatSpellingRules,
            ),
            russian = index(
                listOf("привет" to 100L),
                FuzzyEditPolicy.RUSSIAN,
                chatSubstitutions = null,
            ),
            acceptor = { acceptor },
            properNames = ProperNameIndex.EMPTY,
            personal = PersonalDictionarySource { PersonalDictionary.EMPTY },
            dictionaryLeases = emptyList(),
        )

    private fun index(
        entries: List<Pair<String, Long>>,
        policy: FuzzyEditPolicy,
        chatSubstitutions: ChatSubstitutionTable?,
    ): TdictPrefixIndex {
        val raw = DictionaryTestFixtures.raw(entries)
        val identity = DictionaryIdentity(1, 2, 1, "a".repeat(64))
        return requireNotNull(
            TdictPrefixIndex.open(
                ByteBuffer.wrap(raw),
                identity,
                entries.size.toLong(),
                raw.size.toLong(),
                fuzzyEditPolicy = policy,
                autocorrectMinCandidateFrequency = 0L,
                chatSubstitutions = chatSubstitutions,
            ),
        ).also { it.updateKeyNeighbors(TatarLayoutTestFixtures.tatarNeighborTable()) }
    }
}
