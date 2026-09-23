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
 * dictionary and of the bigram table, plus the catalogs the engines are started from. Pure move
 * from `SuggestionsController.kt` (ROADMAP Phase 6, T2); the production implementations stopped
 * being file-private and became `internal` for that reason alone.
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
 * device-protected context and the supported-artifact list, which is exactly the work that must not
 * happen for a user who leaves suggestions off.
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
         * layout but the two that do) or the store cannot be built at all — both leave the caller
         * fail-closed with no engine and a hidden strip.
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
 * E5c two-stage readiness: lazily created bigram-table storage, the exact same shape as
 * [DictionaryPreparation] for the exact same reason (a user who never turns suggestions on never
 * pays disk space or background work for the bigram table either) — kept a SEPARATE interface
 * rather than folding into [DictionaryPreparation] because the two artifacts already don't share
 * a spec, validator or store (`docs/DICTIONARY-E5B.md`), and merging their controller seams here
 * would just recreate that coupling one layer up.
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
         * Storage for the next-word table of [subtypeId], or null when that subtype ships none —
         * asked of the SAME registry [DeviceProtectedDictionaryPreparation.create] asks for the
         * dictionary, so the two can never end up on different languages. A language present in
         * the registry with no table, and a subtype absent from it entirely, both land here as
         * null and leave NEXT_WORD answering an empty list: the exact fail-closed shape a missing
         * table already had, with no effect on prefix suggestions or ordinary input.
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
