package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.darknet.assembler.ast.ASTElement;
import me.darknet.assembler.error.Result;
import me.darknet.assembler.parser.Token;
import me.darknet.assembler.util.Location;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.path.ClassMemberPathNode;
import software.coley.recaf.path.PathNodes;
import software.coley.recaf.services.assembler.AssemblerPipelineManager;
import software.coley.recaf.services.assembler.JvmAssemblerPipeline;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Shared exact-target resolution and Recaf assembler operations.
 */
final class MethodAssemblySupport {
    static final int MAX_ASSEMBLY_LENGTH = 1_000_000;
    static final int MAX_CLASS_BYTES = 32 * 1024 * 1024;
    static final int MAX_DIAGNOSTICS = 100;
    static final int MAX_DIAGNOSTIC_MESSAGE = 1_000;

    private MethodAssemblySupport() {
    }

    static Target resolve(Workspace workspace,
                          String owner,
                          String name,
                          String descriptor) {
        WorkspaceResource resource = workspace.getPrimaryResource();
        JvmClassBundle bundle = resource.getJvmClassBundle();
        JvmClassInfo classInfo = bundle.get(owner);
        if (classInfo == null) {
            return null;
        }
        MethodMember method = classInfo.getDeclaredMethod(name, descriptor);
        if (method == null) {
            return null;
        }
        ClassMemberPathNode path = PathNodes.memberPath(
                workspace,
                resource,
                bundle,
                classInfo,
                method
        );
        return new Target(bundle, classInfo, method, path);
    }

    static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value)
            );
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    static String validateReplacement(Target target, JvmClassInfo replacement) {
        JvmClassInfo existing = target.classInfo();
        if (!existing.getName().equals(replacement.getName())) {
            return "assembled class name changed";
        }
        if (existing.getVersion() != replacement.getVersion()) {
            return "assembled classfile version changed";
        }
        if (existing.getAccess() != replacement.getAccess()) {
            return "assembled class access flags changed";
        }
        if (!topology(existing).equals(topology(replacement))) {
            return "assembled field or method declarations changed";
        }
        MethodMember patchedMethod = replacement.getDeclaredMethod(
                target.method().getName(),
                target.method().getDescriptor()
        );
        if (patchedMethod == null) {
            return "assembled output no longer contains the requested method";
        }
        if (target.method().getAccess() != patchedMethod.getAccess()) {
            return "assembled target method access flags changed";
        }
        if (!Objects.equals(target.method().getSignature(), patchedMethod.getSignature())
                || !target.method().getThrownTypes().equals(patchedMethod.getThrownTypes())
                || !target.method().getAnnotations().equals(patchedMethod.getAnnotations())
                || !target.method().getTypeAnnotations().equals(
                        patchedMethod.getTypeAnnotations()
                )
                || !Objects.equals(
                        target.method().getAnnotationDefault(),
                        patchedMethod.getAnnotationDefault()
                )) {
            return "assembled target method non-code metadata changed";
        }
        if (replacement.getBytecode().length > MAX_CLASS_BYTES) {
            return "assembled class exceeds the byte limit";
        }
        return null;
    }

    private static List<MemberIdentity> topology(JvmClassInfo classInfo) {
        return classInfo.fieldAndMethodStream()
                .map(member -> new MemberIdentity(
                        member.isMethod() ? "method" : "field",
                        member.getName(),
                        member.getDescriptor()
                ))
                .sorted(Comparator.comparing(MemberIdentity::kind)
                        .thenComparing(MemberIdentity::name)
                        .thenComparing(MemberIdentity::descriptor))
                .toList();
    }

    static String exceptionMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : bounded(message, MAX_DIAGNOSTIC_MESSAGE);
    }

    static String bounded(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    static void addDiagnostics(JsonObject structured, List<Diagnostic> values) {
        JsonArray diagnostics = new JsonArray();
        values.stream().limit(MAX_DIAGNOSTICS).forEach(value -> {
            JsonObject diagnostic = new JsonObject();
            diagnostic.addProperty("level", value.level());
            diagnostic.addProperty("stage", value.stage());
            diagnostic.addProperty("line", value.line());
            diagnostic.addProperty("column", value.column());
            diagnostic.addProperty("length", value.length());
            diagnostic.addProperty("message", value.message());
            diagnostics.add(diagnostic);
        });
        structured.add("diagnostics", diagnostics);
        structured.addProperty("diagnostic_count", values.size());
        structured.addProperty(
                "diagnostics_truncated",
                values.size() > MAX_DIAGNOSTICS
        );
    }

    static final class RecafMethodAssembler implements MethodAssembler {
        private final AssemblerPipelineManager pipelineManager;

        RecafMethodAssembler(AssemblerPipelineManager pipelineManager) {
            this.pipelineManager = Objects.requireNonNull(
                    pipelineManager,
                    "pipelineManager"
            );
        }

        @Override
        public OperationResult<String> disassemble(Workspace workspace, Target target) {
            JvmAssemblerPipeline pipeline = pipelineManager.newJvmAssemblerPipeline(workspace);
            try {
                Result<String> result = pipeline.disassemble(target.path());
                List<Diagnostic> diagnostics = diagnostics("disassemble", result);
                return new OperationResult<>(
                        result.hasErr() || !result.hasValue() ? null : result.get(),
                        diagnostics
                );
            } finally {
                pipeline.close();
            }
        }

        @Override
        public OperationResult<JvmClassInfo> assemble(Workspace workspace,
                                                      Target target,
                                                      String assembly) {
            JvmAssemblerPipeline pipeline = pipelineManager.newJvmAssemblerPipeline(workspace);
            try {
                List<Diagnostic> diagnostics = new ArrayList<>();
                Result<List<Token>> tokenized = pipeline.tokenize(assembly, "<mcp>");
                diagnostics.addAll(diagnostics("tokenize", tokenized));
                if (tokenized.hasErr() || !tokenized.hasValue()) {
                    return new OperationResult<>(null, diagnostics);
                }

                Result<List<ASTElement>> parsed = pipeline.fullParse(tokenized.get());
                diagnostics.addAll(diagnostics("parse", parsed));
                if (parsed.hasErr() || !parsed.hasValue()) {
                    return new OperationResult<>(null, diagnostics);
                }

                Result<JvmClassInfo> assembled = pipeline.assembleAndWrap(
                        parsed.get(),
                        target.path()
                );
                diagnostics.addAll(diagnostics("assemble", assembled));
                return new OperationResult<>(
                        assembled.hasErr() || !assembled.hasValue()
                                ? null
                                : assembled.get(),
                        diagnostics
                );
            } finally {
                pipeline.close();
            }
        }

        private static List<Diagnostic> diagnostics(String stage, Result<?> result) {
            List<Diagnostic> diagnostics = new ArrayList<>();
            result.errors().forEach(error -> diagnostics.add(diagnostic(
                    "error",
                    stage,
                    error
            )));
            result.getWarns().forEach(warn -> diagnostics.add(diagnostic(
                    "warning",
                    stage,
                    warn
            )));
            return diagnostics;
        }

        private static Diagnostic diagnostic(
                String level,
                String stage,
                me.darknet.assembler.error.Error error) {
            Location location = error.getLocation();
            return new Diagnostic(
                    level,
                    stage,
                    location == null ? -1 : location.line(),
                    location == null ? -1 : location.column(),
                    location == null ? -1 : location.length(),
                    bounded(error.getMessage(), MAX_DIAGNOSTIC_MESSAGE)
            );
        }
    }

    record Target(JvmClassBundle bundle,
                  JvmClassInfo classInfo,
                  MethodMember method,
                  ClassMemberPathNode path) {
    }

    record Diagnostic(String level,
                      String stage,
                      int line,
                      int column,
                      int length,
                      String message) {
    }

    record OperationResult<T>(T value, List<Diagnostic> diagnostics) {
        OperationResult {
            diagnostics = List.copyOf(diagnostics);
        }

        boolean success() {
            return value != null && diagnostics.stream()
                    .noneMatch(diagnostic -> diagnostic.level().equals("error"));
        }
    }

    record MemberIdentity(String kind, String name, String descriptor) {
    }

    interface MethodAssembler {
        OperationResult<String> disassemble(Workspace workspace, Target target) throws Exception;

        OperationResult<JvmClassInfo> assemble(Workspace workspace,
                                               Target target,
                                               String assembly) throws Exception;
    }
}
