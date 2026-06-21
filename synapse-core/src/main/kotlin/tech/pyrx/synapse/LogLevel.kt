/*
 * LogLevel.kt
 * PYRXSynapse — Android
 *
 * Log verbosity for the SDK's internal Logcat-backed logger. Mirrors iOS
 * `LogLevel` ordering — `DEBUG` is the most verbose, `NONE` silences the
 * logger entirely.
 */

package tech.pyrx.synapse

/**
 * Runtime log verbosity. Lower ordinals are more verbose.
 *
 * The default level for `PyrxConfig` is [INFO]. Set [NONE] in production
 * builds you want fully silent; set [DEBUG] when debugging integration
 * problems.
 */
public enum class LogLevel {
    DEBUG,
    INFO,
    WARNING,
    ERROR,
    NONE,
    ;

    public operator fun compareTo(other: Int): Int = ordinal.compareTo(other)
}
