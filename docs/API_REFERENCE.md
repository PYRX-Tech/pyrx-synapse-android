# API Reference

Complete public surface of the PYRX Synapse Android SDK as of v1.0.0.

The SDK exposes a single entry point — `Pyrx` — implemented as a Kotlin `object` (singleton). State-mutating methods serialise through an internal `Mutex` so it is safe to call from any coroutine.

---

## Table of Contents

- [`Pyrx` (singleton)](#pyrx-singleton)
  - [`initialize(context, config)`](#initializecontext-config)
  - [`identify(externalId, traits)`](#identifyexternalid-traits)
  - [`alias(newExternalId)`](#aliasnewexternalid)
  - [`logout()`](#logout)
  - [`track(eventName, properties)`](#trackeventname-properties)
  - [`screen(screenName, properties)`](#screenscreenname-properties)
  - [`handleDeviceToken(token)`](#handledevicetokentoken)
  - [`handleNotificationTap(intent)`](#handlenotificationtapintent)
  - [`handleActionButton(intent, actionId)`](#handleactionbuttonintent-actionid)
  - [`handleRegistrationError(error)`](#handleregistrationerrorerror)
  - [`recordColdStartLaunch(intent)`](#recordcoldstartlaunchintent)
  - [`setTrackingEnabled(enabled)`](#settrackingenabledenabled)
  - [`deleteUser()`](#deleteuser)
  - [`setLogLevel(level)`](#setlogleveltype)
  - [`debugInfo()`](#debuginfo)
  - [`installPushBridge(bridge)`](#installpushbridgebridge)
  - [`pushHooks()`](#pushhooks)
- [`PyrxConfig`](#pyrxconfig)
- [`PyrxEnvironment`](#pyrxenvironment)
- [`LogLevel`](#loglevel)
- [`PyrxDebugInfo`](#pyrxdebuginfo)
- [`PyrxNotificationPermission`](#pyrxnotificationpermission)
- [`JSONValue`](#jsonvalue)
- [`PushBridge`](#pushbridge)
- [`PyrxError`](#pyrxerror)
- [`PyrxNetworkError`](#pyrxnetworkerror)
- [`IdentityResult`](#identityresult)
- [`PyrxPush` (synapse-push)](#pyrxpush-synapse-push)
- [`PyrxMessagingService` (synapse-push)](#pyrxmessagingservice-synapse-push)

---

## `Pyrx` (singleton)

```kotlin
public object Pyrx
```

The shared SDK singleton. Apps always reach `Pyrx.initialize(...)`, `Pyrx.track(...)`, etc. directly. No instance management.

---

### `initialize(context, config)`

```kotlin
@Throws(PyrxError::class)
public suspend fun initialize(
    context: Context,
    config: PyrxConfig,
)
```

Initialise the SDK. Must be called exactly once per process before any other API.

`applicationContext` is taken internally, so passing an Activity does not leak it.

**Parameters**
- `context` — any `Context`.
- `config` — validated `PyrxConfig` (see below).

**Throws**
- `PyrxError.AlreadyInitialized` if called twice with different config values.
- `PyrxError.InvalidConfig(reason)` if validation fails.
- `PyrxError.StorageFailure(operation, underlying)` if EncryptedSharedPreferences cannot be opened or written to.

**Example**

```kotlin
Pyrx.initialize(
    context = applicationContext,
    config = PyrxConfig(
        workspaceId = UUID.fromString("00000000-0000-0000-0000-000000000000"),
        apiKey = "psk_live_...",
    ),
)
```

---

### `identify(externalId, traits)`

```kotlin
@Throws(PyrxError::class)
public suspend fun identify(
    externalId: String,
    traits: Map<String, JSONValue>? = null,
): IdentityResult
```

Identify an anonymous SDK session as a known user. Triggers server-side merge of the anonymous contact's events and devices into the known contact.

**Parameters**
- `externalId` — your canonical user identifier (e.g. your DB user ID).
- `traits` — optional contact attributes, shallow-merged into the contact's properties server-side.

**Returns** — `IdentityResult` with the merge path the server took.

**Throws** — `PyrxError.NotInitialized`, `PyrxError.InvalidConfig` (blank externalId), `PyrxError.Network(...)`, `PyrxError.StorageFailure(...)`.

**Example**

```kotlin
val result = Pyrx.identify(
    externalId = "user_123",
    traits = mapOf("email" to JSONValue.Str("jane@example.com")),
)
Log.i("PYRX", "Server merge path: ${result.path}")
```

---

### `alias(newExternalId)`

```kotlin
@Throws(PyrxError::class)
public suspend fun alias(newExternalId: String): IdentityResult
```

Explicitly merge an anonymous session into a known contact under a different identifier.

Use when your backend mints a permanent user ID distinct from the device-local identifier you used for the anonymous session.

**Throws** — `PyrxError.NotInitialized`, `PyrxError.InvalidConfig`, `PyrxError.Network(...)`, `PyrxError.StorageFailure(...)`.

---

### `logout()`

```kotlin
@Throws(PyrxError::class)
public suspend fun logout()
```

Client-side identity clear. Does NOT call the server.

- Removes `EXTERNAL_ID` from EncryptedSharedPreferences.
- Preserves `ANONYMOUS_ID` so subsequent events flow as anonymous activity.
- Preserves `DEVICE_TOKEN` so the device row remains valid for re-attribution at the next `identify`.

**Throws** — `PyrxError.NotInitialized`, `PyrxError.StorageFailure(...)`.

---

### `track(eventName, properties)`

```kotlin
@Throws(PyrxError::class)
public suspend fun track(
    eventName: String,
    properties: Map<String, JSONValue>? = null,
)
```

Track a custom event. The event is persisted to the Room-backed offline queue and drained asynchronously with exponential-backoff retry. 5xx and transport errors trigger retry; 4xx responses cause the event to be dropped with a warning log.

Returns once the event is durably in the queue — actual network delivery happens in the background.

**Throws** — `PyrxError.NotInitialized`, `PyrxError.InvalidConfig` (blank event name), `PyrxError.StorageFailure(...)`.

**Example**

```kotlin
Pyrx.track(
    eventName = "order_placed",
    properties = mapOf(
        "order_id" to JSONValue.Str("ord_abc123"),
        "total" to JSONValue.Num(49.99),
        "currency" to JSONValue.Str("USD"),
    ),
)
```

---

### `screen(screenName, properties)`

```kotlin
@Throws(PyrxError::class)
public suspend fun screen(
    screenName: String,
    properties: Map<String, JSONValue>? = null,
)
```

Track a screen view. Wire shape: `event_name = "$screen"`, `attributes.screen_name = screenName`. Caller `properties` are merged into the attributes bag (SDK-stamped `screen_name` wins on conflict).

Same queue + retry semantics as `track`.

---

### `handleDeviceToken(token)`

```kotlin
@Throws(PyrxError::class)
public suspend fun handleDeviceToken(token: String): DeviceResponse?
```

Register an FCM device token with the Synapse backend.

Persists `token` to EncryptedSharedPreferences and POSTs `/v1/devices` with a full identifying metadata snapshot (package name, app version, OS version, device model, locale, timezone, SDK fields). The server upserts by `(tenant_id, environment, platform, push_token)` so duplicate calls are idempotent.

`external_id` resolution mirrors `track` / `screen`: uses the externalId set by `identify()` if present, otherwise the SDK anonymousId.

**Returns** — `DeviceResponse` (id, contact id, etc.) — or `null` if the SDK has no push bridge installed (host app does not depend on `synapse-push`).

**Throws** — `PyrxError.NotInitialized`, `PyrxError.InvalidConfig` (blank token), `PyrxError.Network(...)`, `PyrxError.StorageFailure(...)`.

Typical call site is `PyrxMessagingService.onNewToken` (which the `synapse-push` module declares automatically). Host apps can also call this directly after fetching the token via `FirebaseMessaging.getInstance().token`.

---

### `handleNotificationTap(intent)`

```kotlin
@Throws(PyrxError::class)
public suspend fun handleNotificationTap(intent: Intent)
```

Bridge a launcher Activity's `Intent` (received via `getIntent()` or `onNewIntent(...)` when the user taps a notification from the system tray) into a `POST /v1/push/opened` telemetry call.

Android delivers FCM data-payload key/values as Intent string extras when a notification is tapped. The Synapse push payload always carries a `pyrx_push_log_id` extra; if the intent doesn't have one, this is a no-op (the user tapped a non-Synapse push or a regular Activity launch).

Recommended call site: the host app's launcher Activity's `onCreate(savedInstanceState)` and `onNewIntent(intent)`. The SDK is deliberately lenient about being called on intents that have no Synapse payload — it costs essentially nothing to check.

**Throws** — `PyrxError.NotInitialized`.

---

### `handleActionButton(intent, actionId)`

```kotlin
@Throws(PyrxError::class)
public suspend fun handleActionButton(
    intent: Intent,
    actionId: String,
)
```

Fire `POST /v1/push/click` for a custom-action button press.

Android does not have a built-in concept of "notification action identifier"; action buttons are usually wired via `PendingIntent`s that include a string extra identifying the action. The host app pulls that out and passes it via `actionId`.

**Parameters**
- `intent` — the intent that fired the action (carries `pyrx_push_log_id`).
- `actionId` — the action discriminator — stored on the wire as `click_url` per push SDK plan §6.5.

**Throws** — `PyrxError.NotInitialized`.

---

### `handleRegistrationError(error)`

```kotlin
public fun handleRegistrationError(error: Throwable)
```

Log a registration failure (e.g. FCM token fetch threw). Fire-and-forget — no retry, no network call. Safe to call before `initialize` — surfaces as a logger warning that the bridge isn't installed yet.

To retry, fix the underlying issue (missing `google-services.json`, FCM throttling) and call `FirebaseMessaging.getInstance().token` again.

---

### `recordColdStartLaunch(intent)`

```kotlin
public suspend fun recordColdStartLaunch(intent: Intent?)
```

Bridge a launcher Activity's `Intent` (received via `getIntent()` or `onNewIntent(...)` when the user opens the app from a push tap) into an `$app_opened_from_push` attribution event.

The event is enqueued through the events queue (offline-durable, retried automatically). Attributes include the campaign-emitter `pyrx_attrs_*` keys, the canonical `push_log_id`, and (if present) the deep-link URI so downstream analytics can re-derive the click target.

No-op if `intent` does not carry a Synapse `pyrx_push_log_id` extra — legacy / cross-vendor pushes or a plain Activity launch pass through silently (so analytics doesn't over-count app opens).

Safe to call BEFORE `initialize(context, config)` — the intent is buffered and replayed once the SDK initialises.

Pass `null` to no-op (convenient for callers that don't want to null-check upstream).

**Example**

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    lifecycleScope.launch {
        Pyrx.recordColdStartLaunch(intent)
    }
}
```

---

### `setTrackingEnabled(enabled)`

```kotlin
public suspend fun setTrackingEnabled(enabled: Boolean)
```

Toggle the SDK's tracking kill switch.

When `enabled == false`:
- `track`, `screen`, and push handlers still ENQUEUE to the on-disk queue (so flipping back doesn't lose intent captured during opt-out).
- The drain loop refuses to send anything until tracking is re-enabled. The next `setTrackingEnabled(true)` auto-drains.

The flag is NOT persisted across launches. Apps that want a sticky opt-out should restore the value from their own preferences store on launch.

Safe to call BEFORE `initialize(context, config)` — the value is buffered and applied when the privacy manager comes online.

---

### `deleteUser()`

```kotlin
@Throws(PyrxError::class)
public suspend fun deleteUser()
```

GDPR right-to-erasure cascade. Order:

1. Wipe EncryptedSharedPreferences (anonymousId + externalId + deviceToken).
2. Wipe the on-disk event queue.
3. POST `/v1/contacts/{external_id}/delete` to ask the backend to cascade — IF an identifier was present. Anonymous-only sessions skip step 3.

**Local wipe happens BEFORE the backend call** — if the backend fails, on-device data is still gone. A thrown error means "couldn't reach the server, please retry that part", not "the wipe didn't happen". 4xx responses are swallowed (treated as "already deleted").

**Throws** — `PyrxError.NotInitialized`, `PyrxError.Network(...)` for 5xx / transport failure on the backend cascade.

---

### `setLogLevel(level)`

```kotlin
public fun setLogLevel(level: LogLevel)
```

Adjust the runtime log level. Safe before or after `initialize`. Logs route through `android.util.Log` with tag `PYRXSynapse`.

---

### `debugInfo()`

```kotlin
public suspend fun debugInfo(): PyrxDebugInfo
```

Snapshot of SDK state. Useful for debug menus and support bundles.

The device-token fingerprint is `…<last 8 chars>` — never the full token.

**Returns** — `PyrxDebugInfo` (see below).

---

### `installPushBridge(bridge)`

```kotlin
public fun installPushBridge(bridge: PushBridge)
```

Install the synapse-push module's `PushBridge` implementation onto the core SDK.

Public-but-bridge-only — host apps NEVER call this directly. Marked `public` because `synapse-push` lives in a separate Gradle module and needs cross-module visibility. The synapse-push module's `PyrxPush.install(context)` is what host apps call.

Idempotent — re-installing the same bridge logs at info and is a no-op; re-installing a DIFFERENT bridge logs a warning and replaces the previous one.

---

### `pushHooks()`

```kotlin
public fun pushHooks(): PyrxPushHooks?
```

Return the hooks `synapse-push` needs to construct its bridge. Returns `null` if `initialize(context, config)` has not completed.

Public for cross-module access; not part of the host-app contract. Host apps should never call this directly.

---

## `PyrxConfig`

```kotlin
public data class PyrxConfig(
    val workspaceId: UUID,
    val apiKey: String,
    val environment: PyrxEnvironment = PyrxEnvironment.PRODUCTION,
    val baseUrl: String = DEFAULT_BASE_URL,
    val logLevel: LogLevel = LogLevel.INFO,
    val maxQueueSize: Int = DEFAULT_MAX_QUEUE_SIZE,
) {
    public fun validate()

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://synapse-events.pyrx.tech"
        public const val DEFAULT_MAX_QUEUE_SIZE: Int = 1000
    }
}
```

| Field | Notes |
|-------|-------|
| `workspaceId` | UUID v4 from `synapse-app.pyrx.tech/settings/workspace`. |
| `apiKey` | `psk_live_…` / `psk_test_…`. Must start with `psk_`. Use the `data` scope. |
| `environment` | `PRODUCTION` (live traffic) or `SANDBOX` (staging / QA). Defaults to `PRODUCTION`. |
| `baseUrl` | Ingestion API root. Defaults to `https://synapse-events.pyrx.tech`. Override only for self-hosted deployments. |
| `logLevel` | `DEBUG` / `INFO` / `WARNING` / `ERROR` / `NONE`. Defaults to `INFO`. |
| `maxQueueSize` | Offline queue cap. FIFO eviction once exceeded. Defaults to 1000. Values < 1 throw `PyrxError.InvalidConfig` at validation time. |

`validate()` throws `PyrxError.InvalidConfig(reason)` for:
- empty `apiKey` after trim
- `apiKey` not starting with `psk_`
- `baseUrl` not using http(s) scheme
- `maxQueueSize < 1`

---

## `PyrxEnvironment`

```kotlin
public enum class PyrxEnvironment {
    PRODUCTION,  // → wire environment "live"
    SANDBOX,     // → wire environment "test"
}
```

---

## `LogLevel`

```kotlin
public enum class LogLevel(public val priority: Int) {
    DEBUG(0),
    INFO(1),
    WARNING(2),
    ERROR(3),
    NONE(4),
}
```

---

## `PyrxDebugInfo`

```kotlin
public data class PyrxDebugInfo(
    val sdkVersion: String,
    val platform: String,
    val initialized: Boolean,
    val workspaceId: UUID?,
    val environment: String?,
    val baseUrl: String?,
    val logLevel: LogLevel,
    val anonymousId: String?,
    val hasExternalId: Boolean,
    val hasDeviceToken: Boolean,
    val deviceTokenFingerprint: String?,
    val trackingEnabled: Boolean,
    val notificationPermission: PyrxNotificationPermission,
    val eventQueueDepth: Int,
    val lastDrainAt: Long?,
) {
    public companion object {
        @JvmStatic
        public fun fingerprint(forDeviceToken: String?): String?
    }
}
```

`fingerprint(forDeviceToken)` returns `null` for an empty/missing token, otherwise `…<last 8 chars>`.

`lastDrainAt` is wall-clock millis-since-epoch of the most recent drain attempt (any outcome). `null` until the queue has at least attempted to flush once. Wrap with `java.util.Date(lastDrainAt!!)` or `java.time.Instant.ofEpochMilli(lastDrainAt!!)` if you want a typed timestamp.

---

## `PyrxNotificationPermission`

```kotlin
public enum class PyrxNotificationPermission {
    GRANTED,        // Granted explicitly (Android 13+) or implicitly (pre-13).
    DENIED,         // Android 13+ with the user having declined the prompt.
    NOT_REQUESTED,  // Runtime prompt never shown (Android 13+), or context missing.
}
```

The SDK never auto-requests `POST_NOTIFICATIONS`. Host apps own the permission UX.

---

## `JSONValue`

```kotlin
public sealed class JSONValue {
    public object Null : JSONValue()
    public data class Bool(val value: Boolean) : JSONValue()
    public data class Int(val value: Long) : JSONValue()
    public data class Num(val value: Double) : JSONValue()
    public data class Str(val value: String) : JSONValue()
    public data class Arr(val value: List<JSONValue>) : JSONValue()
    public data class Obj(val value: Map<String, JSONValue>) : JSONValue()
}
```

Strongly-typed value used for `traits` and event `properties` payloads. Use the variant that matches your value:

```kotlin
val props: Map<String, JSONValue> = mapOf(
    "email" to JSONValue.Str("jane@example.com"),
    "age" to JSONValue.Int(34),
    "balance" to JSONValue.Num(123.45),
    "premium" to JSONValue.Bool(true),
    "tags" to JSONValue.Arr(listOf(JSONValue.Str("vip"), JSONValue.Str("beta"))),
    "address" to JSONValue.Obj(mapOf("city" to JSONValue.Str("HCMC"))),
    "deleted_at" to JSONValue.Null,
)
```

> Why not `Any?` for ergonomics? `Any?` does not survive `kotlinx.serialization` without per-call-site polymorphic machinery. The verbosity at call sites is the price of one-line `@Serializable` data classes everywhere else on the wire.

---

## `PushBridge`

```kotlin
public interface PushBridge {
    @Throws(PyrxError::class)
    public suspend fun registerToken(
        token: String,
        externalId: String,
    ): DeviceResponse

    public suspend fun handleNotificationTap(intent: Intent)

    public suspend fun handleActionButton(
        intent: Intent,
        actionId: String,
    )

    public fun registrationFailed(error: Throwable)
}
```

Cross-module seam between `synapse-core` (Firebase-free) and `synapse-push` (Firebase-dependent). `synapse-core` declares the interface so `Pyrx` can hold a reference without ever importing Firebase types.

`synapse-push`'s `PyrxPush.install(context)` constructs a `SynapsePushBridge` and installs it via `Pyrx.installPushBridge(bridge)`. Host apps never implement or install this interface themselves.

If no bridge is installed, `Pyrx.handleDeviceToken` / `handleNotificationTap` / `handleActionButton` log a warning and no-op rather than throwing.

---

## `PyrxError`

```kotlin
public sealed class PyrxError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    public object AlreadyInitialized : PyrxError(...)
    public object NotInitialized : PyrxError(...)
    public data class InvalidConfig(val reason: String) : PyrxError(...)
    public data class StorageFailure(val operation: String, val underlying: Throwable? = null) : PyrxError(...)
    public data class Network(val inner: PyrxNetworkError) : PyrxError(...)
}
```

All errors thrown by the public SDK API. Sealed, so callers can exhaustively `when`-match every failure mode at compile time.

| Variant | When |
|---|---|
| `AlreadyInitialized` | `initialize` called twice with different config. |
| `NotInitialized` | Public API called before `initialize` completed. |
| `InvalidConfig(reason)` | Config validation failed (empty apiKey, wrong prefix, bad baseUrl scheme, invalid maxQueueSize). |
| `StorageFailure(operation, underlying)` | EncryptedSharedPreferences read/write failed (Keystore unavailable, IO error). |
| `Network(inner)` | Network call failed — see `PyrxNetworkError` for the discriminated cause. |

---

## `PyrxNetworkError`

```kotlin
public sealed class PyrxNetworkError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    public data class Transport(val underlying: Throwable) : PyrxNetworkError(...)
    public object InvalidResponse : PyrxNetworkError(...)
    public data class HttpStatus(val statusCode: Int, val body: ByteArray) : PyrxNetworkError(...)
    public data class Decode(val underlying: Throwable) : PyrxNetworkError(...)
}
```

Pattern-match for retry decisions:

```kotlin
try {
    Pyrx.identify(externalId = "user_123")
} catch (e: PyrxError.Network) {
    when (val inner = e.inner) {
        is PyrxNetworkError.HttpStatus ->
            if (inner.statusCode in 500..599) retryLater() else giveUp()
        is PyrxNetworkError.Transport -> retryLater()
        PyrxNetworkError.InvalidResponse -> giveUp()
        is PyrxNetworkError.Decode -> giveUp()
    }
}
```

---

## `IdentityResult`

```kotlin
public data class IdentityResult(
    val contactId: String,
    val path: IdentifyPath,                     // KNOWN_EXISTS, FIRST_SIGHTING, NO_ANONYMOUS
    val aliasedExternalId: String?,
    val eventsReattributed: Int,
    val devicesReattributed: Int,
    val anonymousContactTombstoned: Boolean,
)
```

Returned by `identify` and `alias`. Useful for support investigations — log `path` to see which merge branch the server ran.

`IdentifyPath`:

```kotlin
public enum class IdentifyPath {
    KNOWN_EXISTS,     // Known contact already existed; anonymous merged into it.
    FIRST_SIGHTING,   // First time this externalId was seen; anonymous promoted.
    NO_ANONYMOUS,     // No anonymous to merge (identify-on-known-session).
}
```

---

## `PyrxPush` (synapse-push)

```kotlin
public object PyrxPush {
    public fun install(context: Context): Boolean
    public fun installedBridge(): SynapsePushBridge?
}
```

Public installer entry point for the `synapse-push` module. Host apps call `PyrxPush.install(context)` from `Application.onCreate` after `Pyrx.initialize` completes:

```kotlin
override fun onCreate() {
    super.onCreate()
    MainScope().launch {
        Pyrx.initialize(this@MyApp, config)
        PyrxPush.install(this@MyApp)
    }
}
```

`install(context)` returns `true` if the bridge was installed (or already installed), `false` if `Pyrx.initialize` hasn't completed yet.

If the host app skips `install`, the bridge installs itself lazily the first time `PyrxMessagingService.onCreate()` runs (which happens before FCM hands the service its first message or token). Explicit `install` is the recommended path because it surfaces config issues at app start.

Idempotent — re-installing with the same hooks is a no-op.

---

## `PyrxMessagingService` (synapse-push)

```kotlin
public open class PyrxMessagingService : FirebaseMessagingService() {
    override fun onCreate()
    override fun onNewToken(token: String)
    override fun onMessageReceived(message: RemoteMessage)
}
```

`FirebaseMessagingService` subclass — the SDK's primary entry point for Firebase Cloud Messaging callbacks.

Declared in `synapse-push`'s `AndroidManifest.xml`; manifest merger picks it up automatically when a host app declares `implementation("tech.pyrx.synapse:synapse-push:<version>")`. No host-app work required.

Host apps that want custom behaviour (notification styling, multi-SDK routing) can subclass and re-declare it in their own manifest using `tools:replace="android:name"`. Subclasses MUST call `super.onCreate()`, `super.onNewToken(token)`, and `super.onMessageReceived(message)` so the SDK's hooks still fire.

Callback responsibilities:
- `onCreate()` — lazy-install `PyrxPush.install` in case the host app didn't call it from `Application.onCreate`.
- `onNewToken(token)` — forward to `Pyrx.handleDeviceToken(token)` → `POST /v1/devices`.
- `onMessageReceived(rm)` — fire `$push_received` via the events queue.
