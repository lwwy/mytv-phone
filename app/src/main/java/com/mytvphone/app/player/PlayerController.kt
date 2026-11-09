package com.mytvphone.app.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.mytvphone.app.data.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlayerState(
    val channelName: String = "",
    val channelTitle: String = "",
    val group: String = "",
    val urlIndex: Int = 0,
    val urlCount: Int = 0,
    val error: String? = null,
    val info: String? = null,
    val buffering: Boolean = false,
    val playing: Boolean = false,
)

/**
 * Media3 / ExoPlayer 封装。
 *
 * 沿用参考项目 my-tv-0 的 TVModel.buildSource() 参数：
 *  - DefaultHttpDataSource 允许跨协议重定向、注入 User-Agent / Referer、5 秒超时
 *  - LiveConfiguration 目标延迟 12 秒（6~30 秒，速度 0.98~1.02）
 *  - 播放失败时按 HLS → DASH → RTSP → Progressive 强制重试，再切换到频道的下一个备用地址
 */
@UnstableApi
class PlayerController(context: Context) {

    private val appContext = context.applicationContext

    private val httpFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setKeepPostFor302Redirects(true)
        .setConnectTimeoutMs(5000)
        .setReadTimeoutMs(5000)

    val player: ExoPlayer = ExoPlayer.Builder(appContext)
        .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
        .build()

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var current: Channel? = null
    private var urlIndex = 0
    private var typeIndex = -1 // -1 = 交给 Media3 自动探测
    private val forcedTypes = listOf("HLS", "DASH", "RTSP", "Progressive")

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                _state.value = _state.value.copy(
                    buffering = playbackState == Player.STATE_BUFFERING,
                )
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(playing = isPlaying)
            }

            override fun onPlayerError(error: PlaybackException) {
                handleError(error)
            }
        })
    }

    fun play(channel: Channel) {
        if (channel.urls.isEmpty()) return
        current = channel
        urlIndex = 0
        typeIndex = -1
        startCurrent()
    }

    fun selectUrl(index: Int) {
        val ch = current ?: return
        if (index !in ch.urls.indices) return
        urlIndex = index
        typeIndex = -1
        startCurrent()
    }

    fun retry() {
        urlIndex = 0
        typeIndex = -1
        startCurrent()
    }

    private fun startCurrent() {
        val ch = current ?: return
        val url = ch.urls.getOrNull(urlIndex) ?: return

        val headers = HashMap<String, String>()
        headers["User-Agent"] = "Mozilla/5.0 (Linux; Android) MyTvPhone/1.0"
        ch.headers.forEach { (k, v) -> if (v.isNotBlank()) headers[k] = v }
        httpFactory.setDefaultRequestProperties(headers)

        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(12_000L)
                    .setMinOffsetMs(6_000L)
                    .setMaxOffsetMs(30_000L)
                    .setMinPlaybackSpeed(0.98f)
                    .setMaxPlaybackSpeed(1.02f)
                    .build()
            )
            .build()

        when (typeIndex) {
            0 -> player.setMediaSource(HlsMediaSource.Factory(httpFactory).createMediaSource(mediaItem))
            1 -> player.setMediaSource(DashMediaSource.Factory(httpFactory).createMediaSource(mediaItem))
            2 -> player.setMediaSource(
                RtspMediaSource.Factory()
                    .setUserAgent(headers["User-Agent"] ?: "")
                    .createMediaSource(mediaItem)
            )
            3 -> player.setMediaSource(ProgressiveMediaSource.Factory(httpFactory).createMediaSource(mediaItem))
            else -> player.setMediaItem(mediaItem)
        }
        player.prepare()
        player.playWhenReady = true

        _state.value = PlayerState(
            channelName = ch.name,
            channelTitle = ch.title,
            group = ch.group,
            urlIndex = urlIndex,
            urlCount = ch.urls.size,
            buffering = true,
            info = if (ch.urls.size > 1) "源 ${urlIndex + 1}/${ch.urls.size}" else null,
        )
    }

    private fun handleError(error: PlaybackException) {
        val ch = current ?: return
        if (typeIndex < forcedTypes.lastIndex) {
            typeIndex++
            startCurrent()
            _state.value = _state.value.copy(
                error = null,
                info = "重试解码方式 ${forcedTypes[typeIndex]}（${typeIndex + 1}/${forcedTypes.size}）",
            )
            return
        }
        if (urlIndex + 1 < ch.urls.size) {
            urlIndex++
            typeIndex = -1
            startCurrent()
            _state.value = _state.value.copy(
                error = null,
                info = "已自动切换备用源 ${urlIndex + 1}/${ch.urls.size}",
            )
            return
        }
        _state.value = _state.value.copy(
            error = describeError(error),
            info = null,
            buffering = false,
            playing = false,
        )
    }

    private fun describeError(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
            "网络连接失败或超时，请检查网络与源地址"

        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            "服务器拒绝访问（403 / 404），这个源可能需要 User-Agent 或 Referer"

        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
            "播放列表格式异常，源可能已失效"

        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            "设备解码器不支持该视频编码，可尝试其他源"

        else -> "播放失败：${e.errorCodeName}${e.message?.let { " · $it" } ?: ""}"
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    fun togglePlayPause() {
        player.playWhenReady = !player.playWhenReady
    }

    fun pause() {
        player.playWhenReady = false
    }

    fun resume() {
        player.playWhenReady = true
    }

    fun stop() {
        player.stop()
        current = null
        _state.value = PlayerState()
    }

    fun release() {
        player.release()
    }
}
