# Ревизия текстов Android-проекта — сводка

Дата: 2026-09-30, HEAD `842fb4ca`. Проект ревизией не менялся, созданы только файлы в `cleaning/`.
Каждый отчёт `01`–`21` написан отдельным агентом по своей области; этот файл — индекс и общий план.

## Диагноз

Код в хорошем состоянии: мёртвых файлов почти нет, ложных утверждений единицы. «Нейрослоп» сосредоточен в трёх местах:

1. **Документация как журнал агентных миссий.** В `docs/` ≈ 46 МБ под git, 79 md-файлов на верхнем уровне. Живых документов из них 3–4 (THREAT-MODEL, PERF-BUDGETS, BACKLOG частично, индекс). Остальное — закрытые планы, отчёты и аудиты, дублирующие git, CHANGELOG и друг друга. HANDOFF.md (155 КБ): живая только верхняя запись (строки 1–45). PUBLISH-CHECKLIST.md (197 КБ): тело описывает 1.8.3.
2. **Комментарии в коде как история правок.** Доля комментариев в собственном коде ≈ 30–45 % (suggestions/ ≈ 45 %). Типовой комментарий — это «код миссии + дата аудита + ссылка на docs/*.md + рассказ о расследовании» вместо одной строки о назначении. Масштаб: сотни кодов миссий (F1–F17, S1–S9, O1–O8, P7-x, E5c, D1b, SIZE-n…), ≈ 350 ссылок на docs/*.md из кода, тестов и скриптов, ≈ 300 датированных сносок.
3. **Агентные артефакты.** «operator», «UNCOMMITTED», «independent verifier verdict: SHIP», таблицы гейтов со счётчиками тестов, SHA и размеры APK в прозе, чужой путь `/home/tarchok/...` (OPTIMIZE-SECURITY-PLAN:242). Плюс риторика: «earned its keep», «centerpiece», «это не обещание, а проверяемое свойство», «fail-closed» не к месту.

## Индекс отчётов

| № | Область | Главное |
|---|---|---|
| 01 | корневые доки, metadata, images, icons | штампы в README/SECURITY/PRIVACY; в PRIVACY.md:30,107 устаревший размер таблицы (135 889 → 170 471); dev-жаргон в changelog'ах стора; 2 побайтных дубля PNG |
| 02 | HANDOFF.md | удалить строки 47–2254 (история); «UNCOMMITTED» у 3.6.0 — ложь; шаблон короткого HANDOFF |
| 03 | CHANGELOG.md | dev-лог, а не changelog; два языка; хэш ключа ×14; предложен Keep a Changelog с примерами |
| 04 | PUBLISH-CHECKLIST, docs/README, CLEANUP | чек-лист 1939 → ~90 строк; ложные «Uncommitted» в индексе; CLEANUP → архив |
| 05 | ROADMAP*, GLIDE-* | 11/13 файлов — закрытые отчёты; GLIDE-LIVE-STRIP4 описывает откатанное |
| 06 | RESTRUCTURE*, DEV-PLAN, BACKLOG, LEFTOVERS, APPLE-UX, ERRORPRONE | APPLE-UX выглядит как план, хотя почти весь уже отгружен |
| 07 | TT-*, NEXTWORD-RACE, CORPUS-*, RUSSIAN-BIGRAMS | ложные «current 1.9.15/2.0.0, uncommitted»; ≈ 8 МБ свидетельств |
| 08 | EMOJI-*, SIZE-*, TABLET, DEVICE-*, release-1.9.12 | 13 закрытых отчётов; SIZE-SCHEMA2+3 → один `ASSET-FORMATS.md` |
| 09 | APK-AUDIT-* (22 шт.) | оставить 3.6.0, остальные → архив; шаблон ≈ 15 строк поверх `release_check.sh` |
| 10 | AUDIT-*, SECURITY-*, OPTIMIZE-*, THREAT-MODEL, PERF-BUDGETS | живые только THREAT-MODEL и PERF-BUDGETS, обе с устаревшими строками («planned S2–S8») |
| 11 | docs/archive (28 МБ) | ≈ 24 МБ png/tsv никем не читаются; блокер — один tsv, который читают 4 JVM-теста |
| 12 | research/ | «свайп-ввод исключён» опровергнуто глайдом; тройное наслоение 01–08 → 00 → BRIEF |
| 13 | keyboard/, compat, event, accessibility | тонкий слой проектных комментариев; 1 STALE (KeyboardActionListener:85) |
| 14 | LatinIME, InputLogic, RichInputConnection, utils | 7 якорных строк комментариев, по которым тесты режут тела методов; мёртвые LAYOUT_ARABIC… |
| 15 | suggestions/, dictionary/engine | комментарии ≈ 38 %; STALE GlideKeyGeometryBuilder:30; неиспользуемые классы опечаток #2/#3 |
| 16 | storage, personal*, glide | STALE AndroidPersonalDictionaryStorage:44 «Dormant»; мёртвый `estimatedFileSize()`; тройные копии комментариев |
| 17 | emoji/, settings/, res/ | UI-строки чистые; комментарии-эссе (Telegram/operator), ≈ 65 ссылок на docs |
| 18 | тесты engine/suggestions/glide | код тестов чистый; 108 датированных сносок; 2 теста невыпущенной политики Phase B |
| 19 | остальные тесты, androidTest, baselineprofile | 2 теста режут RichInputConnection по тексту датированного комментария (блокер) |
| 20 | scripts/ | мёртвый генератор класса #5 в typo_pack.py; docstring'и-отчёты; 2 скрипта без использования |
| 21 | сборка, CI, репо-гигиена | 19 `*.generated.json` в git вопреки .gitignore; startup-prof ≡ baseline-prof; 52-строчный комментарий в lint.xml; 2 STALE в скриптах |

## Проверено мной точечно

- `app/src/main/startup-prof.txt` побайтно совпадает с `baseline-prof.txt` (`cmp`).
- `AndroidPersonalDictionaryStorage.kt:44` — «Dormant in E4a-2: nothing in the live IME constructs this» (устарело).
- `GlideKeyGeometryBuilder.kt:30` — «nothing calls this yet» (устарело).
- В git лежат 19 файлов `docs/**/*.generated.json`; всего под git в `docs/` ≈ 45,8 МБ.

## Разногласия и оговорки

- **Golden-экспортёры (`latin/golden/`).** Отчёт 19 предлагает их удалить, я не согласен: оператор закоммитил их сегодня (`842fb4ca`, `2d494f60`), это мост паритета с iOS-портом. В APK они не попадают и выполняются, только если задана env-переменная. Предлагаю оставить и почистить только комментарии.
- **Правило AGENTS.md** «историю и завершённые отчёты не переписываем» противоречит чистке. Нужно решение оператора: либо правило снимается и история остаётся в git, либо всё закрытое переносится в `docs/archive/` без правок. Большинство отчётов предлагают второй вариант как минимальный.
- **Ссылки из кода на доки.** Около 350 комментариев в коде, тестах и скриптах ссылаются на `docs/*.md`. Архивировать доки без одновременной чистки комментариев — значит получить сотни битых ссылок. Поэтому порядок такой: сначала комментарии, потом перенос доков.
- **Якоря source-contract тестов.** Правка комментариев почти везде безопасна: тесты пинят код, а не прозу. Исключения (детали в 14, 18, 19):
  - `RichInputConnection.java`: «2026-09-25 audit, F1» и «…F10» (делимитеры в `RichInputConnectionRobustnessContractTest:273` и `InputConnectionBinderContractTest:131`), «Do not log the returned value» (`SuggestionStripSourceContractTest`), «Set the selection»;
  - первые строки пяти javadoc в `InputLogic.java`;
  - нельзя вносить в комментарии токены `measureText(`/`HashSet` (KeyboardView), `Log.`/`java.net.` (emoji), `data class` (personalstore).

## Рекомендуемый порядок работ

1. **P1 — ложь и мусор, минимальный риск.** Исправить STALE:
   - код: `KeyboardActionListener:85`, `GlideKeyGeometryBuilder:30`, `AndroidPersonalDictionaryStorage:44`, `InputAttributes:56-60`, `rebuild_assets.py:353`, `bigram_asset_pack.py:2`;
   - доки: `PRIVACY.md:30,107`, «UNCOMMITTED»/«current 1.9.x» в HANDOFF, docs/README и TT-*;
   - AGENTS.md: противоречие 1920/1927 тестов и утверждение, что startup-профиль отличается.

   Убрать чужой путь `/home/tarchok`. Удалить побайтные дубли `images/screenshot-0.png` и `icons/play_feature.png`.
2. **Репо-гигиена.** Сначала вынести из `docs/archive` данные, которые реально читаются кодом: `DICTIONARY-D1A-QUERY-REVIEW.tsv` (4 JVM-теста), `dict-accept/{accepted,conv-freq}-*.tsv` и `DICTIONARY-*-CONV-REVIEW.tsv` (их читает `dict_accept.py`, импортируемый `rebuild_assets.py`), а также `apply_rule_tt.py`. Отчёт 11 ошибочно счёл эти tsv безопасными к удалению. Затем убрать из git `*.generated.json`, png и прочие свидетельства. Детали — в `CLEANUP-PLAN.md`, раздел H3 и WP2.1.
3. **Массовая чистка комментариев** по grep-маркерам из разделов «Системные паттерны» (коды миссий, `docs/*.md`, `20[0-9]{2}-[0-9]{2}-[0-9]{2}`, audit/finding, operator). Правило: одна-две строки о том, что делает код и почему; история остаётся в git. Якоря из оговорок выше править одновременно с тестами.
4. **Мёртвый код:**
   - классы опечаток #2/#3 и генератор #5 в `typo_pack.py`;
   - `LAYOUT_ARABIC…`/`layoutUsesAutoCaps`;
   - `PersonalEntries.estimatedFileSize()`;
   - по решению оператора — тесты Phase B, `suggest_eval.py`, `wordform_kaikki_check.py`.
5. **Документы.** Сократить HANDOFF до одного экрана. Переписать PUBLISH-CHECKLIST (≈ 90 строк) и CHANGELOG (Keep a Changelog, один язык). Закрытые отчёты — в архив или удалить. Слить SIZE-SCHEMA2/3 в `ASSET-FORMATS.md`. Сократить индекс `docs/README.md`. Обновить THREAT-MODEL и PERF-BUDGETS. Research — в архив со сноской про глайд.
6. **Правила на будущее** (в AGENTS.md): без кодов миссий, дат и счётчиков в комментариях; без таблиц гейтов в живых доках; один язык на документ; завершённый отчёт сразу уходит в архив или в git history.
