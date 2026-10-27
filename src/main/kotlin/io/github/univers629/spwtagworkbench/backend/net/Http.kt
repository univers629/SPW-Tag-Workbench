// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.net

import io.github.univers629.spwtagworkbench.core.Diag
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.net.UnknownHostException

/**
 * HTTP 客户端（单例）。
 *
 * **只用 java.net.HttpURLConnection。**
 * 宿主是 jpackage 打包的裁剪运行时，实测不含 `java.net.http` 模块：
 * 此前用 JDK HttpClient 时，连异常类型 `HttpTimeoutException` 都会触发
 * NoClassDefFoundError，所有在线源立即失败。
 * HttpURLConnection 在 java.base 里，一定存在。
 *
 * 行为：连接/读取都有超时；网络类失败重试；4xx 不重试；中文参数正确编码；错误按类别区分。
 */
object Http {
    /** 请求结果。error 非空即失败。 */
    data class Response(
        val status: Int,
        val body: String,
        val error: String = "",
    ) {
        val ok: Boolean get() = error.isEmpty() && status in 200..299
    }

    /**
     * 一次 `Http.get`（含全部重试）的**总预算**，单位毫秒。
     *
     * 单次尝试的真实最坏耗时是 `connectTimeout + readTimeout`（[request] 里两者
     * 都设为同一个值），即 `2 × timeoutMs`；再加上重试次数，总最坏为
     * `2 × timeoutMs × (retries + 1)`。各音源显式传 7000 / 8000 / 9000，
     * 最坏会到 16~36 秒，远超任何合理的界面等待。
     *
     * **本常量是硬上限，由 [get] 自己执行**：每次尝试的上限取
     * `min(调用方要求, 剩余预算 / 2)`（除以 2 是给 connect 与 read 各留一半），
     * 因此总耗时被压在 [SOURCE_BUDGET_MS] 附近，不依赖调用方自觉。
     *
     * 为什么不靠外层超时兜底：音源实现走 `HttpURLConnection` 的**阻塞 IO**，
     * 没有挂起点，`Aggregator` 的 `withTimeoutOrNull` **无法中断它**
     * —— 实测 `outer=300ms` 包住阻塞 900ms 时实际耗时 900ms 且正常返回。
     * 因此内层必须自己限时，外层超时只是「不再等待结果」，不是硬中断。
     *
     * 重试策略：首次快速失败（连接被重置、DNS 抖动、瞬时 5xx）时剩余预算充足，
     * 第二次尝试会真的跑；首次已耗时到预算下限时直接放弃，不再空等。
     */
    const val SOURCE_BUDGET_MS = 7000L

    /** 剩余预算低于此值就不再重试：一次注定来不及的尝试只会多等。 */
    private const val MIN_RETRY_BUDGET_MS = 1200L

    fun get(
        url: String,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 8000,
        retries: Int = 1,
    ): Response {
        val full = buildUrl(url, query)
        val startedAt = System.nanoTime()

        fun elapsedMs(): Long = (System.nanoTime() - startedAt) / 1_000_000

        var attempt = 0
        var lastError = ""
        while (attempt <= retries) {
            attempt++
            // 每次尝试都必须落在剩余预算内。除以 2 是因为单次尝试会先 connect 再 read，
            // 两者各自计时且各自取这个上限，所以「一次尝试」最坏占用 2 倍。
            val remainingMs = SOURCE_BUDGET_MS - elapsedMs()
            val attemptMs = minOf(timeoutMs, (remainingMs / 2).coerceAtLeast(1L))
            val result = once(full, headers, attemptMs)
            if (result.ok) return result
            if (result.status in 400..499) return result
            lastError = result.error
            val leftAfter = SOURCE_BUDGET_MS - elapsedMs()
            if (attempt <= retries && leftAfter >= MIN_RETRY_BUDGET_MS) {
                Diag.log("http: 第 $attempt 次失败（${result.error.take(60)}），剩余 ${leftAfter}ms 预算，重试")
            } else {
                break
            }
        }
        return Response(status = -1, body = "", error = lastError.ifEmpty { "请求失败" })
    }

    /** 下载二进制（封面用）。失败返回 null。 */
    fun getBytes(
        url: String,
        timeoutMs: Long = 10000,
    ): ByteArray? {
        if (url.isBlank()) return null
        return NetGate.run {
            var connection: HttpURLConnection? = null
            try {
                connection = URI.create(url).toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = timeoutMs.toInt()
                connection.readTimeout = timeoutMs.toInt()
                connection.setRequestProperty("User-Agent", USER_AGENT)
                if (connection.responseCode in 200..299) {
                    readBoundedBytes(connection, url)
                } else {
                    Diag.log("http: 下载 HTTP ${connection.responseCode} $url")
                    null
                }
            } catch (t: Throwable) {
                Diag.log("http: 下载失败 ${describe(t)}")
                null
            } finally {
                try {
                    connection?.disconnect()
                } catch (_: Throwable) {
                    // 忽略
                }
            }
        }
    }

    /**
     * POST 表单。
     *
     * 网易云的 `cloudsearch/pc` 只接受 POST 表单（老的那个 GET 接口
     * 返回的 `album` 里只有 `picId` 没有 `picUrl`，取不到封面）。
     */
    fun postForm(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 10000,
    ): Response = request(url, headers, timeoutMs, body)

    /** POST JSON 文本（QQ 的 musicu.fcg 要这样发）。 */
    fun postJson(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 10000,
    ): Response = request(url, headers, timeoutMs, body)

    private fun once(
        fullUrl: String,
        headers: Map<String, String>,
        timeoutMs: Long,
    ): Response = request(fullUrl, headers, timeoutMs, null)

    private fun request(
        fullUrl: String,
        headers: Map<String, String>,
        timeoutMs: Long,
        postBody: String?,
    ): Response =
        // 所有 JSON 请求都从这里出去，闸门放这儿就能保证全局并发不超过上限
        NetGate.run {
            var connection: HttpURLConnection? = null
            try {
                connection = URI.create(fullUrl).toURL().openConnection() as HttpURLConnection
                connection.requestMethod = if (postBody == null) "GET" else "POST"
                connection.connectTimeout = timeoutMs.toInt()
                connection.readTimeout = timeoutMs.toInt()
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept", "application/json")
                headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }

                if (postBody != null) {
                    val bytes = postBody.toByteArray(Charsets.UTF_8)
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Length", bytes.size.toString())
                    connection.outputStream.use { it.write(bytes) }
                }

                val status = connection.responseCode
                if (status in 200..299) {
                    val body = readBounded(connection)
                    Response(status, body)
                } else {
                    Response(status, "", "源返回 HTTP $status")
                }
            } catch (t: Throwable) {
                Response(-1, "", describe(t))
            } finally {
                try {
                    connection?.disconnect()
                } catch (_: Throwable) {
                    // 忽略
                }
            }
        }

    /**
     * 读取响应体，**限制总字节数**。
     *
     * 不设上限时，一个异常或被劫持的响应可以让 `readText()` 一直读到内存耗尽；
     * 音源接口返回的是几百 KB 以内的 JSON，8MB 已远超正常用量。
     * 超限即截断返回（JSON 解析会失败并记为「响应格式异常」），
     * 不做无限读取。
     */
    private fun readBounded(connection: HttpURLConnection): String {
        val limit = MAX_RESPONSE_BYTES
        val out = java.io.ByteArrayOutputStream(minOf(64 * 1024, limit))
        connection.inputStream.use { input ->
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                val room = limit - total
                if (room <= 0) {
                    Diag.log("http: 响应超过 ${limit / 1024}KB，已截断")
                    break
                }
                val take = minOf(n, room)
                out.write(buf, 0, take)
                total += take
                if (take < n) {
                    Diag.log("http: 响应超过 ${limit / 1024}KB，已截断")
                    break
                }
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** 响应体上限：8MB。 */
    private const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024

    /** 封面（二进制）响应上限：16MB。图片远小于这个数量级。 */
    private const val MAX_IMAGE_BYTES = 16 * 1024 * 1024

    /**
     * 读取二进制响应体，**限制总字节数**。
     *
     * 与 [readBounded] 同一个理由：封面的 URL 来自第三方音源，长度完全由对方决定。
     * 不设上限时一个异常响应就能耗尽内存（`readBytes()` 会按声明的长度直接分配）。
     * 超限直接放弃这次下载 —— 半张图没有意义，让候选列表继续往后替补。
     */
    private fun readBoundedBytes(
        connection: HttpURLConnection,
        url: String,
    ): ByteArray? =
        connection.inputStream.use { input ->
            val out = java.io.ByteArrayOutputStream(64 * 1024)
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_IMAGE_BYTES) {
                    Diag.log("http: 响应超过 ${MAX_IMAGE_BYTES / 1024 / 1024}MB，放弃 ${url.take(96)}")
                    return@use null
                }
            }
            out.toByteArray()
        }

    /**
     * 错误分类。
     *
     * 只用 java.base 里的异常类型做判断；**不要**引用 `java.net.http.*` 的异常类，
     * 那会在缺模块的运行时里再抛 NoClassDefFoundError。
     */
    private fun describe(t: Throwable): String {
        val cause = t.cause ?: t
        return when {
            cause is SocketTimeoutException -> "请求超时"
            cause is UnknownHostException -> "域名解析失败"
            cause is java.net.ConnectException -> "网络不可达"
            cause is java.net.NoRouteToHostException -> "无路由"
            cause is java.net.SocketException -> "连接被重置（防火墙/代理？）"
            cause is javax.net.ssl.SSLException -> "TLS 握手失败"
            cause is IOException -> "IO 异常：${cause.message.orEmpty().take(50)}"
            else -> "${cause.javaClass.simpleName}: ${cause.message.orEmpty().take(50)}"
        }
    }

    private fun buildUrl(
        base: String,
        query: Map<String, String>,
    ): String {
        if (query.isEmpty()) return base
        val encoded =
            query.entries
                .filter { it.value.isNotBlank() }
                .joinToString("&") { (k, v) ->
                    "${URLEncoder.encode(k, Charsets.UTF_8)}=${URLEncoder.encode(v, Charsets.UTF_8)}"
                }
        if (encoded.isEmpty()) return base
        return if (base.contains('?')) "$base&$encoded" else "$base?$encoded"
    }

    private const val USER_AGENT = "SaltWorkshop/1.0 (SPW plugin)"
}
