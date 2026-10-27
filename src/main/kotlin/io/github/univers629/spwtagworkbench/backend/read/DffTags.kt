// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.read

import java.io.File
import java.io.RandomAccessFile

/**
 * DFF（DSDIFF）标签读写。
 *
 * 文件是「表单 + 块」结构：`FRM8` + 8 字节大端长度 + 表单类型 `DSD `，
 * 之后是若干块：`FVER`、`PROP`（内含 `FS`/`CHNL`/`CMPR` 等子块）、`DSD`（音频）。
 * 块头 = 4 字节 ID + 8 字节大端长度，负载按偶数对齐补 1 字节。
 *
 * 标签是 `ID3 ` 块，规范允许两种位置：根层（最常见）或 `PROP` 内部。
 * 写入时统一重建：删掉两处的旧 `ID3 `，在末尾追加一份根层 `ID3 `，并修正
 * `FRM8` 与 `PROP` 的长度——音频块原样保留。
 *
 * **音频区按块搬运，不整文件读入内存**：DFF 常见 0.5~4GB，
 * `readBytes()` 会一次性分配同等大小的数组，1~2GB 即有明显 OOM 风险。
 * 这里只把需要改写的块（`ID3 `、`PROP`）读进内存，`DSD` 等大块用流式拷贝转发。
 */
object DffTags {
    private const val FORM_ID = "FRM8"
    private const val ID3_ID = "ID3 "
    private const val CHUNK_HEADER = 12

    /** 解析结果：标签 + 音频数据范围（供上层断言「音频未被改动」）。 */
    class Parsed(
        val tag: Id3v2.Tag?,
        val fileSize: Long,
        val formSize: Long,
    )

    /** 读标签（根层优先，其次 PROP 内部）。 */
    fun read(file: File): Id3v2.Tag? =
        try {
            RandomAccessFile(file, "r").use { raf -> readTag(raf) }
        } catch (_: Throwable) {
            null
        }

    private fun readTag(raf: RandomAccessFile): Id3v2.Tag? {
        val end = raf.length()
        if (end < 16) return null
        val head = ByteArray(16)
        raf.seek(0)
        if (raf.read(head, 0, 16) < 16) return null
        if (String(head, 0, 4, Charsets.ISO_8859_1) != FORM_ID) return null
        val formSize = be64(head, 4)
        val formEnd = (CHUNK_HEADER + formSize).coerceAtMost(end)

        var tagBytes: ByteArray? = null
        walkChunks(raf, 16L, formEnd) { id, dataOffset, dataSize ->
            if (id == ID3_ID && tagBytes == null) {
                // 只读标签块本身（通常几十 KB）
                tagBytes = readFully(raf, dataOffset, dataSize.toInt())
            }
            if (id == "PROP" && tagBytes == null) {
                val prop = readFully(raf, dataOffset, dataSize.toInt())
                if (prop != null) {
                    val inner = parseProp(prop)?.firstOrNull { it.id == ID3_ID }
                    if (inner != null) tagBytes = inner.data
                }
            }
        }
        return tagBytes?.let { Id3v2.readAt(it, 0) }
    }

    /**
     * 以流式方式重建整个文件：转发其余块、重建 `FRM8` 与 `PROP` 长度，末尾追加新的
     * 根层 `ID3 `。
     *
     * **必须写到独立临时文件再替换**：源与目标不能是同一个文件 ——
     * 就地重写需要先 `setLength(0)`，而那时源数据已被截断，后续块拷贝会读到空。
     * 只有 `ID3 ` 与 `PROP` 读进内存，`DSD`（音频）等大块用固定缓冲拷贝，
     * 因此内存占用与文件总大小无关。
     *
     * @return 新文件的总字节数
     */
    fun write(
        file: File,
        tagBytes: ByteArray,
    ): Long {
        val parent = file.absoluteFile.parentFile ?: throw IllegalStateException("DFF 文件没有父目录")
        val temp = File.createTempFile(".${file.name}.dff-write-", ".tmp", parent)
        try {
            val newSize = buildInto(file, temp, tagBytes)
            if (!temp.renameTo(file)) {
                // renameTo 在目标存在时可能失败：退回「删除后改名」
                if (!file.delete() || !temp.renameTo(file)) {
                    throw IllegalStateException("DFF 写入后替换原文件失败")
                }
            }
            return newSize
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun buildInto(
        source: File,
        target: File,
        tagBytes: ByteArray,
    ): Long {
        RandomAccessFile(source, "r").use { raf ->
            val end = raf.length()
            if (end < 16) throw IllegalStateException("不是可识别的 DFF（DSDIFF）文件")
            val head = ByteArray(16)
            raf.seek(0)
            if (raf.read(head, 0, 16) < 16) throw IllegalStateException("不是可识别的 DFF（DSDIFF）文件")
            if (String(head, 0, 4, Charsets.ISO_8859_1) != FORM_ID) {
                throw IllegalStateException("不是可识别的 DFF（DSDIFF）文件")
            }
            val formSize = be64(head, 4)
            val formEnd = (CHUNK_HEADER + formSize).coerceAtMost(end)
            val formType = head.copyOfRange(12, 16)

            // 收集块清单（只记偏移与长度，不读负载）
            val plan = mutableListOf<ChunkPlan>()
            walkChunks(raf, 16L, formEnd) { id, offset, size ->
                plan += ChunkPlan(id, offset, size)
            }

            RandomAccessFile(target, "rw").use { out ->
                out.setLength(0)
                out.seek(0)
                out.write(FORM_ID.toByteArray(Charsets.ISO_8859_1))
                out.write(ByteArray(8)) // 长度占位
                out.write(formType)
                // 表单头 = 4（FRM8）+ 8（长度）+ 4（formType）
                var written = 16L
                for (chunk in plan) {
                    when (chunk.id) {
                        ID3_ID -> Unit // 旧标签丢弃，最后统一追加
                        "PROP" -> {
                            // PROP 需重写内部长度：读进内存（通常几百字节）
                            val data = readFully(raf, chunk.offset, chunk.size.toInt())
                                ?: throw IllegalStateException("DFF 块结构异常")
                            val stripped = stripId3FromProp(data)
                            writeChunkTo(out, "PROP", stripped)
                            written += CHUNK_HEADER + stripped.size + (stripped.size % 2).toLong()
                        }
                        else -> {
                            copyChunk(raf, out, chunk)
                            written += CHUNK_HEADER + chunk.size + (chunk.size % 2L)
                        }
                    }
                }
                writeChunkTo(out, ID3_ID, tagBytes)
                written += CHUNK_HEADER + tagBytes.size + (tagBytes.size % 2).toLong()
                // FRM8 长度 = 总长 - (4 字节 ID + 8 字节长度)
                out.seek(4)
                out.write(be64Bytes(written - CHUNK_HEADER))
                return written
            }
        }
    }

    /** 一个块的位置信息（不含负载）。 */
    private class ChunkPlan(
        val id: String,
        val offset: Long,
        val size: Long,
    )

    /** 流式拷贝一个块（头 + 负载 + 对齐），不把负载读进内存。 */
    private fun copyChunk(
        src: RandomAccessFile,
        dst: RandomAccessFile,
        chunk: ChunkPlan,
    ) {
        dst.write(chunk.id.toByteArray(Charsets.ISO_8859_1))
        dst.write(be64Bytes(chunk.size))
        src.seek(chunk.offset)
        var remaining = chunk.size
        val buf = ByteArray(COPY_BUFFER)
        while (remaining > 0) {
            val want = minOf(buf.size.toLong(), remaining).toInt()
            val n = src.read(buf, 0, want)
            if (n <= 0) break
            dst.write(buf, 0, n)
            remaining -= n
        }
        if (chunk.size % 2L == 1L) dst.write(0)
    }

    private const val COPY_BUFFER = 1 shl 20

    /**
     * 遍历块链，对每个块回调 `(id, 负载偏移, 负载长度)`；不读负载。
     *
     * 遍历期间 [raf] 的读取位置会被移动，调用方若需读负载应自行 `seek`。
     */
    private fun walkChunks(
        raf: RandomAccessFile,
        from: Long,
        until: Long,
        onChunk: (id: String, dataOffset: Long, dataSize: Long) -> Unit,
    ) {
        var p = from
        while (p + CHUNK_HEADER <= until) {
            raf.seek(p)
            val header = ByteArray(CHUNK_HEADER)
            if (raf.read(header, 0, CHUNK_HEADER) < CHUNK_HEADER) return
            val id = String(header, 0, 4, Charsets.ISO_8859_1)
            if (!id.matches(CHUNK_ID)) return
            val size = be64(header, 4)
            if (size < 0 || p + CHUNK_HEADER + size > until) return
            onChunk(id, p + CHUNK_HEADER, size)
            p += CHUNK_HEADER + size + (size % 2L)
        }
    }

    private val CHUNK_ID = Regex("^[ -~]{4}$")

    /** 从指定偏移读 [size] 字节；越界或失败返回 null。 */
    private fun readFully(
        raf: RandomAccessFile,
        offset: Long,
        size: Int,
    ): ByteArray? {
        if (size < 0) return null
        val buf = ByteArray(size)
        raf.seek(offset)
        var read = 0
        while (read < size) {
            val n = raf.read(buf, read, size - read)
            if (n <= 0) return null
            read += n
        }
        return buf
    }

    // ── 块解析/写入 ─────────────────────────────────────────────────────────

    private class Chunk(
        val id: String,
        val data: ByteArray,
    )

    private fun parseProp(data: ByteArray): List<Chunk>? {
        if (data.size < 4) return null
        return parseChunks(data, 4, data.size)
    }

    private fun parseChunks(
        bytes: ByteArray,
        from: Int,
        until: Int,
    ): List<Chunk>? {
        val list = mutableListOf<Chunk>()
        var p = from
        while (p + 12 <= until) {
            val id = String(bytes, p, 4, Charsets.ISO_8859_1)
            if (!id.matches(CHUNK_ID)) break
            val size = be64(bytes, p + 4)
            if (size < 0 || p + 12 + size > until) break
            val data = bytes.copyOfRange(p + 12, (p + 12 + size).toInt())
            list += Chunk(id, data)
            p += 12 + size.toInt() + (size.toInt() % 2)
        }
        return list
    }

    private fun stripId3FromProp(data: ByteArray): ByteArray {
        if (data.size < 4) return data
        val children = parseChunks(data, 4, data.size) ?: return data
        if (children.none { it.id == ID3_ID }) return data
        val out = java.io.ByteArrayOutputStream(data.size)
        out.write(data, 0, 4) // 表单类型，例如 "SND "
        for (child in children) {
            if (child.id == ID3_ID) continue
            writeChunk(out, child.id, child.data)
        }
        return out.toByteArray()
    }

    private fun writeChunk(
        out: java.io.ByteArrayOutputStream,
        id: String,
        data: ByteArray,
    ) {
        out.write(id.toByteArray(Charsets.ISO_8859_1))
        out.write(be64Bytes(data.size.toLong()))
        out.write(data)
        if (data.size % 2 == 1) out.write(0) // 偶数对齐
    }

    /** 同上，写进 [RandomAccessFile]（重写整个文件时用）。 */
    private fun writeChunkTo(
        out: RandomAccessFile,
        id: String,
        data: ByteArray,
    ) {
        out.write(id.toByteArray(Charsets.ISO_8859_1))
        out.write(be64Bytes(data.size.toLong()))
        out.write(data)
        if (data.size % 2 == 1) out.write(0)
    }

    private fun be64(
        bytes: ByteArray,
        offset: Int,
    ): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return v
    }

    private fun be64Bytes(value: Long): ByteArray {
        val out = ByteArray(8)
        for (i in 0 until 8) out[i] = ((value ushr (8 * (7 - i))) and 0xFF).toByte()
        return out
    }

    private fun putBe64(
        bytes: ByteArray,
        offset: Int,
        value: Long,
    ) {
        for (i in 0 until 8) bytes[offset + i] = ((value ushr (8 * (7 - i))) and 0xFF).toByte()
    }
}
