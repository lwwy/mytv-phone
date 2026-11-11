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
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.mytvphone.app.MainViewModel
import com.mytvphone.app.R
import com.mytvphone.app.data.Channel
import android.content.Context
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * 页面内各层的淡入淡出节奏统一短于 NavHost 的页面转场：
 * 退出时先于页面转场收干净，避免两层动画叠加造成的「闪一下」。
 */
private val UiFadeIn = fadeIn(tween(180, easing = FastOutSlowInEasing))
private val UiFadeOut = fadeOut(tween(130, easing = FastOutLinearInEasing))

/**
 * 横屏全屏播放页（纯净模式：没有任何常驻/可唤出的控制栏）。
 *  - 唯一的交互：单击屏幕左侧 1/3 呼出悬浮选台抽屉，5 秒无操作自动收起
 *  - 双击、左右滑动、单击右侧区域均不响应，避免误触
 *  - 音量用手机物理键，换台用抽屉；退出靠返回键 / 全面屏返回手势
 *  - 返回键由 BackHandler 统一接住：面板打开时先收面板
 *
 * 系统栏显隐、状态栏图标明暗、屏幕方向**全部由 AppNav 按路由在首帧统一处理**。
 * 这里绝不再写 window / requestedOrientation：旧实现放在 onDispose 里，要等退出动画
 * 放完才恢复，还会与 AppNav 双重写入互相覆盖，正是返回设置页「先闪一下再跳一下」的根因。
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val st by vm.controller.state.collectAsStateWithLifecycle()
    // 用可观察的 StateFlow：在播放页导入新源时抽屉里的列表会跟着刷新，
    // 直接读 vm.channels.value 只在重组时才取到新值，可能一直是旧列表。
    val channels by vm.channels.collectAsStateWithLifecycle()

    var showChannelPanel by remember { mutableStateOf(false) }
    // 每次面板内交互自增，用来重置 5 秒自动隐藏计时
    var panelTick by remember { mutableStateOf(0) }
    var lastError by remember { mutableStateOf("") }

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

    LaunchedEffect(err) { if (err != null) lastError = err }

    // 面板弹出后 8 秒无操作自动收起，回到纯净全屏。
    // 读屏（TalkBack）用户还没听完频道名就可能被收起：检测到触摸探索开启时
    // 不自动收起，避免 WCAG 2.2.1「时限可调」违规。分组点击 / 列表滚动 /
    // 任意点击都会回调 onInteraction 自增 panelTick，重置计时（焦点移动也会续期）。
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    val touchExploration = am?.isTouchExplorationEnabled == true
    LaunchedEffect(showChannelPanel, panelTick) {
        if (showChannelPanel && !touchExploration) {
            delay(8000)
            showChannelPanel = false
        }
    }

    BackHandler {
        if (showChannelPanel) showChannelPanel = false else leave()
    }

    // 触摸只做一件事：单击左侧 1/3 呼出选台抽屉；抽屉已开时点任意处收起。
    // 拖动、双击、单击右侧区域均不响应，避免误触。
    val tapModifier = Modifier.pointerInput(Unit) {
        detectTapGestures(
            onTap = { offset ->
                if (showChannelPanel) {
                    showChannelPanel = false
                } else if (offset.x < size.width * 0.33f) {
                    showChannelPanel = true
                }
            },
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

        // 选台抽屉：从左侧滑入的悬浮卡片，点频道换台并自动收起
        AnimatedVisibility(
            visible = showChannelPanel,
            enter = slideInHorizontally(tween(220)) { -it },
            exit = slideOutHorizontally(tween(180)) { -it },
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(PANEL_WIDTH + 12.dp),
        ) {
            ChannelPickerPanel(
                channels = channels,
                currentName = st.channelName,
                currentGroup = st.group,
                onPick = { ch ->
                    vm.playChannel(ch)
                    showChannelPanel = false
                },
                onInteraction = { panelTick++ },
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

    }
}

/**
 * 悬浮选台抽屉。
 *
 * 设计要点：
 *  - 不再是铺满左侧的黑块，而是一张悬浮圆角卡片（80% 不透明深灰），后方视频隐约可见；
 *  - 顶部留 72dp 安全区、四周留边距，避免遮住左上角台标与屏幕圆角/刘海；
 *  - 左侧分组改为纯文字竖排，选中项高亮加粗 + 底部小横线，没有数字色块；
 *  - 右侧频道改为圆角小卡片，选中项淡蓝半透明底 + 蓝色文字 + 小播放图标；
 *  - 组点击 / 列表滚动都会回调 [onInteraction]，用于重置 8 秒自动隐藏计时。
 */
@Composable
private fun ChannelPickerPanel(
    channels: List<Channel>,
    currentName: String,
    currentGroup: String,
    onPick: (Channel) -> Unit,
    onInteraction: () -> Unit,
) {
    val all = "全部"
    var selGroup by remember { mutableStateOf(all) }
    val groups = remember(channels) { listOf(all) + channels.map { it.group }.distinct() }
    val inGroup = remember(channels, selGroup) {
        channels.filter { selGroup == all || it.group == selGroup }
    }
    val listState = rememberLazyListState()

    // 打开抽屉直接定位到当前正在播放的频道，长列表不用手动翻。
    val currentIndex = remember(inGroup, currentName, currentGroup) {
        inGroup.indexOfFirst { it.name == currentName && it.group == currentGroup }
    }
    LaunchedEffect(inGroup) {
        if (currentIndex >= 0) listState.scrollToItem(currentIndex)
    }

    // 滚动期间每秒续期一次，避免「手指还在滑，面板 5 秒到点自己关掉」
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collectLatest { scrolling ->
            while (scrolling) {
                onInteraction()
                delay(1000)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            // 顶部留出台标安全区，底部留出手势条区域
            .padding(start = 12.dp, top = 72.dp, bottom = 24.dp)
            // 消费面板内的点击：既不会穿透到播放器关闭面板，也算作一次操作
            .pointerInput(Unit) { detectTapGestures(onTap = { onInteraction() }) },
    ) {
        Surface(
            modifier = Modifier
                .width(PANEL_WIDTH)
                .fillMaxHeight(),
            shape = MaterialTheme.shapes.large,
            // 让抽屉成为受主题管理的 Surface：颜色全部走角色，
            // 浅色主题下不再是「深色硬写 + 主题 primary 文字」，对比度回到达标区间；
            // 同时 Surface 自带与 contentColor 匹配的 ripple，按下可见反馈（P0 #1/#2）
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(vertical = 14.dp),
            ) {
                Row(
                    modifier = Modifier.padding(start = 18.dp, end = 16.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "选台",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "  ${channels.size} 个频道",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                Row(Modifier.weight(1f)) {
                    // 左侧分组：纯文字竖排
                    LazyColumn(
                        modifier = Modifier
                            .width(GROUP_RAIL_WIDTH)
                            .fillMaxHeight(),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        items(groups) { g ->
                            val active = g == selGroup
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable {
                                        selGroup = g
                                        onInteraction()
                                    }
                                    .padding(horizontal = 18.dp, vertical = 9.dp),
                            ) {
                                Text(
                                    text = g,
                                    color = if (active) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (active) {
                                    Box(
                                        modifier = Modifier
                                            .padding(top = 6.dp)
                                            .width(16.dp)
                                            .height(2.dp)
                                            .background(
                                                MaterialTheme.colorScheme.primary,
                                                RoundedCornerShape(1.dp),
                                            ),
                                    )
                                }
                            }
                        }
                    }

                    // 右侧频道：圆角小卡片
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentPadding = PaddingValues(start = 2.dp, end = 14.dp, bottom = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        itemsIndexed(inGroup, key = { _, ch -> ch.id }) { _, ch ->
                            val cur = ch.name == currentName && ch.group == currentGroup
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (cur) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            Color.Transparent
                                        },
                                    )
                                    .clickable { onPick(ch) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = ch.title.ifEmpty { ch.name },
                                    modifier = Modifier.weight(1f),
                                    color = if (cur) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (cur) {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = "正在播放",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier
                                            .padding(start = 6.dp)
                                            .size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 抽屉宽度：手机上约占横屏宽度的 40%，不遮挡正在播放的画面主体。 */
private val PANEL_WIDTH = 320.dp

/** 分组栏宽度：只放纯文字，避免挤压右侧频道名。 */
private val GROUP_RAIL_WIDTH = 96.dp
