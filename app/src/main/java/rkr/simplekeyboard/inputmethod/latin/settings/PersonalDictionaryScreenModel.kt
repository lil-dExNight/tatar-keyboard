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
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalWordFilter

/**
 * One word row of the "Personal dictionary" screen: a word of one language, with the key to delete
 * it by and the usage count to show beside it (U7 of Phase 2, docs/ROADMAP-P2.md).
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
    /** Says nothing on purpose: this type carries the user's words. */
    override fun toString(): String = "PersonalPairRow"
}

/**
 * Everything shown for one language: the word rows and the pair rows that survived the search and
 * the cap, plus the TRUE saved totals ([wordCount], [pairCount]) — the counts a section prints
 * must not shrink just because a query hid the rows or the cap stopped materializing them.
 */
internal class PersonalLanguageSection(
    val subtypeId: String,
    val wordRows: List<PersonalWordRow>,
    val pairRows: List<PersonalPairRow>,
    val wordCount: Int,
    val pairCount: Int,
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
 * The pure content model of the "Personal dictionary" screen — grouping, search and the row cap,
 * with no Android and no I/O, so all of it is covered by plain JVM tests.
 *
 * The performance limiter is a CAP ON ROWS, not a choice of one language, and that is not cosmetic:
 * `SettingsHostActivity` builds its content imperatively into a `ScrollView` + `LinearLayout`
 * without view reuse, and rebuilds the whole screen in `onStart` — that is, on every return to the
 * foreground and after every dialog. `RecyclerView` is not available (since O2 of
 * 2026-09-25 the app carries no androidx dependency at all, and adding `recyclerview` costs
 * on the order of a hundred kilobytes against a phase budget of 25 600 B), so the list simply
 * must not grow without bound. At a cap of 200 it does
 * not matter at all whether those rows come from one language or two, from words or from pairs.
 *
 * Showing only the active subtype was rejected for a reason that is not convenience: "erase all"
 * deletes the files of EVERY language, so a screen that shows one language would silently erase what
 * is not on it. The same holds for the two stores: the pairs of a language sit in the same section
 * as its words, because a screen that showed one store and erased both would tell the same lie.
 */
internal object PersonalDictionaryScreenModel {

    /** The maximum number of rows materialized at once, across all languages and both stores. */
    const val MAX_MATERIALIZED_ROWS = 200

    /**
     * Builds the content for [dictionaries] and [bigrams] (both in the order the languages should
     * appear) narrowed by [query].
     *
     * Matching is on the NORMALIZED form of both sides, so a search for "гүзәл" finds a saved
     * "Гүзәл"; the row still shows the saved spelling. A pair matches when EITHER its context or
     * its successor does — a search that quietly covered only the words would read as "you have
     * no such pair saved", which is a lie the user cannot detect. Filtering happens HERE, before a
     * single View exists — that is what the contract means by "the search narrows the list before
     * the views are built".
     */
    fun build(
        dictionaries: List<Pair<String, PersonalDictionary>>,
        bigrams: List<Pair<String, PersonalBigramDictionary>>,
        query: String,
    ): PersonalScreenContent {
        val normalizedQuery = PersonalWordFilter.normalize(query)
        val bigramsBySubtype = bigrams.toMap()
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
            if (wordRows.isNotEmpty() || pairRows.isNotEmpty()) {
                sections.add(
                    PersonalLanguageSection(subtypeId, wordRows, pairRows, dictionary.size, pairs.size),
                )
            }
        }

        val shown = sections.sumOf { it.wordRows.size + it.pairRows.size }
        return PersonalScreenContent(sections, shown, total)
    }
}
