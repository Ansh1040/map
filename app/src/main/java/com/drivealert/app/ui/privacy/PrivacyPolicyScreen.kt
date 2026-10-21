package com.drivealert.app.ui.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.drivealert.app.BuildConfig
import com.drivealert.app.R
import com.drivealert.app.battery.SystemSettingsIntents
import com.drivealert.app.ui.components.BackTopBar
import com.drivealert.app.ui.components.NavRow
import com.drivealert.app.ui.components.SectionCard

private val SECTIONS = listOf(
    R.string.privacy_h_summary to R.string.privacy_p_summary,
    R.string.privacy_h_location to R.string.privacy_p_location,
    R.string.privacy_h_on_device to R.string.privacy_p_on_device,
    R.string.privacy_h_server to R.string.privacy_p_server,
    R.string.privacy_h_reports to R.string.privacy_p_reports,
    R.string.privacy_h_never to R.string.privacy_p_never,
    R.string.privacy_h_retention to R.string.privacy_p_retention,
    R.string.privacy_h_choices to R.string.privacy_p_choices,
    R.string.privacy_h_contact to R.string.privacy_p_contact,
)

@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    Scaffold(topBar = { BackTopBar(stringResource(R.string.privacy_policy), onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SECTIONS.forEach { (heading, body) ->
                SectionCard {
                    Text(stringResource(heading), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                }
            }
            if (BuildConfig.PRIVACY_POLICY_URL.isNotBlank()) {
                SectionCard {
                    NavRow(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.privacy_full_online), null) {
                        SystemSettingsIntents.openUrl(context, BuildConfig.PRIVACY_POLICY_URL)
                    }
                }
            }
        }
    }
}
