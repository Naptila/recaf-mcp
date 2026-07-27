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
