package com.drivealert.app.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.drivealert.app.domain.model.AlertDistances
import com.drivealert.app.domain.model.AlertSettings
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class AppPreferences(
    val onboardingComplete: Boolean = false,
    val locationDisclosureAccepted: Boolean = false,
    val lastCalibrationAtMillis: Long = 0L,
    val allowMobileDataSync: Boolean = false,
    val historyRetentionDays: Int = 30,
    /** True while an alert session is running; detects sessions killed by the system. */
    val sessionMarkedActive: Boolean = false,
)

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val safeData: Flow<Preferences> = dataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    val alertSettings: Flow<AlertSettings> = safeData.map { prefs ->
        AlertSettings(
            voiceAlerts = prefs[Keys.VOICE] ?: true,
            alertSound = prefs[Keys.SOUND] ?: true,
            vibration = prefs[Keys.VIBRATION] ?: true,
            visualOnly = prefs[Keys.VISUAL_ONLY] ?: false,
            primaryAlertDistanceM = prefs[Keys.PRIMARY_DISTANCE] ?: AlertDistances.DEFAULT,
            closerReminders = prefs[Keys.CLOSER_REMINDERS] ?: true,
            includeUnverified = prefs[Keys.INCLUDE_UNVERIFIED] ?: false,
        )
    }.distinctUntilChanged()

    val appPreferences: Flow<AppPreferences> = safeData.map { prefs ->
        AppPreferences(
            onboardingComplete = prefs[Keys.ONBOARDING_COMPLETE] ?: false,
            locationDisclosureAccepted = prefs[Keys.DISCLOSURE_ACCEPTED] ?: false,
            lastCalibrationAtMillis = prefs[Keys.LAST_CALIBRATION] ?: 0L,
            allowMobileDataSync = prefs[Keys.MOBILE_DATA_SYNC] ?: false,
            historyRetentionDays = prefs[Keys.HISTORY_RETENTION_DAYS] ?: 30,
            sessionMarkedActive = prefs[Keys.SESSION_ACTIVE] ?: false,
        )
    }.distinctUntilChanged()

    suspend fun currentAppPreferences(): AppPreferences = appPreferences.first()

    suspend fun setVoiceAlerts(enabled: Boolean) = set(Keys.VOICE, enabled)
    suspend fun setAlertSound(enabled: Boolean) = set(Keys.SOUND, enabled)
    suspend fun setVibration(enabled: Boolean) = set(Keys.VIBRATION, enabled)
    suspend fun setVisualOnly(enabled: Boolean) = set(Keys.VISUAL_ONLY, enabled)
    suspend fun setCloserReminders(enabled: Boolean) = set(Keys.CLOSER_REMINDERS, enabled)
    suspend fun setIncludeUnverified(enabled: Boolean) = set(Keys.INCLUDE_UNVERIFIED, enabled)

    suspend fun setPrimaryAlertDistance(meters: Int) {
        require(meters in AlertDistances.STANDARD) { "Unsupported alert distance $meters" }
        set(Keys.PRIMARY_DISTANCE, meters)
    }

    suspend fun setOnboardingComplete() = set(Keys.ONBOARDING_COMPLETE, true)
    suspend fun setLocationDisclosureAccepted() = set(Keys.DISCLOSURE_ACCEPTED, true)
    suspend fun setLastCalibration(atMillis: Long) = set(Keys.LAST_CALIBRATION, atMillis)
    suspend fun setAllowMobileDataSync(enabled: Boolean) = set(Keys.MOBILE_DATA_SYNC, enabled)
    suspend fun setSessionMarkedActive(active: Boolean) = set(Keys.SESSION_ACTIVE, active)

    /** Version (osm_base) of the bundled OpenStreetMap snapshot already imported. */
    suspend fun importedBundledSnapshot(): String? = safeData.first()[Keys.BUNDLED_SNAPSHOT]
    suspend fun setImportedBundledSnapshot(version: String) = set(Keys.BUNDLED_SNAPSHOT, version)

    /** Parser version (OsmSnapshotImporter.PARSER_VERSION) the stored OpenStreetMap cameras were imported with. */
    suspend fun appliedOsmParserVersion(): Int? = safeData.first()[Keys.OSM_PARSER_VERSION]
    suspend fun setAppliedOsmParserVersion(version: Int) = set(Keys.OSM_PARSER_VERSION, version)

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        dataStore.edit { it[key] = value }
    }

    private object Keys {
        val VOICE = booleanPreferencesKey("voice_alerts")
        val SOUND = booleanPreferencesKey("alert_sound")
        val VIBRATION = booleanPreferencesKey("vibration")
        val VISUAL_ONLY = booleanPreferencesKey("visual_only")
        val PRIMARY_DISTANCE = intPreferencesKey("primary_alert_distance_m")
        val CLOSER_REMINDERS = booleanPreferencesKey("closer_reminders")
        val INCLUDE_UNVERIFIED = booleanPreferencesKey("include_unverified")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val DISCLOSURE_ACCEPTED = booleanPreferencesKey("location_disclosure_accepted")
        val LAST_CALIBRATION = longPreferencesKey("last_calibration_at")
        val MOBILE_DATA_SYNC = booleanPreferencesKey("allow_mobile_data_sync")
        val HISTORY_RETENTION_DAYS = intPreferencesKey("history_retention_days")
        val SESSION_ACTIVE = booleanPreferencesKey("session_marked_active")
        val BUNDLED_SNAPSHOT = stringPreferencesKey("imported_bundled_osm_snapshot")
        val OSM_PARSER_VERSION = intPreferencesKey("applied_osm_parser_version")
    }
}
