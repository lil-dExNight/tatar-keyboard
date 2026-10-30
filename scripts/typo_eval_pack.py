#!/usr/bin/env python3
"""Build the deterministic typo-mutated held-out eval set for typo-recovery measurement.

Substrate: the unique words of the language's held-out eval set (``tt_eval_sentences.txt`` or
``ru_eval_sentences.txt``) that are words of the pinned bundled dictionary and have at least 3
code points. One mutation per (word, class): ``sub`` replaces a code point with a geometric key
neighbor, ``del`` deletes one code point, ``ins`` inserts a layout-alphabet letter, ``trans``
swaps an adjacent pair of distinct code points. Each (word, class) pair draws its choices from a
private SplitMix64 stream seeded ``splitmix64(seed ^ fnv1a64(word)) ^ class_tag`` (the seed is
the language's; class tags are 1..4 in the class order above), walked position-first, then the
choice within the position; ``del`` and ``trans`` have no second draw. A pair with no legal
mutation is skipped. Rows are ``original<TAB>class<TAB>mutated`` sorted by (original, class),
UTF-8/LF. The Tatar set is regenerated, never committed: its identity is the pinned SHA-256
asserted by tests/typo_eval_pack and mirrored bit-for-bit by TypoMutatedEvalTest.kt. The Russian
set is committed (``app/src/test/resources/ru_typo_eval_set.tsv``): ``build --language rus``
regenerates it, tests/typo_eval_pack asserts the committed file is byte-identical, and
RuTypoMutatedEvalTest.kt measures the committed rows. The ``safety-set`` command separately
builds the reviewed valid-OOV sets from held-out forms absent from the pinned dictionary.
``seen_form`` means that the exact form occurs in the filtered conversational training table;
``new_form_of_seen_stem`` and ``unseen_stem`` cover the remaining forms. Conservative admission
requires NFC lowercase text in the language alphabet, 3..32 code points, a vowel, and no run of
four identical letters. These rules reject formal fragments, not subtle misspellings, so every
admitted word also appears in the explicit native-plausibility review list below. Exits 2 on
missing or invalid input.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import unicodedata
import xml.etree.ElementTree as ElementTree
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping, Sequence, TextIO

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

import dict_accept  # noqa: E402
import dictionary_coverage as coverage  # noqa: E402
import suggest_chain  # noqa: E402
import typo_pack  # noqa: E402


# --- Deterministic-selection knobs. -----------------------------------------------------
# The seeds are fixed and arbitrary (the language tag in ASCII, then "EV"); changing a seed
# changes that language's whole set and the tests that pin it. The class tags double as stream
# salts so the four mutations of one word are independent draws.
TYPO_EVAL_SEED = 0x54544556
RU_TYPO_EVAL_SEED = 0x52554556
MIN_WORD_CODE_POINTS = 3
CLASS_TAGS = {"sub": 1, "del": 2, "ins": 3, "trans": 4}
CLASS_NAMES = tuple(sorted(CLASS_TAGS, key=CLASS_TAGS.get))
MIN_SAFETY_WORD_CODE_POINTS = 3
MAX_SAFETY_WORD_CODE_POINTS = 32

# Corpus attestation does not establish spelling. These held-out forms passed a separate
# native-plausibility review; grouping documents their expected stratum on the pinned inputs.
REVIEWED_VALID_OOV_WORDS = {
    "tat": (
        # Seen form in filtered conversational training.
        "ваттыңмы", "джентльменнар", "дидемме", "ияләшәчәксең", "казымый", "күчергечне",
        "ошадым", "сыртыңа", "сүндердеңме", "тәкъдимегезне", "шалтыраттың", "яшәдеңме",
        # New form of a stem present in the shipped dictionary.
        "абыйларымның", "арфам", "бергәлекләр", "берүземне", "бирешәсез", "ботинкам",
        "визага", "газетам", "галәмче", "гипсны", "гыйбарәләренең", "гыйбарәның",
        "дулкынлануым", "калман", "каргышына", "килмәячәксез", "кисмиләр", "кичергәнегез",
        "комачаулыйсыз", "кондызларны", "копияны", "күзлекләре", "күкегә", "күрешүебезне",
        "кәләшемнең", "көрәшәчәгенә", "майтаралар", "мәшгульсез", "очрарсың", "ошарсың",
        "пастасын", "профсоюзга", "радиоң", "серегез", "синә", "сумың", "сырагыз",
        "сөйләгезче", "тудырганым", "тумышыгыз", "тәкъдимнәремнән", "тәмамлыкка", "уйланучы",
        "фейерверкны", "хыялланмыйм", "чыпчыкларына", "чәйләренә", "эшләмәсләр",
        "эшләмәячәксең", "юклыгың", "яңагыннан", "җибәргәнебез", "җинаятьчеләренең",
        "җитмәгәнне", "җиңмәс", "җәйми", "үткәргәнсең", "әкиятчесе", "өремчегә",
        # No shipped-dictionary stem under the same suffix analysis.
        "алданырсың", "антрополексема", "антропонимиконында", "аударма", "ахмаклыкларны",
        "ашыгуыңны", "аңлатуыңны", "бармыйбызмы", "белештерәсем", "бетерәсеңме", "биемәгез",
        "бикләмичә", "бушатсаң", "бүлик", "горурланыйк", "графлыгындагы", "гүрелдәү",
        "дулкынлануымны", "дәреслегемне", "ертты", "заманчалыгы", "исемлегегезне", "иснәнеп",
        "ишеткәннәреңнән", "йокланылган", "кабызма", "кагасыңмы", "кайсытелләрдә",
        "кайтарылмаслык", "каралыгын", "каһвәсен", "киеренкеләндерергә", "киясезме",
        "корычагач", "кунакханәдәме", "кыздырса", "кыландырмам", "кылтаючан", "кыткылдама",
        "күтләкне", "күңелчәк", "көюләр", "магарыч", "магма", "масдарлар", "микродулкын",
        "миллиметрлап", "мәгънәсендәдер", "мәхариҗ", "начарландыра", "нудист", "онытылгач",
        "ошатыр", "пешекләгез", "пончик", "салышыйк", "сахрадагы", "серсүзегезнең", "сиземләр",
        "сикермәгез", "суффиксы", "сөелүләр", "сөмер", "табынганлыгы", "тамгачылык", "тануымны",
        "таңдагы", "тозагы", "тондыргыч", "торыргамы", "тыңламаучан", "тәшкиләтле",
        "төшерүеңә", "төшмәячәгенә", "улыңмы", "хәтерләгәннәрнең", "чакыруыма", "чыштырдаган",
        "шапылдата", "шунча", "шөгыльләнәсезме", "эшләмәячәгемне", "юешләнгән", "юлыктырды",
        "ябылганын", "япканымны", "яратышмаган", "яшердеңме", "җау", "җимерегез", "җирәнчәк",
        "җитмәвемне", "җылау", "җылынмады", "җырламагыз", "җәрәхәтләде", "җөмләләремнең",
        "үлчәнми", "әйтешергә", "өйләнәсеңме", "өйрәнәсезме", "өлгерерме", "өркәчле", "өшкерә",
    ),
    "rus": (
        # Seen form in filtered conversational training.
        "гасит", "долгожданные", "задумчиво", "минному", "одесское", "пешеходными",
        "приумножать", "расчёска", "сверхзвуковых", "стремилось", "усадила",
        # New form of a stem with at least three other shipped-dictionary forms.
        "арктическое", "бессмертию", "возникай", "высосав", "грубиянов", "дрозды",
        "замещающий", "замёрзло", "злоупотребляй", "кокосовых", "комментирую", "кошмарными",
        "молол", "мостиками", "мучением", "норвежскую", "отговорили", "переполнения",
        "подвинулись", "подружатся", "покойны", "покрасят", "понедельников", "постригла",
        "преступниц", "прохладные", "сменяли", "согрел", "спутаны", "стремительней",
        "схваткой", "термометрах", "хлопотами", "шахматными",
        # No sufficiently attested shipped-dictionary stem under the same analysis.
        "атлетического", "безмозглость", "близорук", "боге", "бодрящего", "валенках",
        "вздут", "вполуха", "газифицирована", "дебоширить", "дефенестрация", "добродушного",
        "домыл", "донимая", "дословный", "жаворонком", "заверено", "замаячил", "измучила",
        "имаджинировали", "интенсифицировать", "каталонском", "кваса", "коран", "кормящая",
        "косинуса", "куревом", "ладожское", "лестничном", "межнациональный", "нахмурила",
        "незаполненным", "немодное", "нерестилище", "нумизматика", "обласкан", "обхитрили",
        "отвесил", "перекрещиваясь", "пластмассовая", "покусывать", "пражская", "предрассудок",
        "предрождественскими", "проймёшь", "пухлая", "размышлениях", "раскритикует",
        "распахнулась", "расщепить", "рекламирую", "рокироваться", "слюнявчик", "снюхался",
        "созревал", "сосущий", "суеверная", "сёмга", "толпилась", "тридцатилетнюю",
        "удивлённой", "успеваемостью", "фарминдустрию", "фундаменталистом",
        "электрообогревателя",
    ),
}

_RES_XML_DIR = ROOT / "app" / "src" / "main" / "res" / "xml"
_TEST_RESOURCES_DIR = ROOT / "app" / "src" / "test" / "resources"
_DICTIONARIES_DIR = ROOT / "app" / "src" / "main" / "assets" / "dictionaries"

# The Russian bundled dictionary identity, mirroring
# DictionaryStorageContracts.RUSSIAN_TOP100K_V1.
_RUSSIAN_ASSET_SHA256 = "273f1a6928f74fdbd7f0fda4490978124b3748160ac283d0b51e8c913fea5fde"
_RUSSIAN_RAW_SHA256 = "f05499a3b4c3c2811c6ee8e9084dfaf472951a289a20dde2ab8cb098cb5a15b2"
_RUSSIAN_ENTRY_COUNT = 100_000


@dataclass(frozen=True)
class LanguageSpec:
    """One language's pinned generator inputs: eval set, dictionary, layout files and seed.

    ``rowkey_files`` feed the insertion alphabet; ``rows_file`` (which includes them by
    reference) feeds the geometric map. ``default_output`` is the committed set path, or None
    when the language's set is regenerated and never committed. ``conv_frequency`` supplies
    exact-form training attestation and ``safety_output`` is the committed valid-OOV set path.
    """

    tag: str
    seed: int
    eval_set: Path
    dictionary: Path
    dictionary_asset_sha256: str
    dictionary_raw_sha256: str
    dictionary_entry_count: int
    rows_file: str
    rowkey_files: tuple[str, ...]
    default_output: Path | None
    conv_frequency: Path
    safety_output: Path


TATAR = LanguageSpec(
    tag="tat",
    seed=TYPO_EVAL_SEED,
    eval_set=_TEST_RESOURCES_DIR / "tt_eval_sentences.txt",
    dictionary=_DICTIONARIES_DIR / "tatar_top100k_v1.tdict.zlib",
    dictionary_asset_sha256=typo_pack.EXPECTED_ASSET_SHA256,
    dictionary_raw_sha256=typo_pack.EXPECTED_RAW_SHA256,
    dictionary_entry_count=typo_pack.EXPECTED_ENTRY_COUNT,
    rows_file="rows_tatar.xml",
    rowkey_files=typo_pack._TATAR_ROWKEY_FILES,
    default_output=None,
    conv_frequency=ROOT / "data" / "dictionary" / "dict-accept" / "conv-freq-tt.tsv",
    safety_output=_TEST_RESOURCES_DIR / "tt_valid_oov_safety_set.tsv",
)
RUSSIAN = LanguageSpec(
    tag="rus",
    seed=RU_TYPO_EVAL_SEED,
    eval_set=_TEST_RESOURCES_DIR / "ru_eval_sentences.txt",
    dictionary=_DICTIONARIES_DIR / "russian_top100k_v1.tdict.zlib",
    dictionary_asset_sha256=_RUSSIAN_ASSET_SHA256,
    dictionary_raw_sha256=_RUSSIAN_RAW_SHA256,
    dictionary_entry_count=_RUSSIAN_ENTRY_COUNT,
    rows_file="rows_russian.xml",
    rowkey_files=(
        "rowkeys_russian1.xml",
        "rowkeys_russian2.xml",
        "rowkeys_russian3.xml",
    ),
    default_output=_TEST_RESOURCES_DIR / "ru_typo_eval_set.tsv",
    conv_frequency=ROOT / "data" / "dictionary" / "dict-accept" / "conv-freq-ru.tsv",
    safety_output=_TEST_RESOURCES_DIR / "ru_valid_oov_safety_set.tsv",
)
LANGUAGES = {language.tag: language for language in (TATAR, RUSSIAN)}


class SplitMixStream:
    """One private SplitMix64 stream for a (word, class) pair; see the module docstring.

    ``next()`` returns the current mixed state and then advances the state by the SplitMix64
    gamma, exactly the sequence TypoMutatedEvalTest.kt walks on the JVM.
    """

    __slots__ = ("state",)

    def __init__(self, seed: int) -> None:
        self.state = seed & typo_pack._MASK64

    def next(self) -> int:
        output = typo_pack.splitmix64(self.state)
        self.state = (self.state + typo_pack._SPLITMIX_GAMMA) & typo_pack._MASK64
        return output

    def index(self, choices: int) -> int:
        """The next output mapped to ``[0, choices)`` by unsigned modulo."""
        if choices <= 0:
            raise typo_pack.TypoPackError("selection over an empty choice set")
        return self.next() % choices


def stream_seed(word: str, class_tag: int, *, seed: int = TYPO_EVAL_SEED) -> int:
    """The per-(word, class) stream seed (see the module docstring)."""
    return typo_pack.splitmix64(seed ^ typo_pack.fnv1a64(word.encode("utf-8"))) ^ class_tag


# --------------------------------------------------------------------------------------
# The four mutation primitives. Each returns the mutated code points, or None when the word
# has no legal mutation of the class.
# --------------------------------------------------------------------------------------
def mutate_sub(
    code_points: tuple[int, ...],
    geometric_map: dict[int, tuple[int, ...]],
    alphabet: tuple[int, ...],
    stream: SplitMixStream,
) -> tuple[int, ...] | None:
    """``sub``: one code point replaced by a geometric neighbor; position draw, neighbor draw."""
    positions = [i for i, cp in enumerate(code_points) if geometric_map.get(cp)]
    if not positions:
        return None
    position = positions[stream.index(len(positions))]
    neighbors = geometric_map[code_points[position]]
    mutated = list(code_points)
    mutated[position] = neighbors[stream.index(len(neighbors))]
    return tuple(mutated)


def mutate_del(
    code_points: tuple[int, ...],
    geometric_map: dict[int, tuple[int, ...]],
    alphabet: tuple[int, ...],
    stream: SplitMixStream,
) -> tuple[int, ...] | None:
    """``del``: one code point removed; a single position draw."""
    position = stream.index(len(code_points))
    return code_points[:position] + code_points[position + 1 :]


def mutate_ins(
    code_points: tuple[int, ...],
    geometric_map: dict[int, tuple[int, ...]],
    alphabet: tuple[int, ...],
    stream: SplitMixStream,
) -> tuple[int, ...] | None:
    """``ins``: one alphabet letter inserted; position draw (0..len), then the letter draw."""
    position = stream.index(len(code_points) + 1)
    letter = alphabet[stream.index(len(alphabet))]
    return code_points[:position] + (letter,) + code_points[position:]


def mutate_trans(
    code_points: tuple[int, ...],
    geometric_map: dict[int, tuple[int, ...]],
    alphabet: tuple[int, ...],
    stream: SplitMixStream,
) -> tuple[int, ...] | None:
    """``trans``: two adjacent distinct code points swapped; a single pivot draw."""
    pivots = [i for i in range(len(code_points) - 1) if code_points[i] != code_points[i + 1]]
    if not pivots:
        return None
    pivot = pivots[stream.index(len(pivots))]
    mutated = list(code_points)
    mutated[pivot], mutated[pivot + 1] = mutated[pivot + 1], mutated[pivot]
    return tuple(mutated)


_MUTATORS = {
    "sub": mutate_sub,
    "del": mutate_del,
    "ins": mutate_ins,
    "trans": mutate_trans,
}


# --------------------------------------------------------------------------------------
# Layout reading, parameterized by the language's resource files. The parsing and geometry
# primitives live in typo_pack (one implementation); only the file names are per language.
# --------------------------------------------------------------------------------------
def _row_width_specs(
    layout_dir: Path, rows_file: str
) -> list[list[tuple[str | None, float | str | None]]]:
    """The rows XML as rows of (single-char keySpec or None, width percent or fill).

    Same model as ``typo_pack._row_width_specs``: a None width is the row's default keyWidth and
    "fill" is fillRight; non-letter keys keep their width entry but carry no keySpec.
    """
    rows_path = layout_dir / rows_file
    if not rows_path.is_file():
        raise typo_pack.TypoPackError(f"layout resource is missing: {rows_path}")
    try:
        root = ElementTree.parse(rows_path).getroot()
    except (ElementTree.ParseError, OSError) as error:
        raise typo_pack.TypoPackError(
            f"cannot parse layout resource {rows_path}: {error}"
        ) from error
    key_spec_attr = f"{{{typo_pack._ANDROID_RES_AUTO}}}keySpec"
    key_width_attr = f"{{{typo_pack._ANDROID_RES_AUTO}}}keyWidth"
    layout_attr = f"{{{typo_pack._ANDROID_RES_AUTO}}}keyboardLayout"
    rows: list[list[tuple[str | None, float | str | None]]] = []
    for row in root.findall("Row"):
        entries: list[tuple[str | None, float | str | None]] = []
        for child in row:
            if child.tag == "Key":
                width_attr = child.get(key_width_attr)
                width: float | str | None
                if width_attr == "fillRight":
                    width = "fill"
                elif width_attr is not None:
                    width = typo_pack._parse_percent(width_attr)
                else:
                    width = None  # the row default applies
                spec = child.get(key_spec_attr)
                entries.append((spec if spec is not None and len(spec) == 1 else None, width))
            elif child.tag == "include":
                include_layout = child.get(layout_attr)
                if include_layout is None:
                    continue
                name = include_layout.split("/")[-1] + ".xml"
                for spec in typo_pack._read_row_key_specs(layout_dir / name):
                    entries.append((spec, None))
        rows.append(entries)
    return rows


def read_language_geometric_map(
    layout_dir: Path, language: LanguageSpec
) -> dict[int, tuple[int, ...]]:
    """The geometric key-neighbor relation of the language's rows file.

    Same model as ``typo_pack.read_layout_geometric_map``: the device key rectangles on the
    reference grid (gap/padding from the shared ``res/values/config.xml``), then the overlap
    relation of ``typo_pack.build_geometric_map``.
    """
    raw_rows = _row_width_specs(layout_dir, language.rows_file)
    rows_path = layout_dir / language.rows_file
    try:
        root = ElementTree.parse(rows_path).getroot()
    except (ElementTree.ParseError, OSError) as error:
        raise typo_pack.TypoPackError(
            f"cannot parse layout resource {rows_path}: {error}"
        ) from error
    key_width_attr = f"{{{typo_pack._ANDROID_RES_AUTO}}}keyWidth"
    row_widths: list[float] = []
    for row in root.findall("Row"):
        row_width_attr = row.get(key_width_attr)
        row_widths.append(typo_pack._parse_percent(row_width_attr) if row_width_attr else 0.0)
    if len(row_widths) != len(raw_rows):
        raise typo_pack.TypoPackError("row count mismatch while resolving row default widths")
    resolved: list[list[tuple[str | None, float | str]]] = []
    for entries, row_width in zip(raw_rows, row_widths):
        resolved.append(
            [(spec, row_width if width is None else width) for spec, width in entries]
        )
    config_path = layout_dir.parent / "values" / typo_pack._VALUES_CONFIG_FILE
    gap, left_pad, right_pad = typo_pack.read_keyboard_gaps(config_path)
    geometric_map = typo_pack.build_geometric_map(
        typo_pack.build_device_geometry(
            resolved,
            gap_percent=gap,
            left_padding_percent=left_pad,
            right_padding_percent=right_pad,
        )
    )
    if not geometric_map:
        raise typo_pack.TypoPackError("layout geometry yielded no geometric neighbour")
    return geometric_map


def read_language_alphabet(layout_dir: Path, language: LanguageSpec) -> tuple[int, ...]:
    """The language's typeable letters as a sorted tuple of code points (== the node set).

    Same model as ``typo_pack.read_layout_alphabet``: every letter key of the rowkeys files plus
    every letter appearing in their ``latin:moreKeys``.
    """
    letters: set[int] = set()
    for name in language.rowkey_files:
        path = layout_dir / name
        if not path.is_file():
            raise typo_pack.TypoPackError(f"layout resource is missing: {path}")
        try:
            root = ElementTree.parse(path).getroot()
        except (ElementTree.ParseError, OSError) as error:
            raise typo_pack.TypoPackError(
                f"cannot parse layout resource {path}: {error}"
            ) from error
        key_spec_attr = f"{{{typo_pack._ANDROID_RES_AUTO}}}keySpec"
        more_keys_attr = f"{{{typo_pack._ANDROID_RES_AUTO}}}moreKeys"
        for key in root.iter("Key"):
            spec = key.get(key_spec_attr)
            if spec is not None and len(spec) == 1:
                normalized = typo_pack._normalize_letter(ord(spec))
                if normalized is not None:
                    letters.add(normalized)
            more_keys = key.get(more_keys_attr)
            if more_keys:
                for token in more_keys.split(","):
                    token = token.strip()
                    if len(token) != 1:
                        continue
                    normalized = typo_pack._normalize_letter(ord(token))
                    if normalized is not None:
                        letters.add(normalized)
    if not letters:
        raise typo_pack.TypoPackError("layout resources yielded no alphabet letter")
    return tuple(sorted(letters))


def read_eval_words(eval_set_path: Path) -> set[str]:
    """The unique words of the eval set: blank lines and ``#`` header lines are skipped."""
    if not eval_set_path.is_file():
        raise typo_pack.TypoPackError(f"eval set is missing: {eval_set_path}")
    words: set[str] = set()
    for line in eval_set_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        words.update(token for token in line.split(" ") if token)
    if not words:
        raise typo_pack.TypoPackError(f"eval set has no words: {eval_set_path}")
    return words


def substrate_words(dictionary_words: Sequence[str], eval_words: set[str]) -> list[str]:
    """The dictionary words (stored order) that are eval words of >= 3 code points."""
    return [
        word
        for word in dictionary_words
        if len(word) >= MIN_WORD_CODE_POINTS and word in eval_words
    ]


@dataclass(frozen=True)
class TypoEvalSet:
    rows: tuple[tuple[str, str, str], ...]
    text: str
    data: bytes
    class_counts: dict[str, int]
    substrate_count: int

    @property
    def size(self) -> int:
        return len(self.rows)

    @property
    def sha256(self) -> str:
        return hashlib.sha256(self.data).hexdigest()


@dataclass(frozen=True)
class ValidOovSafetySet:
    rows: tuple[tuple[str, str], ...]
    text: str
    data: bytes
    stratum_counts: dict[str, int]

    @property
    def size(self) -> int:
        return len(self.rows)

    @property
    def sha256(self) -> str:
        return hashlib.sha256(self.data).hexdigest()


def is_native_plausible_form(word: str, language: LanguageSpec) -> bool:
    """Apply conservative formal checks; the review list decides actual admission."""
    if not MIN_SAFETY_WORD_CODE_POINTS <= len(word) <= MAX_SAFETY_WORD_CODE_POINTS:
        return False
    if word != unicodedata.normalize("NFC", word).lower():
        return False
    normalized, _reason = coverage.normalize_word(
        word, coverage.language_for(language.tag).alphabet
    )
    if normalized != word:
        return False
    if not set(word) & dict_accept.VOWELS[language.tag]:
        return False
    return not any(character * 4 in word for character in set(word))


def _russian_seen_stem(word: str, vocabulary: frozenset[str]) -> str | None:
    """Return the deterministic best stem with three other attested forms, if one exists."""
    candidates: list[tuple[int, int, str, str]] = []
    for ending in sorted(dict_accept.RUSSIAN_ENDINGS):
        if ending and not word.endswith(ending):
            continue
        stem = word[: len(word) - len(ending)] if ending else word
        if len(stem) < dict_accept.MIN_STEM:
            continue
        siblings = sum(
            other != ending and stem + other != word and stem + other in vocabulary
            for other in dict_accept.RUSSIAN_ENDINGS
        )
        if siblings >= dict_accept.MIN_PARADIGM_SIBLINGS:
            candidates.append((-siblings, -len(stem), stem, ending))
    if not candidates:
        return None
    return min(candidates)[2]


def valid_oov_stratum(
    word: str, vocabulary: frozenset[str], seen_frequencies: Mapping[str, int],
    language: LanguageSpec,
) -> str:
    """Classify an OOV form by exact training attestation, then by shipped stem evidence."""
    if seen_frequencies.get(word, 0) > 0:
        return suggest_chain.STRATUM_SEEN_FORM
    if language.tag == TATAR.tag:
        stem = suggest_chain.longest_stem_with_suffix_remainder(
            word, lambda candidate: int(candidate in vocabulary)
        )
    else:
        stem = _russian_seen_stem(word, vocabulary)
    return (
        suggest_chain.STRATUM_NEW_FORM
        if stem is not None
        else suggest_chain.STRATUM_UNSEEN_STEM
    )


def build_valid_oov_safety_set(
    reviewed_words: Sequence[str],
    dictionary_words: Sequence[str],
    eval_words: set[str],
    seen_frequencies: Mapping[str, int],
    *,
    language: LanguageSpec = TATAR,
) -> ValidOovSafetySet:
    """Render the reviewed OOV rows after checking provenance, filtering and stratification."""
    if len(set(reviewed_words)) != len(reviewed_words):
        raise typo_pack.TypoPackError("valid-OOV review list contains a duplicate")
    vocabulary = frozenset(dictionary_words)
    rows: list[tuple[str, str]] = []
    for word in reviewed_words:
        if word not in eval_words:
            raise typo_pack.TypoPackError(f"reviewed safety word is not held out: {word}")
        if word in vocabulary:
            raise typo_pack.TypoPackError(f"reviewed safety word is not OOV: {word}")
        if not is_native_plausible_form(word, language):
            raise typo_pack.TypoPackError(f"reviewed safety word fails formal checks: {word}")
        rows.append((word, valid_oov_stratum(word, vocabulary, seen_frequencies, language)))
    rows.sort()
    counts = {name: 0 for name in suggest_chain.STRATA}
    for _word, stratum in rows:
        counts[stratum] += 1
    missing = [name for name, count in counts.items() if count == 0]
    if missing:
        raise typo_pack.TypoPackError(
            f"valid-OOV safety set lacks strata: {', '.join(missing)}"
        )
    text = "".join(f"{word}\t{stratum}\n" for word, stratum in rows)
    return ValidOovSafetySet(
        rows=tuple(rows),
        text=text,
        data=text.encode("utf-8"),
        stratum_counts=counts,
    )


def read_conv_frequencies(path: Path) -> dict[str, int]:
    """Read the committed filtered training frequencies with strict row validation."""
    if not path.is_file():
        raise typo_pack.TypoPackError(f"conversational frequency table is missing: {path}")
    frequencies: dict[str, int] = {}
    for line_number, line in enumerate(
        path.read_text(encoding="utf-8").splitlines(), start=1
    ):
        if not line or line.startswith("#") or line == "word\tconv_freq":
            continue
        fields = line.split("\t")
        if len(fields) != 2 or not fields[0] or not fields[1].isascii() or not fields[1].isdigit():
            raise typo_pack.TypoPackError(
                f"malformed conversational frequency row: {path}:{line_number}"
            )
        word, value_text = fields
        value = int(value_text)
        if value <= 0 or word in frequencies:
            raise typo_pack.TypoPackError(
                f"invalid conversational frequency row: {path}:{line_number}"
            )
        frequencies[word] = value
    if not frequencies:
        raise typo_pack.TypoPackError(f"conversational frequency table is empty: {path}")
    return frequencies


def generate_valid_oov_safety_set(
    eval_set_path: Path,
    dictionary_path: Path,
    conv_frequency_path: Path,
    *,
    language: LanguageSpec = TATAR,
) -> ValidOovSafetySet:
    """Read the pinned assets and build that language's reviewed valid-OOV safety set."""
    dictionary_words = typo_pack.read_dictionary_words(
        dictionary_path,
        expected_asset_sha256=language.dictionary_asset_sha256,
        expected_raw_sha256=language.dictionary_raw_sha256,
        expected_entry_count=language.dictionary_entry_count,
    )
    return build_valid_oov_safety_set(
        REVIEWED_VALID_OOV_WORDS[language.tag],
        dictionary_words,
        read_eval_words(eval_set_path),
        read_conv_frequencies(conv_frequency_path),
        language=language,
    )


def build_typo_eval_set(
    words: Sequence[str],
    geometric_map: dict[int, tuple[int, ...]],
    alphabet: tuple[int, ...],
    *,
    seed: int = TYPO_EVAL_SEED,
) -> TypoEvalSet:
    """Build the mutated set from the substrate ``words``.

    One mutation per (word, class); classes are tried in tag order. The rendered rows are
    sorted by (original, class) — Python string order is code-point order, which the JVM
    mirror reproduces with an explicit code-point comparator.
    """
    rows: list[tuple[str, str, str]] = []
    for word in words:
        code_points = tuple(ord(character) for character in word)
        for class_name in CLASS_NAMES:
            stream = SplitMixStream(stream_seed(word, CLASS_TAGS[class_name], seed=seed))
            mutated = _MUTATORS[class_name](code_points, geometric_map, alphabet, stream)
            if mutated is None:
                continue
            rows.append((word, class_name, "".join(chr(cp) for cp in mutated)))
    if not rows:
        raise typo_pack.TypoPackError("no substrate word yielded a mutation")
    rows.sort(key=lambda row: (row[0], row[1]))
    counts = {name: 0 for name in CLASS_NAMES}
    for _, class_name, _ in rows:
        counts[class_name] += 1
    text = "".join(
        f"{original}\t{class_name}\t{mutated}\n" for original, class_name, mutated in rows
    )
    return TypoEvalSet(
        rows=tuple(rows),
        text=text,
        data=text.encode("utf-8"),
        class_counts=counts,
        substrate_count=len(words),
    )


def generate(
    eval_set_path: Path,
    dictionary_path: Path,
    layout_dir: Path,
    *,
    language: LanguageSpec = TATAR,
) -> TypoEvalSet:
    """Read the language's pinned inputs and build the mutated eval set."""
    dictionary_words = typo_pack.read_dictionary_words(
        dictionary_path,
        expected_asset_sha256=language.dictionary_asset_sha256,
        expected_raw_sha256=language.dictionary_raw_sha256,
        expected_entry_count=language.dictionary_entry_count,
    )
    eval_words = read_eval_words(eval_set_path)
    geometric_map = read_language_geometric_map(layout_dir, language)
    alphabet = read_language_alphabet(layout_dir, language)
    return build_typo_eval_set(
        substrate_words(dictionary_words, eval_words),
        geometric_map,
        alphabet,
        seed=language.seed,
    )


# --------------------------------------------------------------------------------------
# CLI.
# --------------------------------------------------------------------------------------
def create_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    build = commands.add_parser("build", help="generate the typo-mutated eval set")
    build.add_argument("--language", default=TATAR.tag, choices=sorted(LANGUAGES))
    build.add_argument("--eval-set", type=Path)
    build.add_argument("--dictionary", type=Path)
    build.add_argument("--layout-dir", type=Path)
    build.add_argument("--output", type=Path)
    safety = commands.add_parser("safety-set", help="generate the reviewed valid-OOV safety set")
    safety.add_argument("--language", default=TATAR.tag, choices=sorted(LANGUAGES))
    safety.add_argument("--eval-set", type=Path)
    safety.add_argument("--dictionary", type=Path)
    safety.add_argument("--conv-frequency", type=Path)
    safety.add_argument("--output", type=Path)
    return parser


def _print_json(value: object, stream: TextIO | None = None) -> None:
    if stream is None:
        stream = sys.stdout
    json.dump(value, stream, ensure_ascii=False, indent=2, sort_keys=True)
    stream.write("\n")


def main(argv: Sequence[str] | None = None) -> int:
    args = create_argument_parser().parse_args(argv)
    try:
        if args.command == "build":
            language = LANGUAGES[args.language]
            eval_set = args.eval_set if args.eval_set is not None else language.eval_set
            dictionary = (
                args.dictionary if args.dictionary is not None else language.dictionary
            )
            layout_dir = (
                args.layout_dir if args.layout_dir is not None else _RES_XML_DIR
            )
            output = args.output if args.output is not None else language.default_output
            if output is None:
                raise typo_pack.TypoPackError(
                    f"--output is required: the {language.tag} set has no default path"
                )
            result = generate(eval_set, dictionary, layout_dir, language=language)
            typo_pack.write_atomic(output, result.data)
            _print_json(
                {
                    "class_counts": result.class_counts,
                    "language": language.tag,
                    "rows": result.size,
                    "seed": language.seed,
                    "set_bytes": len(result.data),
                    "set_sha256": result.sha256,
                    "substrate_words": result.substrate_count,
                }
            )
        elif args.command == "safety-set":
            language = LANGUAGES[args.language]
            eval_set = args.eval_set if args.eval_set is not None else language.eval_set
            dictionary = (
                args.dictionary if args.dictionary is not None else language.dictionary
            )
            conv_frequency = (
                args.conv_frequency
                if args.conv_frequency is not None
                else language.conv_frequency
            )
            output = args.output if args.output is not None else language.safety_output
            result = generate_valid_oov_safety_set(
                eval_set, dictionary, conv_frequency, language=language
            )
            typo_pack.write_atomic(output, result.data)
            _print_json(
                {
                    "language": language.tag,
                    "rows": result.size,
                    "set_bytes": len(result.data),
                    "set_sha256": result.sha256,
                    "stratum_counts": result.stratum_counts,
                }
            )
        else:  # pragma: no cover
            raise AssertionError(args.command)
    except (typo_pack.TypoPackError, OSError, UnicodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
