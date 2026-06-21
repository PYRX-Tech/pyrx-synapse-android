/*
 * Codables.kt
 * PYRXSynapse — Android
 *
 * Kotlin @Serializable data classes that mirror the FastAPI Pydantic schemas
 * in `synapse-api/app/schemas/{device,identify,alias,event,push_telemetry}.py`
 * **byte-for-byte on the wire**. iOS PR #2 owns the source-of-truth shape; any
 * change here MUST be paired with an iOS change.
 *
 * Naming: Kotlin convention is camelCase; the backend uses snake_case. We
 * emit / accept snake_case on the wire via explicit `@SerialName` on each
 * field. Public Kotlin property names stay camelCase so call sites read
 * naturally.
 *
 * All wire types are concrete data classes (not sealed) to keep them
 * trivially constructable and equality-checkable from tests.
 *
 * References:
 *   - app/schemas/device.py         (DeviceRegister, DeviceResponse)
 *   - app/schemas/identify.py       (IdentifyRequest, IdentifyResponse)
 *   - app/schemas/alias.py          (AliasRequest, AliasResponse)
 *   - app/schemas/event.py          (EventIngest, EventAccepted, ContactOverride)
 *   - app/schemas/push_telemetry.py (PushOpenedRequest, PushClickedRequest,
 *                                    PushTelemetryResponse)
 *   - ARCHITECTURE.md §28.4 / §28.7 / §28.9
 *   - iOS Codables.swift (line-by-line counterpart)
 */

package tech.pyrx.synapse.network

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

// MARK: - Shared

/**
 * SDK-level environment selector. Wire shape matches the backend
 * `EnvLiteral = Literal["live", "test"]` exactly.
 *
 * Distinct from [tech.pyrx.synapse.PyrxEnvironment] (which is the runtime
 * SDK target — `PRODUCTION` / `SANDBOX`). [WireEnvironment] is what we send
 * in JSON request bodies that accept an explicit `environment` field
 * (identify, alias, devices). Events derive their environment from the
 * API key prefix (`psk_live_…` / `psk_test_…`) on the server side, so they
 * do not carry an `environment` field.
 */
@Serializable
public enum class WireEnvironment {
    @SerialName("live")
    LIVE,

    @SerialName("test")
    TEST,
    ;

    public companion object {
        /**
         * Translate a runtime [tech.pyrx.synapse.PyrxEnvironment] into its
         * wire-side discriminator. Mirrors iOS `PyrxEnvironment.wireEnvironment`.
         */
        public fun from(env: tech.pyrx.synapse.PyrxEnvironment): WireEnvironment =
            when (env) {
                tech.pyrx.synapse.PyrxEnvironment.PRODUCTION -> LIVE
                tech.pyrx.synapse.PyrxEnvironment.SANDBOX -> TEST
            }
    }
}

/**
 * Discriminator returned by `/v1/identify` and `/v1/alias`. Mirrors the
 * backend `PathLiteral = Literal["known_exists", "first_sighting", "no_anonymous"]`.
 */
@Serializable
public enum class IdentifyPath {
    @SerialName("known_exists")
    KNOWN_EXISTS,

    @SerialName("first_sighting")
    FIRST_SIGHTING,

    @SerialName("no_anonymous")
    NO_ANONYMOUS,
}

/**
 * Status discriminator on push telemetry responses. Mirrors the backend
 * `PushTelemetryStatus = Literal["accepted", "ignored"]`.
 */
@Serializable
public enum class PushTelemetryStatus {
    @SerialName("accepted")
    ACCEPTED,

    @SerialName("ignored")
    IGNORED,
}

// MARK: - /v1/identify

/**
 * Body for `POST /v1/identify`. Mirrors `app/schemas/identify.py::IdentifyRequest`.
 *
 * [traits] accepts arbitrary JSON values via [JSONValue] so callers can pass
 * any combination of `String`, `Boolean`, `Long`, `Double`, `null`, nested
 * objects, and arrays — matching the backend's `dict[str, Any]`.
 */
@Serializable
public data class IdentifyRequest(
    @SerialName("anonymous_id")
    val anonymousId: String?,
    @SerialName("external_id")
    val externalId: String,
    @SerialName("traits")
    val traits: Map<String, JSONValue>? = null,
    @SerialName("environment")
    val environment: WireEnvironment = WireEnvironment.LIVE,
)

/**
 * Response from `POST /v1/identify`. Mirrors `IdentifyResponse`.
 *
 * [contactId] is delivered as a UUID string on the wire; we keep it as
 * `String` rather than `java.util.UUID` so the data class stays plain
 * @Serializable without bespoke serializers. Callers that need UUID parsing
 * can do so at the boundary.
 */
@Serializable
public data class IdentifyResponse(
    @SerialName("contact_id")
    val contactId: String,
    @SerialName("path")
    val path: IdentifyPath,
    @SerialName("aliased_external_id")
    val aliasedExternalId: String?,
    @SerialName("events_reattributed")
    val eventsReattributed: Int,
    @SerialName("devices_reattributed")
    val devicesReattributed: Int,
    @SerialName("anonymous_contact_tombstoned")
    val anonymousContactTombstoned: Boolean,
)

// MARK: - /v1/alias

/**
 * Body for `POST /v1/alias`. Mirrors `app/schemas/alias.py::AliasRequest`.
 *
 * Both ids are required by the backend (no Optional on either) — if the
 * caller doesn't know the anonymousId, they should use `/v1/identify`.
 */
@Serializable
public data class AliasRequest(
    @SerialName("anonymous_id")
    val anonymousId: String,
    @SerialName("external_id")
    val externalId: String,
    @SerialName("environment")
    val environment: WireEnvironment = WireEnvironment.LIVE,
)

/**
 * Response from `POST /v1/alias`. Wire shape is identical to
 * [IdentifyResponse] (deliberately so the SDK can share the decoder).
 *
 * Kotlin does not have Swift's `typealias` for shared decoding semantics,
 * so we use a `typealias` of [IdentifyResponse] which preserves the
 * `@Serializable` machinery on the underlying class.
 */
public typealias AliasResponse = IdentifyResponse

// MARK: - /v1/devices

/**
 * Body for `POST /v1/devices`. Mirrors `app/schemas/device.py::DeviceRegister`.
 *
 * [platform] is wire-`String` (one of `"ios"`, `"android"`, `"web"`, `"huawei"`)
 * rather than a Kotlin enum so adding a platform on the server does not
 * require a forced SDK upgrade. The Android SDK only ever sends `"android"`.
 *
 * Declared now (in PR 2) so the wire shape is locked before PR 4 wires the
 * actual push registration call.
 */
@Serializable
public data class DeviceRegisterRequest(
    @SerialName("external_id")
    val externalId: String,
    @SerialName("platform")
    val platform: String,
    @SerialName("push_token")
    val pushToken: String,
    @SerialName("bundle_id")
    val bundleId: String? = null,
    @SerialName("app_version")
    val appVersion: String? = null,
    @SerialName("sdk_version")
    val sdkVersion: String? = null,
    @SerialName("sdk_platform")
    val sdkPlatform: String? = null,
    @SerialName("os_version")
    val osVersion: String? = null,
    @SerialName("device_model")
    val deviceModel: String? = null,
    @SerialName("locale")
    val locale: String? = null,
    @SerialName("timezone")
    val timezone: String? = null,
    @SerialName("environment")
    val environment: WireEnvironment = WireEnvironment.LIVE,
    @SerialName("push_enabled")
    val pushEnabled: Boolean = true,
    @SerialName("metadata")
    val metadata: Map<String, JSONValue> = emptyMap(),
)

/**
 * Response from `POST /v1/devices`. Mirrors `DeviceResponse`.
 *
 * PR 2 only needs the shape declared so PR 4 (push) can decode the response
 * without another schema round-trip. Every field is captured so debug menus
 * can inspect device registration end-to-end.
 */
@Serializable
public data class DeviceResponse(
    @SerialName("id")
    val id: String,
    @SerialName("contact_id")
    val contactId: String,
    @SerialName("platform")
    val platform: String,
    @SerialName("push_token")
    val pushToken: String,
    @SerialName("bundle_id")
    val bundleId: String?,
    @SerialName("app_version")
    val appVersion: String?,
    @SerialName("sdk_version")
    val sdkVersion: String?,
    @SerialName("sdk_platform")
    val sdkPlatform: String?,
    @SerialName("os_version")
    val osVersion: String?,
    @SerialName("device_model")
    val deviceModel: String?,
    @SerialName("locale")
    val locale: String?,
    @SerialName("timezone")
    val timezone: String?,
    @SerialName("environment")
    val environment: String,
    @SerialName("push_enabled")
    val pushEnabled: Boolean,
    @SerialName("last_seen_at")
    val lastSeenAt: String,
    @SerialName("registered_at")
    val registeredAt: String,
    @SerialName("revoked_at")
    val revokedAt: String?,
    @SerialName("metadata")
    val metadata: Map<String, JSONValue> = emptyMap(),
)

// MARK: - /v1/events

/**
 * Contact fields embeddable into an event upsert. Mirrors
 * `app/schemas/contact.py::ContactOverride`. Optional everywhere — only
 * non-null fields are applied by the server.
 *
 * Declared in PR 2 to lock the wire surface; consumed by PR 3 (events).
 */
@Serializable
public data class ContactOverride(
    @SerialName("email")
    val email: String? = null,
    @SerialName("phone")
    val phone: String? = null,
    @SerialName("first_name")
    val firstName: String? = null,
    @SerialName("last_name")
    val lastName: String? = null,
    @SerialName("timezone")
    val timezone: String? = null,
    @SerialName("locale")
    val locale: String? = null,
    @SerialName("properties")
    val properties: Map<String, JSONValue>? = null,
    @SerialName("tags")
    val tags: List<String>? = null,
)

/**
 * Body for `POST /v1/events`. Mirrors `app/schemas/event.py::EventIngest`.
 *
 * We deliberately only emit the **preferred** field names (`external_id`,
 * `contact`) and never the deprecated `user_id` / `contact_overrides`
 * aliases — new SDKs do not need to carry the legacy hump.
 *
 * `environment` is NOT a field here — the server derives it from the API
 * key prefix (`psk_live_` / `psk_test_`). See `app/auth/api_key.py`.
 *
 * Declared in PR 2 to lock the wire surface; consumed by PR 3 (events).
 */
@Serializable
public data class EventIngestRequest(
    @SerialName("external_id")
    val externalId: String,
    @SerialName("event_name")
    val eventName: String,
    @SerialName("attributes")
    val attributes: Map<String, JSONValue> = emptyMap(),
    @SerialName("idempotency_key")
    val idempotencyKey: String? = null,
    @SerialName("contact")
    val contact: ContactOverride? = null,
    @SerialName("occurred_at")
    val occurredAt: String? = null,
)

/**
 * Response from `POST /v1/events`. Mirrors `EventAccepted`.
 */
@Serializable
public data class EventAcceptedResponse(
    @SerialName("event_id")
    val eventId: String,
    @SerialName("status")
    val status: String,
)

// MARK: - /v1/push/opened + /v1/push/click

/**
 * Body for `POST /v1/push/opened`. Mirrors `PushOpenedRequest`.
 *
 * Declared in PR 2 to lock the wire surface; consumed by PR 4 (push).
 */
@Serializable
public data class PushOpenedRequest(
    @SerialName("push_log_id")
    val pushLogId: String,
    @SerialName("occurred_at")
    val occurredAt: String? = null,
)

/**
 * Body for `POST /v1/push/click`. Mirrors `PushClickedRequest`.
 *
 * Declared in PR 2 to lock the wire surface; consumed by PR 4 (push).
 */
@Serializable
public data class PushClickedRequest(
    @SerialName("push_log_id")
    val pushLogId: String,
    @SerialName("occurred_at")
    val occurredAt: String? = null,
    @SerialName("click_url")
    val clickUrl: String? = null,
)

/**
 * Response from both push telemetry endpoints. Mirrors `PushTelemetryResponse`.
 */
@Serializable
public data class PushTelemetryResponse(
    @SerialName("status")
    val status: PushTelemetryStatus,
    @SerialName("envelope_id")
    val envelopeId: String?,
    @SerialName("reason")
    val reason: String?,
)

// MARK: - JSONValue

/**
 * A type-erased JSON value the SDK can ferry into / out of `dict[str, Any]`
 * fields on the backend (event `attributes`, identify `traits`, device
 * `metadata`, contact `properties`).
 *
 * Mirrors iOS `JSONValue` and the browser SDK's union — we keep the same
 * shape (null / bool / number / string / array / object) across platforms
 * so cross-platform docs use one phrasing.
 *
 * Trade-off: this is verbose at the call site compared to `Any?`, but `Any?`
 * doesn't survive kotlinx.serialization without per-call-site polymorphic
 * machinery. The verbosity is the price of one-line `@Serializable` data
 * classes everywhere else.
 */
@Serializable(with = JSONValueSerializer::class)
public sealed class JSONValue {
    public object Null : JSONValue()

    public data class Bool(val value: Boolean) : JSONValue()

    /** 64-bit signed integer. Use [Num] for floating-point values. */
    public data class Int(val value: Long) : JSONValue()

    /** Double-precision floating point. Use [Int] for integers. */
    public data class Num(val value: Double) : JSONValue()

    public data class Str(val value: String) : JSONValue()

    public data class Arr(val value: List<JSONValue>) : JSONValue()

    public data class Obj(val value: Map<String, JSONValue>) : JSONValue()
}

/**
 * Custom kotlinx.serialization serializer for [JSONValue]. Decodes any JSON
 * primitive/array/object into the matching [JSONValue] case; encodes back
 * to the same JSON shape it came from.
 *
 * Implementation strategy: piggyback on kotlinx.serialization.json's
 * `JsonElement` AST. We require a `JsonEncoder`/`JsonDecoder` (i.e. the
 * format must be JSON — which is the only format this SDK uses on the
 * wire), and convert between `JsonElement` and [JSONValue] cases.
 */
public object JSONValueSerializer : KSerializer<JSONValue> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("JSONValue")

    override fun deserialize(decoder: Decoder): JSONValue {
        val jsonDecoder = decoder as? JsonDecoder ?: error("JSONValue can only be decoded from JSON.")
        return fromJsonElement(jsonDecoder.decodeJsonElement())
    }

    override fun serialize(
        encoder: Encoder,
        value: JSONValue,
    ) {
        val jsonEncoder = encoder as? JsonEncoder ?: error("JSONValue can only be encoded to JSON.")
        jsonEncoder.encodeJsonElement(toJsonElement(value))
    }

    /** Convert a kotlinx.serialization [JsonElement] into the equivalent [JSONValue]. */
    private fun fromJsonElement(element: JsonElement): JSONValue =
        when (element) {
            is JsonNull -> JSONValue.Null
            is JsonPrimitive -> fromJsonPrimitive(element)
            is JsonArray -> JSONValue.Arr(element.map { fromJsonElement(it) })
            is JsonObject -> JSONValue.Obj(element.mapValues { fromJsonElement(it.value) })
        }

    /**
     * Map a [JsonPrimitive] to the matching [JSONValue] case. Order matters:
     *   1. String (quoted in JSON) — `prim.isString` is true.
     *   2. Boolean literal (`true` / `false`).
     *   3. Integer — try Long BEFORE Double so integers don't become floats
     *      on the round-trip.
     *   4. Floating-point — Double parse of any remaining numeric form.
     *   5. Fallback — treat as opaque string. Reached only for non-JSON-valid
     *      primitives (kotlinx.serialization treats numeric-looking
     *      unquoted tokens permissively).
     */
    private fun fromJsonPrimitive(prim: JsonPrimitive): JSONValue {
        val value: JSONValue =
            if (prim.isString) {
                JSONValue.Str(prim.content)
            } else {
                prim.booleanOrNull?.let { JSONValue.Bool(it) }
                    ?: prim.longOrNull?.let { JSONValue.Int(it) }
                    ?: prim.doubleOrNull?.let { JSONValue.Num(it) }
                    ?: JSONValue.Str(prim.content)
            }
        return value
    }

    /** Convert a [JSONValue] back into a kotlinx.serialization [JsonElement]. */
    private fun toJsonElement(value: JSONValue): JsonElement =
        when (value) {
            JSONValue.Null -> JsonNull
            is JSONValue.Bool -> JsonPrimitive(value.value)
            is JSONValue.Int -> JsonPrimitive(value.value)
            is JSONValue.Num -> JsonPrimitive(value.value)
            is JSONValue.Str -> JsonPrimitive(value.value)
            is JSONValue.Arr -> JsonArray(value.value.map { toJsonElement(it) })
            is JSONValue.Obj -> JsonObject(value.value.mapValues { toJsonElement(it.value) })
        }
}
