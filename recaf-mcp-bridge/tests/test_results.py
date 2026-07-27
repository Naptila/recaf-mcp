from __future__ import annotations

import mcp.types as types

from results import expose_structured_content


def test_compact_profile_keeps_single_authoritative_structured_result() -> None:
    original = types.CallToolResult(
        content=[types.TextContent(type="text", text="listed 1 class")],
        structuredContent={"classes": ["example/Foo"], "total": 1},
        isError=False,
    )

    compact = expose_structured_content(
        "list_classes",
        original,
        enabled=False,
    )

    assert compact is original
    assert len(compact.content) == 1
    assert compact.structuredContent == original.structuredContent


def test_mirrors_structured_json_into_standard_text_content() -> None:
    original = types.CallToolResult(
        content=[types.TextContent(type="text", text="found 1 hit")],
        structuredContent={
            "hits": [{"class_name": "example/Foo", "value": "你好"}],
            "total": 1,
        },
        isError=False,
    )

    exposed = expose_structured_content("search_strings", original)

    assert exposed is not original
    assert len(original.content) == 1
    assert len(exposed.content) == 2
    fallback = exposed.content[1]
    assert isinstance(fallback, types.TextContent)
    assert fallback.text.startswith("MCP structured result (JSON):")
    assert '"class_name":"example/Foo"' in fallback.text
    assert '"value":"你好"' in fallback.text


def test_formats_decompiled_source_and_is_idempotent() -> None:
    original = types.CallToolResult(
        content=[types.TextContent(type="text", text="decompiled example/Foo")],
        structuredContent={
            "class_name": "example/Foo",
            "source": "package example;\nclass Foo {}",
        },
        isError=False,
    )

    exposed = expose_structured_content("decompile_class", original)
    repeated = expose_structured_content("decompile_class", exposed)

    fallback = exposed.content[1]
    assert isinstance(fallback, types.TextContent)
    assert '"class_name":"example/Foo"' in fallback.text
    assert "```java\npackage example;\nclass Foo {}\n```" in fallback.text
    assert repeated is exposed


def test_formats_complete_method_assembly_for_text_only_clients() -> None:
    original = types.CallToolResult(
        content=[types.TextContent(type="text", text="read complete method assembly")],
        structuredContent={
            "owner": "example/Foo",
            "assembly_sha256": "a" * 64,
            "assembly": ".method public run ()V {\n    return\n}",
        },
        isError=False,
    )

    exposed = expose_structured_content("get_method_assembly", original)

    fallback = exposed.content[1]
    assert isinstance(fallback, types.TextContent)
    assert '"owner":"example/Foo"' in fallback.text
    assert '"assembly_sha256":"' in fallback.text
    assert "```jasm\n.method public run ()V {\n    return\n}\n```" in fallback.text


def test_formats_android_method_disassembly_as_read_only_text() -> None:
    original = types.CallToolResult(
        content=[],
        structuredContent={
            "assembly_format": "android_dex_method_disassembly",
            "editable": False,
            "assembly": "000000: return-void\n",
        },
        isError=False,
    )

    exposed = expose_structured_content("get_method_assembly", original)

    fallback = exposed.content[0]
    assert isinstance(fallback, types.TextContent)
    assert "Read-only Android DEX method disassembly" in fallback.text
    assert "```text\n000000: return-void\n\n```" in fallback.text


def test_formats_read_file_text_for_text_only_clients() -> None:
    original = types.CallToolResult(
        content=[types.TextContent(type="text", text="read 12 characters")],
        structuredContent={
            "path": "META-INF/MANIFEST.MF",
            "format": "text",
            "content_text": "Manifest-Version: 1.0\n",
            "content_base64": None,
        },
        isError=False,
    )

    exposed = expose_structured_content("read_file", original)

    fallback = exposed.content[1]
    assert isinstance(fallback, types.TextContent)
    assert '"path":"META-INF/MANIFEST.MF"' in fallback.text
    assert '"content_text"' not in fallback.text
    assert "File text (META-INF/MANIFEST.MF)" in fallback.text
    assert "```text\nManifest-Version: 1.0\n\n```" in fallback.text


def test_formats_exported_mappings_for_text_only_clients() -> None:
    original = types.CallToolResult(
        content=[types.TextContent(type="text", text="exported mappings")],
        structuredContent={
            "format": "Simple",
            "mapping_sha256": "b" * 64,
            "content_text": "example/Old example/New\n",
        },
        isError=False,
    )

    exposed = expose_structured_content("export_mappings", original)

    fallback = exposed.content[1]
    assert isinstance(fallback, types.TextContent)
    assert '"format":"Simple"' in fallback.text
    assert '"content_text"' not in fallback.text
    assert "Recaf mappings (Simple)" in fallback.text
    assert "```text\nexample/Old example/New\n\n```" in fallback.text


def test_mirrors_conditional_metadata_without_assuming_a_body() -> None:
    original = types.CallToolResult(
        content=[types.TextContent(type="text", text="source unchanged")],
        structuredContent={
            "class_name": "example/Foo",
            "source_sha256": "c" * 64,
            "source_length": 1234,
            "unchanged": True,
        },
        isError=False,
    )

    exposed = expose_structured_content("decompile_class", original)

    fallback = exposed.content[1]
    assert isinstance(fallback, types.TextContent)
    assert '"unchanged":true' in fallback.text
    assert '"source_sha256":"' in fallback.text
    assert "```java" not in fallback.text


def test_formats_script_help_as_markdown_for_text_only_clients() -> None:
    original = types.CallToolResult(
        content=[types.TextContent(type="text", text="returned script help")],
        structuredContent={
            "topic": "asm",
            "guide": "# ASM guide\n\nUse `ClassNode`.",
            "high_privilege": True,
        },
        isError=False,
    )

    exposed = expose_structured_content("get_recaf_script_help", original)

    fallback = exposed.content[1]
    assert isinstance(fallback, types.TextContent)
    assert '"topic":"asm"' in fallback.text
    assert '"guide"' not in fallback.text
    assert "# ASM guide\n\nUse `ClassNode`." in fallback.text


def test_formats_script_stdout_separately_for_text_only_clients() -> None:
    original = types.CallToolResult(
        content=[types.TextContent(type="text", text="script completed")],
        structuredContent={
            "executed": True,
            "stdout": "MCP_RESULT {\"matches\":39}",
            "script_result": {"matches": 39},
        },
        isError=False,
    )

    exposed = expose_structured_content("run_recaf_script", original)

    fallback = exposed.content[1]
    assert isinstance(fallback, types.TextContent)
    assert '"stdout"' not in fallback.text
    assert '"script_result":{"matches":39}' in fallback.text
    assert "```text\nMCP_RESULT {\"matches\":39}\n```" in fallback.text
