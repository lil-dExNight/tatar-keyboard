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

package rkr.simplekeyboard.inputmethod.latin.settings.backup

import android.content.Context
import android.content.RestrictionsManager
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.provider.DocumentsContract
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.AndroidPersonalDictionaryStorage
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalBigramDictionaries
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalDictionaries
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalEmojiDictionaries
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalMutationOutcome
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.RefusedCorrectionStores
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.TextShortcutStores
import rkr.simplekeyboard.inputmethod.latin.settings.Settings
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Android side of the backup: reads and writes the user-picked document (SAF needs no
 * permission), wires [BackupTransfer] to the live stores, and answers on the UI thread.
 *
 * Both directions run on one dedicated worker. Importing writes each personal file through its
 * owning store (`replaceAll` on the store's own worker, awaited here), so a restore cannot race a
 * learning write, and the store re-reads and re-publishes its snapshot right away — the open
 * keyboard picks the imported words up without a restart. Settings are applied through the
 * SharedPreferences editor, so every listener (the IME's Settings singleton among them) reloads;
 * device policy is then re-read, so a managed restriction wins over an imported value.
 *
 * Nothing here logs, and no callback carries content: the answers are a boolean for export and a
 * [BackupImportResult] for import.
 */
internal class BackupCoordinator(
    context: Context,
    private val prefs: SharedPreferences,
    private val uiPoster: (Runnable) -> Unit = { Handler(Looper.getMainLooper()).post(it) },
) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "backup-transfer").apply { isDaemon = true }
    }

    /** True while the credential-protected directories exist. Both directions fail without it. */
    private val userUnlocked: Boolean
        get() = appContext.getSystemService(UserManager::class.java)?.isUserUnlocked ?: false

    /** Writes the archive to the user-picked [uri]; [onFinished] arrives on the UI thread. */
    fun exportTo(uri: Uri, onFinished: (Boolean) -> Unit) {
        executor.execute {
            val written = try {
                if (!userUnlocked) {
                    false
                } else {
                    val bytes = BackupTransfer.export(target)
                    appContext.contentResolver.openOutputStream(uri, "w")?.use { output ->
                        output.write(bytes)
                    } != null
                }
            } catch (_: Exception) {
                false
            }
            if (!written) {
                // A failed write may have left a partial document behind; remove it when the
                // provider allows, so no truncated archive masquerades as a backup.
                runCatching { DocumentsContract.deleteDocument(appContext.contentResolver, uri) }
            }
            uiPoster { onFinished(written) }
        }
    }

    /** Restores the user-picked [uri]; [onFinished] arrives on the UI thread. */
    fun importFrom(uri: Uri, onFinished: (BackupImportResult) -> Unit) {
        executor.execute {
            val result = try {
                if (!userUnlocked) {
                    BackupImportResult.WRITE_FAILED
                } else {
                    val bytes = readBounded(uri)
                    if (bytes == null) {
                        BackupImportResult.INVALID_FILE
                    } else {
                        BackupTransfer.import(target, bytes)
                    }
                }
            } catch (_: Exception) {
                BackupImportResult.INVALID_FILE
            }
            uiPoster { onFinished(result) }
        }
    }

    /** The document's bytes, or null when it is unreadable or over the archive cap. */
    private fun readBounded(uri: Uri): ByteArray? {
        val input = appContext.contentResolver.openInputStream(uri) ?: return null
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size().toLong() > BackupFormat.MAX_ARCHIVE_BYTES) return null
            }
            return out.toByteArray()
        }
    }

    fun shutdown() {
        executor.shutdown()
    }

    private val target = object : BackupTarget {
        override val versionCode: Int
            get() = readVersionCode()

        override fun readPreferences(): Map<String, Any?> = HashMap(prefs.all)

        override fun personalFileNames(): List<String> = try {
            personalDirectory().list()?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        override fun readPersonalFile(fileName: String): ByteArray? = try {
            val (kind) = BackupFormat.classifyPersonalFileName(fileName) ?: return null
            val file = File(personalDirectory(), fileName)
            // The length gate runs before any byte is read; a bigger file cannot pass the
            // validator either, so exporting never touches it.
            if (file.isFile && file.length() <= kind.maxBytes) file.readBytes() else null
        } catch (_: Exception) {
            null
        }

        override fun replacePersonalFile(fileName: String, bytes: ByteArray?): Boolean {
            val (kind, subtypeId) = BackupFormat.classifyPersonalFileName(fileName) ?: return false
            val done = CountDownLatch(1)
            val succeeded = AtomicBoolean(false)
            val outcome = PersonalMutationOutcome { ok ->
                succeeded.set(ok)
                done.countDown()
            }
            when (kind) {
                BackupFormat.PersonalKind.WORDS ->
                    PersonalDictionaries.replaceAll(appContext, subtypeId, bytes, outcome)
                BackupFormat.PersonalKind.PAIRS ->
                    PersonalBigramDictionaries.replaceAll(appContext, subtypeId, bytes, outcome)
                BackupFormat.PersonalKind.EMOJI ->
                    PersonalEmojiDictionaries.replaceAll(appContext, subtypeId, bytes, outcome)
                BackupFormat.PersonalKind.REFUSED ->
                    RefusedCorrectionStores.replaceAll(appContext, subtypeId, bytes, outcome)
                BackupFormat.PersonalKind.SHORTCUTS ->
                    TextShortcutStores.replaceAll(appContext, bytes, outcome)
            }
            return try {
                done.await()
                succeeded.get()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        }

        override fun replacePreferences(values: Map<String, Any?>): Boolean {
            val editor = prefs.edit()
            editor.clear()
            for ((key, value) in values) {
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    else -> Unit // the archive parser produces only the types above
                }
            }
            if (!editor.commit()) return false
            // The import cleared the cached policy with everything else; put it back, so a managed
            // device keeps its managed values and locked rows without waiting for a restart.
            val restrictions = appContext.getSystemService(Context.RESTRICTIONS_SERVICE)
                as? RestrictionsManager
            if (restrictions != null) {
                Settings.loadRestrictions(restrictions, prefs)
            }
            return true
        }

        private fun personalDirectory(): File =
            File(appContext.noBackupFilesDir, AndroidPersonalDictionaryStorage.PERSONAL_DIRECTORY_NAME)

        @Suppress("DEPRECATION")
        private fun readVersionCode(): Int = try {
            val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            } else {
                info.versionCode
            }
        } catch (_: Exception) {
            0
        }
    }
}
