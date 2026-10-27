// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

@file:OptIn(
    androidx.compose.runtime.InternalComposeApi::class,
    com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class,
)

package io.github.univers629.spwtagworkbench.ui.titlebar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.ComposableHook
import com.xuncorp.spw.workshop.api.hook.UiHookCall
import io.github.univers629.spwtagworkbench.core.Diag
import io.github.univers629.spwtagworkbench.core.Settings
import io.github.univers629.spwtagworkbench.core.i18n.L10n
import io.github.univers629.spwtagworkbench.router.SpacePage
import io.github.univers629.spwtagworkbench.router.SpaceRouter
import io.github.univers629.spwtagworkbench.ui.sidebar.SidebarUi
import java.lang.reflect.Method

/**
 * 顶部标题栏的「召唤按钮」。
 *
 * 一枚按钮，**单击直达**（没有下拉菜单）。点它做什么由宿主「模组管理」页的
 * `titlebar.action` 决定：
 *
 * | `titlebar.action` | 单击动作 |
 * | --- | --- |
 * | `workbench`（默认） | [SpaceRouter.open] 展开工作台宽视图 |
 * | `sidebar` | [SidebarUi.toggle] 开合右侧全局侧栏 |
 *
 * 选择放在宿主设置页而不是按钮菜单里 —— 标题栏按钮应当一键直达，
 * 多一次「点开菜单再选」是多余的。
 *
 * 按钮外壳不自绘：直接调用宿主原生的图标标题栏按钮控件（[TitlebarHost]），
 * 于是点击、悬停、无障碍角色、尺寸全部跟随宿主主题，与标题栏其它按钮一致；
 * 插件只提供图标与点击回调。
 */
object TitlebarEntry {
    /**
     * 注册顶部按钮。
     *
     * 走 `afterComposable`：在宿主标题栏那一排的某个子组件**之后**追加渲染，
     * 因此按钮落进同一排、继承同一套布局与主题。
     *
     * 这里显式构造 [ComposableHook]（而不是直接传 lambda）：`afterComposable` 的
     * lambda 重载要求 `@Composable (UiHookCall) -> Unit`，与项目里其它注册点
     * 用的 `ComposableHook` 写法不同，显式对象能让签名一目了然。
     *
     * @param anchor 目标锚点方法（由 [io.github.univers629.spwtagworkbench.backend.host.TitlebarDiscovery] 发现）
     * @param inClass 锚点的调用方类（**JVM 名**，点分隔）：Hook 框架要求「该类内对锚点的调用必须唯一」
     */
    fun register(
        anchor: Method,
        inClass: String,
    ): AutoCloseable {
        // 字节码内部名（斜杠）在这里会变成宿主眼里的「找不到的类」：
        // 注册直接抛 Hook class not found，按钮静默消失且没有其它症状。
        // 在这里显式挡住，避免到运行时才发现。
        check('/' !in inClass) { "inClass 必须是 JVM 名（点分隔），收到内部名：$inClass" }
        return WorkshopApi.hookRegistrar.afterComposable(
            anchor.declaringClass.name,
            anchor.name,
            inClass,
            emptyList(),
            50,
            object : ComposableHook() {
                @Composable
                override fun Content(call: UiHookCall) {
                    TitlebarSummonButton()
                }
            },
        )
    }
}

/**
 * 按钮本体：图标 + 单击直达。
 *
 * **没有下拉菜单** —— 单击直接执行「宿主设置页里选定的动作」：
 * `titlebar.action = workbench` 打开工作台，`= sidebar` 开合侧栏。
 * 二选一放在宿主的「模组管理」页，标题栏按钮始终是一键直达。
 *
 * [Settings.titlebarEntry] 关闭时不画按钮（注册仍保留，避免反复装卸钩子）。
 */
@Composable
fun TitlebarSummonButton() {
    if (!Settings.titlebarEntry) return
    val label = L10n.t("titlebar.label")
    val control = TitlebarHost.control ?: return

    val icon: @Composable BoxScope.() -> Unit = {
        TitlebarIcon(Modifier.align(Alignment.Center))
    }

    // 用宿主原生控件画外壳，图标槽由插件绘制。
    //
    // 参数对位（签名见 TitlebarDiscovery.CAPTION）：
    //   onClick / 无障碍文本 / Modifier / boolean / String / width / 图标槽 / Composer / changed / defaults
    //
    // 末位传宿主缺省参数位掩码：让第 6 个 float 宽度取宿主默认值（实测 40dp）。
    // 传错会让按钮比同排其它按钮宽或窄，所以这里绝不自拟一个宽度数字。
    control.invoke(
        null,
        { runTitlebarAction() },
        label,
        Modifier.semantics { contentDescription = label },
        true,
        null,
        0f,
        icon,
        currentComposer,
        TitlebarHost.CHANGED_DEFAULT,
        TitlebarHost.DEFAULT_WIDTH_MASK,
    )

    LaunchedEffect(Unit) {
        Diag.logOnce(
            "titlebar-icon",
            "titlebar: summon icon ${IconSize.value}dp resource=${TitlebarIcon.RESOURCE} " +
                "action=${Settings.titlebarAction} control=${TitlebarHost.controlName}",
        )
    }
}

/**
 * 执行宿主设置里选定的动作。
 *
 * 每次点击都重读一次设置：用户可能在宿主的「模组管理」页改完选项后
 * 直接回来点按钮，而宿主改配置**不会**回调插件（见 [Settings.refresh] 的说明）。
 *
 * 两个动作都只改插件自己的 Compose 状态；失败只记日志，不影响宿主。
 */
private fun runTitlebarAction() {
    Settings.refresh()
    val action = Settings.titlebarAction
    runCatching {
        when (action) {
            Settings.TITLEBAR_ACTION_SIDEBAR -> SidebarUi.toggle()
            else -> SpaceRouter.open(SpacePage.Desk)
        }
    }.onFailure {
        Diag.log("titlebar: 执行 $action 失败 ${it.javaClass.simpleName}: ${it.message.orEmpty().take(80)}")
    }
}

/**
 * 图标尺寸。
 *
 * **20dp**，与宿主 Quick 按钮的图标一致：宿主 Quick 在图标槽里用
 * `SizeKt.size(20.dp)` 包住 `ImageKt.Image`（字节码实测），所以跟着它取 20dp，
 * 两枚按钮的图标视觉大小才会齐平。
 */
private val IconSize = 20.dp

/**
 * 标题栏图标：绘制工程提供的 SVG。
 *
 * 用 Compose 的 `loadSvgPainter` 解析（与 SPW-Listen-Stats 同一套做法），
 * 保留 SVG 自身的渐变与配色，**不套 tint** —— 单色 tint 会把渐变压平。
 *
 * 尺寸固定 [IconSize]（20dp，与宿主 Quick 的图标一致），
 * painter 用 `remember` 缓存：宿主每次重组标题栏都会走到图标槽，
 * 每次重新解析 SVG 会产生分配。
 */
@Composable
private fun TitlebarIcon(modifier: Modifier = Modifier) {
    val painter = rememberTitlebarPainter() ?: return
    Box(
        modifier
            .size(IconSize)
            .drawWithContent { with(painter) { draw(size) } },
    )
}

/** 解析一次并缓存；失败返回 null（按钮仍在，只是没有图标）。 */
@Composable
private fun rememberTitlebarPainter(): Painter? {
    val density = LocalDensity.current
    return remember(density) {
        runCatching {
            requireNotNull(TitlebarEntry::class.java.getResourceAsStream(TitlebarIcon.RESOURCE)) {
                "missing ${TitlebarIcon.RESOURCE}"
            }.use { loadSvgPainter(it, density) }
        }.onFailure {
            Diag.log("titlebar: SVG 解析失败 ${it.javaClass.simpleName}: ${it.message.orEmpty().take(100)}")
        }.getOrNull()
    }
}

/** 图标资源位置。 */
private object TitlebarIcon {
    /** classpath 路径；文件放在 `src/main/resources/icons/`。 */
    const val RESOURCE = "/icons/music_tag_workbench.svg"
}
