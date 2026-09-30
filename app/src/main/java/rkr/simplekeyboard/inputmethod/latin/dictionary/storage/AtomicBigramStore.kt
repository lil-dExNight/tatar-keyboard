package rkr.simplekeyboard.inputmethod.latin.dictionary.storage

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Coordinates the bigram-table asset the way [AtomicDictionaryStore] coordinates the dictionary:
 * the same seams and the same temp -> fsync -> validate -> atomicRename -> syncDirectory sequence,
 * with its own file naming, pattern and [MAX_FINAL_ARTIFACTS] limit.
 *
 * It is a separate class because [ProcessBigramStorageOwner] is a separate process-wide registry
 * from [ProcessDictionaryStorageOwner]. Both are keyed by directory, and [directoryProvider] points
 * at a bigram-only subdirectory, so a dictionary lease and a bigram lease never share a lock.
 */
class AtomicBigramStore(
    // The seam's method is named for dictionaries, but the instance wired in here returns the
    // bigrams subdirectory.
    private val directoryProvider: DeviceProtectedDirectoryProvider,
    private val assetInputProvider: BigramAssetInputProvider,
    private val clock: StorageClock,
    private val spaceProbe: SpaceProbe,
    private val fileOps: DurableFileOps,
    private val supportedArtifacts: List<BigramArtifactSpec>,
    private val validator: TatBigrValidator = TatBigrValidator(),
) : PublishedBigramTableCatalog {
    init {
        require(supportedArtifacts.isNotEmpty())
        require(supportedArtifacts.map { it.generation }.distinct().size == supportedArtifacts.size)
        require(supportedArtifacts.map { it.finalFileName }.distinct().size == supportedArtifacts.size)
        // One store serves one family in one directory: retention scans by the family's file
        // pattern and the lease map is keyed by directory. Same rule as [AtomicDictionaryStore].
        require(supportedArtifacts.map { it.family }.distinct().size == 1)
        require(supportedArtifacts.map { it.storageDirectoryName }.distinct().size == 1)
    }

    private val temporaryPrefix: String = supportedArtifacts.first().temporaryFilePrefix
    private val finalFilePattern: Regex = supportedArtifacts.first().finalFilePattern

    /** Full raw validations run by this store instance. */
    internal var validationCount: Int = 0
        private set

    fun ensurePublished(spec: BigramArtifactSpec): BigramPreparationResult {
        if (supportedArtifacts.none { it == spec }) {
            return BigramPreparationResult.Unavailable(StorageFailure.INVALID_ASSET)
        }
        return try {
            val directory = directoryProvider.dictionaryDirectory()
            val sharedState = ProcessBigramStorageOwner.stateFor(directory)
            synchronized(sharedState.lock) {
                ensurePublishedLocked(spec, directory, sharedState)
            }
        } catch (error: BigramValidationException) {
            BigramPreparationResult.Unavailable(StorageFailure.INVALID_ASSET)
        } catch (error: RetentionBlockedException) {
            BigramPreparationResult.Unavailable(StorageFailure.RETENTION_BLOCKED)
        } catch (error: IOException) {
            BigramPreparationResult.Unavailable(StorageFailure.IO)
        } catch (error: SecurityException) {
            BigramPreparationResult.Unavailable(StorageFailure.IO)
        } catch (error: Exception) {
            BigramPreparationResult.Unavailable(StorageFailure.IO)
        }
    }

    override fun acquireLatestForActivation(): BigramTableLease? = try {
        val directory = directoryProvider.dictionaryDirectory()
        val sharedState = ProcessBigramStorageOwner.stateFor(directory)
        synchronized(sharedState.lock) {
            acquireLatestForActivationLocked(directory, sharedState)
        }
    } catch (_: Exception) {
        null
    }

    private fun acquireLatestForActivationLocked(
        directory: File,
        sharedState: SharedBigramStorageState,
    ): BigramTableLease? {
        if (!directory.isDirectory) return null
        val activeNames = sharedState.leaseCounts.filterValues { it > 0 }.keys
        for (spec in supportedArtifacts.sortedByDescending { it.generation }) {
            val file = File(directory, spec.finalFileName)
            if (!file.isFile) continue
            val validated = takeRecordedValidation(sharedState, file, spec) ?: try {
                validate(file, spec)
            } catch (error: Exception) {
                continue
            }
            if (activeNames.isNotEmpty() && file.name !in activeNames) {
                return null
            }
            val published = validated.toPublished(spec, file)
            val previousCount = sharedState.leaseCounts[file.name] ?: 0
            if (previousCount == Int.MAX_VALUE) return null
            sharedState.leaseCounts[file.name] = previousCount + 1
            sharedState.validations.remove(file.name)
            return BigramTableLease(published) {
                synchronized(sharedState.lock) {
                    val remaining = (sharedState.leaseCounts[file.name] ?: 1) - 1
                    if (remaining <= 0) sharedState.leaseCounts.remove(file.name)
                    else sharedState.leaseCounts[file.name] = remaining
                }
            }
        }
        return null
    }

    override fun cleanupReleasedVersions() {
        try {
            val directory = directoryProvider.dictionaryDirectory()
            val sharedState = ProcessBigramStorageOwner.stateFor(directory)
            synchronized(sharedState.lock) {
                cleanupReleasedVersionsLocked(directory, sharedState)
            }
        } catch (_: Exception) {
            // Cleanup is best-effort and must never affect keyboard input or an active lease.
        }
    }

    private fun cleanupReleasedVersionsLocked(directory: File, sharedState: SharedBigramStorageState) {
        if (!directory.isDirectory) return
        cleanupTemps(directory)
        val finals = managedFinals(directory)
        if (finals.size <= MAX_FINAL_ARTIFACTS) return

        val keep = mutableSetOf<String>()
        keep += sharedState.leaseCounts.filterValues { it > 0 }.keys
        supportedArtifacts.sortedByDescending { it.generation }
            .firstOrNull { File(directory, it.finalFileName).isFile }
            ?.let { keep += it.finalFileName }
        deleteUnprotectedUntil(directory, finals, keep, MAX_FINAL_ARTIFACTS, sharedState)
    }

    private fun ensurePublishedLocked(
        spec: BigramArtifactSpec,
        directory: File,
        sharedState: SharedBigramStorageState,
    ): BigramPreparationResult {
        ensureDirectory(directory)
        cleanupTemps(directory)

        val destination = File(directory, spec.finalFileName)
        if (destination.isFile) {
            try {
                val validated = validate(destination, spec)
                recordValidation(sharedState, destination, spec, validated)
                enforceStableRetention(directory, destination.name, sharedState)
                return BigramPreparationResult.Published(
                    validated.toPublished(spec, destination),
                    alreadyPresent = true,
                )
            } catch (error: BigramValidationException) {
                sharedState.validations.remove(destination.name)
                if (isLeased(destination, sharedState)) throw error
                deleteAndSync(directory, destination, sharedState)
            }
        }

        reservePublicationSlot(directory, sharedState)
        val requiredBytes = Math.addExact(spec.expectedRawSize, FREE_SPACE_RESERVE_BYTES)
        if (spaceProbe.usableBytes(directory) < requiredBytes) {
            return BigramPreparationResult.Unavailable(StorageFailure.NO_SPACE)
        }

        val temporary = try {
            createExclusiveTemp(directory, destination.name)
        } catch (error: IOException) {
            if (isNoSpace(error)) return BigramPreparationResult.Unavailable(StorageFailure.NO_SPACE)
            throw error
        }
        try {
            FileOutputStream(temporary).use { fileStream ->
                val buffered = BufferedOutputStream(fileStream, BUFFER_SIZE)
                assetInputProvider.open(spec).use { asset ->
                    validator.inflateAsset(asset, buffered, spec)
                }
                buffered.flush()
                fileOps.syncFile(fileStream.fd)
            }
            val validated = validate(temporary, spec)
            if (destination.exists()) {
                throw IOException("versioned destination appeared during publication")
            }
            fileOps.atomicRename(temporary, destination)
            fileOps.syncDirectory(directory)
            // The rename keeps the temp's length and modification time.
            recordValidation(sharedState, destination, spec, validated)
            return BigramPreparationResult.Published(
                validated.toPublished(spec, destination),
                alreadyPresent = false,
            )
        } catch (error: Exception) {
            if (temporary.exists()) fileOps.delete(temporary)
            if (error is BigramValidationException) throw error
            if (error is IOException) {
                if (isNoSpace(error)) return BigramPreparationResult.Unavailable(StorageFailure.NO_SPACE)
                throw error
            }
            throw IOException("bigram table publication failed", error)
        }
    }

    private fun ensureDirectory(directory: File) {
        if (directory.isDirectory) return
        if (directory.exists() || !directory.mkdirs()) {
            throw IOException("cannot create bigram directory")
        }
        directory.parentFile?.let(fileOps::syncDirectory)
    }

    private fun cleanupTemps(directory: File) {
        val temporaryFiles = directory.listFiles { file ->
            file.isFile && file.name.startsWith(temporaryPrefix) && file.name.endsWith(TEMP_SUFFIX)
        } ?: throw IOException("cannot list bigram directory")
        if (temporaryFiles.isEmpty()) return
        for (file in temporaryFiles) {
            if (!fileOps.delete(file) && file.exists()) {
                throw IOException("cannot remove stale bigram temp")
            }
        }
        fileOps.syncDirectory(directory)
    }

    private fun enforceStableRetention(
        directory: File,
        currentName: String,
        sharedState: SharedBigramStorageState,
    ) {
        val keep = sharedState.leaseCounts.filterValues { it > 0 }.keys.toMutableSet()
        keep += currentName
        deleteUnprotectedUntil(
            directory,
            managedFinals(directory),
            keep,
            MAX_FINAL_ARTIFACTS,
            sharedState,
        )
    }

    private fun reservePublicationSlot(directory: File, sharedState: SharedBigramStorageState) {
        val leased = sharedState.leaseCounts.filterValues { it > 0 }.keys
        if (leased.size > 1) throw RetentionBlockedException()
        val finals = managedFinals(directory)
        val keep = leased.toMutableSet()
        if (keep.isEmpty()) {
            supportedArtifacts.sortedByDescending { it.generation }
                .firstOrNull { candidate -> finals.any { it.name == candidate.finalFileName } }
                ?.let { keep += it.finalFileName }
        }
        deleteUnprotectedUntil(directory, finals, keep, MAX_FINALS_BEFORE_STAGING, sharedState)
        if (managedFinals(directory).size > MAX_FINALS_BEFORE_STAGING) {
            throw RetentionBlockedException()
        }
    }

    private fun deleteUnprotectedUntil(
        directory: File,
        initialFiles: List<File>,
        protectedNames: Set<String>,
        limit: Int,
        sharedState: SharedBigramStorageState,
    ) {
        val remaining = initialFiles.toMutableList()
        val candidates = remaining
            .filterNot { it.name in protectedNames }
            .sortedBy { generationForFile(it) ?: Int.MIN_VALUE }
        var changed = false
        for (candidate in candidates) {
            if (remaining.size <= limit) break
            sharedState.validations.remove(candidate.name)
            if (!fileOps.delete(candidate) && candidate.exists()) {
                throw IOException("cannot remove retired bigram table")
            }
            remaining.remove(candidate)
            changed = true
        }
        if (changed) fileOps.syncDirectory(directory)
        if (remaining.size > limit) throw RetentionBlockedException()
    }

    private fun deleteAndSync(
        directory: File,
        file: File,
        sharedState: SharedBigramStorageState,
    ) {
        sharedState.validations.remove(file.name)
        if (!fileOps.delete(file) && file.exists()) {
            throw IOException("cannot remove invalid bigram table")
        }
        fileOps.syncDirectory(directory)
    }

    private fun validate(file: File, spec: BigramArtifactSpec): ValidatedBigramTable {
        validationCount++
        return validator.validateRaw(file, spec)
    }

    private fun recordValidation(
        sharedState: SharedBigramStorageState,
        file: File,
        spec: BigramArtifactSpec,
        validated: ValidatedBigramTable,
    ) {
        val lastModified = file.lastModified()
        if (lastModified == 0L) {
            sharedState.validations.remove(file.name)
            return
        }
        sharedState.validations[file.name] =
            RecordedBigramValidation(file.length(), lastModified, spec, validated)
    }

    /** The recorded validation of [file] if the file still has the recorded length and time. */
    private fun takeRecordedValidation(
        sharedState: SharedBigramStorageState,
        file: File,
        spec: BigramArtifactSpec,
    ): ValidatedBigramTable? {
        val recorded = sharedState.validations[file.name] ?: return null
        val lastModified = file.lastModified()
        if (recorded.spec == spec && lastModified != 0L &&
            recorded.lastModified == lastModified && recorded.length == file.length()
        ) {
            return recorded.validated
        }
        sharedState.validations.remove(file.name)
        return null
    }

    private fun managedFinals(directory: File): List<File> =
        directory.listFiles { file ->
            file.isFile && finalFilePattern.matches(file.name)
        }?.toList() ?: throw IOException("cannot list bigram directory")

    private fun generationForFile(file: File): Int? = supportedArtifacts
        .firstOrNull { it.finalFileName == file.name }
        ?.generation

    private fun isLeased(file: File, sharedState: SharedBigramStorageState): Boolean =
        (sharedState.leaseCounts[file.name] ?: 0) > 0

    private fun createExclusiveTemp(directory: File, destinationName: String): File {
        val timestamp = clock.nowMillis()
        for (counter in 0 until MAX_TEMP_ATTEMPTS) {
            val file = File(directory, "$temporaryPrefix$destinationName.$timestamp.$counter$TEMP_SUFFIX")
            if (fileOps.createNewFile(file)) return file
        }
        throw IOException("cannot create exclusive bigram temp")
    }

    private fun isNoSpace(error: IOException): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val message = current.message.orEmpty().lowercase()
            if ("no space" in message || "enospc" in message) return true
            current = current.cause
        }
        return false
    }

    private fun ValidatedBigramTable.toPublished(
        spec: BigramArtifactSpec,
        file: File,
    ) = PublishedBigramTable(
        generation = spec.generation,
        fileLanguageTag = spec.fileLanguageTag,
        file = file,
        rawSize = rawSize,
        headCount = headCount,
        pairCount = pairCount,
        successVocabularyCount = successVocabularyCount,
        schemaId = schemaId,
        formatVersion = formatVersion,
        rawSha256 = rawSha256,
    )

    private class RetentionBlockedException : IOException()

    companion object {
        private const val BUFFER_SIZE = 8 * 1024
        private const val FREE_SPACE_RESERVE_BYTES = 64L * 1024L
        private const val MAX_TEMP_ATTEMPTS = 100
        private const val MAX_FINAL_ARTIFACTS = 2
        private const val MAX_FINALS_BEFORE_STAGING = 1
        private const val TEMP_SUFFIX = ".tmp"
    }
}

fun interface BigramAssetInputProvider {
    fun open(spec: BigramArtifactSpec): java.io.InputStream
}

/**
 * Process-wide owner for every bigram store instance addressing the same canonical directory; a
 * separate registry from [ProcessDictionaryStorageOwner] (see [AtomicBigramStore]).
 */
private object ProcessBigramStorageOwner {
    private val registryLock = Any()
    private val states = mutableMapOf<String, SharedBigramStorageState>()

    fun stateFor(directory: File): SharedBigramStorageState {
        val canonicalPath = directory.canonicalPath
        return synchronized(registryLock) {
            states.getOrPut(canonicalPath) { SharedBigramStorageState() }
        }
    }
}

private class SharedBigramStorageState {
    val lock = Any()
    val leaseCounts = mutableMapOf<String, Int>()

    /** Publication-time validations by file name; same rules as in [AtomicDictionaryStore]. */
    val validations = mutableMapOf<String, RecordedBigramValidation>()
}

private class RecordedBigramValidation(
    val length: Long,
    val lastModified: Long,
    val spec: BigramArtifactSpec,
    val validated: ValidatedBigramTable,
)
