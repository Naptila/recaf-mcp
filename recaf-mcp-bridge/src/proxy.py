"""Generic single-session routing for Worker-owned MCP tools."""

from __future__ import annotations

import secrets
import time
from collections.abc import Awaitable, Callable
from pathlib import Path
from typing import Any

import anyio
import mcp.types as types

from schema import INTERNAL_METHOD_NAMES
from sessions import (
    PathReservationError,
    PathReservations,
    Session,
    SessionManager,
)
from worker import (
    WorkerMethodError,
    WorkerProtocolError,
    WorkerRpcClient,
    WorkerRpcError,
    WorkerTransportError,
)


def _proxy_error(message: str, error_type: str) -> types.CallToolResult:
    bounded = message[:1000]
    return types.CallToolResult(
        content=[types.TextContent(type="text", text=bounded)],
        structuredContent={"error": error_type, "message": bounded},
        isError=True,
    )


_OUTPUT_PATH_TOOLS = frozenset({"export_sources"})
_LONG_RUNNING_TOOL_TIMEOUTS = {"export_sources": 1810.0}


class WorkerToolProxy:
    """Route only discovered tool names to their selected ready Worker."""

    def __init__(
        self,
        sessions: SessionManager,
        rpc_client: WorkerRpcClient,
        allowed_names: set[str],
        abort_session: Callable[[str], Awaitable[None]] | None = None,
        reservations: PathReservations | None = None,
        drain_timeout_sec: float = 30.0,
    ) -> None:
        forbidden = INTERNAL_METHOD_NAMES.intersection(allowed_names)
        if forbidden:
            raise ValueError(f"proxy allowlist contains internal methods: {forbidden}")
        self._sessions = sessions
        self._rpc_client = rpc_client
        self._allowed_names = frozenset(allowed_names)
        self._abort_session = abort_session
        self._reservations = reservations
        if drain_timeout_sec <= 0:
            raise ValueError("drain_timeout_sec must be positive")
        self._drain_timeout_sec = drain_timeout_sec

    async def call(
        self,
        name: str,
        arguments: dict[str, Any],
    ) -> types.CallToolResult:
        if name in INTERNAL_METHOD_NAMES or name not in self._allowed_names:
            return _proxy_error("Worker tool is not allowed", "unknown_tool")
        forwarded = dict(arguments)
        database = forwarded.pop("database", None)
        if not isinstance(database, str) or not database:
            return _proxy_error("database is required", "invalid_params")
        session = await self._sessions.get(database)
        if session is None:
            return _proxy_error("unknown Recaf session", "unknown_database")
        if not self._is_ready(session):
            return _proxy_error("Recaf session is not ready", "session_not_ready")

        session.busy_count += 1
        session.last_touched = time.monotonic()
        operation_id = secrets.token_hex(16)
        reserved_output: Path | None = None
        try:
            async with session.operation_lock:
                if not self._is_ready(session):
                    return _proxy_error(
                        "Recaf session is not ready",
                        "session_not_ready",
                    )
                if name in _OUTPUT_PATH_TOOLS:
                    requested_output = forwarded.get("output_path")
                    if not isinstance(requested_output, str) or not requested_output:
                        return _proxy_error(
                            "output_path is required",
                            "invalid_params",
                        )
                    if self._reservations is None:
                        return _proxy_error(
                            "output path reservations are unavailable",
                            "bridge_configuration_error",
                        )
                    canonical_output = await self._reservations.reserve_output(
                        session.sid,
                        requested_output,
                    )
                    if canonical_output == session.autosave_path:
                        return _proxy_error(
                            "output path aliases the session autosave path",
                            "invalid_output_path",
                        )
                    reserved_output = canonical_output
                    forwarded["output_path"] = str(reserved_output)
                try:
                    return await self._rpc_client.call_tool(
                        session.port,
                        session.token,
                        name,
                        forwarded,
                        operation_id=operation_id,
                        timeout_sec=_LONG_RUNNING_TOOL_TIMEOUTS.get(name, 310.0),
                    )
                finally:
                    if reserved_output is not None:
                        with anyio.CancelScope(shield=True):
                            await self._reservations.release_output(
                                session.sid,
                                reserved_output,
                            )
        except PathReservationError as error:
            return _proxy_error(str(error), "invalid_output_path")
        except WorkerMethodError as error:
            if error.code in {-32004, -32005}:
                with anyio.CancelScope(shield=True):
                    await self._wait_until_worker_idle(session)
                error_type = (
                    "worker_timeout" if error.code == -32004 else "worker_cancelled"
                )
                return _proxy_error(str(error), error_type)
            return _proxy_error(str(error), "worker_tool_error")
        except WorkerProtocolError as error:
            session.state = "dead"
            return _proxy_error(str(error), "worker_protocol_error")
        except WorkerTransportError as error:
            session.state = "dead"
            return _proxy_error(str(error), "worker_transport_error")
        except WorkerRpcError as error:
            return _proxy_error(str(error), "worker_error")
        except anyio.get_cancelled_exc_class():
            with anyio.CancelScope(shield=True):
                await self._cancel_and_drain(session, operation_id)
            raise
        finally:
            session.busy_count -= 1
            session.last_touched = time.monotonic()

    async def _cancel_and_drain(self, session: Session, operation_id: str) -> None:
        try:
            await self._rpc_client.request(
                session.port,
                session.token,
                "cancel_operation",
                {"operation_id": operation_id},
                timeout_sec=5.0,
            )
        except WorkerRpcError:
            if session.process.returncode is not None:
                session.state = "dead"
                return
        await self._wait_until_worker_idle(session)

    async def _wait_until_worker_idle(self, session: Session) -> None:
        failures = 0
        deadline = time.monotonic() + self._drain_timeout_sec
        while session.process.returncode is None:
            try:
                health = await self._rpc_client.request(
                    session.port,
                    session.token,
                    "health",
                    timeout_sec=2.0,
                )
                failures = 0
            except WorkerRpcError:
                failures += 1
                if failures >= 5:
                    await self._abort_unresponsive(session)
                    return
                await anyio.sleep(0.05)
                continue
            if not isinstance(health, dict) or health.get("ok") is not True:
                await self._abort_unresponsive(session)
                return
            if health.get("busy") is False:
                return
            if time.monotonic() >= deadline:
                await self._abort_unresponsive(session)
                return
            await anyio.sleep(0.05)
        await self._abort_unresponsive(session)

    async def _abort_unresponsive(self, session: Session) -> None:
        session.state = "dead"
        if self._abort_session is not None:
            await self._abort_session(session.sid)

    @staticmethod
    def _is_ready(session: Session) -> bool:
        return session.state == "ready" and session.process.returncode is None
