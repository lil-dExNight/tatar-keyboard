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

package rkr.simplekeyboard.inputmethod.latin.glide.recorder

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import rkr.simplekeyboard.inputmethod.latin.glide.GlidePath
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The Android shell of the gesture recorder (debug builds only). Resolves the storage directory
 * (a single directory inside the base, credential-protected `noBackupFilesDir` — the buffer
 * never writes anywhere else), persists the consent and armed flags in a debug-only preference
 * file, and fans the armed state out to the persistent indicator.
 *
 * The keyboard-side wiring is the integration's seam: it feeds the prompt pool from the active
 * engine's top-frequency read on every layout change ([setPromptWords]) and hands every finished
 * glide gesture to [onGlideGesture] together with the decode's top candidate. Nothing here reads
 * field text.
 */
object GestureRecorderController {

    /** The single directory the recorder owns, inside `noBackupFilesDir`. */
    const val DIRECTORY_NAME = "gesture_recorder"

    private const val PREFS_NAME = "gesture_recorder"
    private const val KEY_CONSENT = "consent_given"
    private const val KEY_ARMED = "armed"

    // Written under init's lock, read on any thread the keyboard or the screen runs on.
    @Volatile private var appContext: Context? = null
    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var recorder: GestureRecorder? = null
    private val armedListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    /**
     * The persisted intent to be armed. Arming needs the prompt pool, which arrives with the
     * first [setPromptWords] of the session; [wantArmed] bridges the gap between the user's
     * consent and that publish, and across process restarts.
     */
    private var wantArmed = false
    private var lastNotifiedArmed = false

    /** Initializes the singleton; idempotent. Safe to call from any entry point. */
    @Synchronized
    fun init(context: Context): GestureRecorder {
        recorder?.let { return it }
        val app = context.applicationContext
        appContext = app
        val store = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = store
        // Persisted armed is honored only with persisted consent; both are debug-local.
        wantArmed = store.getBoolean(KEY_CONSENT, false) && store.getBoolean(KEY_ARMED, false)
        val created = GestureRecorder(
            GestureRingBuffer(File(app.noBackupFilesDir, DIRECTORY_NAME))
        )
        recorder = created
        return created
    }

    private fun ready(): GestureRecorder? = recorder

    fun hasConsent(): Boolean = prefs?.getBoolean(KEY_CONSENT, false) == true

    fun isArmed(): Boolean = ready()?.armed == true

    /** Records the consent (the opt-in dialog's accept path) and arms the recorder. */
    @Synchronized
    fun grantConsentAndArm() {
        prefs?.edit()?.putBoolean(KEY_CONSENT, true)?.apply()
        setArmedInternal(true)
    }

    @Synchronized
    fun disarm() {
        setArmedInternal(false)
    }

    private fun setArmedInternal(armed: Boolean) {
        wantArmed = armed
        prefs?.edit()?.putBoolean(KEY_ARMED, armed)?.apply()
        val rec = ready() ?: return
        if (armed) rec.setArmed(true) else rec.setArmed(false)
        notifyArmed(rec.armed)
    }

    private fun notifyArmed(armed: Boolean) {
        if (armed == lastNotifiedArmed) return
        lastNotifiedArmed = armed
        for (listener in armedListeners) listener.invoke(armed)
    }

    /**
     * The keyboard's layout publish: [layoutId] names the layout (for example "tt"), [words] is
     * that language's bundled-dictionary top-frequency read. Called on every layout change.
     * Completes a pending arm once the prompt pool exists.
     */
    @Synchronized
    fun setPromptWords(layoutId: String, words: List<String>) {
        val rec = ready() ?: return
        rec.setPromptSource(GesturePromptSource(words.take(GesturePromptSource.PROMPT_POOL_SIZE)))
        if (wantArmed) {
            rec.setArmed(true)
            notifyArmed(rec.armed)
        }
    }

    /**
     * The keyboard's gesture commit point: one finished glide path plus the decode's top
     * candidate (null on an empty decode). Discarded unless the recorder is armed.
     */
    fun onGlideGesture(
        path: GlidePath,
        keyRadiusPx: Float,
        layoutId: String,
        densityDpi: Int,
        decodedTop1: String?,
    ) {
        ready()?.onGesture(
            path, keyRadiusPx, layoutId, GestureRecorder.densityBucket(densityDpi), decodedTop1,
        )
    }

    fun currentPrompt(): String? = ready()?.currentPrompt()

    fun recordCount(): Int = ready()?.recordCount() ?: 0

    fun discardAll(): Boolean = ready()?.discardAll() ?: false

    /** The ring buffer's file, served to the share sheet by [GestureRecorderExportProvider]. */
    fun recordsFile(): File? {
        val app = appContext ?: return null
        return File(File(app.noBackupFilesDir, DIRECTORY_NAME), GestureRingBuffer.FILE_NAME)
    }

    /** Registers [listener] for armed-state changes (the persistent indicator's hook). */
    fun addArmedListener(listener: (Boolean) -> Unit) {
        armedListeners.add(listener)
    }

    fun removeArmedListener(listener: (Boolean) -> Unit) {
        armedListeners.remove(listener)
    }

    /** The share-sheet export: hands the ring buffer file to the user's chosen target. */
    fun shareRecords(activity: Activity) {
        val context = appContext ?: return
        val uri = GestureRecorderExportProvider.recordsUri(context)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(Intent.createChooser(send, null))
    }
}
