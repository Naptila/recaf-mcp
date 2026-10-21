from __future__ import annotations

from collections.abc import Mapping, Sequence
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import anyio
import httpx
import pytest

import launcher as launcher_module

from conftest import make_test_plugin_jar, make_test_recaf_jar
from config import BridgeConfig
from launcher import (
    HttpHealthProbe,
    WorkerIdentity,
    WorkerLaunchError,
    WorkerLauncher,
    WorkerNotListening,
    WorkerPortCollision,
)
from sessions import PathReservations, SessionCapacityError, SessionManager


FINGERPRINT = "a" * 64


class FakeProcess:
    def __init__(self, pid: int) -> None:
        self.pid = pid
        self.returncode: int | None = None
        self.stdin = None
        self.stdout = None
        self.stderr = None

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


class RecordingStarter:
    def __init__(self) -> None:
        self.calls: list[tuple[list[str], dict[str, str], Path]] = []
        self.processes: list[FakeProcess] = []

    async def __call__(
        self,
        command: Sequence[str],
        environment: Mapping[str, str],
        log_path: Path,
    ) -> FakeProcess:
        self.calls.append((list(command), dict(environment), log_path))
        process = FakeProcess(1000 + len(self.processes))
        self.processes.append(process)
        return process


class RecordingTerminator:
    def __init__(self) -> None:
        self.processes: list[FakeProcess] = []

    async def __call__(self, process: FakeProcess) -> None:
        self.processes.append(process)
        process.returncode = -15


class ReadyProbe:
    def __init__(self, input_path: Path) -> None:
        self.input_path = input_path.resolve()
        self.tokens: list[str] = []

    async def probe(self, port: int, token: str) -> dict[str, Any]:
        self.tokens.append(token)
        return ready_health(self.input_path, 1000)


def ready_health(input_path: Path, pid: int) -> dict[str, Any]:
    return {
        "recaf_compatible": True,
        "recaf_commit": "runtime-revision",
        "ok": True,
        "pid": pid,
        "ready": True,
        "workspace_open": True,
        "workspace_ready": True,
        "workspace_archive_export_supported": True,
        "workspace_output_format": "jar",
        "expected_input_path": str(input_path.resolve()),
        "input_path": str(input_path.resolve()),
        "plugin_build": "1.0.0",
        "schema_fingerprint": FINGERPRINT,
        "last_error": None,
    }


def make_launcher(
    tmp_path: Path,
    input_path: Path,
    probe: Any,
    starter: RecordingStarter,
    terminator: RecordingTerminator,
    ports: list[int],
) -> tuple[WorkerLauncher, SessionManager, PathReservations]:
    recaf_jar = make_test_recaf_jar(tmp_path)
    plugin_jar = make_test_plugin_jar(tmp_path / "plugins")
    config = BridgeConfig(
        recaf_jar=recaf_jar,
        plugin_jar=plugin_jar,
        session_root=(tmp_path / "sessions").resolve(),
        max_sessions=1,
        launch_timeout_sec=1,
        launch_attempts=len(ports),
        readiness_poll_sec=0.001,
    )
    manager = SessionManager(config.max_sessions)
    reservations = PathReservations()
    port_iterator = iter(ports)

    async def no_sleep(_: float) -> None:
        return None

    launcher = WorkerLauncher(
        config,
        manager,
        reservations,
        WorkerIdentity("1.0.0", FINGERPRINT),
        health_probe=probe,
        process_starter=starter,
        process_terminator=terminator,
        port_allocator=lambda: next(port_iterator),
        sleep=no_sleep,
    )
    return launcher, manager, reservations


def test_runtime_revision_must_match_catalog_discovery(tmp_path: Path) -> None:
    input_path = tmp_path / "input.jar"
    input_path.write_bytes(b"jar")
    launcher, _, _ = make_launcher(
        tmp_path, input_path, ReadyProbe(input_path),
        RecordingStarter(), RecordingTerminator(), [43101],
    )
    launcher._expected_identity = WorkerIdentity("1.0.0", FINGERPRINT, "runtime-revision")
    health = ready_health(input_path, 1000)
    launcher._validate_health_identity(health, input_path, 1000)
    health["recaf_commit"] = "replacement-runtime"
    with pytest.raises(WorkerLaunchError, match="changed since catalog discovery"):
        launcher._validate_health_identity(health, input_path, 1000)
    health["recaf_compatible"] = False
    with pytest.raises(WorkerLaunchError, match="incompatible"):
        launcher._validate_health_identity(health, input_path, 1000)


@pytest.mark.parametrize("output_format", ["apk", "dex"])
def test_detected_android_format_controls_autosave_for_misnamed_input(
    tmp_path: Path, output_format: str,
) -> None:
    input_path = tmp_path / "input.bin"
    input_path.write_bytes(b"fixture")

    class AndroidProbe(ReadyProbe):
        async def probe(self, port: int, token: str) -> dict[str, Any]:
            health = await super().probe(port, token)
            health["workspace_output_format"] = output_format
            return health

    launcher, _, reservations = make_launcher(
        tmp_path, input_path, AndroidProbe(input_path),
        RecordingStarter(), RecordingTerminator(), [43101],
    )

    async def scenario() -> None:
        session = await launcher.launch(input_path)
        try:
            assert session.output_format == output_format
            assert session.autosave_path.suffix == f".{output_format}"
            assert await reservations.owns(session.sid, session.autosave_path)
            assert not await reservations.owns(session.sid, session.autosave_path.with_suffix(".jar"))
        finally:
            await launcher.abort(session.sid)

    anyio.run(scenario)


def test_timeout_does_not_restart_an_authenticated_worker(tmp_path: Path, monkeypatch) -> None:
    input_path = tmp_path / "input.apk"
    input_path.write_bytes(b"apk")
    starter = RecordingStarter()
    terminator = RecordingTerminator()

    class ImportingProbe(ReadyProbe):
        async def probe(self, port: int, token: str) -> dict[str, Any]:
            health = await super().probe(port, token)
            health.update(ready=False, workspace_ready=False)
            return health

    probe = ImportingProbe(input_path)
    launcher, manager, _ = make_launcher(
        tmp_path, input_path, probe, starter, terminator, [43101, 43102, 43103],
    )
    ticks = iter([0.0, 0.0, 2.0])
    # Keep anyio's event-loop clock intact while driving only the launch deadline.
    monkeypatch.setattr(launcher_module, "time", SimpleNamespace(monotonic=lambda: next(ticks)))

    async def scenario() -> None:
        with pytest.raises(WorkerLaunchError, match="RECAF_LAUNCH_TIMEOUT_SEC"):
            await launcher.launch(input_path)
        assert len(starter.processes) == 1
        assert terminator.processes == starter.processes
        assert await manager.slot_count() == 0

    anyio.run(scenario)


def test_oom_is_reported_before_a_long_log_tail_and_token_is_redacted(tmp_path: Path) -> None:
    log = tmp_path / "worker.log"
    token = "private-test-token"
    log.write_text("java.lang.OutOfMemoryError: Java heap space\n" + "x" * 70000 + "\n" + token)
    error = WorkerLauncher._diagnostic_error("Worker exited", log, token)
    message = str(error)
    assert message.startswith("Worker exhausted JVM memory (OutOfMemoryError)")
    assert "RECAF_WORKER_MAX_HEAP_MB" in message[:1000]
    assert "<redacted>" in message
    assert token not in message
    assert len(message) < 16000


def test_oom_exit_is_not_retried_as_a_port_collision(tmp_path: Path) -> None:
    input_path = tmp_path / "input.dex"
    input_path.write_bytes(b"dex")

    class OomStarter(RecordingStarter):
        async def __call__(self, command, environment, log_path) -> FakeProcess:
            process = await super().__call__(command, environment, log_path)
            log_path.write_text("Terminating due to java.lang.OutOfMemoryError: Java heap space")
            process.returncode = 3
            return process

    starter = OomStarter()
    terminator = RecordingTerminator()
    launcher, manager, _ = make_launcher(
        tmp_path, input_path, ReadyProbe(input_path), starter, terminator, [43101, 43102, 43103],
    )

    async def scenario() -> None:
        with pytest.raises(WorkerLaunchError, match="Worker exhausted JVM memory"):
            await launcher.launch(input_path)
        assert len(starter.processes) == 1
        assert terminator.processes == starter.processes
        assert await manager.slot_count() == 0

    anyio.run(scenario)


def test_import_failure_stops_an_idle_worker_without_waiting_or_retrying(tmp_path: Path) -> None:
    input_path = tmp_path / "input.dex"
    input_path.write_bytes(b"dex")

    class FailedImportStarter(RecordingStarter):
        async def __call__(self, command, environment, log_path) -> FakeProcess:
            process = await super().__call__(command, environment, log_path)
            log_path.write_text(
                "14:37:47.874 [LaunchBootstrap/main] ERROR: "
                "Error handling loading of launch workspace content.\n"
                "java.io.IOException: Unknown opcode: 0x60ec at 0x2e392a\n"
                "\tat me.darknet.dex.file.instructions.Format.read(Format.java:37)\n"
                "14:37:47.959 [Main/main] INFO : Headless idle\n"
            )
            return process

    class UnreachableProbe:
        async def probe(self, port, token):
            pytest.fail("a failed import must stop before polling health")

    starter = FailedImportStarter()
    terminator = RecordingTerminator()
    launcher, manager, reservations = make_launcher(
        tmp_path, input_path, UnreachableProbe(), starter, terminator, [43101, 43102, 43103],
    )

    async def scenario() -> None:
        with pytest.raises(WorkerLaunchError, match="Unknown opcode: 0x60ec") as error:
            await launcher.launch(input_path)
        assert str(error.value).startswith("Worker startup failed:")
        assert len(starter.processes) == 1
        assert terminator.processes == starter.processes
        assert await manager.slot_count() == 0
        assert not any(launcher._config.session_root.iterdir())
        replacement = await reservations.reserve("replacement", input_path, None)
        assert replacement.input_path == input_path.resolve()
        await reservations.release("replacement")

    anyio.run(scenario)


def test_bootstrap_root_cause_survives_a_long_tail_without_token_leaks(tmp_path: Path) -> None:
    log = tmp_path / "worker.log"
    token = "private-test-token"
    log.write_text(
        "14:37:47.874 [LaunchBootstrap/main] ERROR: "
        "Error handling loading of launch workspace content.\n"
        "java.lang.IllegalStateException: import failed\n"
        "\tat example.Loader.run(Loader.java:12)\n"
        f"Caused by: java.io.IOException: Unknown opcode: 0x1e7a {token}\n"
        + "\tat example.Loader.run(Loader.java:12)\n" * 2000
        + "14:37:47.959 [Main/main] INFO : Headless idle\n"
        + "java.lang.RuntimeException: unrelated later log\n"
    )
    message = str(WorkerLauncher._diagnostic_error("Worker exited", log, token))
    assert "Unknown opcode: 0x1e7a <redacted>" in message[:1000]
    assert "unrelated later log" not in message.split("\nWorker log tail:")[0]
    assert token not in message
    assert len(message) < 16000


def test_other_import_errors_do_not_fail_a_ready_workspace(tmp_path: Path) -> None:
    input_path = tmp_path / "input.jar"
    input_path.write_bytes(b"jar")

    class RecoverableErrorStarter(RecordingStarter):
        async def __call__(self, command, environment, log_path) -> FakeProcess:
            process = await super().__call__(command, environment, log_path)
            log_path.write_text(
                "14:37:47.874 [BasicResourceImporter/main] ERROR: "
                "Failed to read optional embedded archive\n"
                "java.io.IOException: invalid ZIP entry\n"
            )
            return process

    launcher, _, _ = make_launcher(
        tmp_path, input_path, ReadyProbe(input_path),
        RecoverableErrorStarter(), RecordingTerminator(), [43101],
    )

    async def scenario() -> None:
        session = await launcher.launch(input_path)
        await launcher.abort(session.sid)

    anyio.run(scenario)


@pytest.mark.parametrize("error_type", [httpx.RemoteProtocolError, httpx.ReadError])
def test_disconnected_health_response_is_polled_until_process_exit(monkeypatch, error_type) -> None:
    original_client = httpx.AsyncClient

    async def disconnect(request: httpx.Request) -> httpx.Response:
        raise error_type("Server disconnected", request=request)

    monkeypatch.setattr(launcher_module.httpx, "AsyncClient", lambda **kwargs: original_client(
        transport=httpx.MockTransport(disconnect), **kwargs,
    ))

    async def scenario() -> None:
        with pytest.raises(WorkerNotListening):
            await HttpHealthProbe().probe(43101, "private-token")

    anyio.run(scenario)


def test_launch_uses_argument_vector_and_token_only_in_environment(
    tmp_path: Path,
) -> None:
    input_path = tmp_path / "input $(touch injected).jar"
    input_path.write_bytes(b"jar")
    starter = RecordingStarter()
    terminator = RecordingTerminator()
    probe = ReadyProbe(input_path)
    launcher, manager, reservations = make_launcher(
        tmp_path,
        input_path,
        probe,
        starter,
        terminator,
        [43101],
    )

    async def scenario() -> None:
        session = await launcher.launch(input_path)
        command, environment, log_path = starter.calls[0]

        assert f"-Drecaf.mcp.input_path={input_path.resolve()}" in command[:command.index("-jar")]
        assert "-i" not in command
        assert command[0] == "java"
        assert "-XX:MaxRAMPercentage=60" in command
        assert "-XX:+ExitOnOutOfMemoryError" in command
        assert "-Drecaf.mcp.port=43101" in command
        assert command[command.index("-jar") + 1] == str(
            (tmp_path / "recaf.jar").resolve()
        )
        assert "-cp" not in command
        plugin_directory = Path(command[command.index("-r") + 1])
        assert plugin_directory.parent == session.data_dir.parent
        assert [path.name for path in plugin_directory.glob("*.jar")] == [
            "recaf-mcp-plugin.jar"
        ]
        assert environment["RECAF_MCP_TOKEN"] == session.token
        assert session.token not in command
        assert session.token not in str(log_path)
        assert not (tmp_path / "injected").exists()
        assert await manager.get(session.sid) is session
        assert await reservations.owns(session.sid, session.autosave_path)

        await launcher.abort(session.sid)
        assert await manager.slot_count() == 0
        assert not session.session_dir.exists()
        assert terminator.processes == [session.process]

    anyio.run(scenario)


def test_port_collision_retries_with_fresh_process_port_and_token(
    tmp_path: Path,
) -> None:
    input_path = tmp_path / "input.jar"
    input_path.write_bytes(b"jar")
    starter = RecordingStarter()
    terminator = RecordingTerminator()

    class CollisionThenReady:
        def __init__(self) -> None:
            self.calls = 0
            self.tokens: list[str] = []

        async def probe(self, port: int, token: str) -> dict[str, Any]:
            self.calls += 1
            self.tokens.append(token)
            if self.calls == 1:
                raise WorkerPortCollision("occupied")
            return ready_health(input_path, 1001)

    probe = CollisionThenReady()
    launcher, _, _ = make_launcher(
        tmp_path,
        input_path,
        probe,
        starter,
        terminator,
        [43102, 43103],
    )

    async def scenario() -> None:
        session = await launcher.launch(input_path)
        assert session.port == 43103
        assert len(starter.processes) == 2
        assert terminator.processes == [starter.processes[0]]
        assert probe.tokens[0] != probe.tokens[1]
        await launcher.abort(session.sid)

    anyio.run(scenario)


def test_unsupported_launch_disables_autosave_and_rejects_an_explicit_target(
    tmp_path: Path,
) -> None:
    input_path = tmp_path / "input.bin"
    input_path.write_bytes(b"apk")
    starter = RecordingStarter()
    terminator = RecordingTerminator()

    class AndroidProbe(ReadyProbe):
        async def probe(self, port: int, token: str) -> dict[str, Any]:
            health = await super().probe(port, token)
            health["workspace_archive_export_supported"] = False
            return health

    launcher, manager, reservations = make_launcher(
        tmp_path,
        input_path,
        AndroidProbe(input_path),
        starter,
        terminator,
        [43106],
    )

    async def scenario() -> None:
        session = await launcher.launch(input_path)
        managed_target = input_path.with_name(
            f"input.recaf-autosave-{session.sid}.jar"
        ).resolve()
        assert session.autosave_path is None
        assert not await reservations.owns(session.sid, managed_target)
        await launcher.abort(session.sid)

        second_starter = RecordingStarter()
        second_terminator = RecordingTerminator()
        second, second_manager, _ = make_launcher(
            tmp_path,
            input_path,
            AndroidProbe(input_path),
            second_starter,
            second_terminator,
            [43107],
        )
        with pytest.raises(WorkerLaunchError, match="unsupported for this workspace"):
            await second.launch(input_path, tmp_path / "output.jar")
        assert await second_manager.slot_count() == 0
        assert second_terminator.processes == second_starter.processes

    anyio.run(scenario)


def test_identity_mismatch_terminates_and_releases_launch_state(
    tmp_path: Path,
) -> None:
    input_path = tmp_path / "input.jar"
    input_path.write_bytes(b"jar")
    autosave_path = tmp_path / "output.jar"
    terminator = RecordingTerminator()

    class DiagnosticStarter(RecordingStarter):
        async def __call__(
            self,
            command: Sequence[str],
            environment: Mapping[str, str],
            log_path: Path,
        ) -> FakeProcess:
            process = await super().__call__(command, environment, log_path)
            log_path.write_text(
                "startup token="
                + environment["RECAF_MCP_TOKEN"]
                + "\nidentity failed\n",
                encoding="utf-8",
            )
            return process

    starter = DiagnosticStarter()

    class WrongIdentity:
        async def probe(self, port: int, token: str) -> dict[str, Any]:
            health = ready_health(input_path, 1000)
            health["plugin_build"] = "wrong"
            return health

    launcher, manager, reservations = make_launcher(
        tmp_path,
        input_path,
        WrongIdentity(),
        starter,
        terminator,
        [43104],
    )

    async def scenario() -> None:
        with pytest.raises(WorkerLaunchError, match="build mismatch") as caught:
            await launcher.launch(input_path, autosave_path)
        assert "identity failed" in str(caught.value)
        assert "<redacted>" in str(caught.value)
        assert starter.calls[0][1]["RECAF_MCP_TOKEN"] not in str(caught.value)
        assert terminator.processes == starter.processes
        assert await manager.slot_count() == 0
        replacement = await reservations.reserve(
            "replacement",
            input_path,
            autosave_path,
        )
        assert replacement.autosave_path == autosave_path.resolve()
        await reservations.release("replacement")
        assert not (tmp_path / "sessions").exists() or not any(
            (tmp_path / "sessions").iterdir()
        )

    anyio.run(scenario)


def test_abort_releases_capacity_only_after_process_termination(
    tmp_path: Path,
) -> None:
    input_path = tmp_path / "input.jar"
    input_path.write_bytes(b"jar")
    starter = RecordingStarter()

    class BlockingTerminator(RecordingTerminator):
        def __init__(self) -> None:
            super().__init__()
            self.started = anyio.Event()
            self.release = anyio.Event()

        async def __call__(self, process: FakeProcess) -> None:
            self.processes.append(process)
            self.started.set()
            await self.release.wait()
            process.returncode = -15

    terminator = BlockingTerminator()
    launcher, manager, _ = make_launcher(
        tmp_path,
        input_path,
        ReadyProbe(input_path),
        starter,
        terminator,
        [43105],
    )

    async def scenario() -> None:
        session = await launcher.launch(input_path)
        async with anyio.create_task_group() as tasks:
            tasks.start_soon(launcher.abort, session.sid)
            await terminator.started.wait()
            assert await manager.slot_count() == 1
            with pytest.raises(SessionCapacityError):
                await manager.reserve_slot()
            terminator.release.set()
        assert await manager.slot_count() == 0

    anyio.run(scenario)


def test_cancelled_launch_terminates_and_releases_capacity_and_paths(tmp_path: Path) -> None:
    input_path = tmp_path / "input.dex"
    input_path.write_bytes(b"dex")
    autosave_path = tmp_path / "output.dex"
    starter = RecordingStarter()

    class WaitingProbe:
        def __init__(self) -> None:
            self.entered = anyio.Event()

        async def probe(self, port: int, token: str) -> dict[str, Any]:
            self.entered.set()
            await anyio.sleep_forever()

    class YieldingTerminator(RecordingTerminator):
        async def __call__(self, process: FakeProcess) -> None:
            await anyio.sleep(0)
            await super().__call__(process)

    probe = WaitingProbe()
    terminator = YieldingTerminator()
    launcher, manager, reservations = make_launcher(
        tmp_path, input_path, probe, starter, terminator, [43101, 43102],
    )

    async def launch() -> None:
        await launcher.launch(input_path, autosave_path)

    async def scenario() -> None:
        for _ in range(2):
            probe.entered = anyio.Event()
            async with anyio.create_task_group() as tasks:
                tasks.start_soon(launch)
                await probe.entered.wait()
                assert await manager.slot_count() == 1
                tasks.cancel_scope.cancel()
            assert await manager.slot_count() == 0
            assert await manager.snapshot() == []
            assert terminator.processes == starter.processes
            assert all(process.returncode is not None for process in starter.processes)
            await reservations.reserve("replacement", input_path, autosave_path)
            await reservations.release("replacement")
            assert not any((tmp_path / "sessions").iterdir())

    anyio.run(scenario)
