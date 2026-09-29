# Ядро latin/ (LatinIME, InputLogic, RichInputConnection, utils/common/setup) — ревизия текстов

Область: файлы прямо в `app/src/main/java/rkr/simplekeyboard/inputmethod/latin/`
(без подпапок suggestions/emoji/settings/dictionary/glide), плюс `latin/inputlogic/`,
`latin/common/`, `latin/utils/`, `latin/setup/`. Итого 35 файлов, 9689 строк.

## Итог

Диагноз: код рабочий, но комментарии в трёх ядровых файлах превратились в
журнал аудита. Доля строк-комментариев: `InputLogic.java` 459/1105 (~42 %),
`RichInputConnection.java` 325/927 (~35 %), `RichInputMethodManager.java`
248/800 (~31 %), `LatinIME.java` 693/2601 (~27 %) — но значительная часть в
последних двух это лицензионные шапки и апстримный javadoc AOSP/Simple Keyboard,
их не трогаем. Слоп сконцентрирован в татарском коде: **49** строк с
датированными сносками `20xx-xx-xx`, **32** упоминания «audit», **32** ссылки на
`docs/*.md` (станут битыми при архивации доков), **~129** токенов кодов-миссий
(F1–F11, S8, C5/C6, P1–P7-7, E4b/E4c/E4d/E5d, D1/D3, B2, O2/O5/O6, T2, U8, W6,
M4c…). Утилиты/common (`StringUtils`, `CapsModeUtils`, `LocaleUtils`,
`RecapitalizeStatus` и т. п.) — практически чистое наследие AOSP, слопа нет.

Категории находок: [SLOP]/[OVERLONG] — массово в 3 файлах; [JARGON] — 129 кодов +
32 docs-ссылки; [AGENT] — 49 дат, git-хеш `25c1ae28`, «operator», отсылки к
тестам; [STALE] — 1 явная ложь (javadoc `mInputType`), 4× бессмысленное «part 2
of 3»; [DEAD] — 20 констант раскладок неподдерживаемых языков + их ветки; [DUP] —
абзац «beginBatchEdit() refreshes the connection…» ×4, «a dialog rather than a
Toast…» ×5.

Топ-3 действия: (1) вырезать датированные сноски и коды-миссий из тел
комментариев, оставив 1–2 строки о назначении — **но** сверяясь со списком
комментариев-якорей тестов ниже (source-contract тесты режут тела методов по
тексту комментариев); (2) убрать 32 ссылки `docs/*.md` до архивации доков; (3)
свернуть повторяющиеся абзацы-простыни (dialogs, batch-edit) в одну общую фразу.

⚠️ **Критично про тесты.** В `app/src/test` есть source-contract тесты, которые
читают эти `.java` как текст и режут тела методов по **первым строкам
комментариев** (`bodyOf(from, "…комментарий…")`). Пинится **код-структура**, а не
проза, — но 7 конкретных первых строк комментариев служат разделителями и их
удаление/переписывание уронит тест «boundary not found». Список — в разделе
«Системные паттерны».

## Файлы/код на удаление

Отдельных файлов-кандидатов на удаление в области **нет**: все 35 файлов
используются (извлечённые хелперы `LatinImeAutocorrect/Glide/EmojiSearch/KeyFeedback/
SoftInputWindow` вызываются из `LatinIME.onEvent`/`setInputView`). Кандидат на
удаление — только мёртвый код (ветки неподдерживаемых языков):

| Символ | Размер | Почему | Кто ссылается (grep) | Риск |
|---|---|---|---|---|
| `SubtypeLocaleUtils.LAYOUT_ARABIC…LAYOUT_URDU` + `LAYOUT_EAST_SLAVIC` (19 констант, стр. 76–98) | ~19 строк + свой коммент | Проект — только tt/ru/en. Сам коммент (стр. 74–75) признаёт: «unreachable since phase 3b», «Kept only because…». [DEAD] | `InputLogic.layoutUsesAutoCaps` switch (стр. 979–998), `SubtypePreferenceUtils.java:78` (миграция legacy `east_slavic`), `KeyboardTextsTable.java` (keyspec_east_slavic_* — наследие) | Средний: убрать нужно согласованно в 3 файлах. Не текст, а код — вне узкой задачи чистки, но по метке [DEAD] отмечено. Низкий приоритет |
| `InputLogic.layoutUsesAutoCaps()` — 18 `case`-веток комплексных письменностей (стр. 979–998) | ~20 строк | Возвращают `false` для раскладок, которых в сборке нет. Сводится к `return true;` | Только сам метод (`getCurrentAutoCapsState`) | Средний: связано с константами выше |
| `SubtypePreferenceUtils` — ветка миграции `east_slavic` (стр. ~78) | несколько строк | Мигрирует префы раскладки, которой больше нет | локальная | Низкий |

Примечание: `mInputTypeNoAutoCorrect`, `mApplicationSpecifiedCompletionOn` в
`InputAttributes` вычисляются, но используются ли ещё после реструктуризации —
не проверял (вне текстовой области; стоит проверить отдельным grep при чистке
кода).

## Правки

### latin/InputAttributes.java

- `InputAttributes.java:56-60` — [STALE] P1 — javadoc «Whether the floating
  gesture preview should be disabled … suppressing the floating preview text.»
  стоит над полем `final private int mInputType;`. Комментарий описывает
  **другое** поле (булев флаг превью жеста из апстрима), к `mInputType` не
  относится вообще — ложь/мусорный остаток. Замена: `// The raw EditorInfo
  inputType this attributes object was built from.`
- `InputAttributes.java:37-52` — [JARGON][OVERLONG] P2 — javadoc
  `mNoPersonalizedLearning`: три абзаца про «Incognito browser tabs», API 26 vs
  24, инлайн константы. Полезное — «флаг из imeOptions, помечает
  no-personalized-learning». Замена (1–2 строки): `// EditorInfo.IME_FLAG_NO_
  PERSONALIZED_LEARNING (imeOptions, не inputType). Инкогнито-поля мессенджеров и
  вкладок; на API 24-25 никто его не ставит.`
- `InputAttributes.java:61-66` — [JARGON] P3 — «Used ONLY as a factor of the E4c
  learning predicate…». Убрать код-миссию `E4c`: `// TYPE_TEXT_VARIATION_POSTAL_
  ADDRESS. Влияет только на обучение личного словаря, не на подсказки.`
- `InputAttributes.java:71-73` — [JARGON] P3 — «E4c, LOCAL to the personal
  dictionary…». То же: убрать `E4c`, оставить смысл (адрес не глушит подсказки,
  только обучение).
- `InputAttributes.java:156-158` — [AGENT] P2 — «The target app's package name is
  deliberately NOT printed (2026-09-24 audit, finding 10):». Замена: `// Имя
  пакета приложения не логируем: в какой программе печатает пользователь — не
  дело клавиатуры.`

### latin/AudioAndHapticFeedbackManager.java

- `AudioAndHapticFeedbackManager.java:128-131` — [AGENT][JARGON] P1 — «W6
  (docs/APPLE-UX-2026-09-25.md): … Pinned by AppleUxBatchOneContractTest, which
  asserts the override constant is absent from this whole file.» Код-миссия +
  docs-ссылка + отсылка к тесту. Комментарий тестом **не** пинится (тест проверяет
  отсутствие `FLAG_IGNORE_GLOBAL_SETTING` в коде, не текст). Замена: `//
  Системный переключатель вибрации главнее: на API<29 не подменяем его флагом, как
  и Vibrator на API 29+.`

### latin/utils/AppLocale.kt & latin/setup/SetupActivity.kt

- `AppLocale.kt:24-25` — [AGENT] P2 — «decided 2026-09-25: this is a Tatar
  keyboard…». Убрать дату. Оставить: `// Язык экранов приложения. По умолчанию —
  татарский (это татарская клавиатура), не английский-фолбэк.`
- `AppLocale.kt:31-32` — [AGENT] P1 — «that is the operator's explicit call: the
  product exists for Tatar speakers…». Слово «operator» — артефакт агентной
  сессии. Замена: удалить фразу про «operator»; смысл («англоязычные видят
  татарский, английские строки остаются как фолбэк») оставить одной строкой.
- `AppLocale.kt:34-42` — [OVERLONG] P3 — абзац «Mechanism: a
  Context.createConfigurationContext wrap…» на 9 строк. Полезно, но можно ужать до
  2–3: суть — «оборачиваем attachBaseContext, ночной режим и масштаб шрифта
  копируем, процесс IME не трогаем».
- `SetupActivity.kt:31` — [JARGON] P3 — «Two-step onboarding screen (SETUP-01)…».
  Убрать код `(SETUP-01)`. Остальной комментарий класса хороший.
- `SetupActivity.kt` в целом — [OK]: комментарии по делу (почему guard на IMM,
  почему префикс-сравнение пакета, почему live-region setTextIfChanged). Не
  трогать, кроме кода-миссии.

### latin/utils/SubtypeLocaleUtils.java

- `SubtypeLocaleUtils.java:45-47` — [AGENT] P2 — «Phase 3b (2026-08-30): the app
  ships three keyboard locales only…». Замена: `// Приложение поставляет три
  раскладки: татарскую, русскую, английскую (US). Остальные ~70 из Simple Keyboard
  вырезаны вместе с их XML.`
- `SubtypeLocaleUtils.java:69-71` — [AGENT][JARGON] P2 — «Roadmap phase 1 (T4,
  2026-09-22) cut the six legacy families bepo/azerty/dvorak/colemak/workman/
  pcqwerty…». Убрать `T4`, дату. Оставить факт про активные раскладки.
- `SubtypeLocaleUtils.java:74-75` — [DEAD][JARGON] P2 — «Kept only because
  InputLogic still switches on these complex-script layout names… unreachable
  since phase 3b.» Точное описание мёртвого кода. Либо убрать сам мёртвый код (см.
  таблицу), либо оставить одну строку без «phase 3b»: `// Мёртвые: нужны только
  из-за switch в InputLogic и миграции east_slavic в SubtypePreferenceUtils.`
- `SubtypeLocaleUtils.java:151-155` (`getDefaultSubtypes`) — [JARGON] P3 —
  «(SWITCH-01). Deliberately independent of system locales…». Убрать `(SWITCH-01)`,
  смысл оставить.

### latin/utils/DialogUtils.java

- `DialogUtils.java:39-42` — [AGENT][OVERLONG] P2 — «Audit 2026-09-02, C5: makes
  the dialog drop touches…». Убрать дату+`C5`. Оставить: `// Диалог игнорирует
  касания, доставленные пока его окно перекрыто (защита от tapjacking: окно IME
  плавает над чужими приложениями).` Второй абзац (про decor view и show listener)
  полезен технически — оставить, ужать.
- `DialogUtils.java:60-63` — [AGENT] P2 — «Audit 2026-09-25: marks the dialog's
  own window secure…». Убрать дату. Смысл (FLAG_SECURE на окне диалога с личным
  контентом) оставить.

### latin/utils/ResourceUtils.java

- `ResourceUtils.java:66-68` — [JARGON] P3 — коммент со ссылкой
  `docs/EMOJI-PANEL-SPACE-2026-09-28.md, …`. Убрать docs-ссылку, оставить, что
  пресеты ограничивают высоту панели тем же значением.

### latin/InputView.java

- `InputView.java:41-50` — [OVERLONG][JARGON] P2 — javadoc `mStripHiddenByEmojiPanel`
  на 10 строк со ссылкой `docs/EMOJI-PANEL-SPACE-2026-09-28.md`. Замена (2–3
  строки): `// Панель эмодзи скрыла видимую полосу подсказок — hideEmojiPanel()
  вернёт её. Высота полосы отдаётся панели, поэтому высота клавиатуры не меняется.`
- `InputView.java:186-205` — [OVERLONG][JARGON] P2 — javadoc `showEmojiPanel`:
  абзац про «item B», «46%p-of-screen ceiling», landscape/fullscreen-extract,
  docs-ссылка. Сильно длинно. Оставить 2–3 строки: панель размером с клавиатуру
  (тот же content top inset); при не-дефолтной настройке высоты — масштаб с
  потолком `panelMaxHeightPx`.
- `InputView.java:204-206` — [JARGON][AGENT] P2 — «O5 (docs/OPTIMIZE-SECURITY-
  PLAN-2026-09-29.md): view-side half of the panel-open span … A Trace begin/end
  pair costs ~10 µs…». Убрать `O5`, docs, микро-бенчмарк «~10 µs». Оставить: `//
  Trace-метка первого раздутия панели эмодзи.`

### latin/RichInputConnection.java  (14 датированных сносок, коды F1–F11 + S8 + C6)

- `RichInputConnection.java:52-59` — [SLOP][OVERLONG][JARGON] P2 — class javadoc,
  абзац «2026-09-29 audit wave, S8 (docs/…): the host app on the other side …
  DeadObjectException … the same shape as the F6 dead-editor guards …». 8 строк
  риторики. Оставить 2–3: `// Обёртка над InputConnection: кэширует текст вокруг
  курсора и гасит RuntimeException от чужого/умершего редактора, чтобы он не убил
  процесс IME.`
- `RichInputConnection.java:90-115` — [SLOP][OVERLONG][JARGON] P2 — поле
  `mCacheReachedTextStart`: **26 строк** javadoc («Audit 2026-09-02, C6…», «the D1
  fix (docs/NEXTWORD-RACE.md)…», разбор F3-усечения). Это худший пример в файле.
  Оставить 3–4 строки: `// true, если кэш before-cursor доказуемо начинается с
  начала текста редактора (знание полной перезагрузки). Локальные правки его
  сохраняют, усечение головы кэша — сбрасывает.`
- `RichInputConnection.java:196-201` — [JARGON] P2 ⚠ **пинится
  RichInputConnectionRobustnessContractTest** через javadoc метода
  `applyTextAroundCursor`? Нет — тест режет по `performEditorAction`. Здесь просто
  `2026-09-25 audit, F4`. Убрать дату/код, оставить смысл: fail-closed при
  некорректном selection от хоста.
- `RichInputConnection.java:223-231` — [OVERLONG][JARGON] P2 — javadoc
  `onBeforeCursorCacheReloaded`/reload «2026-09-29 audit wave, S8…». Ужать.
- `RichInputConnection.java:287-293` — [JARGON] P2 ⚠ **первая строка
  `/**\n * 2026-09-25 audit, F10` — разделитель в InputConnectionBinderContractTest:131**
  (`reloadTextCache(final EditorInfo…` режется до этого коммента). Первую строку
  комментария (текст «2026-09-25 audit, F10») **нельзя** удалять без правки теста;
  тело после неё — сократить.
- `RichInputConnection.java:485-491` — [JARGON] P2 — `appendToTextBeforeCursor`:
  «2026-09-25 audit, F3: every append … a megabyte-long paste stayed resident…».
  Убрать дату/код, оставить: держим хвост `EDITOR_CONTENTS_CACHE_SIZE`, при
  усечении головы сбрасываем провенанс C6→«начало текста».
- `RichInputConnection.java:712-719` — [JARGON][SLOP] P2 ⚠ **первая строка
  `/**\n * 2026-09-25 audit, F1` — разделитель в
  RichInputConnectionRobustnessContractTest:273** (тело `performEditorAction`
  режется до этого коммента). **Нельзя** менять первую строку `* 2026-09-25 audit,
  F1` без правки теста. Тело (про TransactionTooLargeException, «64 Ki chars is an
  order of magnitude under…») можно ужать.
- Групповая правка [AGENT][DUP] P2: **11 catch-блоков** с идентичным
  «// S8 (see the class javadoc): the editor died or threw …» (стр. 138, 153, 170,
  ~430, ~490, ~520, 555, ~636, ~663, ~691, ~756, 803, 844). Достаточно
  одной формулировки в javadoc класса и коротких `// см. javadoc класса` в теле.
  ⚠ `catch (final RuntimeException e)` как **код** пинится
  BatchEditPairingContractTest (ровно 11) и RichInputConnectionRobustnessContractTest —
  сам код catch трогать нельзя, только текст внутри.
- `RichInputConnection.java:881-889` — [JARGON][OVERLONG] P2 — «2026-09-25 audit,
  F11: this was a `== ""` reference comparison … CharSequence.isEmpty() … API 35 …».
  Разбор истории правки. Оставить: `// length()==0, а не isEmpty(): последний —
  default-метод с API 35, а minSdk здесь 24.`

### latin/inputlogic/InputLogic.java  (6 датированных сносок, P4/P7-6/P7-7/E5d/D3/F5/F7)

- `InputLogic.java:369-372` — [JARGON] P3 — «2026-09-25 audit, F5: the batch
  closes in finally…». Убрать дату/код: `// endBatchEdit в finally — исключение
  умирающего редактора не должно навсегда оставить незакрытый batch.` ⚠ shape
  batch/try/finally пинится BatchEditPairingContractTest (текст можно менять, код
  нет).
- `InputLogic.java:462-465` — [JARGON] P3 — «2026-09-25 audit, F7: an inverted
  selection…». Убрать дату/код.
- `InputLogic.java:475-482` (`replaceTrailingWord` javadoc) — [SLOP][OVERLONG]
  P2 — «THE single place where a Tatar word is replaced… frozen text contract…».
  Пафос («THE single place», «frozen text contract»). Ужать до сути: единственный
  путь замены хвостового слова (подсказка/автозамена), одно удаление+commit в одном
  batch, composing не ставится.
- `InputLogic.java:498-514` — [SLOP][OVERLONG][DUP] P2 — **абзац** «beginBatchEdit()
  is what refreshes the connection from the framework, so this is the earliest the
  question can be asked … taken from fiction. Nothing is touched instead, and false
  is the truth.» Этот абзац (≈16 строк) **скопирован почти дословно 3–4 раза**:
  здесь, в `commitPredictedWord` (стр. ~752), в `commitGlideWord` (короче, стр.
  ~858), в `replaceGlideLiftedWord` (стр. ~910). [DUP]. Оставить в одном месте
  1–2 строки и ссылаться: `// см. replaceTrailingWord: проверка соединения после
  beginBatchEdit, мёртвый редактор → false, batch всё равно закрывается.`
- `InputLogic.java:660-666` (`revertTatarAutocorrection`) — [AGENT] **P1** —
  «The same guard the two commit paths above carry (**25c1ae28**): beginBatchEdit()
  …». В комментарии git-хеш коммита. Удалить `(25c1ae28)`. ⚠ **первая
  описательная строка javadoc «Undoes the autocorrection…» — разделитель в
  CommitPathConnectionContractTest:55** (`replaceTrailingWord` режется до
  `"* Undoes the autocorrection"`). Первую строку не менять; убрать хеш — он в
  теле, безопасно.
- `InputLogic.java:686-710` (`commitPredictedWord` javadoc) — [SLOP][OVERLONG]
  [JARGON] P2 — «Commits a predicted next word (E5d): the THIRD insertion path of
  the frozen text contract … NEXT_WORD deletes NOTHING (PROPOSALS.md, "Контракт
  текста" amendment, 2026-08-17…) … P4 (docs/TT-SUGGESTIONS.md) adds one case…».
  ~24 строки. Ссылки на `PROPOSALS.md`, `docs/`, дата, коды `E5d`/`P4`. Ужать до
  3–4 строк по смыслу. ⚠ **первая строка `/**\n * Commits a predicted next word` —
  разделитель в CommitPathConnectionContractTest:69** (`revertTatarAutocorrection`
  режется до неё): первую строку сохранить.
- `InputLogic.java:787-792` (`endsWith`) — [OK по тексту] ⚠ **первая строка
  `/** Allocation-free suffix test over the cached text; …` — разделитель в трёх
  тестах** (GlideTouchIntegrationContractTest:290,
  CommitPathConnectionContractTest:60, SuggestionStripSourceContractTest:366/381/404).
  Комментарий короткий и по делу — **не трогать** (иначе три теста красные).
- `InputLogic.java:803-877` — [SLOP][OVERLONG][JARGON] P2 — блок методов глайда
  (`commitGlideWord`, `replaceGlideLiftedWord`, `deleteGlideLiftedWord`): javadoc
  с «P7-6, docs/ROADMAP-P7.md — the 2026-09-24 field report», «P7-7 (the
  2026-09-25 contract change)», примеры «сүз ? », «сәләм дөнья». Коды-миссий и
  даты убрать; поведенческий смысл (глайд не ставит auto-space; цепочка добавляет
  один пробел; undo целого слова) оставить кратко.
- `InputLogic.java` `getCurrentAutoCapsState` javadoc (стр. ~500 выше по факту —
  реально `/**\n * Gets the current auto-caps state`) — ⚠ **разделитель в
  InputConnectionBinderContractTest:199 и BatchEditPairingContractTest:150**.
  Текст апстримный, короткий — не трогать.
- `InputLogic.java` `sendKeyCodePoint` javadoc `/**\n * Sends a code point` —
  ⚠ **разделитель в InputConnectionBinderContractTest:180**. Апстримный текст, не
  трогать.
- `InputLogic.java:979-998` (`layoutUsesAutoCaps`) — [DEAD] P2 — switch по 18
  раскладкам несуществующих языков (см. таблицу удаления).

### latin/LatinIME.java  (17 датированных сносок; наибольшая масса slop)

- `LatinIME.java:200-213` — [OVERLONG][JARGON] P2 — коммент `MSG_UPDATE_SHIFT_STATE`
  «NO MESSAGE ID HERE MAY BE ZERO … Six places post such runnables … see
  docs/SUGGEST-DIES.md.» История бага на ~13 строк. Тест
  (HandlerMessageIdSourceContractTest) пинит **значение id != 0**, не текст. Ужать
  до 2 строк: `// id не должен быть 0: Handler.post(Runnable) использует what=0, и
  removeMessages(0) удалил бы чужие posted-runnable. Поэтому MSG_UPDATE_SHIFT_STATE=3.`
- `LatinIME.java:386-388` — [JARGON][AGENT] P2 — «O5 (docs/…): Perfetto spine …
  A Trace begin/end pair costs ~10 µs, so markers wrap only coarse spans…». Убрать
  `O5`, docs, микро-бенчмарк. Оставить `// Trace-метка инициализации IME.` То же
  для `LatinIME.java:1673-1675` (`onCreateInputView` «O5 … Same ~10 µs marker-pair
  discipline»).
- `LatinIME.java:588-615` — [SLOP][OVERLONG][JARGON] P2 — фабрика движка:
  комментарии «The personal side of the merge (E4b)…», «P1 (docs/ROADMAP-P2.md)…»,
  «P3 (docs/TT-SUGGESTIONS.md)…», «TT-TYPO-NEXT Phases B/C/C2 (docs/…): the fuzzy
  pass … class #1 (long-press) plus class #4 … measured and passed (2026-09-20)»,
  «TT-NEXTWORD-FILL (docs/…)». Каждый на 4–6 строк с кодом-миссией+docs+дата.
  Свести к 1–2 строкам на блок без кодов и docs.
- `LatinIME.java:668-780` — [DUP][JARGON] P2 — серия комментариев про «gate»/«sink»
  с кодами E4b/E4c/P1/P3/B2/P7-3. Повтор мысли «читаем настройку живьём на каждом
  запросе, привязка к языку» много раз. Свести к одной формулировке + короткие
  метки без кодов.
- `LatinIME.java:825-846` — [SLOP][OVERLONG] P2 — javadoc `showSuggestionsOfferDialog`
  и `containsAnyLetter`: «with the setting off it stays GONE before, during and
  after the dialog», «a supplementary letter would simply read as two non-letters —
  "emoji", the safe direction…». Ужать.
- `LatinIME.java:1137-1450` — [DUP][SLOP][OVERLONG] **P2, крупнейший [DUP]** —
  семь диалоговых методов (`showSuggestionsUnavailableDialog`,
  `showPersonalDictionaryOffDialog`, `showNotASavedWordDialog`,
  `showEmojiUnavailableDialog`, `showPersonalForgetFailedDialog`,
  `showPersonalDictionaryUnreadableDialog`, `showPersonalBigramsUnreadableDialog`,
  `showPersonalEmojiUnreadableDialog`) содержат почти дословно повторяющийся абзац
  «A modal dialog rather than a Toast … a toast from a background process is at the
  platform's discretion … The body names no word, no file and no cause…». Это
  ~5–10 строк ×7. Свести к **одному** javadoc-объяснению стиля диалогов и ссылаться
  из остальных одной строкой.
- `LatinIME.java:1462-1466` — [AGENT] P2 — `isPasswordField` «2026-09-25 audit,
  privacy: whether [editorInfo] describes a password-type field…». Убрать дату.
- `LatinIME.java:1509-1514`, `1524-1537`, `1562`, `1595-1600` — [JARGON][AGENT]
  P2 — `isGlideEligible`, `mayLearnPersonalWords`, соседние поля: коды P7-6/U8/E4c,
  «the 2026-09-24 field report: Gboard parity», docs. Убрать коды/даты/docs,
  оставить поведение.
- `LatinIME.java:1741-1744` — [AGENT] P2 — «The geometry goes first (2026-09-24
  audit, finding 6): onSubtypeChanged…». Убрать «(2026-09-24 audit, finding 6)».
- `LatinIME.java:1801`, `1833-1839`, `1914-1919`, `1929-1931`, `1980-1981`,
  `1993`, `2201` — [AGENT] P2 — семь privacy-комментариев «2026-09-25 audit,
  privacy: …» / «(2026-09-24 audit, finding 5)». Смысл (не читаем/не логируем текст
  пароля, чистим кэш на границах сессии) хороший — убрать даты/«finding N».
  ⚠ соответствующий **код** (`mInputLogic.clearCaches()`, `isPasswordField(...)`)
  пинится EditorTextCachePrivacySourceContractTest — код не трогать, только текст.
- `LatinIME.java:1910` — [JARGON] P3 — «(M4c)» в `onWindowHidden`. Убрать код.
- `LatinIME.java:1955-1958` — [JARGON][AGENT] P2 — `deallocateMemory` «O2
  (docs/OPTIMIZE-2026-09-25.md): the glide word indexes…». Убрать код/docs.
- `LatinIME.java:2355-2359` — [JARGON] P2 — `onGlideInput` javadoc «(P7-2/P7-3,
  docs/GLIDE-PLAN.md) … (P7-6: suggestions-off no longer closes it…)». Убрать
  коды/docs, оставить: глайд-жест уходит в контроллер подсказок; при выключенном
  глайде — no-op.
- `LatinIME.java:2430` — [JARGON] P3 — `onEmojiInserted` «(B2)». И вообще javadoc
  этого метода + `onStripEmojiInserted` [DUP]: дважды объяснено «routing one tap
  through both would count it twice». Свести.

### latin/RichInputMethodManager.java

- Почти целиком наследие Simple Keyboard. Единственная татарская сноска:
  `RichInputMethodManager.java:685` — [AGENT] P3 — «Audit 2026-09-02, C5: attached
  to the IME window, the picker floats over other apps.» Убрать дату/`C5`: `//
  Пикер прикреплён к окну IME и плавает над чужими приложениями — гасим касания
  сквозь перекрытие.`

### Извлечённые хелперы (LatinImeAutocorrect / Glide / EmojiSearch / KeyFeedback / SoftInputWindow)

- **P1 [STALE][DUP]** — во всех четырёх (`LatinImeSoftInputWindow.java:34`,
  `LatinImeEmojiSearch.java:25`, `LatinImeKeyFeedback.java:24`,
  `LatinImeAutocorrect.java:24`) первая строка class-javadoc: «… (T2 split, **part
  2 of 3**): …». Четыре файла не могут быть «частью 2 из 3» одновременно — фраза
  бессмысленна и есть артефакт агентной нарезки. Убрать «(T2 split, part 2 of 3)»
  везде, оставив описание назначения класса.
- `LatinImeAutocorrect.java:36-46, 62-70` — [JARGON][OVERLONG] P3 — коды `D3`,
  «(D3)», ««пробел или пунктуация»» — смешение русской вставки в англ. коммент
  [LANG]. Ужать, убрать `D3`.
- `LatinImeGlide.java:22-25` — [JARGON] P3 — «(the UX amendment, docs/ROADMAP-P7.md)».
  Убрать docs-ссылку.

### common/ и остальные utils/

- `Constants.java`, `CollectionUtils.java`, `CoordinateUtils.java`,
  `StringUtils.java`, `LocaleUtils.java`, `CapsModeUtils.java`,
  `RecapitalizeStatus.java`, `InputTypeUtils.java`, `XmlParseUtils.java`,
  `ViewLayoutUtils.java`, `TypefaceUtils.java`, `ApplicationUtils.java`,
  `LeakGuardHandlerWrapper.java`, `LanguageOnSpacebarUtils.java`,
  `LocaleResourceUtils.java`, `SubtypePreferenceUtils.java` — [OK]: наследие
  AOSP/Simple Keyboard, слопа/дат/кодов-миссий нет. Не трогать (кроме east_slavic-
  ветки в `SubtypePreferenceUtils`, см. таблицу [DEAD]).

## Системные паттерны

Повторяющиеся проблемы и grep-маркеры для массовой чистки (по всей области):

1. **Датированные сноски аудита** — 49 строк. Маркер:
   `grep -nE "20[0-9]{2}-[0-9]{2}-[0-9]{2}"`. Правило: дату и слово «audit»
   убрать, оставить одну строку о том, что и зачем делает код. Основной очаг —
   `RichInputConnection.java` (14), `LatinIME.java` (17), `InputLogic.java` (6).

2. **Коды миссий без расшифровки** — ~129 токенов (F1–F11, S8, C5/C6, P1–P7-7,
   E4b/E4c/E4d/E5d, D1/D3, B2, O2/O5/O6, T2, U8, W6, M4c). Маркер:
   `grep -noE "\b([A-Z][0-9]+[a-z]?|P[0-9]-[0-9]|E[0-9][a-z])\b"`. Правило:
   удалить код целиком; если он что-то различает (например ветку/фичу) — назвать
   словами.

3. **Ссылки `docs/*.md`** — 32 штуки. Маркер: `grep -noE "docs/[A-Za-z0-9._-]+\.md"`.
   Правило: удалить (доки планируются к архивации — ссылки станут битыми); при
   реальной необходимости — перенести суть в 1 строку.

4. **Дублированные абзацы-простыни** — правило «одно объяснение + короткая
   ссылка на него»:
   - «beginBatchEdit() refreshes the connection … false is the truth» — 3–4× в
     `InputLogic.java` (~498, ~752, ~858, ~910).
   - «a dialog rather than a Toast … names no word/file/cause» — ~7× в
     `LatinIME.java` (1137–1450).
   - «// S8 (see the class javadoc): the editor died or threw» — 11× в
     `RichInputConnection.java`.

5. **Агентные артефакты** — маркеры: `grep -niE "operator|25c1ae28|part 2 of 3"`.
   Правило: удалить. Конкретно: git-хеш `25c1ae28` (`InputLogic.java:660`), слово
   «operator» (`AppLocale.kt:32`), бессмысленное «(T2 split, part 2 of 3)» в 4
   хелперах.

6. **Ложные/устаревшие комментарии** — `InputAttributes.java:56-60` (javadoc про
   «floating gesture preview» над полем `mInputType`). Проверять комментарий против
   кода при чистке.

7. **Мёртвый код неподдерживаемых языков** — 20 констант `LAYOUT_*` + switch в
   `InputLogic` + ветка `east_slavic`. Маркер:
   `grep -rn "LAYOUT_\(ARABIC\|BENGALI\|FARSI\|…\)"`. Удалять согласованно в 3
   файлах (не только текст — это код).

8. ⚠️ **Комментарии-якоря source-contract тестов** (нельзя удалять/переписывать
   первую строку без синхронной правки теста; тела после первой строки — можно
   сокращать):
   - `RichInputConnection.java` — `/**\n * 2026-09-25 audit, F1` (тест
     RichInputConnectionRobustnessContractTest:273), `/**\n * 2026-09-25 audit,
     F10` (InputConnectionBinderContractTest:131), `/**\n * Set the selection`
     (RichInputConnectionRobustnessContractTest:275, апстрим).
   - `InputLogic.java` — `/** Allocation-free suffix test over the cached text`
     (3 теста), `* Undoes the autocorrection` (CommitPathConnectionContractTest:55),
     `/**\n * Commits a predicted next word` (CommitPathConnectionContractTest:69),
     `/**\n * Sends a code point` (InputConnectionBinderContractTest:180),
     `/**\n * Gets the current auto-caps state`
     (InputConnectionBinderContractTest:199, BatchEditPairingContractTest:150).
   Общее правило безопасной чистки этих файлов: сначала прогнать
   `grep -rn 'bodyOf\|substringBefore\|substringAfter' app/src/test` по имени
   файла, свериться со списком якорей, и **только тела** комментариев сокращать;
   код (catch-блоки, batch-пары, id сообщений, сигнатуры) не трогать — он пинится
   отдельно и это правильно.
