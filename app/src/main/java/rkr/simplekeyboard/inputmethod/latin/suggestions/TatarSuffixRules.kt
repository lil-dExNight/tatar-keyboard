/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rkr.simplekeyboard.inputmethod.latin.suggestions

import java.nio.ByteBuffer
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.InflectedSuffixTable

/**
 * Runtime Tatar suffix rules: a fixed table of concrete inflectional and frequent derivational
 * suffix forms, with a zero-allocation membership test for the same-stem prefix boost.
 *
 * Consistency with the build-time generator (`scripts/wordform_gen.py`): the table is the set of
 * single-suffix remainders its paradigms can attach to a stem (plural, the five oblique cases, the
 * possessives, the verb tenses with their person composites, the gerunds and participles, the
 * masdar, and the seven derivational suffixes). Where the two differ on purpose:
 *
 *  - the runtime table is a fixed explicit list while the generator renders patterns; suffix
 *    chains are not listed here (татарларның reaches the boost in steps: татарлар is boosted by
 *    «лар» at prefix татар, and татарларның by «ның» at prefix татарлар). The generator's two
 *    direct chain shapes, plural+case and 3sg-possessive+case, are covered the same way, since the
 *    plural and the 3sg forms are dictionary words of their own;
 *  - the past-tense 1pl -к composites (яздык) are listed although the generator emits no 1pl
 *    persons: -к is a standard person ending and the surface forms are ordinary words;
 *  - the post-3sg case forms (-н, -на/-нә, -нда/-ндә, -ннан/-ннән) attach to the 3sg-possessive
 *    word (баласы+н), which is why they are single suffixes here but chains from the bare stem;
 *  - the present-tense person endings -сың/-сең ride the identity-contracted present base of
 *    и-final vowel stems (ди+сең), the one place a bare person ending follows a stem directly.
 *
 * The membership test runs on the lookup hot path (the same-stem boost in `TdictPrefixIndex`), so
 * it is allocation-free: a binary search over the sorted table against the one- or two-piece byte
 * ranges a front-coded schema-2 word is stored as. The lookup path passes a heap copy of the
 * mapped block (see `TdictPrefixIndex`).
 */
object TatarSuffixRules : InflectedSuffixTable {

    // --- The suffix table -----------------------------------------------------------------------
    //
    // Semantic groups. The binary-search array is sorted once at class init and verified
    // distinct, so the groups below stay in review order rather than collation order. Every entry
    // is a concrete surface form (no archiphonemes left).

    private val GROUPS: Array<Pair<String, Array<String>>> = arrayOf(
        // plural -LAr (н after nasals: урманнар)
        "plural -LAr" to arrayOf("лар", "ләр", "нар", "нәр"),
        // genitive -нIң
        "genitive -нIң" to arrayOf("ның", "нең"),
        // dative -GA (к after voiceless: китапка)
        "dative -GA" to arrayOf("га", "гә", "ка", "кә"),
        // accusative -нI
        "accusative -нI" to arrayOf("ны", "не"),
        // locative -DA (т after voiceless)
        "locative -DA" to arrayOf("да", "дә", "та", "тә"),
        // ablative -TAн (т after voiceless, н after nasals: урманнан)
        "ablative -TAн" to arrayOf("дан", "дән", "тан", "тән", "нан", "нән"),
        // possessive 1sg -Iм (-м after vowels: абам), 2sg -Iң (-ң), 3sg -I/-сI (суы but абасы)
        "possessive 1sg -Iм/-м" to arrayOf("ым", "ем", "м"),
        "possessive 2sg -Iң/-ң" to arrayOf("ың", "ең", "ң"),
        "possessive 3sg -I/-сI" to arrayOf("ы", "е", "сы", "се"),
        "possessive 1pl -IбIз/-бIз" to arrayOf("ыбыз", "ебез", "быз", "без"),
        "possessive 2pl -IгIз/-гIз" to arrayOf("ыгыз", "егез", "гыз", "гез"),
        "possessive 3pl -LArI" to arrayOf("лары", "ләре", "нары", "нәре"),
        // cases after the 3sg possessive (баласы+н/на/нда/ыннан) — they attach to the 3sg word
        "post-3sg accusative -н" to arrayOf("н"),
        "post-3sg dative -нA" to arrayOf("на", "нә"),
        "post-3sg locative -нDA" to arrayOf("нда", "ндә"),
        "post-3sg ablative -ннAн" to arrayOf("ннан", "ннән"),
        // present -A 3sg, and the type-I persons -м/-сың/-сыз/-лар riding the present base
        "present 3sg -A" to arrayOf("а", "ә"),
        "present persons" to arrayOf(
            "ам", "әм", "асың", "әсең", "асыз", "әсез", "алар", "әләр",
            // -сың/-сең also follow an и-final stem directly (ди+сең: the contracted base IS the
            // stem) — the one bare person ending a stem can take.
            "сың", "сең",
        ),
        // negative present -мый/-ми (front и, not *-мә) and its persons
        "negative present -мый/-ми and persons" to arrayOf(
            "мый", "ми", "мыйм", "мим", "мыйсың", "мисең", "мыйсыз", "мисез", "мыйлар", "миләр",
        ),
        // past -DI (т after voiceless) and the type-II persons -м/-ң/-к/-гыз/-лар
        "past 3sg -DI" to arrayOf("ды", "де", "ты", "те"),
        "past persons" to arrayOf(
            "дым", "дем", "тым", "тем", "дың", "дең", "тың", "тең",
            "дык", "дек", "тык", "тек",
            "дыгыз", "дегез", "тыгыз", "тегез", "дылар", "деләр", "тылар", "теләр",
        ),
        // negative past -мA+DI and persons
        "negative past -мA+DI and persons" to arrayOf(
            "мады", "мәде", "мадым", "мәдем", "мадың", "мәдең",
            "мадыгыз", "мәдегез", "мадылар", "мәделәр",
        ),
        // -GAn participle / recent past (к after voiceless), plus the negated -мA+GAn
        "participle -GAн" to arrayOf("ган", "гән", "кан", "кән", "маган", "мәгән"),
        // simple future (lexical split): -ар/-әр monosyllabic consonant stems, -ыр/-ер longer
        // ones, -р vowel stems, -яр monosyllabic vowel stems (дияр); negative -мAс
        "future -Ap/-Ip/-р/-яр" to arrayOf("ар", "әр", "ыр", "ер", "р", "яр"),
        "negative future -мAс" to arrayOf("мас", "мәс"),
        // definite future -(A)чAк (-ячAк after vowels)
        "definite future -(A)чAк" to arrayOf("ачак", "әчәк", "ячак", "ячәк"),
        // conditional -сA and negated -мA+сA
        "conditional -сA" to arrayOf("са", "сә", "маса", "мәсә"),
        // gerunds
        "gerund -(I)п" to arrayOf("ып", "еп", "п"),
        "gerund -GAч" to arrayOf("гач", "гәч", "кач", "кәч"),
        "gerund -GAнчI" to arrayOf("ганчы", "гәнче", "канчы", "кәнче"),
        "negative gerund -мыйча/-мичә" to arrayOf("мыйча", "мичә"),
        // participles -UчI, -AсI (consonant stems only); masdar -U (-ю after ы/и/у/ү-final)
        "participle -UчI" to arrayOf("учы", "үче"),
        "participle -AсI" to arrayOf("асы", "әсе"),
        "masdar -U/-ю" to arrayOf("у", "ү", "ю"),
        "intention -мAкчI" to arrayOf("макчы", "мәкче"),
        // derivational: -чA (татарча), -лIк, -лI, -сIз, -чI, -DAш (т after voiceless), -рAк
        "derivational -чA" to arrayOf("ча", "чә"),
        "derivational -лIк" to arrayOf("лык", "лек"),
        "derivational -лI" to arrayOf("лы", "ле"),
        "derivational -сIз" to arrayOf("сыз", "сез"),
        "derivational -чI" to arrayOf("чы", "че"),
        "derivational -DAш" to arrayOf("даш", "дәш", "таш", "тәш"),
        "derivational -рAк" to arrayOf("рак", "рәк"),
    )

    /** The sorted table the binary search runs over; built and verified once at class init. */
    private val SUFFIXES: Array<ByteArray> = run {
        val all = GROUPS.flatMap { it.second.asIterable() }
        val encoded = all.map { it.toByteArray(Charsets.UTF_8) }.toTypedArray()
        encoded.sortWith { first, second -> compareBytes(first, second) }
        for (index in 1 until encoded.size) {
            require(compareBytes(encoded[index - 1], encoded[index]) < 0) {
                "suffix table must be strictly increasing"
            }
        }
        encoded
    }

    /** One-time self-check data for the unit tests: how many forms the table holds. */
    internal val suffixCount: Int
        get() = SUFFIXES.size

    private fun compareBytes(first: ByteArray, second: ByteArray): Int {
        val shared = minOf(first.size, second.size)
        for (offset in 0 until shared) {
            val difference = (first[offset].toInt() and 0xff) - (second[offset].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return first.size - second.size
    }

    // --- Membership (zero-allocation; the same-stem boost calls this per candidate) ------------

    /**
     * True when [remainder] is one of the table's suffix forms. Convenience string form of the
     * byte-range test below — it encodes, so it is for tests and other non-hot callers.
     */
    fun isInflectedContinuation(remainder: String): Boolean {
        if (remainder.isEmpty()) return false
        val bytes = remainder.toByteArray(Charsets.UTF_8)
        return isInflectedContinuation(ByteBuffer.wrap(bytes), 0, 0, 0, bytes.size)
    }

    /**
     * True when the remainder stored as the two contiguous pieces [firstStart, firstStart+firstLength)
     * and [secondStart, secondStart+secondLength) of [bytes] equals a suffix form of the table.
     *
     * A schema-2 word is a shared prefix of its block's first word plus a suffix of its own, so the
     * bytes after a typed prefix come in at most two pieces; the lookup path passes them as ranges
     * of its block copy, and nothing is copied or allocated. Empty pieces are allowed; an empty
     * remainder (the typed word itself) is never a suffix and is excluded before this call.
     */
    override fun isInflectedContinuation(
        bytes: ByteBuffer,
        firstStart: Int,
        firstLength: Int,
        secondStart: Int,
        secondLength: Int,
    ): Boolean {
        val remainderLength = firstLength + secondLength
        if (remainderLength == 0) return false
        var low = 0
        var high = SUFFIXES.size
        while (low < high) {
            val middle = (low + high) ushr 1
            val suffix = SUFFIXES[middle]
            val shared = minOf(remainderLength, suffix.size)
            var difference = 0
            for (offset in 0 until shared) {
                val actual = if (offset < firstLength) {
                    bytes.get(firstStart + offset)
                } else {
                    bytes.get(secondStart + (offset - firstLength))
                }
                difference = (actual.toInt() and 0xff) - (suffix[offset].toInt() and 0xff)
                if (difference != 0) break
            }
            if (difference == 0) difference = remainderLength - suffix.size
            when {
                difference == 0 -> return true
                difference > 0 -> low = middle + 1
                else -> high = middle
            }
        }
        return false
    }
}
