package com.mytvphone.app.data

/**
 * 拉流与拉订阅共用的 User-Agent。
 * 部分源会按 UA 拒绝请求，两处各写一份容易改漏，统一放在这里。
 * 不带版本号：升级版本时无需改动，也避免与 BuildConfig 版本号脱节。
 */
const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 10) MyTvPhone"

/** 一个频道。同名同分组的多个地址会合并进 [urls]，播放失败时自动逐个切换。 */
data class Channel(
    val id: String = "",
    val name: String = "",
    val title: String = "",
    val group: String = "未分组",
    val logo: String = "",
    val urls: List<String> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
)

/** 一份订阅（一次导入的结果）。允许多份并存、随时切换。 */
data class Subscription(
    val id: String = "",
    val name: String = "",
    val source: String = "",
    val url: String? = null,
    val importedAt: Long = 0L,
    val channels: List<Channel> = emptyList(),
)

/** 解析结果，导入前用于预览确认。 */
data class ParseResult(
    val format: String = "未知",
    val channels: List<Channel> = emptyList(),
    val groups: List<String> = emptyList(),
    val skipped: Int = 0,
) {
    val isEmpty: Boolean get() = channels.isEmpty()
}
