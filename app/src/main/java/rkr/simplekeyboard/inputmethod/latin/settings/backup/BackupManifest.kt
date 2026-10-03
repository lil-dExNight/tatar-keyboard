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

/**
 * The backup archive's `manifest.json`: the format version, the package the archive belongs to,
 * the writing app's versionCode, and the integrity list of every other entry.
 *
 * ```json
 * {
 *   "format": 1,
 *   "package": "org.tatarkeyboard.ime",
 *   "versionCode": 45,
 *   "entries": [
 *     {"path": "settings/preferences.xml", "size": 932, "sha256": "..."}
 *   ]
 * }
 * ```
 *
 * Parsing is exact: both key sets are fixed, every field is required, every type is checked, the
 * digest is 64 lowercase hex characters and the sizes are non-negative integers. The manifest is
 * written pretty-printed so the file stays inspectable.
 */
internal class BackupManifest(
    val format: Long,
    val packageName: String,
    val versionCode: Long,
    val entries: List<Entry>,
) {
    internal class Entry(val path: String, val size: Long, val sha256: String)

    fun serialize(): ByteArray {
        val entries = entries.map { entry ->
            linkedMapOf(
                "path" to entry.path,
                "size" to entry.size,
                "sha256" to entry.sha256,
            )
        }
        val root = linkedMapOf(
            "format" to format,
            "package" to packageName,
            "versionCode" to versionCode,
            "entries" to entries,
        )
        return pretty(BackupJson.write(root)).toByteArray(Charsets.UTF_8)
    }

    companion object {
        /** @throws BackupJson.JsonException on any violation, structural or type-level. */
        fun parse(bytes: ByteArray): BackupManifest {
            val text = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } catch (_: Exception) {
                throw BackupJson.JsonException("manifest is not UTF-8")
            }
            val root = BackupJson.parse(text) as? Map<*, *> ?: fail("manifest root must be an object")
            if (root.keys != setOf("format", "package", "versionCode", "entries")) {
                fail("manifest fields must be exactly format, package, versionCode, entries")
            }
            val format = requiredLong(root, "format")
            val packageName = root["package"] as? String ?: fail("package must be a string")
            val versionCode = requiredLong(root, "versionCode")
            if (versionCode < 0) fail("versionCode must be non-negative")
            val rawEntries = root["entries"] as? List<*> ?: fail("entries must be an array")
            val entries = rawEntries.map { raw ->
                val entry = raw as? Map<*, *> ?: fail("entry must be an object")
                if (entry.keys != setOf("path", "size", "sha256")) {
                    fail("entry fields must be exactly path, size, sha256")
                }
                val path = entry["path"] as? String ?: fail("path must be a string")
                val size = requiredLong(entry, "size")
                if (size < 0) fail("size must be non-negative")
                val sha256 = entry["sha256"] as? String ?: fail("sha256 must be a string")
                if (!SHA256_HEX.matches(sha256)) fail("sha256 must be 64 lowercase hex characters")
                Entry(path, size, sha256)
            }
            return BackupManifest(format, packageName, versionCode, entries)
        }

        private fun requiredLong(map: Map<*, *>, key: String): Long {
            val value = map[key] as? Long ?: fail("$key must be an integer")
            return value
        }

        private fun fail(message: String): Nothing = throw BackupJson.JsonException(message)

        private val SHA256_HEX = Regex("[0-9a-f]{64}")
    }
}

/** Two-space pretty printing over the compact writer's output, so the manifest stays readable. */
private fun pretty(compact: String): String {
    val out = StringBuilder()
    var depth = 0
    var inString = false
    var escaped = false
    for (char in compact) {
        if (inString) {
            out.append(char)
            if (escaped) {
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == '"') {
                inString = false
            }
            continue
        }
        when (char) {
            '"' -> {
                inString = true
                out.append(char)
            }
            '{', '[' -> {
                out.append(char)
                depth++
                out.append('\n')
                out.append("  ".repeat(depth))
            }
            '}', ']' -> {
                depth--
                out.append('\n')
                out.append("  ".repeat(depth))
                out.append(char)
            }
            ',' -> {
                out.append(char)
                out.append('\n')
                out.append("  ".repeat(depth))
            }
            ':' -> out.append(": ")
            else -> out.append(char)
        }
    }
    out.append('\n')
    return out.toString()
}
