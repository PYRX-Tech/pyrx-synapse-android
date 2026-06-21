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
    // NMCP aggregation — collects publications from synapse-core + synapse-push +
    // synapse-inapp and uploads as a single bundle to Sonatype Central Portal.
    // Requires CENTRAL_USERNAME + CENTRAL_PASSWORD env vars at publish time;
    // signing keys (GPG_PRIVATE_KEY + GPG_PASSPHRASE) wired per-module.
    alias(libs.plugins.nmcp)
}

// Root-level group + version drive NMCP's deployment-bundle filename in the
// Central Portal UI (e.g. `pyrx-synapse-android-0.1.2.zip` instead of
// `pyrx-synapse-android-unspecified.zip`). Cosmetic only — the actual
// per-module artifact coordinates (groupId/artifactId/version) live in each
// module's build.gradle.kts and are unaffected by these root values. Keep
// version in lockstep with the per-module versions until a buildSrc release
// script automates the sync.
group = "tech.pyrx.synapse"
version = "0.1.2"

// Aggregate every SDK module publication into one Central Portal upload.
// Applied at the root project; nmcp's aggregation API walks subprojects
// looking for "release" MavenPublications. synapse-inapp is intentionally
// excluded (its publication block is commented out for v0.1.0).
nmcp {
    publishAllProjectsProbablyBreakingProjectIsolation {
        // Central Portal credentials sourced from env (CI provides them via
        // GH Actions secrets; locally they come from gradle.properties or
        // shell env).
        username = providers.environmentVariable("CENTRAL_USERNAME")
        password = providers.environmentVariable("CENTRAL_PASSWORD")
        // USER_MANAGED requires manual "Publish" click in the Central Portal
        // dashboard after upload — safe default for first v0.1.0 dry-run.
        // Flip to AUTOMATIC after the first verified release.
        publicationType = "USER_MANAGED"
    }
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
