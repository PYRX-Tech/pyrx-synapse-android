// settings.gradle.kts
//
// Root Gradle settings for the PYRX Synapse Android SDK monorepo.
// Declares the three publishable modules. `synapse-push` and `synapse-inapp`
// are scaffolded empty in PR 1 — their public surface lands in PR 4 (push)
// and Phase 9 (in-app) respectively.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "pyrx-synapse-android"

include(":synapse-core")
include(":synapse-push")
include(":synapse-inapp")

// Sample app (Phase 8.4b Task 8.4b.13) — Jetpack Compose demo that
// exercises every public SDK surface. NOT published to Maven; build target
// only. See `sample-app/README.md` for setup + how-to-run instructions.
include(":sample-app")
