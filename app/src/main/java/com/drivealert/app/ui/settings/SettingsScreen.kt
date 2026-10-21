package com.drivealert.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsPaused
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivealert.app.BuildConfig
import com.drivealert.app.R
import com.drivealert.app.battery.SystemSettingsIntents
import com.drivealert.app.data.osm.OsmConfig
import com.drivealert.app.data.testdata.SimulationState
import com.drivealert.app.ui.components.AlertDistanceSelector
import com.drivealert.app.ui.components.BrandHeader
import com.drivealert.app.ui.components.InfoBanner
import com.drivealert.app.ui.components.NavRow
import com.drivealert.app.ui.components.SectionCard
import com.drivealert.app.ui.components.SectionTitle
import com.drivealert.app.ui.components.ToggleRow
import com.drivealert.app.ui.components.Tone
import com.drivealert.app.ui.util.formatDateTime
import com.drivealert.app.ui.util.formatDistance

@Composable
fun SettingsScreen(
    onOpenSetup: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenPackages: () -> Unit,
    onReportNewCamera: () -> Unit,
    onOpenLicences: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.alertSettings.collectAsStateWithLifecycle()
    val prefs by viewModel.appPreferences.collectAsStateWithLifecycle()
    val lastUpdated by viewModel.lastUpdated.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val simulation by viewModel.simulation.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(4_000)
            viewModel.consumeMessage()
        }
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { BrandHeader(title = stringResource(R.string.settings_title), subtitle = stringResource(R.string.settings_subtitle)) }

        message?.let { msg ->
            item {
                Box(Modifier.padding(horizontal = 16.dp)) {
                    InfoBanner(
                        icon = Icons.Outlined.Info,
                        text = stringResource(
                            when (msg) {
                                SettingsMessage.SYNC_REQUESTED -> R.string.msg_sync_requested
                                SettingsMessage.HISTORY_DELETED -> R.string.msg_history_deleted
                                SettingsMessage.NO_LOCATION -> R.string.msg_no_location
                                SettingsMessage.TEST_DATA_LOADED -> R.string.msg_test_loaded
                                SettingsMessage.TEST_DATA_CLEARED -> R.string.msg_test_cleared
                                SettingsMessage.SIMULATION_STARTED -> R.string.msg_simulation_started
                                SettingsMessage.MOCK_LOCATION_NEEDED -> R.string.msg_mock_location_needed
                            },
                        ),
                        tone = Tone.GOOD,
                    )
                }
            }
        }

        item {
            Box(Modifier.padding(horizontal = 16.dp)) {
                androidx.compose.foundation.layout.Column {
                    SectionTitle(stringResource(R.string.settings_section_alerts))
                    SectionCard {
                        ToggleRow(
                            Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.voice_alerts), settings.voiceAlerts, viewModel::setVoice,
                            subtitle = stringResource(R.string.voice_alerts_subtitle), enabled = !settings.visualOnly,
                        )
                        HorizontalDivider()
                        ToggleRow(Icons.Filled.MusicNote, stringResource(R.string.alert_sound), settings.alertSound, viewModel::setSound, enabled = !settings.visualOnly)
                        HorizontalDivider()
                        ToggleRow(Icons.Filled.Vibration, stringResource(R.string.vibration), settings.vibration, viewModel::setVibration, enabled = !settings.visualOnly)
                        HorizontalDivider()
                        ToggleRow(
                            Icons.Filled.VisibilityOff, stringResource(R.string.visual_only), settings.visualOnly, viewModel::setVisualOnly,
                            subtitle = stringResource(R.string.visual_only_subtitle),
                        )
                        HorizontalDivider()
                        Text(
                            stringResource(R.string.alert_distance_value, formatDistance(settings.primaryAlertDistanceM)),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                        )
                        AlertDistanceSelector(selectedM = settings.primaryAlertDistanceM, onSelect = viewModel::setDistance)
                        Text(
                            stringResource(R.string.alert_distance_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        HorizontalDivider(Modifier.padding(top = 8.dp))
                        ToggleRow(
                            Icons.Filled.NotificationsActive, stringResource(R.string.closer_reminders), settings.closerReminders, viewModel::setCloserReminders,
                            subtitle = stringResource(R.string.closer_reminders_subtitle),
                        )
                        HorizontalDivider()
                        ToggleRow(
                            Icons.Outlined.Campaign, stringResource(R.string.include_unverified), settings.includeUnverified, viewModel::setIncludeUnverified,
                            subtitle = stringResource(R.string.include_unverified_subtitle),
                        )
                    }

                    SectionTitle(stringResource(R.string.settings_section_data))
                    SectionCard {
                        NavRow(Icons.Filled.Storage, stringResource(R.string.data_packages), stringResource(R.string.data_packages_subtitle), onOpenPackages)
                        HorizontalDivider()
                        NavRow(
                            Icons.Filled.Sync,
                            stringResource(R.string.update_now),
                            lastUpdated?.let { stringResource(R.string.database_last_updated, formatDateTime(it)) }
                                ?: stringResource(R.string.database_never_updated),
                        ) { viewModel.syncNow() }
                        HorizontalDivider()
                        ToggleRow(
                            Icons.Filled.SignalCellularAlt, stringResource(R.string.mobile_data_sync), prefs.allowMobileDataSync, viewModel::setMobileDataSync,
                            subtitle = stringResource(R.string.mobile_data_sync_subtitle),
                        )
                        HorizontalDivider()
                        NavRow(Icons.Filled.AddLocationAlt, stringResource(R.string.report_new_camera), stringResource(R.string.report_new_camera_subtitle), onReportNewCamera)
                    }

                    SectionTitle(stringResource(R.string.settings_section_device))
                    SectionCard {
                        NavRow(Icons.Filled.BatteryChargingFull, stringResource(R.string.setup_assistant), stringResource(R.string.setup_assistant_subtitle), onOpenSetup)
                        HorizontalDivider()
                        NavRow(Icons.Outlined.NotificationsPaused, stringResource(R.string.notification_settings), null) {
                            SystemSettingsIntents.openNotificationSettings(context)
                        }
                        HorizontalDivider()
                        NavRow(Icons.Filled.Language, stringResource(R.string.app_language), stringResource(R.string.app_language_subtitle)) {
                            SystemSettingsIntents.openAppLanguageSettings(context)
                        }
                    }

                    SectionTitle(stringResource(R.string.settings_section_privacy))
                    SectionCard {
                        NavRow(Icons.Filled.Policy, stringResource(R.string.privacy_policy), null, onOpenPrivacy)
                        HorizontalDivider()
                        NavRow(Icons.Filled.DeleteForever, stringResource(R.string.delete_local_history), stringResource(R.string.delete_local_history_subtitle)) {
                            confirmDelete = true
                        }
                    }

                    if (viewModel.testToolsAvailable) {
                        SectionTitle(stringResource(R.string.settings_section_debug))
                        SectionCard {
                            InfoBanner(Icons.Filled.Science, stringResource(R.string.debug_test_data_warning), Tone.WARN)
                            NavRow(Icons.Filled.CloudDownload, stringResource(R.string.debug_load_test_cameras), stringResource(R.string.debug_load_test_cameras_subtitle), viewModel::loadTestCameras)
                            HorizontalDivider()
                            NavRow(Icons.Filled.DeleteForever, stringResource(R.string.debug_clear_test_cameras), null, viewModel::clearTestCameras)
                            if (viewModel.simulatorAvailable) {
                                HorizontalDivider()
                                when (val sim = simulation) {
                                    is SimulationState.Starting -> NavRow(
                                        Icons.Filled.Stop,
                                        stringResource(R.string.debug_stop_simulation),
                                        stringResource(R.string.debug_simulation_starting),
                                        viewModel::stopSimulation,
                                    )
                                    is SimulationState.Running -> NavRow(
                                        Icons.Filled.Stop,
                                        stringResource(R.string.debug_stop_simulation),
                                        stringResource(R.string.debug_simulation_progress, sim.travelledM / 1000.0, sim.totalM / 1000.0, sim.speedKmh),
                                        viewModel::stopSimulation,
                                    )
                                    else -> NavRow(
                                        Icons.Filled.DirectionsCar,
                                        stringResource(R.string.debug_simulate_drive),
                                        stringResource(R.string.debug_simulate_drive_subtitle),
                                        viewModel::startSimulation,
                                    )
                                }
                                NavRow(Icons.Filled.DeveloperMode, stringResource(R.string.debug_open_developer_options), stringResource(R.string.debug_open_developer_options_subtitle)) {
                                    SystemSettingsIntents.openDeveloperOptions(context)
                                }
                            }
                        }
                    }

                    SectionTitle(stringResource(R.string.settings_section_about))
                    SectionCard {
                        Text(stringResource(R.string.disclaimer_full), style = MaterialTheme.typography.bodyMedium)
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        NavRow(Icons.Filled.Public, stringResource(R.string.open_data_licences), stringResource(R.string.open_data_licences_subtitle)) {
                            SystemSettingsIntents.openUrl(context, OsmConfig.COPYRIGHT_URL)
                        }
                        NavRow(Icons.Filled.Description, stringResource(R.string.open_source_licences), stringResource(R.string.open_source_licences_subtitle), onOpenLicences)
                        NavRow(Icons.Filled.Code, stringResource(R.string.source_code), stringResource(R.string.source_code_subtitle)) {
                            SystemSettingsIntents.openUrl(context, "https://github.com/Ansh1040/map")
                        }
                        Text(
                            stringResource(R.string.version_label, BuildConfig.VERSION_NAME),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_local_history)) },
            text = { Text(stringResource(R.string.delete_local_history_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteLocalHistory()
                    confirmDelete = false
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
