@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mytvphone.app.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mytvphone.app.MainViewModel
import com.mytvphone.app.data.ParseResult
import com.mytvphone.app.data.PlaylistParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 四种导入入口：
 *  ① 本地文件（SAF / content://，修掉参考项目 file:// 在 Android 10+ 读不到的问题）
 *  ② 粘贴文本
 *  ③ 网络订阅 URL（失败保留上次结果）
 *  ④ 从其他 App「分享 / 用其他应用打开」进入，自动填充并解析
 * 所有入口统一走 PlaylistParser.parse()，解析后先预览再确认入库。
 */
@Composable
fun ImportSheet(
    vm: MainViewModel,
    initial: Pair<String, String>?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by rememberSaveable { mutableStateOf("") }
    var urlText by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf("menu") }
    // 播放列表文本可能很大，不放进 savedInstanceState（避免 TransactionTooLargeException）
    var pastedText by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<ParseResult?>(null) }
    var pendingSource by remember { mutableStateOf("") }
    var pendingUrl by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initial) {
        if (initial != null) {
            name = initial.first.substringBeforeLast('.')
            pastedText = initial.second
            mode = "paste"
            pendingSource = "外部分享 / 打开方式"
            pendingUrl = null
            val r = PlaylistParser.parse(initial.second)
            if (r.isEmpty) errorMsg = "未解析到可用频道（识别格式：${r.format}）" else preview = r
            vm.consumeExternal()
        }
    }

    val openDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            errorMsg = null
            val text = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(uri)?.use {
                        PlaylistParser.decodeAuto(it.readBytes())
                    }
                } catch (e: Exception) {
                    null
                }
            }
            busy = false
            if (text.isNullOrBlank()) {
                errorMsg = "读取文件失败或文件为空"
                return@launch
            }
            val display = displayName(context, uri) ?: "本地文件"
            name = display.substringBeforeLast('.')
            val r = PlaylistParser.parse(text)
            if (r.isEmpty) {
                errorMsg = "未解析到可用频道（识别格式：${r.format}）"
            } else {
                pendingSource = "本地文件：$display"
                pendingUrl = null
                preview = r
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("导入直播源", style = MaterialTheme.typography.titleLarge)
            Text(
                "本应用不内置任何频道，播放列表完全由你自己提供。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val r = preview
            when {
                busy -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp))
                        Spacer(Modifier.size(12.dp))
                        Text("正在读取 / 解析…")
                    }
                }

                r != null -> {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("订阅名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "格式：${r.format} · 频道 ${r.channels.size} 个 · 分组 ${r.groups.size} 个" +
                            (if (r.skipped > 0) " · 跳过无效行 ${r.skipped}" else ""),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (r.epgUrl != null) {
                        Text(
                            "检测到 EPG 地址：${r.epgUrl}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column {
                        r.channels.take(5).forEach { ch ->
                            Text(
                                "• ${ch.name}  [${ch.group}]  ${ch.urls.size} 源",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (r.channels.size > 5) {
                            Text("… 共 ${r.channels.size} 个频道", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = {
                            vm.commitImport(name, pendingSource, r, pendingUrl)
                            onDismiss()
                        }) {
                            Text("确认导入 ${r.channels.size} 个频道")
                        }
                        OutlinedButton(onClick = {
                            preview = null
                            pastedText = ""
                            mode = "menu"
                        }) {
                            Text("重新选择")
                        }
                    }
                }

                else -> {
                    EntryRow(
                        icon = Icons.AutoMirrored.Filled.List,
                        title = "选择本地文件",
                        desc = "系统文件选择器，支持 .m3u / .m3u8 / .txt，自动识别 UTF-8 / GBK",
                    ) {
                        errorMsg = null
                        openDoc.launch(arrayOf("*/*"))
                    }
                    EntryRow(
                        icon = Icons.Default.Edit,
                        title = "粘贴文本",
                        desc = "直接粘贴 M3U 或「频道名,地址」内容",
                    ) {
                        errorMsg = null
                        mode = "paste"
                    }
                    EntryRow(
                        icon = Icons.Default.Link,
                        title = "网络订阅 URL",
                        desc = "填一个地址，之后可在设置里一键刷新",
                    ) {
                        errorMsg = null
                        mode = "url"
                    }

                    Text(
                        "也可以在其他 App 里对 .m3u 文件点「分享」或「用其他应用打开」→ 选「口袋电视」，会自动进入这里。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (mode == "paste") {
                        OutlinedTextField(
                            value = pastedText,
                            onValueChange = { pastedText = it },
                            label = { Text("粘贴播放列表内容") },
                            minLines = 5,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(onClick = {
                            val res = PlaylistParser.parse(pastedText)
                            if (res.isEmpty) {
                                errorMsg = "未解析到可用频道（识别格式：${res.format}）"
                            } else {
                                errorMsg = null
                                pendingSource = "粘贴导入"
                                pendingUrl = null
                                preview = res
                            }
                        }) {
                            Text("解析")
                        }
                    }

                    if (mode == "url") {
                        OutlinedTextField(
                            value = urlText,
                            onValueChange = { urlText = it },
                            label = { Text("https://…/list.m3u") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(onClick = {
                            val url = urlText.trim()
                            if (!url.startsWith("http", true)) {
                                errorMsg = "请输入以 http/https 开头的地址"
                                return@Button
                            }
                            scope.launch {
                                busy = true
                                errorMsg = null
                                val result = vm.repo.fetch(url)
                                busy = false
                                result
                                    .onSuccess { bytes ->
                                        val res = PlaylistParser.parse(PlaylistParser.decodeAuto(bytes))
                                        if (res.isEmpty) {
                                            errorMsg = "未解析到可用频道（识别格式：${res.format}）"
                                        } else {
                                            name = name.ifBlank { "网络订阅" }
                                            pendingSource = "网络订阅"
                                            pendingUrl = url
                                            preview = res
                                        }
                                    }
                                    .onFailure { e -> errorMsg = "拉取失败：${e.message}" }
                            }
                        }) {
                            Text("拉取并解析")
                        }
                    }
                }
            }

            errorMsg?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.size(12.dp))
        }
    }
}

@Composable
private fun EntryRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    desc: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun displayName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) c.getString(i) else null
    }
} catch (e: Exception) {
    null
}
