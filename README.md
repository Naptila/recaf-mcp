# recaf-mcp

[English](README.md) | [简体中文](README-ZH.md)

`recaf-mcp` exposes headless [Recaf](https://github.com/Col-E/Recaf) analysis
and patching through the Model Context Protocol (MCP). A Python stdio Bridge
manages isolated Recaf JVMs; a Java plugin performs the actual analysis.

```text
MCP client ── stdio ──> Python Bridge ── authenticated loopback HTTP ──> Recaf Worker
                                                               one JVM per target
```

The Bridge uses your installed Recaf generic JAR in headless mode. No Recaf
source checkout, modified Recaf build, or published Python package is needed.

## What it can do

| Area | Capabilities |
| --- | --- |
| Browse | Workspace summary, classes, archive files, Android manifest/resources |
| Decompile | One class or all selected classes to a ZIP; CFR, Procyon, Vineflower, or another installed Recaf decompiler |
| Search | Strings, JVM/DEX instructions, decompiled source, member declarations/references, call graphs |
| Rename | Classes, methods, and fields; import/export seven Recaf mapping formats |
| Patch JVM | Compile replacement Java source, run safe Recaf transforms, edit exact method instructions |
| Analyze APK/DEX | Android classes, decoded binary XML, DEX strings/instructions/members/calls, reconstructed single-class DEX |
| Automate | Compile and run version-matched Recaf Java scripts for custom deobfuscation |
| Manage sessions | Multiple isolated Workers, save/reopen, idle autosave, cancellation, cleanup, and recovery |

All 26 tools are advertised before a target is opened, so clients do not need
dynamic tool-list support. The complete tool catalog is shown below.

## Requirements

- JDK 25 for the checked-in Gradle toolchain and Worker processes. The plugin
  emits Java 22-compatible bytecode; `patch_class` requires a JDK with `javac`,
  not a JRE.
- A Recaf 4 generic executable JAR, which bundles Recaf's runtime dependencies.
- Python 3.11 or newer.
- [`uv`](https://docs.astral.sh/uv/) for the locked Bridge environment.

## Install

### Install from a Release (recommended)

No source checkout or local build is required. Download the
[`recaf-mcp-plugin-1.0.0.jar`](https://github.com/Naptila/recaf-mcp/releases/download/v1.0.0/recaf-mcp-plugin-1.0.0.jar)
asset from the [latest GitHub Release](https://github.com/Naptila/recaf-mcp/releases/latest)
and copy it into Recaf's plugin directory:

| Platform | Recaf plugin directory |
| --- | --- |
| Linux | `~/.config/Recaf/plugins` |
| macOS | `~/Library/Application Support/Recaf/plugins` |
| Windows | `%APPDATA%\Recaf\plugins` |

Install the cross-platform Bridge wheel directly from the same Release:

```bash
uv tool install https://github.com/Naptila/recaf-mcp/releases/download/v1.0.0/recaf_mcp_bridge-1.0.0-py3-none-any.whl
```

This installs the `recaf-mcp-bridge` command without publishing the project to
PyPI.

### Build from source

To build the plugin and install the Bridge from a checkout instead:

```bash
cd /absolute/path/to/recaf-mcp/recaf-mcp-plugin
./gradlew clean build
mkdir -p ~/.config/Recaf/plugins
cp build/libs/recaf-mcp-plugin-1.0.0.jar ~/.config/Recaf/plugins/
cd ..
uv tool install ./recaf-mcp-bridge
```

After installing by either method, set `RECAF_JAR` to the downloaded generic
executable JAR:

```text
RECAF_JAR=/absolute/path/to/recaf.jar
```

The client-neutral stdio command is:

```bash
RECAF_JAR="$RECAF_JAR" recaf-mcp-bridge --stdio
```

Standard output is exclusively MCP protocol data. Run the command through an
MCP client rather than directly in an interactive terminal; diagnostics go to
standard error.

## Client setup

| Client | Setup |
| --- | --- |
| Codex CLI | `codex mcp add recaf-mcp --env RECAF_JAR="$RECAF_JAR" -- recaf-mcp-bridge --stdio` |
| Claude Code | `claude mcp add --scope user recaf-mcp -e RECAF_JAR="$RECAF_JAR" -- recaf-mcp-bridge --stdio` |
| Antigravity / other JSON clients | Use the configuration below |

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

After connecting, call `recaf_list`; an empty `sessions` array confirms that
catalog discovery and stdio routing work. Allow 120 seconds for first startup,
340 seconds for normal long operations, or 1,840 seconds for
`export_sources`. If the client cannot find the command, use the absolute path
reported by `command -v recaf-mcp-bridge`.

## First analysis session

| Step | Call | Result |
| ---: | --- | --- |
| 1 | `recaf_list()` | Verifies the Bridge |
| 2 | `recaf_open(input_path="/absolute/target.jar")` | Returns an opaque `database` ID |
| 3 | `workspace_info(database=...)` | Summarizes the loaded target |
| 4 | `list_classes(database=...)` or another analysis tool | Begins analysis |
| 5 | `recaf_save(database=..., output_path=...)` | Writes a modified JAR |
| 6 | `recaf_close(database=..., save=false)` | Closes without saving |

All lifecycle paths must be absolute. Pass the returned `database` to every
Worker tool. `recaf_close` saves JAR changes by default; use `save=false` to
discard them and always use it for APK sessions.

## Configuration

The MCP client normally needs only `RECAF_JAR`. The Bridge finds one
`recaf-mcp-plugin*.jar` in Recaf's standard plugin directory, then copies it
into a private directory for each Worker so other installed plugins and normal
Recaf configuration are never loaded. During a normal Recaf GUI launch no MCP
token is present, so the installed plugin remains dormant.

| Variable | Default | Purpose |
| --- | --- | --- |
| `RECAF_JAR` | required | Absolute path to the Recaf generic executable JAR |
| `RECAF_PLUGIN_JAR` | auto-discovered | Optional exact plugin JAR override |
| `RECAF_PLUGIN_DIR` | Recaf's standard directory | Optional plugin discovery directory |
| `RECAF_JAVA_COMMAND` | `java` | JDK executable used for Workers |
| `RECAF_MAX_SESSIONS` | `2` | Concurrent pending/ready Worker cap, 1–32 |
| `RECAF_WORKER_MAX_HEAP_MB` | `1024` | Per-Worker maximum heap, at least 128 MiB |
| `RECAF_IDLE_TTL_SEC` | `600` | Default autosave/close idle time; `0` disables it |
| `RECAF_RESPONSE_PROFILE` | `compact` | `compact` avoids duplicate JSON text; `legacy` restores it |
| `RECAF_LAUNCH_TIMEOUT_SEC` | `60` | Worker launch readiness deadline |
| `RECAF_SESSION_ROOT` | system temp | Owner-only live session directory |
| `RECAF_RECOVERY_DIR` | XDG state | Owner-only failed-shutdown recovery registry |

Bearer tokens and loopback ports are generated per Worker. Do not place tokens
in client configuration or command arguments.

The default `compact` response profile changes only the standard text view:
typed `structuredContent`, tool behavior, result fields, pagination, and error
semantics are unchanged. Use `legacy` when an MCP client exposes only text
content to the model.

## Tools

Lifecycle tools do not need a `database`. Every other tool uses the ID returned
by `recaf_open`.

### Sessions

| Tool | Purpose | Key behavior |
| --- | --- | --- |
| `recaf_open` | Open a JAR or APK | Absolute input path; returns `database` |
| `recaf_list` | List open sessions | Safe installation smoke test |
| `set_idle_ttl` | Change idle timeout | `0` disables idle autosave/close |
| `recaf_save` | Export a modified JAR | Atomic output; APK/DEX save is unsupported |
| `recaf_close` | Close a session | Saves JARs by default; use `save=false` to discard |

### Browse and decompile

| Tool | Purpose | Key options/output |
| --- | --- | --- |
| `workspace_info` | Summarize the target | JVM, Android/DEX, and file counts |
| `list_classes` | List classes | `scope`, `dex_name`, `filter`, pagination |
| `list_files` | List non-class archive entries | Manifests, resources, configs, native libraries |
| `read_file` | Read an archive entry | Text, decoded Android XML, or raw Base64 |
| `decompile_class` | Decompile one class | Runtime `decompiler`, JVM/Android scope, paged source |
| `export_sources` | Decompile selected classes to ZIP | `scope`, `filter`, `regex`, `decompiler`, `overwrite` |
| `get_class_bytecode` | Read class bytes | Original JVM classfile or reconstructed single-class DEX |

### Search and navigation

| Tool | Purpose | Best use |
| --- | --- | --- |
| `search_strings` | Search constants and DEX strings | Literal or partial regex |
| `search_instructions` | Search JVM/DEX instructions | Fast whole-archive search; no decompilation |
| `search_code` | Search decompiled source | Narrow with `filter`; explicit opt-in above 200 classes |
| `search_members` | Find field/method declarations and references | Owner/name/descriptor, kind, result type |
| `call_graph` | Find callers and callees | Direction, depth, JVM/Android scope |

### Rename and patch

| Tool | Purpose | Key behavior |
| --- | --- | --- |
| `rename_symbol` | Rename a class, method, or field | Descriptor-aware; JVM or in-memory Android |
| `apply_mappings` | Import mappings | Enigma, Jadx, Proguard, Simple, SRG, Tiny v1/v2 |
| `export_mappings` | Export accumulated mappings | Paged UTF-8 text with counts and SHA-256 |
| `transform_workspace` | Run reviewed Recaf transforms | JVM only; supports `dry_run` |
| `patch_class` | Compile replacement Java source | JVM only; preserves classfile target by default |
| `get_method_assembly` | Read exact method instructions | Editable JVM Jasm; read-only Android DEX text |
| `patch_instructions` | Assemble a modified JVM method | Complete assembly plus prior SHA-256; supports `dry_run` |

### Custom Recaf scripts

| Tool | Purpose | Key behavior |
| --- | --- | --- |
| `get_recaf_script_help` | Get version-matched examples | Workspace, ASM, services, deobfuscation |
| `run_recaf_script` | Compile or run Recaf Java code | Compile-only by default; execution requires explicit acknowledgement |

### Common behavior

| Topic | Behavior |
| --- | --- |
| Matching | Search text is a case-sensitive substring by default; `regex=true` enables partial Java regex |
| Scope | `jvm` is the default; Android-aware tools accept `android`, and scan/export tools also accept `all` |
| Pagination | Class/file lists default to 20; searches/graphs to 10; large text/byte content to 32 Ki units |
| Decompilers | `tools/list` publishes the installed names and default; the tested Recaf JAR exposes CFR, Fallback, Procyon, and Vineflower |
| Conditional reads | Five large-read tools accept `if_sha256`; use it only while retaining the prior full body |
| Lost context | If the model no longer has that body, omit `if_sha256` and retry to retrieve it |
| Responses | `compact` returns one structured result; `legacy` also mirrors the complete result into text |

The conditional-read tools are `read_file`, `decompile_class`,
`export_mappings`, `get_class_bytecode`, and `get_method_assembly`. A matching
hash returns `unchanged=true` without the body. The server cannot detect
whether an LLM context compaction discarded the caller's cached body.

### JVM and Android support

| Operation | JAR/JVM | APK/DEX |
| --- | :---: | :---: |
| Browse, search, call graph | Yes | Yes |
| Decompile class / export source ZIP | Yes | Yes, through Android-to-JVM conversion |
| Read bytecode | Original classfile | Reconstructed single-class DEX |
| Rename / apply mappings | Persistent after JAR save | In-memory session only |
| Patch source or instructions | Yes | No |
| Transform workspace | Yes | No |
| Save/rebuild archive | Yes | No |

### Important boundaries

| Topic | Rule |
| --- | --- |
| JAR mutations | Stay in memory until `recaf_save` or a saving `recaf_close` |
| APK sessions | Analysis and in-memory mappings only; close with `save=false` |
| `read_file` | Reads only exact entries in the open archive, never arbitrary host paths |
| `export_sources` | Requires an absolute output path and installs a validated ZIP atomically |
| Recaf scripts | Have full Worker filesystem/network/process access and are not transactional |
| Large source search | Prefer `search_instructions`; `search_code` requires narrowing or `allow_large_scan=true` above 200 classes |

For exact schemas, limits, and recovery behavior, see the
[Bridge README](recaf-mcp-bridge/README.md). For plugin packaging and Worker
protocol details, see the [plugin README](recaf-mcp-plugin/README.md).

## Test

```bash
cd /absolute/path/to/recaf-mcp/recaf-mcp-plugin
./gradlew clean build

cd /absolute/path/to/recaf-mcp/recaf-mcp-bridge
uv sync --frozen
uv run pytest -q
```

The opt-in real suite additionally starts installed Recaf Workers and exercises
open, analysis, mutation, paged retrieval, save, reopen, cancellation cleanup,
idle shutdown, signal cleanup, and authenticated recovery.

## Troubleshooting

- **Client reports MCP startup failure:** confirm `recaf-mcp-bridge` is on the
  client's `PATH`, confirm `RECAF_JAR` points to the larger generic executable
  JAR, confirm exactly one MCP plugin is installed, and allow local loopback
  sockets.
- **Initial tool discovery times out:** the first catalog request launches one
  short-lived schema Worker. Raise the client startup timeout to 120 seconds.
- **Bulk source export times out in the MCP client:** the Worker permits up to
  1,800 seconds, so set the client's tool timeout to at least 1,840 seconds or
  narrow `filter`/`scope`. A cancelled or failed export never installs a
  partial destination ZIP.
- **`patch_class` reports no compiler:** point `RECAF_JAVA_COMMAND` to a JDK,
  not a JRE.
- **`patch_class` rejects a legacy classfile:** the default `preserve` policy
  never changes runtime compatibility silently. Retry with
  `target_version_policy="minimum_supported"` only when raising the emitted
  classfile to the current javac minimum is acceptable; the result reports
  both versions and whether an upgrade occurred.
- **Unexpected Recaf plugins/configuration:** never launch against the user's
  normal Recaf data directory. The Bridge always creates a private one; use the
  Bridge instead of hand-launching Workers for normal operation.
- **Protocol parse errors:** ensure the client does not merge Bridge stderr into
  stdout.

## License

This project is licensed under the [MIT License](LICENSE).
