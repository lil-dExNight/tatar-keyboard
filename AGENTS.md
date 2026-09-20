# AGENTS.md — руководство для агентных сессий

Проект: офлайн Android-клавиатура с татарской раскладкой (форк Simple Keyboard /
AOSP LatinIME; Java-наследие + новый код на Kotlin). package `org.tatarkeyboard.ime`,
namespace `rkr.simplekeyboard.inputmethod`. Ветка работы — `main`.

## Команды

| Действие | Команда |
|---|---|
| JVM-тесты (1235 шт.) | `./gradlew test` (для честного прогона — `--rerun-tasks`) |
| Python-тесты конвейера (474 шт.) | `for f in tests/*/test_*.py; do python3 "$f" \|\| exit 1; done` — pytest НЕ используется, это чистый unittest |
| Instrumentation на устройстве (E3b/Phase B) | `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest` → `adb install -r` обоих APK → `adb shell am instrument -w -e class rkr.simplekeyboard.inputmethod.latin.dictionary.engine.E3bComputeInstrumentationTest org.tatarkeyboard.ime.debug.test/android.test.InstrumentationTestRunner` (пакет тестового APK — с суффиксом `.test`; legacy-раннеру нужен `<uses-library android:name="android.test.runner">` в `app/src/androidTest/AndroidManifest.xml`, уже есть). Меряет обе fuzzy-политики (DEFAULT/TATAR, Phase C = {1,4} probe-first) на 22 обзорных префиксах и опечаточных пробах (включая 10-cp «сцләмәтлек» — 380 проб) + дампит живую геометрическую карту соседей (32 пары) для сверки с офлайн-моделью `typo_pack.py`. Прогнан на POCO C71 2026-09-20 (логи — `build/tt-typo-next-phaseB/`, Phase B и Phase C) |
| Эмуляторный смоук (DEV-3) | `bash scripts/emulator-smoke.sh [--avd tt_suggest_a14] [--apk путь] [--no-boot] [--outdir build/emulator-smoke/]` — поднять AVD → установить APK → выбрать IME → сценарий (набор tt/ru/en, подсказки, эмодзи-панель, crash-буфер; TT-SUGGESTIONS P5: two word-form probes — type татар/сакчы + space on the tt layout, tap the middle suggestion cell, read the try-it field; TT-TYPO-NEXT Phase A: a second cell-2 tap after the сакчы probe proves predictions follow an accepted suggestion without a keystroke) → строки `RESULT\|PASS/FAIL/SKIP`, свидетельства (скриншоты, дампы) в outdir. Координаты клавиш откалиброваны под 1080×2280; пиксельная дельта полосы подсказок требует ImageMagick на хосте (без него — SKIP) |
| Линт | `./gradlew lintRelease` (baseline `app/lint-baseline.xml` — 0 errors, 31 осознанный warning после P1 (5x IconLauncherShape сняты: иконки конвертированы в lossless WebP, детектор WebP не анализирует), классификация в `app/lint.xml`; `abortOnError=true`) |
| error-prone (DEV-4) | встроен в компиляцию Java (плагин net.ltgt.errorprone 4.3.0 + error_prone_core 2.42.0 — последняя на JDK 17; build-time, в APK не попадает). Все находки — warnings (`allErrorsAsWarnings`), сборку не роняют; новые стоит разбирать по мере появления в выводе `compile*JavaWithJavac` |
| Release APK | `bash scripts/release_pack.sh [путь-результата]` (SIZE-3: unsigned `assembleRelease -PskipReleaseSigning` → `zipalign -z` (zopfli, строго ДО подписи) → `apksigner sign` v2-only ключом из `keystore.properties`; детерминирован при пиннованных build-tools). Голый `./gradlew assembleRelease` тоже валиден (подпись при наличии `keystore.properties`), но без zopfli-перепаковки |
| Гейт «без INTERNET + backup-whitelist» | `bash scripts/check-no-internet.sh [путь-к-apk]` (по умолчанию debug APK; два уровня: манифест + aapt2) |
| Релизный автомат (DEV-6) | `bash scripts/release_check.sh [--quick\|--full] [путь-к-apk]` (по умолчанию свежий release APK): гейты + размер + пины ассетов + разрешения + подпись + версия + changelog + дельта к dist/, итог машинным блоком `RESULT\|…` |
| Воспроизводимость сборки (DEV-2) | два `clean assembleRelease` обязаны давать побайтно одинаковый APK; в CI — джоба `reproducible` (`cmp` двух unsigned-прогонов). Недетерминизм был в Dependency Info Block (эфемерный ключ AGP) — отключён `dependenciesInfo { includeInApk = false }` в `app/build.gradle`; заново не включать |
| Генерация baseline-профиля | `./gradlew :app:generateReleaseBaselineProfile` (нужен запущенный эмулятор) |

Гейты обязательны после любой правки кода/ресурсов: JVM + python + lintRelease +
check-no-internet (исходник и собранный APK) + размер release APK ≤ 3 145 728 Б.

Эмуляторные AVD: `tatar_e5_test`, `tt_prefix3`, `tt_suggest_a14`
(`~/Android/Sdk/emulator/emulator -avd <имя> -no-window &`, adb в
`~/Android/Sdk/platform-tools/`).

## Жёсткие ограничения (нарушать нельзя)

- **Ноль сторонних runtime-зависимостей.** Dev/build-time инструменты (lint,
  detekt, Macrobenchmark) допустимы, в APK попадать не должны.
- **Без `android.permission.INTERNET`** — проверяется гейтом в CI трижды.
- **Без NDK/C++**, без Compose в IME-процессе (Compose допустим только в
  Activity настроек), без сторонних звуков/шрифтов Apple.
- Бюджеты: APK ≤ 3 МБ, холодный старт < 400 мс, ноль аллокаций в цикле отрисовки.
- Локали и раскладки — только tt/ru/en (срезано осознанно в кампании
  реструктуризации; `resConfigs "tt","ru","en"`).

## Ассеты и пины

Словари (`*.tdict.zlib`) и таблицы биграмм (`*.tatbigr.zlib`) собираются
python-конвейером (`scripts/`), а не руками. Их размеры и SHA-256 **запинены**
в `app/src/main/java/.../dictionary/storage/DictionaryStorageContracts.kt` и
`BigramStorageContracts.kt` — любая пересборка ассета требует пересчёта пинов,
иначе красные тесты.

Эмодзи-ассеты (`assets/emoji/*.txt`, включая `emoji_suggest_v1.txt` — таблицу
«слово → эмодзи» для подсказок в полосе, 1.9.10) контрактов хранилища не имеют
и в гейт пинов `release_check.sh` не входят: их пины (SHA-256, числа записей)
живут в `tests/emoji_*` (python) и JVM-контрактах `Emoji*AssetTest` — правка
данных без пересчёта пина даёт красные тесты там.

С 2026-09-01 (миссия SIZE-1, `docs/SIZE-SCHEMA2.md`) словари — **TATDICT
schema 2**: блочный front-coding (K = 8) + u8-длины + varint-частоты, lossless
к schema 1. Читатель schema 1 удалён; `dictionary_pack.py build`/`repack` пишут
schema 2 по умолчанию (`--schema 1` оставлен для справки и golden-тестов).
Эквивалентность доказывается `scripts/schema2_equivalence_check.py`.

Тем же днём (миссия SIZE-2, `docs/SIZE-SCHEMA3.md`) таблицы биграмм —
**TATBIGR schema 3**: собственных слов нет, головы — дельта-varint индексов
в словарь (блочный индекс по 64), преемники — varint-индексы в словарь,
диапазоны — u8-счётчики. Таблица связана со словарём по raw SHA-256 (заголовок
+ пин `expectedDictionaryRawSha256`); валидатор, читатель и
`rebuild_assets.py --check` проверяют связку fail-closed. Читатель schema 2
удалён; `bigram_asset_pack.py pack` пишет schema 3 по умолчанию (`--schema 2` —
для golden/истории), корпусо-независимый перевод — `bigram_asset_pack.py repack`.
Эквивалентность доказывается `scripts/schema3_equivalence_check.py`.

Единый вход пересборки — **`scripts/rebuild_assets.py`** (DEV-1): одна команда
делает словари → обе таблицы биграмм → пересчёт пинов → проверку, поэтому забытой
второй половины больше не бывает (исторический источник багов — см. `HANDOFF.md`).
С 2026-09-20 (TT-SUGGESTIONS P2, `docs/TT-SUGGESTIONS.md`) татарский словарь перед
сборкой проходит стадию словоформ (`scripts/wordform_gen.py`, допуск по корпусной
засвидетельствованности, отсечка 110 000 — константа `TATAR_DICTIONARY_TOP`), а режим
`--only tatar|russian` пересобирает одну сторону с гарантией побайтной неизменности
другой (снимок SHA-256 до/после).
Проверка согласованности без пересборки (нужен в CI и перед релизом):
`python3 scripts/rebuild_assets.py --check --allow-known-drift` — сверяет ассеты
с пинами, головы таблиц — со словарями; известные расхождения пинутся
по точным числам в `scripts/known_asset_drift.json` (сейчас там только
правило-генераторные остатки: русская 2/0 — «окей» и «берегись» без пар в
прореженном разговорном входе, см. `docs/CORPUS-CONVERSATIONAL-RU.md`;
татарская 3/0 — деепричастия на -гәнчә; дрейф русской таблицы
4 195/4 195 закрыт перепаковкой в 1.9.7, см. `docs/RUSSIAN-BIGRAMS-REPACK.md`).

## Дисциплина документов и коммитов

- Историю git и содержимое завершённых отчётов не переписываем; уточнения —
  датированными сносками. Живые документы — наверху `docs/` (индекс:
  `docs/README.md`), архив миссий — `docs/archive/`.
- Коммиты пишет только оператор; сторонние трейлеры соавторства запрещены.
- Актуальное состояние проекта — корневой `HANDOFF.md`; планы работ —
  `docs/RESTRUCTURE-PLAN.md` (выполнен) и `docs/DEV-PLAN.md` (tooling).
- Коммиты по-русски, раздельные по смыслу. Push, теги наружу, публикация —
  только по явной команде оператора.
- `dist/` локален и не коммитится; `keystore.properties` и
  `tatar-keyboard-release.jks` — секреты, в git им пути нет.

## Структура кода (кратко)

- `latin/LatinIME.java` — InputMethodService, точка входа IME.
- `keyboard/` (Java) — View, PointerTracker, KeyDetector; `latin/suggestions/`,
  `latin/dictionary/**`, `latin/emoji/` (Kotlin) — подсказки, словари, эмодзи.
- `app/src/test` — 1235 JVM-тестов (JUnit 4, Robolectric нет — осознанно).
- `scripts/` — python-конвейер ассетов (stdlib only, fail-closed);
  `research/corpus/` — измерительные скрипты и манифесты корпусов (данные OPUS
  не коммитятся — лицензии).
