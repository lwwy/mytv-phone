package com.mytvphone.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 导入前对频道地址做连通性 / 延迟探测。
 *
 * 设计取舍：
 *  - 只支持 http/https。rtsp / rtmp / udp 等无法用简单 HTTP 探活，统一标记 [Status.UNKNOWN]
 *    并保留（不误删用户可能能播的源）。
 *  - 用 GET + Range 取前 64KB，而不是 HEAD：大量 IPTV 源不实现 HEAD，GET 小范围既能验证
 *    可达性、又能测出「建连 + 首字节」的 RTT；拿到响应码即 disconnect，不读完整 body，流量极小。
 *  - 每个地址独立 5 秒连接 / 读取超时，与播放器 [com.mytvphone.app.player.PlayerController] 一致。
 *  - 批量探测由调用方用 [Semaphore] 限制并发（见 [probeAll]），避免对源站瞬间打满连接。
 */
object ChannelProbe {

    enum class Status { OK, FAIL, UNKNOWN }

    data class Result(val status: Status, val latencyMs: Long?)

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000
    private const val RANGE_BYTES = 65_535L

    /** 同时发起的探测数上限，防止对源站瞬间建立过多连接。 */
    const val CONCURRENCY = 12

    /** 探测单个地址。IO 线程执行，不阻塞调用方。 */
    suspend fun probe(url: String): Result = withContext(Dispatchers.IO) {
        val scheme = url.substringBefore("://").lowercase()
        if (scheme !in setOf("http", "https")) {
            return@withContext Result(Status.UNKNOWN, null)
        }
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
                setRequestProperty("Range", "bytes=0-$RANGE_BYTES")
                instanceFollowRedirects = true
                useCaches = false
            }
            val start = System.nanoTime()
            val code = conn.responseCode
            val elapsed = (System.nanoTime() - start) / 1_000_000
            conn.disconnect()
            if (code in 200..399) Result(Status.OK, elapsed) else Result(Status.FAIL, null)
        } catch (_: Exception) {
            Result(Status.FAIL, null)
        }
    }

    /**
     * 批量探测：[items] 为「稳定键 → 首地址」，结果通过 [onResult] 回调（键, 结果）实时回传，
     * 调用方据此更新 UI；返回时已探测完所有项。并发受 [CONCURRENCY] 限制，
     * 任一地址超时最多 5 秒。协程被取消时（如离开预览页）会安全中止。
     */
    suspend fun probeAll(
        items: List<Pair<String, String>>,
        onResult: (String, Result) -> Unit,
    ) {
        val sem = Semaphore(CONCURRENCY)
        coroutineScope {
            items.forEach { (key, url) ->
                launch {
                    sem.withPermit {
                        if (url.isEmpty()) {
                            onResult(key, Result(Status.UNKNOWN, null))
                        } else {
                            onResult(key, probe(url))
                        }
                    }
                }
            }
        }
    }
}
