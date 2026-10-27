// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.read

import java.io.File
import java.io.RandomAccessFile

/**
 * Ogg / Opus 注释读写。
 *
 * Ogg 是「页」结构：每页 27 字节固定头 + 段表 + 负载，页与页之间靠序号与 CRC 串起来。
 * Opus 的标签在第二个逻辑包 `OpusTags` 里，结构等同 Vorbis comment：
 * `"OpusTags" + vendorLength(4LE) + vendor + count(4LE) + (length(4LE) + "KEY=value")*`。
 *
 * 封面按惯例放在 `METADATA_BLOCK_PICTURE` 键，值是 FLAC PICTURE 块的 Base64。
 *
 * 写入策略：把承载注释的页区间按新内容**重新分页**（支持跨多页），
 * 后续页只改序号并重算 CRC，音频包一个字节都不动。
 */
object OggTags {
    private const val MAX_PAGE_PAYLOAD = 255 * 255

    /** 一条注释。 */
    class Comment(
        val values: List<Pair<String, String>>,
    ) {
        fun text(key: String): String? = values.firstOrNull { it.first.equals(key, ignoreCase = true) }?.second?.takeIf { it.isNotBlank() }

        /** 同名键的全部值（封面会重复出现）。 */
        fun texts(key: String): List<String> = values.filter { it.first.equals(key, ignoreCase = true) }.map { it.second }

        fun all(): Map<String, String> = values.toMap()
    }

    /** 一张内嵌图片。 */
    class Picture(
        val type: Int,
        val mime: String,
        val bytes: ByteArray,
    )

    /** Ogg 页头。 */
    private class Page(
        val offset: Int,
        val headerLen: Int,
        val totalLen: Int,
        val serial: Int,
        val sequence: Int,
        val granule: Long,
        val type: Int,
        val lacing: IntArray,
        val payloadStart: Int,
    ) {
        val payloadLen: Int get() = lacing.sum()

        /** 该页最后一个包是否在此页结束（最后一段 < 255）。 */
        val endsWithPacketBoundary: Boolean get() = lacing.isNotEmpty() && lacing.last() < 255
    }

    /**
     * 读标签时最多扫多少字节。
     *
     * 注释包必然在文件开头几页，但它自身可能很大（封面以 Base64 塞进
     * `METADATA_BLOCK_PICTURE`，几十 KB 到几 MB 都见过），所以上限取 8 MiB。
     * 关键是**不能**像写路径那样整份读：一首 100 MB 的 Opus 只为读标题就
     * 要读 100 MB，而曲目扫描对每个文件都会调到这里。
     */
    private const val MAX_READ_BYTES = 8L * 1024 * 1024

    /** 读取注释；不是 Ogg/Opus 或没有注释时返回 null。 */
    fun read(file: File): Comment? =
        try {
            RandomAccessFile(file, "r").use { raf -> readNote(raf) }
        } catch (_: Throwable) {
            null
        }

    /**
     * 只扫开头若干页找 `OpusTags` 包。
     *
     * 按段表（lacing）重组逻辑包：段值 255 表示包未结束、要到后续页继续拼，
     * 小于 255 才是包的结尾 —— 因此跨页的注释包（封面较大时很常见）也能拼完整。
     *
     * 写完就走：[write] 仍然整份读入，因为它要重排页并重算 CRC；读标签不需要。
     */
    private fun readNote(raf: RandomAccessFile): Comment? {
        val length = raf.length()
        var pos = 0L
        var consumed = 0L
        var pending = java.io.ByteArrayOutputStream()
        while (pos + 27 <= length && consumed < MAX_READ_BYTES) {
            raf.seek(pos)
            val header = ByteArray(27)
            raf.readFully(header)
            if (header[0] != 'O'.code.toByte() ||
                header[1] != 'g'.code.toByte() ||
                header[2] != 'g'.code.toByte() ||
                header[3] != 'S'.code.toByte()
            ) {
                return null
            }
            val segments = header[26].toInt() and 0xFF
            if (pos + 27 + segments > length) return null
            val lacing = ByteArray(segments)
            raf.readFully(lacing)
            var payloadLen = 0
            for (seg in lacing) payloadLen += seg.toInt() and 0xFF
            val pageSize = 27L + segments + payloadLen
            if (pos + pageSize > length) return null
            val payload = ByteArray(payloadLen)
            if (payloadLen > 0) raf.readFully(payload)
            var offset = 0
            for (seg in lacing) {
                val lengthInPage = seg.toInt() and 0xFF
                pending.write(payload, offset, lengthInPage)
                offset += lengthInPage
                if (lengthInPage < 255) {
                    val packet = pending.toByteArray()
                    pending = java.io.ByteArrayOutputStream()
                    if (startsWithAscii(packet, "OpusTags")) return parseComment(packet)
                }
            }
            consumed += pageSize
            pos += pageSize
        }
        return null
    }

    /** 读取全部图片（`METADATA_BLOCK_PICTURE`，按类型去重）。 */
    fun pictures(file: File): List<Picture> {
        val comment = read(file) ?: return emptyList()
        return comment.texts("METADATA_BLOCK_PICTURE").mapNotNull { decodePictureBlock(it) }
    }

    /**
     * 写入/删除注释键。
     *
     * @param values 键 → 值（null 表示删除）；同键多值请用 [pictures]
     * @param pictures 不为 null 时整体替换所有 `METADATA_BLOCK_PICTURE`
     */
    fun write(
        file: File,
        values: Map<String, String?>,
        pictures: List<Picture>? = null,
    ) {
        val bytes = file.readBytes()
        val pages = parsePages(bytes) ?: throw IllegalStateException("不是可识别的 Ogg 容器")
        val range = packetPageIndex(bytes, pages) ?: throw IllegalStateException("找不到 OpusTags 注释包")
        val firstPageIndex = range.first
        val pageCount = range.second
        val current = readCommentFromPages(bytes, pages, firstPageIndex, pageCount).toMutableList()
        for ((key, value) in values) {
            current.removeAll { it.first.equals(key, ignoreCase = true) }
            if (value != null) current += key to value
        }
        if (pictures != null) {
            current.removeAll { it.first.equals("METADATA_BLOCK_PICTURE", ignoreCase = true) }
            pictures.forEach { pic ->
                current += "METADATA_BLOCK_PICTURE" to buildPictureValue(pic.type, pic.mime, pic.bytes)
            }
        }
        val payload = buildComment(current)
        val lastPage = pages[firstPageIndex + pageCount - 1]
        if (!lastPage.endsWithPacketBoundary && pageCount != 1) {
            throw IllegalStateException("注释包与音频包共用页面，暂不支持重排")
        }
        val newPages = repackPages(pages[firstPageIndex], payload)
        val delta = newPages.size - pageCount
        val out = java.io.ByteArrayOutputStream(bytes.size + 4096)
        out.write(bytes, 0, pages[firstPageIndex].offset)
        newPages.forEach { out.write(it) }
        for (i in (firstPageIndex + pageCount) until pages.size) {
            val page = pages[i]
            val raw = bytes.copyOfRange(page.offset, page.offset + page.totalLen)
            if (delta != 0) putLe32(raw, 18, page.sequence + delta)
            putLe32(raw, 22, 0)
            putLe32(raw, 22, crc32(raw))
            out.write(raw)
        }
        file.writeBytes(out.toByteArray())
    }

    /** FLAC PICTURE 块 → Base64，用于写 `METADATA_BLOCK_PICTURE`。 */
    fun buildPictureValue(
        type: Int,
        mime: String,
        bytes: ByteArray,
    ): String {
        val mimeBytes = mime.toByteArray(Charsets.ISO_8859_1)
        val out = java.io.ByteArrayOutputStream(bytes.size + 64)

        fun be32(v: Int) {
            out.write(((v ushr 24) and 0xFF))
            out.write(((v ushr 16) and 0xFF))
            out.write(((v ushr 8) and 0xFF))
            out.write(v and 0xFF)
        }
        be32(type)
        be32(mimeBytes.size)
        out.write(mimeBytes)
        be32(0) // 描述长度
        be32(0) // 宽
        be32(0) // 高
        be32(0) // 色深
        be32(0) // 颜色数
        be32(bytes.size)
        out.write(bytes)
        return java.util.Base64
            .getEncoder()
            .encodeToString(out.toByteArray())
    }

    // ── 图片解码 ────────────────────────────────────────────────────────────

    private fun decodePictureBlock(encoded: String): Picture? {
        val block =
            try {
                java.util.Base64
                    .getDecoder()
                    .decode(encoded)
            } catch (_: Throwable) {
                return null
            }
        if (block.size < 32) return null
        var p = 0

        fun be32(): Int {
            val v = ((block[p].toInt() and 0xFF) shl 24) or
                ((block[p + 1].toInt() and 0xFF) shl 16) or
                ((block[p + 2].toInt() and 0xFF) shl 8) or
                (block[p + 3].toInt() and 0xFF)
            p += 4
            return v
        }
        val type = be32()
        val mimeLen = be32()
        if (mimeLen < 0 || p + mimeLen > block.size) return null
        val mime = String(block, p, mimeLen, Charsets.ISO_8859_1)
        p += mimeLen
        val descLen = be32()
        if (descLen < 0 || p + descLen > block.size) return null
        p += descLen
        p += 16 // 宽、高、深、颜色数
        val dataLen = be32()
        if (dataLen <= 0 || p + dataLen > block.size) return null
        return Picture(type = type, mime = mime, bytes = block.copyOfRange(p, p + dataLen))
    }

    // ── 页解析 ───────────────────────────────────────────────────────────────

    private fun startsWithAscii(
        packet: ByteArray,
        prefix: String,
    ): Boolean = packet.size >= prefix.length && (0 until prefix.length).all { packet[it] == prefix[it].code.toByte() }

    private fun parsePages(bytes: ByteArray): List<Page>? {
        val pages = mutableListOf<Page>()
        var p = 0
        while (p + 27 <= bytes.size) {
            if (bytes[p] != 'O'.code.toByte() ||
                bytes[p + 1] != 'g'.code.toByte() ||
                bytes[p + 2] != 'g'.code.toByte() ||
                bytes[p + 3] != 'S'.code.toByte()
            ) {
                return null
            }
            val type = bytes[p + 5].toInt() and 0xFF
            val granule = le64(bytes, p + 6)
            val serial = le32(bytes, p + 14)
            val seq = le32(bytes, p + 18)
            val segmentCount = bytes[p + 26].toInt() and 0xFF
            if (p + 27 + segmentCount > bytes.size) return null
            val lacing = IntArray(segmentCount) { bytes[p + 27 + it].toInt() and 0xFF }
            val payloadLen = lacing.sum()
            val payloadStart = p + 27 + segmentCount
            if (payloadStart + payloadLen > bytes.size) return null
            pages += Page(p, 27 + segmentCount, 27 + segmentCount + payloadLen, serial, seq, granule, type, lacing, payloadStart)
            p += 27 + segmentCount + payloadLen
        }
        return if (pages.isEmpty()) null else pages
    }

    /** 返回（注释包起始页索引，跨越页数）。 */
    private fun packetPageIndex(
        bytes: ByteArray,
        pages: List<Page>,
    ): Pair<Int, Int>? {
        var pending = java.io.ByteArrayOutputStream()
        var startPage = -1
        for ((index, page) in pages.withIndex()) {
            var offset = page.payloadStart
            for (seg in page.lacing) {
                if (pending.size() == 0) startPage = index
                pending.write(bytes, offset, seg)
                offset += seg
                if (seg < 255) {
                    val packet = pending.toByteArray()
                    if (startsWithAscii(packet, "OpusTags")) {
                        return startPage to (index - startPage + 1)
                    }
                    pending = java.io.ByteArrayOutputStream()
                    startPage = -1
                }
            }
        }
        return null
    }

    private fun readCommentFromPages(
        bytes: ByteArray,
        pages: List<Page>,
        firstPageIndex: Int,
        pageCount: Int,
    ): List<Pair<String, String>> {
        val out = java.io.ByteArrayOutputStream()
        for (i in firstPageIndex until minOf(firstPageIndex + pageCount, pages.size)) {
            val page = pages[i]
            out.write(bytes, page.payloadStart, page.payloadLen)
        }
        return parseComment(out.toByteArray())?.values.orEmpty()
    }

    /** 把一段负载重新分页；第一页继承模板页的序号与类型。 */
    private fun repackPages(
        template: Page,
        payload: ByteArray,
    ): List<ByteArray> {
        val chunks = mutableListOf<ByteArray>()
        var p = 0
        while (p < payload.size) {
            val size = minOf(MAX_PAGE_PAYLOAD, payload.size - p)
            chunks += payload.copyOfRange(p, p + size)
            p += size
        }
        if (chunks.isEmpty()) chunks += ByteArray(0)
        return chunks.mapIndexed { index, chunk ->
            val lacing = mutableListOf<Int>()
            var remaining = chunk.size
            while (remaining >= 255) {
                lacing += 255
                remaining -= 255
            }
            // 最后一页必须落一个 < 255 的段来表示包结束；中间页若整除 255，
            // 不能补 0（那会被当成空包，导致包被提前截断）
            if (remaining > 0 || index == chunks.size - 1) {
                lacing += remaining
            }
            if (lacing.isEmpty()) lacing += 0
            val headerLen = 27 + lacing.size
            val page = ByteArray(headerLen + chunk.size)
            page[0] = 'O'.code.toByte()
            page[1] = 'g'.code.toByte()
            page[2] = 'g'.code.toByte()
            page[3] = 'S'.code.toByte()
            page[4] = 0
            // 第一页沿用模板页类型；续页带 continuation 标志
            page[5] = (if (index == 0) template.type and 0xFE else template.type or 0x01).toByte()
            putLe64(page, 6, 0) // 注释页 granule 为 0
            putLe32(page, 14, template.serial)
            putLe32(page, 18, template.sequence + index)
            page[26] = lacing.size.toByte()
            lacing.forEachIndexed { i, v -> page[27 + i] = v.toByte() }
            chunk.copyInto(page, headerLen)
            putLe32(page, 22, 0)
            putLe32(page, 22, crc32(page))
            page
        }
    }

    // ── 注释包解析/构造 ──────────────────────────────────────────────────────

    private fun parseComment(packet: ByteArray): Comment? {
        if (!startsWithAscii(packet, "OpusTags")) return null
        var p = 8
        if (p + 4 > packet.size) return null
        val vendorLen = le32(packet, p)
        p += 4 + vendorLen
        if (p + 4 > packet.size) return null
        val count = le32(packet, p)
        p += 4
        val values = mutableListOf<Pair<String, String>>()
        repeat(count.coerceAtMost(100_000)) {
            if (p + 4 > packet.size) return@repeat
            val len = le32(packet, p)
            p += 4
            if (len < 0 || p + len > packet.size) return@repeat
            val text = String(packet, p, len, Charsets.UTF_8)
            p += len
            val eq = text.indexOf('=')
            if (eq > 0) values += text.substring(0, eq) to text.substring(eq + 1)
        }
        return Comment(values)
    }

    private fun buildComment(values: List<Pair<String, String>>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write("OpusTags".toByteArray(Charsets.ISO_8859_1))
        val vendor = "SPW-Tag-Workbench".toByteArray(Charsets.UTF_8)
        out.write(le32(vendor.size))
        out.write(vendor)
        out.write(le32(values.size))
        for ((key, value) in values) {
            val entry = "$key=$value".toByteArray(Charsets.UTF_8)
            out.write(le32(entry.size))
            out.write(entry)
        }
        return out.toByteArray()
    }

    // ── 小工具 ───────────────────────────────────────────────────────────────

    private fun le32(
        b: ByteArray,
        o: Int,
    ): Int =
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun le64(
        b: ByteArray,
        o: Int,
    ): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }

    private fun le32(v: Int): ByteArray =
        byteArrayOf(
            (v and 0xFF).toByte(),
            ((v ushr 8) and 0xFF).toByte(),
            ((v ushr 16) and 0xFF).toByte(),
            ((v ushr 24) and 0xFF).toByte(),
        )

    private fun putLe32(
        b: ByteArray,
        o: Int,
        v: Int,
    ) {
        le32(v).copyInto(b, o)
    }

    private fun putLe64(
        b: ByteArray,
        o: Int,
        v: Long,
    ) {
        for (i in 0 until 8) b[o + i] = ((v ushr (8 * i)) and 0xFF).toByte()
    }

    /** Ogg 使用的 CRC-32（多项式 0x04C11DB7，不反射，初值 0，无最终异或）。 */
    internal fun crc32(data: ByteArray): Int {
        var crc = 0
        for (byte in data) {
            crc = (crc shl 8) xor TABLE[((crc ushr 24) xor (byte.toInt() and 0xFF)) and 0xFF]
        }
        return crc
    }

    private val TABLE =
        IntArray(256) { i ->
            var r = i shl 24
            repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
            r
        }
}
