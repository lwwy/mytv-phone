package com.mytvphone.app.data

import android.util.Base64
import com.google.gson.JsonParser
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.LinkedHashMap

/**
 * 直播源解析器。
 *
 * 支持四类输入：
 *  1. M3U / M3U8（#EXTM3U / #EXTINF，含 tvg-name / group-title / tvg-logo / x-tvg-url）
 *  2. TXT（"分组名,#genre#" + "频道名,url1,url2"）
 *  3. JSON（List<TV>，兼容 my-tv-0 导出的频道列表）
 *  4. Gua64 六十四卦编码（兼容 my-tv-0 的 channels.txt），解出明文后按上面三类继续解析
 *  5. 兜底：纯文本逐行 URL
 *
 * 另外修正了参考项目的几个短板：
 *  - 从 #EXTVLCOPT / #KODIPROP / #EXTHTTP 和 "url|Referer=xxx" 中提取 UA / Referer 等请求头
 *  - UTF-8 / GBK 自动识别、BOM 处理
 *  - 用 LinkedHashMap 合并同名频道，保持原始顺序
 *  - 无效行跳过、URL 去重、未填分组归入「未分组」
 */
object PlaylistParser {

    // gua64 字母表：64 卦按 0..63 排列（坤=0 … 乾=63），〇 为 padding
    private const val GUA =
        "䷁䷖䷇䷓䷏䷢䷬䷋" +
        "䷎䷳䷦䷴䷽䷷䷞䷠" +
        "䷆䷃䷜䷺䷧䷿䷮䷅" +
        "䷭䷑䷯䷸䷟䷱䷛䷫" +
        "䷗䷚䷂䷩䷲䷔䷐䷘" +
        "䷣䷕䷾䷤䷶䷝䷰䷌" +
        "䷒䷨䷻䷼䷵䷥䷹䷉" +
        "䷊䷙䷄䷈䷡䷍䷪䷀"
    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private val nameRe = Regex("tvg-name=\"([^\"]*)\"")
    private val idRe = Regex("tvg-id=\"([^\"]*)\"")
    private val logoRe = Regex("tvg-logo=\"([^\"]*)\"")
    private val groupRe = Regex("group-title=\"([^\"]*)\"")
    private val epgRe = Regex("x-tvg-url=\"([^\"]*)\"")
    private val jsonKvRe = Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"")
    private val urlRe = Regex("^(https?|rtsp|rtmp|rtp|udp)://\\S+$", RegexOption.IGNORE_CASE)

    /** 字节 → 文本：去 BOM，UTF-8 优先，非法字节回退 GBK。 */
    fun decodeAuto(bytes: ByteArray): String {
        val data = stripBom(bytes)
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data))
                .toString()
        } catch (e: CharacterCodingException) {
            try {
                String(data, Charset.forName("GBK"))
            } catch (e2: Exception) {
                String(data, StandardCharsets.UTF_8)
            }
        }
    }

    private fun stripBom(b: ByteArray): ByteArray = when {
        b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() ->
            b.copyOfRange(3, b.size)
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() ->
            String(b, StandardCharsets.UTF_16LE).toByteArray(StandardCharsets.UTF_8)
        b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() ->
            String(b, StandardCharsets.UTF_16BE).toByteArray(StandardCharsets.UTF_8)
        else -> b
    }

    private fun looksLikeGua(s: String): Boolean {
        val c = s.trim().firstOrNull() ?: return false
        return c == '〇' || GUA.indexOf(c) >= 0
    }

    /** 六十四卦编码 → 标准 Base64 → 原始文本。失败返回 null。 */
    fun decodeGua(s: String): String? {
        val sb = StringBuilder()
        for (c in s.trim()) {
            if (c == '〇') {
                sb.append('=')
                continue
            }
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') continue
            val i = GUA.indexOf(c)
            if (i < 0) return null
            sb.append(B64[i])
        }
        return try {
            decodeAuto(Base64.decode(sb.toString(), Base64.DEFAULT))
        } catch (e: Exception) {
            null
        }
    }

    /** 统一解析入口。 */
    fun parse(raw: String): ParseResult {
        var text = raw.trim().trimStart('\uFEFF')
        if (text.isEmpty()) return ParseResult(format = "空内容")

        var prefix = ""
        if (looksLikeGua(text)) {
            val decoded = decodeGua(text)
            if (decoded != null) {
                text = decoded.trim()
                prefix = "GUA64 → "
            }
        }

        val result = when {
            text.startsWith("[") -> parseJson(text)
            text.startsWith("#EXTM3U", true) ||
                text.startsWith("#EXTINF", true) ||
                text.contains("#EXTINF") -> parseM3u(text)
            text.contains("#genre#") -> parseTxt(text)
            text.lines().any { looksLikeUrl(it.trim()) } -> parseLoose(text)
            else -> ParseResult(format = "无法识别")
        }
        return result.copy(format = prefix + result.format)
    }

    private class Pending(
        var name: String? = null,
        var title: String = "",
        var group: String = "未分组",
        var logo: String = "",
        var headers: MutableMap<String, String> = mutableMapOf(),
    )

    private fun parseM3u(text: String): ParseResult {
        val merged = LinkedHashMap<String, Channel>()
        val order = ArrayList<String>()
        var epg: String? = null
        var skipped = 0
        var pending: Pending? = null
        var extGrp: String? = null

        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTM3U", true) -> {
                    epgRe.find(line)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { epg = it }
                }

                line.startsWith("#EXTINF", true) -> {
                    val p = Pending()
                    val head = line.substringBeforeLast(',')
                    val title = line.substringAfterLast(',').trim()
                    p.title = title
                    p.name = nameRe.find(head)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                        ?: idRe.find(head)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                        ?: title.takeIf { it.isNotEmpty() }
                        ?: "未命名频道"
                    p.group = groupRe.find(head)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                        ?: extGrp
                        ?: "未分组"
                    p.logo = logoRe.find(head)?.groupValues?.get(1) ?: ""
                    pending = p
                }

                line.startsWith("#EXTGRP", true) -> {
                    extGrp = line.substringAfter(':', "").trim().takeIf { it.isNotEmpty() }
                }

                line.startsWith("#EXTVLCOPT", true) ||
                    line.startsWith("#KODIPROP", true) ||
                    line.startsWith("#EXTHTTP", true) -> {
                    pending?.let { parseHeaderLine(line, it.headers) }
                }

                line.startsWith("#") -> {
                    // 其余注释 / HLS 内部标签，忽略
                }

                looksLikeUrl(line) -> {
                    val (url, urlHeaders) = splitUrlHeaders(line)
                    val p = pending ?: Pending(name = guessNameFromUrl(url), title = guessNameFromUrl(url))
                    val finalName = p.name?.takeIf { it.isNotBlank() } ?: guessNameFromUrl(url)
                    val finalTitle = p.title.takeIf { it.isNotBlank() } ?: finalName
                    val headers = p.headers.toMutableMap().apply { putAll(urlHeaders) }
                    val key = p.group + "\u0000" + finalName
                    val existing = merged[key]
                    if (existing == null) {
                        merged[key] = Channel(
                            id = makeId(p.group, finalName),
                            name = finalName,
                            title = finalTitle,
                            group = p.group,
                            logo = p.logo,
                            urls = listOf(url),
                            headers = headers,
                        )
                        order.add(key)
                    } else {
                        merged[key] = existing.copy(
                            urls = (existing.urls + url).distinct(),
                            headers = existing.headers + urlHeaders,
                            logo = existing.logo.ifEmpty { p.logo },
                        )
                    }
                    pending = null
                }

                else -> skipped++
            }
        }

        val channels = order.mapNotNull { merged[it] }
        return ParseResult(
            format = "M3U",
            channels = channels,
            groups = channels.map { it.group }.distinct(),
            epgUrl = epg,
            skipped = skipped,
        )
    }

    private fun parseTxt(text: String): ParseResult {
        val merged = LinkedHashMap<String, Channel>()
        var group = "未分组"
        var skipped = 0

        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || (line.startsWith("#") && !line.contains("#genre#"))) continue
            if (line.contains("#genre#")) {
                group = line.substringBefore(',').trim().takeIf { it.isNotEmpty() } ?: "未分组"
                continue
            }
            val parts = line.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue
            val name = parts.first()
            val urls = ArrayList<String>()
            val headers = HashMap<String, String>()
            for (candidate in parts.drop(1)) {
                if (!looksLikeUrl(candidate)) continue
                val (url, h) = splitUrlHeaders(candidate)
                urls.add(url)
                headers.putAll(h)
            }
            if (urls.isEmpty()) {
                skipped++
                continue
            }
            val key = group + "\u0000" + name
            val existing = merged[key]
            if (existing == null) {
                merged[key] = Channel(
                    id = makeId(group, name),
                    name = name,
                    title = name,
                    group = group,
                    urls = urls.distinct(),
                    headers = headers,
                )
            } else {
                merged[key] = existing.copy(
                    urls = (existing.urls + urls).distinct(),
                    headers = existing.headers + headers,
                )
            }
        }

        val channels = merged.values.toList()
        return ParseResult(
            format = "TXT (#genre#)",
            channels = channels,
            groups = channels.map { it.group }.distinct(),
            skipped = skipped,
        )
    }

    /** 兼容 my-tv-0 导出的 List<TV> JSON（字段：name/title/group/logo/uris/headers）。 */
    private fun parseJson(text: String): ParseResult {
        return try {
            val arr = JsonParser.parseString(text).asJsonArray
            val list = ArrayList<Channel>()
            for (el in arr) {
                if (!el.isJsonObject) continue
                val o = el.asJsonObject
                fun str(vararg keys: String): String = keys.firstNotNullOfOrNull { k ->
                    o.get(k)?.takeIf { it.isJsonPrimitive }?.asString
                } ?: ""
                val name = str("name", "title", "tvg-name").takeIf { it.isNotEmpty() } ?: "未命名频道"
                val group = str("group", "group-title").takeIf { it.isNotEmpty() } ?: "未分组"
                val logo = str("logo", "tvg-logo")
                val urls = ArrayList<String>()
                o.get("uris")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { u ->
                    if (u.isJsonPrimitive) urls.add(u.asString)
                }
                o.get("url")?.takeIf { it.isJsonPrimitive }?.asString?.let { urls.add(it) }
                val headers = HashMap<String, String>()
                o.get("headers")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { e ->
                    if (e.value.isJsonPrimitive) headers[e.key] = e.value.asString
                }
                if (urls.isEmpty()) continue
                list.add(
                    Channel(
                        id = makeId(group, name),
                        name = name,
                        title = str("title").takeIf { it.isNotEmpty() } ?: name,
                        group = group,
                        logo = logo,
                        urls = urls.distinct(),
                        headers = headers,
                    )
                )
            }
            ParseResult(
                format = "JSON",
                channels = list,
                groups = list.map { it.group }.distinct(),
            )
        } catch (e: Exception) {
            ParseResult(format = "JSON 解析失败")
        }
    }

    /** 兜底：没有 EXTINF 头的纯文本，以上一行文字作为频道名。 */
    private fun parseLoose(text: String): ParseResult {
        val list = ArrayList<Channel>()
        var lastName: String? = null
        var skipped = 0
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (looksLikeUrl(line)) {
                val (url, headers) = splitUrlHeaders(line)
                val name = lastName ?: guessNameFromUrl(url)
                list.add(
                    Channel(
                        id = makeId("未分组", name),
                        name = name,
                        title = name,
                        group = "未分组",
                        urls = listOf(url),
                        headers = headers,
                    )
                )
                lastName = null
            } else if (line.startsWith("#")) {
                skipped++
            } else {
                lastName = line.trimEnd(',')
            }
        }
        return ParseResult(
            format = "纯文本",
            channels = list,
            groups = list.map { it.group }.distinct(),
            skipped = skipped,
        )
    }

    private fun parseHeaderLine(line: String, out: MutableMap<String, String>) {
        val body = line.substringAfter(':', "")
        if (body.startsWith("{")) {
            jsonKvRe.findAll(body).forEach { m ->
                val key = normalizeHeaderKey(m.groupValues[1])
                if (key.isNotEmpty() && m.groupValues[2].isNotEmpty()) out[key] = m.groupValues[2]
            }
            return
        }
        val eq = body.indexOf('=')
        if (eq <= 0) return
        val rawKey = body.substring(0, eq).trim()
        val value = body.substring(eq + 1).trim()
        val key = normalizeHeaderKey(rawKey.removePrefix("http-"))
        if (key.isNotEmpty() && value.isNotEmpty()) {
            out[key] = value
        } else {
            // Kodi 风格：#KODIPROP:inputstream.adaptive.stream_headers=User-Agent=X&Referer=Y
            value.split('&').forEach { kv ->
                val k = kv.substringBefore('=', "").trim()
                val v = kv.substringAfter('=', "").trim()
                val nk = normalizeHeaderKey(k)
                if (nk.isNotEmpty() && v.isNotEmpty()) out[nk] = v
            }
        }
    }

    private fun normalizeHeaderKey(k: String): String = when (k.lowercase()) {
        "user-agent", "ua", "http-user-agent" -> "User-Agent"
        "referer", "referrer", "http-referrer", "http-referer" -> "Referer"
        "origin" -> "Origin"
        "cookie" -> "Cookie"
        "authorization" -> "Authorization"
        else -> if (k.contains('-')) k else ""
    }

    /** 处理 "http://xxx.m3u8|User-Agent=abc&Referer=def" 这类内联 header。 */
    private fun splitUrlHeaders(s: String): Pair<String, Map<String, String>> {
        val idx = s.indexOf('|')
        if (idx < 0) return s to emptyMap()
        val url = s.substring(0, idx)
        val headers = HashMap<String, String>()
        s.substring(idx + 1).split('&', '$').forEach { part ->
            if (!part.contains('=')) return@forEach
            val k = part.substringBefore('=').trim()
            val v = part.substringAfter('=').trim()
            val key = normalizeHeaderKey(k)
            if (key.isNotEmpty() && v.isNotEmpty()) {
                headers[key] = try {
                    URLDecoder.decode(v, "UTF-8")
                } catch (e: Exception) {
                    v
                }
            }
        }
        return url to headers
    }

    private fun looksLikeUrl(s: String): Boolean = urlRe.matches(s)

    private fun guessNameFromUrl(url: String): String = try {
        java.net.URI(url).path
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotEmpty() }
            ?: "未命名频道"
    } catch (e: Exception) {
        "未命名频道"
    }

    private fun makeId(group: String, name: String): String {
        val md = MessageDigest.getInstance("MD5")
            .digest("$group\u0000$name".toByteArray(StandardCharsets.UTF_8))
        return md.joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
    }
}
