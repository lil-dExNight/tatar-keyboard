#!/usr/bin/env python3
"""Единый вход пересборки ассетов: словари → таблицы биграмм → пины → проверка.

Исторический источник багов, который этот скрипт закрывает: пересборка словаря и
перепаковка таблиц биграмм были двумя независимыми ручными командами, и вторая половина
дважды забывалась — татарская таблица разошлась со словарём на 78 голов (закрыто в 1.9.4,
`docs/archive/bigrams/IMPERATIVE-HEADS.md`), русская — на 4 195 (открыто, число записано
там же и в KDoc `BigramArtifactSpec.RUSSIAN_BIGRAMS_V1`). Здесь это ОДНА команда, и забыть
вторую половину невозможно:

    python3 scripts/rebuild_assets.py --baseline <каталог с ассетами 1.8.4>

делает по порядку:

  0. (татарский, с 2026-09-20 — TT-SUGGESTIONS P2) стадию словоформ
     `build_admitted_wordforms`: основы — состав словаря до отсечки, кандидаты —
     `scripts/wordform_gen.py`, допуск — засвидетельствованность в Leipzig tt
     `*-words.txt` тех же двух корпусов, что обучают таблицу, плюс закоммиченный
     conv-freq-tt.tsv; частота = корпусное число;
  1. пересборку словарей через существующий entry point
     `scripts/dict_accept.py pack --write` (состав = ассет 1.8.4 + принятое приёмкой
     + допущенные словоформы у татарского, частоты = Leipzig + разговорные из
     `docs/archive/dictionary/dict-accept/conv-freq-*`; отсечка — `DictionaryAsset.top`;
     SHA-256 основы сверяется самим dict_accept, поверх пересобранного не соберётся);
  2. перепаковку таблиц биграмм через `scripts/bigram_asset_pack.py pack` с
     параметрами последних поставленных упаковок: татарская H = 10 132, K = 4,
     `--extra-heads scripts/bigram_extra_heads_tat.txt`; русская H = 10 000, K = 4
     (`docs/archive/bigrams/IMPERATIVE-HEADS.md` и `RUSSIAN-BIGRAMS.md`);
  3. пересчёт и атомарную запись пинов (размеры и SHA-256 сжатого и сырого, число
     записей/голов) в `DictionaryStorageContracts.kt` и `BigramStorageContracts.kt`;
  4. ту же проверку согласованности, что и `--check`.

`--only tatar|russian` ограничивает пересборку одной стороной: её словарём, её таблицей
и её пинами. Входы другой стороны не нужны (русские корпуса для `--only tatar` не
требуются), а её ассеты и пины обязаны остаться побайтно теми же — это проверяется
снимком SHA-256 до и после, а шаг 4 по-прежнему сверяет все четыре ассета.

Входы пересборки, которых в репозитории нет (и быть не должно — лицензии):

  * `--baseline` — каталог с двумя ассетами 1.8.4. Достаются из git (коммит релиза
    1.8.4); правильность гарантирует не имя коммита, а пины SHA-256 в dict_accept:
      git show <1.8.4>:app/src/main/assets/dictionaries/russian_top100k_v1.tdict.zlib > ...
      git show <1.8.4>:app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib > ...
  * `--corpus-dir` (по умолчанию `~/corpora-leipzig`) — Leipzig `*-sentences.txt`:
    tat_mixed_2015_1M, tat_web_2018_1M, rus_news_2022_1M, rus_news_2019_1M,
    rus_wikipedia_2021_1M, плюс разговорные входы обеих таблиц:
    `rus_conv_thinned60-sentences.txt` (с 2026-08-31, часть A; происхождение и рецепт
    сборки — docs/CORPUS-CONVERSATIONAL-RU.md) и `tt_conv_train90-sentences.txt`
    (тем же днём, часть B — docs/CORPUS-CONVERSATIONAL-TT.md). Нужны только таблицам
    биграмм; словарям корпус не нужен (разговорные частоты закоммичены в conv-freq-*.tsv
    ровно для этого). С P2 татарской стороне нужны ещё и `*-words.txt` двух татарских
    корпусов — частотный источник допуска словоформ.

Режим проверки (ничего не пересобирает, корпуса и baseline не нужны):

    python3 scripts/rebuild_assets.py --check [--allow-known-drift [ФАЙЛ]]

Сверяет каждый ассет с пинами в контрактах (пины ЧИТАЮТСЯ из Kotlin-файлов, а не
дублируются здесь) и каждую таблицу биграмм — с её словарём: головы обязаны быть
подмножеством словаря, а расхождение набора голов с сегодняшним топ-H по частоте считается
числом в обе стороны. Преемники сознательно НЕ проверяются: таблица хранит свои строки
сама, и преемник вне словаря — задокументированное состояние (`docs/archive/dictionary/
DICT-WIDEN.md`: 313 русских преемников), а не расхождение.

Известное расхождение голов фиксируется файлом `scripts/known_asset_drift.json` и
принимается только под `--allow-known-drift`, и только при ТОЧНОМ совпадении чисел:
расхождение, которое стало больше или меньше известного (в том числе исчезло — запись
устарела и должна быть убрана), проваливает проверку. Так CI с `--allow-known-drift`
зелёный на сегодняшнем дереве и красный на любом НОВОМ расхождении.

Коды выхода: 0 — всё согласовано (с учётом разрешённого известного расхождения);
1 — расхождение; 2 — входы или окружение (нет файлов, упал шаг пересборки, контракт
не разобрался). Только stdlib; записи атомарны (temp + replace), как у соседних скриптов.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence, TextIO

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

import bigram_asset_pack  # noqa: E402
import dict_accept  # noqa: E402
import dictionary_coverage as coverage  # noqa: E402
import dictionary_pack  # noqa: E402
import wordform_gen  # noqa: E402
from bigram_pack import select_heads  # noqa: E402

STORAGE_DIR = Path("app/src/main/java/rkr/simplekeyboard/inputmethod/latin/dictionary/storage")
DICT_CONTRACT = STORAGE_DIR / "DictionaryStorageContracts.kt"
BIGRAM_CONTRACT = STORAGE_DIR / "BigramStorageContracts.kt"

DEFAULT_KNOWN_DRIFT = Path("scripts/known_asset_drift.json")
DEFAULT_CORPUS_DIR = Path.home() / "corpora-leipzig"
DEFAULT_WORK_DIR = Path("build/rebuild_assets")


@dataclass(frozen=True)
class DictionaryAsset:
    tag: str  # тег dictionary_coverage: "tat" / "rus"
    spec: str  # имя константы в DictionaryStorageContracts.kt
    asset: str  # путь относительно корня репозитория
    top: int = 100_000  # размер отсечки состава при пересборке


@dataclass(frozen=True)
class BigramAsset:
    tag: str
    spec: str  # имя константы в BigramStorageContracts.kt
    asset: str
    dictionary: str  # tag словаря, с которым таблица поставляется
    heads: int
    successes_per_head: int
    extra_heads: str | None  # путь относительно корня, если есть
    train: tuple[str, ...]  # имена *-sentences.txt в каталоге корпусов


# Отсечка состава татарского словаря с 2026-09-20 (TT-SUGGESTIONS P2,
# docs/TT-SUGGESTIONS.md, раздел P2): выбрана замером — наибольшее N из
# {100 000, 110 000, 120 000}, при котором собранный с допущенными словоформами ассет
# влезает в бюджеты (сжатый ≤ 580 000 байт при потолке схемы 600 000; сырой
# ≤ 1 400 000). Замер 2026-09-20: 100 000 → 501 683 (формы не входят — граница выше
# их частот), 110 000 → 542 493 (входят 9 052 формы, ничего не вытеснено),
# 120 000 → 580 805 — за пределом. Меняется только вместе с новым замером.
TATAR_DICTIONARY_TOP = 110_000

DICTIONARIES = (
    DictionaryAsset(
        tag="tat",
        spec="TATAR_TOP100K_V1",
        asset="app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib",
        top=TATAR_DICTIONARY_TOP,
    ),
    DictionaryAsset(
        tag="rus",
        spec="RUSSIAN_TOP100K_V1",
        asset="app/src/main/assets/dictionaries/russian_top100k_v1.tdict.zlib",
    ),
)

# Параметры — ровно те, что записаны в отчётах последних упаковок и в KDoc констант:
# татарская — docs/archive/bigrams/IMPERATIVE-HEADS.md (H = 10 132 выведен из словаря,
# 13 повелений списком), русская — docs/archive/bigrams/RUSSIAN-BIGRAMS.md плюс
# BIGRAM-ADJACENCY.md (K: 6 → 4) плюс разговорный вход CORPUS-CONVERSATIONAL-RU.md
# (часть A, 2026-08-31). Меняются только вместе с решением о перевыборе,
# и тогда же правится этот файл.
BIGRAMS = (
    BigramAsset(
        tag="tat",
        spec="TATAR_BIGRAMS_V1",
        asset="app/src/main/assets/bigrams/tatar_bigrams_v1.tatbigr.zlib",
        dictionary="tat",
        heads=10_132,
        # K: 4 -> 3 (2026-09-23, ROADMAP-P4 T7, docs/ROADMAP-P4.md): измеренная доля
        # ранга-4 в покрытых eval-парах — 2,08 % (латентно +1,56 п.п. hit-rate), но
        # 4-ячейковая полоса — отдельный контрактный сабфаз; K=3 даёт -19 209 Б
        # (81 476 -> 62 267 при том же наборе голов, метрики тождественны).
        successes_per_head=3,
        # С 2026-09-23 (ROADMAP-P4 P5a, опция (b)) список расширен правилом EXPAND-1
        # (+3 102 разговорно-устоявшихся слова ниже отсечки; правило — в шапке файла).
        extra_heads="scripts/bigram_extra_heads_tat.txt",
        # С 2026-08-31 (разговорный корпус, часть B, docs/CORPUS-CONVERSATIONAL-TT.md)
        # обучение — два Leipzig + разговорный вход: дедуплицированные Tatoeba +
        # OpenSubtitles tt, строки с id % 10 != 1 (остаток — разговорный held-out).
        # Без прореживания: разговорная масса — 3,7 % письменной, резать нечего
        # (решение зафиксировано в DECISION-RULE-PRECOMMIT-TT до прогона). Файл
        # собирается research/corpus/make_conv_train.py и awk-фильтром из отчёта;
        # в --corpus-dir его кладут руками, без него пересборка падает fail-closed.
        train=(
            "tat_mixed_2015_1M-sentences.txt",
            "tat_web_2018_1M-sentences.txt",
            "tt_conv_train90-sentences.txt",
        ),
    ),
    BigramAsset(
        tag="rus",
        spec="RUSSIAN_BIGRAMS_V1",
        asset="app/src/main/assets/bigrams/russian_bigrams_v1.tatbigr.zlib",
        dictionary="rus",
        heads=10_000,
        successes_per_head=4,
        extra_heads=None,
        # С 2026-08-31 (разговорный корпус, часть A, docs/CORPUS-CONVERSATIONAL-RU.md)
        # обучение — три Leipzig + разговорный вход: дедуплицированные Tatoeba +
        # OpenSubtitles, прореженные 1/60 (строки с id % 60 == 0). Файл собирается
        # скриптом research/corpus/make_conv_train.py из корпусов research/corpus/
        # и прореживается awk-фильтром, записанными в отчёте; в --corpus-dir его
        # кладут руками, поэтому полная пересборка без него падает fail-closed.
        train=(
            "rus_news_2022_1M-sentences.txt",
            "rus_news_2019_1M-sentences.txt",
            "rus_wikipedia_2021_1M-sentences.txt",
            "rus_conv_thinned60-sentences.txt",
        ),
    ),
)


# --- стадия словоформ (TT-SUGGESTIONS P2, 2026-09-20) -----------------------------------------
#
# Татарский словарь собирается с допущенными порождёнными словоформами
# (scripts/wordform_gen.py). Допуск — засвидетельствованность в частотных источниках
# пайплайна: Leipzig *-words.txt тех же двух корпусов, что обучают татарскую таблицу
# биграмм, плюс закоммиченный conv-freq-tt.tsv. tat_news_2015_1M сознательно НЕ входит:
# это замороженный письменный held-out замеров (E5a), и подмешивать его в решения о
# поставляемых данных нельзя; у базового словаря 1.8.4 его частоты в составе есть
# (D1a предшествует тому разбиению), у допускаемых форм — нет, вклад записан в отчёт.
WORDFORM_EXCEPTIONS = Path("scripts/wordform_exceptions_tat.tsv")
WORDFORM_FREQUENCY_SOURCES = (
    "tat_mixed_2015_1M-words.txt",
    "tat_web_2018_1M-words.txt",
)


def build_admitted_wordforms(
    root: Path, baseline: Path, corpus_dir: Path, work_dir: Path
) -> Path:
    """Допущенные словоформы татарского словаря: word<TAB>freq TSV в work_dir.

    Основы — весь состав словаря ДО отсечки (поставляемые 1.8.4 ∪ принятые приёмкой),
    частоты у него не нужны. Кандидат допускается, если его суммарное число вхождений в
    источниках выше нуля; частота — это само число. Форма, уже стоящая в составе, —
    не операция (её частота не меняется). Ход детерминирован: основы отсортированы,
    запись атомарная, отчёт без полей времени. Сломанная таблица исключений или
    битый вход падают fail-closed (WordformError / MalformedRowError).
    """
    language = coverage.language_for("tat")
    shipped, _asset = dict_accept.load_baseline("tat", baseline)
    accepted = dict_accept.read_accepted("tat")
    stems = sorted(set(shipped) | set(accepted))

    frequencies: dict[str, int] = dictionary_pack.CheckedFrequencyCounter()
    for name in WORDFORM_FREQUENCY_SOURCES:
        path = corpus_dir / name
        with path.open("r", encoding="utf-8-sig", newline="") as stream:
            coverage.read_source(
                stream, str(path), frequencies,
                skip_malformed=False, alphabet=language.alphabet,
            )
    conversational = dict_accept.read_conv_freq("tat")
    exceptions = wordform_gen.load_exceptions(root / WORDFORM_EXCEPTIONS)

    composition = set(stems)
    admitted: dict[str, int] = {}
    stats: dict[str, object] = {
        "stems": 0,
        "stems_skipped_no_harmony": 0,
        "dual_harmony_stems": 0,
        # Счётчики generated/already_present/unattested — построчные: одна и та же форма,
        # порождённая двумя основами или двумя метками, считается на каждой строке.
        "generated_rows": 0,
        "rows_already_in_composition": 0,
        "rows_unattested": 0,
    }
    for stem in stems:
        variants = wordform_gen.harmony_variants(stem)
        if not variants:
            stats["stems_skipped_no_harmony"] += 1
            continue
        stats["stems"] += 1
        if len(variants) > 1:
            stats["dual_harmony_stems"] += 1
        for _label, form in wordform_gen.generate_all(stem, exceptions):
            if form == stem:
                continue  # голая основа (verb.imp.2sg) уже стоит в составе
            stats["generated_rows"] += 1
            if form in composition:
                stats["rows_already_in_composition"] += 1
                continue
            count = frequencies.get(form, 0) + conversational.get(form, 0)
            if count == 0:
                stats["rows_unattested"] += 1
                continue
            admitted[form] = count
    stats["admitted_forms"] = len(admitted)

    data = "".join(f"{form}\t{admitted[form]}\n" for form in sorted(admitted)).encode("utf-8")
    out = work_dir / "wordforms-admitted-tt.tsv"
    wordform_gen.write_atomic(out, data)
    report = {
        **stats,
        "frequency_sources": list(WORDFORM_FREQUENCY_SOURCES)
        + ["docs/archive/dictionary/dict-accept/conv-freq-tt.tsv"],
        "output": str(out),
        "output_bytes": len(data),
        "output_sha256": hashlib.sha256(data).hexdigest(),
    }
    wordform_gen.write_atomic(
        work_dir / "wordforms-admitted-tt.json",
        (json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8"),
    )
    print(
        f"стадия словоформ: основ {stats['stems']}, допущено форм "
        f"{stats['admitted_forms']} из {stats['generated_rows']} порождённых строк",
        file=sys.stderr,
    )
    return out


# --- пины: чтение и запись Kotlin-контрактов ------------------------------------------------


@dataclass(frozen=True)
class Pins:
    """Пять чисел, которыми контракт пинит ассет, плюс (для таблиц schema 3) raw SHA-256
    словаря, с которым таблица связана. count — записей словаря или голов."""

    compressed_size: int
    compressed_sha256: str
    raw_size: int
    raw_sha256: str
    count: int
    dictionary_raw_sha256: str = ""


class ContractError(ValueError):
    """Контракт не разобрался: структура Kotlin-файла отъехала от ожидаемой."""


def _spec_block(text: str, spec: str, kind: str) -> tuple[int, int]:
    """Позиции [start, end) тела `val <spec> = <kind>(...)` в тексте контракта.

    Блок кончается строкой закрывающей скобки на отступе объявления (8 пробелов) —
    так записаны все четыре спецификации. Не нашлось ровно одного блока — это не
    «пустой пин», а сломанное предположение о файле, и правильный ответ — упасть.
    """
    matches = list(
        re.finditer(rf"val {spec} = {kind}\(.*?\n        \)", text, re.DOTALL)
    )
    if len(matches) != 1:
        raise ContractError(f"{spec}: ожидался ровно один блок {kind}, найдено {len(matches)}")
    return matches[0].start(), matches[0].end()


def _read_number(block: str, field: str) -> int:
    match = re.search(rf"{field} = ([\d_]+),", block)
    if match is None:
        raise ContractError(f"поле {field} не найдено в блоке спецификации")
    return int(match.group(1).replace("_", ""))


def _read_sha(block: str, field: str) -> str:
    match = re.search(rf'{field} =\s*"([0-9a-f]{{64}})"', block)
    if match is None:
        raise ContractError(f"поле {field} не найдено в блоке спецификации")
    return match.group(1)


def read_pins(
    contract_path: Path, spec: str, kind: str, count_field: str, linked: bool = False
) -> Pins:
    text = contract_path.read_text(encoding="utf-8")
    start, end = _spec_block(text, spec, kind)
    block = text[start:end]
    return Pins(
        compressed_size=_read_number(block, "expectedCompressedSize"),
        compressed_sha256=_read_sha(block, "expectedCompressedSha256"),
        raw_size=_read_number(block, "expectedRawSize"),
        raw_sha256=_read_sha(block, "expectedRawSha256"),
        count=_read_number(block, count_field),
        dictionary_raw_sha256=(
            _read_sha(block, "expectedDictionaryRawSha256") if linked else ""
        ),
    )


def _replace_number(block: str, field: str, value: int) -> str:
    block, count = re.subn(
        rf"({field} = )[\d_]+(,)", rf"\g<1>{value:_}\g<2>", block, count=1
    )
    if count != 1:
        raise ContractError(f"поле {field} не заменено в блоке спецификации")
    return block


def _replace_sha(block: str, field: str, value: str) -> str:
    # Между `=` и строкой хеша в файле перевод строки с отступом; \s* в первой группе
    # сохраняет как есть — перестраивается только сам литерал.
    block, count = re.subn(
        rf'({field} =\s*)"[0-9a-f]{{64}}"(,)', rf'\g<1>"{value}"\g<2>', block, count=1
    )
    if count != 1:
        raise ContractError(f"поле {field} не заменено в блоке спецификации")
    return block


def _atomic_write_text(path: Path, text: str) -> None:
    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_text(text, encoding="utf-8", newline="\n")
    os.replace(temp, path)


def write_pins(
    contract_path: Path,
    updates: dict[str, Pins],
    kind: str,
    count_field: str,
) -> None:
    """Переписывает пины в блоках `updates` одним атомарным проходом.

    После записи файл перечитывается, и каждый блок обязан вернуть ровно те значения,
    которые писались, — «записал и поверил» здесь не считается записью.
    """
    text = contract_path.read_text(encoding="utf-8")
    for spec, pins in updates.items():
        start, end = _spec_block(text, spec, kind)
        block = text[start:end]
        block = _replace_number(block, "expectedCompressedSize", pins.compressed_size)
        block = _replace_sha(block, "expectedCompressedSha256", pins.compressed_sha256)
        block = _replace_number(block, "expectedRawSize", pins.raw_size)
        block = _replace_sha(block, "expectedRawSha256", pins.raw_sha256)
        if pins.dictionary_raw_sha256:
            block = _replace_sha(
                block, "expectedDictionaryRawSha256", pins.dictionary_raw_sha256
            )
        block = _replace_number(block, count_field, pins.count)
        text = text[:start] + block + text[end:]
    _atomic_write_text(contract_path, text)
    for spec, pins in updates.items():
        if read_pins(contract_path, spec, kind, count_field, linked=bool(pins.dictionary_raw_sha256)) != pins:
            raise ContractError(f"{spec}: после записи пины не совпали с записанными")


# --- измерение ассетов ----------------------------------------------------------------------


def measure_dictionary(asset_path: Path, tag: str) -> Pins:
    language = coverage.language_for(tag)
    asset = asset_path.read_bytes()
    parsed = dictionary_pack.validate_asset(asset, language=language)
    raw = parsed.raw
    return Pins(
        compressed_size=len(asset),
        compressed_sha256=hashlib.sha256(asset).hexdigest(),
        raw_size=len(raw),
        raw_sha256=hashlib.sha256(raw).hexdigest(),
        count=parsed.entry_count,
    )


def measure_bigram(asset_path: Path, dictionary_path: Path, tag: str) -> Pins:
    """Пины таблицы биграмм. Schema 3 (SIZE-2) валидируется только ВМЕСТЕ со словарём:
    головы и преемники — индексы в него, а пины включают его raw SHA-256 (связку из
    заголовка таблицы). Schema 2 читается для старых ассетов (golden/история)."""
    language = coverage.language_for(tag)
    asset = asset_path.read_bytes()
    raw = bigram_asset_pack.decompress(asset)
    schema_id = bigram_asset_pack.HEADER.unpack_from(raw)[1]
    if schema_id == bigram_asset_pack.SCHEMA_ID:
        parsed = bigram_asset_pack.validate_raw(raw)
        head_count = len(parsed.head_words)
        dictionary_raw_sha256 = ""
    elif schema_id == bigram_asset_pack.SCHEMA_ID_V3:
        parsed_dictionary = dictionary_pack.validate_asset(
            dictionary_path.read_bytes(), language=language
        )
        dictionary_raw_sha256 = hashlib.sha256(parsed_dictionary.raw).hexdigest()
        parsed = bigram_asset_pack.validate_raw_v3(
            raw, parsed_dictionary.words, bytes.fromhex(dictionary_raw_sha256)
        )
        head_count = len(parsed.head_words)
    else:
        raise ContractError(f"неизвестный schema id таблицы биграмм: {schema_id}")
    return Pins(
        compressed_size=len(asset),
        compressed_sha256=hashlib.sha256(asset).hexdigest(),
        raw_size=len(raw),
        raw_sha256=hashlib.sha256(raw).hexdigest(),
        count=head_count,
        dictionary_raw_sha256=dictionary_raw_sha256,
    )


# --- проверка согласованности ---------------------------------------------------------------

@dataclass(frozen=True)
class Drift:
    """Расхождение голов таблицы со словарём, числом в обе стороны.

    missing — слова сегодняшнего топа-H (плюс extra-heads), которых в таблице нет.
    Легитимный остаток — головы без единой пары в обучении: упаковщик их выбрасывает,
    а `--check` без корпуса отличить их от настоящего расхождения не может, поэтому
    судьбу ненулевых чисел решает только known_asset_drift.json, записанный человеком.
    unexpected — головы таблицы вне сегодняшнего топа-H и вне списка extra-heads.
    outside — головы, которых нет в словаре вообще: это не дрейф, а поломка,
    и никакой allowlist её не разрешает.
    """

    missing: int
    unexpected: int
    outside: int
    missing_examples: tuple[str, ...]
    unexpected_examples: tuple[str, ...]
    outside_examples: tuple[str, ...]


def bigram_drift(
    table_path: Path,
    dictionary_path: Path,
    language: coverage.Language,
    heads: int,
    extra_heads: Sequence[str],
) -> Drift:
    vocabulary, frequencies = bigram_asset_pack.read_shipped_vocabulary(
        dictionary_path, language
    )
    raw = bigram_asset_pack.decompress(table_path.read_bytes())
    schema_id = bigram_asset_pack.HEADER.unpack_from(raw)[1]
    if schema_id == bigram_asset_pack.SCHEMA_ID_V3:
        ordered_words = dictionary_pack.validate_asset(
            dictionary_path.read_bytes(), language=language
        ).words
        parsed = bigram_asset_pack.validate_raw_v3(raw, ordered_words)
    else:
        parsed = bigram_asset_pack.validate_raw(raw)
    actual = set(parsed.head_words)
    expected = set(select_heads(frequencies, heads)) | set(extra_heads)
    missing = sorted(expected - actual)
    unexpected = sorted(actual - expected)
    outside = sorted(actual - vocabulary)
    return Drift(
        missing=len(missing),
        unexpected=len(unexpected),
        outside=len(outside),
        missing_examples=tuple(missing[:10]),
        unexpected_examples=tuple(unexpected[:10]),
        outside_examples=tuple(outside[:10]),
    )


def _pin_problems(measured: Pins, pinned: Pins) -> list[str]:
    problems = []
    fields = ["compressed_size", "compressed_sha256", "raw_size", "raw_sha256", "count"]
    # Связка schema 3 со словарём: сравнивается, если хоть одна сторона её несёт.
    if measured.dictionary_raw_sha256 or pinned.dictionary_raw_sha256:
        fields.append("dictionary_raw_sha256")
    for field in fields:
        actual, expected = getattr(measured, field), getattr(pinned, field)
        if actual != expected:
            problems.append(f"{field}: в контракте {expected}, в ассете {actual}")
    return problems


def load_known_drift(path: Path) -> dict[str, dict[str, object]]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise SystemExit(f"{path}: ожидался JSON-объект наверху")
    for key, entry in data.items():
        if not isinstance(entry, dict) or not {
            "missing_top_heads",
            "unexpected_heads",
            "reason",
        } <= set(entry):
            raise SystemExit(
                f"{path}: запись {key!r} обязана содержать missing_top_heads, "
                "unexpected_heads и reason"
            )
    return data


def run_check(
    root: Path,
    known_drift_path: Path | None,
    stream: TextIO = sys.stdout,
) -> int:
    """Сверка ассетов с пинами и словарями. Ничего не пишет и не пересобирает.

    Коды выхода как у команды в целом (см. docstring модуля): 1 — расхождение,
    2 — входы или окружение, в том числе ContractError «контракт не разобрался»
    (C3 аудита 2026-09-02: раньше он улетал необработанным traceback'ом с кодом 1)."""
    dict_contract = root / DICT_CONTRACT
    bigram_contract = root / BIGRAM_CONTRACT
    for path in (dict_contract, bigram_contract):
        if not path.is_file():
            print(f"error: нет файла контракта {path}", file=sys.stderr)
            return 2

    known: dict[str, dict[str, object]] = {}
    if known_drift_path is not None:
        if not known_drift_path.is_file():
            print(f"error: нет файла известных расхождений {known_drift_path}", file=sys.stderr)
            return 2
        known = load_known_drift(known_drift_path)

    report: dict[str, object] = {"dictionaries": {}, "bigrams": {}}
    failed = False

    for dictionary in DICTIONARIES:
        asset_path = root / dictionary.asset
        entry: dict[str, object] = {"asset": dictionary.asset, "spec": dictionary.spec}
        if not asset_path.is_file():
            entry["verdict"] = "missing"
            failed = True
        else:
            try:
                pinned = read_pins(
                    dict_contract, dictionary.spec, "DictionaryArtifactSpec", "expectedEntryCount"
                )
            except ContractError as error:
                print(f"error: контракт не разобрался: {error}", file=sys.stderr)
                return 2
            try:
                problems = _pin_problems(
                    measure_dictionary(asset_path, dictionary.tag), pinned
                )
            except Exception as error:  # битый ассет — это вердикт проверки, а не краш
                problems = [f"ассет не читается: {error}"]
            entry["verdict"] = "mismatch" if problems else "ok"
            entry["problems"] = problems
            failed = failed or bool(problems)
        report["dictionaries"][dictionary.tag] = entry  # type: ignore[index]

    used_drift_keys: set[str] = set()
    for bigram in BIGRAMS:
        asset_path = root / bigram.asset
        asset_key = str(Path(bigram.asset).relative_to("app/src/main/assets"))
        entry: dict[str, object] = {"asset": bigram.asset, "spec": bigram.spec}
        dictionary_path = root / next(
            d.asset for d in DICTIONARIES if d.tag == bigram.dictionary
        )
        known_entry = known.get(asset_key)
        if known_entry is not None:
            used_drift_keys.add(asset_key)
        if not asset_path.is_file() or not dictionary_path.is_file():
            entry["verdict"] = "missing"
            failed = True
        else:
            try:
                pinned = read_pins(
                    bigram_contract, bigram.spec, "BigramArtifactSpec", "expectedHeadCount",
                    linked=True,
                )
            except ContractError as error:
                print(f"error: контракт не разобрался: {error}", file=sys.stderr)
                return 2
            try:
                problems = _pin_problems(
                    measure_bigram(asset_path, dictionary_path, bigram.dictionary), pinned
                )
                extra: list[str] = []
                if bigram.extra_heads is not None:
                    extra = bigram_asset_pack.read_extra_heads(
                        root / bigram.extra_heads,
                        bigram_asset_pack.read_shipped_vocabulary(
                            dictionary_path, coverage.language_for(bigram.dictionary)
                        )[0],
                    )
                drift = bigram_drift(
                    asset_path,
                    dictionary_path,
                    coverage.language_for(bigram.dictionary),
                    bigram.heads,
                    extra,
                )
            except Exception as error:  # битый ассет — вердикт проверки, а не краш
                entry["verdict"] = "mismatch"
                entry["problems"] = [f"ассет не читается: {error}"]
                failed = True
                report["bigrams"][bigram.tag] = entry  # type: ignore[index]
                continue
            entry["drift"] = {
                "missing_top_heads": drift.missing,
                "unexpected_heads": drift.unexpected,
                "heads_outside_dictionary": drift.outside,
                "missing_examples": list(drift.missing_examples),
                "unexpected_examples": list(drift.unexpected_examples),
            }
            if drift.outside:
                problems.append(
                    f"{drift.outside} голов вне словаря: "
                    + ", ".join(drift.outside_examples)
                )
            entry["problems"] = problems
            failed = failed or bool(problems)

            if drift.missing == 0 and drift.unexpected == 0:
                if known_entry is not None:
                    entry["verdict"] = "stale-known-drift"
                    entry["problems"] = problems + [
                        "расхождения больше нет — запись в known_asset_drift.json "
                        "устарела, уберите её"
                    ]
                    failed = True
                else:
                    entry["verdict"] = "ok" if not problems else "mismatch"
            elif known_entry is not None and known_entry[
                "missing_top_heads"
            ] == drift.missing and known_entry["unexpected_heads"] == drift.unexpected:
                entry["verdict"] = "known-drift"
                entry["known_drift_reason"] = known_entry["reason"]
            else:
                entry["verdict"] = "drift"
                hint = (
                    "не совпадает с known_asset_drift.json"
                    if known_entry is not None
                    else "нет записи в known_asset_drift.json"
                    if known_drift_path is not None
                    else "перезапустите с --allow-known-drift, если расхождение известно"
                )
                entry["problems"] = problems + [
                    f"головы разошлись со словарём: нет в таблице {drift.missing}, "
                    f"лишних {drift.unexpected} ({hint})"
                ]
                failed = True
        report["bigrams"][bigram.tag] = entry  # type: ignore[index]

    for key in sorted(set(known) - used_drift_keys):
        # Запись про ассет, которого больше нет в реестре, — тоже устаревшая.
        print(f"error: {known_drift_path}: запись {key!r} не относится ни к одному ассету",
              file=sys.stderr)
        failed = True

    report["ok"] = not failed
    report["known_drift_file"] = str(known_drift_path) if known_drift_path else None
    json.dump(report, stream, ensure_ascii=False, indent=2, sort_keys=True)
    stream.write("\n")

    for section in ("dictionaries", "bigrams"):
        for tag, entry in sorted(report[section].items()):  # type: ignore[union-attr]
            line = f"{section}/{tag}: {entry['verdict']}"
            if entry["verdict"] == "known-drift":
                drift = entry["drift"]
                line += (f" (нет в таблице {drift['missing_top_heads']}, "
                         f"лишних {drift['unexpected_heads']})")
            print(line, file=sys.stderr)
    return 1 if failed else 0


# --- пересборка -----------------------------------------------------------------------------


def bigram_pack_argv(root: Path, corpus_dir: Path, work_dir: Path, bigram: BigramAsset) -> list[str]:
    """Командная строка перепаковки одной таблицы — отдельной функцией, чтобы тест её видел."""
    argv = [
        sys.executable,
        str(root / "scripts/bigram_asset_pack.py"),
        "pack",
        "--train",
        *[str(corpus_dir / name) for name in bigram.train],
        "--asset",
        str(root / next(d.asset for d in DICTIONARIES if d.tag == bigram.dictionary)),
        "--heads",
        str(bigram.heads),
        "--successes-per-head",
        str(bigram.successes_per_head),
        "--schema",
        "3",
        "--out-raw",
        str(work_dir / Path(bigram.asset).name.removesuffix(".zlib")),
        "--out-compressed",
        str(root / bigram.asset),
        "--report",
        str(work_dir / f"{bigram.tag}-pack.generated.json"),
        "--language",
        bigram.tag,
    ]
    if bigram.extra_heads is not None:
        argv += ["--extra-heads", str(root / bigram.extra_heads)]
    return argv


def dict_accept_argv(
    root: Path,
    baseline: Path,
    work_dir: Path,
    dictionary: DictionaryAsset,
    extra_entries: Path | None,
) -> list[str]:
    """Командная строка пересборки одного словаря — отдельной функцией, чтобы тест её видел."""
    argv = [
        sys.executable,
        str(root / "scripts/dict_accept.py"),
        "--json-out",
        str(work_dir / f"dict-accept-pack-{dictionary.tag}.json"),
        "pack",
        "--baseline",
        str(baseline),
        "--write",
        "--only",
        dictionary.tag,
        "--top",
        str(dictionary.top),
    ]
    if extra_entries is not None:
        argv += ["--extra-entries", str(extra_entries)]
    return argv


def _run_step(argv: list[str], cwd: Path) -> None:
    print("+ " + " ".join(argv[1:]), file=sys.stderr)
    completed = subprocess.run(argv, cwd=cwd, check=False)
    if completed.returncode != 0:
        raise SystemExit(f"шаг пересборки упал с кодом {completed.returncode}: {argv[1]}")


def _side_snapshot(root: Path, tags: frozenset[str]) -> dict[str, object]:
    """SHA-256 ассетов и пины контрактов языков `tags` — опора гарантии `--only`.

    Снимок берётся ДО первого шага пересборки и сверяется после записи пинов: в режиме
    `--only` сторона, которую не выбрали, обязана остаться побайтно той же — и файлами,
    и блоками контракта.
    """
    snapshot: dict[str, object] = {}
    for dictionary in DICTIONARIES:
        if dictionary.tag not in tags:
            continue
        asset = root / dictionary.asset
        snapshot[f"asset:{dictionary.asset}"] = (
            hashlib.sha256(asset.read_bytes()).hexdigest() if asset.is_file() else None
        )
        snapshot[f"pins:{dictionary.spec}"] = read_pins(
            root / DICT_CONTRACT, dictionary.spec, "DictionaryArtifactSpec",
            "expectedEntryCount",
        )
    for bigram in BIGRAMS:
        if bigram.tag not in tags:
            continue
        asset = root / bigram.asset
        snapshot[f"asset:{bigram.asset}"] = (
            hashlib.sha256(asset.read_bytes()).hexdigest() if asset.is_file() else None
        )
        snapshot[f"pins:{bigram.spec}"] = read_pins(
            root / BIGRAM_CONTRACT, bigram.spec, "BigramArtifactSpec",
            "expectedHeadCount", linked=True,
        )
    return snapshot


def run_rebuild(
    root: Path,
    baseline: Path,
    corpus_dir: Path,
    work_dir: Path,
    known_drift_path: Path | None,
    only: str | None = None,
    stream: TextIO = sys.stdout,
) -> int:
    # `--only tatar|russian` пересобирает одну сторону: её словарь, её таблицу биграмм
    # и её пины. Другая сторона не требует своих входов (русские корпуса для `--only
    # tatar` не нужны) и обязана остаться побайтно той же — проверяется снимком до/после.
    selected = frozenset(
        ("tat", "rus") if only is None else ({"tatar": "tat", "russian": "rus"}[only],)
    )
    untouched = _side_snapshot(
        root, frozenset(d.tag for d in DICTIONARIES) - selected
    )

    # Сначала собираются ВСЕ недостающие входы выбранных языков: падать на третьем часу
    # работы из-за файла, которого не было с самого начала, недопустимо.
    missing = []
    for dictionary in DICTIONARIES:
        if dictionary.tag not in selected:
            continue
        name = Path(dictionary.asset).name
        if not (baseline / name).is_file():
            missing.append(str(baseline / name))
    for bigram in BIGRAMS:
        if bigram.tag not in selected:
            continue
        for name in bigram.train:
            if not (corpus_dir / name).is_file():
                missing.append(str(corpus_dir / name))
        if bigram.extra_heads is not None and not (root / bigram.extra_heads).is_file():
            missing.append(str(root / bigram.extra_heads))
    if "tat" in selected:
        for name in WORDFORM_FREQUENCY_SOURCES:
            if not (corpus_dir / name).is_file():
                missing.append(str(corpus_dir / name))
        if not (root / WORDFORM_EXCEPTIONS).is_file():
            missing.append(str(root / WORDFORM_EXCEPTIONS))
    if missing:
        print("error: не хватает входов пересборки:", file=sys.stderr)
        for path in missing:
            print(f"  {path}", file=sys.stderr)
        print("происхождение входов — в docstring скрипта", file=sys.stderr)
        return 2

    work_dir.mkdir(parents=True, exist_ok=True)

    # 1. Словари выбранных языков. dict_accept сам сверяет baseline по SHA-256 и падает,
    # если ему подсунули уже пересобранный ассет. Татарский перед сборкой проходит
    # стадию словоформ (P2): допущенные формы ложатся в --extra-entries.
    wordforms: Path | None = None
    if "tat" in selected:
        wordforms = build_admitted_wordforms(root, baseline, corpus_dir, work_dir)
    for dictionary in DICTIONARIES:
        if dictionary.tag not in selected:
            continue
        _run_step(
            dict_accept_argv(
                root, baseline, work_dir, dictionary,
                wordforms if dictionary.tag == "tat" else None,
            ),
            cwd=root,
        )

    # 2. Таблицы биграмм выбранных языков — от свежих словарей шага 1.
    for bigram in BIGRAMS:
        if bigram.tag not in selected:
            continue
        _run_step(bigram_pack_argv(root, corpus_dir, work_dir, bigram), cwd=root)

    # 3. Пины выбранных ассетов, одной операцией на файл контракта.
    dict_updates = {
        d.spec: measure_dictionary(root / d.asset, d.tag)
        for d in DICTIONARIES
        if d.tag in selected
    }
    write_pins(
        root / DICT_CONTRACT, dict_updates, "DictionaryArtifactSpec", "expectedEntryCount"
    )
    bigram_updates = {
        b.spec: measure_bigram(
            root / b.asset,
            root / next(d.asset for d in DICTIONARIES if d.tag == b.dictionary),
            b.dictionary,
        )
        for b in BIGRAMS
        if b.tag in selected
    }
    write_pins(
        root / BIGRAM_CONTRACT, bigram_updates, "BigramArtifactSpec", "expectedHeadCount"
    )
    print("пины переписаны в обоих контрактах", file=sys.stderr)

    # 3b. В режиме --only невыбранная сторона обязана остаться побайтно той же.
    if only is not None:
        after = _side_snapshot(
            root, frozenset(d.tag for d in DICTIONARIES) - selected
        )
        moved = [key for key, before in untouched.items() if after[key] != before]
        if moved:
            print(
                f"error: --only {only}: невыбранная сторона изменилась:",
                file=sys.stderr,
            )
            for key in moved:
                print(f"  {key}", file=sys.stderr)
            return 2

    # 4. Проверка результата той же процедурой, что работает в --check; сверяются
    # все четыре ассета, в том числе нетронутая сторона.
    result = run_check(root, known_drift_path, stream)
    print(
        "дальше руками: прогнать гейты (JVM + python + lintRelease + check-no-internet), "
        "обновить scripts/known_asset_drift.json, если расхождение голов изменилось, "
        "и пересобрать наборы опечаток (scripts/typo_pack.py, см. DICT-WIDEN «Воспроизведение»)",
        file=sys.stderr,
    )
    return result


def create_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--check",
        action="store_true",
        help="только проверка согласованности: ничего не пересобирает и не пишет",
    )
    parser.add_argument(
        "--only",
        choices=("tatar", "russian"),
        default=None,
        help="пересобрать только одну сторону: её словарь (у татарской — со стадией "
        "словоформ), её таблицу биграмм и её пины; входы другой стороны не нужны, а её "
        "ассеты и пины обязаны остаться побайтно теми же (снимок SHA-256 до и после). "
        "Финальная проверка сверяет все четыре ассета. С --check не совместимо",
    )
    parser.add_argument(
        "--allow-known-drift",
        nargs="?",
        const=str(DEFAULT_KNOWN_DRIFT),
        default=None,
        metavar="ФАЙЛ",
        help="принять расхождения голов, ТОЧНО совпадающие с файлом известных "
        "(по умолчанию %(const)s); другое число или устаревшая запись — провал",
    )
    parser.add_argument(
        "--baseline",
        type=Path,
        help="каталог с ассетами 1.8.4 — обязателен для пересборки",
    )
    parser.add_argument(
        "--corpus-dir",
        type=Path,
        default=DEFAULT_CORPUS_DIR,
        help="каталог с Leipzig *-sentences.txt (по умолчанию %(default)s)",
    )
    parser.add_argument(
        "--work-dir",
        type=Path,
        default=None,
        help="куда класть сырые таблицы и отчёты (по умолчанию <root>/build/rebuild_assets)",
    )
    parser.add_argument(
        "--root",
        type=Path,
        default=ROOT,
        help="корень дерева (по умолчанию — репозиторий скрипта; для тестов)",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = create_argument_parser().parse_args(argv)
    root = args.root
    known_drift = (
        Path(args.allow_known_drift)
        if args.allow_known_drift is not None
        else None
    )
    if known_drift is not None and not known_drift.is_absolute():
        known_drift = root / known_drift
    if args.check:
        if args.only is not None:
            print("error: --only относится к пересборке; --check всегда сверяет все "
                  "четыре ассета", file=sys.stderr)
            return 2
        return run_check(root, known_drift)
    if args.baseline is None:
        print("error: для пересборки нужен --baseline (или запустите --check)",
              file=sys.stderr)
        return 2
    work_dir = args.work_dir if args.work_dir is not None else root / DEFAULT_WORK_DIR
    try:
        return run_rebuild(
            root, args.baseline, args.corpus_dir, work_dir, known_drift, only=args.only
        )
    except ContractError as error:
        print(f"error: контракт не разобрался: {error}", file=sys.stderr)
        return 2
    except wordform_gen.WordformError as error:
        print(f"error: стадия словоформ: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
