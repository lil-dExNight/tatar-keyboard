# Остальные JVM-тесты (keyboard/, latin корень, emoji/, settings/, golden/, inputlogic/, utils/, accessibility/, androidTest/, baselineprofile/) — ревизия текстов

## Итог

Область — ~90 файлов тестов (keyboard/ + keyboard/internal/, latin/ корень, emoji/, settings/,
golden/, inputlogic/, utils/, accessibility/, весь app/src/androidTest/, baselineprofile/;
подпапки latin/suggestions, latin/dictionary, latin/glide — чужая область и здесь не считаются).
Общий диагноз: **код тестов чистый, гниль сосредоточена в class-level KDoc/Javadoc-шапках** —
почти каждый `*ContractTest` открывается связкой «код миссии + `docs/*.md` + датированный аудит +
многоабзацный лог расследования» (`S8 of docs/OPTIMIZE-SECURITY-PLAN-2026-09-29.md`, `2026-09-25
audit, F5`, `Mission tt-corpus-os`, `U3 source-contract`, `O4/O6/O7`, `W1..W6/B1..B7/M2..M5/S1/S2`).
Доля строк-комментариев в крупных файлах: LogSafety 35 % (166/479), EditorInfoPrivacyMatrix 26 %,
DrawAllocInstrumentationTest 25 %, HostileHostRobustness 24 %, baselineprofile-генератор ~90 строк
KDoc на один класс. Находки: ~45 датированных сносок (AGENT), ~30 кодов миссий + `docs/*.md`
(JARGON), 6 иОС-экспортёров (DELETE), 1 «operator decided» + пины счётчиков (AGENT), 0 явно
ложных комментариев (проверка «3 ячейки» показала, что мои комментарии как раз *актуальны*).

Топ-3 действия: (1) удалить/отделить `latin/golden/` — 5 файлов ~123 КБ, чистые экспортёры голден-
векторов для внешнего **iOS/Swift-порта** (`ios/docs/VERIFICATION.md`, `ios/tools/…`), инертны,
на них не ссылаются ни CI, ни скрипты; (2) массово ужать шапки-простыни `*ContractTest` до 1–2
строк «что пинит и почему по source, а не поведением», выкинув коды миссий/даты/`docs/*.md`;
(3) осторожно с двумя тестами, которые режут `RichInputConnection.java` по **тексту датированного
комментария** `/** … 2026-09-25 audit, F1/F10` — чистка этих комментариев в main без правки
делимитеров уронит сборку.

## Файлы/код на удаление

| путь | размер | почему | кто ссылается (grep) | риск |
|---|---|---|---|---|
| `app/src/test/java/.../latin/golden/GoldenExportTest.kt` | 16.7 КБ | Экспортёр голден-векторов для iOS-порта; инертен (гейт `assumeTrue(GOLDEN_OUT)`), в APK/CI не попадает | `GOLDEN_OUT`/`GoldenExport` — только внутри самого файла; ни скриптов, ни CI, ни `docs/` | Средний: сломает генерацию parity для iOS, если порт жив |
| `app/src/test/java/.../latin/golden/EdgeGoldenExportTest.kt` | 30 КБ | То же (`EDGE_GOLDEN_OUT`), «OD-5», плюс огромный лог-абзац про инструментирование дерева | нет внешних ссылок | Средний (iOS) |
| `app/src/test/java/.../latin/golden/GlideGoldenExportTest.kt` | 33 КБ | То же (`GLIDE_GOLDEN_OUT`) | нет внешних ссылок | Средний (iOS) |
| `app/src/test/java/.../latin/golden/EmojiGoldenExportTest.kt` | 14 КБ | То же (`EMOJI_GOLDEN_OUT`) | нет внешних ссылок | Средний (iOS) |
| `app/src/test/java/.../latin/golden/PersonalGoldenExportTest.kt` | 28 КБ | То же (`PERSONAL_GOLDEN_OUT`) | нет внешних ссылок | Средний (iOS) |

Примечания к удалению:
- Вся папка `latin/golden/` существует **только** ради iOS-порта: каждая шапка гласит
  «Golden-vector exporter for the iOS port (`ios/docs/VERIFICATION.md §3.1`)», геометрию берут из
  `ios/tools/export-key-geometry`, результат «replays every record against the Swift port». Пути
  `ios/…` находятся вне этого Android-репо. Раз iOS не рассматривается — это мёртвый вес; но
  формально это решение оператора (порт может быть жив в соседнем репо), поэтому риск не «низкий».
- НЕ удалять `app/src/test/resources/tt_eval_sentences.txt` вместе с golden/: его читают живые
  тесты вне моей области (`dictionary/engine/TtSuggestEvalTest.kt`, `TtTypoPhaseB/CCalibrationTest`,
  `AutocorrectWideningCalibrationTest`, `glide/GlideRecoveryCalibrationTest`).
- Больше в области **нет** файлов-кандидатов на удаление: всё остальное — живые тесты из ~1927
  прогона (`./gradlew test`), удалять нельзя.

## Source-contract тесты, читающие исходники как текст (инвентарь)

Эти тесты грепают/режут main-исходники строками. Большинство пинит **идентификаторы кода** —
чистке комментариев в main они не мешают. Отдельно помечены те, что пинят **текст комментария**.

| тест | что читает | пинит комментарий main? |
|---|---|---|
| `latin/InputConnectionBinderContractTest.kt` | `RichInputConnection.java` | **ДА**, `:131` делимитер `"/**\n * 2026-09-25 audit, F10"` |
| `latin/RichInputConnectionRobustnessContractTest.kt` | `RichInputConnection.java` | **ДА**, `:273` делимитер `"/**\n * 2026-09-25 audit, F1"` |
| `inputlogic/BatchEditPairingContractTest.kt` | `InputLogic.java`, `RichInputConnection.java` | делимитер по нейтральному AOSP-javadoc `"Gets the current auto-caps state"` (безопасно) |
| `inputlogic/CommitPathConnectionContractTest.kt` | `RichInputConnection.java` | делимитеры `"Allocation-free suffix test"`, `"Commits a predicted next word"` (безопасно) |
| `latin/LogSafetySourceContractTest.kt` | все `.java/.kt` в keyboard/, suggestions/, dictionary/, emoji/ | пинит множество Log-call-site (код), не комментарии |
| `latin/SilentGestureSourceContractTest.kt`, `latin/RichInputMethodManagerExecutorSourceContractTest.kt`, `latin/HandlerMessageIdSourceContractTest.kt`, `latin/EditorTextCachePrivacySourceContractTest.kt` | соотв. main-классы | код/идентификаторы |
| `keyboard/AppleUxStageBContractTest.kt`, `keyboard/AppleUxBatchOneContractTest.kt` | ~15 res-xml + Key.java/MainKeyboardView.java/… | код/значения ресурсов |
| `keyboard/KeyboardViewDrawLoopContractTest.kt`, `keyboard/PointerTrackerRobustnessContractTest.kt`, `keyboard/internal/KeyPreviewDismissAnimatorSourceContractTest.kt` | draw-loop / PointerTracker / аниматор | код |
| `emoji/EmojiSearchLayoutTest.kt` | EmojiSearch-view | делимитеры `"/** Widest scroll offset"`, `"/** Drops the bound index"` (нейтральный javadoc, безопасно) |
| `emoji/EmojiPanelAccessibilitySourceContractTest.kt` | панель эмодзи | делимитер `"/** Scrolls one grid viewport"` (безопасно) |
| `emoji/EmojiPanelSourceContractTest.kt`, `EmojiSourceContractTest.kt`, `EmojiSuggestDefaultSourceContractTest.kt`, `EmojiSearchHideResetSourceContractTest.kt`, `EmojiRecentAndFlingSourceContractTest.kt`, `KeyboardSurfaceMetricsSourceContractTest.kt` | main-классы emoji | код |
| `settings/*SourceContractTest.kt` (DataSources, DisabledRowExplanation, SettingsTapjacking, PersonalEmojiScreen, PersonalBigramScreen, PersonalDictionaryScreen, AppRestrictions, BackupWhitelist, PersonalQuarantineScreen, PersonalDictionaryFeedback, PersonalDictionaryRejectedMessage) | SettingsHostActivity.kt, res, NOTICE.txt | код/строки ресурсов |
| `utils/DialogObscuredTouchContractTest.kt` | DialogUtils + сайты диалогов | код |
| `accessibility/SubtypeSwitchAnnouncementSourceContractTest.kt` | LatinIME/делегат | код |

Вывод: чистка комментариев в main **безопасна** почти для всех, кроме двух делимитеров по
`2026-09-25 audit, F1/F10` в `RichInputConnection.java` — при снятии этой сноски в main нужно
одновременно поправить строки `:131` и `:273`/`:275` в двух тестах.

## Правки

Ниже — представительные находки с реальными строками. Однотипные шапки свёрнуты в
«Системные паттерны». Приоритет: P1 — агентные артефакты/ложь/мусор; P2 — сильное упрощение;
P3 — косметика.

### keyboard/AppleUxStageBContractTest.kt
- `:26-40` — [OVERLONG][JARGON] — KDoc `Stage B of docs/ROADMAP-P8-PLAN.md (all seven items
  authorized by the operator on 2026-09-25)… B1/M5… B7/S2` — **P1**. История миссии + «authorized
  by the operator» + коды B1..B7/M2..S2 + `docs/*.md`. Замена: «Пины тем/структуры iOS-стиля клавиш,
  панелей и диалогов; пиксели проверяются на эмуляторе. Проверяется по исходникам (Robolectric нет).»
- `:195-196` — [AGENT] — `// W5 raised the strip 40dp → 44dp … 2026-09-29 the strip is back to THREE
  cells (the four-cell wave reverted)` — **P2**. Хронология волн не нужна (сам комментарий
  подтверждает, что «3 ячейки» = актуально). Замена: `// Полоса 44dp, три ячейки — смоук тапает
  центры третей.`
- Имя класса `AppleUxStageBContractTest` — [JARGON] «Stage B» в имени. **P3**, не трогать без нужды
  (класс не пинится извне, но переименование — отдельная механическая правка).

### keyboard/AppleUxBatchOneContractTest.kt
- `:25-38` — [OVERLONG][JARGON] — `Stage A of docs/ROADMAP-P8-PLAN.md — the Apple-UX batch-1 values…
  W1… W6… the KeyboardKit master asset… the old #B3B7C0 was the 6.9.4 pin` — **P1**. Замена:
  «Пины значений iOS-темы (тень/фон функц. клавиши/альфа пробела/хаптика), чтобы правка темы их не
  сдвинула. По исходникам.» Коды W1..W6, `6.9.4`, «KeyboardKit master» убрать.

### keyboard/PointerTrackerRobustnessContractTest.kt
- `:25-26` — [AGENT][JARGON] — `The PointerTracker half of the 2026-09-25 input-robustness wave
  (docs/SECURITY-AUDIT-2026-09-25-FIXES.md), pinned at source level for the same reason as…` — **P1**.
  Замена: «Пины устойчивости PointerTracker к битому вводу (по исходникам).»

### keyboard/KeyboardViewDrawLoopContractTest.kt
- `:25` — [JARGON][AGENT] — `O4 of docs/OPTIMIZE-SECURITY-PLAN-2026-09-29.md: the board's draw loop…`
  — **P2**. Убрать `O4`+`docs/…`. Замена: «Пин цикла отрисовки клавиатуры (без аллокаций), по исходнику.»

### keyboard/internal/GlideTrailTest.kt
- `:11` — [JARGON] — `Unit tests for [GlideTrail] (P7-5; grown in P7-7): ring semantics…` — **P3**.
  Убрать `P7-5/P7-7`. Замена: «Тесты [GlideTrail]: кольцевой буфер, порядок, окно 300 мс, фейд.»
- `:67`, `:103` — [JARGON] — `The window is 300 ms (P7-7)`, `--- P7-7: the post-lift fade-out ---` —
  **P3**. Убрать `(P7-7)`.

### keyboard/internal/PointerTrackerQueueTest.kt
- `:25` — [AGENT][JARGON] — `2026-09-25 audit, F13 (docs/SECURITY-AUDIT-2026-09-25-FIXES.md): the
  queue holds each tracker…` — **P2**. Замена: «Очередь держит каждый tracker слабой ссылкой, чтобы…».

### keyboard/internal/KeyboardTextsTableTatarTest.kt
- `:27` — [AGENT][JARGON] — `U1+U2 (docs/AUDIT-2026-08-31.md): the Tatar layout showed "$"…` — **P2**.
  Убрать `U1+U2`+`docs/…`, оставить суть про валюту/ABC.

### keyboard/internal/KeyPreviewDismissAnimatorSourceContractTest.kt
- `:25` — [JARGON] — `P3 (docs/AUDIT-2026-08-31.md) source-contract for the key-preview dismiss
  animator.` — **P3**. Замена: «Source-contract аниматора сокрытия превью клавиши.»

### latin/LogSafetySourceContractTest.kt  (35 % комментариев)
- `:26` — [JARGON] — `Log-safety gate for the input pipeline (S3 of docs/OPTIMIZE-SECURITY-PLAN-…)` —
  **P2**. Убрать `S3`+`docs/…`.
- `:29`, `:62`, `:66` — [AGENT] — `The 2026-09-24 audit accepted the current posture`,
  `De-texted the same day (2026-09-29)`, `Survey result at creation (2026-09-29): 66 call sites` —
  **P1**. История аудита + счётчик «66 call sites». Оставить смысл трёх правил, выкинуть даты и
  «survey result/66». Сжать шапку с ~50 строк до ~12 (три правила).

### latin/RichInputConnectionRobustnessContractTest.kt
- `:26-32` — [AGENT][JARGON] — `The structural half of the 2026-09-25 input-robustness fix wave …
  Plus the S8 pins of the 2026-09-29 wave (docs/OPTIMIZE-SECURITY-PLAN-…)` — **P1**. Сжать до
  «Структурные пины RichInputConnection: paste-fallback ограничен, каждый вызов editor обёрнут.»
- `:210` — [JARGON] — `// S8 (2026-09-29): the F10 property survives the hostile-host catch…` — **P3**.
- `:273`, `:275` — [STALE-риск] — делимитеры `"/**\n * 2026-09-25 audit, F1"`, `"/**\n * Set the
  selection"` — **P1 (не «правка», а предупреждение)**. ⚠ Эти строки завязаны на **комментарий в
  `RichInputConnection.java`**. Чистка сноски `2026-09-25 audit, F1` в main без правки этого
  делимитера уронит тест. Помечено как блокер массовой чистки main.

### latin/RichInputConnectionRobustnessTest.kt
- `:26-27` — [AGENT][JARGON] — `The behavioral half of the 2026-09-25 input-robustness fix wave
  (docs/SECURITY-AUDIT-2026-09-25-FIXES.md)…` — **P2**. Замена: «Поведенческие тесты RichInputConnection
  на битом/враждебном вводе.»

### latin/HostileHostRobustnessTest.kt  (24 % комментариев)
- `:35-45+` — [OVERLONG][AGENT][JARGON] — `S8 of docs/OPTIMIZE-SECURITY-PLAN-… Per-shape verdicts
  (2026-09-29): … HOLED, fixed … the F5 doctrine … F6 dead-editor idiom … O6-pinned` — **P1**.
  Многоабзацный лог расследования с вердиктами HOLED/fixed и цепочкой F3/F5/F6/F7/F8/F10/O6. Свести
  к 3–4 строкам: «Хост владеет InputConnection и может врать/кидать/умирать — IME не должен падать,
  течь или врать про правку. Гоняет реальный RichInputConnection через Android-free швы.»

### latin/InputConnectionBinderContractTest.kt
- `:25-26` — [JARGON] — `O6 of docs/OPTIMIZE-SECURITY-PLAN-… (findings: docs/IC-BINDER-AUDIT-2026-09-29.md)`
  — **P2**. Убрать `O6`+два `docs/…`.
- `:131` — [STALE-риск] — делимитер `"/**\n * 2026-09-25 audit, F10"` — **P1 (предупреждение)**.
  ⚠ Пинит комментарий `RichInputConnection.java`; чистить синхронно с main.

### latin/EditorTextCachePrivacySourceContractTest.kt
- `:24` — [AGENT] — `2026-09-25 audit, privacy: the lifecycle of the editor surrounding-text cache`
  — **P3**. Убрать дату/`audit`. Замена: «Приватность: жизненный цикл кэша окружающего текста editor.»

### latin/CacheTextStartProvenanceTest.kt
- `:26` — [AGENT][JARGON] — `Audit 2026-09-02, C6: "the before-cursor cache starts at the start of
  the text" is PROVENANCE…` — **P2**. Убрать `Audit 2026-09-02, C6`.

### latin/EditorInfoPrivacyMatrixTest.kt  (26 % комментариев)
- `:47` — [JARGON] — `S6 of docs/OPTIMIZE-SECURITY-PLAN-2026-09-29.md — the EditorInfo privacy matrix
  as explicit JVM tests` — **P2**. Убрать `S6`+`docs/…`. ASCII-таблица матрицы (`:50-70`) — оставить,
  она полезна и не слоп.

### latin/ThreeLanguageStringsTest.kt
- `:29` — [AGENT][JARGON] — `Since phase 3b (2026-08-30) those are the only folders: the eighty
  locale folders…` — **P2**. Убрать `phase 3b (2026-08-30)`. Замена: «Остались только values/values-ru/
  values-tt — 80 локалей срезаны осознанно (tt/ru/en).»

### latin/emoji/*
- `EmojiPanelStateTest.kt:37`, `:178`, `:358` — [AGENT][JARGON] — `The search band the grid absorbed
  on 2026-09-28 (docs/EMOJI-PANEL-SPACE-2026-09-28.md, A)` и `item A`/`item C` — **P2**. Убрать даты
  и `docs/…, item A/C`, оставить что именно проверяется.
- `EmojiSuggestIndexTest.kt:81`, `:83` — [AGENT] — `2026-09-25 audit, F16 (mirrors SentStartIndex):
  the record count is capped fail-closed … raised 4096 -> 8192 on 2026-09-28` — **P2**. [SLOP]
  «fail-closed» + история пина 4096→8192. Замена: «Число записей ограничено сверху (пин 8192).»
- `AtomicRecentEmojiFileOpsTest.kt:27` — [AGENT] — `The recents medium's read side (2026-09-24 audit,
  finding 11)` — **P3**. Убрать `(2026-09-24 audit, finding 11)`.
- `EmojiPanelSourceContractTest.kt:125` — [JARGON] — `// --- O4 (docs/OPTIMIZE-SECURITY-PLAN-…):
  measurement and invalidation ---` — **P3**. Убрать `O4`+`docs/…`.
- `SharedEmojiSearchIndexTest.kt:29` — [AGENT][JARGON] — `Audit 2026-09-02, C7: one parsed
  [EmojiSearchIndex] per process…` — **P3**. Убрать `Audit 2026-09-02, C7`.
- `EmojiSearchTest.kt:202` — [AGENT][JARGON] — `Audit docs/AUDIT-2026-08-31.md, finding m2: CLDR has
  no Tatar annotations…` — **P3**. Убрать `Audit … finding m2`.
- `EmojiPanelAccessibilitySourceContractTest.kt:64` — [AGENT] — `// O2 (2026-09-25): the helper is
  the AOSP fork in our compat package` — **P3**. Убрать `O2 (2026-09-25)`.

### latin/settings/*
- `DataSourcesScreenSourceContractTest.kt:24-45` — [AGENT][OVERLONG] — `Mission tt-corpus-os,
  decision 2… The operator decided on 2026-08-24 to use word frequencies derived from OpenSubtitles…
  On 2026-08-24 mission tt-dict-accept accepted 27 134 Russian and 226 Tatar forms… docs/DICT-ACCEPT.md`
  — **P1**. Классический агентный нарратив: «operator decided», код миссии, пины счётчиков 27134/226,
  `docs/…`. Свести к 4–6 строкам сути: «Экран источников данных существует, достижим, содержит ссылку
  на opensubtitles.org и указывает, что данные — в поставке. Пин по исходнику (это Activity).»
- `:103` — [AGENT] — `// … the 2026-09-24 wave moved the link to https` — **P3**. Убрать дату.
- `PersonalDictionaryFeedbackSourceContractTest.kt:80`, `:157` — [AGENT][JARGON] — `U7 (2026-09-23)…`,
  `(9th exit 2026-09-23: U7's per-language clearWords, docs/ROADMAP-P2.md.)` — **P2**. Убрать `U7`,
  даты, `9th exit`, `docs/…`.
- `PersonalDictionaryRejectedMessageSourceContractTest.kt:25` — [AGENT][JARGON] — `Audit
  docs/AUDIT-2026-08-31.md, finding m1: the add-word rejection toast…` — **P3**. Убрать `finding m1`+`docs/…`.
- `KeyboardColorPreferenceTest.kt:24` — [AGENT] — `The 2026-09-25 SAFE wave: readKeyboardColor used
  to evaluate…` — **P3**. Убрать `2026-09-25 SAFE wave`.
- `EmojiPanelHeightPreferenceTest.kt:25` — [JARGON] — `Item B of docs/EMOJI-PANEL-SPACE-2026-09-28.md:
  the "Emoji panel height" Appearance row…` — **P3**. Убрать `Item B of docs/…`.
- `DisabledRowExplanationSourceContractTest.kt:24` — [JARGON][AGENT] — `C1 of docs/ROADMAP-P8-PLAN.md,
  closing F15(a) of docs/AUDIT-2026-09-24-FIXES.md` — **P2**. Убрать `C1`/`F15(a)`/два `docs/…`.
- `SettingsTapjackingSourceContractTest.kt:25` — [JARGON] — `S1 (docs/AUDIT-2026-08-31.md)
  source-contract for the tapjacking fix` — **P3**. Убрать `S1 (docs/…)`.

### latin/inputlogic/*
- `BatchEditPairingContractTest.kt:24-39` — [OVERLONG][AGENT][JARGON] — `2026-09-25 audit, F5
  (docs/SECURITY-AUDIT-…): every begin/endBatchEdit pair… 2026-09-29 audit wave, S8
  (docs/OPTIMIZE-SECURITY-PLAN-…): the F5 "nothing is caught" doctrine… the F6 dead-editor idiom…
  O6-pinned` — **P1**. Два датированных абзаца с «доктринами». Свести к: «Каждая пара
  begin/endBatchEdit в InputLogic и RichInputConnection — try/finally; вызовы editor обёрнуты в
  catch(RuntimeException), чтобы падение хоста не убило IME. По исходнику.»
- `CommitPathConnectionContractTest.kt:24-32` — [AGENT][JARGON] — `Mission tt-personal-dict, finding
  A4 of docs/SILENT-AUDIT.md: the three insertion paths…` + `:65 // 2026-09-24: it deletes and
  commits…` — **P2**. Убрать миссию/`finding A4`/`docs/…`/дату.

### latin/utils/DialogObscuredTouchContractTest.kt
- `:23-38` — [AGENT] — `Audit 2026-09-02, C5: every dialog…` + `A sibling rule lives here too (audit
  2026-09-25): the activity-wide FLAG_SECURE…` — **P2**. Убрать `Audit … C5` и `(audit 2026-09-25)`,
  оставить два правила (filterTouchesWhenObscured + FLAG_SECURE на окне диалога).

### accessibility/SubtypeSwitchAnnouncementSourceContractTest.kt
- `:22` — [JARGON] — `U3 source-contract for the TalkBack language-switch announcement.` — **P3**.
  Убрать `U3`. Остальной список пунктов шапки — по делу, оставить.

### app/src/androidTest/* (инструментальные)
- `keyboard/DrawAllocInstrumentationTest.kt:39`, `:58`, `:76`, `:107`, `:113` — [AGENT][JARGON] —
  `O3 (docs/OPTIMIZE-SECURITY-PLAN-…): the fail-closed allocation gate…`, `Provenance (POCO C71,
  HyperOS, 2026-09-29): same-state redraws…` — **P2**. Убрать `O3`/`docs/…`/`POCO C71, HyperOS,
  2026-09-29`. Калибровочный комментарий про 720×1640 — оставить (нужен для запуска).
- `latin/glide/GlideUiDeviceTest.kt:28`, `:34`, `:38` — [AGENT] — `The 2026-09-24 field fix on the
  live UI (docs/ROADMAP-P7.md)… P7-7 (2026-09-25): the commits carry NO auto-space… the field report
  of 2026-09-25` — **P2**. Убрать даты/`P7-7`/`docs/…`/«field report».
- `latin/dictionary/engine/DictionaryIoStrategyInstrumentationTest.kt:29`, `:31`, `:54` — [AGENT][JARGON]
  — `O7 (docs/OPTIMIZE-SECURITY-PLAN-…)… (wave-3 POCO session; … docs/PERF-BUDGETS.md…)` — **P2**.
- `latin/glide/GlideDeviceInstrumentationTest.kt:41`, `:103` — [AGENT][JARGON] — `O2
  (docs/OPTIMIZE-2026-09-25.md): the price of the idle index release`, `сәләм decode … on real
  hardware` — **P3**.
- `latin/emoji/EmojiIndexReloadInstrumentationTest.kt:24` — [JARGON] — `O2 (docs/OPTIMIZE-2026-09-25.md):
  the device-side price of the emoji indexes' idle release` — **P3**.
- `latin/dictionary/engine/E3bComputeInstrumentationTest.kt:189` — [AGENT][JARGON] — `// Internal
  since 2026-09-29 (O7, docs/OPTIMIZE-SECURITY-PLAN-…)` — **P3**.
- `keyboard/GlidePointerDeviceTest.kt:36` — [AGENT][JARGON] — `The 2026-09-24 field fix
  (docs/ROADMAP-P7.md), proven on the device` — **P3**.
- `AndroidManifest.xml:1-8` — [JARGON][AGENT] — `E3b/Phase-B instrumental harness manifest… asserted
  by the E3b zero-byte-delta proof, docs/archive/missions/DICTIONARY-E3.md` — **P2**. Суть (нужен
  `uses-library android.test.runner` для legacy-раннера) — оставить; убрать `E3b/Phase-B` и ссылку
  на архивный `docs/archive/missions/DICTIONARY-E3.md`.

### baselineprofile/src/main/java/org/tatarkeyboard/baselineprofile/ImeBaselineProfileGenerator.java
- `:38-84` — [OVERLONG][JARGON][AGENT] — KDoc `Phase 4b: Baseline Profile generator… mission M4…
  mission M4b, PREF_EMOJI_SUGGESTIONS… (P2 of docs/AUDIT-2026-08-31.md)… 2026-09-24 audit, finding 3…
  (mission M4)… (P7)` — **P2**. ~47 строк CUJ-нарратива с кодами миссий/фаз/аудитов. Оставить ~10
  строк: что за CUJ (холодный старт IME → набор «сәлам» → подсказка/пробел/эмодзи/глайд/панель) и
  как запускать. Убрать `Phase 4b`, `M4`/`M4b`, `P2`/`P7`, `2026-09-24 audit, finding 3`, `docs/…`.
- `:290-305` (KeyGeom KDoc) — [AGENT] — `Strip geometry verified 2026-08-31 against screenshots…
  (verified 2026-09-02 …)… after the 2026-09-28 search-band collapse` — **P3**. Убрать даты
  «verified …», оставить геометрию и что тапы дают «сәлам».
- `:106-110` (NOTE о component id) — оставить: это полезная платформенная готча, не слоп.

### app/src/test/resources/tt_eval_sentences.txt
- `:1-18` (шапка) — [JARGON] — ссылки `docs/CORPUS-CONVERSATIONAL-TT.md`, `research/corpus/…`,
  `scripts/…`, `dump Tatoeba-v2026-07-08` — **P3**. Лицензионная атрибуция CC BY (Tatoeba) — **оставить
  обязательно**. Убрать только внутренние ссылки `docs/*.md` (при архивации станут битыми), оставив
  ссылку на `assets/dictionaries/NOTICE.txt`.

## Системные паттерны

1. **Шапка `*ContractTest` = код-миссии + `docs/*.md` + дата + лог расследования.** Самый массовый
   дефект области. Grep-маркеры для массовой чистки (по `app/src/test/**` и `app/src/androidTest/**`):
   - `rg -n "docs/[A-Z0-9-]+\.md" app/src/test app/src/androidTest baselineprofile` — все ссылки на
     доки (архивируются → станут битыми).
   - `rg -n "2026-[0-9]{2}-[0-9]{2}|2026-[0-9]{2}\b" …` — датированные сноски.
   - `rg -n "\b(S[0-9]|O[0-9]|F[0-9]+|W[0-9]|B[0-9]|M[0-9]|U[0-9]|C[0-9]|OD-[0-9]|P[0-9]-[0-9]|Phase [A-Z0-9])\b" …`
     — коды находок/миссий/фаз.
   - `rg -n "operator|mission |audit\b|HOLED|doctrine|field report|Survey result" …` — агентный нарратив.
   **Общее правило:** оставить одну–две строки «что этот тест пинит и почему по исходнику (нет
   Robolectric)»; убрать коды миссий/аудитов, даты, `docs/*.md`, счётчики и «доктрины».

2. **Пины счётчиков/значений в прозе комментария** (`66 call sites`, `27 134/226 forms`, `4096 -> 8192`,
   `#B3B7C0 was the 6.9.4 pin`). Число, которое реально пинится, живёт в `assertEquals` — в
   комментарии оно только устаревает. Правило: числа из пояснений убрать, оставить в ассерте.

3. **`latin/golden/` — чужеродный слой для iOS/Swift-порта** (`ios/docs/VERIFICATION.md`,
   `ios/tools/export-key-geometry`, «Swift port», «iOS parity suite»). 5 инертных файлов, на них не
   ссылается ни CI, ни скрипты. Правило: если iOS-порт закрыт — удалить папку целиком; если жив —
   вынести в отдельный модуль/пометить и всё равно вычистить `docs/*.md` из шапок.

4. **Делимитеры по тексту комментария main.** Маркер: `rg -n '"/\*\*.*2026-' app/src/test` находит
   тесты, режущие исходник по датированной сноске (`InputConnectionBinderContractTest:131`,
   `RichInputConnectionRobustnessContractTest:273`). Правило: при чистке комментариев в
   `RichInputConnection.java` править эти делимитеры в тот же коммит, иначе `./gradlew test` красный.
   Делимитеры по нейтральному AOSP-javadoc («Gets the current auto-caps state», «Set the selection»,
   «Allocation-free suffix test») безопасны — их не трогаем.

5. **«fail-closed» как украшение** (EmojiSuggestIndexTest, DrawAllocInstrumentationTest). Где это
   просто «есть верхняя граница» — заменить на нейтральную формулировку; термин оставить только там,
   где он несёт смысл (реальное поведение при отказе).

6. **LANG/смешение — почти нет.** Комментарии единообразно английские (стиль AOSP-форка); русский —
   только осмысленно (татарские слова «сәлам/сәламәтлек» как тестовые данные). Отдельной кампании
   по языку не требуется.

7. **STALE — не подтвердилось в моей области.** Проверка «3 ячейки» показала обратное: комментарии
   актуальны (`AppleUxStageBContractTest:196` прямо пишет «back to THREE cells»,
   `SuggestionStripStateTest` пинит `CELL_COUNT == 3`). Классы опечаток #5 / live-preview глайда в
   моих файлах не упоминаются. Ложных комментариев не найдено — проблема в объёме и агентном стиле,
   не в фактической неправде.

## Phase 5 result: androidTest/ and baselineprofile/ (WP5.2)

Comments only; no code, identifiers, string literals or assertions changed
(`git diff -U0` over the area: every changed line is a comment line or inside an XML comment).

Files changed (10): `app/src/androidTest/AndroidManifest.xml`, `DrawAllocInstrumentationTest.kt`,
`GlidePointerDeviceTest.kt`, `DictionaryIoStrategyInstrumentationTest.kt`,
`E3bComputeInstrumentationTest.kt`, `EmojiIndexReloadInstrumentationTest.kt`,
`GlideDeviceInstrumentationTest.kt`, `GlideUiDeviceTest.kt`, `baselineprofile/build.gradle`,
`ImeBaselineProfileGenerator.java`. Net: +167 / −258 lines.

What was done:
- Removed mission/phase/finding codes (O2/O3/O5/O7, P7-x, A1, M4/M4b, E3b/Phase-B, "wave"),
  dates, `docs/*.md` links, "field report", device-run provenance and measured numbers in prose
  (18, 2148, 1565, 3.306, 31.6 ms, ~50 ms, ~7.5 s).
- Generator class KDoc: the CUJ narrative is now a five-item list of the journeys it runs plus
  two lines on the pref setup and how to run it (device pinning refers to `build.gradle`).
- DrawAlloc class KDoc shrunk; the StateListDrawable 2-allocations-per-toggle platform quirk is
  kept (it explains `BOARD_TOGGLE_FLOOR_BOUND`).
- Terminology per Appendix D: band → strip, slot → cell, prefix suggestion → word completion.
- Kept: 720x1640 / POCO C71 calibration notes (needed to run the tests), the component-id NOTE
  and the force-stop/IME-reset NOTE in the generator.

Metrics (brief checks, area = `app/src/androidTest baselineprofile`):

| check | before | after |
|---|---|---|
| `docs/*.md` / PROPOSALS | 13 | 0 |
| dates | 20 | 0 |
| Cyrillic in comments | 38 | 32 (all quoted Tatar/Russian test words: сәлам, сәләм, ә→л→ә→м, …) |
| operator/uncommitted/handoff/mission | 2 | 0 |

No test pins comment text in this area (`app/src/test` has no reference to androidTest or
baselineprofile). Open point: `build.gradle` used to say the generated profile lands in
`app/src/main/generated/baselineProfiles/`; it now says `app/src/release/generated/baselineProfiles/`,
following `AGENTS.md` and AGP's per-variant default. Not verified by a run (Gradle not run).
