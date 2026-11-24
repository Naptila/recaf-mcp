package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.android.DeferredFileInfo;
import me.naptila.recafmcp.mcp.util.Hashing;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import software.coley.recaf.info.ArscFileInfo;
import software.coley.recaf.info.BinaryXmlFileInfo;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.info.TextFileInfo;
import software.coley.recaf.info.builder.BinaryXmlFileInfoBuilder;
import software.coley.recaf.info.builder.FileInfoBuilder;
import software.coley.recaf.info.builder.TextFileInfoBuilder;
import software.coley.recaf.info.properties.builtin.BinaryXmlDecodedProperty;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.FileBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Creates, replaces, patches, or deletes non-class archive files in the primary resource.
 */
public final class WriteFileTool implements McpTool {
    private static final int MAX_PATH_LENGTH = 4_000;
    private static final int MAX_CONTENT_BYTES = 16 * 1024 * 1024;
    private static final int SHA256_LENGTH = 64;
    private static final Pattern ROOT_DEX_PATTERN = Pattern.compile("^classes\\d*\\.dex$", Pattern.CASE_INSENSITIVE);
    private static final Set<String> ARGUMENTS = Set.of(
            "path",
            "content_text",
            "content_base64",
            "old_text",
            "new_text",
            "replace_all",
            "delete",
            "expected_sha256",
            "dry_run"
    );
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;

    public WriteFileTool(WorkspaceManager workspaceManager) {
        this(workspaceManager::hasCurrentWorkspace, workspaceManager::getCurrent);
    }

    WriteFileTool(BooleanSupplier hasWorkspace, Supplier<Workspace> workspaceSupplier) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
    }

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "Create, replace, substring-patch, or delete a non-class file in the primary archive.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        Request request = parseRequest(arguments);
        if (!hasWorkspace.getAsBoolean()) {
            return error("workspace not open", request);
        }

        Workspace workspace = workspaceSupplier.get();
        WorkspaceResource primary = workspace.getPrimaryResource();
        String lowerPath = request.path().toLowerCase(Locale.ROOT);
        if (lowerPath.endsWith(".class")) {
            return error(
                    ".class entries belong to JVM class bundles; use patch_class or patch_instructions",
                    request
            );
        }
        if (ROOT_DEX_PATTERN.matcher(request.path()).matches()) {
            return error(
                    "DEX entries belong to Android class bundles; use patch_instructions or rename_symbol",
                    request
            );
        }
        if (ClassToolSupport.resolveEmbeddedResource(primary, request.path()) != null) {
            return error(
                    "path names a mounted embedded archive; embedded resources cannot be overwritten via write_file",
                    request
            );
        }
        FileBundle fileBundle = primary.getFileBundle();
        FileInfo existing = fileBundle.get(request.path());
        if (existing != null && existing.isClass()) {
            return error(
                    "class entries cannot be edited as raw files; use patch_class or patch_instructions",
                    request
            );
        }
        FileInfo resolvedExisting = existing instanceof DeferredFileInfo deferred
                ? deferred.resolve()
                : existing;

        String previousRawSha256 = null;
        String previousTextSha256 = null;
        if (resolvedExisting != null) {
            byte[] prevBytes = resolvedExisting.getRawContent();
            previousRawSha256 = FileToolSupport.sha256(prevBytes);
            if (resolvedExisting.isTextFile()) {
                previousTextSha256 = Hashing.sha256(resolvedExisting.asTextFile().getText());
            }
        }
        if (request.expectedSha256() != null) {
            if (resolvedExisting == null) {
                return error(
                        "expected_sha256 provided, but target file does not exist: " + request.path(),
                        request
                );
            }
            boolean matchesRaw = request.expectedSha256().equals(previousRawSha256);
            boolean matchesText = request.expectedSha256().equals(previousTextSha256);
            if (!matchesRaw && !matchesText) {
                JsonObject err = error(
                        "file content changed; read it again with read_file",
                        request
                );
                JsonObject structured = err.getAsJsonObject("structuredContent");
                structured.addProperty("current_raw_sha256", previousRawSha256);
                if (previousTextSha256 != null) {
                    structured.addProperty("current_content_sha256", previousTextSha256);
                }
                return err;
            }
        }

        if (request.mode() == Mode.DELETE) {
            if (resolvedExisting == null) {
                return error("file not found in primary resource: " + request.path(), request);
            }
            boolean applied = !request.dryRun();
            if (applied) {
                ToolCancellation.check();
                fileBundle.remove(request.path());
            }
            JsonObject structured = baseResult(request, "delete", applied, true);
            structured.addProperty("existed", true);
            structured.addProperty("deleted", applied);
            structured.addProperty("previous_raw_sha256", previousRawSha256);
            structured.add("raw_sha256", JsonNull.INSTANCE);
            structured.add("size_bytes", JsonNull.INSTANCE);
            MappingMutationSupport.addPersistenceMetadata(structured, workspace, applied);
            return McpTool.successResult(
                    applied
                            ? "deleted archive file " + request.path() + " in memory"
                            : "validated deletion of archive file " + request.path() + " (dry_run)",
                    structured
            );
        }

        byte[] newBytes;
        String newText = null;
        Charset charset = StandardCharsets.UTF_8;
        int replacements = 0;

        if (request.mode() == Mode.REPLACE_TEXT) {
            if (resolvedExisting == null) {
                return error(
                        "cannot apply old_text replacement because file does not exist: " + request.path(),
                        request
                );
            }
            if (resolvedExisting instanceof BinaryXmlFileInfo || resolvedExisting instanceof ArscFileInfo) {
                return error(
                        "Android binary XML / ARSC files cannot be patched as plain text; "
                                + "use content_base64 to replace raw binary bytes",
                        request
                );
            }
            if (!resolvedExisting.isTextFile()) {
                return error(
                        "target file is binary, not text; use content_base64 instead of old_text/new_text",
                        request
                );
            }
            TextFileInfo textFile = resolvedExisting.asTextFile();
            charset = textFile.getCharset() != null ? textFile.getCharset() : StandardCharsets.UTF_8;
            String currentText = textFile.getText();
            int count = countOccurrences(currentText, request.oldText());
            if (count == 0) {
                return error("old_text was not found in " + request.path(), request);
            }
            if (count > 1 && !request.replaceAll()) {
                JsonObject err = error(
                        "old_text matched " + count + " locations in " + request.path()
                                + "; pass replace_all=true or provide more surrounding context",
                        request
                );
                err.getAsJsonObject("structuredContent").addProperty("match_count", count);
                return err;
            }
            if (request.replaceAll()) {
                newText = currentText.replace(request.oldText(), request.newText());
                replacements = count;
            } else {
                int idx = currentText.indexOf(request.oldText());
                newText = currentText.substring(0, idx)
                        + request.newText()
                        + currentText.substring(idx + request.oldText().length());
                replacements = 1;
            }
            newBytes = newText.getBytes(charset);
        } else if (request.mode() == Mode.WRITE_TEXT) {
            if (resolvedExisting instanceof BinaryXmlFileInfo || resolvedExisting instanceof ArscFileInfo) {
                return error(
                        "Android binary XML / ARSC files cannot be overwritten with plain text; "
                                + "use content_base64 to replace raw binary bytes",
                        request
                );
            }
            if (resolvedExisting != null && resolvedExisting.isTextFile()
                    && resolvedExisting.asTextFile().getCharset() != null) {
                charset = resolvedExisting.asTextFile().getCharset();
            }
            newText = request.contentText();
            newBytes = newText.getBytes(charset);
        } else {
            try {
                newBytes = Base64.getDecoder().decode(request.contentBase64());
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("content_base64 is not valid Base64", ex);
            }
        }

        if (newBytes.length > MAX_CONTENT_BYTES) {
            return error("new file content exceeds the 16 MiB limit", request);
        }

        String newRawSha256 = FileToolSupport.sha256(newBytes);
        boolean changed = resolvedExisting == null || !newRawSha256.equals(previousRawSha256);
        boolean applied = changed && !request.dryRun();
        if (applied) {
            ToolCancellation.check();
            FileInfo updatedInfo = buildUpdatedFileInfo(
                    request.path(),
                    resolvedExisting,
                    newBytes,
                    newText != null
            );
            fileBundle.put(updatedInfo);
        }

        JsonObject structured = baseResult(request, request.mode().jsonName, applied, changed);
        structured.addProperty("existed", resolvedExisting != null);
        structured.addProperty("created", resolvedExisting == null && applied);
        structured.addProperty("deleted", false);
        if (previousRawSha256 == null) {
            structured.add("previous_raw_sha256", JsonNull.INSTANCE);
        } else {
            structured.addProperty("previous_raw_sha256", previousRawSha256);
        }
        structured.addProperty("raw_sha256", newRawSha256);
        if (newText != null) {
            String contentSha256 = Hashing.sha256(newText);
            structured.addProperty("content_sha256", contentSha256);
            structured.addProperty("sha256", contentSha256);
            structured.addProperty("encoding", charset.name());
            structured.addProperty("size_characters", newText.length());
        } else {
            structured.addProperty("content_sha256", newRawSha256);
            structured.addProperty("sha256", newRawSha256);
            structured.addProperty("encoding", "base64");
        }
        if (request.mode() == Mode.REPLACE_TEXT) {
            structured.addProperty("replacements", replacements);
        }
        structured.addProperty("size_bytes", newBytes.length);
        MappingMutationSupport.addPersistenceMetadata(structured, workspace, applied);
        String summaryMsg;
        if (!changed) {
            summaryMsg = "archive file " + request.path() + " is already identical; no changes made";
        } else if (request.dryRun()) {
            summaryMsg = "validated update to archive file " + request.path() + " (dry_run)";
        } else {
            summaryMsg = (resolvedExisting == null ? "created " : "updated ")
                    + "archive file " + request.path() + " in memory";
        }
        return McpTool.successResult(summaryMsg, structured);
    }

    private static FileInfo buildUpdatedFileInfo(String path,
                                                 FileInfo existing,
                                                 byte[] newBytes,
                                                 boolean preferText) {
        if (existing instanceof BinaryXmlFileInfo binaryXml) {
            BinaryXmlFileInfo rebuilt = new BinaryXmlFileInfoBuilder(binaryXml)
                    .withRawContent(newBytes)
                    .build();
            BinaryXmlDecodedProperty.remove(rebuilt);
            return rebuilt;
        }
        if (preferText) {
            TextFileInfoBuilder builder = existing != null && existing.isTextFile()
                    ? existing.asTextFile().toTextBuilder()
                    : new TextFileInfoBuilder().withName(path);
            if (existing != null && !existing.isTextFile()) {
                existing.getProperties().values().forEach(builder::withProperty);
            }
            return builder.withName(path).withRawContent(newBytes).build();
        }
        if (existing != null) {
            return existing.toFileBuilder()
                    .withName(path)
                    .withRawContent(newBytes)
                    .build();
        }
        return new FileInfoBuilder<>()
                .withName(path)
                .withRawContent(newBytes)
                .build();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int idx = haystack.indexOf(needle, from);
            if (idx < 0) {
                return count;
            }
            count++;
            from = idx + needle.length();
        }
    }

    private static JsonObject baseResult(Request request,
                                         String operation,
                                         boolean applied,
                                         boolean changed) {
        JsonObject structured = new JsonObject();
        structured.addProperty("path", request.path());
        structured.addProperty("scope", "primary_files");
        structured.addProperty("operation", operation);
        structured.addProperty("dry_run", request.dryRun());
        structured.addProperty("changed", changed);
        structured.addProperty("applied", applied);
        return structured;
    }

    private static JsonObject error(String message, Request request) {
        JsonObject structured = baseResult(request, request.mode().jsonName, false, false);
        structured.addProperty("error", message);
        MappingMutationSupport.addPersistenceMetadata(structured, nullWorkspaceSafe(), false);
        structured.remove("workspace_archive_export_supported");
        structured.remove("persistence_note");
        structured.addProperty("save_required", false);
        structured.addProperty("workspace_mutated_in_memory", false);
        return McpTool.errorResult(message, structured);
    }

    private static Workspace nullWorkspaceSafe() {
        return new software.coley.recaf.workspace.model.BasicWorkspace(
                new software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder().build(),
                java.util.List.of(),
                false
        );
    }

    private static Request parseRequest(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        String path = ToolArguments.requiredString(arguments, "path");
        validateArchiveEntryPath(path);

        boolean hasContentText = arguments.has("content_text") && !arguments.get("content_text").isJsonNull();
        String contentText = hasContentText
                ? rawStringArgument(arguments, "content_text")
                : null;
        String contentBase64 = arguments.has("content_base64") && !arguments.get("content_base64").isJsonNull()
                ? rawStringArgument(arguments, "content_base64")
                : null;
        String oldText = ToolArguments.optionalString(arguments, "old_text");
        boolean hasNewText = arguments.has("new_text") && !arguments.get("new_text").isJsonNull();
        String newText = hasNewText ? rawStringArgument(arguments, "new_text") : null;
        boolean replaceAll = ToolArguments.bool(arguments, "replace_all", false);
        boolean delete = ToolArguments.bool(arguments, "delete", false);
        boolean dryRun = ToolArguments.bool(arguments, "dry_run", false);
        String expectedSha256 = ToolArguments.optionalString(arguments, "expected_sha256");
        if (expectedSha256 != null && !isLowercaseSha256(expectedSha256)) {
            throw new IllegalArgumentException(
                    "expected_sha256 must be 64 lowercase hexadecimal characters"
            );
        }

        int modeCount = 0;
        Mode mode = null;
        if (delete) {
            modeCount++;
            mode = Mode.DELETE;
        }
        if (contentText != null) {
            modeCount++;
            mode = Mode.WRITE_TEXT;
        }
        if (contentBase64 != null) {
            modeCount++;
            mode = Mode.WRITE_BASE64;
        }
        if (oldText != null || hasNewText) {
            if (oldText == null || !hasNewText) {
                throw new IllegalArgumentException("old_text and new_text must be provided together");
            }
            modeCount++;
            mode = Mode.REPLACE_TEXT;
        }
        if (modeCount != 1 || mode == null) {
            throw new IllegalArgumentException(
                    "specify exactly one edit mode: content_text, content_base64, "
                            + "(old_text + new_text), or delete=true"
            );
        }
        if (replaceAll && mode != Mode.REPLACE_TEXT) {
            throw new IllegalArgumentException("replace_all requires old_text and new_text");
        }
        if (contentText != null && contentText.length() > MAX_CONTENT_BYTES) {
            throw new IllegalArgumentException("content_text exceeds maximum length");
        }
        if (contentBase64 != null && contentBase64.length() > (MAX_CONTENT_BYTES * 4L) / 3 + 16) {
            throw new IllegalArgumentException("content_base64 exceeds maximum length");
        }
        return new Request(
                path,
                mode,
                contentText,
                contentBase64,
                oldText,
                newText,
                replaceAll,
                dryRun,
                expectedSha256
        );
    }

    private static String rawStringArgument(JsonObject arguments, String key) {
        var elem = arguments.get(key);
        if (elem == null || !elem.isJsonPrimitive() || !elem.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return elem.getAsString();
    }

    private static void validateArchiveEntryPath(String path) {
        if (path.isEmpty() || path.length() > MAX_PATH_LENGTH) {
            throw new IllegalArgumentException("path must be between 1 and " + MAX_PATH_LENGTH + " characters");
        }
        if (path.startsWith("/") || path.endsWith("/") || path.contains("\\") || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(
                    "path must be a normalized archive-relative file path using '/' without leading or trailing '/'"
            );
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("path must not contain empty, '.', or '..' segments");
            }
        }
    }

    private static boolean isLowercaseSha256(String value) {
        if (value.length() != SHA256_LENGTH) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (!((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static JsonObject createInputSchema() {
        JsonObject properties = new JsonObject();
        JsonObject path = new JsonObject();
        path.addProperty("type", "string");
        path.addProperty("minLength", 1);
        path.addProperty("maxLength", MAX_PATH_LENGTH);
        path.addProperty(
                "description",
                "Archive-relative non-class file path (for example fabric.mod.json or META-INF/services/foo.Bar)."
        );
        properties.add("path", path);

        JsonObject contentText = new JsonObject();
        contentText.addProperty("type", "string");
        contentText.addProperty("description", "Complete UTF-8 text content to create or overwrite the file.");
        properties.add("content_text", contentText);

        JsonObject contentBase64 = new JsonObject();
        contentBase64.addProperty("type", "string");
        contentBase64.addProperty("description", "Complete Base64-encoded raw bytes to create or overwrite the file.");
        properties.add("content_base64", contentBase64);

        JsonObject oldText = new JsonObject();
        oldText.addProperty("type", "string");
        oldText.addProperty("minLength", 1);
        oldText.addProperty("description", "Exact existing substring to replace in a text file (use with new_text).");
        properties.add("old_text", oldText);

        JsonObject newText = new JsonObject();
        newText.addProperty("type", "string");
        newText.addProperty("description", "Replacement text for old_text.");
        properties.add("new_text", newText);

        JsonObject replaceAll = new JsonObject();
        replaceAll.addProperty("type", "boolean");
        replaceAll.addProperty("default", false);
        replaceAll.addProperty("description", "Replace all occurrences of old_text when multiple matches exist.");
        properties.add("replace_all", replaceAll);

        JsonObject delete = new JsonObject();
        delete.addProperty("type", "boolean");
        delete.addProperty("default", false);
        delete.addProperty("description", "Delete the target archive file from the primary resource.");
        properties.add("delete", delete);

        JsonObject expectedSha256 = new JsonObject();
        expectedSha256.addProperty("type", "string");
        expectedSha256.addProperty("minLength", SHA256_LENGTH);
        expectedSha256.addProperty("maxLength", SHA256_LENGTH);
        expectedSha256.addProperty("pattern", "^[0-9a-f]{64}$");
        expectedSha256.addProperty(
                "description",
                "Optional concurrency check matching read_file raw_sha256 or content_sha256."
        );
        properties.add("expected_sha256", expectedSha256);

        JsonObject dryRun = new JsonObject();
        dryRun.addProperty("type", "boolean");
        dryRun.addProperty("default", false);
        dryRun.addProperty("description", "Validate the edit and compute hashes without mutating the workspace.");
        properties.add("dry_run", dryRun);

        com.google.gson.JsonArray required = new com.google.gson.JsonArray();
        required.add("path");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty(
                "description",
                "Edits primary-resource non-class archive files in memory (specify content_text, "
                        + "content_base64, old_text + new_text, or delete=true); call recaf_save to persist."
        );
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    enum Mode {
        WRITE_TEXT("write_text"),
        WRITE_BASE64("write_base64"),
        REPLACE_TEXT("replace_text"),
        DELETE("delete");

        private final String jsonName;

        Mode(String jsonName) {
            this.jsonName = jsonName;
        }
    }

    record Request(String path,
                   Mode mode,
                   String contentText,
                   String contentBase64,
                   String oldText,
                   String newText,
                   boolean replaceAll,
                   boolean dryRun,
                   String expectedSha256) {
    }
}
