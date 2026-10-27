// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.ui.spectrum

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.github.univers629.spwtagworkbench.core.EmptyState
import io.github.univers629.spwtagworkbench.core.Salt
import io.github.univers629.spwtagworkbench.core.T
import io.github.univers629.spwtagworkbench.core.TapChip
import io.github.univers629.spwtagworkbench.core.Txt
import io.github.univers629.spwtagworkbench.core.i18n.L10n
import io.github.univers629.spwtagworkbench.data.Drafts
import io.github.univers629.spwtagworkbench.data.Repo
import io.github.univers629.spwtagworkbench.data.SpectrogramState
import io.github.univers629.spwtagworkbench.router.SpacePage
import io.github.univers629.spwtagworkbench.ui.space.DetailScaffold

/**
 * 频谱页。
 *
 * 布局与参考实现一致：**左右两个声道并排在同一张图里**（左半 L、右半 R），
 * 图下方是一条按时序变化的彩色包络带。
 */
@Composable
fun SpectrumPage(interactive: Boolean) {
    val palette = Salt
    val state = Repo.spectrogram

    // 切到这一页就开始生成（参考项目：进页即解码）。
    // key 用曲目路径（稳定），已有结果或正在分析时不重复触发，
    // 避免页面切换时反复重启解码造成闪烁。
    val focusedPath = Repo.focused().path
    androidx.compose.runtime.LaunchedEffect(focusedPath) {
        val meta = Repo.focused()
        val stateNow = Repo.spectrogram
        if (meta.path.isNotBlank() &&
            stateNow !is SpectrogramState.Working &&
            stateNow !is SpectrogramState.Ready
        ) {
            Repo.analyze(meta)
        }
        // 回放增益与频谱走的是同一条 BASS 解码路径，两者可并行（实测 12 核下
        // 447ms vs 串行 763ms），增益那点耗时藏在频谱渲染里。
        // 内部会先读文件里已有的值，没有才测 —— 不重复烧 CPU，也绝不自动写盘。
        Repo.prepareReplayGain()
    }
    DetailScaffold(
        page = SpacePage.Spectrum,
        label = L10n.t("spectrum.title"),
        interactive = interactive,
        // 进度走顶栏中间的蓝色进度条，不再用百分比文字
        progress =
            when (state) {
                is SpectrogramState.Working -> state.progress
                is SpectrogramState.Ready -> 1f
                else -> null
            },
        actions = {
            when (state) {
                is SpectrogramState.Working ->
                    TapChip(
                        label = L10n.t("action.cancel"),
                        onClick = { Repo.cancelSpectrum() },
                        accent = palette.warn,
                        enabled = interactive,
                    )

                else ->
                    TapChip(
                        label = if (state is SpectrogramState.Idle) L10n.t("spectrum.generate") else L10n.t("spectrum.regenerate"),
                        onClick = { Repo.analyze() },
                        enabled = interactive && Repo.focused().hasFile,
                    )
            }
            TapChip(
                label = L10n.t("spectrum.remeasure"),
                onClick = { Repo.measureReplayGain() },
                enabled = interactive && Repo.focused().hasFile && Repo.replayGainState !is Repo.GainState.Measuring,
            )
        },
    ) {
        // 三种状态共用同一套结构：图（weight 1）+ 固定 42dp 彩带 + 固定 26dp 页脚。
        // 状态切换只换内容，不换布局，避免生成完成时整页高度跳动。
        // 页内左右留边距，内容不要贴着窗口边
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            ReplayGainRow(interactive)
            when (state) {
                is SpectrogramState.Ready ->
                    SpectrumImage(state.image, state.displayChannels, state.sampleRate, Modifier.weight(1f))

                is SpectrogramState.Working ->
                    if (state.image != null) {
                        SpectrumImage(state.image, state.displayChannels, state.sampleRate, Modifier.weight(1f))
                    } else {
                        Box(Modifier.weight(1f).fillMaxWidth()) { Skeleton() }
                    }

                else ->
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (state is SpectrogramState.Idle) {
                            EmptyState(
                                glyph = "〜",
                                title = if (Repo.focused().hasFile) L10n.t("spectrum.empty") else L10n.t("common.noFile"),
                                hint = L10n.t("spectrum.emptyHint"),
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            val reason = (state as? SpectrogramState.Failed)?.reason.orEmpty()
                            EmptyState(
                                glyph = "✕",
                                title = L10n.t("spectrum.failed"),
                                hint = reason,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
            }

            Spacer(Modifier.height(10.dp))
            when (state) {
                is SpectrogramState.Ready -> ColorRibbon(state.envelope)
                else -> RibbonPlaceholder()
            }
        }
    }
}

/** 一张图装左右两个声道：左半 L、右半 R，各自横轴时间、纵轴频率。 */
@Composable
private fun SpectrumImage(
    image: androidx.compose.ui.graphics.ImageBitmap,
    displayChannels: Int,
    sampleRate: Int,
    modifier: Modifier = Modifier,
) {
    val palette = Salt
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(palette.card),
    ) {
        Image(
            bitmap = image,
            contentDescription = L10n.t("spectrum.chart"),
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )

        // 声道标签：各自那一半的**顶部居中**（不再压左上角，避免与刻度抢位置）
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                ChannelLabel(L10n.t("spectrum.left"))
            }
            if (displayChannels > 1) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    ChannelLabel(L10n.t("spectrum.right"))
                }
            }
        }

        // 频率刻度：**左右各一份**，镜像对称。
        // 上限 = 奈奎斯特频率（采样率/2），按采样率自适应，不再写死 16k/8k/4k。
        FrequencyScale(
            sampleRate = sampleRate,
            alignRight = false,
            modifier = Modifier.align(Alignment.CenterStart).fillMaxHeight(),
        )
        FrequencyScale(
            sampleRate = sampleRate,
            alignRight = true,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

/**
 * 自适应频率刻度。
 *
 * 算法对齐参考项目 `drawFrequencyScale`：
 * - 上限 = `sampleRate / 2000.0`，也就是 kHz 表示的奈奎斯特频率
 * - 均匀切 5 段，从 0 到上限
 * - 标签两位小数，单位 `kHz` 标在顶部
 *
 * 刻度是**线性**的（不是对数），因为频谱图本身就是按频率线性铺开的。
 *
 * @param alignRight true 时贴在图的右内侧，文字右对齐
 */
@Composable
private fun FrequencyScale(
    sampleRate: Int,
    alignRight: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = Salt
    val maximum = maxOf(1.0, sampleRate / 2000.0)
    val ticks = (0..4).map { maximum * it / 4.0 }
    val horizontal = if (alignRight) Alignment.End else Alignment.Start

    Column(
        modifier =
            modifier
                .padding(start = if (alignRight) 0.dp else 6.dp, end = if (alignRight) 6.dp else 0.dp)
                .padding(top = 20.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = horizontal,
    ) {
        // 顶部先写单位，刻度从高到低排列（图上到下 = 频率高到低）
        Txt("kHz", size = T.micro, color = palette.textTertiary, maxLines = 1)
        ticks.reversed().forEach { tick ->
            Txt(
                text = String.format(java.util.Locale.ROOT, "%.2f", tick),
                size = T.micro,
                color = palette.text,
                maxLines = 1,
            )
        }
    }
}

/** 声道标签：贴在各自那一半的顶部居中。 */
@Composable
private fun ChannelLabel(text: String) {
    val palette = Salt
    Txt(
        text = text,
        size = T.caption,
        color = palette.text,
        maxLines = 1,
        modifier = Modifier.padding(top = 3.dp),
    )
}

/**
 * 彩色包络带：色相沿时间推进，高度与不透明度随能量变化，
 * 相当于把频谱横向压成一根彩条。
 */
@Composable
private fun ColorRibbon(envelope: FloatArray) {
    val palette = Salt
    // 高度对齐参考实现：clamp(34..48)，这里取 42dp
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(palette.cardRaised)
                .border(0.5.dp, palette.border, RoundedCornerShape(8.dp))
                .drawBehind {
                    val h = size.height
                    if (h <= 2f) return@drawBehind

                    // 六段竖向渐变，**与大频谱同向**：底部橙红 → 顶部紫。
                    // 大图里 y 越大（越靠下）频率比越小、色相越靠前（红），
                    // 所以彩条也必须 bottom=橙、top=紫，否则两处观感会「上下颠倒」。
                    val stops = floatArrayOf(0f, 0.0196f, 0.1296f, 0.3844f, 0.6724f, 1f)
                    val colors = listOf(
                        Color(255, 92, 24),
                        Color(255, 218, 0),
                        Color(31, 222, 76),
                        Color(0, 190, 215),
                        Color(42, 72, 204),
                        Color(91, 27, 156),
                    )
                    // startY = 0（顶）→ endY = h（底）：0 号色（橙）在顶、末号色（紫）在底
                    val brush = Brush.verticalGradient(
                        colorStops = colors.mapIndexed { i, c -> stops[i] to c }.toTypedArray(),
                        startY = 0f,
                        endY = h,
                    )

                    if (envelope.isEmpty()) {
                        drawRoundRect(
                            color = palette.border.copy(alpha = 0.4f),
                            topLeft = Offset(0f, h - 1f),
                            size = Size(size.width, 1f),
                        )
                        return@drawBehind
                    }

                    // 每一个像素列画一根从底部起算的柱子：高度 = 包络 × (h-2)
                    val columns = size.width.toInt().coerceAtLeast(1)
                    for (x in 0 until columns) {
                        val source = ((x.toFloat() * envelope.size) / columns)
                            .toInt()
                            .coerceIn(0, envelope.size - 1)
                        val bar = maxOf(1f, envelope[source].coerceIn(0f, 1f) * (h - 2f))
                        drawRect(
                            brush = brush,
                            topLeft = Offset(x.toFloat(), h - bar),
                            size = Size(1f, bar),
                        )
                    }
                },
    )
}

@Composable
private fun RibbonPlaceholder() {
    val palette = Salt
    // 与 ColorRibbon 同高（42dp），否则生成完成的瞬间整页会跳 16dp
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(palette.cardRaised)
                .border(0.5.dp, palette.border, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Txt(L10n.t("spectrum.overviewGenerating"), size = T.micro, color = palette.textTertiary, maxLines = 1)
    }
}

@Composable
private fun Skeleton() {
    val palette = Salt
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(10.dp))
                .background(palette.card),
        contentAlignment = Alignment.Center,
    ) {
        Txt(L10n.t("spectrum.decoding"), size = T.caption, color = palette.textTertiary, maxLines = 1)
    }
}

/**
 * 频谱页顶部的回放增益行。
 *
 * 与频谱同页是因为两者都走 BASS 解码，且能**并行**（实测 12 核下两个并发解码流
 * 447ms，串行要 763ms）；增益那点耗时完全藏在频谱渲染里，进页总耗时不增加。
 *
 * 三种显示状态：
 * - 草稿里已暂存 → 显示草稿值并标「未保存」（源侧增益或本地测量都会先进草稿）
 * - 文件里已有增益 → 直接显示（播放器「音量平衡」实际会用的值）
 * - 没有 → 进页时自动测量，显示测量结果，**不写盘**
 * - 测量中 → 显示进度
 *
 * 写入一律经「保存」：进页浏览就改文件不符合预期。
 */
@Composable
private fun ReplayGainRow(interactive: Boolean) {
    val pal = Salt
    val focused = Repo.focused()
    val existing = Repo.replayGainExisting
    val measured = Repo.replayGainMeasured
    val state = Repo.replayGainState
    val hasFile = focused.hasFile
    // 草稿优先级最高：它是用户还没确认的待写入值
    val gainDraft = Drafts.replayGainDraft(focused.key)

    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(L10n.t("spectrum.replayGain"), size = T.caption, color = pal.textTertiary, maxLines = 1)
        Spacer(Modifier.width(10.dp))

        // 取值链：草稿 → 文件 → 刚测
        val text =
            when {
                gainDraft != null -> {
                    val (gain, peak) = gainDraft
                    val peakSuffix = if (peak.isNotBlank()) L10n.t("spectrum.peakSuffix", peak) else ""
                    L10n.t("spectrum.draft", gain, peakSuffix)
                }

                existing.present -> {
                    val peak = if (existing.trackPeak.isNotBlank()) L10n.t("spectrum.peakSuffix", existing.trackPeak) else ""
                    L10n.t("spectrum.existing", existing.trackGain, peak)
                }

                state is Repo.GainState.Measuring -> L10n.t("spectrum.measuring", (state.progress * 100).toInt())
                measured != null && measured.ok ->
                    L10n.t("spectrum.measured", measured.gainText(), measured.peakText())

                state is Repo.GainState.Failed -> L10n.t("spectrum.measureFailed", state.reason)
                !hasFile -> L10n.t("common.noFile")
                else -> "…"
            }
        val tone =
            when {
                // 草稿只是「待写入」，用 warn 与已落盘的 ok 区分开
                gainDraft != null -> pal.warn
                existing.present -> pal.ok
                state is Repo.GainState.Failed -> pal.error
                measured != null && measured.ok -> pal.warn
                else -> pal.textTertiary
            }
        Txt(text, size = T.label, color = tone, maxLines = 1)
        Spacer(Modifier.weight(1f))

        // 有「未落盘」的测量结果时给写入入口：只测不写，由这里显式落盘
        if (measured != null && measured.ok && !existing.present && gainDraft == null) {
            TapChip(
                label = L10n.t("spectrum.write"),
                onClick = { Repo.applyMeasuredReplayGain() },
                enabled = interactive && hasFile,
            )
        }
        // 已有草稿或已落盘时给「放弃」入口；落盘的那种走 clearReplayGainTag
        when {
            gainDraft != null ->
                TapChip(
                    label = L10n.t("spectrum.discardDraft"),
                    onClick = { Repo.discardCurrentDraft() },
                    enabled = interactive && hasFile,
                )

            existing.present ->
                TapChip(
                    label = L10n.t("spectrum.clear"),
                    onClick = { Repo.clearReplayGainTag() },
                    enabled = interactive && hasFile,
                )
        }
    }
}
