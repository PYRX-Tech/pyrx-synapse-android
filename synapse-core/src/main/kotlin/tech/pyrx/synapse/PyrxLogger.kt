/*
 * PyrxLogger.kt
 * PYRXSynapse — Android
 *
 * Thin android.util.Log wrapper that respects a runtime [LogLevel] threshold.
 * Internal — not part of the public API. The [Pyrx] singleton mutates its
 * level via [Pyrx.setLogLevel].
 *
 * Mirrors iOS `PyrxLogger` (OSLog-backed) with the same level semantics so
 * cross-platform integration guides can use one phrasing.
 */

package tech.pyrx.synapse

import android.util.Log
import java.util.concurrent.atomic.AtomicReference

internal class PyrxLogger {
    private val levelRef: AtomicReference<LogLevel> = AtomicReference(LogLevel.INFO)

    val level: LogLevel
        get() = levelRef.get()

    fun setLevel(level: LogLevel) {
        levelRef.set(level)
    }

    /**
     * Inline + crossinline lambda so the message string is only built when
     * the level threshold permits — matches iOS `@autoclosure` zero-cost
     * semantics for silenced log calls.
     */
    inline fun debug(message: () -> String) {
        if (level <= LogLevel.DEBUG) {
            Log.d(TAG, message())
        }
    }

    inline fun info(message: () -> String) {
        if (level <= LogLevel.INFO) {
            Log.i(TAG, message())
        }
    }

    inline fun warning(message: () -> String) {
        if (level <= LogLevel.WARNING) {
            Log.w(TAG, message())
        }
    }

    inline fun error(
        message: () -> String,
        throwable: Throwable? = null,
    ) {
        if (level <= LogLevel.ERROR) {
            if (throwable != null) {
                Log.e(TAG, message(), throwable)
            } else {
                Log.e(TAG, message())
            }
        }
    }

    companion object {
        /** Logcat tag. Keep at 23 chars or fewer (Android's per-tag max). */
        const val TAG: String = "PYRXSynapse"
    }
}
