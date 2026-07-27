# ASM inspection and rewriting

Shorthand scripts automatically import `org.objectweb.asm.*`,
`org.objectweb.asm.tree.*`, common Recaf information/builders, and Java
collections. A general primary-JVM scan looks like this:

```java
JvmClassBundle bundle = workspace.getPrimaryResource().getJvmClassBundle();
List<JvmClassInfo> classes = new ArrayList<>(bundle.valuesAsCopy());
classes.sort(Comparator.comparing(JvmClassInfo::getName));
long matches = 0;
for (JvmClassInfo info : classes) {
    ClassNode node = new ClassNode();
    new ClassReader(info.getBytecode()).accept(node, 0);
    for (MethodNode method : node.methods) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() == Opcodes.IXOR) matches++;
        }
    }
}
System.out.println("MCP_RESULT {\"matches\":" + matches + "}");
```

Rewrite from a snapshot and call `bundle.put(...)` only after validating each
candidate:

```java
int changed = 0;
for (JvmClassInfo info : new ArrayList<>(bundle.valuesAsCopy())) {
    ClassNode node = new ClassNode();
    new ClassReader(info.getBytecode()).accept(node, 0);
    boolean classChanged = false;
    for (MethodNode method : node.methods) {
        // Match an exact opcode/owner/name/descriptor/constant pattern.
        // Mutate method.instructions only after every precondition passes.
    }
    if (!classChanged) continue;
    ClassWriter writer = new ClassWriter(0);
    node.accept(writer);
    JvmClassInfo replacement = new JvmClassInfoBuilder(writer.toByteArray()).build();
    if (!replacement.getName().equals(info.getName()))
        throw new IllegalStateException("Unexpected class identity");
    bundle.put(replacement);
    changed++;
}
System.out.println("MCP_RESULT {\"changed_classes\":" + changed + "}");
```

Prefer `ClassWriter(0)` when the replacement preserves stack effects, local
layout, and existing frames. Use `COMPUTE_MAXS` when only maximums need
recomputation. `COMPUTE_FRAMES | COMPUTE_MAXS` may need hierarchy resolution
for application classes; blindly using it on incomplete or hostile classpaths
can fail or emit wrong common-superclass decisions.

Keep matching exact enough to avoid unrelated methods: verify class/method
identity, descriptors, opcode neighborhoods, operand types, and expected match
counts. Separate discovery from mutation when analyzing a new obfuscator.
