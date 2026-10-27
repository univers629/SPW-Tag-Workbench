// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.data

import androidx.compose.ui.graphics.ImageBitmap

/**
 * 一首曲目的元数据快照。
 *
 * 编辑区展示的字段比宿主 MediaItem 多：宿主的曲库只带标题/艺术家/专辑/专辑艺术家/
 * 流派/年份，而音轨号、碟号、作词、作曲、注释**只有文件里有**。这些字段在选中曲目时
 * 由 [Repo.refreshFocusedFromDisk] 从文件读出后合并进来。
 */
data class TrackMeta(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val genre: String = "",
    val year: String = "",
    val track: String = "",
    val disc: String = "",
    val lyricist: String = "",
    val composer: String = "",
    val comment: String = "",
    /** ReplayGain 四个标签的原始文本；空串表示文件里没有写。 */
    val replayGainTrackGain: String = "",
    val replayGainTrackPeak: String = "",
    val replayGainAlbumGain: String = "",
    val replayGainAlbumPeak: String = "",
    val path: String = "",
    val durationMs: Long = 0L,
    val readable: Boolean = true,
) {
    val isPresent: Boolean get() = id.isNotEmpty() || path.isNotEmpty()
    val hasFile: Boolean get() = path.isNotEmpty() && readable

    /** 用于列表与详情互相对应的稳定标识。 */
    val key: String get() = id.ifEmpty { path }

    companion object {
        val Empty = TrackMeta()
    }
}

/** 歌词行。 */
data class LyricLine(
    val startMs: Long,
    val endMs: Long = -1L,
    val text: String = "",
    val cells: List<LyricCell> = emptyList(),
)

/** 打轴用的字 / 词单元。 */
data class LyricCell(
    val text: String,
    val startMs: Long = -1L,
    val endMs: Long = -1L,
) {
    val stamped: Boolean get() = startMs >= 0L
}

/** 网络封面候选：只来自在线源，不再区分内嵌/同目录/远程。 */
data class CoverCandidate(
    val id: String,
    val bytes: ByteArray?,
    val image: ImageBitmap?,
    /** 来源标签（「网易云」「QQ音乐」「酷狗音乐」「iTunes」）。 */
    val sourceLabel: String = "",
    /** 原图宽度，0 表示未知。 */
    val width: Int = 0,
    /** 原图高度，0 表示未知。 */
    val height: Int = 0,
    /**
     * 写入标签时用的 ID3v2 / FLAC 图片类型。
     *
     * 标准值见 [io.github.univers629.spwtagworkbench.backend.read.PictureTypeStandard]：
     * 3 = Front Cover（专辑封面），8 = Artist（艺术家），7 = LeadArtist，10 = Band。
     * 在线源拿到的都是**专辑封面**，所以默认 3。
     */
    val pictureType: Int = 3,
) {
    /** 形如 `800 × 800px·120KB`，与 Tag-Workbench 的 `imageInfo` 同格式。 */
    val info: String
        get() {
            val n = bytes?.size ?: 0
            val size =
                when {
                    n <= 0 ->
                        io.github.univers629.spwtagworkbench.core.i18n.L10n
                            .t("model.noData")
                    n >= 1024 * 1024 ->
                        String.format(java.util.Locale.ROOT, "%.1fMB", n / (1024.0 * 1024.0))
                    else -> String.format(java.util.Locale.ROOT, "%.0fKB", maxOf(1.0, n / 1024.0))
                }
            return if (width > 0 && height > 0) "$width × ${height}px·$size" else size
        }

    /** 候选页左上角/角标用的来源名。 */
    val origin: String
        get() = sourceLabel.ifBlank {
            io.github.univers629.spwtagworkbench.core.i18n.L10n
                .t("model.network")
        }

    /**
     * 图片类型的可读标注，形如 `类型 3 · 专辑封面`。
     *
     * ID3v2 APIC / FLAC PICTURE 的 type 字段就是这套值，写进标签时原样用它。
     */
    val typeLabel: String
        get() =
            io.github.univers629.spwtagworkbench.core.i18n.L10n.t(
                "model.typeLabel",
                pictureType,
                io.github.univers629.spwtagworkbench.backend.read.PictureTypeStandard
                    .label(pictureType),
            )

    /**
     * 短边像素数。
     *
     * 判清晰度用**短边**而不是面积：网易云/QQ 有 980×1400 这种非正方形，
     * 用面积会把「又窄又长」的图算成高清晰。
     */
    val shortSide: Int
        get() = if (width <= 0 || height <= 0) 0 else minOf(width, height)

    /**
     * 清晰度档位。
     *
     * 在线候选在入库前就按 [MIN_SHORT_SIDE] 过滤掉了，所以这里只会出现
     * [Good]；[Low] 只可能出现在**本地封面**（内嵌/同目录）——
     * 用户自己的文件再小也得显示出来，不能用同一个门槛拦。
     */
    val quality: Quality
        get() =
            when {
                shortSide <= 0 -> Quality.Unknown
                shortSide < MIN_SHORT_SIDE -> Quality.Low
                else -> Quality.Good
            }

    /** 是否达到在线候选的入库标准。尺寸未知时放行（探不到不等于不能用）。 */
    val usable: Boolean
        get() = quality != Quality.Low

    override fun equals(other: Any?) = other is CoverCandidate && other.id == id

    override fun hashCode() = id.hashCode()

    /** 封面清晰度档位。 */
    enum class Quality(
        val label: String,
    ) {
        /** 探不到尺寸（某些格式解析失败），不拦。 */
        Unknown(""),

        /** 偏小：只会出现在本地封面，界面标「低清」。 */
        Low(
            io.github.univers629.spwtagworkbench.core.i18n.L10n
                .t("model.lowRes"),
        ),

        /** 够清晰。 */
        Good(""),
    }

    companion object {
        /**
         * 专辑封面的短边下限：低于此值**不进候选列表**。
         *
         * 为什么是 300：
         * - 各源实际给的尺寸档位跨度很大，**500×500 是常见且完全可用**的一档
         *   （实测酷我给 500、网易云给 360），旧值 600 会把它们整档挡掉；
         * - 与艺术家图的下限 [ARTIST_MIN_SHORT_SIDE] 取同一量级：同一张 500px 的图
         *   不该在艺术家页通过、在封面页被拒；
         * - 300 仍能排除图标级缩略图（100~300 那档）；
         * - 低清的丢掉后会由流式槽位继续向后替补，不会留下空位。
         *
         * 取舍：300~600 的图在 340dp 的显示尺寸下放大后会略糊，
         * 但「有图可选」优于「整源被筛空」——用户可在候选列表里自行挑更大的那张。
         */
        const val MIN_SHORT_SIDE = 300

        /**
         * 艺术家图的短边下限，比封面宽松。
         *
         * 艺人照本来就不一定是正方形：网易云的 `img1v1Url` 实测是 **640×420**
         * （短边只有 420），用封面那套 600 会把网易云整个源筛空。
         * 300 足以排除图标级小图，又留得住 640×420 这种真艺人照。
         */
        const val ARTIST_MIN_SHORT_SIDE = 300
    }
}

/**
 * 主封面下方的一个槽位：装艺术家类图片。
 *
 * 带分辨率与字节数，界面要在图旁标出来（与 Tag-Workbench 的 `imageInfo` 一致）。
 */
data class CoverSlot(
    /** ID3v2 / FLAC 图片类型：两个槽位都是 8（Artist）。 */
    val type: Int = 0,
    val label: String,
    val description: String,
    val mime: String,
    val bytes: Int,
    val width: Int,
    val height: Int,
    val image: ImageBitmap?,
) {
    /** 形如 `500 × 500px·120KB`；无图时为「无」。 */
    val info: String
        get() =
            if (width > 0 && height > 0) {
                "$width × ${height}px·${formatBytes(bytes)}"
            } else {
                formatBytes(bytes)
            }

    private fun formatBytes(n: Int): String =
        if (n >= 1024 * 1024) {
            String.format(java.util.Locale.ROOT, "%.1fMB", n / (1024.0 * 1024.0))
        } else {
            String.format(java.util.Locale.ROOT, "%.0fKB", maxOf(1.0, n / 1024.0))
        }
}

/** 频谱分析状态机。Partial 表示已有部分结果可以先画。 */
sealed interface SpectrumState {
    data object Idle : SpectrumState

    data class Running(
        val progress: Float,
    ) : SpectrumState

    data class Partial(
        val channels: List<SpectrumChannel>,
        val progress: Float,
    ) : SpectrumState

    data class Ready(
        val channels: List<SpectrumChannel>,
        val derived: Boolean,
    ) : SpectrumState

    data class Failed(
        val reason: String,
    ) : SpectrumState
}

/** 一条波形包络。samples 归一化到 0..1。 */
data class SpectrumChannel(
    val label: String,
    val samples: FloatArray,
    val accent: Boolean = false,
) {
    override fun equals(other: Any?) = other is SpectrumChannel && other.label == label

    override fun hashCode() = label.hashCode()
}

/** 界面上的临时状态条：成功 / 失败都必须是可见终态，不允许静默。 */
data class Status(
    val text: String,
    val kind: Kind,
) {
    enum class Kind { Ok, Warn, Error, Busy }
}

/** 频谱图状态：真实解码渲染，分批发布部分结果。 */
sealed interface SpectrogramState {
    data object Idle : SpectrogramState

    data class Working(
        val progress: Float,
        /** 左右声道在同一张图里；null 表示还没有可显示的部分 */
        val image: androidx.compose.ui.graphics.ImageBitmap?,
        val displayChannels: Int,
        val durationMs: Long,
        /** 源采样率，频率轴上限 = 采样率 / 2。 */
        val sampleRate: Int = 44100,
    ) : SpectrogramState

    data class Ready(
        val image: androidx.compose.ui.graphics.ImageBitmap,
        val displayChannels: Int,
        val envelope: FloatArray,
        val durationMs: Long,
        /** 源采样率，频率轴上限 = 采样率 / 2。 */
        val sampleRate: Int = 44100,
    ) : SpectrogramState {
        override fun equals(other: Any?): Boolean = this === other

        override fun hashCode(): Int = System.identityHashCode(this)
    }

    data class Failed(
        val reason: String,
    ) : SpectrogramState
}

/** 封面网格里的一个槽位：加载中 / 已就绪 / 该源无更多匹配。 */
sealed interface CoverTile {
    data class Searching(
        val sourceId: String,
        val label: String,
        val index: Int,
    ) : CoverTile

    data class Ready(
        val candidate: CoverCandidate,
    ) : CoverTile

    data class Empty(
        val label: String,
    ) : CoverTile
}
