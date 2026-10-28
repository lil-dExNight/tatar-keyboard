/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rkr.simplekeyboard.inputmethod.latin.glide.recorder

import android.app.Application
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.TdictPrefixIndex
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.DictionaryIdentity
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import rkr.simplekeyboard.inputmethod.latin.glide.GlideGestureSink
import rkr.simplekeyboard.inputmethod.latin.glide.GlidePath

/**
 * The debug build's process entry point: starts the gesture recorder's controller, feeds it every
 * committed glide through the sink seam, and loads the prompt pools from the bundled dictionaries.
 * The armed indicator lives on the recorder's own screen: the debug build requests no permissions,
 * so nothing floats over other apps. None of this exists in the release APK.
 */
class GestureRecorderApp : Application() {

    override fun onCreate() {
        super.onCreate()
        GestureRecorderController.init(this)
        GlideGestureSink.current = object : GlideGestureSink {
            override fun onGlideGesture(
                path: GlidePath,
                keyRadiusPx: Float,
                layoutId: String,
                decodedTop1: String?,
            ) {
                GestureRecorderController.onGlideGesture(
                    path, keyRadiusPx, layoutId, resources.displayMetrics.densityDpi, decodedTop1)
            }
        }
        loadPromptPools()
    }

    /** Feeds the prompt pools from the bundled dictionaries themselves, off the UI thread. */
    private fun loadPromptPools() {
        thread {
            for ((layoutId, spec) in mapOf(
                PersonalSubtypes.TATAR_RU to DictionaryArtifactSpec.TATAR_TOP100K_V1,
                PersonalSubtypes.RUSSIAN to DictionaryArtifactSpec.RUSSIAN_TOP100K_V1,
            )) {
                val words = readTopWords(spec)
                if (words != null) GestureRecorderController.setPromptWords(layoutId, words)
            }
        }
    }

    private fun readTopWords(spec: DictionaryArtifactSpec): List<String>? = runCatching {
        val file = java.io.File.createTempFile("recorder-dict-", ".tdict")
        try {
            file.outputStream().use {
                TdictValidator().inflateAsset(assets.open(spec.assetPath), it, spec)
            }
            val validated = TdictValidator().validateRaw(file, spec)
            val raw = file.readBytes()
            val identity = DictionaryIdentity(
                spec.generation, validated.schemaId, validated.formatVersion, validated.rawSha256)
            TdictPrefixIndex.open(
                ByteBuffer.wrap(raw), identity, validated.entryCount, validated.rawSize,
                null, null, 0L,
            )?.topFrequentWords(GesturePromptSource.PROMPT_POOL_SIZE)
        } finally {
            file.delete()
        }
    }.getOrNull()
}
