// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.source

import io.github.univers629.spwtagworkbench.backend.net.Http
import io.github.univers629.spwtagworkbench.backend.net.Json
import io.github.univers629.spwtagworkbench.core.Diag

/**
 * iTunes Search API。
 *
 * 公开接口、无需密钥，能同时给出元数据与封面：
 * - 元数据：trackName / artistName / collectionName / trackTimeMillis
 * - 封面：artworkUrl100，把尺寸段换成 1200x1200 拿到大图
 *
 * 能力：META + COVER（没有歌词）。
 *
 * 商店策略：请求**不带** `country`，由 iTunes 按请求方 IP 自动选择。
 * 实测 `country=cn` 恒返回 `resultCount=0`，指定商店整体收益为负，
 * 因此该配置项已移除，这里固定使用自动行为。
 */
class ItunesSource : MusicSource {
    override val id: String = "itunes"

    override val label: String = "iTunes"

    override val capabilities: Set<Capability> = setOf(Capability.META, Capability.COVER)

    override val weight: Int = 20

    private val base = "https://itunes.apple.com/search"

    override suspend fun search(query: SearchQuery): SourceResult {
        val term =
            query.keyword
        if (term.isEmpty()) return SourceResult(id, label, failure = "标题为空")

        val parsed = request(term)
        if (parsed == null) {
            return SourceResult(id, label, failure = "请求失败")
        }

        val items = parsed.arr("results")

        val candidates =
            items.mapNotNull { item ->
                (item as? Json.Obj)?.let(::mapSearchItem)
            }
        Diag.log("itunes: 搜索「$term」返回 ${candidates.size} 条")
        return SourceResult(id, label, candidates = candidates)
    }

    /**
     * 把 iTunes 搜索响应里的**单个 item** 映射成候选。
     *
     * 抽成 internal 纯函数是为了单测：`search()` 需要真发网络请求，而字段映射
     * （genre / track / disc / year / albumArtist）是纯计算，可以直接拿一段
     * 固定的 JSON 断言。返回 null 表示这条 item 不可用（没有 `trackName`，
     * 或者本身不是 JSON 对象）。
     */
    internal fun mapSearchItem(item: Json.Obj): SourceCandidate? {
        val title = item.str("trackName") ?: return null
        val artist = item.str("artistName").orEmpty()
        val album = item.str("collectionName").orEmpty()
        val durationMs = item.num("trackTimeMillis")?.toLong() ?: 0L
        val artwork = item.str("artworkUrl100").orEmpty()
        return SourceCandidate(
            sourceId = id,
            sourceLabel = label,
            remoteId = item.num("trackId")?.toLong()?.toString() ?: "$title|$artist",
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            hasLyrics = false,
            hasCover = artwork.isNotBlank(),
            artworkUrl = bigArtwork(artwork),
            // 下面四个 iTunes 一次响应里就有，零额外请求
            year = parseYear(item.str("releaseDate")),
            genre = item.str("primaryGenreName").orEmpty(),
            track = item.num("trackNumber")?.toInt() ?: 0,
            disc = item.num("discNumber")?.toInt() ?: 0,
            // iTunes 不给专辑艺术家：只在单艺术家时推断，多艺术家不猜
            albumArtist =
                if (artist.isNotBlank() && !artist.contains("&") && !artist.contains('/')) {
                    artist
                } else {
                    ""
                },
        )
    }

    /**
     * 拼一次搜索请求参数。
     *
     * **不带** `country`：iTunes Search API 会按**请求方 IP** 自动选商店。
     *
     * 商店选择曾经是可配置项（`itunes.storefront`），现已移除：实测大陆区
     * `country=cn` 恒返回 `resultCount=0`，其余区靠 IP 自动选择已足够，
     * 多一个设置项只会让用户以为「选了某区能查到更多」。现在固定不传。
     */
    private fun searchParams(term: String): Map<String, String> =
        mapOf(
            "term" to term,
            "media" to "music",
            "entity" to "song",
            "limit" to "50",
        )

    /**
     * 发一次搜索并解析 JSON。
     *
     * 返回 null 表示这一次请求已经失败（网络错误 / 非 2xx / 响应解析失败），
     * 调用方直接按失败处理，不需要再判断 [Http.Response.ok]。
     */
    private fun request(term: String): Json? {
        val response = Http.get(url = base, query = searchParams(term))
        if (!response.ok) {
            Diag.log("itunes: 搜索失败 ${response.error} status=${response.status}")
            return null
        }
        return try {
            Json.parse(response.body)
        } catch (t: Throwable) {
            Diag.log("itunes: 响应解析失败 ${t.javaClass.simpleName}")
            null
        }
    }

    /**
     * iTunes 的 `releaseDate` 是美式日期串，形如 `08/03/2004 12:00:00`。
     *
     * 顺序是 **月/日/年**，不是 ISO，不能直接取前 4 位。
     * 同时兼容 `yyyy-MM-dd` / `yyyy/MM/dd`。
     *
     * internal 是为了让单测能直接喂两种日期串，不必走网络。
     */
    internal fun parseYear(raw: String?): Int {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return 0
        if (text.length >= 5 && text[0].isDigit() && (text[4] == '-' || text[4] == '/')) {
            return text.substring(0, 4).toIntOrNull() ?: 0
        }
        val parts = text.substringBefore(' ').split('/')
        if (parts.size == 3) return parts[2].take(4).toIntOrNull() ?: 0
        return 0
    }

    override suspend fun cover(candidate: SourceCandidate): ByteArray? {
        val url = candidate.artworkUrl
        if (url.isBlank()) return null
        val bytes = Http.getBytes(url)
        if (bytes == null) Diag.log("itunes: 封面下载失败 ${candidate.title}")
        return bytes
    }

    /** artworkUrl100 的尺寸在路径段里，换成 1200x1200 拿大图。 */
    private fun bigArtwork(url: String): String = if (url.isBlank()) "" else url.replace("100x100", "1200x1200")
}
