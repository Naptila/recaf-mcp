from __future__ import annotations

import os
import signal
import subprocess
import sys
from pathlib import Path

import anyio
import pytest
from mcp.shared.memory import create_connected_server_and_client_session

from application import BridgeShutdownError, BridgeToolService
from config import BridgeConfig
from conftest import BRIDGE_ROOT
from server import create_server
from test_stdio import read_message, write_message


@pytest.mark.skipif(
    "RECAF_MCP_TEST_RECAF_JAR" not in os.environ,
    reason="installed Recaf integration not requested",
)
def test_failed_shutdown_save_is_authenticated_and_recovered(
    tmp_path: Path,
) -> None:
    recaf_jar = Path(os.environ["RECAF_MCP_TEST_RECAF_JAR"]).resolve(strict=True)
    plugin_jar = Path(os.environ["RECAF_MCP_TEST_PLUGIN_JAR"]).resolve(
        strict=True
    )
    input_path = Path(os.environ["RECAF_MCP_TEST_INPUT"]).resolve(strict=True)
    blocked_dir = tmp_path / "blocked-autosave"
    blocked_dir.mkdir(mode=0o700)
    autosave_path = (blocked_dir / "autosave.jar").resolve()
    config = BridgeConfig(
        recaf_jar=recaf_jar,
        plugin_jar=plugin_jar,
        session_root=(tmp_path / "sessions").resolve(),
        recovery_dir=(tmp_path / "state" / "recovery").resolve(),
        max_sessions=1,
        launch_timeout_sec=60,
    )

    async def scenario() -> None:
        first_service = BridgeToolService(config)
        first_server = create_server(first_service)
        async with create_connected_server_and_client_session(
            first_server
        ) as client:
            await client.list_tools()
            opened = await client.call_tool(
                "recaf_open",
                {
                    "input_path": str(input_path),
                    "autosave_path": str(autosave_path),
                    "idle_ttl_sec": 0,
                },
            )
            assert opened.isError is False
            database = opened.structuredContent["database"]
            blocked_dir.chmod(0o500)

        with pytest.raises(BridgeShutdownError, match="same recovery directory"):
            await first_service.close()
        entries = list(config.effective_recovery_dir.glob("*.json"))
        assert len(entries) == 1

        blocked_dir.chmod(0o700)
        second_service = BridgeToolService(config)
        second_server = create_server(second_service)
        try:
            async with create_connected_server_and_client_session(
                second_server
            ) as client:
                await client.list_tools()
                recovered = await client.call_tool("recaf_list", {})
                assert len(recovered.structuredContent["sessions"]) == 1
                public_session = recovered.structuredContent["sessions"][0]
                assert public_session["database"] == database
                assert public_session["state"] == "ready"
                assert public_session["save_error"] is not None
                assert entries[0].exists() is False

                workspace = await client.call_tool(
                    "workspace_info",
                    {"database": database},
                )
                assert workspace.isError is False

                closed = await client.call_tool(
                    "recaf_close",
                    {"database": database},
                )
                assert closed.isError is False
                assert closed.structuredContent["saved"] is True
                assert closed.structuredContent["graceful"] is True
                assert autosave_path.is_file()
        finally:
            await second_service.close()

        assert not config.effective_recovery_dir.exists() or not any(
            config.effective_recovery_dir.iterdir()
        )
        assert not config.session_root.exists() or not any(
            config.session_root.iterdir()
        )

    try:
        anyio.run(scenario)
    finally:
        blocked_dir.chmod(0o700)


@pytest.mark.skipif(
    "RECAF_MCP_TEST_RECAF_JAR" not in os.environ,
    reason="installed Recaf integration not requested",
)
def test_sigterm_saves_real_worker_before_bridge_exit(tmp_path: Path) -> None:
    input_path = Path(os.environ["RECAF_MCP_TEST_INPUT"]).resolve(strict=True)
    autosave_path = (tmp_path / "signal-autosave.jar").resolve()
    environment = {
        **os.environ,
        "RECAF_JAR": os.environ["RECAF_MCP_TEST_RECAF_JAR"],
        "RECAF_PLUGIN_JAR": os.environ["RECAF_MCP_TEST_PLUGIN_JAR"],
        "RECAF_SESSION_ROOT": str((tmp_path / "sessions").resolve()),
        "RECAF_RECOVERY_DIR": str((tmp_path / "recovery").resolve()),
        "RECAF_IDLE_TTL_SEC": "0",
        "RECAF_MAX_SESSIONS": "1",
    }
    process = subprocess.Popen(
        [sys.executable, "src/main.py", "--stdio"],
        cwd=BRIDGE_ROOT,
        env=environment,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
    )
    assert process.stdin is not None
    assert process.stdout is not None
    assert process.stderr is not None

    try:
        write_message(
            process.stdin,
            {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "initialize",
                "params": {
                    "protocolVersion": "2025-11-25",
                    "capabilities": {},
                    "clientInfo": {"name": "pytest", "version": "1.0"},
                },
            },
        )
        assert read_message(process.stdout)["result"]["serverInfo"]["name"] == (
            "recaf-mcp-bridge"
        )
        write_message(
            process.stdin,
            {"jsonrpc": "2.0", "method": "notifications/initialized"},
        )
        write_message(
            process.stdin,
            {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
        )
        assert read_message(process.stdout)["result"]["tools"]

        write_message(
            process.stdin,
            {
                "jsonrpc": "2.0",
                "id": 3,
                "method": "tools/call",
                "params": {
                    "name": "recaf_open",
                    "arguments": {
                        "input_path": str(input_path),
                        "autosave_path": str(autosave_path),
                    },
                },
            },
        )
        opened = read_message(process.stdout)
        assert opened["result"]["isError"] is False

        process.send_signal(signal.SIGTERM)
        assert process.wait(timeout=30) == 0
        assert autosave_path.is_file()
        assert process.stderr.read() == ""
        assert not (tmp_path / "sessions").exists() or not any(
            (tmp_path / "sessions").iterdir()
        )
        assert not (tmp_path / "recovery").exists() or not any(
            (tmp_path / "recovery").iterdir()
        )
    finally:
        if process.poll() is None:
            process.send_signal(signal.SIGTERM)
            try:
                process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
