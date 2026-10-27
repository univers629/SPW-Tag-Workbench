// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.lyrics

import io.github.univers629.spwtagworkbench.backend.net.Json
import io.github.univers629.spwtagworkbench.data.LyricCell
import io.github.univers629.spwtagworkbench.data.LyricLine

/**
 * 歌词格式识别与解析（LRC / SPL / 纯文本）。
 *
 * LRC：行首 `[mm:ss.xx]` 时间戳（可多个，重复行），增强 LRC 还能有 `<mm:ss.xx>` 逐字标记。
 * SPL：Salt Player Lyrics，毫秒**右补零**（`[00:01.5]` = 500ms，不是 5ms），
 *      行内 `[t]` 或 `<t>` 逐字标记，重复行 `[t1][t2]文本`。
 * 纯文本：没有时间戳，只能作为文本预览。
 */
object LyricsFormat {
    enum class Format(
        val label: String,
    ) {
        LRC("LRC"),

        /** 增强型 LRC：行首时间戳之后用 `<mm:ss.xx>` 标逐字。 */
        ESLYRIC("LRC（ESLyric）"),
        SPL("SPL"),

        /** 网易云逐字：`[行起始,行时长]字(字起始,字时长,0)`。 */
        YRC("YRC"),

        /** 酷狗逐字：`[行起始,行时长]<字偏移,字时长,0>字`。 */
        KRC("KRC"),

        /** QQ 音乐逐字：`[行起始,行时长]字(字起始,字时长)`。 */
        QRC("QRC"),
        SRT("SRT"),
        ASS("ASS"),
        TTML("TTML"),
        PLAIN("纯文本"),
        ;

        /** 这份格式**设计上**是否带逐字时间（实际是否真有取决于内容，见 [isReallyWordSynced]）。 */
        val isWordSynced: Boolean
            get() =
                this == ESLYRIC ||
                    this == SPL ||
                    this == YRC ||
                    this == KRC ||
                    this == QRC ||
                    this == TTML
    }

    /**
     * 内容里**真的**带有效逐字时间吗？
     *
     * 光看格式名不够：YRC 可能整首只有行时间没有词标记，
     * 或者词时间乱序（源站数据坏了）。这里与 SPW-Lyrics 的
     * `LyricsGranularityClassifier` 同一判据：
     * - 至少 80% 的带时间行要有合格的字单元
     * - 字单元时间必须单调递增、起止不倒置
     *
     * 不合格就当逐行，避免界面上标「逐字」而实际拿不到逐字。
     */
    fun isReallyWordSynced(raw: String): Boolean {
        val format = detect(raw)
        if (!format.isWordSynced) return false
        val lines = parse(raw).filter { it.startMs >= 0L }
        if (lines.isEmpty()) return false
        // 按时间戳分组再判定：同一时间戳下的多条行是「原文 / 译文 / 罗马音」轨道
        // （见 [parseTtml] 与 [krcToStructured]），附加轨道天然没有字单元。
        // 若按行数算，一首每行都有译文的 TTML 会被拉低到 50%，误判成逐行。
        // 判据取「该时间戳是否至少有一条带合格字单元」。
        val groups = lines.groupBy { it.startMs }
        val withCells = groups.count { (_, group) -> group.any { it.cells.isNotEmpty() && validCells(it.cells) } }
        // 覆盖率不到八成就算逐行，避免「几行有字时间、大部分没有」被吹成逐字
        return withCells * 10 >= groups.size * 8
    }

    /** 字单元自身时间是否合法：起止不倒置、整体单调递增。 */
    private fun validCells(cells: List<LyricCell>): Boolean {
        if (cells.any { it.startMs < 0L }) return false
        if (cells.any { it.endMs >= 0L && it.endMs < it.startMs }) return false
        return cells.zipWithNext().all { (a, b) -> a.startMs <= b.startMs }
    }

    /** 任意位置的 `[分:秒.毫秒]` 或 `<分:秒.毫秒>`。 */
    private val TIMESTAMP = Regex("""([\[<])(\d{1,3}):(\d{1,2})(?:[.:](\d{1,6}))?([\]>])""")

    private val LRC_LINE = Regex("""^\s*(\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?])+""")

    /** TTML 根标记：Apple Music / AMLL 都用这套命名空间。 */
    private val TTML_HINT = Regex("""<tt[\s>]""", RegexOption.IGNORE_CASE)

    /**
     * TTML 帧数写法（`HH:MM:SS:ff`）折算用的帧率。
     *
     * 规范里帧率来自根元素的 `ttp:frameRate`，Apple 的实际文件没用到帧数写法，
     * 所以这里给一个保守默认值而不是去解析属性 —— 遇到没有声明帧率的文件也不至于算错量级。
     */
    private const val DEFAULT_TTML_FRAME_RATE = 25L

    /** ESLyric / 增强型 LRC 的词级标记：`<mm:ss.xx>`。 */
    private val ANGLE_STAMP = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

    /**
     * 逐字歌词的行首标记：`[起始ms,时长ms]`。
     *
     * YRC / KRC / QRC 都用「纯数字毫秒」做时间轴，与 LRC 的 `[mm:ss.xx]` 不同。
     */
    private val WORD_LINE_STAMP = Regex("""^\[(\d+),(\d+)]""")

    /**
     * YRC 词级标记：`(绝对起始ms,时长ms,0)字`，**文本在括号之后**。
     *
     * 用 lookahead 而不是 `[^()]*`：文本本身可能含括号（注解、表情），
     * 否定字符类会提前截断。写法与 SPW-Lyrics 的 `YrcCodec` 一致。
     */
    private val YRC_WORD = Regex("""\((\d+),(\d+),\d+\)(.*?)(?=\(\d+,\d+,\d+\)|$)""")

    /**
     * KRC 词级标记：`<行内偏移ms,时长ms,0>字`，**文本在括号之后**。
     *
     * 偏移是相对行起始的，解析时要加上行起始时间。
     */
    private val KRC_WORD = Regex("""<(\d+),(\d+),\d+>(.*?)(?=<\d+,\d+,\d+>|$)""")

    /** QRC 词级标记：`字(绝对起始ms,时长ms)`，**文本在括号之前**。 */
    private val QRC_WORD = Regex("""(.*?)\((\d+),(\d+)\)""")

    /**
     * 源站歌词的文本头标签：`[id:…]`、`[ti:…]`、`[al:…]`、`[language:…]` 这类。
     *
     * 与时间轴行的区别在方括号内：时间轴是纯数字（`[1031,4600]`、`[00:01.03]`），
     * 头标签以字母或 `$` 开头。
     */
    private val HEADER_TAG = Regex("""^\[[A-Za-z_$][^]]*]""")

    /** SRT 时间轴：`00:00:12,340 --> 00:00:15,670`。 */
    private val SRT_CUE =
        Regex(
            """(\d{1,2}):(\d{1,2}):(\d{1,2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{1,2}):(\d{1,2})[,.](\d{1,3})""",
        )

    /** ASS/SSA 段标记。 */
    private val ASS_SECTION = Regex("""^\s*\[(Script Info|V4\+? Styles|Events)]""", RegexOption.MULTILINE)

    /** ASS 事件行：`Dialogue: 0,0:00:12.34,0:00:15.67,...`。 */
    private val ASS_DIALOGUE = Regex("""^\s*Dialogue:\s*\d+,([^,]*),([^,]*),""", RegexOption.MULTILINE)

    /**
     * 识别格式。
     *
     * 判据用各格式的**独有语法**：
     * - ASS/SSA：`[Script Info]` 段或 `Dialogue:` 行
     * - SRT：`00:00:12,340 --> 00:00:15,670` 这种双时间轴
     * - TTML：XML 根元素 `<tt>`（Apple Music / AMLL）
     * - YRC / KRC / QRC：行首是 `[起始ms,时长ms]`（纯数字毫秒），不是 `[mm:ss.xx]`
     * - SPL：行内 `[t]` 做逐字（LRC 的逐字用 `<>`）
     * - ESLyric：行首时间戳之外还有 `<mm:ss.xx>` 词级标记
     * - 有行首时间戳但无任何词级标记 → LRC
     * - 一个时间戳都没有 → 纯文本
     */
    fun detect(text: String): Format {
        if (text.isBlank()) return Format.PLAIN
        val head = text.take(4000)
        // ASS 先判：它有标志性的段标记，别的格式不会有
        if (ASS_SECTION.containsMatchIn(head) || ASS_DIALOGUE.containsMatchIn(head)) return Format.ASS
        // SRT：时间轴带小时段和逗号毫秒，且用 --> 连接
        if (SRT_CUE.containsMatchIn(head)) return Format.SRT
        // TTML：XML，别的东西不会以 <tt 开头
        if (TTML_HINT.containsMatchIn(head)) return Format.TTML
        // 逐字三兄弟：行首 `[数字,数字]`。靠词级标记的括号形式区分。
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val m = WORD_LINE_STAMP.find(line.trimStart()) ?: continue
            val body = line.trimStart().substring(m.range.last + 1)
            return when {
                KRC_WORD.containsMatchIn(body) -> Format.KRC
                YRC_WORD.containsMatchIn(body) -> Format.YRC
                QRC_WORD.containsMatchIn(body) -> Format.QRC
                // 只有行时间没有词级标记：当逐行处理
                else -> Format.LRC
            }
        }
        var hasLineStamp = false
        var hasWordStamp = false
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            if (LRC_LINE.containsMatchIn(line) || line.trimStart().startsWith("[")) hasLineStamp = true
            if (hasInlineBracketMarker(line)) return Format.SPL
            // 行首时间戳之后还有 <...> 标记 → ESLyric 逐字
            if (hasInlineAngleMarker(line)) hasWordStamp = true
        }
        return when {
            hasLineStamp && hasWordStamp -> Format.ESLYRIC
            hasLineStamp -> Format.LRC
            else -> Format.PLAIN
        }
    }

    /** 行首时间戳之后是否出现 `<mm:ss.xx>` 词级标记（ESLyric）。 */
    private fun hasInlineAngleMarker(line: String): Boolean {
        val trimmed = line.trimStart()
        var index = 0
        // 跳过行首连续的方括号时间戳
        while (index < trimmed.length && trimmed[index] == '[') {
            val end = trimmed.indexOf(']', index)
            if (end < 0) return false
            if (!TIMESTAMP.matches(trimmed.substring(index, end + 1))) return false
            index = end + 1
        }
        if (index >= trimmed.length) return false
        return ANGLE_STAMP.containsMatchIn(trimmed.substring(index))
    }

    /** 行内是否出现方括号逐字标记（文本之后又出现 `[t]`）。 */
    private fun hasInlineBracketMarker(line: String): Boolean {
        val trimmed = line.trimStart()
        var index = 0
        // 跳过行首连续的方括号时间戳
        while (index < trimmed.length && trimmed[index] == '[') {
            val end = trimmed.indexOf(']', index)
            if (end < 0) return false
            if (!TIMESTAMP.matches(trimmed.substring(index, end + 1))) return false
            index = end + 1
        }
        if (index >= trimmed.length) return false
        val rest = trimmed.substring(index)
        // 文本之后再出现方括号时间戳 → SPL 逐字
        return TIMESTAMP.findAll(rest).any { it.value.startsWith("[") }
    }

    /**
     * 解析成带时间轴的行。
     *
     * 纯文本返回每行 -1 时间；LRC / SPL 返回毫秒时间。
     */
    fun parse(text: String): List<LyricLine> =
        when (detect(text)) {
            Format.TTML -> parseTtml(text)
            Format.SPL -> parseSpl(text)
            Format.ESLYRIC -> parseEsLyric(text)
            Format.YRC -> parseWordSynced(text, YRC_WORD, format = Format.YRC)
            Format.KRC -> parseWordSynced(text, KRC_WORD, format = Format.KRC)
            Format.QRC -> parseWordSynced(text, QRC_WORD, format = Format.QRC)
            Format.SRT -> parseSrt(text)
            Format.ASS -> parseAss(text)
            Format.LRC -> parseLrc(text)
            Format.PLAIN ->
                text
                    .lineSequence()
                    .filter { it.isNotBlank() }
                    .map { LyricLine(startMs = -1L, text = it.trim()) }
                    .toList()
        }

    /**
     * 解析逐字歌词（YRC / KRC / QRC）。
     *
     * 三者行首都是 `[起始ms,时长ms]`，词级标记的差异集中在两处：
     * - **文本位置**：YRC / KRC 在标记**之后**，QRC 在标记**之前**
     * - **时间基准**：KRC 是相对行起始的偏移，YRC / QRC 是绝对毫秒
     *
     * @param wordRegex 词级标记正则
     * @param format 用来决定分组含义与时间基准
     */
    private fun parseWordSynced(
        text: String,
        wordRegex: Regex,
        format: Format,
    ): List<LyricLine> {
        val out = mutableListOf<LyricLine>()
        val qrc = format == Format.QRC
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val m = WORD_LINE_STAMP.find(line) ?: continue
            val lineStart = m.groupValues[1].toLongOrNull() ?: 0L
            val lineDuration = m.groupValues[2].toLongOrNull() ?: 0L
            val body = line.substring(m.range.last + 1)
            val cells = mutableListOf<LyricCell>()
            for (w in wordRegex.findAll(body)) {
                val g = w.groupValues
                // QRC：组1=文本 组2=起始 组3=时长
                // YRC/KRC：组1=起始 组2=时长 组3=文本
                val wordText = if (qrc) g[1] else g[3]
                val rawStart = (if (qrc) g[2] else g[1]).toLongOrNull() ?: 0L
                val dur = (if (qrc) g[3] else g[2]).toLongOrNull() ?: 0L
                val startMs = if (format == Format.KRC) lineStart + rawStart else rawStart
                cells +=
                    LyricCell(
                        text = wordText,
                        startMs = startMs,
                        endMs = if (dur > 0) startMs + dur else -1L,
                    )
            }
            if (cells.isEmpty()) continue
            out +=
                LyricLine(
                    startMs = lineStart,
                    endMs = if (lineDuration > 0) lineStart + lineDuration else -1L,
                    text = cells.joinToString("") { it.text },
                    cells = cells,
                )
        }
        return finish(out)
    }

    /**
     * 解析 ESLyric 逐字 LRC：行首时间戳作为行时间，`<mm:ss.xx>` 作为词级标记。
     *
     * 内部模型只有行级 [LyricLine]，词级时间在这一层不保留（与 SPL 一样处理）：
     * 需要逐字编辑时由打轴页自己处理，歌词设置区只关心「一行一句 + 行时间」。
     */
    private fun parseEsLyric(text: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val stamps = LRC_LINE.find(line)
            if (stamps == null) {
                lines += LyricLine(startMs = -1L, text = line)
                continue
            }
            val body = line.substring(stamps.range.last + 1)
            val first = TIMESTAMP.find(stamps.value)
            val startMs =
                if (first != null) {
                    stampToMillis(first.groupValues[2], first.groupValues[3], first.groupValues[4])
                } else {
                    -1L
                }
            lines += LyricLine(startMs = startMs, text = ANGLE_STAMP.replace(body, "").trim())
        }
        return finish(lines)
    }

    /** 解析 SRT：序号行忽略，` --> ` 两侧是起止时间，正文可能多行。 */
    private fun parseSrt(text: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        val blocks = text.split(Regex("""\R\s*\R"""))
        for (block in blocks) {
            val rows = block.trim().lines()
            if (rows.isEmpty()) continue
            val cueIndex = rows.indexOfFirst { SRT_CUE.containsMatchIn(it) }
            if (cueIndex < 0) continue
            val m = SRT_CUE.find(rows[cueIndex]) ?: continue
            val g = m.groupValues
            val startMs = srtMillis(g[1], g[2], g[3], g[4])
            val endMs = srtMillis(g[5], g[6], g[7], g[8])
            // 时间轴之后的所有行都是正文（SRT 允许一行字幕拆成多行）
            val body = rows.drop(cueIndex + 1).joinToString("\n").trim()
            if (body.isEmpty()) continue
            lines += LyricLine(startMs = startMs, text = body, endMs = endMs)
        }
        return finish(lines)
    }

    private fun srtMillis(
        h: String,
        m: String,
        s: String,
        ms: String,
    ): Long =
        h.toLongOrNull().orZero() * 3600000L +
            m.toLongOrNull().orZero() * 60000L +
            s.toLongOrNull().orZero() * 1000L +
            ms.toLongOrNull().orZero()

    /** 解析 ASS/SSA：只取 `Dialogue:` 行，跳过 `Comment:` 与样式段。 */
    private fun parseAss(text: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith("Dialogue:", ignoreCase = true)) continue
            // 字段：Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            val parts = line.substringAfter(':').split(',')
            if (parts.size < 10) continue
            val startMs = assMillis(parts[1].trim())
            val endMs = assMillis(parts[2].trim())
            if (startMs < 0) continue
            // 第 10 项起都是正文（正文本身可能含逗号，所以要重新拼回）
            val body =
                parts
                    .drop(9)
                    .joinToString(",")
                    // ASS 里 \N 是换行、\n 也当换行；花括号是样式覆盖标记
                    .replace("\\N", "\n")
                    .replace("\\n", "\n")
                    .replace(Regex("""\{[^}]*}"""), "")
                    .trim()
            if (body.isEmpty()) continue
            lines += LyricLine(startMs = startMs, text = body, endMs = endMs)
        }
        return finish(lines)
    }

    /** ASS 时间：`0:00:12.34`（百分秒）。 */
    private fun assMillis(raw: String): Long {
        val parts = raw.split(':')
        if (parts.size < 3) return -1L
        val h = parts[0].toLongOrNull() ?: return -1L
        val m = parts[1].toLongOrNull() ?: return -1L
        val secParts = parts[2].replace(',', '.').split('.')
        val s = secParts[0].toLongOrNull() ?: return -1L
        val cs = secParts
            .getOrNull(1)
            ?.take(2)
            ?.toLongOrNull()
            .orZero()
        return h * 3600000L + m * 60000L + s * 1000L + cs * 10L
    }

    private fun Long?.orZero(): Long = this ?: 0L

    /** 时间戳转毫秒。毫秒位数不足按**右补零**（SPL 标准）。 */
    fun stampToMillis(
        minutes: String,
        seconds: String,
        fraction: String?,
    ): Long {
        val min = minutes.toLongOrNull() ?: 0L
        val sec = seconds.toLongOrNull() ?: 0L
        val frac =
            when {
                fraction.isNullOrEmpty() -> 0L
                fraction.length >= 3 -> fraction.take(3).toLongOrNull() ?: 0L
                else -> fraction.padEnd(3, '0').toLongOrNull() ?: 0L
            }
        return (min * 60L + sec) * 1000L + frac
    }

    private fun parseLrc(text: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        for (raw in text.lineSequence()) {
            if (raw.isBlank()) continue
            val stamps = mutableListOf<Long>()
            var cursor = 0
            // 行首可连续多个时间戳
            while (true) {
                val match = TIMESTAMP.find(raw, cursor) ?: break
                if (match.range.first != cursor && raw.substring(cursor, match.range.first).isNotBlank()) break
                stamps += stampToMillis(match.groupValues[2], match.groupValues[3], match.groupValues[4])
                cursor = match.range.last + 1
            }
            if (stamps.isEmpty()) continue
            val body = raw.substring(cursor)
            // 时间戳后没有任何文本 = 显式行结尾标记，不产生空行
            if (stripTags(body).isBlank()) continue
            val cells = parseCells(body, stamps.first())
            for (stamp in stamps) {
                lines +=
                    LyricLine(
                        startMs = stamp,
                        text = stripTags(body),
                        cells = cells,
                    )
            }
        }
        return finish(lines)
    }

    private fun parseSpl(text: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        for (raw in text.lineSequence()) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue

            val all = TIMESTAMP.findAll(trimmed).toList()
            if (all.isEmpty()) continue

            // 行首连续时间戳 → 重复行，展开成多行
            val leading = mutableListOf<Long>()
            var cursor = 0
            for (match in all) {
                if (match.range.first != cursor) break
                leading += stampToMillis(match.groupValues[2], match.groupValues[3], match.groupValues[4])
                cursor = match.range.last + 1
            }
            if (leading.isEmpty()) continue

            val body = trimmed.substring(cursor)
            // 时间戳后没有任何文本 = 显式行结尾标记，跳过
            if (body.isBlank()) continue

            val cells = parseCells(body, leading.first())
            val plain = stripTags(body)
            for (stamp in leading) {
                lines += LyricLine(startMs = stamp, text = plain, cells = cells)
            }
        }
        return finish(lines)
    }

    /** 行内逐字标记 → 字单元。没有标记就返回空。 */
    private fun parseCells(
        body: String,
        lineStart: Long,
    ): List<LyricCell> {
        val matches = TIMESTAMP.findAll(body).toList()
        if (matches.isEmpty()) return emptyList()
        val cells = mutableListOf<LyricCell>()

        // 行首到第一个标记之间的文字：时间取行起始时间（不能丢）
        val firstStart = matches.first().range.first
        if (firstStart > 0) {
            val head = body.substring(0, firstStart)
            if (head.isNotBlank()) {
                cells += LyricCell(text = head.trim(), startMs = lineStart, endMs = -1L)
            }
        }

        for ((index, match) in matches.withIndex()) {
            val start = stampToMillis(match.groupValues[2], match.groupValues[3], match.groupValues[4])
            val textStart = match.range.last + 1
            val textEnd = if (index + 1 < matches.size) matches[index + 1].range.first else body.length
            if (textStart >= textEnd) continue
            val cellText = body.substring(textStart, textEnd)
            if (cellText.isBlank()) continue
            cells += LyricCell(text = cellText.trim(), startMs = start, endMs = -1L)
        }

        // 每个字单元的结束时间 = 下一个字的开始时间
        return cells.mapIndexed { index, cell ->
            if (index + 1 < cells.size) cell.copy(endMs = cells[index + 1].startMs) else cell
        }
    }

    /** 去掉文本里的时间标签。 */
    private fun stripTags(body: String): String = TIMESTAMP.replace(body, "").trim()

    // ── TTML（Apple Music / AMLL） ────────────────────────────────────────────

    /**
     * 解析 TTML（Apple Music / AMLL）。
     *
     * 结构：`<p begin end ttm:agent itunes:key>` 是一行，其内 `<span begin end>` 是词/字单元。
     *
     * 三处分流都看**属性值**，不按 `xml:id` 猜：
     * - `ttm:role="x-translation"` → 译文轨道，`"x-roman"` → 罗马音轨道。
     *   两者写成**与主行同一时间戳的后续行** —— 与 KRC / YRC 的轨道约定一致
     *   （见 [io.github.univers629.spwtagworkbench.data.Repo] 的 `filterLyricTracks`：
     *   同一时间戳第 1/2/3 条依次是原文 / 译文 / 罗马音），因此三个语言开关对 TTML 同样生效。
     *   它们**不进正文**，也不产生没有时间的假字单元。
     * - `ttm:role="x-bg"` → 背景人声，从正文 cell 里剥离，按惯例挂到行尾括号内。
     * - `ttm:agent` 只表示演唱者，**不参与过滤**。`xml:id` 是文件里任意分配的：
     *   实测 Apple 把对唱的另一方写成 `v2` + `type="other"`，那是**正文**；
     *   背景人声靠的是 `ttm:role="x-bg"`。按 `v2`/`v3` 丢行会把对唱歌曲的一半歌词删掉。
     *
     * 空白按 TTML 规范处理：TTML 是空白敏感的，`</span> <span>` 之间的行内空白是
     * **词间空格**，必须归并到前一个单元，否则 `We're` + `swaying` 会粘成 `We'reswaying`。
     * 所以这里遍历**子节点**（含文本节点），而不是只取 `<span>` 元素。
     *
     * 用 JDK 自带的 DOM 解析，不引第三方 XML 库；同时禁掉外部实体，避免恶意歌词文件触发 XXE。
     */
    private fun parseTtml(text: String): List<LyricLine> {
        val factory =
            javax.xml.parsers.DocumentBuilderFactory
                .newInstance()
        factory.isNamespaceAware = true
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        factory.isExpandEntityReferences = false

        val doc =
            try {
                factory
                    .newDocumentBuilder()
                    .parse(text.trim().byteInputStream(Charsets.UTF_8))
            } catch (t: Throwable) {
                // XML 坏了就当纯文本，至少有字可看
                return text
                    .lineSequence()
                    .filter { it.isNotBlank() && !it.trimStart().startsWith("<") }
                    .map { LyricLine(startMs = -1L, text = it.trim()) }
                    .toList()
            }

        // 主行与它的两条附加轨道必须成对保存：附加轨道与主行**同时间戳**，
        // 不能混进主行列表一起参与 finish()（那会把主行的结束时间压成等于起始）。
        val dated = mutableListOf<DatedLine>()
        val paragraphs = doc.getElementsByTagNameNS("*", "p")

        for (i in 0 until paragraphs.length) {
            val p = paragraphs.item(i) as? org.w3c.dom.Element ?: continue

            val scan = TtmlScan()
            scanTtmlChildren(p, scan, inBackground = false)

            val cells = trimTtmlCellEdges(scan.main)
            val bodyText = cells.joinToString("") { it.text }
            val background = scan.background.toString().trim()

            // 背景人声不占 cell，按惯例附在行尾括号内
            val lineText =
                when {
                    bodyText.isEmpty() -> if (background.isEmpty()) "" else "($background)"
                    background.isEmpty() -> bodyText
                    else -> "$bodyText ($background)"
                }
            if (lineText.isBlank()) continue

            val lineStart = ttmlTime(p.getAttribute("begin"))
            val lineEnd = ttmlTime(p.getAttribute("end"))
            // 行时间：p 上没有就取第一个带时间的单元
            val start = if (lineStart >= 0) lineStart else cells.firstOrNull()?.startMs ?: -1L

            dated +=
                DatedLine(
                    line =
                        LyricLine(
                            startMs = start,
                            endMs = lineEnd,
                            text = lineText,
                            cells = cells.filter { it.startMs >= 0 },
                        ),
                    translation = scan.translation.toString().trim(),
                    romanization = scan.romanization.toString().trim(),
                )
        }

        // 先只对主行排序并补结束时间，再按「原文 → 译文 → 罗马音」展开
        val timed = dated.filter { it.line.startMs >= 0 }.sortedBy { it.line.startMs }
        val untimed = dated.filter { it.line.startMs < 0 }
        val finished = finish(timed.map { it.line })
        val lines = mutableListOf<LyricLine>()
        // 用 zip 把「补完结束时间的行」与「它的附加轨道」配对，不靠下标对齐：
        // finish() 内部会再排一次序，若日后改了排序策略，按下标取会静默错配。
        for ((line, source) in finished.zip(timed)) {
            lines += line
            if (source.translation.isNotEmpty()) {
                lines += LyricLine(startMs = line.startMs, endMs = line.endMs, text = source.translation)
            }
            if (source.romanization.isNotEmpty()) {
                lines += LyricLine(startMs = line.startMs, endMs = line.endMs, text = source.romanization)
            }
        }
        // 无时间的行保持原序跟在后面（含它们自己的轨道）
        for (source in untimed) {
            lines += source.line
            if (source.translation.isNotEmpty()) {
                lines += LyricLine(startMs = -1L, text = source.translation)
            }
            if (source.romanization.isNotEmpty()) {
                lines += LyricLine(startMs = -1L, text = source.romanization)
            }
        }
        return lines
    }

    /** 一行主歌词 + 它的译文 / 罗马音轨道（两者都可能为空串）。 */
    private data class DatedLine(
        val line: LyricLine,
        val translation: String,
        val romanization: String,
    )

    /** 一趟 `<p>` 扫描的中间结果：主轨字单元 + 三条附加轨道。 */
    private class TtmlScan {
        val main = mutableListOf<LyricCell>()
        val translation = StringBuilder()
        val romanization = StringBuilder()
        val background = StringBuilder()
    }

    /**
     * 遍历 TTML 行内的子节点，按 `ttm:role` 分流。
     *
     * **必须保留文本节点**：词间空格就写在 `</span> <span>` 之间，只取 span 元素会把它丢掉。
     */
    private fun scanTtmlChildren(
        parent: org.w3c.dom.Node,
        scan: TtmlScan,
        inBackground: Boolean,
    ) {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i) ?: continue
            when (node.nodeType) {
                org.w3c.dom.Node.TEXT_NODE, org.w3c.dom.Node.CDATA_SECTION_NODE -> {
                    val raw = ttmlInlineText(node.nodeValue)
                    if (raw.isEmpty()) continue
                    // 空白属于前一个单元（TTML 空白敏感）
                    if (inBackground) scan.background.append(raw) else appendTtmlInline(raw, scan.main)
                }

                org.w3c.dom.Node.ELEMENT_NODE -> {
                    val element = node as org.w3c.dom.Element
                    when (attributeByLocalName(element, "role").lowercase()) {
                        // 译文 / 罗马音：整段进独立轨道，不进正文；也不做字单元
                        "x-translation" -> scan.translation.append(ttmlInlineText(element.textContent))
                        "x-roman" -> scan.romanization.append(ttmlInlineText(element.textContent))
                        // 背景人声：递归收集，正文里不留它
                        "x-bg" -> scanTtmlChildren(element, scan, inBackground = true)
                        else -> {
                            val begin = ttmlTime(element.getAttribute("begin"))
                            if (!inBackground && begin >= 0L) {
                                // 带时间的 span = 一个词 / 字单元
                                val end = ttmlTime(element.getAttribute("end"))
                                val cellText = ttmlInlineText(element.textContent)
                                if (cellText.isNotEmpty()) {
                                    scan.main +=
                                        LyricCell(
                                            text = cellText,
                                            startMs = begin,
                                            endMs = if (end >= begin) end else -1L,
                                        )
                                }
                            } else {
                                // 容器 span（自身无时间）或背景轨：继续向下拆
                                scanTtmlChildren(element, scan, inBackground)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 把行内文本并入主轨。
     *
     * 已有字单元时**合并到前一个单元的末尾**（`</span> <span>` 之间的空格属于前一个词）；
     * 一个单元都还没有时（`<p>纯文本</p>` 或行首就有文字）先存一个无时间的单元，
     * 它最后会作为整行文本保留下来。
     */
    private fun appendTtmlInline(
        raw: String,
        main: MutableList<LyricCell>,
    ) {
        if (raw.isEmpty()) return
        if (main.isEmpty()) {
            main += LyricCell(text = raw)
            return
        }
        val last = main[main.size - 1]
        main[main.size - 1] = last.copy(text = last.text + raw)
    }

    /**
     * 去掉整行首尾的空白。
     *
     * 只动第一个单元的开头与最后一个单元的末尾：词间空格必须留着，
     * 否则 `We're` / `swaying` 的间隔又没了。
     */
    private fun trimTtmlCellEdges(cells: List<LyricCell>): List<LyricCell> {
        if (cells.isEmpty()) return emptyList()
        val out = cells.toMutableList()
        out[0] = out[0].copy(text = out[0].text.trimStart())
        val last = out.size - 1
        out[last] = out[last].copy(text = out[last].text.trimEnd())
        return out.filter { it.text.isNotEmpty() }
    }

    /**
     * 行内文本的空白处理。
     *
     * TTML 是空白敏感的：`</span> <span>` 之间那个空格是**词间分隔**，必须保留。
     * 但同一段行内文本可能来自两种排版，得区分开：
     *
     * | 输入片段 | 含义 | 处理 |
     * |---|---|---|
     * | `" "`（压缩文件里的单个空格） | 词间分隔 | 保留为一个空格 |
     * | `"\n      "`（美化排版的缩进） | 排版噪声 | 丢弃 |
     * | `"abc\n  def"`（混合内容） | 真实文本 | 折叠为单个空格 |
     *
     * 判据是「整段都是空白 **且** 含换行」→ 排版缩进。真实 Apple / amll-ttml-db 的文件
     * 是压缩过的（只有第一种），但用户手写或编辑器格式化后的文件会引入第二种；
     * 若不区分，`こん` 与 `にちは` 之间会被塞进一个由缩进折叠成的空格。
     *
     * 行首/行尾的残留空格由 [trimTtmlCellEdges] 去掉。
     */
    private fun ttmlInlineText(value: String?): String {
        val raw = value.orEmpty()
        if (raw.isEmpty()) return raw
        // 整段纯空白且含换行 → 是排版缩进，不是词间空格
        if (raw.isBlank() && (raw.contains('\n') || raw.contains('\r'))) return ""
        return WHITESPACE_RUN.replace(raw, " ")
    }

    /** 连续空白（含换行）。折叠成单个空格用。 */
    private val WHITESPACE_RUN = Regex("""\s+""")

    /** 按 local name 取属性，忽略命名空间前缀（`ttm:agent` / `agent` 都能取到）。 */
    private fun attributeByLocalName(
        element: org.w3c.dom.Element,
        localName: String,
    ): String {
        val attrs = element.attributes
        for (i in 0 until attrs.length) {
            val node = attrs.item(i) ?: continue
            val name = node.localName ?: node.nodeName
            if (name.equals(localName, ignoreCase = true)) return node.nodeValue.orEmpty()
        }
        return ""
    }

    /**
     * TTML 时间值转毫秒。
     *
     * 支持四种写法，**全部由 Apple / AMLL 的真实文件实测确认**：
     * - `MM:SS.fff` —— 实测**主用**格式（如 `00:00.515`、`20.519`、`4:04.497`）
     * - `HH:MM:SS.fff` —— 规范允许，三种冒号段
     * - `12.3s` / `12.3ms` —— offset-time
     * - `HH:MM:SS:ff` —— 帧数，按 [DEFAULT_TTML_FRAME_RATE] 折算
     *
     * 关键点：**`MM:SS.fff` 只有两段**。此前要求冒号分段 ≥ 3，导致真实文件中
     * 每一个时间值都返回 -1（整首歌解析成"无时间轴、无逐字"），是 TTML 路径
     * 完全失效的根因。冒号段数由此改为 2 段起。
     *
     * 解析不了返回 -1（由调用方跳过该行）。
     */
    private fun ttmlTime(raw: String?): Long {
        val v = raw?.trim().orEmpty()
        if (v.isEmpty()) return -1L
        if (v.endsWith("ms", ignoreCase = true)) {
            return v.dropLast(2).toDoubleOrNull()?.toLong() ?: -1L
        }
        if (v.endsWith("s", ignoreCase = true)) {
            return ((v.dropLast(1).toDoubleOrNull() ?: return -1L) * 1000.0).toLong()
        }
        // 纯秒（无冒号无单位）：Apple 的 AMLL 文件里出现过 `20.519` / `23.530`
        if (!v.contains(':')) {
            return v.toDoubleOrNull()?.let { (it * 1000.0).toLong() } ?: -1L
        }
        val parts = v.split(':')
        // MM:SS.fff（两段）/ HH:MM:SS.fff（三段）/ HH:MM:SS:ff（四段）
        if (parts.size < 2) return -1L
        // 段数不同，首段含义不同：三段/四段时首段是小时，两段时首段是分钟
        val hours = if (parts.size >= 3) parts[0].toLongOrNull() ?: return -1L else 0L
        val minutes = if (parts.size >= 3) parts[1].toLongOrNull() ?: return -1L else parts[0].toLongOrNull() ?: return -1L
        val secPart = parts[if (parts.size >= 3) 2 else 1]
        val base = (hours * 3600L + minutes * 60L) * 1000L
        val dot = secPart.indexOf('.')
        if (dot >= 0) {
            val s = secPart.substring(0, dot).toLongOrNull() ?: return -1L
            val frac = secPart.substring(dot + 1)
            val ms = frac.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
            return base + s * 1000L + ms
        }
        // HH:MM:SS:ff —— 帧数按 [DEFAULT_TTML_FRAME_RATE] 折算
        if (parts.size >= 4) {
            val s = secPart.toLongOrNull() ?: return -1L
            val f = parts[3].toLongOrNull() ?: 0L
            return base + s * 1000L + f * (1000L / DEFAULT_TTML_FRAME_RATE)
        }
        val s = secPart.toLongOrNull() ?: return -1L
        return base + s * 1000L
    }

    /** 排序并补全每行的结束时间。 */
    private fun finish(lines: List<LyricLine>): List<LyricLine> {
        val sorted = lines.sortedBy { it.startMs }
        return sorted.mapIndexed { index, line ->
            if (line.endMs >= 0L) {
                line
            } else {
                line.copy(endMs = sorted.getOrNull(index + 1)?.startMs ?: -1L)
            }
        }
    }

    /** 写出标准 LRC。 */
    fun toLrc(lines: List<LyricLine>): String {
        val sb = StringBuilder()
        for (line in lines) {
            val start = line.startMs.coerceAtLeast(0L)
            val min = start / 60000
            val sec = (start % 60000) / 1000
            val cs = (start % 1000) / 10
            sb
                .append('[')
                .append(min.toString().padStart(2, '0'))
                .append(':')
                .append(sec.toString().padStart(2, '0'))
                .append('.')
                .append(cs.toString().padStart(2, '0'))
                .append(']')
                .append(line.text)
                .append('\n')
        }
        return sb.toString()
    }

    /**
     * 把歌词转换成指定格式的文本。
     *
     * 所有格式都走「行列表 → 序列化」这条路，保证时间轴一致；
     * 纯文本则直接丢掉时间轴。
     */
    fun toFormat(
        lines: List<LyricLine>,
        format: Format,
    ): String =
        when (format) {
            Format.LRC -> toLrc(lines)
            Format.ESLYRIC -> toEsLrc(lines)
            Format.SPL -> toSpl(lines)
            Format.YRC -> toWordSynced(lines, Format.YRC)
            Format.KRC -> toWordSynced(lines, Format.KRC)
            Format.QRC -> toWordSynced(lines, Format.QRC)
            Format.TTML -> toLrc(lines)
            Format.SRT -> toSrt(lines)
            Format.ASS -> toAss(lines)
            Format.PLAIN -> lines.joinToString("\n") { it.text }
        }

    /**
     * 写出逐字歌词（YRC / KRC / QRC 三种结构）。
     *
     * 三者行首都是 `[起始ms,时长ms]`，差异在词级标记：
     * - YRC：`字(绝对起始,时长,0)`
     * - KRC：`<相对偏移,时长,0>字`
     * - QRC：`字(绝对起始,时长)`
     *
     * 没有 [LyricLine.cells] 逐字数据时，退化成「整行一个词」，内容仍是合法的。
     */
    fun toWordSynced(
        lines: List<LyricLine>,
        format: Format,
    ): String {
        val sb = StringBuilder()
        for (line in lines) {
            val start = line.startMs.coerceAtLeast(0L)
            val end = if (line.endMs > start) line.endMs else start
            sb
                .append('[')
                .append(start)
                .append(',')
                .append(end - start)
                .append(']')
            val cells =
                line.cells.ifEmpty {
                    listOf(LyricCell(text = line.text, startMs = start, endMs = end))
                }
            for (cell in cells) {
                val cs = cell.startMs.coerceAtLeast(start)
                val ce = if (cell.endMs > cs) cell.endMs else cs
                when (format) {
                    // YRC：时间在前、文本在后 —— `(起始,时长,0)字`
                    Format.YRC ->
                        sb
                            .append('(')
                            .append(cs)
                            .append(',')
                            .append(ce - cs)
                            .append(",0)")
                            .append(cell.text)
                    // KRC：`<行内偏移,时长,0>字`
                    Format.KRC ->
                        sb
                            .append('<')
                            .append(cs - start)
                            .append(',')
                            .append(ce - cs)
                            .append(",0>")
                            .append(cell.text)
                    // QRC：文本在前、时间在后 —— `字(起始,时长)`
                    else ->
                        sb
                            .append(cell.text)
                            .append('(')
                            .append(cs)
                            .append(',')
                            .append(ce - cs)
                            .append(')')
                }
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * 写出 ESLyric 逐字 LRC：行首行时间 + 每字前 `<mm:ss.xx>`。
     *
     * 没有 [LyricLine.cells] 逐字数据时退化成普通 LRC —— 比硬造假时间诚实。
     */
    fun toEsLrc(lines: List<LyricLine>): String {
        val sb = StringBuilder()
        for (line in lines) {
            val start = line.startMs.coerceAtLeast(0L)
            sb.append(lrcStamp(start))
            if (line.cells.isEmpty()) {
                sb.append(line.text)
            } else {
                for (cell in line.cells) {
                    sb.append('<').append(angleStamp(cell.startMs.coerceAtLeast(0L))).append('>')
                    sb.append(cell.text)
                }
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * 写出 SPL：每个字前用 `[mm:ss.xx]` 逐字标记。
     *
     * 与 ESLyric 的唯一差别是括号形式（方括号 vs 尖括号），这是两种格式的定义差异。
     */
    fun toSpl(lines: List<LyricLine>): String {
        val sb = StringBuilder()
        for (line in lines) {
            if (line.cells.isEmpty()) {
                sb.append(lrcStamp(line.startMs.coerceAtLeast(0L))).append(line.text)
            } else {
                for (cell in line.cells) {
                    sb.append(lrcStamp(cell.startMs.coerceAtLeast(0L))).append(cell.text)
                }
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * 把 QRC **原始机器格式**转成结构化歌词（SPL 的 `[t]词[t]词[t]` 写法）。
     *
     * QRC 原文长这样：
     * ```
     * [273,759]猎(273,543)户(816,36)星(852,36)座(888,36)
     * ```
     * 行首 `[起始,时长]`、每个字 `(起始,时长)`，全是毫秒数字。
     * 它**不属于本项目支持的任何格式**（LRC / SPL / ESLyric / SRT / ASS / 纯文本），
     * 直接交给界面就会显示成一堆数字，编辑器与格式下拉都认不出。
     *
     * 所以源站返回后必须先转成可编辑的文本：每个字前面加标准 LRC 时间戳。
     * 这与参考项目 `StructuredLyrics.render` 的做法一致——它的输出正是
     * `[t]词[t]词[t]`（SPL），逐字时间完整保留在每个字前后的 `[t]` 里。
     *
     * @return 转换后的文本；输入不是 QRC 或解析不出行时返回空串
     */
    fun qrcToStructured(raw: String): String {
        if (raw.isBlank()) return ""
        // QRC 原文可能带 `QRC` / `hex=` 前缀（不同接口版本），先剥掉再判别
        val text = raw.trim()
        if (!text.lineSequence().any { WORD_LINE_STAMP.containsMatchIn(it.trimStart()) }) return ""
        val lines = parseWordSynced(text, QRC_WORD, format = Format.QRC)
        if (lines.isEmpty()) return ""
        return toSpl(lines)
    }

    /**
     * 剥掉源站歌词里的文本头标签，只保留时间轴行。
     *
     * KRC 会带 `[id:] / [ti:] / [ar:] / [al:] / [by:] / [language:]` 六类头标签，
     * 其中 `[language:]` 的值是数 KB 的 Base64(JSON)：原样留在文本里，界面就会
     * 显示成一屏乱码。解析器本来就只认时间轴开头的行，剥掉不影响时间轴。
     */
    fun stripHeaderTags(text: String): String =
        text
            .lineSequence()
            .filterNot { HEADER_TAG.containsMatchIn(it.trimStart()) }
            .joinToString("\n")
            .trim()

    /**
     * 把 KRC 转成结构化歌词（SPL 写法），并把 `[language:]` 里的译文与罗马音并入。
     *
     * 与 [qrcToStructured] 同一条思路：源站返回的机器格式先转成可编辑文本再交给界面。
     * KRC 比 QRC 多一条 `[language:]` 轨道，解出来是：
     * `{"content":[{"lyricContent":[[…]],"type":0},{"lyricContent":[[…]],"type":1}],"version":1}`，
     * `type = 1` 是逐行翻译、`type = 0` 是逐字罗马音。
     *
     * 项目里多语言轨道的约定是「同一时间戳的第 1/2/3 条依次为原文/译文/罗马音」
     * （见 `Repo.filterLyricTracks`），因此输出按 原文 → 译文 → 罗马音 的顺序排列，
     * 三个开关对酷狗歌词同样生效。
     *
     * @return 转换后的文本；输入不是 KRC 或解析不出行时返回空串
     */
    fun krcToStructured(raw: String): String {
        if (raw.isBlank()) return ""
        val body = stripHeaderTags(raw)
        if (!body.lineSequence().any { WORD_LINE_STAMP.containsMatchIn(it.trimStart()) }) return ""
        val lines = parseWordSynced(body, KRC_WORD, format = Format.KRC)
        if (lines.isEmpty()) return ""
        val tracks = krcLanguageTracks(raw)
        val sb = StringBuilder()
        for ((index, line) in lines.withIndex()) {
            val stamp = lrcStamp(line.startMs.coerceAtLeast(0L))
            if (line.cells.isEmpty()) {
                sb.append(stamp).append(line.text)
            } else {
                for (cell in line.cells) {
                    sb.append(lrcStamp(cell.startMs.coerceAtLeast(0L))).append(cell.text)
                }
            }
            sb.append('\n')
            tracks.translation.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                sb.append(stamp).append(it).append('\n')
            }
            tracks.romanization.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                sb.append(stamp).append(it).append('\n')
            }
        }
        return sb.toString().trimEnd()
    }

    /** `[language:]` 解出的两条轨道，缺失时为空列表。 */
    private data class LanguageTracks(
        val translation: List<String> = emptyList(),
        val romanization: List<String> = emptyList(),
    )

    /**
     * 解析 KRC 的 `[language:<Base64>]` 头标签。
     *
     * Base64 解出 JSON：`content` 每项带 `type` 与 `lyricContent`，`lyricContent`
     * 按行给出逐字片段数组，拼接后是整行文本。Base64 可能省略 `=` 补位，先补齐。
     * 任何一步失败都返回空轨道，原文不受影响。
     */
    private fun krcLanguageTracks(raw: String): LanguageTracks {
        val tag =
            raw
                .lineSequence()
                .map { it.trimStart() }
                .firstOrNull { it.startsWith("[language:") }
                ?: return LanguageTracks()
        val payload = tag.removePrefix("[language:").substringBeforeLast(']', "")
        if (payload.isEmpty()) return LanguageTracks()
        val json =
            runCatching {
                val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
                val decoded = String(
                    java.util.Base64
                        .getDecoder()
                        .decode(padded),
                    Charsets.UTF_8,
                )
                Json.parse(decoded)
            }.getOrNull() ?: return LanguageTracks()
        var translation: List<String> = emptyList()
        var romanization: List<String> = emptyList()
        for (track in json.arr("content")) {
            val lines =
                track.arr("lyricContent").map { row ->
                    row
                        .let { it as? Json.Arr }
                        ?.items
                        .orEmpty()
                        .mapNotNull { (it as? Json.Str)?.value }
                        .joinToString("")
                }
            when (track.num("type")?.toInt()) {
                1 -> translation = lines
                0 -> romanization = lines
            }
        }
        return LanguageTracks(translation, romanization)
    }

    /**
     * 把 YRC 转成结构化歌词（SPL），并把译文与罗马音并入同时间戳轨道。
     *
     * YRC 行格式是 `[行起始ms,行时长](字绝对起始ms,字时长,0)字`，词级时间用绝对毫秒，
     * 与 KRC 的相对偏移不同。译文与罗马音来自歌词接口的 `tlyric` / `romalrc`（普通 LRC），
     * 与逐字行按起始时间对齐，容差见 [TRACK_TOLERANCE_MS]。
     *
     * @return 转换后的文本；输入不是 YRC 或解析不出行时返回空串
     */
    fun yrcToStructured(
        raw: String,
        translation: String? = null,
        romanization: String? = null,
    ): String {
        if (raw.isBlank()) return ""
        val lines = parseWordSynced(stripHeaderTags(raw), YRC_WORD, format = Format.YRC)
        if (lines.isEmpty()) return ""
        val trans = trackIndex(translation)
        val roma = trackIndex(romanization)
        val sb = StringBuilder()
        for (line in lines) {
            val stamp = lrcStamp(line.startMs.coerceAtLeast(0L))
            if (line.cells.isEmpty()) {
                sb.append(stamp).append(line.text)
            } else {
                for (cell in line.cells) {
                    sb.append(lrcStamp(cell.startMs.coerceAtLeast(0L))).append(cell.text)
                }
            }
            sb.append('\n')
            trackAt(trans, line.startMs)?.let { sb.append(stamp).append(it).append('\n') }
            trackAt(roma, line.startMs)?.let { sb.append(stamp).append(it).append('\n') }
        }
        return sb.toString().trimEnd()
    }

    /**
     * 把普通 LRC 与译文 / 罗马音合并成同一时间戳的多行文本。
     *
     * 没有可合并的轨道时原样返回（保留重复时间戳等原始写法），避免无谓改写。
     */
    fun mergeLrcTracks(
        lrc: String,
        translation: String? = null,
        romanization: String? = null,
    ): String {
        val body = stripHeaderTags(lrc)
        if (body.isBlank()) return ""
        val trans = trackIndex(translation)
        val roma = trackIndex(romanization)
        if (trans.isEmpty() && roma.isEmpty()) return body
        val sb = StringBuilder()
        for (line in parse(body)) {
            val stamp = lrcStamp(line.startMs.coerceAtLeast(0L))
            sb.append(stamp).append(line.text).append('\n')
            if (line.startMs < 0L) continue
            trackAt(trans, line.startMs)?.let { sb.append(stamp).append(it).append('\n') }
            trackAt(roma, line.startMs)?.let { sb.append(stamp).append(it).append('\n') }
        }
        return sb.toString().trimEnd()
    }

    /** 译文 / 罗马音与逐字行的对齐容差：两边时间戳来源不同，允许几十毫秒偏差。 */
    private const val TRACK_TOLERANCE_MS = 60L

    /** 把译文 / 罗马音的 LRC 文本按行起始时间索引。 */
    private fun trackIndex(track: String?): List<Pair<Long, String>> =
        if (track.isNullOrBlank()) {
            emptyList()
        } else {
            parse(track)
                .filter { it.startMs >= 0L && it.text.isNotBlank() }
                .map { it.startMs to it.text.trim() }
        }

    /** 取与逐字行时间最接近的一条轨道文本，超出容差返回 null。 */
    private fun trackAt(
        track: List<Pair<Long, String>>,
        startMs: Long,
    ): String? {
        if (track.isEmpty()) return null
        val hit = track.minByOrNull { kotlin.math.abs(it.first - startMs) } ?: return null
        if (kotlin.math.abs(hit.first - startMs) > TRACK_TOLERANCE_MS) return null
        return hit.second.takeIf { it.isNotEmpty() }
    }

    /** `[mm:ss.xx]` 行级时间戳。 */
    private fun lrcStamp(millis: Long): String =
        buildString {
            append('[')
            append((millis / 60000).toString().padStart(2, '0')).append(':')
            append((millis / 1000 % 60).toString().padStart(2, '0')).append('.')
            append((millis % 1000 / 10).toString().padStart(2, '0'))
            append(']')
        }

    /** `<mm:ss.xx>` 词级时间戳（ESLyric 用）。 */
    private fun angleStamp(millis: Long): String =
        buildString {
            append((millis / 60000).toString().padStart(2, '0')).append(':')
            append((millis / 1000 % 60).toString().padStart(2, '0')).append('.')
            append((millis % 1000 / 10).toString().padStart(2, '0'))
        }

    /** 写出 SRT。结束时间缺失时按下一行起点或 +5s 推算。 */
    fun toSrt(lines: List<LyricLine>): String {
        val sb = StringBuilder()
        val timed = lines.filter { it.startMs >= 0L }
        timed.forEachIndexed { index, line ->
            val start = line.startMs
            val end =
                if (line.endMs > start) {
                    line.endMs
                } else {
                    val next = timed.getOrNull(index + 1)?.startMs
                    if (next != null && next > start) next - 10 else start + 5000
                }
            sb.append(index + 1).append('\n')
            sb
                .append(srtTime(start))
                .append(" --> ")
                .append(srtTime(end))
                .append('\n')
            sb.append(line.text).append('\n')
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    /** SRT 时间格式：`hh:mm:ss,mmm`。 */
    private fun srtTime(millis: Long): String {
        val m = millis.coerceAtLeast(0L)
        return buildString {
            append((m / 3600000).toString().padStart(2, '0')).append(':')
            append((m / 60000 % 60).toString().padStart(2, '0')).append(':')
            append((m / 1000 % 60).toString().padStart(2, '0')).append(',')
            append((m % 1000).toString().padStart(3, '0'))
        }
    }

    /** 写出 ASS。带最小可用的 Script Info / Styles 段，播放器能直接加载。 */
    fun toAss(lines: List<LyricLine>): String {
        val sb = StringBuilder()
        sb.append("[Script Info]\n")
        sb.append("ScriptType: v4.00+\n")
        sb.append("Timer: 100.0000\n\n")
        sb.append("[V4+ Styles]\n")
        sb.append(
            "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, " +
                "OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, " +
                "ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, " +
                "Alignment, MarginL, MarginR, MarginV, Encoding\n",
        )
        sb.append(
            "Style: Lyrics,MiSans,20,&H00FFFFFF,&H000000FF,&H00000000," +
                "&H00000000,0,0,0,0,100,100,0,0,1,2,2,2,10,10,10,1\n\n",
        )
        sb.append("[Events]\n")
        sb.append("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")
        val timed = lines.filter { it.startMs >= 0L }
        timed.forEachIndexed { index, line ->
            val start = line.startMs
            val end =
                if (line.endMs > start) {
                    line.endMs
                } else {
                    val next = timed.getOrNull(index + 1)?.startMs
                    if (next != null && next > start) next - 10 else start + 5000
                }
            sb.append("Dialogue: 0,")
            sb.append(assTime(start)).append(',').append(assTime(end))
            sb.append(",Lyrics,,0,0,0,,")
            // ASS 用 \N 换行；花括号必须转义，否则会被当成样式标记
            sb.append(
                line.text
                    .replace("\\", "\\\\")
                    .replace("{", "\\{")
                    .replace("\n", "\\N"),
            )
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    /** ASS 时间格式：`h:mm:ss.cc`（百分秒）。 */
    private fun assTime(millis: Long): String {
        val m = millis.coerceAtLeast(0L)
        return buildString {
            append(m / 3600000).append(':')
            append((m / 60000 % 60).toString().padStart(2, '0')).append(':')
            append((m / 1000 % 60).toString().padStart(2, '0')).append('.')
            append(((m % 1000) / 10).toString().padStart(2, '0'))
        }
    }
}
