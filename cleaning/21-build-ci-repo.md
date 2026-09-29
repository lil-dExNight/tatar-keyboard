# Build / CI / tests / repo-гигиена — ревизия текстов

Область: `tests/**` (python unittest), `.github/workflows/ci.yml`, корневой `build.gradle`,
`settings.gradle`, `app/build.gradle`, `baselineprofile/build.gradle`, `gradle.properties`,
`gradle/wrapper/*`, `app/lint.xml`, `app/lint-baseline.xml`, `app/proguard-rules.pro`,
`app/src/main/{baseline,startup}-prof.txt`, `.gitignore`, статус `local.properties`/`.kotlin`/`.gradle`,
плюс общерепозиторный проход `git ls-files` (файлы, которых не должно быть в git; топ-30 крупнейших).

## Итог

Собственно код в этой области чистый: сборочные скрипты и тесты работают, dead-фикстур нет (все 14
фикстур ссылаются хотя бы из одного теста), `local.properties`/`.kotlin`/`.gradle`/`gradle-wrapper.jar`
корректно НЕ отслеживаются, `*.orig/*.bak/*.tmp/.DS_Store` в дереве нет, пустых отслеживаемых файлов нет.
Проблема — текстовая: комментарии в конфигах и тестах перегружены кодами миссий (`DEV-4`, `SIZE-3`, `T7`,
`L3`, `O2`, `Phase 4a/4b`, `TT-SUGGESTIONS P2`, `TT-TYPO-NEXT Phase B/C`), датированными сносками
(`2026-09-25`, `2026-08-31`…) и ссылками на `docs/*.md` как на обоснование — а эти доки планируется
архивировать, ссылки станут битыми. Доля комментариев высокая в конфигах: `.github/workflows/ci.yml` ≈ 45 %
строк — комментарии, `app/build.gradle` ≈ 40 %, `app/lint.xml` — 52-строчный монокомментарий на 2 строки
XML, `gradle.properties` 12/25. В тестах комментарии по делу, но пересыпаны теми же метками.

Находки по категориям (уникальные места): **[JARGON]** ~55, **[AGENT]** ~30 (даты/коды/`244 zip`/
`~80 legacy`), **[OVERLONG]** 4 крупных блока (`lint.xml`, шапка `ci.yml`, блоки `T8/B8`, `DEV-2`),
**[STALE]** 2 (см. ниже), **[DUP]** 1 паттерн (фраза «CONTRACT of the tool» в 4 тестах), **[SLOP]** ~6
(«trojan candidate», «re-litigated quietly», «the dossier states as rules», «the mission promised»),
**[DELETE]** 19 отслеживаемых `*.generated.json` (репо-уровень). **[STALE]/[WEIRD]** в самой области — 0
битых ссылок на классы/методы.

Топ-3 действия:
1. Общерепозиторный: 19 закоммиченных `*.generated.json` (в т.ч. 4.5 МБ + 1.5 МБ) противоречат явному
   правилу самого `.gitignore` («сам JSON не коммитится»); вынести из git (детали — раздел «на удаление»).
2. Массово выполоть из комментариев коды миссий, даты и ссылки `docs/*.md`, оставив 1 строку «что и зачем»
   (grep-маркеры — в «Системных паттернах»). Крупнейший единичный источник — `app/lint.xml` (52 строки).
3. Починить 2 [STALE]: `rebuild_assets.py:353` («четырёхклеточная полоса вернулась» — полоса 3 ячейки) и
   шапку `bigram_asset_pack.py:2` («schema-2 packer» — упаковщик пишет schema 3).

Важная оговорка о ложных срабатываниях: слово **«operator»** в `tests/dict_accept/*` и
`tests/review_batches/*` — это ДОМЕННЫЙ термин (человек, курирующий словарь: классы `operator-excluded`,
`operator-widened`, «portion the operator declared read»), а НЕ агентный артефакт. Не трогать. Аналогично
«fail-closed» в тестах в большинстве мест — точное описание поведения упаковщиков (отказ писать ассет при
плохом входе), не пафос.

## Файлы/код на удаление

| Путь (или символ) | Размер | Почему | Кто ссылается (grep) | Риск |
|---|---|---|---|---|
| `docs/corpus-conversational/evidence/*.generated.json` (13 шт.) | 4.57 МБ + 1.58 МБ + 253 КБ + 10 мелких | `.gitignore:88` прямо декларирует «Сырые отчёты прогонов… сам JSON не коммитится»; эти лежат в подкаталоге, не покрытом верхнеуровневым паттерном, и всё равно закоммичены | `docs/CORPUS-CONVERSATIONAL-RU.md`, `cleaning/07-tt-missions.md` (отчёт другого агента) | Низкий-средний: это сгенерированные свидетельства, числа уже перенесены в markdown. Ссылки — только из архивируемых доков. Решение о самих доках — за областью docs, но политика git — репо-уровень |
| `docs/russian-bigrams-repack/evidence/*.generated.json` (2 шт.) | 2 файла | То же нарушение политики | архивируемый отчёт RUSSIAN-BIGRAMS-REPACK | Низкий |
| `docs/archive/bigrams/lang-priority/*.generated.json` (3 шт.) | 3 файла | `.gitignore:89-91` называет их «закоммичены осознанно» как пины-свидетельства — единственные из 19, для кого есть письменное исключение | их отчёт lang-priority | НЕ удалять без решения docs-области — помечены как осознанные |
| `icons/icon.png` | 722 КБ | Бинарник > 500 КБ вне `app/src/main/assets`; исходник мастер-иконки. `icons/` содержит и `.svg`, и готовые `ic_launcher-playstore.png` | прямых ссылок из кода/сборки нет (сборка тянет `res/**` webp/vector, не `icons/`) | Низкий: дизайн-исходник, кандидат в git-lfs или в отдельный design-репозиторий; не критично |
| `app/src/main/startup-prof.txt` | 411 575 Б | **Побайтно идентичен** `baseline-prof.txt` (проверено `cmp` — BYTE-IDENTICAL, оба 3451 строка). AGENTS.md утверждает, что в startup есть «правила LatinIme*/glide, которых нет в baseline» — это уже НЕВЕРНО | AGP (упаковывает в `assets/dexopt/`), `baselineprofile/build.gradle:3-4` | НЕ удалять (нужен AGP), но дубликат стоит зафиксировать/перегенерировать: файл-шапки нет и не нужна (формат HRF комментариев не поддерживает) |
| dead-фикстуры | — | искал: 14/14 фикстур (`corpus_a/b-words`, `golden-v1/v2`, `manual_*_queries`, `synthetic_a/b`, `emoji_sample`, `unknown_group`, `version_mismatch`, `duplicate_sequence`, `filtered-only-words`, `malformed-words`) — все ссылаются ≥1 тестом | grep по `tests/` | Нет находок — удалять нечего |

Крупные `docs/archive/**` TSV (4.2 МБ `accepted-ru.tsv`, 2.8/2.6 МБ и т.д.) и ~200 PNG-свидетельств
(200-400 КБ каждый) — вес репозитория, но это область docs; на уровне git отмечаю как «архивный балласт,
кандидат в git-lfs», решение — за областью docs.

## Правки

### `.gitignore`
- `.gitignore:36` — [JARGON][AGENT] — «`# T7 (stage D, docs/ROADMAP-P8-PLAN.md)`: the dependency
  checksums must be versioned…» — код миссии + ссылка на архивируемый док. Замена: `# Пины
  контрольных сумм зависимостей версионируются — это и есть supply-chain-пин, бесполезный локально.`
- `.gitignore:39` — [JARGON][AGENT] — «`# L3 (docs/LEFTOVERS-PLAN-2026-09-28.md)`: the exported PGP
  keyring…» — то же. Замена: `# Экспортированный PGP-keyring версионируется, чтобы CI и свежие
  клоны проверяли подписи офлайн.`
- `.gitignore:76` — [JARGON] — «`черновики; не коммитится — ROADMAP-P1 T6`» — убрать хвост `— ROADMAP-P1 T6`.
- `.gitignore:89` и `.gitignore:92` — [AGENT] — «`# Уточнение 2026-08-30:`…» и «`(фаза 2):`» — две
  датированные сноски-простыни про историю переездов JSON. Схлопнуть в 1-2 строки без дат/фаз:
  `# Игнор верхнего уровня docs/*.generated.json; три пина lang-priority уже отслеживаются
  осознанно, .gitignore на них не действует.`
- `.gitignore:28` — [SLOP] (мягко) — «`игнорируется ПОСОДЕРЖИМО`» — капслок-неологизм; оставить смысл:
  `# gradle/ игнорируется по содержимому (gradle/*): исключённый родитель нельзя ре-включить пофайлово.`

### `.github/workflows/ci.yml`  (≈45 % строк — комментарии)
- `ci.yml:9-18` — [OVERLONG][JARGON][AGENT] — «`NOTE (T5, stage D of docs/ROADMAP-P8-PLAN.md,
  2026-09-25):`… The SHAs were resolved from the GitHub API on 2026-09-25…» — 10 строк с кодом миссии,
  датой и таблицей резолвинга SHA. Смысл (почему пиним экшены полным SHA) полезен — оставить 2 строки:
  `# Экшены запинены полным commit-SHA (тег — в хвостовом комментарии): плавающий тег следует за
  # тем, куда его двинет апстрим, и компрометация репозитория экшена прошла бы молча.`
- `ci.yml:23-26` — [JARGON][AGENT] — «`Dependency verification (L3, 2026-09-28):`…» — убрать `(L3,
  2026-09-28)`, остальное по делу.
- `ci.yml:54` и `ci.yml:62` — [JARGON] — «`(Phase 4a)`» ×2 — удалить метку фазы, текст оставить.
- `ci.yml:89-91` — [SLOP][AGENT] — «`2026-09-25 audit:` … the artifact would be a maintainer-hosted
  **trojan candidate**» — драматизация + дата. Замена: `# Не выгружать артефакты на pull_request:
  форк собирает свой код, артефакт был бы недоверенным. На push (доверенное дерево) — выгружаем.`
- `ci.yml:127-136` — [OVERLONG][JARGON][AGENT] — блок «`T8 (stage D, docs/ROADMAP-P8-PLAN.md)`… `B8
  (2026-09-28)`…» — ~10 строк истории с двумя кодами и датой. Оставить 2 строки: `# PACK-конвейер
  (zopfli+arsc-deflate) детерминирован — проверяем двойной упаковкой без подписи (CI без keystore).
  # Версия build-tools образа перекрывает релизный пин через TT_BUILD_TOOLS_VERSION.`

### корневой `build.gradle`
- `build.gradle:14` — [JARGON] — «`// Phase 4b: Baseline Profile Gradle plugin…`» — убрать `Phase 4b:`,
  оставить «Baseline Profile plugin (build-time only)».

### `settings.gradle`
- `settings.gradle:12` — [JARGON] — «`// Phase 4b: dev-only generator module (Macrobenchmark)…`» —
  убрать метку `Phase 4b:`, суть оставить.

### `app/build.gradle`  (≈40 % строк — комментарии)  ⚠ читается source-contract-тестом `EmojiPanelAccessibilitySourceContractTest.kt:66-68`
Тест ассертит `assertFalse(gradle.contains("androidx.customview:customview"))` — пинится ПОЛНАЯ координата
`androidx.customview:customview` (с двоеточием). Комментарии ниже её не содержат, поэтому их правка/удаление
безопасны; но при переписывании не вставлять полную координату в текст.
- `app/build.gradle:1` — [JARGON] — «`// DEV-4: error-prone as a build-time javac plugin…`» — убрать
  `DEV-4:` (встречается и на :82, :101).
- `app/build.gradle:10` и `:114` — [JARGON] — «`Phase 4b:`» ×2 — снять метку.
- `app/build.gradle:28` — [JARGON] — «`// Phase 3b: the app ships three locales only`» — снять `Phase 3b:`.
- `app/build.gradle:30`, `:119` — [JARGON] — «`E3b instrumental harness`» — снять код `E3b`, оставить
  «instrumentation-only harness (androidTest)».
- `app/build.gradle:52` — [JARGON] — «`// SIZE-3: -PskipReleaseSigning…`» — снять `SIZE-3:`.
- `app/build.gradle:61-66` — [OVERLONG][AGENT][JARGON] — «`DEV-2: reproducible build (F-Droid). The
  Dependency Info Block (signing-block id 0x504b4453)… all **244 zip entries**…`» — точный счётчик `244`
  и код миссии — артефакты. Оставить 2 строки: `// Воспроизводимая сборка: Dependency Info Block
  подписывается AGP эфемерным ключом и ломает побайтную идентичность; для Play не нужен — выключаем.`
- `app/build.gradle:71`, `:76` — [JARGON] — «`Phase 4a:`» ×2 — снять метку.
- `app/build.gradle:82` — [AGENT] — «`error-prone findings stay warnings… the **~80 legacy** Java
  files`» — приблизительный счётчик; заменить на «унаследованные Java-файлы».
- `app/build.gradle:109-112` — [JARGON][AGENT] — «`// O2 (2026-09-25, docs/OPTIMIZE-2026-09-25.md):
  androidx.customview is gone…`» — снять `O2 (…дата…docs)`; суть «зависимости нет, ExploreByTouchHelper
  форкнут в compat» оставить. ⚠ не вставлять строку `androidx.customview:customview`.

### `baselineprofile/build.gradle`
- `baselineprofile/build.gradle:32` — [JARGON] — «`// DEVICE PIN (A1):`…» — снять код `A1`.
- `baselineprofile/build.gradle:57-58` — [JARGON][AGENT] — «`// O5 (docs/OPTIMIZE-SECURITY-PLAN-
  2026-09-29.md): newest stable, bumped from 1.4.1 — the plan's trace-instrumentation wave…`» — снять
  `O5 (…docs…)` и историю «bumped from 1.4.1»; оставить: `// benchmark-macro-junit4 (dev-only генератор
  профиля; в APK не попадает).`

### `gradle/wrapper/gradle-wrapper.properties`
- `gradle-wrapper.properties:3` — [AGENT] — «`# Пин SHA-256 дистрибутива (аудит 2026-09-25):`…» —
  убрать `(аудит 2026-09-25)`; строка про сверку с gradle.org полезна, дату снять.

### `app/proguard-rules.pro`
- `app/proguard-rules.pro:5` — [AGENT] — «`…(verified by a full clean release build + the JVM test
  suite, **phase 3a**)`» — снять `, phase 3a`; можно убрать всю ремарку о верификации, оставив: `# Keep-
  правила не нужны: в приложении нет reflection-/serialization-достижимого кода, ломающегося при shrink.`

### `app/lint.xml`  — крупнейший единичный [OVERLONG] в области
- `app/lint.xml:3-55` — [OVERLONG][AGENT][JARGON] — 52-строчный комментарий поверх 2 строк XML
  (`<lint></lint>`). Внутри: «`DEV-4 (2026-08-31): the 22 former baseline ERRORS…`», история счётчиков
  «`36 → 31 → 29 → 28 warnings`», даты `2026-08-31/2026-09-25`, коды `O2`, `SAFE wave`, `P1`, разбор
  каждого warning. Проблема: это лог миссий, а не пояснение к правилам подавления. `lint-baseline.xml`
  и так машинно перечисляет все issue. Замена — 3-4 строки классификации без дат/кодов/счётчиков:
  `<!-- lintRelease: abortOnError, baseline = lint-baseline.xml. Все записи baseline — осознанные:
  API-инлайны (minSdk 24), AOSP-именование styleable, синхронный commit() для одноразового флага,
  singleton IME на весь процесс, backup выключен полностью. VectorPath иконки — вне baseline (redraw
  разовый). -->`

### `app/lint-baseline.xml`
- Машинно-сгенерирован lint-инструментом (`by="lint 9.2.1"`), добавленных «нейрослоп»-комментариев нет —
  **не трогать** (правится только регенерацией baseline).

### `gradle.properties`
- Шапка и комментарии — стандартные апстрим-Gradle (ссылки на gradle.org/docs) — **не трогать**.

### `tests/**`  — доменные комментарии по делу; чистить метки миссий/даты/`docs`-ссылки
Единый паттерн (даты + коды `TT-SUGGESTIONS`/`TT-TYPO-NEXT`/`SIZE-1`/`ROADMAP-P4`/`IMPERATIVE-HEADS`/
`EXPAND-1` + `docs/*.md`). Пинованные КОНСТАНТЫ (SHA-256, счётчики, размеры) — НЕ трогать, только прозу
комментариев рядом. Ни один JVM source-contract-тест не читает `tests/*.py` как текст — правка комментов
тесты не ломает.

- `tests/rebuild_assets/test_rebuild_assets.py:353` — **[STALE]** (P1) — «`# K: 3 -> 4 (2026-09-27):
  четырёхклеточная полоса вернулась (T7 переоткрыт).`» — ЛОЖЬ: видимая полоса подсказок = 3 ячейки
  (`CompositePrefixComputer.kt:359` «The band is three cells (SuggestionStripState.CELL_COUNT)»); `4` в
  ассерте `--successes-per-head 4` — это K упаковки таблицы биграмм (в `rebuild_assets.py:169` реально
  `successes_per_head=4`), не число ячеек. Ассерт-значение `4` верно и остаётся; коммент переписать:
  `# успехов на голову = 4 (K таблицы биграмм; к числу ячеек полосы не относится).`
- `tests/bigram_asset_pack/test_bigram_asset_pack.py:2` — **[STALE][JARGON]** (P1) — docstring «`Tests
  for the E5b real TATBIGR **schema-2** packer…`» — упаковщик по умолчанию пишет schema 3 (файл сам
  тестирует schema 3: строки 220/647/690/706; AGENTS.md: текущая — schema 3). Замена: `"""Тесты
  упаковщика и валидатора реальной таблицы TATBIGR (schema 3)."""` + снять ссылку `docs/DICTIONARY-E5A.md`.

- `tests/dict_accept/test_dict_accept.py:408` — [AGENT][JARGON] (P2) — «`# tat, с 2026-09-20 (TT-
  SUGGESTIONS P2, docs/TT-SUGGESTIONS.md): отсечка поднята…`» — снять дату/код/док; оставить «отсечка 110 000».
- `tests/dict_accept/test_dict_accept.py:5-13` (docstring) — [SLOP] (P3) — «`the operator-visible
  invariants **the mission promised**`» — убрать «the mission promised»; «operator-visible» — домен, оставить.
- `tests/typo_pack/test_typo_pack.py:349-350,373,652-654,689` — [AGENT][JARGON] (P2) — «`Recorded in
  docs/DICTIONARY-E3.md`», «`Recalibrated 2026-09-20 (TT-SUGGESTIONS P2)`», «`(docs/TT-TYPO-NEXT.md)`»,
  «`TT-TYPO-NEXT Phase C (2026-09-20)`» — снять даты/коды/`docs`-ссылки. Оставить суть (например: `#
  Полный набор одиночных подстановок на поставляемом ассете, оба окна, алфавит раскладки — 39 букв.`).
  Числа-ассерты (`96118`, `109649`, `109637`) не трогать.
- `tests/bigram_asset_pack/test_bigram_asset_pack.py:392,397,433,561,574` — [SLOP][JARGON] (P2/P3) —
  «`pinned so it cannot be **re-litigated quietly**`», «`docs/BIGRAM-ADJACENCY.md`/`docs/RUSSIAN-
  BIGRAMS.md section 12 item 3`/`docs/IMPERATIVE-HEADS.md`», «`(2026-09-23, ROADMAP-P4 P5a…)`» — убрать
  риторику и `docs`-ссылки/коды; механику («head set решается до подсчёта пар») оставить.
- `tests/dictionary_pack/test_dictionary_pack.py:40-43,503,506,579,630` — [AGENT][JARGON] (P2) —
  «`пересозданы 2026-08-24 миссией tt-dict-accept`», «`110 000 since 2026-09-20 (TT-SUGGESTIONS P2:
  +9 052 admitted…)`», «`(схема 2, SIZE-1)`», «`on 2026-08-21`» — снять даты/коды. Константы
  `RUSSIAN_REVIEW_DATE`/`TATAR_REVIEW_DATE = "2026-08-24"` — это ДАННЫЕ (пины ревью), проверить их
  использование перед любой правкой, вероятно не трогать (не комментарий).
- `tests/suggest_eval/test_suggest_eval.py:2,8,38,119` — [JARGON][AGENT] (P2) — «`TT-SUGGESTIONS
  phase-P0`», «`docs/TT-SUGGESTIONS.md`», «`Pinned at P0 creation (2026-09-19)`», «`Tatoeba-v2026-07-08`».
  `Tatoeba-v2026-07-08` пинится ассертом (`assertIn(...header)`) — НЕ трогать. Остальное — снять код/дату/док.
- `tests/wordform_gen/test_wordform_gen.py:2,32` — [JARGON][AGENT] (P2) — «`TT-SUGGESTIONS phase-P1`»,
  «`Pinned at P1 creation (2026-09-19)`» — снять `phase-P1`/дату; смысл «правка таблицы требует ре-пина» оставить.
- `tests/sentstart_pack/test_sentstart_pack.py:6,332,450` — [JARGON][DUP] (P2/P3) — «`(P4)`», «`rebuild
  recipe in docs/TT-SUGGESTIONS.md, P4 section`», «`docs/ROADMAP-P1.md`» — снять `docs`-ссылки/коды;
  docstring содержит DUP-фразу (см. ниже).
- `tests/emoji_suggest_pack/test_emoji_suggest_pack.py:6,10,281` и
  `tests/emoji_search_pack/test_emoji_search_pack.py:7,315,605` — [JARGON][DUP] (P2/P3) — ссылки
  «`docs/EMOJI-SUGGEST-PLAN.md`/`docs/EMOJI-SUGGEST-RESEARCH.md`/`docs/AUDIT-2026-08-31.md` Mission m2»
  + DUP-фраза; снять ссылки/коды, суть оставить.
- `tests/emoji_pack/test_emoji_pack.py:22,31` — [JARGON] (P3) — «`the **orchestrator** provides it`»,
  «`Values pinned by docs/DICTIONARY-E2.md`» — «orchestrator» → «внешний вход (env EMOJI_TEST_TXT)»;
  снять `docs`-ссылку (сами числа-пины оставить).
- `tests/glide_pack/test_glide_pack.py:251-253` — [JARGON] (P3) — «`Recorded in docs/ROADMAP-P7.md…
  the cross-language mirror pin. **P7-8:**…`» — снять `docs`-ссылку и код `P7-8`; «зеркальный пин с
  Kotlin-тестом GlideRecoveryCalibrationTest» оставить (это реальный класс, проверено).
- `tests/bigram_pack/test_bigram_pack.py:54` — [JARGON] (P3) — «`docs/RUSSIAN-BIGRAMS.md section 12
  item 3… docs/BIGRAM-ADJACENCY.md`» — снять ссылки, механику оставить.
- `tests/review_batches/test_review_batches.py:4-9` — [SLOP] (P3) — docstring «`the two invariants…
  the **dossier states as rules rather than as features**`» — вычурно; заменить на нейтральное «два
  инварианта, которые важнее прочих:». «operator declared read» (строка 8) — домен, оставить.

## Системные паттерны

1. **Коды миссий в комментариях** — grep-маркер:
   `grep -rnE '\b(DEV-[0-9]|SIZE-[0-9]|O[0-9]|T[0-9]|L[0-9]|A[0-9]|B[0-9]|P[0-9]|E[0-9][a-z]|Phase [0-9]|phase-P[0-9]|TT-(SUGGESTIONS|TYPO)|ROADMAP|IMPERATIVE-HEADS|EXPAND-1)\b' tests/ *.gradle app/ .github/ .gitignore gradle/`.
   Правило: код миссии из комментария убрать; если он нёс смысл — переписать смысл словами, не кодом.
2. **Датированные сноски** — маркер `grep -rnE '20[0-9]{2}-[0-9]{2}-[0-9]{2}'`. Правило: дату убрать
   (история живёт в git), оставить одну строку «что и зачем делает» настоящий код/конфиг.
3. **Ссылки `docs/*.md` как обоснование** — маркер `grep -rnE 'docs/[A-Za-z0-9-]+\.(md|tsv)'`. Доки
   архивируются → ссылки станут битыми. Правило: убрать ссылку; факт, который она подтверждала, либо
   изложить в 1 строке, либо удалить как неактуальный для чтения кода.
4. **Счётчики/размеры/SHA в прозе** — `244 zip entries`, `~80 legacy`, «31 → 29 → 28 warnings», история
   «bumped from 1.4.1». Правило: приблизительные счётчики в комментариях убрать; настоящие пины (SHA-256,
   `EXPECTED_*`, размеры ассетов) — это ДАННЫЕ/константы, оставить.
5. **DUP-шаблон** — фраза «(What is pinned … the) CONTRACT of the tool(, not the data)» в
   `tests/{emoji_suggest_pack,emoji_search_pack,sentstart_pack}` и вариант в `tests/rebuild_assets`
   (маркер `grep -rn 'CONTRACT of the tool' tests/`). Низкий приоритет: это осознанно единый паттерн
   пояснения. Если чистить — привести к одной короткой формулировке во всех четырёх.
6. **Риторика/пафос [SLOP]** — точечно: «trojan candidate» (`ci.yml:91`), «re-litigated quietly»
   (`bigram_asset_pack:392`), «the mission promised» (`dict_accept:11`), «the dossier states as rules»
   (`review_batches:6`). Заменить нейтральными формулировками.
7. **Ложные срабатывания (НЕ трогать):** «operator» = доменный курор словаря (не агент); «fail-closed»
   в тестах = точное поведение упаковщиков; `RUSSIAN_REVIEW_DATE`/`Tatoeba-v2026-07-08`/`EXPECTED_*SHA256`
   и т.п. = данные-пины (часть некоторых пинится ассертами). AOSP/Gradle-шапки (`gradle.properties`,
   `settings.gradle` верх, `lint-baseline.xml`) — апстрим, вне ревизии.

## Отдельно: репо-гигиена (общий проход `git ls-files`, 1536 файлов)

- ✅ `gradle/wrapper/gradle-wrapper.jar` — НЕ отслеживается (только `.properties`), как и предписано.
- ✅ `local.properties` — НЕ отслеживается (содержит только `sdk.dir`).
- ✅ `.kotlin/`, `.gradle/` — НЕ отслеживаются.
- ✅ `*.orig`/`*.bak`/`*.tmp`/`.DS_Store` — в дереве нет; пустых отслеживаемых файлов нет.
- ✅ `cleaning/` (эта директория отчётов) — НЕ отслеживается git.
- ⚠ 19 отслеживаемых `*.generated.json` — см. таблицу удаления (главная репо-находка).
- ⚠ Бинарники > 500 КБ вне `assets`: `icons/icon.png` (722 КБ), `docs/archive/PROPOSALS.md` (695 КБ,
  markdown), крупные `docs/archive/**` TSV (до 4.2 МБ) и ~200 PNG-свидетельств — вес репозитория;
  большинство — область docs, отмечено как кандидаты в git-lfs/архив вне git.
- Распределение по типам: 348 `.kt`, 224 `.png`, 217 `.tsv`, 198 `.xml`, 167 `.md`, 152 `.txt`, 88
  `.java`, 76 `.py`. PNG+TSV (441 файла) — почти весь бинарный вес, и он сосредоточен в `docs/**`.
