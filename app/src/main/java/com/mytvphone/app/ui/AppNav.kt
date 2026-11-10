package com.mytvphone.app.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mytvphone.app.MainViewModel
import com.mytvphone.app.data.Channel

object Routes {
    const val LIST = "list"
    const val PLAYER = "player"
    const val SETTINGS = "settings"
    const val CHANNELS = "channels/{subId}"

    fun channels(subId: String) = "channels/$subId"
}

/**
 * Material 3「fade through」页面转场：旧页先淡出、新页稍后淡入，
 * 不做位移与缩放，避免播放页视频画面被拉伸，也避免黑白底硬切造成闪屏。
 */
private val PageFadeIn = fadeIn(
    animationSpec = tween(280, delayMillis = 50, easing = FastOutSlowInEasing),
)
private val PageFadeOut = fadeOut(
    animationSpec = tween(160, easing = FastOutLinearInEasing),
)

@Composable
fun AppNav(vm: MainViewModel) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val activity = context as? Activity
    val darkTheme = isSystemInDarkTheme()
    val settings by vm.settings.collectAsStateWithLifecycle()
    // 导入面板挂在导航顶层（而非某个子页面）：这样无论在列表页还是横屏播放页，
    // 从外部「分享 / 打开」文件进来都能正常弹出 ImportSheet
    val showImport by vm.showImport.collectAsStateWithLifecycle()
    val external by vm.externalText.collectAsStateWithLifecycle()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route

    // 全局唯一的消息出口：Material 3 Snackbar
    LaunchedEffect(Unit) {
        vm.toast.collect { msg -> snackbar.showSnackbar(msg) }
    }

    /**
     * 系统栏显隐、状态栏图标明暗、屏幕方向统一随路由切换。
     *
     * 旧实现放在 PlayerScreen 的 DisposableEffect.onDispose 里：Compose Navigation 会在
     * 退出动画结束后才 dispose 旧页面，于是系统栏与方向在动画放完的那一帧瞬间恢复，
     * 表现为「先闪一下再跳一下」。这里改为在路由变化的第一帧同步切换，与转场动画
     * 同时开始，整个过渡一次完成。
     */
    LaunchedEffect(route, darkTheme, settings.lockLandscape) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (route == Routes.PLAYER) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            // 全屏黑底下状态栏图标必须是白色
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
            activity.requestedOrientation = if (settings.lockLandscape) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    Box(Modifier.fillMaxSize()) {
        NavHost(
            navController = nav,
            startDestination = Routes.LIST,
            enterTransition = { PageFadeIn },
            exitTransition = { PageFadeOut },
            popEnterTransition = { PageFadeIn },
            popExitTransition = { PageFadeOut },
        ) {
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
                SettingsScreen(
                    vm = vm,
                    onBack = { nav.popBackStack() },
                    onEditChannels = { subId -> nav.navigate(Routes.channels(subId)) },
                )
            }
            composable(Routes.CHANNELS) { entry ->
                ChannelEditScreen(
                    vm = vm,
                    subId = entry.arguments?.getString("subId").orEmpty(),
                    onBack = { nav.popBackStack() },
                )
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        )
        if (showImport) {
            ImportSheet(
                vm = vm,
                initial = external,
                onDismiss = { vm.hideImportSheet() },
            )
        }
    }
}
