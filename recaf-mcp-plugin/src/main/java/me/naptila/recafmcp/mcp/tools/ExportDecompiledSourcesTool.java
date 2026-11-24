package me.naptila.recafmcp.mcp.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.internal.AtomicFileInstaller;
import me.naptila.recafmcp.mcp.util.Hashing;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import me.naptila.recafmcp.mcp.util.ToolErrors;
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
    private static final int DEFAULT_PER_CLASS_TIMEOUT_SEC = 10;
    private static final Set<String> ARGUMENTS = Set.of(
            "output_path",
            "overwrite",
            "filter",
            "regex",
            "decompiler",
            "include_inner_classes",
            "scope",
            "dex_name",
            "resource",
            "include_embedded",
            DecompilerToolSupport.PER_CLASS_TIMEOUT_ARGUMENT
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
        Predicate<String> classMatcher = ClassToolSupport.classFilterMatcher(filter, regex);
        ClassToolSupport.Selection selection = ClassToolSupport.parseSelection(arguments, true, true);
        String requestedDecompiler = ToolArguments.optionalString(arguments, "decompiler");
        boolean includeInnerClasses = ToolArguments.bool(arguments, "include_inner_classes", false);
        int perClassTimeoutSec = ToolArguments.integer(
                arguments,
                DecompilerToolSupport.PER_CLASS_TIMEOUT_ARGUMENT,
                DEFAULT_PER_CLASS_TIMEOUT_SEC,
                1,
                DecompilerToolSupport.MAX_PER_CLASS_TIMEOUT_SEC
        );

        if (!hasWorkspace.getAsBoolean()) {
            return error(
                    "workspace not open",
                    null,
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
        JsonObject selectionError = ClassToolSupport.validateSelection(
                workspace.getPrimaryResource(),
                selection
        );
        if (selectionError != null) {
            return selectionError;
        }
        if (!(workspace.getPrimaryResource() instanceof WorkspaceFileResource fileResource)) {
            return error(
                    "workspace primary resource is not file-backed",
                    workspace,
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
                    workspace,
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
                    workspace,
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
                                includeInnerClasses,
                                selection,
                                filter,
                                regex,
                                decompiler,
                                perClassTimeoutSec
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
                    workspace,
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
                    workspace,
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
                ClassToolSupport.outputScope(selection.scope(), true, selection)
        );
        structured.addProperty("requested_scope", selection.scope().value());
        addNullable(structured, "dex_name", selection.dexName());
        ClassToolSupport.addSelectionMetadata(structured, selection);
        structured.addProperty(
                "android_decompilation_input",
                selection.scope().includesAndroid()
                        ? "recaf_android_to_jvm"
                        : "not_selected"
        );
        structured.addProperty("decompiler", decompiler.name());
        structured.addProperty("decompiler_version", decompiler.version());
        structured.addProperty(
                DecompilerToolSupport.PER_CLASS_TIMEOUT_ARGUMENT,
                perClassTimeoutSec
        );
        addNullable(structured, "filter", filter);
        structured.addProperty("regex", regex);
        structured.addProperty("include_inner_classes", includeInnerClasses);
        structured.addProperty("overwrite", overwrite);
        structured.addProperty("classes_selected", classes.size());
        structured.addProperty("inner_classes_inlined", summary.innerClassesInlined());
        structured.addProperty("classes_exported", summary.exportedClasses());
        structured.addProperty("classes_fallback", summary.fallbackClasses());
        structured.addProperty("classes_failed", summary.failures().size());
        structured.addProperty("classes_timed_out", summary.timedOutClasses());
        structured.addProperty(
                "complete",
                summary.failures().isEmpty()
                        && summary.exportedClasses() + summary.innerClassesInlined() == classes.size()
        );
        structured.addProperty(
                "max_in_flight_decompilations",
                MAX_IN_FLIGHT_DECOMPILATIONS
        );
        structured.addProperty(
                "decompilation_cache_temporarily_disabled",
                cacheTemporarilyDisabled
        );
        if (cacheTemporarilyDisabled) {
            structured.addProperty(
                    "decompilation_cache_note",
                    "Recaf's in-memory decompilation cache is bypassed during bulk ZIP export "
                            + "to prevent retaining all exported source strings in Worker heap memory."
            );
        }
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
                                               boolean includeInnerClasses,
                                               ClassToolSupport.Selection selection,
                                               String filter,
                                               boolean regex,
                                               SelectedDecompiler decompiler,
                                               int perClassTimeoutSec)
            throws IOException {
        InnerClassPlan plan = planInnerClasses(classes, includeInnerClasses);
        List<ClassToolSupport.Entry> workList = new ArrayList<>(plan.initialClasses());
        JsonArray manifestEntries = new JsonArray();
        List<Failure> failures = new ArrayList<>();
        int exportedClasses = 0;
        int fallbackClasses = 0;
        int innerClassesInlined = 0;
        int timedOutClasses = 0;
        Deque<DecompilerToolSupport.PendingDecompilation> pending = new ArrayDeque<>();
        MessageDigest archiveDigest = Hashing.newSha256();
        int nextClassIndex = DecompilerToolSupport.fillPending(
                pending,
                workList,
                0,
                MAX_IN_FLIGHT_DECOMPILATIONS,
                workspace,
                decompiler.operation()::run
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
                    DecompilerToolSupport.PendingDecompilation current = pending.removeFirst();
                    DecompilerToolSupport.AwaitOutcome outcome =
                            DecompilerToolSupport.awaitPending(current, perClassTimeoutSec);
                    if (outcome.timedOut()) {
                        timedOutClasses++;
                    }
                    DecompileResult result = outcome.result();
                    String failureMessage = outcome.failureMessage() == null
                            ? null
                            : boundedFailure(outcome.failureMessage());

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

                    List<ClassToolSupport.Entry> deferredChildren =
                            plan.deferredByOuter().getOrDefault(entryGroupKey(current.entry()), List.of());

                    if (failureMessage == null) {
                        byte[] source = result.getText().getBytes(StandardCharsets.UTF_8);
                        writeZipEntry(output, sourcePath, source);
                        manifestEntry.addProperty("status", "success");
                        manifestEntry.addProperty("result_type", "success");
                        manifestEntry.addProperty("source_chars", result.getText().length());
                        manifestEntry.addProperty("source_bytes", source.length);
                        exportedClasses++;
                        innerClassesInlined += deferredChildren.size();
                    } else {
                        if (!deferredChildren.isEmpty()) {
                            workList.addAll(deferredChildren);
                        }
                        boolean wroteFallback = false;
                        if (current.entry().scope() == ClassToolSupport.Scope.ANDROID) {
                            try {
                                String jasm = AndroidAssemblySupport.disassembleClass(
                                        current.entry().classInfo().asAndroidClass()
                                );
                                String jasmPath = jasmEntryPath(current.entry());
                                byte[] jasmBytes = jasm.getBytes(StandardCharsets.UTF_8);
                                writeZipEntry(output, jasmPath, jasmBytes);
                                manifestEntry.addProperty("path", jasmPath);
                                manifestEntry.addProperty("status", "fallback");
                                manifestEntry.addProperty("result_type", "fallback");
                                manifestEntry.addProperty("fallback_format", "dalvik_jasm");
                                manifestEntry.addProperty("fallback_reason", failureMessage);
                                manifestEntry.addProperty("source_chars", jasm.length());
                                manifestEntry.addProperty("source_bytes", jasmBytes.length);
                                exportedClasses++;
                                fallbackClasses++;
                                wroteFallback = true;
                            } catch (Exception fallbackEx) {
                                ToolCancellation.propagateIfRequested(fallbackEx);
                            }
                        }
                        if (!wroteFallback) {
                            Failure failure = new Failure(current.entry(), failureMessage);
                            failures.add(failure);
                            manifestEntry.addProperty("status", outcome.timedOut() ? "timed_out" : "failed");
                            manifestEntry.addProperty("error", failureMessage);
                            if (result != null) {
                                manifestEntry.addProperty(
                                        "result_type",
                                        result.getType().name().toLowerCase(Locale.ROOT)
                                );
                            } else {
                                manifestEntry.addProperty(
                                        "result_type",
                                        outcome.timedOut() ? "timeout" : "unavailable"
                                );
                            }
                        }
                    }
                    manifestEntries.add(manifestEntry);
                    nextClassIndex = DecompilerToolSupport.fillPending(
                            pending,
                            workList,
                            nextClassIndex,
                            MAX_IN_FLIGHT_DECOMPILATIONS,
                            workspace,
                            decompiler.operation()::run
                    );
                }
            } finally {
                DecompilerToolSupport.drainPending(pending);
            }

            JsonObject manifest = new JsonObject();
            manifest.addProperty("format_version", 1);
            manifest.addProperty("generator", "recaf-mcp");
            manifest.addProperty("decompiler", decompiler.name());
            manifest.addProperty("decompiler_version", decompiler.version());
            manifest.addProperty("requested_scope", selection.scope().value());
            addNullable(manifest, "dex_name", selection.dexName());
            ClassToolSupport.addSelectionMetadata(manifest, selection);
            addNullable(manifest, "filter", filter);
            manifest.addProperty("regex", regex);
            manifest.addProperty("include_inner_classes", includeInnerClasses);
            manifest.addProperty("classes_selected", classes.size());
            manifest.addProperty("inner_classes_inlined", innerClassesInlined);
            manifest.addProperty("classes_exported", exportedClasses);
            manifest.addProperty("classes_fallback", fallbackClasses);
            manifest.addProperty("classes_failed", failures.size());
            manifest.addProperty("classes_timed_out", timedOutClasses);
            manifest.addProperty(
                    "complete",
                    failures.isEmpty() && exportedClasses + innerClassesInlined == classes.size()
            );
            manifest.addProperty(
                    "layout",
                    "jvm/<internal-name>.java, android/<dex-name>/<internal-name>.java (.jasm on fallback), "
                            + "and embedded/<resource>/..."
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
                fallbackClasses,
                innerClassesInlined,
                timedOutClasses,
                List.copyOf(failures),
                HexFormat.of().formatHex(archiveDigest.digest())
        );
    }

    private static InnerClassPlan planInnerClasses(List<ClassToolSupport.Entry> classes,
                                                   boolean includeInnerClasses) {
        if (includeInnerClasses) {
            return new InnerClassPlan(classes, java.util.Map.of());
        }
        java.util.Set<EntryGroupKey> present = new java.util.HashSet<>();
        for (ClassToolSupport.Entry entry : classes) {
            present.add(entryGroupKey(entry));
        }
        List<ClassToolSupport.Entry> initial = new ArrayList<>();
        java.util.Map<EntryGroupKey, List<ClassToolSupport.Entry>> deferred = new java.util.LinkedHashMap<>();
        for (ClassToolSupport.Entry entry : classes) {
            EntryGroupKey enclosing = findPresentOuterKey(entry, present);
            if (enclosing != null) {
                deferred.computeIfAbsent(enclosing, ignored -> new ArrayList<>()).add(entry);
            } else {
                initial.add(entry);
            }
        }
        return new InnerClassPlan(initial, deferred);
    }

    private static EntryGroupKey findPresentOuterKey(ClassToolSupport.Entry entry,
                                                     java.util.Set<EntryGroupKey> present) {
        String name = entry.className();
        int firstDollar = name.indexOf('$');
        if (firstDollar <= 0 || firstDollar == name.length() - 1) {
            return null;
        }
        // Prefer the outermost selected ancestor so nested A$B$C attaches to A when A is exported.
        int idx = firstDollar;
        while (idx > 0 && idx < name.length() - 1) {
            String candidateOuter = name.substring(0, idx);
            EntryGroupKey key = new EntryGroupKey(
                    entry.scope(),
                    entry.dexName(),
                    entry.resourcePath(),
                    candidateOuter
            );
            if (present.contains(key)) {
                return key;
            }
            idx = name.indexOf('$', idx + 1);
        }
        return null;
    }

    private static EntryGroupKey entryGroupKey(ClassToolSupport.Entry entry) {
        return new EntryGroupKey(
                entry.scope(),
                entry.dexName(),
                entry.resourcePath(),
                entry.className()
        );
    }

    private static String boundedFailure(String message) {
        String singleLine = message.replace('\n', ' ').replace('\r', ' ').strip();
        return singleLine.length() <= 512 ? singleLine : singleLine.substring(0, 512);
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
        return entryPathWithExtension(entry, ".java");
    }

    static String jasmEntryPath(ClassToolSupport.Entry entry) {
        return entryPathWithExtension(entry, ".jasm");
    }

    private static String entryPathWithExtension(ClassToolSupport.Entry entry, String extension) {
        StringBuilder path = new StringBuilder();
        if (entry.resourcePath() != null) {
            path.append("embedded/")
                    .append(escapePathSegment(entry.resourcePath()))
                    .append('/');
        }
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
        return path.append(extension).toString();
    }

    static String escapePathSegment(String value) {
        if (value == null || value.isEmpty()) {
            return "%00";
        }
        StringBuilder escaped = new StringBuilder(value.length());
        for (int offset = 0; offset < value.length(); ) {
            int cp = value.codePointAt(offset);
            offset += Character.charCount(cp);
            boolean safeAscii = (cp >= 'a' && cp <= 'z')
                    || (cp >= 'A' && cp <= 'Z')
                    || (cp >= '0' && cp <= '9')
                    || cp == '-'
                    || cp == '_'
                    || cp == '$'
                    || cp == '.';
            boolean safeUnicodeIdentifier = cp > 0x7F
                    && Character.isJavaIdentifierPart(cp)
                    && !Character.isISOControl(cp);
            if (safeAscii || safeUnicodeIdentifier) {
                escaped.appendCodePoint(cp);
            } else {
                byte[] utf8 = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
                for (byte raw : utf8) {
                    int current = raw & 0xff;
                    escaped.append('%')
                            .append(Character.toUpperCase(Character.forDigit(current >>> 4, 16)))
                            .append(Character.toUpperCase(Character.forDigit(current & 0x0f, 16)));
                }
            }
        }
        if (escaped.toString().equals(".") || escaped.toString().equals("..")) {
            return escaped.toString().replace(".", "%2E");
        }
        return escaped.toString();
    }

    private JsonObject error(String message,
                             Workspace workspace,
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
        if (workspace != null) {
            ClassToolSupport.addScopeDiagnostics(
                    structured,
                    workspace.getPrimaryResource(),
                    selection
            );
        }
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
        ClassToolSupport.addSelectionMetadata(structured, selection);
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

    private static String exceptionMessage(Throwable throwable) {
        return ToolErrors.message(throwable, 512);
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
        JsonObject includeInnerClasses = new JsonObject();
        includeInnerClasses.addProperty("type", "boolean");
        includeInnerClasses.addProperty("default", false);
        includeInnerClasses.addProperty(
                "description",
                "Export standalone Outer$Inner.java files even when their enclosing Outer.java is exported."
        );
        properties.add("include_inner_classes", includeInnerClasses);
        properties.add(
                "decompiler",
                DecompilerToolSupport.inputSchemaProperty(decompilers)
        );
        properties.add(
                DecompilerToolSupport.PER_CLASS_TIMEOUT_ARGUMENT,
                DecompilerToolSupport.timeoutSchemaProperty(DEFAULT_PER_CLASS_TIMEOUT_SEC)
        );
        ClassToolSupport.addScopeProperties(properties, true, true, true);

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

    private record ArchiveSummary(int exportedClasses,
                                  int fallbackClasses,
                                  int innerClassesInlined,
                                  int timedOutClasses,
                                  List<Failure> failures,
                                  String sha256) {
    }

    private record EntryGroupKey(ClassToolSupport.Scope scope,
                                 String dexName,
                                 String resourcePath,
                                 String className) {
    }

    private record InnerClassPlan(
            List<ClassToolSupport.Entry> initialClasses,
            java.util.Map<EntryGroupKey, List<ClassToolSupport.Entry>> deferredByOuter) {
    }

    private record Failure(ClassToolSupport.Entry entry, String message) {
        private JsonObject toJson() {
            JsonObject failure = identity(entry);
            failure.addProperty("error", message);
            return failure;
        }
    }
}
