/*
 * HTTPClient.kt
 * PYRXSynapse — Android
 *
 * Typed JSON HTTP client for the PYRX Synapse backend. Owns:
 *
 *   - URL construction from `PyrxConfig.baseUrl` + endpoint path
 *   - Required-header injection on every request
 *   - JSON encode of @Serializable request bodies via kotlinx.serialization
 *   - JSON decode of @Serializable response bodies via kotlinx.serialization
 *   - HTTP status / transport / decode error mapping into `PyrxError.Network`
 *
 * Does NOT own:
 *
 *   - Retry / exponential backoff — PR 3 (offline queue) owns retries; this
 *     client surfaces the error so the queue can decide.
 *   - Auth state — `PyrxConfig` carries workspaceId + apiKey; the client
 *     reads them from the config it was constructed with.
 *   - Per-request idempotency keys — wired in PR 3 alongside the queue.
 *
 * Headers (matches backend contract verbatim — see ARCHITECTURE.md §17.6
 * for auth headers and §28.7 for SDK telemetry headers):
 *
 *   X-WORKSPACE-ID:       <PyrxConfig.workspaceId>
 *   X-API-KEY:            <PyrxConfig.apiKey>            // psk_{env}_{hex32}
 *   X-PYRX-SDK-VERSION:   <PyrxConstants.SDK_VERSION>
 *   X-PYRX-SDK-PLATFORM:  android
 *   Content-Type:         application/json
 *
 * Environment is NOT carried as a header — the server derives it from the
 * API key prefix (`psk_live_` vs `psk_test_`). Endpoints that need an
 * explicit `environment` field (identify, alias, devices) carry it in the
 * JSON body via [WireEnvironment].
 *
 * Mirrors iOS `HTTPClient.swift` verbatim — same endpoints, same header
 * names, same error mapping.
 */

package tech.pyrx.synapse.network

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tech.pyrx.synapse.PyrxConfig
import tech.pyrx.synapse.PyrxConstants
import tech.pyrx.synapse.PyrxError
import tech.pyrx.synapse.PyrxNetworkError
import java.io.IOException

/**
 * Typed JSON HTTP client. Construct via [HTTPClient] and keep the instance
 * for the lifetime of the SDK (cheap — no state besides the [Json] codec
 * and the [HTTPSession]). Thread-safe — [Json] is immutable and the session
 * contract requires thread-safety.
 *
 * @param config The validated SDK configuration (workspaceId + apiKey + baseUrl).
 * @param session Transport seam. Defaults to [OkHttpSession] in production;
 *                tests inject a `MockHTTPSession`.
 * @param json The kotlinx.serialization JSON codec. Defaults to one configured
 *             to ignore unknown keys (forward-compatible with backend
 *             additions) and explicit-nulls=true (matches Pydantic which
 *             round-trips null fields).
 */
public class HTTPClient(
    private val config: PyrxConfig,
    private val session: HTTPSession = OkHttpSession(),
    private val json: Json = defaultJson(),
) {
    // MARK: - Endpoint paths
    //
    // Centralised so a future server-side rename only changes one constant.
    // These match `app/routers/*.py` route prefixes verbatim.

    /**
     * Backend endpoint paths. Path strings are a wire contract — changing
     * one requires a coordinated iOS + browser SDK PR.
     */
    public enum class Endpoint(public val path: String) {
        DEVICES_REGISTER("/v1/devices"),
        IDENTIFY("/v1/identify"),
        ALIAS("/v1/alias"),
        EVENTS("/v1/events"),
        PUSH_OPENED("/v1/push/opened"),
        PUSH_CLICK("/v1/push/click"),
    }

    // MARK: - Header names (canonical wire surface)

    /**
     * Header names sent on every request. Capitalisation matches the
     * backend contract verbatim — do not lowercase.
     */
    public object HeaderName {
        public const val WORKSPACE_ID: String = "X-WORKSPACE-ID"
        public const val API_KEY: String = "X-API-KEY"
        public const val SDK_VERSION: String = "X-PYRX-SDK-VERSION"
        public const val SDK_PLATFORM: String = "X-PYRX-SDK-PLATFORM"
        public const val CONTENT_TYPE: String = "Content-Type"
    }

    // MARK: - Public POST

    /**
     * POST [endpoint] with a @Serializable [body] and decode the response
     * using the supplied [responseSerializer].
     *
     * We require an explicit serializer (rather than reified generics) so
     * the call site reads identically to iOS — `httpClient.post(.identify,
     * body: req, responseType: IdentifyResponse.self)` becomes `httpClient
     * .post(Endpoint.IDENTIFY, bodySerializer, body, responseSerializer)`.
     *
     * Convenience overloads via [reifiedPost] below.
     *
     * @throws PyrxError.Network on transport / HTTP / decode failure.
     */
    public suspend fun <B, R> post(
        endpoint: Endpoint,
        bodySerializer: KSerializer<B>,
        body: B,
        responseSerializer: KSerializer<R>,
    ): R {
        val request = buildRequest(endpoint, bodySerializer, body)
        val response = perform(request)
        validate(response)
        return decode(response.body, responseSerializer)
    }

    /**
     * POST [endpoint] with a @Serializable [body] and discard the response
     * body. Use for endpoints whose 200/202 response carries no info the
     * SDK needs to surface (telemetry callbacks, ack-only writes).
     *
     * @throws PyrxError.Network on transport / HTTP failure.
     */
    public suspend fun <B> postVoid(
        endpoint: Endpoint,
        bodySerializer: KSerializer<B>,
        body: B,
    ) {
        val request = buildRequest(endpoint, bodySerializer, body)
        val response = perform(request)
        validate(response)
    }

    // MARK: - Request construction

    /**
     * Build a [Request] for [endpoint] with [body] JSON-encoded.
     *
     * Internal access (package-private) so unit tests can assert header
     * injection without going through [perform].
     */
    internal fun <B> buildRequest(
        endpoint: Endpoint,
        bodySerializer: KSerializer<B>,
        body: B,
    ): Request {
        val url = "${config.baseUrl.trimEnd('/')}${endpoint.path}"

        val bodyBytes =
            try {
                json.encodeToString(bodySerializer, body).toByteArray(Charsets.UTF_8)
            } catch (e: SerializationException) {
                // Encode failure is an SDK bug (we control every @Serializable
                // shape) — surface as a decode-ish error rather than crashing.
                throw PyrxError.Network(PyrxNetworkError.Decode(e))
            }

        val requestBody = bodyBytes.toRequestBody(JSON_MEDIA_TYPE)

        return Request.Builder()
            .url(url)
            .post(requestBody)
            // Required headers — every PYRX SDK call carries the same 5.
            .header(HeaderName.WORKSPACE_ID, config.workspaceId.toString())
            .header(HeaderName.API_KEY, config.apiKey)
            .header(HeaderName.SDK_VERSION, PyrxConstants.SDK_VERSION)
            .header(HeaderName.SDK_PLATFORM, PyrxConstants.PLATFORM)
            .header(HeaderName.CONTENT_TYPE, JSON_CONTENT_TYPE)
            .build()
    }

    // MARK: - Internals

    private suspend fun perform(request: Request): HTTPResponse =
        try {
            session.execute(request)
        } catch (e: IOException) {
            throw PyrxError.Network(PyrxNetworkError.Transport(e))
        } catch (e: RuntimeException) {
            // OkHttp can wrap connection-pool / cancellation issues in
            // RuntimeException — preserve them as transport errors so the
            // queue (PR 3) treats them as retry-able.
            throw PyrxError.Network(PyrxNetworkError.Transport(e))
        }

    private fun validate(response: HTTPResponse) {
        if (response.statusCode !in HTTP_2XX_RANGE) {
            throw PyrxError.Network(
                PyrxNetworkError.HttpStatus(
                    statusCode = response.statusCode,
                    body = response.body,
                ),
            )
        }
    }

    private fun <R> decode(
        bytes: ByteArray,
        serializer: KSerializer<R>,
    ): R {
        // Single try/catch around the whole pipeline so detekt's throw-count
        // budget (default 2) isn't exceeded. Both UTF-8 decode and JSON
        // decode failures land in the same .Decode bucket — they are
        // indistinguishable to a caller for retry / report purposes.
        return try {
            val text = bytes.toString(Charsets.UTF_8)
            json.decodeFromString(serializer, text)
        } catch (e: SerializationException) {
            throw PyrxError.Network(PyrxNetworkError.Decode(e))
        } catch (e: IllegalArgumentException) {
            // kotlinx.serialization throws IAE on some malformed inputs
            // (e.g., missing required field of an enum).
            throw PyrxError.Network(PyrxNetworkError.Decode(e))
        }
    }

    public companion object {
        private val HTTP_2XX_RANGE = 200..299

        /** JSON content-type sent on every request. */
        public const val JSON_CONTENT_TYPE: String = "application/json"

        private val JSON_MEDIA_TYPE = JSON_CONTENT_TYPE.toMediaType()

        /**
         * Default [Json] configuration:
         *   - `ignoreUnknownKeys = true` — forward-compat with backend
         *     additions the SDK hasn't shipped a schema bump for yet.
         *   - `explicitNulls = false` — Swift's auto-synthesized Codable
         *     uses `encodeIfPresent` for Optional properties, so null
         *     optionals are OMITTED from JSON (verified against iOS
         *     `HTTPClientTests.test_identifyRequest_encodesSnakeCaseKeys`
         *     which asserts `XCTAssertNil(json?["traits"])` when traits=nil).
         *   - `encodeDefaults = true` — Swift Codable encodes every stored
         *     property regardless of whether it equals a constructor default,
         *     so default-valued fields like `environment = .live` always
         *     appear on the wire. The iOS identify test verifies this:
         *     `XCTAssertEqual(json?["environment"] as? String, "live")`.
         *
         * Mirrors iOS `JSONEncoder` defaults. iOS does NOT use a date strategy
         * — the wire format is ISO-8601 strings for datetimes; we surface
         * them as `String` on Codables so callers pick their own formatter.
         */
        @OptIn(ExperimentalSerializationApi::class)
        public fun defaultJson(): Json =
            Json {
                ignoreUnknownKeys = true
                // `explicitNulls` is still marked experimental in
                // kotlinx.serialization 1.6.x but the underlying behaviour
                // is stable and shipped in Compose / KMP libraries widely.
                // We opt in deliberately so null Optional properties are
                // omitted, matching iOS Swift Codable's `encodeIfPresent`.
                explicitNulls = false
                encodeDefaults = true
            }
    }
}
