package rkr.simplekeyboard.inputmethod.latin.dictionary.storage

import android.content.Context
import android.content.res.AssetManager
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.util.concurrent.Executor

object AndroidDictionaryStorageFactory {
    /**
     * Storage for ONE artifact. [artifact] names both the asset to inflate and the directory to
     * inflate it into ([DictionaryArtifactSpec.storageDirectoryName]), so a second language gets a
     * second controller rather than a second code path — and the Tatar one keeps addressing the
     * very directory and file name it published in 1.6.1.
     */
    @JvmStatic
    @JvmOverloads
    fun create(
        context: Context,
        executor: Executor,
        artifact: DictionaryArtifactSpec = DictionaryArtifactSpec.TATAR_TOP100K_V1,
    ): DictionaryStorageController {
        val deviceProtectedContext = context.createDeviceProtectedStorageContext()
        val store = AtomicDictionaryStore(
            directoryProvider = DeviceProtectedDirectoryProvider {
                File(deviceProtectedContext.filesDir, artifact.storageDirectoryName)
            },
            assetInputProvider = AssetInputProvider { spec ->
                context.assets.open(spec.assetPath, AssetManager.ACCESS_STREAMING)
            },
            clock = StorageClock(System::currentTimeMillis),
            spaceProbe = SpaceProbe(File::getUsableSpace),
            fileOps = AndroidDurableFileOps,
            supportedArtifacts = listOf(artifact),
        )
        return DictionaryStorageController(
            BackgroundDictionaryPreparer(executor, store, artifact),
            store,
        )
    }
}

/**
 * The one production [DurableFileOps]. `internal` (not `private`) so the personal store's
 * whole-file write path (E4a-2) can reuse the same fsync/rename/replace logic instead of
 * duplicating it. The dictionary-asset store keeps using [atomicRename]; the personal store uses
 * [atomicReplace].
 */
internal object AndroidDurableFileOps : DurableFileOps {
    override fun createNewFile(file: File): Boolean = file.createNewFile()

    override fun syncFile(fileDescriptor: FileDescriptor) {
        fileDescriptor.sync()
    }

    override fun atomicRename(source: File, destination: File) {
        if (destination.exists()) throw IOException("versioned destination already exists")
        translateErrno("rename dictionary") {
            Os.rename(source.absolutePath, destination.absolutePath)
        }
    }

    override fun atomicReplace(source: File, destination: File) {
        // POSIX rename(2) is atomic and replaces an existing destination in place. No pre-existence
        // check: unlike atomicRename, replacing the current file is exactly what E4a-2 needs.
        translateErrno("replace personal file") {
            Os.rename(source.absolutePath, destination.absolutePath)
        }
    }

    override fun syncDirectory(directory: File) {
        val descriptor = translateErrno("open dictionary directory") {
            Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        }
        var failure: IOException? = null
        try {
            translateErrno("fsync dictionary directory") { Os.fsync(descriptor) }
        } catch (error: IOException) {
            failure = error
        } finally {
            try {
                translateErrno("close dictionary directory") { Os.close(descriptor) }
            } catch (closeError: IOException) {
                if (failure == null) failure = closeError else failure.addSuppressed(closeError)
            }
        }
        failure?.let { throw it }
    }

    override fun delete(file: File): Boolean = file.delete()

    private inline fun <T> translateErrno(operation: String, block: () -> T): T = try {
        block()
    } catch (error: ErrnoException) {
        throw IOException("$operation failed: errno=${error.errno}", error)
    }
}
