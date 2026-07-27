from __future__ import annotations

import mcp.types as types
import pytest

from schema import StaticToolCatalog, inject_database


def tool(name: str) -> types.Tool:
    return types.Tool(
        name=name,
        description=f"{name} description",
        inputSchema={"type": "object", "properties": {}},
    )


def test_catalog_snapshot_is_stable_and_defensive() -> None:
    catalog = StaticToolCatalog([tool("first"), tool("second")])
    original_fingerprint = catalog.fingerprint

    first = catalog.snapshot()
    first[0].description = "mutated"
    second = catalog.snapshot()

    assert [item.name for item in second] == ["first", "second"]
    assert second[0].description == "first description"
    assert catalog.fingerprint == original_fingerprint
    assert len(original_fingerprint) == 64


def test_catalog_rejects_duplicate_and_internal_names() -> None:
    with pytest.raises(ValueError, match="duplicate"):
        StaticToolCatalog([tool("same"), tool("same")])
    with pytest.raises(ValueError, match="reserved"):
        StaticToolCatalog([tool("shutdown")])


def test_database_injection_is_required_defensive_and_preserves_base_schema() -> None:
    base = types.Tool(
        name="decompile_class",
        description="Decompile one class.",
        inputSchema={
            "type": "object",
            "properties": {"class_name": {"type": "string"}},
            "required": ["class_name"],
            "additionalProperties": False,
        },
    )

    routed = inject_database(base)

    assert list(routed.inputSchema["properties"]) == ["database", "class_name"]
    assert routed.inputSchema["properties"]["database"] == {
        "type": "string",
        "minLength": 1,
    }
    assert routed.inputSchema["required"] == ["database", "class_name"]
    assert routed.inputSchema["additionalProperties"] is False
    assert "database" not in base.inputSchema["properties"]


def test_database_injection_rejects_worker_owned_routing_property() -> None:
    base = types.Tool(
        name="bad",
        description="Invalid base schema.",
        inputSchema={
            "type": "object",
            "properties": {"database": {"type": "string"}},
        },
    )
    with pytest.raises(ValueError, match="already defines database"):
        inject_database(base)
