# recaf-mcp

[English](README.md) | [简体中文](README-ZH.md)

`recaf-mcp` 通过模型上下文协议（MCP）提供无头（headless）
[Recaf 4](https://github.com/Col-E/Recaf) 字节码分析与补丁能力。Python stdio
桥接器负责监管相互隔离的 Recaf Worker JVM，未遮蔽依赖的 Java 插件直接运行在你本地安装的
Recaf 通用 JAR 中。

```text
MCP 客户端 ── stdio ──> Python 桥接器 ── 带认证的本地回环 HTTP ──> Recaf Worker
                                                              每个目标独占一个 JVM
```

无需检出 Recaf 源码、无需自行编译修改版 Recaf，也不依赖 PyPI 发布包。

## 核心能力

| 领域 | 支持能力 |
| --- | --- |
| 浏览与资源编辑 | 工作区摘要（含多 DEX 明细与嵌套内嵌 JAR）、类列表、归档文件列表、解码后的 `AndroidManifest.xml`/二进制 XML/`resources.arsc` 资源表；支持通过 `write_file` 新增、编辑或删除主归档资源文件 |
| 反编译 | 单类反编译或导出源码 ZIP；支持隔离超时线程池、默认 CFR 失败自动切 Vineflower、UTF-8 合法标识符文件名保留、内部类自动去重与 Dalvik Jasm `.jasm` 兜底 |
| 搜索 | 字符串常量（含 `filter` 与 `location_kind`）、自动兼容 `\uXXXX` 与原始 Unicode 的 JVM/DEX 指令与反编译源码搜索、`exact`/`contains`/`regex` 成员查询（含独立 `descriptor_match`）、结构与 opcode 查询、调用图（含 JVM 未解析外部边、内嵌归档来源路径、Android `invoke-super` 继承解析与会话缓存） |
| 重命名 | 精确分派族全层级重命名与写后校验（默认拦截 `AutoCloseable` 等外部库覆盖方法）；自动同步 JAR `MANIFEST.MF` + `META-INF/services/*` 与 APK 二进制 `AndroidManifest.xml`，支持 Mixin `package` 相对路径扫描与七种映射格式无损导出/导入 |
| 修改 JVM | 编译替换 Java 源码（支持外部 `classpath`、内嵌 JAR 虚拟类路径与 `dry_run`）、运行白名单 Recaf 反混淆转换、ASM 字节码校验门禁保护的 Jasm 方法补丁（支持 `\uXXXX`/点分类名归一化与 `did_you_mean` 提示） |
| 分析与修改 APK/DEX | 多 DEX 浏览/搜索/调用图、可编辑 Dalvik Jasm 方法补丁、符号重映射、单类 DEX 重建 |
| 脚本自动化 | 编译并执行与当前 Recaf 版本匹配的 Java 脚本，支持结构化返回及主资源 + 内嵌资源类变更追踪 |
| 会话管理 | 多并发隔离 Worker（启动前魔数快速校验与重复打开提醒）、JAR/APK/DEX 原子保存（JAR `MANIFEST.MF` 置首、重命名类保留原槽位、`jar_signature_status`、APK 16 KiB `.so` 页对齐、DEX 重写体积差统计、未改动 DEX/嵌套 JAR 原样保留）、按需脏状态自动保存与异常恢复 |

全部 28 个工具都会在首次 `tools/list` 时一次性公布，客户端无需支持动态工具列表通知。

## 环境要求

- **JDK 25**：用于项目构建与启动 Worker 进程（插件生成 Java 22 兼容字节码；`patch_class`
  需要带 `javac` 的完整 JDK，不能使用 JRE）。
- **Recaf 4 通用可执行 JAR**（`recaf-*-jar-with-dependencies.jar`）：`1.2.1`
  适配 [Recaf 提交 `42368be5`](https://github.com/Col-E/Recaf/commit/42368be5126f737ffa99229d707ae013aacc490f)
  （2026-11-02），包含 Jasm 3.2 以及 Recaf 原生结构查询与多阶段转换计划 API。升级插件时请同步更新旧版 Recaf 快照。
- **Python 3.11+** 与 [`uv`](https://docs.astral.sh/uv/)。

Worker 启动时会在会话就绪前校验所需的 Recaf、Jasm 与 DEX API 边界。
`get_recaf_script_help` 会同时报告当前实际加载的运行时（`recaf_version` / `recaf_commit`）
与插件编译基线（`recaf_build_version` / `recaf_build_commit`），以及
`recaf_compatible` 和 `recaf_compatibility_errors`。更换 `RECAF_JAR` 后请重启
Bridge；与首次目录发现版本不一致的 Worker 会被拒绝。

## 安装

### 从 GitHub Release 安装（推荐）

1. 从 [最新 GitHub Release](https://github.com/Naptila/recaf-mcp/releases/latest)
   下载 `recaf-mcp-plugin-1.2.1.jar`，放入 Recaf 的用户插件目录：

   | 平台 | Recaf 插件目录 |
   | --- | --- |
   | Linux | `~/.config/Recaf/plugins` |
   | macOS | `~/Library/Application Support/Recaf/plugins` |
   | Windows | `%APPDATA%\Recaf\plugins` |

2. 使用 `uv` 安装跨平台 Bridge 命令行工具：

   ```bash
   uv tool install https://github.com/Naptila/recaf-mcp/releases/download/v1.2.1/recaf_mcp_bridge-1.2.1-py3-none-any.whl
   ```

升级版本时，请确保 Recaf 插件目录中**仅保留一个** `recaf-mcp-plugin*.jar`，使用
`uv tool install --force <url>` 更新 Bridge，并重启客户端的 MCP 连接。

### 从源码构建

```bash
cd /absolute/path/to/recaf-mcp/recaf-mcp-plugin
./gradlew clean build
mkdir -p ~/.config/Recaf/plugins
cp build/libs/recaf-mcp-plugin-1.2.1.jar ~/.config/Recaf/plugins/

cd ..
uv tool install ./recaf-mcp-bridge
```

### Stdio 启动命令

将 `RECAF_JAR` 指向本地 Recaf 通用可执行 JAR：

```bash
RECAF_JAR=/absolute/path/to/recaf.jar recaf-mcp-bridge --stdio
```

标准输出仅传输 MCP JSON-RPC 协议帧，日志与诊断信息写入标准错误；请通过 MCP
客户端调用该命令，不要直接在交互式终端运行。

## 客户端配置

| 客户端 | 配置方式 |
| --- | --- |
| Codex CLI | `codex mcp add recaf-mcp --env RECAF_JAR="$RECAF_JAR" -- recaf-mcp-bridge --stdio` |
| Claude Code | `claude mcp add --scope user recaf-mcp -e RECAF_JAR="$RECAF_JAR" -- recaf-mcp-bridge --stdio` |
| Antigravity / 其他 JSON 客户端 | 使用下方配置（Antigravity 需显式设置 `RECAF_RESPONSE_PROFILE=legacy`） |

```json
{
  "mcpServers": {
    "recaf-mcp": {
      "command": "recaf-mcp-bridge",
      "args": ["--stdio"],
      "env": {
        "RECAF_JAR": "/absolute/path/to/recaf.jar",
        "RECAF_RESPONSE_PROFILE": "legacy"
      }
    }
  }
}
```

- **为什么 Antigravity 需要 `RECAF_RESPONSE_PROFILE=legacy`？** Antigravity 的
  `call_mcp_tool` 只向模型转发 `content` 文本块，不展示独立的 `structuredContent`
  对象。开启 `legacy` 后，Bridge 会将完整结构化 JSON、反编译 Java 源码、Jasm
  汇编以及文件文本同步附加到第二个文本块中。原生向模型暴露 `structuredContent`
  的客户端保持默认 `compact` 即可，避免重复占用上下文。
- **建议超时设置：** 首次启动发现工具目录建议预留 `120s`，复杂转换/脚本/大文件保存预留
  `340s`，整包 `export_sources` 预留 `1840s`。若客户端 `PATH` 中找不到
  `recaf-mcp-bridge`，请填写 `command -v recaf-mcp-bridge` 输出的绝对路径。

## 快速上手

| 步骤 | 调用示例 | 说明 |
| ---: | --- | --- |
| 1 | `recaf_list()` | 验证 Bridge 连接、活动/启动中会话与槽位容量 |
| 2 | `recaf_open(input_path="/abs/path/target.jar")` | 启动隔离 Worker，返回不透明会话 ID `database` |
| 3 | `workspace_info(database=...)` | 查看主资源与依赖中的 JVM 类、DEX 与归档文件数量 |
| 4 | `list_classes`、`search_strings`、`decompile_class` 等 | 携带 `database` 执行浏览、搜索或修改 |
| 5 | `recaf_save(database=..., output_path="/abs/path/out.jar")` | 按原格式原子导出 `MANIFEST.MF` 置首的 JAR、已 4B/16KiB 页对齐的未签名 APK 或 DEX |
| 6 | `recaf_close(database=...)` | 默认仅自动保存已修改（`dirty=true`）的会话（`save=true` 强制保存，`save=false` 丢弃改动）并关闭 Worker |

所有传入生命周期或导出工具的文件路径都必须是绝对路径。导出的 JAR 会强制将 `META-INF/MANIFEST.MF` 置于归档最前（兼容 `JarInputStream`）并流式原样保留未修改条目、目录结构与时间戳；导出的 APK 会移除已失效的旧签名，并自动对未压缩 `STORED` 条目执行对齐（`.so` 原生库按 `16384` 字节即 16 KiB 页边界对齐，`resources.arsc` 及其他 `STORED` 资源按 `4` 字节对齐，满足 Android 15+ `zipalign -P 16 4` 要求），安装到设备前只需执行签名（如 `apksigner`）。

## 配置说明

Bridge 会在 Recaf 插件目录中定位唯一的 `recaf-mcp-plugin*.jar`，并将其复制到每个会话私有的插件目录中运行，不会加载用户的其他插件或普通 GUI 配置。日常打开 Recaf GUI 时因不存在 `RECAF_MCP_TOKEN`，该插件完全休眠。

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `RECAF_JAR` | 必填 | Recaf 4 通用可执行 JAR 的绝对路径 |
| `RECAF_PLUGIN_JAR` | 自动发现 | 显式指定 `recaf-mcp-plugin-*.jar` 路径 |
| `RECAF_PLUGIN_DIR` | Recaf 标准插件目录 | 未设置 `RECAF_PLUGIN_JAR` 时的插件查找目录 |
| `RECAF_JAVA_COMMAND` | `java` | 启动 Worker 使用的 JDK `java` 命令 |
| `RECAF_MAX_SESSIONS` | `4` | 最大并发（启动中 + 就绪）Worker 数量（`1`–`32`） |
| `RECAF_WORKER_MAX_HEAP_MB` | 自动分配 | 每个 Worker 的固定堆上限 `-Xmx`（`128`–`65536` MiB） |
| `RECAF_IDLE_TTL_SEC` | `600` | 默认空闲超时秒数，到期仅对已修改会话自动保存并关闭（`0` 为禁用） |
| `RECAF_RESPONSE_PROFILE` | `compact` | `compact`（结构化结果 + 简短摘要）或 `legacy`（额外输出完整文本块） |
| `RECAF_LAUNCH_TIMEOUT_SEC` | `60` | Worker 启动与工作区导入就绪超时秒数 |
| `RECAF_SESSION_ROOT` | 系统临时目录 | 仅当前用户可访问的会话运行目录 |
| `RECAF_AUTOSAVE_DIR` | XDG state 目录 | 默认托管自动保存目录（`~/.local/state/recaf-mcp/autosave`） |
| `RECAF_RECOVERY_DIR` | XDG state 目录 | 仅当前用户可访问的异常退出恢复注册表目录 |

Windows 包管理器生成的 `java` 垫片（Scoop、Chocolatey、mise、jabba 等）会自动解析为底层真实 JDK 可执行文件；如需指定特定 JDK，仍可显式设置 `RECAF_JAVA_COMMAND`。

### 运行时与内存策略

- **堆大小与快速失败：** 未指定 `RECAF_WORKER_MAX_HEAP_MB` 时，Bridge 将每个 Worker 的
  `-XX:MaxRAMPercentage` 设为 `60 / RECAF_MAX_SESSIONS`（默认 4 个槽位时各占 JVM
  可见内存的 15%），并启用 `-XX:+ExitOnOutOfMemoryError`。发生 OOM、就绪超时或
  Recaf 导入致命错误（如损坏的 DEX `Unknown opcode`）时，Bridge 会立即终止该进程并返回脱敏后的根因诊断，不会误判为端口冲突重复重试。分析大型多 DEX APK 时可调高
  `RECAF_WORKER_MAX_HEAP_MB=4096` 或 `RECAF_LAUNCH_TIMEOUT_SEC=120`。
- **只读输入目录支持与按需自动保存：** 默认自动保存路径分配在用户可写的
  `RECAF_AUTOSAVE_DIR` 下，因此直接打开只读目录中的样本无需手动指定 `autosave_path`。未发生内存修改的只读会话（`dirty=false`）在关闭、空闲过期或退出时不会产生冗余自动保存副本。
- **APK 延迟按需读取与高保真保存：** APK 内的非 DEX 条目仅在调用 `read_file`
  时按需解压。保存 APK 时仅重新编码有改动的 DEX 包，未改动的次级 DEX 与资源按原始字节直接复制，并自动对齐所有 `STORED` 条目（返回 `apk_alignment_status="aligned_16k"` 与 `native_lib_alignment_status="page_aligned_16k"` / `"no_uncompressed_so"`）；保存 JAR 时保证 `META-INF/MANIFEST.MF` 置首，原样复制未改动的类、资源、目录、时间戳与内嵌 JAR（含 Spring Boot `STORED` 模式的 `BOOT-INF/lib/*.jar`），并在发生修改时自动清理已失效签名块。会话关闭前请勿移动或修改原输入文件；保存操作享有 310 秒独立超时。
- **槽位与取消清理：** `recaf_list` 会返回 `pending_sessions`（`state="starting"`）、
  `occupied_slots` 与 `available_slots`。取消正在执行的 `recaf_open` 会立即清理对应
  Worker 并释放槽位与路径预留。Bearer 令牌按进程随机生成，绝不对外暴露。

## 工具速查

生命周期工具不需要 `database`；其余所有工具均需传入 `recaf_open` 返回的 `database` ID。所有类名/`owner` 参数同时支持内部斜杠形式（`a/b/C`）与点分形式（`a.b.C`），`scope` 默认 `"auto"`（在 APK/DEX 工作区自动选择 `"android"`，其余选择 `"jvm"`）。

### 会话生命周期

| 工具 | 用途 | 关键行为 |
| --- | --- | --- |
| `recaf_open` | 打开 JAR、APK、DEX、ZIP 或 `.class` | 启动前快速校验绝对路径与文件魔数；返回 `database`、`output_format`、`dirty=false`、托管 `autosave_path` 及重复打开提醒 `duplicate_input_sessions` |
| `recaf_list` | 查看活动/启动中会话与槽位 | 报告容量、`dirty` 状态、剩余空闲时间与 `save_error`，不暴露令牌 |
| `set_idle_ttl` | 修改单会话空闲超时 | `0` 禁用自动清理；更新后重置挂起的保存重试计时 |
| `recaf_save` | 原子导出工作区 | 写入 `output_path` 或托管 `autosave_path`（绝不原地修改 `input_path`）：输出 `MANIFEST.MF` 置首 JAR（含 `jar_signature_status`）、`aligned_16k` 未签名 APK（含 `native_lib_alignment_status` 与 `rewritten_dex_entries`）或 DEX |
| `recaf_close` | 关闭 Worker 会话 | 默认在 `dirty=true`（或 `save=true`）时保存至 `output_path` 或托管 `autosave_path`（不改动 `input_path`）；传入 `save=false` 丢弃改动 |

### 浏览与反编译

| 工具 | 用途 | 关键选项 / 输出 |
| --- | --- | --- |
| `workspace_info` | 汇总工作区资源 | JVM/Android/文件计数、逐 DEX 明细 `android_bundles` 与内嵌归档 `embedded_resources` |
| `list_classes` | 列出内部类名 | 支持 `scope`、`dex_name`、`resource`、`include_embedded`、`filter` 与作用域提示 |
| `list_files` | 列出非 class 归档条目 | 支持 `resource`、`include_embedded`、字节大小、类型（`android_binary_xml`、`android_arsc` 等）与字符集 |
| `read_file` | 读取归档内单个文件 | 支持 `auto`/`text`/`base64`（含解码 `resources.arsc` 及读取多层嵌套内嵌 JAR 原始字节）、`resource`、双哈希与 `if_sha256` |
| `write_file` | 编辑或删除主归档资源条目 | 支持 `content_text`、`content_base64`、`old_text`+`new_text`（`replace_all`）、`delete`、`expected_sha256` 与 `dry_run` |
| `decompile_class` | 反编译单个类 | 隔离超时线程池、默认 CFR 失败自动切 Vineflower（`decompiler_fallback_from`）、Dalvik Jasm 兜底、`\uXXXX`/点分类名归一化与 `did_you_mean` |
| `export_sources` | 批量导出反编译源码 ZIP | 原子生成含 `_recaf-mcp/manifest.json` 的 ZIP，保留 UTF-8 合法标识符文件名，默认折叠内部类（`include_inner_classes`）并将失败 Android 类兜底导出为 `.jasm` |
| `get_class_bytecode` | 读取类字节码 | Base64 分页返回 `.class`（区分 `original_classfile` / `modified_classfile`）或重建单类 `.dex`；支持 `resource` |

### 搜索与调用图

| 工具 | 用途 | 适用场景 |
| --- | --- | --- |
| `search_strings` | 搜索常量池与 DEX 字符串 | 子串或 `regex=true` 匹配，支持 `filter`、`location_kind` 与 `resource` / `include_embedded` |
| `search_instructions` | 搜索 JVM/DEX 字节码指令 | 推荐的全包快速搜索方式，自动兼容 `\uXXXX` 与原始 Unicode；支持 `resource` / `include_embedded` |
| `search_code` | 搜索反编译/兜底源码行 | 支持 `filter`、`per_class_timeout_sec`、`\uXXXX`/原始 Unicode 归一化、Android Dalvik Jasm 兜底（`fallback_classes`）与 `include_embedded` |
| `search_members` | 查找字段/方法声明与引用 | 默认 `match="exact"` 精确匹配（可选 `"contains"` / `"regex"`）与独立 `descriptor_match`；精确 `owner` 走 $O(1)$ 直查 |
| `search_structure` | 按结构与原生 opcode 匹配类 | 原生 Recaf `ClassQuery`：访问标志、成员约束与 VM opcode 多重集；支持 `include_embedded` |
| `call_graph` | 分析方法调用方与被调用方 | 补全 JVM 未解析外部边与内嵌 `resource` 路径；Android `invoke-super` 继承解析、正向懒遍历与反向图会话缓存（`graph_dex_scope`） |

### 重命名、转换与字节码补丁

| 工具 | 用途 | 关键行为 |
| --- | --- | --- |
| `rename_symbol` | 重命名单个类、字段或方法 | 全层级方法分派族重命名与写后原子校验；默认拦截外部/JDK 覆盖方法（`force=true` 可强制）；自动同步 `MANIFEST.MF`、`META-INF/services/*` 与 `AndroidManifest.xml`，扫描 Mixin/模组配置引用 |
| `apply_mappings` | 导入内联映射文本 | 支持七种映射格式（反向 ProGuard 自动返回 `direction_hint`）、自动补全级联 `Outer$Inner` 与方法分派族、`dry_run` 及清单与 `META-INF/services/*` 联动 |
| `export_mappings` | 导出当前会话累计映射 | 七种格式无损导出（含未改名宿主类上的成员重命名），支持 `direction`（默认 `original_to_current` 或标准 ProGuard `target_to_original`）、条目统计与 SHA-256 |
| `transform_workspace` | 运行白名单 Recaf JVM 转换 | 基于隔离快照事务执行；支持有序 `phases`、类型化 `options` 与 `dry_run` |
| `patch_class` | 编译替换 Java 源码 | 仅限 JVM；支持外部 `classpath`、内嵌 JAR 虚拟类路径（`include_embedded_classpath`）与 `dry_run`，默认保持原 classfile 目标版本 |
| `get_method_assembly` | 反汇编单个精确方法 | 返回可编辑的 JVM 或 Dalvik Jasm（返回规范化类名/方法名，支持 `\uXXXX` 输入反转义）；支持 `resource`（内嵌只读附带 `editable_reason`） |
| `patch_instructions` | 汇编替换单个方法实现 | 诊断按行列消息去重，内置 ASM `BasicVerifier` + Jasm 类型门禁（`allow_unverifiable=true` 可放行）、`verification_passed`、`preview_assembly_sha256` 与原样无损短路 |

- **JVM 转换（`transform_workspace`）：** 省略 `transformers` 时默认执行保守元数据清理
  （`duplicate_annotations`、`illegal_annotations`、`illegal_signatures`、
  `illegal_varargs`）。显式可选别名包括：`synthetic_removal`、`unknown_attributes`、
  `frame_removal`、`dead_code`、`goto_inlining`、`redundant_try_catch`、
  `long_annotations`、`long_exceptions`、`duplicate_catches`、
  `variable_table_normalization`、`opaque_constants`、`opaque_predicates`、
  `variable_folding`、`static_value_inlining`、`call_result_inlining` 与
  `invokedynamic_inlining`。参数范围与多阶段配置详见
  [recaf-mcp-bridge/README.md](recaf-mcp-bridge/README.md)。
- **Dalvik Jasm 补丁（`get_method_assembly` / `patch_instructions`）：** Jasm 3.2
  会根据指令自动计算 Dalvik 寄存器帧大小，同时支持 `p0`/`this` 参数别名与物理
  `vN` 寄存器。原有的行号、参数名与局部变量调试表独立保留并在汇编后重新绑定（请保留调试信息引用的标签；若精简代码缩减了局部寄存器帧，不再使用的局部变量调试项会自动裁剪）。

### 自定义 Recaf 脚本

| 工具 | 用途 | 关键行为 |
| --- | --- | --- |
| `get_recaf_script_help` | 获取与当前版本匹配的脚本指南 | 主题：`overview`、`workspace`、`asm`、`services`、`deobfuscation` |
| `run_recaf_script` | 编译或运行 Recaf Java 脚本 | 默认 `compile_only=true`；执行须显式设置 `acknowledge_full_worker_access=true` |

脚本在隔离的 Worker JVM 内拥有完整文件系统/网络/进程权限，且不具备事务回滚。批量修改 Android DEX 时，请遍历 `getAndroidClassBundles()` 修改原生 DEX 树（需先复制只读的 `Code.getInstructions()` 列表构造新 `Code`），再通过 `bundle.put(...)` 写回重建的 `AndroidClassInfo`；修改转换后的临时 JVM 类不会影响导出的 DEX。标准输出最后一行若为 `MCP_RESULT <json>`，会自动解析到 `script_result`。

### 通用约定与格式支持矩阵

- **条件读取（`if_sha256`）：** `read_file`、`decompile_class`、`export_mappings`、
  `get_class_bytecode` 与 `get_method_assembly` 支持传入上次完整内容的哈希；若未变化则返回
  `unchanged=true` 并省略正文。若对话上下文压缩导致旧正文丢失，请省略 `if_sha256` 重新获取。
- **JVM 与 Android 能力对照：**

  | 操作 | JAR / JVM | APK / DEX |
  | --- | :---: | :---: |
  | 浏览、搜索、结构查询、调用图 | 支持 | 支持 |
  | 单类反编译 / 导出源码 ZIP | 支持 | 支持（经校验的 Android 转 JVM 视图） |
  | 读取字节码 | 原始 `.class` | 重建的独立单类 `.dex` |
  | 符号重命名 / 导入导出映射 | 支持 | 支持 |
  | 方法级指令补丁（`patch_instructions`） | 支持（JVM Jasm） | 支持（Dalvik Jasm） |
  | Java 源码编译替换（`patch_class`） | 支持 | 不支持 |
  | 工作区反混淆转换（`transform_workspace`） | 支持 | 不支持 |
  | 保存 / 重建归档 | 支持 | 支持（APK 输出未签名） |

完整的参数 schema、分页上限、超时分级与恢复机制请参阅
[Bridge README](recaf-mcp-bridge/README.md) 与
[Plugin README](recaf-mcp-plugin/README.md)。

## 本地构建与开发

编译并校验 Java 插件 JAR 产物边界：

```bash
cd recaf-mcp-plugin
./gradlew clean build
```

安装锁定的 Python 环境并构建 Bridge 发行包：

```bash
cd recaf-mcp-bridge
uv sync --frozen
uv build --out-dir dist
```

## 故障排除

- **客户端报告 MCP 启动失败：** 检查 `recaf-mcp-bridge` 是否在客户端 `PATH` 中、
  `RECAF_JAR` 是否指向 Recaf 4 通用可执行 JAR、插件目录中是否只有一个
  `recaf-mcp-plugin*.jar`，并确认允许本地 `127.0.0.1` 回环端口通信。
- **首次发现工具超时：** 首次 `tools/list` 会启动短生命周期的 schema Worker，请将客户端启动超时调至 `120s` 以上。
- **批量导出源码超时：** 将客户端工具超时设为至少 `1840s`，或通过 `filter` / `scope`
  缩小导出范围。取消或失败的导出不会留下残缺的目标 ZIP。
- **`patch_class` 报错缺少编译器或旧版字节码不兼容：** 将 `RECAF_JAVA_COMMAND` 指向完整
  JDK（而非 JRE）。若旧类版本低于当前 `javac` 支持的最低目标版本，仅在允许提升该类字节码版本时传入
  `target_version_policy="minimum_supported"`。
- **Stdio 协议解析错误：** 确保客户端未将 Bridge 的 `stderr` 合并到 `stdout`。

## 许可证

本项目基于 [MIT 许可证](LICENSE) 开源。
