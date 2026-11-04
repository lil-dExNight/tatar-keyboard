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
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.CompanionListReader
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.ValidatedCompanionList
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.CompanionListArtifactSpec
import java.util.concurrent.ExecutorService

/**
 * Lazily loads ONE language's cold-companion word list off the UI thread, at most once per
 * process. [onResult] may arrive on any thread and is called exactly once; a null list means
 * "unusable" (missing or corrupt asset) and is terminal for the process.
 */
fun interface ColdCompanionPreparation {
    fun prepare(onResult: (ValidatedCompanionList?) -> Unit)
}

/**
 * Production [ColdCompanionPreparation]: reads ONE language's list from the assets on the given
 * executor. Which language is decided by the caller through [spec] (the artifact registry's
 * answer — `CompanionListArtifactSpec.forSubtype` — never a language string checked here).
 * Constructed cheaply on the UI thread (it only keeps the application context and the spec); the
 * AssetManager is touched only inside the background task, never on the cold-start path. The
 * result is returned to the caller and never written to any persistent store.
 */
class AssetColdCompanionPreparation(
    context: Context,
    private val executor: ExecutorService,
    private val spec: CompanionListArtifactSpec,
) : ColdCompanionPreparation {
    private val appContext = context.applicationContext

    override fun prepare(onResult: (ValidatedCompanionList?) -> Unit) {
        try {
            executor.execute {
                onResult(load())
            }
        } catch (_: Throwable) {
            onResult(null)
        }
    }

    private fun load(): ValidatedCompanionList? = try {
        appContext.assets.open(spec.assetPath).use { input ->
            CompanionListReader().read(input, spec)
        }
    } catch (_: Throwable) {
        null
    }
}
