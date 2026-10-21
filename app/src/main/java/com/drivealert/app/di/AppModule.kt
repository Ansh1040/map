package com.drivealert.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.drivealert.app.alerts.AlertCoordinator
import com.drivealert.app.cameras.CameraRepository
import com.drivealert.app.cameras.RoomCameraRepository
import com.drivealert.app.data.history.AlertHistoryRepository
import com.drivealert.app.database.AlertHistoryDao
import com.drivealert.app.database.CameraDao
import com.drivealert.app.database.DataPackageDao
import com.drivealert.app.database.DriveAlertDatabase
import com.drivealert.app.database.ReportDao
import com.drivealert.app.domain.alerts.AlertThresholdPolicy
import com.drivealert.app.domain.cameras.NearbyCameraIndex
import com.drivealert.app.domain.location.AdaptiveLocationController
import com.drivealert.app.domain.location.TrackingModePolicy
import com.drivealert.app.domain.model.AlertSettings
import com.drivealert.app.domain.ports.AlertHistoryRecorder
import com.drivealert.app.domain.ports.AlertPresenter
import com.drivealert.app.domain.ports.Clock
import com.drivealert.app.domain.ports.LocationSource
import com.drivealert.app.domain.relevance.CameraRelevanceEngine
import com.drivealert.app.domain.reporting.ReportModerationPolicy
import com.drivealert.app.domain.session.DrivingSessionManager
import com.drivealert.app.location.FusedLocationSource
import com.drivealert.app.network.ApiConfig
import com.drivealert.app.network.CameraDataApi
import com.drivealert.app.settings.SettingsRepository
import com.drivealert.app.util.SystemClockImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun bindLocationSource(impl: FusedLocationSource): LocationSource
    @Binds abstract fun bindAlertPresenter(impl: AlertCoordinator): AlertPresenter
    @Binds abstract fun bindHistoryRecorder(impl: AlertHistoryRepository): AlertHistoryRecorder
    @Binds abstract fun bindCameraRepository(impl: RoomCameraRepository): CameraRepository
    @Binds abstract fun bindClock(impl: SystemClockImpl): Clock
}

/** Separate so tests can replace the settings store with an isolated file. */
@Module
@InstallIn(SingletonComponent::class)
object StorageModule {
    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("drive_alert_settings") }
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): DriveAlertDatabase =
        Room.databaseBuilder(context, DriveAlertDatabase::class.java, DriveAlertDatabase.NAME).build()

    @Provides fun provideCameraDao(db: DriveAlertDatabase): CameraDao = db.cameraDao()
    @Provides fun providePackageDao(db: DriveAlertDatabase): DataPackageDao = db.dataPackageDao()
    @Provides fun provideHistoryDao(db: DriveAlertDatabase): AlertHistoryDao = db.alertHistoryDao()
    @Provides fun provideReportDao(db: DriveAlertDatabase): ReportDao = db.reportDao()

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(ApiConfig.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(ApiConfig.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // TODO(deployment): add CertificatePinner for the production API host.
        .build()

    @Provides
    @Singleton
    fun provideCameraDataApi(client: OkHttpClient): CameraDataApi {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        // ".invalid" never resolves: calls are additionally gated on ApiConfig.isConfigured.
        val baseUrl = if (ApiConfig.isConfigured) ApiConfig.baseUrl else "https://camera-api.not-configured.invalid/"
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(CameraDataApi::class.java)
    }

    @Provides
    @Singleton
    fun provideAlertSettings(
        settingsRepository: SettingsRepository,
        @ApplicationScope scope: CoroutineScope,
    ): StateFlow<AlertSettings> = settingsRepository.alertSettings.stateIn(scope, SharingStarted.Eagerly, AlertSettings())

    @Provides @Singleton fun provideTrackingPolicy(): TrackingModePolicy = TrackingModePolicy()
    @Provides @Singleton fun provideThresholdPolicy(): AlertThresholdPolicy = AlertThresholdPolicy()
    @Provides @Singleton fun provideRelevanceEngine(): CameraRelevanceEngine = CameraRelevanceEngine()
    @Provides @Singleton fun provideModerationPolicy(): ReportModerationPolicy = ReportModerationPolicy()

    @Provides
    @Singleton
    fun provideNearbyCameraIndex(repository: CameraRepository): NearbyCameraIndex = NearbyCameraIndex(repository)

    @Provides
    @Singleton
    fun provideAdaptiveLocationController(
        source: LocationSource,
        policy: TrackingModePolicy,
        clock: Clock,
    ): AdaptiveLocationController = AdaptiveLocationController(source, policy, clock)

    @Provides
    @Singleton
    fun provideDrivingSessionManager(
        source: LocationSource,
        controller: AdaptiveLocationController,
        index: NearbyCameraIndex,
        engine: CameraRelevanceEngine,
        thresholds: AlertThresholdPolicy,
        presenter: AlertPresenter,
        history: AlertHistoryRecorder,
        settings: StateFlow<AlertSettings>,
        clock: Clock,
        cameras: CameraRepository,
    ): DrivingSessionManager = DrivingSessionManager(
        locationSource = source,
        locationController = controller,
        cameraIndex = index,
        relevanceEngine = engine,
        thresholdPolicy = thresholds,
        alertPresenter = presenter,
        historyRecorder = history,
        settings = settings,
        clock = clock,
        dataVersion = cameras.dataVersion,
        // Map matching is optional and needs a licensed road-data provider; none is configured.
        roadContextProvider = null,
        workContext = Dispatchers.Default,
    )
}
