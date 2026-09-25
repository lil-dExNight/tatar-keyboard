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

resolve_tool() { # <имя>
    local found
    found=$(find "$SDK_ROOT/build-tools" -maxdepth 2 -name "$1" -type f | sort -V | tail -1)
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
