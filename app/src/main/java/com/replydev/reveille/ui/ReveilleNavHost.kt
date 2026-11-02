package com.replydev.reveille.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.replydev.reveille.ui.home.HomeScreen

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
    }
}
