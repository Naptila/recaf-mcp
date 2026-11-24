# recaf-mcp

[English](README.md) | [简体中文](README-ZH.md)

`recaf-mcp` exposes headless [Recaf 4](https://github.com/Col-E/Recaf) bytecode analysis
and patching over the Model Context Protocol (MCP). A Python stdio Bridge
supervises isolated Recaf Worker JVMs, while an unshaded Java plugin runs
inside your installed Recaf generic JAR.

```text
MCP client ── stdio ──> Python Bridge ── authenticated loopback HTTP ──> Recaf Worker
                                                               one JVM per target
```

No Recaf source checkout, modified Recaf build, or PyPI dependency is needed.

## Capabilities

| Area | Capabilities |
| --- | --- |
| Browse | Workspace summary (including per-DEX bundles & nested JARs), classes, archive files, decoded `AndroidManifest.xml`, binary XML, and `resources.arsc` tables; create/update/delete primary archive entries via `write_file` |
| Decompile | Single class or filtered source-ZIP export via CFR, Procyon, Vineflower (auto-fallback from default CFR), or Fallback, with isolated per-class timeouts, inner-class deduplication, UTF-8 identifier filenames, and Dalvik Jasm fallback |
| Search | String constants (with `filter` & `location_kind`), `\uXXXX`/raw-Unicode-normalized JVM/DEX instructions and decompiled source lines (with Dalvik Jasm fallback), exact/substring/regex member queries (`descriptor_match`), structural/opcode queries, call graphs (including unresolved JVM boundary edges, Android super-call resolution, & cached/lazy multi-DEX graphs) |
| Rename | Exact-family inheritance-safe class, method, and field renames (`AutoCloseable` / library-override guard); automatic JAR `MANIFEST.MF` + `META-INF/services/*` & APK binary `AndroidManifest.xml` sync, Mixin package-relative & config reference warnings, and lossless 7-format mapping export/import |
| Patch JVM | Compile replacement Java source (`classpath`, embedded-JAR virtual classpath, `dry_run`), run allowlisted Recaf deobfuscation transforms, assemble & ASM-verify exact Jasm methods (`\uXXXX` & dotted-name normalization + `did_you_mean` hints) |
| Analyze & Patch APK/DEX | Multi-DEX browsing/search/call graphs, editable Dalvik Jasm method patching, symbol remapping, reconstructed single-class DEX |
| Automate | Compile and execute version-matched Recaf Java scripts with structured output and primary + embedded bundle change tracking |
| Manage sessions | Concurrent isolated Workers (pre-launch magic-byte check & duplicate-open warning), atomic JAR/APK/DEX saves (manifest-first JAR streaming, original renamed-class slots, `jar_signature_status`, `zipalign -P 16 4` 16 KiB `.so` page-aligned APKs, DEX rewrite deltas, untouched DEX/nested-JAR preservation), dirty-aware autosave, recovery |

All 28 tools are advertised upfront on `tools/list`, so MCP clients do not need
dynamic tool-list notifications.

## Requirements

- **JDK 25** for building and running Workers (`patch_class` requires a full JDK
  with `javac`, not a JRE; the plugin emits Java 22-compatible bytecode).
- **Recaf 4 generic executable JAR** (`recaf-*-jar-with-dependencies.jar`).
  Version `1.2.1` targets [Recaf commit `42368be5`](https://github.com/Col-E/Recaf/commit/42368be5126f737ffa99229d707ae013aacc490f)
  (2026-11-02), including Jasm 3.2 and Recaf's native structural query and
  transformation plan APIs. Update older Recaf snapshots when upgrading.
- **Python 3.11+** and [`uv`](https://docs.astral.sh/uv/).

At startup, each Worker verifies required Recaf, Jasm, and DEX API boundaries
before marking a session ready. `get_recaf_script_help` reports both the loaded
runtime (`recaf_version` / `recaf_commit`) and the plugin's compile-time
baseline (`recaf_build_version` / `recaf_build_commit`), alongside
`recaf_compatible` and `recaf_compatibility_errors`. Restart the Bridge after
updating `RECAF_JAR`; Workers whose revision differs from initial catalog
discovery are rejected.

## Installation

### From a GitHub Release (recommended)

1. Download `recaf-mcp-plugin-1.2.1.jar` from the
   [latest GitHub Release](https://github.com/Naptila/recaf-mcp/releases/latest)
   and place it in Recaf's user `plugins` directory:

   | Platform | Recaf plugin directory |
   | --- | --- |
   | Linux | `~/.config/Recaf/plugins` |
   | macOS | `~/Library/Application Support/Recaf/plugins` |
   | Windows | `%APPDATA%\Recaf\plugins` |

2. Install the Bridge CLI with `uv`:

   ```bash
   uv tool install https://github.com/Naptila/recaf-mcp/releases/download/v1.2.1/recaf_mcp_bridge-1.2.1-py3-none-any.whl
   ```

When upgrading, keep **only one** `recaf-mcp-plugin*.jar` inside Recaf's
`plugins` directory, reinstall the wheel with `uv tool install --force <url>`,
and restart your MCP client connection.

### From source

```bash
cd /absolute/path/to/recaf-mcp/recaf-mcp-plugin
./gradlew clean build
mkdir -p ~/.config/Recaf/plugins
cp build/libs/recaf-mcp-plugin-1.2.1.jar ~/.config/Recaf/plugins/

cd ..
uv tool install ./recaf-mcp-bridge
```

### Stdio entrypoint

Point `RECAF_JAR` at your Recaf generic JAR:

```bash
RECAF_JAR=/absolute/path/to/recaf.jar recaf-mcp-bridge --stdio
```

Standard output carries MCP JSON-RPC frames exclusively; diagnostics go to
standard error. Launch the Bridge through an MCP client rather than an
interactive terminal.

## Client Setup

| Client | Configuration |
| --- | --- |
| Codex CLI | `codex mcp add recaf-mcp --env RECAF_JAR="$RECAF_JAR" -- recaf-mcp-bridge --stdio` |
| Claude Code | `claude mcp add --scope user recaf-mcp -e RECAF_JAR="$RECAF_JAR" -- recaf-mcp-bridge --stdio` |
| Antigravity / JSON MCP clients | Use the JSON block below (`RECAF_RESPONSE_PROFILE=legacy` required for Antigravity) |

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

- **Why `RECAF_RESPONSE_PROFILE=legacy` on Antigravity?** Antigravity's
  `call_mcp_tool` forwards `content` text blocks to the model without exposing
  the separate `structuredContent` JSON field. Setting `legacy` mirrors complete
  structured results, decompiled Java code, Jasm listings, and file text into a
  second text block. Clients that natively expose `structuredContent` should
  keep the default `compact` profile to avoid duplicate payloads.
- **Recommended client timeouts:** allow `120s` for first-time catalog discovery,
  `340s` for heavy transforms/scripts/saves, and `1840s` for full-archive
  `export_sources`. If `recaf-mcp-bridge` is not on the client's `PATH`, use the
  absolute path printed by `command -v recaf-mcp-bridge`.

## Quick Start

| Step | Tool Call | Purpose |
| ---: | --- | --- |
| 1 | `recaf_list()` | Smoke-test catalog discovery, active/starting sessions, and slot capacity |
| 2 | `recaf_open(input_path="/abs/path/target.jar")` | Launch an isolated Worker and return an opaque `database` ID |
| 3 | `workspace_info(database=...)` | Inspect primary JVM, Android DEX, and archive file counts |
| 4 | `list_classes`, `search_strings`, `decompile_class`, ... | Analyze or patch the workspace using `database` |
| 5 | `recaf_save(database=..., output_path="/abs/path/out.jar")` | Atomically export manifest-first JAR, 4-byte/16-KiB aligned unsigned APK, or DEX |
| 6 | `recaf_close(database=...)` | Auto-save mutated (`dirty`) sessions by default (`save=true` forces, `save=false` discards) and stop the Worker |

All filesystem paths passed to lifecycle or export tools must be absolute. JAR
exports place `META-INF/MANIFEST.MF` first (`JarInputStream`-compatible) and
stream untouched entries/directories/timestamps raw. APK exports strip
invalidated signatures, align uncompressed `STORED` entries (`16384` bytes /
16 KiB page boundary for `.so` native libraries and `4` bytes for
`resources.arsc`/other `STORED` resources, matching `zipalign -P 16 4`), and
must be signed (e.g. `apksigner`) before installation on Android devices.

## Configuration

The Bridge discovers a single `recaf-mcp-plugin*.jar` from Recaf's plugin
directory and copies only that plugin into a private per-session directory, so
other Recaf plugins and GUI settings never interfere. Launching Recaf normally
without `RECAF_MCP_TOKEN` leaves the plugin completely dormant.

| Variable | Default | Purpose |
| --- | --- | --- |
| `RECAF_JAR` | required | Absolute path to the Recaf 4 generic executable JAR |
| `RECAF_PLUGIN_JAR` | auto-discovered | Explicit path to `recaf-mcp-plugin-*.jar` |
| `RECAF_PLUGIN_DIR` | Recaf user plugins dir | Directory searched when `RECAF_PLUGIN_JAR` is unset |
| `RECAF_JAVA_COMMAND` | `java` | JDK executable used to spawn Workers |
| `RECAF_MAX_SESSIONS` | `4` | Maximum concurrent starting + ready Workers (`1`–`32`) |
| `RECAF_WORKER_MAX_HEAP_MB` | automatic | Fixed per-Worker `-Xmx` in MiB (`128`–`65536`) |
| `RECAF_IDLE_TTL_SEC` | `600` | Default idle timeout before dirty-session autosave + shutdown (`0` disables) |
| `RECAF_RESPONSE_PROFILE` | `compact` | `compact` (`structuredContent` + short text) or `legacy` (mirrors full payload to text) |
| `RECAF_LAUNCH_TIMEOUT_SEC` | `60` | Worker startup and workspace import deadline in seconds |
| `RECAF_SESSION_ROOT` | system temp | Owner-only directory for live Worker data and logs |
| `RECAF_AUTOSAVE_DIR` | XDG state | Owner-only directory for default managed autosaves (`~/.local/state/recaf-mcp/autosave`) |
| `RECAF_RECOVERY_DIR` | XDG state | Owner-only registry for adopting Workers after failed shutdown saves |

Windows package-manager Java shims (Scoop, Chocolatey, mise, jabba, etc.) on `PATH` are resolved to the underlying JDK binary automatically, while `RECAF_JAVA_COMMAND` remains available to select a specific JDK.

### Runtime & Memory Behavior

- **Heap sizing & fast failure:** When `RECAF_WORKER_MAX_HEAP_MB` is unset, each
  Worker receives `-XX:MaxRAMPercentage` equal to `60 / RECAF_MAX_SESSIONS`
  (15% per Worker at the default 4 slots). Workers enable
  `-XX:+ExitOnOutOfMemoryError`; OOM, readiness timeouts, and terminal Recaf
  import errors (such as corrupt DEX opcodes) fail immediately with redacted
  diagnostics instead of retrying as port collisions. Raise
  `RECAF_WORKER_MAX_HEAP_MB` (e.g., `4096`) or `RECAF_LAUNCH_TIMEOUT_SEC`
  (e.g., `120`) for huge multidex APKs.
- **Read-only input directories & dirty-aware autosaves:** Default managed
  autosave paths are reserved inside `RECAF_AUTOSAVE_DIR` rather than alongside
  `input_path`, so archives in read-only directories open cleanly without
  specifying `autosave_path`. Read-only sessions (`dirty=false`) close, expire,
  and shut down without writing redundant autosave copies.
- **Deferred APK archives & lossless saves:** APK non-DEX entries stay compressed
  on disk until explicitly read via `read_file` (capped at
  `min(heap / 3, Integer.MAX_VALUE)`). APK saves re-encode only modified DEX
  bundles while copying untouched secondary DEX bundles and unchanged resources
  byte-for-byte, aligning all `STORED` entries (`apk_alignment_status="aligned_16k"`
  and `native_lib_alignment_status="page_aligned_16k"` / `"no_uncompressed_so"`).
  JAR saves emit `META-INF/MANIFEST.MF` first and raw-copy untouched classes,
  resources, directory entries, timestamps, and embedded JARs (including Spring
  Boot `STORED` `BOOT-INF/lib/*.jar` entries), stripping stale signature blocks
  only when mutated. Keep the input file intact until `recaf_close`. Saves use a
  310-second RPC deadline.
- **Session slots & cancellation:** `recaf_list` reports `pending_sessions`
  (`state="starting"`), `occupied_slots`, and `available_slots`. Cancelling an
  in-flight `recaf_open` terminates the starting Worker and frees its slot and
  path reservations. Bearer tokens are generated per Worker and never exposed.

## Tool Reference

Lifecycle tools omit `database`; all Worker tools require the `database` ID
returned by `recaf_open`. Class/owner selectors accept both internal (`a/b/C`)
and dotted (`a.b.C`) names, and `scope` defaults to `"auto"` (`"android"` on
APK/DEX workspaces, `"jvm"` otherwise).

### Session Lifecycle

| Tool | Purpose | Key behavior |
| --- | --- | --- |
| `recaf_open` | Open a JAR, APK, DEX, ZIP, or `.class` | Pre-validates absolute path & magic bytes; returns `database`, `output_format`, `dirty=false`, managed `autosave_path`, and `duplicate_input_sessions` warning if already open |
| `recaf_list` | Inspect active/starting sessions | Reports slot usage, `dirty`, idle countdowns, and `save_error` without tokens |
| `set_idle_ttl` | Adjust per-session idle timeout | `0` disables idle expiration; updates reset pending retry timers |
| `recaf_save` | Export workspace atomically | Writes to `output_path` or managed `autosave_path` (never mutates `input_path` in place): manifest-first JAR (`jar_signature_status`), `aligned_16k` unsigned APK (`native_lib_alignment_status`, `rewritten_dex_entries`), or DEX |
| `recaf_close` | Close a Worker session | Saves when `dirty=true` (or `save=true`) to `output_path` or managed `autosave_path` without mutating `input_path`; pass `save=false` to discard |

### Browse & Decompile

| Tool | Purpose | Key options / output |
| --- | --- | --- |
| `workspace_info` | Summarize loaded resources | JVM/Android/file counts, per-DEX `android_bundles`, and `embedded_resources` |
| `list_classes` | List internal class names | `scope`, `dex_name`, `resource`, `include_embedded`, `filter`, scope hints |
| `list_files` | List non-class archive entries | `resource`, `include_embedded`, sizes, kinds (`text`, `android_binary_xml`, `android_arsc`, ...) |
| `read_file` | Read an archive entry | `auto`/`text`/`base64` (including decoded `resources.arsc` & raw bytes of multi-level nested JARs), `resource`, dual hashes, `if_sha256` |
| `write_file` | Edit or delete primary archive entry | `content_text`, `content_base64`, `old_text`+`new_text` (`replace_all`), `delete`, `expected_sha256`, `dry_run` |
| `decompile_class` | Decompile one class | Isolated timeout pool, auto-fallback to Vineflower (`decompiler_fallback_from`), Dalvik Jasm fallback, `\uXXXX`/dotted-name normalization & `did_you_mean` |
| `export_sources` | Export decompiled classes to ZIP | Atomic ZIP with `_recaf-mcp/manifest.json`, UTF-8 identifier filenames, inner-class deduplication (`include_inner_classes`), Android `.jasm` fallback |
| `get_class_bytecode` | Read raw class bytes | Base64 JVM classfile (`original_classfile` vs `modified_classfile`) or single-class DEX; `resource` |

### Search & Call Graph

| Tool | Purpose | Best use |
| --- | --- | --- |
| `search_strings` | Search JVM constants & DEX strings | Literal or `regex=true`, `filter`, `location_kind`, plus `resource` / `include_embedded` |
| `search_instructions` | Search JVM/DEX bytecode instructions | Fast whole-archive search with `\uXXXX`/raw-Unicode normalization; `resource` / `include_embedded` |
| `search_code` | Search decompiled Java / fallback lines | `filter`, `per_class_timeout_sec`, `\uXXXX`/raw-Unicode normalization, Android Dalvik Jasm fallback (`fallback_classes`), `include_embedded` |
| `search_members` | Find field/method declarations & xrefs | `match="exact"` (default) / `"contains"` / `"regex"`, `descriptor_match`; `O(1)` exact owner lookup; `resource` / `include_embedded` |
| `search_structure` | Match classes by structure & opcodes | Native Recaf `ClassQuery`: access flags, member sets, VM opcode multisets; `include_embedded` |
| `call_graph` | Traverse callers and callees | Unresolved JVM boundary edges, embedded `resource` metadata, Android super-call resolution, lazy forward & session-cached reverse multi-DEX graphs, `graph_dex_scope` |

### Rename, Transform & Patch

| Tool | Purpose | Key behavior |
| --- | --- | --- |
| `rename_symbol` | Rename one class, field, or method | Renames full JVM/Android method dispatch family with post-apply verification; guards external/JDK overrides (`force=true`); syncs `MANIFEST.MF`, `META-INF/services/*` & `AndroidManifest.xml`, scans Mixin/mod configs |
| `apply_mappings` | Apply inline mapping text | 7 Recaf formats (`direction_hint` on reversed ProGuard input), cascaded `Outer$Inner` & method-family expansion, `dry_run`, JAR/APK manifest & `META-INF/services/*` sync |
| `export_mappings` | Export session mapping history | Lossless 7-format export (including member-only renames on unrenamed owners), `direction` (`original_to_current` or ProGuard `target_to_original`), counts & SHA-256 |
| `transform_workspace` | Run allowlisted Recaf JVM transforms | Transactional detached snapshot; ordered `phases`, typed `options`, `dry_run` |
| `patch_class` | Compile replacement Java source | JVM only; supports external `classpath`, virtual embedded-JAR classpath (`include_embedded_classpath`), `dry_run`, and preserves original classfile target version by default |
| `get_method_assembly` | Disassemble one exact method | Editable JVM or Dalvik Jasm (canonical owner/name output, `\uXXXX` input normalization); `resource` (`editable_reason` when read-only) |
| `patch_instructions` | Assemble a replacement method | Deduplicated diagnostics, ASM `BasicVerifier` + Jasm type gate (`allow_unverifiable=true` override), `verification_passed`, `preview_assembly_sha256`, no-op hash short-circuit |

- **JVM transformations (`transform_workspace`):** Omitting `transformers` runs
  conservative metadata cleanup (`duplicate_annotations`, `illegal_annotations`,
  `illegal_signatures`, `illegal_varargs`). Additional allowlisted aliases
  include `synthetic_removal`, `unknown_attributes`, `frame_removal`,
  `dead_code`, `goto_inlining`, `redundant_try_catch`, `long_annotations`,
  `long_exceptions`, `duplicate_catches`, `variable_table_normalization`,
  `opaque_constants`, `opaque_predicates`, `variable_folding`,
  `static_value_inlining`, `call_result_inlining`, and `invokedynamic_inlining`.
  See [recaf-mcp-bridge/README.md](recaf-mcp-bridge/README.md) for per-alias
  options and multi-phase plan syntax.
- **Dalvik Jasm patching (`get_method_assembly` / `patch_instructions`):** Jasm
  3.2 computes Dalvik register frame sizes automatically from instructions and
  accepts positional `p0`/`this` aliases as well as physical `vN` registers.
  Existing line numbers, parameter names, and active local-variable debug
  metadata are preserved outside the edited text (keep labels referenced by
  debug tables; locals in registers eliminated by frame compaction are pruned
  automatically).

### Custom Recaf Scripts

| Tool | Purpose | Key behavior |
| --- | --- | --- |
| `get_recaf_script_help` | Read version-matched scripting guides | Topics: `overview`, `workspace`, `asm`, `services`, `deobfuscation` |
| `run_recaf_script` | Compile or run Java scripts in Recaf | `compile_only=true` by default; execution requires `acknowledge_full_worker_access=true` |

Scripts run unsandboxed inside the isolated Worker JVM and are not
transactional. For Android batch modifications, mutate backing DEX definitions
from `getAndroidClassBundles()` (copying read-only `Code.getInstructions()`
lists before building a replacement `Code`) and reinsert rebuilt
`AndroidClassInfo` entries via `bundle.put(...)`; editing converted JVM views
does not modify DEX output. Emit `MCP_RESULT <json>` on the final stdout line to
return structured data in `script_result`.

### Shared Conventions & Format Matrix

- **Conditional reads (`if_sha256`):** `read_file`, `decompile_class`,
  `export_mappings`, `get_class_bytecode`, and `get_method_assembly` return
  `unchanged=true` without the body when `if_sha256` matches. Omit `if_sha256`
  whenever prior context has been compacted.
- **JVM vs. Android support:**

  | Capability | JAR / JVM | APK / DEX |
  | --- | :---: | :---: |
  | Browse, search, structure query, call graph | Yes | Yes |
  | Decompile class / export source ZIP | Yes | Yes (via verified Android-to-JVM conversion) |
  | Read bytecode | Original `.class` | Reconstructed single-class `.dex` |
  | Rename symbols / apply & export mappings | Yes | Yes |
  | Method instruction assembly (`patch_instructions`) | Yes (JVM Jasm) | Yes (Dalvik Jasm) |
  | Java source compilation (`patch_class`) | Yes | No |
  | Deobfuscation transforms (`transform_workspace`) | Yes | No |
  | Save / rebuild archive | Yes | Yes (APK output is unsigned) |

For full schemas, pagination limits, timeout tiers, and recovery details, see
the [Bridge README](recaf-mcp-bridge/README.md) and
[Plugin README](recaf-mcp-plugin/README.md).

## Development

Build and verify the Worker plugin JAR:

```bash
cd recaf-mcp-plugin
./gradlew clean build
```

Install the locked Python environment and build Bridge distributions:

```bash
cd recaf-mcp-bridge
uv sync --frozen
uv build --out-dir dist
```

## Troubleshooting

- **MCP startup fails:** Verify `recaf-mcp-bridge` is on `PATH`, `RECAF_JAR`
  points to the Recaf 4 generic executable JAR, only one `recaf-mcp-plugin*.jar`
  exists in the plugins directory, and local `127.0.0.1` loopback sockets are
  permitted.
- **First `tools/list` times out:** Catalog discovery launches a short-lived
  schema Worker; set the client startup timeout to at least `120s`.
- **`export_sources` times out:** Increase the client tool timeout to `1840s` or
  narrow `filter` / `scope`. Cancelled exports clean up temporary files without
  writing partial ZIPs.
- **`patch_class` reports missing compiler or legacy target version:** Point
  `RECAF_JAVA_COMMAND` at a full JDK (not a JRE). If an older classfile target
  is below `javac`'s minimum supported release, pass
  `target_version_policy="minimum_supported"` only when upgrading that class's
  bytecode version is acceptable.
- **Protocol parse errors on stdio:** Ensure your MCP client does not merge the
  Bridge's `stderr` stream into `stdout`.

## License

Licensed under the [MIT License](LICENSE).
