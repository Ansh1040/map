package com.drivealert.app.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.drivealert.app.R
import com.drivealert.app.permissions.LocationPermissions
import com.drivealert.app.ui.components.PrimaryActionButton
import com.drivealert.app.ui.components.SectionCard
import com.drivealert.app.ui.theme.LocalStatusColors

private enum class Step { WELCOME, HOW_IT_WORKS, SAFETY, LOCATION, NOTIFICATIONS, DONE }

@Composable
fun OnboardingScreen(
    onFinished: (openSetupAssistant: Boolean) -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    val steps = remember { Step.entries.filter { it != Step.NOTIFICATIONS || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU } }
    val step = steps[stepIndex.coerceIn(0, steps.lastIndex)]
    fun next() {
        stepIndex = (stepIndex + 1).coerceAtMost(steps.lastIndex)
    }

    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { next() }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { next() }
    val status = LocalStatusColors.current

    Column(
        Modifier
            .fillMaxSize()
            .background(status.header)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        StepDots(count = steps.size, current = stepIndex, modifier = Modifier.padding(20.dp))
        AnimatedContent(targetState = step, label = "onboarding", modifier = Modifier.weight(1f)) { current ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (current) {
                    Step.WELCOME -> StepContent(
                        icon = Icons.Filled.Shield,
                        title = stringResource(R.string.onboarding_welcome_title),
                        body = stringResource(R.string.onboarding_welcome_body),
                    )
                    Step.HOW_IT_WORKS -> {
                        StepContent(
                            icon = Icons.Filled.Map,
                            title = stringResource(R.string.onboarding_maps_title),
                            body = stringResource(R.string.onboarding_maps_body),
                        )
                        SectionCard {
                            Bullet(stringResource(R.string.onboarding_maps_point_1))
                            Bullet(stringResource(R.string.onboarding_maps_point_2))
                            Bullet(stringResource(R.string.onboarding_maps_point_3))
                        }
                    }
                    Step.SAFETY -> {
                        StepContent(
                            icon = Icons.Filled.Verified,
                            title = stringResource(R.string.onboarding_safety_title),
                            body = stringResource(R.string.onboarding_safety_body),
                        )
                        SectionCard {
                            Bullet(stringResource(R.string.onboarding_safety_point_1))
                            Bullet(stringResource(R.string.onboarding_safety_point_2))
                            Bullet(stringResource(R.string.onboarding_safety_point_3))
                        }
                    }
                    Step.LOCATION -> {
                        StepContent(
                            icon = Icons.Filled.LocationOn,
                            title = stringResource(R.string.disclosure_title),
                            body = stringResource(R.string.disclosure_body),
                        )
                        SectionCard {
                            Bullet(stringResource(R.string.disclosure_point_1))
                            Bullet(stringResource(R.string.disclosure_point_2))
                            Bullet(stringResource(R.string.disclosure_point_3))
                            Bullet(stringResource(R.string.disclosure_point_4))
                        }
                    }
                    Step.NOTIFICATIONS -> StepContent(
                        icon = Icons.Filled.Notifications,
                        title = stringResource(R.string.onboarding_notifications_title),
                        body = stringResource(R.string.onboarding_notifications_body),
                    )
                    Step.DONE -> StepContent(
                        icon = Icons.Filled.CheckCircle,
                        title = stringResource(R.string.onboarding_done_title),
                        body = stringResource(R.string.onboarding_done_body),
                    )
                }
            }
        }

        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (step) {
                Step.LOCATION -> {
                    PrimaryActionButton(
                        text = stringResource(R.string.action_continue_allow_location),
                        icon = null,
                        onClick = {
                            viewModel.acceptDisclosure()
                            locationLauncher.launch(LocationPermissions.FOREGROUND_LOCATION)
                        },
                    )
                    SecondaryTextButton(stringResource(R.string.action_not_now)) { next() }
                }
                Step.NOTIFICATIONS -> {
                    PrimaryActionButton(
                        text = stringResource(R.string.action_allow_notifications),
                        icon = null,
                        onClick = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    )
                    SecondaryTextButton(stringResource(R.string.action_not_now)) { next() }
                }
                Step.DONE -> {
                    PrimaryActionButton(
                        text = stringResource(R.string.action_open_setup_assistant),
                        icon = null,
                        onClick = { viewModel.complete { onFinished(true) } },
                    )
                    SecondaryTextButton(stringResource(R.string.action_finish)) { viewModel.complete { onFinished(false) } }
                }
                else -> PrimaryActionButton(text = stringResource(R.string.action_continue), icon = null, onClick = { next() })
            }
        }
    }
}

@Composable
private fun StepContent(icon: ImageVector, title: String, body: String) {
    val status = LocalStatusColors.current
    Spacer(Modifier.height(8.dp))
    Box(
        Modifier
            .size(72.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(36.dp))
    }
    Text(title, style = MaterialTheme.typography.headlineSmall, color = status.onHeader)
    Text(body, style = MaterialTheme.typography.bodyLarge, color = status.onHeaderMuted)
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SecondaryTextButton(text: String, onClick: () -> Unit) {
    val status = LocalStatusColors.current
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) { Text(text, color = status.onHeader) }
}

@Composable
private fun StepDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    val status = LocalStatusColors.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { index ->
            Box(
                Modifier
                    .size(width = if (index == current) 24.dp else 8.dp, height = 8.dp)
                    .background(
                        if (index <= current) MaterialTheme.colorScheme.primary else status.onHeaderMuted.copy(alpha = 0.4f),
                        CircleShape,
                    ),
            )
        }
    }
}
