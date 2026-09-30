package rkr.simplekeyboard.inputmethod.latin.golden

import org.junit.Assume.assumeTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiDisplaySnapshots
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiPanelState
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiSearchIndex
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiSet
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiSetSnapshot
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiSkinTones
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiSuggestIndex
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiTextUtils
import rkr.simplekeyboard.inputmethod.latin.emoji.RecentEmojiList
import java.io.File
import java.io.Writer

/**
 * Emoji golden-vector exporter (`emoji.jsonl`). Inert unless EMOJI_GOLDEN_OUT names an existing
 * directory; its output feeds the iOS port's parity suite. Nothing here reaches the APK.
 *
 *   EMOJI_GOLDEN_OUT=/path/to/dir ./gradlew :app:testDebugUnitTest --tests '*EmojiGoldenExportTest*'
 *
 * Writes deterministic UTF-8 JSON Lines: the parsed catalog, skin-tone variants, ranked search
 * results, recents MRU/codec scripts, trailing-cluster lengths, panel geometry and hit tests at an
 * iPhone-sized viewport, and the suggest table as parsed.
 */
class EmojiGoldenExportTest {

    @Test
    fun export() {
        val outName = System.getenv("EMOJI_GOLDEN_OUT")
        assumeTrue("EMOJI_GOLDEN_OUT not set: exporter skipped", !outName.isNullOrEmpty())
        val out = File(outName!!)
        require(out.isDirectory) { "EMOJI_GOLDEN_OUT must be an existing directory" }

        val setText = locate("src/main/assets/emoji/emoji_set_v1.txt").readText(Charsets.UTF_8)
        val skinText = locate("src/main/assets/emoji/emoji_skin_v1.txt").readText(Charsets.UTF_8)
        val searchText = locate("src/main/assets/emoji/emoji_search_v1.txt").readText(Charsets.UTF_8)
        val suggestText = locate("src/main/assets/emoji/emoji_suggest_v1.txt").readText(Charsets.UTF_8)

        val set = EmojiSet.parse(setText)
        val tones = EmojiSkinTones.parse(skinText)
        val index = EmojiSearchIndex.parse(searchText)

        File(out, "emoji.jsonl").bufferedWriter(Charsets.UTF_8).use { w ->
            exportSet(w, set)
            exportSkin(w, set, tones)
            exportSearch(w, index, searchText, set)
            exportRecents(w, set, tones)
            exportClusters(w, set, tones)
            exportPanel(w, set)
            exportSuggest(w, suggestText)
        }
    }

    private fun exportSet(w: Writer, set: EmojiSetSnapshot) {
        val sb = StringBuilder("{\"kind\":\"set\",\"categories\":[")
        for (c in 0 until set.categoryCount) {
            if (c > 0) sb.append(',')
            sb.append("{\"name\":").append(json(set.categoryName(c))).append(",\"entries\":")
            sb.append(jsonList(set.entriesOf(c))).append('}')
        }
        sb.append("],\"total\":").append(set.totalEntryCount()).append('}')
        w.write(sb.toString()); w.write("\n")
        // A filtered build through a deterministic fake probe: every third entry dropped.
        var n = 0
        val filtered = EmojiSet.build(setText(set)) { n++ % 3 != 2 }
        val fb = StringBuilder("{\"kind\":\"setFiltered\",\"categories\":[")
        for (c in 0 until filtered.categoryCount) {
            if (c > 0) fb.append(',')
            fb.append("{\"name\":").append(json(filtered.categoryName(c))).append(",\"count\":")
                .append(filtered.entryCount(c)).append('}')
        }
        fb.append("]}")
        w.write(fb.toString()); w.write("\n")
    }

    /** Rebuilds a canonical asset text from a snapshot (headers + entries). */
    private fun setText(set: EmojiSetSnapshot): String {
        val sb = StringBuilder()
        for (c in 0 until set.categoryCount) {
            sb.append('#').append(set.categoryName(c)).append('\n')
            for (e in set.entriesOf(c)) sb.append(e).append('\n')
        }
        return sb.toString()
    }

    private fun exportSkin(w: Writer, set: EmojiSetSnapshot, tones: EmojiSkinTones) {
        w.write("{\"kind\":\"skinSummary\",\"baseCount\":${tones.baseCount},\"toned\":${tones.allTonedSequences().size}}\n")
        for (c in 0 until set.categoryCount) {
            for (e in set.entriesOf(c)) {
                if (!tones.hasTones(e)) continue
                val variants = (0 until EmojiSkinTones.VARIANT_COUNT).map { tones.variantAt(e, it) }
                w.write("{\"kind\":\"skin\",\"base\":${json(e)},\"variants\":${jsonList(variants)}}\n")
            }
        }
    }

    private fun exportSearch(w: Writer, index: EmojiSearchIndex, searchText: String, set: EmojiSetSnapshot) {
        // nameOf for every panel entry (null when the index has no line for it).
        for (c in 0 until set.categoryCount) {
            for (e in set.entriesOf(c)) {
                val name = index.nameOf(e)
                w.write("{\"kind\":\"name\",\"seq\":${json(e)},\"name\":${if (name == null) "null" else json(name)}}\n")
            }
        }
        val queries = LinkedHashSet<String>()
        queries.addAll(CURATED_QUERIES)
        val lines = searchText.split('\n').filter { it.isNotEmpty() }
        for ((i, line) in lines.withIndex()) {
            if (i % 5 != 0) continue
            val fields = line.split('\t')
            if (fields.size != 3) continue
            val words = LinkedHashSet<String>()
            words.addAll(fields[1].split(' '))
            words.addAll(fields[2].split(' '))
            for (word in words) {
                if (word.isEmpty()) continue
                val cps = word.codePoints().toArray()
                for (n in 1..minOf(cps.size, 4)) queries.add(String(cps, 0, n))
                queries.add(word)
            }
        }
        for (q in queries) {
            val count = index.search(q)
            val results = (0 until count).map { index.resultAt(it) }
            w.write("{\"kind\":\"search\",\"query\":${json(q)},\"results\":${jsonList(results)}}\n")
        }
        for (q in CURATED_QUERIES) {
            w.write("{\"kind\":\"normalize\",\"query\":${json(q)},\"normalized\":${json(EmojiSearchIndex.normalize(q))}}\n")
        }
        // filterTo over every other panel entry, then a few queries.
        val available = HashSet<String>()
        var k = 0
        for (c in 0 until set.categoryCount) for (e in set.entriesOf(c)) { if (k++ % 2 == 0) available.add(e) }
        val filtered = index.filterTo(available)
        w.write("{\"kind\":\"searchFilteredSummary\",\"entryCount\":${filtered.entryCount}}\n")
        for (q in listOf("кот", "сердце", "cat", "heart", "йөрәк", "сәлам", "flag", "флаг", "а", "e")) {
            val count = filtered.search(q)
            val results = (0 until count).map { filtered.resultAt(it) }
            w.write("{\"kind\":\"searchFiltered\",\"query\":${json(q)},\"results\":${jsonList(results)}}\n")
        }
    }

    private fun exportRecents(w: Writer, set: EmojiSetSnapshot, tones: EmojiSkinTones) {
        val all = ArrayList<String>()
        for (c in 0 until set.categoryCount) all.addAll(set.entriesOf(c))
        // Script 1: use 40 emoji with repeats and toned forms; record the list after each use.
        var list = RecentEmojiList.EMPTY
        val script = ArrayList<String>()
        for (i in 0 until 40) {
            val base = all[(i * 37) % all.size]
            val seq = if (i % 4 == 3 && tones.hasTones(base)) tones.variantAt(base, 1 + i % 5) else base
            script.add(seq)
            if (i % 6 == 5) script.add(all[(i * 11) % all.size]) // a repeat of an older pick
        }
        for (seq in script) {
            list = list.used(seq)
            w.write("{\"kind\":\"recentsUse\",\"use\":${json(seq)},\"entries\":${jsonList(list.entries)},\"serialized\":${json(list.serialize())}}\n")
        }
        // Script 2: the char budget with long ZWJ families.
        var budget = RecentEmojiList.EMPTY
        for (i in 0 until 30) {
            val seq = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66" + "\uD83C\uDFFB".repeat(i % 3) + i
            budget = budget.used(seq)
        }
        w.write("{\"kind\":\"recentsBudget\",\"entries\":${jsonList(budget.entries)}}\n")
        // Deserialize: good and bad media.
        val sep = RecentEmojiList.SEPARATOR.toString()
        val media = listOf(
            "", all[0], all.take(5).joinToString(sep), all.take(24).joinToString(sep),
            all.take(25).joinToString(sep), all[0] + sep + all[0], all[0] + sep + "a b",
            all[0] + sep, sep + all[0], "x".repeat(64), "x".repeat(65), all[0] + "\u00A0",
            all[0] + "\u2009", list.serialize(),
        )
        for (m in media) {
            w.write("{\"kind\":\"recentsParse\",\"raw\":${json(m)},\"entries\":${jsonList(RecentEmojiList.deserialize(m).entries)}}\n")
        }
    }

    private fun exportClusters(w: Writer, set: EmojiSetSnapshot, tones: EmojiSkinTones) {
        val texts = LinkedHashSet<String>()
        for (c in 0 until set.categoryCount) {
            for (e in set.entriesOf(c)) {
                texts.add(e)
                texts.add("ab" + e)
                if (tones.hasTones(e)) texts.add(tones.variantAt(e, 3))
            }
        }
        texts.addAll(listOf("", "a", "сәлам", "5", " ", "\uD83D\uDC68\u200D\uD83D\uDC66", "x\u200D\uD83D\uDE00",
            "\u200D\uD83D\uDE00", "\uD83C\uDDF7\uD83C\uDDFA\uD83C\uDDF7", "e\u0301", "\uD83D\uDE00\uFE0F\uFE0F"))
        for (t in texts) {
            w.write("{\"kind\":\"cluster\",\"text\":${json(t)},\"length\":${EmojiTextUtils.trailingEmojiClusterLength(t)}}\n")
        }
    }

    private fun exportPanel(w: Writer, set: EmojiSetSnapshot) {
        val display = EmojiDisplaySnapshots.withRecents(set, listOf(set.entryAt(0, 0), set.entryAt(1, 3)))
        for ((width, height) in listOf(393 to 216, 393 to 176, 852 to 160, 320 to 120)) {
            val state = EmojiPanelState()
            state.setColumns(if (width > 600) 12 else 8)
            state.setCellMetrics(36, 56, 44, 30, 44, 8, 60)
            state.setSwipeMinDistance(32)
            state.setViewport(width, height)
            state.setSnapshot(display)
            val tops = (0..state.sectionCount()).map { state.sectionTop(it) }
            val starts = (0..state.sectionCount()).map { state.sectionStartIndex(it) }
            w.write("{\"kind\":\"panel\",\"width\":$width,\"height\":$height,\"cellWidth\":${state.cellWidth()},\"cellHeight\":${state.cellHeight()}," +
                "\"tabBar\":${state.tabBarHeight()},\"searchBar\":0,\"gridTop\":${state.gridTop()},\"gridHeight\":${state.gridHeight()}," +
                "\"contentHeight\":${state.contentHeight()},\"maxScroll\":${state.maxScrollY()},\"sectionTops\":$tops,\"sectionStarts\":$starts}\n")
            val scrolls = listOf(0, 1, 77, state.sectionTop(2), state.maxScrollY() / 2, state.maxScrollY())
            for (scroll in scrolls) {
                state.setScrollY(scroll)
                val targets = ArrayList<Int>()
                var y = 0
                while (y < height) {
                    var x = 0
                    while (x < width) { targets.add(state.targetAt(x + 0.5f, y + 0.5f)); x += 11 }
                    y += 7
                }
                w.write("{\"kind\":\"panelHits\",\"width\":$width,\"height\":$height,\"scroll\":${state.scrollY()},\"active\":${state.activeCategory()}," +
                    "\"visible\":${state.visibleCellCount()},\"targets\":$targets}\n")
            }
            state.setScrollY(0)
            for (cell in listOf(0, 1, 7, 8, 9, 30, 100, state.entryCount() - 1)) {
                if (!state.openPopup(cell, EmojiSkinTones.VARIANT_COUNT)) continue
                w.write("{\"kind\":\"panelPopup\",\"width\":$width,\"height\":$height,\"cell\":$cell,\"left\":${state.popupLeft()},\"top\":${state.popupTop()}," +
                    "\"right\":${state.popupRight()},\"bottom\":${state.popupBottom()}}\n")
                state.closePopup()
            }
        }
    }

    private fun exportSuggest(w: Writer, suggestText: String) {
        val index = EmojiSuggestIndex.parse(suggestText)
        w.write("{\"kind\":\"suggestSummary\",\"entryCount\":${index.entryCount}}\n")
        for (line in suggestText.split('\n')) {
            val f = line.split('\t')
            if (f.size != 3) continue
            val hit = index.lookup(f[0], f[1])
            w.write("{\"kind\":\"suggest\",\"lang\":${json(f[0])},\"word\":${json(f[1])},\"emoji\":${if (hit == null) "null" else json(hit)}}\n")
        }
    }

    private fun jsonList(items: List<String>): String = items.joinToString(",", "[", "]") { json(it) }

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
        private val CURATED_QUERIES = listOf(
            "кот", "КОТ", "  Кот  ", "cat", "CAT", "сердце", "heart", "йөрәк", "ЙӨРӘК", "мәхәббәт", "мәче",
            "сәлам", "китап", "этэч", "этэ", "йөр", "рәк", "флаг", "flag", "россия", "russia", "татар",
            "smile", "улыб", "елмаю", "көлү", "а", "e", "1", "#", "*", "кот кош", "  ", "", "\u00A0кот\u2009",
            "Σ", "İ", "ǅ", "ﬀ", "ё", "Ё", "огонь", "fire", "ут", "су", "water", "🐱", "руки", "hand",
        )
    }
}
