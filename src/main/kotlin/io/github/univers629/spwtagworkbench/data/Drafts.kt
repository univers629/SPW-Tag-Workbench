// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.univers629.spwtagworkbench.backend.model.TagField
import io.github.univers629.spwtagworkbench.core.Diag
import io.github.univers629.spwtagworkbench.core.PluginEnv
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 草稿里的图片引用。
 *
 * 为什么不直接存 `ByteArray`：一张封面动辄 200KB~2MB，用户在多个页面里挑图、
 * 而且每次切换页面都会重新搜索，若把字节长期挂在内存里，批量编辑几十首就是几十 MB。
 * 这里只记录**临时文件路径**，字节落在一个可清理的目录里，保存时才读回。
 *
 * [preview] 只用于界面显示（缩略到必要尺寸的解码结果），与 [bytes] 二选一：
 * 有临时文件时优先从文件读。
 */
data class DraftImage(
    /** 临时文件路径；[inlineBytes] 非空时可为空。 */
    val path: String? = null,
    /** 极小图（<64KB）直接驻留内存更划算，省一次磁盘往返。 */
    val inlineBytes: ByteArray? = null,
    val mime: String = "image/jpeg",
) {
    val isEmpty: Boolean get() = path.isNullOrBlank() && (inlineBytes == null || inlineBytes.isEmpty())

    /** 取回字节：优先内存，其次文件。文件读失败返回 null。 */
    fun readBytes(): ByteArray? {
        inlineBytes?.let { if (it.isNotEmpty()) return it }
        val p = path
        if (p.isNullOrBlank()) return null
        return runCatching { File(p).readBytes() }.getOrNull()
    }

    override fun equals(other: Any?): Boolean =
        other is DraftImage &&
            other.path == path &&
            other.mime == mime &&
            (other.inlineBytes == null) == (inlineBytes == null)

    override fun hashCode(): Int = (path?.hashCode() ?: 0) * 31 + mime.hashCode()
}

/**
 * 一首曲目的待提交变更。
 *
 * 界面读的是「原始值 + 草稿覆盖」的合成结果；字段只在草稿里改，不落盘。
 */
data class TrackDraft(
    val fields: Map<TagField, String> = emptyMap(),
    /** 专辑封面（ID3v2 type=3 / FLAC Front Cover）。 */
    val cover: DraftImage? = null,
    /**
     * 艺术家图，按**槽位下标**分开存（0 = 左槽，1 = 右槽）。
     *
     * 两个槽位都写 type=8（Artist），彼此独立：写槽 0 不影响槽 1，
     * 也都不影响 [cover] 的专辑封面。
     */
    val artistCovers: Map<Int, DraftImage> = emptyMap(),
    val lyrics: String? = null,
) {
    val isEmpty: Boolean
        get() = fields.isEmpty() && cover == null && artistCovers.isEmpty() && lyrics == null

    val fieldCount: Int
        get() =
            fields.size +
                (if (cover != null) 1 else 0) +
                artistCovers.size +
                (if (lyrics != null) 1 else 0)

    override fun equals(other: Any?): Boolean =
        other is TrackDraft &&
            other.fields == fields &&
            other.cover == cover &&
            other.artistCovers == artistCovers &&
            (other.lyrics == null) == (lyrics == null)

    override fun hashCode(): Int = fields.hashCode() * 31 + fieldCount
}

/**
 * 待提交变更集（草稿集）。
 *
 * 三条铁律：
 * 1. 任何编辑都不立刻写盘，只改这里
 * 2. 保存是一次批量操作，把所有草稿一次性写盘
 * 3. 保存流程顺序固定：释放音频引擎 → 写文件 → 刷新音乐库 → 重载当前播放
 *
 * 图片字节落在 [imageDir] 下的临时文件里，草稿只持有路径。
 * 放弃草稿 / 保存成功时删掉对应文件，避免越积越多。
 */
object Drafts {
    /**
     * 草稿表。
     *
     * **不能用 `mutableStateMapOf`**：它属于 Compose 快照系统，
     * 在 `onValueChange` 这类回调里处于「未提交快照」中，写入会在帧末被回滚——
     * 刚 set 进去、紧接着读回来还是旧值，连对象引用都不是同一个。
     *
     * 改成普通并发容器 + 一个独立版本号：数据可靠，重组靠 [version] 触发。
     */
    private val drafts = java.util.concurrent.ConcurrentHashMap<String, TrackDraft>()

    /** 草稿变更版本号，供 Compose 侧观察（`dirtyCount`/`dirtyKeys` 读它建立依赖）。 */
    private var version by androidx.compose.runtime.mutableStateOf(0L)

    /** 写后统一递增版本，触发界面重组。 */
    private fun bump() {
        version++
    }

    /** 图片临时目录。宿主分配给插件的数据目录下，随插件数据一起存放在用户目录里。 */
    private val imageDir: File by lazy {
        // 拿不到宿主数据目录时退回旧布局。id 为空说明宿主尚未注入，
        // 用固定段名兜底，否则会落到 workshop/data 这个多插件共享的目录上。
        val pluginSegment = PluginEnv.id.ifBlank { "unknown-plugin" }
        val base =
            PluginEnv.dataDir
                ?: File(
                    System.getenv("APPDATA") ?: System.getProperty("user.home"),
                    "Salt Player for Windows/workshop/data/$pluginSegment",
                )
        base.resolve("draft-images").also { if (!it.isDirectory) it.mkdirs() }
    }

    private val seq = AtomicLong()

    val dirtyCount: Int
        get() {
            version // 建立快照依赖，否则界面不会随草稿变化重组
            return drafts.size
        }

    val dirtyKeys: Set<String>
        get() {
            version
            return drafts.keys.toSet()
        }

    fun draftFor(trackKey: String): TrackDraft? = drafts[trackKey]

    fun isDirty(trackKey: String): Boolean = drafts[trackKey]?.isEmpty == false

    /** 暂存一个字段。value 为空表示「清空该字段」。 */
    fun stageField(
        trackKey: String,
        field: TagField,
        value: String,
    ) {
        if (trackKey.isEmpty()) return
        val current = drafts[trackKey] ?: TrackDraft()
        val fields = current.fields.toMutableMap()
        fields[field] = value.trim()
        put(trackKey, current.copy(fields = fields))
    }

    fun unstageField(
        trackKey: String,
        field: TagField,
    ) {
        val current = drafts[trackKey] ?: return
        val fields = current.fields.toMutableMap()
        fields.remove(field)
        put(trackKey, current.copy(fields = fields))
    }

    fun stageCover(
        trackKey: String,
        bytes: ByteArray,
    ) {
        val image = persistImage(trackKey, bytes, "cover") ?: return
        val current = drafts[trackKey] ?: TrackDraft()
        // 覆盖前先删掉被替换的旧图：persistImage 每次都会新建一个文件，
        // 反复「写入草稿」而不回收旧图会在临时目录里留下无人引用的孤儿
        // （依次试多张候选时最明显）。只删磁盘文件，内存内联图无文件可删。
        dropImage(current.cover)
        put(trackKey, current.copy(cover = image))
    }

    /**
     * 暂存一张艺术家图，按**槽位下标**分开存（两个槽位都写 type=8）。
     *
     * 槽位独立：写槽 0 不影响槽 1，也都不影响 [stageCover] 写入的专辑封面。
     */
    fun stageArtistCover(
        trackKey: String,
        bytes: ByteArray,
        slotIndex: Int,
    ) {
        val image = persistImage(trackKey, bytes, "artist-$slotIndex") ?: return
        val current = drafts[trackKey] ?: TrackDraft()
        val covers = current.artistCovers.toMutableMap()
        // 同上：覆盖该槽位前先回收旧文件
        dropImage(covers[slotIndex])
        covers[slotIndex] = image
        put(trackKey, current.copy(artistCovers = covers))
    }

    /**
     * 把图片字节写进临时文件，返回引用。
     *
     * 很小的图（<64KB）直接驻留内存：省掉一次写盘 + 一次读盘，
     * 而且 64KB 以内的大量小图加起来也不至于撑爆堆。
     */
    private fun persistImage(
        trackKey: String,
        bytes: ByteArray,
        kind: String,
    ): DraftImage? {
        if (trackKey.isEmpty() || bytes.isEmpty()) return null
        val mime = io.github.univers629.spwtagworkbench.backend.read.CoverMime
            .detect(bytes)
        if (bytes.size <= INLINE_LIMIT) {
            return DraftImage(inlineBytes = bytes, mime = mime)
        }
        return runCatching {
            val name = "d${seq.incrementAndGet()}-$kind-${trackKey.hashCode().toUInt()}.img"
            val file = File(imageDir, name)
            file.writeBytes(bytes)
            DraftImage(path = file.absolutePath, mime = mime)
        }.getOrNull().also {
            if (it == null) Diag.log("drafts: 图片落临时文件失败 track=${trackKey.take(12)} kind=$kind")
        }
    }

    fun stageLyrics(
        trackKey: String,
        text: String,
    ) {
        if (trackKey.isEmpty()) return
        val current = drafts[trackKey] ?: TrackDraft()
        put(trackKey, current.copy(lyrics = text))
    }

    /**
     * 撤掉歌词这一项草稿，**但保留其它字段**。
     *
     * 编辑框被清空时用：不该让一条空歌词把「保存」按钮一直点亮。
     * 若撤完草稿已空，整条移除（[TrackDraft.isEmpty] 由 put 判空处理）。
     */
    fun unstageLyrics(trackKey: String) {
        if (trackKey.isEmpty()) return
        val current = drafts[trackKey] ?: return
        put(trackKey, current.copy(lyrics = null))
    }

    /** 撤销整首曲目的草稿。 */
    fun unstage(trackKey: String) {
        drafts.remove(trackKey)?.let { dropImages(it) }
        bump()
    }

    /** 保存成功后只清掉这一首；失败的保留脏标记，用户可重试。 */
    fun markSaved(trackKey: String) {
        drafts.remove(trackKey)?.let { dropImages(it) }
        bump()
    }

    /** 全部放弃。 */
    fun clear() {
        drafts.values.forEach { dropImages(it) }
        drafts.clear()
        bump()
    }

    /** 删掉草稿引用的临时图片文件。 */
    private fun dropImages(draft: TrackDraft) {
        for (img in listOfNotNull(draft.cover) + draft.artistCovers.values) {
            dropImage(img)
        }
    }

    /** 删掉单张草稿图片对应的临时文件；内存内联图（无 path）不做任何事。 */
    private fun dropImage(image: DraftImage?) {
        val p = image?.path ?: return
        runCatching { File(p).delete() }
    }

    /** 清空整个临时目录（插件启动时调用，回收上次异常退出残留的文件）。 */
    fun sweepImageDir() {
        runCatching {
            imageDir.listFiles()?.forEach { runCatching { it.delete() } }
            Diag.log("drafts: 清理草稿图片临时目录")
        }
    }

    /** 合成预览值：原始曲目 + 草稿覆盖。 */
    fun merge(track: TrackMeta): TrackMeta {
        val draft = drafts[track.key] ?: return track
        if (draft.fields.isEmpty()) return track
        var merged = track
        for ((field, value) in draft.fields) {
            merged =
                when (field) {
                    TagField.Title -> merged.copy(title = value)
                    TagField.Artist -> merged.copy(artist = value)
                    TagField.Album -> merged.copy(album = value)
                    TagField.AlbumArtist -> merged.copy(albumArtist = value)
                    TagField.Genre -> merged.copy(genre = value)
                    TagField.Year -> merged.copy(year = value)
                    TagField.ReplayGainTrackGain -> merged.copy(replayGainTrackGain = value)
                    TagField.ReplayGainTrackPeak -> merged.copy(replayGainTrackPeak = value)
                    TagField.ReplayGainAlbumGain -> merged.copy(replayGainAlbumGain = value)
                    TagField.ReplayGainAlbumPeak -> merged.copy(replayGainAlbumPeak = value)
                    else -> merged
                }
        }
        return merged
    }

    /** 某字段的草稿值；没有草稿返回 null。 */
    fun draftValue(
        trackKey: String,
        field: TagField,
    ): String? = drafts[trackKey]?.fields?.get(field)

    /**
     * 某曲目在草稿里的 ReplayGain（音轨增益 + 峰值）。
     *
     * 读 [version] 建立 Compose 快照依赖：草稿变化要能让调用方重组，
     * 否则暂存后界面不会刷新。
     *
     * @return 没有 ReplayGain 草稿时返回 null
     */
    fun replayGainDraft(trackKey: String): Pair<String, String>? {
        version
        if (trackKey.isEmpty()) return null
        val fields = drafts[trackKey]?.fields ?: return null
        val gain = fields[TagField.ReplayGainTrackGain]?.takeIf { it.isNotBlank() } ?: return null
        val peak = fields[TagField.ReplayGainTrackPeak].orEmpty()
        return gain to peak
    }

    private fun put(
        trackKey: String,
        draft: TrackDraft,
    ) {
        if (draft.isEmpty) {
            drafts.remove(trackKey)?.let { dropImages(it) }
            bump()
        } else {
            drafts[trackKey] = draft
            bump()
        }
    }

    /** 小于此值的图片直接驻留内存，不走临时文件。 */
    private const val INLINE_LIMIT = 64 * 1024
}
