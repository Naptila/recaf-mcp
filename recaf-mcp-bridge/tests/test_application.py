from __future__ import annotations

import time
from pathlib import Path
from typing import Any

import anyio
import mcp.types as types
import pytest

from application import BridgeShutdownError, BridgeToolService
from conftest import make_test_plugin_jar, make_test_recaf_jar
from config import BridgeConfig
from discovery import DiscoveredWorkerCatalog
from launcher import WorkerIdentity
from sessions import PathReservations, Session, SessionManager
from worker import WorkerMethodError


FINGERPRINT = "b" * 64


def test_list_reports_pending_launches_and_capacity(tmp_path: Path) -> None:
    config = make_config(tmp_path)
    manager = SessionManager(1)
    service = BridgeToolService(config, sessions=manager, discovery=FakeDiscovery())

    async def scenario() -> None:
        sid = await manager.reserve_slot()
        listed = await service.call("recaf_list", {})
        assert listed.structuredContent == {
            "sessions": [], "pending_sessions": [{"database": sid, "state": "starting"}],
            "occupied_slots": 1, "max_sessions": 1, "available_slots": 0,
        }
        assert "1 starting" in listed.content[0].text
        await manager.abandon(sid)
        empty = (await service.call("recaf_list", {})).structuredContent
        assert empty["pending_sessions"] == []
        assert empty["occupied_slots"] == 0
        assert empty["available_slots"] == 1
        await service.close()

    anyio.run(scenario)


def make_config(root: Path) -> BridgeConfig:
    return BridgeConfig(
        recaf_jar=make_test_recaf_jar(root),
        plugin_jar=make_test_plugin_jar(root / "plugins"),
        session_root=(root / "sessions").resolve(),
        max_sessions=1,
    )


class FakeProcess:
    pid = 7001
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


class FakeDiscovery:
    def __init__(self) -> None:
        self.calls = 0

    async def discover(self) -> DiscoveredWorkerCatalog:
        self.calls += 1
        return DiscoveredWorkerCatalog(
            WorkerIdentity("1.0.0", FINGERPRINT),
            (
                types.Tool(
                    name="workspace_info",
                    description="Return workspace information.",
                    inputSchema={
                        "type": "object",
                        "properties": {},
                        "additionalProperties": False,
                    },
                ),
            ),
        )


class FakeLauncher:
    def __init__(
        self,
        manager: SessionManager,
        reservations: PathReservations,
        root: Path,
    ) -> None:
        self.manager = manager
        self.reservations = reservations
        self.root = root
        self.aborted: list[str] = []

    async def launch(
        self,
        input_path: str | Path,
        autosave_path: str | Path | None = None,
        idle_ttl_sec: int | None = None,
    ) -> Session:
        unsupported = Path(input_path).suffix.lower() == ".bin"
        if unsupported and autosave_path is not None:
            raise ValueError("autosave_path is unsupported for this workspace format")
        sid = await self.manager.reserve_slot()
        paths = await self.reservations.reserve(sid, input_path, autosave_path)
        effective_autosave = paths.autosave_path
        if unsupported:
            await self.reservations.disable_autosave(
                sid,
                paths.autosave_path,
            )
            effective_autosave = None
        session = Session(
            sid=sid,
            process=FakeProcess(),
            port=43111,
            token="secret-worker-token",
            input_path=paths.input_path,
            autosave_path=effective_autosave,
            session_dir=self.root / sid,
            data_dir=self.root / sid / "data",
            log_path=self.root / sid / "worker.log",
            last_touched=time.monotonic(),
            idle_ttl_sec=idle_ttl_sec if idle_ttl_sec is not None else 600,
        )
        await self.manager.activate(session)
        return session

    async def abort(self, sid: str) -> None:
        self.aborted.append(sid)
        session = await self.manager.remove(sid)
        if session is not None:
            session.process.returncode = -15
        await self.reservations.release(sid)


class FakeRpcClient:
    def __init__(self) -> None:
        self.calls: list[tuple[int, str, str, dict[str, Any]]] = []
        self.requests: list[tuple[int, str, str, dict[str, Any] | None]] = []
        self.process: FakeProcess | None = None

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
        return types.CallToolResult(
            content=[types.TextContent(type="text", text="workspace open")],
            structuredContent={"workspace_open": True, "class_count": 7},
            isError=False,
        )

    async def request(
        self,
        port: int,
        token: str,
        method: str,
        params: dict[str, Any] | None = None,
        timeout_sec: float | None = None,
    ) -> dict[str, Any]:
        self.requests.append((port, token, method, params))
        if method == "save_workspace":
            assert timeout_sec == 310.0
            assert params is not None
            destination = Path(params["destination"])
            destination.write_bytes(b"saved workspace")
            return {
                "ok": True,
                "path": str(destination.resolve()),
                "size_bytes": destination.stat().st_size,
            }
        assert method == "shutdown"
        assert self.process is not None
        self.process.returncode = 0
        return {"ok": True}


def test_service_discovers_once_and_routes_initial_session_tools(
    tmp_path: Path,
) -> None:
    input_path = tmp_path / "input.jar"
    input_path.write_bytes(b"jar")
    config = make_config(tmp_path)
    manager = SessionManager(1)
    reservations = PathReservations()
    discovery = FakeDiscovery()
    launcher = FakeLauncher(manager, reservations, config.session_root)
    rpc_client = FakeRpcClient()
    service = BridgeToolService(
        config,
        sessions=manager,
        reservations=reservations,
        discovery=discovery,
        launcher_factory=lambda identity: launcher,
        rpc_client=rpc_client,
    )

    async def scenario() -> None:
        first = await service.list_tools()
        second = await service.list_tools()
        assert discovery.calls == 1
        assert [tool.name for tool in first] == [
            "recaf_open",
            "recaf_save",
            "recaf_close",
            "recaf_list",
            "set_idle_ttl",
            "workspace_info",
        ]
        assert [tool.model_dump() for tool in first] == [
            tool.model_dump() for tool in second
        ]
        workspace_schema = first[-1].inputSchema
        assert workspace_schema["required"] == ["database"]

        opened = await service.call("recaf_open", {"input_path": str(input_path)})
        assert opened.isError is False
        database = opened.structuredContent["database"]
        assert "secret-worker-token" not in str(opened.model_dump())

        listed = await service.call("recaf_list", {})
        assert listed.isError is False
        assert listed.structuredContent["sessions"][0]["database"] == database
        assert "secret-worker-token" not in str(listed.model_dump())

        invalid_ttl = await service.call(
            "set_idle_ttl",
            {"database": database, "idle_ttl_sec": True},
        )
        assert invalid_ttl.structuredContent["error"] == "invalid_params"
        updated_ttl = await service.call(
            "set_idle_ttl",
            {"database": database, "idle_ttl_sec": 0},
        )
        assert updated_ttl.structuredContent == {
            "database": database,
            "idle_ttl_sec": 0,
        }

        workspace = await service.call(
            "workspace_info",
            {"database": database},
        )
        assert workspace.structuredContent == {
            "workspace_open": True,
            "class_count": 7,
        }
        assert len(workspace.content) == 1
        assert isinstance(workspace.content[0], types.TextContent)
        assert workspace.content[0].text == "workspace open"
        assert rpc_client.calls == [
            (43111, "secret-worker-token", "workspace_info", {})
        ]

        saved = await service.call("recaf_save", {"database": database})
        assert saved.isError is False
        assert saved.structuredContent["ok"] is True
        assert Path(saved.structuredContent["path"]).read_bytes() == b"saved workspace"

        live_session = await manager.get(database)
        assert live_session is not None
        rpc_client.process = live_session.process
        closed = await service.call("recaf_close", {"database": database})
        assert closed.isError is False
        assert closed.structuredContent == {
            "database": database,
            "closed": True,
            "saved": True,
            "path": str(live_session.autosave_path.resolve()),
            "size_bytes": len(b"saved workspace"),
            "graceful": True,
        }
        assert rpc_client.requests == [
            (
                43111,
                "secret-worker-token",
                "save_workspace",
                {
                    "destination": str(live_session.autosave_path),
                    "overwrite": True,
                },
            ),
            (
                43111,
                "secret-worker-token",
                "save_workspace",
                {
                    "destination": str(live_session.autosave_path),
                    "overwrite": True,
                },
            ),
            (43111, "secret-worker-token", "shutdown", None)
        ]
        assert launcher.aborted == [database]
        assert await manager.slot_count() == 0

        await service.close()

    anyio.run(scenario)


def test_unsupported_session_defaults_to_close_without_export(tmp_path: Path) -> None:
    input_path = tmp_path / "input.bin"
    input_path.write_bytes(b"unsupported")
    config = make_config(tmp_path)
    manager = SessionManager(1)
    reservations = PathReservations()
    launcher = FakeLauncher(manager, reservations, config.session_root)
    rpc_client = FakeRpcClient()
    service = BridgeToolService(
        config,
        sessions=manager,
        reservations=reservations,
        discovery=FakeDiscovery(),
        launcher_factory=lambda identity: launcher,
        rpc_client=rpc_client,
    )

    async def scenario() -> None:
        tools = await service.list_tools()
        close_schema = next(
            tool.inputSchema for tool in tools if tool.name == "recaf_close"
        )
        assert "default" not in close_schema["properties"]["save"]

        opened = await service.call("recaf_open", {"input_path": str(input_path)})
        assert opened.isError is False
        assert opened.structuredContent["autosave_path"] is None
        assert opened.structuredContent[
            "workspace_archive_export_supported"
        ] is False
        database = opened.structuredContent["database"]

        listed = await service.call("recaf_list", {})
        listed_session = listed.structuredContent["sessions"][0]
        assert listed_session["autosave_path"] is None
        assert listed_session["workspace_archive_export_supported"] is False

        save = await service.call("recaf_save", {"database": database})
        assert save.isError is True
        assert save.structuredContent["error"] == "save_unsupported"
        explicit_save = await service.call(
            "recaf_close",
            {"database": database, "save": True},
        )
        assert explicit_save.isError is True
        assert explicit_save.structuredContent["error"] == "save_unsupported"

        session = await manager.get(database)
        assert session is not None
        rpc_client.process = session.process
        closed = await service.call("recaf_close", {"database": database})
        assert closed.isError is False
        assert closed.structuredContent["saved"] is False
        assert rpc_client.requests == [
            (43111, "secret-worker-token", "shutdown", None)
        ]
        assert list(tmp_path.glob("*.recaf-autosave-*.jar")) == []
        await service.close()

    anyio.run(scenario)


@pytest.mark.parametrize("trigger", ["bridge_shutdown", "idle_timeout"])
def test_unsupported_automatic_cleanup_never_exports(
    tmp_path: Path,
    trigger: str,
) -> None:
    input_path = tmp_path / "input.bin"
    input_path.write_bytes(b"unsupported")
    config = make_config(tmp_path)
    manager = SessionManager(1)
    reservations = PathReservations()
    launcher = FakeLauncher(manager, reservations, config.session_root)
    rpc_client = FakeRpcClient()
    service = BridgeToolService(
        config,
        sessions=manager,
        reservations=reservations,
        discovery=FakeDiscovery(),
        launcher_factory=lambda identity: launcher,
        rpc_client=rpc_client,
    )

    async def scenario() -> None:
        opened = await service.call(
            "recaf_open",
            {"input_path": str(input_path), "idle_ttl_sec": 1},
        )
        database = opened.structuredContent["database"]
        session = await manager.get(database)
        assert session is not None
        rpc_client.process = session.process

        if trigger == "bridge_shutdown":
            await service.close()
        else:
            session.last_touched = time.monotonic() - 2
            await service._watchdog.run_once()
            await service.close()

        assert rpc_client.requests == [
            (43111, "secret-worker-token", "shutdown", None)
        ]
        assert launcher.aborted == [database]
        assert await manager.slot_count() == 0
        assert list(tmp_path.glob("*.recaf-autosave-*.jar")) == []

    anyio.run(scenario)


def test_cancelled_close_still_aborts_and_releases_session(tmp_path: Path) -> None:
    input_path = tmp_path / "input.jar"
    input_path.write_bytes(b"jar")
    config = make_config(tmp_path)
    manager = SessionManager(1)
    reservations = PathReservations()
    launcher = FakeLauncher(manager, reservations, config.session_root)

    class BlockingRpcClient(FakeRpcClient):
        def __init__(self) -> None:
            super().__init__()
            self.started = anyio.Event()

        async def request(
            self,
            port: int,
            token: str,
            method: str,
            params: dict[str, Any] | None = None,
            timeout_sec: float | None = None,
        ) -> dict[str, bool]:
            self.started.set()
            await anyio.sleep_forever()

    rpc_client = BlockingRpcClient()
    service = BridgeToolService(
        config,
        sessions=manager,
        reservations=reservations,
        discovery=FakeDiscovery(),
        launcher_factory=lambda identity: launcher,
        rpc_client=rpc_client,
    )

    async def scenario() -> None:
        opened = await service.call("recaf_open", {"input_path": str(input_path)})
        database = opened.structuredContent["database"]
        async with anyio.create_task_group() as tasks:
            tasks.start_soon(
                service.call,
                "recaf_close",
                {"database": database, "save": False},
            )
            await rpc_client.started.wait()
            session = await manager.get(database)
            assert session is not None
            assert session.state == "closing"
            duplicate = await service.call(
                "recaf_close",
                {"database": database, "save": False},
            )
            assert duplicate.structuredContent["error"] == "session_closing"
            tasks.cancel_scope.cancel()

        assert launcher.aborted == [database]
        assert await manager.slot_count() == 0

    anyio.run(scenario)


def test_bridge_shutdown_registers_failed_save_without_killing_worker(
    tmp_path: Path,
) -> None:
    input_path = tmp_path / "input.jar"
    input_path.write_bytes(b"jar")
    config = make_config(tmp_path)
    manager = SessionManager(1)
    reservations = PathReservations()
    launcher = FakeLauncher(manager, reservations, config.session_root)

    class FailingSaveRpcClient(FakeRpcClient):
        async def request(
            self,
            port: int,
            token: str,
            method: str,
            params: dict[str, Any] | None = None,
            timeout_sec: float | None = None,
        ) -> dict[str, Any]:
            if method == "save_workspace":
                raise WorkerMethodError("autosave is read-only", -32011)
            return await super().request(port, token, method, params)

    class FakeRecoveryRegistry:
        def __init__(self) -> None:
            self.persisted: list[tuple[Session, WorkerIdentity, str]] = []

        async def recover(self, identity: WorkerIdentity) -> list[Session]:
            return []

        async def persist(
            self,
            session: Session,
            identity: WorkerIdentity,
            error: str,
        ) -> Path:
            self.persisted.append((session, identity, error))
            return tmp_path / "recovery" / f"{session.sid}.json"

    recovery = FakeRecoveryRegistry()
    service = BridgeToolService(
        config,
        sessions=manager,
        reservations=reservations,
        discovery=FakeDiscovery(),
        launcher_factory=lambda identity: launcher,
        rpc_client=FailingSaveRpcClient(),
        recovery_registry=recovery,  # type: ignore[arg-type]
    )

    async def scenario() -> None:
        opened = await service.call("recaf_open", {"input_path": str(input_path)})
        database = opened.structuredContent["database"]
        session = await manager.get(database)
        assert session is not None

        with pytest.raises(BridgeShutdownError, match="recoverable") as caught:
            await service.close()

        assert database in str(caught.value)
        assert session.token not in str(caught.value)
        assert session.process.returncode is None
        assert launcher.aborted == []
        assert len(recovery.persisted) == 1
        assert recovery.persisted[0][0] is session
        assert recovery.persisted[0][2] == "autosave is read-only"

        await launcher.abort(database)

    anyio.run(scenario)
