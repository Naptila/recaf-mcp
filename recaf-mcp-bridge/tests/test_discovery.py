from __future__ import annotations

import hashlib
import json

import pytest

from discovery import SchemaWorkerDiscovery
from launcher import WorkerLaunchError


def worker_definition() -> dict:
    return {
        "name": "workspace_info",
        "description": "Return workspace information.",
        "inputSchema": {
            "type": "object",
            "properties": {},
            "additionalProperties": False,
        },
    }


def health_for(result: dict) -> dict:
    canonical = json.dumps(
        result,
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")
    return {
        "recaf_compatible": True,
        "recaf_commit": "runtime-revision",
        "plugin_build": "1.0.0",
        "schema_fingerprint": hashlib.sha256(canonical).hexdigest(),
    }


def test_discovered_catalog_matches_worker_fingerprint_and_has_no_database() -> None:
    result = {"tools": [worker_definition()]}
    discovered = SchemaWorkerDiscovery._validate_catalog(
        health_for(result),
        result,
    )

    assert discovered.identity.plugin_build == "1.0.0"
    assert discovered.identity.recaf_commit == "runtime-revision"
    assert discovered.tools[0].name == "workspace_info"
    assert "database" not in discovered.tools[0].inputSchema["properties"]


def test_discovery_rejects_fingerprint_mismatch_and_routing_property() -> None:
    result = {"tools": [worker_definition()]}
    bad_health = health_for(result)
    bad_health["schema_fingerprint"] = "0" * 64
    with pytest.raises(WorkerLaunchError, match="fingerprint"):
        SchemaWorkerDiscovery._validate_catalog(bad_health, result)

    routed = worker_definition()
    routed["inputSchema"]["properties"]["database"] = {"type": "string"}
    routed_result = {"tools": [routed]}
    with pytest.raises(WorkerLaunchError, match="contains database"):
        SchemaWorkerDiscovery._validate_catalog(
            health_for(routed_result),
            routed_result,
        )


@pytest.mark.parametrize("compatible", [None, False, "true"])
def test_discovery_rejects_missing_or_failed_runtime_checks(compatible: object) -> None:
    result = {"tools": [worker_definition()]}
    health = health_for(result)
    health["recaf_compatible"] = compatible
    with pytest.raises(WorkerLaunchError, match="Recaf runtime"):
        SchemaWorkerDiscovery._validate_catalog(health, result)
