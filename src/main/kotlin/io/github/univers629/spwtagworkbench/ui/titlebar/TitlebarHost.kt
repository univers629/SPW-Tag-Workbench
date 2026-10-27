// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package io.github.univers629.spwtagworkbench.ui.titlebar

import com.xuncorp.spw.workshop.api.WorkshopApi
import io.github.univers629.spwtagworkbench.backend.host.TitlebarDiscovery
import io.github.univers629.spwtagworkbench.core.Diag
import java.lang.reflect.Method

/**
 * 顶部按钮的宿主适配层：把「结构发现出来的混淆方法」翻译成可调用的 [Method]。
 *
 * 生命周期与插件一致：每次启用重新扫描、重新绑定，[clear] 在 [io.github.univers629.spwtagworkbench.MainPlugin.stop]
 * 里清空。**不做进程级缓存** —— 宿主升级后旧绑定的类会失效，
 * 缓存只会让下次启用用一个已经不对的方法句柄。
 */
object TitlebarHost {
    /**
     * `changed` 掩码：Compose 编译器约定，第一个 int 表示「哪些参数是动态的」。
     *
     * 传 0 表示本帧所有参数都按实参、静态处理，
     * 配合下面的 `defaults` 掩码，宿主会对未传实参的参数使用其自身缺省值。
     */
    const val CHANGED_DEFAULT = 0

    /**
     * 缺省参数位掩码：bit 5 对应第 6 个业务参数（`float` 宽度）。
     *
     * 含义是该参数不传实参，由宿主使用其自身默认值。宿主默认宽度实测 40dp，
     * 与同排其它 caption 按钮一致；插件不自拟宽度，避免按钮宽度和邻居对不齐。
     */
    const val DEFAULT_WIDTH_MASK = 32

    /** 宿主原生图标按钮的方法句柄；发现失败时为 null。 */
    @Volatile
    var control: Method? = null
        private set

    /** 本次注册绑定的锚点方法。 */
    @Volatile
    var anchor: Method? = null
        private set

    /** 发现到的锚点调用方类（Hook 框架唯一性校验用）。 */
    @Volatile
    var inClass: String? = null
        private set

    /** 控件名，仅用于日志。 */
    @Volatile
    var controlName: String = "-"
        private set

    /** 上次失败原因，写进诊断日志便于对不上点位时定位。 */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * 扫描 + 绑定。
     *
     * **必须在后台线程调用**：完整扫描宿主 jar（4 万多个 class）需要时间，
     * 放主线程会让启动卡住。
     *
     * @param position 按钮排在宿主 Quick 的哪一侧（来自设置项）
     * @return 绑定成功返回 true
     */
    fun bind(position: TitlebarDiscovery.Position): Boolean {
        lastError = null
        return runCatching {
            val host = WorkshopApi.instance.javaClass
            val source =
                java.nio.file.Path.of(
                    checkNotNull(host.protectionDomain.codeSource) { "host code source unavailable" }.location.toURI(),
                )
            val sites = TitlebarDiscovery.discover(source)
            val bound = TitlebarDiscovery.bind(sites.control, host.classLoader)
            val anchorSite = sites.anchor(position) ?: error("no anchor for position=$position")

            control = bound
            anchor = TitlebarDiscovery.bind(anchorSite, host.classLoader)
            // 字节码里的 owner 是**内部名**（`androidx/compose/ui/ৱ`），
            // 而 `afterComposable` 的 className/inClass 要的是 **JVM 名**（点分隔）。
            // 少了这一步，宿主会直接抛 `Hook class not found`，按钮静默不出现。
            inClass = sites.inClassJvm
            controlName = "${sites.control.owner}.${sites.control.name}"
            Diag.log("titlebar: 发现成功 ${sites.describe()} position=${position.key}")
            true
        }.onFailure { failure ->
            control = null
            anchor = null
            inClass = null
            lastError = "${failure.javaClass.simpleName}: ${failure.message.orEmpty().take(160)}"
            Diag.log("titlebar: 结构发现失败 $lastError（顶部按钮不可用，其余功能不受影响）")
        }.getOrDefault(false)
    }

    /** 插件停用时清空绑定。 */
    fun clear() {
        control = null
        anchor = null
        inClass = null
        controlName = "-"
    }
}
