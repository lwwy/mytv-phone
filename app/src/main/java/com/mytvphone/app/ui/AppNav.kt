package com.mytvphone.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val snackbar = remember { SnackbarHostState() }

    // 全局唯一的消息出口：Material 3 Snackbar，替代散落在各页面的系统 Toast
    LaunchedEffect(Unit) {
        vm.toast.collect { msg -> snackbar.showSnackbar(msg) }
    }

    Box(Modifier.fillMaxSize()) {
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
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        )
    }
}
