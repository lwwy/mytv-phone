package com.mytvphone.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mytvphone.app.MainViewModel
import com.mytvphone.app.data.Channel

object Routes {
    const val LIST = "list"
    const val PLAYER = "player"
    const val SETTINGS = "settings"
}

@Composable
fun AppNav(vm: MainViewModel) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.LIST) {
        composable(Routes.LIST) {
            ChannelListScreen(
                vm = vm,
                onPlay = { ch: Channel ->
                    vm.playChannel(ch)
                    nav.navigate(Routes.PLAYER) { launchSingleTop = true }
                },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.PLAYER) {
            PlayerScreen(vm = vm, onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(vm = vm, onBack = { nav.popBackStack() })
        }
    }
}
