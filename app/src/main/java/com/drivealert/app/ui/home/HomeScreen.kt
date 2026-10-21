package com.drivealert.app.ui.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivealert.app.R
import com.drivealert.app.alerts.AlertTextFormatter
import com.drivealert.app.domain.alerts.AlertThresholdPolicy
import com.drivealert.app.domain.cameras.NearbyCamera
import com.drivealert.app.domain.cameras.NearbyCameras
import com.drivealert.app.domain.model.AlertSettings
import com.drivealert.app.domain.model.Camera
import com.drivealert.app.domain.model.GpsStatus
import com.drivealert.app.domain.model.SessionState
import com.drivealert.app.domain.model.TrackingMode
import com.drivealert.app.permissions.LocationPermissions
import com.drivealert.app.ui.components.AlertDistanceSelector
import com.drivealert.app.ui.components.BrandHeader
import com.drivealert.app.ui.components.CameraTypeBadge
import com.drivealert.app.ui.components.InfoBanner
import com.drivealert.app.ui.components.PrimaryActionButton
import com.drivealert.app.ui.components.SectionCard
import com.drivealert.app.ui.components.StatusChip
import com.drivealert.app.ui.components.ToggleRow
import com.drivealert.app.ui.components.Tone
import com.drivealert.app.ui.util.formatBearingFromYou
import com.drivealert.app.ui.util.formatDistance
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private const val MAP_ITEM_KEY = "map"
private const val NEARBY_ITEM_KEY = "nearby"

/** Rows shown before "Show all", so the list does not push the quick settings far down. */
private const val COLLAPSED_NEARBY_ROWS = 5
private const val MAX_NEARBY_ROWS = 10

/**
 * Worst fix accuracy for which distances and directions "from you" are shown. Weaker fixes can be
 * hundreds of metres off. Same limit as "From your position" on Camera details.
 */
private const val MAX_FROM_YOU_ACCURACY_M = 200f

@Composable
fun HomeScreen(
    onStartAlerts: () -> Unit,
    onOpenCamera: (String) -> Unit,
    onOpenSetup: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Edge-to-edge: the list draws under the transparent status bar (only BrandHeader pads itself).
    val statusBars = WindowInsets.statusBars
    val density = LocalDensity.current

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { viewModel.onPause() }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.refresh()
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "header") {
            BrandHeader(title = stringResource(R.string.app_name), subtitle = stringResource(R.string.app_tagline)) {
                Spacer(Modifier.height(16.dp))
                SearchField(query = ui.searchQuery, onQueryChange = viewModel::onSearchQueryChange)
            }
        }
        if (ui.searchResults.isNotEmpty()) {
            item(key = "search") {
                SearchResults(
                    results = ui.searchResults,
                    onSelect = {
                        viewModel.onSearchResultSelected(it)
                        onOpenCamera(it.id)
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        item(key = "banners") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ui.sessionEndedUnexpectedly) {
                    InfoBanner(
                        icon = Icons.Filled.BatteryAlert,
                        text = stringResource(R.string.banner_session_killed),
                        tone = Tone.WARN,
                        actionLabel = stringResource(R.string.action_fix),
                        onAction = {
                            viewModel.dismissUnexpectedStop()
                            onOpenSetup()
                        },
                    )
                }
                if (!ui.hasPreciseLocation) {
                    InfoBanner(
                        icon = Icons.Filled.LocationOff,
                        text = stringResource(if (ui.hasLocationPermission) R.string.banner_precise_needed else R.string.banner_location_needed),
                        tone = Tone.BAD,
                        actionLabel = stringResource(R.string.action_allow),
                        onAction = { permissionLauncher.launch(LocationPermissions.FOREGROUND_LOCATION) },
                    )
                } else if (!ui.locationEnabled) {
                    InfoBanner(
                        icon = Icons.Filled.GpsOff,
                        text = stringResource(R.string.banner_location_off),
                        tone = Tone.BAD,
                        actionLabel = stringResource(R.string.action_turn_on),
                        onAction = { com.drivealert.app.battery.SystemSettingsIntents.openLocationSettings(context) },
                    )
                } else if (ui.setupWarnings > 0) {
                    InfoBanner(
                        icon = Icons.Filled.Warning,
                        text = pluralStringResource(R.plurals.banner_setup_warnings, ui.setupWarnings, ui.setupWarnings),
                        tone = Tone.WARN,
                        actionLabel = stringResource(R.string.action_review),
                        onAction = onOpenSetup,
                    )
                }
            }
        }
        item(key = MAP_ITEM_KEY, contentType = "map") {
            MapCard(ui = ui, session = session, onOpenCamera = onOpenCamera, onMapIdle = viewModel::onMapIdle, onFocusConsumed = viewModel::onFocusConsumed)
        }
        item(key = "session") {
            Box(Modifier.padding(horizontal = 16.dp)) {
                val active = session as? SessionState.Active
                if (active != null) {
                    ActiveSessionCard(
                        state = active,
                        onOpenCamera = onOpenCamera,
                        onOpenMaps = { openMaps(context) },
                        onStop = viewModel::stopAlerts,
                    )
                } else {
                    PrimaryActionButton(
                        text = stringResource(R.string.start_camera_alerts),
                        icon = Icons.Filled.PlayArrow,
                        onClick = {
                            if (ui.hasPreciseLocation) onStartAlerts() else permissionLauncher.launch(LocationPermissions.FOREGROUND_LOCATION)
                        },
                    )
                }
            }
        }
        // Below the Start / Stop controls, so the list never pushes them off screen. Hidden during a
        // drive (the active-session card shows the live next camera; Home's one-off position is stale),
        // without location access, and until the list was computed for a recent fix (no "none nearby" flash).
        val showNearby = session !is SessionState.Active &&
            ui.hasLocationPermission && ui.locationEnabled &&
            ui.myLocation != null && ui.nearbyLoaded
        if (showNearby) {
            item(key = NEARBY_ITEM_KEY) {
                NearbyCamerasCard(
                    cameras = ui.nearbyCameras,
                    hasPreciseLocation = ui.hasPreciseLocation,
                    accuracyM = ui.accuracyM,
                    hasCameraData = ui.totalCameras > 0,
                    onOpenCamera = onOpenCamera,
                    onShowOnMap = { camera ->
                        viewModel.onSearchResultSelected(camera)
                        scope.launch { listState.revealMapCard(topInsetPx = statusBars.getTop(density)) }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        item(key = "quick-settings") {
            QuickSettingsCard(
                settings = settings,
                onVoiceChange = viewModel::setVoiceAlerts,
                onDistanceChange = viewModel::setAlertDistance,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        item(key = "disclaimer") {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.disclaimer_short),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium),
        placeholder = { Text(stringResource(R.string.search_hint)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_clear_search))
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedIndicatorColor = MaterialTheme.colorScheme.surface,
            unfocusedIndicatorColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

@Composable
private fun SearchResults(results: List<Camera>, onSelect: (Camera) -> Unit, modifier: Modifier = Modifier) {
    SectionCard(modifier = modifier, contentPadding = PaddingValues(vertical = 4.dp)) {
        results.take(8).forEachIndexed { index, camera ->
            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable { onSelect(camera) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CameraTypeBadge(camera.type)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        camera.roadName ?: stringResource(AlertTextFormatter.typeNameRes(camera.type)),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(camera.city, camera.state).joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MapCard(
    ui: HomeUiState,
    session: SessionState,
    onOpenCamera: (String) -> Unit,
    onMapIdle: (com.drivealert.app.domain.geo.BoundingBox, Float) -> Unit,
    onFocusConsumed: () -> Unit,
) {
    // All nearby cameras, nearest first: the map frames the first three itself and draws every listed
    // camera unclustered. Same list instance until they change, so unrelated UI updates do not hand
    // the map a "new" list.
    val nearestCameras = remember(ui.nearbyCameras) { ui.nearbyCameras.map { it.camera } }
    SectionCard(modifier = Modifier.padding(horizontal = 16.dp), contentPadding = PaddingValues(0.dp)) {
        CameraMap(
            cameras = ui.mapCameras,
            myLocation = ui.myLocation,
            myLocationAccuracyM = ui.accuracyM,
            nearestCameras = nearestCameras,
            focusPoint = ui.focusPoint,
            showMyLocation = ui.hasLocationPermission,
            onCameraClick = onOpenCamera,
            onMapIdle = onMapIdle,
            onFocusConsumed = onFocusConsumed,
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(MaterialTheme.shapes.large),
        )
        FlowRow(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GpsChip(ui, session)
            val nearby = (session as? SessionState.Active)?.nearbyCameraCount ?: ui.nearbyCount
            StatusChip(
                icon = Icons.Filled.PhotoCamera,
                text = pluralStringResource(R.plurals.cameras_nearby, nearby, nearby),
                tone = Tone.NEUTRAL,
            )
            if (ui.totalCameras == 0) {
                StatusChip(icon = Icons.Outlined.Info, text = stringResource(R.string.chip_no_camera_data), tone = Tone.WARN)
            }
        }
    }
}

@Composable
private fun GpsChip(ui: HomeUiState, session: SessionState) {
    val active = session as? SessionState.Active
    when {
        !ui.hasLocationPermission -> StatusChip(Icons.Filled.LocationOff, stringResource(R.string.gps_permission_needed), Tone.BAD)
        !ui.locationEnabled -> StatusChip(Icons.Filled.GpsOff, stringResource(R.string.gps_location_off), Tone.BAD)
        active != null -> when (active.gpsStatus) {
            GpsStatus.GOOD -> StatusChip(Icons.Filled.GpsFixed, stringResource(R.string.gps_ready), Tone.GOOD)
            GpsStatus.WEAK -> StatusChip(Icons.Filled.GpsNotFixed, stringResource(R.string.gps_weak, (active.accuracyM ?: 0f).toInt()), Tone.WARN)
            GpsStatus.LOST -> StatusChip(Icons.Filled.GpsOff, stringResource(R.string.gps_lost), Tone.WARN)
            GpsStatus.ACQUIRING -> StatusChip(Icons.Filled.GpsNotFixed, stringResource(R.string.gps_searching), Tone.NEUTRAL)
        }
        ui.accuracyM == null -> StatusChip(Icons.Filled.GpsNotFixed, stringResource(R.string.gps_searching), Tone.NEUTRAL)
        ui.accuracyM <= 25f -> StatusChip(Icons.Filled.GpsFixed, stringResource(R.string.gps_ready), Tone.GOOD)
        else -> StatusChip(Icons.Filled.GpsNotFixed, stringResource(R.string.gps_weak, ui.accuracyM.toInt()), Tone.WARN)
    }
}

/** "Cameras near you": nearest first, with distance and direction from the user's position. */
@Composable
private fun NearbyCamerasCard(
    cameras: List<NearbyCamera>,
    hasPreciseLocation: Boolean,
    accuracyM: Float?,
    hasCameraData: Boolean,
    onOpenCamera: (String) -> Unit,
    onShowOnMap: (Camera) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    SectionCard(modifier = modifier, contentPadding = PaddingValues(vertical = 8.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.NearMe, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.nearby_cameras_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
        }
        when {
            // Approximate-only locations are blurred by about 2 km: distances and directions would be wrong.
            !hasPreciseLocation -> NearbyNote(stringResource(R.string.nearby_needs_precise))
            // Accuracy not known yet: same wording as the GPS chip.
            accuracyM == null -> NearbyNote(stringResource(R.string.gps_searching))
            accuracyM > MAX_FROM_YOU_ACCURACY_M -> NearbyNote(stringResource(R.string.nearby_low_accuracy, accuracyM.roundToInt()))
            cameras.isEmpty() -> NearbyNote(
                if (hasCameraData) {
                    stringResource(R.string.nearby_cameras_empty, (NearbyCameras.DEFAULT_RADIUS_M / 1_000).toInt())
                } else {
                    stringResource(R.string.nearby_cameras_no_data)
                },
            )
            else -> {
                val total = cameras.size.coerceAtMost(MAX_NEARBY_ROWS)
                cameras.take(if (expanded) total else COLLAPSED_NEARBY_ROWS).forEachIndexed { index, nearby ->
                    if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    NearbyCameraRow(
                        nearby = nearby,
                        onOpen = { onOpenCamera(nearby.camera.id) },
                        onShowOnMap = { onShowOnMap(nearby.camera) },
                    )
                }
                if (total > COLLAPSED_NEARBY_ROWS) {
                    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.padding(horizontal = 4.dp)) {
                        Text(
                            if (expanded) stringResource(R.string.nearby_show_fewer) else stringResource(R.string.nearby_show_all, total),
                        )
                    }
                }
            }
        }
    }
}

/** One explanatory line under the card title, shown instead of the rows. */
@Composable
private fun NearbyNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun NearbyCameraRow(nearby: NearbyCamera, onOpen: () -> Unit, onShowOnMap: () -> Unit) {
    val camera = nearby.camera
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onOpen)
            // End inset 4 dp: the 48 dp icon button's own padding lines its icon up with the 16 dp gutter.
            .padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CameraTypeBadge(camera.type)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(AlertTextFormatter.typeNameRes(camera.type)),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Rounded like spoken alerts (50 m steps below 1 km, 100 m above), matching Camera details.
            val fromYou = formatBearingFromYou(AlertThresholdPolicy.roundForAnnouncement(nearby.distanceM), nearby.bearingFromUserDeg)
            val limit = camera.speedLimitKmh
            Text(
                if (limit != null) stringResource(R.string.nearby_camera_with_limit, fromYou, limit) else fromYou,
                style = MaterialTheme.typography.bodyMedium,
            )
            camera.roadName?.takeIf { it.isNotBlank() }?.let { road ->
                Text(
                    road,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onShowOnMap) {
            Icon(Icons.Outlined.Place, contentDescription = stringResource(R.string.cd_show_on_map), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * Scrolls the map card fully on screen (if it is not already) so "Show on map" is seen.
 * The map is above the nearby list, whose button was just tapped: when the map is not laid
 * out at all, jump to the top of the list first, then bring any cut-off part into view
 * without hiding the map's top. [topInsetPx] is the status bar height: the list is drawn
 * edge-to-edge, so its first [topInsetPx] pixels are behind the transparent status bar.
 */
private suspend fun LazyListState.revealMapCard(topInsetPx: Int) {
    if (layoutInfo.visibleItemsInfo.none { it.key == MAP_ITEM_KEY }) animateScrollToItem(0)
    val info = layoutInfo
    val map = info.visibleItemsInfo.firstOrNull { it.key == MAP_ITEM_KEY } ?: return
    val hiddenAbove = info.viewportStartOffset + topInsetPx - map.offset
    val hiddenBelow = map.offset + map.size - info.viewportEndOffset
    when {
        hiddenAbove > 0 -> animateScrollBy(-hiddenAbove.toFloat())
        hiddenBelow > 0 -> animateScrollBy(minOf(hiddenBelow, -hiddenAbove).toFloat())
    }
}

@Composable
private fun ActiveSessionCard(
    state: SessionState.Active,
    onOpenCamera: (String) -> Unit,
    onOpenMaps: () -> Unit,
    onStop: () -> Unit,
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(12.dp)
                    .background(com.drivealert.app.ui.theme.BrandColors.Success, CircleShape),
            )
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.camera_alerts_active), style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(
                when (state.trackingMode) {
                    TrackingMode.CLOSE -> R.string.mode_close_long
                    TrackingMode.APPROACH -> R.string.mode_approach_long
                    TrackingMode.PASSIVE, TrackingMode.OFF -> R.string.mode_passive_long
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val next = state.nextCamera
        if (next != null) {
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onOpenCamera(next.camera.id) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CameraTypeBadge(next.camera.type)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(AlertTextFormatter.titleRes(next.camera.type)), style = MaterialTheme.typography.titleMedium)
                    val limit = next.camera.speedLimitKmh
                    Text(
                        if (limit != null) {
                            stringResource(R.string.alert_body_with_limit, formatDistance(next.distanceM), limit)
                        } else {
                            formatDistance(next.distanceM)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.speedKmh?.let { stringResource(R.string.current_speed_value, it) } ?: stringResource(R.string.current_speed_unknown),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(stringResource(R.string.drive_safely), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = onOpenMaps,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            Icon(Icons.Filled.Navigation, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.open_maps))
        }
        Spacer(Modifier.height(8.dp))
        PrimaryActionButton(text = stringResource(R.string.action_stop_alerts), icon = Icons.Filled.Stop, onClick = onStop, destructive = true)
    }
}

@Composable
private fun QuickSettingsCard(
    settings: AlertSettings,
    onVoiceChange: (Boolean) -> Unit,
    onDistanceChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(modifier = modifier) {
        ToggleRow(
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            title = stringResource(R.string.voice_alerts),
            subtitle = stringResource(R.string.voice_alerts_subtitle),
            checked = settings.voiceAlerts && !settings.visualOnly,
            enabled = !settings.visualOnly,
            onCheckedChange = onVoiceChange,
        )
        HorizontalDivider()
        Row(Modifier.padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Straighten, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Text(
                stringResource(R.string.alert_distance_value, formatDistance(settings.primaryAlertDistanceM)),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        AlertDistanceSelector(selectedM = settings.primaryAlertDistanceM, onSelect = onDistanceChange)
    }
}

/** Opens Google Maps if installed, otherwise any map/navigation app. */
private fun openMaps(context: Context) {
    val maps = context.packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")
    val intent = maps ?: Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"))
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: android.content.ActivityNotFoundException) {
        // No map app installed; nothing to open.
    }
}
