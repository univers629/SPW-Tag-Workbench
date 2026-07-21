plugins {
    java
}

group = "com.smallsinger.spw.tags"
version = "0.3.1"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    compileOnly("com.github.Moriafly:spw-workshop-api:0.1.0-dev14")
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.2.0")
    annotationProcessor("org.pf4j:pf4j:3.12.0")
    implementation("net.jthink:jaudiotagger:3.0.1")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("com.formdev:flatlaf:3.6.2")
}

val pluginClass = "com.smallsinger.spw.tags.TagWorkbenchPlugin"
val pluginId = "com.smallsinger.spw.tags"
val pluginName = "音乐标签工作台"
val pluginVersion = project.version.toString()

tasks.jar {
    manifest {
        attributes(
            "Plugin-Class" to pluginClass,
            "Plugin-Id" to pluginId,
            "Plugin-Name" to pluginName,
            "Plugin-Description" to "批量预览、编辑并写入本地音乐标签",
            "Plugin-Version" to pluginVersion,
            "Plugin-Provider" to "smallsinger",
            "Plugin-Open-Source-Url" to "https://github.com/univers629/SPW-Tag-Workbench",
            "Plugin-License" to "MIT",
            "Plugin-Has-Config" to "true"
        )
    }
}

tasks.register<Jar>("plugin") {
    group = "build"
    description = "Build SPW installable plugin package"
    archiveFileName.set("SPW-Tag-Workbench-$pluginVersion.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    into("classes") { with(tasks.jar.get()) }
    dependsOn(tasks.jar, configurations.runtimeClasspath)
    into("lib") {
        from(configurations.runtimeClasspath.get().filter {
            it.name.startsWith("jaudiotagger-") || it.name.startsWith("gson-") || it.name.startsWith("flatlaf-")
        })
    }
    archiveExtension.set("zip")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
