package com.drivealert.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.drivealert.app.BuildConfig
import com.drivealert.app.R
import com.drivealert.app.alerts.AlertTextFormatter
import com.drivealert.app.domain.geo.BoundingBox
import com.drivealert.app.domain.geo.GeoPoint
import com.drivealert.app.domain.model.Camera
import com.drivealert.app.domain.model.VerificationStatus
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState

private val INDIA_CENTER = LatLng(22.5, 79.0)

/**
 * Map with the user's location and camera markers. Uses the Google Maps SDK
 * inside Drive Alert only; it never reads anything from the Google Maps app.
 */
@Composable
fun CameraMap(
    cameras: List<Camera>,
    myLocation: GeoPoint?,
    focusPoint: GeoPoint?,
    showMyLocation: Boolean,
    onCameraClick: (String) -> Unit,
    onMapIdle: (BoundingBox, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!BuildConfig.MAPS_CONFIGURED) {
        MapUnavailable(modifier)
        return
    }
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(INDIA_CENTER, 4.5f)
    }
    var centeredOnUser by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(myLocation) {
        if (myLocation != null && !centeredOnUser) {
            cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(LatLng(myLocation.latitude, myLocation.longitude), 13f))
            centeredOnUser = true
        }
    }
    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(LatLng(focusPoint.latitude, focusPoint.longitude), 15f))
        }
    }
    LaunchedEffect(cameraPositionState.isMoving) {
        if (!cameraPositionState.isMoving) {
            val bounds = cameraPositionState.projection?.visibleRegion?.latLngBounds ?: return@LaunchedEffect
            onMapIdle(
                BoundingBox(bounds.southwest.latitude, bounds.northeast.latitude, bounds.southwest.longitude, bounds.northeast.longitude),
                cameraPositionState.position.zoom,
            )
        }
    }

    GoogleMap(
        modifier = modifier,
        cameraPositionState = cameraPositionState,
        properties = MapProperties(isMyLocationEnabled = showMyLocation),
        uiSettings = MapUiSettings(
            zoomControlsEnabled = false,
            myLocationButtonEnabled = showMyLocation,
            mapToolbarEnabled = false,
            compassEnabled = true,
        ),
    ) {
        cameras.forEach { camera ->
            key(camera.id) {
                val markerState = remember(camera.id) { MarkerState(position = LatLng(camera.latitude, camera.longitude)) }
                val title = stringResource(AlertTextFormatter.typeNameRes(camera.type))
                val snippet = listOfNotNull(camera.roadName, camera.speedLimitKmh?.let { stringResource(R.string.speed_limit_value, it) })
                    .joinToString(" • ")
                Marker(
                    state = markerState,
                    title = title,
                    snippet = snippet.ifEmpty { null },
                    icon = BitmapDescriptorFactory.defaultMarker(markerHue(camera)),
                    onInfoWindowClick = { onCameraClick(camera.id) },
                )
            }
        }
    }
}

private fun markerHue(camera: Camera): Float = when (camera.verificationStatus) {
    VerificationStatus.VERIFIED, VerificationStatus.COMMUNITY_CONFIRMED -> BitmapDescriptorFactory.HUE_AZURE
    VerificationStatus.PENDING_VERIFICATION -> BitmapDescriptorFactory.HUE_VIOLET
    VerificationStatus.DISPUTED, VerificationStatus.REJECTED -> BitmapDescriptorFactory.HUE_ROSE
}

@Composable
private fun MapUnavailable(modifier: Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
        ) {
            Icon(Icons.Outlined.Map, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.map_unavailable_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(if (BuildConfig.DEBUG) R.string.map_unavailable_debug else R.string.map_unavailable_body),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
