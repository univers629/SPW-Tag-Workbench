// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.read

import io.github.univers629.spwtagworkbench.backend.model.CoverArt
import io.github.univers629.spwtagworkbench.backend.model.TagField
import io.github.univers629.spwtagworkbench.core.Diag
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File

/**
 * ID3v2 `APIC` / FLAC `PICTURE` 的标准图片类型。
 *
 * 注意标准里**没有**「歌曲封面」和「专辑封面」之分：两者都是 [FRONT_COVER]。
 * 艺术家类图片用 [ARTIST] / [LEAD_ARTIST] / [BAND] 区分用途，
 * 归属哪个艺术家记在 `description` 里。
 */
object PictureTypeStandard {
    const val OTHER = 0
    const val FILE_ICON = 1
    const val FRONT_COVER = 3
    const val BACK_COVER = 4
    const val LEAD_ARTIST = 7
    const val ARTIST = 8
    const val BAND = 10
    const val COMPOSER = 11
    const val LYRICIST = 12

    /**
     * 艺术家类图片的替代顺序。
     *
     * 与 Lyrico 的 `artistPictureTypes` 一致：Artist → LeadArtist → Band。
     * 下方两个槽位按这个顺序取前两张可用的图。
     */
    val ARTIST_CHAIN = listOf(ARTIST, LEAD_ARTIST, BAND)

    fun label(type: Int): String =
        when (type) {
            FRONT_COVER -> "专辑封面"
            BACK_COVER -> "封底"
            ARTIST -> "艺术家"
            LEAD_ARTIST -> "主唱"
            BAND -> "乐队"
            COMPOSER -> "作曲"
            LYRICIST -> "作词"
            OTHER -> "其他"
            FILE_ICON -> "图标"
            else -> "类型 $type"
        }

    /** 是否为艺术家类图片。 */
    fun isArtistType(type: Int): Boolean = type in ARTIST_CHAIN
}

/**
 * 一张内嵌图片。
 *
 * `type` 见 [PictureTypeStandard]；`description` 是标签里的描述文本
 * （ID3v2 用它记艺术家名，见 Lyrico 的归属规则）。
 */
class EmbeddedPicture(
    val type: Int,
    val description: String,
    val bytes: ByteArray,
    val mime: String,
) {
    val sizeBytes: Int get() = bytes.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbeddedPicture) return false
        return type == other.type &&
            description == other.description &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var r = type
        r = 31 * r + description.hashCode()
        r = 31 * r + bytes.contentHashCode()
        return r
    }
}

/**
 * 封面读取与 MIME 嗅探。
 */
object CoverMime {
    /**
     * 按文件头判断图片类型。
     *
     * 不要写死 `image/jpeg`：在线来源并不保证返回 JPEG，
     * PNG / WebP 封面用错 MIME 会在播放器里显示异常。
     */
    fun detect(bytes: ByteArray?): String {
        if (bytes == null || bytes.size < 12) return "image/jpeg"
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return "image/jpeg"
        if (bytes[0] == 0x89.toByte() &&
            bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() &&
            bytes[3] == 'G'.code.toByte()
        ) {
            return "image/png"
        }
        if (bytes[0] == 'G'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte()
        ) {
            return "image/gif"
        }
        if (bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()) return "image/bmp"
        if (bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() &&
            bytes[11] == 'P'.code.toByte()
        ) {
            return "image/webp"
        }
        return "image/jpeg"
    }

    fun asCover(bytes: ByteArray?): CoverArt? {
        if (bytes == null || bytes.isEmpty()) return null
        return CoverArt(bytes = bytes, mime = detect(bytes))
    }
}

/**
 * 主封面选取：优先 Front Cover（type=3），并跳过艺术家类图片。
 *
 * 艺术家图（Artist / LeadArtist / Band）属于艺术家槽，不能被当成专辑封面：
 * 文件里只有一张艺术家图时，取第一张会把艺人头像显示到主封面位置。
 *
 * 类型字段只用于排除艺术家图，**不校验取值范围**：部分工具把该字段写成
 * `0xFFFFFFFF`（读出为 -1），按范围校验会把一张完好的可解码封面整个丢掉。
 * 类型未知时按封面处理。
 */
internal fun frontCoverArtwork(tag: org.jaudiotagger.tag.Tag?): org.jaudiotagger.tag.images.Artwork? {
    val pictures = runCatching { tag?.artworkList }.getOrNull().orEmpty()

    fun typeOf(art: org.jaudiotagger.tag.images.Artwork): Int = runCatching { art.pictureType }.getOrDefault(0)

    fun usableAsCover(type: Int): Boolean = type !in PictureTypeStandard.ARTIST_CHAIN
    val picked =
        pictures.firstOrNull { typeOf(it) == PictureTypeStandard.FRONT_COVER }
            ?: pictures.firstOrNull { usableAsCover(typeOf(it)) }
    if (picked == null && pictures.isNotEmpty()) {
        Diag.log("cover-pick: 文件里只有艺术家图，没有可用的主封面")
    }
    // 类型超出标准取值时按封面处理，只在真出现时记一条，便于确认这条路径
    val pickedType = picked?.let { typeOf(it) }
    if (pickedType != null && pickedType !in 0..20) {
        Diag.log("cover-pick: 图片类型 $pickedType 超出标准取值，已按封面处理")
    }
    return picked
}

/**
 * 封面缓存。
 *
 * 同一首歌不要反复解码：字节按曲目 key 缓存，超过容量按最近最少使用淘汰。
 * 只缓存原始字节（UI 层自己解码成位图），保证后端不依赖任何图形类型。
 */
object CoverStore {
    private const val MAX_ENTRIES = 24
    private const val MAX_BYTES = 64L * 1024L * 1024L

    private val order = ArrayDeque<String>()
    private val cache = HashMap<String, ByteArray>()
    private var cachedBytes = 0L

    @Synchronized
    fun cached(key: String): ByteArray? {
        val hit = cache[key] ?: return null
        order.remove(key)
        order.addLast(key)
        return hit
    }

    @Synchronized
    fun put(
        key: String,
        bytes: ByteArray,
    ) {
        if (bytes.isEmpty()) return
        cache.put(key, bytes)?.let { cachedBytes -= it.size }
        order.remove(key)
        order.addLast(key)
        cachedBytes += bytes.size
        while (order.size > MAX_ENTRIES || cachedBytes > MAX_BYTES) {
            val evicted = order.removeFirstOrNull() ?: break
            cache.remove(evicted)?.let { cachedBytes -= it.size }
        }
    }

    /** 只清单图缓存。整体清空请用 [clearPictures]（插件停止时调用）。 */
    @Synchronized
    fun clear() {
        cache.clear()
        order.clear()
        cachedBytes = 0L
    }

    /** 读内嵌封面；带缓存。 */
    fun loadFromFile(
        key: String,
        path: String,
    ): ByteArray? {
        cached(key)?.let { return it }
        if (path.isBlank()) return null
        val file = File(path)
        if (!file.isFile) return null

        val bytes =
            try {
                val audio = AudioFileIO.read(file)
                frontCoverArtwork(audio.tag)?.binaryData
            } catch (t: Throwable) {
                Diag.log("cover: 读取失败 ${file.name} ${t.javaClass.simpleName}")
                null
            }
        if (bytes != null && bytes.isNotEmpty()) put(key, bytes)
        return bytes
    }

    // ── 多图读取 ─────────────────────────────────────────────────────────────

    private const val MAX_PIC_ENTRIES = 12

    /**
     * 多图缓存的字节预算。
     *
     * 只限条数不够：这里缓存的是曲目**全部内嵌图片的原始字节**
     * （[EmbeddedPicture.bytes]），封面常见 1~5MB、多图文件更大，
     * 12 首就可能上百 MB。与单图缓存（[MAX_BYTES]）一样按字节淘汰。
     */
    private const val MAX_PIC_BYTES = 48L * 1024L * 1024L

    private val picOrder = ArrayDeque<String>()
    private val picCache = HashMap<String, List<EmbeddedPicture>>()
    private var picCachedBytes = 0L

    /** 一组内嵌图片的总字节数。 */
    private fun bytesOf(list: List<EmbeddedPicture>): Long {
        var sum = 0L
        for (p in list) sum += p.bytes.size
        return sum
    }

    /** 按访问序淘汰，直到条数与字节都在预算内。 */
    private fun trimPicCache() {
        while (picOrder.size > MAX_PIC_ENTRIES || picCachedBytes > MAX_PIC_BYTES) {
            val evicted = picOrder.removeFirstOrNull() ?: break
            picCache.remove(evicted)?.let { picCachedBytes -= bytesOf(it) }
        }
    }

    /**
     * 读全部内嵌图片（带类型与描述）。
     *
     * 与 [loadFromFile] 分开缓存：单图那条路径只关心第一张，这里要全部。
     */
    @Synchronized
    fun readPictures(path: String): List<EmbeddedPicture> {
        if (path.isBlank()) return emptyList()
        picCache[path]?.let { hit ->
            picOrder.remove(path)
            picOrder.addLast(path)
            return hit
        }
        val file = File(path)
        if (!file.isFile) return emptyList()

        val list =
            try {
                if (ContainerKind.detect(file) == ContainerKind.DFF) {
                    DffTags.read(file)?.pictures?.map { pic ->
                        EmbeddedPicture(
                            type = pic.type,
                            description = pic.description,
                            bytes = pic.bytes,
                            mime = pic.mime.ifBlank { CoverMime.detect(pic.bytes) },
                        )
                    } ?: emptyList()
                } else if (ContainerKind.detect(file) == ContainerKind.AAC) {
                    Id3v2.read(file)?.pictures?.map { pic ->
                        EmbeddedPicture(
                            type = pic.type,
                            description = pic.description,
                            bytes = pic.bytes,
                            mime = pic.mime.ifBlank { CoverMime.detect(pic.bytes) },
                        )
                    } ?: emptyList()
                } else if (ContainerKind.detect(file) == ContainerKind.OPUS) {
                    OggTags.pictures(file).map { pic ->
                        EmbeddedPicture(
                            type = pic.type,
                            description = "",
                            bytes = pic.bytes,
                            mime = pic.mime.ifBlank { CoverMime.detect(pic.bytes) },
                        )
                    }
                } else if (ContainerKind.detect(file) == ContainerKind.TTA && Id3v2.read(file) != null) {
                    Id3v2.read(file)?.pictures?.map { pic ->
                        EmbeddedPicture(
                            type = pic.type,
                            description = pic.description,
                            bytes = pic.bytes,
                            mime = pic.mime.ifBlank { CoverMime.detect(pic.bytes) },
                        )
                    } ?: emptyList()
                } else if (ContainerKind.detect(file).isApeV2) {
                    // APEv2 容器（APE / WV / TAK / TTA / MPC）：标签库不支持，直接用尾部标签
                    val tag = ApeV2.read(file)
                    val cover = tag?.item(ApeV2.Key.COVER_FRONT)?.value?.let { ApeV2.coverBytes(it) }
                    if (cover == null || cover.isEmpty()) {
                        emptyList()
                    } else {
                        listOf(
                            EmbeddedPicture(
                                type = PictureTypeStandard.FRONT_COVER,
                                description = "",
                                bytes = cover,
                                mime = CoverMime.detect(cover),
                            ),
                        )
                    }
                } else {
                    val audio = AudioFileIO.read(file)
                    val tag = audio.tag ?: return emptyList()
                    tag.artworkList.orEmpty().mapNotNull { art ->
                        val data = art.binaryData
                        if (data == null || data.isEmpty()) {
                            null
                        } else {
                            EmbeddedPicture(
                                type = runCatching { art.pictureType }.getOrDefault(0),
                                description = art.description.orEmpty(),
                                bytes = data,
                                mime = art.mimeType ?: CoverMime.detect(data),
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                Diag.log("pictures: 读取失败 ${file.name} ${t.javaClass.simpleName}")
                emptyList()
            }

        picCache.put(path, list)?.let { picCachedBytes -= bytesOf(it) }
        picCachedBytes += bytesOf(list)
        picOrder.remove(path)
        picOrder.addLast(path)
        trimPicCache()
        return list
    }

    /** 写入封面后清掉该曲目的图片缓存，否则界面还是旧图。 */
    @Synchronized
    fun invalidate(path: String) {
        picCache.remove(path)?.let { picCachedBytes -= bytesOf(it) }
        cache.remove(path)?.let { cachedBytes -= it.size }
        order.remove(path)
        picOrder.remove(path)
    }

    /**
     * 全部清空（插件停止时调用）。
     *
     * 两条缓存一起清：多图缓存持有的是图片原始字节，停用插件后不该继续驻留。
     */
    @Synchronized
    fun clearPictures() {
        picCache.clear()
        picOrder.clear()
        picCachedBytes = 0L
        cache.clear()
        order.clear()
        cachedBytes = 0L
    }
}

/**
 * 读取层：把「宿主实体」和「文件」都收成统一模型。
 */
object TrackReader {
    /**
     * ReplayGain 四键的原始文本。
     *
     * 保留文件里的原样字符串而不解析成数值：规范要求消费方容忍写法变体
     * （前导 `+`、位数多少、键名大小写），解析时机交给真正要用它的地方；
     * 没写过这些标签的文件四项都是空串，与「算出来是 0」区分得开。
     */
    data class Gain(
        val trackGain: String = "",
        val trackPeak: String = "",
        val albumGain: String = "",
        val albumPeak: String = "",
    )

    /**
     * 按 [TagField.tagKey] 取四个 ReplayGain 键。
     *
     * 键名由规范固定，三套体系完全一致，差别只在「怎么取值」——
     * 因此这里收一个取值函数，各条读取路径传各自的实现。
     */
    private fun replayGainOf(lookup: (TagField) -> String?): Gain =
        Gain(
            trackGain = lookup(TagField.ReplayGainTrackGain).orEmpty(),
            trackPeak = lookup(TagField.ReplayGainTrackPeak).orEmpty(),
            albumGain = lookup(TagField.ReplayGainAlbumGain).orEmpty(),
            albumPeak = lookup(TagField.ReplayGainAlbumPeak).orEmpty(),
        )

    /** 从文件读一份完整标签（封面字节可选）。 */
    fun readFile(
        path: String,
        withCover: Boolean = false,
    ): io.github.univers629.spwtagworkbench.backend.model.Track? {
        if (path.isBlank()) return null
        val file = File(path)
        if (!file.isFile) return null

        return try {
            val kind = ContainerKind.detect(file)
            // TTA 与 MPC 的主标签都可能是文件开头的 ID3v2，其次才是尾部 APEv2。
            // 只走 APEv2 时，带 ID3v2 前缀的文件会读不出标签（jaudiotagger 不支持这两种容器）。
            if (kind == ContainerKind.TTA || kind == ContainerKind.MPC) {
                val tag = Id3v2.read(file)
                if (tag != null) {
                    val fromId3 = trackFromId3(tag, path, withCover)
                    if (fromId3 != null) return fromId3
                }
            }
            if (kind.isApeV2) {
                val track = readApeV2(file, path, withCover)
                if (track != null) return track
            }
            if (kind == ContainerKind.OPUS) {
                val track = readOpus(file, path, withCover)
                if (track != null) return track
            }
            if (kind == ContainerKind.AAC) {
                return readAac(file, path, withCover)
            }
            if (kind == ContainerKind.DFF) {
                val tag = DffTags.read(file)
                if (tag != null) return trackFromId3(tag, path, withCover) ?: readAac(file, path, withCover)
            }
            val audio = AudioFileIO.read(file)
            val tag = audio.tag
            val header = audio.audioHeader
            // 主封面**只取一次**：`hasCover` 需要知道「有没有图」，
            // 而 withCover 只决定「要不要把字节读出来」。此前分两处各调一次
            // frontCoverArtwork（它会遍历 artworkList 并写两条诊断日志），
            // 同一首歌的封面被重复解析。
            val coverArt = frontCoverArtwork(tag)
            val artwork = if (withCover) coverArt?.binaryData else null
            if (artwork != null && artwork.isNotEmpty()) {
                CoverStore.put(path, artwork)
            }
            // jaudiotagger 路径的自由键取法按体系不同，统一走 JtUserFields
            val gain = replayGainOf { JtUserFields.get(tag, it.tagKey) }
            io.github.univers629.spwtagworkbench.backend.model
                .Track(
                    id = "",
                    path = path,
                    title = read(tag, FieldKey.TITLE),
                    artist = read(tag, FieldKey.ARTIST),
                    album = read(tag, FieldKey.ALBUM),
                    albumArtist = read(tag, FieldKey.ALBUM_ARTIST),
                    genre = read(tag, FieldKey.GENRE),
                    year = read(tag, FieldKey.YEAR),
                    track = read(tag, FieldKey.TRACK),
                    disc = read(tag, FieldKey.DISC_NO),
                    lyrics = read(tag, FieldKey.LYRICS),
                    comment = read(tag, FieldKey.COMMENT),
                    lyricist = read(tag, FieldKey.LYRICIST),
                    composer = read(tag, FieldKey.COMPOSER),
                    durationMs =
                        run {
                            val seconds = header?.trackLength?.toLong() ?: 0L
                            seconds * 1000L
                        },
                    bitDepth = if ((header?.bitsPerSample ?: 0) > 0) "${header?.bitsPerSample} bit" else "",
                    bitrate = header?.bitRate?.let { "$it kbps" } ?: "",
                    replayGainTrackGain = gain.trackGain,
                    replayGainTrackPeak = gain.trackPeak,
                    replayGainAlbumGain = gain.albumGain,
                    replayGainAlbumPeak = gain.albumPeak,
                    readable = true,
                    // 复用上面已解析出的那张，不再重复遍历 artworkList
                    hasCover = coverArt != null,
                ).let { track -> mergeRiffInfo(file, track) }
        } catch (t: Throwable) {
            Diag.log("read: 读文件失败 ${file.name} ${t.javaClass.simpleName}")
            null
        }
    }

    /**
     * WAV / AIFF 常见「只有 RIFF INFO、没有 ID3」的文件：
     * 用 INFO 里的字段补齐标签库读出来的空字段。
     */
    private fun mergeRiffInfo(
        file: File,
        track: io.github.univers629.spwtagworkbench.backend.model.Track,
    ): io.github.univers629.spwtagworkbench.backend.model.Track {
        val kind = ContainerKind.detect(file)
        if (kind != ContainerKind.WAV && kind != ContainerKind.AIFF) return track
        val info = RiffInfo.read(file)
        if (info.isEmpty()) return track

        fun pick(
            current: String,
            key: String,
        ): String = current.ifBlank { info[key].orEmpty() }
        return track.copy(
            title = pick(track.title, "title"),
            artist = pick(track.artist, "artist"),
            album = pick(track.album, "album"),
            genre = pick(track.genre, "genre"),
            year = pick(track.year, "year"),
            track = pick(track.track, "track"),
            comment = pick(track.comment, "comment"),
        )
    }

    /** 从 AAC 裸流的前置 ID3v2 构造 Track；没有标签时返回空字段的 Track。 */
    private fun readAac(
        file: File,
        path: String,
        withCover: Boolean,
    ): io.github.univers629.spwtagworkbench.backend.model.Track =
        trackFromId3(Id3v2.read(file), path, withCover)
            ?: io.github.univers629.spwtagworkbench.backend.model.Track(
                id = "",
                path = path,
                readable = true,
            )

    /** 用一份 ID3v2 标签构造 Track（AAC / DFF 共用）。 */
    private fun trackFromId3(
        tag: Id3v2.Tag?,
        path: String,
        withCover: Boolean,
    ): io.github.univers629.spwtagworkbench.backend.model.Track? {
        tag ?: return null
        val cover = tag.pictures.firstOrNull()?.bytes
        if (withCover && cover != null && cover.isNotEmpty()) CoverStore.put(path, cover)
        val major = tag.major

        fun field(id: String): String = tag.text(id).orEmpty()

        // ReplayGain 在 ID3v2 里是 TXXX，按描述符取（描述符 = TagField.tagKey）
        val gain = replayGainOf { tag.userText(it.tagKey) }
        return io.github.univers629.spwtagworkbench.backend.model.Track(
            id = "",
            path = path,
            title = field("TIT2"),
            artist = field("TPE1"),
            album = field("TALB"),
            albumArtist = field("TPE2"),
            genre = field("TCON"),
            year = field(Id3v2.yearFrameId(major)).ifEmpty { field("TYER") }.ifEmpty { field("TDRC") },
            track = field("TRCK"),
            disc = field("TPOS"),
            lyrics = tag?.lyrics.orEmpty(),
            comment = field("COMM"),
            lyricist = field("TEXT"),
            composer = field("TCOM"),
            durationMs = 0L,
            bitDepth = "",
            bitrate = "",
            replayGainTrackGain = gain.trackGain,
            replayGainTrackPeak = gain.trackPeak,
            replayGainAlbumGain = gain.albumGain,
            replayGainAlbumPeak = gain.albumPeak,
            readable = true,
            hasCover = cover != null && cover.isNotEmpty(),
        )
    }

    /** 从 OpusTags 注释构造 Track；读不到注释时返回 null。 */
    private fun readOpus(
        file: File,
        path: String,
        withCover: Boolean,
    ): io.github.univers629.spwtagworkbench.backend.model.Track? {
        val comment = OggTags.read(file) ?: return null
        val gain = replayGainOf { comment.text(it.tagKey) }
        return io.github.univers629.spwtagworkbench.backend.model.Track(
            id = "",
            path = path,
            title = comment.text("TITLE").orEmpty(),
            artist = comment.text("ARTIST").orEmpty(),
            album = comment.text("ALBUM").orEmpty(),
            albumArtist = comment.text("ALBUMARTIST").orEmpty(),
            genre = comment.text("GENRE").orEmpty(),
            year = comment.text("DATE").orEmpty(),
            track = comment.text("TRACKNUMBER").orEmpty(),
            disc = comment.text("DISCNUMBER").orEmpty(),
            lyrics = comment.text("LYRICS").orEmpty(),
            comment = comment.text("COMMENT").orEmpty(),
            lyricist = comment.text("LYRICIST").orEmpty(),
            composer = comment.text("COMPOSER").orEmpty(),
            durationMs = 0L,
            bitDepth = "",
            bitrate = "",
            replayGainTrackGain = gain.trackGain,
            replayGainTrackPeak = gain.trackPeak,
            replayGainAlbumGain = gain.albumGain,
            replayGainAlbumPeak = gain.albumPeak,
            readable = true,
            hasCover = comment.text("METADATA_BLOCK_PICTURE") != null,
        )
    }

    /**
     * 从 APEv2 标签构造 Track。容器不支持读取时返回 null，由调用方回到标签库流程。
     *
     * APEv2 容器没有统一的音频头解析入口，时长与码率留空。
     */
    private fun readApeV2(
        file: File,
        path: String,
        withCover: Boolean,
    ): io.github.univers629.spwtagworkbench.backend.model.Track? {
        val tag = ApeV2.read(file) ?: return null
        val cover = tag.item(ApeV2.Key.COVER_FRONT)?.value?.let { ApeV2.coverBytes(it) }
        if (withCover && cover != null && cover.isNotEmpty()) {
            CoverStore.put(path, cover)
        }
        val gain = replayGainOf { tag.text(it.tagKey) }
        return io.github.univers629.spwtagworkbench.backend.model.Track(
            id = "",
            path = path,
            title = tag.text(ApeV2.Key.TITLE).orEmpty(),
            artist = tag.text(ApeV2.Key.ARTIST).orEmpty(),
            album = tag.text(ApeV2.Key.ALBUM).orEmpty(),
            albumArtist = tag.text(ApeV2.Key.ALBUM_ARTIST).orEmpty(),
            genre = tag.text(ApeV2.Key.GENRE).orEmpty(),
            year = tag.text(ApeV2.Key.YEAR).orEmpty(),
            track = tag.text(ApeV2.Key.TRACK).orEmpty(),
            disc = tag.text(ApeV2.Key.DISC).orEmpty(),
            lyrics = tag.text(ApeV2.Key.LYRICS).orEmpty(),
            comment = tag.text(ApeV2.Key.COMMENT).orEmpty(),
            lyricist = tag.text(ApeV2.Key.LYRICIST).orEmpty(),
            composer = tag.text(ApeV2.Key.COMPOSER).orEmpty(),
            durationMs = 0L,
            bitDepth = "",
            bitrate = "",
            replayGainTrackGain = gain.trackGain,
            replayGainTrackPeak = gain.trackPeak,
            replayGainAlbumGain = gain.albumGain,
            replayGainAlbumPeak = gain.albumPeak,
            readable = true,
            hasCover = cover != null && cover.isNotEmpty(),
        )
    }

    /** 只读歌词文本。 */
    fun readLyrics(path: String): String? {
        if (path.isBlank()) return null
        val file = File(path)
        if (!file.isFile) return null
        return try {
            val kind = ContainerKind.detect(file)
            if (kind == ContainerKind.TTA) {
                Id3v2.read(file)?.lyrics?.let { return it }
            }
            if (kind.isApeV2) {
                return ApeV2.read(file)?.text(ApeV2.Key.LYRICS)
            }
            if (kind == ContainerKind.OPUS) {
                return OggTags.read(file)?.text("LYRICS")
            }
            if (kind == ContainerKind.AAC) {
                return Id3v2.read(file)?.lyrics
            }
            if (kind == ContainerKind.DFF) {
                return DffTags.read(file)?.lyrics
            }
            val text = AudioFileIO.read(file).tag?.getFirst(FieldKey.LYRICS)
            text?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            Diag.log("read: 读歌词失败 ${file.name} ${t.javaClass.simpleName}")
            null
        }
    }

    private fun read(
        tag: org.jaudiotagger.tag.Tag?,
        key: FieldKey,
    ): String {
        if (tag == null) return ""
        return try {
            // 个别容器（WAV 的 INFO 块）会在值末尾留 NUL，读出来要清掉
            tag
                .getFirst(key)
                ?.replace("\u0000", "")
                ?.trim()
                .orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }
}
