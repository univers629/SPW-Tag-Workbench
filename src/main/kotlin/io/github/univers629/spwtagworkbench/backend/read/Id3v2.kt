// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.read

import java.io.File

/**
 * 自有 ID3v2 编解码器。
 *
 * 标签库（jaudiotagger）只对它认识的容器（MP3 / AIFF / DSF / WAV）写 ID3v2，
 * AAC 裸流、DFF 这类容器需要直接操作 ID3v2 字节，因此这里实现一层最小可用的
 * 读写：文本帧、歌词（USLT）、封面（APIC），版本 2.3 / 2.4。
 *
 * 结构要点：
 * - 头 10 字节：`ID3` + 主版本 + 修订 + 标志 + 4 字节**同步安全整数**长度（每字节 7 位）
 * - 2.3 帧长是普通大端 32 位；2.4 帧长同样是同步安全整数
 * - 文本帧首字节是编码：0=ISO-8859-1、1=UTF-16（带 BOM）、2=UTF-16BE、3=UTF-8
 */
object Id3v2 {
    const val HEADER_SIZE = 10

    /** 一张内嵌图片。 */
    class Picture(
        val type: Int,
        val mime: String,
        val description: String,
        val bytes: ByteArray,
    )

    /** 解析结果。 */
    class Tag(
        val major: Int,
        val fields: Map<String, String>,
        val lyrics: String?,
        val pictures: List<Picture>,
        /** 整个标签占用的字节数（含头与内边距）。 */
        val totalSize: Int,
        /**
         * `TXXX` 帧：**描述符 → 值**。
         *
         * 普通文本帧的 ID 是固定的四字符（`TIT2` 等），一个 ID 只能有一个值；
         * `TXXX` 不同 —— 帧 ID 全是 `TXXX`，靠帧内的描述符区分，数量不限。
         * ReplayGain 就靠它承载（四个键各占一个 TXXX）。
         *
         * 描述符按规范大小写不敏感，这里统一按原样存，查找时忽略大小写。
         */
        val userTexts: Map<String, String> = emptyMap(),
    ) {
        fun text(frameId: String): String? = fields[frameId]

        /** 按描述符取 `TXXX` 的值；找不到返回 null。描述符忽略大小写。 */
        fun userText(description: String): String? = userTexts.entries.firstOrNull { it.key.equals(description, ignoreCase = true) }?.value

        /** 是否含某个 `TXXX` 描述符。 */
        fun hasUserText(description: String): Boolean = userTexts.keys.any { it.equals(description, ignoreCase = true) }
    }

    /** 文本帧 ID（2.3 与 2.4 多数相同，年份字段不同）。 */
    fun yearFrameId(major: Int): String = if (major >= 4) "TDRC" else "TYER"

    // ── 头 ──────────────────────────────────────────────────────────────────

    /** 解析出的 ID3v2 头。 */
    class Header(
        val major: Int,
        val revision: Int,
        val flags: Int,
        /** 标签体长度（不含 10 字节头）。 */
        val bodySize: Int,
    ) {
        /** 整个标签占用的字节数（头 + 体）。 */
        val totalSize: Int get() = HEADER_SIZE + bodySize
    }

    /**
     * 解析 10 字节 ID3v2 头；不是合法头时返回 null。
     *
     * **「前三个字节是 ID3」不等于「这是一个 ID3v2 标签」**：实测样本
     * `almostempty.mpc` 的内容就是 `ID3` + 一段输入的文字（`ID3 dsfdsfds MP+`）。
     * 只看魔数就会把 `0x20` 当版本号、把 `s f d s` 当同步安全长度，得出
     * 「ID3v2.32.100、两亿字节」这种明显失真的结果，容器布局卡据此画出一条
     * 不存在的块。因此这里要求版本在 2..4、修订号不是 0xFF、
     * 长度字段每个字节的最高位为 0（同步安全整数）。
     *
     * 调用方仍需自行确认 [Header.totalSize] 落在文件内。
     */
    fun header(head: ByteArray): Header? {
        if (head.size < HEADER_SIZE || !hasMagic(head, 0)) return null
        val major = head[3].toInt() and 0xFF
        if (major !in 2..4) return null
        val revision = head[4].toInt() and 0xFF
        if (revision == 0xFF) return null
        for (i in 6..9) if ((head[i].toInt() and 0x80) != 0) return null
        return Header(major, revision, head[5].toInt() and 0xFF, syncsafe(head, 6))
    }

    // ── 读 ──────────────────────────────────────────────────────────────────

    /**
     * 读文件开头的 ID3v2 标签；没有返回 null。
     *
     * **只读标签本身**：先读 10 字节头拿到声明长度，再按需读那么长。
     * 早先是 `file.readBytes()` 把整个文件读进来 —— 一首 100 MB 的 DSD 为此白读 100 MB，
     * 而真正需要的标签通常只有几十 KB。
     */
    fun read(file: File): Tag? =
        try {
            val head = readPrefix(file, HEADER_SIZE)
            // 头不合法就不是 ID3v2 文件（可能只是恰好以 ID3 开头的其它内容）
            val parsed = head?.let { header(it) } ?: return null
            // 声明长度超出文件：标签被截断，不按半截解析（那会读出残缺帧）
            if (parsed.totalSize > file.length()) return null
            val bytes = readPrefix(file, parsed.totalSize) ?: return null
            readAt(bytes, 0)
        } catch (_: Throwable) {
            null
        }

    /** 读文件开头最多 [count] 字节；文件更短时返回实际读到的部分。 */
    private fun readPrefix(
        file: File,
        count: Int,
    ): ByteArray? {
        if (count <= 0) return null
        return file.inputStream().use { input ->
            val buffer = ByteArray(count)
            var read = 0
            while (read < count) {
                val n = input.read(buffer, read, count - read)
                if (n <= 0) break
                read += n
            }
            when (read) {
                0 -> null
                count -> buffer
                else -> buffer.copyOf(read)
            }
        }
    }

    /** 判断 [offset] 处是不是 `ID3` 魔数。 */
    private fun hasMagic(
        bytes: ByteArray,
        offset: Int,
    ): Boolean =
        offset + 3 <= bytes.size &&
            bytes[offset] == 'I'.code.toByte() &&
            bytes[offset + 1] == 'D'.code.toByte() &&
            bytes[offset + 2] == '3'.code.toByte()

    /** 从 [offset] 处读标签。 */
    fun readAt(
        bytes: ByteArray,
        offset: Int,
    ): Tag? {
        if (offset + HEADER_SIZE > bytes.size) return null
        if (!hasMagic(bytes, offset)) return null
        val major = bytes[offset + 3].toInt() and 0xFF
        if (major !in 3..4) return null // 2.2 用 3 字节帧 ID，单独一种解析，暂不支持
        val flags = bytes[offset + 5].toInt() and 0xFF
        val declared = syncsafe(bytes, offset + 6)
        val bodyEnd = minOf(offset + HEADER_SIZE + declared, bytes.size)
        var body = bytes.copyOfRange(offset + HEADER_SIZE, bodyEnd)
        if ((flags and 0x80) != 0) body = deUnsynchronise(body)

        var p = 0
        // 扩展头：2.3 是 4 字节大小（不含自身），2.4 是同步安全整数（含自身）
        if ((flags and 0x40) != 0) {
            p =
                if (major == 4) {
                    val size = syncsafe(body, 0)
                    size.coerceIn(0, body.size)
                } else {
                    val size = be32(body, 0)
                    (size + 4).coerceIn(0, body.size)
                }
        }

        val fields = LinkedHashMap<String, String>()
        val userTexts = LinkedHashMap<String, String>()
        var lyrics: String? = null
        val pictures = mutableListOf<Picture>()
        while (p + 10 <= body.size) {
            if (body[p] == 0.toByte()) break // 进入内边距
            val id = String(body, p, 4, Charsets.ISO_8859_1)
            if (!id.matches(Regex("^[A-Z0-9]{4}$"))) break
            val size = if (major == 4) syncsafe(body, p + 4) else be32(body, p + 4)
            if (size <= 0 || p + 10 + size > body.size) break
            val frame = body.copyOfRange(p + 10, p + 10 + size)
            when {
                // TXXX 必须排在通用 `T` 分支前面：它的帧体是「编码 + 描述符 + 0 + 值」，
                // 被 parseText 当成普通文本会连描述符一起读进来
                id == "TXXX" -> parseUserText(frame)?.let { (desc, value) -> userTexts[desc] = value }
                id == "USLT" -> lyrics = parseLyrics(frame) ?: lyrics
                id == "APIC" -> parsePicture(frame)?.let { pictures += it }
                id == "COMM" -> parseComment(frame)?.let { fields.putIfAbsent("COMM", it) }
                id.startsWith("T") -> parseText(frame)?.let { fields.putIfAbsent(id, it) }
            }
            p += 10 + size
        }
        val total = HEADER_SIZE + declared
        return Tag(
            major = major,
            fields = fields,
            lyrics = lyrics,
            pictures = pictures,
            totalSize = total,
            userTexts = userTexts,
        )
    }

    /**
     * 解析 `TXXX` 帧体：`编码 + 描述符 + 0 + 值`。
     *
     * 与 COMM 的区别是没有 3 字节语言码，多出来的反而是描述符承担了区分作用。
     * 描述符为空时视为无效帧（规范要求描述符非空才有意义）。
     */
    private fun parseUserText(frame: ByteArray): Pair<String, String>? {
        if (frame.size < 3) return null
        val encoding = frame[0].toInt() and 0xFF
        if (encoding !in 0..3) return null

        val descEnd = terminatorEnd(frame, 1, encoding)
        val description = decodeText(encoding, frame, 1, descEnd)?.trim().orEmpty()
        if (description.isEmpty()) return null

        val step = if (encoding == 1 || encoding == 2) 2 else 1
        val valueStart = (descEnd + step).coerceAtMost(frame.size)
        val value = decodeText(encoding, frame, valueStart)?.trimEnd().orEmpty()
        return description to value
    }

    private fun parseText(frame: ByteArray): String? {
        if (frame.isEmpty()) return null
        val text = decodeText(frame[0].toInt() and 0xFF, frame, 1)
        return text?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun parseLyrics(frame: ByteArray): String? {
        if (frame.size < 5) return null
        val encoding = frame[0].toInt() and 0xFF
        // 3 字节语言 + 描述（以 0 结尾）+ 正文
        var p = 4
        p = skipTerminated(frame, p, encoding)
        val text = decodeText(encoding, frame, p)
        return text?.trimEnd()?.takeIf { it.isNotBlank() }
    }

    private fun parseComment(frame: ByteArray): String? {
        if (frame.size < 5) return null
        val encoding = frame[0].toInt() and 0xFF
        var p = 4
        p = skipTerminated(frame, p, encoding)
        return decodeText(encoding, frame, p)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun parsePicture(frame: ByteArray): Picture? {
        if (frame.size < 6) return null
        val encoding = frame[0].toInt() and 0xFF
        var p = 1
        val mimeEnd = (p until frame.size).firstOrNull { frame[it] == 0.toByte() } ?: return null
        val mime = String(frame, p, mimeEnd - p, Charsets.ISO_8859_1)
        p = mimeEnd + 1
        if (p >= frame.size) return null
        val type = frame[p].toInt() and 0xFF
        p += 1
        val descEnd = terminatorEnd(frame, p, encoding)
        val description = decodeText(encoding, frame, p, descEnd)?.trimStart('\u0000') ?: ""
        p = descEnd + if (encoding == 1 || encoding == 2) 2 else 1
        if (p > frame.size) return null
        val data = frame.copyOfRange(p, frame.size)
        if (data.isEmpty()) return null
        return Picture(type = type, mime = mime, description = description, bytes = data)
    }

    private fun skipTerminated(
        frame: ByteArray,
        start: Int,
        encoding: Int,
    ): Int {
        val end = terminatorEnd(frame, start, encoding)
        val step = if (encoding == 1 || encoding == 2) 2 else 1
        return (end + step).coerceAtMost(frame.size)
    }

    /** 找到以 0 结尾的字符串的终止位置（UTF-16 是双字节 0）。 */
    private fun terminatorEnd(
        frame: ByteArray,
        start: Int,
        encoding: Int,
    ): Int {
        if (encoding == 1 || encoding == 2) {
            var i = start
            while (i + 1 < frame.size) {
                if (frame[i] == 0.toByte() && frame[i + 1] == 0.toByte()) return i
                i += 2
            }
            return frame.size
        }
        for (i in start until frame.size) if (frame[i] == 0.toByte()) return i
        return frame.size
    }

    private fun decodeText(
        encoding: Int,
        frame: ByteArray,
        from: Int,
        to: Int = frame.size,
    ): String? {
        if (from > to || from >= frame.size) return null
        val slice = frame.copyOfRange(from, minOf(to, frame.size))
        return when (encoding) {
            0 -> String(slice, Charsets.ISO_8859_1)
            1 -> String(slice, Charsets.UTF_16)
            2 -> String(slice, Charsets.UTF_16BE)
            3 -> String(slice, Charsets.UTF_8)
            else -> null
        }?.trim('\u0000')
    }

    // ── 写 ──────────────────────────────────────────────────────────────────

    /**
     * 构造 ID3v2 标签字节。
     *
     * @param version 3 = ID3v2.3（文本用 UTF-16）、4 = ID3v2.4（文本用 UTF-8）
     * @param values 帧 ID → 文本值（null 或空串表示不写）
     * @param userTexts 描述符 → 值，每个非空项写成一个 `TXXX` 帧。
     *   ReplayGain 的四个键走这里（`REPLAYGAIN_TRACK_GAIN` 等）。
     *   值传 null 或空串表示不写该帧；**删除已有帧是调用方的事**，
     *   因为 `build` 只负责构造，不知道原标签里有什么。
     */
    fun build(
        version: Int,
        values: Map<String, String?>,
        lyrics: String? = null,
        comment: String? = null,
        pictures: List<Picture> = emptyList(),
        padding: Int = 1024,
        userTexts: Map<String, String?> = emptyMap(),
    ): ByteArray {
        val major = if (version == 4) 4 else 3
        val encoding = if (major == 4) 3 else 1
        val frames = java.io.ByteArrayOutputStream()
        for ((id, value) in values) {
            val text = value?.trim().orEmpty()
            if (text.isEmpty() || !id.matches(Regex("^[A-Z0-9]{4}$"))) continue
            // COMM 的帧体不是纯文本（多 3 字节语言码），由 comment 参数单独写
            if (id == "COMM") continue
            frames.write(frameBytes(major, id, textFrameBody(encoding, text)))
        }
        for ((description, value) in userTexts) {
            val text = value?.trimEnd().orEmpty()
            val desc = description.trim()
            if (text.isEmpty() || desc.isEmpty()) continue
            frames.write(frameBytes(major, "TXXX", userTextBody(encoding, desc, text)))
        }
        lyrics?.takeIf { it.isNotBlank() }?.let {
            frames.write(frameBytes(major, "USLT", lyricsBody(encoding, it)))
        }
        comment?.takeIf { it.isNotBlank() }?.let {
            frames.write(frameBytes(major, "COMM", commentBody(encoding, it)))
        }
        for (picture in pictures) {
            frames.write(frameBytes(major, "APIC", pictureBody(encoding, picture)))
        }
        val frameBytes = frames.toByteArray()
        val bodySize = frameBytes.size + padding
        val out = java.io.ByteArrayOutputStream(HEADER_SIZE + bodySize)
        out.write("ID3".toByteArray(Charsets.ISO_8859_1))
        out.write(major)
        out.write(0) // 修订号
        out.write(0) // 标志位（不写 unsynchronisation，保持结构简单）
        out.write(syncsafeBytes(bodySize))
        out.write(frameBytes)
        if (padding > 0) out.write(ByteArray(padding))
        return out.toByteArray()
    }

    private fun frameBytes(
        major: Int,
        id: String,
        body: ByteArray,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream(HEADER_SIZE + body.size)
        out.write(id.toByteArray(Charsets.ISO_8859_1))
        out.write(if (major == 4) syncsafeBytes(body.size) else be32Bytes(body.size))
        out.write(ByteArray(2)) // 帧标志
        out.write(body)
        return out.toByteArray()
    }

    private fun textFrameBody(
        encoding: Int,
        text: String,
    ): ByteArray = byteArrayOf(encoding.toByte()) + encodeText(encoding, text)

    private fun lyricsBody(
        encoding: Int,
        lyrics: String,
    ): ByteArray =
        byteArrayOf(encoding.toByte()) +
            "XXX".toByteArray(Charsets.ISO_8859_1) +
            terminator(encoding) +
            encodeText(encoding, lyrics)

    /**
     * `COMM` 帧体：`编码 + 语言(3) + 描述 + 0 + 文本`。
     *
     * 与 `TXXX` 的区别就是多了 3 字节语言码。**不能当普通文本帧写**：
     * `parseComment` 会跳过语言码与描述，写成纯文本时读回来是空串，
     * 走手写写入器的容器（DFF、AAC 裸流、带 ID3v2 的 TTA）会直接回读校验失败。
     */
    private fun commentBody(
        encoding: Int,
        text: String,
    ): ByteArray =
        byteArrayOf(encoding.toByte()) +
            "XXX".toByteArray(Charsets.ISO_8859_1) +
            terminator(encoding) +
            encodeText(encoding, text)

    /**
     * `TXXX` 帧体：`编码 + 描述符 + 0 + 值`。
     *
     * 描述符和值用同一种编码。ReplayGain 的键都是 ASCII，因此在
     * ISO-8859-1 与 UTF-8 下字节完全一致，两种编码都能被通用播放器读出。
     */
    private fun userTextBody(
        encoding: Int,
        description: String,
        value: String,
    ): ByteArray =
        byteArrayOf(encoding.toByte()) +
            encodeText(encoding, description) +
            terminator(encoding) +
            encodeText(encoding, value)

    private fun pictureBody(
        encoding: Int,
        picture: Picture,
    ): ByteArray =
        byteArrayOf(encoding.toByte()) +
            picture.mime.toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0) +
            byteArrayOf(picture.type.toByte()) +
            encodeText(encoding, picture.description) +
            terminator(encoding) +
            picture.bytes

    private fun terminator(encoding: Int): ByteArray = if (encoding == 1 || encoding == 2) ByteArray(2) else ByteArray(1)

    private fun encodeText(
        encoding: Int,
        text: String,
    ): ByteArray =
        when (encoding) {
            0 -> text.toByteArray(Charsets.ISO_8859_1)
            1 -> text.toByteArray(Charsets.UTF_16) // 会带 BOM
            2 -> text.toByteArray(Charsets.UTF_16BE)
            else -> text.toByteArray(Charsets.UTF_8)
        }

    // ── 小工具 ──────────────────────────────────────────────────────────────

    fun syncsafe(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)

    fun syncsafeBytes(value: Int): ByteArray =
        byteArrayOf(
            ((value ushr 21) and 0x7F).toByte(),
            ((value ushr 14) and 0x7F).toByte(),
            ((value ushr 7) and 0x7F).toByte(),
            (value and 0x7F).toByte(),
        )

    fun be32(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    fun be32Bytes(value: Int): ByteArray =
        byteArrayOf(
            ((value ushr 24) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte(),
        )

    private fun deUnsynchronise(body: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(body.size)
        var i = 0
        while (i < body.size) {
            val b = body[i]
            out.write(b.toInt())
            if (b == 0xFF.toByte() && i + 1 < body.size && body[i + 1] == 0.toByte()) i += 2 else i += 1
        }
        return out.toByteArray()
    }
}
