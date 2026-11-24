package me.naptila.recafmcp.mcp.tools;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.services.inheritance.InheritanceGraph;
import software.coley.recaf.services.inheritance.InheritanceVertex;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;

/**
 * Computes exact JVM/Dalvik method dispatch families without joining unrelated types
 * through supertypes (such as {@code java/lang/Object} or {@code java/lang/AutoCloseable})
 * that neither declare nor inherit the target method signature.
 */
final class MethodHierarchySupport {
    private MethodHierarchySupport() {
    }

    record Analysis(List<String> primaryDeclaringOwners,
                    Set<String> carrierAndDescendantOwners,
                    List<String> externalOverrides,
                    List<String> conflictingOwners) {
    }

    static boolean isInheritable(MethodMember method) {
        if (method == null) {
            return false;
        }
        String name = method.getName();
        if ("<init>".equals(name) || "<clinit>".equals(name)) {
            return false;
        }
        return (method.getAccess() & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == 0;
    }

    static Analysis analyze(Workspace workspace,
                            InheritanceGraph graph,
                            ClassInfo rootOwner,
                            MethodMember declaredMethod,
                            String newName) {
        String oldName = declaredMethod.getName();
        String descriptor = declaredMethod.getDescriptor();
        if (!isInheritable(declaredMethod)) {
            return new Analysis(
                    List.of(rootOwner.getName()),
                    Set.of(rootOwner.getName()),
                    List.of(),
                    List.of()
            );
        }

        WorkspaceResource primary = workspace.getPrimaryResource();
        InheritanceVertex rootVertex = graph == null ? null : graph.getVertex(rootOwner.getName());
        if (rootVertex == null) {
            Set<String> externalOverrides = new TreeSet<>();
            Set<String> conflictingOwners = new TreeSet<>();
            inspectExternalPlatformSupertypes(
                    workspace,
                    rootOwner,
                    oldName,
                    newName,
                    descriptor,
                    externalOverrides,
                    conflictingOwners
            );
            return new Analysis(
                    List.of(rootOwner.getName()),
                    Set.of(rootOwner.getName()),
                    List.copyOf(externalOverrides),
                    List.copyOf(conflictingOwners)
            );
        }

        Map<String, Boolean> carrierMemo = new HashMap<>();
        Set<InheritanceVertex> componentVertices = new LinkedHashSet<>();
        Queue<InheritanceVertex> queue = new ArrayDeque<>();
        componentVertices.add(rootVertex);
        queue.add(rootVertex);

        while (!queue.isEmpty()) {
            InheritanceVertex current = queue.remove();
            for (InheritanceVertex parent : current.getParents()) {
                if (parent == null || componentVertices.contains(parent)) {
                    continue;
                }
                if (carriesMethod(workspace, parent, oldName, descriptor, carrierMemo)
                        && canLinkParentChild(parent, current, oldName, descriptor)) {
                    componentVertices.add(parent);
                    queue.add(parent);
                }
            }
            for (InheritanceVertex child : current.getChildren()) {
                if (child == null || componentVertices.contains(child)) {
                    continue;
                }
                if (carriesMethod(workspace, child, oldName, descriptor, carrierMemo)
                        && canLinkParentChild(current, child, oldName, descriptor)) {
                    componentVertices.add(child);
                    queue.add(child);
                }
            }
        }

        Set<String> primaryDeclaring = new TreeSet<>();
        primaryDeclaring.add(rootOwner.getName());
        Set<String> carrierNames = new LinkedHashSet<>();
        Set<String> externalOverrides = new TreeSet<>();
        Set<String> conflictingOwners = new TreeSet<>();

        for (InheritanceVertex vertex : componentVertices) {
            String vertexName = vertex.getName();
            carrierNames.add(vertexName);
            for (InheritanceVertex descendant : vertex.getAllChildren()) {
                if (descendant != null && descendant.getName() != null) {
                    carrierNames.add(descendant.getName());
                }
            }
            if (ownsPrimaryClass(primary, vertexName)
                    && declaresInheritableMethod(vertex, oldName, descriptor)) {
                primaryDeclaring.add(vertexName);
            } else if ((vertex.isLibraryVertex() || !ownsPrimaryClass(primary, vertexName))
                    && declaresInheritableMethod(vertex, oldName, descriptor)) {
                externalOverrides.add(vertexName + "." + oldName + descriptor);
            }

            if (!vertexName.equals(rootOwner.getName())
                    && declaresNonPrivateMethod(vertex, newName, descriptor)) {
                conflictingOwners.add(vertexName);
            }
            for (InheritanceVertex parent : vertex.getAllParents()) {
                if (parent == null || parent.getName() == null) {
                    continue;
                }
                if ((parent.isLibraryVertex() || !ownsPrimaryClass(primary, parent.getName()))
                        && declaresInheritableMethod(parent, oldName, descriptor)
                        && canInheritFromAncestor(parent, vertex, oldName, descriptor)) {
                    externalOverrides.add(parent.getName() + "." + oldName + descriptor);
                }
                if (!parent.getName().equals(rootOwner.getName())
                        && declaresInheritableMethod(parent, newName, descriptor)
                        && canInheritFromAncestor(parent, vertex, newName, descriptor)) {
                    conflictingOwners.add(parent.getName());
                }
            }
            for (InheritanceVertex child : vertex.getAllChildren()) {
                if (child == null || child.getName() == null
                        || child.getName().equals(rootOwner.getName())) {
                    continue;
                }
                if (declaresNonPrivateMethod(child, newName, descriptor)
                        && canInheritFromAncestor(vertex, child, oldName, descriptor)) {
                    conflictingOwners.add(child.getName());
                }
            }

            ClassInfo info = resolveClassInfo(workspace, vertex);
            if (info != null) {
                inspectExternalPlatformSupertypes(
                        workspace,
                        info,
                        oldName,
                        newName,
                        descriptor,
                        externalOverrides,
                        conflictingOwners
                );
            }
        }

        return new Analysis(
                List.copyOf(primaryDeclaring),
                Set.copyOf(carrierNames),
                List.copyOf(externalOverrides),
                List.copyOf(conflictingOwners)
        );
    }

    static Set<String> carrierAndDescendantNames(Workspace workspace,
                                                 InheritanceGraph graph,
                                                 String ownerName,
                                                 String methodName,
                                                 String descriptor) {
        Set<String> names = new LinkedHashSet<>();
        names.add(ownerName);
        if (graph == null) {
            return names;
        }
        InheritanceVertex root = graph.getVertex(ownerName);
        if (root == null) {
            return names;
        }
        ClassInfo info = resolveClassInfo(workspace, root);
        MethodMember method = info == null ? null : info.getDeclaredMethod(methodName, descriptor);
        if (method != null && !isInheritable(method)) {
            return names;
        }
        Map<String, Boolean> memo = new HashMap<>();
        Set<InheritanceVertex> visited = new HashSet<>();
        Queue<InheritanceVertex> queue = new ArrayDeque<>();
        visited.add(root);
        queue.add(root);
        while (!queue.isEmpty()) {
            InheritanceVertex current = queue.remove();
            if (current.getName() != null) {
                names.add(current.getName());
            }
            for (InheritanceVertex parent : current.getParents()) {
                if (parent != null && !visited.contains(parent)
                        && carriesMethod(workspace, parent, methodName, descriptor, memo)
                        && canLinkParentChild(parent, current, methodName, descriptor)) {
                    visited.add(parent);
                    queue.add(parent);
                }
            }
            for (InheritanceVertex child : current.getChildren()) {
                if (child != null && !visited.contains(child)) {
                    if (child.getName() != null) {
                        names.add(child.getName());
                    }
                    if (carriesMethod(workspace, child, methodName, descriptor, memo)
                            && canLinkParentChild(current, child, methodName, descriptor)) {
                        visited.add(child);
                        queue.add(child);
                    }
                }
            }
        }
        for (InheritanceVertex comp : List.copyOf(visited)) {
            for (InheritanceVertex desc : comp.getAllChildren()) {
                if (desc != null && desc.getName() != null) {
                    names.add(desc.getName());
                }
            }
        }
        return names;
    }

    private static boolean carriesMethod(Workspace workspace,
                                         InheritanceVertex vertex,
                                         String name,
                                         String descriptor,
                                         Map<String, Boolean> memo) {
        if (vertex == null || vertex.getName() == null) {
            return false;
        }
        Boolean cached = memo.get(vertex.getName());
        if (cached != null) {
            return cached;
        }
        boolean result = computeCarriesMethod(workspace, vertex, name, descriptor);
        memo.put(vertex.getName(), result);
        return result;
    }

    private static boolean computeCarriesMethod(Workspace workspace,
                                                InheritanceVertex vertex,
                                                String name,
                                                String descriptor) {
        if (declaresInheritableMethod(vertex, name, descriptor)) {
            return true;
        }
        for (InheritanceVertex parent : vertex.getAllParents()) {
            if (parent != null
                    && declaresInheritableMethod(parent, name, descriptor)
                    && canInheritFromAncestor(parent, vertex, name, descriptor)) {
                return true;
            }
        }
        ClassInfo info = resolveClassInfo(workspace, vertex);
        if (info != null) {
            Set<String> overrides = new HashSet<>();
            Set<String> unusedConflicts = new HashSet<>();
            inspectExternalPlatformSupertypes(
                    workspace,
                    info,
                    name,
                    "\u0000__unused__",
                    descriptor,
                    overrides,
                    unusedConflicts
            );
            return !overrides.isEmpty();
        }
        return false;
    }

    private static boolean declaresInheritableMethod(InheritanceVertex vertex,
                                                     String name,
                                                     String descriptor) {
        if (vertex == null) {
            return false;
        }
        ClassInfo info = vertex.getValue();
        if (info != null) {
            MethodMember m = info.getDeclaredMethod(name, descriptor);
            return isInheritable(m);
        }
        return !"<init>".equals(name)
                && !"<clinit>".equals(name)
                && vertex.hasMethod(name, descriptor);
    }

    private static boolean declaresNonPrivateMethod(InheritanceVertex vertex,
                                                    String name,
                                                    String descriptor) {
        if (vertex == null) {
            return false;
        }
        ClassInfo info = vertex.getValue();
        if (info != null) {
            MethodMember m = info.getDeclaredMethod(name, descriptor);
            return m != null && (m.getAccess() & Opcodes.ACC_PRIVATE) == 0;
        }
        return vertex.hasMethod(name, descriptor);
    }

    private static boolean canLinkParentChild(InheritanceVertex parent,
                                              InheritanceVertex child,
                                              String name,
                                              String descriptor) {
        ClassInfo parentInfo = parent.getValue();
        if (parentInfo == null) {
            return true;
        }
        MethodMember parentDecl = parentInfo.getDeclaredMethod(name, descriptor);
        if (parentDecl != null && isPackagePrivate(parentInfo, parentDecl)) {
            boolean hasVisibleSuper = parent.getAllParents().stream()
                    .anyMatch(anc -> anc != null
                            && declaresInheritableMethod(anc, name, descriptor)
                            && canInheritFromAncestor(anc, child, name, descriptor));
            if (!hasVisibleSuper) {
                return Objects.equals(packageOf(parent.getName()), packageOf(child.getName()));
            }
        }
        return true;
    }

    private static boolean canInheritFromAncestor(InheritanceVertex ancestor,
                                                  InheritanceVertex descendant,
                                                  String name,
                                                  String descriptor) {
        ClassInfo ancInfo = ancestor.getValue();
        if (ancInfo == null) {
            return true;
        }
        MethodMember decl = ancInfo.getDeclaredMethod(name, descriptor);
        if (decl == null) {
            return true;
        }
        if (isPackagePrivate(ancInfo, decl)) {
            return Objects.equals(packageOf(ancestor.getName()), packageOf(descendant.getName()));
        }
        return true;
    }

    private static boolean isPackagePrivate(ClassInfo owner, MethodMember method) {
        if ((owner.getAccess() & Opcodes.ACC_INTERFACE) != 0) {
            return false;
        }
        return (method.getAccess()
                & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE)) == 0;
    }

    private static String packageOf(String internalName) {
        if (internalName == null) {
            return "";
        }
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? "" : internalName.substring(0, slash);
    }

    private static ClassInfo resolveClassInfo(Workspace workspace, InheritanceVertex vertex) {
        if (vertex.getValue() != null) {
            return vertex.getValue();
        }
        if (workspace == null || vertex.getName() == null) {
            return null;
        }
        var node = workspace.findClass(vertex.getName());
        return node == null ? null : node.getValue();
    }

    static boolean ownsPrimaryClass(WorkspaceResource primary, String internalName) {
        if (primary.getJvmClassBundle().containsKey(internalName)) {
            return true;
        }
        if (primary.getVersionedJvmClassBundles().values().stream()
                .anyMatch(bundle -> bundle.containsKey(internalName))) {
            return true;
        }
        return primary.getAndroidClassBundles().values().stream()
                .anyMatch(bundle -> bundle.containsKey(internalName));
    }

    static void inspectExternalPlatformSupertypes(Workspace workspace,
                                                  ClassInfo rootOwner,
                                                  String oldName,
                                                  String newName,
                                                  String descriptor,
                                                  Set<String> externalOverrides,
                                                  Set<String> conflictingOwners) {
        WorkspaceResource primary = workspace.getPrimaryResource();
        Queue<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        enqueueSupertypes(rootOwner, queue, visited);
        ClassLoader platformLoader = ClassLoader.getPlatformClassLoader();

        while (!queue.isEmpty()) {
            String superInternal = queue.remove();
            var classNode = workspace.findClass(superInternal);
            if (classNode != null) {
                ClassInfo info = classNode.getValue();
                boolean inPrimary = ownsPrimaryClass(primary, superInternal);
                if (!inPrimary) {
                    var oldMatch = info.getDeclaredMethod(oldName, descriptor);
                    if (oldMatch != null && isInheritable(oldMatch)) {
                        externalOverrides.add(superInternal + "." + oldName + descriptor);
                    }
                    var newMatch = info.getDeclaredMethod(newName, descriptor);
                    if (newMatch != null && isInheritable(newMatch)) {
                        conflictingOwners.add(superInternal);
                    }
                }
                enqueueSupertypes(info, queue, visited);
                continue;
            }
            if (superInternal.startsWith("java/")
                    || superInternal.startsWith("javax/")
                    || superInternal.startsWith("jdk/")) {
                try {
                    Class<?> platformClass = Class.forName(
                            superInternal.replace('/', '.'),
                            false,
                            platformLoader
                    );
                    inspectPlatformClassHierarchy(
                            platformClass,
                            oldName,
                            newName,
                            descriptor,
                            visited,
                            externalOverrides,
                            conflictingOwners
                    );
                } catch (ClassNotFoundException | LinkageError ignored) {
                    // External non-JDK type not present in platform classloader.
                }
            }
        }
    }

    private static void inspectPlatformClassHierarchy(Class<?> type,
                                                      String oldName,
                                                      String newName,
                                                      String descriptor,
                                                      Set<String> visited,
                                                      Set<String> externalOverrides,
                                                      Set<String> conflictingOwners) {
        if (type == null) {
            return;
        }
        String internalName = type.getName().replace('.', '/');
        for (Method method : type.getDeclaredMethods()) {
            int mod = method.getModifiers();
            if (Modifier.isPrivate(mod) || Modifier.isStatic(mod)) {
                continue;
            }
            if (!Type.getMethodDescriptor(method).equals(descriptor)) {
                continue;
            }
            if (method.getName().equals(oldName)) {
                externalOverrides.add(internalName + "." + oldName + descriptor);
            } else if (method.getName().equals(newName)) {
                conflictingOwners.add(internalName);
            }
        }
        Class<?> superclass = type.getSuperclass();
        if (superclass != null && visited.add(superclass.getName().replace('.', '/'))) {
            inspectPlatformClassHierarchy(
                    superclass,
                    oldName,
                    newName,
                    descriptor,
                    visited,
                    externalOverrides,
                    conflictingOwners
            );
        }
        for (Class<?> iface : type.getInterfaces()) {
            if (visited.add(iface.getName().replace('.', '/'))) {
                inspectPlatformClassHierarchy(
                        iface,
                        oldName,
                        newName,
                        descriptor,
                        visited,
                        externalOverrides,
                        conflictingOwners
                );
            }
        }
    }

    private static void enqueueSupertypes(ClassInfo info,
                                          Queue<String> queue,
                                          Set<String> visited) {
        if (info.getSuperName() != null && visited.add(info.getSuperName())) {
            queue.add(info.getSuperName());
        }
        for (String iface : info.getInterfaces()) {
            if (iface != null && visited.add(iface)) {
                queue.add(iface);
            }
        }
    }
}
