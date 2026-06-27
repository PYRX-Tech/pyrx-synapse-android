/*
 * ColdStartClickDedup.kt
 * PYRXSynapse — Android
 *
 * Phase 9.2.1 — enforces the non-negotiable invariant from Risk Register
 * item #1:
 *
 *   If the SAME push (same `pushLogId`) caused a cold-start launch AND
 *   subsequently arrived through `handleNotificationTap` within a short
 *   window, ONLY `PushReceivedColdStart` fires — the matching
 *   `PushClicked` for that pushLogId is suppressed.
 *
 * Why the dedup matters
 * =====================
 * Android's cold-start tap fires `MainActivity.onCreate(intent)` AND the
 * SDK's `handleNotificationTap(intent)` for the same payload. The
 * existing `Pyrx.recordColdStartAttribution` already emits the analytics
 * event for cold-starts; the new `PushReceivedColdStart` observer event
 * is the observer-side equivalent. Without dedup, a host app that
 * collects `Pyrx.events` and routes on `PushClicked.deepLink` would
 * route TWICE on every cold-start: once on `PushReceivedColdStart` and
 * once on `PushClicked` from the same payload. This breaks navigation
 * UX (e.g., back-stack pollution) and is impossible to work around
 * cleanly from the consumer side.
 *
 * Mechanism
 * =========
 * A small in-memory map of `pushLogId -> seenAtMillis`. When
 * `recordColdStartAttribution` emits `PushReceivedColdStart`, the same
 * `pushLogId` is recorded with the current wall-clock millis. When
 * `PushHandlers.handleNotificationTap` is about to emit a `PushClicked`,
 * it consults the dedup: if the same `pushLogId` was recorded within
 * the window (default 5 seconds), the `PushClicked` is suppressed.
 *
 * 5 seconds is sufficient for the Android cold-start sequence: the OS
 * delivers `onCreate(intent)` first, then queues `handleNotificationTap`
 * — both happen within tens of milliseconds in practice. 5s leaves
 * generous slack for slow devices, hot-restart races, and FCM redelivery
 * quirks without leaking the suppression past the "this is the same
 * launch" semantic.
 *
 * Map bound
 * ---------
 * Entries are pruned lazily on each call; the map never holds more than
 * a handful of entries in practice (one per recent push tap). No
 * scheduled cleanup task — that would be overkill.
 *
 * Threading
 * ---------
 * Concurrent access from `Pyrx.recordColdStartAttribution` (caller's
 * coroutine, typically `lifecycleScope` from the launcher Activity) and
 * `PushHandlers.handleNotificationTap` (caller's coroutine, typically
 * `runBlocking` from `PyrxMessagingService.onMessageReceived` or
 * `lifecycleScope` from the launcher Activity). All access goes through
 * a synchronized block — the work is microsecond-scale (HashMap put /
 * remove / get) so a Mutex would be overkill.
 *
 * Test seam
 * ---------
 * The `nowMillis` lambda lets tests inject a deterministic clock.
 * Production uses `System::currentTimeMillis`. No global mutable state —
 * the dedup is held inside `Pyrx` as an instance-of-a-singleton.
 */

package tech.pyrx.synapse.observer

/**
 * 5-second-window LRU dedup for `pushLogId`s that already fired
 * [PyrxEvent.PushReceivedColdStart]. Used to suppress the matching
 * [PyrxEvent.PushClicked] emission for the same payload — see file-level
 * KDoc for the invariant.
 *
 * Internal — instantiated once inside `Pyrx`; never reachable from host
 * apps.
 *
 * @param windowMillis Suppression window in milliseconds. Defaults to 5
 *   seconds, sized for Android's cold-start tap sequence
 *   (onCreate → handleNotificationTap typically <100ms apart;
 *   5s leaves generous slack without leaking past the launch).
 * @param nowMillis Test seam — defaults to [System.currentTimeMillis]. A
 *   deterministic lambda lets unit tests inject controlled wall-
 *   clock progression without freezing the system clock.
 */
internal class ColdStartClickDedup(
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val seen: MutableMap<String, Long> = HashMap()
    private val lock: Any = Any()

    /**
     * Record that [pushLogId] just fired a cold-start observer event. Any
     * subsequent [shouldSuppressClick] call within [windowMillis] returns
     * true for the same id; calls past the window return false.
     */
    fun recordColdStart(pushLogId: String) {
        synchronized(lock) {
            seen[pushLogId] = nowMillis()
            pruneStale()
        }
    }

    /**
     * @return true if [pushLogId] was recorded as a cold-start within
     * [windowMillis] — in which case `PushHandlers` should suppress the
     * paired `PushClicked` emission. Consumes the entry (sets it to
     * a sentinel) so a duplicate redelivery within the window doesn't
     * also get suppressed; the consumer-visible click should fire on the
     * second tap.
     */
    fun shouldSuppressClick(pushLogId: String): Boolean {
        synchronized(lock) {
            pruneStale()
            val seenAt = seen[pushLogId] ?: return false
            val age = nowMillis() - seenAt
            if (age > windowMillis) {
                seen.remove(pushLogId)
                return false
            }
            // Consume — the cold-start suppression applies once. A second
            // tap of the same notification within the window legitimately
            // fires PushClicked (the user actually re-tapped).
            seen.remove(pushLogId)
            return true
        }
    }

    /**
     * Drop entries older than [windowMillis] so the map stays bounded
     * even if no `shouldSuppressClick` call ever reads them. Cheap —
     * called from both record paths.
     */
    private fun pruneStale() {
        val now = nowMillis()
        val cutoff = now - windowMillis
        val iter = seen.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.value < cutoff) iter.remove()
        }
    }

    /** Test-only — drop everything. Not exposed in production. */
    internal fun clear() {
        synchronized(lock) { seen.clear() }
    }

    /** Test-only diagnostic — current entry count. */
    internal fun size(): Int = synchronized(lock) { seen.size }

    companion object {
        /**
         * Default suppression window — 5 seconds. Sized for Android's
         * cold-start sequence; see file-level KDoc for the rationale.
         */
        const val DEFAULT_WINDOW_MILLIS: Long = 5_000L
    }
}
