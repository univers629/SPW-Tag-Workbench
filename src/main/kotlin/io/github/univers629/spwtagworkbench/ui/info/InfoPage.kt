// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.ui.info

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.github.univers629.spwtagworkbench.backend.read.PictureInfo
import io.github.univers629.spwtagworkbench.backend.read.TagDump
import io.github.univers629.spwtagworkbench.backend.read.TagDumpReader
import io.github.univers629.spwtagworkbench.backend.read.TagFieldRow
import io.github.univers629.spwtagworkbench.core.Card
import io.github.univers629.spwtagworkbench.core.Salt
import io.github.univers629.spwtagworkbench.core.T
import io.github.univers629.spwtagworkbench.core.TapChip
import io.github.univers629.spwtagworkbench.core.Txt
import io.github.univers629.spwtagworkbench.core.i18n.L10n
import io.github.univers629.spwtagworkbench.data.Repo
import io.github.univers629.spwtagworkbench.router.SpacePage
import io.github.univers629.spwtagworkbench.ui.space.DetailScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 信息页：只读展示当前曲目的标签代码与全部内嵌图片。
 *
 * 左侧是文本卡列（文件信息 + 每个标签体系一张卡），右侧是图片卡；
 * 两栏 1:1。唯一的写操作是删除非法类型的图片。
 */
@Composable
fun InfoPage(interactive: Boolean) {
    val pal = Salt
    val meta = Repo.focused()
    val scope = rememberCoroutineScope()
    var dump by remember { mutableStateOf<TagDump?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(meta.path, reload, Repo.infoRefreshTick) {
        deleting = false
        if (meta.path.isBlank()) {
            dump = null
            failed = false
            return@LaunchedEffect
        }
        loading = true
        failed = false
        val path = meta.path
        val result =
            withContext(Dispatchers.IO) {
                runCatching {
                    TagDumpReader.read(path) { stage ->
                        // 分段回填：每段读完就铺卡，不必等图片缩略图全部解码
                        scope.launch { dump = stage }
                    }
                }.getOrNull()
            }
        dump = result
        failed = result == null
        loading = false
    }

    DetailScaffold(
        page = SpacePage.Info,
        label = L10n.t("info.title"),
        interactive = interactive,
        actions = {
            TapChip(
                label = L10n.t("info.refresh"),
                onClick = { reload++ },
                enabled = interactive && !deleting,
                accent = pal.accent,
            )
        },
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 左：内嵌图片侧栏。宽度与高度固定，滚动由卡片内部的列表承担，
            // 每行一张图。
            //
            // 本列不加 verticalScroll：滚动容器会把高度约束变为无界，
            // 卡片上的 fillMaxHeight 与列表的 weight 都没有可分配的有限空间，
            // 图片行会被压成 0 高度而不可见。
            Column(
                modifier =
                    Modifier
                        .width(232.dp)
                        .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PicturesCard(
                    pictures = dump?.pictures.orEmpty(),
                    loading = loading,
                    deleting = deleting,
                    onDelete = { picture ->
                        // 只标记「删除中」：真正重读由 Repo.infoRefreshTick 触发，
                        // 避免在写盘完成前就先读一遍旧列表
                        deleting = true
                        Repo.removePicture(picture.index, picture.type, picture.byteSize)
                    },
                    enabled = interactive,
                )
            }

            // 右：文本卡列，占据剩余宽度
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    loading && dump == null -> InfoCard(title = L10n.t("info.reading"), rows = emptyList())
                    failed -> InfoCard(title = L10n.t("info.readFailed"), rows = emptyList(), accent = pal.error)
                    dump != null -> {
                        val current = dump ?: return@Column
                        FileCard(current)
                        for (section in current.sections) {
                            InfoCard(title = section.title, subtitle = section.subtitle, rows = section.rows)
                        }
                        if (current.sections.isEmpty()) {
                            InfoCard(title = L10n.t("info.tagsCard"), rows = emptyList())
                        }
                        if (current.blocks.isNotEmpty()) {
                            InfoCard(
                                title = L10n.t("info.blocksCard"),
                                rows =
                                    current.blocks.map {
                                        TagFieldRow(
                                            key = it.name,
                                            value = it.detail,
                                            byteSize = it.byteSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                                        )
                                    },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 文件信息卡：容器、时长、码率、大小。 */
@Composable
private fun FileCard(dump: TagDump) {
    val rows =
        buildList {
            add(TagFieldRow("container", dump.container))
            if (dump.audio.format.isNotBlank()) add(TagFieldRow("format", dump.audio.format))
            if (dump.audio.encoding.isNotBlank()) add(TagFieldRow("encoding", dump.audio.encoding))
            add(TagFieldRow("duration", formatDuration(dump.durationMs)))
            add(TagFieldRow("sampleRate", if (dump.sampleRate > 0) "${dump.sampleRate} Hz" else "-"))
            if (dump.audio.bitsPerSample > 0) add(TagFieldRow("bitDepth", "${dump.audio.bitsPerSample} bit"))
            if (dump.bitRateKbps > 0) add(TagFieldRow("bitRate", "${dump.bitRateKbps} kbps"))
            add(TagFieldRow("channels", if (dump.channels > 0) dump.channels.toString() else "-"))
            add(TagFieldRow("lossless", if (dump.audio.lossless) "true" else "false"))
            add(TagFieldRow("variableBitRate", if (dump.audio.variableBitRate) "true" else "false"))
            add(TagFieldRow("size", formatSize(dump.sizeBytes)))
            add(TagFieldRow("path", dump.path))
        }
    InfoCard(title = dump.fileName, rows = rows)
}

/** 文本卡：标题 + 键值行（等宽字体，只读）。 */
@Composable
private fun InfoCard(
    title: String,
    subtitle: String = "",
    rows: List<TagFieldRow>,
    accent: Color? = null,
) {
    val pal = Salt
    Card(
        modifier = Modifier.fillMaxWidth(),
        color = pal.card,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(text = title, size = T.heading, color = accent ?: pal.text, maxLines = 1)
                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.width(10.dp))
                    Txt(text = subtitle, size = T.caption, color = pal.textTertiary, maxLines = 1)
                }
            }
            if (rows.isEmpty()) {
                Txt(text = "-", size = T.caption, color = pal.textTertiary, maxLines = 1)
            }
            for (row in rows) {
                Row(Modifier.fillMaxWidth()) {
                    Txt(
                        text = row.key,
                        size = T.caption,
                        color = pal.textTertiary,
                        modifier = Modifier.width(104.dp),
                        maxLines = 1,
                    )
                    Txt(
                        text = row.value,
                        size = T.caption,
                        color = pal.textSecondary,
                        modifier = Modifier.weight(1f),
                        maxLines = Int.MAX_VALUE,
                    )
                    if (row.byteSize > 0) {
                        Txt(
                            text = formatSize(row.byteSize.toLong()),
                            size = T.caption,
                            color = pal.textTertiary,
                            modifier = Modifier.width(64.dp),
                            maxLines = 1,
                            align = androidx.compose.ui.text.style.TextAlign.End,
                        )
                    }
                }
            }
        }
    }
}

/** 图片卡：固定高度侧栏，内部滚动；每行一张图。 */
@Composable
private fun PicturesCard(
    pictures: List<PictureInfo>,
    loading: Boolean,
    deleting: Boolean,
    onDelete: (PictureInfo) -> Unit,
    enabled: Boolean,
) {
    val pal = Salt
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
        color = pal.card,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(text = L10n.t("info.picturesCard"), size = T.heading, color = pal.text, maxLines = 1)
                Spacer(Modifier.width(10.dp))
                Txt(
                    text =
                        if (loading) {
                            L10n.t("info.reading")
                        } else {
                            L10n.t("info.pictureCount", pictures.size)
                        },
                    size = T.caption,
                    color = pal.textTertiary,
                    maxLines = 1,
                )
            }
            // 卡片本身顶到上下边界；图片列表在这里内部滚动，不随数量撑高页面
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!loading && pictures.isEmpty()) {
                    Txt(text = L10n.t("info.noPictures"), size = T.caption, color = pal.textTertiary, maxLines = 1)
                }
                for (picture in pictures) {
                    PictureCell(
                        picture = picture,
                        deleting = deleting,
                        onDelete = onDelete,
                        enabled = enabled,
                    )
                }
            }
        }
    }
}

@Composable
private fun PictureCell(
    picture: PictureInfo,
    deleting: Boolean,
    onDelete: (PictureInfo) -> Unit,
    enabled: Boolean,
) {
    val pal = Salt
    Card(
        modifier = Modifier.fillMaxWidth(),
        color = if (picture.valid) pal.cardRaised else pal.warn.copy(alpha = 0.14f),
        radius = 10.dp,
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(pal.hover),
                contentAlignment = Alignment.Center,
            ) {
                val image = picture.thumbnail
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = picture.typeLabel,
                        contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.Medium,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Txt(text = picture.mime, size = T.caption, color = pal.textTertiary, maxLines = 1)
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Txt(
                    text = L10n.t("info.pictureType", picture.type, picture.typeLabel),
                    size = T.caption,
                    color = if (picture.valid) pal.textSecondary else pal.warn,
                    maxLines = 1,
                )
                Txt(
                    text = "${picture.width} × ${picture.height}",
                    size = T.caption,
                    color = pal.textTertiary,
                    maxLines = 1,
                )
                Txt(
                    text = formatSize(picture.byteSize.toLong()),
                    size = T.caption,
                    color = pal.textTertiary,
                    maxLines = 1,
                )
                if (picture.description.isNotBlank()) {
                    Txt(text = picture.description, size = T.caption, color = pal.textTertiary, maxLines = 1)
                }
                if (!picture.valid) {
                    TapChip(
                        label = L10n.t("info.deletePicture"),
                        onClick = { onDelete(picture) },
                        enabled = enabled && !deleting,
                        accent = pal.warn,
                    )
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String =
    when {
        bytes >= 1024L * 1024L -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(java.util.Locale.ROOT, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "-"
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
