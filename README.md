# PYRX Synapse — Android SDK

[![CI](https://github.com/PYRX-Tech/pyrx-synapse-android/actions/workflows/ci.yml/badge.svg)](https://github.com/PYRX-Tech/pyrx-synapse-android/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/tech.pyrx.synapse/synapse-core.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:tech.pyrx.synapse)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![API](https://img.shields.io/badge/API-21%2B-brightgreen.svg)](https://developer.android.com/about/versions/lollipop)
[![Kotlin](https://img.shields.io/badge/kotlin-1.9.22-blueviolet.svg)](https://kotlinlang.org)

Native Android SDK for the [PYRX Synapse](https://synapse.pyrx.tech) customer engagement platform.

Track events, identify users, register for FCM push, and respect user privacy — all from a single thread-safe `object` API designed for Jetpack Compose, View-based UI, and any Kotlin/Java host app on Android 5.0 (API 21) and newer.

> **What's new in 0.1.4 (Phase 9.2.1):** first public **observer surface** on the SDK — `Pyrx.events: SharedFlow<PyrxEvent>`. Subscribe in `lifecycleScope` and observe push deliveries, taps, cold-start launches, queue drains, and identity transitions in real time. **5 events** in v1: `PushReceived`, `PushClicked`, `PushReceivedColdStart`, `QueueDrained`, `IdentityChanged(before, after)`. Multi-subscriber, replay buffer of 4 for the cold-start race. Cold-start vs. click dedup is JUnit-covered. See [docs/OBSERVERS.md](docs/OBSERVERS.md).

## Install

`pyrx-synapse-android` ships as two Gradle artifacts so you only depend on what you use:

| Module | What it gives you | Min SDK |
|---|---|---|
| `synapse-core` | `Pyrx` singleton, identity, events, encrypted storage, offline queue, privacy controls, diagnostics | API 21 |
| `synapse-push` | `PyrxMessagingService` (FCM subclass), token registration, notification-tap + action-button telemetry | API 23 |

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("tech.pyrx.synapse:synapse-core:1.0.0")
    implementation("tech.pyrx.synapse:synapse-push:1.0.0") // optional — only if you use push
}
```

Make sure Maven Central is in your repository list:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}
```

## Quick Start

Initialise the SDK from `Application.onCreate`:

```kotlin
import android.app.Application
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import tech.pyrx.synapse.Pyrx
import tech.pyrx.synapse.PyrxConfig
import tech.pyrx.synapse.PyrxEnvironment
import tech.pyrx.synapse.push.PyrxPush
import java.util.UUID

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MainScope().launch {
            Pyrx.initialize(
                context = this@MyApp,
                config = PyrxConfig(
                    workspaceId = UUID.fromString("<YOUR_WORKSPACE_UUID>"),
                    apiKey = "psk_live_<YOUR_API_KEY>",
                    environment = PyrxEnvironment.PRODUCTION,
                ),
            )
            // Optional — only if you depend on synapse-push.
            PyrxPush.install(this@MyApp)

            Pyrx.identify(externalId = "user_123")
            Pyrx.track(eventName = "app_opened")
        }
    }
}
```

The FCM device token is delivered through `PyrxMessagingService.onNewToken`, which the synapse-push manifest registers automatically. See [docs/QUICKSTART.md](docs/QUICKSTART.md) for the full setup path and [docs/PUSH_SETUP.md](docs/PUSH_SETUP.md) for end-to-end push provisioning.

## Features

- **Identity** — `identify`, `alias`, `logout` with anonymous-to-known merge, server-side event/device re-attribution, EncryptedSharedPreferences-backed identifier persistence.
- **Events** — `track` and `screen` with a Room-backed on-disk offline queue, exponential-backoff retry on 5xx/transport failures, FIFO eviction at the configured cap (1000 default), drop-on-4xx semantics, drain on connectivity restore.
- **Push notifications** — `PyrxMessagingService` (FirebaseMessagingService subclass) auto-registered via manifest merger; FCM token registration to `POST /v1/devices`; foreground + background delivery; notification-tap → `POST /v1/push/opened`; custom action button → `POST /v1/push/click`; cold-start attribution via `recordColdStartLaunch(intent)`.
- **Privacy controls** — tracking kill switch (`setTrackingEnabled`), GDPR cascade delete (`deleteUser`), Android 13+ `POST_NOTIFICATIONS` permission readout.
- **Diagnostics** — `debugInfo()` snapshot with SDK version, queue depth, last drain timestamp, device-token fingerprint (last 8 chars only — never the full token), and configuration echo for support cases.
- **Thread safety** — `Pyrx` is a Kotlin `object` (singleton). State-mutating methods serialise through an internal `Mutex`. Call from any coroutine.
- **Cross-platform parity** — wire surface mirrors the [iOS SDK](https://github.com/PYRX-Tech/pyrx-synapse-ios) field-for-field. Same config shape, same error hierarchy, same merge semantics.

## Documentation

| Guide | Purpose |
|-------|---------|
| [docs/QUICKSTART.md](docs/QUICKSTART.md) | Five-minute setup walkthrough — Gradle → SDK → identify → track → push |
| [docs/API_REFERENCE.md](docs/API_REFERENCE.md) | Every public type and method, with usage examples |
| [docs/PUSH_SETUP.md](docs/PUSH_SETUP.md) | Firebase project → `google-services.json` → FCM service account → PYRX dashboard |
| [docs/MIGRATION.md](docs/MIGRATION.md) | Migration notes between SDK versions |
| [docs/RELEASING.md](docs/RELEASING.md) | Release process for SDK maintainers |
| [CHANGELOG.md](CHANGELOG.md) | Per-version release notes |

Full developer portal: [synapse.pyrx.tech/developers/sdks/android](https://synapse.pyrx.tech/developers/sdks/android).

## Requirements

| Tool | Minimum |
|---|---|
| `synapse-core` `minSdk` | 21 (Android 5.0 Lollipop) |
| `synapse-push` `minSdk` | 23 (Android 6.0 Marshmallow) |
| `compileSdk` / `targetSdk` | 34 (Android 14) |
| Kotlin (consumer projects) | 1.9+ |
| Build JDK | 17 |
| AndroidX | `android.useAndroidX=true` |

## Building from source

```bash
# JVM unit tests (no emulator required)
./gradlew test

# Lint + static analysis
./gradlew ktlintCheck detekt

# Build the AARs
./gradlew assembleDebug
./gradlew assembleRelease

# Publish to local Maven for sample-app integration
./gradlew publishToMavenLocal
```

You need:

- JDK 17 (`brew install --cask temurin@17`)
- Android SDK with `platforms;android-34` + `build-tools;34.0.0` installed (Android Studio handles this automatically, or use `sdkmanager` from `android-commandlinetools`)
- A `local.properties` file at the repo root pointing to your Android SDK:
  ```properties
  sdk.dir=/Users/<you>/Library/Android/sdk
  ```

## Sample app

A complete Jetpack Compose sample app lives at [`sample-app/`](sample-app) — every public SDK surface (identity, events, push, privacy, debug) is wired into a tab UI you can run on an emulator or real device.

```bash
# In Android Studio: open the repo root, select 'sample-app' as the run configuration, hit Run.
# Or from CLI:
./gradlew :sample-app:installDebug
```

To point the sample app at your own workspace, put the credentials in
`~/.gradle/gradle.properties` (user-global, never committed):

```properties
pyrx.workspaceId = <UUID from Synapse dashboard → /settings/workspace>
pyrx.apiKey      = psk_test_<32-hex from /settings/api-keys>
pyrx.baseUrl     = https://synapse-events.pyrx.tech
```

The sample-app build reads these via `providers.gradleProperty(...)`
and bakes them into BuildConfig. See [`sample-app/README.md`](sample-app/README.md) for the
full setup (including Firebase / `google-services.json` for push
testing).

## Cross-platform consistency

This SDK mirrors the [iOS SDK](https://github.com/PYRX-Tech/pyrx-synapse-ios) field-for-field: same config shape, same error hierarchy, same wire contract, same anonymous-merge state machine. When updating one, update the other.

## Contributing

Bug reports, feature requests, and pull requests are welcome on [GitHub](https://github.com/PYRX-Tech/pyrx-synapse-android).

For substantial changes, open an issue first so we can align on direction. Every PR is gated on `./gradlew test`, `./gradlew ktlintCheck detekt`, and `./gradlew assembleRelease`.

## License

[MIT](LICENSE) © PYRX Tech
