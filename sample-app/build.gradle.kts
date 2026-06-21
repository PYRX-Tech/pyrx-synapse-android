// sample-app/build.gradle.kts
//
// Jetpack Compose demo app for the PYRX Synapse Android SDK (Phase 8.4b
// Task 8.4b.13). Mirrors the iOS SwiftUIDemo target: exercises every public
// SDK surface (initialize, identify, alias, logout, track, screen, push
// registration, debugInfo, setTrackingEnabled, deleteUser) end-to-end so
// host-app developers can copy patterns from a working reference.
//
// NOT published to Maven; build target only. `./gradlew :sample-app:
// assembleDebug` succeeds in this PR; physical-device verification is
// deferred to PR 7 (and is described in `sample-app/README.md`).
//
// Application ID: `tech.pyrx.synapse.sample` — distinct from the SDK's
// `tech.pyrx.synapse` namespace so a developer can ship the demo alongside
// their own app for side-by-side testing.

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "tech.pyrx.synapse.sample"
    compileSdk = 34

    defaultConfig {
        applicationId = "tech.pyrx.synapse.sample"
        // Matches synapse-push's floor (the sample depends on synapse-push;
        // 23 is the lowest API on which FCM 25.x supports both runtime
        // permission flow and the legacy push intent path).
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
        // No BuildConfig generation — the sample reads config from
        // `local.properties` overrides only (see SampleApplication.kt).
        buildConfig = false
    }

    composeOptions {
        // Matches Kotlin 1.9.22 → Compose Compiler 1.5.8. AGP fails the
        // build if these drift apart, so the version is pinned here rather
        // than in libs.versions.toml.
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        debug {
            // No minification on debug — the sample is meant to be
            // poked at with the inspector / debugger; ProGuard rules
            // for the SDK live in the SDK module itself.
            isMinifyEnabled = false
        }
        release {
            // The sample is never released to a store; keeping release
            // un-minified makes it easy for QA to inspect during PR 7
            // verification without an obfuscation-tracing detour.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    packaging {
        // Compose's lifecycle libs ship duplicate META-INF/LICENSE-like
        // entries when bundled with the AndroidX core artifacts. Excluding
        // them is the AGP-recommended path and matches every modern
        // Compose template.
        resources {
            excludes +=
                listOf(
                    "/META-INF/{AL2.0,LGPL2.1}",
                    "/META-INF/DEPENDENCIES",
                    "/META-INF/LICENSE",
                    "/META-INF/LICENSE.txt",
                    "/META-INF/NOTICE",
                    "/META-INF/NOTICE.txt",
                )
        }
    }
}

dependencies {
    // SDK under demo
    implementation(project(":synapse-core"))
    implementation(project(":synapse-push"))

    // Compose (BoM-pinned)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    // AndroidX core + lifecycle + activity-compose entry point
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Firebase Messaging (BoM-pinned via synapse-push transitives, but
    // declared explicitly so the sample's manifest merger sees the FCM
    // service provider).
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // Kotlin coroutines (already pulled in transitively via synapse-core's
    // `api` declaration, but listed explicitly for clarity).
    implementation(libs.kotlinx.coroutines.android)
}
