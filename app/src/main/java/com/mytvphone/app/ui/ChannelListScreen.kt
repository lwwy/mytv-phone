@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mytvphone.app.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
    val settings by vm.settings.collectAsStateWithLifecycle()
    val subs by vm.repo.subs.collectAsStateWithLifecycle()
    val showImport by vm.showImport.collectAsStateWithLifecycle()
    val external by vm.externalText.collectAsStateWithLifecycle()

    val activity = LocalContext.current as? Activity

    var query by rememberSaveable { mutableStateOf("") }
    var favOnly by rememberSaveable { mutableStateOf(false) }
    var selectedGroup by rememberSaveable { mutableStateOf(ALL_GROUP) }
    var lastBack by remember { mutableStateOf(0L) }

    val groups = remember(channels) {
        listOf(ALL_GROUP) + channels.map { it.group }.distinct()
    }
    val filtered = remember(channels, query, favOnly, selectedGroup, settings.favorites) {
        channels.filter { ch ->
            (selectedGroup == ALL_GROUP || ch.group == selectedGroup) &&
                (!favOnly || ch.id in settings.favorites) &&
                (query.isBlank() || ch.name.contains(query, true) || ch.title.contains(query, true))
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
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.showImportSheet() }) {
                Icon(Icons.Default.Add, contentDescription = "导入直播源")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                placeholder = { Text("搜索频道名") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "清空")
                        }
                    }
                },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilterChip(
                    selected = favOnly,
                    onClick = { favOnly = !favOnly },
                    label = { Text("只看收藏") },
                    leadingIcon = {
                        Icon(
                            if (favOnly) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
                Text(
                    text = "${subs.size} 份订阅 · ${channels.size} 个频道",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

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
                            text = if (channels.isEmpty()) "还没有直播源" else "没有匹配的频道",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = if (channels.isEmpty()) {
                                "点右下角 + 导入 M3U / TXT 文件、粘贴文本或网络订阅"
                            } else {
                                "换个关键词或分组试试"
                            },
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
                            fav = ch.id in settings.favorites,
                            onPlay = { onPlay(ch) },
                            onFav = { vm.toggleFavorite(ch) },
                        )
                    }
                }
            }
        }
    }

    if (showImport) {
        ImportSheet(
            vm = vm,
            initial = external,
            onDismiss = { vm.hideImportSheet() },
        )
    }
}

@Composable
private fun ChannelRow(
    ch: Channel,
    fav: Boolean,
    onPlay: () -> Unit,
    onFav: () -> Unit,
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
        trailingContent = {
            IconButton(onClick = onFav) {
                Icon(
                    imageVector = if (fav) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = if (fav) "取消收藏" else "收藏",
                    tint = if (fav) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
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
