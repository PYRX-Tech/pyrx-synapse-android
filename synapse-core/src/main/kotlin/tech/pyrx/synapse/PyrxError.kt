/*
 * PyrxError.kt
 * PYRXSynapse — Android
 *
 * Typed error hierarchy surfaced from the SDK's public API. Every `suspend`
 * method that fails throws a subclass of [PyrxError] so callers can
 * pattern-match against well-known failure modes (`is PyrxError.NotInitialized`,
 * `is PyrxError.Network`).
 *
 * Mirrors iOS `PyrxError` cases:
 *   - alreadyInitialized → [AlreadyInitialized]
 *   - notInitialized     → [NotInitialized]
 *   - invalidConfig      → [InvalidConfig]
 *   - keychainFailure    → [StorageFailure] (Android uses EncryptedSharedPreferences)
 *   - network            → [Network] (wraps [PyrxNetworkError] — populated in PR 2)
 */

package tech.pyrx.synapse

/**
 * All errors thrown by the public PYRXSynapse API. Sealed so consumers can
 * exhaustively `when`-match every failure mode at compile time.
 */
public sealed class PyrxError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /**
     * `initialize` was called more than once with conflicting values.
     *
     * Singleton — identity is preserved by Kotlin `object` semantics within
     * a process. Cross-process serialization is unsupported for SDK errors.
     */
    public object AlreadyInitialized : PyrxError("PYRXSynapse: SDK is already initialized.")

    /** A method that requires `initialize` was called before init. */
    public object NotInitialized : PyrxError(
        "PYRXSynapse: SDK has not been initialized — call Pyrx.initialize(context, config) first.",
    )

    /** Configuration validation failed. [reason] is human-readable. */
    public data class InvalidConfig(val reason: String) :
        PyrxError("PYRXSynapse: invalid configuration — $reason.")

    /**
     * Persistent-storage operation failed. On Android this wraps any failure
     * from EncryptedSharedPreferences (Keystore-bound symmetric crypto on
     * first run, IO on subsequent reads / writes).
     */
    public data class StorageFailure(val operation: String, val underlying: Throwable? = null) :
        PyrxError("PYRXSynapse: storage operation '$operation' failed.", underlying)

    /**
     * A network call failed. See [PyrxNetworkError] for the discriminated
     * failure mode (transport, non-2xx status, decode). Populated in PR 2 —
     * the discriminator is wired now so PR 2 only adds the call sites.
     */
    public data class Network(val inner: PyrxNetworkError) :
        PyrxError("PYRXSynapse: network call failed — ${inner.message}", inner)
}

/**
 * Discriminated network-failure variants. Wrapped in [PyrxError.Network].
 *
 * Mirrors iOS `PyrxNetworkError`:
 *   1. [Transport] — underlying transport threw (DNS, TLS, timeout, connection refused).
 *   2. [InvalidResponse] — server returned a response shape the client can't parse.
 *   3. [HttpStatus] — server returned a non-2xx status. `body` carries raw bytes
 *      for diagnostic logs / retry decisions (PR 3 wires off `statusCode`).
 *   4. [Decode] — body was not parseable as the expected type.
 */
public sealed class PyrxNetworkError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    public data class Transport(val underlying: Throwable) :
        PyrxNetworkError("transport: ${underlying.message}", underlying)

    public object InvalidResponse : PyrxNetworkError("invalid response")

    public data class HttpStatus(val statusCode: Int, val body: ByteArray) :
        PyrxNetworkError("HTTP $statusCode") {
        // ByteArray needs hand-rolled equals/hashCode (data-class default uses identity).
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is HttpStatus) return false
            return statusCode == other.statusCode && body.contentEquals(other.body)
        }

        override fun hashCode(): Int = 31 * statusCode + body.contentHashCode()
    }

    public data class Decode(val underlying: Throwable) :
        PyrxNetworkError("decode failed: ${underlying.message}", underlying)
}
