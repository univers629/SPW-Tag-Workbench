// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package io.github.univers629.spwtagworkbench.backend.host

import com.xuncorp.spw.workshop.api.WorkshopApi
import io.github.univers629.spwtagworkbench.core.Diag
import io.github.univers629.spwtagworkbench.core.PluginEnv
import java.nio.file.Path

/**
 * 混淆点位的解析、校验与有限自适应发现。
 *
 * ## 版本来源只有一个
 *
 * 三个解析入口都读 [PluginEnv.hostVersion]（宿主启动时注入），不再由调用方传版本号。
 * 参数化会产生第二个版本源，出现「按 A 版本选表、按 B 版本校验」的错配，
 * 且调用方需要各自记住传哪个版本。
 *
 * ## 开销只在未知版本上
 *
 * 已知版本（[HostHookPoints.isTested]）直取静态表，**不建索引**，启动开销与适配前一致。
 * 只有版本未知（宿主升级、或实测范围外的版本）才建一次字节码索引（约 1.4 秒），
 * 用于校验与发现。
 *
 * ## 自适应发现的能力边界（实测）
 *
 * | 点位 | 能否结构发现 | 依据 |
 * | --- | --- | --- |
 * | 层级 B `navCaller` | 能 | 「唯一 invoke `AppNavigationKt.AppNavigation` 的外部类」在 1.19.0～1.19.3 唯一命中 |
 * | 层级 A 三点 | 不能 | 判据仍留 2 个候选，无原则性区分；只走静态表 + 校验 |
 *
 * 层级 A 校验不过即返回 null，由调用方退回层级 B —— 这正是它原有的降级路径。
 */
object HostPointResolver {
    private const val NAV_CLASS_INTERNAL = "com/xuncorp/voxzen/ui/navigation/AppNavigationKt"
    private const val NAV_METHOD = "AppNavigation"

    /** `AppNavigationKt.AppNavigation` 的描述符：无业务参数的 Composable。 */
    private const val NAV_DESCRIPTOR = "(Landroidx/compose/runtime/Composer;I)V"

    private val NAV_TARGET =
        TitlebarDiscovery.HostMethod(NAV_CLASS_INTERNAL, NAV_METHOD, NAV_DESCRIPTOR)

    /** `PiscesMediaItem` 的内部名；类名本身未被混淆。 */
    private const val MEDIA_ITEM_CLASS_INTERNAL = "com/xuncorp/pisces/PiscesMediaItem"

    /** id getter 的描述符：无参、返回 String。 */
    private const val GETTER_DESCRIPTOR = "()Ljava/lang/String;"

    /**
     * 层级 A 类的**角色锚点**：必须**全部**引用。
     *
     * 依据点位清单 §1.3：层级 A 类（MainSurface 实现）同时引用
     * `AppNavigationKt`、`Navigator`、`PlayerSheetState`。
     * 这三个类名未被混淆，故是跨版本稳定的语义判据。
     *
     * 用途见 [verifyTopLevel]：只用「唯一调用点」判据会假阳性。
     */
    private val TOP_LEVEL_ANCHORS =
        listOf(
            "com/xuncorp/voxzen/ui/navigation/AppNavigationKt",
            "com/xuncorp/voxzen/ui/navigation/Navigator",
            "com/xuncorp/voxzen/ui/component/PlayerSheetState",
        )

    /** 索引缓存：一次进程内只建立一次。 */
    @Volatile
    private var cachedIndex: TitlebarDiscovery.HostBytecodeIndex? = null

    @Volatile
    private var indexFailed = false

    /** 清空索引缓存；插件停用时调用，避免热重载后复用旧宿主的索引。 */
    @Synchronized
    fun clear() {
        cachedIndex = null
        indexFailed = false
    }

    // ── 解析入口 ────────────────────────────────────────────────────────────

    /**
     * 层级 B `inClass`（应用页层的插入点）。
     *
     * - 已知版本：直取静态表，不建索引。
     * - 未知版本：校验静态值，失效则结构发现；两者都不可用时沿用静态值
     *   （可为 null），由 Hook 框架报错并走原有降级。
     *
     * 返回值不做非空保证：调用方需要「尽可能试一次」而不是「拿不到就整个挂载失败」。
     */
    fun resolveNavCaller(): String? {
        val version = PluginEnv.hostVersion
        val points = HostHookPoints.forVersion(version)
        val static = points.navCaller
        var value = static
        var source = "static"

        if (!HostHookPoints.isTested(version)) {
            val idx = index()
            val reason =
                idx?.let {
                    static?.let { v -> verifyUniqueCallSite(it, v.replace('.', '/'), NAV_TARGET) }
                }
            when {
                idx == null ->
                    Diag.log("host-points: 索引不可用，navCaller 沿用静态值 ${static ?: "(无)"}")

                reason == null && static != null -> source = "verified"

                else -> {
                    if (reason != null) {
                        Diag.log("host-points: 静态 navCaller 校验失败（$reason），改用结构发现")
                    }
                    val found = discoverNavCaller(idx)
                    if (found != null) {
                        value = found
                        source = "discovered"
                    }
                }
            }
        }

        Diag.log(
            "host-points: navCaller=${value ?: "(无)"} source=$source " +
                "version=${HostVersion.label(version)} points=${HostHookPoints.labelOf(points)}",
        )
        return value
    }

    /**
     * 层级 A 三点 `(className, methodName, inClass)`。
     *
     * 未知版本先校验（角色 + 调用点），不过即返回 null，由调用方退回层级 B。
     * 索引不可用时直接放行：宁可让 Hook 框架最终报错，也不要因误报失效而丢掉层级 A。
     */
    fun resolveTopLevel(): Triple<String, String, String>? {
        val version = PluginEnv.hostVersion
        val triple = HostHookPoints.forVersion(version).topLevel() ?: return null
        if (HostHookPoints.isTested(version)) return triple

        val idx = index() ?: return triple
        val reason = verifyTopLevel(idx, triple)
        if (reason == null) return triple

        Diag.log("host-points: 层级 A 校验失败（$reason），改用层级 B")
        return null
    }

    /**
     * `PiscesMediaItem` 的 id getter 名。
     *
     * 未知版本校验形状，不符即返回 null（调用方跳过该只读探测）。
     */
    fun resolveMediaItemIdGetter(): String? {
        val version = PluginEnv.hostVersion
        val getter = HostHookPoints.forVersion(version).mediaItemIdGetter ?: return null
        if (HostHookPoints.isTested(version)) return getter

        val idx = index() ?: return getter
        val reason = verifyMediaItemIdGetter(idx, getter)
        if (reason == null) return getter

        Diag.log("host-points: id getter 校验失败（$reason），跳过队列 id 探测")
        return null
    }

    // ── 索引 ────────────────────────────────────────────────────────────────

    /** 宿主 jar 路径：从宿主自身的 codeSource 取。 */
    private fun hostSource(): Path {
        val host = WorkshopApi.instance.javaClass
        val codeSource =
            checkNotNull(host.protectionDomain.codeSource) { "host code source unavailable" }
        return Path.of(codeSource.location.toURI())
    }

    /**
     * 取（或建立）字节码索引；**阻塞约 1.4 秒**。
     * 建立失败只记一次日志，不反复重试。
     */
    @Synchronized
    private fun index(): TitlebarDiscovery.HostBytecodeIndex? {
        cachedIndex?.let { return it }
        if (indexFailed) return null
        return runCatching {
            TitlebarDiscovery.read(hostSource())
        }.onFailure { t ->
            indexFailed = true
            Diag.log(
                "host-points: 字节码索引建立失败 ${t.javaClass.simpleName}: ${t.message.orEmpty().take(120)}",
            )
        }.getOrNull()
            ?.also { cachedIndex = it }
    }

    // ── 判据（纯函数，索引由调用方注入，便于单元测试） ──────────────────────

    /**
     * 校验 `inClass` 内对目标方法的调用点是否恰好 1 处。
     *
     * @return `null` 表示通过，否则返回原因串。返回原因而非布尔：
     * `0 处`（点位失效）与 `N 处`（ambiguous）的处理不同，日志必须能区分。
     */
    fun verifyUniqueCallSite(
        index: TitlebarDiscovery.HostBytecodeIndex,
        ownerInternal: String,
        target: TitlebarDiscovery.HostMethod,
    ): String? =
        when (val count = index.callCount(ownerInternal, target)) {
            1 -> null
            0 -> "no call site (owner=$ownerInternal target=${target.owner}.${target.name})"
            else ->
                "$count call sites, ambiguous (owner=$ownerInternal target=${target.owner}.${target.name})"
        }

    /**
     * 结构发现层级 B 的 `inClass`：宿主 jar 里**唯一** invoke 了
     * `AppNavigationKt.AppNavigation` 且**不是自调用**的那个类。
     *
     * 自调用必须排除：`AppNavigationKt` 自身的 Composable 会 invoke 自己，
     * 那不是 Hook 框架眼里的调用点。
     *
     * @return JVM 名（点分隔）；候选数不为 1 时返回 null 并记日志。
     */
    fun discoverNavCaller(index: TitlebarDiscovery.HostBytecodeIndex): String? {
        val candidates =
            index.methods
                .filter { body ->
                    body.method.owner != NAV_CLASS_INTERNAL &&
                        body.calls.any { it.owner == NAV_CLASS_INTERNAL && it.name == NAV_METHOD }
                }.map { it.method.owner }
                .distinct()

        if (candidates.size != 1) {
            Diag.log(
                "host-points: nav callers 发现失败，候选 ${candidates.size} 个：${candidates.take(5)}",
            )
            return null
        }
        return candidates.single().replace('/', '.')
    }

    /**
     * 校验层级 A 三点，两道判据**缺一不可**：
     *
     * 1. **角色**：`className` 必须引用 [TOP_LEVEL_ANCHORS] 的全部锚点。
     * 2. **调用点**：`topLevelCaller` 对 `className.methodName` 的调用点恰好 1 处。
     *
     * 判据 1 不可省：2026-10-29 实测，1.19.2 的三点（`К`/`Ϳ`/`ѐ`）在 1.19.3 上
     * **通过了判据 2**（`ѐ` 确实对 `К.Ϳ` 唯一调用一次），但 `К` 在 1.19.3
     * 已不再引用 `Navigator`，角色已变。原因是混淆名会在版本间**复用**
     * （同一字母段分配给不同的类），于是「某类被某类唯一调用一次」这种局部关系
     * 极易偶然成立；锚点引用是跨类的语义关系，不会偶然成立。
     *
     * @return `null` 表示通过，否则返回原因串。
     */
    fun verifyTopLevel(
        index: TitlebarDiscovery.HostBytecodeIndex,
        expected: Triple<String, String, String>,
    ): String? {
        val (className, methodName, inClass) = expected
        val ownerInternal = className.replace('.', '/')

        // 判据 1：角色
        val referenced =
            index.methods
                .filter { it.method.owner == ownerInternal }
                .flatMap { it.calls }
                .map { it.owner }
                .toSet()
        val missingAnchors = TOP_LEVEL_ANCHORS.filter { it !in referenced }
        if (missingAnchors.isNotEmpty()) {
            return "role mismatch: $className does not reference $missingAnchors"
        }

        // 判据 2：调用点
        val methods =
            index.methods
                .filter { it.method.owner == ownerInternal && it.method.name == methodName }
                .map { it.method }
        if (methods.isEmpty()) {
            return "method '$methodName' not found on $className"
        }
        val total = methods.sumOf { index.callCount(inClass.replace('.', '/'), it) }
        return when (total) {
            1 -> null
            0 -> "no call site (inClass=$inClass -> $className.$methodName)"
            else -> "$total call sites, ambiguous (inClass=$inClass -> $className.$methodName)"
        }
    }

    /**
     * 校验 `PiscesMediaItem` 的 id getter 形状：无参、返回 String。
     *
     * 该点位需要 [TitlebarDiscovery] 的包白名单把它纳入索引
     * （`PiscesMediaItem` 是纯数据类，不引用 Compose，会被 Compose 粗筛跳过）。
     *
     * @return `null` 表示通过，否则返回原因串。
     */
    fun verifyMediaItemIdGetter(
        index: TitlebarDiscovery.HostBytecodeIndex,
        getter: String,
    ): String? {
        val method =
            index.methods
                .map { it.method }
                .firstOrNull { it.owner == MEDIA_ITEM_CLASS_INTERNAL && it.name == getter }
                ?: return "getter '$getter' not found on $MEDIA_ITEM_CLASS_INTERNAL"
        return if (method.descriptor == GETTER_DESCRIPTOR) {
            null
        } else {
            "getter '$getter' has descriptor ${method.descriptor}, expected $GETTER_DESCRIPTOR"
        }
    }
}
