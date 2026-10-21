package com.drivealert.app.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
import com.drivealert.app.domain.model.Directionality
import com.drivealert.app.domain.model.VerificationStatus
import com.drivealert.app.permissions.LocationPermissions
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

private val INDIA_CENTER = LatLng(22.5, 79.0)
private const val SOURCE_ID = "drive-alert-cameras"
private const val LAYER_ID = "drive-alert-cameras-layer"
private const val DIRECTION_LAYER_ID = "drive-alert-camera-direction-layer"
private const val CLUSTER_LAYER_ID = "drive-alert-camera-cluster-layer"
private const val CLUSTER_COUNT_LAYER_ID = "drive-alert-camera-cluster-count-layer"

/** Unclustered source with the cameras of the "Cameras near you" list, drawn above the clusters. */
private const val NEAREST_SOURCE_ID = "drive-alert-nearest-cameras"
private const val NEAREST_LAYER_ID = "drive-alert-nearest-cameras-layer"
private const val NEAREST_DIRECTION_LAYER_ID = "drive-alert-nearest-camera-direction-layer"
private const val ARROW_ONE_IMAGE = "drive-alert-arrow-one"
private const val ARROW_BOTH_IMAGE = "drive-alert-arrow-both"
private const val DIR_ONE = "one"
private const val DIR_BOTH = "both"
private const val DIR_UNKNOWN = "unknown"
private const val BRAND_BLUE = "#1F6BFF"

/** Cameras merge into clusters up to this zoom; from the next level on every camera has its own marker. */
private const val CLUSTER_MAX_ZOOM = 11
private const val CLUSTER_RADIUS = 45
private const val CLUSTER_TAP_ZOOM_STEP = 2.0
private const val USER_ZOOM = 13.0

/** Framing the user together with the nearest cameras never zooms in closer than this. */
private const val MAX_FRAMING_ZOOM = 15.0

/** How many of the nearest cameras the first centring frames with the user; the rest are drawn, not framed. */
private const val FRAMED_NEAREST = 3

/**
 * How long the first centring waits for the nearest cameras when the location fix arrives
 * before them (they are looked up just after it), so it can frame them in one move.
 */
private const val NEAREST_CAMERAS_WAIT_MS = 1_000L
private const val ARROW_SIZE_DP = 14f

/**
 * Map with the user's location and camera markers, rendered by MapLibre
 * (open source) with OpenFreeMap vector tiles (free, no API key). The map is
 * only a preview: it never reads anything from the Google Maps app, and
 * camera alerts work without it (including offline).
 *
 * [cameras] can be every usable camera of the installed packages: MapLibre clusters
 * them while zoomed out (tapping a cluster zooms in) and, zoomed in, draws each camera
 * with an arrow for its monitored direction when that is known.
 *
 * [nearestCameras] are the cameras of the "Cameras near you" list, nearest first. They
 * look the same but are never clustered: they have their own source, drawn above the
 * clusters, so they stay visible at every zoom (and are left out of the clustered source,
 * so they are neither counted in a cluster nor drawn twice). The first centring on the
 * user frames the first [FRAMED_NEAREST] of them as well.
 *
 * Attribution: a permanently visible credit (links to the OSM copyright page)
 * plus MapLibre's attribution dialog.
 */
@Composable
fun CameraMap(
    cameras: List<Camera>,
    myLocation: GeoPoint?,
    myLocationAccuracyM: Float?,
    nearestCameras: List<Camera> = emptyList(),
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
    val framingPaddingPx = with(LocalDensity.current) { 48.dp.roundToPx() }
    // Only the framed cameras matter to the first centring: a change further down the list
    // must not restart its wait for them.
    val framedNearest = remember(nearestCameras) { nearestCameras.take(FRAMED_NEAREST).map { it.latitude to it.longitude } }

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
                // 48 dp touch target around the tap; pick the camera (clustered source or nearest
                // cameras) or cluster nearest the finger.
                val screen = m.projection.toScreenLocation(point)
                val area = RectF(screen.x - tapRadiusPx, screen.y - tapRadiusPx, screen.x + tapRadiusPx, screen.y + tapRadiusPx)
                val hit = m.queryRenderedFeatures(area, LAYER_ID, NEAREST_LAYER_ID, CLUSTER_LAYER_ID)
                    .mapNotNull { feature ->
                        val geometry = feature.geometry() as? Point ?: return@mapNotNull null
                        val isCluster = feature.hasProperty("point_count")
                        val cameraId = if (isCluster) null else (feature.getStringProperty("id") ?: return@mapNotNull null)
                        val position = LatLng(geometry.latitude(), geometry.longitude())
                        val pos = m.projection.toScreenLocation(position)
                        val dx = pos.x - screen.x
                        val dy = pos.y - screen.y
                        CameraMapHit(position, cameraId, dx * dx + dy * dy)
                    }
                    .minByOrNull { it.distanceSq }
                    ?: return@addOnMapClickListener false
                val cameraId = hit.cameraId
                if (cameraId != null) {
                    latestOnClick(cameraId)
                } else {
                    // A cluster: zoom in on it so it splits into smaller groups or single cameras.
                    val zoom = m.cameraPosition.zoom + CLUSTER_TAP_ZOOM_STEP
                    m.animateCamera(CameraUpdateFactory.newLatLngZoom(hit.position, zoom))
                    // Saved now, not only on idle: the map may be recreated before the animation ends.
                    savedCamera = listOf(hit.position.latitude, hit.position.longitude, zoom)
                }
                true
            }
            m.setStyle(Style.Builder().fromUri(OsmConfig.mapStyleUrl)) { loaded ->
                loaded.addImage(ARROW_ONE_IMAGE, arrowBitmap(context, doubleHeaded = false))
                loaded.addImage(ARROW_BOTH_IMAGE, arrowBitmap(context, doubleHeaded = true))
                loaded.addSource(
                    GeoJsonSource(
                        SOURCE_ID,
                        FeatureCollection.fromFeatures(emptyList<Feature>()),
                        GeoJsonOptions().withCluster(true).withClusterMaxZoom(CLUSTER_MAX_ZOOM).withClusterRadius(CLUSTER_RADIUS),
                    ),
                )
                // Never clustered, so the cameras of the "near you" list are never hidden in a bubble.
                loaded.addSource(GeoJsonSource(NEAREST_SOURCE_ID, FeatureCollection.fromFeatures(emptyList<Feature>())))
                // Bottom to top: every layer of the clustered source, then the nearest cameras above them all.
                loaded.addLayer(cameraLayer(LAYER_ID, SOURCE_ID))
                loaded.addLayer(directionLayer(DIRECTION_LAYER_ID, SOURCE_ID))
                loaded.addLayer(clusterLayer())
                loaded.addLayer(clusterCountLayer())
                loaded.addLayer(cameraLayer(NEAREST_LAYER_ID, NEAREST_SOURCE_ID))
                loaded.addLayer(directionLayer(NEAREST_DIRECTION_LAYER_ID, NEAREST_SOURCE_ID))
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

    LaunchedEffect(style, cameras, nearestCameras) {
        val s = style ?: return@LaunchedEffect
        // Both sources are rebuilt when either list changes: the nearest cameras go only to their
        // own source, never to the clustered one. Up to a few thousand cameras: build the features
        // off the main thread.
        val (clustered, nearest) = withContext(Dispatchers.Default) {
            val nearestIds = nearestCameras.mapTo(HashSet<String>()) { it.id }
            featureCollection(cameras.filter { it.id !in nearestIds }) to featureCollection(nearestCameras)
        }
        s.getSourceAs<GeoJsonSource>(SOURCE_ID)?.setGeoJson(clustered)
        s.getSourceAs<GeoJsonSource>(NEAREST_SOURCE_ID)?.setGeoJson(nearest)
    }

    LaunchedEffect(map, myLocation, framedNearest) {
        val m = map ?: return@LaunchedEffect
        if (myLocation != null && !centeredOnUser) {
            // A change of the framed cameras restarts this effect and ends the wait early.
            if (framedNearest.isEmpty()) delay(NEAREST_CAMERAS_WAIT_MS)
            val user = LatLng(myLocation.latitude, myLocation.longitude)
            val points = framedNearest.map { (lat, lon) -> LatLng(lat, lon) }
            val framed = fitUserAndCameras(m, mapView, user, points, framingPaddingPx)
            val target = framed?.first ?: user
            val zoom = framed?.second ?: USER_ZOOM
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(target, zoom))
            // Saved now, not only on idle: the map may be paused or recreated before the animation ends.
            savedCamera = listOf(target.latitude, target.longitude, zoom)
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

/** A camera or cluster under the finger; [cameraId] is null for a cluster. */
private class CameraMapHit(val position: LatLng, val cameraId: String?, val distanceSq: Float)

/**
 * Target and zoom showing [user] and the camera positions [cameras] with [paddingPx] to spare on
 * every side, never closer than [MAX_FRAMING_ZOOM]; null when there is nothing to frame or the
 * map has no usable size.
 */
private fun fitUserAndCameras(
    map: MapLibreMap,
    view: MapView,
    user: LatLng,
    cameras: List<LatLng>,
    paddingPx: Int,
): Pair<LatLng, Double>? {
    if (cameras.isEmpty() || view.width <= 2 * paddingPx || view.height <= 2 * paddingPx) return null
    // The builder needs at least two points: the user plus one or more cameras.
    val bounds = LatLngBounds.Builder()
        .include(user)
        .includes(cameras)
        .build()
    val fitted = map.getCameraForLatLngBounds(bounds, intArrayOf(paddingPx, paddingPx, paddingPx, paddingPx)) ?: return null
    val target = fitted.target ?: return null
    if (fitted.zoom.isNaN()) return null
    return target to minOf(fitted.zoom, MAX_FRAMING_ZOOM)
}

/**
 * Individual cameras of [sourceId] (never its clusters), coloured by how far the record can be
 * trusted. The clustered source and the nearest cameras use this same style.
 */
private fun cameraLayer(layerId: String, sourceId: String): CircleLayer = CircleLayer(layerId, sourceId)
    .withFilter(Expression.not(Expression.has("point_count")))
    .withProperties(
        PropertyFactory.circleRadius(9f),
        PropertyFactory.circleColor(
            Expression.match(
                Expression.get("trust"),
                Expression.color(Color.parseColor(BRAND_BLUE)),
                Expression.stop("pending", Expression.color(Color.parseColor("#7B61FF"))),
                Expression.stop("disputed", Expression.color(Color.parseColor("#D93025"))),
            ),
        ),
        PropertyFactory.circleStrokeColor(Color.WHITE),
        PropertyFactory.circleStrokeWidth(2f),
    )

/** Arrow on the individual cameras of [sourceId] whose monitored direction is known, turned to the travel bearing. */
private fun directionLayer(layerId: String, sourceId: String): SymbolLayer = SymbolLayer(layerId, sourceId)
    .withFilter(
        Expression.all(
            Expression.not(Expression.has("point_count")),
            Expression.any(
                Expression.eq(Expression.get("dir"), DIR_ONE),
                Expression.eq(Expression.get("dir"), DIR_BOTH),
            ),
        ),
    )
    .withProperties(
        PropertyFactory.iconImage(
            Expression.match(
                Expression.get("dir"),
                Expression.literal(""),
                Expression.stop(DIR_ONE, ARROW_ONE_IMAGE),
                Expression.stop(DIR_BOTH, ARROW_BOTH_IMAGE),
            ),
        ),
        PropertyFactory.iconRotate(Expression.get("bearing")),
        PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
        PropertyFactory.iconAllowOverlap(true),
        PropertyFactory.iconIgnorePlacement(true),
    )

/** Groups of cameras while zoomed out: brand-blue circles that grow with the count. */
private fun clusterLayer(): CircleLayer = CircleLayer(CLUSTER_LAYER_ID, SOURCE_ID)
    .withFilter(Expression.has("point_count"))
    .withProperties(
        PropertyFactory.circleColor(Color.parseColor(BRAND_BLUE)),
        PropertyFactory.circleRadius(
            Expression.step(
                Expression.get("point_count"),
                14f,
                Expression.stop(10, 17f),
                Expression.stop(50, 20f),
                Expression.stop(200, 23f),
                Expression.stop(1000, 27f),
            ),
        ),
        PropertyFactory.circleStrokeColor(Color.WHITE),
        PropertyFactory.circleStrokeWidth(2f),
    )

/** Number of cameras in each cluster. */
private fun clusterCountLayer(): SymbolLayer = SymbolLayer(CLUSTER_COUNT_LAYER_ID, SOURCE_ID)
    .withFilter(Expression.has("point_count"))
    .withProperties(
        PropertyFactory.textField(Expression.toString(Expression.get("point_count"))),
        // A font stack served with the OpenFreeMap "liberty" glyphs.
        PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
        PropertyFactory.textSize(12f),
        PropertyFactory.textColor(Color.WHITE),
        PropertyFactory.textAllowOverlap(true),
        PropertyFactory.textIgnorePlacement(true),
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
            camera.bearingDeg?.let { addNumberProperty("bearing", it) }
            addStringProperty("dir", directionKey(camera))
        }
    },
)

/** "one" / "both" when the monitored direction is known (that needs a bearing), else "unknown". */
private fun directionKey(camera: Camera): String = when {
    camera.bearingDeg == null -> DIR_UNKNOWN
    camera.directionality == Directionality.ONE_DIRECTION -> DIR_ONE
    camera.directionality == Directionality.BOTH_DIRECTIONS -> DIR_BOTH
    else -> DIR_UNKNOWN
}

/**
 * White arrow with a thin dark outline, about [ARROW_SIZE_DP] square, pointing north (up) so
 * that rotating it by the camera's travel bearing shows the monitored direction.
 * [doubleHeaded] adds a second head at the tail, for cameras that enforce both directions.
 */
private fun arrowBitmap(context: Context, doubleHeaded: Boolean): Bitmap {
    val metrics = context.resources.displayMetrics
    val size = (ARROW_SIZE_DP * metrics.density).roundToInt().coerceAtLeast(8)
    val outline = metrics.density // 1 dp of dark edge outside the white fill
    val s = size.toFloat()
    val cx = s / 2f
    val top = outline + 1f
    val bottom = s - outline - 1f
    val headHalfWidth = s * if (doubleHeaded) 0.28f else 0.32f
    val headLength = s * if (doubleHeaded) 0.30f else 0.44f
    val shaftHalfWidth = s * 0.11f
    val path = Path().apply {
        moveTo(cx, top)
        lineTo(cx + headHalfWidth, top + headLength)
        lineTo(cx + shaftHalfWidth, top + headLength)
        if (doubleHeaded) {
            lineTo(cx + shaftHalfWidth, bottom - headLength)
            lineTo(cx + headHalfWidth, bottom - headLength)
            lineTo(cx, bottom)
            lineTo(cx - headHalfWidth, bottom - headLength)
            lineTo(cx - shaftHalfWidth, bottom - headLength)
        } else {
            lineTo(cx + shaftHalfWidth, bottom)
            lineTo(cx - shaftHalfWidth, bottom)
        }
        lineTo(cx - shaftHalfWidth, top + headLength)
        lineTo(cx - headHalfWidth, top + headLength)
        close()
    }
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    // MapLibre takes the icon's pixel ratio from the bitmap density: this keeps it ARROW_SIZE_DP on screen.
    bitmap.density = metrics.densityDpi
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.ROUND }
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = outline * 2f // the fill drawn next covers the inner half
    paint.color = Color.argb(220, 27, 27, 27)
    canvas.drawPath(path, paint)
    paint.style = Paint.Style.FILL
    paint.color = Color.WHITE
    canvas.drawPath(path, paint)
    return bitmap
}

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
