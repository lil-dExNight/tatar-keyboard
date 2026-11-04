package rkr.simplekeyboard.inputmethod.latin.golden

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.floor
import org.w3c.dom.Element

/**
 * Derives the golden key geometry (keys-tt.tsv / keys-ru.tsv) from the committed layout
 * resources: rows_*.xml give the row structure and key widths, rowkeys_*.xml the keys and
 * long-press partners, values/config.xml the gap and padding fractions. The model is the
 * device layout formula on the 100 000-unit reference grid (the x model of
 * scripts/typo_pack.py) plus the reference vertical model of scripts/glide_pack.py; the
 * Russian rows share that vertical model, as GlideTestFixtures.russianRawKeys does.
 *
 * The committed TSVs under src/test/resources/golden are the interchange artifact the iOS
 * parity suite consumes; this derivation is their regeneration path, pinned byte-for-byte by
 * KeyGeometryTsvTest so a layout edit cannot silently desynchronize the two ports.
 */
internal object KeyGeometryTsv {

    /** One letter key of the layout: the letter, its rectangle and its built more-keys. */
    class KeyRecord(
        val codePoint: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val moreKeyCodePoints: IntArray,
    )

    /** The TSV of [lang] ("tt" or "ru"): keys in layout order, LF-terminated lines. */
    fun tsv(lang: String): String =
        derive(lang).joinToString("\n", postfix = "\n") { key ->
            val more = key.moreKeyCodePoints.joinToString(",") { Integer.toHexString(it) }
            val line = "${Integer.toHexString(key.codePoint)}\t${key.left}\t${key.top}\t${key.right}\t${key.bottom}"
            if (more.isEmpty()) line else "$line\t$more"
        }

    /**
     * The keys-$lang.tsv one exporter run reads: another port's copy dropped into
     * [overrideDir] for a parity run when present, else the committed, pinned copy.
     */
    fun locate(lang: String, overrideDir: File): File {
        val override = File(overrideDir, "keys-$lang.tsv")
        if (override.isFile) return override
        return listOf(
            File("src/test/resources/golden/keys-$lang.tsv"),
            File("app/src/test/resources/golden/keys-$lang.tsv"),
        ).firstOrNull(File::isFile) ?: error("cannot locate keys-$lang.tsv")
    }

    /** The letter keys of [lang]'s layout with their device-model reference rectangles. */
    fun derive(lang: String): List<KeyRecord> {
        val xml = locateRes("xml")
        val config = readConfig(locateRes("values/config.xml"))
        val rows = readRows(File(xml, if (lang == "tt") "rows_tatar.xml" else "rows_russian.xml"), xml)
        val keyboardPx = roundHalfUp(config.keyboardHeightDp * REFERENCE_DENSITY_DPI / 160.0)
        val rowHeightPercent = readRowHeightPercent(File(xml, "kbd_tatar.xml"))
        require(rowHeightPercent == rowHeightPercent.toInt().toDouble()) {
            "the row height must be a whole percent, was $rowHeightPercent"
        }
        val pitchPx = keyboardPx * rowHeightPercent.toInt() / 100
        val pitch = toGrid(pitchPx)
        val keyHeight = toGrid(pitchPx - roundHalfUp(keyboardPx * config.verticalGapPercent / 100.0))
        val topPadding = toGrid(roundHalfUp(keyboardPx * config.topPaddingPercent / 100.0))

        val gap = config.horizontalGapPercent / 100.0 * GRID_WIDTH
        val leftPad = config.leftPaddingPercent / 100.0 * GRID_WIDTH
        val rightPad = config.rightPaddingPercent / 100.0 * GRID_WIDTH
        val baseWidth = GRID_WIDTH - leftPad - rightPad + gap
        val rightEdge = GRID_WIDTH - rightPad
        val records = ArrayList<KeyRecord>()
        for ((rowIndex, row) in rows.withIndex()) {
            var x = leftPad
            for (entry in row.entries) {
                val fraction = entry.widthFraction ?: row.defaultWidthFraction
                if (entry.fillRight) break // the trailing delete key only bounds the clamp
                var width = fraction / 100.0 * baseWidth - gap
                if (x + width > rightEdge) width = rightEdge - x
                if (entry.keySpec != null) {
                    val letter = normalizeLetter(entry.keySpec.codePointAt(0))
                    if (letter != null) {
                        val top = topPadding + rowIndex * pitch
                        records.add(
                            KeyRecord(
                                letter, Math.round(x).toInt(), top,
                                Math.round(x + width).toInt(), top + keyHeight,
                                entry.builtMoreKeys(),
                            ),
                        )
                    }
                }
                x += width + gap
            }
        }
        return records
    }

    // ---------------------------------------------------------------------------------------
    // Layout resources.

    private class Row(val defaultWidthFraction: Double, val entries: List<Entry>)

    private class Entry(
        val keySpec: String?,
        val widthFraction: Double?,
        val fillRight: Boolean,
        val moreKeys: String?,
        val additionalMoreKeys: String?,
    ) {
        /** The more-keys as the built keyboard carries them (see MoreKeySpec). */
        fun builtMoreKeys(): IntArray {
            val base = moreKeys?.split(",") ?: emptyList()
            val additional = additionalMoreKeys?.split(",") ?: emptyList()
            val expanded = when {
                additional.isEmpty() -> base.filter { it != "%" }
                "%" in base -> {
                    val out = ArrayList<String>()
                    val rest = additional.iterator()
                    for (token in base) {
                        if (token == "%" && rest.hasNext()) rest.next().let(out::add)
                        else if (token != "%") out.add(token)
                    }
                    rest.forEach(out::add)
                    out
                }
                else -> additional + base
            }
            // A multi-character token ("!text/...") is not a letter partner; it never appears
            // in these rows, and dropping it keeps the column single-code-point-only.
            return expanded.filter { it.codePointCount(0, it.length) == 1 }
                .map { it.codePointAt(0) }.toIntArray()
        }
    }

    /** The rows of one rows_*.xml: Row elements only; the trailing bottom-row include is none. */
    private fun readRows(rowsFile: File, xmlDir: File): List<Row> {
        val root = parse(rowsFile)
        val rows = ArrayList<Row>()
        for (row in childElements(root, "Row")) {
            val defaultWidth = row.getAttributeNS(RES_AUTO, "keyWidth")
                .takeIf { it.isNotEmpty() }?.let(::parsePercent) ?: 0.0
            val entries = ArrayList<Entry>()
            for (child in childElements(row)) {
                when (child.tagName) {
                    "Key" -> {
                        val width = child.getAttributeNS(RES_AUTO, "keyWidth")
                        entries.add(
                            Entry(
                                keySpec = child.getAttributeNS(RES_AUTO, "keySpec")
                                    .takeIf { it.codePointCount(0, it.length) == 1 },
                                widthFraction = if (width == "fillRight") null
                                    else width.takeIf { it.isNotEmpty() }?.let(::parsePercent),
                                fillRight = width == "fillRight",
                                moreKeys = null,
                                additionalMoreKeys = null,
                            ),
                        )
                    }
                    "include" -> {
                        val layout = child.getAttributeNS(RES_AUTO, "keyboardLayout")
                        if (layout.isNotEmpty()) {
                            for ((keySpec, moreKeys, additional) in readRowKeys(File(xmlDir, layout.substringAfterLast('/') + ".xml"))) {
                                entries.add(Entry(keySpec, null, false, moreKeys, additional))
                            }
                        }
                    }
                }
            }
            rows.add(Row(defaultWidth, entries))
        }
        return rows
    }

    /** (keySpec, moreKeys, additionalMoreKeys) of every Key in the <default> branches. */
    private fun readRowKeys(file: File): List<Triple<String, String?, String?>> {
        val out = ArrayList<Triple<String, String?, String?>>()
        fun walk(element: Element) {
            for (child in childElements(element)) {
                when (child.tagName) {
                    "Key" -> out.add(
                        Triple(
                            child.getAttributeNS(RES_AUTO, "keySpec"),
                            child.getAttributeNS(RES_AUTO, "moreKeys").ifEmpty { null },
                            child.getAttributeNS(RES_AUTO, "additionalMoreKeys").ifEmpty { null },
                        ),
                    )
                    "switch" -> {
                        val defaults = childElements(child, "default")
                        require(defaults.size == 1) { "${file.name}: a <switch> needs one <default> branch" }
                        walk(defaults[0])
                    }
                    else -> walk(child)
                }
            }
        }
        walk(parse(file))
        for (key in out) {
            require(key.first.codePointCount(0, key.first.length) == 1) {
                "${file.name}: a row key has no single-character keySpec"
            }
        }
        return out
    }

    private class Config(
        val horizontalGapPercent: Double,
        val leftPaddingPercent: Double,
        val rightPaddingPercent: Double,
        val verticalGapPercent: Double,
        val topPaddingPercent: Double,
        val keyboardHeightDp: Double,
    )

    private fun readConfig(file: File): Config {
        val root = parse(file)
        val values = HashMap<String, String>()
        for (tag in listOf("fraction", "dimen")) {
            for (element in childElements(root, tag)) {
                values[element.getAttribute("name")] = element.textContent
            }
        }
        fun fraction(name: String): Double =
            parsePercent(requireNotNull(values[name]) { "${file.name} misses $name" })
        return Config(
            horizontalGapPercent = fraction("config_key_horizontal_gap"),
            leftPaddingPercent = fraction("config_keyboard_left_padding"),
            rightPaddingPercent = fraction("config_keyboard_right_padding"),
            // Referenced by kbd_tatar.xml's default keyboard; the reference model is 5-row.
            verticalGapPercent = fraction("config_key_vertical_gap_5row"),
            topPaddingPercent = fraction("config_keyboard_top_padding"),
            keyboardHeightDp = requireNotNull(values["config_default_keyboard_height"])
                .removeSuffix("dp").toDouble(),
        )
    }

    /** The default branch's rowHeight of the Tatar keyboard set (the reference y model). */
    private fun readRowHeightPercent(file: File): Double {
        val defaults = childElements(parse(file), "default")
        require(defaults.size == 1) { "${file.name}: one <default> branch" }
        val keyboards = childElements(defaults[0], "Keyboard")
        require(keyboards.size == 1) { "${file.name}: one <Keyboard> in the default branch" }
        return parsePercent(keyboards[0].getAttributeNS(RES_AUTO, "rowHeight"))
    }

    // ---------------------------------------------------------------------------------------
    // Arithmetic and XML plumbing.

    private fun toGrid(px: Int): Int = roundHalfUp(px * GRID_WIDTH.toDouble() / REFERENCE_SCREEN_WIDTH_PX)

    /** Round half up, the rule the device's Key applies to pixel edges. */
    private fun roundHalfUp(value: Double): Int = floor(value + 0.5).toInt()

    private fun parsePercent(value: String): Double =
        value.removeSuffix("%p").removeSuffix("%").trim().toDouble()

    /** Fold to a single NFC lower-case letter, or null; mirrors KeyNeighborTable.build. */
    private fun normalizeLetter(codePoint: Int): Int? {
        val folded = java.text.Normalizer.normalize(
            String(Character.toChars(codePoint)), java.text.Normalizer.Form.NFC,
        ).lowercase()
        if (folded.codePointCount(0, folded.length) != 1) return null
        val normalized = folded.codePointAt(0)
        return if (Character.isLetter(normalized)) normalized else null
    }

    private fun parse(file: File): Element {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    private fun childElements(element: Element, tag: String? = null): List<Element> {
        val nodes = element.childNodes
        val out = ArrayList<Element>()
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element && (tag == null || node.tagName == tag)) out.add(node)
        }
        return out
    }

    private fun locateRes(path: String): File =
        listOf(File("src/main/res/$path"), File("app/src/main/res/$path")).firstOrNull(File::exists)
            ?: error("cannot locate res/$path from ${File(".").absolutePath}")

    private const val RES_AUTO = "http://schemas.android.com/apk/res-auto"
    private const val GRID_WIDTH = 100_000
    // The reference screen of the vertical model, as scripts/glide_pack.py documents it.
    private const val REFERENCE_SCREEN_WIDTH_PX = 1080
    private const val REFERENCE_DENSITY_DPI = 440
}
