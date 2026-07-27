package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.services.compile.CompilerDiagnostic;
import software.coley.recaf.services.compile.CompilerResult;
import software.coley.recaf.services.compile.JavacArguments;
import software.coley.recaf.services.compile.JavacArgumentsBuilder;
import software.coley.recaf.services.compile.JavacCompiler;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Compiles complete Java source against the workspace and atomically replaces one class family.
 */
public final class PatchClassTool implements McpTool {
    private static final int MAX_SOURCE_LENGTH = 750_000;
    private static final int MAX_COMPILED_CLASSES = 100;
    private static final int MAX_COMPILED_BYTES = 32 * 1024 * 1024;
    private static final int MAX_DIAGNOSTICS = 100;
    private static final int MAX_DIAGNOSTIC_MESSAGE = 1_000;
    private static final String PRESERVE_VERSION = "preserve";
    private static final String MINIMUM_SUPPORTED_VERSION = "minimum_supported";
    private static final Set<String> VERSION_POLICIES = Set.of(
            PRESERVE_VERSION,
            MINIMUM_SUPPORTED_VERSION
    );
    private static final Set<String> ARGUMENTS = Set.of(
            "class_name",
            "source",
            "target_version_policy"
    );
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;
    private final CompileOperation compileOperation;

    public PatchClassTool(WorkspaceManager workspaceManager, JavacCompiler compiler) {
        this(
                workspaceManager::hasCurrentWorkspace,
                workspaceManager::getCurrent,
                (workspace, classInfo, source, targetVersion) -> {
                    if (!JavacCompiler.isAvailable()) {
                        return new Compilation(
                                false,
                                Map.of(),
                                List.of(),
                                "the current Java runtime has no javac compiler"
                        );
                    }
                    JavacArguments arguments = new JavacArgumentsBuilder()
                            .withClassName(classInfo.getName())
                            .withClassSource(source)
                            .withVersionTarget(targetVersion)
                            .withDebugVariables(true)
                            .withDebugLineNumbers(true)
                            .withDebugSourceName(true)
                            .build();
                    CompilerResult result = compiler.compile(arguments, workspace, null);
                    List<Diagnostic> diagnostics = result.getDiagnostics().stream()
                            .map(PatchClassTool::diagnostic)
                            .toList();
                    String error = result.getException() == null
                            ? null
                            : exceptionMessage(result.getException());
                    return new Compilation(
                            result.wasSuccess(),
                            result.getCompilations() == null
                                    ? Map.of()
                                    : new LinkedHashMap<>(result.getCompilations()),
                            diagnostics,
                            error
                    );
                }
        );
    }

    PatchClassTool(BooleanSupplier hasWorkspace,
                   Supplier<Workspace> workspaceSupplier,
                   CompileOperation compileOperation) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
        this.compileOperation = Objects.requireNonNull(compileOperation, "compileOperation");
    }

    @Override
    public String name() {
        return "patch_class";
    }

    @Override
    public String description() {
        return "Compile complete Java source and replace one primary JVM class family.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        String className = ToolArguments.requiredString(arguments, "class_name");
        String source = ToolArguments.requiredString(arguments, "source");
        String versionPolicy = ToolArguments.optionalString(arguments, "target_version_policy");
        if (versionPolicy == null) {
            versionPolicy = PRESERVE_VERSION;
        } else if (!VERSION_POLICIES.contains(versionPolicy)) {
            throw new IllegalArgumentException(
                    "target_version_policy must be preserve or minimum_supported"
            );
        }
        if (!isValidInternalClassName(className)) {
            throw new IllegalArgumentException("class_name must be a valid JVM internal name");
        }
        if (source.length() > MAX_SOURCE_LENGTH) {
            throw new IllegalArgumentException("source exceeds the maximum length");
        }
        if (!hasWorkspace.getAsBoolean()) {
            return errorWithoutVersion("workspace not open", className);
        }

        Workspace workspace = workspaceSupplier.get();
        JvmClassBundle bundle = workspace.getPrimaryResource().getJvmClassBundle();
        JvmClassInfo existing = bundle.get(className);
        if (existing == null) {
            return errorWithoutVersion("class not found in primary JVM resource", className);
        }
        int inputClassfileVersion = existing.getVersion();
        int inputTargetVersion = inputClassfileVersion - 44;
        int compilerMinimum = JavacCompiler.getMinTargetVersion();
        int compilerMaximum = Runtime.version().feature();
        int targetVersion = inputTargetVersion;
        boolean upgraded = false;
        if (inputTargetVersion < compilerMinimum
                && MINIMUM_SUPPORTED_VERSION.equals(versionPolicy)) {
            targetVersion = compilerMinimum;
            upgraded = true;
        }
        VersionPlan versionPlan = new VersionPlan(
                inputClassfileVersion,
                inputTargetVersion,
                targetVersion,
                versionPolicy,
                upgraded,
                compilerMinimum,
                compilerMaximum
        );
        if (inputTargetVersion < compilerMinimum && !upgraded) {
            return error(
                    "existing classfile version " + inputClassfileVersion
                            + " (Java " + inputTargetVersion + ") is below the current javac "
                            + "minimum " + (compilerMinimum + 44) + " (Java "
                            + compilerMinimum + "); retry with target_version_policy="
                            + "\"minimum_supported\" to upgrade the emitted bytecode explicitly",
                    className,
                    versionPlan,
                    null
            );
        }
        if (targetVersion > compilerMaximum) {
            return error(
                    "existing classfile version " + inputClassfileVersion
                            + " (Java " + inputTargetVersion + ") is newer than the current javac "
                            + "maximum " + (compilerMaximum + 44) + " (Java "
                            + compilerMaximum + "); run Recaf with a newer JDK",
                    className,
                    versionPlan,
                    null
            );
        }

        Compilation compilation;
        try {
            compilation = compileOperation.compile(
                    workspace,
                    existing,
                    source,
                    targetVersion
            );
        } catch (Exception ex) {
            ToolCancellation.propagateIfRequested(ex);
            return error(
                    "compilation failed: " + exceptionMessage(ex),
                    className,
                    versionPlan,
                    null
            );
        }
        if (!compilation.success()) {
            String message = compilation.error() == null
                    ? "Java compilation failed"
                    : "Java compilation failed: " + compilation.error();
            return error(message, className, versionPlan, compilation);
        }

        Map<String, byte[]> outputs = compilation.outputs();
        String outputError = validateOutputs(className, outputs);
        if (outputError != null) {
            return error(outputError, className, versionPlan, compilation);
        }

        List<JvmClassInfo> replacements = new ArrayList<>();
        long compiledBytes = 0;
        try {
            for (Map.Entry<String, byte[]> entry : outputs.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .toList()) {
                ToolCancellation.check();
                compiledBytes += entry.getValue().length;
                if (compiledBytes > MAX_COMPILED_BYTES) {
                    return error(
                            "compiled output exceeds the byte limit",
                            className,
                            versionPlan,
                            compilation
                    );
                }
                JvmClassInfo replacement = new JvmClassInfoBuilder(entry.getValue()).build();
                if (!entry.getKey().equals(replacement.getName())) {
                    return error(
                            "compiler output name does not match its bytecode",
                            className,
                            versionPlan,
                            compilation
                    );
                }
                if (replacement.getVersion() != versionPlan.targetClassfileVersion()) {
                    return error(
                            "compiler output classfile version " + replacement.getVersion()
                                    + " does not match requested version "
                                    + versionPlan.targetClassfileVersion(),
                            className,
                            versionPlan,
                            compilation
                    );
                }
                replacements.add(replacement);
            }
        } catch (RuntimeException ex) {
            ToolCancellation.propagateIfRequested(ex);
            return error(
                    "compiled output is not valid JVM bytecode: " + exceptionMessage(ex),
                    className,
                    versionPlan,
                    compilation
            );
        }

        String originalHash = sha256(existing.getBytecode());
        ToolCancellation.check();
        for (JvmClassInfo replacement : replacements) {
            bundle.put(replacement);
        }
        JvmClassInfo patched = bundle.get(className);

        JsonObject structured = metadata(className, versionPlan, compilation);
        JsonArray updated = new JsonArray();
        replacements.stream().map(JvmClassInfo::getName).sorted().forEach(updated::add);
        structured.add("updated_classes", updated);
        structured.addProperty("updated_class_count", replacements.size());
        structured.addProperty("generated_companion_count", replacements.size() - 1);
        structured.addProperty("compiled_size_bytes", compiledBytes);
        structured.addProperty("original_sha256", originalHash);
        structured.addProperty("patched_sha256", sha256(patched.getBytecode()));
        structured.addProperty("scope", "primary_jvm");
        return McpTool.successResult(
                "compiled and replaced " + replacements.size() + " JVM classes",
                structured
        );
    }

    private static String validateOutputs(String className, Map<String, byte[]> outputs) {
        if (outputs.isEmpty() || !outputs.containsKey(className)) {
            return "compiler did not emit the requested primary class";
        }
        if (outputs.size() > MAX_COMPILED_CLASSES) {
            return "compiler emitted too many classes";
        }
        String companionPrefix = className + "$";
        for (Map.Entry<String, byte[]> output : outputs.entrySet()) {
            if (!output.getKey().equals(className)
                    && !output.getKey().startsWith(companionPrefix)) {
                return "source emitted an unrelated top-level class: " + output.getKey();
            }
            if (output.getValue() == null || output.getValue().length == 0) {
                return "compiler emitted empty bytecode";
            }
        }
        return null;
    }

    private static JsonObject errorWithoutVersion(String message, String className) {
        return error(message, className, null, null);
    }

    private static JsonObject error(String message,
                                    String className,
                                    VersionPlan versionPlan,
                                    Compilation compilation) {
        JsonObject structured = metadata(className, versionPlan, compilation);
        structured.addProperty("error", message);
        return McpTool.errorResult(message, structured);
    }

    private static JsonObject metadata(String className,
                                       VersionPlan versionPlan,
                                       Compilation compilation) {
        JsonObject structured = new JsonObject();
        structured.addProperty("class_name", className);
        if (versionPlan != null) {
            structured.addProperty("input_classfile_version", versionPlan.inputClassfileVersion());
            structured.addProperty("input_target_version", versionPlan.inputTargetVersion());
            structured.addProperty("target_classfile_version", versionPlan.targetClassfileVersion());
            structured.addProperty("target_version", versionPlan.targetVersion());
            structured.addProperty("target_version_policy", versionPlan.policy());
            structured.addProperty("classfile_version_upgraded", versionPlan.upgraded());
            structured.addProperty("compiler_min_target_version", versionPlan.compilerMinimum());
            structured.addProperty("compiler_max_target_version", versionPlan.compilerMaximum());
        }
        JsonArray diagnostics = new JsonArray();
        if (compilation != null) {
            compilation.diagnostics().stream().limit(MAX_DIAGNOSTICS).forEach(item -> {
                JsonObject json = new JsonObject();
                json.addProperty("level", item.level());
                json.addProperty("line", item.line());
                json.addProperty("column", item.column());
                json.addProperty("length", item.length());
                json.addProperty("message", bounded(
                        item.message(),
                        MAX_DIAGNOSTIC_MESSAGE
                ));
                diagnostics.add(json);
            });
            structured.addProperty("diagnostic_count", compilation.diagnostics().size());
            structured.addProperty(
                    "diagnostics_truncated",
                    compilation.diagnostics().size() > MAX_DIAGNOSTICS
            );
        } else {
            structured.addProperty("diagnostic_count", 0);
            structured.addProperty("diagnostics_truncated", false);
        }
        structured.add("diagnostics", diagnostics);
        return structured;
    }

    private static Diagnostic diagnostic(CompilerDiagnostic diagnostic) {
        return new Diagnostic(
                diagnostic.level().name().toLowerCase(Locale.ROOT),
                diagnostic.line(),
                diagnostic.column(),
                diagnostic.length(),
                diagnostic.message()
        );
    }

    private static boolean isValidInternalClassName(String name) {
        if (name.startsWith("/") || name.endsWith("/")) {
            return false;
        }
        for (String component : name.split("/", -1)) {
            if (component.isEmpty()) {
                return false;
            }
            for (int index = 0; index < component.length(); index++) {
                char character = component.charAt(index);
                if (character == '.' || character == ';' || character == '['
                        || Character.isISOControl(character)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value)
            );
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static String exceptionMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : bounded(message, MAX_DIAGNOSTIC_MESSAGE);
    }

    private static String bounded(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private static JsonObject createInputSchema() {
        JsonObject className = new JsonObject();
        className.addProperty("type", "string");
        className.addProperty("minLength", 1);
        className.addProperty("description", "Exact primary JVM internal class name.");
        JsonObject source = new JsonObject();
        source.addProperty("type", "string");
        source.addProperty("minLength", 1);
        source.addProperty("maxLength", MAX_SOURCE_LENGTH);
        source.addProperty("description", "Complete Java source for the requested class.");
        JsonObject versionPolicy = new JsonObject();
        versionPolicy.addProperty("type", "string");
        JsonArray versionPolicyValues = new JsonArray();
        versionPolicyValues.add(PRESERVE_VERSION);
        versionPolicyValues.add(MINIMUM_SUPPORTED_VERSION);
        versionPolicy.add("enum", versionPolicyValues);
        versionPolicy.addProperty("default", PRESERVE_VERSION);
        versionPolicy.addProperty(
                "description",
                "Preserve the target or upgrade legacy bytecode to the javac minimum."
        );

        JsonObject properties = new JsonObject();
        properties.add("class_name", className);
        properties.add("source", source);
        properties.add("target_version_policy", versionPolicy);
        JsonArray required = new JsonArray();
        required.add("class_name");
        required.add("source");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    record Diagnostic(String level, int line, int column, int length, String message) {
    }

    record Compilation(boolean success,
                       Map<String, byte[]> outputs,
                       List<Diagnostic> diagnostics,
                       String error) {
    }

    record VersionPlan(int inputClassfileVersion,
                       int inputTargetVersion,
                       int targetVersion,
                       String policy,
                       boolean upgraded,
                       int compilerMinimum,
                       int compilerMaximum) {
        int targetClassfileVersion() {
            return targetVersion + 44;
        }
    }

    @FunctionalInterface
    interface CompileOperation {
        Compilation compile(Workspace workspace,
                            JvmClassInfo classInfo,
                            String source,
                            int targetVersion) throws Exception;
    }
}
