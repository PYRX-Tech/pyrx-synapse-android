// synapse-push/build.gradle.kts
//
// Push registration + FCM handler module. Empty in PR 1 — the public API
// (Pyrx.shared.push.register / handler) lands in PR 4 (Phase 8.4b.7 + 8.4b.8).
// Module exists now so apps that want a push-only opt-in path can already
// declare `implementation("tech.pyrx.synapse:synapse-push:0.1.0")` without
// pulling in-app dependencies they don't use.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

group = "tech.pyrx.synapse"
version = "0.1.0"

android {
    namespace = "tech.pyrx.synapse.push"
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

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "tech.pyrx.synapse"
                artifactId = "synapse-push"
                version = project.version.toString()
            }
        }
    }
}
