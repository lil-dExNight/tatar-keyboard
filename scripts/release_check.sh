#!/usr/bin/env bash
# Release checker: runs the repository gates and the artifact checks on a release
# candidate in one command. Each check prints PASS/FAIL/SKIP; the summary is a
# machine-readable RESULT block, and any FAIL gives a non-zero exit code. Any
# unexpected error (no aapt2, broken APK, missing contract) also exits non-zero.
# Artifact checks: size, asset pins, bundled asset sets, dex layout, profile rules, permissions,
# signature, version, store changelog and the delta against dist/.
#
# Run from the repository root:
#   bash scripts/release_check.sh [--quick|--full|--checks LIST] [path/to.apk]
#
# Modes:
#   (default)  the APK is already built; gates run, nothing is rebuilt;
#   --quick    artifact checks only (gradle/python gates are reported as SKIP);
#   --full     ./gradlew clean assembleRelease --no-build-cache first, then everything else
#              (checks the freshly built app/build/outputs/apk/release/*.apk);
#   --checks   only the listed artifact checks, comma-separated names without the "artifact."
#              prefix (accepted too, it is stripped; CI: exported_surface,no_secrets);
#              selectable: size up to signature.
#
# Default APK: the newest (by mtime) app/build/outputs/apk/release/*.apk.
# The script writes only build output: logs go to build/release_check/, and --full
# also rebuilds app/build/.
set -euo pipefail

# --- release invariants ----------------------------------------------------------------------

# APK size ceiling (3 MB), in bytes.
APK_SIZE_LIMIT=3145728

# SHA-256 of the release signing certificate (CN=Tatar Keyboard), the same for every release.
RELEASE_CERT_SHA256="98ca6febfed6c146d81c1fdcfe52c79acf7aa926a1033d98b844a59803ec42ad"

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd -- "$SCRIPT_DIR/.." && pwd)
cd "$REPO_ROOT"

LOG_DIR="build/release_check"
mkdir -p "$LOG_DIR"

# --- arguments -------------------------------------------------------------------------------

QUICK=0
FULL=0
CHECKS=""
APK=""
# Artifact checks that --checks can select; each runs on the APK alone. Version, changelog and
# delta depend on each other and run only without --checks.
SELECTABLE_CHECKS="size asset_pins emoji_assets tree_assets critical_resources arsc_stored dex_layout profiles permissions exported_surface no_secrets signature"

# Prints the header comment (lines 2-22); keep the header exactly that long.
usage() {
    sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'
}

while [ "$#" -gt 0 ]; do
    arg="$1"; shift
    case "$arg" in
        --quick) QUICK=1 ;;
        --full)  FULL=1 ;;
        --checks)
            if [ "$#" -eq 0 ] || [ -z "$1" ]; then
                echo "ERROR: --checks требует список проверок" >&2; exit 2
            fi
            CHECKS="$1"; shift
            ;;
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
if [ -n "$CHECKS" ] && { [ "$QUICK" -eq 1 ] || [ "$FULL" -eq 1 ]; }; then
    echo "ERROR: --checks несовместим с --quick и --full" >&2; exit 2
fi
if [ -n "$CHECKS" ] && [ -z "${CHECKS//,/}" ]; then
    echo "ERROR: --checks требует хотя бы одну проверку" >&2; exit 2
fi
for check in ${CHECKS//,/ }; do
    case " $SELECTABLE_CHECKS " in
        *" ${check#artifact.} "*) ;;
        *) echo "ERROR: --checks: неизвестная проверка $check (доступны: $SELECTABLE_CHECKS)" >&2
           exit 2 ;;
    esac
done

# want <check>: true when the artifact check runs (no --checks, or listed in it; the
# "artifact." prefix in a --checks name is optional). The check sections below are wrapped
# in `if want ...; then` without extra indentation, because their Python heredocs cannot be
# indented.
want() {
    [ -z "$CHECKS" ] || [[ ",$CHECKS," == *",$1,"* || ",$CHECKS," == *",artifact.$1,"* ]]
}

# --- result bookkeeping ----------------------------------------------------------------------

RESULTS=()
FAILURES=0

# report <PASS|FAIL|SKIP> <check-name> [detail]
report() {
    local status="$1" name="$2" detail="${3:-}"
    RESULTS+=("$status|$name|$detail")
    printf '  %-4s  %s%s\n' "$status" "$name" "${detail:+ — $detail}"
    if [ "$status" = "FAIL" ]; then
        FAILURES=$((FAILURES + 1))
    fi
}

# run_logged <log-file> <command...>: all output goes to the log, the exit code is returned.
run_logged() {
    local log="$1"; shift
    if "$@" >"$log" 2>&1; then
        return 0
    fi
    return 1
}

# --- SDK tools (aapt2, apksigner) ------------------------------------------------------------

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
if [ ! -d "$SDK_ROOT/build-tools" ]; then
    echo "ERROR: Android SDK build-tools не найдены ($SDK_ROOT/build-tools);" >&2
    echo "       задайте ANDROID_HOME или ANDROID_SDK_ROOT" >&2
    exit 1
fi

# Pinned build-tools version.
source "$SCRIPT_DIR/build-tools-pin.sh"

resolve_tool() { # <name>
    local found
    # Read-only use (aapt2 dump / apksigner verify): prefer the pinned $TT_BUILD_TOOLS_PIN
    # directory, else fall back to the newest installed version. Dump/verify output is stable
    # across versions and no APK bytes are produced here (unlike release_pack.sh, which
    # forbids the fallback).
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

# --- --full: build from scratch before all checks --------------------------------------------

echo "== release_check: кандидат и гейты =="

if [ "$FULL" -eq 1 ]; then
    # `gradlew clean` deletes the root build/ together with LOG_DIR, so clean separately
    # and recreate the log directory before building (as release_pack.sh does).
    ./gradlew clean --console=plain >/dev/null 2>&1
    mkdir -p "$LOG_DIR"
    if run_logged "$LOG_DIR/assemble-release.log" ./gradlew assembleRelease --no-build-cache --console=plain; then
        report PASS build.assemble_release "clean assembleRelease, лог $LOG_DIR/assemble-release.log"
    else
        report FAIL build.assemble_release "сборка упала, лог $LOG_DIR/assemble-release.log"
        tail -20 "$LOG_DIR/assemble-release.log" >&2 || true
    fi
fi

# --- APK selection ----------------------------------------------------------------------------

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

# --- 1. gates ---------------------------------------------------------------------------------

if [ -n "$CHECKS" ]; then
    : # --checks runs the listed artifact checks only
elif [ "$QUICK" -eq 1 ]; then
    report SKIP gates.gradle_test "--quick"
    report SKIP gates.lint_release "--quick"
    report SKIP gates.python_tests "--quick"
    report SKIP gates.no_internet "--quick"
    report SKIP gates.asset_rebuild_check "--quick"
else
    # JVM tests; counts come from the JUnit XML reports (app/build/test-results/*/).
    # --rerun-tasks forces a real run instead of an up-to-date skip. calibrationTest carries
    # the suites the default test tasks exclude (app/build.gradle).
    if run_logged "$LOG_DIR/gradle-test.log" ./gradlew test calibrationTest --rerun-tasks --console=plain; then
        sum_attr() { # <attribute>
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

    # lintRelease against the baseline (abortOnError=true).
    if run_logged "$LOG_DIR/lint-release.log" ./gradlew lintRelease --console=plain; then
        report PASS gates.lint_release "baseline без новых ошибок"
    else
        report FAIL gates.lint_release "упал, лог $LOG_DIR/lint-release.log"
        tail -20 "$LOG_DIR/lint-release.log" >&2 || true
    fi

    # Pipeline Python tests through the shared runner (parallel, one unittest file per process).
    if run_logged "$LOG_DIR/python-tests.log" bash scripts/run_python_tests.sh; then
        py_total=$(grep -oE 'Ran [0-9]+ tests' "$LOG_DIR/python-tests.log" | grep -oE '[0-9]+' | awk '{s+=$1} END{print s+0}')
        report PASS gates.python_tests "$py_total тестов в $(ls tests/*/test_*.py | wc -l) файлах"
    else
        report FAIL gates.python_tests "падения, лог $LOG_DIR/python-tests.log"
        tail -20 "$LOG_DIR/python-tests.log" >&2 || true
    fi

    # No INTERNET + backup whitelist on the APK under test (both levels of the check).
    if run_logged "$LOG_DIR/no-internet.log" bash scripts/check-no-internet.sh "$APK"; then
        report PASS gates.no_internet "оба уровня (манифест + aapt2), лог $LOG_DIR/no-internet.log"
    else
        report FAIL gates.no_internet "гейт упал, лог $LOG_DIR/no-internet.log"
        cat "$LOG_DIR/no-internet.log" >&2 || true
    fi

    # Assets against their pins and bigram table heads against the dictionaries, through the
    # same entry point CI uses, not only indirectly through the pins extracted from the APK below.
    if run_logged "$LOG_DIR/asset-rebuild-check.log" \
            python3 scripts/rebuild_assets.py --check --allow-known-drift; then
        report PASS gates.asset_rebuild_check "пины и связки согласованы, лог $LOG_DIR/asset-rebuild-check.log"
    else
        report FAIL gates.asset_rebuild_check "расхождение, лог $LOG_DIR/asset-rebuild-check.log"
        tail -20 "$LOG_DIR/asset-rebuild-check.log" >&2 || true
    fi
fi

echo "== артефактные проверки =="

# --- 2. APK size against the ceiling -----------------------------------------------------------
if want size; then
APK_SIZE=$(stat -c %s "$APK")
if [ "$APK_SIZE" -le "$APK_SIZE_LIMIT" ]; then
    headroom=$(awk -v s="$APK_SIZE" -v lim="$APK_SIZE_LIMIT" 'BEGIN{printf "%.1f", (lim - s) / lim * 100}')
    report PASS artifact.size "$APK_SIZE Б при потолке $APK_SIZE_LIMIT Б, запас $headroom %"
else
    report FAIL artifact.size "$APK_SIZE Б превышает потолок $APK_SIZE_LIMIT Б"
fi
fi

# --- 3. asset pins against the constants in code ----------------------------------------------
# Extracts *.tdict.zlib / *.tatbigr.zlib from the APK and compares size and SHA-256 (compressed
# and raw) with the constants in DictionaryStorageContracts.kt / BigramStorageContracts.kt.
# The pins are read with the same regex approach as read_pins in scripts/rebuild_assets.py,
# but self-contained, so this check does not depend on the pipeline being importable.
if want asset_pins; then
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
fi

# --- 3b. emoji assets: APK against the tree ------------------------------------------------------
# Emoji assets are plain text (no zlib wrapper), so their pin is the file in the tree: the APK
# content must be byte-identical to what is committed. The file SET is compared, not a
# hard-coded list, so a file present only in the tree or only in the APK also fails.
if want emoji_assets; then
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
fi

# --- 3c. dictionaries and bigrams: APK against the tree ------------------------------------------
# Same rules as for emoji: the file SET under assets/dictionaries/ and assets/bigrams/ in both
# directions (including NOTICE.txt and the sentence-start tables) plus byte-identical content,
# because the engine reads these directories as a whole. artifact.asset_pins above stays a
# separate check: it also compares the decompressed content with the constants in code.
if want tree_assets; then
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
fi

# --- 3d. critical resources: keep.xml really covers the live names in the APK -------------------
# shrinkResources relies on app/src/main/res/raw/keep.xml, because the resource families
# keyboard_layout_set_*/kbd_*/rows_*/rowkeys_*/row_* and the strings locale_name_*/label_* are
# looked up by name (KeyboardLayoutSet builds "keyboard_layout_set_" +
# subtype.getKeyboardLayoutSet() for getIdentifier; KeyboardTextsSet resolves
# label_pause_key/label_wait_key; LocaleResourceUtils resolves locale_name_*). If keep.xml stops
# matching the real names (renamed file, lost glob), shrinking drops the resource and only a run
# on a device shows it. The check lists the concrete names covered by the keep.xml families from
# the TREE and requires each one in `aapt2 dump resources` of the candidate. There is no reverse
# direction (dump -> tree) on purpose: the dump includes framework android:* entries.
# A missing keep.xml also fails.
if want critical_resources; then
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

# The xml families come from keep.xml itself (@xml/<prefix>* entries) and must equal a fixed
# list, so a keep.xml edit that loses a family fails instead of silently checking fewer names.
EXPECTED_XML_FAMILIES = ["keyboard_layout_set_", "kbd_", "rows_", "rowkeys_", "row_"]
xml_families = [p[len("@xml/"):-1] for p in keep_pats
                if p.startswith("@xml/") and p.endswith("*")]
if sorted(xml_families) != sorted(EXPECTED_XML_FAMILIES):
    raise SystemExit(f"ERROR: xml-семейства в {KEEP}: {sorted(xml_families)}, "
                     f"ожидались {EXPECTED_XML_FAMILIES}")

expected = {}  # "type/name" -> where it comes from in the tree

def want(kind, name, origin):
    expected.setdefault(f"{kind}/{name}", origin)

# Concrete xml resources: files in ALL res/xml*/ configurations whose names match the keep.xml
# families (resource name = file name without extension).
for d in sorted(RES.glob("xml*")):
    if not d.is_dir():
        continue
    for f in sorted(d.glob("*.xml")):
        if any(f.stem.startswith(pref) for pref in xml_families):
            want("xml", f.stem, str(f))

# Strings that KeyboardTextsSet resolves by hard-coded name must be defined in
# strings-action-keys.xml; if not, the tree itself is broken.
ACTION_STRINGS = RES / "values" / "strings-action-keys.xml"
if not ACTION_STRINGS.is_file():
    raise SystemExit(f"ERROR: нет {ACTION_STRINGS}")
action_text = ACTION_STRINGS.read_text(encoding="utf-8")
for name in ("label_pause_key", "label_wait_key"):
    if not re.search(rf'<string\b[^>]*\bname="{name}"', action_text):
        raise SystemExit(f"ERROR: строка {name} не определена в {ACTION_STRINGS}")
    want("string", name, str(ACTION_STRINGS))

# Every locale_name_* string under res/values*/ (LocaleResourceUtils resolves them by locale
# name). The "locale_name_<locale>" placeholder lives in an XML comment in donottranslate.xml,
# so only real <string ... name="..."> elements are matched.
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
fi

# --- 3.9. resources.arsc must be STORED (otherwise Android 11+ will not install the APK) -------
# An app targeting SDK 30+ with a compressed arsc fails to install:
#   Failure [-124: ... Targeting R+ (version 30 and above) requires the resources.arsc of
#   installed APKs to be stored uncompressed and aligned on a 4-byte boundary]
# `zipalign -c 4` does not catch it (prints "OK - compressed" and exits 0), so the artifact is
# checked directly: STORED only, with a data offset that is a multiple of 4.
if want arsc_stored; then
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
fi

# --- 3.10. dex layout: startup classes.dex plus classes2.dex, and the baseline profile ----------
# The code fits one dex, so a second dex appears only when AGP applied the startup profile from
# app/src/main/generated/baselineProfiles/. A single classes.dex means the profile was not read.
if want dex_layout; then
DEX_LOG="$LOG_DIR/dex-layout.log"
if python3 - "$APK" >"$DEX_LOG" 2>&1 <<'PYEOF'
import re
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as zf:
    names = set(zf.namelist())
dexes = sorted(n for n in names if re.fullmatch(r'classes\d*\.dex', n))
if 'classes.dex' not in dexes or 'classes2.dex' not in dexes:
    raise SystemExit('ожидались classes.dex и classes2.dex (стартовый профиль), в APK: %s'
                     % (', '.join(dexes) or 'нет dex'))
if 'assets/dexopt/baseline.prof' not in names:
    raise SystemExit('нет assets/dexopt/baseline.prof')
print('%s + assets/dexopt/baseline.prof' % ', '.join(dexes))
PYEOF
then
    report PASS artifact.dex_layout "$(tail -1 "$DEX_LOG")"
else
    report FAIL artifact.dex_layout "$(tail -1 "$DEX_LOG")"
fi
fi

# --- 3.11. profile rules against the dex ---------------------------------------------------------
# The tracked text profiles are hand-editable, and a typo'd rule silently becomes a dead rule.
# scripts/check_profiles.py parses both profiles strictly, resolves the class rules through the
# R8 mapping against the APK's dex and gates the resolved ratio against a pinned floor (the trend
# is the staleness signal); with the SDK cmdline-tools present it also runs profgen validate.
# The mapping and the APK must come from the same build: the default flow (newest built APK) and
# --full guarantee it, a --quick run on an older APK may not.
if want profiles; then
PROFILES_LOG="$LOG_DIR/profiles.log"
if run_logged "$PROFILES_LOG" python3 scripts/check_profiles.py --apk "$APK"; then
    report PASS artifact.profiles "$(tail -1 "$PROFILES_LOG" | sed 's/^RESULT|[A-Z]*|[a-z]*|//')"
else
    report FAIL artifact.profiles "гейт профилей упал, лог $PROFILES_LOG"
    cat "$PROFILES_LOG" >&2
fi
fi

# --- 4. permissions: exactly VIBRATE -----------------------------------------------------------
if want permissions; then
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
fi

# --- 4b. exported surface: exact match with the golden set -------------------------------------
# The APK manifest is the merged+built one, so drift can enter through build config, not only
# source edits. Parse `aapt2 dump xmltree` into component records (kind, name, permission
# guard, intent-filter actions/categories) and require the EXPORTED set to equal the embedded
# golden set in both directions. The IME service's exported=false + BIND_INPUT_METHOD pair is
# checked separately: an IME service exported without that guard is the main risk here.
if want exported_surface; then
EXPORTED_LOG="$LOG_DIR/exported-surface.log"
if python3 - "$APK" "$AAPT2" >"$EXPORTED_LOG" 2>&1 <<'PYEOF'
import re
import subprocess
import sys

apk_path, aapt2 = sys.argv[1], sys.argv[2]

COMPONENT_KINDS = ("activity", "activity-alias", "service", "receiver", "provider")

# Golden exported surface, matching app/src/main/AndroidManifest.xml (no dependencies merge
# anything in):
#   activity rkr.simplekeyboard.inputmethod.latin.setup.SetupActivity
#       exported, no permission guard, filter MAIN + category LAUNCHER: the launcher entry
#       point (onboarding), must stay reachable.
#   activity rkr.simplekeyboard.inputmethod.latin.settings.SettingsActivity
#       exported, no permission guard, no filter: the settings screen, opened by explicit
#       intents (system IME settings, SetupActivity).
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
        # Platform rule for a missing attribute: a component with an intent-filter is
        # exported (targetSdk 37 already requires an explicit exported at build time; this
        # is the safe fallback). Treat it as exported rather than skipping it.
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
fi

# --- 4c. secrets scan: tracked repository files + APK entries ------------------------------------
# Two halves, each listing offenders:
# (a) repo: `git ls-files` (tracked files only, so the gitignored local keystore.properties
#     and .jks do not trip the check), scanned for secret filenames
#     (*.jks, *.keystore, keystore.properties, *.pem, *.p12), private-key block
#     headers, and common token shapes (GitHub PAT, AWS access key id, sk-tokens).
# (b) APK: no zip entry may carry a secret filename.
# The content regexes are written so this script's own text never matches them (the literal
# prefix is followed by '[', outside the accepted class). The check scans this tracked file
# on every run, so a self-match would fail permanently.
if want no_secrets; then
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
fi

# --- 5. signature: release key certificate, exactly one signer -----------------------------------
if want signature; then
if SIG=$("$APKSIGNER" verify --print-certs "$APK" 2>&1); then
    # There must be exactly one signer. Each signer prints a
    # "... certificate SHA-256 digest: <hash>" line (with several signers: "V2 Signer #1/#2: ...");
    # head -1 would silently accept an APK with an extra foreign key. One key signing with two
    # schemes prints the same digest twice, which is still one signer, so count distinct
    # digests, not lines.
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
fi

# --- 6. version: aapt2 badging against app/build.gradle ----------------------------------------
if [ -z "$CHECKS" ]; then
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

# --- 7. store changelog metadata/en-US/changelogs/<versionCode>.txt ----------------------------

CHANGELOG="metadata/en-US/changelogs/${APK_VC:-$EXPECTED_VC}.txt"
if [ -n "$APK_VC" ] && [ -f "$CHANGELOG" ]; then
    report PASS artifact.changelog "$CHANGELOG на месте ($(wc -c <"$CHANGELOG") Б)"
else
    report FAIL artifact.changelog "нет $CHANGELOG"
fi

# --- 8. delta against the previous release in dist/ ---------------------------------------------
# Previous = the dist/ APK with the highest versionCode strictly below the candidate's.
# Informational only: artifact.size enforces the size budget.

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

    # Per-component summary (uncompressed sizes from unzip -l): assets / arsc / dex / res / other.
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
fi

# --- 9. summary --------------------------------------------------------------------------------

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
