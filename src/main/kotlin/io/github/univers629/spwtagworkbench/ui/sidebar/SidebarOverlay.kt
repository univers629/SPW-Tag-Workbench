// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.ui.sidebar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.univers629.spwtagworkbench.backend.model.TagField
import io.github.univers629.spwtagworkbench.core.Card
import io.github.univers629.spwtagworkbench.core.Diag
import io.github.univers629.spwtagworkbench.core.FieldBox
import io.github.univers629.spwtagworkbench.core.Glyph
import io.github.univers629.spwtagworkbench.core.LocalPalette
import io.github.univers629.spwtagworkbench.core.Salt
import io.github.univers629.spwtagworkbench.core.Settings
import io.github.univers629.spwtagworkbench.core.T
import io.github.univers629.spwtagworkbench.core.Txt
import io.github.univers629.spwtagworkbench.core.fmtTime
import io.github.univers629.spwtagworkbench.core.i18n.L10n
import io.github.univers629.spwtagworkbench.core.sidebarSolid
import io.github.univers629.spwtagworkbench.data.Drafts
import io.github.univers629.spwtagworkbench.data.Repo
import io.github.univers629.spwtagworkbench.data.Status
import io.github.univers629.spwtagworkbench.data.Tags
import io.github.univers629.spwtagworkbench.data.TrackMeta
import io.github.univers629.spwtagworkbench.router.SharedKey
import io.github.univers629.spwtagworkbench.router.ShellState
import io.github.univers629.spwtagworkbench.router.SpaceMetrics
import io.github.univers629.spwtagworkbench.router.SpacePage
import io.github.univers629.spwtagworkbench.router.SpaceRouter
import io.github.univers629.spwtagworkbench.ui.DraggableFab
import io.github.univers629.spwtagworkbench.ui.NoIndication
import io.github.univers629.spwtagworkbench.ui.PanelEase
import io.github.univers629.spwtagworkbench.ui.PanelMs
import io.github.univers629.spwtagworkbench.ui.icons.IconChevronRight
import io.github.univers629.spwtagworkbench.ui.rememberHostPalette
import io.github.univers629.spwtagworkbench.ui.space.CoverThumb

/**
 * 侧栏自身的开合。只控制这一个面板，不控制任何页面显隐。
 *
 * 可见性从 `private` 放开到 `internal`：顶部标题栏的召唤按钮也要能开合它
 * （下拉里选「全局侧边栏」），否则两条入口会各自维护一份状态。
 */
internal object SidebarUi {
    var open by mutableStateOf(false)

    /** 供外部入口（标题栏召唤按钮）开合侧栏，语义与悬浮按钮一致。 */
    fun toggle() {
        open = !open
    }

    fun close() {
        open = false
    }
}

/**
 * 侧栏当前挂在哪一层。
 *
 * - [TOP]：宿主顶层（guide §9.2 层级 A），画在底栏与玻璃浮层之上 —— 由
 *   `MainPlugin.installTopLevelSidebar` 注册成功后设置
 * - [PAGE]：应用页层（层级 B），随 [io.github.univers629.spwtagworkbench.ui.Shell.PluginOverlay]
 *   渲染 —— 顶层注册失败时的退回位置
 *
 * 渲染侧据此二选一：[SidebarLayer]（顶层入口）只在 TOP 时画，
 * [SidebarHost]（页层入口）只在非 TOP 时画，避免双份。
 */
enum class SidebarLayerLocation { TOP, PAGE }

/** 侧栏当前所在层级（由 MainPlugin 注册结果决定）。 */
object SidebarLayerState {
    var location: SidebarLayerLocation = SidebarLayerLocation.PAGE
}

private val PanelWidth = 340.dp
private val PanelShape = RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp)

/**
 * 层级 A（顶层）的标题栏安全高度（guide §9.16）。
 *
 * 宿主自绘标题栏实测约 44dp：右上角是窗口按钮区，左侧是功能按钮与拖动区。
 * 面板内容从这里之下开始，标题行不再被窗口按钮遮挡；面板背景仍从 0 铺满。
 */
private val TITLEBAR_SAFE_TOP = 44.dp

/**
 * 顶层（层级 A）渲染入口。
 *
 * guide §9.2 层级图：
 * ```
 * MainSurfaceContent
 *   ├ AppNavigation        ← 层级 B：当前 PluginOverlay 挂这里，会被底栏盖住
 *   ├ PlayerSurfaceGlass   ← 玻璃浮层
 *   └ PlayerBottomSheet    ← 播放条底栏
 * ```
 * 层级 A 画在以上全部之后 → 侧栏与 FAB 盖住底栏，不再被遮挡。
 *
 * 注意 guide §9.15 的**顶部安全区**：全局层从窗口 y=0 开始，
 * 顶部一段是宿主标题栏（窗口按钮 + 拖动区）。侧栏是右侧竖条、
 * FAB 在垂直居中附近，均不触顶，无需额外让位。
 */
@Composable
fun SidebarLayer() {
    if (SidebarLayerState.location != SidebarLayerLocation.TOP) return
    // 层级 A 挂在宿主自己的 composition 里，**拿不到** PluginOverlay 提供的
    // LocalPalette：不在这层重新提供的话，[Salt] 会退回默认的 darkSolid
    // （material = false），于是材质模式下 FAB 与面板反而全是不透明色。
    val palette = rememberHostPalette()
    CompositionLocalProvider(
        LocalPalette provides palette,
        // 与 PluginOverlay 保持一致：不画点击波纹
        androidx.compose.foundation.LocalIndication provides NoIndication,
    ) {
        SidebarContent()
    }
}

/**
 * 应用页层（层级 B）渲染入口 —— 顶层注册失败时的退回位置。
 *
 * 顶层挂载成功时（[SidebarHost.layer] == [SidebarLayerLocation.TOP]），
 * 侧栏由 [SidebarLayer] 在层级 A 渲染，这里直接跳过，避免双份。
 *
 * 形态：**上下到顶的右侧面板**，紧贴窗口右边缘，左侧两角圆角、右侧直角；
 * 半透明底 + 发丝描边，透出宿主材质；没有全屏遮罩 —— 它是侧边栏，不是模态框。
 *
 * 平时完全不存在，只有一颗悬浮按钮（FAB）在召唤。面板从右边缘向左滑出。
 */
@Composable
fun SidebarHost() {
    if (SidebarLayerState.location == SidebarLayerLocation.TOP) return
    // 层级 B 在 PluginOverlay 子树内，色板与波纹已在外面提供好
    SidebarContent()
}

/** 两个层级共用的实际内容。 */
@Composable
private fun SidebarContent() {
    if (!Settings.sidebarRail) return

    val palette = Salt
    val open = SidebarUi.open

    // 打开面板时若还没封面，立即补一次（不依赖之前是否加载成功）
    androidx.compose.runtime.LaunchedEffect(open) {
        if (open) {
            val meta = if (Repo.track.isPresent) Repo.track else Repo.focused()
            if (Repo.cover == null && meta.path.isNotBlank()) Repo.loadCovers(meta)
        }
    }
    // 宽视图展开过程中侧栏本体淡出，不跟着缩放
    val squeezed = 1f - ShellState.progress.coerceIn(0f, 1f)

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = open,
            // 进出完全对称：同时长、同曲线
            enter =
                slideInHorizontally(
                    animationSpec = tween(PanelMs, easing = PanelEase),
                    initialOffsetX = { it },
                ) + fadeIn(tween(PanelMs, easing = PanelEase)),
            exit =
                slideOutHorizontally(
                    animationSpec = tween(PanelMs, easing = PanelEase),
                    targetOffsetX = { it },
                ) + fadeOut(tween(PanelMs, easing = PanelEase)),
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight(),
        ) {
            SidebarPanel(
                onClose = { SidebarUi.open = false },
                modifier =
                    Modifier
                        .width(PanelWidth)
                        .fillMaxHeight()
                        .graphicsLayer { alpha = squeezed },
            )
        }

        // 悬浮召唤按钮：默认停在窗口右侧垂直居中，可上下拖动，
        // 静置后自动贴边半隐，鼠标移过去再滑出来。
        DraggableFab(
            icon = { color ->
                io.github.univers629.spwtagworkbench.ui.icons.IconMusicTag(
                    color = color,
                    modifier = androidx.compose.ui.Modifier
                        .size(24.dp),
                )
            },
            expanded = open,
            onClick = { SidebarUi.open = !open },
        )
    }
}

@Composable
private fun SidebarPanel(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // **侧栏面板取浮层配方、材质下压到 75%**（[Palette.sidebarSolid]，
    // 与悬浮按钮同一档不透明度；弹窗/下拉仍是 90%）。
    //
    // 半透明底在 Win11 材质下与背后内容重叠，所以用浮层那套校准过的实色底，
    // 只是把不透明度从 90% 降到 75%，让材质透出来一些。
    val palette = Salt.sidebarSolid
    val track = Repo.track
    val original = if (track.isPresent) track else Repo.focused()
    // 预览制：显示草稿合成后的值
    val meta = Drafts.merge(original)
    // 有未保存改动的字段：与主视图一样在标签前加圆点
    val changed = Drafts
        .draftFor(meta.key)
        ?.fields
        ?.keys
        .orEmpty()

    Box(
        modifier =
            modifier
                .clip(PanelShape)
                .background(palette.cardOverlay)
                .border(0.5.dp, palette.border, PanelShape),
    ) {
        Column(Modifier.fillMaxSize()) {
            // **顶部安全区（guide §9.16）**：层级 A 从窗口 y=0 开始铺，最上面
            // 44dp 是宿主标题栏（窗口按钮 + 拖动区）。底色照常铺满，
            // 内容从安全区之下开始，这一段不参与点击，指针留给窗口拖动。
            Spacer(Modifier.height(TITLEBAR_SAFE_TOP))

            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 16.dp),
            ) {
                if (!meta.isPresent) {
                    Spacer(Modifier.height(20.dp))
                    Txt(
                        text = io.github.univers629.spwtagworkbench.core.i18n.L10n
                            .t("sidebar.empty"),
                        size = T.caption,
                        color = palette.textTertiary,
                        maxLines = 3,
                    )
                    return@Column
                }

                // 这张卡片是进入工作台动画的锚点
                AnchorCard(meta)

                // 打开工作台：紧贴音乐卡片下方
                Spacer(Modifier.height(10.dp))
                OpenWorkbenchButton(onClose)

                // ── 标签区：与主视图元数据同一套排版 ──────────────────────────
                // 回放增益由一键测量产生，单独置顶，与可手填字段分离。
                Spacer(Modifier.height(14.dp))
                PairRow(
                    first = Triple(L10n.tagLabel(TagField.ReplayGainTrackGain), meta.replayGainTrackGain, Tags.Key.TrackGain),
                    second = Triple(L10n.tagLabel(TagField.ReplayGainTrackPeak), meta.replayGainTrackPeak, Tags.Key.TrackPeak),
                    editable = meta.hasFile,
                    firstChanged = TagField.ReplayGainTrackGain in changed,
                    secondChanged = TagField.ReplayGainTrackPeak in changed,
                )
                PairRow(
                    first = Triple(L10n.tagLabel(TagField.ReplayGainAlbumGain), meta.replayGainAlbumGain, Tags.Key.AlbumGain),
                    second = Triple(L10n.tagLabel(TagField.ReplayGainAlbumPeak), meta.replayGainAlbumPeak, Tags.Key.AlbumPeak),
                    editable = meta.hasFile,
                    firstChanged = TagField.ReplayGainAlbumGain in changed,
                    secondChanged = TagField.ReplayGainAlbumPeak in changed,
                )
                Spacer(Modifier.height(6.dp))

                MetaField(L10n.tagLabel(TagField.Title), meta.title, Tags.Key.Title, meta.hasFile, TagField.Title in changed)
                MetaField(L10n.tagLabel(TagField.Artist), meta.artist, Tags.Key.Artist, meta.hasFile, TagField.Artist in changed)
                MetaField(L10n.tagLabel(TagField.Album), meta.album, Tags.Key.Album, meta.hasFile, TagField.Album in changed)
                MetaField(L10n.tagLabel(TagField.Year), meta.year, Tags.Key.Year, meta.hasFile, TagField.Year in changed)
                PairRow(
                    first = Triple(L10n.tagLabel(TagField.Track), meta.track, Tags.Key.Track),
                    second = Triple(L10n.tagLabel(TagField.Disc), meta.disc, Tags.Key.Disc),
                    editable = meta.hasFile,
                    firstChanged = TagField.Track in changed,
                    secondChanged = TagField.Disc in changed,
                )
                MetaField(L10n.tagLabel(TagField.Genre), meta.genre, Tags.Key.Genre, meta.hasFile, TagField.Genre in changed)
                MetaField(
                    L10n.tagLabel(TagField.AlbumArtist),
                    meta.albumArtist,
                    Tags.Key.AlbumArtist,
                    meta.hasFile,
                    TagField.AlbumArtist in changed,
                )
                MetaField(L10n.tagLabel(TagField.Composer), meta.composer, Tags.Key.Composer, meta.hasFile, TagField.Composer in changed)
                MetaField(L10n.tagLabel(TagField.Lyricist), meta.lyricist, Tags.Key.Lyricist, meta.hasFile, TagField.Lyricist in changed)
                MetaField(
                    label = L10n.tagLabel(TagField.Comment),
                    value = meta.comment,
                    key = Tags.Key.Comment,
                    editable = meta.hasFile,
                    changed = TagField.Comment in changed,
                    lines = 3,
                )

                Spacer(Modifier.height(10.dp))
                StatusLine()
                Spacer(Modifier.height(6.dp))
            }
        }

        // 关闭按钮浮在音乐卡片右上角：**不占布局**，卡片不被挤压，
        // 也不随内容滚动。
        Box(
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = TITLEBAR_SAFE_TOP + 16.dp, end = 22.dp)
                    .size(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.cardRaised.copy(alpha = 0.92f))
                    .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Txt("✕", size = Glyph.inline, color = palette.textSecondary, maxLines = 1)
        }
    }
}

/**
 * 基础信息卡片 —— 进入工作台动画的视觉锚点。
 * 每帧把整卡与封面 / 标题的屏幕矩形登记下来，切换时从这些矩形长出去。
 */
@Composable
private fun AnchorCard(meta: TrackMeta) {
    val palette = Salt
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned {
                    // 只在量到真实尺寸时登记，避免退场过程把零矩形写进去
                    val r = it.boundsInWindow()
                    if (r.width > 1f && r.height > 1f) SpaceMetrics.putAnchor(r)
                },
        color = palette.cardRaised,
        radius = 14.dp,
        outline = palette.border,
    ) {
        Row(
            // end 多留 22dp：关闭按钮浮在卡片右上角，避免压住文字
            modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 34.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier.onGloballyPositioned {
                        val r = it.boundsInWindow()
                        if (r.width > 1f && r.height > 1f) SpaceMetrics.putAnchorPart(SharedKey.Cover, r)
                    },
            ) {
                CoverThumb(64.dp, radius = 12.dp)
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Box(
                    modifier =
                        Modifier.onGloballyPositioned {
                            val r = it.boundsInWindow()
                            if (r.width > 1f && r.height > 1f) SpaceMetrics.putAnchorPart(SharedKey.Title, r)
                        },
                ) {
                    Txt(
                        text = meta.title.ifBlank { L10n.t("sidebar.unknownTitle") },
                        size = T.label,
                        color = palette.text,
                        weight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Txt(
                    text = meta.artist.ifBlank { L10n.t("sidebar.unknownArtist") },
                    size = T.caption,
                    color = palette.textSecondary,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Txt(
                    text = meta.album.ifBlank { L10n.t("sidebar.unknownAlbum") },
                    size = T.caption,
                    color = palette.textTertiary,
                    maxLines = 1,
                )
                Spacer(Modifier.height(5.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Txt(
                        text = if (Repo.playing) L10n.t("sidebar.playing") else L10n.t("sidebar.paused"),
                        size = T.caption,
                        color = if (Repo.playing) palette.accent else palette.textTertiary,
                        maxLines = 1,
                    )
                    Txt(
                        text = "${fmtTime(Repo.positionMs)} / ${fmtTime(meta.durationMs)}",
                        size = T.caption,
                        color = palette.textTertiary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 与主视图元数据区一致的一行：左标签（86dp）+ 右输入框。 */
@Composable
private fun MetaField(
    label: String,
    value: String,
    key: Tags.Key,
    editable: Boolean,
    changed: Boolean = false,
    lines: Int = 1,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        // 多行框要顶对齐：居中会让标签飘在框的中间
        verticalAlignment = if (lines > 1) Alignment.Top else Alignment.CenterVertically,
    ) {
        MetaLabel(label, changed, lines)
        FieldBox(
            value = value,
            onCommit = { Repo.stageField(key, it) },
            modifier = Modifier.weight(1f),
            placeholder = L10n.t("field.placeholder"),
            singleLine = lines <= 1,
            readOnly = !editable,
            size = T.label,
        )
    }
}

/**
 * 两个短字段并排一行（音轨号 + 碟号、增益 + 峰值）。
 *
 * 与主视图同款排法：第一格只有值，第二格前面带标签，
 * 两个 1~3 位短值各占一整行会把面板拉得过长。
 */
@Composable
private fun PairRow(
    first: Triple<String, String, Tags.Key>,
    second: Triple<String, String, Tags.Key>,
    editable: Boolean,
    firstChanged: Boolean = false,
    secondChanged: Boolean = false,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MetaLabel(first.first, firstChanged)
        FieldBox(
            value = first.second,
            onCommit = { Repo.stageField(first.third, it) },
            modifier = Modifier.weight(1f),
            placeholder = L10n.t("field.placeholder"),
            readOnly = !editable,
            size = T.label,
        )
        Txt(
            text = second.first,
            size = T.caption,
            color = if (secondChanged) Salt.warn else Salt.textTertiary,
            modifier = Modifier.padding(start = 10.dp, end = 8.dp),
            maxLines = 1,
        )
        FieldBox(
            value = second.second,
            onCommit = { Repo.stageField(second.third, it) },
            modifier = Modifier.weight(1f),
            placeholder = L10n.t("field.placeholder"),
            readOnly = !editable,
            size = T.label,
        )
    }
}

/** 行首标签；有未保存改动时前面加一个圆点。宽度与主视图一致（86dp）。 */
@Composable
private fun MetaLabel(
    label: String,
    changed: Boolean,
    lines: Int = 1,
) {
    val palette = Salt
    Txt(
        text = if (changed) "● $label" else label,
        size = T.caption,
        color = if (changed) palette.warn else palette.textTertiary,
        modifier = Modifier.width(86.dp).padding(top = if (lines > 1) 6.dp else 0.dp),
        maxLines = 1,
    )
}

@Composable
private fun StatusLine() {
    val palette = Salt
    val status = Repo.status ?: return
    val color =
        when (status.kind) {
            Status.Kind.Ok -> palette.ok
            Status.Kind.Warn -> palette.warn
            Status.Kind.Error -> palette.error
            Status.Kind.Busy -> palette.accent
        }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier =
                Modifier
                    .size(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color),
        )
        Spacer(Modifier.width(7.dp))
        Txt(status.text, size = T.caption, color = palette.textSecondary, maxLines = 2)
    }
}

@Composable
private fun OpenWorkbenchButton(onClose: () -> Unit) {
    val palette = Salt
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(11.dp))
                .background(palette.accent.copy(alpha = 0.16f))
                .clickable {
                    // 面板开着，AnchorCard 每帧在登记，此刻锚点一定新鲜
                    Diag.log(
                        "open workbench: anchor=" + SpaceMetrics.anchor() +
                            " cover=" + SpaceMetrics.anchorPart(SharedKey.Cover),
                    )
                    onClose()
                    // 焦点对齐到正在播放的那首，保证主视图与侧栏同曲
                    Repo.focusPlaying()
                    SpaceRouter.open(SpacePage.Desk)
                }.padding(horizontal = 15.dp, vertical = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt(L10n.t("sidebar.openWorkbench"), size = T.label, color = palette.accent, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                Txt(
                    text = L10n.t("sidebar.openWorkbenchSub"),
                    size = T.caption,
                    color = palette.textTertiary,
                    maxLines = 1,
                )
            }
            IconChevronRight(color = palette.accent, modifier = Modifier.size(14.dp))
        }
    }
}
