package com.drivealert.app.ui.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivealert.app.R
import com.drivealert.app.alerts.AlertTextFormatter
import com.drivealert.app.domain.model.CameraType
import com.drivealert.app.domain.reporting.DuplicateReason
import com.drivealert.app.domain.reporting.ReportType
import com.drivealert.app.ui.components.BackTopBar
import com.drivealert.app.ui.components.InfoBanner
import com.drivealert.app.ui.components.PrimaryActionButton
import com.drivealert.app.ui.components.SectionCard
import com.drivealert.app.ui.components.SectionTitle
import com.drivealert.app.ui.components.Tone
import java.util.Locale

@Composable
fun ReportScreen(onBack: () -> Unit, viewModel: ReportViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    Scaffold(topBar = { BackTopBar(stringResource(R.string.report_title), onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (ui.vehicleMoving) {
                InfoBanner(icon = Icons.Filled.DirectionsCar, text = stringResource(R.string.report_moving_warning), tone = Tone.WARN)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(value = ui.passengerConfirmed, role = Role.Checkbox, onValueChange = viewModel::setPassengerConfirmed),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = ui.passengerConfirmed, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.report_passenger_confirm), style = MaterialTheme.typography.bodyMedium)
                }
            }

            SectionTitle(stringResource(R.string.report_what_changed))
            SectionCard(modifier = Modifier.selectableGroup()) {
                ui.availableTypes.forEach { type ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .selectable(selected = ui.type == type, role = Role.RadioButton, onClick = { viewModel.selectType(type) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = ui.type == type, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(reportTypeLabel(type)), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            val type = ui.type
            if (type == ReportType.NEW_CAMERA || type == ReportType.TEMPORARY_MOBILE_CAMERA) {
                SectionTitle(stringResource(R.string.report_camera_type))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(CameraType.FIXED_SPEED, CameraType.RED_LIGHT, CameraType.ANPR).forEach { cameraType ->
                        FilterChip(
                            selected = ui.cameraType == cameraType,
                            onClick = { viewModel.setCameraType(cameraType) },
                            label = { Text(stringResource(AlertTextFormatter.typeNameRes(cameraType))) },
                        )
                    }
                }
            }
            if (type == ReportType.INCORRECT_SPEED_LIMIT) {
                OutlinedTextField(
                    value = ui.speedLimitText,
                    onValueChange = viewModel::setSpeedLimit,
                    label = { Text(stringResource(R.string.report_correct_speed_limit)) },
                    suffix = { Text(stringResource(R.string.unit_kmh)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (type?.needsLocation == true) {
                SectionCard {
                    Text(stringResource(R.string.report_location_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        ui.location?.let { String.format(Locale.US, "%.5f, %.5f", it.latitude, it.longitude) }
                            ?: stringResource(if (ui.locating) R.string.gps_searching else R.string.report_location_missing),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.padding(top = 8.dp))
                    OutlinedButton(onClick = viewModel::useCurrentLocation, enabled = !ui.locating) {
                        androidx.compose.material3.Icon(Icons.Filled.MyLocation, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.report_use_current_location))
                    }
                    Text(
                        stringResource(R.string.report_location_privacy),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            if (type != null) {
                OutlinedTextField(
                    value = ui.note,
                    onValueChange = viewModel::setNote,
                    label = { Text(stringResource(R.string.report_note)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            InfoBanner(icon = Icons.Outlined.Info, text = stringResource(R.string.report_moderation_note), tone = Tone.NEUTRAL)

            when (val outcome = ui.outcome) {
                is ReportOutcome.Queued -> InfoBanner(
                    icon = Icons.Filled.CheckCircle,
                    text = stringResource(if (outcome.serverConfigured) R.string.report_queued else R.string.report_saved_offline),
                    tone = Tone.GOOD,
                )
                is ReportOutcome.RateLimited -> InfoBanner(
                    icon = Icons.Filled.ErrorOutline,
                    text = stringResource(R.string.report_rate_limited, outcome.minutes),
                    tone = Tone.WARN,
                )
                is ReportOutcome.Duplicate -> InfoBanner(
                    icon = Icons.Filled.ErrorOutline,
                    text = stringResource(
                        when (outcome.reason) {
                            DuplicateReason.SAME_REPORT_RECENTLY -> R.string.report_duplicate_recent
                            DuplicateReason.CAMERA_ALREADY_KNOWN -> R.string.report_duplicate_known
                            DuplicateReason.NEAR_OWN_RECENT_REPORT -> R.string.report_duplicate_own
                        },
                    ),
                    tone = Tone.WARN,
                )
                is ReportOutcome.Invalid -> InfoBanner(icon = Icons.Filled.ErrorOutline, text = stringResource(R.string.report_invalid), tone = Tone.BAD)
                null -> Unit
            }

            PrimaryActionButton(
                text = stringResource(R.string.report_submit),
                icon = Icons.AutoMirrored.Filled.Send,
                enabled = ui.canSubmit && ui.outcome !is ReportOutcome.Queued,
                onClick = viewModel::submit,
            )
        }
    }
}

private fun reportTypeLabel(type: ReportType): Int = when (type) {
    ReportType.NEW_CAMERA -> R.string.report_type_new
    ReportType.CAMERA_REMOVED -> R.string.report_type_removed
    ReportType.INCORRECT_POSITION -> R.string.report_type_position
    ReportType.INCORRECT_DIRECTION -> R.string.report_type_direction
    ReportType.INCORRECT_SPEED_LIMIT -> R.string.report_type_speed_limit
    ReportType.TEMPORARY_MOBILE_CAMERA -> R.string.report_type_temporary
    ReportType.DUPLICATE_CAMERA -> R.string.report_type_duplicate
    ReportType.ABUSE -> R.string.report_type_abuse
}
