package rkr.simplekeyboard.inputmethod.latin.dictionary.storage

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

class AtomicDictionaryStore(
    private val directoryProvider: DeviceProtectedDirectoryProvider,
    private val assetInputProvider: AssetInputProvider,
    private val clock: StorageClock,
    private val spaceProbe: SpaceProbe,
    private val fileOps: DurableFileOps,
    private val supportedArtifacts: List<DictionaryArtifactSpec>,
    private val validator: TdictValidator = TdictValidator(),
) : PublishedDictionaryCatalog {
    init {
        require(supportedArtifacts.isNotEmpty())
        require(supportedArtifacts.map { it.generation }.distinct().size == supportedArtifacts.size)
        require(supportedArtifacts.map { it.finalFileName }.distinct().size == supportedArtifacts.size)
        // One store serves exactly one family, in that family's own directory. Retention counts
        // files by [finalFilePattern] and the process-wide lease map is keyed by directory, so a
        // store spanning two families would count the other language's file against this one's
        // retention budget and would refuse to activate it while the other holds a lease.
        require(supportedArtifacts.map { it.family }.distinct().size == 1)
        require(supportedArtifacts.map { it.storageDirectoryName }.distinct().size == 1)
    }

    /** The one family this store owns; every name it writes, matches or deletes carries it. */
    private val temporaryPrefix: String = supportedArtifacts.first().temporaryFilePrefix
    private val finalFilePattern: Regex = supportedArtifacts.first().finalFilePattern

    fun ensurePublished(spec: DictionaryArtifactSpec): PreparationResult {
        if (supportedArtifacts.none { it == spec }) {
            return PreparationResult.Unavailable(StorageFailure.INVALID_ASSET)
        }
        return try {
            val directory = directoryProvider.dictionaryDirectory()
            val sharedState = ProcessDictionaryStorageOwner.stateFor(directory)
            synchronized(sharedState.lock) {
                ensurePublishedLocked(spec, directory, sharedState)
            }
        } catch (error: DictionaryValidationException) {
            PreparationResult.Unavailable(StorageFailure.INVALID_ASSET)
        } catch (error: RetentionBlockedException) {
            PreparationResult.Unavailable(StorageFailure.RETENTION_BLOCKED)
        } catch (error: IOException) {
            PreparationResult.Unavailable(StorageFailure.IO)
        } catch (error: SecurityException) {
            PreparationResult.Unavailable(StorageFailure.IO)
        } catch (error: Exception) {
            PreparationResult.Unavailable(StorageFailure.IO)
        }
    }

    override fun acquireLatestForActivation(): DictionaryFileLease? = try {
        val directory = directoryProvider.dictionaryDirectory()
        val sharedState = ProcessDictionaryStorageOwner.stateFor(directory)
        synchronized(sharedState.lock) {
            acquireLatestForActivationLocked(directory, sharedState)
        }
    } catch (_: Exception) {
        null
    }

    private fun acquireLatestForActivationLocked(
        directory: File,
        sharedState: SharedDictionaryStorageState,
    ): DictionaryFileLease? {
        if (!directory.isDirectory) return null
        val activeNames = sharedState.leaseCounts.filterValues { it > 0 }.keys
        for (spec in supportedArtifacts.sortedByDescending { it.generation }) {
            val file = File(directory, spec.finalFileName)
            if (!file.isFile) continue
            val validated = try {
                validator.validateRaw(file, spec)
            } catch (error: Exception) {
                continue
            }
            if (activeNames.isNotEmpty() && file.name !in activeNames) {
                // The newest valid file is staged. It cannot become active until every reader of
                // the old version has stopped and its lease has been closed.
                return null
            }
            val published = validated.toPublished(spec, file)
            val previousCount = sharedState.leaseCounts[file.name] ?: 0
            if (previousCount == Int.MAX_VALUE) return null
            sharedState.leaseCounts[file.name] = previousCount + 1
            return DictionaryFileLease(published) {
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
            val sharedState = ProcessDictionaryStorageOwner.stateFor(directory)
            synchronized(sharedState.lock) {
                cleanupReleasedVersionsLocked(directory, sharedState)
            }
        } catch (_: Exception) {
            // Cleanup is best-effort and must never affect keyboard input or an active lease.
        }
    }

    private fun cleanupReleasedVersionsLocked(
        directory: File,
        sharedState: SharedDictionaryStorageState,
    ) {
        if (!directory.isDirectory) return
        cleanupTemps(directory)
        val finals = managedFinals(directory)
        if (finals.size <= MAX_FINAL_ARTIFACTS) return

        val keep = mutableSetOf<String>()
        keep += sharedState.leaseCounts.filterValues { it > 0 }.keys
        supportedArtifacts.sortedByDescending { it.generation }
            .firstOrNull { File(directory, it.finalFileName).isFile }
            ?.let { keep += it.finalFileName }
        deleteUnprotectedUntil(directory, finals, keep, MAX_FINAL_ARTIFACTS)
    }

    private fun ensurePublishedLocked(
        spec: DictionaryArtifactSpec,
        directory: File,
        sharedState: SharedDictionaryStorageState,
    ): PreparationResult {
        ensureDirectory(directory)
        cleanupTemps(directory)

        val destination = File(directory, spec.finalFileName)
        if (destination.isFile) {
            try {
                val validated = validator.validateRaw(destination, spec)
                enforceStableRetention(directory, destination.name, sharedState)
                return PreparationResult.Published(
                    validated.toPublished(spec, destination),
                    alreadyPresent = true,
                )
            } catch (error: DictionaryValidationException) {
                if (isLeased(destination, sharedState)) throw error
                deleteAndSync(directory, destination)
            }
        }

        reservePublicationSlot(directory, sharedState)
        val requiredBytes = Math.addExact(spec.expectedRawSize, FREE_SPACE_RESERVE_BYTES)
        if (spaceProbe.usableBytes(directory) < requiredBytes) {
            return PreparationResult.Unavailable(StorageFailure.NO_SPACE)
        }

        val temporary = try {
            createExclusiveTemp(directory, destination.name)
        } catch (error: IOException) {
            if (isNoSpace(error)) {
                return PreparationResult.Unavailable(StorageFailure.NO_SPACE)
            }
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
            val validated = validator.validateRaw(temporary, spec)
            if (destination.exists()) {
                throw IOException("versioned destination appeared during publication")
            }
            fileOps.atomicRename(temporary, destination)
            fileOps.syncDirectory(directory)
            return PreparationResult.Published(
                validated.toPublished(spec, destination),
                alreadyPresent = false,
            )
        } catch (error: Exception) {
            if (temporary.exists()) {
                fileOps.delete(temporary)
            }
            if (error is DictionaryValidationException) throw error
            if (error is IOException) {
                if (isNoSpace(error)) {
                    return PreparationResult.Unavailable(StorageFailure.NO_SPACE)
                }
                throw error
            }
            throw IOException("dictionary publication failed", error)
        }
    }

    private fun ensureDirectory(directory: File) {
        if (directory.isDirectory) return
        if (directory.exists() || !directory.mkdirs()) {
            throw IOException("cannot create dictionary directory")
        }
        directory.parentFile?.let(fileOps::syncDirectory)
    }

    private fun cleanupTemps(directory: File) {
        val temporaryFiles = directory.listFiles { file ->
            file.isFile && file.name.startsWith(temporaryPrefix) && file.name.endsWith(TEMP_SUFFIX)
        } ?: throw IOException("cannot list dictionary directory")
        if (temporaryFiles.isEmpty()) return
        for (file in temporaryFiles) {
            if (!fileOps.delete(file) && file.exists()) {
                throw IOException("cannot remove stale dictionary temp")
            }
        }
        fileOps.syncDirectory(directory)
    }

    private fun enforceStableRetention(
        directory: File,
        currentName: String,
        sharedState: SharedDictionaryStorageState,
    ) {
        val keep = sharedState.leaseCounts.filterValues { it > 0 }.keys.toMutableSet()
        keep += currentName
        deleteUnprotectedUntil(
            directory,
            managedFinals(directory),
            keep,
            MAX_FINAL_ARTIFACTS,
        )
    }

    private fun reservePublicationSlot(
        directory: File,
        sharedState: SharedDictionaryStorageState,
    ) {
        val leased = sharedState.leaseCounts.filterValues { it > 0 }.keys
        if (leased.size > 1) throw RetentionBlockedException()
        val finals = managedFinals(directory)
        val keep = leased.toMutableSet()
        if (keep.isEmpty()) {
            supportedArtifacts.sortedByDescending { it.generation }
                .firstOrNull { candidate -> finals.any { it.name == candidate.finalFileName } }
                ?.let { keep += it.finalFileName }
        }
        deleteUnprotectedUntil(directory, finals, keep, MAX_FINALS_BEFORE_STAGING)
        if (managedFinals(directory).size > MAX_FINALS_BEFORE_STAGING) {
            throw RetentionBlockedException()
        }
    }

    private fun deleteUnprotectedUntil(
        directory: File,
        initialFiles: List<File>,
        protectedNames: Set<String>,
        limit: Int,
    ) {
        val remaining = initialFiles.toMutableList()
        val candidates = remaining
            .filterNot { it.name in protectedNames }
            .sortedBy { generationForFile(it) ?: Int.MIN_VALUE }
        var changed = false
        for (candidate in candidates) {
            if (remaining.size <= limit) break
            if (!fileOps.delete(candidate) && candidate.exists()) {
                throw IOException("cannot remove retired dictionary")
            }
            remaining.remove(candidate)
            changed = true
        }
        if (changed) fileOps.syncDirectory(directory)
        if (remaining.size > limit) throw RetentionBlockedException()
    }

    private fun deleteAndSync(directory: File, file: File) {
        if (!fileOps.delete(file) && file.exists()) {
            throw IOException("cannot remove invalid dictionary")
        }
        fileOps.syncDirectory(directory)
    }

    private fun managedFinals(directory: File): List<File> =
        directory.listFiles { file ->
            file.isFile && finalFilePattern.matches(file.name)
        }?.toList() ?: throw IOException("cannot list dictionary directory")

    private fun generationForFile(file: File): Int? = supportedArtifacts
        .firstOrNull { it.finalFileName == file.name }
        ?.generation

    private fun isLeased(
        file: File,
        sharedState: SharedDictionaryStorageState,
    ): Boolean = (sharedState.leaseCounts[file.name] ?: 0) > 0

    private fun createExclusiveTemp(directory: File, destinationName: String): File {
        val timestamp = clock.nowMillis()
        for (counter in 0 until MAX_TEMP_ATTEMPTS) {
            val file = File(
                directory,
                "$temporaryPrefix$destinationName.$timestamp.$counter$TEMP_SUFFIX",
            )
            if (fileOps.createNewFile(file)) return file
        }
        throw IOException("cannot create exclusive dictionary temp")
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

    private fun ValidatedDictionary.toPublished(
        spec: DictionaryArtifactSpec,
        file: File,
    ) = PublishedDictionary(
        generation = spec.generation,
        file = file,
        rawSize = rawSize,
        entryCount = entryCount,
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

class BackgroundDictionaryPreparer(
    private val executor: Executor,
    private val store: AtomicDictionaryStore,
    private val artifact: DictionaryArtifactSpec,
) {
    fun prepare(callback: (PreparationResult) -> Unit) {
        val taskStarted = AtomicBoolean(false)
        try {
            executor.execute {
                taskStarted.set(true)
                callback(store.ensurePublished(artifact))
            }
        } catch (error: RuntimeException) {
            if (taskStarted.get()) throw error
            callback(PreparationResult.Unavailable(StorageFailure.EXECUTOR_REJECTED))
        }
    }
}

/** Process-wide owner for every store instance addressing the same canonical directory. */
private object ProcessDictionaryStorageOwner {
    private val registryLock = Any()
    // Production has one fixed device-protected path. States are deliberately retained for the
    // process lifetime: evicting one could split lock/lease identity while a lease is still live.
    private val states = mutableMapOf<String, SharedDictionaryStorageState>()

    fun stateFor(directory: File): SharedDictionaryStorageState {
        val canonicalPath = directory.canonicalPath
        return synchronized(registryLock) {
            states.getOrPut(canonicalPath) { SharedDictionaryStorageState() }
        }
    }
}

private class SharedDictionaryStorageState {
    val lock = Any()
    val leaseCounts = mutableMapOf<String, Int>()
}
