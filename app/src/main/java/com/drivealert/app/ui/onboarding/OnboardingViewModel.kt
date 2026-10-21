package com.drivealert.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drivealert.app.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    /** Records that the prominent location disclosure was shown and accepted. */
    fun acceptDisclosure() {
        viewModelScope.launch { settingsRepository.setLocationDisclosureAccepted() }
    }

    fun complete(then: () -> Unit) {
        viewModelScope.launch {
            settingsRepository.setOnboardingComplete()
            then()
        }
    }
}
