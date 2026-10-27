// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xuncorp.spw.workshop.api.WorkshopApi

/**
 * 插件设置。读写宿主提供的配置文件，并在设置页改动时实时同步到界面。
 */
object Settings {
    var sidebarRail by mutableStateOf(true)
        private set

    /**
     * 是否在宿主顶部标题栏显示插件的「召唤按钮」。
     *
     * 由宿主「模组管理」页的 `titlebar.entry` 开关控制；关闭时按钮不绘制，
     * 但注册仍然保留 —— 反复装卸钩子比「画个空按钮」更容易出问题。
     */
    var titlebarEntry by mutableStateOf(true)
        private set

    /**
     * 顶部按钮排在宿主 Quick 按钮的哪一侧：`left`（默认）或 `right`。
     *
     * 由宿主「模组管理」页的 `titlebar.position` 列表项控制。这是**插入点**的选择，
     * 不是像素坐标：宿主标题栏是固定结构的 Row，插件只能挑「挂在哪个兄弟之后」。
     */
    var titlebarPosition by mutableStateOf("left")
        private set

    /**
     * 顶部按钮**点击时做什么**：`workbench`（默认）或 `sidebar`。
     *
     * 由宿主「模组管理」页的 `titlebar.action` 列表项控制。
     *
     * 按钮本身**没有下拉菜单**：单击就执行这里选定的动作。
     * 选择放在宿主设置页，而不是点开菜单再选 —— 标题栏按钮应当一键直达。
     */
    var titlebarAction by mutableStateOf(TITLEBAR_ACTION_WORKBENCH)
        private set

    /**
     * 主视图是否显示封面下方那两个「艺术家封面」小槽。
     *
     * 由宿主「模组管理」页的 `view.showArtistSlots` 开关控制——插件的所有设置项
     * 统一在那里改，插件自己不再维护一份界面。这里只是把值读进内存供界面使用。
     */
    var showArtistSlots by mutableStateOf(true)

    /**
     * 批量匹配时「已有标签」的处理策略，取值见 [io.github.univers629.spwtagworkbench.data.MatchingPolicy]。
     *
     * 由宿主「模组管理」页的 `matching.policy` 列表项控制。
     */
    var matchingPolicy by mutableStateOf("fill")
        private set

    /**
     * 写 MP3 等 ID3v2 容器时使用的标签版本与文本编码。
     *
     * 3 = ID3v2.3 + UTF-16，4 = ID3v2.4 + UTF-8；由宿主「模组管理」页的
     * `tags.id3v2` 列表项控制，默认 3（旧播放器兼容性更好）。
     */
    var id3v2Version by mutableStateOf(3)
        internal set

    /**
     * 强制不透明：`replaceComposable` 失败时置位。
     * 那种情况下宿主内容还在自己画，让页面透明不会透出材质，只会露出宿主 UI。
     */
    var forceOpaque by mutableStateOf(false)

    /**
     * 各音源启用状态。
     *
     * 默认值与 `preference_config.json` 里声明的 `sources.<id>.enabled` 保持一致；
     * 真实值由 [read] 从配置读入，宿主「模组管理」页改完即生效。
     */
    private val sourceEnabled =
        mutableStateMapOf(
            "itunes" to true,
            "lrclib" to true,
            "netease" to true,
            "qqmusic" to true,
            "kugou" to true,
        )

    fun isSourceEnabled(id: String): Boolean = sourceEnabled[id] ?: true

    /** 源开关变化标记：聚合器在下一次取可用源时消费并清空检索缓存。 */
    private val sourceToggleFlag = java.util.concurrent.atomic
        .AtomicBoolean(false)

    /** 取出并清除「源开关已变化」标记。 */
    fun consumeSourceToggle(): Boolean = sourceToggleFlag.getAndSet(false)

    fun setSourceEnabled(
        id: String,
        enabled: Boolean,
    ) {
        sourceEnabled[id] = enabled
        sourceToggleFlag.set(true)
        runCatching {
            val config = helper ?: return@runCatching
            config.set("sources.$id.enabled", enabled)
            config.save()
        }
    }

    private var helper: com.xuncorp.spw.workshop.api.config.ConfigHelper? = null
    private var listenerRegistered = false

    fun load() {
        runCatching {
            val manager = WorkshopApi.manager.createConfigManager()
            // 与 preference_config.json 里声明的 config 文件名一致
            val config = manager.getConfig("config.json").also { runCatching { it.reload() } }
            helper = config
            read(config)
            if (!listenerRegistered) {
                listenerRegistered = true
                manager.addConfigChangeListener { changed -> read(changed) }
            }
        }
    }

    /**
     * 重新从磁盘读一次配置。
     *
     * 宿主配置页的写入**不会**触发 [addConfigChangeListener]——那个回调只在
     * 插件自己 `save()` 时来。所以从宿主配置页改完开关回到工作台时，
     * 内存里的值还是旧的，结果是改了设置但主视图不变。
     *
     * 工作台每次打开时调用本方法，即可让外部改动生效。
     */
    fun refresh() {
        val config = helper ?: return
        runCatching {
            config.reload()
            read(config)
        }
    }

    private fun read(config: com.xuncorp.spw.workshop.api.config.ConfigHelper) {
        sidebarRail =
            runCatching {
                config.get<Boolean>("sidebar.rail", true) ?: true
            }.getOrDefault(true)
        titlebarEntry =
            runCatching {
                config.get<Boolean>(TITLEBAR_ENTRY_KEY, true) ?: true
            }.getOrDefault(true)
        titlebarPosition =
            runCatching {
                when (config.get<String>(TITLEBAR_POSITION_KEY, "left") ?: "left") {
                    "right" -> "right"
                    else -> "left"
                }
            }.getOrDefault("left")
        titlebarAction =
            runCatching {
                when (config.get<String>(TITLEBAR_ACTION_KEY, TITLEBAR_ACTION_WORKBENCH)) {
                    TITLEBAR_ACTION_SIDEBAR -> TITLEBAR_ACTION_SIDEBAR
                    else -> TITLEBAR_ACTION_WORKBENCH
                }
            }.getOrDefault(TITLEBAR_ACTION_WORKBENCH)
        showArtistSlots =
            runCatching {
                config.get<Boolean>(SHOW_ARTIST_SLOTS_KEY, true) ?: true
            }.getOrDefault(true)
        matchingPolicy =
            runCatching {
                config.get<String>(MATCHING_POLICY_KEY, "fill") ?: "fill"
            }.getOrDefault("fill")
        // 音源开关也从配置读，宿主「模组管理」页改完才能反映到工具栏下拉
        id3v2Version =
            runCatching {
                when (config.get<String>(ID3V2_VERSION_KEY, "23") ?: "23") {
                    "24" -> 4
                    else -> 3
                }
            }.getOrDefault(3)
        // 音源开关也从配置读，宿主「模组管理」页改完才能反映到工具栏下拉
        for (id in sourceEnabled.keys.toList()) {
            sourceEnabled[id] =
                runCatching {
                    config.get<Boolean>("sources.$id.enabled", true) ?: true
                }.getOrDefault(true)
        }
    }

    private const val SHOW_ARTIST_SLOTS_KEY = "view.showArtistSlots"
    private const val MATCHING_POLICY_KEY = "matching.policy"
    private const val ID3V2_VERSION_KEY = "tags.id3v2"
    private const val TITLEBAR_ENTRY_KEY = "titlebar.entry"
    private const val TITLEBAR_POSITION_KEY = "titlebar.position"
    private const val TITLEBAR_ACTION_KEY = "titlebar.action"

    /** `titlebar.action` 的取值：打开工作台（默认） / 开合侧边栏。 */
    const val TITLEBAR_ACTION_WORKBENCH = "workbench"
    const val TITLEBAR_ACTION_SIDEBAR = "sidebar"
}
