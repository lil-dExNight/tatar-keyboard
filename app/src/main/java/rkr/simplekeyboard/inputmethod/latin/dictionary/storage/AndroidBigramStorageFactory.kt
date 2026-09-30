package rkr.simplekeyboard.inputmethod.latin.dictionary.storage

import android.content.Context
import android.content.res.AssetManager
import java.io.File
import java.util.concurrent.Executor

/**
 * Production wiring for the bigram-table store, the counterpart of
 * [AndroidDictionaryStorageFactory]. It points at [artifact]'s own device-protected subdirectory and
 * reuses [AndroidDurableFileOps]. The caller picks [artifact] via [DictionaryArtifactSpec.forSubtype].
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
