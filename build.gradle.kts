plugins {
    java
}

group = "com.smallsinger.spw.tags"
version = "1.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    compileOnly("com.github.Moriafly:spw-workshop-api:0.1.0-dev14")
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.2.0")
    compileOnly("net.java.dev.jna:jna:5.18.1")
    annotationProcessor("org.pf4j:pf4j:3.12.0")
    implementation("net.jthink:jaudiotagger:3.0.1")
    implementation("com.google.code.gson:gson:2.13.2") {
        exclude(group = "com.google.errorprone",
                module = "error_prone_annotations")
    }
    implementation("com.formdev:flatlaf:3.6.2")
}

val pluginClass = "com.smallsinger.spw.tags.TagWorkbenchPlugin"
val pluginId = "com.smallsinger.spw.tags"
val pluginName = "音乐标签工作台"
val pluginVersion = project.version.toString()
val pluginManifest = mapOf(
    "Plugin-Class" to pluginClass,
    "Plugin-Id" to pluginId,
    "Plugin-Name" to pluginName,
    "Plugin-Description" to "在线批量匹配封面和逐字歌词，查看歌曲频谱图，编辑、预览音乐标签信息",
    "Plugin-Version" to pluginVersion,
    "Plugin-Provider" to "univers629",
    "Plugin-Open-Source-Url" to "https://github.com/univers629/SPW-Tag-Workbench",
    "Plugin-License" to "GPL-3.0-only",
    "Plugin-Has-Config" to "true"
)

tasks.jar {
    manifest {
        attributes(pluginManifest)
    }
}

val systemFontJar = tasks.register<Jar>("systemFontJar") {
    group = "build"
    description = "Build plugin classes without the bundled MiSans font"
    archiveClassifier.set("system-font")
    from(sourceSets.main.get().output)
    exclude("fonts/**")
    manifest {
        attributes(pluginManifest)
    }
}

tasks.register<Jar>("plugin") {
    group = "build"
    description = "Build SPW installable plugin package with MiSans"
    archiveFileName.set(
        "SPW-Tag-Workbench-$pluginVersion-with-MiSans.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    into("classes") { with(tasks.jar.get()) }
    dependsOn(tasks.jar, configurations.runtimeClasspath)
    into("lib") {
        from(configurations.runtimeClasspath)
    }
    from("LICENSE")
    from("THIRD_PARTY_NOTICES.md")
    from("MiSans_FONT_LICENSE.pdf")
    archiveExtension.set("zip")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.register<Jar>("pluginLite") {
    group = "build"
    description = "Build SPW installable plugin package using system fonts"
    archiveFileName.set(
        "SPW-Tag-Workbench-$pluginVersion-system-font.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    into("classes") { with(systemFontJar.get()) }
    dependsOn(systemFontJar, configurations.runtimeClasspath)
    into("lib") {
        from(configurations.runtimeClasspath)
    }
    from("LICENSE")
    from("THIRD_PARTY_NOTICES.md")
    archiveExtension.set("zip")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
