package com.mytvphone.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mytvphone.app.data.AppSettings
import com.mytvphone.app.data.Channel
import com.mytvphone.app.data.ParseResult
import com.mytvphone.app.data.PlaylistParser
import com.mytvphone.app.data.PlaylistRepository
import com.mytvphone.app.data.Prefs
import com.mytvphone.app.data.Subscription
import com.mytvphone.app.player.PlayerController
import kotlinx.coroutines.channels.Channel as EventChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val repo = PlaylistRepository(app)
    val prefs = Prefs(app)
    val controller = PlayerController(app)

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _showImport = MutableStateFlow(false)
    val showImport: StateFlow<Boolean> = _showImport.asStateFlow()

    /** 从其他 App 分享/打开进来的 (名称, 文本)，用于自动填充导入面板。 */
    private val _externalText = MutableStateFlow<Pair<String, String>?>(null)
    val externalText: StateFlow<Pair<String, String>?> = _externalText.asStateFlow()

    /** 一次性提示：Channel 会缓冲直到 UI 消费，不会像 StateFlow 那样丢事件。 */
    private val _toast = EventChannel<String>(EventChannel.BUFFERED)
    val toast = _toast.receiveAsFlow()

    init {
        viewModelScope.launch {
            repo.load()?.let { showToast(it) }
        }
        // 设置 + 订阅合并成单一来源，避免两个流各自刷新造成竞态
        viewModelScope.launch {
            combine(prefs.settings, repo.subs) { s, subs -> s to subs }
                .collect { (s, subs) ->
                    _settings.value = s
                    val activeId = s.activeSubId.ifEmpty { subs.firstOrNull()?.id ?: "" }
                    _channels.value = subs.firstOrNull { it.id == activeId }?.channels ?: emptyList()
                }
        }
    }

    fun setActiveSub(id: String) = viewModelScope.launch {
        prefs.setActiveSub(id)
        showToast("已切换到：${repo.get(id)?.name ?: ""}")
    }

    /** 预览确认后真正写入订阅。 */
    fun commitImport(name: String, source: String, result: ParseResult, url: String? = null) {
        if (result.isEmpty) {
            showToast("没有解析到可用频道，未导入")
            return
        }
        val sub = Subscription(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "订阅 ${repo.subs.value.size + 1}" },
            source = source,
            url = url,
            importedAt = System.currentTimeMillis(),
            channels = result.channels,
        )
        repo.add(sub)
        viewModelScope.launch { prefs.setActiveSub(sub.id) }
        showToast("已导入 ${result.channels.size} 个频道 / ${result.groups.size} 个分组")
    }

    /** 网络订阅刷新：失败时保留上一次成功的频道列表。解析整体放入 IO，不卡主线程。 */
    fun refreshSubscription(id: String) = viewModelScope.launch {
        val sub = repo.get(id) ?: return@launch
        val url = sub.url ?: run {
            showToast("该订阅来自本地文件，无法在线刷新")
            return@launch
        }
        showToast("正在更新订阅…")
        val bytes = repo.fetch(url)
        if (bytes.isFailure) {
            showToast("更新失败：${bytes.exceptionOrNull()?.message}，已保留原列表")
            return@launch
        }
        // fetch 内部虽在 IO，但 onSuccess 会从 withContext 切回主线程，
        // 所以这里显式再切到 IO 做解码 + 解析，避免大列表阻塞主线程
        val result = withContext(Dispatchers.IO) {
            PlaylistParser.parse(PlaylistParser.decodeAuto(bytes.getOrThrow()))
        }
        if (result.isEmpty) {
            showToast("更新失败：未解析到频道，已保留原列表")
        } else {
            repo.replaceChannels(id, result.channels)
            showToast("已更新为 ${result.channels.size} 个频道")
        }
    }

    fun deleteSubscription(id: String) {
        repo.remove(id)
        viewModelScope.launch { prefs.setActiveSub(repo.subs.value.firstOrNull()?.id ?: "") }
    }

    fun renameSubscription(id: String, name: String) = repo.rename(id, name)

    fun exportJson(): String = repo.exportJson()

    fun importBackup(json: String) {
        val err = repo.importBackup(json)
        showToast(err ?: "配置已导入")
        viewModelScope.launch { prefs.setActiveSub("") }
    }

    fun playChannel(ch: Channel) {
        controller.play(ch)
    }

    fun nextChannel() = step(1)

    fun prevChannel() = step(-1)

    private fun step(delta: Int) {
        val list = _channels.value
        if (list.isEmpty()) return
        val st = controller.state.value
        val cur = list.indexOfFirst { it.name == st.channelName && it.group == st.group }
        val next = if (cur < 0) 0 else (cur + delta + list.size) % list.size
        playChannel(list[next])
    }

    fun setLockLandscape(v: Boolean) = viewModelScope.launch { prefs.setLockLandscape(v) }
    fun setGestures(v: Boolean) = viewModelScope.launch { prefs.setGestures(v) }

    fun showImportSheet() {
        _showImport.value = true
    }

    fun hideImportSheet() {
        _showImport.value = false
        _externalText.value = null
    }

    fun setExternalImport(name: String, text: String) {
        _externalText.value = name to text
        _showImport.value = true
    }

    fun consumeExternal() {
        _externalText.value = null
    }

    fun showToast(msg: String) {
        _toast.trySend(msg)
    }

    override fun onCleared() {
        controller.release()
        super.onCleared()
    }
}
