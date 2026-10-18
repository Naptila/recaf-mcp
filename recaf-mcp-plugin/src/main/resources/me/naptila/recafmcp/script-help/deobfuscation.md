# General deobfuscation workflow

Treat scripts as task-specific, reviewable transformations rather than a new
hard-coded Worker tool for each obfuscator.

1. **Inventory:** count classes, methods, opcodes, constants, descriptors, and
   candidate call sites. Print a compact `MCP_RESULT` summary and make no
   changes.
2. **Model the invariant:** identify the exact bytecode/data-flow shape and
   reject candidates that do not satisfy every assumption.
3. **Evaluate safely:** implement only the small deterministic operation
   required by the pattern. Bound array indexes, output sizes, recursion, and
   iteration. Do not execute arbitrary target bytecode or class initializers.
4. **Rewrite narrowly:** replace exact instruction ranges while preserving
   stack effects, or remove verified proxy members/classes only after all
   references are gone.
5. **Verify:** report candidates, accepted/rejected counts, changed classes,
   and remaining matches. Re-run normal MCP searches/decompilation before
   saving.

For example, scattered string decoder proxies may share one descriptor while
call sites provide a static array and integer constants. A generic script can
first collect decoder method identities by descriptor plus opcode shape, then
inspect `MethodInsnNode` call sites and their preceding producers. Only when
the array owner/name/descriptor, constants, bounds, decoder identity, and
expected stack inputs are all proven should it replace the call with an
`LdcInsnNode` string. The descriptor or XOR opcode alone is not sufficient.

Keep discovery and rewrite as separate runs. Use `compile_only=true` for both
sources before executing. A failed script is not rolled back automatically,
so build replacements before `bundle.put`, avoid writes during discovery, and
save to a new output JAR/APK/DEX before overwriting any existing artifact.

For APK/DEX, use the native Android bundle/tree APIs described in the `workspace`
topic rather than ASM. One script can traverse every primary DEX and replace many
reviewed methods, then reinsert each changed class in its original bundle. Keep
Dalvik register types, branch labels, exception regions, and debug labels valid;
preserving an instruction's width alone does not establish semantic correctness.
