package com.drivealert.app.data.osm

import com.drivealert.app.BuildConfig

/**
 * Open-data endpoints. All are free, need no API key and are HTTPS only.
 *
 * Camera data is NOT fetched from Overpass by every phone: a weekly GitHub
 * Actions job (.github/workflows/update-osm-snapshot.yml) regenerates one
 * India snapshot and publishes it in the repository, and apps download that
 * single file. This keeps load off the volunteer-run Overpass servers.
 */
object OsmConfig {
    /** Weekly-refreshed snapshot (same format as the bundled asset). Override with OSM_SNAPSHOT_URL. */
    val snapshotUrl: String = BuildConfig.OSM_SNAPSHOT_URL

    /** Bundled offline snapshot shipped in the APK. */
    const val BUNDLED_ASSET = "osm/india_cameras.json"

    /**
     * OpenStreetMap API for public map notes (community reports). Debug builds use
     * the OSM *test* server so testing never creates notes on the real map.
     */
    val osmApiUrl: String = BuildConfig.OSM_API_URL
    const val OSM_WEBSITE = "https://www.openstreetmap.org/"
    const val COPYRIGHT_URL = "https://www.openstreetmap.org/copyright"

    /** Vector map style from OpenFreeMap (open source, free, no key). Override with MAP_STYLE_URL. */
    val mapStyleUrl: String = BuildConfig.MAP_STYLE_URL

    val userAgent: String = "DriveAlert/${BuildConfig.VERSION_NAME} (Android; +https://github.com/Ansh1040/map)"
}
