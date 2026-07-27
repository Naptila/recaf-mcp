package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.services.deobfuscation.transform.generic.DeadCodeRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.DuplicateAnnotationRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.FrameRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.GotoInliningTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.IllegalAnnotationRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.IllegalSignatureRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.IllegalVarargsRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.RedundantTryCatchRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.UnknownAttributeRemovingTransformer;
import software.coley.recaf.services.transform.JvmClassTransformer;
import software.coley.recaf.services.transform.JvmTransformResult;
import software.coley.recaf.services.transform.TransformationApplier;
import software.coley.recaf.services.transform.TransformationApplierService;
import software.coley.recaf.services.transform.TransformationFeedback;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.ClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Applies an explicit allowlist of Recaf JVM transformers to the primary resource.
 */
public final class TransformWorkspaceTool implements McpTool {
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 200;
    private static final int MAX_PASSES = 3;
    private static final int MAX_FAILURE_DETAILS = 100;
    private static final int MAX_ERROR_LENGTH = 500;
    private static final Set<String> ARGUMENTS = Set.of(
            "transformers",
            "class_prefix",
            "max_passes",
            "dry_run",
            "offset",
            "limit"
    );
    private static final Map<String, Class<? extends JvmClassTransformer>> ALLOWED = allowed();
    private static final List<String> SAFE_DEFAULTS = List.of(
            "duplicate_annotations",
            "illegal_annotations",
            "illegal_signatures",
            "illegal_varargs"
    );
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;
    private final TransformOperation transformOperation;

    public TransformWorkspaceTool(WorkspaceManager workspaceManager,
                                  TransformationApplierService applierService) {
        this(
                workspaceManager::hasCurrentWorkspace,
                workspaceManager::getCurrent,
                (workspace, request) -> transform(workspace, request, applierService)
        );
    }

    TransformWorkspaceTool(BooleanSupplier hasWorkspace,
                           Supplier<Workspace> workspaceSupplier,
                           TransformOperation transformOperation) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
        this.transformOperation = Objects.requireNonNull(transformOperation, "transformOperation");
    }

    @Override
    public String name() {
        return "transform_workspace";
    }

    @Override
    public String description() {
        return "Apply allowlisted Recaf JVM transformers with conservative defaults.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        TransformRequest request = parseRequest(arguments);
        if (!hasWorkspace.getAsBoolean()) {
            return error("workspace not open", request, null);
        }

        TransformSummary summary;
        try {
            summary = transformOperation.run(workspaceSupplier.get(), request);
        } catch (Exception ex) {
            ToolCancellation.propagateIfRequested(ex);
            return error("workspace transformation failed: " + exceptionMessage(ex), request, null);
        }
        if (summary.failureCount() > 0) {
            return error(
                    "one or more transformers failed; no changes were applied",
                    request,
                    summary
            );
        }

        JsonObject structured = summaryJson(request, summary);
        return McpTool.successResult(
                request.dryRun()
                        ? "transformation dry run completed without applying changes"
                        : "workspace transformations applied",
                structured
        );
    }

    private static TransformSummary transform(Workspace workspace,
                                              TransformRequest request,
                                              TransformationApplierService service) throws Exception {
        TransformationApplier applier = service.newApplier(workspace);
        applier.setMaxPasses(request.maxPasses());
        List<Class<? extends JvmClassTransformer>> transformers = request.transformers()
                .stream()
                .map(ALLOWED::get)
                .toList();
        WorkspaceResource primary = workspace.getPrimaryResource();
        TransformationFeedback feedback = new TransformationFeedback() {
            @Override
            public boolean hasRequestedCancellation() {
                return ToolCancellation.requested();
            }

            @Override
            public boolean shouldTransform(Workspace currentWorkspace,
                                           WorkspaceResource resource,
                                           ClassBundle<?> bundle,
                                           ClassInfo classInfo,
                                           software.coley.recaf.services.transform.ClassTransformer transformer,
                                           int pass) {
                return resource == primary
                        && classInfo.isJvmClass()
                        && (request.classPrefix() == null
                        || classInfo.getName().startsWith(request.classPrefix()));
            }
        };
        JvmTransformResult result = applier.transformJvm(transformers, feedback);

        List<String> modifiedClasses = result.getTransformedClasses()
                .keySet()
                .stream()
                .map(path -> path.getValue().getName())
                .distinct()
                .sorted()
                .toList();
        List<String> removedClasses = result.getClassesToRemove()
                .stream()
                .map(path -> path.getValue().getName())
                .distinct()
                .sorted()
                .toList();

        Map<String, Integer> modifications = new LinkedHashMap<>();
        result.getModifiedClassesPerTransformer().entrySet().stream()
                .sorted(Comparator.comparing(entry -> alias(entry.getKey())))
                .forEach(entry -> modifications.put(
                        alias(entry.getKey()),
                        entry.getValue().size()
                ));

        List<TransformFailure> failures = new ArrayList<>();
        result.getTransformerFailures().entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().getValue().getName()))
                .forEach(classEntry -> classEntry.getValue().entrySet().stream()
                        .sorted(Comparator.comparing(entry -> alias(entry.getKey())))
                        .forEach(failure -> failures.add(new TransformFailure(
                                classEntry.getKey().getValue().getName(),
                                alias(failure.getKey()),
                                exceptionMessage(failure.getValue())
                        ))));

        var mappings = result.getMappingsToApply();
        MappingCounts mappingCounts = new MappingCounts(
                mappings.getClasses().size(),
                mappings.getFields().values().stream().mapToInt(Collection::size).sum(),
                mappings.getMethods().values().stream().mapToInt(Collection::size).sum(),
                mappings.getVariables().values().stream().mapToInt(Collection::size).sum()
        );
        boolean applied = !request.dryRun() && failures.isEmpty();
        if (applied) {
            ToolCancellation.check();
            result.apply();
        }
        return new TransformSummary(
                modifiedClasses,
                removedClasses,
                modifications,
                failures,
                mappingCounts,
                applied
        );
    }

    private static TransformRequest parseRequest(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        List<String> transformers = parseTransformers(arguments.get("transformers"));
        String classPrefix = ToolArguments.optionalString(arguments, "class_prefix");
        int maxPasses = ToolArguments.integer(arguments, "max_passes", 1, 1, MAX_PASSES);
        boolean dryRun = ToolArguments.bool(arguments, "dry_run", false);
        int offset = ToolArguments.integer(arguments, "offset", 0, 0, Integer.MAX_VALUE);
        int limit = ToolArguments.integer(arguments, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
        return new TransformRequest(
                transformers,
                classPrefix,
                maxPasses,
                dryRun,
                offset,
                limit
        );
    }

    private static List<String> parseTransformers(JsonElement element) {
        if (element == null) {
            return SAFE_DEFAULTS;
        }
        if (!element.isJsonArray() || element.getAsJsonArray().isEmpty()) {
            throw new IllegalArgumentException("transformers must be a nonempty array");
        }
        if (element.getAsJsonArray().size() > ALLOWED.size()) {
            throw new IllegalArgumentException("too many transformers");
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("transformers must contain strings");
            }
            String name = item.getAsString();
            if (!ALLOWED.containsKey(name)) {
                throw new IllegalArgumentException("transformer is not allowlisted: " + name);
            }
            if (!names.add(name)) {
                throw new IllegalArgumentException("transformers must not contain duplicates");
            }
        }
        return List.copyOf(names);
    }

    private static JsonObject error(String message,
                                    TransformRequest request,
                                    TransformSummary summary) {
        JsonObject structured = summary == null
                ? requestJson(request)
                : summaryJson(request, summary);
        structured.addProperty("error", message);
        return McpTool.errorResult(message, structured);
    }

    private static JsonObject summaryJson(TransformRequest request,
                                          TransformSummary summary) {
        JsonObject structured = requestJson(request);
        structured.addProperty("applied", summary.applied());
        structured.addProperty("modified_class_count", summary.modifiedClasses().size());
        structured.addProperty("removed_class_count", summary.removedClasses().size());
        structured.addProperty("failure_count", summary.failureCount());

        int start = Math.min(request.offset(), summary.modifiedClasses().size());
        int end = Math.min(start + request.limit(), summary.modifiedClasses().size());
        JsonArray classes = new JsonArray();
        summary.modifiedClasses().subList(start, end).forEach(classes::add);
        structured.add("modified_classes", classes);
        addPageMetadata(
                structured,
                request,
                summary.modifiedClasses().size(),
                end - start
        );

        JsonArray removed = new JsonArray();
        summary.removedClasses().stream().limit(MAX_LIMIT).forEach(removed::add);
        structured.add("removed_classes", removed);
        structured.addProperty(
                "removed_classes_truncated",
                summary.removedClasses().size() > MAX_LIMIT
        );

        JsonObject perTransformer = new JsonObject();
        summary.modifications().forEach(perTransformer::addProperty);
        structured.add("modified_classes_per_transformer", perTransformer);

        JsonArray failures = new JsonArray();
        summary.failures().stream().limit(MAX_FAILURE_DETAILS).forEach(failure -> {
            JsonObject json = new JsonObject();
            json.addProperty("class_name", failure.className());
            json.addProperty("transformer", failure.transformer());
            json.addProperty("message", bounded(failure.message(), MAX_ERROR_LENGTH));
            failures.add(json);
        });
        structured.add("failures", failures);
        structured.addProperty(
                "failures_truncated",
                summary.failures().size() > MAX_FAILURE_DETAILS
        );

        JsonObject mappings = new JsonObject();
        mappings.addProperty("classes", summary.mappingCounts().classes());
        mappings.addProperty("fields", summary.mappingCounts().fields());
        mappings.addProperty("methods", summary.mappingCounts().methods());
        mappings.addProperty("variables", summary.mappingCounts().variables());
        structured.add("mapping_counts", mappings);
        return structured;
    }

    private static JsonObject requestJson(TransformRequest request) {
        JsonObject json = new JsonObject();
        JsonArray transformers = new JsonArray();
        request.transformers().forEach(transformers::add);
        json.add("transformers", transformers);
        if (request.classPrefix() == null) {
            json.add("class_prefix", JsonNull.INSTANCE);
        } else {
            json.addProperty("class_prefix", request.classPrefix());
        }
        json.addProperty("max_passes", request.maxPasses());
        json.addProperty("dry_run", request.dryRun());
        json.addProperty("scope", "primary_jvm");
        return json;
    }

    private static void addPageMetadata(JsonObject target,
                                        TransformRequest request,
                                        int total,
                                        int returned) {
        target.addProperty("offset", request.offset());
        target.addProperty("limit", request.limit());
        target.addProperty("total", total);
        target.addProperty("returned", returned);
        long next = (long) request.offset() + returned;
        if (next < total) {
            target.addProperty("next_offset", next);
        } else {
            target.add("next_offset", JsonNull.INSTANCE);
        }
    }

    private static String alias(Class<? extends JvmClassTransformer> type) {
        return ALLOWED.entrySet().stream()
                .filter(entry -> entry.getValue() == type)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(type.getSimpleName());
    }

    private static Map<String, Class<? extends JvmClassTransformer>> allowed() {
        Map<String, Class<? extends JvmClassTransformer>> allowed = new LinkedHashMap<>();
        allowed.put("duplicate_annotations", DuplicateAnnotationRemovingTransformer.class);
        allowed.put("illegal_annotations", IllegalAnnotationRemovingTransformer.class);
        allowed.put("illegal_signatures", IllegalSignatureRemovingTransformer.class);
        allowed.put("illegal_varargs", IllegalVarargsRemovingTransformer.class);
        allowed.put("unknown_attributes", UnknownAttributeRemovingTransformer.class);
        allowed.put("frame_removal", FrameRemovingTransformer.class);
        allowed.put("dead_code", DeadCodeRemovingTransformer.class);
        allowed.put("goto_inlining", GotoInliningTransformer.class);
        allowed.put("redundant_try_catch", RedundantTryCatchRemovingTransformer.class);
        return Map.copyOf(allowed);
    }

    private static JsonObject createInputSchema() {
        JsonArray names = new JsonArray();
        ALLOWED.keySet().stream().sorted().forEach(names::add);
        JsonObject item = new JsonObject();
        item.addProperty("type", "string");
        item.add("enum", names);
        JsonObject transformers = new JsonObject();
        transformers.addProperty("type", "array");
        transformers.add("items", item);
        transformers.addProperty("minItems", 1);
        transformers.addProperty("maxItems", ALLOWED.size());
        transformers.addProperty("uniqueItems", true);
        transformers.addProperty(
                "description",
                "Optional ordered allowlist; omit for conservative metadata cleanup."
        );

        JsonObject properties = new JsonObject();
        properties.add("transformers", transformers);
        JsonObject prefix = new JsonObject();
        prefix.addProperty("type", "string");
        prefix.addProperty("minLength", 1);
        prefix.addProperty("description", "Optional JVM internal-name prefix scope.");
        properties.add("class_prefix", prefix);
        properties.add("max_passes", integerProperty(1, MAX_PASSES, 1));
        JsonObject dryRun = new JsonObject();
        dryRun.addProperty("type", "boolean");
        dryRun.addProperty("default", false);
        properties.add("dry_run", dryRun);
        properties.add("offset", integerProperty(0, Integer.MAX_VALUE, 0));
        properties.add("limit", integerProperty(1, MAX_LIMIT, DEFAULT_LIMIT));

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    private static JsonObject integerProperty(int minimum, int maximum, int defaultValue) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "integer");
        property.addProperty("minimum", minimum);
        property.addProperty("maximum", maximum);
        property.addProperty("default", defaultValue);
        return property;
    }

    private static String exceptionMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : bounded(message, MAX_ERROR_LENGTH);
    }

    private static String bounded(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    record TransformRequest(List<String> transformers,
                            String classPrefix,
                            int maxPasses,
                            boolean dryRun,
                            int offset,
                            int limit) {
    }

    record TransformFailure(String className, String transformer, String message) {
    }

    record MappingCounts(int classes, int fields, int methods, int variables) {
    }

    record TransformSummary(List<String> modifiedClasses,
                            List<String> removedClasses,
                            Map<String, Integer> modifications,
                            List<TransformFailure> failures,
                            MappingCounts mappingCounts,
                            boolean applied) {
        private int failureCount() {
            return failures.size();
        }
    }

    @FunctionalInterface
    interface TransformOperation {
        TransformSummary run(Workspace workspace, TransformRequest request) throws Exception;
    }
}
