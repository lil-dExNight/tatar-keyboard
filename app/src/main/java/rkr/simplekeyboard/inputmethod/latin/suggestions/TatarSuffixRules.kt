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
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.AfterWordForms
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.AfterWordFormsFactory
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.ImmutableUtf8Prefix
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.InflectedSuffixTable
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.WordFrequencySource
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.isValidUtf8Scalar

/**
 * P3 runtime Tatar suffix machinery (docs/TT-SUGGESTIONS.md): a fixed table of concrete inflectional
 * and frequent derivational suffix forms, a zero-allocation membership test over it, and a bounded
 * harmony-aware form generator.
 *
 * Consistency with the build-time generator (`scripts/wordform_gen.py`, P1): the table is the set of
 * SINGLE-suffix remainders the P1 paradigms can attach to a stem — plural, the five oblique cases,
 * the possessives, the verb tenses with their person composites, the gerunds and participles, the
 * masdar, and the seven derivational suffixes. Where the two deliberately differ:
 *
 *  - the runtime table is a fixed explicit list while P1 renders patterns; suffix CHAINS are not
 *    listed here (татарларның reaches the boost through its own steps: татарлар is boosted by «лар»
 *    at prefix татар, and татарларның by «ның» at prefix татарлар). The two chain shapes P1 emits
 *    directly — plural+case and 3sg-possessive+case from the bare stem — are covered the same way,
 *    since the plural and the 3sg forms are dictionary words of their own;
 *  - the past-tense 1pl -к composites (яздык) are listed although P1 emits no 1pl persons — the
 *    brief's person-ending set names -к explicitly, and the surface forms are ordinary words;
 *  - the post-3sg case forms (-н, -на/-нә, -нда/-ндә, -ннан/-ннән) attach to the 3sg-possessive
 *    word (баласы+н), which is why they are single suffixes here but chains from the bare stem;
 *  - the present-tense person endings -сың/-сең ride the identity-contracted present base of
 *    и-final vowel stems (ди+сең), the one place a bare person ending follows a stem directly.
 *
 * The membership test runs on the lookup hot path (the P3 same-stem boost in `TdictPrefixIndex`),
 * so it is strictly allocation-free: a binary search over the sorted table comparing against the
 * one- or two-piece byte ranges a front-coded schema-2 word is stored as, straight off the mapped
 * buffer. The generator runs once per committed word on the engine worker (the P3 after-word
 * forms), where bounded allocation is acceptable — see [generateForms].
 *
 * No regex anywhere: harmony and assimilation are decided by explicit letter sets.
 */
object TatarSuffixRules : InflectedSuffixTable, AfterWordFormsFactory {

    // --- Letter classes (mirror wordform_gen.py exactly) ---------------------------------------

    private const val BACK_VOWELS = "аыоуюя"
    private const val FRONT_VOWELS = "әеиөүэ"
    private const val VOWELS = BACK_VOWELS + FRONT_VOWELS
    private const val VOICELESS = "пкстчшфхцщ"
    private const val NASALS = "мнң"
    // Letters that mark an unassimilated Russian loan; such stems generate in BOTH harmonies.
    private const val RUSSIAN_MARKERS = "ёъьжцщ"

    private const val BACK = 0
    private const val FRONT = 1

    // --- The suffix table -----------------------------------------------------------------------
    //
    // Semantic groups, exactly the inventory of the P3 brief; the binary-search array is sorted
    // once at class init and verified distinct, so the groups below stay in review order rather
    // than collation order. Every entry is a concrete surface form (no archiphonemes left).

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
        // simple future (P1's lexical split): -ар/-әр monosyllabic consonant stems, -ыр/-ер longer
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

    // --- Membership (zero-allocation; the P3 same-stem boost calls this per candidate) ---------

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
     * bytes after a typed prefix come in at most two pieces; the caller passes them straight off the
     * mapped buffer and nothing is copied or allocated. Binary search, so a candidate costs ~8
     * piecewise comparisons. Empty pieces are allowed; an empty remainder (the typed word itself) is
     * never a suffix and never reaches here (the exact scan excludes it first).
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

    // --- Generation (bounded; runs once per committed word, off the lookup hot path) -----------

    /**
     * Appends the bounded P0 candidate inflections of a committed [stem] to [out], at most
     * [maxOut] of them: plural, the five oblique cases, the 3sg possessive with its four special
     * case forms, present/past/future 3sg, the -ып gerund, the -GAn participle, the masdar and
     * -чA. That is 18 forms per harmony variant; mixed-harmony and Russian-marker stems generate
     * BOTH variants (exactly like P1 — the caller's dictionary-presence filter keeps the attested
     * one), so [maxOut] caps the total at well under 40. Duplicates (the vowel-stem -р future is
     * harmony-blind) and the bare stem itself are never emitted.
     *
     * Runs once per committed word on the engine worker, NOT on the per-keystroke lookup path; the
     * small bounded allocations it makes (the form strings themselves) are deliberate. Returns the
     * number of forms appended. A stem with no harmony vowel adds nothing.
     *
     * Known deliberate simplifications versus P1 (the dictionary filter absorbs both): the
     * п→б/к→г possessive voicing of the exceptions table is not replicated (китап generates
     * китапы, which is not a dictionary word, while the real китабы simply does not get offered),
     * and neither are the suppletive pronouns and the per-stem verb overrides.
     */
    fun generateForms(stem: String, out: MutableList<String>, maxOut: Int): Int {
        var added = 0
        val variants = harmonyVariants(stem)
        for (harmony in variants) {
            if (added >= maxOut) break
            val last = stem[stem.length - 1]
            val vowelFinal = last in VOWELS
            val a = if (harmony == BACK) 'а' else 'ә'
            val i = if (harmony == BACK) 'ы' else 'е'
            val u = if (harmony == BACK) 'у' else 'ү'
            val g = if (last in VOICELESS) 'к' else 'г'
            val d = if (last in VOICELESS) 'т' else 'д'
            val l = if (last in NASALS) 'н' else 'л'
            val abl = if (last in VOICELESS) 'т' else if (last in NASALS) 'н' else 'д'

            added += emit(out, maxOut - added, stem, stem + l + a + 'р') // plural
            added += emit(out, maxOut - added, stem, stem + "н" + i + 'ң') // genitive
            added += emit(out, maxOut - added, stem, stem + g + a) // dative
            added += emit(out, maxOut - added, stem, stem + "н" + i) // accusative
            added += emit(out, maxOut - added, stem, stem + d + a) // locative
            added += emit(out, maxOut - added, stem, stem + abl + a + 'н') // ablative

            // 3sg possessive: bare -ы/-е after consonants and у/ү (суы), -сы/-се elsewhere (абасы).
            val possessive3 = if (!vowelFinal || last == 'у' || last == 'ү') {
                stem + i
            } else {
                stem + 'с' + i
            }
            added += emit(out, maxOut - added, stem, possessive3)
            // The post-3sg cases ride the possessive's own harmony (ы back, е front).
            val pa = if (harmony == BACK) 'а' else 'ә'
            added += emit(out, maxOut - added, stem, possessive3 + 'н')
            added += emit(out, maxOut - added, stem, possessive3 + 'н' + pa)
            added += emit(out, maxOut - added, stem, possessive3 + "н" + 'д' + pa)
            added += emit(out, maxOut - added, stem, possessive3 + "нн" + pa + 'н')

            // Present 3sg: -а/-ә after consonants; vowel-final stems contract the final vowel to
            // ый/и (укы→укый, эшлә→эшли) — P1's Y rule.
            val present = if (vowelFinal) {
                stem.substring(0, stem.length - 1) + (if (harmony == BACK) "ый" else "и")
            } else {
                stem + a
            }
            added += emit(out, maxOut - added, stem, present)
            added += emit(out, maxOut - added, stem, stem + d + i) // past 3sg -DI
            added += emit(out, maxOut - added, stem, futureForm(stem, harmony, vowelFinal))
            added += emit(out, maxOut - added, stem, if (vowelFinal) stem + 'п' else stem + i + 'п')
            added += emit(out, maxOut - added, stem, stem + g + a + 'н') // -GAn
            // Masdar: -у/-ү after consonants and а/ә/о/ө/э-final stems; the й-glide spelling -ю
            // after ы/и/у/ү-final (җыю, дию).
            val masdar = if (vowelFinal && last !in "аәоөэ") stem + 'ю' else stem + u
            added += emit(out, maxOut - added, stem, masdar)
            added += emit(out, maxOut - added, stem, stem + 'ч' + a) // -чA
        }
        return added
    }

    /** P1's lexically split simple future: -р/-яр on vowel stems, -ар/-әр vs -ыр/-ер by syllables. */
    private fun futureForm(stem: String, harmony: Int, vowelFinal: Boolean): String {
        if (vowelFinal) {
            return if (syllableCount(stem) == 1) stem + "яр" else stem + 'р'
        }
        val vowel = if (syllableCount(stem) == 1) {
            if (harmony == BACK) 'а' else 'ә'
        } else {
            if (harmony == BACK) 'ы' else 'е'
        }
        return stem + vowel + 'р'
    }

    /** Rough syllable count: one per harmony vowel letter — the same rule wordform_gen applies. */
    private fun syllableCount(word: String): Int {
        var count = 0
        for (char in word) {
            if (char in VOWELS) count++
        }
        return count
    }

    /** Appends [form] when it is new (not the stem, not already listed) and room remains. */
    private fun emit(out: MutableList<String>, room: Int, stem: String, form: String): Int {
        if (room <= 0 || form == stem || form in out) return 0
        out.add(form)
        return 1
    }

    /**
     * The harmony of [word] by its last stem syllable, or null when it has no harmony vowel. A
     * final у/ү/ю/я right after another vowel is a diphthong glide and does not decide (эшләү is
     * front, дию is front) — the same rule `wordform_gen.harmony_of` applies.
     */
    private fun harmonyOf(word: String): Int? {
        for (index in word.length - 1 downTo 0) {
            val char = word[index]
            if (char in "уүюя" && index > 0 && word[index - 1] in VOWELS) continue
            if (char in BACK_VOWELS) return BACK
            if (char in FRONT_VOWELS) return FRONT
        }
        return null
    }

    /**
     * The harmony variants to generate: one for unambiguous stems, both (primary first) for
     * mixed-harmony and Russian-marker stems — surface rules cannot tell совет from исем, so the
     * overgeneration is deliberate and left for the dictionary filter, exactly as in P1.
     */
    private fun harmonyVariants(word: String): IntArray {
        val primary = harmonyOf(word) ?: return IntArray(0)
        var hasBack = false
        var hasFront = false
        var hasMarker = false
        for (char in word) {
            if (char in BACK_VOWELS) hasBack = true
            if (char in FRONT_VOWELS) hasFront = true
            if (char in RUSSIAN_MARKERS) hasMarker = true
        }
        return if ((hasBack && hasFront) || hasMarker) {
            intArrayOf(primary, if (primary == BACK) FRONT else BACK)
        } else {
            intArrayOf(primary)
        }
    }

    // --- AfterWordFormsFactory: the P3 after-word forms of the Tatar NEXT_WORD slot -------------

    override fun createAfterWordForms(dictionary: WordFrequencySource): AfterWordForms =
        TatarAfterWordForms(this, dictionary)
}

/**
 * The Tatar [AfterWordForms]: inflected forms of the just-committed context word that are
 * themselves dictionary entries, frequency-ranked.
 *
 * Bounded and allocation-light, not allocation-free: it runs once per NEXT_WORD request on the
 * engine worker (never on the prefix-scan hot path), generates at most a few dozen candidates and
 * materializes up to [maxOut] result strings — the same class of cost the bigram predict already
 * pays per request. Every early exit fails toward the bigram-only list.
 */
internal class TatarAfterWordForms(
    private val rules: TatarSuffixRules,
    private val dictionary: WordFrequencySource,
) : AfterWordForms {

    override fun formsOf(
        contextWord: ImmutableUtf8Prefix,
        alreadyShown: List<String>,
        maxOut: Int,
    ): List<String> {
        if (maxOut <= 0) return emptyList()
        val byteCount = contextWord.byteCount
        if (byteCount == 0 || byteCount > MAX_CONTEXT_BYTES || !isValidUtf8Scalar(contextWord)) {
            return emptyList()
        }
        val stem = contextWord.decodeUtf8()
        val generated = ArrayList<String>(GENERATION_CAP)
        rules.generateForms(stem, generated, GENERATION_CAP)
        if (generated.isEmpty()) return emptyList()

        // Keep dictionary words only, never the word itself or anything the bigrams already show;
        // rank by frequency descending, code-point ascending on ties — the exact frozen tie-break
        // of the prefix pass. The list is bounded by GENERATION_CAP, so this insertion sort is
        // bounded too.
        val forms = ArrayList<String>(generated.size)
        val frequencies = LongArray(generated.size)
        for (form in generated) {
            if (form == stem || alreadyShown.contains(form) || forms.contains(form)) continue
            val frequency = dictionary.frequencyOf(form)
            if (frequency <= 0L) continue
            var at = forms.size
            for (slot in forms.indices) {
                val ranked = frequencies[slot]
                if (frequency > ranked || (frequency == ranked && form < forms[slot])) {
                    at = slot
                    break
                }
            }
            // Shift the parallel frequency array right by one to stay in step with the list insert.
            for (slot in forms.size downTo at + 1) frequencies[slot] = frequencies[slot - 1]
            frequencies[at] = frequency
            forms.add(at, form)
        }
        if (forms.isEmpty()) return emptyList()
        return forms.subList(0, minOf(forms.size, maxOut)).toList()
    }

    companion object {
        /** Same bound the bigram table applies to a context word. */
        private const val MAX_CONTEXT_BYTES = 128

        /**
         * Hard cap on generated candidates before the dictionary filter: 18 forms times two
         * harmony variants, rounded up — generation can never produce more by construction.
         */
        private const val GENERATION_CAP = 40
    }
}
