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

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.test.InstrumentationTestCase
import android.test.InstrumentationTestRunner
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import rkr.simplekeyboard.inputmethod.compat.PreferenceManagerCompat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalAdd
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersbFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.AndroidPersonalDictionaryStorage
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalBigramDictionaries
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalBigramStore
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalDictionaries
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalDictionaryStore
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalDirectoryProvider
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalMutationOutcome
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalOutputOpener
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.AndroidDurableFileOps
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.SpaceProbe
import rkr.simplekeyboard.inputmethod.latin.settings.Settings

/**
 * Before/after counters for the manual lock-screen quick-reply leg. Preparation installs one
 * disposable word and pair in the real stores; verification proves that typing them on keyguard
 * changed neither counter, restores the original media and preferences, and removes all probes.
 */
class LockScreenLearningDeviceTest : InstrumentationTestCase() {

    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private lateinit var probeDirectory: File
    private lateinit var personalDirectory: File

    override fun setUp() {
        super.setUp()
        context = instrumentation.targetContext.applicationContext
        preferences = PreferenceManagerCompat.getDeviceSharedPreferences(context)
        probeDirectory = File(context.noBackupFilesDir, "lock-screen-learning-device-probe")
        personalDirectory = File(
            context.noBackupFilesDir,
            AndroidPersonalDictionaryStorage.PERSONAL_DIRECTORY_NAME,
        )
    }

    fun testPrepareLockScreenLearningProbe() {
        val word = requiredWordArgument()
        assertNotNull(PersonalAdd.addableNormalizedForm(PersonalSubtypes.TATAR_RU, word))
        assertFalse("prepare must run after unlock", keyguardLocked())
        assertFalse("a previous probe was not verified and restored", probeDirectory.exists())
        assertTrue(probeDirectory.mkdirs())
        val metadata = Properties()
        metadata.setProperty("word", word)
        metadata.setProperty("context", CONTEXT_WORD)
        try {
            val words = PersonalDictionaries.storeFor(context, PersonalSubtypes.TATAR_RU)
            val pairs = PersonalBigramDictionaries.storeFor(context, PersonalSubtypes.TATAR_RU)
            awaitMutation { words.prime(it) }
            awaitMutation { pairs.prime(it) }
            assertTrue("the probe word already exists", words.snapshot.isEmpty ||
                words.snapshot.indexOfNormalized(word.lowercase()) < 0)
            assertTrue("the probe pair already exists", pairs.snapshot.indexOfPair(
                CONTEXT_WORD, word.lowercase(),
            ) < 0)

            saveOriginal(metadata, "words", TpersFormat.personalFileName(PersonalSubtypes.TATAR_RU))
            saveOriginal(metadata, "pairs",
                TpersbFormat.personalBigramsFileName(PersonalSubtypes.TATAR_RU))
            for (key in PREFERENCE_KEYS) {
                metadata.setProperty("pref.$key.present", preferences.contains(key).toString())
                metadata.setProperty("pref.$key.value", preferences.getBoolean(key, false).toString())
            }
            writeMetadata(metadata)

            preferences.edit()
                .putBoolean(Settings.PREF_TATAR_SUGGESTIONS, true)
                .putBoolean(Settings.PREF_PERSONAL_DICTIONARY, true)
                .putBoolean(Settings.PREF_GLIDE_TYPING, true)
                .putBoolean(Settings.PREF_TATAR_AUTOCORRECT, false)
                .commit()

            val wordBytes = fixtureWords(word)
            val pairBytes = fixturePairs(word)
            awaitMutation { outcome -> words.replaceAll(wordBytes, outcome) }
            awaitMutation { outcome -> pairs.replaceAll(pairBytes, outcome) }
            val wordIndex = words.snapshot.indexOfNormalized(word.lowercase())
            val pairIndex = pairs.snapshot.indexOfPair(CONTEXT_WORD, word.lowercase())
            assertTrue("the prepared word was not published", wordIndex >= 0)
            assertTrue("the prepared pair was not published", pairIndex >= 0)
            metadata.setProperty("wordUsage", words.snapshot.usageCountAt(wordIndex).toString())
            metadata.setProperty("pairFrequency", pairs.snapshot.frequencyCountAt(pairIndex).toString())
            writeMetadata(metadata)
        } catch (failure: Throwable) {
            if (metadataFile().isFile) {
                restorePreferences(metadata)
                restoreOriginalStores(metadata)
            }
            probeDirectory.deleteRecursively()
            throw failure
        }
    }

    fun testVerifyLockScreenLearningProbeAndRestore() {
        assertTrue("prepare has not created a probe", probeDirectory.isDirectory)
        val metadata = readMetadata()
        try {
            val word = metadata.getProperty("word")
            val words = PersonalDictionaries.storeFor(context, PersonalSubtypes.TATAR_RU)
            val pairs = PersonalBigramDictionaries.storeFor(context, PersonalSubtypes.TATAR_RU)
            awaitMutation { words.prime(it) }
            awaitMutation { pairs.prime(it) }
            val wordIndex = words.snapshot.indexOfNormalized(word.lowercase())
            val pairIndex = pairs.snapshot.indexOfPair(CONTEXT_WORD, word.lowercase())
            assertTrue("the prepared word disappeared during quick reply", wordIndex >= 0)
            assertTrue("the prepared pair disappeared during quick reply", pairIndex >= 0)
            assertEquals("the lock screen changed the saved word's usage counter",
                metadata.getProperty("wordUsage").toInt(),
                words.snapshot.usageCountAt(wordIndex))
            assertEquals("the lock screen changed the learned pair's frequency",
                metadata.getProperty("pairFrequency").toInt(),
                pairs.snapshot.frequencyCountAt(pairIndex))
            assertFalse("the device is still on keyguard after the leg", keyguardLocked())
        } finally {
            restorePreferences(metadata)
            restoreOriginalStores(metadata)
            probeDirectory.deleteRecursively()
        }
    }

    private fun fixtureWords(word: String): ByteArray {
        val directory = File(probeDirectory, "fixture-words")
        val store = PersonalDictionaryStore(
            PersonalSubtypes.TATAR_RU,
            PersonalDirectoryProvider { directory },
            AndroidDurableFileOps,
            PersonalOutputOpener { file -> FileOutputStream(file) },
            SpaceProbe { Long.MAX_VALUE },
            { 1000L },
            { runnable -> runnable.run() },
        )
        store.addManually(word)
        return File(directory, TpersFormat.personalFileName(PersonalSubtypes.TATAR_RU)).readBytes()
    }

    private fun fixturePairs(word: String): ByteArray {
        val directory = File(probeDirectory, "fixture-pairs")
        val store = PersonalBigramStore(
            PersonalSubtypes.TATAR_RU,
            PersonalDirectoryProvider { directory },
            AndroidDurableFileOps,
            PersonalOutputOpener { file -> FileOutputStream(file) },
            SpaceProbe { Long.MAX_VALUE },
            { 1000L },
            { runnable -> runnable.run() },
            { _, _ -> true },
        )
        repeat(PersonalBigramStore.LEARN_THRESHOLD) { store.notePair(CONTEXT_WORD, word) }
        return File(directory,
            TpersbFormat.personalBigramsFileName(PersonalSubtypes.TATAR_RU)).readBytes()
    }

    private fun saveOriginal(metadata: Properties, label: String, fileName: String) {
        val file = File(personalDirectory, fileName)
        val present = file.isFile
        metadata.setProperty("$label.present", present.toString())
        if (present) File(probeDirectory, "$label-before.bin").writeBytes(file.readBytes())
    }

    private fun restoreOriginalStores(metadata: Properties) {
        val wordBytes = originalBytes(metadata, "words")
        val pairBytes = originalBytes(metadata, "pairs")
        awaitMutation { outcome ->
            PersonalDictionaries.storeFor(context, PersonalSubtypes.TATAR_RU)
                .replaceAll(wordBytes, outcome)
        }
        awaitMutation { outcome ->
            PersonalBigramDictionaries.storeFor(context, PersonalSubtypes.TATAR_RU)
                .replaceAll(pairBytes, outcome)
        }
    }

    private fun originalBytes(metadata: Properties, label: String): ByteArray? =
        if (metadata.getProperty("$label.present").toBoolean()) {
            File(probeDirectory, "$label-before.bin").readBytes()
        } else {
            null
        }

    private fun restorePreferences(metadata: Properties) {
        val editor = preferences.edit()
        for (key in PREFERENCE_KEYS) {
            if (metadata.getProperty("pref.$key.present").toBoolean()) {
                editor.putBoolean(key, metadata.getProperty("pref.$key.value").toBoolean())
            } else {
                editor.remove(key)
            }
        }
        assertTrue("could not restore quick-reply preferences", editor.commit())
    }

    private fun awaitMutation(call: (PersonalMutationOutcome) -> Unit) {
        val done = CountDownLatch(1)
        val succeeded = AtomicBoolean()
        call(PersonalMutationOutcome { result ->
            succeeded.set(result)
            done.countDown()
        })
        assertTrue("the personal-store operation did not finish", done.await(10, TimeUnit.SECONDS))
        assertTrue("the personal-store operation failed", succeeded.get())
    }

    private fun requiredWordArgument(): String {
        val runner = instrumentation as InstrumentationTestRunner
        val word = runner.arguments.getString("ttLockWord")?.trim().orEmpty()
        assertTrue("pass -e ttLockWord <unused Tatar word>", word.isNotEmpty())
        return word
    }

    private fun keyguardLocked(): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: true

    private fun metadataFile() = File(probeDirectory, "probe.properties")

    private fun writeMetadata(metadata: Properties) {
        FileOutputStream(metadataFile()).use { metadata.store(it, "lock-screen learning probe") }
    }

    private fun readMetadata(): Properties = Properties().apply {
        FileInputStream(metadataFile()).use { load(it) }
    }

    private companion object {
        const val CONTEXT_WORD = "сәләм"
        val PREFERENCE_KEYS = listOf(
            Settings.PREF_TATAR_SUGGESTIONS,
            Settings.PREF_PERSONAL_DICTIONARY,
            Settings.PREF_GLIDE_TYPING,
            Settings.PREF_TATAR_AUTOCORRECT,
        )
    }
}
