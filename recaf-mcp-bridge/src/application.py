"""Bridge tool service connecting MCP lifecycle calls to Worker sessions."""

from __future__ import annotations

import os
import time
from collections.abc import Callable
from pathlib import Path
from typing import Any, Protocol

import anyio
import mcp.types as types

from config import BridgeConfig, response_profile_from_env
from discovery import DiscoveredWorkerCatalog, SchemaWorkerDiscovery
from launcher import WorkerIdentity, WorkerLaunchError, WorkerLauncher
from proxy import WorkerToolProxy
from recovery import RecoveryRegistry
from results import expose_structured_content
from schema import StaticToolCatalog, inject_database
from sessions import (
    PathReservationError,
    PathReservations,
    Session,
    SessionCapacityError,
    SessionManager,
    SessionStateError,
)
from tools import LIFECYCLE_TOOLS, LifecycleToolService
from watchdog import IdleWatchdog
from worker import (
    WorkerMethodError,
    WorkerProtocolError,
    WorkerRpcClient,
    WorkerRpcError,
    WorkerTransportError,
)


class CatalogDiscovery(Protocol):
    async def discover(self) -> DiscoveredWorkerCatalog: ...


LauncherFactory = Callable[[WorkerIdentity], WorkerLauncher]


class SaveWorkspaceError(RuntimeError):
    """A sanitized save failure which leaves the Worker available for retry."""


class BridgeShutdownError(RuntimeError):
    """Raised after live Workers are registered for authenticated recovery."""

    def __init__(self, instructions: list[str]) -> None:
        self.instructions = tuple(instructions)
        super().__init__(
            "Bridge shutdown left recoverable Worker sessions alive:\n"
            + "\n".join(instructions)
        )


def _error_result(message: str, error_type: str) -> types.CallToolResult:
    bounded = message[:1000]
    return types.CallToolResult(
        content=[types.TextContent(type="text", text=bounded)],
        structuredContent={"error": error_type, "message": bounded},
        isError=True,
    )


class BridgeToolService:
    """Own the one-time catalog, live sessions, and initial routed tools."""

    def __init__(
        self,
        config: BridgeConfig,
        *,
        sessions: SessionManager | None = None,
        reservations: PathReservations | None = None,
        discovery: CatalogDiscovery | None = None,
        launcher_factory: LauncherFactory | None = None,
        rpc_client: WorkerRpcClient | None = None,
        recovery_registry: RecoveryRegistry | None = None,
        watchdog_interval_sec: float = 5.0,
    ) -> None:
        self._config = config.validate()
        self._sessions = sessions or SessionManager(config.max_sessions)
        self._reservations = reservations or PathReservations()
        self._discovery = discovery or SchemaWorkerDiscovery(config)
        self._rpc_client = rpc_client or WorkerRpcClient()
        self._recovery_registry = recovery_registry or RecoveryRegistry(
            config.effective_recovery_dir,
            self._sessions,
            self._reservations,
        )
        self._launcher_factory = launcher_factory or (
            lambda identity: WorkerLauncher(
                config,
                self._sessions,
                self._reservations,
                identity,
            )
        )
        self._catalog: StaticToolCatalog | None = None
        self._worker_names: set[str] = set()
        self._launcher: WorkerLauncher | None = None
        self._proxy: WorkerToolProxy | None = None
        self._identity: WorkerIdentity | None = None
        self._shutting_down = False
        self._shutdown_event = anyio.Event()
        self._watchdog = IdleWatchdog(
            self._sessions,
            self._expire_idle_locked,
            self._finalize_idle_session,
            interval_sec=watchdog_interval_sec,
        )

        self._initialization_lock = anyio.Lock()

    async def list_tools(self) -> list[types.Tool]:
        """Discover once, then return the same defensive catalog snapshot."""
        if self._shutting_down:
            return []
        await self._ensure_initialized()
        assert self._catalog is not None
        return self._catalog.snapshot()

    async def call(
        self,
        name: str,
        arguments: dict[str, Any],
    ) -> types.CallToolResult:
        result = await self._call_unformatted(name, arguments)
        return expose_structured_content(
            name,
            result,
            enabled=self._config.response_profile == "legacy",
        )

    async def _call_unformatted(
        self,
        name: str,
        arguments: dict[str, Any],
    ) -> types.CallToolResult:
        if self._shutting_down:
            return _error_result("Bridge is shutting down", "bridge_shutting_down")
        await self._ensure_initialized()
        if name == "recaf_open":
            return await self._open(arguments)
        if name == "recaf_list":
            return await self._list_sessions()
        if name == "set_idle_ttl":
            return await self._set_idle_ttl(arguments)
        if name == "recaf_save":
            return await self._save(arguments)
        if name == "recaf_close":
            return await self._close(arguments)
        if name in {tool.name for tool in LIFECYCLE_TOOLS}:
            return _error_result(
                f"{name} is not implemented yet",
                "not_implemented",
            )
        if name in self._worker_names:
            assert self._proxy is not None
            return await self._proxy.call(name, arguments)
        return _error_result(f"Unknown tool: {name}", "unknown_tool")

    async def close(self) -> None:
        """Save live sessions, registering failures for authenticated recovery."""
        self.begin_shutdown()
        await anyio.sleep(0)
        launcher = self._launcher
        if launcher is None:
            return
        recovery_instructions: list[str] = []
        for session in await self._sessions.snapshot():
            try:
                instruction = await self._shutdown_bridge_session(session.sid)
            except BaseException as error:
                instruction = (
                    f"database={session.sid} pid={session.process.pid} "
                    f"log={session.log_path}: shutdown cleanup failed: "
                    f"{str(error)[:500]}"
                )
            if instruction is not None:
                recovery_instructions.append(instruction)
        if recovery_instructions:
            raise BridgeShutdownError(recovery_instructions)

    async def run_background(self) -> None:
        """Run Bridge background coordinators until the server cancels them."""
        async with anyio.create_task_group() as tasks:
            tasks.start_soon(self._watchdog.run)
            await self._shutdown_event.wait()
            tasks.cancel_scope.cancel()

    def begin_shutdown(self) -> None:
        """Reject new calls and request cancellation of background work."""
        if not self._shutting_down:
            self._shutting_down = True
            self._shutdown_event.set()

    async def _ensure_initialized(self) -> None:
        if self._catalog is not None:
            return
        async with self._initialization_lock:
            if self._catalog is not None:
                return
            discovered = await self._discovery.discover()
            self._identity = discovered.identity
            client_worker_tools = tuple(
                inject_database(tool) for tool in discovered.tools
            )
            self._catalog = StaticToolCatalog(
                (*LIFECYCLE_TOOLS, *client_worker_tools)
            )
            self._worker_names = {tool.name for tool in discovered.tools}
            self._launcher = self._launcher_factory(discovered.identity)
            self._proxy = WorkerToolProxy(
                self._sessions,
                self._rpc_client,
                self._worker_names,
                abort_session=self._launcher.abort,
                reservations=self._reservations,
            )
            await self._recovery_registry.recover(discovered.identity)

    async def _open(self, arguments: dict[str, Any]) -> types.CallToolResult:
        assert self._launcher is not None
        idle_ttl_sec = arguments.get("idle_ttl_sec")
        if idle_ttl_sec is not None and (
            isinstance(idle_ttl_sec, bool)
            or not isinstance(idle_ttl_sec, int)
            or not 0 <= idle_ttl_sec <= 604800
        ):
            return _error_result(
                "idle_ttl_sec must be an integer between 0 and 604800",
                "invalid_params",
            )
        try:
            session = await self._launcher.launch(
                arguments["input_path"],
                arguments.get("autosave_path"),
                idle_ttl_sec,
            )
        except KeyError:
            return _error_result("input_path is required", "invalid_params")
        except (
            OSError,
            PathReservationError,
            SessionCapacityError,
            ValueError,
            WorkerLaunchError,
        ) as error:
            return _error_result(str(error), "open_failed")
        structured = {
            "database": session.sid,
            "state": session.state,
            "input_path": str(session.input_path),
            "autosave_path": (
                str(session.autosave_path)
                if session.autosave_path is not None
                else None
            ),
            "output_format": session.output_format,
            "workspace_archive_export_supported": (
                session.autosave_path is not None
            ),
            "port": session.port,
            "idle_ttl_sec": session.idle_ttl_sec,
        }
        return types.CallToolResult(
            content=[
                types.TextContent(
                    type="text",
                    text=f"Opened {session.input_path.name} as {session.sid}.",
                )
            ],
            structuredContent=structured,
            isError=False,
        )

    async def _list_sessions(self) -> types.CallToolResult:
        now = time.monotonic()
        snapshot = await self._sessions.snapshot_state()
        sessions = [
            self._public_session(session, now)
            for session in snapshot.sessions
        ]
        text = (
            "No Recaf sessions are open."
            if snapshot.occupied_slots == 0
            else f"{len(sessions)} Recaf session(s) open, {len(snapshot.pending_ids)} starting."
        )
        return types.CallToolResult(
            content=[types.TextContent(type="text", text=text)],
            structuredContent={
                "sessions": sessions,
                "pending_sessions": [
                    {"database": sid, "state": "starting"}
                    for sid in snapshot.pending_ids
                ],
                "occupied_slots": snapshot.occupied_slots,
                "max_sessions": snapshot.max_sessions,
                "available_slots": snapshot.max_sessions - snapshot.occupied_slots,
            },
            isError=False,
        )

    async def _set_idle_ttl(
        self,
        arguments: dict[str, Any],
    ) -> types.CallToolResult:
        database = arguments.get("database")
        if not isinstance(database, str) or not database:
            return _error_result("database is required", "invalid_params")
        idle_ttl_sec = arguments.get("idle_ttl_sec")
        if (
            isinstance(idle_ttl_sec, bool)
            or not isinstance(idle_ttl_sec, int)
            or not 0 <= idle_ttl_sec <= 604800
        ):
            return _error_result(
                "idle_ttl_sec must be an integer between 0 and 604800",
                "invalid_params",
            )
        try:
            session = await self._sessions.update_idle_ttl(
                database,
                idle_ttl_sec,
                time.monotonic(),
            )
        except SessionStateError as error:
            return _error_result(str(error), "session_not_ready")
        if session is None:
            return _error_result("unknown Recaf session", "unknown_database")
        return types.CallToolResult(
            content=[
                types.TextContent(
                    type="text",
                    text=(
                        f"Updated idle TTL for Recaf session {database} to "
                        f"{idle_ttl_sec} seconds."
                    ),
                )
            ],
            structuredContent={
                "database": database,
                "idle_ttl_sec": idle_ttl_sec,
            },
            isError=False,
        )

    async def _close(self, arguments: dict[str, Any]) -> types.CallToolResult:
        database = arguments.get("database")
        if not isinstance(database, str) or not database:
            return _error_result("database is required", "invalid_params")
        requested_save = arguments.get("save")
        if requested_save is not None and not isinstance(requested_save, bool):
            return _error_result("save must be a boolean", "invalid_params")
        session = await self._sessions.get(database)
        if session is None:
            return _error_result("unknown Recaf session", "unknown_database")
        save = (
            session.autosave_path is not None
            if requested_save is None
            else requested_save
        )
        if session.autosave_path is None and (
            save or arguments.get("output_path") is not None
        ):
            return _error_result(
                "workspace format does not support saving",
                "save_unsupported",
            )
        if not save and arguments.get("output_path") is not None:
            return _error_result(
                "output_path cannot be used when save is false",
                "invalid_params",
            )
        if save:
            return await self._save_and_close(database, arguments.get("output_path"))
        try:
            closed = await self._close_session(database)
        except SessionStateError as error:
            return _error_result(str(error), "session_closing")
        if closed is None:
            return _error_result("unknown Recaf session", "unknown_database")
        graceful, warning = closed
        structured: dict[str, Any] = {
            "database": database,
            "closed": True,
            "saved": False,
            "graceful": graceful,
        }
        if warning is not None:
            structured["warning"] = warning
        return types.CallToolResult(
            content=[
                types.TextContent(
                    type="text",
                    text=f"Closed Recaf session {database} without saving.",
                )
            ],
            structuredContent=structured,
            isError=False,
        )

    async def _save(self, arguments: dict[str, Any]) -> types.CallToolResult:
        database = arguments.get("database")
        if not isinstance(database, str) or not database:
            return _error_result("database is required", "invalid_params")
        output_path = arguments.get("output_path")
        if output_path is not None and (
            not isinstance(output_path, str) or not output_path
        ):
            return _error_result("output_path must be a string", "invalid_params")
        overwrite = arguments.get("overwrite", False)
        if not isinstance(overwrite, bool):
            return _error_result("overwrite must be a boolean", "invalid_params")
        session = await self._sessions.get(database)
        if session is None:
            return _error_result("unknown Recaf session", "unknown_database")
        if session.autosave_path is None:
            return _error_result(
                "workspace format does not support saving",
                "save_unsupported",
            )
        if not self._session_ready(session):
            return _error_result("Recaf session is not ready", "session_not_ready")

        session.busy_count += 1
        session.last_touched = time.monotonic()
        try:
            async with session.operation_lock:
                if not self._session_ready(session):
                    return _error_result(
                        "Recaf session is not ready",
                        "session_not_ready",
                    )
                try:
                    saved = await self._save_locked(
                        session,
                        output_path,
                        overwrite,
                    )
                except (PathReservationError, SaveWorkspaceError) as error:
                    return _error_result(str(error), "save_failed")
        finally:
            session.busy_count -= 1
            session.last_touched = time.monotonic()
        return self._save_result(session, saved)

    async def _save_and_close(
        self,
        database: str,
        output_path: Any,
    ) -> types.CallToolResult:
        if output_path is not None and (
            not isinstance(output_path, str) or not output_path
        ):
            return _error_result("output_path must be a string", "invalid_params")
        try:
            session = await self._sessions.begin_close(database)
        except SessionStateError as error:
            return _error_result(str(error), "session_closing")
        if session is None:
            return _error_result("unknown Recaf session", "unknown_database")
        assert self._launcher is not None

        saved: dict[str, Any] | None = None
        save_error: Exception | None = None
        graceful = False
        warning: str | None = None
        session.busy_count += 1
        session.last_touched = time.monotonic()
        try:
            async with session.operation_lock:
                try:
                    saved = await self._save_locked(
                        session,
                        output_path,
                        overwrite=False,
                    )
                except (PathReservationError, SaveWorkspaceError) as error:
                    save_error = error
                if saved is not None:
                    graceful, warning = await self._shutdown_locked(session)
        finally:
            session.busy_count -= 1
            session.last_touched = time.monotonic()
            with anyio.CancelScope(shield=True):
                if saved is None:
                    await self._sessions.cancel_close(database)
                else:
                    await self._launcher.abort(database)
        if save_error is not None:
            return _error_result(str(save_error), "save_failed")
        if saved is None:
            return _error_result("save was interrupted", "save_failed")

        structured: dict[str, Any] = {
            "database": database,
            "closed": True,
            "saved": True,
            **{key: value for key, value in saved.items() if key != "ok"},
            "graceful": graceful,
        }
        if warning is not None:
            structured["warning"] = warning
        return types.CallToolResult(
            content=[
                types.TextContent(
                    type="text",
                    text=f"Saved and closed Recaf session {database}.",
                )
            ],
            structuredContent=structured,
            isError=False,
        )

    async def _save_locked(
        self,
        session: Session,
        output_path: str | None,
        overwrite: bool,
    ) -> dict[str, Any]:
        if session.autosave_path is None:
            raise SaveWorkspaceError(
                "workspace format does not support saving"
            )
        requested = session.autosave_path if output_path is None else output_path
        destination: Path | None = None
        try:
            destination = await self._reservations.reserve_output(
                session.sid,
                requested,
            )
            managed = destination == session.autosave_path
            try:
                result = await self._rpc_client.request(
                    session.port,
                    session.token,
                    "save_workspace",
                    {
                        "destination": str(destination),
                        "overwrite": managed or overwrite,
                    },
                    timeout_sec=310.0,
                )
            except WorkerMethodError as error:
                raise SaveWorkspaceError(str(error)) from error
            except (WorkerProtocolError, WorkerTransportError) as error:
                session.state = "dead"
                raise SaveWorkspaceError(str(error)) from error
            except WorkerRpcError as error:
                raise SaveWorkspaceError(str(error)) from error
            saved = self._validate_save_result(result, destination)
            session.save_error = None
            session.save_failure_count = 0
            session.retry_after = 0.0
            return saved
        except (OSError, PathReservationError, SaveWorkspaceError) as error:
            session.save_error = str(error)[:1000]
            if isinstance(error, (PathReservationError, SaveWorkspaceError)):
                raise
            raise SaveWorkspaceError(str(error)) from error
        finally:
            if destination is not None:
                await self._reservations.release_output(session.sid, destination)

    @staticmethod
    def _validate_save_result(result: Any, destination: Path) -> dict[str, Any]:
        if not isinstance(result, dict) or result.get("ok") is not True:
            raise SaveWorkspaceError("Worker returned an invalid save result")
        result_path = result.get("path")
        size_bytes = result.get("size_bytes")
        if not isinstance(result_path, str):
            raise SaveWorkspaceError("Worker save result has no path")
        try:
            canonical_result = Path(result_path).resolve(strict=True)
        except OSError as error:
            raise SaveWorkspaceError("Worker save output does not exist") from error
        if canonical_result != destination:
            raise SaveWorkspaceError("Worker save path does not match destination")
        if isinstance(size_bytes, bool) or not isinstance(size_bytes, int):
            raise SaveWorkspaceError("Worker save result has invalid size")
        actual_size = canonical_result.stat().st_size
        if size_bytes <= 0 or actual_size != size_bytes:
            raise SaveWorkspaceError("Worker save output size is invalid")
        metadata = {"ok": True, "path": str(canonical_result), "size_bytes": size_bytes}
        output_format = result.get("format")
        if output_format is not None:
            if output_format not in ("jar", "apk", "dex"):
                raise SaveWorkspaceError("Worker save result has invalid format")
            signature = result.get("apk_signature_status")
            expected_signature = "unsigned" if output_format == "apk" else "not_applicable"
            if signature != expected_signature:
                raise SaveWorkspaceError("Worker save result has invalid APK signature status")
            metadata.update(format=output_format, apk_signature_status=signature)
        return metadata

    @staticmethod
    def _save_result(
        session: Session,
        saved: dict[str, Any],
    ) -> types.CallToolResult:
        structured = {
            "database": session.sid,
            **saved,
        }
        return types.CallToolResult(
            content=[
                types.TextContent(
                    type="text",
                    text=f"Saved Recaf session {session.sid} to {saved['path']}.",
                )
            ],
            structuredContent=structured,
            isError=False,
        )

    async def _close_session(
        self,
        database: str,
    ) -> tuple[bool, str | None] | None:
        session = await self._sessions.begin_close(database)
        if session is None:
            return None
        assert self._launcher is not None
        graceful = False
        warning: str | None = None
        try:
            async with session.operation_lock:
                graceful, warning = await self._shutdown_locked(session)
        finally:
            with anyio.CancelScope(shield=True):
                await self._launcher.abort(database)
        return graceful, warning

    async def _shutdown_bridge_session(self, database: str) -> str | None:
        """Close unsavable sessions; save or recover savable sessions."""
        session = await self._sessions.get(database)
        if session is None:
            return None
        assert self._launcher is not None
        assert self._identity is not None
        if session.process.returncode is not None:
            await self._launcher.abort(database)
            return None

        try:
            claimed = await self._sessions.begin_close(database)
        except SessionStateError:
            claimed = session
        if claimed is None:
            return None

        discard_only = claimed.autosave_path is None
        completed = False
        failure: str | None = None
        with anyio.move_on_after(30) as drain_scope:
            try:
                async with claimed.operation_lock:
                    if claimed.process.returncode is not None:
                        completed = True
                    else:
                        if not discard_only:
                            await self._save_locked(
                                claimed,
                                output_path=None,
                                overwrite=False,
                            )
                        completed = True
                        await self._shutdown_locked(claimed)
            except (PathReservationError, SaveWorkspaceError) as error:
                failure = str(error)[:1000]
        if drain_scope.cancel_called:
            failure = "timed out waiting for in-flight session operations"

        if completed or discard_only:
            with anyio.CancelScope(shield=True):
                await self._launcher.abort(database)
            return None

        failure = failure or "session could not be saved during Bridge shutdown"
        claimed.save_error = failure
        try:
            with anyio.CancelScope(shield=True):
                registry_path = await self._recovery_registry.persist(
                    claimed,
                    self._identity,
                    failure,
                )
        except Exception as error:
            return (
                f"database={claimed.sid} pid={claimed.process.pid} "
                f"log={claimed.log_path}: {failure}; recovery metadata failed: "
                f"{str(error)[:500]}"
            )
        return (
            f"database={claimed.sid} pid={claimed.process.pid} "
            f"registry={registry_path} log={claimed.log_path}: {failure}. "
            "Restart the Bridge with the same recovery directory to authenticate "
            "and adopt this Worker."
        )

    async def _shutdown_locked(
        self,
        session: Session,
    ) -> tuple[bool, str | None]:
        warning: str | None = None
        try:
            await self._rpc_client.request(
                session.port,
                session.token,
                "shutdown",
            )
            with anyio.move_on_after(5):
                await session.process.wait()
        except WorkerRpcError as error:
            warning = str(error)[:1000]
        return session.process.returncode is not None, warning

    async def _expire_idle_locked(self, session: Session) -> None:
        """Persist savable sessions, then request shutdown while locked."""
        if session.autosave_path is not None:
            await self._save_locked(session, output_path=None, overwrite=False)
        await self._shutdown_locked(session)

    async def _finalize_idle_session(self, database: str) -> None:
        """Apply process-group fallback and release an expired session."""
        assert self._launcher is not None
        await self._launcher.abort(database)

    @staticmethod
    def _session_ready(session: Session) -> bool:
        return session.state == "ready" and session.process.returncode is None

    @staticmethod
    def _public_session(session: Session, now: float) -> dict[str, Any]:
        state = (
            "dead"
            if session.process.returncode is not None
            else session.state
        )
        idle_remaining: float | None
        if session.idle_ttl_sec == 0:
            idle_remaining = None
        else:
            idle_remaining = max(
                0.0,
                session.idle_ttl_sec - (now - session.last_touched),
            )
        return {
            "database": session.sid,
            "state": state,
            "input_path": str(session.input_path),
            "autosave_path": (
                str(session.autosave_path)
                if session.autosave_path is not None
                else None
            ),
            "output_format": session.output_format,
            "workspace_archive_export_supported": (
                session.autosave_path is not None
            ),
            "port": session.port,
            "idle_ttl_sec": session.idle_ttl_sec,
            "idle_remaining_sec": idle_remaining,
            "busy": session.busy_count > 0,
            "save_error": session.save_error,
        }


def tool_service_from_env() -> LifecycleToolService | BridgeToolService:
    """Use installed Recaf when configured, retaining a protocol-only fallback."""
    if "RECAF_JAR" not in os.environ:
        return LifecycleToolService(response_profile_from_env(os.environ))
    return BridgeToolService(BridgeConfig.from_env())
