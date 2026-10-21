# Recaf MCP Worker plugin

This module builds the Java plugin hosted by each dedicated headless Recaf
Worker JVM. The production artifact is one unshaded plugin JAR installed into
the user's existing Recaf 4 installation. No Recaf source checkout or separate
Worker runtime distribution is required.

See the [root README](../README.md) for installation, MCP client configuration,
and the normal Bridge-managed workflow.

## Build and test

JDK 25 and the checked-in Gradle 9.4.1 wrapper are used. Production classes
target Java 22.

```bash
./gradlew clean build
./gradlew test
./gradlew -q javaToolchains
```

`build` runs JUnit 5 and verifies the plugin service entry and dependency
boundaries. Its production output is:

```text
build/libs/recaf-mcp-plugin-1.1.1.jar
```

The build checks the service entry and verifies that the plugin does not shade
Recaf or Gson. JAR ordering and timestamps are reproducible. Copy the JAR into
Recaf's standard `plugins` directory; see the root README for platform paths.
Without the Bridge-owned `RECAF_MCP_TOKEN`, a normal Recaf launch leaves the
MCP server dormant.

Android fidelity tests use an independent dexlib2 encoder/parser (test dependency
only). The opt-in real-Worker runner invokes `generateAndroidFidelityFixture` to
generate compact numeric values, nullable parameter names, debug tables and
cross-DEX callers in its temporary directory. No binary fixtures are checked in.

Before reporting Android workspace readiness, the Worker repairs encoded values
in detached DEX items and reads modeled debug information. Android remapping
preserves unnamed debug parameters and propagates preparation failures. Export
validates field values and modeled debug tables before atomic installation.
Java source tools use detached JVM conversion with corrected array effects and
ASM-verified fallback output; the saved DEX keeps its own instruction bodies.

## Development launch

The production Bridge launches the installed Recaf generic JAR directly and
never runs Gradle. For an isolated development Worker:

```bash
RECAF_MCP_TOKEN='<32-or-more-character-random-token>' \
  ./gradlew \
  -Drecaf.dataDir=/absolute/path/to/private-recaf-data \
  -Drecaf.input=/absolute/path/to/input.jar \
  runRecafHeadless
```

Omit `recaf.input` for a schema-only Worker. `recaf.dataDir` must be unique so
the test cannot load the user's Recaf plugins or configuration. The task keeps
plugin classes off Recaf's application classpath and loads the plugin only via
Recaf's `-r` option.

The equivalent production command shape is:

```bash
RECAF_MCP_TOKEN='<random-token>' \
  java -Xmx1024m -Drecaf.mcp.port=<allocated-port> \
  -jar /absolute/path/to/recaf.jar \
  -d /absolute/private-data \
  -r /absolute/private-plugins \
  --headless --idle \
  -i /absolute/input.jar
```

## Private Worker protocol

The Worker binds only `127.0.0.1`, normally on a Bridge-assigned dynamic port,
and logs `RECAF_MCP_READY host=127.0.0.1 port=<port>`. Requests use HTTP POST to
the exact `/jsonrpc` path with JSON UTF-8 and:

```text
Authorization: Bearer <RECAF_MCP_TOKEN>
Content-Type: application/json
```

Tokens must contain 32–512 valid bearer-token ASCII characters. Bodies are
limited to 1 MiB for declared and streamed requests. The Worker rejects query
strings, other methods, unsupported content encodings, invalid UTF-8, malformed
JSON-RPC envelopes, notifications, and batches. Responses use
`Cache-Control: no-store`; tokens are never placed in results or normal logs.

Authenticated internal methods are:

- `health`: process/build/schema/workspace identity, readiness, archive-export
  capability, busy/closing state, and bounded last error;
- `cancel_operation`: interrupt one exact Bridge-owned operation ID;
- `save_workspace`: original-format JAR/APK/DEX export, format validation and
  atomic installation; APK output is unsigned;
- `shutdown`: response-before-exit graceful termination;
- `probe/headless_services`: retained headless regression probe.

They are reserved names and never appear in `tools/list`.

## Execution model

A fair `WorkerOperationCoordinator` serializes all public tools, saves, and
retained analysis probes for the single mutable workspace. HTTP health and
cancellation remain responsive on separate request threads.

Every public call has a private operation ID and a cost-based deadline:

| Timeout | Tools |
| --- | --- |
| 15 seconds | `workspace_info`, `list_classes`, `list_files`, `get_class_bytecode`, `get_recaf_script_help` |
| 60 seconds | `read_file`, `decompile_class`, `search_strings`, `search_instructions`, `search_members`, `search_structure`, `rename_symbol`, `export_mappings`, `get_method_assembly` |
| 120 seconds | `search_code`, `call_graph`, `patch_instructions` |
| 300 seconds | `apply_mappings`, `transform_workspace`, `patch_class`, `run_recaf_script` |
| 1,800 seconds | `export_sources` |

Timeout and cancellation interrupt cooperative loops before mutation is
applied. If a Recaf dependency is slow to honor interruption, health remains
`busy:true` until its execution thread really exits, preventing overlapping
workspace access. The Bridge force-terminates a Worker that cannot drain safely.

## Public tools and bounds

The catalog order and base schemas are fixed for a plugin build. The plugin
never defines the Bridge-owned `database` property.

- `workspace_info`: bounded primary/supporting resource summary.
- `list_classes`: sorted primary JVM and/or Android names, 20-entry default
  pages capped at 500. Android entries retain their owning DEX name.
- `list_files`: sorted primary-resource non-class archive paths with file kind,
  byte size, extension/directory, Recaf text/charset metadata, and whether a
  textual representation is available. Android binary XML is identified as
  `android_binary_xml`. Optional case-sensitive path filtering runs before
  20-entry default pages capped at 500 entries.
- `read_file`: exact paths returned by `list_files`, with 32 KiB default and
  512 KiB maximum pages. `format=auto` returns Recaf-recognized text and
  resource-aware decoded Android binary XML as text; all other files use
  Base64. Explicit `text` also decodes AXML, while explicit `base64` always
  returns the original archive bytes. Results identify decoded text and its
  source, report the offset unit, complete raw byte size, and raw SHA-256. It
  never resolves a host filesystem path. Passing that raw hash as optional
  `if_sha256` returns unchanged metadata without decoding or returning a body.
- `decompile_class`: exact primary JVM or Android class and optional
  decompiler; Android classes use Recaf's Android-to-JVM conversion. Source
  pages default to 32 Ki characters and are capped at 500,000 characters. Its
  `decompiler` schema enum is generated from Recaf's installed JVM
  decompilers and identifies the configured default. Schema-aware clients
  reject values outside this enum before execution; result/error metadata
  retains selected and available versions for direct protocol callers. Every
  successful page includes `source_sha256` for the complete UTF-8 source;
  matching optional `if_sha256` omits the source and page fields.
- `export_sources`: all JVM and/or Android classes selected by an
  optional literal/regex internal-name filter, streamed into an atomic source
  ZIP at a required absolute destination. It uses the same runtime decompiler
  enum as `decompile_class`, keeps at most eight asynchronous results in
  flight, and temporarily disables/restores Recaf's per-class decompilation
  cache so an all-class export does not retain every source string. Entries
  live under separate `jvm/` and `android/<dex>/` roots; unsafe path bytes are
  percent-escaped. `_recaf-mcp/manifest.json` records every selected class and
  per-class failures. The response reports partial/completed counts, bounded
  failure details, archive size/entries, and SHA-256.
- `search_strings`, `search_instructions`, and `search_code`: literal/regex
  search, 500-character query, 10-item default/200-item maximum pages, 20,000
  collected-hit cap, and bounded values/lines. `search_instructions` uses Recaf assembler-form
  instructions without decompilation and is the preferred whole-JAR search.
  `search_code` supports an optional canonical installed decompiler name,
  uses Recaf's cache/thread pool with eight bounded in-flight requests, and
  accepts a class/package filter before decompilation. Scopes over 200 classes
  return an actionable preflight error unless `allow_large_scan=true` is set
  explicitly; no partial source-search result is returned. It publishes the
  same runtime decompiler enum as `decompile_class`. `search_strings` and
  `search_code`, and `search_instructions` accept JVM, Android, or combined
  scope. Android instruction search walks Recaf's native DEX tree and reports
  instruction indexes and DEX offsets without decompilation.
- `search_members`: Recaf `DeclarationQuery` and `ReferenceQuery` results for
  fields, methods, or both. Like every search tool, owner/name/descriptor use
  case-sensitive literal substring matching by default or partial Java regex
  matching with `regex=true`; omitted selectors match any value. A
  class/package filter restricts the selected primary JVM and/or Android
  classes scanned. The tool
  reapplies all selectors and kind/result constraints to final identities
  before sorting and pagination.
- `rename_symbol`: exact JVM or Android class/member rename through Recaf's
  search and mapping services. Android requests accept DEX identity, select
  only declarations/referrers for bounded memory, and persist through APK/DEX save.
- `apply_mappings`: inline mapping text parsed by Recaf in any installed
  text format. Its runtime enum currently exposes Enigma, Jadx (Legacy),
  Proguard, Simple, SRG, Tiny-V1, and Tiny-V2. It supports dry-run, optional
  globally unique-key lookup, collision checks, bounded changed-class pages,
  explicit scope/save semantics, and JVM/Android/DEX change identities.
  Android mappings persist when the Bridge saves the APK/DEX workspace.
- `export_mappings`: the current session's `AggregateMappingManager` history
  exported in the same runtime-discovered seven formats. Original-to-current
  UTF-8 text uses 32 Ki-character default/512 Ki-character maximum pages, a
  16 Mi-character complete-text guard, entry counts, byte/character sizes, and
  a whole-document SHA-256. Matching optional `if_sha256` returns those
  document/count metadata without the mapping page.
  It refuses empty history, unsupported text writers, and
  descriptor-requiring formats when aggregate field descriptors are missing.
  It returns JVM or DEX mapping text to the caller, never resolves a host
  output path, and does not export a rewritten archive.
- `call_graph`: callers/callees to depth five, 10,000 vertices and 20,000
  edges. Unresolved JDK/third-party methods remain visible as boundary edges
  but are not expanded by default; `stop_at_external=false` opts into the
  broader graph. Results report how many unique external vertices were
  stopped. JVM scope uses Recaf's call graph; Android scope indexes symbolic
  DEX invoke instructions and reverse callers across the selected DEX classes.
- `transform_workspace`: reviewed transformer aliases only, primary JVM scope,
  typed per-run `options`, up to eight ordered native Recaf `phases` with unique
  IDs and one to three passes each, and per-phase statuses/counts. Extra aliases
  cover long annotations/exceptions, duplicate catches, variable tables,
  constant/predicate/variable folding, and static/call/invokedynamic inlining.
  The existing four conservative metadata defaults are unchanged. All phases
  run on detached JVM class bundles (including multi-release bundles), capped
  at 256 MiB of snapshot class bytes including supporting resources. Nested
  archives and libraries resolve types but are excluded from transformation.
  `class_prefix` constrains committed changes, the live class bytes are
  rechecked before commit, and failed plans/dry runs leave them untouched.
  Exact aliases and option bounds are in the Bridge README.
- `search_structure`: native Recaf `ClassQuery` matching across JVM/Android
  primary classes. The required `query` combines class names/access masks and
  up to eight field and eight method requirements; method descriptors are exact,
  names are exact unless their nested `regex=true`, and opcode bags allow up to
  32 numeric native opcodes with multiplicity. Empty lists require absence.
  Access masks are nonnegative 16-bit integers and cannot overlap. Default
  `scope=jvm`; `android` accepts exact `dex_name`, and `all` scans both backends.
  Stable class/domain/DEX ordering, 10 default/200 maximum page size, and a
  20,000-hit collection cap keep responses bounded; `collection_truncated`
  identifies a partial count. Hits preserve `class_scope` and `dex_name`.
- `patch_class`: complete source compiled against the workspace; 750,000 source
  characters, 100 related outputs, 32 MiB output, and bounded diagnostics. It
  preserves the input classfile target by default. Legacy inputs below the
  current javac minimum require explicit `target_version_policy` =
  `minimum_supported`; responses report input and output Java/classfile
  versions and reject compiler output at an unexpected target.
- `get_method_assembly`: complete editable Recaf JVM or Dalvik Jasm text for
  one exact primary method. Android results use
  `assembly_format=recaf_jasm_dalvik_method`; select `scope=android` and use
  the returned `dex_name` when patching. Output is capped at 1,000,000 characters.
  Matching optional
  `if_sha256` suppresses the complete assembly while retaining its hash and
  method metadata in either scope.
- `patch_instructions`: assembles a complete method document, not a fragment.
  The required prior assembly hash prevents stale edits. `dry_run` performs the
  same parse, control-flow analysis, output validation, and bounded diagnostic
  reporting without mutation. The assembled class must keep its name,
  classfile version, class access, field/method declaration topology, and
  target method access/signature/annotations/throws metadata; valid changes are
  applied with one bundle replacement and limited to 32 MiB.
  Dalvik assembly runs on a detached DEX tree and permits changes only inside
  the target method body; declarations and every other member must remain
  unchanged. Duplicate Android owners require an exact `dex_name`. Both paths
  recheck the owner class hash before replacement; failures and dry runs leave
  the workspace untouched. APK/DEX edits can be persisted with `recaf_save`.
- `get_class_bytecode`: standard Base64, 32 KiB default/1 MiB maximum pages,
  16 MiB class guard, full-class size and SHA-256 metadata. JVM scope returns
  the original classfile. Android scope reconstructs a valid standalone DEX
  containing the selected class and explicitly reports `original_bytes=false`.
  Matching optional `if_sha256` omits Base64 and page fields while preserving
  format, identity, complete size, and hash metadata.
- `get_recaf_script_help`: bundled, version-matched Markdown guidance for
  shorthand/full-class scripts, primary workspace bundles, ASM, service
  injection, and general deobfuscation workflows. It does not require source
  documentation or network access at runtime.
- `run_recaf_script`: one Recaf Java shorthand body or full class, capped at
  500,000 characters. It defaults to non-executing compile-only preflight;
  execution requires explicit full-Worker-access acknowledgement. Compiler
  diagnostics are capped at 100, stdout at 256 KiB, changed-class pages at 200,
  and a final `MCP_RESULT <json>` line is exposed structurally. Primary ordinary
  JVM and Android bundle events are observed, but arbitrary script side effects are not
  sandboxed, atomic, or rolled back. A script-replaced current workspace is
  restored when possible, cancellation uses Recaf's cooperative script stop,
  and saving remains explicit.

The `workspace` script guide includes a complete DEX constant replacement,
`ClassDefinition.getMethods().values()` traversal, and the exact invoke-range
factory argument order. `workspace_changed` means the current workspace object
was replaced or closed; class edits are reported by the separate change lists.
Runtime errors retain their outer type/message and add `runtime_error_chain`
(up to eight causes, 12 stack frames per cause, 1,000 characters per message,
500 per frame). Each cause reports whether its stack frames were truncated.
`runtime_root_cause_type` and `runtime_root_cause_message` are present only when
the chain is complete; a depth limit or cycle sets
`runtime_error_chain_truncated=true` instead.

All class names use internal form. The Android-aware analysis tools expose
`scope` (`jvm`, `android`, and `all` where a scan is meaningful) and optional
`dex_name`; JVM is always the compatibility default. Every Android hit reports
its DEX identity, so multi-DEX names remain unambiguous. Member operations use
JVM-style descriptors for both class domains. Android source patching and JVM
transformations remain unsupported. Symbol mapping mutates DEX trees in memory;
`save_workspace` persists all bundles in the original APK/DEX format. Source and
mapping exports are independent. The Bridge saves JAR/APK/DEX by default on close.
APK outputs have old signatures removed; align and sign them before installation.

Invalid tool arguments preserve a bounded diagnostic in the JSON-RPC error.
Invalid regular expressions identify the selector, Java regex description,
and error index without returning a stack trace.

`run_recaf_script` receives a 300-second Worker timeout. The operation remains
busy while Recaf's asynchronous script future drains; if cooperative
cancellation cannot stop hostile script code, the Bridge's existing drain
deadline terminates that isolated Worker instead of allowing overlapping use.
Bulk source export instead receives 1,800 seconds because a deliberate
whole-archive decompilation can cover thousands of classes. Cancellation still
drains the bounded in-flight futures, closes/removes the temporary ZIP, and
never installs a partial destination.

## Readiness and save safety

An analysis Worker is ready only when authenticated health reports
`ready=true`, `workspace_open=true`, and `workspace_ready=true`; the canonical
expected/input paths, process PID, plugin build, and schema fingerprint must
also match the Bridge's launch record. Health reports
`workspace_archive_export_supported` and `workspace_output_format` (`jar`, `apk`,
`dex`, or null for unsupported inputs).

Saving exports only the primary resource to a temporary sibling, validates
a nonempty ZIP for JAR/APK or parses raw DEX, and atomically installs it. APK/DEX
destinations must use the corresponding extension. The result reports `format`
and `apk_signature_status` (`unsigned` for APK; `not_applicable` otherwise). Input aliases and unapproved existing destinations are
rejected. A failed save leaves the Worker ready and records a retryable
`last_error`; a later successful save clears it.
