package me.naptila.recafmcp.mcp.tools;

import com.google.devrel.gmscore.tools.apk.arsc.BinaryResourceFile;
import com.google.devrel.gmscore.tools.apk.arsc.BinaryResourceString;
import com.google.devrel.gmscore.tools.apk.arsc.BinaryResourceValue;
import com.google.devrel.gmscore.tools.apk.arsc.Chunk;
import com.google.devrel.gmscore.tools.apk.arsc.StringPoolChunk;
import com.google.devrel.gmscore.tools.apk.arsc.XmlAttribute;
import com.google.devrel.gmscore.tools.apk.arsc.XmlChunk;
import com.google.devrel.gmscore.tools.apk.arsc.XmlResourceMapChunk;
import com.google.devrel.gmscore.tools.apk.arsc.XmlStartElementChunk;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.naptila.recafmcp.mcp.android.DeferredFileInfo;
import software.coley.android.xml.XmlDecoder;
import software.coley.recaf.info.BinaryXmlFileInfo;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.info.builder.BinaryXmlFileInfoBuilder;
import software.coley.recaf.info.properties.builtin.BinaryXmlDecodedProperty;
import software.coley.recaf.util.android.AndroidRes;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * Synchronizes JAR {@code META-INF/MANIFEST.MF} and APK {@code AndroidManifest.xml}
 * entry-point class references when primary classes are renamed.
 */
final class EntryPointMetadataSupport {
    static final String JAR_MANIFEST_PATH = "META-INF/MANIFEST.MF";
    static final String ANDROID_MANIFEST_PATH = "AndroidManifest.xml";
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    private static final int ATTR_NAME_RES_ID = 0x01010003;
    private static final int ATTR_MANAGE_SPACE_ACTIVITY_RES_ID = 0x01010004;
    private static final int ATTR_TARGET_ACTIVITY_RES_ID = 0x01010202;
    private static final int ATTR_BACKUP_AGENT_RES_ID = 0x0101027f;
    private static final int ATTR_APP_COMPONENT_FACTORY_RES_ID = 0x0101057a;

    private static final List<String> JAR_CLASS_ATTRIBUTES = List.of(
            "Main-Class",
            "Premain-Class",
            "Agent-Class",
            "Launcher-Agent-Class"
    );
    private static final Set<String> ANDROID_NAMED_COMPONENTS = Set.of(
            "application",
            "activity",
            "service",
            "receiver",
            "provider"
    );

    private EntryPointMetadataSupport() {
    }

    static SyncResult syncClassRenames(Workspace workspace,
                                       Map<String, String> jvmRenames,
                                       Map<String, String> androidRenames,
                                       boolean apply) {
        WorkspaceResource primary = workspace.getPrimaryResource();
        List<UpdatedReference> updated = new ArrayList<>();
        List<UnupdatedReference> unupdated = new ArrayList<>();

        if (!jvmRenames.isEmpty()) {
            syncJarManifest(primary, jvmRenames, apply, updated, unupdated);
            syncJarServices(primary, jvmRenames, apply, updated, unupdated);
            scanJarConfigReferences(primary, jvmRenames, unupdated);
        }
        if (!androidRenames.isEmpty()) {
            syncAndroidManifest(primary, androidRenames, apply, updated, unupdated);
        }
        return new SyncResult(List.copyOf(updated), List.copyOf(unupdated));
    }

    static void addSyncMetadata(JsonObject target, SyncResult syncResult) {
        JsonArray updated = new JsonArray();
        for (UpdatedReference item : syncResult.updated()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("path", item.path());
            obj.addProperty("attribute", item.attribute());
            obj.addProperty("old_value", item.oldValue());
            obj.addProperty("new_value", item.newValue());
            updated.add(obj);
        }
        JsonArray unupdated = new JsonArray();
        for (UnupdatedReference item : syncResult.unupdated()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("path", item.path());
            obj.addProperty("attribute", item.attribute());
            obj.addProperty("old_value", item.oldValue());
            if (item.suggestedValue() != null) {
                obj.addProperty("suggested_value", item.suggestedValue());
            }
            obj.addProperty("reason", item.reason());
            unupdated.add(obj);
        }
        target.add("updated_manifest_references", updated);
        target.add("unupdated_manifest_references", unupdated);
    }

    private static void syncJarManifest(WorkspaceResource primary,
                                        Map<String, String> jvmRenames,
                                        boolean apply,
                                        List<UpdatedReference> updated,
                                        List<UnupdatedReference> unupdated) {
        FileInfo fileInfo = primary.getFileBundle().get(JAR_MANIFEST_PATH);
        if (fileInfo instanceof DeferredFileInfo deferred) {
            fileInfo = deferred.resolve();
        }
        if (fileInfo == null) {
            return;
        }
        Manifest manifest;
        try {
            manifest = new Manifest(new ByteArrayInputStream(fileInfo.getRawContent()));
        } catch (Exception ex) {
            unupdated.add(new UnupdatedReference(
                    JAR_MANIFEST_PATH,
                    "*",
                    "",
                    "manifest parse failed: " + ex.getClass().getSimpleName()
            ));
            return;
        }
        Attributes mainAttributes = manifest.getMainAttributes();
        List<UpdatedReference> pendingUpdates = new ArrayList<>();
        for (String attributeName : JAR_CLASS_ATTRIBUTES) {
            String rawValue = mainAttributes.getValue(attributeName);
            if (rawValue == null || rawValue.isBlank()) {
                continue;
            }
            String trimmed = rawValue.strip();
            String internalName = trimmed.replace('.', '/');
            String mappedInternal = jvmRenames.get(internalName);
            if (mappedInternal == null || mappedInternal.equals(internalName)) {
                continue;
            }
            String newDotted = mappedInternal.replace('/', '.');
            mainAttributes.putValue(attributeName, newDotted);
            pendingUpdates.add(new UpdatedReference(
                    JAR_MANIFEST_PATH,
                    attributeName,
                    trimmed,
                    newDotted
            ));
        }
        if (pendingUpdates.isEmpty()) {
            return;
        }
        if (apply) {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                manifest.write(out);
                FileInfo replacement = fileInfo.toFileBuilder()
                        .withRawContent(out.toByteArray())
                        .build();
                primary.getFileBundle().put(replacement);
            } catch (Exception ex) {
                for (UpdatedReference pending : pendingUpdates) {
                    unupdated.add(new UnupdatedReference(
                            pending.path(),
                            pending.attribute(),
                            pending.oldValue(),
                            "manifest write failed: " + ex.getClass().getSimpleName()
                    ));
                }
                return;
            }
        }
        updated.addAll(pendingUpdates);
    }

    private static final String SERVICES_PREFIX = "META-INF/services/";
    private static final int MAX_SCANNED_CONFIG_BYTES = 1024 * 1024;
    private static final int MAX_UNUPDATED_REFERENCES = 100;

    private static void syncJarServices(WorkspaceResource primary,
                                        Map<String, String> jvmRenames,
                                        boolean apply,
                                        List<UpdatedReference> updated,
                                        List<UnupdatedReference> unupdated) {
        var fileBundle = primary.getFileBundle();
        List<Map.Entry<String, FileInfo>> snapshot = new ArrayList<>(fileBundle.entrySet());
        snapshot.sort(Map.Entry.comparingByKey());
        for (Map.Entry<String, FileInfo> entry : snapshot) {
            String path = entry.getKey();
            if (!path.startsWith(SERVICES_PREFIX)) {
                continue;
            }
            String spiDotted = path.substring(SERVICES_PREFIX.length());
            if (spiDotted.isEmpty() || spiDotted.contains("/")) {
                continue;
            }
            FileInfo fileInfo = entry.getValue();
            if (fileInfo instanceof DeferredFileInfo deferred) {
                fileInfo = deferred.resolve();
            }
            byte[] raw = fileInfo.getRawContent();
            if (raw == null || raw.length > MAX_SCANNED_CONFIG_BYTES) {
                continue;
            }
            String spiInternal = spiDotted.replace('.', '/');
            String mappedSpiInternal = jvmRenames.get(spiInternal);
            String targetPath = mappedSpiInternal != null && !mappedSpiInternal.equals(spiInternal)
                    ? SERVICES_PREFIX + mappedSpiInternal.replace('/', '.')
                    : path;

            String text = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
            StringBuilder rewritten = new StringBuilder(text.length() + 64);
            List<UpdatedReference> lineUpdates = new ArrayList<>();
            int cursor = 0;
            while (cursor < text.length()) {
                int lineEnd = text.indexOf('\n', cursor);
                String lineWithEnding;
                if (lineEnd < 0) {
                    lineWithEnding = text.substring(cursor);
                    cursor = text.length();
                } else {
                    lineWithEnding = text.substring(cursor, lineEnd + 1);
                    cursor = lineEnd + 1;
                }
                String rewrittenLine = rewriteServiceLine(
                        lineWithEnding,
                        targetPath,
                        jvmRenames,
                        lineUpdates
                );
                rewritten.append(rewrittenLine);
            }

            boolean pathChanged = !targetPath.equals(path);
            boolean contentChanged = !lineUpdates.isEmpty();
            if (!pathChanged && !contentChanged) {
                continue;
            }
            if (apply) {
                try {
                    byte[] newBytes = contentChanged
                            ? rewritten.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)
                            : raw;
                    FileInfo replacement = fileInfo.toFileBuilder()
                            .withName(targetPath)
                            .withRawContent(newBytes)
                            .build();
                    if (pathChanged) {
                        fileBundle.remove(path);
                    }
                    fileBundle.put(replacement);
                } catch (RuntimeException ex) {
                    unupdated.add(new UnupdatedReference(
                            path,
                            "service",
                            spiDotted,
                            null,
                            "service file update failed: " + ex.getClass().getSimpleName()
                    ));
                    continue;
                }
            }
            if (pathChanged) {
                updated.add(new UpdatedReference(path, "service_file", path, targetPath));
            }
            updated.addAll(lineUpdates);
        }
    }

    private static String rewriteServiceLine(String lineWithEnding,
                                             String reportPath,
                                             Map<String, String> jvmRenames,
                                             List<UpdatedReference> lineUpdates) {
        int contentLen = lineWithEnding.length();
        while (contentLen > 0 && (lineWithEnding.charAt(contentLen - 1) == '\n'
                || lineWithEnding.charAt(contentLen - 1) == '\r')) {
            contentLen--;
        }
        String ending = lineWithEnding.substring(contentLen);
        String line = lineWithEnding.substring(0, contentLen);
        int hashIdx = line.indexOf('#');
        String activePart = hashIdx >= 0 ? line.substring(0, hashIdx) : line;
        String commentPart = hashIdx >= 0 ? line.substring(hashIdx) : "";

        int start = 0;
        while (start < activePart.length() && Character.isWhitespace(activePart.charAt(start))) {
            start++;
        }
        int end = activePart.length();
        while (end > start && Character.isWhitespace(activePart.charAt(end - 1))) {
            end--;
        }
        if (start >= end) {
            return lineWithEnding;
        }
        String token = activePart.substring(start, end);
        String internalName = token.replace('.', '/');
        String mappedInternal = jvmRenames.get(internalName);
        if (mappedInternal == null || mappedInternal.equals(internalName)) {
            return lineWithEnding;
        }
        String newDotted = mappedInternal.replace('/', '.');
        lineUpdates.add(new UpdatedReference(reportPath, "service_provider", token, newDotted));
        return activePart.substring(0, start)
                + newDotted
                + activePart.substring(end)
                + commentPart
                + ending;
    }

    private static void scanJarConfigReferences(WorkspaceResource primary,
                                                Map<String, String> jvmRenames,
                                                List<UnupdatedReference> unupdated) {
        if (unupdated.size() >= MAX_UNUPDATED_REFERENCES) {
            return;
        }
        List<RenamePattern> patterns = new ArrayList<>();
        for (Map.Entry<String, String> entry : jvmRenames.entrySet()) {
            if (!entry.getKey().equals(entry.getValue())) {
                patterns.add(new RenamePattern(
                        entry.getKey(),
                        entry.getValue(),
                        entry.getKey().replace('/', '.'),
                        entry.getValue().replace('/', '.')
                ));
            }
        }
        if (patterns.isEmpty()) {
            return;
        }

        List<Map.Entry<String, FileInfo>> files = new ArrayList<>(primary.getFileBundle().entrySet());
        files.sort(Map.Entry.comparingByKey());
        for (Map.Entry<String, FileInfo> fileEntry : files) {
            if (unupdated.size() >= MAX_UNUPDATED_REFERENCES) {
                break;
            }
            String path = fileEntry.getKey();
            if (!isCandidateTextConfigPath(path)) {
                continue;
            }
            FileInfo info = fileEntry.getValue();
            if (info instanceof DeferredFileInfo deferred) {
                info = deferred.resolve();
            }
            byte[] raw = info.getRawContent();
            if (raw == null || raw.length == 0 || raw.length > MAX_SCANNED_CONFIG_BYTES
                    || looksBinary(raw)) {
                continue;
            }
            String text = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
            List<String> lines = text.lines().toList();
            scanMixinPackageRelativeReferences(path, text, lines, jvmRenames, unupdated);
            for (int lineIdx = 0; lineIdx < lines.size(); lineIdx++) {
                if (unupdated.size() >= MAX_UNUPDATED_REFERENCES) {
                    break;
                }
                String line = lines.get(lineIdx);
                for (RenamePattern pattern : patterns) {
                    if (containsTokenBoundedClass(line, pattern.oldDotted())) {
                        unupdated.add(new UnupdatedReference(
                                path,
                                "line:" + (lineIdx + 1),
                                pattern.oldDotted(),
                                pattern.newDotted(),
                                "text configuration reference requires review (editable via write_file)"
                        ));
                    } else if (!pattern.oldInternal().equals(pattern.oldDotted())
                            && containsTokenBoundedClass(line, pattern.oldInternal())) {
                        unupdated.add(new UnupdatedReference(
                                path,
                                "line:" + (lineIdx + 1),
                                pattern.oldInternal(),
                                pattern.newInternal(),
                                "text configuration reference requires review (editable via write_file)"
                        ));
                    }
                    if (unupdated.size() >= MAX_UNUPDATED_REFERENCES) {
                        break;
                    }
                }
            }
        }
    }

    private static final List<String> MIXIN_ARRAY_KEYS = List.of("mixins", "client", "server");

    private static void scanMixinPackageRelativeReferences(String path,
                                                           String text,
                                                           List<String> lines,
                                                           Map<String, String> jvmRenames,
                                                           List<UnupdatedReference> unupdated) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (!lower.endsWith(".json")) {
            return;
        }
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) {
                return;
            }
            root = parsed.getAsJsonObject();
        } catch (RuntimeException ex) {
            return;
        }
        JsonElement pkgElem = root.get("package");
        if (pkgElem == null || !pkgElem.isJsonPrimitive() || !pkgElem.getAsJsonPrimitive().isString()) {
            return;
        }
        String pkgDotted = pkgElem.getAsString().strip();
        if (pkgDotted.isEmpty()) {
            return;
        }
        String pkgInternal = pkgDotted.replace('.', '/');
        if (pkgInternal.endsWith("/")) {
            pkgInternal = pkgInternal.substring(0, pkgInternal.length() - 1);
        }
        if (pkgInternal.isEmpty()) {
            return;
        }
        for (String arrayKey : MIXIN_ARRAY_KEYS) {
            JsonElement arrElem = root.get(arrayKey);
            if (arrElem == null || !arrElem.isJsonArray()) {
                continue;
            }
            JsonArray arr = arrElem.getAsJsonArray();
            for (int idx = 0; idx < arr.size(); idx++) {
                if (unupdated.size() >= MAX_UNUPDATED_REFERENCES) {
                    return;
                }
                JsonElement item = arr.get(idx);
                if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                    continue;
                }
                String relEntry = item.getAsString().strip();
                if (relEntry.isEmpty()) {
                    continue;
                }
                String fullInternal = pkgInternal + "/" + relEntry.replace('.', '/');
                String mappedInternal = jvmRenames.get(fullInternal);
                if (mappedInternal == null || mappedInternal.equals(fullInternal)) {
                    continue;
                }
                String quoted = "\"" + relEntry + "\"";
                int lineNumber = -1;
                for (int lineIdx = 0; lineIdx < lines.size(); lineIdx++) {
                    if (lines.get(lineIdx).contains(quoted)) {
                        lineNumber = lineIdx + 1;
                        break;
                    }
                }
                String attribute = lineNumber > 0
                        ? arrayKey + "[" + idx + "] (line:" + lineNumber + ")"
                        : arrayKey + "[" + idx + "]";
                String prefix = pkgInternal + "/";
                if (mappedInternal.startsWith(prefix) && mappedInternal.length() > prefix.length()) {
                    String suggestedRelative = mappedInternal.substring(prefix.length()).replace('/', '.');
                    unupdated.add(new UnupdatedReference(
                            path,
                            attribute,
                            relEntry,
                            suggestedRelative,
                            "Mixin package-relative entry requires update (editable via write_file)"
                    ));
                } else {
                    unupdated.add(new UnupdatedReference(
                            path,
                            attribute,
                            relEntry,
                            mappedInternal.replace('/', '.'),
                            "Mixin class moved outside config package '" + pkgDotted
                                    + "'; keep under Mixin package or update config (editable via write_file)"
                    ));
                }
            }
        }
    }

    private static boolean isCandidateTextConfigPath(String path) {
        if (path.equalsIgnoreCase(JAR_MANIFEST_PATH)
                || path.equals(ANDROID_MANIFEST_PATH)
                || path.startsWith(SERVICES_PREFIX)) {
            return false;
        }
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        String fileName = lower.substring(lower.lastIndexOf('/') + 1);
        if (fileName.equals("fabric.mod.json")
                || fileName.equals("quilt.mod.json")
                || fileName.endsWith(".mixins.json")
                || (fileName.startsWith("mixins.") && fileName.endsWith(".json"))
                || fileName.equals("mods.toml")
                || fileName.equals("neoforge.mods.toml")
                || fileName.equals("mcmod.info")
                || fileName.equals("plugin.yml")
                || fileName.equals("paper-plugin.yml")
                || fileName.equals("bungee.yml")
                || fileName.equals("velocity-plugin.json")
                || fileName.equals("spring.factories")
                || lower.startsWith("meta-inf/spring/")
                || fileName.startsWith("application.")
                || fileName.equals("reference.conf")) {
            return true;
        }
        boolean rootOrMetaInf = !lower.contains("/") || lower.startsWith("meta-inf/");
        return rootOrMetaInf && (fileName.endsWith(".json")
                || fileName.endsWith(".toml")
                || fileName.endsWith(".yml")
                || fileName.endsWith(".yaml")
                || fileName.endsWith(".properties")
                || fileName.endsWith(".xml")
                || fileName.endsWith(".cfg")
                || fileName.endsWith(".conf"));
    }

    private static boolean looksBinary(byte[] raw) {
        int checkLen = Math.min(raw.length, 512);
        for (int i = 0; i < checkLen; i++) {
            if (raw[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsTokenBoundedClass(String line, String needle) {
        if (needle.isEmpty() || line.length() < needle.length()) {
            return false;
        }
        int from = 0;
        while (true) {
            int idx = line.indexOf(needle, from);
            if (idx < 0) {
                return false;
            }
            int end = idx + needle.length();
            boolean leftOk = idx == 0 || !isClassTokenChar(line.charAt(idx - 1));
            boolean rightOk = end == line.length() || !isClassTokenRightChar(line, end);
            if (leftOk && rightOk) {
                return true;
            }
            from = idx + 1;
        }
    }

    private static boolean isClassTokenChar(char ch) {
        return Character.isJavaIdentifierPart(ch) || ch == '.' || ch == '/';
    }

    private static boolean isClassTokenRightChar(String line, int index) {
        char ch = line.charAt(index);
        if (Character.isJavaIdentifierPart(ch) || ch == '/') {
            return true;
        }
        if (ch == '.') {
            return index + 1 < line.length() && Character.isJavaIdentifierStart(line.charAt(index + 1));
        }
        return false;
    }

    private record RenamePattern(String oldInternal,
                                 String newInternal,
                                 String oldDotted,
                                 String newDotted) {
    }

    private static void syncAndroidManifest(WorkspaceResource primary,
                                            Map<String, String> androidRenames,
                                            boolean apply,
                                            List<UpdatedReference> updated,
                                            List<UnupdatedReference> unupdated) {
        FileInfo fileInfo = primary.getFileBundle().get(ANDROID_MANIFEST_PATH);
        if (fileInfo instanceof DeferredFileInfo deferred) {
            fileInfo = deferred.resolve();
        }
        if (!(fileInfo instanceof BinaryXmlFileInfo binaryXml)) {
            return;
        }
        byte[] raw = binaryXml.getRawContent();
        BinaryResourceFile model;
        try {
            model = binaryXml.getChunkModel();
        } catch (RuntimeException ex) {
            unupdated.add(new UnupdatedReference(
                    ANDROID_MANIFEST_PATH,
                    "*",
                    "",
                    "binary XML chunk model unavailable: " + ex.getClass().getSimpleName()
            ));
            return;
        }
        if (model == null || model.getChunks().size() != 1
                || !(model.getChunks().getFirst() instanceof XmlChunk xmlChunk)) {
            return;
        }

        ByteBuffer rawBuf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        if (raw.length < 8 || xmlChunk.getOriginalChunkSize() > raw.length) {
            return;
        }

        Integer spOffsetObj = null;
        StringPoolChunk stringPool = null;
        int[] resourceIds = new int[0];
        for (Map.Entry<Integer, Chunk> subEntry : xmlChunk.getChunks().entrySet()) {
            Chunk sub = subEntry.getValue();
            if (sub instanceof StringPoolChunk sp && stringPool == null) {
                spOffsetObj = subEntry.getKey();
                stringPool = sp;
            } else if (sub instanceof XmlResourceMapChunk resMap) {
                int mapOffset = subEntry.getKey();
                int payloadStart = mapOffset + resMap.getHeaderSize();
                int count = Math.max(0, (resMap.getOriginalChunkSize() - resMap.getHeaderSize()) / 4);
                if (payloadStart >= 0 && payloadStart + count * 4 <= raw.length) {
                    resourceIds = new int[count];
                    for (int idx = 0; idx < count; idx++) {
                        resourceIds[idx] = rawBuf.getInt(payloadStart + idx * 4);
                    }
                }
            }
        }
        if (stringPool == null || spOffsetObj == null) {
            return;
        }
        int spOffset = spOffsetObj;
        int spSize = stringPool.getOriginalChunkSize();
        if (spOffset < 8 || spOffset + spSize > raw.length) {
            return;
        }

        String manifestPackage = findManifestPackage(xmlChunk, stringPool);
        List<PendingAxmlPatch> patches = new ArrayList<>();
        for (Map.Entry<Integer, Chunk> subEntry : xmlChunk.getChunks().entrySet()) {
            if (!(subEntry.getValue() instanceof XmlStartElementChunk element)) {
                continue;
            }
            int elemOffset = subEntry.getKey();
            if (elemOffset < spOffset + spSize) {
                // Element precedes string pool; unusual layout, report rather than risk corruption.
                collectElementReferencesAsUnupdated(
                        element,
                        stringPool,
                        resourceIds,
                        manifestPackage,
                        androidRenames,
                        "XML element precedes string pool chunk",
                        unupdated
                );
                continue;
            }
            inspectElement(
                    rawBuf,
                    elemOffset,
                    element,
                    stringPool,
                    resourceIds,
                    manifestPackage,
                    androidRenames,
                    patches,
                    unupdated
            );
        }
        if (patches.isEmpty()) {
            return;
        }

        if (apply) {
            try {
                byte[] rewritten = rewriteBinaryManifest(
                        raw,
                        spOffset,
                        stringPool,
                        patches
                );
                BinaryResourceFile verifyModel = new BinaryResourceFile(rewritten);
                String decoded = XmlDecoder.decode(
                        verifyModel,
                        AndroidRes.getAndroidBase(),
                        null
                );
                for (PendingAxmlPatch patch : patches) {
                    if (!decoded.contains(patch.newDotted())) {
                        throw new IllegalStateException(
                                "decoded manifest verification missed " + patch.newDotted()
                        );
                    }
                }
                BinaryXmlFileInfo replacement = new BinaryXmlFileInfoBuilder(binaryXml)
                        .withRawContent(rewritten)
                        .build();
                BinaryXmlDecodedProperty.remove(replacement);
                primary.getFileBundle().put(replacement);
            } catch (Exception ex) {
                for (PendingAxmlPatch patch : patches) {
                    unupdated.add(new UnupdatedReference(
                            ANDROID_MANIFEST_PATH,
                            patch.attributeLabel(),
                            patch.oldRawValue(),
                            "binary XML rewrite failed: " + ex.getClass().getSimpleName()
                    ));
                }
                return;
            }
        }
        for (PendingAxmlPatch patch : patches) {
            updated.add(new UpdatedReference(
                    ANDROID_MANIFEST_PATH,
                    patch.attributeLabel(),
                    patch.oldRawValue(),
                    patch.newDotted()
            ));
        }
    }

    private static String findManifestPackage(XmlChunk xmlChunk, StringPoolChunk stringPool) {
        for (Chunk sub : xmlChunk.getChunks().values()) {
            if (!(sub instanceof XmlStartElementChunk element)) {
                continue;
            }
            if (!"manifest".equals(element.getName())) {
                continue;
            }
            for (XmlAttribute attr : element.getAttributes()) {
                String ns = attr.namespace();
                if ((ns == null || ns.isEmpty()) && "package".equals(attr.name())) {
                    return attributeStringValue(attr, stringPool);
                }
            }
        }
        return null;
    }

    private static void inspectElement(ByteBuffer rawBuf,
                                       int elemOffset,
                                       XmlStartElementChunk element,
                                       StringPoolChunk stringPool,
                                       int[] resourceIds,
                                       String manifestPackage,
                                       Map<String, String> androidRenames,
                                       List<PendingAxmlPatch> patches,
                                       List<UnupdatedReference> unupdated) {
        String tag = element.getName();
        boolean namedComponent = ANDROID_NAMED_COMPONENTS.contains(tag);
        boolean isApplication = "application".equals(tag);
        boolean isActivityAlias = "activity-alias".equals(tag);
        if (!namedComponent && !isActivityAlias) {
            return;
        }

        int headerSize = element.getHeaderSize();
        if (elemOffset + headerSize + 20 > rawBuf.capacity()) {
            return;
        }
        int attributeStart = rawBuf.getShort(elemOffset + headerSize + 8) & 0xFFFF;
        int attributeSize = rawBuf.getShort(elemOffset + headerSize + 10) & 0xFFFF;
        List<XmlAttribute> attributes = element.getAttributes();
        for (int index = 0; index < attributes.size(); index++) {
            XmlAttribute attr = attributes.get(index);
            String componentAttr = matchingComponentAttribute(
                    tag,
                    namedComponent,
                    isApplication,
                    isActivityAlias,
                    attr,
                    resourceIds
            );
            if (componentAttr == null) {
                continue;
            }
            String oldRawValue = attributeStringValue(attr, stringPool);
            if (oldRawValue == null || oldRawValue.isEmpty()) {
                continue;
            }
            String resolvedInternal = resolveAndroidClassName(oldRawValue, manifestPackage);
            if (resolvedInternal == null) {
                continue;
            }
            String mappedInternal = androidRenames.get(resolvedInternal);
            if (mappedInternal == null || mappedInternal.equals(resolvedInternal)) {
                continue;
            }
            String newDotted = mappedInternal.replace('/', '.');
            String label = tag + "@" + componentAttr;

            BinaryResourceValue typedValue = attr.typedValue();
            int attrOffset = elemOffset + headerSize + attributeStart + index * attributeSize;
            if (attributeSize < 20
                    || attrOffset + 20 > rawBuf.capacity()
                    || typedValue == null
                    || typedValue.type() != BinaryResourceValue.Type.STRING
                    || (attr.rawValueIndex() >= 0 && attr.rawValueIndex() != typedValue.data())
                    || !matchesRawAttributeSlot(rawBuf, attrOffset, attr)) {
                unupdated.add(new UnupdatedReference(
                        ANDROID_MANIFEST_PATH,
                        label,
                        oldRawValue,
                        "non-standard binary XML attribute layout"
                ));
                continue;
            }
            patches.add(new PendingAxmlPatch(
                    attrOffset,
                    attr.rawValueIndex() >= 0,
                    label,
                    oldRawValue,
                    newDotted
            ));
        }
    }

    private static void collectElementReferencesAsUnupdated(XmlStartElementChunk element,
                                                            StringPoolChunk stringPool,
                                                            int[] resourceIds,
                                                            String manifestPackage,
                                                            Map<String, String> androidRenames,
                                                            String reason,
                                                            List<UnupdatedReference> unupdated) {
        String tag = element.getName();
        boolean namedComponent = ANDROID_NAMED_COMPONENTS.contains(tag);
        boolean isApplication = "application".equals(tag);
        boolean isActivityAlias = "activity-alias".equals(tag);
        if (!namedComponent && !isActivityAlias) {
            return;
        }
        for (XmlAttribute attr : element.getAttributes()) {
            String componentAttr = matchingComponentAttribute(
                    tag,
                    namedComponent,
                    isApplication,
                    isActivityAlias,
                    attr,
                    resourceIds
            );
            if (componentAttr == null) {
                continue;
            }
            String oldRawValue = attributeStringValue(attr, stringPool);
            String resolvedInternal = resolveAndroidClassName(oldRawValue, manifestPackage);
            if (resolvedInternal != null && androidRenames.containsKey(resolvedInternal)) {
                unupdated.add(new UnupdatedReference(
                        ANDROID_MANIFEST_PATH,
                        tag + "@" + componentAttr,
                        oldRawValue,
                        reason
                ));
            }
        }
    }

    private static boolean matchesRawAttributeSlot(ByteBuffer buf,
                                                   int attrOffset,
                                                   XmlAttribute attr) {
        return buf.getInt(attrOffset) == attr.namespaceIndex()
                && buf.getInt(attrOffset + 4) == attr.nameIndex()
                && buf.getInt(attrOffset + 8) == attr.rawValueIndex()
                && (buf.getShort(attrOffset + 12) & 0xFFFF) == attr.typedValue().size()
                && buf.get(attrOffset + 15) == attr.typedValue().type().code()
                && buf.getInt(attrOffset + 16) == attr.typedValue().data();
    }

    private static String matchingComponentAttribute(String tag,
                                                     boolean namedComponent,
                                                     boolean isApplication,
                                                     boolean isActivityAlias,
                                                     XmlAttribute attr,
                                                     int[] resourceIds) {
        int nameIdx = attr.nameIndex();
        int resId = (nameIdx >= 0 && nameIdx < resourceIds.length) ? resourceIds[nameIdx] : 0;
        boolean androidNs = ANDROID_NS.equals(attr.namespace());
        String name = attr.name();

        if (namedComponent
                && (resId == ATTR_NAME_RES_ID || (androidNs && "name".equals(name)))) {
            return "android:name";
        }
        if (isActivityAlias
                && (resId == ATTR_TARGET_ACTIVITY_RES_ID
                        || (androidNs && "targetActivity".equals(name)))) {
            return "android:targetActivity";
        }
        if (isApplication) {
            if (resId == ATTR_BACKUP_AGENT_RES_ID
                    || (androidNs && "backupAgent".equals(name))) {
                return "android:backupAgent";
            }
            if (resId == ATTR_MANAGE_SPACE_ACTIVITY_RES_ID
                    || (androidNs && "manageSpaceActivity".equals(name))) {
                return "android:manageSpaceActivity";
            }
            if (resId == ATTR_APP_COMPONENT_FACTORY_RES_ID
                    || (androidNs && "appComponentFactory".equals(name))) {
                return "android:appComponentFactory";
            }
        }
        return null;
    }

    private static String attributeStringValue(XmlAttribute attr, StringPoolChunk stringPool) {
        BinaryResourceValue typed = attr.typedValue();
        if (typed != null && typed.type() == BinaryResourceValue.Type.STRING) {
            int idx = typed.data();
            if (idx >= 0 && idx < stringPool.getStringCount()) {
                return stringPool.getString(idx);
            }
        }
        int rawIdx = attr.rawValueIndex();
        if (rawIdx >= 0 && rawIdx < stringPool.getStringCount()) {
            return stringPool.getString(rawIdx);
        }
        return null;
    }

    static String resolveAndroidClassName(String rawValue, String manifestPackage) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        String value = rawValue.strip();
        if (value.startsWith(".")) {
            if (manifestPackage == null || manifestPackage.isBlank()) {
                return null;
            }
            return (manifestPackage + value).replace('.', '/');
        }
        if (!value.contains(".")) {
            if (manifestPackage != null && !manifestPackage.isBlank()) {
                return (manifestPackage + "." + value).replace('.', '/');
            }
            return value;
        }
        return value.replace('.', '/');
    }

    private static byte[] rewriteBinaryManifest(byte[] raw,
                                                int spOffset,
                                                StringPoolChunk stringPool,
                                                List<PendingAxmlPatch> patches) {
        ByteBuffer orig = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int spHeaderSize = orig.getShort(spOffset + 2) & 0xFFFF;
        int oldSpSize = orig.getInt(spOffset + 4);
        int oldStringCount = stringPool.getStringCount();
        int styleCount = orig.getInt(spOffset + 12);
        int oldFlags = orig.getInt(spOffset + 16);
        int oldStringsStart = orig.getInt(spOffset + 20);
        int oldStylesStart = orig.getInt(spOffset + 24);

        Map<String, Integer> stringIndices = new LinkedHashMap<>();
        List<byte[]> encodedAppended = new ArrayList<>();
        int nextIndex = oldStringCount;
        for (PendingAxmlPatch patch : patches) {
            if (stringIndices.containsKey(patch.newDotted())) {
                continue;
            }
            int existing = stringPool.indexOf(patch.newDotted());
            if (existing >= 0) {
                stringIndices.put(patch.newDotted(), existing);
            } else {
                encodedAppended.add(BinaryResourceString.encodeString(
                        patch.newDotted(),
                        stringPool.getStringType()
                ));
                stringIndices.put(patch.newDotted(), nextIndex++);
            }
        }

        int addedCount = encodedAppended.size();
        byte[] newStringPoolBytes;
        if (addedCount == 0) {
            newStringPoolBytes = new byte[oldSpSize];
            System.arraycopy(raw, spOffset, newStringPoolBytes, 0, oldSpSize);
        } else {
            int offsetsArrayStart = spOffset + spHeaderSize;
            int stringOffsetsBytes = oldStringCount * 4;
            int styleOffsetsBytes = styleCount * 4;
            int gapBeforeStrings = oldStringsStart - (spHeaderSize + stringOffsetsBytes + styleOffsetsBytes);
            if (gapBeforeStrings < 0) {
                throw new IllegalStateException("invalid StringPoolChunk stringsStart");
            }
            int oldStringsEnd = (styleCount > 0 && oldStylesStart > 0) ? oldStylesStart : oldSpSize;
            int oldStringsDataLen = oldStringsEnd - oldStringsStart;
            int stylesDataLen = oldSpSize - oldStringsEnd;
            if (oldStringsDataLen < 0 || stylesDataLen < 0) {
                throw new IllegalStateException("invalid StringPoolChunk data bounds");
            }

            int appendedRawBytes = encodedAppended.stream().mapToInt(arr -> arr.length).sum();
            int unpaddedStringsLen = oldStringsDataLen + appendedRawBytes;
            int paddedStringsLen = (unpaddedStringsLen + 3) & ~3;
            int paddingBytes = paddedStringsLen - unpaddedStringsLen;

            int newStringCount = oldStringCount + addedCount;
            int newStringsStart = oldStringsStart + addedCount * 4;
            int newStylesStart = (styleCount > 0 && oldStylesStart > 0)
                    ? oldStylesStart + addedCount * 4 + (paddedStringsLen - oldStringsDataLen)
                    : 0;
            int newSpSize = spHeaderSize
                    + newStringCount * 4
                    + styleOffsetsBytes
                    + gapBeforeStrings
                    + paddedStringsLen
                    + stylesDataLen;

            ByteBuffer spBuf = ByteBuffer.allocate(newSpSize).order(ByteOrder.LITTLE_ENDIAN);
            // Copy original chunk header (preserving any extra header bytes > 28)
            spBuf.put(raw, spOffset, spHeaderSize);
            spBuf.putInt(4, newSpSize);
            spBuf.putInt(8, newStringCount);
            spBuf.putInt(12, styleCount);
            spBuf.putInt(16, oldFlags & ~1); // Clear SORTED_FLAG since strings are appended
            spBuf.putInt(20, newStringsStart);
            spBuf.putInt(24, newStylesStart);

            // Existing string offsets
            spBuf.position(spHeaderSize);
            spBuf.put(raw, offsetsArrayStart, stringOffsetsBytes);
            // Appended string offsets
            int runningStringOffset = oldStringsDataLen;
            for (byte[] encoded : encodedAppended) {
                spBuf.putInt(runningStringOffset);
                runningStringOffset += encoded.length;
            }
            // Existing style offsets + any header gap
            spBuf.put(raw, offsetsArrayStart + stringOffsetsBytes, styleOffsetsBytes + gapBeforeStrings);
            // Existing string data + appended strings + 4-byte alignment padding
            spBuf.put(raw, spOffset + oldStringsStart, oldStringsDataLen);
            for (byte[] encoded : encodedAppended) {
                spBuf.put(encoded);
            }
            for (int pad = 0; pad < paddingBytes; pad++) {
                spBuf.put((byte) 0);
            }
            // Existing style data (if any)
            if (stylesDataLen > 0) {
                spBuf.put(raw, spOffset + oldStringsEnd, stylesDataLen);
            }
            newStringPoolBytes = spBuf.array();
        }

        int delta = newStringPoolBytes.length - oldSpSize;
        int afterSpOffset = spOffset + oldSpSize;
        int afterSpLen = raw.length - afterSpOffset;
        ByteBuffer out = ByteBuffer.allocate(raw.length + delta).order(ByteOrder.LITTLE_ENDIAN);
        out.put(raw, 0, spOffset);
        int oldOuterSize = orig.getInt(4);
        out.putInt(4, oldOuterSize + delta);
        out.put(newStringPoolBytes);
        out.put(raw, afterSpOffset, afterSpLen);

        for (PendingAxmlPatch patch : patches) {
            int newIdx = Objects.requireNonNull(stringIndices.get(patch.newDotted()));
            int shiftedAttrOffset = patch.attrOffset() + delta;
            if (patch.updateRawValueIndex()) {
                out.putInt(shiftedAttrOffset + 8, newIdx);
            }
            out.putInt(shiftedAttrOffset + 16, newIdx);
        }
        return out.array();
    }

    private record PendingAxmlPatch(int attrOffset,
                                    boolean updateRawValueIndex,
                                    String attributeLabel,
                                    String oldRawValue,
                                    String newDotted) {
    }

    record UpdatedReference(String path,
                            String attribute,
                            String oldValue,
                            String newValue) {
    }

    record UnupdatedReference(String path,
                              String attribute,
                              String oldValue,
                              String suggestedValue,
                              String reason) {
        UnupdatedReference(String path,
                           String attribute,
                           String oldValue,
                           String reason) {
            this(path, attribute, oldValue, null, reason);
        }
    }

    record SyncResult(List<UpdatedReference> updated,
                      List<UnupdatedReference> unupdated) {
    }
}
