// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-FileCopyrightText: 2024-2025 沉默の金 (LDDC KuGou signature scheme)
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.source

import io.github.univers629.spwtagworkbench.backend.lyrics.LyricsFormat
import io.github.univers629.spwtagworkbench.backend.net.Http
import io.github.univers629.spwtagworkbench.backend.net.Json
import io.github.univers629.spwtagworkbench.core.Diag
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * 酷狗音乐（公开检索接口）。
 *
 * 能力：歌词 + 元数据 + 艺术家图。
 *
 * 接口行为与处理：
 * - 搜索结果里的 `SongName` / `SingerName` 带 `<em>` 高亮标签，**必须剥掉**，
 *   否则会把 HTML 写进标签。
 * - 封面地址带 `{size}` 占位，`0` 是原图、`500` 是退档。搜索列表自带的 `Image`
 *   往往只是 150×150 缩略图，所以优先问详情接口要 `img`。
 * - 歌词接口要签名（`signature = md5(SALT + 排序参数串 + SALT)`）。
 *   优先取 `krc`（Base64 → 去前 4 字节 → 异或 → 解压，得到逐字歌词），失败时退回明文 `lrc`。
 */
class KugouSource : MusicSource {
    override val id: String = "kugou"

    override val label: String = "酷狗音乐"

    override val capabilities: Set<Capability> =
        setOf(Capability.LYRICAL, Capability.WORD_SYNCED, Capability.META, Capability.ARTIST_COVER)

    override val weight: Int = 35

    override suspend fun search(query: SearchQuery): SourceResult {
        val keyword =
            query.keyword
        if (keyword.isEmpty()) return SourceResult(id, label, failure = "标题为空")

        val response =
            Http.get(
                url = "https://songsearch.kugou.com/song_search_v2",
                query = mapOf("page" to "1", "pagesize" to "30", "keyword" to keyword),
                timeoutMs = 7000,
            )
        if (!response.ok) {
            Diag.log("kugou: 搜索失败 ${response.error} status=${response.status}")
            return SourceResult(id, label, failure = response.error.ifBlank { "HTTP ${response.status}" })
        }
        val parsed =
            try {
                Json.parse(response.body)
            } catch (t: Throwable) {
                Diag.log("kugou: 响应解析失败 ${t.javaClass.simpleName}")
                return SourceResult(id, label, failure = "响应格式异常")
            }
        val lists = parsed.obj("data")?.arr("lists") ?: emptyList()
        val candidates =
            lists.mapNotNull { song ->
                val title = clean(song.str("SongName").orEmpty())
                if (title.isBlank()) return@mapNotNull null
                val hash = song.str("FileHash").orEmpty()
                if (hash.isBlank()) return@mapNotNull null
                val artist = clean(song.str("SingerName").orEmpty())
                val album = clean(song.str("AlbumName").orEmpty())
                val seconds = song.num("Duration")?.toLong() ?: 0L
                val image = song.str("Image").orEmpty()
                SourceCandidate(
                    sourceId = id,
                    sourceLabel = label,
                    remoteId = hash,
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = seconds * 1000L,
                    hasLyrics = true,
                    hasCover = image.isNotBlank(),
                    // 优先取 krc（逐字）；取不到会退回 lrc
                    lyricFormat = "krc",
                    artworkUrl = image,
                    // 酷狗搜索里的 Image 本身就是歌手头像（singerimg.kugou.com/.../softhead/...），
                    // 正好可以直接当艺术家图用，不用另外请求
                    artistArtworkUrl = image,
                    // PublishDate 是 `yyyy-MM-dd`；注意它常是**再版日期**，不一定是首发
                    year = song.str("PublishDate")?.take(4)?.toIntOrNull() ?: 0,
                )
            }
        Diag.log(
            "kugou: 搜索「$keyword」返回 ${candidates.size} 条，" +
                "带封面 ${candidates.count { it.hasCover }} 条",
        )
        return SourceResult(id, label, candidates = candidates)
    }

    /**
     * 艺术家图。
     *
     * 酷狗搜索返回的 `Image` 本身就是歌手头像
     * （`singerimg.kugou.com/uploadpic/softhead/{size}/...`），
     * 替换 `{size}` 即可；实测 1200 / 1500 都能给。
     */
    override suspend fun artistCover(candidate: SourceCandidate): ByteArray? {
        val raw = candidate.artistArtworkUrl
        if (raw.isBlank()) return null
        val urls =
            buildList {
                if (raw.contains("{size}")) {
                    add(raw.replace("{size}", "1200"))
                    add(raw.replace("{size}", "480"))
                } else {
                    add(raw)
                }
            }.distinct()
        for (url in urls) {
            val bytes = Http.getBytes(url, timeoutMs = 9000)
            if (bytes != null && looksLikeImage(bytes)) return bytes
        }
        return null
    }

    override suspend fun cover(candidate: SourceCandidate): ByteArray? {
        val urls =
            buildList {
                // 详情接口的 img 是高清图，优先
                val detailImg = fetchDetailImage(candidate.remoteId)
                if (detailImg.isNotBlank()) addAll(expandSize(detailImg))
                // 搜索列表自带的当兜底
                val raw = candidate.artworkUrl
                if (raw.isNotBlank()) addAll(expandSize(raw))
            }.distinct()

        for (url in urls) {
            val bytes = Http.getBytes(url, timeoutMs = 9000)
            if (bytes != null && looksLikeImage(bytes)) return bytes
        }
        Diag.log("kugou: 取封面失败（试了 ${urls.size} 个地址）")
        return null
    }

    /**
     * `{size}` 占位展开。
     *
     * 酷狗 CDN 支持**任意尺寸**（实测 240/480/800/1080/1200/1500 全 200），
     * 所以直接要 1200，失败再退 480（Lyrico 官方插件用的就是 480）。
     *
     * **不改 scheme**：桌面端没有 cleartext 限制，http 反而更快（少一次 TLS 握手）。
     * 强制 https 在部分 CDN 上会直接失败。
     */
    private fun expandSize(url: String): List<String> =
        if (url.contains("{size}")) {
            listOf(url.replace("{size}", "1200"), url.replace("{size}", "480"))
        } else {
            listOf(url)
        }

    /** 详情接口：`r=play/getdata&hash=...`，取 `data.img`。 */
    private suspend fun fetchDetailImage(hash: String): String {
        if (hash.isBlank()) return ""
        val response =
            Http.get(
                url = "https://wwwapi.kugou.com/yy/index.php",
                query = mapOf("r" to "play/getdata", "hash" to hash),
                timeoutMs = 7000,
            )
        if (!response.ok) return ""
        return try {
            Json
                .parse(response.body)
                .obj("data")
                ?.str("img")
                .orEmpty()
        } catch (t: Throwable) {
            Diag.log("kugou: 详情解析失败 ${t.javaClass.simpleName}")
            ""
        }
    }

    override suspend fun lyrics(candidate: SourceCandidate): String? {
        val search =
            signedUrl(
                base = "https://lyrics.kugou.com/v1/search",
                params =
                    linkedMapOf(
                        "album_audio_id" to "",
                        "duration" to candidate.durationMs.toString(),
                        "hash" to candidate.remoteId,
                        "keyword" to candidate.display,
                        "lrctxt" to "1",
                        "man" to "no",
                    ),
            )
        val searchResponse = Http.get(search, timeoutMs = 7000)
        if (!searchResponse.ok) {
            Diag.log("kugou: 歌词搜索失败 ${searchResponse.error}")
            return null
        }
        val candidateObj =
            try {
                Json.parse(searchResponse.body).arr("candidates").firstOrNull()
            } catch (t: Throwable) {
                Diag.log("kugou: 歌词搜索解析失败 ${t.javaClass.simpleName}")
                null
            } ?: return null

        val id = candidateObj.str("id").orEmpty()
        val accesskey = candidateObj.str("accesskey").orEmpty()
        if (id.isBlank() || accesskey.isBlank()) return null

        // 先要 krc（逐字），拿不到再退回 lrc（逐行）。
        // krc 是「去掉前 4 字节 → 按 KRC_KEY 异或 → zlib 解压」的二进制，
        // 解密链在本文件的 decodeKrc 里。
        val krcText = downloadLyric(id, accesskey, "krc")?.let { decodeKrc(it) }
        if (!krcText.isNullOrBlank() && LyricsFormat.detect(krcText) == LyricsFormat.Format.KRC) {
            // **必须转成结构化歌词再返回**，与 QQ 的 qrcToStructured 同一处理：
            // KRC 带 `[id:]/[ti:]/[language:]` 等文本头标签，其中 `[language:]`
            // 是数 KB 的 Base64(JSON)，原样交给界面会显示成一大段乱码；
            // 转换时顺带把里面的译文与罗马音并进同时间戳轨道。
            val structured =
                runCatching { LyricsFormat.krcToStructured(krcText) }
                    .getOrElse { "" }
            if (structured.isNotBlank()) {
                Diag.log("kugou: krc ${krcText.length} 字 -> 结构化 ${structured.length} 字")
                return structured
            }
            Diag.log("kugou: krc 转结构化失败，退回已剥头标签的原文")
            return LyricsFormat.stripHeaderTags(krcText)
        }
        val lrcText = downloadLyric(id, accesskey, "lrc")?.let { it }
        Diag.log("kugou: 回退逐行 lrc ${lrcText?.length ?: 0} 字")
        return lrcText
    }

    /** 下载某个格式的歌词：返回 content 字段的原始字符串。 */
    private suspend fun downloadLyric(
        id: String,
        accesskey: String,
        fmt: String,
    ): String? {
        val download =
            signedUrl(
                base = "https://lyrics.kugou.com/download",
                params =
                    linkedMapOf(
                        "accesskey" to accesskey,
                        "charset" to "utf8",
                        "client" to "mobi",
                        "fmt" to fmt,
                        "id" to id,
                        "ver" to "1",
                    ),
            )
        val response = Http.get(download, timeoutMs = 7000)
        if (!response.ok) {
            Diag.log("kugou: $fmt 歌词下载失败 ${response.error}")
            return null
        }
        return try {
            Json.parse(response.body).str("content")?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            Diag.log("kugou: $fmt 歌词下载解析失败 ${t.javaClass.simpleName}")
            null
        }
    }

    /**
     * 解码 KRC：`去掉前 4 字节 → 与 KRC_KEY 逐字节异或 → zlib 解压 → UTF-8`。
     *
     * 与参考项目 `decryptKrc` 完全一致。任何一步失败都返回 null，
     * 上层会回退到 lrc。
     */
    private fun decodeKrc(content: String): String? =
        runCatching {
            val raw = java.util.Base64
                .getDecoder()
                .decode(content)
            if (raw.size <= 4) return@runCatching null
            val body = raw.copyOfRange(4, raw.size)
            val out = ByteArray(body.size)
            for (i in body.indices) {
                out[i] = (body[i].toInt() xor KRC_KEY[i % KRC_KEY.size]).toByte()
            }
            java.io.ByteArrayInputStream(out).use { input ->
                java.util.zip.InflaterInputStream(input).use { inflater ->
                    inflater.readBytes().toString(Charsets.UTF_8)
                }
            }
        }.getOrElse { t ->
            Diag.log("kugou: krc 解码失败 ${t.javaClass.simpleName}: ${t.message}")
            null
        }

    /**
     * 拼签名 URL。
     *
     * `signature = md5(SALT + 按键排序的 k=v 串 + SALT)`，参数再整体 URL 编码。
     */
    private fun signedUrl(
        base: String,
        params: Map<String, String>,
    ): String {
        val all = linkedMapOf("appid" to "3116", "clientver" to "11070")
        all.putAll(params)
        val raw = all.keys.sorted().joinToString("") { k -> "$k=${all[k]}" }
        all["signature"] = md5(SALT + raw + SALT)
        val query =
            all.keys.sorted().joinToString("&") { k ->
                "${enc(k)}=${enc(all[k].orEmpty())}"
            }
        return "$base?$query"
    }

    private fun md5(text: String): String =
        MessageDigest
            .getInstance("MD5")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** 剥掉搜索结果里的 `<em>` 之类的 HTML 标签。 */
    private fun clean(value: String): String = value.replace(Regex("<[^>]+>"), "").trim()

    private fun looksLikeImage(b: ByteArray): Boolean {
        if (b.size < 12) return false
        val jpeg = b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()
        val png = b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte()
        val webp = b[0] == 'R'.code.toByte() && b[8] == 'W'.code.toByte()
        return jpeg || png || webp
    }

    private companion object {
        const val SALT = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"

        /**
         * KRC 解码异或键（16 字节，与参考项目 `01_http_sign.js` 一致）。
         *
         * 存成 IntArray 再在解码时转 Byte：`byteArrayOf` 里直接写 >127 的数会超范围。
         */
        val KRC_KEY = intArrayOf(64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105)
    }
}
