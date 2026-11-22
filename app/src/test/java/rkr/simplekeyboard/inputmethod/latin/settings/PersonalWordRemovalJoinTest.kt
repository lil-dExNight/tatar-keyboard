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

package rkr.simplekeyboard.inputmethod.latin.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalWordRemovalJoinTest {
    @Test
    fun sentenceCallbackMayCompleteBeforeTheDictionaryCallback() {
        val answers = ArrayList<Boolean>()
        val join = PersonalWordRemovalJoin { answers.add(it) }

        join.sentenceFinished(true)
        assertTrue("the first store cannot answer for both", answers.isEmpty())
        join.dictionaryFinished(true)

        assertEquals(listOf(true), answers)
    }

    @Test
    fun eitherStoreFailureFailsTheJoinInEitherOrder() {
        val answers = ArrayList<Boolean>()
        val join = PersonalWordRemovalJoin { answers.add(it) }

        join.sentenceFinished(false)
        join.dictionaryFinished(true)

        assertEquals(listOf(false), answers)
    }

    @Test
    fun duplicateCallbackCannotStandInForTheMissingStore() {
        val answers = ArrayList<Boolean>()
        val join = PersonalWordRemovalJoin { answers.add(it) }

        join.sentenceFinished(true)
        join.sentenceFinished(true)
        join.dictionaryFinished(true)

        assertEquals("a duplicate is one failure, never an ambiguous success", listOf(false), answers)
    }

    @Test
    fun missingCallbackNeverReportsSuccess() {
        val answers = ArrayList<Boolean>()
        val join = PersonalWordRemovalJoin { answers.add(it) }

        join.dictionaryFinished(true)

        assertTrue(answers.isEmpty())
    }
}
