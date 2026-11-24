package rkr.simplekeyboard.inputmethod.latin.morphology

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TatarMorphologicalAcceptorTest {
    @Test
    fun chainFlagsStayWithinTheirPartsOfSpeech() {
        val nounOnly = MorphologyTestSupport.acceptor(
            listOf(MorphologyTestSupport.entry("яз", noun = true, verb = false)),
        )
        assertAccepted(nounOnly, "яз", "язлар", "язлык")
        assertRejected(nounOnly, "язды", "язар")

        val verbOnly = MorphologyTestSupport.acceptor(
            listOf(MorphologyTestSupport.entry("татар", noun = false, verb = true)),
        )
        assertAccepted(verbOnly, "татар", "татарды", "татарыр")
        assertRejected(verbOnly, "татарлар", "татарча")
    }

    @Test
    fun bundledDictionaryStemCanStartAChain() {
        val acceptor = MorphologyTestSupport.realAcceptor()
        assertTrue(acceptor.accepts("китапларымның"))
    }

    @Test
    fun acceptsBareStemsAndAllNounContinuationFamilies() {
        val acceptor = acceptor("татар", "өй", "урман", "китап", "су", "әби", "аба", "аракы")
        assertAccepted(
            acceptor,
            "татар", "өй", "татарлар", "татарның", "татарга", "татарны", "татарда", "татардан",
            "татарым", "татарың", "татары", "татарыбыз", "татарыгыз",
            "татарымның", "татарыма", "татарымны", "татарымда", "татарымнан",
            "татарыңа", "татарыбызга", "татарыгыздан", "татарлары",
            "татарының", "татарын", "татарына", "татарында", "татарыннан",
            "татарларының", "татарларын", "татарларына", "татарларында", "татарларыннан",
            "татарларым", "татарларыма", "татарларыңны", "татарларыбызга", "татарларыгыздан",
            "татарларның", "татарларга", "татарларны", "татарларда", "татарлардан",
            "татармын", "татарсың", "татарбыз", "татарсыз", "татармынмы", "татармы", "татарлармы",
            "өйләр", "өйнең", "өйгә", "өйне", "өйдә", "өйдән", "өйем", "өйең", "өйе",
            "өйебез", "өйегез", "өйемә", "өйләреннән", "өйбезме", "урманнар", "урманнан",
            "китапка", "китапта", "китаптан", "китабым", "китабың", "китабы", "китаплары",
            "суым", "суың", "суы", "суыбыз", "суын", "суына", "әбием", "әбисе", "абам", "аракым",
            "аракысы",
        )
        assertRejected(
            acceptor,
            "татарла", "татарымга", "татарларымга", "китапым", "китапы", "китапларгы",
            "сусы", "әбие", "өйнеңме",
        )
    }

    @Test
    fun possessiveContractionSoftSignAndNasalRulesMatchTheGenerator() {
        val acceptor = acceptor("малай", "чәй", "автомобиль", "вазгыять", "җәмгыять", "таң", "көн")
        assertAccepted(
            acceptor,
            "малаем", "малаең", "малае", "малаебыз", "малаен", "чәе", "автомобиле", "автомобильне",
            "вазгыяте", "җәмгыятькә", "җәмгыятьтә", "җәмгыятьтән", "таңнар", "таңнан", "көннән",
        )
        assertRejected(
            acceptor,
            "малайым", "малайы", "автомобилье", "автомобильы", "җәмгыятьгә", "җәмгыятьдә",
            "таңлар", "таңдан",
        )
    }

    @Test
    fun acceptsEveryVerbContinuationFamilyAndQuestionLayer() {
        val acceptor = acceptor("яз", "кит", "эшлә", "кара", "ди", "ту")
        assertAccepted(
            acceptor,
            "языгыз", "языйк", "язсын", "язсыннар",
            "язам", "язасың", "яза", "язабыз", "язасыз", "язалар", "язаммы", "язамы",
            "язмыйм", "язмыйсың", "язмый", "язмыйбыз", "язмыйсыз", "язмыйлар", "язмыйммы",
            "яздым", "яздың", "язды", "яздык", "яздыгыз", "яздылар", "яздымы",
            "язмадым", "язмадың", "язмады", "язмадык", "язмадыгыз", "язмадылар", "язмадымы",
            "язганмын", "язгансың", "язган", "язганбыз", "язгансыз", "язганнар", "язганмы",
            "язмаганмын", "язмагансың", "язмаган", "язмаганбыз", "язмагансыз", "язмаганнар",
            "язармын", "язарсың", "язар", "язарбыз", "язарсыз", "язарлар", "язармы",
            "язмасмын", "язмассың", "язмас", "язмасбыз", "язмассыз", "язмаслар",
            "язачакмын", "язачаксың", "язачак", "язачакбыз", "язачаксыз", "язачаклар",
            "язсам", "язсаң", "язса", "язсак", "язсагыз", "язсалар", "язсамы",
            "язмасам", "язмасаң", "язмаса", "язмасак", "язмасагыз", "язмасалар",
            "язып", "язгач", "язганчы", "язмыйча", "язган", "язучы", "язасы", "язу", "язарга",
            "язмакчы", "китте", "киткән", "китәр", "китеп", "киткәч", "китү", "китми",
            "киткәннәрме", "эшли", "эшлим", "эшләми", "эшләр", "эшләячәк", "эшләп", "эшләмичә",
            "эшләүче", "эшләү", "эшлик", "карый", "карыйм", "карыйк", "ди", "дияр", "дип", "дию",
            "туып",
        )
        assertRejected(
            acceptor,
            "язамый", "язаргаы", "язарыр", "язасый", "эшләсо", "эшләпи", "киттемер", "туп",
        )
    }

    @Test
    fun glideAndFinalVoicingRulesApplyOnlyToTheirVocalicBases() {
        val acceptor = acceptor("куй", "җый", "сөй", "тап", "күнек")
        assertAccepted(
            acceptor,
            "куям", "куясың", "куя", "куябыз", "куясыз", "куялар", "куяр", "куячак", "куеп", "кую",
            "җыя", "җыябыз", "җыяр", "җыячак", "җыеп", "җыю", "сөя", "сөеп", "сөю",
            "табалар", "табып", "табу", "табачак", "табасы", "тапты", "тапыйк", "тапар", "тапка",
            "күнегә", "күнегер", "күнегеп", "күнегү",
        )
        assertRejected(
            acceptor,
            "куйа", "куйасың", "куйалар", "куйячак", "куйып", "куйу", "тапып", "тапалар", "тапу",
            "тапачак", "күнекеп",
        )
    }

    @Test
    fun derivationsUseTheNounChainArm() {
        val acceptor = acceptor("дус", "мөмкин", "су", "укыту", "юл", "нык", "күп")
        assertAccepted(
            acceptor,
            "дусча", "дуслык", "дуслы", "дуссыз", "дусчы", "дусташ", "дусрак", "мөмкинлек", "сусыз",
            "укытучы", "юлдаш", "ныграк", "күбрәк",
        )
        assertRejected(acceptor, "дусчә", "дуслыг", "дусдаш", "ныкрак", "күпрәк")

        val verbOnly = acceptor("дус", noun = false, verb = true, nounChain = false, verbChain = true)
        assertFalse(verbOnly.accepts("дуслык"))
        assertTrue(verbOnly.accepts("дусар"))
    }

    @Test
    fun everyForcedBackRowOverridesSurfaceHarmony() {
        val stems = listOf(
            "министр", "президент", "метр", "шрифт", "ковид", "совет", "объект", "рухани", "тарих",
            "проект", "студент", "монумент", "конфликт", "баһадир", "синоптик", "әдәбият", "комитет",
            "волонтер", "мюзикл", "орден", "ел", "ай", "еш",
        )
        val acceptor = MorphologyTestSupport.acceptor(stems.map { MorphologyTestSupport.entry(it, forceBack = true) })
        for (stem in stems) {
            assertTrue(stem, acceptor.accepts(stem + "ның"))
            assertFalse(stem, acceptor.accepts(stem + "нең"))
        }
        assertAccepted(acceptor, "ешрак", "аенда", "елда", "министры", "президентына")
        assertRejected(acceptor, "ешрәк", "айында", "елдә", "министре")
    }

    @Test
    fun reviewedLoansControlVoicingAndAssimilation() {
        val acceptor = acceptor("парк", "банк", "суп", "ковид", "завод", "клуб", "призов", "мираж")
        assertAccepted(
            acceptor,
            "паркы", "банкы", "супы", "ковидка", "ковидта", "ковидтан", "заводка", "заводта",
            "клубка", "призовга", "миражда",
        )
        assertRejected(
            acceptor,
            "паргы", "бангы", "субы", "ковидга", "ковидда", "ковиддан", "заводга", "клубга",
            "призовка", "миражта",
        )
    }

    @Test
    fun possessiveBasesAndSuppletivesNeedNotStartWithTheStem() {
        val acceptor = acceptor("халык", "ай", "мин", "син", "ул", "бу", "без", "сез", "алар")
        assertAccepted(
            acceptor,
            "халкым", "халкың", "халкы", "халкының", "халкын", "халыклар", "халыкка", "ае", "аенда",
            "минем", "миңа", "мине", "миндә", "миннән", "синең", "сиңа", "сине", "синдә", "синнән",
            "аның", "аңа", "аны", "анда", "аннан", "моның", "моңа", "моны", "монда", "моннан",
            "безнең", "безгә", "безне", "бездә", "бездән", "сезнең", "сезгә", "сезне", "сездә",
            "сездән", "аларның", "аларга", "аларны", "аларда", "алардан",
        )
        assertRejected(
            acceptor,
            "халыкым", "халыкың", "халыкы", "халыкын", "халыкының", "айында", "мингә", "минләр",
            "улның", "буның", "аныңның", "моңың",
        )
    }

    @Test
    fun futureOverridesRederivePersonsAndInfinitives() {
        val rows = listOf(
            Triple("бар", "барыр", "барырмын"), Triple("бир", "бирер", "бирермен"),
            Triple("бел", "белер", "белермен"), Triple("бул", "булыр", "булырмын"),
            Triple("ал", "алыр", "алырмын"), Triple("әйт", "әйтер", "әйтермен"),
            Triple("кал", "калыр", "калырмын"), Triple("кил", "килер", "килермен"),
            Triple("тор", "торыр", "торырмын"), Triple("тул", "тулыр", "тулырмын"),
            Triple("йөр", "йөрер", "йөрермен"),
        )
        val acceptor = acceptor(*rows.map { it.first }.toTypedArray())
        for ((stem, future, person) in rows) {
            assertTrue(stem, acceptor.accepts(future))
            assertTrue(stem, acceptor.accepts(person))
            assertTrue(stem, acceptor.accepts(person + if (stem in setOf("бир", "бел", "әйт", "кил", "йөр")) "ме" else "мы"))
        }
        assertAccepted(
            acceptor,
            "барырга", "бирергә", "белергә", "булырга", "алырга", "әйтергә", "калырга", "килергә",
            "торырга", "тулырга", "йөрергә",
        )
        assertRejected(acceptor, "барар", "бирәр", "беләр", "торар", "киләргә", "укарга")
    }

    @Test
    fun everyUkuOverrideIsApplied() {
        val acceptor = acceptor("укы")
        assertAccepted(
            acceptor,
            "уку", "укыр", "укырмын", "укырга", "укачак", "укачаксыз", "укмый", "укмыйм", "укмыйммы",
            "укмас", "укмасбыз", "укмады", "укмадым", "укмаган", "укмаганнар", "укмыйча",
        )
        assertRejected(acceptor, "укыю", "укачакыр", "укымый", "укымас", "укымады")
    }

    @Test
    fun masdarOverrideMatchesDirectlyWithoutAnAssetStem() {
        val matcher = TatarWordformMatcher()
        val exception = LexicalException("ю", overrides = mapOf(WordformLabels.VERB_MASDAR to "юу"))
        val query = "юу".toCharArray()
        assertTrue(matcher.matches(charArrayOf('ю'), 1, 40, exception, query, query.size, false, true))
    }

    @Test
    fun derivationLabelsDoNotConsumeOverrides() {
        val exceptions = arrayOf(
            LexicalException("дус", overrides = mapOf("deriv.lik" to "башкалалык")),
        )
        val acceptor = MorphologyTestSupport.acceptor(listOf(MorphologyTestSupport.entry("дус")), exceptions)
        assertAccepted(acceptor, "дуслык")
        assertRejected(acceptor, "башкалалык")
    }

    @Test
    fun nonThirdPersonVerbOverridesAreTerminalOnly() {
        val exceptions = arrayOf(
            LexicalException(
                "яз",
                overrides = mapOf(WordformLabels.VERB_PRES_1SG to "язалым"),
            ),
        )
        val acceptor = MorphologyTestSupport.acceptor(listOf(MorphologyTestSupport.entry("яз")), exceptions)
        assertAccepted(acceptor, "язалым", "язаммы")
        assertRejected(acceptor, "язалыммы")

        val withQuestionOverride = arrayOf(
            LexicalException(
                "яз",
                overrides = mapOf(
                    WordformLabels.VERB_PRES_1SG to "язалым",
                    WordformLabels.VERB_PRES_1SG_Q to "язагайныммы",
                ),
            ),
        )
        val overridden = MorphologyTestSupport.acceptor(
            listOf(MorphologyTestSupport.entry("яз")), withQuestionOverride,
        )
        assertAccepted(overridden, "язалым", "язагайныммы")
        assertRejected(overridden, "язаммы", "язалыммы")
    }

    @Test
    fun nominalPredicateOverridesRederiveTheirDefaultQuestion() {
        val exceptions = arrayOf(
            LexicalException("татар", overrides = mapOf(WordformLabels.NOUN_PRED_1SG to "татарымын")),
        )
        val acceptor = MorphologyTestSupport.acceptor(
            listOf(MorphologyTestSupport.entry("татар")), exceptions,
        )
        assertAccepted(acceptor, "татарымын", "татарымынмы", "татарсың")
        assertRejected(acceptor, "татармын", "татармынмы")
    }

    @Test
    fun bareOnlyStemsNeverUnlockOverridesOrChains() {
        val acceptor = MorphologyTestSupport.acceptor(
            listOf(
                MorphologyTestSupport.entry("татар", nounChain = false, verbChain = false),
                MorphologyTestSupport.entry("халык", nounChain = false, verbChain = false),
                MorphologyTestSupport.entry("ул", nounChain = false, verbChain = false),
                MorphologyTestSupport.entry("бар", nounChain = false, verbChain = false),
            ),
        )
        assertAccepted(acceptor, "татар", "халык", "ул", "бар")
        assertRejected(acceptor, "татарлар", "татарча", "тагар", "халкы", "аның", "барыр", "бармын")
    }

    @Test
    fun negativeImperativesAndNominalizedVerbChainsAreAccepted() {
        val acceptor = acceptor("көл", "яз", "кызыксын", "ела", "алын", "ышан", "эзлә", "кил")
        assertAccepted(
            acceptor,
            "көлмә", "көлмәгез", "көлмик", "көлмәсен", "көлмәсеннәр",
            "язма", "язмагыз", "язмыйк", "язмасын", "язмасыннар",
            "кызыксынмау", "елавың", "елавыңны", "алынмавың", "алынмавыңны",
            "ышанганың", "ышанганыңны", "эзләүчесе", "эзләүчене", "киләчәктән", "киләчәге",
        )
        assertRejected(acceptor, "көлма", "кызыксынмәү", "елавең", "эзләүчесы")
    }

    @Test
    fun oneVerbDerivationLayerFeedsTenseAndNominalChains() {
        val acceptor = acceptor("ат", "иңдер", "күр", "сөйлә", "тип", "тешлә", "моң", "ычкын", "кис")
        assertAccepted(
            acceptor,
            "атыл", "атылачакбыз", "иңдереләчәк", "күрешер", "сөйләш", "сөйләшә",
            "типтермә", "тешләтмә", "моңланмый", "моңлана", "ычкындырма", "кистертми",
        )
        val denominal = MorphologyTestSupport.acceptor(listOf(
            MorphologyTestSupport.entry("зомби", forceBack = true),
        ))
        assertAccepted(denominal, "зомбилашкан")
        assertRejected(acceptor, "атыләчәкбыз", "моңланмыйды", "типтермәг")
    }

    @Test
    fun locativeAttributiveSimilativeAndTerminativeCasesAreAccepted() {
        val acceptor = MorphologyTestSupport.acceptor(listOf(
            MorphologyTestSupport.entry("дача"),
            MorphologyTestSupport.entry("концлагерь", forceBack = true),
            MorphologyTestSupport.entry("май"),
            MorphologyTestSupport.entry("картлык"),
        ))
        assertAccepted(acceptor, "дачадагы", "концлагерьларындагы", "майдай", "картлыккача")
        assertRejected(acceptor, "дачадаге", "майдәй", "картлыкгачә")
    }

    @Test
    fun assumptiveCliticFollowsTheWholeForm() {
        val acceptor = acceptor("мөселман", "әсәр", "яз", "канал")
        assertAccepted(acceptor, "мөселманнардыр", "әсәрдер", "язгандыр", "каналдандыр")
        assertRejected(acceptor, "мөселманнардер", "әсәрдыр", "язгандар")
    }

    @Test
    fun malformedOutsideAndOversizedInputIsANonMatch() {
        val acceptor = acceptor("татар", "яз")
        val oversized = "я" + "з".repeat(MAX_QUERY_CODE_POINTS)
        for (query in listOf("", "я", "Tатар", "татар'", "та-тар", "-татар", "татар-", "та--тар", "татар ", "татар😀", oversized)) {
            assertFalse(query, acceptor.accepts(query))
        }
        assertFalse(acceptor.accepts("татар\u0306"))
    }

    @Test
    fun utf8EntryPointMatchesStringsAndReusesScratchAfterRejectedInput() {
        val acceptor = acceptor("татар", "яз")
        for (word in listOf("татар", "татарлар", "яздым")) {
            val bytes = word.toByteArray(Charsets.UTF_8)
            assertTrue(word, acceptor.acceptsUtf8(bytes, bytes.size))
        }
        assertFalse(acceptor.acceptsUtf8(ByteArray(0), 0))
        val valid = "татар".toByteArray(Charsets.UTF_8)
        assertFalse(acceptor.acceptsUtf8(valid, valid.size + 1))
        assertFalse(acceptor.acceptsUtf8(valid, valid.size - 1))
        assertFalse(acceptor.acceptsUtf8(byteArrayOf(0xc3.toByte(), 0x28), 2))
        val emoji = "татар😀".toByteArray(Charsets.UTF_8)
        assertFalse(acceptor.acceptsUtf8(emoji, emoji.size))
        assertTrue(acceptor.acceptsUtf8(valid, valid.size))
    }

    @Test
    fun dictionaryIdentityComesFromTheArtifactSpec() {
        val entries = listOf(MorphologyTestSupport.entry("татар"))
        val artifact = rkr.simplekeyboard.inputmethod.latin.dictionary.storage.StemLexiconTestFixtures.artifact(entries)
        val validated = rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TatStemValidator()
            .inflateAsset(artifact.compressed.inputStream(), artifact.spec)
        val acceptor = TatarMorphologicalAcceptor(validated, artifact.spec)
        assertEquals(artifact.spec.expectedDictionaryRawSha256, acceptor.dictionaryRawSha256)
    }

    private fun acceptor(vararg stems: String): TatarMorphologicalAcceptor =
        acceptor(stems.toList())

    private fun acceptor(stems: List<String>): TatarMorphologicalAcceptor =
        MorphologyTestSupport.acceptor(stems.map { MorphologyTestSupport.entry(it) })

    private fun acceptor(
        stem: String,
        noun: Boolean,
        verb: Boolean,
        nounChain: Boolean,
        verbChain: Boolean,
    ): TatarMorphologicalAcceptor = MorphologyTestSupport.acceptor(
        listOf(MorphologyTestSupport.entry(stem, noun, verb, nounChain, verbChain)),
    )

    private fun assertAccepted(acceptor: TatarMorphologicalAcceptor, vararg forms: String) {
        for (form in forms) assertTrue(form, acceptor.accepts(form))
    }

    private fun assertRejected(acceptor: TatarMorphologicalAcceptor, vararg forms: String) {
        for (form in forms) assertFalse(form, acceptor.accepts(form))
    }
}
