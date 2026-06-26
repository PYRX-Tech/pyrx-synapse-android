// synapse-inapp/build.gradle.kts
//
// In-app messaging module. Empty in PR 1 — full public API arrives in
// Phase 9. Scaffolded now so apps can plan Gradle dependency lines that
// will remain stable across the Phase 8 → Phase 9 transition.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

group = "tech.pyrx.synapse"
version = "0.1.3"

android {
    namespace = "tech.pyrx.synapse.inapp"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    api(project(":synapse-core"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (!name.contains("UnitTest", ignoreCase = true) &&
        !name.contains("AndroidTest", ignoreCase = true)
    ) {
        compilerOptions {
            freeCompilerArgs.add("-Xexplicit-api=strict")
        }
    }
}

// Maven publication intentionally NOT configured for v0.1.0 — the module is
// an empty Phase-8.4b scaffold (in-app messaging arrives in Phase 9) and would
// publish a misleading 0.1.0 placeholder to Maven Central. Until Phase 9 lands
// the implementation + completes the POM, NMCP aggregation simply skips this
// module (no MavenPublication created → nothing to aggregate → no publish).
//
// Phase 9 reactivation checklist:
//   1. Restore the afterEvaluate publishing { } block (copy from synapse-core)
//   2. Add full POM metadata (name, description, url, license, developers, scm)
//   3. Add the same `signing { useInMemoryPgpKeys + sign(publications) }` block
//   4. Bump version in lockstep with synapse-core / synapse-push
