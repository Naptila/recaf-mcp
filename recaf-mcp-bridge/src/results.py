"""Client-compatible MCP tool result rendering."""

from __future__ import annotations

import json
from typing import Any

import mcp.types as types


_FALLBACK_HEADING = "MCP structured result (JSON):"


def expose_structured_content(
    tool_name: str,
    result: types.CallToolResult,
    *,
    enabled: bool = True,
) -> types.CallToolResult:
    """Optionally mirror structured data for clients that hide typed results."""
    if not enabled:
        return result
    structured = result.structuredContent
    if not structured or _already_exposed(result.content):
        return result
    fallback = types.TextContent(
        type="text",
        text=_render_structured_content(tool_name, structured),
    )
    return result.model_copy(
        update={"content": [*result.content, fallback]},
        deep=True,
    )


def _already_exposed(content: list[types.ContentBlock]) -> bool:
    return any(
        isinstance(item, types.TextContent)
        and item.text.startswith(_FALLBACK_HEADING)
        for item in content
    )


def _render_structured_content(tool_name: str, structured: dict[str, Any]) -> str:
    if tool_name == "decompile_class" and isinstance(
        structured.get("source"),
        str,
    ):
        metadata = dict(structured)
        source = metadata.pop("source")
        return (
            f"{_FALLBACK_HEADING}\n{_compact_json(metadata)}\n\n"
            f"Decompiled Java source:\n```java\n{source}\n```"
        )
    if tool_name == "get_method_assembly" and isinstance(
        structured.get("assembly"),
        str,
    ):
        metadata = dict(structured)
        assembly = metadata.pop("assembly")
        android = metadata.get("assembly_format") == (
            "android_dex_method_disassembly"
        )
        heading = (
            "Read-only Android DEX method disassembly"
            if android
            else "Complete Recaf method assembly"
        )
        language = "text" if android else "jasm"
        return (
            f"{_FALLBACK_HEADING}\n{_compact_json(metadata)}\n\n"
            f"{heading}:\n```{language}\n{assembly}\n```"
        )
    if tool_name == "read_file" and isinstance(
        structured.get("content_text"),
        str,
    ):
        metadata = dict(structured)
        content = metadata.pop("content_text")
        metadata.pop("content_base64", None)
        path = metadata.get("path", "file")
        return (
            f"{_FALLBACK_HEADING}\n{_compact_json(metadata)}\n\n"
            f"File text ({path}):\n```text\n{content}\n```"
        )
    if tool_name == "export_mappings" and isinstance(
        structured.get("content_text"),
        str,
    ):
        metadata = dict(structured)
        content = metadata.pop("content_text")
        mapping_format = metadata.get("format", "unknown")
        return (
            f"{_FALLBACK_HEADING}\n{_compact_json(metadata)}\n\n"
            f"Recaf mappings ({mapping_format}):\n```text\n{content}\n```"
        )
    if tool_name == "get_recaf_script_help" and isinstance(
        structured.get("guide"),
        str,
    ):
        metadata = dict(structured)
        guide = metadata.pop("guide")
        return (
            f"{_FALLBACK_HEADING}\n{_compact_json(metadata)}\n\n"
            f"{guide}"
        )
    if tool_name == "run_recaf_script" and isinstance(
        structured.get("stdout"),
        str,
    ):
        metadata = dict(structured)
        stdout = metadata.pop("stdout")
        return (
            f"{_FALLBACK_HEADING}\n{_compact_json(metadata)}\n\n"
            f"Captured script stdout:\n```text\n{stdout}\n```"
        )
    return f"{_FALLBACK_HEADING}\n{_compact_json(structured)}"


def _compact_json(value: dict[str, Any]) -> str:
    return json.dumps(
        value,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    )
