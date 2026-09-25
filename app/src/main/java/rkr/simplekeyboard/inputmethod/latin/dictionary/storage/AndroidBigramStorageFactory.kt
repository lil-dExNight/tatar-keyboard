package rkr.simplekeyboard.inputmethod.latin.dictionary.storage

import android.content.Context
import android.content.res.AssetManager
import java.io.File
import java.util.concurrent.Executor

/**
 * Production wiring for the bigram-table store — the E5c counterpart of
 * [AndroidDictionaryStorageFactory], pointed at [artifact]'s own device-protected subdirectory
 * (never `filesDir/dictionaries`) with its own artifact, own regex/retention
 * (`AtomicBigramStore`), and the same production [DurableFileOps] ([AndroidDurableFileOps]) the
 * dictionary store already uses — the fsync/rename semantics are format-agnostic.
 *
 * [artifact] is passed in rather than chosen here, exactly like [AndroidDictionaryStorageFactory]:
 * the choice of language is made once, in [DictionaryArtifactSpec.forSubtype], and this factory
 * only wires up whatever that choice produced.
 */
object AndroidBigramStorageFactory {
    @JvmStatic
    fun create(
        context: Context,
        executor: Executor,
        artifact: BigramArtifactSpec,
    ): BigramStorageController {
        val deviceProtectedContext = context.createDeviceProtectedStorageContext()
        val store = AtomicBigramStore(
            directoryProvider = DeviceProtectedDirectoryProvider {
                File(deviceProtectedContext.filesDir, artifact.storageDirectoryName)
            },
            assetInputProvider = BigramAssetInputProvider { spec ->
                context.assets.open(spec.assetPath, AssetManager.ACCESS_STREAMING)
            },
            clock = StorageClock(System::currentTimeMillis),
            spaceProbe = SpaceProbe(File::getUsableSpace),
            fileOps = AndroidDurableFileOps,
            supportedArtifacts = listOf(artifact),
        )
        return BigramStorageController(
            BackgroundBigramPreparer(executor, store, artifact),
            store,
        )
    }
}
