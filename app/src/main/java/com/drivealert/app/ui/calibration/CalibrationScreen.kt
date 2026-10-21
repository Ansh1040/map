package com.drivealert.app.ui.calibration

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivealert.app.R
import com.drivealert.app.battery.SystemSettingsIntents
import com.drivealert.app.permissions.LocationPermissions
import com.drivealert.app.ui.components.BackTopBar
import com.drivealert.app.ui.components.InfoBanner
import com.drivealert.app.ui.components.PrimaryActionButton
import com.drivealert.app.ui.components.SectionCard
import com.drivealert.app.ui.components.Tone
import com.drivealert.app.ui.theme.BrandColors
import com.drivealert.app.ui.theme.DisplayNumberStyle

@Composable
fun CalibrationScreen(
    onBack: () -> Unit,
    onDriveStarted: () -> Unit,
    viewModel: CalibrationViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) {
        viewModel.refreshReadiness()
        onPauseOrDispose { }
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.refreshReadiness()
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.refreshReadiness()
    }

    Scaffold(topBar = { BackTopBar(stringResource(R.string.calibration_title), onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val readiness = ui.readiness
            when {
                readiness == null -> Unit
                !readiness.preciseLocationGranted -> InfoBanner(
                    icon = Icons.Filled.LocationOff,
                    text = stringResource(R.string.banner_precise_needed),
                    tone = Tone.BAD,
                    actionLabel = stringResource(R.string.action_allow),
                    onAction = { locationLauncher.launch(LocationPermissions.FOREGROUND_LOCATION) },
                )
                !readiness.locationServicesEnabled -> InfoBanner(
                    icon = Icons.Filled.GpsOff,
                    text = stringResource(R.string.banner_location_off),
                    tone = Tone.BAD,
                    actionLabel = stringResource(R.string.action_turn_on),
                    onAction = { SystemSettingsIntents.openLocationSettings(context) },
                )
            }
            if (readiness != null && !readiness.notificationsAllowed) {
                InfoBanner(
                    icon = Icons.Filled.NotificationsOff,
                    text = stringResource(R.string.banner_notifications_off),
                    tone = Tone.WARN,
                    actionLabel = stringResource(R.string.action_allow),
                    onAction = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            SystemSettingsIntents.openNotificationSettings(context)
                        }
                    },
                )
            }

            SectionCard {
                AccuracyGauge(ui)
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.calibration_explanation),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (ui.locationReady && (ui.quality == GpsQuality.WEAK || (ui.quality == GpsQuality.NONE && ui.secondsElapsed > 8))) {
                InfoBanner(icon = Icons.Filled.WbSunny, text = stringResource(R.string.calibration_weak_tip), tone = Tone.WARN)
            }

            InfoBanner(icon = Icons.Outlined.Info, text = stringResource(R.string.calibration_safety_note), tone = Tone.NEUTRAL)

            PrimaryActionButton(
                text = stringResource(if (ui.isStartAnyway) R.string.start_drive_anyway else R.string.start_drive),
                icon = Icons.Filled.DirectionsCar,
                enabled = ui.canStart,
                onClick = {
                    viewModel.startDrive()
                    onDriveStarted()
                },
            )
        }
    }
}

@Composable
private fun AccuracyGauge(ui: CalibrationUiState) {
    val progress = when (ui.quality) {
        GpsQuality.NONE -> 0f
        GpsQuality.WEAK -> 0.25f
        GpsQuality.FAIR -> 0.55f
        GpsQuality.GOOD -> 0.8f
        GpsQuality.EXCELLENT -> 1f
    }
    val color = when (ui.quality) {
        GpsQuality.EXCELLENT, GpsQuality.GOOD -> BrandColors.Success
        GpsQuality.FAIR -> MaterialTheme.colorScheme.primary
        GpsQuality.WEAK -> BrandColors.Amber
        GpsQuality.NONE -> MaterialTheme.colorScheme.outline
    }
    val label = stringResource(
        when (ui.quality) {
            GpsQuality.NONE -> R.string.gps_searching
            GpsQuality.WEAK -> R.string.quality_weak
            GpsQuality.FAIR -> R.string.quality_fair
            GpsQuality.GOOD -> R.string.quality_good
            GpsQuality.EXCELLENT -> R.string.quality_excellent
        },
    )
    val accuracyText = ui.accuracyM?.let { stringResource(R.string.accuracy_value, it.toInt()) } ?: "—"
    val description = stringResource(R.string.cd_accuracy_gauge, accuracyText, label)

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(200.dp)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            if (ui.quality == GpsQuality.NONE && ui.locationReady) {
                CircularProgressIndicator(modifier = Modifier.size(200.dp), strokeWidth = 12.dp, color = color)
            } else {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(200.dp),
                    strokeWidth = 12.dp,
                    color = color,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = StrokeCap.Round,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(accuracyText, style = DisplayNumberStyle)
                Text(stringResource(R.string.gps_accuracy), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = color)
    }
}
