<div align="center">

# 🐬 DBboys

**新一代开源数据库开发与运维客户端**

*装 · 用 · 管 · 卸 — 数据库全生命周期管理*

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

DBboys 是一款桌面数据库客户端，支持数据库远程安装卸载、对象管理、一键智能执行所有SQL。  
支持SSH终端操作。  
支持Markdown文档知识库管理及全文索引。  
支持AI助手。  

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
| **达梦 (DM)** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| **SQLite** | ✅ | ✅ | ✅ | ✅ | — | — | — | — | — |
| **通用 JDBC** | ✅ | ✅ | ✅ | ✅ | — | — | — | — | — |

> 适配能力依赖数据库类型、驱动、用户权限和版本。应用菜单 `帮助 → 适配列表` 可查看详细适配项。

---

## 从源码构建

Windows x64：

1. 安装 JDK 25.0.2，并确保 JDK `bin` 目录在 `PATH` 中
2. 修改 `build.bat` 中的 `JAVAFX_JMODS`，指向本机 JavaFX jmods 目录
3. 在项目根目录执行：

   ```bat
   build.bat
   ```

4. 脚本会编译源码、复制资源、生成 `dbboys.jar`、通过 `jlink` 生成运行时、通过 `jpackage` 打包 app-image，并最终输出 `dbboys.zip`
5. 解压 `dbboys.zip` 后运行 `dbboys/bin/dbboys.exe`

Linux x64：

1. 安装 JDK 25.0.2，并确保 JDK `bin` 目录在 `PATH` 中
2. 修改 `build.sh` 中的 `JAVAFX_JMODS`，指向本机 JavaFX jmods 目录
3. 在项目根目录执行：

   ```shell
   sh build.sh
   ```

4. 脚本会编译源码、复制资源、生成 `dbboys.jar`、通过 `jlink` 生成运行时、通过 `jpackage` 打包 app-image，并最终输出 `dbboys.zip`
5. 解压 `dbboys.zip` 后运行 `sh start.sh`

Linux aarch64：
1. 源码编译jmod，官方提供的jmod glibc版本较高，不一定适用大部分linux
   安装OpenJDK Runtime Environment (build 22+36-2370)（21版本太低，25版本太高）
   gcc版本7.5以上（4.8太低）
   下载jfx源码jfx-25-3
   修改modules/javafx.graphics/src/main/native-glass/gtk/PlatformSupport.cpp，最后一行添加
   ```
   constexpr const char* PlatformSupport::OBSERVED_SETTINGS[];
   ```
   否则编译完成后可能libglassgtk3.so中的OBSERVED_SETTINGS未定义
   ```
   nm -D build/modular-sdk/modules_libs/javafx.graphics/libglassgtk3.so | grep OBSERVED_SETTINGS
   ```
   显示D正常，显示U未定义
   ```
   cd jfx-25-3
   chmod 777 gradlew
   ./gradlew  jmods
   ```
2. 安装 JDK 25.0.2，并确保 JDK `bin` 目录在 `PATH` 中
3. 修改 `build.sh` 中的 `JAVAFX_JMODS`，指向本机 JavaFX jmods 目录（第一步编译好的jmod）
4. 在项目根目录执行：

   ```shell
   sh build.sh
   ```

5. 脚本会编译源码、复制资源、生成 `dbboys.jar`、通过 `jlink` 生成运行时、通过 `jpackage` 打包 app-image，并最终输出 `dbboys.zip`
6. 解压 `dbboys.zip` 后运行 `sh start.sh`

---

## 🏗️ 技术栈

OpenJFX25.0.3(UI)、Apache MINA SSHD（SSH）、Lucene/IK(全文索引)、Apache POI(CSV)


---

## 📄 许可

本项目基于 [GNU General Public License v3.0](LICENSE) 开源许可。
