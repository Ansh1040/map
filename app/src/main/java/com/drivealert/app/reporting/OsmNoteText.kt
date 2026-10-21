package com.drivealert.app.reporting

import com.drivealert.app.data.osm.OsmConfig
import com.drivealert.app.domain.reporting.ReportType

/**
 * Text of a public OpenStreetMap note for a community report. OSM volunteers
 * review notes before changing the map, which gives reports human moderation
 * without a Drive Alert server. Written in English for the OSM community.
 */
object OsmNoteText {
    /** Unique per report; lets the uploader find a note whose creation response was lost. */
    fun reference(localId: Long, createdAtMillis: Long): String = "DriveAlert-r$localId-$createdAtMillis"

    fun build(
        type: ReportType,
        cameraId: String?,
        cameraType: String?,
        speedLimitKmh: Int?,
        comment: String?,
        reference: String,
    ): String = buildString {
        appendLine("Drive Alert community report - please verify on the ground before editing the map.")
        appendLine("Report: ${label(type)}")
        osmLink(cameraId)?.let { appendLine("Existing camera: $it") }
        cameraType?.let { appendLine("Camera type: ${it.lowercase().replace('_', ' ')}") }
        speedLimitKmh?.let { appendLine("Reported speed limit: $it km/h") }
        comment?.trim()?.takeIf { it.isNotEmpty() }?.let { appendLine("Comment: ${it.take(400)}") }
        appendLine("Sent from the open-source Drive Alert app (https://github.com/Ansh1040/map). #DriveAlert")
        append("Ref: $reference")
    }

    /** "osm-node-123" -> https://www.openstreetmap.org/node/123 */
    fun osmLink(cameraId: String?): String? {
        val match = cameraId?.let { Regex("^osm-(node|way|relation)-(\\d+)$").matchEntire(it) } ?: return null
        return "${OsmConfig.OSM_WEBSITE}${match.groupValues[1]}/${match.groupValues[2]}"
    }

    private fun label(type: ReportType): String = when (type) {
        ReportType.NEW_CAMERA -> "new traffic enforcement camera at this note's position"
        ReportType.CAMERA_REMOVED -> "camera no longer present"
        ReportType.INCORRECT_POSITION -> "camera is mapped in the wrong position; the reporter stood at the correct position (this note)"
        ReportType.INCORRECT_DIRECTION -> "monitored direction is wrong"
        ReportType.INCORRECT_SPEED_LIMIT -> "speed limit is wrong"
        ReportType.TEMPORARY_MOBILE_CAMERA -> "temporary / mobile speed check seen here (do not map as permanent)"
        ReportType.DUPLICATE_CAMERA -> "duplicate camera entry"
        ReportType.ABUSE -> "suspicious or incorrect data"
    }
}
