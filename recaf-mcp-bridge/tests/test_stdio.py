from __future__ import annotations

import json
import queue
import signal
import subprocess
import sys
import threading
from typing import Any, TextIO

import pytest
from mcp.types import LATEST_PROTOCOL_VERSION

from conftest import BRIDGE_ROOT


def write_message(stream: TextIO, message: dict[str, Any]) -> None:
    stream.write(json.dumps(message, separators=(",", ":")) + "\n")
    stream.flush()


def read_message(stream: TextIO) -> dict[str, Any]:
    result: queue.Queue[str | BaseException] = queue.Queue(maxsize=1)

    def read_line() -> None:
        try:
            result.put(stream.readline())
        except BaseException as error:
            result.put(error)

    threading.Thread(target=read_line, daemon=True).start()
    try:
        item = result.get(timeout=10)
    except queue.Empty:
        raise AssertionError(
            "timed out waiting for the Bridge stdio response"
        ) from None
    if isinstance(item, BaseException):
        raise item
    line = item
    assert line, "Bridge stdout closed before a response arrived"
    return json.loads(line)


def test_stdio_initialize_list_and_call_lifecycle() -> None:
    process = subprocess.Popen(
        [sys.executable, "src/main.py", "--stdio"],
        cwd=BRIDGE_ROOT,
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
                    "protocolVersion": LATEST_PROTOCOL_VERSION,
                    "capabilities": {},
                    "clientInfo": {"name": "pytest", "version": "1.0"},
                },
            },
        )
        initialized = read_message(process.stdout)
        assert initialized["result"]["serverInfo"] == {
            "name": "recaf-mcp-bridge",
            "version": "1.1.0",
        }
        assert initialized["result"]["capabilities"]["tools"] == {
            "listChanged": False
        }
        assert initialized["result"]["instructions"] == (
            "Call recaf_open first and reuse its database ID."
        )

        write_message(
            process.stdin,
            {
                "jsonrpc": "2.0",
                "method": "notifications/initialized",
            },
        )
        write_message(
            process.stdin,
            {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
        )
        catalog = read_message(process.stdout)
        assert [tool["name"] for tool in catalog["result"]["tools"]] == [
            "recaf_open",
            "recaf_save",
            "recaf_close",
            "recaf_list",
            "set_idle_ttl",
        ]

        write_message(
            process.stdin,
            {
                "jsonrpc": "2.0",
                "id": 3,
                "method": "tools/call",
                "params": {"name": "recaf_list", "arguments": {}},
            },
        )
        called = read_message(process.stdout)
        assert called["result"]["isError"] is False
        assert called["result"]["structuredContent"] == {"sessions": []}
        assert called["result"]["content"] == [
            {"type": "text", "text": "No Recaf sessions are open."}
        ]

        process.stdin.close()
        assert process.wait(timeout=10) == 0
        assert process.stdout.read() == ""
        assert process.stderr.read() == ""
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=5)


@pytest.mark.skipif(
    sys.platform == "win32",
    reason="Windows SIGTERM forcibly terminates subprocesses",
)
def test_stdio_sigterm_exits_without_waiting_for_client_eof() -> None:
    process = subprocess.Popen(
        [sys.executable, "src/main.py", "--stdio"],
        cwd=BRIDGE_ROOT,
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
                    "protocolVersion": LATEST_PROTOCOL_VERSION,
                    "capabilities": {},
                    "clientInfo": {"name": "pytest", "version": "1.0"},
                },
            },
        )
        initialized = read_message(process.stdout)
        assert initialized["result"]["serverInfo"]["name"] == "recaf-mcp-bridge"

        process.send_signal(signal.SIGTERM)
        assert process.wait(timeout=10) == 0
        assert process.stderr.read() == ""
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=5)
