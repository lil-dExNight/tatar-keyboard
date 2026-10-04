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

package rkr.simplekeyboard.inputmethod.latin.lab

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.UserManager
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import rkr.simplekeyboard.inputmethod.latin.common.Constants
import rkr.simplekeyboard.inputmethod.latin.settings.Settings

/**
 * Android-side owner of the lab session log, the opt-in instrument of the fifth-row study. While
 * the developer setting is on — and only while it is on — key-down and window events go to
 * [LabSessionLogWriter.FILE_NAME] in the app's files dir. An event is a timestamp, a kind, a key
 * code and the arm id: never text, never word or suggestion content (LabSessionLogContractTest
 * pins the no-text API surface). Nothing is written before the first unlock, when the files dir
 * is still encrypted, or from password fields.
 *
 * The key-down path only enqueues; file work runs on one background thread. The owner pulls the
 * file off the device with adb; the switch and the log itself exist only on debuggable builds.
 */
object LabSessionLog {
    private val lock = Any()
    private var appContext: Context? = null
    private var executor: ExecutorService? = null
    private var writer: LabSessionLogWriter? = null

    @Volatile
    private var enabled = false

    @Volatile
    private var arm = FifthRowArm.DEFAULT

    /** Once true it stays true for the process: unlocked storage is not re-locked by the keyguard. */
    @Volatile
    private var unlocked = false

    /** Held in a field: SharedPreferencesImpl keeps its listeners by weak reference. */
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, _ ->
        syncWith(prefs)
    }

    /** Called once from the IME service's onCreate; later reads ride on the preference listener. */
    @JvmStatic
    fun init(context: Context, prefs: SharedPreferences) {
        appContext = context.applicationContext
        syncWith(prefs)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
    }

    @JvmStatic
    fun syncWith(prefs: SharedPreferences) {
        // The switch lives on the debug-only developer screen; a pref arriving any other way
        // (a restored backup) must not arm the log on a release build.
        val debuggable = appContext?.let {
            it.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        } ?: false
        enabled = debuggable && Settings.readLabSessionLogEnabled(prefs)
        arm = Settings.readFifthRowArm(prefs)
    }

    /** Key down of a printable key, space or delete; every other code is not an event. */
    @JvmStatic
    fun onKeyDown(keyCode: Int, passwordField: Boolean) {
        if (!enabled || passwordField || !isUnlocked()) return
        val kind = when (keyCode) {
            Constants.CODE_DELETE -> LabSessionLogWriter.KIND_DELETE
            Constants.CODE_SPACE -> LabSessionLogWriter.KIND_SPACE
            else -> if (keyCode > 0) LabSessionLogWriter.KIND_LETTER else return
        }
        append(kind, keyCode)
    }

    @JvmStatic
    fun onKeyboardShown() {
        if (!enabled || !isUnlocked()) return
        append(LabSessionLogWriter.KIND_KEYBOARD_SHOWN, LabSessionLogWriter.KEY_CODE_NONE)
    }

    /** The hide is the flush point, so a pull right after a session sees the tail of the log. */
    @JvmStatic
    fun onKeyboardHidden() {
        if (!enabled || !isUnlocked()) return
        append(LabSessionLogWriter.KIND_KEYBOARD_HIDDEN, LabSessionLogWriter.KEY_CODE_NONE)
        executor?.execute {
            synchronized(lock) {
                writer?.flush()
            }
        }
    }

    /** Deletes the log and its rotation; runs after the already queued appends. */
    @JvmStatic
    fun clear(context: Context) {
        val dir = context.applicationContext.filesDir
        val task = Runnable {
            synchronized(lock) {
                writer?.close()
                writer = null
                LabSessionLogWriter(File(dir, LabSessionLogWriter.FILE_NAME)).clear()
            }
        }
        executor?.execute(task) ?: task.run()
    }

    private fun append(kind: Int, keyCode: Int) {
        val context = appContext ?: return
        val timestampMillis = System.currentTimeMillis()
        val currentArm = arm
        val exec = executor
            ?: Executors.newSingleThreadExecutor { task -> Thread(task, "lab-session-log") }
                .also { executor = it }
        exec.execute {
            synchronized(lock) {
                val target = writer ?: LabSessionLogWriter(
                    File(context.filesDir, LabSessionLogWriter.FILE_NAME)).also { writer = it }
                target.append(timestampMillis, kind, keyCode, currentArm)
            }
        }
    }

    private fun isUnlocked(): Boolean {
        if (unlocked) return true
        val context = appContext ?: return false
        val userManager = context.getSystemService(UserManager::class.java)
        // A missing UserManager means locked, not open.
        val value = userManager != null && userManager.isUserUnlocked
        unlocked = value
        return value
    }
}
