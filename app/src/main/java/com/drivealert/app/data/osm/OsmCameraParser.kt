package com.drivealert.app.data.osm

import com.drivealert.app.domain.geo.GeoMath
import com.drivealert.app.domain.model.Camera
import com.drivealert.app.domain.model.CameraType
import com.drivealert.app.domain.model.DataSource
import com.drivealert.app.domain.model.Directionality
import com.drivealert.app.domain.model.RoadContext
import com.drivealert.app.domain.model.VerificationStatus
import java.time.Instant
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** An Indian state or union territory, keyed by its ISO 3166-2 code (e.g. "IN-KA"). */
data class OsmRegion(val code: String, val name: String)

data class OsmSnapshot(
    val regions: List<OsmRegion>,
    val camerasByRegion: Map<String, List<Camera>>,
    /** OSM database timestamp of the snapshot; used as the package data version. */
    val osmBaseMillis: Long?,
) {
    val cameraCount: Int get() = camerasByRegion.values.sumOf { it.size }
}

/**
 * Converts OpenStreetMap enforcement data (Overpass JSON, or the compact
 * snapshot built by tools/fetch_osm_cameras.py) into [Camera] records.
 *
 * Mapping (see docs/05-algorithms.md §8):
 * - `highway=speed_camera` node → fixed speed camera
 * - `surveillance:type=ALPR` node → ANPR camera
 * - `type=enforcement` relation: `device` member(s) become cameras;
 *   `enforcement=maxspeed|average_speed` → speed, `traffic_signals` → red light;
 *   direction comes from `from`/`to` geometry when it is long and consistent
 *   enough, and overrides a contradicting node tag.
 * - `direction=*` is the way the camera FACES (degrees incl. negative, or compass
 *   points); the enforced travel direction is the opposite bearing. `a;b`
 *   (opposite values) → both directions; `forward`/`backward` need way geometry
 *   and are treated as unknown.
 * - `maxspeed=*` → speed limit (km/h; mph converted).
 *
 * Data © OpenStreetMap contributors, ODbL 1.0.
 */
object OsmCameraParser {
    const val PACKAGE_PREFIX = "osm-"
    const val ATTRIBUTION = "© OpenStreetMap contributors (ODbL)"

    private val json = Json { ignoreUnknownKeys = true }
    private val REGION_CODE = Regex("^IN-[A-Z]{2}$")
    private val SPEED = Regex("^(\\d{1,3})\\s*(km/h|kmh|kph|mph)?$", RegexOption.IGNORE_CASE)
    private const val FOUR_YEARS_MS = 4L * 365 * 24 * 3_600_000L
    private const val MIN_REFERENCE_DISTANCE_M = 20.0
    private const val MAX_DEVICE_SPREAD_DEG = 30.0

    private val COMPASS = mapOf(
        "N" to 0.0, "NNE" to 22.5, "NE" to 45.0, "ENE" to 67.5, "E" to 90.0, "ESE" to 112.5,
        "SE" to 135.0, "SSE" to 157.5, "S" to 180.0, "SSW" to 202.5, "SW" to 225.0, "WSW" to 247.5,
        "W" to 270.0, "WNW" to 292.5, "NW" to 315.0, "NNW" to 337.5,
    )

    fun packageId(regionCode: String): String = PACKAGE_PREFIX + regionCode

    fun regionCodeOf(packageId: String): String? =
        packageId.takeIf { it.startsWith(PACKAGE_PREFIX) }?.removePrefix(PACKAGE_PREFIX)?.takeIf { REGION_CODE.matches(it) }

    /**
     * @param fallbackRegion region for elements that appear before any area
     *   marker (e.g. a single-region Overpass response).
     */
    fun parse(text: String, nowMillis: Long, fallbackRegion: OsmRegion? = null): OsmSnapshot {
        val root = json.parseToJsonElement(text).jsonObject
        val osmBase = root.string("osm_base")
            ?: root["osm3s"]?.jsonObject?.string("timestamp_osm_base")
        val elements = root["elements"]?.jsonArray.orEmpty()

        val regions = LinkedHashMap<String, OsmRegion>()
        fallbackRegion?.let { regions[it.code] = it }
        val coordinates = HashMap<Long, Pair<Double, Double>>()
        val nodes = ArrayList<Pair<String?, JsonObject>>()
        val relations = ArrayList<Pair<String?, JsonObject>>()
        var current: String? = fallbackRegion?.code

        for (element in elements) {
            val obj = element.jsonObject
            val tags = obj["tags"]?.jsonObject
            when (obj.string("type")) {
                "area" -> {
                    val code = tags?.string("ISO3166-2")?.takeIf { REGION_CODE.matches(it) } ?: continue
                    current = code
                    regions.getOrPut(code) { OsmRegion(code, tags.string("name:en") ?: tags.string("name") ?: code) }
                }
                "node" -> {
                    val id = obj.long("id") ?: continue
                    val lat = obj.double("lat")
                    val lon = obj.double("lon")
                    if (lat != null && lon != null) coordinates[id] = lat to lon
                    if (!tags.isNullOrEmpty()) nodes += current to obj
                }
                "relation" -> if (!tags.isNullOrEmpty()) relations += current to obj
            }
        }

        // id -> (region code, camera). The first region listing an element owns it.
        val cameras = LinkedHashMap<String, Pair<String?, Camera>>()
        for ((region, node) in nodes) {
            val camera = cameraFromNode(node, region?.let { regions[it] }?.name, nowMillis) ?: continue
            cameras.putIfAbsent(camera.id, region to camera)
        }
        for ((region, relation) in relations) {
            mergeRelation(relation, coordinates, cameras, region, region?.let { regions[it] }?.name, nowMillis)
        }

        val byRegion = cameras.values
            .filter { it.first != null }
            .groupBy({ it.first!! }, { (code, camera) -> camera.copy(packageId = packageId(code!!)) })
        return OsmSnapshot(regions.values.toList(), byRegion, osmBase?.let(::parseInstant))
    }

    private fun cameraFromNode(node: JsonObject, regionName: String?, nowMillis: Long): Camera? {
        val tags = node["tags"]?.jsonObject ?: return null
        val type = when {
            tags.string("highway") == "speed_camera" -> CameraType.FIXED_SPEED
            tags.string("surveillance:type").equals("ALPR", ignoreCase = true) -> CameraType.ANPR
            else -> return null
        }
        val id = node.long("id") ?: return null
        val lat = node.double("lat") ?: return null
        val lon = node.double("lon") ?: return null
        // OSM `direction` on a camera is the way the camera FACES (towards oncoming
        // traffic), so the enforced direction of travel is the opposite bearing.
        val (bearing, directionality) = parseDirection(tags.string("direction")).let { (facing, kind) ->
            if (kind == Directionality.ONE_DIRECTION && facing != null) GeoMath.normalizeBearing(facing + 180.0) to kind else facing to kind
        }
        val speed = parseMaxspeed(tags.string("maxspeed"))
        val timestamp = node.string("timestamp")?.let(::parseInstant)
        return Camera(
            id = "osm-node-$id",
            latitude = lat,
            longitude = lon,
            type = type,
            roadName = tags.string("name:en") ?: tags.string("name") ?: tags.string("ref"),
            city = tags.string("addr:city"),
            state = regionName,
            bearingDeg = bearing,
            directionality = directionality,
            speedLimitKmh = speed,
            verificationStatus = VerificationStatus.COMMUNITY_CONFIRMED,
            verificationCount = 0,
            dataSource = DataSource.OPENSTREETMAP,
            sourceReference = "$ATTRIBUTION · node/$id",
            lastVerifiedAtMillis = timestamp,
            confidenceScore = confidence(type, directionality != Directionality.UNKNOWN, speed != null, timestamp, nowMillis),
            isActive = true,
            roadContext = RoadContext.UNKNOWN,
            isTestData = false,
        )
    }

    private fun mergeRelation(
        relation: JsonObject,
        coordinates: Map<Long, Pair<Double, Double>>,
        cameras: MutableMap<String, Pair<String?, Camera>>,
        region: String?,
        regionName: String?,
        nowMillis: Long,
    ) {
        val tags = relation["tags"]?.jsonObject ?: return
        if (tags.string("type") != "enforcement") return
        val type = when (tags.string("enforcement")) {
            "maxspeed", "average_speed" -> CameraType.FIXED_SPEED
            "traffic_signals" -> CameraType.RED_LIGHT
            else -> return
        }
        val members = relation["members"]?.jsonArray.orEmpty().map { it.jsonObject }
        fun refs(role: String) = members.filter { it.string("type") == "node" && it.string("role") == role }.mapNotNull { it.long("ref") }
        val from = refs("from").firstNotNullOfOrNull { coordinates[it] }
        val to = refs("to").firstNotNullOfOrNull { coordinates[it] }
        val speed = parseMaxspeed(tags.string("maxspeed"))
        val relationId = relation.long("id")
        val timestamp = relation.string("timestamp")?.let(::parseInstant)
        val devices = refs("device").mapNotNull { ref -> coordinates[ref]?.let { ref to it } }
        val bearings = relationBearings(devices, from, to)

        for ((deviceRef, device) in devices) {
            val bearing = bearings[deviceRef]
            val id = "osm-node-$deviceRef"
            val existing = cameras[id]
            if (existing != null) {
                val camera = existing.second
                val mergedType = if (type == CameraType.RED_LIGHT && camera.type == CameraType.FIXED_SPEED) CameraType.SPEED_AND_RED_LIGHT else camera.type
                // Relation geometry (from -> device) states the travel direction directly;
                // prefer it when the node tag is missing or contradicts it.
                val nodeBearing = camera.bearingDeg
                val useBearing = bearing != null && (
                    camera.directionality == Directionality.UNKNOWN ||
                        (camera.directionality == Directionality.ONE_DIRECTION && nodeBearing != null &&
                            GeoMath.angularDifferenceDeg(nodeBearing, bearing) > 90.0)
                    )
                val merged = camera.copy(
                    type = mergedType,
                    bearingDeg = if (useBearing) bearing else camera.bearingDeg,
                    directionality = if (useBearing) Directionality.ONE_DIRECTION else camera.directionality,
                    speedLimitKmh = camera.speedLimitKmh ?: speed,
                )
                cameras[id] = existing.first to merged.copy(
                    confidenceScore = confidence(merged.type, merged.directionality != Directionality.UNKNOWN, merged.speedLimitKmh != null, merged.lastVerifiedAtMillis, nowMillis),
                )
            } else {
                val directionality = if (bearing != null) Directionality.ONE_DIRECTION else Directionality.UNKNOWN
                cameras[id] = region to Camera(
                    id = id,
                    latitude = device.first,
                    longitude = device.second,
                    type = type,
                    roadName = tags.string("name:en") ?: tags.string("name"),
                    state = regionName,
                    bearingDeg = bearing,
                    directionality = directionality,
                    speedLimitKmh = speed,
                    verificationStatus = VerificationStatus.COMMUNITY_CONFIRMED,
                    dataSource = DataSource.OPENSTREETMAP,
                    sourceReference = "$ATTRIBUTION · relation/$relationId",
                    lastVerifiedAtMillis = timestamp,
                    confidenceScore = confidence(type, bearing != null, speed != null, timestamp, nowMillis),
                    roadContext = if (type == CameraType.RED_LIGHT) RoadContext.JUNCTION else RoadContext.UNKNOWN,
                    isTestData = false,
                )
            }
        }
    }

    /**
     * Travel bearings for an enforcement relation's devices, or none when the
     * geometry is too short or inconsistent to be trusted:
     * - reference nodes closer than [MIN_REFERENCE_DISTANCE_M] are mapping noise;
     * - with both `from` and `to`, all devices share the from -> to bearing,
     *   provided each device lies on that path;
     * - otherwise per-device bearings are used only if they all agree within
     *   [MAX_DEVICE_SPREAD_DEG].
     */
    private fun relationBearings(
        devices: List<Pair<Long, Pair<Double, Double>>>,
        from: Pair<Double, Double>?,
        to: Pair<Double, Double>?,
    ): Map<Long, Double> {
        fun far(a: Pair<Double, Double>, b: Pair<Double, Double>) =
            GeoMath.distanceMeters(a.first, a.second, b.first, b.second) >= MIN_REFERENCE_DISTANCE_M
        fun bearing(a: Pair<Double, Double>, b: Pair<Double, Double>) =
            GeoMath.initialBearingDeg(a.first, a.second, b.first, b.second)

        if (devices.isEmpty()) return emptyMap()
        if (from != null && to != null && far(from, to)) {
            val shared = bearing(from, to)
            val consistent = devices.all { (_, d) ->
                (!far(from, d) || GeoMath.angularDifferenceDeg(bearing(from, d), shared) <= 90.0) &&
                    (!far(d, to) || GeoMath.angularDifferenceDeg(bearing(d, to), shared) <= 90.0)
            }
            return if (consistent) devices.associate { it.first to shared } else emptyMap()
        }
        val perDevice = devices.mapNotNull { (ref, d) ->
            val incoming = from?.takeIf { far(it, d) }?.let { bearing(it, d) }
            val outgoing = to?.takeIf { far(d, it) }?.let { bearing(d, it) }
            val b = when {
                // from -> device and device -> to contradict each other: geometry is unreliable.
                incoming != null && outgoing != null && GeoMath.angularDifferenceDeg(incoming, outgoing) > 90.0 -> null
                else -> incoming ?: outgoing
            }
            b?.let { ref to it }
        }
        if (perDevice.size != devices.size) return emptyMap()
        val first = perDevice.first().second
        return if (perDevice.all { GeoMath.angularDifferenceDeg(it.second, first) <= MAX_DEVICE_SPREAD_DEG }) perDevice.toMap() else emptyMap()
    }

    /** Monitored direction from an OSM `direction` value. */
    fun parseDirection(raw: String?): Pair<Double?, Directionality> {
        val parts = raw?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (parts.isEmpty()) return null to Directionality.UNKNOWN
        val bearings = parts.map { singleBearing(it) }
        if (bearings.any { it == null }) return null to Directionality.UNKNOWN
        return when (bearings.size) {
            1 -> bearings[0] to Directionality.ONE_DIRECTION
            2 -> if (GeoMath.angularDifferenceDeg(bearings[0]!!, bearings[1]!!) >= 150.0) {
                bearings[0] to Directionality.BOTH_DIRECTIONS
            } else {
                null to Directionality.UNKNOWN
            }
            else -> null to Directionality.UNKNOWN
        }
    }

    private fun singleBearing(value: String): Double? {
        value.toDoubleOrNull()?.let { return if (it.isFinite() && it in -720.0..720.0) GeoMath.normalizeBearing(it) else null }
        return COMPASS[value.uppercase()]
    }

    /** Speed limit in km/h from an OSM `maxspeed` value, or null when not numeric. */
    fun parseMaxspeed(raw: String?): Int? {
        val first = raw?.split(';')?.firstOrNull()?.trim() ?: return null
        val match = SPEED.matchEntire(first) ?: return null
        val number = match.groupValues[1].toInt()
        val kmh = if (match.groupValues[2].equals("mph", ignoreCase = true)) {
            ((number * 1.609344) / 5.0).roundToInt() * 5
        } else {
            number
        }
        return kmh.takeIf { it in 5..150 }
    }

    private fun confidence(type: CameraType, directionKnown: Boolean, speedKnown: Boolean, timestamp: Long?, nowMillis: Long): Double {
        var score = if (type == CameraType.ANPR) 0.60 else 0.65
        if (directionKnown) score += 0.15
        if (speedKnown) score += 0.05
        if (timestamp != null && nowMillis - timestamp > FOUR_YEARS_MS) score -= 0.15
        return score.coerceIn(0.0, 1.0)
    }

    private fun parseInstant(value: String): Long? = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull
    private fun JsonObject.double(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull
}
