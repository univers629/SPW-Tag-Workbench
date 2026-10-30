// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.ui.cover

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.univers629.spwtagworkbench.core.EmptyState
import io.github.univers629.spwtagworkbench.core.Glyph
import io.github.univers629.spwtagworkbench.core.Salt
import io.github.univers629.spwtagworkbench.core.T
import io.github.univers629.spwtagworkbench.core.TapChip
import io.github.univers629.spwtagworkbench.core.Txt
import io.github.univers629.spwtagworkbench.core.i18n.L10n
import io.github.univers629.spwtagworkbench.core.sourceDoneFraction
import io.github.univers629.spwtagworkbench.data.CoverCandidate
import io.github.univers629.spwtagworkbench.data.CoverTile
import io.github.univers629.spwtagworkbench.data.Repo
import io.github.univers629.spwtagworkbench.router.SpacePage
import io.github.univers629.spwtagworkbench.ui.icons.IconCover
import io.github.univers629.spwtagworkbench.ui.source.SourceEmptyTile
import io.github.univers629.spwtagworkbench.ui.source.SourceLoadingTile
import io.github.univers629.spwtagworkbench.ui.space.DetailScaffold

/**
 * 封面匹配页。
 *
 * - 左栏：**预览窗格** —— 一开始显示文件里内嵌的封面（还没写入预览），
 *   只有点顶栏「写入草稿」才会换成勾选的那张候选
 * - 右栏：多源候选网格，点一下只**勾选**，不碰左栏
 * - 顶栏：返回键 + 进度条 + 「写入草稿」
 */
@Composable
fun CoverPage(interactive: Boolean) {
    val palette = Salt

    LaunchedEffect(Repo.focused().key) {
        if (Repo.focused().path.isNotBlank()) {
            Repo.loadNetworkCovers()
        }
    }

    val checked = Repo.selectedCover

    DetailScaffold(
        page = SpacePage.Cover,
        label = L10n.t("cover.title"),
        interactive = interactive,
        coverSize = 92.dp,
        progress = sourceDoneFraction(Repo.coverSourceStates()),
        actions = {
            TapChip(
                label = L10n.t("action.writeDraft"),
                onClick = { Repo.applyCover() },
                enabled = interactive && checked != null && Repo.focused().hasFile,
            )
        },
    ) {
        Row(Modifier.fillMaxSize()) {
            // ── 左：本地封面（只读展示）────────────────────────────────────
            Column(
                modifier =
                    Modifier
                        .width(320.dp)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Txt(L10n.t("cover.preview"), size = T.micro, color = palette.textTertiary, maxLines = 1)
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(palette.cardRaised)
                            .border(0.5.dp, palette.border, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    // 只画属于当前聚焦曲目的那张：封面是全局单值，切歌后会残留上一首
                    val local = if (Repo.coverBelongsToFocused()) Repo.cover else null
                    if (local != null) {
                        Image(
                            bitmap = local,
                            contentDescription = L10n.t("cover.local"),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            // 空占位用矢量图标，不再用 emoji
                            IconCover(color = palette.textTertiary, modifier = Modifier.fillMaxSize(0.34f))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Txt(
                    text = Repo.localCoverInfo.ifBlank { L10n.t("cover.none") },
                    size = T.caption,
                    color = palette.textSecondary,
                    maxLines = 1,
                )

                // 只放导入 / 导出 / 套用到同专辑；写入草稿在顶栏
                Spacer(Modifier.height(14.dp))
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TapChip(
                        label = L10n.t("action.import"),
                        onClick = {
                            pickImageFile(L10n.t("cover.pickImage")) {
                                Repo.importImage(it, artistMode = false)
                            }
                        },
                        enabled = interactive,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TapChip(
                        label = L10n.t("action.export"),
                        onClick = {
                            saveImageFile(suggestedName("cover", checked)) { path ->
                                Repo.exportCover(path, artistMode = false)
                            }
                        },
                        enabled = interactive && checked != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // 与主视图的「套用到同专辑」同一个入口：把当前曲目的专辑级字段
                    // 写进同专辑全部曲目。确认弹窗由 SpaceCanvas 根级统一渲染。
                    TapChip(
                        label = L10n.t("workbench.applyToAlbum"),
                        onClick = { Repo.requestAlbumBatch() },
                        enabled = interactive && Repo.focused().hasFile,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Box(Modifier.width(0.5.dp).fillMaxHeight().background(palette.border))

            // ── 右：多源候选网格 ────────────────────────────────────────────
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(L10n.t("cover.candidates"), size = T.heading, color = palette.text, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    if (checked != null) {
                        Txt(
                            text = L10n.t("common.checked", checked.origin),
                            size = T.caption,
                            color = palette.accent,
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))

                val slots = Repo.networkCovers
                if (slots.isEmpty()) {
                    EmptyState(
                        glyph = "◉",
                        title = if (Repo.coverLoading) L10n.t("cover.searchingNetwork") else L10n.t("cover.noResults"),
                        hint = L10n.t("common.researchOnTrackChange"),
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                    )
                } else {
                    slots.chunked(3).forEach { rowSlots ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            rowSlots.forEach { slot ->
                                when (slot) {
                                    is CoverTile.Ready ->
                                        CoverTileCard(
                                            candidate = slot.candidate,
                                            selected = slot.candidate.id == checked?.id,
                                            onClick = { Repo.selectCover(slot.candidate.id) },
                                            modifier = Modifier.weight(1f),
                                        )

                                    is CoverTile.Searching ->
                                        SourceLoadingTile(
                                            label = slot.label,
                                            index = slot.index,
                                            modifier = Modifier.weight(1f),
                                        )

                                    is CoverTile.Empty ->
                                        SourceEmptyTile(
                                            label = slot.label,
                                            modifier = Modifier.weight(1f),
                                        )
                                }
                            }
                            repeat(3 - rowSlots.size) { Spacer(Modifier.weight(1f)) }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }
    }
}

/** 一张封面候选，右下角带勾选框。单选由勾选状态（[Repo.selectedCoverId]）保证。 */
@Composable
private fun CoverTileCard(
    candidate: CoverCandidate,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = Salt
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.cardRaised)
                    .border(
                        width = if (selected) 2.dp else 0.5.dp,
                        color = if (selected) palette.accent else palette.border,
                        shape = RoundedCornerShape(10.dp),
                    ).clickable(onClick = onClick),
        ) {
            if (candidate.image != null) {
                Image(
                    bitmap = candidate.image,
                    contentDescription = candidate.origin,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    IconCover(color = palette.textTertiary, modifier = Modifier.fillMaxSize(0.4f))
                }
            }

            CheckMark(
                selected = selected,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
            )
        }
        Spacer(Modifier.height(5.dp))
        // 图下一行：左边来源、右边分辨率与大小。
        // 布局要点：**右侧信息先占位**（不参与压缩），来源拿剩下的宽度。
        // 之前用两个 weight 互相竞争，右侧一长就把来源挤成省略号。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(
                text = candidate.origin,
                size = T.micro,
                color = if (selected) palette.accent else palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Txt(
                text = candidate.info,
                size = T.micro,
                color = palette.textTertiary,
                maxLines = 1,
            )
        }
    }
}

/** 右下角勾选框。 */
@Composable
internal fun CheckMark(
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = Salt
    Box(
        modifier =
            modifier
                .width(16.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (selected) palette.accent else palette.card.copy(alpha = 0.72f))
                .border(
                    width = 1.dp,
                    color = if (selected) palette.accent else palette.border,
                    shape = RoundedCornerShape(5.dp),
                ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Txt("✓", size = Glyph.inline, color = palette.card, maxLines = 1)
    }
}

// ── 文件选择与导出（Swing 对话框，必须在 UI 线程外弹）─────────────────────

internal fun pickImageFile(
    title: String,
    onPicked: (String) -> Unit,
) {
    Thread {
        val chooser =
            javax.swing.JFileChooser().apply {
                dialogTitle = title
                fileSelectionMode = javax.swing.JFileChooser.FILES_ONLY
                isAcceptAllFileFilterUsed = true
                fileFilter =
                    object : javax.swing.filechooser.FileFilter() {
                        override fun accept(f: java.io.File): Boolean =
                            !f.isDirectory &&
                                f.extension.lowercase() in
                                setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")

                        override fun getDescription() = L10n.t("cover.imageFilter")
                    }
            }
        if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
            onPicked(chooser.selectedFile.absolutePath)
        }
    }.apply { isDaemon = true }.start()
}

internal fun saveImageFile(
    defaultName: String,
    onPath: (String) -> Unit,
) {
    Thread {
        val chooser =
            javax.swing.JFileChooser().apply {
                dialogTitle = L10n.t("cover.exportTitle")
                selectedFile = java.io.File(defaultName)
            }
        if (chooser.showSaveDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
            onPath(chooser.selectedFile.absolutePath)
        }
    }.apply { isDaemon = true }.start()
}

internal fun suggestedName(
    prefix: String,
    candidate: CoverCandidate?,
): String = "$prefix.${if (candidate?.bytes?.let { isPng(it) } == true) "png" else "jpg"}"

private fun isPng(bytes: ByteArray): Boolean = bytes.size > 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()
