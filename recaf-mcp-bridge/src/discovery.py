"""Lazy schema-only Worker discovery and identity validation."""

from __future__ import annotations

import hashlib
import json
import os
import secrets
import shutil
import time
import uuid
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import anyio
import mcp.types as types
from anyio.abc import Process

from config import BridgeConfig
from launcher import (
    HealthProbe,
    HttpHealthProbe,
    ProcessStarter,
    ProcessTerminator,
    WorkerIdentity,
    WorkerLaunchError,
    WorkerLauncher,
    WorkerNotListening,
    WorkerPortCollision,
    allocate_loopback_port,
    resolve_java_command,
    resolve_worker_pid,
    start_worker_process,
    terminate_process_group,
    validate_recaf_runtime,
    windows_process_creation_time,
)
from schema import INTERNAL_METHOD_NAMES
from worker import WorkerRpcClient, WorkerRpcError


@dataclass(frozen=True, slots=True)
class DiscoveredWorkerCatalog:
    identity: WorkerIdentity
    tools: tuple[types.Tool, ...]


class SchemaWorkerDiscovery:
    """Start, query, and always terminate an untracked schema-only Worker."""

    def __init__(
        self,
        config: BridgeConfig,
        *,
        health_probe: HealthProbe | None = None,
        rpc_client: WorkerRpcClient | None = None,
        process_starter: ProcessStarter = start_worker_process,
        process_terminator: ProcessTerminator = terminate_process_group,
        port_allocator: Callable[[], int] = allocate_loopback_port,
        sleep: Callable[[float], Awaitable[None]] = anyio.sleep,
    ) -> None:
        self._config = config.validate()
        self._health_probe = health_probe or HttpHealthProbe()
        self._rpc_client = rpc_client or WorkerRpcClient(timeout_sec=5)
        self._process_starter = process_starter
        self._process_terminator = process_terminator
        self._port_allocator = port_allocator
        self._sleep = sleep

    async def discover(self) -> DiscoveredWorkerCatalog:
        """Return one validated catalog after guaranteed probe cleanup."""
        self._config.session_root.mkdir(parents=True, mode=0o700, exist_ok=True)
        self._config.session_root.chmod(0o700)
        probe_root = self._config.session_root / f"schema-{uuid.uuid4().hex[:12]}"
        probe_root.mkdir(mode=0o700)
        final_error: Exception | None = None
        try:
            effective_java = (
                await resolve_java_command(self._config.java_command, os.environ)
                if self._process_starter is start_worker_process
                else self._config.java_command
            )
            for attempt in range(1, self._config.launch_attempts + 1):
                attempt_root = probe_root / f"attempt-{attempt}"
                data_dir = attempt_root / "data"
                plugins_dir = attempt_root / "plugins"
                data_dir.mkdir(parents=True, mode=0o700)
                plugins_dir.mkdir(mode=0o700)
                shutil.copy2(
                    self._config.effective_plugin_jar,
                    plugins_dir / self._config.effective_plugin_jar.name,
                )
                log_path = attempt_root / "worker.log"
                port = self._port_allocator()
                token = secrets.token_urlsafe(32)
                process: Process | None = None
                observed_worker: list[int | None] = [None, None]
                retry = False
                try:
                    process = await self._process_starter(
                        self._command(
                            port,
                            data_dir,
                            plugins_dir,
                            java_command=effective_java,
                        ),
                        {**os.environ, "RECAF_MCP_TOKEN": token},
                        log_path,
                    )
                    health = await self._wait_until_ready(
                        process,
                        port,
                        token,
                        log_path,
                        observed_worker,
                    )
                    result = await self._rpc_client.request(
                        port,
                        token,
                        "tools/list",
                    )
                    discovered = self._validate_catalog(health, result)
                    try:
                        await self._rpc_client.request(
                            port,
                            token,
                            "shutdown",
                        )
                    except WorkerRpcError:
                        pass
                    return discovered
                except (WorkerPortCollision, WorkerNotListening) as error:
                    if WorkerLauncher._has_memory_failure(log_path):
                        raise WorkerLauncher._diagnostic_error(
                            str(error), log_path, token,
                        ) from error
                    retry = True
                    final_error = error
                except OSError as error:
                    raise WorkerLaunchError(
                        "failed to start schema-only Worker"
                    ) from error
                except WorkerLaunchError as error:
                    raise WorkerLauncher._diagnostic_error(
                        str(error), log_path, token,
                    ) from error
                finally:
                    if process is not None:
                        with anyio.CancelScope(shield=True):
                            await self._terminate_worker(
                                process,
                                observed_worker[0],
                                observed_worker[1],
                            )
                if not retry:
                    break
            raise WorkerLaunchError(
                "schema-only Worker failed after all port retry attempts"
            ) from final_error
        finally:
            shutil.rmtree(probe_root, ignore_errors=True)

    async def _terminate_worker(
        self,
        process: Process,
        worker_pid: int | None,
        worker_create_time: int | None,
    ) -> None:
        if self._process_terminator is terminate_process_group:
            await terminate_process_group(
                process,
                worker_pid=worker_pid,
                worker_create_time=worker_create_time,
            )
        else:
            await self._process_terminator(process)

    def _command(
        self,
        port: int,
        data_dir: Path,
        plugins_dir: Path,
        *,
        java_command: str | None = None,
    ) -> list[str]:
        return [
            java_command or self._config.java_command,
            *self._config.worker_jvm_options,
            f"-Drecaf.mcp.port={port}",
            "-jar",
            str(self._config.recaf_jar),
            "-d",
            str(data_dir),
            "-r",
            str(plugins_dir),
            "--headless",
            "--idle",
        ]

    async def _wait_until_ready(
        self,
        process: Process,
        port: int,
        token: str,
        log_path: Path,
        observed_worker: list[int | None],
    ) -> dict[str, Any]:
        deadline = time.monotonic() + self._config.launch_timeout_sec
        while time.monotonic() < deadline:
            if process.returncode is not None:
                raise WorkerLaunchError("schema-only Worker exited before readiness")
            failure = WorkerLauncher._startup_failure(log_path)
            if failure is not None:
                raise WorkerLaunchError(
                    "schema-only Worker startup failed: "
                    + failure.replace(token, "<redacted>")
                )
            try:
                health = await self._health_probe.probe(port, token)
            except WorkerNotListening:
                await self._sleep(self._config.readiness_poll_sec)
                continue
            worker_pid = resolve_worker_pid(health, process.pid)
            if worker_pid is None:
                raise WorkerLaunchError("schema-only Worker process mismatch")
            if observed_worker[0] != worker_pid:
                observed_worker[0] = worker_pid
                observed_worker[1] = (
                    windows_process_creation_time(worker_pid)
                    if worker_pid != process.pid
                    else None
                )
            if health.get("expected_input_path") is not None:
                raise WorkerLaunchError("schema-only Worker expected an input")
            if health.get("input_path") is not None:
                raise WorkerLaunchError("schema-only Worker opened an input")
            validate_recaf_runtime(health)
            if health.get("last_error") is not None:
                raise WorkerLaunchError("schema-only Worker reported a startup error")
            if (
                health.get("ok") is True
                and health.get("ready") is True
                and health.get("workspace_open") is False
                and health.get("workspace_ready") is False
            ):
                return health
            await self._sleep(self._config.readiness_poll_sec)
        raise WorkerLaunchError("schema-only Worker readiness timed out")

    @staticmethod
    def _validate_catalog(
        health: dict[str, Any],
        result: Any,
    ) -> DiscoveredWorkerCatalog:
        recaf_commit = validate_recaf_runtime(health)
        if not isinstance(result, dict) or not isinstance(result.get("tools"), list):
            raise WorkerLaunchError("Worker tools/list result is invalid")
        raw_tools = result["tools"]
        if not raw_tools:
            raise WorkerLaunchError("Worker catalog is empty")
        canonical = json.dumps(
            {"tools": raw_tools},
            ensure_ascii=False,
            separators=(",", ":"),
        ).encode("utf-8")
        fingerprint = hashlib.sha256(canonical).hexdigest()
        if health.get("schema_fingerprint") != fingerprint:
            raise WorkerLaunchError("Worker catalog fingerprint mismatch")
        plugin_build = health.get("plugin_build")
        if not isinstance(plugin_build, str) or not plugin_build:
            raise WorkerLaunchError("Worker plugin build is missing")

        tools: list[types.Tool] = []
        for definition in raw_tools:
            try:
                tool = types.Tool.model_validate(definition)
            except ValueError as error:
                raise WorkerLaunchError("Worker tool schema is invalid") from error
            if tool.name in INTERNAL_METHOD_NAMES or tool.name.startswith("probe_"):
                raise WorkerLaunchError("Worker catalog contains an internal method")
            properties = tool.inputSchema.get("properties", {})
            if "database" in properties:
                raise WorkerLaunchError("Worker base schema contains database")
            tools.append(tool)
        return DiscoveredWorkerCatalog(
            WorkerIdentity(plugin_build, fingerprint, recaf_commit),
            tuple(tools),
        )
