package com.mytvphone.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PlaylistParser 单元测试（JVM，无需 Android 运行环境）。
 *
 * 覆盖：M3U / TXT / JSON 的正常解析、同名频道合并、格式错误识别。
 * 注意：本测试只走 parse() 的非 Gua64 分支，不触发 android.util.Base64，因此可在纯 JVM 运行。
 */
class PlaylistParserTest {

    // ---------- M3U ----------

    @Test
    fun `M3U 正常解析提取频道名与分组`() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-name="浙江卫视" group-title="卫视",浙江卫视
            http://example.com/zj.m3u8
            #EXTINF:-1 tvg-name="CCTV1" group-title="央视",CCTV1
            http://example.com/cctv1.m3u8
        """.trimIndent()

        val r = PlaylistParser.parse(m3u)

        assertEquals("M3U", r.format)
        assertFalse(r.isEmpty)
        assertEquals(2, r.channels.size)
        assertEquals("浙江卫视", r.channels[0].name)
        assertEquals("卫视", r.channels[0].group)
        assertEquals("http://example.com/zj.m3u8", r.channels[0].urls.first())
        assertEquals(listOf("卫视", "央视"), r.groups)
    }

    @Test
    fun `M3U 同名同分组频道合并多源`() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-name="浙江卫视" group-title="卫视",浙江卫视
            http://example.com/zj1.m3u8
            #EXTINF:-1 tvg-name="浙江卫视" group-title="卫视",浙江卫视
            http://example.com/zj2.m3u8
        """.trimIndent()

        val r = PlaylistParser.parse(m3u)

        // 同名同分组只保留一个条目，但合并出两个备用源
        assertEquals(1, r.channels.size)
        assertEquals(2, r.channels[0].urls.size)
        assertEquals("http://example.com/zj1.m3u8", r.channels[0].urls[0])
        assertEquals("http://example.com/zj2.m3u8", r.channels[0].urls[1])
    }

    // ---------- TXT ----------

    @Test
    fun `TXT 正常解析 #genre# 格式`() {
        val txt = """
            卫视,#genre#
            浙江卫视,http://example.com/zj.m3u8
            北京卫视,http://example.com/bj.m3u8
            央视,#genre#
            CCTV1,http://example.com/cctv1.m3u8
        """.trimIndent()

        val r = PlaylistParser.parse(txt)

        assertEquals("TXT (#genre#)", r.format)
        assertEquals(3, r.channels.size)
        assertEquals("卫视", r.channels[0].group)
        assertEquals("央视", r.channels[2].group)
    }

    @Test
    fun `TXT 同名频道合并多源`() {
        val txt = """
            卫视,#genre#
            浙江卫视,http://example.com/zj1.m3u8,http://example.com/zj2.m3u8
        """.trimIndent()

        val r = PlaylistParser.parse(txt)

        assertEquals(1, r.channels.size)
        assertEquals(2, r.channels[0].urls.size)
    }

    // ---------- JSON ----------

    @Test
    fun `JSON 正常解析 List TV`() {
        val json = """
            [
              {"name":"浙江卫视","group":"卫视","logo":"http://example.com/logo.png","uris":["http://example.com/zj.m3u8"]},
              {"name":"CCTV1","group":"央视","url":"http://example.com/cctv1.m3u8"}
            ]
        """.trimIndent()

        val r = PlaylistParser.parse(json)

        assertEquals("JSON", r.format)
        assertEquals(2, r.channels.size)
        assertEquals("浙江卫视", r.channels[0].name)
        assertEquals("http://example.com/logo.png", r.channels[0].logo)
        assertEquals("http://example.com/cctv1.m3u8", r.channels[1].urls.first())
    }

    @Test
    fun `JSON 同名频道合并多源`() {
        val json = """
            [
              {"name":"浙江卫视","group":"卫视","uris":["http://example.com/zj1.m3u8"]},
              {"name":"浙江卫视","group":"卫视","uris":["http://example.com/zj2.m3u8"]}
            ]
        """.trimIndent()

        val r = PlaylistParser.parse(json)

        assertEquals(1, r.channels.size)
        assertEquals(2, r.channels[0].urls.size)
    }

    @Test
    fun `JSON 解析失败返回空结果`() {
        // 以 [ 开头会被路由到 parseJson；不是合法 JSON 数组时进入解析失败分支
        val json = "[ this is not a valid json array"

        val r = PlaylistParser.parse(json)

        assertTrue(r.format.contains("JSON 解析失败"))
        assertTrue(r.isEmpty)
    }

    // ---------- 格式错误 ----------

    @Test
    fun `无法识别的内容返回空结果`() {
        val r = PlaylistParser.parse("这 是 一段 完全 没有 任何 http 链接 的 随意 文本")

        assertTrue(r.isEmpty)
    }

    @Test
    fun `空内容返回空结果`() {
        assertTrue(PlaylistParser.parse("   ").isEmpty)
    }

    // ---------- 大小限制 ----------

    @Test
    fun `readBounded 超限抛异常`() {
        val huge = ByteArray((PlaylistParser.MAX_SOURCE_BYTES + 1).toInt()) { 0 }
        val stream = java.io.ByteArrayInputStream(huge)

        val thrown = runCatching { PlaylistParser.readBounded(stream) }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
    }

    @Test
    fun `readBounded 正常读取不超限`() {
        val data = "small content".toByteArray()
        val stream = java.io.ByteArrayInputStream(data)

        val out = PlaylistParser.readBounded(stream)
        assertEquals("small content", String(out, Charsets.UTF_8))
    }
}
