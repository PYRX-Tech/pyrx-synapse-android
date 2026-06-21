// build.gradle.kts (root)
//
// Root build script — declares plugin versions and ktlint/detekt configuration
// applied to every Kotlin subproject. Per-module Android library plugins live
// in each module's own build.gradle.kts.

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

// Apply ktlint + detekt to every subproject that has Kotlin sources so the
// formatting + static-analysis surface is uniform across modules.
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    // ktlint config — pinned to a stable version so CI doesn't drift when the
    // plugin auto-resolves to the latest patch.
    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set("1.2.1")
        android.set(true)
        ignoreFailures.set(false)
        reporters {
            reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
            reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
        }
        filter {
            // Generated sources have no business getting linted.
            exclude { entry -> entry.file.toString().contains("/build/") }
        }
    }

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        toolVersion = "1.23.6"
        // sample-app uses a relaxed overlay so Compose `@Composable`
        // PascalCase naming + long Composable screens don't trip the
        // stricter SDK rules. SDK modules (synapse-core, synapse-push,
        // synapse-inapp) keep the strict config.
        val configFile =
            if (project.name == "sample-app") {
                "${rootProject.projectDir}/config/detekt/detekt-sample.yml"
            } else {
                "${rootProject.projectDir}/config/detekt/detekt.yml"
            }
        config.setFrom(files(configFile))
        buildUponDefaultConfig = true
        autoCorrect = false
        parallel = true
    }
}
