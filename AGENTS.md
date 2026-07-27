# Repository Guidelines

## Scope and Sources of Truth

This repository integrates with Recaf 4 but does not vendor Recaf source or
documentation.

- `recaf-mcp-plugin/build.gradle` pins `recafVersion`; it is the compile-time
  compatibility baseline.
- The generic JAR selected by `RECAF_JAR` is the runtime compatibility target.
- [Recaf's developer documentation](https://recaf.coley.software/dev/index.html)
  is a rolling upstream reference. Verify version-sensitive APIs against the
  pinned dependency, source for that revision, and the build.
- `tmp/` is ignored local scratch space. Never depend on its contents or commit
  copied Recaf documentation, sample targets, generated output, or model notes.

## Project Structure

- `recaf-mcp-plugin/` contains the Java Recaf plugin and Worker implementation.
  Production code is under `src/main/java/me/naptila/recafmcp/`, resources are
  under `src/main/resources/`, and matching JUnit tests are under
  `src/test/java/`.
- `recaf-mcp-bridge/` contains the Python MCP stdio bridge and Worker session
  supervisor. Production modules are under `src/`; pytest tests are under
  `tests/`.
- `README.md` and `README-ZH.md` are the public user documentation.

## Build and Test Commands

Run commands from the relevant module directory.

For the plugin:

- `./gradlew build` compiles the plugin, runs all JUnit tests, and creates the
  plugin JAR in `build/libs/`.
- `./gradlew test` runs the Java test suite.
- `./gradlew runRecafHeadless` launches the pinned headless development runtime.
- `./gradlew -q javaToolchains` lists detected JDK installations.

The Gradle wrapper is authoritative. The build uses a JDK 25 toolchain and emits
Java 22-compatible bytecode.

For the bridge:

- `uv sync --frozen` installs the locked Python environment.
- `uv run pytest -q` runs the Python test suite.
- `uv run recaf-mcp-bridge --stdio` starts the development stdio server.

Python 3.11 or newer is required. Do not hand-edit `uv.lock`; update it through
`uv`.

## Coding Conventions

Use four-space indentation in Java and Python and avoid unrelated formatting
changes.

- Java: use `UpperCamelCase` types, `lowerCamelCase` methods and fields, and
  lowercase packages. Prefer constructor injection for Recaf services and log
  through SLF4J.
- Python: follow PEP 8, use `snake_case`, and add type hints to new public APIs.
- Keep MCP schemas, Java Worker behavior, Python proxy behavior, and public
  documentation synchronized when a tool contract changes.

## Testing Requirements

- Add focused tests for every behavior change.
- Name Java tests `*Test` and Python tests `test_*.py`.
- Protocol or schema changes require coverage on both the Worker and Bridge
  sides.
- Keep tests cross-platform. Do not require symbolic-link privileges or Windows
  Developer Mode.
- Use temporary directories for generated artifacts and leave no Worker
  processes behind.
- Run the full Java and Python suites before merging. Use the opt-in real-Worker
  suite when changing launch, lifecycle, Recaf integration, mutation, or export
  behavior.

## Documentation

- Keep `README.md` and `README-ZH.md` aligned for user-visible changes.
- Link to upstream Recaf documentation instead of copying it into this
  repository.
- Treat upstream documentation as current guidance, not as proof that an API
  exists in the pinned Recaf revision.
- Document exact commands and observable behavior; avoid historical planning
  notes in public documentation.

## Commits and Pull Requests

Use concise imperative commit subjects and keep each commit focused. Pull
requests should explain motivation and user-visible behavior, list verification
commands, and link relevant issues. Include sample MCP requests and responses
for protocol changes.

Never commit build output, virtual environments, IDE metadata, logs, secrets,
sample binaries, APKs, JARs, recovery data, or anything under `tmp/`.
