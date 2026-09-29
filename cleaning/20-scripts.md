# scripts/ — ревизия текстов

## Итог

Область — 22 `*.py` (11 183 стр.), 7 `*.sh` (3 500 стр.) и 5 данных (`*.txt/tsv/json`).
`#`-комментарии в питоне — 527 строк (~4,7 %), но основной объём текста лежит в
**модульных docstring'ах**: только 7 крупнейших дают 443 строки (typo_pack 43,
bigram_asset_pack 71, rebuild_assets 74, wordform_gen 63, dict_accept 61, glide_pack 74,
make_eval_set 57), а всего описательного текста в питоне ~12 %. Диагноз: код чистый и
детерминированный, но docstring'и превращены в мини-отчёты миссий — с кодами (E5a/E5b,
SIZE-1/2/3, P7-1, TT-SUGGESTIONS Px, ROADMAP-P4), датами (95 сносок `2026-09-xx`),
ссылками на `docs/*.md` (53) и риторикой («fail-closed» 40 раз, «data, not code» 6 раз,
«there is nothing to buy with it»). В `.sh` та же болезнь острее: `emulator-smoke.sh`
несёт 29 датированных сносок, `release_check.sh` — 12.

Находки: [OVERLONG] ~10 гигантских docstring'ов + 4 шапки `.sh`; [JARGON] 127 кодов
миссий + 53 ссылки на доки (архивируются → станут битыми); [AGENT] 95 дат, 39
«operator/оператор», «agents have no network»; [SLOP] 40 «fail-closed», повтор одной
мысли; [DUP] boilerplate в 6–9 файлах; [DEAD] класс опечаток #5 в `typo_pack.py`
(нет теста, нет движка); [DELETE] `suggest_eval.py` и `wordform_kaikki_check.py` (без
тестов, ссылки только из доков).

Топ-3 действия: (1) сжать все модульные docstring'и до 3–8 строк «что/вход/выход»,
вынеся историю миссий и коды в git-историю; (2) массово убрать датированные сноски,
коды миссий и ссылки `docs/*.md` из комментариев; (3) удалить мёртвый класс #5 из
`typo_pack.py` и рассмотреть удаление двух бестестовых скриптов.

Важно про риск: **ни один JVM/python-тест не пинит комментарии или docstring'и скриптов
как текст** (проверено grep'ом — source-contract на тексты скриптов нет). Единственная
текстовая привязка теста — это *выводимая* строка `вычитано` в `review_batches.py`
(`test_review_batches.py:175,257`), но это функциональный вывод, а не комментарий.
Поэтому чистка комментариев тестов не роняет.

---

## Файлы/код на удаление

| Путь (или символ) | Размер | Почему | Кто ссылается (grep) | Риск |
|---|---|---|---|---|
| `typo_pack.py` — `build_two_substitution_typo_set` + ветка `edit_class == 5` + `choices=(1,2,3,4,5)` + текст `#5` | ~60 стр. (825–880, 1044–1049, 1090, 1100) | «Класс опечаток #5» (две подстановки). `test_typo_pack.py:622` гоняет только `for edit_class in (1,2,3,4)` и комментирует «The four edit classes»; движок `FuzzyPrefixVariants.kt` класса #5 не реализует. Мёртвая ветка «ROADMAP-P4 P6», не дошедшая до продукта. | только сам `typo_pack.py`; тест НЕ покрывает | Низкий-средний. Сузить `choices` до `(1,2,3,4)`; тест `test_unknown_edit_class_raises` использует `edit_class=9`, его не заденет. Свериться с ROADMAP-P4 перед удалением. |
| `suggest_eval.py` | 222 стр. / 9,6 КБ | Corpus-stat замер eval-набора; JVM-аналог `TtSuggestEvalTest` меряет то же. **Нет теста** (`tests/suggest_eval/test_suggest_eval.py` тестирует `make_eval_set.py`+`dictionary_coverage.py`, не его — проверено по importlib), не вызывается `rebuild_assets`/CI. | только `docs/TT-SUGGESTIONS-PLAN.md`, `docs/TT-SUGGESTIONS.md` | Средний. Одноразовый измеритель; удаление теряет офлайн-сверку с JVM. Держать, если офлайн-замер ещё нужен; иначе в архив. |
| `wordform_kaikki_check.py` | 273 стр. / 12 КБ | Dev-time валидация против kaikki.org, **требует сеть**, сам говорит «NEVER part of a gate». Нет теста. | `docs/TT-SUGGESTIONS*.md`; упоминается в комментариях `wordform_gen.py` | Средний. Легитимный, но одноразовый dev-инструмент. В агентной среде без сети бесполезен. Кандидат в `research/` или удаление. |
| `bigram_extra_heads_conv.py` | 100 стр. / 4 КБ | Одноразовый генератор списка extra-heads (правило EXPAND-1). Список-результат (`bigram_extra_heads_tat.txt`) закоммичен; сам генератор в конвейере не участвует. | `tests/bigram_asset_pack/…` (упоминание), `docs/ROADMAP-P4.md` | Средний-низкий. Проверить, тестируется ли реально (не только упоминается). Возможно в `research/`. |

Полноценных «мёртвых по имени» скриптов нет: у всех паковщиков есть тесты в `tests/*` и
они вызываются из `rebuild_assets.py` либо CI (`ci.yml`: `check-no-internet.sh`,
`release_pack.sh`).

---

## Правки

Ниже — представительные находки с `путь:строка`. Повторяющийся boilerplate свёрнут в
«Системные паттерны». Приоритеты: P1 — ложь/агентные артефакты/мусор, P2 — сильное
упрощение, P3 — косметика.

### typo_pack.py (docstring 1–43, 77 `#`-строк)

- `typo_pack.py:1` — [OVERLONG][JARGON] — «Build the deterministic edit-class typo sets used
  to calibrate recovery@3 (E3a/E3b, TT-TYPO-NEXT Phase B).» — 43-строчный docstring с кодами
  E3a/E3b/Phase B/C, «PROVEN equal … POCO C71», ссылками на `docs/`. — **P2**: сжать до
  «Генерирует детерминированные наборы опечаток (классы 1–4) для калибровки fuzzy-декодера.
  Вход: раскладка `res/xml/rowkeys_tatar*.xml` + словарь-ассет (пины в коде). Выход:
  воспроизводимый UTF-8/LF-набор.»
- `typo_pack.py:20` — [AGENT] — «(110,000 since 2026-09-20, TT-SUGGESTIONS P2)» — дата+код
  миссии в скобках. — **P1**: убрать скобку, оставить «110 000 записей».
- `typo_pack.py:24` — [SLOP] — «this is the same principle the engine's `KeyNeighborTableBuilder`
  follows. Since TT-TYPO-NEXT Phase B (2026-09-20) the pair set … is PROVEN equal to the
  on-device one» — пафос + дата. — **P1**: «Пары читаются из `latin:moreKeys` и
  симметризуются как `KeyNeighborTable`.»
- `typo_pack.py:34` — [DUP] — «The generator is fail-closed. It exits nonzero and writes no
  partial output when:» + 8 пунктов. — **P2**: свести к «Падает без записи при
  несовпадении пинов, битой раскладке или невалидном UTF-8.» (см. паттерн DUP-1).
- `typo_pack.py:833,880,1090,1100` — [DEAD] — «Edit class #5 (ROADMAP-P4 P6): two
  substitutions…» — мёртвый класс (см. таблицу удаления). — **P1**: удалить функцию,
  ветку `== 5`, значение `5` из `choices` и текст `#5` в help.

### dictionary_pack.py (docstring 1–8 EN, инлайн-комменты RU)

- `dictionary_pack.py:6` — [SLOP] — «One binary format serves every language … `--language tat`
  … reproduces the D1a Tatar asset byte for byte.» — код D1a без расшифровки. — **P2/JARGON**:
  «Один формат для всех языков; язык задаёт лишь алфавит фильтра и бюджет размера.»
- `dictionary_pack.py:40–45` — [SLOP] — «A per-language budget was considered and dropped:
  a second, laxer number would only ever be an invitation to ship a bigger artifact without
  noticing, and there is nothing to buy with it.» — риторическое эссе про отвергнутый вариант. —
  **P2**: удалить абзац, оставить «Единый бюджет для всех языков (замер: русский top-100k
  укладывается).»
- `dictionary_pack.py:46–52` — [LANG][AGENT] — «Schema 2 (SIZE-1, docs/SIZE-SCHEMA2.md):
  блочный front-coding (K = 8 — замер 2026-09-01 …)» — переключение на русский посреди
  английского файла + код SIZE-1 + дата + ссылка на док. — **P1**: «Schema 2: блочный
  front-coding (K=8), u8-длины, varint-частоты; lossless к schema 1.»

### bigram_asset_pack.py (docstring 1–71 — самый длинный)

- `bigram_asset_pack.py:1` — [JARGON] — «E5b: pack the shipped Tatar bigram table asset» —
  код E5b как заголовок. — **P2**: «Паковщик татарской таблицы биграмм (magic `TATBIGR\0`,
  schema 3).»
- `bigram_asset_pack.py:11–18` — [SLOP][AGENT] — «This is the real, byte-exact artifact
  generator. It is deliberately a SEPARATE file from `bigram_pack.py` (E5a): that script is
  the measurement prototype whose gate was independently reviewed on 2026-08-17 … turning it
  into the production packer as well would blur what was reviewed.» — история ревью + дата. —
  **P1**: «Отдельно от `bigram_pack.py` (тот — измерительный прототип); переиспользует его
  data-layer helpers.»
- `bigram_asset_pack.py:20` — [JARGON] — «Format (PROPOSALS.md, "## E5" / "E5b. Секции",
  byte-for-byte):» — ссылка на архивный док как обоснование. — **P3**: убрать ссылку,
  оставить описание формата.
- `bigram_asset_pack.py:47–56` — [OVERLONG] — «**Heads are chosen by unigram frequency…** …
  frequent Tatar imperatives (the bare verb stem: "кил" — come, "кит" — go) sit just below H
  … (docs/BIGRAM-ADJACENCY.md, "Почему повелительные формы молчат").» — три абзаца
  обоснования `--extra-heads` с примерами и ссылкой. — **P2**: «`--extra-heads FILE`:
  добавляет слова в набор голов вне зависимости от ранга (слово уже должно быть в словаре).»
- `bigram_asset_pack.py:65` — [AGENT] — «Multilingual since 2026-08-21 (`docs/RUSSIAN-BIGRAMS.md`):»
  — дата + ссылка. — **P1**: «`--language` выбирает алфавит; по умолчанию татарский.»

### bigram_pack.py

- `bigram_pack.py:1` — [JARGON] — «E5a: measure the size and the usefulness of a Tatar bigram
  table before any Android code.» — код E5a. — **P2**: «Измеряет размер и полезность таблицы
  биграмм (прототип; продакшн-паковщик — `bigram_asset_pack.py`).»
- `bigram_pack.py:29` — [AGENT] — «Usage (the corpora are downloaded by a human — agents have
  no network):» — прямой артефакт агентной работы. — **P1**: «Использование (корпуса
  скачиваются вручную):».
- `bigram_pack.py:3` — [SLOP] — «so every rule it applies is the rule written in PROPOSALS.md
  ("## E5"), not a convenient approximation» — риторика + ссылка. — **P2**: удалить оговорку.

### rebuild_assets.py (docstring 1–74, RU, 66 `#`-строк)

- `rebuild_assets.py:3–9` — [OVERLONG][AGENT] — «Исторический источник багов, который этот
  скрипт закрывает: … татарская таблица разошлась со словарём на 78 голов (закрыто в 1.9.4,
  `docs/archive/bigrams/IMPERATIVE-HEADS.md`), русская — на 4 195 …» — история багов с
  номерами версий и ссылками. — **P2**: «Единый вход пересборки: словари → таблицы биграмм →
  пины → проверка. Одна команда вместо двух ручных (раньше вторую половину забывали).»
- `rebuild_assets.py:12` — [JARGON] — «(татарский, с 2026-09-20 — TT-SUGGESTIONS P2) стадию
  словоформ» — дата + код. — **P1**: «(татарский) стадию словоформ `build_admitted_wordforms`».
- Весь docstring несёт 10 ссылок `docs/*.md` и 12 дат `2026-…` — крупнейший источник
  [JARGON]/[AGENT] в области. — **P2**: сократить до ~15 строк (что делает `--baseline`,
  `--only`, `--check`), детали шагов — в архив/доки.

### wordform_gen.py (docstring 1–63)

- `wordform_gen.py:1` — [JARGON] — «Build-time Tatar word-form generator (TT-SUGGESTIONS phase
  P1).» — код фазы. — **P3**: убрать «(TT-SUGGESTIONS phase P1)».
- `wordform_gen.py:6–7` — [JARGON] — «The output is a CANDIDATE list for phase P2: forms are
  emitted without any corpus check; P2 admits only forms attested…» — P2 без расшифровки. —
  **P2**: «Выход — список КАНДИДАТОВ; допуск по корпусу делается на следующем шаге.»
- Лингвистическое ядро (harmony, суффиксы, `kaikki.org`) 8–55 **оставить** — это содержательное
  описание правил, а не слоп. — **не трогать**.

### dict_accept.py (docstring 1–61 — эссе)

- `dict_accept.py:1` — [SLOP] — «Cut the dictionary acceptance queues into portions a human can
  actually read.» ... «Nobody reads that in one sitting, and nobody ever will.» (в
  `review_batches.py:2`) — риторика. — **P2**: «Режет очереди приёмки на нумерованные порции;
  `slice`/`collect`.»
- `dict_accept.py:15` — [SLOP][AGENT] — «Ручная вычитка 39 176 слов не состоится — оператор
  сказал это прямо.» — прямая речь оператора в docstring. — **P1**: удалить.
- `dict_accept.py:33–61` — [OVERLONG][AGENT][JARGON] — «РАСШИРЕНИЕ 1.9.1 (миссия tt-dict-widen,
  отчёт — docs/DICT-WIDEN.md) Оператор посмотрел сто случайных отклонённых слов…» — 29-строчный
  рассказ миссии с числами (`8 310`, `417 (5 %)`, `ме (19 092)`), решениями оператора и
  ссылкой на архивный док. — **P1**: свести к «Принимаются все отклонённые, кроме формальных
  обрывков (коротких/без гласных, русский) и `EXCLUDED_WORDS`.» Историю миссии — в git.
- `dict_accept.py:29` — [SLOP] — «принятое — подсказка, которая позорит клавиатуру у живого
  человека.» — пафос. — **P2**: «ложно принятое слово хуже ложно отклонённого.»

### review_batches.py (docstring 1–~30, 47 `#`-строк)

- `review_batches.py:2–4` — [SLOP] — «are 35 444 and 3 734 rows long. Nobody reads that in one
  sitting, and nobody ever will.» — риторика + конкретные счётчики (устаревают). — **P2**:
  «Очереди слишком длинны для одного прохода; скрипт режет их на порции.»
- `review_batches.py` docstring — [SLOP] — «Two rules this script must never break, both from
  the mission dossier: … ``approved`` IS NEVER WRITTEN. … the operator's own act, personally
  and by name.» — «mission dossier», капслок, «operator … by name». — **P1**: «`approved`
  никогда не пишется скриптом — это ручное решение. Непомеченное слово в вычитанной порции
  считается принятым.» ⚠ строка вывода `вычитано` пинится `test_review_batches.py:175,257` —
  **не менять формат самой выводимой строки**, только комментарии.

### glide_pack.py (docstring 1–74)

- `glide_pack.py:1` — [JARGON] — «...calibrate the glide decoder (P7-1 of docs/GLIDE-PLAN.md;
  mission report docs/ROADMAP-P7.md).» — коды P7-1 + 2 ссылки. — **P2**: «Генерирует
  детерминированный синтетический набор глайд-жестов для калибровки декодера.»
- `glide_pack.py:16–41` — [OVERLONG] — модель координат/шума на ~30 строк с «reference screen
  (1080 px, 440 dpi — the emulator smoke AVD class)», «the P7-4 device tuning re-derives…». —
  **P2**: сжать до сути (геометрия из `rows_tatar.xml`+`config.xml`, вертикальная модель —
  документированная, не замер), коды P7-4 убрать.
- Примечание [STALE-риск]: «live-превью глайда удалено» (из брифа) — в `glide_pack.py`
  генератор жестов не про live-превью; ложных упоминаний «live preview» в scripts нет
  (проверено grep). Находок нет.

### make_eval_set.py (docstring 1–57)

- `make_eval_set.py:1` — [JARGON] — «(TT-SUGGESTIONS phase P0)» — код фазы. — **P3**: убрать.
- `make_eval_set.py:9–14` — [AGENT] — «The documented output is 218 552 rows, 12 619 164 bytes,
  SHA-256 `8420ec0a…82e2`; this script re-runs the converter … and VERIFIES that digest» —
  SHA/размеры в комментарии (пин лучше держать в коде-константе, а не в прозе). — **P2**:
  «Пере-запускает конвертер в build-каталог и сверяет SHA-256 (fail-closed).» Конкретные
  числа — в константах `CONV_SENTENCES_*`, не в docstring.

### suggest_eval.py

- `suggest_eval.py:1` — [JARGON] — «Corpus-stat baseline metrics for TT-SUGGESTIONS over the
  pinned Tatar eval set.» + метрики. — если файл не удаляется: **P2** сжать шапку. Иначе см.
  таблицу удаления.

### Emoji-паковщики (emoji_pack / emoji_search_pack / emoji_skin_pack / emoji_suggest_pack)

Эти docstring'и **самые адекватные** в области (описывают формат и вход/выход), но несут
общий boilerplate. Конкретные точки:

- `emoji_pack.py:4`, `emoji_search_pack.py:4`, `emoji_skin_pack.py:4`, `emoji_suggest_pack.py:4`
  — [DUP] — «The tool uses only the Python standard library.» — повтор в 9 файлах (см. DUP-2).
- `emoji_pack.py:7`, `emoji_search_pack.py:10`, `emoji_skin_pack.py:9`, `emoji_suggest_pack.py:16`
  — [DUP] — «(data, not code)» — повтор в 6 файлах (DUP-3). — **P3**: убрать как самоочевидное.
- `emoji_pack.py:9–15` — [DUP] — «The generator is fail-closed. It exits with a nonzero status
  and writes no partial asset when:» + список. — повтор дословно в `emoji_skin_pack`,
  `emoji_suggest_pack`, `sentstart_pack`, `typo_pack` (DUP-1). — **P2**: одна строка.
- `emoji_pack.py:18` — [JARGON] — «Set composition (see ``docs/DICTIONARY-E2.md``):» — ссылка
  на архивный док. — **P3**: убрать «(see docs/...)», оставить правило.
- `emoji_skin_pack.py:9–16` — [SLOP] — «Why a second asset instead of a change to
  `emoji_pack.py`: the panel asset is frozen … That decision stands» — обоснование решения на
  8 строк. — **P2**: «Отдельный ассет: панель режет тон-модификаторы; здесь хранится, какие
  нейтральные ячейки принимают тон и как собрать тонированную форму.»

### sentstart_pack.py

- `sentstart_pack.py:1` — [JARGON] — «(TT-SUGGESTIONS P4; ROADMAP P1 P3b)» — три кода. — **P3**:
  убрать скобку.
- `sentstart_pack.py:19–24` — [SLOP] — «deliberately the stricter choice over the Leipzig
  `*-words.txt` lists: … a Leipzig-only form would be an uncompletable, unrankable stranger to
  the engine» — риторика. — **P2**: «Фильтр — сам словарь (строже, чем Leipzig-списки): таблица
  не предложит слова, которого движок не знает.»

### schema2_equivalence_check.py / schema3_equivalence_check.py

- `schema2_equivalence_check.py:1`, `schema3_equivalence_check.py:1` — [JARGON] — «(SIZE-1)» /
  «(SIZE-2)» — коды. — **P3**: убрать коды, суть оставить (docstring'и здесь по делу).
- `schema3_equivalence_check.py:6` — [AGENT] — «для КАЖДОЙ головы (10 204 tat + 9 998 rus)» —
  захардкоженные счётчики в прозе (устаревают при перепаковке). — **P3**: «для каждой головы».

### dictionary_coverage.py

- Docstring 1–7 короткий и по делу; **оставить**. Мелочь: `dictionary_coverage.py:4` двойной
  пробел «library.  It» — [WEIRD] — **P3**: одиночный пробел.

### wordform_kaikki_check.py

- `wordform_kaikki_check.py:1` — если не удаляется: docstring по делу (dev-tool, сеть,
  лицензия). [JARGON]-мелочь: «the P1 generator» (строка 19). — **P3** либо удалить файл.

### dict_accept_check.py

- Docstring RU 1–~14; [SLOP] — «Мусор, который оператор называл лично (`щрн`, `нб`, `фп`,
  `бш`, `ме`)» — прямая речь оператора + перечень. — **P2**: «Проверяет, не попал ли в словарь
  формальный мусор и поимённо исключённые слова; печатает JSON.»

### Shell-скрипты

- `emulator-smoke.sh:11–26` — [OVERLONG][AGENT][JARGON] — «TT-SUGGESTIONS P5 … (2026-09-29:
  the strip is THREE cells again — the 2026-09-27 four-cell wave reverted; сакчы stays
  replaced by сәләм — the 2026-09-23 EXPAND-1 extra heads…)» — история волн 3→4→3 ячеек с
  тремя датами и кодами. Факт (3 ячейки) верен, но обёрнут в хронику. — **P1**: «На tt-раскладке
  два зонда словоформ (татар, сәләм): набрать слово+пробел, тапнуть ячейку подсказки, прочитать
  поле. Полоса — 3 ячейки.» (`emulator-smoke.sh` несёт 29 дат `2026-…` — рекордсмен.)
- `emulator-smoke.sh:54–63` — [AGENT] — «Деструктивность записи префов (C4 аудита 2026-09-02):
  … До 2026-09-02 файл затирался ЦЕЛИКОМ…» — код аудита + история фикса. — **P2**: оставить
  предупреждение «правка префов идёт в обход API, запускать на выделенных AVD», историю убрать.
- `emulator-smoke.sh:77–79` — [AGENT] — «(флаки type-en-hi на release APK, 2026-08-31)» — дата
  + история флака. — **P1**: убрать скобку.
- `release_check.sh:39–41` — [AGENT] — «SHA-256 релизного сертификата … зафиксирован в
  docs/APK-AUDIT-1.9.5.md, раздел «Подпись».» — ссылка на док как обоснование. — **P3**:
  «SHA-256 релизного сертификата (CN=Tatar Keyboard).»
- `release_check.sh:1` — [JARGON] — «DEV-PLAN п.6: релизный автомат — механическая половина
  docs/PUBLISH-CHECKLIST.md одной командой.» — код + ссылка. — **P2**: «Релизный автомат:
  гейты + артефактные проверки одной командой, итог машинным блоком.»
- `release_pack.sh:1,5` — [JARGON][AGENT] — «SIZE-3: … (docs/SIZE-OPTIMIZATION-RESEARCH.md…)»
  и «O2 (2026-09-25, docs/OPTIMIZE-2026-09-25.md):» — коды + даты + ссылки (8 дат, 5 ссылок). —
  **P2**: оставить техническое «zipalign ДО подписи; arsc deflate ДО zipalign», убрать
  коды/даты/ссылки.
- `release_pack.sh:35` — [JARGON] — «T8 (стадия D, docs/ROADMAP-P8-PLAN.md): --no-sign …» —
  код T8. — **P1**: «`--no-sign` доводит до выровненного НЕподписанного APK (для CI без
  keystore).»
- `device-perf-ritual.sh:1–3` — [JARGON] — «O2/O4 of docs/OPTIMIZE-SECURITY-PLAN-2026-09-29.md,
  feeding docs/PERF-BUDGETS.md» — коды + 2 ссылки. — **P2**: убрать коды/ссылки, оставить
  «перф-ритуал на реальном устройстве (POCO C71, 720×1640)».
- `device-perf-ritual.sh:33–39` — [OVERLONG][AGENT] — «on Android 15 FrameCompleted is column
  17, not 14 as in the pre-FrameTimeline format the 2026-09-04 cold-start script assumed (it
  silently read SyncStart — ~30 ms earlier … 26.6–32.7 ms across the five 2026-09-29 runs)» —
  расследование с числами и датами. — **P2**: «Колонки framestats ищутся по имени; на Android
  15 FrameCompleted — столбец 17.» (техническую суть оставить, историю измерений убрать.)
- `device-netstats-proof.sh:1–2` — [JARGON] — «(S7 of docs/OPTIMIZE-SECURITY-PLAN-2026-09-29.md,
  NETWORK row of docs/THREAT-MODEL.md)» — коды + ссылки. — **P2**: убрать, оставить «повторяемое
  доказательство отсутствия сетевого трафика в рантайме».
- `device-netstats-proof.sh:35–43` — [OVERLONG][AGENT] — «(three cells again since 2026-09-29 —
  the four-cell wave reverted; thirds → centres x=120/360/600 …)» — та же хроника ячеек, что и
  в emulator-smoke, продублирована. — **P1/DUP**: оставить только текущие координаты 3 ячеек.
- `check-no-internet.sh:1` — [JARGON] — «PERF-04 + E2b-3: verify two properties…» — коды. —
  **P3**: «Проверяет на СОБРАННОМ APK: нет INTERNET; backup закрыт по whitelist.»
- `check-no-internet.sh:11–13` — [AGENT] — «The legacy android:fullBackupContent edition was
  dead … removed in phase 3a; its reappearance is an error.» — «phase 3a» + история. — **P2**:
  оставить «dataExtractionRules обязателен: закрывает device-transfer на Android 12+», убрать
  «phase 3a».
- `build-tools-pin.sh:1` — [JARGON] — «B8 (2026-09-28): single source of truth…» — код + дата. —
  **P3**: «Единый источник пиннованной версии build-tools.»

### Данные (*.txt / *.tsv / *.json) — только шапки

- `bigram_extra_heads_tat.txt:1–20` — [OVERLONG][AGENT][JARGON] — «Список собран правилом… До
  2026-08-31 правило IMPERATIVE-HEADS покрывало ранги [10 000, 15 000)… Миссия «разговорный
  корпус, часть B» (docs/CORPUS-CONVERSATIONAL-TT.md) расширила правило…» — 12 строк истории
  правила с датами, диапазонами, ссылками. — **P2**: оставить 3 строки «что это (головы вне
  ранга H), почему (императивы молчат), правило (4 условия)», историю миссий убрать.
- `bigram_extra_heads_conv.py:1–20` — [AGENT][JARGON] — «Rule EXPAND-1 (written 2026-09-23 in
  docs/ROADMAP-P4.md…)» + «an earlier draft of this script ranked by … — that was a bug». —
  **P2**: правило EXPAND-1 в 3 строки; историю бага и дату убрать.
- `emoji_suggest_data.tsv:1–6` — [JARGON] — «подсказок (миссия 1, docs/EMOJI-SUGGEST-PLAN.md).
  Формат строки: …» — «миссия 1» + ссылка. — **P3**: «Таблица слово→эмодзи для подсказок.
  Формат: эмодзи<TAB>язык(ru|tt)<TAB>слово.»
- `wordform_exceptions_tat.tsv:1–13` — шапка по делу (формат, fail-closed, пин). [AGENT]-мелочь:
  «pinned by SHA-256 in tests/wordform_gen/test_wordform_gen.py» — ⚠ этот TSV **действительно
  пинится** SHA-256 тестом, но пинится *содержимое данных*, а не строки шапки-комментария;
  правка комментария после `#` меняет байты файла → **сломает пин**. — **P3, но с пометкой**:
  трогать шапку `wordform_exceptions_tat.tsv` только с пересчётом пина (или не трогать).
- `known_asset_drift.json:*` — [OVERLONG][AGENT][JARGON] — поля `reason` — это абзацы-эссе с
  датами и кодами: «Перепаковка 2026-09-23 (ROADMAP-P4 batch A…): K 4→3 по решению T7 и +3 102
  extra-головы правилом EXPAND-1 по решению P5a(b)…». — это *данные* (не комментарий), их читает
  `rebuild_assets --check`; менять текст `reason` безопасно по формату, но он захламлён. — **P2**:
  сократить `reason` до сути расхождения без кодов решений (T7/P5a(b)) и дат.
- `emoji_search_tt_extra.txt` — шапки нет проблемной (проверено начало). Данные нужны
  (`emoji_search_pack.py --tt-extra`). **Оставить.**

---

## Системные паттерны

Повторяющиеся проблемы (grep-маркеры для массовой чистки) и общее правило.

1. **Датированные сноски в комментариях** — [AGENT]. 95 совпадений.
   `grep -rnE '2026-[0-9]{2}-[0-9]{2}' scripts/*.py scripts/*.sh`
   Хуже всех: `emulator-smoke.sh` (29), `rebuild_assets.py` (12), `release_check.sh` (12),
   `release_pack.sh` (8), `device-perf-ritual.sh` (7).
   **Правило:** дату из комментария убрать; «когда» живёт в git-истории. Оставлять дату
   только если она — часть настоящего технического инварианта (напр. фиксированный SHA-256
   сертификата), но и тогда без «зафиксирован в docs/…».

2. **Коды миссий без расшифровки** — [JARGON]. 127 совпадений.
   `grep -rnE '\b(SIZE-[0-9]|E[0-9][a-z]?|P[0-9]|Phase [A-Z]|phase [0-9]|DEV-[0-9]|TT-[A-Z]|ROADMAP-P[0-9]|O[0-9]|T[0-9]|S[0-9]|B[0-9]|D1[a-z]|EXPAND-[0-9])\b' scripts/`
   Встречаются как заголовки docstring'ов (E5a/E5b, P7-1, SIZE-1/2/3, TT-SUGGESTIONS Px).
   **Правило:** код миссии из комментария/docstring убрать; заменять человекочитаемым
   назначением. Код нужен только в git-сообщении/архивном доке.

3. **Ссылки `docs/*.md` как обоснование** — [JARGON]. 53 совпадения.
   `grep -rnE 'docs/[A-Za-z0-9/_-]+\.md' scripts/`
   Доки планируется архивировать → ссылки станут битыми. Хуже всех: `rebuild_assets.py` (10),
   `typo_pack.py` (7), `bigram_asset_pack.py`/`make_eval_set.py`/`release_pack.sh` (по 5).
   **Правило:** ссылку на док из комментария удалить; если правило важно — изложить его прямо
   в 1–2 строки, а не отсылать в архив.

4. **DUP-1: «fail-closed. It exits … writes no partial …» + список** — [DUP][SLOP]. 40
   упоминаний `fail-closed`. Дословный блок-перечень в `emoji_pack.py:9`, `emoji_skin_pack.py`,
   `emoji_suggest_pack.py`, `sentstart_pack.py`, `typo_pack.py:34`, `bigram_asset_pack.py:36`.
   **Правило:** одна строка «Падает без записи при невалидном входе/несовпадении пинов.»
   Слово «fail-closed» — только там, где это неочевидно; не как мантра.

5. **DUP-2/DUP-3: boilerplate шапки** — [DUP].
   «The tool uses only the Python standard library.» — 9 файлов
   (`grep -n 'only the Python standard library' scripts/*.py`).
   «(data, not code)» — 6 файлов (`grep -n 'data, not code' scripts/*.py`).
   **Правило:** «stdlib-only» упомянуть один раз в `AGENTS.md`/README конвейера, из docstring'ов
   убрать; «(data, not code)» удалить как самоочевидное.

6. **Прямая речь / решения оператора** — [AGENT][SLOP]. 39 «operator/оператор».
   `grep -rniE '\b(operator|оператор|handoff|uncommitted)\b' scripts/`
   Примеры: `dict_accept.py:15` «оператор сказал это прямо», `review_batches.py` «the operator's
   own act, personally and by name», `dict_accept.py:2` «Оператор посмотрел сто … слов».
   Плюс `bigram_pack.py:29` «agents have no network».
   **Правило:** описывать поведение кода, а не кто и когда что решил. «оператор» → нейтральное
   «вручную/человеком» либо удалить.

7. **Риторика/самолюбование** — [SLOP].
   Маркеры: «Nobody reads that … and nobody ever will» (`review_batches.py:2`,
   `dict_accept.py`), «there is nothing to buy with it» (`dictionary_pack.py:45`), «This is the
   real, byte-exact artifact generator» (`bigram_asset_pack.py:11`), «позорит клавиатуру у
   живого человека» (`dict_accept.py:29`), «PROVEN equal … not a convenient approximation».
   **Правило:** убрать эмоциональные/оправдательные обороты; docstring описывает, а не убеждает.

8. **Захардкоженные счётчики/SHA/размеры в прозе** — [AGENT][STALE-риск].
   `make_eval_set.py:9` «218 552 rows, 12 619 164 bytes, SHA `8420ec0a…`», `schema3_…:6`
   «10 204 tat + 9 998 rus», `known_asset_drift.json` числа в `reason`, `review_batches.py:2`
   «35 444 и 3 734». Такие числа устаревают при любой пересборке.
   **Правило:** числа-пины держать в коде-константах (они и так есть, напр. `CONV_SENTENCES_*`),
   а в прозе не дублировать; в `reason`-полях данных — минимум.

9. **[OVERLONG] модульные docstring'и** — 40–74 строки.
   Кандидаты на сжатие до 3–8 строк: `bigram_asset_pack.py` (71), `rebuild_assets.py` (74),
   `glide_pack.py` (74), `wordform_gen.py` (63)*, `dict_accept.py` (61), `make_eval_set.py`
   (57), `typo_pack.py` (43). *У `wordform_gen.py` лингвистическое ядро оставить — оно по делу.
   **Правило:** docstring = «что делает / вход / выход / когда падает» в ≤8 строк. Обоснования
   архитектурных решений, историю и примеры — в git/доки.

10. **[LANG] непоследовательность языка комментариев.**
    В одном файле смешаны EN-docstring и RU-инлайн (`dictionary_pack.py`: docstring EN,
    комменты schema-2 RU, стр. 46–52). По области: `bigram_asset_pack/bigram_pack/emoji_*/
    glide_pack/typo_pack/make_eval_set/suggest_eval/sentstart` — EN; `rebuild_assets/
    schema2_/schema3_/dict_accept/dict_accept_check/review_batches` — RU.
    **Правило:** выбрать один язык комментариев для конвейера (проект русскоязычный — логично
    RU) и не смешивать внутри файла. Это большая, но механическая унификация; в рамках чистки
    достаточно убрать смешение внутри отдельных файлов (минимум — `dictionary_pack.py`).

11. **[STALE]-проверки из брифа — результат.**
    - «полоса подсказок = 3 ячейки»: комментарии в `emulator-smoke.sh`, `device-perf-ritual.sh`,
      `device-netstats-proof.sh` говорят «three cells» — **верно**, но обёрнуто в хронику
      «four-cell wave reverted» → чистить как [OVERLONG], не как ложь.
    - «live-превью глайда удалено»: упоминаний «live preview» в scripts нет — **находок нет**.
    - «класс опечаток #5 удалён»: в `typo_pack.py` класс #5 **ещё присутствует** и не покрыт
      тестом/движком → [DEAD], см. таблицу удаления.
    - «версия 3.6.0/43»: в scripts версия нигде не захардкожена (`release_check.sh` читает её из
      `app/build.gradle` динамически) — **находок нет**, это правильно.
