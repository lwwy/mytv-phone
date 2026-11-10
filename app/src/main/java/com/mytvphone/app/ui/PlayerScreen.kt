package com.mytvphone.app.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import androidx.activity.compose.BackHandler
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.mytvphone.app.MainViewModel
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * 横屏全屏播放页。
 *  - 单击显隐控制层，双击暂停/播放
 *  - 全屏任意位置上下滑换台
 *  - 左右滑：左半屏调亮度、右半屏调音量
 *  - 返回键 / 全面屏手势返回由 BackHandler 统一接住
 */
@Composable
fun PlayerScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val st by vm.controller.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    var showControls by remember { mutableStateOf(true) }
    var overlay by remember { mutableStateOf<String?>(null) }

    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val scrim = MaterialTheme.colorScheme.scrim

    // 只在此页面强制横屏 + 沉浸式；离开时恢复系统栏与方向
    DisposableEffect(settings.lockLandscape) {
        val prevOrientation = activity?.requestedOrientation
            ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = if (settings.lockLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        // 全局已 enableEdgeToEdge()，这里只隐藏系统栏，不再改 decorFits（离开时避免破坏边缘布局）
        activity?.window?.let { w ->
            WindowCompat.getInsetsController(w, w.decorView)
                .hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            activity?.window?.let { w ->
                WindowCompat.getInsetsController(w, w.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
            activity?.requestedOrientation = prevOrientation
        }
    }

    // 离开播放页就停止播放，避免退回列表后还有声音
    DisposableEffect(Unit) {
        onDispose { vm.controller.stop() }
    }

    LaunchedEffect(showControls) {
        if (showControls) {
            delay(4000)
            showControls = false
        }
    }

    LaunchedEffect(overlay) {
        if (overlay != null) {
            delay(1200)
            overlay = null
        }
    }

    BackHandler {
        if (showControls) showControls = false else onBack()
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
                                overlay = "亮度 ${(next * 100).toInt()}%"
                            }
                        } else {
                            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                            val next = (cur + delta * max * 1.5f).toInt().coerceIn(0, max)
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                            overlay = "音量 ${(next * 100 / max.coerceAtLeast(1))}%"
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
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    player = vm.controller.player
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (st.buffering && st.error == null) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        if (st.error == null && !st.buffering && showControls) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart)
                    .background(scrim.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回列表", tint = Color.White)
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
                PlayerControlIcon(Icons.Default.SkipPrevious, "上一个频道", 38.dp) { vm.prevChannel() }
                PlayerControlIcon(
                    if (st.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    "播放/暂停",
                    48.dp,
                ) { vm.controller.togglePlayPause() }
                PlayerControlIcon(Icons.Default.SkipNext, "下一个频道", 38.dp) { vm.nextChannel() }
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

        st.error?.let { err ->
            Card(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("播放失败", style = MaterialTheme.typography.titleMedium)
                    Text(err, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { vm.controller.retry() }) { Text("重试") }
                        FilledTonalButton(onClick = { vm.nextChannel() }) { Text("下一个频道") }
                    }
                }
            }
        }

        overlay?.let { msg ->
            Text(
                text = msg,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.Center)
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
