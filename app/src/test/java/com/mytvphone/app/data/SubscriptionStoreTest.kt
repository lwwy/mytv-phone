package com.mytvphone.app.data

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 订阅文件读取的单元测试（JVM，不依赖 Android）。
 *
 * 重点覆盖「0 字节文件」这一类**静默丢数据**的场景：读到损坏内容必须抛异常，
 * 这样 PlaylistRepository.load() 才会走 .corrupt 备份 + .bak 恢复；
 * 如果把它当成空列表返回，用户的频道会在某次断电后无声消失。
 */
class SubscriptionStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val gson = Gson()

    private fun write(name: String, text: String): File =
        tmp.newFile(name).apply { writeText(text) }

    @Test
    fun `文件不存在返回 null（首次启动）`() {
        val missing = File(tmp.root, "nope.json")
        assertNull(readSubscriptions(missing, gson))
    }

    @Test
    fun `0 字节文件视为损坏并抛异常`() {
        val f = write("empty.json", "")
        assertEquals(0L, f.length())
        val e = runCatching { readSubscriptions(f, gson) }.exceptionOrNull()
        assertTrue("0 字节必须抛异常，否则会静默清空", e != null)
    }

    @Test
    fun `全空白文件视为损坏`() {
        val f = write("blank.json", "   \n\t ")
        assertTrue(runCatching { readSubscriptions(f, gson) }.isFailure)
    }

    @Test
    fun `字面量 null 视为损坏而不是空列表`() {
        val f = write("null.json", "null")
        assertTrue(runCatching { readSubscriptions(f, gson) }.isFailure)
    }

    @Test
    fun `非法 JSON 抛异常`() {
        val f = write("bad.json", "{ not an array")
        assertTrue(runCatching { readSubscriptions(f, gson) }.isFailure)
    }

    @Test
    fun `空数组是合法的空列表`() {
        val f = write("empty-array.json", "[]")
        val list = readSubscriptions(f, gson)
        assertEquals(0, list?.size)
    }

    @Test
    fun `正常内容解析出订阅与频道`() {
        val f = write(
            "ok.json",
            """
            [
              {
                "id": "sub-1",
                "name": "测试源",
                "source": "粘贴导入",
                "importedAt": 1700000000000,
                "channels": [
                  {"id": "c1", "name": "CCTV1", "group": "央视", "urls": ["http://a/b.m3u8"]}
                ]
              }
            ]
            """.trimIndent(),
        )
        val list = readSubscriptions(f, gson)
        assertEquals(1, list?.size)
        assertEquals("测试源", list?.first()?.name)
        assertEquals("CCTV1", list?.first()?.channels?.first()?.name)
        assertEquals(1, list?.first()?.channels?.first()?.urls?.size)
    }
}
