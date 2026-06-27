/*
 * EventQueue.kt
 * PYRXSynapse — Android
 *
 * Disk-backed bounded FIFO queue for events that cannot be sent immediately.
 * Drains to `POST /v1/events` with exponential backoff. All state-changing
 * operations serialise through a [Mutex] — concurrent `enqueue` / drain
 * calls cannot interleave.
 *
 * Persistence model
 * =================
 *
 *   Path: `<app internal>/databases/pyrx_event_queue.db` (Room — see
 *   [EventQueueDatabase]). One row per queued event. Inserts, updates, and
 *   deletes go through [QueuedEventDao] which is suspend-safe.
 *
 *   Crash-safety: Room wraps every DAO call in a SQLite transaction. We
 *   accept that an event enqueued but not yet committed may be lost on a
 *   hard kill — the alternative (per-event WAL sync) is too expensive for
 *   the modest durability guarantee this queue claims.
 *
 * Bounding
 * ========
 *
 *   `maxQueueSize` is enforced on enqueue. On overflow we drop the OLDEST
 *   event(s) — FIFO eviction — so the queue always reflects the most recent
 *   user activity. Mirrors iOS [EventQueue.enqueue].
 *
 * Retry policy
 * ============
 *
 *   On drain failure:
 *
 *     * Transport error (no HTTP response: DNS, timeout, etc.) — retain the
 *       event, schedule next attempt after exponential backoff.
 *
 *     * HTTP 4xx — DROP the event and log a warning. 4xx means the event is
 *       malformed (bad shape, bad external_id, schema validation failure).
 *       Infinite retry of a malformed event would block every good event
 *       behind it indefinitely.
 *
 *     * HTTP 5xx — retain the event, schedule next attempt after exponential
 *       backoff.
 *
 *   Backoff schedule: 1s, 2s, 4s, 8s, 16s, then capped at 60s. Counter resets
 *   on the first successful drain.
 *
 * Drain triggers
 * ==============
 *
 *   1. Explicit call from [EventQueue.enqueue] after appending a new event —
 *      gives near-immediate flush when online.
 *   2. Reachability flip to [ReachabilityStatus.SATISFIED] (network came
 *      back) — fires the drain loop without waiting for the next backoff
 *      tick.
 *   3. SDK init ([tech.pyrx.synapse.Pyrx.initialize]) calls [drainNow] once
 *      so events accumulated while the app was killed flush on relaunch.
 *
 * Concurrency
 * ===========
 *
 *   A single in-flight drain [Job] is held in [drainJob]. `enqueue` and
 *   `drainNow` both re-use the same in-flight job — only one drain is ever
 *   running at once across the SDK. A [Mutex] serialises mutations to the
 *   drain-job pointer + the consecutive-failure counter so reachability and
 *   enqueue triggers can race without corrupting state.
 *
 * Mirrors iOS `EventQueue.swift` semantics verbatim: same trigger set, same
 * backoff schedule, same 4xx drop policy, same at-enqueue external_id
 * capture timing.
 */

package tech.pyrx.synapse.queue

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.pyrx.synapse.Pyrx
import tech.pyrx.synapse.PyrxError
import tech.pyrx.synapse.PyrxLogger
import tech.pyrx.synapse.PyrxNetworkError
import tech.pyrx.synapse.network.EventAcceptedResponse
import tech.pyrx.synapse.network.EventIngestRequest
import tech.pyrx.synapse.network.HTTPClient
import tech.pyrx.synapse.observer.PyrxEvent

/**
 * Clock seam — production uses [kotlinx.coroutines.delay]; tests inject a
 * no-op implementation so unit tests do not actually pause for 1+ seconds
 * waiting for the backoff schedule.
 */
internal interface QueueClock {
    suspend fun sleep(millis: Long)
}

/** Production [QueueClock] backed by [kotlinx.coroutines.delay]. */
internal class SystemClock : QueueClock {
    override suspend fun sleep(millis: Long) {
        delay(millis)
    }
}

/**
 * Disk-backed, bounded, retrying event queue. One instance per SDK.
 *
 * Construction is cheap — no I/O until the first [enqueue] / [drainNow].
 * The first [drainNow] performs a lazy load from disk so events that were
 * enqueued in a previous app session are picked up on relaunch.
 *
 * @param httpClient The wire-level HTTP client (PR 2). Owned by [tech.pyrx
 *                   .synapse.Pyrx]; passed in here so the queue does not
 *                   re-couple to [tech.pyrx.synapse.PyrxConfig].
 * @param dao The Room DAO for [QueuedEventEntity].
 * @param maxQueueSize Maximum events on disk before FIFO eviction kicks in.
 *                     Refuses zero — that would disable queueing entirely.
 * @param logger Internal logger.
 * @param clock Sleep seam — defaults to [SystemClock].
 * @param scope Coroutine scope the queue launches background work into. The
 *              Pyrx singleton owns this scope's lifecycle. Defaults to a
 *              fresh [SupervisorJob] + [Dispatchers.IO] scope so any one
 *              failure does not cancel sibling jobs.
 */
internal class EventQueue(
    private val httpClient: HTTPClient,
    private val dao: QueuedEventDao,
    maxQueueSize: Int,
    private val logger: PyrxLogger,
    private val clock: QueueClock = SystemClock(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val maxQueueSize: Int = maxOf(1, maxQueueSize)

    /** Serialises mutations to [drainJob] + [consecutiveFailures]. */
    private val mutex = Mutex()

    /** Single in-flight drain job. New triggers attach to the existing job. */
    private var drainJob: Job? = null

    /** Exponential-backoff retry counter. Reset to 0 on success. */
    private var consecutiveFailures: Int = 0

    /**
     * Optional reachability binding. Held so [shutdown] can cancel the
     * collector job. `null` until [bindReachability] is called.
     */
    private var reachabilityJob: Job? = null

    /**
     * Privacy kill switch (Phase 8.4b Task 8.4b.10). When `false`, [enqueue]
     * still persists events to disk (so the SDK preserves user intent through
     * a disable→re-enable cycle) but the drain loop refuses to send anything
     * until tracking is re-enabled. Toggled exclusively by
     * `PrivacyManager.setTrackingEnabled(_:)`.
     *
     * @Volatile because the drain coroutine reads this on every loop entry
     * while the privacy actor mutates it from a different coroutine context.
     */
    @Volatile
    private var trackingEnabled: Boolean = true

    /**
     * Wall-clock timestamp (millis-since-epoch) of the most recent drain pass.
     * Updated on entry to [drainLoop] — even a no-op pass (tracking disabled)
     * leaves a trace so diagnostics can show the SDK is alive and the gating
     * decision is fresh.
     *
     * Surfaced via [debugLastDrainAt] / [PyrxDebugInfo.lastDrainAt]. Mirrors
     * iOS `EventQueue.lastDrainAt`.
     */
    @Volatile
    private var lastDrainAt: Long? = null

    // MARK: - Public API

    /**
     * Append [event] to the queue, persist, and trigger a drain. The caller
     * does NOT await the drain — [enqueue] returns as soon as the event is
     * durably in Room.
     *
     * Returns the number of events on disk AFTER the enqueue + bound
     * enforcement (so tests can assert FIFO eviction without re-reading the
     * table).
     */
    suspend fun enqueue(event: QueuedEvent): Int {
        dao.insert(QueuedEventEntity.fromDomain(event))
        enforceBound()
        val count = dao.count()
        startDrainIfIdle()
        return count
    }

    /**
     * Drain now and wait for the in-flight pass to complete. Used by
     * [tech.pyrx.synapse.Pyrx.initialize] to ensure pre-existing events get
     * a shot at flushing on cold start.
     */
    suspend fun drainNow() {
        startDrainIfIdle()
        val job = mutex.withLock { drainJob }
        job?.join()
    }

    /**
     * Bind a [Reachability] source. On every [ReachabilityStatus.SATISFIED]
     * transition the queue triggers a drain. Subscription survives for the
     * queue's lifetime — [EventQueue] outlives [Reachability] because both
     * are owned by [tech.pyrx.synapse.Pyrx].
     *
     * Calling twice is idempotent — the second binding is ignored.
     */
    fun bindReachability(reachability: Reachability) {
        if (reachabilityJob != null) return
        reachability.start()
        reachabilityJob =
            scope.launch {
                reachability.status
                    .distinctUntilChanged()
                    .filter { it == ReachabilityStatus.SATISFIED }
                    .onEach { logger.debug { "EventQueue reachability satisfied — triggering drain" } }
                    .collect { drainNow() }
            }
    }

    /**
     * Tear down background jobs. Cancels any in-flight drain and the
     * reachability collector. Used by [tech.pyrx.synapse.Pyrx.resetForTesting]
     * to give each test a clean slate.
     */
    suspend fun shutdown() {
        mutex.withLock {
            drainJob?.cancel()
            drainJob = null
            reachabilityJob?.cancel()
            reachabilityJob = null
        }
        scope.cancel()
    }

    /**
     * In-memory event count — used by tests to assert size without going
     * through DAO directly.
     */
    suspend fun count(): Int = dao.count()

    /**
     * Snapshot of the on-disk queue depth for diagnostics. Same value as
     * [count] but the spelling matches the public [tech.pyrx.synapse
     * .PyrxDebugInfo.eventQueueDepth] field so the call site reads
     * naturally. Mirrors iOS `EventQueue.debugQueueDepth`.
     */
    suspend fun debugQueueDepth(): Int = dao.count()

    /**
     * Snapshot of the most-recent drain timestamp (millis-since-epoch) for
     * diagnostics. Returns `null` until the queue has attempted to flush at
     * least once. Mirrors iOS `EventQueue.debugLastDrainAt`.
     */
    fun debugLastDrainAt(): Long? = lastDrainAt

    /**
     * Read the privacy gate. Used by diagnostics — `PrivacyManager` holds
     * its own copy as the source of truth, but the queue's copy is what
     * the drain loop actually consults, so we expose it for parity.
     * Mirrors iOS `EventQueue.debugTrackingEnabled`.
     */
    fun debugTrackingEnabled(): Boolean = trackingEnabled

    /**
     * Flip the privacy kill switch. When `enabled == false`:
     *   - The drain loop refuses to send anything (returns immediately on
     *     entry).
     *   - [enqueue] still persists events so they flush on re-enable.
     *
     * Idempotent — setting the same value twice is a no-op. Called by
     * `PrivacyManager.setTrackingEnabled`; never by host apps directly.
     */
    fun setTrackingEnabled(enabled: Boolean) {
        trackingEnabled = enabled
    }

    /**
     * GDPR cascade — drop every persisted event without sending. Called by
     * `PrivacyManager.deleteUser`. Idempotent; safe to call on an
     * already-empty queue.
     *
     * Cancels any in-flight drain so a concurrent post cannot reinstate a
     * row that the wipe was about to delete. Mirrors iOS `EventQueue.wipe`.
     */
    suspend fun wipe() {
        mutex.withLock {
            drainJob?.cancel()
            drainJob = null
            consecutiveFailures = 0
        }
        dao.deleteAll()
        logger.info { "EventQueue: wiped on privacy request." }
    }

    // MARK: - Private — drain loop

    /**
     * Start a drain pass if one isn't already in flight. The launched job
     * runs [drainLoop] and clears [drainJob] on completion so the next
     * trigger can start a fresh pass.
     */
    private suspend fun startDrainIfIdle() {
        mutex.withLock {
            if (drainJob?.isActive == true) return@withLock
            drainJob =
                scope.launch {
                    try {
                        drainLoop()
                    } finally {
                        mutex.withLock { drainJob = null }
                    }
                }
        }
    }

    /**
     * One drain pass. Pops events FIFO, POSTs each. Bounded by both
     * [MAX_PER_DRAIN] (iteration cap so one drain pass cannot starve other
     * work) and [MAX_RETRIES_PER_PASS] (so a single persistently-failing
     * event cannot consume the entire iteration budget).
     */
    @Suppress("LoopWithTooManyJumpStatements")
    private suspend fun drainLoop() {
        // Stamp every drain entry — even a no-op pass — so diagnostics can
        // show the SDK is alive and the gating decision is fresh.
        lastDrainAt = System.currentTimeMillis()

        // Privacy gate (Phase 8.4b Task 8.4b.10). When tracking is disabled
        // we still hold queued events on disk — but we refuse to send them
        // until the user re-opts-in. `PrivacyManager.setTrackingEnabled(true)`
        // explicitly calls `drainNow()` to kick this loop back into life.
        if (!trackingEnabled) {
            logger.debug { "EventQueue: drain skipped — tracking disabled (count=${dao.count()})." }
            return
        }

        var iterations = 0
        var retriesThisPass = 0
        // Phase 9.2.1 — observer fire-point counter. Tracks the number of
        // events flushed in this drain pass for PyrxEvent.QueueDrained.
        var successfullyFlushed = 0

        while (iterations < MAX_PER_DRAIN) {
            iterations += 1
            val head = dao.selectOldest(limit = 1).firstOrNull() ?: break

            val outcome = postOne(head.toDomain().toWireRequest())

            when (outcome) {
                Outcome.Success -> {
                    dao.delete(head)
                    consecutiveFailures = 0
                    retriesThisPass = 0
                    successfullyFlushed += 1
                }
                is Outcome.DropMalformed -> {
                    dao.delete(head)
                    logger.warning {
                        "EventQueue dropped event id=${head.id} name=${head.eventName} " +
                            "due to HTTP ${outcome.statusCode} (malformed event, not retrying)"
                    }
                    // Do NOT reset consecutiveFailures — a 4xx flood must not
                    // artificially shorten the backoff window for subsequent
                    // transient failures (matches iOS).
                }
                Outcome.Retry -> {
                    dao.update(head.copy(attemptCount = head.attemptCount + 1))
                    consecutiveFailures += 1
                    retriesThisPass += 1

                    if (retriesThisPass >= MAX_RETRIES_PER_PASS) {
                        logger.info {
                            "EventQueue drain paused — $retriesThisPass retries this pass exhausted; " +
                                "remaining=${dao.count()}"
                        }
                        return
                    }

                    val backoff = BACKOFF_MILLIS[minOf(consecutiveFailures - 1, BACKOFF_MILLIS.size - 1)]
                    logger.info {
                        "EventQueue retry attempt=$consecutiveFailures " +
                            "backoff=${backoff / 1000L}s remaining=${dao.count()}"
                    }
                    try {
                        clock.sleep(backoff)
                    } catch (_: kotlinx.coroutines.CancellationException) {
                        // Cancelled (e.g., shutdown). Exit cleanly — events
                        // remain in Room for the next drain. Rethrow to honour
                        // structured concurrency.
                        throw kotlinx.coroutines.CancellationException("EventQueue drain cancelled")
                    }
                }
            }
        }

        val remaining = dao.count()
        if (remaining == 0) {
            logger.debug { "EventQueue drained — 0 events remaining" }
        } else {
            logger.debug { "EventQueue drain paused — $remaining event(s) remaining" }
        }

        emitQueueDrainedIfProgress(successfullyFlushed)
    }

    /**
     * Phase 9.2.1 — observer fire-point helper extracted from
     * [drainLoop] so the loop body stays under detekt's `LongMethod`
     * budget (60 lines). Publishes [PyrxEvent.QueueDrained] only when
     * the drain pass made progress; a no-op drain (zero events
     * successfully flushed) is not worth notifying observers about.
     * Defensively swallows observer publish failures so a buggy
     * subscriber cannot break the queue's drain loop.
     */
    private fun emitQueueDrainedIfProgress(successfullyFlushed: Int) {
        if (successfullyFlushed == 0) return
        try {
            Pyrx.publishEvent(PyrxEvent.QueueDrained(count = successfullyFlushed))
        } catch (e: Throwable) {
            logger.warning { "EventQueue: observer publish failed — ${e.message}" }
        }
    }

    /** Result of attempting one event POST. */
    private sealed class Outcome {
        object Success : Outcome()

        data class DropMalformed(val statusCode: Int) : Outcome()

        object Retry : Outcome()
    }

    private suspend fun postOne(body: EventIngestRequest): Outcome =
        try {
            httpClient.post(
                endpoint = HTTPClient.Endpoint.EVENTS,
                bodySerializer = EventIngestRequest.serializer(),
                body = body,
                responseSerializer = EventAcceptedResponse.serializer(),
            )
            Outcome.Success
        } catch (e: PyrxError.Network) {
            mapNetworkError(e)
        }

    /**
     * Map a [PyrxError.Network] to a drain outcome. 4xx is a drop; everything
     * else (transport, decode, 5xx) is a retry.
     */
    private fun mapNetworkError(e: PyrxError.Network): Outcome {
        val inner = e.inner
        return if (inner is PyrxNetworkError.HttpStatus && inner.statusCode in HTTP_4XX_RANGE) {
            Outcome.DropMalformed(inner.statusCode)
        } else {
            Outcome.Retry
        }
    }

    /**
     * Enforce [maxQueueSize] by deleting the oldest rows over the cap. Runs
     * after every insert. Mirrors iOS `EventQueue` overflow handling.
     */
    private suspend fun enforceBound() {
        val current = dao.count()
        if (current <= maxQueueSize) return
        val overflow = current - maxQueueSize
        val toDrop = dao.selectOldestIds(limit = overflow)
        if (toDrop.isNotEmpty()) {
            dao.deleteByIds(toDrop)
            logger.warning { "EventQueue overflow — dropped ${toDrop.size} oldest event(s)" }
        }
    }

    companion object {
        /**
         * Maximum events POSTed in a single drain pass. The wire endpoint is
         * single-event today (no batch on /v1/events), so this is the maximum
         * number of sequential POSTs per drain. Capped at 100 to keep one
         * drain bounded — anything beyond waits for the next trigger.
         */
        const val MAX_PER_DRAIN: Int = 100

        /**
         * Maximum retries the FIRST event may consume within a single drain
         * pass before we yield to the next trigger. Keeps one bad event from
         * monopolising drain time when transient errors persist.
         */
        const val MAX_RETRIES_PER_PASS: Int = 6

        /** HTTP 4xx range — events here are dropped, not retried. */
        private val HTTP_4XX_RANGE: IntRange = 400..499

        /**
         * Exponential backoff schedule in milliseconds. `consecutiveFailures`
         * indexes into this array; values past the end clamp to the final
         * value (60s cap). Mirrors iOS `backoffNanos / 1_000_000`.
         */
        val BACKOFF_MILLIS: LongArray =
            longArrayOf(
                1_000L,
                2_000L,
                4_000L,
                8_000L,
                16_000L,
                60_000L,
            )
    }
}
