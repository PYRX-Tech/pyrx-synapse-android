/*
 * HTTPSession.kt
 * PYRXSynapse — Android
 *
 * Minimal abstraction over `okhttp3.Call.Factory` so unit tests can swap in
 * a mock without going over the wire. Production uses an `OkHttpClient` via
 * the default conformance below; tests use `MockHTTPSession` (defined in
 * the test source set).
 *
 * Kept deliberately thin — one method, one shape — so we do not have to
 * re-implement OkHttp in tests. The whole HTTP surface of the SDK goes
 * through [HTTPClient] (this PR) which in turn calls this interface.
 *
 * Mirrors iOS `HTTPSession` protocol verbatim — same method shape (request
 * in, response-data + metadata out), same suspend semantics. The iOS
 * implementation wraps `URLSession.data(for:)`; Android wraps `OkHttpClient
 * .newCall(request).enqueue(...)` bridged through a continuation.
 */

package tech.pyrx.synapse.network

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A captured (request, response) round-trip. We return both so the caller
 * (HTTPClient) can introspect status / headers / body bytes without
 * re-coupling itself to OkHttp types in its own signature.
 */
public data class HTTPResponse(
    val statusCode: Int,
    val body: ByteArray,
    val headers: Map<String, String>,
) {
    // ByteArray needs hand-rolled equals/hashCode (data-class default is identity).
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HTTPResponse) return false
        return statusCode == other.statusCode &&
            body.contentEquals(other.body) &&
            headers == other.headers
    }

    override fun hashCode(): Int {
        var result = statusCode
        result = 31 * result + body.contentHashCode()
        result = 31 * result + headers.hashCode()
        return result
    }
}

/**
 * Network transport seam. Implementations MUST be thread-safe.
 *
 * The production conformance is [OkHttpSession] (below) which wraps an
 * [OkHttpClient]. Tests pass a `MockHTTPSession` so no real network calls
 * happen in `./gradlew test`.
 */
public interface HTTPSession {
    /**
     * Perform [request] and return the response status + body + headers.
     *
     * Errors thrown here propagate to [HTTPClient] which maps them to
     * [tech.pyrx.synapse.PyrxError.Network] with a [tech.pyrx.synapse
     * .PyrxNetworkError.Transport] discriminator.
     */
    public suspend fun execute(request: Request): HTTPResponse
}

/**
 * Production [HTTPSession] backed by [OkHttpClient]. Construct once and
 * keep — OkHttp manages its own connection pool and worker dispatcher.
 *
 * @param client The underlying OkHttp client. Defaults to a fresh one with
 *               10s connect + read + write timeouts (matching the iOS
 *               `URLRequest.timeoutInterval = 10` default in [HTTPClient]).
 */
public class OkHttpSession(
    private val client: OkHttpClient = defaultClient(),
) : HTTPSession {
    override suspend fun execute(request: Request): HTTPResponse =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(SessionCallback(continuation))
        }

    public companion object {
        /**
         * Build the default [OkHttpClient] used when callers don't supply one.
         *
         * Timeouts mirror iOS `HTTPClient`'s 10s `URLRequest.timeoutInterval`.
         * Connection pool / dispatcher use OkHttp defaults — appropriate for
         * the request rate this SDK generates (one POST per identify / alias
         * / track call, queue-batched in PR 3).
         */
        public fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()

        /** Per-request default timeout in seconds. Matches iOS `HTTPClient.timeout = 10`. */
        public const val DEFAULT_TIMEOUT_SECONDS: Long = 10L
    }
}

/**
 * Bridges OkHttp's async [Callback] into the coroutine continuation. Closes
 * the response body before resuming to avoid leaking connections back into
 * OkHttp's pool — without this, the connection would be parked open until
 * GC reclaims the body.
 */
private class SessionCallback(
    private val continuation: CancellableContinuation<HTTPResponse>,
) : Callback {
    override fun onFailure(
        call: Call,
        e: IOException,
    ) {
        if (continuation.isActive) {
            continuation.resumeWithException(e)
        }
    }

    override fun onResponse(
        call: Call,
        response: Response,
    ) {
        response.use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            val headers = resp.headers.toMultimap().mapValues { it.value.joinToString(",") }
            if (continuation.isActive) {
                continuation.resume(
                    HTTPResponse(
                        statusCode = resp.code,
                        body = bytes,
                        headers = headers,
                    ),
                )
            }
        }
    }
}
