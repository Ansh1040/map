package com.drivealert.app.network

import com.drivealert.app.BuildConfig

/**
 * Backend configuration. TODO(deployment): supply CAMERA_API_BASE_URL — an
 * HTTPS endpoint operated under a licence that permits redistribution of the
 * camera data to app users.
 */
object ApiConfig {
    val baseUrl: String = BuildConfig.CAMERA_API_BASE_URL.let { if (it.isBlank() || it.endsWith("/")) it else "$it/" }

    /** Sync and report upload are disabled until a valid HTTPS URL is configured. */
    val isConfigured: Boolean = baseUrl.startsWith("https://")

    const val CONNECT_TIMEOUT_SECONDS = 15L
    const val READ_TIMEOUT_SECONDS = 30L
}
