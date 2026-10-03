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

package rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore

import android.content.Context
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.RefusedCorrectionSink

/**
 * Turns undone-correction events into refused-store mutations, under [PersonalLearningPredicate].
 * See [PersonalLearning]: the class that sees every keystroke only announces events, and the
 * decision to write lives in the package that owns the file. The predicate is the same one every
 * learning write consults, so an undo in a private field, in a postal-address field or while
 * learning is paused is never recorded.
 */
object RefusedCorrectionLearning {

    /**
     * The sink for whatever language is active at the moment of the undo. The subtype is resolved
     * per event, not per sink, exactly as in [PersonalLearning.sinkFor]: a refusal must reach its
     * own language's store. A null subtype (a layout with no dictionary) writes nothing.
     */
    @JvmStatic
    fun sinkFor(
        context: Context,
        activeSubtype: ActiveSubtypeSupplier,
        predicate: PersonalLearningPredicate,
    ): RefusedCorrectionSink = RefusedCorrectionSink { typedWord, replacement ->
        if (!predicate.mayLearn()) return@RefusedCorrectionSink
        val subtypeId = activeSubtype.get() ?: return@RefusedCorrectionSink
        RefusedCorrectionStores.storeFor(context, subtypeId).noteRefusal(typedWord, replacement)
    }
}
