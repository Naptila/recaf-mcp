"""Stable MCP tool-catalog primitives."""

from __future__ import annotations

import hashlib
import json
from collections.abc import Iterable
from copy import deepcopy

import mcp.types as types


INTERNAL_METHOD_NAMES = frozenset(
    {
        "health",
        "save_workspace",
        "cancel_operation",
        "shutdown",
        "tools/list",
        "tools/call",
    }
)


def inject_database(tool: types.Tool) -> types.Tool:
    """Return a client schema with required Bridge-only session routing."""
    schema = deepcopy(tool.inputSchema)
    if schema.get("type") != "object":
        raise ValueError(f"Worker tool {tool.name} must have an object schema")
    base_properties = schema.get("properties")
    if base_properties is None:
        base_properties = {}
    if not isinstance(base_properties, dict):
        raise ValueError(f"Worker tool {tool.name} properties must be an object")
    if "database" in base_properties:
        raise ValueError(f"Worker tool {tool.name} already defines database")
    schema["properties"] = {
        "database": {
            "type": "string",
            "minLength": 1,
        },
        **base_properties,
    }
    required = schema.get("required")
    if required is None:
        required_names: list[str] = []
    elif isinstance(required, list) and all(
        isinstance(name, str) for name in required
    ):
        required_names = required
    else:
        raise ValueError(f"Worker tool {tool.name} required must be an array")
    schema["required"] = ["database", *required_names]
    schema["additionalProperties"] = False
    return tool.model_copy(update={"inputSchema": schema}, deep=True)


class StaticToolCatalog:
    """An immutable, fingerprinted catalog fixed for one Bridge lifetime."""

    def __init__(self, tools: Iterable[types.Tool]) -> None:
        copied_tools = tuple(tool.model_copy(deep=True) for tool in tools)
        names = [tool.name for tool in copied_tools]
        if len(names) != len(set(names)):
            raise ValueError("tool catalog contains duplicate names")
        reserved = sorted(INTERNAL_METHOD_NAMES.intersection(names))
        if reserved:
            raise ValueError(f"tool catalog contains reserved names: {reserved}")

        self._tools = copied_tools
        canonical = [
            tool.model_dump(mode="json", by_alias=True, exclude_none=True)
            for tool in copied_tools
        ]
        payload = json.dumps(
            canonical,
            ensure_ascii=True,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
        self._fingerprint = hashlib.sha256(payload).hexdigest()

    @property
    def fingerprint(self) -> str:
        """Return a deterministic digest of the client-visible schemas."""
        return self._fingerprint

    def snapshot(self) -> list[types.Tool]:
        """Return a defensive copy without changing catalog identity."""
        return [tool.model_copy(deep=True) for tool in self._tools]
