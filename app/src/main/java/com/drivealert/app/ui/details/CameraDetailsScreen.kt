package com.drivealert.app.ui.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.drivealert.app.R
import com.drivealert.app.alerts.AlertTextFormatter
import com.drivealert.app.cameras.CameraRepository
import com.drivealert.app.domain.model.Camera
import com.drivealert.app.domain.model.DataSource
import com.drivealert.app.domain.model.VerificationStatus
import com.drivealert.app.navigation.CameraDetailsRoute
import com.drivealert.app.ui.components.BackTopBar
import com.drivealert.app.ui.components.CameraTypeBadge
import com.drivealert.app.ui.components.InfoBanner
import com.drivealert.app.ui.components.LabeledValue
import com.drivealert.app.ui.components.PrimaryActionButton
import com.drivealert.app.ui.components.SectionCard
import com.drivealert.app.ui.components.StatusChip
import com.drivealert.app.ui.components.Tone
import com.drivealert.app.ui.util.formatDateTime
import com.drivealert.app.ui.util.formatDirection
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface CameraDetailsState {
    data object Loading : CameraDetailsState
    data object NotFound : CameraDetailsState
    data class Loaded(val camera: Camera) : CameraDetailsState
}

@HiltViewModel
class CameraDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repository: CameraRepository,
) : ViewModel() {
    private val cameraId = savedStateHandle.toRoute<CameraDetailsRoute>().cameraId

    val state: StateFlow<CameraDetailsState> = repository.observeCamera(cameraId)
        .map { camera -> if (camera == null) CameraDetailsState.NotFound else CameraDetailsState.Loaded(camera) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CameraDetailsState.Loading)
}

@Composable
fun CameraDetailsScreen(
    onBack: () -> Unit,
    onReport: (String) -> Unit,
    viewModel: CameraDetailsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { BackTopBar(stringResource(R.string.camera_details_title), onBack) }) { padding ->
        when (val s = state) {
            CameraDetailsState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            CameraDetailsState.NotFound -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.camera_not_found), style = MaterialTheme.typography.bodyLarge)
            }
            is CameraDetailsState.Loaded -> CameraDetailsContent(s.camera, Modifier.padding(padding), onReport)
        }
    }
}

@Composable
private fun CameraDetailsContent(camera: Camera, modifier: Modifier, onReport: (String) -> Unit) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (camera.isTestData) {
            InfoBanner(icon = Icons.Filled.Science, text = stringResource(R.string.test_data_banner), tone = Tone.WARN)
        }
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CameraTypeBadge(camera.type)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(AlertTextFormatter.typeNameRes(camera.type)), style = MaterialTheme.typography.titleLarge)
                    Text(
                        camera.roadName ?: stringResource(R.string.road_unknown),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.padding(top = 12.dp))
            VerificationChip(camera.verificationStatus)
        }

        SectionCard {
            LabeledValue(
                stringResource(R.string.label_location),
                listOfNotNull(camera.roadName, camera.city, camera.state).joinToString(", ").ifEmpty { stringResource(R.string.road_unknown) },
            )
            LabeledValue(
                stringResource(R.string.label_coordinates),
                String.format(Locale.US, "%.5f, %.5f", camera.latitude, camera.longitude),
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            LabeledValue(stringResource(R.string.label_direction), formatDirection(camera.bearingDeg, camera.directionality))
            LabeledValue(
                stringResource(R.string.label_speed_limit),
                camera.speedLimitKmh?.let { stringResource(R.string.speed_limit_value, it) } ?: stringResource(R.string.value_not_known),
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            LabeledValue(
                stringResource(R.string.label_last_verified),
                camera.lastVerifiedAtMillis?.let { formatDateTime(it) } ?: stringResource(R.string.value_not_known),
            )
            LabeledValue(stringResource(R.string.label_confirmations), camera.verificationCount.toString())
            LabeledValue(stringResource(R.string.label_data_source), stringResource(dataSourceLabel(camera.dataSource)))
            camera.expiresAtMillis?.let { LabeledValue(stringResource(R.string.label_expires), formatDateTime(it)) }
        }

        InfoBanner(icon = Icons.Outlined.Info, text = stringResource(R.string.disclaimer_short), tone = Tone.NEUTRAL)

        PrimaryActionButton(text = stringResource(R.string.report_update), icon = Icons.Filled.Flag, onClick = { onReport(camera.id) })
    }
}

@Composable
private fun VerificationChip(status: VerificationStatus) {
    val (text, tone) = when (status) {
        VerificationStatus.VERIFIED -> R.string.status_verified to Tone.GOOD
        VerificationStatus.COMMUNITY_CONFIRMED -> R.string.status_community_confirmed to Tone.GOOD
        VerificationStatus.PENDING_VERIFICATION -> R.string.status_pending to Tone.WARN
        VerificationStatus.DISPUTED -> R.string.status_disputed to Tone.WARN
        VerificationStatus.REJECTED -> R.string.status_rejected to Tone.BAD
    }
    val icon = if (tone == Tone.GOOD) Icons.Filled.Verified else Icons.Filled.HourglassTop
    StatusChip(icon = icon, text = stringResource(text), tone = tone)
}

private fun dataSourceLabel(source: DataSource): Int = when (source) {
    DataSource.OFFICIAL_GOVERNMENT -> R.string.source_official
    DataSource.LICENSED_PROVIDER -> R.string.source_licensed
    DataSource.ADMINISTRATOR -> R.string.source_admin
    DataSource.COMMUNITY_MODERATED -> R.string.source_community
    DataSource.TEST_ONLY_SYNTHETIC -> R.string.source_test
}
