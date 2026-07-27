package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import software.coley.recaf.info.AndroidClassInfo;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.workspace.model.bundle.AndroidClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Shared primary-resource class scope and multi-DEX identity helpers.
 */
final class ClassToolSupport {
    static final String SCOPE_ARGUMENT = "scope";
    static final String DEX_NAME_ARGUMENT = "dex_name";
    private static final Comparator<Entry> ENTRY_ORDER = Comparator
            .comparing(Entry::className)
            .thenComparing(entry -> entry.scope().value())
            .thenComparing(Entry::dexName, Comparator.nullsFirst(String::compareTo));

    private ClassToolSupport() {
    }

    static Selection parseSelection(JsonObject arguments, boolean allowAll) {
        String value = ToolArguments.optionalString(arguments, SCOPE_ARGUMENT);
        Scope scope = value == null ? Scope.JVM : Scope.from(value);
        if (!allowAll && scope == Scope.ALL) {
            throw new IllegalArgumentException("scope=all is not supported for exact class lookup");
        }
        String dexName = ToolArguments.optionalString(arguments, DEX_NAME_ARGUMENT);
        if (dexName != null && scope != Scope.ANDROID) {
            throw new IllegalArgumentException("dex_name requires scope=android");
        }
        return new Selection(scope, dexName);
    }

    static List<Entry> entries(WorkspaceResource resource, Selection selection) {
        List<Entry> entries = new ArrayList<>();
        if (selection.scope().includesJvm()) {
            resource.getJvmClassBundle().values().forEach(classInfo ->
                    entries.add(Entry.jvm(classInfo)));
        }
        if (selection.scope().includesAndroid()) {
            for (Map.Entry<String, AndroidClassBundle> bundleEntry
                    : resource.getAndroidClassBundles().entrySet()) {
                if (selection.dexName() != null
                        && !selection.dexName().equals(bundleEntry.getKey())) {
                    continue;
                }
                bundleEntry.getValue().values().forEach(classInfo ->
                        entries.add(Entry.android(bundleEntry.getKey(), classInfo)));
            }
        }
        entries.sort(ENTRY_ORDER);
        return entries;
    }

    static List<Entry> exact(WorkspaceResource resource,
                             Selection selection,
                             String className) {
        return entries(resource, selection).stream()
                .filter(entry -> entry.className().equals(className))
                .toList();
    }

    static Entry identify(WorkspaceResource resource, ClassInfo classInfo) {
        if (classInfo.isJvmClass()
                && resource.getJvmClassBundle().get(classInfo.getName()) == classInfo) {
            return Entry.jvm(classInfo.asJvmClass());
        }
        if (classInfo.isAndroidClass()) {
            for (Map.Entry<String, AndroidClassBundle> bundle
                    : resource.getAndroidClassBundles().entrySet()) {
                if (bundle.getValue().get(classInfo.getName()) == classInfo) {
                    return Entry.android(bundle.getKey(), classInfo.asAndroidClass());
                }
            }
        }
        return null;
    }

    static void addScopeProperties(JsonObject properties, boolean allowAll) {
        addScopeProperty(properties, allowAll);

        JsonObject dexName = new JsonObject();
        dexName.addProperty("type", "string");
        dexName.addProperty("minLength", 1);
        dexName.addProperty(
                "description",
                "Exact Android DEX name, for example classes2.dex."
        );
        properties.add(DEX_NAME_ARGUMENT, dexName);
    }

    static void addScopeProperty(JsonObject properties, boolean allowAll) {
        JsonObject scope = new JsonObject();
        scope.addProperty("type", "string");
        JsonArray values = new JsonArray();
        values.add(Scope.JVM.value());
        values.add(Scope.ANDROID.value());
        if (allowAll) {
            values.add(Scope.ALL.value());
        }
        scope.add("enum", values);
        scope.addProperty("default", Scope.JVM.value());
        scope.addProperty(
                "description",
                "Class domain."
        );
        properties.add(SCOPE_ARGUMENT, scope);
    }

    static void addIdentity(JsonObject target, Entry entry) {
        target.addProperty("class_scope", entry.scope().value());
        target.addProperty("scope", outputScope(entry.scope(), false));
        if (entry.dexName() == null) {
            target.add("dex_name", JsonNull.INSTANCE);
        } else {
            target.addProperty("dex_name", entry.dexName());
        }
    }

    static void addHitIdentity(JsonObject target, Entry entry) {
        target.addProperty("class_scope", entry.scope().value());
        if (entry.dexName() == null) {
            target.add("dex_name", JsonNull.INSTANCE);
        } else {
            target.addProperty("dex_name", entry.dexName());
        }
    }

    static String outputScope(Scope scope, boolean decompiled) {
        String suffix = decompiled ? "_decompiled" : "";
        return switch (scope) {
            case JVM -> "primary_jvm" + suffix;
            case ANDROID -> "primary_android" + suffix;
            case ALL -> "primary_all" + suffix;
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

    record Selection(Scope scope, String dexName) {
        Selection {
            Objects.requireNonNull(scope, "scope");
        }
    }

    record Entry(String className,
                 Scope scope,
                 String dexName,
                 ClassInfo classInfo) {
        Entry {
            Objects.requireNonNull(className, "className");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(classInfo, "classInfo");
        }

        static Entry jvm(JvmClassInfo classInfo) {
            return new Entry(classInfo.getName(), Scope.JVM, null, classInfo);
        }

        static Entry android(String dexName, AndroidClassInfo classInfo) {
            return new Entry(classInfo.getName(), Scope.ANDROID, dexName, classInfo);
        }
    }
}
