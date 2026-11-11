@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.mytvphone.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mytvphone.app.MainViewModel
import com.mytvphone.app.data.Subscription
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置页：只保留「导入 / 切换 / 刷新 / 删除订阅」与一个锁横屏开关。
 * 频道编辑、单个频道添加、配置导出/恢复都已移除，入口收敛到最少。
 *
 * active 标记由响应式的 settings 计算（旧代码直接读 ViewModel 快照，切换订阅后不刷新）。
 */
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val subs by vm.repo.subs.collectAsStateWithLifecycle()

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
                TextButton(onClick = { vm.openImport() }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("导入新源")
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
                    title = "动态取色（跟随壁纸）",
                    desc = "Android 12+ 跟随系统壁纸生成配色；关闭则使用品牌蓝",
                    checked = settings.dynamicColor,
                    onChange = { vm.setDynamicColor(it) },
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
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = containerColor),
        // 整行都是触控目标（M3 要求列表项整行可点）：开关本身不单独响应，由行代理切换
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = { onChange(!checked) }),
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
                TextButton(
                    onClick = {
                        onDelete()
                        confirmDelete = false
                    },
                    // 破坏性操作：确认按钮用 error 色，而不是默认 primary，符合 M3 语义
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}
