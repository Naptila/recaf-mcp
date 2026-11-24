package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.Hashing;
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
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Reads a bounded text or Base64 page from one exact primary or embedded file.
 */
public final class ReadFileTool implements McpTool {
    private static final int DEFAULT_LIMIT = 32 * 1024;
    private static final int MAX_LIMIT = 512 * 1024;
    private static final int MAX_PATH_LENGTH = 4_000;
    private static final Set<String> ARGUMENTS = Set.of(
            "path",
            "format",
            ClassToolSupport.RESOURCE_ARGUMENT,
            "offset",
            "limit",
            ConditionalReadSupport.ARGUMENT
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
        String resourcePath = ToolArguments.optionalString(
                arguments,
                ClassToolSupport.RESOURCE_ARGUMENT
        );
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
            return error("workspace not open", path, requestedFormat, resourcePath);
        }

        Workspace workspace = workspaceSupplier.get();
        WorkspaceResource primary = workspace.getPrimaryResource();
        if (resourcePath != null
                && ClassToolSupport.resolveEmbeddedResource(primary, resourcePath) == null) {
            String message = "embedded resource not found: " + resourcePath;
            JsonObject structured = errorMetadata(path, requestedFormat, resourcePath);
            structured.addProperty("error", message);
            JsonArray available = new JsonArray();
            ClassToolSupport.embeddedResources(primary).stream()
                    .map(ClassToolSupport.ScopedResource::path)
                    .limit(200)
                    .forEach(available::add);
            structured.add("available_embedded_resources", available);
            return McpTool.errorResult(message, structured);
        }

        FileToolSupport.Entry entry = FileToolSupport.exact(
                primary,
                resourcePath,
                path
        );
        if (entry == null) {
            String candidateMountedPath = resourcePath == null
                    ? path
                    : resourcePath + "!/" + path;
            WorkspaceResource embeddedMatch = ClassToolSupport.resolveEmbeddedResource(
                    primary,
                    candidateMountedPath
            );
            if (embeddedMatch instanceof software.coley.recaf.workspace.model.resource.WorkspaceFileResource embeddedFile) {
                String hint = "Path names a mounted embedded archive; pass resource=\""
                        + candidateMountedPath + "\" to list_files or read_file entries inside it, or "
                        + "format=\"base64\" to read raw archive bytes.";
                byte[] embeddedBytes = embeddedFile.getFileInfo().getRawContent();
                if ("base64".equals(requestedFormat)
                        && embeddedBytes != null
                        && embeddedBytes.length > 0) {
                    String rawSha256 = FileToolSupport.sha256(embeddedBytes);
                    JsonObject structured = new JsonObject();
                    structured.addProperty("path", path);
                    if (resourcePath != null) {
                        structured.addProperty("resource", resourcePath);
                    }
                    structured.addProperty("mounted_resource", candidateMountedPath);
                    structured.addProperty("size_bytes", embeddedBytes.length);
                    structured.addProperty(
                            "scope",
                            resourcePath == null ? "primary_embedded_archive" : "nested_embedded_archive"
                    );
                    structured.addProperty("embedded_archive", true);
                    structured.addProperty("embedded_resource_hint", hint);
                    structured.addProperty("requested_format", requestedFormat);
                    structured.addProperty("format", "base64");
                    structured.addProperty("raw_sha256", rawSha256);
                    structured.addProperty("content_sha256", rawSha256);
                    structured.addProperty("sha256", rawSha256);
                    if (ConditionalReadSupport.matches(ifSha256, rawSha256)) {
                        structured.addProperty("decoded", false);
                        structured.addProperty("encoding", "base64");
                        ConditionalReadSupport.markUnchanged(structured);
                        return McpTool.successResult("file unchanged: " + candidateMountedPath, structured);
                    }
                    structured.addProperty("offset", offset);
                    structured.addProperty("limit", limit);
                    addBase64Page(structured, embeddedBytes, offset, limit);
                    return McpTool.successResult(
                            "read " + structured.get("returned").getAsInt()
                                    + " bytes from embedded archive " + candidateMountedPath,
                            structured
                    );
                }
                String message = "path is a mounted embedded archive; pass resource=\""
                        + candidateMountedPath + "\" to inspect its contents, or format=\"base64\" for raw bytes";
                JsonObject structured = errorMetadata(path, requestedFormat, resourcePath);
                structured.addProperty("mounted_resource", candidateMountedPath);
                structured.addProperty("is_embedded_resource", true);
                structured.addProperty("embedded_resource_hint", hint);
                structured.addProperty("error", message);
                return McpTool.errorResult(message, structured);
            }
            return error(
                    resourcePath == null
                            ? "file not found in primary resource"
                            : "file not found in embedded resource",
                    path,
                    requestedFormat,
                    resourcePath
            );
        }
        FileInfo fileInfo = entry.fileInfo();
        String selectedFormat = requestedFormat.equals("auto")
                ? FileToolSupport.isTextAvailable(fileInfo) ? "text" : "base64"
                : requestedFormat;
        if (selectedFormat.equals("text")
                && !FileToolSupport.isTextAvailable(fileInfo)) {
            JsonObject structured = errorMetadata(path, requestedFormat, resourcePath);
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
        String rawSha256 = FileToolSupport.sha256(rawContent);
        JsonObject structured = new JsonObject();
        FileToolSupport.addSummary(structured, entry);
        structured.addProperty(
                "scope",
                resourcePath == null ? "primary_files" : "embedded_files"
        );
        structured.addProperty("requested_format", requestedFormat);
        structured.addProperty("format", selectedFormat);
        structured.addProperty("raw_sha256", rawSha256);

        if (selectedFormat.equals("text")) {
            TextContent content;
            try {
                content = textContent(workspace, fileInfo);
            } catch (RuntimeException ex) {
                JsonObject error = errorMetadata(path, requestedFormat, resourcePath);
                FileToolSupport.addSummary(error, entry);
                error.addProperty("format", "text");
                error.addProperty("raw_sha256", rawSha256);
                String failMsg = fileInfo instanceof ArscFileInfo
                        ? "failed to decode Android resources.arsc table; use format=base64"
                        : "failed to decode Android binary XML; use format=base64";
                error.addProperty("error", failMsg);
                error.addProperty("decode_error_type", ex.getClass().getSimpleName());
                JsonArray available = new JsonArray();
                available.add("base64");
                error.add("available_formats", available);
                return McpTool.errorResult(failMsg, error);
            }
            String contentSha256 = Hashing.sha256(content.text());
            structured.addProperty("content_sha256", contentSha256);
            structured.addProperty("sha256", contentSha256);
            if (ConditionalReadSupport.matches(ifSha256, contentSha256)) {
                structured.addProperty("decoded", content.decoded());
                structured.addProperty("text_source", content.source());
                structured.addProperty("encoding", content.encoding());
                structured.addProperty("size_characters", content.text().length());
                ConditionalReadSupport.markUnchanged(structured);
                return McpTool.successResult("file unchanged: " + path, structured);
            }
            structured.addProperty("offset", offset);
            structured.addProperty("limit", limit);
            addTextPage(structured, content, offset, limit);
        } else {
            structured.addProperty("content_sha256", rawSha256);
            structured.addProperty("sha256", rawSha256);
            if (ConditionalReadSupport.matches(ifSha256, rawSha256)) {
                structured.addProperty("decoded", false);
                structured.addProperty("encoding", "base64");
                ConditionalReadSupport.markUnchanged(structured);
                return McpTool.successResult("file unchanged: " + path, structured);
            }
            structured.addProperty("offset", offset);
            structured.addProperty("limit", limit);
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
        if (fileInfo instanceof ArscFileInfo arscFileInfo) {
            return new TextContent(
                    decodeArscSummary(arscFileInfo),
                    StandardCharsets.UTF_8.name(),
                    "recaf_android_arsc_decoder",
                    true
            );
        }
        throw new IllegalArgumentException("file has no text representation");
    }

    private static String decodeArscSummary(ArscFileInfo arscFileInfo) {
        StringBuilder out = new StringBuilder(4096);
        out.append("# Android Resource Table (resources.arsc)\n");
        var model = arscFileInfo.getChunkModel();
        com.google.devrel.gmscore.tools.apk.arsc.StringPoolChunk globalPool = null;
        if (model != null) {
            for (var chunk : model.getChunks()) {
                if (chunk instanceof com.google.devrel.gmscore.tools.apk.arsc.ResourceTableChunk tableChunk) {
                    globalPool = tableChunk.getStringPool();
                    var packages = tableChunk.getPackages();
                    out.append("packages: ").append(packages.size()).append('\n');
                    for (var pkg : packages) {
                        out.append("package 0x")
                                .append(Integer.toHexString(pkg.getId()))
                                .append(": ")
                                .append(pkg.getPackageName())
                                .append('\n');
                    }
                }
            }
        }
        AndroidRes res = arscFileInfo.getResourceInfo();
        java.util.List<AndroidRes.ResourceEntry> entries = new java.util.ArrayList<>();
        if (res != null && res.getEntriesByType() != null) {
            res.getEntriesByType().values().forEach(entries::addAll);
        }
        entries.sort(java.util.Comparator.comparingInt(AndroidRes.ResourceEntry::id));
        out.append("resource_entries: ").append(entries.size()).append('\n');
        if (!entries.isEmpty()) {
            out.append("\n## Entries\n");
            for (AndroidRes.ResourceEntry item : entries) {
                out.append(String.format("0x%08x @%s/%s", item.id(), item.type(), item.name()));
                if (item.stringValue() != null) {
                    out.append(" = \"").append(escapeSingleLine(item.stringValue())).append('"');
                } else if (item.resourcePath() != null) {
                    out.append(" -> ").append(item.resourcePath());
                } else if (item.simpleValue() != null) {
                    out.append(" = ").append(item.simpleValue().type())
                            .append("(0x")
                            .append(Integer.toHexString(item.simpleValue().data()))
                            .append(')');
                } else if (item.isComplex() && item.complexValues() != null) {
                    out.append(" [bag:").append(item.complexValues().size()).append(" items]");
                }
                out.append('\n');
            }
        } else if (globalPool != null && globalPool.getStringCount() > 0) {
            int count = globalPool.getStringCount();
            int cap = Math.min(count, 2000);
            out.append("string_pool_count: ").append(count).append("\n\n## String Pool\n");
            for (int i = 0; i < cap; i++) {
                out.append('[').append(i).append("] \"")
                        .append(escapeSingleLine(globalPool.getString(i)))
                        .append("\"\n");
            }
            if (count > cap) {
                out.append("... (").append(count - cap).append(" more strings)\n");
            }
        }
        return out.toString();
    }

    private static String escapeSingleLine(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
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
                                    String requestedFormat,
                                    String resourcePath) {
        JsonObject structured = errorMetadata(path, requestedFormat, resourcePath);
        structured.addProperty("error", message);
        return McpTool.errorResult(message, structured);
    }

    private static JsonObject errorMetadata(String path,
                                            String requestedFormat,
                                            String resourcePath) {
        JsonObject structured = new JsonObject();
        structured.addProperty("path", path);
        structured.addProperty("requested_format", requestedFormat);
        structured.addProperty(
                "scope",
                resourcePath == null ? "primary_files" : "embedded_files"
        );
        if (resourcePath != null) {
            structured.addProperty("resource", resourcePath);
        }
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
        ClassToolSupport.addResourceProperty(properties);
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
