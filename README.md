<div align="center">

# 🗄️ DBboys

**新一代开源数据库开发与运维客户端**

*装 · 用 · 管 · 卸 — 数据库全生命周期管理*

[![License: GPL v3](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Platform: Windows](https://img.shields.io/badge/Platform-Windows-0078D4.svg)]()
[![JavaFX](https://img.shields.io/badge/JavaFX-25.0.3-FF6F00.svg)]()
[![JDK](https://img.shields.io/badge/JDK-25.0.2-ED8B00.svg)]()

[功能特性](#-功能特性) · [快速开始](#-快速开始) · [数据库适配](#-数据库适配说明) · [开发指南](#-开发指南) · [更新日志](#-更新日志)

</div>

---

## ✨ 功能特性

项目采用本地客户端形态运行，连接信息、驱动信息、知识库索引和运行数据保存在本机；数据库能力通过方言模块扩展，不同数据库可以共享 SQL 工作台、元数据浏览、结果集展示等通用能力，也可以按数据库特性提供专门的管理入口。

---

### 🔌 连接管理

- 支持按分类管理数据库连接，连接树支持新建分类、新建连接、复制连接、编辑连接、重命名、移动、删除和刷新
- 连接配置支持数据库类型、驱动、地址、端口、用户名、密码、连接属性和只读标记
- 支持添加、删除和切换 JDBC 驱动，驱动文件按数据库类型放在 `extlib/` 对应目录下
- 支持测试连接、断开连接、断开分类下连接、断开所有连接和断线重连
- GBase 8s / Informix 支持 IP 端口连接，也支持通过数据库组名和 `sqlhosts` 文件连接
- 连接只读模式会限制部分高风险操作，适合巡检、查询和只读分析场景
- 支持长连接保活，自动维持连接可用性

### 📝 SQL 工作台

- 支持多 SQL 标签页，连接、数据库、用户、SQL mode、提交模式可在 SQL 窗口中切换
- 支持 SQL 执行、停止执行、重新执行、执行计划查看、后台任务状态展示
- SQL 编辑器支持保存、打开 SQL 文件、查找替换、格式化、大小写转换、行注释和段注释
- 支持自动提交和手动提交模式；手动提交时可在编辑器内进行提交或回滚
- 支持变更历史记录，便于追踪连接级别已执行成功的变更 SQL
- GBase 8s 支持 `sqlmode=gbase`、`sqlmode=mysql`、`sqlmode=oracle` 等模式切换
- 支持绑定变量，兼容所有 PL/SQL、DDL、DML 混合执行

### 📊 结果集

- 查询结果以表格展示，支持分页加载、下一页、获取全部行、获取总行数和执行耗时展示
- 支持复制当前 SQL、刷新当前结果集、导出已加载结果集
- 结果集为空时显示空状态，长时间执行时显示进度和任务信息
- 单表查询且结果集中包含主键或 `rowid` 时支持编辑结果集
- 可编辑结果集支持单元格修改、插入行、删除行、保存编辑和取消未保存编辑
- 手动提交连接下的结果集修改会保留事务状态，提示用户提交或回滚
- 大对象（LOB）支持点击查看与编辑

### 🌳 对象管理

- 支持数据库/模式、表、视图、索引、序列、同义词、函数、过程、触发器、包、用户等对象浏览
- 支持表结构页查看 DDL、列信息等对象详情
- 支持对象名称复制、刷新、DDL 查看和导出
- DDL 可导出到文件、剪贴板、当前 SQL 窗口、新建 SQL 窗口或弹出窗口
- 支持数据库/模式级 DDL 导出，导出过程带进度展示和取消能力
- 支持图形化创建表、修改表
- 对象列表支持多选批量处理
- 部分数据库支持创建数据库、创建模式、创建表、创建用户、重置密码、统计更新、清空表、启用/禁用索引或触发器等菜单操作

### 📤 数据导入导出

- 元数据树提供数据导出入口，支持导出到 CSV、JSON、SQL
- 支持单表、多表批量导出
- 结果集支持导出当前已经加载到表格中的数据
- DDL 导出支持按对象、模式或数据库粒度生成脚本
- 下载/导出任务会进入下载管理区域，支持查看进度、暂停、恢复、取消等操作

### 🏠 实例运维

- 实例管理页根据数据库类型和连接权限动态展示可用功能
- 支持实例信息查看、一键巡检、运行日志、空间/容量管理、参数管理和实例启停
- 巡检结果以表格展示巡检项、命令、期望值、当前值和结论
- 空间管理支持图表展示，并按数据库能力提供创建空间、增加数据文件、删除空间、自动扩展、解除大小限制等操作
- 参数管理支持查看和修改数据库配置，部分数据库支持应用到文件或运行时
- GBase 8s / Informix 支持锁表处理，双击查看会话，右键 Kill 会话
- GBase 8s / Informix 的实例管理依赖管理员用户和远程系统能力；Oracle、MySQL 按当前已实现方言提供对应巡检和空间相关功能

### 🚀 远程安装与卸载

- 主菜单提供安装环境检查、GBase 8s / Informix / MySQL 远程安装/卸载入口
- 远程安装面向 Linux/Unix 环境，流程包括服务器连接、系统信息检查、安装包选择或上传、安装步骤执行和结果展示
- GBase 8s 远程安装向导支持 V8.7、V8.8；Informix 远程安装向导支持 12.1 以上版本
- 安装流程可自动处理用户、目录、依赖、实例初始化、空间规划、参数优化和备份脚本配置等步骤
- 卸载流程可清理数据库进程、安装目录、数据文件、用户目录、用户和用户组

### 📖 Markdown 知识库

- 左侧内置 Markdown 知识库标签页，支持按文件夹组织笔记
- 支持新建主目录、新建文件夹、新建 Markdown 文件、重命名、删除、刷新、复制粘贴和拖动移动
- 支持 Markdown 编辑和预览，编辑器提供保存、撤销、重做、查找替换等操作
- 支持搜索索引重建和关键词搜索（基于 Lucene + IK Analyzer 中文分词），适合沉淀 SQL、巡检记录、运维步骤和项目笔记
- 支持图片直接粘贴、图片另存为、链接复制、自动滚动截图等常用资料整理操作
- 支持自动标题、自动编号、自动缩进

### 🤖 AI 辅助

- 内置 AI 问答功能，支持 DeepSeek、Qwen 等大模型
- 支持记忆开关，灵活控制上下文
- 辅助 SQL 编写与数据库问题排查

### 🎨 界面与本地化

- 支持深色和浅色主题，并会应用到主界面、弹出窗口和常用控件
- 支持简体中文、繁体中文和英文界面语言切换
- 主窗口采用无边框自定义标题栏，弹出框、菜单、工具栏、表格和状态区统一使用主题样式
- 状态栏展示后台 SQL 任务、下载/导出任务和常用入口
- 表格编辑增加选中背景色，提升交互体验

### 🔄 升级与下载

- 帮助菜单提供版本检查和启动更新入口
- 内置下载管理，支持下载进度、速度、暂停、恢复、取消和完成提醒
- 支持在线平滑升级和离线平滑升级
- Windows 打包产物中包含升级脚本，便于发布 zip 包后执行平滑升级

---

## 🗃️ 数据库适配说明

当前内置适配的数据库类型：

| 数据库 | 连接 | SQL 工作台 | 对象浏览 | DDL | 巡检 | 空间管理 | 参数管理 | 远程安装 | 实例启停 |
|--------|:----:|:----------:|:--------:|:---:|:----:|:--------:|:--------:|:--------:|:--------:|
| **GBase 8S** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Informix** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **MySQL** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| **Oracle** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | — | — |
| **达梦 (DM)** | ✅ | ✅ | ✅ | ✅ | — | — | — | — | — |
| **通用 JDBC** | ✅ | ✅ | ✅ | ✅ | — | — | — | — | — |

> 不同数据库类型的能力按方言模块逐步适配，部分管理能力依赖数据库类型、驱动能力、连接用户权限以及数据库版本。可在应用菜单 `帮助 → 适配列表` 查看当前版本内置的适配项。

---

## 🚀 快速开始

### 环境要求

| 项目 | 要求 |
|------|------|
| 操作系统 | Windows 10 / 11 |
| JDK | 25.0.2（需包含 `javac`、`jar`、`jlink`、`jpackage`） |
| JavaFX JMods | 25.0.3（路径在 `build.bat` 的 `JAVAFX_JMODS` 变量中配置） |
| JavaFX SDK | 25.0.3 |

> **JDK 配置提示：**
> - 将 `javafx-jmods-25.0.3/jmods` 下所有文件复制到 `jdk-25.0.2/jmods`
> - 将 `javafx-sdk-25.0.3/bin` 下所有文件复制到 `jdk-25.0.2/bin`

### 从源码构建

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

### 典型使用流程

```
新建分类 → 新建连接 → 测试连接 → 浏览对象 → 执行 SQL → 查看结果 → 管理运维
```

1. 在左侧连接树新建连接分类
2. 新建数据库连接，选择数据库类型、驱动、地址端口、用户名密码，必要时编辑连接属性
3. 点击测试连接确认驱动和网络可用
4. 连接成功后在对象树中浏览库、模式、表、视图、索引等对象
5. 新建 SQL 标签页，选择连接和数据库后执行 SQL
6. 在结果集区域分页查看数据，必要时导出结果集或编辑可编辑结果集
7. 对对象执行 DDL 查看、导出、导入、统计更新、清空、启用/禁用等管理操作
8. 对具备权限的连接打开实例管理页，查看巡检、运行日志、空间和参数信息

---

## 📁 目录结构

```
dbboys/
├── src/                          # JavaFX 客户端源码、FXML、CSS、国际化资源
│   └── com/dbboys/
│       ├── api/                  # 核心接口层（连接、元数据、DDL、SQL 执行等接口）
│       ├── app/                  # 应用入口与全局状态
│       ├── ctrl/                 # 控制器层（主界面、SQL、结果集、连接管理等）
│       ├── css/                  # 主题样式（Cupertino 风格，亮色/暗色）
│       ├── customnode/           # 自定义 UI 组件（编辑器、表格、树节点等）
│       ├── db/local/             # 本地嵌入式数据库访问
│       ├── fxml/                 # FXML 页面布局
│       ├── i18n/                 # 国际化资源（简中/繁中/英文）
│       ├── impl/dialect/         # 数据库方言与适配实现
│       │   ├── gbase/            #   GBase 8s 方言
│       │   ├── informix/         #   Informix 方言
│       │   ├── mysql/            #   MySQL 方言
│       │   ├── oracle/           #   Oracle 方言
│       │   ├── dameng/           #   达梦方言
│       │   └── genericjdbc/      #   通用 JDBC 方言
│       ├── service/              # 业务服务层（表、索引、过程、序列等对象管理）
│       ├── ui/                   # 图标工厂与资源路径
│       ├── util/                 # 工具类
│       │   ├── remote/           #   远程安装/卸载/SSH 工具
│       │   └── tree/             #   树形导航（右键菜单、CRUD、数据加载）
│       └── vo/                   # 值对象（连接、表、索引、序列等 40+ 个 VO）
├── extlib/                       # 各数据库类型的外部驱动与扩展资源
│   ├── GBASE 8S/
│   ├── INFORMIX/
│   ├── MYSQL/
│   ├── ORACLE/
│   ├── DAMENG/
│   └── GENERAL JDBC/
├── etc/                          # 默认配置与日志配置
├── images/                       # 应用图标与界面资源
├── docs/                         # 项目和数据库相关文档
├── data/                         # 运行期本地数据目录
├── index/                        # Lucene 全文检索索引
├── build.bat                     # Windows 一键编译打包脚本
├── CHANGELOG.md                  # 更新日志
├── LICENSE                       # GPL v3 许可证
└── README.md                     # 项目说明
```

---

## ⚙️ 配置与数据

- 首次启动会初始化本地 SQLite 数据库与运行期目录
- 默认配置位于 `etc/config.properties`
- 日志配置位于 `etc/log4j2.xml`
- 数据库连接、连接分类、驱动信息、知识库索引等运行数据存放在本地数据目录中

### 主要配置项

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `UI_THEME` | 界面主题（dark / light） | `dark` |
| `UI_LANG` | 界面语言（zh-CN / zh-TW / en） | `zh-CN` |
| `SQL_EDITOR_FONT_SIZE` | SQL 编辑器字号 | `13` |
| `RESULT_FETCH_PER_TIME` | 结果集每次获取行数 | `200` |
| `CONNECT_KEEPALIVE_SECONDS` | 连接保活间隔（秒） | `180` |

### 运行数据

DBboys 会在本地保存运行数据，典型内容包括：

- 数据库连接和连接分类
- 驱动选择和连接属性
- SQL 执行历史和变更记录
- Markdown 知识库索引
- 下载任务和导出任务的临时状态
- 应用配置、主题、语言和窗口状态

> ⚠️ 敏感连接信息会保存在本地数据文件中，实际使用时请按团队安全要求管理工作目录和备份文件。

---

## 🛠️ 开发指南

### 代码结构

| 模块 | 路径 | 说明 |
|------|------|------|
| 应用入口 | `src/com/dbboys/app/Main.java` | 程序启动入口 |
| 全局状态 | `src/com/dbboys/app/AppState.java` | 运行期全局状态管理 |
| 控制器 | `src/com/dbboys/ctrl/` | 界面交互逻辑 |
| 自定义组件 | `src/com/dbboys/customnode/` | UI 组件扩展 |
| 服务层 | `src/com/dbboys/service/` | 业务逻辑封装 |
| 数据库方言 | `src/com/dbboys/impl/dialect/` | 多数据库适配实现 |
| 工具类 | `src/com/dbboys/util/` | 通用工具与远程运维 |
| 值对象 | `src/com/dbboys/vo/` | 数据传输对象 |

### 新增数据库适配

新增数据库类型时，按以下接口扩展：

1. **`DatabasePlatform`** — 注册数据库平台与能力集
2. **`ConnectionSupport`** — 实现连接逻辑
3. **`MetadataRepository`** — 实现元数据查询（表、列、索引等）
4. **`DdlRepository`** — 实现 DDL 生成与导出
5. **`SqlexeRepository`** — 实现 SQL 执行适配
6. **`InstanceAdminRepository`** — 实现实例管理（可选）

参考现有方言实现（如 `mysql/` 目录）进行扩展。

### 国际化

国际化资源位于 `src/com/dbboys/i18n/`，包含：

- `messages.properties` — 简体中文（默认）
- `messages_en.properties` — 英文
- `messages_zh_TW.properties` — 繁体中文

### 主题样式

主题样式位于 `src/com/dbboys/css/`：

- `cupertino-common.css` — 公共样式
- `cupertino-dark.css` — 深色主题变量
- `cupertino-light.css` — 浅色主题变量

---

## 📦 第三方组件

| 组件 | 用途 |
|------|------|
| JavaFX / ControlsFX | 桌面 UI 框架 |
| RichTextFX / Flowless | SQL 和 Markdown 编辑区域 |
| JSqlParser | SQL 解析辅助 |
| SQLite JDBC | 本地数据存储 |
| JSch | 远程 SSH/SFTP 能力 |
| Apache POI | 表格导出相关能力 |
| Apache PDFBox | PDF 文档文本提取 |
| CommonMark | Markdown 解析 |
| Lucene / IK Analyzer | 知识库全文搜索索引 |
| Log4j2 | 日志框架 |

---

## 📋 更新日志

当前版本：**DBboys V2.0.0beta.20260517**

### 近期更新

| 日期 | 更新内容 |
|------|----------|
| 20260522 | JDK 升级到 25.0.2，OpenJFX 升级到 25.0.3；新增 MySQL 5.7 适配与远程安装/卸载；增加浅色主题；锁表处理与 Kill 会话 |
| 20260517 | 新增 DeepSeek 4.0pro 模型、通用 JDBC、MySQL 支持 |
| 20260416 | 修复多 SELECT 内存泄露；新增结果集插入、删除行、保存、取消功能 |
| 20260412 | GBase MySQL 模式 DDL 支持；完善 Oracle；AI 增加 Qwen3.6 大模型与记忆开关 |
| 20260406 | 新增 Informix、Oracle 支持 |
| 20260329 | 全库导出 DDL；查询结果集表头显示数据类型 |
| 20260322 | 深色主题；AI 问答 |
| 20260301 | 单表/多表导出 CSV/JSON/SQL；DDL 导出；大对象查看编辑 |
| 20260221 | 国际化语言支持；图形化创建/修改表；对象列表多选批量处理 |
| 20260115 | 首次发布 |

完整更新记录请查看 [CHANGELOG.md](CHANGELOG.md)。

---

## 📄 许可

本项目基于 [GNU General Public License v3.0](LICENSE) 开源许可。
