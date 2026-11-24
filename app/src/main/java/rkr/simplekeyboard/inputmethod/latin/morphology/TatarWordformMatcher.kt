package rkr.simplekeyboard.inputmethod.latin.morphology

internal const val MAX_QUERY_CODE_POINTS = 256

private const val HARMONY_AUTO = -1
private const val HARMONY_FRONT = 0
private const val HARMONY_BACK = 1

private const val BACK_VOWELS = "аыоуюя"
private const val FRONT_VOWELS = "әеиөүэ"
private const val VOWELS = BACK_VOWELS + FRONT_VOWELS
private const val GLIDE_VOWELS = "уүюя"
private const val HIATUS_FINAL = "иуү"
private const val VOICELESS = "пкстчшфхцщ"
private const val NASALS = "мнң"
private const val LOAN_DEVOICING_FINALS = "бгдз"

private const val STEM_SLOT = 0
private const val FORM_SLOT = 1
private const val PLURAL_SLOT = 2
private const val POSSESSIVE_STEM_SLOT = 3
private const val P1_SLOT = 4
private const val P2_SLOT = 5
private const val P3_SLOT = 6
private const val P1PL_SLOT = 7
private const val P2PL_SLOT = 8
private const val PLURAL_P1_SLOT = 9
private const val PLURAL_P2_SLOT = 10
private const val PLURAL_P3_SLOT = 11
private const val PLURAL_P1PL_SLOT = 12
private const val PLURAL_P2PL_SLOT = 13
private const val NEGATED_SLOT = 14
private const val TENSE_SLOT = 15
private const val TENSE_COUNT = 11
private const val AUX_SLOT = TENSE_SLOT + TENSE_COUNT
private const val SLOT_COUNT = AUX_SLOT + 1

private class PossessiveSpec(
    val caseLabels: Array<String>,
    val pluralBaseLabel: String,
    val pluralCaseLabels: Array<String>,
    val shortDative: Boolean,
)

private class PredicativeSpec(val plainLabel: String, val questionLabel: String, val pattern: String?)

private class TenseSpec(
    val kind: Int,
    val baseOverrideLabel: String,
    val endings: Array<String?>,
    val labels: Array<String>,
)

/** Allocation-free surface-form matcher; the acceptor serializes access to its scratch buffers. */
internal class TatarWordformMatcher {
    private val chars = CharArray(MAX_QUERY_CODE_POINTS * SLOT_COUNT)
    private val lengths = IntArray(SLOT_COUNT)
    private lateinit var query: CharArray
    private var queryLength = 0
    private var stemLength = 0
    private var harmony = HARMONY_FRONT
    private var devoicedFinal = false
    private var voiceFinal = true
    private var exception: LexicalException? = null

    val retainedByteEstimate: Long =
        chars.size * Char.SIZE_BYTES.toLong() + lengths.size * Int.SIZE_BYTES

    fun matches(
        stem: CharArray,
        length: Int,
        flags: Int,
        lexicalException: LexicalException?,
        queryChars: CharArray,
        queryCharsLength: Int,
        nounChain: Boolean,
        verbChain: Boolean,
    ): Boolean {
        query = queryChars
        queryLength = queryCharsLength
        stemLength = length
        exception = lexicalException
        voiceFinal = lexicalException?.loan != true
        devoicedFinal = lexicalException?.loan == true && stem[length - 1] in LOAN_DEVOICING_FINALS
        harmony = when {
            lexicalException?.forceBack == true -> HARMONY_BACK
            flags and FLAG_FORCE_BACK != 0 -> HARMONY_BACK
            flags and FLAG_BACK != 0 -> HARMONY_BACK
            else -> HARMONY_FRONT
        }
        copyIntoSlot(STEM_SLOT, stem, length)
        if (nounChain && matchNoun()) return true
        if (verbChain && matchVerb()) return true
        return nounChain && matchDerivations()
    }

    private fun matchNoun(): Boolean {
        val pronouns = exception?.pronouns
        if (pronouns != null) {
            for (index in pronouns.indices) {
                putString(FORM_SLOT, pronouns[index])
                if (matchesQuery(FORM_SLOT)) return true
            }
            return false
        }

        if (!copyOverride(PLURAL_SLOT, WordformLabels.NOUN_PL)) {
            attach(PLURAL_SLOT, STEM_SLOT, "LAр", harmony, false)
        }
        if (matchesQuery(PLURAL_SLOT)) return true
        for (index in NOUN_CASE_PATTERNS.indices) {
            attach(FORM_SLOT, STEM_SLOT, NOUN_CASE_PATTERNS[index], harmony, devoicedFinal)
            if (matchWithOverride(NOUN_CASE_LABELS[index])) return true
        }

        val possessiveBase = exception?.possessiveBase
        if (possessiveBase == null) copySlot(POSSESSIVE_STEM_SLOT, STEM_SLOT)
        else putString(POSSESSIVE_STEM_SLOT, possessiveBase)
        dropFinalSoftSign(POSSESSIVE_STEM_SLOT)
        voiceFinal(POSSESSIVE_STEM_SLOT)
        buildPossessives(POSSESSIVE_STEM_SLOT, P1_SLOT)
        for (index in 0 until POSSESSIVE_COUNT) {
            applyOverride(P1_SLOT + index, POSSESSIVE_BASE_LABELS[index])
            if (matchesQuery(P1_SLOT + index)) return true
        }
        for (index in POSSESSIVE_SPECS.indices) {
            val spec = POSSESSIVE_SPECS[index]
            val base = P1_SLOT + POSSESSIVE_SLOTS[index]
            for (case in CASE_PATTERNS.indices) {
                val pattern = if (spec.shortDative && case == DATIVE_INDEX) "A" else CASE_PATTERNS[case]
                attach(FORM_SLOT, base, pattern, HARMONY_AUTO, false)
                if (matchWithOverride(spec.caseLabels[case])) return true
            }
        }

        buildPossessives(PLURAL_SLOT, PLURAL_P1_SLOT)
        copyOverride(PLURAL_P3_SLOT, WordformLabels.NOUN_P3PL)
        if (matchesQuery(PLURAL_P3_SLOT)) return true
        for (index in POST3_PATTERNS.indices) {
            attach(FORM_SLOT, P3_SLOT, POST3_PATTERNS[index], harmony, false)
            if (matchWithOverride(POST3_LABELS[index])) return true
        }
        for (index in POST3_PATTERNS.indices) {
            attach(FORM_SLOT, PLURAL_P3_SLOT, POST3_PATTERNS[index], harmony, false)
            if (matchWithOverride(POST3_PLURAL_LABELS[index])) return true
        }
        for (index in POSSESSIVE_SPECS.indices) {
            val spec = POSSESSIVE_SPECS[index]
            val base = PLURAL_P1_SLOT + POSSESSIVE_SLOTS[index]
            if (!copyOverride(FORM_SLOT, spec.pluralBaseLabel)) copySlot(FORM_SLOT, base)
            if (matchesQuery(FORM_SLOT)) return true
            for (case in CASE_PATTERNS.indices) {
                val pattern = if (spec.shortDative && case == DATIVE_INDEX) "A" else CASE_PATTERNS[case]
                attach(FORM_SLOT, base, pattern, HARMONY_AUTO, false)
                if (matchWithOverride(spec.pluralCaseLabels[case])) return true
            }
        }
        for (index in CASE_PATTERNS.indices) {
            attach(FORM_SLOT, PLURAL_SLOT, CASE_PATTERNS[index], HARMONY_AUTO, false)
            if (matchWithOverride(PLURAL_CASE_LABELS[index])) return true
        }
        for (index in PREDICATIVE_SPECS.indices) {
            val spec = PREDICATIVE_SPECS[index]
            if (spec.pattern == null) copySlot(AUX_SLOT, STEM_SLOT)
            else attach(AUX_SLOT, STEM_SLOT, spec.pattern, harmony, false)
            applyOverride(AUX_SLOT, spec.plainLabel)
            if (matchesQuery(AUX_SLOT)) return true
            attach(FORM_SLOT, AUX_SLOT, QUESTION_PATTERN, HARMONY_AUTO, false)
            if (matchWithOverride(spec.questionLabel)) return true
        }
        return false
    }

    /** Builds five possessive bases without applying stem-level overrides. */
    private fun buildPossessives(source: Int, destination: Int) {
        val length = lengths[source]
        if (length < 0) {
            for (index in 0 until POSSESSIVE_COUNT) lengths[destination + index] = -1
            return
        }
        val last = charAt(source, length - 1)
        val full = last !in VOWELS || last in HIATUS_FINAL
        val contracted = endsWith(source, "ай") || endsWith(source, "әй") ||
            last == 'е' && length >= 2 && (charAt(source, length - 2) == 'а' ||
                charAt(source, length - 2) == 'ә')
        if (contracted) {
            copySlot(destination + 2, source)
            if (last == 'й') {
                lengths[destination + 2] = length - 1
                append(destination + 2, 'е')
            }
            attach(destination, destination + 2, "м", harmony, false)
            attach(destination + 1, destination + 2, "ң", harmony, false)
            attach(destination + 3, destination + 2, "бIз", harmony, false)
            attach(destination + 4, destination + 2, "гIз", harmony, false)
            return
        }
        if (last !in VOWELS || last == 'у' || last == 'ү') {
            attach(destination + 2, source, "I", harmony, false)
        } else {
            attach(destination + 2, source, "сI", harmony, false)
        }
        attach(destination, source, if (full) "Iм" else "м", harmony, false)
        attach(destination + 1, source, if (full) "Iң" else "ң", harmony, false)
        attach(destination + 3, source, if (full) "IбIз" else "бIз", harmony, false)
        attach(destination + 4, source, if (full) "IгIз" else "гIз", harmony, false)
    }

    private fun matchVerb(): Boolean {
        if (!copyOverride(FORM_SLOT, WordformLabels.VERB_IMP_2SG)) copySlot(FORM_SLOT, STEM_SLOT)
        if (matchesQuery(FORM_SLOT)) return true
        attach(FORM_SLOT, STEM_SLOT, "(IгIз~гIз)", harmony, false)
        if (matchWithOverride(WordformLabels.VERB_IMP_2PL)) return true
        buildFirstPluralImperative()
        if (matchWithOverride(WordformLabels.VERB_IMP_1PL)) return true
        attach(FORM_SLOT, STEM_SLOT, "сIн", harmony, false)
        if (matchWithOverride(WordformLabels.VERB_IMP_3SG)) return true
        attach(FORM_SLOT, STEM_SLOT, "сIннAр", harmony, false)
        if (matchWithOverride(WordformLabels.VERB_IMP_3PL)) return true

        attach(NEGATED_SLOT, STEM_SLOT, "мA", harmony, false)
        for (index in TENSE_SPECS.indices) buildTenseBase(index, TENSE_SLOT + index)
        for (index in TENSE_SPECS.indices) {
            val spec = TENSE_SPECS[index]
            val base = TENSE_SLOT + index
            for (person in spec.endings.indices) {
                val ending = spec.endings[person]
                if (ending == null) copySlot(AUX_SLOT, base)
                else attach(AUX_SLOT, base, ending, HARMONY_AUTO, false)
                copySlot(FORM_SLOT, AUX_SLOT)
                if (matchWithOverride(spec.labels[person * 2])) return true
                attach(FORM_SLOT, AUX_SLOT, QUESTION_PATTERN, HARMONY_AUTO, false)
                if (matchWithOverride(spec.labels[person * 2 + 1])) return true
            }
        }

        buildConverb()
        if (matchWithOverride(WordformLabels.VERB_GER_IP)) return true
        attach(FORM_SLOT, STEM_SLOT, "GAч", harmony, devoicedFinal)
        if (matchWithOverride(WordformLabels.VERB_GER_GAC)) return true
        attach(FORM_SLOT, STEM_SLOT, "GAнчI", harmony, devoicedFinal)
        if (matchWithOverride(WordformLabels.VERB_GER_GANCI)) return true
        copySlot(FORM_SLOT, STEM_SLOT)
        appendLiteral(FORM_SLOT, if (harmony == HARMONY_BACK) "мыйча" else "мичә")
        if (matchWithOverride(WordformLabels.VERB_GER_NEG)) return true
        attach(FORM_SLOT, STEM_SLOT, "GAн", harmony, devoicedFinal)
        if (matchWithOverride(WordformLabels.VERB_PART_GAN)) return true
        attach(FORM_SLOT, STEM_SLOT, "UчI", harmony, false)
        if (matchWithOverride(WordformLabels.VERB_PART_UCI)) return true
        if (charAt(STEM_SLOT, stemLength - 1) !in VOWELS) {
            buildVowelSuffixBase(AUX_SLOT, STEM_SLOT)
            attach(FORM_SLOT, AUX_SLOT, "AсI", harmony, false)
            if (matchWithOverride(WordformLabels.VERB_PART_ASI)) return true
        }
        buildMasdar(FORM_SLOT)
        if (matchWithOverride(WordformLabels.VERB_MASDAR)) return true
        attach(FORM_SLOT, TENSE_SLOT + FUTURE_TENSE_INDEX, "GA", HARMONY_AUTO, false)
        if (matchWithOverride(WordformLabels.VERB_INF)) return true
        attach(FORM_SLOT, STEM_SLOT, "мAкчI", harmony, false)
        return matchWithOverride(WordformLabels.VERB_INTENT)
    }

    private fun buildFirstPluralImperative() {
        val last = charAt(STEM_SLOT, stemLength - 1)
        if (last in VOWELS) {
            copySlot(FORM_SLOT, STEM_SLOT)
            if (stemLength > 1) {
                lengths[FORM_SLOT] = stemLength - 1
                renderPattern(FORM_SLOT, "Yк", STEM_SLOT, harmony, false)
            } else {
                append(FORM_SLOT, 'й')
                renderPattern(FORM_SLOT, "Iк", STEM_SLOT, harmony, false)
            }
        } else {
            attach(FORM_SLOT, STEM_SLOT, "Yк", harmony, false)
        }
    }

    private fun buildTenseBase(index: Int, destination: Int) {
        val spec = TENSE_SPECS[index]
        if (copyOverride(destination, spec.baseOverrideLabel)) return
        when (spec.kind) {
            PRESENT -> buildPresent(destination)
            PRESENT_NEGATIVE -> attach(destination, STEM_SLOT, "мY", harmony, false)
            PAST -> attach(destination, STEM_SLOT, "DI", harmony, devoicedFinal)
            PAST_NEGATIVE -> attach(destination, NEGATED_SLOT, "DI", HARMONY_AUTO, false)
            RECENT_PAST -> attach(destination, STEM_SLOT, "GAн", harmony, devoicedFinal)
            RECENT_PAST_NEGATIVE -> attach(destination, NEGATED_SLOT, "GAн", HARMONY_AUTO, false)
            FUTURE -> buildFuture(destination)
            FUTURE_NEGATIVE -> attach(destination, STEM_SLOT, "мAс", harmony, false)
            DEFINITE_FUTURE -> {
                buildVowelSuffixBase(destination, STEM_SLOT)
                attach(destination, destination, "(AчAк~ячAк)", harmony, false)
            }
            CONDITIONAL -> attach(destination, STEM_SLOT, "сA", harmony, false)
            else -> attach(destination, NEGATED_SLOT, "сA", HARMONY_AUTO, false)
        }
    }

    private fun buildPresent(destination: Int) {
        if (isGlideStem(STEM_SLOT)) {
            copySlot(destination, STEM_SLOT)
            lengths[destination] = stemLength - 1
            append(destination, 'я')
            return
        }
        buildVowelSuffixBase(destination, STEM_SLOT)
        val length = lengths[destination]
        if (length < 0) return
        if (charAt(destination, length - 1) in VOWELS) {
            lengths[destination] = length - 1
            renderPattern(destination, "Y", destination, harmony, false)
        } else {
            attach(destination, destination, "A", harmony, false)
        }
    }

    private fun buildFuture(destination: Int) {
        copySlot(destination, STEM_SLOT)
        if (isGlideStem(destination)) lengths[destination] = stemLength - 1
        val length = lengths[destination]
        if (length < 0) return
        if (charAt(destination, length - 1) in VOWELS) {
            appendLiteral(destination, if (syllableCount(destination) == 1) "яр" else "р")
        } else if (syllableCount(destination) == 1) {
            attach(destination, destination, "Aр", harmony, false)
        } else {
            dropFinalSoftSign(destination)
            voiceFinal(destination)
            attach(destination, destination, "Iр", harmony, false)
        }
    }

    private fun buildMasdar(destination: Int) {
        if (isGlideStem(STEM_SLOT)) {
            copySlot(destination, STEM_SLOT)
            lengths[destination] = stemLength - 1
            append(destination, 'ю')
            return
        }
        buildVowelSuffixBase(destination, STEM_SLOT)
        val length = lengths[destination]
        if (length < 0) return
        val last = charAt(destination, length - 1)
        if (last in VOWELS && last !in "аәоөэ") append(destination, 'ю')
        else attach(destination, destination, "U", harmony, false)
    }

    private fun buildConverb() {
        if (isGlideStem(STEM_SLOT)) {
            copySlot(FORM_SLOT, STEM_SLOT)
            lengths[FORM_SLOT] = stemLength - 1
            appendLiteral(FORM_SLOT, "еп")
            return
        }
        if (charAt(STEM_SLOT, stemLength - 1) == 'у' ||
            charAt(STEM_SLOT, stemLength - 1) == 'ү'
        ) {
            attach(FORM_SLOT, STEM_SLOT, "Iп", harmony, false)
            return
        }
        buildVowelSuffixBase(AUX_SLOT, STEM_SLOT)
        attach(FORM_SLOT, AUX_SLOT, "(Iп~п)", harmony, false)
    }

    private fun matchDerivations(): Boolean {
        for (index in DERIV_PATTERNS.indices) {
            if (index == DERIV_RAK_INDEX) {
                copySlot(AUX_SLOT, STEM_SLOT)
                dropFinalSoftSign(AUX_SLOT)
                voiceFinal(AUX_SLOT)
                attach(FORM_SLOT, AUX_SLOT, DERIV_PATTERNS[index], harmony, devoicedFinal)
            } else {
                attach(FORM_SLOT, STEM_SLOT, DERIV_PATTERNS[index], harmony, devoicedFinal)
            }
            if (matchesQuery(FORM_SLOT)) return true
        }
        return false
    }

    private fun buildVowelSuffixBase(destination: Int, source: Int) {
        copySlot(destination, source)
        dropFinalSoftSign(destination)
        if (isGlideStem(destination)) lengths[destination] = lengths[destination] - 1
        voiceFinal(destination)
    }

    private fun voiceFinal(slot: Int) {
        if (!voiceFinal) return
        val length = lengths[slot]
        if (length < 2 || charAt(slot, length - 2) !in VOWELS) return
        when (charAt(slot, length - 1)) {
            'п' -> setChar(slot, length - 1, 'б')
            'к' -> setChar(slot, length - 1, 'г')
        }
    }

    private fun dropFinalSoftSign(slot: Int) {
        val length = lengths[slot]
        if (length > 0 && charAt(slot, length - 1) == 'ь') lengths[slot] = length - 1
    }

    private fun isGlideStem(slot: Int): Boolean {
        val length = lengths[slot]
        return length >= 2 && charAt(slot, length - 1) == 'й' &&
            charAt(slot, length - 2) in VOWELS && syllableCount(slot) == 1
    }

    private fun syllableCount(slot: Int): Int {
        var result = 0
        val length = lengths[slot]
        for (index in 0 until length) if (charAt(slot, index) in VOWELS) result++
        return result
    }

    private fun endsWith(slot: Int, suffix: String): Boolean {
        val length = lengths[slot]
        if (length < suffix.length) return false
        val start = length - suffix.length
        for (index in suffix.indices) if (charAt(slot, start + index) != suffix[index]) return false
        return true
    }

    private fun naturalHarmony(slot: Int): Int {
        val length = lengths[slot]
        if (length > 0 && charAt(slot, length - 1) == 'ь') return HARMONY_FRONT
        for (index in length - 1 downTo 0) {
            val char = charAt(slot, index)
            if (char in GLIDE_VOWELS && index > 0 && charAt(slot, index - 1) in VOWELS) continue
            if (char in BACK_VOWELS) return HARMONY_BACK
            if (char in FRONT_VOWELS) return HARMONY_FRONT
        }
        return harmony
    }

    private fun attach(destination: Int, source: Int, pattern: String, requestedHarmony: Int, devoiced: Boolean) {
        copySlot(destination, source)
        renderPattern(destination, pattern, source, requestedHarmony, devoiced)
    }

    private fun renderPattern(
        destination: Int,
        pattern: String,
        source: Int,
        requestedHarmony: Int,
        devoiced: Boolean,
    ) {
        val resolved = if (requestedHarmony == HARMONY_AUTO) naturalHarmony(source) else requestedHarmony
        renderRange(destination, pattern, 0, pattern.length, source, resolved, devoiced)
    }

    private fun renderRange(
        destination: Int,
        pattern: String,
        start: Int,
        end: Int,
        source: Int,
        resolvedHarmony: Int,
        devoiced: Boolean,
    ) {
        val sourceLength = lengths[source]
        if (sourceLength <= 0 || lengths[destination] < 0) return
        val last = charAt(source, sourceLength - 1)
        val assimilation = if (last == 'ь' && sourceLength > 1) {
            charAt(source, sourceLength - 2)
        } else {
            last
        }
        val voiceless = assimilation in VOICELESS ||
            devoiced && assimilation in LOAN_DEVOICING_FINALS
        var index = start
        while (index < end) {
            when (val char = pattern[index]) {
                '(' -> {
                    val close = pattern.indexOf(')', index + 1)
                    val separator = pattern.indexOf('~', index + 1)
                    val branchStart = if (last in VOWELS) separator + 1 else index + 1
                    val branchEnd = if (last in VOWELS) close else separator
                    renderRange(destination, pattern, branchStart, branchEnd, source,
                        resolvedHarmony, devoiced)
                    index = close + 1
                }
                'A' -> { append(destination, if (resolvedHarmony == HARMONY_BACK) 'а' else 'ә'); index++ }
                'I' -> { append(destination, if (resolvedHarmony == HARMONY_BACK) 'ы' else 'е'); index++ }
                'U' -> { append(destination, if (resolvedHarmony == HARMONY_BACK) 'у' else 'ү'); index++ }
                'Y' -> {
                    if (resolvedHarmony == HARMONY_BACK) appendLiteral(destination, "ый")
                    else append(destination, 'и')
                    index++
                }
                'G' -> { append(destination, if (voiceless) 'к' else 'г'); index++ }
                'D' -> { append(destination, if (voiceless) 'т' else 'д'); index++ }
                'L' -> { append(destination, if (assimilation in NASALS) 'н' else 'л'); index++ }
                'T' -> {
                    append(destination, when {
                        voiceless -> 'т'
                        assimilation in NASALS -> 'н'
                        else -> 'д'
                    })
                    index++
                }
                else -> { append(destination, char); index++ }
            }
        }
    }

    private fun copyOverride(slot: Int, label: String): Boolean {
        val value = exception?.overrides?.get(label) ?: return false
        putString(slot, value)
        return true
    }

    private fun applyOverride(slot: Int, label: String) {
        copyOverride(slot, label)
    }

    private fun matchWithOverride(label: String): Boolean {
        applyOverride(FORM_SLOT, label)
        return matchesQuery(FORM_SLOT)
    }

    private fun matchesQuery(slot: Int): Boolean {
        val length = lengths[slot]
        if (length != queryLength) return false
        for (index in 0 until length) if (charAt(slot, index) != query[index]) return false
        return true
    }

    private fun putString(slot: Int, value: String) {
        lengths[slot] = 0
        for (index in value.indices) append(slot, value[index])
    }

    private fun copyIntoSlot(slot: Int, source: CharArray, length: Int) {
        if (length > MAX_QUERY_CODE_POINTS) {
            lengths[slot] = -1
            return
        }
        source.copyInto(chars, slot * MAX_QUERY_CODE_POINTS, 0, length)
        lengths[slot] = length
    }

    private fun copySlot(destination: Int, source: Int) {
        if (destination == source) return
        val length = lengths[source]
        lengths[destination] = length
        if (length >= 0) {
            chars.copyInto(
                chars,
                destination * MAX_QUERY_CODE_POINTS,
                source * MAX_QUERY_CODE_POINTS,
                source * MAX_QUERY_CODE_POINTS + length,
            )
        }
    }

    private fun appendLiteral(slot: Int, value: String) {
        for (index in value.indices) append(slot, value[index])
    }

    private fun append(slot: Int, value: Char) {
        val length = lengths[slot]
        if (length < 0) return
        if (length >= MAX_QUERY_CODE_POINTS) {
            lengths[slot] = -1
            return
        }
        setChar(slot, length, value)
        lengths[slot] = length + 1
    }

    private fun charAt(slot: Int, index: Int): Char = chars[slot * MAX_QUERY_CODE_POINTS + index]

    private fun setChar(slot: Int, index: Int, value: Char) {
        chars[slot * MAX_QUERY_CODE_POINTS + index] = value
    }

    private companion object {
        const val POSSESSIVE_COUNT = 5
        const val DATIVE_INDEX = 1
        const val DERIV_RAK_INDEX = 6
        const val FUTURE_TENSE_INDEX = 6

        const val PRESENT = 0
        const val PRESENT_NEGATIVE = 1
        const val PAST = 2
        const val PAST_NEGATIVE = 3
        const val RECENT_PAST = 4
        const val RECENT_PAST_NEGATIVE = 5
        const val FUTURE = 6
        const val FUTURE_NEGATIVE = 7
        const val DEFINITE_FUTURE = 8
        const val CONDITIONAL = 9
        const val CONDITIONAL_NEGATIVE = 10

        const val QUESTION_PATTERN = "мI"
        val POSSESSIVE_SLOTS = intArrayOf(0, 1, 3, 4)
        val NOUN_CASE_PATTERNS = arrayOf("нIң", "GA", "нI", "DA", "TAн")
        val NOUN_CASE_LABELS = arrayOf(
            WordformLabels.NOUN_GEN, WordformLabels.NOUN_DAT, WordformLabels.NOUN_ACC,
            WordformLabels.NOUN_LOC, WordformLabels.NOUN_ABL,
        )
        val CASE_PATTERNS = arrayOf("нIң", "GA", "нI", "DA", "TAн")
        val PLURAL_CASE_LABELS = arrayOf(
            "noun.pl.gen", "noun.pl.dat", "noun.pl.acc", "noun.pl.loc", "noun.pl.abl",
        )
        val POSSESSIVE_BASE_LABELS = arrayOf(
            WordformLabels.NOUN_P1, WordformLabels.NOUN_P2, WordformLabels.NOUN_P3,
            WordformLabels.NOUN_P1PL, WordformLabels.NOUN_P2PL,
        )
        val POST3_PATTERNS = arrayOf("нIң", "н", "нA", "нDA", "ннAн")
        val POST3_LABELS = arrayOf("noun.p3.gen", "noun.p3.acc", "noun.p3.dat",
            "noun.p3.loc", "noun.p3.abl")
        val POST3_PLURAL_LABELS = arrayOf("noun.p3pl.gen", "noun.p3pl.acc", "noun.p3pl.dat",
            "noun.p3pl.loc", "noun.p3pl.abl")
        val POSSESSIVE_SPECS = arrayOf(
            PossessiveSpec(
                arrayOf("noun.p1.gen", "noun.p1.dat", "noun.p1.acc", "noun.p1.loc", "noun.p1.abl"),
                "noun.pl.p1",
                arrayOf("noun.pl.p1.gen", "noun.pl.p1.dat", "noun.pl.p1.acc", "noun.pl.p1.loc",
                    "noun.pl.p1.abl"),
                true,
            ),
            PossessiveSpec(
                arrayOf("noun.p2.gen", "noun.p2.dat", "noun.p2.acc", "noun.p2.loc", "noun.p2.abl"),
                "noun.pl.p2",
                arrayOf("noun.pl.p2.gen", "noun.pl.p2.dat", "noun.pl.p2.acc", "noun.pl.p2.loc",
                    "noun.pl.p2.abl"),
                true,
            ),
            PossessiveSpec(
                arrayOf("noun.p1pl.gen", "noun.p1pl.dat", "noun.p1pl.acc", "noun.p1pl.loc",
                    "noun.p1pl.abl"),
                "noun.pl.p1pl",
                arrayOf("noun.pl.p1pl.gen", "noun.pl.p1pl.dat", "noun.pl.p1pl.acc",
                    "noun.pl.p1pl.loc", "noun.pl.p1pl.abl"),
                false,
            ),
            PossessiveSpec(
                arrayOf("noun.p2pl.gen", "noun.p2pl.dat", "noun.p2pl.acc", "noun.p2pl.loc",
                    "noun.p2pl.abl"),
                "noun.pl.p2pl",
                arrayOf("noun.pl.p2pl.gen", "noun.pl.p2pl.dat", "noun.pl.p2pl.acc",
                    "noun.pl.p2pl.loc", "noun.pl.p2pl.abl"),
                false,
            ),
        )
        val PREDICATIVE_SPECS = arrayOf(
            PredicativeSpec(WordformLabels.NOUN_PRED_1SG, "noun.pred.1sg.q", "мIн"),
            PredicativeSpec(WordformLabels.NOUN_PRED_2SG, "noun.pred.2sg.q", "сIң"),
            PredicativeSpec(WordformLabels.NOUN_PRED_3SG, "noun.pred.3sg.q", null),
            PredicativeSpec(WordformLabels.NOUN_PRED_1PL, "noun.pred.1pl.q", "бIз"),
            PredicativeSpec(WordformLabels.NOUN_PRED_2PL, "noun.pred.2pl.q", "сIз"),
            PredicativeSpec(WordformLabels.NOUN_PRED_3PL, "noun.pred.3pl.q", "LAр"),
        )
        val TYPE_I = arrayOf<String?>("м", "сIң", null, "бIз", "сIз", "LAр")
        val TYPE_II = arrayOf<String?>("м", "ң", null, "к", "гIз", "LAр")
        val PREDICATIVE = arrayOf<String?>("мIн", "сIң", null, "бIз", "сIз", "LAр")
        val CONDITIONAL_ENDINGS = arrayOf<String?>("м", "ң", null, "к", "гIз", "LAр")
        val TENSE_SPECS = arrayOf(
            TenseSpec(PRESENT, WordformLabels.VERB_PRES_3SG, TYPE_I, personLabels("verb.pres")),
            TenseSpec(PRESENT_NEGATIVE, WordformLabels.VERB_PRES_NEG_3SG, TYPE_I,
                personLabels("verb.pres.neg")),
            TenseSpec(PAST, WordformLabels.VERB_PAST_3SG, TYPE_II, personLabels("verb.past")),
            TenseSpec(PAST_NEGATIVE, WordformLabels.VERB_PAST_NEG_3SG, TYPE_II,
                personLabels("verb.past.neg")),
            TenseSpec(RECENT_PAST, WordformLabels.VERB_RPAST_3SG, PREDICATIVE,
                personLabels("verb.rpast")),
            TenseSpec(RECENT_PAST_NEGATIVE, WordformLabels.VERB_RPAST_NEG_3SG, PREDICATIVE,
                personLabels("verb.rpast.neg")),
            TenseSpec(FUTURE, WordformLabels.VERB_FUT_3SG, PREDICATIVE, personLabels("verb.fut")),
            TenseSpec(FUTURE_NEGATIVE, WordformLabels.VERB_FUT_NEG_3SG, PREDICATIVE,
                personLabels("verb.fut.neg")),
            TenseSpec(DEFINITE_FUTURE, WordformLabels.VERB_DEFFUT_3SG, PREDICATIVE,
                personLabels("verb.deffut")),
            TenseSpec(CONDITIONAL, WordformLabels.VERB_COND_3SG, CONDITIONAL_ENDINGS,
                personLabels("verb.cond")),
            TenseSpec(CONDITIONAL_NEGATIVE, WordformLabels.VERB_COND_NEG_3SG, CONDITIONAL_ENDINGS,
                personLabels("verb.cond.neg")),
        )
        val DERIV_PATTERNS = arrayOf("чA", "лIк", "лI", "сIз", "чI", "DAш", "рAк")

        fun personLabels(tense: String): Array<String> = arrayOf(
            "$tense.1sg", "$tense.1sg.q", "$tense.2sg", "$tense.2sg.q",
            "$tense.3sg", "$tense.3sg.q", "$tense.1pl", "$tense.1pl.q",
            "$tense.2pl", "$tense.2pl.q", "$tense.3pl", "$tense.3pl.q",
        )
    }
}

private const val FLAG_BACK = 8
private const val FLAG_FORCE_BACK = 64
