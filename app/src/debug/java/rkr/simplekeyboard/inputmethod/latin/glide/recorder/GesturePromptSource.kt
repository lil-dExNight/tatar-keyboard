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

package rkr.simplekeyboard.inputmethod.latin.glide.recorder

import java.util.Random

/**
 * The prompt words of the prompted-word mode. The only legitimate list is the bundled
 * dictionary's top-frequency read (`topFrequentWords`, capped at [PROMPT_POOL_SIZE]) for the
 * active layout's language; the recorder never shows, reads, or stores anything the user typed.
 * Prompts are drawn uniformly at random, independently per draw (immediate repeats can happen).
 */
class GesturePromptSource(words: List<String>, private val random: Random = Random()) {
    private val words = words.filter { it.isNotBlank() }.distinct()

    val isEmpty: Boolean get() = words.isEmpty()

    /** The next prompt, or null when the pool is empty (the recorder refuses to arm then). */
    fun next(): String? = if (words.isEmpty()) null else words[random.nextInt(words.size)]

    companion object {
        /** The prompt pool is the dictionary's top-frequency read truncated to this size. */
        const val PROMPT_POOL_SIZE = 5000
    }
}
