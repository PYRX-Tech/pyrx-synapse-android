// synapse-core/build.gradle.kts
//
// The base SDK module — public `Pyrx` singleton, config, logger, storage.
// Push registration and in-app messaging live in their own modules so apps
// opt in only to what they ship.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

group = "tech.pyrx.synapse"
version = "0.1.0"

android {
    namespace = "tech.pyrx.synapse"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
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
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
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

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
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

// Maven coords — wired now, publish job activated in PR 7 release work.
// Sonatype Central credentials are read from secrets in .github/workflows/publish.yml.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

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
}
