"""Safe argument-vector launch and authenticated Worker readiness checks."""

from __future__ import annotations

import json
import locale
import os
import re
import secrets
import shutil
import signal
import socket
import subprocess
import time
import zipfile
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


_JAVA_PROBE_TIMEOUT_SEC = 5.0
_JAVA_PROBE_MAX_OUTPUT_BYTES = 262144
_JAVA_HOME_PATTERN = re.compile(r"^\s*java\.home\s*=\s*(.+?)\s*$")
_MAX_ANCESTOR_PIDS = 16
_RESOLVED_JAVA_COMMANDS: dict[str, str] = {}

if os.name == "nt":  # pragma: no cover - exercised on Windows hosts
    import ctypes
    from ctypes import wintypes

    _PROCESS_TERMINATE = 0x0001
    _PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
    _SYNCHRONIZE = 0x00100000
    _STILL_ACTIVE = 259
    _WAIT_TIMEOUT = 0x00000102

    class _FILETIME(ctypes.Structure):
        _fields_ = [
            ("dwLowDateTime", wintypes.DWORD),
            ("dwHighDateTime", wintypes.DWORD),
        ]

    _kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    _kernel32.OpenProcess.argtypes = [
        wintypes.DWORD,
        wintypes.BOOL,
        wintypes.DWORD,
    ]
    _kernel32.OpenProcess.restype = wintypes.HANDLE
    _kernel32.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
    _kernel32.WaitForSingleObject.restype = wintypes.DWORD
    _kernel32.GetExitCodeProcess.argtypes = [
        wintypes.HANDLE,
        ctypes.POINTER(wintypes.DWORD),
    ]
    _kernel32.GetExitCodeProcess.restype = wintypes.BOOL
    _kernel32.GetProcessTimes.argtypes = [
        wintypes.HANDLE,
        ctypes.POINTER(_FILETIME),
        ctypes.POINTER(_FILETIME),
        ctypes.POINTER(_FILETIME),
        ctypes.POINTER(_FILETIME),
    ]
    _kernel32.GetProcessTimes.restype = wintypes.BOOL
    _kernel32.TerminateProcess.argtypes = [wintypes.HANDLE, wintypes.UINT]
    _kernel32.TerminateProcess.restype = wintypes.BOOL
    _kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
    _kernel32.CloseHandle.restype = wintypes.BOOL


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
    recaf_commit: str | None = None

    def __post_init__(self) -> None:
        if not self.plugin_build:
            raise ValueError("plugin_build must not be empty")
        if len(self.schema_fingerprint) != 64 or any(
            character not in "0123456789abcdef"
            for character in self.schema_fingerprint
        ):
            raise ValueError("schema_fingerprint must be lowercase SHA-256")


def validate_recaf_runtime(
    health: dict[str, Any], expected_commit: str | None = None,
) -> str:
    """Require Worker API compatibility and a consistent loaded Recaf revision."""
    if health.get("recaf_compatible") is not True:
        raise WorkerLaunchError(
            "Worker Recaf runtime is incompatible or lacks compatibility metadata; "
            "use a matching current plugin and Recaf generic JAR"
        )
    commit = health.get("recaf_commit")
    if not isinstance(commit, str) or not commit.strip() or len(commit) > 200:
        raise WorkerLaunchError("Worker Recaf runtime commit is missing or invalid")
    if expected_commit is not None and commit != expected_commit:
        raise WorkerLaunchError("Worker Recaf runtime changed since catalog discovery")
    return commit


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
        except httpx.TransportError as error:
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


def resolve_worker_pid(health: Mapping[str, Any], process_pid: int) -> int | None:
    """Return the Worker JVM PID when health matches process_pid or its ancestors."""
    worker_pid = health.get("pid")
    if isinstance(worker_pid, bool) or not isinstance(worker_pid, int) or worker_pid <= 0:
        return None
    if worker_pid == process_pid:
        return worker_pid
    raw_ancestors = health.get("ancestor_pids")
    if isinstance(raw_ancestors, list):
        for ancestor in raw_ancestors[:_MAX_ANCESTOR_PIDS]:
            if (
                not isinstance(ancestor, bool)
                and isinstance(ancestor, int)
                and ancestor == process_pid
            ):
                return worker_pid
    return None


def windows_process_creation_time(pid: int) -> int | None:
    """Return the 64-bit Win32 creation FILETIME of a live process, or None."""
    if os.name != "nt" or pid <= 0:
        return None
    access = _PROCESS_QUERY_LIMITED_INFORMATION | _SYNCHRONIZE
    has_sync = True
    handle = _kernel32.OpenProcess(access, False, pid)
    if not handle:
        has_sync = False
        handle = _kernel32.OpenProcess(
            _PROCESS_QUERY_LIMITED_INFORMATION,
            False,
            pid,
        )
    if not handle:
        return None
    try:
        if has_sync:
            if _kernel32.WaitForSingleObject(handle, 0) != _WAIT_TIMEOUT:
                return None
        else:
            exit_code = wintypes.DWORD()
            if (
                not _kernel32.GetExitCodeProcess(handle, ctypes.byref(exit_code))
                or exit_code.value != _STILL_ACTIVE
            ):
                return None
        created = _FILETIME()
        exited = _FILETIME()
        kernel = _FILETIME()
        user = _FILETIME()
        if not _kernel32.GetProcessTimes(
            handle,
            ctypes.byref(created),
            ctypes.byref(exited),
            ctypes.byref(kernel),
            ctypes.byref(user),
        ):
            return None
        return (int(created.dwHighDateTime) << 32) | int(created.dwLowDateTime)
    finally:
        _kernel32.CloseHandle(handle)


def _windows_is_worker_alive(
    pid: int,
    expected_create_time: int | None,
) -> bool:
    current = windows_process_creation_time(pid)
    if current is None:
        return False
    if expected_create_time is not None and current != expected_create_time:
        return False
    return True


def _windows_terminate_pid(
    pid: int,
    expected_create_time: int | None,
) -> None:
    if os.name != "nt" or pid <= 0:
        return
    handle = _kernel32.OpenProcess(
        _PROCESS_TERMINATE | _PROCESS_QUERY_LIMITED_INFORMATION,
        False,
        pid,
    )
    if not handle:
        return
    try:
        if expected_create_time is not None:
            created = _FILETIME()
            exited = _FILETIME()
            kernel = _FILETIME()
            user = _FILETIME()
            if not _kernel32.GetProcessTimes(
                handle,
                ctypes.byref(created),
                ctypes.byref(exited),
                ctypes.byref(kernel),
                ctypes.byref(user),
            ):
                return
            actual = (int(created.dwHighDateTime) << 32) | int(
                created.dwLowDateTime
            )
            if actual != expected_create_time:
                return
        _kernel32.TerminateProcess(handle, 1)
    finally:
        _kernel32.CloseHandle(handle)


def _windows_taskkill_executable() -> str:
    system_root = os.environ.get("SystemRoot")
    if system_root:
        candidate = Path(system_root) / "System32" / "taskkill.exe"
        if candidate.is_file():
            return str(candidate)
    return shutil.which("taskkill") or "taskkill"


def _posix_pid_alive(pid: int) -> bool:
    if pid <= 0:
        return False
    try:
        os.kill(pid, 0)
        return True
    except (ProcessLookupError, PermissionError):
        return False


def _signal_posix_worker(
    group_pid: int,
    extra_worker_pid: int | None,
    sig: signal.Signals,
) -> None:
    try:
        os.killpg(group_pid, sig)
    except (ProcessLookupError, PermissionError):
        pass
    if extra_worker_pid is not None:
        try:
            os.kill(extra_worker_pid, sig)
        except (ProcessLookupError, PermissionError):
            pass


async def _wait_posix_pid_exit(pid: int, timeout_sec: float) -> None:
    deadline = time.monotonic() + timeout_sec
    while time.monotonic() < deadline:
        if not _posix_pid_alive(pid):
            return
        await anyio.sleep(0.05)


def _extract_java_executable_from_settings(output: str) -> str | None:
    binary_names = ("java.exe", "java") if os.name == "nt" else ("java", "java.exe")
    for line in output.splitlines():
        match = _JAVA_HOME_PATTERN.match(line)
        if match is None:
            continue
        java_home = Path(match.group(1).strip())
        for binary_name in binary_names:
            candidate = java_home / "bin" / binary_name
            try:
                if not candidate.is_file():
                    continue
                if os.name != "nt" and not os.access(candidate, os.X_OK):
                    continue
                try:
                    canonical = candidate.resolve(strict=True)
                except OSError:
                    canonical = candidate.absolute()
                return str(canonical)
            except OSError:
                continue
    return None


async def resolve_java_command(
    java_command: str,
    environment: Mapping[str, str] | None = None,
) -> str:
    """Resolve shims/wrappers to <java.home>/bin/java(.exe) once per java_command."""
    cached = _RESOLVED_JAVA_COMMANDS.get(java_command)
    if cached is not None:
        return cached

    env = dict(os.environ if environment is None else environment)
    for option_var in (
        "JAVA_TOOL_OPTIONS",
        "_JAVA_OPTIONS",
        "JDK_JAVA_OPTIONS",
        "RECAF_MCP_TOKEN",
    ):
        env.pop(option_var, None)

    probe_executable = java_command
    if os.name == "nt":
        which_match = shutil.which(java_command, path=env.get("PATH"))
        if which_match is not None:
            probe_executable = which_match

    probe_process: Process | None = None
    output_chunks: list[bytes] = []
    try:
        with anyio.move_on_after(_JAVA_PROBE_TIMEOUT_SEC) as probe_scope:
            probe_process = await anyio.open_process(
                [probe_executable, "-XshowSettings:properties", "-version"],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                env=env,
                start_new_session=(os.name == "posix"),
            )
            assert probe_process.stdout is not None
            captured_bytes = 0
            async for chunk in probe_process.stdout:
                if captured_bytes < _JAVA_PROBE_MAX_OUTPUT_BYTES:
                    remaining = _JAVA_PROBE_MAX_OUTPUT_BYTES - captured_bytes
                    output_chunks.append(chunk[:remaining])
                    captured_bytes += len(chunk)
            await probe_process.wait()
        if (
            probe_scope.cancel_called
            or probe_process is None
            or probe_process.returncode != 0
        ):
            _RESOLVED_JAVA_COMMANDS[java_command] = java_command
            return java_command
    except OSError:
        _RESOLVED_JAVA_COMMANDS[java_command] = java_command
        return java_command
    finally:
        if probe_process is not None and probe_process.returncode is None:
            with anyio.CancelScope(shield=True):
                await terminate_process_group(probe_process)

    raw_output = b"".join(output_chunks)
    try:
        decoded = raw_output.decode("utf-8")
    except UnicodeDecodeError:
        decoded = raw_output.decode(
            locale.getpreferredencoding(False) or "utf-8",
            errors="replace",
        )
    resolved = _extract_java_executable_from_settings(decoded)
    effective = resolved if resolved is not None else java_command
    _RESOLVED_JAVA_COMMANDS[java_command] = effective
    return effective


async def start_worker_process(
    command: Sequence[str],
    environment: Mapping[str, str],
    log_path: Path,
) -> Process:
    """Start one owned process group with owner-only merged diagnostics."""
    argv = list(command)
    if argv and os.name == "nt":  # pragma: no cover - exercised on Windows hosts
        resolved_executable = shutil.which(argv[0], path=environment.get("PATH"))
        if resolved_executable is not None:
            argv[0] = resolved_executable
    descriptor = os.open(
        log_path,
        os.O_WRONLY | os.O_CREAT | os.O_APPEND,
        0o600,
    )
    try:
        with os.fdopen(descriptor, "ab", closefd=True) as log_file:
            return await anyio.open_process(
                argv,
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


async def _terminate_windows_worker(
    process: Process,
    *,
    extra_worker_pid: int | None,
    worker_create_time: int | None,
    identity_check: Callable[[], bool] | None,
) -> None:
    if identity_check is not None and not identity_check():
        return
    target_pids: list[int] = []
    if process.returncode is None and process.pid > 0:
        target_pids.append(process.pid)
    if extra_worker_pid is not None and _windows_is_worker_alive(
        extra_worker_pid,
        worker_create_time,
    ):
        target_pids.append(extra_worker_pid)
    if not target_pids:
        return

    taskkill_cmd = [_windows_taskkill_executable(), "/F", "/T"]
    for pid in target_pids:
        taskkill_cmd.extend(["/PID", str(pid)])

    killer: Process | None = None
    try:
        with anyio.move_on_after(5):
            killer = await anyio.open_process(
                taskkill_cmd,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            await killer.wait()
    except OSError:
        pass
    finally:
        if killer is not None and killer.returncode is None:
            try:
                killer.kill()
            except OSError:
                pass
            with anyio.move_on_after(1):
                await killer.wait()

    if process.returncode is None:
        with anyio.move_on_after(5):
            await process.wait()
    if process.returncode is None and (
        identity_check is None or identity_check()
    ):
        try:
            process.kill()
        except (ProcessLookupError, OSError):
            pass
        with anyio.move_on_after(5):
            await process.wait()

    if extra_worker_pid is not None and _windows_is_worker_alive(
        extra_worker_pid,
        worker_create_time,
    ):
        _windows_terminate_pid(extra_worker_pid, worker_create_time)


async def terminate_process_group(
    process: Process,
    *,
    worker_pid: int | None = None,
    worker_create_time: int | None = None,
) -> None:
    """Terminate an owned Worker process group/tree with bounded escalation."""
    identity_check = getattr(process, "verify_identity", None)
    if callable(identity_check) and not identity_check():
        return

    extra_worker_pid = (
        worker_pid
        if worker_pid is not None and worker_pid != process.pid
        else None
    )

    if os.name == "posix":
        need_signal = process.returncode is None or (
            extra_worker_pid is not None and _posix_pid_alive(extra_worker_pid)
        )
        if not need_signal:
            return
        _signal_posix_worker(process.pid, extra_worker_pid, signal.SIGTERM)
        if process.returncode is None:
            with anyio.move_on_after(5):
                await process.wait()
        elif extra_worker_pid is not None:
            await _wait_posix_pid_exit(extra_worker_pid, timeout_sec=5.0)

        still_running = process.returncode is None or (
            extra_worker_pid is not None and _posix_pid_alive(extra_worker_pid)
        )
        if not still_running:
            return
        if callable(identity_check) and not identity_check():
            return
        _signal_posix_worker(process.pid, extra_worker_pid, signal.SIGKILL)
        if process.returncode is None:
            with anyio.move_on_after(5):
                await process.wait()
        elif extra_worker_pid is not None:
            await _wait_posix_pid_exit(extra_worker_pid, timeout_sec=5.0)
        return

    await _terminate_windows_worker(
        process,
        extra_worker_pid=extra_worker_pid,
        worker_create_time=worker_create_time,
        identity_check=identity_check if callable(identity_check) else None,
    )


_SUPPORTED_INPUT_MAGIC_PREFIXES: tuple[bytes, ...] = (
    b"PK\x03\x04",
    b"PK\x05\x06",
    b"PK\x07\x08",
    b"JM\x01\x00",
    b"dex\n",
    b"dey\n",
    b"\xca\xfe\xba\xbe",
)


def validate_supported_input_magic(input_path: Path) -> None:
    """Reject non-archive/non-bytecode inputs before booting a Worker JVM."""
    try:
        with input_path.open("rb") as handle:
            prefix = handle.read(8)
    except OSError as error:
        raise WorkerLaunchError("input file could not be read") from error
    if not prefix:
        raise WorkerLaunchError("input file is empty")
    if any(prefix.startswith(magic) for magic in _SUPPORTED_INPUT_MAGIC_PREFIXES):
        return
    if zipfile.is_zipfile(input_path):
        return
    raise WorkerLaunchError(
        "input file is not a supported JAR, APK, DEX, ZIP, JMOD, or JVM class file"
    )


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
        validate_input_format: bool | None = None,
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
        self._validate_input_format = (
            process_starter is start_worker_process
            if validate_input_format is None
            else validate_input_format
        )

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
                default_autosave_dir=self._config.effective_autosave_dir,
            )
            if self._validate_input_format:
                validate_supported_input_magic(paths.input_path)
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

            effective_java = (
                await resolve_java_command(self._config.java_command, os.environ)
                if self._process_starter is start_worker_process
                else self._config.java_command
            )
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
                    java_command=effective_java,
                )
                environment = os.environ.copy()
                environment["RECAF_MCP_TOKEN"] = token
                process: Process | None = None
                observed_worker: list[int | None] = [None, None]
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
                        log_path,
                        observed_worker,
                    )
                    archive_export_supported = health[
                        "workspace_archive_export_supported"
                    ]
                    effective_autosave_path: Path | None = paths.autosave_path
                    output_format = (
                        health.get("workspace_output_format")
                        if archive_export_supported else None
                    )
                    if archive_export_supported:
                        if autosave_path is None:
                            effective_autosave_path = await self._reservations.set_autosave_format(
                                sid, output_format,
                            )
                        elif (
                            output_format in {"apk", "dex"}
                            and paths.autosave_path.suffix.lower() != f".{output_format}"
                        ):
                            raise WorkerLaunchError(
                                f"autosave_path extension must be .{output_format}"
                            )
                    if not archive_export_supported:
                        if autosave_path is not None:
                            raise WorkerLaunchError(
                                "autosave_path is unsupported for this workspace format"
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
                        output_format=output_format,
                        worker_pid=observed_worker[0],
                        worker_create_time=observed_worker[1],
                    )
                    await self._sessions.activate(session)
                    activated = True
                    return session
                except WorkerPortCollision as error:
                    if self._has_memory_failure(log_path):
                        raise self._diagnostic_error(str(error), log_path, token) from error
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
                        with anyio.CancelScope(shield=True):
                            await self._terminate_worker(
                                process,
                                observed_worker[0],
                                observed_worker[1],
                            )
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
            with anyio.CancelScope(shield=True):
                await self._sessions.abandon(sid)
                if paths is not None:
                    await self._reservations.release(sid)
                shutil.rmtree(session_root, ignore_errors=True)
            raise

    async def abort(self, sid: str) -> None:
        """Force-stop an owned session and release all launch-owned resources."""
        with anyio.CancelScope(shield=True):
            session = await self._sessions.get(sid)
            if session is None:
                await self._reservations.release(sid)
                return
            try:
                await self._terminate_worker(
                    session.process,
                    session.worker_pid,
                    session.worker_create_time,
                )
            finally:
                await self._sessions.remove(sid)
                await self._reservations.release(sid)
                shutil.rmtree(session.session_dir, ignore_errors=True)

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
        input_path: Path,
        *,
        java_command: str | None = None,
    ) -> list[str]:
        return [
            java_command or self._config.java_command,
            *self._config.worker_jvm_options,
            f"-Drecaf.mcp.port={port}",
            f"-Drecaf.mcp.input_path={input_path}",
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
        expected_input: Path,
        log_path: Path,
        observed_worker: list[int | None],
    ) -> dict[str, Any]:
        deadline = time.monotonic() + self._config.launch_timeout_sec
        while time.monotonic() < deadline:
            if process.returncode is not None:
                raise WorkerLaunchError(
                    f"Worker exited before readiness (exit code {process.returncode})"
                )
            failure = self._startup_failure(log_path)
            if failure is not None:
                raise WorkerLaunchError(
                    "Worker startup failed: " + failure.replace(token, "<redacted>")
                )
            try:
                health = await self._health_probe.probe(port, token)
            except WorkerNotListening:
                await self._sleep(self._config.readiness_poll_sec)
                continue

            worker_pid = self._validate_health_identity(
                health,
                expected_input,
                process.pid,
            )
            if observed_worker[0] != worker_pid:
                observed_worker[0] = worker_pid
                observed_worker[1] = (
                    windows_process_creation_time(worker_pid)
                    if worker_pid != process.pid
                    else None
                )
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
        raise WorkerLaunchError(
            f"Worker readiness timed out after {self._config.launch_timeout_sec:g}s; "
            "increase RECAF_LAUNCH_TIMEOUT_SEC for large inputs"
        )

    def _validate_health_identity(
        self,
        health: dict[str, Any],
        expected_input: Path,
        expected_pid: int,
    ) -> int:
        worker_pid = resolve_worker_pid(health, expected_pid)
        if worker_pid is None:
            raise WorkerLaunchError("Worker process identity mismatch")
        expected_path = str(expected_input)
        if health.get("expected_input_path") != expected_path:
            raise WorkerLaunchError("Worker expected-input identity mismatch")
        actual_path = health.get("input_path")
        if actual_path is not None and actual_path != expected_path:
            raise WorkerLaunchError("Worker input identity mismatch")
        if health.get("plugin_build") != self._expected_identity.plugin_build:
            raise WorkerLaunchError("Worker plugin build mismatch")
        validate_recaf_runtime(health, self._expected_identity.recaf_commit)
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
        if (
            health.get("workspace_archive_export_supported") is True
            and health.get("workspace_output_format") not in ("jar", "apk", "dex")
        ):
            raise WorkerLaunchError("Worker output format is missing or invalid")
        return worker_pid

    @staticmethod
    def _startup_failure(log_path: Path) -> str | None:
        """Read Recaf's terminal bootstrap error even when it stays alive in idle mode."""
        marker = None
        cause = None
        fatal_messages = (
            "Error handling loading of launch workspace content.",
            "Failed to initialize plugins",
        )
        try:
            with log_path.open("rb") as log_file:
                while raw_line := log_file.readline(4096):
                    line = raw_line.decode("utf-8", errors="replace").strip()
                    if marker is not None and re.match(r"^\d\d:\d\d:\d\d\.\d+ \[", line):
                        break
                    if "[LaunchBootstrap/" in line and "] ERROR" in line:
                        marker = next((value for value in fatal_messages if value in line), None)
                    elif marker is not None and (
                        line.startswith("Caused by: ")
                        or re.match(r"^(?:[\w$]+\.)*[\w$]*(?:Exception|Error)(?::|$)", line)
                    ):
                        cause = line.removeprefix("Caused by: ")
        except OSError:
            return None
        if marker is None:
            return None
        reason = f"{marker} {cause or ''}".strip()
        return "".join(character if ord(character) >= 32 else "?" for character in reason)[:600]

    @staticmethod
    def _has_memory_failure(log_path: Path) -> bool:
        """Find an OOM even before a long thread dump, using bounded memory."""
        try:
            with log_path.open("rb") as log_file:
                previous = b""
                while chunk := log_file.read(65536):
                    if b"OutOfMemoryError" in previous + chunk:
                        return True
                    previous = chunk[-32:]
        except OSError:
            pass
        return False

    @staticmethod
    def _diagnostic_error(
        message: str,
        log_path: Path,
        token: str,
    ) -> WorkerLaunchError:
        """Attach a bounded, control-character-safe, token-free log tail."""
        failure = WorkerLauncher._startup_failure(log_path)
        if failure is not None:
            failure = failure.replace(token, "<redacted>")
            if failure not in message:
                message = "Worker startup failed: " + failure + "\n" + message
        if WorkerLauncher._has_memory_failure(log_path):
            message = (
                "Worker exhausted JVM memory (OutOfMemoryError). "
                "Increase RECAF_WORKER_MAX_HEAP_MB or reduce RECAF_MAX_SESSIONS. "
                + message
            )
        try:
            with log_path.open("rb") as log_file:
                log_file.seek(0, os.SEEK_END)
                log_file.seek(max(0, log_file.tell() - 16384))
                data = log_file.read(16384)
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
