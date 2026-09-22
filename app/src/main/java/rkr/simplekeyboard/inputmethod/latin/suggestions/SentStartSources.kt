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
import java.util.concurrent.ExecutorService

/**
 * Lazily loads ONE language's [SentStartSource] off the UI thread — the exact shape of the
 * controller's `EmojiSuggestPreparation` seam, per language: a language whose fields never sit
 * at a sentence start never has its asset read at all. [onResult] may arrive on any thread and
 * is called exactly once; a null source means "unusable" (missing/corrupt asset) and is
 * terminal for the process.
 */
fun interface SentStartPreparation {
    fun prepare(onResult: (SentStartSource?) -> Unit)
}

/**
 * Production [SentStartPreparation]: reads ONE language's sentence-start table from the assets
 * on the given executor; which language is decided by the caller through [assetPath] (the
 * artifact registry's answer — `DictionaryArtifactSpec.sentStartAssetForSubtype` — never a
 * language string checked here). Constructed cheaply on the UI thread (it only keeps the
 * application context and the path); the AssetManager is touched only inside the background
 * task, never on the cold-start path. The result is returned to the caller and never written
 * to any persistent store.
 */
class AssetSentStartPreparation(
    context: Context,
    private val executor: ExecutorService,
    private val assetPath: String,
) : SentStartPreparation {
    private val appContext = context.applicationContext

    override fun prepare(onResult: (SentStartSource?) -> Unit) {
        try {
            executor.execute {
                onResult(load())
            }
        } catch (_: Throwable) {
            onResult(null)
        }
    }

    private fun load(): SentStartSource? {
        val table = try {
            appContext.assets.open(assetPath).use { SentStartIndex.parse(it) }
        } catch (_: Throwable) {
            SentStartIndex.EMPTY
        }
        if (table.isEmpty) return null
        return table
    }
}
