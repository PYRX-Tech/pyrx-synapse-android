# Changelog

All notable changes to the PYRX Synapse Android SDK are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Added
- Full developer documentation set: README, Quickstart, API Reference, Push Setup, Migration, Releasing.
- `docs/RELEASING.md` walkthrough for maintainers cutting future releases.

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

[Unreleased]: https://github.com/PYRX-Tech/pyrx-synapse-android/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/PYRX-Tech/pyrx-synapse-android/releases/tag/v0.1.0
