package rkr.simplekeyboard.inputmethod.latin.morphology

/** Fixed lexical data shared by the generator and the binary acceptor. */
internal class LexicalException(
    val stem: String,
    val forceBack: Boolean = false,
    val loan: Boolean = false,
    val possessiveBase: String? = null,
    val pronouns: Array<String>? = null,
    val overrides: Map<String, String> = emptyMap(),
) {
    val chars: CharArray = stem.toCharArray()
    val utf8: ByteArray = stem.toByteArray(Charsets.UTF_8)
}

internal object WordformLabels {
    const val NOUN_PL = "noun.pl"
    const val NOUN_GEN = "noun.gen"
    const val NOUN_DAT = "noun.dat"
    const val NOUN_ACC = "noun.acc"
    const val NOUN_LOC = "noun.loc"
    const val NOUN_ABL = "noun.abl"
    const val NOUN_P1 = "noun.p1"
    const val NOUN_P2 = "noun.p2"
    const val NOUN_P3 = "noun.p3"
    const val NOUN_P1PL = "noun.p1pl"
    const val NOUN_P2PL = "noun.p2pl"
    const val NOUN_P3PL = "noun.p3pl"
    const val NOUN_PRED_1SG = "noun.pred.1sg"
    const val NOUN_PRED_2SG = "noun.pred.2sg"
    const val NOUN_PRED_3SG = "noun.pred.3sg"
    const val NOUN_PRED_1PL = "noun.pred.1pl"
    const val NOUN_PRED_2PL = "noun.pred.2pl"
    const val NOUN_PRED_3PL = "noun.pred.3pl"

    const val VERB_IMP_2SG = "verb.imp.2sg"
    const val VERB_IMP_2PL = "verb.imp.2pl"
    const val VERB_IMP_1PL = "verb.imp.1pl"
    const val VERB_IMP_3SG = "verb.imp.3sg"
    const val VERB_IMP_3PL = "verb.imp.3pl"
    const val VERB_IMP_NEG_2SG = "verb.imp.neg.2sg"
    const val VERB_IMP_NEG_2PL = "verb.imp.neg.2pl"
    const val VERB_IMP_NEG_1PL = "verb.imp.neg.1pl"
    const val VERB_IMP_NEG_3SG = "verb.imp.neg.3sg"
    const val VERB_IMP_NEG_3PL = "verb.imp.neg.3pl"
    const val VERB_PRES_1SG = "verb.pres.1sg"
    const val VERB_PRES_1SG_Q = "verb.pres.1sg.q"
    const val VERB_PRES_3SG = "verb.pres.3sg"
    const val VERB_PRES_NEG_3SG = "verb.pres.neg.3sg"
    const val VERB_PAST_3SG = "verb.past.3sg"
    const val VERB_PAST_NEG_3SG = "verb.past.neg.3sg"
    const val VERB_RPAST_3SG = "verb.rpast.3sg"
    const val VERB_RPAST_NEG_3SG = "verb.rpast.neg.3sg"
    const val VERB_FUT_3SG = "verb.fut.3sg"
    const val VERB_FUT_NEG_3SG = "verb.fut.neg.3sg"
    const val VERB_DEFFUT_3SG = "verb.deffut.3sg"
    const val VERB_COND_3SG = "verb.cond.3sg"
    const val VERB_COND_NEG_3SG = "verb.cond.neg.3sg"
    const val VERB_GER_IP = "verb.ger.ip"
    const val VERB_GER_GAC = "verb.ger.gac"
    const val VERB_GER_GANCI = "verb.ger.ganci"
    const val VERB_GER_NEG = "verb.ger.neg"
    const val VERB_PART_GAN = "verb.part.gan"
    const val VERB_PART_UCI = "verb.part.uci"
    const val VERB_PART_ASI = "verb.part.asi"
    const val VERB_MASDAR = "verb.masdar"
    const val VERB_MASDAR_NEG = "verb.masdar.neg"
    const val VERB_INF = "verb.inf"
    const val VERB_INTENT = "verb.intent"
}

/** The reviewed rows of the word-form exception table, initialized once. */
internal object TatarWordformExceptions {
    val STEMS: Array<LexicalException> = arrayOf(
        LexicalException("министр", forceBack = true),
        LexicalException("президент", forceBack = true),
        LexicalException("метр", forceBack = true),
        LexicalException("шрифт", forceBack = true),
        LexicalException("ковид", forceBack = true, loan = true),
        LexicalException("совет", forceBack = true),
        LexicalException("объект", forceBack = true),
        LexicalException("рухани", forceBack = true),
        LexicalException("тарих", forceBack = true),
        LexicalException("проект", forceBack = true),
        LexicalException("студент", forceBack = true),
        LexicalException("монумент", forceBack = true),
        LexicalException("конфликт", forceBack = true),
        LexicalException("баһадир", forceBack = true),
        LexicalException("синоптик", forceBack = true),
        LexicalException("әдәбият", forceBack = true),
        LexicalException("комитет", forceBack = true),
        LexicalException("волонтер", forceBack = true),
        LexicalException("мюзикл", forceBack = true),
        LexicalException("орден", forceBack = true),
        LexicalException("ел", forceBack = true),
        LexicalException("ай", forceBack = true, possessiveBase = "ае"),
        LexicalException("еш", forceBack = true),
        LexicalException("парк", loan = true),
        LexicalException("банк", loan = true),
        LexicalException("суп", loan = true),
        LexicalException("завод", loan = true),
        LexicalException("клуб", loan = true),
        LexicalException("халык", possessiveBase = "халк"),
        LexicalException("бар", overrides = mapOf(WordformLabels.VERB_FUT_3SG to "барыр")),
        LexicalException("бир", overrides = mapOf(WordformLabels.VERB_FUT_3SG to "бирер")),
        LexicalException("бел", overrides = mapOf(WordformLabels.VERB_FUT_3SG to "белер")),
        LexicalException("бул", overrides = mapOf(WordformLabels.VERB_FUT_3SG to "булыр")),
        LexicalException("ал", overrides = mapOf(WordformLabels.VERB_FUT_3SG to "алыр")),
        LexicalException("әйт", overrides = mapOf(WordformLabels.VERB_FUT_3SG to "әйтер")),
        LexicalException("кал", overrides = mapOf(WordformLabels.VERB_FUT_3SG to "калыр")),
        LexicalException("кил", overrides = mapOf(WordformLabels.VERB_FUT_3SG to "килер")),
        LexicalException("тор", overrides = mapOf(WordformLabels.VERB_FUT_3SG to "торыр")),
        LexicalException("тул", overrides = mapOf(WordformLabels.VERB_FUT_3SG to "тулыр")),
        LexicalException("йөр", overrides = mapOf(WordformLabels.VERB_FUT_3SG to "йөрер")),
        LexicalException(
            "укы",
            overrides = mapOf(
                WordformLabels.VERB_MASDAR to "уку",
                WordformLabels.VERB_FUT_3SG to "укыр",
                WordformLabels.VERB_DEFFUT_3SG to "укачак",
                WordformLabels.VERB_PRES_NEG_3SG to "укмый",
                WordformLabels.VERB_FUT_NEG_3SG to "укмас",
                WordformLabels.VERB_PAST_NEG_3SG to "укмады",
                WordformLabels.VERB_RPAST_NEG_3SG to "укмаган",
                WordformLabels.VERB_GER_NEG to "укмыйча",
            ),
        ),
        LexicalException("ю", overrides = mapOf(WordformLabels.VERB_MASDAR to "юу")),
        LexicalException("мин", pronouns = arrayOf("минем", "миңа", "мине", "миндә", "миннән")),
        LexicalException("син", pronouns = arrayOf("синең", "сиңа", "сине", "синдә", "синнән")),
        LexicalException("ул", pronouns = arrayOf("аның", "аңа", "аны", "анда", "аннан")),
        LexicalException("бу", pronouns = arrayOf("моның", "моңа", "моны", "монда", "моннан")),
        LexicalException("без", pronouns = arrayOf("безнең", "безгә", "безне", "бездә", "бездән")),
        LexicalException("сез", pronouns = arrayOf("сезнең", "сезгә", "сезне", "сездә", "сездән")),
        LexicalException(
            "алар",
            pronouns = arrayOf("аларның", "аларга", "аларны", "аларда", "алардан"),
        ),
    )
}
