/*
 * EventsManager.kt
 * PYRXSynapse — Android
 *
 * Public events surface. Owned by the [tech.pyrx.synapse.Pyrx] singleton;
 * never instantiated by callers. Two methods:
 *
 *   - track(eventName, properties)
 *   - screen(screenName, properties)
 *
 * Both shapes ultimately produce a [tech.pyrx.synapse.queue.QueuedEvent]
 * and append to the on-disk [tech.pyrx.synapse.queue.EventQueue]. The queue
 * handles the wire-level POST + retry + bounded persistence.
 *
 * external_id resolution
 * ======================
 *
 *   1. If `identify()` has been called and externalId is in storage → use it.
 *   2. Otherwise → use the device's anonymousId (always present after
 *      [tech.pyrx.synapse.Pyrx.initialize]).
 *   3. If neither is present (a developer bug — track called before init
 *      completed) → throw [tech.pyrx.synapse.PyrxError.NotInitialized].
 *
 * screen() encoding
 * =================
 *
 *   Screen views map onto the same `/v1/events` endpoint with the canonical
 *   event name `"$screen"`. The screen name lands in `attributes["screen_name"]`.
 *   This matches the cross-platform shape the browser SDK uses for `$pageview`
 *   (`event_name="$pageview"`, `attributes.url/path/title`) — we keep the
 *   `$`-prefix for analytics consumers to distinguish SDK-emitted system
 *   events from user-defined events.
 *
 * Mirrors iOS `EventsManager.swift` semantics verbatim.
 */

package tech.pyrx.synapse.events

import tech.pyrx.synapse.PyrxError
import tech.pyrx.synapse.PyrxLogger
import tech.pyrx.synapse.network.JSONValue
import tech.pyrx.synapse.queue.EventQueue
import tech.pyrx.synapse.queue.QueuedEvent
import tech.pyrx.synapse.storage.PyrxStorage
import tech.pyrx.synapse.storage.PyrxStorageKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Events surface owned by [tech.pyrx.synapse.Pyrx]. Forwards `track` /
 * `screen` calls into the [EventQueue] after resolving the active
 * external_id and stamping the wall-clock timestamp.
 *
 * @param queue The disk-backed offline queue (PR 3).
 * @param storage The encrypted K/V store (PR 1); read for the current
 *                EXTERNAL_ID on every call.
 * @param anonymousId Snapshot of the SDK-level anonymousId captured at SDK
 *                    initialize time. Kept in-memory so the events path
 *                    does not need to hit Keystore on every `track` — only
 *                    the (rarer) externalId lookup goes through [storage].
 * @param logger Internal logger.
 */
internal class EventsManager(
    private val queue: EventQueue,
    private val storage: PyrxStorage,
    private val anonymousId: String,
    private val logger: PyrxLogger,
) {
    /**
     * ISO-8601 formatter for `occurred_at`. Built once and reused —
     * SimpleDateFormat construction is expensive. Pinned to UTC so the wire
     * value matches what `Instant.toString()` would produce on iOS / browser.
     *
     * Thread-safety: [SimpleDateFormat] is NOT thread-safe. We wrap every
     * format() call in a synchronized block. An alternative would be
     * `ThreadLocal<SimpleDateFormat>` but the call rate here is bounded by
     * the host app's event frequency (typically a few per second), so the
     * lock overhead is negligible compared to the JSON encode + Room insert
     * already on the path.
     */
    private val isoFormatter: SimpleDateFormat =
        SimpleDateFormat(ISO_8601_PATTERN, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    // MARK: - track

    /**
     * Track a custom event. Persists to the disk-backed queue and triggers
     * a non-blocking drain. Returns once the event is durably in Room;
     * network success/failure is handled asynchronously by the queue's
     * drain loop.
     *
     * @param eventName Caller-supplied event name. Trimmed; must not be
     *                  blank after trimming.
     * @param properties Optional caller-supplied attributes. Maps onto the
     *                   wire `attributes` field.
     * @throws PyrxError.InvalidConfig on blank eventName.
     * @throws PyrxError.NotInitialized if neither externalId nor anonymousId
     *         is resolvable (programmer error — SDK not initialised).
     * @throws PyrxError.StorageFailure on storage read failure.
     */
    @Throws(PyrxError::class)
    suspend fun track(
        eventName: String,
        properties: Map<String, JSONValue>? = null,
    ) {
        val trimmed = eventName.trim()
        if (trimmed.isEmpty()) {
            throw PyrxError.InvalidConfig("eventName must not be empty")
        }

        val event = makeQueuedEvent(name = trimmed, attributes = properties ?: emptyMap())
        queue.enqueue(event)
        logger.debug { "track enqueued — event=$trimmed externalId=${event.externalId}" }
    }

    // MARK: - screen

    /**
     * Track a screen view. Wire shape: `$screen` event with
     * `attributes.screen_name = screenName`. Additional caller [properties]
     * are merged into the same attributes bag — caller values do NOT
     * overwrite the SDK-stamped `screen_name`.
     *
     * @param screenName Human-readable screen identifier. Trimmed; must not
     *                   be blank after trimming.
     * @param properties Optional caller-supplied attributes. Merged with
     *                   `screen_name` (SDK value wins on conflict).
     * @throws PyrxError.InvalidConfig on blank screenName.
     * @throws PyrxError.NotInitialized if neither externalId nor anonymousId
     *         is resolvable.
     * @throws PyrxError.StorageFailure on storage read failure.
     */
    @Throws(PyrxError::class)
    suspend fun screen(
        screenName: String,
        properties: Map<String, JSONValue>? = null,
    ) {
        val trimmed = screenName.trim()
        if (trimmed.isEmpty()) {
            throw PyrxError.InvalidConfig("screenName must not be empty")
        }

        // SDK-stamped fields are last-write-wins so a caller cannot spoof
        // the canonical screen identifier through `properties`.
        val attributes: Map<String, JSONValue> =
            (properties ?: emptyMap()) + ("screen_name" to JSONValue.Str(trimmed))

        val event = makeQueuedEvent(name = SCREEN_EVENT_NAME, attributes = attributes)
        queue.enqueue(event)
        logger.debug { "screen enqueued — name=$trimmed externalId=${event.externalId}" }
    }

    // MARK: - Internals

    /**
     * Resolve the active external_id and stamp the wall-clock timestamp.
     */
    private fun makeQueuedEvent(
        name: String,
        attributes: Map<String, JSONValue>,
    ): QueuedEvent {
        val externalId = resolveExternalId()
        return QueuedEvent(
            externalId = externalId,
            eventName = name,
            attributes = attributes,
            occurredAt = nowIso(),
        )
    }

    /**
     * externalId from storage if set, else the cached anonymousId. Throws
     * [PyrxError.NotInitialized] only if both are missing — which is a
     * programmer error (SDK must have been initialised already).
     */
    private fun resolveExternalId(): String {
        val external = storage.get(PyrxStorageKey.EXTERNAL_ID)
        if (!external.isNullOrEmpty()) return external
        if (anonymousId.isEmpty()) throw PyrxError.NotInitialized
        return anonymousId
    }

    /** Current UTC time as an ISO-8601 string with millisecond precision. */
    private fun nowIso(): String = synchronized(isoFormatter) { isoFormatter.format(Date()) }

    companion object {
        /**
         * Canonical event name for screen views. Matches iOS + browser SDK.
         * Analytics consumers split on the `$`-prefix to distinguish
         * SDK-emitted system events from user-defined events.
         */
        const val SCREEN_EVENT_NAME: String = "\$screen"

        /**
         * ISO-8601 pattern with millisecond precision + `Z` suffix. Matches
         * `Instant.toString()` output on iOS / browser.
         */
        private const val ISO_8601_PATTERN: String = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
    }
}
