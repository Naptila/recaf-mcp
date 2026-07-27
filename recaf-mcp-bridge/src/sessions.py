"""Async-safe session capacity and filesystem path reservations."""

from __future__ import annotations

import os
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Literal

import anyio
from anyio.abc import Process


class SessionCapacityError(RuntimeError):
    """Raised when all configured Worker slots are active or starting."""


class PathReservationError(ValueError):
    """Raised when an input or autosave path is unsafe or already owned."""


class SessionStateError(RuntimeError):
    """Raised when a lifecycle transition conflicts with current session state."""


@dataclass(frozen=True, slots=True)
class ReservedPaths:
    input_path: Path
    autosave_path: Path


@dataclass(slots=True)
class Session:
    sid: str
    process: Process
    port: int
    token: str
    input_path: Path
    autosave_path: Path
    session_dir: Path
    data_dir: Path
    log_path: Path
    last_touched: float
    idle_ttl_sec: int
    operation_lock: anyio.Lock = field(default_factory=anyio.Lock)
    busy_count: int = 0
    save_error: str | None = None
    save_failure_count: int = 0
    retry_after: float = 0.0
    closing: bool = False
    state: Literal["starting", "ready", "closing", "dead"] = "starting"


class SessionManager:
    """Own active sessions and count pending launches against the same cap."""

    def __init__(self, max_sessions: int) -> None:
        if max_sessions < 1:
            raise ValueError("max_sessions must be positive")
        self._max_sessions = max_sessions
        self._sessions: dict[str, Session] = {}
        self._pending: set[str] = set()
        self._lock = anyio.Lock()

    async def reserve_slot(self) -> str:
        """Atomically reserve one capacity slot and return an opaque session ID."""
        async with self._lock:
            if len(self._sessions) + len(self._pending) >= self._max_sessions:
                raise SessionCapacityError(
                    f"maximum Recaf session count ({self._max_sessions}) reached"
                )
            while True:
                sid = uuid.uuid4().hex[:12]
                if sid not in self._sessions and sid not in self._pending:
                    self._pending.add(sid)
                    return sid

    async def activate(self, session: Session) -> None:
        """Replace one pending reservation with its ready owned process."""
        async with self._lock:
            if session.sid not in self._pending:
                raise RuntimeError("session slot is not pending")
            self._pending.remove(session.sid)
            session.state = "ready"
            self._sessions[session.sid] = session

    async def adopt(self, session: Session) -> None:
        """Atomically adopt one authenticated Worker from recovery state."""
        async with self._lock:
            if session.sid in self._sessions or session.sid in self._pending:
                raise SessionStateError("recovery session ID is already active")
            if len(self._sessions) + len(self._pending) >= self._max_sessions:
                raise SessionCapacityError(
                    f"maximum Recaf session count ({self._max_sessions}) reached"
                )
            session.closing = False
            session.state = "ready"
            self._sessions[session.sid] = session

    async def abandon(self, sid: str) -> None:
        """Release a pending launch slot after failure."""
        async with self._lock:
            self._pending.discard(sid)

    async def remove(self, sid: str) -> Session | None:
        """Remove an active session and mark it dead."""
        async with self._lock:
            session = self._sessions.pop(sid, None)
            if session is not None:
                session.state = "dead"
            self._pending.discard(sid)
            return session

    async def begin_close(self, sid: str) -> Session | None:
        """Atomically stop new routed calls before the close lock is acquired."""
        async with self._lock:
            session = self._sessions.get(sid)
            if session is None:
                return None
            if session.closing or session.state == "closing":
                raise SessionStateError("Recaf session is already closing")
            session.closing = True
            session.state = "closing"
            return session

    async def cancel_close(self, sid: str) -> None:
        """Restore a live session after a save-before-close failure."""
        async with self._lock:
            session = self._sessions.get(sid)
            if session is None:
                return
            session.closing = False
            session.state = (
                "dead"
                if session.state == "dead" or session.process.returncode is not None
                else "ready"
            )

    async def update_idle_ttl(
        self,
        sid: str,
        idle_ttl_sec: int,
        touched_at: float,
    ) -> Session | None:
        """Update one ready session's TTL and reset its retry schedule."""
        async with self._lock:
            session = self._sessions.get(sid)
            if session is None:
                return None
            if (
                session.state != "ready"
                or session.closing
                or session.process.returncode is not None
            ):
                raise SessionStateError("Recaf session is not ready")
            session.idle_ttl_sec = idle_ttl_sec
            session.last_touched = touched_at
            session.save_failure_count = 0
            session.retry_after = 0.0
            return session

    async def get(self, sid: str) -> Session | None:
        async with self._lock:
            return self._sessions.get(sid)

    async def snapshot(self) -> list[Session]:
        async with self._lock:
            return list(self._sessions.values())

    async def slot_count(self) -> int:
        async with self._lock:
            return len(self._sessions) + len(self._pending)


class PathReservations:
    """Prevent autosaves from overwriting inputs or another session's target."""

    def __init__(self) -> None:
        self._inputs: dict[str, Path] = {}
        self._targets: dict[Path, str] = {}
        self._managed_targets: dict[str, Path] = {}
        self._lock = anyio.Lock()

    @staticmethod
    def _canonical_input(input_path: str | Path) -> Path:
        try:
            canonical = Path(input_path).expanduser().resolve(strict=True)
        except (OSError, RuntimeError) as error:
            raise PathReservationError("input path does not exist") from error
        if not canonical.is_file():
            raise PathReservationError("input path must be a regular file")
        if not os.access(canonical, os.R_OK):
            raise PathReservationError("input path is not readable")
        return canonical

    @staticmethod
    def _canonical_output(output_path: Path) -> Path:
        try:
            parent = output_path.parent.expanduser().resolve(strict=True)
        except (OSError, RuntimeError) as error:
            raise PathReservationError("autosave parent does not exist") from error
        if not parent.is_dir():
            raise PathReservationError("autosave parent is not a directory")
        if not os.access(parent, os.W_OK):
            raise PathReservationError("autosave parent is not writable")
        candidate = parent / output_path.name
        try:
            return candidate.resolve(strict=candidate.exists())
        except (OSError, RuntimeError) as error:
            raise PathReservationError("autosave path cannot be resolved") from error

    async def reserve(
        self,
        sid: str,
        input_path: str | Path,
        autosave_path: str | Path | None = None,
    ) -> ReservedPaths:
        """Validate and reserve canonical paths for a pending session."""
        canonical_input = self._canonical_input(input_path)
        if autosave_path is None:
            default_name = (
                f"{canonical_input.stem}.recaf-autosave-{sid}.jar"
            )
            requested_output = canonical_input.with_name(default_name)
        else:
            requested_output = Path(autosave_path).expanduser()
            if not requested_output.is_absolute():
                raise PathReservationError("autosave path must be absolute")
        canonical_output = self._canonical_output(requested_output)
        if canonical_output == canonical_input:
            raise PathReservationError("autosave path aliases the input path")

        async with self._lock:
            owner = self._targets.get(canonical_input)
            if owner is not None and owner != sid:
                raise PathReservationError(
                    "input path is reserved as another session's autosave"
                )
            if canonical_output in self._inputs.values():
                raise PathReservationError(
                    "autosave path aliases an active session input"
                )
            owner = self._targets.get(canonical_output)
            if owner is not None and owner != sid:
                raise PathReservationError(
                    "autosave path is reserved by another session"
                )
            self._inputs[sid] = canonical_input
            self._targets[canonical_output] = sid
            self._managed_targets[sid] = canonical_output
        return ReservedPaths(canonical_input, canonical_output)

    async def reserve_output(
        self,
        sid: str,
        output_path: str | Path,
    ) -> Path:
        """Temporarily reserve a canonical save target for one active session."""
        requested_output = Path(output_path).expanduser()
        if not requested_output.is_absolute():
            raise PathReservationError("output path must be absolute")
        canonical_output = self._canonical_output(requested_output)
        async with self._lock:
            input_path = self._inputs.get(sid)
            if input_path is None:
                raise PathReservationError("session has no path reservation")
            if canonical_output == input_path:
                raise PathReservationError("output path aliases the input path")
            if canonical_output in self._inputs.values():
                raise PathReservationError(
                    "output path aliases an active session input"
                )
            owner = self._targets.get(canonical_output)
            if owner is not None and owner != sid:
                raise PathReservationError(
                    "output path is reserved by another session"
                )
            self._targets[canonical_output] = sid
            return canonical_output

    async def release_output(self, sid: str, output_path: Path) -> None:
        """Release a temporary output while retaining the managed autosave."""
        canonical = output_path.expanduser().resolve(strict=False)
        async with self._lock:
            if self._managed_targets.get(sid) == canonical:
                return
            if self._targets.get(canonical) == sid:
                del self._targets[canonical]

    async def release(self, sid: str) -> None:
        """Release every input/target association owned by one session."""
        async with self._lock:
            self._inputs.pop(sid, None)
            self._managed_targets.pop(sid, None)
            owned_targets = [
                path for path, owner in self._targets.items() if owner == sid
            ]
            for path in owned_targets:
                del self._targets[path]

    async def owns(self, sid: str, path: Path) -> bool:
        canonical = path.expanduser().resolve(strict=False)
        async with self._lock:
            return self._targets.get(canonical) == sid

def new_session_timestamp() -> float:
    """Centralize the monotonic clock used for activity tracking."""
    return time.monotonic()
