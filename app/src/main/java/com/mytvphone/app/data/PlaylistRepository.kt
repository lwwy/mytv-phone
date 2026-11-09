package com.mytvphone.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** 订阅持久化：应用私有目录 playlists.json，不申请任何存储权限。 */
class PlaylistRepository(private val context: Context) {

    private val gson = Gson()
    private val store = File(context.filesDir, "playlists.json")
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val _subs = MutableStateFlow<List<Subscription>>(emptyList())
    val subs: StateFlow<List<Subscription>> = _subs.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    fun notify(msg: String) {
        _message.value = msg
    }

    fun load() {
        _subs.value = try {
            if (!store.exists()) {
                emptyList()
            } else {
                val type = object : TypeToken<List<Subscription>>() {}.type
                gson.fromJson<List<Subscription>>(store.readText(), type) ?: emptyList()
            }
        } catch (e: Exception) {
            notify("读取本地订阅失败：${e.message}")
            emptyList()
        }
    }

    fun get(id: String): Subscription? = _subs.value.firstOrNull { it.id == id }

    fun add(sub: Subscription) {
        val list = _subs.value.toMutableList()
        list.add(sub)
        persist(list)
    }

    /** 刷新成功才覆盖；失败保留上一次的频道列表。 */
    fun replaceChannels(id: String, channels: List<Channel>) {
        val list = _subs.value.map { if (it.id == id) it.copy(channels = channels) else it }
        persist(list)
    }

    fun rename(id: String, name: String) {
        val list = _subs.value.map { if (it.id == id) it.copy(name = name) else it }
        persist(list)
    }

    fun remove(id: String) {
        persist(_subs.value.filter { it.id != id })
    }

    fun exportJson(): String = gson.toJson(_subs.value)

    fun importBackup(json: String): Boolean = try {
        val type = object : TypeToken<List<Subscription>>() {}.type
        val list = gson.fromJson<List<Subscription>>(json, type) ?: emptyList()
        persist(list)
        true
    } catch (e: Exception) {
        notify("备份文件解析失败：${e.message}")
        false
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
        try {
            store.writeText(gson.toJson(list))
        } catch (e: Exception) {
            notify("保存订阅失败：${e.message}")
        }
        _subs.value = list
    }
}
