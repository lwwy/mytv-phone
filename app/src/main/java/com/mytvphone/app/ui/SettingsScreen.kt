@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mytvphone.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mytvphone.app.MainViewModel
import com.mytvphone.app.data.Subscription
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val subs by vm.repo.subs.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var renameId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }

    val createDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(vm.exportJson().toByteArray(Charsets.UTF_8))
            }
            vm.showToast("配置已导出")
        } catch (e: Exception) {
            vm.showToast("导出失败：${e.message}")
        }
    }

    val openBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            val json = context.contentResolver.openInputStream(uri)?.use {
                String(it.readBytes(), Charsets.UTF_8)
            }
            if (json != null) vm.importBackup(json) else vm.showToast("读取失败")
        } catch (e: Exception) {
            vm.showToast("读取失败：${e.message}")
        }
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
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { SectionTitle("播放与手势") }
            item {
                SwitchRow(
                    title = "播放时锁定横屏",
                    desc = "进入播放页自动转到横屏，竖屏列表不受影响",
                    checked = settings.lockLandscape,
                ) { vm.setLockLandscape(it) }
            }
            item {
                SwitchRow(
                    title = "滑动手势",
                    desc = "上下滑换台、左半屏调亮度、右半屏调音量",
                    checked = settings.gesturesEnabled,
                ) { vm.setGestures(it) }
            }
            item {
                SwitchRow(
                    title = "启动时恢复上次频道",
                    desc = "记录最后一次播放的频道",
                    checked = settings.autoResume,
                ) { vm.setAutoResume(it) }
            }
            item {
                SwitchRow(
                    title = "显示台标",
                    desc = "列表里加载 tvg-logo，关闭可省流量",
                    checked = settings.showLogo,
                ) { vm.setShowLogo(it) }
            }

            item { SectionTitle("订阅管理（${subs.size}）") }
            if (subs.isEmpty()) {
                item {
                    Text(
                        "还没有订阅，回到列表点右下角 + 导入。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(subs, key = { it.id }) { sub ->
                SubscriptionRow(
                    sub = sub,
                    active = sub.id == vm.activeSubId(),
                    onSelect = { vm.setActiveSub(sub.id) },
                    onRefresh = { vm.refreshSubscription(sub.id) },
                    onRename = {
                        renameId = sub.id
                        renameText = sub.name
                    },
                    onDelete = { vm.deleteSubscription(sub.id) },
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { createDoc.launch("mytv-backup.json") }) {
                        Text("导出配置备份")
                    }
                    OutlinedButton(onClick = { openBackup.launch(arrayOf("application/json", "*/*")) }) {
                        Text("从备份恢复")
                    }
                }
            }

            item { SectionTitle("关于") }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("口袋电视 v1.0.0", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "自用播放器，不内置、不分发、不验证任何直播源。播放列表由你自己导入，" +
                                "频道版权归各自电视台 / 内容提供方所有。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "播放内核：AndroidX Media3 (ExoPlayer) 1.4.0 · 设计参考 my-tv-0 的 TVModel / str2List 实现。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "支持格式：M3U / M3U8、TXT(#genre#)、JSON、卦象编码；支持 UA / Referer 请求头与 UTF-8 / GBK。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (renameId != null) {
        AlertDialog(
            onDismissRequest = { renameId = null },
            title = { Text("重命名订阅") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(onClick = {
                    renameId?.let { vm.renameSubscription(it, renameText.trim()) }
                    renameId = null
                }) { Text("保存") }
            },
            dismissButton = {
                OutlinedButton(onClick = { renameId = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 18.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SubscriptionRow(
    sub: Subscription,
    active: Boolean,
    onSelect: () -> Unit,
    onRefresh: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val date = remember(sub.importedAt) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(sub.importedAt))
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onSelect,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (active) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "当前使用",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(sub.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${sub.channels.size} 个频道 · $date",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    sub.source + if (sub.url.isNullOrEmpty()) "" else " · 可刷新",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (!sub.url.isNullOrEmpty()) {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新订阅")
                }
            }
            IconButton(onClick = onRename) {
                Icon(Icons.Default.Edit, contentDescription = "重命名")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除订阅")
            }
        }
    }
}
