package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.darknet.assembler.ast.ASTElement;
import me.darknet.assembler.error.Outcome;
import me.darknet.assembler.parser.Token;
import me.darknet.assembler.util.Location;
import me.naptila.recafmcp.mcp.util.Hashing;
import me.naptila.recafmcp.mcp.util.ToolErrors;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.path.ClassMemberPathNode;
import software.coley.recaf.path.PathNodes;
import software.coley.recaf.services.assembler.AssemblerPipelineManager;
import software.coley.recaf.services.assembler.JvmAssemblerPipeline;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayList;
import java.util.Comparator;
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
        return resolve(workspace, workspace.getPrimaryResource(), owner, name, descriptor);
    }

    static Target resolve(Workspace workspace,
                          WorkspaceResource resource,
                          String owner,
                          String name,
                          String descriptor) {
        JvmClassBundle bundle = resource.getJvmClassBundle();
        JvmClassInfo classInfo = bundle.get(owner);
        if (classInfo == null && owner != null) {
            String normalizedOwner = ClassToolSupport.normalizeClassNameInput(owner);
            if (!normalizedOwner.equals(owner)) {
                classInfo = bundle.get(normalizedOwner);
            }
        }
        if (classInfo == null) {
            return null;
        }
        MethodMember method = ClassToolSupport.findDeclaredMethod(classInfo, name, descriptor);
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
        return Hashing.sha256(value);
    }

    static String sha256(byte[] value) {
        return Hashing.sha256(value);
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

    static VerificationOutcome verifyMethodBytecode(JvmClassInfo replacement,
                                                    String methodName,
                                                    String methodDescriptor,
                                                    List<Diagnostic> assemblyDiagnostics,
                                                    boolean allowUnverifiable) {
        List<Diagnostic> verificationDiagnostics = new ArrayList<>();
        String diagnosticLevel = allowUnverifiable ? "warning" : "error";
        for (Diagnostic diag : assemblyDiagnostics) {
            if ("warning".equals(diag.level()) && isVerificationWarning(diag.message())) {
                verificationDiagnostics.add(new Diagnostic(
                        diagnosticLevel,
                        "verify",
                        diag.line(),
                        diag.column(),
                        diag.length(),
                        diag.message()
                ));
            }
        }

        try {
            org.objectweb.asm.tree.ClassNode classNode = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(replacement.getBytecode()).accept(classNode, 0);
            org.objectweb.asm.tree.MethodNode targetNode = null;
            for (org.objectweb.asm.tree.MethodNode methodNode : classNode.methods) {
                if (methodName.equals(methodNode.name) && methodDescriptor.equals(methodNode.desc)) {
                    targetNode = methodNode;
                    break;
                }
            }
            if (targetNode == null) {
                verificationDiagnostics.add(new Diagnostic(
                        diagnosticLevel,
                        "verify",
                        -1,
                        -1,
                        -1,
                        "assembled class does not contain target method node"
                ));
            } else if ((targetNode.access & (org.objectweb.asm.Opcodes.ACC_ABSTRACT
                    | org.objectweb.asm.Opcodes.ACC_NATIVE)) == 0) {
                org.objectweb.asm.tree.analysis.Analyzer<org.objectweb.asm.tree.analysis.BasicValue> analyzer =
                        new org.objectweb.asm.tree.analysis.Analyzer<>(
                                new org.objectweb.asm.tree.analysis.BasicVerifier()
                        );
                try {
                    analyzer.analyze(replacement.getName(), targetNode);
                } catch (org.objectweb.asm.tree.analysis.AnalyzerException ex) {
                    int insnIndex = ex.node == null ? -1 : targetNode.instructions.indexOf(ex.node);
                    String detail = insnIndex >= 0
                            ? "JVM bytecode verification failed at instruction " + insnIndex + ": " + ex.getMessage()
                            : "JVM bytecode verification failed: " + ex.getMessage();
                    verificationDiagnostics.add(new Diagnostic(
                            diagnosticLevel,
                            "verify",
                            -1,
                            -1,
                            -1,
                            bounded(detail, MAX_DIAGNOSTIC_MESSAGE)
                    ));
                }
            }
        } catch (RuntimeException ex) {
            verificationDiagnostics.add(new Diagnostic(
                    diagnosticLevel,
                    "verify",
                    -1,
                    -1,
                    -1,
                    bounded("JVM bytecode verification failed: " + exceptionMessage(ex), MAX_DIAGNOSTIC_MESSAGE)
            ));
        }
        return new VerificationOutcome(verificationDiagnostics.isEmpty(), List.copyOf(verificationDiagnostics));
    }

    private static boolean isVerificationWarning(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        return message.contains("may fail JVM verification")
                || message.startsWith("Cannot return ")
                || message.startsWith("Cannot assign ")
                || message.startsWith("Cannot invoke ")
                || message.startsWith("Expected type ")
                || message.contains("Stack underflow")
                || message.contains("Incompatible stack");
    }

    record VerificationOutcome(boolean passed, List<Diagnostic> diagnostics) {
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
        return ToolErrors.message(throwable, MAX_DIAGNOSTIC_MESSAGE);
    }

    static String bounded(String value, int maximum) {
        return ToolErrors.bounded(value, maximum);
    }

    static void addDiagnostics(JsonObject structured, List<Diagnostic> values) {
        List<Diagnostic> deduplicated = deduplicateDiagnostics(values);
        JsonArray diagnostics = new JsonArray();
        deduplicated.stream().limit(MAX_DIAGNOSTICS).forEach(value -> {
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
        structured.addProperty("diagnostic_count", deduplicated.size());
        structured.addProperty(
                "diagnostics_truncated",
                deduplicated.size() > MAX_DIAGNOSTICS
        );
    }

    private static List<Diagnostic> deduplicateDiagnostics(List<Diagnostic> values) {
        if (values.size() <= 1) {
            return values;
        }
        record Key(int line, int column, String message) {
        }
        java.util.Map<Key, Diagnostic> byKey = new java.util.LinkedHashMap<>();
        for (Diagnostic item : values) {
            Key key = new Key(item.line(), item.column(), item.message());
            Diagnostic existing = byKey.get(key);
            if (existing == null || severityRank(item) > severityRank(existing)) {
                byKey.put(key, item);
            }
        }
        return List.copyOf(byKey.values());
    }

    private static int severityRank(Diagnostic diagnostic) {
        int rank = "error".equals(diagnostic.level()) ? 10 : 1;
        if ("verify".equals(diagnostic.stage())) {
            rank += 2;
        }
        return rank;
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
                Outcome<String> result = pipeline.disassemble(target.path());
                List<Diagnostic> diagnostics = diagnostics("disassemble", result);
                return new OperationResult<>(
                        result.isSuccess() ? result.value() : null,
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
                Outcome<List<Token>> tokenized = pipeline.tokenize(assembly, "<mcp>");
                diagnostics.addAll(diagnostics("tokenize", tokenized));
                if (!tokenized.isSuccess()) {
                    return new OperationResult<>(null, diagnostics);
                }

                List<Token> normalizedTokens = JasmEscapeSupport.normalizeIdentifierTokens(
                        tokenized.requireValue()
                );
                Outcome<List<ASTElement>> parsed = pipeline.fullParse(normalizedTokens);
                diagnostics.addAll(diagnostics("parse", parsed));
                if (!parsed.isSuccess()) {
                    return new OperationResult<>(null, diagnostics);
                }

                Outcome<JvmClassInfo> assembled = pipeline.assembleAndWrap(
                        parsed.requireValue(),
                        target.path()
                );
                diagnostics.addAll(diagnostics("assemble", assembled));
                return new OperationResult<>(
                        assembled.isSuccess() ? assembled.value() : null,
                        diagnostics
                );
            } finally {
                pipeline.close();
            }
        }

        static List<Diagnostic> diagnostics(String stage, Outcome<?> result) {
            List<Diagnostic> diagnostics = new ArrayList<>();
            result.errors().forEach(error -> diagnostics.add(diagnostic(
                    "error",
                    stage,
                    error
            )));
            result.warnings().forEach(warn -> diagnostics.add(diagnostic(
                    "warning",
                    stage,
                    warn
            )));
            return diagnostics;
        }

        private static Diagnostic diagnostic(
                String level,
                String stage,
                me.darknet.assembler.error.Diagnostic error) {
            Location location = error.location();
            return new Diagnostic(
                    level,
                    stage,
                    location == null ? -1 : location.line(),
                    location == null ? -1 : location.column(),
                    location == null ? -1 : location.length(),
                    bounded(error.message(), MAX_DIAGNOSTIC_MESSAGE)
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
