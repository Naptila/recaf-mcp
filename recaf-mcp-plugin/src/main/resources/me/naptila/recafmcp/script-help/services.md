# Full-class scripts and Recaf services

Shorthand scripts cannot inject services. Use a full class with `@Dependent`,
an `@Inject` constructor, and a public non-static `void run()`:

```java
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

@Dependent
public class ServiceScript {
    private final WorkspaceManager workspaceManager;
    private final DecompilerManager decompilerManager;

    @Inject
    public ServiceScript(WorkspaceManager workspaceManager,
                         DecompilerManager decompilerManager) {
        this.workspaceManager = workspaceManager;
        this.decompilerManager = decompilerManager;
    }

    public void run() {
        Workspace workspace = workspaceManager.getCurrent();
        String name = decompilerManager.getTargetJvmDecompiler().getName();
        System.out.println("MCP_RESULT {\"decompiler\":\"" + name + "\"}");
    }
}
```

Recaf's [service documentation][services] describes available services such as
`WorkspaceManager`, `ScriptEngine`, search, mapping, decompilation,
compilation, assembler, and transformation services. It is a rolling upstream
reference, so the current Worker's compile result remains authoritative for
version compatibility. Inject interfaces through the constructor instead of
creating Recaf service implementations manually.

Avoid changing `WorkspaceManager` lifecycle state from an MCP script. The
Worker owns one workspace and the Bridge owns saving and shutdown. Prefer the
provided `workspace` in shorthand or `workspaceManager.getCurrent()` in a full
class and mutate its primary resource in place.

[services]: https://recaf.coley.software/dev/services/index.html
