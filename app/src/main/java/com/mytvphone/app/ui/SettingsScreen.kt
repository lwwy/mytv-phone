@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.mytvphone.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mytvphone.app.MainViewModel
import com.mytvphone.app.data.Subscription
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置：只保留 2 个真正生效的开关与必要的订阅管理。
 * active 标记改由响应式的 settings 计算（旧代码直接读 ViewModel 快照，切换订阅后不刷新）。
 */
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val subs by vm.repo.subs.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val createDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(vm.exportJson().toByteArray(Charsets.UTF_8))
            } ?: error("无法写入文件")
            vm.showToast("配置已导出")
        }.onFailure { vm.showToast("导出失败：${it.message}") }
    }

    val openBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            val json = context.contentResolver.openInputStream(uri)?.use {
                String(it.readBytes(), Charsets.UTF_8)
            }
            if (json != null) vm.importBackup(json) else vm.showToast("读取失败")
        }.onFailure { vm.showToast("读取失败：${it.message}") }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SectionHeader("直播源") }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.showImportSheet() }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text("  导入新源")
                    }
                }
            }

            if (subs.isEmpty()) {
                item {
                    Text(
                        "还没有订阅，点上方「导入新源」添加。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .animateItem()
                            .padding(vertical = 8.dp),
                    )
                }
            }
            items(subs, key = { it.id }) { sub ->
                SubscriptionCard(
                    sub = sub,
                    active = sub.id == settings.activeSubId ||
                        (settings.activeSubId.isEmpty() && sub.id == subs.firstOrNull()?.id),
                    onSelect = { vm.setActiveSub(sub.id) },
                    onRefresh = { vm.refreshSubscription(sub.id) },
                    onDelete = { vm.deleteSubscription(sub.id) },
                    modifier = Modifier.animateItem(),
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { createDoc.launch("mytv-backup.json") }) {
                        Text("导出备份")
                    }
                    TextButton(onClick = { openBackup.launch(arrayOf("application/json", "*/*")) }) {
                        Text("恢复备份")
                    }
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { SectionHeader("播放") }

            item {
                SwitchRow(
                    title = "播放时锁定横屏",
                    desc = "进入播放页自动转横屏，列表页不受影响",
                    checked = settings.lockLandscape,
                    onChange = { vm.setLockLandscape(it) },
                    modifier = Modifier.animateItem(),
                )
            }
            item {
                SwitchRow(
                    title = "滑动手势",
                    desc = "上下滑换台、左半屏调亮度、右半屏调音量",
                    checked = settings.gesturesEnabled,
                    onChange = { vm.setGestures(it) },
                    modifier = Modifier.animateItem(),
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { SectionHeader("关于") }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("口袋电视", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "自用播放器，不内置、不分发、不验证任何直播源。播放列表由你自己导入。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 开关切换时整行底色与标题色一起平滑过渡，与 Material 3 Switch 自带的滑块
    // 动画同步，避免「开关动了、界面硬切」的割裂感。
    val containerColor by animateColorAsState(
        targetValue = if (checked) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(220),
        label = "SwitchRowContainer",
    )
    val titleColor by animateColorAsState(
        targetValue = if (checked) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(220),
        label = "SwitchRowTitle",
    )

    ListItem(
        headlineContent = {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
        },
        supportingContent = {
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = ListItemDefaults.colors(containerColor = containerColor),
        modifier = modifier.clip(MaterialTheme.shapes.medium),
    )
}

@Composable
private fun SubscriptionCard(
    sub: Subscription,
    active: Boolean,
    onSelect: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val date = remember(sub.importedAt) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(sub.importedAt))
    }
    var confirmDelete by remember { mutableStateOf(false) }
    // 切换当前订阅时，两张卡片的底色同时渐变，不再瞬间互换
    val containerColor by animateColorAsState(
        targetValue = if (active) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = tween(240),
        label = "SubscriptionActive",
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        onClick = onSelect,
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (active) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "当前使用",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(sub.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${sub.channels.size} 个频道 · $date",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!sub.url.isNullOrEmpty()) {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新订阅")
                }
            }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Default.Delete, contentDescription = "删除订阅")
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除订阅") },
            text = { Text("确定删除「${sub.name}」？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    confirmDelete = false
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}
