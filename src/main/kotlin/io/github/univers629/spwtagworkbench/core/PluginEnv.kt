// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.core

import com.xuncorp.spw.workshop.api.WorkshopApi
import java.io.File

/**
 * 宿主注入的本插件运行期信息。
 *
 * 插件 ID 与数据目录都由宿主给出，代码里不再抄写字面量：改 ID 时只需改
 * `build.gradle.kts` 的 `PluginId`，以及 `preference_config.json` 里
 * 无法在运行期求值的 `on_click`，不会再出现「改了清单忘了改代码」的偏差。
 */
object PluginEnv {
    /** 宿主注入的插件 ID；[bind] 之前为空串。 */
    var id: String = ""
        private set

    /**
     * 宿主版本串（`PluginContext.spwVersion`，1.19.2 实测为 `1.19.2`）。
     *
     * 混淆点位按版本选取，而点位解析发生在宿主无法再提供上下文的场合
     * （`MainPlugin` 伴生对象里的静态方法），因此在这里留一份。
     * [bind] 之前为空串，取值方必须容忍不可解析。
     */
    var hostVersion: String = ""
        private set

    /** 宿主渠道（`Steam` / `MS`）；[bind] 之前为空串。 */
    var hostChannel: String = ""
        private set

    fun bind(
        pluginId: String,
        spwVersion: String = "",
        spwChannel: String = "",
    ) {
        id = pluginId
        hostVersion = spwVersion
        hostChannel = spwChannel
    }

    /**
     * 宿主分配给本插件的数据目录，即配置文件的父目录。
     *
     * 宿主 API 不可用时返回 null，由调用方决定退回行为。
     */
    val dataDir: File? by lazy {
        runCatching {
            WorkshopApi.manager
                .createConfigManager()
                .getConfig()
                .getConfigPath()
                .toFile()
                .parentFile
        }.getOrNull()
    }
}
