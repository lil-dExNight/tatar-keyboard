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

import android.content.Context
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalCandidateSource
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.SnapshotPersonalCandidateSource

/** Reads the live value of the personal-dictionary setting. A seam so tests need no preferences. */
fun interface PersonalDictionaryGate {
    fun isOn(): Boolean
}

/**
 * The ONE process-wide owner of the personal dictionaries — one store per subtype, one background
 * executor for all of them.
 *
 * There is exactly one process to own: `SettingsHostActivity` is declared without
 * `android:process`, so the settings screen and the IME share it. That is what lets the screen add,
 * remove and erase words through the same serialized owner the engine reads from, with no IPC and
 * no second writer.
 *
 * Reading is gated live rather than by restarting the engine: [PersonalDictionaryGate] is consulted
 * on every lookup, so turning the setting off stops personal candidates on the very next keystroke
 * while the engine, its lease and its mapping stay exactly as they were.
 */
object PersonalDictionaries {

    private val lock = Any()
    private val stores = HashMap<String, PersonalDictionaryStore>()
    private var sharedExecutor: ExecutorService? = null

    /**
     * Notified after words are erased ("Erase all" / "Forget"), so the IME can unbind whatever is
     * still displayed. Erasure must not merely change the NEXT lookup: without this the user who
     * just confirmed the dialog would keep seeing the erased word in the band and could insert it
     * with a tap.
     */
    @Volatile
    private var erasureListener: Runnable? = null

    /**
     * Notified when an unreadable personal file has been set aside on a store's first open, so the
     * user can be told why the list is empty.
     *
     * The flag beside it exists because the event does not respect the keyboard's lifecycle: the
     * store opens from the engine's background executor, and from the settings screen, both of which
     * can happen with no input window up and no listener installed. Dropping the notice then would
     * put the subsystem straight back to losing data silently, so it waits instead, and the IME picks
     * it up at the next input start.
     */
    @Volatile
    private var quarantineListener: Runnable? = null

    @Volatile
    private var quarantinePending = false

    /** The store for [subtypeId], created on first use. Safe to call from any thread. */
    internal fun storeFor(context: Context, subtypeId: String): PersonalDictionaryStore =
        synchronized(lock) {
            stores.getOrPut(subtypeId) {
                AndroidPersonalDictionaryStorage.create(context, subtypeId, executorLocked()) {
                    notifyQuarantined()
                }
            }
        }

    /**
     * The engine's read side for [subtypeId]. Returns [PersonalCandidateSource.EMPTY] semantics
     * whenever the setting is off, so a disabled personal dictionary costs a lookup nothing beyond
     * one boolean read.
     *
     * Called from the controller's background executor at engine start (the store's first open
     * reads a file), never from the UI thread.
     */
    @JvmStatic
    fun sourceFor(
        context: Context,
        subtypeId: String,
        gate: PersonalDictionaryGate,
    ): PersonalCandidateSource {
        val store = storeFor(context, subtypeId)
        if (gate.isOn()) store.prime()
        // The source itself is built in the `personal` package, which owns the read model: this
        // package hands it nothing but a supplier of the published snapshot. That is also what keeps
        // the frozen privacy rule of the store package true — no method name here names typed text.
        return SnapshotPersonalCandidateSource {
            if (gate.isOn()) store.snapshot else PersonalDictionary.EMPTY
        }
    }

    /** The current snapshot of [subtypeId], for the "Personal dictionary" screen. */
    internal fun snapshotFor(context: Context, subtypeId: String): PersonalDictionary =
        storeFor(context, subtypeId).also { it.prime() }.snapshot

    @JvmStatic
    fun setErasureListener(listener: Runnable?) {
        erasureListener = listener
    }

    /** Called by the screen once an erasure event has been queued on the store's worker. */
    internal fun notifyErased() {
        erasureListener?.run()
    }

    @JvmStatic
    fun setQuarantineListener(listener: Runnable?) {
        quarantineListener = listener
    }

    /** Whether a notice is still waiting to be shown; see [quarantineListener]. */
    @JvmStatic
    fun hasPendingQuarantineNotice(): Boolean = quarantinePending

    /**
     * Takes the waiting notice, if there is one. The caller clears it only when it is really about to
     * be shown, so a notice raised while the window was down is not spent on nobody.
     *
     * B5. Spending it also clears the DURABLE half of the mark, on every store that is open — the
     * in-memory flag alone died with the process, and the loss went unmentioned for ever after. Each
     * store queues its own deletion on the shared worker, so no file is touched on the caller's
     * thread. A language whose store has not been opened in this process keeps its own mark and
     * raises its own notice when it opens: one notice per language that lost something, which is the
     * number of things that actually happened.
     */
    @JvmStatic
    fun consumeQuarantineNotice(): Boolean {
        if (!quarantinePending) return false
        quarantinePending = false
        val live = synchronized(lock) { stores.values.toList() }
        for (store in live) store.noticeDelivered()
        return true
    }

    /** Called on the store's worker when it set an unreadable file aside. */
    private fun notifyQuarantined() {
        quarantinePending = true
        quarantineListener?.run()
    }

    private fun executorLocked(): ExecutorService =
        sharedExecutor ?: Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "personal-dictionary").apply { isDaemon = true }
        }.also { sharedExecutor = it }

    /**
     * The one worker every personal store in this process is serialized on — words and bigrams
     * alike (P1 of Phase 2, docs/ROADMAP-P2.md). Sharing it is what lets the two stores sweep the
     * same `personal/` directory without ever racing each other's in-flight temp files.
     */
    internal fun sharedStoreExecutor(): ExecutorService = synchronized(lock) { executorLocked() }

    /** Test hook: drops every cached store so an isolated test starts from nothing. */
    internal fun resetForTest() {
        synchronized(lock) {
            stores.clear()
            sharedExecutor?.shutdown()
            sharedExecutor = null
        }
        erasureListener = null
        quarantineListener = null
        quarantinePending = false
    }
}
