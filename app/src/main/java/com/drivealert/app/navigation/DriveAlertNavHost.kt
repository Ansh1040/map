package com.drivealert.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.drivealert.app.R
import com.drivealert.app.ui.RootViewModel
import com.drivealert.app.ui.calibration.CalibrationScreen
import com.drivealert.app.ui.details.CameraDetailsScreen
import com.drivealert.app.ui.history.HistoryScreen
import com.drivealert.app.ui.home.HomeScreen
import com.drivealert.app.ui.licenses.LicensesScreen
import com.drivealert.app.ui.onboarding.OnboardingScreen
import com.drivealert.app.ui.packages.DataPackagesScreen
import com.drivealert.app.ui.privacy.PrivacyPolicyScreen
import com.drivealert.app.ui.report.ReportScreen
import com.drivealert.app.ui.settings.SettingsScreen
import com.drivealert.app.ui.setup.SetupAssistantScreen
import kotlin.reflect.KClass

private data class TopLevelDestination(val route: Any, val routeClass: KClass<*>, val icon: ImageVector, val label: Int)

private val TOP_LEVEL = listOf(
    TopLevelDestination(HomeRoute, HomeRoute::class, Icons.Filled.Home, R.string.nav_home),
    TopLevelDestination(HistoryRoute, HistoryRoute::class, Icons.Filled.History, R.string.nav_history),
    TopLevelDestination(SettingsRoute, SettingsRoute::class, Icons.Filled.Settings, R.string.nav_settings),
)

@Composable
fun DriveAlertRoot(rootViewModel: RootViewModel = hiltViewModel()) {
    val onboardingComplete by rootViewModel.onboardingComplete.collectAsStateWithLifecycle()
    // Decide the start destination once; later preference changes must not rebuild the graph.
    var startWithOnboarding by rememberSaveable { mutableStateOf<Boolean?>(null) }
    if (startWithOnboarding == null) onboardingComplete?.let { startWithOnboarding = !it }
    when (val start = startWithOnboarding) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> DriveAlertNavHost(startWithOnboarding = start)
    }
}

@Composable
private fun DriveAlertNavHost(startWithOnboarding: Boolean, navController: NavHostController = rememberNavController()) {
    val backStack by navController.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val showBottomBar = TOP_LEVEL.any { top -> destination?.hierarchy?.any { it.hasRoute(top.routeClass) } == true }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    TOP_LEVEL.forEach { top ->
                        val selected = destination?.hierarchy?.any { it.hasRoute(top.routeClass) } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(top.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(top.icon, contentDescription = null) },
                            label = { Text(stringResource(top.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = if (startWithOnboarding) OnboardingRoute else HomeRoute,
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
        ) {
            composable<OnboardingRoute> {
                OnboardingScreen(
                    onFinished = { openSetup ->
                        navController.navigate(HomeRoute) { popUpTo(OnboardingRoute) { inclusive = true } }
                        if (openSetup) navController.navigate(SetupAssistantRoute)
                    },
                )
            }
            composable<HomeRoute> {
                HomeScreen(
                    onStartAlerts = { navController.navigate(CalibrationRoute) },
                    onOpenCamera = { navController.navigate(CameraDetailsRoute(it)) },
                    onOpenSetup = { navController.navigate(SetupAssistantRoute) },
                )
            }
            composable<HistoryRoute> {
                HistoryScreen(onOpenCamera = { navController.navigate(CameraDetailsRoute(it)) })
            }
            composable<SettingsRoute> {
                SettingsScreen(
                    onOpenSetup = { navController.navigate(SetupAssistantRoute) },
                    onOpenPrivacy = { navController.navigate(PrivacyPolicyRoute) },
                    onOpenPackages = { navController.navigate(DataPackagesRoute) },
                    onReportNewCamera = { navController.navigate(ReportRoute(null)) },
                    onOpenLicences = { navController.navigate(LicensesRoute) },
                )
            }
            composable<CalibrationRoute> {
                CalibrationScreen(
                    onBack = { navController.popBackStack() },
                    onDriveStarted = { navController.popBackStack(HomeRoute, inclusive = false) },
                )
            }
            composable<CameraDetailsRoute> {
                CameraDetailsScreen(
                    onBack = { navController.popBackStack() },
                    onReport = { id -> navController.navigate(ReportRoute(id)) },
                )
            }
            composable<ReportRoute> {
                ReportScreen(onBack = { navController.popBackStack() })
            }
            composable<SetupAssistantRoute> {
                SetupAssistantScreen(onBack = { navController.popBackStack() })
            }
            composable<PrivacyPolicyRoute> {
                PrivacyPolicyScreen(onBack = { navController.popBackStack() })
            }
            composable<DataPackagesRoute> {
                DataPackagesScreen(onBack = { navController.popBackStack() })
            }
            composable<LicensesRoute> {
                LicensesScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
