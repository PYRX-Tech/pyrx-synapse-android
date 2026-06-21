/*
 * QueuedEventEntity.kt
 * PYRXSynapse — Android
 *
 * Room entity for the offline event queue. One row per queued event. Lives
 * in the `queued_events` table inside the `pyrx_event_queue.db` database.
 *
 * Why a dedicated entity instead of annotating [QueuedEvent] directly:
 *
 *   - [QueuedEvent] is a `@Serializable` domain type the events layer + tests
 *     pass around; mixing Room annotations onto it would couple the domain
 *     shape to Room's reflection requirements (no-arg constructor on the
 *     compiler side, public setters for vars, etc.).
 *
 *   - The `attributes` map on [QueuedEvent] is open-ended `Map<String, JSONValue>`
 *     — there's no clean Room column type for that, so we store it as a
 *     single JSON-encoded text column and round-trip via kotlinx.serialization.
 *
 *   - Keeping the entity in this file (vs in the database file) lets the DAO
 *     and the entity sit next to each other while the public domain type
 *     (`QueuedEvent`) stays free of Room machinery.
 *
 * Schema (matches the iOS JSONL row exactly, projected to columns):
 *
 *   id              TEXT PRIMARY KEY  -- UUID v4 string, equal to QueuedEvent.id
 *   external_id     TEXT NOT NULL
 *   event_name      TEXT NOT NULL
 *   attributes_json TEXT NOT NULL    -- JSON-encoded Map<String, JSONValue>
 *   occurred_at     TEXT NOT NULL    -- ISO-8601 string
 *   idempotency_key TEXT NOT NULL    -- UUID v4 string
 *   attempt_count   INTEGER NOT NULL DEFAULT 0
 *   created_at      INTEGER NOT NULL -- epoch millis; FIFO ordering
 *
 * `created_at` is the FIFO ordering key. Room generates monotonic-enough
 * insertion order via the autoincrement of insert time, but we capture
 * `System.currentTimeMillis()` explicitly so the order is portable across
 * test backends (in-memory DBs may not preserve insertion order without it).
 */

package tech.pyrx.synapse.queue

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.json.Json
import tech.pyrx.synapse.network.JSONValue

@Entity(tableName = "queued_events")
internal data class QueuedEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "external_id")
    val externalId: String,
    @ColumnInfo(name = "event_name")
    val eventName: String,
    @ColumnInfo(name = "attributes_json")
    val attributesJson: String,
    @ColumnInfo(name = "occurred_at")
    val occurredAt: String,
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
) {
    companion object {
        /**
         * JSON codec used to encode / decode the open-ended `attributes` map
         * into the `attributes_json` text column. Configured to round-trip
         * defaults so `JSONValue.Null` survives the trip without being
         * collapsed (kotlinx.serialization defaults to omitting nulls).
         */
        private val json: Json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }

        /** Convert a domain [QueuedEvent] into an insertable Room row. */
        fun fromDomain(
            event: QueuedEvent,
            createdAt: Long = System.currentTimeMillis(),
        ): QueuedEventEntity {
            val attributesJson =
                json.encodeToString(
                    kotlinx.serialization.serializer<Map<String, JSONValue>>(),
                    event.attributes,
                )
            return QueuedEventEntity(
                id = event.id,
                externalId = event.externalId,
                eventName = event.eventName,
                attributesJson = attributesJson,
                occurredAt = event.occurredAt,
                idempotencyKey = event.idempotencyKey,
                attemptCount = event.attemptCount,
                createdAt = createdAt,
            )
        }
    }

    /** Convert this Room row back into a domain [QueuedEvent]. */
    fun toDomain(): QueuedEvent {
        val attributes: Map<String, JSONValue> =
            if (attributesJson.isEmpty()) {
                emptyMap()
            } else {
                json.decodeFromString(
                    kotlinx.serialization.serializer<Map<String, JSONValue>>(),
                    attributesJson,
                )
            }
        return QueuedEvent(
            id = id,
            externalId = externalId,
            eventName = eventName,
            attributes = attributes,
            occurredAt = occurredAt,
            idempotencyKey = idempotencyKey,
            attemptCount = attemptCount,
        )
    }
}
