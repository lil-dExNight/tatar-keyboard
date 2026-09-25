#!/usr/bin/env bash
# SIZE-3: релизная упаковка с zopfli-рекомпрессией (docs/SIZE-OPTIMIZATION-RESEARCH.md,
# упаковочный уровень: zipalign -z даёт ~40 КБ). Порядок обязателен: zipalign ДО
# подписи (подпись v2 покрывает байты zip-записей — перепаковка подписанного APK
# её инвалидирует).
#
# O2 (2026-09-25, docs/OPTIMIZE-2026-09-25.md): resources.arsc deflate-ится ДО zipalign —
# AGP хранит arsc в STORED ради mmap, но он стоит ~100 КБ несжатых байт; после deflate
# (−71 КБ) читатель грузит его в память, что и меряется на устройстве (гейт холодного
# старта POCO C71). Выравнивание на этом шаге не нужно нарочно: zipalign -z после него
# раскладывает записи заново (включая 4-байтовое выравнивание оставшихся STORED), а
# zipalign -c это проверяет перед подписью.
#
# Пайплайн одной командой:
#   1. ./gradlew clean assembleRelease -PskipReleaseSigning  → unsigned APK
#   2. python3 (stdlib)                                      → resources.arsc: STORED→DEFLATED
#   3. zipalign -f -z 4                                      → zopfli-рекомпрессия + выравнивание
#   4. zipalign -c                                           → выравнивание сохранено
#   5. apksigner sign (ключи из keystore.properties, v2-only — как у AGP-сборки)
#   6. apksigner verify --print-certs                        → подпись валидна
#
# Запуск из корня репозитория:
#   bash scripts/release_pack.sh [путь-результата.apk]
# По умолчанию результат — app/build/outputs/apk/release/app-release-zopfli.apk.
#
# Воспроизводимость (DEV-2) сохраняется: и AGP-сборка unsigned, и zopfli (при
# пиннованной версии build-tools — resolve_tool берёт старшую установленную), и
# apksigner v2 детерминированы; два прогона одного дерева дают одинаковый SHA-256.
# Скрипт ничего не меняет в репозитории, кроме build/ и app/build/.
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd -- "$SCRIPT_DIR/.." && pwd)
cd "$REPO_ROOT"

LOG_DIR="build/release_pack"
mkdir -p "$LOG_DIR"

# T8 (стадия D, docs/ROADMAP-P8-PLAN.md): --no-sign доводит пайплайн до выровненного
# НЕподписанного APK и останавливается. Это ровно та часть, детерминизм которой раньше не
# проверялся в CI (у CI нет keystore.properties): джоба reproducible теперь прогоняет упаковку
# дважды и сверяет байты. Локальный релизный ритуал по-прежнему идёт полным путём с подписью.
NO_SIGN=0
if [ "${1:-}" = "--no-sign" ]; then
    NO_SIGN=1
    shift
fi

OUT="${1:-app/build/outputs/apk/release/app-release-zopfli.apk}"

# Аудит 2026-09-25: выходной путь не должен оказаться симлинком (apksigner писал бы по его
# цели — потенциально чужому файлу) или осиротевшим файлом прошлого прогона, который при
# падении середины пайплайна можно принять за свежий результат. Стираем заранее: дальше
# каждый шаг либо пишет OUT заново, либо валится — ложного «готового» APK не остаётся.
if [ -L "$OUT" ] || [ -e "$OUT" ]; then
    rm -f -- "$OUT"
fi

# --- инструменты SDK (zipalign, apksigner) ---------------------------------------------------

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
if [ ! -d "$SDK_ROOT/build-tools" ]; then
    echo "ERROR: Android SDK build-tools не найдены ($SDK_ROOT/build-tools);" >&2
    echo "       задайте ANDROID_HOME или ANDROID_SDK_ROOT" >&2
    exit 1
fi

resolve_tool() { # <имя>
    local found
    # T6 (docs/SECURITY-AUDIT-2026-09-25.md), закрыт в стадии D docs/ROADMAP-P8-PLAN.md:
    # раньше здесь бралась СТАРШАЯ установленная версия build-tools, то есть результат упаковки
    # менялся от установки нового SDK-пакета — а APK обязан быть побайтно воспроизводимым.
    # Теперь версия запиннована; её можно переопределить переменной окружения (для проверки
    # обновления), но по умолчанию инструмент берётся только из пиннованного каталога и
    # отсутствие каталога — это громкая ошибка, а не молчаливый откат на другую версию.
    local pinned_dir="$SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION"
    if [ ! -d "$pinned_dir" ]; then
        echo "ERROR: запиннованные build-tools $BUILD_TOOLS_VERSION не найдены ($pinned_dir)." >&2
        echo "       Установите их (sdkmanager \"build-tools;$BUILD_TOOLS_VERSION\")" >&2
        echo "       или задайте TT_BUILD_TOOLS_VERSION=<версия> осознанно." >&2
        exit 1
    fi
    found=$(find "$pinned_dir" -maxdepth 2 -name "$1" -type f | sort -V | tail -1)
    if [ -z "$found" ]; then
        echo "ERROR: $1 не найден под $pinned_dir" >&2
        exit 1
    fi
    printf '%s' "$found"
}

# Пин версии build-tools: 37.0.0 — та, на которой собран и проверен релиз 3.1.0
# (docs/APK-AUDIT-3.1.0.md). Обновление версии — осознанный шаг с пересчётом SHA-256 артефакта.
BUILD_TOOLS_VERSION="${TT_BUILD_TOOLS_VERSION:-37.0.0}"

ZIPALIGN=$(resolve_tool zipalign)
APKSIGNER=$(resolve_tool apksigner)
echo "build-tools: $BUILD_TOOLS_VERSION (пин; переопределяется TT_BUILD_TOOLS_VERSION)"
echo "zipalign:  $ZIPALIGN"
echo "apksigner: $APKSIGNER"

# --- ключи из keystore.properties (та же конвенция, что app/build.gradle) --------------------

KS_PROPS="keystore.properties"
if [ "$NO_SIGN" = 0 ] && [ ! -f "$KS_PROPS" ]; then
    echo "ERROR: нет keystore.properties — подписывать нечем (unsigned-сборка и так доступна через gradle)" >&2
    exit 1
fi

ks_prop() { # <ключ>
    [ -f "$KS_PROPS" ] || return 0
    grep -E "^$1=" "$KS_PROPS" | head -1 | cut -d= -f2-
}
KS_FILE=$(ks_prop storeFile)
KS_ALIAS=$(ks_prop keyAlias)
KS_STORE_PASS=$(ks_prop storePassword)
KS_KEY_PASS=$(ks_prop keyPassword)
if [ "$NO_SIGN" = 0 ] && { [ -z "$KS_FILE" ] || [ -z "$KS_ALIAS" ]; }; then
    echo "ERROR: в keystore.properties нет storeFile/keyAlias" >&2
    exit 1
fi
# storeFile относительный — резолвится от app/ (как file() в app/build.gradle).
case "$KS_FILE" in
    /*) ;;
    *)  KS_FILE="app/$KS_FILE" ;;
esac
if [ "$NO_SIGN" = 0 ] && [ ! -f "$KS_FILE" ]; then
    echo "ERROR: keystore не найден: $KS_FILE" >&2
    exit 1
fi

# --- 1. unsigned release ---------------------------------------------------------------------

echo "== 1/6 clean assembleRelease -PskipReleaseSigning =="
mkdir -p "$LOG_DIR"
./gradlew clean assembleRelease -PskipReleaseSigning --console=plain \
    >"$LOG_DIR/assemble.log" 2>&1 || {
        echo "ERROR: сборка упала, лог $LOG_DIR/assemble.log" >&2
        tail -20 "$LOG_DIR/assemble.log" >&2 || true
        exit 1
    }
# gradle clean стирает корневой build/ вместе с LOG_DIR — создаём заново.
mkdir -p "$LOG_DIR"

UNSIGNED="app/build/outputs/apk/release/app-release-unsigned.apk"
if [ ! -f "$UNSIGNED" ]; then
    echo "ERROR: $UNSIGNED не появился (skipReleaseSigning не сработал?)" >&2
    exit 1
fi

# --- 1.5. resources.arsc: STORED -> DEFLATED (O2) ---------------------------------------------
# Контент записей сохраняется побайтно (zipfile читает/пишет декомпрессированное содержимое;
# zipalign -z после этого шага заново сжимает zopfli и выравнивает). Fail-closed: в выходе
# arsc обязан оказаться DEFLATED, а число и имена записей — совпасть с входом.
echo "== 2/6 resources.arsc deflate =="
DEFLATED="$LOG_DIR/app-release-arsc-deflated.apk"
python3 - "$UNSIGNED" "$DEFLATED" <<'PYEOF'
import sys
import zipfile

src, dst = sys.argv[1], sys.argv[2]
zin = zipfile.ZipFile(src)
infos = zin.infolist()
names = [i.filename for i in infos]
# Аудит 2026-09-25: дубликаты имён — не «last wins», а стоп. Чтение ниже идёт по ZipInfo
# (запись за записью), поэтому битый вход с двумя одинаковыми именами перепаковался бы молча
# и не так, как читает его Android; такой APK здесь не родится.
if len(set(names)) != len(names):
    dups = sorted({n for n in names if names.count(n) > 1})
    raise SystemExit(f'duplicate zip entries in {src}: {dups}')
with zipfile.ZipFile(dst, 'w') as zout:
    for info in infos:
        # read(ZipInfo), не read(имя): чтение по имени при дубликатах отдаёт ПОСЛЕДНЮЮ
        # запись с этим именем, подменяя байты копируемой (last-wins).
        data = zin.read(info)
        method = (zipfile.ZIP_DEFLATED if info.filename == 'resources.arsc'
                  else info.compress_type)
        out = zipfile.ZipInfo(info.filename, date_time=info.date_time)
        out.compress_type = method
        out.external_attr = info.external_attr
        out.internal_attr = info.internal_attr
        out.create_system = info.create_system
        zout.writestr(out, data, compresslevel=9 if method == zipfile.ZIP_DEFLATED else None)
zcheck = zipfile.ZipFile(dst)
after = zcheck.infolist()
if [i.filename for i in after] != names:
    raise SystemExit('entry list changed')
# Полная посодержательная сверка, а не только arsc: каждая запись выхода обязана побайтно
# равняться СВОЕЙ записи входа (пары сопоставляются позиционно, чтение — по ZipInfo).
for before, written in zip(infos, after):
    if zcheck.read(written) != zin.read(before):
        raise SystemExit(f'entry content changed: {before.filename}')
arsc = zcheck.getinfo('resources.arsc')
if arsc.compress_type != zipfile.ZIP_DEFLATED:
    raise SystemExit('resources.arsc is not DEFLATED in the output')
print(f'arsc: {arsc.file_size} -> {arsc.compress_size} B; {len(infos)} entries verified')
PYEOF

# --- 2. zipalign -z (zopfli) -----------------------------------------------------------------

echo "== 3/6 zipalign -z (zopfli) =="
ALIGNED="$LOG_DIR/app-release-zopfli-aligned.apk"
"$ZIPALIGN" -f -z 4 "$DEFLATED" "$ALIGNED"

# --- 3. проверка выравнивания -----------------------------------------------------------------

echo "== 4/6 zipalign -c =="
if "$ZIPALIGN" -c 4 "$ALIGNED" >"$LOG_DIR/zipalign-check.log" 2>&1; then
    echo "  выравнивание OK"
else
    echo "ERROR: выравнивание сломано, лог $LOG_DIR/zipalign-check.log" >&2
    exit 1
fi

# --- 4. подпись (v2-only — как у AGP-сборки линейки с 2026-08-18) ------------------------------

if [ "$NO_SIGN" = 1 ]; then
    cp -- "$ALIGNED" "$OUT"
    SIZE_UNSIGNED=$(stat -c %s "$UNSIGNED")
    SIZE_OUT=$(stat -c %s "$OUT")
    SHA_OUT=$(sha256sum "$OUT" | awk '{print $1}')
    echo "== --no-sign: остановка после выравнивания =="
    echo "unsigned:        $SIZE_UNSIGNED Б"
    echo "aligned (zopfli): $SIZE_OUT Б"
    echo "sha256:          $SHA_OUT"
    echo "RESULT|OK|pack_unsigned|$OUT|$SIZE_OUT|$SHA_OUT"
    exit 0
fi

echo "== 5/6 apksigner sign =="
# Пароли — через env:-форму, а не pass: в argv: командная строка процесса видна
# любому пользователю хоста через ps (C1 аудита 2026-09-02), окружение — нет.
KS_STORE_PASS="$KS_STORE_PASS" KS_KEY_PASS="$KS_KEY_PASS" \
"$APKSIGNER" sign \
    --ks "$KS_FILE" --ks-key-alias "$KS_ALIAS" \
    --ks-pass env:KS_STORE_PASS --key-pass env:KS_KEY_PASS \
    --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled false \
    --out "$OUT" "$ALIGNED"

# --- 5. верификация ----------------------------------------------------------------------------

echo "== 6/6 apksigner verify =="
if ! "$APKSIGNER" verify --print-certs "$OUT" | tee "$LOG_DIR/verify.log"; then
    echo "ERROR: подпись не верифицируется" >&2
    exit 1
fi

SIZE_UNSIGNED=$(stat -c %s "$UNSIGNED")
SIZE_OUT=$(stat -c %s "$OUT")
SHA_OUT=$(sha256sum "$OUT" | awk '{print $1}')
echo
echo "RESULT|unsigned|$SIZE_UNSIGNED"
echo "RESULT|zopfli+signed|$SIZE_OUT"
printf 'RESULT|delta|%+d Б\n' "$((SIZE_OUT - SIZE_UNSIGNED))"
echo "RESULT|sha256|$SHA_OUT"
echo "RESULT|out|$OUT"
