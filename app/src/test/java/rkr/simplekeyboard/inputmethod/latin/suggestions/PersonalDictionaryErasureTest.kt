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

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.KeyNeighborTable
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.LookupKind

/**
 * Erased means erased.
 *
 * Erasure must not merely change the NEXT lookup. Without unbinding what is already displayed, the
 * user who has just confirmed the dialog keeps seeing the erased word in the band and can insert it
 * with a tap — through the same single commit path as any other candidate.
 */
class PersonalDictionaryErasureTest {

    private class FakeStrip : StripSurface {
        val events = mutableListOf<String>()
        var listener: SuggestionTapListener? = null

        override fun showSuggestions(first: String, second: String?, third: String?, fourth: String?) {
            events.add("show:$first")
        }

        override fun reserve() {
            events.add("reserve")
        }

        override fun hideSuggestions() {
            events.add("hide")
        }

        override fun setTapListener(listener: SuggestionTapListener) {
            this.listener = listener
        }
    }

    private class FakeEditor : EditorSurface {
        var word = ""
        val committed = mutableListOf<String>()

        override fun cachedWordBeforeCursor(): String = word

        override fun commitSuggestion(expectedPrefix: String, suggestion: String): Boolean {
            committed.add(suggestion)
            return true
        }

        override fun hasKnownCursor(): Boolean = true

        override fun hasLetterAfterCursor(): Boolean = false
    }

    private class FakeEngine : EngineHandle {
        val requested = mutableListOf<String>()
        var finishCount = 0
        private var serial = 0L

        override fun request(editorSessionId: Long, subtypeId: String, prefixUtf8: ByteArray): Any? {
            requested.add(String(prefixUtf8, Charsets.UTF_8))
            return ++serial
        }

        override fun isCurrent(token: Any): Boolean = token == serial

        override fun finishInput() {
            finishCount++
        }

        override fun updateKeyNeighbors(table: KeyNeighborTable?) = Unit

        override fun destroy(timeoutMs: Long): Boolean = true
    }

    private class DirectExecutor : java.util.concurrent.AbstractExecutorService() {
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() = Unit
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown(): Boolean = false
        override fun isTerminated(): Boolean = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
    }

    private fun sourceRoot(): File {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
    }

    @Test
    fun erasureUnbindsDisplayedCandidatesSoATapCannotCommitTheErasedWord() {
        val strip = FakeStrip()
        val editor = FakeEditor()
        val engine = FakeEngine()
        var callback: ResultCallback? = null
        val controller = SuggestionsController(
            strip, editor, UiPoster { it.run() },
            { resultCallback -> callback = resultCallback; engine },
            DirectExecutor(), true,
        )

        controller.onStartInput(eligible = true)
        editor.word = "гүз"
        controller.onTextChanged()
        val token = engine.requested.size.toLong()
        callback!!.onResult(token, listOf("Гүзәл", "гүзәллек"), LookupKind.PREFIX)
        assertTrue("the personal word is on the strip before erasure",
            strip.events.any { it == "show:Гүзәл" })

        val finishesBefore = engine.finishCount
        controller.onPersonalDictionaryErased()

        assertEquals("the generation is idled exactly as on an actual subtype change",
            finishesBefore + 1, engine.finishCount)
        assertEquals("the band stays reserved and empty, it does not keep the erased word",
            "reserve", strip.events.last())

        // The tap that would have committed the erased word is now a no-op.
        strip.listener!!.onTap("Гүзәл")
        assertTrue("nothing may be committed after erasure without new input",
            editor.committed.isEmpty())
    }

    @Test
    fun aStaleResultComputedBeforeErasureCannotRepaintTheBand() {
        val strip = FakeStrip()
        val editor = FakeEditor()
        val engine = FakeEngine()
        var callback: ResultCallback? = null
        val controller = SuggestionsController(
            strip, editor, UiPoster { it.run() },
            { resultCallback -> callback = resultCallback; engine },
            DirectExecutor(), true,
        )

        controller.onStartInput(eligible = true)
        editor.word = "гүз"
        controller.onTextChanged()
        val staleToken = engine.requested.size.toLong()

        controller.onPersonalDictionaryErased()
        val eventsAfterErasure = strip.events.size
        callback!!.onResult(staleToken, listOf("Гүзәл"), LookupKind.PREFIX)

        assertEquals("a result computed before the erasure must not repaint the strip",
            eventsAfterErasure, strip.events.size)
    }

    @Test
    fun erasingCoversEveryLanguageNotOnlyTheOneInView() {
        // Erase-all takes the full list of supported languages and loops over it, including
        // languages currently disabled. Asserted by source: the loop needs Context and cannot run
        // off-device.
        val controller = File(sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/settings/PersonalDictionaryScreenController.kt")
            .readText()
        // The call also takes a completion callback: the screen may not repaint or claim success
        // before the erasure has actually run.
        assertTrue("erase-all iterates every subtype it is given",
            controller.contains("fun eraseAll(subtypeIds: List<String>,"))
        assertTrue("the list it loops over is derived from ALL of them",
            controller.contains("val targets = subtypeIds.filter"))
        assertTrue(controller.contains("for (subtypeId in targets)"))
        assertTrue("and it notifies the IME so the band unbinds",
            controller.contains("PersonalDictionaries.notifyErased()"))

        val host = File(sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/settings/SettingsHostActivity.kt").readText()
        val screen = host.substringAfter("private fun buildPersonalDictionaryScreen()")
            .substringBefore("private fun addPersonalQuarantineCards(")
        assertTrue("the screen builds the erase target from every supported language",
            screen.contains("val subtypeIds = personalSubtypeIds()"))
        val eraseCall = screen.substringAfter("showErasePersonalDictionaryDialog(")
            .substringBefore(")")
        assertTrue("that complete list reaches erase-all", eraseCall.contains("subtypeIds"))
        assertTrue("disabled languages are included too",
            host.contains("DictionaryArtifactSpec.ALL.map { it.languageTag }.distinct()"))
    }

    @Test
    fun eraseAllDeletesTheImportJournalBeforeTheStoresAndStopsWhenItStays() {
        // The journal holds a full copy of the personal data; the discard runs on the backup
        // worker, behind any rollback queued at screen open, and only then do the stores erase.
        // A journal that stays on disk would roll the erase back later, so the erase stops.
        val host = File(sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/settings/SettingsHostActivity.kt").readText()
        val dialog = host.substringAfter("private fun showErasePersonalDictionaryDialog(")
            .substringBefore(".setNegativeButton(")
        val discard = dialog.indexOf("backupCoordinator().discardPendingImport { journalErased ->")
        val firstStore = dialog.indexOf("controller.eraseAll(subtypeIds)")
        assertTrue(discard in 0 until firstStore)
        val stop = dialog.indexOf("if (!journalErased) {")
        assertTrue("a failed discard is checked before any store erases", stop in discard until firstStore)
        assertTrue("and reports the failure without touching the stores",
            dialog.substring(stop, firstStore).contains(
                "afterPersonalMutation(false, R.string.personal_dictionary_erase_failed)\n" +
                    "                            return@discardPendingImport"))
        assertTrue(dialog.contains("&& refusalsErased && overlayErased,"))
    }

    @Test
    fun theKeyboardRollsBackAnInterruptedImportAtStartAndAtUnlock() {
        val ime = File(sourceRoot(), "java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java")
            .readText()
        val create = ime.substringAfter("public void onCreate() {").substringBefore("\n    }\n")
        assertTrue(create.contains("BackupCoordinator.recoverInterruptedImport(this, mDevicePrefs);"))
        val receiver = ime.substringAfter("mLockStateReceiver = new BroadcastReceiver() {")
            .substringBefore("};")
        assertTrue(receiver.contains("Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())"))
        assertTrue(receiver.contains(
            "BackupCoordinator.recoverInterruptedImport(LatinIME.this, mDevicePrefs);"))
    }
}
