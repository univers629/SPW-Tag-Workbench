// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.univers629.spwtagworkbench.core.EmptyState
import io.github.univers629.spwtagworkbench.core.Glyph
import io.github.univers629.spwtagworkbench.core.Host
import io.github.univers629.spwtagworkbench.core.MenuOptions
import io.github.univers629.spwtagworkbench.core.MenuPopup
import io.github.univers629.spwtagworkbench.core.MenuRow
import io.github.univers629.spwtagworkbench.core.Mono
import io.github.univers629.spwtagworkbench.core.Salt
import io.github.univers629.spwtagworkbench.core.T
import io.github.univers629.spwtagworkbench.core.Txt
import io.github.univers629.spwtagworkbench.core.fmtTime
import io.github.univers629.spwtagworkbench.core.i18n.L10n
import io.github.univers629.spwtagworkbench.data.Drafts
import io.github.univers629.spwtagworkbench.data.Repo
import io.github.univers629.spwtagworkbench.data.Selection
import io.github.univers629.spwtagworkbench.data.TrackMeta

/**
 * 曲库：**内嵌在宽视图左侧的一列**，不是浮层。
 *
 * 它在 Row 里占位，宽度由父级做动画（0 ↔ 320dp），内容按固定 320dp 布局、
 * 由父容器裁剪 —— 于是视觉上是「滑出」，右侧主内容被挤窄而不是变形。
 * 没有遮罩、不点外部关闭：它是并排的一列，不是模态。
 */
@Composable
fun LibraryPanel(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = Salt
    var query by remember { mutableStateOf("") }
    val library = Repo.library

    // 三个多选筛选：专辑 / 艺术家 / 流派
    var filterAlbums by remember { mutableStateOf(setOf<String>()) }
    var filterArtists by remember { mutableStateOf(setOf<String>()) }
    var filterGenres by remember { mutableStateOf(setOf<String>()) }

    val albumOptions =
        remember(library) {
            library
                .map { it.album.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()
        }
    val artistOptions =
        remember(library) {
            library
                .map { it.artist.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()
        }
    val genreOptions =
        remember(library) {
            library
                .map { it.genre.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()
        }

    val filtered =
        remember(library, query, filterAlbums, filterArtists, filterGenres) {
            val tokens = query.trim().split(" ").filter { it.isNotBlank() }
            library.filter { track ->
                // 同一筛选器内是「或」，筛选器之间是「与」
                if (filterAlbums.isNotEmpty() && track.album.trim() !in filterAlbums) return@filter false
                if (filterArtists.isNotEmpty() && track.artist.trim() !in filterArtists) return@filter false
                if (filterGenres.isNotEmpty() && track.genre.trim() !in filterGenres) return@filter false
                if (tokens.isEmpty()) return@filter true
                // 关键词匹配多个标签字段（含文件名兜底），全部命中才算
                val haystack =
                    listOf(
                        track.title,
                        track.artist,
                        track.album,
                        track.albumArtist,
                        track.genre,
                        track.path,
                    ).joinToString(" ").lowercase()
                tokens.all { haystack.contains(it.lowercase()) }
            }
        }
    // 与主视图同源：高亮的是当前聚焦曲目，而不是兜底的 selected
    val selectedKey = Repo.focused().key

    // 内嵌但不铺满：四周留距、四角圆角，像一张浮起来的卡片
    Column(
        modifier =
            modifier
                // 贴住折叠内右侧的边：左侧留距、右侧贴边
                .padding(start = 10.dp, top = 8.dp, bottom = 10.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(palette.cardOverlay)
                .border(0.5.dp, palette.border, RoundedCornerShape(14.dp)),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt(L10n.t("library.title"), size = T.heading, color = palette.text, maxLines = 1)
                Spacer(Modifier.width(8.dp))
                Txt("${filtered.size}", size = T.micro, color = palette.textTertiary, maxLines = 1)
                Spacer(Modifier.width(8.dp))
                val checkedCount = Selection.checkedCount
                if (checkedCount > 0) {
                    Txt(L10n.t("library.selected", checkedCount), size = T.micro, color = palette.accent, maxLines = 1)
                }
                Spacer(Modifier.weight(1f))
                // 全选：作用于当前搜索结果
                val visibleKeys = filtered.map { it.key }
                val allChecked = visibleKeys.isNotEmpty() && visibleKeys.all { Selection.isChecked(it) }
                Box(
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(palette.hover)
                            .clickable { Selection.setAllByUser(visibleKeys, !allChecked) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Txt(
                        text = if (allChecked) L10n.t("library.deselectAll") else L10n.t("library.selectAll"),
                        size = T.caption,
                        color = palette.textSecondary,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier =
                        Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(palette.hover)
                            .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Txt("✕", size = Glyph.inline, color = palette.textSecondary, maxLines = 1)
                }
            }
            // 筛选行：在搜索框上方，三个多选下拉（覆盖层，不推挤布局）
            FilterRow(
                albumOptions = albumOptions,
                artistOptions = artistOptions,
                genreOptions = genreOptions,
                albums = filterAlbums,
                artists = filterArtists,
                genres = filterGenres,
                onAlbums = { filterAlbums = it },
                onArtists = { filterArtists = it },
                onGenres = { filterGenres = it },
            )
            Spacer(Modifier.height(6.dp))

            // 搜索
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .height(32.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(palette.hover),
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 10.dp),
                    textStyle =
                        TextStyle(
                            color = palette.text,
                            fontSize = T.label,
                            fontFamily = Host.sans,
                        ),
                    cursorBrush = SolidColor(palette.accent),
                    singleLine = true,
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Txt(
                                    text = L10n.t("library.searchPlaceholder"),
                                    size = T.label,
                                    color = palette.textTertiary,
                                    maxLines = 1,
                                )
                            }
                            inner()
                        }
                    },
                )
            }

            Spacer(Modifier.height(8.dp))

            if (Repo.libraryLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Txt(L10n.t("library.loading"), size = T.caption, color = palette.textTertiary, maxLines = 1)
                }
            } else if (filtered.isEmpty()) {
                EmptyState(
                    glyph = "♫",
                    title = if (query.isBlank()) L10n.t("library.empty") else L10n.t("library.noMatch"),
                    hint = if (query.isBlank()) L10n.t("library.addMusicHint") else null,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    items(filtered, key = { it.key.ifEmpty { it.title + it.path } }) { item ->
                        LibraryRow(
                            track = item,
                            selected = item.key == selectedKey,
                            onClick = { Repo.select(item) },
                            onToggleCheck = { Selection.toggleByUser(item.key) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(
    track: TrackMeta,
    selected: Boolean,
    onClick: () -> Unit,
    onToggleCheck: () -> Unit,
) {
    val palette = Salt
    val selection = Selection.rowOf(track.key)
    val checked = selection?.checked == true
    // 勾选比"当前查看"低一档强调：勾选用 accent，查看用底色
    val checkColor =
        when {
            selection?.failed == true -> palette.warn
            selection?.needsConfirm == true -> palette.warn
            checked -> palette.accent
            else -> palette.borderStrong
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 1.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (selected) palette.selected else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 复选框：只负责选择，不触发"查看详情"
        Box(
            modifier =
                Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (checked) checkColor else Color.Transparent)
                    .border(1.dp, checkColor, RoundedCornerShape(5.dp))
                    .clickable(onClick = onToggleCheck),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Txt("✓", size = T.caption, color = palette.accentOn, maxLines = 1)
            }
        }
        Spacer(Modifier.width(9.dp))

        Column(Modifier.weight(1f)) {
            Txt(
                text = track.title.ifBlank { L10n.t("sidebar.unknownTitle") },
                size = T.label,
                color = if (selected) palette.text else palette.textSecondary,
                weight = if (selected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(
                    text = track.artist.ifBlank { L10n.t("sidebar.unknownArtist") },
                    size = T.micro,
                    color = palette.textTertiary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (selection?.failed == true) {
                    Spacer(Modifier.width(6.dp))
                    Txt(L10n.t("shell.failed"), size = T.caption, color = palette.warn, maxLines = 1)
                } else if (selection?.needsConfirm == true) {
                    Spacer(Modifier.width(6.dp))
                    Txt(L10n.t("library.needConfirm"), size = T.caption, color = palette.warn, maxLines = 1)
                }
                if (Drafts.draftFor(track.key) != null) {
                    Spacer(Modifier.width(6.dp))
                    Txt(L10n.t("library.pendingSave"), size = T.caption, color = palette.accent, maxLines = 1)
                }
            }
        }

        Mono(fmtTime(track.durationMs), size = T.micro, color = palette.textTertiary)
    }
}

/** 筛选行：三个多选下拉，选项来自当前曲库的标签值。 */
@Composable
private fun FilterRow(
    albumOptions: List<String>,
    artistOptions: List<String>,
    genreOptions: List<String>,
    albums: Set<String>,
    artists: Set<String>,
    genres: Set<String>,
    onAlbums: (Set<String>) -> Unit,
    onArtists: (Set<String>) -> Unit,
    onGenres: (Set<String>) -> Unit,
) {
    var expanded by remember { mutableStateOf<String?>(null) }
    val palette = Salt

    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // 每个按钮各自包一层 Box：菜单以该按钮为锚点，从下沿弹出
        Box {
            FilterChip(L10n.t("field.album"), albums.size, expanded == "album") {
                expanded = if (expanded == "album") null else "album"
            }
            MenuPopup(expanded = expanded == "album", onDismiss = { expanded = null }, width = 210.dp) {
                FilterMenuBody(albumOptions, albums, onAlbums)
            }
        }
        Box {
            FilterChip(L10n.t("field.artist"), artists.size, expanded == "artist") {
                expanded = if (expanded == "artist") null else "artist"
            }
            MenuPopup(expanded = expanded == "artist", onDismiss = { expanded = null }, width = 210.dp) {
                FilterMenuBody(artistOptions, artists, onArtists)
            }
        }
        Box {
            FilterChip(L10n.t("library.genreFilter"), genres.size, expanded == "genre") {
                expanded = if (expanded == "genre") null else "genre"
            }
            MenuPopup(expanded = expanded == "genre", onDismiss = { expanded = null }, width = 210.dp) {
                FilterMenuBody(genreOptions, genres, onGenres)
            }
        }
        if (albums.isNotEmpty() || artists.isNotEmpty() || genres.isNotEmpty()) {
            Spacer(Modifier.width(2.dp))
            Box(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .clickable {
                            onAlbums(emptySet())
                            onArtists(emptySet())
                            onGenres(emptySet())
                        }.padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                Txt(L10n.t("shell.clear"), size = T.caption, color = palette.accent, maxLines = 1)
            }
        }
    }
}

/** 筛选菜单里选项区最多显示的行数（上方「清除」是固定行，不计入），其余滚动查看。 */
private const val FILTER_MENU_MAX_ROWS = 8

@Composable
private fun FilterMenuBody(
    options: List<String>,
    selected: Set<String>,
    onChange: (Set<String>) -> Unit,
) {
    MenuRow(
        label = L10n.t("library.clearSelected", selected.size),
        showCheck = false,
        accent = Salt.accent,
        onClick = { onChange(emptySet()) },
    )
    // 选项可能上百条：最多显示 FILTER_MENU_MAX_ROWS 行，其余滚动
    MenuOptions(count = options.size, maxRows = FILTER_MENU_MAX_ROWS) {
        items(options) { value ->
            MenuRow(
                label = value,
                checked = value in selected,
                onClick = { onChange(if (value in selected) selected - value else selected + value) },
            )
        }
    }
}

/** 筛选按钮：显示已选数量，有选中时变色。 */
@Composable
private fun FilterChip(
    label: String,
    count: Int,
    active: Boolean,
    onClick: () -> Unit,
) {
    val palette = Salt
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(7.dp))
                .background(if (active || count > 0) palette.selected else palette.hover)
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Txt(
            text = if (count > 0) "$label $count ▾" else "$label ▾",
            size = T.caption,
            color = if (count > 0) palette.accent else palette.textSecondary,
            maxLines = 1,
        )
    }
}
