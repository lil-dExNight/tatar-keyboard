# TT-миссии (docs/TT-* + разговорный корпус + repack) — ревизия текстов

## Итог

Область — 10 «отчётов/планов миссий» (3 534 строки, ≈258 КБ) плюс 3 каталога
свидетельств (≈8,3 МБ, из них 6,9 МБ — один каталог: скриншоты + сгенерированные
JSON-дампы до 4,4 МБ). Диагноз: это **не документация продукта, а операционный
журнал агентных прогонов**. Все 10 файлов помечены `STATUS: done/complete`, но
шапки трёх из них до сих пор кричат «uncommitted — awaits the operator's
commit/release decision» и «version 1.9.15/31 · 2.0.0/32», хотя код давно на
**3.6.0/43** (`app/build.gradle`) и всё закоммичено (git log: `93966d2b`,
`7fea9797`, `d1e418d3`). Это делает область насквозь [STALE] + [AGENT].

Находки по категориям (крупные, без учёта поглощённых в паттерны): [AGENT] ≈45
(gate-таблицы со счётчиками тестов, «Files touched», «fresh agent / orchestrator
amendment», «uncommitted awaits operator»), [STALE] ≈20 (версии, «uncommitted»,
захардкоженные счётчики 1109/1152/1191/1234/1257 тестов и размеры APK), [JARGON]
пронизывает всё (P0–P5, E3b, E5c/E5d, D1/D2, G1-C2, C2, SIZE-1/2/3,
BIGRAM-ADJACENCY, IMPERATIVE-HEADS), [SLOP] ≈15 (пафос «Evidence over shipment»,
«отклонение не спрятано», «прочитать этот раздел глазами», «the numbers are
honest»), [OVERLONG] — сами объёмы (60 КБ и 55 КБ на две завершённые миссии),
[DELETE] — 3 каталога свидетельств + кандидаты на архивацию всех 10 доков.

Топ-3 действия:
1. **Вынести из репозитория 3 каталога свидетельств** (≈8,3 МБ скриншотов и
   `.generated.json`-дампов) — они не сборочные, не тестовые, ссылается на них
   только собственный отчёт миссии; таким артефактам место в `build/`
   (gitignored), как прямо и написано в самих доках.
2. **Переместить все 10 доков в `docs/archive/`** (миссии закрыты) и снять
   ложь про «uncommitted / версия 1.9.15» — либо, если оставлять живыми,
   выпилить gate-таблицы, «Files touched» и разделы независимой верификации.
3. **Свернуть агентный слой**: счётчики тестов, «fresh agent», «orchestrator
   amendment», размеры APK по фазам — это не факты о продукте, а следы прогонов.

Замечание по коду: десятки комментариев в `app/src/**` и `scripts/**` цитируют
эти доки по имени (`docs/TT-SUGGESTIONS.md`, `docs/TT-TYPO-NEXT.md`, …) — при
переносе в архив ссылки надо поправить, но сами доки живыми это не делает
(ссылки — тоже часть «нейрослопа», датированные сноски в тестах).

---

## Файлы на удаление / перенос

| путь | размер | почему | кто ссылается (grep) | риск |
|---|---|---|---|---|
| `docs/corpus-conversational/` (весь каталог, 30 файлов) | 6.9 МБ | Свидетельства прогона: 4.4 МБ `changed-heads-naive.generated.json`, 1.5 МБ `changed-heads-thinned.generated.json`, ещё ~10 JSON-дампов, 4 PNG-скриншота полосы, снапшоты `measure*.py`/`analyze*.py`, 2×`DECISION-RULE-PRECOMMIT*.md`. Сгенерированные артефакты в git. | Только `CORPUS-CONVERSATIONAL-{TT,RU}.md` (свои же). ОДНО живое исключение: `scripts/bigram_extra_heads_tat.txt:26` в шапке ссылается на `.../evidence/apply_rule_tt.py` как источник воспроизведения. | средний (сохранить `apply_rule_tt.py` рядом со скриптом или в `scripts/`) |
| `docs/russian-bigrams-repack/` (весь каталог, 9 файлов) | 668 КБ | 4 PNG на верхнем уровне (`ru-repack-*.png`, `tt-repack-min.png`) + `evidence/` (5 файлов: `changed-heads-analysis.generated.json` 45 КБ, `measure*.json`, снапшоты `.py`, `DECISION-RULE`). Пруфы закрытой перепаковки 1.9.7. | Только `RUSSIAN-BIGRAMS-REPACK.md` (свой же). Код/тесты/CI — нет. | низкий |
| `docs/nextword-race/evidence/` (7 файлов) | 748 КБ | 5 PNG (`before/after-*.png`), `trace-first-nextword.txt` (удалённая инструментация), `acceptance-runs.txt` (6 строк эмулятор-прогона). | Только `NEXTWORD-RACE.md` (свой же). | низкий |
| `docs/TT-SUGGESTIONS.md` | 60 КБ / 906 стр. | Отчёт закрытой миссии (`Status: complete`, все P0–P5 done). 906 строк на фичу подсказок. Кандидат в `docs/archive/`. | `docs/README.md:326`; ~25 датированных сносок в тестах/коде. | низкий (архивировать, не удалять) |
| `docs/TT-TYPO-NEXT.md` | 55 КБ / 824 стр. | Отчёт закрытой миссии. 3 фазы «NOT SHIPPED» описаны так же подробно, как поставленная — операционный журнал, не документация. Кандидат в `docs/archive/`. | `docs/README.md:334`; ~15 сносок в тестах. | низкий (архивировать) |
| `docs/TT-NEXTWORD-FILL.md` | 20 КБ / 267 стр. | Отчёт закрытой миссии; сам говорит «committed as d1e418d3». Две девайс-UAT-таблицы (20-09 эмулятор + 21-09 устройство) дублируют друг друга. | `docs/README.md:358`; сноски в тестах. | низкий (архивировать) |
| `docs/CORPUS-CONVERSATIONAL-TT.md` | 25 КБ / 294 стр. | Отчёт закрытой миссии (вошло в 1.9.8). | `docs/README.md:418`; `BigramStorageContracts.kt:108`. | низкий (архивировать) |
| `docs/CORPUS-CONVERSATIONAL-RU.md` | 23 КБ / 281 стр. | Отчёт закрытой миссии (вошло в 1.9.8). | `docs/README.md:414`; `BigramStorageContracts.kt:172`. | низкий (архивировать) |
| `docs/RUSSIAN-BIGRAMS-REPACK.md` | 17 КБ / 209 стр. | Отчёт закрытой миссии (вошло в 1.9.7); дрейф 4195/4195 «закрыт», проблема историческая. | `docs/README.md:411`; `BigramStorageContracts.kt`. | низкий (архивировать) |
| `docs/TT-SUGGESTIONS-PLAN.md`, `docs/TT-TYPO-NEXT-PLAN.md`, `docs/TT-NEXTWORD-FILL-PLAN.md` | 17+13+4 КБ | Планы выполненных миссий; дублируют «DONE WHEN» из отчётов + носят footnote-сноски «executed». После закрытия отдельная ценность около нуля. | `docs/README.md`; пара сносок в тестах (`TtSuggestEvalTest`, `TtTypoPhase*`). | низкий (архивировать вместе с отчётами) |

Общий вывод по каталогам: три `evidence`-каталога = **≈8,3 МБ бинарных и
сгенерированных артефактов в git**, которые сами доки называют «в `build/`, не
коммитятся» (напр. `CORPUS-CONVERSATIONAL-TT.md:290`, `NEXTWORD-RACE.md`
«удалена до фикса, в дереве её нет»). Их закоммитили вопреки собственному
правилу. Единственная реальная привязка — `apply_rule_tt.py` из шапки
`bigram_extra_heads_tat.txt`.

---

## Правки по файлам

### docs/TT-SUGGESTIONS.md

- `docs/TT-SUGGESTIONS.md:8` — [STALE][AGENT] — «The whole changeset is
  uncommitted and awaits the operator's commit/release decision (no version
  bump).» — Ложь: закоммичено, версия давно 3.6.0/43. **Удалить предложение**
  (и весь хвост про «awaits operator» в шапке).
- `docs/TT-SUGGESTIONS.md:817` — [AGENT] — «Commit decision (the changeset is
  intentionally uncommitted), version bump and release …» — тот же
  устаревший агентный хвост. **Удалить абзац «Left for the operator».**
- `docs/TT-SUGGESTIONS.md:830` — [STALE] — «reports versionName 1.9.15 /
  versionCode 31 … POCO C71» — версия из прошлой эпохи. Если раздел UAT
  сохраняется — заменить на «релиз того времени (1.9.15)», но лучше вынести всю
  DEVICE-UAT-секцию (строки ~820–906) в архив: это лог одного прогона.
- `docs/TT-SUGGESTIONS.md:403-408,553,667-668,739-745` — [AGENT][STALE] — gate-
  таблицы «421 tests, 0 failures», «1 109 → 1 152 → 1 191 tests», «unsigned APK
  1 858 375 bytes», «release_check.sh … 8 artifact checks PASS». Захардкоженные
  дрейфующие счётчики тестов и размеры APK по фазам. **Удалить все gate-таблицы**
  (это следы прогонов, не свойства фичи).
- `docs/TT-SUGGESTIONS.md:775` — [STALE] — таблица «before (1.9.15) → after»
  размеров APK. Числа привязаны к мёртвой версии. Удалить или свести к
  «дельта ассетов» без версий.
- `docs/TT-SUGGESTIONS.md:3-9` — [OVERLONG][JARGON] — вся шапка: «Phase P0
  (evaluation harness + baseline) complete 2026-09-19; P1 … P5 … every gate
  green, the mission DONE WHEN audited in the P5 section». P0–P5 без расшифровки
  для читателя вне миссии. **Заменить на 1–2 строки**: «Татарские подсказки:
  словоформы, same-stem boost, предсказания в начале предложения. Реализовано,
  в проде с 1.9.15.»
- `docs/TT-SUGGESTIONS.md:171` (и по всему файлу «### Files touched») — [AGENT] —
  списки «какие файлы тронул прогон». В отчёте фичи бесполезны, git это хранит.
  **Удалить все разделы Files touched / Recalibrations / Gates.**

### docs/TT-TYPO-NEXT.md

- `docs/TT-TYPO-NEXT.md:3-13` — [STALE][AGENT][JARGON] — «Status: Phase A done …
  Phase B measured: gates G1/G2 failed … Phase C2 (2026-09-20, orchestrator
  amendment): … class #4 SHIPS … the changeset is uncommitted and awaits the
  operator's commit/release decision (no version bump).» — 11 строк кодов миссий +
  ложь про uncommitted. Проверено по коду: `FuzzyEditPolicy.TATAR` = `{LONG_PRESS,
  SUBSTITUTION}` + bonus (класс #4) действительно в проде — но это единственный
  факт из абзаца. **Заменить всё на**: «Опечатка `сцләм` → подсказка `сәләм` в
  полосе (класс правок #4, только для татарского, при пустом точном совпадении).
  В проде.»
- `docs/TT-TYPO-NEXT.md:355` — [SLOP] — «Evidence over shipment.» — лозунг.
  **Удалить.**
- `docs/TT-TYPO-NEXT.md:410` — [AGENT][JARGON] — «**Gate amendment (orchestrator,
  2026-09-20).** Phase B proved G2's 2 % precision threshold miscalibrated …» —
  внутренняя переписка про пороги гейтов между агентом и «оркестратором».
  **Удалить весь блок методологии гейтов** (строки ~410–430) — читателю продукта
  не нужно, почему первая калибровка гейта была неверной.
- `docs/TT-TYPO-NEXT.md:582-600` — [AGENT] — «### Why this phase exists
  (orchestrator amendment, recorded verbatim in intent) … A METHODOLOGICAL error
  in the Phase-C gates themselves». **Удалить**: разбор ошибок собственного
  процесса — чистый журнал.
- `docs/TT-TYPO-NEXT.md:794-795` — [AGENT] — «The plan's Phase-D task 4
  (independent re-verification by a fresh agent) is the orchestrator's item, not
  this run's.» — адресация к агентному конвейеру. **Удалить.**
- `docs/TT-TYPO-NEXT.md:807-820` — [AGENT] — «## Independent verification
  (2026-09-20, verdict: PASS) — A fresh agent re-verified the mission adversarially
  … Two findings were fixed by the orchestrator afterwards». **Удалить весь раздел**
  — это отчёт о работе агента о работе агента.
- `docs/TT-TYPO-NEXT.md:171,384,564,703,797` — [AGENT] — пять разделов «Files
  touched (Phase A/B/C/C2/D)». **Удалить.**
- `docs/TT-TYPO-NEXT.md:727,732` и другие gate-строки — [AGENT][STALE] — «1 234
  tests, 0 failures … version 1.9.15/31 … 8/8 artifact checks PASS». Счётчики и
  мёртвая версия. **Удалить gate-таблицы всех фаз.**
- Фазы B и C целиком (строки ~130–580) — [OVERLONG] — две отвергнутые
  («NOT SHIPPED») фазы описаны с той же детализацией (raw gate-логи, таблицы p50/
  p95 по устройству), что и поставленная. Для живого дока достаточно: «пробовали
  геометрический класс #2 и полную подстановку — отвергнуто по recovery/precision,
  оставлено класс #4». **Сжать в один абзац**, детали — в архив.

### docs/TT-NEXTWORD-FILL.md

- `docs/TT-NEXTWORD-FILL.md:3-6` — [STALE][JARGON] — «Status: phases A–C done
  (2026-09-20, post-2.0.0, committed as d1e418d3 + 9833892b). Phase D: … device
  UAT blocked that day (phone off USB), replayed on the emulator, then DONE on
  the POCO C71 2026-09-21 … Only Phase E (independent re-verification) and the
  operator's release decision remain.» — этот файл сам признаёт коммит (в отличие
  от двух соседних, где «uncommitted»), но так же завис в «phase E remains».
  **Заменить на**: «Полоса после набранного слова больше не бывает пустой:
  добор глобально-частотными словами. В проде.»
- `docs/TT-NEXTWORD-FILL.md:135` — [STALE] — «version 2.0.0/32 matches
  app/build.gradle (no bump)» — сейчас 3.6.0/43, уже не matches. **Удалить
  gate-строку.**
- `docs/TT-NEXTWORD-FILL.md:201` — [STALE] — «install -r over 1.9.15/31 → 2.0.0/32»
  — мёртвые версии. Часть UAT-лога, выносить в архив.
- Две UAT-таблицы: «Device UAT — BLOCKED … emulator fallback» (F1–F12, стр.
  ~150–175) и «Device UAT 2026-09-21» (стр. ~205–260) — [DUP][AGENT] — один и тот
  же сценарий прогнан дважды (эмулятор, потом устройство), обе таблицы полны.
  **Оставить максимум одну краткую**, лучше вынести обе в архив.
- `docs/TT-NEXTWORD-FILL.md:97,183` — [AGENT] — «## Files touched (phases A–C)»,
  «### Files touched (Phase D)». **Удалить.**
- `docs/TT-NEXTWORD-FILL.md:130` — [AGENT][STALE] — «1 257 tests, 0 failures».
  Дрейфующий счётчик. **Удалить.**

### docs/TT-*-PLAN.md (три плана)

- `docs/TT-SUGGESTIONS-PLAN.md:3-6`, `docs/TT-TYPO-NEXT-PLAN.md:3-6`,
  `docs/TT-NEXTWORD-FILL-PLAN.md:3` — [JARGON][AGENT] — «Status: approved work
  plan … this plan is not rewritten, only annotated with dated footnotes.» —
  ритуал «план не переписываем, только сноски» — артефакт процесса миссий.
- `docs/TT-SUGGESTIONS-PLAN.md` «## DONE WHEN» (стр. 9–29) — [DUP] — дословно
  повторяет «DONE WHEN» из `TT-SUGGESTIONS.md:11-30`. То же у двух других пар
  план/отчёт. **При архивации — держать одну копию.**
- `docs/TT-TYPO-NEXT-PLAN.md:186-201` и `docs/TT-NEXTWORD-FILL-PLAN.md:64-78` —
  [AGENT] — footnote-блоки «2026-09-20 — Phase X DONE/MEASURED (uncommitted) …
  All gates green: 1196 JVM tests, 455 python …». Счётчики + «uncommitted».
  **Удалить сноски-отчёты о прогоне** (для планов достаточно «выполнено»).
- `docs/TT-TYPO-NEXT-PLAN.md:206`, `docs/TT-NEXTWORD-FILL-PLAN.md:62` — [AGENT] —
  «Independent re-verification of the DONE WHEN items by a fresh agent.» как пункт
  плана. **Удалить.**

### docs/NEXTWORD-RACE.md (по-русски, короче и суше — лучший из области)

- `docs/NEXTWORD-RACE.md:154` — [WEIRD] — финальная строка-заглушка
  «ПЕРВОЕ СЛОВО ПРЕДСКАЗЫВАЕТСЯ» (капсом, без точки, отдельным абзацем) — лозунг-
  подпись прогона. **Удалить строку.**
- `docs/NEXTWORD-RACE.md:1` (и весь файл) — [JARGON] — «Миссия nextword-race,
  2026-09-01 … Открытый хвост из HANDOFF.md и docs/emoji-suggest/ENGINE.md (гонка
  двухступенчатого attach биграммной таблицы, E5c)». Коды E5c/E5d/D1/D2 без
  расшифровки. Терпимо (это техразбор бага), но при живом хранении расшифровать
  D1/D2 в одну строку.
- Раздел «## Проверки» (таблица с «JVM-тесты 1063», «assembleRelease 1 816 075 Б»)
  — [AGENT][STALE] — счётчики прогона. **Удалить таблицу проверок.**
- Раздел «## Чего этим не доказано» — [SLOP-] — на грани, но это честная оговорка
  об ограничениях (D2 на устройстве не изолирован), а не пафос. **Оставить.**

### Каталоги evidence (инвентарь)

- `docs/corpus-conversational/evidence/DECISION-RULE-PRECOMMIT-TT.md:1-6` — [AGENT]
  — «Записано 2026-08-31 23:22 MSK, до первого запуска упаковщика … SHA-256 этого
  файла зафиксирован в отчёте … Ни один порог после появления результатов не
  двигается.» — ритуал «правило приёмки с хешем-таймстампом» — типичный агентный
  паттерн доказывания добросовестности. Целевой файл на удаление.
- `docs/nextword-race/evidence/acceptance-runs.txt` — [AGENT] — 6 строк
  `run=N word=… FILLED dark=0.012…` — сырой вывод эмулятор-скрипта. На удаление.
- `*.generated.json` (все, до 4.4 МБ) — [DELETE] — сгенерированные дампы
  «изменившихся голов», не читаются человеком, не тестируются. На удаление.
- `*.py`-снапшоты (`measure*.py`, `analyze_changed_heads*.py`, `spot_check*.py`) —
  [DUP] — снимки одноразовых измерительных скриптов; частично дублируют
  `scripts/` и `research/corpus/`. На удаление (кроме `apply_rule_tt.py` — см.
  таблицу, на него ссылается `bigram_extra_heads_tat.txt`).

---

## Системные паттерны

1. **«uncommitted / awaits the operator / version 1.9.15·2.0.0»** — ложный
   агентный хвост в шапках и хвостах отчётов. Встречается: `TT-SUGGESTIONS.md:8,817,830`,
   `TT-TYPO-NEXT.md:12`, `TT-NEXTWORD-FILL.md:3,135,201`, `TT-*-PLAN.md` сноски,
   `docs/README.md:328,353,367`. Правило: миссия закрыта и в проде (3.6.0/43) —
   удалить всякое «uncommitted/awaits/no version bump», версии из тела заменить на
   «релиз N.N.N» или убрать.

2. **Gate-таблицы и счётчики тестов внутри доков** — [AGENT][STALE]. Каждая фаза
   каждой миссии несёт таблицу «./gradlew test → NNNN tests, 0 failures / lint /
   check-no-internet / APK NNNN B». Числа дрейфуют (1063→1109→1152→1191→1234→1257…)
   и уже неверны. Правило: удалить все gate/Recalibrations/Files-touched таблицы;
   факт «покрыто тестами и гейтами» — одна строка максимум.

3. **Коды миссий без расшифровки** — [JARGON]. P0–P5, E3a/E3b, E5a/E5c/E5d,
   D1/D2, G1/G2/G3/G1-C2/G2-C2/G3-C2, C2, SIZE-1/2/3, BIGRAM-ADJACENCY,
   IMPERATIVE-HEADS, D1A. Понятны только участникам. Правило: в живом тексте — либо
   расшифровать в одном месте, либо заменить на человеческое описание фичи.

4. **«orchestrator / fresh agent / independent verification / recorded verbatim in
   intent»** — [AGENT]. Следы многоагентного конвейера (`TT-TYPO-NEXT.md:410,582,
   794,807`, планы). Правило: удалить целиком — это метаданные процесса, не продукт.

5. **Пафос и самозаверение** — [SLOP]. «Evidence over shipment»
   (`TT-TYPO-NEXT.md:355`), «отклонение не спрятано», «претворяться им было бы
   подгонкой», «Оператору при релизе стоит прочитать этот раздел глазами»
   (`CORPUS-CONVERSATIONAL-RU.md`, `-TT.md`), «the numbers are honest». Правило:
   факты оставить, риторику убрать; «не спрятано/честно» — само по себе шум.

6. **Отвергнутые фазы описаны как полноценные** — [OVERLONG]. `TT-TYPO-NEXT.md`
   фазы B и C («NOT SHIPPED») занимают ~450 строк с сырыми gate-логами и девайс-
   таблицами p50/p95. Правило: закрытые/отвергнутые ветки — одна строка «пробовали
   X, отвергли по Y», подробности в архив/git.

7. **Свидетельства-артефакты в git** — [DELETE]. 3 каталога, ≈8,3 МБ скриншотов и
   `.generated.json`, при том что доки сами пишут «в `build/`, не коммитится».
   Правило: `evidence/` с бинарями и генерёжкой — в gitignore, не в дерево; если
   нужны как пруф — ссылка на релиз/CI-артефакт.

8. **План ⇄ отчёт дублируют «DONE WHEN» и Research summary** — [DUP]. Три пары
   plan/report повторяют цели и диагностику. Правило: при архивации оставлять один
   документ на миссию (отчёт поглощает план).
