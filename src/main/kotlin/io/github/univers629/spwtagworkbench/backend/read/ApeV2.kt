// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.read

import java.io.File
import java.io.RandomAccessFile

/**
 * APEv2 标签读写。
 *
 * 标签位于文件**末尾**：`[音频数据][标签头?][条目…][标签尾]`，每段都是 32 字节的
 * `APETAGEX` 结构。真实文件里两种布局都存在：只有尾（`luckynight.ape`）与
 * 头 + 尾（`silence-44-s.wv`），读取必须都支持。
 *
 * 条目结构：`valueLength(4) + flags(4) + key(以 0 结尾) + value(valueLength 字节)`，
 * 键与值都是 UTF-8。封面存在 `Cover Art (Front)` 键里，值是「文件名 0 图片字节」。
 */
object ApeV2 {
    const val FOOTER = "APETAGEX"
    private const val FOOTER_SIZE = 32

    /** 标签尾部标志位。 */
    private const val FLAG_HAS_HEADER = 0x80000000L
    private const val FLAG_NO_FOOTER = 0x40000000L
    private const val FLAG_IS_HEADER = 0x20000000L

    /** 条目标志。 */
    private const val ITEM_READ_ONLY = 0x00000001L
    private const val ITEM_BINARY = 0x00000002L

    /** 一个标签条目。 */
    class Item(
        val key: String,
        val value: ByteArray,
        val flags: Int = 0,
    ) {
        val isBinary: Boolean get() = (flags.toLong() and ITEM_BINARY) != 0L

        fun text(): String = String(value, Charsets.UTF_8)

        override fun toString(): String = "$key=${if (isBinary) "<binary ${value.size}B>" else text()}"
    }

    /** 读完的标签。 */
    class Tag(
        val items: List<Item>,
        /** 标签起始偏移（若有头部，指向头部而不是条目）。 */
        val tagStart: Long,
        val tagEnd: Long,
        val hasHeader: Boolean,
    ) {
        fun item(key: String): Item? = items.firstOrNull { it.key.equals(key, ignoreCase = true) }

        fun text(key: String): String? = item(key)?.takeIf { !it.isBinary }?.text()?.takeIf { it.isNotBlank() }

        /** 音频数据的结束位置（标签之前）。 */
        val audioEnd: Long get() = tagStart
    }

    /** 定位并解析标签；没有标签返回 null。 */
    fun read(file: File): Tag? =
        try {
            RandomAccessFile(file, "r").use { raf -> read(raf) }
        } catch (_: Throwable) {
            null
        }

    private fun read(raf: RandomAccessFile): Tag? {
        val fileLength = raf.length()
        if (fileLength < FOOTER_SIZE) return null
        // ID3v1 可能排在 APEv2 之后（最后 128 字节）
        var id3v1 = 0L
        if (fileLength >= 128 + FOOTER_SIZE) {
            raf.seek(fileLength - 128)
            val tag = ByteArray(3)
            raf.read(tag, 0, 3)
            if (String(tag, Charsets.ISO_8859_1) == "TAG") id3v1 = 128
        }
        val searchEnd = fileLength - id3v1
        if (searchEnd < FOOTER_SIZE) return null

        // 先从**文件末尾**读 32 字节尾部结构，取它自报的 size 精确定位标签区，
        // 再按需读入。此前是在末尾 256KB 窗口里搜 "APETAGEX" 字符串，
        // 两个后果：
        //   1) 封面超过 ~256KB 的文件整体读不出标签（窗口里找不到尾部结构）；
        //   2) 音频数据里若恰好出现 "APETAGEX" 字节序列，会被当成标签起点。
        // 尾部结构按规范就在最后 32 字节，不需要搜索。
        raf.seek(searchEnd - FOOTER_SIZE)
        val footer = ByteArray(FOOTER_SIZE)
        if (raf.read(footer, 0, FOOTER_SIZE) != FOOTER_SIZE) return null
        if (String(footer, 0, 8, Charsets.ISO_8859_1) != FOOTER) return null

        val size = u32(footer, 12)
        val count = u32(footer, 16)
        val flags = u32(footer, 20)
        // `size` = 全部条目 + 尾部，**不含可选头部**（APEv2 规范）。
        if (size < FOOTER_SIZE || size > searchEnd) return null

        val tagEnd = searchEnd
        // 条目区起点 = 末尾倒退 size
        val itemsStart = tagEnd - size
        val itemsEnd = tagEnd - FOOTER_SIZE
        // 有头部时，头部紧贴在条目之前；audioEnd 要含头部，否则会把它当音频留下
        val hasHeader = (flags and FLAG_IS_HEADER) == 0L && (flags and FLAG_HAS_HEADER) != 0L
        val tagStart = if (hasHeader) itemsStart - FOOTER_SIZE else itemsStart
        if (tagStart < 0 || itemsEnd < itemsStart) return null

        val buf = ByteArray((tagEnd - tagStart).toInt())
        raf.seek(tagStart)
        if (raf.read(buf, 0, buf.size) < FOOTER_SIZE) return null

        val items = mutableListOf<Item>()
        var p = (itemsStart - tagStart).toInt()
        val end = (itemsEnd - tagStart).toInt()
        repeat(count.toInt().coerceAtMost(MAX_ITEMS)) {
            if (p + 8 > end) return@repeat
            val valueLength = u32(buf, p).toInt()
            val itemFlags = u32(buf, p + 4).toInt()
            p += 8
            if (valueLength < 0 || p >= end) return@repeat
            val keyEnd = (p until end).firstOrNull { buf[it] == 0.toByte() } ?: return@repeat
            val key = String(buf, p, keyEnd - p, Charsets.UTF_8)
            p = keyEnd + 1
            if (valueLength > end - p) return@repeat
            val value = buf.copyOfRange(p, p + valueLength)
            p += valueLength
            items += Item(key, value, itemFlags)
        }
        return Tag(
            items = items,
            tagStart = tagStart,
            tagEnd = tagEnd,
            hasHeader = hasHeader,
        )
    }

    /**
     * 把标签写到 [file] 末尾（覆盖已有 APEv2，保留其后的 ID3v1）。
     *
     * 调用方负责「在临时副本上操作」，因此这里直接截断重写，不需要原子性。
     *
     * **音频区按字节搬运，不整块读进内存**：APE / WV / TAK 常见 0.5~4GB，
     * 读成 `ByteArray` 会占用同等内存，且 [Long] 到 [Int] 的转换对 ≥2GiB 的文件
     * 会溢出成负数（抛异常）或回绕成小值 —— 后者更危险：`setLength` 会把临时副本
     * 截断到几 MB，而回读校验只验标签、必然通过，于是截断后的文件被当成写入成功
     * 替换原文件，音频永久丢失。
     */
    fun write(
        file: File,
        values: Map<String, ByteArray?>,
        removeKeys: Collection<String> = emptyList(),
    ) {
        val existing = read(file)
        val merged = LinkedHashMap<String, Item>()
        existing?.items?.forEach { merged[it.key.lowercase()] = it }
        for ((key, value) in values) {
            val k = key.lowercase()
            if (value == null) merged.remove(k) else merged[k] = Item(key, value)
        }
        for (key in removeKeys) merged.remove(key.lowercase())
        val items = merged.values.toList()
        val payload = buildItems(items)
        val footer = buildFooter(payload.size, items.size, hasHeader = false)
        val tagBytes = payload + footer

        // 保留标签之后的 ID3v1
        var id3v1: ByteArray? = null
        if (file.length() >= 128) {
            val tail = ByteArray(128)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(file.length() - 128)
                raf.read(tail, 0, 128)
            }
            if (String(tail, 0, 3, Charsets.ISO_8859_1) == "TAG") id3v1 = tail
        }
        // 音频区结束位置：优先用已解析的标签起点；解析不出标签时，
        // 末尾若确实是 APEv2 尾部结构，也必须回推边界 —— 否则旧标签会被当成音频保留，
        // 文件变成「音频 + 旧标签垃圾 + 新标签」，且回读只验末尾新标签、判定成功。
        val withoutId3v1 = file.length() - (id3v1?.size ?: 0)
        val audioEnd = existing?.audioEnd ?: trailingTagStart(file, withoutId3v1)

        // 逐块搬运音频区：不做 Long→Int 转换，任意大小都安全
        val source = RandomAccessFile(file, "r")
        val target = RandomAccessFile(file, "rw")
        try {
            source.seek(0)
            target.setLength(audioEnd)
            target.seek(0)
            val buf = ByteArray(COPY_CHUNK)
            var remaining = audioEnd
            while (remaining > 0) {
                val want = minOf(buf.size.toLong(), remaining).toInt()
                val n = source.read(buf, 0, want)
                if (n <= 0) break
                target.write(buf, 0, n)
                remaining -= n
            }
            target.seek(audioEnd)
            target.write(tagBytes)
            id3v1?.let { target.write(it) }
        } finally {
            runCatching { source.close() }
            runCatching { target.close() }
        }
    }

    /** 搬运音频区的缓冲大小。 */
    private const val COPY_CHUNK = 1 shl 20

    /**
     * 末尾是 APEv2 尾部结构时，回推出音频区结束位置；否则返回 [end]。
     *
     * 用于 [write] 里 [read] 失败（例如标签结构损坏到解析不出条目）的情形：
     * 此时仍必须把旧标签区排除在音频之外，否则旧标签会被当成音频保留下来。
     */
    private fun trailingTagStart(
        file: File,
        end: Long,
    ): Long {
        val footerStart = end - FOOTER_SIZE
        if (footerStart < 0) return end
        val probe = ByteArray(FOOTER_SIZE)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(footerStart)
            if (raf.read(probe, 0, FOOTER_SIZE) < FOOTER_SIZE) return end
        }
        if (String(probe, 0, 8, Charsets.ISO_8859_1) != FOOTER) return end
        val size = u32(probe, 12)
        val flags = u32(probe, 20)
        if (size < FOOTER_SIZE || size > end) return end
        val hasHeader = (flags and FLAG_IS_HEADER) == 0L && (flags and FLAG_HAS_HEADER) != 0L
        val headerSize = if (hasHeader) FOOTER_SIZE.toLong() else 0L
        val start = end - size - headerSize
        return if (start < 0) end else start
    }

    /** 单个标签最多解析的条目数：防止损坏的 count 字段导致长时间循环。 */
    private const val MAX_ITEMS = 100_000

    private fun buildItems(items: List<Item>): ByteArray {
        var size = 0
        for (item in items) size += 8 + item.key.toByteArray(Charsets.UTF_8).size + 1 + item.value.size
        val out = java.io.ByteArrayOutputStream(size)
        for (item in items) {
            val key = item.key.toByteArray(Charsets.UTF_8)
            // APEv2 的整数字段是**小端**
            out.write(le32(item.value.size))
            out.write(le32(item.flags))
            out.write(key)
            out.write(0)
            out.write(item.value)
        }
        return out.toByteArray()
    }

    private fun buildFooter(
        payloadSize: Int,
        itemCount: Int,
        hasHeader: Boolean,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream(FOOTER_SIZE)
        out.write(FOOTER.toByteArray(Charsets.ISO_8859_1))
        out.write(le32(2000)) // version，小端
        out.write(le32(payloadSize + FOOTER_SIZE))
        out.write(le32(itemCount))
        out.write(le32(if (hasHeader) FLAG_HAS_HEADER.toInt() else 0))
        out.write(ByteArray(8))
        return out.toByteArray()
    }

    private fun le32(value: Int): ByteArray =
        byteArrayOf(
            (value and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 24) and 0xFF).toByte(),
        )

    private fun u32(
        b: ByteArray,
        o: Int,
    ): Long =
        ((b[o].toLong() and 0xFF) shl 0) or
            ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or
            ((b[o + 3].toLong() and 0xFF) shl 24)

    /** 从 `Cover Art (Front)` 的值里剥掉文件名前缀（值是「文件名 0 图片字节」）。 */
    fun coverBytes(value: ByteArray): ByteArray {
        val sep = value.indexOf(0)
        return if (sep in 0 until value.size - 1) value.copyOfRange(sep + 1, value.size) else value
    }

    /** 标准键名。 */
    object Key {
        const val TITLE = "Title"
        const val ARTIST = "Artist"
        const val ALBUM = "Album"
        const val ALBUM_ARTIST = "Album Artist"
        const val GENRE = "Genre"
        const val YEAR = "Year"
        const val TRACK = "Track"
        const val DISC = "Disc"
        const val COMMENT = "Comment"
        const val LYRICS = "Lyrics"
        const val LYRICIST = "Lyricist"
        const val COMPOSER = "Composer"
        const val COVER_FRONT = "Cover Art (Front)"
    }

    /** 未使用的常量：保留给后续「只读/二进制」标记逻辑。 */
    private const val UNUSED_ITEM_FLAGS = ITEM_READ_ONLY
}
