from __future__ import annotations

import os
import stat
import time
from pathlib import Path
from typing import Any

import anyio
import pytest

from launcher import WorkerIdentity
from recovery import RecoveredProcess, RecoveryRegistry
from sessions import PathReservations, Session, SessionManager


FINGERPRINT = "c" * 64
TOKEN = "recovery-secret-token-0123456789abcdef"


class FakeProcess:
    pid = 9123
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


class FakeHealthProbe:
    def __init__(self, health: dict[str, Any]) -> None:
        self.health = health
        self.calls: list[tuple[int, str]] = []

    async def probe(self, port: int, token: str) -> dict[str, Any]:
        self.calls.append((port, token))
        return dict(self.health)


def make_session(tmp_path: Path) -> Session:
    input_path = tmp_path / "input.jar"
    input_path.write_bytes(b"jar")
    attempt_dir = tmp_path / "sessions" / "abcdef123456" / "attempt-1"
    data_dir = attempt_dir / "data"
    data_dir.mkdir(parents=True)
    log_path = attempt_dir / "worker.log"
    log_path.write_text("worker log", encoding="utf-8")
    return Session(
        sid="abcdef123456",
        process=FakeProcess(),
        port=43141,
        token=TOKEN,
        input_path=input_path.resolve(),
        autosave_path=(tmp_path / "autosave.jar").resolve(),
        session_dir=attempt_dir.parent.resolve(),
        data_dir=data_dir.resolve(),
        log_path=log_path.resolve(),
        last_touched=time.monotonic(),
        idle_ttl_sec=600,
        save_error="save failed",
    )


def matching_health(session: Session) -> dict[str, Any]:
    return {
        "ok": True,
        "pid": session.process.pid,
        "ready": True,
        "workspace_open": True,
        "workspace_ready": True,
        "workspace_archive_export_supported": True,
        "busy": False,
        "closing": False,
        "expected_input_path": str(session.input_path),
        "input_path": str(session.input_path),
        "plugin_build": "1.0.0",
        "schema_fingerprint": FINGERPRINT,
        "last_error": "save failed",
    }


def test_registry_persists_owner_only_state_and_authenticates_adoption(
    tmp_path: Path,
) -> None:
    async def scenario() -> None:
        session = make_session(tmp_path)
        identity = WorkerIdentity("1.0.0", FINGERPRINT)
        root = (tmp_path / "state" / "recovery").resolve()
        start_ids = {session.process.pid: "start-100"}
        writer = RecoveryRegistry(
            root,
            SessionManager(1),
            PathReservations(),
            process_start_reader=lambda pid: start_ids.get(pid),
        )
        entry_path = await writer.persist(session, identity, "save failed")

        assert root.is_dir()
        assert entry_path.is_file()
        if os.name == "posix":
            assert stat.S_IMODE(root.stat().st_mode) == 0o700
            assert stat.S_IMODE(entry_path.stat().st_mode) == 0o600
        assert TOKEN in entry_path.read_text(encoding="utf-8")

        manager = SessionManager(1)
        reservations = PathReservations()
        probe = FakeHealthProbe(matching_health(session))
        reader = RecoveryRegistry(
            root,
            manager,
            reservations,
            health_probe=probe,
            process_start_reader=lambda pid: start_ids.get(pid),
        )
        recovered = await reader.recover(identity)

        assert [item.sid for item in recovered] == [session.sid]
        adopted = await manager.get(session.sid)
        assert adopted is recovered[0]
        assert adopted is not None
        assert adopted.token == TOKEN
        assert adopted.save_error == "save failed"
        assert adopted.process.returncode is None
        assert probe.calls == [(session.port, TOKEN)]
        assert not entry_path.exists()
        assert await reservations.owns(session.sid, session.autosave_path)

    anyio.run(scenario)


def test_stale_process_identity_is_discarded_without_authentication(
    tmp_path: Path,
) -> None:
    async def scenario() -> None:
        session = make_session(tmp_path)
        identity = WorkerIdentity("1.0.0", FINGERPRINT)
        root = (tmp_path / "recovery").resolve()
        start_id = ["original"]
        registry = RecoveryRegistry(
            root,
            SessionManager(1),
            PathReservations(),
            process_start_reader=lambda pid: start_id[0],
        )
        entry = await registry.persist(session, identity, "save failed")
        start_id[0] = "reused-pid"

        probe = FakeHealthProbe(matching_health(session))
        recovered = await RecoveryRegistry(
            root,
            SessionManager(1),
            PathReservations(),
            health_probe=probe,
            process_start_reader=lambda pid: start_id[0],
        ).recover(identity)

        assert recovered == []
        assert probe.calls == []
        assert not entry.exists()

    anyio.run(scenario)


def test_recovered_process_never_signals_a_reused_pid(monkeypatch: pytest.MonkeyPatch) -> None:
    current_start_id = ["original"]
    signals: list[tuple[int, int]] = []
    process = RecoveredProcess(
        8123,
        "original",
        lambda pid: current_start_id[0],
    )
    monkeypatch.setattr(
        os,
        "kill",
        lambda pid, signal_number: signals.append((pid, signal_number)),
    )

    current_start_id[0] = "reused"
    with pytest.raises(ProcessLookupError, match="identity"):
        process.terminate()

    assert signals == []
    assert process.returncode == 0
