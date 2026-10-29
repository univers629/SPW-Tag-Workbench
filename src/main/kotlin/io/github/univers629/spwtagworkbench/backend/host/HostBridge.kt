// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.host

import io.github.univers629.spwtagworkbench.core.Diag

/**
 * 播放状态信号。
 *
 * 由扩展点（宿主回调）写入，后端只读。放在后端而不是 UI 层，
 * 是为了让「写前释放播放」这条硬约束不依赖任何界面组件。
 */
object PlaybackSignal {
    @Volatile
    var isPlaying: Boolean = false

    @Volatile
    var currentId: String = ""

    @Volatile
    var currentPath: String = ""
}

/**
 * 宿主交互层。
 *
 * **全项目唯一允许反射的地方。** 其余模块只调这里的函数。
 * 三件事：写前释放播放、写后刷新音乐库、写后重载当前歌曲歌词。
 */
object HostBridge {
    private const val REPO_SERVICE = "com.xuncorp.voxzen.service.RepoService"
    private const val PLAYBACK_CONTROLLER = "com.xuncorp.voxzen.service.PlaybackController"
    private const val PLAYBACK_SERVICE = "com.xuncorp.voxzen.service.PlaybackService"
    private const val LYRICS_LOADER = "com.xuncorp.voxzen.service.PlaybackLyricsLoader"
    private const val MEDIA_ITEM = "com.xuncorp.pisces.PiscesMediaItem"

    /**
     * 写入前释放宿主对目标文件的占用。
     *
     * 宿主持有正在播放文件的解码句柄，直接改会在写一半时失败或损坏。
     * 只暂停、不碰引擎本身（宿主自己的 BASS 不归插件管）。
     *
     * @return 是否已安全（不需要暂停，或暂停后确认停止播放）
     */
    fun releasePlaybackFor(
        targetPath: String,
        waitMs: Long = 700L,
    ): Boolean {
        val target = normalize(targetPath)
        if (target.isEmpty()) return false

        // 只认路径：不是正在播的这首就不打断播放
        val playingThis =
            PlaybackSignal.currentPath.isNotEmpty() &&
                normalize(PlaybackSignal.currentPath) == target

        if (!playingThis) return true

        val paused = pause()
        if (!paused) {
            // 暂停失败也要给一次机会：有些版本解码句柄与播放状态无关
            Diag.log("release: pause() 未成功，仍继续尝试写入 $target")
            return false
        }

        val deadline = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < deadline) {
            if (!PlaybackSignal.isPlaying) {
                Diag.log("release: 已暂停，放行写入")
                return true
            }
            try {
                Thread.sleep(40L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        Diag.log("release: 等待暂停超时（${waitMs}ms），仍尝试写入")
        return false
    }

    /**
     * 设置宿主播放倍速。
     *
     * 点位：`PlaybackController.changeSpeed(float)`。插件 API 未暴露倍速，
     * 打轴需要降速精确定位，所以走反射。
     *
     * @return 是否成功；失败时调用方应保持原倍速显示
     */
    fun setPlaybackSpeed(speed: Float): Boolean {
        val controller = playbackControllerOrNull() ?: return false
        return runCatching {
            controller.javaClass
                .getMethod("changeSpeed", Float::class.javaPrimitiveType)
                .invoke(controller, speed)
            Diag.log("speed: 已设为 $speed")
            true
        }.getOrElse { t ->
            Diag.log("speed: changeSpeed 失败 ${t.javaClass.simpleName}")
            false
        }
    }

    /** 读取宿主当前播放倍速；取不到返回 null。 */
    fun currentPlaybackSpeed(): Float? {
        val controller = playbackControllerOrNull() ?: return null
        return runCatching {
            controller.javaClass.getMethod("getPlaybackSpeed").invoke(controller) as? Float
        }.getOrNull()
    }

    /** 取 PlaybackController 单例；失败返回 null。 */
    private fun playbackControllerOrNull(): Any? {
        val loader = HostBridge::class.java.classLoader
        return runCatching {
            Class.forName(PLAYBACK_CONTROLLER, false, loader).getField("INSTANCE").get(null)
        }.getOrNull()
    }

    /**
     * 释放宿主的音频引擎（写盘前必须先做）。
     *
     * 点位来自开发指南 9.13：PlaybackService.releaseAudioEngine。
     * 引擎不释放时，正在播放的文件被持有句柄，删除/改名会失败或写坏。
     */
    fun releaseAudioEngine(): Boolean {
        val loader = HostBridge::class.java.classLoader
        val controller =
            try {
                Class.forName(PLAYBACK_CONTROLLER, false, loader).getField("INSTANCE").get(null)
            } catch (t: Throwable) {
                Diag.log("releaseEngine: 取 PlaybackController 失败 ${t.javaClass.simpleName}")
                return false
            }
        val service = fieldOfType(controller, PLAYBACK_SERVICE)
        if (service == null) {
            Diag.log("releaseEngine: 找不到 PlaybackService 实例")
            return false
        }
        val method =
            try {
                service.javaClass.getMethod("releaseAudioEngine")
            } catch (t: Throwable) {
                Diag.log("releaseEngine: PlaybackService 上没有 releaseAudioEngine()")
                return false
            }
        return try {
            method.invoke(service)
            Diag.log("releaseEngine: 已释放音频引擎")
            true
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            Diag.log("releaseEngine: 调用异常 ${cause.javaClass.simpleName}")
            false
        }
    }

    private fun pause(): Boolean {
        val playback =
            try {
                com.xuncorp.spw.workshop.api.WorkshopApi.playback
            } catch (t: Throwable) {
                Diag.log("release: 取 WorkshopApi.playback 失败 ${t.javaClass.simpleName}")
                return false
            }
        try {
            playback.pause()
            Diag.log("release: 已请求 pause()")
            return true
        } catch (t: Throwable) {
            Diag.log("release: pause() 抛异常 ${t.javaClass.simpleName} ${t.message.orEmpty().take(60)}")
            return false
        }
    }

    /**
     * 让宿主重读音乐库：歌单条目与列表里的元数据刷新。
     *
     * @return 是否成功触发
     */
    fun refreshLibrary(reason: String): Boolean {
        val loader = HostBridge::class.java.classLoader
        val clazz =
            try {
                Class.forName(REPO_SERVICE, false, loader)
            } catch (t: Throwable) {
                Diag.log("refresh: 找不到 $REPO_SERVICE（${t.javaClass.simpleName}）")
                return false
            }
        val instance =
            try {
                clazz.getField("INSTANCE").get(null)
            } catch (t: Throwable) {
                Diag.log("refresh: $REPO_SERVICE 无 INSTANCE 字段")
                return false
            }
        val method =
            try {
                clazz.getMethod("rebuildMusicLibrary")
            } catch (t: Throwable) {
                Diag.log("refresh: $REPO_SERVICE 无 rebuildMusicLibrary()，可用=${clazz.methods.joinToString { it.name }}")
                return false
            }
        try {
            method.invoke(instance)
            Diag.log("refresh: 已触发音乐库重读（$reason）")
            return true
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            Diag.log("refresh: rebuildMusicLibrary 异常 ${cause.javaClass.simpleName} ${cause.message.orEmpty().take(60)}")
            return false
        }
    }

    /**
     * 重载**当前播放歌曲**的歌词。
     *
     * 只刷新音乐库不会让当前播放的歌词重读：
     * 歌词在加载时被缓存，必须显式触发 loader 的 reload。
     *
     * 链路：PlaybackController.INSTANCE → 字段(PlaybackService) → 字段(PlaybackLyricsLoader) → reload(String)
     */
    fun reloadLyrics(songId: String): Boolean {
        if (songId.isEmpty()) {
            Diag.log("lyrics-reload: 缺少歌曲 id，跳过")
            return false
        }
        val loader = findLyricsLoader()
        if (loader == null) {
            Diag.log("lyrics-reload: 未取得 PlaybackLyricsLoader")
            return false
        }
        val method =
            try {
                loader.javaClass.methods.firstOrNull {
                    it.name == "reload" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
                }
            } catch (t: Throwable) {
                null
            }
        if (method == null) {
            Diag.log("lyrics-reload: loader 上没有 reload(String)")
            return false
        }
        try {
            method.invoke(loader, songId)
            Diag.log("lyrics-reload: 已请求重载 id=$songId")
            return true
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            Diag.log("lyrics-reload: reload 异常 ${cause.javaClass.simpleName} ${cause.message.orEmpty().take(60)}")
            return false
        }
    }

    private fun findLyricsLoader(): Any? {
        val loader = HostBridge::class.java.classLoader
        val controller =
            try {
                val clazz = Class.forName(PLAYBACK_CONTROLLER, false, loader)
                clazz.getField("INSTANCE").get(null)
            } catch (t: Throwable) {
                Diag.log("lyrics-reload: 取 PlaybackController.INSTANCE 失败（${t.javaClass.simpleName}）")
                return null
            }
        val service = fieldOfType(controller, PLAYBACK_SERVICE)
        if (service == null) {
            Diag.log("lyrics-reload: PlaybackController 上找不到 PlaybackService 字段")
            return null
        }
        val found = fieldOfType(service, LYRICS_LOADER)
        if (found == null) {
            Diag.log("lyrics-reload: PlaybackService 上找不到 PlaybackLyricsLoader 字段")
        }
        return found
    }

    /** 在对象自身及父类的字段里找指定类型的值。 */
    private fun fieldOfType(
        target: Any,
        typeName: String,
    ): Any? {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            for (field in cls.declaredFields) {
                if (field.type.name != typeName) continue
                try {
                    field.isAccessible = true
                    return field.get(target)
                } catch (t: Throwable) {
                    Diag.log("lyrics-reload: 读字段 ${field.name} 失败（${t.javaClass.simpleName}）")
                }
            }
            cls = cls.superclass
        }
        return null
    }

    /**
     * 让宿主切到指定曲目并开始播放。
     *
     * 照抄宿主自己的播放路径（`androidx/compose/ui/fc`）：
     *
     * ```
     * PlaybackController.INSTANCE.setPlaybackQueue(listOf(item), index)
     * PlaybackController.INSTANCE.prepare()
     * PlaybackController.INSTANCE.play()
     * ```
     *
     * 第 6 个参数是**完整路径**：宿主 `Track → PiscesMediaItem` 转换里
     * `Vri.fromPath(Track.getPath())` 保留的是完整路径字符串，
     * `getName` 只是从中取文件名用于显示，不参与播放。
     *
     * **必须主线程调用**，且设完不要立刻回读当前曲目（命令是异步投递的）。
     *
     * @param path 曲目完整路径
     * @return 是否已成功投递
     */
    fun playTrack(
        id: String,
        title: String,
        artist: String,
        album: String,
        albumArtist: String,
        path: String,
    ): Boolean {
        if (id.isEmpty() && path.isEmpty()) {
            Diag.log("play-track: 缺少 id 与 path，跳过")
            return false
        }
        val loader = HostBridge::class.java.classLoader
        val controller =
            try {
                Class.forName(PLAYBACK_CONTROLLER, false, loader).getField("INSTANCE").get(null)
            } catch (t: Throwable) {
                Diag.log("play-track: 取 PlaybackController.INSTANCE 失败（${t.javaClass.simpleName}）")
                return false
            }

        val item =
            try {
                val itemCls = Class.forName(MEDIA_ITEM, false, loader)
                // 只有这一个 6 参数构造器，没有单参数重载
                val ctor = itemCls.getConstructor(*Array(6) { String::class.java })
                ctor.newInstance(id, title, artist, album, albumArtist, path)
            } catch (t: Throwable) {
                val cause = t.cause ?: t
                Diag.log("play-track: 构造 PiscesMediaItem 失败 ${cause.javaClass.simpleName} ${cause.message.orEmpty().take(80)}")
                return false
            }

        return try {
            // 宿主序列：setPlaybackQueue(list, index) → prepare() → play()
            controller.javaClass
                .getMethod("setPlaybackQueue", java.util.List::class.java, Int::class.javaPrimitiveType)
                .invoke(controller, listOf(item), 0)
            Diag.log("play-track: 已设队列 id=$id path=$path")
            runCatching { controller.javaClass.getMethod("prepare").invoke(controller) }
                .onFailure { t -> Diag.log("play-track: prepare() 失败 ${t.javaClass.simpleName}") }
            runCatching { controller.javaClass.getMethod("play").invoke(controller) }
                .onFailure { t -> Diag.log("play-track: play() 失败 ${t.javaClass.simpleName}") }
            Diag.log("play-track: 已请求 prepare+play")
            true
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            Diag.log("play-track: setPlaybackQueue 异常 ${cause.javaClass.simpleName} ${cause.message.orEmpty().take(80)}")
            false
        }
    }

    /**
     * 只读探测：宿主曲库里这条 id 是否存在，用于区分「id 不对」与「播放链路不对」。
     *
     * 读 `PlaybackController.playbackQueueState` 当前队列里的曲目 id 集合。
     * id getter 是**混淆名**，按宿主版本从 [HostPointResolver] 取，
     * 取不到就返回空列表（不猜名字）。
     */
    fun hostQueueIds(): List<String> =
        try {
            val getter =
                HostPointResolver.resolveMediaItemIdGetter()
                    ?: return emptyList()
            val loader = HostBridge::class.java.classLoader
            val pc = Class.forName(PLAYBACK_CONTROLLER, false, loader).getField("INSTANCE").get(null)
            val state = pc.javaClass.getMethod("getPlaybackQueueState").invoke(pc)
            val value = state?.javaClass?.getMethod("getValue")?.invoke(state)
            val items = value?.javaClass?.getMethod("getMediaItems")?.invoke(value) as? List<*>
            items
                ?.mapNotNull { mi ->
                    runCatching { mi?.javaClass?.getMethod(getter)?.invoke(mi) as? String }.getOrNull()
                }.orEmpty()
        } catch (t: Throwable) {
            Diag.log("queue-ids: 读取失败 ${t.javaClass.simpleName}")
            emptyList()
        }

    private fun normalize(path: String): String = path.replace('/', '\\').trim().lowercase()
}
