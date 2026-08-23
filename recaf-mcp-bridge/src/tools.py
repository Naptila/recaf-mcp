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
        description="Open a JAR or APK in a new isolated headless Recaf session.",
        inputSchema=_object_schema(
            {
                "input_path": {
                    "type": "string",
                    "description": "Absolute path to the input JAR or APK.",
                },
                "autosave_path": {
                    "type": "string",
                    "description": (
                        "Optional absolute autosave destination for a savable "
                        "JVM archive; unsupported for APK/DEX."
                    ),
                },
                "idle_ttl_sec": {
                    "type": "integer",
                    "minimum": 0,
                    "description": "Optional per-session idle timeout in seconds.",
                },
            },
            ["input_path"],
        ),
    ),
    types.Tool(
        name="recaf_save",
        description="Atomically save a JAR session; APK/DEX save is unsupported.",
        inputSchema=_object_schema(
            {
                "database": {"type": "string", "minLength": 1},
                "output_path": {"type": "string"},
                "overwrite": {"type": "boolean", "default": False},
            },
            ["database"],
        ),
    ),
    types.Tool(
        name="recaf_close",
        description=(
            "Close a session; omitted save persists JVM archives and discards "
            "APK/DEX sessions without exporting them."
        ),
        inputSchema=_object_schema(
            {
                "database": {"type": "string", "minLength": 1},
                "save": {
                    "type": "boolean",
                    "description": (
                        "Explicitly save or discard. Omit for format-aware "
                        "default behavior."
                    ),
                },
                "output_path": {"type": "string"},
            },
            ["database"],
        ),
    ),
    types.Tool(
        name="recaf_list",
        description="List Recaf sessions without exposing bearer tokens.",
        inputSchema=_object_schema({}),
    ),
    types.Tool(
        name="set_idle_ttl",
        description="Update the idle timeout for one Recaf session.",
        inputSchema=_object_schema(
            {
                "database": {"type": "string", "minLength": 1},
                "idle_ttl_sec": {"type": "integer", "minimum": 0},
            },
            ["database", "idle_ttl_sec"],
        ),
    ),
)


class LifecycleToolService:
    """Dispatch Bridge-owned tools; session operations are attached in M3.2."""

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
