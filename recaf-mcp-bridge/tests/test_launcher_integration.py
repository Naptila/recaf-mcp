from __future__ import annotations

import os
from pathlib import Path

import anyio
import pytest

from config import BridgeConfig
from launcher import WorkerIdentity, WorkerLauncher
from sessions import PathReservations, SessionManager


@pytest.mark.skipif(
    "RECAF_MCP_TEST_RECAF_JAR" not in os.environ,
    reason="installed Recaf integration not requested",
)
def test_installed_recaf_reaches_authenticated_readiness(tmp_path: Path) -> None:
    recaf_jar = Path(os.environ["RECAF_MCP_TEST_RECAF_JAR"]).resolve(strict=True)
    plugin_jar = Path(os.environ["RECAF_MCP_TEST_PLUGIN_JAR"]).resolve(
        strict=True
    )
    input_path = Path(os.environ["RECAF_MCP_TEST_INPUT"]).resolve(strict=True)
    identity = WorkerIdentity(
        os.environ["RECAF_MCP_TEST_PLUGIN_BUILD"],
        os.environ["RECAF_MCP_TEST_SCHEMA_FINGERPRINT"],
    )
    config = BridgeConfig(
        recaf_jar=recaf_jar,
        plugin_jar=plugin_jar,
        session_root=(tmp_path / "sessions").resolve(),
        max_sessions=1,
        launch_timeout_sec=60,
        readiness_poll_sec=0.2,
    )
    sessions = SessionManager(config.max_sessions)
    reservations = PathReservations()
    launcher = WorkerLauncher(config, sessions, reservations, identity)

    async def scenario() -> None:
        session = await launcher.launch(input_path)
        try:
            assert session.state == "ready"
            assert session.input_path == input_path
            assert session.process.returncode is None
            assert await reservations.owns(session.sid, session.autosave_path)
        finally:
            await launcher.abort(session.sid)
        assert session.process.returncode is not None
        assert not session.session_dir.exists()

    anyio.run(scenario)
