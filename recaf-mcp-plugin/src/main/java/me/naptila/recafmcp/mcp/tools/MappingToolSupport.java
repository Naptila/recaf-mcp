package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import software.coley.recaf.services.mapping.IntermediateMappings;
import software.coley.recaf.services.mapping.format.MappingFileFormat;
import software.coley.recaf.services.mapping.format.MappingFormatManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Shared Recaf mapping-format discovery and result metadata helpers.
 */
final class MappingToolSupport {
    static final int MAX_MAPPING_TEXT_LENGTH = 750_000;

    private MappingToolSupport() {
    }

    static List<Descriptor> descriptors(MappingFormatManager manager) {
        return manager.getMappingFileFormats().stream()
                .map(manager::createFormatInstance)
                .filter(Objects::nonNull)
                .map(format -> new Descriptor(
                        format.implementationName(),
                        format.supportsExportText(),
                        format.doesSupportFieldTypeDifferentiation(),
                        format.doesSupportVariableTypeDifferentiation()
                ))
                .toList();
    }

    static Descriptor descriptor(List<Descriptor> descriptors, String requested) {
        return descriptors.stream()
                .filter(item -> item.name().equalsIgnoreCase(requested))
                .findFirst()
                .orElse(null);
    }

    static JsonObject formatProperty(List<Descriptor> descriptors) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "string");
        property.addProperty("minLength", 1);
        if (!descriptors.isEmpty()) {
            JsonArray values = new JsonArray();
            descriptors.forEach(item -> values.add(item.name()));
            property.add("enum", values);
        }
        property.addProperty(
                "description",
                "Installed Recaf mapping format."
        );
        return property;
    }

    static void addFormatMetadata(JsonObject target,
                                  Descriptor selected,
                                  List<Descriptor> descriptors) {
        target.addProperty("format", selected.name());
        target.addProperty("format_supports_export", selected.supportsExport());
        target.addProperty(
                "format_field_type_differentiation",
                selected.fieldTypeDifferentiation()
        );
        target.addProperty(
                "format_variable_type_differentiation",
                selected.variableTypeDifferentiation()
        );
        JsonArray available = new JsonArray();
        descriptors.forEach(item -> {
            JsonObject format = new JsonObject();
            format.addProperty("name", item.name());
            format.addProperty("supports_export", item.supportsExport());
            format.addProperty(
                    "field_type_differentiation",
                    item.fieldTypeDifferentiation()
            );
            format.addProperty(
                    "variable_type_differentiation",
                    item.variableTypeDifferentiation()
            );
            available.add(format);
        });
        target.add("available_formats", available);
    }

    static MappingCounts counts(IntermediateMappings mappings) {
        return new MappingCounts(
                mappings.getClasses().size(),
                count(mappings.getFields().values()),
                count(mappings.getMethods().values()),
                count(mappings.getVariables().values())
        );
    }

    static void addCounts(JsonObject target, MappingCounts counts) {
        JsonObject mappings = new JsonObject();
        mappings.addProperty("classes", counts.classes());
        mappings.addProperty("fields", counts.fields());
        mappings.addProperty("methods", counts.methods());
        mappings.addProperty("variables", counts.variables());
        mappings.addProperty("total", counts.total());
        target.add("mapping_counts", mappings);
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(text.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    static String exceptionMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        String singleLine = message.replace('\n', ' ').replace('\r', ' ').strip();
        return singleLine.length() <= 500
                ? singleLine
                : singleLine.substring(0, 500);
    }

    private static int count(Collection<? extends Collection<?>> values) {
        return values.stream().mapToInt(Collection::size).sum();
    }

    record Descriptor(String name,
                      boolean supportsExport,
                      boolean fieldTypeDifferentiation,
                      boolean variableTypeDifferentiation) {
        Descriptor {
            Objects.requireNonNull(name, "name");
        }
    }

    record MappingCounts(int classes, int fields, int methods, int variables) {
        int total() {
            return classes + fields + methods + variables;
        }

        boolean isEmpty() {
            return total() == 0;
        }
    }
}
