"""Owner-only recovery registry for authenticated orphaned Workers."""

from __future__ import annotations

import json
import os
import signal
import stat
import time
import uuid
from collections.abc import Callable
from pathlib import Path
from typing import Any

import anyio

from launcher import HealthProbe, HttpHealthProbe, WorkerIdentity, validate_recaf_runtime
from sessions import (
    PathReservationError,
    PathReservations,
    Session,
    SessionCapacityError,
    SessionManager,
    SessionStateError,
)


ProcessStartReader = Callable[[int], str | None]


def read_process_start_id(pid: int) -> str | None:
    """Read Linux process start ticks so PID reuse can never authorize a signal."""
    try:
        raw = Path(f"/proc/{pid}/stat").read_text(encoding="ascii")
        closing_parenthesis = raw.rfind(")")
        fields = raw[closing_parenthesis + 2 :].split()
        if closing_parenthesis < 0 or len(fields) <= 19:
            return None
        return fields[19]
    except (OSError, UnicodeError, ValueError):
        return None


class RecoveredProcess:
    """Minimal AnyIO-compatible process handle guarded by a Linux start ID."""

    stdin = None
    stdout = None
    stderr = None

    def __init__(
        self,
        pid: int,
        start_id: str,
        start_reader: ProcessStartReader = read_process_start_id,
    ) -> None:
        self._pid = pid
        self._start_id = start_id
        self._start_reader = start_reader
        self._returncode: int | None = None

    @property
    def pid(self) -> int:
        return self._pid

    @property
    def returncode(self) -> int | None:
        if self._returncode is None and not self.verify_identity():
            self._returncode = 0
        return self._returncode

    def verify_identity(self) -> bool:
        """Return true only while this PID has the recorded process start ID."""
        return self._start_reader(self._pid) == self._start_id

    async def wait(self) -> int:
        while self.returncode is None:
            await anyio.sleep(0.1)
        assert self._returncode is not None
        return self._returncode

    async def aclose(self) -> None:
        return None

    def terminate(self) -> None:
        self.send_signal(signal.SIGTERM)

    def kill(self) -> None:
        self.send_signal(signal.SIGKILL)

    def send_signal(self, signal_number: int) -> None:
        if not self.verify_identity():
            self._returncode = 0
            raise ProcessLookupError("recovered Worker identity no longer matches")
        os.kill(self._pid, signal_number)


class RecoveryRegistry:
    """Persist failed-shutdown sessions and authenticate them before adoption."""

    VERSION = 1
    MAX_ENTRY_BYTES = 16 * 1024

    def __init__(
        self,
        root: Path,
        sessions: SessionManager,
        reservations: PathReservations,
        *,
        health_probe: HealthProbe | None = None,
        process_start_reader: ProcessStartReader = read_process_start_id,
    ) -> None:
        if not root.is_absolute():
            raise ValueError("recovery registry path must be absolute")
        self._root = root
        self._sessions = sessions
        self._reservations = reservations
        self._health_probe = health_probe or HttpHealthProbe()
        self._process_start_reader = process_start_reader

    @property
    def root(self) -> Path:
        return self._root

    async def persist(
        self,
        session: Session,
        identity: WorkerIdentity,
        error: str,
    ) -> Path:
        """Atomically store enough secret state to authenticate this Worker."""
        if session.autosave_path is None:
            raise RuntimeError("APK/DEX sessions do not have recoverable autosaves")
        start_id = self._process_start_reader(session.process.pid)
        if start_id is None or session.process.returncode is not None:
            raise RuntimeError("Worker process identity cannot be persisted safely")
        self._secure_root(create=True)
        entry = {
            "version": self.VERSION,
            "database": session.sid,
            "pid": session.process.pid,
            "process_start_id": start_id,
            "port": session.port,
            "token": session.token,
            "input_path": str(session.input_path),
            "autosave_path": str(session.autosave_path),
            "session_dir": str(session.session_dir),
            "data_dir": str(session.data_dir),
            "log_path": str(session.log_path),
            "idle_ttl_sec": session.idle_ttl_sec,
            "plugin_build": identity.plugin_build,
            "schema_fingerprint": identity.schema_fingerprint,
            "save_error": error[:1000],
            "written_at": time.time(),
        }
        encoded = json.dumps(
            entry,
            ensure_ascii=False,
            separators=(",", ":"),
        ).encode("utf-8")
        if len(encoded) > self.MAX_ENTRY_BYTES:
            raise RuntimeError("recovery entry is unexpectedly large")

        destination = self._entry_path(session.sid)
        temporary = self._root / f".{session.sid}-{uuid.uuid4().hex}.tmp"
        descriptor = os.open(
            temporary,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL,
            0o600,
        )
        try:
            with os.fdopen(descriptor, "wb", closefd=True) as stream:
                stream.write(encoded)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, destination)
            destination.chmod(0o600)
            self._fsync_root()
        finally:
            try:
                temporary.unlink()
            except FileNotFoundError:
                pass
        return destination

    async def recover(self, identity: WorkerIdentity) -> list[Session]:
        """Authenticate safe entries and adopt matching live Workers."""
        if not self._root.exists():
            return []
        self._secure_root(create=False)
        recovered: list[Session] = []
        for path in sorted(self._root.glob("*.json")):
            try:
                entry = self._read_entry(path)
                session = await self._authenticate(entry, identity)
            except (OSError, RuntimeError, ValueError):
                self._discard(path)
                continue

            reserved = False
            try:
                await self._reservations.reserve(
                    session.sid,
                    session.input_path,
                    session.autosave_path,
                )
                reserved = True
                await self._sessions.adopt(session)
            except (PathReservationError, SessionCapacityError, SessionStateError):
                if reserved:
                    await self._reservations.release(session.sid)
                continue
            self._discard(path)
            recovered.append(session)
        return recovered

    async def _authenticate(
        self,
        entry: dict[str, Any],
        identity: WorkerIdentity,
    ) -> Session:
        if entry["plugin_build"] != identity.plugin_build:
            raise RuntimeError("recovery plugin build mismatch")
        if entry["schema_fingerprint"] != identity.schema_fingerprint:
            raise RuntimeError("recovery schema fingerprint mismatch")

        pid = entry["pid"]
        start_id = entry["process_start_id"]
        if self._process_start_reader(pid) != start_id:
            raise RuntimeError("recovery process identity is stale")
        health = await self._health_probe.probe(entry["port"], entry["token"])
        if self._process_start_reader(pid) != start_id:
            raise RuntimeError("recovery process changed during authentication")
        if health.get("pid") != pid:
            raise RuntimeError("authenticated Worker PID mismatch")
        if health.get("plugin_build") != identity.plugin_build:
            raise RuntimeError("authenticated Worker build mismatch")
        if health.get("schema_fingerprint") != identity.schema_fingerprint:
            raise RuntimeError("authenticated Worker schema mismatch")
        validate_recaf_runtime(health, identity.recaf_commit)
        if health.get("expected_input_path") != entry["input_path"]:
            raise RuntimeError("authenticated Worker expected-input mismatch")
        if health.get("input_path") != entry["input_path"]:
            raise RuntimeError("authenticated Worker input mismatch")
        if (
            health.get("ok") is not True
            or health.get("ready") is not True
            or health.get("workspace_open") is not True
            or health.get("workspace_ready") is not True
            or health.get("workspace_archive_export_supported") is not True
            or health.get("workspace_output_format") not in ("jar", "apk", "dex")
            or health.get("closing") is True
        ):
            raise RuntimeError("authenticated Worker is not recoverable")

        process = RecoveredProcess(pid, start_id, self._process_start_reader)
        save_error = health.get("last_error")
        return Session(
            sid=entry["database"],
            process=process,  # type: ignore[arg-type]
            port=entry["port"],
            token=entry["token"],
            input_path=Path(entry["input_path"]),
            autosave_path=Path(entry["autosave_path"]),
            session_dir=Path(entry["session_dir"]),
            data_dir=Path(entry["data_dir"]),
            log_path=Path(entry["log_path"]),
            last_touched=time.monotonic(),
            idle_ttl_sec=entry["idle_ttl_sec"],
            output_format=health.get("workspace_output_format"),
            save_error=(
                str(save_error)[:1000]
                if save_error is not None
                else entry["save_error"]
            ),
        )

    def _read_entry(self, path: Path) -> dict[str, Any]:
        metadata = path.lstat()
        if not stat.S_ISREG(metadata.st_mode):
            raise RuntimeError("recovery entry is not a regular file")
        if os.name == "posix" and stat.S_IMODE(metadata.st_mode) != 0o600:
            raise RuntimeError("recovery entry permissions are unsafe")
        if hasattr(os, "getuid") and metadata.st_uid != os.getuid():
            raise RuntimeError("recovery entry owner is unsafe")
        if metadata.st_size > self.MAX_ENTRY_BYTES:
            raise RuntimeError("recovery entry is too large")
        payload = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(payload, dict) or payload.get("version") != self.VERSION:
            raise RuntimeError("recovery entry version is invalid")

        sid = payload.get("database")
        if (
            not isinstance(sid, str)
            or len(sid) != 12
            or any(character not in "0123456789abcdef" for character in sid)
            or path != self._entry_path(sid)
        ):
            raise RuntimeError("recovery session ID is invalid")
        self._require_int(payload, "pid", 1, 2**31 - 1)
        self._require_int(payload, "port", 1, 65535)
        self._require_int(payload, "idle_ttl_sec", 0, 604800)
        self._require_string(payload, "process_start_id", 1, 64)
        token = self._require_string(payload, "token", 32, 512)
        if not token.isascii() or any(character.isspace() for character in token):
            raise RuntimeError("recovery token is invalid")
        self._require_string(payload, "plugin_build", 1, 200)
        fingerprint = self._require_string(
            payload,
            "schema_fingerprint",
            64,
            64,
        )
        if any(character not in "0123456789abcdef" for character in fingerprint):
            raise RuntimeError("recovery fingerprint is invalid")
        self._require_string(payload, "save_error", 0, 1000)

        input_path = self._canonical_file(payload, "input_path")
        autosave_path = self._canonical_output(payload, "autosave_path")
        session_dir = self._canonical_directory(payload, "session_dir")
        data_dir = self._canonical_directory(payload, "data_dir")
        log_path = self._canonical_file(payload, "log_path")
        if data_dir.parent.parent != session_dir:
            raise RuntimeError("recovery data directory is not session-owned")
        if log_path.parent != data_dir.parent:
            raise RuntimeError("recovery log is not session-owned")
        payload["input_path"] = str(input_path)
        payload["autosave_path"] = str(autosave_path)
        payload["session_dir"] = str(session_dir)
        payload["data_dir"] = str(data_dir)
        payload["log_path"] = str(log_path)
        return payload

    def _secure_root(self, *, create: bool) -> None:
        if create:
            self._root.mkdir(parents=True, mode=0o700, exist_ok=True)
        metadata = self._root.lstat()
        if not stat.S_ISDIR(metadata.st_mode):
            raise RuntimeError("recovery registry is not a directory")
        if hasattr(os, "getuid") and metadata.st_uid != os.getuid():
            raise RuntimeError("recovery registry owner is unsafe")
        self._root.chmod(0o700)

    def _entry_path(self, sid: str) -> Path:
        return self._root / f"{sid}.json"

    @staticmethod
    def _require_int(
        payload: dict[str, Any],
        name: str,
        minimum: int,
        maximum: int,
    ) -> int:
        value = payload.get(name)
        if isinstance(value, bool) or not isinstance(value, int):
            raise RuntimeError(f"recovery {name} is invalid")
        if not minimum <= value <= maximum:
            raise RuntimeError(f"recovery {name} is out of range")
        return value

    @staticmethod
    def _require_string(
        payload: dict[str, Any],
        name: str,
        minimum: int,
        maximum: int,
    ) -> str:
        value = payload.get(name)
        if not isinstance(value, str) or not minimum <= len(value) <= maximum:
            raise RuntimeError(f"recovery {name} is invalid")
        return value

    def _canonical_file(self, payload: dict[str, Any], name: str) -> Path:
        raw = self._require_string(payload, name, 1, 4096)
        path = Path(raw)
        if not path.is_absolute():
            raise RuntimeError(f"recovery {name} is not absolute")
        canonical = path.resolve(strict=True)
        if canonical != path or not canonical.is_file():
            raise RuntimeError(f"recovery {name} is not canonical")
        return canonical

    def _canonical_directory(self, payload: dict[str, Any], name: str) -> Path:
        raw = self._require_string(payload, name, 1, 4096)
        path = Path(raw)
        if not path.is_absolute():
            raise RuntimeError(f"recovery {name} is not absolute")
        canonical = path.resolve(strict=True)
        if canonical != path or not canonical.is_dir():
            raise RuntimeError(f"recovery {name} is not canonical")
        return canonical

    def _canonical_output(self, payload: dict[str, Any], name: str) -> Path:
        raw = self._require_string(payload, name, 1, 4096)
        path = Path(raw)
        if not path.is_absolute():
            raise RuntimeError(f"recovery {name} is not absolute")
        parent = path.parent.resolve(strict=True)
        canonical = (parent / path.name).resolve(strict=path.exists())
        if canonical != path:
            raise RuntimeError(f"recovery {name} is not canonical")
        return canonical

    def _discard(self, path: Path) -> None:
        try:
            path.unlink()
            self._fsync_root()
        except FileNotFoundError:
            pass

    def _fsync_root(self) -> None:
        try:
            descriptor = os.open(self._root, os.O_RDONLY | os.O_DIRECTORY)
        except (AttributeError, OSError):
            return
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
