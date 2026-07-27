# Recaf script overview

`run_recaf_script` compiles one Java source file with Recaf's `ScriptEngine`.
Use `compile_only=true` first. Compilation does not run the script. When the
source is ready, call it again with `compile_only=false` and
`acknowledge_full_worker_access=true`.

Recaf accepts two forms:

1. **Shorthand** is a Java method body. Recaf adds common Java, Recaf, ASM, and
   Jakarta imports and provides `Workspace workspace` and `Logger log`. It is
   best for workspace and ASM scans or rewrites. It cannot inject services.
2. **Full class** is an ordinary Java class with a non-static `void run()`.
   Add `@Dependent` and an `@Inject` constructor when Recaf services are needed.

Minimal shorthand:

```java
if (workspace == null) throw new IllegalStateException("No workspace");
int classes = workspace.getPrimaryResource().getJvmClassBundle().size();
System.out.println("MCP_RESULT {\"class_count\":" + classes + "}");
```

Minimal full class:

```java
import jakarta.enterprise.context.Dependent;

@Dependent
public class MyScript {
    public void run() {
        System.out.println("MCP_RESULT {\"ok\":true}");
    }
}
```

Print concise machine-readable output with `System.out.println`. If the last
captured line starts with `MCP_RESULT ` and the remainder is valid JSON, the
tool also returns it as `script_result`. `log.info(...)` goes to Worker logs
and is not a reliable MCP result channel.

Scripts have the same filesystem, network, process, JVM, and Recaf-service
access as the Worker. Java scripts are not sandboxed. Execution is not atomic:
an exception or cancellation can leave partial in-memory workspace changes.
Use narrow targets, validate assumptions before writes, and save explicitly
with `recaf_save` or `recaf_close` only after checking the result.

Do not close or replace the current workspace, call `System.exit`, spawn
long-lived threads, replace standard streams, or retain listeners. The tool
tries to restore the original current workspace, but it cannot roll back
arbitrary side effects.
