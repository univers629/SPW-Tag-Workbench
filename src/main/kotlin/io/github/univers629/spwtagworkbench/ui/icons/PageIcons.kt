// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.ui.icons

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser

// 换算基准：以 deepseek-harness 的 ui-primitives 图标为标准，
// viewBox 16、中等线宽 1.3、圆头圆角 → 比例 1.3 / 16

/**
 * 主界面全部矢量图标（Compose Canvas 自绘）。
 *
 * ## 统一规范
 * | 项 | 值 |
 * | --- | --- |
 * | 线宽 | 全部 `s * 0.10` |
 * | 描边外框 | 起点 (0.14, 0.16)、尺寸 0.72 × 0.68、圆角 0.16 |
 * | 安全区 | 框内元素再内缩 0.08 |
 * | 端帽 | 全部 StrokeCap.Round |
 * | 视觉占位 | 内容落在 0.16–0.84，保证同一排视觉大小一致 |
 *
 * 频谱与打轴取自参考项目 SyncIcons.spectrum / SyncIcons.waveform 的形状语言；
 * 其余为本项目自绘。
 */
private const val STROKE = 1.3f / 16f
private const val FRAME_LEFT = 0.14f
private const val FRAME_TOP = 0.16f
private const val FRAME_W = 0.72f
private const val FRAME_H = 0.68f
private const val FRAME_RADIUS = 0.16f

// ── 分页图标 ────────────────────────────────────────────────────────────────

/** 频谱：五根圆角竖柱，左右对称、落在统一安全区内。 */
@Composable
fun IconSpectrum(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val s = minOf(size.width, size.height)
        val heights = floatArrayOf(0.28f, 0.52f, 0.68f, 0.46f, 0.60f)
        val barW = s * 0.11f
        val gap = (s * FRAME_W - barW * heights.size) / (heights.size - 1)
        val bottom = s * 0.84f
        heights.forEachIndexed { i, h ->
            val bh = s * h
            drawRoundRect(
                color = color,
                topLeft = Offset(s * FRAME_LEFT + i * (barW + gap), bottom - bh),
                size = Size(barW, bh),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f),
            )
        }
    }
}

/** 打轴：穿过中轴的波形折线。 */
@Composable
fun IconTimeline(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val s = minOf(size.width, size.height)
        val points = listOf(
            0.14f to 0.50f,
            0.24f to 0.50f,
            0.32f to 0.26f,
            0.42f to 0.74f,
            0.52f to 0.34f,
            0.62f to 0.66f,
            0.72f to 0.50f,
            0.86f to 0.50f,
        )
        val path = Path().apply {
            points.forEachIndexed { index, (px, py) ->
                if (index == 0) moveTo(s * px, s * py) else lineTo(s * px, s * py)
            }
        }
        drawPath(path = path, color = color, style = Stroke(width = s * STROKE, cap = StrokeCap.Round))
    }
}

/** 信息：圆环 + 中间一个小写 i（详情页通用图标）。 */
@Composable
fun IconInfo(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val s = minOf(size.width, size.height)
        drawCircle(
            color = color,
            radius = s * 0.36f,
            center = center,
            style = Stroke(width = s * STROKE),
        )
        // 小写 i：上方圆点 + 下方竖线
        drawCircle(
            color = color,
            radius = s * 0.035f,
            center = Offset(s * 0.5f, s * 0.30f),
        )
        drawLine(
            color = color,
            start = Offset(s * 0.5f, s * 0.42f),
            end = Offset(s * 0.5f, s * 0.70f),
            strokeWidth = s * STROKE,
            cap = StrokeCap.Round,
        )
    }
}

/** 歌词：描边外框 + 三行无圆点的文字线。 */
@Composable
fun IconLyrics(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val s = minOf(size.width, size.height)
        drawRoundRect(
            color = color,
            topLeft = Offset(s * FRAME_LEFT, s * FRAME_TOP),
            size = Size(s * FRAME_W, s * FRAME_H),
            cornerRadius = CornerRadius(s * FRAME_RADIUS, s * FRAME_RADIUS),
            style = Stroke(width = s * STROKE),
        )
        val rows = listOf(0.26f to 0.74f, 0.26f to 0.66f, 0.26f to 0.54f)
        rows.forEachIndexed { i, (from, to) ->
            val y = s * (0.34f + i * 0.16f)
            drawLine(
                color = color,
                start = Offset(s * from, y),
                end = Offset(s * to, y),
                strokeWidth = s * STROKE,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** 封面：描边相框 + 太阳圆点 + 折线山。 */
@Composable
fun IconCover(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val s = minOf(size.width, size.height)
        drawRoundRect(
            color = color,
            topLeft = Offset(s * FRAME_LEFT, s * FRAME_TOP),
            size = Size(s * FRAME_W, s * FRAME_H),
            cornerRadius = CornerRadius(s * FRAME_RADIUS, s * FRAME_RADIUS),
            style = Stroke(width = s * STROKE),
        )
        drawCircle(color = color, radius = s * 0.07f, center = Offset(s * 0.36f, s * 0.38f))
        val mountain = Path().apply {
            moveTo(s * 0.24f, s * 0.72f)
            lineTo(s * 0.42f, s * 0.52f)
            lineTo(s * 0.55f, s * 0.64f)
            lineTo(s * 0.66f, s * 0.55f)
            lineTo(s * 0.78f, s * 0.72f)
        }
        drawPath(path = mountain, color = color, style = Stroke(width = s * STROKE, cap = StrokeCap.Round))
    }
}

/** 艺术家图：圆头 + 肩弧（无外框，落在安全区内）。 */
@Composable
fun IconArtist(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val s = minOf(size.width, size.height)
        drawCircle(
            color = color,
            radius = s * 0.15f,
            center = Offset(s * 0.50f, s * 0.36f),
            style = Stroke(width = s * STROKE),
        )
        val shoulder = Path().apply {
            moveTo(s * 0.22f, s * 0.82f)
            quadraticTo(s * 0.50f, s * 0.52f, s * 0.78f, s * 0.82f)
        }
        drawPath(path = shoulder, color = color, style = Stroke(width = s * STROKE, cap = StrokeCap.Round))
    }
}

// ── 工具栏图标 ──────────────────────────────────────────────────────────────

/**
 * 日志：带圆点的清单（dot-list，无外框）。
 *
 * 与歌词图标区分：歌词是描边外框 + 无圆点文字线，日志是无外框 + 每行一个圆点。
 */
@Composable
fun IconLog(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val s = minOf(size.width, size.height)
        listOf(0.34f, 0.50f, 0.66f).forEach { y ->
            drawCircle(color = color, radius = s * 0.055f, center = Offset(s * 0.24f, s * y))
            drawLine(
                color = color,
                start = Offset(s * 0.40f, s * y),
                end = Offset(s * 0.80f, s * y),
                strokeWidth = s * STROKE,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** 曲库：描边外框 + 左侧竖分隔 + 三条书目线。 */
@Composable
fun IconLibrary(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val s = minOf(size.width, size.height)
        drawRoundRect(
            color = color,
            topLeft = Offset(s * FRAME_LEFT, s * FRAME_TOP),
            size = Size(s * FRAME_W, s * FRAME_H),
            cornerRadius = CornerRadius(s * FRAME_RADIUS, s * FRAME_RADIUS),
            style = Stroke(width = s * STROKE),
        )
        drawLine(
            color = color,
            start = Offset(s * 0.38f, s * FRAME_TOP),
            end = Offset(s * 0.38f, s * (FRAME_TOP + FRAME_H)),
            strokeWidth = s * STROKE,
        )
        listOf(0.34f to 0.78f, 0.50f to 0.72f, 0.66f to 0.78f).forEach { (y, to) ->
            drawLine(
                color = color,
                start = Offset(s * 0.50f, s * y),
                end = Offset(s * to, s * y),
                strokeWidth = s * STROKE,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * 设置：深探工坊（deepseek-harness）同款齿轮。
 *
 * 采用 ui-primitives/src/icons/index.tsx 中 IconSettingsOutline 的两条 SVG 路径
 * （16 格视图、线宽 1.3、外轮廓带四个圆角凸耳 + 中心圆孔），
 * 与宿主工作台图标体系同源，不再自创齿轮。
 */
@Composable
fun IconSettings(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val s = minOf(size.width, size.height)
        val u = s / 16f
        // 齿轮用 harness 的 regular 线宽 1.0（比其它图标的中等档 1.3 更细）
        val stroke = Stroke(width = 1.0f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        withTransform({ scale(u, u, pivot = Offset.Zero) }) {
            drawPath(path = GEAR_HOLE, color = color, style = stroke)
            drawPath(path = GEAR_BODY, color = color, style = stroke)
        }
    }
}

/** 齿轮中心圆孔（SVG 16 格坐标）。 */
private val GEAR_HOLE: Path by lazy {
    PathParser()
        .parsePathString(
            "M8 9.75012C8.9665 9.75012 9.75 8.96662 9.75 8.00012C9.75 7.03362 8.9665 6.25012 8 6.25012C7.0335 6.25012 6.25 7.03362 6.25 8.00012C6.25 8.96662 7.0335 9.75012 8 9.75012Z",
        ).toPath()
}

/** 齿轮外轮廓（SVG 16 格坐标，来自 harness IconSettingsOutline）。 */
private val GEAR_BODY: Path by lazy {
    PathParser()
        .parsePathString(
            "M13.0107 7.79377C12.9505 7.89401 12.9205 7.94413 12.9205 7.99951C12.9205 8.0549 12.9505 8.10502 13.0106 8.20528L13.9849 9.83006C14.045 9.93029 14.0751 9.9804 14.0751 10.0358C14.0751 10.0911 14.045 10.1413 13.9849 10.2415L13.0037 11.8777C12.9468 11.9726 12.9184 12.0201 12.8725 12.0461C12.8267 12.072 12.7713 12.072 12.6607 12.072H10.6704C10.5598 12.072 10.5045 12.072 10.4586 12.098C10.4128 12.1239 10.3843 12.1714 10.3274 12.2662L9.33825 13.9142C9.28133 14.009 9.25287 14.0564 9.20703 14.0823C9.16118 14.1083 9.10588 14.1083 8.99529 14.1083H7.00486C6.89426 14.1083 6.83896 14.1083 6.79312 14.0823C6.74727 14.0564 6.71881 14.009 6.6619 13.9142L5.67273 12.2662C5.61581 12.1714 5.58735 12.1239 5.54151 12.098C5.49566 12.072 5.44036 12.072 5.32977 12.072H3.33945C3.2288 12.072 3.17347 12.072 3.12761 12.0461C3.08176 12.0201 3.0533 11.9726 2.9964 11.8777L2.0152 10.2415C1.9551 10.1413 1.92505 10.0911 1.92505 10.0358C1.92505 9.9804 1.9551 9.93029 2.0152 9.83006L2.98951 8.20528C3.04963 8.10502 3.07969 8.0549 3.07969 7.99951C3.07968 7.94413 3.04961 7.89401 2.98946 7.79377L2.01529 6.17011C1.95514 6.06987 1.92507 6.01975 1.92507 5.96437C1.92506 5.90899 1.95512 5.85886 2.01524 5.7586L2.9964 4.1224C3.0533 4.0275 3.08176 3.98005 3.12761 3.95408C3.17347 3.92811 3.2288 3.92811 3.33945 3.92811H5.32977C5.44036 3.92811 5.49566 3.92811 5.54151 3.90216C5.58735 3.87621 5.61581 3.82879 5.67273 3.73397L6.6619 2.08599C6.71881 1.99116 6.74727 1.94375 6.79312 1.9178C6.83896 1.89185 6.89426 1.89185 7.00486 1.89185H8.99529C9.10588 1.89185 9.16118 1.89185 9.20703 1.9178C9.25287 1.94375 9.28133 1.99116 9.33825 2.08599L10.3274 3.73397C10.3843 3.82879 10.4128 3.87621 10.4586 3.90216C10.5045 3.92811 10.5598 3.92811 10.6704 3.92811H12.6607C12.7713 3.92811 12.8267 3.92811 12.8725 3.95408C12.9184 3.98005 12.9468 4.0275 13.0037 4.1224L13.9849 5.7586C14.045 5.85886 14.0751 5.90899 14.0751 5.96437C14.0751 6.01975 14.045 6.06987 13.9849 6.17011L13.0107 7.79377Z",
        ).toPath()
} // ── 小控件图标（同样遵守统一线宽）────────────────────────────────────────────

@Composable
fun IconChevronDown(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Chevron(color, modifier, listOf(0.26f to 0.42f, 0.50f to 0.58f, 0.74f to 0.42f))
}

@Composable
fun IconChevronRight(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Chevron(color, modifier, listOf(0.42f to 0.26f, 0.58f to 0.50f, 0.42f to 0.74f))
}

@Composable
fun IconChevronLeft(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Chevron(color, modifier, listOf(0.58f to 0.26f, 0.42f to 0.50f, 0.58f to 0.74f))
}

/** 三点折线箭头（左/右/下共用）。points 为 (x, y)。 */
@Composable
private fun Chevron(
    color: Color,
    modifier: Modifier,
    points: List<Pair<Float, Float>>,
) {
    Canvas(modifier) {
        val s = minOf(size.width, size.height)
        val path = Path().apply {
            points.forEachIndexed { index, (px, py) ->
                if (index == 0) moveTo(s * px, s * py) else lineTo(s * px, s * py)
            }
        }
        drawPath(path = path, color = color, style = Stroke(width = s * STROKE, cap = StrokeCap.Round))
    }
}

/** 加号。 */
@Composable
fun IconPlus(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val s = minOf(size.width, size.height)
        drawLine(
            color = color,
            start = Offset(s * 0.22f, s * 0.50f),
            end = Offset(s * 0.78f, s * 0.50f),
            strokeWidth = s * STROKE,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(s * 0.50f, s * 0.22f),
            end = Offset(s * 0.50f, s * 0.78f),
            strokeWidth = s * STROKE,
            cap = StrokeCap.Round,
        )
    }
}

/** 音符：符干 + 符头。 */
@Composable
fun IconMusicNote(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val s = minOf(size.width, size.height)
        drawLine(
            color = color,
            start = Offset(s * 0.58f, s * 0.20f),
            end = Offset(s * 0.58f, s * 0.66f),
            strokeWidth = s * STROKE,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(s * 0.58f, s * 0.20f),
            end = Offset(s * 0.78f, s * 0.26f),
            strokeWidth = s * STROKE,
            cap = StrokeCap.Round,
        )
        drawCircle(color = color, radius = s * 0.12f, center = Offset(s * 0.46f, s * 0.66f))
    }
}

/**
 * 音乐标签：细长标签 + 半内嵌八分音符。
 *
 * 造型要点（都在 16 格视图内）：
 * - 标签细长（高 6.8、宽 10.3），右侧收尖头，左侧圆角，几乎占满画布（等比放大）；
 * - 音符不做完整内嵌：符头在标签内，符干从标签上沿探出；
 * - 音符与标签描边之间**处处留 0.35 单位以上的空隙**：上沿在符干处断开，
 *   符头与下沿之间也留出明显缝隙，避免看起来"线条连在一起"。
 */
@Composable
fun IconMusicTag(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val s = minOf(size.width, size.height)
        val u = s / 16f
        val w = 1.1f * u
        val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)

        // 标签轮廓：占满 2.0–14.2 × 4.6–11.4；上沿在符干处断开
        val tag = Path().apply {
            moveTo(11.85f * u, 4.6f * u)
            lineTo(12.2f * u, 4.6f * u)
            lineTo(14.2f * u, 8.0f * u) // 右尖头
            lineTo(12.2f * u, 11.4f * u)
            lineTo(3.0f * u, 11.4f * u)
            quadraticTo(2.0f * u, 11.4f * u, 2.0f * u, 10.4f * u)
            lineTo(2.0f * u, 5.6f * u)
            quadraticTo(2.0f * u, 4.6f * u, 3.0f * u, 4.6f * u)
            lineTo(10.15f * u, 4.6f * u) // 收笔在缺口左侧
        }
        drawPath(tag, color = color, style = stroke)

        // 圆孔（穿绳孔）
        drawCircle(color = color, radius = 0.62f * u, center = Offset(4.5f * u, 8.0f * u), style = stroke)

        // 八分音符：符头在标签内，符干从缺口探出上沿
        drawLine(
            color = color,
            start = Offset(11.0f * u, 3.0f * u),
            end = Offset(11.0f * u, 8.6f * u),
            strokeWidth = w,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(11.0f * u, 3.0f * u),
            end = Offset(12.5f * u, 3.5f * u),
            strokeWidth = w,
            cap = StrokeCap.Round,
        )
        drawCircle(color = color, radius = 0.7f * u, center = Offset(10.2f * u, 8.9f * u), style = stroke)
    }
}
