package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import software.coley.android.xml.XmlDecoder;
import software.coley.recaf.info.ArscFileInfo;
import software.coley.recaf.info.BinaryXmlFileInfo;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.info.TextFileInfo;
import software.coley.recaf.info.properties.builtin.BinaryXmlDecodedProperty;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.util.android.AndroidRes;
import software.coley.recaf.workspace.model.Workspace;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Reads a bounded text or Base64 page from one exact primary-resource file.
 */
public final class ReadFileTool implements McpTool {
    private static final int DEFAULT_LIMIT = 32 * 1024;
    private static final int MAX_LIMIT = 512 * 1024;
    private static final int MAX_PATH_LENGTH = 4_000;
    private static final Set<String> ARGUMENTS = Set.of(
            "path", "format", "offset", "limit", ConditionalReadSupport.ARGUMENT
    );
    private static final Set<String> FORMATS = Set.of("auto", "text", "base64");
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;

    public ReadFileTool(WorkspaceManager workspaceManager) {
        this(workspaceManager::hasCurrentWorkspace, workspaceManager::getCurrent);
    }

    ReadFileTool(BooleanSupplier hasWorkspace, Supplier<Workspace> workspaceSupplier) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "Read an archive file as text or Base64, including decoded Android XML.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        String path = ToolArguments.requiredString(arguments, "path");
        if (path.length() > MAX_PATH_LENGTH) {
            throw new IllegalArgumentException("path is too long");
        }
        String requestedFormat = ToolArguments.optionalString(arguments, "format");
        if (requestedFormat == null) {
            requestedFormat = "auto";
        } else if (!FORMATS.contains(requestedFormat)) {
            throw new IllegalArgumentException("format must be auto, text, or base64");
        }
        String ifSha256 = ConditionalReadSupport.parse(arguments);
        int offset = ToolArguments.integer(arguments, "offset", 0, 0, Integer.MAX_VALUE);
        int limit = ToolArguments.integer(
                arguments,
                "limit",
                DEFAULT_LIMIT,
                1,
                MAX_LIMIT
        );
        if (!hasWorkspace.getAsBoolean()) {
            return error("workspace not open", path, requestedFormat);
        }

        Workspace workspace = workspaceSupplier.get();
        FileToolSupport.Entry entry = FileToolSupport.exact(
                workspace.getPrimaryResource(),
                path
        );
        if (entry == null) {
            return error("file not found in primary resource", path, requestedFormat);
        }
        FileInfo fileInfo = entry.fileInfo();
        String selectedFormat = requestedFormat.equals("auto")
                ? FileToolSupport.isTextAvailable(fileInfo) ? "text" : "base64"
                : requestedFormat;
        if (selectedFormat.equals("text")
                && !FileToolSupport.isTextAvailable(fileInfo)) {
            JsonObject structured = errorMetadata(path, requestedFormat);
            FileToolSupport.addSummary(structured, entry);
            JsonArray available = new JsonArray();
            available.add("base64");
            structured.add("available_formats", available);
            structured.addProperty("error", "file is not text; use format=base64");
            return McpTool.errorResult(
                    "file is not text; use format=base64",
                    structured
            );
        }

        byte[] rawContent = fileInfo.getRawContent();
        JsonObject structured = new JsonObject();
        FileToolSupport.addSummary(structured, entry);
        structured.addProperty("scope", "primary_files");
        structured.addProperty("requested_format", requestedFormat);
        structured.addProperty("format", selectedFormat);
        String sha256 = FileToolSupport.sha256(rawContent);
        structured.addProperty("sha256", sha256);
        if (ConditionalReadSupport.matches(ifSha256, sha256)) {
            ConditionalReadSupport.markUnchanged(structured);
            return McpTool.successResult("file unchanged: " + path, structured);
        }
        structured.addProperty("offset", offset);
        structured.addProperty("limit", limit);
        if (selectedFormat.equals("text")) {
            try {
                addTextPage(structured, textContent(workspace, fileInfo), offset, limit);
            } catch (RuntimeException ex) {
                JsonObject error = errorMetadata(path, requestedFormat);
                FileToolSupport.addSummary(error, entry);
                error.addProperty("format", "text");
                error.addProperty("error", "failed to decode Android binary XML; "
                        + "use format=base64");
                error.addProperty("decode_error_type", ex.getClass().getSimpleName());
                JsonArray available = new JsonArray();
                available.add("base64");
                error.add("available_formats", available);
                return McpTool.errorResult(
                        "failed to decode Android binary XML; use format=base64",
                        error
                );
            }
        } else {
            addBase64Page(structured, rawContent, offset, limit);
        }
        return McpTool.successResult(
                "read " + structured.get("returned").getAsInt()
                        + " " + structured.get("offset_unit").getAsString()
                        + " from " + path,
                structured
        );
    }

    private static TextContent textContent(Workspace workspace, FileInfo fileInfo) {
        if (fileInfo.isTextFile()) {
            TextFileInfo textFileInfo = fileInfo.asTextFile();
            return new TextContent(
                    textFileInfo.getText(),
                    textFileInfo.getCharset().name(),
                    "recaf_text",
                    false
            );
        }
        if (fileInfo instanceof BinaryXmlFileInfo binaryXmlFileInfo) {
            String decoded = BinaryXmlDecodedProperty.get(binaryXmlFileInfo);
            if (decoded == null) {
                ArscFileInfo arscFileInfo = null;
                var arscPath = workspace.findFile(ArscFileInfo.NAME);
                if (arscPath != null && arscPath.getValue() instanceof ArscFileInfo value) {
                    arscFileInfo = value;
                }
                AndroidRes appResources = arscFileInfo == null
                        ? null
                        : arscFileInfo.getResourceInfo();
                decoded = XmlDecoder.decode(
                        binaryXmlFileInfo.getChunkModel(),
                        AndroidRes.getAndroidBase(),
                        appResources
                );
                BinaryXmlDecodedProperty.set(binaryXmlFileInfo, decoded);
            }
            return new TextContent(
                    decoded,
                    StandardCharsets.UTF_8.name(),
                    "recaf_android_binary_xml_decoder",
                    true
            );
        }
        throw new IllegalArgumentException("file has no text representation");
    }

    private static void addTextPage(JsonObject target,
                                    TextContent content,
                                    int offset,
                                    int limit) {
        String text = content.text();
        int start = Math.min(offset, text.length());
        int end = (int) Math.min((long) start + limit, text.length());
        target.addProperty("content_text", text.substring(start, end));
        target.addProperty("encoding", content.encoding());
        target.addProperty("text_source", content.source());
        target.addProperty("decoded", content.decoded());
        target.addProperty("offset_unit", "characters");
        target.addProperty("size_characters", text.length());
        target.addProperty("returned", end - start);
        target.addProperty("returned_characters", end - start);
        target.add("content_base64", JsonNull.INSTANCE);
        target.add("returned_bytes", JsonNull.INSTANCE);
        if (end < text.length()) {
            target.addProperty("next_offset", end);
        } else {
            target.add("next_offset", JsonNull.INSTANCE);
        }
    }

    private static void addBase64Page(JsonObject target,
                                      byte[] content,
                                      int offset,
                                      int limit) {
        int start = Math.min(offset, content.length);
        int end = (int) Math.min((long) start + limit, content.length);
        byte[] page = Arrays.copyOfRange(content, start, end);
        target.addProperty(
                "content_base64",
                Base64.getEncoder().encodeToString(page)
        );
        target.addProperty("encoding", "base64");
        target.add("text_source", JsonNull.INSTANCE);
        target.addProperty("decoded", false);
        target.addProperty("offset_unit", "bytes");
        target.add("size_characters", JsonNull.INSTANCE);
        target.addProperty("returned", page.length);
        target.addProperty("returned_bytes", page.length);
        target.add("content_text", JsonNull.INSTANCE);
        target.add("returned_characters", JsonNull.INSTANCE);
        if (end < content.length) {
            target.addProperty("next_offset", end);
        } else {
            target.add("next_offset", JsonNull.INSTANCE);
        }
    }

    private static JsonObject error(String message,
                                    String path,
                                    String requestedFormat) {
        JsonObject structured = errorMetadata(path, requestedFormat);
        structured.addProperty("error", message);
        return McpTool.errorResult(message, structured);
    }

    private static JsonObject errorMetadata(String path, String requestedFormat) {
        JsonObject structured = new JsonObject();
        structured.addProperty("path", path);
        structured.addProperty("requested_format", requestedFormat);
        structured.addProperty("scope", "primary_files");
        return structured;
    }

    private static JsonObject createInputSchema() {
        JsonObject properties = new JsonObject();
        JsonObject path = new JsonObject();
        path.addProperty("type", "string");
        path.addProperty("minLength", 1);
        path.addProperty("maxLength", MAX_PATH_LENGTH);
        path.addProperty("description", "Exact path returned by list_files.");
        properties.add("path", path);

        JsonObject format = new JsonObject();
        format.addProperty("type", "string");
        JsonArray formats = new JsonArray();
        formats.add("auto");
        formats.add("text");
        formats.add("base64");
        format.add("enum", formats);
        format.addProperty("default", "auto");
        format.addProperty(
                "description",
                "auto: text or decoded Android XML when available; base64: raw bytes."
        );
        properties.add("format", format);
        properties.add("offset", integerProperty(0, Integer.MAX_VALUE, 0));
        properties.add("limit", integerProperty(1, MAX_LIMIT, DEFAULT_LIMIT));
        properties.add(
                ConditionalReadSupport.ARGUMENT,
                ConditionalReadSupport.schemaProperty()
        );

        JsonArray required = new JsonArray();
        required.add("path");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty(
                "description",
                "Reads only open-workspace archive files, never host paths."
        );
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    private static JsonObject integerProperty(int minimum,
                                              int maximum,
                                              int defaultValue) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "integer");
        property.addProperty("minimum", minimum);
        property.addProperty("maximum", maximum);
        property.addProperty("default", defaultValue);
        return property;
    }

    private record TextContent(String text,
                               String encoding,
                               String source,
                               boolean decoded) {
        private TextContent {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(encoding, "encoding");
            Objects.requireNonNull(source, "source");
        }
    }
}
