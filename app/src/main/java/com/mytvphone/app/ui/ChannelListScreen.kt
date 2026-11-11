@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mytvphone.app.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mytvphone.app.MainViewModel
import com.mytvphone.app.data.Channel

private const val ALL_GROUP = "全部"

@Composable
fun ChannelListScreen(
    vm: MainViewModel,
    onPlay: (Channel) -> Unit,
    onSettings: () -> Unit,
) {
    val channels by vm.channels.collectAsStateWithLifecycle()

    val activity = LocalContext.current as? Activity

    var selectedGroup by rememberSaveable { mutableStateOf(ALL_GROUP) }
    var lastBack by remember { mutableStateOf(0L) }

    val groups = remember(channels) {
        listOf(ALL_GROUP) + channels.map { it.group }.distinct()
    }
    val filtered = remember(channels, selectedGroup) {
        channels.filter { selectedGroup == ALL_GROUP || it.group == selectedGroup }
    }

    // 切换订阅后，旧分组可能已不存在；若当前选中分组不在新分组里就重置为「全部」，
    // 否则 ch.group != selectedGroup 会把所有频道筛掉，误显示「没有匹配的频道」
    LaunchedEffect(channels) {
        if (selectedGroup != ALL_GROUP && channels.none { it.group == selectedGroup }) {
            selectedGroup = ALL_GROUP
        }
    }

    // 三键返回与全面屏手势返回统一走这里，2 秒内再按一次退出
    BackHandler {
        val now = System.currentTimeMillis()
        if (now - lastBack < 2000) {
            activity?.finish()
        } else {
            lastBack = now
            vm.showToast("再按一次返回键退出")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("口袋电视") },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            Text(
                text = "${filtered.size} 个频道",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )

            if (groups.size > 2) {
                ScrollableTabRow(
                    selectedTabIndex = groups.indexOf(selectedGroup).coerceAtLeast(0),
                    edgePadding = 16.dp,
                ) {
                    groups.forEach { g ->
                        Tab(
                            selected = selectedGroup == g,
                            onClick = { selectedGroup = g },
                            text = { Text(g) },
                        )
                    }
                }
            }

            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    ) {
                        Icon(
                            Icons.Default.Tv,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.size(12.dp))
                        Text(
                            text = "还没有直播源",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = "导入 M3U / TXT 文件、粘贴文本或网络订阅后，频道会显示在这里",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.size(16.dp))
                        Button(onClick = onSettings) {
                            Text("导入直播源")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // 稳定的 contentType 让滚出/滚入的同类型 item 复用组合，减少测量与重建
                ) {
                    items(
                        filtered,
                        key = { it.id },
                        contentType = { "channel" },
                    ) { ch ->
                        ChannelRow(
                            ch = ch,
                            onPlay = { onPlay(ch) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(
    ch: Channel,
    onPlay: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                text = ch.title.ifEmpty { ch.name },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = "${ch.group} · ${ch.urls.size} 个源",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            ChannelAvatar(ch = ch)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onPlay),
    )
}

/**
 * 频道台标：**品牌蓝实心方块 + 白色频道简称**，完全不依赖网络 logo。
 *
 * 为什么弃用 tvg-logo：这些台标大多是白/浅灰底的不透明图片，Fit 铺满方块后会把
 * 底色整个盖住，于是「改底色看不出变化」「对比不够、看不清」——截图里那块浅灰
 * 其实一直就是图片自带的底，不是 App 画的底色。改成纯色块 + 文字后：
 *  - 品牌蓝 100% 可见，白页面上的对比拉满；
 *  - 白字简称（CCTV5 / CCTV12 / CGTN / 湖南…）统一、清晰、够大；
 *  - 不再有图片加载 / 解码 / 重组重启请求，长列表（400+ 频道）滑动更顺。
 */
@Composable
private fun ChannelAvatar(ch: Channel) {
    val shape = MaterialTheme.shapes.small
    val label = remember(ch.name, ch.title) { shortLabel(ch) }
    // 简称越长字号越小，保证单行塞进 52dp 方块且尽量大
    val fontSize = when (label.length) {
        in 0..2 -> 20.sp
        in 3..4 -> 15.sp
        in 5..6 -> 12.sp
        else -> 10.sp
    }
    Box(
        modifier = Modifier
            .size(52.dp)
            .background(MaterialTheme.colorScheme.primary, shape)
            .padding(horizontal = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
            ),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

/** 尾部画质后缀 / 空格，提取简称前先剥掉，避免「CCTV5 高清」被算成长字符串。 */
private val QUALITY_SUFFIX =
    Regex("(?i)(高清|超清|蓝光|标清|HD|FHD|UHD|4K|8K|1080p?|720p?|[\\s-])+$")

/** 名称开头的 ASCII 字母数字（含 +），如 CCTV5、CCTV5+、CGTN。 */
private val ASCII_HEAD = Regex("^[A-Za-z0-9+]+")

/** 从频道名提取简短台标：优先 ASCII 主标识，纯中文则取前两字。 */
private fun shortLabel(ch: Channel): String {
    val raw = ch.title.ifEmpty { ch.name }.trim()
    val cleaned = raw.replace(QUALITY_SUFFIX, "").trim()
    val base = cleaned.ifEmpty { raw }
    val ascii = ASCII_HEAD.find(base)?.value
    return when {
        ascii != null && ascii.length >= 2 -> ascii.take(7)
        else -> base.take(2).ifEmpty { "TV" }
    }
}
