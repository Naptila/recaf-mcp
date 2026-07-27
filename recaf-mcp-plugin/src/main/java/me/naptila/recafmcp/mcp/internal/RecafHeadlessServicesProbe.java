package me.naptila.recafmcp.mcp.internal;

import com.google.gson.JsonObject;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.decompile.JvmDecompiler;
import software.coley.recaf.services.search.SearchService;
import software.coley.recaf.services.search.match.StringPredicateProvider;
import software.coley.recaf.services.search.query.StringQuery;
import software.coley.recaf.services.search.result.Results;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;

import java.awt.GraphicsEnvironment;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Runs workspace, decompiler, and search operations without UI services.
 */
public final class RecafHeadlessServicesProbe implements HeadlessServicesProbe {
    private final WorkspaceManager workspaceManager;
    private final DecompilerManager decompilerManager;
    private final SearchService searchService;
    private final StringPredicateProvider stringPredicateProvider;

    public RecafHeadlessServicesProbe(WorkspaceManager workspaceManager,
                                      DecompilerManager decompilerManager,
                                      SearchService searchService,
                                      StringPredicateProvider stringPredicateProvider) {
        this.workspaceManager = workspaceManager;
        this.decompilerManager = decompilerManager;
        this.searchService = searchService;
        this.stringPredicateProvider = stringPredicateProvider;
    }

    @Override
    public JsonObject run(String className, String searchText) {
        if (!workspaceManager.hasCurrentWorkspace()) {
            throw new IllegalStateException("workspace not open");
        }

        String callerThread = Thread.currentThread().getName();
        boolean callerIsJavaFx = callerThread.contains("JavaFX Application Thread");
        boolean javaFxThreadPresent = Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.getName().contains("JavaFX Application Thread"));

        Workspace workspace = workspaceManager.getCurrent();
        JvmClassBundle classBundle = workspace.getPrimaryResource().getJvmClassBundle();
        JvmClassInfo classInfo = classBundle.get(className);
        if (classInfo == null) {
            throw new IllegalArgumentException("probe class not found");
        }

        JvmDecompiler decompiler = decompilerManager.getTargetJvmDecompiler();
        Instant decompileStart = Instant.now();
        DecompileResult decompileResult = decompiler.decompile(workspace, classInfo);
        long decompileMillis = Duration.between(decompileStart, Instant.now()).toMillis();
        String decompiledText = decompileResult.getText();
        String simpleClassName = className.substring(className.lastIndexOf('/') + 1);
        boolean containsDeclaration = decompiledText != null
                && decompiledText.contains("class " + simpleClassName);

        Instant searchStart = Instant.now();
        Results searchResults = searchService.search(
                workspace,
                new StringQuery(stringPredicateProvider.newContainsPredicate(searchText))
        );
        long searchMillis = Duration.between(searchStart, Instant.now()).toMillis();

        JsonObject runtime = new JsonObject();
        runtime.addProperty("caller_thread", callerThread);
        runtime.addProperty("caller_is_javafx", callerIsJavaFx);
        runtime.addProperty("javafx_application_thread_present", javaFxThreadPresent);
        runtime.addProperty("awt_headless", GraphicsEnvironment.isHeadless());

        JsonObject workspaceResult = new JsonObject();
        workspaceResult.addProperty("class_count", classBundle.size());
        workspaceResult.addProperty("class_name", className);
        workspaceResult.addProperty("class_found", true);

        JsonObject decompile = new JsonObject();
        decompile.addProperty("name", decompiler.getName());
        decompile.addProperty("version", decompiler.getVersion());
        decompile.addProperty(
                "result_type",
                decompileResult.getType().name().toLowerCase(Locale.ROOT)
        );
        decompile.addProperty("text_length", decompiledText == null ? 0 : decompiledText.length());
        decompile.addProperty("contains_class_declaration", containsDeclaration);
        decompile.addProperty("elapsed_ms", decompileMillis);

        JsonObject search = new JsonObject();
        search.addProperty("text", searchText);
        search.addProperty("hit_count", searchResults.size());
        search.addProperty("elapsed_ms", searchMillis);

        boolean ok = classBundle.size() > 0
                && decompileResult.getType() == DecompileResult.ResultType.SUCCESS
                && containsDeclaration
                && !searchResults.isEmpty()
                && !callerIsJavaFx
                && !javaFxThreadPresent;

        JsonObject result = new JsonObject();
        result.addProperty("ok", ok);
        result.add("runtime", runtime);
        result.add("workspace", workspaceResult);
        result.add("decompile", decompile);
        result.add("search", search);
        return result;
    }
}
