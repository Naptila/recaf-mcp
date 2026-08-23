"""Safe argument-vector launch and authenticated Worker readiness checks."""

from __future__ import annotations

import json
import os
import secrets
import shutil
import signal
import socket
import subprocess
import time
from collections.abc import Awaitable, Callable, Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol

import anyio
import httpx
from anyio.abc import Process

from config import BridgeConfig
from sessions import (
    PathReservations,
    ReservedPaths,
    Session,
    SessionManager,
    new_session_timestamp,
)


class WorkerLaunchError(RuntimeError):
    """A bounded, token-free Worker launch diagnostic."""


class WorkerPortCollision(WorkerLaunchError):
    """A retryable close-to-bind port allocation race."""


class WorkerNotListening(WorkerLaunchError):
    """The selected port is not accepting Worker requests yet."""


@dataclass(frozen=True, slots=True)
class WorkerIdentity:
    plugin_build: str
    schema_fingerprint: str

    def __post_init__(self) -> None:
        if not self.plugin_build:
            raise ValueError("plugin_build must not be empty")
        if len(self.schema_fingerprint) != 64 or any(
            character not in "0123456789abcdef"
            for character in self.schema_fingerprint
        ):
            raise ValueError("schema_fingerprint must be lowercase SHA-256")


class HealthProbe(Protocol):
    async def probe(self, port: int, token: str) -> dict[str, Any]: ...


class ProcessStarter(Protocol):
    async def __call__(
        self,
        command: Sequence[str],
        environment: Mapping[str, str],
        log_path: Path,
    ) -> Process: ...


class ProcessTerminator(Protocol):
    async def __call__(self, process: Process) -> None: ...


class HttpHealthProbe:
    """Make one authenticated loopback health request with no proxy lookup."""

    async def probe(self, port: int, token: str) -> dict[str, Any]:
        request = {
            "jsonrpc": "2.0",
            "id": "bridge-readiness",
            "method": "health",
            "params": {},
        }
        try:
            async with httpx.AsyncClient(
                timeout=httpx.Timeout(1.0),
                trust_env=False,
            ) as client:
                response = await client.post(
                    f"http://127.0.0.1:{port}/jsonrpc",
                    headers={"Authorization": f"Bearer {token}"},
                    json=request,
                )
        except (
            httpx.ConnectError,
            httpx.ConnectTimeout,
            httpx.ReadTimeout,
        ) as error:
            raise WorkerNotListening("Worker endpoint is not listening") from error
        if response.status_code in {401, 403, 404}:
            raise WorkerPortCollision("allocated port is owned by another endpoint")
        try:
            response.raise_for_status()
            payload = response.json()
        except (httpx.HTTPError, json.JSONDecodeError) as error:
            raise WorkerPortCollision(
                "allocated port returned a non-Worker response"
            ) from error
        if not isinstance(payload, dict) or payload.get("id") != "bridge-readiness":
            raise WorkerPortCollision(
                "allocated port returned an invalid Worker response"
            )
        result = payload.get("result")
        if not isinstance(result, dict):
            raise WorkerLaunchError("Worker health response has no result object")
        return result


def allocate_loopback_port() -> int:
    """Ask the kernel for a loopback port and immediately release it for Recaf."""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("127.0.0.1", 0))
        return int(listener.getsockname()[1])


async def start_worker_process(
    command: Sequence[str],
    environment: Mapping[str, str],
    log_path: Path,
) -> Process:
    """Start one owned process group with owner-only merged diagnostics."""
    descriptor = os.open(
        log_path,
        os.O_WRONLY | os.O_CREAT | os.O_APPEND,
        0o600,
    )
    try:
        with os.fdopen(descriptor, "ab", closefd=True) as log_file:
            return await anyio.open_process(
                list(command),
                stdin=subprocess.DEVNULL,
                stdout=log_file,
                stderr=subprocess.STDOUT,
                env=dict(environment),
                start_new_session=(os.name == "posix"),
            )
    except BaseException:
        try:
            os.close(descriptor)
        except OSError:
            pass
        raise


async def terminate_process_group(process: Process) -> None:
    """Terminate an owned Worker process group with bounded escalation."""
    if process.returncode is not None:
        return
    identity_check = getattr(process, "verify_identity", None)
    if callable(identity_check) and not identity_check():
        return
    try:
        if os.name == "posix":
            os.killpg(process.pid, signal.SIGTERM)
        else:  # pragma: no cover - exercised on Windows hosts
            process.terminate()
    except ProcessLookupError:
        return
    with anyio.move_on_after(5):
        await process.wait()
    if process.returncode is not None:
        return
    if callable(identity_check) and not identity_check():
        return
    try:
        if os.name == "posix":
            os.killpg(process.pid, signal.SIGKILL)
        else:  # pragma: no cover - exercised on Windows hosts
            process.kill()
    except ProcessLookupError:
        return
    with anyio.move_on_after(5):
        await process.wait()


class WorkerLauncher:
    """Launch Workers while owning all capacity, path, process, and retry state."""

    def __init__(
        self,
        config: BridgeConfig,
        sessions: SessionManager,
        reservations: PathReservations,
        expected_identity: WorkerIdentity,
        *,
        health_probe: HealthProbe | None = None,
        process_starter: ProcessStarter = start_worker_process,
        process_terminator: ProcessTerminator = terminate_process_group,
        port_allocator: Callable[[], int] = allocate_loopback_port,
        sleep: Callable[[float], Awaitable[None]] = anyio.sleep,
    ) -> None:
        self._config = config.validate()
        self._sessions = sessions
        self._reservations = reservations
        self._expected_identity = expected_identity
        self._health_probe = health_probe or HttpHealthProbe()
        self._process_starter = process_starter
        self._process_terminator = process_terminator
        self._port_allocator = port_allocator
        self._sleep = sleep

    async def launch(
        self,
        input_path: str | Path,
        autosave_path: str | Path | None = None,
        idle_ttl_sec: int | None = None,
    ) -> Session:
        """Reserve, launch, authenticate, and activate one ready Worker."""
        sid = await self._sessions.reserve_slot()
        paths: ReservedPaths | None = None
        session_root = self._config.session_root / sid
        try:
            paths = await self._reservations.reserve(
                sid,
                input_path,
                autosave_path,
            )
            self._config.session_root.mkdir(
                parents=True,
                mode=0o700,
                exist_ok=True,
            )
            self._config.session_root.chmod(0o700)
            session_root.mkdir(mode=0o700, exist_ok=False)
            ttl = (
                self._config.default_idle_ttl_sec
                if idle_ttl_sec is None
                else idle_ttl_sec
            )
            if ttl < 0:
                raise ValueError("idle_ttl_sec must not be negative")

            final_error: WorkerLaunchError | None = None
            final_log_path: Path | None = None
            final_token: str | None = None
            for attempt in range(1, self._config.launch_attempts + 1):
                attempt_dir = session_root / f"attempt-{attempt}"
                data_dir = attempt_dir / "data"
                data_dir.mkdir(parents=True, mode=0o700)
                plugins_dir = attempt_dir / "plugins"
                plugins_dir.mkdir(mode=0o700)
                shutil.copy2(
                    self._config.effective_plugin_jar,
                    plugins_dir / self._config.effective_plugin_jar.name,
                )
                log_path = attempt_dir / "worker.log"
                port = self._port_allocator()
                token = secrets.token_urlsafe(32)
                command = self._command(
                    port,
                    data_dir,
                    plugins_dir,
                    paths.input_path,
                )
                environment = os.environ.copy()
                environment["RECAF_MCP_TOKEN"] = token
                process: Process | None = None
                activated = False
                try:
                    process = await self._process_starter(
                        command,
                        environment,
                        log_path,
                    )
                    health = await self._wait_until_ready(
                        process,
                        port,
                        token,
                        paths.input_path,
                    )
                    archive_export_supported = health[
                        "workspace_archive_export_supported"
                    ]
                    effective_autosave_path: Path | None = paths.autosave_path
                    if not archive_export_supported:
                        if autosave_path is not None:
                            raise WorkerLaunchError(
                                "autosave_path is unsupported for APK/DEX workspaces"
                            )
                        await self._reservations.disable_autosave(
                            sid,
                            paths.autosave_path,
                        )
                        effective_autosave_path = None
                    session = Session(
                        sid=sid,
                        process=process,
                        port=port,
                        token=token,
                        input_path=paths.input_path,
                        autosave_path=effective_autosave_path,
                        session_dir=session_root,
                        data_dir=data_dir,
                        log_path=log_path,
                        last_touched=new_session_timestamp(),
                        idle_ttl_sec=ttl,
                    )
                    await self._sessions.activate(session)
                    activated = True
                    return session
                except WorkerPortCollision as error:
                    final_error = error
                    final_log_path = log_path
                    final_token = token
                except WorkerLaunchError as error:
                    raise self._diagnostic_error(
                        str(error),
                        log_path,
                        token,
                    ) from error
                except OSError as error:
                    raise self._diagnostic_error(
                        "failed to start the Java Worker",
                        log_path,
                        token,
                    ) from error
                finally:
                    if process is not None and not activated:
                        await self._process_terminator(process)
                if attempt < self._config.launch_attempts:
                    continue
            if final_log_path is None or final_token is None:
                raise WorkerLaunchError(
                    "Worker failed before a retry attempt could start"
                ) from final_error
            raise self._diagnostic_error(
                "Worker failed after all close-to-bind retry attempts",
                final_log_path,
                final_token,
            ) from final_error
        except BaseException:
            await self._sessions.abandon(sid)
            if paths is not None:
                await self._reservations.release(sid)
            shutil.rmtree(session_root, ignore_errors=True)
            raise

    async def abort(self, sid: str) -> None:
        """Force-stop an owned session and release all launch-owned resources."""
        session = await self._sessions.get(sid)
        if session is None:
            await self._reservations.release(sid)
            return
        try:
            await self._process_terminator(session.process)
        finally:
            await self._sessions.remove(sid)
            await self._reservations.release(sid)
            shutil.rmtree(session.session_dir, ignore_errors=True)

    def _command(
        self,
        port: int,
        data_dir: Path,
        plugins_dir: Path,
        input_path: Path,
    ) -> list[str]:
        return [
            self._config.java_command,
            f"-Xmx{self._config.worker_max_heap_mb}m",
            f"-Drecaf.mcp.port={port}",
            "-jar",
            str(self._config.recaf_jar),
            "-d",
            str(data_dir),
            "-r",
            str(plugins_dir),
            "--headless",
            "--idle",
            "-i",
            str(input_path),
        ]

    async def _wait_until_ready(
        self,
        process: Process,
        port: int,
        token: str,
        expected_input: Path,
    ) -> dict[str, Any]:
        deadline = time.monotonic() + self._config.launch_timeout_sec
        while time.monotonic() < deadline:
            if process.returncode is not None:
                raise WorkerLaunchError("Worker exited before readiness")
            try:
                health = await self._health_probe.probe(port, token)
            except WorkerNotListening:
                await self._sleep(self._config.readiness_poll_sec)
                continue

            self._validate_health_identity(health, expected_input, process.pid)
            last_error = health.get("last_error")
            if last_error is not None:
                message = str(last_error).replace(token, "<redacted>")[:500]
                raise WorkerLaunchError(f"Worker startup error: {message}")
            if (
                health.get("ok") is True
                and health.get("ready") is True
                and health.get("workspace_open") is True
                and health.get("workspace_ready") is True
            ):
                return health
            await self._sleep(self._config.readiness_poll_sec)
        raise WorkerPortCollision("Worker readiness timed out on the allocated port")

    def _validate_health_identity(
        self,
        health: dict[str, Any],
        expected_input: Path,
        expected_pid: int,
    ) -> None:
        if health.get("pid") != expected_pid:
            raise WorkerLaunchError("Worker process identity mismatch")
        expected_path = str(expected_input)
        if health.get("expected_input_path") != expected_path:
            raise WorkerLaunchError("Worker expected-input identity mismatch")
        actual_path = health.get("input_path")
        if actual_path is not None and actual_path != expected_path:
            raise WorkerLaunchError("Worker input identity mismatch")
        if health.get("plugin_build") != self._expected_identity.plugin_build:
            raise WorkerLaunchError("Worker plugin build mismatch")
        if not isinstance(
            health.get("workspace_archive_export_supported"),
            bool,
        ):
            raise WorkerLaunchError(
                "Worker archive-export capability is missing"
            )
        if (
            health.get("schema_fingerprint")
            != self._expected_identity.schema_fingerprint
        ):
            raise WorkerLaunchError("Worker schema fingerprint mismatch")

    @staticmethod
    def _diagnostic_error(
        message: str,
        log_path: Path,
        token: str,
    ) -> WorkerLaunchError:
        """Attach a bounded, control-character-safe, token-free log tail."""
        try:
            data = log_path.read_bytes()[-16384:]
        except OSError:
            return WorkerLaunchError(message)
        decoded = data.decode("utf-8", errors="replace").replace(token, "<redacted>")
        lines: list[str] = []
        for raw_line in decoded.splitlines()[-30:]:
            cleaned = "".join(
                character
                if character == "\t" or ord(character) >= 32
                else "?"
                for character in raw_line
            )
            lines.append(cleaned[:500])
        if not lines:
            return WorkerLaunchError(message)
        return WorkerLaunchError(
            message + "\nWorker log tail:\n" + "\n".join(lines)
        )
