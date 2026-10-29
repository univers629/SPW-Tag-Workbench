// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.xuncorp.voxzen.ui.navigation.AppNavigation
import io.github.univers629.spwtagworkbench.core.Card
import io.github.univers629.spwtagworkbench.core.Diag
import io.github.univers629.spwtagworkbench.core.Glyph
import io.github.univers629.spwtagworkbench.core.Host
import io.github.univers629.spwtagworkbench.core.LocalPalette
import io.github.univers629.spwtagworkbench.core.Mono
import io.github.univers629.spwtagworkbench.core.Palette
import io.github.univers629.spwtagworkbench.core.PluginEnv
import io.github.univers629.spwtagworkbench.core.Salt
import io.github.univers629.spwtagworkbench.core.Settings
import io.github.univers629.spwtagworkbench.core.T
import io.github.univers629.spwtagworkbench.core.TapChip
import io.github.univers629.spwtagworkbench.core.Txt
import io.github.univers629.spwtagworkbench.core.fabCard
import io.github.univers629.spwtagworkbench.core.fabCardRaised
import io.github.univers629.spwtagworkbench.core.i18n.L10n
import io.github.univers629.spwtagworkbench.core.saltPalette
import io.github.univers629.spwtagworkbench.data.Drafts
import io.github.univers629.spwtagworkbench.data.Repo
import io.github.univers629.spwtagworkbench.router.ShellState
import io.github.univers629.spwtagworkbench.router.SpaceMetrics
import io.github.univers629.spwtagworkbench.router.SpaceRouter
import io.github.univers629.spwtagworkbench.ui.icons.IconLog
import io.github.univers629.spwtagworkbench.ui.library.LibraryPanel
import io.github.univers629.spwtagworkbench.ui.sidebar.SidebarHost
import io.github.univers629.spwtagworkbench.ui.space.SpaceCanvas

internal val ShellExpandEase = CubicBezierEasing(0.20f, 0.80f, 0.16f, 1f)
internal val ShellCollapseEase = CubicBezierEasing(0.34f, 0f, 0.30f, 1f)
internal const val ShellExpandMs = 340
internal const val ShellCollapseMs = 280

/** 曲库内嵌列 */
private val LibraryWidth = 320.dp
private const val LibraryMs = 280

/** 右侧侧栏面板：展开与收起必须对称（同时长、同曲线）。 */
internal const val PanelMs = 300
internal val PanelEase = CubicBezierEasing(0.20f, 0.80f, 0.16f, 1f)

// ── 宿主内容：测量但不放置 ───────────────────────────────────────────────────

private data class HostPlacement(
    val hidden: Boolean,
) : LayoutModifier {
    override fun MeasureScope.measure(
        measurable: androidx.compose.ui.layout.Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val child = measurable.measure(constraints)
        return layout(child.width, child.height) {
            if (!hidden) child.placeRelative(0, 0)
        }
    }
}

@Composable
fun RetainedHostContent(
    hidden: Boolean,
    content: @Composable () -> Unit,
) {
    val focus = LocalFocusManager.current
    LaunchedEffect(hidden) { if (hidden) focus.clearFocus(force = true) }
    Box(Modifier.fillMaxSize().then(HostPlacement(hidden))) { content() }
}

// ── 宿主内容透传（replace 钩子的替换体） ─────────────────────────────────────
//
// **替换体只做一件事：把宿主内容画回去，或者按需不放置它。**
//
// 绝不在这里画插件自己的界面。原因：替换体一旦出问题，宿主内容区会整体失效 ——
// 设置页卡在加载、导航点不动都由此而来。把插件 UI 放进 append 钩子，替换体失败
// 最多是材质透不出来，宿主始终可用。

@Composable
fun HostPassthrough() {
    // 进入替换体就记一次：看门狗据此区分「宿主没调用替换体」和
    // 「调用了但组合中途抛异常」，两者的处理策略不同。
    //
    // 这里不能 try/catch 包住下面的 composable 调用（Compose 编译器不支持），
    // 所以只记次数；组合真出错时宿主会吞掉异常，看门狗看到「进过组合但没存活」
    // 就按失败处理。
    ShellState.replaceInvocations += 1

    // 声明「宿主内容已由替换体接管」——透明底的判据之一。
    // 用 DisposableEffect 而不是裸赋值：离开组合时清位，
    // 于是宿主不再调用替换体后，配色自动退回不透明，不会留下
    // 「宿主内容重新可见、插件页面仍是透明」的重叠状态。
    //
    // 起止都记日志：重叠只发生在「宿主内容可见」与「页面已透明」两者并存的窗口里，
    // 事后要靠这两行才能判断当时是否真的接管住了。
    DisposableEffect(Unit) {
        ShellState.hostContentTakenOver = true
        Diag.log("material: 宿主内容已接管（展开时隐藏，页面可透明）")
        onDispose {
            ShellState.hostContentTakenOver = false
            Diag.log("material: 宿主内容不再接管（页面转不透明，宿主恢复自绘）")
        }
    }

    RetainedHostContent(hidden = ShellState.onScreen) { AppNavigation() }
    // 存活标记放在最后：整个组合体走完才置位。
    // 中途抛异常则不置位，看门狗会关掉替换、让宿主自绘。
    SideEffect { ShellState.replaceAlive = true }
}

// ── 插件自己的界面（append 钩子） ────────────────────────────────────────────

/**
 * 按宿主当前状态取色板（深浅 + 窗口材质 + 强制不透明）。
 *
 * **必须由每个「独立组合树」自己调用**：`CompositionLocalProvider(LocalPalette …)`
 * 只覆盖调用它的子树。顶层侧栏（层级 A）挂在宿主的另一个 composition 里，
 * 拿不到 [PluginOverlay] 提供的色板，会退回 [LocalPalette] 的默认值
 * `darkSolid`（`material = false`）：材质开着但浮层全是不透明色。
 */
@Composable
internal fun rememberHostPalette(): Palette {
    val dark = remember(ShellState.open) { guardHost("dark") { Host.detectDark() } ?: true }
    val style = remember(ShellState.open) { guardHost("style") { Host.windowStyle } ?: "None" }
    // 两个 key 都必须进 remember：
    // - forceOpaque：替换失败回退时被改写
    // - hostContentTakenOver：替换体是否真的在接管（见下）
    val takenOver = ShellState.hostContentTakenOver
    return remember(dark, style, Settings.forceOpaque, takenOver) {
        guardHost("palette") {
            saltPalette(
                dark = dark,
                windowStyle = style,
                forceOpaque = Settings.forceOpaque,
                // 透明底**只在宿主内容确实被替换体接管时**成立：
                // 托管中 → 展开时宿主内容被隐藏，透明只透出 DWM 材质；
                // 未托管 → 宿主仍在自绘，透明会让两套界面叠在一起互相穿透。
                // 只看 windowStyle 不够：材质可用不等于替换体接管成功。
                hostContentTakenOver = takenOver,
            )
        } ?: saltPalette(dark = true, windowStyle = "None", forceOpaque = true)
    }
}

@Composable
fun PluginOverlay() {
    // 取宿主状态：任一步失败都不能让整块区域炸掉
    val palette = rememberHostPalette()
    // 日志面板的显示状态。用 remember 而非 ShellState：它只是浮层里的一层遮罩
    var logOpen by remember { mutableStateOf(false) }
    val onScreen = ShellState.onScreen
    // 按值变化记录，而不是 logOnce：logOnce 只看首个值，
    // 而「接管成功 → 页面由不透明转透明」这个转场正是要观察的对象。
    Diag.logOnChange(
        key = "shell-material",
        value = "material=${palette.material} takenOver=${ShellState.hostContentTakenOver} " +
            "forceOpaque=${Settings.forceOpaque}",
    ) { v -> "PluginOverlay $v" }

    CompositionLocalProvider(
        LocalPalette provides palette,
        // **全局关掉点击波纹**。
        //
        // 项目里没有任何显式 ripple，用户看到「点一下向外扩散一圈」的效果
        // 就是 clickable 默认的 Material Indication。这里统一替换成空实现，
        // 点击行为不受影响，只是不再画那一圈。
        androidx.compose.foundation.LocalIndication provides NoIndication,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(pageBackground(onScreen, palette)),
        ) {
            if (ShellState.open || onScreen) {
                WideView(onOpenLog = { logOpen = true })
            }

            SidebarHost()

            if (logOpen) {
                LogSheet(onDismiss = { logOpen = false })
            }
        }
    }
}

/** 取宿主状态失败时记日志并返回 null，让调用方用兜底值继续。 */
private inline fun <T> guardHost(
    what: String,
    block: () -> T,
): T? =
    try {
        block()
    } catch (e: Throwable) {
        Diag.log("PluginOverlay: $what 取值失败 ${e.javaClass.name}: ${e.message}")
        null
    }

/**
 * 页面底色。
 *
 * 宽视图没占屏时**必须完全透明** —— 插件界面是叠在宿主之上的浮层，
 * 铺实色会把宿主界面整块盖住（设置页、导航都点不到）。
 */
private fun pageBackground(
    onScreen: Boolean,
    palette: Palette,
): Modifier =
    if (onScreen) {
        Modifier.background(palette.page)
    } else {
        Modifier
    }

// ── 插件内设置面板状态 ───────────────────────────────────────────────────────

// ── 宽视图外壳 ───────────────────────────────────────────────────────────────

@Composable
private fun WideView(onOpenLog: () -> Unit) {
    val progress = ShellState.progress
    val anchor = remember { SpaceMetrics.anchor() ?: SpaceMetrics.fab() }
    var selfOrigin by remember { mutableStateOf(Offset.Zero) }
    var libraryOpen by remember { mutableStateOf(false) }
    var sourcesOpen by remember { mutableStateOf(false) }

    LaunchedEffect(ShellState.open) {
        if (ShellState.open) {
            // 宿主配置页改完设置不会通知插件，这里补一次重读，
            // 否则「工作台主视图显示艺术家封面」这类开关改了看不到效果。
            Settings.refresh()
            animate(
                initialValue = ShellState.progress,
                targetValue = 1f,
                animationSpec = tween(ShellExpandMs, easing = ShellExpandEase),
            ) { value, _ -> ShellState.progress = value }
            Diag.log("shell: 展开完成 progress=${ShellState.progress}")
        } else {
            animate(
                initialValue = ShellState.progress,
                targetValue = 0f,
                animationSpec = tween(ShellCollapseMs, easing = ShellCollapseEase),
            ) { value, _ -> ShellState.progress = value }
            Diag.log("shell: 收起完成 progress=${ShellState.progress}")
            SpaceRouter.hardReset()
        }
    }

    // 内容透明度自展开一开始就跟着变化，不再有 0→0.45 期间 alpha 恒定 0.35 的死区
    val damping = progress.coerceIn(0f, 1f)

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .onGloballyPositioned { selfOrigin = it.positionInWindow() }
                .drawWithContent {
                    val a = anchor
                    if (progress >= 0.999f || a == null) {
                        Diag.logOnce("clip-off", "clip OFF progress=$progress anchor=$a")
                        drawContent()
                        return@drawWithContent
                    }
                    Diag.logOnce("clip-on", "clip ON progress=$progress anchor=$a selfOrigin=$selfOrigin")
                    val local =
                        Rect(
                            left = a.left - selfOrigin.x,
                            top = a.top - selfOrigin.y,
                            right = a.right - selfOrigin.x,
                            bottom = a.bottom - selfOrigin.y,
                        )
                    clipRect(
                        left = lerp(local.left, 0f, progress),
                        top = lerp(local.top, 0f, progress),
                        right = lerp(local.right, size.width, progress),
                        bottom = lerp(local.bottom, size.height, progress),
                    ) {
                        this@drawWithContent.drawContent()
                    }
                },
    ) {
        Row(Modifier.fillMaxSize()) {
            val libraryWidth by animateDpAsState(
                targetValue = if (libraryOpen) LibraryWidth else 0.dp,
                // 与全局面板曲线一致：曲库列和侧栏、FAB 用同一条缓动
                animationSpec = tween(LibraryMs, easing = PanelEase),
                label = "libraryWidth",
            )
            // 曲库列：从内容区顶部开始，与工具栏同水平带
            Box(
                modifier =
                    Modifier
                        .width(libraryWidth)
                        .fillMaxHeight()
                        .clipToBounds(),
            ) {
                LibraryPanel(
                    onClose = { libraryOpen = false },
                    modifier =
                        Modifier
                            .width(LibraryWidth)
                            .fillMaxHeight(),
                )
            }

            // 主内容：工具栏只占主区宽度，主内容整体下移；曲库不受影响
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .graphicsLayer { alpha = 0.35f + 0.65f * damping },
            ) {
                WideToolbar(
                    libraryOpen = libraryOpen,
                    onToggleLibrary = { libraryOpen = !libraryOpen },
                    sourcesOpen = sourcesOpen,
                    onToggleSources = { sourcesOpen = !sourcesOpen },
                    onOpenLog = onOpenLog,
                    onClose = { SpaceRouter.closeShell() },
                )
                // 中间页底部留出宿主播放条高度：宿主底栏浮在内容之上，不留就被遮挡。
                // 两个侧栏（曲库列 / 右侧面板）贴满高度，不受影响。
                val reservedDp = remember { Host.miniPlayerReservedDp() }
                Diag.logOnce("mini-player-inset", "底栏预留高度=" + reservedDp + "dp")
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(bottom = reservedDp.dp),
                ) {
                    SpaceCanvas()
                }
            }
        }
    }
}

/**
 * 顶部工具栏：只占主内容区上方一条，曲库列不受影响。
 * 最右侧设置入口位置固定，不受其它按钮增减影响。
 */
@Composable
private fun WideToolbar(
    libraryOpen: Boolean,
    onToggleLibrary: () -> Unit,
    sourcesOpen: Boolean,
    onToggleSources: () -> Unit,
    onOpenLog: () -> Unit,
    onClose: () -> Unit,
) {
    val palette = Salt
    val dirty = Drafts.dirtyCount
    val checked = io.github.univers629.spwtagworkbench.data.Selection.checkedCount

    Column {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconToolButton(
                active = libraryOpen,
                onClick = onToggleLibrary,
            ) {
                io.github.univers629.spwtagworkbench.ui.icons.IconLibrary(
                    color = if (libraryOpen) palette.accent else palette.textSecondary,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.width(4.dp))
            // 「匹配」按钮已删除：匹配的入口是「匹配选中 / 匹配当前」，
            // 原来那个只搜不写、结果无处可看，属于冗余。
            Box {
                ToolButton(
                    label = L10n.t("shell.sourceGroup"),
                    trailing = "▾",
                    active = sourcesOpen,
                    onClick = onToggleSources,
                )
                io.github.univers629.spwtagworkbench.core.MenuPopup(
                    expanded = sourcesOpen,
                    onDismiss = onToggleSources,
                ) {
                    io.github.univers629.spwtagworkbench.core.MenuRow(
                        label = "iTunes · " + L10n.t("shell.capMetaCover"),
                        checked = Settings.isSourceEnabled("itunes"),
                        onClick = { Settings.setSourceEnabled("itunes", !Settings.isSourceEnabled("itunes")) },
                    )
                    io.github.univers629.spwtagworkbench.core.MenuRow(
                        label = "LRCLIB · " + L10n.t("shell.capLyricsMeta"),
                        checked = Settings.isSourceEnabled("lrclib"),
                        onClick = { Settings.setSourceEnabled("lrclib", !Settings.isSourceEnabled("lrclib")) },
                    )
                    io.github.univers629.spwtagworkbench.core.MenuRow(
                        label = "网易云 · " + L10n.t("shell.capLyricsMeta"),
                        checked = Settings.isSourceEnabled("netease"),
                        onClick = { Settings.setSourceEnabled("netease", !Settings.isSourceEnabled("netease")) },
                    )
                    io.github.univers629.spwtagworkbench.core.MenuRow(
                        label = "QQ音乐 · " + L10n.t("shell.capLyricsMeta"),
                        checked = Settings.isSourceEnabled("qqmusic"),
                        onClick = { Settings.setSourceEnabled("qqmusic", !Settings.isSourceEnabled("qqmusic")) },
                    )
                    io.github.univers629.spwtagworkbench.core.MenuRow(
                        label = "酷狗音乐 · " + L10n.t("shell.capLyricsMeta"),
                        checked = Settings.isSourceEnabled("kugou"),
                        onClick = { Settings.setSourceEnabled("kugou", !Settings.isSourceEnabled("kugou")) },
                    )
                    Spacer(Modifier.height(3.dp))
                    io.github.univers629.spwtagworkbench.core.MenuRow(
                        label = L10n.t("shell.enableAll"),
                        showCheck = false,
                        accent = Salt.accent,
                        onClick = {
                            listOf("itunes", "lrclib", "netease", "qqmusic", "kugou").forEach {
                                Settings.setSourceEnabled(it, true)
                            }
                        },
                    )
                }
            }
            ToolButton(
                label = if (checked > 0) L10n.t("shell.matchSelectedCount", checked) else L10n.t("shell.matchSelected"),
                onClick = { Repo.matchSelected() },
            )
            // 只处理当前这一首：与批量同理，直接写文件，不经过草稿预览
            ToolButton(
                label = L10n.t("shell.matchCurrent"),
                onClick = { Repo.matchCurrent() },
            )
            // 保存按钮**常驻**，不再依赖「有没有脏草稿」才出现。
            //
            // 之前是 `if (dirty > 0)` 才渲染，问题有二：
            // 1. 用户改完歌词发现顶栏没有保存入口，不知道内容去哪了；
            // 2. 为了点亮它，各处要小心翼翼地维护「脏标记」，编辑框那种逐字回调
            //    尤其容易把状态算错（曾经因为比较内容判断「外部变化」，把输入吃成单字）。
            //
            // 改成常驻后：有改动时显示数量，没改动时按钮置灰（不能点），语义一样清楚，
            // 但不再需要任何检测逻辑参与「显不显示」这个决策。
            ToolButton(
                label = if (dirty > 0) L10n.t("shell.saveCount", dirty) else L10n.t("shell.save"),
                accent = if (dirty > 0) palette.accent else null,
                onClick = { Repo.saveDrafts() },
                enabled = dirty > 0,
            )
            // 放弃全部草稿：预览制的配套出口。
            // 摘要型动作（源侧回放增益等）只进草稿不落盘，没有这个入口就无法撤销；
            // 与保存按钮同样常驻，无脏草稿时置灰。
            ToolButton(
                label = L10n.t("shell.discardAll"),
                onClick = { Repo.discardAllDrafts() },
                enabled = dirty > 0,
            )

            Spacer(Modifier.width(8.dp))
            // 专题页入口：候选与预览都在展开页里做
            // 专题页入口：矢量图标 + 圆角背景
            IconToolButton(onClick = { SpaceRouter.navigate(io.github.univers629.spwtagworkbench.router.SpacePage.Lyrics) }) {
                io.github.univers629.spwtagworkbench.ui.icons.IconLyrics(
                    color = palette.textSecondary,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            IconToolButton(onClick = { SpaceRouter.navigate(io.github.univers629.spwtagworkbench.router.SpacePage.Cover) }) {
                io.github.univers629.spwtagworkbench.ui.icons.IconCover(
                    color = palette.textSecondary,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            IconToolButton(onClick = { SpaceRouter.navigate(io.github.univers629.spwtagworkbench.router.SpacePage.Artist) }) {
                io.github.univers629.spwtagworkbench.ui.icons.IconArtist(
                    color = palette.textSecondary,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            IconToolButton(onClick = { SpaceRouter.navigate(io.github.univers629.spwtagworkbench.router.SpacePage.Spectrum) }) {
                io.github.univers629.spwtagworkbench.ui.icons.IconSpectrum(
                    color = palette.textSecondary,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            IconToolButton(onClick = { SpaceRouter.navigate(io.github.univers629.spwtagworkbench.router.SpacePage.Timeline) }) {
                io.github.univers629.spwtagworkbench.ui.icons.IconTimeline(
                    color = palette.textSecondary,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            IconToolButton(onClick = { SpaceRouter.navigate(io.github.univers629.spwtagworkbench.router.SpacePage.Info) }) {
                io.github.univers629.spwtagworkbench.ui.icons.IconInfo(
                    color = palette.textSecondary,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Spacer(Modifier.weight(1f))

            Txt(text = Repo.netLabel(), size = T.caption, color = palette.textTertiary, maxLines = 1)
            Spacer(Modifier.width(6.dp))
            // 右侧三个与左侧那排专题页入口保持同一种排布：32dp 方块、**紧邻不留 Spacer**，
            // 视觉间距由方块自身的 7dp 内边距形成。之前这里多插了两个 4dp Spacer，
            // 又用变宽的 ToolButton 做关闭，导致右侧比左侧松且不齐。
            LogButton(onClick = onOpenLog)
            SettingsButton()
            // 关闭：与侧栏、曲库用同一个 ✕ 图标
            IconToolButton(onClick = onClose) {
                Txt("✕", size = Glyph.small, color = palette.textSecondary, maxLines = 1)
            }
        }
    }
}

/** 标签源下拉：显示两个源的启用状态，点击切换。 */
@Composable
private fun SourceRow(
    label: String,
    id: String,
) {
    val palette = Salt
    val enabled = Settings.isSourceEnabled(id)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (enabled) palette.selected else Color.Transparent)
                .clickable { Settings.setSourceEnabled(id, !enabled) }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(
            text = if (enabled) "✓" else "○",
            size = Glyph.inline,
            color = if (enabled) palette.accent else palette.textTertiary,
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        Txt(label, size = T.caption, color = palette.textSecondary, maxLines = 1)
    }
}

/** 日志按钮：与齿轮并排，点开看 diag.log 尾部内容。 */
@Composable
private fun LogButton(onClick: () -> Unit) {
    val palette = Salt
    var hovered by remember { mutableStateOf(false) }
    Box(
        modifier =
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (hovered) palette.hover else Color.Transparent)
                .clickable(onClick = onClick)
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false }
                .padding(7.dp),
    ) {
        // 三条横线「≡」示意日志行；用 Glyph 档位，不与正文的字号体系混用
        IconLog(color = palette.textSecondary, modifier = Modifier.fillMaxSize())
    }
}

/**
 * 日志面板：显示 diag.log 的尾部内容。
 *
 * 用等宽字体、可滚动、可清空。刷新靠打开时读一次 + 「刷新」按钮，
 * 不做实时跟随——那需要文件监听或轮询，对排查够用了。
 */
@Composable
private fun LogSheet(onDismiss: () -> Unit) {
    val palette = Salt
    var lines by remember { mutableStateOf(Diag.tail()) }
    // 用 PanelDialog：**不铺半透明黑底（无压暗）、无光波/发光**。
    io.github.univers629.spwtagworkbench.core.PanelDialog(
        onDismiss = onDismiss,
        width = 720.dp,
        maxHeight = 560.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(L10n.t("shell.diagnostics"), size = T.heading, color = palette.text, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            Txt(
                text = L10n.t("shell.diagnosticsRows", lines.size, Diag.path),
                size = T.caption,
                color = palette.textTertiary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            TapChip(label = L10n.t("shell.refresh"), onClick = { lines = Diag.tail() })
            Spacer(Modifier.width(8.dp))
            TapChip(
                label = L10n.t("shell.clear"),
                accent = palette.warn,
                onClick = {
                    Diag.clear()
                    lines = Diag.tail()
                },
            )
            Spacer(Modifier.width(8.dp))
            TapChip(label = L10n.t("shell.close"), onClick = onDismiss, accent = palette.accent)
        }
        if (lines.isEmpty()) {
            Txt(L10n.t("shell.logEmpty"), size = T.caption, color = palette.textTertiary, maxLines = 1)
        } else {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                lines.forEach { line ->
                    // 失败/异常行标红，扫一眼就能定位问题
                    val bad =
                        line.contains("失败") ||
                            line.contains("异常") ||
                            line.contains("Exception") ||
                            line.contains("error", ignoreCase = true)
                    Mono(
                        text = line,
                        size = T.micro,
                        color = if (bad) palette.error else palette.textSecondary,
                    )
                }
            }
        }
    }
}

/** 齿轮：跳转到宿主的模组管理页（本插件的配置项在那里改）。 */
@Composable
private fun SettingsButton() {
    val navigator = Host.rememberNavigator()
    IconToolButton(
        onClick = {
            SpaceRouter.closeShell()
            Diag.log("settings: 请求打开宿主模组管理页")
            val pluginId = PluginEnv.id
            val ok = pluginId.isNotBlank() && Host.openPluginConfig(navigator, pluginId)
            Diag.log("settings: 跳转结果=$ok navigator=${navigator?.javaClass?.name} pluginId=$pluginId")
        },
    ) {
        io.github.univers629.spwtagworkbench.ui.icons.IconSettings(
            color = Salt.textSecondary,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun ToolButton(
    label: String = "",
    glyph: String = "",
    trailing: String = "",
    active: Boolean = false,
    accent: Color? = null,
    /** 禁用时整块变淡且不可点。用于「常驻但当前无事可做」的按钮（如无改动时的保存）。 */
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val palette = Salt
    var hovered by remember { mutableStateOf(false) }
    val bg =
        when {
            !enabled -> Color.Transparent
            active -> palette.selected
            hovered -> palette.hover
            else -> Color.Transparent
        }
    Box(
        modifier =
            Modifier
                .height(32.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(bg)
                .clickable(enabled = enabled, onClick = onClick)
                .onPointerEvent(PointerEventType.Enter) { if (enabled) hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false }
                .padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 禁用时整体降到 textTertiary，一眼看出「这个按钮现在不能点」
        val fg = if (enabled) (accent ?: palette.textSecondary) else palette.textTertiary
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (glyph.isNotEmpty()) {
                Txt(glyph, size = Glyph.small, color = fg, maxLines = 1)
            }
            if (label.isNotEmpty()) {
                Txt(label, size = T.label, color = fg, maxLines = 1)
            }
            if (trailing.isNotEmpty()) {
                Spacer(Modifier.width(3.dp))
                Txt(trailing, size = T.caption, color = palette.textTertiary, maxLines = 1)
            }
        }
    }
}

/**
 * 空 Indication：不画任何点击反馈。
 *
 * 覆盖 `LocalIndication` 后所有 `clickable` 都不再显示 Material 波纹
 * （就是「点一下向外扩散一圈」那个效果），点击行为不受影响。
 *
 * Compose 1.12 用 `IndicationNodeFactory`；这里返回一个无副作用的节点，
 * 即不绘制任何内容。
 */
internal data object NoIndication : androidx.compose.foundation.IndicationNodeFactory {
    /**
     * **每次都必须返回新节点**。
     *
     * `DelegatingNode.delegate()` 不允许同一个节点被多个宿主委托；
     * 返回单例会让第二个 clickable 初始化时抛
     * `IllegalStateException: Cannot delegate to an already delegated node`。
     * 页面里 clickable 很多，鼠标移过任意按钮就会崩。
     */
    override fun create(
        interactionSource: androidx.compose.foundation.interaction.InteractionSource,
    ): androidx.compose.ui.node.DelegatableNode = object : androidx.compose.ui.Modifier.Node() {}
}

// ── 悬浮按钮 ─────────────────────────────────────────────────────────────────

@Composable
internal fun FabButton(
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 按钮主图标，颜色由展开状态决定。 */
    icon: @Composable (Color) -> Unit,
) {
    val palette = Salt
    var hovered by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 45f else 0f,
        animationSpec = tween(PanelMs, easing = PanelEase),
        label = "fabRotation",
    )

    Card(
        modifier = modifier,
        // 材质下按钮取 85% 不透明度的浮层底色（见 Palette.fabCard），
        // 无材质时仍是原来的不透明原色。
        color = if (hovered) palette.fabCardRaised else palette.fabCard,
        radius = 22.dp,
        outline = palette.border,
    ) {
        Box(
            modifier =
                Modifier
                    .size(44.dp)
                    .clickable(onClick = onClick)
                    .onPointerEvent(PointerEventType.Enter) { hovered = true }
                    .onPointerEvent(PointerEventType.Exit) { hovered = false },
            contentAlignment = Alignment.Center,
        ) {
            val tint = if (expanded) palette.accent else palette.textSecondary
            Box(Modifier.graphicsLayer { rotationZ = rotation }) { icon(tint) }
        }
    }
}

/** 分页图标按钮：32dp 方块、圆角 9dp 背景，hover/按下有底色。 */
@Composable
private fun IconToolButton(
    onClick: () -> Unit,
    /** 是否为「当前激活」状态（如曲库列已展开）。激活时底色高亮。 */
    active: Boolean = false,
    content: @Composable () -> Unit,
) {
    val palette = Salt
    var hovered by remember { mutableStateOf(false) }
    Box(
        modifier =
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(
                    when {
                        active -> palette.selected
                        hovered -> palette.hover
                        else -> Color.Transparent
                    },
                ).clickable(onClick = onClick)
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false }
                .padding(7.dp),
        // 矢量图标用 fillMaxSize 铺满，居不居中看不出来；
        // 但传入文本字形（如关闭用的 ✕）时必须显式居中，否则会贴在左上角。
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
