package com.drivealert.app.navigation

import kotlinx.serialization.Serializable

@Serializable data object OnboardingRoute
@Serializable data object HomeRoute
@Serializable data object HistoryRoute
@Serializable data object SettingsRoute
@Serializable data object CalibrationRoute
@Serializable data object SetupAssistantRoute
@Serializable data object PrivacyPolicyRoute
@Serializable data object DataPackagesRoute
@Serializable data object LicensesRoute
@Serializable data class CameraDetailsRoute(val cameraId: String)
@Serializable data class ReportRoute(val cameraId: String? = null)
