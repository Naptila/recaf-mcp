# Recaf MCP Bridge

This module is the client-neutral Python MCP server. It speaks standard MCP
JSON-RPC over stdio and will supervise isolated headless Recaf Worker JVMs.

See the [root README](../README.md) for the complete build, installation, and
verified Codex/Claude/Antigravity client configuration. This page documents the
Bridge's runtime behavior and exact routed tools.

The Bridge uses the official low-level MCP Python SDK API so its tool schemas
and `CallToolResult` values remain explicit. The catalog is fixed for the
lifetime of a Bridge process and advertises `listChanged: false`; correctness
does not depend on optional client support for catalog-change notifications.

## Install

Python 3.11 or newer and `uv` are required. Install the cross-platform wheel
directly from GitHub without a checkout or local build:

```bash
uv tool install https://github.com/Naptila/recaf-mcp/releases/download/v1.0.0/recaf_mcp_bridge-1.0.0-py3-none-any.whl
```

To install from a local source checkout instead:

```bash
uv tool install ./recaf-mcp-bridge
```

Runtime dependencies have major-version upper bounds. The committed `uv.lock`
pins the development and test environment; a local tool install resolves the
declared compatible ranges. In particular, the MCP SDK remains on the
supported 1.x low-level API with `mcp>=1.27,<2`.

## Run over stdio

```bash
RECAF_JAR=/absolute/path/to/recaf.jar recaf-mcp-bridge --stdio
```

Standard output is reserved exclusively for newline-delimited MCP protocol
frames. Diagnostics are written to standard error. Closing the client input
stream ends the Bridge process cleanly.

For development and tests, create the locked project environment instead:

```bash
uv sync --frozen
uv run pytest -q
```

The Bridge exposes five lifecycle schemas plus the schemas discovered from the
installed Recaf and plugin. The first `tools/list` lazily starts a schema-only Worker,
checks its plugin build and exact schema fingerprint, freezes the merged catalog
for the Bridge lifetime, and terminates the probe. It does not depend on
tool-list change notifications.

## Worker launch configuration

The launcher consumes the user's Recaf generic executable JAR and installed
MCP plugin:

```text
RECAF_JAR=/absolute/path/to/recaf.jar
RECAF_PLUGIN_JAR=/optional/exact/recaf-mcp-plugin.jar
RECAF_PLUGIN_DIR=/optional/plugin/discovery/directory
RECAF_MAX_SESSIONS=2
RECAF_WORKER_MAX_HEAP_MB=1024
RECAF_IDLE_TTL_SEC=600
RECAF_RESPONSE_PROFILE=compact
RECAF_LAUNCH_TIMEOUT_SEC=60
RECAF_SESSION_ROOT=/optional/owner-only/session/directory
RECAF_RECOVERY_DIR=/optional/owner-only/recovery/directory
```

Without `RECAF_RECOVERY_DIR`, the Bridge uses
`$XDG_STATE_HOME/recaf-mcp/recovery`, falling back to
`~/.local/state/recaf-mcp/recovery`.

When neither plugin override is set, the Bridge finds exactly one
`recaf-mcp-plugin*.jar` in Recaf's standard user plugin directory. It honors
Recaf's `RECAF` base-directory override and the platform defaults under
`%APPDATA%`, `~/Library/Application Support`, or `$XDG_CONFIG_HOME`/`~/.config`.
Each launch copies only that plugin into a private session directory. Inputs
and autosaves are canonicalized and reserved before process creation; pending
launches count toward the session cap.

The launcher does not use a shell. It gives every attempt a new loopback port,
random bearer token, isolated Recaf data directory, plugin directory, log, and
process group. Authenticated readiness must match the expected input path,
plugin build, schema fingerprint, and fully opened workspace. Launch failures
terminate the owned process group, release all reservations, and expose only a
bounded token-redacted log tail.

## Available session flow

The lifecycle and routed analysis flow is connected end to end:

- `recaf_open(input_path, autosave_path?, idle_ttl_sec?)` returns an opaque
  `database` session ID only after authenticated workspace readiness.
- `recaf_list()` returns public session state, canonical paths, port, idle
  status, busy state, and save errors. It never returns a token.
- `set_idle_ttl(database, idle_ttl_sec)` updates the timeout from 0 (disabled)
  through 604800 seconds, touches the session, and resets a pending watchdog
  retry schedule.
- `recaf_save(database, output_path?, overwrite=false)` serializes with routed
  operations and atomically exports the primary resource. The managed autosave
  can be replaced by its owner; a user-selected existing target requires
  `overwrite=true`.
- Worker-owned `workspace_info(database)`, `list_classes(database, ...)`,
  `list_files(database, ...)`, `read_file(database, ...)`, and
  `decompile_class(database, ...)`, plus bulk source export, search, rename,
  and mapping tools, route through a generic allowlist proxy to the selected
  ready session.
- `recaf_close(database, save=true, output_path?)` saves before shutdown by
  default. If saving fails or is interrupted, the session returns to `ready`
  with `save_error` exposed by `recaf_list`, so the caller can retry.
- `recaf_close(database, save=false)` is the explicit discard path. It drains
  the session operation lock, requests graceful Worker shutdown, escalates the
  owned process group when needed, and releases capacity and path reservations.

The Bridge defensively injects required `database` into every client-facing
Worker schema, then removes it before forwarding. Worker-owned base schemas
remain routing-neutral, and internal methods such as health, save, and shutdown
cannot pass through the generic proxy. Text content, structured content, and
`isError` are preserved. MCP clients that do not expose `structuredContent` to
the model can set `RECAF_RESPONSE_PROFILE=legacy`; that profile mirrors every
nonempty structured result into a second standard text `content` block as
compact JSON. Decompiled source and script stdout remain fenced, while script
guides remain Markdown. The default `compact` profile returns only the
Worker's short text summary beside the same unchanged authoritative structured
result, avoiding a complete duplicate. Each
forwarded call has a private
operation ID; when an MCP client cancels, the Bridge asks the Worker to
interrupt that exact operation and retains the session lock until authenticated
health reports that the operation has really drained. Malformed or unreachable
Workers are marked unhealthy.

Workers enforce timeouts by expected tool cost: 15 seconds for workspace/class
metadata, bytecode pages, and script help; 60 seconds for decompilation,
string/instruction or member search, renames, and mapping export; 120 seconds for
decompiled-code search and call graphs; and 300 seconds for mapping application,
transforms, source patches, and Recaf script execution. Whole-archive
`export_sources` receives 1,800 seconds. The Bridge's normal Worker
HTTP deadline is 310 seconds and its bulk-export deadline is 1,810 seconds, so
the Worker can return its own distinct timeout error first. A timeout or
cancellation never makes the session
available for overlapping work while the interrupted task is still unwinding. If it has not
drained within 30 seconds, the Bridge force-terminates and releases that
isolated Worker rather than reusing an unsafe or permanently stuck session.

When a client supplies a standard MCP progress token, the Bridge sends a
0-of-1 start notification and a 1-of-1 completion notification. Clients that
do not request progress see identical tool behavior and results.

Save and source-export destinations must be absolute, must not alias any active
input, and cannot be owned by another session. A source export also cannot
replace the session's managed autosave. The Bridge verifies that saves return the
reserved canonical path and that the installed file exists with the reported
nonzero size before it considers a save successful. Shutdown is requested only
after that validation succeeds, so save failure never silently destroys the
live session.

## Idle autosave

The server lifespan checks for expired sessions every five seconds. The
watchdog first identifies an idle, non-busy session, marks it closing to block
new routed operations, acquires its operation lock, then re-checks liveness,
busy state, activity time, TTL, and retry deadline. A request that starts or
finishes during that race therefore prevents expiration.

An expired session is atomically saved to its managed autosave before Worker
shutdown and process-group cleanup. A save failure keeps a responsive Worker
alive, restores the session to `ready`, and leaves the bounded diagnostic in
`recaf_list.save_error`. Retries use delays of 5, 10, 20, and so on up to a
300-second maximum. A successful explicit save or `set_idle_ttl` clears the
retry schedule. `idle_ttl_sec=0` disables automatic expiration for that
session.

## Graceful shutdown and recovery

Client EOF, SIGINT, and SIGTERM stop new tool calls, cancel idle coordination,
and give each live session up to 30 seconds to drain. The Bridge atomically
saves before requesting Worker shutdown. A successful signal-driven shutdown
exits with status 0 even when the client's stdin pipe remains open.

If a save fails, the Bridge deliberately leaves that Worker alive and exits
nonzero. It writes one atomic JSON entry per session under the recovery
directory; the directory is owner-only mode 0700 and entries containing bearer
tokens are mode 0600. Stderr identifies the database, PID, registry file, and
Worker log without printing the token.

On the next catalog initialization, the Bridge authenticates each entry before
adoption. The token-protected health response must match the recorded PID,
canonical input, plugin build, schema fingerprint, and ready workspace. Linux
`/proc` process start ticks must also match both before adoption and before any
later process-group signal, preventing a stale entry from signaling a reused
PID. Invalid, unsafe, or mismatched entries are discarded without signaling a
process. Capacity-conflicting valid entries remain for a later restart.

Recovery requires the original Worker and session directory to remain alive.
SIGKILL, power loss, and host failure cannot execute this registry path; only a
previous successful save protects work in those cases.

## Core analysis tools

`list_classes(database, filter?, scope="jvm", dex_name?, offset=0, limit=20)`
returns sorted internal names from primary JVM classes, Android classes, or
both. The optional filter is a case-sensitive substring, limit is bounded to
1–500, and the result includes `total`, `returned`, nullable `next_offset`, and
structured `class_entries` carrying `class_scope` and `dex_name`.

`list_files(database, filter?, offset=0, limit=20)` returns sorted non-class
paths from the primary resource's Recaf file bundle. The optional filter is a
case-sensitive archive-path substring and pages are capped at 500. Alongside a
compact `paths` array, each structured file entry reports byte size, detected
kind, extension/directory, whether Recaf classified it as text, whether a text
representation is available, and its charset when applicable. Android binary
XML entries use `kind="android_binary_xml"` and `text_available=true`. Classes
and arbitrary host files are not included.

`read_file(database, path, format="auto", offset=0, limit=32768,
if_sha256?)` reads one exact path returned by `list_files`. Auto mode uses
Recaf's own file classification and decoder: normal text and Android binary XML return
`content_text`, while images, native libraries, and other binary data return
`content_base64`. AXML decoding uses the same Android base resources,
workspace `resources.arsc`, and Recaf cache as the GUI. Callers may force
`text` or `base64`; explicit Base64 always returns original archive bytes, and
forcing text on unsupported binary data returns an actionable error. Results
use `decoded` and `text_source` to distinguish decoded AXML from native text.
Text offsets are characters and binary offsets are bytes, explicitly reported
by `offset_unit`; pages are capped at 512 KiB-equivalent units. Results include
complete raw byte size, raw SHA-256, returned count, and nullable
`next_offset`. Under the `legacy` profile, text-only MCP clients receive text
content in a separate code fence rather than JSON-escaped inline. The path is
resolved only inside the open workspace and never against the Worker's
filesystem.

The returned `sha256` always describes the complete raw archive bytes. Passing
it back as `if_sha256` returns `unchanged=true` plus file/format/size/hash
metadata without decoding or returning `content_text`/`content_base64`.

`decompile_class(database, class_name, decompiler?, scope="jvm", dex_name?,
offset=0, limit=32768, if_sha256?)` resolves an exact primary JVM or Android
internal name. Android input is mapped through Recaf's Android-to-JVM conversion before
the selected JVM decompiler runs. With no explicit
decompiler it uses Recaf's target JVM decompiler (CFR in the installed JAR).
The `decompiler` property in `tools/list` contains canonical names as a runtime
`enum` plus the configured `default`; selected-result and error metadata carry
versions without repeating a version list in three tool schemas. The current
generic JAR offers CFR, Fallback, Procyon, and Vineflower. Success returns a source slice, full `source_length`,
`returned_chars`, nullable `next_offset`, `result_type`, and selected
decompiler name/version. The page limit is 500,000 characters. Missing
workspaces/classes, unavailable decompilers, and failed results are normal MCP
tool errors. Schema-aware MCP paths reject values outside the advertised enum;
the Worker's defensive error also carries `available_decompilers` and
`default_decompiler` for direct protocol callers.

Every successful page includes `source_sha256` for the complete UTF-8 source.
Passing it back as `if_sha256` returns `unchanged=true`, source length, class
identity, and decompiler metadata without source or pagination fields.

`export_sources(database, output_path, overwrite=false, filter?,
regex=false, decompiler?, scope="jvm", dex_name?)` decompiles every selected
class into one ZIP. `output_path` is a required absolute path whose parent must
exist. Omitting `filter` selects the complete scope; otherwise class names use
the same case-sensitive literal substring or partial Java regex matching as
the search tools. Use a package prefix for a subtree or an anchored regex for
one exact class. Scope accepts `jvm`, `android`, or `all`; `dex_name` narrows an
Android scope. The runtime decompiler enum/default/version metadata is the same
as `decompile_class` and `search_code`.

Source paths are `jvm/<internal-name>.java` and
`android/<dex-name>/<internal-name>.java`, with unsafe path-segment bytes
percent-escaped. `_recaf-mcp/manifest.json` records source paths, identities,
byte/character sizes, and every per-class failure. Successfully decompiled
classes are retained even when other classes fail, while `complete` and the
counts make a partial archive unambiguous. Response failure details are capped
at 100; the manifest retains the full set. The result also reports canonical
path, ZIP byte/entry counts, and SHA-256.

The Worker streams results in stable class order with at most eight Recaf
decompilations in flight. It temporarily disables new Recaf decompilation-cache
entries for this operation and restores the previous setting afterward, which
prevents all exported source from remaining resident on large targets. Output
is written to a temporary sibling, ZIP-validated, fsynced, and atomically
installed. Existing destinations require `overwrite=true`; input, autosave,
and cross-session aliases are rejected. The operation has a 30-minute Worker
deadline, so configure the MCP client for at least 1,840 seconds when exporting
large archives. This derived source ZIP does not modify or rebuild a JAR/APK.

`search_strings(database, text, regex=false, scope="jvm", dex_name?, offset=0,
limit=10)` uses Recaf's string query service over selected primary JVM and/or
Android classes. It returns class/DEX identity, optional member
kind/name/descriptor, optional instruction index, and the matched constant.
Values longer than 4096 characters are explicitly truncated.

`search_instructions(database, text, regex=false, filter?, scope="jvm",
dex_name?, offset=0, limit=10)` searches instructions without decompiling
classes. JVM scope uses Recaf's `InstructionQuery`; Android scope walks the
native DEX instruction tree. Hits include class/DEX identity, method name and
descriptor, instruction index, formatted instruction, and a DEX byte offset
for Android hits. For example, queries can match an invoke opcode, field
operation, referenced internal class name, descriptor, or constant value. The
optional class/package `filter` is applied before instruction traversal. This
is the recommended whole-archive bytecode search, especially for large or
obfuscated inputs.

`search_code(database, text, regex=false, filter?, decompiler?, scope="jvm",
dex_name?, allow_large_scan=false, offset=0, limit=10)` decompiles selected
primary JVM and/or Android classes in sorted order and returns one hit per
matching source line with class/DEX identity and line number. Android classes
use Recaf's Android-to-JVM conversion. The optional decompiler uses the
canonical runtime enum value;
otherwise Recaf's configured target is used. Decompilations use Recaf's shared
cache and thread pool with at most eight requests in flight while results are
consumed in stable class order. Repeated searches with the same decompiler can
reuse cached source. Its `decompiler` property exposes the same runtime enum
and default as `decompile_class`.

The optional case-sensitive `filter` is applied to JVM internal class names
before decompilation. Pass a full class path such as `com/example/Foo` to narrow
to that class name (and matching nested classes), or a package prefix such as
`com/example/` to search a package. The Worker preflights the selected scope:
more than 200 classes returns an immediate structured error with the class
count and suggestions, before starting any decompiler work. Narrow `filter` or
use `search_instructions` for whole-JAR bytecode searches. Set
`allow_large_scan=true` only to accept the cold-start cost deliberately, for
example when source is already cached. The guard never returns a silently
incomplete first-200-class result. Successful responses echo the guard choice
and report classes selected, scanned, and failed plus the selected
decompiler/version; lines longer than 2000 characters are explicitly
truncated.

All four search tools use case-sensitive literal substring matching by default
or a partial Java regex, cap queries at 500 characters and pages at 200, and
return `total`, `returned`, and nullable `next_offset`. Collection stops at
20,000 hits and reports `collection_truncated` if that global safety cap is
reached.

`search_members(database, owner?, name?, descriptor?, regex=false,
kind="any", results="both", filter?, scope="jvm", dex_name?, offset=0,
limit=10)` delegates to
Recaf's member `DeclarationQuery` and `ReferenceQuery`. At least one of owner,
name, or descriptor is required at runtime; the schema intentionally keeps all
three as ordinary optional properties instead of a top-level `anyOf`, because
several MCP clients otherwise collapse the typed signature. Omitted selectors
match any value. Supplied
selectors use the same matching contract as the other search tools: a
case-sensitive literal substring by default, or a partial Java regex when
`regex=true`. The one regex flag applies to every supplied selector. `kind`
selects `field`, `method`, or `any`, while `results` selects `declarations`,
`references`, or `both`. When both are requested, Recaf composes the two
queries and scans the selected primary JVM and/or Android classes once. The
optional `filter` limits the classes containing declarations or references
before traversal.
The Worker reapplies selectors, member/result kind, and class filter to final
member identities before sorting and pagination, so internal Recaf matches
cannot leak outside the public query contract.

Each hit identifies the result/member kind, target owner/name/descriptor,
declaration access flags when applicable, containing class/member, and an
instruction index when the reference has one. Results are stable, paged to at
most 200 items, and capped at 20,000 accepted hits. For example, a method
search including its declaration is:

```text
search_members(
  database,
  owner="com/example/Foo",
  name="run",
  descriptor="()V",
  kind="method",
  results="both"
)
```

Use anchored regular expressions when exact identity is required, for example
`name="^run$"` with `regex=true`.

Invalid regular expressions return a bounded parameter diagnostic naming the
selector, Java regex failure, and character index instead of a generic
`Invalid params` message.

For all Android-aware tools, `scope` defaults to `jvm`; scan and bulk-export
tools also accept `all`, while exact lookup tools accept `jvm` or `android`.
`dex_name`
such as `classes2.dex` is valid only with Android scope. Every Android result
retains that identity, which avoids ambiguity when the same internal name is
present in multiple DEX bundles. The current Recaf generic JAR registers no
source-producing `AndroidDecompiler`, so reusing its JVM conversion plus the
discoverable JVM decompiler enum is intentional and reported as
`recaf_android_to_jvm`.

Android bytecode analysis operates directly on Recaf's DEX tree. Transform,
patch, save, and APK/DEX archive rebuilding remain unsupported, but the three
mapping tools support in-memory DEX symbol renames and mapping-text export.
Bulk decompilation can export derived Android Java source without changing
that archive boundary. Close APK sessions with `recaf_close(save=false)`.

`rename_symbol(database, kind, name, new_name, owner?, descriptor?,
scope="jvm", dex_name?)` renames one exact primary-resource JVM or Android
symbol. Class renames use internal names and do not accept owner or descriptor.
Field and method renames require the declaring class internal name and complete
JVM-style descriptor, preventing overload or same-name field ambiguity.
Android selection may include an exact DEX bundle name. Recaf's search first
selects the declaration and all referrer classes, then its mapping applier
rewrites those definitions and references without cloning every class in a
large APK. The result reports JVM/Android changed-class counts and DEX identity.
Malformed shapes, absent targets, target conflicts, and no-op names are tool or
argument errors and leave the workspace unchanged until a valid mapping is
applied. Android results explicitly report `in_memory_only=true` and
`save_required=false`.

`apply_mappings(database, format, mappings, unique_keys=false, dry_run=false,
scope="jvm", offset=0, limit=100)` parses inline mapping text with Recaf and
rewrites JVM and/or Android definitions and references together. The runtime
`format` enum currently
lists all seven installed text formats: Enigma, Jadx (Legacy), Proguard,
Simple, SRG, Tiny-V1, and Tiny-V2. Source/input names must match the current
workspace; destination/output names are the requested replacements.
`dry_run=true` performs parsing, resolution, and collision checks without
mutation. `unique_keys=true` opts into Recaf's globally unique identifier
lookup for suitable mapping sets. Text is capped at 750,000 characters,
changed-class pages at 500, and the result includes mapping counts and a
SHA-256 digest. `scope` accepts `jvm`, `android`, or `all` and requires at least
one change in that domain; it is not an isolation boundary because Recaf may
also update cross-domain referrers. JAR changes remain in memory until an
explicit save. APK/DEX mappings are session-only, so export their mapping text
and close without saving.

`export_mappings(database, format, offset=0, limit=32768, if_sha256?)` formats
the current session's Recaf aggregate mapping history as original-to-current text.
It exposes the same seven-value runtime format enum as `apply_mappings` and
returns a UTF-8 page of at most 512 Ki characters, complete character/byte
sizes, mapping counts, and whole-document SHA-256. The complete generated
document is capped at 16 Mi characters. No Worker filesystem destination is
accepted. Recaf compresses
transitive renames, so successive `a -> b -> c` changes export as `a -> c`.
When accumulated field mappings lack descriptors, formats requiring field-type
differentiation are rejected instead of emitting a lossy document. Export
before closing the session because mapping history is not reconstructed from a
reopened renamed JAR. Under the `legacy` profile, text-only MCP clients receive
the mapping page in a separate fence. JVM and DEX mapping histories are
supported; empty histories are rejected. This is mapping-text export only, not
JAR/APK archive export.

Passing `mapping_sha256` back as `if_sha256` returns `unchanged=true` with
format, counts, complete sizes, and digest but no mapping text or page fields.

`call_graph(database, owner, name, descriptor, direction="both", max_depth=1,
stop_at_external=true, scope="jvm", dex_name?, offset=0, limit=10)` starts at
one exact JVM or Android method. JVM scope traverses Recaf's workspace call
graph. Android scope builds symbolic call edges from native DEX invoke
instructions; caller/both direction indexes all classes in the selected
Android scope, including every DEX when `dex_name` is omitted. Direction can
be `calls`, `callers`, or `both`; depth is bounded to 1–5 and pages to 200
edges. Each identity includes owner, name, descriptor, resolved state,
class scope, and nullable DEX identity. The result also reports visited
vertices and whether its
10,000-vertex or 20,000-edge safety cap truncated graph collection. The
default preserves edges to unresolved JDK/third-party methods but does not
expand those vertices. This avoids reverse fan-out through common library
calls; set it to `false` only when that broader traversal is wanted.
`external_vertices_stopped` reports the number of unique boundary vertices.

`transform_workspace(database, transformers?, class_prefix?, max_passes=1,
dry_run=false, offset=0, limit=100)` runs only reviewed Recaf JVM transformer
aliases. Omitting `transformers` selects the conservative
`duplicate_annotations`, `illegal_annotations`, `illegal_signatures`, and
`illegal_varargs` metadata cleanup set. Optional allowlisted transforms are
`unknown_attributes`, `frame_removal`, `dead_code`, `goto_inlining`, and
`redundant_try_catch`. Processing is restricted to the primary JVM resource
and can be narrowed by internal-name prefix. Dry runs return the same bounded
modified-class, per-transformer, removal, mapping, and failure summaries but do
not call `apply()`. Any transformer failure also prevents all result changes
from being applied.

`patch_class(database, class_name, source, target_version_policy="preserve")`
compiles complete Java source against the current Recaf workspace and replaces
one existing primary JVM class. It preserves the class's Java target version by
default and emits debug variables, line numbers, and source name. If a legacy
input is older than the current javac can emit, the safe default returns an
actionable error. Explicit `target_version_policy="minimum_supported"` raises
only the emitted classfile to that compiler minimum. Responses expose input and
target Java/classfile versions plus `classfile_version_upgraded`; compiler
output at any other version is rejected. The opt-in upgrade is unsuitable when
the saved JAR must still run on the original older JVM. The source must emit the
requested class; only that class and generated names beginning with
`class_name + "$"` are accepted, so a second unrelated top-level class cannot
be overwritten implicitly.
Compilation is limited to 750,000 source characters, 100 output classes, and
32 MiB of class data. Structured diagnostics are capped at 100 entries, and a
successful result reports updated internal names, companion count, byte size,
and original/patched SHA-256 hashes. Saving remains explicit through
`recaf_save` or `recaf_close`.

`get_method_assembly(database, owner, name, descriptor, scope="jvm",
dex_name?, if_sha256?)` resolves one exact JVM or Android method. JVM scope
returns the complete editable Recaf Jasm document with `assembly_sha256`, owning class
hash, access flags, classfile version, and bounded assembler diagnostics.
Android scope returns complete native DEX instruction text with offsets,
register/in/out counts, DEX version, and `editable=false` /
`patch_supported=false`. Text-only clients receive Jasm in a `jasm` fence and
Android disassembly in a read-only `text` fence. Only the JVM document may be
passed to `patch_instructions`. Passing `assembly_sha256` back as
`if_sha256` returns `unchanged=true` and method/hash metadata without the
assembly body in either scope.

`patch_instructions(database, owner, name, descriptor, assembly,
expected_assembly_sha256, dry_run=false)` parses, analyzes, and assembles that
complete method against its current class. The expected hash must still match
a fresh disassembly, so an edit cannot silently overwrite a newer method.
`dry_run=true` validates without changing the workspace. A successful apply is
one atomic in-memory class replacement and cannot change the class name,
classfile version, class access, member declaration set, or target method
access, signature, annotations, default, or declared exceptions. The source is
capped at 1,000,000 characters, output at 32 MiB, and diagnostics at 100
entries. This path uses Recaf's assembler directly and does not require a
decompiler or `javac`; saving remains explicit.

`get_class_bytecode(database, class_name, scope="jvm", dex_name?, offset=0,
limit=32768, if_sha256?)` returns standard Base64 pages. JVM scope returns the
exact classfile bytes, classfile version, and derived Java release. Android scope
reconstructs a valid standalone DEX containing the selected class and reports
`format="dex"`, its DEX version,
`representation="reconstructed_single_class_dex"`, and
`original_bytes=false`; DEX classes share file-wide tables, so this is not
misrepresented as an original APK byte slice. Both forms include full size,
returned byte count, nullable `next_offset`, and complete-payload SHA-256.
Pages are at most 1 MiB and the complete payload is limited to 16 MiB. Passing
the payload hash back as `if_sha256` returns `unchanged=true` with
identity/format/size/hash metadata but no Base64 or pagination fields.

`get_recaf_script_help(database, topic="overview")` returns bundled Markdown
matched to the Recaf build used by the Worker. Topics are `overview`,
`workspace`, `asm`, `services`, and `deobfuscation`. They explain both Recaf
source forms, the shorthand `workspace`/`log` variables, primary bundle
replacement, ASM inspection and rewriting, full-class `@Inject` service use,
bounded output, and a staged general deobfuscation workflow.

`run_recaf_script(database, script, compile_only=true,
acknowledge_full_worker_access=false, offset=0, limit=100)` sends one Java
source file to Recaf's own asynchronous `ScriptEngine`. The safe default only
compiles and returns up to 100 bounded diagnostics. To execute, callers must
set both `compile_only=false` and `acknowledge_full_worker_access=true`.
Captured `System.out` is capped at 256 KiB; a final single-line
`MCP_RESULT <json>` value is also returned as `script_result`. Primary ordinary
JVM bundle additions, updates, and removals are reported as a stable page of at
most 200 changed class names, and the original current workspace is restored
if the script replaces it.

Scripts have the full Worker's filesystem, network, process, JVM, and Recaf
service permissions. Java provides no useful in-process sandbox for this use
case. Execution is not atomic or rolled back: failures and cancellations can
leave partial in-memory workspace or external side effects. The Bridge still
isolates the Worker and data directory, serializes all operations, requires an
explicit save, and destroys the Worker if a cancelled script does not drain.
Use compile-only preflight and read `get_recaf_script_help` before execution.

## Test

```bash
uv run pytest -q
```

The suite uses the official in-memory reference client and a real stdio
subprocess to verify initialization, stable repeated `tools/list`, structured
`tools/call`, stdout purity, and shutdown on EOF.

The installed-Recaf integration tests are opt-in. Set
`RECAF_MCP_TEST_RECAF_JAR`, `RECAF_MCP_TEST_PLUGIN_JAR`, `RECAF_MCP_TEST_INPUT`,
`RECAF_MCP_TEST_PLUGIN_BUILD`, and `RECAF_MCP_TEST_SCHEMA_FINGERPRINT` to run it
with the regular pytest command. They verify both the raw launcher and the full
MCP `tools/list` → `recaf_open` → routed analysis/mutation → atomic save/default
close → reopen flow, including progress notifications, source/bytecode page
reconstruction, a descriptor-aware method rename with updated call sites,
occupied-output failure/retry, and real one-second-TTL automatic
autosave/termination behavior. They also cover failed-save recovery across
Bridge instances and real SIGTERM save/cleanup with stdin still open.

Set `RECAF_MCP_TEST_REFERENCE_INPUT` as well to enable the optional large-JAR
generic script test. It performs a read-only ASM inventory through
`run_recaf_script`; the Worker implementation contains no knowledge of the
reference JAR's obfuscation pattern.

Set `RECAF_MCP_TEST_ANDROID_INPUT` to the reference APK to enable the optional
Android analysis and mapping suite. It verifies generic file listing/manifest reading,
scope schemas, multi-DEX
identity, Android-to-JVM source decompilation, reconstructed single-class DEX
bytes, native DEX instruction search/disassembly, a full-APK call-graph index,
Android source/string/member searches, in-memory method/class renames, and all
seven mapping-text exports without saving the APK.
