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
version = "0.1.0"

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

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "tech.pyrx.synapse"
                artifactId = "synapse-inapp"
                version = project.version.toString()
            }
        }
    }
}
