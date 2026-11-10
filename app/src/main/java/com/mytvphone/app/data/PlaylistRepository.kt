package com.mytvphone.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel as EventChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 订阅持久化：应用私有目录 playlists.json，不申请任何存储权限。
 * 磁盘读写统一放在单线程 IO 协程里，避免主线程阻塞与乱序写。
 */
class PlaylistRepository(private val context: Context) {

    private val gson = Gson()
    private val store = File(context.filesDir, "playlists.json")
    private val tmp = File(context.filesDir, "playlists.json.tmp")
    private val bak = File(context.filesDir, "playlists.json.bak")
    private val corrupt = File(context.filesDir, "playlists.json.corrupt")
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val writeVersion = AtomicLong(0)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val _subs = MutableStateFlow<List<Subscription>>(emptyList())
    val subs: StateFlow<List<Subscription>> = _subs.asStateFlow()

    /**
     * 落盘失败事件。
     *
     * persist() 先更新内存再异步写盘，所以 UI 早已提示「已导入」；如果写盘失败只打日志，
     * 用户会以为数据存好了，重启后才发现频道没了。这里把失败抛成一次性事件，
     * 由 ViewModel 转成 Snackbar。
     */
    private val _writeErrors = EventChannel<String>(EventChannel.BUFFERED)
    val writeErrors: Flow<String> = _writeErrors.receiveAsFlow()

    /**
     * 返回错误描述；null 表示成功。
     *
     * 读取失败时**绝不**清空内存列表、也绝不覆盖/删除磁盘原文件：
     *  1) 把损坏的 store 复制为 .corrupt 备份，保留现场供排查；
     *  2) 优先从最近一次成功备份 .bak 恢复；
     *  3) 若连 .bak 也没有，则保留当前内存列表（不主动清空），仅向上层返回错误提示。
     */
    suspend fun load(): String? = withContext(Dispatchers.IO) {
        try {
            // 文件不存在（首次启动）视为空列表；存在但损坏则抛异常进入下方备份逻辑
            _subs.value = readSubscriptions(store, gson) ?: emptyList()
            null
        } catch (e: Exception) {
            Log.w(TAG, "读取本地订阅失败", e)
            if (store.exists()) runCatching { store.copyTo(corrupt, overwrite = true) }
            // 仅当 .bak 真实存在时才尝试恢复，避免把「无备份」误报成「已从备份恢复」
            val recovered = if (bak.exists()) runCatching { readSubscriptions(bak, gson) }.getOrNull() else null
            if (recovered != null) {
                _subs.value = recovered
                return@withContext "订阅文件损坏，已从备份恢复"
            }
            // 无法恢复：保留当前内存列表（不主动清空），仅向上层返回错误提示
            "读取本地订阅失败：${e.message}（已保留原文件副本）"
        }
    }

    fun get(id: String): Subscription? = _subs.value.firstOrNull { it.id == id }

    fun add(sub: Subscription) {
        persist(_subs.value + sub)
    }

    /** 刷新成功才覆盖；失败保留上一次的频道列表。 */
    fun replaceChannels(id: String, channels: List<Channel>) {
        persist(_subs.value.map { if (it.id == id) it.copy(channels = channels) else it })
    }

    fun remove(id: String) {
        persist(_subs.value.filter { it.id != id })
    }

    /** 网络订阅拉取（返回原始字节，交给 PlaylistParser 做 UTF-8 / GBK 识别）。 */
    suspend fun fetch(url: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", DEFAULT_USER_AGENT)
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val body = resp.body ?: error("响应内容为空")
                // contentLength 已知且超限时提前拒绝；未知长度则边读边限制
                val len = body.contentLength()
                if (len > PlaylistParser.MAX_SOURCE_BYTES) {
                    error("文件过大（${len / 1024 / 1024}MB），已超过上限 ${PlaylistParser.MAX_SOURCE_BYTES / 1024 / 1024}MB")
                }
                PlaylistParser.readBounded(body.byteStream(), PlaylistParser.MAX_SOURCE_BYTES)
            }
        }
    }

    /**
     * 原子写入 + 版本备份：
     *  1) 内存立即更新，保证 UI 即时响应；
     *  2) 写前先把当前成功版本复制为 .bak（损坏时仍可回退）；
     *  3) 先写临时文件再 rename 原子替换主文件，避免「写一半断电/崩溃」损坏 store；
     *     rename 在极少数跨存储场景会失败，此时退化为直接覆盖写 store，并清理临时文件。
     */
    private fun persist(list: List<Subscription>) {
        _subs.value = list // 内存立即更新，保证 UI 即时响应
        val version = writeVersion.incrementAndGet()
        ioScope.launch {
            writeMutex.withLock {
                // 已有更新的写入时跳过，保证最终落盘的是最后一份数据
                if (version != writeVersion.get()) return@withLock
                val json = gson.toJson(list)
                try {
                    if (store.exists()) runCatching { store.copyTo(bak, overwrite = true) }
                    tmp.writeText(json)
                    val ok = tmp.renameTo(store) || runCatching { store.writeText(json) }.isSuccess
                    if (tmp.exists()) runCatching { tmp.delete() }
                    if (!ok) fail("写入文件失败")
                } catch (e: Exception) {
                    fail(e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    /** 落盘失败：日志 + 通知 UI。内存列表保持不变，避免 UI 与磁盘出现两种「现实」。 */
    private fun fail(reason: String) {
        Log.w(TAG, "保存订阅失败：$reason")
        _writeErrors.trySend("订阅已更新，但保存到本地失败（$reason），重启后可能丢失")
    }

    private companion object {
        const val TAG = "PlaylistRepository"
    }
}

/**
 * 读取订阅 JSON（纯 java.io + Gson，不碰 Android API，可直接单测）。
 *
 * 文件不存在返回 null（首次启动）；存在但解析失败抛异常，交由 [PlaylistRepository.load]
 * 处理 .corrupt 备份与 .bak 恢复。
 *
 * **0 字节 / 全空白 / 字面量 null 都算损坏**，绝不能当成「空列表」：
 * 写到一半被断电或杀进程时 store 会被截断成 0 字节，若这里静默返回空列表，
 * load() 就不会进 catch、不会触发 .bak 恢复，用户几十个频道会无声消失。
 */
internal fun readSubscriptions(file: File, gson: Gson): List<Subscription>? {
    if (!file.exists()) return null
    if (file.length() == 0L) error("文件为空，可能写入中断")
    val text = file.readText()
    if (text.isBlank()) error("文件内容为空")
    val type = object : TypeToken<List<Subscription>>() {}.type
    return gson.fromJson<List<Subscription>>(text, type) ?: error("JSON 内容为 null")
}
