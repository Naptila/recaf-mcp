"""Authenticated HTTP JSON-RPC client for one loopback Recaf Worker."""

from __future__ import annotations

import secrets
from typing import Any

import httpx
import mcp.types as types


class WorkerRpcError(RuntimeError):
    """A bounded Worker transport or JSON-RPC failure."""

    def __init__(self, message: str, code: int | None = None) -> None:
        super().__init__(message[:1000])
        self.code = code


class WorkerTransportError(WorkerRpcError):
    """The Worker could not be reached over authenticated HTTP."""


class WorkerProtocolError(WorkerRpcError):
    """The Worker returned a malformed JSON-RPC or MCP result."""


class WorkerMethodError(WorkerRpcError):
    """The Worker returned a valid JSON-RPC error for one request."""


class WorkerRpcClient:
    """Issue isolated requests without consulting proxy environment variables."""

    def __init__(
        self,
        timeout_sec: float = 30.0,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        if timeout_sec <= 0:
            raise ValueError("timeout_sec must be positive")
        self._timeout = httpx.Timeout(timeout_sec)
        self._transport = transport

    async def request(
        self,
        port: int,
        token: str,
        method: str,
        params: dict[str, Any] | None = None,
        timeout_sec: float | None = None,
    ) -> Any:
        request_id = secrets.token_hex(8)
        request = {
            "jsonrpc": "2.0",
            "id": request_id,
            "method": method,
            "params": params or {},
        }
        try:
            async with httpx.AsyncClient(
                timeout=(
                    self._timeout
                    if timeout_sec is None
                    else httpx.Timeout(timeout_sec)
                ),
                trust_env=False,
                transport=self._transport,
            ) as client:
                response = await client.post(
                    f"http://127.0.0.1:{port}/jsonrpc",
                    headers={"Authorization": f"Bearer {token}"},
                    json=request,
                )
                response.raise_for_status()
                payload = response.json()
        except httpx.HTTPError as error:
            raise WorkerTransportError("Worker HTTP request failed") from error
        except ValueError as error:
            raise WorkerProtocolError("Worker returned invalid JSON") from error
        if not isinstance(payload, dict) or payload.get("id") != request_id:
            raise WorkerProtocolError(
                "Worker returned an invalid JSON-RPC envelope"
            )
        rpc_error = payload.get("error")
        if isinstance(rpc_error, dict):
            code = rpc_error.get("code")
            message = rpc_error.get("message", "Worker request failed")
            raise WorkerMethodError(
                str(message),
                code if isinstance(code, int) else None,
            )
        if "result" not in payload:
            raise WorkerProtocolError("Worker JSON-RPC response has no result")
        return payload["result"]

    async def call_tool(
        self,
        port: int,
        token: str,
        name: str,
        arguments: dict[str, Any],
        *,
        operation_id: str | None = None,
        timeout_sec: float = 310.0,
    ) -> types.CallToolResult:
        params: dict[str, Any] = {"name": name, "arguments": arguments}
        if operation_id is not None:
            params["operation_id"] = operation_id
        result = await self.request(
            port,
            token,
            "tools/call",
            params,
            timeout_sec=timeout_sec,
        )
        try:
            return types.CallToolResult.model_validate(result)
        except ValueError as error:
            raise WorkerProtocolError(
                "Worker returned an invalid tool result"
            ) from error
