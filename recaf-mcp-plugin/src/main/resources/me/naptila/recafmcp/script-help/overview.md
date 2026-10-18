# Recaf script overview

`run_recaf_script` compiles one Java source file with Recaf's `ScriptEngine`.
Use `compile_only=true` first. Compilation does not run the script. When the
source is ready, call it again with `compile_only=false` and
`acknowledge_full_worker_access=true`.

Recaf accepts two forms:

1. **Shorthand** is a Java method body. Recaf adds common Java, Recaf, ASM, and
   Jakarta imports and provides `Workspace workspace` and `Logger log`. It is
   best for workspace scans and JVM/ASM or Android/DEX rewrites. It cannot inject services.
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

For APK/DEX batch editing, read the `workspace` topic's Android section. Iterate
the Android bundles and edit native DEX trees; converted JVM views are not the
saved Android bytecode. Reinsert edited classes with `bundle.put` to report changes.
That topic includes a complete constant replacement and the precise invoke-range
argument order. Class edits appear in `modified_classes`; `workspace_changed`
only reports replacement or closure of the current workspace object.

On runtime failure, `runtime_error_type` and `runtime_error_message` describe the
outer exception. `runtime_error_chain` lists up to eight causes from outermost
to innermost, each with `type`, `message`, up to 12 `stack_trace` frames, and
`stack_trace_truncated`. Messages are capped at 1,000 characters and frames at
500 characters. `runtime_root_cause_type` and `runtime_root_cause_message` expose
the deepest cause when the chain is complete. If it exceeds the limit or cycles,
`runtime_error_chain_truncated=true` and the root-cause fields are omitted.
Inspect these diagnostics together with observed changes: a failed script can
still have edited classes.

Scripts have the same filesystem, network, process, JVM, and Recaf-service
access as the Worker. Java scripts are not sandboxed. Execution is not atomic:
an exception or cancellation can leave partial in-memory workspace changes.
Use narrow targets, validate assumptions before writes, and save explicitly
with `recaf_save` or `recaf_close` only after checking the result.

Do not close or replace the current workspace, call `System.exit`, spawn
long-lived threads, replace standard streams, or retain listeners. The tool
tries to restore the original current workspace, but it cannot roll back
arbitrary side effects.
