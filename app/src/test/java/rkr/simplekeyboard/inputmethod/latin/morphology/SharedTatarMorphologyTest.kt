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

package rkr.simplekeyboard.inputmethod.latin.morphology

import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SharedTatarMorphologyTest {

    @Test
    fun failedLoadDoesNotPreventDictionaryUsersFromRetrying() {
        val acceptor = MorphologyTestSupport.realAcceptor()
        var calls = 0
        var fail = true
        val loader = SharedMorphologyLoader {
            calls++
            if (fail) throw IOException("load failure")
            acceptor
        }

        assertNull(loader.get())
        assertEquals(1, calls)
        fail = false
        assertSame(acceptor, loader.get())
        assertSame(acceptor, loader.get())
        assertEquals(2, calls)
    }

    @Test
    fun concurrentCallersShareOneSuccessfulLoad() {
        val acceptor = MorphologyTestSupport.realAcceptor()
        var calls = 0
        val loader = SharedMorphologyLoader {
            synchronized(this) { calls++ }
            acceptor
        }
        val executor = Executors.newFixedThreadPool(8)
        try {
            val answers = (0 until 32).map {
                executor.submit<TatarMorphologicalAcceptor?> { loader.get() }
            }
            answers.forEach { assertSame(acceptor, it.get(10, TimeUnit.SECONDS)) }
            assertEquals(1, calls)
        } finally {
            executor.shutdownNow()
        }
    }
}
