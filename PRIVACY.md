# Политика конфиденциальности / Privacy Policy

**Версия / Version:** 1.7 — 2026-09-28

## English

Tatar Keyboard keeps what you type **on your device**.

- The app's manifest contains **no INTERNET permission** — the app itself sends nothing to any server. This is verifiable: run `aapt2 dump permissions` on the APK, or check the CI gate that validates both the source manifest and the built APK on every commit. The only permission the app uses is VIBRATE (haptic feedback on key press).
- Everything you type is processed **on your device only**. The keyboard works **fully offline**.
- The clipboard is read **only when you tap the paste key** — never in the background, and what is on the clipboard is never stored by the keyboard.
- There are **no analytics, no advertising SDKs, no Firebase**, and no third‑party trackers of any kind.
- **Nothing is backed up off your device.** The app sets `android:allowBackup="false"`, and its backup rules exclude every app‑data domain — files, settings, databases, external storage, regular and device‑protected alike — from both the cloud backup and the transfer to a new device. This is verified by CI on the manifest **and** on the built APK, the same way the INTERNET check is. One consequence, stated plainly: your keyboard settings are **not** restored on a new device or brought back from a backup — you set them again.
- The source code is open under the Apache License 2.0 — anyone can audit these claims.

### Recently used emoji

So the emoji panel can show them first, it remembers **up to 24 recently used emoji** — every emoji you insert counts, whether picked in the panel, found through the search, or tapped in the suggestion strip.

- **Where.** They are stored in an internal app folder that is **decrypted only after you enter your device's lock code** (PIN, pattern or password).
- **Before the device is unlocked** for the first time after a restart, this list is **not read and not added to** — there simply is no “Recent” tab until you unlock.
- **Excluded from backup.** This list lives in an internal “no‑backup” folder that Android does **not** include in a cloud backup or in a transfer to a new device.
- **Not everywhere.** Nothing is remembered in **password fields or other private fields** — e‑mail, URL, and any field that asks the keyboard not to show suggestions or not to personalize (for example an incognito browser tab or a banking app).
- **How to erase it.** Open the keyboard settings and tap **“Clear recent emoji.”** To remove it together with everything else, delete the app's data in the system settings — that also removes the unpacked dictionaries and prediction tables the app rebuilds on next use.

### Word and emoji suggestions

The word-completion dictionaries, the next-word prediction tables and the “word → emoji” table are **shipped inside the app** and unpacked to an internal app folder on first use.

- **Size.** Two unpacked dictionaries (1,276,289 and 1,151,323 bytes) and two prediction tables (170,471 and 131,662 bytes) — about 2.7 MB in total. The emoji lookup tables add a few hundred KB packed and store nothing about you.
- **What they record about you: nothing.** Suggestions, predictions and emoji suggestions are pure read-only lookups against these bundled tables. They keep no history, no counters, no copy of what you type. The emoji suggestion that appears in the strip reads only the word you just finished, on the device, and is gone from memory with it. In password and other private fields neither word suggestions nor emoji suggestions appear at all.
- **Two versions kept after an update.** When a new app version brings a new dictionary or table, the previous unpacked copy is kept next to the new one for a while, so the update can be rolled back; an old version is removed on later launches. At most two versions of each artifact are on the device at any time.
- **Excluded from backup**, like everything else.

### Personal dictionary

If you turn the **personal dictionary** on (it is **off** unless you turn it on), the keyboard saves words you type so it can suggest them later.

- **What is saved.** The word itself, spelled the way you typed it, plus two numbers: how often it has been used and when it was last used, as a counter — not a clock time. **Nothing else**: not the sentence around it, not the app you typed it in, not the field, not the date.
- **Until a word earns its place, it does not exist in plaintext anywhere.** The first and the second clean completion of a word write nothing but a salted truncated SHA-256 hash of it (`pending-<subtype>-s1-f1.bin` next to the store, the salt in the store's own `salt.bin` — 16 random bytes created on first use and destroyed by erase-all). Only the third clean completion writes the word itself, to `personal-<subtype>-s1-f1.tpers` — at most 2,000 words per language, the least-used evicted silently.
- **Where.** In an internal app folder that is **decrypted only after you enter your device's lock code** (PIN, pattern or password), separately for each keyboard language. **Before the first unlock** after a restart, nothing there is read or written.
- **It never leaves your device**, and it is excluded from cloud backup and from the transfer to a new device — there is no export, no import and no sync, in this version or any planned one.
- **Not everywhere.** Nothing is saved in **password fields or other private fields** — e-mail, URL, postal address, and any field that asks the keyboard not to show suggestions or not to personalize (an incognito tab, a banking app).
- **How to see and erase it.** Keyboard settings → **“Saved words”**: every saved word of every language, with **“Delete”** on each one and **“Erase everything saved.”** The same screen also lists the learned **word pairs** and the learned **word → emoji** pairs (see the next sections), and the erase-everything action there covers all three. Turning the personal dictionary off does **not** erase what was already saved — erase it here. Deleting the app, or clearing its data in the system settings, destroys everything saved, and no backup brings it back.
- **If a saved-words file cannot be read, it is not destroyed.** The keyboard moves it aside into a quarantine copy (a `*.tpers.quarantine` file next to the dictionary), tells you once that the words could not be read, and keeps the copy on the device. On the **“Saved words”** screen a card appears for that language: **“Bring the words back”** puts back every word that survived (the card says how many, and warns if the tail of the copy is damaged beyond recovery); **“Delete the copy”** removes the quarantine file. Nothing in it is ever uploaded anywhere.
- **A limit worth knowing, on Android 7 only.** The standard signal an app uses to say "do not learn from this field" (`IME_FLAG_NO_PERSONALIZED_LEARNING`) exists from Android 8 onwards. On Android 7.0 and 7.1 no app sets it, so on those two versions the keyboard cannot tell such a field apart, and only the other gates above (password and private field types, postal addresses, the off-by-default switch itself) protect it. Every other guarantee on this page holds on all supported versions.
- **The screen is not behind a separate password.** Anyone holding your unlocked phone can read the list of saved words. `FLAG_SECURE` keeps it out of screenshots and out of the recent-apps thumbnail, but it cannot keep out a person standing next to you. This is a deliberate trade-off, stated rather than left unsaid.

### Personal word pairs

If the personal dictionary is on, the keyboard can also learn **which word follows which** — after you type the same word pair cleanly twice, the second word starts appearing in the suggestions after the first one.

- **What is saved, per pair.** The first word (the context) in its **normalized form only** (lowercased, accent-folded — it is used for matching and is never displayed), the second word (the successor) **exactly as you typed it** (it is the cell you will see), and three counters: how often the pair was typed, how often its suggestion was tapped, and a last-use counter — again a counter, not a clock time. **Nothing else**: not the sentence, not the app, not the field, not the date.
- **Until a pair earns its place, it does not exist in plaintext anywhere.** One observation writes nothing but a salted truncated SHA-256 hash of the pair (`pending-bigrams-…-s1-f1.bin` next to the store, the salt in the store's own `salt-bigrams.bin` — 16 random bytes created on first use and destroyed by erase-all). Only the second clean observation writes the pair itself, to `personal-bigrams-…-s1-f1.tpersb` — at most 1 000 pairs per language, the least-used evicted silently.
- **Where.** The same credential-protected `no_backup/` folder as the saved words, separately per language; before the first unlock after a restart nothing is read or written; never backed up, exported or synced, in this version or any planned one.
- **How it ranks.** Static prediction tables always come first; a learned pair may only take a cell the static table left free (at most two of the three cells), and a pair duplicating a table prediction is never shown twice.
- **Not everywhere**, exactly like single words: password and other private fields, fields asking not to personalize, postal addresses — and everywhere while **incognito mode** is on (below).
- **How to see and erase it.** Keyboard settings → **“Saved words”**: each language has a pairs card with every learned pair, **“Delete”** on each one and **“Clear all word pairs.”** The screen's erase-everything action destroys **all three** stores — the words, the pairs and the learned emoji — with their pending-hash files and salts. Deleting the app or clearing its data destroys everything.
- **If a pairs file cannot be read, it is not destroyed.** It is moved aside into a quarantine copy (`*.tpersb.quarantine`), you are told once, and the “Saved words” screen shows a card for it with **“Bring the pairs back”** (puts back what survived — the card says how many) and **“Delete the copy.”** Deleting a pair also removes it from the quarantine copy, so a restore can never bring a deleted pair back.

### Learned emoji

If the personal dictionary is on **and** emoji suggestions are on, the keyboard can also learn **which emoji you insert after which word** — after the same emoji has followed the same word twice, that emoji starts leading the suggestion strip's emoji cell for that word, ahead of the built-in table's entry.

- **What is saved, per pair.** The word in its **normalized form only** (lowercased — it is the lookup key, and the list shows it in that same form), the emoji cluster **exactly as you picked it**, and three counters: how often the pair was observed, how often its learned cell was tapped, and a last-use counter — again a counter, not a clock time. **Nothing else**: not the sentence, not the app, not the field, not the date.
- **Until a pair earns its place, it does not exist in plaintext anywhere.** One observation writes nothing but a salted truncated SHA-256 hash of the pair (`pending-emoji-…-s1-f1.bin` next to the store, the salt in `salt-emoji.bin` — 16 random bytes created on first use and destroyed by erase-all). Only the second observation writes the pair itself, to `personal-emoji-…-s1-f1.tpersem` — at most 500 pairs per language, the least-used evicted silently. A word is at most 24 code points, an emoji cluster at most 32 UTF-16 units, and the whole file is capped at 64 KiB.
- **Only the keyboard's own insertions teach.** The teaching events are exactly three: picking an emoji in the emoji panel, picking one in the emoji search, and tapping the suggestion strip's emoji cell — each counted only when the emoji lands right after a word you committed. Pasted text never reaches these paths, so the clipboard teaches nothing.
- **What a learned pair changes.** Only the tail cell's leader: where the strip would offer the built-in table's emoji for a word, it offers yours instead. It is **offered, never inserted** — it lands in the text only if you tap it. A tap on the learned cell also bumps its usage counter; ranking is learned entries first, then most-tapped, then most-observed. The cell appears only while all three switches are on: suggestions, emoji suggestions and the personal dictionary.
- **Where.** The same credential-protected `no_backup/` folder as the saved words, separately per language; before the first unlock after a restart nothing is read or written; never backed up, exported or synced, in this version or any planned one.
- **Not everywhere**, exactly like words and pairs: password and other private fields, fields asking not to personalize — and under **incognito mode** the pause covers **all** of this store's writes, pending hashes and the end-of-session flush included. Reads are never paused: a learned emoji keeps being offered while incognito is on.
- **How to see and erase it.** Keyboard settings → **“Saved words”**: each language has a third card listing every learned word → emoji with its counters, **“Delete”** on each one (the confirmation names the pair), and **“Clear all learned emoji”** for the language. The screen's erase-everything action destroys all three stores — words, pairs and emoji — with their pending-hash files and salts. Deleting the app or clearing its data destroys everything.
- **If an emoji file cannot be read, it is not destroyed.** It is moved aside into a quarantine copy (`*.tpersem.quarantine`), you are told once, and the “Saved words” screen shows a card with **“Bring the emoji back”** (puts back what survived — the card says how many) and **“Delete the copy.”**

### Incognito mode

A single switch (keyboard settings → Preferences → **“Incognito mode”**, off by default) pauses **all** personal learning.

- **What pauses.** No new word, no new pair and no new learned emoji is written to any of the three stores, and the pending-hash counters of all three are not touched — nothing is hashed, nothing expires, the counters simply wait.
- **What does not pause.** What you already saved keeps working: saved words, saved pairs and learned emoji still appear in suggestions (this is a pause, not a wipe — the separate personal-dictionary switch is the one that hides them).
- **Turning it off** resumes learning exactly where it stopped; nothing that was typed while it was on is learned retroactively — during the pause those observations never existed for the stores.

## По-русски

Tatar Keyboard хранит то, что вы печатаете, **на вашем устройстве**.

- В манифесте приложения **нет разрешения INTERNET** — само приложение ничего никуда не отправляет. Это проверяемо: команда `aapt2 dump permissions` по APK, плюс CI‑гейт проверяет манифест и собранный APK на каждом коммите. Единственное используемое разрешение — VIBRATE (вибрация при нажатии клавиш).
- Всё, что вы печатаете, обрабатывается **только на вашем устройстве**. Клавиатура работает **полностью офлайн**.
- Буфер обмена читается **только когда вы нажимаете клавишу вставки** — никогда в фоне, и его содержимое клавиатурой нигде не сохраняется.
- **Нет аналитики, нет рекламных SDK, нет Firebase** и никаких сторонних трекеров.
- **Ничего не уходит в резервную копию.** Приложение выставляет `android:allowBackup="false"`, а его правила бэкапа исключают все домены данных приложения — файлы, настройки, базы, внешнее хранилище, и обычные, и device‑protected — и из облачной резервной копии, и из переноса на новое устройство. Это проверяет CI по манифесту **и** по собранному APK — так же, как проверку INTERNET. Прямое следствие: настройки клавиатуры на новом устройстве и из резервной копии **не** восстанавливаются — вы задаёте их заново.
- Исходный код открыт под лицензией Apache‑2.0 — любой может убедиться в этих утверждениях сам.

### Недавно использованные эмодзи

Чтобы панель эмодзи показывала их первыми, она запоминает **до 24 недавно использованных эмодзи** — засчитывается каждый вставленный вами эмодзи: выбранный в панели, найденный через поиск или нажатый в полосе подсказок.

- **Где.** Они хранятся во внутренней папке приложения, которая **расшифровывается только после ввода кода блокировки устройства** (PIN, графический ключ или пароль).
- **До разблокировки** устройства после перезагрузки этот список **не читается и не пополняется** — вкладки «Недавние» до разблокировки просто нет.
- **Исключено из бэкапа.** Этот список лежит во внутренней «no‑backup» папке, которую Android **не** включает ни в облачную резервную копию, ни в перенос на новое устройство.
- **Не везде.** Ничего не запоминается в **полях пароля и других приватных полях** — e‑mail, URL и любое поле, которое просит клавиатуру не показывать подсказки или не персонализироваться (например, вкладка браузера в режиме инкогнито или банковское приложение).
- **Как стереть.** Откройте настройки клавиатуры и нажмите **«Очистить недавние эмодзи»**. Чтобы удалить вместе со всем остальным — удалите данные приложения в системных настройках; это заодно удалит распакованные словари и таблицы предсказаний, которые приложение соберёт заново при следующем использовании.

### Подсказки слов и эмодзи

Словари подсказок, таблицы предсказания следующего слова и таблица «слово → эмодзи» **поставляются внутри приложения** и распаковываются во внутреннюю папку при первом использовании.

- **Размер.** Два распакованных словаря (1 276 289 и 1 151 323 байта) и две таблицы предсказаний (170 471 и 131 662 байта) — около 2,7 МБ суммарно. Таблицы эмодзи добавляют несколько сотен КБ в упакованном виде и ничего о вас не хранят.
- **Что они о вас записывают: ничего.** Подсказки, предсказания и эмодзи-подсказки — это чистые чтения из встроенных таблиц. Никакой истории, счётчиков, копий набранного. Эмодзи-подсказка в полосе читает только что завершённое слово, на устройстве, и исчезает из памяти вместе с ним. В полях паролей и других приватных полях не показываются ни подсказки слов, ни подсказки эмодзи.
- **Две версии после обновления.** Когда новая версия приложения приносит новый словарь или таблицу, прежняя распакованная копия некоторое время лежит рядом с новой — чтобы обновление можно было откатить; старая версия удаляется при последующих запусках. Одновременно на устройстве не больше двух версий каждого артефакта.
- **Исключены из бэкапа**, как и всё остальное.

### Личный словарь

Если вы включите **личный словарь** (по умолчанию он **выключен**), клавиатура сохраняет набранные вами слова, чтобы предлагать их позже.

- **Что сохраняется.** Само слово в том написании, в котором вы его набрали, и два числа: сколько раз оно использовалось и когда использовалось в последний раз — счётчиком, а не временем по часам. **Больше ничего**: ни окружающего предложения, ни приложения, в котором вы печатали, ни поля, ни даты.
- **Пока слово не заслужило своё место, его нигде нет в открытом виде.** Первое и второе чистые завершения слова записывают лишь солёный усечённый SHA-256-хеш слова (файл `pending-<subtype>-s1-f1.bin` рядом с хранилищем; соль — собственный `salt.bin` хранилища, 16 случайных байт, создаются при первом использовании и уничтожаются стиранием всего). Только третье чистое завершение записывает само слово — в `personal-<subtype>-s1-f1.tpers`; не больше 2 000 слов на язык, редко используемые молча вытесняются.
- **Где.** Во внутренней папке приложения, которая **расшифровывается только после ввода кода блокировки устройства** (PIN, графический ключ или пароль), отдельно для каждого языка клавиатуры. **До первой разблокировки** после перезагрузки там ничего не читается и не пишется.
- **Это никогда не покидает ваше устройство** и исключено из облачной резервной копии и переноса на новое устройство — ни экспорта, ни импорта, ни синхронизации нет ни в этой версии, ни в планах.
- **Не везде.** Ничего не сохраняется в **полях пароля и других приватных полях** — e-mail, URL, почтовый адрес и любое поле, которое просит клавиатуру не показывать подсказки или не персонализироваться (вкладка инкогнито, банковское приложение).
- **Как посмотреть и стереть.** Настройки клавиатуры → **«Сохранённые слова»**: все сохранённые слова всех языков, у каждого — **«Удалить»**, и отдельно **«Стереть всё сохранённое»**. На том же экране перечислены и выученные **пары слов**, и изученные пары **слово → эмодзи** (следующие разделы), а действие «стереть всё» покрывает все три хранилища. Выключение личного словаря **не** удаляет накопленное — стирать нужно здесь. Удаление приложения или «стереть данные» в системных настройках уничтожает всё накопленное безвозвратно, и восстановление из резервной копии его не вернёт.
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
- **Как посмотреть и стереть.** Настройки клавиатуры → **«Сохранённые слова»**: у каждого языка есть карточка пар со всеми выученными парами, у каждой — **«Удалить»**, и отдельно **«Очистить все пары слов»**. Действие «стереть всё» на экране уничтожает **все три** хранилища — слов, пар и изученных эмодзи — вместе с файлами ожидающих хешей и солями. Удаление приложения или «стереть данные» уничтожает всё.
- **Если файл пар не читается, он не уничтожается.** Он откладывается в карантинную копию (`*.tpersb.quarantine`), вы один раз об этом узнаёте, а на экране **«Сохранённые слова»** появляется карточка с кнопками **«Вернуть пары»** (возвращает всё уцелевшее — карточка называет число) и **«Удалить копию»**. Удаление пары стирает её и из карантинной копии, так что восстановление не может вернуть удалённую пару.

### Изученные эмодзи

Если личный словарь включён **и** эмодзи-подсказки включены, клавиатура может учить и **какой эмодзи вы вставляете после какого слова** — после того как один и тот же эмодзи дважды встал сразу после одного и того же слова, этот эмодзи начинает возглавлять эмодзи-ячейку полосы подсказок для этого слова, опережая запись встроенной таблицы.

- **Что сохраняется о паре.** Слово **только в нормализованном виде** (в нижнем регистре — это ключ поиска, и список показывает его в том же виде), эмодзи-кластер **ровно в том виде, в котором вы его выбрали**, и три счётчика: сколько раз пара наблюдалась, сколько раз по её изученной ячейке нажали, и счётчик последнего использования — снова счётчик, а не время по часам. **Больше ничего**: ни предложения, ни приложения, ни поля, ни даты.
- **Пока пара не заслужила своё место, её нигде нет в открытом виде.** Одно наблюдение записывает лишь солёный усечённый SHA-256-хеш пары (файл `pending-emoji-…-s1-f1.bin` рядом с хранилищем; соль — `salt-emoji.bin`, 16 случайных байт, создаются при первом использовании и уничтожаются стиранием всего). Только второе наблюдение записывает саму пару — в `personal-emoji-…-s1-f1.tpersem`; не больше 500 пар на язык, редко используемые молча вытесняются. Слово — не длиннее 24 кодовых позиций, эмодзи-кластер — не длиннее 32 UTF-16 единиц, а весь файл ограничен 64 КиБ.
- **Учат только собственные вставки клавиатуры.** Обучающих событий ровно три: выбор эмодзи в панели эмодзи, выбор в поиске эмодзи и нажатие эмодзи-ячейки полосы подсказок — каждое засчитывается, только если эмодзи встал сразу после завершённого вами слова. Вставленный текст до этих путей не доходит, поэтому буфер обмена ничему не учит.
- **Что меняет изученная пара.** Только лидера хвостовой ячейки: там, где полоса предлагала эмодзи встроенной таблицы для этого слова, она предлагает ваш. Он **предлагается, но никогда не вставляется сам** — в текст попадает только по вашему нажатию. Нажатие по изученной ячейке увеличивает и её счётчик использования; ранжирование — сначала изученные, затем чаще нажимаемые, затем чаще наблюдавшиеся. Ячейка появляется, только пока включены все три переключателя: подсказки, эмодзи-подсказки и личный словарь.
- **Где.** В той же credential-protected «no_backup» папке, что и сохранённые слова, отдельно для каждого языка; до первой разблокировки после перезагрузки ничего не читается и не пишется; никогда не копируется в бэкап, не экспортируется и не синхронизируется — ни в этой версии, ни в планах.
- **Не везде** — в точности как слова и пары: поля паролей и другие приватные поля, поля, просящие не персонализироваться; а **режим инкогнито** приостанавливает **все** записи этого хранилища, включая ожидающие хеши и запись накопленного в конце сессии. Чтения никогда не приостанавливаются: изученный эмодзи продолжает предлагаться и под инкогнито.
- **Как посмотреть и стереть.** Настройки клавиатуры → **«Сохранённые слова»**: у каждого языка есть третья карточка со всеми изученными парами слово → эмодзи и их счётчиками, у каждой — **«Удалить»** (подтверждение называет пару), и **«Очистить все изученные эмодзи»** на язык. Действие «стереть всё» на экране уничтожает все три хранилища — слов, пар и эмодзи — вместе с файлами ожидающих хешей и солями. Удаление приложения или «стереть данные» уничтожает всё.
- **Если файл эмодзи не читается, он не уничтожается.** Он откладывается в карантинную копию (`*.tpersem.quarantine`), вы один раз об этом узнаёте, а на экране **«Сохранённые слова»** появляется карточка с кнопками **«Вернуть эмодзи»** (возвращает всё уцелевшее — карточка называет число) и **«Удалить копию»**.

### Режим инкогнито

Один переключатель (настройки клавиатуры → «Настройки» → **«Режим инкогнито»**, по умолчанию выключен) приостанавливает **всё** личное обучение.

- **Что приостанавливается.** Ни новое слово, ни новая пара, ни новый изученный эмодзи не записываются ни в одно из трёх хранилищ, а счётчики ожидающих хешей всех трёх не трогаются — ничего не хешируется, ничего не истекает, счётчики просто ждут.
- **Что НЕ приостанавливается.** Уже сохранённое продолжает работать: сохранённые слова, пары и изученные эмодзи по-прежнему появляются в подсказках (это пауза, а не стирание — скрыть их умеет отдельный выключатель личного словаря).
- **После выключения** обучение продолжается ровно с того места, где остановилось; набранное за время паузы задним числом не доучивается — для хранилищ этих наблюдений просто не было.

## Контакт / Contact

Вопросы по приватности — через Issues репозитория проекта. / Privacy questions — via the project repository's Issues.
