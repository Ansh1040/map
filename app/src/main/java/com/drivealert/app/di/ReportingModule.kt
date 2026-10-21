package com.drivealert.app.di

import com.drivealert.app.data.sync.SyncScheduler
import com.drivealert.app.network.OpenDataClient
import com.drivealert.app.network.OsmNotesApi
import com.drivealert.app.reporting.ReportUploadScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class ReportingModule {
    @Binds abstract fun bindOsmNotes(impl: OpenDataClient): OsmNotesApi
    @Binds abstract fun bindReportUploadScheduler(impl: SyncScheduler): ReportUploadScheduler
}
