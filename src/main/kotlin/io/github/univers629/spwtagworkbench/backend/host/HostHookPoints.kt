// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.host

/**
 * 一个宿主版本段所用的**混淆点位**。
 *
 * 宿主把业务类名与方法名经 R8 混淆成小语种字母，每次发版都可能重新分配。
 * 这些点位无法像未混淆类名那样直接依赖，因此按版本固化在这里；
 * 字段为 `null` 表示该点位在这个版本上**尚未测出**，调用方必须跳过它，
 * 而不是猜一个值。
 *
 * 与之相对，[TitlebarDiscovery] 那条链路不写任何混淆名（运行时结构发现），
 * 不需要版本表。两套做法并存：能结构发现的就不写死。
 *
 * 新增版本时照 `docs/saltplayer-re/宿主点位扫描规范.md` 扫描并填表。
 */
internal data class HostHookPoints(
    /**
     * 应用页层（层级 B）的插入点 `inClass`。
     *
     * 语义：宿主 jar 里**唯一** invoke 了 `AppNavigationKt.AppNavigation` 的那个类。
     */
    val navCaller: String?,
    /** 层级 A：持有顶层 Composable 的混淆类（`className`）。 */
    val topLevelClass: String?,
    /** 层级 A：该类的 Composable 方法名（`methodName`）。 */
    val topLevelMethod: String?,
    /** 层级 A：对 `topLevelClass.topLevelMethod` 有唯一调用点的类（`inClass`）。 */
    val topLevelCaller: String?,
    /** `PiscesMediaItem` 的 id getter 名。 */
    val mediaItemIdGetter: String?,
) {
    /**
     * 层级 A 三点；任一项缺失即返回 null（表示该版本不支持层级 A）。
     *
     * 把「三缺一」这种半残状态收在这里判断，调用方不必各自拼 Triple。
     */
    fun topLevel(): Triple<String, String, String>? {
        val className = topLevelClass ?: return null
        val methodName = topLevelMethod ?: return null
        val inClass = topLevelCaller ?: return null
        return Triple(className, methodName, inClass)
    }

    companion object {
        /**
         * 1.19.2 点位表。
         *
         * 1.19.2 相对 1.19.1 只少 4 个方法，**R8 映射表沿用、点位未变**，
         * 两个版本共用同一套值（见扫描规范 §2 基线）。
         *
         * 1.19.2 实测：索引 22629 个方法；层级 B 调用点 1 处；
         * 层级 A 调用点 1 处（字节码偏移 179）。
         */
        val V1_19_2 =
            HostHookPoints(
                navCaller = "androidx.compose.ui.\u0443",
                topLevelClass = "androidx.compose.ui.\u041A",
                topLevelMethod = "\u037F",
                topLevelCaller = "androidx.compose.ui.\u0450",
                mediaItemIdGetter = "\u037F",
            )

        /**
         * 1.19.3 点位表（2026-10-29 扫描确认）。
         *
         * 该版本**点位整体重排**：
         * - 1.19.2 的 navCaller `у`(U+0443) 已无调用点（校验报 `no call site`），
         *   结构发现给出新值 `Ɏ`(U+024E)，调用点 1 处；
         * - 层级 A 三点同样重排，`Ϳ`(U+037F) 方法名与 id getter **未变**；
         * - 索引 25993 个方法（1.19.2 为 22629）。
         */
        val V1_19_3 =
            HostHookPoints(
                navCaller = "androidx.compose.ui.\u024E",
                topLevelClass = "androidx.compose.ui.\u0525",
                topLevelMethod = "\u037F",
                topLevelCaller = "androidx.compose.ui.\u0537",
                mediaItemIdGetter = "\u037F",
            )

        /**
         * 未知版本的回退表：取**最新已测**点位。
         *
         * 宿主小版本升级常常不改混淆名，沿用最近一次实测值的命中率高于任何猜测。
         * 但**高版本也可能整体重排**（1.19.3 就是如此），
         * 因此携带这些点位的注册路径必须先经运行时校验
         * （[HostPointResolver.verifyUniqueCallSite] / [HostPointResolver.verifyTopLevel]）：
         * 校验不过就放弃该点位并降级 —— 不允许「试一下能不能用」。
         */
        val FALLBACK: HostHookPoints = V1_19_3

        /**
         * 已实测的 1.19.x 小版本 → 点位表。
         *
         * 只收录**实际扫描确认过**的版本。其余（含 1.19.0 / 1.19.1）
         * 一律走 [FALLBACK] 加运行时校验：navCaller 可由结构发现自动纠正，
         * 层级 A 若角色校验不过则退回层级 B，不会静默用错点位。
         */
        private val MINOR_TABLE: Map<Int, HostHookPoints> =
            mapOf(
                2 to V1_19_2,
                3 to V1_19_3,
            )

        /**
         * 按宿主版本选取点位表。
         *
         * 命中 [MINOR_TABLE] 返回对应表；其余情况返回 [FALLBACK]。
         */
        fun forVersion(raw: String?): HostHookPoints {
            val v = HostVersion.parse(raw)
            if (v.size >= 3 && v[0] == 1 && v[1] == 19) {
                MINOR_TABLE[v[2]]?.let { return it }
            }
            return FALLBACK
        }

        /**
         * 这个版本号是否**已实测过**。
         *
         * 命中表示静态表可信，走**零扫描**路径（不建字节码索引）——
         * 这是保证既有用户启动开销不变的关键：1.19.2 与 1.19.3 都走这条路径。
         *
         * 未命中（含 1.19.0 / 1.19.1、两段式版本号、无法解析的串）会先建索引校验，
         * 代价约 1.4 秒，但能自动纠正失效点位。
         */
        fun isTested(raw: String?): Boolean {
            val v = HostVersion.parse(raw)
            return v.size >= 3 && v[0] == 1 && v[1] == 19 && MINOR_TABLE.containsKey(v[2])
        }

        /** 表标识，写进日志用于确认选中的是哪张表。 */
        fun labelOf(points: HostHookPoints): String =
            when (points) {
                V1_19_2 -> "1.19.2"
                V1_19_3 -> "1.19.3"
                else -> "unknown"
            }
    }
}
