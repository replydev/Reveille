package com.replydev.reveille.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.replydev.reveille.ui.home.HomeScreen
import com.replydev.reveille.ui.oneoff.OneOffEditorScreen
import com.replydev.reveille.ui.policies.PoliciesScreen
import com.replydev.reveille.ui.policies.PolicyEditorScreen
import com.replydev.reveille.ui.reliability.ReliabilityScreen
import com.replydev.reveille.ui.settings.SettingsScreen
import com.replydev.reveille.ui.theme.Motion

object Routes {
    const val HOME = "home"
    const val POLICIES = "policies"
    const val POLICY = "policy/{id}"
    const val ONE_OFF = "oneoff/{id}"
    const val SETTINGS = "settings"
    const val RELIABILITY = "reliability"

    fun policy(id: Long) = "policy/$id"
    fun oneOff(id: Long) = "oneoff/$id"
}

@Composable
fun ReveilleNavHost() {
    val nav = rememberNavController()
    val density = LocalDensity.current
    // Material shared axis X instead of the default 700 ms crossfade. Pop runs the same motion in reverse, which also
    // drives the predictive back preview.
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        enterTransition = { Motion.sharedAxisEnter(density, forward = true) },
        exitTransition = { Motion.sharedAxisExit(density, forward = true) },
        popEnterTransition = { Motion.sharedAxisEnter(density, forward = false) },
        popExitTransition = { Motion.sharedAxisExit(density, forward = false) },
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenPolicies = { nav.navigate(Routes.POLICIES) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenReliability = { nav.navigate(Routes.RELIABILITY) },
                onNewOneOff = { nav.navigate(Routes.oneOff(0)) },
                onNewPolicy = { nav.navigate(Routes.policy(0)) },
                onEditOneOff = { nav.navigate(Routes.oneOff(it)) },
            )
        }
        composable(Routes.POLICIES) { entry ->
            PoliciesScreen(onBack = { nav.leave(entry) }, onOpen = { nav.navigate(Routes.policy(it)) })
        }
        // The editors read the "id" argument through their ScreenSession's SavedStateHandle.
        composable(Routes.POLICY, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            PolicyEditorScreen(onDone = { nav.leave(entry) })
        }
        composable(Routes.ONE_OFF, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            OneOffEditorScreen(onDone = { nav.leave(entry) })
        }
        composable(Routes.SETTINGS) { entry -> SettingsScreen(onBack = { nav.leave(entry) }) }
        composable(Routes.RELIABILITY) { entry -> ReliabilityScreen(onBack = { nav.leave(entry) }) }
    }
}

/** Pops [entry] only while it is the current screen, so a double tap on Back (or Back after Save) cannot pop Home. */
private fun NavController.leave(entry: NavBackStackEntry) {
    if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) popBackStack()
}
