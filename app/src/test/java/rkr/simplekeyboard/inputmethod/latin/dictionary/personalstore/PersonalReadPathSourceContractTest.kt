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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hot read paths of the personal sources, checked from source (these JVM tests have no
 * Android framework to build the process-wide owners on). The sentence-start strip, the refused
 * corrections and the learned emoji no longer enqueue a prime on every query: each read asks the
 * store's `ensureOpen`, which returns at once once loaded, queues one open per load, coalesces
 * the queries that arrive while it runs, retries a failed open on the next query, and keeps the
 * file I/O on the shared worker. The refused-corrections and learned-emoji owners cache one
 * source object per subtype with the live gate inside it, and the backup prime keeps its outcome
 * callback through the worker.
 */
class PersonalReadPathSourceContractTest {

    @Test
    fun theHotReadPathsCoalesceOpensInsteadOfPrimingPerQuery() {
        val strip = bodyOf(sentStartOwner, "fun topWordsFor(")
        assertTrue("the strip's read side queues one coalesced open",
            strip.contains("store.ensureOpen()"))
        assertFalse("and never primes per query", strip.contains("store.prime()"))
        for (owner in listOf(refusedOwner, emojiOwner)) {
            val body = bodyOf(owner, "fun sourceFor(")
            assertTrue("the gated read side queues one coalesced open",
                body.contains("if (gate.isOn()) store.ensureOpen()"))
            assertFalse("and never primes per query", body.contains("store.prime()"))
        }
        for (store in STORES) {
            val text = personalStoreFile(store)
            val body = bodyOf(text, "fun ensureOpen()")
            assertTrue("$store short-circuits on the published load state",
                body.contains("if (loaded) return"))
            assertTrue("$store coalesces concurrent opens through one atomic slot",
                body.contains("openQueued.compareAndSet(false, true)"))
            assertTrue("$store queues the open on the worker", body.contains("onWorker {"))
            assertTrue("$store releases the slot even when the open throws",
                body.contains("finally") && body.contains("openQueued.set(false)"))
            assertTrue("$store loads volatile, so a finished open publishes to the queries",
                Regex("""@Volatile\s*\n\s*private var loaded""").containsMatchIn(text))
        }
    }

    @Test
    fun theFailedOpenRetryIsKept() {
        for (store in STORES) {
            val body = bodyOf(personalStoreFile(store), "fun ensureOpen()")
            assertTrue("$store retries a failed open: the queued open clears a stale failure",
                body.contains("loadFailed = false"))
        }
    }

    @Test
    fun theReadSourcesAreCachedPerSubtypeWithTheirLiveGateInside() {
        assertTrue("one refused-corrections source per language",
            refusedOwner.contains("private val sources = HashMap<String, RefusedCorrectionSource>()"))
        assertTrue(refusedOwner.contains("sources.getOrPut(subtypeId)"))
        assertEquals("one source object is built, never one per query",
            1, Regex("""RefusedCorrectionSource \{ typedWord""").findAll(refusedOwner).count())
        assertTrue("the cached gate is checked live on every lookup",
            bodyOf(refusedOwner, "sources.getOrPut(subtypeId)").contains("gate.isOn()"))

        assertTrue("one learned-emoji source per language",
            emojiOwner.contains("private val sources = HashMap<String, PersonalEmojiSource>()"))
        assertTrue(emojiOwner.contains("sources.getOrPut(subtypeId)"))
        assertEquals("one source object is built, never one per query",
            1, Regex("""SnapshotPersonalEmojiSource \{""").findAll(emojiOwner).count())
        assertTrue("the cached gate is checked live on every lookup",
            bodyOf(emojiOwner, "sources.getOrPut(subtypeId)").contains("gate.isOn()"))

        for (owner in listOf(refusedOwner, emojiOwner)) {
            val reset = bodyOf(owner, "internal fun resetForTest()")
            assertTrue("the test hook drops the cached sources too",
                reset.contains("sources.clear()"))
        }
    }

    @Test
    fun theBackupPrimeKeepsItsOutcomeThroughTheWorker() {
        for (store in listOf("RefusedCorrectionStore.kt", "PersonalEmojiStore.kt")) {
            val text = personalStoreFile(store)
            val body = bodyOf(text, "fun prime(outcome: PersonalMutationOutcome? = null)")
            assertTrue("$store's prime still answers through the worker",
                body.contains("onWorker {"))
            assertTrue("$store's prime still answers with the open result",
                body.contains("report(outcome, open())"))
            assertTrue("$store's prime still clears a stale failure before it opens",
                body.contains("loadFailed = false"))
        }
        // The strip's forced-open entry is untouched: tests drive the serialized owner through it.
        assertTrue(personalStoreFile("SentStartOverlayStore.kt")
            .contains("fun prime() = onWorker {"))
    }

    private fun sourceRoot(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")

    private fun javaRoot(): File = File(sourceRoot(), "java")

    private fun source(relative: String): String = File(javaRoot(), relative).readText()

    private fun personalStoreFile(name: String): String = source("$PERSONALSTORE/$name")

    private val sentStartOwner by lazy {
        personalStoreFile("SentStartOverlays.kt")
    }
    private val refusedOwner by lazy {
        personalStoreFile("RefusedCorrectionStores.kt")
    }
    private val emojiOwner by lazy {
        personalStoreFile("PersonalEmojiDictionaries.kt")
    }

    /**
     * The source from the declaration containing [signature] through its closing brace, by brace
     * matching — so assertions can pin the declaration shape too (e.g. `fun prime(...) =
     * onWorker {`).
     */
    private fun bodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature not found", start >= 0)
        val open = source.indexOf('{', start)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("unbalanced braces after $signature")
    }

    private companion object {
        const val PERSONALSTORE = "rkr/simplekeyboard/inputmethod/latin/dictionary/personalstore"

        val STORES = listOf(
            "SentStartOverlayStore.kt",
            "RefusedCorrectionStore.kt",
            "PersonalEmojiStore.kt",
        )
    }
}
