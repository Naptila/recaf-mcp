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
| Manage sessions | Multiple isolated Workers, JAR save/reopen, format-aware idle cleanup, cancellation, and recovery |

All 27 tools are advertised before a target is opened, so clients do not need
dynamic tool-list support. The complete tool catalog is shown below.

## Requirements

- JDK 25 for the checked-in Gradle toolchain and Worker processes. The plugin
  emits Java 22-compatible bytecode; `patch_class` requires a JDK with `javac`,
  not a JRE.
- A Recaf 4 generic executable JAR, which bundles Recaf's runtime dependencies.
  Version 1.1.1 targets [Recaf build `9090128`](https://github.com/Col-E/Recaf/commit/909012806d893a61f68e2a66e174618c50840343)
  (2026-10-18) and its assembler/DEX APIs. Update older Recaf snapshots before
  upgrading the plugin.
- Python 3.11 or newer.
- [`uv`](https://docs.astral.sh/uv/) for the locked Bridge environment.

The Worker checks the Jasm and DEX APIs it requires at startup. Incompatible
runtimes are rejected before a session becomes ready. `get_recaf_script_help`
reports the loaded JAR as `recaf_version` / `recaf_commit` and the plugin's
compile-time baseline as `recaf_build_version` / `recaf_build_commit`, alongside
`recaf_compatible` and `recaf_compatibility_errors`. These are API checks, not a
guarantee for every future snapshot. Restart the Bridge after changing the
Recaf JAR; it rejects Workers whose revision differs from catalog discovery.

## Install

### Install from a Release (recommended)

No source checkout or local build is required. Download the
[`recaf-mcp-plugin-1.1.1.jar`](https://github.com/Naptila/recaf-mcp/releases/download/v1.1.1/recaf-mcp-plugin-1.1.1.jar)
asset from the [latest GitHub Release](https://github.com/Naptila/recaf-mcp/releases/latest)
and copy it into Recaf's plugin directory:

| Platform | Recaf plugin directory |
| --- | --- |
| Linux | `~/.config/Recaf/plugins` |
| macOS | `~/Library/Application Support/Recaf/plugins` |
| Windows | `%APPDATA%\Recaf\plugins` |

Install the cross-platform Bridge wheel directly from the same Release:

```bash
uv tool install https://github.com/Naptila/recaf-mcp/releases/download/v1.1.1/recaf_mcp_bridge-1.1.1-py3-none-any.whl
```

This installs the `recaf-mcp-bridge` command without publishing the project to
PyPI.

When upgrading, back up the old plugin JAR outside Recaf's plugin directory and
keep exactly one `recaf-mcp-plugin*.jar` there. Reinstall the Bridge wheel with
`uv tool install --force` followed by the URL above, then restart the MCP
connection and reopen workspaces so both components use 1.1.1.

### Build from source

To build the plugin and install the Bridge from a checkout instead:

```bash
cd /absolute/path/to/recaf-mcp/recaf-mcp-plugin
./gradlew clean build
mkdir -p ~/.config/Recaf/plugins
cp build/libs/recaf-mcp-plugin-1.1.1.jar ~/.config/Recaf/plugins/
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

`recaf_list` also returns `pending_sessions` with `state="starting"`,
`occupied_slots`, `max_sessions`, and `available_slots`. A starting Worker
uses a slot before it appears in `sessions`; cancelling its open request stops
the Worker and releases that slot and its reserved paths.

## First analysis session

| Step | Call | Result |
| ---: | --- | --- |
| 1 | `recaf_list()` | Verifies the Bridge |
| 2 | `recaf_open(input_path="/absolute/target.jar")` | Returns an opaque `database` ID |
| 3 | `workspace_info(database=...)` | Summarizes the loaded target |
| 4 | `list_classes(database=...)` or another analysis tool | Begins analysis |
| 5 | `recaf_save(database=..., output_path=...)` | Writes the modified JAR/APK/DEX |
| 6 | `recaf_close(database=...)` | Saves and closes the session |

All lifecycle paths must be absolute. Pass the returned `database` to every
Worker tool. With `save` omitted, `recaf_close` saves supported JAR/APK/DEX
inputs in their detected format. Use `save=false` to discard changes. APK
exports are unsigned; sign them separately before installation.

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
| `RECAF_WORKER_MAX_HEAP_MB` | automatic | Optional per-Worker heap override, 128–65536 MiB |
| `RECAF_IDLE_TTL_SEC` | `600` | Idle cleanup time: autosave supported JAR/APK/DEX inputs; `0` disables it |
| `RECAF_RESPONSE_PROFILE` | `compact` | `compact` avoids duplicate JSON text; `legacy` restores it |
| `RECAF_LAUNCH_TIMEOUT_SEC` | `60` | Worker launch readiness deadline |
| `RECAF_SESSION_ROOT` | system temp | Owner-only live session directory |
| `RECAF_RECOVERY_DIR` | XDG state | Owner-only failed-shutdown recovery registry |

Without a heap override, the Bridge sets each JVM's `-XX:MaxRAMPercentage`
to 60 divided by `RECAF_MAX_SESSIONS`. With two slots, each Worker can use up to
30% of JVM-visible RAM; native memory and other processes also need RAM. Set
`RECAF_WORKER_MAX_HEAP_MB=4096` for an explicit 4 GiB limit, or increase it
when a large APK needs more memory. Managed APK imports index resource headers
with up to four readers and parse at most two DEX files concurrently (one with
a heap below 1 GiB). Each DEX is read once with fidelity repairs, and its class
set is checked before readiness. Generic JAR imports remain serial.
APK resources stay in the original ZIP until explicitly read; file listings use
header previews and directory sizes. Unread file kinds and charsets are inferred
from the preview; explicit reads classify the complete content.
Embedded ZIP/JAR/APK entries remain files,
without recursive workspace expansion. Keep the original input available and
unchanged until closing the session. Full resource reads are bounded by the
smaller of one third of the Worker heap and Java's byte-array limit.
Import repairs become the clean baseline without retaining duplicate undo trees.
APK saves encode the current DEX trees and stream unchanged compressed resources
without loading the whole APK into memory. ZIP64 output uses streaming
recompression. The exporter checks the complete resource inventory; embedded
workspace resources added by scripts must instead be supplied as file entries.
Saved APKs require alignment and signing before installation.
Dalvik Jasm can print parameter aliases such as `p0` and the receiver `this`;
returned assembly can be patched directly with those aliases. Native DEX script
APIs continue to use physical register numbers.
DEX scripts must copy the read-only `Code.getInstructions()` view and install a
replacement `Code`; `get_recaf_script_help(topic="workspace")` includes the
updated complete example with debug and exception metadata retained.
Workers exit on JVM out-of-memory errors; the open error identifies memory
exhaustion and does not retry the same import as a port collision. For slow
imports, set `RECAF_LAUNCH_TIMEOUT_SEC=120`; readiness timeouts also fail without
restarting the import.
If Recaf reports a terminal workspace import or plugin initialization failure,
the Bridge stops the Worker and returns the cause immediately, including DEX
`Unknown opcode` errors, instead of waiting for the readiness deadline.

Android saves and per-class DEX encoding also apply a scoped compatibility fix
for the pinned writer's missing `move-object/16` opcode. Its 16-bit register
operands and instruction width are preserved; the opcode registry is restored
after encoding, including failures.
Workspace saves use a 310-second HTTP deadline, including save-before-close
and autosave, so larger exports can finish after the general 30-second RPC limit.

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
| `recaf_open` | Open a JAR, APK, or DEX | Returns `database`, save capability, and a nullable autosave path |
| `recaf_list` | List open/starting sessions and capacity | Safe installation smoke test |
| `set_idle_ttl` | Change idle timeout | `0` disables automatic idle cleanup |
| `recaf_save` | Export JAR/APK/DEX | Atomic output in the original format; APK output is unsigned |
| `recaf_close` | Close a session | Omitted `save` saves JAR/APK/DEX; `save=false` discards |

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
| `search_structure` | Find classes by combined structure | Class/member names, access masks, descriptors, native opcodes |
| `call_graph` | Find callers and callees | Direction, depth, JVM/Android scope |

`search_structure` uses Recaf's native JVM/DEX structural query engine without
decompilation. For example, `{"database":"<id>","scope":"android","query":
{"methods":[{"name":"greet","descriptor":"()V","opcodes":[26,14]}]}}`
finds classes declaring `greet()V` with DEX `const-string` and `return-void`.
Constraints are combined with AND; opcode numbers belong to the selected VM.
Field/method arrays require distinct members, and `[]` requires none. Results
include `class_scope` and `dex_name`; use `offset`/`limit` for stable pages.

### Rename and patch

| Tool | Purpose | Key behavior |
| --- | --- | --- |
| `rename_symbol` | Rename a class, method, or field | Descriptor-aware; JVM or Android |
| `apply_mappings` | Import mappings | Enigma, Jadx, Proguard, Simple, SRG, Tiny v1/v2 |
| `export_mappings` | Export accumulated mappings | Paged UTF-8 text with counts and SHA-256 |
| `transform_workspace` | Run reviewed Recaf transforms | JVM only; typed `options`, ordered `phases`, `dry_run` |
| `patch_class` | Compile replacement Java source | JVM only; preserves classfile target by default |
| `get_method_assembly` | Read exact method instructions | Editable JVM or Dalvik Jasm; `scope` and exact `dex_name` |
| `patch_instructions` | Assemble a modified JVM or Dalvik method | Complete assembly plus prior SHA-256; supports `dry_run` |

`transform_workspace` keeps its conservative metadata cleanup defaults. Extra
constant/predicate/variable folding, static/call/`invokedynamic` inlining, long
metadata removal, catch merging, and variable-table normalization require an
explicit transformer alias. Use `options` for per-run settings, or `phases`
for an ordered plan with separate aliases, options, and up to three passes per
phase. The whole plan runs on a detached JVM snapshot; dry runs and failed
plans leave the current workspace unchanged. See the [Bridge contract](recaf-mcp-bridge/README.md)
for aliases, parameter ranges, phase results, and an example.

### Custom Recaf scripts

| Tool | Purpose | Key behavior |
| --- | --- | --- |
| `get_recaf_script_help` | Get version-matched examples | Workspace, ASM, services, deobfuscation |
| `run_recaf_script` | Compile or run Recaf Java code | Compile-only by default; execution requires explicit acknowledgement |

Android scripts can batch edits across primary DEX bundles using native DEX
trees, then reinsert rebuilt `AndroidClassInfo` objects with `bundle.put`.
Script change reports include primary JVM and Android bundle events. Read the
`workspace` topic from `get_recaf_script_help` for a complete instruction replacement,
method-map traversal, and invoke-range arguments. Editing converted JVM views does
not change the saved DEX. Save explicitly and sign APK output.

Class edits appear in `modified_classes` and the other change lists.
`workspace_changed` only reports replacement or closure of the current workspace
object, so it normally stays false during edits. Runtime failures include a bounded
`runtime_error_chain` with stack frames and `runtime_root_cause_type` /
`runtime_root_cause_message` when the chain is complete.

### Common behavior

| Topic | Behavior |
| --- | --- |
| Matching | Search text is a case-sensitive substring by default; `regex=true` enables partial Java regex |
| Structural matching | `search_structure` names are exact by default; nested `regex=true` enables partial regex |
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
| Rename / apply mappings | Persistent after save | Persistent after save |
| Patch Java source | Yes | No |
| Patch method instructions | Yes | Yes, editable Dalvik Jasm |
| Transform workspace | Yes | No |
| Save/rebuild archive | Yes | Yes; APK output is unsigned |

Android import preserves compact signed and floating-point encoded values, modeled
parameter/line/local debug tables, and explicit inner-class access flags. Before
installing an exported DEX or APK, the Worker checks class/member sets, static
field values, and modeled debug tables against the workspace. These checks do
not execute the APK. Class renames update type and member-owner references across
primary DEX bundles.

Java source tools use detached, verified JVM views with corrected array conversion
and a verified fallback converter. Source conversion does not replace DEX method
bodies. `transform_workspace` returns an explicit error for Android-only inputs.
Editable Dalvik Jasm uses physical registers and keeps existing debug metadata
outside the edited text. Keep labels referenced by that metadata when patching;
removing a required label is rejected before changing the workspace.

### Important boundaries

| Topic | Rule |
| --- | --- |
| JAR mutations | Stay in memory until `recaf_save` or a saving `recaf_close` |
| APK/DEX sessions | Saved in the detected input format on close/idle/shutdown; autosave uses `.apk`/`.dex`. APK signatures are removed; signing is a separate step |
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
format-aware idle/shutdown cleanup, and authenticated recovery.

Run it against an explicit generic JAR from the Bridge directory:

```bash
uv run --frozen python ../scripts/test_real_worker.py \
  --recaf-jar /absolute/path/to/recaf.jar \
  --plugin-jar ../recaf-mcp-plugin/build/libs/recaf-mcp-plugin-1.1.1.jar
```

The runner discovers Worker identity automatically, copies its input to a
temporary directory, generates small multi-DEX APK, raw DEX, and runnable JVM
transformation fixtures, and
cleans up Workers. Optional reference APK and large
JAR tests still require their `RECAF_MCP_TEST_ANDROID_INPUT` and
`RECAF_MCP_TEST_REFERENCE_INPUT` fixtures. CI runs unit tests on Linux/Windows
and the real suite on Linux for pull requests, main pushes, and release tags.
It builds the generic runtime from the commit pinned in `build.gradle`.
The separate weekly/manual compatibility workflow tests upstream `master`;
publishing remains restricted to version tag pushes.

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
