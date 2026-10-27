// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.read

import java.io.File
import java.io.RandomAccessFile

/** 标签体系：决定读写走哪条路径。 */
enum class TagSystem(
    val label: String,
) {
    ID3("ID3v2"),
    VORBIS("Vorbis comment"),
    MP4("MP4 atom"),
    APEV2("APEv2"),
    RIFF("RIFF chunk"),
    ASF("ASF object"),
    NONE("无"),
}

/**
 * 容器识别。按文件开头的魔数判断，不依赖扩展名（同一容器常有多个扩展名，
 * 扩展名也可能被改错）。
 *
 * 这里的划分直接对应标签体系：MP3/AIFF/DSF/DFF 走 ID3v2，FLAC/OGG/Opus 走
 * Vorbis comment（Opus 的注释包叫 OpusTags，结构相同），APE/WV/TAK/TTA/MPC
 * 走文件尾部的 APEv2，WAV 走 RIFF 内的块。
 */
enum class ContainerKind(
    val label: String,
    val tagSystem: TagSystem,
) {
    FLAC("FLAC", TagSystem.VORBIS),
    MP3("MP3", TagSystem.ID3),
    AAC("AAC（裸流）", TagSystem.ID3),
    MP4("M4A/MP4", TagSystem.MP4),
    OGG("OGG", TagSystem.VORBIS),
    OPUS("Opus", TagSystem.VORBIS),
    APE("APE", TagSystem.APEV2),
    WAVPACK("WavPack", TagSystem.APEV2),
    TAK("TAK", TagSystem.APEV2),
    TTA("TTA", TagSystem.APEV2),
    MPC("MPC", TagSystem.APEV2),
    WAV("WAV", TagSystem.RIFF),
    AIFF("AIFF", TagSystem.ID3),
    DSF("DSF", TagSystem.ID3),
    DFF("DFF", TagSystem.ID3),
    WMA("WMA", TagSystem.ASF),
    UNKNOWN("未知容器", TagSystem.NONE),

    ;

    val isApeV2: Boolean get() = tagSystem == TagSystem.APEV2

    companion object {
        /** 探测文件头时读多少字节：够放 ID3v2 头（10）并留出音频首帧的余量。 */
        private const val HEADER_PROBE = 64

        /** ASF（WMA）文件头的 GUID。 */
        private val ASF_GUID =
            byteArrayOf(
                0x30,
                0x26,
                0xB2.toByte(),
                0x75,
                0x8E.toByte(),
                0x66,
                0xCF.toByte(),
                0x11,
                0xA6.toByte(),
                0xD9.toByte(),
                0x00,
                0xAA.toByte(),
                0x00,
                0x62,
                0xCE.toByte(),
                0x6C,
            )

        /**
         * 识别容器：先按魔数，**判不出来时**才按扩展名兜底（见 [fallbackByName]）。
         */
        fun detect(file: File): ContainerKind =
            try {
                val byMagic = RandomAccessFile(file, "r").use { raf -> detect(raf) }
                if (byMagic != UNKNOWN) byMagic else fallbackByName(file.name) ?: UNKNOWN
            } catch (_: Throwable) {
                UNKNOWN
            }

        /**
         * 魔数判不出来时的扩展名兜底。
         *
         * 只覆盖**确实没有魔数**的那几代 MusePack：SV4~SV6 的文件头直接是压缩流，
         * `MP+` 到 SV7 才出现、`MPCK` 是 SV8 的事，除了扩展名没有可依赖的判据
         * （实测样本 `sv4_header.mpc` 前 4 字节是 `C1 27 20 00`，就是压缩数据）。
         * 其余扩展名一律不兜底：扩展名能被改错，不能当通用判据 ——
         * 判不出来则报告「未知容器」，优于按错的标签体系去读写。
         */
        private fun fallbackByName(fileName: String): ContainerKind? =
            when (fileName.substringAfterLast('.', "").lowercase()) {
                "mpc", "mp+" -> MPC
                else -> null
            }

        private fun detect(raf: RandomAccessFile): ContainerKind {
            val head = ByteArray(16)
            val read = raf.read(head, 0, 16)
            if (read < 4) return UNKNOWN
            val magic4 = String(head, 0, 4, Charsets.ISO_8859_1)
            return when {
                magic4 == "fLaC" -> FLAC
                magic4 == "MAC " -> APE
                magic4 == "wvpk" -> WAVPACK
                magic4 == "tBaK" -> TAK
                magic4 == "TTA1" -> TTA
                magic4 == "DSD " -> DSF
                magic4 == "FRM8" -> DFF
                magic4 == "FORM" -> AIFF
                magic4 == "RIFF" -> WAV
                magic4 == "OggS" -> oggKind(raf)
                magic4 == "ADIF" -> AAC
                isAdts(head) -> AAC
                // "ID3" 只有 3 字节，取 4 字节比较永远不相等，必须按前三字节判断
                head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte() -> id3Kind(raf)
                read >= 12 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> MP4
                read >= 16 && head.copyOfRange(0, 16).contentEquals(ASF_GUID) -> WMA
                // MPC 有两种头：老版 "MP+"、新版 "MPCK"；也可能以 ID3 开头
                magic4 == "MPCK" || (magic4.startsWith("MP") && head[2] == '+'.code.toByte()) -> MPC
                else -> UNKNOWN
            }
        }

        /**
         * `ID3` 开头：MP3 最常见，MPC 也可能带 ID3v2 头。
         *
         * **必须校验 ID3v2 头**：`ID3` 只是三个字节，任意文本也可能与之相同
         * （实测样本 `almostempty.mpc` 内容就是 `ID3 dsfdsfds MP+`）。
         * 头不合法时返回 [UNKNOWN]，交给扩展名兜底，不能默认当 MP3 ——
         * 否则容器布局会按错的长度画出不存在的 ID3 块。
         */
        private fun id3Kind(raf: RandomAccessFile): ContainerKind {
            val probe = ByteArray(HEADER_PROBE)
            raf.seek(0)
            val n = raf.read(probe, 0, HEADER_PROBE)
            if (n < Id3v2.HEADER_SIZE) return UNKNOWN
            val header = Id3v2.header(probe) ?: return UNKNOWN
            // 声明的标签长度必须落在文件内：否则「跳过标签读音频首帧」无从谈起
            if (header.totalSize > raf.length()) return UNKNOWN
            val after = header.totalSize.toLong()
            // 跳过 ID3v2 后看音频首帧：ADTS 同步字 → AAC 裸流；MP+/MPCK → MPC
            if (after + 4 <= raf.length()) {
                raf.seek(after)
                val next = ByteArray(4)
                raf.read(next, 0, 4)
                val magic = String(next, Charsets.ISO_8859_1)
                if (magic == "MP+" || magic == "MPCK") return MPC
                if (isAdts(next)) return AAC
                if (magic.startsWith("TTA")) return TTA
            }
            return MP3
        }

        /**
         * ADTS 同步字：12 位 0xFFF，接下来 2 位 layer 固定为 00。
         *
         * 判断时只取第二字节的高 4 位与 bit1（`0xF6` 掩码），避免把普通 MP3 帧误判成 AAC：
         * MP3 帧的 layer 位不会是 00。
         */
        private fun isAdts(bytes: ByteArray): Boolean {
            if (bytes.size < 2) return false
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            return b0 == 0xFF && (b1 and 0xF6) == 0xF0
        }

        /** Ogg 容器里区分 Opus 与 Vorbis：看首页负载里的编解码标识。 */
        private fun oggKind(raf: RandomAccessFile): ContainerKind {
            val buf = ByteArray(256)
            raf.seek(0)
            val n = raf.read(buf, 0, 256)
            if (n <= 0) return OGG
            val text = String(buf, 0, n, Charsets.ISO_8859_1)
            return if (text.contains("OpusHead") || text.contains("OpusTags")) OPUS else OGG
        }
    }
}
