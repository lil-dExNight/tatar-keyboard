"""Reusable text normalization and conservative quality filters for Tatar corpora."""
from __future__ import annotations

import html
import re
import sys
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import dictionary_coverage as coverage  # noqa: E402
import dictionary_pack  # noqa: E402
import evaluation_splits  # noqa: E402

WORD_RE = re.compile(r"[^\W\d_]+(?:[-’'][^\W\d_]+)*", re.UNICODE)
SENTENCE_BREAK_RE = re.compile(r"(?<=[.!?…])\s+(?=[^\W\d_«\"'—-])", re.UNICODE)
TATAR_SPECIFIC = frozenset("әөүҗңһ")
BASHKIR_ONLY = frozenset("ҙҡҫ")
KAZAKH_ONLY = frozenset("құі")
CHUVASH_ONLY = frozenset("ӑӗҫӳ")
OTHER_CYRILLIC = frozenset("іїєґў")
CHAT_TRANSLATION = str.maketrans({"ә": "э", "ө": "о", "ү": "у", "җ": "ж", "ң": "н", "һ": "х"})

TATAR_FUNCTION_WORDS = frozenset(
    "һәм белән өчен түгел бик булып тора иде дип шул моның аның без сез алар мин син ул бу "
    "менә хәзер яхшы кирәк бар юк тагын ничек кайда кайчан белә эшли яши укы эшләп килә китә "
    "ала бирә бирәм бирәбез итәм итә итәбез итәсез буларак булса булган вакытта соң алдан "
    "арасында турында хакында шулай шуңа монда анда моннан аннан нигә нәрсә бер ике өч дүрт биш "
    "хэм белэн очен тугел кирэк ничэгэ монда анда монын анын берүзем берүзе үзем үзе үзебез "
    "ай аларга минем моны болар дус хайван кеше ил авыл татар тел эш уен су юл ел көн баш кул "
    "йорт урта зур кече яңа иске ак кара тиз ерак якын белем тормыш уку язу сүз ди булу".split()
)
RUSSIAN_FUNCTION_WORDS = frozenset(
    "и в не на что он она они мы вы это как для есть был была были быть по из у о от до при все "
    "его ее их с со или но же только очень после через который которая которые этого этот эта "
    "если уже так здесь там где когда кто нет привет мир дом работа сдаю продаю квартира "
    "комната цена рублей телефон купить продать требуется можно более менее между перед над под".split()
)
TATAR_LATIN_WORDS = frozenset(
    "häm belän öçen tügel bik bulıp tora ide dip şul monıñ anıñ bez sez alar min sin ul bu "
    "xäzer yaxşı kiräk bar yuq tağın niçek qayda qayçan belä eşli yäşi uqı eşläp kilä kitä "
    "ala birä biräm itäm itä itäbez itäsez bularak bulsa bulgan waqıtta soñ aldan arasında "
    "turynda xaqında şulay şuña monda anda monnan annan nigä närsä ber ike öç dürt biş".split()
)
CHAT_OVERRIDES = frozenset(
    "рэхмэт белэн хэм кирэк сэлам очен куп жыр хэзер тугел ничек эштэ эшлим миннэн синнэн "
    "момкин йорэк хэбэр курэ узенен нэрсэ мэктэп мохим тубэн бэйрэм кубрэк узэк кондэ эйтэ "
    "сузлэр кунел дэулэт шэхэр узгэрту элбэттэ мэскэу мэсэлэн шэхэре морэжэгать искэрмэлэр "
    "олкэсендэ курсэтэ жину килэчэктэ шэхэрендэ монэсэбэтлэр житди пенсиядэ сойлэш дурт "
    "остенлеге".split()
)
CHAT_REAL_VARIANTS = frozenset({"монда"})
TATAR_STRONG_SUFFIXES = (
    "лар", "ләр", "лык", "лек", "сыз", "сез", "кан", "кән", "ган", "гән",
    "чы", "че", "мы", "ме", "гыз", "гез", "ып", "еп", "дан", "дән",
)
TATAR_WEAK_SUFFIXES = ("лы", "ле", "ны", "не", "да", "дә", "де", "те", "ка", "кә", "га", "гә", "п")
BOILERPLATE_PATTERNS = (
    ("cookie", re.compile(r"\b(?:cookies?|cookie policy|куки|печень[ея])\b", re.I)),
    ("share", re.compile(r"\b(?:share|поделиться|поделитесь|facebook|twitter|вконтакте|одноклассники)\b", re.I)),
    ("subscription", re.compile(r"(?:читайте нас|подписывайтесь|подписаться на|subscribe|read more|читать далее)", re.I)),
    ("imprint", re.compile(r"(?:copyright|все права защищены|импринт|imprint|©|реклама|advertisement|app store|google play)", re.I)),
    ("navigation", re.compile(r"^(?:menu|меню|навигация|поиск|search|login|войти|загрузка|loading|home|главная)$", re.I)),
    ("navigation", re.compile(r"(?:перейти к содержимому|похожие (?:новости|статьи|материалы)|ещ[её] по теме|related articles)", re.I)),
    ("wiki_structure", re.compile(r"(?:\[үзгәртү\]|\{\||\|\}|^(?:Искәрмәләр|Эчтәлек|Чыганаклар|Тышкы сылтамалар)$)", re.I)),
    ("wiki_structure", re.compile(r"^(?:[a-z-]{2,12}:)?[^\s]{1,40}\s*\([^)]*\)\s*$", re.I)),
)
MT_RE = re.compile(
    r"(?:machine[- ]translat|translated by (?:google|yandex)|google translate|yandex translate|"
    r"машинн(?:ый|ого) перевод|автоматическ(?:ий|ого) перевод|машиналы тәрҗемә|автоматик тәрҗемә)",
    re.I,
)
SEO_RE = re.compile(
    r"(?:\bcasino\b|\bviagra\b|\bbuy cheap\b|\bfree spins?\b|онлайн казино|купить дешево|"
    r"порно|\bсекс\b|эротическ|секс знакомств|взрослый чат|заработок без вложений|"
    r"криптовалют(?:а|ы|ный)|бинарные опционы)",
    re.I,
)
URL_RE = re.compile(r"(?:https?://|www\.)\S+", re.I)
MIXED_SCRIPT_RE = re.compile(r"[A-Za-z][Ѐ-ӿ]|[Ѐ-ӿ][A-Za-z]")
MOJIBAKE_RE = re.compile(r"(?:Ð.|Ñ.|Р[ђћќ]|С[ђћќ]|вЂ|аЂ)")

_CHAT_VARIANTS_CACHE: tuple[frozenset[str], frozenset[str], dict[str, str]] | None = None


def raw_tokens(text: str) -> list[str]:
    """Return Unicode alphabetic tokens, retaining internal apostrophes and hyphens."""
    return WORD_RE.findall(text)


def normalize_line(text: str, *, preserve_replacement: bool = False) -> str:
    """Normalize one line without changing its ordinary letters or punctuation."""
    text = html.unescape(text).replace("\u00ad", "")
    if not preserve_replacement:
        text = text.replace("\ufffd", " ")
    text = "".join(" " if unicodedata.category(char) in {"Cc", "Cf", "Cs"} else char for char in text)
    return " ".join(unicodedata.normalize("NFC", text).split())


def extract_lines(text: str) -> list[str]:
    """Split source text at physical lines and clear sentence boundaries."""
    lines = []
    for physical in text.replace("\r\n", "\n").replace("\r", "\n").split("\n"):
        for sentence in SENTENCE_BREAK_RE.split(physical):
            normalized = normalize_line(sentence, preserve_replacement=True)
            if normalized:
                lines.append(normalized)
    return lines


def sentence_key(text: str) -> str:
    """Return the evaluation-split normalized Tatar sentence key."""
    return evaluation_splits.normalize_sentence(normalize_line(text), coverage.TATAR_ALPHABET)


def _script_counts(tokens: list[str]) -> tuple[int, int]:
    cyrillic = latin = 0
    for token in tokens:
        chars = set(token.lower())
        if any("\u0400" <= char <= "\u04ff" for char in chars):
            cyrillic += 1
        if any("a" <= char <= "z" or char in "äöüñçşğıə" for char in chars):
            latin += 1
    return cyrillic, latin


def _tatar_suffix_score(tokens: list[str]) -> int:
    strong = sum(token.endswith(TATAR_STRONG_SUFFIXES) for token in tokens if len(token) > 4)
    weak = sum(token.endswith(TATAR_WEAK_SUFFIXES) for token in tokens if len(token) > 4)
    return strong + weak


def classify_language(
    text: str,
    tatar_vocabulary: frozenset[str] | None = None,
    russian_vocabulary: frozenset[str] | None = None,
) -> str:
    """Classify script, grammatical markers and optional dictionary evidence."""
    tokens = [token.lower().strip("-’'") for token in raw_tokens(text)]
    tokens = [token for token in tokens if token]
    if not tokens:
        return "empty"
    cyrillic, latin = _script_counts(tokens)
    if latin and not cyrillic:
        score = sum(token in TATAR_LATIN_WORDS for token in tokens)
        letters = set("".join(tokens))
        return "tatar_latin" if score >= 1 or "ñ" in letters or "ə" in letters else "other"
    letters = set("".join(tokens))
    if letters & CHUVASH_ONLY:
        return "chuvash"
    if letters & KAZAKH_ONLY and (letters & {"қ", "ұ"} or letters & TATAR_SPECIFIC):
        return "kazakh"
    if letters & OTHER_CYRILLIC:
        return "other"
    if letters & BASHKIR_ONLY:
        return "bashkir"

    tatar_functions = sum(token in TATAR_FUNCTION_WORDS for token in tokens)
    russian_functions = sum(token in RUSSIAN_FUNCTION_WORDS for token in tokens)
    tatar_letters = len(letters & TATAR_SPECIFIC)
    if "ғ" in letters and not letters & {"қ", "ұ", "і"}:
        return "bashkir"
    if latin and cyrillic and latin >= 2:
        return "mixed"
    if tatar_letters and not russian_functions:
        return "tatar"
    if tatar_letters >= 2 and (tatar_functions or tatar_letters >= 2) and russian_functions <= 1:
        return "tatar"
    if russian_functions:
        if tatar_functions or tatar_letters:
            return "mixed"
        return "russian"
    if tatar_functions and (tatar_vocabulary is None or russian_vocabulary is None):
        return "tatar"

    suffix_score = _tatar_suffix_score(tokens)
    if tatar_vocabulary is not None and russian_vocabulary is not None:
        tatar_hits = sum(token in tatar_vocabulary for token in tokens)
        russian_hits = sum(token in russian_vocabulary for token in tokens)
        tatar_only = sum(
            token in tatar_vocabulary and token not in russian_vocabulary for token in tokens
        )
        russian_only = sum(
            token in russian_vocabulary and token not in tatar_vocabulary for token in tokens
        )
        if tatar_functions and not russian_only:
            return "tatar"
        if tatar_only and not russian_only:
            return "tatar"
        if russian_only:
            return "russian"
        if tatar_hits >= 2 and russian_hits <= 1:
            return "tatar"
        if suffix_score and tatar_hits:
            return "tatar"
        return "other"
    if suffix_score:
        return "tatar"
    return "other"


def boilerplate_reason(text: str) -> str | None:
    """Return a line-level site-chrome or structural-noise reason."""
    line = normalize_line(text)
    if not line:
        return "empty"
    if len(line) > 2000 and line.count("http") >= 3:
        return "link_list"
    for reason, pattern in BOILERPLATE_PATTERNS:
        if pattern.search(line):
            return reason
    if line.startswith(("|", "{|", "|}")) or line.count("[") + line.count("]") >= 8:
        return "wiki_structure"
    return None


def quality_reason(text: str) -> str | None:
    """Return only explicit MT, strong SEO-spam, or visible encoding evidence."""
    if MT_RE.search(text):
        return "machine_translation"
    if (
        text.count("|") >= 6
        and re.search(r"[A-Za-z]{3}", text)
        and not TATAR_SPECIFIC.isdisjoint(text.lower())
    ):
        return "machine_translation"
    urls = len(URL_RE.findall(text))
    seo = len(SEO_RE.findall(text))
    if seo >= 2 or (seo and urls):
        return "seo_spam"
    replacement = text.count("\ufffd")
    if replacement >= 2 or (replacement and replacement / max(1, len(text)) > 0.005):
        return "encoding"
    if MIXED_SCRIPT_RE.search(text) or MOJIBAKE_RE.search(text):
        return "encoding"
    tokens = raw_tokens(text)
    if any(len(token) > 64 for token in tokens):
        return "encoding"
    return None


def load_dictionary_vocabularies(root: Path) -> tuple[frozenset[str], frozenset[str]]:
    """Load the bundled Tatar and Russian word sets without modifying them."""
    directory = root / "app/src/main/assets/dictionaries"

    def load(name: str, language: coverage.Language) -> frozenset[str]:
        asset = (directory / name).read_bytes()
        parsed = dictionary_pack.validate_raw(
            dictionary_pack.decompress_asset(asset, language), language=language
        )
        return frozenset(parsed.words)

    return (
        load("tatar_top100k_v1.tdict.zlib", coverage.TATAR),
        load("russian_top100k_v1.tdict.zlib", coverage.RUSSIAN),
    )


def _chat_variants(tatar_words: frozenset[str], russian_words: frozenset[str]) -> dict[str, str]:
    global _CHAT_VARIANTS_CACHE
    if (
        _CHAT_VARIANTS_CACHE
        and _CHAT_VARIANTS_CACHE[0] is tatar_words
        and _CHAT_VARIANTS_CACHE[1] is russian_words
    ):
        return _CHAT_VARIANTS_CACHE[2]
    candidates: dict[str, set[str]] = {}
    for word in tatar_words:
        if not letters_present(word, TATAR_SPECIFIC):
            continue
        variant = word.translate(CHAT_TRANSLATION)
        if len(variant) >= 3 and variant != word:
            candidates.setdefault(variant, set()).add(word)
    variants = {}
    for variant, standard_forms in candidates.items():
        if len(standard_forms) != 1:
            continue
        if variant in russian_words and variant not in CHAT_OVERRIDES:
            continue
        if variant in tatar_words and variant not in CHAT_OVERRIDES:
            continue
        if variant in CHAT_REAL_VARIANTS:
            continue
        variants[variant] = next(iter(standard_forms))
    _CHAT_VARIANTS_CACHE = (tatar_words, russian_words, variants)
    return variants


def letters_present(text: str, letters: frozenset[str]) -> bool:
    """Return whether text contains any member of letters."""
    return not letters.isdisjoint(text)


def chat_spelling_map(
    text: str, tatar_words: frozenset[str], russian_words: frozenset[str]
) -> dict[str, str]:
    """Map unambiguous Russian-letter variants to their standard Tatar forms."""
    variants = _chat_variants(tatar_words, russian_words)
    found = {
        token.lower()
        for token in raw_tokens(text)
        if token.lower() in variants
    }
    return {variant: variants[variant] for variant in sorted(found)}


def chat_spelling_words(
    text: str, tatar_words: frozenset[str], russian_words: frozenset[str]
) -> tuple[str, ...]:
    """Return unambiguous Russian-letter variants of standard Tatar words."""
    return tuple(chat_spelling_map(text, tatar_words, russian_words))
