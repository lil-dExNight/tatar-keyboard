package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

/**
 * Shared fixtures for the E3a fuzzy-suggestion tests.
 *
 * The neighbor table is built through the public [KeyNeighborTable.build] from hand-written
 * [KeyNeighborTable.RawKey]s that mirror the long-press pairs declared in `rowkeys_tatar*.xml`.
 * Building a fixture from explicit key descriptors is a test concern; production still derives the
 * table from the live layout (`KeyNeighborTableBuilder.fromKeyboard`).
 */
internal object E3aTestFixtures {
    /** The Tatar long-press pairs (а↔ә, о↔ө, у↔ү, ж↔җ, н↔ң, г↔һ, х↔һ, е↔ё, ь↔ъ, э↔ә). */
    fun tatarNeighborTable(subtypeId: String = "tt_RU"): KeyNeighborTable =
        KeyNeighborTable.build(
            subtypeId,
            true,
            listOf(
                rawKey('а', 'ә'),
                rawKey('о', 'ө'),
                rawKey('у', 'ү'),
                rawKey('ж', 'җ'),
                rawKey('н', 'ң'),
                rawKey('г', 'һ'),
                rawKey('х', 'һ'),
                rawKey('е', 'ё'),
                rawKey('ь', 'ъ'),
                rawKey('э', 'ә'),
                // Plain letters used by the tests, none carrying a long-press partner.
                rawKey('к'), rawKey('и'), rawKey('т'), rawKey('с'), rawKey('м'),
                rawKey('п'), rawKey('б'), rawKey('л'), rawKey('р'), rawKey('д'),
                rawKey('я'), rawKey('ы'), rawKey('в'), rawKey('ф'),
            ),
        )

    fun rawKey(base: Char, vararg partners: Char): KeyNeighborTable.RawKey =
        KeyNeighborTable.RawKey(base.code, IntArray(partners.size) { partners[it].code })

    fun rawKey(baseCodePoint: Int, vararg partnerCodePoints: Int): KeyNeighborTable.RawKey =
        KeyNeighborTable.RawKey(baseCodePoint, partnerCodePoints)
}
