/*
 * PyrxStorage.kt
 * PYRXSynapse — Android
 *
 * Abstract storage interface. Production builds use [EncryptedStore]
 * (EncryptedSharedPreferences-backed). Tests use [InMemoryStorage] to
 * avoid mutating the real Keystore — Keystore access requires a real
 * Android Context on instrumented tests, which we deliberately do not
 * run in PR 1.
 *
 * Mirrors iOS `PyrxStorage` protocol — same key set, same method signatures.
 */

package tech.pyrx.synapse.storage

/**
 * Well-known keys persisted by the SDK. String values are stable across
 * versions — never rename, or you'll orphan user-installed deviceTokens.
 */
public enum class PyrxStorageKey(public val rawValue: String) {
    ANONYMOUS_ID("anonymous_id"),
    EXTERNAL_ID("external_id"),
    DEVICE_TOKEN("device_token"),
    ;

    public companion object {
        /** Used by [PyrxStorage.wipe] to iterate every SDK-owned key. */
        public val ALL: List<PyrxStorageKey> = values().toList()
    }
}

/**
 * Synchronous key/value store. Implementations MUST be thread-safe.
 * Operations throw [tech.pyrx.synapse.PyrxError.StorageFailure] on IO /
 * Keystore failures so callers can pattern-match.
 */
public interface PyrxStorage {
    public fun get(key: PyrxStorageKey): String?

    public fun set(
        key: PyrxStorageKey,
        value: String,
    )

    public fun delete(key: PyrxStorageKey)

    /** GDPR cascade — remove every SDK-owned value. */
    public fun wipe()
}
