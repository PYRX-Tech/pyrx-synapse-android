# PYRX Synapse — Android SDK

[![CI](https://github.com/PYRX-Tech/pyrx-synapse-android/actions/workflows/ci.yml/badge.svg)](https://github.com/PYRX-Tech/pyrx-synapse-android/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![API](https://img.shields.io/badge/API-21%2B-brightgreen.svg)](https://developer.android.com/about/versions/lollipop)
[![Kotlin](https://img.shields.io/badge/kotlin-1.9.22-blueviolet.svg)](https://kotlinlang.org)

Customer engagement SDK for Android — event tracking, identity, push notifications, and in-app messaging — powering [PYRX Synapse](https://synapse.pyrx.tech).

> **Status:** Phase 8.4b PR 1 (Foundation) — public API surface lands incrementally across 7 PRs. Today's PR ships the core `Pyrx` singleton, configuration, and encrypted storage. HTTP + identity arrive in PR 2.

## Install

`pyrx-synapse-android` ships three modules so you only depend on what you use:

| Module | What it gives you | Status |
|---|---|---|
| `synapse-core` | `Pyrx` singleton, identity, events, encrypted storage | PR 1 (this PR) — scaffold; PR 2 — HTTP + identify; PR 3 — events |
| `synapse-push` | FCM registration + notification handlers | PR 4 |
| `synapse-inapp` | In-app messages, inbox | Phase 9 |

```kotlin
// build.gradle.kts
dependencies {
    implementation("tech.pyrx.synapse:synapse-core:0.1.0")
    implementation("tech.pyrx.synapse:synapse-push:0.1.0")  // optional
}
```

Add Maven Central to your project's repository list if it isn't there already:

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

Initialize the SDK once per process — usually from `Application.onCreate`:

```kotlin
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import tech.pyrx.synapse.Pyrx
import tech.pyrx.synapse.PyrxConfig
import tech.pyrx.synapse.PyrxEnvironment
import java.util.UUID

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val config = PyrxConfig(
            workspaceId = UUID.fromString("00000000-0000-0000-0000-000000000000"),
            apiKey = "psk_live_yourkeyhere",
            environment = PyrxEnvironment.PRODUCTION,
        )

        MainScope().launch {
            Pyrx.initialize(this@MyApp, config)
        }
    }
}
```

Once PR 2 lands, you'll be able to call `Pyrx.identify("user-123")`, `Pyrx.track("checkout_completed")`, and so on. The wire surface is locked against the [iOS SDK PR #1+#2](https://github.com/PYRX-Tech/pyrx-synapse-ios) so the two platforms behave identically.

## Features (planned roadmap)

| Feature | PR | Status |
|---|---|---|
| `Pyrx.initialize` + config + storage | PR 1 (Phase 8.4b.1-3) | This PR |
| HTTP client + `identify` / `alias` / `logout` | PR 2 (Phase 8.4b.4-5) | Mirrors iOS PR #2 |
| `track` events + offline queue | PR 3 (Phase 8.4b.6) | — |
| Push registration + FCM handlers | PR 4 (Phase 8.4b.7-8) | — |
| Attribution + privacy + diagnostics | PR 5 (Phase 8.4b.9-11) | — |
| Sample app + Espresso suite | PR 6 (Phase 8.4b.12-13) | — |
| Docs site + Maven Central release | PR 7 (Phase 8.4b.14-15) | — |

## Requirements

- **minSdk 21** (Android 5.0 Lollipop)
- **targetSdk 34** (Android 14)
- **Kotlin 1.9+** in consumer projects
- **JDK 17** for builds (the AAR runs on any Android device meeting `minSdk`)
- AndroidX (`android.useAndroidX=true`)

## Building from source

```bash
# Run unit tests (JVM only — no emulator needed)
./gradlew test

# Lint + static analysis
./gradlew ktlintCheck detekt

# Build the AAR
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

## Cross-platform consistency

This SDK mirrors the [iOS SDK](https://github.com/PYRX-Tech/pyrx-synapse-ios) field-for-field: same config shape, same error hierarchy, same wire contract, same anonymous-merge state machine. When updating one, update the other.

## License

[MIT](LICENSE) © PYRX Tech
