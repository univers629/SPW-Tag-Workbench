// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.host

import io.github.univers629.spwtagworkbench.core.Diag
import java.lang.classfile.ClassFile
import java.lang.classfile.instruction.InvokeInstruction
import java.lang.invoke.MethodType
import java.lang.reflect.Method
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * 宿主标题栏的**结构发现**。
 *
 * 宿主的类名与方法名都被 R8 混淆成了小语种字母（`Ϳ`、`ԩ`、`К` …），
 * 每次发版都可能重新分配，所以这里**不写死任何混淆名**，改为：
 *
 * 1. 打开宿主自己的 jar（`WorkshopApi.instance` 的 codeSource），
 * 2. 用 JVM 25 自带的 `java.lang.classfile` 索引每个方法的**调用列表**，
 * 3. 按「Compose 内部描述符 + 调用关系」反推标题栏那一排按钮的调用点。
 *
 * 描述符（`Landroidx/compose/runtime/Composer;` 之类）来自 Compose / Kotlin 标准库，
 * 不属于宿主业务代码，**不随混淆变化**，因此可以作为稳定锚点。
 *
 * 这套算法与 SPW-Lyrics、SPW-Listen-Stats 使用的结构发现同源；
 * 本插件只保留标题栏需要的那一部分，不涉及导航、主题等其它点位。
 */
object TitlebarDiscovery {
    /** 无业务参数的 Composable：`(Composer, Int) -> Unit`。 */
    private const val COMPOSE = "(Landroidx/compose/runtime/Composer;I)V"

    /**
     * 宿主原生「图标标题栏按钮」的签名。
     *
     * 这是一个 Compose 组件的内部委托方法，参数依次为：
     * onClick、无障碍文本、Modifier、boolean、String、float、图标槽（Function3=BoxScope lambda）。
     *
     * 第 6 个 `F` 是按钮宽度，`Function3` 是宿主留给调用方的**图标槽** ——
     * 插件把图标画在这里，按钮外壳（点击、悬停、角色语义）全部由宿主提供。
     */
    private const val CAPTION =
        "(Lkotlin/jvm/functions/Function0;" +
            "Ljava/lang/String;" +
            "Landroidx/compose/ui/Modifier;" +
            "Z" +
            "Ljava/lang/String;" +
            "F" +
            "Lkotlin/jvm/functions/Function3;" +
            "Landroidx/compose/runtime/Composer;II)V"

    /** 按钮在标题栏这一排里的落点。 */
    enum class Position {
        /** 排在宿主 Quick 按钮**左侧**（与其它插件的按钮相邻）。 */
        LEFT,

        /** 排在宿主 Quick 按钮**右侧**。 */
        RIGHT,
        ;

        val key: String get() = if (this == LEFT) "left" else "right"

        companion object {
            fun fromKey(value: String?): Position = if (value?.trim()?.lowercase() == "right") RIGHT else LEFT
        }
    }

    /** 索引里的一个方法引用。 */
    data class HostMethod(
        val owner: String,
        val name: String,
        val descriptor: String,
    ) {
        val className: String get() = owner.replace('/', '.')
    }

    /** 一个方法的调用列表。 */
    data class HostMethodBody(
        val method: HostMethod,
        val access: Int,
        val calls: List<HostMethod>,
    ) {
        val isStatic: Boolean get() = access and ACC_STATIC != 0
    }

    private const val ACC_STATIC = 0x0008

    /** 建立好的字节码索引。 */
    class HostBytecodeIndex(
        val methods: List<HostMethodBody>,
    ) {
        private val byKey = methods.associateBy { it.method }

        fun body(method: HostMethod): HostMethodBody? = byKey[method]

        /** 某个类里对目标方法的调用次数（Hook 框架眼里的“调用点”数）。 */
        fun callCount(
            callerClass: String,
            target: HostMethod,
        ): Int =
            methods
                .filter { it.method.owner == callerClass }
                .sumOf { body -> body.calls.count { it == target } }
    }

    /**
     * 发现结果：标题栏这一排的**有序子组件**，以及留给插件图标的本机控件。
     *
     * [entries] 的顺序就是从左到右的布局顺序（Row 按组合顺序排布）。
     */
    data class Sites(
        val inClass: String,
        val control: HostMethod,
        val entries: List<HostMethod>,
        val quick: HostMethod,
        val quickIndex: Int,
    ) {
        /**
         * [inClass] 的 **JVM 名**（点分隔）。
         *
         * 字节码索引里存的是内部名（`androidx/compose/ui/ৱ`），
         * 但 Hook 框架的 `className` / `inClass` 要 JVM 名（`androidx.compose.ui.ৱ`）。
         * 传内部名宿主会抛 `Hook class not found` —— 按钮静默不出现，
         * 界面上没有任何其它症状，所以这个转换必须显式且可测。
         */
        val inClassJvm: String get() = inClass.replace('/', '.')

        /** 取某个落点对应的锚点：LEFT 取 Quick 的前一个兄弟，RIGHT 取 Quick 本身。 */
        fun anchor(position: Position): HostMethod? =
            when (position) {
                Position.RIGHT -> quick
                Position.LEFT -> entries.getOrNull(quickIndex - 1)
            }

        /** 人类可读的诊断串，写进日志用于定位失败原因。 */
        fun describe(): String =
            "inClass=$inClass quick#$quickIndex/${entries.size} " +
                "control=${control.owner}.${control.name}"
    }

    /**
     * 扫描宿主 jar 并解析标题栏点位。
     *
     * 调用方负责放到后台线程执行：完整扫描要遍历数万个 class。
     *
     * @throws IllegalStateException 任一环节候选数不为 1 时抛出，附具体角色名
     */
    fun discover(source: Path): Sites = discover(read(source))

    /** 在已建好的索引上做结构发现（便于单测直接喂索引）。 */
    fun discover(index: HostBytecodeIndex): Sites {
        // 1) 宿主原生图标按钮控件：签名匹配且是 static，并且内部委托给同形状的方法。
        val control =
            unique(
                index.methods.filter { body ->
                    body.method.descriptor == CAPTION &&
                        body.isStatic &&
                        body.calls.any { it.descriptor == CAPTION.replace("String;F", "String;") }
                },
                "caption control",
            ).method

        // 2) Quick：唯一一个调用了该控件、且自身是 (Composer, Int) 的方法。
        val quick =
            unique(
                index.methods.filter { body ->
                    body.method.descriptor == COMPOSE && body.calls.contains(control)
                },
                "caption entry",
            ).method

        // 3) 调用方所在类：必须是**另一个**类里的 Composable
        //    （排除编译器 restart 回调对自身 owner 的调用）。
        val inClass =
            unique(
                index.methods
                    .filter { body ->
                        body.method.owner != quick.owner &&
                            body.calls.contains(quick) &&
                            body.method.descriptor.contains("Landroidx/compose/runtime/Composer;")
                    }.map { it.method.owner }
                    .distinct(),
                "UI caller of $quick",
            )

        // 4) 这一排本身：inClass 里调用 Quick 的那个方法。
        val row =
            unique(
                index.methods.filter { body ->
                    body.method.owner == inClass && body.calls.contains(quick)
                },
                "caption row",
            )

        // 5) 结构自检：确认它真的是一个 Row，而不是别的布局容器。
        check(
            row.calls.count { it.owner == "androidx/compose/foundation/layout/RowKt" && it.name == "rowMeasurePolicy" } == 1,
        ) { "caption row structure changed: not a single Row" }

        // 6) 有序子组件：Compose 调用 + 方法是 static + 排除运行时工具类。
        val entries =
            row.calls.filter { call ->
                call.descriptor == COMPOSE &&
                    index.body(call)?.isStatic == true &&
                    call.owner != "androidx/compose/runtime/ComposerKt"
            }
        val quickIndex = entries.indexOf(quick)
        check(quickIndex >= 0) { "caption entry not found among ordered children" }

        // 7) 插入点必须唯一：同一类里对该锚点的调用只能有 1 处，否则 Hook 框架会拒绝。
        //    LEFT 需要 Quick 前面还有一个兄弟可以锚定。
        check(quickIndex > 0) { "caption predecessor missing (quick is first child)" }
        for (target in listOf(entries[quickIndex - 1], quick)) {
            val count = index.callCount(inClass, target)
            check(count == 1) { "caption call site $target is not unique: $count" }
        }

        return Sites(inClass, control, entries, quick, quickIndex)
    }

    /** 建立索引：支持目录（开发环境）与 jar（打包后的宿主）。 */
    fun read(source: Path): HostBytecodeIndex {
        val methods = ArrayList<HostMethodBody>(24_000)
        if (Files.isDirectory(source)) {
            Files.walk(source).use { paths ->
                paths
                    .filter { it.toString().endsWith(".class") }
                    .forEach { path ->
                        check(!Thread.currentThread().isInterrupted) { "titlebar scan cancelled" }
                        runCatching { Files.readAllBytes(path) }
                            .onSuccess { parse(it, methods) }
                    }
            }
        } else {
            JarFile(source.toFile()).use { jar ->
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || !entry.name.endsWith(".class")) continue
                    if (entry.name.startsWith("META-INF/versions/")) continue
                    check(!Thread.currentThread().isInterrupted) { "titlebar scan cancelled" }
                    runCatching { jar.getInputStream(entry).use { it.readBytes() } }
                        .onSuccess { parse(it, methods) }
                }
            }
        }
        Diag.log("titlebar: 索引完成 ${methods.size} 个方法（source=$source）")
        return HostBytecodeIndex(methods)
    }

    /**
     * 除了 Compose 之外，还需要索引的包。
     *
     * 粗筛原本只认 `androidx/compose/runtime/Composer`，但插件还依赖一批
     * **非 Compose 的反射点位**（如 `PiscesMediaItem` 的 id getter）：
     * 纯数据类不引用 Compose，会被整体跳过，于是这些点位既无法校验、
     * 也无法在改版后重新发现。
     *
     * 按包前缀白名单放行，而不是取消粗筛：宿主有 4 万多个 class，
     * 全量解析会明显拖慢扫描（实测索引约 1.1～1.5 秒），
     * 而下面的包只占几十个类，代价可忽略。
     */
    private val EXTRA_INDEXED_PACKAGES =
        listOf(
            // PiscesMediaItem 的 id / path getter（混淆名，按版本表取值）
            "com/xuncorp/pisces/",
        )

    private fun parse(
        bytes: ByteArray,
        into: MutableList<HostMethodBody>,
    ) {
        // 粗筛：Compose 类，以及上面白名单里的反射点位类。
        // 宿主 jar 有 4 万多个 class，不筛的话扫描会明显变慢。
        val pool = String(bytes, StandardCharsets.ISO_8859_1)
        val wanted =
            pool.contains("androidx/compose/runtime/Composer") ||
                EXTRA_INDEXED_PACKAGES.any { pool.contains(it) }
        if (!wanted) return

        val model = ClassFile.of().parse(bytes)
        val owner = model.thisClass().asInternalName()
        for (method in model.methods()) {
            val code = method.code().orElse(null) ?: continue
            val calls = ArrayList<HostMethod>(8)
            for (element in code) {
                if (element is InvokeInstruction) {
                    calls.add(
                        HostMethod(
                            owner = element.owner().asInternalName(),
                            name = element.name().stringValue(),
                            descriptor = element.type().stringValue(),
                        ),
                    )
                }
            }
            into.add(
                HostMethodBody(
                    method =
                        HostMethod(
                            owner = owner,
                            name = method.methodName().stringValue(),
                            descriptor = method.methodType().stringValue(),
                        ),
                    access = method.flags().flagsMask(),
                    calls = calls,
                ),
            )
        }
    }

    /**
     * 按描述符绑定宿主的图标按钮方法。
     *
     * 只解析签名、不初始化宿主类（`initialize = false`），避免把播放器服务提前拉起来。
     */
    fun bind(
        control: HostMethod,
        loader: ClassLoader,
    ): Method {
        val signature = MethodType.fromMethodDescriptorString(control.descriptor, loader)
        return Class
            .forName(control.className, false, loader)
            .getDeclaredMethod(control.name, *signature.parameterArray())
            .also { check(it.returnType == signature.returnType() && it.trySetAccessible()) { "caption control not accessible" } }
    }

    private fun <T> unique(
        matches: List<T>,
        role: String,
    ): T {
        check(matches.size == 1) { "$role: expected one match, found ${matches.size}" }
        return matches.single()
    }
}
