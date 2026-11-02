package com.replydev.reveille.ui

import androidx.compose.runtime.Composable
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
import com.replydev.reveille.ui.settings.SettingsScreen

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
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenPolicies = { nav.navigate(Routes.POLICIES) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenReliability = { nav.navigate(Routes.RELIABILITY) },
                onNewOneOff = { nav.navigate(Routes.oneOff(0)) },
                onEditOneOff = { nav.navigate(Routes.oneOff(it)) },
            )
        }
        composable(Routes.POLICIES) { entry ->
            PoliciesScreen(onBack = { nav.leave(entry) }, onOpen = { nav.navigate(Routes.policy(it)) })
        }
        composable(Routes.POLICY, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            PolicyEditorScreen(id = entry.arguments?.getLong("id") ?: 0L, onDone = { nav.leave(entry) })
        }
        composable(Routes.ONE_OFF, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            OneOffEditorScreen(id = entry.arguments?.getLong("id") ?: 0L, onDone = { nav.leave(entry) })
        }
        composable(Routes.SETTINGS) { entry -> SettingsScreen(onBack = { nav.leave(entry) }) }
    }
}

/** Pops [entry] only while it is the current screen, so a double tap on Back (or Back after Save) cannot pop Home. */
private fun NavController.leave(entry: NavBackStackEntry) {
    if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) popBackStack()
}
