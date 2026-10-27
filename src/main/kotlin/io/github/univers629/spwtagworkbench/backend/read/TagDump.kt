// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.read

import io.github.univers629.spwtagworkbench.core.Diag
import io.github.univers629.spwtagworkbench.core.Host
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.TagField
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import java.io.File
import java.io.RandomAccessFile

/** 一行标签字段：键 + 原始文本 + 原始字节数。 */
data class TagFieldRow(
    val key: String,
    val value: String,
    val byteSize: Int = 0,
    /** 二进制帧（如图片帧）：值只是占位描述，字节数才是真实内容大小。 */
    val binary: Boolean = false,
)

/** 一个标签段（一个标签体系一张卡）。 */
data class TagSection(
    val title: String,
    val subtitle: String = "",
    val rows: List<TagFieldRow>,
)

/** 一张内嵌图片的只读描述。 */
data class PictureInfo(
    val index: Int,
    val type: Int,
    val typeLabel: String,
    val description: String,
    val mime: String,
    val byteSize: Int,
    val width: Int,
    val height: Int,
    /** 类型在 0..20 之外视为非法（损坏或工具写坏）。 */
    val valid: Boolean,
    val thumbnail: androidx.compose.ui.graphics.ImageBitmap?,
)

/** 音频流参数（来自 AudioHeader）。 */
data class AudioInfo(
    val format: String = "",
    val encoding: String = "",
    val bitsPerSample: Int = 0,
    val lossless: Boolean = false,
    val variableBitRate: Boolean = false,
)

/** 容器内的元数据块：FLAC 的 STREAMINFO/PADDING 之类。 */
data class ContainerBlock(
    val name: String,
    val byteSize: Long,
    val detail: String = "",
)

/** 一首曲目的只读标签快照：文件信息 + 各标签段 + 全部内嵌图片。 */
data class TagDump(
    val path: String,
    val fileName: String,
    val container: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val sampleRate: Int,
    val bitRateKbps: Int,
    val channels: Int,
    val audio: AudioInfo,
    val blocks: List<ContainerBlock>,
    val sections: List<TagSection>,
    val pictures: List<PictureInfo>,
)

/**
 * 读取标签的原始文本、音频参数、容器块与图片列表，供信息页只读展示。
 *
 * 分三段发布：[onStage] 依次收到「文件信息」「标签段」「图片」快照，
 * 界面可以按段铺卡，不必等图片缩略图全部解码完。
 */
object TagDumpReader {
    private val VALID_PICTURE_TYPES = 0..20

    fun read(
        path: String,
        onStage: ((TagDump) -> Unit)? = null,
    ): TagDump? {
        if (path.isBlank()) return null
        val file = File(path)
        if (!file.isFile) return null

        val kind = runCatching { ContainerKind.detect(file) }.getOrDefault(ContainerKind.UNKNOWN)
        val container = kind.label
        var durationMs = 0L
        var sampleRate = 0
        var bitRateKbps = 0
        var channels = 0
        var audio = AudioInfo()
        var sections = emptyList<TagSection>()

        runCatching {
            val parsed = AudioFileIO.read(file)
            parsed.audioHeader?.let { header ->
                durationMs = header.trackLength.toLong() * 1000L
                sampleRate = runCatching { header.sampleRate.toIntOrNull() ?: 0 }.getOrDefault(0)
                bitRateKbps = runCatching { header.bitRate.toIntOrNull() ?: 0 }.getOrDefault(0)
                channels = runCatching { header.channels.toIntOrNull() ?: 0 }.getOrDefault(0)
                audio =
                    AudioInfo(
                        format = runCatching { header.format }.getOrDefault(""),
                        encoding = runCatching { header.encodingType }.getOrDefault(""),
                        bitsPerSample = runCatching { header.bitsPerSample }.getOrDefault(0),
                        lossless = runCatching { header.isLossless }.getOrDefault(false),
                        variableBitRate = runCatching { header.isVariableBitRate }.getOrDefault(false),
                    )
            }
            val tag = parsed.tag
            if (tag != null) {
                val rows = tag
                    .getFields()
                    .asSequence()
                    .map { rowOf(it) }
                    .toList()
                sections =
                    listOf(
                        TagSection(
                            title = tag.javaClass.simpleName,
                            subtitle = sectionSubtitle(tag, rows.size),
                            rows = rows,
                        ),
                    )
            }
        }.onFailure { t ->
            Diag.log("tagdump: 读取标签失败 ${file.name} ${t.javaClass.simpleName}")
        }

        // 附加段：插件自研解析器。
        //
        // jaudiotagger 对这几类格式**没有** Reader：实测 `.ape` / `.wv` / `.tak` /
        // `.tta` / `.mpc` / `.opus` 直接抛「No Reader associated with this
        // extension」，`.dff` 返回 null。而这些格式的标签正是插件自己读写的那几条
        // 路径（APEv2 在文件尾、DFF 的 ID3 块、OpusTags、AAC 前缀 ID3v2、
        // WAV/AIFF 的 INFO 块），不补的话信息页「原始标签」卡对它们整卡是空的。
        // 只补 jaudiotagger 覆盖不到的格式，避免同一份标签出现两张卡。
        sections = sections + ownSections(file, kind)

        // ID3v1 放最后：它是老格式的兼容尾巴，与主标签体系并列而非替代
        id3v1Section(file)?.let { sections = sections + it }

        var dump =
            TagDump(
                path = path,
                fileName = file.name,
                container = container,
                sizeBytes = file.length(),
                durationMs = durationMs,
                sampleRate = sampleRate,
                bitRateKbps = bitRateKbps,
                channels = channels,
                audio = audio,
                blocks = containerLayout(file, kind),
                sections = emptyList(),
                pictures = emptyList(),
            )
        onStage?.invoke(dump)
        dump = dump.copy(sections = sections)
        onStage?.invoke(dump)

        val pictures =
            runCatching {
                CoverStore.readPictures(path).mapIndexed { index, picture ->
                    val (width, height) = Host.imageSize(picture.bytes)
                    PictureInfo(
                        index = index,
                        type = picture.type,
                        typeLabel = PictureTypeStandard.label(picture.type),
                        description = picture.description,
                        mime = picture.mime,
                        byteSize = picture.sizeBytes,
                        width = width,
                        height = height,
                        valid = picture.type in VALID_PICTURE_TYPES,
                        thumbnail = Host.decodeImageSampled(picture.bytes, 160),
                    )
                }
            }.getOrDefault(emptyList())
        dump = dump.copy(pictures = pictures)
        onStage?.invoke(dump)
        return dump
    }

    /** 标签段副标题：体系版本 + 字段数 + 声明大小。 */
    private fun sectionSubtitle(
        tag: org.jaudiotagger.tag.Tag,
        fieldCount: Int,
    ): String {
        val parts = mutableListOf<String>()
        if (tag is AbstractID3v2Tag) {
            runCatching { tag.size }.getOrNull()?.let { parts += "$it B" }
        }
        parts += "$fieldCount fields"
        return parts.joinToString(" · ")
    }

    private fun rowOf(field: TagField): TagFieldRow {
        val id = runCatching { field.id }.getOrDefault("")
        val text = runCatching { field.toString() }.getOrNull().orEmpty()
        val value =
            if (id.isNotBlank() && text.startsWith(id)) {
                text.removePrefix(id).trimStart(' ', '=', ':')
            } else {
                text
            }
        val bytes = runCatching { field.rawContent?.size ?: 0 }.getOrDefault(0)
        val binary = runCatching { field.isBinary }.getOrDefault(false)
        return TagFieldRow(key = id.ifBlank { "?" }, value = value, byteSize = bytes, binary = binary)
    }

    /** ID3v1：文件尾 128 字节，含 `TAG` 标识才解析。 */
    private fun id3v1Section(file: File): TagSection? =
        runCatching {
            if (file.length() < 128) return null
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(file.length() - 128)
                val block = ByteArray(128)
                raf.readFully(block)
                if (String(block, 0, 3, Charsets.ISO_8859_1) != "TAG") return null

                fun text(
                    offset: Int,
                    length: Int,
                ) = String(block, offset, length, Charsets.ISO_8859_1).trimEnd('\u0000', ' ')
                val rows =
                    buildList {
                        add(TagFieldRow("TITLE", text(3, 30)))
                        add(TagFieldRow("ARTIST", text(33, 30)))
                        add(TagFieldRow("ALBUM", text(63, 30)))
                        add(TagFieldRow("YEAR", text(93, 4)))
                        add(TagFieldRow("COMMENT", text(97, 28)))
                        val track = block[125].toInt() and 0xFF
                        if (track != 0) add(TagFieldRow("TRACK", track.toString()))
                        add(TagFieldRow("GENRE_ID", (block[127].toInt() and 0xFF).toString()))
                    }
                TagSection(title = "ID3v1", subtitle = "128 B · ${rows.size} fields", rows = rows)
            }
        }.getOrNull()

    /**
     * 自研解析器的补充标签段。
     *
     * 按 [ContainerKind] 分派，**只覆盖 jaudiotagger 读不到的容器**：
     * - APEv2 系（APE / WV / TAK）：标签在文件尾，走 [ApeV2]；
     * - TTA / MPC：主标签是文件开头的 ID3v2，没有时退回 APEv2（两者都合法）；
     * - OPUS：注释包是 OpusTags，走 [OggTags]；
     * - AAC 裸流：ID3v2 前缀，走 [Id3v2]；
     * - DFF：ID3 块，走 [DffTags]（DSD 流的容器，jaudiotagger 不认识）；
     * - WAV / AIFF：`LIST/INFO` 文本块，走 [RiffInfo]。
     *
     * FLAC / MP3 / MP4 / OGG / WMA / DF 之外的容器不补：那些 jaudiotagger 已经
     * 给出完整标签，再补一张卡只会让同一份数据出现两遍。
     */
    private fun ownSections(
        file: File,
        kind: ContainerKind,
    ): List<TagSection> =
        when {
            kind == ContainerKind.DFF -> listOfNotNull(id3Section(DffTags.read(file), "ID3v2（DFF 块）"))
            kind == ContainerKind.OPUS -> listOfNotNull(oggSection(file))
            kind == ContainerKind.AAC -> listOfNotNull(id3Section(Id3v2.read(file), "ID3v2（AAC 前缀）"))
            // 顺序不能反：TTA / MPC 也声明了 APEV2，但主标签通常是前缀 ID3v2
            kind == ContainerKind.TTA || kind == ContainerKind.MPC ->
                listOfNotNull(id3Section(Id3v2.read(file), "ID3v2（${kind.label} 前缀）") ?: apeSection(file))
            kind.isApeV2 -> listOfNotNull(apeSection(file))
            kind == ContainerKind.WAV || kind == ContainerKind.AIFF -> listOfNotNull(riffSection(file))
            else -> emptyList()
        }

    /** APEv2 段：键 + 文本（二进制项只报大小，避免把图片字节当文本渲染）。 */
    private fun apeSection(file: File): TagSection? {
        val tag = ApeV2.read(file) ?: return null
        val rows =
            tag.items.map { item ->
                TagFieldRow(
                    key = item.key,
                    value = if (item.isBinary) "" else item.text(),
                    byteSize = item.value.size,
                    binary = item.isBinary,
                )
            }
        if (rows.isEmpty()) return null
        return TagSection(
            title = "APEv2",
            subtitle = "自研解析 · ${rows.size} fields · 标签在文件尾",
            rows = rows,
        )
    }

    /** 自研 ID3v2 段（DFF 块 / AAC 前缀 / TTA、MPC 前缀共用同一种渲染）。 */
    private fun id3Section(
        tag: Id3v2.Tag?,
        title: String,
    ): TagSection? {
        if (tag == null) return null
        val rows =
            buildList {
                tag.fields.forEach { (id, value) -> add(TagFieldRow(key = id, value = value)) }
                // TXXX 不进 fields：帧 ID 都是 TXXX，靠描述符区分，单独列才有信息量
                tag.userTexts.forEach { (description, value) ->
                    add(TagFieldRow(key = "TXXX:$description", value = value))
                }
                if (tag.pictures.isNotEmpty()) {
                    add(
                        TagFieldRow(
                            key = "APIC",
                            value = "${tag.pictures.size} 张",
                            byteSize = tag.pictures.sumOf { it.bytes.size },
                            binary = true,
                        ),
                    )
                }
            }
        if (rows.isEmpty()) return null
        return TagSection(
            title = title,
            subtitle = "自研解析 · ID3v2.${tag.major} · ${rows.size} fields · ${tag.totalSize} B",
            rows = rows,
        )
    }

    /** Opus 的 OpusTags（Vorbis comment 结构）。 */
    private fun oggSection(file: File): TagSection? {
        val comment = OggTags.read(file) ?: return null
        if (comment.values.isEmpty()) return null
        val rows = comment.values.map { (key, value) -> TagFieldRow(key = key, value = value) }
        return TagSection(title = "OpusTags", subtitle = "自研解析 · ${rows.size} fields", rows = rows)
    }

    /** WAV / AIFF 的 `LIST/INFO` 文本块。 */
    private fun riffSection(file: File): TagSection? {
        val info = RiffInfo.read(file)
        if (info.isEmpty()) return null
        val rows = info.map { (id, value) -> TagFieldRow(key = id, value = value) }
        return TagSection(title = "RIFF INFO", subtitle = "自研解析 · ${rows.size} fields", rows = rows)
    }

    /** 容器布局：按格式分派，详见 [ContainerLayout]。 */
    private fun containerLayout(
        file: File,
        kind: ContainerKind,
    ): List<ContainerBlock> =
        runCatching {
            ContainerLayout.blocks(file, kind)
        }.getOrDefault(emptyList())
}
