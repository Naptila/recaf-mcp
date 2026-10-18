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
import software.coley.recaf.services.deobfuscation.transform.generic.CallResultInliningTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.DuplicateCatchMergingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.InvokeDynamicInliningTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.LongAnnotationRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.LongExceptionRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.OpaqueConstantFoldingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.OpaquePredicateFoldingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.StaticValueInliningTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.VariableFoldingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.VariableTableNormalizingTransformer;
import software.coley.recaf.services.transform.JvmClassTransformer;
import software.coley.recaf.services.transform.JvmTransformResult;
import software.coley.recaf.services.transform.TransformationApplier;
import software.coley.recaf.services.transform.TransformationApplierService;
import software.coley.recaf.services.transform.TransformationFeedback;
import software.coley.recaf.services.transform.TransformationPlan;
import software.coley.recaf.services.transform.TransformationPhase;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.ClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayList;
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
    static final int MAX_PASSES = 3;
    private static final int MAX_FAILURE_DETAILS = 100;
    private static final int MAX_ERROR_LENGTH = 500;
    private static final Set<String> ARGUMENTS = Set.of(
            "transformers",
            "class_prefix",
            "max_passes",
            "dry_run",
            "offset",
            "limit", "options", "phases"
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
        Workspace workspace = workspaceSupplier.get();
        if (workspace.getPrimaryResource().getJvmClassBundle().isEmpty()
                && !workspace.getPrimaryResource().getAndroidClassBundles().isEmpty()) {
            return error("transform_workspace supports JVM classes; Android/DEX transformations are not supported",
                    request, null);
        }

        TransformSummary summary;
        try {
            summary = transformOperation.run(workspace, request);
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
        try (var snapshot = new TransformationWorkspaceSupport(workspace)) {
            Workspace detached = snapshot.workspace();
            TransformationApplier applier = service.newApplier(detached);
            applier.setDropFaultyClasses(false);
            TransformationPlan plan = new TransformationPlan(request.phases().stream().map(phase ->
                    new TransformationPhase(phase.id(), phase.maxPasses(),
                            phase.transformers().stream().map(ALLOWED::get).toList(), phase.parameters())).toList());
            TransformationFeedback feedback = new TransformationFeedback() {
                @Override
                public boolean hasRequestedCancellation() {
                    return ToolCancellation.requested();
                }

                @Override
                public boolean shouldTransform(Workspace currentWorkspace, WorkspaceResource resource,
                                               ClassBundle<?> bundle, ClassInfo info,
                                               software.coley.recaf.services.transform.ClassTransformer transformer, int pass) {
                    return resource == detached.getPrimaryResource() && snapshot.isPrimaryBundle(bundle)
                            && info.isJvmClass() && (request.classPrefix() == null
                            || info.getName().startsWith(request.classPrefix()));
                }
            };
            var result = applier.transformJvm(plan, feedback);
            ToolCancellation.check();
            Map<String, Set<String>> modifiedByTransformer = new java.util.TreeMap<>();
            Set<String> plannedModified = new java.util.TreeSet<>();
            Set<String> plannedRemoved = new java.util.TreeSet<>();
            List<TransformFailure> failures = new ArrayList<>();
            List<PhaseSummary> phases = new ArrayList<>();
            for (var phase : result.phaseResults()) {
                JvmTransformResult output = phase.result();
                int modifiedCount = 0;
                int removedCount = 0;
                if (output != null) {
                    output.getTransformedClasses().keySet().forEach(path -> plannedModified.add(path.getValue().getName()));
                    output.getClassesToRemove().forEach(path -> plannedRemoved.add(path.getValue().getName()));
                    modifiedCount = output.getTransformedClasses().size();
                    removedCount = output.getClassesToRemove().size();
                    output.getModifiedClassesPerTransformer().forEach((type, paths) -> {
                        Set<String> names = modifiedByTransformer.computeIfAbsent(alias(type), ignored -> new java.util.TreeSet<>());
                        paths.forEach(path -> names.add(path.getValue().getName()));
                    });
                    output.getTransformerFailures().forEach((path, errors) -> errors.forEach((type, failure) ->
                            failures.add(new TransformFailure(path.getValue().getName(), alias(type), exceptionMessage(failure)))));
                    if (!output.getMappingsToApply().isEmpty()) {
                        throw new IllegalStateException("allowlisted transformation unexpectedly produced mappings");
                    }
                }
                if (!phase.status().isSuccess() && !phase.status().name().equals("SKIPPED")
                        && (output == null || output.getTransformerFailures().isEmpty())) {
                    failures.add(new TransformFailure(null, "phase:" + phase.phase().id(), phase.status().name()));
                }
                phases.add(new PhaseSummary(phase.phase().id(), phase.status().name().toLowerCase(java.util.Locale.ROOT),
                        phase.passes(), modifiedCount, removedCount));
            }
            failures.sort(Comparator.comparing(TransformFailure::className, Comparator.nullsFirst(String::compareTo))
                    .thenComparing(TransformFailure::transformer));
            Map<String, Integer> modifications = new LinkedHashMap<>();
            modifiedByTransformer.forEach((alias, names) -> modifications.put(alias, names.size()));
            boolean complete = result.isComplete() && failures.isEmpty();
            List<String> modified = List.copyOf(plannedModified);
            List<String> removed = List.copyOf(plannedRemoved);
            if (complete) {
                // Only the final phase remains unapplied; earlier phases modified the isolated snapshot.
                result.apply();
                modified = snapshot.modifiedClasses(request.classPrefix());
                removed = snapshot.removedClasses(request.classPrefix());
                if (!request.dryRun()) {
                    snapshot.commit(request.classPrefix());
                }
            }
            return new TransformSummary(modified, removed, modifications, failures,
                    new MappingCounts(0, 0, 0, 0), complete && !request.dryRun(), phases);
        }
    }

    private static TransformRequest parseRequest(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        List<TransformationPlanSupport.Phase> phases = TransformationPlanSupport.parse(arguments);
        List<String> transformers = phases.stream().flatMap(phase -> phase.transformers().stream()).distinct().toList();
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
                limit,
                phases
        );
    }

    static List<String> parseTransformers(JsonElement element) {
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
        structured.addProperty("applied", false);
        if (summary == null) {
            structured.add("phase_results", new JsonArray());
        }
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
        JsonArray phaseResults = new JsonArray();
        summary.phases().forEach(phase -> {
            JsonObject json = new JsonObject();
            json.addProperty("id", phase.id());
            json.addProperty("status", phase.status());
            json.addProperty("passes", phase.passes());
            json.addProperty("modified_class_count", phase.modifiedClasses());
            json.addProperty("removed_class_count", phase.removedClasses());
            phaseResults.add(json);
        });
        structured.add("phase_results", phaseResults);

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
        json.add("phases", TransformationPlanSupport.toJson(request.phases()));
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
        allowed.put("long_annotations", LongAnnotationRemovingTransformer.class);
        allowed.put("long_exceptions", LongExceptionRemovingTransformer.class);
        allowed.put("duplicate_catches", DuplicateCatchMergingTransformer.class);
        allowed.put("variable_table_normalization", VariableTableNormalizingTransformer.class);
        allowed.put("opaque_constants", OpaqueConstantFoldingTransformer.class);
        allowed.put("opaque_predicates", OpaquePredicateFoldingTransformer.class);
        allowed.put("variable_folding", VariableFoldingTransformer.class);
        allowed.put("static_value_inlining", StaticValueInliningTransformer.class);
        allowed.put("call_result_inlining", CallResultInliningTransformer.class);
        allowed.put("invokedynamic_inlining", InvokeDynamicInliningTransformer.class);
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
        properties.add("options", TransformationPlanSupport.optionsSchema());
        properties.add("phases", TransformationPlanSupport.phasesSchema(
                transformers, properties.getAsJsonObject("max_passes")));
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
                            int limit,
                            List<TransformationPlanSupport.Phase> phases) {
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
                            boolean applied,
                            List<PhaseSummary> phases) {
        TransformSummary(List<String> modifiedClasses, List<String> removedClasses,
                         Map<String, Integer> modifications, List<TransformFailure> failures,
                         MappingCounts mappingCounts, boolean applied) {
            this(modifiedClasses, removedClasses, modifications, failures, mappingCounts, applied, List.of());
        }
        private int failureCount() {
            return failures.size();
        }
    }

    record PhaseSummary(String id, String status, int passes, int modifiedClasses, int removedClasses) {}

    @FunctionalInterface
    interface TransformOperation {
        TransformSummary run(Workspace workspace, TransformRequest request) throws Exception;
    }
}
