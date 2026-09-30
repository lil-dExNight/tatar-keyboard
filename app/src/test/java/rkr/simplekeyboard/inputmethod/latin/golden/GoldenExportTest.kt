package rkr.simplekeyboard.inputmethod.latin.golden

import org.junit.Assume.assumeTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.BigramTableIdentity
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.CompositePrefixComputer
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.DictionaryIdentity
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.FuzzyEditPolicy
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.GlobalTopFrequencyFallbackFactory
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.ImmutableUtf8Prefix
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.KeyNeighborTable
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.TatBigrPrefixIndex
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.TdictPrefixIndex
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalBigramSource
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalCandidateSource
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.BigramArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TatBigrValidator
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import rkr.simplekeyboard.inputmethod.latin.suggestions.SentStartIndex
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarSuffixRules
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarWordUtils
import java.io.File
import java.io.Writer
import java.nio.ByteBuffer

/**
 * Golden-vector exporter for the iOS port (ios/docs/VERIFICATION.md §3.1). TEST-ONLY and inert:
 * it runs only when the environment variable GOLDEN_OUT names a directory, so the normal
 * `./gradlew test` run (CI, release_check) skips it and nothing here reaches the APK.
 *
 *   GOLDEN_OUT=/path/to/dir ./gradlew :app:testDebugUnitTest --tests '*GoldenExportTest*'
 *
 * The directory must already contain keys-tt.tsv / keys-ru.tsv — the canonical key geometry the
 * iOS side also builds its KeyNeighborTable from (ios/tools/export-key-geometry). Output is
 * deterministic UTF-8 JSON Lines; the iOS parity suite replays every record against the Swift
 * port and requires exact equality.
 */
class GoldenExportTest {

    @Test
    fun export() {
        val outName = System.getenv("GOLDEN_OUT")
        assumeTrue("GOLDEN_OUT not set: exporter skipped", !outName.isNullOrEmpty())
        val out = File(outName!!)
        require(out.isDirectory) { "GOLDEN_OUT must be an existing directory" }

        val evalLines = locate("src/test/resources/tt_eval_sentences.txt")
            .readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val evalWords = evalLines.flatMap { it.split(" ") }.distinct()

        exportText(File(out, "text.jsonl"), evalWords)
        for (lang in listOf("tt", "ru")) {
            val engine = openLanguage(lang, File(out, "keys-$lang.tsv"))
            exportFormats(File(out, "formats-$lang.jsonl"), engine)
            exportPrefix(File(out, "prefix-$lang.jsonl"), engine, queryWords(lang, engine, evalWords))
            exportNextWord(File(out, "nextword-$lang.jsonl"), engine)
            exportSentStart(File(out, "sentstart-$lang.jsonl"), lang)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Engine assembly — exactly the production wiring of EngineHandle.start /
    // MappedDictionaryEngine.start (Tatar: suffix rules + TATAR fuzzy policy; Russian: neither;
    // both: the global top-frequency fallback; no personal sources).

    private class Engine(
        val lang: String,
        val index: TdictPrefixIndex,
        val bigrams: TatBigrPrefixIndex,
        val computer: CompositePrefixComputer,
        val entryCount: Int,
    )

    private fun openLanguage(lang: String, keysFile: File): Engine {
        val tatar = lang == "tt"
        val dictSpec = if (tatar) DictionaryArtifactSpec.TATAR_TOP100K_V1 else DictionaryArtifactSpec.RUSSIAN_TOP100K_V1
        val bigramSpec = if (tatar) BigramArtifactSpec.TATAR_BIGRAMS_V1 else BigramArtifactSpec.RUSSIAN_BIGRAMS_V1
        val dictAsset = locate("src/main/assets/dictionaries/${if (tatar) "tatar" else "russian"}_top100k_v1.tdict.zlib")
        val bigramAsset = locate("src/main/assets/bigrams/${if (tatar) "tatar" else "russian"}_bigrams_v1.tatbigr.zlib")

        val dictRaw = File.createTempFile("golden-dict-", ".tdict")
        val index: TdictPrefixIndex
        val entryCount: Int
        try {
            dictRaw.outputStream().use { TdictValidator().inflateAsset(dictAsset.inputStream(), it, dictSpec) }
            val v = TdictValidator().validateRaw(dictRaw, dictSpec)
            val identity = DictionaryIdentity(dictSpec.generation, v.schemaId, v.formatVersion, v.rawSha256)
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
        index.updateKeyNeighbors(readKeyNeighbors(lang, keysFile))

        val bigramRaw = File.createTempFile("golden-bigr-", ".tatbigr")
        val bigrams: TatBigrPrefixIndex
        try {
            bigramRaw.outputStream().use { TatBigrValidator().inflateAsset(bigramAsset.inputStream(), it, bigramSpec) }
            val v = TatBigrValidator().validateRaw(bigramRaw, bigramSpec)
            val identity = BigramTableIdentity(
                bigramSpec.generation, bigramSpec.fileLanguageTag, v.schemaId, v.formatVersion, v.rawSha256,
            )
            bigrams = requireNotNull(
                TatBigrPrefixIndex.open(ByteBuffer.wrap(bigramRaw.readBytes()), identity, index, v.headCount, v.rawSize),
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
        return Engine(lang, index, bigrams, computer, entryCount)
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
    // text.jsonl — TatarWordUtils normalization / casing / context rules.

    private fun exportText(file: File, evalWords: List<String>) {
        val singles = buildList {
            for (cp in 0x20..0x7e) add(String(Character.toChars(cp)))
            for (cp in 0x400..0x52f) if (Character.isDefined(cp)) add(String(Character.toChars(cp)))
            addAll(listOf("\u00e9", "e\u0301", "\u0438\u0306", "\u0435\u0308", "\u00df", "\u0130", "\u0131", "\u212a"))
        }
        val words = singles + evalWords.take(400) + listOf(
            "Сәлам", "СӘЛАМ", "сӘлам", "Ә", "ӘЛИ", "Москва", "МОСКВА", "мОСКВА", "Ёлка", "ЁЛКА", "iPhone", "",
        )
        val contexts = listOf(
            "", " ", "сәлам", "сәлам ", "сәлам, ", "Сәлам. ", "Сәлам.", "Нихәл?", "Нихәл? Ә", "ул китте\n",
            "ул китте\nминем", "\"сәлам", "(сәлам", "сәлам-", "7 ", "сәлам 7", "e.g. ", "ә", "Һәм ", "бу — ",
            "кара...", "кара... ", "сүз'", "сүз’", "Мин 🙂 ", "мин🙂", "  ", "эй!", "эй! ", "т.б. ",
        )
        val afters = listOf("", " ", "сүз", ",", ".", " сүз", "\n", "!", "-", "🙂")
        file.bufferedWriter(Charsets.UTF_8).use { w ->
            for (s in words) {
                val casing = TatarWordUtils.classifyCasing(s)
                val norm = TatarWordUtils.normalizeForLookup(s)
                w.record(
                    "kind" to "word", "raw" to s, "normalized" to norm, "casing" to casing.name,
                    "initialCaps" to TatarWordUtils.applyCasing(norm, TatarWordUtils.PrefixCasing.INITIAL_CAPS),
                    "allCaps" to TatarWordUtils.applyCasing(norm, TatarWordUtils.PrefixCasing.ALL_CAPS),
                )
            }
            for (c in contexts) {
                w.record(
                    "kind" to "context", "before" to c,
                    "trailingWord" to TatarWordUtils.extractTrailingWord(c),
                    "nextWordContext" to TatarWordUtils.extractNextWordContext(c),
                    "nextWordContextAtStart" to TatarWordUtils.extractNextWordContext(c, true),
                    "sentenceStart" to TatarWordUtils.isSentenceStartContext(c).toString(),
                    "sentenceStartAtStart" to TatarWordUtils.isSentenceStartContext(c, true).toString(),
                    "endsWithWord3" to TatarWordUtils.endsWithWordOfAtLeast(c, 3).toString(),
                )
            }
            for (a in afters) {
                w.record(
                    "kind" to "after", "after" to a,
                    "needsAutoSpace" to TatarWordUtils.needsAutoSpace(a).toString(),
                    "startsWithWordCharacter" to TatarWordUtils.startsWithWordCharacter(a).toString(),
                )
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // formats-<lang>.jsonl — reader parity: word order, frequencies, top words.

    private fun exportFormats(file: File, e: Engine) {
        file.bufferedWriter(Charsets.UTF_8).use { w ->
            w.record(
                "kind" to "header", "entryCount" to e.entryCount.toString(),
                "top8" to e.index.topFrequentWords(8),
            )
            val step = 37
            var i = 0
            while (i < e.entryCount) {
                val word = e.index.wordAt(i)
                val bytes = word.toByteArray(Charsets.UTF_8)
                w.record(
                    "kind" to "entry", "index" to i.toString(), "word" to word,
                    "frequency" to e.index.frequencyOf(bytes, bytes.size).toString(),
                    "bigramIndex" to e.index.indexOfWord(bytes, bytes.size).toString(),
                )
                i += if (i < 64 || i >= e.entryCount - 64) 1 else step
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // prefix-<lang>.jsonl — the full PREFIX answer (exact + fuzzy + ranking) and the D3 verdict.

    private fun queryWords(lang: String, e: Engine, evalWords: List<String>): List<String> {
        val base = LinkedHashSet<String>()
        if (lang == "tt") base.addAll(evalWords.map(TatarWordUtils::normalizeForLookup))
        // Every 211th dictionary word — covers both languages evenly without depending on the
        // eval set (which is Tatar only).
        var i = 0
        while (i < e.entryCount) { base.add(e.index.wordAt(i)); i += 211 }
        val out = LinkedHashSet<String>()
        for (word in base) {
            val cps = word.codePoints().toArray()
            for (n in 1..cps.size) out.add(String(cps, 0, n))
            // Deterministic one-letter typos to exercise the fuzzy classes: long-press partners
            // (class #1) and a neighbour substitution in the middle (class #4).
            for (pos in cps.indices) {
                val swapped = TYPO[cps[pos]] ?: continue
                val t = cps.copyOf(); t[pos] = swapped
                out.add(String(t, 0, t.size))
            }
        }
        out.add("")
        return out.toList()
    }

    private fun exportPrefix(file: File, e: Engine, queries: List<String>) {
        file.bufferedWriter(Charsets.UTF_8).use { w ->
            for (q in queries) {
                val prefix = ImmutableUtf8Prefix.copyOf(q.toByteArray(Charsets.UTF_8))
                val results = e.computer.lookup(prefix)
                val advice = e.computer.lastAutocorrectAdvice
                w.record(
                    "prefix" to q, "results" to results, "exact" to e.index.lastExactCount.toString(),
                    "autocorrect" to (advice?.replacement ?: ""),
                    "autocorrectFrequency" to (advice?.frequency?.toString() ?: ""),
                )
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // nextword-<lang>.jsonl — static successors alone and the full production NEXT_WORD chain.

    private fun exportNextWord(file: File, e: Engine) {
        file.bufferedWriter(Charsets.UTF_8).use { w ->
            for (i in 0 until e.entryCount) {
                val word = e.index.wordAt(i)
                val prefix = ImmutableUtf8Prefix.copyOf(word.toByteArray(Charsets.UTF_8))
                val successors = e.bigrams.predict(prefix)
                // Every head, plus every 101st non-head word (forms + fallback path).
                if (successors.isEmpty() && i % 101 != 0) continue
                w.record("context" to word, "bigrams" to successors, "chain" to e.computer.predict(prefix))
            }
        }
    }

    private fun exportSentStart(file: File, lang: String) {
        val index = SentStartIndex.parse(
            locate("src/main/assets/dictionaries/${if (lang == "tt") "tatar" else "russian"}_sentstart_v1.txt")
                .readText(Charsets.UTF_8),
        )
        file.bufferedWriter(Charsets.UTF_8).use { w ->
            for (n in listOf(0, 1, 3, 4, 8, 100)) w.record("max" to n.toString(), "words" to index.topWords(n))
        }
    }

    // ---------------------------------------------------------------------------------------
    // Minimal deterministic JSON writer (keys in the given order; strings and string lists).

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
        /** Deterministic typo map: long-press partners both ways plus a few row neighbours. */
        private val TYPO: Map<Int, Int> = mapOf(
            'а'.code to 'ә'.code, 'ә'.code to 'а'.code, 'о'.code to 'ө'.code, 'ө'.code to 'о'.code,
            'у'.code to 'ү'.code, 'ү'.code to 'у'.code, 'ж'.code to 'җ'.code, 'җ'.code to 'ж'.code,
            'н'.code to 'ң'.code, 'ң'.code to 'н'.code, 'х'.code to 'һ'.code, 'һ'.code to 'х'.code,
            'е'.code to 'ё'.code, 'ь'.code to 'ъ'.code, 'к'.code to 'е'.code, 'л'.code to 'д'.code,
            'и'.code to 'т'.code, 'р'.code to 'п'.code, 'с'.code to 'м'.code, 'в'.code to 'а'.code,
        )
    }
}
