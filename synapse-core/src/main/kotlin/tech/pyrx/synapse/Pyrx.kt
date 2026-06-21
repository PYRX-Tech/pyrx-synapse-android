/*
 * Pyrx.kt
 * PYRXSynapse — Android
 *
 * Public entry point for the PYRX Synapse Android SDK. Implemented as a
 * Kotlin `object` (singleton) so callers always reach `Pyrx.initialize(...)`
 * without instance management — matches the API surface every Android
 * engagement SDK uses (Firebase, OneSignal, Adjust, Branch).
 *
 * Thread-safety: state-mutating methods serialise through a [Mutex] so
 * `initialize` is safe to call from any coroutine context. Read paths use
 * `@Volatile` references where one-shot reads suffice.
 *
 * PR 1 surface (Foundation):
 *   - initialize(context, config) — validate + persist config, generate anonymousId
 *   - setLogLevel(level)          — adjust runtime verbosity
 *   - debugInfo()                 — snapshot for diagnostics
 *
 * PR 2 surface (Network + Identity) will add identify / alias / logout.
 * Subsequent PRs (events, push, attribution) extend this object in place
 * rather than introducing new top-level entry points.
 *
 * Mirrors iOS `Pyrx` actor surface — every public method names + semantics
 * align so cross-platform docs can use one phrasing.
 */

package tech.pyrx.synapse

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.pyrx.synapse.storage.EncryptedStore
import tech.pyrx.synapse.storage.PyrxStorage
import tech.pyrx.synapse.storage.PyrxStorageKey
import java.util.UUID

public object Pyrx {
    // MARK: - State

    private val initMutex: Mutex = Mutex()
    private val logger: PyrxLogger = PyrxLogger()

    @Volatile
    private var config: PyrxConfig? = null

    @Volatile
    private var storage: PyrxStorage? = null

    @Volatile
    private var anonymousId: String? = null

    // MARK: - Public API

    /**
     * Initialize the SDK. Must be called exactly once before any other API.
     * Safe to call from any coroutine — concurrent calls serialise through
     * an internal mutex.
     *
     * Calling twice with the **same** config is a no-op (logged at info).
     * Calling twice with a **different** config throws
     * [PyrxError.AlreadyInitialized].
     *
     * @param context Any [Context] — `applicationContext` is taken internally
     *                so passing an Activity will not leak it.
     * @param config Validated [PyrxConfig].
     * @throws PyrxError.InvalidConfig if config validation fails.
     * @throws PyrxError.AlreadyInitialized if called with a different config.
     * @throws PyrxError.StorageFailure if the EncryptedSharedPreferences
     *         file cannot be opened or written to.
     */
    @Throws(PyrxError::class)
    public suspend fun initialize(
        context: Context,
        config: PyrxConfig,
    ) {
        initialize(
            context = context,
            config = config,
            storageOverride = null,
        )
    }

    /**
     * Test seam. Production callers always use the two-argument overload.
     * Allows the unit tests to inject an [tech.pyrx.synapse.storage.InMemoryStorage]
     * without touching the real Keystore.
     */
    @Throws(PyrxError::class)
    internal suspend fun initialize(
        context: Context,
        config: PyrxConfig,
        storageOverride: PyrxStorage?,
    ) {
        initMutex.withLock {
            val existing = this.config
            if (existing != null) {
                if (existing == config) {
                    logger.info { "initialize(...) called twice with identical config — no-op." }
                    return
                }
                throw PyrxError.AlreadyInitialized
            }

            config.validate()
            logger.setLevel(config.logLevel)

            val store = storageOverride ?: EncryptedStore(context.applicationContext)
            val anon = ensureAnonymousId(store)

            this.config = config
            this.storage = store
            this.anonymousId = anon

            logger.info {
                "Initialized PYRXSynapse v${PyrxConstants.SDK_VERSION} " +
                    "(workspace=${config.workspaceId}, env=${config.environment})"
            }
        }
    }

    /** Adjust the runtime log level. Safe to call before or after [initialize]. */
    public fun setLogLevel(level: LogLevel) {
        logger.setLevel(level)
    }

    /**
     * Snapshot of the SDK's internal state. Useful for debug menus and
     * support bundles. Reading is non-throwing — missing storage values
     * surface as `false` / `null` rather than raising.
     */
    public fun debugInfo(): PyrxDebugInfo {
        val cfg = config
        val store = storage
        val externalId = store?.let { runCatchingStorage { it.get(PyrxStorageKey.EXTERNAL_ID) } }
        val deviceToken = store?.let { runCatchingStorage { it.get(PyrxStorageKey.DEVICE_TOKEN) } }
        return PyrxDebugInfo(
            sdkVersion = PyrxConstants.SDK_VERSION,
            platform = PyrxConstants.PLATFORM,
            initialized = cfg != null,
            workspaceId = cfg?.workspaceId,
            logLevel = logger.level,
            anonymousId = anonymousId,
            hasExternalId = externalId != null,
            hasDeviceToken = deviceToken != null,
        )
    }

    // MARK: - Internal seam for tests

    /**
     * Reset the singleton's state. Test-only — package-internal so it is
     * never visible from a host app's classpath. Production callers cannot
     * "uninitialize" the SDK.
     */
    internal fun resetForTesting() {
        config = null
        storage = null
        anonymousId = null
        logger.setLevel(LogLevel.INFO)
    }

    // MARK: - Private helpers

    /**
     * Returns the persisted anonymousId, generating + persisting a fresh
     * UUIDv4 if none exists. Mirrors iOS `ensureAnonymousId` and the
     * browser SDK's `getOrCreateAnonymousId`.
     */
    @Throws(PyrxError::class)
    private fun ensureAnonymousId(store: PyrxStorage): String {
        val existing = store.get(PyrxStorageKey.ANONYMOUS_ID)
        if (existing != null) return existing
        val fresh = UUID.randomUUID().toString()
        store.set(PyrxStorageKey.ANONYMOUS_ID, fresh)
        return fresh
    }

    /**
     * Best-effort storage read for [debugInfo]. We deliberately swallow
     * exceptions here — debug snapshots must never throw.
     */
    private inline fun runCatchingStorage(block: () -> String?): String? =
        try {
            block()
        } catch (_: Throwable) {
            null
        }
}
