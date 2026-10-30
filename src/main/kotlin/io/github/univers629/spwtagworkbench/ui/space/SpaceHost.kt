// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.ui.space

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.univers629.spwtagworkbench.core.Card
import io.github.univers629.spwtagworkbench.core.PanelDialog
import io.github.univers629.spwtagworkbench.core.Salt
import io.github.univers629.spwtagworkbench.core.StatusToast
import io.github.univers629.spwtagworkbench.core.T
import io.github.univers629.spwtagworkbench.core.TapChip
import io.github.univers629.spwtagworkbench.core.Txt
import io.github.univers629.spwtagworkbench.core.i18n.L10n
import io.github.univers629.spwtagworkbench.data.Repo
import io.github.univers629.spwtagworkbench.data.Tags
import io.github.univers629.spwtagworkbench.router.SharedKey
import io.github.univers629.spwtagworkbench.router.ShellState
import io.github.univers629.spwtagworkbench.router.SpaceMetrics
import io.github.univers629.spwtagworkbench.router.SpacePage
import io.github.univers629.spwtagworkbench.router.SpaceRouter
import io.github.univers629.spwtagworkbench.ui.artist.ArtistPage
import io.github.univers629.spwtagworkbench.ui.cover.CoverPage
import io.github.univers629.spwtagworkbench.ui.icons.IconChevronLeft
import io.github.univers629.spwtagworkbench.ui.icons.IconMusicNote
import io.github.univers629.spwtagworkbench.ui.info.InfoPage
import io.github.univers629.spwtagworkbench.ui.lyrics.LyricsPage
import io.github.univers629.spwtagworkbench.ui.spectrum.SpectrumPage
import io.github.univers629.spwtagworkbench.ui.timeline.TimelinePage
import io.github.univers629.spwtagworkbench.ui.workbench.WorkbenchPage

// ── 动效参数 ─────────────────────────────────────────────────────────────────

/** 慢入慢出：进入用「先快后缓」，返回用「先缓后快」。 */
internal val EnterEase = CubicBezierEasing(0.16f, 0.84f, 0.16f, 1f)
internal val ExitEase = CubicBezierEasing(0.34f, 0f, 0.24f, 1f)
internal const val EnterMs = 420
internal const val ExitMs = 340

// ── 空间画布：一处渲染全部页面，视点移动由进度驱动 ───────────────────────────

@Composable
fun SpaceCanvas() {
    val page = SpaceRouter.page
    val transit = SpaceRouter.transit

    LaunchedEffect(page, transit) {
        if (transit == null) return@LaunchedEffect
        val goingIn = transit.to.isDetail
        val spec =
            tween<Float>(
                durationMillis = if (goingIn) EnterMs else ExitMs,
                easing = if (goingIn) EnterEase else ExitEase,
            )
        SpaceRouter.progress = 0f
        animate(0f, 1f, animationSpec = spec) { value, _ ->
            SpaceRouter.progress = value
        }
        SpaceRouter.finish()
    }

    val p = SpaceRouter.progress

    // 桌面：始终存在。专题页在它之上生长。返回后空间关系仍然成立。
    val deskAlpha =
        when {
            transit == null -> if (page == SpacePage.Desk) 1f else 0f
            transit.to == SpacePage.Desk -> p
            transit.from == SpacePage.Desk -> 1f - p
            else -> 0f
        }

    Box(Modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = deskAlpha
                        // 桌面在专题页生长时轻微收缩并下沉，强化空间纵深
                        val pull =
                            if (transit == null) {
                                0f
                            } else {
                                1f - (if (transit.to == SpacePage.Desk) p else 1f - p)
                            }
                        // 桌面在专题页生长时收缩 6% 并下沉 12dp：与专题页的 3% 缩放形成层级差
                        scaleX = 1f - 0.06f * pull
                        scaleY = 1f - 0.06f * pull
                        translationY = 12.dp.toPx() * pull
                    },
        ) {
            WorkbenchPage()
        }

        // 专题页：切换的两端都在这里，双方同时存在才能做元素保留
        val detailPages =
            buildList {
                transit?.let { t ->
                    if (t.from.isDetail) add(t.from)
                    if (t.to.isDetail) add(t.to)
                } ?: run { if (page.isDetail) add(page) }
            }

        detailPages.distinct().forEach { target ->
            val alpha =
                when {
                    transit == null -> 1f
                    target == transit.to -> p
                    target == transit.from -> 1f - p
                    else -> 0f
                }
            // 切换期间保持两端页面组合（alpha 归零而非卸载）：
            // 卸载再挂载会重跑 LaunchedEffect，导致频谱/检索被重复触发。
            if (transit == null && alpha <= 0.001f) return@forEach

            // 上一页在切换中不再响应交互，避免误触
            val interactive = transit == null || target == transit.to

            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            if (transit == null) {
                                this.alpha = 1f
                            } else {
                                when {
                                    target == transit.to -> {
                                        // 进入：平滑步 alpha（共享元素更早显现），自下方 28dp 上移并微放大
                                        this.alpha = p * p * (3f - 2f * p)
                                        translationY = (1f - p) * 28.dp.toPx()
                                        scaleX = 0.972f + 0.028f * p
                                        scaleY = 0.972f + 0.028f * p
                                        transformOrigin = TransformOrigin.Center
                                    }
                                    target == transit.from -> {
                                        // 退出：上移 16dp 并收缩 3%，alpha 后段才淡出，
                                        // 让封面 / 歌词框在交接前后都保持实体感
                                        this.alpha = (1f - p) * (1f - p) * (3f - 2f * (1f - p))
                                        translationY = p * -16.dp.toPx()
                                        scaleX = 1f - 0.03f * p
                                        scaleY = 1f - 0.03f * p
                                        transformOrigin = TransformOrigin.Center
                                    }
                                    else -> this.alpha = 0f
                                }
                            }
                        },
            ) {
                DetailPage(page = target, interactive = interactive)
            }
        }

        // 全局操作状态提示：桌面与全部专题页共用一条，
        // 「写入草稿」「保存」「匹配」等动作的反馈在哪个页面都看得到。
        StatusToast(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 14.dp),
        )

        // 专辑批量确认弹窗：**放在画布最上层**。
        // 桌面始终在树里、专题页盖在它之上，弹窗放在任一页面里都会被另一页挡住；
        // 这里作为根 Box 的最后一个子项，保证它压在全部页面之上，且只渲染一次。
        AlbumBatchDialog()
    }
}

/**
 * 把当前曲目的专辑级字段套用到同专辑全部曲目的确认弹窗。
 *
 * 入口有两处：主视图的「套用到同专辑」，以及封面匹配页左侧的同名按钮。
 * 两处都只调 [Repo.requestAlbumBatch]，弹窗由这里统一响应。
 */
@Composable
private fun AlbumBatchDialog() {
    if (!Repo.pendingAlbumBatch) return
    val pal = Salt
    val count = Repo.albumBatchCount
    PanelDialog(
        onDismiss = { Repo.dismissAlbumBatch() },
        width = 420.dp,
    ) {
        Txt(L10n.t("dialog.applyToAlbum"), size = T.heading, color = pal.text, maxLines = 1)
        Txt(
            text = L10n.t("dialog.applyToAlbumBody", count),
            size = T.caption,
            color = pal.textSecondary,
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.weight(1f))
            TapChip(
                label = L10n.t("action.cancel"),
                onClick = { Repo.dismissAlbumBatch() },
                accent = pal.textTertiary,
            )
            TapChip(
                label = L10n.t("dialog.writeCount", count),
                onClick = {
                    Repo.dismissAlbumBatch()
                    Repo.writeAlbumBatch(Tags.Key.ALBUM_LEVEL)
                },
                accent = pal.accent,
            )
        }
    }
}

@Composable
private fun DetailPage(
    page: SpacePage,
    interactive: Boolean,
) {
    when (page) {
        SpacePage.Cover -> CoverPage(interactive)
        SpacePage.Artist -> ArtistPage(interactive)
        SpacePage.Lyrics -> LyricsPage(interactive)
        SpacePage.Spectrum -> SpectrumPage(interactive)
        SpacePage.Timeline -> TimelinePage(interactive)
        SpacePage.Info -> InfoPage(interactive)
        SpacePage.Desk -> Unit
    }
}

// ── 专题页统一骨架 ───────────────────────────────────────────────────────────

/**
 * 所有专题页共用一页骨架。封面与标题登记为共享元素：
 * 进入时它们从工作台上各自的位置连续移动到本页位置，
 * 返回时反向收缩归位。移动层按目标尺寸实时变换，全程清晰。
 */
@Composable
internal fun DetailScaffold(
    page: SpacePage,
    label: String,
    interactive: Boolean,
    coverSize: Dp = 68.dp,
    subtitle: String? = null,
    /**
     * 0..1 的进度：非空时顶栏中间区域铺进度条（占满返回键与右侧按钮之间），
     * 否则在该区域居中显示 [subtitle] 文本。
     */
    progress: Float? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val pal = Salt
    val meta = Repo.focused()

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(pal.pageOverlay),
    ) {
        Column(Modifier.fillMaxSize()) {
            // 专题页不显示封面与歌名：只留一个返回按钮（图标，无文字标题）
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(start = 18.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(pal.hover)
                            .clickable(enabled = interactive) {
                                SpaceRouter.navigate(SpacePage.Desk) { Repo.clearStatus() }
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    IconChevronLeft(color = pal.textSecondary, modifier = Modifier.size(14.dp))
                }
                Spacer(Modifier.width(12.dp))
                // 中间槽位占满「返回键」与「右侧按钮」之间的全部空间，两端各留一点呼吸位。
                // 进度条铺满该区域；没有进度时退回 subtitle 文本。源标签统计已不再显示。
                if (progress != null) {
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .padding(horizontal = 6.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(pal.border),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(pal.accent),
                        )
                    }
                } else if (!subtitle.isNullOrBlank()) {
                    Box(
                        modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Txt(subtitle, size = T.caption, color = pal.textSecondary, maxLines = 1)
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.width(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(pal.border),
            )

            Column(Modifier.weight(1f).fillMaxWidth()) { content() }
        }

        if (!interactive) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        },
            )
        }
    }
}

/**
 * 共享元素包装。
 *
 * 位移量 t：1 = 完全位于另一处（另一页 / 侧栏锚点卡片），0 = 完全归位到本处自然位置。
 * 元素按自身目标尺寸真实布局，只用图形层做缩放与平移：
 * 放大过程不是位图拉伸，文字与图形全程清晰，动画期间也不发生重新布局。
 */
@Composable
internal fun SharedHero(
    page: SpacePage,
    key: SharedKey,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var natural by remember { mutableStateOf(Rect.Zero) }
    val transit = SpaceRouter.transit
    val p = SpaceRouter.progress
    val shell = ShellState
    val shellAnimating = shell.open && shell.progress < 0.999f

    // 外壳展开 / 收起：桌面上的共享元素从侧栏锚点卡片里那一份长出来
    val shellOrigin = if (shellAnimating) SpaceMetrics.anchorPart(key) else null

    val t: Float
    val origin: Rect?
    if (shellAnimating) {
        t = 1f - shell.progress
        origin = shellOrigin
    } else {
        t =
            when {
                transit == null -> 0f
                page == transit.to -> 1f - p
                page == transit.from -> p
                else -> 0f
            }
        origin =
            when {
                transit == null -> null
                page == transit.to -> SpaceMetrics.shared(transit.from, key)
                page == transit.from -> SpaceMetrics.shared(transit.to, key)
                else -> null
            }
    }

    // 页面切换期间由专题页那一份承载运动，桌面上的这一份让位，避免重影
    val hidden = transit != null && page == SpacePage.Desk && !shellAnimating

    if (key == SharedKey.Cover && transit != null) {
        io.github.univers629.spwtagworkbench.core.Diag.logOnce(
            "hero-anim-${page.name}-${transit.to.name}",
            "hero: $page 动画 t=" + String.format(java.util.Locale.ROOT, "%.2f", t) +
                " origin=" + origin + " natural=" + natural,
        )
    }

    Box(
        modifier =
            modifier
                .onGloballyPositioned { coords ->
                    natural = coords.boundsInWindow()
                    SpaceMetrics.putShared(page, key, natural)
                    if (key == SharedKey.Cover) {
                        io.github.univers629.spwtagworkbench.core.Diag.logOnce(
                            "hero-register-${page.name}",
                            "hero: 登记 $page/$key = $natural",
                        )
                    }
                }.graphicsLayer {
                    alpha = if (hidden) 0f else 1f
                    if (origin != null && natural.width > 1f && natural.height > 1f && t > 0f) {
                        val sx = origin.width / natural.width
                        val sy = origin.height / natural.height
                        // 方形元素（封面、艺术家图）保留非等比形变；矩形元素（歌词框、标题）
                        // 走等比缩放，否则文字会被横向拉伸。
                        val square = kotlin.math.abs(natural.width - natural.height) <= 1.5f
                        val uniform = ((sx + sy) / 2f)
                        val kx = if (square) sx else uniform
                        val ky = if (square) sy else uniform
                        scaleX = 1f + (kx - 1f) * t
                        scaleY = 1f + (ky - 1f) * t
                        translationX = (origin.center.x - natural.center.x) * t
                        translationY = (origin.center.y - natural.center.y) * t
                        transformOrigin = TransformOrigin.Center
                    }
                },
    ) {
        content()
    }
}

/** 曲目封面缩略图。 */
@Composable
internal fun CoverThumb(
    size: Dp,
    radius: Dp = 12.dp,
) {
    val pal = Salt
    // 只画属于当前聚焦曲目的那张：封面是全局单值，切歌后可能残留上一首
    val image = if (Repo.coverBelongsToFocused()) Repo.cover else null
    Box(
        modifier =
            Modifier
                .size(size)
                .clip(RoundedCornerShape(radius))
                .background(pal.cardRaised),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            IconMusicNote(color = pal.textTertiary, modifier = Modifier.fillMaxSize(0.34f))
        }
    }
}

/** 工作台上可点开专题页的区域。登记自身边界，作为聚焦缩放的起点。 */
@Composable
internal fun SpaceRegion(
    page: SpacePage,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val pal = Salt
    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .onGloballyPositioned {
                    SpaceMetrics.putRegion(SpacePage.Desk, page.origin, it.boundsInWindow())
                }.clickable { SpaceRouter.navigate(page) },
        color = pal.card,
        outline = pal.border,
    ) {
        Column(Modifier.padding(PaddingValues(horizontal = 16.dp, vertical = 14.dp))) {
            content()
        }
    }
}

/** 终极拦截：浮层吃掉的只是子组件没消费的指针，避免点到下面的宿主。 */
internal fun Modifier.consumeAllPointers(): Modifier =
    this.then(
        Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    event.changes.forEach { if (!it.isConsumed) it.consume() }
                }
            }
        },
    )
