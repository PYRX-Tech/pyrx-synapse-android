/*
 * MockHTTPSession.kt
 * PYRXSynapseTests — Android
 *
 * In-process [HTTPSession] stub. Records every request the SDK makes and
 * replays canned [HTTPResponse]s. Tests never touch the real network —
 * `./gradlew test` is hermetic and stays well under the CI wall-clock budget.
 *
 * Mirrors iOS `MockHTTPSession.swift` semantics: FIFO canned responses,
 * recorded requests for body / header assertions, single-class for both
 * success-tuple and failure-throw replay modes.
 */

package tech.pyrx.synapse.network

import okhttp3.Request
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A captured request the mock saw. We snapshot the OkHttp [Request] (which
 * is immutable) AND the request body bytes (which OkHttp materialises
 * lazily) so tests can assert on body shape without re-reading the body
 * source.
 */
data class RecordedRequest(
    val request: Request,
    val body: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RecordedRequest) return false
        return request == other.request && body.contentEquals(other.body)
    }

    override fun hashCode(): Int = 31 * request.hashCode() + body.contentHashCode()
}

/**
 * A canned response. Either a [Success] tuple OR a [Failure] to throw on
 * `execute(...)`. The mock pops responses in FIFO order — tests that issue
 * multiple requests should enqueue multiple responses.
 */
sealed class CannedResponse {
    data class Success(
        val statusCode: Int,
        val body: ByteArray,
        val headers: Map<String, String>,
    ) : CannedResponse() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Success) return false
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

    data class Failure(val error: Throwable) : CannedResponse()
}

class MockHTTPSession : HTTPSession {
    private val lock = ReentrantLock()
    private val queued: ArrayDeque<CannedResponse> = ArrayDeque()
    private val _requests: MutableList<RecordedRequest> = mutableListOf()

    val requests: List<RecordedRequest>
        get() = lock.withLock { _requests.toList() }

    /** Enqueue a JSON success response. Body is serialised to UTF-8 bytes. */
    fun enqueueJsonSuccess(
        statusCode: Int = 200,
        json: String,
    ) {
        enqueue(
            CannedResponse.Success(
                statusCode = statusCode,
                body = json.toByteArray(Charsets.UTF_8),
                headers = mapOf("Content-Type" to "application/json"),
            ),
        )
    }

    /** Enqueue a raw response — used to test non-2xx and decode failures. */
    fun enqueue(response: CannedResponse) {
        lock.withLock { queued.addLast(response) }
    }

    override suspend fun execute(request: Request): HTTPResponse {
        val popped =
            lock.withLock {
                check(queued.isNotEmpty()) { "MockHTTPSession: no canned response queued" }
                // Materialise the body so the buffer can be inspected even after
                // the request leaves this scope.
                val bodyBytes =
                    request.body?.let { rb ->
                        val buf = okio.Buffer()
                        rb.writeTo(buf)
                        buf.readByteArray()
                    } ?: ByteArray(0)
                _requests.add(RecordedRequest(request = request, body = bodyBytes))
                queued.removeFirst()
            }

        return when (popped) {
            is CannedResponse.Success ->
                HTTPResponse(
                    statusCode = popped.statusCode,
                    body = popped.body,
                    headers = popped.headers,
                )
            is CannedResponse.Failure -> {
                val err = popped.error
                // Preserve iOS's distinction between IOException (transport)
                // and other Throwable — HTTPClient maps both to .Transport.
                if (err is IOException) throw err else throw err
            }
        }
    }
}
