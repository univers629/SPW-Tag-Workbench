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

## 致谢

本项目的设计与实现参考了以下优秀开源项目，感谢各项目作者与贡献者：

- [spw-workshop-api](https://github.com/Moriafly/spw-workshop-api)：提供 SPW 创意工坊插件接口、示例与开发参考。
- [SaltUI](https://github.com/Moriafly/SaltUI)：提供 Salt 系列界面风格与交互设计参考。
- [Lyrico](https://github.com/Replica0110/Lyrico)：提供音乐信息匹配、歌词处理与工作流设计参考。
- [Lyrico-Plugins](https://github.com/Replica0110/Lyrico-Plugins)：提供多音乐源搜索插件的实现参考。
- [LDDC](https://github.com/chenmozhijin/LDDC)：提供歌词匹配及逐字歌词处理思路参考。

以上项目的名称与链接仅用于致谢，不代表其作者对本项目提供官方支持或背书。

## 许可证

[MIT License](LICENSE)
