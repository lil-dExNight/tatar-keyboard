package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

/**
 * Shared fixture: the full Tatar alphabet keyboard (all 39 letter keys of `rows_tatar.xml`, in
 * layout order) with the long-press partners of `rowkeys_tatar*.xml`. Unlike
 * [LongPressTestFixtures], which lists only the letters its tests use, the node set here is the
 * complete layout alphabet that edit class #4 substitutes from.
 *
 * Every key also carries its rectangle, so the table's geometric adjacency (the DL-1 rank key's
 * neighbor bonus) is exercised under the production wiring. The x edges are the device model of
 * scripts/typo_pack.py (build_device_geometry) on the 100 000-unit reference grid; the y edges
 * follow the shipped 5-row variant (rowHeight 20%p, vertical gap 2.814%p of the keyboard
 * height), so the key height is 17 186 and the row pitch 20 000 reference units. The extra top
 * row is the eight Tatar-only letters at 12.5%p, so ё and ъ are direct keys of the layout.
 *
 * Production derives the table from the live keyboard (`KeyNeighborTableBuilder.fromKeyboard`);
 * no production source carries a Cyrillic literal (asserted by
 * [EngineLayoutLiteralSourceContractTest]). The eval mutation substrate's recorded neighbor map
 * (TypoMutatedEvalTest.GEOMETRIC_NEIGHBORS) and its pins are regenerated from the layout by
 * scripts/typo_pack.py in the asset-rebuild step, so they trail this fixture until then.
 */
internal object TatarLayoutTestFixtures {
    fun tatarNeighborTable(subtypeId: String = "tt_RU"): KeyNeighborTable =
        KeyNeighborTable.build(
            subtypeId,
            true,
            listOf(
                // The extra Tatar row: ә ө ү җ ң һ ё ъ (row top 0), eight keys at 12.5%p.
                geoKey(870, 0, 11631, 'ә'), geoKey(13370, 0, 24131, 'ө'),
                geoKey(25870, 0, 36631, 'ү'), geoKey(38370, 0, 49131, 'җ'),
                geoKey(50870, 0, 61630, 'ң'), geoKey(63369, 0, 74130, 'һ'),
                geoKey(75869, 0, 86630, 'ё'), geoKey(88369, 0, 99130, 'ъ'),
                // й ц у к е н г ш щ з х (row top 20000)
                geoKey(870, 20000, 8222, 'й'), geoKey(9961, 20000, 17313, 'ц'),
                geoKey(19052, 20000, 26404, 'у', 'ү'), geoKey(28143, 20000, 35495, 'к'),
                geoKey(37234, 20000, 44586, 'е', 'ё'), geoKey(46325, 20000, 53676, 'н', 'ң'),
                geoKey(55415, 20000, 62767, 'г', 'һ'), geoKey(64506, 20000, 71858, 'ш'),
                geoKey(73597, 20000, 80949, 'щ'), geoKey(82688, 20000, 90040, 'з'),
                geoKey(91779, 20000, 99130, 'х', 'һ'),
                // ф ы в а п р о л д ж э (row top 40000)
                geoKey(870, 40000, 8222, 'ф'), geoKey(9961, 40000, 17313, 'ы'),
                geoKey(19052, 40000, 26404, 'в'), geoKey(28143, 40000, 35495, 'а', 'ә'),
                geoKey(37234, 40000, 44586, 'п'), geoKey(46325, 40000, 53676, 'р'),
                geoKey(55415, 40000, 62767, 'о', 'ө'), geoKey(64506, 40000, 71858, 'л'),
                geoKey(73597, 40000, 80949, 'д'), geoKey(82688, 40000, 90040, 'ж', 'җ'),
                geoKey(91779, 40000, 99130, 'э', 'ә'),
                // я ч с м и т ь б ю (row top 60000)
                geoKey(11670, 60000, 18642, 'я'), geoKey(20381, 60000, 27353, 'ч'),
                geoKey(29092, 60000, 36064, 'с'), geoKey(37803, 60000, 44775, 'м'),
                geoKey(46514, 60000, 53485, 'и'), geoKey(55224, 60000, 62196, 'т'),
                geoKey(63935, 60000, 70907, 'ь', 'ъ'), geoKey(72646, 60000, 79618, 'б'),
                geoKey(81357, 60000, 88329, 'ю'),
            ),
        )

    /** One letter key with its rectangle: [left], [top] and [right] as dumped; height 17 186. */
    private fun geoKey(
        left: Int,
        top: Int,
        right: Int,
        base: Char,
        vararg partners: Char,
    ): KeyNeighborTable.RawKey =
        KeyNeighborTable.RawKey(
            base.code,
            IntArray(partners.size) { partners[it].code },
            left, top, right, top + KEY_HEIGHT,
        )

    private const val KEY_HEIGHT = 17_186
}
