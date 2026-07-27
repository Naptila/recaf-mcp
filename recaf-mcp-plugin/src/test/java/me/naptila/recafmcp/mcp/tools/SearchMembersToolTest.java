package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.services.search.SearchService;
import software.coley.recaf.services.search.SearchServiceConfig;
import software.coley.recaf.services.search.match.StringPredicateProvider;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.BasicJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchMembersToolTest {
    @Test
    void returnsStablePaginatedMemberResults() throws IOException {
        JvmClassInfo first = readClass(MemberSearchFixture.class);
        JvmClassInfo second = readClass(SearchMembersToolTest.class);
        Workspace workspace = workspace(first, second);
        SearchMembersTool tool = new SearchMembersTool(
                () -> true,
                () -> workspace,
                (currentWorkspace, request) -> {
                    assertEquals(workspace, currentWorkspace);
                    assertEquals("read", request.name());
                    assertFalse(request.regex());
                    assertEquals("method", request.kind());
                    assertEquals("both", request.results());
                    return new SearchMembersTool.SearchOutcome(
                            List.of(
                                    new SearchMembersTool.MemberHit(
                                            "reference",
                                            "method",
                                            "z/Owner",
                                            "readLater",
                                            "()I",
                                            null,
                                            "me/naptila/recafmcp/mcp/tools/z/Caller",
                                            "method",
                                            "call",
                                            "()I",
                                            8
                                    ),
                                    new SearchMembersTool.MemberHit(
                                            "declaration",
                                            "method",
                                            "a/Owner",
                                            "read",
                                            "()I",
                                            9,
                                            "me/naptila/recafmcp/mcp/tools/a/Owner",
                                            "method",
                                            "read",
                                            "()I",
                                            null
                                    ),
                                    new SearchMembersTool.MemberHit(
                                            "reference",
                                            "method",
                                            "z/Owner",
                                            "write",
                                            "()I",
                                            null,
                                            "me/naptila/recafmcp/mcp/tools/z/Wrong",
                                            "method",
                                            "call",
                                            "()I",
                                            10
                                    )
                            ),
                            false
                    );
                }
        );
        JsonObject arguments = new JsonObject();
        arguments.addProperty("name", "read");
        arguments.addProperty("kind", "method");
        arguments.addProperty("filter", "mcp/tools/");
        arguments.addProperty("offset", 1);
        arguments.addProperty("limit", 1);

        JsonObject result = tool.execute(arguments);
        JsonObject structured = result.getAsJsonObject("structuredContent");
        JsonArray hits = structured.getAsJsonArray("hits");

        assertFalse(result.get("isError").getAsBoolean());
        assertEquals(2, structured.get("classes_selected").getAsInt());
        assertEquals(2, structured.get("total").getAsInt());
        assertEquals(1, structured.get("returned").getAsInt());
        assertEquals("reference", hits.get(0).getAsJsonObject()
                .get("result_type").getAsString());
        assertEquals("me/naptila/recafmcp/mcp/tools/z/Caller",
                hits.get(0).getAsJsonObject()
                .get("class_name").getAsString());
        assertEquals(8, hits.get(0).getAsJsonObject()
                .get("instruction_index").getAsInt());
        assertTrue(structured.get("next_offset").isJsonNull());
        assertFalse(tool.inputSchema().has("anyOf"));
        assertTrue(tool.inputSchema().get("description").getAsString()
                .contains("Requires owner"));
        assertFalse(tool.inputSchema().getAsJsonObject("properties")
                .has("match"));
        JsonObject limit = tool.inputSchema().getAsJsonObject("properties")
                .getAsJsonObject("limit");
        assertEquals(10, limit.get("default").getAsInt());
        assertEquals(200, limit.get("maximum").getAsInt());
    }

    @Test
    void postFiltersFinalIdentityForAnchoredShortNames() throws IOException {
        JvmClassInfo fixture = readClass(MemberSearchFixture.class);
        Workspace workspace = workspace(fixture);
        SearchMembersTool tool = new SearchMembersTool(
                () -> true,
                () -> workspace,
                (currentWorkspace, request) -> new SearchMembersTool.SearchOutcome(
                        List.of(
                                memberDeclaration("mcpConfigLabel", fixture.getName()),
                                memberDeclaration("b", fixture.getName())
                        ),
                        false
                )
        );
        JsonObject arguments = new JsonObject();
        arguments.addProperty("name", "^b$");
        arguments.addProperty("regex", true);
        arguments.addProperty("kind", "method");
        arguments.addProperty("results", "declarations");

        JsonObject structured = tool.execute(arguments)
                .getAsJsonObject("structuredContent");

        assertEquals(1, structured.get("total").getAsInt());
        assertEquals("b", structured.getAsJsonArray("hits")
                .get(0).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void delegatesMethodDeclarationsAndReferencesToRecafSearch() throws IOException {
        JvmClassInfo fixture = readClass(MemberSearchFixture.class);
        Workspace workspace = workspace(fixture);
        SearchMembersTool tool = new SearchMembersTool(
                () -> true,
                () -> workspace,
                new SearchService(new SearchServiceConfig()),
                new StringPredicateProvider()
        );
        JsonObject arguments = new JsonObject();
        arguments.addProperty("owner", fixture.getName());
        arguments.addProperty("name", "read");
        arguments.addProperty("descriptor", "()I");
        arguments.addProperty("kind", "method");
        arguments.addProperty("results", "both");
        arguments.addProperty("filter", fixture.getName());

        JsonObject result = tool.execute(arguments);
        JsonObject structured = result.getAsJsonObject("structuredContent");
        JsonArray hits = structured.getAsJsonArray("hits");

        assertFalse(result.get("isError").getAsBoolean());
        assertEquals(1, structured.get("classes_selected").getAsInt());
        assertEquals(2, structured.get("total").getAsInt());
        JsonObject declaration = hits.get(0).getAsJsonObject();
        JsonObject reference = hits.get(1).getAsJsonObject();
        assertEquals("declaration", declaration.get("result_type").getAsString());
        assertEquals("method", declaration.get("member_kind").getAsString());
        assertEquals("read", declaration.get("containing_member_name").getAsString());
        assertFalse(declaration.get("access").isJsonNull());
        assertEquals("reference", reference.get("result_type").getAsString());
        assertEquals("call", reference.get("containing_member_name").getAsString());
        assertFalse(reference.get("instruction_index").isJsonNull());
        assertTrue(reference.get("access").isJsonNull());

        JsonObject referencesOnly = arguments.deepCopy();
        referencesOnly.addProperty("results", "references");
        JsonObject referenceResult = tool.execute(referencesOnly)
                .getAsJsonObject("structuredContent");
        assertEquals(1, referenceResult.get("total").getAsInt());
        assertEquals("reference", referenceResult.getAsJsonArray("hits")
                .get(0).getAsJsonObject().get("result_type").getAsString());

        JsonObject fieldDeclaration = new JsonObject();
        fieldDeclaration.addProperty("owner", fixture.getName());
        fieldDeclaration.addProperty("name", "val");
        fieldDeclaration.addProperty("kind", "field");
        fieldDeclaration.addProperty("results", "declarations");
        JsonObject fieldResult = tool.execute(fieldDeclaration)
                .getAsJsonObject("structuredContent");
        assertEquals(1, fieldResult.get("total").getAsInt());
        JsonObject fieldHit = fieldResult.getAsJsonArray("hits")
                .get(0).getAsJsonObject();
        assertEquals("declaration", fieldHit.get("result_type").getAsString());
        assertEquals("field", fieldHit.get("member_kind").getAsString());
        assertEquals("value", fieldHit.get("name").getAsString());
    }

    @Test
    void validatesSelectorsAndWorkspaceBeforeSearch() {
        SearchMembersTool unavailable = new SearchMembersTool(
                () -> false,
                () -> null,
                (workspace, request) -> new SearchMembersTool.SearchOutcome(
                        List.of(),
                        false
                )
        );
        JsonObject valid = new JsonObject();
        valid.addProperty("name", "read");
        assertTrue(unavailable.execute(valid).get("isError").getAsBoolean());

        JsonObject empty = new JsonObject();
        assertThrows(IllegalArgumentException.class, () -> unavailable.execute(empty));

        JsonObject badKind = valid.deepCopy();
        badKind.addProperty("kind", "constructor");
        assertThrows(
                IllegalArgumentException.class,
                () -> unavailable.execute(badKind)
        );

        JsonObject badRegex = valid.deepCopy();
        badRegex.addProperty("name", "[");
        badRegex.addProperty("regex", true);
        assertThrows(
                IllegalArgumentException.class,
                () -> unavailable.execute(badRegex)
        );

        JsonObject unknown = valid.deepCopy();
        unknown.addProperty("decompiler", "cfr");
        assertThrows(
                IllegalArgumentException.class,
                () -> unavailable.execute(unknown)
        );
    }

    private static Workspace workspace(JvmClassInfo... classes) {
        BasicJvmClassBundle bundle = new BasicJvmClassBundle();
        for (JvmClassInfo classInfo : classes) {
            bundle.put(classInfo);
        }
        WorkspaceResource primary = new WorkspaceResourceBuilder()
                .withJvmClassBundle(bundle)
                .build();
        return new BasicWorkspace(primary, List.of(), false);
    }

    private static SearchMembersTool.MemberHit memberDeclaration(String name,
                                                                 String owner) {
        return new SearchMembersTool.MemberHit(
                "declaration",
                "method",
                owner,
                name,
                "()V",
                0,
                owner,
                "method",
                name,
                "()V",
                null
        );
    }

    private static JvmClassInfo readClass(Class<?> type) throws IOException {
        String resourceName = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("test class resource is missing");
            }
            return new JvmClassInfoBuilder(input.readAllBytes()).build();
        }
    }

    private static final class MemberSearchFixture {
        private static int value;

        private static int read() {
            return value;
        }

        private static int call() {
            return read();
        }
    }
}
