package rkr.simplekeyboard.inputmethod.latin.suggestions

import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.ChatSubstitutionTable

/**
 * Russian letters that Tatar chat spelling replaces with the Tatar partner letter. The map is
 * one directed table: a typed Russian letter becomes its Tatar spelling, and both `э` and `а`
 * map to `ә` because Tatar chat text uses either front-vowel substitute.
 */
object TatarChatSpellingRules : ChatSubstitutionTable {
    override fun replacementOf(codePoint: Int): Int = when (codePoint) {
        'э'.code -> 'ә'.code
        'а'.code -> 'ә'.code
        'о'.code -> 'ө'.code
        'у'.code -> 'ү'.code
        'ж'.code -> 'җ'.code
        'н'.code -> 'ң'.code
        'х'.code -> 'һ'.code
        else -> codePoint
    }
}
