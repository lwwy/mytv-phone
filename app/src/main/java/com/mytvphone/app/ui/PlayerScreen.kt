package com.mytvphone.app.ui

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import com.mytvphone.app.MainViewModel
import com.mytvphone.app.R
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * 页面内各层的淡入淡出节奏统一短于 NavHost 的页面转场：
 * 退出时先于页面转场收干净，避免两层动画叠加造成的「闪一下」。
 */
private val UiFadeIn = fadeIn(tween(180, easing = FastOutSlowInEasing))
private val UiFadeOut = fadeOut(tween(130, easing = FastOutLinearInEasing))
private val HintFadeIn = fadeIn(tween(110))
private val HintFadeOut = fadeOut(tween(180, delayMillis = 60))

/**
 * 横屏全屏播放页。
 *  - 单击显隐控制层，双击暂停/播放
 *  - 全屏任意位置上下滑换台
 *  - 左右滑：左半屏调亮度、右半屏调音量
 *  - 返回键 / 全面屏手势返回由 BackHandler 统一接住
 *
 * 系统栏显隐、状态栏图标明暗、屏幕方向**全部由 AppNav 按路由在首帧统一处理**。
 * 这里绝不再写 window / requestedOrientation：旧实现放在 onDispose 里，要等退出动画
 * 放完才恢复，还会与 AppNav 双重写入互相覆盖，正是返回设置页「先闪一下再跳一下」的根因。
 */
@Composable
fun PlayerScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val st by vm.controller.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    var showControls by remember { mutableStateOf(true) }
    var overlayText by remember { mutableStateOf("") }
    var overlayVisible by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf("") }

    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val scrim = MaterialTheme.colorScheme.scrim
    val err = st.error

    // 转场动画结束后才 dispose，此时再彻底停止播放
    DisposableEffect(Unit) {
        onDispose { vm.controller.stop() }
    }

    // 退出前先静音并保留最后一帧：淡出期间画面不再变化，过渡更连贯
    fun leave() {
        vm.controller.pause()
        onBack()
    }

    fun showOverlay(msg: String) {
        overlayText = msg
        overlayVisible = true
    }

    LaunchedEffect(err) { if (err != null) lastError = err }

    LaunchedEffect(showControls) {
        if (showControls) {
            delay(4000)
            showControls = false
        }
    }

    LaunchedEffect(overlayText, overlayVisible) {
        if (overlayVisible) {
            delay(1200)
            overlayVisible = false
        }
    }

    BackHandler {
        if (showControls) showControls = false else leave()
    }

    val tapModifier = Modifier.pointerInput(Unit) {
        detectTapGestures(
            onTap = { showControls = !showControls },
            onDoubleTap = { vm.controller.togglePlayPause() },
        )
    }

    val dragModifier = if (settings.gesturesEnabled) {
        Modifier.pointerInput(Unit) {
            var mode = 0 // 0=未定, 1=垂直, 2=水平
            var accX = 0f
            var accY = 0f
            var originX = 0f
            detectDragGestures(
                onDragStart = { off ->
                    mode = 0
                    accX = 0f
                    accY = 0f
                    originX = off.x
                },
                onDrag = { _, dragAmount ->
                    accX += dragAmount.x
                    accY += dragAmount.y
                    if (mode == 0 && (abs(accX) > 12f || abs(accY) > 12f)) {
                        mode = if (abs(accX) > abs(accY)) 2 else 1
                    }
                    if (mode == 2) {
                        val delta = dragAmount.x / size.width.toFloat()
                        if (originX < size.width / 2f) {
                            val w = activity?.window
                            val lp = w?.attributes
                            if (lp != null) {
                                val cur = if (lp.screenBrightness in 0f..1f) lp.screenBrightness else 0.5f
                                val next = (cur + delta).coerceIn(0.02f, 1f)
                                lp.screenBrightness = next
                                w.attributes = lp
                                showOverlay("亮度 ${(next * 100).toInt()}%")
                            }
                        } else {
                            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                            val next = (cur + delta * max * 1.5f).toInt().coerceIn(0, max)
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                            showOverlay("音量 ${(next * 100 / max.coerceAtLeast(1))}%")
                        }
                    }
                },
                onDragEnd = {
                    if (mode == 1 && abs(accY) > 90f) {
                        if (accY < 0) vm.nextChannel() else vm.prevChannel()
                    }
                    mode = 0
                },
                onDragCancel = { mode = 0 },
            )
        }
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(tapModifier)
            .then(dragModifier),
    ) {
        // TextureView（见 res/layout/player_view.xml）：普通 View 才能参与 Compose 的
        // alpha 淡出。默认 SurfaceView 是独立窗口层，退出时视频会硬切一下造成闪屏。
        AndroidView(
            factory = { ctx ->
                val view = LayoutInflater.from(ctx)
                    .inflate(R.layout.player_view, null, false) as PlayerView
                view.player = vm.controller.player
                view.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
                view
            },
            modifier = Modifier.fillMaxSize(),
        )

        AnimatedVisibility(
            visible = st.buffering && err == null,
            enter = UiFadeIn,
            exit = UiFadeOut,
            modifier = Modifier.align(Alignment.Center),
        ) {
            CircularProgressIndicator()
        }

        AnimatedVisibility(
            visible = showControls && err == null,
            enter = UiFadeIn,
            exit = UiFadeOut,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopStart)
                        .background(scrim.copy(alpha = 0.6f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { leave() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回列表",
                            tint = Color.White,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = st.channelTitle.ifEmpty { st.channelName },
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                        )
                        Text(
                            text = st.group + (st.info?.let { " · $it" } ?: ""),
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                    }
                }

                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayerControlIcon(Icons.Default.SkipPrevious, "上一个频道", 38.dp) {
                        vm.prevChannel()
                    }
                    PlayerControlIcon(
                        if (st.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        "播放/暂停",
                        48.dp,
                    ) { vm.controller.togglePlayPause() }
                    PlayerControlIcon(Icons.Default.SkipNext, "下一个频道", 38.dp) {
                        vm.nextChannel()
                    }
                }

                if (st.urlCount > 1) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .background(scrim.copy(alpha = 0.6f))
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("备用源：", color = Color.White, style = MaterialTheme.typography.bodySmall)
                        for (i in 0 until st.urlCount) {
                            TextButton(onClick = { vm.controller.selectUrl(i) }) {
                                Text(
                                    "${i + 1}",
                                    color = if (i == st.urlIndex) {
                                        MaterialTheme.colorScheme.inversePrimary
                                    } else {
                                        Color.White
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = err != null,
            enter = UiFadeIn,
            exit = UiFadeOut,
            modifier = Modifier.align(Alignment.Center),
        ) {
            Card(
                modifier = Modifier.padding(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("播放失败", style = MaterialTheme.typography.titleMedium)
                    // err 为空时（退出动画进行中）显示最后一次错误，保证淡出完整
                    Text(err ?: lastError, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { vm.controller.retry() }) { Text("重试") }
                        FilledTonalButton(onClick = { vm.nextChannel() }) { Text("下一个频道") }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = overlayVisible,
            enter = HintFadeIn,
            exit = HintFadeOut,
            modifier = Modifier.align(Alignment.Center),
        ) {
            Text(
                text = overlayText,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .background(scrim.copy(alpha = 0.72f), MaterialTheme.shapes.small)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun PlayerControlIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    iconSize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}
