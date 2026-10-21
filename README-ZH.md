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

全部 27 个工具都会在打开目标之前公布，因此客户端无需支持动态工具列表。完整工具目录见下文。

## 环境要求

- JDK 25，用于仓库内置的 Gradle 工具链和 Worker 进程。插件生成兼容
  Java 22 的字节码；`patch_class` 需要包含 `javac` 的 JDK，不能使用 JRE。
- Recaf 4 通用可执行 JAR，其中包含 Recaf 的运行时依赖。
  1.1.1 适配 [Recaf 构建 `9090128`](https://github.com/Col-E/Recaf/commit/909012806d893a61f68e2a66e174618c50840343)
  （2026-10-18）及其汇编器/DEX API。升级插件前，请先更新旧版 Recaf 快照。
- Python 3.11 或更高版本。
- [`uv`](https://docs.astral.sh/uv/)，用于桥接器的锁定依赖环境。

Worker 启动时会检查所需的 Jasm 和 DEX API，在会话就绪前拒绝不兼容的运行时。
`get_recaf_script_help` 的 `recaf_version` / `recaf_commit` 表示实际加载的 JAR，
`recaf_build_version` / `recaf_build_commit` 表示插件的编译基线，并返回
`recaf_compatible` 和 `recaf_compatibility_errors`。这些 API 检查不保证所有未来
快照的行为兼容。更换 Recaf JAR 后请重启 Bridge；它会拒绝与工具目录发现时
版本不同的 Worker。

## 安装

### 从 Release 安装（推荐）

无需检出源码或在本地构建项目。从
[最新 GitHub Release](https://github.com/Naptila/recaf-mcp/releases/latest)
下载
[`recaf-mcp-plugin-1.1.1.jar`](https://github.com/Naptila/recaf-mcp/releases/download/v1.1.1/recaf-mcp-plugin-1.1.1.jar)，
并将其复制到 Recaf 插件目录：

| 平台 | Recaf 插件目录 |
| --- | --- |
| Linux | `~/.config/Recaf/plugins` |
| macOS | `~/Library/Application Support/Recaf/plugins` |
| Windows | `%APPDATA%\Recaf\plugins` |

直接从同一个 Release 安装跨平台 Bridge wheel：

```bash
uv tool install https://github.com/Naptila/recaf-mcp/releases/download/v1.1.1/recaf_mcp_bridge-1.1.1-py3-none-any.whl
```

该命令会安装 `recaf-mcp-bridge`，项目本身无需发布到 PyPI。

升级时，请将旧插件 JAR 备份到 Recaf 插件目录之外，目录内仅保留一个
`recaf-mcp-plugin*.jar`。通过 `uv tool install --force` 加上面的 URL
重新安装 Bridge wheel，再重启 MCP 连接并重新打开工作区，使两部分均使用 1.1.1。

### 从源码构建

如果需要自行构建插件并从本地源码安装 Bridge：

```bash
cd /absolute/path/to/recaf-mcp/recaf-mcp-plugin
./gradlew clean build
mkdir -p ~/.config/Recaf/plugins
cp build/libs/recaf-mcp-plugin-1.1.1.jar ~/.config/Recaf/plugins/
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
| 5 | `recaf_save(database=..., output_path=...)` | 写出修改后的 JAR/APK/DEX |
| 6 | `recaf_close(database=...)` | 保存并关闭会话 |

所有生命周期路径都必须是绝对路径。每次调用 Worker 工具时都要传入返回的
`database`。省略 `save` 时，`recaf_close` 按检测到的原格式保存 JAR/APK/DEX。
如需丢弃修改，请使用 `save=false`。APK 输出未签名，安装前需另行签名。

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
| `RECAF_WORKER_MAX_HEAP_MB` | 自动 | 可选的每 Worker 堆上限，128–65536 MiB |
| `RECAF_IDLE_TTL_SEC` | `600` | 空闲清理时间：自动保存支持的 JAR/APK/DEX 输入；`0` 表示禁用 |
| `RECAF_RESPONSE_PROFILE` | `compact` | `compact` 避免重复 JSON 文本；`legacy` 恢复重复文本 |
| `RECAF_LAUNCH_TIMEOUT_SEC` | `60` | 等待 Worker 启动就绪的超时时间 |
| `RECAF_SESSION_ROOT` | 系统临时目录 | 仅所有者可访问的实时会话目录 |
| `RECAF_RECOVERY_DIR` | XDG state | 仅所有者可访问的异常关闭恢复记录 |

未指定堆上限时，桥接器将每个 JVM 的 `-XX:MaxRAMPercentage` 设为
60 除以 `RECAF_MAX_SESSIONS` 的数值。默认两个名额时，每个 Worker
最多使用 JVM 可见内存的 30%；原生内存和其他进程也需要内存。可以设置
`RECAF_WORKER_MAX_HEAP_MB=4096` 明确限制为 4 GiB；大 APK 内存不足时可继续调高。
托管 APK 导入最多用四个读取线程扫描资源头，最多同时解析两个 DEX（堆小于
1 GiB 时只解析一个）。每个 DEX 仅做一次保真读取，并在报告就绪前核对类集合。
普通 JAR 仍采用串行导入。
APK 资源保留在原 ZIP 中，显式读取时才完整加载；文件列表使用文件头预览及
目录记录的大小；未读取文件的类型和字符集由预览推断，显式读取后按完整内容判断。
内嵌 ZIP/JAR/APK 作为文件保留，不递归展开为工作区。
会话关闭前应保持原始输入可用且不变。单个资源完整读取的上限为 Worker 堆的
三分之一与 Java 字节数组上限中的较小值。
导入修复作为干净的初始状态，不在撤销历史中保留重复的旧 DEX 树。
APK 保存时编码当前 DEX 树，并流式复制未修改资源的压缩数据，无需将整个 APK
加载进内存；ZIP64 输出采用流式解压再压缩。导出会核对完整资源清单；脚本新增
的内嵌归档应放入文件集合，不支持新增内嵌工作区资源。输出安装前需对齐并签名。
Dalvik Jasm 可能显示 `p0` 等参数别名和接收者 `this`，返回的汇编可以直接使用
这些别名进行补丁；原生 DEX 脚本 API 仍使用物理寄存器编号。
DEX 脚本需复制只读的 `Code.getInstructions()`，修改副本后安装新的 `Code`。
`get_recaf_script_help(topic="workspace")` 已提供保留调试及异常处理元数据的完整示例。
JVM 内存耗尽时 Worker 会退出，打开错误会明确指出内存不足，不再按端口冲突
重复导入。导入较慢时可以设置 `RECAF_LAUNCH_TIMEOUT_SEC=120`；就绪超时也会直接
失败并清理，不会重新启动导入。
Recaf 报告工作区导入或插件初始化失败时，桥接器会停止 Worker 并立即返回原因，
包括 DEX 的 `Unknown opcode` 错误，无需等待就绪超时。

Android 保存及单类 DEX 编码还针对当前固定版本写入器遗漏的 `move-object/16`
操作码应用临时兼容修复，保留其 16 位寄存器参数及指令宽度；编码结束或失败后
都会恢复原来的操作码注册表。
工作区保存采用 310 秒的 HTTP 超时，包括关闭前保存和自动保存，让大型导出可以
超过普通 RPC 的 30 秒期限。

`recaf_list` 还返回状态为 `starting` 的 `pending_sessions`，以及
`occupied_slots`、`max_sessions` 和 `available_slots`。正在启动的 Worker
尚未出现在 `sessions` 中，但已占用名额；取消打开请求会停止 Worker，释放
名额及预留路径。

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
| `recaf_open` | 打开 JAR、APK 或 DEX | 返回 `database`、保存能力和可为空的自动保存路径 |
| `recaf_list` | 列出已打开、正在启动的会话及容量 | 可用于安全检查安装是否正常 |
| `set_idle_ttl` | 修改空闲超时 | `0` 表示禁用自动空闲清理 |
| `recaf_save` | 导出 JAR/APK/DEX | 按原格式原子写入；APK 输出未签名 |
| `recaf_close` | 关闭会话 | 省略 `save` 时保存 JAR/APK/DEX；`save=false` 丢弃 |

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
| `search_structure` | 按组合结构查找类 | 类/成员名称、访问掩码、描述符、原生 opcode |
| `call_graph` | 查找调用方和被调用方 | 方向、深度、JVM/Android 范围 |

`search_structure` 直接使用 Recaf 的 JVM/DEX 结构查询引擎，无需反编译。
例如 `{"database":"<id>","scope":"android","query":
{"methods":[{"name":"greet","descriptor":"()V","opcodes":[26,14]}]}}`
查找声明 `greet()V` 且包含 DEX `const-string` 与 `return-void` 的类。
各约束按 AND 组合；opcode 数值属于所选虚拟机。字段/方法数组要求不同成员
分别匹配，`[]` 要求没有对应成员。结果包含 `class_scope` 和 `dex_name`，
支持 `offset`/`limit` 稳定分页。

### 重命名与修改

| 工具 | 用途 | 关键行为 |
| --- | --- | --- |
| `rename_symbol` | 重命名类、方法或字段 | 感知描述符；支持 JVM 或 Android |
| `apply_mappings` | 导入映射 | Enigma、Jadx、Proguard、Simple、SRG、Tiny v1/v2 |
| `export_mappings` | 导出累计映射 | 分页 UTF-8 文本，包含数量和 SHA-256 |
| `transform_workspace` | 运行已审核的 Recaf 转换 | 仅限 JVM；支持类型化 `options`、有序 `phases` 和 `dry_run` |
| `patch_class` | 编译替换 Java 源码 | 仅限 JVM；默认保留 classfile 目标版本 |
| `get_method_assembly` | 读取指定方法的准确指令 | 可编辑 JVM 或 Dalvik Jasm；支持 `scope` 和精确 `dex_name` |
| `patch_instructions` | 汇编修改后的 JVM 或 Dalvik 方法 | 完整汇编文本加原 SHA-256；支持 `dry_run` |

`transform_workspace` 保留保守的元数据清理默认集合。新增常量/谓词/变量折叠、
静态值/调用结果/`invokedynamic` 内联、长元数据删除、异常处理合并和变量表规范化，
均须显式选择转换别名。通过 `options` 设置本次请求的参数，或通过 `phases`
指定有序计划，每阶段可独立设置别名、参数及最多三轮执行。整个计划在隔离的 JVM
快照中运行，dry-run 或任何阶段失败均不改动当前工作区。别名、参数范围、阶段结果
和示例见 [Bridge 契约](recaf-mcp-bridge/README.md)。

### 自定义 Recaf 脚本

| 工具 | 用途 | 关键行为 |
| --- | --- | --- |
| `get_recaf_script_help` | 获取版本匹配的示例 | 工作区、ASM、服务和反混淆 |
| `run_recaf_script` | 编译或运行 Recaf Java 代码 | 默认仅编译；执行时需要明确确认 |

安卓脚本可以遍历主资源的各个 DEX bundle，批量修改原生 DEX 树，再通过
`bundle.put` 放回重建的 `AndroidClassInfo`。脚本改动报告同时统计主 JVM 和
Android bundle 事件。`get_recaf_script_help` 的 `workspace` 主题提供完整的指令
替换示例、方法 Map 遍历和 invoke-range 参数说明。修改转换后的 JVM 视图不会改变
保存的 DEX。修改后需显式保存，APK 输出需另行签名。

类内容修改应查看 `modified_classes` 等改动列表。`workspace_changed` 只表示当前
工作区对象被替换或关闭，因此修改类时通常仍为 false。运行时错误会返回有长度限制的
`runtime_error_chain` 和堆栈帧；异常链完整时另提供 `runtime_root_cause_type` /
`runtime_root_cause_message`，用于定位根因。

### 通用行为

| 主题 | 行为 |
| --- | --- |
| 匹配 | 搜索文本默认使用区分大小写的子字符串；`regex=true` 启用 Java 部分正则匹配 |
| 结构匹配 | `search_structure` 名称默认精确匹配；嵌套的 `regex=true` 启用部分正则匹配 |
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
| 重命名/应用映射 | 保存后持久化 | 保存后持久化 |
| 修改 Java 源码 | 支持 | 不支持 |
| 修改方法指令 | 支持 | 支持，可编辑 Dalvik Jasm |
| 转换工作区 | 支持 | 不支持 |
| 保存/重建归档 | 支持 | 支持；APK 输出未签名 |

Android 导入保留紧凑编码的有符号数和浮点数、模型支持的参数名/行号/局部变量调试表，
以及显式声明的内部类访问标志。DEX 或 APK 输出安装到目标路径前，Worker 会对照
工作区检查类/成员集合、静态字段值和模型支持的调试表；这些检查不执行 APK。
类重命名会更新主资源中各 DEX 的类型引用及方法/字段所属类引用。

Java 源码工具使用独立且经过校验的 JVM 视图，包含数组转换修正和经过校验的备用
转换器。源码转换不会替换 DEX 方法体。纯 Android 输入调用 `transform_workspace`
会明确返回不支持错误。
可编辑的 Dalvik Jasm 使用物理寄存器，原调试信息单独保留，不放入编辑文本。
修改时需保留调试信息引用的标签；删除必要标签会在修改工作区前被拒绝。

### 重要边界

| 主题 | 规则 |
| --- | --- |
| JAR 修改 | 修改保留在内存中，直到调用 `recaf_save` 或会保存的 `recaf_close` |
| APK/DEX 会话 | 默认关闭、空闲清理和退出时按检测到的原格式保存，自动保存扩展名为 `.apk`/`.dex`；APK 旧签名被移除，需另行签名 |
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

在 Bridge 目录中指定通用 JAR 即可运行：

```bash
uv run --frozen python ../scripts/test_real_worker.py \
  --recaf-jar /absolute/path/to/recaf.jar \
  --plugin-jar ../recaf-mcp-plugin/build/libs/recaf-mcp-plugin-1.1.1.jar
```

运行器自动发现 Worker 身份，将输入复制到临时目录，生成小型多 DEX APK 和裸
DEX 及可执行 JVM 转换测试样本，并清理 Worker。可选的参考
APK 和大型 JAR 测试仍需通过 `RECAF_MCP_TEST_ANDROID_INPUT` 和
`RECAF_MCP_TEST_REFERENCE_INPUT` 提供样本。CI 在 PR、main 推送和版本标签推送时
运行 Linux/Windows 单元测试及 Linux 真实 Worker 测试；通用运行时从
`build.gradle` 锁定的提交构建。独立的每周/手动兼容性工作流检查上游 `master`，
发布仍仅在版本标签推送时进行。

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
