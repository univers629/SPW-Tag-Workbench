// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.host

/**
 * 宿主版本串的解析。
 *
 * 宿主通过 `PluginContext.spwVersion` 给出自身版本
 * （实测 1.19.2 / 1.19.3，与 `app/.jpackage.xml` 的 `<app-version>` 一致）。
 *
 * 本对象只做纯字符串处理，**不依赖任何宿主类** ——
 * 这样即使宿主类加载失败，版本判断依然可用。
 */
object HostVersion {
    /**
     * 解析成整数段列表；无法解析时返回**空列表**（而不是补零）。
     *
     * 丢弃 `-` 与 `+` 之后的部分（SemVer 的预发布 / 构建元数据）：
     * `spwVersion` 是否带渠道后缀尚未实测，两种写法都要能吃下。
     *
     * 任一段不是整数即整体判为不可解析 —— 避免 `1.19.x` 被静默当成 `1.19.0`，
     * 从而错误命中点位表。
     */
    fun parse(raw: String?): List<Int> {
        val head =
            raw
                ?.trim()
                .orEmpty()
                .substringBefore('-')
                .substringBefore('+')
        if (head.isEmpty()) return emptyList()

        val parts = head.split('.')
        val numbers = ArrayList<Int>(parts.size)
        for (part in parts) {
            val value = part.trim().toIntOrNull() ?: return emptyList()
            if (value < 0) return emptyList()
            numbers += value
        }
        return numbers
    }

    /** 归一成 `major.minor` 用于日志；不可解析时给出可辨认的占位。 */
    fun label(raw: String?): String {
        val v = parse(raw)
        if (v.size >= 2) return "${v[0]}.${v[1]}"
        return raw?.trim().orEmpty().ifEmpty { "unknown" }
    }
}
