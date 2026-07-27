"""Command-line entry point for the Recaf MCP Bridge."""

from __future__ import annotations

import argparse
import asyncio
import logging
import os
import signal
import sys
from collections.abc import Sequence

import mcp.server.stdio
from mcp.server.lowlevel import NotificationOptions

from application import BridgeShutdownError, tool_service_from_env
from server import create_server


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    """Parse the intentionally small, client-neutral Bridge CLI."""
    parser = argparse.ArgumentParser(description="Run the Recaf MCP Bridge")
    parser.add_argument(
        "--stdio",
        action="store_true",
        help="serve MCP JSON-RPC over standard input and standard output",
    )
    args = parser.parse_args(argv)
    if not args.stdio:
        parser.error("a transport is required; use --stdio")
    return args


async def run_stdio() -> None:
    """Run one standard MCP stdio connection until the client closes it."""
    service = tool_service_from_env()
    server = create_server(service)
    initialization_options = server.create_initialization_options(
        notification_options=NotificationOptions(tools_changed=False),
        experimental_capabilities={},
    )
    loop = asyncio.get_running_loop()
    signal_shutdown_started = False

    async def signal_shutdown(signal_name: str) -> None:
        service.begin_shutdown()
        exit_code = 0
        try:
            await service.close()
        except BridgeShutdownError as error:
            exit_code = 1
            print(f"ERROR recaf-mcp-bridge: {error}", file=sys.stderr, flush=True)
        except BaseException as error:
            exit_code = 1
            print(
                f"ERROR recaf-mcp-bridge: {signal_name} cleanup failed: "
                f"{str(error)[:1000]}",
                file=sys.stderr,
                flush=True,
            )
        os._exit(exit_code)

    def request_signal_shutdown(signal_name: str) -> None:
        nonlocal signal_shutdown_started
        if signal_shutdown_started:
            return
        signal_shutdown_started = True
        service.begin_shutdown()
        loop.create_task(signal_shutdown(signal_name))

    installed_signals: list[signal.Signals] = []
    for signal_number in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(
                signal_number,
                request_signal_shutdown,
                signal_number.name,
            )
            installed_signals.append(signal_number)
        except NotImplementedError:  # pragma: no cover - Windows event loop
            pass
    try:
        async with mcp.server.stdio.stdio_server() as (read_stream, write_stream):
            await server.run(read_stream, write_stream, initialization_options)
    finally:
        for signal_number in installed_signals:
            loop.remove_signal_handler(signal_number)
        if not signal_shutdown_started:
            await service.close()


def main(argv: Sequence[str] | None = None) -> None:
    """Validate the CLI and start the requested transport."""
    parse_args(argv)
    logging.basicConfig(
        level=logging.WARNING,
        stream=sys.stderr,
        format="%(levelname)s %(name)s: %(message)s",
    )
    try:
        asyncio.run(run_stdio())
    except BridgeShutdownError as error:
        logging.getLogger(__name__).error("%s", error)
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
