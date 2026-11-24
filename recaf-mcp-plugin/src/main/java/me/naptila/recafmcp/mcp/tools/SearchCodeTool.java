package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.decompile.JvmDecompiler;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Searches line-level matches in decompiled primary JVM and Android source.
 */
public final class SearchCodeTool implements McpTool {
    private static final int MAX_LINE_LENGTH = 2000;
    private static final int MAX_COLLECTED_RESULTS = 20_000;
    private static final int MAX_FILTER_LENGTH = 500;
    private static final int MAX_IN_FLIGHT_DECOMPILATIONS = 8;
    private static final int MAX_IMPLICIT_DECOMPILATION_CLASSES = 200;
    private static final int DEFAULT_PER_CLASS_TIMEOUT_SEC = 10;
    private static final Set<String> ARGUMENTS = Set.of(
            "text",
            "regex",
            "filter",
            "decompiler",
            "scope",
            "dex_name",
            "resource",
            "include_embedded",
            "allow_large_scan",
            DecompilerToolSupport.PER_CLASS_TIMEOUT_ARGUMENT,
            "offset",
            "limit"
    );

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;
    private final DecompilerSelector decompilerSelector;
    private final List<DecompilerToolSupport.Descriptor> decompilers;
    private final JsonObject inputSchema;

    public SearchCodeTool(WorkspaceManager workspaceManager,
                          DecompilerManager decompilerManager) {
        this(
                workspaceManager::hasCurrentWorkspace,
                workspaceManager::getCurrent,
                requestedName -> {
                    JvmDecompiler decompiler = DecompilerToolSupport.select(
                            decompilerManager,
                            requestedName
                    );
                    if (decompiler == null) {
                        return null;
                    }
                    boolean allowVineflowerFallback = requestedName == null;
                    return new SelectedDecompiler(
                            decompiler.getName(),
                            decompiler.getVersion(),
                            (workspace, classInfo) -> DecompilerToolSupport.decompile(
                                    decompilerManager,
                                    decompiler,
                                    allowVineflowerFallback,
                                    workspace,
                                    classInfo
                            )
                    );
                },
                DecompilerToolSupport.descriptors(decompilerManager)
        );
    }

    SearchCodeTool(BooleanSupplier hasWorkspace,
                   Supplier<Workspace> workspaceSupplier,
                   DecompileOperation decompileOperation) {
        this(
                hasWorkspace,
                workspaceSupplier,
                requestedName -> new SelectedDecompiler(
                        "test",
                        "test",
                        (workspace, classInfo) -> CompletableFuture.completedFuture(
                                decompileOperation.run(workspace, classInfo)
                        )
                ),
                List.of(new DecompilerToolSupport.Descriptor("test", "test", true))
        );
    }

    SearchCodeTool(BooleanSupplier hasWorkspace,
                   Supplier<Workspace> workspaceSupplier,
                   DecompilerSelector decompilerSelector) {
        this(hasWorkspace, workspaceSupplier, decompilerSelector, List.of());
    }

    SearchCodeTool(BooleanSupplier hasWorkspace,
                   Supplier<Workspace> workspaceSupplier,
                   DecompilerSelector decompilerSelector,
                   List<DecompilerToolSupport.Descriptor> decompilers) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
        this.decompilerSelector = Objects.requireNonNull(
                decompilerSelector,
                "decompilerSelector"
        );
        this.decompilers = List.copyOf(decompilers);
        inputSchema = createInputSchema(this.decompilers);
    }

    @Override
    public String name() {
        return "search_code";
    }

    @Override
    public String description() {
        return "Search decompiled JVM and/or Android source lines.";
    }

    @Override
    public JsonObject inputSchema() {
        return inputSchema.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        SearchToolSupport.Request request = SearchToolSupport.parse(arguments, ARGUMENTS);
        ClassToolSupport.Selection selection = ClassToolSupport.parseSelection(arguments, true, true);
        String filter = ToolArguments.optionalString(arguments, "filter");
        String requestedDecompiler = ToolArguments.optionalString(arguments, "decompiler");
        boolean allowLargeScan = ToolArguments.bool(arguments, "allow_large_scan", false);
        int perClassTimeoutSec = ToolArguments.integer(
                arguments,
                DecompilerToolSupport.PER_CLASS_TIMEOUT_ARGUMENT,
                DEFAULT_PER_CLASS_TIMEOUT_SEC,
                1,
                DecompilerToolSupport.MAX_PER_CLASS_TIMEOUT_SEC
        );
        if (filter != null && filter.length() > MAX_FILTER_LENGTH) {
            throw new IllegalArgumentException("filter is too long");
        }
        Predicate<String> lineMatcher = SearchToolSupport.unicodeAwareMatcher(
                request.text(),
                request.regex()
        );
        if (!hasWorkspace.getAsBoolean()) {
            JsonObject error = new JsonObject();
            error.addProperty("error", "workspace not open");
            return McpTool.errorResult("workspace not open", error);
        }

        Workspace workspace = workspaceSupplier.get();
        selection = ClassToolSupport.resolve(workspace.getPrimaryResource(), selection);
        SelectedDecompiler decompiler = decompilerSelector.select(requestedDecompiler);
        if (decompiler == null) {
            JsonObject error = new JsonObject();
            error.addProperty("error", "requested JVM decompiler is not available");
            if (requestedDecompiler == null) {
                error.add("requested_decompiler", JsonNull.INSTANCE);
            } else {
                error.addProperty("requested_decompiler", requestedDecompiler);
            }
            DecompilerToolSupport.addAvailability(error, decompilers);
            error.addProperty("requested_scope", selection.scope().value());
            return McpTool.errorResult("requested JVM decompiler is not available", error);
        }

        JsonObject selectionError = ClassToolSupport.validateSelection(
                workspace.getPrimaryResource(),
                selection
        );
        if (selectionError != null) {
            return selectionError;
        }
        List<ClassToolSupport.Entry> availableClasses = ClassToolSupport.entries(
                workspace.getPrimaryResource(),
                selection
        );
        Predicate<String> filterMatcher = ClassToolSupport.classFilterMatcher(filter, false);
        List<ClassToolSupport.Entry> classes = availableClasses
                .stream()
                .filter(entry -> filterMatcher.test(entry.className()))
                .toList();
        if (classes.size() > MAX_IMPLICIT_DECOMPILATION_CLASSES && !allowLargeScan) {
            return largeScopeError(
                    request,
                    filter,
                    selection,
                    decompiler,
                    availableClasses.size(),
                    classes.size()
            );
        }
        JsonArray page = new JsonArray();
        JsonArray failedClassesArray = new JsonArray();
        JsonArray fallbackClassesArray = new JsonArray();
        int total = 0;
        int failedClasses = 0;
        int fallbackClasses = 0;
        int timedOutClasses = 0;
        int scannedClasses = 0;
        boolean collectionTruncated = false;
        Deque<DecompilerToolSupport.PendingDecompilation> pending = new ArrayDeque<>();
        int nextClassIndex = DecompilerToolSupport.fillPending(
                pending,
                classes,
                0,
                MAX_IN_FLIGHT_DECOMPILATIONS,
                workspace,
                decompiler.operation()::run
        );
        try {
            search:
            while (!pending.isEmpty()) {
                ToolCancellation.check();
                DecompilerToolSupport.PendingDecompilation current = pending.removeFirst();
                scannedClasses++;
                DecompilerToolSupport.AwaitOutcome outcome =
                        DecompilerToolSupport.awaitPending(current, perClassTimeoutSec);
                if (outcome.timedOut()) {
                    timedOutClasses++;
                }
                DecompileResult result = outcome.result();
                String searchableText = null;
                String sourceKind = "decompiled_java";
                if (result != null
                        && result.getType() == DecompileResult.ResultType.SUCCESS
                        && result.getText() != null) {
                    searchableText = result.getText();
                } else if (current.entry().scope() == ClassToolSupport.Scope.ANDROID) {
                    try {
                        searchableText = AndroidAssemblySupport.disassembleClass(
                                current.entry().classInfo().asAndroidClass()
                        );
                        sourceKind = "dalvik_jasm_fallback";
                        fallbackClasses++;
                        if (fallbackClassesArray.size() < 50) {
                            JsonObject fallbackItem = new JsonObject();
                            fallbackItem.addProperty("class_name", current.entry().className());
                            ClassToolSupport.addHitIdentity(fallbackItem, current.entry());
                            String fbReason = outcome.failureMessage() != null
                                    ? outcome.failureMessage()
                                    : (result != null && result.getException() != null
                                            ? result.getException().getClass().getSimpleName()
                                            : "decompilation_unavailable");
                            fallbackItem.addProperty("reason", fbReason);
                            fallbackClassesArray.add(fallbackItem);
                        }
                    } catch (Exception fallbackEx) {
                        ToolCancellation.propagateIfRequested(fallbackEx);
                    }
                }
                if (searchableText == null) {
                    failedClasses++;
                    if (failedClassesArray.size() < 50) {
                        JsonObject failedItem = new JsonObject();
                        failedItem.addProperty("class_name", current.entry().className());
                        ClassToolSupport.addHitIdentity(failedItem, current.entry());
                        String reason = outcome.failureMessage() != null
                                ? outcome.failureMessage()
                                : (result != null && result.getException() != null
                                        ? result.getException().getClass().getSimpleName()
                                        : "decompilation_unavailable");
                        failedItem.addProperty("reason", reason);
                        failedClassesArray.add(failedItem);
                    }
                    nextClassIndex = DecompilerToolSupport.fillPending(
                            pending,
                            classes,
                            nextClassIndex,
                            MAX_IN_FLIGHT_DECOMPILATIONS,
                            workspace,
                            decompiler.operation()::run
                    );
                    continue;
                }
                String[] lines = searchableText.split("\\R", -1);
                for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
                    ToolCancellation.check();
                    String line = lines[lineIndex];
                    if (!lineMatcher.test(line)) {
                        continue;
                    }
                    if (total >= MAX_COLLECTED_RESULTS) {
                        collectionTruncated = true;
                        break search;
                    }
                    if (SearchToolSupport.inPage(total, request)) {
                        page.add(hit(current.entry(), lineIndex + 1, line, sourceKind));
                    }
                    total++;
                }
                nextClassIndex = DecompilerToolSupport.fillPending(
                        pending,
                        classes,
                        nextClassIndex,
                        MAX_IN_FLIGHT_DECOMPILATIONS,
                        workspace,
                        decompiler.operation()::run
                );
            }
        } finally {
            DecompilerToolSupport.drainPending(pending);
        }

        JsonObject structuredContent = new JsonObject();
        structuredContent.add("hits", page);
        structuredContent.addProperty(
                "scope",
                ClassToolSupport.outputScope(selection.scope(), true, selection)
        );
        structuredContent.addProperty("requested_scope", selection.scope().value());
        if (selection.dexName() == null) {
            structuredContent.add("dex_name", JsonNull.INSTANCE);
        } else {
            structuredContent.addProperty("dex_name", selection.dexName());
        }
        ClassToolSupport.addSelectionMetadata(structuredContent, selection);
        structuredContent.addProperty(
                "android_decompilation_input",
                selection.scope().includesAndroid()
                        ? "recaf_android_to_jvm"
                        : "not_selected"
        );
        structuredContent.addProperty("decompiler", decompiler.name());
        structuredContent.addProperty("decompiler_version", decompiler.version());
        structuredContent.addProperty(
                DecompilerToolSupport.PER_CLASS_TIMEOUT_ARGUMENT,
                perClassTimeoutSec
        );
        structuredContent.addProperty(
                "max_in_flight_decompilations",
                MAX_IN_FLIGHT_DECOMPILATIONS
        );
        structuredContent.addProperty(
                "implicit_scan_class_limit",
                MAX_IMPLICIT_DECOMPILATION_CLASSES
        );
        structuredContent.addProperty("allow_large_scan", allowLargeScan);
        if (filter == null) {
            structuredContent.add("filter", JsonNull.INSTANCE);
        } else {
            structuredContent.addProperty("filter", filter);
        }
        structuredContent.addProperty("classes_selected", classes.size());
        structuredContent.addProperty("classes_scanned", scannedClasses);
        structuredContent.addProperty("classes_fallback", fallbackClasses);
        structuredContent.add("fallback_classes", fallbackClassesArray);
        structuredContent.addProperty(
                "fallback_classes_truncated",
                fallbackClasses > fallbackClassesArray.size()
        );
        structuredContent.addProperty("classes_failed", failedClasses);
        structuredContent.addProperty("classes_timed_out", timedOutClasses);
        structuredContent.add("failed_classes", failedClassesArray);
        structuredContent.addProperty("failed_classes_truncated", failedClasses > failedClassesArray.size());
        structuredContent.addProperty("collection_truncated", collectionTruncated);
        if (total == 0) {
            ClassToolSupport.addScopeDiagnostics(
                    structuredContent,
                    workspace.getPrimaryResource(),
                    selection
            );
        }
        SearchToolSupport.addPageMetadata(
                structuredContent,
                request,
                total,
                page.size()
        );
        return McpTool.successResult(
                "found " + total + " matching decompiled source lines",
                structuredContent
        );
    }

    private static JsonObject createInputSchema(
            List<DecompilerToolSupport.Descriptor> decompilers) {
        JsonObject schema = SearchToolSupport.inputSchema(
                "Source-line literal or partial Java regex."
        );
        JsonObject filter = new JsonObject();
        filter.addProperty("type", "string");
        filter.addProperty("minLength", 1);
        filter.addProperty("maxLength", MAX_FILTER_LENGTH);
        filter.addProperty(
                "description",
                "Class-name substring or package prefix (internal a/b/C or dotted a.b.C) applied before decompilation."
        );
        schema.getAsJsonObject("properties").add("filter", filter);
        schema.getAsJsonObject("properties").add(
                "decompiler",
                DecompilerToolSupport.inputSchemaProperty(decompilers)
        );
        schema.getAsJsonObject("properties").add(
                DecompilerToolSupport.PER_CLASS_TIMEOUT_ARGUMENT,
                DecompilerToolSupport.timeoutSchemaProperty(DEFAULT_PER_CLASS_TIMEOUT_SEC)
        );
        ClassToolSupport.addScopeProperties(schema.getAsJsonObject("properties"), true, true, true);
        JsonObject allowLargeScan = new JsonObject();
        allowLargeScan.addProperty("type", "boolean");
        allowLargeScan.addProperty("default", false);
        allowLargeScan.addProperty(
                "description",
                "Allow decompiling more than "
                        + MAX_IMPLICIT_DECOMPILATION_CLASSES
                        + " classes; prefer filter or search_instructions."
        );
        schema.getAsJsonObject("properties").add("allow_large_scan", allowLargeScan);
        return schema;
    }

    private static JsonObject largeScopeError(SearchToolSupport.Request request,
                                              String filter,
                                              ClassToolSupport.Selection selection,
                                              SelectedDecompiler decompiler,
                                              int classesAvailable,
                                              int classesSelected) {
        String message = "search_code selected " + classesSelected
                + " classes, above the implicit cold-scan limit of "
                + MAX_IMPLICIT_DECOMPILATION_CLASSES
                + "; narrow filter, use search_instructions, or set "
                + "allow_large_scan=true explicitly";
        JsonObject structured = new JsonObject();
        structured.addProperty("error", "large_decompilation_scope_requires_opt_in");
        structured.addProperty("message", message);
        structured.addProperty(
                "scope",
                ClassToolSupport.outputScope(selection.scope(), true)
        );
        structured.addProperty("requested_scope", selection.scope().value());
        if (selection.dexName() == null) {
            structured.add("dex_name", JsonNull.INSTANCE);
        } else {
            structured.addProperty("dex_name", selection.dexName());
        }
        structured.addProperty("text", request.text());
        structured.addProperty("regex", request.regex());
        structured.addProperty("decompiler", decompiler.name());
        structured.addProperty("decompiler_version", decompiler.version());
        if (filter == null) {
            structured.add("filter", JsonNull.INSTANCE);
        } else {
            structured.addProperty("filter", filter);
        }
        structured.addProperty("classes_available", classesAvailable);
        structured.addProperty("classes_selected", classesSelected);
        structured.addProperty(
                "implicit_scan_class_limit",
                MAX_IMPLICIT_DECOMPILATION_CLASSES
        );
        structured.addProperty("allow_large_scan", false);
        JsonArray suggestions = new JsonArray();
        suggestions.add("Narrow filter to a class name or business-package prefix.");
        suggestions.add("Use search_instructions for a fast whole-JAR bytecode search.");
        suggestions.add("Set allow_large_scan=true to accept cold decompilation cost.");
        structured.add("suggestions", suggestions);
        return McpTool.errorResult(message, structured);
    }

    private static JsonObject hit(ClassToolSupport.Entry entry,
                                  int lineNumber,
                                  String line,
                                  String sourceKind) {
        JsonObject hit = new JsonObject();
        hit.addProperty("class_name", entry.className());
        ClassToolSupport.addHitIdentity(hit, entry);
        hit.addProperty("source_kind", sourceKind);
        hit.addProperty("line_number", lineNumber);
        boolean truncated = line.length() > MAX_LINE_LENGTH;
        hit.addProperty("line", truncated ? line.substring(0, MAX_LINE_LENGTH) : line);
        hit.addProperty("line_truncated", truncated);
        return hit;
    }

    @FunctionalInterface
    interface DecompileOperation {
        DecompileResult run(Workspace workspace, JvmClassInfo classInfo) throws Exception;
    }

    @FunctionalInterface
    interface AsyncDecompileOperation {
        CompletableFuture<DecompileResult> run(Workspace workspace,
                                               JvmClassInfo classInfo) throws Exception;
    }

    @FunctionalInterface
    interface DecompilerSelector {
        SelectedDecompiler select(String requestedName);
    }

    record SelectedDecompiler(String name,
                              String version,
                              AsyncDecompileOperation operation) {
        SelectedDecompiler {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(operation, "operation");
        }
    }
}
