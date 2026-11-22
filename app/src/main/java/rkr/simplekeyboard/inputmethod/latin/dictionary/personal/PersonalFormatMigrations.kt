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

package rkr.simplekeyboard.inputmethod.latin.dictionary.personal

/** The personal binary containers covered by the s1-f1 migration contract. */
internal enum class PersonalStoreFormat {
    WORDS,
    BIGRAMS,
    EMOJI,
    REFUSALS,
    SENTENCE_STARTS,
    SHORTCUTS,
}

/**
 * A schema/container version, both fields unsigned 16-bit on disk. A plain class without a
 * generated `toString`, like the other personal models; equality and hashing are explicit because
 * registry paths and visited sets use versions as values.
 */
internal class PersonalFormatVersion(val schemaId: Int, val formatVersion: Int) {
    init {
        if (schemaId !in 1..0xffff || formatVersion !in 1..0xffff) {
            throw PersonalFormatMigrationException("invalid personal format version")
        }
    }

    override fun equals(other: Any?): Boolean =
        other is PersonalFormatVersion && schemaId == other.schemaId &&
            formatVersion == other.formatVersion

    override fun hashCode(): Int = 31 * schemaId + formatVersion
}

internal const val PERSONAL_S1_SCHEMA_ID = 1
internal const val PERSONAL_F1_FORMAT_VERSION = 1
internal val PERSONAL_S1_F1 = PersonalFormatVersion(PERSONAL_S1_SCHEMA_ID, PERSONAL_F1_FORMAT_VERSION)

/** A failure in migration registration, reading or writing; never includes user text or paths. */
internal class PersonalFormatMigrationException(message: String) : Exception(message)

/** Validates one serialized snapshot without changing it. */
internal fun interface PersonalFormatReader {
    fun read(bytes: ByteArray, subtypeId: String?)
}

/**
 * One registered step from an older container to a newer one. Both readers are mandatory: the
 * source reader must remain able to read [source] after the production writer switches, and the
 * target reader proves the migration's output before any destination file is replaced.
 *
 * [targetFileName] receives null only for the global shortcut store. Salt and pending-counter
 * files are deliberately not renamed by a step; a future format that changes their semantics must
 * register its own compatible handling rather than silently invalidating them.
 */
internal class PersonalFormatMigration(
    val kind: PersonalStoreFormat,
    val source: PersonalFormatVersion,
    val target: PersonalFormatVersion,
    val sourceReader: PersonalFormatReader,
    val targetReader: PersonalFormatReader,
    val targetFileName: (String?) -> String,
    val migrate: (ByteArray, String?) -> ByteArray,
) {
    init {
        val forward = target.schemaId > source.schemaId ||
            (target.schemaId == source.schemaId && target.formatVersion > source.formatVersion)
        if (!forward) throw PersonalFormatMigrationException("migration must move forward")
    }
}

/** An immutable, fully registered path from s1-f1 to [targetVersion]. */
internal class PersonalFormatMigrationPlan internal constructor(
    val kind: PersonalStoreFormat,
    val targetVersion: PersonalFormatVersion,
    internal val steps: List<PersonalFormatMigration>,
) {
    fun targetFileName(subtypeId: String?): String =
        steps.lastOrNull()?.targetFileName?.invoke(subtypeId)
            ?: PersonalFormats.s1f1FileName(kind, subtypeId)
}

/**
 * Registry and fail-closed writer gate for personal format migrations.
 *
 * s1-f1 needs no migration. Any other writer version is rejected until a complete forward path
 * from s1-f1 is registered. Registration of a later step also fails when its predecessor is
 * missing. Migration validates the source and every produced target snapshot in memory; callers
 * must still use the durable file coordinator for the actual replacement.
 */
internal class PersonalFormatMigrationRegistry {
    private val registered = ArrayList<PersonalFormatMigration>()

    @Synchronized
    fun register(step: PersonalFormatMigration) {
        if (findPath(step.kind, step.target) != null) {
            throw PersonalFormatMigrationException("migration target is already registered")
        }
        if (findPath(step.kind, step.source) == null) {
            throw PersonalFormatMigrationException("migration predecessor is not registered")
        }
        registered.add(step)
    }

    @Synchronized
    fun planFor(kind: PersonalStoreFormat, targetVersion: PersonalFormatVersion):
        PersonalFormatMigrationPlan? {
        val path = findPath(kind, targetVersion) ?: return null
        return PersonalFormatMigrationPlan(kind, targetVersion, path)
    }

    @Synchronized
    fun requireWriter(kind: PersonalStoreFormat, targetVersion: PersonalFormatVersion) {
        if (findPath(kind, targetVersion) == null) {
            throw PersonalFormatMigrationException("writer migration is not registered")
        }
    }

    /** Validates and migrates [sourceBytes]; returns the original bytes for an s1-f1 no-op plan. */
    fun migrate(
        plan: PersonalFormatMigrationPlan,
        sourceBytes: ByteArray,
        subtypeId: String?,
    ): ByteArray {
        if (plan.steps.isEmpty()) {
            currentReader(plan.kind).read(sourceBytes, subtypeId)
            return sourceBytes
        }
        var current = sourceBytes
        for (step in plan.steps) {
            step.sourceReader.read(current, subtypeId)
            current = step.migrate(current, subtypeId)
            step.targetReader.read(current, subtypeId)
        }
        return current
    }

    /** Revalidates bytes read back from a temporary destination before it is installed. */
    fun validateTarget(
        plan: PersonalFormatMigrationPlan,
        bytes: ByteArray,
        subtypeId: String?,
    ) {
        val reader = plan.steps.lastOrNull()?.targetReader ?: currentReader(plan.kind)
        reader.read(bytes, subtypeId)
    }

    /** The reader for the build's original s1-f1 fixtures and no-op round trips. */
    fun currentReader(kind: PersonalStoreFormat): PersonalFormatReader = when (kind) {
        PersonalStoreFormat.WORDS -> PersonalFormatReader { bytes, subtype ->
            TpersValidator().validate(bytes, requiredSubtype(subtype))
        }
        PersonalStoreFormat.BIGRAMS -> PersonalFormatReader { bytes, subtype ->
            TpersbValidator().validate(bytes, requiredSubtype(subtype))
        }
        PersonalStoreFormat.EMOJI -> PersonalFormatReader { bytes, subtype ->
            TpersemValidator().validate(bytes, requiredSubtype(subtype))
        }
        PersonalStoreFormat.REFUSALS -> PersonalFormatReader { bytes, subtype ->
            TrefValidator().validate(bytes, requiredSubtype(subtype))
        }
        PersonalStoreFormat.SENTENCE_STARTS -> PersonalFormatReader { bytes, subtype ->
            TsentValidator().validate(bytes, requiredSubtype(subtype))
        }
        PersonalStoreFormat.SHORTCUTS -> PersonalFormatReader { bytes, _ ->
            TcutValidator().validate(bytes)
        }
    }

    /** Must be called with [registered] held by the caller. */
    private fun findPath(
        kind: PersonalStoreFormat,
        targetVersion: PersonalFormatVersion,
    ): List<PersonalFormatMigration>? {
        if (targetVersion == PERSONAL_S1_F1) return emptyList()
        val pending = ArrayDeque<Pair<PersonalFormatVersion, List<PersonalFormatMigration>>>()
        pending.add(PERSONAL_S1_F1 to emptyList())
        val visited = HashSet<PersonalFormatVersion>()
        while (pending.isNotEmpty()) {
            val (source, path) = pending.removeFirst()
            if (!visited.add(source)) continue
            val outgoing = registered
                .filter { it.kind == kind && it.source == source }
                .sortedWith(compareBy({ it.target.schemaId }, { it.target.formatVersion }))
            for (step in outgoing) {
                val next = path + step
                if (step.target == targetVersion) return next
                pending.add(step.target to next)
            }
        }
        return null
    }

    private fun requiredSubtype(subtypeId: String?): String = subtypeId
        ?: throw PersonalFormatMigrationException("personal subtype is required")
}

/** Production writer gate and immutable names/caps of the original s1-f1 representation. */
internal object PersonalFormats {
    val migrations = PersonalFormatMigrationRegistry()

    /** Fast path allocates nothing for every current s1-f1 filename and serialization. */
    fun requireWriter(kind: PersonalStoreFormat, schemaId: Int, formatVersion: Int) {
        if (schemaId == PERSONAL_S1_SCHEMA_ID && formatVersion == PERSONAL_F1_FORMAT_VERSION) return
        migrations.requireWriter(kind, PersonalFormatVersion(schemaId, formatVersion))
    }

    /** These names stay frozen even after the active format constants move to a newer version. */
    fun s1f1FileName(kind: PersonalStoreFormat, subtypeId: String?): String = when (kind) {
        PersonalStoreFormat.WORDS -> "personal-$subtypeId-s1-f1.tpers"
        PersonalStoreFormat.BIGRAMS -> "personal-bigrams-$subtypeId-s1-f1.tpersb"
        PersonalStoreFormat.EMOJI -> "personal-emoji-$subtypeId-s1-f1.tpersem"
        PersonalStoreFormat.REFUSALS -> "personal-refused-$subtypeId-s1-f1.tref"
        PersonalStoreFormat.SENTENCE_STARTS -> "sentstart-$subtypeId-s1-f1.tsent"
        PersonalStoreFormat.SHORTCUTS -> "shortcuts-s1-f1.tcut"
    }

    /** Original caps, pinned independently of future format objects. */
    fun s1f1MaxBytes(kind: PersonalStoreFormat): Long = when (kind) {
        PersonalStoreFormat.WORDS -> 131_072L
        PersonalStoreFormat.BIGRAMS -> 65_536L
        PersonalStoreFormat.EMOJI -> 65_536L
        PersonalStoreFormat.REFUSALS -> 131_072L
        PersonalStoreFormat.SENTENCE_STARTS -> 4_096L
        PersonalStoreFormat.SHORTCUTS -> 65_536L
    }
}
