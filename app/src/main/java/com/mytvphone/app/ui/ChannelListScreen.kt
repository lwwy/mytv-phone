@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mytvphone.app.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
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
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filtered, key = { it.id }) { ch ->
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
 * 频道台标：有 tvg-logo 就加载，否则用频道首字作占位。
 *
 * 优化点（之前看不清的根因）：
 *  - 尺寸从 42dp 放大到 52dp，细线条文字在小尺寸下会糊成一片；
 *  - 加一圈描边，把台标从浅色页面背景里「框」出来，低对比 logo 也能看清边界；
 *  - 用 [SubcomposeAsyncImage]：加载中 / 失败不再留一块空瓷砖，而是回退到首字占位，
 *    并开启 crossfade，避免半截模糊图一闪而过。
 */
@Composable
private fun ChannelAvatar(ch: Channel) {
    val shape = MaterialTheme.shapes.small
    val tileModifier = Modifier
        .size(52.dp)
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)

    if (ch.logo.isNotEmpty()) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(ch.logo)
                .crossfade(true)
                .build(),
            contentDescription = null,
            modifier = tileModifier,
            contentScale = ContentScale.Fit,
            // transform 仅用于从「带 loading/error 槽位的重载」中唯一锁定本重载，
            // 避免与同名重载产生歧义；内容绘制交给下面的 content 按状态切换
            transform = { it },
            content = {
                when (painter.state) {
                    is AsyncImagePainter.State.Loading,
                    is AsyncImagePainter.State.Error,
                    -> FirstCharFallback(ch)

                    else -> SubcomposeAsyncImageContent()
                }
            },
        )
    } else {
        // 无 logo：用彩色占位块（primaryContainer），与有 logo 的中性块区分开
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
            contentAlignment = Alignment.Center,
        ) {
            FirstCharFallback(ch)
        }
    }
}

/** 首字占位：无 logo 或 logo 加载失败时显示，保证每一行左侧都有可辨识的内容。 */
@Composable
private fun FirstCharFallback(ch: Channel) {
    Text(
        text = ch.name.take(1),
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        style = MaterialTheme.typography.titleMedium,
    )
}
