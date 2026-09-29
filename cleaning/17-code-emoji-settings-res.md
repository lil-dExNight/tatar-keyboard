# Emoji / Settings / res — ревизия текстов

Область: `latin/emoji/` (20 файлов, ~5 350 строк), `latin/settings/` (16 файлов,
~3 860 строк), все XML в `app/src/main/res/`, `AndroidManifest.xml`, текстовые
шапки `app/src/main/assets/`. Всё прочитано (комментарии — построчно через grep,
крупные файлы — глазами; строки tt/ru/en — целиком).

## Итог

Диагноз: **код рабочий, слоп сосредоточен в комментариях**, а не в UI-строках.
Доля комментариев по области ≈ 30 % (≈ 2 750 из ≈ 9 200 строк; в `latin/settings/`
≈ 32 %, в `latin/emoji/` ≈ 27 %). Из них лицензионная шапка Apache — ~14 строк ×
36 файлов ≈ 500 строк (не трогаем), остальное — добавленная проза, и она
перегружена. Главные болезни: **KDoc-простыни-эссе** (класс-доки на 25–45 строк,
где хватает 3–5), **коды миссий без расшифровки** (T2, U6/U7/U8, E2b-3, E4b, M5,
P7-2/3/5/6, F15a, F16, C2/C5/C7, O2/O5, S1/S2, Р-2/Р-3, D6, SETUP-01, T5 и др.),
**65 ссылок на `docs/*.md`** (17 разных файлов — при архивации доков станут
битыми), **датированные сноски** «2026-09-xx» (~36 мест), «operator»/«Telegram
client»/«the moment of victory» (пафос), и **дубли** одного и того же объяснения.
UI-строки (tt/ru/en) — чистые и согласованные; **удалять/переводить в них нечего**,
кроме косметики (кавычки в ru, метка «(T5)»).

Находки по категориям (приблизительно): [OVERLONG] ~40 мест, [JARGON] ~70 (коды +
docs-ссылки), [AGENT] ~40 (даты, «operator», числа памяти, ссылки на тесты),
[SLOP] ~25, [DUP] ~8 кластеров, [LANG] ~6, [STALE] 0 подтверждённых, [DEAD]/[DELETE]
0 целых файлов.

Топ-3 действия:
1. **Обрезать код-миссий и docs-ссылки во всех комментариях** одним проходом
   (grep-маркеры ниже): каждую строку с `docs/...md`, `2026-09-xx`, `U7/T2/F15a/…`
   заменить на назначение по-человечески или удалить.
2. **Свернуть класс-доки** emoji/settings до 2–5 строк «что это и главное правило»;
   вынести историю дефектов/«operator»/«Telegram» — она не про код.
3. **Убрать агентные артефакты**: числа резидентной памяти и «re-measurement
   ritual» (EmojiSuggestIndex), ссылки на имена тестов внутри прод-кода, метку
   «Machine-assisted translation (T5)».

Важно про source-contract тесты (проверено чтением `EmojiRecentAndFlingSourceContractTest`,
`KeyboardHeightPreferenceTest`): **они пинят идентификаторы кода и имена
ресурсов, НЕ прозу комментариев** — чистка комментариев их не роняет. Два
ограничения на переписанный текст (не на удаление): в `.kt` пакета emoji комментарий
не должен содержать токены `Log.`, `println`, `System.out`, `java.net.`
(`theEmojiPackageContainsNoLoggingOrStdoutOrNetwork`); в `SettingsHostActivity.kt`
комментарий не должен содержать `RecentEmojiStore`/`RecentEmojiList`/`noBackupFilesDir`/
`deserialize`/`currentRecents` (`settingsHostActivityNeverReadsTheRecentsContent`).

## Файлы/код на удаление

Целых файлов-кандидатов на удаление в области **нет** — всё живое. Проверенные
подозрения (все опровергнуты grep'ом):

| путь / символ | размер | почему подозревали | кто ссылается (grep) | риск удаления |
|---|---|---|---|---|
| `settings/SettingsActivity.java` | 2 КБ | форвардер, «legacy» | `method.xml` (`android:settingsActivity`), `AndroidManifest.xml`, `LatinIME#launchSettings` | **высокий — не трогать** (экспортируемая точка входа) |
| `R.string.settings_screen_theme` | — | «Тема», экрана темы вроде нет | `res/xml/app_restrictions.xml:91` (title рестрикции) | высокий — используется |
| `R.string.settings_screen_appearance` | — | — | `SettingsHostActivity.kt:102,380` | высокий — используется |
| `R.string.abbreviation_unit_dp` | — | — | `SettingsKeyPressScreen.kt:135` | высокий — используется |
| `R.string.keyboard_theme_tatar` | — | единственная тема | `values/keyboard-themes.xml:26` | высокий — используется |
| `drawable/ios_key_*.xml`, `ios_popup_*` | — | «iOS» в Android-only проекте | тема/стили (design-токены «iOS-style») | высокий — это визуальный стиль, не платформа |

Вывод: **удалять нечего**; вся работа — правка текста комментариев/XML-комментариев.

## Правки

Приоритеты: **P1** — агентные артефакты, коды миссий, битые docs-ссылки, пафос;
**P2** — сильное упрощение простыней; **P3** — косметика. Ниже сгруппировано по
файлам; повторяющееся свёрнуто в паттерны (см. последний раздел).

### latin/emoji/

**EmojiPanelView.kt**
- `EmojiPanelView.kt:44-79` — [OVERLONG][SLOP] класс-док на 36 строк: «The arrangement
  is the one the operator asked for, copied from the Telegram client… since 2026-09-28
  (docs/EMOJI-PANEL-SPACE-2026-09-28.md, item A)… moved verbatim in the T2 split
  (docs/ROADMAP-P6.md)». Проблема: история миссий + «operator» + docs-ссылки вместо
  сути. Замена (P1): «Панель эмодзи — Canvas-поверхность, заменяющая клавиатуру, пока
  открыта. Шрифта эмодзи нет: невидимые на устройстве символы отсеяны глиф-пробой при
  сборке снимка. Геометрия и хит-тест — в чистом [EmojiPanelState]; рисование без
  аллокаций. Вставка идёт через listener в LatinIME; текст поля не читается.»
- `EmojiPanelView.kt:71-79` — [LANG][DUP][JARGON] блок-комментарий «Р-3: размеры текста
  … При font_scale 2.0 полоса подсказок вырождалась в «Мини… · Минем · Мини…» …
  (docs/DEVICE-RESEARCH-GEOMETRY.md, Р-3)». Тот же текст продублирован в
  `EmojiSearchView.kt:93-99`. Замена (P2): «Размеры клавиатурных текстов — в dp, не
  sp: системный масштаб шрифта не должен ломать полосы фиксированной высоты.»
- `EmojiPanelView.kt:63-66,80` — [JARGON] «internal, not private: … (T2 part 3,
  docs/ROADMAP-P6.md). The rest stay private.» → «internal (не private): читается
  расширениями-художниками из EmojiPanelDrawing.kt/EmojiPanelGestures.kt.»

**EmojiPanelState.kt**
- `EmojiPanelState.kt:21-47` — [OVERLONG][DUP] класс-док повторяет ту же
  «Telegram/operator/2026-09-28» историю, что и EmojiPanelView. Замена (P2): «Чистая
  (Android-free) геометрия и жесты панели: ряд вкладок + ячейка поиска сверху, единый
  скролл по секциям, две плавающие клавиши (АБВ/удаление). Индексы ячеек — сквозные по
  всему набору, при скролле не сдвигаются. Тестируется на JVM без устройства.»
- `EmojiPanelState.kt:181-194` — [OVERLONG][JARGON] «At "Keyboard height 50 %" the
  bands ate almost everything … (docs/DEVICE-RESEARCH-GEOMETRY.md, Р-2). … collapsed
  into a tab-row cell on 2026-09-28 …». Замена (P2): «Полоса вкладок ужимается только
  когда контенту не хватает места, но не ниже [MIN_BAND_SCALE]; при полной высоте
  коэффициент = 1.»
- `EmojiPanelState.kt:222-242` — [OVERLONG][JARGON] длинная история про Android 15 и
  «defect Д-1, docs/DEVICE-UAT-1.9.12.md». Замена (P2): оставить 2 строки о смысле
  `bottomInsetPx` (панель не должна залезать под навбар), убрать номер дефекта/док.
- `EmojiPanelState.kt:768-778` — [JARGON] «(Р-2)» и рассуждение про «one row plus a
  header». Сжать до «Пол, ниже которого полоса вкладок перестаёт быть нажимаемой».

**EmojiPanelController.kt**
- `EmojiPanelController.kt:184-187,300-308` — [JARGON][AGENT] «(O2: the idle memory
  release …)» / «O2 (docs/OPTIMIZE-2026-09-25.md): the idle memory release …». Убрать
  код-миссии и док-ссылку, оставить смысл: «При освобождении памяти (MSG_DEALLOCATE_MEMORY)
  живой индекс сбрасывается; следующий поиск перечитает его. Вердикт EMPTY сохраняется.»
- `EmojiPanelController.kt:607-612` — [JARGON] «[SharedEmojiSearchIndex], audit
  2026-09-02 C7» → «процессно-общий индекс: панель и подсказки читают один ассет.»
- Прочие доки в файле разумны по существу, но верны и без «(O2/C7)» — вычистить коды.

**EmojiSuggestIndex.kt**
- `EmojiSuggestIndex.kt:89-98` — [AGENT][OVERLONG] самый яркий пример: «Hard cap …
  (2026-09-25 audit, F16, mirrors SentStartIndex)… Resident cost is ~140 B/record
  (557 KB @ 3 976 records, docs/ROADMAP-P8.md C2)… Raised 4096 -> 8192 on 2026-09-28
  (backlog B3)… Re-measurement ritual: …». Проблема: аудит-код, числа памяти, дата,
  «ритуал перемера» — чистый агентный лог. Замена (P1): «Верхняя граница числа записей:
  защита от разросшегося битого ассета (совпадает с MAX_LINES=8192 в
  emoji_suggest_pack.py, чтобы валидную таблицу нельзя было обрезать здесь).»
- `EmojiSuggestIndex.kt:22-25,126-131` — [JARGON] «mission 1 of docs/EMOJI-SUGGEST-PLAN.md»,
  «C2 of docs/ROADMAP-P8-PLAN.md» → убрать ссылки; «(язык, слово) → эмодзи, курируется
  вручную, ru/tt» и «парсит и фильтрует за один проход» достаточно.

**SharedEmojiSearchIndex.kt**
- `SharedEmojiSearchIndex.kt:22-38` — [AGENT][JARGON] «Audit 2026-09-02, C7: … Before
  this … kept their own instance alive. … (O2: the idle memory release …)». Замена (P1):
  «Одна разобранная копия emoji_search_v1.txt на процесс: панель-поиск и озвучка имён
  для TalkBack читают её через общий [get] (synchronized, парсит максимум раз).»
- `SharedEmojiSearchIndex.kt:57-62,105-111` — [JARGON] дважды «O2 (docs/OPTIMIZE-2026-09-25.md)».
  Убрать код+док, оставить «Сбрасывает копию при освобождении памяти; следующий [get]
  перечитает ассет.»

**EmojiSuggestSources.kt**
- `EmojiSuggestSources.kt:24-25,73-94` — [JARGON] «mission 2 of docs/EMOJI-SUGGEST-PLAN.md»,
  «C2 (docs/ROADMAP-P8-PLAN.md)», «(audit C7)». Убрать коды/док-ссылки.

**EmojiSearchIndex.kt**
- `EmojiSearchIndex.kt:116-119` — [JARGON] «(mission 2 of docs/EMOJI-SUGGEST-PLAN.md)»
  в KDoc `nameOf` → удалить хвост про миссию.

**EmojiSearchView.kt**
- `EmojiSearchView.kt:42-56` — [SLOP][JARGON] «Why this shape. In the Telegram client
  the search field summons the system keyboard; here the keyboard IS this application…
  The same trade is what Gboard makes… Since 2026-09-28 (docs/EMOJI-PANEL-SPACE-2026-09-28.md,
  item A)…». Замена (P2): «Поверхность поиска эмодзи: две полосы вместо полосы подсказок,
  под ними видна буквенная клавиатура. Запрос живёт в чистом [EmojiSearchQuery] и в поле
  не попадает; совпадения — [EmojiSearchIndex]; выбор идёт через listener в LatinIME.»
- `EmojiSearchView.kt:93-99` — [DUP][LANG] дубль блока «Р-3» из EmojiPanelView (см. выше).

**EmojiSearchLayout.kt**
- `EmojiSearchLayout.kt:24-27` — [SLOP][AGENT] «Every rule here comes from a defect the
  operator found on a real phone. In 1.6.0: the caret had a key's constant added… In
  1.6.1: a field holding nothing but spaces…». Замена (P2): «Хрупкие правила геометрии/
  состояния поиска, вынесенные сюда для JVM-тестов: позиция каретки, когда поле считается
  запросом, когда показывать полосу результатов.» (истории версий убрать).

**EmojiPanelDrawing.kt / EmojiPanelGestures.kt**
- `EmojiPanelDrawing.kt:20-34` и `EmojiPanelGestures.kt:18-24` — [JARGON][OVERLONG]
  «(T2 part 3, docs/ROADMAP-P6.md) … moved verbatim … the source contracts slice
  EmojiPanelView.kt between … parts 1–2 record the same move». Замена (P1): «Художники
  панели (ряд вкладок с ячейкой поиска, плавающие клавиши, попап тонов, часы) /
  жест-хелперы — internal-расширения [EmojiPanelView], читают геометрию [EmojiPanelState].»
- `EmojiPanelDrawing.kt:25-26` — [AGENT] «(The search pill painter was retired on
  2026-09-28 — docs/…)» → удалить (описывает историю, не текущий код).

**RecentEmojiList.kt / RecentEmojiStore.kt**
- `RecentEmojiList.kt:22` — [JARGON] «Semantics (E2b-3):» → «Правила:».
- `RecentEmojiStore.kt:189-193` — [AGENT] «Fail-closed read cap (2026-09-24 audit,
  finding 11):» → убрать «(2026-09-24 audit, finding 11)».
- `RecentEmojiStore.kt` — [SLOP] «gate» повторяется как термин (RecentEmojiGate/
  GateState); допустимо как имя типа, но в прозе «the three-factor gate» можно заменить
  на «три условия записи». P3.

### latin/settings/

**SettingsHostActivity.kt** (1 452 строки, 397 комментов — крупнейший файл)
- `SettingsHostActivity.kt:52-93` — [OVERLONG][JARGON] класс-док на 42 строки:
  «View-based settings screens (IOS-REDESIGN.md S1 + S2)… E2b-3 turns backup off…
  T2 part 3 (docs/ROADMAP-P6.md) split the file… sixteen source-contract tests pin
  their exact call text to this file». Замена (P1, до ~8 строк): «Экраны настроек на
  обычных View (без androidx.preference): один Activity меняет страницы в одном
  scaffold с ручным back-stack. Переносит из старого стека: device-protected prefs,
  рестрикции работодателя (гасят строки), зависимости строк, живой ребилд клавиатуры
  на смену темы/числового ряда. LANGUAGE_DETAIL — единственный экран с параметром
  (локаль в detailLocale).» Ссылку на «sixteen source-contract tests» убрать —
  требование «не менять текст вызовов» относится к коду, не к докам.
- `SettingsHostActivity.kt:87-92,96-97,120-121,1253-1254,1272-1276,1304-1308` —
  [JARGON][AGENT] повторяющиеся «(T2 part 3, docs/ROADMAP-P6.md)» + ссылки на имена
  тестов («EmojiRecentAndFlingSourceContractTest pins…», «KeyboardHeightPreferenceTest
  pins its text to this file»). Замена (P1): оставить одну строку «moved to
  SettingsRows.kt / SettingsLanguagesScreens.kt / SettingsKeyPressScreen.kt», убрать
  коды и упоминания тестов.
- `SettingsHostActivity.kt:328-338` — [JARGON] «M5 of docs/APPLE-UX-2026-09-25.md: iOS
  slides a pushed settings screen…». Замена (P3): «Анимация перехода между экранами:
  push уезжает вправо, pop — влево; уважает системный масштаб анимаций (0 = без
  движения).»
- `SettingsHostActivity.kt:387-402` — [OVERLONG] «Data sources» — 15 строк философии
  про CC BY и «half a person can actually reach without unpacking an APK». Сжать до
  3 строк (P2): «Экран источников данных: по строке на коллекцию. Существует ради
  условий лицензий (CC BY требует называть источник; OpenSubtitles — ссылку). Полные
  тексты — в NOTICE.txt рядом с ассетами.»
- `SettingsHostActivity.kt:455-456` — [JARGON][STALE-риск] «Deferred (docs/AUDIT-2026-09-24.md,
  L4): rows disabled while this switch is off swallow taps silently; they should answer
  with a short toast instead.» Это TODO-«следует бы», при этом ниже (F15a) уже сделано
  soft-disabled с пояснением. Проверить, не выполнено ли уже; если да — [STALE], удалить.
- `SettingsHostActivity.kt:470-511` — [JARGON] блок про подчинение переключателей:
  «P7-6», «U8 (docs/ROADMAP-P2.md)», «Autocorrection (D3)», «mission 2 of
  docs/EMOJI-SUGGEST-PLAN.md», «M4b». Существо (что чему подчинено и почему) полезно,
  но коды/док-ссылки убрать. P2.
- `SettingsHostActivity.kt:714-731` — [SLOP][OVERLONG] карточка-карантин: «That second
  sentence is not decoration. Handing back two thirds of someone's words under the word
  "restored" is the one outcome this feature must never produce…». Красиво, но это эссе.
  Сжать до 3–4 строк о поведении (P2).
- `SettingsHostActivity.kt:1435-1443` — [SLOP] «The log line alone was the whole answer
  before… "Privacy Policy" being a row that does nothing is the worst row to lose
  quietly.» → «Открывает ссылку; если открыть нечем — показывает Toast (лог-строка
  остаётся для разработчика).» P2.

**Settings.java**
- `Settings.java:62-134` — [JARGON] серия KDoc с кодами: «U6 of Phase 5,
  docs/ROADMAP-P5.md», «docs/EMOJI-PANEL-SPACE-2026-09-28.md, item B», «(docs/GLIDE-PLAN.md).
  The settings UI row lands with P7-3; the read path exists from P7-2», «(U8 of Phase 2,
  docs/ROADMAP-P2.md)», «(M4b …)». Существо (значения по умолчанию, подчинённость)
  полезно — убрать коды/док-ссылки. P1/P2.
- `Settings.java:248-256` — [LANG] русские комментарии посреди англоязычного файла
  («getString даёт null, если ограничение задано значением другого типа…»). Не ошибка,
  но несогласованно с остальным файлом; привести к одному языку. P3.
- `Settings.java:399-403` — [AGENT] «(P7-6, docs/ROADMAP-P7.md — the 2026-09-24 field
  report: with the master off the gesture died on the gate…)». Убрать код+дату+док. P1.

**SettingsRows.kt**
- `SettingsRows.kt:28-40` — [JARGON] «(T2 part 3, docs/ROADMAP-P6.md) … the
  source-contract tests pin that text to SettingsHostActivity.kt (e.g.
  switchRow(Settings.PREF_SHOW_EMOJI_KEY, true)) … parts 1–2 record». Сжать (P1):
  «Строители строк настроек (link/text/action/switch/value) + каркас карточек —
  internal-расширения [SettingsHostActivity].»
- `SettingsRows.kt:221-229,249-251,264-267` — [JARGON] «F15(a) of
  docs/AUDIT-2026-09-24-FIXES.md, closed in stage C of docs/ROADMAP-P8-PLAN.md», «F15a».
  Существо (soft-disabled строка объясняет себя тапом) хорошее — убрать коды/док. P2.

**PersonalBigramScreenController.kt / PersonalEmojiScreenController.kt / PersonalDictionaryScreenController.kt**
- [DUP][JARGON] три класс-дока почти дословно совпадают (см. `PersonalBigramScreenController.kt:29-43`,
  `PersonalEmojiScreenController.kt:29-43`, `PersonalDictionaryScreenController.kt:31-46`):
  один и тот же абзац про «process-wide owner… single personal-store worker… delivers on
  the UI thread… queueing an event and repainting in the next statement made the screen
  report an outcome it could not know yet». Замена (P2): в первом (words) оставить полное
  объяснение без «(U7 of Phase 2, docs/ROADMAP-P2.md)»; в bigram/emoji — «Зеркало
  [PersonalDictionaryScreenController] для пар / выученных эмодзи.» и не повторять абзац.
- Мелкие: `…Controller.kt:96-97` во всех трёх — идентичный коммент «All outcomes arrive
  on the one store worker…» (DUP, оставить один осмысленный).

**KeyboardHeightPresets.kt / EmojiPanelHeightPresets.kt**
- `KeyboardHeightPresets.kt:19-32` — [JARGON] «U6 of Phase 5 (docs/ROADMAP-P5.md): …
  (SettingsValues.mKeyboardHeightScale → ResourceUtils.getKeyboardHeight → …)». Сжать до
  сути (P2): «Три пресета высоты клавиатуры, хранятся в том же float pref_keyboard_height,
  что писал старый ползунок; значение вне пресетов применяется как есть и показывается
  процентом.»
- `EmojiPanelHeightPresets.kt:22-34` — [JARGON] «(docs/EMOJI-PANEL-SPACE-2026-09-28.md,
  item B)». Убрать док-ссылку; остальное по делу.

**SettingsValues.java**
- `SettingsValues.java:53,57,59,70,74-77` — [JARGON] «(P7-2)», «(D3)», «(emoji-suggest)»,
  «U6 of Phase 5, docs/ROADMAP-P5.md», «docs/EMOJI-PANEL-SPACE-2026-09-28.md, item B».
  Убрать коды/док, оставить «подчинено mTatarSuggestionsEnabled». P2.

**SeekBarDialogHelper.kt / SettingsLanguagesScreens.kt / SettingsActivity.java / SettingsKeyPressScreen.kt**
- `SeekBarDialogHelper.kt:26-32,94` — [JARGON] «SeekBarDialogPreference (removed in S2)»,
  «(audit 2026-09-02, C5)». Убрать «S2»/«audit …, C5». P3.
- `SettingsLanguagesScreens.kt:30-38,76` — [JARGON] «(T2 part 3, docs/ROADMAP-P6.md)»,
  «(2026-09-24 audit, finding 15b)». Убрать коды/дату. P2.
- `SettingsActivity.java:25-35` — [JARGON] «removed in S2 (IOS-REDESIGN.md)». Оставить
  «legacy PreferenceActivity удалён»; убрать «S2/IOS-REDESIGN.md». P3.
- `SettingsKeyPressScreen.kt:23-33` — [JARGON] «(T2 part 3, docs/ROADMAP-P6.md) … pinned
  to the activity by KeyboardHeightPreferenceTest's neighbours». Убрать код + упоминание
  теста. P2.

**PersonalDictionaryScreenModel.kt**
- `PersonalDictionaryScreenModel.kt:104-111` — [AGENT][JARGON] «RecyclerView is not
  available (since O2 of 2026-09-25 the app carries no androidx dependency at all, and
  adding recyclerview costs on the order of a hundred kilobytes against a phase budget of
  25 600 B)». Замена (P2): «Ограничитель — колпак на число строк: экран строится
  императивно в ScrollView без переиспользования view и целиком пересобирается в onStart,
  поэтому список не должен расти без предела.» (число байт бюджета и «O2 of 2026-09-25»
  убрать).

### app/src/main/res/ (XML-комментарии и строки)

- `values-tt/strings.xml:20` и `values-tt/strings-setup.xml:16` — [AGENT] «<!-- Machine-
  assisted translation; awaiting native-speaker proofread (T5) -->». Проблема: метка
  миссии «(T5)» + внутренняя заметка о процессе. Замена (P2): либо удалить, либо
  «Требуется вычитка носителем» без «(T5)». (Информация полезна редактору переводов —
  оставить смысл, убрать код.)
- `values/strings.xml:86` — [JARGON] «<!-- U6: the three named height presets… -->» →
  «Три пресета высоты клавиатуры».
- `values/strings.xml:90` — [JARGON] «<!-- The "Emoji panel height" row and its three
  presets (docs/EMOJI-PANEL-SPACE-2026-09-28.md) -->» → убрать док-ссылку.
- `values/strings.xml` — [SLOP] редакторские хвосты в комментариях к строкам:
  `:` перед `personal_dictionary_save_failed` «Names no file, no code and no cause: none
  of that is anything the user could act on», `personal_dictionary_erase_action` «Names
  the action rather than saying OK: it is destructive and irreversible», quarantine_partial
  «a partial recovery presented as a whole one is the outcome this feature must never
  produce». Это оправдания дизайна в комментах к строкам — сжать до пояснения строки. P3.
- `layout/setup_activity.xml:16-21` — [JARGON] «Two-step onboarding (SETUP-01)… iOS
  grouped-table restyle (IOS-REDESIGN.md D6)… design tokens (wave D)». Убрать коды
  миссий; «View ids are load-bearing (SetupActivity.kt) — do not rename» полезно, оставить.
- `layout/setup_activity.xml:187-190` — [SLOP] «Try-it field in its own card. The moment
  of victory — the freshly selected keyboard opens right here.» Замена (P3): «Поле
  «попробовать»: здесь открывается только что выбранная клавиатура. Hint — заодно
  TalkBack-метка поля.»
- `layout/settings_screen.xml:17`, `layout/row_switch.xml:17`, `layout/row_link.xml`,
  `layout/row_value.xml`, `layout/row_text_input.xml:17` — [JARGON] ссылки «IOS-REDESIGN.md»,
  «(E4b)». Убрать коды миссий из комментариев макетов. P3.
- `AndroidManifest.xml:30-35` — [JARGON][AGENT] «O5 (docs/OPTIMIZE-SECURITY-PLAN-2026-09-29.md):
  lets the shell (Perfetto, Macrobenchmark)…». Замена (P2): «profileable для shell-трейсинга
  (Perfetto/Macrobenchmark) на release; framework-атрибут API 29+, приложению прав не
  даёт.» (код «O5» и док-ссылку убрать).
- `values/config.xml:33`, `values/attrs.xml:144` — [JARGON] «(P7-5)» в комментариях к
  цвету/ширине glide-трейла → убрать код. P3.
- `values/strings-appname.xml:24-27` — [SLOP] хвост «the OpenSubtitles link… is the reason
  this row exists» — оставить факт «имена/ссылки не переводятся», сжать. P3.
- `values-ru/strings.xml` — [LANG] несогласованные кавычки: старые AOSP-строки в
  двойных кавычках (`"Виброотклик клавиш"`, `"Тема"`, `"По умолчанию"`), новые — без.
  Android кавычки-обёртки срезает, поведение одинаковое, но выглядит небрежно. Привести
  к единому виду (убрать обрамляющие `"` там, где нет ведущих/хвостовых пробелов). P3.

### app/src/main/assets/ (только шапки txt)

- `dictionaries/tatar_sentstart_v1.txt:1` и `russian_sentstart_v1.txt` — [JARGON] шапка
  «# Tatar sentence-start suggestions, v1 (TT-SUGGESTIONS, phase P4).» Замена (P3): убрать
  «(TT-SUGGESTIONS, phase P4)», оставить «# Tatar sentence-start suggestions, v1». (Файлы
  генерятся; при пересборке шапку задаёт `wordform_gen.py`/пайплайн — правку согласовать
  там, руками не трогать — это генерируемый ассет.)
- `assets/*/NOTICE.txt`, `emoji_set_v1.txt`, `emoji_suggest_v1.txt` — [OK] лицензионные/
  дата-шапки корректны, слопа нет; не трогать (юридическая атрибуция).

## Системные паттерны

Одни и те же болезни тиражированы. Для массовой чистки — grep-маркеры и правило.

1. **Коды миссий в комментариях** — [JARGON]. Маркер:
   `grep -rnE '\b(T2|S1|S2|U[0-9]|E[0-9][a-z]?|E2b-3|M[0-9][a-z]?|P7-[0-9]|D3|D6|C[0-9]|O[0-9]|F1[0-9][a-z]?|Р-[0-9]|SETUP-01|T5)\b' app/src/main/java/.../{emoji,settings} app/src/main/res app/src/main/AndroidManifest.xml`.
   Правило: код миссии из комментария/строки-описания **убрать**; если он нёс смысл —
   переписать смысл словами. Имена типов/переменных не трогать.

2. **Ссылки `docs/*.md` как обоснование** — [JARGON]. Маркер: `grep -rnoE 'docs/[A-Za-z0-9_-]+\.md'`
   (65 вхождений, 17 файлов; топ: ROADMAP-P6 ×15, EMOJI-PANEL-SPACE-2026-09-28 ×11,
   ROADMAP-P2 ×9). Правило: доки планируются к архивации → **удалить ссылку** из
   комментария; при нужде оставить название механизма (например «emoji_suggest_pack.py»,
   «NOTICE.txt» — это живые артефакты, их можно оставлять).

3. **Датированные сноски и аудит-номера** — [AGENT]. Маркер:
   `grep -rnE '2026-[0-9]{2}-[0-9]{2}|audit|finding [0-9]|backlog B[0-9]'` (≈36 мест).
   Правило: даты, «audit», «finding N», «backlog BN», «re-measurement ritual», числа
   резидентной памяти/бюджета — **удалить**; оставить только текущее правило кода.

4. **Класс-док-эссе** — [OVERLONG]. Симптом: KDoc 20–45 строк с историей («used to…»,
   «before this…», «moved verbatim», «parts 1–2 record»). Правило: свернуть до формулы
   «что это + главный инвариант + где тестируется», ≤ ~8 строк. Историю переезда файлов
   (T2-split) — одной строкой «вынесено из X», без кодов.

5. **«operator» / «Telegram client» / пафос** — [SLOP]. Маркер:
   `grep -rniE 'operator|Telegram|moment of victory|the worst row'`. Правило: убрать
   отсылки к тому, «кто попросил», и эмоциональные вставки; описывать поведение, не
   провенанс.

6. **Дубли объяснений** — [DUP]. Кластеры: (а) блок «Р-3 dp/sp» в EmojiPanelView.kt:71-79 =
   EmojiSearchView.kt:93-99; (б) абзац «process-wide owner / UI-thread callback» в трёх
   Personal*ScreenController; (в) «All outcomes arrive on the one store worker…» ×3; (г)
   «Telegram-client arrangement the operator asked for» в EmojiPanelView и EmojiPanelState.
   Правило: оставить объяснение в одном каноническом месте, в остальных — «см. X» одной
   строкой.

7. **Упоминания имён тестов внутри прод-кода** — [AGENT]. Маркер:
   `grep -rnE 'SourceContract|PreferenceTest|pins (its|their)'`. Правило: прод-комментарий
   не должен объяснять, какой тест что пинит; удалить (тесты сами себя документируют).

8. **Ссылки на версии продукта в прозе** — [AGENT], редко (EmojiSearchLayout «1.6.0/1.6.1»,
   SettingsHostActivity «since 1.9.0/1.9.1», «1.8.2»). Правило: убрать номера версий из
   комментариев кода.

Ограничение при чистке (не блокер): переписанные комментарии в emoji-`.kt` не должны
содержать `Log.`/`println`/`System.out`/`java.net.`, а в `SettingsHostActivity.kt` —
`RecentEmojiStore`/`RecentEmojiList`/`noBackupFilesDir`/`deserialize`/`currentRecents`
(эти токены проверяются source-contract тестами на отсутствие в файле целиком).
