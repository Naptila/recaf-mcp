# Changelog

## 1.1.0 — 2026-10-18

- Save APK and standalone DEX workspaces in their original format, including
  automatic saves and recovery. APK outputs are unsigned and require signing.
- Edit Android method bodies with Dalvik Jasm, exact DEX selection, previews,
  stale-assembly rejection, and preservation of supported debug information.
- Batch native DEX edits through `run_recaf_script`, with Android workspace
  guidance, runnable instruction examples, invoke-range API details, and observed
  change reports across primary DEX bundles. Clarify workspace lifecycle flags.
- Report bounded runtime exception chains, script stack frames, and root causes
  while retaining the outer exception fields.
- Preserve compact signed and floating-point DEX values, nested encoded values,
  and explicit inner-class flags. Validate exported declarations, static values,
  and supported debug tables before installing output files.
- Update class and member-owner references across primary DEX bundles during
  renaming and mapping. Mapping preparation failures leave the workspace unchanged.
- Correct primitive-array conversion for Android source tools and verify detached
  JVM views before decompilation. Saved DEX method bodies remain independent of
  these source views.
- Add `search_structure` for JVM and Android classes and members.
- Add explicit JVM transformation aliases, typed options, ordered phases, and
  previews on detached snapshots. Android-only transformation requests return
  an unsupported-operation error.
- Report runtime and compile-time Recaf identities separately, check required APIs,
  and reject runtime changes between discovery and Worker startup.
- Gate releases on real Worker integration tests against the pinned Recaf build;
  add scheduled compatibility checks against upstream Recaf.

Both components use version 1.1.0. The supported baseline remains
[Recaf `17216bd3`](https://github.com/Col-E/Recaf/commit/17216bd3b76bc6ef36c4198262b5318e2936fbb7).
For upgrade instructions, see [README.md](README.md#install) or
[README-ZH.md](README-ZH.md#安装).
