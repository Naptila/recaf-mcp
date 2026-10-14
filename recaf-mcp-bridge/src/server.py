"""Official low-level MCP server wiring for the Recaf Bridge."""

from __future__ import annotations

from contextlib import asynccontextmanager
from typing import Any, Protocol

import anyio
import mcp.types as types
from mcp.server.lowlevel import Server

from tools import LifecycleToolService


SERVER_NAME = "recaf-mcp-bridge"
SERVER_VERSION = "1.0.2"
SERVER_INSTRUCTIONS = "Call recaf_open first and reuse its database ID."


class ToolService(Protocol):
    async def list_tools(self) -> list[types.Tool]: ...

    async def call(
        self,
        name: str,
        arguments: dict[str, Any],
    ) -> types.CallToolResult: ...

    async def close(self) -> None: ...

    async def run_background(self) -> None: ...

    def begin_shutdown(self) -> None: ...


def create_server(tool_service: ToolService | None = None) -> Server:
    """Create one MCP server with a catalog fixed for its entire lifetime."""
    service = tool_service or LifecycleToolService()

    @asynccontextmanager
    async def lifespan(_: Server):
        async with anyio.create_task_group() as tasks:
            tasks.start_soon(service.run_background)
            try:
                yield {}
            finally:
                tasks.cancel_scope.cancel()

    server = Server(
        SERVER_NAME,
        version=SERVER_VERSION,
        instructions=SERVER_INSTRUCTIONS,
        lifespan=lifespan,
    )

    @server.list_tools()
    async def list_tools() -> list[types.Tool]:
        return await service.list_tools()

    @server.call_tool()
    async def call_tool(
        name: str,
        arguments: dict[str, Any],
    ) -> types.CallToolResult:
        context = server.request_context
        progress_token = (
            context.meta.progressToken
            if context.meta is not None
            else None
        )
        if progress_token is not None:
            await context.session.send_progress_notification(
                progress_token,
                0,
                total=1,
                message=f"Starting {name}",
                related_request_id=str(context.request_id),
            )
        result = await service.call(name, arguments)
        if progress_token is not None:
            await context.session.send_progress_notification(
                progress_token,
                1,
                total=1,
                message=f"Completed {name}",
                related_request_id=str(context.request_id),
            )
        return result

    return server
