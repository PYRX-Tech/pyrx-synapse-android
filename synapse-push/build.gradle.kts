// synapse-push/build.gradle.kts
//
// Push registration + FCM handler module. PR 4 wires the public surface:
//
//   - `PyrxMessagingService`   — FirebaseMessagingService subclass; receives
//                                FCM tokens + remote messages.
//   - `PushRegistration`       — POST /v1/devices orchestrator (token →
//                                wire body → HTTPClient).
//   - `PushHandlers`           — notification-tap + action-button telemetry
//                                (POST /v1/push/opened, /v1/push/click).
//   - `DeviceMetadata`         — Build / Locale / TimeZone snapshot.
//
// Firebase Messaging is `implementation` (not `api`) so apps that don't ship
// push don't transitively pull firebase-iid / firebase-analytics. The BoM is
// the canonical version pin — see `gradle/libs.versions.toml`.
//
// Apps that want push opt in by adding:
//   implementation("tech.pyrx.synapse:synapse-push:<version>")
//   implementation(platform("com.google.firebase:firebase-bom:34.x"))
//
// AGP requires consumers to bring their own Firebase BoM if they want to
// pin a different version; ours is the minimum-supported floor.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
    signing
    jacoco
}

group = "tech.pyrx.synapse"
version = "0.1.2"

android {
    namespace = "tech.pyrx.synapse.push"
    compileSdk = 34

    defaultConfig {
        // Firebase Messaging 25.x requires minSdk 23 (Android 6, Marshmallow).
        // synapse-core stays at 21 — apps that don't depend on synapse-push
        // still ship to 21. PR 7 docs must note the floor bump for push
        // consumers.
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // withJavadocJar() omitted — Dokka crashes on certain KDoc reference
    // links under AGP 8.2.x. Empty -javadoc.jar provided by `emptyJavadocJar`
    // task below (Maven Central requires the artifact, not the content).
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    // Required for Robolectric — host JVM unit tests need access to the
    // android resources / assets to fake out a Context. Without this flag
    // Robolectric throws on `ApplicationProvider.getApplicationContext()`.
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
    implementation(libs.kotlinx.coroutines.android)

    // Firebase Messaging — BoM-pinned so consumers don't fight version drift.
    // `platform(...)` resolves the BoM; `firebase-messaging` has no version
    // declared because the BoM owns it.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    // Robolectric + androidx-test-core give us an Android Context in JVM unit
    // tests so we can construct PyrxMessagingService instances without
    // booting an emulator. Real Firebase SDK calls are mocked.
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)

    // The push tests reuse MockHTTPSession declared in synapse-core's test
    // source set. We pull it in via a `testFixtures`-style classpath share —
    // the simplest path is to keep a thin copy in this module's test
    // sources so we don't have to enable AGP test fixtures across modules.
    // (Declared in src/test/.../MockHTTPSession.kt — kept byte-identical
    // with synapse-core's copy so the wire-shape assertions stay aligned.)
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
// JaCoCo coverage (Phase 8.4b Task 8.4b.12)
// ---------------------------------------------------------------------------
// Mirrors synapse-core wiring. synapse-push has a smaller surface (push
// registration + tap/click telemetry + device metadata + Firebase service
// shim) so the coverage gate applies to the same set of generated reports.
jacoco {
    toolVersion = "0.8.11"
}

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")
    group = "verification"
    description = "Generates JaCoCo line/branch coverage report for synapse-push unit tests."

    reports {
        html.required.set(true)
        xml.required.set(true)
        csv.required.set(false)
    }

    val excludes =
        listOf(
            "**/R.class",
            "**/R\$*.class",
            "**/BuildConfig.*",
            "**/Manifest*.*",
            "**/*Test*.*",
            "android/**/*.*",
            "**/*\$\$serializer.*",
            "**/*\$Companion.*",
            "**/*\$WhenMappings.*",
            // PushModulePlaceholder is the empty marker so the module
            // compiles with no API surface in earlier PRs; it has no logic.
            "**/PushModulePlaceholder*",
        )

    val kotlinClasses =
        fileTree("${layout.buildDirectory.get()}/tmp/kotlin-classes/debug") {
            exclude(excludes)
        }

    classDirectories.setFrom(kotlinClasses)
    sourceDirectories.setFrom(files("src/main/kotlin"))
    executionData.setFrom(
        fileTree(layout.buildDirectory.get()) {
            include("jacoco/testDebugUnitTest.exec", "outputs/unit_test_code_coverage/debugUnitTest/*.exec")
        },
    )
}

tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

android {
    buildTypes {
        named("debug") {
            enableUnitTestCoverage = true
        }
    }
}

// Empty -javadoc.jar (Maven Central requires the artifact; AGP 8.2.x Dokka bug).
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
                artifactId = "synapse-push"
                version = project.version.toString()

                pom {
                    name.set("PYRX Synapse Android SDK — Push")
                    description.set(
                        "FCM push registration + delivery handlers for PYRX Synapse. " +
                            "Depends on synapse-core and Firebase Messaging.",
                    )
                    url.set("https://github.com/PYRX-Tech/pyrx-synapse-android")
                    licenses {
                        license {
                            name.set("MIT")
                            url.set(
                                "https://github.com/PYRX-Tech/pyrx-synapse-android/blob/main/LICENSE",
                            )
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

    // GPG signing — required by Maven Central. See synapse-core for full rationale.
    signing {
        val signingKey = providers.environmentVariable("GPG_PRIVATE_KEY").orNull
        val signingPwd = providers.environmentVariable("GPG_PASSPHRASE").orNull
        if (!signingKey.isNullOrBlank() && !signingPwd.isNullOrBlank()) {
            useInMemoryPgpKeys(signingKey, signingPwd)
            sign(publishing.publications["release"])
        }
    }
}
