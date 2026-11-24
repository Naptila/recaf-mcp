package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import software.coley.recaf.info.AndroidClassInfo;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.workspace.model.bundle.AndroidClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Shared primary and embedded resource class scope and multi-DEX identity helpers.
 */
final class ClassToolSupport {
    static final String SCOPE_ARGUMENT = "scope";
    static final String DEX_NAME_ARGUMENT = "dex_name";
    static final String RESOURCE_ARGUMENT = "resource";
    static final String INCLUDE_EMBEDDED_ARGUMENT = "include_embedded";

    private static final Comparator<Entry> ENTRY_ORDER = Comparator
            .comparing(Entry::className)
            .thenComparing(entry -> entry.scope().value())
            .thenComparing(Entry::dexName, Comparator.nullsFirst(String::compareTo))
            .thenComparing(Entry::resourcePath, Comparator.nullsFirst(String::compareTo));

    private ClassToolSupport() {
    }

    static Selection parseSelection(JsonObject arguments, boolean allowAll) {
        return parseSelection(arguments, allowAll, false);
    }

    static Selection parseSelection(JsonObject arguments,
                                    boolean allowAll,
                                    boolean allowEmbeddedScan) {
        String value = ToolArguments.optionalString(arguments, SCOPE_ARGUMENT);
        boolean scopeExplicit = value != null && !"auto".equals(value);
        String dexName = ToolArguments.optionalString(arguments, DEX_NAME_ARGUMENT);
        Scope scope;
        if (!scopeExplicit) {
            scope = dexName != null ? Scope.ANDROID : Scope.JVM;
        } else {
            scope = Scope.from(value);
            if (!allowAll && scope == Scope.ALL) {
                throw new IllegalArgumentException("scope=all is not supported for exact class lookup");
            }
            if (dexName != null && scope != Scope.ANDROID) {
                throw new IllegalArgumentException("dex_name requires scope=android");
            }
        }
        String resourcePath = arguments.has(RESOURCE_ARGUMENT)
                ? ToolArguments.optionalString(arguments, RESOURCE_ARGUMENT)
                : null;
        boolean includeEmbedded = allowEmbeddedScan
                && arguments.has(INCLUDE_EMBEDDED_ARGUMENT)
                && ToolArguments.bool(arguments, INCLUDE_EMBEDDED_ARGUMENT, false);
        if (resourcePath != null && includeEmbedded) {
            throw new IllegalArgumentException(
                    "resource and include_embedded=true cannot be combined"
            );
        }
        return new Selection(scope, dexName, scopeExplicit, resourcePath, includeEmbedded);
    }

    static Selection resolve(WorkspaceResource primary, Selection selection) {
        if (selection.scopeExplicit() || primary == null) {
            return selection;
        }
        Scope resolved = defaultScope(primary, selection);
        if (resolved == selection.scope()) {
            return selection;
        }
        return new Selection(
                resolved,
                selection.dexName(),
                false,
                selection.resourcePath(),
                selection.includeEmbedded()
        );
    }

    static Scope defaultScope(WorkspaceResource primary, Selection selection) {
        if (selection.dexName() != null) {
            return Scope.ANDROID;
        }
        if (selection.resourcePath() != null) {
            WorkspaceResource embedded = resolveEmbeddedResource(
                    primary,
                    selection.resourcePath()
            );
            if (embedded != null) {
                return isAndroidResource(embedded) ? Scope.ANDROID : Scope.JVM;
            }
        }
        return isAndroidResource(primary) ? Scope.ANDROID : Scope.JVM;
    }

    static boolean isAndroidResource(WorkspaceResource resource) {
        if (resource instanceof me.naptila.recafmcp.mcp.android.AndroidInputResource) {
            return true;
        }
        if (resource instanceof WorkspaceFileResource fileResource
                && (fileResource.getFileInfo() instanceof software.coley.recaf.info.ApkFileInfo
                || fileResource.getFileInfo() instanceof software.coley.recaf.info.DexFileInfo)) {
            return true;
        }
        return resource.getAndroidClassBundles().values().stream()
                .anyMatch(bundle -> !bundle.isEmpty());
    }

    static List<ScopedResource> embeddedResources(WorkspaceResource primary) {
        List<ScopedResource> flattened = new ArrayList<>();
        collectEmbedded(primary, null, flattened);
        flattened.sort(Comparator.comparing(ScopedResource::path));
        return flattened;
    }

    private static void collectEmbedded(WorkspaceResource parent,
                                        String prefix,
                                        List<ScopedResource> target) {
        for (Map.Entry<String, WorkspaceFileResource> entry
                : parent.getEmbeddedResources().entrySet()) {
            String path = prefix == null ? entry.getKey() : prefix + "!/" + entry.getKey();
            WorkspaceFileResource child = entry.getValue();
            target.add(new ScopedResource(path, child));
            collectEmbedded(child, path, target);
        }
    }

    static WorkspaceResource resolveEmbeddedResource(WorkspaceResource primary,
                                                     String resourcePath) {
        WorkspaceFileResource direct = primary.getEmbeddedResources().get(resourcePath);
        if (direct != null) {
            return direct;
        }
        for (ScopedResource item : embeddedResources(primary)) {
            if (item.path().equals(resourcePath)) {
                return item.resource();
            }
        }
        return null;
    }

    static List<ScopedResource> targetResources(WorkspaceResource primary,
                                                Selection selection) {
        if (selection.resourcePath() != null) {
            WorkspaceResource resolved = resolveEmbeddedResource(
                    primary,
                    selection.resourcePath()
            );
            return resolved == null
                    ? List.of()
                    : List.of(new ScopedResource(selection.resourcePath(), resolved));
        }
        List<ScopedResource> targets = new ArrayList<>();
        targets.add(new ScopedResource(null, primary));
        if (selection.includeEmbedded()) {
            targets.addAll(embeddedResources(primary));
        }
        return targets;
    }

    static JsonObject validateSelection(WorkspaceResource primary, Selection selection) {
        if (selection.resourcePath() != null
                && resolveEmbeddedResource(primary, selection.resourcePath()) == null) {
            String message = "embedded resource not found: " + selection.resourcePath();
            JsonObject structured = new JsonObject();
            structured.addProperty("error", message);
            structured.addProperty("requested_resource", selection.resourcePath());
            JsonArray available = new JsonArray();
            embeddedResources(primary).stream()
                    .map(ScopedResource::path)
                    .limit(200)
                    .forEach(available::add);
            structured.add("available_embedded_resources", available);
            return McpTool.errorResult(message, structured);
        }
        if (selection.dexName() != null) {
            List<ScopedResource> targets = targetResources(primary, selection);
            Set<String> availableDexNames = new LinkedHashSet<>();
            boolean found = false;
            for (ScopedResource target : targets) {
                Map<String, AndroidClassBundle> bundles =
                        target.resource().getAndroidClassBundles();
                availableDexNames.addAll(bundles.keySet());
                if (bundles.containsKey(selection.dexName())) {
                    found = true;
                }
            }
            if (!found) {
                String message = "Android DEX bundle not found: " + selection.dexName();
                JsonObject structured = new JsonObject();
                structured.addProperty("error", message);
                structured.addProperty("requested_scope", selection.scope().value());
                structured.addProperty("dex_name", selection.dexName());
                if (selection.resourcePath() != null) {
                    structured.addProperty("resource", selection.resourcePath());
                }
                JsonArray available = new JsonArray();
                availableDexNames.stream().sorted().forEach(available::add);
                structured.add("available_dex_names", available);
                return McpTool.errorResult(message, structured);
            }
        }
        return null;
    }

    static List<Entry> entries(WorkspaceResource primary, Selection selection) {
        List<Entry> entries = new ArrayList<>();
        for (ScopedResource scoped : targetResources(primary, selection)) {
            WorkspaceResource resource = scoped.resource();
            String resourcePath = scoped.path();
            if (selection.scope().includesJvm()) {
                resource.getJvmClassBundle().values().forEach(classInfo ->
                        entries.add(Entry.jvm(resourcePath, resource, classInfo)));
            }
            if (selection.scope().includesAndroid()) {
                for (Map.Entry<String, AndroidClassBundle> bundleEntry
                        : resource.getAndroidClassBundles().entrySet()) {
                    if (selection.dexName() != null
                            && !selection.dexName().equals(bundleEntry.getKey())) {
                        continue;
                    }
                    bundleEntry.getValue().values().forEach(classInfo ->
                            entries.add(Entry.android(
                                    bundleEntry.getKey(),
                                    resourcePath,
                                    resource,
                                    classInfo
                            )));
                }
            }
        }
        entries.sort(ENTRY_ORDER);
        return entries;
    }

    static List<Entry> exact(WorkspaceResource primary,
                             Selection selection,
                             String className) {
        List<Entry> matches = exactRaw(primary, selection, className);
        if (matches.isEmpty() && className != null) {
            String normalized = className.contains("\\u")
                    ? JasmEscapeSupport.unescapeSafeIdentifierUnicode(className)
                    : className;
            if (!normalized.equals(className)) {
                matches = exactRaw(primary, selection, normalized);
            }
            if (matches.isEmpty() && normalized.indexOf('.') >= 0 && normalized.indexOf('/') < 0) {
                String slashed = normalized.replace('.', '/');
                matches = exactRaw(primary, selection, slashed);
            }
        }
        return matches;
    }

    private static List<Entry> exactRaw(WorkspaceResource primary,
                                        Selection selection,
                                        String className) {
        List<Entry> matches = new ArrayList<>();
        for (ScopedResource scoped : targetResources(primary, selection)) {
            WorkspaceResource resource = scoped.resource();
            String resourcePath = scoped.path();
            if (selection.scope().includesJvm()) {
                JvmClassInfo jvm = resource.getJvmClassBundle().get(className);
                if (jvm != null) {
                    matches.add(Entry.jvm(resourcePath, resource, jvm));
                }
            }
            if (selection.scope().includesAndroid()) {
                for (Map.Entry<String, AndroidClassBundle> bundleEntry
                        : resource.getAndroidClassBundles().entrySet()) {
                    if (selection.dexName() != null
                            && !selection.dexName().equals(bundleEntry.getKey())) {
                        continue;
                    }
                    AndroidClassInfo android = bundleEntry.getValue().get(className);
                    if (android != null) {
                        matches.add(Entry.android(
                                bundleEntry.getKey(),
                                resourcePath,
                                resource,
                                android
                        ));
                    }
                }
            }
        }
        matches.sort(ENTRY_ORDER);
        return matches;
    }

    static software.coley.recaf.info.member.MethodMember findDeclaredMethod(
            ClassInfo owner,
            String name,
            String descriptor) {
        var direct = owner.getDeclaredMethod(name, descriptor);
        if (direct != null) {
            return direct;
        }
        String normName = normalizeUnicodeInput(name);
        String normDesc = normalizeUnicodeInput(descriptor);
        if (!Objects.equals(normName, name) || !Objects.equals(normDesc, descriptor)) {
            return owner.getDeclaredMethod(normName, normDesc);
        }
        return null;
    }

    static software.coley.recaf.info.member.FieldMember findDeclaredField(
            ClassInfo owner,
            String name,
            String descriptor) {
        var direct = owner.getDeclaredField(name, descriptor);
        if (direct != null) {
            return direct;
        }
        String normName = normalizeUnicodeInput(name);
        String normDesc = normalizeUnicodeInput(descriptor);
        if (!Objects.equals(normName, name) || !Objects.equals(normDesc, descriptor)) {
            return owner.getDeclaredField(normName, normDesc);
        }
        return null;
    }

    static String normalizeUnicodeInput(String value) {
        if (value == null || !value.contains("\\u")) {
            return value;
        }
        return JasmEscapeSupport.unescapeSafeIdentifierUnicode(value);
    }

    static String normalizeClassNameInput(String value) {
        if (value == null) {
            return null;
        }
        String unescaped = normalizeUnicodeInput(value);
        if (unescaped.indexOf('.') >= 0 && unescaped.indexOf('/') < 0) {
            return unescaped.replace('.', '/');
        }
        return unescaped;
    }

    static java.util.function.Predicate<String> classFilterMatcher(String filter, boolean regex) {
        if (filter == null) {
            return ignored -> true;
        }
        if (regex) {
            return SearchToolSupport.matcher(filter, true);
        }
        String unescaped = normalizeUnicodeInput(filter);
        String slashed = normalizeClassNameInput(filter);
        boolean checkUnescaped = !unescaped.equals(filter);
        boolean checkSlashed = !slashed.equals(filter) && !slashed.equals(unescaped);
        return className -> className != null
                && (className.contains(filter)
                || (checkUnescaped && className.contains(unescaped))
                || (checkSlashed && className.contains(slashed)));
    }

    static Entry identify(WorkspaceResource primary, ClassInfo classInfo) {
        return identify(primary, null, classInfo);
    }

    static Entry identify(WorkspaceResource resource,
                          String resourcePath,
                          ClassInfo classInfo) {
        if (classInfo.isJvmClass()
                && resource.getJvmClassBundle().get(classInfo.getName()) == classInfo) {
            return Entry.jvm(resourcePath, resource, classInfo.asJvmClass());
        }
        if (classInfo.isAndroidClass()) {
            for (Map.Entry<String, AndroidClassBundle> bundle
                    : resource.getAndroidClassBundles().entrySet()) {
                if (bundle.getValue().get(classInfo.getName()) == classInfo) {
                    return Entry.android(
                            bundle.getKey(),
                            resourcePath,
                            resource,
                            classInfo.asAndroidClass()
                    );
                }
            }
        }
        return null;
    }

    static Entry identifyInTargets(List<ScopedResource> targets,
                                   WorkspaceResource matchedResource,
                                   ClassInfo classInfo) {
        for (ScopedResource target : targets) {
            if (target.resource() == matchedResource) {
                return identify(matchedResource, target.path(), classInfo);
            }
        }
        return null;
    }

    static void addScopeProperties(JsonObject properties, boolean allowAll) {
        addScopeProperties(properties, allowAll, false, false);
    }

    static void addScopeProperties(JsonObject properties,
                                   boolean allowAll,
                                   boolean allowResource,
                                   boolean allowIncludeEmbedded) {
        addScopeProperty(properties, allowAll);

        JsonObject dexName = new JsonObject();
        dexName.addProperty("type", "string");
        dexName.addProperty("minLength", 1);
        dexName.addProperty(
                "description",
                "Exact Android DEX name, for example classes2.dex."
        );
        properties.add(DEX_NAME_ARGUMENT, dexName);

        if (allowResource) {
            addResourceProperty(properties);
        }
        if (allowIncludeEmbedded) {
            addIncludeEmbeddedProperty(properties);
        }
    }

    static void addResourceProperty(JsonObject properties) {
        JsonObject resource = new JsonObject();
        resource.addProperty("type", "string");
        resource.addProperty("minLength", 1);
        resource.addProperty(
                "description",
                "Optional embedded resource path from workspace_info (for example META-INF/jars/sub.jar)."
        );
        properties.add(RESOURCE_ARGUMENT, resource);
    }

    static void addIncludeEmbeddedProperty(JsonObject properties) {
        JsonObject includeEmbedded = new JsonObject();
        includeEmbedded.addProperty("type", "boolean");
        includeEmbedded.addProperty("default", false);
        includeEmbedded.addProperty(
                "description",
                "Include classes/files inside embedded JAR resources."
        );
        properties.add(INCLUDE_EMBEDDED_ARGUMENT, includeEmbedded);
    }

    static void addScopeProperty(JsonObject properties, boolean allowAll) {
        JsonObject scope = new JsonObject();
        scope.addProperty("type", "string");
        JsonArray values = new JsonArray();
        values.add("auto");
        values.add(Scope.JVM.value());
        values.add(Scope.ANDROID.value());
        if (allowAll) {
            values.add(Scope.ALL.value());
        }
        scope.add("enum", values);
        scope.addProperty("default", "auto");
        scope.addProperty(
                "description",
                "Class domain: auto (default; selects android on APK/DEX workspaces and jvm otherwise), "
                        + "jvm, or android" + (allowAll ? " (or all)." : ".")
        );
        properties.add(SCOPE_ARGUMENT, scope);
    }

    static void addIdentity(JsonObject target, Entry entry) {
        target.addProperty("class_scope", entry.scope().value());
        target.addProperty("scope", outputScope(entry.scope(), false, entry.resourcePath(), false));
        if (entry.dexName() == null) {
            target.add("dex_name", JsonNull.INSTANCE);
        } else {
            target.addProperty("dex_name", entry.dexName());
        }
        if (entry.resourcePath() != null) {
            target.addProperty("resource", entry.resourcePath());
        }
    }

    static void addHitIdentity(JsonObject target, Entry entry) {
        target.addProperty("class_scope", entry.scope().value());
        if (entry.dexName() == null) {
            target.add("dex_name", JsonNull.INSTANCE);
        } else {
            target.addProperty("dex_name", entry.dexName());
        }
        if (entry.resourcePath() != null) {
            target.addProperty("resource", entry.resourcePath());
        }
    }

    static void addSelectionMetadata(JsonObject target, Selection selection) {
        if (selection.resourcePath() != null) {
            target.addProperty("resource", selection.resourcePath());
        }
        if (selection.includeEmbedded()) {
            target.addProperty("include_embedded", true);
        }
    }

    static void addScopeDiagnostics(JsonObject target,
                                    WorkspaceResource primary,
                                    Selection selection) {
        if (selection.resourcePath() != null) {
            return;
        }
        if (!selection.scopeExplicit() && selection.scope() == Scope.JVM) {
            int androidCount = primary.getAndroidClassBundles().values().stream()
                    .mapToInt(Map::size)
                    .sum();
            if (androidCount > 0) {
                target.addProperty("available_android_class_count", androidCount);
                JsonArray dexNames = new JsonArray();
                primary.getAndroidClassBundles().keySet().stream()
                        .sorted()
                        .forEach(dexNames::add);
                target.add("available_dex_names", dexNames);
                target.addProperty(
                        "scope_hint",
                        "Workspace contains " + androidCount
                                + " Android DEX classes; pass scope=\"android\" or scope=\"all\" to include them."
                );
            }
        } else if (!selection.scopeExplicit() && selection.scope() == Scope.ANDROID) {
            int jvmCount = primary.getJvmClassBundle().size();
            if (jvmCount > 0) {
                target.addProperty("available_jvm_class_count", jvmCount);
                target.addProperty(
                        "scope_hint",
                        "Workspace also contains " + jvmCount
                                + " JVM class(es); pass scope=\"jvm\" or scope=\"all\" to inspect them."
                );
            }
        }
        if (!selection.includeEmbedded()
                && primary.getJvmClassBundle().isEmpty()
                && primary.getAndroidClassBundles().isEmpty()
                && !primary.getEmbeddedResources().isEmpty()) {
            List<ScopedResource> embedded = embeddedResources(primary);
            target.addProperty("available_embedded_resource_count", embedded.size());
            target.addProperty(
                    "embedded_hint",
                    "Primary resource has 0 top-level classes and " + embedded.size()
                            + " embedded resource(s); pass include_embedded=true or resource=\"<path>\" from workspace_info."
            );
        }
    }

    static void addMutationScopeDiagnostics(JsonObject target,
                                            WorkspaceResource primary,
                                            Selection selection,
                                            String requestedClassName) {
        if (!selection.scopeExplicit() && selection.scope() == Scope.JVM) {
            int androidCount = primary.getAndroidClassBundles().values().stream()
                    .mapToInt(Map::size)
                    .sum();
            if (androidCount > 0) {
                target.addProperty("available_android_class_count", androidCount);
                JsonArray dexNames = new JsonArray();
                primary.getAndroidClassBundles().keySet().stream()
                        .sorted()
                        .forEach(dexNames::add);
                target.add("available_dex_names", dexNames);
                target.addProperty(
                        "scope_hint",
                        "Workspace contains " + androidCount
                                + " Android DEX classes; pass scope=\"android\" to target them."
                );
            }
        } else if (!selection.scopeExplicit() && selection.scope() == Scope.ANDROID) {
            int jvmCount = primary.getJvmClassBundle().size();
            if (jvmCount > 0) {
                target.addProperty("available_jvm_class_count", jvmCount);
                target.addProperty(
                        "scope_hint",
                        "Workspace also contains " + jvmCount
                                + " JVM class(es); pass scope=\"jvm\" to target them."
                );
            }
        }
        if (requestedClassName != null && !primary.getEmbeddedResources().isEmpty()) {
            String normClass = normalizeClassNameInput(requestedClassName);
            List<String> foundResources = new ArrayList<>();
            for (ScopedResource embedded : embeddedResources(primary)) {
                Selection embSel = new Selection(Scope.ALL, null, true, null, false);
                if (!exact(embedded.resource(), embSel, normClass).isEmpty()) {
                    foundResources.add(embedded.path());
                }
            }
            if (!foundResources.isEmpty()) {
                JsonArray arr = new JsonArray();
                foundResources.stream().limit(20).forEach(arr::add);
                target.add("found_in_embedded_resources", arr);
                target.addProperty(
                        "embedded_note",
                        "Target class exists inside an embedded archive; embedded archives are "
                                + "currently read-only and cannot be mutated directly."
                );
            } else if (primary.getJvmClassBundle().isEmpty()
                    && primary.getAndroidClassBundles().isEmpty()) {
                target.addProperty(
                        "embedded_note",
                        "Primary resource has 0 top-level classes; classes inside embedded "
                                + "archives are read-only."
                );
            }
        }
    }

    static void addDidYouMeanClasses(JsonObject target,
                                     WorkspaceResource primary,
                                     Selection selection,
                                     String requestedClassName) {
        if (requestedClassName == null || requestedClassName.isBlank()) {
            return;
        }
        String normQuery = normalizeUnicodeInput(requestedClassName).replace('.', '/');
        String queryPkg = packagePrefix(normQuery);
        String querySimple = simpleName(normQuery);
        List<ScoredSuggestion> scored = new ArrayList<>();
        List<Entry> all = entries(primary, selection);
        if (all.size() > 50_000) {
            return;
        }
        for (Entry entry : all) {
            String candidate = entry.className();
            if (candidate.equals(requestedClassName)) {
                continue;
            }
            if (candidate.equals(normQuery)) {
                scored.add(new ScoredSuggestion(candidate, 0));
                continue;
            }
            String candPkg = packagePrefix(candidate);
            String candSimple = simpleName(candidate);
            int score = similarityScore(queryPkg, querySimple, candPkg, candSimple);
            if (score < 1_000) {
                scored.add(new ScoredSuggestion(candidate, score));
            }
        }
        emitSuggestions(target, scored);
    }

    static void addDidYouMeanMembers(JsonObject target,
                                     ClassInfo owner,
                                     String requestedName,
                                     String requestedDescriptor,
                                     boolean isMethod) {
        if (owner == null || requestedName == null) {
            return;
        }
        String normName = normalizeUnicodeInput(requestedName);
        String normDesc = normalizeUnicodeInput(requestedDescriptor);
        List<ScoredSuggestion> scored = new ArrayList<>();
        Iterable<? extends software.coley.recaf.info.member.ClassMember> members =
                isMethod ? owner.getMethods() : owner.getFields();
        for (var member : members) {
            String mName = member.getName();
            String mDesc = member.getDescriptor();
            if (mName.equals(normName) && Objects.equals(normDesc, mDesc)) {
                continue;
            }
            boolean sameDesc = normDesc != null && normDesc.equals(mDesc);
            boolean sameCodepoints = hasSameCodepointMultiset(normName, mName);
            int dist = boundedEditDistance(normName, mName, Math.max(4, normName.length()));
            int threshold = Math.max(3, normName.length() / 2);
            int score = Integer.MAX_VALUE;
            if (sameDesc && sameCodepoints) {
                score = dist;
            } else if (mName.equals(normName)) {
                score = 5 + boundedEditDistance(normDesc == null ? "" : normDesc, mDesc, 8);
            } else if (sameCodepoints) {
                score = 10 + dist;
            } else if (sameDesc) {
                score = 20 + Math.min(dist, 50);
            } else if (mName.equalsIgnoreCase(normName)) {
                score = 15;
            } else if (dist <= threshold) {
                score = 40 + dist;
            }
            if (score < 1_000) {
                scored.add(new ScoredSuggestion(mName + mDesc, score));
            }
        }
        emitSuggestions(target, scored);
    }

    private static void emitSuggestions(JsonObject target, List<ScoredSuggestion> scored) {
        if (scored.isEmpty()) {
            return;
        }
        scored.sort(Comparator.comparingInt(ScoredSuggestion::score)
                .thenComparing(ScoredSuggestion::value));
        JsonArray arr = new JsonArray();
        Set<String> seen = new LinkedHashSet<>();
        for (ScoredSuggestion item : scored) {
            if (seen.add(item.value())) {
                arr.add(item.value());
                if (seen.size() >= 5) {
                    break;
                }
            }
        }
        if (!arr.isEmpty()) {
            target.add("did_you_mean", arr);
        }
    }

    private static int similarityScore(String queryPkg,
                                       String querySimple,
                                       String candPkg,
                                       String candSimple) {
        boolean samePkg = queryPkg.equals(candPkg);
        if (samePkg && hasSameCodepointMultiset(querySimple, candSimple)) {
            return boundedEditDistance(querySimple, candSimple, 8);
        }
        if (samePkg && candSimple.equalsIgnoreCase(querySimple)) {
            return 5;
        }
        if (!samePkg && candSimple.equals(querySimple)) {
            return 12;
        }
        if (hasSameCodepointMultiset(querySimple, candSimple)) {
            return 15 + boundedEditDistance(queryPkg, candPkg, 10);
        }
        int maxDist = Math.max(2, Math.min(4, querySimple.length() / 2));
        if (Math.abs(querySimple.length() - candSimple.length()) > maxDist) {
            return Integer.MAX_VALUE;
        }
        int dist = boundedEditDistance(querySimple, candSimple, maxDist + 1);
        if (dist <= maxDist) {
            return (samePkg ? 20 : 50) + dist;
        }
        return Integer.MAX_VALUE;
    }

    private static String packagePrefix(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? "" : internalName.substring(0, slash);
    }

    private static String simpleName(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? internalName : internalName.substring(slash + 1);
    }

    private static boolean hasSameCodepointMultiset(String a, String b) {
        if (a.length() != b.length() || a.isEmpty()) {
            return false;
        }
        int[] cpA = a.codePoints().sorted().toArray();
        int[] cpB = b.codePoints().sorted().toArray();
        return java.util.Arrays.equals(cpA, cpB);
    }

    private static int boundedEditDistance(String a, String b, int limit) {
        int lenA = a.length();
        int lenB = b.length();
        if (Math.abs(lenA - lenB) > limit) {
            return limit + 1;
        }
        int[] prev = new int[lenB + 1];
        int[] curr = new int[lenB + 1];
        for (int j = 0; j <= lenB; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= lenA; i++) {
            curr[0] = i;
            int rowMin = curr[0];
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= lenB; j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(
                        Math.min(curr[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + cost
                );
                if (curr[j] < rowMin) {
                    rowMin = curr[j];
                }
            }
            if (rowMin > limit) {
                return limit + 1;
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[lenB];
    }

    private record ScoredSuggestion(String value, int score) {
    }

    static String outputScope(Scope scope, boolean decompiled) {
        return outputScope(scope, decompiled, null, false);
    }

    static String outputScope(Scope scope, boolean decompiled, Selection selection) {
        return outputScope(
                scope,
                decompiled,
                selection.resourcePath(),
                selection.includeEmbedded()
        );
    }

    static String outputScope(Scope scope,
                              boolean decompiled,
                              String resourcePath,
                              boolean includeEmbedded) {
        String prefix = resourcePath != null
                ? "embedded_"
                : includeEmbedded ? "primary_and_embedded_" : "primary_";
        String suffix = decompiled ? "_decompiled" : "";
        return switch (scope) {
            case JVM -> prefix + "jvm" + suffix;
            case ANDROID -> prefix + "android" + suffix;
            case ALL -> prefix + "all" + suffix;
        };
    }

    static JsonArray candidates(List<Entry> entries) {
        JsonArray candidates = new JsonArray();
        entries.forEach(entry -> {
            JsonObject candidate = new JsonObject();
            candidate.addProperty("class_name", entry.className());
            addHitIdentity(candidate, entry);
            candidates.add(candidate);
        });
        return candidates;
    }

    enum Scope {
        JVM("jvm"),
        ANDROID("android"),
        ALL("all");

        private final String value;

        Scope(String value) {
            this.value = value;
        }

        String value() {
            return value;
        }

        boolean includesJvm() {
            return this == JVM || this == ALL;
        }

        boolean includesAndroid() {
            return this == ANDROID || this == ALL;
        }

        static Scope from(String value) {
            for (Scope scope : values()) {
                if (scope.value.equals(value)) {
                    return scope;
                }
            }
            throw new IllegalArgumentException("scope has an unsupported value");
        }
    }

    record ScopedResource(String path, WorkspaceResource resource) {
        ScopedResource {
            Objects.requireNonNull(resource, "resource");
        }
    }

    record Selection(Scope scope,
                     String dexName,
                     boolean scopeExplicit,
                     String resourcePath,
                     boolean includeEmbedded) {
        Selection {
            Objects.requireNonNull(scope, "scope");
        }

        Selection(Scope scope, String dexName) {
            this(scope, dexName, true, null, false);
        }
    }

    record Entry(String className,
                 Scope scope,
                 String dexName,
                 String resourcePath,
                 WorkspaceResource resource,
                 ClassInfo classInfo) {
        Entry {
            Objects.requireNonNull(className, "className");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(classInfo, "classInfo");
        }

        Entry(String className, Scope scope, String dexName, ClassInfo classInfo) {
            this(className, scope, dexName, null, null, classInfo);
        }

        static Entry jvm(JvmClassInfo classInfo) {
            return new Entry(classInfo.getName(), Scope.JVM, null, null, null, classInfo);
        }

        static Entry jvm(String resourcePath,
                         WorkspaceResource resource,
                         JvmClassInfo classInfo) {
            return new Entry(
                    classInfo.getName(),
                    Scope.JVM,
                    null,
                    resourcePath,
                    resource,
                    classInfo
            );
        }

        static Entry android(String dexName, AndroidClassInfo classInfo) {
            return new Entry(
                    classInfo.getName(),
                    Scope.ANDROID,
                    dexName,
                    null,
                    null,
                    classInfo
            );
        }

        static Entry android(String dexName,
                             String resourcePath,
                             WorkspaceResource resource,
                             AndroidClassInfo classInfo) {
            return new Entry(
                    classInfo.getName(),
                    Scope.ANDROID,
                    dexName,
                    resourcePath,
                    resource,
                    classInfo
            );
        }
    }
}
