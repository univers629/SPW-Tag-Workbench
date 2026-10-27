// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.data

import io.github.univers629.spwtagworkbench.backend.model.TagField
import io.github.univers629.spwtagworkbench.backend.model.TagPatch
import io.github.univers629.spwtagworkbench.backend.model.Track
import io.github.univers629.spwtagworkbench.backend.read.CoverMime
import io.github.univers629.spwtagworkbench.backend.read.CoverStore
import io.github.univers629.spwtagworkbench.backend.read.TrackReader
import io.github.univers629.spwtagworkbench.backend.write.AudioTagWriter
import io.github.univers629.spwtagworkbench.core.i18n.L10n
import java.io.File

/**
 * UI 面向标签读写的门面。
 *
 * **真正的写盘全部走 [AudioTagWriter]**：
 * 释放播放 → 复制到临时文件 → 写 → 回读校验 → 先删后改名 → 还原属性。
 * 这里只负责「字段枚举映射」和「路径 → 统一模型」的转换。
 */
object Tags {
    /**
     * UI 侧的可编辑字段，与后端 [TagField] 一一对应。
     *
     * **顺序即界面顺序**，沿用 legacy 版（Swing）元数据区的排列：
     * 标题 / 艺术家 / 专辑 / 年份 / 音轨号 / 风格 / 专辑艺术家 / 作曲家 / 作词家 / 注释。
     * 音轨号与碟号在界面上共用一行，但这里仍是两个独立字段。
     *
     * 标签文字也与 legacy 对齐（「风格」而非「流派」、「作词家」而非「作词」）。
     */
    enum class Key(
        val label: String,
        val field: TagField,
    ) {
        Title("标题", TagField.Title),
        Artist("艺术家", TagField.Artist),
        Album("专辑", TagField.Album),
        Year("年份", TagField.Year),
        Track("音轨号", TagField.Track),
        Disc("碟号", TagField.Disc),
        Genre("风格", TagField.Genre),
        AlbumArtist("专辑艺术家", TagField.AlbumArtist),
        Composer("作曲家", TagField.Composer),
        Lyricist("作词家", TagField.Lyricist),
        Comment("注释", TagField.Comment),

        // ReplayGain 2.0 的四个自由键：写入走 TXXX / 注释键，不是标准文本帧。
        TrackGain("音轨增益", TagField.ReplayGainTrackGain),
        TrackPeak("音轨峰值", TagField.ReplayGainTrackPeak),
        AlbumGain("专辑增益", TagField.ReplayGainAlbumGain),
        AlbumPeak("专辑峰值", TagField.ReplayGainAlbumPeak),
        ;

        companion object {
            /** 「套用到同专辑」写入的字段：只套用专辑级信息，不动单曲独有的字段。 */
            val ALBUM_LEVEL: List<Key> = listOf(Album, AlbumArtist, Genre, Year)
        }
    }

    data class Result(
        val ok: Boolean,
        val message: String,
    )

    // ── 读 ──────────────────────────────────────────────────────────────────

    /** 从文件读完整标签：这是权威来源，宿主数据库只是缓存。 */
    fun read(path: String): Track? = TrackReader.readFile(path)

    fun readLyrics(path: String): String? = TrackReader.readLyrics(path)

    fun readEmbeddedCover(path: String): ByteArray? {
        if (path.isBlank()) return null
        return CoverStore.loadFromFile(path, path)
    }

    /** 文件探针结果：只回答「有没有」，不搬字节。 */
    data class Probe(
        val hasLyrics: Boolean,
        val hasCover: Boolean,
    )

    /**
     * 轻量探测：文件里是否已有歌词与封面。
     *
     * 批量匹配的「跳过完整标签」判定只需要这两个布尔量，不该为此把
     * 封面字节和整段歌词读进内存——几百首下来就是几十 MB。
     */
    fun probe(path: String): Probe {
        if (path.isBlank()) return Probe(false, false)
        val lyrics = runCatching { TrackReader.readLyrics(path) }.getOrNull()
        val cover =
            runCatching {
                // 只查是否存在，不保留字节
                CoverStore.loadFromFile(path, path)
            }.getOrNull()
        return Probe(
            hasLyrics = !lyrics.isNullOrBlank(),
            hasCover = cover != null && cover.isNotEmpty(),
        )
    }

    // ── 写 ──────────────────────────────────────────────────────────────────

    fun writeField(
        path: String,
        key: Key,
        value: String,
        reloadLyricsForId: String? = null,
    ): Result {
        val track = ensureTrack(path) ?: return Result(false, L10n.t("err.fileUnreadable"))
        val outcome = AudioTagWriter.writeField(track, key.field, value, reloadLyricsForId)
        return Result(outcome.ok, decorate(outcome.message, ok = outcome.ok, refreshed = outcome.hostRefreshed))
    }

    fun writeLyrics(
        path: String,
        lrcText: String,
        reloadLyricsForId: String? = null,
    ): Result {
        val track = ensureTrack(path) ?: return Result(false, L10n.t("err.fileUnreadable"))
        val outcome =
            AudioTagWriter.write(
                track = track,
                patch = TagPatch.single(TagField.Lyrics, lrcText),
                reloadLyricsForId = reloadLyricsForId,
            )
        return Result(outcome.ok, decorate(outcome.message, ok = outcome.ok, refreshed = outcome.hostRefreshed))
    }

    fun writeCover(
        path: String,
        bytes: ByteArray,
    ): Result {
        val track = ensureTrack(path) ?: return Result(false, L10n.t("err.fileUnreadable"))
        val cover = CoverMime.asCover(bytes) ?: return Result(false, L10n.t("err.coverEmpty"))
        val outcome = AudioTagWriter.writeCover(track, cover)
        return Result(outcome.ok, decorate(outcome.message, ok = outcome.ok, refreshed = outcome.hostRefreshed))
    }

    /**
     * 写入**艺术家图**：`type = 8`（Artist）。
     *
     * 与 [writeCover]（`type = 3`，Front Cover）是两张不同的图。
     * ID3v2 的 APIC / FLAC 的 PICTURE 允许多张不同类型共存，互不覆盖。
     */
    fun writeArtistCover(
        path: String,
        bytes: ByteArray,
    ): Result {
        val track = ensureTrack(path) ?: return Result(false, L10n.t("err.fileUnreadable"))
        val cover = CoverMime.asCover(bytes) ?: return Result(false, L10n.t("err.artistCoverEmpty"))
        val outcome = AudioTagWriter.writeCover(track, cover, pictureType = 8)
        return Result(outcome.ok, decorate(outcome.message, ok = outcome.ok, refreshed = outcome.hostRefreshed))
    }

    // ── ReplayGain ──────────────────────────────────────────────────────────

    /**
     * 写入 ReplayGain 的若干键（空串表示删除该键）。
     *
     * 键名与取值格式由 ReplayGain 2.0 规范固定，[ReplayGainService] 负责计算与格式化；
     * 这里只做「路径 → 调用写盘」的转发，保持与其它写入方法同一层。
     */
    fun writeReplayGain(
        path: String,
        values: Map<TagField, String>,
        reloadLyricsForId: String? = null,
    ): Result {
        val track = ensureTrack(path) ?: return Result(false, L10n.t("err.fileUnreadable"))
        if (values.isEmpty()) return Result(true, L10n.t("err.noGainData"))
        val patch = TagPatch()
        for ((field, value) in values) {
            require(field.isReplayGain) { L10n.t("err.notGainField", field) }
            patch.set(field, value)
        }
        val outcome = AudioTagWriter.write(track, patch, reloadLyricsForId = reloadLyricsForId)
        return Result(outcome.ok, decorate(outcome.message, ok = outcome.ok, refreshed = outcome.hostRefreshed))
    }

    /** 删除指定序号的内嵌图片；信息页用来清理非法图片。 */
    fun removePicture(
        path: String,
        index: Int,
        expectedType: Int = -1,
        expectedSize: Int = -1,
    ): Result {
        val outcome = AudioTagWriter.removePicture(path, index, expectedType, expectedSize)
        return Result(outcome.ok, decorate(outcome.message, ok = outcome.ok, refreshed = outcome.hostRefreshed))
    }

    /** 清除四个 ReplayGain 键（写空值即删除）。 */
    fun clearReplayGain(path: String): Result {
        val track = ensureTrack(path) ?: return Result(false, L10n.t("err.fileUnreadable"))
        val patch = TagPatch()
        for (field in TagField.replayGainFields) patch.set(field, "")
        val outcome = AudioTagWriter.write(track, patch)
        return Result(outcome.ok, decorate(outcome.message, ok = outcome.ok, refreshed = outcome.hostRefreshed))
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private fun ensureTrack(path: String): Track? {
        if (path.isBlank()) return null
        val file = File(path)
        if (!file.isFile || !file.canRead()) return null
        return Track(id = "", path = path)
    }

    /**
     * 写成功但宿主没回读时补一句提示。
     *
     * **只在成功时补**：失败信息（只读、容器异常…）后面接「宿主刷新未响应」
     * 会被误读为刷新导致失败，日志里会混入两种原因。
     */
    private fun decorate(
        message: String,
        ok: Boolean,
        refreshed: Boolean,
    ): String = if (ok && !refreshed) L10n.t("status.hostRefreshMiss", message) else message
}
