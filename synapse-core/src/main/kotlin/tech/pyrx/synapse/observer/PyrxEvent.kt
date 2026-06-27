/*
 * PyrxEvent.kt
 * PYRXSynapse — Android
 *
 * Phase 9.2.1 (D3 + D4) — the public observer event sum exposed on
 * `Pyrx.events: SharedFlow<PyrxEvent>`. Sealed interface with five data
 * class subtypes, each covering one fire-point inside the SDK.
 *
 * Five events in v1 (frozen for this release per D4):
 *
 *   1. [PushReceived]            — foreground push delivered
 *   2. [PushClicked]             — push body tap or action button
 *   3. [PushReceivedColdStart]   — app launched from a tapped push
 *   4. [QueueDrained]            — internal event queue successfully flushed
 *   5. [IdentityChanged]         — identify / alias / logout succeeded
 *
 * Exhaustive `when`
 * =================
 * Sealed interface means `when` is exhaustive in Kotlin — every consumer
 * gets a compile error if they `when (event)` without covering all five
 * cases. We document the forward-compat policy in `docs/observers.md`:
 * adding new cases in future minors WILL require updating consumer
 * `when` blocks in source-compatible ways (the addition is the signal
 * that there is a new case worth handling). Consumers that want to be
 * tolerant can include `else -> { }` — Kotlin allows this on sealed
 * `when` even when exhaustive.
 *
 * Multi-subscriber
 * ================
 * Every collector of `Pyrx.events` gets every event. There is no
 * arbitration between subscribers — the RN bridge collects in parallel
 * with a host-app-installed observer; both see every event.
 *
 * Late-subscriber replay
 * ----------------------
 * `Pyrx.events` is backed by a `MutableSharedFlow(replay = 4)` — late
 * subscribers receive the most-recent 4 events. 4 is the smallest value
 * that covers the realistic cold-start race window (one cold-start push +
 * one identify on launch + one queue drain + one headroom slot). Larger
 * buffers invite stale-event surprise; smaller would risk missing the
 * cold-start event when the RN JS bridge mounts late.
 *
 * Mirrors iOS `PyrxEvent` shape; the two SDKs publish the same five cases
 * with the same field-for-field payloads.
 */

package tech.pyrx.synapse.observer

/**
 * Hot stream of SDK events. Collect via [tech.pyrx.synapse.Pyrx.events]
 * (a `SharedFlow<PyrxEvent>`) inside your own coroutine scope —
 * typically `lifecycleScope` in an Activity or `viewModelScope` in a
 * ViewModel — so cancellation is automatic when the host context dies.
 *
 * Multi-subscriber by design; replay buffer of 4.
 */
public sealed interface PyrxEvent {
    /**
     * A push notification was delivered to the app while in the
     * foreground (FCM `onMessageReceived` path). The carrying app may
     * choose to display its own UI for these (e.g., an in-app toast)
     * since the system tray notification only renders for background /
     * killed-app deliveries by default.
     */
    public data class PushReceived(val event: PushReceivedEvent) : PyrxEvent

    /**
     * The user tapped a push notification — either the body or an action
     * button (distinguished by [PushClickedEvent.actionId]). Fires for
     * BOTH foreground and warm-start (process-already-alive) taps.
     *
     * Does NOT fire for cold-start taps of the same payload — those
     * publish [PushReceivedColdStart] instead, and [PushClicked] is
     * debounced by a 5-second LRU on `pushLogId` to enforce the
     * mutual-exclusivity invariant per Risk Register item #1.
     */
    public data class PushClicked(val event: PushClickedEvent) : PyrxEvent

    /**
     * The app was launched FROM a tapped push (the OS started the
     * process to deliver the tap). Fires AFTER
     * [tech.pyrx.synapse.Pyrx.initialize] completes and the buffered
     * launch intent is replayed. Distinct from [PushClicked] so consumer
     * routing logic can branch on "did we come from a push tap" vs "the
     * app was already alive and got a push".
     *
     * Mutually exclusive with [PushClicked] for the same `pushLogId` —
     * see [PushClicked] doc.
     */
    public data class PushReceivedColdStart(val event: PushReceivedEvent) : PyrxEvent

    /**
     * The internal event queue successfully flushed [count] events to
     * `POST /v1/events`. Debug-oriented — most consumers won't subscribe;
     * the sample app uses it to show a "synced" indicator. Cheap to
     * publish — the count is already known internally; we just announce
     * it. Does NOT fire on no-op drain passes (count = 0).
     */
    public data class QueueDrained(val count: Int) : PyrxEvent

    /**
     * The SDK's resolved identity changed via [tech.pyrx.synapse.Pyrx
     * .identify], [tech.pyrx.synapse.Pyrx.alias], or [tech.pyrx.synapse
     * .Pyrx.logout]. Use [before] vs [after] delta to detect:
     *
     *   - **Login transition**: `before?.externalId == null && after
     *     .externalId != null` (anonymous → identified).
     *   - **User switch**: `before?.externalId != null && after.externalId
     *     != null && before.externalId != after.externalId` (rare — usual
     *     pattern is logout-then-identify, not direct switch).
     *   - **Logout transition**: `before?.externalId != null && after
     *     .externalId == null` (identified → anonymous).
     *
     * [before] is `null` ONLY on the very first identify after a fresh
     * install (there is no prior identity state recorded). Otherwise
     * both snapshots are non-null.
     *
     * Dashboard-style RN/native apps refetch user data on this event
     * instead of polling `useIdentify` in a `useEffect`.
     */
    public data class IdentityChanged(
        val before: IdentitySnapshot?,
        val after: IdentitySnapshot,
    ) : PyrxEvent
}
