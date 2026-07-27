"""Idle-session save and shutdown coordination."""

from __future__ import annotations

import time
from collections.abc import Awaitable, Callable

import anyio

from sessions import Session, SessionManager, SessionStateError


ExpireCallback = Callable[[Session], Awaitable[None]]
FinalizeCallback = Callable[[str], Awaitable[None]]
Clock = Callable[[], float]
Sleep = Callable[[float], Awaitable[None]]


class IdleWatchdog:
    """Save and terminate sessions which remain idle past their TTL."""

    def __init__(
        self,
        sessions: SessionManager,
        expire: ExpireCallback,
        finalize: FinalizeCallback,
        *,
        interval_sec: float = 5.0,
        retry_base_sec: float = 5.0,
        retry_max_sec: float = 300.0,
        clock: Clock = time.monotonic,
        sleep: Sleep = anyio.sleep,
    ) -> None:
        if interval_sec <= 0:
            raise ValueError("watchdog interval must be positive")
        if retry_base_sec <= 0 or retry_max_sec < retry_base_sec:
            raise ValueError("watchdog retry bounds are invalid")
        self._sessions = sessions
        self._expire = expire
        self._finalize = finalize
        self._interval_sec = interval_sec
        self._retry_base_sec = retry_base_sec
        self._retry_max_sec = retry_max_sec
        self._clock = clock
        self._sleep = sleep

    async def run(self) -> None:
        """Inspect sessions at the configured fixed interval until cancelled."""
        while True:
            await self._sleep(self._interval_sec)
            await self.run_once()

    async def run_once(self) -> None:
        """Inspect one snapshot and concurrently process eligible sessions."""
        now = self._clock()
        candidates = [
            session
            for session in await self._sessions.snapshot()
            if self._eligible_before_claim(session, now)
        ]
        async with anyio.create_task_group() as tasks:
            for session in candidates:
                tasks.start_soon(self._inspect, session)

    async def _inspect(self, candidate: Session) -> None:
        try:
            session = await self._sessions.begin_close(candidate.sid)
        except SessionStateError:
            return
        if session is None:
            return

        expired = False
        try:
            async with session.operation_lock:
                if not self._eligible_after_claim(session, self._clock()):
                    return
                session.busy_count += 1
                try:
                    await self._expire(session)
                except Exception as error:
                    self._record_failure(session, error)
                else:
                    session.save_error = None
                    session.save_failure_count = 0
                    session.retry_after = 0.0
                    expired = True
                finally:
                    session.busy_count -= 1
        finally:
            with anyio.CancelScope(shield=True):
                if expired:
                    try:
                        await self._finalize(session.sid)
                    except Exception as error:
                        live = await self._sessions.get(session.sid)
                        if live is not None:
                            live.save_error = str(error)[:1000]
                else:
                    await self._sessions.cancel_close(session.sid)

    @staticmethod
    def _eligible_before_claim(session: Session, now: float) -> bool:
        return (
            session.state == "ready"
            and not session.closing
            and session.process.returncode is None
            and session.idle_ttl_sec > 0
            and session.busy_count == 0
            and now - session.last_touched >= session.idle_ttl_sec
            and now >= session.retry_after
        )

    @staticmethod
    def _eligible_after_claim(session: Session, now: float) -> bool:
        return (
            session.state == "closing"
            and session.closing
            and session.process.returncode is None
            and session.idle_ttl_sec > 0
            and session.busy_count == 0
            and now - session.last_touched >= session.idle_ttl_sec
            and now >= session.retry_after
        )

    def _record_failure(self, session: Session, error: Exception) -> None:
        session.save_error = str(error)[:1000]
        session.save_failure_count += 1
        exponent = min(session.save_failure_count - 1, 30)
        delay = min(
            self._retry_base_sec * (2**exponent),
            self._retry_max_sec,
        )
        session.retry_after = self._clock() + delay
