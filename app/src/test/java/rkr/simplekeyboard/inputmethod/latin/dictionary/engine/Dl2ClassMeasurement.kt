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

import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.BigramArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TatBigrValidator
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarSuffixRules
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarWordUtils
import java.io.File
import java.nio.ByteBuffer

/**
 * Host-only measurement bridge for the six reviewed letter classes (ә/е, ө/о, ү/у, ң/н, һ/х and
 * the Russian е→ё). The Python front end selects the reviewed records and writes a TSV batch;
 * this main replays every record through the two arms and reports paired per-record outcomes,
 * probe counters and host timings.
 *
 * The baseline arm is the shipped engine wiring: the production prefix index with the TATAR (or
 * RUSSIAN) fuzzy policy, plus the reviewed exact corrections of [TatarWordUtils] for Tatar.
 * Ranking, autocorrect advice and the typo-recovery budgets are never reimplemented — the bridge
 * reads the same index surface the production engines use, including the context-armed lookup
 * with successor ids resolved through the shipped bigram table.
 *
 * The candidate arm adds exactly one thing per batch, the class under test:
 *  - display: the class's single-letter substitution variants that are dictionary words rank with
 *    the engine's own same-length rule (before longer continuations, frequency descending, then
 *    code-point order) ahead of the baseline candidates — `dl2-candidate-v1`;
 *  - autocorrect: when the production advice is absent, the class fires only on a single
 *    unambiguous variant word of at least [AutocorrectPolicy.MIN_WORD_CODE_POINTS] code points,
 *    at or above the language's candidate floor, and only when the companion language does not
 *    spell the typed word. With armed bigram successors the class match is additionally held to
 *    the same context-successor guard as the DL-1 tier — `dl2-advice-v1`.
 *
 * The reviewed exact corrections always win in both arms. Protected words are measured with an
 * unarmed lookup and, when the record has a predecessor that resolves successors, an armed one;
 * a fire in either path is reported.
 *
 * This source is compiled as part of the app's JVM-test source set but runs only through the
 * `dl2Benchmark` task of `research/dl2-evaluation/dl2-benchmark.init.gradle`; a plain `test` run
 * never executes it and it reads nothing unless the environment is set. Environment contract:
 *   DL2_BENCHMARK_INPUT  batch TSV (header + one row per record)
 *   DL2_BENCHMARK_OUTPUT result TSV (header + one row per record + a metadata row)
 *   DL2_BENCHMARK_ROOT   repository root (dictionary and bigram asset lookup)
 */
object Dl2ClassMeasurement {
    /** The engine result list holds at most this many entries; the visible strip shows three. */
    internal const val ENGINE_RESULT_LIMIT = 4
    internal const val VISIBLE_TOP_K = 3

    @JvmStatic
    fun main(args: Array<String>) {
        val input = requiredPath("DL2_BENCHMARK_INPUT")
        val output = requiredPath("DL2_BENCHMARK_OUTPUT")
        val root = File(System.getenv("DL2_BENCHMARK_ROOT") ?: error("DL2_BENCHMARK_ROOT is required"))
        val rows = readBatch(input)
        require(rows.isNotEmpty()) { "the batch has no records" }
        val engines = Engines(root)
        try {
            for (row in rows) engines.check(row)
            for (row in rows) engines.measure(row, ruleOf(row)) // warm pass, untimed
            output.parentFile?.mkdirs()
            output.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(RESULT_HEADER.joinToString("\t"))
                writer.write("\n")
                for (row in rows) writer.write(engines.measure(row, ruleOf(row)).toTsvRow())
                writer.write("metadata\t")
                writer.write(Json.write(engines.metadata(rows)))
                writer.write("\n")
            }
        } finally {
            engines.close()
        }
    }

    private fun ruleOf(row: BatchRow): ClassRule =
        requireNotNull(Dl2ClassRules.BY_SLUG[row.classSlug]) { "unknown class ${row.classSlug}" }

    internal data class BatchRow(
        val index: Int,
        val id: String,
        val kind: String,
        val language: String,
        val word: String,
        val correction: String,
        val predecessor: String,
        val classSlug: String,
    )

    internal val BATCH_HEADER = listOf(
        "record_index", "id", "kind", "language", "word", "correction", "predecessor", "class",
    )
    internal val RESULT_HEADER = listOf(
        "record_index", "id", "kind", "predecessor", "word_in_dictionary",
        "baseline_hit", "candidate_hit", "baseline_hit4", "candidate_hit4",
        "baseline_advice", "candidate_advice", "advice_source",
        "armed_successors", "armed_baseline_advice", "armed_candidate_advice", "guard_blocked",
        "class_variants", "class_frequency_probes",
        "baseline_over_budget", "candidate_over_budget",
        "baseline_fuzzy_probes", "candidate_fuzzy_probes",
        "baseline_autocorrect_probes", "candidate_autocorrect_probes",
        "baseline_ns", "candidate_ns",
        "baseline_candidates", "candidate_candidates",
        "baseline_fires", "candidate_fires",
    )

    internal fun readBatch(file: File): List<BatchRow> {
        val lines = file.readLines(Charsets.UTF_8)
        require(lines.isNotEmpty()) { "empty batch ${file.path}" }
        require(lines[0].split("\t") == BATCH_HEADER) { "unexpected batch header" }
        return lines.drop(1).filter { it.isNotEmpty() }.mapIndexed { position, line ->
            val fields = line.split("\t")
            require(fields.size == BATCH_HEADER.size) { "malformed batch line ${position + 2}" }
            BatchRow(
                index = fields[0].toInt(),
                id = fields[1],
                kind = fields[2].also { require(it in KINDS) { "unknown kind $it" } },
                language = fields[3].also { require(it in LANGUAGES) { "unknown language $it" } },
                word = checkedNormalized(fields[4]),
                correction = if (fields[5].isEmpty()) "" else checkedNormalized(fields[5]),
                predecessor = if (fields[6].isEmpty()) "" else checkedNormalized(fields[6]),
                classSlug = fields[7],
            )
        }
    }

    internal val KINDS = setOf("recovery", "control", "safety", "baseline_pair")
    internal val LANGUAGES = setOf("tat", "rus")

    /** The batch must arrive in the exact NFC lowercase form the engines normalize to. */
    internal fun checkedNormalized(word: String): String {
        require(word.isNotEmpty()) { "empty word" }
        require(word == TatarWordUtils.normalizeForLookup(word)) {
            "word is not in the normalized lookup form: $word"
        }
        return word
    }

    private fun requiredPath(name: String): File =
        File(System.getenv(name) ?: error("$name is required")).absoluteFile

    /**
     * One class under test. [substitutions] is directed (from, to); the five Tatar classes are
     * symmetric letter pairs, е→ё is one-directional.
     */
    internal class ClassRule(val slug: String, val language: String, val substitutions: List<Pair<Char, Char>>)

    /** The class catalog; the slug keys match the review worksheet slugs. */
    internal object Dl2ClassRules {
        val BY_SLUG: Map<String, ClassRule> = listOf(
            ClassRule("ae", "tat", listOf('ә' to 'е', 'е' to 'ә')),
            ClassRule("oe", "tat", listOf('ө' to 'о', 'о' to 'ө')),
            ClassRule("ue", "tat", listOf('ү' to 'у', 'у' to 'ү')),
            ClassRule("ng", "tat", listOf('ң' to 'н', 'н' to 'ң')),
            ClassRule("hkh", "tat", listOf('һ' to 'х', 'х' to 'һ')),
            ClassRule("ye-yo", "rus", listOf('е' to 'ё')),
        ).associateBy { it.slug }
    }

    /** A class variant confirmed as a dictionary word, with its whole-word frequency. */
    internal data class VariantHit(val word: String, val frequency: Long)

    /**
     * Deterministic class semantics, kept pure so the focused tests run without the committed
     * assets. All the letters involved are BMP, so Char indexing equals code-point indexing.
     */
    internal object Dl2ClassSemantics {
        /** One variant per (position, rule); duplicates removed, the typed word excluded. */
        fun classVariants(word: String, substitutions: List<Pair<Char, Char>>): List<String> {
            val variants = LinkedHashSet<String>()
            for (position in word.indices) {
                for ((from, to) in substitutions) {
                    if (word[position] != from) continue
                    val variant = word.substring(0, position) + to + word.substring(position + 1)
                    if (variant != word) variants.add(variant)
                }
            }
            return variants.toList()
        }

        /** Class variants that are dictionary words; every [WordFrequencySource] probe is counted. */
        fun dictionaryVariants(
            index: WordFrequencySource,
            variants: List<String>,
        ): Pair<List<VariantHit>, Int> {
            val hits = ArrayList<VariantHit>(variants.size)
            for (variant in variants) {
                val frequency = index.frequencyOf(variant)
                if (frequency > 0L) hits.add(VariantHit(variant, frequency))
            }
            return hits to variants.size
        }

        /**
         * The engine's own tie-break: frequency descending, then code-point order. Every letter
         * involved is BMP, so UTF-16 [compareTo] equals code-point order.
         */
        fun rankVariants(variants: List<VariantHit>): List<String> =
            variants.sortedWith(compareByDescending<VariantHit> { it.frequency }.thenBy { it.word }).map { it.word }

        /**
         * `dl2-candidate-v1`: exact production candidates are never displaced. The baseline list
         * keeps its production order and class variants fill only the cells the baseline leaves
         * free, in their ranked order. The merged list is capped at the engine result size.
         */
        fun mergedCandidates(variantWords: List<String>, baseline: List<String>): List<String> {
            val merged = ArrayList<String>(ENGINE_RESULT_LIMIT)
            for (candidate in baseline) if (candidate !in merged && merged.size < ENGINE_RESULT_LIMIT) merged.add(candidate)
            for (variant in variantWords) if (variant !in merged && merged.size < ENGINE_RESULT_LIMIT) merged.add(variant)
            return merged
        }

        /** Visible-top-k hit predicate; recovery is measured at k=3, never over the whole list. */
        fun visibleHit(candidates: List<String>, target: String, k: Int): Int =
            if (target.isNotEmpty() && candidates.take(k).contains(target)) 1 else 0

        /**
         * `dl2-advice-v1`: the class fires only when the production advice is absent (checked by
         * the caller), the typed word is absent from the dictionary (the same condition as
         * [TdictPrefixIndex.computeAutocorrectAdvice]), the word is long enough, the companion
         * language does not spell it, exactly one variant is a dictionary word, and that word
         * meets the language's candidate floor. With armed successors, [isArmedSuccessor] must
         * also accept the replacement (the DL-1 tier's context-successor guard); an unarmed
         * lookup applies no guard.
         */
        fun classAdvice(
            word: String,
            variants: List<VariantHit>,
            companionContainsWord: Boolean,
            typedWordInDictionary: Boolean,
            minCandidateFrequency: Long,
            armed: Boolean,
            isArmedSuccessor: (String) -> Boolean,
        ): String? {
            if (typedWordInDictionary) return null
            if (word.codePointCount(0, word.length) < AutocorrectPolicy.MIN_WORD_CODE_POINTS) return null
            if (companionContainsWord) return null
            if (variants.size != 1) return null
            val only = variants.single()
            if (only.frequency < minCandidateFrequency) return null
            if (armed && !isArmedSuccessor(only.word)) return null
            return only.word
        }
    }

    private class Engines(private val root: File) {
        private var tatar: TdictPrefixIndex? = null
        private var russian: TdictPrefixIndex? = null
        private var tatarBigram: TatBigrPrefixIndex? = null
        private var russianBigram: TatBigrPrefixIndex? = null
        private val tatarSpec = DictionaryArtifactSpec.TATAR_TOP100K_V1
        private val russianSpec = DictionaryArtifactSpec.RUSSIAN_TOP100K_V1
        private val successorSink = IntArray(TatBigrPrefixIndex.MAX_CONTEXT_SUCCESSORS)

        private val tatarNeighborTable = TatarLayoutTestFixtures.tatarNeighborTable()
        private val russianNeighborTable = KeyNeighborTable.build(
            "ru",
            true,
            listOf(
                rawKey('й'), rawKey('ц'), rawKey('у', 'ү'), rawKey('к'), rawKey('е', 'ё'),
                rawKey('н', 'ң'), rawKey('г', 'һ'), rawKey('ш'), rawKey('щ'), rawKey('з'),
                rawKey('х', 'һ'), rawKey('ф'), rawKey('ы'), rawKey('в'), rawKey('а', 'ә'),
                rawKey('п'), rawKey('р'), rawKey('о', 'ө'), rawKey('л'), rawKey('д'),
                rawKey('ж', 'җ'), rawKey('э', 'ә'), rawKey('я'), rawKey('ч'), rawKey('с'),
                rawKey('м'), rawKey('и'), rawKey('т'), rawKey('ь', 'ъ'), rawKey('б'), rawKey('ю'),
            ),
        )

        fun check(row: BatchRow) {
            val rule = ruleOf(row)
            require(rule.language == row.language) {
                "record ${row.id}: language ${row.language} does not match class ${rule.language}"
            }
            require(row.kind != "recovery" && row.kind != "control" || row.correction.isNotEmpty()) {
                "recovery record ${row.id} has no correction"
            }
        }

        fun index(language: String): TdictPrefixIndex = when (language) {
            "tat" -> tatar ?: openTatar().also { tatar = it }
            else -> russian ?: openRussian().also { russian = it }
        }

        private fun bigram(language: String): TatBigrPrefixIndex = when (language) {
            "tat" -> tatarBigram ?: openBigram(BigramArtifactSpec.TATAR_BIGRAMS_V2, index("tat")).also { tatarBigram = it }
            else -> russianBigram ?: openBigram(BigramArtifactSpec.RUSSIAN_BIGRAMS_V1, index("rus")).also { russianBigram = it }
        }

        private fun openTatar(): TdictPrefixIndex = open(
            tatarSpec, TatarSuffixRules, FuzzyEditPolicy.TATAR, AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_TT,
        ).also { it.updateKeyNeighbors(tatarNeighborTable) }

        private fun openRussian(): TdictPrefixIndex = open(
            russianSpec, null, FuzzyEditPolicy.RUSSIAN, AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_RU,
        ).also { it.updateKeyNeighbors(russianNeighborTable) }

        private fun open(
            spec: DictionaryArtifactSpec,
            suffixTable: InflectedSuffixTable?,
            policy: FuzzyEditPolicy,
            floor: Long,
        ): TdictPrefixIndex {
            val asset = File(root, "app/src/main/assets/${spec.assetPath}")
            check(asset.isFile) { "missing dictionary asset ${asset.path}" }
            val rawFile = File.createTempFile("dl2-measure-", ".tdict")
            try {
                rawFile.outputStream().use { output ->
                    TdictValidator().inflateAsset(asset.inputStream(), output, spec)
                }
                val validated = TdictValidator().validateRaw(rawFile, spec)
                val identity = DictionaryIdentity(
                    spec.generation, validated.schemaId, validated.formatVersion, validated.rawSha256,
                )
                return requireNotNull(
                    TdictPrefixIndex.open(
                        ByteBuffer.wrap(rawFile.readBytes()), identity,
                        validated.entryCount, validated.rawSize, suffixTable, policy, floor,
                    ),
                )
            } finally {
                rawFile.delete()
            }
        }

        private fun openBigram(spec: BigramArtifactSpec, dictionary: TdictPrefixIndex): TatBigrPrefixIndex {
            val asset = File(root, "app/src/main/assets/${spec.assetPath}")
            check(asset.isFile) { "missing bigram asset ${asset.path}" }
            val rawFile = File.createTempFile("dl2-measure-", ".tatbigr")
            try {
                rawFile.outputStream().use { output ->
                    TatBigrValidator().inflateAsset(asset.inputStream(), output, spec)
                }
                val validated = TatBigrValidator().validateRaw(rawFile, spec)
                val identity = BigramTableIdentity(
                    spec.generation, spec.fileLanguageTag, validated.schemaId,
                    validated.formatVersion, validated.rawSha256,
                )
                return requireNotNull(
                    TatBigrPrefixIndex.open(
                        ByteBuffer.wrap(rawFile.readBytes()), identity, dictionary,
                        validated.headCount, validated.rawSize,
                    ),
                )
            } finally {
                rawFile.delete()
            }
        }

        private fun floor(language: String): Long = if (language == "tat") {
            AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_TT
        } else {
            AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_RU
        }

        /** The production exact-pair baseline; Tatar only, exactly as the shipped engine applies it. */
        private fun productionAdviceFor(language: String, index: TdictPrefixIndex, word: String): String? {
            if (language != "tat") return index.lastAutocorrectAdvice?.replacement
            return TatarWordUtils.explicitAutocorrection(word) ?: index.lastAutocorrectAdvice?.replacement
        }

        private fun lookup(index: TdictPrefixIndex, word: String, armed: Boolean): List<String> =
            if (armed) {
                index.lookup(ImmutableUtf8Prefix.copyOf(TatarWordUtils.toLookupBytes(word)), successorSink, successorCount)
            } else {
                index.lookup(ImmutableUtf8Prefix.copyOf(TatarWordUtils.toLookupBytes(word)))
            }

        private var successorCount = 0

        fun measure(row: BatchRow, rule: ClassRule): ResultRow {
            val index = index(row.language)
            val companion = if (row.language == "tat") index("rus") else index("tat")
            successorCount = if (row.kind == "safety" && row.predecessor.isNotEmpty()) {
                bigram(row.language).collectSuccessorIds(
                    ImmutableUtf8Prefix.copyOf(TatarWordUtils.toLookupBytes(row.predecessor)), successorSink,
                )
            } else {
                0
            }
            val armed = successorCount > 0
            val successorIds = if (armed) successorSink.copyOf(successorCount) else IntArray(0)
            val isSuccessor: (String) -> Boolean = { word ->
                val bytes = TatarWordUtils.toLookupBytes(word)
                val entry = index.indexOfWord(bytes, bytes.size)
                entry >= 0 && (0 until successorCount).any { successorIds[it] == entry }
            }

            // Baseline arm: production behavior only, unarmed and (for protected rows) armed.
            val baselineStarted = System.nanoTime()
            val baselineCandidates = lookup(index, row.word, false)
            val baselineAdvice = productionAdviceFor(row.language, index, row.word)
            var baselineOverBudget = index.lastFuzzyOverBudget
            var baselineFuzzyProbes = index.lastFuzzyProbeCount
            var baselineAutocorrectProbes = index.lastAutocorrectProbeCount
            val armedBaselineAdvice = if (armed) {
                lookup(index, row.word, true)
                baselineOverBudget = baselineOverBudget || index.lastFuzzyOverBudget
                baselineFuzzyProbes += index.lastFuzzyProbeCount
                baselineAutocorrectProbes += index.lastAutocorrectProbeCount
                productionAdviceFor(row.language, index, row.word)
            } else {
                null
            }
            val baselineNs = System.nanoTime() - baselineStarted

            // Candidate arm: production behavior, then the class under test, same lookups.
            val candidateStarted = System.nanoTime()
            val candidateBaseline = lookup(index, row.word, false)
            val productionAdvice = productionAdviceFor(row.language, index, row.word)
            val variants = Dl2ClassSemantics.classVariants(row.word, rule.substitutions)
            val (variantHits, frequencyProbes) = Dl2ClassSemantics.dictionaryVariants(index, variants)
            val ranked = Dl2ClassSemantics.rankVariants(variantHits)
            val candidateCandidates = Dl2ClassSemantics.mergedCandidates(ranked, candidateBaseline)
            val companionContains = companion.containsWordCold(row.word)
            val typedWordInDictionary = index.frequencyOf(row.word) > 0L
            val classAdvice = Dl2ClassSemantics.classAdvice(
                row.word, variantHits, companionContains, typedWordInDictionary,
                floor(row.language), false, isSuccessor,
            )
            val candidateAdvice = productionAdvice ?: classAdvice
            var candidateOverBudget = index.lastFuzzyOverBudget
            var candidateFuzzyProbes = index.lastFuzzyProbeCount
            var candidateAutocorrectProbes = index.lastAutocorrectProbeCount
            val armedClassAdvice: String?
            val armedCandidateAdvice: String?
            if (armed) {
                lookup(index, row.word, true)
                candidateOverBudget = candidateOverBudget || index.lastFuzzyOverBudget
                candidateFuzzyProbes += index.lastFuzzyProbeCount
                candidateAutocorrectProbes += index.lastAutocorrectProbeCount
                val armedProduction = productionAdviceFor(row.language, index, row.word)
                armedClassAdvice = Dl2ClassSemantics.classAdvice(
                    row.word, variantHits, companionContains, typedWordInDictionary,
                    floor(row.language), true, isSuccessor,
                )
                armedCandidateAdvice = armedProduction ?: armedClassAdvice
            } else {
                armedClassAdvice = null
                armedCandidateAdvice = null
            }
            val candidateNs = System.nanoTime() - candidateStarted
            val adviceSource = when {
                productionAdvice != null -> "baseline"
                classAdvice != null -> "class"
                else -> "none"
            }

            return ResultRow(
                row = row,
                wordInDictionary = typedWordInDictionary,
                baselineHit = Dl2ClassSemantics.visibleHit(baselineCandidates, row.correction, VISIBLE_TOP_K),
                candidateHit = Dl2ClassSemantics.visibleHit(candidateCandidates, row.correction, VISIBLE_TOP_K),
                baselineHit4 = Dl2ClassSemantics.visibleHit(baselineCandidates, row.correction, ENGINE_RESULT_LIMIT),
                candidateHit4 = Dl2ClassSemantics.visibleHit(candidateCandidates, row.correction, ENGINE_RESULT_LIMIT),
                baselineAdvice = baselineAdvice,
                candidateAdvice = candidateAdvice,
                adviceSource = adviceSource,
                armedSuccessors = successorCount,
                armedBaselineAdvice = armedBaselineAdvice,
                armedCandidateAdvice = armedCandidateAdvice,
                guardBlocked = armed && classAdvice != null && armedClassAdvice == null,
                classVariants = ranked.joinToString("|") { variant ->
                    val frequency = variantHits.first { it.word == variant }.frequency
                    "$variant:$frequency"
                },
                classFrequencyProbes = frequencyProbes,
                baselineOverBudget = baselineOverBudget,
                candidateOverBudget = candidateOverBudget,
                baselineFuzzyProbes = baselineFuzzyProbes,
                candidateFuzzyProbes = candidateFuzzyProbes,
                baselineAutocorrectProbes = baselineAutocorrectProbes,
                candidateAutocorrectProbes = candidateAutocorrectProbes,
                baselineNs = baselineNs,
                candidateNs = candidateNs,
                baselineCandidates = baselineCandidates,
                candidateCandidates = candidateCandidates,
                baselineFires = row.correction.isNotEmpty() && baselineAdvice == row.correction,
                candidateFires = row.correction.isNotEmpty() && candidateAdvice == row.correction,
            )
        }

        fun metadata(rows: List<BatchRow>): Map<String, Any> {
            val classSlug = rows.firstOrNull()?.classSlug ?: ""
            val rule = Dl2ClassRules.BY_SLUG.getValue(classSlug)
            return mapOf(
                "class_slug" to rule.slug,
                "language" to rule.language,
                "substitutions" to rule.substitutions.map { "${it.first}>${it.second}" },
                "candidate_semantics" to "dl2-candidate-v1",
                "advice_semantics" to "dl2-advice-v1",
                "min_word_code_points" to AutocorrectPolicy.MIN_WORD_CODE_POINTS,
                "tatar_floor" to AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_TT,
                "russian_floor" to AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_RU,
                "dictionaries" to listOf(
                    mapOf(
                        "language" to "tat",
                        "asset" to tatarSpec.assetPath,
                        "generation" to tatarSpec.generation,
                        "raw_sha256" to tatarSpec.expectedRawSha256,
                        "entry_count" to tatarSpec.expectedEntryCount,
                        "bigram_asset" to BigramArtifactSpec.TATAR_BIGRAMS_V2.assetPath,
                        "bigram_raw_sha256" to BigramArtifactSpec.TATAR_BIGRAMS_V2.expectedRawSha256,
                    ),
                    mapOf(
                        "language" to "rus",
                        "asset" to russianSpec.assetPath,
                        "generation" to russianSpec.generation,
                        "raw_sha256" to russianSpec.expectedRawSha256,
                        "entry_count" to russianSpec.expectedEntryCount,
                        "bigram_asset" to BigramArtifactSpec.RUSSIAN_BIGRAMS_V1.assetPath,
                        "bigram_raw_sha256" to BigramArtifactSpec.RUSSIAN_BIGRAMS_V1.expectedRawSha256,
                    ),
                ),
                "visible_top_k" to VISIBLE_TOP_K,
                "engine_result_limit" to ENGINE_RESULT_LIMIT,
            )
        }

        fun close() {
            tatar = null
            russian = null
            tatarBigram = null
            russianBigram = null
        }

        private fun rawKey(vararg letters: Char): KeyNeighborTable.RawKey =
            KeyNeighborTable.RawKey(letters[0].code, letters.drop(1).map { it.code }.toIntArray())
    }

    internal data class ResultRow(
        val row: BatchRow,
        val wordInDictionary: Boolean,
        val baselineHit: Int,
        val candidateHit: Int,
        val baselineHit4: Int,
        val candidateHit4: Int,
        val baselineAdvice: String?,
        val candidateAdvice: String?,
        val adviceSource: String,
        val armedSuccessors: Int,
        val armedBaselineAdvice: String?,
        val armedCandidateAdvice: String?,
        val guardBlocked: Boolean,
        val classVariants: String,
        val classFrequencyProbes: Int,
        val baselineOverBudget: Boolean,
        val candidateOverBudget: Boolean,
        val baselineFuzzyProbes: Int,
        val candidateFuzzyProbes: Int,
        val baselineAutocorrectProbes: Int,
        val candidateAutocorrectProbes: Int,
        val baselineNs: Long,
        val candidateNs: Long,
        val baselineCandidates: List<String>,
        val candidateCandidates: List<String>,
        val baselineFires: Boolean,
        val candidateFires: Boolean,
    ) {
        fun toTsvRow(): String = listOf(
            row.index, row.id, row.kind, row.predecessor, int(wordInDictionary),
            baselineHit, candidateHit, baselineHit4, candidateHit4,
            baselineAdvice ?: "", candidateAdvice ?: "", adviceSource,
            armedSuccessors, armedBaselineAdvice ?: "", armedCandidateAdvice ?: "", int(guardBlocked),
            classVariants, classFrequencyProbes,
            int(baselineOverBudget), int(candidateOverBudget),
            baselineFuzzyProbes, candidateFuzzyProbes,
            baselineAutocorrectProbes, candidateAutocorrectProbes,
            baselineNs, candidateNs,
            baselineCandidates.joinToString("|"), candidateCandidates.joinToString("|"),
            int(baselineFires), int(candidateFires),
        ).joinToString("\t") + "\n"

        private fun int(value: Boolean) = if (value) "1" else "0"
    }

    /** Minimal deterministic JSON writer for the metadata row; keys keep insertion order. */
    private object Json {
        fun write(value: Any?): String = when (value) {
            is String -> quote(value)
            is Int, is Long -> value.toString()
            is Boolean -> value.toString()
            is Map<*, *> -> value.entries.joinToString(",", "{", "}") { "${quote(it.key.toString())}:${write(it.value)}" }
            is List<*> -> value.joinToString(",", "[", "]") { write(it) }
            else -> error("unsupported metadata value $value")
        }

        private fun quote(value: String): String {
            val escaped = buildString(value.length + 2) {
                append('"')
                for (char in value) when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\t' -> append("\\t")
                    else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
                }
                append('"')
            }
            return escaped
        }
    }
}
