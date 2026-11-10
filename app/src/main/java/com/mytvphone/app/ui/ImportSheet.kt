@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mytvphone.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mytvphone.app.MainViewModel
import com.mytvphone.app.data.ParseResult
import com.mytvphone.app.data.PlaylistParser
import com.mytvphone.app.util.resolveDisplayName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 导入直播源。
 *
 * 四种入口统一走 [PlaylistParser.parse]，解析后先预览再确认入库：
 *  ① 本地文件（SAF content://）② 粘贴文本 ③ 网络订阅 URL ④ 其他 App「分享 / 打开」。
 *
 * 交互采用显式状态机 [ImportStep]：点任一入口会**整页切换**到对应表单，
 * 而不是把输入框追加到长列表末尾——后者在半展开的 BottomSheet 里会落在可视区外，
 * 表现为「点了没反应」。同时 sheet 默认全展开，输入框固定在标题正下方。
 */
private enum class ImportStep { Choose, Paste, Url, Preview }

@Composable
fun ImportSheet(
    vm: MainViewModel,
    initial: Pair<String, String>?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var step by rememberSaveable { mutableStateOf(ImportStep.Choose) }
    var name by rememberSaveable { mutableStateOf("") }
    var urlText by rememberSaveable { mutableStateOf("") }
    // 播放列表文本可能很大，不写进 savedInstanceState，避免 TransactionTooLargeException
    var pastedText by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<ParseResult?>(null) }
    var pendingSource by rememberSaveable { mutableStateOf("") }
    var pendingUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var errorMsg by rememberSaveable { mutableStateOf<String?>(null) }

    // 外部分享只在首次进入时处理一次；不再与「当前页」互相覆盖
    LaunchedEffect(Unit) {
        if (initial != null) {
            name = initial.first.substringBeforeLast('.')
            pastedText = initial.second
            pendingSource = "外部分享 / 打开方式"
            pendingUrl = null
            val r = PlaylistParser.parse(initial.second)
            if (r.isEmpty) {
                errorMsg = "未解析到可用频道（识别格式：${r.format}）"
                step = ImportStep.Paste
            } else {
                preview = r
                step = ImportStep.Preview
            }
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
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        PlaylistParser.decodeAuto(it.readBytes())
                    }
                }.getOrNull()
            }
            busy = false
            if (text.isNullOrBlank()) {
                errorMsg = "读取文件失败或文件为空"
                return@launch
            }
            val display = resolveDisplayName(context, uri) ?: "本地文件"
            name = display.substringBeforeLast('.')
            val r = PlaylistParser.parse(text)
            if (r.isEmpty) {
                errorMsg = "未解析到可用频道（识别格式：${r.format}）"
                return@launch
            }
            pendingSource = "本地文件：$display"
            pendingUrl = null
            preview = r
            step = ImportStep.Preview
        }
    }

    fun backToChoose() {
        step = ImportStep.Choose
        preview = null
        errorMsg = null
        busy = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (step) {
                ImportStep.Choose -> ChoosePage(
                    busy = busy,
                    onFile = {
                        errorMsg = null
                        openDoc.launch(arrayOf("*/*"))
                    },
                    onPaste = { errorMsg = null; step = ImportStep.Paste },
                    onUrl = { errorMsg = null; step = ImportStep.Url },
                )

                ImportStep.Paste -> InputPage(
                    title = "粘贴文本",
                    hint = "把 M3U 内容或「频道名,地址」粘贴到这里",
                    text = pastedText,
                    onTextChange = { pastedText = it },
                    multiline = true,
                    actionLabel = "解析并预览",
                    busy = busy,
                    onBack = ::backToChoose,
                    onAction = {
                        val r = PlaylistParser.parse(pastedText)
                        if (r.isEmpty) {
                            errorMsg = "未解析到可用频道（识别格式：${r.format}）"
                        } else {
                            errorMsg = null
                            pendingSource = "粘贴导入"
                            pendingUrl = null
                            preview = r
                            step = ImportStep.Preview
                        }
                    },
                )

                ImportStep.Url -> InputPage(
                    title = "网络订阅 URL",
                    hint = "https://example.com/list.m3u",
                    text = urlText,
                    onTextChange = { urlText = it },
                    multiline = false,
                    actionLabel = "拉取并预览",
                    busy = busy,
                    onBack = ::backToChoose,
                    onAction = {
                        val url = urlText.trim()
                        if (!url.startsWith("http", ignoreCase = true)) {
                            errorMsg = "请输入以 http/https 开头的地址"
                        } else {
                            scope.launch {
                                busy = true
                                errorMsg = null
                                val result = vm.repo.fetch(url)
                                busy = false
                                result
                                    .onSuccess { bytes ->
                                        val r = PlaylistParser.parse(PlaylistParser.decodeAuto(bytes))
                                        if (r.isEmpty) {
                                            errorMsg = "未解析到可用频道（识别格式：${r.format}）"
                                        } else {
                                            name = name.ifBlank { "网络订阅" }
                                            pendingSource = "网络订阅"
                                            pendingUrl = url
                                            preview = r
                                            step = ImportStep.Preview
                                        }
                                    }
                                    .onFailure { e -> errorMsg = "拉取失败：${e.message}" }
                            }
                        }
                    },
                )

                ImportStep.Preview -> {
                    val r = preview
                    if (r == null) {
                        // 极端情况（进程恢复后预览丢失）：不在这里写状态，只给个返回入口
                        Text("预览已失效，请重新选择导入方式。")
                        FilledTonalButton(onClick = ::backToChoose) { Text("重新选择") }
                    } else {
                        PreviewPage(
                            name = name,
                            onNameChange = { name = it },
                            result = r,
                            onBack = ::backToChoose,
                            onConfirm = {
                                vm.commitImport(name, pendingSource, r, pendingUrl)
                                onDismiss()
                            },
                        )
                    }
                }
            }

            errorMsg?.let { msg ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = msg,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChoosePage(
    busy: Boolean,
    onFile: () -> Unit,
    onPaste: () -> Unit,
    onUrl: () -> Unit,
) {
    Text("导入直播源", style = MaterialTheme.typography.headlineSmall)
    Text(
        "本应用不内置任何频道，播放列表完全由你自己提供。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.size(4.dp))

    if (busy) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(12.dp))
            Text("正在读取…", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    ImportEntry(
        icon = Icons.AutoMirrored.Filled.List,
        title = "选择本地文件",
        desc = "系统文件选择器 · M3U / M3U8 / TXT / JSON，自动识别 UTF-8 / GBK",
        onClick = onFile,
    )
    ImportEntry(
        icon = Icons.Default.Edit,
        title = "粘贴文本",
        desc = "直接粘贴 M3U 内容或「频道名,地址」",
        onClick = onPaste,
    )
    ImportEntry(
        icon = Icons.Default.Link,
        title = "网络订阅 URL",
        desc = "填一个地址，之后可在设置里一键刷新",
        onClick = onUrl,
    )

    Text(
        "也可以在其他 App 里对 .m3u 文件点「分享」或「用其他应用打开」→ 选「口袋电视」。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun ImportEntry(
    icon: ImageVector,
    title: String,
    desc: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(title, style = MaterialTheme.typography.titleMedium)
        },
        supportingContent = {
            Text(desc, style = MaterialTheme.typography.bodySmall)
        },
        leadingContent = {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clickable(onClick = onClick),
    )
}

/** 输入页：返回箭头 + 标题在最上，输入框紧随其后，保证一定在可视区内。 */
@Composable
private fun InputPage(
    title: String,
    hint: String,
    text: String,
    onTextChange: (String) -> Unit,
    multiline: Boolean,
    actionLabel: String,
    busy: Boolean,
    onBack: () -> Unit,
    onAction: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }

    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        label = { Text(if (multiline) "播放列表内容" else "订阅地址") },
        placeholder = { Text(hint) },
        singleLine = !multiline,
        minLines = if (multiline) 8 else 1,
        maxLines = if (multiline) 14 else 1,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )

    Button(
        onClick = onAction,
        enabled = !busy && text.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("处理中…")
        } else {
            Text(actionLabel)
        }
    }

    Text(
        "支持 M3U / M3U8、TXT(#genre#)、JSON、卦象编码；自动识别 UA / Referer 请求头与 UTF-8 / GBK。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PreviewPage(
    name: String,
    onNameChange: (String) -> Unit,
    result: ParseResult,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Text("确认导入", style = MaterialTheme.typography.titleLarge)
    }

    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text("订阅名称") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Text(
        "${result.format} · ${result.channels.size} 个频道 · ${result.groups.size} 个分组" +
            (if (result.skipped > 0) " · 跳过 ${result.skipped} 行" else ""),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    result.epgUrl?.let {
        Text(
            "检测到 EPG：$it",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        result.channels.take(5).forEach { ch ->
            ListItem(
                headlineContent = { Text(ch.name, style = MaterialTheme.typography.bodyMedium) },
                supportingContent = {
                    Text(
                        "${ch.group} · ${ch.urls.size} 个源",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                colors = ListItemDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            )
        }
        if (result.channels.size > 5) {
            Text(
                "… 共 ${result.channels.size} 个频道",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            )
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onConfirm, modifier = Modifier.weight(1f)) {
            Text("确认导入 ${result.channels.size} 个频道")
        }
        FilledTonalButton(onClick = onBack) { Text("重选") }
    }
}
