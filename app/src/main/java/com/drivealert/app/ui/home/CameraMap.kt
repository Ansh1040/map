package com.drivealert.app.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.location.Location
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.drivealert.app.R
import com.drivealert.app.battery.SystemSettingsIntents
import com.drivealert.app.data.osm.OsmConfig
import com.drivealert.app.domain.geo.BoundingBox
import com.drivealert.app.domain.geo.GeoPoint
import com.drivealert.app.domain.model.Camera
import com.drivealert.app.domain.model.VerificationStatus
import com.drivealert.app.permissions.LocationPermissions
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

private val INDIA_CENTER = LatLng(22.5, 79.0)
private const val SOURCE_ID = "drive-alert-cameras"
private const val LAYER_ID = "drive-alert-cameras-layer"

/**
 * Map with the user's location and camera markers, rendered by MapLibre
 * (open source) with OpenFreeMap vector tiles (free, no API key). The map is
 * only a preview: it never reads anything from the Google Maps app, and
 * camera alerts work without it (including offline).
 *
 * Attribution: a permanently visible credit (links to the OSM copyright page)
 * plus MapLibre's attribution dialog.
 */
@Composable
fun CameraMap(
    cameras: List<Camera>,
    myLocation: GeoPoint?,
    myLocationAccuracyM: Float?,
    focusPoint: GeoPoint?,
    showMyLocation: Boolean,
    onCameraClick: (String) -> Unit,
    onMapIdle: (BoundingBox, Float) -> Unit,
    onFocusConsumed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).apply { onCreate(null) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var centeredOnUser by rememberSaveable { mutableStateOf(false) }
    // Last camera position (lat, lon, zoom), or the destination of an animation just
    // started: a recreated MapView (navigation back, rotation, scrolling the card away)
    // reopens where the user left it.
    var savedCamera by rememberSaveable { mutableStateOf<List<Double>?>(null) }
    val latestOnClick by rememberUpdatedState(onCameraClick)
    val latestOnIdle by rememberUpdatedState(onMapIdle)
    val latestOnFocusConsumed by rememberUpdatedState(onFocusConsumed)
    val tapRadiusPx = with(LocalDensity.current) { 24.dp.toPx() }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.addOnDidFailLoadingMapListener { loadFailed = true }
        mapView.getMapAsync { m ->
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            val start = savedCamera
            m.cameraPosition = if (start != null && start.size == 3) {
                CameraPosition.Builder().target(LatLng(start[0], start[1])).zoom(start[2]).build()
            } else {
                CameraPosition.Builder().target(INDIA_CENTER).zoom(4.0).build()
            }
            m.addOnCameraIdleListener {
                val bounds = m.projection.visibleRegion.latLngBounds
                val target = m.cameraPosition.target
                if (target != null) savedCamera = listOf(target.latitude, target.longitude, m.cameraPosition.zoom)
                latestOnIdle(
                    BoundingBox(bounds.latitudeSouth, bounds.latitudeNorth, bounds.longitudeWest, bounds.longitudeEast),
                    m.cameraPosition.zoom.toFloat(),
                )
            }
            m.addOnMapClickListener { point ->
                // 48 dp touch target around the tap; pick the marker nearest the finger.
                val screen = m.projection.toScreenLocation(point)
                val area = RectF(screen.x - tapRadiusPx, screen.y - tapRadiusPx, screen.x + tapRadiusPx, screen.y + tapRadiusPx)
                val id = m.queryRenderedFeatures(area, LAYER_ID)
                    .mapNotNull { feature ->
                        val geometry = feature.geometry() as? Point ?: return@mapNotNull null
                        val pos = m.projection.toScreenLocation(LatLng(geometry.latitude(), geometry.longitude()))
                        val dx = pos.x - screen.x
                        val dy = pos.y - screen.y
                        feature.getStringProperty("id")?.let { it to (dx * dx + dy * dy) }
                    }
                    .minByOrNull { it.second }
                    ?.first
                if (id != null) latestOnClick(id)
                id != null
            }
            m.setStyle(Style.Builder().fromUri(OsmConfig.mapStyleUrl)) { loaded ->
                loaded.addSource(GeoJsonSource(SOURCE_ID, FeatureCollection.fromFeatures(emptyList<Feature>())))
                loaded.addLayer(cameraLayer())
                style = loaded
                loadFailed = false
            }
            map = m
        }
    }

    LaunchedEffect(map, style, showMyLocation, myLocation) {
        val m = map ?: return@LaunchedEffect
        val s = style ?: return@LaunchedEffect
        enableLocationComponent(context, m, s, showMyLocation, myLocation, myLocationAccuracyM)
    }

    LaunchedEffect(style, cameras) {
        style?.getSourceAs<GeoJsonSource>(SOURCE_ID)?.setGeoJson(featureCollection(cameras))
    }

    LaunchedEffect(map, myLocation) {
        val m = map ?: return@LaunchedEffect
        if (myLocation != null && !centeredOnUser) {
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(myLocation.latitude, myLocation.longitude), 13.0))
            // Saved now, not only on idle: the map may be paused or recreated before the animation ends.
            savedCamera = listOf(myLocation.latitude, myLocation.longitude, 13.0)
            centeredOnUser = true
        }
    }

    LaunchedEffect(map, focusPoint) {
        val m = map ?: return@LaunchedEffect
        if (focusPoint != null) {
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(focusPoint.latitude, focusPoint.longitude), 15.0))
            // Saved before the focus is consumed: opening the result's details can stop the
            // animation before it reaches idle, and the map recreated after Back must open there.
            savedCamera = listOf(focusPoint.latitude, focusPoint.longitude, 15.0)
            latestOnFocusConsumed()
        }
    }

    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        if (loadFailed) MapUnavailable(Modifier.fillMaxSize())
        // Always-visible data/tile credit (ODbL and OpenMapTiles/OpenFreeMap terms).
        Text(
            text = stringResource(R.string.map_attribution),
            style = MaterialTheme.typography.labelSmall,
            color = ComposeColor(0xFF1B1B1B),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
                .background(ComposeColor(0xCCFFFFFF), MaterialTheme.shapes.extraSmall)
                .clickable { SystemSettingsIntents.openUrl(context, OsmConfig.COPYRIGHT_URL) }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private fun cameraLayer(): CircleLayer = CircleLayer(LAYER_ID, SOURCE_ID).withProperties(
    PropertyFactory.circleRadius(7f),
    PropertyFactory.circleColor(
        Expression.match(
            Expression.get("trust"),
            Expression.color(Color.parseColor("#1F6BFF")),
            Expression.stop("pending", Expression.color(Color.parseColor("#7B61FF"))),
            Expression.stop("disputed", Expression.color(Color.parseColor("#D93025"))),
        ),
    ),
    PropertyFactory.circleStrokeColor(Color.WHITE),
    PropertyFactory.circleStrokeWidth(2f),
)

private fun featureCollection(cameras: List<Camera>): FeatureCollection = FeatureCollection.fromFeatures(
    cameras.map { camera ->
        Feature.fromGeometry(Point.fromLngLat(camera.longitude, camera.latitude)).apply {
            addStringProperty("id", camera.id)
            addStringProperty(
                "trust",
                when (camera.verificationStatus) {
                    VerificationStatus.VERIFIED, VerificationStatus.COMMUNITY_CONFIRMED -> "trusted"
                    VerificationStatus.PENDING_VERIFICATION -> "pending"
                    VerificationStatus.DISPUTED, VerificationStatus.REJECTED -> "disputed"
                },
            )
        }
    },
)

/**
 * Shows the "you are here" dot from the single fix the Home screen already has.
 * MapLibre's own location engine is NOT used, so the map never starts continuous GPS.
 */
@SuppressLint("MissingPermission") // guarded by LocationPermissions.hasAnyLocation
private fun enableLocationComponent(
    context: Context,
    map: MapLibreMap,
    style: Style,
    show: Boolean,
    position: GeoPoint?,
    accuracyM: Float?,
) {
    val component = map.locationComponent
    if (!show || position == null || !LocationPermissions.hasAnyLocation(context)) {
        if (component.isLocationComponentActivated) component.isLocationComponentEnabled = false
        return
    }
    if (!component.isLocationComponentActivated) {
        component.activateLocationComponent(
            LocationComponentActivationOptions.builder(context, style)
                .useDefaultLocationEngine(false)
                // Fixes arrive one at a time, minutes apart: never grey the dot out as "stale" between them.
                .locationComponentOptions(LocationComponentOptions.builder(context).enableStaleState(false).build())
                .build(),
        )
    }
    component.isLocationComponentEnabled = true
    component.cameraMode = CameraMode.NONE
    component.renderMode = RenderMode.NORMAL
    component.forceLocationUpdate(
        Location("drive-alert").apply {
            latitude = position.latitude
            longitude = position.longitude
            accuracy = accuracyM ?: 20f
            time = System.currentTimeMillis()
        },
    )
}

@Composable
private fun MapUnavailable(modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Icon(Icons.Outlined.Map, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.map_unavailable_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(R.string.map_unavailable_body),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
