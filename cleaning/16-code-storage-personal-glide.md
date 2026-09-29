# storage / personal / personalstore / glide — ревизия текстов

Область: `latin/dictionary/storage/` (8 файлов), `latin/dictionary/personal/` (16),
`latin/dictionary/personalstore/` (23), `latin/glide/` (11) — 58 файлов, 11 608 строк Kotlin.
Читал каждый файл целиком.

## Итог

Код здесь чистый и аккуратный; практически весь «нейрослоп» — в комментариях и KDoc.
Доля комментариев (только строки, начинающиеся с `//`/`*`, — реальная выше за счёт inline):
`DictionaryStorageContracts` 42 %, `BigramStorageContracts` 39 %, `PersonalDictionaryStore` 36 %,
`GlideDecoder` 32 %, `PersonalEmojiStore` 31 %, `PersonalBigramStore` 30 %; мелкие форматы/сидмы —
63–78 % (`PersonalSubtypes` 78 %, `PersonalStorageSeams` 72 %, `Tpersb/TpersemFormat` ~68 %).
Находки по категориям (примерно): **[JARGON]** — доминирует, коды миссий (D1a/D1b/E4a-2/E4b/E4c/E5b/E5c/P1/P7-1…8/T5/T7/U7/U8/B2–B5/S2/SIZE-1/SIZE-2/EXPAND-1/F13/F14) и ссылки `docs/*.md`
в десятках файлов; **[OVERLONG]** — истории перепаковок и многоабзацные KDoc-эссе (два контракта + три стора + `GlideDecoder`); **[AGENT]** — датированные сноски «2026-08/09-xx audit, finding N», SHA/размеры и «operator» в комментариях; **[DUP]** — тройное дублирование `Personal{Dictionary,Bigram,Emoji}{Store,Entries,Dictionaries,Learning,QuarantineSalvage,Source}` + фраза «NOT a Kotlin data class … first interpolation» в ~13 файлах; **[STALE]** — 3 реальных (Dormant-коммент, висячий KDoc глайда, мёртвый `estimatedFileSize`); **[LANG]** — вкрапления русского в англоязычные комментарии.
Ничего не найдено: закомментированный код, мусор, файлы под удаление целиком (все 58 подключены).
Топ-3 действия: (1) grep-зачистка кодов миссий + `docs/*.md` + датированных сносок, оставить одну строку назначения; (2) свернуть тройные дубли комментариев в один канон + «mirror of X»; (3) починить 3 STALE/WEIRD.

## Файлы/код на удаление

Целых файлов-кандидатов на удаление нет — фабрики, фасады, сидмы и синки все подключены
(проверено grep: `AndroidPersonalDictionaryStorage.create/createBigrams` вызывается из
`PersonalDictionaries`/`PersonalBigramDictionaries`; `PersonalEmojiDictionaries.createStore` — там же).
Кандидаты на удаление — символы и один висячий комментарий:

| символ / место | размер | почему | кто ссылается (grep) | риск |
|---|---|---|---|---|
| `PersonalEntries.estimatedFileSize()` (`PersonalEntries.kt:179`) | ~3 стр. | Мёртвый метод: `writeWhole` считает `bytes.size` напрямую; ни main, ни тесты его не зовут | нет ссылок нигде | низкий |
| `PersonalBigramEntries.estimatedFileSize()` (`PersonalBigramEntries.kt:236`) | ~5 стр. | В проде не используется (writeWhole не зовёт) | только `PersonalBigramEntriesTest` | средний (тронет 1 тест) |
| `PersonalEmojiEntries.estimatedFileSize()` (`PersonalEmojiEntries.kt:221`) | ~5 стр. | То же | только `PersonalEmojiEntriesTest` | средний (тронет 1 тест) |
| висячий KDoc `GlideGestureDecider.kt:124` | 1 стр. | KDoc «FlorisBoard's distance threshold…» ни к чему не привязан (константа удалена, порог — параметр конструктора `distanceThresholdPx`) | никто | нулевой |

Замечание по всем `estimatedFileSize`: их KDoc «for the pre-write free-space check» **вводит в заблуждение** — фактическая пред-записная проверка в `writeWhole` использует `bytes.size + FREE_SPACE_RESERVE_BYTES`, а не этот метод. Это [STALE], а не только [DEAD].

## Правки

### Пинация тестами — общий факт
Source-contract тесты в этой области пинят **код-идентификаторы и структуру**, а не прозу
комментариев (проверено: `PersonalStoragePathSourceContractTest`, `PersonalStorePrivacyTest`,
`GlideSourceContractTest`, `PersonalDictionaryNoLiveWriteSourceContractTest`,
`GlideIndexResidencySourceContractTest`). Значит чистка комментариев в целом безопасна, если:
- **не вводить** токены `data class` в типах с пользовательскими словами (`PersonalStorePrivacyTest.noTypeCarryingAWordIsADataClass`), `createDeviceProtectedStorageContext`/`android.util.Log`/`typedText` в `personalstore` (`PersonalStorePrivacyTest.packageHasNoLogging…`), `DeviceProtectedDirectoryProvider`/`RecentEmojiFileProvider` в `personalstore` (`PersonalStoragePathSourceContractTest`);
- в `latin/glide` кириллица в комментариях **разрешена** (`GlideSourceContractTest.glideSourcesCarryNoCyrillicLiteral` вырезает комментарии перед проверкой) — правка/удаление русской фразы безопасны.
Отдельные находки с риском помечены «⚠».

---

### storage/DictionaryStorageContracts.kt (164/391 — 42 % комментариев)

- `:123–133` и `:163–184` — **[OVERLONG][AGENT][JARGON]** — «Repacked 2026-08-24 for 1.9.1 … the operator then read a sample … `docs/DICT-ACCEPT.md` … `docs/DICT-WIDEN.md`» — P1. Двухабзацные истории перепаковок с датами, версиями, числами вытеснений и `docs/*.md`. Пины (SHA/размеры/`expectedEntryCount`) живут в коде ниже и проверяются asset-тестами по значениям, не по тексту. Замена: одна строка на артефакт, напр. `/** Татарский top-110k словарь. Пины (SHA-256, размеры, число слов) ниже — единственный источник истины; пересборка требует их пересчёта. */`.
- `:10–24` (KDoc `DictionaryArtifactSpec`) — **[SLOP][OVERLONG]** — «Both are literals of the spec rather than values derived … Backward compatibility wins; the family is the seam that lets a new language pick its own name…» — P2. Риторика на 15 строк вместо сути. Замена: `// family и storageDirectoryName — литералы: имя/каталог татарского файла заморожены с 1.6.1, переименование заставит устройство переинфлировать 2.5 МБ.` (2 строки).
- `:31–50` (KDoc `bigrams`) — **[SLOP][OVERLONG]** — «This field is what makes "which language is active" a question with ONE answer … which is exactly how a fix ends up landing in one copy…» — P2. Замена: `/** Таблица биграмм языка или null. Реестр языков один: [ALL]/[forSubtype] отвечают за оба вида артефактов. */`.
- `:58` — **[JARGON]** — «(ROADMAP Phase 1, P3b)» — P3 — убрать код фазы, оставить «таблица начала предложения языка или null».
- `:73` и `:88` — **[JARGON]** — «T5 (docs/ROADMAP-P1.md): this field is what makes the registry the single source…» ×2 — P3 — убрать `T5`/ссылку, оставить одно предложение.
- `:275` — **[JARGON]** — «D1b's staged-publication retention depends on that throwing behaviour» — P3 — «Отличается от [atomicRename], который бросает при существующем destination: staged-retention на это опирается.» (без `D1b`).
- `:380–384` (TdictFormat) — **[JARGON][LANG]** — «Schema 2 (SIZE-1, docs/SIZE-SCHEMA2.md): блоки front-coding … K = 8 выбрано замером 2026-09-01» — P3 — оставить «блочный front-coding, K=8» без кода миссии, ссылки и даты.

### storage/BigramStorageContracts.kt (121/314 — 39 %)

- `:104–160` (KDoc `TATAR_BIGRAMS_V1`) — **[OVERLONG][AGENT][JARGON]** — «Repacked 2026-08-25 … 2026-08-31 … 2026-09-01 … 2026-09-20 … 2026-09-23 (ROADMAP-P4 batch A) … eval next-word coverage 75.3623 % → 84.1730 %» — P1. Пять датированных абзацев истории с процентами и `docs/archive/bigrams/*`. Замена: 1 строка назначения, числа-пины оставить в полях ниже.
- `:163–190` (KDoc `RUSSIAN_BIGRAMS_V1`) — то же **[OVERLONG][AGENT]** — P1 — свернуть до одной строки.
- `:8–34` (KDoc `BigramArtifactSpec`) — **[JARGON][LANG][SLOP]** — «E5b: … per PROPOSALS.md ("E5b. Отдельный файл и отдельная схема") … the second language duly added a second spec rather than a second class» — P2. Замена: 2–3 строки о `fileLanguageTag` (в имени файла, заморожен на `tt`) vs `subtypeId`, без `E5b`/PROPOSALS.
- `:218` — **[JARGON][LANG]** — «E5c's own contract ("владелец состояния обязан знать вид результата")» — P3 — убрать код и русскую цитату.
- `:263–269` (KDoc `BigramStorageController`) — **[SLOP]** — «mirrored here rather than generalized … `docs/DICTIONARY-E5B.md` … the last layer of that same shape» — P3 — «Аналог [DictionaryStorageController] для таблицы биграмм.».

### storage/AtomicBigramStore.kt

- `:8–20` (KDoc класса) — **[JARGON][LANG]** — «PROPOSALS.md, "E5b. Хранение" … a DIFFERENT concrete class … [ProcessBigramStorageOwner] is a SEPARATE process-wide registry» — P2. Сохранить суть (отдельный класс/реестр/каталог, чтобы лизы не пересекались), убрать PROPOSALS/E5b.
- `:24–27` — **[JARGON][LANG]** — «Reused as-is per PROPOSALS.md ("Переиспользуются швы DeviceProtectedDirectoryProvider…")» — P2 — «Сид переиспользован: метод возвращает подкаталог биграмм, а не filesDir/dictionaries.».
- `:180`, `:189` (внутри `ProcessBigramStorageOwner`) — **[SLOP]** — «deliberately a SEPARATE registry … so a live dictionary lease and a live bigram lease can never contend for the same lock» — P3 — сжать до одной строки.

### storage/TatBigrValidator.kt / AtomicDictionaryStore.kt / factories

- `TatBigrValidator.kt:26–35` — **[JARGON]** — «TATBIGR schema-3 (SIZE-2, `docs/SIZE-SCHEMA3.md` — … replaced schema 2's six sections on 2026-09-01)» — P3 — убрать `SIZE-2`/ссылку/дату.
- `AndroidDictionaryStorageFactory.kt:44–48` (KDoc `AndroidDurableFileOps`) — **[JARGON]** — «so the personal store's whole-file write path (E4a-2) can reuse…» — P3 — убрать `E4a-2`.
- `AndroidBigramStorageFactory.kt:9–17` — **[JARGON]** — «the E5c counterpart of [AndroidDictionaryStorageFactory]» — P3 — «Продакшн-обвязка стора таблицы биграмм, аналог [AndroidDictionaryStorageFactory].».
- `AtomicDictionaryStore.kt` — чистый; правок не требует.

### personalstore/PersonalDictionaryStore.kt (283/792 — 36 %)

Крупнейший источник [OVERLONG]/[SLOP]/[AGENT] в области. Комментарии рассказывают сюжет
(«The keyboard died in the middle of typing in someone else's app»), а не «что/зачем делает код».

- `:49–51` — **[STALE][JARGON]** — «E4a-2 wires none of this into the live IME — it is exercised only from tests…» — P1. Неверно: фича давно подключена (`PersonalDictionaries.create`, синки `PersonalLearning`). Замена: удалить абзац.
- `:120–162` (`forget` KDoc + тело B3) — **[OVERLONG][SLOP][AGENT]** — «B3. This was the one mutation whose body ran outside a `try` … The keyboard died in the middle of typing in someone else's app.» — P2. ~30 строк саги про баг. Замена: `// Тело в try: бросок deleteFile() на удалении последнего слова иначе убил бы IME (нет UncaughtExceptionHandler). Отказ идёт через outcome.` (2 строки).
- `:215–260` (`clearAll`) — **[OVERLONG][JARGON]** — «Three INDEPENDENT deletions. They used to share one try … B2 keeps a copy … B5. The "not told yet" mark…» — P2 — сжать до 3–4 строк, убрать `B2`/`B5`.
- `:262–330` (`report`, `open` KDoc) — **[SLOP]** — «Two problems, one shape. B3 closed the throw INSIDE each mutation, but every `outcome?.onFinished(...)` still sat outside…» — P2 — «Единственный способ ответить вызывающему; в try, т.к. бросок из callback убил бы worker. `succeeded` — аргумент, поэтому всегда вычисляется.».
- `:73,77,94,115,162–163,325,635` — **[JARGON][AGENT]** — маркеры `B5.`, `E4c:`, «E4b's "Add word…"», «(U7 of Phase 2, docs/ROADMAP-P2.md — the P1 pairs store pinned this rule first)», «S2 of docs/AUDIT-2026-08-31.md» — P3 — убрать коды/ссылки, оставить смысл.
- `:115–123` (`noteCompletion`) — **[SLOP]** — «The threshold is 3 for a reason worth keeping written down: 1 would learn any typo, 2 would learn a typo repeated twice…» — P3 — «Порог 3: одно/двукратное совпадение может быть повтором опечатки.».

### personalstore/PersonalBigramStore.kt (223/755) и PersonalEmojiStore.kt (237/757)

- **[DUP] крупный** — классовые KDoc и почти все приватные методы (`writeWhole`, `writeBytesDurably`, `saltOrCreate`, `readPending`, `deleted`, `report`, `open`, `load`, `quarantine`, `purgeFromQuarantine`, `deleteFile`, `cleanupTemps`, `createExclusiveTemp`, companion `QUARANTINE_SUFFIX`) — дословно повторяют `PersonalDictionaryStore`. P2. Правило: развернуть один канонический комментарий в `PersonalDictionaryStore`, в двух других заменить на «Зеркало [PersonalDictionaryStore].<член>; отличия: …» и оставить только отличия.
- `PersonalEmojiStore.kt:635` и `PersonalBigramStore.kt` (readPending) — **[JARGON]** — «(S2 of docs/AUDIT-2026-08-31.md)» ×3 (три стора) — P3 — убрать ссылку, оставить «past 2 GiB readBytes() бросает OutOfMemoryError, который catch не ловит».
- `PersonalEmojiStore.kt:? (companion `LEARN_THRESHOLD`)` — **[OVERLONG]** — «The same value the pairs store pins … by the analogue of its reasoning: the words store's third observation exists to filter accidental junk…» — P3 — «Порог 2, как у пар: слово из набранного текста, эмодзи — явный выбор с панели.».
- `pendingKey` KDoc (`PersonalEmojiStore`) — **[SLOP]** — «keying the PAIR, not the bare word, is what keeps «сәләм»→☀️ and «сәләм»→🌙 in two counters…» — P3 — оставить формулу `salt‖word‖0x00‖emoji` + 1 строку why.

### personalstore/PersonalDictionaries.kt / PersonalBigramDictionaries.kt / PersonalEmojiDictionaries.kt

- **[DUP]** — фасады повторяют друг друга (listener'ы, `consumeQuarantineNotice`, `sourceFor`, комментарий «The source itself is built in the `personal` package…»). P2 — свернуть повторяющийся абзац в один и ссылаться.
- `PersonalDictionaries.kt:? (quarantineListener)` — **[AGENT][JARGON]** — «The set is per LANGUAGE (2026-09-24 audit, finding 13): every language that lost something is owed its own notice…» — P2 — убрать дату/finding, оставить «по одной записи на язык».
- `PersonalBigramDictionaries.kt` и `PersonalEmojiDictionaries.kt` — «(2026-09-24 audit, F13)», «(2026-09-24 audit, finding 13)» ×3 — **[AGENT]** — P3 — удалить.
- `PersonalEmojiDictionaries.kt:42–46` — **[AGENT][SLOP]** — «wired HERE rather than added to [AndroidPersonalDictionaryStorage] because this feature layer was built without touching the existing factory» — P3 — «Обвязка тут (а не в фабрике): те же сиды, что и у слов/пар.».

### personalstore/PersonalQuarantineSalvage.kt + PersonalBigram… + PersonalEmoji… (тройка)

- **[DUP] крупный** — три файла почти идентичны (KDoc, `read`, `readAtMost`, `decodeSubtypeTag`, `decodeStrictUtf8`, `compareUnsigned`, `NOTHING`). P2 — оставить полный комментарий в `PersonalQuarantineSalvage`, в двух других — «Аналог [PersonalQuarantineSalvage] для пар/эмодзи».
- KDoc `read` (во всех трёх) — **[OVERLONG]** — «The stored checksum is deliberately NOT consulted. A truncated write is the ordinary way this file breaks…» ~10 строк — P2 — «Контрольную сумму не проверяем (усечение её и рушит): полагаемся на пер-запись + порядок ключей как детектор рассинхрона. Стоп на первой битой записи.» (2 строки).

### personalstore/PersonalLearning.kt / PersonalBigramLearning.kt / PersonalEmojiLearning.kt

- **[DUP]** — «The subtype is resolved per event, not per sink … writing it to the Tatar one would put Russian words into Tatar suggestions for good» повторяется в трёх файлах дословно (с заменой слово/пара/эмодзи). P2 — один канон + «то же правило, что у [PersonalLearning]».
- `PersonalLearning.kt:? (onAcceptedSuggestion)` — **[AGENT]** — «The acceptance bump is a write like any other (2026-09-24 audit, finding 2)» — P3 — убрать «(… finding 2)».
- `PersonalLearningGates.kt:? / PersonalLearning.kt` — **[JARGON]** — маркеры `U8`, «in the established style of `PersonalDictionaryRestriction`» — P3 — убрать `U8`, оставить «инкогнито ставит запись на паузу».

### personalstore/AndroidPersonalDictionaryStorage.kt

- `:38–39` — **[STALE][JARGON]** — «Dormant in E4a-2: nothing in the live IME constructs this. Learning, the merge and the settings toggle that will call it are E4b/E4c.» — **P1**. Ложь: `PersonalDictionaries.storeFor` вызывает `create()` (grep подтверждён). Замена: удалить абзац. ⚠ файл пинится `PersonalStoragePathSourceContractTest` и `PersonalStorePrivacyTest`, но по коду (`noBackupFilesDir`, `PERSONAL_DIRECTORY_NAME`, `isUserUnlocked`, отсутствие `createDeviceProtectedStorageContext`) — правка комментария безопасна, если не трогать эти токены.
- `:20–27` — **[SLOP][JARGON]** — «The claim "a device-protected context is created in exactly two seams (AndroidDictionaryStorageFactory and PreferenceManagerCompat)" stays true.» — P2 — оставить 1–2 строки: почему credential-protected `noBackupFilesDir` (расшифровка после PIN, вне бэкапа).

### personalstore/PendingCounters.kt

- `:? (KDoc класса)` — **[JARGON]** — «stated plainly here and in docs/DICTIONARY-E4.md» — P3 — убрать ссылку.
- `MAX_SERIALIZED_BYTES` KDoc — **[OVERLONG]** — «(S2 of docs/AUDIT-2026-08-31.md) … a file over 2 GiB would not even fit one, and the resulting `OutOfMemoryError`…» — P3 — сжать до 2 строк, убрать `S2`/ссылку. В целом файл нормальный.

### personalstore/PersonalStorageSeams.kt (86/120 — 72 %) / PersonalWordFilter / PersonalBigramWordFilter / PersonalForget / PersonalLearningGates

- `PersonalStorageSeams.kt` — **[SLOP][OVERLONG]** — сиды переобъяснены («sharing one provider would put "erase the personal dictionary" and "clear the recent emoji" next to each other in code with no reason»). P2 — каждый `fun interface` до 1–2 строк назначения.
- `PersonalBigramContextMembership` KDoc (там же) — **[JARGON]** — «P1 of Phase 2 (docs/ROADMAP-P2.md)» — P3 — убрать.
- `PersonalWordFilter`/`PersonalBigramWordFilter` — **[DUP]** — тела и KDoc почти идентичны; отличие только 3..24 vs 1..24. P3 — во втором «Аналог [PersonalWordFilter]; окно 1..24 (однобуквенное слово — легальный член пары)».
- `PersonalForget.kt:? (confirmForget)` — **[OVERLONG][SLOP]** — «That used to be entirely silent, and it was the worst possible kind of silent, because the dialog had already closed…» — P3 — «`onFailed` — если слово всё ещё сохранено (перезапись файла упала); зовётся на worker, без слова/пути.».

### personal/ модели и форматы

- `TpersFormat.kt:54`, `:81`, `:84`, `:97` — **[JARGON][AGENT]** — «("Контракт текста" amendment of 2026-07-27, four points, owned by E4a-1)», «(E4a-2 limit…)» — P3 — убрать даты/коды, суть оставить. Таблицы layout полезны — не трогать.
- `TpersbFormat.kt`/`TpersemFormat.kt` — **[DUP][JARGON]** — «deliberate sibling of `.tpers`/`.tpersb` … a reader written for one must never even open the other» повторяется; «(P1 of Phase 2, docs/ROADMAP-P2.md)». P3 — убрать коды миссий; во втором/третьем сжать общий абзац до «Сиблинг [TpersFormat]; отличия ниже».
- `PersonalSubtypes.kt` (78 % комм.) — **[OVERLONG][JARGON]** — «Ownership … moved to E4a-1 after phase D2 was cancelled (see PROPOSALS.md …) … The literal "tt_RU" used to live twice, in `SuggestionsController.SUBTYPE_ID` and … kept in sync by hand; then [alphabetFor]…» — P2. История рефакторинга вместо назначения. Замена: «Константы subtype и алфавиты языков. `alphabetFor` — чистый lookup в реестре `DictionaryArtifactSpec` (один список языков). Null-алфавит = фича выключена для subtype.». ⚠ `PersonalSubtypeRegistryContractTest`/`PersonalSubtypeSeamTest` пинят `TATAR_RU`/`RUSSIAN`/`*_ALPHABET` и `alphabetFor` — это код, комментарии не пинятся.
- `PersonalDictionary.kt:? (KDoc класса)` — **[STALE-мягкий][JARGON]** — «This class writes nothing to disk — E4a-1 is a read-only path; atomic writing and LRU eviction are E4a-2.» — P3 — E4a-2 сделано; «Только чтение; запись и LRU — в пакете store.».
- `PersonalCandidateSource.kt` (`containsNormalized`, `glideSnapshot`) — **[JARGON]** — «the membership test D3 needs», «(docs/GLIDE-PERSONAL.md)», «written before D3 keeps compiling» — P3 — убрать `D3`/ссылку.
- `TpersValidator`/`TpersbValidator`/`TpersemValidator` — **[DUP]** — приватные хелперы (`decodeSubtypeTag`, `digestWithChecksumZeroed`, `decodeStrictUtf8`, `isCombiningMark`, `compareUnsigned`, `fail`) и вводные KDoc повторяются во всех трёх. P3 — коротко «Аналог [TpersValidator]».
- `PersonalDictionary.kt / PersonalBigramDictionary.kt / PersonalEmojiDictionary.kt / PersonalEntries.kt / PersonalBigramEntries.kt / PersonalEmojiEntries.kt / *QuarantineSalvage / *Source / Validated*` — **[DUP]** — фраза «NOT a Kotlin data class … a synthesised toString would print them at the first interpolation» встречается ~13 раз почти дословно. P3. ⚠ Оставить смысл (это осознанное privacy-правило, пинится `PersonalStorePrivacyTest.noTypeCarryingAWordIsADataClass` по факту отсутствия `data class`, не по тексту). Свернуть до «Не data class: несёт слова пользователя (см. privacy-правило пакета).».

### glide/

- `GlideDecoder.kt` (32 %) — **[OVERLONG][JARGON]** — `:30,39,54,76,96,123,158,282,319,321,342,363` — коды `P7-1/P7-4/P7-8` и датированные «field report» (`the 2026-09-25 field report "сәләм typed, сәлләм committed"`). P2. Атрибуцию к AnySoftKeyboard/FlorisBoard **оставить** (лицензионно полезна). Убрать `P7-x`/даты/`docs/ROADMAP-P7.md`; напр. `:39` и `:282` — «Удвоенная буква требует петли в пути пользователя: без неё plain-вариант совпадает с неудвоенным близнецом и частота решает неверно.» (2 строки вместо абзаца-расследования).
- `GlideGestureDecider.kt:124` — **[WEIRD][STALE]** — висячий `/** FlorisBoard's distance threshold: one key width… */` без члена (порог — параметр конструктора). P1 — удалить строку.
- `GlideGestureDecider.kt:36–41` — **[LANG][OVERLONG]** — русская фраза внутри английского KDoc: «"зажимаю букву и начинаю вести её в сторону второй — не работает"» + «(the 2026-09-24 field report, see docs/ROADMAP-P7.md)». P2 — убрать цитату/дату/ссылку, оставить: «Окно и скорость якорятся на первом сэмпле за слопом, а не на touch-down: иначе пауза на первой клавише навсегда отклоняет глайд.».
- `GlideComputer.kt:? (GlideIndexReleaser)` — **[JARGON][AGENT]** — «(O2, docs/OPTIMIZE-2026-09-25.md): … ~6 MB of pure derivation» — P3 — убрать `O2`/ссылку/размер, оставить «сбрасывает лениво построенный индекс, когда клавиатура давно скрыта».
- `GlidePath.kt:? (addPoint)` — **[AGENT]** — «2026-09-25 audit, F14: a NaN/Infinity coordinate would poison…» — P3 — убрать «2026-09-25 audit, F14:», оставить суть (NaN/Inf → отказ, fail-closed).
- `GlideWordIndex.kt` — в целом хорошо; **[JARGON]** мелкие «(P7-4 device profile)», «pool/poll trick» — P3 — можно оставить или сжать.
- `GlideKeyGeometry.kt`, `GlideResampler.kt`, `GlideIdealPaths.kt`, `GlideWordInventory.kt`, `CompositeGlideInventory.kt` — комментарии по делу; точечно убрать `P7-4`/`docs/GLIDE-PERSONAL.md` (`GlideResampler.kt` normalizeByBoxSide, `CompositeGlideInventory` KDoc). P3.

## Системные паттерны

1. **Коды миссий и ссылки на доки** — самый массовый шум. grep-маркеры для чистки:
   `\b(D1[ab]|E4[abc]|E4a-[12]|E5[bc]|P1\b|P3b|P4\b|P7-[0-9]|T[257]\b|U[78]\b|B[2-5]\.|S2\b|SIZE-[12]|EXPAND-1)\b`
   и `docs/[A-Za-z0-9./-]+\.md`, и `PROPOSALS\.md`, и `"Контракт текста"`.
   Правило: код миссии и ссылку на `docs/*.md` из комментария убрать (доки архивируются → ссылки битые), оставить одну строку назначения на английском.
2. **Датированные сноски и «audit findings»** — grep: `20\d\d-\d\d-\d\d` и `\b(finding|audit|F1[0-9]|F[0-9])\b` в комментариях. Правило: удалять дату/номер находки; если правка описывает поведение — переписать в настоящем времени без ссылки на баг/дату.
3. **Истории перепаковок ассетов** (два контракта) — многоабзацные «Repacked 20xx …» с процентами и SHA. Правило: одна строка назначения; числа-пины остаются в полях `expected*` (их и проверяют тесты), в комментарии их дублировать не нужно.
4. **Тройное дублирование `Personal{Dictionary,Bigram,Emoji}*`** — Store/Entries/Dictionaries/Learning/QuarantineSalvage/Source/Validator/Format. Правило: полный комментарий держать в «словном» файле (`PersonalDictionaryStore`, `PersonalEntries`, `TpersFormat`, `TpersValidator`, `PersonalQuarantineSalvage`, `PersonalDictionaries`, `PersonalLearning`), в биграм/эмодзи-сиблингах — одна строка «Зеркало [X]; отличия: …».
5. **Повторяемая privacy-мантра** «NOT a Kotlin data class … first interpolation» (~13 мест) — свернуть до «Не data class: несёт слова пользователя (privacy-правило пакета).». Смысл сохранить (пинится фактом отсутствия `data class`, не текстом).
6. **Storytelling/риторика** в `PersonalDictionaryStore`/`PersonalEmojiStore`/`PersonalBigramStore`/`GlideDecoder` — «The keyboard died…», «the worst possible kind of silent», «Erased means erased», «Two problems, one shape». Правило: заменять на «что делает и почему» в 1–2 строки императивом.
7. **Смешение RU/EN** в англоязычных комментариях (`GlideGestureDecider`, `AtomicBigramStore`, `BigramStorageContracts`, `TdictFormat`) — цитаты PROPOSALS/полевых отчётов по-русски. Правило: убрать русскую цитату либо перевести; не смешивать в одной строке.
8. **Безопасность правок**: source-contract тесты пинят идентификаторы/структуру, не прозу — комментарии чистить можно свободно; исключения помечены ⚠ выше (не вводить `data class`, `createDeviceProtectedStorageContext`, `android.util.Log`, `DeviceProtectedDirectoryProvider` в `personalstore`; кириллица в комментариях `glide` допустима).
