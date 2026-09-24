package rkr.simplekeyboard.inputmethod.latin.glide

/**
 * Shared fixture for the glide tests: the device-true Tatar letter-key geometry in the
 * 100 000-unit reference grid, mirroring `scripts/glide_pack.py` bit-for-bit.
 *
 * The x model is the E3bTestFixtures/typo_pack device formula (KeyboardBuilder/KeyboardRow/Key
 * with the horizontal gap, one round-half-up per edge). The vertical model is the documented
 * reference: the default 5-row Tatar keyboard (kbd_tatar.xml rowHeight 20%p, vertical gap
 * config_key_vertical_gap_5row 2.814%p, top padding config_keyboard_top_padding 2.335%p) at the
 * default height (config_default_keyboard_height 205.6 dp) on a 1080 px-wide 440 dpi reference
 * screen, each value rounded half-up in pixels and scaled into the grid by the same rule.
 * `GlideRecoveryCalibrationTest` proves the fixture and the python generator agree by
 * regenerating the pinned gesture set byte-for-byte.
 */
internal object GlideTestFixtures {
    private const val GRID_WIDTH = 100_000.0
    private const val GAP = 1.739 / 100.0 * GRID_WIDTH
    private const val PADDING = 0.870 / 100.0 * GRID_WIDTH
    private const val BASE_WIDTH = GRID_WIDTH - 2 * PADDING + GAP
    private const val RIGHT_EDGE = GRID_WIDTH - PADDING

    // The vertical model constants (see the class docstring; mirror scripts/glide_pack.py).
    private const val REFERENCE_SCREEN_WIDTH_PX = 1080
    private const val KEYBOARD_HEIGHT_PX = 565 // round(205.6 dp x 440 dpi / 160)
    private const val ROW_PITCH_PX = KEYBOARD_HEIGHT_PX * 20 / 100 // rowHeight 20%p
    private const val VERTICAL_GAP_PX = 16 // round(565 x 2.814%p)
    private const val TOP_PADDING_PX = 13 // round(565 x 2.335%p)

    private fun toGrid(px: Int): Int = Math.round(px.toFloat() * GRID_WIDTH.toFloat() / REFERENCE_SCREEN_WIDTH_PX)

    val ROW_PITCH = toGrid(ROW_PITCH_PX)
    val KEY_HEIGHT = toGrid(ROW_PITCH_PX - VERTICAL_GAP_PX)
    val TOP_PADDING = toGrid(TOP_PADDING_PX)

    // Key widths in percent points, mirroring rows_tatar.xml (same values as E3bTestFixtures).
    private val ROW_WIDTHS = listOf(
        listOf(16.667, 16.667, 16.667, 16.667, 16.667, 16.667),
        listOf(9.091, 9.091, 9.091, 9.091, 9.091, 9.091, 9.091, 9.091, 9.091, 9.091, 9.091),
        listOf(9.091, 9.091, 9.091, 9.091, 9.091, 9.091, 9.091, 9.091, 9.091, 9.091, 9.091),
        listOf(10.8, 8.711, 8.711, 8.711, 8.711, 8.711, 8.711, 8.711, 8.711, 8.711),
    )

    /** The key at index [col] of [row] with the device-modelled edges and the reference y. */
    private fun geoKey(
        base: Char,
        row: Int,
        col: Int,
        rowWidths: List<List<Double>> = ROW_WIDTHS,
    ): GlideKeyGeometry.RawKey {
        val widths = rowWidths[row]
        var x = PADDING
        for (index in 0 until col) x += widths[index] / 100.0 * BASE_WIDTH
        var width = widths[col] / 100.0 * BASE_WIDTH - GAP
        if (x + width > RIGHT_EDGE) width = RIGHT_EDGE - x
        val top = TOP_PADDING + row * ROW_PITCH
        return GlideKeyGeometry.RawKey(
            base.code, Math.round(x).toInt(), top,
            Math.round(x + width).toInt(), top + KEY_HEIGHT,
        )
    }

    // rows_russian.xml: the same qwerty rows without the extra Tatar row.
    private val RUSSIAN_ROW_WIDTHS = listOf(
        listOf(9.091, 9.091, 9.091, 9.091, 9.091, 9.091, 9.091, 9.091, 9.091, 9.091, 9.091),
        listOf(9.091, 9.091, 9.091, 9.091, 9.091, 9.091, 9.091, 9.091, 9.091, 9.091, 9.091),
        listOf(10.8, 8.711, 8.711, 8.711, 8.711, 8.711, 8.711, 8.711, 8.711, 8.711),
    )

    /** The Tatar alphabet keyboard's letter keys, mirroring the layout XML's key order. */
    fun tatarRawKeys(): List<GlideKeyGeometry.RawKey> = listOf(
        // Row 0 — the extra Tatar row.
        geoKey('ә', 0, 0), geoKey('ө', 0, 1), geoKey('ү', 0, 2),
        geoKey('җ', 0, 3), geoKey('ң', 0, 4), geoKey('һ', 0, 5),
        // Row 1.
        geoKey('й', 1, 0), geoKey('ц', 1, 1), geoKey('у', 1, 2),
        geoKey('к', 1, 3), geoKey('е', 1, 4), geoKey('н', 1, 5),
        geoKey('г', 1, 6), geoKey('ш', 1, 7), geoKey('щ', 1, 8),
        geoKey('з', 1, 9), geoKey('х', 1, 10),
        // Row 2.
        geoKey('ф', 2, 0), geoKey('ы', 2, 1), geoKey('в', 2, 2),
        geoKey('а', 2, 3), geoKey('п', 2, 4), geoKey('р', 2, 5),
        geoKey('о', 2, 6), geoKey('л', 2, 7), geoKey('д', 2, 8),
        geoKey('ж', 2, 9), geoKey('э', 2, 10),
        // Row 3 (behind the shift key).
        geoKey('я', 3, 1), geoKey('ч', 3, 2), geoKey('с', 3, 3),
        geoKey('м', 3, 4), geoKey('и', 3, 5), geoKey('т', 3, 6),
        geoKey('ь', 3, 7), geoKey('б', 3, 8), geoKey('ю', 3, 9),
    )

    /**
     * The Russian alphabet keyboard's letter keys: the same three qwerty rows WITHOUT the extra
     * Tatar row (rows_russian.xml), so the letters sit one row pitch higher. The widths are the
     * same percent values (9.091/9.091/10.8+8.711).
     */
    fun russianRawKeys(): List<GlideKeyGeometry.RawKey> = listOf(
        geoKey('й', 0, 0, RUSSIAN_ROW_WIDTHS), geoKey('ц', 0, 1, RUSSIAN_ROW_WIDTHS),
        geoKey('у', 0, 2, RUSSIAN_ROW_WIDTHS), geoKey('к', 0, 3, RUSSIAN_ROW_WIDTHS),
        geoKey('е', 0, 4, RUSSIAN_ROW_WIDTHS), geoKey('н', 0, 5, RUSSIAN_ROW_WIDTHS),
        geoKey('г', 0, 6, RUSSIAN_ROW_WIDTHS), geoKey('ш', 0, 7, RUSSIAN_ROW_WIDTHS),
        geoKey('щ', 0, 8, RUSSIAN_ROW_WIDTHS), geoKey('з', 0, 9, RUSSIAN_ROW_WIDTHS),
        geoKey('х', 0, 10, RUSSIAN_ROW_WIDTHS),
        geoKey('ф', 1, 0, RUSSIAN_ROW_WIDTHS), geoKey('ы', 1, 1, RUSSIAN_ROW_WIDTHS),
        geoKey('в', 1, 2, RUSSIAN_ROW_WIDTHS), geoKey('а', 1, 3, RUSSIAN_ROW_WIDTHS),
        geoKey('п', 1, 4, RUSSIAN_ROW_WIDTHS), geoKey('р', 1, 5, RUSSIAN_ROW_WIDTHS),
        geoKey('о', 1, 6, RUSSIAN_ROW_WIDTHS), geoKey('л', 1, 7, RUSSIAN_ROW_WIDTHS),
        geoKey('д', 1, 8, RUSSIAN_ROW_WIDTHS), geoKey('ж', 1, 9, RUSSIAN_ROW_WIDTHS),
        geoKey('э', 1, 10, RUSSIAN_ROW_WIDTHS),
        geoKey('я', 2, 1, RUSSIAN_ROW_WIDTHS), geoKey('ч', 2, 2, RUSSIAN_ROW_WIDTHS),
        geoKey('с', 2, 3, RUSSIAN_ROW_WIDTHS), geoKey('м', 2, 4, RUSSIAN_ROW_WIDTHS),
        geoKey('и', 2, 5, RUSSIAN_ROW_WIDTHS), geoKey('т', 2, 6, RUSSIAN_ROW_WIDTHS),
        geoKey('ь', 2, 7, RUSSIAN_ROW_WIDTHS), geoKey('б', 2, 8, RUSSIAN_ROW_WIDTHS),
        geoKey('ю', 2, 9, RUSSIAN_ROW_WIDTHS),
    )

    fun tatarGeometry(): GlideKeyGeometry = GlideKeyGeometry.build(tatarRawKeys())

    fun russianGeometry(): GlideKeyGeometry = GlideKeyGeometry.build(russianRawKeys())

    /** min over keys of min(width, height) in integer grid units (the generator's scale). */
    fun tatarKeyRadius(): Int = tatarRawKeys().minOf { minOf(it.right - it.left, it.bottom - it.top) }

    /** The ideal center-to-center path of [word] over [geometry] (no noise); null when a letter
     * has no key. Shared by the engine-level and the controller-level glide tests. */
    fun idealPath(word: String, geometry: GlideKeyGeometry): GlidePath? {
        val path = GlidePath()
        var t = 0f
        var offset = 0
        while (offset < word.length) {
            val codePoint = word.codePointAt(offset)
            offset += Character.charCount(codePoint)
            val key = geometry.keyIndexOfLetter(Character.toLowerCase(codePoint))
            if (key < 0) return null
            path.addPoint(geometry.centerX(key), geometry.centerY(key), t)
            t += 8f
        }
        return path
    }
}

/** A list-backed [GlideWordInventory] for fixture tests (entries are in "dictionary" order). */
internal class ListGlideInventory(
    private val entries: List<Pair<String, Long>>,
) : GlideWordInventory {
    override val entryCount: Int get() = entries.size

    override fun wordAt(index: Int): String = entries[index].first

    override fun forEachWord(visitor: GlideWordVisitor) {
        for ((word, frequency) in entries) visitor.visit(word, frequency)
    }
}
