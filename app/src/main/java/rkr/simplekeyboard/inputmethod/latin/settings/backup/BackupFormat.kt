/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rkr.simplekeyboard.inputmethod.latin.settings.backup

import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TcutFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TcutValidator
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersemFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersemValidator
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersValidator
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersbFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersbValidator
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TrefFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TrefValidator
import rkr.simplekeyboard.inputmethod.latin.settings.Settings

/**
 * The fixed shape of the backup archive: one zip file the user picks a place for through the
 * Storage Access Framework.
 *
 * Entries, all under fixed canonical paths:
 * - `manifest.json` — format version, the package the archive belongs to, the writing app's
 *   versionCode, and the path, size and SHA-256 of every other entry;
 * - `settings/preferences.xml` — the settings, in the SharedPreferences XML shape;
 * - `personal/<file>` — one personal store file per language and kind (saved words, learned word
 *   pairs, learned emoji, refused corrections), under the file name the store itself uses.
 *
 * The importer never resolves an archive path against the filesystem: a path classifies into a
 * store identity (kind + subtype), and the destination file name is re-derived from that identity.
 * The canonical-path check is the second, independent gate.
 *
 * Not exported, on purpose: the pending learning counters and their salts (salted hashes of words
 * that have not graduated yet — useless without the live store and a privacy liability with it),
 * quarantined copies (already unreadable once) and the recent-emoji list (a recency cache, not
 * learned content).
 */
object BackupFormat {
    /** The only archive format this build reads and writes. */
    const val FORMAT_VERSION = 1

    /** The value of the manifest's `package` field: a format-level identity, not the install id. */
    const val ARCHIVE_PACKAGE = "org.tatarkeyboard.ime"

    const val MANIFEST_PATH = "manifest.json"
    const val SETTINGS_PATH = "settings/preferences.xml"
    const val PERSONAL_DIRECTORY = "personal"

    /** The file name the create-document picker is pre-filled with. */
    const val SUGGESTED_FILE_NAME = "tatar-keyboard-backup.zip"

    const val MAX_MANIFEST_BYTES = 64L * 1024L
    const val MAX_SETTINGS_BYTES = 256L * 1024L
    const val MAX_ENTRIES = 64
    const val MAX_PATH_LENGTH = 128

    /** Cap on the archive as picked (compressed). Far above any real backup. */
    const val MAX_ARCHIVE_BYTES = 8L * 1024L * 1024L

    /** Cap on the sum of uncompressed entry sizes; the zip bomb bound. */
    const val MAX_TOTAL_UNCOMPRESSED_BYTES = 4L * 1024L * 1024L

    /**
     * Preference keys that never enter an archive and are never applied from one. The restriction
     * cache belongs to the device's policy, not to the user: importing it could fake a policy, and
     * the real one is reloaded after every import anyway.
     */
    val INTERNAL_PREFERENCE_KEYS: Set<String> = setOf(Settings.ACTIVE_RESTRICTIONS)

    /** One kind of personal store file, with the pins of its frozen binary format. */
    enum class PersonalKind(
        val filePrefix: String,
        val fileExtension: String,
        val schemaId: Int,
        val formatVersion: Int,
        val maxBytes: Long,
    ) {
        WORDS("personal-", ".tpers", TpersFormat.SCHEMA_ID, TpersFormat.FORMAT_VERSION,
            TpersFormat.MAX_FILE_SIZE),
        PAIRS("personal-bigrams-", ".tpersb", TpersbFormat.SCHEMA_ID, TpersbFormat.FORMAT_VERSION,
            TpersbFormat.MAX_FILE_SIZE),
        EMOJI("personal-emoji-", ".tpersem", TpersemFormat.SCHEMA_ID, TpersemFormat.FORMAT_VERSION,
            TpersemFormat.MAX_FILE_SIZE),
        REFUSED("personal-refused-", ".tref", TrefFormat.SCHEMA_ID, TrefFormat.FORMAT_VERSION,
            TrefFormat.MAX_FILE_SIZE),
        // The text-shortcut store is global (no per-language file): its name carries no subtype.
        SHORTCUTS("shortcuts-", ".tcut", TcutFormat.SCHEMA_ID, TcutFormat.FORMAT_VERSION,
            TcutFormat.MAX_FILE_SIZE),
    }

    /** What one archive entry path means. */
    sealed interface EntryPath {
        /** The manifest itself; never listed inside the manifest. */
        data object Manifest : EntryPath

        /** The settings document. */
        data object Settings : EntryPath

        /** One personal store file: [kind] of [subtypeId]. */
        data class Personal(val kind: PersonalKind, val subtypeId: String) : EntryPath
    }

    /** The file name [kind] uses for [subtypeId], delegated to the format that owns it. */
    fun personalFileName(kind: PersonalKind, subtypeId: String): String = when (kind) {
        PersonalKind.WORDS -> TpersFormat.personalFileName(subtypeId)
        PersonalKind.PAIRS -> TpersbFormat.personalBigramsFileName(subtypeId)
        PersonalKind.EMOJI -> TpersemFormat.personalEmojiFileName(subtypeId)
        PersonalKind.REFUSED -> TrefFormat.refusedCorrectionsFileName(subtypeId)
        PersonalKind.SHORTCUTS -> TcutFormat.shortcutsFileName()
    }

    /** The archive path of a personal store file. */
    fun personalEntryPath(kind: PersonalKind, subtypeId: String): String =
        "$PERSONAL_DIRECTORY/${personalFileName(kind, subtypeId)}"

    /**
     * Parses a personal store file name into its kind and subtype, or null when the name is not
     * exactly what this build would write: the prefix and extension of one kind, the current schema
     * and format version, and a subtype that has a personal store at all. The longer prefixes are
     * tried first, because every kind's prefix starts with "personal-".
     */
    fun classifyPersonalFileName(name: String): Pair<PersonalKind, String>? {
        if (name == TcutFormat.shortcutsFileName()) return PersonalKind.SHORTCUTS to ""
        for (kind in PersonalKind.entries.sortedByDescending { it.filePrefix.length }) {
            if (!name.startsWith(kind.filePrefix) || !name.endsWith(kind.fileExtension)) continue
            val middle = name.removePrefix(kind.filePrefix).removeSuffix(kind.fileExtension)
            val tail = "-s${kind.schemaId}-f${kind.formatVersion}"
            if (!middle.endsWith(tail)) return null
            val tag = middle.dropLast(tail.length)
            if (tag.isEmpty() || !tag.all { it in 'a'..'z' || it in 'A'..'Z' || it == '_' }) return null
            if (!PersonalSubtypes.isSupported(tag)) return null
            return kind to tag
        }
        return null
    }

    /**
     * Classifies an archive entry path, or null when the path names nothing this build stores.
     * Unknown paths are rejected by the importer: a newer format that adds an entry kind must bump
     * [FORMAT_VERSION], and older builds fail closed on it.
     */
    fun classifyEntryPath(path: String): EntryPath? = when {
        path == MANIFEST_PATH -> EntryPath.Manifest
        path == SETTINGS_PATH -> EntryPath.Settings
        path.startsWith("$PERSONAL_DIRECTORY/") -> {
            val classified = classifyPersonalFileName(path.substring(PERSONAL_DIRECTORY.length + 1))
            classified?.let { EntryPath.Personal(it.first, it.second) }
        }
        else -> null
    }

    /**
     * The zip-slip gate, independent of classification: a relative path of clean segments only.
     * Rejects `..` and `.` segments, absolute paths, empty segments, backslashes, NULs, colons and
     * every non-ASCII character, so no entry can escape or confuse a writer even if one ever
     * resolved paths directly.
     */
    fun isCanonicalEntryPath(path: String): Boolean {
        if (path.isEmpty() || path.length > MAX_PATH_LENGTH) return false
        for (char in path) {
            val clean = char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' ||
                char == '.' || char == '_' || char == '-' || char == '/'
            if (!clean) return false
        }
        if (path.startsWith("/") || path.endsWith("/")) return false
        return path.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    /** The per-entry uncompressed size cap of a classified path. */
    fun maxEntryBytes(path: EntryPath): Long = when (path) {
        EntryPath.Manifest -> MAX_MANIFEST_BYTES
        EntryPath.Settings -> MAX_SETTINGS_BYTES
        is EntryPath.Personal -> path.kind.maxBytes
    }

    /** Runs the strict binary validator of the store kind over [bytes]; true only when it passes. */
    fun contentPasses(kind: PersonalKind, subtypeId: String, bytes: ByteArray): Boolean = try {
        when (kind) {
            PersonalKind.WORDS -> TpersValidator().validate(bytes, subtypeId)
            PersonalKind.PAIRS -> TpersbValidator().validate(bytes, subtypeId)
            PersonalKind.EMOJI -> TpersemValidator().validate(bytes, subtypeId)
            PersonalKind.REFUSED -> TrefValidator().validate(bytes, subtypeId)
            PersonalKind.SHORTCUTS -> TcutValidator().validate(bytes)
        }
        true
    } catch (_: Exception) {
        false
    }
}
