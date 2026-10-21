package com.drivealert.app.ui.setup

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.drivealert.app.R
import com.drivealert.app.battery.BatteryRestrictionChecker
import com.drivealert.app.battery.DeviceFamily
import com.drivealert.app.battery.DeviceReadiness
import com.drivealert.app.battery.OemBatteryGuide
import com.drivealert.app.battery.SystemSettingsIntents
import com.drivealert.app.permissions.LocationPermissions
import com.drivealert.app.ui.components.BackTopBar
import com.drivealert.app.ui.components.InfoBanner
import com.drivealert.app.ui.components.SectionCard
import com.drivealert.app.ui.components.SectionTitle
import com.drivealert.app.ui.components.Tone
import com.drivealert.app.ui.components.toneColors
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SetupAssistantViewModel @Inject constructor(
    private val checker: BatteryRestrictionChecker,
) : ViewModel() {
    fun check(): DeviceReadiness = checker.check()
}

private enum class CheckState { OK, WARN, FAIL }

@Composable
fun SetupAssistantScreen(onBack: () -> Unit, viewModel: SetupAssistantViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var readiness by remember { mutableStateOf(viewModel.check()) }
    LifecycleResumeEffect(Unit) {
        readiness = viewModel.check()
        onPauseOrDispose { }
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        readiness = viewModel.check()
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        readiness = viewModel.check()
    }
    val r = readiness

    Scaffold(topBar = { BackTopBar(stringResource(R.string.setup_assistant), onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.setup_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            SectionTitle(stringResource(R.string.setup_section_required))
            SectionCard {
                CheckItem(
                    title = stringResource(R.string.check_location_permission),
                    detail = stringResource(if (r.preciseLocationGranted || r.approximateLocationOnly) R.string.check_location_permission_ok else R.string.check_location_permission_fail),
                    state = if (r.preciseLocationGranted || r.approximateLocationOnly) CheckState.OK else CheckState.FAIL,
                    actionLabel = stringResource(R.string.action_allow).takeIf { !r.preciseLocationGranted && !r.approximateLocationOnly },
                    onAction = { locationLauncher.launch(LocationPermissions.FOREGROUND_LOCATION) },
                )
                HorizontalDivider()
                CheckItem(
                    title = stringResource(R.string.check_precise_location),
                    detail = stringResource(if (r.preciseLocationGranted) R.string.check_precise_ok else R.string.check_precise_fail),
                    state = if (r.preciseLocationGranted) CheckState.OK else CheckState.FAIL,
                    actionLabel = stringResource(R.string.action_open_settings).takeIf { !r.preciseLocationGranted },
                    onAction = {
                        if (r.approximateLocationOnly) {
                            SystemSettingsIntents.openAppDetails(context)
                        } else {
                            locationLauncher.launch(LocationPermissions.FOREGROUND_LOCATION)
                        }
                    },
                )
                HorizontalDivider()
                CheckItem(
                    title = stringResource(R.string.check_location_services),
                    detail = stringResource(if (r.locationServicesEnabled) R.string.check_location_services_ok else R.string.check_location_services_fail),
                    state = if (r.locationServicesEnabled) CheckState.OK else CheckState.FAIL,
                    actionLabel = stringResource(R.string.action_turn_on).takeIf { !r.locationServicesEnabled },
                    onAction = { SystemSettingsIntents.openLocationSettings(context) },
                )
            }

            SectionTitle(stringResource(R.string.setup_section_reliability))
            SectionCard {
                val notificationsOk = r.notificationsAllowed && r.alertChannelEnabled
                CheckItem(
                    title = stringResource(R.string.check_notifications),
                    detail = stringResource(if (notificationsOk) R.string.check_notifications_ok else R.string.check_notifications_fail),
                    state = if (notificationsOk) CheckState.OK else CheckState.WARN,
                    actionLabel = stringResource(R.string.action_allow).takeIf { !notificationsOk },
                    onAction = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !LocationPermissions.hasNotificationPermission(context)) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            SystemSettingsIntents.openNotificationSettings(context)
                        }
                    },
                )
                HorizontalDivider()
                CheckItem(
                    title = stringResource(R.string.check_background_activity),
                    detail = stringResource(if (r.backgroundRestricted) R.string.check_background_restricted else R.string.check_background_ok),
                    state = if (r.backgroundRestricted) CheckState.FAIL else CheckState.OK,
                    actionLabel = stringResource(R.string.action_open_settings).takeIf { r.backgroundRestricted },
                    onAction = { SystemSettingsIntents.openAppDetails(context) },
                )
                HorizontalDivider()
                CheckItem(
                    title = stringResource(R.string.check_power_saving),
                    detail = stringResource(if (r.powerSaveMode) R.string.check_power_saving_on else R.string.check_power_saving_off),
                    state = if (r.powerSaveMode) CheckState.WARN else CheckState.OK,
                    actionLabel = stringResource(R.string.action_open_settings).takeIf { r.powerSaveMode },
                    onAction = { SystemSettingsIntents.openBatterySaverSettings(context) },
                )
                HorizontalDivider()
                CheckItem(
                    title = stringResource(R.string.check_battery_optimization),
                    detail = stringResource(if (r.ignoringBatteryOptimizations) R.string.check_battery_unrestricted else R.string.check_battery_optimized),
                    state = CheckState.OK,
                    actionLabel = null,
                    onAction = {},
                )
            }

            OemGuideCard(r.deviceFamily, onOpenAppSettings = { SystemSettingsIntents.openAppDetails(context) })

            InfoBanner(icon = Icons.Outlined.Info, text = stringResource(R.string.setup_cannot_change_note), tone = Tone.NEUTRAL)
        }
    }
}

@Composable
private fun CheckItem(title: String, detail: String, state: CheckState, actionLabel: String?, onAction: () -> Unit) {
    val tone = when (state) {
        CheckState.OK -> Tone.GOOD
        CheckState.WARN -> Tone.WARN
        CheckState.FAIL -> Tone.BAD
    }
    val (_, toneContent) = toneColors(tone)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when (state) {
                CheckState.OK -> Icons.Filled.CheckCircle
                CheckState.WARN -> Icons.Filled.Warning
                CheckState.FAIL -> Icons.Filled.Error
            },
            contentDescription = stringResource(
                when (state) {
                    CheckState.OK -> R.string.cd_check_ok
                    CheckState.WARN -> R.string.cd_check_warn
                    CheckState.FAIL -> R.string.cd_check_fail
                },
            ),
            tint = toneContent,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (actionLabel != null) {
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(actionLabel) }
        }
    }
}

@Composable
private fun OemGuideCard(family: DeviceFamily, onOpenAppSettings: () -> Unit) {
    val guide = OemBatteryGuide.forFamily(family)
    SectionTitle(stringResource(R.string.setup_section_device))
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.PhoneAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(guide.title), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.padding(top = 8.dp))
        Text(stringResource(guide.path), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.padding(top = 8.dp))
        Text(stringResource(guide.note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.padding(top = 12.dp))
        OutlinedButton(
            onClick = onOpenAppSettings,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
        ) { Text(stringResource(R.string.action_open_app_settings)) }
    }
}
