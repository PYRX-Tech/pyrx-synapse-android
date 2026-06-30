/*
 * InAppManager.kt
 * PYRXSynapse — Android — synapse-inapp module
 *
 * Phase 10 PR-2b — Android port of the browser SDK's
 * `SynapseInAppManager` (packages/sdk/src/in-app.ts, 605 LOC). Owns the
 * polling lifecycle, in-memory cache, telemetry round-trips,
 * soft-degrade backoff, and per-placement render-callback dispatch for
 * the in-app messaging surface.
 *
 * Boundary
 * ========
 *
 *   - This module owns: fetch lifecycle, in-memory cache, dismiss /
 *     impression / interaction telemetry, soft-degrade backoff,
 *     identity-gated polling.
 *   - This module does NOT own: pixels, animation, layout,
 *     accessibility — the host app's [InAppRenderCallback] does. There
 *     is ABSOLUTELY no Compose / View / Drawable code in this file by
 *     design (ADR-0008 D2; PYRX UI Kit is deferred to Phase 10.x).
 *
 * Authority
 * =========
 *
 *   - [ADR-0008](../../../docs/adr/ADR-0008-in-app-messaging-delivery-model.md)
 *     D1 pull delivery / D2 rendering-callback / D4 impression as the
 *     billable unit.
 *   - [ADR-0009](../../../docs/adr/ADR-0009-in-app-sdk-surface.md) D5
 *     5-event taxonomy extended to 7 (`InAppMessageReceived` +
 *     `InAppMessageDismissed` are wired through the [InAppBridge.show /
 *     dismiss] paths to `Pyrx.events`).
 *
 * Wire shape mirrors `synapse-api/app/schemas/in_app.py` verbatim —
 * `/v1/in-app/poll` query params (`contact_id`, repeated `placement`),
 * `/v1/in-app/log` body (`assignment_id`, `event`, optional `cta_id`),
 * `InAppLogResponse` fields (`log_id`, `billable`, `plan_limit_reached`,
 * `soft_degraded`). No client-side field-name transforms.
 *
 * The 10 lifecycle rules from PR #218 (cross-SDK symmetric)
 * =========================================================
 *
 *   1. Identity-gated polling — no poll until [contactIdProvider]
 *      returns non-null.
 *   2. Immediate poll on null → identified transition (via
 *      [notifyIdentityChanged]) when any placements are registered.
 *   3. Track-call refresh within `currentPollIntervalMillis` window
 *      short-circuits (no redundant fetch). Wired via
 *      [notifyTracked].
 *   4. Concurrent poll coalescing via single in-flight [Mutex] guard.
 *   5. Server-authoritative cache — evict messages absent from the
 *      latest poll response.
 *   6. Received observer dedupes by assignment id ([InAppMessage.id]).
 *   7. Auto-`markImpression` (POST `/v1/in-app/log` with
 *      `event="impressed"`) AFTER the render callback returns —
 *      billable per ADR-0008 D4.
 *   8. `soft_degraded: true` doubles polling interval (60s → 120s);
 *      next clean 200 resets to default.
 *   9. `plan_limit_reached: true` still surfaces message to the host
 *      callback; logs at warning level.
 *  10. No widget code.
 */

package tech.pyrx.synapse.inapp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tech.pyrx.synapse.PyrxConfig
import tech.pyrx.synapse.PyrxConstants
import tech.pyrx.synapse.network.HTTPClient
import tech.pyrx.synapse.network.HTTPSession
import tech.pyrx.synapse.observer.PyrxEvent
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicLong

/**
 * Concrete polling cache + telemetry manager for in-app messaging.
 *
 * Production callers never instantiate this — `PyrxInApp.install(...)`
 * does, then wraps it in a [SynapseInAppBridge] and registers via
 * `Pyrx.installInAppBridge(...)`. Tests instantiate directly with a
 * stub [tech.pyrx.synapse.network.HTTPSession] to inject canned
 * `/v1/in-app/poll` and `/v1/in-app/log` responses.
 *
 * Thread-safety
 * -------------
 * The cache + dedupe + log-queue mutations are serialised through
 * [cacheMutex]. The in-flight poll guard is [pollMutex]. Both are
 * fair-ish (Kotlin `Mutex` is FIFO). All public methods are `suspend`
 * — callers MUST be in a coroutine.
 *
 * Coroutine lifetime
 * ------------------
 * Owns an internal [CoroutineScope] backed by a [SupervisorJob] so a
 * single failed poll / telemetry POST does NOT cancel the manager.
 * Background polling launches into this scope. [shutdown] cancels it.
 *
 * @param httpClient The SDK's wire-level HTTP client (provides config
 *   + the 5 required headers). Reused so the in-app endpoints carry
 *   `X-WORKSPACE-ID` / `X-API-KEY` exactly like every other SDK call.
 * @param contactIdProvider Returns the SDK's resolved contact id for
 *   `/v1/in-app/poll?contact_id=...`. Externalid if set by identify,
 *   else null. The bridge calls this on every poll; identity changes
 *   are signaled out-of-band via [notifyIdentityChanged].
 * @param eventPublisher Forwards [PyrxEvent.InAppMessageReceived] /
 *   [PyrxEvent.InAppMessageDismissed] onto the `Pyrx.events`
 *   SharedFlow. Plumbed as a lambda (rather than a direct
 *   `Pyrx.publishEvent` call) so tests can capture emitted events
 *   without going through the global singleton.
 * @param logger Debug-tag-aware logger from synapse-core. Gated by
 *   [tech.pyrx.synapse.PyrxConfig.logLevel].
 * @param json The kotlinx.serialization codec — defaults to the one
 *   from synapse-core's [HTTPClient.defaultJson] so decode rules
 *   (`ignoreUnknownKeys`, `explicitNulls=false`) match every other
 *   SDK call. Tests can inject a stricter instance.
 * @param scope Internal coroutine scope — defaults to one rooted at
 *   [SupervisorJob] + [Dispatchers.Default]. Tests inject a `TestScope`
 *   so virtual time advances deterministically.
 * @param nowMillis Wall-clock seam for the track-call refresh window
 *   check. Defaults to [System.currentTimeMillis]. Tests inject a
 *   controlled lambda.
 * @param defaultPollIntervalMillis Default polling cadence — 60s per
 *   PR #218 / browser SDK. Exposed as a constructor parameter so tests
 *   can shrink it to make `withTimeout` assertions instant.
 * @param degradedPollMultiplier Polling-interval multiplier when the
 *   backend signals `soft_degraded: true`. Defaults to 2 (60s → 120s).
 * @param enableBackgroundTimer When `true` (production default), a
 *   background coroutine sleeps for the current poll interval and
 *   fires a poll on each tick. When `false` (test-only), the manager
 *   only polls in response to explicit [refresh] / [notifyTracked] /
 *   [notifyIdentityChanged] calls + the [show] initial poll. The flag
 *   exists because `kotlinx.coroutines.test`'s `runTest` +
 *   `advanceUntilIdle` will exhaust virtual time forever in pursuit
 *   of the never-ending `while (true) { delay(); poll() }` loop —
 *   tests turn the timer off and drive polls explicitly instead.
 */
@Suppress(
    // DI-heavy constructor by design. Each param is a separately-
    // mockable seam used by tests + the install bridge; bundling them
    // into a config struct would obscure the seams without removing
    // the params.
    "LongParameterList",
    // The manager surfaces 5 public methods (per ADR-0009 D5) plus
    // internal helpers for cache mutation, polling lifecycle, log
    // dispatch, and timer arithmetic. Extracting them would just hide
    // them behind a delegate; the class boundary is the right one.
    "TooManyFunctions",
)
public class InAppManager(
    private val config: PyrxConfig,
    private val session: HTTPSession,
    private val contactIdProvider: () -> String?,
    private val eventPublisher: (PyrxEvent) -> Unit,
    private val logger: (message: String) -> Unit = { /* no-op */ },
    private val json: Json = HTTPClient.defaultJson(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val defaultPollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
    private val degradedPollMultiplier: Int = DEGRADED_POLL_MULTIPLIER,
    private val enableBackgroundTimer: Boolean = true,
) {
    // ── State (all mutations through cacheMutex; readers tolerate stale view)

    /**
     * In-memory cache of currently-active messages, keyed by assignment
     * id. Server-authoritative (rule 5) — populated by poll responses,
     * evicted on dismiss, eviction-by-absence, or replacement.
     */
    private val activeMessages: MutableMap<String, InAppMessage> = HashMap()

    /**
     * Per-placement render callbacks registered via [show]. A placement
     * can have multiple callbacks (defensive — host apps that hot-
     * reload or compose multiple screens for the same placement key
     * might double-register; dedupe is a host concern).
     */
    private val renderCallbacks: MutableMap<String, MutableList<InAppRenderCallback>> = HashMap()

    /**
     * Assignment ids that already fired [PyrxEvent.InAppMessageReceived]
     * — rule 6, dedupe per identity. Cleared on user-switch via
     * [notifyIdentityChanged].
     */
    private val firedReceivedIds: MutableSet<String> = HashSet()

    /**
     * Last identity-change snapshot. We compare on [notifyIdentityChanged]
     * to detect null → identified (triggers immediate poll) and
     * user-switch (clears [firedReceivedIds]). Wrapped in [AtomicLong]
     * via a hash because the identity snapshot is a plain String?.
     */
    @Volatile
    private var lastObservedContactId: String? = null

    /**
     * Current effective polling interval in millis. Doubles to
     * `defaultPollIntervalMillis * degradedPollMultiplier` on
     * `soft_degraded`; resets on the next clean 200. Read by the
     * polling loop and the track-call refresh window check.
     */
    @Volatile
    private var currentPollIntervalMillis: Long = defaultPollIntervalMillis

    /**
     * Last poll completion timestamp in millis. Used to honor the
     * track-call refresh window (rule 3) — track calls within the
     * window short-circuit without firing a poll.
     */
    private val lastPollAtMillis: AtomicLong = AtomicLong(0L)

    /** Coalesce concurrent polls — rule 4. Holds the in-flight Deferred. */
    private val pollMutex: Mutex = Mutex()

    @Volatile
    private var inFlightPoll: Deferred<Unit>? = null

    /** Serialises cache + dedupe + log-queue mutations. */
    private val cacheMutex: Mutex = Mutex()

    /** Bounded offline log queue — drops oldest at [LOG_QUEUE_CAP]. */
    private val logQueue: ArrayDeque<QueuedLogEvent> = ArrayDeque()

    /** Background polling timer job. Null when no placements registered. */
    @Volatile
    private var pollTimerJob: Job? = null

    // ── Public surface — invoked by SynapseInAppBridge ─────────────────────

    /**
     * Register [callback] for [placement]. Returns a [ShowToken] that
     * unregisters when closed.
     *
     * Replays already-cached messages for [placement] into [callback]
     * synchronously so a late-registering host doesn't miss prior
     * messages. The replay path does NOT re-fire
     * [PyrxEvent.InAppMessageReceived] (the dedupe key is assignment
     * id, not registration count) — that's a no-op from the observer's
     * perspective.
     *
     * Triggers an immediate poll under the hood (rule 2's sibling —
     * registration also kicks a poll when identity is already known) if
     * [contactIdProvider] returns non-null; otherwise the poll waits
     * for the next [notifyIdentityChanged].
     *
     * Validates [placement] is non-blank; logs + returns a closed
     * no-op token on invalid input rather than throwing — mirrors the
     * browser SDK's defensive-validation pattern.
     */
    public fun show(
        placement: String,
        callback: InAppRenderCallback,
    ): ShowToken {
        if (placement.isBlank()) {
            logger("inApp.show: placement must be a non-blank string")
            return ShowToken { /* no-op */ }
        }

        // Register synchronously — the lambda below does not block.
        scope.launch {
            val cachedForPlacement: List<InAppMessage> =
                cacheMutex.withLock {
                    val list = renderCallbacks.getOrPut(placement) { mutableListOf() }
                    list.add(callback)
                    activeMessages.values.filter { it.placement == placement }
                }

            // Replay outside the mutex so a buggy host doesn't deadlock.
            for (msg in cachedForPlacement) {
                safeInvokeCallback(callback, msg)
            }

            ensurePollTimer()
            // Rule 2's sibling — first registration with an identity
            // triggers an immediate poll so the host doesn't wait up to
            // [currentPollIntervalMillis] for the first message.
            if (contactIdProvider() != null) {
                launch { poll() }
            }
        }

        return ShowToken {
            scope.launch {
                cacheMutex.withLock {
                    val list = renderCallbacks[placement] ?: return@withLock
                    list.removeAll { it === callback }
                    if (list.isEmpty()) renderCallbacks.remove(placement)
                    if (renderCallbacks.isEmpty()) stopPollTimer()
                }
            }
        }
    }

    /**
     * Sync-ish read of currently-active messages. Suspends only on
     * [cacheMutex] acquisition (microsecond-scale). Returns a sorted
     * defensive copy: priority desc, then expiry asc (ISO-8601 string
     * compare — lexicographic ordering matches chronological order for
     * UTC-Z timestamps, which the backend always emits).
     *
     * Does NOT trigger a poll — the cache is populated by background
     * polling and explicit [refresh].
     */
    public suspend fun getActive(placement: String?): List<InAppMessage> =
        cacheMutex.withLock {
            val all = activeMessages.values.toList()
            val filtered = if (placement == null) all else all.filter { it.placement == placement }
            filtered.sortedWith(
                compareByDescending<InAppMessage> { it.priority }
                    .thenBy(nullsLast()) { it.expiresAt },
            )
        }

    /**
     * Mark [messageId] dismissed. Evicts from the cache FIRST so any
     * concurrent [getActive] reflects the dismissal, then publishes
     * [PyrxEvent.InAppMessageDismissed] (observer-only — [reason] does
     * NOT cross the wire per ADR-0008 D2), then fires the `dismissed`
     * telemetry event.
     *
     * Safe to call with an unknown id — the SDK still attempts the
     * telemetry POST (backend swallows it as a no-op if the assignment
     * doesn't exist). Mirrors the browser SDK's call semantics.
     */
    public suspend fun dismiss(
        messageId: String,
        reason: String?,
    ) {
        if (messageId.isBlank()) {
            logger("inApp.dismiss: messageId must be a non-blank string")
            return
        }

        cacheMutex.withLock {
            activeMessages.remove(messageId)
            firedReceivedIds.remove(messageId)
        }

        // Fire observer event synchronously so host apps that react to
        // dismissal (analytics, debug overlay) see the state change
        // BEFORE the telemetry round-trip completes.
        eventPublisher(PyrxEvent.InAppMessageDismissed(messageId = messageId, reason = reason))

        sendLog(QueuedLogEvent(assignmentId = messageId, event = LogEvent.DISMISSED, ctaId = null))
    }

    /**
     * Mark [messageId] interacted with [ctaId]. Validates [ctaId] is
     * non-blank client-side to avoid the round-trip (backend's
     * `model_validator` enforces this; pre-validating saves an RTT and
     * matches the browser SDK).
     *
     * Does NOT evict from cache — the host app decides whether
     * interaction implies dismissal (a `DISMISS`-type CTA would call
     * [dismiss] separately). Does NOT fire an observer event per
     * ADR-0009 D5 (the host already knows when its own CTA was tapped
     * — it triggered this call).
     */
    public suspend fun markInteracted(
        messageId: String,
        ctaId: String,
    ) {
        if (messageId.isBlank()) {
            logger("inApp.markInteracted: messageId must be a non-blank string")
            return
        }
        if (ctaId.isBlank()) {
            logger("inApp.markInteracted: ctaId is required when calling markInteracted")
            return
        }
        sendLog(QueuedLogEvent(assignmentId = messageId, event = LogEvent.INTERACTED, ctaId = ctaId))
    }

    /**
     * Explicit poll trigger. Coalesces with any in-flight poll via
     * [pollMutex] (rule 4) — three concurrent callers all await the
     * same [Deferred] rather than firing three requests.
     */
    public suspend fun refresh() {
        poll()
    }

    // ── Cross-module hooks called by SynapseInAppBridge ────────────────────

    /**
     * Signal that the SDK's identity changed. The bridge calls this
     * from `notifyIdentityChanged()`; we read [contactIdProvider]'s
     * fresh value to detect:
     *   - null → set: kick an immediate poll if placements registered
     *     (rule 2).
     *   - set → different set: clear [firedReceivedIds] so the new
     *     contact's messages get fresh receive observers.
     *   - set → null (logout): pause polling — the next poll attempt
     *     short-circuits in [poll] on the null contact id.
     */
    public fun notifyIdentityChanged() {
        val previous = lastObservedContactId
        val current = contactIdProvider()
        lastObservedContactId = current

        scope.launch {
            if (previous != null && previous != current) {
                cacheMutex.withLock { firedReceivedIds.clear() }
            }
            if (current != null && previous == null) {
                // Rule 2 — null → identified with placements registered
                // fires an immediate poll. With no placements the next
                // [show] will kick it.
                val hasPlacements = cacheMutex.withLock { renderCallbacks.isNotEmpty() }
                if (hasPlacements) poll()
            }
        }
    }

    /**
     * Fire-and-forget — the track-call refresh hint (rule 3). Called
     * from `Pyrx.track` / `Pyrx.screen` (wired by the bridge) after
     * each enqueue. Short-circuits silently when:
     *   - no placements registered, OR
     *   - no identity yet, OR
     *   - the last poll completed within [currentPollIntervalMillis]
     *     (the backend's `max-age=60s` cache + this short-circuit
     *     keep actual fetch frequency bounded).
     *
     * Otherwise launches a poll into [scope] without awaiting it.
     */
    public fun notifyTracked() {
        scope.launch {
            val hasPlacements = cacheMutex.withLock { renderCallbacks.isNotEmpty() }
            if (!hasPlacements) return@launch
            if (contactIdProvider() == null) return@launch
            val sinceLastPoll = nowMillis() - lastPollAtMillis.get()
            if (sinceLastPoll < currentPollIntervalMillis) return@launch
            poll()
        }
    }

    /** Cancel background polling + the manager's coroutine scope. */
    public fun shutdown() {
        stopPollTimer()
        scope.cancel()
    }

    // ── Internal: polling ──────────────────────────────────────────────────

    /**
     * Single poll cycle. Coalesces concurrent callers via [pollMutex]
     * + [inFlightPoll] — three simultaneous [refresh] calls all await
     * the same [Deferred].
     */
    private suspend fun poll() {
        // Fast-path identity / placement checks before taking the mutex.
        if (contactIdProvider() == null) {
            logger("inApp.poll: skipped — no identified user yet")
            return
        }
        val hasPlacements = cacheMutex.withLock { renderCallbacks.isNotEmpty() }
        if (!hasPlacements) return

        val existing = inFlightPoll
        if (existing != null) {
            existing.await()
            return
        }

        val deferred =
            pollMutex.withLock {
                // Re-check under the mutex to avoid the double-fetch race.
                val existing2 = inFlightPoll
                if (existing2 != null) return@withLock existing2
                val launched = scope.async { executePoll() }
                inFlightPoll = launched
                launched
            }

        try {
            deferred.await()
        } finally {
            // Clear the in-flight reference inside the mutex so the next
            // caller sees a clean slot.
            pollMutex.withLock {
                if (inFlightPoll === deferred) inFlightPoll = null
            }
        }
    }

    // detekt:ReturnCount — 5 guard-style early returns (no contact, no
    // placements, non-2xx, IOException, malformed JSON). Refactoring to
    // single-exit would obscure the failure modes; the early-return
    // pattern is idiomatic Kotlin and matches the existing push poll
    // path in synapse-push.
    @Suppress("ReturnCount")
    private suspend fun executePoll() {
        val contactId = contactIdProvider() ?: return
        val placements = cacheMutex.withLock { renderCallbacks.keys.toList() }
        if (placements.isEmpty()) return

        val url = buildPollUrl(contactId = contactId, placements = placements)
        val request = buildGetRequest(url)

        val responseBytes: ByteArray =
            try {
                val resp = session.execute(request)
                lastPollAtMillis.set(nowMillis())
                if (resp.statusCode !in HTTP_2XX) {
                    logger("inApp.poll: non-2xx ${resp.statusCode}")
                    return
                }
                resp.body
            } catch (e: IOException) {
                lastPollAtMillis.set(nowMillis())
                logger("inApp.poll: network error — ${e.message}")
                return
            }

        val body: PollResponseBody =
            try {
                json.decodeFromString(PollResponseBody.serializer(), responseBytes.toString(Charsets.UTF_8))
            } catch (e: Throwable) {
                logger("inApp.poll: malformed JSON — ${e.message}")
                return
            }

        applyPollResult(body.messages)

        if (logQueueSize() > 0) {
            flushLogQueue()
        }
    }

    /**
     * Reconcile [messages] (the latest poll response) with the cache.
     * Server-authoritative (rule 5) — anything not in [messages] is
     * evicted from the cache.
     *
     * For each fresh assignment id we:
     *   1. Add to cache
     *   2. Fire [PyrxEvent.InAppMessageReceived] (rule 6 dedupes)
     *   3. Dispatch to per-placement render callbacks
     *   4. Auto-`markImpression` (rule 7 — billable per ADR-0008 D4)
     */
    private suspend fun applyPollResult(messages: List<InAppMessage>) {
        val incomingIds = messages.map { it.id }.toHashSet()

        // Step 1 — reconcile inside the mutex to keep the cache view
        // consistent for concurrent getActive readers.
        val freshMessages: List<InAppMessage> =
            cacheMutex.withLock {
                val fresh = mutableListOf<InAppMessage>()
                for (msg in messages) {
                    val isNew = !activeMessages.containsKey(msg.id)
                    activeMessages[msg.id] = msg
                    if (isNew && !firedReceivedIds.contains(msg.id)) {
                        firedReceivedIds.add(msg.id)
                        fresh.add(msg)
                    }
                }
                // Server-authoritative eviction.
                val toEvict = activeMessages.keys.filter { it !in incomingIds }
                for (id in toEvict) {
                    activeMessages.remove(id)
                    firedReceivedIds.remove(id)
                }
                fresh
            }

        // Step 2-4 OUTSIDE the mutex so host-app callbacks can't
        // deadlock the cache. Order matters: observer first so
        // analytics middleware sees every message globally, then the
        // per-placement dispatch.
        for (msg in freshMessages) {
            eventPublisher(PyrxEvent.InAppMessageReceived(msg))
            dispatchToPlacementCallbacks(msg)
        }
    }

    private suspend fun dispatchToPlacementCallbacks(msg: InAppMessage) {
        val callbacks: List<InAppRenderCallback> =
            cacheMutex.withLock {
                renderCallbacks[msg.placement]?.toList() ?: emptyList()
            }
        if (callbacks.isEmpty()) return
        for (cb in callbacks) {
            safeInvokeCallback(cb, msg)
        }
        // Rule 7 — auto-impression AFTER the render callback returns.
        // Fire-and-forget into the manager scope so the polling loop
        // doesn't block on the impression round-trip.
        scope.launch {
            sendLog(QueuedLogEvent(assignmentId = msg.id, event = LogEvent.IMPRESSED, ctaId = null))
        }
    }

    private fun safeInvokeCallback(
        cb: InAppRenderCallback,
        msg: InAppMessage,
    ) {
        try {
            cb(msg)
        } catch (t: Throwable) {
            logger("inApp render callback threw (ignored) — ${t.message}")
        }
    }

    // ── Internal: telemetry ────────────────────────────────────────────────

    /**
     * Send a single log event. Honors `soft_degraded` by doubling the
     * polling interval (rule 8); honors `plan_limit_reached` by
     * surfacing a warning log (rule 9 — message already delivered to
     * the host callback).
     *
     * Failures queue the event for later retry (best-effort).
     */
    private suspend fun sendLog(event: QueuedLogEvent) {
        val request: Request =
            try {
                buildLogRequest(event)
            } catch (t: Throwable) {
                logger("inApp.log: failed to serialise — ${t.message}; queueing")
                queueLog(event)
                return
            }

        val response =
            try {
                session.execute(request)
            } catch (e: IOException) {
                logger("inApp.log: network error — ${e.message}; queueing")
                queueLog(event)
                return
            }

        if (response.statusCode !in HTTP_2XX) {
            logger("inApp.log: non-2xx ${response.statusCode} for event=${event.event}")
            // 4xx is permanent — drop. 5xx is transient — queue for retry.
            if (response.statusCode >= HTTP_5XX_FLOOR) queueLog(event)
            return
        }

        val parsed: LogResponseBody? =
            try {
                json.decodeFromString(LogResponseBody.serializer(), response.body.toString(Charsets.UTF_8))
            } catch (_: Throwable) {
                // Empty / non-JSON body — log is recorded, but we lose
                // the backoff signal. Reset to default to avoid stuck-
                // degraded state.
                currentPollIntervalMillis = defaultPollIntervalMillis
                restartPollTimerIfRunning()
                null
            }
        parsed?.let { applyLogResponse(it) }
    }

    private fun applyLogResponse(body: LogResponseBody) {
        if (body.planLimitReached) {
            // Rule 9 — host already saw the message (callback fired
            // BEFORE this round-trip). Surface a warning so dashboards
            // / debug menus can show "you're at cap" without
            // suppressing the in-app surface itself.
            logger(
                "inApp.log: plan_limit_reached — tenant at 100% of monthly_in_app_messages_limit",
            )
        }

        // Rule 8 — soft_degraded doubles the interval; clean response
        // resets to default.
        val wanted =
            if (body.softDegraded) {
                defaultPollIntervalMillis * degradedPollMultiplier
            } else {
                defaultPollIntervalMillis
            }
        if (currentPollIntervalMillis != wanted) {
            currentPollIntervalMillis = wanted
            restartPollTimerIfRunning()
            if (body.softDegraded) {
                logger("inApp.log: soft_degraded — polling interval doubled to ${wanted}ms")
            } else {
                logger("inApp.log: degrade cleared — polling interval reset")
            }
        }
    }

    // ── Internal: log-queue management ─────────────────────────────────────

    private suspend fun queueLog(event: QueuedLogEvent) {
        cacheMutex.withLock {
            if (logQueue.size >= LOG_QUEUE_CAP) logQueue.removeFirst()
            logQueue.addLast(event)
        }
    }

    private suspend fun logQueueSize(): Int = cacheMutex.withLock { logQueue.size }

    private suspend fun flushLogQueue() {
        val drained: List<QueuedLogEvent> =
            cacheMutex.withLock {
                val copy = logQueue.toList()
                logQueue.clear()
                copy
            }
        for (event in drained) {
            // Re-enter sendLog so the soft_degraded signal is honored
            // on flushed events too. If one fails, the rest re-queue.
            sendLog(event)
        }
    }

    // ── Internal: timer management ─────────────────────────────────────────

    private fun ensurePollTimer() {
        if (!enableBackgroundTimer) return
        if (pollTimerJob != null) return
        pollTimerJob =
            scope.launch {
                // Background poll loop. Sleeps for the current interval
                // (which may double on soft_degraded), then fires a
                // poll. Coalesces with explicit refresh() via pollMutex.
                while (true) {
                    delay(currentPollIntervalMillis)
                    poll()
                }
            }
    }

    private fun stopPollTimer() {
        pollTimerJob?.cancel()
        pollTimerJob = null
    }

    private fun restartPollTimerIfRunning() {
        if (pollTimerJob == null) return
        stopPollTimer()
        ensurePollTimer()
    }

    // ── Internal: request construction ─────────────────────────────────────

    /**
     * Build the `/v1/in-app/poll?contact_id=...&placement=...` URL.
     * Placements are sent as REPEATED query params (NOT comma-joined)
     * — wire contract per PR-1 backend.
     */
    private fun buildPollUrl(
        contactId: String,
        placements: List<String>,
    ): String {
        val base = "${config.baseUrl.trimEnd('/')}$POLL_PATH"
        val encodedContact = URLEncoder.encode(contactId, Charsets.UTF_8.name())
        val placementParams =
            placements.joinToString(separator = "&") { p ->
                "placement=${URLEncoder.encode(p, Charsets.UTF_8.name())}"
            }
        return if (placementParams.isEmpty()) {
            "$base?contact_id=$encodedContact"
        } else {
            "$base?contact_id=$encodedContact&$placementParams"
        }
    }

    private fun buildGetRequest(url: String): Request =
        Request.Builder()
            .url(url)
            .get()
            .header(HTTPClient.HeaderName.WORKSPACE_ID, config.workspaceId.toString())
            .header(HTTPClient.HeaderName.API_KEY, config.apiKey)
            .header(HTTPClient.HeaderName.SDK_VERSION, PyrxConstants.SDK_VERSION)
            .header(HTTPClient.HeaderName.SDK_PLATFORM, PyrxConstants.PLATFORM)
            .header("Accept", HTTPClient.JSON_CONTENT_TYPE)
            .build()

    private fun buildLogRequest(event: QueuedLogEvent): Request {
        val body =
            LogRequestBody(
                assignmentId = event.assignmentId,
                event = event.event.wire,
                ctaId = event.ctaId,
            )
        val bytes = json.encodeToString(LogRequestBody.serializer(), body).toByteArray(Charsets.UTF_8)
        val requestBody = bytes.toRequestBody(JSON_MEDIA_TYPE)
        val url = "${config.baseUrl.trimEnd('/')}$LOG_PATH"
        return Request.Builder()
            .url(url)
            .post(requestBody)
            .header(HTTPClient.HeaderName.WORKSPACE_ID, config.workspaceId.toString())
            .header(HTTPClient.HeaderName.API_KEY, config.apiKey)
            .header(HTTPClient.HeaderName.SDK_VERSION, PyrxConstants.SDK_VERSION)
            .header(HTTPClient.HeaderName.SDK_PLATFORM, PyrxConstants.PLATFORM)
            .header(HTTPClient.HeaderName.CONTENT_TYPE, HTTPClient.JSON_CONTENT_TYPE)
            .build()
    }

    // ── Test seams ─────────────────────────────────────────────────────────

    /** Test-only — current effective polling interval in ms. */
    internal fun getPollIntervalForTests(): Long = currentPollIntervalMillis

    /** Test-only — snapshot of queued log events (offline-queue depth). */
    internal suspend fun getQueuedLogsForTests(): List<QueuedLogEvent> = cacheMutex.withLock { logQueue.toList() }

    /** Test-only — number of cached active messages. */
    internal suspend fun getActiveCountForTests(): Int = cacheMutex.withLock { activeMessages.size }

    /** Test-only — number of registered placement callbacks. */
    internal suspend fun getRegisteredPlacementCountForTests(): Int = cacheMutex.withLock { renderCallbacks.size }

    public companion object {
        /** Default polling cadence (60s) — rule 3's window basis. */
        public const val DEFAULT_POLL_INTERVAL_MILLIS: Long = 60_000L

        /** Soft-degrade multiplier — 60s × 2 = 120s. */
        public const val DEGRADED_POLL_MULTIPLIER: Int = 2

        /** Backend route — `/v1/in-app/poll`. Wire contract. */
        public const val POLL_PATH: String = "/v1/in-app/poll"

        /** Backend route — `/v1/in-app/log`. Wire contract. */
        public const val LOG_PATH: String = "/v1/in-app/log"

        /** Offline log queue cap — drops oldest beyond this depth. */
        public const val LOG_QUEUE_CAP: Int = 200

        private val HTTP_2XX: IntRange = 200..299
        private const val HTTP_5XX_FLOOR: Int = 500
        private val JSON_MEDIA_TYPE = HTTPClient.JSON_CONTENT_TYPE.toMediaType()
    }

    // ── Wire types (private — backend round-trip shape) ────────────────────

    /** One queued telemetry event. */
    internal data class QueuedLogEvent(
        val assignmentId: String,
        val event: LogEvent,
        val ctaId: String?,
    )

    /** Wire enum for `event` field on `/v1/in-app/log`. */
    internal enum class LogEvent(val wire: String) {
        IMPRESSED("impressed"),
        DISMISSED("dismissed"),
        INTERACTED("interacted"),
    }

    /** Wire shape for `POST /v1/in-app/log` request body. */
    @Serializable
    internal data class LogRequestBody(
        @SerialName("assignment_id") val assignmentId: String,
        val event: String,
        @SerialName("cta_id") val ctaId: String? = null,
    )

    /** Wire shape for `POST /v1/in-app/log` response. */
    @Serializable
    internal data class LogResponseBody(
        @SerialName("log_id") val logId: String,
        val billable: Boolean,
        @SerialName("plan_limit_reached") val planLimitReached: Boolean,
        @SerialName("soft_degraded") val softDegraded: Boolean,
    )

    /** Wire shape for `GET /v1/in-app/poll` response. */
    @Serializable
    internal data class PollResponseBody(
        val messages: List<InAppMessage> = emptyList(),
    )
}
