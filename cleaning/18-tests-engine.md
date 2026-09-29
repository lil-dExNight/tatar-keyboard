# JVM-тесты (dictionary/**, suggestions/, glide/) — ревизия текстов

Область: `app/src/test/java/rkr/simplekeyboard/inputmethod/latin/` — подпакеты
`dictionary/` (корень + `engine/`, `personal/`, `personalstore/`, `storage/`),
`suggestions/`, `glide/`. Всего **137 .kt-файлов, 36 479 строк**.

## Итог

- **Код тестов чист и консистентен, проблема — в текстах.** CELL_COUNT=3 пинится
  везде (`SuggestionStripStateTest.kt:219 assertEquals(3, …CELL_COUNT)`), ни одной
  ссылки на удалённый функционал (нет live-превью глайда, нет класса опечаток #5,
  нет версии 3.6.0/43), ноль реальных `@Ignore`, тест-дубли отсутствуют. Файлов
  на удаление практически нет.
- **~16% строк — комментарии** (5 658 / 36 479); в eval/калибровочных тестах доля
  20–29%. Почти каждый файл открывается **кодом миссии + ссылкой на `docs/*.md`**.
- **Находки по категориям (грубо):** [AGENT] датированные сноски `2026-xx-xx` —
  **108** в ~50 файлах; [JARGON] ссылки на `docs/*.md` — **108** на **~30 разных
  доков** (все под архивацию → ссылки станут битыми) + десятки кодов миссий
  (E3a/E3b, D3, E4a–E4d, P7-x, C3, S5, O7, S8, Phase B/C, SIZE-2, ROADMAP-Pn,
  T5/T7); [AGENT] «mission»/«operator»/«audit finding N» — ~15 файлов;
  [OVERLONG] абзацы истории «re-pinned 2026-09-29 (three cells again…)».
- **⚠ Блокиратор чистки main:** `SuggestionStripSourceContractTest` пинит **текст
  комментария** «Do not log the returned value» в `RichInputConnection.java`
  (реально есть, строки 549/573). Полный список source-contract тестов и читаемых
  ими файлов main — в отдельном разделе ниже (нужен агентам, чистящим main).
- **Топ-3 действия:** (1) массово вычистить датированные сноски / коды миссий /
  ссылки на `docs/*.md` из KDoc, оставив 1–2 строки о назначении; (2) свернуть
  блоки «re-pin history» в eval/калибровочных тестах до одной строки; (3)
  заменить ссылку `docs/X.md` пояснением сути прямо в комментарии.

---

## Source-contract тесты и файлы main, которые они читают

Эти тесты читают исходники main как ТЕКСТ и проверяют форму кода (сигнатуры,
вызовы). В большинстве пинятся идентификаторы кода (это устойчиво к чистке
комментариев), НО отмечены случаи, где пинится текст комментария/строки —
их чистка в main уронит тест.

| Тест (моя область) | Файлы main, читаемые как текст | Пинит текст комментария? |
|---|---|---|
| `suggestions/SuggestionStripSourceContractTest.kt` | `SuggestionStripView.kt`, `InputView.java`, `LatinIME.java`, `inputlogic/InputLogic.java`, `RichInputConnection.java`, `suggestions/SuggestionsController.kt`, `suggestions/SuggestionsOfferController.kt`, `InputAttributes.java`, `settings/Settings.java`, `SettingsValues.java`, `SettingsHostActivity.kt`, `res/layout/input_view.xml`, `res/layout-v28/input_view.xml`, `res/layout/suggestion_strip.xml`, `res/values/attrs.xml`, `themes-tatar.xml`, `res/values/strings.xml`, `res/values-tt/strings.xml` | **ДА** — `RichInputConnection.java` комментарий «Do not log the returned value» (стр. 549/573) |
| `suggestions/AutocorrectSourceContractTest.kt` | `inputlogic/InputLogic.java`, `suggestions/SuggestionsController.kt`, `RichInputConnection.java` + обход всего main (скан composing-API) | нет (идентификаторы/вызовы) |
| `suggestions/CursorMoveBandSourceContractTest.kt` | `suggestions/SuggestionsController.kt`, `LatinIME.java`, `RichInputConnection.java` | нет |
| `suggestions/GlideIndexResidencySourceContractTest.kt` | `suggestions/SuggestionsController.kt` | нет |
| `suggestions/SuggestionStripDecorationContractTest.kt` | `SuggestionStripView.kt` | нет |
| `suggestions/SuggestionsPackagePrivacyTest.kt` | все исходники пакета `suggestions/` (скан на Log/сеть) | нет |
| `glide/GlideSourceContractTest.kt` | `glide/*.kt` + `suggestions/GlideKeyGeometryBuilder.kt` (скан кириллицы/Log/Android-import) | нет |
| `glide/GlideTouchIntegrationContractTest.kt` | `keyboard/PointerTracker.java`, `LatinIME.java`, `LatinImeGlide.java`, `inputlogic/InputLogic.java`, `suggestions/SuggestionsController.kt`, `suggestions/SuggestionSurfaces.kt`, `settings/Settings.java`, `SettingsValues.java`, `SettingsHostActivity.kt` | нет (вызовы/идентификаторы) |
| `glide/GlideTrailContractTest.kt` | `keyboard/PointerTracker.java`, `MainKeyboardView.java`, `internal/DrawingProxy.java`, `res/values/attrs.xml`, `themes-tatar.xml`, `colors.xml`, `values-night/colors.xml`, `config.xml` | нет |
| `dictionary/engine/E3bEngineSourceContractTest.kt` | `dictionary/engine/*.kt` + `suggestions/KeyNeighborTableBuilder.kt` (скан кириллицы/Log) | нет |
| `dictionary/engine/DictionaryEnginePrivacyTest.kt` | `dictionary/engine/*` (скан + рефлексия имён методов) | нет |
| `dictionary/personal/PersonalDictionaryContractTest.kt` | обход main (контракт) | нет |
| `dictionary/personal/PersonalSubtypeRegistryContractTest.kt` | `DictionaryArtifactSpec` + main (форма делегирования) | нет |
| `dictionary/personal/PersonalDictionaryReadPathPrivacyTest.kt` | исходники пакета `personal/` | нет |
| `dictionary/personalstore/PersonalDictionaryNoLiveWriteSourceContractTest.kt` | `LatinIME.java`, `suggestions/SuggestionsController.kt` + обход `java/` | нет (вызовы) |
| `dictionary/personalstore/PersonalStoragePathSourceContractTest.kt` | `AndroidPersonalDictionaryStorage.kt` + пакет `personalstore/` | нет |
| `dictionary/personalstore/PersonalQuarantineNoticeSourceContractTest.kt` | `PersonalDictionaries.kt`, `LatinIME.java` | частично — пинит имена методов/строки уведомления |
| `dictionary/personalstore/PersonalEmojiQuarantineNoticeSourceContractTest.kt` | те же (эмодзи-сторона) | частично |
| `dictionary/personalstore/PersonalLearningGatesTest.kt` | фабрика sink + `LatinIME.java` (пинит `mIsPostalAddressField`) | нет (идентификатор) |
| `dictionary/personalstore/PersonalBigramLearningGatesTest.kt` | фабрика sink + `LatinIME.java` | нет |
| `dictionary/personalstore/PersonalDictionarySilentFailureTest.kt` | частично source-contract | нет |
| `dictionary/storage/DictionaryStoragePrivacyTest.kt` | исходники пакета `storage/` (пинит `throw IOException`) | нет (идентификатор) |

Вывод для агентов main: **единственный текст-комментарий, пиннутый из моей
области — «Do not log the returned value» в `RichInputConnection.java`.** Всё
остальное — идентификаторы кода, устойчивы к чистке комментариев.

---

## Файлы/код на удаление

| Путь (или символ) | Размер | Почему | Кто ссылается (grep) | Риск |
|---|---|---|---|---|
| `dictionary/engine/TtTypoPhaseBCalibrationTest.kt` | 459 стр. | Меряет НЕвышедшую политику `{1,2}` (по KDoc `TdictPrefixIndexShippedFuzzyClassesTest`: «Phase-B candidate {1,2} failed gate G1 and was never wired»). Как ship-калибровка своё отработала. | Самостоятелен; на его имя не ссылаются другие тесты | СРЕДНИЙ — служит регрессией генераторов класса #2; удалять только по решению владельца |
| `dictionary/engine/TtTypoPhaseBPrecisionTest.kt` | 176+ стр. | Та же Phase B (гейт G2 precision невышедшей ветки). | Самостоятелен | СРЕДНИЙ — как выше |

Полноценных кандидатов на удаление в области **нет**: тест-дерево живое, дублей
нет, `@Ignore` нет, тестов удалённого функционала нет. `PersonalDictionaryReader.kt`
живёт в test-sourceset и используется (`PersonalDictionaryReaderTest`,
`PersonalDictionaryTextContractTest`) — НЕ мёртвый. Пять fuzz-тестов —
зеркала, но каждый проверяет свой валидатор — НЕ дубли.

---

## Правки

Приоритеты: **P1** ложь/мусор/агентные артефакты · **P2** сильное упрощение ·
**P3** косметика. Ни одна из правок ниже не пинится source-contract тестами
(это комментарии в самих тестах — их чистка безопасна), если не отмечено иначе.

### dictionary/engine/TtSuggestEvalTest.kt (29% комментариев — рекордсмен)

- `:55–63` — [AGENT][OVERLONG] — «2026-09-29 (back to three cells): the 2026-09-27
  four-cell wave is reverted by an operator UX decision (docs/GLIDE-LIVE-STRIP4.md
  footnote)…» — целый абзац истории волн и «operator UX decision». **Замена:**
  «Метрики считаются по трём ячейкам (`CELL_COUNT=3`). Татарская таблица упакована
  при K=4, но `TatBigrPrefixIndex.MAX_RESULTS=3`, поэтому топ-3 совпадает с
  `scripts/suggest_eval.py`.»
- `:36–39` — [JARGON] — «TT-SUGGESTIONS phase P0 (docs/TT-SUGGESTIONS-PLAN.md)…» —
  код фазы + ссылка на док. **Замена:** «Гоняет реальные татарские словарь и
  биграммы по held-out eval-набору `tt_eval_sentences.txt` и печатает метрики
  `EVAL|metric|value`.»
- `:64–71` — [SLOP] — «forces a conscious re-pin, which is exactly what the
  mission's before/after discipline needs» — пафос про «mission discipline».
  **Замена:** «Числа ниже — детерминированная функция закоммиченных байт;
  при изменении ассета/eval-набора/ранжирования пере-пинить осознанно.»
- `:297–334` — [AGENT][OVERLONG] — блок `companion object` с историей пере-пинов
  («Pins measured on 2026-09-19… recalibrated 2026-09-23 (ROADMAP-P4 P5a + T7)…
  Re-pinned 2026-09-29 (three cells again…): 547 -> 471»), повторяется у ~8
  констант. **Замена:** одна строка над блоком: «Пины по закоммиченным ассетам;
  при изменении — пере-пинить.», у констант убрать даты/стрелки переходов.

### dictionary/engine/TtTypoPhaseCCalibrationTest.kt / TtTypoPhaseBCalibrationTest.kt / TtTypoPhaseBPrecisionTest.kt

- `PhaseCCalibration:34–46` — [JARGON][AGENT] — «TT-TYPO-NEXT Phase C calibration
  (docs/TT-TYPO-NEXT-PLAN.md + the 2026-09-20 orchestrator amendment relaxing G2
  to <= 25 %…). AMENDED 2026-09-20 (orchestrator, recorded in docs/TT-TYPO-NEXT.md):
  G2-C2…» — коды гейтов, «orchestrator», даты, ссылки на 2 дока. **Замена:**
  «Калибровка класса #4 (полная одиночная замена по алфавиту, активна при пустом
  точном проходе и ≥4 code points). Гейты: recovery@3 ≥ 1.5× baseline, precision
  ≤ 25%.»
- `PhaseCCalibration:479–493` — [AGENT] — «Exact pins of the Phase-C measurements
  (2026-09-20, committed 110k asset + eval set)… Re-pinned 2026-09-29 (three cells
  again — the four-cell wave reverted…)» — даты/история. **Замена:** «Пины
  Phase-C по закоммиченным ассетам; пере-пинить осознанно.»
- `PhaseBCalibration:359–363` — [AGENT] — «Re-pinned 2026-09-29 (three cells again
  — the four-cell wave reverted); the four-cell excursion measured 8 108 / 45 263
  / …» — числа несуществующей ветки. **Замена:** удалить строку про four-cell
  excursion; оставить пин текущих чисел без истории.
- `PhaseBCalibration:34`, `PhaseBPrecision:31,46` — [JARGON] — «TT-TYPO-NEXT
  Phase B, gate G2 (precision; docs/TT-TYPO-NEXT-PLAN.md, written before
  measuring)…». **Замена:** «Гейт precision: доля корректных префиксов, получивших
  нечёткий кандидат в топ-3, ≤ 25%.» (при удалении файлов — см. раздел «на удаление»).

### dictionary/engine/AutocorrectWideningCalibrationTest.kt

- `:35–37` — [JARGON][AGENT] — «ROADMAP-P3 P7 calibration (docs/ROADMAP-P3.md,
  gates G1–G4 written 2026-09-23 BEFORE measuring): widening the D3 autocorrect
  edit classes from {1} to {1, 4}…». **Замена:** «Калибровка автозамены:
  классы правки {1} vs {1,4} (probe-first полная замена), частотный пол 411.
  Только татарский.»
- `:475` — [AGENT] — «Exact pins of the P7 measurements (2026-09-23); re-pin
  consciously. The verdict was…». **Замена:** «Пины по закоммиченным ассетам;
  пере-пинить осознанно.»

### dictionary/engine/DictionaryIoStrategyCalibrationTest.kt

- `:16–37` — [JARGON][SLOP][OVERLONG] — «O7 (docs/OPTIMIZE-SECURITY-PLAN-2026-09-29.md):
  the host leg of the dictionary I/O-strategy experiment. The plan item's premise…
  is inverted in the tree as found…» — многоабзацная простыня с кодом O7 и
  «wave-3 POCO session». **Замена:** «Сравнивает две I/O-стратегии словаря (mmap
  vs heap `ByteBuffer.wrap`) на реальном ассете: байтовая идентичность результатов
  + p50/p95 ≤ 5 мс + равенство аллокаций.»

### dictionary/engine/E3aRecoveryCalibrationTest.kt / E3bRecoveryCalibrationTest.kt

- `E3b:44–47` — [AGENT] — «2026-09-20 (TT-TYPO-NEXT Phase B): the fixture geometry
  is now device-true… exactly as since the 2026-07-27 E3b verdict. The "full
  engine" phrasing above is the historic…». **Замена:** «Геометрия фикстуры
  device-true; классы правки #1+#2+#3.»
- `E3b:304–320`, `E3a:254` — [AGENT] — «Recalibrated 2026-09-20 (TT-SUGGESTIONS
  P2) for the 110 000-entry dictionary… Contract threshold (amendment 2026-07-27):
  recovery@3…». **Замена:** оставить смысл порога («recovery@3 после E3b ≥ 2.4×
  baseline»), убрать даты/коды.
- `E3a:32` (KDoc) — [JARGON] — «E3a calibration: recovery@3 of edit class #1
  (letter -> long-press partner) on the REAL committed dictionary» — «E3a» без
  расшифровки. **Замена:** «Калибровка recovery@3 класса правки #1 (буква →
  long-press-партнёр) на реальном словаре.»

### glide/GlideRecoveryCalibrationTest.kt

- `:22–40` — [JARGON] — «P7-1 calibration (docs/GLIDE-PLAN.md, docs/ROADMAP-P7.md)…
  Gates (written in the plan before any code): G1… G2… G3…». **Замена:**
  «Калибровка глайд-декодера на реальном словаре и синтетическом наборе жестов
  (регенерируется бит-в-бит, сверяется по SHA-256 с `tests/glide_pack/`). Гейты:
  top-3 recovery ≥ 60%, top-1 ≥ 35%, p95 ≤ 2 мс, 0 аллокаций.»

### dictionary/personalstore/PersonalQuarantineNoticeSourceContractTest.kt / *SilentFailureTest / *QuarantineSalvageTest / *QuarantineRecoveryTest

- `Notice:26` — [AGENT][JARGON] — «Mission `tt-version-1.8.2`, findings B2 and B3
  of `docs/SILENT-AUDIT.md`…». **Замена:** «Проверяет путь от воркера стора до
  фразы, которую видит пользователь (карантинное уведомление). Форма кода, т.к.
  нужен живой `InputMethodService`.»
- `Notice:61,105,162,249` — [AGENT] — «open() split… when the notice became
  durable (mission …)», «2026-09-24 audit, finding 14/13: …», «Mission
  `tt-quarantine`, B3 and B5…». **Замена:** убрать «mission»/«audit finding N»,
  оставить, что именно проверяет строка.
- `SilentFailure:39` — [JARGON] — «register in `docs/SILENT-AUDIT.md` — A2, A3,
  A5, B1, and the last two, B2 and B3.» **Замена:** «Разные режимы тихого сбоя:
  файл откладывается в карантин, уведомление срабатывает, неудачное удаление
  отвечает.»
- `QuarantineSalvage:34,315` / `QuarantineRecovery:38,119` — [AGENT] — «Mission
  `tt-quarantine`, task 1: version 1.8.2 stopped destroying…», «The point of the
  whole mission: the words come back…». **Замена:** «Нечитаемый личный словарь не
  уничтожается, а откладывается; слова возвращаются в список после восстановления.»

### dictionary/storage/TatBigrValidatorTest.kt / TdictValidatorTest.kt

- `TatBigr:52–72` — [AGENT][OVERLONG] — «Schema 3 since 2026-09-01 (SIZE-2,
  docs/SIZE-SCHEMA3.md): content carried over… 40 734 -> 40 735. Repacked
  2026-09-23 (ROADMAP-P4 batch A…): T7… Repacked 2026-09-27 at K = 4 (the
  four-cell…)» — вся история перепаковок с датами/кодами/числами переходов.
  **Замена:** одна строка «Числа — из закоммиченного ассета (schema 3, K=4);
  сверяются с пинами `BigramStorageContracts`.»
- `Tdict:48` — [AGENT] — «110 000 / 1 276 289 since 2026-09-20 (TT-SUGGESTIONS P2,
  docs/TT-SUGGESTIONS.md):» — дата+код над пином. **Замена:** оставить числа,
  убрать «since 2026-09-20 (TT-SUGGESTIONS P2…)».

### dictionary/**/*FuzzTest.kt (5 файлов) — [DUP]

- `SeededFuzzHarness.kt:24`, `Tpers/Tpersb/Tpersem/Tdict/TatBigr FuzzTest` KDoc —
  [DUP][JARGON] — идентичная преамбула повторяется 5–6 раз: «Seeded deterministic
  fuzzing of [X] — S5 of `docs/OPTIMIZE-SECURITY-PLAN-2026-09-29.md`… Zero
  dependencies: mutations come from a seeded java.util.Random via [SeededFuzzHarness];
  a fixed seed per shape makes every run byte-identical, and on a property violation
  the failure message names shape + seed + iteration + the touched offsets…».
  **Замена (в каждом файле):** «Сид-фаззинг [X] через [SeededFuzzHarness]:
  детерминирован (сид+итерация воспроизводят вход), проверяет что валидатор либо
  принимает, либо кидает свой тип исключения.» Убрать «S5» и ссылку на док.

### dictionary/engine/TtNextWordFillE2ETest.kt / TtNextWordPredictP95Test.kt / ShippedIndexPrefixCoverageTest.kt

- `Fill:39,47–48` — [AGENT] — «The operator's scenario (2.0.0): after accepting
  `сәләм` the strip offered only `сәләмә`…», «2026-09-29: the strip is three cells
  again (the four-cell wave of 2026-09-27 reverted)…». **Замена:** оставить
  описание сценария («после принятия `сәләм` полоса заполняется формами и
  fallback-топом»), убрать «operator's scenario (2.0.0)» и историю волн.
- `PredictP95:34` — [AGENT][JARGON] — «The 2026-09-25 SAFE wave
  (docs/OPTIMIZE-2026-09-25.md): a p95 pin over the FULL composite NEXT_WORD
  predict path…». **Замена:** «p95 полного композитного пути NEXT_WORD (биграммы →
  формы → топ-fallback) на реальных ассетах.»
- `ShippedIndexPrefixCoverage:18–24,75` — [AGENT][JARGON] — «Written while hunting
  the blank band of `docs/SUGGEST-DIES.md`…», «The four prefixes of the operator's
  report». **Замена:** «Префикс, который словарь продолжает, никогда не даёт
  пустую полосу. Свип по shipped-ассетам с включённой key-neighbour таблицей.»

### suggestions/TapReproTest.kt

- `:26–30` — [AGENT] — «Mission tt-tap-repro. Reproduces… the two defects the
  operator saw on 1.8.0… The symptom-1 test was @Ignore'd until mission tt-final:
  the operator's 2026-08-22 decision…». **Замена:** «Регрессия двух дефектов
  полосы: (1) курсорная бухгалтерия рассинхронизировалась, (2) то, что полоса
  рисует — должно быть кликабельно. Движок-фейк имеет реальную latest-only
  токен-семантику, чтобы выражать «сброшенный результат».» (упоминание @Ignore
  в тексте — историческое, тест не @Ignore'нут; убрать).

### suggestions/DictionaryUnavailableProvenanceTest.kt

- `:30` — [AGENT][JARGON] — «Mission `tt-personal-dict`, finding A1 of
  `docs/SILENT-AUDIT.md`: the provenance of a preparation request.» **Замена:**
  «Провенанс запроса на подготовку словаря: `DictionaryUnavailableListener`
  срабатывает только когда пользователь сам включил настройку.»

### suggestions/HostileHostSuggestionChurnTest.kt

- `:26–33` — [JARGON] — «S8 of `docs/OPTIMIZE-SECURITY-PLAN-2026-09-29.md`,
  suggestion side of the rapid start/finish-input churn shape…». **Замена:**
  «Враждебный хост гоняет IME циклами start/finish: результат мёртвой сессии не
  должен рисовать полосу, контроллер должен пережить шторм.»

### suggestions/SuggestionsControllerTest.kt (1589 стр. — крупнейший)

- `:466–467` — [AGENT][JARGON] — «Phase A … (docs/TT-TYPO-NEXT.md on 2026-09-20).»
- `:1292` — [AGENT] — «--- E5d NEXT_WORD ("Контракт текста" amendment, 2026-08-17)»
- `:1467` — [AGENT] — «--- "Состояния полосы" amendment, 2026-08-17, пятый пункт»
  **Замена (секции-разделители):** оставить название группы («NEXT_WORD»,
  «Состояния полосы»), убрать «amendment, 2026-08-17, пятый пункт».

### suggestions/SuggestionsControllerBigramAttachTest.kt / *BigramLanguageSwitchTest / AutocorrectControllerTest / PersonalLearningRunTest / PersonalBigramRunTest

- `BigramAttach:367` — [AGENT] — «--- Audit 2026-09-02, B4: the re-request guard…».
  **Замена:** «Гвард пере-запроса: «положил ли АКТИВНЫЙ язык слово…».»
- `BigramLanguageSwitch:275` — [JARGON] — «SIZE-2…». Убрать код, оставить смысл.
- `AutocorrectController:390` — [JARGON] — «finding 7». Убрать.
- `PersonalLearningRun:240` — [JARGON] — «finding 2». Убрать.
- `PersonalBigramRun` (док-ссылка) — [JARGON] — ссылка на `docs/*.md`. Убрать.

### suggestions/CursorMoveBandSourceContractTest.kt

- `:24–34` (KDoc) — [AGENT][SLOP] — «This is the test that fails against the code
  as it stood before mission tt-prefix3-bug: there was no re-derivation…».
  **Замена:** «Проверяет форму кода: каждый путь, гасящий полосу ради движения
  курсора, обязан запросить её обратно, когда движение улеглось (кроме эмодзи-панели
  и эмодзи-поиска).»

### glide/GlideIndexResidencySourceContractTest.kt

- `:24–35` (KDoc) — [AGENT][JARGON] — «C3 of `docs/ROADMAP-P8-PLAN.md`… The 3.0.0
  audit recorded an accepted worst case of ~5.8 MB… (`releaseGlideIndexes`, O2-3).»
  **Замена:** «В памяти держится не более одного глайд-индекса: запрос глайда
  сначала сбрасывает индексы прочих языков, затем шлёт запрос (порядок важен).»

### dictionary/personalstore/PersonalDictionaryNoLiveWriteSourceContractTest.kt

- `:26–40` (KDoc) — [OVERLONG][JARGON] — «E4a-2 stated this as… E4b connects it…
  What must NOT arrive before E4c is learning…» — три кода миссий в одном абзаце.
  **Замена:** «Путь набора никогда не пишет личное слово: `SuggestionsController`
  вообще не знает о сторе; `LatinIME` только читает и слушает стирание;
  единственный мутатор вне пакета — экран настроек.»

### glide/GlideTouchIntegrationContractTest.kt

- `:39` — [JARGON] — «finding 1». Убрать код. Прочее — пины идентификаторов, ок.

---

## Системные паттерны

Все — массовые, чинятся grep-волнами. Общее правило: **комментарий должен
объяснять, ЧТО и ЗАЧЕМ делает код, а не историю правок, миссию, дату и аудит.**

1. **Датированные сноски `2026-xx-xx` (108 шт., ~50 файлов).**
   `grep -rn "2026-[01][0-9]-[0-3][0-9]"`. Правило: удалить дату целиком; если
   строка была только про «когда пере-пинили» — удалить строку.

2. **Ссылки `docs/*.md` как обоснование (108 ссылок, ~30 доков — под архивацию).**
   `grep -rnoE "docs/[A-Za-z0-9-]+\.md|PROPOSALS\.md|[A-Z-]+\.md"`. Топ: TT-SUGGESTIONS.md
   (15), PROPOSALS.md (13), ROADMAP-P2.md (11), OPTIMIZE-SECURITY-PLAN-2026-09-29.md
   (11), TT-TYPO-NEXT.md (10). Правило: заменить ссылку кратким пояснением сути
   прямо в комментарии (иначе ссылка станет битой).

3. **Коды миссий/фаз/гейтов без расшифровки.** Маркеры: `\b(E3[ab]|E4[a-d]|E5[cd]|D1[bd]|D3|P7-[0-9]|C3|S[258]|O[27]|T[57]|Phase [ABC]|SIZE-[0-9]|ROADMAP-P[0-9]|EXPAND-[0-9]|finding [0-9]|G[1-4](-C[0-9]?)?)\b`.
   Правило: убрать код, оставить одну фразу назначения; если код нужен для навигации
   — не восстанавливать по доку, доки уходят.

4. **Агентные слова.** `grep -rni "mission\|operator\|audit\|orchestrator\|handoff"`
   — **важно:** `ResultHandoff`/`TimedHandoff`/`handoffCount` — это РЕАЛЬНЫЕ имена
   API движка (не артефакты, не трогать). Артефакты — «mission X», «the operator's
   scenario/report/decision», «NN audit, finding N», «orchestrator amendment».
   Правило: описывать поведение без ссылки на того, кто его заказал.

5. **Блоки «re-pin history» в eval/калибровочных тестах.** Шаблон «Re-pinned
   2026-09-29 (three cells again — the four-cell wave reverted): 547 -> 471».
   Наибольшая концентрация: `TtSuggestEvalTest`, `TtTypoPhaseB/CCalibrationTest`,
   `TtTypoPhaseBPrecisionTest`, `TatBigrValidatorTest`. Правило: заменить на одну
   строку «Пины — детерминированная функция закоммиченных байт; пере-пинить
   осознанно при изменении ассета/eval/ранжирования». Стрелки переходов чисел и
   числа несуществующих веток (four-cell excursion) — удалить.

6. **[DUP] преамбула fuzz-тестов** (5 файлов + харнесс) — идентичный абзац.
   Свернуть до одной строки в каждом, вынести общее описание в KDoc
   `SeededFuzzHarness` (там оно уже есть — в файлах достаточно ссылки «см.
   [SeededFuzzHarness]»).

7. **[LANG] русско-английские вкрапления.** `RealBigramPrefixIndexTest`,
   `TdictPrefixIndexEditClassRankingTest`, `CompositePrefixComputerTest`,
   `PersonalDictionaryTextContractTest`, `TatBigrValidatorTest` — цитаты из
   русскоязычного PROPOSALS.md внутри английских KDoc. Не ошибка сама по себе, но
   при чистке ссылок на PROPOSALS.md эти кавычки-цитаты теряют смысл — заменить на
   английское описание правила.

8. **[STALE] — ложных утверждений НЕ найдено.** CELL_COUNT=3 пинится и
   согласован; нет ссылок на удалённый функционал (live-превью глайда, класс
   опечаток #5, версия 3.6.0/43); ноль `@Ignore`. Единственный «stale» — это
   объём истории про «four-cell wave», описывающей состояние, которого больше нет
   (см. п.5), но утверждения кода при этом верны.

### Метрика комментариев (для [OVERLONG])

Область в целом: **16%** строк — комментарии (5 658 / 36 479). По крупнейшим/самым
нарративным файлам: `TtSuggestEvalTest` 29% (126/439), `TtTypoPhaseCCalibration`
20% (119/600), `TtTypoPhaseBCalibration` 22% (101/459), `CursorMoveBandSourceContract`
26% (50/189), `PersonalQuarantineNoticeSourceContract` 19% (52/278),
`E3bRecoveryCalibration` 18% (65/371), `AutocorrectWideningCalibration` 15%
(83/561), `SuggestionStripSourceContract` 15% (90/582), `GlideRecoveryCalibration`
10% (75/729). Основной жир — не «сколько», а «про что»: история/миссии/даты, а не
поведение.
