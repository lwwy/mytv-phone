package com.mytvphone.app.data

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
