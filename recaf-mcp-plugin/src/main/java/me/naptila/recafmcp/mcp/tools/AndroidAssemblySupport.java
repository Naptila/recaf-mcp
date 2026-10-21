package me.naptila.recafmcp.mcp.tools;

import me.darknet.assembler.backend.dalvik.printer.DalvikClassPrinter;
import me.darknet.assembler.ast.ASTElement;
import me.darknet.assembler.ast.primitive.ASTLabel;
import me.darknet.assembler.ast.specific.ASTMethod;
import me.darknet.assembler.printer.PrintContext;
import me.darknet.dex.tree.definitions.code.Code;
import me.darknet.dex.tree.definitions.debug.DebugInformation;
import me.darknet.dex.tree.definitions.instructions.Label;
import software.coley.recaf.info.AndroidClassInfo;
import software.coley.recaf.path.PathNodes;
import software.coley.recaf.services.assembler.AssemblerPipelineManager;
import me.naptila.recafmcp.mcp.android.AndroidDexReader;
import me.naptila.recafmcp.mcp.android.AndroidDexWriter;
import software.coley.recaf.workspace.model.Workspace;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.HashMap;

/** Dalvik Jasm operations on detached DEX trees, leaving the workspace untouched. */
final class AndroidAssemblySupport {
    private AndroidAssemblySupport() {}

    static String disassemble(AndroidClassInfo info, String name, String descriptor) throws IOException {
        var detached = AndroidDexReader.readClass(AndroidDexReader.encodeClass(info, 39), info.getName());
        var method = detached.getBackingDefinition().getMethod(name, descriptor);
        if (method == null) throw new IllegalArgumentException("method not found in Android owner");
        method.setParameterNames(List.of());
        if (method.getCode() != null) {
            method.getCode().setDebugInfo(null);
            labels(method.getCode()).forEach(label -> label.lineNumber(Label.UNASSIGNED));
        }
        // Omit debug-local names and explicit parameter names. Jasm 3.1 can still
        // print positional pN/this aliases; it resolves them from the signature.
        // Debug metadata is retained separately when the method body is replaced.
        return AndroidDexWriter.withCompatibility(() -> {
            var printer = new DalvikClassPrinter(detached.getBackingDefinition()).method(name, descriptor);
            if (printer == null) {
                throw new IllegalArgumentException("method not found in Android owner");
            }
            var context = new PrintContext<>("    ");
            printer.print(context);
            return context.toString();
        });
    }

    static byte[] bytes(AndroidClassInfo info, int version) throws IOException {
        return GetClassBytecodeTool.singleClassDex(info, version);
    }

    static MethodAssemblySupport.OperationResult<AndroidClassInfo> assemble(
            AssemblerPipelineManager manager, Workspace workspace, ClassToolSupport.Entry entry,
            String name, String descriptor, String assembly) throws IOException {
        var resource = workspace.getPrimaryResource();
        var bundle = resource.getAndroidClassBundles().get(entry.dexName());
        // The upstream Dalvik compiler can work with mutable trees. Never give it a live tree.
        byte[] original = bytes(entry.classInfo().asAndroidClass(), bundle.getVersion());
        AndroidClassInfo detached = AndroidDexReader.readClass(original, entry.className());
        var method = detached.getDeclaredMethod(name, descriptor);
        if (method == null) {
            throw new IllegalArgumentException("method not found in Android owner");
        }
        var path = PathNodes.memberPath(workspace, resource, bundle, detached, method);
        var beforeMethod = detached.getBackingDefinition().getMethod(name, descriptor);
        var beforeCode = beforeMethod.getCode();
        var parameterNames = beforeMethod.getParameterNames();
        var parameterAccess = beforeMethod.getParameterAccessFlags();
        var parameterAnnotations = beforeMethod.getParameterAnnotations();
        var diagnostics = new ArrayList<MethodAssemblySupport.Diagnostic>();
        var pipeline = manager.newAndroidAssemblerPipeline(workspace);
        try {
            var tokens = pipeline.tokenize(assembly, "<mcp>");
            diagnostics.addAll(MethodAssemblySupport.RecafMethodAssembler.diagnostics("tokenize", tokens));
            if (!tokens.isSuccess()) {
                return new MethodAssemblySupport.OperationResult<>(null, diagnostics);
            }
            var parsed = pipeline.fullParse(tokens.requireValue());
            diagnostics.addAll(MethodAssemblySupport.RecafMethodAssembler.diagnostics("parse", parsed));
            if (!parsed.isSuccess()) {
                return new MethodAssemblySupport.OperationResult<>(null, diagnostics);
            }
            ASTMethod submitted = methodAst(parsed.requireValue());
            if (submitted.getCode() == null) throw new IOException("Android patch must contain a method body");
            if (!submitted.getParameters().isEmpty()
                    || submitted.getCode().getInstructions().stream().anyMatch(instruction -> instruction.content().equals("line"))) {
                throw new IOException("Android instruction patches preserve existing debug metadata; omit debug directives and parameter-name declarations");
            }
            var assembled = AndroidDexWriter.withCompatibility(
                    () -> pipeline.assembleAndWrap(parsed.requireValue(), path));
            diagnostics.addAll(MethodAssemblySupport.RecafMethodAssembler.diagnostics("assemble", assembled));
            if (assembled.isSuccess()) {
                var after = assembled.value().getBackingDefinition().getMethod(name, descriptor);
                var originalParsed = pipeline.fullParse(pipeline.tokenize(disassemble(
                        entry.classInfo().asAndroidClass(), name, descriptor), "<original>").requireValue());
                restoreDebug(beforeCode, after.getCode(),
                        labelNames(methodAst(originalParsed.requireValue())), labelNames(submitted));
                after.setParameterNames(parameterNames);
                after.setParameterAccessFlags(parameterAccess);
                after.setParameterAnnotations(parameterAnnotations);
            }
            return new MethodAssemblySupport.OperationResult<>(
                    assembled.isSuccess() ? assembled.value() : null, diagnostics);
        } finally {
            pipeline.close();
        }
    }

    private static ASTMethod methodAst(List<ASTElement> elements) throws IOException {
        var methods = elements.stream().filter(ASTMethod.class::isInstance).map(ASTMethod.class::cast).toList();
        if (methods.size() != 1) throw new IOException("Android patch must contain one method");
        return methods.getFirst();
    }

    private static List<String> labelNames(ASTMethod method) {
        return method.getCode().getInstructions().stream().filter(ASTLabel.class::isInstance)
                .map(ASTLabel.class::cast).map(ASTLabel::content).toList();
    }

    private static List<Label> labels(Code code) {
        return code.getInstructions().stream().filter(Label.class::isInstance).map(Label.class::cast).toList();
    }

    private static void restoreDebug(Code before, Code after, List<String> oldNames, List<String> newNames)
            throws IOException {
        if (before == null || after == null || before.getDebugInfo() == null) return;
        var oldLabels = labels(before);
        var newLabels = labels(after);
        if (oldLabels.size() != oldNames.size() || newLabels.size() != newNames.size()) {
            throw new IOException("cannot bind Android debug metadata to assembled labels");
        }
        var oldByIdentity = new IdentityHashMap<Label, String>();
        for (int index = 0; index < oldNames.size(); index++) oldByIdentity.put(oldLabels.get(index), oldNames.get(index));
        var newByName = new HashMap<String, Label>();
        for (int index = 0; index < newNames.size(); index++) newByName.put(newNames.get(index), newLabels.get(index));
        java.util.function.Function<Label, Label> mapped = label -> {
            var replacement = newByName.get(oldByIdentity.get(label));
            if (replacement == null) throw new IllegalArgumentException("patch removed a label used by existing debug metadata");
            return replacement;
        };
        var original = before.getDebugInfo();
        var lines = original.lineNumbers().stream().map(line -> {
            Label label = mapped.apply(line.label());
            label.lineNumber(line.line());
            return new DebugInformation.LineNumber(label, line.line());
        }).toList();
        var locals = original.locals().stream().map(local -> {
            int register = local.register();
            int firstParameter = before.getRegisters() - before.getIn();
            if (register >= firstParameter) register += after.getRegisters() - before.getRegisters();
            if (register < 0 || register >= after.getRegisters()
                    || local.register() < firstParameter && register >= after.getRegisters() - after.getIn()) {
                throw new IllegalArgumentException("patch changes a register used by existing debug metadata");
            }
            return new DebugInformation.LocalVariable(register, local.name(), local.type(), local.signature(),
                    mapped.apply(local.start()), mapped.apply(local.end()));
        }).toList();
        after.setDebugInfo(new DebugInformation(lines, List.of(), original.parameterNames(), locals));
    }

    static String validateReplacement(AndroidClassInfo original, AndroidClassInfo replacement,
                                      String name, String descriptor, int version) throws IOException {
        if (!original.getName().equals(replacement.getName())) {
            return "assembled Android class name changed";
        }
        var originalMethod = original.getBackingDefinition().getMethod(name, descriptor);
        var replacementMethod = replacement.getBackingDefinition().getMethod(name, descriptor);
        if (originalMethod == null || replacementMethod == null) {
            return "assembled output no longer contains the requested method";
        }
        // Comparing serialized classes with the original body restored detects changes to
        // every other method, field, annotation and class/member declaration.
        var code = replacementMethod.getCode();
        try {
            replacementMethod.setCode(originalMethod.getCode());
            if (!Arrays.equals(bytes(original, version), bytes(replacement, version))) {
                return "assembled output changed declarations or content outside the target method body";
            }
        } finally {
            replacementMethod.setCode(code);
        }
        if (bytes(replacement, version).length > MethodAssemblySupport.MAX_CLASS_BYTES) {
            return "assembled Android class exceeds the byte limit";
        }
        return null;
    }
}
