package com.drivealert.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drivealert.app.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class RootViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
) : ViewModel() {
    /** Null until preferences are loaded, to avoid flashing the wrong start screen. */
    val onboardingComplete: StateFlow<Boolean?> = settingsRepository.appPreferences
        .map<_, Boolean?> { it.onboardingComplete }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
