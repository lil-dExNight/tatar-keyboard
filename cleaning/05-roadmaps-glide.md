# ROADMAP + GLIDE документы — ревизия текстов

Область: `docs/ROADMAP.md`, `docs/ROADMAP-P1.md … ROADMAP-P8.md`, `docs/ROADMAP-P8-PLAN.md`,
`docs/GLIDE-PLAN.md`, `docs/GLIDE-PERSONAL.md`, `docs/GLIDE-LIVE-STRIP4.md`.
Всего 13 файлов, ~4 266 строк, ~316 КБ. Все прочитаны целиком.

## Итог

Диагноз: это 13 документов «эпохи миссий», из которых **11 — завершённые отчёты** (ROADMAP-P1…P8,
GLIDE-PERSONAL, плюс планы GLIDE-PLAN и ROADMAP-P8-PLAN, чья работа выполнена), 1 — мастер-план
(ROADMAP.md), у которого **все 7 фаз уже сделаны** (текущий релиз 3.6.0/43), и 1 —
GLIDE-LIVE-STRIP4, который **описывает две фичи, откатанные в 3.6.0** (live-превью глайда и
4-клеточная полоса). Отчёты написаны в жанре «нейрослопа»: гейт-таблицы с дрейфующими счётчиками
тестов и SHA APK, девайсные UAT-логи, ритор­ика («the flagship mission», «the library calls were
the wall», «a partial clean result over a forced one»), «fail-closed» 15× только в P7, и сквозной
жаргон миссий (P3a, T5, U7, U8, P7-3, E4c, E5d, G1–G4, C2/C5, W1–W6, M1–M5, S1/S2, EXPAND-1).

Находки по категориям (свёрнуты в паттерны): [AGENT] ~40 мест (гейт-таблицы, «No commits — the
operator commits», UAT-логи, SHA), [JARGON] пронизывает все файлы, [SLOP] ~30, [OVERLONG] весь
жанр отчётов, [STALE] ~12 (GLIDE-LIVE-STRIP4 целиком, ROADMAP.md как «план», счётчики тестов,
размеры APK), [DUP] гейт-таблицы и «device state restored» повторяются в каждом отчёте, [LANG]
P7 наполовину английский, наполовину русский, [DELETE] 13 кандидатов.

Топ-3 действия:
1. **Перенести 11 завершённых отчётов в `docs/archive/roadmaps/`** — это прямо предписано
   конвенцией самого проекта (`docs/README.md`: «отчёты завершённых миссий — в `docs/archive/`»).
   Блокер: ~50 файлов кода/тестов ссылаются на эти пути в комментариях — перенос требует правки
   и комментариев, либо осознанного оставления путей (см. таблицу и паттерн П-1).
2. **GLIDE-LIVE-STRIP4.md сжать до 10-строчной записи «фичи откатаны в 3.6.0»** или удалить —
   основной текст описывает несуществующее поведение (STALE).
3. **ROADMAP.md переписать из «плана будущего» в короткий индекс «сделано, релиз 3.6.0»** —
   сейчас он читается как список предстоящих работ, которых нет.

## Файлы на удаление / перенос

Все пути от корня репозитория. Размеры — из `ls -la`. «Кто ссылается» — по `grep` в коде,
тестах, скриптах, CI и других доках. Риск удаления высок почти везде: исходники цитируют
эти доки как провенанс в комментариях (`P7-3 (docs/GLIDE-PLAN.md)` и т.п.).

| Путь | Размер | Почему кандидат | Кто ссылается (grep) | Риск |
|---|---|---|---|---|
| `docs/GLIDE-LIVE-STRIP4.md` | 9.8 КБ / 133 стр | Описывает live-превью глайда и 4-клеточную полосу — **обе откатаны в 3.6.0** (внутренние сноски :41, :66 это признают). Основной текст STALE. | `SuggestionStripState.kt:167`, `TtSuggestEvalTest.kt:56` (коммент), README, HANDOFF, `APK-AUDIT-3.6.0.md` | Средний — 2 коммента кода цитируют |
| `docs/GLIDE-PLAN.md` | 5.3 КБ / 102 стр | План фазы 7, работа завершена (P7-1…P7-4 + follow-ups). План при готовом отчёте (ROADMAP-P7) — дубль-провенанс. | ~15 файлов кода/тестов (`SuggestionsController.kt`, `MappedDictionaryEngine.kt`, `GlideDecoderHost.kt`, `EngineHandle.kt`, `PointerTracker.java`, `GlideGestureDecider.kt`, тесты) + README/HANDOFF | **Высокий** — активный провенанс `P7-3 (docs/GLIDE-PLAN.md)` |
| `docs/GLIDE-PERSONAL.md` | 8.0 КБ / 145 стр | Завершённый mission report (2026-09-26). Архивный по конвенции. | `MappedDictionaryEngine.kt:374`, `GlideDecoderHost.kt:41`, `MappedDictionaryEngineGlideTest.kt`, `GlideEndToEndTest.kt`, README/HANDOFF | **Высокий** — 4 коммента кода |
| `docs/ROADMAP.md` | 8.4 КБ / 164 стр | Мастер-план, **все 7 фаз сделаны** (релиз 3.6.0). Как «план» устарел целиком. | README, HANDOFF; в коде — только косвенно («ROADMAP Phase 1» в паре комментов) | Низкий-средний |
| `docs/ROADMAP-P1.md` | 28.6 КБ / 480 стр | Завершённый отчёт фазы 1. | `PersonalSubtypes.kt:35,85`, `RussianSentStartAssetTest.kt`, `TatarWordUtils.kt:96`, `SuggestionSurfaces.kt:73`, README/HANDOFF | **Высокий** |
| `docs/ROADMAP-P2.md` | 33.7 КБ / 474 стр | Завершённый отчёт фазы 2. Самый цитируемый. | ~10 файлов: `LatinIME.java`, `CleanRunMachine.kt`, `CompositePrefixComputer.kt`, `SettingsHostActivity.kt`, `Settings.java`, `EngineHandle.kt`, тесты | **Высокий** |
| `docs/ROADMAP-P3.md` | 25.4 КБ / 354 стр | Завершённый отчёт фазы 3. | `AutocorrectPreview.kt`, `SuggestionStripState.kt:25`, `SuggestionStripView.kt`, `TdictPrefixIndex.kt`, `SuggestionsController.kt:56` | **Высокий** |
| `docs/ROADMAP-P4.md` | 28.2 КБ / 399 стр | Завершённый отчёт фазы 4 (класс #5 уже удалён из кода). | `TtSuggestEvalTest.kt:299,332`, README/HANDOFF, LEFTOVERS-PLAN | Средний-высокий |
| `docs/ROADMAP-P5.md` | 13.7 КБ / 169 стр | Завершённый отчёт фазы 5. | `Settings.java:63`, README/HANDOFF | Средний |
| `docs/ROADMAP-P6.md` | 31.1 КБ / 443 стр | Завершённый отчёт фазы 6 (T2 split). | `SettingsHostActivity.kt:87,97,121`, `EmojiPanelView.kt`, `SettingsRows.kt` | **Высокий** — «T2 part 3 (docs/ROADMAP-P6.md)» в комментах |
| `docs/ROADMAP-P7.md` | 68.6 КБ / 963 стр | Завершённый отчёт фазы 7. **Самый большой файл, наполовину на русском** (P7-5…P7-8). | ~10 файлов кода/тестов: `GlideDecoder.kt`, `SuggestionSurfaces.kt`, `PointerTracker.java`, `Settings.java:400`, `GlideDeviceInstrumentationTest.kt` | **Высокий** |
| `docs/ROADMAP-P8.md` | 15.9 КБ / 194 стр | Завершённый отчёт фазы 8. | `EmojiSuggestIndex.kt:94,98`, README/HANDOFF | Средний |
| `docs/ROADMAP-P8-PLAN.md` | 14.5 КБ / 246 стр | План фазы 8, выполнен. Дубль-провенанс к ROADMAP-P8. | `EmojiSuggestIndex.kt:126`, `SuggestionStripSourceContractTest.kt:36`, `SettingsRows.kt:222` | Средний-высокий |

Вывод по удалению: **чистого «удалить» кандидата нет** — всё либо цитируется кодом, либо служит
единственной записью-провенансом. Реалистичный путь: (а) массовый перенос в `docs/archive/roadmaps/`
с пакетной правкой doc-путей в комментах кода (это область другого агента — кода), либо (б) если
код-комменты не трогаем — оставить файлы на месте, но **радикально сжать** (см. Правки).
Единственный файл, который можно ужать до заглушки без сожалений, — `GLIDE-LIVE-STRIP4.md`.

## Правки

Приоритеты: P1 — мусор/ложь/агентные артефакты/устаревшее; P2 — сильное упрощение; P3 — косметика.

### docs/GLIDE-LIVE-STRIP4.md

- `docs/GLIDE-LIVE-STRIP4.md:1` — [STALE][SLOP] — «live glide scoring, glide learning, the
  four-cell strip … One wave, three roadmap leftovers shipped together on top of release 3.2.0» —
  из трёх фич две (live-scoring, 4 клетки) откатаны в 3.6.0; заголовок обещает поведение,
  которого нет. **Замена:** заголовок → «GLIDE-LIVE-STRIP4 — откатанный эксперимент (live-превью
  + 4 клетки), только glide-обучение осталось (3.6.0)».
- `docs/GLIDE-LIVE-STRIP4.md:13-47` — [STALE] — весь раздел «1. Live per-MOVE scoring» с design,
  «The preview never commits», «Lift vs preview discrimination» — описывает удалённый код
  (в `app/src/main` нет `onGlideProgress`/`maybeEmitGlideProgress`/`applyGlideProgressResult`/
  `glideLiftInFlight` — проверено grep'ом, пусто). Сноска :41 это признаёт. **Замена:** удалить
  весь раздел, оставить одну строку: «Live-превью глайда было отгружено и откатано 2026-09-29
  (операторское UX-решение): подсказки только при отрыве пальца».
- `docs/GLIDE-LIVE-STRIP4.md:64-72` — [STALE] — раздел «3. The four-cell strip»: `CELL_COUNT 3→4`
  и вся контрактная половина откатана (сейчас `CELL_COUNT=3`, `MAX_RESULTS=3` в обоих индексах —
  проверено). Сноска :66 признаёт. **Замена:** свести к одной строке: «4-клеточная полоса
  откатана к 3 клеткам; татарская таблица биграмм осталась на K=4 (4-й преемник не читается)».
- `docs/GLIDE-LIVE-STRIP4.md:104-131` — [AGENT][DUP] — блок «Gates and evidence (2026-09-27/28)»
  и «Device legs (POCO C71…)»: счётчики «1 703 tests, 182 suites», «507 tests», «21 PASS / 0 FAIL
  / 1 SKIP», p95-числа, пути свидетельств. Дрейфующие агентные артефакты. **Замена:** удалить
  блок целиком (для истории достаточно строки о факте отгрузки/отката).

### docs/ROADMAP.md

- `docs/ROADMAP.md:1` — [STALE] — «ROADMAP — remaining prediction, UX and tech-debt work» и весь
  тон «remaining». Все 7 фаз сделаны, релиз 3.6.0. **Замена:** переписать в «ROADMAP — история
  фаз после 2.0.1 (все выполнены; актуальный релиз 3.6.0)» либо перенести в архив.
- `docs/ROADMAP.md:9-19` — [OVERLONG][AGENT] — «Working agreements (every phase, no exceptions):»
  — 4 буллета про «plan doc → implementation → all gates green → device UAT → marker commit», «the
  TT-TYPO-NEXT discipline», «commits by the operator, Russian, conventional». Это процессный
  ритуал, дублирующий AGENTS.md. **Замена:** удалить блок (правила уже в AGENTS.md).
- `docs/ROADMAP.md:36` — [SLOP] — «glide typing is the flagship last mission.» — пафос.
  **Замена:** «glide typing (фаза 7) — последняя и самая крупная.»
- `docs/ROADMAP.md:151` — [SLOP] — «The flagship mission. No open-source implementation exists in
  AOSP lineage (Google's is a closed library) — research phase first…» — самолюбование +
  раздутое. **Замена:** одна строка: «Фаза 7: свайп-набор (SHARK²-классификатор, реализация
  своя, ноль зависимостей). Готово в 3.0.0, детали — отчёт фазы 7.»
- `docs/ROADMAP.md:150-159` — [STALE] — раздел Phase 7 сформулирован в будущем времени
  («then a written plan», «Only after the plan is approved: implementation») — фаза давно сделана.
  **Замена:** пометить статус «done (3.0.0)».

### docs/ROADMAP-P1.md … P8.md (общие для всех отчётов правки)

Эти правки типовые — применимы к каждому из восьми отчётов; ниже конкретные якоря, дальше — паттерн.

- `docs/ROADMAP-P1.md:6`, `ROADMAP-P2.md:5`, `ROADMAP-P3.md:6` (и аналогично P4-P8) — [AGENT] —
  «No commits — the operator commits.» — адресация к процессу агента в теле отчёта. **Замена:**
  удалить фразу везде (факт «коммитит оператор» уже в AGENTS.md).
- `docs/ROADMAP-P1.md` (блоки «## Gates», «## Full gates», таблицы с «JVM 1 282 tests», «python
  484», «unsigned APK 1 871 473 B», SHA-256) — [AGENT][STALE][DUP] — счётчики и хэши, которые
  дрейфуют и повторяются 3-4 раза в одном файле (промежуточный + финальный прогон). Сейчас в
  проекте 1 927 JVM / 507 python — все числа в отчётах устарели. **Замена:** оставить один
  строчный итог «все гейты зелёные на дереве фазы (детали — git на момент коммита)», убрать
  дрейфующие числа и SHA.
- `docs/ROADMAP-P3.md:7-12` — [AGENT][STALE] — «Footnote 2026-09-25 … the "P7 is NOT started"
  sentence above is stale … kept as history, per the project's no-rewrite rule» — сноска, которая
  чинит соседнее предложение вместо того, чтобы его исправить; артефакт политики «не переписываем
  историю» внутри живого дока. **Замена:** в живом (не архивном) доке просто исправить строку :3
  на «P7 — измерен и отклонён гейтами (см. §P7)», сноску удалить.
- `docs/ROADMAP-P6.md:66`, `:201`, `:313` — [SLOP] — «A partial clean result was chosen over a
  forced one, per the phase's own rule.» — одна и та же риторическая фраза трижды в файле.
  **Замена:** удалить (тавтология); достаточно фактической строки про размер файла и причину.
- `docs/ROADMAP-P1.md:367` — [SLOP][AGENT] — «history is not rewritten; this section is the new
  state of record.» — процессный пафос. **Замена:** удалить.

### docs/ROADMAP-P7.md (отдельно — крупнейший и двуязычный)

- `docs/ROADMAP-P7.md:685`, `:760`, `:839`, `:903` — [LANG] — разделы P7-5, P7-6, P7-7, P7-8
  написаны **по-русски** («## P7-5 — lift-commit UX + след свайпа и подсветка клавиши»), тогда
  как P7-1…P7-4 и все остальные ROADMAP-отчёты — «canonical English». Смешение языков в одном
  файле без причины. **Замена:** привести к одному языку (по конвенции доков — английский; либо,
  если решают перейти на русский во всех доках, — единообразно).
- `docs/ROADMAP-P7.md:509` — [SLOP] — «the 8.42 threshold is earned, not loose» — антропоморфный
  пафос про число. **Замена:** «порог 8.42 подтверждён: истинные слова достигают |Δlen|=14 радиусов
  на p99».
- `docs/ROADMAP-P7.md:510` — [SLOP] — «**The library calls were the wall.**» — драматизация.
  **Замена:** «Узкое место — вызовы `Math.abs`/`Math.sqrt` (микробенч: ~1847/594 нс на вызов).»
- `docs/ROADMAP-P7.md:454-575` — [OVERLONG][AGENT] — раздел «The failure and the iteration» +
  «No-ops measured and rejected» + «Final numbers»: подробный дневник перф-итерации (40.0 → 27.3
  → 3.4 мс), микробенчи, «reconciled» сноски про то, какой SHA был до фикса. Ценность —
  историческая, не «что делает код». **Замена:** сжать до 5-6 строк: «декод на POCO C71 сначала
  50 мс p95, после перф-итерации (fused-loop, ручной abs, L1 shape channel) — 3.4 мс p95 ≤ гейта
  5 мс; калибровка recovery не изменилась».
- `docs/ROADMAP-P7.md` (15× «fail-closed») — [SLOP] — термин к месту и не к месту. **Замена:**
  оставить там, где это реальное свойство отказа (декод junk-жеста), убрать как украшение.

### docs/GLIDE-PLAN.md

- `docs/GLIDE-PLAN.md:1-6` — [AGENT] — «Status: approved work plan (operator: "реализовать фазы
  5-7"). Research basis: `/tmp/glide-research/` clones …» — ссылка на несуществующий локальный
  `/tmp/` и адресация «operator». **Замена:** убрать `/tmp/`-путь и цитату оператора; оставить
  «план фазы 7; отчёт — ROADMAP-P7.md».
- `docs/GLIDE-PLAN.md:38-44` — [STALE] — сноска «2026-09-27: all four parked follow-ups are now
  shipped … Nothing on this list stays open.» — при этом live-scoring из follow-ups позже
  откатан (см. GLIDE-LIVE-STRIP4). Утверждение «nothing stays open» неверно постфактум.
  **Замена:** «follow-ups отгружены; live-scoring и 4 клетки позже откатаны (3.6.0)».
- `docs/GLIDE-PLAN.md` целиком — [DUP] — «DONE WHEN», «Design (from research)», «Phases and gates»
  дублируют соответствующие разделы отчёта ROADMAP-P7. **Замена:** после архивирования ROADMAP-P7
  этот план избыточен — свести к 1 абзацу или удалить.

### docs/GLIDE-PERSONAL.md

- `docs/GLIDE-PERSONAL.md:1-3` — [AGENT] — «Status: implemented, all gates green on the host; the
  device latency leg (C5 re-measurement on the POCO C71) is the operator's.» — [JARGON] «C5»,
  адресация оператору. **Замена:** «Личные слова участвуют в glide-кандидатах (готово 3.x).»
- `docs/GLIDE-PERSONAL.md:106-141` — [AGENT][STALE] — блоки «## Tests (+21 JVM)», «## Gates»
  («gradle test — 1 691 tests», «python … 502 tests today … AGENTS.md documents 507»), «Draft
  CHANGELOG entry (for the operator to paste)». Дрейфующие счётчики + черновик changelog внутри
  дока + прямое обсуждение расхождения счётчиков. **Замена:** удалить перечисление тестов и
  гейтов; changelog-черновик уже не нужен (релиз вышел).
- `docs/GLIDE-PERSONAL.md:97-98` — [SLOP] — «`CompositeGlideInventory` is not a data class and
  declares no `toString`: the arrays carry the user's words, which must never reach a log.» —
  верно по сути, но это код-коммент, а не содержание отчёта. **Замена:** одна строка «личные
  слова не логируются (пин `GlideSourceContractTest`)».

### docs/ROADMAP-P8.md и docs/ROADMAP-P8-PLAN.md

- `docs/ROADMAP-P8.md:1` — [SLOP] — «phase 8 report: the accumulated release, the Apple-UX
  batches, the engineering backlog and the supply-chain round» — перечисление-нагромождение в
  заголовке. **Замена:** «Отчёт фазы 8: релиз 3.1.x + Apple-UX + бэклог + supply-chain».
- `docs/ROADMAP-P8.md:5-6` — [AGENT] — «Everything below is **uncommitted**; commits, the tag,
  the push and the publication are the operator's (AGENTS.md).» — артефакт «UNCOMMITTED» +
  адресация. **Замена:** удалить (релиз давно вышел, 3.1.1 в истории).
- `docs/ROADMAP-P8.md:18-24` — [JARGON] — «R1 gates … R3 … R4 … R5 (tag, push, publish)» — коды
  под-стадий без расшифровки. **Замена:** заменить на человекочитаемые «гейты», «версия/changelog»,
  «упаковка», «тег/публикация».
- `docs/ROADMAP-P8-PLAN.md:1` — [DUP] — план фазы 8 дублирует отчёт ROADMAP-P8 (те же стадии
  R/A/B/C/D/E/F). **Замена:** после закрытия фазы план избыточен — архивировать/удалить, отчёт
  самодостаточен.
- `docs/ROADMAP-P8-PLAN.md:159-210` — [JARGON][OVERLONG] — «Stage C … C1 (F15a) … C2 … C3 …
  C5 (budget-device criterion) …» с длинными обоснованиями отклонений. **Замена:** свернуть до
  таблицы «пункт — вердикт — числа», убрать повторные обоснования (они уже в отчёте).

### docs/ROADMAP-P4.md (пример STALE-по-коду)

- `docs/ROADMAP-P4.md` раздел «Batch B — P6 two-edit typo recovery» + сноска про удаление
  класса #5 — [STALE] — весь батч описывает класс #5, который **удалён из кода** (`TdictPrefixIndex`
  сейчас `MAX_RESULTS=3`, класс #5 вырезан в 3.5.0 — подтверждено CHANGELOG «class #5 … deleted»).
  Сноска о удалении уже есть. **Замена:** после архивирования оставить как единственную запись
  об отклонении; в живом доке — свести к абзацу «класс #5 отклонён гейтами и удалён (3.5.0)».

## Системные паттерны

**П-1. Завершённые отчёты лежат в «живых» доках вопреки собственной конвенции.**
`docs/README.md` явно делит: живое наверху, «отчёты завершённых миссий — в `docs/archive/`».
ROADMAP-P1…P8, GLIDE-PERSONAL и оба плана — завершённые, но перечислены как живые. Правило чистки:
перенести все завершённые отчёты фаз в `docs/archive/roadmaps/`. **Массовый блокер:** ~50 файлов
исходников/тестов ссылаются на `docs/ROADMAP-PN.md`/`docs/GLIDE-*.md` в комментариях как на
провенанс (`grep` дал 220 совпадений в 116 файлах кода). Перенос без правки комментов оставит
битые пути. Рекомендация оператору: решить один раз — либо (а) пакетная замена doc-путей в
комментах при переносе, либо (б) чистка самих комментов кода от кодов миссий (это отдельная
область — исходники), тогда доки можно архивировать свободно.

**П-2. Гейт-таблицы и UAT-логи — главный источник «нейрослопа» и дрейфа.**
В каждом отчёте 2-4 таблицы вида «JVM N tests / python M / APK X B / SHA-256 …» плюс девайсные
UAT-таблицы со скриншот-путями (`build/device-uat-…`). Числа устарели повсеместно (в отчётах
1 257–1 703 JVM, в проекте сейчас 1 927; APK-размеры 1.8 МБ против 3.6.0). Правило: в отчёте
достаточно одной строки «гейты зелёные на дереве фазы»; конкретные счётчики/хэши/скриншоты — это
разовое свидетельство, ему место в `build/`-артефактах, а не в доке. Убрать таблицы во всех
восьми P-отчётах и в GLIDE-PERSONAL/GLIDE-LIVE-STRIP4.

**П-3. Жаргон миссий без расшифровки.** Коды P3a/P3b/P4/T1–T7/U1–U9/P1/P2/P7-1…P7-8/E4c/E5d/
G1–G4/C1–C5/W1–W6/M1–M5/S1–S2/R1–R5/EXPAND-1/TATDICT/TPERSB заголовками и в тексте. Понятны только
участнику. Правило: в живых доках заменить код на суть при первом упоминании («P4 → предсказания
после запятой»); в архиве — оставить (историческая запись). Особенно плотно: ROADMAP.md (таблица
фаз со столбцом кодов), P8-PLAN (R/A/B/C/D/E/F + C1–C5), P7 (P7-1…P7-8).

**П-4. Адресация к агентно-операторскому процессу в теле доков.** «No commits — the operator
commits» (в P1-P8), «uncommitted — awaits the operator's decision», «C5 … is the operator's»,
«written by the parallel agent», «/tmp/glide-research/ clones». Правило: удалить любые фразы про
коммиты/агентов/операторов/локальные `/tmp`-пути из тела доков — процессные правила живут в
AGENTS.md, а не повторяются в каждом отчёте.

**П-5. Дублирование план↔отчёт.** GLIDE-PLAN ↔ ROADMAP-P7, ROADMAP-P8-PLAN ↔ ROADMAP-P8: планы
пересказывают дизайн и гейты, которые затем целиком повторены в отчётах. Правило: по завершении
фазы план сворачивается в 1-2 абзаца или архивируется; единственный источник истины — отчёт.

**П-6. Сноски вместо правки в «живых» доках.** Политика «историю не переписываем» уместна для
архива, но в живом доке порождает строки, которые сами себя опровергают (P3:3 «P7 NOT started»
+ сноска P3:7 «это устарело»; GLIDE-PLAN:38 «nothing stays open» при последующем откате;
P4 batch B + сноска «класс #5 удалён»). Правило: доки, ещё числящиеся живыми, чинить прямо;
политику no-rewrite применять только после переноса в `docs/archive/`.

**П-7. Риторика и антропоморфизм.** «the flagship mission», «the library calls were the wall»,
«the 8.42 threshold is earned, not loose», «a partial clean result over a forced one» (×3),
«fail-closed» как украшение (15× в P7). Правило: убрать оценочно-драматические формулировки,
оставить факт и число.
