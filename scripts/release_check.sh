#!/usr/bin/env bash
# DEV-PLAN п.6: релизный автомат — механическая половина docs/PUBLISH-CHECKLIST.md
# одной командой. Прогоняет гейты репозитория и артефактные проверки на кандидате,
# каждая с явным PASS/FAIL, итог — машинным блоком и ненулевым кодом выхода на
# любом FAIL. Fail-closed: любая непредусмотренная ошибка (нет aapt2, битый APK,
# отсутствующий контракт) — тоже ненулевой выход.
#
# Запуск из корня репозитория:
#   bash scripts/release_check.sh [--quick|--full] [путь-к-apk]
#
# Режимы:
#   (по умолчанию)  артефакт уже собран; гейты гоняются, сборка не пересобирается;
#   --quick         только артефактные проверки (гейты gradle/python помечаются SKIP);
#   --full          сначала ./gradlew clean assembleRelease, затем всё остальное
#                   (проверяется свежесобранный app/build/outputs/apk/release/*.apk).
#
# APK по умолчанию — последний по mtime app/build/outputs/apk/release/*.apk.
# Скрипт ничего не меняет в репозитории, кроме build/ (логи — build/release_check/,
# при --full — и сама пересборка).
set -euo pipefail

# --- константы релизного инварианта ---------------------------------------------------------

# Потолок размера APK (3 МБ), побайтно — AGENTS.md, «Бюджеты».
APK_SIZE_LIMIT=3145728

# SHA-256 релизного сертификата (CN=Tatar Keyboard), один и тот же для всей линейки
# релизов с 2026-08-18; зафиксирован в docs/APK-AUDIT-1.9.5.md, раздел «Подпись».
RELEASE_CERT_SHA256="98ca6febfed6c146d81c1fdcfe52c79acf7aa926a1033d98b844a59803ec42ad"

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd -- "$SCRIPT_DIR/.." && pwd)
cd "$REPO_ROOT"

LOG_DIR="build/release_check"
mkdir -p "$LOG_DIR"

# --- аргументы -------------------------------------------------------------------------------

QUICK=0
FULL=0
APK=""

usage() {
    sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
}

for arg in "$@"; do
    case "$arg" in
        --quick) QUICK=1 ;;
        --full)  FULL=1 ;;
        -h|--help) usage; exit 0 ;;
        -*) echo "ERROR: неизвестный флаг: $arg" >&2; usage >&2; exit 2 ;;
        *)
            if [ -n "$APK" ]; then
                echo "ERROR: лишний позиционный аргумент: $arg" >&2; exit 2
            fi
            APK="$arg"
            ;;
    esac
done

if [ "$QUICK" -eq 1 ] && [ "$FULL" -eq 1 ]; then
    echo "ERROR: --quick и --full несовместимы" >&2; exit 2
fi

# --- учёт результатов ------------------------------------------------------------------------

RESULTS=()
FAILURES=0

# report <PASS|FAIL|SKIP> <имя-проверки> [деталь]
report() {
    local status="$1" name="$2" detail="${3:-}"
    RESULTS+=("$status|$name|$detail")
    printf '  %-4s  %s%s\n' "$status" "$name" "${detail:+ — $detail}"
    if [ "$status" = "FAIL" ]; then
        FAILURES=$((FAILURES + 1))
    fi
}

# run_logged <лог-файл> <команда...> — вывод целиком в лог, код возврата наружу.
run_logged() {
    local log="$1"; shift
    if "$@" >"$log" 2>&1; then
        return 0
    fi
    return 1
}

# --- инструменты SDK (aapt2, apksigner) ------------------------------------------------------

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
if [ ! -d "$SDK_ROOT/build-tools" ]; then
    echo "ERROR: Android SDK build-tools не найдены ($SDK_ROOT/build-tools);" >&2
    echo "       задайте ANDROID_HOME или ANDROID_SDK_ROOT" >&2
    exit 1
fi

# B8 (2026-09-28): единый пин версии build-tools.
source "$SCRIPT_DIR/build-tools-pin.sh"

resolve_tool() { # <имя>
    local found
    # B8: read-only consumer (aapt2 dump / apksigner verify) — предпочитаем запиннованный
    # каталог $TT_BUILD_TOOLS_PIN, а при его отсутствии откатываемся на старшую установленную
    # версию: вывод dump/verify стабилен между версиями build-tools, байты APK здесь не
    # производятся (в отличие от release_pack.sh, где откат запрещён).
    if [ -d "$SDK_ROOT/build-tools/$TT_BUILD_TOOLS_PIN" ]; then
        found=$(find "$SDK_ROOT/build-tools/$TT_BUILD_TOOLS_PIN" -maxdepth 2 -name "$1" -type f | sort -V | tail -1)
    else
        found=$(find "$SDK_ROOT/build-tools" -maxdepth 2 -name "$1" -type f | sort -V | tail -1)
    fi
    if [ -z "$found" ]; then
        echo "ERROR: $1 не найден под $SDK_ROOT/build-tools" >&2
        exit 1
    fi
    printf '%s' "$found"
}

AAPT2=$(resolve_tool aapt2)
APKSIGNER=$(resolve_tool apksigner)

# --- режим --full: сборка с нуля до всех проверок --------------------------------------------

echo "== release_check: кандидат и гейты =="

if [ "$FULL" -eq 1 ]; then
    # `gradlew clean` стирает корневой build/ вместе с LOG_DIR, поэтому чистим отдельно
    # и пересоздаём каталог логов перед сборкой (та же осторожность, что в release_pack.sh).
    ./gradlew clean --console=plain >/dev/null 2>&1
    mkdir -p "$LOG_DIR"
    if run_logged "$LOG_DIR/assemble-release.log" ./gradlew assembleRelease --console=plain; then
        report PASS build.assemble_release "clean assembleRelease, лог $LOG_DIR/assemble-release.log"
    else
        report FAIL build.assemble_release "сборка упала, лог $LOG_DIR/assemble-release.log"
        tail -20 "$LOG_DIR/assemble-release.log" >&2 || true
    fi
fi

# --- выбор APK --------------------------------------------------------------------------------

if [ -z "$APK" ]; then
    APK=$(ls -t app/build/outputs/apk/release/*.apk 2>/dev/null | head -1 || true)
    if [ -z "$APK" ]; then
        echo "ERROR: APK не задан и app/build/outputs/apk/release/*.apk пуст" >&2
        exit 1
    fi
fi
if [ ! -f "$APK" ]; then
    echo "ERROR: APK не найден: $APK" >&2
    exit 1
fi
echo "Кандидат: $APK"

# --- 1. гейты ---------------------------------------------------------------------------------

if [ "$QUICK" -eq 1 ]; then
    report SKIP gates.gradle_test "--quick"
    report SKIP gates.lint_release "--quick"
    report SKIP gates.python_tests "--quick"
    report SKIP gates.no_internet "--quick"
    report SKIP gates.asset_rebuild_check "--quick"
else
    # JVM-тесты; счётчик — из XML-отчётов JUnit (каталог app/build/test-results/*/).
    # --rerun-tasks: честный прогон, а не up-to-date (AGENTS.md).
    if run_logged "$LOG_DIR/gradle-test.log" ./gradlew test --rerun-tasks --console=plain; then
        sum_attr() { # <атрибут>
            grep -hoE "$1=\"[0-9]+\"" app/build/test-results/*/*.xml 2>/dev/null \
                | awk -F'"' '{s+=$2} END{print s+0}'
        }
        t_files=$(ls app/build/test-results/*/*.xml 2>/dev/null | wc -l || true)
        t_tests=$(sum_attr tests)
        t_fail=$(sum_attr failures)
        t_err=$(sum_attr errors)
        if [ "$t_files" -eq 0 ]; then
            report FAIL gates.gradle_test "gradle зелёный, но XML-отчётов нет — нечем подтвердить прогон"
        elif [ "$t_fail" -eq 0 ] && [ "$t_err" -eq 0 ]; then
            report PASS gates.gradle_test "$t_tests тестов в $t_files файлах, 0 падений"
        else
            report FAIL gates.gradle_test "$t_tests тестов, failures=$t_fail, errors=$t_err; лог $LOG_DIR/gradle-test.log"
        fi
    else
        report FAIL gates.gradle_test "./gradlew test упал, лог $LOG_DIR/gradle-test.log"
        tail -20 "$LOG_DIR/gradle-test.log" >&2 || true
    fi

    # lintRelease с baseline (abortOnError=true).
    if run_logged "$LOG_DIR/lint-release.log" ./gradlew lintRelease --console=plain; then
        report PASS gates.lint_release "baseline без новых ошибок"
    else
        report FAIL gates.lint_release "упал, лог $LOG_DIR/lint-release.log"
        tail -20 "$LOG_DIR/lint-release.log" >&2 || true
    fi

    # Python-тесты конвейера: чистый unittest, по файлу за прогон (как в AGENTS.md).
    py_total=0
    py_failed=0
    : >"$LOG_DIR/python-tests.log"
    for f in tests/*/test_*.py; do
        if python3 "$f" >>"$LOG_DIR/python-tests.log" 2>&1; then
            n=$(tail -5 "$LOG_DIR/python-tests.log" | grep -oE 'Ran [0-9]+ tests' | tail -1 | grep -oE '[0-9]+' || true)
            py_total=$((py_total + ${n:-0}))
        else
            py_failed=$((py_failed + 1))
            echo "FAILED: $f" >>"$LOG_DIR/python-tests.log"
        fi
    done
    if [ "$py_failed" -eq 0 ]; then
        report PASS gates.python_tests "$py_total тестов в $(ls tests/*/test_*.py | wc -l) файлах"
    else
        report FAIL gates.python_tests "$py_failed файлов с падениями, лог $LOG_DIR/python-tests.log"
    fi

    # no-INTERNET + backup-whitelist на проверяемом APK (оба уровня гейта).
    if run_logged "$LOG_DIR/no-internet.log" bash scripts/check-no-internet.sh "$APK"; then
        report PASS gates.no_internet "оба уровня (манифест + aapt2), лог $LOG_DIR/no-internet.log"
    else
        report FAIL gates.no_internet "гейт упал, лог $LOG_DIR/no-internet.log"
        cat "$LOG_DIR/no-internet.log" >&2 || true
    fi

    # Аудит 2026-09-25: согласованность ассетов с пинами и голов таблиц со словарями —
    # тем же входом, что CI и предрелизная сверка (AGENTS.md), а не только косвенно через
    # извлечённые из APK пины ниже.
    if run_logged "$LOG_DIR/asset-rebuild-check.log" \
            python3 scripts/rebuild_assets.py --check --allow-known-drift; then
        report PASS gates.asset_rebuild_check "пины и связки согласованы, лог $LOG_DIR/asset-rebuild-check.log"
    else
        report FAIL gates.asset_rebuild_check "расхождение, лог $LOG_DIR/asset-rebuild-check.log"
        tail -20 "$LOG_DIR/asset-rebuild-check.log" >&2 || true
    fi
fi

# --- 2. размер APK против инварианта -----------------------------------------------------------

echo "== артефактные проверки =="

APK_SIZE=$(stat -c %s "$APK")
if [ "$APK_SIZE" -le "$APK_SIZE_LIMIT" ]; then
    headroom=$(awk -v s="$APK_SIZE" -v lim="$APK_SIZE_LIMIT" 'BEGIN{printf "%.1f", (lim - s) / lim * 100}')
    report PASS artifact.size "$APK_SIZE Б при потолке $APK_SIZE_LIMIT Б, запас $headroom %"
else
    report FAIL artifact.size "$APK_SIZE Б превышает потолок $APK_SIZE_LIMIT Б"
fi

# --- 3. пины ассетов против констант в коде ----------------------------------------------------
# Извлекаем *.tdict.zlib / *.tatbigr.zlib из APK и сверяем размер и SHA-256 (сжатый и
# развёрнутый) с константами DictionaryStorageContracts.kt / BigramStorageContracts.kt.
# Чтение пинов повторяет regex-подход scripts/rebuild_assets.py (read_pins), но
# самодостаточно: этот гейт не должен зависеть от импортируемости конвейера.

PINS_LOG="$LOG_DIR/asset-pins.log"
if python3 - "$APK" >"$PINS_LOG" 2>&1 <<'PYEOF'
import hashlib
import re
import sys
import zipfile
import zlib
from pathlib import Path

apk_path = sys.argv[1]
storage = Path("app/src/main/java/rkr/simplekeyboard/inputmethod/latin/dictionary/storage")

SPECS = [
    (storage / "DictionaryStorageContracts.kt", "DictionaryArtifactSpec",
     [("TATAR_TOP100K_V1", "dictionaries/tatar_top100k_v1.tdict.zlib"),
      ("RUSSIAN_TOP100K_V1", "dictionaries/russian_top100k_v1.tdict.zlib")]),
    (storage / "BigramStorageContracts.kt", "BigramArtifactSpec",
     [("TATAR_BIGRAMS_V1", "bigrams/tatar_bigrams_v1.tatbigr.zlib"),
      ("RUSSIAN_BIGRAMS_V1", "bigrams/russian_bigrams_v1.tatbigr.zlib")]),
]

def read_field(block, field):
    m = re.search(rf"{field} = ([\d_]+),", block)
    if m:
        return int(m.group(1).replace("_", ""))
    m = re.search(rf'{field} =\s*"([0-9a-f]{{64}})"', block)
    if m:
        return m.group(1)
    raise SystemExit(f"ERROR: поле {field} не найдено в блоке спецификации")

problems = []
checked = 0
with zipfile.ZipFile(apk_path) as apk:
    for contract, kind, specs in SPECS:
        text = contract.read_text(encoding="utf-8")
        for spec, asset in specs:
            blocks = list(re.finditer(
                rf"val {spec} = {kind}\(.*?\n        \)", text, re.DOTALL))
            if len(blocks) != 1:
                problems.append(f"{spec}: блоков {kind} в {contract.name}: {len(blocks)}, ожидался 1")
                continue
            block = blocks[0].group(0)
            expected = {f: read_field(block, f) for f in (
                "expectedCompressedSize", "expectedCompressedSha256",
                "expectedRawSize", "expectedRawSha256")}
            try:
                compressed = apk.read(f"assets/{asset}")
                raw = zlib.decompress(compressed)
            except (KeyError, zipfile.BadZipFile, zlib.error) as exc:
                problems.append(f"{asset}: не извлекается из APK: {exc}")
                continue
            actual = {
                "expectedCompressedSize": len(compressed),
                "expectedCompressedSha256": hashlib.sha256(compressed).hexdigest(),
                "expectedRawSize": len(raw),
                "expectedRawSha256": hashlib.sha256(raw).hexdigest(),
            }
            for field, want in expected.items():
                checked += 1
                got = actual[field]
                if got != want:
                    problems.append(f"{asset}: {field}: ожидалось {want}, в APK {got}")
            print(f"OK {asset} (сжатый и raw размер + SHA-256)")

for p in problems:
    print(f"MISMATCH {p}")
if problems:
    sys.exit(1)
print(f"TOTAL {checked} значений по {sum(len(s) for _, _, s in SPECS)} ассетам совпали")
PYEOF
then
    report PASS artifact.asset_pins "$(tail -1 "$PINS_LOG")"
    grep '^OK ' "$PINS_LOG" | sed 's/^/       /'
else
    report FAIL artifact.asset_pins "пины не сошлись, лог $PINS_LOG"
    cat "$PINS_LOG" >&2
fi

# --- 3b. эмодзи-ассеты: APK против дерева ------------------------------------------------------
# Эмодзи-ассеты — открытый текст (без zlib-обёртки), поэтому их пин — сам файл в
# дереве: содержимое APK обязано быть побайтно тем, что закоммичено (до 2026-09-01
# они покрывались только python/JVM-тестами, но не этим гейтом).
# С 2026-09-02 (C2 аудита) сверяется МНОЖЕСТВО файлов, а не зашитый список:
# новый файл в дереве без APK (или наоборот) — тоже FAIL, fail-open закрыт.

EMOJI_LOG="$LOG_DIR/emoji-assets.log"
if python3 - "$APK" >"$EMOJI_LOG" 2>&1 <<'PYEOF'
import hashlib
import sys
import zipfile
from pathlib import Path

apk_path = sys.argv[1]
TREE_DIR = Path("app/src/main/assets/emoji")

problems = []
tree = {p.relative_to(TREE_DIR).as_posix(): p for p in TREE_DIR.rglob("*") if p.is_file()}
with zipfile.ZipFile(apk_path) as apk:
    in_apk = {name.removeprefix("assets/emoji/")
              for name in apk.namelist()
              if name.startswith("assets/emoji/") and not name.endswith("/")}

    for name in sorted(set(tree) - in_apk):
        problems.append(f"{name}: есть в дереве, нет в APK")
    for name in sorted(in_apk - set(tree)):
        problems.append(f"{name}: есть в APK, нет в дереве")
    for name in sorted(set(tree) & in_apk):
        want = tree[name].read_bytes()
        got = apk.read(f"assets/emoji/{name}")
        if got != want:
            problems.append(
                f"{name}: расходится с деревом "
                f"(дерево {hashlib.sha256(want).hexdigest()[:16]}…, "
                f"APK {hashlib.sha256(got).hexdigest()[:16]}…)")
        else:
            print(f"OK {name} ({len(want)} Б, побайтно дерево)")

for p in problems:
    print(f"MISMATCH {p}")
if problems:
    sys.exit(1)
print(f"TOTAL {len(tree)} эмодзи-ассетов совпали с деревом (множество и содержимое)")
PYEOF
then
    report PASS artifact.emoji_assets "$(tail -1 "$EMOJI_LOG")"
    grep '^OK ' "$EMOJI_LOG" | sed 's/^/       /'
else
    report FAIL artifact.emoji_assets "эмодзи-ассеты расходятся, лог $EMOJI_LOG"
    cat "$EMOJI_LOG" >&2
fi

# --- 3c. словари и биграммы: APK против дерева ---------------------------------------------------
# Аудит 2026-09-25: множественная сверка (C2 аудита 2026-09-02) покрывала только assets/emoji/ —
# посторонний файл под assets/dictionaries/ или assets/bigrams/ в APK проходил мимо гейта, хотя
# движок читает ассеты изображённым каталогом. Те же правила, что для эмодзи: МНОЖЕСТВО файлов в
# обе стороны (включая NOTICE.txt и sentstart-таблицы) плюс побайтное содержимое. Пины четырёх
# zlib-ассетов выше (artifact.asset_pins) при этом остаются отдельной проверкой: она сверяет ещё
# и развёрнутое содержимое с константами в коде.

TREE_ASSETS_LOG="$LOG_DIR/tree-assets.log"
if python3 - "$APK" >"$TREE_ASSETS_LOG" 2>&1 <<'PYEOF'
import hashlib
import sys
import zipfile
from pathlib import Path

apk_path = sys.argv[1]
SUBTREES = ["dictionaries", "bigrams"]

problems = []
tree = {}
for subtree in SUBTREES:
    root = Path("app/src/main/assets") / subtree
    for p in root.rglob("*"):
        if p.is_file():
            tree[(subtree, p.relative_to(root).as_posix())] = p
with zipfile.ZipFile(apk_path) as apk:
    in_apk = set()
    for subtree in SUBTREES:
        prefix = f"assets/{subtree}/"
        for name in apk.namelist():
            if name.startswith(prefix) and not name.endswith("/"):
                in_apk.add((subtree, name[len(prefix):]))
    for key in sorted(set(tree) - in_apk):
        problems.append(f"{key[0]}/{key[1]}: есть в дереве, нет в APK")
    for key in sorted(in_apk - set(tree)):
        problems.append(f"{key[0]}/{key[1]}: есть в APK, нет в дереве")
    for key in sorted(set(tree) & in_apk):
        want = tree[key].read_bytes()
        got = apk.read(f"assets/{key[0]}/{key[1]}")
        if got != want:
            problems.append(
                f"{key[0]}/{key[1]}: расходится с деревом "
                f"(дерево {hashlib.sha256(want).hexdigest()[:16]}…, "
                f"APK {hashlib.sha256(got).hexdigest()[:16]}…)")
        else:
            print(f"OK {key[0]}/{key[1]} ({len(want)} Б, побайтно дерево)")

for p in problems:
    print(f"MISMATCH {p}")
if problems:
    sys.exit(1)
print(f"TOTAL {len(tree)} ассетов dictionaries/bigrams совпали с деревом (множество и содержимое)")
PYEOF
then
    report PASS artifact.tree_assets "$(tail -1 "$TREE_ASSETS_LOG")"
    grep '^OK ' "$TREE_ASSETS_LOG" | sed 's/^/       /'
else
    report FAIL artifact.tree_assets "ассеты расходятся с деревом, лог $TREE_ASSETS_LOG"
    cat "$TREE_ASSETS_LOG" >&2
fi

# --- 3d. критические ресурсы: keep.xml реально покрывает живые имена в APK ---------------------
# B5 (2026-09-28): shrinkResources держится на app/src/main/res/raw/keep.xml, а ресурсы семейств
# keyboard_layout_set_*/kbd_*/rows_*/rowkeys_*/row_* и строк locale_name_*/label_* грузятся
# РЕФЛЕКСИВНО (KeyboardLayoutSet собирает "keyboard_layout_set_" + subtype.getKeyboardLayoutSet()
# и зовёт getIdentifier; KeyboardTextsSet резолвит label_pause_key/label_wait_key;
# LocaleResourceUtils — locale_name_*). Если keep.xml молча перестанет совпадать с реальными
# именами (переименование файла, потерянный глоб), прореживание выбросит ресурс и заметит это
# только запуск на устройстве. Гейт: из ДЕРЕВА перечисляем конкретные имена, покрываемые
# семействами keep.xml, и требуем каждое в `aapt2 dump resources` кандидата. Обратного направления
# (dump -> дерево) нет намеренно: дамп включает framework-записи android:*.
# Отсутствие самого keep.xml — тоже FAIL (охрана охранника).

CRIT_RES_LOG="$LOG_DIR/critical-resources.log"
if python3 - "$APK" "$AAPT2" >"$CRIT_RES_LOG" 2>&1 <<'PYEOF'
import re
import subprocess
import sys
from pathlib import Path

apk_path, aapt2 = sys.argv[1], sys.argv[2]
RES = Path("app/src/main/res")

KEEP = RES / "raw" / "keep.xml"
if not KEEP.is_file():
    raise SystemExit(f"ERROR: нет {KEEP} — shrinkResources остался без keep-списка, "
                     "рефлексивно груженые ресурсы ничто не защищает")

m = re.search(r'tools:keep="([^"]+)"', KEEP.read_text(encoding="utf-8"))
if not m:
    raise SystemExit(f"ERROR: tools:keep не разобран в {KEEP}")
keep_pats = [p.strip() for p in m.group(1).split(",") if p.strip()]

# xml-семейства — из самого keep.xml (префиксы @xml/<prefix>*); состав семейств фиксирован:
# тихая правка keep.xml (потерянное семейство) обязана сломать гейт, а не перечислить меньше.
EXPECTED_XML_FAMILIES = ["keyboard_layout_set_", "kbd_", "rows_", "rowkeys_", "row_"]
xml_families = [p[len("@xml/"):-1] for p in keep_pats
                if p.startswith("@xml/") and p.endswith("*")]
if sorted(xml_families) != sorted(EXPECTED_XML_FAMILIES):
    raise SystemExit(f"ERROR: xml-семейства в {KEEP}: {sorted(xml_families)}, "
                     f"ожидались {EXPECTED_XML_FAMILIES}")

expected = {}  # "тип/имя" -> откуда в дереве

def want(kind, name, origin):
    expected.setdefault(f"{kind}/{name}", origin)

# Конкретные xml-ресурсы: файлы во ВСЕХ res/xml*/ конфигурациях, чьи имена подпадают под
# семейства keep.xml (имя ресурса = имя файла без расширения).
for d in sorted(RES.glob("xml*")):
    if not d.is_dir():
        continue
    for f in sorted(d.glob("*.xml")):
        if any(f.stem.startswith(pref) for pref in xml_families):
            want("xml", f.stem, str(f))

# Строки, резолвимые KeyboardTextsSet по хардкод-имени: обязаны быть ОПРЕДЕЛЕНЫ в
# strings-action-keys.xml (иначе проверять в APK нечего — это уже ошибка дерева).
ACTION_STRINGS = RES / "values" / "strings-action-keys.xml"
if not ACTION_STRINGS.is_file():
    raise SystemExit(f"ERROR: нет {ACTION_STRINGS}")
action_text = ACTION_STRINGS.read_text(encoding="utf-8")
for name in ("label_pause_key", "label_wait_key"):
    if not re.search(rf'<string\b[^>]*\bname="{name}"', action_text):
        raise SystemExit(f"ERROR: строка {name} не определена в {ACTION_STRINGS}")
    want("string", name, str(ACTION_STRINGS))

# Каждая строка locale_name_* под res/values*/ (LocaleResourceUtils резолвит их по имени
# локали). Плейсхолдер «locale_name_<locale>» живёт в XML-комментарии donottranslate.xml,
# поэтому матчим только реальные элементы <string ... name="...">.
locale_count = 0
for d in sorted(RES.glob("values*")):
    if not d.is_dir():
        continue
    for f in sorted(d.glob("*.xml")):
        for name in re.findall(r'<string\b[^>]*\bname="(locale_name_[^"]+)"',
                               f.read_text(encoding="utf-8")):
            want("string", name, str(f))
            locale_count += 1
if locale_count == 0:
    raise SystemExit("ERROR: ни одной строки locale_name_* под app/src/main/res/values*/ — "
                     "дерево обеднело или гейт смотрит не туда")

dump = subprocess.run([aapt2, "dump", "resources", apk_path],
                      capture_output=True, text=True, check=True).stdout
present = set(re.findall(r"^    resource 0x[0-9a-fA-F]+ (\S+/\S+)$", dump, re.MULTILINE))

problems = []
for key in sorted(expected):
    if key in present:
        print(f"OK {key} ({expected[key]})")
    else:
        problems.append(f"{key}: ожидался в APK ({expected[key]}), в aapt2 dump его нет")

for p in problems:
    print(f"MISSING {p}")
if problems:
    sys.exit(1)
print(f"TOTAL {len(expected)} критических ресурсов ({len(xml_families)} xml-семейств + "
      f"label_pause_key/label_wait_key + locale_name_*) присутствуют в APK")
PYEOF
then
    report PASS artifact.critical_resources "$(tail -1 "$CRIT_RES_LOG")"
else
    report FAIL artifact.critical_resources "критические ресурсы отсутствуют в APK, лог $CRIT_RES_LOG"
    cat "$CRIT_RES_LOG" >&2
fi

# --- 3.9. resources.arsc обязан быть STORED (иначе APK не установится на Android 11+) ---------
# Найдено 2026-09-25 при проверке 3.1.0 на POCO C71 (Android 15): упакованный APK не ставился —
#   Failure [-124: ... Targeting R+ (version 30 and above) requires the resources.arsc of
#   installed APKs to be stored uncompressed and aligned on a 4-byte boundary]
# Виноват был шаг O2-1 упаковщика (arsc STORED -> DEFLATED, −73,7 КБ). Ни один гейт этого не
# видел: `zipalign -c 4` для сжатого arsc печатает «OK - compressed» и выходит с нулём. Теперь
# условие проверяется прямо на артефакте: только STORED и только со смещением, кратным 4.
ARSC_LOG="$LOG_DIR/arsc-stored.log"
if python3 - "$APK" >"$ARSC_LOG" 2>&1 <<'PYEOF'
import sys
import zipfile

apk = sys.argv[1]
with zipfile.ZipFile(apk) as zf:
    info = zf.getinfo('resources.arsc')
    if info.compress_type != zipfile.ZIP_STORED:
        raise SystemExit('resources.arsc COMPRESSED (method %d) — Android 11+ откажется ставить APK'
                         % info.compress_type)
    with open(apk, 'rb') as fh:
        fh.seek(info.header_offset)
        local = fh.read(30)
        name_len = int.from_bytes(local[26:28], 'little')
        extra_len = int.from_bytes(local[28:30], 'little')
        data_offset = info.header_offset + 30 + name_len + extra_len
    if data_offset % 4 != 0:
        raise SystemExit('resources.arsc не выровнен: смещение данных %d не кратно 4' % data_offset)
print('resources.arsc: STORED, %d Б, смещение %d (кратно 4)' % (info.file_size, data_offset))
PYEOF
then
    report PASS artifact.arsc_stored "$(tail -1 "$ARSC_LOG")"
else
    report FAIL artifact.arsc_stored "$(tail -2 "$ARSC_LOG")"
fi

# --- 4. разрешения: ровно VIBRATE --------------------------------------------------------------

if PERMS=$("$AAPT2" dump permissions "$APK" 2>&1); then
    perm_count=$(grep -c '^uses-permission:' <<<"$PERMS" || true)
    if [ "$perm_count" -eq 1 ] \
        && grep -qF "uses-permission: name='android.permission.VIBRATE'" <<<"$PERMS"; then
        report PASS artifact.permissions "ровно [VIBRATE]"
    else
        report FAIL artifact.permissions "ожидалось ровно одно uses-permission [VIBRATE], фактически:"
        printf '%s\n' "$PERMS" | sed 's/^/       /' >&2
    fi
else
    report FAIL artifact.permissions "aapt2 dump permissions упал: $PERMS"
fi

# --- 4b. exported surface: точное равенство золотому набору (S2 аудита 2026-09-29) --------------
# The APK manifest is the merged+built one, so drift can enter through build config, not only
# source edits. Parse `aapt2 dump xmltree` into component records (kind, name, permission
# guard, intent-filter actions/categories) and require the EXPORTED set to equal the embedded
# golden set in both directions (unexpected-exported fails, missing-exported fails). The IME
# service's exported=false + BIND_INPUT_METHOD pair is pinned separately: a service exported
# without that guard is the canonical IME finding this gate exists for.

EXPORTED_LOG="$LOG_DIR/exported-surface.log"
if python3 - "$APK" "$AAPT2" >"$EXPORTED_LOG" 2>&1 <<'PYEOF'
import re
import subprocess
import sys

apk_path, aapt2 = sys.argv[1], sys.argv[2]

COMPONENT_KINDS = ("activity", "activity-alias", "service", "receiver", "provider")

# Golden exported surface, derived 2026-09-29 from dist/tatar-keyboard-3.4.0.apk
# (`aapt2 dump xmltree --file AndroidManifest.xml`) and verified 1:1 against
# app/src/main/AndroidManifest.xml (zero runtime dependencies — nothing merges in):
#   activity rkr.simplekeyboard.inputmethod.latin.setup.SetupActivity
#       exported, no permission guard, filter MAIN + category LAUNCHER — launcher-icon
#       entry point (onboarding/setup wizard), must stay reachable.
#   activity rkr.simplekeyboard.inputmethod.latin.settings.SettingsActivity
#       exported, no permission guard, no filter — settings screen reached by explicit
#       intents (system IME-settings gear, SetupActivity hand-off), exported by design.
# Any other exported component, or a changed guard/filter here, is drift and fails.
GOLDEN_EXPORTED = {
    ("activity", "rkr.simplekeyboard.inputmethod.latin.setup.SetupActivity", "",
     ("android.intent.action.MAIN",), ("android.intent.category.LAUNCHER",)),
    ("activity", "rkr.simplekeyboard.inputmethod.latin.settings.SettingsActivity", "",
     (), ()),
}

IME_SERVICE = "rkr.simplekeyboard.inputmethod.latin.LatinIME"
IME_PERMISSION = "android.permission.BIND_INPUT_METHOD"

proc = subprocess.run([aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk_path],
                      capture_output=True, text=True)
if proc.returncode != 0:
    raise SystemExit(f"ERROR: aapt2 dump xmltree упал: {proc.stderr.strip() or proc.stdout.strip()}")


def attr_pair(line):
    # 'A: <ns-uri>:name(0x01010003)="value" (Raw: "value")' -> ("name", "value");
    # booleans arrive unquoted ('=false'), strings quoted. Plain attrs without an
    # id (package=...) return None — not needed here.
    m = re.match(r"A: (\S+)\(0x[0-9a-fA-F]+\)=(.*)$", line)
    if not m:
        return None
    local = m.group(1).rsplit(":", 1)[-1]
    raw = m.group(2).strip()
    if raw.startswith('"'):
        return local, raw[1:raw.find('"', 1)]
    return local, raw.split(" ", 1)[0]


components = []
stack = []  # {'indent', 'tag', 'comp', 'filt'} — nesting by indent depth
for raw_line in proc.stdout.splitlines():
    line = raw_line.strip()
    indent = len(raw_line) - len(raw_line.lstrip(" "))
    if line.startswith("E: "):
        tag = line[3:].split(" ", 1)[0]
        while stack and stack[-1]["indent"] >= indent:
            stack.pop()
        parent = stack[-1] if stack else None
        entry = {"indent": indent, "tag": tag, "comp": None, "filt": False}
        if parent and parent["tag"] == "application" and tag in COMPONENT_KINDS:
            comp = {"kind": tag, "name": "", "exported": None, "permission": "",
                    "actions": [], "categories": []}
            components.append(comp)
            entry["comp"] = comp
        elif parent and parent["comp"] is not None and tag == "intent-filter":
            entry["comp"] = parent["comp"]
            entry["filt"] = True
        elif parent and parent["filt"] and tag in ("action", "category"):
            entry["comp"] = parent["comp"]
            entry["filt"] = True
        stack.append(entry)
    elif line.startswith("A: ") and stack:
        pair = attr_pair(line)
        if not pair:
            continue
        local, value = pair
        owner = stack[-1]
        comp = owner["comp"]
        if comp is None:
            continue
        if owner["tag"] in COMPONENT_KINDS and not owner["filt"]:
            if local in ("name", "exported", "permission"):
                comp[local] = value
        elif owner["filt"] and owner["tag"] == "action" and local == "name":
            comp["actions"].append(value)
        elif owner["filt"] and owner["tag"] == "category" and local == "name":
            comp["categories"].append(value)


def record_of(comp):
    if comp["exported"] is not None:
        exported = comp["exported"] == "true"
    else:
        # Platform implicit rule for a missing attribute: a component carrying an
        # intent-filter is exported (targetSdk 37 forces explicit exported at build
        # time; the rule is the safe fallback). Fail-closed, not silent-skip.
        exported = bool(comp["actions"] or comp["categories"])
    return (comp["kind"], comp["name"], comp["permission"],
            tuple(sorted(comp["actions"])), tuple(sorted(comp["categories"]))), exported


def fmt(key):
    kind, name, perm, actions, cats = key
    parts = [kind, name, f"permission={perm or '-'}"]
    if actions:
        parts.append("actions=" + ",".join(actions))
    if cats:
        parts.append("categories=" + ",".join(cats))
    return " ".join(parts)


problems = []
actual_exported = {}
for comp in components:
    key, exported = record_of(comp)
    if exported:
        actual_exported[key] = comp

for key in sorted(actual_exported):
    if key not in GOLDEN_EXPORTED:
        problems.append(f"неожиданный экспонированный компонент: {fmt(key)}")
for key in sorted(GOLDEN_EXPORTED):
    if key not in actual_exported:
        problems.append(f"ожидался экспонированный, не найден: {fmt(key)}")
    else:
        print(f"OK {fmt(key)}")

ime = [c for c in components if c["kind"] == "service" and c["name"] == IME_SERVICE]
ime_ok = True
if len(ime) != 1:
    problems.append(f"IME-сервис {IME_SERVICE}: найдено записей {len(ime)}, ожидалась 1")
    ime_ok = False
else:
    svc = ime[0]
    if svc["exported"] != "false":
        problems.append(f"IME-сервис {IME_SERVICE}: exported={svc['exported']!r}, "
                        "требуется явное false")
        ime_ok = False
    if svc["permission"] != IME_PERMISSION:
        problems.append(f"IME-сервис {IME_SERVICE}: permission={svc['permission']!r}, "
                        f"требуется {IME_PERMISSION}")
        ime_ok = False
if ime_ok:
    print(f"OK service {IME_SERVICE} exported=false + {IME_PERMISSION} (пин IME-стража)")

for p in problems:
    print(f"DRIFT {p}")
if problems:
    sys.exit(1)
print(f"TOTAL {len(GOLDEN_EXPORTED)} экспонированных компонента = золотому набору "
      "(обе стороны), IME-сервис закрыт пином")
PYEOF
then
    report PASS artifact.exported_surface "$(tail -1 "$EXPORTED_LOG")"
    grep '^OK ' "$EXPORTED_LOG" | sed 's/^/       /'
else
    report FAIL artifact.exported_surface "экспонированная поверхность отличается от золотого набора, лог $EXPORTED_LOG"
    cat "$EXPORTED_LOG" >&2
fi

# --- 4c. secrets scan: дерево репозитория (tracked) + записи APK (S4 аудита 2026-09-29) ---------
# Two fail-closed halves, offenders listed.
# (a) repo: `git ls-files` — tracked files only, so keystore.properties and the .jks
#     (gitignored locals) must not trip the gate — scanned for secret filenames
#     (*.jks, *.keystore, keystore.properties, *.pem, *.p12), private-key block
#     headers, and common token shapes (GitHub PAT, AWS access key id, sk-tokens).
# (b) APK: no zip entry may carry a secret filename.
# Content regexes are shaped so this script's own text never matches them (right after
# the literal prefix comes '[', outside the accepted class) — the gate scans its own
# tracked file on every run, so a self-match would be a permanent FAIL.

NO_SECRETS_LOG="$LOG_DIR/no-secrets.log"
if python3 - "$APK" >"$NO_SECRETS_LOG" 2>&1 <<'PYEOF'
import re
import subprocess
import sys
import zipfile

apk_path = sys.argv[1]

SECRET_BASENAMES = {"keystore.properties"}
SECRET_SUFFIXES = (".jks", ".keystore", ".pem", ".p12")

CONTENT_PATTERNS = [
    ("private-key block header", re.compile(rb"-----BEGIN [A-Z0-9 ]{0,64}PRIVATE KEY")),
    ("GitHub PAT", re.compile(rb"ghp_[A-Za-z0-9]{36}")),
    ("AWS access key id", re.compile(rb"AKIA[0-9A-Z]{16}")),
    ("sk-token", re.compile(rb"sk-[A-Za-z0-9]{20,}")),
]


def secret_name(path):
    base = path.rsplit("/", 1)[-1].lower()
    return base in SECRET_BASENAMES or base.endswith(SECRET_SUFFIXES)


problems = []

out = subprocess.run(["git", "ls-files", "-z"], capture_output=True, check=True).stdout
paths = [p for p in out.decode("utf-8", "surrogateescape").split("\0") if p]
scanned = 0
for path in paths:
    if secret_name(path):
        problems.append(f"repo {path}: имя файла подпадает под секретный шаблон")
    try:
        with open(path, "rb") as fh:
            content = fh.read()
    except OSError:
        # Tracked but gone from the worktree (unstaged deletion): scan the staged
        # blob instead. An intent-to-add entry whose file then vanished has the
        # empty blob — nothing exists to leak, so that case scans 0 bytes.
        blob = subprocess.run(["git", "cat-file", "blob", f":{path}"],
                              capture_output=True)
        if blob.returncode != 0:
            problems.append(f"repo {path}: нет ни в рабочем дереве, ни в индексе")
            continue
        content = blob.stdout
    scanned += 1
    for label, rx in CONTENT_PATTERNS:
        hit = rx.search(content)
        if hit:
            line_no = content.count(b"\n", 0, hit.start()) + 1
            problems.append(f"repo {path}:{line_no}: найден паттерн «{label}»")

entries = 0
with zipfile.ZipFile(apk_path) as apk:
    for name in apk.namelist():
        if name.endswith("/"):
            continue
        entries += 1
        if secret_name(name):
            problems.append(f"apk {name}: запись с именем секретного файла")

for p in problems:
    print(f"LEAK {p}")
if problems:
    sys.exit(1)
print(f"TOTAL 0 находок: {scanned} отслеживаемых файлов (имена + содержимое), "
      f"{entries} записей APK (имена)")
PYEOF
then
    report PASS artifact.no_secrets "$(tail -1 "$NO_SECRETS_LOG")"
else
    report FAIL artifact.no_secrets "найдены секретоподобные файлы/строки, лог $NO_SECRETS_LOG"
    cat "$NO_SECRETS_LOG" >&2
fi

# --- 5. подпись: сертификат релизного ключа, ровно один сигнер -----------------------------------

if SIG=$("$APKSIGNER" verify --print-certs "$APK" 2>&1); then
    # Аудит 2026-09-25: сигнеров должно быть РОВНО один. Каждый сигнер печатает строку
    # «… certificate SHA-256 digest: <hash>» (мульти-подпись — «V2 Signer #1/#2: …», проверено
    # на собранном вручную двухключевом APK); head -1 принимал бы APK с лишним чужим ключом
    # молча. Один ключ, подписавший по двум схемам, даёт один ДАЙДЖЕСТ дважды — это один
    # сигнер, поэтому считаем различные дайджесты, а не строки.
    mapfile -t CERTS < <(grep -oE 'certificate SHA-256 digest: [0-9a-f]{64}' <<<"$SIG" \
        | grep -oE '[0-9a-f]{64}' | sort -u || true)
    if [ "${#CERTS[@]}" -eq 0 ]; then
        report FAIL artifact.signature "apksigner не вернул SHA-256 сертификата: $SIG"
    elif [ "${#CERTS[@]}" -gt 1 ]; then
        report FAIL artifact.signature "различных сертификатов: ${#CERTS[@]} (>1) — мульти-подпись недопустима"
        printf '       signer %s\n' "${CERTS[@]}" >&2
    elif [ "${CERTS[0]}" = "$RELEASE_CERT_SHA256" ]; then
        report PASS artifact.signature "сертификат ${CERTS[0]:0:12}… (релизный ключ, единственный сигнер)"
    else
        report FAIL artifact.signature "сертификат ${CERTS[0]} ≠ релизному ${RELEASE_CERT_SHA256:0:12}… (не тот ключ — debug?)"
    fi
else
    report FAIL artifact.signature "APK не подписан или подпись не верифицируется: $(tail -1 <<<"$SIG")"
fi

# --- 6. версия: aapt2 badging против app/build.gradle ------------------------------------------

EXPECTED_VC=$(grep -oE 'versionCode [0-9]+' app/build.gradle | awk '{print $2}' | head -1 || true)
EXPECTED_VN=$(grep -oE 'versionName "[^"]+"' app/build.gradle | head -1 | cut -d'"' -f2 || true)
if [ -z "$EXPECTED_VC" ] || [ -z "$EXPECTED_VN" ]; then
    echo "ERROR: versionCode/versionName не разобрались из app/build.gradle" >&2
    exit 1
fi

if BADGE=$("$AAPT2" dump badging "$APK" 2>&1); then
    BADGE=${BADGE%%$'\n'*}
    APK_VC=$(sed -nE "s/.*versionCode='([0-9]+)'.*/\1/p" <<<"$BADGE")
    APK_VN=$(sed -nE "s/.*versionName='([^']*)'.*/\1/p" <<<"$BADGE")
    if [ -z "$APK_VC" ]; then
        report FAIL artifact.version "badging не содержит versionCode: $BADGE"
    elif [ "$APK_VC" = "$EXPECTED_VC" ] && [ "$APK_VN" = "$EXPECTED_VN" ]; then
        report PASS artifact.version "$APK_VN / versionCode $APK_VC = app/build.gradle"
    else
        report FAIL artifact.version "APK $APK_VN/$APK_VC ≠ app/build.gradle $EXPECTED_VN/$EXPECTED_VC"
    fi
else
    APK_VC=""
    report FAIL artifact.version "aapt2 dump badging упал: $BADGE"
fi

# --- 7. store-заметка metadata/en-US/changelogs/<versionCode>.txt ------------------------------

CHANGELOG="metadata/en-US/changelogs/${APK_VC:-$EXPECTED_VC}.txt"
if [ -n "$APK_VC" ] && [ -f "$CHANGELOG" ]; then
    report PASS artifact.changelog "$CHANGELOG на месте ($(wc -c <"$CHANGELOG") Б)"
else
    report FAIL artifact.changelog "нет $CHANGELOG"
fi

# --- 8. дельта к предыдущему релизу из dist/ ----------------------------------------------------
# Предыдущий = APK из dist/ с максимальным versionCode, строго меньшим кандидатского.
# Проверка информационная: бюджет размера охраняет artifact.size, здесь только сводка.

PREV=""
PREV_VC=-1
if [ -n "$APK_VC" ] && ls dist/*.apk >/dev/null 2>&1; then
    for f in dist/*.apk; do
        badge=$("$AAPT2" dump badging "$f" 2>/dev/null || true)
        vc=$(sed -nE "s/.*versionCode='([0-9]+)'.*/\1/p" <<<"${badge%%$'\n'*}")
        if [ -n "$vc" ] && [ "$vc" -lt "$APK_VC" ] && [ "$vc" -gt "$PREV_VC" ]; then
            PREV="$f"
            PREV_VC="$vc"
        fi
    done
fi

if [ -z "$PREV" ]; then
    report SKIP artifact.delta "в dist/ нет APK с versionCode < ${APK_VC:-?}"
else
    PREV_SIZE=$(stat -c %s "$PREV")
    delta=$((APK_SIZE - PREV_SIZE))
    delta_pct=$(awk -v d="$delta" -v p="$PREV_SIZE" 'BEGIN{printf "%+.1f", d / p * 100}')

    # Сводка по компонентам (несжатые размеры из unzip -l): assets / arsc / dex / res / прочее.
    component_sizes() { # <apk>
        unzip -l "$1" | awk '
            $1 ~ /^[0-9]+$/ && NF >= 4 {
                name = $NF; size = $1
                if (name ~ /^assets\//)            c = "assets"
                else if (name == "resources.arsc") c = "arsc"
                else if (name ~ /\.dex$/)          c = "dex"
                else if (name ~ /^res\//)          c = "res"
                else                               c = "other"
                sum[c] += size; total += size
            }
            END {
                split("assets arsc dex res other", order, " ")
                for (i = 1; i <= 5; i++) printf "%s %d\n", order[i], sum[order[i]] + 0
                printf "total %d\n", total + 0
            }'
    }

    echo "       дельта к $PREV (versionCode $PREV_VC):"
    printf '       %-8s %12s %12s %12s\n' "" "$PREV_VC" "$APK_VC" "Δ"
    paste -d' ' <(component_sizes "$PREV") <(component_sizes "$APK") | \
    while read -r c old _ new; do
        printf '       %-8s %12d %12d %+12d\n' "$c" "$old" "$new" "$((new - old))"
    done
    printf '       %-8s %12d %12d %+12d (%s %%)\n' "APK" "$PREV_SIZE" "$APK_SIZE" "$delta" "$delta_pct"
    report PASS artifact.delta "предыдущий — $(basename "$PREV") (vc $PREV_VC), APK $delta Б ($delta_pct %)"
fi

# --- 9. итог -----------------------------------------------------------------------------------

echo
echo "=== ИТОГ ==="
for r in "${RESULTS[@]}"; do
    IFS='|' read -r status name detail <<<"$r"
    printf 'RESULT|%s|%s|%s\n' "$status" "$name" "$detail"
done

if [ "$FAILURES" -eq 0 ]; then
    echo "OVERALL|PASS|$APK"
    exit 0
fi
echo "OVERALL|FAIL|$APK|$FAILURES проваленных проверок"
exit 1
