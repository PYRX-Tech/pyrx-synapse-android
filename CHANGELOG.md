# Changelog

All notable changes to the PYRX Synapse Android SDK are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Added
- Full developer documentation set: README, Quickstart, API Reference, Push Setup, Migration, Releasing.
- `docs/RELEASING.md` walkthrough for maintainers cutting future releases.

---

## [0.1.4] - 2026-06-27

### Added
- **`feat(observer):` public observer `SharedFlow` for SDK events** — first public streaming surface on `tech.pyrx.synapse:synapse-{core,push}`. Five event cases shipped in v1 ([Phase 9.2.1](https://github.com/PYRX-Tech/pyrx.synapse/blob/master/docs/plans/phase-9.2.1-native-callback-observers-plan-2026-06-27.md)):
  - `PyrxEvent.PushReceived` — foreground push delivered (publishes from `PushHandlers.recordPushReceived`).
  - `PyrxEvent.PushClicked` — body tap or action-button press (publishes from `PushHandlers.handleNotificationTap` / `handleActionButton`).
  - `PyrxEvent.PushReceivedColdStart` — app launched from a tapped push (publishes from `Pyrx.recordColdStartLaunch` → `recordColdStartAttribution`).
  - `PyrxEvent.QueueDrained(count)` — internal event queue successfully flushed `count` events.
  - `PyrxEvent.IdentityChanged(before, after)` — successful `identify` / `alias` / `logout` transition with `IdentitySnapshot` deltas (per [Q3 user override](https://github.com/PYRX-Tech/pyrx.synapse/blob/master/docs/plans/phase-9.2.1-native-callback-observers-plan-2026-06-27.md#q3-d4-event-taxonomy--four-events-defer-identity-or-five-events-ship-identitychanged-in-v1)).
- **`Pyrx.events: SharedFlow<PyrxEvent>`** — multi-subscriber hot stream, backed by `MutableSharedFlow(replay = 4, extraBufferCapacity = 16, onBufferOverflow = DROP_OLDEST)`. Late subscribers receive the most-recent 4 events for the cold-start race window; every collector sees every event.
- **`Pyrx.publishEvent(event): Boolean`** — cross-module seam used by `synapse-push` and `synapse-core`'s fire-points. Public-but-internal-marker; host apps never call this directly.
- **`PyrxAttributeValue` typealias** — promotes the internal `JSONValue` to a friendlier name on observer payloads so consumers don't reach into `network.*` from event-handling code.
- **`IdentitySnapshot`** — `(externalId: String?, anonymousId: String?, resolvedAt: Instant)` carried by `IdentityChanged.before`/`.after`. Dashboard-style apps use this to refetch user data on login state change without polling.
- **`Pyrx.shouldSuppressClickForColdStart(pushLogId)`** + internal `ColdStartClickDedup` — enforces the non-negotiable invariant from [Risk Register item #1](https://github.com/PYRX-Tech/pyrx.synapse/blob/master/docs/plans/phase-9.2.1-native-callback-observers-plan-2026-06-27.md#7-risk-register): a payload that fires `PushReceivedColdStart` does NOT also fire `PushClicked` within the 5-second dedup window. JUnit-covered both ways.
- **`PushReceivedEvent` / `PushClickedEvent`** — typed payloads with `pushLogId`, `title`/`body`, `deepLink`, `actionId`, `pyrxAttributes`, `userInfo`, `receivedAt`/`clickedAt`. Mirrors iOS `PYRXSynapse 0.1.2` field-for-field.
- **Sample app** — new "Observer" tab (`ObserverScreen.kt`) demonstrating `Pyrx.events.collect { ... }` inside `LaunchedEffect`. Shows received events as a colour-coded LazyColumn.
- **Coverage** — 35 new JUnit/Robolectric tests across `synapse-core` (`ColdStartClickDedupTest`, `PyrxEventsFlowTest`, `IdentityChangedFireTest`, `QueueDrainedFireTest`) and `synapse-push` (`PushHandlersObserverTest`, `ColdStartDedupIntegrationTest`). SharedFlow semantics (replay, multi-collector, `DROP_OLDEST` under burst, cross-thread `publishEvent`), every fire-point, identity transitions, cold-start dedup invariant.
- **Docs** — `docs/observers.md` API reference with lifecycle-scope guidance, late-subscriber semantics, and the cold-start dedup contract.

### Changed
- `PushHandlers.recordPushReceived` / `handleNotificationTap` / `handleActionButton` now also publish observer events. The analytics path (`/v1/push/opened`, `/v1/push/click`, `$push_received` event-queue enqueue) is unchanged — observer publishes wrap the existing telemetry calls so a failing observer subscriber cannot break the SDK's wire-side delivery.
- `EventQueue.drainLoop` publishes `PyrxEvent.QueueDrained` after successful drain passes that flushed at least one event. No-op drains (zero successful flushes) do NOT publish.
- `Pyrx.identify` / `alias` / `logout` capture before/after `IdentitySnapshot` and publish `PyrxEvent.IdentityChanged` after the underlying `IdentityManager` call succeeds.
- `config/detekt/detekt.yml` — `TooManyFunctions.thresholdInObjects` bumped from 35 to 40 for `Pyrx`'s observer plumbing (`publishEvent`, `buildPushReceivedFromData`, `shouldSuppressClickForColdStart`, `currentIdentitySnapshot`, `emitIdentityChanged`). Documented in the config comment.

### Internal
- `tech.pyrx.synapse.observer` — new package on `synapse-core` with `PyrxEvent`, `PushReceivedEvent`, `PushClickedEvent`, `IdentitySnapshot`, `PyrxAttributeValue`, `ColdStartClickDedup`.
- API surface is **purely additive** — every existing public API behaves identically. The 0.1.3 → 0.1.4 bump is a minor (per [Phase 9.2.1 D1 + Q6](https://github.com/PYRX-Tech/pyrx.synapse/blob/master/docs/plans/phase-9.2.1-native-callback-observers-plan-2026-06-27.md#d1-the-observer-surface-is-the-sdks-first-public-streaming-api-its-positioned-as-a-v1-customer-facing-feature-not-internal--for-sdk-wrappers-and-the-native-sdk-changelogs-treat-it-as-a-featobserver-headline)) — `feat(observer):` headline.
- Coordinated release: in flight alongside iOS `PYRXSynapse 0.1.2` and React Native `@pyrx/synapse-react-native 0.2.0` (which adds `usePushReceivedColdStart` and `useIdentityChanged` hooks that ride on this surface). See the Phase 9.2.1 plan for the full sequence.

### Known limitations
- The `PyrxEvent.PushReceived.title` / `.body` are empty strings when the push is data-only (no FCM `notification` payload). Foreground deliveries reach `PushHandlers.recordPushReceived` with `RemoteMessage.data` only; consumers needing the rich notification metadata should subscribe to `FirebaseMessagingService` directly. Cold-start emissions read from intent extras — same caveat.
- The `ColdStartClickDedup` window is fixed at 5 seconds; configurability is a deferred follow-up if a real consumer use case emerges.

---

## [0.1.3] - 2026-06-26

### Added
- **`PyrxConfig.sdkVariant`** — new optional constructor parameter for cross-platform wrapper SDKs (React Native, Flutter, Unity, etc.) to mark their origin in telemetry. When set, the wire-level `sdk_platform` field on `/v1/devices` becomes `"android+<variant>"` (e.g. `"android+rn"`); when omitted (the default), the field remains `"android"`. The `Device.platform` field stays `"android"` regardless — push dispatch routing (APNs vs FCM) is unaffected. Telemetry-only.
- **`DeviceMetadata.sdkPlatform(variant)`** — internal helper used by `PushRegistration` to compose the suffixed value. The bare-arg `DeviceMetadata.sdkPlatform()` is preserved for backward compatibility.
- **`PyrxConfig.normalizedSdkVariant()`** — public helper that returns the trimmed `sdkVariant` with empty/blank collapsed to `null`, so the push module can pass the value to `DeviceMetadata.sdkPlatform(variant)` without re-running the same normalization.

### Changed
- `PushRegistration` constructor accepts an optional `sdkVariant: String?` parameter so the variant can flow from `PyrxConfig` to the wire payload without re-resolving on every call.
- `Pyrx.PyrxPushHooks` carries the normalized `sdkVariant` (default `null` for source-compat with any external consumers); `PyrxPush.install` forwards it to `PushRegistration`.

### Internal
- New test coverage in `PyrxConfigTest` (default-null, pass-through, whitespace trimming, empty-collapses-to-null), `DeviceMetadataTest` (`sdkPlatform(variant)` contract), and `PushRegistrationTest` (wire payload assertions for both variant-set and bare cases).

---

## [0.1.0] - 2026-06-21

Initial public release. Ships the complete Phase 8.4b Android SDK surface, mirroring the iOS SDK PR-for-PR with platform-native idioms.

### Added

- **Foundation** (PR #1 — Phase 8.4b Tasks 8.4b.1–8.4b.3)
  - `Pyrx` Kotlin `object` — thread-safe global singleton with `Mutex`-serialised state mutation.
  - `Pyrx.initialize(context, config)` with idempotent semantics — second call with identical config is a no-op; differing config throws `PyrxError.AlreadyInitialized`.
  - `PyrxConfig` data class with `workspaceId`, `apiKey`, `environment`, `baseUrl`, `logLevel`, `maxQueueSize`. `validate()` rejects empty/wrong-prefix `apiKey`, non-http(s) `baseUrl`, and `maxQueueSize < 1`.
  - `PyrxEnvironment` (`PRODUCTION`, `SANDBOX`) and `LogLevel` (`DEBUG`/`INFO`/`WARNING`/`ERROR`/`NONE`).
  - `PyrxError` sealed error hierarchy: `AlreadyInitialized`, `NotInitialized`, `InvalidConfig`, `StorageFailure`, `Network`.
  - `EncryptedStore` — EncryptedSharedPreferences-backed persistence for anonymous ID / external ID / device token.
  - `PyrxLogger` — `android.util.Log`-backed runtime logger with per-level filtering.
  - `PyrxDebugInfo` snapshot for diagnostics.
  - Gradle scaffold: `synapse-core` AAR (`minSdk 21`, `compileSdk 34`, `targetSdk 34`), JDK 17 toolchain.
  - GitHub Actions CI (`ci.yml`): `./gradlew assembleDebug`, `./gradlew test`, `./gradlew ktlintCheck detekt` on every PR.

- **Network + Identity** (PR #2 — Phase 8.4b Tasks 8.4b.4–8.4b.5)
  - `HTTPClient` — `OkHttp`-backed wrapper with PYRX headers (`X-WORKSPACE-ID`, `X-API-KEY`, `X-PYRX-SDK-PLATFORM`, `X-PYRX-SDK-VERSION`).
  - `HTTPSession` protocol — injectable for tests.
  - `IdentityManager` — `identify`, `alias`, `logout`.
  - `IdentityResult` + `IdentifyPath` — server merge-path readout.
  - All wire models (`IdentifyRequest`, `IdentifyResponse`, `AliasRequest`, `WireEnvironment`, `IdentifyPath`, `DeviceRegisterRequest`, `DeviceResponse`, `EventIngestRequest`, `EventAcceptedResponse`, `PushOpenedRequest`, `PushClickedRequest`, `PushTelemetryResponse`) — wire surface locked against iOS SDK byte-for-byte.

- **Events + Offline Queue** (PR #3 — Phase 8.4b Task 8.4b.6)
  - `Pyrx.track(eventName, properties)` and `Pyrx.screen(screenName, properties)`.
  - `EventQueue` — Room-backed on-disk persistence under the app's private database directory.
  - `QueuedEventDao` + `QueuedEventEntity` + `EventQueueDatabase` — single-table SQLite schema for offline events.
  - Bounded retry with exponential backoff, FIFO eviction at `maxQueueSize` (default 1000), drop-on-4xx.
  - `NetworkCallbackReachability` — `ConnectivityManager.registerDefaultNetworkCallback`-based reactive drain on connectivity restore.
  - `JSONValue` sealed class — strongly-typed payload shape for `traits` and `properties` (`Null` / `Bool` / `Int` / `Num` / `Str` / `Arr` / `Obj`).

- **Push Registration + Delivery Handlers** (PR #4 — Phase 8.4b Tasks 8.4b.7–8.4b.8)
  - `synapse-push` Gradle artifact (`minSdk 23`) with `firebase-messaging` from Firebase BoM 34.15.0.
  - `PyrxMessagingService` — `FirebaseMessagingService` subclass auto-registered via manifest merger.
  - `PyrxPush.install(context)` — public installer; lazy-installs from `PyrxMessagingService.onCreate` as a fallback.
  - `PushBridge` interface in `synapse-core` — cross-module IoC seam so `synapse-core` never imports Firebase types.
  - `SynapsePushBridge` — concrete implementation wiring `PushRegistration` (token → `/v1/devices`) and `PushHandlers` (telemetry).
  - `Pyrx.handleDeviceToken(token)` — registers FCM token with backend via `POST /v1/devices`.
  - `Pyrx.handleNotificationTap(intent)` — parses `pyrx_push_log_id` from intent extras → `POST /v1/push/opened`.
  - `Pyrx.handleActionButton(intent, actionId)` — action-button telemetry → `POST /v1/push/click` with `actionId` as `click_url`.
  - `Pyrx.handleRegistrationError(error)` — diagnostic-only failure log.
  - `$push_received` event auto-fired from `onMessageReceived` for foreground / data-only deliveries.
  - `DeviceMetadata` — collects package name, app version, OS version, device model, locale, timezone for the device registration payload.

- **Attribution + Privacy + Diagnostics** (PR #5 — Phase 8.4b Tasks 8.4b.9–8.4b.11)
  - `Pyrx.recordColdStartLaunch(intent)` — `$app_opened_from_push` cold-start attribution; safe to call before `initialize` (intent is buffered and replayed).
  - `Pyrx.setTrackingEnabled(enabled)` — privacy kill switch with pre-init buffering. Enqueues continue when disabled; drain loop refuses to send until re-enabled.
  - `Pyrx.deleteUser()` — GDPR right-to-erasure cascade (local wipe + `POST /v1/contacts/{external_id}/delete`).
  - `PrivacyManager` — owns the kill switch + delete cascade; surfaces `POST_NOTIFICATIONS` permission state via `notificationPermissionStatus()`.
  - `PyrxNotificationPermission` (`GRANTED` / `DENIED` / `NOT_REQUESTED`) — Android analogue of iOS's ATT status enum.
  - `PyrxDebugInfo` extended with `environment`, `baseUrl`, `deviceTokenFingerprint` (last-8 only — never the full token), `trackingEnabled`, `notificationPermission`, `eventQueueDepth`, `lastDrainAt`.

- **Tests + Sample App** (PR #6 — Phase 8.4b Tasks 8.4b.12–8.4b.13)
  - JaCoCo coverage plugin + Robolectric `androidTest` scaffold; targeted unit-test fills for `IdentityManager`, `EventsManager`, `PrivacyManager`, `PushHandlers`, `PushRegistration`, queue / storage edges.
  - Jetpack Compose sample app at `sample-app/` — demonstrates every public SDK surface end-to-end across `IdentityScreen`, `EventsScreen`, `PushScreen`, `PrivacyScreen`, `DebugInfoScreen` tabs. Mirrors the iOS `SwiftUIDemo` target case-for-case. Reads workspace ID / API key from `SampleApplication.kt` constants (replace before running).
  - `google-services.json.placeholder` for the sample app — copy/rename to `google-services.json` and add your real Firebase project to enable push testing.

### Internal
- `internal`-visibility test seams for `Pyrx.initialize` (storage / session / DAO / reachability overrides) — production paths use real (EncryptedSharedPreferences, OkHttp, Room, ConnectivityManager) implementations; tests inject in-memory mocks.
- `PushBridge` IoC interface declared in `synapse-core` so the Firebase-dependent `synapse-push` module can install itself without creating a circular module dependency.
- Cross-platform `@OptIn(ExperimentalCoroutinesApi::class)` and `runBlocking` bridges for `FirebaseMessagingService` callbacks (which are not suspend-aware).
- Detekt + ktlint with strict rules (`MaximumLineLength`, `LongParameterList=7`, `ReturnCount=4`) — every public file passes without `@Suppress`.

### Known limitations
- Physical-device push delivery verification is deferred to maintainer manual test using the Compose sample app + a real Firebase project. Phase 8.4b exit criterion ("install SDK via Gradle → push from PYRX dashboard lands on Pixel device → opens attribute back correctly") requires hardware QA out of CI scope.
- `PyrxConstants.SDK_VERSION` and the Gradle `version` declaration must be hand-synced on release; automation lands in a future release.
- Sonatype OSSRH credentials + GPG signing key are required before the first Maven Central publish — see [docs/RELEASING.md](docs/RELEASING.md) for the one-time provisioning checklist.

---

[Unreleased]: https://github.com/PYRX-Tech/pyrx-synapse-android/compare/v0.1.4...HEAD
[0.1.4]: https://github.com/PYRX-Tech/pyrx-synapse-android/compare/v0.1.3...v0.1.4
[0.1.3]: https://github.com/PYRX-Tech/pyrx-synapse-android/compare/v0.1.0...v0.1.3
[0.1.0]: https://github.com/PYRX-Tech/pyrx-synapse-android/releases/tag/v0.1.0
