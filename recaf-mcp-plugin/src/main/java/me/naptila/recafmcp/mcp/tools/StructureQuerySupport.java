package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import me.darknet.dex.tree.definitions.instructions.Instruction;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import software.coley.recaf.services.search.match.AccessFlagMatcher;
import software.coley.recaf.services.search.match.Matcher;
import software.coley.recaf.services.search.match.Matchers;
import software.coley.recaf.services.search.query.structure.ClassQuery;
import software.coley.recaf.services.search.query.structure.ClassQueryBuilder;
import software.coley.recaf.services.search.query.structure.FieldMatcher;
import software.coley.recaf.services.search.query.structure.FieldMatcherBuilder;
import software.coley.recaf.services.search.query.structure.InsnMatcher;
import software.coley.recaf.services.search.query.structure.ListMatcher;
import software.coley.recaf.services.search.query.structure.MethodMatcher;
import software.coley.recaf.services.search.query.structure.MethodMatcherBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict JSON adapter for Recaf's backend-neutral structural queries. */
final class StructureQuerySupport {
    private static final int MAX_MEMBERS = 8;
    private static final int MAX_OPCODES = 32;
    private static final Set<String> CLASS_KEYS = Set.of(
            "name", "regex", "access_required", "access_forbidden", "fields", "methods");
    private static final Set<String> FIELD_KEYS = Set.of(
            "name", "regex", "descriptor", "access_required", "access_forbidden");
    private static final Set<String> METHOD_KEYS = Set.of(
            "name", "regex", "descriptor", "access_required", "access_forbidden", "opcodes");

    private StructureQuerySupport() {}

    static ClassQuery parse(JsonObject query) {
        ToolArguments.rejectUnknown(query, CLASS_KEYS);
        if (query.isEmpty()) {
            throw new IllegalArgumentException("query must contain a structural constraint");
        }
        var builder = new ClassQueryBuilder().name(name(query)).accessFlags(access(query));
        if (query.has("fields")) {
            List<FieldMatcher> fields = new ArrayList<>();
            for (JsonElement value : array(query, "fields", MAX_MEMBERS)) {
                JsonObject field = object(value, "field");
                ToolArguments.rejectUnknown(field, FIELD_KEYS);
                var fieldBuilder = new FieldMatcherBuilder().name(name(field)).accessFlags(access(field));
                String descriptor = ToolArguments.optionalString(field, "descriptor");
                if (descriptor != null) {
                    fieldBuilder.type(fieldType(descriptor));
                }
                fields.add(fieldBuilder.build());
            }
            builder.fields(ListMatcher.bag(fields));
        }
        if (query.has("methods")) {
            List<MethodMatcher> methods = new ArrayList<>();
            for (JsonElement value : array(query, "methods", MAX_MEMBERS)) {
                JsonObject method = object(value, "method");
                ToolArguments.rejectUnknown(method, METHOD_KEYS);
                var methodBuilder = new MethodMatcherBuilder().name(name(method)).accessFlags(access(method));
                String descriptor = ToolArguments.optionalString(method, "descriptor");
                if (descriptor != null) {
                    Type type = methodType(descriptor);
                    methodBuilder.returnType(type.getReturnType()).exactParameters(type.getArgumentTypes());
                }
                MethodMatcher matcher = methodBuilder.build();
                if (method.has("opcodes")) {
                    List<InsnMatcher> instructions = new ArrayList<>();
                    for (JsonElement opcode : array(method, "opcodes", MAX_OPCODES)) {
                        JsonObject argument = new JsonObject();
                        argument.add("opcode", opcode);
                        instructions.add(new NativeOpcode(ToolArguments.integer(argument, "opcode", 0, 0, 255)));
                    }
                    matcher = new MethodMatcher(matcher.name(), matcher.returnType(), matcher.accessFlags(),
                            matcher.parameterTypes(), matcher.annotations(), matcher.exceptions(),
                            matcher.tryCatchBlocks(), ListMatcher.bag(instructions), matcher.debugInfo());
                }
                methods.add(matcher);
            }
            builder.methods(ListMatcher.bag(methods));
        }
        return builder.build();
    }

    private static Matcher<String> name(JsonObject object) {
        String name = ToolArguments.optionalString(object, "name");
        boolean regex = ToolArguments.bool(object, "regex", false);
        if (name == null) {
            if (regex) {
                throw new IllegalArgumentException("regex requires name");
            }
            return Matchers.any();
        }
        if (name.length() > SearchToolSupport.MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("name is too long");
        }
        if (!regex) {
            return Matchers.exact(name);
        }
        SearchToolSupport.validateRegex(name, "name");
        Pattern pattern = Pattern.compile(name);
        return candidate -> candidate != null && pattern.matcher(candidate).find();
    }

    private static AccessFlagMatcher access(JsonObject object) {
        return new AccessFlagMatcher(
                ToolArguments.integer(object, "access_required", 0, 0, 65535),
                ToolArguments.integer(object, "access_forbidden", 0, 0, 65535));
    }

    private static Type fieldType(String descriptor) {
        try {
            Type type = Type.getType(descriptor);
            if (descriptor.length() > 4000 || type.getSort() == Type.VOID || type.getSort() == Type.METHOD
                    || !type.getDescriptor().equals(descriptor) || !validType(type, false)) {
                throw new IllegalArgumentException();
            }
            return type;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("invalid field descriptor", ex);
        }
    }

    private static Type methodType(String descriptor) {
        try {
            Type type = Type.getMethodType(descriptor);
            Type[] arguments = type.getArgumentTypes();
            Type returned = type.getReturnType();
            if (descriptor.length() > 4000 || !validType(returned, true)
                    || !Type.getMethodDescriptor(returned, arguments).equals(descriptor)) {
                throw new IllegalArgumentException();
            }
            for (Type argument : arguments) {
                if (!validType(argument, false)) {
                    throw new IllegalArgumentException();
                }
            }
            return type;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("invalid method descriptor", ex);
        }
    }

    private static boolean validType(Type type, boolean allowVoid) {
        String descriptor = type.getDescriptor();
        if (type.getSort() == Type.ARRAY) {
            return type.getDimensions() <= 255 && validType(type.getElementType(), false);
        }
        if (type.getSort() == Type.OBJECT) {
            return descriptor.startsWith("L") && descriptor.endsWith(";") && descriptor.length() > 2
                    && !type.getInternalName().contains(";") && !type.getInternalName().contains("[");
        }
        return descriptor.length() == 1 && ("ZCBSIFJD".contains(descriptor)
                || allowVoid && "V".equals(descriptor));
    }

    private static JsonArray array(JsonObject object, String name, int maximum) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() > maximum) {
            throw new IllegalArgumentException(name + " must be an array with at most " + maximum + " entries");
        }
        return value.getAsJsonArray();
    }

    static JsonObject object(JsonElement value, String name) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        return value.getAsJsonObject();
    }

    static JsonObject schema() {
        JsonObject field = memberSchema(false);
        JsonObject method = memberSchema(true);
        JsonObject properties = commonProperties();
        properties.add("fields", arraySchema(field, MAX_MEMBERS));
        properties.add("methods", arraySchema(method, MAX_MEMBERS));
        JsonObject schema = objectSchema(properties);
        schema.addProperty("minProperties", 1);
        schema.addProperty("description", "Class constraints are ANDed. Member arrays require distinct matching "
                + "members; [] requires none. Names are exact unless regex=true. Opcodes use native numeric values.");
        return schema;
    }

    private static JsonObject memberSchema(boolean method) {
        JsonObject properties = commonProperties();
        JsonObject descriptor = new JsonObject();
        descriptor.addProperty("type", "string");
        descriptor.addProperty("minLength", 1);
        descriptor.addProperty("maxLength", 4000);
        descriptor.addProperty("description", "Exact JVM-style descriptor, also used for Android members.");
        properties.add("descriptor", descriptor);
        if (method) {
            JsonObject opcode = integerSchema(0, 255);
            JsonObject opcodes = arraySchema(opcode, MAX_OPCODES);
            opcodes.addProperty("description", "Unordered required native opcodes in this method, including "
                    + "multiplicity. [] requires no instructions. JVM and DEX numbers differ; choose scope explicitly.");
            properties.add("opcodes", opcodes);
        }
        return objectSchema(properties);
    }

    private static JsonObject commonProperties() {
        JsonObject properties = new JsonObject();
        JsonObject name = new JsonObject();
        name.addProperty("type", "string");
        name.addProperty("minLength", 1);
        name.addProperty("maxLength", SearchToolSupport.MAX_QUERY_LENGTH);
        properties.add("name", name);
        JsonObject regex = new JsonObject();
        regex.addProperty("type", "boolean");
        regex.addProperty("default", false);
        properties.add("regex", regex);
        properties.add("access_required", integerSchema(0, 65535));
        properties.add("access_forbidden", integerSchema(0, 65535));
        return properties;
    }

    private static JsonObject objectSchema(JsonObject properties) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    private static JsonObject arraySchema(JsonObject item, int maximum) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "array");
        schema.add("items", item);
        schema.addProperty("maxItems", maximum);
        return schema;
    }

    private static JsonObject integerSchema(int minimum, int maximum) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "integer");
        schema.addProperty("minimum", minimum);
        schema.addProperty("maximum", maximum);
        return schema;
    }

    private record NativeOpcode(int opcode) implements InsnMatcher {
        @Override
        public boolean matchesJvm(AbstractInsnNode node) {
            return node.getOpcode() == opcode;
        }

        @Override
        public boolean matchesAndroid(Instruction instruction) {
            return instruction.opcode() == opcode;
        }
    }
}
