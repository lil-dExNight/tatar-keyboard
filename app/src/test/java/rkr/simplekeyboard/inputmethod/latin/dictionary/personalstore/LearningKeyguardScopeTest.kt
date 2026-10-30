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

import java.util.function.BooleanSupplier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The per-event keyguard answer of the learning predicate: kept inside a scope, never outside. */
class LearningKeyguardScopeTest {

    private class CountingRead(var locked: Boolean = false) : BooleanSupplier {
        var reads = 0

        override fun getAsBoolean(): Boolean {
            reads++
            return locked
        }
    }

    @Test
    fun outsideAScopeEveryCheckReadsLive() {
        val scope = LearningKeyguardScope()
        val read = CountingRead()

        assertFalse(scope.isLocked(read))
        read.locked = true
        assertTrue(scope.isLocked(read))
        assertEquals(2, read.reads)
    }

    @Test
    fun insideAScopeTheFirstAnswerIsReused() {
        val scope = LearningKeyguardScope()
        val read = CountingRead()

        scope.open()
        assertFalse(scope.isLocked(read))
        assertFalse(scope.isLocked(read))
        assertFalse(scope.isLocked(read))
        scope.close()

        assertEquals(1, read.reads)
    }

    @Test
    fun aLockedAnswerIsKeptForTheWholeScope() {
        val scope = LearningKeyguardScope()
        val read = CountingRead(locked = true)

        scope.open()
        assertTrue(scope.isLocked(read))
        read.locked = false
        assertTrue("one event never mixes two answers", scope.isLocked(read))
        scope.close()
    }

    @Test
    fun closingTheScopeDropsTheAnswer() {
        val scope = LearningKeyguardScope()
        val read = CountingRead()

        scope.open()
        assertFalse(scope.isLocked(read))
        scope.close()
        read.locked = true

        assertTrue("after the event the next check reads live again", scope.isLocked(read))
        scope.open()
        assertTrue("a new scope reads live again", scope.isLocked(read))
        scope.close()
        assertEquals(3, read.reads)
    }

    @Test
    fun nestedScopesShareOneAnswerUntilTheOutermostCloses() {
        val scope = LearningKeyguardScope()
        val read = CountingRead()

        scope.open()
        assertFalse(scope.isLocked(read))
        scope.open()
        assertFalse(scope.isLocked(read))
        scope.close()
        read.locked = true
        assertFalse("the outer scope is still open", scope.isLocked(read))
        scope.close()

        assertTrue(scope.isLocked(read))
        assertEquals(2, read.reads)
    }

    @Test
    fun anUnmatchedCloseDoesNotOpenAKeptState() {
        val scope = LearningKeyguardScope()
        val read = CountingRead()

        scope.close()
        scope.isLocked(read)
        scope.isLocked(read)

        assertEquals(2, read.reads)
    }
}
