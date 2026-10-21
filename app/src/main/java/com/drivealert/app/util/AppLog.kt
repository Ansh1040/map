package com.drivealert.app.util

import android.util.Log
import com.drivealert.app.BuildConfig
import java.util.Locale

/**
 * Privacy-safe logging. Debug/info logs exist only in debug builds (and are
 * stripped by R8 in release). Never pass raw coordinates: use [redact].
 */
object AppLog {
    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        Log.e(tag, message + (error?.let { " (${it.javaClass.simpleName})" } ?: ""))
    }

    /** Coarsens a coordinate to ~11 km for debugging; returns "redacted" in release. */
    fun redact(latitude: Double, longitude: Double): String =
        if (BuildConfig.DEBUG) String.format(Locale.US, "%.1f,%.1f", latitude, longitude) else "redacted"
}
