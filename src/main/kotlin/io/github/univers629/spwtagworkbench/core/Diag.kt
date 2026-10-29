// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.core

import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 诊断日志。
 *
 * 运行时没有调试通道，只能靠文件：日志写到插件数据目录的 diag.log，
 * 用于判断 Hook 是否注册、材质是否可用、动画锚点是否拿到。
 * 只记关键节点，不刷屏。
 */
object Diag {
    private const val MAX_BYTES = 256 * 1024L
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    private val file: File? by lazy {
        PluginEnv.dataDir?.resolve("diag.log")
    }

    val path: String get() = file?.absolutePath ?: "<unavailable>"

    fun session(header: String) {
        val f = file ?: return
        runCatching {
            if (f.exists() && f.length() > MAX_BYTES) f.writeText("")
            f.parentFile?.mkdirs()
            f.appendText("\n===== $header @ ${LocalTime.now().format(timeFmt)} =====\n")
        }
        // 新会话重置转场记忆：同一进程内重新启用插件时，首个值要能记下来
        resetOnChange()
        synchronized(once) { once.clear() }
    }

    fun log(message: String) {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            f.appendText("${LocalTime.now().format(timeFmt)}  $message\n")
        }
    }

    /** 只记一次的事件，避免每帧刷日志。 */
    private val once = mutableSetOf<String>()

    fun logOnce(
        key: String,
        message: String,
    ) {
        if (once.add(key)) log(message)
    }

    /** 上次记录过的值，供 [logOnChange] 比对。 */
    private val lastValue = mutableMapOf<String, Any?>()

    /**
     * 值**发生变化**时才记一行。首次调用即记录。
     *
     * 用于状态转场：这类事件按帧求值，直接 log 会刷屏，而 [logOnce] 又只能看首个值，
     * 转场（如「不透明 → 透明」）反而看不到。
     */
    fun logOnChange(
        key: String,
        value: Any?,
        message: (Any?) -> String,
    ) {
        synchronized(lastValue) {
            val previous = lastValue[key]
            if (previous == value && lastValue.containsKey(key)) return
            lastValue[key] = value
        }
        log(message(value))
    }

    /** 清掉 [logOnChange] 的记忆，使下次调用必定记录。 */
    private fun resetOnChange() {
        synchronized(lastValue) { lastValue.clear() }
    }

    /**
     * 读回最近 [maxLines] 行日志，供插件内的「日志」面板显示。
     *
     * 只取尾部：日志可能已经几 MB，全量读进来会把界面拖住。
     * 读失败返回空列表，不抛异常——日志面板本身不该因为日志出问题而崩。
     */
    fun tail(maxLines: Int = 800): List<String> {
        val f = file ?: return emptyList()
        if (!f.isFile) return emptyList()
        return runCatching {
            // 读最后 128KB 已足够覆盖 maxLines 行，避免整份文件进内存
            val length = f.length()
            val maxBytes = 128 * 1024L
            val start = (length - maxBytes).coerceAtLeast(0L)
            java.io.RandomAccessFile(f, "r").use { raf ->
                raf.seek(start)
                val buf = ByteArray((length - start).toInt())
                raf.readFully(buf)
                String(buf, Charsets.UTF_8)
                    .lineSequence()
                    .filter { it.isNotBlank() }
                    .toList()
                    .takeLast(maxLines)
            }
        }.getOrDefault(emptyList())
    }

    /** 清空日志文件（面板上的「清空」按钮）。 */
    fun clear() {
        val f = file ?: return
        runCatching {
            f.writeText("")
            synchronized(once) { once.clear() }
            resetOnChange()
        }
    }
}
