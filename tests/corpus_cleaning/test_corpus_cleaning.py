#!/usr/bin/env python3
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "research/corpus"))
sys.path.insert(0, str(ROOT / "scripts"))

import corpus_cleaning as cleaning
import evaluation_splits
import dictionary_coverage as coverage


class ExtractionTest(unittest.TestCase):
    def test_controls_and_soft_hyphen_are_removed(self):
        self.assertEqual(cleaning.normalize_line("Рәх\u00adмәт\u200b  бар\u0000"), "Рәхмәт бар")
        self.assertNotIn("�", cleaning.normalize_line("бозау�"))

    def test_extracts_physical_lines_and_sentences(self):
        self.assertEqual(
            cleaning.extract_lines("Сәләм! Ничек яшисең?\r\nРәхмәт."),
            ["Сәләм!", "Ничек яшисең?", "Рәхмәт."],
        )

    def test_sentence_key_matches_split_normalization(self):
        line = "«Азнакайда» 12нче класс укучылары концерт куйды."
        expected = evaluation_splits.normalize_sentence(line, coverage.TATAR_ALPHABET)
        self.assertEqual(cleaning.sentence_key(line), expected)


class LanguageTest(unittest.TestCase):
    def test_tatar_without_special_letters(self):
        self.assertEqual(cleaning.classify_language("Мин барам, син дә кил."), "tatar")
        self.assertEqual(cleaning.classify_language("Бар юк"), "tatar")

    def test_confusable_languages(self):
        self.assertEqual(cleaning.classify_language("Беҙҙең ауылда ҡар яуҙы"), "bashkir")
        self.assertEqual(cleaning.classify_language("Это наш дом и наша работа."), "russian")
        self.assertEqual(cleaning.classify_language("Қазақстанда қала және ұл бар."), "kazakh")
        self.assertEqual(cleaning.classify_language("Пирӗн ӗҫеҫӗпӗр"), "chuvash")
        self.assertEqual(cleaning.classify_language("Min sinen belän baram, dips"), "tatar_latin")
        self.assertEqual(cleaning.classify_language("Қазақстанда тәжірибе жинадық"), "kazakh")
        self.assertEqual(cleaning.classify_language("бө\u0493өнгө"), "bashkir")
        self.assertEqual(cleaning.classify_language("Іван"), "other")

    def test_code_switching_is_not_russian_loan(self):
        self.assertEqual(cleaning.classify_language("Мин это делаю, син дом"), "mixed")
        self.assertEqual(cleaning.classify_language("Казан шәһәрендә интернет тиз эшли."), "tatar")

    def test_empty_and_other(self):
        self.assertEqual(cleaning.classify_language("123 !"), "empty")
        self.assertEqual(cleaning.classify_language("Hello world"), "other")

    def test_dictionary_evidence_recovers_regular_tatar(self):
        tatar = frozenset({"аванс", "ай", "уртасында", "бирелде"})
        russian = frozenset({"он", "сказал", "что", "не"})
        self.assertEqual(cleaning.classify_language("аванс ай уртасында бирелде", tatar, russian), "tatar")
        self.assertEqual(cleaning.classify_language("он сказал что не", tatar, russian), "russian")


class QualityTest(unittest.TestCase):
    def test_boilerplate_does_not_include_ordinary_prose(self):
        self.assertEqual(cleaning.boilerplate_reason("Поделиться в ВКонтакте"), "share")
        self.assertEqual(cleaning.boilerplate_reason("We use cookies and cookie policy"), "cookie")
        self.assertIsNone(cleaning.boilerplate_reason("Әти белән чәй эчтек."))
        self.assertIsNone(cleaning.boilerplate_reason("Элемтәләр өчен телефон: [PHONE]"))

    def test_explicit_machine_translation(self):
        self.assertEqual(cleaning.quality_reason("Translated by Google Translate"), "machine_translation")
        self.assertIsNone(cleaning.quality_reason("Тәрҗемәне яхшы итеп башкардык"))

    def test_seo_and_encoding(self):
        self.assertEqual(cleaning.quality_reason("online casino free spins http://spam.invalid"), "seo_spam")
        self.assertEqual(cleaning.quality_reason("Төпле cценарий яздык"), "encoding")
        self.assertIsNone(cleaning.quality_reason("Бик яхшы һәм кызыклы китап"))

    def test_parallel_gloss_machine_translation(self):
        line = "Latin text |Latin text| Latin text |Җыр| Latin text |җыр|"
        self.assertEqual(cleaning.quality_reason(line), "machine_translation")
        self.assertIsNone(cleaning.quality_reason("Китап укыдык һәм сөйләштек"))


class ChatSpellingTest(unittest.TestCase):
    def setUp(self):
        self.tatar = frozenset({"рәхмәт", "белән", "һәм", "кирәк", "моңда", "монда"})
        self.russian = frozenset({"кир", "монда"})

    def test_multiple_substitutions_and_words(self):
        self.assertEqual(
            cleaning.chat_spelling_words("Рэхмэт, белэн хэм кирэк", self.tatar, self.russian),
            ("белэн", "кирэк", "рэхмэт", "хэм"),
        )

    def test_ambiguous_existing_form_does_not_convict(self):
        self.assertEqual(cleaning.chat_spelling_words("монда", self.tatar, self.russian), ())

    def test_standard_tatar_is_not_chat_spelling(self):
        self.assertEqual(cleaning.chat_spelling_words("рәхмәт белән", self.tatar, self.russian), ())

    def test_reviewed_variant_overrides_contaminated_membership(self):
        tatar = self.tatar | {"рэхмэт"}
        self.assertEqual(cleaning.chat_spelling_map("рэхмэт", tatar, self.russian), {"рэхмэт": "рәхмәт"})


if __name__ == "__main__":
    unittest.main()
