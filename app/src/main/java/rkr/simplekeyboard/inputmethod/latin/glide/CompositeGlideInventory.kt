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

package rkr.simplekeyboard.inputmethod.latin.glide

import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalDictionary

/**
 * The glide inventory of one engine with the user's personal dictionary appended: all base
 * entries in base order, then the personal words that are not dictionary words (usage count
 * descending, normalized form ascending). The decoder and the word index see a plain
 * [GlideWordInventory].
 *
 * Ranking:
 *  - Personal words never reorder dictionary words. A personal word's frequency is
 *    `max(1, baseMax * usageCount / maxUsageCount)`, so it never exceeds the base maximum and
 *    [GlideWordIndex]'s `maxFrequency` (the frequency normalizer) is unchanged. The user's
 *    most-used word ties the strongest dictionary prior; between weaker words the shape
 *    channel decides.
 *  - A personal word that is already a dictionary word ([baseMembership]) is not indexed twice.
 *    If its saved raw form differs (the user's casing), [wordAt] of the dictionary entry
 *    returns the saved form: one cell with the user's spelling, as in word completion.
 *
 * [forEachWord] yields normalized forms (the decoder lowercases letters anyway); [wordAt]
 * returns the raw saved spelling, so the strip's casing pass sees what a word completion shows.
 *
 * Not a data class and without [toString]: the arrays hold the user's words, which must never
 * reach a log. Words with a letter the layout has no key for are skipped by [GlideWordIndex.build].
 */
class CompositeGlideInventory(
    private val base: GlideWordInventory,
    personal: PersonalDictionary,
    baseMembership: (String) -> Boolean,
) : GlideWordInventory {
    // The kept personal entries, usage-descending / normalized-ascending; strings are the
    // snapshot's own (shared, never copied).
    private val personalRawForms: Array<String>
    private val personalNormalizedForms: Array<String>
    private val personalUsageCounts: IntArray

    /** Normalized dictionary form -> the user's saved casing, for the duplicates only. */
    private val casingOverrides: Map<String, String>

    init {
        val kept = ArrayList<Int>(personal.size)
        val overrides = HashMap<String, String>()
        for (index in 0 until personal.size) {
            val normalized = personal.normalizedFormAt(index)
            if (baseMembership(normalized)) {
                val raw = personal.rawFormAt(index)
                if (raw != normalized) overrides[normalized] = raw
            } else {
                kept.add(index)
            }
        }
        // The snapshot's arrays are normalized-ascending, so a stable usage-descending sort
        // yields exactly the personal order (usage count descending, normalized ascending).
        val ordered = kept.sortedWith(
            compareByDescending<Int> { personal.usageCountAt(it) }.thenBy { it },
        )
        personalRawForms = Array(ordered.size) { personal.rawFormAt(ordered[it]) }
        personalNormalizedForms = Array(ordered.size) { personal.normalizedFormAt(ordered[it]) }
        personalUsageCounts = IntArray(ordered.size) { personal.usageCountAt(ordered[it]) }
        casingOverrides = overrides
    }

    override val entryCount: Int get() = base.entryCount + personalRawForms.size

    override fun wordAt(index: Int): String {
        if (index >= base.entryCount) return personalRawForms[index - base.entryCount]
        val word = base.wordAt(index)
        return casingOverrides[word] ?: word
    }

    override fun forEachWord(visitor: GlideWordVisitor) {
        var baseMax = 0L
        base.forEachWord { word, frequency ->
            if (frequency > baseMax) baseMax = frequency
            visitor.visit(word, frequency)
        }
        if (personalRawForms.isEmpty()) return
        var maxUsage = 1
        for (usage in personalUsageCounts) if (usage > maxUsage) maxUsage = usage
        for (index in personalNormalizedForms.indices) {
            // Long arithmetic: baseMax is a u32 and the usage count an int, so the product
            // cannot overflow. The floor of 1 keeps the strictly-positive contract even when
            // the base inventory is empty (baseMax == 0).
            val frequency = maxOf(1L, baseMax * personalUsageCounts[index] / maxUsage)
            visitor.visit(personalNormalizedForms[index], frequency)
        }
    }
}
