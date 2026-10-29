import com.xuncorp.spw.workshop.gradle.PluginPermission

plugins {
    id("java-library")
    kotlin("jvm") version "2.4.20"
    kotlin("kapt") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("com.xuncorp.spw.workshop") version "0.1.0-dev23"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
}

/**
 * 编译期 stub source set。
 *
 * 里面只有一个文件：与宿主同名同签名的 `AppNavigation()`（`(Composer, Int)`）。
 * 它让插件能反过来调用宿主未混淆的 AppNavigation，从而「测量但不放置」宿主内容，
 * 让窗口的 DWM 材质裸露出来。stub 的产物只用于编译，不进入插件 jar。
 */
val hostStub: SourceSet by sourceSets.creating

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
        freeCompilerArgs.add("-Xjvm-default=all")
        optIn.add("com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi")
        optIn.add("androidx.compose.ui.ExperimentalComposeUiApi")
    }
}

dependencies {
    compileOnly(kotlin("stdlib"))
    compileOnly(libs.spw.workshop.api)
    kapt(libs.spw.workshop.api)

    compileOnly(libs.compose.ui)
    compileOnly(libs.compose.foundation)
    compileOnly(libs.compose.animation)
    compileOnly(libs.coroutines.core)
    compileOnly(libs.skiko.awt)
    compileOnly(libs.jaudiotagger)
    compileOnly(libs.jna) // 宿主自带 JNA，用于 BASS 解码

    compileOnly(hostStub.output)

    // 纯逻辑单元测试（匹配层等），无图形环境依赖
    testImplementation(kotlin("test"))
    testImplementation(libs.jaudiotagger)
    // ebur128 的 JNA 绑定有单元测试：要用真实 JNA + 真实 DLL 验证数值与 EBU 参考一致
    testImplementation(libs.jna)
    // Compose 编译器也作用于 test 编译，需要 runtime 在类路径上
    testImplementation(libs.compose.ui)
    "hostStubImplementation"(kotlin("stdlib"))
    "hostStubImplementation"(libs.compose.ui)
}

tasks.test {
    useJUnitPlatform()
}

/**
 * ktlint 配置。
 *
 * 只格式化 `src/main` 与 `src/hostStub`；宿主 stub 里刻意保留了一些编译器技巧，
 * 不参与规则检查。用 `.editorconfig` 的 Kotlin 规则（intellij_idea 风格）。
 */
ktlint {
    version.set("1.3.1")
    android.set(false)
    outputToConsole.set(true)
    ignoreFailures.set(true)
    filter {
        exclude("**/hostStub/**")
    }
}

spmod {
    PluginClass = "io.github.univers629.spwtagworkbench.MainPlugin"
    PluginId = "io.github.univers629.spwtagworkbench"
    PluginVersion = "1.4.1"
    PluginProvider = "univers629"
    PluginName = "音乐标签工作台"
    PluginDescription = "在线批量匹配封面和逐字歌词，逐字歌词打轴，查看歌曲频谱图，编辑、预览音乐标签信息"
    PluginHasConfig = true
    PluginOpenSourceUrl = "https://github.com/univers629/SPW-Tag-Workbench"
    PluginPermissions =
        listOf(
            PluginPermission.CLASS_TRANSFORM,
            PluginPermission.KEY_BINDINGS,
            PluginPermission.LIBRARY_READ,
            PluginPermission.LIBRARY_WRITE,
        )
}
