// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.ui.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.univers629.spwtagworkbench.core.Glyph
import io.github.univers629.spwtagworkbench.core.Host
import io.github.univers629.spwtagworkbench.core.Mono
import io.github.univers629.spwtagworkbench.core.Salt
import io.github.univers629.spwtagworkbench.core.T
import io.github.univers629.spwtagworkbench.core.TapChip
import io.github.univers629.spwtagworkbench.core.Txt
import io.github.univers629.spwtagworkbench.core.fmtTimeFine
import io.github.univers629.spwtagworkbench.core.i18n.L10n
import io.github.univers629.spwtagworkbench.data.LyricLine
import io.github.univers629.spwtagworkbench.data.Repo
import io.github.univers629.spwtagworkbench.data.Status
import io.github.univers629.spwtagworkbench.router.SpacePage
import io.github.univers629.spwtagworkbench.ui.space.DetailScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 打轴页：只用宿主播放引擎，键位收敛到 5 个。
 *
 * | 键 | 作用 |
 * | --- | --- |
 * | 空格 | 宿主播放 / 暂停 |
 * | 回车 | 当前行行首 = 播放位置，自动跳下一行 |
 * | Tab | 当前字 = 播放位置，自动跳下一个未打轴的字 |
 * | ↑ / ↓ | 上一行 / 下一行 |
 * | Delete | 清空本行时间 |
 */
@Composable
fun TimelinePage(interactive: Boolean) {
    val pal = Salt
    val lines = Repo.timelineLines()
    var lineIndex by remember { mutableStateOf(0) }
    var cellIndex by remember { mutableStateOf(0) }
    var follow by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val synced = Repo.hostSynced

    val line = lines.getOrNull(lineIndex)
    val units: List<String> = remember(line) {
        line?.let { l ->
            if (l.cells.isNotEmpty()) {
                l.cells.map { it.text }
            } else {
                l.text.filterNot { it.isWhitespace() }.map { it.toString() }
            }
        } ?: emptyList()
    }

    LaunchedEffect(Repo.currentLine, follow, synced) {
        // 只有「正在编辑的这首 == 宿主正在播的那首」才跟随。
        // 否则在曲库里选了别的歌，宿主播放进度还是会推着这一页滚动 ——
        // 看上去就是谱轴在跟一首没在放的歌。
        if (!follow || !synced) return@LaunchedEffect
        val target = Repo.currentLine
        if (target in lines.indices) {
            lineIndex = target
            if (target < listState.layoutInfo.totalItemsCount) listState.animateScrollToItem(target)
        }
    }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // **播放位置推进循环**。
    //
    // 以下结论均为实测结果：
    //
    // ① 不能用自建协程 + `withFrameNanos`：帧时钟（MonotonicFrameClock）只存在于
    //    组合上下文里，在 Repo 里用 Dispatchers.Main 起协程会直接抛
    //    `A MonotonicFrameClock is not available`。
    //
    // ② **也不能用 `withFrameNanos` 逐帧空转**：它每帧都会向宿主请求一次渲染帧，
    //    即使位置没变。对照实验（把本循环整段去掉）显示宿主 CPU 从 **30% 掉到 0.2%**
    //    ——即这个「每帧唤醒」本身吃掉了 30% CPU，比全部绘制（0.15ms/帧）
    //    贵几百倍。这也是「所有部分都卡」的原因：主线程被帧请求塞满，
    //    宿主自己的界面也因此掉帧。
    //
    // ③ 现在改成**固定节奏的 `delay`**：只推进状态，不主动请求渲染帧；
    //    Compose 发现状态变了才安排一次重绘。播放位置变化是低频数据（每 16ms 一次
    //    已经足够），不需要跟渲染帧硬绑定。
    LaunchedEffect(Repo.playing) {
        io.github.univers629.spwtagworkbench.core.FrameStats
            .reset()
        // **进场预热**：进页面后的前 [ENTRY_ANIM_MS] 毫秒内，不论播放状态如何
        // 都按 16ms 推进一次。否则进页瞬间 `playing` 还没同步过来，循环落到
        // 120ms 慢速分支，画面就是 8fps，看起来像「卡住没动画」。
        val entryStart = System.nanoTime()
        while (true) {
            io.github.univers629.spwtagworkbench.core.FrameStats
                .frameStart()
            val changed = Repo.publishPlayhead()
            io.github.univers629.spwtagworkbench.core.FrameStats
                .frameEnd("tick")
            val warmingUp =
                (System.nanoTime() - entryStart) / 1_000_000L < ENTRY_ANIM_MS
            // 播放中（或进场预热期）16ms 一次；暂停且已稳定则降到 120ms 省电
            kotlinx.coroutines.delay(if (Repo.playing || changed || warmingUp) 16L else 120L)
        }
    }

    DetailScaffold(
        page = SpacePage.Timeline,
        label = L10n.t("timeline.title"),
        interactive = interactive,
        actions = {
            SyncLight(synced = synced)
            if (!synced) {
                TapChip(
                    label = L10n.t("timeline.followCurrent"),
                    onClick = { Repo.switchHostToFocused() },
                    accent = pal.warn,
                    enabled = interactive,
                )
            }
            // **播放倍速**：降速能让字边界听得更清，是打轴刚需。
            // 宿主 API 未暴露倍速，Repo 里走反射改 PlaybackController.changeSpeed。
            Row(verticalAlignment = Alignment.CenterVertically) {
                AxisStepButton("−") { Repo.stepPlaybackSpeed(-1) }
                Box(Modifier.width(52.dp), contentAlignment = Alignment.Center) {
                    Mono(
                        text = "${Repo.playbackSpeed}x",
                        size = T.micro,
                        color = if (Repo.playbackSpeed == 1.0f) pal.textTertiary else pal.accent,
                    )
                }
                AxisStepButton("+") { Repo.stepPlaybackSpeed(1) }
            }
            TapChip(
                label = if (follow) L10n.t("timeline.following") else L10n.t("timeline.backToFollow"),
                onClick = {
                    follow = !follow
                    if (follow && Repo.currentLine >= 0) lineIndex = Repo.currentLine
                },
                accent = if (follow) pal.accent else pal.textTertiary,
                enabled = interactive,
            )
            TapChip(
                label = L10n.t("timeline.writeTag"),
                onClick = { Repo.saveLyrics() },
                enabled = interactive && Repo.focused().hasFile,
            )
        },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(focus)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.Spacebar -> {
                            if (synced) Repo.togglePlay()
                            true
                        }

                        Key.Enter, Key.NumPadEnter -> {
                            if (synced && lines.isNotEmpty()) {
                                Repo.stampLineStart(lineIndex)
                                if (lineIndex < lines.lastIndex) {
                                    lineIndex += 1
                                    cellIndex = 0
                                    follow = false
                                }
                            }
                            true
                        }

                        Key.Tab -> {
                            if (synced && line != null) {
                                if (units.isEmpty()) {
                                    Repo.stampLineStart(lineIndex)
                                } else {
                                    Repo.stampCell(lineIndex, cellIndex, Repo.positionMs, -1L)
                                    val next = findNextUnstamped(cellIndex, line, units.size)
                                    if (next != null) {
                                        cellIndex = next
                                    } else if (lineIndex < lines.lastIndex) {
                                        lineIndex += 1
                                        cellIndex = 0
                                        follow = false
                                    }
                                }
                            }
                            true
                        }

                        Key.DirectionUp -> {
                            lineIndex = (lineIndex - 1).coerceAtLeast(0)
                            cellIndex = 0
                            follow = false
                            true
                        }

                        Key.DirectionDown -> {
                            lineIndex = (lineIndex + 1).coerceAtMost(lines.lastIndex.coerceAtLeast(0))
                            cellIndex = 0
                            follow = false
                            true
                        }

                        Key.Delete, Key.Backspace -> {
                            Repo.clearLineTiming(lineIndex)
                            cellIndex = 0
                            true
                        }

                        else -> false
                    }
                },
        ) {
            Column(Modifier.fillMaxSize()) {
                // 顶部那行进度条（`0:17.00 / 5:82.11` + `行91/91·字494/494`）已删除：
                // 播放位置宿主自己就显示了，行/字统计对打轴没有作用，白占一行高度。

                // 波形固定在**顶部**窄带：横轴是时间，与歌词行一起看才顺；
                // 放侧栏时它只是个竖向小方块，横向时间轴被压得没法用。
                WaveformStrip(
                    lines = lines,
                    synced = synced,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.width(240.dp).fillMaxHeight()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(8.dp),
                        ) {
                            itemsIndexed(lines) { index, item ->
                                LineRow(
                                    line = item,
                                    selected = index == lineIndex,
                                    playing = index == Repo.currentLine,
                                    onClick = {
                                        lineIndex = index
                                        cellIndex = 0
                                        follow = false
                                        if (synced) Repo.seekTo(item.startMs.coerceAtLeast(0L))
                                    },
                                )
                            }
                        }
                    }

                    VDivider()

                    Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 14.dp)) {
                        Spacer(Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(pal.cardRaised)
                                .border(0.5.dp, pal.border, RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            Column {
                                // **这一行就是预览**：用 KaraokePreview 显示逐字扫过动画，
                                // 不再是把整行文字静态列出来。位置由预览内部在绘制阶段读，
                                // 所以播放时只有它在重绘，整页不跟着重组。
                                if (line != null && units.isNotEmpty()) {
                                    Box(Modifier.fillMaxWidth().height(72.dp)) {
                                        KaraokePreview(line = line, units = units)
                                    }
                                } else {
                                    Txt(
                                        text = line?.text?.ifBlank { L10n.t("timeline.emptyLine") } ?: L10n.t("timeline.loadLyricsFirst"),
                                        size = T.title,
                                        color = if (synced) pal.text else pal.textSecondary,
                                        maxLines = 3,
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Txt(
                                        text = if (line != null && line.startMs >= 0) {
                                            L10n.t("timeline.lineStart", fmtTimeFine(line.startMs))
                                        } else {
                                            L10n.t("timeline.lineStartUnmarked")
                                        },
                                        size = T.micro,
                                        color = if (line != null && line.startMs >= 0) pal.accent else pal.textTertiary,
                                        maxLines = 1,
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Txt(L10n.t("timeline.hintEnter"), size = T.caption, color = pal.textTertiary, maxLines = 1)
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            WordCards(
                                units = units,
                                line = line,
                                activeIndex = cellIndex,
                                enabled = interactive && synced,
                                onPick = { idx ->
                                    cellIndex = idx
                                    Repo.stampCell(lineIndex, idx, Repo.positionMs, -1L)
                                },
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Tip(L10n.t("timeline.hintSpace"))
                            Tip(L10n.t("timeline.hintTab"))
                            Tip(L10n.t("timeline.hintArrows"))
                            Tip(L10n.t("timeline.hintDelete"))
                            Spacer(Modifier.width(6.dp))
                            // **补全时间戳**：未打轴的字按前后已知时间插值补齐。
                            // 语义参考 StrangeUtaGame 的「补全时间戳」（默认 150ms 外扩）。
                            TapChip(
                                label = L10n.t("timeline.fillTimestamps"),
                                onClick = {
                                    val n = Repo.completeTimestamps(lineIndex)
                                    Repo.postStatus(
                                        Status(
                                            if (n > 0) L10n.t("timeline.filled", n) else L10n.t("timeline.noFillable"),
                                            if (n > 0) Status.Kind.Ok else Status.Kind.Warn,
                                        ),
                                    )
                                },
                                accent = pal.textSecondary,
                            )
                            // **批量平移**：行级 / 字级各一组，每次 10ms，可反复叠加。
                            // 参考 StrangeUtaGame 的「调整原始时间戳」：点一次叠一次。
                            Tip(L10n.t("timeline.line"))
                            AxisStepButton("−") { Repo.shiftLineTimestamps(lineIndex, -10L) }
                            AxisStepButton("+") { Repo.shiftLineTimestamps(lineIndex, 10L) }
                            Tip(L10n.t("timeline.char"))
                            AxisStepButton("−") { Repo.shiftCellTimestamps(lineIndex, cellIndex, -10L) }
                            AxisStepButton("+") { Repo.shiftCellTimestamps(lineIndex, cellIndex, 10L) }
                            Spacer(Modifier.weight(1f))
                            TapChip(
                                label = L10n.t("timeline.clearLine"),
                                onClick = {
                                    Repo.clearLineTiming(lineIndex)
                                    cellIndex = 0
                                },
                                accent = pal.warn,
                                enabled = interactive && line != null,
                            )
                        }
                    }

                    VDivider()

                    // 右栏：上 = 卡拉 OK **预览**，下 = **原文本**（可改时间戳）。
                    // 两块比例 3:4（参考项目 VerticalRatioLayout 的 3:4）——
                    // 预览是「最终效果」，原文本是「精确修正」，后者需要更多高度放长句。
                    // Compose 里直接 weight 即可表达固定比例，不像 Swing 要先占首选尺寸。
                    Column(Modifier.width(340.dp).fillMaxHeight().padding(horizontal = 10.dp)) {
                        Spacer(Modifier.height(8.dp))
                        PanelCard(L10n.t("timeline.line"), Modifier.weight(3f).fillMaxWidth()) {
                            LineTimePanel(
                                lineIndex = lineIndex,
                                line = line,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        PanelCard(L10n.t("timeline.split"), Modifier.weight(3f).fillMaxWidth()) {
                            CellTimePanel(
                                lineIndex = lineIndex,
                                cellIndex = cellIndex,
                                line = line,
                                units = units,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        PanelCard(L10n.t("timeline.sourceText"), Modifier.weight(4f).fillMaxWidth()) {
                            RawLineEditor(
                                line = line,
                                enabled = interactive,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncLight(synced: Boolean) {
    val pal = Salt
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (synced) pal.accent else pal.warn),
        )
        Spacer(Modifier.width(6.dp))
        Txt(
            text = if (synced) L10n.t("timeline.synced") else L10n.t("timeline.unsynced"),
            size = T.micro,
            color = if (synced) pal.accent else pal.warn,
            maxLines = 1,
        )
    }
}

@Composable
private fun Tip(text: String) {
    val pal = Salt
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(pal.hover)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Txt(text, size = T.micro, color = pal.textTertiary, maxLines = 1)
    }
}

@Composable
private fun VDivider() {
    Box(Modifier.width(0.5.dp).fillMaxHeight().background(Salt.border))
}

@Composable
private fun LineRow(
    line: LyricLine,
    selected: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
) {
    val pal = Salt
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    selected -> pal.selected
                    playing -> pal.hover
                    else -> Color.Transparent
                },
            ).clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Mono(
            text = if (line.startMs >= 0) fmtTimeFine(line.startMs) else "--:--",
            size = T.micro,
            color = if (line.startMs >= 0) pal.accent else pal.textTertiary,
            modifier = Modifier.width(54.dp),
        )
        Spacer(Modifier.width(6.dp))
        Txt(
            text = line.text.ifBlank { L10n.t("timeline.emptyLine") },
            size = T.micro,
            color = if (selected) pal.text else pal.textSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun WordCards(
    units: List<String>,
    line: LyricLine?,
    activeIndex: Int,
    enabled: Boolean,
    onPick: (Int) -> Unit,
) {
    val pal = Salt
    if (line == null || units.isEmpty()) {
        Txt(L10n.t("timeline.noSplittable"), size = T.micro, color = pal.textTertiary, maxLines = 1)
        return
    }
    val starts: List<Long> = if (line.cells.isNotEmpty()) line.cells.map { it.startMs } else emptyList()
    val ends: List<Long> = if (line.cells.isNotEmpty()) line.cells.map { it.endMs } else emptyList()
    // 注意：**不要**在这里读 `Repo.positionMs`。播放位置只在各卡片的进度条
    // Canvas 的绘制阶段读取，避免整块卡片因播放而重组。

    // 每张卡是参考项目 `TokenPillPanel` 那种「胶囊」：
    // 左上角序号、中央字、底部时长、**最底下一条进度条**。
    //
    // 关键分工（原来搞错了）：
    // - **高亮圈只表示编辑焦点**（activeIndex，按 Tab 或点选移动的那个字）
    // - **播放进度用每张卡自己的进度条表示**，与焦点无关
    // 这样播放时是「进度条一格一格扫过去」，而不是把高亮圈挪来挪去。
    // 用 FlowRow 自动换行：卡片宽度按内容自适应，一行放不下就折到下一行。
    // 原来 `chunked(10)` 固定每行 10 个 + 固定 52dp 宽，内容稍长就被挤/截断。
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        units.forEachIndexed { idx, text ->
            val startMs = starts.getOrNull(idx) ?: -1L
            val endMs = ends.getOrNull(idx) ?: -1L
            val timed = startMs >= 0
            val isFocused = idx == activeIndex

            // 这一张自己的填充进度**在绘制阶段算**（见下方进度条的 Canvas）。
            //
            // 不能在组合阶段读 `Repo.positionMs` 再算 fill：那会让整块卡片的组合
            // 对播放位置建立依赖，每帧（33fps）把这一行十几个卡片全部重新组合，
            // 这正是用户说的「分块逐字也卡」的原因。
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // **用 Column 流式布局，宽度完全由内容决定**。
                //
                // 原来用 `Box` + `align(Center)` 绝对定位：Box 的尺寸跟着父约束走、
                // 不跟内容走，`wrapContentWidth()` 等于没生效，所有卡片被拉成同一宽度。
                // 现在改成 Column：内容的固有宽度直接就是卡片宽度，
                // 只有真的超出容器宽度时才由外层 FlowRow 折行。
                Column(
                    modifier =
                        Modifier
                            // **收紧到内容宽度**：FlowRow 里的子项默认会被拉满，
                            // 加 IntrinsicSize.Min 让宽度由「最宽的那个子元素」决定，
                            // 于是卡片只按实际内容占位，超出容器才换行。
                            // 宽度按内容收紧：Canvas 进度条改成固定宽后，
                            // Column 的最宽子项就是文字，卡片自然只占内容宽度。
                            .wrapContentWidth(Alignment.CenterHorizontally)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                when {
                                    isFocused -> pal.accentSoft
                                    timed -> pal.cardRaised
                                    else -> pal.card
                                },
                            ).border(
                                width = if (isFocused) 2.dp else 1.dp,
                                color = if (isFocused) pal.accent else pal.border,
                                shape = RoundedCornerShape(10.dp),
                            ).clickable(enabled = enabled) { onPick(idx) }
                            // 内边距给足：序号/时长不再和文字挤在一起
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // 序号
                    Txt(
                        text = (idx + 1).toString().padStart(2, '0'),
                        size = T.micro,
                        color = pal.textTertiary,
                        maxLines = 1,
                    )
                    // 字：宽度由文字自己决定，不强制换行
                    Txt(
                        text = text,
                        size = T.title,
                        color = if (timed) pal.text else pal.textTertiary,
                        maxLines = 1,
                    )
                    // 时长：与文字之间留 2dp，底部与进度条留 4dp，不再重叠
                    Spacer(Modifier.height(2.dp))
                    Txt(
                        text = if (timed && endMs > startMs) "${endMs - startMs}ms" else "--",
                        size = T.micro,
                        color = pal.textTertiary,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(4.dp))
                    // **底部进度条**：Canvas 自绘，播放位置在绘制阶段读。
                    // 这样播放时只有这条 3dp 高的进度条重绘，卡片本身不重组。
                    Canvas(
                        modifier = Modifier
                            .width(CARD_PROGRESS_WIDTH)
                            .height(3.dp),
                    ) {
                        drawRect(color = pal.border)
                        if (!timed) return@Canvas
                        val pos = Repo.positionMs
                        val f =
                            when {
                                pos <= startMs -> 0f
                                endMs > startMs && pos >= endMs -> 1f
                                endMs > startMs -> ((pos - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)
                                else -> if (pos >= startMs) 1f else 0f
                            }
                        if (f > 0f) {
                            drawRect(
                                color = pal.accent,
                                size = androidx.compose.ui.geometry
                                    .Size(size.width * f, size.height),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 从当前字往后找第一个未打轴的字。 */
private fun findNextUnstamped(
    current: Int,
    line: LyricLine,
    unitCount: Int,
): Int? {
    if (line.cells.isEmpty()) return (current + 1).takeIf { it < unitCount }
    for (i in (current + 1) until line.cells.size) {
        if (!line.cells[i].stamped) return i
    }
    for (i in 0 until current) {
        if (!line.cells[i].stamped) return i
    }
    return null
}

/**
 * 顶部滚动波形：**只显示播放位置附近的时间窗口**，随播放滚动。
 *
 * 为什么不画全轴：参考项目 `SPW-Tag-Workbench` 的 `WaveformPanel` 也是滚动视口
 * （`viewStartMs` / `viewDurationMs`）。全轴把整首歌压进 900px 后，
 * 一秒宽只有几像素，打轴时看不出字与字的间隙。
 *
 * 数据源是**直接读 PCM**（[Waveform.peaks]），与频谱图无关——
 * 频谱是一次性整首 FFT 的低分辨率图像，没法支撑局部放大。
 */
@Composable
private fun WaveformStrip(
    lines: List<LyricLine>,
    synced: Boolean,
    modifier: Modifier = Modifier,
) {
    val pal = Salt
    val path = Repo.focused().path
    val duration = Repo.focused().durationMs.coerceAtLeast(1L)

    // **播放位置一律在绘制阶段（Canvas 的 draw lambda）里读**，不在组合阶段读。
    //
    // 这是 Compose 的关键区别：在组合阶段读 `mutableStateOf`，值一变就要重新执行
    // 整个 composable（重组合）；在绘制阶段（DrawScope 里）读，只会让这个 Canvas
    // 重绘一次 draw pass——跳过组合与布局。
    //
    // 原来是 `val position = Repo.positionMs` 写在组件顶部，等于每 40ms 让整条波形
    // 走一遍完整重组，播放时自然卡。

    // **进场动画**：进页面后的前 [ENTRY_ANIM_MS] 让绘制位置从「基准 - 1.2 秒」
    // 线性追到「基准」，波形于是整体向左滚入。
    //
    // 宿主暂停时 `Repo.positionMs` 不动，波形是静止图。
    // 用这个独立的动画量驱动，与播放状态无关。
    val entryOffset = remember {
        androidx.compose.animation.core
            .Animatable(1f)
    }
    LaunchedEffect(path) {
        entryOffset.snapTo(1f)
        entryOffset.animateTo(
            targetValue = 0f,
            animationSpec = androidx.compose.animation.core.tween(
                durationMillis = 1600,
                easing = androidx.compose.animation.core.FastOutSlowInEasing,
            ),
        )
    }

    // **整条轴一次性算好，之后只做切片**。
    //
    // 之前把 `viewStart` 放进 LaunchedEffect 的 key：播放位置每变一点点就重算一次，
    // 而 `Waveform.peaks` 每次都要新建 BASS 解码流、定位、逐块读 PCM，
    // 于是每几毫秒一次磁盘解码，帧率被拖到个位数——用户感觉「很卡」。
    //
    // 现在：进页面时把**整条轴**读一次（[WAVEFORM_FULL_BUCKETS] 个峰值柱），
    // 后续每帧只是从这份数组里切片绘制，零 IO。
    var axis by remember(path) { mutableStateOf(WaveformAxis(FloatArray(0), 0L)) }

    // **波形静态层缓存**：窗口量化后的 viewStart 作为 key，
    // 位移不足 [WAVE_LAYER_QUANTUM_PX] 像素就复用旧位图，只重画播放头。
    var waveLayer by remember(path) {
        mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    }
    var waveLayerKey by remember(path) { mutableStateOf<WaveLayerKey?>(null) }

    // 复用的波形 Path：逐列构建后一次性 drawPath，避免每帧 new Path 与大量 Offset
    val wavePathBuf = remember {
        androidx.compose.ui.graphics
            .Path()
    }
    LaunchedEffect(path, duration, synced) {
        io.github.univers629.spwtagworkbench.core.Diag.log(
            "wave: effect path=${path.takeLast(28)} dur=$duration synced=$synced",
        )
        if (path.isBlank() || !synced) return@LaunchedEffect
        val t0 = System.nanoTime()
        val data =
            withContext(Dispatchers.IO) {
                io.github.univers629.spwtagworkbench.backend.audio.Waveform.peaks(
                    path = path,
                    fromMs = 0L,
                    toMs = duration,
                    buckets = WAVEFORM_FULL_BUCKETS,
                )
            }
        io.github.univers629.spwtagworkbench.core.Diag.log(
            "wave: 读完整轴 ${data.size} 桶 耗时 ${(System.nanoTime() - t0) / 1_000_000}ms",
        )
        axis = WaveformAxis(data, duration)
    }

    // 绘制用的数据：从整轴里按当前窗口取。改为在 Canvas 里按像素列直接采样，
    // 不再每帧 slice 出一个中间数组（省一次分配，且能保证 1 像素 1 柱）。

    Row(
        modifier = modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .height(72.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(pal.cardRaised)
                    .border(0.5.dp, pal.border, RoundedCornerShape(10.dp))
                    .pointerInput(synced, duration) {
                        if (!synced) return@pointerInput
                        detectTapGestures { offset ->
                            // 点波形跳转：这里读位置是「事件时」求值，不建立组合依赖
                            val window = WINDOW_MS.coerceAtMost(duration)
                            val pos = Repo.positionMs
                            val start =
                                (pos - window / 2).coerceIn(0L, (duration - window).coerceAtLeast(0L))
                            val ratio = (offset.x / size.width).coerceIn(0f, 1f)
                            val target = start + (window * ratio).toLong()
                            Repo.seekTo(target.coerceIn(0L, duration))
                        }
                    },
        ) {
            // **静态层 + 播放头**：波形等静态内容缓存成 ImageBitmap，
            // 只有窗口真实位移时才重建；每帧只贴图 + 画一根播放头线。
            //
            // 借鉴 StrangeUtaGame `timeline_widget` 的 `_static_layer` 设计：
            // 它把波形/标签画到 QPixmap，key（宽高/可见区间/选中态…）不变就
            // 直接 drawPixmap，播放头单独画在最上层。
            Canvas(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 6.dp)) {
                val perfT0 = System.nanoTime()
                val data = axis.peaks
                if (data.isEmpty()) return@Canvas

                val position = Repo.positionMs
                val windowMs = WINDOW_MS.coerceAtMost(duration)
                val entryFrom = position - ENTRY_SHIFT_MS
                val entryProgress = 1f - entryOffset.value
                val drawPosition =
                    entryFrom + ((position - entryFrom) * entryProgress).toLong()
                val viewStart = drawPosition - windowMs / 2
                val viewEnd = viewStart + windowMs
                val w = size.width.toInt().coerceAtLeast(1)
                val h = size.height.toInt().coerceAtLeast(1)

                // 量化 viewStart，避免每像素位移都重建缓存
                val quantum = (windowMs / w).coerceAtLeast(1L) * WAVE_LAYER_QUANTUM_PX
                val quantizedStart = (viewStart / quantum) * quantum

                val layerKey = WaveLayerKey(w, h, quantizedStart, windowMs)
                if (waveLayer == null || waveLayerKey != layerKey) {
                    waveLayer = renderWaveLayer(axis, layerKey)
                    waveLayerKey = layerKey
                }
                waveLayer?.let { drawImage(it) }

                // 播放头固定在正中（窗口以播放位置为中心）
                drawLine(
                    color = pal.text,
                    start = Offset(size.width / 2f, 0f),
                    end = Offset(size.width / 2f, size.height),
                    strokeWidth = 2f,
                )
                io.github.univers629.spwtagworkbench.core.FrameStats
                    .record("wave-draw", System.nanoTime() - perfT0)
            }
        }
        // 时间文本（当前位置 + 窗口区间）已移除：
        // 宿主的播放条一直在显示播放位置，这里再标一次是重复信息，还白占波形宽度。
    }
}

/** 波形静态层的缓存键。宽高或可见区间（量化后）变了才需要重建。 */
private data class WaveLayerKey(
    val width: Int,
    val height: Int,
    val viewStart: Long,
    val windowMs: Long,
)

/** 窗口位移多少像素才重建静态层。太小等于没缓存，太大视觉上会「跳」。 */
private const val WAVE_LAYER_QUANTUM_PX = 4

/** 金字塔最小层桶数：再往下减半已无意义（一桶对应太多毫秒）。 */
private const val PYRAMID_MIN_BUCKETS = 128

/**
 * 把波形渲染到一张 ImageBitmap（离屏绘制一次，之后每帧只 drawImage）。
 *
 * 借鉴 StrangeUtaGame `_render_static_layer`：静态内容与播放头分离，
 * 播放时不再逐列重建路径。
 */
private fun renderWaveLayer(
    axis: WaveformAxis,
    key: WaveLayerKey,
): androidx.compose.ui.graphics.ImageBitmap? {
    // **用最细层绘制**。
    //
    // 之前这里用 `axis.levelFor(key.width)` 选金字塔层级，结果选到 1024 桶那层：
    // 800px 宽上只有 1024 个点，且每层是把相邻桶取 max 合并出来的，
    // 相邻点差异被抹平，画出来是一排「大方块」。
    // 最细层 8192 桶对 8 秒窗口而言密度足够（每像素约 10 个原始桶），
    // 线性插值采样能得到连续轮廓。金字塔留给整轴概览用。
    val data = axis.peaks
    if (data.isEmpty() || key.width <= 0 || key.height <= 0) return null
    val bitmap =
        androidx.compose.ui.graphics.ImageBitmap(
            key.width,
            key.height,
            androidx.compose.ui.graphics.ImageBitmapConfig.Argb8888,
        )
    val canvas = androidx.compose.ui.graphics
        .Canvas(bitmap)
    val scope = androidx.compose.ui.graphics.drawscope
        .CanvasDrawScope()
    scope.draw(
        density = androidx.compose.ui.unit
            .Density(1f),
        layoutDirection = androidx.compose.ui.unit.LayoutDirection.Ltr,
        canvas = canvas,
        size = androidx.compose.ui.geometry
            .Size(key.width.toFloat(), key.height.toFloat()),
    ) {
        val baseline = key.height.toFloat()
        val usable = key.height * 0.96f
        val total = axis.durationMs.toDouble().coerceAtLeast(1.0)
        val span = key.windowMs.toDouble().coerceAtLeast(1.0)
        val n = data.size
        val path = androidx.compose.ui.graphics
            .Path()
        var started = false
        var firstX = 0f
        var lastX = 0f
        val waveColor = androidx.compose.ui.graphics
            .Color(0.42f, 0.62f, 0.92f, 0.8f)
        for (x in 0 until key.width) {
            val at = key.viewStart + span * (x.toDouble() / key.width)
            if (at < 0.0 || at > total) continue
            val ratio = (at / total).coerceIn(0.0, 1.0)
            val idx = (ratio * (n - 1)).toInt().coerceIn(0, n - 1)
            val p = data[idx].coerceIn(0f, 1f)
            val hh = (p * usable).coerceAtLeast(0.5f)
            val fx = x.toFloat()
            if (!started) {
                path.moveTo(fx, baseline - hh)
                started = true
                firstX = fx
            } else {
                path.lineTo(fx, baseline - hh)
            }
            lastX = fx
        }
        if (started) {
            path.lineTo(lastX + 1f, baseline)
            path.lineTo(firstX, baseline)
            path.close()
            drawPath(path = path, color = waveColor)
        }
    }
    return bitmap
}

/** 滚动窗口的跨度：8 秒左右，一秒约 110px，够看清字与字的间隙。 */
private const val WINDOW_MS = 8_000L

/**
 * 进场预热的时长（毫秒）：进页面后这段时间内无条件按 60fps 推进，
 * 让动画立刻动起来，不必等宿主播放状态同步。
 */
private const val ENTRY_ANIM_MS = 1_600L

/** 进场动画的滚入距离（毫秒）：进页时波形从「基准 - 该值」平滑追到基准。 */
private const val ENTRY_SHIFT_MS = 4_000L

/**
 * 整条轴的峰值柱数。
 *
 * 打轴页窗口 8 秒铺在约 800px 上，采样密度必须明显高于屏幕像素，
 * 否则滚到局部时每列只能重复同一个值，波形会呈现「阶梯/方块」。
 * 8192 柱对 5 分钟歌约 36ms/柱，铺到 800px 上每像素有 ~10 个采样可选，轮廓平滑。
 * 8192 个 float = 32KB，内存无压力。
 */
private const val WAVEFORM_FULL_BUCKETS = 8192

/**
 * 整条轴的波形峰值：一次性读好，绘制时只切片。
 *
 * 存在的意义：把「读 PCM」与「绘制」彻底解耦。
 * 读盘是大开销（新建解码流 + 定位 + 逐块解），绘制每帧都要做，
 * 两者一旦绑在一起（把播放位置放进重算 key），帧率立刻掉到个位数。
 */
private class WaveformAxis(
    raw: FloatArray,
    val durationMs: Long,
) {
    /**
     * 已按**整轴最大值**归一化到 0..1 的峰值。
     *
     * 为什么在构造时算：归一化基准是整首歌的常量，算一次就够。
     * 上一版放在绘制路径里逐帧扫窗口求 peak，每帧多一次 O(宽度) 遍历，
     * 增加开销，而且窗口滚动时亮度会跳。
     */
    val peaks: FloatArray

    /** 金字塔各层，[0] 最细（等于 [peaks]），往后逐级减半。 */
    private val pyramid: Array<FloatArray>

    init {
        var maxPeak = 0f
        for (v in raw) {
            if (v > maxPeak) maxPeak = v
        }
        // 0.02 是噪声地板：整首都极安静时不把底噪放大成满幅
        peaks =
            if (maxPeak > 0.02f) {
                val k = 1f / maxPeak
                FloatArray(raw.size) { (raw[it] * k).coerceIn(0f, 1f) }
            } else {
                FloatArray(raw.size) { (raw[it] * 20f).coerceIn(0f, 1f) }
            }

        // **多分辨率金字塔**：每层是把上一层的相邻两桶取最大合并而成。
        //
        // 为什么取 max 而不是平均：波形要保留瞬态峰值，平均会把尖峰抹平。
        // 参考 StrangeUtaGame `_waveform_peak_levels` 的做法，
        // 缩放时按 2 的幂选层，永远挑「粒度不粗于目标」的那层。
        val levels = ArrayList<FloatArray>()
        levels.add(peaks)
        var cur = peaks
        while (cur.size > PYRAMID_MIN_BUCKETS) {
            val next = FloatArray(cur.size / 2)
            for (i in next.indices) {
                val a = cur[i * 2]
                val b = cur[i * 2 + 1]
                next[i] = if (a > b) a else b
            }
            levels.add(next)
            cur = next
        }
        pyramid = levels.toTypedArray()
    }

    /**
     * 选一层适合 [targetBins] 个采样点的峰值数组。
     *
     * 选「长度 >= targetBins 的最粗那层」：粒度不粗于目标，瞬态不会被抹平；
     * 同时尽量贴近目标，避免用超细层做无谓的重复采样。
     */
    fun levelFor(targetBins: Int): FloatArray {
        if (targetBins <= 0 || pyramid.isEmpty()) return peaks
        var best = pyramid[0]
        for (lvl in pyramid) {
            if (lvl.size >= targetBins) best = lvl else break
        }
        return best
    }
}

/** 带标题的圆角卡片容器，右栏两块共用。 */
@Composable
private fun PanelCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val pal = Salt
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(pal.cardRaised)
            .border(0.5.dp, pal.border, RoundedCornerShape(12.dp)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(title, size = T.caption, color = pal.textSecondary, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(pal.border))
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

/**
 * 卡拉 OK 预览：按播放位置给每个字上色——**已唱的字用强调色、未唱的字用次级色**。
 *
 * 为什么需要它：波形和时间数字都是「专业视角」，而歌词最终是给人唱的。
 * 只有按真实样式渲染一遍，才能判断「这个字是不是早了一点」。
 *
 * 未打轴的字保持中性色并加下划线——**未打轴必须是可见的状态**，
 * 否则用户会误以为已经打好轴了。
 */
@Composable
private fun KaraokePreview(
    line: LyricLine?,
    units: List<String>,
) {
    val pal = Salt
    if (line == null || units.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
            Txt(L10n.t("timeline.noPreview"), size = T.micro, color = pal.textTertiary, maxLines = 2)
        }
        return
    }
    val starts: List<Long> =
        if (line.cells.isNotEmpty()) line.cells.map { it.startMs } else emptyList()
    val ends: List<Long> =
        if (line.cells.isNotEmpty()) line.cells.map { it.endMs } else emptyList()
    val lineEnd = line.endMs.takeIf { it > line.startMs } ?: (line.startMs + ESTIMATED_LINE_MS)

    // 用 Canvas 自绘，而不是每个字一个 Txt：
    // 1. 参考项目就是「先画整字、再 clipRect 重画已唱部分」得到字内扫过效果；
    // 2. 一个 Canvas 的重绘成本远低于几十个文本组件的重组。
    val measurer = rememberTextMeasurer()
    val style =
        androidx.compose.ui.text.TextStyle(
            color = pal.textSecondary,
            fontSize = T.title,
            fontFamily = Host.sans,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        )

    // **排版必须预计算，不能放在 Canvas 的绘制路径里**。
    //
    // `TextMeasurer` 只有 8 条 LRU 记录：一行十几个字，每帧逐个 measure 会把缓存全部冲掉，
    // 等于每帧重排整行文字——这是明显的卡顿来源。
    // 这里把每个字的 LayoutResult 算一次存起来，绘制时只按位置偏移。
    val layoutData =
        remember(units, style, measurer) {
            units.map { text -> measurer.measure(text, style) }
        }

    // 复用的填充进度缓冲：绘制每秒几十次，`map{}` 每帧都会新建 List，
    // 在宿主 512M 堆 + SoftMaxHeapSize=128M 下会持续推高 GC（实测老年代占用 97.9%）。
    val fillsBuf = remember(units) { FloatArray(units.size) }

    Box(Modifier.fillMaxSize().padding(12.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val perfT0 = System.nanoTime()
            // **在绘制阶段读播放位置**：只触发 draw pass 重绘，不重新组合。
            val positionMs = Repo.positionMs

            // 每个字的填充进度（0..1）——连续值，「字内扫过」的前提。
            // 复用 fillsBuf，避免每帧分配新数组。
            val fills = fillsBuf
            val unitCount = units.size
            for (idx in 0 until unitCount) {
                val startMs = starts.getOrNull(idx) ?: line.startMs
                val endMs =
                    ends.getOrNull(idx)?.takeIf { it > startMs } ?: run {
                        // 没有逐字时间：按整行时长均分推算，让逐行歌词也有推进感
                        val span = (lineEnd - line.startMs).coerceAtLeast(1L)
                        val per = span.toFloat() / unitCount
                        val s = line.startMs + (per * idx).toLong()
                        s + per.toLong().coerceAtLeast(1L)
                    }
                fills[idx] =
                    when {
                        positionMs <= startMs -> 0f
                        positionMs >= endMs -> 1f
                        else -> ((positionMs - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)
                    }
            }

            val pad = 0f
            var x = pad
            var y = 0f
            val lineHeight = 26.dp.toPx()
            layoutData.forEachIndexed { idx, layout ->
                val w = layout.size.width.toFloat()
                if (x + w > size.width && x > pad) {
                    x = pad
                    y += lineHeight
                }
                if (y > size.height) return@forEachIndexed

                val startMs = starts.getOrNull(idx) ?: line.startMs
                val timed = startMs >= 0
                val f = fills[idx]

                // ① 基色画整字
                val base =
                    when {
                        !timed -> pal.textTertiary
                        f >= 1f -> pal.accent
                        else -> pal.textSecondary
                    }
                drawText(layout, color = base, topLeft = Offset(x, y))

                // ② 唱到一半：裁剪到 fraction 宽度，用强调色重画同一个字
                //    这就是「字内扫过」——左边已唱、右边未唱，扫过线在字里推进
                if (timed && f > 0f && f < 1f) {
                    clipRect(
                        left = x,
                        top = y,
                        right = x + w * f,
                        bottom = y + lineHeight,
                    ) {
                        drawText(layout, color = pal.accent, topLeft = Offset(x, y))
                    }
                }

                // ③ 未打轴：加下划线（形状表达，不只靠颜色）
                if (!timed) {
                    drawLine(
                        color = pal.textTertiary,
                        start = Offset(x, y + layout.size.height - 2f),
                        end = Offset(x + w, y + layout.size.height - 2f),
                        strokeWidth = 1.5f,
                    )
                }

                x += w
            }
            io.github.univers629.spwtagworkbench.core.FrameStats
                .record("preview-draw", System.nanoTime() - perfT0)
        }
    }
}

/**
 * 时间显示格式：`分:秒.毫秒`。未标记（-1）显示 `--:--.---`。
 */
private fun fmtAxisTime(ms: Long): String {
    if (ms < 0L) return "--:--.---"
    val m = ms / 60_000
    val s = ms / 1_000 % 60
    val f = ms % 1_000
    return "%d:%02d.%03d".format(m, s, f)
}

/**
 * 解析用户输入的 `分:秒.毫秒` / `秒.毫秒` / 纯毫秒，失败返回 -1。
 */
private fun parseAxisTime(raw: String): Long {
    val t = raw.trim()
    if (t.isEmpty()) return -1L
    return runCatching {
        if (':' in t) {
            val parts = t.split(":")
            val m = parts[0].toLong()
            val sp = parts[1].split(".")
            val sec = sp[0].toLong()
            val frac = if (sp.size > 1) sp[1].padEnd(3, '0').take(3).toLong() else 0L
            m * 60_000 + sec * 1_000 + frac
        } else {
            t.toLong()
        }
    }.getOrDefault(-1L)
}

/** 小圆加减按钮，与歌词匹配页 StepButton 同形。 */
@Composable
private fun AxisStepButton(
    label: String,
    onClick: () -> Unit,
) {
    val pal = Salt
    Box(
        modifier =
            Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(pal.cardRaised)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Txt(label, size = Glyph.inline, color = pal.textSecondary, maxLines = 1)
    }
}

/** 加减按钮 + 可输入的时间框，两个面板共用。 */
@Composable
private fun TimeField(
    label: String,
    valueMs: Long,
    stepMs: Long,
    onSet: (Long) -> Unit,
) {
    val pal = Salt
    // 输入框持有本地缓冲：用户打字时不回写外部状态。
    var buf by remember(valueMs) { mutableStateOf(fmtAxisTime(valueMs)) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(label, size = T.caption, color = pal.textSecondary, maxLines = 1)
        Spacer(Modifier.weight(1f))
        AxisStepButton("−") { onSet((valueMs - stepMs).coerceAtLeast(0L)) }
        Box(
            modifier =
                Modifier
                    .width(96.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(pal.hover)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
        ) {
            BasicTextField(
                value = buf,
                onValueChange = { buf = it },
                textStyle =
                    androidx.compose.ui.text.TextStyle(
                        color = pal.accent,
                        fontSize = T.micro,
                        fontFamily = Host.sans,
                    ),
                singleLine = true,
                cursorBrush = SolidColor(pal.accent),
                modifier =
                    Modifier.fillMaxWidth().onFocusChanged { st ->
                        // 失焦时提交；解析不出来就恢复原值
                        if (!st.isFocused) {
                            val parsed = parseAxisTime(buf)
                            if (parsed >= 0L) onSet(parsed)
                            buf = fmtAxisTime(if (parsed >= 0L) parsed else valueMs)
                        }
                    },
            )
        }
        AxisStepButton("+") { onSet(valueMs + stepMs) }
    }
}

/** 行面板：当前行的行首 / 行尾。 */
@Composable
private fun LineTimePanel(
    lineIndex: Int,
    line: LyricLine?,
) {
    val pal = Salt
    if (line == null) {
        Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
            Txt(L10n.t("timeline.selectLineFirst"), size = T.micro, color = pal.textTertiary, maxLines = 1)
        }
        return
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TimeField(
            label = L10n.t("timeline.lineStartLabel"),
            valueMs = line.startMs,
            stepMs = AXIS_TIME_STEP_MS,
        ) { Repo.setLineTime(lineIndex, it, line.endMs) }
        TimeField(
            label = L10n.t("timeline.lineEndLabel"),
            valueMs = line.endMs,
            stepMs = AXIS_TIME_STEP_MS,
        ) { Repo.setLineTime(lineIndex, line.startMs, it) }
    }
}

/** 分片面板：当前选中分片的首 / 尾。 */
@Composable
private fun CellTimePanel(
    lineIndex: Int,
    cellIndex: Int,
    line: LyricLine?,
    units: List<String>,
) {
    val pal = Salt
    val cell = line?.cells?.getOrNull(cellIndex)
    if (line == null || cell == null) {
        Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
            Txt(L10n.t("timeline.selectFragmentFirst"), size = T.micro, color = pal.textTertiary, maxLines = 1)
        }
        return
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Txt(
            text = "${cellIndex + 1}. ${units.getOrNull(cellIndex) ?: cell.text}",
            size = T.caption,
            color = pal.text,
            maxLines = 2,
        )
        TimeField(
            label = L10n.t("timeline.charStart"),
            valueMs = cell.startMs,
            stepMs = AXIS_TIME_STEP_MS,
        ) { Repo.setCellTime(lineIndex, cellIndex, it, cell.endMs) }
        TimeField(
            label = L10n.t("timeline.charEnd"),
            valueMs = cell.endMs,
            stepMs = AXIS_TIME_STEP_MS,
        ) { Repo.setCellTime(lineIndex, cellIndex, cell.startMs, it) }
    }
}

/** 时间加减步进（毫秒）。 */
private const val AXIS_TIME_STEP_MS = 10L

/** 分片卡片底部进度条的固定宽度（卡片本身按内容宽）。 */
private val CARD_PROGRESS_WIDTH = 40.dp

/** 没有 endMs 时的行长估算值：一句歌词大约 4 秒。 */
private const val ESTIMATED_LINE_MS = 4_000L

/** 未打轴的下划线标记：Compose 没有现成修饰符，用 drawBehind 画底部细线。 */
private fun Modifier.underline(color: Color): Modifier =
    this.drawBehind {
        val w = this.size.width
        val h = this.size.height
        drawLine(
            color = color,
            start = Offset(0f, h - 1f),
            end = Offset(w, h - 1f),
            strokeWidth = 1f,
        )
    }

/**
 * 原文本窗格：**只显示这一行的源文本**。
 *
 * 参考项目 SPW-Tag-Workbench 的 `SplLineEditorPanel` 就是这个定位——
 * 显示当前行的 SPL 原文（`[t]词[t]词`），让用户直接改时间戳。
 * 它也**不列逐字明细表**：源文本本身已经把每个字的时间写在字前面了，
 * 再列一遍表格是重复信息，还占掉整块高度。
 *
 * 保留时间戳是关键：行首的 `[t]` 与每个字前的 `[t]` 就是时间，改它们就是改轴。
 */
@Composable
private fun RawLineEditor(
    line: LyricLine?,
    enabled: Boolean,
) {
    val pal = Salt
    if (line == null) {
        Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
            Txt(L10n.t("timeline.selectLineFirst"), size = T.micro, color = pal.textTertiary, maxLines = 1)
        }
        return
    }

    // 这一行导出成 SPL 后的字符串——与最终写盘格式完全一致：
    // 用户在这里看到什么，文件里就是什么。
    val spl = remember(line) {
        runCatching {
            io.github.univers629.spwtagworkbench.backend.lyrics.LyricsFormat
                .toSpl(listOf(line))
        }.getOrElse { line.text }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Mono(
            text = spl.ifBlank { L10n.t("timeline.emptyLine") },
            size = T.caption,
            color = pal.textSecondary,
            // 源文本允许换行：Mono 默认 maxLines=1，长行会被截断
            maxLines = Int.MAX_VALUE,
        )
    }
}
