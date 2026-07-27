from __future__ import annotations

import time
from pathlib import Path

import anyio

from sessions import Session, SessionManager
from watchdog import IdleWatchdog


class FakeProcess:
    pid = 9001
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


async def ready_session(
    manager: SessionManager,
    tmp_path: Path,
    *,
    last_touched: float,
    idle_ttl_sec: int,
) -> Session:
    sid = await manager.reserve_slot()
    session = Session(
        sid=sid,
        process=FakeProcess(),
        port=43131,
        token="watchdog-secret-token",
        input_path=tmp_path / "input.jar",
        autosave_path=tmp_path / "autosave.jar",
        session_dir=tmp_path / sid,
        data_dir=tmp_path / sid / "data",
        log_path=tmp_path / sid / "worker.log",
        last_touched=last_touched,
        idle_ttl_sec=idle_ttl_sec,
    )
    await manager.activate(session)
    return session


def test_watchdog_rechecks_busy_and_activity_after_acquiring_lock(
    tmp_path: Path,
) -> None:
    async def scenario() -> None:
        manager = SessionManager(1)
        now = [100.0]
        session = await ready_session(
            manager,
            tmp_path,
            last_touched=0.0,
            idle_ttl_sec=10,
        )
        expired: list[str] = []

        async def expire(candidate: Session) -> None:
            expired.append(candidate.sid)

        async def finalize(sid: str) -> None:
            await manager.remove(sid)

        watchdog = IdleWatchdog(
            manager,
            expire,
            finalize,
            clock=lambda: now[0],
        )

        async with anyio.create_task_group() as tasks:
            async with session.operation_lock:
                tasks.start_soon(watchdog.run_once)
                while session.state != "closing":
                    await anyio.sleep(0)
                session.busy_count = 1
                session.last_touched = now[0]
            while session.state != "ready":
                await anyio.sleep(0)
            session.busy_count = 0

        assert expired == []
        assert await manager.get(session.sid) is session
        assert session.closing is False
        assert session.state == "ready"

    anyio.run(scenario)


def test_watchdog_uses_bounded_exponential_save_retry(tmp_path: Path) -> None:
    async def scenario() -> None:
        manager = SessionManager(1)
        now = [100.0]
        session = await ready_session(
            manager,
            tmp_path,
            last_touched=0.0,
            idle_ttl_sec=10,
        )
        attempts: list[float] = []
        finalized: list[str] = []

        async def expire(_: Session) -> None:
            attempts.append(now[0])
            if len(attempts) < 4:
                raise RuntimeError(f"save failure {len(attempts)}")

        async def finalize(sid: str) -> None:
            finalized.append(sid)
            await manager.remove(sid)

        watchdog = IdleWatchdog(
            manager,
            expire,
            finalize,
            retry_base_sec=5,
            retry_max_sec=12,
            clock=lambda: now[0],
        )

        await watchdog.run_once()
        assert attempts == [100.0]
        assert session.state == "ready"
        assert session.process.returncode is None
        assert session.save_error == "save failure 1"
        assert session.retry_after == 105.0

        now[0] = 104.0
        await watchdog.run_once()
        assert attempts == [100.0]

        now[0] = 105.0
        await watchdog.run_once()
        assert attempts == [100.0, 105.0]
        assert session.retry_after == 115.0

        now[0] = 114.0
        await watchdog.run_once()
        assert len(attempts) == 2

        now[0] = 115.0
        await watchdog.run_once()
        assert attempts == [100.0, 105.0, 115.0]
        assert session.retry_after == 127.0

        now[0] = 126.0
        await watchdog.run_once()
        assert len(attempts) == 3

        now[0] = 127.0
        await watchdog.run_once()
        assert attempts == [100.0, 105.0, 115.0, 127.0]
        assert finalized == [session.sid]
        assert await manager.get(session.sid) is None

    anyio.run(scenario)


def test_watchdog_ignores_disabled_and_recent_sessions(tmp_path: Path) -> None:
    async def scenario() -> None:
        manager = SessionManager(2)
        disabled = await ready_session(
            manager,
            tmp_path,
            last_touched=time.monotonic() - 100,
            idle_ttl_sec=0,
        )
        recent = await ready_session(
            manager,
            tmp_path,
            last_touched=95.0,
            idle_ttl_sec=10,
        )
        expired: list[str] = []

        async def expire(session: Session) -> None:
            expired.append(session.sid)

        async def finalize(sid: str) -> None:
            await manager.remove(sid)

        watchdog = IdleWatchdog(
            manager,
            expire,
            finalize,
            clock=lambda: 100.0,
        )
        await watchdog.run_once()

        assert expired == []
        assert await manager.get(disabled.sid) is disabled
        assert await manager.get(recent.sid) is recent

    anyio.run(scenario)
