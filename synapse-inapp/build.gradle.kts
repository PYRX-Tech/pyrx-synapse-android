// synapse-inapp/build.gradle.kts
//
// In-app messaging module. Full public surface lands in Phase 10 PR-2b
// (this PR). Depends on synapse-core for [Pyrx], the wire-level
// [HTTPSession], and the [PyrxEvent] sealed interface (extended here
// via the new [InAppMessageReceived] / [InAppMessageDismissed] cases).

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
    signing
}

group = "tech.pyrx.synapse"
version = "0.2.0"

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

    // Required for AGP 8+ — produce a sources JAR for downstream consumers.
    // Mirrors synapse-core's publishing setup. Javadoc handled via the
    // empty-jar workaround below to dodge Dokka KDoc-link crashes under
    // AGP 8.2.x.
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    api(project(":synapse-core"))
    implementation(libs.kotlinx.coroutines.core)
    // okhttp is pulled in transitively via synapse-core's `api(libs.okhttp)`
    // but we name it explicitly so this module compiles cleanly even if
    // synapse-core's exposure shape changes.
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    // Robolectric gives us an Android Context in JVM unit tests so any
    // path that touches android.* APIs (currently none in this module,
    // but the test fixtures may need a Context for PyrxInApp.install
    // round-trip coverage) doesn't require an emulator.
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
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

// ---------------------------------------------------------------------------
// Maven publication — activated for Phase 10 PR-2b (v0.2.0).
// ---------------------------------------------------------------------------
// Empty javadoc JAR — Maven Central requires -javadoc.jar exist
// (AGP 8.2.x + Dokka has a KDoc-link bug; the empty jar satisfies the
// requirement). Mirrors the workaround in synapse-core's build script.
val emptyJavadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifact(emptyJavadocJar)

                groupId = "tech.pyrx.synapse"
                artifactId = "synapse-inapp"
                version = project.version.toString()

                pom {
                    name.set("PYRX Synapse Android SDK — In-App Messaging")
                    description.set(
                        "Customer engagement SDK for Android. In-app messaging " +
                            "module: polling cache, telemetry round-trips, render-callback " +
                            "delivery for in-app messages defined in the Synapse dashboard.",
                    )
                    url.set("https://github.com/PYRX-Tech/pyrx-synapse-android")
                    licenses {
                        license {
                            name.set("MIT")
                            url.set("https://github.com/PYRX-Tech/pyrx-synapse-android/blob/main/LICENSE")
                        }
                    }
                    developers {
                        developer {
                            id.set("pyrx-tech")
                            name.set("PYRX Tech")
                            email.set("dev@pyrx.tech")
                        }
                    }
                    scm {
                        url.set("https://github.com/PYRX-Tech/pyrx-synapse-android")
                        connection.set("scm:git:git://github.com/PYRX-Tech/pyrx-synapse-android.git")
                        developerConnection.set("scm:git:ssh://git@github.com/PYRX-Tech/pyrx-synapse-android.git")
                    }
                }
            }
        }
    }

    // GPG signing — required by Maven Central. Mirrors synapse-core's pattern.
    signing {
        val signingKey = providers.environmentVariable("GPG_PRIVATE_KEY").orNull
        val signingPwd = providers.environmentVariable("GPG_PASSPHRASE").orNull
        if (!signingKey.isNullOrBlank() && !signingPwd.isNullOrBlank()) {
            useInMemoryPgpKeys(signingKey, signingPwd)
            sign(publishing.publications["release"])
        }
    }
}
