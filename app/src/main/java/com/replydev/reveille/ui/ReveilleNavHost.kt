package com.replydev.reveille.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.dialog
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.replydev.reveille.ui.components.NavBottomSheet
import com.replydev.reveille.ui.components.ReveilleNavBar
import com.replydev.reveille.ui.components.Tab
import com.replydev.reveille.ui.components.rememberFailingChecks
import com.replydev.reveille.ui.home.HomeScreen
import com.replydev.reveille.ui.oneoff.OneOffEditorScreen
import com.replydev.reveille.ui.policies.PoliciesScreen
import com.replydev.reveille.ui.policies.PolicyEditorScreen
import com.replydev.reveille.ui.reliability.ReliabilityScreen
import com.replydev.reveille.ui.settings.SettingsScreen
import com.replydev.reveille.ui.theme.Motion
import com.replydev.reveille.ui.theme.ReveilleTheme

object Routes {
    val HOME = Tab.Today.route
    val POLICIES = Tab.Policies.route
    const val POLICY = "policy/{id}"
    const val ONE_OFF = "oneoff/{id}"
    val SETTINGS = Tab.Settings.route
    val RELIABILITY = Tab.Health.route

    fun policy(id: Long) = "policy/$id"
    fun oneOff(id: Long) = "oneoff/$id"
}

private fun NavBackStackEntry.tab(): Tab? = Tab.of(destination.route)

@Composable
fun ReveilleNavHost() {
    val nav = rememberNavController()
    val density = LocalDensity.current
    val failingChecks = rememberFailingChecks()
    val current by nav.currentBackStackEntryAsState()
    // The one-off sheet floats over the screen that opened it; that screen (and its bar) stays where it is.
    val base = if (current?.destination?.route == Routes.ONE_OFF) nav.previousBackStackEntry else current
    val selectedTab = base?.tab()

    // Tab switches fade through in place; pushes and pops use Material shared axis X, and Back runs it in reverse. A
    // fade leaves a moment where neither screen is opaque, so the host paints the app background: otherwise the window
    // shows through as a flash. The back gesture keeps Navigation's predictive back preview.
    fun AnimatedContentTransitionScope<NavBackStackEntry>.betweenTabs() = initialState.tab() != null && targetState.tab() != null
    Box(Modifier.fillMaxSize().background(ReveilleTheme.colors.bg)) {
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.fillMaxSize(),
            enterTransition = { if (betweenTabs()) Motion.fadeThroughEnter else Motion.sharedAxisEnter(density, forward = true) },
            exitTransition = { if (betweenTabs()) Motion.fadeThroughExit else Motion.sharedAxisExit(density, forward = true) },
            popEnterTransition = { if (betweenTabs()) Motion.fadeThroughEnter else Motion.sharedAxisEnter(density, forward = false) },
            popExitTransition = { if (betweenTabs()) Motion.fadeThroughExit else Motion.sharedAxisExit(density, forward = false) },
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    failingChecks = failingChecks,
                    onOpenReliability = { nav.openTab(Tab.Health) },
                    onNewOneOff = { nav.navigate(Routes.oneOff(0)) },
                    onNewPolicy = { nav.navigate(Routes.policy(0)) },
                    onEditOneOff = { nav.navigate(Routes.oneOff(it)) },
                    onOpenPolicy = { nav.navigate(Routes.policy(it)) },
                )
            }
            composable(Routes.POLICIES) { PoliciesScreen(onOpen = { nav.navigate(Routes.policy(it)) }) }
            // The editors read the "id" argument through their ScreenSession's SavedStateHandle.
            composable(Routes.POLICY, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                PolicyEditorScreen(onDone = { nav.leave(entry) })
            }
            dialog(
                Routes.ONE_OFF,
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
                dialogProperties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
            ) { entry ->
                NavBottomSheet(onDismissed = { nav.leave(entry) }) { close -> OneOffEditorScreen(onDone = close) }
            }
            composable(Routes.SETTINGS) { SettingsScreen() }
            composable(Routes.RELIABILITY) { ReliabilityScreen() }
        }
        AnimatedVisibility(
            visible = selectedTab != null,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(Motion.MEDIUM2, easing = Motion.EmphasizedDecelerate)) { it } + fadeIn(tween(Motion.SHORT3)),
            exit = slideOutVertically(tween(Motion.SHORT3 * 2, easing = Motion.EmphasizedAccelerate)) { it } + fadeOut(tween(Motion.SHORT3)),
        ) {
            ReveilleNavBar(
                selected = selectedTab,
                onSelect = { nav.openTab(it) },
                healthBadge = failingChecks != null && failingChecks != 0,
            )
        }
    }
}

/** Switches to [tab], keeping one entry per tab: Back from any tab returns to Today, and each tab keeps its state. */
private fun NavController.openTab(tab: Tab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Pops [entry] only while it is the current screen, so a double tap on Back (or Back after Save) cannot pop Home. */
private fun NavController.leave(entry: NavBackStackEntry) {
    if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) popBackStack()
}
