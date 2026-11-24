package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.objectweb.asm.Type;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.services.inheritance.InheritanceGraph;
import software.coley.recaf.services.mapping.IntermediateMappings;
import software.coley.recaf.services.mapping.MappingResults;
import software.coley.recaf.services.mapping.Mappings;
import software.coley.recaf.services.mapping.data.ClassMapping;
import software.coley.recaf.services.mapping.data.FieldMapping;
import software.coley.recaf.services.mapping.data.MethodMapping;
import software.coley.recaf.services.mapping.data.VariableMapping;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Shared JVM/Android mapping-result classification and response metadata.
 */
final class MappingMutationSupport {
    private MappingMutationSupport() {
    }

    static void preserveOriginalClassNames(Workspace workspace, MappingResults results) {
        Map<String, String> newNameToOriginal = new LinkedHashMap<>();
        for (Map.Entry<String, String> mapped : results.getMappedClasses().entrySet()) {
            String oldName = mapped.getKey();
            String newName = mapped.getValue();
            if (oldName == null || newName == null || oldName.equals(newName)) {
                continue;
            }
            var preNode = results.getPreMappingPaths().get(oldName);
            var postNode = results.getPostMappingPaths().get(oldName);
            ClassInfo preInfo = preNode == null ? null : preNode.getValue();
            String rootOriginal = preInfo == null
                    ? null
                    : software.coley.recaf.info.properties.builtin.OriginalClassNameProperty.get(preInfo);
            if (rootOriginal == null || rootOriginal.isBlank()) {
                rootOriginal = oldName;
            }
            if (!rootOriginal.equals(newName)) {
                newNameToOriginal.put(newName, rootOriginal);
                if (postNode != null) {
                    software.coley.recaf.info.properties.builtin.OriginalClassNameProperty.set(
                            postNode.getValue(),
                            rootOriginal
                    );
                }
            }
        }
        if (workspace != null && !newNameToOriginal.isEmpty()) {
            WorkspaceResource primary = workspace.getPrimaryResource();
            for (Map.Entry<String, String> entry : newNameToOriginal.entrySet()) {
                var jvmInfo = primary.getJvmClassBundle().get(entry.getKey());
                if (jvmInfo != null
                        && software.coley.recaf.info.properties.builtin.OriginalClassNameProperty.get(jvmInfo) == null) {
                    software.coley.recaf.info.properties.builtin.OriginalClassNameProperty.set(
                            jvmInfo,
                            entry.getValue()
                    );
                }
            }
        }
    }

    static Summary summarize(Workspace workspace, MappingResults results) {
        WorkspaceResource resource = workspace.getPrimaryResource();
        List<Change> changes = results.getMappedClasses().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> {
                    var prePath = results.getPreMappingPaths().get(entry.getKey());
                    ClassInfo classInfo = prePath == null ? null : prePath.getValue();
                    ClassToolSupport.Scope scope = classInfo != null
                            && classInfo.isAndroidClass()
                            ? ClassToolSupport.Scope.ANDROID
                            : ClassToolSupport.Scope.JVM;
                    String dexName = null;
                    if (classInfo != null) {
                        ClassToolSupport.Entry identity = ClassToolSupport.identify(
                                resource,
                                classInfo
                        );
                        if (identity != null) {
                            scope = identity.scope();
                            dexName = identity.dexName();
                        }
                    }
                    return new Change(
                            entry.getKey(),
                            entry.getValue(),
                            scope,
                            dexName
                    );
                })
                .toList();
        return new Summary(changes);
    }

    static boolean hasAndroidClasses(Workspace workspace) {
        return workspace.getPrimaryResource()
                .androidClassBundleStreamRecursive()
                .anyMatch(bundle -> !bundle.isEmpty());
    }

    static void addPersistenceMetadata(JsonObject target, Workspace workspace, boolean applied) {
        boolean supported = me.naptila.recafmcp.mcp.internal.RecafSaveWorkspaceHandler
                .supportsArchiveExport(workspace);
        target.addProperty("workspace_mutated_in_memory", applied);
        target.addProperty("workspace_archive_export_supported", supported);
        target.addProperty("save_required", applied && supported);
        target.addProperty("in_memory_only", applied && !supported);
        if (!supported) {
            target.addProperty("persistence_note", "This workspace is not a supported archive format; "
                    + "changes remain in memory only unless exported via export_mappings.");
        } else if (applied) {
            target.addProperty("persistence_note", "Changes are applied in memory; "
                    + "call recaf_save or recaf_close(save=true) to persist to disk.");
        }
    }

    static void addSummary(JsonObject target, Summary summary) {
        target.addProperty("changed_classes", summary.changes().size());
        target.addProperty("jvm_changed_classes", summary.jvmChanges());
        target.addProperty("android_changed_classes", summary.androidChanges());
        JsonArray dexBundles = new JsonArray();
        summary.dexBundles().forEach(dexBundles::add);
        target.add("dex_bundles_changed", dexBundles);
        target.addProperty("dex_bundle_count", summary.dexBundles().size());
    }

    static IntermediateMappings copyIntermediate(IntermediateMappings source) {
        IntermediateMappings copy = new IntermediateMappings();
        for (ClassMapping cls : source.getClasses().values()) {
            copy.addClass(cls.getOldName(), cls.getNewName());
        }
        for (List<FieldMapping> list : source.getFields().values()) {
            for (FieldMapping f : list) {
                copy.addField(f.getOwnerName(), f.getDesc(), f.getOldName(), f.getNewName());
            }
        }
        for (List<MethodMapping> list : source.getMethods().values()) {
            for (MethodMapping m : list) {
                copy.addMethod(m.getOwnerName(), m.getDesc(), m.getOldName(), m.getNewName());
            }
        }
        for (List<VariableMapping> list : source.getVariables().values()) {
            for (VariableMapping v : list) {
                copy.addVariable(
                        v.getOwnerName(),
                        v.getMethodName(),
                        v.getMethodDesc(),
                        v.getDesc(),
                        v.getOldName(),
                        v.getIndex(),
                        v.getNewName()
                );
            }
        }
        return copy;
    }

    static IntermediateMappings withCascadedClassRenames(Mappings baseMappings,
                                                         List<Change> changes) {
        IntermediateMappings out = copyIntermediate(baseMappings.exportIntermediate());
        for (Change change : changes) {
            if (change.renamed() && !out.getClasses().containsKey(change.oldName())) {
                out.addClass(change.oldName(), change.newName());
            }
        }
        return out;
    }

    static IntermediateMappings expandPrimaryOuterInnerAndMethodFamilies(
            Workspace workspace,
            IntermediateMappings source,
            InheritanceGraph graph) {
        IntermediateMappings out = copyIntermediate(source);
        WorkspaceResource primary = workspace.getPrimaryResource();
        List<ClassToolSupport.Entry> allPrimary = ClassToolSupport.entries(
                primary,
                new ClassToolSupport.Selection(ClassToolSupport.Scope.ALL, null)
        );
        if (!source.getClasses().isEmpty()) {
            for (ClassToolSupport.Entry entry : allPrimary) {
                String candidate = entry.className();
                if (out.getClasses().containsKey(candidate) || candidate.indexOf('$') <= 0) {
                    continue;
                }
                String bestOuter = null;
                int dollar = candidate.lastIndexOf('$');
                while (dollar > 0) {
                    String prefix = candidate.substring(0, dollar);
                    if (source.getClasses().containsKey(prefix)) {
                        bestOuter = prefix;
                        break;
                    }
                    dollar = candidate.lastIndexOf('$', dollar - 1);
                }
                if (bestOuter != null) {
                    ClassMapping outerMapping = source.getClasses().get(bestOuter);
                    if (outerMapping != null && outerMapping.getNewName() != null
                            && !outerMapping.getOldName().equals(outerMapping.getNewName())) {
                        String cascadedNew = outerMapping.getNewName()
                                + candidate.substring(bestOuter.length());
                        out.addClass(candidate, cascadedNew);
                    }
                }
            }
        }
        if (graph != null && !source.getMethods().isEmpty()) {
            for (List<MethodMapping> methodList : source.getMethods().values()) {
                for (MethodMapping method : methodList) {
                    var node = workspace.findClass(method.getOwnerName());
                    if (node == null) {
                        continue;
                    }
                    ClassInfo ownerInfo = node.getValue();
                    MethodMember declared = ownerInfo.getDeclaredMethod(
                            method.getOldName(),
                            method.getDesc()
                    );
                    if (declared == null || !MethodHierarchySupport.isInheritable(declared)) {
                        continue;
                    }
                    MethodHierarchySupport.Analysis analysis = MethodHierarchySupport.analyze(
                            workspace,
                            graph,
                            ownerInfo,
                            declared,
                            method.getNewName()
                    );
                    for (String declOwner : analysis.primaryDeclaringOwners()) {
                        boolean alreadyMapped = out.getMethods()
                                .getOrDefault(declOwner, List.of())
                                .stream()
                                .anyMatch(existing -> existing.getOldName().equals(method.getOldName())
                                        && Objects.equals(existing.getDesc(), method.getDesc()));
                        if (!alreadyMapped) {
                            out.addMethod(
                                    declOwner,
                                    method.getDesc(),
                                    method.getOldName(),
                                    method.getNewName()
                            );
                        }
                    }
                }
            }
        }
        return out;
    }

    static IntermediateMappings withIdentityOwnerClasses(IntermediateMappings source) {
        IntermediateMappings out = copyIntermediate(source);
        for (String owner : source.getFields().keySet()) {
            out.getClasses().putIfAbsent(owner, new ClassMapping(owner, owner));
        }
        for (String owner : source.getMethods().keySet()) {
            out.getClasses().putIfAbsent(owner, new ClassMapping(owner, owner));
        }
        for (String owner : source.getVariables().keySet()) {
            out.getClasses().putIfAbsent(owner, new ClassMapping(owner, owner));
        }
        return out;
    }

    static IntermediateMappings invertIntermediateMappings(IntermediateMappings source) {
        Map<String, String> origToCurrent = new LinkedHashMap<>();
        for (ClassMapping cls : source.getClasses().values()) {
            if (cls.getNewName() != null) {
                origToCurrent.put(cls.getOldName(), cls.getNewName());
            }
        }
        IntermediateMappings inverted = new IntermediateMappings();
        for (ClassMapping cls : source.getClasses().values()) {
            if (cls.getNewName() != null) {
                inverted.addClass(cls.getNewName(), cls.getOldName());
            }
        }
        for (List<FieldMapping> list : source.getFields().values()) {
            for (FieldMapping f : list) {
                String currentOwner = mapInternalType(f.getOwnerName(), origToCurrent);
                String currentDesc = f.getDesc() == null
                        ? null
                        : remapDescriptor(f.getDesc(), origToCurrent);
                inverted.addField(currentOwner, currentDesc, f.getNewName(), f.getOldName());
            }
        }
        for (List<MethodMapping> list : source.getMethods().values()) {
            for (MethodMapping m : list) {
                String currentOwner = mapInternalType(m.getOwnerName(), origToCurrent);
                String currentDesc = m.getDesc() == null
                        ? null
                        : remapMethodDescriptor(m.getDesc(), origToCurrent);
                inverted.addMethod(currentOwner, currentDesc, m.getNewName(), m.getOldName());
            }
        }
        for (List<VariableMapping> list : source.getVariables().values()) {
            for (VariableMapping v : list) {
                String currentOwner = mapInternalType(v.getOwnerName(), origToCurrent);
                String currentMethodDesc = v.getMethodDesc() == null
                        ? null
                        : remapMethodDescriptor(v.getMethodDesc(), origToCurrent);
                String currentVarDesc = v.getDesc() == null
                        ? null
                        : remapDescriptor(v.getDesc(), origToCurrent);
                String currentMethodName = findMappedMethodName(
                        source,
                        v.getOwnerName(),
                        v.getMethodName(),
                        v.getMethodDesc()
                );
                inverted.addVariable(
                        currentOwner,
                        currentMethodName,
                        currentMethodDesc,
                        currentVarDesc,
                        v.getNewName(),
                        v.getIndex(),
                        v.getOldName()
                );
            }
        }
        return inverted;
    }

    private static String findMappedMethodName(IntermediateMappings source,
                                               String owner,
                                               String methodName,
                                               String methodDesc) {
        for (MethodMapping m : source.getMethods().getOrDefault(owner, List.of())) {
            if (m.getOldName().equals(methodName) && Objects.equals(m.getDesc(), methodDesc)) {
                return m.getNewName();
            }
        }
        return methodName;
    }

    private static String mapInternalType(String internalName, Map<String, String> classMap) {
        String direct = classMap.get(internalName);
        if (direct != null) {
            return direct;
        }
        int dollar = internalName.lastIndexOf('$');
        while (dollar > 0) {
            String outer = internalName.substring(0, dollar);
            String mappedOuter = classMap.get(outer);
            if (mappedOuter != null) {
                return mappedOuter + internalName.substring(dollar);
            }
            dollar = internalName.lastIndexOf('$', dollar - 1);
        }
        return internalName;
    }

    private static String remapDescriptor(String desc, Map<String, String> classMap) {
        try {
            return remapType(Type.getType(desc), classMap).getDescriptor();
        } catch (RuntimeException ex) {
            return desc;
        }
    }

    private static String remapMethodDescriptor(String desc, Map<String, String> classMap) {
        try {
            Type methodType = Type.getMethodType(desc);
            Type[] args = methodType.getArgumentTypes();
            Type[] mappedArgs = new Type[args.length];
            for (int i = 0; i < args.length; i++) {
                mappedArgs[i] = remapType(args[i], classMap);
            }
            Type mappedRet = remapType(methodType.getReturnType(), classMap);
            return Type.getMethodDescriptor(mappedRet, mappedArgs);
        } catch (RuntimeException ex) {
            return desc;
        }
    }

    private static Type remapType(Type type, Map<String, String> classMap) {
        return switch (type.getSort()) {
            case Type.OBJECT -> Type.getObjectType(mapInternalType(type.getInternalName(), classMap));
            case Type.ARRAY -> {
                Type elem = remapType(type.getElementType(), classMap);
                yield Type.getType("[".repeat(type.getDimensions()) + elem.getDescriptor());
            }
            default -> type;
        };
    }

    record Change(String oldName,
                  String newName,
                  ClassToolSupport.Scope scope,
                  String dexName) {
        Change {
            Objects.requireNonNull(oldName, "oldName");
            Objects.requireNonNull(newName, "newName");
            Objects.requireNonNull(scope, "scope");
        }

        boolean renamed() {
            return !oldName.equals(newName);
        }
    }

    record Summary(List<Change> changes) {
        Summary {
            changes = List.copyOf(changes);
        }

        int jvmChanges() {
            return count(ClassToolSupport.Scope.JVM);
        }

        int androidChanges() {
            return count(ClassToolSupport.Scope.ANDROID);
        }

        int count(ClassToolSupport.Scope scope) {
            if (scope == ClassToolSupport.Scope.ALL) {
                return changes.size();
            }
            return (int) changes.stream()
                    .filter(change -> change.scope() == scope)
                    .count();
        }

        Set<String> dexBundles() {
            Set<String> names = new TreeSet<>();
            changes.stream()
                    .map(Change::dexName)
                    .filter(Objects::nonNull)
                    .forEach(names::add);
            return names;
        }

        boolean isEmpty() {
            return changes.isEmpty();
        }
    }
}
