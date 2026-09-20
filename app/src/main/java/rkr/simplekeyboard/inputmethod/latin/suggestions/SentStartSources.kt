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
 * Lazily loads the one [SentStartSource] of the process off the UI thread — the exact shape of
 * the controller's `EmojiSuggestPreparation` seam, so a user who never opens a Tatar field at a
 * sentence start never reads the asset at all. [onResult] may arrive on any thread and is called
 * exactly once; a null source means "unusable" (missing/corrupt asset) and is terminal for the
 * process.
 */
fun interface SentStartPreparation {
    fun prepare(onResult: (SentStartSource?) -> Unit)
}

/**
 * Production [SentStartPreparation]: reads the sentence-start table from the assets on the given
 * executor. Constructed cheaply on the UI thread (it only keeps the application context); the
 * AssetManager is touched only inside the background task, never on the cold-start path. The
 * result is returned to the caller and never written to any persistent store.
 */
class AssetSentStartPreparation(
    context: Context,
    private val executor: ExecutorService,
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
            appContext.assets.open(SENTSTART_ASSET_PATH).use { SentStartIndex.parse(it) }
        } catch (_: Throwable) {
            SentStartIndex.EMPTY
        }
        if (table.isEmpty) return null
        return table
    }

    private companion object {
        const val SENTSTART_ASSET_PATH = "dictionaries/tatar_sentstart_v1.txt"
    }
}
