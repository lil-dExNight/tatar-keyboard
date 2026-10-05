/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rkr.simplekeyboard.inputmethod.latin.settings

import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalBigramDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalEmojiDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalWordFilter

/**
 * One word row of the "Personal dictionary" screen: a word of one language, with the key to delete
 * it by and the usage count to show beside it.
 */
internal class PersonalWordRow(
    val subtypeId: String,
    val rawForm: String,
    val normalizedForm: String,
    val usageCount: Int,
) {
    /** Says nothing on purpose: this type carries the user's word. */
    override fun toString(): String = "PersonalWordRow"
}

/**
 * One pair row of the same screen: the normalized context and the successor as the user typed it,
 * with the keys to delete the pair by and the usage count to show beside it. The context exists
 * only in normalized form (the store never keeps its raw casing), so that is what the row shows.
 */
internal class PersonalPairRow(
    val subtypeId: String,
    val contextForm: String,
    val successorRawForm: String,
    val successorNormalizedForm: String,
    val usageCount: Int,
) {
    /** See [PersonalWordRow.toString]. */
    override fun toString(): String = "PersonalPairRow"
}

/**
 * One emoji row of the same screen: the normalized word (the store keeps no raw casing, so that is
 * what the row shows) and the emoji cluster as the user picked it, with the keys to delete the
 * entry by and the two counters — accepted suggestions ([usageCount]) and clean co-observations
 * ([frequencyCount]).
 */
internal class PersonalEmojiRow(
    val subtypeId: String,
    val word: String,
    val emoji: String,
    val usageCount: Int,
    val frequencyCount: Int,
) {
    /** See [PersonalWordRow.toString]. */
    override fun toString(): String = "PersonalEmojiRow"
}

/**
 * Everything shown for one language: the word, pair and emoji rows that survived the search and
 * the cap, plus the true saved totals ([wordCount], [pairCount], [emojiCount]). A section's counts
 * must not shrink because a query hid rows or the cap stopped materializing them.
 */
internal class PersonalLanguageSection(
    val subtypeId: String,
    val wordRows: List<PersonalWordRow>,
    val pairRows: List<PersonalPairRow>,
    val wordCount: Int,
    val pairCount: Int,
    val emojiRows: List<PersonalEmojiRow> = emptyList(),
    val emojiCount: Int = 0,
)

/**
 * What the screen materializes: the sections to show, how many rows that is ([shownCount]) and how
 * many matched in total ([totalCount]). The two differ exactly when the cap trims the list, and the
 * screen says so in a "showing N of M" row rather than silently truncating.
 */
internal class PersonalScreenContent(
    val sections: List<PersonalLanguageSection>,
    val shownCount: Int,
    val totalCount: Int,
) {
    val isTruncated: Boolean
        get() = shownCount < totalCount
}

/**
 * The pure content model of the "Personal dictionary" screen: grouping, search and the row cap,
 * with no Android and no I/O, so it is covered by plain JVM tests.
 *
 * The performance limit is a cap on rows: `SettingsHostActivity` builds the screen imperatively in
 * a `ScrollView` without view reuse and rebuilds it in `onStart`, and the app has no androidx
 * dependency (no `RecyclerView`), so the list must not grow without bound. Every language and all
 * three stores are shown, because "erase all" deletes the files of every language and store: the
 * screen never erases anything it does not show.
 */
internal object PersonalDictionaryScreenModel {

    /** The maximum number of rows materialized at once, across all languages and all three stores. */
    const val MAX_MATERIALIZED_ROWS = 200

    /**
     * Builds the content for [dictionaries], [bigrams] and [emoji] (all in the order the languages
     * should appear) narrowed by [query].
     *
     * Matching is on the normalized form of both sides, so a search for "гүзәл" finds a saved
     * "Гүзәл"; the row still shows the saved spelling. A pair matches when either its context or
     * its successor does; an emoji entry matches on its word only (the emoji is not searchable
     * text). Filtering happens here, before any View exists.
     */
    fun build(
        dictionaries: List<Pair<String, PersonalDictionary>>,
        bigrams: List<Pair<String, PersonalBigramDictionary>>,
        query: String,
        emoji: List<Pair<String, PersonalEmojiDictionary>> = emptyList(),
    ): PersonalScreenContent {
        val normalizedQuery = PersonalWordFilter.normalize(query)
        val bigramsBySubtype = bigrams.toMap()
        val emojiBySubtype = emoji.toMap()
        var total = 0
        var remaining = MAX_MATERIALIZED_ROWS
        val sections = ArrayList<PersonalLanguageSection>(dictionaries.size)

        for ((subtypeId, dictionary) in dictionaries) {
            val wordRows = ArrayList<PersonalWordRow>()
            // The snapshot is already ordered by normalized form ascending, which is the order the
            // sections want, so no sorting happens here.
            for (index in 0 until dictionary.size) {
                val normalized = dictionary.normalizedFormAt(index)
                if (normalizedQuery.isNotEmpty() && !normalized.contains(normalizedQuery)) continue
                total++
                if (remaining <= 0) continue
                remaining--
                wordRows.add(
                    PersonalWordRow(
                        subtypeId, dictionary.rawFormAt(index), normalized,
                        dictionary.usageCountAt(index),
                    ),
                )
            }
            val pairRows = ArrayList<PersonalPairRow>()
            val pairs = bigramsBySubtype[subtypeId] ?: PersonalBigramDictionary.EMPTY
            // Ordered by the pair key ascending: context groups stay together, successors sorted
            // inside each — the readable order for a list of "A → B" rows.
            for (index in 0 until pairs.size) {
                val context = pairs.contextAt(index)
                val successorNormalized = pairs.successorNormalizedFormAt(index)
                if (normalizedQuery.isNotEmpty() &&
                    !context.contains(normalizedQuery) &&
                    !successorNormalized.contains(normalizedQuery)
                ) {
                    continue
                }
                total++
                if (remaining <= 0) continue
                remaining--
                pairRows.add(
                    PersonalPairRow(
                        subtypeId, context, pairs.successorRawFormAt(index), successorNormalized,
                        pairs.usageCountAt(index),
                    ),
                )
            }
            val emojiRows = ArrayList<PersonalEmojiRow>()
            val learned = emojiBySubtype[subtypeId] ?: PersonalEmojiDictionary.EMPTY
            // Ordered by the entry key ascending: word groups stay together, emoji sorted inside
            // each — the readable order for a list of "word → emoji" rows.
            for (index in 0 until learned.size) {
                val word = learned.wordAt(index)
                if (normalizedQuery.isNotEmpty() && !word.contains(normalizedQuery)) continue
                total++
                if (remaining <= 0) continue
                remaining--
                emojiRows.add(
                    PersonalEmojiRow(
                        subtypeId, word, learned.emojiAt(index),
                        learned.usageCountAt(index), learned.frequencyCountAt(index),
                    ),
                )
            }
            if (wordRows.isNotEmpty() || pairRows.isNotEmpty() || emojiRows.isNotEmpty()) {
                sections.add(
                    PersonalLanguageSection(
                        subtypeId, wordRows, pairRows, dictionary.size, pairs.size,
                        emojiRows, learned.size,
                    ),
                )
            }
        }

        val shown = sections.sumOf { it.wordRows.size + it.pairRows.size + it.emojiRows.size }
        return PersonalScreenContent(sections, shown, total)
    }
}
