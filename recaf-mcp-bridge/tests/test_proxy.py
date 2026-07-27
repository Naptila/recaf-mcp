from __future__ import annotations

import time
from pathlib import Path
from typing import Any

import anyio
import mcp.types as types

from proxy import WorkerToolProxy
from sessions import PathReservations, Session, SessionManager
from worker import (
    WorkerMethodError,
    WorkerProtocolError,
    WorkerTransportError,
)


class FakeProcess:
    pid = 8001
    returncode: int | None = None
    stdin = None
    stdout = None
    stderr = None

    async def wait(self) -> int:
        while self.returncode is None:
            await anyio.sleep(0)
        return self.returncode

    async def aclose(self) -> None:
        return None

    def terminate(self) -> None:
        self.returncode = -15

    def kill(self) -> None:
        self.returncode = -9

    def send_signal(self, signal_number: int) -> None:
        self.returncode = -signal_number


class RecordingRpcClient:
    def __init__(self, result: types.CallToolResult | BaseException) -> None:
        self.result = result
        self.calls: list[tuple[int, str, str, dict[str, Any]]] = []
        self.operation_ids: list[str | None] = []
        self.timeouts: list[float] = []

    async def call_tool(
        self,
        port: int,
        token: str,
        name: str,
        arguments: dict[str, Any],
        *,
        operation_id: str | None = None,
        timeout_sec: float = 310.0,
    ) -> types.CallToolResult:
        self.calls.append((port, token, name, arguments))
        self.operation_ids.append(operation_id)
        self.timeouts.append(timeout_sec)
        if isinstance(self.result, BaseException):
            raise self.result
        return self.result

    async def request(
        self,
        port: int,
        token: str,
        method: str,
        params: dict[str, Any] | None = None,
        timeout_sec: float | None = None,
    ) -> dict[str, Any]:
        assert method == "health"
        return {"ok": True, "busy": False}


async def ready_session(
    manager: SessionManager,
    tmp_path: Path,
    reservations: PathReservations | None = None,
) -> Session:
    sid = await manager.reserve_slot()
    input_path = tmp_path / f"{sid}-input.jar"
    autosave_path = tmp_path / f"{sid}-autosave.jar"
    input_path.write_bytes(b"jar")
    if reservations is not None:
        reserved = await reservations.reserve(sid, input_path, autosave_path)
        input_path = reserved.input_path
        autosave_path = reserved.autosave_path
    session = Session(
        sid=sid,
        process=FakeProcess(),
        port=43121,
        token="proxy-secret-token",
        input_path=input_path,
        autosave_path=autosave_path,
        session_dir=tmp_path / sid,
        data_dir=tmp_path / sid / "data",
        log_path=tmp_path / sid / "worker.log",
        last_touched=time.monotonic() - 10,
        idle_ttl_sec=600,
    )
    await manager.activate(session)
    return session


def test_proxy_strips_database_and_preserves_complete_tool_result(
    tmp_path: Path,
) -> None:
    expected = types.CallToolResult(
        content=[types.TextContent(type="text", text="bounded result")],
        structuredContent={"hits": [1, 2], "next_offset": 2},
        isError=True,
    )
    rpc = RecordingRpcClient(expected)

    async def scenario() -> None:
        manager = SessionManager(1)
        session = await ready_session(manager, tmp_path)
        proxy = WorkerToolProxy(manager, rpc, {"search_code"})
        arguments = {
            "database": session.sid,
            "text": "needle",
            "limit": 2,
        }
        touched_before = session.last_touched

        result = await proxy.call("search_code", arguments)

        assert result is expected
        assert result.structuredContent == {"hits": [1, 2], "next_offset": 2}
        assert result.isError is True
        assert rpc.calls == [
            (
                43121,
                "proxy-secret-token",
                "search_code",
                {"text": "needle", "limit": 2},
            )
        ]
        assert arguments["database"] == session.sid
        assert rpc.operation_ids[0] is not None
        assert len(rpc.operation_ids[0]) == 32
        assert session.busy_count == 0
        assert session.last_touched > touched_before

    anyio.run(scenario)


def test_proxy_reserves_bulk_source_export_path_and_uses_long_timeout(
    tmp_path: Path,
) -> None:
    expected = types.CallToolResult(
        content=[types.TextContent(type="text", text="exported")],
        structuredContent={"path": str(tmp_path / "sources.zip")},
        isError=False,
    )
    rpc = RecordingRpcClient(expected)

    async def scenario() -> None:
        manager = SessionManager(1)
        reservations = PathReservations()
        session = await ready_session(manager, tmp_path, reservations)
        proxy = WorkerToolProxy(
            manager,
            rpc,
            {"export_sources"},
            reservations=reservations,
        )
        output = tmp_path / "sources.zip"

        result = await proxy.call(
            "export_sources",
            {
                "database": session.sid,
                "output_path": str(output),
                "filter": "example/",
            },
        )

        assert result is expected
        assert rpc.calls == [
            (
                43121,
                "proxy-secret-token",
                "export_sources",
                {
                    "output_path": str(output.resolve()),
                    "filter": "example/",
                },
            )
        ]
        assert rpc.timeouts == [1810.0]
        assert await reservations.owns(session.sid, output) is False

        relative = await proxy.call(
            "export_sources",
            {
                "database": session.sid,
                "output_path": "relative.zip",
            },
        )
        assert relative.structuredContent["error"] == "invalid_output_path"
        assert len(rpc.calls) == 1

        autosave = await proxy.call(
            "export_sources",
            {
                "database": session.sid,
                "output_path": str(session.autosave_path),
            },
        )
        assert autosave.structuredContent["error"] == "invalid_output_path"
        assert len(rpc.calls) == 1
        assert await reservations.owns(session.sid, session.autosave_path)

        failed_output = tmp_path / "failed-sources.zip"
        rpc.result = WorkerMethodError("decompiler failed", -32603)
        failed = await proxy.call(
            "export_sources",
            {
                "database": session.sid,
                "output_path": str(failed_output),
            },
        )
        assert failed.structuredContent["error"] == "worker_tool_error"
        assert await reservations.owns(session.sid, failed_output) is False

    anyio.run(scenario)


def test_proxy_rejects_internal_unknown_and_missing_database(tmp_path: Path) -> None:
    rpc = RecordingRpcClient(
        types.CallToolResult(content=[], structuredContent={}, isError=False)
    )

    async def scenario() -> None:
        manager = SessionManager(1)
        session = await ready_session(manager, tmp_path)
        proxy = WorkerToolProxy(manager, rpc, {"workspace_info"})

        internal = await proxy.call("shutdown", {"database": session.sid})
        missing = await proxy.call("workspace_info", {})
        unknown = await proxy.call("workspace_info", {"database": "missing"})

        assert internal.structuredContent["error"] == "unknown_tool"
        assert missing.structuredContent["error"] == "invalid_params"
        assert unknown.structuredContent["error"] == "unknown_database"
        assert rpc.calls == []

    anyio.run(scenario)


def test_proxy_marks_malformed_worker_unhealthy_but_not_tool_error(
    tmp_path: Path,
) -> None:
    async def scenario() -> None:
        manager = SessionManager(2)
        malformed_session = await ready_session(manager, tmp_path)
        malformed_proxy = WorkerToolProxy(
            manager,
            RecordingRpcClient(WorkerProtocolError("malformed")),
            {"workspace_info"},
        )
        malformed = await malformed_proxy.call(
            "workspace_info",
            {"database": malformed_session.sid},
        )
        assert malformed.structuredContent["error"] == "worker_protocol_error"
        assert malformed_session.state == "dead"
        assert malformed_session.busy_count == 0

        method_session = await ready_session(manager, tmp_path)
        method_proxy = WorkerToolProxy(
            manager,
            RecordingRpcClient(WorkerMethodError("bad arguments", -32602)),
            {"workspace_info"},
        )
        method = await method_proxy.call(
            "workspace_info",
            {"database": method_session.sid},
        )
        assert method.structuredContent["error"] == "worker_tool_error"
        assert method_session.state == "ready"
        assert method_session.busy_count == 0

    anyio.run(scenario)


def test_proxy_maps_worker_timeout_without_reusing_the_session_early(
    tmp_path: Path,
) -> None:
    async def scenario() -> None:
        manager = SessionManager(1)
        session = await ready_session(manager, tmp_path)
        rpc = RecordingRpcClient(WorkerMethodError("timed out", -32004))
        proxy = WorkerToolProxy(manager, rpc, {"search_code"})

        result = await proxy.call(
            "search_code",
            {"database": session.sid, "text": "needle"},
        )

        assert result.structuredContent["error"] == "worker_timeout"
        assert session.state == "ready"
        assert session.busy_count == 0

    anyio.run(scenario)


def test_proxy_aborts_worker_that_does_not_drain_after_timeout(
    tmp_path: Path,
) -> None:
    class StuckRpcClient(RecordingRpcClient):
        async def request(
            self,
            port: int,
            token: str,
            method: str,
            params: dict[str, Any] | None = None,
            timeout_sec: float | None = None,
        ) -> dict[str, Any]:
            assert method == "health"
            return {"ok": True, "busy": True}

    async def scenario() -> None:
        manager = SessionManager(1)
        session = await ready_session(manager, tmp_path)
        rpc = StuckRpcClient(WorkerMethodError("timed out", -32004))
        aborted: list[str] = []

        async def abort(database: str) -> None:
            aborted.append(database)

        proxy = WorkerToolProxy(
            manager,
            rpc,
            {"search_code"},
            abort_session=abort,
            drain_timeout_sec=0.001,
        )

        result = await proxy.call(
            "search_code",
            {"database": session.sid, "text": "needle"},
        )

        assert result.structuredContent["error"] == "worker_timeout"
        assert session.state == "dead"
        assert aborted == [session.sid]
        assert session.busy_count == 0

    anyio.run(scenario)


def test_proxy_cancellation_and_dead_transport_restore_busy_state(
    tmp_path: Path,
) -> None:
    class BlockingRpcClient:
        def __init__(self) -> None:
            self.started = anyio.Event()
            self.operation_id: str | None = None
            self.cancelled_operation_id: str | None = None

        async def call_tool(
            self,
            port: int,
            token: str,
            name: str,
            arguments: dict[str, Any],
            *,
            operation_id: str | None = None,
            timeout_sec: float = 310.0,
        ) -> types.CallToolResult:
            self.operation_id = operation_id
            self.started.set()
            await anyio.sleep_forever()

        async def request(
            self,
            port: int,
            token: str,
            method: str,
            params: dict[str, Any] | None = None,
            timeout_sec: float | None = None,
        ) -> dict[str, Any]:
            if method == "cancel_operation":
                self.cancelled_operation_id = params["operation_id"] if params else None
                return {"ok": True, "cancelled": True}
            assert method == "health"
            return {"ok": True, "busy": False}

    async def scenario() -> None:
        manager = SessionManager(2)
        session = await ready_session(manager, tmp_path)
        blocking = BlockingRpcClient()
        proxy = WorkerToolProxy(manager, blocking, {"workspace_info"})
        async with anyio.create_task_group() as tasks:
            tasks.start_soon(
                proxy.call,
                "workspace_info",
                {"database": session.sid},
            )
            await blocking.started.wait()
            assert session.busy_count == 1
            tasks.cancel_scope.cancel()
        assert session.busy_count == 0
        assert blocking.operation_id is not None
        assert blocking.cancelled_operation_id == blocking.operation_id

        dead_session = await ready_session(manager, tmp_path)

        class DyingTransportClient:
            async def call_tool(
                self,
                port: int,
                token: str,
                name: str,
                arguments: dict[str, Any],
                *,
                operation_id: str | None = None,
                timeout_sec: float = 310.0,
            ) -> types.CallToolResult:
                dead_session.process.returncode = 1
                raise WorkerTransportError("connection lost")

        transport_proxy = WorkerToolProxy(
            manager,
            DyingTransportClient(),
            {"workspace_info"},
        )
        result = await transport_proxy.call(
            "workspace_info",
            {"database": dead_session.sid},
        )
        assert result.structuredContent["error"] == "worker_transport_error"
        assert dead_session.state == "dead"
        assert dead_session.busy_count == 0

    anyio.run(scenario)
