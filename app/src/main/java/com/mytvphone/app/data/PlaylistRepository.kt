package com.mytvphone.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /** 返回错误描述；null 表示成功。 */
    suspend fun load(): String? = withContext(Dispatchers.IO) {
        try {
            _subs.value = if (!store.exists()) {
                emptyList()
            } else {
                val type = object : TypeToken<List<Subscription>>() {}.type
                gson.fromJson<List<Subscription>>(store.readText(), type) ?: emptyList()
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "读取本地订阅失败", e)
            _subs.value = emptyList()
            "读取本地订阅失败：${e.message}"
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

    fun rename(id: String, name: String) {
        persist(_subs.value.map { if (it.id == id) it.copy(name = name) else it })
    }

    fun remove(id: String) {
        persist(_subs.value.filter { it.id != id })
    }

    fun exportJson(): String = gson.toJson(_subs.value)

    /** 返回错误描述；null 表示成功。 */
    fun importBackup(json: String): String? = try {
        val type = object : TypeToken<List<Subscription>>() {}.type
        val list = gson.fromJson<List<Subscription>>(json, type) ?: emptyList()
        persist(list)
        null
    } catch (e: Exception) {
        "备份文件解析失败：${e.message}"
    }

    /** 网络订阅拉取（返回原始字节，交给 PlaylistParser 做 UTF-8 / GBK 识别）。 */
    suspend fun fetch(url: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android) MyTvPhone/1.0")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                resp.body?.bytes() ?: error("响应内容为空")
            }
        }
    }

    private fun persist(list: List<Subscription>) {
        _subs.value = list // 内存立即更新，保证 UI 即时响应
        val version = writeVersion.incrementAndGet()
        ioScope.launch {
            writeMutex.withLock {
                // 已有更新的写入时跳过，保证最终落盘的是最后一份数据
                if (version != writeVersion.get()) return@withLock
                try {
                    store.writeText(gson.toJson(list))
                } catch (e: Exception) {
                    Log.w(TAG, "保存订阅失败", e)
                }
            }
        }
    }

    private companion object {
        const val TAG = "PlaylistRepository"
    }
}
