// synapse-core/build.gradle.kts
//
// The base SDK module — public `Pyrx` singleton, config, logger, storage.
// Push registration and in-app messaging live in their own modules so apps
// opt in only to what they ship.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    `maven-publish`
    signing
    jacoco
}

group = "tech.pyrx.synapse"
version = "0.1.4"

android {
    namespace = "tech.pyrx.synapse"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")

        // Phase 8.4b Task 8.4b.12 — instrumented tests live in
        // src/androidTest/ and run via `./gradlew connectedDebugAndroidTest`
        // on a real device or emulator. They are NOT run by `./gradlew test`
        // — that gate stays as-is (Robolectric only). On-device CI runs
        // these as part of PR 7's release verification.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // Public-API stability — surface every Kotlin API explicitly. Required
        // for a published library so we don't accidentally ship internal types.
        // Test sources are excluded below (explicit-api mode applies only to main).
        freeCompilerArgs = freeCompilerArgs + listOf("-opt-in=kotlin.RequiresOptIn")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // Required for AGP 8+ — produce a sources JAR for downstream consumers.
    // withJavadocJar() omitted: Android's JavaDocGenerationTask delegates to
    // Dokka which crashes on certain KDoc reference links under AGP 8.2.x +
    // bundled Dokka version. We provide an empty -javadoc.jar artifact via
    // a custom Jar task below to satisfy Maven Central's requirement that
    // a -javadoc.jar exists (content can be empty).
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
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.annotation)

    // PR 2 — network + identity. Exposed as `api` so downstream modules
    // (synapse-push, synapse-inapp) can share the same OkHttp Call.Factory
    // and the same kotlinx.serialization JSON instance without re-declaring
    // versions. Mirrors iOS PR #2 which puts `URLSession` + `Foundation`
    // JSON in the public surface of the core target.
    api(libs.okhttp)
    api(libs.kotlinx.serialization.json)

    // PR 3 — offline event queue. Room is the persistence layer; runtime is
    // `implementation` (downstream modules don't need to depend on Room
    // directly), KSP processes the compile-time @Database / @Dao annotations.
    // ktx pulls in coroutine-aware suspend DAO support.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    // PR 3 — Room in-memory database + Robolectric give us an Android
    // Context in JVM unit tests so Room can open SQLite without an emulator.
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)

    // Instrumented (on-device) test dependencies. Used by src/androidTest/
    // and only pulled in for `connectedDebugAndroidTest` — `./gradlew test`
    // ignores this configuration entirely.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlin.test)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

// Apply explicit-api strict ONLY to the production source sets — test
// fixtures don't ship in the AAR and forcing visibility on every test
// helper just adds noise. AGP routes test variants through dedicated
// compile tasks (compile*UnitTestKotlin), which we skip explicitly here.
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
// Coverage gate is 80%+ on identity/events/queue/push subsystems per
// DEVELOPMENT_PLAN.md L1221. The Gradle Android plugin does not auto-wire
// JaCoCo against `testDebugUnitTest` — we declare the report task manually so
// `./gradlew :synapse-core:jacocoTestReport` produces an HTML + XML report.
//
// Excludes follow the standard Android/Room generated-code list so coverage
// reflects hand-written SDK logic, not generated database glue.
jacoco {
    toolVersion = "0.8.11"
}

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")
    group = "verification"
    description = "Generates JaCoCo line/branch coverage report for synapse-core unit tests."

    reports {
        html.required.set(true)
        xml.required.set(true)
        csv.required.set(false)
    }

    val excludes =
        listOf(
            // Android / Kotlin generated
            "**/R.class",
            "**/R\$*.class",
            "**/BuildConfig.*",
            "**/Manifest*.*",
            "**/*Test*.*",
            "android/**/*.*",
            // Kotlin-generated
            "**/*\$\$serializer.*",
            "**/*\$Companion.*",
            "**/*\$WhenMappings.*",
            // Room-generated database glue
            "**/queue/EventQueueDatabase_Impl*",
            "**/queue/QueuedEventDao_Impl*",
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

// Empty javadoc JAR — Maven Central requires -javadoc.jar exist (workaround
// for AGP 8.2.x + Dokka KDoc-link bug; see publishing { } comment above).
val emptyJavadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

// Maven coords — wired now, publish job activated in PR 7 release work.
// Sonatype Central credentials are read from secrets in .github/workflows/publish.yml.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifact(emptyJavadocJar)

                groupId = "tech.pyrx.synapse"
                artifactId = "synapse-core"
                version = project.version.toString()

                pom {
                    name.set("PYRX Synapse Android SDK — Core")
                    description.set(
                        "Customer engagement SDK for Android. Core module: " +
                            "Pyrx singleton, identity, events, encrypted storage.",
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

    // GPG signing — required by Maven Central. In-memory key from env vars
    // (CI provides them via GH Actions secrets; locally signing is skipped
    // when GPG_PRIVATE_KEY/GPG_PASSPHRASE are absent so dev builds stay fast).
    signing {
        val signingKey = providers.environmentVariable("GPG_PRIVATE_KEY").orNull
        val signingPwd = providers.environmentVariable("GPG_PASSPHRASE").orNull
        if (!signingKey.isNullOrBlank() && !signingPwd.isNullOrBlank()) {
            useInMemoryPgpKeys(signingKey, signingPwd)
            sign(publishing.publications["release"])
        }
    }
}
