/*
 * IdentityManager.kt
 * PYRXSynapse — Android
 *
 * Anonymous-merge state machine per ARCHITECTURE.md §28.4 + push SDK plan
 * §5.3. Owned solely by the [tech.pyrx.synapse.Pyrx] singleton — never
 * instantiated by callers.
 *
 * State held on disk (PyrxStorage / EncryptedStore):
 *
 *   ANONYMOUS_ID  — generated UUIDv4 at first launch, persists forever
 *                   (PR 1 / Pyrx.ensureAnonymousId)
 *   EXTERNAL_ID   — set by identify(externalId, traits), cleared by logout()
 *   DEVICE_TOKEN  — set by PR 4 push registration, NOT cleared by logout()
 *
 * Lifecycle:
 *
 *   1. First launch — anonymousId is minted by PR 1; nothing else exists.
 *      Events flow as `external_id = anonymousId` until identify is called.
 *
 *   2. identify(externalId) — POST /v1/identify with both ids; server
 *      performs the merge (known_exists / first_sighting / no_anonymous).
 *      Client persists externalId. anonymousId is NOT cleared (audit /
 *      diagnostics; the server is the source of truth for the merge).
 *
 *   3. alias(newExternalId) — POST /v1/alias linking an already-known
 *      external_id to a new external_id. Requires anonymousId on disk;
 *      throws PyrxError.NotInitialized otherwise.
 *
 *   4. logout() — purely client-side. Clears EXTERNAL_ID from storage so
 *      subsequent events fall back to anonymousId. KEEPS ANONYMOUS_ID +
 *      DEVICE_TOKEN so the device row stays valid for re-attribution.
 *      NO server call (the server cannot tell us to forget — the SDK owns
 *      the local identity).
 *
 * Concurrency: every public method is `suspend`. The [tech.pyrx.synapse.Pyrx]
 * singleton serializes calls into the manager via its init mutex; the
 * manager itself is thread-safe because it only forwards into the
 * thread-safe storage and stateless HTTPClient.
 *
 * Mirrors iOS `IdentityManager.swift` semantics exactly.
 */

package tech.pyrx.synapse.identity

import tech.pyrx.synapse.PyrxError
import tech.pyrx.synapse.PyrxLogger
import tech.pyrx.synapse.network.AliasRequest
import tech.pyrx.synapse.network.AliasResponse
import tech.pyrx.synapse.network.HTTPClient
import tech.pyrx.synapse.network.IdentifyPath
import tech.pyrx.synapse.network.IdentifyRequest
import tech.pyrx.synapse.network.IdentifyResponse
import tech.pyrx.synapse.network.JSONValue
import tech.pyrx.synapse.network.WireEnvironment
import tech.pyrx.synapse.storage.PyrxStorage
import tech.pyrx.synapse.storage.PyrxStorageKey

/**
 * Per-call return shape for [IdentityManager.identify] and
 * [IdentityManager.alias]. Wraps the server response so callers can log
 * which merge branch ran for support cases.
 *
 * Mirrors iOS `IdentityResult` field-for-field.
 */
public data class IdentityResult(
    val contactId: String,
    val path: IdentifyPath,
    val aliasedExternalId: String?,
    val eventsReattributed: Int,
    val devicesReattributed: Int,
    val anonymousContactTombstoned: Boolean,
) {
    public companion object {
        internal fun from(response: IdentifyResponse): IdentityResult =
            IdentityResult(
                contactId = response.contactId,
                path = response.path,
                aliasedExternalId = response.aliasedExternalId,
                eventsReattributed = response.eventsReattributed,
                devicesReattributed = response.devicesReattributed,
                anonymousContactTombstoned = response.anonymousContactTombstoned,
            )
    }
}

/**
 * Identity state machine. Constructed by [tech.pyrx.synapse.Pyrx] during
 * [tech.pyrx.synapse.Pyrx.initialize]; not part of the public API surface
 * (package-internal).
 *
 * @param storage Thread-safe persistent K/V store (anonymousId + externalId).
 * @param httpClient The wire-level HTTP client (constructed alongside this
 *                   manager from the same [tech.pyrx.synapse.PyrxConfig]).
 * @param environment Wire-side environment discriminator. Translated from
 *                    `PyrxConfig.environment` at construction time so the
 *                    manager doesn't need to re-translate on every call.
 * @param logger Internal logger.
 */
internal class IdentityManager(
    private val storage: PyrxStorage,
    private val httpClient: HTTPClient,
    private val environment: WireEnvironment,
    private val logger: PyrxLogger,
) {
    // MARK: - identify

    /**
     * Identify an anonymous SDK session into a known contact.
     *
     * Server-side state machine (push SDK plan §5.3):
     *
     *   * **known_exists**   — both contacts already exist; server merges
     *                          anonymous → canonical, re-attributes events
     *                          + devices, tombstones anonymous.
     *   * **first_sighting** — only the anonymous contact exists; rename
     *                          in place.
     *   * **no_anonymous**   — no anonymous contact; plain upsert.
     *
     * Client-side after success: persists EXTERNAL_ID to storage. Keeps
     * ANONYMOUS_ID for audit (server is authoritative for the merge).
     *
     * @param externalId canonical contact identity (e.g. your user id).
     * @param traits optional shallow-merge into `Contact.properties`.
     * @return The server's [IdentityResult] for support / diagnostics.
     * @throws PyrxError.InvalidConfig on blank externalId.
     * @throws PyrxError.Network on transport / HTTP failure.
     * @throws PyrxError.StorageFailure on persist failure.
     */
    @Throws(PyrxError::class)
    suspend fun identify(
        externalId: String,
        traits: Map<String, JSONValue>? = null,
    ): IdentityResult {
        val trimmed = externalId.trim()
        if (trimmed.isEmpty()) {
            throw PyrxError.InvalidConfig("externalId must not be empty")
        }

        // anonymousId is set by Pyrx.ensureAnonymousId during initialize().
        // If somehow missing at this point, send null — the server tolerates
        // it (path C: no_anonymous) and we recover by generating one on the
        // next event flow (PR 3).
        val anonymousId = storage.get(PyrxStorageKey.ANONYMOUS_ID)

        val request =
            IdentifyRequest(
                anonymousId = anonymousId,
                externalId = trimmed,
                traits = traits,
                environment = environment,
            )

        val response: IdentifyResponse =
            httpClient.post(
                endpoint = HTTPClient.Endpoint.IDENTIFY,
                bodySerializer = IdentifyRequest.serializer(),
                body = request,
                responseSerializer = IdentifyResponse.serializer(),
            )

        // Persist the externalId. Keep anonymousId — it is audit-only after
        // the merge; the server has already re-attributed history.
        storage.set(PyrxStorageKey.EXTERNAL_ID, trimmed)

        logger.info {
            "identify completed — path=${response.path} contact=${response.contactId}"
        }
        return IdentityResult.from(response)
    }

    // MARK: - alias

    /**
     * Explicitly merge an anonymous external_id into a known external_id.
     *
     * Both ids are required by the backend. We pass the on-disk ANONYMOUS_ID
     * as the prior id and [newExternalId] as the target. After success,
     * [newExternalId] is persisted as the current EXTERNAL_ID.
     *
     * @param newExternalId the canonical identity to merge into.
     * @throws PyrxError.InvalidConfig on blank newExternalId.
     * @throws PyrxError.NotInitialized if no ANONYMOUS_ID is on disk
     *         (initialize() must have run first).
     * @throws PyrxError.Network on transport / HTTP failure.
     * @throws PyrxError.StorageFailure on persist failure.
     */
    @Throws(PyrxError::class)
    suspend fun alias(newExternalId: String): IdentityResult {
        val trimmed = newExternalId.trim()
        if (trimmed.isEmpty()) {
            throw PyrxError.InvalidConfig("newExternalId must not be empty")
        }

        // /v1/alias requires both ids — there must be an ANONYMOUS_ID on disk.
        // If a caller calls alias() without ever calling initialize(), this
        // is the failure mode they see.
        val anonymousId = storage.get(PyrxStorageKey.ANONYMOUS_ID)
        if (anonymousId.isNullOrEmpty()) {
            throw PyrxError.NotInitialized
        }

        val request =
            AliasRequest(
                anonymousId = anonymousId,
                externalId = trimmed,
                environment = environment,
            )

        val response: AliasResponse =
            httpClient.post(
                endpoint = HTTPClient.Endpoint.ALIAS,
                bodySerializer = AliasRequest.serializer(),
                body = request,
                responseSerializer = AliasResponse.serializer(),
            )

        storage.set(PyrxStorageKey.EXTERNAL_ID, trimmed)

        logger.info {
            "alias completed — path=${response.path} contact=${response.contactId}"
        }
        return IdentityResult.from(response)
    }

    // MARK: - logout

    /**
     * Client-side identity clear. No server call.
     *
     * Preserves ANONYMOUS_ID and DEVICE_TOKEN so the device row stays valid
     * for re-attribution. After logout, subsequent events flow with
     * `external_id = anonymousId` (the same row prior to identify), and the
     * server treats this as a new anonymous session reassigned to the same
     * device.
     *
     * @throws PyrxError.StorageFailure on storage delete failure.
     */
    @Throws(PyrxError::class)
    suspend fun logout() {
        storage.delete(PyrxStorageKey.EXTERNAL_ID)
        logger.info {
            "logout — externalId cleared; anonymousId + deviceToken preserved"
        }
    }
}
