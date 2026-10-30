// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.core

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── 色板 ─────────────────────────────────────────────────────────────────────

/**
 * 一套色板。
 *
 * 材质可用时页面底是 **透明** 的：底层窗口的 DWM 材质直接透出来，
 * 卡片等元素用半透明覆盖色线性叠在材质上，一套配方就能吃亚克力 / 云母 / 云母 Alt。
 * 材质不可用（windowStyle = None）时退回不透明原色。
 */
data class Palette(
    val dark: Boolean,
    /** 是否真的透出了宿主材质（透明底才有意义）。 */
    val material: Boolean,
    val page: Color,
    val card: Color,
    val cardRaised: Color,
    val hover: Color,
    val selected: Color,
    val border: Color,
    val borderStrong: Color,
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val accentOn: Color,
    val accentSoft: Color,
    /** SaltUI 的高亮蓝：浅色 #0470E6 / 深色 #0088FF，进度条与选中态用它。 */
    val saltBlue: Color,
    val ok: Color,
    val warn: Color,
    val error: Color,
    /** 页面底（材质可用时是 Transparent）。 */
    val pageOverlay: Color,
    /** 卡片 / 面板底。 */
    val cardOverlay: Color,
    /** 遮罩：面板打开时调暗背后内容。 */
    val scrim: Color,
)

// ── 材质配方 ─────────────────────────────────────────────────────────────────
// 叠加是线性的：结果 = 背景 × (1 - α) + 覆盖色 × α
// 深色亚克力底 #505050 → 50×0.66 + 7C×0.34 ≈ #5F；深色云母底 #272727 → #44。

private val accentDark = Color(0xFF6FA8FF)
private val accentLight = Color(0xFF2C6BC4)

/** 浮层（全局侧栏、两个弹窗、下拉菜单）在材质状态下的不透明度。 */
private const val OVERLAY_ALPHA = 0.90f

private fun darkTint(alpha: Float) = Color(0xFF7C7C7C).copy(alpha = alpha)

/** 深色 + 任意材质（亚克力 / 云母 / 云母 Alt 共用同一套）。 */
private val darkMaterial =
    Palette(
        dark = true,
        material = true,
        page = Color.Transparent,
        card = darkTint(0.34f),
        cardRaised = darkTint(0.44f),
        hover = Color.White.copy(alpha = 0.08f),
        selected = Color.White.copy(alpha = 0.10f),
        border = Color(0xFF9B9BA1).copy(alpha = 1f / 3f),
        borderStrong = Color(0xFF9B9BA1).copy(alpha = 0.5f),
        text = Color(0xFFEBEEF1),
        // **实色，不用 alpha**：文字叠在亚克力/云母底上再带透明度会更淡，
        // 大面积侧栏尤其明显。这里直接给等效亮度的实色，层次靠明度区分。
        textSecondary = Color(0xFFC8CDD4),
        textTertiary = Color(0xFFA8AEB8),
        accent = accentDark,
        accentOn = Color(0xFF0B1420),
        accentSoft = accentDark.copy(alpha = 0.18f),
        saltBlue = Color(0xFF0088FF),
        ok = Color(0xFF6FCF97),
        warn = Color(0xFFE8B65C),
        error = Color(0xFFE57373),
        pageOverlay = Color.Transparent,
        cardOverlay = darkTint(0.34f),
        scrim = Color.Black.copy(alpha = 0.32f),
    )

/** 浅色亚克力。 */
private val lightAcrylic =
    Palette(
        dark = false,
        material = true,
        page = Color.Transparent,
        card = Color.White.copy(alpha = 0.16f),
        cardRaised = Color.White.copy(alpha = 0.34f),
        hover = Color.Black.copy(alpha = 0.075f),
        selected = Color.White.copy(alpha = 0.60f),
        border = Color(0xFF808080).copy(alpha = 0.28f),
        borderStrong = Color(0xFF808080).copy(alpha = 0.40f),
        text = Color(0xFF1E1715),
        // **实色，不用 alpha**（同深色版）。取值与无材质回退的浅色一致。
        textSecondary = Color(0xFF4A4A4A),
        textTertiary = Color(0xFF737373),
        accent = accentLight,
        accentOn = Color.White,
        accentSoft = accentLight.copy(alpha = 0.14f),
        saltBlue = Color(0xFF0470E6),
        ok = Color(0xFF2E8B57),
        warn = Color(0xFFA96D10),
        error = Color(0xFFC4342C),
        pageOverlay = Color.Transparent,
        cardOverlay = Color.White.copy(alpha = 0.16f),
        scrim = Color.Black.copy(alpha = 0.22f),
    )

/** 浅色云母 / 云母 Alt：底色比亚克力更亮，卡片要更实一点才压得住。 */
private val lightMica =
    lightAcrylic.copy(
        card = Color(0xFFFCFCFC).copy(alpha = 0.72f),
        cardRaised = Color(0xFFFCFCFC).copy(alpha = 0.86f),
        hover = Color.Black.copy(alpha = 0.049f),
        border = Color(0xFF808080).copy(alpha = 0.24f),
        cardOverlay = Color(0xFFFCFCFC).copy(alpha = 0.72f),
    )

// ── 无材质回退（经典不透明） ─────────────────────────────────────────────────

private val darkSolid =
    Palette(
        dark = true,
        material = false,
        page = Color(0xFF232323),
        card = Color(0xFF2A2A2A),
        cardRaised = Color(0xFF323232),
        hover = Color(0xFF363637),
        selected = Color(0xFF353C46),
        border = Color(0xFF353535),
        borderStrong = Color(0xFF464646),
        text = Color(0xFFEFEFEF),
        // 纯灰阶主题：原来 0xA2 / 0x6E 在 0x232323 背景上的对比度约 5.6:1 / 3.3:1，
        // 三级低于 WCAG AA 的 4.5:1，所以显淡。抬到 0xBE / 0x8E 后约 7.6:1 / 4.6:1。
        textSecondary = Color(0xFFBEBEBE),
        textTertiary = Color(0xFF8E8E8E),
        accent = accentDark,
        accentOn = Color(0xFF0B1420),
        accentSoft = Color(0xFF25313F),
        saltBlue = Color(0xFF0088FF),
        ok = Color(0xFF6FCF97),
        warn = Color(0xFFE8B65C),
        error = Color(0xFFE57373),
        pageOverlay = Color(0xFF232323),
        cardOverlay = Color(0xFF2A2A2A),
        scrim = Color.Black.copy(alpha = 0.32f),
    )

private val lightSolid =
    Palette(
        dark = false,
        material = false,
        page = Color(0xFFF8F8F8),
        card = Color(0xFFFCFCFC),
        cardRaised = Color(0xFFFFFFFF),
        hover = Color(0xFFE8E8E8),
        selected = Color(0xFFE6EFFB),
        border = Color(0xFFE4E4E4),
        borderStrong = Color(0xFFD0D0D0),
        text = Color(0xFF1C1C1C),
        // 浅灰主题：0x90 在 0xFFFCFCFC 白底上对比度仅 3.2:1，低于 AA 的 4.5:1。
        // 抬到 0x73 后约 4.6:1；次级同步提到 0x4A。
        textSecondary = Color(0xFF4A4A4A),
        textTertiary = Color(0xFF737373),
        accent = accentLight,
        accentOn = Color(0xFFFFFFFF),
        accentSoft = Color(0xFFDCE9FB),
        saltBlue = Color(0xFF0470E6),
        ok = Color(0xFF2E8B57),
        warn = Color(0xFFA96D10),
        error = Color(0xFFC4342C),
        pageOverlay = Color(0xFFF8F8F8),
        cardOverlay = Color(0xFFFCFCFC),
        scrim = Color.Black.copy(alpha = 0.18f),
    )

/**
 * **浮层实色取值**：全局侧栏、两个弹窗（日志 / 批量确认）与所有下拉菜单共用。
 *
 * 这些浮层在 Win11 材质模式下用半透明底会与背后内容重叠
 * （宿主内容的隐身机制是整棵树全有或全无，做不到只隐藏面板那一条竖带）。
 * 所以它们取 Win10 无材质配方（[darkSolid] / [lightSolid]）—— 那本来就是一套
 * 通过对比度校准的完整颜色规范，材质有无都成立。
 *
 * 材质状态下再把浮层底压到 [OVERLAY_ALPHA]（90%）：保住一点材质透感，
 * 又不会让背后内容透上来干扰阅读。深色下不能沿用材质配方里的中性灰卡面，
 * 那在 90% 时会让浅色文字掉到 3:1 以下。无材质时没有可透出的底，保持全不透明。
 */
private fun overlayOf(solid: Palette): Palette =
    solid.copy(
        card = solid.card.copy(alpha = OVERLAY_ALPHA),
        cardRaised = solid.cardRaised.copy(alpha = OVERLAY_ALPHA),
        cardOverlay = solid.cardOverlay.copy(alpha = OVERLAY_ALPHA),
    )

private val darkOverlay = overlayOf(darkSolid)
private val lightOverlay = overlayOf(lightSolid)

/**
 * 全局侧栏悬浮按钮在材质下的不透明度。
 *
 * 取 60%：按钮比侧栏面板（[SIDEBAR_MATERIAL_ALPHA]，75%）更透，
 * 让材质在按钮上透得更明显，同时仍保证图标可辨。
 * 无材质时没有可透出的底，按钮保持不透明原色。
 */
private const val FAB_MATERIAL_ALPHA = 0.60f

/**
 * 悬浮按钮底色：材质下用浮层配方的 60% 不透明度，无材质下用不透明原色。
 *
 * 之所以不直接用 [Palette.card]：材质配方的卡面是「提亮覆盖色」，
 * 叠在材质上的不透明度只有 16%–44%，深色下按钮会显得比旁边侧栏面板虚一截；
 * 而浮层配方（[overlaySolid]）在深浅两套主题下都是校准过的实色底，
 * 压到 60% 后能稳定透出材质；浅色下底色接近白色，低不透明度仍显得偏实。
 */
val Palette.fabCard: Color
    get() = if (material) overlaySolid.card.copy(alpha = FAB_MATERIAL_ALPHA) else card

/** 悬浮按钮悬停态底色，规则同 [fabCard]。 */
val Palette.fabCardRaised: Color
    get() = if (material) overlaySolid.cardRaised.copy(alpha = FAB_MATERIAL_ALPHA) else cardRaised

/**
 * 全局侧栏面板在材质下的不透明度。
 *
 * 与悬浮按钮（[FAB_MATERIAL_ALPHA]）同为 75%：按钮与面板是同一套浮层，
 * 视觉上要一起变。弹窗与下拉菜单仍保持 [OVERLAY_ALPHA]（90%）——
 * 它们是模态层，需要压住背后的编辑界面。
 */
private const val SIDEBAR_MATERIAL_ALPHA = 0.75f

/**
 * 侧栏面板配色：材质下把浮层配方压到 75%，无材质时直接用浮层实色（本来就不透明）。
 *
 * 只压 `card` / `cardRaised` / `cardOverlay` 三个底色，文字与强调色不动 ——
 * 它们本来就是不透明实色，带 alpha 会掉对比度。
 */
val Palette.sidebarSolid: Palette
    get() =
        if (!material) {
            overlaySolid
        } else {
            overlaySolid.copy(
                card = overlaySolid.card.copy(alpha = SIDEBAR_MATERIAL_ALPHA),
                cardRaised = overlaySolid.cardRaised.copy(alpha = SIDEBAR_MATERIAL_ALPHA),
                cardOverlay = overlaySolid.cardOverlay.copy(alpha = SIDEBAR_MATERIAL_ALPHA),
            )
        }

val Palette.overlaySolid: Palette
    get() =
        if (!material) {
            if (dark) darkSolid else lightSolid
        } else {
            if (dark) darkOverlay else lightOverlay
        }

val LocalPalette = staticCompositionLocalOf { darkSolid }

val Salt: Palette @Composable get() = LocalPalette.current

/**
 * 按宿主窗口材质与深浅取色板。
 *
 * @param windowStyle 宿主 SpwWindowStyle 的名字；"None" 表示材质不可用
 * @param forceOpaque 替换体组合中途抛异常时强制不透明（看门狗置位）
 *
 * 判据只看「宿主有没有材质」，**不看替换体是否接管**。材质配方的页面底是
 * [Color.Transparent]，未接管时宿主内容会透出来，但透明带来的材质连续性是
 * 主要观感来源；退回实色会与宿主的模糊材质形成明显接缝。
 *
 * 宿主内容是否隐藏由替换体负责（`RetainedHostContent`）：替换体运行时隐藏，
 * 不运行时宿主自绘。透明底在两种情况下都成立。
 */
fun saltPalette(
    dark: Boolean,
    windowStyle: String,
    forceOpaque: Boolean = false,
): Palette {
    val material = !forceOpaque && windowStyle != "None"
    if (!material) return if (dark) darkSolid else lightSolid
    if (dark) return darkMaterial
    return if (windowStyle.startsWith("WindowsMica")) lightMica else lightAcrylic
}

// ── 字号阶梯 ─────────────────────────────────────────────────────────────────

/**
 * 全项目唯一字号来源。**界面上任何文字都必须从这里取值，不要写 `12.sp` 之类字面量。**
 *
 * | 档位 | 字号 | 用途 |
 * | --- | --- | --- |
 * | [display] | 28 | 页面主标题（「音乐标签工作台」这类） |
 * | [title] | 20 | 区块标题、详情页标题、面板标题 |
 * | [heading] | 16 | 子区块标题、弹层标题 |
 * | [body] | 14 | 正文、主要说明 |
 * | [label] | 13 | 列表项主文本、输入框的值、按钮文字 |
 * | [caption] | 12 | 辅助说明、**字段标签**、次要状态 |
 * | [micro] | 11 | **下限**：颊标、计数、极简状态词 |
 *
 * 约定：
 * 1. **11sp 是下限**，界面上不再出现 8/9/10sp 的真实文本——那在 100% 缩放下已经发虚。
 * 2. **标签不得小于它标注的内容**。字段标签用 [caption]，值用 [label] 或 [body]；
 *    反过来的层级（标签 9sp 而值 12sp）会让整块看起来糊在一起。
 * 3. 图标字形（`♪` `☺` `✕` `›`）属于装饰，不计入文字阶梯，见 [Glyph]。
 * 4. 侧栏 / 面板这类窄栏同样适用本阶梯：窄不等于要缩小字号，靠留白与分隔来省空间。
 */
object T {
    val display = 28.sp
    val title = 20.sp
    val heading = 16.sp
    val body = 14.sp
    val label = 13.sp
    val caption = 12.sp

    /**
     * 最小字号，**与 [caption] 同为 12sp**。
     *
     * 原来是 11sp：在 1x 缩放下明显偏小，侧栏/说明类文字尤其吃力。
     * 12sp 是桌面 UI 可读下限，统一后全项目不再有「小一号」的区块。
     */
    val micro = 12.sp
}

/**
 * 装饰性字形字号。与 [T] 分开，避免被当成正文尺寸误用。
 *
 * 这些是单个符号（音符、笑脸、箭头、对勾），不承载文字信息，
 * 尺寸按视觉重量定，不受正文下限约束。
 */
object Glyph {
    val inline = 12.sp
    val small = 13.sp
    val medium = 16.sp
    val large = 18.sp
    val hero = 22.sp
    val huge = 36.sp
    val display = 44.sp
}

// ── 文本 ─────────────────────────────────────────────────────────────────────

/**
 * 统一文本组件。始终走 BasicText + 纯 String：
 * 折行由纯文本完成，不依赖富文本标签（其小数号取整会让同列字号不一致）。
 */
@Composable
fun Txt(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = T.body,
    color: Color = Salt.text,
    weight: FontWeight = FontWeight.Normal,
    family: FontFamily? = null,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style =
            TextStyle(
                color = color,
                fontSize = size,
                fontWeight = weight,
                fontFamily = family ?: Host.sans,
                textAlign = align ?: TextAlign.Unspecified,
                lineHeight = lineHeight,
            ),
        maxLines = maxLines,
        overflow = overflow,
    )
}

/** 时间码 / 数值：等宽字体，保证纵向对齐。 */
@Composable
fun Mono(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = T.caption,
    color: Color = Salt.textSecondary,
    weight: FontWeight = FontWeight.Normal,
    align: TextAlign? = null,
    /** 行数上限。默认 1（单行省略）；需要换行的地方传 [Int.MAX_VALUE]。 */
    maxLines: Int = 1,
) = Txt(
    text = text,
    modifier = modifier,
    size = size,
    color = color,
    weight = weight,
    family = Host.mono,
    maxLines = maxLines,
    align = align,
)

// ── 容器 ─────────────────────────────────────────────────────────────────────

/** 自绘圆角底色：先裁形再铺色，四角不会露出直角。 */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    color: Color = Salt.card,
    radius: Dp = 14.dp,
    outline: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier =
            modifier
                .clip(shape)
                .background(color)
                .then(if (outline != null) Modifier.border(0.5.dp, outline, shape) else Modifier),
        content = content,
    )
}

/**
 * 居中弹窗容器：**没有压暗遮罩、没有光波/发光/阴影**。
 *
 * 用户明确要求这类弹窗保持「干净」：不铺半透明黑底、不加动画。
 * 点空白处关闭由 [dismissOnOutside] 控制。
 */
@Composable
fun PanelDialog(
    onDismiss: () -> Unit,
    width: Dp = 520.dp,
    maxHeight: Dp = 560.dp,
    dismissOnOutside: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = Salt
    // **弹窗取浮层实色配方**（材质下 90% 不透明，见 [Palette.overlaySolid]）。
    //
    // 半透明卡片在 Win11 材质下会与背后的插件页面内容重叠（文字互相穿透）；
    // 背后的内容是插件自己的组合树，无法走「宿主隐身」那条路（那是整棵树全有或全无）。
    // 取 darkSolid/lightSolid 的现成规范色最干净：卡片与遮底都用它们。
    val solid = palette.overlaySolid
    // 把 LocalPalette 切到浮层配方：弹窗内所有 Txt/Mono/组件自动取
    // 该配方的规范色，调用方无需逐个改颜色
    CompositionLocalProvider(LocalPalette provides solid) {
        // **纯 Box 铺满 + 居中**，与原始日志弹窗的实现一致（那次是居中的）。
        //
        // 为什么不用 Popup：Popup 的坐标原点/布局约束与「直接铺一层 Box」不同，
        // 实测会让弹窗偏左。原始实现就是 Box，行为已验证正确，回退到它。
        //
        // **调用约定**：请在页面根级调用（父容器已 fillMaxSize），
        // 这样 Box 才能覆盖整页。嵌在表单小容器里调用会只填满那个容器。
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    // 不垫遮底：卡片本身实色已足够挡住它背后那一块；
                    // 垫全屏底会把整个页面盖住，弹窗周围的内容也被遮掉。
                    .clickable(enabled = dismissOnOutside, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                modifier =
                    Modifier
                        .width(width)
                        .heightIn(max = maxHeight)
                        // 拦截卡片内的点击，避免穿透到外面触发关闭
                        .clickable(enabled = false) {},
                color = solid.cardRaised,
                radius = 16.dp,
                outline = solid.borderStrong,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content,
                )
            }
        }
    }
}

// ── 交互件 ───────────────────────────────────────────────────────────────────

@Composable
fun TapChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Salt.accent,
    enabled: Boolean = true,
) {
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(9.dp))
                .background(if (enabled) accent.copy(alpha = 0.14f) else Salt.hover)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(
            text = label,
            size = T.label,
            color = if (enabled) accent else Salt.textTertiary,
            weight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/**
 * 行内可编辑输入框。
 * 回车或失焦时提交；值未变化不触发回调，避免无意义的写盘。
 */
@Composable
fun FieldBox(
    value: String,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    size: TextUnit = T.body,
    readOnly: Boolean = false,
) {
    val pal = Salt
    var draft by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }

    fun commit() {
        if (draft != value) onCommit(draft.trim())
    }

    BasicTextField(
        value = draft,
        onValueChange = { if (!readOnly) draft = it },
        modifier =
            modifier
                .clip(RoundedCornerShape(9.dp))
                .background(if (focused) pal.cardRaised else pal.hover)
                .border(
                    width = 0.5.dp,
                    color = if (focused) pal.accent.copy(alpha = 0.6f) else Color.Transparent,
                    shape = RoundedCornerShape(9.dp),
                ).onFocusChanged { state ->
                    if (focused && !state.isFocused) commit()
                    focused = state.isFocused
                }.padding(horizontal = 10.dp, vertical = 8.dp),
        textStyle =
            TextStyle(
                color = if (readOnly) pal.textSecondary else pal.text,
                fontSize = size,
                fontFamily = Host.sans,
            ),
        cursorBrush = SolidColor(pal.accent),
        singleLine = singleLine,
        readOnly = readOnly,
        keyboardOptions =
            KeyboardOptions(
                imeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
            ),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (draft.isEmpty()) {
                    Txt(text = placeholder, size = size, color = pal.textTertiary, maxLines = 1)
                }
                inner()
            }
        },
    )
}

/** 空态占位：图标 + 主句 + 副句，避免用户面对一片空白。 */
@Composable
fun EmptyState(
    glyph: String,
    title: String,
    hint: String? = null,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Txt(text = glyph, size = Glyph.huge, color = Salt.textTertiary, maxLines = 1)
            androidx.compose.foundation.layout
                .Spacer(Modifier.padding(top = 8.dp))
            Txt(text = title, size = T.body, color = Salt.textSecondary, maxLines = 2)
            if (hint != null) {
                androidx.compose.foundation.layout
                    .Spacer(Modifier.padding(top = 4.dp))
                Txt(text = hint, size = T.caption, color = Salt.textTertiary, maxLines = 2)
            }
        }
    }
}

// ── 数值格式 ─────────────────────────────────────────────────────────────────

fun fmtTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

fun fmtTimeFine(ms: Long): String {
    if (ms < 0L) return "0:00.00"
    val totalSec = ms / 1000
    return "%d:%02d.%02d".format(totalSec / 60, totalSec % 60, (ms % 1000) / 10)
}

/**
 * 弹出菜单。
 *
 * 位置由 [PopupPositionProvider] 精确计算：横向对齐锚点（按钮）左边缘，
 * 纵向从锚点**下边界**开始，再留 6dp 间距；超出窗口时向内收。
 * 这样菜单绝不会盖住触发它的按钮。
 */
@Composable
fun MenuPopup(
    expanded: Boolean,
    onDismiss: () -> Unit,
    width: androidx.compose.ui.unit.Dp = 230.dp,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    if (!expanded) return
    val density = androidx.compose.ui.platform.LocalDensity.current
    val gapPx = with(density) { 6.dp.roundToPx() }

    androidx.compose.ui.window.Popup(
        popupPositionProvider =
            remember(gapPx) {
                object : androidx.compose.ui.window.PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: androidx.compose.ui.unit.IntRect,
                        windowSize: androidx.compose.ui.unit.IntSize,
                        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                        popupContentSize: androidx.compose.ui.unit.IntSize,
                    ): androidx.compose.ui.unit.IntOffset {
                        val x =
                            anchorBounds.left
                                .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                        val preferredY = anchorBounds.bottom + gapPx
                        val y =
                            if (preferredY + popupContentSize.height <= windowSize.height) {
                                preferredY
                            } else {
                                // 下方放不下就翻到按钮上方
                                (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(0)
                            }
                        return androidx.compose.ui.unit
                            .IntOffset(x, y)
                    }
                }
            },
        onDismissRequest = onDismiss,
        properties =
            androidx.compose.ui.window.PopupProperties(
                focusable = true,
                dismissOnClickOutside = true,
            ),
    ) {
        Card(color = Salt.overlaySolid.cardOverlay, radius = 11.dp, outline = Salt.border) {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.width(width).padding(5.dp),
                content = content,
            )
        }
    }
}

/** 菜单行的上下内边距；[MenuRow] 与 [menuRowHeight] 共用，改一处即可。 */
val MenuRowPadding = 6.dp

/**
 * 一行菜单的高度上限：文本行高加 [MenuRowPadding] 的上下内边距。
 *
 * 字体由宿主决定，所以实测文本行高而不是写死 dp。取值以菜单里最高的那一行为准
 * （标签用 [T.label]，比 12sp 的对勾字形 [Glyph.inline] 高），因此是上限：
 * 乘以行数得到的容器高度不会把下一行露出来。需要按「可见行数」限制高度的调用方
 * （例如曲库筛选菜单最多 8 行）用本值乘行数即可。
 */
@Composable
fun menuRowHeight(): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return with(density) {
        measurer
            .measure(
                text = "测",
                style = TextStyle(fontSize = T.label, fontFamily = Host.sans),
            ).size.height
            .toDp() + MenuRowPadding * 2
    }
}

/**
 * 菜单里的选项区：最多显示 [maxRows] 行，其余滚动。
 *
 * 高度上限按**实际行数**收窄，而不是直接乘 [maxRows]：滚动容器在被限定的高度里
 * 可能选择撑满而不是按内容收缩，只乘 [maxRows] 会让选项不足的菜单留出一片空白。
 * 行数为 0 时不组合任何内容，菜单里不会多出一个空块。
 */
@Composable
fun MenuOptions(
    count: Int,
    maxRows: Int,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    if (count <= 0) return
    val rowHeight = menuRowHeight()
    LazyColumn(
        modifier = modifier.fillMaxWidth().heightIn(max = rowHeight * count.coerceAtMost(maxRows)),
        content = content,
    )
}

/** 菜单里的一行：可勾选，点击回调。 */
@Composable
fun MenuRow(
    label: String,
    checked: Boolean = false,
    showCheck: Boolean = true,
    accent: androidx.compose.ui.graphics.Color? = null,
    onClick: () -> Unit,
) {
    val palette = Salt
    var hovered by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.foundation.layout.Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(7.dp))
                .background(if (hovered) palette.hover else androidx.compose.ui.graphics.Color.Transparent)
                .clickable(onClick = onClick)
                .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Enter) { hovered = true }
                .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Exit) { hovered = false }
                .padding(horizontal = 8.dp, vertical = MenuRowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCheck) {
            Txt(
                text = if (checked) "✓" else " ",
                size = Glyph.inline,
                color = accent ?: if (checked) palette.accent else palette.textTertiary,
                maxLines = 1,
                modifier = Modifier.width(14.dp),
            )
        }
        Txt(label, size = T.label, color = accent ?: palette.textSecondary, maxLines = 1)
    }
}
