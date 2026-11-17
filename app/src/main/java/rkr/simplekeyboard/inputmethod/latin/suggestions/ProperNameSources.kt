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
 * Lazily loads one language's [ProperNameIndex] off the UI thread. [onResult] may arrive on any
 * thread and is called exactly once; null means the asset is unusable and is terminal.
 */
fun interface ProperNamePreparation {
    fun prepare(onResult: (ProperNameIndex?) -> Unit)
}

/**
 * Reads one language's proper-name table from the assets on [executor]. The application context and
 * path are captured cheaply; AssetManager I/O stays off the caller's thread, and nothing is written
 * to a store.
 */
class AssetProperNamePreparation(
    context: Context,
    private val executor: ExecutorService,
    private val assetPath: String,
) : ProperNamePreparation {
    private val appContext = context.applicationContext

    override fun prepare(onResult: (ProperNameIndex?) -> Unit) {
        try {
            executor.execute {
                onResult(load())
            }
        } catch (_: Throwable) {
            onResult(null)
        }
    }

    private fun load(): ProperNameIndex? {
        val table = try {
            appContext.assets.open(assetPath).use { ProperNameIndex.parse(it) }
        } catch (_: Throwable) {
            ProperNameIndex.EMPTY
        }
        if (table.isEmpty) return null
        return table
    }
}
