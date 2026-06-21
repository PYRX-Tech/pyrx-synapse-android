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
 * PR 2 surface (Network + Identity):
 *   - identify(externalId, traits) — anonymous → known merge
 *   - alias(newExternalId)         — explicit anonymous → known merge
 *   - logout()                     — client-side identity clear
 *
 * Subsequent PRs (events, push, attribution) extend this object in place
 * rather than introducing new top-level entry points.
 *
 * Mirrors iOS `Pyrx` actor surface — every public method name + semantics
 * align so cross-platform docs can use one phrasing.
 */

package tech.pyrx.synapse

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.pyrx.synapse.identity.IdentityManager
import tech.pyrx.synapse.identity.IdentityResult
import tech.pyrx.synapse.network.HTTPClient
import tech.pyrx.synapse.network.HTTPSession
import tech.pyrx.synapse.network.JSONValue
import tech.pyrx.synapse.network.OkHttpSession
import tech.pyrx.synapse.network.WireEnvironment
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

    @Volatile
    private var httpClient: HTTPClient? = null

    @Volatile
    private var identityManager: IdentityManager? = null

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
            sessionOverride = null,
        )
    }

    /**
     * Test seam. Production callers always use the two-argument overload.
     * Allows the unit tests to inject an
     * [tech.pyrx.synapse.storage.InMemoryStorage] and / or a `MockHTTPSession`
     * without touching the real Keystore / real network.
     */
    @Throws(PyrxError::class)
    internal suspend fun initialize(
        context: Context,
        config: PyrxConfig,
        storageOverride: PyrxStorage?,
        sessionOverride: HTTPSession?,
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

            // Build the network + identity layer now (not lazily) so the
            // first call to identify() pays no construction cost on the
            // request path.
            val session = sessionOverride ?: OkHttpSession()
            val client = HTTPClient(config = config, session = session)
            val identity =
                IdentityManager(
                    storage = store,
                    httpClient = client,
                    environment = WireEnvironment.from(config.environment),
                    logger = logger,
                )

            this.config = config
            this.storage = store
            this.anonymousId = anon
            this.httpClient = client
            this.identityManager = identity

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

    // MARK: - Identity API (PR 2)

    /**
     * Identify an anonymous SDK session into a known contact.
     *
     * Sends both the on-disk anonymousId and [externalId] to the backend
     * `/v1/identify` endpoint; the server performs the merge and returns
     * which path ran (known_exists / first_sighting / no_anonymous).
     *
     * After success, [externalId] is persisted to encrypted storage so
     * subsequent events carry it as `external_id`. The anonymousId is
     * KEPT for audit purposes (the server has already re-attributed
     * historical events).
     *
     * @param externalId canonical user identifier (e.g. your user id from
     *                   pyrx.auth, your CRM, or your DB).
     * @param traits optional contact attributes — shallow-merged into
     *               `Contact.properties` server-side.
     * @return The server's [IdentityResult] for support / diagnostics.
     * @throws PyrxError.NotInitialized if [initialize] has not completed.
     * @throws PyrxError.InvalidConfig for blank externalId.
     * @throws PyrxError.Network on transport / HTTP / decode failure.
     * @throws PyrxError.StorageFailure on persist failure.
     */
    @Throws(PyrxError::class)
    public suspend fun identify(
        externalId: String,
        traits: Map<String, JSONValue>? = null,
    ): IdentityResult {
        val manager = identityManager ?: throw PyrxError.NotInitialized
        return manager.identify(externalId = externalId, traits = traits)
    }

    /**
     * Explicitly merge an anonymous session into a known contact.
     *
     * Use when you have a separate user id you want to attach to all prior
     * anonymous activity (e.g., the user signs up — your backend mints a
     * permanent user id distinct from any device-local identifier).
     *
     * @param newExternalId the canonical identity to merge into.
     * @throws PyrxError.NotInitialized if [initialize] has not completed.
     * @throws PyrxError.InvalidConfig for blank newExternalId.
     * @throws PyrxError.Network on transport / HTTP / decode failure.
     * @throws PyrxError.StorageFailure on persist failure.
     */
    @Throws(PyrxError::class)
    public suspend fun alias(newExternalId: String): IdentityResult {
        val manager = identityManager ?: throw PyrxError.NotInitialized
        return manager.alias(newExternalId = newExternalId)
    }

    /**
     * Client-side identity clear. Does NOT call the server.
     *
     * After [logout]:
     *   - EXTERNAL_ID is removed from encrypted storage.
     *   - ANONYMOUS_ID is preserved (subsequent events flow as
     *     `external_id = anonymousId`).
     *   - DEVICE_TOKEN is preserved (the device row remains valid; the
     *     server will re-attribute it to the next identify call).
     *
     * @throws PyrxError.NotInitialized if [initialize] has not completed.
     * @throws PyrxError.StorageFailure on delete failure.
     */
    @Throws(PyrxError::class)
    public suspend fun logout() {
        val manager = identityManager ?: throw PyrxError.NotInitialized
        manager.logout()
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
        httpClient = null
        identityManager = null
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
