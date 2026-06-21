/*
 * Placeholder.kt
 * PYRXSynapse — synapse-push module
 *
 * Module is scaffolded empty in PR 1 (Phase 8.4b.1). Public API (push
 * registration + FCM handlers) lands in PR 4 (Phase 8.4b.7 + 8.4b.8).
 *
 * This file exists only so the Kotlin compiler emits the .aar with a non-empty
 * classpath, otherwise ktlint and detekt skip an empty module silently and
 * downstream `api(project(":synapse-push"))` fails resolution on consumers.
 */

package tech.pyrx.synapse.push

internal object PushModulePlaceholder {
    internal const val MODULE_VERSION: String = "0.1.0"
}
