package rkr.simplekeyboard.inputmethod.latin.golden

import org.junit.Assume.assumeTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.BigramTableIdentity
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.CompositePrefixComputer
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.DictionaryIdentity
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.EngineExecutor
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.FuzzyEditPolicy
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.GlobalTopFrequencyFallbackFactory
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.ImmutableUtf8Prefix
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.KeyNeighborTable
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.LatestOnlyPrefixEngine
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.LookupResult
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.ResultHandoff
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.TatBigrPrefixIndex
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.TdictPrefixIndex
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalBigramSource
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalCandidateSource
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.BigramArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TatBigrValidator
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import rkr.simplekeyboard.inputmethod.latin.suggestions.AutocorrectGate
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarSuffixRules
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarWordUtils
import rkr.simplekeyboard.inputmethod.latin.suggestions.computeAutocorrectPreview
import java.io.File
import java.io.Writer
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * Edge-case golden-vector exporter. Inert unless EDGE_GOLDEN_OUT is set; its output feeds the iOS
 * port's parity suite. Covers what GoldenExportTest does not: malformed / non-UTF-8 prefixes and
 * contexts, empty and very long (MAX_PREFIX_BYTES boundary) inputs, mixed-case prefixes through
 * the controller's casing gate and the autocorrect-preview policy, the typo-recovery budget abort,
 * and NEXT_WORD on non-dictionary / invalid contexts.
 *
 * EDGE_GOLDEN_OUT must name a directory that already contains keys-tt.tsv / keys-ru.tsv (the
 * canonical key geometry). Nothing here reaches the APK.
 *
 *   EDGE_GOLDEN_OUT=/path/to/dir ./gradlew :app:testDebugUnitTest --tests '*EdgeGoldenExportTest*' --rerun
 *
 * The engine is assembled exactly as in GoldenExportTest (no personal sources). Byte inputs travel
 * as lowercase hex because a JSON string cannot carry malformed UTF-8. The fuzzy counters
 * (lastFuzzyOverBudget, lastFuzzyVariantCount, lastFuzzyVisitedCount, lastFuzzyProbeCount,
 * lastAutocorrectProbeCount) are written only for inputs that pass the lookup's validity gate,
 * because on the early return they keep the previous lookup's values.
 *
 * Only the edit-class-1 variant budget (MAX_FUZZY_VARIANTS), in the lookup and in the
 * autocorrect pass, can trip: on runs of 33+ letters that have two long-press partners
 * (ә: а/э, һ: г/х). The other budgets are structurally out of reach. The records pin both sides
 * of the reachable boundary and the class-4 probe maximum.
 */
class EdgeGoldenExportTest {

    @Test
    fun export() {
        val outName = System.getenv("EDGE_GOLDEN_OUT")
        assumeTrue("EDGE_GOLDEN_OUT not set: exporter skipped", !outName.isNullOrEmpty())
        val out = File(outName!!)
        require(out.isDirectory) { "EDGE_GOLDEN_OUT must be an existing directory" }

        val evalWords = locate("src/test/resources/tt_eval_sentences.txt")
            .readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .flatMap { it.split(" ") }.distinct()

        File(out, "edge.jsonl").bufferedWriter(Charsets.UTF_8).use { w ->
            for (lang in listOf("tt", "ru")) {
                val e = openLanguage(lang, File(out, "keys-$lang.tsv"))
                exportPrefixEdges(w, e)
                exportBudgetSweep(w, e)
                exportNextWordEdges(w, e)
                exportControllerEdges(w, e, evalWords)
                e.engine.destroy(1, TimeUnit.SECONDS)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Engine assembly (identical to GoldenExportTest) + a LatestOnlyPrefixEngine over the same
    // computer on an inline executor, for the request-level validity gate.

    private class Engine(
        val lang: String,
        val index: TdictPrefixIndex,
        val bigrams: TatBigrPrefixIndex,
        val computer: CompositePrefixComputer,
        val table: KeyNeighborTable,
        val entryCount: Int,
        val engine: LatestOnlyPrefixEngine,
        val handoffs: MutableList<LookupResult>,
    )

    private class InlineExecutor : EngineExecutor {
        private var shutdown = false
        override fun execute(command: Runnable) {
            check(!shutdown)
            command.run()
        }
        override fun shutdown() {
            shutdown = true
        }
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = shutdown
    }

    private fun openLanguage(lang: String, keysFile: File): Engine {
        val tatar = lang == "tt"
        val dictSpec = if (tatar) DictionaryArtifactSpec.TATAR_TOP100K_V1 else DictionaryArtifactSpec.RUSSIAN_TOP100K_V1
        val bigramSpec = if (tatar) BigramArtifactSpec.TATAR_BIGRAMS_V1 else BigramArtifactSpec.RUSSIAN_BIGRAMS_V1
        val dictAsset = locate("src/main/assets/dictionaries/${if (tatar) "tatar" else "russian"}_top100k_v1.tdict.zlib")
        val bigramAsset = locate("src/main/assets/bigrams/${if (tatar) "tatar" else "russian"}_bigrams_v1.tatbigr.zlib")

        val dictRaw = File.createTempFile("edge-dict-", ".tdict")
        val index: TdictPrefixIndex
        val identity: DictionaryIdentity
        val entryCount: Int
        try {
            dictRaw.outputStream().use { TdictValidator().inflateAsset(dictAsset.inputStream(), it, dictSpec) }
            val v = TdictValidator().validateRaw(dictRaw, dictSpec)
            identity = DictionaryIdentity(dictSpec.generation, v.schemaId, v.formatVersion, v.rawSha256)
            index = requireNotNull(
                TdictPrefixIndex.open(
                    ByteBuffer.wrap(dictRaw.readBytes()), identity, v.entryCount, v.rawSize,
                    if (tatar) TatarSuffixRules else null,
                    if (tatar) FuzzyEditPolicy.TATAR else null,
                ),
            )
            entryCount = v.entryCount.toInt()
        } finally {
            dictRaw.delete()
        }
        val table = readKeyNeighbors(lang, keysFile)
        index.updateKeyNeighbors(table)

        val bigramRaw = File.createTempFile("edge-bigr-", ".tatbigr")
        val bigrams: TatBigrPrefixIndex
        try {
            bigramRaw.outputStream().use { TatBigrValidator().inflateAsset(bigramAsset.inputStream(), it, bigramSpec) }
            val v = TatBigrValidator().validateRaw(bigramRaw, bigramSpec)
            val bigramIdentity = BigramTableIdentity(
                bigramSpec.generation, bigramSpec.fileLanguageTag, v.schemaId, v.formatVersion, v.rawSha256,
            )
            bigrams = requireNotNull(
                TatBigrPrefixIndex.open(ByteBuffer.wrap(bigramRaw.readBytes()), bigramIdentity, index, v.headCount, v.rawSize),
            )
        } finally {
            bigramRaw.delete()
        }

        val computer = CompositePrefixComputer(
            index,
            PersonalCandidateSource.EMPTY,
            if (tatar) TatarSuffixRules.createAfterWordForms(index) else null,
            GlobalTopFrequencyFallbackFactory.createFallbackWords(index),
            PersonalBigramSource.EMPTY,
            null,
        )
        computer.attachBigramSource(bigrams)
        val handoffs = ArrayList<LookupResult>()
        val engine = LatestOnlyPrefixEngine(identity, computer, InlineExecutor(), ResultHandoff { handoffs += it })
        return Engine(lang, index, bigrams, computer, table, entryCount, engine, handoffs)
    }

    private fun readKeyNeighbors(lang: String, file: File): KeyNeighborTable {
        require(file.isFile) { "missing ${file.name} — run ios/tools/export-key-geometry and copy it" }
        val keys = file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.map { line ->
            val f = line.split('\t')
            val more = if (f.size > 5 && f[5].isNotEmpty()) f[5].split(',').map { it.toInt(16) }.toIntArray() else IntArray(0)
            KeyNeighborTable.RawKey(f[0].toInt(16), more)
        }
        return KeyNeighborTable.build(if (lang == "tt") "tt_RU" else "ru", true, keys)
    }

    // ---------------------------------------------------------------------------------------
    // Byte-level inputs shared by the PREFIX and NEXT_WORD edge records.

    private fun utf8(s: String) = s.toByteArray(Charsets.UTF_8)

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private operator fun ByteArray.plus(other: String): ByteArray = this + utf8(other)

    /** Malformed UTF-8: every rejection branch of isValidUtf8Scalar, alone and embedded. */
    private fun malformedInputs(): List<Pair<String, ByteArray>> = listOf(
        "lone-continuation-80" to bytes(0x80),
        "lone-continuation-bf" to bytes(0xbf),
        "truncated-2byte-d0" to bytes(0xd0),
        "truncated-2byte-after-word" to (utf8("кит") + bytes(0xd0)),
        "truncated-3byte-e2-82" to bytes(0xe2, 0x82),
        "truncated-4byte-f0-9f-99" to bytes(0xf0, 0x9f, 0x99),
        "overlong-c0-80" to bytes(0xc0, 0x80),
        "overlong-c1-bf" to bytes(0xc1, 0xbf),
        "overlong-e0-80-80" to bytes(0xe0, 0x80, 0x80),
        "overlong-e0-9f-bf" to bytes(0xe0, 0x9f, 0xbf),
        "overlong-f0-80-80-80" to bytes(0xf0, 0x80, 0x80, 0x80),
        "overlong-f0-8f-bf-bf" to bytes(0xf0, 0x8f, 0xbf, 0xbf),
        "surrogate-ed-a0-80" to bytes(0xed, 0xa0, 0x80),
        "surrogate-ed-bf-bf" to bytes(0xed, 0xbf, 0xbf),
        "surrogate-in-word" to (utf8("сә") + bytes(0xed, 0xb0, 0x80) + "лам"),
        "above-10ffff-f4-90" to bytes(0xf4, 0x90, 0x80, 0x80),
        "lead-f5" to bytes(0xf5, 0x80, 0x80, 0x80),
        "lead-f8" to bytes(0xf8, 0x88, 0x80, 0x80, 0x80),
        "byte-fe" to bytes(0xfe),
        "byte-ff" to bytes(0xff),
        "bad-continuation-d0-41" to bytes(0xd0, 0x41),
        "bad-continuation-e2-28-a1" to bytes(0xe2, 0x28, 0xa1),
        "word-then-ff" to (utf8("сәлам") + bytes(0xff)),
        "ff-then-word" to (bytes(0xff) + "сәлам"),
        "continuation-mid-word" to (utf8("ки") + bytes(0x80) + "тап"),
        "latin1-e9" to bytes(0x63, 0x61, 0x66, 0xe9),
        "cesu-supplementary" to bytes(0xed, 0xa0, 0xbd, 0xed, 0xb8, 0x80),
    )

    /** Valid but unusual inputs: non-Cyrillic, uppercase (the computer sees bytes as-is), NUL,
     *  non-NFC, emoji, punctuation, whitespace. */
    private fun unusualValidInputs(): List<Pair<String, ByteArray>> = listOf(
        "nul" to bytes(0x00),
        "nul-in-word" to (utf8("ки") + bytes(0x00) + "т"),
        "space" to utf8(" "),
        "word-trailing-space" to utf8("кит "),
        "ascii-abc" to utf8("abc"),
        "ascii-hello" to utf8("hello"),
        "digits" to utf8("123"),
        "digits-long" to utf8("20260929"),
        "punct-dot" to utf8("."),
        "apostrophe" to utf8("кит'"),
        "hyphen" to utf8("кара-"),
        "emoji" to utf8("🙂"),
        "word-emoji" to utf8("кит🙂"),
        "emoji-word" to utf8("🙂кит"),
        "non-nfc-short-i" to utf8("и\u0306"),
        "non-nfc-in-word" to utf8("ми\u0306"),
        "nfc-short-i" to utf8("й"),
        "combining-only" to utf8("\u0301"),
        "kazakh-letter" to utf8("қазақ"),
        "bashkir-letter" to utf8("ҡала"),
        "upper-initial-tt" to utf8("Кит"),
        "upper-all-tt" to utf8("КИТ"),
        "upper-schwa" to utf8("Әни"),
        "upper-initial-ru" to utf8("При"),
        "mixed-bytes" to utf8("кИт"),
        "latin-lookalike" to utf8("кuт"),
        "zwj" to utf8("ки\u200dт"),
        "nbsp" to utf8("кит\u00a0"),
        "yo" to utf8("ёлка"),
        "hard-sign" to utf8("ъ"),
        "one-letter" to utf8("к"),
        "two-letters" to utf8("ки"),
        "three-letters" to utf8("кит"),
        "four-letters" to utf8("кита"),
    )

    /** The MAX_PREFIX_BYTES (128) and MAX_WORD_BYTES / MAX_CONTEXT_BYTES (128) boundaries. */
    private fun longInputs(e: Engine): List<Pair<String, ByteArray>> {
        var longest = ""
        for (i in 0 until e.entryCount) {
            val w = e.index.wordAt(i)
            if (utf8(w).size > utf8(longest).size) longest = w
        }
        return listOf(
            "cyr-64cp-128b" to utf8("а".repeat(64)),
            "cyr-63cp-126b" to utf8("а".repeat(63)),
            "cyr-64cp-plus-ascii-129b" to utf8("а".repeat(64) + "a"),
            "cyr-63cp-plus-ascii-127b" to utf8("а".repeat(63) + "a"),
            "cyr-63cp-plus-cyr-128b" to utf8("к".repeat(63) + "а"),
            "ascii-128b" to utf8("a".repeat(128)),
            "ascii-129b" to utf8("a".repeat(129)),
            "euro-42x3-plus-2-128b" to utf8("€".repeat(42) + "ab"),
            "euro-43x3-129b" to utf8("€".repeat(43)),
            "emoji-32x4-128b" to utf8("🙂".repeat(32)),
            "emoji-32x4-plus-1-129b" to utf8("🙂".repeat(32) + "a"),
            "longest-word" to utf8(longest),
            "longest-word-minus-1cp" to longest.codePoints().toArray().let { utf8(String(it, 0, it.size - 1)) },
            "longest-word-plus-a" to utf8(longest + "а"),
            "longest-word-padded-128b" to padTo128(longest),
            "word-glued-long" to utf8("китапханәләребезнеңкитапханәләребезнең"),
            "word-repeated-to-128b" to padTo128("кит".repeat(22)),
            "kit-then-127b-ascii" to (utf8("кит") + "a".repeat(122)),
            "kit-then-128b-ascii-reject" to (utf8("кит") + "a".repeat(123)),
        )
    }

    private fun padTo128(word: String): ByteArray {
        val sb = StringBuilder(word)
        while (utf8(sb.toString() + "а").size <= 128) sb.append("а")
        if (utf8(sb.toString()).size < 128) sb.append("a")
        return utf8(sb.toString())
    }

    /** The reachable fuzzy budget boundary (class #1, MAX_FUZZY_VARIANTS = 64) and the
     *  unreachable one's maximum (class #4 probes). */
    private fun budgetInputs(): List<Pair<String, ByteArray>> = listOf(
        "h-32-variants-64" to utf8("һ".repeat(32)),
        "h-33-variants-66-trip" to utf8("һ".repeat(33)),
        "schwa-32-variants-64" to utf8("ә".repeat(32)),
        "schwa-33-variants-66-trip" to utf8("ә".repeat(33)),
        "h-64-128b-trip" to utf8("һ".repeat(64)),
        "schwa16-h16-variants-64" to utf8("ә".repeat(16) + "һ".repeat(16)),
        "schwa16-h17-trip" to utf8("ә".repeat(16) + "һ".repeat(17)),
        "a-64-variants-64" to utf8("а".repeat(64)),
        "e-64-variants-64" to utf8("е".repeat(64)),
        "alternating-ah-64-variants-96-trip" to utf8("аһ".repeat(32)),
        "alternating-ah-21-variants-63" to utf8("аһ".repeat(21)),
        "alternating-ah-22-variants-66-trip" to utf8("аһ".repeat(22)),
        "word-then-h-33-trip" to utf8("сәлам" + "һ".repeat(33)),
        "kitap-then-schwa-31" to utf8("китап" + "ә".repeat(31)),
        "ascii-128-max-class4-probes" to utf8("a".repeat(128)),
        "ascii-then-h-trip" to utf8("x".repeat(62) + "һ".repeat(33)),
        "no-partner-letters-64" to utf8("к".repeat(64)),
        "trip-4cp-min" to utf8("һһһһ"),
    )

    // ---------------------------------------------------------------------------------------
    // prefix records: the computer's full answer, the autocorrect advice, the fuzzy counters, and
    // whether the engine accepts the request at all.

    private fun exportPrefixEdges(w: Writer, e: Engine) {
        val inputs = listOf(
            "empty" to ByteArray(0),
        ) + malformedInputs() + unusualValidInputs() + longInputs(e) + budgetInputs()
        for ((label, input) in inputs) prefixRecord(w, e, "prefix", label, input)
    }

    private fun prefixRecord(w: Writer, e: Engine, kind: String, label: String, input: ByteArray) {
        val prefix = ImmutableUtf8Prefix.copyOf(input)
        val results = e.computer.lookup(prefix)
        val advice = e.computer.lastAutocorrectAdvice
        val gated = input.isNotEmpty() && input.size <= TdictPrefixIndex.MAX_PREFIX_BYTES && validUtf8(input)
        val fields = arrayListOf<Pair<String, Any>>(
            "kind" to kind, "lang" to e.lang, "label" to label,
        )
        // Sweep inputs are fully described by their label ("<letter>*<n>"), so they carry no hex.
        if (kind != "sweep") fields += "hex" to hex(input)
        fields += listOf(
            "results" to results, "exact" to e.index.lastExactCount.toString(),
            "autocorrect" to (advice?.replacement ?: ""),
            "autocorrectFrequency" to (advice?.frequency?.toString() ?: ""),
            "autocorrectProbes" to e.index.lastAutocorrectProbeCount.toString(),
        )
        if (gated) {
            fields += "overBudget" to e.index.lastFuzzyOverBudget.toString()
            fields += "variants" to e.index.lastFuzzyVariantCount.toString()
            fields += "visited" to e.index.lastFuzzyVisitedCount.toString()
            fields += "probes" to e.index.lastFuzzyProbeCount.toString()
        }
        e.handoffs.clear()
        val token = e.engine.request(1, e.lang, input)
        fields += "accepted" to (token != null).toString()
        if (token != null) {
            check(e.handoffs.size == 1 && e.handoffs[0].token == token) { "inline engine did not hand off" }
            check(e.handoffs[0].suggestions == results) { "engine answer differs from the direct lookup: $label" }
        } else {
            check(e.handoffs.isEmpty())
        }
        w.record(*fields.toTypedArray())
    }

    private fun validUtf8(b: ByteArray): Boolean {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(b)); true
        } catch (_: java.nio.charset.CharacterCodingException) {
            false
        }
    }

    /** Every alphabet letter repeated 1..64 times (label "<letter>*<n>", no hex): pins the class-#1 budget boundary per letter
     *  (letters with two partners trip at 33, with one never — 64 variants exactly fit). */
    private fun exportBudgetSweep(w: Writer, e: Engine) {
        for (cp in e.table.nodes) {
            val letter = String(Character.toChars(cp))
            for (n in 1..64) {
                val input = utf8(letter.repeat(n))
                if (input.size > TdictPrefixIndex.MAX_PREFIX_BYTES) break
                prefixRecord(w, e, "sweep", "$letter*$n", input)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // nextword records: the bigram list and the full production chain for contexts that are not
    // dictionary words, are malformed, empty or over-long.

    private fun exportNextWordEdges(w: Writer, e: Engine) {
        val top = e.index.topFrequentWords(8)
        val contexts = listOf("empty" to ByteArray(0)) + malformedInputs() + unusualValidInputs() +
            longInputs(e) + listOf(
                "non-dict-tt" to utf8("кызыклыгыбызныкы"),
                "non-dict-ru" to utf8("приветики"),
                "non-dict-latin" to utf8("iphone"),
                "upper-dict-word-tt" to utf8("СӘЛАМ"),
                "initial-dict-word-tt" to utf8("Сәлам"),
                "upper-dict-word-ru" to utf8("ПРИВЕТ"),
                "dict-top-0" to utf8(top[0]),
                "dict-top-1" to utf8(top[1]),
                "dict-top-3" to utf8(top[3]),
                "dict-top-7" to utf8(top[7]),
                "tatar-stem-kitap" to utf8("китап"),
                "tatar-stem-non-dict-form" to utf8("китапчыкларыбызныкы"),
                "tatar-stem-truncated" to utf8("кита"),
                "suffix-only" to utf8("лар"),
                "word-with-hyphen" to utf8("кара-каршы"),
                "context-128b" to padTo128("китап"),
                "context-129b" to (padTo128("китап") + "a"),
            )
        for ((label, input) in contexts) {
            val prefix = ImmutableUtf8Prefix.copyOf(input)
            val bigrams = e.bigrams.predict(prefix)
            val chain = e.computer.predict(prefix)
            e.handoffs.clear()
            val token = e.engine.requestNextWord(1, e.lang, input)
            if (token != null) {
                check(e.handoffs.size == 1 && e.handoffs[0].suggestions == chain) { "engine chain differs: $label" }
            }
            w.record(
                "kind" to "nextword", "lang" to e.lang, "label" to label, "hex" to hex(input),
                "bigrams" to bigrams, "chain" to chain, "accepted" to (token != null).toString(),
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // controller records: the SuggestionsController PREFIX decision for a RAW typed word —
    // requestCurrentPrefix's casing gate (MIXED -> no request, empty strip reserved), the lookup
    // bytes it would send, and what applyPrefixResult paints: the autocorrect preview
    // (computeAutocorrectPreview, gate on) when it fires, else the first four candidates re-cased
    // with applyCasing. Pure composition of the production functions, in the controller's order.

    private fun exportControllerEdges(w: Writer, e: Engine, evalWords: List<String>) {
        val plain = if (e.lang == "tt") {
            listOf(
                "сәлам", "Сәлам", "СӘЛАМ", "сӘлам", "СәЛам", "сәлаМ", "сӘЛАМ", "СӘЛАм",
                "китап", "Китап", "КИТАП", "кИтап", "КиТАП", "китаП",
                "ә", "Ә", "әл", "Әл", "ӘЛ", "әЛ", "ӘЛИ", "Әли", "әЛИ",
                "татар", "ТаТар", "ТАТАР", "iPhone", "IPHONE", "Iphone", "iPHONE", "McDonald",
                "яңа", "Яңа", "ЯҢА", "яҢа", "ЯңА", "Ёлка", "ЁЛКА", "ёЛка",
                "И\u0306", "и\u0306", "Й", "ЙӨЗ", "йӨз", "К", "к", "КИ", "кИ", "Ки",
                "ҺӘМ", "Һәм", "һӘм", "123", "А1", "а1Б",
            )
        } else {
            listOf(
                "привет", "Привет", "ПРИВЕТ", "пРивет", "ПрИвЕт", "приveт", "москва", "Москва",
                "МОСКВА", "мОСКВА", "МосКва", "ёлка", "Ёлка", "ЁЛКА", "ёЛКА", "Я", "я", "ЯЗ", "яЗ",
                "Яз", "iPhone", "IPHONE", "ЕЩЕ", "Еще", "еЩе",
            )
        }
        // Typed words that DO carry autocorrect advice, in every casing (the preview path and its
        // casing re-application; MIXED must still refuse).
        val adviceWords = findAdviceWords(e, evalWords, 6)
        val words = LinkedHashSet<String>()
        words.addAll(plain)
        for (typo in adviceWords) {
            words.add(typo)
            words.add(TatarWordUtils.applyCasing(typo, TatarWordUtils.PrefixCasing.INITIAL_CAPS))
            words.add(TatarWordUtils.applyCasing(typo, TatarWordUtils.PrefixCasing.ALL_CAPS))
            val cps = typo.codePoints().toArray()
            if (cps.size >= 2) {
                cps[1] = Character.toUpperCase(cps[1])
                words.add(String(cps, 0, cps.size))
            }
            val last = typo.codePoints().toArray()
            last[last.size - 1] = Character.toUpperCase(last[last.size - 1])
            words.add(String(last, 0, last.size))
        }
        val gate = AutocorrectGate { true }
        for (raw in words) {
            val casing = TatarWordUtils.classifyCasing(raw)
            val normalized = TatarWordUtils.normalizeForLookup(raw)
            val lookupBytes = TatarWordUtils.toLookupBytes(normalized)
            // The advice the separator/preview policy would read — always the lookup of THIS word,
            // so the preview function's own MIXED refusal is what the mixed records pin.
            val results = e.computer.lookup(ImmutableUtf8Prefix.copyOf(lookupBytes))
            val advice = e.computer.lastAutocorrectAdvice
            val requested = casing != TatarWordUtils.PrefixCasing.MIXED
            val preview = computeAutocorrectPreview(gate, raw, null) { advice }
            val cells = if (!requested) emptyList() else results.take(4).map { TatarWordUtils.applyCasing(it, casing) }
            val band = when {
                !requested -> emptyList()
                preview != null -> listOf(preview.typedShown, preview.correctionShown)
                else -> cells
            }
            w.record(
                "kind" to "controller", "lang" to e.lang, "raw" to raw, "casing" to casing.name,
                "requested" to requested.toString(), "lookup" to if (requested) normalized else "",
                "cells" to cells, "band" to band,
                "previewTyped" to (preview?.typedShown ?: ""), "previewCorrection" to (preview?.correctionShown ?: ""),
            )
        }
    }

    private fun findAdviceWords(e: Engine, evalWords: List<String>, max: Int): List<String> {
        val base = LinkedHashSet<String>()
        if (e.lang == "tt") base.addAll(evalWords.map(TatarWordUtils::normalizeForLookup))
        var i = 0
        while (i < e.entryCount) { base.add(e.index.wordAt(i)); i += 211 }
        val out = ArrayList<String>()
        for (word in base) {
            val cps = word.codePoints().toArray()
            for (pos in cps.indices) {
                val swapped = TYPO[cps[pos]] ?: continue
                val t = cps.copyOf(); t[pos] = swapped
                val typo = String(t, 0, t.size)
                e.computer.lookup(ImmutableUtf8Prefix.copyOf(utf8(typo)))
                if (e.computer.lastAutocorrectAdvice != null && typo !in out) {
                    out += typo
                    if (out.size >= max) return out
                }
            }
        }
        return out
    }

    // ---------------------------------------------------------------------------------------
    // Minimal deterministic JSON writer (same format as GoldenExportTest).

    private fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append("%02x".format(x.toInt() and 0xff))
        return sb.toString()
    }

    private fun Writer.record(vararg fields: Pair<String, Any>) {
        val sb = StringBuilder("{")
        fields.forEachIndexed { i, (k, v) ->
            if (i > 0) sb.append(',')
            sb.append(json(k)).append(':')
            when (v) {
                is String -> sb.append(json(v))
                is List<*> -> sb.append(v.joinToString(",", "[", "]") { json(it as String) })
                else -> error("unsupported value")
            }
        }
        sb.append("}\n")
        write(sb.toString())
    }

    private fun json(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch < ' ' -> sb.append("\\u%04x".format(ch.code))
                else -> sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }

    private fun locate(path: String): File =
        listOf(File(path), File("app/$path")).firstOrNull(File::isFile) ?: error("cannot locate $path")

    companion object {
        /** The GoldenExportTest typo map (long-press partners both ways plus a few neighbors). */
        private val TYPO: Map<Int, Int> = mapOf(
            'а'.code to 'ә'.code, 'ә'.code to 'а'.code, 'о'.code to 'ө'.code, 'ө'.code to 'о'.code,
            'у'.code to 'ү'.code, 'ү'.code to 'у'.code, 'ж'.code to 'җ'.code, 'җ'.code to 'ж'.code,
            'н'.code to 'ң'.code, 'ң'.code to 'н'.code, 'х'.code to 'һ'.code, 'һ'.code to 'х'.code,
            'е'.code to 'ё'.code, 'ь'.code to 'ъ'.code, 'к'.code to 'е'.code, 'л'.code to 'д'.code,
            'и'.code to 'т'.code, 'р'.code to 'п'.code, 'с'.code to 'м'.code, 'в'.code to 'а'.code,
        )
    }
}
