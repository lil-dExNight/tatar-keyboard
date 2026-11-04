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

package rkr.simplekeyboard.inputmethod.latin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The suggestion pipeline's threading topology, checked from source (these JVM tests have no
 * Android framework to run the threads with): SuggestionsController's public surface runs on the
 * UI thread with one lazily created single-thread worker behind it; each engine start builds one
 * single-thread executor (ExecutorServiceEngineExecutor.singleThread); every personal store is
 * serialized on the one daemon worker of PersonalDictionaries.sharedStoreExecutor. The
 * forbidden-API scan (`Thread(`, `Executors.`, `CoroutineScope`, `runBlocking`) over
 * suggestions/, engine/ and personalstore/ allows exactly those documented construction sites.
 * The scan is comment-inclusive: naming a forbidden API even in prose is pinned the same way.
 */
class ThreadingTopologySourceContractTest {

    private val controller by lazy { source("$SUGGESTIONS/SuggestionsController.kt") }
    private val surfaces by lazy { source("$SUGGESTIONS/SuggestionSurfaces.kt") }
    private val slot by lazy { source("$SUGGESTIONS/LanguageSlot.kt") }
    private val engine by lazy { source("$ENGINE/MappedDictionaryEngine.kt") }
    private val prefixEngine by lazy { source("$ENGINE/LatestOnlyPrefixEngine.kt") }
    private val personalDictionaries by lazy { source("$PERSONALSTORE/PersonalDictionaries.kt") }

    // --- SuggestionsController -------------------------------------------------------------------

    @Test
    fun theControllerKeepsItsPublicSurfaceOnTheUiThread() {
        assertTrue(
            "the class doc carries the single-thread ownership marker",
            controller.contains("Every public method runs on the UI thread."),
        )
        assertTrue(
            controller.contains(
                "/** Suggestion-strip UI seam. All methods are called on the UI thread. */",
            ),
        )
        assertTrue(
            "the re-marshaling seam is defined as a post onto the UI thread",
            surfaces.contains("Marshals a [Runnable] onto the UI thread."),
        )
        assertTrue(
            "the one structure shared with the worker is concurrent",
            controller.contains("private val slots = ConcurrentHashMap<String, LanguageSlot>()"),
        )
        assertTrue(slot.contains("@Volatile"))
        assertTrue(
            "the engine handoff across threads is a @Volatile slot field",
            slot.contains("var engine: EngineHandle?"),
        )
    }

    @Test
    fun theControllerOwnsOneLazilyCreatedWorker() {
        assertEquals(
            "the controller creates its worker through the one injected factory default",
            1,
            controller.split("newSingleThreadExecutor").size - 1,
        )
        assertTrue(
            "the production default factory is a plain single-thread executor",
            controller.contains("{ Executors.newSingleThreadExecutor() }"),
        )
        assertTrue(
            "the test constructors inject their own executor through the same factory seam",
            controller.contains("{ backgroundExecutor },"),
        )
        assertEquals(
            "one executor field, never a second worker",
            1,
            controller.split("private var executor: ExecutorService? = null").size - 1,
        )
        val accessor = body(controller, "private fun backgroundExecutor()")
        assertTrue("no worker is created after onDestroy", accessor.contains("if (destroyed) return null"))
        assertTrue("an existing worker is reused", accessor.contains("executor?.let { return it }"))
        assertTrue(accessor.contains("executor = created"))
        assertEquals(
            "the factory is invoked inside the accessor and nowhere else",
            1,
            controller.split("executorFactory()").size - 1,
        )
        val destroy = body(controller, "fun onDestroy()")
        val shutdown = destroy.indexOf("executor?.shutdownNow()")
        assertTrue("onDestroy shuts the worker down", shutdown >= 0)
        assertTrue(
            "the field is cleared after the shutdown",
            destroy.indexOf("executor = null") > shutdown,
        )
    }

    @Test
    fun engineStartRunsOnTheWorkerAndPublishesThroughTheUiPoster() {
        val start = body(controller, "private fun maybeStartEngine()")
        val dispatch = start.indexOf("backgroundExecutor.execute {")
        assertTrue("the engine start is dispatched to the worker", dispatch >= 0)
        assertTrue(
            "the factory itself runs on the worker (it maps the dictionary)",
            start.indexOf("factory(slot.subtypeId, callback)") > dispatch,
        )
        assertTrue(
            "the started engine is re-marshaled before any state change",
            start.contains("uiPoster.post { publishEngine(slot, handle) }"),
        )
        assertTrue(
            "lookup results are re-marshaled before any state change",
            start.contains("uiPoster.post { applyResult(slot, token, suggestions, kind) }"),
        )
    }

    // --- Engine ----------------------------------------------------------------------------------

    @Test
    fun eachEngineStartBuildsOneSingleThreadWorker() {
        assertEquals(
            "the engine package has exactly one executor construction",
            1,
            engine.split("newSingleThreadExecutor").size - 1,
        )
        assertTrue(
            "it is the singleThread factory",
            engine.contains("ExecutorServiceEngineExecutor(Executors.newSingleThreadExecutor())"),
        )
        assertTrue(
            "and the start default reaches it",
            engine.contains("ExecutorServiceEngineExecutor::singleThread"),
        )
        val startup = body(engine, "private fun startOwnedLease(")
        assertTrue(startup.contains("val executor = executorFactory()"))
        assertEquals(
            "one executor per engine start",
            1,
            engine.split("val executor = executorFactory()").size - 1,
        )
        assertTrue(
            "a failed start shuts the executor it created",
            startup.contains("createdExecutor?.shutdown()"),
        )
        assertTrue(
            "the engine receives its executor; it never builds one",
            prefixEngine.contains("private val executor: EngineExecutor,"),
        )
    }

    // --- Personal stores -------------------------------------------------------------------------

    @Test
    fun thePersonalStoresShareOneNamedDaemonWorker() {
        var constructions = 0
        File(javaRoot(), PERSONALSTORE).walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .forEach { constructions += it.readText().split("newSingleThreadExecutor").size - 1 }
        assertEquals(
            "the whole personalstore package holds exactly one executor construction",
            1,
            constructions,
        )
        val locked = personalDictionaries
            .substringAfter("private fun executorLocked()")
            .substringBefore("internal fun sharedStoreExecutor")
        assertTrue(locked.contains("Executors.newSingleThreadExecutor { runnable ->"))
        assertTrue(
            "the worker thread is a named daemon",
            locked.contains("Thread(runnable, \"personal-dictionary\").apply { isDaemon = true }"),
        )
        assertTrue("created once and cached", locked.contains(".also { sharedExecutor = it }"))
        assertTrue(
            "the shared accessor is the sole route to the worker",
            personalDictionaries.contains(
                "internal fun sharedStoreExecutor(): ExecutorService" +
                    " = synchronized(lock) { executorLocked() }",
            ),
        )
        val storeFor = personalDictionaries
            .substringAfter("internal fun storeFor(")
            .substringBefore("@JvmStatic")
        assertTrue("the store map is mutated under the lock", storeFor.contains("synchronized(lock) {"))
        assertTrue(
            "a store is created on first use, on the shared worker",
            storeFor.contains("stores.getOrPut(subtypeId)") && storeFor.contains("executorLocked()"),
        )
        val reset = body(personalDictionaries, "internal fun resetForTest()")
        assertTrue("the test hook shuts the worker down", reset.contains("sharedExecutor?.shutdown()"))
        for (provider in listOf(
            "PersonalBigramDictionaries.kt",
            "PersonalEmojiDictionaries.kt",
            "PersonalErasure.kt",
            "RefusedCorrectionStores.kt",
            "SentStartOverlays.kt",
            "TextShortcutStores.kt",
        )) {
            assertTrue(
                "$provider must be serialized on the shared worker",
                source("$PERSONALSTORE/$provider").contains("PersonalDictionaries.sharedStoreExecutor()"),
            )
        }
    }

    // --- Forbidden-API scan ----------------------------------------------------------------------

    @Test
    fun noConcurrencyPrimitiveLivesOutsideTheDocumentedConstructionSites() {
        val seen = HashSet<String>()
        val violations = ArrayList<String>()
        for (dir in SCAN_DIRS) {
            val root = File(javaRoot(), dir)
            assertTrue("scan dir not found: ${root.absolutePath}", root.isDirectory)
            root.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .sortedBy { it.absolutePath }
                .forEach { file ->
                    val path = file.relativeTo(javaRoot()).invariantSeparatorsPath
                    seen.add(path)
                    val text = file.readText()
                    for ((token, regex) in FORBIDDEN_APIS) {
                        val actual = regex.findAll(text).count()
                        val expected = ALLOWED_SITES[path]?.get(token) ?: 0
                        if (actual != expected) {
                            violations.add("$path: '$token' x$actual, documented x$expected")
                        }
                    }
                }
        }
        val missing = ALLOWED_SITES.keys - seen
        assertTrue("a documented construction site is gone or renamed: $missing", missing.isEmpty())
        assertTrue(
            "concurrency primitives outside the documented construction sites" +
                " (see the class KDoc):\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    // --- machinery -------------------------------------------------------------------------------

    /** The body of the method whose declaration contains [signature], by brace matching. */
    private fun body(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature is missing", start >= 0)
        val open = source.indexOf('{', start)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, index + 1)
                }
            }
        }
        error("unbalanced braces after $signature")
    }

    private fun javaRoot(): File = File(sourceRoot(), "java")

    private fun source(relative: String): String = File(javaRoot(), relative).readText()

    private fun sourceRoot(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")

    private companion object {
        const val ROOT = "rkr/simplekeyboard/inputmethod/latin"
        const val SUGGESTIONS = "$ROOT/suggestions"
        const val ENGINE = "$ROOT/dictionary/engine"
        const val PERSONALSTORE = "$ROOT/dictionary/personalstore"

        val SCAN_DIRS = listOf(SUGGESTIONS, ENGINE, PERSONALSTORE)

        // `Thread(` is boundary-aware: Thread.currentThread() is interrupt bookkeeping, not a
        // construction, and HandlerThread( must not match inside the word.
        val FORBIDDEN_APIS = linkedMapOf(
            "Executors." to Regex("""Executors\."""),
            "CoroutineScope" to Regex("""CoroutineScope"""),
            "runBlocking" to Regex("""runBlocking"""),
            "Thread(" to Regex("""(?<![A-Za-z0-9_])Thread\s*\("""),
        )

        // The documented construction sites: file from the java root -> token -> pinned count.
        // A new worker is a reviewed act: justify it here in the same change, or the scan fails.
        val ALLOWED_SITES = mapOf(
            // The production default of the controller's injected executorFactory.
            "$SUGGESTIONS/SuggestionsController.kt" to mapOf("Executors." to 1),
            // ExecutorServiceEngineExecutor.singleThread: one worker per engine start.
            "$ENGINE/MappedDictionaryEngine.kt" to mapOf("Executors." to 1),
            // The personal stores' one shared daemon worker, thread factory included.
            "$PERSONALSTORE/PersonalDictionaries.kt" to mapOf("Executors." to 1, "Thread(" to 1),
        )
    }
}
