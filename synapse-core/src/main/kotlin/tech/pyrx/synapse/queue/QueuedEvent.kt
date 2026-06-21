/*
 * QueuedEvent.kt
 * PYRXSynapse — Android
 *
 * On-disk representation of a single event waiting to flush to
 * `POST /v1/events`. Persisted as a Room row (see [QueuedEventEntity]) keyed
 * by [id]. The wire-side `idempotency_key` lives on this struct so the
 * backend can dedupe across drain attempts (network failure → device retries
 * from disk after backoff → server sees the same key and 200s without
 * double-recording).
 *
 * Why a dedicated struct instead of persisting [EventIngestRequest] directly:
 *
 *   - We need to capture state captured AT ENQUEUE TIME (the external_id the
 *     user had when they called `track`, the wall-clock timestamp from the
 *     device, the SDK-generated idempotency key). If we re-derived these at
 *     drain time we'd corrupt history when the user identifies between the
 *     `track` call and the eventual successful drain.
 *
 *   - The Room entity stores [attributes] as a single JSON string column
 *     (no relational mapping for the open-ended `dict[str, Any]` shape). We
 *     route through this Kotlin struct so callers, tests, and the drain loop
 *     all see one typed shape rather than untyped JSON.
 *
 * Mirrors iOS `QueuedEvent.swift` — same fields, same semantics, same
 * at-enqueue capture timing.
 */

package tech.pyrx.synapse.queue

import kotlinx.serialization.Serializable
import tech.pyrx.synapse.network.ContactOverride
import tech.pyrx.synapse.network.EventIngestRequest
import tech.pyrx.synapse.network.JSONValue
import java.util.UUID

/**
 * A single event waiting to be POSTed to `/v1/events`.
 *
 * [id] is an SDK-side UUID we use for queue-row identity (the Room PK) and
 * log correlation. [idempotencyKey] is what we send on the wire so the
 * backend can dedupe across drain attempts.
 *
 * `@Serializable` so the [attributes] map can be JSON-encoded into the Room
 * row's `attributes_json` column via [QueuedEventEntity.fromDomain] /
 * [QueuedEventEntity.toDomain].
 */
@Serializable
public data class QueuedEvent(
    /**
     * Stable per-event UUID. Generated on enqueue; never mutates. Used as the
     * Room primary key and for log correlation.
     */
    val id: String = UUID.randomUUID().toString(),
    /**
     * The `external_id` resolved at enqueue time — the user's `externalId`
     * if `identify()` had been called, otherwise the device's `anonymousId`.
     * Captured at enqueue (not drain) so events tracked before identify
     * still bear their original attribution.
     */
    val externalId: String,
    /** Event name as supplied by the caller of `track` / `screen`. */
    val eventName: String,
    /**
     * Caller-supplied properties. Maps onto `attributes` in the wire body
     * (see [EventIngestRequest.attributes]). Stored as `Map<String, JSONValue>`
     * because `Any` isn't `Serializable`.
     */
    val attributes: Map<String, JSONValue> = emptyMap(),
    /**
     * ISO-8601 wall-clock timestamp captured at enqueue. Sent as the
     * `occurred_at` field — the server may rewrite it based on `received_at`
     * if it arrives more than `MAX_FUTURE_SKEW` ahead, but the SDK always
     * supplies the original.
     */
    val occurredAt: String,
    /**
     * SDK-side idempotency key. Sent as `idempotency_key` so the backend
     * can dedupe across drain attempts.
     */
    val idempotencyKey: String = UUID.randomUUID().toString(),
    /**
     * Per-event attempt counter. Starts at 0; incremented each time a drain
     * attempt fails with a retryable error. Diagnostic only today.
     */
    val attemptCount: Int = 0,
) {
    /**
     * Project this queued event onto the wire request body. Pure projection
     * — no validation, no mutation. The queue calls this immediately before
     * `httpClient.post(.events, body:)`.
     *
     * `contact` is always null today — PR 3 only exposes `track` / `screen`
     * without a [ContactOverride] surface; PR 4+ (identify-with-trait flows)
     * may revisit.
     */
    public fun toWireRequest(): EventIngestRequest =
        EventIngestRequest(
            externalId = externalId,
            eventName = eventName,
            attributes = attributes,
            idempotencyKey = idempotencyKey,
            contact = null,
            occurredAt = occurredAt,
        )
}
