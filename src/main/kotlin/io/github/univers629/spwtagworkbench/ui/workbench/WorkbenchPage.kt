// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.ui.workbench

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.univers629.spwtagworkbench.backend.model.TagField
import io.github.univers629.spwtagworkbench.backend.source.RankedCandidate
import io.github.univers629.spwtagworkbench.core.Card
import io.github.univers629.spwtagworkbench.core.Diag
import io.github.univers629.spwtagworkbench.core.FieldBox
import io.github.univers629.spwtagworkbench.core.Host
import io.github.univers629.spwtagworkbench.core.Mono
import io.github.univers629.spwtagworkbench.core.Salt
import io.github.univers629.spwtagworkbench.core.Settings
import io.github.univers629.spwtagworkbench.core.T
import io.github.univers629.spwtagworkbench.core.TapChip
import io.github.univers629.spwtagworkbench.core.Txt
import io.github.univers629.spwtagworkbench.core.i18n.L10n
import io.github.univers629.spwtagworkbench.data.CoverSlot
import io.github.univers629.spwtagworkbench.data.Drafts
import io.github.univers629.spwtagworkbench.data.Repo
import io.github.univers629.spwtagworkbench.data.Tags
import io.github.univers629.spwtagworkbench.data.TrackMeta
import io.github.univers629.spwtagworkbench.router.SharedKey
import io.github.univers629.spwtagworkbench.router.SpacePage
import io.github.univers629.spwtagworkbench.router.SpaceRouter
import io.github.univers629.spwtagworkbench.ui.icons.IconChevronRight
import io.github.univers629.spwtagworkbench.ui.icons.IconMusicNote
import io.github.univers629.spwtagworkbench.ui.icons.IconPlus
import io.github.univers629.spwtagworkbench.ui.space.SharedHero

/**
 * 工作台 = 这个空间的桌面。
 * 左曲库列表默认折叠成窄轨道，右侧详情就是桌面本身；四个专题页从详情里的区域长出来。
 */
@Composable
fun WorkbenchPage() {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Salt.pageOverlay),
    ) {
        DeskDetail(Modifier.fillMaxSize())
        // 专辑批量确认弹窗与状态提示都由 SpaceCanvas 根级统一渲染：
        // 桌面始终在树里，而专题页盖在它之上，留在这里会被专题页挡住。
    }
}

// ── 桌面详情 ─────────────────────────────────────────────────────────────────

@Composable
private fun DeskDetail(modifier: Modifier = Modifier) {
    val meta = Repo.focused()

    // 左 = 封面 + 元数据（上下堆叠，可滚动）
    // 右 = 歌词编辑（占满剩余宽度）
    // 候选检索一律在展开的专题页里做，主界面不发网络请求。
    //
    // 宽度分配要点：左列用**固定宽度**（窄窗口按一半比例缩小），右列用 weight(1f) 吃掉全部余量。
    //
    // 为什么不能给左列 weight(1f, fill=false)：
    //   fill=false 的意思是「按比例分到位置，但不强制填满」。
    //   左列被 widthIn 封顶到 380dp 后，那份多余空间不会转给右列，
    //   而是留成 Row 末尾的空隙，右侧空出一大截。
    //
    // 为什么不用 weight 而用 BoxWithConstraints 算宽度：
    //   封面是 1:1 正方形，全屏时若左右各占一半，封面会撑到上千像素、
    //   把元数据整块顶出视口。这里把左列宽度钳到 380dp 上限，
    //   同时保留窄窗口下退化成「占一半」的自适应。
    BoxWithConstraints(modifier.fillMaxSize()) {
        val leftWidth = minOf(LEFT_COLUMN_MAX_WIDTH, maxWidth * 0.5f)
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    // 底部与其它专题页对齐（歌词页也是 10dp）。
                    // 原来这里用 24dp，而 Shell 已经为宿主播放条预留下方空间，
                    // 两层叠加后主视图比别的页明显高一截。
                    .padding(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier =
                    Modifier
                        .width(leftWidth)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CoverPane(meta, Modifier.fillMaxWidth())
                MetadataCard(meta, Modifier.fillMaxWidth())
            }

            // 剩余宽度全部归歌词编辑卡，不再留空
            LyricsEditorCard(meta, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/**
 * 主视图左列（封面 + 元数据）的最大宽度。
 *
 * 380dp 的依据：封面是 1:1，这个宽度下封面约 380px 高，
 * 加上信息行与元数据卡片刚好能在一屏内看全；
 * 再宽封面就会把元数据顶出视口，再窄元数据的字段又容易折行。
 */
private val LEFT_COLUMN_MAX_WIDTH = 380.dp

@Composable
private fun CoverPane(
    meta: TrackMeta,
    modifier: Modifier = Modifier,
) {
    val pal = Salt
    // 只认属于当前聚焦曲目的那张：封面是全局单值，异步加载会残留上一首的图
    val hasCover = Repo.coverBelongsToFocused()
    // 每次封面状态变化都记一笔：证明重组真的发生了
    LaunchedEffect(hasCover) {
        Diag.log("cover-pane: hasCover=$hasCover")
    }
    Diag.logOnce(
        "cover-pane-first",
        "CoverPane first compose meta=${meta.title.take(16)} hasFile=${meta.hasFile} " +
            "cover=$hasCover coverLoading=${Repo.coverLoading}",
    )
    Card(modifier = modifier, color = pal.card, outline = pal.border) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── 大图：专辑封面（type=3，回退到第一张）──
            // 点一下进「封面」匹配页，与下方艺术家小槽的行为一致。
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { SpaceRouter.navigate(SpacePage.Cover) }
                        .onGloballyPositioned {
                            Diag.logOnce("cover-box-size", "CoverPane 封面框尺寸=${it.size}")
                        },
                contentAlignment = Alignment.Center,
            ) {
                SharedHero(SpacePage.Desk, SharedKey.Cover, Modifier.fillMaxSize()) {
                    val image = if (Repo.coverBelongsToFocused()) Repo.cover else null
                    if (image != null) {
                        Image(
                            bitmap = image,
                            contentDescription = L10n.t("workbench.cover"),
                            contentScale = ContentScale.Fit,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(12.dp)),
                        )
                    } else {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(pal.cardRaised),
                            contentAlignment = Alignment.Center,
                        ) {
                            IconMusicNote(color = pal.textTertiary, modifier = Modifier.fillMaxSize(0.34f))
                        }
                    }
                }
            }

            // ── 大图的信息行：分辨率 + 字节数 ──
            Spacer(Modifier.height(6.dp))
            Mono(
                text = primaryInfo(),
                size = T.micro,
                color = pal.textTertiary,
            )

            // ── 下方两个槽：艺术家类图片，点一下进「艺术家图」匹配页 ──
            // 可在插件设置里关掉这一区，关掉后整块不占位。
            if (Settings.showArtistSlots) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val slots = Repo.artistSlots
                    repeat(2) { i ->
                        ArtistSlot(
                            slot = slots.getOrNull(i),
                            modifier = Modifier.weight(1f),
                            // 两个槽位都写 type=8：点哪个编辑哪个，互不影响
                            onClick = {
                                Repo.selectArtistSlot(i)
                                SpaceRouter.navigate(SpacePage.Artist)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 一个艺术家封面槽。
 *
 * 有图时画图 + 类型标签 + 分辨率信息；无图时显示「无艺术家封面」占位，
 * 不让槽位塌掉 —— 两个槽的尺寸必须稳定，否则布局会跳。
 */
@Composable
private fun ArtistSlot(
    slot: CoverSlot?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val pal = Salt
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(pal.cardRaised)
                    .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            val img = slot?.image
            if (img != null) {
                Image(
                    bitmap = img,
                    contentDescription = slot.label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                IconPlus(color = pal.textTertiary, modifier = Modifier.fillMaxSize(0.3f))
            }
        }
        Spacer(Modifier.height(4.dp))
        Txt(
            text = slot?.label ?: L10n.t("workbench.matchArtistHint"),
            size = T.micro,
            color = if (slot != null) pal.textSecondary else pal.textTertiary,
            maxLines = 1,
        )
        if (slot != null) {
            Mono(
                text = slot.info,
                size = T.caption,
                color = pal.textTertiary,
            )
        }
    }
}

/**
 * 大图的信息文本：分辨率 + 字节数，与 Tag-Workbench 的 `imageInfo` 同格式。
 *
 * 只读**预览**的信息（[Repo.localCoverInfo]）：读本地封面时算好，
 * 点「写入草稿」换成候选图时同步更新 —— 勾选候选不影响它。
 */
@Composable
private fun primaryInfo(): String = if (Repo.coverBelongsToFocused()) Repo.localCoverInfo else L10n.t("workbench.noCover")

/**
 * 歌词编辑框：主界面右栏，占满高度，可直接编辑，改动只进草稿。
 */
@Composable
private fun LyricsEditorCard(
    meta: TrackMeta,
    modifier: Modifier = Modifier,
) {
    val pal = Salt

    // 外部内容：只在「载入新歌词」时作为初值写进编辑框，之后不再回读。
    val current =
        Repo.lyricText.ifBlank {
            Repo.editedLines.ifEmpty { Repo.lines }.joinToString("\n") { it.text }
        }

    // **非受控编辑**：编辑框自己持有全部内容与选区，外部状态不参与每次输入的决策。
    //
    // 参考项目 SPW-Tag-Workbench 用的是 Swing 的 `JTextArea`——用户输入直接进组件内部，
    // 只在离开页面时才读一次 `.text` 交给外部。那是非受控组件，天然不会丢字。
    //
    // 之前这里用受控写法（value 由外部状态驱动，每次 onValueChange 都回写 Repo），
    // 于是「外部值」与「用户输入」在每一帧都在竞争，实测被吃成只剩最后 1-2 个字符
    // （存盘得到 'n'、'ni'）。这里改成：**只有 lyricsEpoch 变化时才覆盖编辑框内容**。
    var field by remember(meta.key) { mutableStateOf(TextFieldValue(current)) }
    LaunchedEffect(meta.key, Repo.lyricsEpoch) {
        // 换歌或重新载入歌词：接受外部内容，光标回到开头
        field = TextFieldValue(current)
    }
    // 用户输入：本地立即生效，同时进草稿。不回写 Repo.lyricText，避免与编辑框竞争。
    val text = field.text

    // 「与原文是否不同」**只用于显示状态点**，不参与任何可见性/写入决策。
    // 保存按钮本身常驻，所以这里算错也不会导致入口消失。
    val changed = text != current

    Card(modifier = modifier, color = pal.card, outline = pal.border) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(L10n.t("workbench.lyricsEditor"), size = T.heading, color = pal.text, maxLines = 1)
                Spacer(Modifier.width(10.dp))
                // 状态点：按「当前编辑内容 vs 原文」判断，只做提示，不控制任何入口
                TapChip(
                    label = if (changed) L10n.t("library.pendingSave") else L10n.t("workbench.synced"),
                    onClick = { },
                    accent = if (changed) pal.warn else pal.textTertiary,
                )
                Spacer(Modifier.weight(1f))
                TapChip(
                    label = L10n.t("workbench.onlineLyrics"),
                    onClick = { SpaceRouter.navigate(SpacePage.Lyrics) },
                    enabled = meta.isPresent,
                )
            }
            Spacer(Modifier.height(8.dp))
            // 编辑区挂共享元素：切到歌词匹配页时这一块会连续飞过去，
            // 与封面从主视图飞进封面匹配页是同一套机制。
            SharedHero(SpacePage.Desk, SharedKey.Lyrics, Modifier.fillMaxWidth().weight(1f)) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .defaultMinSize(minHeight = 150.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(pal.hover)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    BasicTextField(
                        value = field,
                        onValueChange = { value ->
                            // 1) 编辑框状态是唯一真相：连选区一起更新，光标不会跳
                            field = value
                            // 2) **只有内容真的变了才写草稿**。
                            //
                            //    点一下光标、或者输入法开始合成时，Compose 也会回调
                            //    onValueChange，但文本与原文完全一致。若不比较就写入，
                            //    用户「只是点了一下输入框」顶栏的保存就会亮起来。
                            //    这里比较的是「和外部原文是否一致」，不是「和上一帧是否一致」——
                            //    后者会让输入过程参与竞争，正是之前丢字的原因。
                            if (meta.isPresent && value.text != current) {
                                Repo.stageLyricEdit(value.text)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        textStyle =
                            androidx.compose.ui.text.TextStyle(
                                color = pal.text,
                                fontSize = T.body,
                                fontFamily = Host.sans,
                                lineHeight = 20.sp,
                            ),
                        cursorBrush = SolidColor(pal.accent),
                        decorationBox = { inner ->
                            Box {
                                if (text.isEmpty()) {
                                    Txt(
                                        text = L10n.t("workbench.lyricsPlaceholder"),
                                        size = T.body,
                                        color = pal.textTertiary,
                                        maxLines = 1,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MetadataCard(
    original: TrackMeta,
    modifier: Modifier = Modifier,
) {
    val pal = Salt
    // 预览制：显示「原始值 + 草稿覆盖」，本身不落盘
    val meta = Drafts.merge(original)
    val draft = Drafts.draftFor(meta.key)
    val dirtyCount = draft?.fields?.size ?: 0

    Card(
        modifier = Modifier.fillMaxWidth(),
        color = pal.card,
        outline = pal.border,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(L10n.t("workbench.metadata"), size = T.heading, color = pal.text, maxLines = 1)
                if (dirtyCount > 0) {
                    Spacer(Modifier.width(10.dp))
                    TapChip(
                        label = L10n.t("workbench.dirtyCount", dirtyCount),
                        onClick = { Repo.discardCurrentDraft() },
                        accent = pal.warn,
                    )
                }
                Spacer(Modifier.weight(1f))
                // 回放增益在频谱页测量：那边与频谱共用同一次 BASS 解码结果，
                // 所以入口指向频谱页，而不是在元数据区重复跑一遍扫描。
                TapChip(
                    label = L10n.t("workbench.computeReplayGain"),
                    onClick = { SpaceRouter.navigate(SpacePage.Spectrum) },
                    enabled = original.hasFile,
                )
            }
            Spacer(Modifier.height(12.dp))
            // 顺序与标签沿用 legacy 版元数据区：标题 / 艺术家 / 专辑 / 年份 /
            // 音轨号 / 风格 / 专辑艺术家 / 作曲家 / 作词家 / 注释。
            MetaRow(L10n.tagLabel(TagField.Title), meta.title, Tags.Key.Title, original.hasFile, isDirty(TagField.Title))
            MetaRow(L10n.tagLabel(TagField.Artist), meta.artist, Tags.Key.Artist, original.hasFile, isDirty(TagField.Artist))
            MetaRow(L10n.tagLabel(TagField.Album), meta.album, Tags.Key.Album, original.hasFile, isDirty(TagField.Album))
            MetaRow(L10n.tagLabel(TagField.Year), meta.year, Tags.Key.Year, original.hasFile, isDirty(TagField.Year))
            // 音轨号与碟号共用一行（legacy 的排法）：两个都是短数字，各占一整行太浪费
            MetaPairRow(
                first = Triple(L10n.tagLabel(TagField.Track), meta.track, Tags.Key.Track),
                second = Triple(L10n.tagLabel(TagField.Disc), meta.disc, Tags.Key.Disc),
                editable = original.hasFile,
                firstChanged = isDirty(TagField.Track),
                secondChanged = isDirty(TagField.Disc),
            )
            MetaRow(L10n.tagLabel(TagField.Genre), meta.genre, Tags.Key.Genre, original.hasFile, isDirty(TagField.Genre))
            MetaRow(
                L10n.tagLabel(TagField.AlbumArtist),
                meta.albumArtist,
                Tags.Key.AlbumArtist,
                original.hasFile,
                isDirty(TagField.AlbumArtist),
            )
            MetaRow(L10n.tagLabel(TagField.Composer), meta.composer, Tags.Key.Composer, original.hasFile, isDirty(TagField.Composer))
            MetaRow(L10n.tagLabel(TagField.Lyricist), meta.lyricist, Tags.Key.Lyricist, original.hasFile, isDirty(TagField.Lyricist))
            // 注释可能很长，用多行框（legacy 同样给它 TextArea）
            MetaRow(
                label = L10n.tagLabel(TagField.Comment),
                value = meta.comment,
                key = Tags.Key.Comment,
                editable = original.hasFile,
                changed = isDirty(TagField.Comment),
                lines = 3,
            )
            // 回放增益同样是标签字段（REPLAYGAIN_*），与频谱页测量、写入的是同一组键。
            // 四个值两行：音轨一对、专辑一对；数值格式由规范固定（`-9.38 dB` / `0.999900`）。
            MetaPairRow(
                first = Triple(L10n.tagLabel(TagField.ReplayGainTrackGain), meta.replayGainTrackGain, Tags.Key.TrackGain),
                second = Triple(L10n.tagLabel(TagField.ReplayGainTrackPeak), meta.replayGainTrackPeak, Tags.Key.TrackPeak),
                editable = original.hasFile,
                firstChanged = isDirty(TagField.ReplayGainTrackGain),
                secondChanged = isDirty(TagField.ReplayGainTrackPeak),
            )
            MetaPairRow(
                first = Triple(L10n.tagLabel(TagField.ReplayGainAlbumGain), meta.replayGainAlbumGain, Tags.Key.AlbumGain),
                second = Triple(L10n.tagLabel(TagField.ReplayGainAlbumPeak), meta.replayGainAlbumPeak, Tags.Key.AlbumPeak),
                editable = original.hasFile,
                firstChanged = isDirty(TagField.ReplayGainAlbumGain),
                secondChanged = isDirty(TagField.ReplayGainAlbumPeak),
            )

            Spacer(Modifier.height(12.dp))
            BatchRow(original)
        }
    }
}

/**
 * 批量写入：把本曲的专辑级字段套用到同专辑所有曲目。
 * 进度、取消、失败统计都由后端 BatchWriter 提供，这里只负责显示。
 */
@Composable
private fun BatchRow(meta: TrackMeta) {
    val pal = Salt
    val progress by Repo.batchProgress.collectAsState()
    val report = Repo.batchReport

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(
                text = L10n.t("workbench.batch"),
                size = T.caption,
                color = pal.textTertiary,
                modifier = Modifier.width(86.dp),
                maxLines = 1,
            )
            if (progress.done < progress.total || progress.total == 0) {
                TapChip(
                    label = L10n.t("workbench.applyToAlbum"),
                    // 只请求，弹窗由 WorkbenchPage 根级统一渲染（才能整窗居中）
                    onClick = { Repo.requestAlbumBatch() },
                    enabled = meta.hasFile,
                )
            } else {
                TapChip(
                    label = if (progress.finished) {
                        L10n.t(
                            "workbench.writingProgress",
                            progress.done,
                            progress.total,
                        )
                    } else {
                        L10n.t("workbench.writing")
                    },
                    onClick = { },
                    accent = if (progress.failed > 0) pal.warn else pal.ok,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (progress.total > 0 && !progress.finished) {
                TapChip(label = L10n.t("workbench.cancel"), onClick = { Repo.cancelBatch() }, accent = pal.warn)
            }
            Spacer(Modifier.weight(1f))
            if (report != null) {
                Txt(
                    text = report.summary,
                    size = T.micro,
                    color = if (report.ok) pal.ok else pal.warn,
                    maxLines = 1,
                )
            }
        }

        if (progress.total > 0 && !progress.finished) {
            Spacer(Modifier.height(8.dp))
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(pal.hover),
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth(progress.fraction.coerceIn(0f, 1f))
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(pal.accent),
                )
            }
            Spacer(Modifier.height(4.dp))
            Txt(
                text = progress.currentPath.substringAfterLast('\\'),
                size = T.micro,
                color = pal.textTertiary,
                maxLines = 1,
            )
        }

        if (report != null && report.failed.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            report.failed.take(3).forEach { failure ->
                Txt(
                    text = "${failure.path.substringAfterLast('\\')}：${failure.message}",
                    size = T.micro,
                    color = pal.error,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 该字段是否有未保存的草稿改动。 */
private fun isDirty(field: TagField): Boolean = Drafts.draftFor(Repo.focused().key)?.fields?.containsKey(field) == true

@Composable
private fun MetaRow(
    label: String,
    value: String,
    key: Tags.Key,
    editable: Boolean,
    changed: Boolean = false,
    lines: Int = 1,
) {
    val pal = Salt
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
 * 两个短字段并排一行（音轨号 + 碟号）。
 *
 * 沿用 legacy 版的排法：两者都是 1~3 位数字，各占一整行会把元数据区拉得过长。
 * 第一格不显示标签，第二格前面带标签，视觉上就是 `[音轨] 碟号 [碟号]`。
 */
@Composable
private fun MetaPairRow(
    first: Triple<String, String, Tags.Key>,
    second: Triple<String, String, Tags.Key>,
    editable: Boolean,
    firstChanged: Boolean = false,
    secondChanged: Boolean = false,
) {
    val pal = Salt
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
            text = if (secondChanged) "● ${second.second}" else second.first,
            size = T.caption,
            color = if (secondChanged) pal.warn else pal.textTertiary,
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

/** 行首标签；有未保存改动时前面加一个圆点。 */
@Composable
private fun MetaLabel(
    label: String,
    changed: Boolean,
    lines: Int = 1,
) {
    val pal = Salt
    Txt(
        text = if (changed) "● $label" else label,
        size = T.caption,
        color = if (changed) pal.warn else pal.textTertiary,
        modifier = Modifier.width(86.dp).padding(top = if (lines > 1) 6.dp else 0.dp),
        maxLines = 1,
    )
}

@Composable
private fun RegionHeader(
    title: String,
    meta: String,
) {
    val pal = Salt
    Row(verticalAlignment = Alignment.CenterVertically) {
        Txt(title, size = T.heading, color = pal.text, maxLines = 1)
        Spacer(Modifier.width(10.dp))
        Txt(meta, size = T.caption, color = pal.textTertiary, maxLines = 1)
        Spacer(Modifier.weight(1f))
        IconChevronRight(color = pal.textTertiary, modifier = Modifier.size(14.dp))
    }
}

private fun Modifier.drawEnvelope(
    samples: FloatArray?,
    color: Color,
): Modifier =
    this.drawBehind {
        if (samples == null || samples.isEmpty()) return@drawBehind
        val count = samples.size
        val barWidth = size.width / count
        for (i in 0 until count) {
            val h = (size.height * 0.86f * samples[i]).coerceAtLeast(1f)
            drawRect(
                color = color,
                topLeft =
                    androidx.compose.ui.geometry.Offset(
                        x = i * barWidth,
                        y = (size.height - h) / 2f,
                    ),
                size =
                    androidx.compose.ui.geometry
                        .Size(barWidth * 0.72f, h),
            )
        }
    }

@Composable
private fun CandidateRow(ranked: RankedCandidate) {
    val pal = Salt
    val levelLabel =
        when (ranked.level) {
            1 -> L10n.t("workbench.rateExact")
            2 -> L10n.t("workbench.rateCore")
            3 -> L10n.t("workbench.rateSimilar")
            else -> L10n.t("workbench.rateWeak")
        }
    val levelColor =
        when (ranked.level) {
            1, 2 -> pal.ok
            else -> pal.warn
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(pal.cardRaised)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(levelColor.copy(alpha = 0.18f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Txt(levelLabel, size = T.caption, color = levelColor, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Txt(
                text = ranked.candidate.title,
                size = T.label,
                color = pal.text,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Txt(
                text =
                    buildString {
                        append(ranked.candidate.artist.ifBlank { L10n.t("sidebar.unknownArtist") })
                        if (ranked.candidate.album.isNotBlank()) append(" · ").append(ranked.candidate.album)
                    },
                size = T.micro,
                color = pal.textSecondary,
                maxLines = 1,
            )
        }
        Mono(
            text = ranked.allSources.joinToString("+"),
            size = T.caption,
            color = pal.textTertiary,
        )
        Spacer(Modifier.width(8.dp))
        TapChip(label = L10n.t("workbench.useMetadata"), onClick = { Repo.applyCandidate(ranked) })
        if (ranked.candidate.hasLyrics || ranked.allSources.any { it.contains("lrclib") }) {
            Spacer(Modifier.width(4.dp))
            TapChip(label = L10n.t("workbench.takeLyrics"), onClick = { Repo.loadOnlineLyrics(ranked) })
        }
        if (ranked.candidate.hasCover) {
            Spacer(Modifier.width(4.dp))
            TapChip(label = L10n.t("workbench.takeCover"), onClick = { Repo.loadNetworkCovers() })
        }
    }
}
