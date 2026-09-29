# latin/suggestions + latin/dictionary/engine — ревизия текстов

Область: `app/src/main/java/rkr/simplekeyboard/inputmethod/latin/suggestions/` (18 файлов, ~6 100 строк) и `latin/dictionary/engine/` (12 файлов, ~4 330 строк). Прочитаны все 30 файлов целиком (крупнейшие — `SuggestionsController.kt` и `TdictPrefixIndex.kt` — по частям). Лицензионные шапки Apache и апстрим-код не оцениваются; всё ниже — текст, добавленный в этом проекте (шапки датированы 2026, стиль и коды миссий это подтверждают).

## Итог

Общий диагноз: код в целом рабочий и логика комментариев верна, но **тексты систематически перегружены агентными артефактами** — кодами миссий (E5c, P7-6, O5, D3, B4, T2, TT-TYPO-NEXT Phase C2 и т.п.), ссылками на `docs/*.md` как на обоснование, датированными сносками (2026-09-xx) и «расследовательской» риторикой. Это не единичные места, а сквозной стиль: ~150 ссылок на `docs/*.md`/`PROPOSALS.md`, ~60 датированных сносок, десятки «fail-closed»/«by construction»/«the six events» в моей области.

Доля строк-комментариев (грубо, по строкам, начинающимся с `//`,`*`,`/*`): **suggestions/ ≈ 45 % (2 727 / 6 107), engine/ ≈ 30 % (1 296 / 4 331), вместе ≈ 38 %**. Рекордсмены-простыни: `SuggestionSurfaces.kt` 71 %, `AutocorrectAdvice.kt` 79 %, `LanguageSlot.kt` 68 %, `FuzzyEditPolicy.kt` 61 %, `TatarWordUtils.kt` 58 %, `SuggestionsController.kt` 45 % (1 177 строк комментариев в одном файле).

Находки по категориям (оценка): [OVERLONG] ~120 мест (самая массовая), [JARGON] ~90 (коды миссий + `docs/*.md`), [AGENT] ~60 (даты, «operator», «owner», device-witness), [SLOP] ~40 (пафос/повтор), [STALE] 3–4 (одна явная ложь), [LANG] ~10 (русские вставки в англ. комментариях), [DUP] ~8 групп, [DEAD] 1 крупный блок (неотгруженные классы опечаток #2/#3). [WEIRD] и закомментированный код — не найдены; TODO/FIXME — нет.

Топ-3 действия:
1. **Убрать `docs/*.md`-ссылки и коды миссий из комментариев** (доки идут в архив → ссылки станут битыми). Оставлять одну строку «что и зачем делает код». Массовый grep-маркер: `docs/[A-Z].*\.md`, `\b(E5[a-d]|P[0-9]|O[0-9]|D[0-9]|B[0-9]|T[0-9]|W[0-9]) \b`.
2. **Стереть датированные сноски и агентные слова** (`2026-\d\d-\d\d`, `audit`, `finding N`, `the owner`, `operator`, `POCO C71 witness`, `build/…`). История правок — в git, не в комментарии.
3. **Исправить ложь**: `GlideKeyGeometryBuilder.kt:30` «nothing calls this yet» — вызывается из `LatinIME.java:1606`; удалить строку.

Важно про тесты: source-contract-тесты моей области (`SuggestionStripSourceContractTest`, `AutocorrectSourceContractTest`, `CursorMoveBandSourceContractTest`, `E3bEngineSourceContractTest`) пинят **код** (имена методов, вызовы, порядок), а **не текст комментариев**. Чистку комментариев они не ломают (проверено чтением тестов). Единственная тонкость — `E3bEngineSourceContractTest` вырезает комментарии и запрещает кириллицу в *коде*; комментарии на кириллице разрешены, так что переписывание/перевод безопасны.

## Файлы/код на удаление

В области нет файлов-кандидатов на полное удаление — все 30 файлов используются в проде (проверено grep'ом импортов/вызовов). Единственный кандидат — блок мёртвой-в-проде логики:

| Символ | Размер | Почему | Кто ссылается (grep) | Риск |
|---|---|---|---|---|
| `FuzzyPrefixVariants.generateGeometricVariants` + `generateTranspositionVariants` (класс опечаток #2/#3) | ~45 строк | Ни одна отгружаемая политика их не включает: `FuzzyEditPolicy.DEFAULT`=[#1], `TATAR`=[#1,#4]. Комментарии сами признают «stay unreachable through a shipped lookup()». | Вызовы только в `TdictPrefixIndex.collectFuzzy` под `EDIT_CLASS_GEOMETRIC/TRANSPOSITION in editClasses` (мёртвые ветки) + тесты `TdictPrefixIndex*FuzzyClasses*`, `FuzzyPrefixVariants`-тесты | Средний — есть прямые тесты; удаление потянет удаление тестов и геометрию из `KeyNeighborTable` |
| `KeyNeighborTable.computeGeometricPairs`, `geometricNeighborsOf`, поля `geometricKeys/geometricValues` | ~50 строк | Питают только класс #2 (см. выше). В проде не читаются. | `TdictPrefixIndex` (мёртвая ветка class #2), `KeyNeighborTable`-тесты | Средний — тесты пинят геометрию (E3b) |

Рекомендация: не удалять сходу — сначала решить продуктово, нужен ли класс #2/#3 вообще. Если нет — удалить генераторы, геометрию таблицы соседей и их тесты одной волной. Это вне «чистки текстов»; помечено как найденный [DEAD], не как обязательная правка.

## Правки

Ниже — по файлам. Массовые однотипные случаи свёрнуты в «паттерн + список»; уникальные процитированы. Приоритеты: **P1** — ложь/агентные артефакты/битые ссылки; **P2** — сильное упрощение простыней; **P3** — косметика.

### suggestions/SuggestionsController.kt (2604 стр., 45 % комментов — главный источник)

Файл содержит огромные корректные KDoc'и, но каждый нагружен кодами миссий, `docs/*`-ссылками и датами. Показательные точки (паттерн — везде):

- **:143 и :284** — [JARGON][AGENT] — `// O5 (docs/OPTIMIZE-SECURITY-PLAN-2026-09-29.md): the Perfetto seam …` / `… the cookie of the one outstanding lookup`. Проблема: код миссии + ссылка на док. Замена: `// Perfetto-трейс одного цикла запроса подсказки (async-слайс).` и `// Кука единственного незавершённого запроса.` (P1)
- **:316–327** — [OVERLONG][AGENT] — `// The UX amendment (2026-09-24, the "tapping or lifting commits" line of docs/GLIDE-PLAN.md's DONE-WHEN): …` многоабзацный блок про историю глайда. Проблема: пересказ истории плана вместо поведения. Замена: 2 строки — `// Лифт-коммит глайда: подъём пальца коммитит top-1; остальные кандидаты // показываются как альтернативы, один backspace удаляет коммит целиком.` (P2)
- **:343** — [AGENT] — `// Audit 2026-09-02, B4: whether the band the ACTIVE language painted …`. Замена: убрать «Audit 2026-09-02, B4:», оставить суть про `bandHasActiveLanguageWord`. (P1)
- **:538, :609, :713, :2421** — [DUP][SLOP] — повтор мантры «one of the six events that make an undo impossible / close the undo window» в 4 местах. Проблема: одна мысль 4 раза. Замена: в одном месте (`clearRevertState`) оставить «Граница, закрывающая окно отмены»; в остальных — просто `clearRevertState()` без комментария. (P3)
- **:59** (в `StripSurface.setEmphasizedCell` KDoc) — [AGENT] — `… before the spoken labels (2026-09-25 audit: the emphasis carries the publication's display rebuild …)`. Убрать «(2026-09-25 audit: …)», оставить «эмфазис ставится до устных меток». (P2)
- Конструктор **:107–150** — [OVERLONG][JARGON] — комментарии к trailing-default параметрам («E5c: trailing default so every existing internal test constructor …», «P4/P3b sentence-start tables (docs/…)…»). Проблема: 5 абзацев про историю тестовых конструкторов. Замена на 1 строку у группы: `// Хвостовые значения по умолчанию: старые тестовые конструкторы компилируются без bigram/emoji/sentstart/trace.` (P2)

Правило для всего файла: заменить `E5c/E5d/E4c/D3/P1/P2/P3a/P3b/P4/P7-3/P7-6/P7-7/O2/O5/B2/B4/C3` + `docs/*.md` + `(2026-…)` на нейтральное описание. Это ~57 «audit/finding/phase/gate»-упоминаний и ~38 `docs/*`-ссылок в одном файле.

### suggestions/SuggestionStripState.kt

- **:164–170** — [AGENT][OVERLONG][JARGON] — `/** Three cells. Briefly four (T7 of docs/ROADMAP-P4.md reopened 2026-09-27, repacking the Tatar bigram table at K = 4 …); reverted 2026-09-29 by an operator UX decision (docs/GLIDE-LIVE-STRIP4.md footnote). …` Проблема: история «было 4, откатили 29-го», код миссии T7, «operator», две docs-ссылки, K=4. Замена: `/** Три ячейки полосы подсказок. */` перед `const val CELL_COUNT = 3`. (P1) — ⚠ значение `3` пинится тестами (`SuggestionStripSourceContractTest`, `CompositePrefixComputerTest`), но пинится **само число, не комментарий** — текст править безопасно.
- **:81** — [AGENT] — `// 2026-09-25 audit, F17: a NaN coordinate passes every comparison below …`. Замена: убрать «2026-09-25 audit, F17:», оставить `// NaN-координата проходит все сравнения и клеила бы последнюю ячейку — не-конечный ввод не ячейка.` (P2)
- **:175–178** — [JARGON][AGENT] — `/** W5 of docs/APPLE-UX-2026-09-25.md: 44dp — the iOS tap-target height … Was 40dp; the change raises the IME by 4dp and is mirrored by … scripts/emulator-smoke.sh. */`. Замена: `/** Высота полосы 44dp (высота тап-таргета). */` (P2)

### suggestions/SuggestionSurfaces.kt (71 % комментов)

- **:72** — [AGENT][JARGON] — `* E5d NEXT_WORD context extraction (PROPOSALS.md, "Контракт текста" amendment, 2026-08-17): the word immediately before a trailing run of one-or-more U+0020 …`. Замена: `* Контекст NEXT_WORD: слово перед хвостовым прогоном пробелов, или "".` (P2)
- **:118–130** — [OVERLONG][JARGON] — KDoc `commitGlideWord`/`replaceGlideLiftedWord` с `P7-6, docs/ROADMAP-P7.md — the 2026-09-24 field report`, `P7-7 (the 2026-09-25 contract change)`. Проблема: даты и коды в описании контракта метода. Замена: оставить, что метод делает (коммит глайд-слова с правилом цепного пробела), убрать `P7-x/даты/docs`. (P2)
- Общий: 7 `docs/*`-ссылок и 3 даты в файле, весь — «pure declarations, extracted verbatim from SuggestionsController.kt (ROADMAP Phase 6, T2)». Строку про «ROADMAP Phase 6, T2» удалить как агентную (P3).

### suggestions/EngineHandle.kt

- **:126** — [JARGON][AGENT] — `* O2 (docs/OPTIMIZE-2026-09-25.md): the idle memory release of the glide word index (~6 MB of pure derivation …)`. Замена: `* Освобождает индекс слов глайда в простое; следующий декод его пересоберёт.` (P2)
- **:198** — [AGENT] — `… failed gate G1 (2026-09-20) and was never wired. …` в KDoc `start` (см. также FuzzyEditPolicy). Проблема: история калибровки в контракте API. Замена: убрать историю гейтов, оставить «Tatar-движок использует FuzzyEditPolicy.TATAR; остальные — DEFAULT». (P2)
- Массово: `E5c`, `P7-3`, `P1 of Phase 2`, `D3`, `TT-TYPO-NEXT`, `TT-NEXTWORD-FILL`, `docs/*` в каждом методе интерфейса. Правило: заменить на назначение метода. (P2)

### suggestions/LanguageSlot.kt

- **:34–36** — [AGENT][JARGON] — `* Pure move from SuggestionsController.kt (ROADMAP Phase 6, T2): the class referenced no outer state, so the inner modifier is simply gone; internal replaces file-private for the same reason.` Проблема: комментарий про механику рефакторинга, не про код. Замена: удалить абзац целиком. (P1)
- **:45** — [JARGON] — `/** E5c two-stage readiness: same lazy-seam shape as [preparation], for the bigram table. */` → `/** Ленивый seam хранилища таблицы биграмм. */` (P3)

### suggestions/LookupTracer.kt

- **:23** — [JARGON][AGENT] — `* O5 (docs/OPTIMIZE-SECURITY-PLAN-2026-09-29.md): the Perfetto seam … the worker hop and the re-marshal in between being exactly what the existing p95 numbers could not explain before.` Проблема: код миссии + «p95 numbers could not explain». Замена: `* Perfetto async-слайс цикла подсказки: от «запрос выдан на UI» до «ответ применён на UI».` (P2)

### suggestions/RevertWindow.kt

- **:80–81** — [LANG] — англ. комментарий с русской вставкой `… which is what makes «любое другое событие делает revert невозможным» true …`. Проблема: смешение языков без причины. Замена: целиком по-русски или целиком по-английски — `// Любое другое изменение текста закрывает окно отмены.` (P3)
- **:28** — [AGENT] — `Pure move from SuggestionsController.kt (ROADMAP Phase 6, T2), state and transitions verbatim; …` — удалить упоминание рефакторинга/T2. (P3)

### suggestions/AutocorrectPreview.kt

- **:22, :45, :56** — [JARGON][AGENT] — `The autocorrect preview of P2 (Phase 3, docs/ROADMAP-P3.md) … Pure move from SuggestionsController.kt (ROADMAP Phase 6, T2)…`. Замена: оставить «Форма грядущей автозамены и решение, сработает ли политика на данном слове», убрать P2/Phase 3/T2/docs. (P2)

### suggestions/SuggestionsOfferController.kt (58 % комментов)

- **:29, :106, :123** — [JARGON] — `[E1b-8]` / `(E1b-8)` — внутренний код без расшифровки, причём `[E1b-8]` в KDoc выглядит как ссылка на несуществующий символ. Замена: убрать `[E1b-8]`/`(E1b-8)`/`(E1b)`, говорить «предложение» и «сообщение о недоступности». (P2)
- **:99–113** — [OVERLONG] — KDoc класса пересказывает две one-shot-фичи абзацами с «The chosen failure direction is explicit: showing it zero times is acceptable, showing it twice is not». Проблема: риторика. Замена: 2 строки о назначении. (P3)

### suggestions/TatarWordUtils.kt (58 % комментов)

Технически ценный файл (юникод/морфология), но с кодами миссий и датами:
- **:84–108** — [AGENT][JARGON] — `E5d NEXT_WORD context extraction (PROPOSALS.md, "Контракт текста" amendment, 2026-08-17, пункт 1) … ROADMAP Phase 1 (P4, docs/ROADMAP-P1.md) amends …`. Замена: описать правило (слово перед прогоном пробелов; для `, ; :` — слово перед пунктуацией), убрать `E5d/PROPOSALS/пункт 1/P4/docs/даты`. (P2)
- **:429** — [AGENT] — `… (see the "Контракт текста" amendment of 2026-07-27); at a LOWER prefix a personal record …`. Убрать дату и «amendment». (P3)
- **:334, :401** (`isAutocorrectSeparator`, комментарии `«пробел или пунктуация»`) — [LANG] — русские цитаты «контракта» в англ. комментарии; допустимо, но лучше единый язык. (P3)

### suggestions/TatarSuffixRules.kt

- **:22–42** — [JARGON] — заголовок `P3 runtime Tatar suffix machinery (docs/TT-SUGGESTIONS.md) … Consistency with the build-time generator (scripts/wordform_gen.py, P1)`. Морфологическое содержание ценно — оставить; убрать только `P3/P1/docs`-обвязку и вводную мету. (P3)
- **:25** — [AGENT] — `… (O7 follow-up, 2026-09-29) — so the reads run at array speed …` — убрать `(O7 follow-up, 2026-09-29)`. (P2)
- **:53** (дубликат фразы про «array speed while the mapping stays the source of truth», см. `TdictPrefixIndex.kt:25`) — [DUP] — та же формулировка в двух файлах; свернуть до «читается из копии блока в куче». (P3)
- Таблица суффиксов **:78–210** — комментарии-группы («plural -LAr», «genitive -нIң» …) — это [хорошо], не трогать: реально помогают.

### suggestions/SuggestionStripView.kt

- **:603–609** — [LANG][JARGON][DUP] — блок `// Р-3: размеры текста … в dp, а НЕ в sp … При font_scale 2.0 полоса подсказок вырождалась в «Мини… · Минем · Мини…» … (docs/DEVICE-RESEARCH-GEOMETRY.md, Р-3).` Проблема: единственный крупный русский блок в англоязычном файле, код миссии `Р-3` (кириллическая Р), docs-ссылка, дублируется дословно в `EmojiPanelView.kt:93` и `EmojiSearchView.kt:93`. Замена (одной строкой, по-английски как остальной файл): `// Strip text is sized in dp, not sp: the band has a fixed dp height and sp would overflow it at large font scale.` (P2)
- **:82, :101, :273, :324, :612** — [JARGON][AGENT] — `W4 (docs/APPLE-UX-2026-09-25.md)`, `O4 (docs/OPTIMIZE-SECURITY-PLAN-2026-09-29.md)`, `(2026-09-25 audit: …)`. Замена: убрать коды/ссылки/даты, оставить «нажатая ячейка — inset-скруглённый прямоугольник» / «ширина подчёркивания измеряется при публикации, не в кадре». (P2)

### engine/AutocorrectAdvice.kt (79 % комментов)

- **:50** — [AGENT] — `* Both were fixed by the owner on 2026-07-31, BEFORE a single line of phase code and BEFORE any quality run, for the same reason the E5a threshold was: a gate decided after the result is not a gate.` Проблема: чистый агентный нарратив про «owner»/«phase code». Замена: удалить абзац; оставить «Пороги фиксированы заранее и не пересматриваются под результат». (P1)
- **:64–78** — [OVERLONG][AGENT] — KDoc `MIN_CANDIDATE_FREQUENCY` пересказывает историю перемеров: `Re-measured 2026-09-20 (TT-SUGGESTIONS P2): rank 10 000 is frequency 411 (чиновниклар), rank 20 000 is 153 (хәмзин) … the quoted 403/149 were the 1.8.4 measurements …`. Проблема: журнал измерений в комментарии к константе. Замена: `/** Частота слова ранга 10 000 в шипнутом артефакте (перемеряется при пересборке асета). */` + значение. (P2)

### engine/FuzzyEditPolicy.kt (61 % комментов)

- **:19–45** — [OVERLONG][AGENT][JARGON] — заголовок = журнал калибровки: `Calibration history (docs/TT-TYPO-NEXT.md): the Phase-B candidate (…) failed gate G1 (2026-09-20) … failed its first measurement round the same day (G3-C: the naive probe path cost 31.6 ms p95 …) … PASSED the corrected gates G1-C2 (+27.2 pp …), G2-C2 (21.1 % activation …) and G3-C2.` Проблема: гейты/pp/ms/даты в KDoc типа. Замена: `* Пер-движковая конфигурация fuzzy-прохода: какие классы правок запускать и нужен ли same-length бонус. Tatar: класс #1 + класс #4 (probe-first полная замена, ≥4 к.т.). Остальные: только класс #1.` (P2)
- **:27, :75** — [AGENT] — `failed gate G1 (2026-09-20)`, `(since Phase C2, 2026-09-20)` — убрать даты/гейты. (P2)

### engine/TdictPrefixIndex.kt (1846 стр., 27 % — но много OVERLONG)

- **:190–200** — [AGENT][OVERLONG] — `// O7 follow-up (2026-09-29, docs/OPTIMIZE-SECURITY-PLAN-2026-09-29.md): raw-block fetch scratches. The POCO C71 witness (build/device-uat-2026-09-29/mmap-witness) showed per-byte absolute MappedByteBuffer.get is the mmap arm's deficit …`. Проблема: имя устройства, путь к артефакту прогона, дата, «arm's deficit». Замена: `// Блок целиком копируется в кучевой буфер одним bulk get: по-байтные mapped-чтения были узким местом. // Два буфера (range-scan и probe), т.к. декод внутри колбэков scan.` (P1)
- **:25** — [DUP][AGENT] — `… a verbatim bulk-fetched copy of the mapped block (O7 follow-up, 2026-09-29) — so the test reads at array speed while the mapping stays the source of truth.` — та же фраза, что в `TatarSuffixRules.kt:53`; убрать `(O7 follow-up, 2026-09-29)`. (P2)
- **:514–525** — [AGENT] — `The length gate (P3 refinement, 2026-09-20): a short complete word (су, ал, өй) …`. Убрать `(P3 refinement, 2026-09-20)`; суть про длину оставить. (P2)
- **:606–620** (KDoc `collectFuzzy`) — [OVERLONG][JARGON] — абзацы про `Phase-B G1`, `PROPOSALS.md section "Контракт текста", line "Итог, 2026-07-27", docs/archive/missions/DICTIONARY-E3.md`. Проблема: ссылка в архив (`docs/archive/…`) как обоснование. Замена: описать порядок классов и что #2/#3 не отгружены. (P2)
- Константы **companion (~1600–1720)** — коды миссий в каждом `const val`-комментарии (`Phase C`, `TT-TYPO-NEXT`, `E3b offline reference (p95 33 variants, max 39)`). [AGENT] числа прогонов (`p95 133 entries, max 522`) — убрать конкретику прогонов, оставить назначение бюджета. (P3)

### engine/CompositePrefixComputer.kt

- **:239** — [JARGON][AGENT] — `* O2 (docs/OPTIMIZE-2026-09-25.md): the idle-release seam of the glide side …`. Замена: `* Освобождение индекса глайда в простое (форвардится в host).` (P2)
- **:58–79** (KDoc класса) — [JARGON][LANG] — `The single ranking of E4b … in the order frozen by «Контракт текста»`, `«словарное гүзәл» + «личное Гүзәл»`. Ценно по сути; убрать `E4b/E4a-1` и оставить русские примеры (они уместны — язык татарский). Убрать коды миссий. (P3)
- Поля-seam'ы **:88–103** — `P3:`, `TT-NEXTWORD-FILL (docs/…)`, `P1 of Phase 2 (docs/ROADMAP-P2.md)` — заменить на назначение seam'а. (P2)

### engine/TatBigrPrefixIndex.kt

- **:183–185** — [AGENT] — `// Three strip cells again (2026-09-29 revert, SuggestionStripState.CELL_COUNT): the read caps at three even though the shipped Tatar table is packed at K = 4 …`. Замена: `// Читаем не более трёх преемников (K=4 в таблице — headroom).` (P2)
- **:19–41** — [JARGON] — `E5c read side … (docs/DICTIONARY-E5B.md; schema 3 since SIZE-2, docs/SIZE-SCHEMA3.md)`, `PROPOSALS.md ("E5c. Вид запроса")`. Убрать коды/доки, оставить «читатель schema-3 TATBIGR». (P3)

### engine/MappedDictionaryEngine.kt

- **:66** — [JARGON][AGENT] — `* O2 (docs/OPTIMIZE-2026-09-25.md): the idle memory release of the glide word index …`. Замена: назначение. (P2)
- **:290–312** (KDoc `start`) — [OVERLONG][JARGON] — перечисление всех wiring-параметров с `P3/TT-TYPO-NEXT/TT-NEXTWORD-FILL/P1` и «byte-identical to the frozen D1 behavior» ×5. Свернуть до: «Tatar-движок получает suffix/fuzzy/fallback/personal-bigram wiring; остальные — null/EMPTY (поведение как раньше)». (P2)
- Прочее: `E5c two-stage readiness (PROPOSALS.md …)` на :77, :? — убрать `PROPOSALS.md`-цитаты. (P3)

### engine/LatestOnlyPrefixEngine.kt

- **:62–66** — [JARGON] — `Implementations must dispatch to the serialized state owner (the UI thread in D1e).` — `D1e` без расшифровки. Замена: убрать «(the UI thread in D1e)» → «(UI-поток)». (P3)
- **:? (enum LookupKind), :? (requestNextWord/requestGlide)** — [JARGON] — `PROPOSALS.md, "E5c. Вид запроса"`, `P7-3 (docs/GLIDE-PLAN.md)`. Заменить на назначение. (P2)
- **:251** — [JARGON][AGENT] — `* O2 (docs/OPTIMIZE-2026-09-25.md): the idle memory release of the glide word index.` — назначение. (P2)

### engine/GlideDecoderHost.kt

- **:69** — [JARGON][AGENT] — `* O2 (docs/OPTIMIZE-2026-09-25.md): drops the lazily built decoder …`. Замена: `* Сбрасывает лениво построенный декодер (индекс ~6 МБ); следующий декод пересоберёт.` (P2)
- **:29–47** (KDoc) — [JARGON] — `P7-3`, `docs/GLIDE-PLAN.md`, `docs/GLIDE-PERSONAL.md` — убрать, оставить описание владения геометрией/снапшотом. (P3)

### engine/KeyNeighborTable.kt

- **:23** — [LANG] — `Immutable adjacency source ("источник соседства") derived from the live keyboard layout.` Проблема: русская глосса в англ. KDoc без нужды. Замена: убрать `("источник соседства")`. (P3)
- **:41** — [AGENT] — `Geometry-bearing letter keys actually read from the layout (37 on the Tatar layout).` — «37» — вшитый факт-в-комментарии; оставить назначение поля, убрать «(37 …)». (P3)
- **:47–57, :150–190** — [JARGON] — `E3a/E3b fills in the edit class #1/#2 source`. Если класс #2 останется — оставить, убрав `E3a/E3b`. (P3)

### engine/FallbackWords.kt / TdictGlideInventory.kt / FuzzyPrefixVariants.kt

- `FallbackWords.kt` — [JARGON] — весь KDoc через `TT-NEXTWORD-FILL (docs/TT-NEXTWORD-FILL.md)`; заменить на «глобальный топ-частотный fallback последней очереди NEXT_WORD». (P3)
- `TdictGlideInventory.kt:19` — [JARGON] — `the decode-side dictionary integration of P7-1 (docs/GLIDE-PLAN.md)` → убрать `P7-1/docs`. (P3)
- `FuzzyPrefixVariants.kt` — [JARGON] — `TT-TYPO-NEXT Phase C2`, `class #4` описания. Классы #1/#3/#4 корректны; убрать только коды фаз/даты. Если #2/#3 удаляются (см. раздел удаления) — убрать `generateGeometricVariants`/`generateTranspositionVariants`. (P3)

### suggestions/GlideKeyGeometryBuilder.kt

- **:30** — [STALE] — `* P7-1 note: nothing calls this yet — P7-2/P7-3 wire it to the live keyboard.` Проблема: **ложь** — метод вызывается из `LatinIME.java:1606` (`GlideKeyGeometryBuilder.fromKeyboard`). Замена: удалить строку целиком. (P1)

### suggestions/SentStartIndex.kt / SentStartSources.kt / SuggestionPreparation.kt / CleanRunMachine.kt / KeyNeighborTableBuilder.kt

- `SentStartIndex.kt:22` — [JARGON] — `The sentence-start table (TT-SUGGESTIONS phase P4, docs/TT-SUGGESTIONS.md) …` → убрать «phase P4, docs/…», оставить «таблица частотных начал предложения; данные Leipzig, CC BY 4.0». (P3)
- `SentStartSources.kt:24` — [JARGON] — `the exact shape of the controller's EmojiSuggestPreparation seam` — норм, но `assetForSubtype`-ссылки на реестр оставить. (P3)
- `SuggestionPreparation.kt:38, :96` — [JARGON][AGENT] — `Pure move from SuggestionsController.kt (ROADMAP Phase 6, T2)…`, `E5c two-stage readiness … (docs/DICTIONARY-E5B.md)`. Убрать T2/рефакторинг-мету и docs-ссылку. (P3)
- `CleanRunMachine.kt:89, :196, :221` — [AGENT] — `2026-09-25 audit, privacy:`, `(2026-09-24 audit, finding 2)`, `(2026-09-25 audit, the paste rule)`. Убрать «audit/finding/дата», оставить правило (одно наблюдение ≤ 1 нажатия; вставка — грязный ран). (P2) — примечание: фразы «finding 2»/«the paste rule» встречаются и в *тестовых* комментариях (`PersonalLearningRunTest`, `PersonalBigramRunTest`), но это комментарии тестов, а не ассерты — правка исходника их не ломает.
- `KeyNeighborTableBuilder.kt` — чистый, только `KeyNeighborTable`-описание; трогать не нужно. (—)

## Системные паттерны

Повторяющиеся проблемы (в порядке массовости) и grep-маркеры для массовой чистки:

1. **Коды миссий в комментариях** [JARGON]. Маркер: `\b(E[0-9][a-z]?|P[0-9](-[0-9])?|O[0-9]|D[0-9][a-z]?|B[0-9]|C[0-9]|T[0-9]|W[0-9]|G[0-9])\b`, плюс словесные `Phase [ABC]`, `TT-TYPO-NEXT`, `TT-NEXTWORD-FILL`, `TT-SUGGESTIONS`, `ROADMAP-P\d`, `SIZE-\d`, `schema \d`. **Правило:** код миссии из комментария убрать; если поясняет поведение — переформулировать в термины кода, если только историю — удалить.

2. **Ссылки на `docs/*.md`/`PROPOSALS.md` как обоснование** [JARGON]. Маркер: `docs/[A-Za-z0-9_-]+\.md`, `PROPOSALS\.md`, `docs/archive/`. ~150 вхождений в области (только `SuggestionsController.kt` — 38). Доки уходят в архив → ссылки станут битыми, а `docs/archive/…` уже мёртвые. **Правило:** удалить ссылку; суть, если нужна, оставить одной строкой.

3. **Датированные сноски и «audit/finding»** [AGENT]. Маркер: `202[0-9]-[0-9]{2}-[0-9]{2}`, `\baudit\b`, `finding [0-9]`, `field report`. ~60 дат в области. **Правило:** удалить дату и слово «audit/finding»; описание поведения оставить.

4. **Агентный/девелоперский нарратив** [AGENT]. Маркеры: `\bowner\b`, `\boperator\b`, `POCO C71`, `witness`, `build/`, `Pure move from`, `ROADMAP Phase 6, T2`, `p95`, `pp)`, конкретные ms/числа прогонов. **Правило:** удалять целиком — это заметки процесса, не документация кода. Точки в моей области: `AutocorrectAdvice.kt:50`, `SuggestionStripState.kt:167`, `TdictPrefixIndex.kt:190-191`, `FuzzyEditPolicy.kt:19-45`, все `Pure move …`-абзацы (`LanguageSlot`, `RevertWindow`, `SuggestionSurfaces`, `CleanRunMachine`, `SuggestionPreparation`, `AutocorrectPreview`).

5. **Простыни-KDoc'и** [OVERLONG]. Многие KDoc'и 15–30 строк там, где хватит 2–3 (`SuggestionsController` конструктор, `onCursorMoveSettled`, `applyGlideResult`, `onTap`, `MappedDictionaryEngine.start`, `FuzzyEditPolicy`, `AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY`). **Правило:** одна строка «что делает» + при необходимости одна «почему именно так»; убрать перечисления всех гейтов/фаз/альтернатив.

6. **Повтор одной мысли** [DUP]. «one of the six events …» ×4 в `SuggestionsController`; «reads at array speed while the mapping stays the source of truth» в `TdictPrefixIndex.kt:25` и `TatarSuffixRules.kt:53`; блок «Р-3 … dp, не sp» дословно в 3 файлах (`SuggestionStripView`, `EmojiPanelView`, `EmojiSearchView`). **Правило:** оставить формулировку в одном месте, в остальных — короткая отсылка или ничего.

7. **Смешение языков** [LANG]. Русские вставки в англоязычных комментариях: `RevertWindow.kt:80` (`«любое другое событие…»`), `KeyNeighborTable.kt:23` (`«источник соседства»`), `SuggestionStripView.kt:603` (весь блок Р-3), русские «контрактные» цитаты в `TatarWordUtils`/`CompositePrefixComputer`. **Правило:** один язык на комментарий. Русские *примеры* слов (татарские/русские) уместны и остаются; русские *пояснения* в англоязычном файле — переводить или убирать.

8. **Пафос/самооправдание** [SLOP]. «fail-closed in every direction», «by construction rather than by review», «the exact shape of failure this keyboard treats as a defect even where the code is formally right», «a gate decided after the result is not a gate». **Правило:** заменить на факт («при ошибке — пусто» и т.п.), убрать оценочность.

Единственная фактическая ложь [STALE]: `GlideKeyGeometryBuilder.kt:30`. Число `CELL_COUNT=3`, версии, «класс опечаток #5» — противоречий в комментариях области **не найдено** (класс #5 нигде не упоминается; классы #1–#4 описаны верно; live-превью глайда в коде нет и в комментариях не обещано).

Безопасность правок: source-contract-тесты области пинят код/структуру, не прозу комментариев (проверено по `SuggestionStripSourceContractTest`, `AutocorrectSourceContractTest`, `CursorMoveBandSourceContractTest`, `E3bEngineSourceContractTest`). Отдельно отмечено выше единственное место с оговоркой (`SuggestionStripState.CELL_COUNT` — пинится число, не текст).
