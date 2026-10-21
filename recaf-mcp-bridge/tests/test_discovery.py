from __future__ import annotations

import hashlib
import json
from pathlib import Path
from types import SimpleNamespace

import anyio
import pytest

from config import BridgeConfig
from conftest import make_test_recaf_jar, make_test_plugin_jar
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


def test_cancelled_discovery_always_stops_the_probe_worker(tmp_path: Path) -> None:
    processes = []

    class WaitingProbe:
        def __init__(self) -> None:
            self.entered = anyio.Event()

        async def probe(self, port, token):
            self.entered.set()
            await anyio.sleep_forever()

    async def start(command, environment, log_path):
        process = SimpleNamespace(pid=9000, returncode=None)
        processes.append(process)
        return process

    async def terminate(process):
        await anyio.sleep(0)
        process.returncode = -15

    probe = WaitingProbe()
    config = BridgeConfig(
        recaf_jar=make_test_recaf_jar(tmp_path),
        plugin_jar=make_test_plugin_jar(tmp_path / "plugins"),
        session_root=tmp_path / "sessions",
    )
    discovery = SchemaWorkerDiscovery(
        config, health_probe=probe, process_starter=start, process_terminator=terminate,
        port_allocator=lambda: 43101,
    )

    async def scenario() -> None:
        async with anyio.create_task_group() as tasks:
            tasks.start_soon(discovery.discover)
            await probe.entered.wait()
            tasks.cancel_scope.cancel()
        assert len(processes) == 1
        assert processes[0].returncode == -15
        assert not any(config.session_root.iterdir())

    anyio.run(scenario)


def test_failed_plugin_initialization_stops_discovery_without_retry(tmp_path: Path) -> None:
    processes = []

    class UnreachableProbe:
        async def probe(self, port, token):
            pytest.fail("plugin initialization failure must stop before polling health")

    async def start(command, environment, log_path):
        process = SimpleNamespace(pid=9000, returncode=None)
        processes.append(process)
        log_path.write_text(
            "14:37:47.874 [LaunchBootstrap/main] ERROR: Failed to initialize plugins\n"
            "software.coley.recaf.services.plugin.PluginException: Cannot load plugin\n"
            "Caused by: java.lang.NoClassDefFoundError: missing/API\n"
        )
        return process

    async def terminate(process):
        process.returncode = -15

    config = BridgeConfig(
        recaf_jar=make_test_recaf_jar(tmp_path),
        plugin_jar=make_test_plugin_jar(tmp_path / "plugins"),
        session_root=tmp_path / "sessions",
    )
    discovery = SchemaWorkerDiscovery(
        config, health_probe=UnreachableProbe(), process_starter=start, process_terminator=terminate,
        port_allocator=lambda: 43101,
    )

    async def scenario() -> None:
        with pytest.raises(WorkerLaunchError, match="NoClassDefFoundError: missing/API"):
            await discovery.discover()
        assert len(processes) == 1
        assert processes[0].returncode == -15
        assert not any(config.session_root.iterdir())

    anyio.run(scenario)
