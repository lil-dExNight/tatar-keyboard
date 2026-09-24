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

package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import rkr.simplekeyboard.inputmethod.latin.glide.GlideWordInventory
import rkr.simplekeyboard.inputmethod.latin.glide.GlideWordVisitor

/**
 * A shipped dictionary's prefix index as a [GlideWordInventory] — the decode-side dictionary
 * integration of P7-1 (docs/GLIDE-PLAN.md). The word-frequency content comes from
 * [TdictPrefixIndex.forEachWordCold], a cold sequential walk with its own local state, so the
 * glide index build never touches the per-keystroke lookup path or its budgets; [wordAt] shares
 * the index's worker confinement (it serves the decode's result materialization, which runs on
 * the same engine worker).
 */
internal class TdictGlideInventory(
    private val index: TdictPrefixIndex,
) : GlideWordInventory {
    override val entryCount: Int get() = index.entryCount

    override fun wordAt(index: Int): String = this.index.wordAt(index)

    override fun forEachWord(visitor: GlideWordVisitor) {
        index.forEachWordCold(
            ColdWordVisitor { word, frequency -> visitor.visit(word, frequency) },
        )
    }
}
