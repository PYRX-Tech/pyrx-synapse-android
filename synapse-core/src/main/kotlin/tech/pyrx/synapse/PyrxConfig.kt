/*
 * PyrxConfig.kt
 * PYRXSynapse — Android
 *
 * Immutable SDK configuration. Pass to `Pyrx.initialize(context, config)`
 * exactly once per process — typically from `Application.onCreate`.
 *
 * Mirrors iOS `PyrxConfig` field-for-field so cross-platform docs stay
 * accurate. Validation rules are identical (apiKey prefix, base URL scheme).
 */

package tech.pyrx.synapse

import java.util.UUID

/**
 * Configuration for the PYRX Synapse SDK.
 *
 * @property workspaceId Synapse workspace identifier (UUID v4).
 * @property apiKey Public ingestion API key. Format: `psk_{env}_{hex32}`.
 *                  Environment (live/test) is inferred from the prefix by
 *                  the backend — the SDK passes the key verbatim.
 * @property environment Runtime environment selector ([PyrxEnvironment.PRODUCTION] by default).
 * @property baseUrl Ingestion API base URL. Defaults to the production endpoint
 *                   `https://synapse-events.pyrx.tech`.
 * @property logLevel Verbosity for the internal logger. Defaults to [LogLevel.INFO].
 * @property maxQueueSize Maximum number of events the offline queue retains
 *                        on disk before evicting the oldest. PR 3 introduces
 *                        the queue; this knob exists so a host app with
 *                        high event volume + intermittent connectivity can
 *                        opt into a larger or smaller buffer. Defaults to
 *                        [DEFAULT_MAX_QUEUE_SIZE] (matches iOS PR #3).
 */
public data class PyrxConfig(
    val workspaceId: UUID,
    val apiKey: String,
    val environment: PyrxEnvironment = PyrxEnvironment.PRODUCTION,
    val baseUrl: String = DEFAULT_BASE_URL,
    val logLevel: LogLevel = LogLevel.INFO,
    val maxQueueSize: Int = DEFAULT_MAX_QUEUE_SIZE,
) {
    /**
     * Validate this configuration. Called by [Pyrx.initialize] before any
     * other setup runs. Throws [PyrxError.InvalidConfig] with a precise
     * `reason` string on failure — surface that to the developer's logs.
     */
    @Throws(PyrxError::class)
    public fun validate() {
        val reason = firstValidationFailure() ?: return
        throw PyrxError.InvalidConfig(reason)
    }

    /**
     * Returns the first (and only the first) validation failure reason, or
     * `null` when the config is valid. Splitting the validation rules out of
     * [validate] keeps the throw count at one and makes the rule set easier
     * to extend.
     */
    private fun firstValidationFailure(): String? {
        val trimmedKey = apiKey.trim()
        return when {
            trimmedKey.isEmpty() -> "apiKey must not be empty"
            !trimmedKey.startsWith("psk_") -> "apiKey must start with 'psk_'"
            !baseUrl.startsWith("http://") && !baseUrl.startsWith("https://") ->
                "baseUrl must use http(s) scheme"
            maxQueueSize < 1 -> "maxQueueSize must be >= 1"
            else -> null
        }
    }

    public companion object {
        /** Default production ingestion endpoint. Matches iOS `PyrxConfig.defaultBaseUrl`. */
        public const val DEFAULT_BASE_URL: String = "https://synapse-events.pyrx.tech"

        /**
         * Default offline-queue bound. 1000 events × ~500 bytes/event ≈ 500 KB
         * on disk worst-case — fits comfortably within a host app's cache
         * budget while covering hours of offline activity for typical apps.
         * Matches iOS `PyrxConfig.defaultMaxQueueSize`.
         */
        public const val DEFAULT_MAX_QUEUE_SIZE: Int = 1000
    }
}
