package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.decompile.JvmDecompiler;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Shared JVM decompiler selection and schema helpers.
 */
final class DecompilerToolSupport {
    private DecompilerToolSupport() {
    }

    static JvmDecompiler select(DecompilerManager manager, String requested) {
        if (requested == null) {
            return manager.getTargetJvmDecompiler();
        }
        return manager.getJvmDecompilers().stream()
                .filter(decompiler -> decompiler.getName().equalsIgnoreCase(requested))
                .findFirst()
                .orElse(null);
    }

    static List<Descriptor> descriptors(DecompilerManager manager) {
        JvmDecompiler defaultDecompiler = manager.getTargetJvmDecompiler();
        return manager.getJvmDecompilers().stream()
                .sorted(Comparator.comparing(
                        JvmDecompiler::getName,
                        String.CASE_INSENSITIVE_ORDER
                ))
                .map(decompiler -> new Descriptor(
                        decompiler.getName(),
                        decompiler.getVersion(),
                        defaultDecompiler != null
                                && decompiler.getName().equalsIgnoreCase(
                                        defaultDecompiler.getName()
                                )
                ))
                .toList();
    }

    static JsonObject inputSchemaProperty(List<Descriptor> descriptors) {
        JsonObject decompiler = new JsonObject();
        decompiler.addProperty("type", "string");
        decompiler.addProperty("minLength", 1);
        if (!descriptors.isEmpty()) {
            JsonArray values = new JsonArray();
            descriptors.forEach(item -> values.add(item.name()));
            decompiler.add("enum", values);
            descriptors.stream()
                    .filter(Descriptor::isDefault)
                    .findFirst()
                    .ifPresent(item -> decompiler.addProperty("default", item.name()));
        }
        decompiler.addProperty(
                "description",
                "Installed JVM decompiler; Android classes are converted first."
        );
        return decompiler;
    }

    static void addAvailability(JsonObject target, List<Descriptor> descriptors) {
        JsonArray available = new JsonArray();
        descriptors.forEach(item -> {
            JsonObject descriptor = new JsonObject();
            descriptor.addProperty("name", item.name());
            descriptor.addProperty("version", item.version());
            descriptor.addProperty("default", item.isDefault());
            available.add(descriptor);
        });
        target.add("available_decompilers", available);
        descriptors.stream()
                .filter(Descriptor::isDefault)
                .findFirst()
                .ifPresentOrElse(
                        item -> target.addProperty("default_decompiler", item.name()),
                        () -> target.add("default_decompiler", JsonNull.INSTANCE)
                );
    }

    record Descriptor(String name, String version, boolean isDefault) {
        Descriptor {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(version, "version");
        }
    }
}
