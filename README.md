# SPW Tag Workbench

适用于 Salt Player for Windows（SPW）的音乐标签工作台插件，可批量读取、在线匹配、预览、编辑并写入本地音乐标签。

## 功能

- 支持标题、艺术家、专辑、年份、音轨号、碟号、风格、专辑艺术家、作曲家、作词家、注释、歌词与封面。
- 支持网易云音乐、QQ 音乐、酷狗音乐和 Apple Music 数据源。
- 支持批量勾选、分区域匹配、搜索、编辑与保存。
- 支持配置音乐文件夹及跟随 SPW 当前播放歌曲。
- 适配深色与浅色界面。

## 安装

从 Releases 下载 `SPW-Tag-Workbench-*.zip`，在 SPW 创意工坊的本地插件管理中导入。插件需要 SPW 1.15.2 或更高兼容版本及 Java 21 运行环境。

## 构建

```powershell
.\gradlew.bat clean plugin
```

构建产物位于 `build/distributions`。

## 说明

本项目是非官方社区插件。在线数据来自相应音乐服务，仅供个人音乐文件整理使用，请遵守当地法律及各服务条款。

## 许可证

[MIT License](LICENSE)
