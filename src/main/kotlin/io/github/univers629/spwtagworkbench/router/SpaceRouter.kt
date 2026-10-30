// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.router

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect

/**
 * 宽视图空间里的五个页面。
 * [origin] 是它在工作台桌面上对应的区域标识 —— 专题页都从某块桌面区域放大而来。
 */
enum class SpacePage(
    val label: String,
    val origin: String,
) {
    Desk("工作台", "desk-root"),
    Lyrics("歌词", "region-lyrics"),
    Cover("封面", "region-cover"),
    Artist("艺术家", "region-artist"),
    Spectrum("频谱", "region-spectrum"),
    Timeline("打轴", "region-timeline"),
    Info("信息", "region-info"),
    ;

    val isDetail: Boolean get() = this != Desk
}

/**
 * 两页共有的元素标识。切换时这些元素必须被识别为同一个东西连续移动。
 *
 * - [Cover]：专辑封面（主视图 ↔ 封面匹配页）
 * - [Title]：歌名标题（侧栏锚点 ↔ 各页顶栏）
 * - [Lyrics]：歌词编辑区（主视图右栏 ↔ 歌词匹配页右侧）
 */
enum class SharedKey { Cover, Title, Lyrics }

/**
 * 空间度量表。
 *
 * 三张表：
 * - regions：桌面上的可聚焦区域
 * - shared：各页的共享元素（封面、标题）
 * - anchors：侧栏里那块基础信息卡片 —— 进入工作台动画的锚点
 */
object SpaceMetrics {
    private val regions = mutableStateMapOf<String, Rect>()
    private val shared = mutableStateMapOf<String, Rect>()
    private val anchors = mutableStateMapOf<String, Rect>()

    private fun regionKey(
        page: SpacePage,
        region: String,
    ) = "${page.name}#$region"

    private fun sharedKey(
        page: SpacePage,
        key: SharedKey,
    ) = "${page.name}#${key.name}"

    fun putRegion(
        page: SpacePage,
        region: String,
        rect: Rect,
    ) {
        regions[regionKey(page, region)] = rect
    }

    fun region(
        page: SpacePage,
        region: String,
    ): Rect? = regions[regionKey(page, region)]

    fun putShared(
        page: SpacePage,
        key: SharedKey,
        rect: Rect,
    ) {
        shared[sharedKey(page, key)] = rect
    }

    fun shared(
        page: SpacePage,
        key: SharedKey,
    ): Rect? = shared[sharedKey(page, key)]

    /** 侧栏锚点卡片整体矩形。 */
    fun putAnchor(rect: Rect) {
        anchors["card"] = rect
    }

    /** 侧栏锚点卡片里的封面 / 标题矩形。 */
    fun putAnchorPart(
        key: SharedKey,
        rect: Rect,
    ) {
        anchors["part#${key.name}"] = rect
    }

    fun anchor(): Rect? = anchors["card"]

    /** 侧栏召唤按钮的矩形：没有卡片锚点时的退化起点。 */

    fun putFab(rect: Rect) {
        anchors["fab"] = rect
    }

    fun fab(): Rect? = anchors["fab"]

    fun anchorPart(key: SharedKey): Rect? = anchors["part#${key.name}"]
}

/**
 * 一次切换的全部参数。切换开始前测量、切换结束后清空，
 * 保证上一次的参数不会残留到下一次。
 */
data class Transit(
    val from: SpacePage,
    val to: SpacePage,
)

/**
 * 外壳状态：宽视图的展开 / 收起。
 *
 * [open] 是用户意图，[progress] 是对应的动效值：
 * 0 = 仍然是侧栏里那张卡片的大小，1 = 已经铺满内容区。
 * 宿主内容的隐身跟着 [onScreen] 走 —— 只要还在屏幕上就保持隐身，收起到 0 才交还。
 */
object ShellState {
    var open by mutableStateOf(false)

    /** 0..1。由 Shell 的动画逐帧写入。 */
    var progress by mutableStateOf(0f)

    /** 宽视图此刻是否占据内容区（含正在展开 / 正在收起）。 */
    val onScreen: Boolean get() = progress > 0.002f

    /**
     * 替换体是否真的被组合过。
     *
     * `replaceComposable` 注册成功只代表「没抛异常」，不代表替换体会被渲染。
     * 若宿主内容区已被替换而替换体又没跑，宿主界面会整体空白（设置页、导航都打不开）。
     *
     * 组合线程写入、看门狗线程读取，所以用 `@Volatile` 而不是 Compose 状态 ——
     * 它不驱动界面，只做跨线程存活信号。
     */
    @Volatile
    var replaceAlive: Boolean = false

    /**
     * 替换体进入组合的次数。
     *
     * 用来区分两种失败：宿主**没有调用**替换体（次数为 0），
     * 还是调用了但组合中途抛异常（>0 而 [replaceAlive] 仍为 false）。
     * 两种情况下看门狗的处理策略不同，日志也需要能看出来。
     */
    @Volatile
    var replaceInvocations: Int = 0

    /** 替换体组合时捕获到的第一个异常，供看门狗写进日志。 */
    @Volatile
    var replaceError: String? = null

    /**
     * 宿主内容此刻是否**真的**由替换体接管（即替换体正在组合中）。
     *
     * 仅供诊断：配色判据不看本字段。透明底以「宿主有没有窗口材质」为准，
     * 因为材质连续性是主要观感来源；退回实色会与宿主的模糊材质形成接缝。
     * 宿主内容隐藏由替换体负责（`RetainedHostContent`）：替换体运行时隐藏，
     * 不运行时宿主照常自绘。
     *
     * 与 [replaceAlive] 的区别：那个是「曾经成功过」的跨线程存活信号（`@Volatile`），
     * 本字段是「此刻仍在接管」的 Compose 状态，由 `HostPassthrough` 在进入组合时置位、
     * 离开组合时清位。两个字段一起进入诊断日志，用于区分接管窗口与永久失败。
     */
    var hostContentTakenOver by mutableStateOf(false)

    fun reset() {
        open = false
        progress = 0f
    }
}

/**
 * 宽视图的路由器。页面状态的唯一来源。
 *
 * 固定顺序：释放旧页资源 → 切换 → 收尾。各页不得自维护「是否打开」，
 * 也不得互相调用关闭方法。异常一律兜底回工作台且不触发回调。
 */
object SpaceRouter {
    /** 当前页。唯一真相来源。 */
    var page by mutableStateOf(SpacePage.Desk)
        private set

    /** 正在进行的切换；null 表示静止。 */
    var transit by mutableStateOf<Transit?>(null)
        private set

    /** 动画进度：0 = 出发页状态，1 = 目标页状态。由动效驱动写入。 */
    var progress by mutableStateOf(1f)

    /** 目标页是否已就位（动画结束）。 */
    var settled by mutableStateOf(true)
        private set

    private var listener: ((SpacePage) -> Unit)? = null

    fun setListener(block: ((SpacePage) -> Unit)?) {
        listener = block
    }

    /**
     * 统一入口。
     *
     * @param release 释放旧页资源（取消异步任务、清空临时状态），在切换前调用
     * @return 是否真的发生了切换
     */
    fun navigate(
        target: SpacePage,
        release: (() -> Unit)? = null,
    ): Boolean {
        if (target == page) return false
        return try {
            release?.invoke()
            reset()

            val from = page
            transit = Transit(from = from, to = target)
            progress = 0f
            settled = false
            page = target
            true
        } catch (_: Throwable) {
            transit = null
            progress = 1f
            settled = true
            page = SpacePage.Desk
            false
        }
    }

    /** 动画结束时调用：交还真实元素并清理本次切换参数。 */
    fun finish() {
        progress = 1f
        settled = true
        transit = null
        listener?.invoke(page)
    }

    fun reset() {
        transit = null
        progress = 1f
        settled = true
    }

    /** 收起外壳后直接回到工作台，不产生页面切换动画。 */
    fun hardReset() {
        reset()
        page = SpacePage.Desk
    }

    fun back(release: (() -> Unit)? = null) {
        if (page != SpacePage.Desk) {
            navigate(SpacePage.Desk, release)
        } else {
            closeShell()
        }
    }

    /** 展开宽视图（从侧栏锚点长大）。 */
    fun open(target: SpacePage = SpacePage.Desk) {
        if (ShellState.open) {
            if (target != page) navigate(target)
            return
        }
        page = target
        reset()
        ShellState.progress = 0f
        ShellState.open = true
    }

    /**
     * 开合宽视图：已展开则收起，未展开则展开。
     *
     * 与 [open] 的区别：`open` 在已展开时只处理换页、**不收起**（语义是「不重复打开」），
     * 因此它不能直接当开关用 —— 快捷键需要的是「开 → 关 → 开」。
     *
     * 收起走 [closeShell]，与右上角关闭按钮同一条路径：它只翻转意图，
     * 由 `Shell` 的组合动画收尾并在结束时 [hardReset] 清页面状态。
     */
    fun toggle(target: SpacePage = SpacePage.Desk) {
        if (ShellState.open) closeShell() else open(target)
    }

    /** 收起宽视图（缩回侧栏锚点）。 */
    fun closeShell() {
        ShellState.open = false
    }

    /** 宿主跳页时收起宽视图。 */
    fun hideForHostNavigation() {
        reset()
        page = SpacePage.Desk
        ShellState.reset()
    }
}
