# Privacy Policy

**Version:** 1.12 — 2026-11-02

Tatar Keyboard keeps what you type **on your device**.

- The app's manifest contains no INTERNET permission, so the app itself sends nothing to any server. You can check this by running `aapt2 dump permissions` on the APK; a CI check also validates both the source manifest and the built APK on every commit. The only permission the app uses is VIBRATE (haptic feedback on key press).
- Everything you type is processed on your device only. The keyboard works fully offline.
- The text of a password field is never read into the keyboard's memory, not even by a cache reload that was queued before the password field opened.
- The clipboard is read only while the keyboard is open on screen, never in the background and never on the lock screen: when you tap the paste key, and to offer a clip you copied moments ago as a one-tap cell in the suggestion strip. That cell lives in memory only (the clip is dropped when the keyboard closes and expires after about two minutes), is never offered in password or other private fields or on the lock screen, never shows a clip that the app you copied it from marked as sensitive (password managers mark the passwords and card numbers they copy), and the keyboard never stores what is on the clipboard.
- There are no analytics, advertising or tracking SDKs.
- **Nothing is backed up off your device.** The app sets `android:allowBackup="false"`, and its backup rules exclude the files, settings and databases domains (regular and device-protected) and the external-storage domain from the cloud backup and from the transfer to a new device. Everything this page calls personal lives in a “no-backup” folder that Android never includes in either. CI checks this on the manifest and on the built APK, the same way it checks for the INTERNET permission. The only backup is the one you make yourself, when you ask for it; see Backup below.
- The source code is open under the Apache License 2.0.

## Recently used emoji

So the emoji panel can show them first, the keyboard remembers up to 24 recently used emoji. Every emoji you insert counts, whether you picked it in the panel, found it through the search, or tapped it in the suggestion strip. An emoji the panel cannot show (for example a skin tone the system font cannot draw) is not added; an emoji whose toned forms the font cannot draw gets no tone picker, and its toned forms appear neither in search nor in the suggestion strip.

- The list is stored in an internal app folder that is decrypted only after you enter your device's lock code (PIN, pattern or password).
- Before the first unlock after a restart, the list is neither read nor added to; there is no “Recent” tab until you unlock.
- On the lock screen (a quick reply to a notification, for example), also after the first unlock, the “Recent” tab is not shown and nothing is added.
- Pause learning (below) pauses it too: emoji you insert while it is on are not added, and the list you already have stays.
- The list lives in an internal “no-backup” folder that Android does not include in a cloud backup or in a transfer to a new device.
- Nothing is remembered in password fields or other private fields: email, URL, and any field that asks the keyboard not to show suggestions or not to personalize (for example an incognito browser tab or a banking app).
- **How to erase it.** Keyboard settings → Preferences → “Clear recent emoji.” “Erase everything saved” (below) clears it too. To remove it together with everything else, including the settings, delete the app's data in the system settings. That also removes the unpacked dictionaries and next-word prediction tables, which the app unpacks again on next use.

## Word and emoji suggestions

The word-completion dictionaries, the next-word prediction tables and the “word → emoji” table are bundled inside the app and unpacked to an internal app folder on first use.

- **Size.** Two unpacked dictionaries and two next-word prediction tables, a few megabytes in total. The emoji lookup tables add a few hundred KB packed and store nothing about you.
- **What they record about you: nothing.** Word completions, next-word predictions and emoji suggestions are read-only lookups in these bundled tables. They keep no history, no counters and no copy of what you type. The emoji suggestion in the strip reads only the word you just finished, on the device, and is dropped from memory with it. In password and other private fields, and on the lock screen, neither word suggestions nor emoji suggestions appear.
- When a new app version brings a new dictionary or table, the previous unpacked copy is kept next to the new one for a while, so the update can be rolled back; the old version is removed on a later launch. At most two versions of each file are on the device at any time.
- The files are excluded from backup, like everything else.

## Personal dictionary

If you turn the personal dictionary on (it is off by default), the keyboard saves words you type so it can suggest them later. It exists for Tatar and Russian; each language has its own store.

- **What is saved.** The word itself, spelled the way you typed it, plus two numbers: how often it has been used, and when it was last used, as a counter rather than a clock time. No other data is stored.
- A word is stored in plaintext only after its third clean completion. A clean completion means the word was typed letter by letter, with no backspace, cursor move, paste or accepted suggestion in between. The first and second clean completions write only a salted, truncated SHA-256 hash of the word (`pending-<subtype>-s1-f1.bin` next to the store; the salt is the store's own `salt.bin`, 16 random bytes created on first use and destroyed by “Erase everything saved”). The third clean completion writes the word itself to `personal-<subtype>-s1-f1.tpers`. Each language holds at most 2,000 words; the word unused for the longest time is evicted silently when the store is full.
- Words you add yourself skip that step: on the “Saved words” screen, “Add word…” saves a word right away.
- **Where.** In an internal app folder that is decrypted only after you enter your device's lock code (PIN, pattern or password). Before the first unlock after a restart, nothing there is read or written.
- While the lock screen is shown (a quick reply to a notification, for example), also after the first unlock, the keyboard shows no suggestion strip, glide typing is off and nothing is learned, so no saved word can be seen or added there.
- It never leaves your device on its own: it is excluded from cloud backup and from the transfer to a new device, and there is no sync. The only way it leaves the device is a backup file you save yourself (see Backup).
- Nothing is saved in password fields or other private fields: email, URL, postal address, and any field that asks the keyboard not to show suggestions or not to personalize (an incognito tab, a banking app).
- **How to see and erase it.** Keyboard settings → Preferences → “Saved words”: every saved word of every language, with “Delete” on each one, “Clear all words” for a language, and “Erase everything saved” (see below). The same screen lists the learned word pairs and the learned word → emoji entries (see the next sections). Turning the personal dictionary off does not erase what was already saved; erase it on this screen. Deleting the app, or clearing its data in the system settings, destroys everything saved, and no backup brings it back.
- If a saved-words file is damaged, it is not destroyed: the keyboard moves it aside into a quarantined copy (a `*.tpers.quarantine` file next to the dictionary), tells you once that the words could not be read, and keeps the copy on the device. The “Saved words” screen then shows a card for that language, “Words that could not be read”: “Restore the words” restores every word that can still be read (the card says how many, and says when the rest is lost); “Delete them” removes the quarantined file. Nothing in it is ever uploaded anywhere.
- If the storage cannot be read at all, nothing is overwritten. When reading a file fails with a storage error rather than finding it damaged, the keyboard leaves the file exactly as it is, learns nothing into that store and writes nothing to it, and tries to read it again later, for example when the settings show it. The same holds for the learned word pairs, learned emoji, refused corrections, sentence starts and text shortcuts.
- **Android 7 limitation.** The standard signal an app uses to say “do not learn from this field” (`IME_FLAG_NO_PERSONALIZED_LEARNING`) exists from Android 8 onwards. On Android 7.0 and 7.1 no app sets it, so on those two versions the keyboard cannot recognize such a field, and only the other rules above protect it (password and other private field types, postal addresses, and the switch itself, which is off by default). Every other statement on this page holds on all supported versions.
- The screen has no separate password. Anyone holding your unlocked phone can read the list of saved words. `FLAG_SECURE` keeps it out of screenshots and out of the recent-apps thumbnail, but not out of sight of a person next to you.

## Learned word pairs

If the personal dictionary is on, the keyboard can also learn which word follows which. After you type the same word pair cleanly twice, the second word starts appearing in the suggestions after the first one.

- A pair stores the first word (the context) in normalized form only (lowercased, with accents folded; it is used for matching and is never displayed), the second word exactly as you typed it (this is what the cell shows), and three counters: how often the pair was typed, how often its suggestion was tapped, and a last-use counter, a counter rather than a clock time. No other data is stored.
- A pair is stored in plaintext only after its second clean observation. The first observation writes only a salted, truncated SHA-256 hash of the pair (`pending-bigrams-…-s1-f1.bin` next to the store; the salt is the store's own `salt-bigrams.bin`, 16 random bytes created on first use and destroyed by “Erase everything saved”). The second clean observation writes the pair itself to `personal-bigrams-…-s1-f1.tpersb`. Each language holds at most 1,000 pairs; the pair unused for the longest time is evicted silently.
- The pairs live in the same credential-protected `no_backup/` folder as the saved words, separately per language. Before the first unlock after a restart, nothing is read or written. They are never backed up by Android and never synced; the only way they leave the device is a backup file you save yourself (see Backup).
- **How it ranks.** The bundled prediction table's words come first, and learned pairs fill the cells it left free, at most three. A pair you have typed cleanly at least five times can also take the third cell ahead of the table's third word. A pair that duplicates a table prediction is never shown twice.
- Nothing is recorded in password or other private fields, exactly like single words: fields that ask not to personalize, postal addresses, and everywhere while Pause learning is on (below).
- **How to see and erase it.** Keyboard settings → Preferences → “Saved words”: each language has a pairs card listing every learned pair, with “Delete” on each one and “Clear all word pairs.” “Erase everything saved” removes them with everything else (see below). Deleting the app or clearing its data destroys everything.
- A damaged pairs file is not destroyed: it is moved aside into a quarantined copy (`*.tpersb.quarantine`), you are told once, and the “Saved words” screen shows a card for it with “Restore the pairs” (restores what can still be read; the card says how many) and “Delete them.” Deleting a pair also removes it from the quarantined copy, so a restore can never bring a deleted pair back.

## Learned emoji

If the personal dictionary is on and emoji suggestions are on, the keyboard can also learn which emoji you insert after which word. After the same emoji has followed the same word twice, that emoji leads the emoji cell of the suggestion strip for that word, ahead of the bundled table's entry.

- An entry stores the word in normalized form only (lowercased; it is the lookup key, and the list shows it in that form), the emoji exactly as you picked it, and three counters: how often the pair was observed, how often its learned cell was tapped, and a last-use counter, a counter rather than a clock time. No other data is stored.
- An entry is stored in plaintext only after its second observation. The first observation writes only a salted, truncated SHA-256 hash of the pair (`pending-emoji-…-s1-f1.bin` next to the store; the salt is in `salt-emoji.bin`, 16 random bytes created on first use and destroyed by “Erase everything saved”). The second observation writes the entry itself to `personal-emoji-…-s1-f1.tpersem`. Each language holds at most 500 entries; the entry unused for the longest time is evicted silently. A word is at most 24 code points, an emoji at most 32 UTF-16 units, and the whole file is capped at 64 KiB.
- Only the keyboard's own insertions teach it. There are exactly three learning events: picking an emoji in the emoji panel, picking one in the emoji search, and tapping the emoji cell of the suggestion strip. Each counts only when the emoji lands right after a word you finished. Pasted text never reaches these paths, so the clipboard teaches nothing.
- A learned entry changes only the emoji shown in the strip's last cell: where the strip would offer the bundled table's emoji for a word, it offers yours instead. It is inserted only when you tap it. A tap on the learned cell also increases its usage counter; ranking is learned entries first, then most-tapped, then most-observed. The cell appears only while all three switches are on: word suggestions, emoji suggestions and the personal dictionary.
- The entries live in the same credential-protected `no_backup/` folder as the saved words, separately per language. Before the first unlock after a restart, nothing is read or written. They are never backed up by Android and never synced; the only way they leave the device is a backup file you save yourself (see Backup).
- The same fields are excluded as for words and pairs: password and other private fields, and fields that ask not to personalize. Pause learning pauses all writes to this store, including pending hashes and the save at the end of a session. Reads are never paused: a learned emoji keeps being offered while learning is paused.
- **How to see and erase it.** Keyboard settings → Preferences → “Saved words”: each language has a third card listing every learned word → emoji entry with its counters, with “Delete” on each one (the confirmation names the entry) and “Clear all emoji” for the language. “Erase everything saved” removes them with everything else (see below). Deleting the app or clearing its data destroys everything.
- A damaged emoji file is not destroyed: it is moved aside into a quarantined copy (`*.tpersem.quarantine`), you are told once, and the “Saved words” screen shows a card, “Emoji that could not be read”, with “Restore the emoji” (restores what can still be read; the card says how many) and “Delete them.”

## Refused corrections

Undo an autocorrection (one backspace right after it, or the quoted typed-word cell in the strip) and it does not fire again in that text field. Undo the same correction, the same typed word replaced with the same word, a second time, in any session, and the keyboard remembers the pair on the device: that correction never fires again. A refusal only ever suppresses a correction; it never inserts or suggests anything.

- A pair stores the word you typed and the rejected replacement, both lowercased and accent-folded (the lookup form), and a refusal count. No other data is stored. Each language holds at most 500 pairs; the least recently refused pair is evicted silently when the store is full.
- The pairs live in the same credential-protected `no_backup/` folder as the saved words, separately per language, under exactly the same rules: nothing is recorded in password or other private fields, in postal-address fields, before the first unlock after a restart, or while Pause learning is on, and the list stops suppressing while the personal dictionary switch is off.
- **How to erase it.** “Erase everything saved” on the “Saved words” screen covers it. It travels in a backup file you save yourself (see Backup).

## Sentence starts

If the personal dictionary is on, the keyboard also remembers the few words you most often start a sentence with, so that at the start of a new sentence the suggestion strip can offer them ahead of the bundled sentence-start words.

- A word is stored as you typed it, with how often you started a sentence with it, and a last-use counter, a counter rather than a clock time. No other data is stored. Each language holds at most 8 words; the word unused for the longest time is evicted silently.
- A word is stored in plaintext only after its third sentence-initial use. The first and second uses write only a salted, truncated SHA-256 hash of the word (`pending-sentstart-…-s1-f1.bin`; the salt is in `salt-sentstart.bin`, 16 random bytes created on first use and destroyed by “Erase everything saved”). The third use writes the word itself to `sentstart-…-s1-f1.tsent`.
- The words live in the same credential-protected `no_backup/` folder as the saved words, separately per language, under exactly the same rules: nothing is recorded in password or other private fields, in postal-address fields, before the first unlock after a restart, on the lock screen, or while Pause learning is on, and the words are offered only while the personal dictionary switch is on.
- The list is short and is not shown on its own card; “Erase everything saved” on the “Saved words” screen erases it with everything else. It is not part of a backup file: it relearns from your typing. If its file is damaged, it is moved aside into a quarantined copy, which “Erase everything saved” also deletes.

## Text shortcuts

Keyboard settings → Preferences → “Text shortcuts” holds the shortcuts you add yourself: a short word and the longer text it turns into.

- Each shortcut and its text are stored exactly as you entered them. Nothing is added from your typing: a shortcut exists only if you saved it on this screen or restored it from a backup file. At most 500 shortcuts, one list for all languages.
- The shortcuts live in the same credential-protected `no_backup/` folder as the saved words. Before the first unlock after a restart, nothing is read or written. They are never backed up by Android and never synced; they travel in a backup file you save yourself (see Backup).
- **How to erase it.** “Delete” on each shortcut, or “Erase everything saved.”

## Erase everything saved

Keyboard settings → Preferences → “Saved words” → “Erase everything saved.” The action is offered whenever the screen lists a saved entry or the keyboard's personal folder holds any file, also content the screen does not list (text shortcuts, refused corrections, sentence starts, half-learned hashes, quarantined copies). The recently used emoji list is kept outside that folder, so on its own it does not make the action appear; clear it with “Clear recent emoji” instead. After you confirm, it deletes, in this order:

1. The import journal (see Backup) and its temporary file. If either stays, the erase stops here and says it could not erase everything, so that a later rollback cannot bring erased data back.
2. The recently used emoji list.
3. For Tatar and Russian, whether or not the language is enabled: the saved words, learned word pairs, learned emoji, sentence starts and refused corrections, with their half-learned hashes, salts, quarantined copies and notice marks; then the text shortcuts and their quarantined copy.
4. Every other file still in the keyboard's personal folder, such as a temporary file left by an interrupted write.

Steps 3 and 4 run even when step 2 failed. The keyboard reports success only when every store reported its files gone, the personal folder is empty and the recent-emoji list is empty; otherwise it says that some of it is still saved. Your settings, the bundled dictionaries and any backup file you saved elsewhere are not touched.

## Backup

Keyboard settings → “Backup” can write one backup file with your settings and what the keyboard saved for you: your saved words, learned word pairs, learned emoji, refused corrections and text shortcuts. “Save to a file…” writes it, “Restore from a file…” reads such a file back. Both start only from your tap, and the file goes only where you choose in the system file picker.

- **The file is not encrypted.** Anyone who can open it can read your saved words. Keep it the way you would keep a note with those words.
- The app never uploads it. The app has no INTERNET permission; the file moves only if you move it, with whatever app you trust for that. That choice is yours and happens outside the keyboard.
- **Restoring replaces.** Restoring a file replaces the current settings, saved words, learned pairs, learned emoji, refused corrections and text shortcuts on this device with the file's contents; what was here before is lost. The screen asks for confirmation first. A file that fails any integrity check is rejected whole: nothing is changed.
- Damaged files stay out. Before saving or restoring, the keyboard opens every saved store, so a damaged file is moved aside into its quarantined copy (as described above) and is not put into the backup. If a store cannot be read at all, saving fails and restoring is refused before anything changes.
- **What is not in the file.** The recently used emoji list, the sentence starts, the half-learned words, pairs and emoji that have not crossed the learning threshold yet (with their salts), the quarantined copies, and your organization's device-policy values.
- An interrupted restore is undone. Before a restore changes anything, the keyboard writes a private journal of the old and the new state. If the restore is cut off (the phone restarts, the app is closed), the old state is restored the next time the keyboard starts or the settings open. “Erase everything saved” deletes this journal first, so an undo can never bring erased words back.
- Android's own backup stays off. Cloud backup and device-to-device transfer keep excluding everything, as described above; this file is the only backup, and it exists only when you make it.

## Pause learning

A single switch (keyboard settings → Preferences → “Pause learning”, off by default) pauses all personal learning.

- **What pauses.** No new word, pair, learned emoji, refused correction or sentence start is written, and the half-learned hashes of the stores that use them are left untouched: nothing is hashed, nothing expires, and the counters keep their values. The recently used emoji list is not added to either.
- **What does not pause.** What you already saved keeps working: saved words, learned pairs, learned emoji and sentence starts still appear in suggestions, a remembered refused correction keeps suppressing its correction, text shortcuts keep working, and the “Recent” tab still shows your recent emoji. This is a pause, not a wipe; the personal dictionary switch is the one that hides them.
- Turning it off resumes learning where it stopped. Nothing typed while it was on is learned afterwards; for the stores, those observations never happened.

## Contact

Privacy questions: open an issue in the project repository.
