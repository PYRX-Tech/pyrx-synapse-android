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
 * PR 3 surface (Events + Offline Queue):
 *   - track(eventName, properties)  — enqueue a custom event; flush async
 *   - screen(screenName, properties) — enqueue a `$screen` event; flush async
 *
 * Subsequent PRs (push, attribution) extend this object in place rather than
 * introducing new top-level entry points.
 *
 * Mirrors iOS `Pyrx` actor surface — every public method name + semantics
 * align so cross-platform docs can use one phrasing.
 */

package tech.pyrx.synapse

import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.pyrx.synapse.events.EventsManager
import tech.pyrx.synapse.identity.IdentityManager
import tech.pyrx.synapse.identity.IdentityResult
import tech.pyrx.synapse.network.HTTPClient
import tech.pyrx.synapse.network.HTTPSession
import tech.pyrx.synapse.network.JSONValue
import tech.pyrx.synapse.network.OkHttpSession
import tech.pyrx.synapse.network.WireEnvironment
import tech.pyrx.synapse.queue.EventQueue
import tech.pyrx.synapse.queue.EventQueueDatabase
import tech.pyrx.synapse.queue.NetworkCallbackReachability
import tech.pyrx.synapse.queue.QueuedEventDao
import tech.pyrx.synapse.queue.Reachability
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

    @Volatile
    private var eventQueue: EventQueue? = null

    @Volatile
    private var eventsManager: EventsManager? = null

    @Volatile
    private var reachability: Reachability? = null

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
            daoOverride = null,
            reachabilityOverride = null,
        )
    }

    /**
     * Test seam. Production callers always use the two-argument overload.
     * Allows the unit tests to inject:
     *   - an [tech.pyrx.synapse.storage.InMemoryStorage] (skip Keystore)
     *   - a `MockHTTPSession` (skip network)
     *   - a Room in-memory [QueuedEventDao] (skip on-disk SQLite)
     *   - a hand-rolled [Reachability] (skip ConnectivityManager)
     */
    @Throws(PyrxError::class)
    internal suspend fun initialize(
        context: Context,
        config: PyrxConfig,
        storageOverride: PyrxStorage?,
        sessionOverride: HTTPSession?,
        daoOverride: QueuedEventDao?,
        reachabilityOverride: Reachability?,
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

            // PR 3 — events + offline queue. Build the DAO (real Room db or
            // injected in-memory for tests), then the EventQueue and
            // EventsManager. Drain triggers wire up here:
            //   - reachability flips to SATISFIED → drain
            //   - this initialize() call itself → one drain so cold-start
            //     flushes events left over from a previous app session
            val dao = daoOverride ?: EventQueueDatabase.create(context.applicationContext).queuedEventDao()
            val queue =
                EventQueue(
                    httpClient = client,
                    dao = dao,
                    maxQueueSize = config.maxQueueSize,
                    logger = logger,
                )
            val events =
                EventsManager(
                    queue = queue,
                    storage = store,
                    anonymousId = anon,
                    logger = logger,
                )
            val reach = reachabilityOverride ?: NetworkCallbackReachability(context.applicationContext)
            queue.bindReachability(reach)

            this.config = config
            this.storage = store
            this.anonymousId = anon
            this.httpClient = client
            this.identityManager = identity
            this.eventQueue = queue
            this.eventsManager = events
            this.reachability = reach

            logger.info {
                "Initialized PYRXSynapse v${PyrxConstants.SDK_VERSION} " +
                    "(workspace=${config.workspaceId}, env=${config.environment})"
            }

            // Kick a drain so events persisted in a previous session get a
            // shot at flushing immediately. Fire-and-forget — we do not
            // block initialize() on network I/O.
            queue.drainNow()
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

    // MARK: - Events API (PR 3)

    /**
     * Track a custom event. Enqueues to the on-disk offline queue and
     * triggers an asynchronous flush. Returns once the event is durably in
     * the queue (Room insert + bound enforcement) — network success/failure
     * is handled asynchronously by the drain loop.
     *
     * Wire shape:
     *   POST /v1/events
     *   {
     *     "external_id":     <user externalId, or device anonymousId>,
     *     "event_name":      <eventName>,
     *     "attributes":      <properties>,
     *     "idempotency_key": <SDK-generated UUID>,
     *     "occurred_at":     <ISO-8601 UTC>
     *   }
     *
     * @param eventName Caller-supplied event name. Trimmed; must not be
     *                  blank after trimming.
     * @param properties Optional caller-supplied attributes. Maps onto the
     *                   wire `attributes` field.
     * @throws PyrxError.NotInitialized if [initialize] has not completed.
     * @throws PyrxError.InvalidConfig on blank eventName.
     * @throws PyrxError.StorageFailure on storage read failure.
     */
    @Throws(PyrxError::class)
    public suspend fun track(
        eventName: String,
        properties: Map<String, JSONValue>? = null,
    ) {
        val manager = eventsManager ?: throw PyrxError.NotInitialized
        manager.track(eventName = eventName, properties = properties)
    }

    /**
     * Track a screen view. Wire shape uses the canonical event name
     * `"$screen"` with `attributes.screen_name = screenName`. Mirrors iOS
     * + browser SDK so cross-platform analytics consumers can split on
     * `event_name == "$screen"` regardless of source platform.
     *
     * @param screenName Human-readable screen identifier. Trimmed; must not
     *                   be blank after trimming.
     * @param properties Optional caller-supplied attributes. Merged with
     *                   `screen_name` (SDK-stamped value wins on conflict).
     * @throws PyrxError.NotInitialized if [initialize] has not completed.
     * @throws PyrxError.InvalidConfig on blank screenName.
     * @throws PyrxError.StorageFailure on storage read failure.
     */
    @Throws(PyrxError::class)
    public suspend fun screen(
        screenName: String,
        properties: Map<String, JSONValue>? = null,
    ) {
        val manager = eventsManager ?: throw PyrxError.NotInitialized
        manager.screen(screenName = screenName, properties = properties)
    }

    // MARK: - Internal seam for tests

    /**
     * Reset the singleton's state. Test-only — package-internal so it is
     * never visible from a host app's classpath. Production callers cannot
     * "uninitialize" the SDK.
     */
    internal fun resetForTesting() {
        // Tear down background jobs first so the launched drain coroutines
        // can't outlive the reset. shutdown() is a suspend function; we
        // bridge through runBlocking only in the test path — production
        // never calls this method.
        eventQueue?.let { queue ->
            runCatching { runBlocking { queue.shutdown() } }
        }
        reachability?.stop()
        config = null
        storage = null
        anonymousId = null
        httpClient = null
        identityManager = null
        eventQueue = null
        eventsManager = null
        reachability = null
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
