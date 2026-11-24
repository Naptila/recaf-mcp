"""Bridge-owned lifecycle tool schemas and bootstrap behavior."""

from __future__ import annotations

from typing import Any

import mcp.types as types

from config import RESPONSE_PROFILES
from results import expose_structured_content


def _object_schema(
    properties: dict[str, Any],
    required: list[str] | None = None,
) -> dict[str, Any]:
    schema: dict[str, Any] = {
        "type": "object",
        "properties": properties,
        "additionalProperties": False,
    }
    if required:
        schema["required"] = required
    return schema


LIFECYCLE_TOOLS = (
    types.Tool(
        name="recaf_open",
        description="Open a JAR, APK, or DEX in a new isolated headless Recaf session.",
        inputSchema=_object_schema(
            {
                "input_path": {
                    "type": "string",
                    "description": "Absolute path to the input JAR, APK, or DEX.",
                },
                "autosave_path": {
                    "type": "string",
                    "description": (
                        "Optional absolute autosave destination in the input format. "
                        "APK/DEX destinations require .apk/.dex; APK output is unsigned."
                    ),
                },
                "idle_ttl_sec": {
                    "type": "integer",
                    "minimum": 0,
                    "description": (
                        "Optional per-session idle timeout in seconds "
                        "(0 disables idle expiration)."
                    ),
                },
            },
            ["input_path"],
        ),
    ),
    types.Tool(
        name="recaf_save",
        description=(
            "Atomically save a JAR, APK, or DEX in its original format. Omitting "
            "output_path writes to the session's managed autosave_path and never "
            "mutates input_path in place. APK output is unsigned."
        ),
        inputSchema=_object_schema(
            {
                "database": {"type": "string", "minLength": 1},
                "output_path": {
                    "type": "string",
                    "minLength": 1,
                    "description": (
                        "Optional absolute destination path. If omitted, writes "
                        "to the session's managed autosave_path (never mutates "
                        "input_path in place)."
                    ),
                },
                "overwrite": {
                    "type": "boolean",
                    "default": False,
                    "description": (
                        "Allow replacing an existing explicit output_path "
                        "(never allows overwriting input_path)."
                    ),
                },
            },
            ["database"],
        ),
    ),
    types.Tool(
        name="recaf_close",
        description=(
            "Close a session; omitted save persists unsaved (dirty) changes to "
            "output_path or the session's managed autosave_path, never mutating "
            "input_path in place. APK output is unsigned; use save=true to force "
            "export or save=false to discard."
        ),
        inputSchema=_object_schema(
            {
                "database": {"type": "string", "minLength": 1},
                "save": {
                    "type": "boolean",
                    "description": (
                        "Explicitly save (true) or discard (false). Omit to "
                        "auto-save dirty JAR/APK/DEX sessions to output_path or "
                        "managed autosave_path."
                    ),
                },
                "output_path": {
                    "type": "string",
                    "minLength": 1,
                    "description": (
                        "Optional absolute destination path when saving on "
                        "close. If omitted, writes to the session's managed "
                        "autosave_path (never mutates input_path in place)."
                    ),
                },
            },
            ["database"],
        ),
    ),
    types.Tool(
        name="recaf_list",
        description=(
            "List open and pending Recaf sessions and capacity without exposing bearer tokens."
        ),
        inputSchema=_object_schema({}),
    ),
    types.Tool(
        name="set_idle_ttl",
        description="Update the idle timeout for one Recaf session (0 disables idle expiration).",
        inputSchema=_object_schema(
            {
                "database": {"type": "string", "minLength": 1},
                "idle_ttl_sec": {
                    "type": "integer",
                    "minimum": 0,
                    "description": "Idle timeout in seconds; pass 0 to disable idle expiration.",
                },
            },
            ["database", "idle_ttl_sec"],
        ),
    ),
)


class LifecycleToolService:
    """Dispatch Bridge-owned lifecycle tools when no Recaf runtime is configured."""

    def __init__(self, response_profile: str = "compact") -> None:
        if response_profile not in RESPONSE_PROFILES:
            choices = ", ".join(sorted(RESPONSE_PROFILES))
            raise ValueError(f"response_profile must be one of: {choices}")
        self._response_profile = response_profile

    @property
    def definitions(self) -> tuple[types.Tool, ...]:
        """Return the complete Bridge-owned lifecycle schema set."""
        return LIFECYCLE_TOOLS

    async def list_tools(self) -> list[types.Tool]:
        """Return the stable protocol-only lifecycle catalog."""
        return [tool.model_copy(deep=True) for tool in LIFECYCLE_TOOLS]

    async def call(self, name: str, arguments: dict[str, Any]) -> types.CallToolResult:
        """Call a lifecycle tool using the currently configured session backend."""
        if name == "recaf_list":
            result = types.CallToolResult(
                content=[
                    types.TextContent(type="text", text="No Recaf sessions are open.")
                ],
                structuredContent={"sessions": []},
                isError=False,
            )
        elif name in {tool.name for tool in LIFECYCLE_TOOLS}:
            result = types.CallToolResult(
                content=[
                    types.TextContent(
                        type="text",
                        text="The Recaf session backend is not configured.",
                    )
                ],
                structuredContent={"error": "session backend not configured"},
                isError=True,
            )
        else:
            result = types.CallToolResult(
                content=[
                    types.TextContent(type="text", text=f"Unknown tool: {name}")
                ],
                structuredContent={"error": "unknown tool", "tool": name},
                isError=True,
            )
        return expose_structured_content(
            name,
            result,
            enabled=self._response_profile == "legacy",
        )

    async def close(self) -> None:
        """Protocol-only mode owns no external resources."""
        return None

    async def run_background(self) -> None:
        """Protocol-only mode has no background coordinators."""
        return None

    def begin_shutdown(self) -> None:
        """Protocol-only mode owns no mutable sessions."""
        return None
