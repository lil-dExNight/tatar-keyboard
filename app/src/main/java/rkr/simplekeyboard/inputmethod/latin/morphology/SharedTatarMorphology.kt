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

package rkr.simplekeyboard.inputmethod.latin.morphology

import android.content.Context
import android.content.res.AssetManager
import rkr.simplekeyboard.inputmethod.compat.PreferenceManagerCompat
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.StemLexiconArtifactSpec

/**
 * A lazy value whose successful result is shared by every caller. A failed load is not cached, so
 * a later caller may retry; concurrent callers publish at most one successful result.
 */
internal class SharedMorphologyLoader<T : Any>(private val load: () -> T) {
    @Volatile
    private var value: T? = null

    private val lock = Any()

    fun get(): T? {
        value?.let { return it }
        synchronized(lock) {
            value?.let { return it }
            return try {
                load().also { value = it }
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * The process-wide Tatar morphological acceptor. The keyboard engine and the spell checker ask for
 * the same validated image, so an APK that uses both keeps one copy of its stem data.
 */
object SharedTatarMorphology {
    @Volatile
    private var loader: SharedMorphologyLoader<TatarMorphologicalAcceptor>? = null

    private val lock = Any()

    /** The shared acceptor, or null when its asset cannot be read or validated. */
    @JvmStatic
    fun get(context: Context): TatarMorphologicalAcceptor? {
        val existing = loader
        val shared = existing ?: synchronized(lock) {
            loader ?: SharedMorphologyLoader {
                val deviceContext = PreferenceManagerCompat.getDeviceContext(
                    context.applicationContext,
                )
                deviceContext.assets.open(
                    StemLexiconArtifactSpec.TATAR_STEMS_V1.assetPath,
                    AssetManager.ACCESS_STREAMING,
                ).use { source ->
                    TatarMorphologicalAcceptor.open(
                        source,
                        StemLexiconArtifactSpec.TATAR_STEMS_V1,
                    )
                }
            }.also { loader = it }
        }
        return shared.get()
    }
}
