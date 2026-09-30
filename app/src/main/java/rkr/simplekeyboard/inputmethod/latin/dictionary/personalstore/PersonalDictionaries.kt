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
 * The process-wide owner of the personal dictionaries: one store per subtype, one background
 * executor for all of them.
 *
 * `SettingsHostActivity` runs in the IME process, so the settings screen edits words through the
 * same serialized owner the engine reads from, with no IPC and no second writer. Reading is gated
 * live: [PersonalDictionaryGate] is checked on every lookup, so turning the setting off takes
 * effect on the next keystroke without restarting the engine.
 *
 * [PersonalBigramDictionaries] and [PersonalEmojiDictionaries] follow the same design.
 */
object PersonalDictionaries {

    private val lock = Any()
    private val stores = HashMap<String, PersonalDictionaryStore>()
    private var sharedExecutor: ExecutorService? = null

    /**
     * Notified after words are erased ("Erase all" / "Forget"), so the IME clears whatever is still
     * displayed and an erased word cannot be inserted with a tap.
     */
    @Volatile
    private var erasureListener: Runnable? = null

    /**
     * Notified when a store quarantines an unreadable file on its first open, so the user can be
     * told why the list is empty.
     *
     * A store can open with no input window and no listener, so notices wait in the set beside it
     * until the next input start. The set holds one entry per language that lost something.
     */
    @Volatile
    private var quarantineListener: Runnable? = null

    /** Guarded by [lock]; see [quarantineListener]. */
    private val pendingQuarantineNotices = LinkedHashSet<String>()

    /** The store for [subtypeId], created on first use. Safe to call from any thread. */
    internal fun storeFor(context: Context, subtypeId: String): PersonalDictionaryStore =
        synchronized(lock) {
            stores.getOrPut(subtypeId) {
                AndroidPersonalDictionaryStorage.create(context, subtypeId, executorLocked()) {
                    notifyQuarantined(subtypeId)
                }
            }
        }

    /**
     * The engine's read side for [subtypeId]. Behaves like [PersonalCandidateSource.EMPTY] while the
     * setting is off. Called from the controller's background executor at engine start, because the
     * store's first open reads a file.
     */
    @JvmStatic
    fun sourceFor(
        context: Context,
        subtypeId: String,
        gate: PersonalDictionaryGate,
    ): PersonalCandidateSource {
        val store = storeFor(context, subtypeId)
        if (gate.isOn()) store.prime()
        // The source is built in the `personal` package, which owns the read model; this package
        // passes only a snapshot supplier, so no method here names typed text.
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
    fun hasPendingQuarantineNotice(): Boolean =
        synchronized(lock) { pendingQuarantineNotices.isNotEmpty() }

    /**
     * Takes one waiting notice, the earliest-raised language's, if there is one. The caller takes it
     * only when it is about to be shown; another pending language waits for the next input start.
     *
     * Taking a notice also clears the durable mark of that language's store only, queued on the
     * shared worker. A language whose store is not open keeps its mark and raises its own notice
     * when it opens.
     */
    @JvmStatic
    fun consumeQuarantineNotice(): Boolean {
        val subtypeId = synchronized(lock) {
            val next = pendingQuarantineNotices.firstOrNull() ?: return false
            pendingQuarantineNotices.remove(next)
            next
        }
        synchronized(lock) { stores[subtypeId] }?.noticeDelivered()
        return true
    }

    /** Called on the store's worker when it quarantined an unreadable file. */
    private fun notifyQuarantined(subtypeId: String) {
        synchronized(lock) { pendingQuarantineNotices.add(subtypeId) }
        quarantineListener?.run()
    }

    private fun executorLocked(): ExecutorService =
        sharedExecutor ?: Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "personal-dictionary").apply { isDaemon = true }
        }.also { sharedExecutor = it }

    /**
     * The one worker every personal store in this process is serialized on (words, pairs, emoji),
     * so the stores sweep the shared `personal/` directory without racing each other's temp files.
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
        synchronized(lock) { pendingQuarantineNotices.clear() }
    }
}
