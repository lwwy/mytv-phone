package com.mytvphone.app.ui

import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.mytvphone.app.data.Channel
import kotlinx.coroutines.delay

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
 *  - 单击：屏幕左侧弹出「选台」面板，其余区域显隐控制层
 *  - 双击暂停/播放
 *  - 左侧滑亮度、右侧滑音量、上下滑换台等手势已取消（音量用手机物理键，换台用选台面板）
 *  - 返回键 / 全面屏手势返回由 BackHandler 统一接住
 *
 * 系统栏显隐、状态栏图标明暗、屏幕方向**全部由 AppNav 按路由在首帧统一处理**。
 * 这里绝不再写 window / requestedOrientation：旧实现放在 onDispose 里，要等退出动画
 * 放完才恢复，还会与 AppNav 双重写入互相覆盖，正是返回设置页「先闪一下再跳一下」的根因。
 */
@Composable
fun PlayerScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val st by vm.controller.state.collectAsStateWithLifecycle()

    var showControls by remember { mutableStateOf(true) }
    var showChannelPanel by remember { mutableStateOf(false) }
    var overlayText by remember { mutableStateOf("") }
    var overlayVisible by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf("") }

    val scrim = MaterialTheme.colorScheme.scrim
    val err = st.error

    // 转场动画结束后才 dispose，此时再彻底停止播放
    DisposableEffect(Unit) {
        onDispose { vm.controller.stop() }
    }

    // 退出前先暂停并保留最后一帧：淡出期间画面不再变化，过渡更连贯
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
        if (showControls && !showChannelPanel) {
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
        when {
            showChannelPanel -> showChannelPanel = false
            showControls -> showControls = false
            else -> leave()
        }
    }

    // 单击：左侧 35% 区域弹出选台面板，其余区域显隐控制层；双击暂停/播放
    val tapModifier = Modifier.pointerInput(Unit) {
        detectTapGestures(
            onTap = { offset ->
                if (showChannelPanel) {
                    showChannelPanel = false
                } else if (offset.x < size.width * 0.35f) {
                    showControls = false
                    showChannelPanel = true
                } else {
                    showControls = !showControls
                }
            },
            onDoubleTap = { vm.controller.togglePlayPause() },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(tapModifier),
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
            visible = showControls && !showChannelPanel && err == null,
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

        // 选台面板：从左侧滑入，左侧分组栏 + 右侧频道列表，当前频道蓝色高亮
        AnimatedVisibility(
            visible = showChannelPanel,
            enter = slideInHorizontally(tween(220)) { -it },
            exit = slideOutHorizontally(tween(180)) { -it },
            modifier = Modifier.fillMaxSize(),
        ) {
            ChannelPickerPanel(
                channels = vm.channels.value,
                currentName = st.channelName,
                currentGroup = st.group,
                onPick = { ch ->
                    vm.playChannel(ch)
                    showChannelPanel = false
                },
            )
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

/**
 * 选台面板：左侧分组栏 + 右侧频道列表。参照 TV 端换台 UI，
 * 选中分组高亮、当前播放频道蓝色条高亮。点频道即换台并关闭面板。
 */
@Composable
private fun ChannelPickerPanel(
    channels: List<Channel>,
    currentName: String,
    currentGroup: String,
    onPick: (Channel) -> Unit,
) {
    val all = "全部"
    var selGroup by remember { mutableStateOf(all) }
    val groups = remember(channels) { listOf(all) + channels.map { it.group }.distinct() }
    val inGroup = remember(channels, selGroup) {
        channels.filter { selGroup == all || it.group == selGroup }
    }

    Row(Modifier.fillMaxSize()) {
        // 左侧分组栏
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(0.34f)
                .background(Color(0xE6000000))
                .padding(vertical = 10.dp),
        ) {
            LazyColumn {
                itemsIndexed(groups) { idx, g ->
                    val count = if (g == all) channels.size else channels.count { it.group == g }
                    val active = g == selGroup
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .background(
                                if (active) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                                } else {
                                    Color.Transparent
                                },
                                MaterialTheme.shapes.small,
                            )
                            .clickable { selGroup = g }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .background(
                                    if (active) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    MaterialTheme.shapes.extraSmall,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${idx + 1}",
                                color = if (active) Color.White else Color.Black,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Text(
                            g,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 10.dp),
                            color = if (active) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.White
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "$count",
                            color = Color.White.copy(alpha = 0.55f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }

        // 右侧频道列表
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f)
                .background(Color(0xCC000000))
                .padding(10.dp),
        ) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(inGroup) { idx, ch ->
                    val cur = ch.name == currentName && ch.group == currentGroup
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (cur) MaterialTheme.colorScheme.primary else Color.Transparent,
                                MaterialTheme.shapes.small,
                            )
                            .clickable { onPick(ch) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(
                                    if (cur) {
                                        Color.White
                                    } else {
                                        Color.White.copy(alpha = 0.14f)
                                    },
                                    MaterialTheme.shapes.extraSmall,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${idx + 1}",
                                color = if (cur) MaterialTheme.colorScheme.primary else Color.White,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        Text(
                            ch.title.ifEmpty { ch.name },
                            modifier = Modifier.padding(start = 12.dp),
                            color = if (cur) Color.White else Color.White.copy(alpha = 0.9f),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
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
