// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.net

/**
 * 极简 JSON 解析。
 *
 * 只为一个目的存在：解析音源接口的响应，不引入外部依赖（宿主没打包 JSON 库）。
 * 支持对象 / 数组 / 字符串 / 数字 / 布尔 / null，忽略空白。
 * 解析失败抛 [JsonException]，由调用方转成「该源返回格式异常」。
 */
sealed interface Json {
    data class Obj(
        val values: Map<String, Json>,
    ) : Json

    data class Arr(
        val items: List<Json>,
    ) : Json

    data class Str(
        val value: String,
    ) : Json

    data class Num(
        val value: Double,
    ) : Json

    data class Bool(
        val value: Boolean,
    ) : Json

    data object Null : Json

    // ── 取值辅助 ────────────────────────────────────────────────────────────

    fun str(key: String): String? =
        (this as? Obj)?.values?.get(key).let {
            when (it) {
                is Str -> it.value
                is Num -> if (it.value % 1.0 == 0.0) it.value.toLong().toString() else it.value.toString()
                is Bool -> it.value.toString()
                else -> null
            }
        }

    fun num(key: String): Double? =
        when (val v = (this as? Obj)?.values?.get(key)) {
            is Num -> v.value
            is Str -> v.value.toDoubleOrNull()
            else -> null
        }

    fun bool(key: String): Boolean? =
        when (val v = (this as? Obj)?.values?.get(key)) {
            is Bool -> v.value
            is Str -> v.value.equals("true", ignoreCase = true)
            else -> null
        }

    fun obj(key: String): Json? = (this as? Obj)?.values?.get(key)?.takeIf { it is Obj }

    fun arr(key: String): List<Json> =
        (this as? Obj)?.values?.get(key)?.let {
            (it as? Arr)?.items
        } ?: emptyList()

    companion object {
        fun parse(text: String): Json = Parser(text).parseValue()
    }

    class JsonException(
        message: String,
    ) : Exception(message)
}

private class Parser(
    private val text: String,
) {
    private var index = 0

    /**
     * 当前嵌套深度。
     *
     * 解析器是递归下降：[parseObject] / [parseArray] 互相递归，
     * 一条形如 `[[[[…]]]]` 的响应会让栈随深度线性增长，最终抛
     * `StackOverflowError`。该错误属 `Throwable`，虽然会被调用方的
     * catch 接住，但**栈已耗尽、恢复不可靠**，所以要在到达之前主动拒绝。
     *
     * 音源响应的真实嵌套通常不超过 8 层，[MAX_DEPTH] 留了足够余量。
     */
    private var depth = 0

    fun parseValue(): Json {
        skipWhitespace()
        if (index >= text.length) throw Json.JsonException("空文档")
        return when (val c = text[index]) {
            '{' -> enterDepth { parseObject() }
            '[' -> enterDepth { parseArray() }
            '"' -> Json.Str(parseString())
            't' -> parseLiteral("true", Json.Bool(true))
            'f' -> parseLiteral("false", Json.Bool(false))
            'n' -> parseLiteral("null", Json.Null)
            else -> if (c == '-' || c.isDigit()) parseNumber() else throw Json.JsonException("意外字符 '$c' @ $index")
        }
    }

    private companion object {
        /** 允许的最大嵌套层数：音源响应实际不超过 8 层，64 留足余量。 */
        const val MAX_DEPTH = 64
    }

    /** 进入一层嵌套，超过 [MAX_DEPTH] 立即拒绝；退出时无论成败都还原深度。 */
    private fun <T> enterDepth(block: () -> T): T {
        if (++depth > MAX_DEPTH) {
            depth--
            throw Json.JsonException("嵌套超过 $MAX_DEPTH 层 @ $index")
        }
        return try {
            block()
        } finally {
            depth--
        }
    }

    private fun parseObject(): Json {
        expect('{')
        val values = LinkedHashMap<String, Json>()
        skipWhitespace()
        if (peek() == '}') {
            index++
            return Json.Obj(values)
        }
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            values[key] = parseValue()
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                '}' -> {
                    index++
                    return Json.Obj(values)
                }

                else -> throw Json.JsonException("对象里期望 , 或 } @ $index")
            }
        }
    }

    private fun parseArray(): Json {
        expect('[')
        val items = ArrayList<Json>()
        skipWhitespace()
        if (peek() == ']') {
            index++
            return Json.Arr(items)
        }
        while (true) {
            items += parseValue()
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                ']' -> {
                    index++
                    return Json.Arr(items)
                }

                else -> throw Json.JsonException("数组里期望 , 或 ] @ $index")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (index < text.length) {
            val c = text[index++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (index >= text.length) throw Json.JsonException("字符串未结束")
                    when (val esc = text[index++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (index + 4 > text.length) throw Json.JsonException("\\u 转义不完整")
                            val hex = text.substring(index, index + 4)
                            index += 4
                            sb.append(hex.toInt(16).toChar())
                        }

                        else -> throw Json.JsonException("未知转义 \\$esc")
                    }
                }

                else -> sb.append(c)
            }
        }
        throw Json.JsonException("字符串未结束")
    }

    private fun parseNumber(): Json {
        val start = index
        if (peek() == '-') index++
        while (index < text.length &&
            (
                text[index].isDigit() ||
                    text[index] == '.' ||
                    text[index] == 'e' ||
                    text[index] == 'E' ||
                    text[index] == '+' ||
                    text[index] == '-'
            )
        ) {
            index++
        }
        val raw = text.substring(start, index)
        return Json.Num(raw.toDoubleOrNull() ?: throw Json.JsonException("非法数字 '$raw'"))
    }

    private fun parseLiteral(
        literal: String,
        value: Json,
    ): Json {
        if (!text.startsWith(literal, index)) throw Json.JsonException("期望 $literal @ $index")
        index += literal.length
        return value
    }

    private fun skipWhitespace() {
        while (index < text.length && text[index].isWhitespace()) index++
    }

    private fun peek(): Char = if (index < text.length) text[index] else throw Json.JsonException("意外的文档结尾")

    private fun expect(c: Char) {
        if (peek() != c) throw Json.JsonException("期望 '$c' 实际 '${peek()}' @ $index")
        index++
    }
}
