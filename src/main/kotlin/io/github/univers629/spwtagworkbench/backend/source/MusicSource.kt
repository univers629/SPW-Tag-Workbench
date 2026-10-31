// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.source

import io.github.univers629.spwtagworkbench.backend.match.Matching
import io.github.univers629.spwtagworkbench.backend.model.TagPatch

/** 音源能力。只有声明了对应能力的源才会被请求。 */
enum class Capability {
    /** 元数据：标题 / 艺术家 / 专辑 / 时长 */
    META,

    /** 专辑封面（ID3v2 type=3）。**不含**艺术家图 */
    COVER,

    /** 艺术家图（ID3v2 type=8）。与 [COVER] 是两个不同端点，必须分开声明。 */
    ARTIST_COVER,

    /** 歌词 */
    LYRICAL,

    /**
     * 逐字歌词（卡拉OK逐字时间轴）。
     *
     * 与 [LYRICAL] 分开声明：能取到歌词 ≠ 能取到逐字。实测 QQ（QRC）与酷狗（KRC）
     * 每条记录都给逐字，网易云的 yrc 只在部分记录上有，酷我 / LRCLIB 结构上只有逐行。
     * 声明了它的源才会被粒度补取纳入；未声明的源补取只能得到逐行。
     */
    WORD_SYNCED,
}

/** 搜索输入。 */
data class SearchQuery(
    val title: String,
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    /**
     * 覆盖默认关键词。
     *
     * 默认关键词按「专辑 + 艺人」构造，用于检索专辑封面；
     * 艺术家图只归属艺人，检索时必须只按艺人名，由调用方显式传入。
     */
    val keywordOverride: String? = null,
) {
    /**
     * 检索关键词。
     *
     * 对齐 MusicTag 的做法：有专辑就用「专辑 + 艺人」，没有专辑才退回「标题 + 艺人」。
     * 按专辑检索结果更多且相关度更高——同一张专辑的封面天然就是目标。
     */
    val keyword: String
        get() {
            val override = keywordOverride?.trim()
            if (!override.isNullOrEmpty()) return override
            val builder = StringBuilder()
            if (album.isNotBlank()) {
                builder.append(album)
                if (artist.isNotBlank()) builder.append(' ').append(artist)
            } else {
                builder.append(title)
                if (artist.isNotBlank()) builder.append(' ').append(artist)
            }
            return builder.toString().trim()
        }
}

/** 一个源返回的候选。 */
data class SourceCandidate(
    val sourceId: String,
    val sourceLabel: String,
    /** 源内 ID（封面可能从 A 源取、歌词从 B 源取，所以必须保留） */
    val remoteId: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L,
    /** 该候选是否带歌词 */
    val hasLyrics: Boolean = false,
    /** 该候选是否带封面（或可取封面） */
    val hasCover: Boolean = false,
    /** 歌词格式标注：lrc / spl / text / word */
    val lyricFormat: String = "",
    /** 封面地址（有 COVER 能力的源才填） */
    val artworkUrl: String = "",
    /**
     * 艺术家图地址。
     *
     * 与 [artworkUrl]（专辑封面）分开：ID3v2 里它们是不同类型的图片
     * （3 = Front Cover，8 = Artist）。QQ 和酷狗都能给到 1200px 以上的艺人图。
     */
    val artistArtworkUrl: String = "",
    /**
     * 源内的**艺术家 ID**。
     *
     * 有些源的搜索响应里没有艺人图地址（网易云的 `ar[]` 只给 id 和 name），
     * 要另外打一次艺术家详情端点才能拿到。这里保留 id，让 [MusicSource.artistCover]
     * 在**真正要下载时**才去解析 —— 封面页不需要艺人图，不该为此付这次往返。
     */
    val artistId: String = "",
    /** 发行年份，0 表示未知。iTunes 给日期串、QQ 给 Unix 时间戳、酷狗给 `yyyy-MM-dd`。 */
    val year: Int = 0,
    /** 风格 / 流派，空表示未知（目前只有 iTunes 给）。 */
    val genre: String = "",
    /** 专辑内音轨号，0 表示未知（只有 iTunes 给）。 */
    val track: Int = 0,
    /** 碟号，0 表示未知（只有 iTunes 给）。 */
    val disc: Int = 0,
    /**
     * 专辑艺术家。
     *
     * **这些接口都不直接提供这个字段**。只在「该候选只有一个艺术家」时
     * 才用艺术家名推断，多艺术家时留空，不猜。
     */
    val albumArtist: String = "",
    /**
     * 第二标识：某些源需要**两个 ID** 才能取到全部内容。
     *
     * QQ 是典型：逐行 LRC 走 `fcg_query_lyric_new.fcg` 只认 `songmid`（字母串，即 [remoteId]），
     * 而逐字 QRC 走 `musicu.fcg GetPlayLyricInfo` 只认数字 `songID`。
     * 只存一个会让另一半接口始终失败，QQ 歌词只能取到逐行。
     */
    val altId: String = "",
    /**
     * 源侧提供的回放增益（dB）。
     *
     * 目前只有 QQ 给：搜索结果与歌曲详情里都带 `volume.gain`，无需本地测量即可写入标签。
     */
    val gainDb: Float? = null,
    /** 源侧提供的线性峰值（`volume.peak`）。 */
    val gainPeak: Float? = null,
    /** 源侧提供的响度范围（`volume.lra`，单位 LU）。 */
    val gainRange: Float? = null,
) {
    val display: String get() = if (artist.isBlank()) title else "$title — $artist"
}

/** 源搜索结果：成功候选 + 失败原因（失败隔离用）。 */
data class SourceResult(
    val sourceId: String,
    val sourceLabel: String,
    val candidates: List<SourceCandidate> = emptyList(),
    /** 非空表示这个源失败了，其它源不受影响 */
    val failure: String = "",
) {
    val ok: Boolean get() = failure.isEmpty()
}

/**
 * 音源统一契约。
 *
 * 各源自己实现；能力声明是必须的 —— 只有歌词能力的源不能出现在封面候选里。
 */
interface MusicSource {
    val id: String

    val label: String

    val capabilities: Set<Capability>

    /** 源权重，越大越优先（同匹配等级时用于排序）。 */
    val weight: Int get() = 0

    fun supports(capability: Capability): Boolean = capability in capabilities

    /** 搜索候选。实现应自己吞掉网络异常并转成 [SourceResult.failure]。 */
    suspend fun search(query: SearchQuery): SourceResult

    /** 取封面字节。不支持封面时返回 null。 */
    suspend fun cover(candidate: SourceCandidate): ByteArray? = null

    /**
     * 取**艺术家图**字节。
     *
     * 与 [cover]（专辑封面）是**两套完全不同的端点**，不要互相复用：
     * - 专辑封面：QQ `T002…`、酷我 `/albumcover/…`、iTunes artwork
     * - 艺术家图：QQ `T001…`、酷狗 `softhead/…`、网易云 `artist.img1v1Url`
     *
     * 不支持艺术家图的源返回 null，界面显示「该源无艺术家图」。
     */
    suspend fun artistCover(candidate: SourceCandidate): ByteArray? = null

    /** 取歌词文本。不支持歌词时返回 null。 */
    suspend fun lyrics(candidate: SourceCandidate): String? = null
}

/**
 * 匹配判定：标题四级 + 艺术家 + 时长。
 *
 * 返回 1..4（越小越可信），0 表示不匹配。
 */
object CandidateScoring {
    fun level(
        query: SearchQuery,
        candidate: SourceCandidate,
    ): Int {
        val titleLevel = Matching.titleLevel(query.title, candidate.title)
        if (titleLevel == 0) return 0
        if (!artistCompatible(query.artist, candidate.artist)) return 0
        if (!Matching.durationMatches(query.durationMs, candidate.durationMs)) return 0
        return titleLevel
    }

    /**
     * 结果里出现次数最多的专辑名 —— **源把「按专辑检索」解析成了哪张专辑**。
     *
     * 仅用于封面检索：检索按专辑发出时，源返回的是它匹配到的那张专辑，
     * 但**专辑名与曲名都可能被本地化**，无法与本地标签做字符串比对 ——
     * iTunes 会把「君の名は。 / 前前前世」返回成「Your Name. / Dream Lantern」，
     * 标题与专辑名均无法匹配，候选被判为不相关。
     *
     * 取众数就不依赖任何字符串相等：占比最高的那张专辑就是源的理解。
     * 结果分散在多张专辑上时（按曲名检索）众数很低，调用方应改用标题判定。
     */
    fun dominantAlbum(candidates: List<SourceCandidate>): String? =
        candidates
            .mapNotNull { it.album.trim().takeIf(String::isNotEmpty) }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key

    /** 艺术家兼容：任一为空视为兼容；否则要求互相包含或相似度够高。 */
    fun artistCompatible(
        a: String,
        b: String,
    ): Boolean {
        if (a.isBlank() || b.isBlank()) return true
        val na = normalizeArtist(a)
        val nb = normalizeArtist(b)
        if (na == nb) return true
        if (na.contains(nb) || nb.contains(na)) return true
        return Matching.similarity(na, nb) >= 0.62
    }

    private fun normalizeArtist(value: String): String =
        Matching
            .toHalfWidth(value)
            .lowercase()
            .replace("feat.", " ")
            .replace("&", " ")
            .replace("/", " ")
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ")

    /** 候选能否用于该曲目（伴奏跳过由调用方用 Matching.isMatchCandidate 判断）。 */
    fun viable(
        query: SearchQuery,
        candidate: SourceCandidate,
    ): Boolean = level(query, candidate) > 0

    /**
     * 两条候选是不是**同一条录音**：同曲名 + 艺人兼容 + 时长接近。
     *
     * 用于跨源去重（同曲被多个源命中）与「同一源里同一首歌的多条记录」的识别。
     *
     * 注意：同一条录音的**歌词粒度可能不同**——实测网易云同一首歌有两条
     * 同名同艺人、时长同为 258834ms 的记录，一条只有 LRC、另一条带 yrc 逐字。
     * 因此它只能用于识别同一条录音，不能用于排除重复记录。
     */
    fun sameRecording(
        a: SourceCandidate,
        b: SourceCandidate,
    ): Boolean {
        if (Matching.normalizeTitle(a.title) != Matching.normalizeTitle(b.title)) return false
        if (!artistCompatible(a.artist, b.artist)) return false
        return Matching.durationMatches(a.durationMs, b.durationMs)
    }
}

/**
 * 候选里能写进标签的字段集合（按字段合并用）。
 *
 * **按字段合并，不是按记录**：某个源只给了年份、另一个源只给了风格，
 * 两者都会进同一份 patch，不会互相覆盖。
 */
fun SourceCandidate.toPatch(): TagPatch =
    TagPatch().apply {
        if (title.isNotBlank()) set(io.github.univers629.spwtagworkbench.backend.model.TagField.Title, title)
        if (artist.isNotBlank()) set(io.github.univers629.spwtagworkbench.backend.model.TagField.Artist, artist)
        if (album.isNotBlank()) set(io.github.univers629.spwtagworkbench.backend.model.TagField.Album, album)
        if (albumArtist.isNotBlank()) {
            set(io.github.univers629.spwtagworkbench.backend.model.TagField.AlbumArtist, albumArtist)
        }
        if (genre.isNotBlank()) set(io.github.univers629.spwtagworkbench.backend.model.TagField.Genre, genre)
        if (year > 0) set(io.github.univers629.spwtagworkbench.backend.model.TagField.Year, year.toString())
        if (track > 0) set(io.github.univers629.spwtagworkbench.backend.model.TagField.Track, track.toString())
        if (disc > 0) set(io.github.univers629.spwtagworkbench.backend.model.TagField.Disc, disc.toString())
    }
