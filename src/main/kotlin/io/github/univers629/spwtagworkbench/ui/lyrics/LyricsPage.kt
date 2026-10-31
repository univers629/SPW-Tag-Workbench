// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.ui.lyrics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.univers629.spwtagworkbench.backend.source.GroupedCandidate
import io.github.univers629.spwtagworkbench.backend.source.flattenLyricCandidates
import io.github.univers629.spwtagworkbench.core.Card
import io.github.univers629.spwtagworkbench.core.EmptyState
import io.github.univers629.spwtagworkbench.core.Glyph
import io.github.univers629.spwtagworkbench.core.Host
import io.github.univers629.spwtagworkbench.core.MenuOptions
import io.github.univers629.spwtagworkbench.core.MenuPopup
import io.github.univers629.spwtagworkbench.core.MenuRow
import io.github.univers629.spwtagworkbench.core.Salt
import io.github.univers629.spwtagworkbench.core.T
import io.github.univers629.spwtagworkbench.core.TapChip
import io.github.univers629.spwtagworkbench.core.Txt
import io.github.univers629.spwtagworkbench.core.fmtTime
import io.github.univers629.spwtagworkbench.core.i18n.L10n
import io.github.univers629.spwtagworkbench.core.sourceDoneFraction
import io.github.univers629.spwtagworkbench.data.LyricMatch
import io.github.univers629.spwtagworkbench.data.Repo
import io.github.univers629.spwtagworkbench.router.SharedKey
import io.github.univers629.spwtagworkbench.router.SpacePage
import io.github.univers629.spwtagworkbench.ui.icons.IconChevronDown
import io.github.univers629.spwtagworkbench.ui.space.DetailScaffold
import io.github.univers629.spwtagworkbench.ui.space.SharedHero

/**
 * 歌词匹配页。
 *
 * 左侧按源分组罗列网络歌词候选（每源独立，不跨源合并），
 * 右侧是逐行编辑器；点候选 → 取歌词 → 解析 → 载入编辑区 → 进草稿。
 */
@Composable
fun LyricsPage(interactive: Boolean) {
    val palette = Salt
    val lines = Repo.editedLines.ifEmpty { Repo.lines }

    LaunchedEffect(Repo.focused().key) {
        if (Repo.focused().path.isNotBlank()) Repo.searchLyrics()
    }

    DetailScaffold(
        page = SpacePage.Lyrics,
        label = L10n.t("lyrics.title"),
        interactive = interactive,
        progress = sourceDoneFraction(Repo.lyricSourceStates()),
        actions = {
            TapChip(
                label = if (Repo.lyricMatch is LyricMatch.Searching) L10n.t("lyrics.searching") else L10n.t("lyrics.searchAgain"),
                onClick = { Repo.searchLyrics() },
                enabled = interactive,
            )
            TapChip(
                label = L10n.t("action.writeDraft"),
                onClick = { Repo.saveLyricsToDraft() },
                enabled = interactive && lines.isNotEmpty() && Repo.focused().hasFile,
            )
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f)) {
                // 左侧候选列表：与曲库列（LibraryPanel）同一套外壳——
                // 圆角卡片 + cardOverlay 底色 + 0.5dp 描边，
                // 并且「向左留距、向右贴边」：start 留 10dp，end 不留，
                // 于是左边缘空出一条缝隙，右侧紧贴中间的分隔位置。
                Column(
                    modifier =
                        Modifier
                            .width(320.dp)
                            .fillMaxHeight()
                            .padding(start = 10.dp, top = 8.dp, bottom = 10.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(palette.cardOverlay)
                            .border(0.5.dp, palette.border, RoundedCornerShape(14.dp)),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Txt(L10n.t("lyrics.candidates"), size = T.heading, color = palette.text, maxLines = 1)
                        Spacer(Modifier.weight(1f))
                        Txt(
                            text = Repo.lyricSource.ifBlank { "—" },
                            size = T.micro,
                            color = palette.textTertiary,
                            maxLines = 1,
                        )
                    }
                    // 不再画分割线：曲库列也没有，靠标题高度与列表留白区分层次
                    // weight(1f) 是必需的：LyricCandidateList 内部用 fillMaxSize，
                    // 不限制高度它会把下面的设置区整个挤出可视区。
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        LyricCandidateList(interactive)
                    }
                    // 左下角：歌词设置区（对齐参考项目 1.2.0 的 lyricSettingsPanel）
                    LyricSettingsPanel()
                }

                // 中间不再画竖分割线：两侧都是圆角卡片，各自有描边，
                // 再加一条竖线会显得割裂。

                // 右侧歌词区：与主视图的「歌词编辑」是同一个共享元素，
                // 两页切换时这块会连续飞过去（和封面飞进封面匹配页同一机制）。
                SharedHero(
                    SpacePage.Lyrics,
                    SharedKey.Lyrics,
                    Modifier.weight(1f).fillMaxHeight().padding(10.dp),
                ) {
                    Card(
                        modifier = Modifier.fillMaxSize(),
                        color = palette.card,
                        outline = palette.border,
                    ) {
                        if (lines.isEmpty()) {
                            EmptyState(
                                glyph = "♪",
                                title = if (Repo.lyricsLoading) L10n.t("lyrics.loading") else L10n.t("lyrics.empty"),
                                hint = L10n.t("lyrics.emptyHint"),
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            SourceTextEditor(Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricCandidateList(interactive: Boolean) {
    val palette = Salt
    when (val state = Repo.lyricMatch) {
        is LyricMatch.Idle ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Txt(L10n.t("lyrics.autoSearchHint"), size = T.micro, color = palette.textTertiary, maxLines = 1)
            }

        is LyricMatch.Searching ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Txt(L10n.t("lyrics.multiSearching"), size = T.caption, color = palette.textSecondary, maxLines = 1)
            }

        is LyricMatch.Failed ->
            Column(Modifier.fillMaxSize().padding(14.dp)) {
                Txt(L10n.t("lyrics.searchFailed"), size = T.caption, color = palette.error, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Txt(state.reason, size = T.micro, color = palette.textTertiary, maxLines = 4)
            }

        is LyricMatch.Done -> {
            val groups = state.groups
            // 不再按源分组：五个源摊平成一张表，同匹配等级内逐字优先。
            // 每行副标题已带源名，摊平不损失信息；组间顺序（源权重）留作第三层 tie-break。
            val rows =
                flattenLyricCandidates(groups) {
                    Repo.isWordSyncedCandidate(it.candidate.sourceId, it.candidate.remoteId)
                }
            if (rows.isEmpty()) {
                EmptyState(
                    glyph = "♪",
                    title = L10n.t("lyrics.noMatch"),
                    hint = L10n.t("lyrics.noMatchHint"),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // 候选列表要**显式持有滚动状态**：不传 state 时滚动位置不受控，
                // 新一轮搜索结束后会停在上一次翻到的位置，用户得往上翻才看得到
                // 排在最前的候选。这里在结果集变化时滚回顶部。
                val listState = rememberLazyListState()
                // 用「源数 + 候选总数」当信号：只有换了结果集才回顶。
                // 不能用行的顺序当 key：逐字粒度确认后行会重排，滚动位置会被重置。
                val resultKey = groups.size to rows.size
                LaunchedEffect(resultKey) {
                    if (rows.isNotEmpty()) listState.scrollToItem(0)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    // 与曲库列表一致：横向 8 / 纵向 4，行间距由每行自身的 1dp padding 提供
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    // 上限已在数据层截断（Aggregator.MAX_PER_SOURCE，按源），这里不再重复 take
                    items(
                        items = rows,
                        key = { "${it.candidate.sourceId}-${it.candidate.remoteId}" },
                    ) { item ->
                        CandidateRow(
                            item = item,
                            interactive = interactive,
                            onClick = { Repo.chooseGroupedLyrics(item.candidate.sourceId, item.candidate) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(
    item: GroupedCandidate,
    interactive: Boolean,
    onClick: () -> Unit,
) {
    val palette = Salt
    val duration = item.candidate.durationMs
    // 与曲库列表（LibraryPanel.LibraryRow）保持同一套视觉：
    // 圆角 9dp、行间 1dp、选中用 selected 底色、两行文本（标题 + 副标题）。
    //
    // 「逐字」只在**已确认**时标：搜索接口不告诉粒度，没取过词的候选不加标记。
    // 已确认的来源只有两个：点击候选时取得的歌词，以及逐字能力源的粒度补取
    // （见 Repo.inspectWordSyncedCandidates）。
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 1.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Color.Transparent)
                .clickable(enabled = interactive, onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Txt(
                text = item.candidate.title.ifBlank { L10n.t("sidebar.unknownTitle") },
                size = T.label,
                color = palette.textSecondary,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Txt(
                text =
                    buildString {
                        append(item.candidate.sourceLabel)
                        append(" · ")
                        append(item.candidate.artist.ifBlank { L10n.t("sidebar.unknownArtist") })
                        if (item.candidate.album.isNotBlank()) append(" · ").append(item.candidate.album)
                    },
                size = T.micro,
                color = palette.textTertiary,
                maxLines = 1,
            )
        }

        // 「逐字」：已确认才显示，未确认的候选不加标记
        if (Repo.isWordSyncedCandidate(item.candidate.sourceId, item.candidate.remoteId)) {
            Spacer(Modifier.width(8.dp))
            Txt(
                text = L10n.t("lyrics.wordSynced"),
                size = T.micro,
                color = palette.accent,
                maxLines = 1,
            )
        }

        // 右侧：时长（分:秒）。原来这里是「精确/核心/相似/弱」评分，
        // 对挑歌词没有参考价值——用户关心的是时长对不对得上。
        if (duration > 0) {
            Spacer(Modifier.width(8.dp))
            Txt(
                text = fmtTime(duration),
                size = T.micro,
                color = palette.textTertiary,
                maxLines = 1,
            )
        }
    }
}

/**
 * 歌词源文本编辑区：显示文件里的原始歌词，**不做任何解析**。
 *
 * 与主视图右栏的「歌词编辑」是同一份内容、同一个共享元素，
 * 两页切换时这块会连续移动过去。
 *
 * 显示内容**直接来自 [Repo.lyricText]**，不透过草稿：
 * 草稿是「等待写盘的内容」，而 `lyricText` 是「按当前设置渲染出来的显示内容」。
 * 之前先读草稿，导致轨道一过滤就把中间结果钉在草稿里，
 * 之后再怎么改设置界面都读那份旧草稿——看起来就是「点了没反应」。
 */
@Composable
private fun SourceTextEditor(modifier: Modifier) {
    val palette = Salt
    val key = Repo.focused().key
    // 单一真相：Repo.lyricText（由 renderLyrics 从基准算出）
    val current = Repo.lyricText

    // 用户正在输入时才用本地缓冲，避免每个按键都触发整段重算。
    // 外部改动（切格式 / 调偏移 / 切轨道 / 换歌）必须覆盖本地缓冲，
    // 否则编辑过的内容会一直盖住新算出的文本——这正是「点了设置没反应」的原因。
    var editing by remember(key) { mutableStateOf<String?>(null) }
    LaunchedEffect(current) {
        // current 变了说明是外部改动，丢弃本地缓冲
        if (editing != null && editing != current) editing = null
    }
    val text = editing ?: current

    Box(
        modifier =
            modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(palette.hover)
                .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        BasicTextField(
            value = text,
            onValueChange = { value ->
                // 编辑即改基准内容：基准变了，其余设置照常叠加
                editing = value
                if (key.isNotEmpty()) Repo.updateLyricText(value)
            },
            modifier = Modifier.fillMaxSize(),
            textStyle =
                TextStyle(
                    color = palette.text,
                    fontSize = T.body,
                    fontFamily = Host.sans,
                    lineHeight = 20.sp,
                ),
            cursorBrush = SolidColor(palette.accent),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) {
                        Txt(
                            text = L10n.t("lyrics.emptyHint"),
                            size = T.body,
                            color = palette.textTertiary,
                            maxLines = 1,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

/**
 * 歌词设置区：候选列表下方的圆角卡片。
 *
 * 对齐参考项目 1.2.0 的 `lyricSettingsPanel`（两组两列）：
 * - 歌词格式：LRC / SPL / TTML / 纯文本
 * - 偏移量：整首歌词时间轴平移（毫秒）
 * - 转简 / 转繁：中文简繁互换
 * - 原文 / 译文 / 罗马音：按语言类别筛选（逐行/逐字后缀由解析器识别）
 *
 * 所有改动都直接作用于右栏的源文本，改完即进草稿，点保存才写文件。
 */
@Composable
private fun LyricSettingsPanel() {
    val palette = Salt
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(palette.hover)
                .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 第一行：歌词格式下拉 + 偏移量
        // 偏移的「重置」不单独给按钮：把加减调到 0 就是归零，符合直觉。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(L10n.t("lyrics.format"), size = T.micro, color = palette.textTertiary, maxLines = 1)
            Spacer(Modifier.width(6.dp))
            LyricFormatDropdown(
                current = Repo.lyricFormatChoice ?: L10n.t("lyrics.original"),
                onPick = { pick ->
                    if (pick == L10n.t("lyrics.original")) Repo.clearLyricFormat() else Repo.convertLyricFormat(pick)
                },
            )
            Spacer(Modifier.weight(1f))
            Txt(L10n.t("lyrics.offset"), size = T.micro, color = palette.textTertiary, maxLines = 1)
            Spacer(Modifier.width(6.dp))
            // 偏移量是「设置值」而不是对文本的累积修改，所以减到 0 就等于完全没偏移过。
            StepButton("−", enabled = Repo.lyricOffsetMs > -1000) { Repo.shiftLyrics(-100) }
            Spacer(Modifier.width(2.dp))
            // 带单位：只写数字会让人不知道是毫秒还是别的
            Txt(
                text = "${Repo.lyricOffsetMs} ms",
                size = T.micro,
                color = if (Repo.lyricOffsetMs == 0) palette.textTertiary else palette.accent,
                maxLines = 1,
            )
            Spacer(Modifier.width(2.dp))
            StepButton("+", enabled = Repo.lyricOffsetMs < 1000) { Repo.shiftLyrics(100) }
        }
        // 第二行：简繁转换 + 语言轨道筛选（原文 / 译文 / 罗马音）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(L10n.t("lyrics.script"), size = T.micro, color = palette.textTertiary, maxLines = 1)
            Spacer(Modifier.width(6.dp))
            TapChip(label = L10n.t("lyrics.toSimplified"), onClick = { Repo.convertLyricsChinese(false) })
            Spacer(Modifier.width(4.dp))
            TapChip(label = L10n.t("lyrics.toTraditional"), onClick = { Repo.convertLyricsChinese(true) })
            Spacer(Modifier.weight(1f))
            // 语言轨道：同一时间戳的第 1/2/3 条依次是原文/译文/罗马音
            LanguageToggle(L10n.t("lyrics.sourceText"), Repo.lyricShowOriginal) { Repo.setLyricLanguage(0, it) }
            Spacer(Modifier.width(4.dp))
            LanguageToggle(L10n.t("lyrics.translation"), Repo.lyricShowTranslation) { Repo.setLyricLanguage(1, it) }
            Spacer(Modifier.width(4.dp))
            LanguageToggle(L10n.t("lyrics.romanization"), Repo.lyricShowRomanization) { Repo.setLyricLanguage(2, it) }
        }
    }
}

/** 语言轨道开关：选中时 accent 描边，未选时灰字。 */
@Composable
private fun LanguageToggle(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val palette = Salt
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (checked) palette.selected else palette.hover)
                .clickable { onChange(!checked) }
                .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Txt(
            text = label,
            size = T.micro,
            color = if (checked) palette.accent else palette.textTertiary,
            maxLines = 1,
        )
    }
}

/**
 * 歌词格式下拉：与工具栏「标签源」用同一套 [MenuPopup] / [MenuRow]。
 *
 * 设置区在窗口底部附近，下拉必须**向上展开**——[MenuPopup] 的定位器会在
 * 下方放不下时自动翻到按钮上方，所以这里不用额外判断。
 */
@Composable
private fun LyricFormatDropdown(
    current: String,
    onPick: (String) -> Unit,
) {
    val palette = Salt
    var open by remember { mutableStateOf(false) }
    Box {
        // 触发按钮：与工具栏 ToolButton 同样的形状与 hover 反馈
        Row(
            modifier =
                Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (open) palette.selected else palette.cardRaised)
                    .clickable { open = !open }
                    .padding(start = 8.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(current, size = T.micro, color = palette.textSecondary, maxLines = 1)
            Spacer(Modifier.width(4.dp))
            IconChevronDown(color = palette.textTertiary, modifier = Modifier.size(10.dp))
        }
        MenuPopup(
            expanded = open,
            onDismiss = { open = false },
            width = 140.dp,
        ) {
            // 「原始」也是选项（不做转换），和其余格式一起进受高度限制的滚动区
            MenuOptions(count = LYRIC_FORMATS.size + 1, maxRows = FORMAT_MENU_MAX_ROWS) {
                item {
                    MenuRow(
                        label = L10n.t("lyrics.original"),
                        checked = current == L10n.t("lyrics.original"),
                        onClick = {
                            open = false
                            onPick(L10n.t("lyrics.original"))
                        },
                    )
                }
                items(LYRIC_FORMATS) { format ->
                    MenuRow(
                        label = format,
                        checked = format == current,
                        onClick = {
                            open = false
                            if (format != current) onPick(format)
                        },
                    )
                }
            }
        }
    }
}

/** 小圆形加减按钮（偏移量用）。 */
@Composable
private fun StepButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val palette = Salt
    Box(
        modifier =
            Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (enabled) palette.cardRaised else palette.hover)
                .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Txt(label, size = Glyph.inline, color = palette.textSecondary, maxLines = 1)
    }
}

/**
 * 下拉里列出的**可写出**格式。
 *
 * 直接取 [LyricsFormat.Format] 的 label 再排除掉只读的那几个，避免两处维护：
 * - `TTML`：只保留解析（用户自带的 TTML 文件能读），不提供写出
 *   因为 TTML 实际只存在于 Apple Music，而取它需要账号凭据，本插件没有合法取源。
 */
private val LYRIC_FORMATS =
    io.github.univers629.spwtagworkbench.backend.lyrics.LyricsFormat.Format.entries
        .filter { it != io.github.univers629.spwtagworkbench.backend.lyrics.LyricsFormat.Format.TTML }
        .map { it.label }

/** 歌词格式下拉最多显示的行数（含「原始」），其余滚动。 */
private const val FORMAT_MENU_MAX_ROWS = 8
