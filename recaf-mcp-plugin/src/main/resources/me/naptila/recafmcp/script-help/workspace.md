# Workspace and bundles

Shorthand scripts receive the current `workspace`. The primary JAR's ordinary
JVM classes are in `workspace.getPrimaryResource().getJvmClassBundle()`.
Class names use JVM internal form such as `com/example/Foo`.

Read-only scan:

```java
if (workspace == null) throw new IllegalStateException("No workspace");
JvmClassBundle bundle = workspace.getPrimaryResource().getJvmClassBundle();
long methods = bundle.values().stream()
        .mapToLong(info -> info.getMethods().size())
        .sum();
System.out.println("MCP_RESULT {\"classes\":" + bundle.size()
        + ",\"methods\":" + methods + "}");
```

For deterministic and mutation-safe traversal, copy and sort first:

```java
JvmClassBundle bundle = workspace.getPrimaryResource().getJvmClassBundle();
List<JvmClassInfo> classes = new ArrayList<>(bundle.valuesAsCopy());
classes.sort(Comparator.comparing(JvmClassInfo::getName));
for (JvmClassInfo info : classes) {
    // Inspect info.getBytecode(), fields, methods, annotations, and properties.
}
```

Replace a class by putting a newly built `JvmClassInfo` into the same bundle:

```java
JvmClassInfo original = bundle.get("com/example/Foo");
if (original == null) throw new IllegalStateException("Target not found");
byte[] rewritten = /* ASM output */;
JvmClassInfo replacement = new JvmClassInfoBuilder(rewritten).build();
if (!replacement.getName().equals(original.getName()))
    throw new IllegalStateException("Class identity changed");
bundle.put(replacement);
```

`getJvmClassBundle()` excludes multi-release versioned bundles, Android
bundles, embedded resources, and libraries. Use the corresponding
`WorkspaceResource` APIs only when the task deliberately includes them.
Mutations remain in memory until an explicit save.

## Android APK and DEX bundles

For Android, iterate `workspace.getPrimaryResource().getAndroidClassBundles()`.
Each map key is the exact DEX entry name. Classes expose native DEX trees through
`AndroidClassInfo.getBackingDefinition()`, and each method's `getCode()` exposes
its Dalvik instructions. ASM and converted JVM source views are for JVM analysis;
changing those views does not replace Android method bodies.

`ClassDefinition.getMethods()` returns a
`Map<MemberIdentifier, MethodMember>`, not a list of methods. Iterate
`info.getBackingDefinition().getMethods().values()`, or look up an exact overload
with `getMethod(name, descriptor)`. Likewise, `getFields()` returns a
`Map<MemberIdentifier, FieldMember>`; use `.values()` or `getField(name, descriptor)`.
Abstract and native methods have no code.

```java
long classes = 0;
long methods = 0;
for (var entry : workspace.getPrimaryResource().getAndroidClassBundles().entrySet()) {
    for (var info : entry.getValue().valuesAsCopy()) {
        classes++;
        methods += info.getBackingDefinition().getMethods().size();
    }
}
System.out.println("MCP_RESULT {\"classes\":" + classes + ",\"methods\":" + methods + "}");
```

Native instruction classes are under `me.darknet.dex.tree.definitions.instructions`;
import the specific classes explicitly. Preserve register use, instruction widths,
branch and exception labels, and debug-local ranges when replacing instructions.
Rebuild the `AndroidClassInfo` and put it back into the same Android bundle after
the edits, so Recaf's caches, listeners, and script change tracking see them.

### Replace one integer constant

This complete shorthand changes exactly one `const` value from 17 to 18 in
`sample/Fidelity.mix(I)I`. Set the DEX name, owner, method, and descriptor to match
your target. Read `get_method_assembly(scope="android")` first. These two values
fit the original `const/16` or `const` encoding; other values must fit the selected
opcode's literal range and register limits.

```java
import me.darknet.dex.tree.definitions.instructions.ConstInstruction;
import me.darknet.dex.tree.definitions.code.Code;
import software.coley.recaf.info.builder.AndroidClassInfoBuilder;

String dexName = "classes.dex";
var bundle = workspace.getPrimaryResource().getAndroidClassBundles().get(dexName);
if (bundle == null) throw new IllegalStateException("DEX not found: " + dexName);
var info = bundle.get("sample/Fidelity");
if (info == null) throw new IllegalStateException("Target not found");
var method = info.getBackingDefinition().getMethod("mix", "(I)I");
if (method == null || method.getCode() == null)
    throw new IllegalStateException("Method with code not found");
var code = method.getCode();
var instructions = new java.util.ArrayList<>(code.getInstructions());
int index = -1;
ConstInstruction original = null;
for (int i = 0; i < instructions.size(); i++) {
    if (instructions.get(i) instanceof ConstInstruction constant && constant.value() == 17) {
        if (index != -1) throw new IllegalStateException("Ambiguous constant");
        index = i;
        original = constant;
    }
}
if (original == null) throw new IllegalStateException("Expected constant not found");
var replacement = new ConstInstruction(original.opcode(), original.register(), 18);
if (replacement.unitSize() != original.unitSize())
    throw new IllegalStateException("Instruction width changed");
instructions.set(index, replacement);
var updated = new Code(code.getIn(), code.getOut(), code.getRegisters());
updated.addInstructions(instructions);
code.tryCatch().forEach(updated::addTryCatch);
if (code.getDebugInfo() != null) updated.setDebugInfo(code.getDebugInfo());
method.setCode(updated);
bundle.put(new AndroidClassInfoBuilder(info.getBackingDefinition()).build());
System.out.println("MCP_RESULT {\"replacements\":1}");
```

`Code.getInstructions()` returns a read-only view in the pinned DEX version.
Copy it, edit the copy, and install a new `Code` as shown, retaining register counts,
try/catch regions and debug metadata. Old instruction offsets are invalid after
an edit and are recomputed when encoding. The shown instruction objects are immutable. The
three-int `ConstInstruction(opcode, register, value)` constructor above preserves
the existing opcode and destination register. For a binary literal instruction,
use `new BinaryLiteralInstruction(old.opcode(), old.dest(), old.src(), newValue)`
with an `int newValue` to select the raw-opcode constructor. Its overload ending
in `short` interprets the first argument as an operation kind instead.
`MoveExceptionInstruction(int register)` exposes its destination with `register()`;
it has no `to()` accessor. Import the native DEX `Types` and `Label` explicitly
when using them, since shorthand imports can also include JVM types with these names.

### Invoke-range arguments

In this pinned runtime, the factory is
`InvokeInstruction.range(int rangeOpcode, ReferenceType owner, String name,
MethodType type, int registerCount, int firstRegister)`. The last two arguments
are **register word count, then first physical register**. They are not first and
last registers, or Java argument count. `long` and `double` each use two words;
an instance invocation also includes its receiver. The range is contiguous and
ends at `firstRegister + registerCount - 1`.

This complete read-only shorthand constructs `invoke-static/range` for
`sample/Helpers.consume(IJLjava/lang/String;)V` using v2 through v5: v2 holds the
int, v3/v4 the long, and v5 the reference. It does not call the target method or
insert the instruction into a method body.

```java
import me.darknet.dex.tree.definitions.instructions.InvokeInstruction;
import me.darknet.dex.tree.type.Types;

var call = InvokeInstruction.range(
        0x77, Types.instanceTypeFromInternalName("sample/Helpers"), "consume",
        Types.methodTypeFromDescriptor("(IJLjava/lang/String;)V"), 4, 2);
if (!call.isRange() || call.first() != 2 || call.last() != 5 || call.arguments() != null)
    throw new IllegalStateException("Unexpected invoke-range layout");
System.out.println("MCP_RESULT {\"first\":" + call.first() + ",\"last\":" + call.last() + "}");
```

The factory takes the encoded range opcode (`0x77` for invoke-static/range),
while `call.opcode()` returns its base opcode (`0x71` for invoke-static).
Do not pass that base opcode directly to `range`. For range calls,
`arguments()` is null; use `first()` and `last()`. Non-range constructors instead
accept explicit register words as `int...`. Polymorphic calls have separate
`polymorphic` and `polymorphicRange` factories with two method types.
Before inserting an invoke, ensure the method's register allocation and
`Code.getOut()` can hold its arguments, the target descriptor and dispatch kind
match, and move-result, branch, exception, and debug labels remain valid.

### Change reports and saving

`run_recaf_script` observes additions, updates, and removals in the primary ordinary
JVM bundle and the primary Android bundles present when the script starts. Reported
class-name lists are sorted and deduplicated across bundles. Direct tree edits
without `bundle.put` are not observed. Changes to supporting, embedded, versioned
JVM, or newly attached bundles are outside this event observation scope.

`workspace_changed` means the current workspace object was replaced or closed;
`workspace_restored` reports restoration of that original object. They do not
measure edits inside a workspace. Use `added_classes`, `modified_classes`,
`removed_classes`, and their counts to inspect class changes. A successful edit
normally leaves `workspace_changed=false`.

Scripts can batch edits across DEX entries in one call. Compile first, run with
explicit acknowledgement, inspect `script_result` and observed changes, then use
`recaf_save` to export APK/DEX. APK output is unsigned and needs signing before
installation. Scripts do not provide transactional rollback; prepare and validate
the complete edit plan before writing.
