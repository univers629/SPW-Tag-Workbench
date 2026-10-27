// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.read

import java.io.File
import java.io.RandomAccessFile

/**
 * 容器结构扫描：把文件里的「块 / 帧 / 标签区」列出来，供信息页只读展示。
 *
 * 各容器格式差异很大，这里按 [ContainerKind] 分派；每个解析器都只读头部
 * 与必要的偏移，不解析音频负载，因此打开大文件也很快。
 */
object ContainerLayout {
    private const val MAX_BLOCKS = 64

    fun blocks(
        file: File,
        kind: ContainerKind,
    ): List<ContainerBlock> {
        if (!file.isFile) return emptyList()
        val out =
            when (kind) {
                ContainerKind.FLAC -> flac(file)
                ContainerKind.MP3 -> id3v2Prefix(file) + mp3Frames(file) + id3v1(file) + apev2(file)
                ContainerKind.AAC -> id3v2Prefix(file) + adts(file) + id3v1(file)
                ContainerKind.MP4 -> mp4(file)
                ContainerKind.OGG -> ogg(file, opus = false)
                ContainerKind.OPUS -> ogg(file, opus = true)
                ContainerKind.TTA -> id3v2Prefix(file) + id3v1(file) + apev2(file)
                ContainerKind.AIFF -> aiff(file) + id3v1(file)
                ContainerKind.DSF -> dsf(file) + id3v1(file)
                ContainerKind.DFF -> dff(file) + id3v1(file)
                ContainerKind.WAV -> wav(file) + id3v1(file)
                ContainerKind.APE,
                ContainerKind.WAVPACK,
                ContainerKind.TAK,
                ContainerKind.MPC,
                -> id3v2Prefix(file) + apev2(file) + id3v1(file)
                ContainerKind.WMA -> asf(file)
                ContainerKind.UNKNOWN -> emptyList()
            }
        return out.take(MAX_BLOCKS)
    }

    private fun block(
        name: String,
        size: Long,
        detail: String = "",
    ) = ContainerBlock(name = name, byteSize = size, detail = detail)

    // ── ID3 ────────────────────────────────────────────────────────────────

    /** 文件开头的 ID3v2 头：版本、标志、声明长度。头不合法时不算块。 */
    private fun id3v2Prefix(file: File): List<ContainerBlock> = id3v2At(file, 0).let { if (it == null) emptyList() else listOf(it) }

    private fun id3v2At(
        file: File,
        offset: Long,
    ): ContainerBlock? =
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                if (offset < 0 || raf.length() < offset + Id3v2.HEADER_SIZE) return null
                raf.seek(offset)
                val head = ByteArray(Id3v2.HEADER_SIZE)
                raf.readFully(head)
                // 版本、修订号、同步安全位都要对得上：「ID3」三个字节本身不能证明这是标签
                // （实测样本 `almostempty.mpc` 就是 `ID3` + 任意输入的文字）。
                val parsed = Id3v2.header(head) ?: return null
                // 声明的标签长度必须落在文件内，否则是坏头，画出来只会误导
                if (offset + parsed.totalSize > raf.length()) return null
                block(
                    name = "ID3v2.${parsed.major}.${parsed.revision}",
                    size = parsed.totalSize.toLong(),
                    detail = "flags=0x%02X".format(parsed.flags),
                )
            }
        }.getOrNull()

    /** ID3v1：文件尾 128 字节。 */
    private fun id3v1(file: File): List<ContainerBlock> =
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 128) return emptyList()
                raf.seek(raf.length() - 128)
                val tag = ByteArray(3)
                raf.readFully(tag)
                if (String(tag, Charsets.ISO_8859_1) != "TAG") emptyList() else listOf(block("ID3v1", 128))
            }
        }.getOrDefault(emptyList())

    // ── MP3 / AAC ──────────────────────────────────────────────────────────

    /**
     * MP3：跳过 ID3v2 后统计 MPEG 帧数量与总长。
     *
     * 帧头里的位率索引与采样率索引**必须按「MPEG 版本 + 层」查表**：
     * MPEG2 / MPEG2.5 的采样率是 22050 / 12000 这一档、位率表也与 MPEG1 不同
     * （Layer III 的 8~160 kbps 一档）。只按 MPEG1 查表会把帧长算大 1 倍左右，
     * 逐帧推进随即错位到半个帧头、很快 `break`，于是信息页把一首 MPEG2.5 的
     * 文件显示成「几十帧」甚至没有 MPEG frames 行。
     */
    private fun mp3Frames(file: File): List<ContainerBlock> =
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                var pos = id3TagEnd(raf, 0)
                if (pos >= raf.length()) return emptyList()
                var frames = 0
                var bytes = 0L
                val end = raf.length() - if (hasId3v1(raf)) 128 else 0
                while (pos + 4 <= end && frames < 400_000) {
                    raf.seek(pos)
                    val h = ByteArray(4)
                    raf.readFully(h)
                    val frameSize = mpegFrameSize(h) ?: break
                    frames++
                    bytes += frameSize
                    pos += frameSize
                }
                if (frames == 0) emptyList() else listOf(block("MPEG frames", bytes, "$frames frames"))
            }
        }.getOrDefault(emptyList())

    /**
     * 解析一个 MPEG 音频帧头，返回**整帧字节数**；不是合法帧头返回 null。
     *
     * 版本编码：`00`=MPEG2.5、`10`=MPEG2、`11`=MPEG1（`01` 保留）。
     * 层编码：`01`=Layer III、`10`=Layer II、`11`=Layer I（`00` 保留）。
     * 帧长公式：Layer I 为 `(12×bitrate/fs+pad)×4`，Layer III 的 LSF（MPEG2/2.5）
     * 为 `72×bitrate/fs+pad`，其余为 `144×bitrate/fs+pad`。
     */
    private fun mpegFrameSize(h: ByteArray): Long? {
        val b0 = h[0].toInt() and 0xFF
        val b1 = h[1].toInt() and 0xFF
        val b2 = h[2].toInt() and 0xFF
        // 11 位同步字 + 版本/层都不能是保留值
        if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return null
        val version =
            when ((b1 shr 3) and 0x03) {
                0 -> 25 // MPEG2.5
                2 -> 2
                3 -> 1
                else -> return null
            }
        val layer =
            when ((b1 shr 1) and 0x03) {
                1 -> 3
                2 -> 2
                3 -> 1
                else -> return null
            }
        val bitrateIndex = (b2 shr 4) and 0x0F
        val sampleRateIndex = (b2 shr 2) and 0x03
        if (bitrateIndex == 0 || bitrateIndex == 0x0F || sampleRateIndex == 3) return null
        val bitrate = bitrateTable(version, layer)[bitrateIndex].toLong() * 1000
        val sampleRate = sampleRateTable(version)[sampleRateIndex]
        if (bitrate <= 0 || sampleRate <= 0) return null
        val padding = (b2 shr 1) and 0x01
        val frameSize =
            when {
                layer == 1 -> ((12 * bitrate / sampleRate) + padding) * 4
                layer == 3 && version != 1 -> 72 * bitrate / sampleRate + padding
                else -> 144 * bitrate / sampleRate + padding
            }
        return frameSize.takeIf { it > 4 }
    }

    /** 位率表（kbps）：索引 0 = free、15 = 非法，都填 0 以便直接下标。 */
    private fun bitrateTable(
        version: Int,
        layer: Int,
    ): IntArray {
        val lsf = version != 1
        return when {
            layer == 1 && !lsf -> intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, 0)
            layer == 1 -> intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256, 0)
            layer == 2 && !lsf -> intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 0)
            !lsf -> intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
            // Layer II / III 的 LSF 表相同
            else -> intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
        }
    }

    /** 采样率表（Hz）：版本决定基础档位。 */
    private fun sampleRateTable(version: Int): IntArray =
        when (version) {
            1 -> intArrayOf(44100, 48000, 32000)
            2 -> intArrayOf(22050, 24000, 16000)
            else -> intArrayOf(11025, 12000, 8000)
        }

    /** AAC 裸流：ADTS 帧数量。 */
    private fun adts(file: File): List<ContainerBlock> =
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                var pos = id3TagEnd(raf, 0)
                var frames = 0
                var bytes = 0L
                while (pos + 7 <= raf.length() && frames < 400_000) {
                    raf.seek(pos)
                    val h = ByteArray(7)
                    raf.readFully(h)
                    if ((h[0].toInt() and 0xFF) != 0xFF || (h[1].toInt() and 0xF0) != 0xF0) break
                    val frameLength =
                        ((h[3].toInt() and 0x03) shl 11) or
                            ((h[4].toInt() and 0xFF) shl 3) or
                            ((h[5].toInt() and 0xE0) shr 5)
                    if (frameLength <= 7) break
                    frames++
                    bytes += frameLength
                    pos += frameLength
                }
                if (frames == 0) emptyList() else listOf(block("ADTS frames", bytes, "$frames frames"))
            }
        }.getOrDefault(emptyList())

    /** ID3v2 前缀的实际结尾（跳过标签后的音频起点）；头不合法时返回 [offset]。 */
    private fun id3TagEnd(
        raf: RandomAccessFile,
        offset: Long,
    ): Long =
        runCatching {
            if (offset < 0 || raf.length() < offset + Id3v2.HEADER_SIZE) return offset
            raf.seek(offset)
            val head = ByteArray(Id3v2.HEADER_SIZE)
            raf.readFully(head)
            val parsed = Id3v2.header(head) ?: return offset
            if (offset + parsed.totalSize > raf.length()) return offset
            offset + parsed.totalSize
        }.getOrDefault(offset)

    private fun hasId3v1(raf: RandomAccessFile): Boolean =
        runCatching {
            if (raf.length() < 128) return false
            raf.seek(raf.length() - 128)
            val tag = ByteArray(3)
            raf.readFully(tag)
            String(tag, Charsets.ISO_8859_1) == "TAG"
        }.getOrDefault(false)

    // ── FLAC ───────────────────────────────────────────────────────────────

    private fun flac(file: File): List<ContainerBlock> =
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val head = ByteArray(4)
                raf.seek(0)
                raf.readFully(head)
                if (String(head, Charsets.ISO_8859_1) != "fLaC") return emptyList()
                val blocks = mutableListOf<ContainerBlock>()
                var pos = 4L
                var last = false
                // 每轮先校验剩余长度够读块头，再读：块长损坏时 readFully 会抛 EOF，
                // 而外层 catch 会把已经解析出的块全部丢掉。逐轮校验则保留已有结果。
                while (!last && blocks.size < MAX_BLOCKS && pos + 4 <= raf.length()) {
                    raf.seek(pos)
                    val header = ByteArray(4)
                    raf.readFully(header)
                    val flag = header[0].toInt()
                    last = (flag and 0x80) != 0
                    val type = flag and 0x7F
                    val length =
                        ((header[1].toInt() and 0xFF) shl 16) or
                            ((header[2].toInt() and 0xFF) shl 8) or
                            (header[3].toInt() and 0xFF)
                    // 块体必须落在文件内，否则是损坏的长度字段：停止而不是继续错位读
                    if (pos + 4 + length > raf.length()) break
                    blocks += block(flacBlockName(type), length.toLong())
                    pos += 4 + length
                }
                blocks
            }
        }.getOrDefault(emptyList())

    private fun flacBlockName(type: Int): String =
        when (type) {
            0 -> "STREAMINFO"
            1 -> "PADDING"
            2 -> "APPLICATION"
            3 -> "SEEKTABLE"
            4 -> "VORBIS_COMMENT"
            5 -> "CUESHEET"
            6 -> "PICTURE"
            else -> "TYPE_$type"
        }

    // ── MP4 ────────────────────────────────────────────────────────────────

    private fun mp4(file: File): List<ContainerBlock> =
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val blocks = mutableListOf<ContainerBlock>()
                listBoxes(raf, 0, raf.length(), blocks, depth = 0)
                blocks
            }
        }.getOrDefault(emptyList())

    private fun listBoxes(
        raf: RandomAccessFile,
        start: Long,
        end: Long,
        out: MutableList<ContainerBlock>,
        depth: Int,
    ) {
        if (depth > 2) return
        var pos = start
        while (pos + 8 <= end && out.size < MAX_BLOCKS) {
            raf.seek(pos)
            val header = ByteArray(8)
            raf.readFully(header)
            var size =
                ((header[0].toInt() and 0xFF).toLong() shl 24) or
                    ((header[1].toInt() and 0xFF).toLong() shl 16) or
                    ((header[2].toInt() and 0xFF).toLong() shl 8) or
                    (header[3].toInt() and 0xFF).toLong()
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            var headerSize = 8L
            if (size == 1L) {
                // 64 位长度：先确认还有 8 字节可读，否则 readFully 会在文件尾截断时抛异常，
                // 把整个 MP4 布局清空
                if (pos + 16 > end) break
                val large = ByteArray(8)
                raf.readFully(large)
                size = 0
                for (b in large) size = (size shl 8) or (b.toLong() and 0xFF)
                headerSize = 16
            } else if (size == 0L) {
                size = end - pos
            }
            if (size < headerSize || pos + size > end) break
            out += block(type, size)
            if (type == "moov" || type == "trak" || type == "udta" || type == "meta") {
                // meta 是 full box：多 4 字节版本/标志
                val childStart = pos + headerSize + if (type == "meta") 4 else 0
                listBoxes(raf, childStart, pos + size, out, depth + 1)
            }
            pos += size
        }
    }

    // ── OGG / Opus ─────────────────────────────────────────────────────────

    /**
     * OGG / Opus 布局。
     *
     * **注释包不保证只占一页**：Vorbis / Opus 的 comment 包可以跨页（样本
     * `multipagecomment.ogg` 就是三页）。早先的实现假设「第二页正好是注释包」，
     * 于是跨页时块列表里的 `OpusTags` / `COMMENT` 只报了第一页的大小，
     * 剩下的页全被算进「Ogg pages」里，看不出注释到底占了多大。
     *
     * 因此这里按 Ogg 的段表（lacing）把页重组成包：段值 255 表示「这一包里
     * 还有下文」，小于 255 才是包的结尾。据此得到每个包的**真实字节数**和它
     * 跨了几页，再按包的顺序标成识别头 / 注释包。
     */
    private fun ogg(
        file: File,
        opus: Boolean,
    ): List<ContainerBlock> =
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val packetSizes = mutableListOf<Long>()
                val packetPages = mutableListOf<Int>()
                var pos = 0L
                var pages = 0
                var bytes = 0L
                // 正在拼接的包：已累计字节数、已占用页数
                var current = 0L
                var currentPages = 0
                while (pos + 27 <= raf.length() && pages < 50_000) {
                    raf.seek(pos)
                    val header = ByteArray(27)
                    raf.readFully(header)
                    if (String(header, 0, 4, Charsets.ISO_8859_1) != "OggS") break
                    val segments = header[26].toInt() and 0xFF
                    // 先确认段表与页体都在文件内：截断文件上 readFully 会抛 EOF，
                    // 外层 catch 会把已经解析出的包全部丢掉（保留比清空有用）
                    if (pos + 27 + segments > raf.length()) break
                    val table = ByteArray(segments)
                    raf.readFully(table)
                    var body = 0L
                    for (seg in table) body += (seg.toInt() and 0xFF)
                    val pageSize = 27L + segments + body
                    if (pos + pageSize > raf.length()) break
                    pages++
                    bytes += pageSize
                    pos += pageSize
                    if (segments == 0) continue
                    currentPages++
                    for (seg in table) {
                        val length = seg.toInt() and 0xFF
                        current += length
                        if (length < 255) {
                            packetSizes += current
                            packetPages += currentPages
                            current = 0
                            currentPages = 0
                        }
                    }
                }
                if (pages == 0) return emptyList()
                // 第 0 个包是识别头，第 1 个包是注释包（Vorbis 与 Opus 一致）
                val blocks = mutableListOf<ContainerBlock>()
                packetSizes.getOrNull(0)?.let { size ->
                    blocks += block(
                        if (opus) "OpusHead" else "ID header",
                        size,
                        pagesDetail(packetPages.getOrNull(0) ?: 1),
                    )
                }
                packetSizes.getOrNull(1)?.let { size ->
                    blocks += block(
                        if (opus) "OpusTags" else "COMMENT",
                        size,
                        pagesDetail(packetPages.getOrNull(1) ?: 1),
                    )
                }
                blocks += block("Ogg pages", bytes, "$pages pages")
                blocks
            }
        }.getOrDefault(emptyList())

    /** 跨页时才在块说明里写页数：单页是默认情形，写出来只是冗余。 */
    private fun pagesDetail(pages: Int): String = if (pages > 1) "$pages pages" else ""

    // ── APEv2（APE / WV / TAK / TTA / MPC）─────────────────────────────────

    private fun apev2(file: File): List<ContainerBlock> =
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                // **先扣掉尾部 ID3v1**：两种标签可以共存，ID3v1 固定占最后 128 字节，
                // APEv2 的 footer 就被挤到它前面。直接读 `length - 32` 拿到的是
                // ID3v1 的 COMMENT 区，`APETAGEX` 匹配不上 —— 带 ID3v1 的文件布局卡
                // 会永远缺 APEv2 行，而 [ApeV2.read] 实际读取时是扣掉的
                // （`searchEnd = fileLength - id3v1`），两边结论必须一致。
                val tail = if (hasId3v1(raf)) 128L else 0L
                if (raf.length() < tail + 32) return emptyList()
                raf.seek(raf.length() - tail - 32)
                val footer = ByteArray(32)
                raf.readFully(footer)
                if (String(footer, 0, 8, Charsets.ISO_8859_1) != "APETAGEX") return emptyList()
                val tagSize = le32(footer, 12)
                val items = le32(footer, 16)
                val header = (le32(footer, 20) and 0x80000000L) != 0L
                listOf(
                    block(
                        "APEv2",
                        tagSize + if (header) 32L else 0L,
                        "$items items" + if (header) " + header" else "",
                    ),
                )
            }
        }.getOrDefault(emptyList())

    private fun le32(
        buf: ByteArray,
        offset: Int,
    ): Long =
        (buf[offset].toLong() and 0xFF) or
            ((buf[offset + 1].toLong() and 0xFF) shl 8) or
            ((buf[offset + 2].toLong() and 0xFF) shl 16) or
            ((buf[offset + 3].toLong() and 0xFF) shl 24)

    // ── WAV / AIFF / DSF / DFF ─────────────────────────────────────────────

    private fun wav(file: File): List<ContainerBlock> =
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val head = ByteArray(12)
                raf.seek(0)
                raf.readFully(head)
                if (String(head, 0, 4, Charsets.ISO_8859_1) != "RIFF") return emptyList()
                chunkList(raf, 12, raf.length(), bigEndian = false)
            }
        }.getOrDefault(emptyList())

    private fun aiff(file: File): List<ContainerBlock> = iffWithId3(file, "FORM")

    private fun dff(file: File): List<ContainerBlock> = dffChunks(file)

    /**
     * IFF 容器（AIFF 的 `FORM`）：块链 + 块内 ID3v2。
     *
     * ID3v2 不在文件开头，而是作为一个 `ID3 ` 块嵌在块链里，
     * 所以先按块走，遇到 `ID3` 块再按它自己的头解析。
     *
     * AIFF 的块头是 **4 字节 ID + 4 字节大端长度**，共 8 字节；
     * DFF（DSDIFF）的块头是 **4 字节 ID + 8 字节大端长度**，共 12 字节，
     * 且 `FRM8` 之后还有 4 字节 formType。两者结构不同，
     * 因此 DFF 走独立的 [dffChunks]，不能与本函数共用偏移。
     */
    private fun iffWithId3(
        file: File,
        magic: String,
    ): List<ContainerBlock> =
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val head = ByteArray(12)
                raf.seek(0)
                raf.readFully(head)
                if (String(head, 0, 4, Charsets.ISO_8859_1) != magic) return emptyList()
                val blocks = mutableListOf<ContainerBlock>()
                var pos = 12L
                val end = raf.length()
                while (pos + 8 <= end && blocks.size < MAX_BLOCKS) {
                    raf.seek(pos)
                    val chunk = ByteArray(8)
                    raf.readFully(chunk)
                    val id = String(chunk, 0, 4, Charsets.ISO_8859_1)
                    val size = be32(chunk, 4)
                    if (size < 0 || pos + 8 + size > end) break
                    val dataOffset = pos + 8
                    if (id.trim() == "ID3") {
                        val id3 = id3v2At(file, dataOffset)
                        if (id3 != null) {
                            blocks += id3.copy(detail = id3.detail + " · offset=0x%X".format(dataOffset))
                        } else {
                            blocks += block(id.trim(), size + 8, "offset=0x%X".format(dataOffset))
                        }
                    } else {
                        blocks += block(id.trim(), size + 8)
                    }
                    val pad = if (size % 2L == 1L) 1L else 0L
                    pos += 8 + size + pad
                }
                blocks
            }
        }.getOrDefault(emptyList())

    /**
     * DFF（DSDIFF）块链：`FRM8` + 8 字节大端长度 + 4 字节 formType，之后是各块。
     *
     * 块头是 **4 字节 ID + 8 字节大端长度**（共 12 字节），与 AIFF 的 4+4 不同；
     * 起点是 16（跳过 `FRM8` 头与 formType），不是 12。
     * 按 AIFF 的偏移解析常规 DFF 会读到 formType 与块 ID，长度字段错位，
     * 首轮即判越界并返回空列表。
     */
    private fun dffChunks(file: File): List<ContainerBlock> =
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val end = raf.length()
                if (end < 16) return emptyList()
                raf.seek(0)
                val head = ByteArray(16)
                raf.readFully(head)
                if (String(head, 0, 4, Charsets.ISO_8859_1) != "FRM8") return emptyList()
                val blocks = mutableListOf<ContainerBlock>()
                blocks += block("FRM8", 16, "formType=" + String(head, 12, 4, Charsets.ISO_8859_1).trim())
                var pos = 16L
                while (pos + 12 <= end && blocks.size < MAX_BLOCKS) {
                    raf.seek(pos)
                    val chunk = ByteArray(12)
                    raf.readFully(chunk)
                    val id = String(chunk, 0, 4, Charsets.ISO_8859_1)
                    val size = be64(chunk, 4)
                    if (size < 0 || pos + 12 + size > end) break
                    val dataOffset = pos + 12
                    if (id.trim() == "ID3") {
                        val id3 = id3v2At(file, dataOffset)
                        if (id3 != null) {
                            blocks += id3.copy(detail = id3.detail + " · offset=0x%X".format(dataOffset))
                        } else {
                            blocks += block(id.trim(), size + 12, "offset=0x%X".format(dataOffset))
                        }
                    } else {
                        blocks += block(id.trim(), size + 12)
                    }
                    val pad = if (size % 2L == 1L) 1L else 0L
                    pos += 12 + size + pad
                }
                blocks
            }
        }.getOrDefault(emptyList())

    /** 8 字节大端整数（DSDIFF 的长度字段）。 */
    private fun be64(
        buf: ByteArray,
        offset: Int,
    ): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (buf[offset + i].toLong() and 0xFF)
        return v
    }

    private fun be32(
        buf: ByteArray,
        offset: Int,
    ): Long =
        ((buf[offset].toLong() and 0xFF) shl 24) or
            ((buf[offset + 1].toLong() and 0xFF) shl 16) or
            ((buf[offset + 2].toLong() and 0xFF) shl 8) or
            (buf[offset + 3].toLong() and 0xFF)

    private fun dsf(file: File): List<ContainerBlock> =
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val head = ByteArray(28)
                raf.seek(0)
                raf.readFully(head)
                if (String(head, 0, 4, Charsets.ISO_8859_1) != "DSD ") return emptyList()
                val blocks = mutableListOf<ContainerBlock>()
                blocks += block("DSD header", 28)
                // 偏移 20 处的 8 字节是 ID3v2 元数据指针（0 表示没有）
                val metadataOffset = le64(head, 20)
                if (metadataOffset > 0 && metadataOffset < raf.length()) {
                    val id3 = id3v2At(file, metadataOffset)
                    if (id3 != null) {
                        blocks += id3.copy(detail = id3.detail + " · offset=0x%X".format(metadataOffset))
                    }
                }
                var pos = 28L
                while (pos + 12 <= raf.length() && blocks.size < MAX_BLOCKS) {
                    raf.seek(pos)
                    val chunk = ByteArray(12)
                    raf.readFully(chunk)
                    val id = String(chunk, 0, 4, Charsets.ISO_8859_1)
                    val size = le64(chunk, 4)
                    if (size < 12) break
                    blocks += block(id.trim(), size)
                    pos += size
                }
                blocks
            }
        }.getOrDefault(emptyList())

    private fun le64(
        buf: ByteArray,
        offset: Int,
    ): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (buf[offset + i].toLong() and 0xFF)
        return v
    }

    /** RIFF / IFF 通用块遍历：4 字节 ID + 4 字节长度（大小端由格式决定）。 */
    private fun chunkList(
        raf: RandomAccessFile,
        start: Long,
        end: Long,
        bigEndian: Boolean,
    ): List<ContainerBlock> {
        val blocks = mutableListOf<ContainerBlock>()
        var pos = start
        while (pos + 8 <= end && blocks.size < MAX_BLOCKS) {
            raf.seek(pos)
            val chunk = ByteArray(8)
            raf.readFully(chunk)
            val id = String(chunk, 0, 4, Charsets.ISO_8859_1)
            val size =
                if (bigEndian) {
                    ((chunk[4].toLong() and 0xFF) shl 24) or
                        ((chunk[5].toLong() and 0xFF) shl 16) or
                        ((chunk[6].toLong() and 0xFF) shl 8) or
                        (chunk[7].toLong() and 0xFF)
                } else {
                    (chunk[4].toLong() and 0xFF) or
                        ((chunk[5].toLong() and 0xFF) shl 8) or
                        ((chunk[6].toLong() and 0xFF) shl 16) or
                        ((chunk[7].toLong() and 0xFF) shl 24)
                }
            if (size < 0 || pos + 8 + size > end) break
            blocks += block(id.trim(), size + 8)
            // 奇数字节长度的块后面补 1 字节对齐。RIFF 与 IFF 都有这条规则，
            // 只对小端（RIFF）不补会让 LIST/id3 等奇数块之后的每个块都从错位处读。
            val pad = if (size % 2L == 1L) 1L else 0L
            pos += 8 + size + pad
        }
        return blocks
    }

    // ── ASF（WMA）──────────────────────────────────────────────────────────

    private fun asf(file: File): List<ContainerBlock> =
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val head = ByteArray(30)
                raf.seek(0)
                raf.readFully(head)
                if (head[0] != 0x30.toByte() || head[1] != 0x26.toByte()) return emptyList()
                val headerSize = le64(head, 16)
                val objectCount = le32(head, 24)
                listOf(block("ASF header", headerSize, "$objectCount objects"))
            }
        }.getOrDefault(emptyList())
}
