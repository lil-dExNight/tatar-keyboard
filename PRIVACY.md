# Privacy Policy

**Version:** 1.9 — 2026-09-30

Tatar Keyboard keeps what you type **on your device**.

- The app's manifest contains **no INTERNET permission**, so the app itself sends nothing to any server. You can check this by running `aapt2 dump permissions` on the APK; a CI check also validates both the source manifest and the built APK on every commit. The only permission the app uses is VIBRATE (haptic feedback on key press).
- Everything you type is processed **on your device only**. The keyboard works **fully offline**.
- The clipboard is read **only while the keyboard is open on screen**, never in the background: when you tap the paste key, and to offer a clip you copied moments ago as a one-tap cell in the suggestion strip. That cell lives in memory only (the clip is dropped when the keyboard closes and expires after about two minutes), is never offered in password or other private fields or on the lock screen, and the keyboard **never stores** what is on the clipboard.
- There are **no analytics, no advertising SDKs, no Firebase**, and no third-party trackers of any kind.
- **Nothing is backed up off your device.** The app sets `android:allowBackup="false"`, and its backup rules exclude every app-data domain (files, settings, databases, external storage, both regular and device-protected) from the cloud backup and from the transfer to a new device. CI checks this on the manifest **and** on the built APK, the same way it checks for the INTERNET permission. The only backup is the one you make yourself, when you ask for it — see **Backup and export** below.
- The source code is open under the Apache License 2.0, so anyone can audit these claims.

## Recently used emoji

So the emoji panel can show them first, the keyboard remembers **up to 24 recently used emoji**. Every emoji you insert counts, whether you picked it in the panel, found it through the search, or tapped it in the suggestion strip.

- **Where.** They are stored in an internal app folder that is **decrypted only after you enter your device's lock code** (PIN, pattern or password).
- **Before the device is unlocked** for the first time after a restart, this list is **not read and not added to**; there is no “Recent” tab until you unlock.
- **On the lock screen** (for example a quick reply to a notification), also after the first unlock, the “Recent” tab is not shown and nothing is added to the list.
- **Incognito mode** (below) pauses it too: emoji you insert while it is on are not added, and the list you already have stays.
- **Excluded from backup.** The list lives in an internal “no-backup” folder that Android does **not** include in a cloud backup or in a transfer to a new device.
- **Not everywhere.** Nothing is remembered in **password fields or other private fields**: e-mail, URL, and any field that asks the keyboard not to show suggestions or not to personalize (for example an incognito browser tab or a banking app).
- **How to erase it.** Open the keyboard settings and tap **“Clear recent emoji.”** To remove it together with everything else, delete the app's data in the system settings. That also removes the unpacked dictionaries and next-word prediction tables, which the app unpacks again on next use.

## Word and emoji suggestions

The word-completion dictionaries, the next-word prediction tables and the “word → emoji” table are **bundled inside the app** and unpacked to an internal app folder on first use.

- **Size.** Two unpacked dictionaries (1,276,289 and 1,151,323 bytes) and two next-word prediction tables (170,471 and 131,662 bytes), about 2.7 MB in total. The emoji lookup tables add a few hundred KB packed and store nothing about you.
- **What they record about you: nothing.** Word completions, next-word predictions and emoji suggestions are read-only lookups in these bundled tables. They keep no history, no counters and no copy of what you type. The emoji suggestion in the strip reads only the word you just finished, on the device, and is dropped from memory with it. In password and other private fields, and on the lock screen, neither word suggestions nor emoji suggestions appear.
- **Two versions kept after an update.** When a new app version brings a new dictionary or table, the previous unpacked copy is kept next to the new one for a while, so the update can be rolled back; the old version is removed on a later launch. At most two versions of each file are on the device at any time.
- **Excluded from backup**, like everything else.

## Personal dictionary

If you turn the **personal dictionary** on (it is **off** by default), the keyboard saves words you type so it can suggest them later. It exists for Tatar and Russian; each language has its own store.

- **What is saved.** The word itself, spelled the way you typed it, plus two numbers: how often it has been used, and when it was last used, as a counter rather than a clock time. **Nothing else**: not the sentence around it, not the app you typed it in, not the field, not the date.
- **A word is stored in plaintext only after its third clean completion.** A clean completion means the word was typed letter by letter, with no backspace, cursor move, paste or accepted suggestion in between. The first and second clean completions write only a salted, truncated SHA-256 hash of the word (`pending-<subtype>-s1-f1.bin` next to the store; the salt is the store's own `salt.bin`, 16 random bytes created on first use and destroyed by “Erase everything saved”). The third clean completion writes the word itself to `personal-<subtype>-s1-f1.tpers`. Each language holds at most 2,000 words; the least-used word is evicted silently when the store is full.
- **Words you add yourself.** On the **“Saved words”** screen, **“Add word…”** saves a word right away, without the three-completion step.
- **Where.** In an internal app folder that is **decrypted only after you enter your device's lock code** (PIN, pattern or password). **Before the first unlock** after a restart, nothing there is read or written.
- **Not on the lock screen.** While the lock screen is shown (a quick reply to a notification, for example), also after the first unlock, the keyboard shows no suggestion strip, glide typing is off and nothing is learned, so no saved word can be seen or added there.
- **It never leaves your device on its own.** It is excluded from cloud backup and from the transfer to a new device, and there is no sync. The only way it leaves the device is a backup file you export yourself (see **Backup and export**).
- **Not everywhere.** Nothing is saved in **password fields or other private fields**: e-mail, URL, postal address, and any field that asks the keyboard not to show suggestions or not to personalize (an incognito tab, a banking app).
- **How to see and erase it.** Keyboard settings → **“Saved words”**: every saved word of every language, with **“Delete”** on each one and **“Erase everything saved.”** The same screen lists the learned **word pairs** and the learned **word → emoji** entries (see the next sections), and “Erase everything saved” covers all four stores, the refused corrections included. Turning the personal dictionary off does **not** erase what was already saved; erase it on this screen. Deleting the app, or clearing its data in the system settings, destroys everything saved, and no backup brings it back.
- **If a saved-words file cannot be read, it is not destroyed.** The keyboard moves it aside into a quarantined copy (a `*.tpers.quarantine` file next to the dictionary), tells you once that the words could not be read, and keeps the copy on the device. The **“Saved words”** screen then shows a card for that language: **“Bring the words back”** restores every word that can still be read (the card says how many, and warns if the end of the copy is damaged beyond recovery); **“Delete the copy”** removes the quarantined file. Nothing in it is ever uploaded anywhere.
- **Android 7 limitation.** The standard signal an app uses to say “do not learn from this field” (`IME_FLAG_NO_PERSONALIZED_LEARNING`) exists from Android 8 onwards. On Android 7.0 and 7.1 no app sets it, so on those two versions the keyboard cannot recognize such a field, and only the other rules above protect it (password and other private field types, postal addresses, and the switch itself, which is off by default). Every other statement on this page holds on all supported versions.
- **The screen has no separate password.** Anyone holding your unlocked phone can read the list of saved words. `FLAG_SECURE` keeps it out of screenshots and out of the recent-apps thumbnail, but not out of sight of a person next to you. This is a deliberate trade-off.

## Learned word pairs

If the personal dictionary is on, the keyboard can also learn **which word follows which**. After you type the same word pair cleanly twice, the second word starts appearing in the suggestions after the first one.

- **What is saved, per pair.** The first word (the context) in its **normalized form only** (lowercased, with accents folded; it is used for matching and is never displayed), the second word **exactly as you typed it** (this is what the cell shows), and three counters: how often the pair was typed, how often its suggestion was tapped, and a last-use counter (a counter, not a clock time). **Nothing else**: not the sentence, not the app, not the field, not the date.
- **A pair is stored in plaintext only after its second clean observation.** The first observation writes only a salted, truncated SHA-256 hash of the pair (`pending-bigrams-…-s1-f1.bin` next to the store; the salt is the store's own `salt-bigrams.bin`, 16 random bytes created on first use and destroyed by “Erase everything saved”). The second clean observation writes the pair itself to `personal-bigrams-…-s1-f1.tpersb`. Each language holds at most 1,000 pairs; the least-used pair is evicted silently.
- **Where.** The same credential-protected `no_backup/` folder as the saved words, separately per language. Before the first unlock after a restart, nothing is read or written. Never backed up by Android and never synced; the only way it leaves the device is a backup file you export yourself (see **Backup and export**).
- **How it ranks.** The bundled prediction tables always come first. A learned pair may only take a cell the bundled table left free (at most two of the three cells), and a pair that duplicates a table prediction is never shown twice.
- **Not everywhere**, exactly like single words: password and other private fields, fields that ask not to personalize, postal addresses, and everywhere while **incognito mode** is on (below).
- **How to see and erase it.** Keyboard settings → **“Saved words”**: each language has a pairs card listing every learned pair, with **“Delete”** on each one and **“Clear all word pairs.”** “Erase everything saved” destroys **all four** stores (words, pairs, learned emoji and refused corrections) together with the pending-hash files and salts of the three learning stores. Deleting the app or clearing its data destroys everything.
- **If a pairs file cannot be read, it is not destroyed.** It is moved aside into a quarantined copy (`*.tpersb.quarantine`), you are told once, and the “Saved words” screen shows a card for it with **“Bring the pairs back”** (restores what can still be read; the card says how many) and **“Delete the copy.”** Deleting a pair also removes it from the quarantined copy, so a restore can never bring a deleted pair back.

## Learned emoji

If the personal dictionary is on **and** emoji suggestions are on, the keyboard can also learn **which emoji you insert after which word**. After the same emoji has followed the same word twice, that emoji leads the emoji cell of the suggestion strip for that word, ahead of the bundled table's entry.

- **What is saved, per entry.** The word in its **normalized form only** (lowercased; it is the lookup key, and the list shows it in that form), the emoji **exactly as you picked it**, and three counters: how often the pair was observed, how often its learned cell was tapped, and a last-use counter (a counter, not a clock time). **Nothing else**: not the sentence, not the app, not the field, not the date.
- **An entry is stored in plaintext only after its second observation.** The first observation writes only a salted, truncated SHA-256 hash of the pair (`pending-emoji-…-s1-f1.bin` next to the store; the salt is in `salt-emoji.bin`, 16 random bytes created on first use and destroyed by “Erase everything saved”). The second observation writes the entry itself to `personal-emoji-…-s1-f1.tpersem`. Each language holds at most 500 entries; the least-used entry is evicted silently. A word is at most 24 code points, an emoji at most 32 UTF-16 units, and the whole file is capped at 64 KiB.
- **Only the keyboard's own insertions teach it.** There are exactly three learning events: picking an emoji in the emoji panel, picking one in the emoji search, and tapping the emoji cell of the suggestion strip. Each counts only when the emoji lands right after a word you finished. Pasted text never reaches these paths, so the clipboard teaches nothing.
- **What a learned entry changes.** Only the emoji shown in the strip's last cell: where the strip would offer the bundled table's emoji for a word, it offers yours instead. It is **offered, never inserted**: it goes into the text only if you tap it. A tap on the learned cell also increases its usage counter; ranking is learned entries first, then most-tapped, then most-observed. The cell appears only while all three switches are on: word suggestions, emoji suggestions and the personal dictionary.
- **Where.** The same credential-protected `no_backup/` folder as the saved words, separately per language. Before the first unlock after a restart, nothing is read or written. Never backed up by Android and never synced; the only way it leaves the device is a backup file you export yourself (see **Backup and export**).
- **Not everywhere**, exactly like words and pairs: password and other private fields, and fields that ask not to personalize. **Incognito mode** pauses **all** writes to this store, including pending hashes and the save at the end of a session. Reads are never paused: a learned emoji keeps being offered while incognito mode is on.
- **How to see and erase it.** Keyboard settings → **“Saved words”**: each language has a third card listing every learned word → emoji entry with its counters, with **“Delete”** on each one (the confirmation names the entry) and **“Clear all learned emoji”** for the language. “Erase everything saved” destroys all four stores (words, pairs, emoji and refused corrections) with the pending-hash files and salts of the three learning stores. Deleting the app or clearing its data destroys everything.
- **If an emoji file cannot be read, it is not destroyed.** It is moved aside into a quarantined copy (`*.tpersem.quarantine`), you are told once, and the “Saved words” screen shows a card with **“Bring the emoji back”** (restores what can still be read; the card says how many) and **“Delete the copy.”**

## Refused corrections

Undo an autocorrection — one backspace right after it, or the quoted typed-word cell in the strip — and it does not fire again in that text field. Undo the **same** correction (the same typed word replaced with the same word) a **second** time, in any session, and the keyboard remembers the pair on the device: that correction never fires again. A refusal only ever suppresses a correction; it never inserts or suggests anything.

- **What is saved, per pair.** The word you typed and the rejected replacement, both lowercased and accent-folded (the lookup form), and a refusal count. **Nothing else**: not the sentence, not the app, not the field, not the date. Each language holds at most 500 pairs; the least recently refused pair is evicted silently when the store is full.
- **Where and when.** The same credential-protected `no_backup/` folder as the saved words, separately per language, under exactly the same rules: nothing is recorded in password or other private fields, in postal-address fields, before the first unlock after a restart, or while **incognito mode** is on, and the list stops suppressing while the personal dictionary switch is off.
- **How to erase it.** **“Erase everything saved”** on the **“Saved words”** screen covers it, and it travels in a backup file you export yourself (see **Backup and export**).

## Backup and export

Keyboard settings → **“Backup and export”** can write **one backup file** with your settings and everything the keyboard learned — your saved words, learned word pairs, learned emoji, text shortcuts and refused corrections — and read such a file back. Both directions start only from your tap, and the file goes only where you choose in the system file picker.

- **The file is not encrypted.** Anyone who can open it can read your saved words. Keep it the way you would keep a note with those words.
- **The app never uploads it.** The app has no INTERNET permission; the file moves only if you move it, with whatever app you trust for that — that choice is yours and happens outside the keyboard.
- **Importing replaces.** Restoring a file replaces the current settings, saved words, learned pairs, learned emoji, text shortcuts and refused corrections on this device with the file's contents; what was here before is lost. The screen asks for confirmation first. A file that fails any integrity check is rejected whole: nothing is changed.
- **What is not in the file.** The recently used emoji list, the half-learned words and pairs that have not crossed the learning threshold yet, the quarantined copies, and your organization's device-policy values.
- **Android's own backup stays off.** Cloud backup and device-to-device transfer keep excluding everything, as described above; this file is the only backup, and it exists only when you make it.

## Incognito mode (pause learning)

A single switch (keyboard settings → Preferences → **“Incognito mode”**, off by default) pauses **all** personal learning.

- **What pauses.** No new word, pair, learned emoji or refused correction is written to any of the four stores, and the pending-hash counters of the three learning stores are left untouched: nothing is hashed, nothing expires, the counters wait. The recently used emoji list is not added to either.
- **What does not pause.** What you already saved keeps working: saved words, learned pairs and learned emoji still appear in suggestions, a remembered refused correction keeps suppressing its correction, and the “Recent” tab still shows your recent emoji. This is a pause, not a wipe; the personal dictionary switch is the one that hides them.
- **Turning it off** resumes learning where it stopped. Nothing typed while it was on is learned afterwards; for the stores, those observations never happened.

## Lab session log (layout study)

The keyboard contains an **opt-in** instrument for a one-time layout study, under keyboard settings → **“Developer”** → **“Lab session log.”** It is **off by default**, and while it is off nothing is recorded anywhere.

- **What it records while on.** One line per event: a timestamp, the kind of event (a printable key, space, delete, keyboard shown or hidden), the **key code** of the key (a number, not text) and the study arm. Nothing else: no words, no suggestions, no app or field names. Note what key codes still are: in sequence, the codes of letter keys correspond to the letters pressed, so treat the file like typed content.
- **Where.** A single size-capped file in the app's internal folder, in storage that is **decrypted only after you enter your device's lock code**. When it grows past the cap, the older half moves aside into one rotation file; there are never more than two. **Nothing is recorded before the first unlock** after a restart, and **nothing is recorded in password fields**.
- **It never leaves your device by itself.** The file is excluded from backup like everything else. The only way off the device is a copy made with `adb` by a person holding your unlocked device — and that works only on a debuggable build. The regular (release) app can record the log but offers no way to read it back.
- **How to erase it.** Keyboard settings → **“Developer”** → **“Clear lab session log.”** Clearing the app's data removes it too. Turning the switch off stops recording immediately.

## Contact

Privacy questions: open an issue in the project repository.
