// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.source

import io.github.univers629.spwtagworkbench.backend.lyrics.LyricsFormat
import io.github.univers629.spwtagworkbench.backend.net.Http
import io.github.univers629.spwtagworkbench.backend.net.Json
import io.github.univers629.spwtagworkbench.core.Diag
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 网易云音乐（公开检索接口）。
 *
 * 能力：歌词 + 元数据 + 封面。封面直接取搜索响应里的 `album.picUrl`，
 * 并**去掉尺寸参数**——带 `?param=130y130` 会被锁在缩略图分辨率上。
 */
class NeteaseSource : MusicSource {
    override val id: String = "netease"

    override val label: String = "网易云"

    override val capabilities: Set<Capability> =
        setOf(Capability.LYRICAL, Capability.WORD_SYNCED, Capability.META, Capability.COVER, Capability.ARTIST_COVER)

    override val weight: Int = 25

    private val headers =
        mapOf(
            "Referer" to "https://music.163.com/",
            "Cookie" to "appver=2.0.2",
        )

    override suspend fun search(query: SearchQuery): SourceResult {
        val keyword =
            query.keyword
        if (keyword.isEmpty()) return SourceResult(id, label, failure = "标题为空")

        // 必须用 cloudsearch/pc（POST 表单）：
        // 老的 /api/search/get/web 返回的 album 里只有 picId、**没有 picUrl**，
        // 封面取不到。新接口读的是 al.picUrl。
        val form = "s=" + enc(keyword) + "&type=1&offset=0&limit=30"
        val response =
            Http.postForm(
                url = "https://music.163.com/api/cloudsearch/pc",
                body = form,
                headers = headers + mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                timeoutMs = 8000,
            )
        if (!response.ok) {
            Diag.log("netease: 搜索失败 ${response.error} status=${response.status}")
            return SourceResult(id, label, failure = response.error.ifBlank { "HTTP ${response.status}" })
        }
        val parsed =
            try {
                Json.parse(response.body)
            } catch (t: Throwable) {
                Diag.log("netease: 响应解析失败 ${t.javaClass.simpleName}")
                return SourceResult(id, label, failure = "响应格式异常")
            }
        val songs = parsed.obj("result")?.arr("songs") ?: emptyList()
        val candidates =
            songs.mapNotNull { song ->
                val title = song.str("name") ?: return@mapNotNull null
                val id = song.num("id")?.toLong()?.toString() ?: return@mapNotNull null
                // 新接口用 ar / al，老接口用 artists / album，两者都兼容
                val singerObj = song.arr("ar").firstOrNull() ?: song.arr("artists").firstOrNull()
                val artist = singerObj?.str("name").orEmpty()
                val albumObj = song.obj("al") ?: song.obj("album")
                val album = albumObj?.str("name").orEmpty()
                val durationMs = song.num("dt")?.toLong() ?: song.num("duration")?.toLong() ?: 0L
                val artwork = coverUrl(albumObj?.str("picUrl"))
                // 艺术家图**不在搜索响应里**：新版 cloudsearch 的 ar[] 只有
                // name / tns / alias / id / alia 五个键，没有任何图片字段。
                // 只保留 artistId，等真正要下载时再打艺术家详情端点（见 artistCover）。
                val artistId = singerObj
                    ?.num("id")
                    ?.toLong()
                    ?.toLong()
                    .toString()
                    .orEmpty()
                SourceCandidate(
                    sourceId = this.id,
                    sourceLabel = label,
                    remoteId = id,
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = durationMs,
                    hasLyrics = true,
                    hasCover = artwork.isNotBlank(),
                    // 优先取 yrc（逐字）；取不到会退回 lrc，界面标记随后由内容校正
                    lyricFormat = "yrc",
                    artworkUrl = artwork,
                    artistId = artistId,
                )
            }
        Diag.log(
            "netease: 搜索「$keyword」返回 ${candidates.size} 条，" +
                "带封面 ${candidates.count { it.hasCover }} 条",
        )
        return SourceResult(id, label, candidates = candidates)
    }

    private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    /**
     * 封面地址规整：去掉 `?param=130y130` 这类尺寸参数，否则永远拿缩略图。
     *
     * **不改 scheme**。Lyrico 插件强制 https 是因为 Android 默认禁明文流量（cleartext），
     * 桌面 JVM 没有这个限制；实测 http 反而快 70~100ms（省 TLS 握手），
     * 而酷我的 CDN（`img1.kwcdn.kuwo.cn`）**不支持 https**，强改会导致全部请求失败。
     */
    private fun coverUrl(raw: String?): String {
        val url = raw?.trim().orEmpty()
        if (url.isEmpty()) return ""
        val cut = url.indexOf('?')
        return if (cut > 0) url.substring(0, cut) else url
    }

    override suspend fun cover(candidate: SourceCandidate): ByteArray? {
        val url = candidate.artworkUrl
        if (url.isBlank()) return null
        val bytes = Http.getBytes(url, timeoutMs = 9000)
        if (bytes == null) {
            Diag.log("netease: 取封面失败（无响应）")
            return null
        }
        if (!looksLikeImage(bytes)) {
            Diag.log("netease: 封面响应不是图片，已丢弃（${bytes.size} 字节）")
            return null
        }
        return bytes
    }

    /**
     * 艺术家图。
     *
     * 搜索响应里**没有**艺人图（`ar[]` 只有 name / tns / alias / id / alia），
     * 要拿 [SourceCandidate.artistId] 打一次艺术家详情端点。
     *
     * 这次往返只在**真正要下载**时发生，封面页不受影响；
     * 结果按艺术家 id 缓存 —— 同一艺人的多首歌共用一张图，第二次是纯内存命中。
     */
    override suspend fun artistCover(candidate: SourceCandidate): ByteArray? {
        val url =
            candidate.artistArtworkUrl.ifBlank {
                resolveArtistImage(candidate.artistId) ?: return null
            }
        val bytes =
            Http.getBytes(url, timeoutMs = 9000)
                ?: return null
        return bytes.takeIf { looksLikeImage(it) }
    }

    /**
     * 用艺术家 id 换艺人图地址，带缓存。
     *
     * 端点选 `/api/artist/{id}`：`/api/v1/artist/{id}` 内容相同但响应更大，
     * 而 `/api/artist/head/info/get` 只有 4KB 却**没有图片字段**，用不了。
     *
     * **同一个 artistId 的并发请求共用一次往返**：艺人图页会为同一艺人的多首歌
     * 并发下载，只查缓存会全部落空（缓存踩踏）—— 实测 5 个候选发了 5 次相同请求。
     * 这里用 per-id 互斥锁 + 锁内二次检查：后到的调用直接命中缓存。
     */
    private suspend fun resolveArtistImage(artistId: String): String? {
        if (artistId.isBlank()) return null
        cachedArtistImage(artistId)?.let { return it.ifBlank { null } }

        val mutex = artistImageLocks.computeIfAbsent(artistId) { Mutex() }
        return try {
            mutex.withLock {
                cachedArtistImage(artistId)?.let { return@withLock it.ifBlank { null } }

                val response =
                    runCatching {
                        Http.get(
                            url = "https://music.163.com/api/artist/$artistId",
                            headers = headers,
                            timeoutMs = 9000,
                        )
                    }.getOrNull()
                val url =
                    response
                        ?.takeIf { it.ok }
                        ?.let { Json.parse(it.body)?.obj("artist") }
                        ?.let { coverUrl(it.str("img1v1Url")) ?: coverUrl(it.str("picUrl")) }
                        .orEmpty()

                putArtistImage(artistId, url)
                Diag.log("netease: 艺术家 $artistId 图片=${if (url.isBlank()) "无" else url.takeLast(28)}")
                url.ifBlank { null }
            }
        } finally {
            // 锁只在「有并发」这一小段时间内需要：请求结束就移除，
            // 否则每个出现过的 artistId 都会永久留下一个 Mutex 对象。
            // 用 remove(key, value) 原子移除，避免误删并发场景下重建的新实例；
            // 等待中的调用方已持有自己的引用，移除不影响它们继续加锁。
            artistImageLocks.remove(artistId, mutex)
        }
    }

    /** 读艺人图缓存（有界 LRU，需同步访问）。 */
    private fun cachedArtistImage(artistId: String): String? = synchronized(artistImageCache) { artistImageCache[artistId] }

    /** 写艺人图缓存（有界 LRU，需同步访问）。 */
    private fun putArtistImage(
        artistId: String,
        url: String,
    ) {
        synchronized(artistImageCache) { artistImageCache[artistId] = url }
    }

    /** 艺人图缓存的条目上限。单条只是一个 URL 字符串，512 条约几十 KB。 */
    private val MAX_ARTIST_IMAGE_ENTRIES = 512

    /**
     * 艺术家 id → 艺人图地址；空串表示查过但没有。
     *
     * **必须有上限**：条目随「浏览过多少艺人」增长，而本对象是常驻单例、
     * 插件停用也不会重建 —— 无界时条目会跨会话一直累积。
     * 用访问序 LRU：常用艺人留在表内，冷门条目被挤出。
     *
     * [LinkedHashMap] 非线程安全，所有访问都包在 `synchronized(artistImageCache)` 里；
     * 不持有该锁跨越挂起点（网络请求与 `withLock` 都在锁外）。
     */
    private val artistImageCache =
        object : LinkedHashMap<String, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > MAX_ARTIST_IMAGE_ENTRIES
        }

    /**
     * 艺术家 id → 该 id 的查询锁，保证同一 id 只发一次请求。
     *
     * 仅在请求进行期间存在：请求结束即在 [resolveArtistImage] 的 `finally` 中移除，
     * 因此表的大小受「并发中的艺人数量」约束，不随历史累计。
     */
    private val artistImageLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    /** 简单嗅探：非图片响应（错误页、占位图）不要写进文件。 */
    private fun looksLikeImage(b: ByteArray): Boolean {
        if (b.size < 12) return false
        val jpeg = b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()
        val png = b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte()
        val webp = b[0] == 'R'.code.toByte() && b[8] == 'W'.code.toByte()
        return jpeg || png || webp
    }

    override suspend fun lyrics(candidate: SourceCandidate): String? {
        val response =
            Http.get(
                url = "https://music.163.com/api/song/lyric",
                // **`yv=-1` 是逐字（yrc）的开关**：只传 lv/kv/tv 时响应里没有 yrc 字段，
                // 于是永远回落到逐行 LRC。`rv`/`yrv` 用于带出 romalrc（罗马音）。
                query =
                    mapOf(
                        "id" to candidate.remoteId,
                        "lv" to "-1",
                        "kv" to "-1",
                        "tv" to "-1",
                        "rv" to "-1",
                        "yv" to "-1",
                        "ytv" to "-1",
                        "yrv" to "-1",
                    ),
                headers = headers,
                timeoutMs = 7000,
            )
        if (!response.ok) {
            Diag.log("netease: 取歌词失败 ${response.error}")
            return null
        }
        val parsed =
            try {
                Json.parse(response.body)
            } catch (t: Throwable) {
                Diag.log("netease: 歌词响应解析失败 ${t.javaClass.simpleName}")
                return null
            }
        // 一次请求会带回多份：yrc 逐字、klyric 逐字旧版、lrc 逐行、tlyric 译文、romalrc 罗马音。
        val yrc = parsed.obj("yrc")?.str("lyric")?.takeIf { it.isNotBlank() }
        val klyric = parsed.obj("klyric")?.str("lyric")?.takeIf { it.isNotBlank() }
        val lrc = parsed.obj("lrc")?.str("lyric")?.takeIf { it.isNotBlank() }
        val tlyric = parsed.obj("tlyric")?.str("lyric")?.takeIf { it.isNotBlank() }
        val romalrc = parsed.obj("romalrc")?.str("lyric")?.takeIf { it.isNotBlank() }
        Diag.log(
            "netease: 歌词字段 yrc=${yrc?.length ?: 0} klyric=${klyric?.length ?: 0} " +
                "lrc=${lrc?.length ?: 0} tlyric=${tlyric?.length ?: 0} romalrc=${romalrc?.length ?: 0}",
        )
        if (yrc != null) {
            // 与酷狗 KRC 同一处理：机器格式先转成结构化歌词，再顺带并入译文与罗马音
            val structured =
                runCatching { LyricsFormat.yrcToStructured(yrc, tlyric, romalrc) }
                    .getOrElse { "" }
            if (structured.isNotBlank()) {
                Diag.log("netease: yrc ${yrc.length} 字 -> 结构化 ${structured.length} 字")
                return structured
            }
            Diag.log("netease: yrc 转结构化失败，退回已剥头标签的原文")
            return LyricsFormat.stripHeaderTags(yrc)
        }
        if (klyric != null) {
            Diag.log("netease: 取到逐字 klyric ${klyric.length} 字")
            return LyricsFormat.stripHeaderTags(klyric)
        }
        return lrc?.let { LyricsFormat.mergeLrcTracks(it, tlyric, romalrc) }
    }
}
