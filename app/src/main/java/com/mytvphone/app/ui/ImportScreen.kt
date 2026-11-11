@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mytvphone.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mytvphone.app.MainViewModel
import com.mytvphone.app.data.Channel
import com.mytvphone.app.data.ChannelProbe
import com.mytvphone.app.data.ParseResult
import com.mytvphone.app.data.PlaylistParser
import com.mytvphone.app.util.resolveDisplayName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 导入直播源的**全屏页面**。
 *
 * 早先是 ModalBottomSheet：sheet 自带嵌套滚动、遮罩联动、imePadding 与自身位移叠加，
 * 收起时同一帧要重排两遍，真机上一直掉帧。改成整页后只剩一层
 * `Surface + AnimatedVisibility`（一次垂直位移 + 淡入淡出），
 * 布局在动画期间完全不变，也就没有可掉帧的地方了。
 *
 * 四种入口统一走 [PlaylistParser.parse]，解析后先预览再确认入库：
 *  ① 本地文件（SAF content://）② 粘贴文本 ③ 网络订阅 URL ④ 其他 App「分享 / 打开」。
 *
 * 交互采用显式状态机 [ImportStep]：点任一入口整页切换到对应表单，而不是把输入框
 * 追加到长列表末尾——后者会让输入框落在可视区外，表现为「点了没反应」。
 */
private enum class ImportStep { Choose, Paste, Url, Preview }

/** 进入/退出时长：与 AnimatedVisibility 的 spec 保持一致，close() 据此等待动画播完。 */
private const val ENTER_MS = 260
private const val EXIT_MS = 200

@Composable
fun ImportScreen(
    vm: MainViewModel,
    initial: Pair<String, String>?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    var step by rememberSaveable { mutableStateOf(ImportStep.Choose) }
    var name by rememberSaveable { mutableStateOf("") }
    var urlText by rememberSaveable { mutableStateOf("") }
    // 播放列表文本可能很大，不写进 savedInstanceState，避免 TransactionTooLargeException
    var pastedText by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<ParseResult?>(null) }
    var pendingSource by rememberSaveable { mutableStateOf("") }
    var pendingUrl by rememberSaveable { mutableStateOf<String?>(null) }
    // 普通 remember：不跨进程持久化。否则解析途中进程被回收、恢复后会残留 busy=true，
    // 页面永远卡在「处理中…」且按钮禁用，无法继续操作
    var busy by remember { mutableStateOf(false) }
    var errorMsg by rememberSaveable { mutableStateOf<String?>(null) }

    // 页面显隐：进组合后立刻置 true 触发入场动画；关闭时先置 false，等退出动画播完再移除组合
    var visible by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    /**
     * 关闭页面，顺序很关键：
     *  1) 先播完退出动画，**期间不碰键盘**——若先收键盘，imePadding 归零会让内容
     *     在滑走前先"长高"一下，看起来就是抖一下；
     *  2) 动画结束后再 clearFocus 收键盘（此时页面已移出屏幕，键盘收起看不见）；
     *  3) 最后才写库并移除组合，避免订阅/设置/频道三个流在动画第一帧同时刷新。
     *
     * [afterHide] 在动画结束后、移除组合之前执行。
     */
    fun close(afterHide: (() -> Unit)? = null) {
        if (closing) return
        closing = true
        visible = false
        scope.launch {
            delay(EXIT_MS.toLong())
            focus.clearFocus()
            afterHide?.invoke()
            onDismiss()
        }
    }

    // 外部分享只在首次进入时处理一次；不再与「当前页」互相覆盖。
    // 解析是纯 CPU 操作，放进 IO 协程，避免大列表卡主线程。
    LaunchedEffect(Unit) {
        if (initial != null) {
            name = initial.first.substringBeforeLast('.')
            pastedText = initial.second
            pendingSource = "外部分享 / 打开方式"
            pendingUrl = null
            val r = withContext(Dispatchers.IO) { PlaylistParser.parse(initial.second) }
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
            val result = withContext(Dispatchers.IO) {
                val text = runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        PlaylistParser.decodeAuto(PlaylistParser.readBounded(it))
                    }
                }.getOrNull()
                if (text.isNullOrBlank()) null else PlaylistParser.parse(text)
            }
            busy = false
            if (result == null) {
                errorMsg = "读取文件失败或文件为空"
                return@launch
            }
            if (result.isEmpty) {
                errorMsg = "未解析到可用频道（识别格式：${result.format}）"
                return@launch
            }
            val display = resolveDisplayName(context, uri) ?: "本地文件"
            name = display.substringBeforeLast('.')
            pendingSource = "本地文件：$display"
            pendingUrl = null
            preview = result
            step = ImportStep.Preview
        }
    }

    fun backToChoose() {
        step = ImportStep.Choose
        preview = null
        errorMsg = null
        busy = false
    }

    // 返回键/返回手势：非首页先退回选择页，首页则关闭整个导入页
    BackHandler {
        if (step == ImportStep.Choose) close() else backToChoose()
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            animationSpec = tween(ENTER_MS, easing = FastOutSlowInEasing),
            initialOffsetY = { it },
        ) + fadeIn(tween(180)),
        exit = slideOutVertically(
            animationSpec = tween(EXIT_MS, easing = FastOutLinearInEasing),
            targetOffsetY = { it },
        ) + fadeOut(tween(EXIT_MS)),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                when (step) {
                                    ImportStep.Choose -> "导入直播源"
                                    ImportStep.Paste -> "粘贴文本"
                                    ImportStep.Url -> "网络订阅"
                                    ImportStep.Preview -> "确认导入"
                                },
                            )
                        },
                        navigationIcon = {
                            if (step != ImportStep.Choose) {
                                IconButton(onClick = ::backToChoose) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "返回",
                                    )
                                }
                            }
                        },
                        actions = {
                            IconButton(onClick = { close() }) {
                                Icon(Icons.Default.Close, contentDescription = "关闭")
                            }
                        },
                    )
                },
            ) { innerPadding ->
                // Box 叠层：内容（Crossfade）铺满，错误提示浮在底部，避免再用 weight
                // 在 modifier 作用域外取值导致解析到布局内部属性而编译失败
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        // imePadding 放在滚动层外层：键盘弹出时收缩可视区，
                        // 输入框可以滚到键盘上方，而不是被压在键盘下
                        .imePadding()
                        .padding(horizontal = 20.dp),
                ) {
                    // 分步表单用带方向的转场（前进向右滑入、返回向左滑回），让用户感知
                    // 「自己在流程的哪一步」，不再是无方向的交叉淡化（P1 #17）。
                    // 只做横向位移、不做高度插值，避免每帧重测整块内容掉帧。
                    AnimatedContent(
                        targetState = step,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            val forward = targetState.ordinal >= initialState.ordinal
                            val enter = slideInHorizontally(tween(220)) { w ->
                                if (forward) w / 3 else -w / 3
                            } + fadeIn(tween(180))
                            val exit = slideOutHorizontally(tween(180)) { w ->
                                if (forward) -w / 3 else w / 3
                            } + fadeOut(tween(180))
                            enter.togetherWith(exit)
                        },
                        label = "ImportStep",
                    ) { target ->
                        when (target) {
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
                                hint = "把 M3U 内容或「频道名,地址」粘贴到这里",
                                text = pastedText,
                                onTextChange = { pastedText = it },
                                multiline = true,
                                actionLabel = "解析并预览",
                                busy = busy,
                                error = errorMsg,
                                onAction = {
                                    val input = pastedText
                                    // 解析可能处理数 MB 文本，放进 IO 协程，避免阻塞主线程
                                    scope.launch {
                                        busy = true
                                        errorMsg = null
                                        val r = withContext(Dispatchers.IO) {
                                            PlaylistParser.parse(input)
                                        }
                                        busy = false
                                        if (r.isEmpty) {
                                            errorMsg = "未解析到可用频道（识别格式：${r.format}）"
                                        } else {
                                            pendingSource = "粘贴导入"
                                            pendingUrl = null
                                            preview = r
                                            step = ImportStep.Preview
                                        }
                                    }
                                },
                            )

                            ImportStep.Url -> InputPage(
                                hint = "https://example.com/list.m3u",
                                text = urlText,
                                onTextChange = { urlText = it },
                                multiline = false,
                                actionLabel = "拉取并预览",
                                busy = busy,
                                error = errorMsg,
                                onAction = {
                                    val url = urlText.trim()
                                    if (!url.startsWith("http", ignoreCase = true)) {
                                        errorMsg = "请输入以 http/https 开头的地址"
                                    } else {
                                        scope.launch {
                                            busy = true
                                            errorMsg = null
                                            val fetched = vm.repo.fetch(url)
                                            val bytes = fetched.getOrNull()
                                            // 解码 + 解析是纯 CPU 操作，可能处理数 MB 文本，不能留在主线程
                                            val parsed: Result<ParseResult> = if (bytes == null) {
                                                Result.failure(
                                                    fetched.exceptionOrNull()
                                                        ?: IllegalStateException("拉取失败"),
                                                )
                                            } else {
                                                runCatching {
                                                    withContext(Dispatchers.IO) {
                                                        PlaylistParser.parse(
                                                            PlaylistParser.decodeAuto(bytes),
                                                        )
                                                    }
                                                }
                                            }
                                            busy = false
                                            parsed
                                                .onSuccess { r ->
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
                                                .onFailure { e ->
                                                    errorMsg = "拉取失败：${e.message}"
                                                }
                                        }
                                    }
                                },
                            )

                            ImportStep.Preview -> {
                                val r = preview
                                if (r == null) {
                                    // 极端情况（进程恢复后预览丢失）：只给返回入口，不在这里写状态
                                    Text("预览已失效，请重新选择导入方式。")
                                    FilledTonalButton(onClick = ::backToChoose) {
                                        Text("重新选择")
                                    }
                                } else {
                                    PreviewPage(
                                        name = name,
                                        onNameChange = { name = it },
                                        result = r,
                                        onConfirm = { kept ->
                                            // 先让页面滑走，再写库：避免订阅/设置/频道三个流
                                            // 在动画第一帧同时刷新
                                            close {
                                                vm.commitImport(name, pendingSource, kept, pendingUrl)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }

                    // 错误提示：选择页（文件读取失败等）用底部浮层；输入页的错误已贴合到
                    // 对应输入框下方（supportingText），这里不再重复展示
                    if (errorMsg != null && step == ImportStep.Choose) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 12.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                text = errorMsg.orEmpty(),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
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
    // AnimatedContent 的内容作用域是 BoxScope，兄弟节点会默认叠在左上角同一点；
    // 必须显式包一层 Column 才能纵向排列（InputPage/PreviewPage 已有，这里原来漏了）。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "本应用不内置任何频道，播放列表完全由你自己提供。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(12.dp))
                Text("正在读取…", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
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
        }

        Text(
            "也可以在其他 App 里对 .m3u 文件点「分享」或「用其他应用打开」→ 选「口袋电视」。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
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
            .clickable(onClick = onClick),
    )
}

/** 输入页：输入框紧跟标题栏，保证一定在可视区内。 */
@Composable
private fun InputPage(
    hint: String,
    text: String,
    onTextChange: (String) -> Unit,
    multiline: Boolean,
    actionLabel: String,
    busy: Boolean,
    error: String? = null,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            label = { Text(if (multiline) "播放列表内容" else "订阅地址") },
            placeholder = { Text(hint) },
            singleLine = !multiline,
            minLines = if (multiline) 10 else 1,
            maxLines = if (multiline) 16 else 1,
            enabled = !busy,
            isError = error != null,
            supportingText = if (error != null) {
                { Text(error) }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = onAction,
            enabled = !busy && text.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
        if (busy) {
            // 按钮容器是 primary，指示器默认也是 primary 会隐形；
            // 改用 LocalContentColor（即按钮的 onPrimary）才看得清（P0 #3）
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = LocalContentColor.current,
                strokeWidth = 2.dp,
            )
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
}

/**
 * 导入前的频道预览与**批量剔除**。
 *
 * - 默认全部保留（[kept] 初始为全部键）；点掉某个频道即标记为不导入。
 * - 顶部搜索框按「频道名 / 分组」实时过滤；分组标题带勾选框，可整组一键保留/剔除。
 * - 「全部剔除 / 全部保留 / 反选」做跨分组批量操作。
 * - 可一键**检测所有频道首地址的连通性与延迟**：可达显示毫秒数、不可达标红、未知协议
 *   （rtsp/rtmp 等）标灰；探测完可「只看不可达」或一键「剔除不可达」，不用逐个打开试看。
 * - 勾选状态用 [keyOf] 作稳定键（tvg-id 常为空，用「名称 + 首个地址」避免滚动/搜索重排后错位）。
 *   整页是内存态，关闭预览即丢弃，**不会**误改已保存的订阅。
 */
@Composable
private fun PreviewPage(
    name: String,
    onNameChange: (String) -> Unit,
    result: ParseResult,
    onConfirm: (List<Channel>) -> Unit,
) {
    // (频道, 稳定键) 列表；键与 result 绑定，result 不变则不会重算
    val rows = remember(result) {
        result.channels.map { ch ->
            ch to keyOf(ch)
        }
    }
    val allKeys = remember(rows) { rows.map { it.second } }

    var query by rememberSaveable { mutableStateOf("") }
    // 默认全保留：初始为全部键。关掉某个 = 从 kept 移除
    var kept by remember { mutableStateOf(allKeys.toSet()) }
    // 只看不可达：仅展示探测后判定为不可达的频道
    var onlyUnreachable by remember { mutableStateOf(false) }

    // 连通性探测结果：键 → 状态。点「检测连通性」后实时填充，默认全空
    val results = remember { mutableStateMapOf<String, ChannelProbe.Result>() }
    var probing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 搜索只影响展示，不改 kept 语义（keep 始终基于全量键）；
    // 勾选「只看不可达」时再叠加过滤（仅保留探测结果为 FAIL 的频道）
    val filtered = remember(rows, query, onlyUnreachable, results) {
        val base = if (query.isBlank()) {
            rows
        } else {
            rows.filter { (ch, _) ->
                ch.name.contains(query, ignoreCase = true) ||
                    ch.group.contains(query, ignoreCase = true)
            }
        }
        if (onlyUnreachable) {
            base.filter { (_, key) -> results[key]?.status == ChannelProbe.Status.FAIL }
        } else {
            base
        }
    }
    val grouped = remember(filtered) { filtered.groupBy({ it.first.group }, { it }) }

    val keptChannels = remember(rows, kept) {
        rows.filter { it.second in kept }.map { it.first }
    }
    val excludedCount = allKeys.size - kept.size
    val unreachableCount = results.values.count { it.status == ChannelProbe.Status.FAIL }

    /** 并发探测所有频道首地址的可达性与延迟；结果写入 [results]，协程随预览页离开而取消。 */
    fun runProbe() {
        if (probing) return
        probing = true
        results.clear()
        scope.launch {
            try {
                ChannelProbe.probeAll(
                    rows.map { (ch, key) -> key to ch.urls.firstOrNull().orEmpty() },
                ) { key, res -> results[key] = res }
            } finally {
                probing = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("订阅名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("搜索频道 / 分组") },
            singleLine = true,
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null)
            },
            modifier = Modifier.fillMaxWidth(),
        )

        // 跨分组批量操作：剔除/保留/反选
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = { kept = emptySet() },
                modifier = Modifier.weight(1f),
            ) { Text("全部剔除") }
            FilledTonalButton(
                onClick = { kept = allKeys.toSet() },
                modifier = Modifier.weight(1f),
            ) { Text("全部保留") }
            FilledTonalButton(
                onClick = { kept = allKeys.minus(kept).toSet() },
                modifier = Modifier.weight(1f),
            ) { Text("反选") }
        }

        // 连通性探测工具条：点「检测连通性」对每个频道首地址探活并测延迟；
        // 探测完可「只看不可达」或一键「剔除不可达」，不用逐个打开试看
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = ::runProbe,
                enabled = !probing,
            ) {
                Text(
                    if (probing) "检测中 ${results.size}/${rows.size}" else "检测连通性",
                )
            }
            if (results.isNotEmpty()) {
                FilledTonalButton(
                    onClick = { onlyUnreachable = !onlyUnreachable },
                ) {
                    Text(if (onlyUnreachable) "显示全部" else "只看不可达 ($unreachableCount)")
                }
            }
            Spacer(Modifier.weight(1f))
            if (unreachableCount > 0) {
                FilledTonalButton(
                    onClick = {
                        val bad = results.filterValues { it.status == ChannelProbe.Status.FAIL }.keys
                        kept = kept - bad
                    },
                ) { Text("剔除不可达") }
            }
        }

        Text(
            "将导入 ${kept.size} 个频道" +
                (if (excludedCount > 0) " · 已排除 $excludedCount 个" else "") +
                (if (result.skipped > 0) " · 跳过 ${result.skipped} 行" else ""),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            grouped.forEach { (group, list) ->
                val groupKeys = list.map { it.second }
                val keptInGroup = groupKeys.count { it in kept }
                val allKept = keptInGroup == groupKeys.size
                val noneKept = keptInGroup == 0

                // 分组标题：整组一键保留/剔除（半选时显示不确定态）
                item(key = "group:$group") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                kept = if (allKept) kept - groupKeys else kept + groupKeys
                            }
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TriStateCheckbox(
                            state = when {
                                allKept -> ToggleableState.On
                                noneKept -> ToggleableState.Off
                                else -> ToggleableState.Indeterminate
                            },
                            onClick = {
                                kept = if (allKept) kept - groupKeys else kept + groupKeys
                            },
                        )
                        Text(
                            group,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "$keptInGroup/${groupKeys.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                items(list, key = { it.second }) { (ch, key) ->
                    val isKept = key in kept
                    // 连通性探测状态：可达显示延迟、不可达标红、未知协议标灰、检测中提示
                    val probe = results[key]
                    val (statusText, statusColor) = when {
                        probe == null ->
                            (if (probing) "检测中" else "") to MaterialTheme.colorScheme.onSurfaceVariant
                        probe.status == ChannelProbe.Status.OK ->
                            "${probe.latencyMs}ms" to MaterialTheme.colorScheme.primary
                        probe.status == ChannelProbe.Status.FAIL ->
                            "不可达" to MaterialTheme.colorScheme.error
                        else -> "未知" to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    ListItem(
                        headlineContent = {
                            Text(ch.name, style = MaterialTheme.typography.bodyMedium)
                        },
                        supportingContent = {
                            Text(
                                "${ch.group} · ${ch.urls.size} 个源",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        trailingContent = {
                            Column(horizontalAlignment = Alignment.End) {
                                if (statusText.isNotEmpty()) {
                                    Text(
                                        statusText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = statusColor,
                                    )
                                    Spacer(Modifier.size(2.dp))
                                }
                                Checkbox(
                                    checked = isKept,
                                    onCheckedChange = null,
                                )
                            }
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                kept = if (isKept) kept - key else kept + key
                            },
                    )
                }
            }
        }

        Button(
            onClick = { onConfirm(keptChannels) },
            enabled = kept.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (kept.isEmpty()) "至少保留 1 个频道" else "确认导入 ${kept.size} 个频道",
            )
        }
    }
}

/**
 * 频道稳定键：tvg-id 经常为空，同名频道可能跨分组，单用名称不足以区分；
 * 用「名称 + NUL + 首个播放地址」既稳定又能区分同一名称的不同源。
 */
private fun keyOf(ch: Channel): String =
    ch.name + "\u0000" + (ch.urls.firstOrNull() ?: "")
