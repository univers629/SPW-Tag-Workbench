# 第三方软件与许可证声明

SPW Tag Workbench 的整体源代码依据 **GNU General Public License v3.0 only（GPL-3.0-only）** 发布。

本项目的设计或实现参考了以下开源项目：

| 项目 | 许可证 | 用途 |
| --- | --- | --- |
| [LDDC](https://github.com/chenmozhijin/LDDC) | GPL-3.0-only | 歌词匹配与逐字歌词处理参考 |
| [Lyrico](https://github.com/Replica0110/Lyrico) | Apache-2.0 | 音乐信息匹配、歌词处理与工作流参考 |
| [SaltUI](https://github.com/Moriafly/SaltUI) | Apache-2.0 | Salt 系列界面与交互风格参考 |
| [spw-workshop-api](https://github.com/Moriafly/spw-workshop-api) | Apache-2.0 | SPW 插件接口与开发示例 |
| [Lyrico-Plugins](https://github.com/Replica0110/Lyrico-Plugins) | 仓库根目录未提供明确许可证 | 多音乐源搜索实现参考；相关权利归原作者所有 |
| [Spek](https://github.com/alexkay/spek) | GPL-3.0 | 频谱计算管线与渐进绘制方式参考 |

构建及发行包还包含或使用以下第三方组件：

| 组件 | 许可证 |
| --- | --- |
| FlatLaf | Apache-2.0 |
| Gson | Apache-2.0 |
| jaudiotagger | LGPL-3.0-or-later |
| JNA（仅编译及宿主运行时使用，不随插件分发） | Apache-2.0 OR LGPL-2.1-or-later |
| Gradle Wrapper | Apache-2.0 |
| MiSans Medium | 小米《MiSans 字体知识产权许可协议》 |

带 MiSans 的发行包内置并使用未修改的 MiSans Medium 字体；系统字体轻量
发行包不包含该字体。MiSans 的版权归小米科技有限责任公司所有，字体文件
不作为独立产品分发。带字体发行包随附原始许可协议
`MiSans_FONT_LICENSE.pdf`，许可详情亦可在
[MiSans 官方下载页](https://hyperos.mi.com/font/zh/download/) 查阅。

频谱功能在运行时调用 SPW 已经加载的 BASS 音频引擎，本插件不分发
BASS 二进制文件。BASS 的相关权利归 Un4seen Developments 所有。

Apache-2.0 许可的代码可以组合到 GPLv3 项目中，但原项目的版权、NOTICE（如有）及许可证义务仍需保留。各项目名称与链接仅用于归属说明，不代表其作者为本项目提供官方支持或背书。
