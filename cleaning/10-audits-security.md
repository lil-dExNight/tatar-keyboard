# Аудиты, безопасность, оптимизация — ревизия текстов

Область: `docs/AUDIT-2026-08-31.md`, `docs/AUDIT-2026-09-24.md`,
`docs/AUDIT-2026-09-24-FIXES.md`, `docs/audit-fixes/`,
`docs/SECURITY-AUDIT-2026-09-25.md`, `docs/SECURITY-AUDIT-2026-09-25-FIXES.md`,
`docs/FINAL-AUDIT-2026-09-02.md`, `docs/OPTIMIZE-2026-09-25.md`,
`docs/OPTIMIZE-SECURITY-PLAN-2026-09-29.md`, `docs/THREAT-MODEL.md`,
`docs/PERF-BUDGETS.md`, `docs/IC-BINDER-AUDIT-2026-09-29.md`.

Проверочная база (прочитано в коде): `app/build.gradle:26-27` → versionName
**3.6.0**, versionCode **43**; `SuggestionStripStateTest.kt:219` →
`assertEquals(3, SuggestionStripState.CELL_COUNT)` (полоса — **3 ячейки**);
`HANDOFF.md:44-46` → план `OPTIMIZE-SECURITY-PLAN-2026-09-29` **исполнен
полностью, все 17 пунктов сданы**; 207 файлов JVM-тестов в `app/src/test`
(число тестов ~1927 из AGENTS.md правдоподобно).

## Итог

Из 12 позиций области **10 — закрытые отчёты и один исполненный план**; живых
только два (`THREAT-MODEL.md`, `PERF-BUDGETS.md`). Диагноз: это не «мусор», а
добротные, но перегруженные документы, написанные в агентной стилистике —
плотный английский канцелярит, самолюбование («earned its keep»,
«the centerpiece», «existential»), сплошные внутренние коды миссий
(F1–F17, S1–S9, O1–O8, T1–T11, N1–N5, U1–U4, A/B/C/D, H1, L2–L8) без единой
расшифровки, и артефакты процесса внутри технического текста («No commits —
the operator commits», «written by the parallel agent», «handed to F2 by
design», таблицы «Gates» со счётчиками тестов/байтов). Находки по категориям
(приблизительно): **[AGENT] ~40+**, **[JARGON] ~30+**, **[SLOP] ~25**,
**[OVERLONG] ~15**, **[STALE] ~10** (главное — «planned/lands as S2–S8» в живом
THREAT-MODEL, хотя всё сдано; дрейфующие счётчики и размер APK 3.4.0 в
PERF-BUDGETS), **[LANG] ~3**, **[WEIRD] 1** (утёкший чужой путь
`/home/tarchok/...`).

Топ-3 действия:
1. **Перенести 8 закрытых отчётов + исполненный план + свидетельства в
   `docs/archive/`** (по дисциплине AGENTS.md «архив миссий — docs/archive/»),
   поправив ссылки в `docs/README.md`, `HANDOFF.md`, комментариях кода и тестах.
2. **Освежить два живых документа**: убрать из THREAT-MODEL слова «planned/
   lands as» (S2–S8 сданы) и переписать статусы на «done»; в PERF-BUDGETS
   отвязать «Latest measured» от дрейфующих чисел версии/размера.
3. **Массовая чистка агентных артефактов**: удалить блоки «Gates» со
   счётчиками тестов, фразы «the operator commits»/«parallel agent», расшифровать
   или снять коды миссий там, где текст должен пережить своих авторов.

## Файлы на удаление / перенос

Все закрытые отчёты **ссылаются** из индекса и живых доков, поэтому «жёсткое»
удаление сломает ссылки — рекомендуется **перенос в `docs/archive/`** с правкой
ссылок. «Риск» ниже — про поломку ссылок при переносе.

| Путь | Размер | Почему кандидат | Кто ссылается (grep) | Риск |
|---|---|---|---|---|
| `docs/OPTIMIZE-SECURITY-PLAN-2026-09-29.md` | 30 259 Б | Исполненный план (HANDOFF: «все 17 пунктов сданы»); план, а не справка — место в архиве | `docs/README.md:151`, `HANDOFF.md:50`, `PUBLISH-CHECKLIST.md:29`, `THREAT-MODEL.md:4`, `PERF-BUDGETS.md:3`, `IC-BINDER-AUDIT:1`, а также **комментарии кода** (`SuggestionsController.kt:143,284`, `LatinIME.java:386,1673`) и **тесты** (`SuggestionStripSourceContractTest.kt:153,181,207`, `BatchEditPairingContractTest.kt:31`, `InputConnectionBinderContractTest.kt:25`, `RichInputConnectionRobustnessContractTest.kt:32`) | средний (ссылки в коде/тестах) |
| `docs/IC-BINDER-AUDIT-2026-09-29.md` | 8 739 Б | Закрытый под-отчёт исполненного плана (пункт O6), вердикт «no code fix needed» | `HANDOFF.md:83`, `docs/README.md:183`, план `:162`, `InputConnectionBinderContractTest.kt:26` | средний (ссылка в тесте) |
| `docs/OPTIMIZE-2026-09-25.md` | 30 063 Б | Закрытая волна (O1/O2), с внутренней сноской «O2-1 REVERTED» | `docs/README.md:283`, `HANDOFF.md:473,563,576`, `CHANGELOG.md:91`, `PUBLISH-CHECKLIST.md:141`, `PERF-BUDGETS.md` (много), **код** `SuggestionsController.kt:950,1631`, `LatinIME.java:1955`, `scripts/release_pack.sh:7,153` | средний (ссылки в коде/скрипте) |
| `docs/SECURITY-AUDIT-2026-09-25.md` | 19 450 Б | Закрытый аудит (все находки fixed/accepted) | `THREAT-MODEL.md` (агрегирует), `README:273`, `HANDOFF`, `CHANGELOG:103`, `PUBLISH-CHECKLIST:143`, план `:280` | низкий-средний |
| `docs/SECURITY-AUDIT-2026-09-25-FIXES.md` | 15 286 Б | Закрытый отчёт-половинка волны правок | `README:290`, `SECURITY-AUDIT-2026-09-25.md`, **тесты** `RichInputConnectionRobustnessContractTest.kt:27`, `BatchEditPairingContractTest.kt:26` | средний (ссылки в тестах) |
| `docs/AUDIT-2026-09-24.md` | 10 053 Б | Закрытый консолидированный аудит 3.0.0 | `THREAT-MODEL.md` (агрегирует), `README:266`, `HANDOFF`, `CHANGELOG:126`, `PUBLISH-CHECKLIST:169`, `APK-AUDIT-3.0.1.md` | низкий-средний |
| `docs/AUDIT-2026-09-24-FIXES.md` | 12 834 Б | Закрытый отчёт-половинка (код) | `README`, `HANDOFF:635`, `PUBLISH-CHECKLIST:170`, `APK-AUDIT-3.0.1.md` | низкий |
| `docs/AUDIT-2026-08-31.md` | 20 257 Б | Закрытый аудит 1.9.5 (все находки закрыты датированными пометками) | `README:263`, `CHANGELOG:373,400`, `PUBLISH-CHECKLIST:419`, `HANDOFF:2184`, свидетельства в `audit-fixes/` | низкий |
| `docs/FINAL-AUDIT-2026-09-02.md` | 16 230 Б | Закрытый предрелизный аудит 1.9.11 (остались только операторские A2/C8) | `THREAT-MODEL.md` (агрегирует), `README:309`, `HANDOFF`, `PUBLISH-CHECKLIST:318`, план `:280` | низкий-средний |
| `docs/audit-fixes/evidence/*` (5 PNG + 2 txt, ≈820 КБ) | 820 КБ | Агентные свидетельства к закрытому `AUDIT-2026-08-31.md`; переносить вместе с отчётом | Только `AUDIT-2026-08-31.md:80,101,119,159,204` | низкий |

Живые (НЕ удалять, только править): `docs/THREAT-MODEL.md` (27 581 Б),
`docs/PERF-BUDGETS.md` (10 721 Б).

## Правки

### docs/THREAT-MODEL.md (живой — освежить)

Документ качественный и по делу; главная беда — статусы «planned» на уже
сделанном и агентный жаргон кодов.

- `THREAT-MODEL.md:3-4` — [STALE] — «gates marked "planned" below are items
  S2–S8 … and land after this document» — S2–S8 **сданы** (HANDOFF:44).
  Замена: «gates S2–S8 landed 2026-09-29; this document is kept in sync.»
- `THREAT-MODEL.md:106` — [STALE] — «hostile-shape tests extended as plan item
  S8» — S8 сдан. Замена: назвать сданные пины (`HostileHostRobustnessTest`,
  `HostileHostSuggestionChurnTest`) без слова «as plan item».
- `THREAT-MODEL.md:109` — [STALE] — «seeded parser fuzzing planned as S5» —
  S5 сдан (61 500 итераций). Замена: «seeded parser fuzzing:
  `*ValidatorFuzzTest` + `SeededFuzzHarness`».
- `THREAT-MODEL.md:111` — [STALE] — «Source-contract log-safety gate planned as
  S3» — сдан (`LogSafetySourceContractTest`). Замена: назвать тест как gate.
- `THREAT-MODEL.md:112` — [STALE] — «the full EditorInfo privacy matrix lands as
  S6» — сдан (`EditorInfoPrivacyMatrixTest`). Замена: назвать тест.
- `THREAT-MODEL.md` §6 (постура «SetupActivity and SettingsActivity are
  exported») — [STALE] — «The golden exported set becomes a fail-closed release
  gate as plan item S2» — S2 сдан (`artifact.exported_surface`). Замена:
  «pinned by `artifact.exported_surface` in `release_check.sh`».
- `THREAT-MODEL.md` §2, актив 5 (User trust) — [SLOP] — «existential. The claim
  *is* the product; one observed byte of egress invalidates the project.» —
  пафос. Замена: «Пользовательское доверие к офлайн-обещанию: одно наблюдаемое
  исходящее подключение обесценивает проект — отсюда доказательная цепочка §7.»
- `THREAT-MODEL.md` §7 STORAGE — [SLOP] — «pending stage as salted truncated
  SHA-256 until a word earns its place» — «earns its place» лишнее. Замена:
  «…until the word crosses the learning threshold».
- `THREAT-MODEL.md` §9 «Incident record» — [OVERLONG]/P3 — раздел на 6 абзацев
  (Citizen Lab, CVE, ai.type, GO Keyboard, Kika, SwiftKey). Контекст полезный,
  но каждый абзац дублирует мораль «поэтому мы X». Сократить до одной таблицы
  «инцидент → контроль» без риторических хвостов («turning the same technique …
  into our evidence»). Приоритет P3.
- [JARGON] по всему файлу коды F-wave/T6/N4/P5/P6/U1/L3/L4/I3 в реестре рисков
  (§8) — при переносе их в единственную живую точку правды дать одноразовую
  легенду «код = аудит-документ + номер» вверху таблицы.

### docs/PERF-BUDGETS.md (живой — отвязать от дрейфа)

- `PERF-BUDGETS.md:17` (строка «Release APK size») — [STALE] — «Latest
  measured: **1 804 874 B** signed 3.4.0, 2026-09-28» при текущей версии
  **3.6.0/43**. Захардкоженный размер конкретной версии в живой таблице
  дрейфует. Замена: «текущий signed APK (см. свежий `docs/APK-AUDIT-*.md`)»,
  число обновлять только вместе с релизом или убрать из ячейки, оставив гейт.
- `PERF-BUDGETS.md:18` (Cold start) — [OVERLONG] — ячейка на ~10 строк с тремя
  наборами замеров и разбором «column 17 vs 14». Половину (методология
  framestats) вынести в раздел «Measurement discipline» ниже, в ячейке оставить
  бюджет + последнее число + ссылку.
- `PERF-BUDGETS.md:19` (Frame time) — [OVERLONG] — то же: диагноз «debug vs
  release, +2.3 ms offset» повторяется трижды по файлу. Свернуть в одну сноску.
- `PERF-BUDGETS.md` PSS-строка — [OVERLONG]/[JARGON] — «114 000 kB debug scale …
  = peak reading 90 526 kB + ~25% headroom, rounded up» и коды O2/O2-2 —
  оставить число и «debug scale», вывод расчёта headroom — в сноску.
- [JARGON] коды O2-2, G2, E3b, C3, F-wave в ячейках «Gate/ritual» — расшифровать
  один раз или заменить на имена тестов, которые и так рядом стоят.

### docs/OPTIMIZE-SECURITY-PLAN-2026-09-29.md (исполненный план → архив)

- `OPTIMIZE-SECURITY-PLAN-2026-09-29.md:242` — [WEIRD]/[AGENT]/P1 — в тексте
  ошибки aapt утёк **чужой абсолютный путь**
  `/home/tarchok/Projects/tatar-keyboard/app/src/main/AndroidManifest.xml:...`
  (проект сейчас `/Users/amirka/...`, macOS). Заменить на относительный
  `app/src/main/AndroidManifest.xml:32` при архивировании.
- `OPTIMIZE-SECURITY-PLAN-2026-09-29.md:33-34, 54-55, 72, …` — [AGENT] — каждый
  пункт несёт «Verification: …» и «Effort: ~1 h/~2 h/~3–4 h». Для исполненного
  плана оценки трудозатрат — мусор. При архивировании удалить строки «Effort».
- Весь файл — [AGENT] — под каждым пунктом дописан абзац-исполнение
  «**2026-09-29:** done…» — это дневник агента поверх плана. Место в архиве
  как есть; в живом дереве держать не нужно.
- `:7 «O7. … the centerpiece»` (в HANDOFF, но эхо и здесь) — [SLOP] —
  «centerpiece» — самолюбование. При правке HANDOFF снять (вне моей области,
  отмечаю для системного паттерна).

### docs/IC-BINDER-AUDIT-2026-09-29.md (закрытый → архив)

- `IC-BINDER-AUDIT-2026-09-29.md:8-13` — [AGENT] — «Verdict up front: **no code
  fix needed**… What this audit adds is the *inventory proof*». Отчёт целиком —
  «доказательство инвентаря», ценный, но это закрытый артефакт. Перенести в
  архив без правок содержания.
- [JARGON] сквозь весь файл коды F1/F3/F4/F5/F8/F10/C6/O6/Q1–Q5 — понятны только
  участникам волны 2026-09-25. При сохранении как справки — легенда сверху.
- `:«Q1 — per-keystroke cost: CLEAN»` … `Q5` — [OVERLONG] — пять «вопросов» с
  вердиктом CLEAN; для справки достаточно таблицы IC-инвентаря + одной строки
  «все пять осей чисты, пины в `InputConnectionBinderContractTest`».

### docs/OPTIMIZE-2026-09-25.md (закрытый → архив)

- `OPTIMIZE-2026-09-25.md` заголовок — [SLOP] — «A wave of zero-quality-loss
  optimizations». В теле — «earned»/«the hard gate»/«honest note». Риторику
  снять при переносе.
- `:«## Gates»` и `«### O2 gates»`, `«### Final size accounting»` — [AGENT] —
  блоки со счётчиками «1 587 tests, 0 failures», «baseline 32 → 29», байтовыми
  дельтами. Это протокол прогонов, не документация. В архив как есть, в живом
  дереве не поддерживать.
- Сноска «O2-1 REVERTED — it made the APK uninstallable» — [OVERLONG] — ценный
  урок (arsc STORED для targetSdk 30+), но 20 строк. Одну строку урока стоит
  поднять в `docs/PERF-BUDGETS.md` или `scripts/release_pack.sh` (там уже есть
  комментарий), остальное — в архив.
- [JARGON] O1–O16, O2-1…O2-6, E5c, P7-4, SAFE-волна — коды без расшифровки.

### docs/AUDIT-2026-08-31.md (закрытый → архив)

- Файл двуязычный: заголовки/сводка по-русски, но термины и пометки мешают
  языки. [LANG] — «Baseline-профиль не покрывает движок подсказок», «CUJ бежал
  с выключенными подсказками», «touch-path без аллокаций» — англицизмы в
  русском тексте. Для закрытого отчёта — оставить, для живого стиля — норма
  проекта; правкам не подлежит при архивировании.
- Датированные блоки «**Закрыто 2026-08-31** … коммит `4f87c48d`» под каждой
  находкой (S1, S2, U1+U2, U3, m1, m2, P1, P2, P3) — [OVERLONG]/[AGENT] — по
  10–25 строк объяснения на находку с хэшами коммитов и «Гейты: JVM 988/0,
  python 281/0…». Для закрытого отчёта архивно приемлемо; в живом индексе не
  держать.
- `:«P1 — закрыто»` — [AGENT] — «Эмуляторное подтверждение … смоук DEV-3 на
  release APK: 11 PASS / 1 FAIL / 7 SKIP» — счётчики прогонов внутри доки.
- [JARGON] коды S1/S2/U1–U3/m1–m4/P1–P3/DEV-3 без легенды.

### docs/AUDIT-2026-09-24.md (закрытый → архив)

- Весь файл — [JARGON] — статусы «fixed-in-3.0.1», коды H1/L2–L8/M1/M2/N/F1 в
  таблице находок без расшифровки, «F1 wave», «this wave».
- `:«Verdict:** release-blocker H1 found and fixed; everything else is fixed,
  documented, or an accepted risk»` — [SLOP] — плотный аудиторский слог; для
  архива приемлемо.
- Секция «The fix wave» и «Gates of this wave (2026-09-24): python suites
  **507 tests in 16 files**» — [AGENT] — счётчики тестов.

### docs/AUDIT-2026-09-24-FIXES.md (закрытый → архив)

- `AUDIT-2026-09-24-FIXES.md:3` — [AGENT]/P1 — «The wave's other half … belongs
  to a parallel agent; … No commits — the operator commits. Canonical English».
  Прямые артефакты агентного процесса. В архив как есть; в живом дереве таких
  фраз быть не должно.
- Заголовки F1–F16 «MAJOR/MEDIUM (fixed)» — [JARGON] — коды находок.
- `:«## Gates (2026-09-24, host)»` — [AGENT] — таблица «**1 543 tests, 0
  failures** (1 534 → 1 543: +9 …)», «**1 860 976 B**». Счётчики/байты.

### docs/SECURITY-AUDIT-2026-09-25.md (закрытый → архив)

- Коды N1–N5, P1–P7, F1–F17, T1–T11, U1–U4 в таблицах — [JARGON].
- `:«written by the parallel agent»` (в companion-файле) и «Two fix waves land
  this round, working disjoint file sets» — [AGENT].
- `:«## Gates»` — [AGENT] — «**1 633 tests, 174 suites, 0 failures**»,
  «identical SHA-256 `dd39c156…`». Счётчики/хэши.
- [SLOP] — «the fork's RuntimeException subclassing is accepted as
  androidx-mirroring; AppLocale is clean» — плотное нагромождение вердиктов.

### docs/SECURITY-AUDIT-2026-09-25-FIXES.md (закрытый → архив)

- `SECURITY-AUDIT-2026-09-25-FIXES.md:3` — [AGENT] — «Companion to … (the audit
  itself, written by the parallel agent). … No commits — the operator commits.
  Canonical English». Артефакты процесса.
- `:«F9/F12/F15 are absent by assignment (not this half).»` — [AGENT] — дележ
  задач между агентами внутри техдоки.
- `:83` сноска «**2026-09-29 footnote (S8 …)**» — [OVERLONG] — 12-строчная
  сноска-эволюция доктрины «nothing is caught» → «catch RuntimeException».
- `:«## Gates»` — [AGENT] — «died to build-dir races with the parallel agent's
  concurrent gradle runs (`NoSuchFileException`…) — infrastructure, not red
  tests». Дневник инфраструктурных гонок между агентами.

### docs/FINAL-AUDIT-2026-09-02.md (закрытый → архив)

- Коды A1–A3/B1–B5/C1–C8/D1–D5 в таблицах — [JARGON].
- `:«миссия META»`, `«миссия TOOLING-FIX»`, `«миссия CODE-FIX»` с хэшами
  коммитов под каждым пунктом — [AGENT]/[OVERLONG].
- `:«Вердикт»` — «Публиковать в сторы нельзя из-за витрины (metadata):
  скриншоты показывают другое приложение» с двумя вложенными датированными
  «финальными пометками» поверх — [OVERLONG]. Для архива приемлемо.
- [LANG] — «витринный блокер снят», «перецелен на 1.9.12» — жаргон.

### docs/audit-fixes/evidence/ (свидетельства → архив вместе с отчётом)

- 5 PNG (≈780 КБ: `m2-emoji-tt-search.png`, `p1-launcher-icon.png`,
  `p3-key-preview.png`, `u1-u2-*.png`) + 2 txt-лога
  (`u3-language-announce-events.txt`, `m1-add-rejected-ru-toast.txt`) — [AGENT]/
  [DELETE] — сырые эмуляторные свидетельства (дампы `uiautomator events`,
  скриншоты) к находкам закрытого `AUDIT-2026-08-31.md`. Ссылается только сам
  отчёт. Переносить в `docs/archive/` вместе с ним; либо удалить, если история
  доказательств не нужна (данные — debug-эмулятор, чувствительного нет). Риск
  низкий.

## Системные паттерны

1. **Дневник агента поверх документа.** Почти в каждом файле под пунктами
   дописаны блоки «**2026-09-29:** done…», «**Закрыто 2026-08-31** (миссия …,
   коммит `hash`)», таблицы «## Gates» со счётчиками «1 633 tests, 0 failures»
   и байтами APK. Правило: технический документ описывает **что делает система
   сейчас**; протоколы прогонов, хэши и счётчики тестов живут в git-истории и
   CI, а не в доке. При архивировании — оставить как есть (исторический
   артефакт); в живом дереве (README-индекс, THREAT-MODEL, PERF-BUDGETS) —
   вычистить.

2. **Коды миссий без легенды.** F1–F17, S1–S9, O1–O8, T1–T11, N1–N5, U1–U4,
   H1, L2–L8, A/B/C/D, E5c, P7-4, DEV-3, SAFE-волна. Понятны только участникам.
   Правило: код миссии допустим внутри своего закрытого отчёта; при ссылке из
   живого документа — либо расшифровать («находка о …»), либо заменить именем
   пина/теста, который и так рядом.

3. **Артефакты параллельной работы двух агентов.** «written by the parallel
   agent», «No commits — the operator commits», «handed to F2 by design»,
   «F9/F12/F15 are absent by assignment», «build-dir races with the parallel
   agent's concurrent gradle runs». Правило: адресация к другому агенту/оператору
   и координация задач — не часть продукта; удалять из любого живого текста.

4. **Статусы «planned/lands as» на сделанном.** Живой THREAT-MODEL шесть раз
   называет уже сданные гейты «planned as Sx / lands as Sx / becomes … as plan
   item Sx». Правило: у живого документа статус синхронизируют с кодом; после
   сдачи гейта — «gated by <test>», а не «planned».

5. **Захардкоженные дрейфующие числа.** Размер APK версии 3.4.0 в живой
   PERF-BUDGETS при текущей 3.6.0; счётчики тестов (988/1001/1543/1587/1633/…)
   в закрытых отчётах. Правило: в живых доках — ссылка на источник истины
   (свежий APK-AUDIT, гейт), а не число; в закрытых — оставить как снимок даты.

6. **Пафос и самолюбование.** «earned its keep», «the centerpiece»,
   «existential», «the hard gate», «zero-quality-loss», «honest note»,
   «earns its place». Правило: убирать оценочную риторику, оставлять факт.

7. **Перенос закрытого в архив.** AGENTS.md прямо предписывает «архив миссий —
   docs/archive/». 8 закрытых отчётов + 1 исполненный план + свидетельства
   лежат в корне `docs/`, раздувая индекс. Правило массовой чистки: перенести в
   `docs/archive/`, обновить `docs/README.md`, `HANDOFF.md`; для тех, на кого
   ссылаются **комментарии кода и тесты** (OPTIMIZE-SECURITY-PLAN,
   OPTIMIZE-2026-09-25, IC-BINDER-AUDIT, SECURITY-AUDIT-2026-09-25-FIXES) —
   поправить и пути в этих ссылках (иначе останутся битые ссылки, но код не
   сломается — это текстовые комментарии).
