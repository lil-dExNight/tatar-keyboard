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

import android.content.Context
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.AndroidBigramStorageFactory
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.AndroidDictionaryStorageFactory
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.BigramPreparationResult
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.BigramStorageController
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryStorageController
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PreparationResult
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedBigramTableCatalog
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedDictionaryCatalog
import java.util.concurrent.ExecutorService

/**
 * The storage-preparation seams of [SuggestionsController]: background unpacking/validation of the
 * dictionary and of the bigram table, plus the catalogs the engines are started from.
 */

/**
 * Lazily created dictionary storage: background unpacking/validation plus the catalog the engine is
 * started from.
 *
 * Nothing behind this seam exists until preparation is actually requested, so a user who never
 * turns Tatar suggestions on never pays disk space or background work for them. JVM tests inject a
 * fake, which is what makes "preparation not requested / requested exactly once" observable without
 * Android.
 */
interface DictionaryPreparation {
    /**
     * Requests background preparation of the newest dictionary. There is no de-duplication behind
     * this seam — `DictionaryStorageController.prepare` is a straight delegate and
     * `BackgroundDictionaryPreparer` queues a fresh task per call — so the caller's "preparation
     * requested" flag is the only guard. [onResult] may arrive on any thread.
     */
    fun prepare(onResult: (PreparationResult) -> Unit)

    /** Catalog over the published dictionary, read by the engine factory off the UI thread. */
    fun catalog(): PublishedDictionaryCatalog
}

/**
 * Production [DictionaryPreparation] over the device-protected dictionary store.
 *
 * Built on the first preparation request only: constructing the store resolves the
 * device-protected context and the supported-artifact list, work that must not happen for a user
 * who leaves suggestions off.
 */
internal class DeviceProtectedDictionaryPreparation(
    private val storage: DictionaryStorageController,
) : DictionaryPreparation {
    override fun prepare(onResult: (PreparationResult) -> Unit) {
        storage.prepare(onResult)
    }

    override fun catalog(): PublishedDictionaryCatalog = storage

    companion object {
        /**
         * Storage for the dictionary of [subtypeId], or null when that subtype ships none (every
         * layout but the two that do) or the store cannot be built at all; both leave the caller
         * with no engine and a hidden strip.
         */
        fun create(
            context: Context,
            executor: ExecutorService,
            subtypeId: String,
        ): DictionaryPreparation? = try {
            val artifact = DictionaryArtifactSpec.forSubtype(subtypeId)
            if (artifact == null) {
                null
            } else {
                DeviceProtectedDictionaryPreparation(
                    AndroidDictionaryStorageFactory.create(context, executor, artifact),
                )
            }
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * Lazily created bigram-table storage, shaped like [DictionaryPreparation] for the same reason
 * (no disk space or background work until suggestions are turned on). A separate interface because
 * the two artifacts share no spec, validator or store.
 */
interface BigramPreparation {
    fun prepare(onResult: (BigramPreparationResult) -> Unit)
    fun catalog(): PublishedBigramTableCatalog
}

/** Production [BigramPreparation] over the device-protected bigram-table store. */
internal class DeviceProtectedBigramPreparation(
    private val storage: BigramStorageController,
) : BigramPreparation {
    override fun prepare(onResult: (BigramPreparationResult) -> Unit) = storage.prepare(onResult)

    override fun catalog(): PublishedBigramTableCatalog = storage

    companion object {
        /**
         * Storage for the next-word table of [subtypeId], or null when that subtype ships none.
         * Asks the same registry as [DeviceProtectedDictionaryPreparation.create], so the two never
         * end up on different languages. Null leaves NEXT_WORD answering an empty list, with no
         * effect on word completion or ordinary input.
         */
        fun create(
            context: Context,
            executor: ExecutorService,
            subtypeId: String,
        ): BigramPreparation? = try {
            val artifact = DictionaryArtifactSpec.bigramsForSubtype(subtypeId)
            if (artifact == null) {
                null
            } else {
                DeviceProtectedBigramPreparation(
                    AndroidBigramStorageFactory.create(context, executor, artifact),
                )
            }
        } catch (_: Throwable) {
            null
        }
    }
}
