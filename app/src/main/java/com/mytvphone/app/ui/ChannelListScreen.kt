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
import coil.compose.AsyncImage
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
                text = "${channels.size} 个频道",
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
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "还没有直播源",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = "在右上角「设置」里导入 M3U / TXT 文件、粘贴文本或网络订阅",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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

/** 有 tvg-logo 就加载，否则用频道首字作占位，不再需要「显示台标」开关。 */
@Composable
private fun ChannelAvatar(ch: Channel) {
    if (ch.logo.isNotEmpty()) {
        AsyncImage(
            model = ch.logo,
            contentDescription = null,
            modifier = Modifier
                .size(42.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentScale = ContentScale.Fit,
        )
    } else {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = ch.name.take(1),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
