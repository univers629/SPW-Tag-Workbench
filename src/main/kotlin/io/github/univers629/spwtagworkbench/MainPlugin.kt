// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package io.github.univers629.spwtagworkbench

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.WorkshopApi
import io.github.univers629.spwtagworkbench.backend.host.TitlebarDiscovery
import io.github.univers629.spwtagworkbench.core.Diag
import io.github.univers629.spwtagworkbench.core.HostLanguage
import io.github.univers629.spwtagworkbench.core.PluginEnv
import io.github.univers629.spwtagworkbench.core.Settings
import io.github.univers629.spwtagworkbench.core.i18n.L10n
import io.github.univers629.spwtagworkbench.data.Repo
import io.github.univers629.spwtagworkbench.router.ShellState
import io.github.univers629.spwtagworkbench.router.SpacePage
import io.github.univers629.spwtagworkbench.router.SpaceRouter
import io.github.univers629.spwtagworkbench.ui.HostPassthrough
import io.github.univers629.spwtagworkbench.ui.PluginOverlay
import io.github.univers629.spwtagworkbench.ui.titlebar.TitlebarEntry
import io.github.univers629.spwtagworkbench.ui.titlebar.TitlebarHost
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * 内容区接管 + 全局侧栏。
 *
 * 两者都在应用页层（AppNavigation 的位置）：位于 PlayerSurfaceGlass 与
 * PlayerBottomSheet 之前，所以宽视图与侧栏都会被播放条底栏盖住 ——
 * 它们是「页面的一部分」，不是盖住一切。
 *
 * 注册方式优先用 [WorkshopApi.HookRegistrar.replaceComposable] 接管内容区：
 * 这样可以把宿主内容「测量但不放置」，让窗口的 DWM 材质裸露出来。
 * 替换失败时退回 append 方式，材质与透视一并退回不透明。
 */
class MainPlugin(
    pluginContext: PluginContext,
) : SpwPlugin(pluginContext) {
    /** 替换体句柄（replace 模式）。 */
    private var replaceHandle: AutoCloseable? = null

    /** 浮层句柄（append 模式）。 */
    private var appendHandle: AutoCloseable? = null

    /**
     * 宿主导航 hook 句柄。
     *
     * 宿主文档说停用时会自动清理，但**不依赖它**：主动保存并在 [stop] 关闭，
     * 避免「宿主某版本不清理」或「热重载后新旧 hook 叠加」时同一次跳页
     * 触发多次回调、旧类加载器持有的状态无法回收。
     */
    private val navHandles = ArrayList<AutoCloseable>()

    /** 插件动作快捷键句柄；宿主停用时会自动清理，[stop] 里主动注销。 */
    private var shortcutHandle: AutoCloseable? = null

    /** 顶部标题栏召唤按钮的句柄。 */
    private var titlebarHandle: AutoCloseable? = null

    override fun start() {
        // 先绑定宿主给出的插件 ID：后面所有落数据目录的逻辑都依赖它
        PluginEnv.bind(pluginContext.pluginId)
        L10n.install(HostLanguage.effectiveTag())
        Settings.load()
        // 清掉上次异常退出可能残留的草稿图片临时文件
        io.github.univers629.spwtagworkbench.data.Drafts
            .sweepImageDir()
        Diag.session("start v=" + pluginContext.pluginVersion + " spw=" + pluginContext.spwVersion + " path=" + pluginContext.pluginPath)

        val canHook =
            runCatching {
                WorkshopApi.manager.isPermissionGranted(PluginPermission.CLASS_TRANSFORM)
            }.getOrDefault(false)

        Diag.log(
            "canHook=" + canHook + " windowStyle=" + io.github.univers629.spwtagworkbench.core.Host.windowStyle + " dark=" +
                io.github.univers629.spwtagworkbench.core.Host
                    .detectDark(),
        )

        if (canHook) {
            mount()
            registerHostNavigation()
            registerTitlebarEntry()
        }
        registerShortcut()

        // 字体：确认用的是宿主 MiSans，而不是系统默认
        io.github.univers629.spwtagworkbench.core.Host.sans
        Diag.log("font: " + io.github.univers629.spwtagworkbench.core.Host.fontStatus)
        // 调试自检：数据目录存在 debug-sweep.flag 时，依次打开四个专题页各 700ms，
        // 让每个页面的顶栏组合一次并把 header: 日志写进 diag.log。
        val dataDir = java.io.File(Diag.path).parentFile
        if (dataDir != null && java.io.File(dataDir, "debug-sweep.flag").exists()) {
            Diag.log("sweep: 检测到调试标记，开始逐页自检")
            kotlin.concurrent.thread(isDaemon = true, name = "spw-sweep") {
                Thread.sleep(4000)
                runCatching { SpaceRouter.open(SpacePage.Desk) }
                Thread.sleep(1200)
                for (page in listOf(SpacePage.Lyrics, SpacePage.Cover, SpacePage.Spectrum, SpacePage.Artist)) {
                    runCatching { SpaceRouter.navigate(page) }
                    Thread.sleep(1500)
                }
                runCatching { SpaceRouter.closeShell() }
                Diag.log("sweep: 自检结束")
            }
        }
        Repo.bootstrap()
        Repo.startPlaybackFollow()
        // 启动即探网：网络可达性直接从 diag.log 判断，不需要先操作界面
        kotlin.concurrent.thread(isDaemon = true, name = "spw-netprobe") {
            io.github.univers629.spwtagworkbench.backend.net.NetProbe
                .probe()
        }
    }

    override fun stop() {
        runCatching { replaceHandle?.close() }
        runCatching { appendHandle?.close() }
        runCatching { topLevelHandle?.close() }
        runCatching { titlebarHandle?.close() }
        replaceHandle = null
        appendHandle = null
        topLevelHandle = null
        titlebarHandle = null
        // 清空宿主方法绑定：下次启用重新扫描，不复用旧句柄
        TitlebarHost.clear()
        // 主动注销宿主导航 hook 与快捷键：句柄一个不漏地关掉
        for (h in navHandles) runCatching { h.close() }
        navHandles.clear()
        runCatching { shortcutHandle?.close() }
        shortcutHandle = null
        Repo.shutdown()
        SpaceRouter.hideForHostNavigation()
    }

    override fun delete() {}

    override fun update() {}

    // ── 快捷键 ────────────────────────────────────────────────────────────────

    /**
     * 注册打开工作台的宿主快捷键。
     *
     * 注册后该动作出现在宿主的「快捷键」设置页，用户可自行改键或配置全局热键；
     * 应用内默认 Ctrl+Alt+T。文本输入框聚焦时应用内键不触发，冲突键不触发。
     *
     * 需要 KEY_BINDINGS 权限，且必须在 [start] 线程内注册。
     */
    private fun registerShortcut() {
        runCatching {
            if (!WorkshopApi.manager.isPermissionGranted(PluginPermission.KEY_BINDINGS)) {
                Diag.log("shortcut: 未授予 KEY_BINDINGS，跳过注册")
                return
            }
            shortcutHandle =
                WorkshopApi.manager.keyBindingManager.register(
                    actionId = "open-workbench",
                    title = L10n.t("shortcut.openWorkbench"),
                    defaultShortcut =
                        com.xuncorp.spw.workshop.api.ActionShortcut(
                            java.awt.event.KeyEvent.VK_T,
                            com.xuncorp.spw.workshop.api.ActionShortcut.CONTROL or
                                com.xuncorp.spw.workshop.api.ActionShortcut.ALT,
                        ),
                    hasGlobal = true,
                ) {
                    // 回调可能不在界面线程：切到 EDT 再改 Compose 状态
                    runCatching { javax.swing.SwingUtilities.invokeLater { openWorkbench() } }
                }
            Diag.log("shortcut: 已注册 open-workbench（默认 Ctrl+Alt+T，可在宿主快捷键设置中改键）")
        }.onFailure { t ->
            Diag.log("shortcut: 注册失败 ${t.javaClass.simpleName}: ${t.message.orEmpty().take(80)}")
        }
    }

    // ── 挂载 ──────────────────────────────────────────────────────────────────

    /**
     * 两个钩子分工明确，缺一不可但互不拖累：
     *
     * | 钩子 | 内容 | 作用 |
     * | --- | --- | --- |
     * | `afterComposable` | [PluginOverlay] | 插件自己的界面（FAB / 侧栏 / 宽视图）。**必须成功** |
     * | `replaceComposable` | [HostPassthrough] | 只把宿主内容画回去（或按需隐身）。可选增强 |
     *
     * 之所以拆开：替换体一旦画了插件界面，插件界面出问题就会连带宿主内容区一起失效
     * （设置页卡加载、导航点不动）。现在替换体只透传宿主内容，最坏情况只是材质透不出来。
     *
     * - Win11：替换体正常 → 宽视图展开时宿主内容隐身 → DWM 材质透出
     * - Win10 / 替换失效：关掉替换句柄，宿主自绘，插件界面照常
     */
    private fun mount() {
        if (!installAppend()) {
            Diag.log("mount: FATAL — afterComposable 失败，插件界面无法挂载")
            return
        }
        // 侧栏优先挂顶层（层级 A，盖住宿主底栏）；失败自动退回层级 B
        installTopLevelSidebar()
        // 只在宿主有窗口材质时接管内容区。
        // windowStyle=None（Win10）时替换没有收益，却会让宿主自己的页面（设置/模组管理）
        // 在"替换 → 看门狗关闭 → 恢复自绘"的一轮里跑到坏掉的组合树上，界面卡在加载态。
        val materialAvailable = io.github.univers629.spwtagworkbench.core.Host.windowStyle != "None"
        if (materialAvailable) {
            if (tryInstallReplace()) startReplaceWatchdog()
        } else {
            Diag.log("mount: windowStyle=None -> 跳过 replace，宿主自绘（不影响宿主页面）")
        }
    }

    /**
     * **顶层（层级 A）侧栏挂载**：画在宿主底栏与玻璃浮层之上。
     *
     * guide §9.2 的两个全局层级里，当前 [PluginOverlay] 挂在应用页层（层级 B），
     * 会被 PlayerSurfaceGlass / PlayerBottomSheet 盖住 —— 侧栏与 FAB 在宿主底栏
     * 出现时被遮挡。层级 A 画在它们之上，正是「侧边抽屉、悬浮面板」的指定位置。
     *
     * 失败容忍：顶层三点位全是混淆名，跨版本风险比层级 B 高。
     * 挂不上时侧栏退回层级 B 渲染（[PluginOverlay] 内），功能不丢、只是可能被底栏盖。
     */
    private var topLevelHandle: AutoCloseable? = null

    /** 是否因为替换体失败而把界面强制成不透明（用于透传成功后恢复材质）。 */
    @Volatile
    private var forceOpaqueByReplace = false

    private fun installTopLevelSidebar() {
        topLevelHandle =
            runCatching {
                WorkshopApi.hookRegistrar.afterComposable(
                    // 1.19.2 实测点位：guide §9.2 的 className 写的是 Š(U+0160)，
                    // 但那个类在 1.19.2 是死代码（全 JAR 无一引用）。
                    // 真正持有顶层 Composable 的是 К(U+041A)——MainSurface 的实现类，
                    // 常量池验证 ѐ 对 К.Ϳ 有且仅有 1 个调用点（偏移 179）。
                    className = "androidx.compose.ui.\u041A",
                    methodName = "\u037F",
                    inClass = "androidx.compose.ui.\u0450",
                    parameterTypes = null,
                ) {
                    io.github.univers629.spwtagworkbench.ui.sidebar
                        .SidebarLayer()
                }
            }.onFailure { t ->
                // 失败原因必须进日志：三个混淆点位任何一个对不上都会走到这里，
                // 没有具体异常就无法判断是「调用点不唯一」还是「签名不匹配」
                Diag.log(
                    "mount: 顶层注册异常 ${t.javaClass.name}: ${t.message.orEmpty().take(120)}",
                )
            }.getOrNull()
        if (topLevelHandle != null) {
            io.github.univers629.spwtagworkbench.ui.sidebar.SidebarLayerState.location =
                io.github.univers629.spwtagworkbench.ui.sidebar.SidebarLayerLocation.TOP
            Diag.log("mount: 顶层侧栏已注册（层级 A）")
        } else {
            Diag.log("mount: 顶层注册失败，侧栏退回应用页层（层级 B）")
        }
    }

    /**
     * **顶部标题栏召唤按钮**。
     *
     * 扫描宿主 jar（4 万多个 class）在后台线程执行，但本函数**会阻塞等待**结果：
     * Hook 注册必须发生在宿主组合线程之前，因此只能同步取回点位。
     * 代价是插件启动最多多等这次扫描（实测约 1~3 秒），
     * 由 [TITLEBAR_DISCOVERY_TIMEOUT_MS] 封顶；超时即放弃顶部按钮。
     *
     * 失败容忍：顶部点位是纯结构推断，跨版本风险高于未混淆的类名。
     * 任何一步对不上都只记日志，插件其余功能照常。
     */
    private fun registerTitlebarEntry() {
        val position = TitlebarDiscovery.Position.fromKey(Settings.titlebarPosition)
        runCatching {
            val bound =
                CompletableFuture
                    .supplyAsync { TitlebarHost.bind(position) }
                    .get(TITLEBAR_DISCOVERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            check(bound) { TitlebarHost.lastError ?: "bind failed" }
            val anchor = checkNotNull(TitlebarHost.anchor) { "anchor unavailable" }
            val inClass = checkNotNull(TitlebarHost.inClass) { "inClass unavailable" }
            titlebarHandle = TitlebarEntry.register(anchor, inClass)
            Diag.log("mount: 顶部召唤按钮已注册（position=${position.key}）")
        }.onFailure { t ->
            Diag.log("mount: 顶部召唤按钮不可用 ${t.javaClass.simpleName}: ${t.message.orEmpty().take(120)}")
            titlebarHandle = null
        }
    }

    /** 主界面：浮层模式。宿主自己画内容，插件叠一层，未展开时完全透明。 */
    private fun installAppend(): Boolean =
        try {
            appendHandle =
                WorkshopApi.hookRegistrar.afterComposable(
                    className = NAV_CLASS,
                    methodName = NAV_METHOD,
                    inClass = NAV_CALLER,
                    parameterTypes = null,
                ) {
                    PluginOverlay()
                }
            Diag.log("mount: afterComposable registered (plugin overlay)")
            true
        } catch (e: Throwable) {
            Diag.log("mount: afterComposable FAILED ${e.javaClass.name}: ${e.message}")
            appendHandle = null
            false
        }

    /** 材质增强：宿主内容透传。异常必须进日志，不能被吞掉。 */
    private fun tryInstallReplace(): Boolean =
        try {
            replaceHandle =
                WorkshopApi.hookRegistrar.replaceComposable(
                    className = NAV_CLASS,
                    methodName = NAV_METHOD,
                    inClass = NAV_CALLER,
                    parameterTypes = null,
                ) {
                    HostPassthrough()
                }
            Diag.log("mount: replaceComposable registered (host passthrough)")
            true
        } catch (e: Throwable) {
            Diag.log("mount: replaceComposable FAILED ${e.javaClass.name}: ${e.message}")
            replaceHandle = null
            false
        }

    /**
     * 看门狗：确认透传体被组合过；失败时重试注册，成功后恢复材质。
     *
     * 注册成功只代表「没抛异常」。宿主什么时候把内容区组合起来是不确定的：
     * 实测同一份构建，有时 2.5 秒内就调用替换体，有时更晚——固定超时会让材质
     * 「时灵时不灵」。因此这里按轮次探测：
     *
     * | 情况 | 判据 | 处理 |
     * | --- | --- | --- |
     * | 正常 | 存活信号已置位 | 记日志；若之前因失败置过 `forceOpaque`，清回去 |
     * | 组合失败（会白屏） | 进入过组合但没有存活信号 | 关掉替换避免白屏，隔一会重装钩子再试 |
     * | 宿主尚未调用 | 一次都没进入组合 | 继续等；整轮都没动静就重装钩子再试 |
     *
     * 总预算约 [REPLACE_MAX_ATTEMPTS] ×（[REPLACE_WAIT_MS] + [REPLACE_RETRY_DELAY_MS]），
     * 仍然失败才彻底放弃并保持不透明。
     */
    private fun startReplaceWatchdog() {
        Thread({
            runCatching {
                var attempt = 0
                while (attempt < REPLACE_MAX_ATTEMPTS && !ShellState.replaceAlive) {
                    attempt++
                    val deadline = System.currentTimeMillis() + REPLACE_WAIT_MS
                    while (System.currentTimeMillis() < deadline && !ShellState.replaceAlive) {
                        Thread.sleep(250)
                    }
                    if (ShellState.replaceAlive) break
                    Diag.log(
                        "material: replace 第 $attempt 轮未存活 " +
                            "invocations=${ShellState.replaceInvocations} err=${ShellState.replaceError ?: "-"}",
                    )
                    if (ShellState.replaceInvocations > 0) {
                        // 进入过组合却没走完：保留替换会白屏，先关掉
                        runCatching { replaceHandle?.close() }
                        replaceHandle = null
                        forceOpaqueByReplace = true
                        Settings.forceOpaque = true
                    } else {
                        // 宿主没调用过：可能注册时宿主的这一帧已经过去了，重装一次钩子
                        runCatching { replaceHandle?.close() }
                        replaceHandle = null
                    }
                    if (attempt < REPLACE_MAX_ATTEMPTS) {
                        Thread.sleep(REPLACE_RETRY_DELAY_MS)
                        ShellState.replaceInvocations = 0
                        ShellState.replaceError = null
                        tryInstallReplace()
                    }
                }
                if (ShellState.replaceAlive) {
                    Diag.log("material: replace alive ✓ (宿主内容透传中)")
                    if (forceOpaqueByReplace) {
                        // 之前失败时把界面转成了不透明，现在透传成功要恢复材质
                        Settings.forceOpaque = false
                        forceOpaqueByReplace = false
                        Diag.log("material: 恢复材质（forceOpaque=false）")
                    }
                } else {
                    Diag.log("material: replace 最终失败 -> 关闭替换，宿主自绘（界面保持不透明）")
                    runCatching { replaceHandle?.close() }
                    replaceHandle = null
                    Settings.forceOpaque = true
                }
            }
        }, "spw-replace-watchdog").apply { isDaemon = true }.start()
    }

    // ── 宿主跳页时收起宽视图，避免盖住宿主自己的页面 ───────────────────────────

    private fun registerHostNavigation() {
        runCatching {
            for (method in listOf("navigate", "replace")) {
                navHandles +=
                    WorkshopApi.hookRegistrar.hookMethod(
                        className = "com.xuncorp.voxzen.ui.navigation.Navigator",
                        methodName = method,
                        parameterTypes = listOf("com.xuncorp.voxzen.ui.navigation.ScreenRoute"),
                    ) {
                        before { SpaceRouter.hideForHostNavigation() }
                    }
            }
            navHandles +=
                WorkshopApi.hookRegistrar.hookMethod(
                    className = "com.xuncorp.voxzen.ui.navigation.Navigator",
                    methodName = "forward",
                    parameterTypes = emptyList(),
                ) {
                    before { SpaceRouter.hideForHostNavigation() }
                }
            Diag.log("mount: 宿主导航 hook 已注册 ${navHandles.size} 个")
        }
    }

    companion object {
        /** 应用页层的插入点：位于 PlayerSurfaceGlass / PlayerBottomSheet 之前。 */
        private const val NAV_CLASS = "com.xuncorp.voxzen.ui.navigation.AppNavigationKt"
        private const val NAV_METHOD = "AppNavigation"

        /**
         * AppNavigation 的实际调用方所在类（Compose 混淆名）。
         *
         * 这个值**宿主每次发版都可能变**，是升级后最需要重新定位的点位。
         * 1.19.0 = `androidx.compose.ui.\u06C4`（阿拉伯字母）
         * 1.19.1 = `androidx.compose.ui.\u0443`（西里尔字母 у）
         *
         * 定位方法见 `宿主点位提取方法论.md` §6 Step 4b：
         * 在宿主 JAR 里找常量池 Methodref 真正 invoke 了
         * `AppNavigationKt.AppNavigation` 的那个类。
         */
        private const val NAV_CALLER = "androidx.compose.ui.\u0443"

        /** 每轮等宿主调用替换体的时长。留足首帧时间，避免误判。 */
        private const val REPLACE_WAIT_MS = 3_000L

        /** 失败后重装钩子再试的间隔。 */
        private const val REPLACE_RETRY_DELAY_MS = 3_000L

        /** 最多重试几轮；总预算约 24 秒。 */
        private const val REPLACE_MAX_ATTEMPTS = 4

        /**
         * 顶部按钮的结构发现预算。
         *
         * 扫描宿主 jar 实测约 1～3 秒；超时即放弃顶部按钮，
         * 不让一个可选入口拖住整个插件启动。
         */
        private const val TITLEBAR_DISCOVERY_TIMEOUT_MS = 12_000L

        /** 设置页按钮入口。 */
        @JvmStatic
        fun openWorkbench() {
            SpaceRouter.open(SpacePage.Desk)
        }

        /** 设置页「快捷键设置」入口：宿主的快捷键管理页由用户侧打开，这里给出路径提示。 */
        @JvmStatic
        fun showShortcutHint() {
            runCatching {
                WorkshopApi.ui.toast(L10n.t("shortcut.hint"), WorkshopApi.Ui.ToastType.Success)
            }
        }

        /**
         * 插件仓库地址。
         *
         * 与 `build.gradle.kts` 的 `PluginOpenSourceUrl`（写进清单的
         * `Plugin-Open-Source-Url`，宿主模组设置页显示的那个链接）保持一致。
         */
        const val SOURCE_URL = "https://github.com/univers629/SPW-Tag-Workbench"

        /** 设置页「开源项目」入口：用系统默认浏览器打开仓库。 */
        @JvmStatic
        fun openSourcePage() {
            try {
                check(java.awt.Desktop.isDesktopSupported()) { L10n.t("misc.browserUnsupported") }
                java.awt.Desktop
                    .getDesktop()
                    .browse(java.net.URI.create(SOURCE_URL))
            } catch (t: Throwable) {
                // 宿主环境里调不起浏览器时给可见反馈，不静默失败
                Diag.log("openSourcePage 失败：${t.javaClass.simpleName} ${t.message.orEmpty().take(80)}")
                runCatching {
                    WorkshopApi.ui.toast(
                        L10n.t("misc.openSourceFailed", t.message.orEmpty().take(60)),
                        WorkshopApi.Ui.ToastType.Error,
                    )
                }
            }
        }
    }
}
