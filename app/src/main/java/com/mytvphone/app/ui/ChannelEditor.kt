@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mytvphone.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mytvphone.app.MainViewModel
import com.mytvphone.app.data.Channel

/**
 * 频道编辑对话框：名称 / 分组 / 地址。
 *
 * 地址按「每行一个」录入，第一行为主源，其余作为备用源（播放失败时依次重试），
 * 与 M3U 里同名频道的多地址合并规则保持一致。
 */
@Composable
fun ChannelEditorDialog(
    title: String,
    initialName: String,
    initialGroup: String,
    initialUrls: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (name: String, group: String, urls: List<String>) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    // 用 initial* 作为 key：同一个对话框被复用到另一个频道时（不经过关闭再打开）
    // 输入框会跟着重置，不会把上一个频道的名称/地址带过去。
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    var group by rememberSaveable(initialGroup) { mutableStateOf(initialGroup) }
    var urlText by rememberSaveable(initialUrls) { mutableStateOf(initialUrls.joinToString("\n")) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val n = name.trim()
        val urls = urlText.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        when {
            n.isEmpty() -> error = "频道名称不能为空"
            urls.isEmpty() -> error = "请至少填写一个播放地址"
            else -> onConfirm(n, group.trim(), urls)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("频道名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = group,
                    onValueChange = { group = it },
                    label = { Text("分组") },
                    placeholder = { Text("留空则为「未分组」") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it; error = null },
                    label = { Text("播放地址") },
                    placeholder = { Text("每行一个地址，第一行为主源") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/**
 * 频道管理页：搜索 + 列表 + 编辑。入口在设置页每张订阅卡片右侧的铅笔按钮。
 * 直接读 repo.subs，编辑后无需刷新，列表实时更新。
 */
@Composable
fun ChannelEditScreen(vm: MainViewModel, subId: String, onBack: () -> Unit) {
    val subs by vm.repo.subs.collectAsStateWithLifecycle()
    val sub = subs.firstOrNull { it.id == subId }

    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Channel?>(null) }
    var adding by remember { mutableStateOf(false) }

    val all = sub?.channels ?: emptyList()
    val filtered = remember(all, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            all
        } else {
            all.filter {
                it.name.contains(q, ignoreCase = true) ||
                    it.title.contains(q, ignoreCase = true) ||
                    it.group.contains(q, ignoreCase = true) ||
                    it.urls.any { u -> u.contains(q, ignoreCase = true) }
            }
        }
    }

    // 订阅被删除（例如在另一处删除）时给出兜底，避免空白页
    if (sub == null) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("订阅不存在或已删除", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onBack) { Text("返回") }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("编辑频道") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { adding = true }) {
                        Icon(Icons.Default.Add, contentDescription = "添加频道")
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
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text("搜索") },
                placeholder = { Text("频道名 / 分组 / 地址") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            Text(
                text = "${sub.name} · ${sub.channels.size} 个频道",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (sub.channels.isEmpty()) {
                            "还没有频道，点右上角 + 添加"
                        } else {
                            "没有匹配的频道"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filtered, key = { it.id }) { ch ->
                        ChannelEditRow(ch = ch, onClick = { editing = ch })
                    }
                }
            }
        }
    }

    if (adding) {
        ChannelEditorDialog(
            title = "添加频道",
            initialName = "",
            initialGroup = "",
            initialUrls = emptyList(),
            onDismiss = { adding = false },
            onConfirm = { name, group, urls ->
                vm.addChannel(name, group, urls, sub.id)
                adding = false
            },
        )
    }

    editing?.let { ch ->
        ChannelEditorDialog(
            title = "编辑频道",
            initialName = ch.name,
            initialGroup = ch.group,
            initialUrls = ch.urls,
            onDismiss = { editing = null },
            onConfirm = { name, group, urls ->
                vm.updateChannel(sub.id, ch, name, group, urls)
                editing = null
            },
            onDelete = {
                vm.deleteChannel(sub.id, ch.id)
                editing = null
            },
        )
    }
}

@Composable
private fun ChannelEditRow(ch: Channel, onClick: () -> Unit) {
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
                text = "${ch.group} · ${ch.urls.firstOrNull().orEmpty()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .padding(end = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = ch.name.take(1),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        trailingContent = {
            Icon(
                Icons.Default.Edit,
                contentDescription = "编辑",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clickable(onClick = onClick),
    )
}
