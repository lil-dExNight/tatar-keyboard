package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.E3aTestFixtures.rawKey

/**
 * Shared fixture: the full Tatar alphabet keyboard (all 37 letter keys of `rows_tatar.xml`, in
 * layout order) with the long-press partners of `rowkeys_tatar*.xml`. Unlike [E3aTestFixtures],
 * which lists only the letters its tests use, the node set here is the complete layout alphabet
 * that edit class #4 substitutes from.
 *
 * Production derives the table from the live keyboard (`KeyNeighborTableBuilder.fromKeyboard`);
 * no production source carries a Cyrillic literal (asserted by [E3bEngineSourceContractTest]).
 */
internal object E3bTestFixtures {
    fun tatarNeighborTable(subtypeId: String = "tt_RU"): KeyNeighborTable =
        KeyNeighborTable.build(
            subtypeId,
            true,
            listOf(
                // The extra Tatar row: ә ө ү җ ң һ
                rawKey('ә'), rawKey('ө'), rawKey('ү'), rawKey('җ'), rawKey('ң'), rawKey('һ'),
                // й ц у к е н г ш щ з х
                rawKey('й'), rawKey('ц'), rawKey('у', 'ү'), rawKey('к'), rawKey('е', 'ё'),
                rawKey('н', 'ң'), rawKey('г', 'һ'), rawKey('ш'), rawKey('щ'), rawKey('з'),
                rawKey('х', 'һ'),
                // ф ы в а п р о л д ж э
                rawKey('ф'), rawKey('ы'), rawKey('в'), rawKey('а', 'ә'), rawKey('п'), rawKey('р'),
                rawKey('о', 'ө'), rawKey('л'), rawKey('д'), rawKey('ж', 'җ'), rawKey('э', 'ә'),
                // я ч с м и т ь б ю
                rawKey('я'), rawKey('ч'), rawKey('с'), rawKey('м'), rawKey('и'), rawKey('т'),
                rawKey('ь', 'ъ'), rawKey('б'), rawKey('ю'),
            ),
        )
}
