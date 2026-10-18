package me.naptila.recafmcp.mcp.tools;

import me.naptila.recafmcp.mcp.android.AndroidJvmConverter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.internal.AtomicFileInstaller;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.properties.builtin.InputFilePathProperty;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.decompile.JvmDecompiler;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Decompiles selected primary-resource classes into an atomic source ZIP.
 */
public final class ExportDecompiledSourcesTool implements McpTool {
    static final String MANIFEST_PATH = "_recaf-mcp/manifest.json";
    private static final Gson MANIFEST_GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();
    private static final int MAX_FILTER_LENGTH = 500;
    private static final int MAX_IN_FLIGHT_DECOMPILATIONS = 8;
    private static final int MAX_REPORTED_FAILURES = 100;
    private static final Set<String> ARGUMENTS = Set.of(
            "output_path",
            "overwrite",
            "filter",
            "regex",
            "decompiler",
            "scope",
            "dex_name"
    );

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;
    private final DecompilerSelector decompilerSelector;
    private final CacheControl cacheControl;
    private final List<DecompilerToolSupport.Descriptor> decompilers;
    private final JsonObject inputSchema;

    public ExportDecompiledSourcesTool(WorkspaceManager workspaceManager,
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
                    return new SelectedDecompiler(
                            decompiler.getName(),
                            decompiler.getVersion(),
                            (workspace, classInfo) -> decompilerManager.decompile(
                                    decompiler,
                                    workspace,
                                    classInfo
                            )
                    );
                },
                new CacheControl(
                        () -> decompilerManager.getServiceConfig()
                                .getCacheDecompilations().getValue(),
                        enabled -> decompilerManager.getServiceConfig()
                                .getCacheDecompilations().setValue(enabled)
                ),
                DecompilerToolSupport.descriptors(decompilerManager)
        );
    }

    ExportDecompiledSourcesTool(BooleanSupplier hasWorkspace,
                                Supplier<Workspace> workspaceSupplier,
                                DecompilerSelector decompilerSelector,
                                List<DecompilerToolSupport.Descriptor> decompilers) {
        this(
                hasWorkspace,
                workspaceSupplier,
                decompilerSelector,
                new CacheControl(() -> false, ignored -> {}),
                decompilers
        );
    }

    ExportDecompiledSourcesTool(BooleanSupplier hasWorkspace,
                                Supplier<Workspace> workspaceSupplier,
                                DecompilerSelector decompilerSelector,
                                CacheControl cacheControl,
                                List<DecompilerToolSupport.Descriptor> decompilers) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
        this.decompilerSelector = Objects.requireNonNull(
                decompilerSelector,
                "decompilerSelector"
        );
        this.cacheControl = Objects.requireNonNull(cacheControl, "cacheControl");
        this.decompilers = List.copyOf(decompilers);
        inputSchema = createInputSchema(this.decompilers);
    }

    @Override
    public String name() {
        return "export_sources";
    }

    @Override
    public String description() {
        return "Decompile filtered JVM and/or Android classes into an atomic source ZIP.";
    }

    @Override
    public JsonObject inputSchema() {
        return inputSchema.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        String requestedOutput = ToolArguments.requiredString(arguments, "output_path");
        Path outputPath;
        try {
            outputPath = Path.of(requestedOutput);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("output_path is not a valid filesystem path", ex);
        }
        if (!outputPath.isAbsolute()) {
            throw new IllegalArgumentException("output_path must be absolute");
        }
        boolean overwrite = ToolArguments.bool(arguments, "overwrite", false);
        String filter = ToolArguments.optionalString(arguments, "filter");
        boolean regex = ToolArguments.bool(arguments, "regex", false);
        if (filter != null && filter.length() > MAX_FILTER_LENGTH) {
            throw new IllegalArgumentException("filter is too long");
        }
        if (regex && filter == null) {
            throw new IllegalArgumentException("regex=true requires filter");
        }
        if (regex) {
            SearchToolSupport.validateRegex(filter, "filter");
        }
        Predicate<String> classMatcher = SearchToolSupport.matcher(filter, regex);
        ClassToolSupport.Selection selection = ClassToolSupport.parseSelection(arguments, true);
        String requestedDecompiler = ToolArguments.optionalString(arguments, "decompiler");

        if (!hasWorkspace.getAsBoolean()) {
            return error(
                    "workspace not open",
                    requestedOutput,
                    filter,
                    regex,
                    selection,
                    requestedDecompiler
            );
        }

        SelectedDecompiler decompiler = decompilerSelector.select(requestedDecompiler);
        if (decompiler == null) {
            JsonObject structured = errorMetadata(
                    requestedOutput,
                    filter,
                    regex,
                    selection,
                    requestedDecompiler
            );
            structured.addProperty("error", "requested JVM decompiler is not available");
            DecompilerToolSupport.addAvailability(structured, decompilers);
            return McpTool.errorResult(
                    "requested JVM decompiler is not available",
                    structured
            );
        }

        Workspace workspace = workspaceSupplier.get();
        if (!(workspace.getPrimaryResource() instanceof WorkspaceFileResource fileResource)) {
            return error(
                    "workspace primary resource is not file-backed",
                    requestedOutput,
                    filter,
                    regex,
                    selection,
                    requestedDecompiler
            );
        }
        Path inputPath = InputFilePathProperty.get(fileResource.getFileInfo());
        if (inputPath == null) {
            return error(
                    "workspace primary resource has no input path",
                    requestedOutput,
                    filter,
                    regex,
                    selection,
                    requestedDecompiler
            );
        }

        List<ClassToolSupport.Entry> classes = ClassToolSupport.entries(
                        workspace.getPrimaryResource(),
                        selection
                ).stream()
                .filter(entry -> classMatcher.test(entry.className()))
                .toList();
        if (classes.isEmpty()) {
            return error(
                    "no classes matched the requested export scope and filter",
                    requestedOutput,
                    filter,
                    regex,
                    selection,
                    requestedDecompiler
            );
        }

        AtomicReference<ArchiveSummary> summaryReference = new AtomicReference<>();
        AtomicFileInstaller.Result installed;
        boolean cacheTemporarilyDisabled = cacheControl.enabled().getAsBoolean();
        try {
            if (cacheTemporarilyDisabled) {
                cacheControl.setter().accept(false);
            }
            try {
                installed = AtomicFileInstaller.install(
                        inputPath,
                        outputPath,
                        overwrite,
                        temporary -> summaryReference.set(writeArchive(
                                temporary,
                                workspace,
                                classes,
                                selection,
                                filter,
                                regex,
                                decompiler
                        ))
                );
            } finally {
                if (cacheTemporarilyDisabled) {
                    cacheControl.setter().accept(true);
                }
            }
        } catch (FileAlreadyExistsException ex) {
            return error(
                    "destination already exists; set overwrite=true to replace it",
                    requestedOutput,
                    filter,
                    regex,
                    selection,
                    requestedDecompiler
            );
        } catch (IOException ex) {
            ToolCancellation.propagateIfRequested(ex);
            return error(
                    "source export failed: " + exceptionMessage(ex),
                    requestedOutput,
                    filter,
                    regex,
                    selection,
                    requestedDecompiler
            );
        }
        ArchiveSummary summary = Objects.requireNonNull(
                summaryReference.get(),
                "archive summary"
        );

        JsonObject structured = new JsonObject();
        structured.addProperty("path", installed.path().toString());
        structured.addProperty("size_bytes", installed.sizeBytes());
        structured.addProperty("zip_entries", installed.zipEntries());
        structured.addProperty("sha256", summary.sha256());
        structured.addProperty("format", "zip");
        structured.addProperty("manifest_path", MANIFEST_PATH);
        structured.addProperty(
                "scope",
                ClassToolSupport.outputScope(selection.scope(), true)
        );
        structured.addProperty("requested_scope", selection.scope().value());
        addNullable(structured, "dex_name", selection.dexName());
        structured.addProperty(
                "android_decompilation_input",
                selection.scope().includesAndroid()
                        ? "recaf_android_to_jvm"
                        : "not_selected"
        );
        structured.addProperty("decompiler", decompiler.name());
        structured.addProperty("decompiler_version", decompiler.version());
        addNullable(structured, "filter", filter);
        structured.addProperty("regex", regex);
        structured.addProperty("overwrite", overwrite);
        structured.addProperty("classes_selected", classes.size());
        structured.addProperty("classes_exported", summary.exportedClasses());
        structured.addProperty("classes_failed", summary.failures().size());
        structured.addProperty(
                "complete",
                summary.exportedClasses() == classes.size()
        );
        structured.addProperty(
                "max_in_flight_decompilations",
                MAX_IN_FLIGHT_DECOMPILATIONS
        );
        structured.addProperty(
                "decompilation_cache_temporarily_disabled",
                cacheTemporarilyDisabled
        );
        structured.addProperty("decompilation_results_cached", false);
        JsonArray failures = new JsonArray();
        summary.failures().stream()
                .limit(MAX_REPORTED_FAILURES)
                .forEach(failure -> failures.add(failure.toJson()));
        structured.add("failures", failures);
        structured.addProperty(
                "failures_truncated",
                summary.failures().size() > MAX_REPORTED_FAILURES
        );
        return McpTool.successResult(
                "exported " + summary.exportedClasses() + " of "
                        + classes.size() + " decompiled classes to "
                        + installed.path(),
                structured
        );
    }

    private static ArchiveSummary writeArchive(Path path,
                                               Workspace workspace,
                                               List<ClassToolSupport.Entry> classes,
                                               ClassToolSupport.Selection selection,
                                               String filter,
                                               boolean regex,
                                               SelectedDecompiler decompiler)
            throws IOException {
        JsonArray manifestEntries = new JsonArray();
        List<Failure> failures = new ArrayList<>();
        int exportedClasses = 0;
        Deque<PendingDecompilation> pending = new ArrayDeque<>();
        MessageDigest archiveDigest = newSha256Digest();
        int nextClassIndex = fillPending(
                pending,
                classes,
                0,
                workspace,
                decompiler
        );
        try (ZipOutputStream output = new ZipOutputStream(
                new DigestOutputStream(
                        new BufferedOutputStream(Files.newOutputStream(path)),
                        archiveDigest
                ),
                StandardCharsets.UTF_8
        )) {
            try {
                while (!pending.isEmpty()) {
                    ToolCancellation.check();
                    PendingDecompilation current = pending.removeFirst();
                    DecompileResult result = null;
                    String failureMessage = null;
                    try {
                        result = current.result().get();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new CancellationException("tool operation was cancelled");
                    } catch (ExecutionException ex) {
                        ToolCancellation.propagateIfRequested(ex.getCause());
                        failureMessage = exceptionMessage(ex.getCause());
                    } catch (CancellationException ex) {
                        ToolCancellation.propagateIfRequested(ex);
                        failureMessage = exceptionMessage(ex);
                    }

                    JsonObject manifestEntry = identity(current.entry());
                    String sourcePath = sourceEntryPath(current.entry());
                    manifestEntry.addProperty("path", sourcePath);
                    if (failureMessage == null && (result == null
                            || result.getType() != DecompileResult.ResultType.SUCCESS
                            || result.getText() == null)) {
                        String resultType = result == null
                                ? "unavailable"
                                : result.getType().name().toLowerCase(Locale.ROOT);
                        failureMessage = result != null && result.getException() != null
                                ? exceptionMessage(result.getException())
                                : "decompiler returned " + resultType;
                    }

                    if (failureMessage == null) {
                        byte[] source = result.getText().getBytes(StandardCharsets.UTF_8);
                        writeZipEntry(output, sourcePath, source);
                        manifestEntry.addProperty("status", "success");
                        manifestEntry.addProperty("result_type", "success");
                        manifestEntry.addProperty("source_chars", result.getText().length());
                        manifestEntry.addProperty("source_bytes", source.length);
                        exportedClasses++;
                    } else {
                        Failure failure = new Failure(current.entry(), failureMessage);
                        failures.add(failure);
                        manifestEntry.addProperty("status", "failed");
                        manifestEntry.addProperty("error", failureMessage);
                        if (result != null) {
                            manifestEntry.addProperty(
                                    "result_type",
                                    result.getType().name().toLowerCase(Locale.ROOT)
                            );
                        } else {
                            manifestEntry.addProperty("result_type", "unavailable");
                        }
                    }
                    manifestEntries.add(manifestEntry);
                    nextClassIndex = fillPending(
                            pending,
                            classes,
                            nextClassIndex,
                            workspace,
                            decompiler
                    );
                }
            } finally {
                drainPending(pending);
            }

            JsonObject manifest = new JsonObject();
            manifest.addProperty("format_version", 1);
            manifest.addProperty("generator", "recaf-mcp");
            manifest.addProperty("decompiler", decompiler.name());
            manifest.addProperty("decompiler_version", decompiler.version());
            manifest.addProperty("requested_scope", selection.scope().value());
            addNullable(manifest, "dex_name", selection.dexName());
            addNullable(manifest, "filter", filter);
            manifest.addProperty("regex", regex);
            manifest.addProperty("classes_selected", classes.size());
            manifest.addProperty("classes_exported", exportedClasses);
            manifest.addProperty("classes_failed", failures.size());
            manifest.addProperty(
                    "complete",
                    exportedClasses == classes.size()
            );
            manifest.addProperty(
                    "layout",
                    "jvm/<internal-name>.java and android/<dex-name>/<internal-name>.java"
            );
            manifest.add("classes", manifestEntries);
            writeZipEntry(
                    output,
                    MANIFEST_PATH,
                    MANIFEST_GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8)
            );
        }
        return new ArchiveSummary(
                exportedClasses,
                List.copyOf(failures),
                HexFormat.of().formatHex(archiveDigest.digest())
        );
    }

    private static int fillPending(Deque<PendingDecompilation> pending,
                                   List<ClassToolSupport.Entry> classes,
                                   int nextClassIndex,
                                   Workspace workspace,
                                   SelectedDecompiler decompiler) {
        while (pending.size() < MAX_IN_FLIGHT_DECOMPILATIONS
                && nextClassIndex < classes.size()) {
            ToolCancellation.check();
            ClassToolSupport.Entry entry = classes.get(nextClassIndex++);
            CompletableFuture<DecompileResult> result;
            try {
                JvmClassInfo classInfo = entry.scope() == ClassToolSupport.Scope.ANDROID
                        ? AndroidJvmConverter.convert(entry.classInfo().asAndroidClass())
                        : entry.classInfo().asJvmClass();
                result = Objects.requireNonNull(
                        decompiler.operation().run(workspace, classInfo),
                        "decompilation future"
                );
            } catch (Exception ex) {
                result = CompletableFuture.failedFuture(ex);
            }
            pending.addLast(new PendingDecompilation(entry, result));
        }
        return nextClassIndex;
    }

    private static void drainPending(Deque<PendingDecompilation> pending) {
        while (!pending.isEmpty()) {
            try {
                pending.removeFirst().result().join();
            } catch (CancellationException | CompletionException ignored) {
                // Draining keeps Recaf decompiler tasks inside the tool lifetime.
            }
        }
    }

    private static void writeZipEntry(ZipOutputStream output,
                                      String name,
                                      byte[] content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        output.putNextEntry(entry);
        output.write(content);
        output.closeEntry();
    }

    static String sourceEntryPath(ClassToolSupport.Entry entry) {
        StringBuilder path = new StringBuilder();
        if (entry.scope() == ClassToolSupport.Scope.ANDROID) {
            path.append("android/")
                    .append(escapePathSegment(entry.dexName()))
                    .append('/');
        } else {
            path.append("jvm/");
        }
        String[] segments = entry.className().split("/", -1);
        for (int index = 0; index < segments.length; index++) {
            if (index > 0) {
                path.append('/');
            }
            path.append(escapePathSegment(segments[index]));
        }
        return path.append(".java").toString();
    }

    static String escapePathSegment(String value) {
        if (value == null || value.isEmpty()) {
            return "%00";
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder escaped = new StringBuilder(bytes.length);
        for (byte raw : bytes) {
            int current = raw & 0xff;
            boolean safe = current >= 'a' && current <= 'z'
                    || current >= 'A' && current <= 'Z'
                    || current >= '0' && current <= '9'
                    || current == '-'
                    || current == '_'
                    || current == '$'
                    || current == '.';
            if (safe) {
                escaped.append((char) current);
            } else {
                escaped.append('%')
                        .append(Character.toUpperCase(Character.forDigit(current >>> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(current & 0x0f, 16)));
            }
        }
        if (escaped.toString().equals(".") || escaped.toString().equals("..")) {
            return escaped.toString().replace(".", "%2E");
        }
        return escaped.toString();
    }

    private JsonObject error(String message,
                             String outputPath,
                             String filter,
                             boolean regex,
                             ClassToolSupport.Selection selection,
                             String requestedDecompiler) {
        JsonObject structured = errorMetadata(
                outputPath,
                filter,
                regex,
                selection,
                requestedDecompiler
        );
        structured.addProperty("error", message);
        return McpTool.errorResult(message, structured);
    }

    private JsonObject errorMetadata(String outputPath,
                                     String filter,
                                     boolean regex,
                                     ClassToolSupport.Selection selection,
                                     String requestedDecompiler) {
        JsonObject structured = new JsonObject();
        structured.addProperty("output_path", outputPath);
        structured.addProperty("requested_scope", selection.scope().value());
        addNullable(structured, "dex_name", selection.dexName());
        addNullable(structured, "filter", filter);
        structured.addProperty("regex", regex);
        addNullable(structured, "requested_decompiler", requestedDecompiler);
        return structured;
    }

    private static JsonObject identity(ClassToolSupport.Entry entry) {
        JsonObject identity = new JsonObject();
        identity.addProperty("class_name", entry.className());
        ClassToolSupport.addHitIdentity(identity, entry);
        return identity;
    }

    private static void addNullable(JsonObject target, String name, String value) {
        if (value == null) {
            target.add(name, JsonNull.INSTANCE);
        } else {
            target.addProperty(name, value);
        }
    }

    private static MessageDigest newSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static String exceptionMessage(Throwable throwable) {
        if (throwable == null) {
            return "unknown failure";
        }
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        String singleLine = message.replace('\n', ' ').replace('\r', ' ').strip();
        return singleLine.length() <= 512 ? singleLine : singleLine.substring(0, 512);
    }

    private static JsonObject createInputSchema(
            List<DecompilerToolSupport.Descriptor> decompilers) {
        JsonObject properties = new JsonObject();
        JsonObject outputPath = new JsonObject();
        outputPath.addProperty("type", "string");
        outputPath.addProperty("minLength", 1);
        outputPath.addProperty(
                "description",
                "Absolute destination ZIP path."
        );
        properties.add("output_path", outputPath);
        JsonObject overwrite = new JsonObject();
        overwrite.addProperty("type", "boolean");
        overwrite.addProperty("default", false);
        overwrite.addProperty(
                "description",
                "Replace an existing safe destination."
        );
        properties.add("overwrite", overwrite);
        JsonObject filter = new JsonObject();
        filter.addProperty("type", "string");
        filter.addProperty("minLength", 1);
        filter.addProperty("maxLength", MAX_FILTER_LENGTH);
        filter.addProperty(
                "description",
                "Internal class-name substring or package prefix."
        );
        properties.add("filter", filter);
        JsonObject regex = new JsonObject();
        regex.addProperty("type", "boolean");
        regex.addProperty("default", false);
        regex.addProperty(
                "description",
                "Use a partial Java regex for filter; requires filter."
        );
        properties.add("regex", regex);
        properties.add(
                "decompiler",
                DecompilerToolSupport.inputSchemaProperty(decompilers)
        );
        ClassToolSupport.addScopeProperties(properties, true);

        JsonArray required = new JsonArray();
        required.add("output_path");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
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

    record CacheControl(BooleanSupplier enabled, Consumer<Boolean> setter) {
        CacheControl {
            Objects.requireNonNull(enabled, "enabled");
            Objects.requireNonNull(setter, "setter");
        }
    }

    private record PendingDecompilation(ClassToolSupport.Entry entry,
                                        CompletableFuture<DecompileResult> result) {
    }

    private record ArchiveSummary(int exportedClasses,
                                  List<Failure> failures,
                                  String sha256) {
    }

    private record Failure(ClassToolSupport.Entry entry, String message) {
        private JsonObject toJson() {
            JsonObject failure = identity(entry);
            failure.addProperty("error", message);
            return failure;
        }
    }
}
