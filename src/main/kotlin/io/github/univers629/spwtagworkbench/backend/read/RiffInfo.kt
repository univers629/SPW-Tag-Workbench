// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.read

import java.io.File
import java.io.RandomAccessFile

/**
 * WAV / AIFF 里的 RIFF `INFO` 标签读取。
 *
 * 老一点的音频工具（Sound Forge、GoldWave、部分刻录软件）只写 `LIST/INFO` 块，
 * 不写 ID3。这类文件的 ID3 是空的，光靠标签库会显示「无标题」。
 *
 * 结构：`RIFF`(或 `FORM`) + 长度 + 表单类型，之后是若干块：4 字节 ID + 长度 + 数据（偶数对齐）。
 * `LIST` 块的负载先是一个 4 字节类型（`INFO`），再是若干 `INAM`/`IART` 之类的子块。
 * WAV 的长度是小端，AIFF 是大端。
 */
object RiffInfo {
    /**
     * `LIST/INFO` 负载与单个文本块的读取上限。
     *
     * 超过上限只跳过、不读：块长度是文件里写什么就信什么，一个坏值就能让
     * 「读几个标签字段」变成「把几百 MB 读进内存」。
     */
    private const val MAX_INFO_BYTES = 1 shl 20

    private const val MAX_TEXT_BYTES = 64 * 1024

    /** 返回 标题/艺术家/专辑/流派/年份/音轨/备注，缺失的键不出现。 */
    fun read(file: File): Map<String, String> =
        try {
            RandomAccessFile(file, "r").use { raf -> readChunks(raf) }
        } catch (_: Throwable) {
            emptyMap()
        }

    /**
     * 逐块扫描：只读块头与真正需要的负载，音频 `data` / `SSND` 这类大块直接跳过。
     *
     * 早先是 `file.readBytes()` 把**整个文件**读进内存 —— 一个 300 MB 的 WAV
     * 只为拿几十字节的 INFO 字段也要整份读入，而曲目读取与信息页都会经过这里。
     */
    private fun readChunks(raf: RandomAccessFile): Map<String, String> {
        val length = raf.length()
        if (length < 12) return emptyMap()
        val head = ByteArray(12)
        raf.seek(0)
        raf.readFully(head)
        val little =
            when (String(head, 0, 4, Charsets.ISO_8859_1)) {
                "RIFF" -> true
                "FORM" -> false
                else -> return emptyMap()
            }
        val form = String(head, 8, 4, Charsets.ISO_8859_1)
        if (form != "WAVE" && form != "AIFF" && form != "AIFC") return emptyMap()

        val result = LinkedHashMap<String, String>()
        var pos = 12L
        while (pos + 8 <= length) {
            raf.seek(pos)
            val header = ByteArray(8)
            raf.readFully(header)
            val id = String(header, 0, 4, Charsets.ISO_8859_1)
            val size = if (little) le32(header, 4) else be32(header, 4)
            if (size < 0 || pos + 8 + size > length) break
            val body = pos + 8
            when {
                id == "LIST" && size in 4..MAX_INFO_BYTES -> {
                    // 读块头后指针已落在块体起点，直接读这一段
                    val data = ByteArray(size)
                    raf.readFully(data)
                    if (String(data, 0, 4, Charsets.ISO_8859_1) == "INFO") {
                        readInfoChildren(data, little, result)
                    }
                }
                // AIFF 自带的文本块（部分工具只写这些）
                (id == "NAME" || id == "AUTH" || id == "ANNO") && size <= MAX_TEXT_BYTES -> {
                    val data = ByteArray(size)
                    raf.readFully(data)
                    val key =
                        when (id) {
                            "NAME" -> "title"
                            "AUTH" -> "artist"
                            else -> "comment"
                        }
                    result.putIfAbsent(key, text(data))
                }
            }
            pos = body + size + (size % 2)
        }
        return result
    }

    private fun readInfoChildren(
        data: ByteArray,
        little: Boolean,
        out: MutableMap<String, String>,
    ) {
        var p = 4
        while (p + 8 <= data.size) {
            val id = String(data, p, 4, Charsets.ISO_8859_1)
            val size = if (little) le32(data, p + 4) else be32(data, p + 4)
            if (size < 0 || p + 8 + size > data.size) break
            val value = text(data.copyOfRange(p + 8, p + 8 + size))
            if (value.isNotBlank()) {
                when (id) {
                    "INAM" -> out.putIfAbsent("title", value)
                    "IART" -> out.putIfAbsent("artist", value)
                    "IPRD" -> out.putIfAbsent("album", value)
                    "IGNR" -> out.putIfAbsent("genre", value)
                    "ICRD" -> out.putIfAbsent("year", value)
                    "ITRK", "IPRT" -> out.putIfAbsent("track", value)
                    "ICMT" -> out.putIfAbsent("comment", value)
                    "IENG" -> out.putIfAbsent("engineer", value)
                }
            }
            p += 8 + size + (size % 2)
        }
    }

    /**
     * INFO 规范写的是 ASCII/Latin-1，但实际文件里中文字段常见 UTF-8 甚至是 GBK。
     * 先按 UTF-8 严格解码，失败再退回 Latin-1（这样既不会把 UTF-8 中文读成乱码，
     * 也不会把纯 ASCII 弄坏）。
     */
    private fun text(data: ByteArray): String {
        val end = data.indexOf(0).let { if (it < 0) data.size else it }
        val raw = data.copyOfRange(0, end)
        if (raw.isEmpty()) return ""
        val utf8 =
            runCatching {
                val decoder =
                    Charsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                decoder.decode(java.nio.ByteBuffer.wrap(raw)).toString()
            }.getOrNull()
        return (utf8 ?: String(raw, Charsets.ISO_8859_1)).trim()
    }

    private fun le32(
        b: ByteArray,
        o: Int,
    ): Int =
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun be32(
        b: ByteArray,
        o: Int,
    ): Int =
        ((b[o].toInt() and 0xFF) shl 24) or
            ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or
            (b[o + 3].toInt() and 0xFF)
}
