package rkr.simplekeyboard.inputmethod.latin.dictionary.storage

import java.io.File

/**
 * Proof that a store fully validated a published file's content in this process, handed to the
 * engine through the lease so the reader (`TdictPrefixIndex.open`, `TatBigrPrefixIndex.open`)
 * can skip its structural walk and repeat only the header and section-arithmetic checks.
 *
 * A token vouches for content (raw SHA-256 and size), not for a path. [matches] also requires
 * the process-local serial to be in the issuance registry, so a fabricated token never matches.
 */
class StoreValidationToken private constructor(
    /** Raw SHA-256 of the validated content. */
    val rawSha256: String,
    /** Raw size of the validated file in bytes. */
    val rawSize: Long,
    /** Modification time of the validated file at validation; 0 never matches a file. */
    val lastModified: Long,
    internal val serial: Long,
) {
    /**
     * True when [file] still has the length and modification time recorded at validation, the
     * same freshness rule as the store's recorded-validation reuse.
     */
    fun matchesFile(file: File): Boolean =
        lastModified != 0L && file.length() == rawSize && file.lastModified() == lastModified

    internal companion object {
        private val lock = Any()
        private var nextSerial = 1L

        /**
         * Live serials and the content each vouches for. Entries stay for the process lifetime:
         * a content binding cannot go stale, and a process publishes a handful of files.
         */
        private val live = HashMap<Long, Pair<String, Long>>()

        /** Mints a token; only a store calls this, over a validation it stands by. */
        fun issue(rawSha256: String, rawSize: Long, lastModified: Long): StoreValidationToken =
            synchronized(lock) {
                val serial = nextSerial++
                live[serial] = rawSha256 to rawSize
                StoreValidationToken(rawSha256, rawSize, lastModified, serial)
            }

        /** True when [token] came from [issue] here and vouches for exactly this content. */
        fun matches(token: StoreValidationToken?, rawSha256: String, rawSize: Long): Boolean {
            token ?: return false
            return synchronized(lock) { live[token.serial] == (rawSha256 to rawSize) }
        }
    }
}
