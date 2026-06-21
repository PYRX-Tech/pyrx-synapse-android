/*
 * MockHTTPSession.kt  (synapse-push test copy)
 * PYRXSynapseTests — Android
 *
 * Test-source copy of synapse-core's MockHTTPSession (line-for-line). We
 * keep a copy here rather than depending on synapse-core's test source
 * set because AGP test fixtures are awkward to wire across modules, and
 * the file is short / stable enough that the duplication is a net win.
 *
 * If synapse-core's MockHTTPSession ever changes shape, this file must
 * be updated in lock-step — both files share the same FIFO canned-response
 * + recorded-requests contract.
 */

package tech.pyrx.synapse.push

import okhttp3.Request
import tech.pyrx.synapse.network.HTTPResponse
import tech.pyrx.synapse.network.HTTPSession
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
                if (err is IOException) throw err else throw err
            }
        }
    }
}
