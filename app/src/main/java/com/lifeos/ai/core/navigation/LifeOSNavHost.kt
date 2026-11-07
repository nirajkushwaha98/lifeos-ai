package com.lifeos.ai.core.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import com.lifeos.ai.R
import com.lifeos.ai.core.ads.AdsManager
import com.lifeos.ai.core.theme.LifeColors
import com.lifeos.ai.core.theme.LocalLifeColors
import com.lifeos.ai.core.ui.GradientIcon
import com.lifeos.ai.core.ui.LifeIcons
import com.lifeos.ai.core.ui.ScreenBackground
import com.lifeos.ai.feature.activity.ActivityScreen
import com.lifeos.ai.feature.assistant.AssistantScreen
import com.lifeos.ai.feature.auth.ForgotPasswordScreen
import com.lifeos.ai.feature.auth.LoginScreen
import com.lifeos.ai.feature.auth.RegisterScreen
import com.lifeos.ai.feature.auth.VerifyEmailScreen
import com.lifeos.ai.feature.documents.DocumentPreviewScreen
import com.lifeos.ai.feature.documents.DocumentsScreen
import com.lifeos.ai.feature.expenses.ExpenseReportsScreen
import com.lifeos.ai.feature.expenses.ExpensesScreen
import com.lifeos.ai.feature.expenses.TransactionEditorScreen
import com.lifeos.ai.feature.home.HomeScreen
import com.lifeos.ai.feature.notes.NoteEditorScreen
import com.lifeos.ai.feature.notes.NotesScreen
import com.lifeos.ai.feature.onboarding.OnboardingScreen
import com.lifeos.ai.feature.profile.ProfileScreen
import com.lifeos.ai.feature.reminders.ReminderEditorScreen
import com.lifeos.ai.feature.reminders.RemindersScreen
import com.lifeos.ai.feature.settings.PrivacyPolicyScreen
import com.lifeos.ai.feature.settings.SettingsScreen
import com.lifeos.ai.feature.splash.SplashScreen
import com.lifeos.ai.feature.tasks.TaskEditorScreen
import com.lifeos.ai.feature.tasks.TasksScreen
import com.lifeos.ai.feature.tools.ToolsScreen
import com.lifeos.ai.feature.voice.VoiceScreen
import kotlin.reflect.KClass

private data class TabItem(val route: Any, val routeClass: KClass<*>, val icon: ImageVector, val label: Int)

private val tabs = listOf(
    TabItem(Routes.Home, Routes.Home::class, LifeIcons.Home, R.string.tab_home),
    TabItem(Routes.Assistant(), Routes.Assistant::class, LifeIcons.Spark, R.string.tab_assistant),
    TabItem(Routes.Tools, Routes.Tools::class, LifeIcons.Tools, R.string.tab_tools),
    TabItem(Routes.Activity, Routes.Activity::class, LifeIcons.Activity, R.string.tab_activity),
    TabItem(Routes.Profile, Routes.Profile::class, LifeIcons.Profile, R.string.tab_profile),
)

private fun NavDestination?.isTab(): Boolean = this?.hierarchy?.any { d -> tabs.any { d.hasRoute(it.routeClass) } } == true

@Composable
fun LifeOSNavHost(
    navController: NavHostController,
    adsManager: AdsManager,
    pendingRoute: String?,
    onPendingRouteConsumed: () -> Unit,
) {
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination
    val showBottomBar = current.isTab()

    val navigate: (Any) -> Unit = remember(navController) { { route -> navController.navigate(route) { launchSingleTop = true } } }
    val back: () -> Unit = remember(navController) { { navController.popBackStack(); Unit } }
    val goToMain: () -> Unit = remember(navController) {
        {
            navController.navigate(Routes.Home) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }
    val goToLogin: () -> Unit = remember(navController) {
        {
            navController.navigate(Routes.Login) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    // Deep link from a notification (e.g. reminders), once the main UI is visible.
    LaunchedEffect(pendingRoute, showBottomBar) {
        if (pendingRoute == "reminders" && showBottomBar) {
            navigate(Routes.Reminders)
            onPendingRouteConsumed()
        }
    }

    ScreenBackground {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                AnimatedVisibility(
                    visible = showBottomBar,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                ) {
                    ModernBottomNavigation(current) { tab ->
                        navController.navigate(tab.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = Routes.Splash,
                modifier = Modifier.fillMaxSize().padding(padding),
                enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 12 } },
                exitTransition = { fadeOut(tween(180)) },
                popEnterTransition = { fadeIn(tween(220)) },
                popExitTransition = { fadeOut(tween(180)) + slideOutHorizontally(tween(220)) { it / 12 } },
            ) {
                composable<Routes.Splash> {
                    SplashScreen(
                        onOnboarding = { navController.navigate(Routes.Onboarding) { popUpTo(Routes.Splash) { inclusive = true } } },
                        onLogin = { navController.navigate(Routes.Login) { popUpTo(Routes.Splash) { inclusive = true } } },
                        onMain = { navController.navigate(Routes.Home) { popUpTo(Routes.Splash) { inclusive = true } } },
                    )
                }
                composable<Routes.Onboarding> {
                    OnboardingScreen(onDone = { navController.navigate(Routes.Login) { popUpTo(Routes.Onboarding) { inclusive = true } } })
                }
                composable<Routes.Login> {
                    LoginScreen(
                        onLoggedIn = goToMain,
                        onNeedsVerification = { navController.navigate(Routes.VerifyEmail) { popUpTo(Routes.Login) { inclusive = true } } },
                        onRegister = { navigate(Routes.Register) },
                        onForgotPassword = { navigate(Routes.ForgotPassword) },
                    )
                }
                composable<Routes.Register> {
                    RegisterScreen(
                        onRegistered = { navController.navigate(Routes.VerifyEmail) { popUpTo(navController.graph.id) { inclusive = true } } },
                        onBack = back,
                    )
                }
                composable<Routes.ForgotPassword> { ForgotPasswordScreen(onBack = back) }
                composable<Routes.VerifyEmail> { VerifyEmailScreen(onVerified = goToMain, onSignedOut = goToLogin) }

                composable<Routes.Home> { HomeScreen(navigate = navigate, adsManager = adsManager) }
                composable<Routes.Assistant> { AssistantScreen(navigate = navigate) }
                composable<Routes.Tools> { ToolsScreen(navigate = navigate) }
                composable<Routes.Activity> { ActivityScreen(navigate = navigate, adsManager = adsManager) }
                composable<Routes.Profile> { ProfileScreen(navigate = navigate, onSignedOut = goToLogin) }

                composable<Routes.Tasks> { TasksScreen(onBack = back, navigate = navigate) }
                composable<Routes.TaskEditor> { TaskEditorScreen(onBack = back) }
                composable<Routes.Reminders> { RemindersScreen(onBack = back, navigate = navigate) }
                composable<Routes.ReminderEditor> { ReminderEditorScreen(onBack = back) }
                composable<Routes.Expenses> { ExpensesScreen(onBack = back, navigate = navigate) }
                composable<Routes.TransactionEditor> { TransactionEditorScreen(onBack = back) }
                composable<Routes.ExpenseReports> { ExpenseReportsScreen(onBack = back, adsManager = adsManager) }
                composable<Routes.Notes> { NotesScreen(onBack = back, navigate = navigate) }
                composable<Routes.NoteEditor> { NoteEditorScreen(onBack = back) }
                composable<Routes.Documents> { DocumentsScreen(onBack = back, navigate = navigate) }
                composable<Routes.DocumentPreview> { DocumentPreviewScreen(onBack = back) }
                composable<Routes.Voice>(
                    enterTransition = { fadeIn(tween(260)) + scaleIn(tween(380, easing = FastOutSlowInEasing), initialScale = 0.9f) },
                    exitTransition = { fadeOut(tween(180)) },
                    popEnterTransition = { fadeIn(tween(220)) },
                    popExitTransition = { fadeOut(tween(220)) + scaleOut(tween(260), targetScale = 0.94f) },
                ) {
                    VoiceScreen(
                        onBack = back,
                        navigate = navigate,
                        onOpenChat = { conversationId ->
                            navController.navigate(Routes.Assistant(conversationId = conversationId)) {
                                popUpTo<Routes.Voice> { inclusive = true }
                                launchSingleTop = true
                            }
                        },
                    )
                }
                composable<Routes.Settings> {
                    SettingsScreen(onBack = back, onPrivacyPolicy = { navigate(Routes.PrivacyPolicy) }, onSignedOut = goToLogin, adsManager = adsManager)
                }
                composable<Routes.PrivacyPolicy> { PrivacyPolicyScreen(onBack = back) }
            }
        }
    }
}

@Composable
private fun ModernBottomNavigation(current: NavDestination?, onSelect: (TabItem) -> Unit) {
    val colors = LocalLifeColors.current
    Box(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(66.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f))
                .border(1.dp, colors.glassBorder, RoundedCornerShape(24.dp)),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                val selected = current?.hierarchy?.any { it.hasRoute(tab.routeClass) } == true
                val label = stringResource(tab.label)
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onSelect(tab) }
                        .semantics { this.selected = selected }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val pill = RoundedCornerShape(15.dp)
                    Box(
                        Modifier.size(width = 52.dp, height = 30.dp).clip(pill)
                            .then(
                                if (selected) Modifier
                                    .background(Brush.linearGradient(listOf(LifeColors.Blue.copy(alpha = 0.28f), LifeColors.Purple.copy(alpha = 0.28f))))
                                    .border(1.dp, Brush.linearGradient(listOf(LifeColors.Cyan.copy(alpha = 0.7f), LifeColors.Purple.copy(alpha = 0.25f))), pill)
                                else Modifier
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            GradientIcon(tab.icon, colors.accentGradient, contentDescription = null, modifier = Modifier.size(22.dp))
                        } else {
                            Icon(tab.icon, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(22.dp))
                        }
                    }
                    Text(
                        label, style = MaterialTheme.typography.labelSmall,
                        color = if (selected) LifeColors.Cyan else colors.textSecondary, maxLines = 1,
                    )
                }
            }
        }
    }
}
