# recaf-mcp

[English](README.md) | [简体中文](README-ZH.md)

`recaf-mcp` 通过模型上下文协议（MCP）提供无头
[Recaf](https://github.com/Col-E/Recaf) 分析与补丁能力。Python stdio
桥接器负责管理相互隔离的 Recaf JVM，Java 插件负责执行实际分析。

```text
MCP 客户端 ── stdio ──> Python 桥接器 ── 带身份验证的回环 HTTP ──> Recaf Worker
                                                               每个目标一个 JVM
```

桥接器以无头模式使用已安装的 Recaf 通用 JAR。无需检出 Recaf 源码、构建修改版
Recaf，也无需发布 Python 包。

## 功能概览

| 领域 | 能力 |
| --- | --- |
| 浏览 | 工作区摘要、类、归档文件、Android Manifest 和资源 |
| 反编译 | 反编译单个类或将选定的所有类导出到 ZIP；支持 CFR、Procyon、Vineflower 或其他已安装的 Recaf 反编译器 |
| 搜索 | 字符串、JVM/DEX 指令、反编译源码、成员声明/引用和调用图 |
| 重命名 | 类、方法和字段；导入/导出 Recaf 支持的七种映射格式 |
| 修改 JVM | 编译替换 Java 源码、运行安全的 Recaf 转换、编辑指定方法的指令 |
| 分析 APK/DEX | Android 类、解码后的二进制 XML、DEX 字符串/指令/成员/调用，以及重建的单类 DEX |
| 自动化 | 编译并运行与当前 Recaf 版本匹配的 Java 脚本，用于自定义反混淆 |
| 会话管理 | 多个隔离 Worker、JAR 保存/重新打开、按格式处理空闲清理、取消和恢复 |

全部 26 个工具都会在打开目标之前公布，因此客户端无需支持动态工具列表。完整工具目录见下文。

## 环境要求

- JDK 25，用于仓库内置的 Gradle 工具链和 Worker 进程。插件生成兼容
  Java 22 的字节码；`patch_class` 需要包含 `javac` 的 JDK，不能使用 JRE。
- Recaf 4 通用可执行 JAR，其中包含 Recaf 的运行时依赖。
  1.0.2 适配 [Recaf 构建 `17216bd3`](https://github.com/Col-E/Recaf/commit/17216bd3b76bc6ef36c4198262b5318e2936fbb7)
  （2026-10-14）及其汇编器/DEX API。升级插件前，请先更新旧版 Recaf 快照。
- Python 3.11 或更高版本。
- [`uv`](https://docs.astral.sh/uv/)，用于桥接器的锁定依赖环境。

## 安装

### 从 Release 安装（推荐）

无需检出源码或在本地构建项目。从
[最新 GitHub Release](https://github.com/Naptila/recaf-mcp/releases/latest)
下载
[`recaf-mcp-plugin-1.0.2.jar`](https://github.com/Naptila/recaf-mcp/releases/download/v1.0.2/recaf-mcp-plugin-1.0.2.jar)，
并将其复制到 Recaf 插件目录：

| 平台 | Recaf 插件目录 |
| --- | --- |
| Linux | `~/.config/Recaf/plugins` |
| macOS | `~/Library/Application Support/Recaf/plugins` |
| Windows | `%APPDATA%\Recaf\plugins` |

直接从同一个 Release 安装跨平台 Bridge wheel：

```bash
uv tool install https://github.com/Naptila/recaf-mcp/releases/download/v1.0.2/recaf_mcp_bridge-1.0.2-py3-none-any.whl
```

该命令会安装 `recaf-mcp-bridge`，项目本身无需发布到 PyPI。

### 从源码构建

如果需要自行构建插件并从本地源码安装 Bridge：

```bash
cd /absolute/path/to/recaf-mcp/recaf-mcp-plugin
./gradlew clean build
mkdir -p ~/.config/Recaf/plugins
cp build/libs/recaf-mcp-plugin-1.0.2.jar ~/.config/Recaf/plugins/
cd ..
uv tool install ./recaf-mcp-bridge
```

使用任一方式完成安装后，将 `RECAF_JAR` 指向下载的通用可执行 JAR：

```text
RECAF_JAR=/absolute/path/to/recaf.jar
```

与客户端无关的 stdio 命令如下：

```bash
RECAF_JAR="$RECAF_JAR" recaf-mcp-bridge --stdio
```

标准输出仅用于 MCP 协议数据。请通过 MCP 客户端运行该命令，不要直接在交互式终端中
运行；诊断信息会写入标准错误。

## 客户端配置

| 客户端 | 配置方式 |
| --- | --- |
| Codex CLI | `codex mcp add recaf-mcp --env RECAF_JAR="$RECAF_JAR" -- recaf-mcp-bridge --stdio` |
| Claude Code | `claude mcp add --scope user recaf-mcp -e RECAF_JAR="$RECAF_JAR" -- recaf-mcp-bridge --stdio` |
| Antigravity / 其他 JSON 客户端 | 使用下方配置 |

```json
{
  "mcpServers": {
    "recaf-mcp": {
      "command": "recaf-mcp-bridge",
      "args": ["--stdio"],
      "env": {
        "RECAF_JAR": "/absolute/path/to/recaf.jar"
      }
    }
  }
}
```

连接后调用 `recaf_list`；返回空的 `sessions` 数组，说明工具目录发现和 stdio
路由工作正常。首次启动请预留 120 秒，普通长耗时操作预留 340 秒，
`export_sources` 则预留 1,840 秒。如果客户端找不到命令，请使用
`command -v recaf-mcp-bridge` 返回的绝对路径。

## 第一次分析会话

| 步骤 | 调用 | 结果 |
| ---: | --- | --- |
| 1 | `recaf_list()` | 验证桥接器 |
| 2 | `recaf_open(input_path="/absolute/target.jar")` | 返回不透明的 `database` ID |
| 3 | `workspace_info(database=...)` | 汇总已加载的目标 |
| 4 | `list_classes(database=...)` 或其他分析工具 | 开始分析 |
| 5 | `recaf_save(database=..., output_path=...)` | 写出修改后的 JAR |
| 6 | `recaf_close(database=...)` | 保存 JAR，或关闭 APK/DEX 且不导出 |

所有生命周期路径都必须是绝对路径。每次调用 Worker 工具时都要传入返回的
`database`。省略 `save` 时，`recaf_close` 会保存 JAR 修改，但关闭 APK/DEX
会话时不会导出 JAR。如需丢弃 JAR 修改，请使用 `save=false`；Android 归档保存
请求会被明确拒绝。

## 配置

MCP 客户端通常只需设置 `RECAF_JAR`。桥接器会在 Recaf 的标准插件目录中查找一个
`recaf-mcp-plugin*.jar`，然后将它复制到每个 Worker 的私有目录中，从而避免加载
其他已安装插件和普通 Recaf 配置。正常启动 Recaf GUI 时不存在 MCP 令牌，因此
已安装的插件会保持休眠。

| 变量 | 默认值 | 用途 |
| --- | --- | --- |
| `RECAF_JAR` | 必填 | Recaf 通用可执行 JAR 的绝对路径 |
| `RECAF_PLUGIN_JAR` | 自动发现 | 可选：指定确切的插件 JAR |
| `RECAF_PLUGIN_DIR` | Recaf 标准目录 | 可选：指定插件发现目录 |
| `RECAF_JAVA_COMMAND` | `java` | Worker 使用的 JDK 可执行文件 |
| `RECAF_MAX_SESSIONS` | `2` | 并发等待/就绪 Worker 上限，取值 1–32 |
| `RECAF_WORKER_MAX_HEAP_MB` | `1024` | 每个 Worker 的最大堆，至少 128 MiB |
| `RECAF_IDLE_TTL_SEC` | `600` | 空闲清理时间：自动保存 JAR，关闭 APK/DEX 但不导出；`0` 表示禁用 |
| `RECAF_RESPONSE_PROFILE` | `compact` | `compact` 避免重复 JSON 文本；`legacy` 恢复重复文本 |
| `RECAF_LAUNCH_TIMEOUT_SEC` | `60` | 等待 Worker 启动就绪的超时时间 |
| `RECAF_SESSION_ROOT` | 系统临时目录 | 仅所有者可访问的实时会话目录 |
| `RECAF_RECOVERY_DIR` | XDG state | 仅所有者可访问的异常关闭恢复记录 |

每个 Worker 的 Bearer 令牌和回环端口都会独立生成。不要将令牌放入客户端配置或
命令行参数中。

默认的 `compact` 响应配置只会改变标准文本视图：带类型的
`structuredContent`、工具行为、结果字段、分页和错误语义均不受影响。如果 MCP
客户端只能向模型提供文本内容，请使用 `legacy`。

## 工具

生命周期工具无需 `database`。其他所有工具都使用 `recaf_open` 返回的 ID。

### 会话

| 工具 | 用途 | 关键行为 |
| --- | --- | --- |
| `recaf_open` | 打开 JAR 或 APK | 返回 `database`、保存能力和可为空的自动保存路径 |
| `recaf_list` | 列出已打开的会话 | 可用于安全检查安装是否正常 |
| `set_idle_ttl` | 修改空闲超时 | `0` 表示禁用自动空闲清理 |
| `recaf_save` | 导出修改后的 JAR | 原子写入；不支持保存 APK/DEX |
| `recaf_close` | 关闭会话 | 省略 `save` 时保存 JAR，且绝不导出 APK/DEX |

### 浏览与反编译

| 工具 | 用途 | 关键选项/输出 |
| --- | --- | --- |
| `workspace_info` | 汇总目标 | JVM、Android/DEX 和文件数量 |
| `list_classes` | 列出类 | `scope`、`dex_name`、`filter`、分页 |
| `list_files` | 列出非 class 归档条目 | Manifest、资源、配置和原生库 |
| `read_file` | 读取归档条目 | 文本、解码后的 Android XML 或原始 Base64 |
| `decompile_class` | 反编译单个类 | 运行时选择 `decompiler`、JVM/Android 范围、分页源码 |
| `export_sources` | 将选定类反编译到 ZIP | `scope`、`filter`、`regex`、`decompiler`、`overwrite` |
| `get_class_bytecode` | 读取类字节码 | 原始 JVM classfile 或重建的单类 DEX |

### 搜索与导航

| 工具 | 用途 | 最适合的场景 |
| --- | --- | --- |
| `search_strings` | 搜索常量和 DEX 字符串 | 字面量或部分正则表达式 |
| `search_instructions` | 搜索 JVM/DEX 指令 | 快速搜索整个归档，无需反编译 |
| `search_code` | 搜索反编译源码 | 使用 `filter` 缩小范围；超过 200 个类需明确允许 |
| `search_members` | 查找字段/方法声明和引用 | 所有者、名称、描述符、种类和结果类型 |
| `call_graph` | 查找调用方和被调用方 | 方向、深度、JVM/Android 范围 |

### 重命名与修改

| 工具 | 用途 | 关键行为 |
| --- | --- | --- |
| `rename_symbol` | 重命名类、方法或字段 | 感知描述符；支持 JVM 或内存中的 Android |
| `apply_mappings` | 导入映射 | Enigma、Jadx、Proguard、Simple、SRG、Tiny v1/v2 |
| `export_mappings` | 导出累计映射 | 分页 UTF-8 文本，包含数量和 SHA-256 |
| `transform_workspace` | 运行已审核的 Recaf 转换 | 仅限 JVM；支持 `dry_run` |
| `patch_class` | 编译替换 Java 源码 | 仅限 JVM；默认保留 classfile 目标版本 |
| `get_method_assembly` | 读取指定方法的准确指令 | 可编辑的 JVM Jasm；只读的 Android DEX 文本 |
| `patch_instructions` | 汇编修改后的 JVM 方法 | 完整汇编文本加原 SHA-256；支持 `dry_run` |

### 自定义 Recaf 脚本

| 工具 | 用途 | 关键行为 |
| --- | --- | --- |
| `get_recaf_script_help` | 获取版本匹配的示例 | 工作区、ASM、服务和反混淆 |
| `run_recaf_script` | 编译或运行 Recaf Java 代码 | 默认仅编译；执行时需要明确确认 |

### 通用行为

| 主题 | 行为 |
| --- | --- |
| 匹配 | 搜索文本默认使用区分大小写的子字符串；`regex=true` 启用 Java 部分正则匹配 |
| 范围 | 默认为 `jvm`；支持 Android 的工具接受 `android`，扫描/导出工具还接受 `all` |
| 分页 | 类/文件列表默认返回 20 项；搜索/调用图默认 10 项；大文本/字节内容按 32 Ki 单元分页 |
| 反编译器 | `tools/list` 公布已安装名称和默认值；经过测试的 Recaf JAR 提供 CFR、Fallback、Procyon 和 Vineflower |
| 条件读取 | 五个大内容读取工具接受 `if_sha256`；仅在仍保留上次完整内容时使用 |
| 上下文丢失 | 如果模型已不再持有该内容，请省略 `if_sha256` 并重试以重新获取 |
| 响应 | `compact` 返回一份结构化结果；`legacy` 还会将完整结果复制到文本中 |

支持条件读取的工具为 `read_file`、`decompile_class`、`export_mappings`、
`get_class_bytecode` 和 `get_method_assembly`。哈希匹配时会返回
`unchanged=true`，但不返回正文。服务端无法判断 LLM 上下文压缩是否丢弃了调用方
缓存的正文。

### JVM 与 Android 支持

| 操作 | JAR/JVM | APK/DEX |
| --- | :---: | :---: |
| 浏览、搜索、调用图 | 支持 | 支持 |
| 反编译类/导出源码 ZIP | 支持 | 支持，通过 Android 到 JVM 转换 |
| 读取字节码 | 原始 classfile | 重建的单类 DEX |
| 重命名/应用映射 | 保存 JAR 后持久化 | 仅限内存会话 |
| 修改源码或指令 | 支持 | 不支持 |
| 转换工作区 | 支持 | 不支持 |
| 保存/重建归档 | 支持 | 不支持 |

### 重要边界

| 主题 | 规则 |
| --- | --- |
| JAR 修改 | 修改保留在内存中，直到调用 `recaf_save` 或会保存的 `recaf_close` |
| APK 会话 | 仅支持分析和内存映射；不会创建自动保存路径，默认关闭、空闲清理和 Bridge 退出都不会导出 JAR |
| `read_file` | 只读取当前归档中的准确条目，绝不读取任意主机路径 |
| `export_sources` | 要求绝对输出路径，并以原子方式安装经过验证的 ZIP |
| Recaf 脚本 | 拥有完整的 Worker 文件系统/网络/进程权限，且不具备事务性 |
| 大规模源码搜索 | 优先使用 `search_instructions`；超过 200 个类时，`search_code` 必须缩小范围或设置 `allow_large_scan=true` |

精确的 schema、限制和恢复行为请参阅
[桥接器 README](recaf-mcp-bridge/README.md)。插件打包和 Worker 协议详情请参阅
[插件 README](recaf-mcp-plugin/README.md)。

## 测试

```bash
cd /absolute/path/to/recaf-mcp/recaf-mcp-plugin
./gradlew clean build

cd /absolute/path/to/recaf-mcp/recaf-mcp-bridge
uv sync --frozen
uv run pytest -q
```

可选启用的真实环境测试套件还会启动已安装的 Recaf Worker，覆盖打开、分析、修改、
分页读取、保存、重新打开、取消清理、按格式处理空闲/退出清理以及带身份验证的恢复流程。

## 故障排除

- **客户端报告 MCP 启动失败：** 确认 `recaf-mcp-bridge` 位于客户端的 `PATH`
  中，确认 `RECAF_JAR` 指向体积较大的通用可执行 JAR，确认只安装了一个 MCP
  插件，并允许本地回环套接字。
- **首次发现工具超时：** 第一次目录请求会启动一个短生命周期的 schema Worker。
  将客户端启动超时提高到 120 秒。
- **在 MCP 客户端中批量导出源码超时：** Worker 允许最长 1,800 秒，因此请将客户端
  工具超时设置为至少 1,840 秒，或缩小 `filter`/`scope`。取消或失败的导出绝不会
  安装不完整的目标 ZIP。
- **`patch_class` 报告没有编译器：** 将 `RECAF_JAVA_COMMAND` 指向 JDK，而不是
  JRE。
- **`patch_class` 拒绝旧版 classfile：** 默认的 `preserve` 策略绝不会悄悄改变
  运行时兼容性。只有在可以接受将生成的 classfile 提升到当前 javac 最低版本时，
  才使用 `target_version_policy="minimum_supported"` 重试；结果会报告修改前后的
  版本以及是否发生升级。
- **加载了意外的 Recaf 插件/配置：** 绝不要使用用户的普通 Recaf 数据目录启动。
  桥接器始终创建私有目录；正常使用时应通过桥接器启动，而不是手动启动 Worker。
- **协议解析错误：** 确保客户端没有将桥接器的 stderr 合并到 stdout。

## 许可证

本项目采用 [MIT 许可证](LICENSE)。
