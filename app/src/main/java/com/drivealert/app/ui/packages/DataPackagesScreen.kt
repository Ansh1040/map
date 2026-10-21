package com.drivealert.app.ui.packages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.drivealert.app.R
import com.drivealert.app.data.sync.CameraSyncRepository
import com.drivealert.app.data.sync.SyncScheduler
import com.drivealert.app.database.DataPackageEntity
import com.drivealert.app.network.ApiConfig
import com.drivealert.app.ui.components.BackTopBar
import com.drivealert.app.ui.components.InfoBanner
import com.drivealert.app.ui.components.NavRow
import com.drivealert.app.ui.components.SectionCard
import com.drivealert.app.ui.components.ToggleRow
import com.drivealert.app.ui.components.Tone
import com.drivealert.app.ui.util.formatDateTime
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class DataPackagesViewModel @Inject constructor(
    private val syncRepository: CameraSyncRepository,
    private val scheduler: SyncScheduler,
) : ViewModel() {
    val packages: StateFlow<List<DataPackageEntity>> =
        syncRepository.observePackages().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val usesOpenStreetMap: Boolean = !ApiConfig.isConfigured

    /** Downloads the latest published data (weekly OpenStreetMap snapshot or the configured server). */
    fun updateNow() {
        viewModelScope.launch { syncRepository.refreshCatalog() }
        scheduler.syncNow()
    }

    fun setInstalled(pkg: DataPackageEntity, installed: Boolean) {
        viewModelScope.launch {
            syncRepository.setPackageInstalled(pkg.id, installed)
            if (installed) scheduler.syncNow()
        }
    }
}

@Composable
fun DataPackagesScreen(onBack: () -> Unit, viewModel: DataPackagesViewModel = hiltViewModel()) {
    val packages by viewModel.packages.collectAsStateWithLifecycle()
    Scaffold(topBar = { BackTopBar(stringResource(R.string.data_packages), onBack) }) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { InfoBanner(icon = Icons.Outlined.Info, text = stringResource(R.string.packages_coverage_note), tone = Tone.NEUTRAL) }
            if (viewModel.usesOpenStreetMap) {
                item { InfoBanner(icon = Icons.Filled.Public, text = stringResource(R.string.packages_osm_source), tone = Tone.NEUTRAL) }
            }
            item { SectionCard { NavRow(Icons.Filled.Sync, stringResource(R.string.packages_refresh), stringResource(R.string.packages_refresh_subtitle), viewModel::updateNow) } }
            if (packages.isEmpty()) {
                item {
                    Box(Modifier.padding(24.dp)) {
                        Text(stringResource(R.string.packages_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(packages, key = { it.id }) { pkg ->
                SectionCard {
                    ToggleRow(
                        icon = Icons.Filled.Map,
                        title = listOfNotNull(pkg.city, pkg.state).joinToString(", ").ifEmpty { pkg.name },
                        checked = pkg.isInstalled,
                        enabled = !pkg.isTestData,
                        onCheckedChange = { viewModel.setInstalled(pkg, it) },
                        subtitle = pkg.coverageNote,
                    )
                    Column(Modifier.padding(start = 40.dp)) {
                        Text(
                            pluralStringResource(R.plurals.package_camera_count, pkg.cameraCount, pkg.cameraCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            pkg.lastUpdatedAt?.let { stringResource(R.string.database_last_updated, formatDateTime(it)) }
                                ?: stringResource(R.string.database_never_updated),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
