# Политика конфиденциальности / Privacy Policy

**Версия / Version:** 1.5 — 2026-09-23

## English

Tatar Keyboard keeps what you type **on your device**.

- The app's manifest contains **no INTERNET permission** — the app itself sends nothing to any server. This is verifiable: run `aapt2 dump permissions` on the APK, or check the CI gate that validates both the source manifest and the built APK on every commit. The only permission the app uses is VIBRATE (haptic feedback on key press).
- Everything you type is processed **on your device only**. The keyboard works **fully offline**.
- There are **no analytics, no advertising SDKs, no Firebase**, and no third‑party trackers of any kind.
- **Nothing is backed up off your device.** The app sets `android:allowBackup="false"`, and its backup rules exclude every app‑data domain — files, settings, databases, external storage, regular and device‑protected alike — from both the cloud backup and the transfer to a new device. This is verified by CI on the manifest **and** on the built APK, the same way the INTERNET check is. One consequence, stated plainly: your keyboard settings are **not** restored on a new device or brought back from a backup — you set them again.
- The source code is open under the Apache License 2.0 — anyone can audit these claims.

### Recently used emoji

So the emoji panel can show them first, it remembers **up to 24 recently used emoji**.

- **Where.** They are stored in an internal app folder that is **decrypted only after you enter your device's lock code** (PIN, pattern or password).
- **Before the device is unlocked** for the first time after a restart, this list is **not read and not added to** — there simply is no “Recent” tab until you unlock.
- **Excluded from backup.** This list lives in an internal “no‑backup” folder that Android does **not** include in a cloud backup or in a transfer to a new device.
- **Not everywhere.** Nothing is remembered in **password fields or other private fields** — e‑mail, URL, and any field that asks the keyboard not to show suggestions or not to personalize (for example an incognito browser tab or a banking app).
- **How to erase it.** Open the keyboard settings and tap **“Clear recent emoji.”** To remove it together with everything else, delete the app's data in the system settings — that also removes the unpacked dictionaries and prediction tables the app rebuilds on next use.

### Word and emoji suggestions

The word-completion dictionaries, the next-word prediction tables and the “word → emoji” table are **shipped inside the app** and unpacked to an internal app folder on first use.

- **Size.** Two unpacked dictionaries (1,162,870 and 1,151,323 bytes) and two prediction tables (134,664 and 131,662 bytes) — about 2.6 MB in total. The emoji lookup tables add a few hundred KB packed and store nothing about you.
- **What they record about you: nothing.** Suggestions, predictions and emoji suggestions are pure read-only lookups against these bundled tables. They keep no history, no counters, no copy of what you type. The emoji suggestion that appears in the strip reads only the word you just finished, on the device, and is gone from memory with it. In password and other private fields neither word suggestions nor emoji suggestions appear at all.
- **Two versions kept after an update.** When a new app version brings a new dictionary or table, the previous unpacked copy is kept next to the new one for a while, so the update can be rolled back; an old version is removed on later launches. At most two versions of each artifact are on the device at any time.
- **Excluded from backup**, like everything else.

### Personal dictionary

If you turn the **personal dictionary** on (it is **off** unless you turn it on), the keyboard saves words you type so it can suggest them later.

- **What is saved.** The word itself, spelled the way you typed it, plus two numbers: how often it has been used and when it was last used, as a counter — not a clock time. **Nothing else**: not the sentence around it, not the app you typed it in, not the field, not the date.
- **Where.** In an internal app folder that is **decrypted only after you enter your device's lock code** (PIN, pattern or password), separately for each keyboard language. **Before the first unlock** after a restart, nothing there is read or written.
- **It never leaves your device**, and it is excluded from cloud backup and from the transfer to a new device — there is no export, no import and no sync, in this version or any planned one.
- **Not everywhere.** Nothing is saved in **password fields or other private fields** — e-mail, URL, postal address, and any field that asks the keyboard not to show suggestions or not to personalize (an incognito tab, a banking app).
- **How to see and erase it.** Keyboard settings → **“Saved words”**: every saved word of every language, with **“Delete”** on each one and **“Erase all saved words.”** The same screen also lists the learned **word pairs** (see the next section), and the erase-everything action there covers both. Turning the personal dictionary off does **not** erase what was already saved — erase it here. Deleting the app, or clearing its data in the system settings, destroys everything saved, and no backup brings it back.
- **If a saved-words file cannot be read, it is not destroyed.** The keyboard moves it aside into a quarantine copy (a `*.tpers.quarantine` file next to the dictionary), tells you once that the words could not be read, and keeps the copy on the device. On the **“Saved words”** screen a card appears for that language: **“Restore words”** puts back every word that survived (the card says how many, and warns if the tail of the copy is damaged beyond recovery); **“Delete copy”** removes the quarantine file. Nothing in it is ever uploaded anywhere.
- **A limit worth knowing, on Android 7 only.** The standard signal an app uses to say "do not learn from this field" (`IME_FLAG_NO_PERSONALIZED_LEARNING`) exists from Android 8 onwards. On Android 7.0 and 7.1 no app sets it, so on those two versions the keyboard cannot tell such a field apart, and only the other gates above (password and private field types, postal addresses, the off-by-default switch itself) protect it. Every other guarantee on this page holds on all supported versions.
- **The screen is not behind a separate password.** Anyone holding your unlocked phone can read the list of saved words. `FLAG_SECURE` keeps it out of screenshots and out of the recent-apps thumbnail, but it cannot keep out a person standing next to you. This is a deliberate trade-off, stated rather than left unsaid.

### Personal word pairs

If the personal dictionary is on, the keyboard can also learn **which word follows which** — after you type the same word pair cleanly twice, the second word starts appearing in the suggestions after the first one.

- **What is saved, per pair.** The first word (the context) in its **normalized form only** (lowercased, accent-folded — it is used for matching and is never displayed), the second word (the successor) **exactly as you typed it** (it is the cell you will see), and three counters: how often the pair was typed, how often its suggestion was tapped, and a last-use counter — again a counter, not a clock time. **Nothing else**: not the sentence, not the app, not the field, not the date.
- **Until a pair earns its place, it does not exist in plaintext anywhere.** One observation writes nothing but a salted truncated SHA-256 hash of the pair (`pending-bigrams-…-s1-f1.bin` next to the store, the salt in the store's own `salt-bigrams.bin` — 16 random bytes created on first use and destroyed by erase-all). Only the second clean observation writes the pair itself, to `personal-bigrams-…-s1-f1.tpersb` — at most 1 000 pairs per language, the least-used evicted silently.
- **Where.** The same credential-protected `no_backup/` folder as the saved words, separately per language; before the first unlock after a restart nothing is read or written; never backed up, exported or synced, in this version or any planned one.
- **How it ranks.** Static prediction tables always come first; a learned pair may only take a cell the static table left free (at most two of the three cells), and a pair duplicating a table prediction is never shown twice.
- **Not everywhere**, exactly like single words: password and other private fields, fields asking not to personalize, postal addresses — and everywhere while **incognito mode** is on (below).
- **How to see and erase it.** Keyboard settings → **“Saved words”**: each language has a pairs card with every learned pair, **“Delete”** on each one and **“Clear all pairs.”** The screen's erase-everything action destroys **both** the words and the pairs stores, their pending-hash files and salts. Deleting the app or clearing its data destroys everything.
- **If a pairs file cannot be read, it is not destroyed.** It is moved aside into a quarantine copy (`*.tpersb.quarantine`), you are told once, and the “Saved words” screen shows a card for it with **“Restore pairs”** (puts back what survived — the card says how many) and **“Delete copy.”** Deleting a pair also removes it from the quarantine copy, so a restore can never bring a deleted pair back.

### Incognito mode

A single switch (keyboard settings → Preferences → **“Incognito mode”**, off by default) pauses **all** personal learning.

- **What pauses.** No new word and no new pair is written to either store, and the pending-hash counters of both stores are not touched — nothing is hashed, nothing expires, the counters simply wait.
- **What does not pause.** What you already saved keeps working: saved words and saved pairs still appear in suggestions (this is a pause, not a wipe — the separate personal-dictionary switch is the one that hides them).
- **Turning it off** resumes learning exactly where it stopped; nothing that was typed while it was on is learned retroactively — during the pause those observations never existed for the stores.

## По-русски

Tatar Keyboard хранит то, что вы печатаете, **на вашем устройстве**.

- В манифесте приложения **нет разрешения INTERNET** — само приложение ничего никуда не отправляет. Это проверяемо: команда `aapt2 dump permissions` по APK, плюс CI‑гейт проверяет манифест и собранный APK на каждом коммите. Единственное используемое разрешение — VIBRATE (вибрация при нажатии клавиш).
- Всё, что вы печатаете, обрабатывается **только на вашем устройстве**. Клавиатура работает **полностью офлайн**.
- **Нет аналитики, нет рекламных SDK, нет Firebase** и никаких сторонних трекеров.
- **Ничего не уходит в резервную копию.** Приложение выставляет `android:allowBackup="false"`, а его правила бэкапа исключают все домены данных приложения — файлы, настройки, базы, внешнее хранилище, и обычные, и device‑protected — и из облачной резервной копии, и из переноса на новое устройство. Это проверяет CI по манифесту **и** по собранному APK — так же, как проверку INTERNET. Прямое следствие: настройки клавиатуры на новом устройстве и из резервной копии **не** восстанавливаются — вы задаёте их заново.
- Исходный код открыт под лицензией Apache‑2.0 — любой может убедиться в этих утверждениях сам.

### Недавно использованные эмодзи

Чтобы панель эмодзи показывала их первыми, она запоминает **до 24 недавно использованных эмодзи**.

- **Где.** Они хранятся во внутренней папке приложения, которая **расшифровывается только после ввода кода блокировки устройства** (PIN, графический ключ или пароль).
- **До разблокировки** устройства после перезагрузки этот список **не читается и не пополняется** — вкладки «Недавние» до разблокировки просто нет.
- **Исключено из бэкапа.** Этот список лежит во внутренней «no‑backup» папке, которую Android **не** включает ни в облачную резервную копию, ни в перенос на новое устройство.
- **Не везде.** Ничего не запоминается в **полях пароля и других приватных полях** — e‑mail, URL и любое поле, которое просит клавиатуру не показывать подсказки или не персонализироваться (например, вкладка браузера в режиме инкогнито или банковское приложение).
- **Как стереть.** Откройте настройки клавиатуры и нажмите **«Очистить недавние эмодзи»**. Чтобы удалить вместе со всем остальным — удалите данные приложения в системных настройках; это заодно удалит распакованные словари и таблицы предсказаний, которые приложение соберёт заново при следующем использовании.

### Подсказки слов и эмодзи

Словари подсказок, таблицы предсказания следующего слова и таблица «слово → эмодзи» **поставляются внутри приложения** и распаковываются во внутреннюю папку при первом использовании.

- **Размер.** Два распакованных словаря (1 162 870 и 1 151 323 байта) и две таблицы предсказаний (134 664 и 131 662 байта) — около 2,6 МБ суммарно. Таблицы эмодзи добавляют несколько сотен КБ в упакованном виде и ничего о вас не хранят.
- **Что они о вас записывают: ничего.** Подсказки, предсказания и эмодзи-подсказки — это чистые чтения из встроенных таблиц. Никакой истории, счётчиков, копий набранного. Эмодзи-подсказка в полосе читает только что завершённое слово, на устройстве, и исчезает из памяти вместе с ним. В полях паролей и других приватных полях не показываются ни подсказки слов, ни подсказки эмодзи.
- **Две версии после обновления.** Когда новая версия приложения приносит новый словарь или таблицу, прежняя распакованная копия некоторое время лежит рядом с новой — чтобы обновление можно было откатить; старая версия удаляется при последующих запусках. Одновременно на устройстве не больше двух версий каждого артефакта.
- **Исключены из бэкапа**, как и всё остальное.

### Личный словарь

Если вы включите **личный словарь** (по умолчанию он **выключен**), клавиатура сохраняет набранные вами слова, чтобы предлагать их позже.

- **Что сохраняется.** Само слово в том написании, в котором вы его набрали, и два числа: сколько раз оно использовалось и когда использовалось в последний раз — счётчиком, а не временем по часам. **Больше ничего**: ни окружающего предложения, ни приложения, в котором вы печатали, ни поля, ни даты.
- **Где.** Во внутренней папке приложения, которая **расшифровывается только после ввода кода блокировки устройства** (PIN, графический ключ или пароль), отдельно для каждого языка клавиатуры. **До первой разблокировки** после перезагрузки там ничего не читается и не пишется.
- **Это никогда не покидает ваше устройство** и исключено из облачной резервной копии и переноса на новое устройство — ни экспорта, ни импорта, ни синхронизации нет ни в этой версии, ни в планах.
- **Не везде.** Ничего не сохраняется в **полях пароля и других приватных полях** — e-mail, URL, почтовый адрес и любое поле, которое просит клавиатуру не показывать подсказки или не персонализироваться (вкладка инкогнито, банковское приложение).
- **Как посмотреть и стереть.** Настройки клавиатуры → **«Сохранённые слова»**: все сохранённые слова всех языков, у каждого — **«Удалить»**, и отдельно **«Стереть все сохранённые слова»**. На том же экране перечислены и выученные **пары слов** (следующий раздел), а действие «стереть всё» покрывает оба хранилища. Выключение личного словаря **не** удаляет накопленное — стирать нужно здесь. Удаление приложения или «стереть данные» в системных настройках уничтожает всё накопленное безвозвратно, и восстановление из резервной копии его не вернёт.
- **Если файл сохранённых слов не читается, он не уничтожается.** Клавиатура откладывает его в карантинную копию (файл `*.tpers.quarantine` рядом со словарём), один раз сообщает, что слова не удалось прочитать, и хранит копию на устройстве. На экране **«Сохранённые слова»** появляется карточка этого языка: кнопка **«Вернуть слова»** возвращает всё, что уцелело (карточка называет число слов и честно предупреждает, если конец копии повреждён безвозвратно); кнопка **«Удалить копию»** стирает карантинный файл. Ничего из него никуда не отправляется.
- **Ограничение, о котором стоит знать, — только для Android 7.** Стандартный сигнал, которым приложение просит клавиатуру не запоминать набранное в поле (`IME_FLAG_NO_PERSONALIZED_LEARNING`), существует начиная с Android 8. На Android 7.0 и 7.1 его не выставляет ни одно приложение, поэтому на этих двух версиях клавиатура не может отличить такое поле, и его защищают только остальные перечисленные выше условия (поля пароля и другие приватные типы полей, почтовые адреса и сам выключенный по умолчанию тумблер). Все прочие гарантии этой страницы действуют на всех поддерживаемых версиях.
- **Экран не защищён отдельным паролем.** Любой, у кого в руках ваш разблокированный телефон, увидит список сохранённых слов. `FLAG_SECURE` закрывает его от скриншота и от миниатюры «недавних приложений», но не от человека рядом. Это осознанный компромисс, и он назван, а не умолчан.

### Личные пары слов

Если личный словарь включён, клавиатура может учить и **какое слово за каким следует** — после того как вы дважды чисто набрали одну и ту же пару, второе слово начинает появляться в подсказках после первого.

- **Что сохраняется о паре.** Первое слово (контекст) **только в нормализованном виде** (в нижнем регистре, со свёрнутыми диакритиками — оно нужно для сопоставления и нигде не показывается), второе слово (продолжение) **ровно так, как вы его набрали** (именно его вы увидите в ячейке), и три счётчика: сколько раз пару набрали, сколько раз по её подсказке нажали, и счётчик последнего использования — снова счётчик, а не время по часам. **Больше ничего**: ни предложения, ни приложения, ни поля, ни даты.
- **Пока пара не заслужила своё место, её нигде нет в открытом виде.** Одно наблюдение записывает лишь солёный усечённый SHA-256-хеш пары (файл `pending-bigrams-…-s1-f1.bin` рядом с хранилищем; соль — собственные `salt-bigrams.bin` хранилища, 16 случайных байт, создаются при первом использовании и уничтожаются стиранием всего). Только второе чистое наблюдение записывает саму пару — в `personal-bigrams-…-s1-f1.tpersb`; не больше 1 000 пар на язык, редко используемые молча вытесняются.
- **Где.** В той же credential-protected «no_backup» папке, что и сохранённые слова, отдельно для каждого языка; до первой разблокировки после перезагрузки ничего не читается и не пишется; никогда не копируется в бэкап, не экспортируется и не синхронизируется — ни в этой версии, ни в планах.
- **Как это ранжируется.** Статические таблицы предсказаний всегда первыми; выученная пара может занять лишь свободную ячейку (не больше двух из трёх), а пара, совпадающая с предсказанием таблицы, не показывается дважды.
- **Не везде** — в точности как с отдельными словами: поля паролей и другие приватные поля, поля, просящие не персонализироваться, почтовые адреса — и всегда, пока включён **режим инкогнито** (ниже).
- **Как посмотреть и стереть.** Настройки клавиатуры → **«Сохранённые слова»**: у каждого языка есть карточка пар со всеми выученными парами, у каждой — **«Удалить»**, и отдельно **«Очистить все пары»**. Действие «стереть всё» на экране уничтожает **оба** хранилища — и слов, и пар, — вместе с файлами ожидающих хешей и солями. Удаление приложения или «стереть данные» уничтожает всё.
- **Если файл пар не читается, он не уничтожается.** Он откладывается в карантинную копию (`*.tpersb.quarantine`), вы один раз об этом узнаёте, а на экране **«Сохранённые слова»** появляется карточка с кнопками **«Вернуть пары»** (возвращает всё уцелевшее — карточка называет число) и **«Удалить копию»**. Удаление пары стирает её и из карантинной копии, так что восстановление не может вернуть удалённую пару.

### Режим инкогнито

Один переключатель (настройки клавиатуры → «Настройки» → **«Режим инкогнито»**, по умолчанию выключен) приостанавливает **всё** личное обучение.

- **Что приостанавливается.** Ни новое слово, ни новая пара не записываются ни в одно хранилище, а счётчики ожидающих хешей обоих хранилищ не трогаются — ничего не хешируется, ничего не истекает, счётчики просто ждут.
- **Что НЕ приостанавливается.** Уже сохранённое продолжает работать: сохранённые слова и пары по-прежнему появляются в подсказках (это пауза, а не стирание — скрыть их умеет отдельный выключатель личного словаря).
- **После выключения** обучение продолжается ровно с того места, где остановилось; набранное за время паузы задним числом не доучивается — для хранилищ этих наблюдений просто не было.

## Контакт / Contact

Вопросы по приватности — через Issues репозитория проекта. / Privacy questions — via the project repository's Issues.
