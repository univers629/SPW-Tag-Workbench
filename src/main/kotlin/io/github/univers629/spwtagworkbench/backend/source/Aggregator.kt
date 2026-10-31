// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.source

import io.github.univers629.spwtagworkbench.backend.match.Matching
import io.github.univers629.spwtagworkbench.backend.model.TagField
import io.github.univers629.spwtagworkbench.backend.model.TagPatch
import io.github.univers629.spwtagworkbench.core.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 排序后的候选：带匹配等级、合并了哪些源、以及每个源的原始 ID。 */
data class RankedCandidate(
    val candidate: SourceCandidate,
    val level: Int,
    /** 同一首歌被多个源命中时，这里列出全部源 */
    val allSources: List<String>,
    /** 源 → 源内 ID（封面可能从 A 取、歌词从 B 取） */
    val idsBySource: Map<String, String>,
) {
    val needsConfirm: Boolean get() = level >= 3
}

/** 聚合结果：候选 + 失败源 + 进度统计。 */
data class AggregateResult(
    val candidates: List<RankedCandidate> = emptyList(),
    val failures: List<Pair<String, String>> = emptyList(),
    val responded: Int = 0,
    val queried: Int = 0,
) {
    val hasFailure: Boolean get() = failures.isNotEmpty()
}

/**
 * 多源聚合。
 *
 * 顺序固定：按能力筛选 → 并发请求（单源超时不等待）→ 计算匹配等级 →
 * 去重（归一化标题相同 + 艺术家相似 + 时长差 < 3s）→ 排序（等级 > 源权重 > 顺序）→ 按字段合并。
 */
class Aggregator(
    private val sources: List<MusicSource>,
) {
    /**
     * 可用源：同时满足两个条件
     * 1. 声明了目标能力（能力声明必须被尊重）
     * 2. 没有被用户在「标签源」里关掉
     *
     * 第 2 条以前漏了——`isSourceEnabled` 只在界面里被读，backend 从没消费过，
     * 于是关掉源只改了勾选状态、搜索照打。
     */
    private fun usableSources(capability: Capability): List<MusicSource> {
        // 源开关变过一次就清空检索缓存：否则重新启用的源会拿到停用前的旧结果
        if (io.github.univers629.spwtagworkbench.core.Settings
                .consumeSourceToggle()
        ) {
            invalidateSearchCache()
        }
        return sources.filter {
            it.supports(capability) &&
                io.github.univers629.spwtagworkbench.core.Settings
                    .isSourceEnabled(it.id)
        }
    }

    /** 检索结果复用时长：同一曲目在封面 / 歌词 / 艺术家 / 批量匹配之间共用一次检索。 */
    private val searchCacheTtlMs = 5 * 60 * 1000L

    /** 空结果的复用时长：短一些，源临时返回空时不至于十分钟拿不到候选。 */
    private val emptyCacheTtlMs = 30 * 1000L

    /** 缓存条目上限：超出后淘汰写入时间最早的一条，避免长时间浏览持续累积。 */
    private val SEARCH_CACHE_MAX_ENTRIES = 300

    private class CachedSearch(
        val at: Long,
        val result: SourceResult,
    )

    /** 键 = 源 ID + 关键词；值 = 已完成的检索结果。 */
    private val searchCache = java.util.concurrent.ConcurrentHashMap<String, CachedSearch>()

    /** 同一源同一关键词的并发请求合并成一个：后来的等前一个的结果。 */
    private val searchInflight =
        java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<SourceResult>>()

    /** 缓存命中次数，供日志确认复用效果。 */
    private val searchHits = java.util.concurrent.atomic
        .AtomicInteger()

    fun searchCacheHits(): Int = searchHits.get()

    /** 写入缓存，超出上限时淘汰最旧的一条。 */
    private fun cachePut(
        key: String,
        result: SourceResult,
    ) {
        searchCache[key] = CachedSearch(System.currentTimeMillis(), result)
        if (searchCache.size <= SEARCH_CACHE_MAX_ENTRIES) return
        val oldest = searchCache.entries.minByOrNull { it.value.at } ?: return
        searchCache.remove(oldest.key)
        Diag.log("cache evict: 条目超过 $SEARCH_CACHE_MAX_ENTRIES，淘汰最旧一条")
    }

    /** 当前缓存条目数（诊断与停用日志用）。 */
    fun searchCacheSize(): Int = searchCache.size

    /**
     * 检索缓存键。
     *
     * **必须覆盖全部影响结果的输入**。只拼 `keyword` 不够：带专辑时
     * `keyword` 是「专辑 + 艺人」、不含曲名（见 [SearchQuery.keyword]），
     * 同一张专辑的每首歌都会落到同一个键上。
     *
     * 撞键的后果不是「少发一次请求」，而是**串歌**：命中后拿到的是上一首的候选，
     * 调用方却按当前曲目打分（[CandidateScoring.level]）甚至直接取用 ——
     * 逐字歌词会显示成上一首的，回放增益会被写进错误的文件。
     */
    private fun cacheKey(
        sourceId: String,
        query: SearchQuery,
    ): String =
        buildString {
            append(sourceId).append('|')
            append(query.keyword).append('|')
            append(query.title).append('|')
            append(query.artist).append('|')
            append(query.album).append('|')
            append(query.durationMs)
        }

    /**
     * 只读缓存：命中且未过期则返回已有结果，不发起任何请求。
     *
     * 与 [cachedSearch] 用同一个键函数，并复用同一套 TTL 判定 ——
     * 否则只读路径会把已过期的条目当成新鲜结果用。
     */
    fun cachedResult(
        sourceId: String,
        query: SearchQuery,
    ): SourceResult? {
        val key = cacheKey(sourceId, query)
        val entry = searchCache[key] ?: return null
        val ttl = if (entry.result.candidates.isEmpty()) emptyCacheTtlMs else searchCacheTtlMs
        if (System.currentTimeMillis() - entry.at > ttl) {
            searchCache.remove(key, entry)
            return null
        }
        return entry.result
    }

    /** 清空检索缓存：源开关变化、插件停用时调用。 */
    fun invalidateSearchCache() {
        searchCache.clear()
    }

    private fun searchKey(
        source: MusicSource,
        query: SearchQuery,
    ): String = cacheKey(source.id, query)

    /**
     * 带缓存与单飞的单源检索。
     *
     * 四个界面（批量匹配、歌词页、封面页、艺术家页）对同一首歌发的是同一个关键词，
     * 因此只有第一次真正请求源，其余命中缓存；并发请求合并等待同一个结果。
     * 失败结果不缓存——否则一次超时或限流会被固化十分钟。
     */
    private suspend fun cachedSearch(
        source: MusicSource,
        query: SearchQuery,
        timeoutMs: Long,
    ): SourceResult {
        val key = searchKey(source, query)
        val now = System.currentTimeMillis()
        searchCache[key]?.let { entry ->
            val ttl = if (entry.result.candidates.isEmpty()) emptyCacheTtlMs else searchCacheTtlMs
            if (now - entry.at <= ttl) {
                Diag.log("cache hit: ${source.label}「${query.keyword.take(24)}」")
                searchHits.incrementAndGet()
                return entry.result
            }
            searchCache.remove(key, entry)
        }
        while (true) {
            val inflight = searchInflight[key]
            if (inflight != null) {
                Diag.log("cache wait: ${source.label}「${query.keyword.take(24)}」")
                // 等待者也要有自己的超时，否则 owner 卡住时它会一直挂着。
                //
                // 与音源那层不同，这里**能**真正生效：`await()` 是可挂起点，
                // `withTimeoutOrNull` 会在超时时取消等待（阻塞 IO 那层则拦不住）。
                //
                // 预算比 owner 多 [WAITER_TIMEOUT_MARGIN_MS]：owner 自己的
                // `withTimeoutOrNull(timeoutMs)` 到点后会返回失败结果并完成
                // deferred，等待者应当有机会拿到那份结果（而非抢先判超时）。
                // 这里**不**移除 inflight 条目：它归 owner 所有，由 owner 的
                // finally 清理；等待者提前退出不应影响后续调用方继续等同一结果。
                return try {
                    withTimeoutOrNull(timeoutMs + WAITER_TIMEOUT_MARGIN_MS) { inflight.await() }
                        ?: SourceResult(source.id, source.label, failure = "等待合并请求超时")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    SourceResult(source.id, source.label, failure = t.javaClass.simpleName)
                }
            }
            val deferred = kotlinx.coroutines.CompletableDeferred<SourceResult>()
            if (searchInflight.putIfAbsent(key, deferred) != null) continue
            try {
                // 超时只对**可挂起**的代码有效：音源实现走 HttpURLConnection 的阻塞 IO，
                // 没有挂起点，超时不会中断它（实测：outer=300ms 包住阻塞 900ms，实际 900ms 后正常返回）。
                // 因此单次尝试的真实上限由 Http 自己的 connect/read 超时决定，
                // 这里只是「超时后不再等待结果」的语义，不是硬中断。
                val result =
                    withTimeoutOrNull(timeoutMs) {
                        try {
                            source.search(query)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            SourceResult(source.id, source.label, failure = t.javaClass.simpleName)
                        }
                    } ?: SourceResult(source.id, source.label, failure = "请求超时")
                if (result.ok) {
                    cachePut(key, result)
                }
                deferred.complete(result)
                return result
            } catch (e: kotlinx.coroutines.CancellationException) {
                deferred.completeExceptionally(e)
                throw e
            } catch (t: Throwable) {
                deferred.completeExceptionally(t)
                throw t
            } finally {
                searchInflight.remove(key)
            }
        }
    }

    /**
     * 搜索。
     *
     * @param capability 只请求声明了该能力的源（能力声明必须被尊重）
     * @param perSourceTimeoutMs 单源超时；超时只影响该源，不阻塞整体。
     *   默认 [SOURCE_TIMEOUT_MS]；传入值同样必须大于
     *   [io.github.univers629.spwtagworkbench.backend.net.Http.SOURCE_BUDGET_MS]，
     *   否则内层重试会被外层提前取消。
     */
    suspend fun search(
        query: SearchQuery,
        capability: Capability,
        perSourceTimeoutMs: Long = SOURCE_TIMEOUT_MS,
    ): AggregateResult {
        val usable = usableSources(capability)
        if (usable.isEmpty()) {
            return AggregateResult(queried = 0)
        }

        // 已有「足够好」的候选时不再发起剩余请求。
        //
        // 注意收益的**真实边界**：所有 `async` 是一次性创建的，协程调度顺序不确定，
        // 所以这里只能省掉「还没被调度到」的那些源的请求，省不掉已经在飞的请求。
        // 真正的价值在于：`awaitAll` 仍会等所有协程结束，但被跳过的那几个是立即返回的，
        // 因此不会为「已经确定不需要」的源多等一个 9 秒超时。
        //
        // 不做「取消在飞请求」的原因：取消会让那个源记为失败，
        // 在批量日志里制造假失败，反而更难排查。让它自然完成、结果照常参与 rank 更稳。
        val excellent =
            java.util.concurrent.atomic
                .AtomicBoolean(false)

        val results: List<SourceResult> =
            coroutineScope {
                usable
                    .map { source ->
                        async {
                            // 已经有好结果就不再发起新请求（能省掉的请求才是真省）
                            if (excellent.get()) {
                                return@async SourceResult(source.id, source.label, failure = "")
                            }
                            val result = cachedSearch(source, query, perSourceTimeoutMs)
                            // 拿到结果立刻判定：够好就置位，让尚未开始的源直接跳过
                            if (hasExcellent(query, result)) excellent.set(true)
                            result
                        }
                    }.awaitAll()
            }

        val failures = results.filter { !it.ok }.map { it.sourceLabel to it.failure }
        failures.forEach { (label, reason) -> Diag.log("aggregate fail: $label -> $reason") }
        val responded = results.count { it.ok }
        val all = results.flatMap { it.candidates }
        val ranked = rank(query, all, weightOf = { id -> sources.firstOrNull { it.id == id }?.weight ?: 0 })
        Diag.log(
            "aggregate: cap=$capability queried=${usable.size} responded=$responded " +
                "candidates=${all.size} ranked=${ranked.size} failures=${failures.size}" +
                if (excellent.get()) "（已优秀，提前收工）" else "",
        )
        // 把全局并发快照记下来：排查限流时能直接看到实际峰值有没有超过 8
        io.github.univers629.spwtagworkbench.backend.net.NetGate
            .logSnapshot("aggregate:")
        return AggregateResult(
            candidates = ranked,
            failures = failures,
            responded = responded,
            queried = usable.size,
        )
    }

    /**
     * 某个源的返回里是否已有「优秀到不必再看其它源」的候选。
     *
     * 判据与 `MatchRules.isExcellent` 一致：等级 1/2（标题确定）+ 时长差 ≤2.5s。
     * 这里内联一份是为了不让 backend 反向依赖 data 层。
     */
    private fun hasExcellent(
        query: SearchQuery,
        result: SourceResult,
    ): Boolean {
        if (!result.ok) return false
        for (candidate in result.candidates) {
            val level = CandidateScoring.level(query, candidate)
            if (level == 0 || level > 2) continue
            if (query.durationMs <= 0L || candidate.durationMs <= 0L) return true
            if (kotlin.math.abs(query.durationMs - candidate.durationMs) <= 2_500L) return true
        }
        return false
    }

    /**
     * 按源分组搜索：每个源单独一组，组内按匹配等级、时长接近度排序。
     *
     * 与 [search] 的区别：不做跨源去重与合并，也不丢弃弱匹配——
     * 歌词匹配页要的是「各个源分别罗列」，让用户自己挑。
     */
    suspend fun searchGrouped(
        query: SearchQuery,
        capability: Capability,
    ): List<SourceGroup> {
        val groups = java.util.Collections.synchronizedList(mutableListOf<SourceGroup>())
        streamGrouped(query, capability) { group ->
            groups += group
        }
        return groups.toList().sortedWith(compareByDescending<SourceGroup> { it.weight })
    }

    /**
     * 流式版按源搜索：**每个源一返回就回调一次**，不等其它源。
     *
     * 这是排版图页 / 歌词页「先出的先看」的关键：旧版 [searchGrouped] 用 `awaitAll()`，
     * 用户要等最慢那个源才能看到任何候选；这里谁先回谁先上屏。
     *
     * @param onGroup 每个源完成时回调，可能在任意线程调用，调用方需自行切主线程
     */
    suspend fun streamGrouped(
        query: SearchQuery,
        capability: Capability,
        onGroup: suspend (SourceGroup) -> Unit,
    ) {
        val usable = usableSources(capability)
        Diag.log("grouped-stream: cap=$capability sources=${usable.joinToString(",") { it.label }}")
        if (usable.isEmpty()) return

        coroutineScope {
            usable.forEach { source ->
                launch(Dispatchers.IO) {
                    val outcome = cachedSearch(source, query, SOURCE_TIMEOUT_MS)
                    val items = rankGroup(query, outcome.candidates)
                    Diag.log(
                        "grouped-stream: ${source.label} -> ${items.size} 条" +
                            "（原始 ${outcome.candidates.size}）" +
                            if (outcome.failure.isNotBlank()) "（失败：${outcome.failure}）" else "",
                    )
                    onGroup(
                        SourceGroup(
                            sourceId = source.id,
                            label = source.label,
                            weight = source.weight,
                            // rankGroup 内部已按 MAX_PER_SOURCE 截断，这里不再二次截
                            items = items,
                            failure = outcome.failure.ifBlank { null },
                        ),
                    )
                }
            }
        }
    }

    /** 参与**专辑封面**检索的源（不含只有艺术家图的源）。 */
    fun coverSources(): List<MusicSource> = usableSources(Capability.COVER)

    /**
     * 按源 id 找源实例。
     *
     * 批量匹配拿到候选后要回它自己的源取歌词/封面，
     * 而 `sources` 是私有的，这里开一个最小入口。
     */
    fun sourceById(id: String): MusicSource? = sources.firstOrNull { it.id == id }

    /** 参与**艺术家图**检索的源。与 [coverSources] 是两套端点，不能混用。 */
    fun artistSources(): List<MusicSource> = usableSources(Capability.ARTIST_COVER)

    /** 参与歌词检索的源（界面显示「支持的源」用）。 */
    fun lyricSources(): List<MusicSource> = usableSources(Capability.LYRICAL)

    /** 参与元数据匹配的源。 */
    fun metaSources(): List<MusicSource> = usableSources(Capability.META)

    /**
     * 每源最多保留多少条候选。
     *
     * 各源接口配额已调到 30~50 条，但界面每源只列 5 条就够挑了。
     * 在这里截断可以少建对象、少排一次序，界面侧也就不必再管上限。
     */
    private fun truncate(items: List<GroupedCandidate>): List<GroupedCandidate> =
        if (items.size <= MAX_PER_SOURCE) items else items.subList(0, MAX_PER_SOURCE)

    /**
     * 单源候选的排序依据。
     *
     * 截断到 [MAX_PER_SOURCE] 是**按排序结果取前几条**，所以排序键决定了
     * 谁会活下来。三类用途的判据各不相同，不能共用一个键：歌词与元数据看曲名，
     * 艺术家图看艺人（艺人的图对他任何一首歌都一样），
     * 专辑封面看源解析出的那张专辑（同一张专辑的曲目共用封面）。
     */
    enum class GroupRank {
        /** 按标题相关性 —— 歌词、元数据用。 */
        TITLE,

        /** 艺术家兼容优先，其次才是标题相关性 —— 艺术家图页用。 */
        ARTIST,

        /**
         * 源解析出的那张专辑优先 —— 封面页在**按专辑检索**时用。
         *
         * 检索已按专辑发出，但源返回的专辑名可能被本地化，标题相关性
         * 在这类结果上基本为 0，截断会退化为随机切。先按「众数专辑」聚拢，
         * 保证保留的 5 条均属于目标专辑。
         */
        ALBUM,
    }

    /** 单源搜索（封面流式载入用）。 */
    suspend fun groupFor(
        source: MusicSource,
        query: SearchQuery,
        rank: GroupRank = GroupRank.TITLE,
    ): SourceGroup? {
        val result = cachedSearch(source, query, SOURCE_TIMEOUT_MS)
        val candidates = result?.candidates.orEmpty()
        // 众数专辑只在按专辑检索时有意义；按曲名检索的结果分散在多张专辑上
        val targetAlbum =
            if (query.album.isNotBlank() && rank == GroupRank.ALBUM) {
                CandidateScoring.dominantAlbum(candidates)
            } else {
                null
            }
        val items =
            candidates
                .map { candidate ->
                    GroupedCandidate(candidate = candidate, level = CandidateScoring.level(query, candidate))
                }.sortedWith(
                    when (rank) {
                        // level 是「越小越可信」（见 [CandidateScoring.level]），
                        // 所以这里必须**升序**：精确命中的排最前，截断才留得住它。
                        GroupRank.TITLE -> compareBy<GroupedCandidate> { it.level }

                        // 否则「正确艺人的其他歌曲」会被标题更匹配的别人挤掉：
                        // 标题同名的翻唱排在前面，截断到 5 条后真艺人的歌被挤掉。
                        GroupRank.ARTIST ->
                            compareByDescending<GroupedCandidate> {
                                if (CandidateScoring.artistCompatible(query.artist, it.candidate.artist)) 1 else 0
                            }.thenBy { it.level }

                        GroupRank.ALBUM ->
                            compareByDescending<GroupedCandidate> {
                                if (targetAlbum != null && it.candidate.album == targetAlbum) 1 else 0
                            }.thenBy { it.level }
                    },
                )
        return SourceGroup(
            sourceId = source.id,
            label = source.label,
            weight = source.weight,
            items = truncate(items),
            failure = result?.failure?.ifBlank { null },
            albumTarget = targetAlbum,
        )
    }

    companion object {
        /**
         * 外层等待预算比 [io.github.univers629.spwtagworkbench.backend.net.Http]
         * 内部预算多出的余量，单位毫秒。
         *
         * `Http.get` 自己会在 `SOURCE_BUDGET_MS` 内收口（含重试），但一次请求除了
         * 网络本身还要经过参数拼装、JSON 解析、候选构造。余量留给这些步骤，
         * 否则外层会在内层**正要**返回结果时先判超时，把一次成功当失败。
         */
        const val SOURCE_TIMEOUT_MARGIN_MS = 1_000L

        /**
         * 单源检索超时：`Http` 的预算 + 余量。
         *
         * **从 [io.github.univers629.spwtagworkbench.backend.net.Http.SOURCE_BUDGET_MS]
         * 推导，不写死**——两者是同一笔预算的两端。旧代码在这里写死 9000，
         * 内层预算一旦调大就会「外层先取消、内层还在跑」，改一处忘一处。
         *
         * 注意它**不是硬中断**：音源实现走 `HttpURLConnection` 的阻塞 IO，
         * 没有挂起点，`withTimeoutOrNull` 无法打断它（实测 `outer=300ms` 包住
         * 阻塞 900ms 时，实际耗时 900ms 并正常返回）。真正的单次耗时上限由
         * `Http` 自己保证；这里只决定「超时后不再等待结果」。
         */
        const val SOURCE_TIMEOUT_MS =
            io.github.univers629.spwtagworkbench.backend.net.Http.SOURCE_BUDGET_MS + SOURCE_TIMEOUT_MARGIN_MS

        /**
         * 单飞（single-flight）等待者比 owner 多等的余量，单位毫秒。
         *
         * owner 到点后会把失败结果写进 `deferred` 并完成它，等待者多等这一小段
         * 才能拿到那份结果；否则两边同时超时，等待者会多记一次失败。
         */
        const val WAITER_TIMEOUT_MARGIN_MS = 2_000L

        /** 每源最多保留几条候选（界面每源只列 5 条，多留只是拖慢排序）。 */
        const val MAX_PER_SOURCE = 5

        /**
         * 计算等级、去重、排序。纯逻辑，可单测。
         */
        fun rank(
            query: SearchQuery,
            candidates: List<SourceCandidate>,
            weightOf: (sourceId: String) -> Int = { 0 },
        ): List<RankedCandidate> {
            val groups = mutableListOf<MutableGroup>()

            for (candidate in candidates) {
                val level = CandidateScoring.level(query, candidate)
                if (level == 0) continue // 不匹配的直接丢弃，不占列表
                val existing = groups.firstOrNull { it.matches(candidate) }
                if (existing == null) {
                    groups +=
                        MutableGroup(
                            candidate = candidate,
                            level = level,
                            sources = mutableListOf(candidate.sourceId),
                            ids = mutableMapOf(candidate.sourceId to candidate.remoteId),
                            weight = weightOf(candidate.sourceId),
                        )
                } else {
                    existing.absorb(candidate, level, weightOf(candidate.sourceId))
                }
            }

            return groups
                .sortedWith(
                    compareBy<MutableGroup> { it.level } // 1 = 精确，排最前
                        .thenByDescending { it.weight } // 同等级看源权重
                        .thenByDescending { it.candidate.hasLyrics } // 再优先有歌词的
                        .thenByDescending { it.candidate.hasCover },
                ).map { group ->
                    RankedCandidate(
                        candidate = group.candidate,
                        level = group.level,
                        allSources = group.sources.toList(),
                        idsBySource = group.ids.toMap(),
                    )
                }
        }

        /**
         * 组内排序与截断：**只留曲名对得上的，最可信的在前**。
         *
         * 旧实现按 [CandidateScoring.level] 降序排，而 level 是「越小越可信」，
         * 于是每组最前面反而是最不像的几条：实测「晴天」的结果里精确命中（level=1）
         * 被排到第 5 位之后，被 [MAX_PER_SOURCE] 直接截掉，列表上只剩同专辑的
         * 其它曲目与翻唱。
         *
         * 过滤只按**曲名**：曲名完全对不上的是别的歌，放进歌词候选是有害的
         * （曲名对不上时选中会载入别的歌的歌词）。艺人 / 时长对不上但曲名对得上的仍然保留 ——
         * 本地时长可能是错的、源侧艺人可能是合作署名。
         *
         * **不做「同录音去重」**：同一条录音在源侧可能有多条记录，而它们的
         * 歌词粒度可能不同。实测网易云同一首歌有两条同名同艺人、时长同为
         * 258834ms 的记录，一条只有 LRC、另一条带 yrc 逐字 —— 去重会按顺序
         * 保留前者、把逐字的记录排除。元数据无法区分两者，因此全部保留。
         */
        fun rankGroup(
            query: SearchQuery,
            candidates: List<SourceCandidate>,
            limit: Int = MAX_PER_SOURCE,
        ): List<GroupedCandidate> {
            val kept =
                candidates
                    .map { GroupedCandidate(candidate = it, level = CandidateScoring.level(query, it)) }
                    .filter { Matching.titleLevel(query.title, it.candidate.title) > 0 }
                    .sortedWith(
                        compareBy<GroupedCandidate> { it.level }
                            .thenBy { kotlin.math.abs(it.candidate.durationMs - query.durationMs) }
                            .thenBy { it.candidate.title },
                    )
            return if (kept.size <= limit) kept else kept.subList(0, limit)
        }

        /**
         * 按字段合并：多源命中同一首歌时，逐字段取第一个非空值。
         * 某个源缺专辑不会把别的源给的专辑擦掉。
         */
        fun mergeFields(ranked: List<RankedCandidate>): TagPatch {
            val patch = TagPatch()
            for (field in listOf(TagField.Title, TagField.Artist, TagField.Album)) {
                if (patch.has(field)) continue
                for (item in ranked) {
                    val value =
                        when (field) {
                            TagField.Title -> item.candidate.title
                            TagField.Artist -> item.candidate.artist
                            TagField.Album -> item.candidate.album
                            else -> ""
                        }
                    if (value.isNotBlank()) {
                        patch.set(field, value)
                        break
                    }
                }
            }
            return patch
        }

        private class MutableGroup(
            var candidate: SourceCandidate,
            var level: Int,
            val sources: MutableList<String>,
            val ids: MutableMap<String, String>,
            var weight: Int,
        ) {
            fun matches(other: SourceCandidate): Boolean = CandidateScoring.sameRecording(candidate, other)

            fun absorb(
                other: SourceCandidate,
                otherLevel: Int,
                otherWeight: Int,
            ) {
                if (other.sourceId !in sources) sources += other.sourceId
                ids[other.sourceId] = other.remoteId
                // 等级取更可信的；同等级时取信息更全的
                if (otherLevel < level) {
                    level = otherLevel
                    candidate = other
                } else if (otherLevel == level && informationScore(other) > informationScore(candidate)) {
                    candidate = other
                }
                if (otherWeight > weight) weight = otherWeight
            }

            private fun informationScore(c: SourceCandidate): Int =
                (if (c.album.isNotBlank()) 2 else 0) +
                    (if (c.hasLyrics) 2 else 0) +
                    (if (c.hasCover) 1 else 0) +
                    (if (c.durationMs > 0) 1 else 0)
        }
    }
}

/**
 * 按源分组的结果（歌词匹配页用）。
 *
 * 参考项目的行为：每个源独立罗列，不跨源合并，失败也按源显示原因。
 */
data class SourceGroup(
    val sourceId: String,
    val label: String,
    val weight: Int,
    val items: List<GroupedCandidate>,
    val failure: String? = null,
    /**
     * 该源按专辑检索时解析出的目标专辑（非按专辑检索时为 null）。
     *
     * 由本对象在**排序时**一并算出并随结果带出，调用方**不得重算**：
     * 这里用的是排序前的全部候选，而调用方拿到的是截断后的 [items]，
     * 两次在**不同输入**上算众数会得到不同答案 —— 排序特意提到前面的候选
     * 会被按另一个众数过滤掉，实测单源 5 条只留 1 条。
     */
    val albumTarget: String? = null,
)

/** 一个源里的一条候选，带匹配等级。 */
data class GroupedCandidate(
    val candidate: SourceCandidate,
    val level: Int,
    val sources: List<String> = listOf(candidate.sourceId),
)

/**
 * 歌词候选的**扁平**列表：不再按源分组，五个源混在一张表里。
 *
 * 排序：匹配等级升序（越小越可信）→ 同等级内逐字优先 → 其余保持传入顺序
 * （`groups` 已按源权重排好，稳定排序会把它保留成第三层 tie-break）。
 *
 * 为什么逐字不放到绝对第一位：逐字只说明**粒度**，不代表是同一首歌。
 * 让「level 3 的逐字翻唱」盖住「level 1 的逐行原唱」，等于用相关度换粒度。
 *
 * `isWordSynced` 只应对**已确认**的候选返回 true：没取过词的一律 false，
 * 顺序只在确认到逐字时改变，不因推测而调整。
 */
fun flattenLyricCandidates(
    groups: List<SourceGroup>,
    isWordSynced: (GroupedCandidate) -> Boolean,
): List<GroupedCandidate> =
    groups
        .flatMap { it.items }
        .sortedWith(
            compareBy<GroupedCandidate> { it.level }
                .thenByDescending(isWordSynced),
        )
