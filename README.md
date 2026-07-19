<div align="center">

# 🐬 DBboys

**新一代开源数据库开发与运维客户端**

*装 · 用 · 简 · 省 — 数据库全生命周期管理*

[![License: GPL v3](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Platform: Windows](https://img.shields.io/badge/Platform-Windows-0078D4.svg)]()
[![Platform: Linux](https://img.shields.io/badge/Platform-Linux-FCC624.svg)]()
[![Platform: Linux ARM64](https://img.shields.io/badge/Platform-Linux_ARM64-0078D4.svg)]()
[![JavaFX](https://img.shields.io/badge/JavaFX-25.0.3-FF6F00.svg)]()
[![JDK](https://img.shields.io/badge/JDK-25.0.2-ED8B00.svg)]()

[功能特性](#-功能特性) · [界面预览](#-界面预览) · [数据库适配](#-数据库适配说明) · [下载与构建](#-下载与构建) · [架构](#-架构) · [贡献](#-贡献) · [更新日志](CHANGELOG.md)

</div>

---

## ✨ 功能特性

DBboys 是一款桌面数据库客户端，支持连接管理、SQL 工作台、元数据浏览、DDL 导出、结果集编辑与导出、实例巡检、空间管理、参数管理、远程安装/卸载、实例启停等功能。采用本地客户端形态，连接信息、驱动、知识库索引和运行数据保存在本地；数据库能力通过方言模块扩展，不同数据库可以共享通用能力，也可按数据库特性提供专门的管理入口。

---

## 📸 界面预览

<a href="src/com/dbboys/html/images/img1.png"><img src="src/com/dbboys/html/images/img1.png" width="400"/></a>
<a href="src/com/dbboys/html/images/img2.png"><img src="src/com/dbboys/html/images/img2.png" width="400"/></a>
<a href="src/com/dbboys/html/images/img3.png"><img src="src/com/dbboys/html/images/img3.png" width="400"/></a>

---

## 🗃️ 数据库适配说明

| 数据库 | 连接 | SQL工作台 | 对象浏览 | DDL | 巡检 | 空间管理 | 参数管理 | 远程安装 | 实例启停 |
|--------|:----:|:--------:|:--------:|:---:|:----:|:--------:|:--------:|:--------:|:--------:|
| **GBase 8S** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Informix** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **MySQL** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| **Oracle** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | — | — |
| **达梦 (DM)** | ✅ | ✅ | ✅ | ✅ | — | — | — | — | — |
| **SQLite** | ✅ | ✅ | ✅ | ✅ | — | — | — | — | — |
| **通用 JDBC** | ✅ | ✅ | ✅ | ✅ | — | — | — | — | — |

> 适配能力依赖数据库类型、驱动、用户权限和版本。应用菜单 `帮助 → 适配列表` 可查看详细适配项。

---

## 📥 下载与构建

**下载：** 从 [Releases](https://github.com/dbboys/dbboys/releases) 获取预构建包（Windows / Linux x64 / Linux ARM64），解压后运行 `dbboys/bin/dbboys.exe`（Windows）或 `sh start.sh`（Linux）。

**从源码构建：**

| 项目 | 要求 |
|------|------|
| 操作系统 | Windows 10/11、Linux x64、Linux aarch64 |
| JDK | 25.0.2（含 `javac`、`jar`、`jlink`、`jpackage`） |
| JavaFX JMods | 25.0.3 |
| JavaFX SDK | 25.0.3 |

```shell
# Windows
build.bat
# Linux
sh build.sh
```

---

## 🏗️ 架构

DBboys 基于 **JavaFX 25** 构建，采用分层设计与方言插件架构：

- **方言层** — 7 个数据库方言模块（GBase 8s、Informix、MySQL、Oracle、达梦、SQLite、通用 JDBC）
- **核心层** — `DatabasePlatform` 接口定义连接、元数据、DDL、SQL 执行、实例管理能力
- **服务层** — 表、索引、过程、序列等对象业务逻辑
- **UI 层** — JavaFX 控制器、FXML、Cupertino 风格 CSS 主题（亮/暗色）
- **工具层** — JSqlParser（SQL 解析）、JSch（SSH）、Lucene/IK（知识库搜索）、Apache POI（导出）


---

## 📄 许可

本项目基于 [GNU General Public License v3.0](LICENSE) 开源许可。
