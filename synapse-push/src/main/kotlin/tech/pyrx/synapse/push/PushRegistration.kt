/*
 * PushRegistration.kt
 * PYRXSynapse — Android
 *
 * Bridge from the FCM `onNewToken` callback to `POST /v1/devices` (Phase
 * 8.4b Task 8.4b.7).
 *
 * Callsite shape
 * ==============
 *
 * Apps that use synapse-push don't construct this class directly. The flow:
 *
 *   1. Firebase delivers a new token to [PyrxMessagingService.onNewToken].
 *   2. The service forwards the token to [tech.pyrx.synapse.Pyrx
 *      .handleDeviceToken] (declared in synapse-core).
 *   3. `Pyrx` looks up the installed [tech.pyrx.synapse.PushBridge] —
 *      whose `registerToken` implementation is [SynapsePushBridge
 *      .registerToken], which in turn calls this class.
 *   4. This class:
 *      a. Persists the token to [tech.pyrx.synapse.storage.PyrxStorage]
 *         under [tech.pyrx.synapse.storage.PyrxStorageKey.DEVICE_TOKEN].
 *      b. Snapshots device metadata via [DeviceMetadata].
 *      c. POSTs the full payload to `/v1/devices` via the SDK's
 *         [tech.pyrx.synapse.network.HTTPClient].
 *      d. Returns the server's [tech.pyrx.synapse.network.DeviceResponse]
 *         so debug menus can surface the device id.
 *
 * Token de-duplication
 * --------------------
 * We POST every time `register` is invoked, even if the token is unchanged.
 * The server upserts by `(tenant_id, environment, platform, push_token)`
 * so a duplicate POST is idempotent. We rely on that rather than gating
 * client-side — if the user uninstalls + reinstalls, the EncryptedStore
 * may still carry the OLD token while the OS issues a NEW one, and we
 * want the SDK to re-register unconditionally.
 *
 * Concurrency
 * -----------
 * `register` is `suspend`. The class itself is stateless beyond its
 * injected dependencies — safe to share across threads.
 *
 * Mirrors iOS `PushRegistration.swift` semantics, with the platform-
 * appropriate substitutions:
 *   - APNs `Data` token → FCM `String` token (no hex conversion needed —
 *     FCM tokens are already opaque base64-ish strings).
 *   - `Bundle.main.bundleIdentifier` → `Context.packageName`.
 *   - `CFBundleShortVersionString` → `PackageInfo.versionName`.
 */

package tech.pyrx.synapse.push

import android.content.Context
import android.util.Log
import tech.pyrx.synapse.PyrxError
import tech.pyrx.synapse.network.DeviceRegisterRequest
import tech.pyrx.synapse.network.DeviceResponse
import tech.pyrx.synapse.network.HTTPClient
import tech.pyrx.synapse.network.WireEnvironment
import tech.pyrx.synapse.storage.PyrxStorage
import tech.pyrx.synapse.storage.PyrxStorageKey

/**
 * Public — instantiable by host apps that want to drive registration
 * outside the `PyrxMessagingService` flow (e.g. a debug menu's "force
 * re-register" button). Most apps go through `Pyrx.handleDeviceToken`.
 *
 * @param context Application context — used by [DeviceMetadata] for
 *                package name / version. Stored as `applicationContext` so
 *                an Activity is never retained.
 * @param storage Encrypted K/V store — persists the FCM token under
 *                [PyrxStorageKey.DEVICE_TOKEN] so diagnostics can verify
 *                registration state without a network call.
 * @param httpClient The wire-level HTTP client (constructed by
 *                   [tech.pyrx.synapse.Pyrx] alongside this class).
 * @param environment Wire-side environment discriminator. Translated from
 *                    `PyrxConfig.environment` at construction time so this
 *                    class doesn't re-translate on every call.
 */
public class PushRegistration(
    private val context: Context,
    private val storage: PyrxStorage,
    private val httpClient: HTTPClient,
    private val environment: WireEnvironment,
) {
    // MARK: - Token registration

    /**
     * Persist the FCM [token] and POST `/v1/devices` with the full metadata
     * snapshot.
     *
     * @param token The opaque FCM token from `FirebaseMessaging.getInstance
     *              ().token.await()` or `onNewToken(token)`.
     * @param externalId The active contact identity — externalId if set by
     *                   [tech.pyrx.synapse.Pyrx.identify], otherwise the
     *                   SDK's anonymousId. Caller (PushBridge) does the
     *                   resolution; this class trusts what it's given.
     * @return The server's [DeviceResponse] so debug menus can surface the
     *         device id + contact id.
     * @throws PyrxError.InvalidConfig if [externalId] or [token] is blank.
     * @throws PyrxError.Network on transport / HTTP / decode failure.
     * @throws PyrxError.StorageFailure on persist failure.
     */
    @Throws(PyrxError::class)
    public suspend fun register(
        token: String,
        externalId: String,
    ): DeviceResponse {
        val trimmedExternal = externalId.trim()
        if (trimmedExternal.isEmpty()) {
            throw PyrxError.InvalidConfig("externalId must not be empty")
        }
        val trimmedToken = token.trim()
        if (trimmedToken.isEmpty()) {
            throw PyrxError.InvalidConfig("token must not be empty")
        }

        Log.d(
            TAG,
            "handleDeviceToken: token=${fingerprint(trimmedToken)} (len=${trimmedToken.length})",
        )

        // Persist BEFORE the network call so a transient failure still
        // leaves the SDK's local state pointing at the most recent token.
        // Diagnostic surfaces (`debugInfo`) then accurately report
        // `hasDeviceToken=true`.
        storage.set(PyrxStorageKey.DEVICE_TOKEN, trimmedToken)

        val request =
            DeviceRegisterRequest(
                externalId = trimmedExternal,
                platform = ANDROID_PLATFORM,
                pushToken = trimmedToken,
                bundleId = DeviceMetadata.bundleId(context),
                appVersion = DeviceMetadata.appVersion(context),
                sdkVersion = DeviceMetadata.sdkVersion(),
                sdkPlatform = DeviceMetadata.sdkPlatform(),
                osVersion = DeviceMetadata.osVersion(),
                deviceModel = DeviceMetadata.deviceModel(),
                locale = DeviceMetadata.locale(),
                timezone = DeviceMetadata.timezone(),
                environment = environment,
                pushEnabled = true,
                metadata = emptyMap(),
            )

        val response: DeviceResponse =
            httpClient.post(
                endpoint = HTTPClient.Endpoint.DEVICES_REGISTER,
                bodySerializer = DeviceRegisterRequest.serializer(),
                body = request,
                responseSerializer = DeviceResponse.serializer(),
            )

        Log.i(
            TAG,
            "handleDeviceToken: registered device=${response.id} contact=${response.contactId}",
        )
        return response
    }

    // MARK: - Helpers

    /**
     * Diagnostic-only short form of a token: last 8 chars with a leading
     * horizontal-ellipsis (matching the dashboard's `push_token_fingerprint`).
     * Never written to disk; only used in logs.
     */
    private fun fingerprint(token: String): String =
        if (token.length >= FINGERPRINT_TAIL_LENGTH) {
            "${'…'}${token.takeLast(FINGERPRINT_TAIL_LENGTH)}"
        } else {
            "${'…'}$token"
        }

    private companion object {
        /** Wire-side `platform` discriminator for the Android SDK. */
        private const val ANDROID_PLATFORM: String = "android"

        /** Number of trailing chars surfaced in token fingerprint logs. */
        private const val FINGERPRINT_TAIL_LENGTH: Int = 8

        /** Logcat tag — matches `PyrxLogger.TAG` for visual grep'ability. */
        private const val TAG: String = "PYRXSynapse"
    }
}
