package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

/**
 * Shared fixture for the E3b fuzzy-suggestion tests (edit classes #2 and #3).
 *
 * Unlike [E3aTestFixtures] — whose keys all sit at the same degenerate rectangle so no geometric
 * neighbour is derived and the class #1 tests exercise long-press alone — this fixture reconstructs
 * the real Tatar layout geometry so [KeyNeighborTable] derives the edit class #2 relation.
 *
 * TT-TYPO-NEXT Phase B (docs/TT-TYPO-NEXT.md): the reconstruction is now DEVICE-TRUE. The
 * pre-Phase-B grid packed keys edge-to-edge, which made same-row keys "touch" (`right == left`)
 * and produced 33 same-row pairs the device never has: on a real build KeyboardRow subtracts the
 * horizontal gap (`config_key_horizontal_gap`, 1.739%p on a phone) from every key's width and
 * advances the next key by the full PADDED width, so `right < left` for every same-row pair and
 * only the 32 cross-row pairs survive. The grid below reproduces the device formula
 * (KeyboardBuilder/KeyboardRow/Key) on a 100 000-px reference width with one Math.round per key
 * edge; the identical relation is reconstructed offline by `scripts/typo_pack.py` from the same
 * committed resources (layout XML for widths/order, `res/values/config.xml` for the gap), the
 * byte-identical class-#2 typo-set SHA-256 in [E3bRecoveryCalibrationTest] proves the two agree,
 * and the Phase-B instrumentation run dumps the live on-device table for comparison.
 *
 * Production still derives the relation from the live keyboard
 * (`KeyNeighborTableBuilder.fromKeyboard`). Building a fixture from explicit key descriptors is a
 * test concern; no production source carries a Cyrillic literal or a hard-coded key pair (asserted
 * by [E3bEngineSourceContractTest]).
 */
internal object E3bTestFixtures {

    // The device formula (see above) on the reference grid. The gap/padding values mirror
    // res/values/config.xml (phone portrait); the pair set is proven identical under every shipped
    // config variant by the typo_pack python tests, so one variant is enough here.
    private const val GRID_WIDTH = 100_000.0
    private const val GAP = 1.739 / 100.0 * GRID_WIDTH
    private const val PADDING = 0.870 / 100.0 * GRID_WIDTH
    private const val BASE_WIDTH = GRID_WIDTH - 2 * PADDING + GAP
    private const val RIGHT_EDGE = GRID_WIDTH - PADDING

    // Key widths in percent points, mirroring rows_tatar.xml: the extra row of six 16.667%p keys,
    // two rows of eleven 9.091%p keys, and the bottom letter row behind the 10.8%p shift key (the
    // fillRight delete key only bounds the last key's clamp and carries no letter).
    private val ROW_WIDTHS = listOf(
        listOf(16.667, 16.667, 16.667, 16.667, 16.667, 16.667),
        listOf(9.091, 9.091, 9.091, 9.091, 9.091, 9.091, 9.091, 9.091, 9.091, 9.091, 9.091),
        listOf(9.091, 9.091, 9.091, 9.091, 9.091, 9.091, 9.091, 9.091, 9.091, 9.091, 9.091),
        listOf(10.8, 8.711, 8.711, 8.711, 8.711, 8.711, 8.711, 8.711, 8.711, 8.711),
    )

    /**
     * The key at index [col] of [row] (the bottom row's shift key counts, so its letters start at
     * col 1), with the device-modelled edges: x advances by padded widths, the key itself is the
     * padded width minus the gap, clamped to the padded right edge, rounded once per edge exactly
     * as Key does.
     */
    private fun geoKey(base: Char, row: Int, col: Int, vararg partners: Char): KeyNeighborTable.RawKey {
        val widths = ROW_WIDTHS[row]
        var x = PADDING
        for (index in 0 until col) x += widths[index] / 100.0 * BASE_WIDTH
        var width = widths[col] / 100.0 * BASE_WIDTH - GAP
        if (x + width > RIGHT_EDGE) width = RIGHT_EDGE - x
        return KeyNeighborTable.RawKey(
            base.code, Math.round(x).toInt(), row, Math.round(x + width).toInt(), row + 1,
            IntArray(partners.size) { partners[it].code },
        )
    }

    /** The Tatar alphabet keyboard, geometry and long-press partners mirroring the layout XML. */
    fun tatarNeighborTable(subtypeId: String = "tt_RU"): KeyNeighborTable =
        KeyNeighborTable.build(
            subtypeId,
            true,
            listOf(
                // Row 0 — the extra Tatar row: ә ө ү җ ң һ
                geoKey('ә', 0, 0), geoKey('ө', 0, 1), geoKey('ү', 0, 2),
                geoKey('җ', 0, 3), geoKey('ң', 0, 4), geoKey('һ', 0, 5),
                // Row 1: й ц у к е н г ш щ з х
                geoKey('й', 1, 0), geoKey('ц', 1, 1), geoKey('у', 1, 2, 'ү'),
                geoKey('к', 1, 3), geoKey('е', 1, 4, 'ё'), geoKey('н', 1, 5, 'ң'),
                geoKey('г', 1, 6, 'һ'), geoKey('ш', 1, 7), geoKey('щ', 1, 8),
                geoKey('з', 1, 9), geoKey('х', 1, 10, 'һ'),
                // Row 2: ф ы в а п р о л д ж э
                geoKey('ф', 2, 0), geoKey('ы', 2, 1), geoKey('в', 2, 2),
                geoKey('а', 2, 3, 'ә'), geoKey('п', 2, 4), geoKey('р', 2, 5),
                geoKey('о', 2, 6, 'ө'), geoKey('л', 2, 7), geoKey('д', 2, 8),
                geoKey('ж', 2, 9, 'җ'), geoKey('э', 2, 10, 'ә'),
                // Row 3 (behind the shift key): я ч с м и т ь б ю
                geoKey('я', 3, 1), geoKey('ч', 3, 2), geoKey('с', 3, 3),
                geoKey('м', 3, 4), geoKey('и', 3, 5), geoKey('т', 3, 6),
                geoKey('ь', 3, 7, 'ъ'), geoKey('б', 3, 8), geoKey('ю', 3, 9),
            ),
        )
}
