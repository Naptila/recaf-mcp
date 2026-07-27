from __future__ import annotations

import json

import anyio
import httpx
import pytest

from worker import WorkerMethodError, WorkerProtocolError, WorkerRpcClient


def test_http_worker_client_preserves_structured_result_and_error_flag() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["Authorization"] == "Bearer test-token"
        body = json.loads(request.content)
        assert body["method"] == "tools/call"
        assert body["params"]["arguments"] == {"text": "needle"}
        assert body["params"]["operation_id"] == "operation-1"
        return httpx.Response(
            200,
            json={
                "jsonrpc": "2.0",
                "id": body["id"],
                "result": {
                    "content": [{"type": "text", "text": "tool failed"}],
                    "structuredContent": {"reason": "expected"},
                    "isError": True,
                },
            },
        )

    async def scenario() -> None:
        client = WorkerRpcClient(transport=httpx.MockTransport(handler))
        result = await client.call_tool(
            43131,
            "test-token",
            "search_code",
            {"text": "needle"},
            operation_id="operation-1",
        )
        assert result.content[0].text == "tool failed"
        assert result.structuredContent == {"reason": "expected"}
        assert result.isError is True

    anyio.run(scenario)


def test_http_worker_client_classifies_rpc_and_malformed_result_errors() -> None:
    def method_error(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "jsonrpc": "2.0",
                "id": body["id"],
                "error": {"code": -32602, "message": "bad arguments"},
            },
        )

    def malformed_result(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        return httpx.Response(
            200,
            json={"jsonrpc": "2.0", "id": body["id"], "result": {"bad": True}},
        )

    async def scenario() -> None:
        method_client = WorkerRpcClient(transport=httpx.MockTransport(method_error))
        with pytest.raises(WorkerMethodError) as method:
            await method_client.request(43132, "token", "tools/call")
        assert method.value.code == -32602

        malformed_client = WorkerRpcClient(
            transport=httpx.MockTransport(malformed_result)
        )
        with pytest.raises(WorkerProtocolError, match="invalid tool result"):
            await malformed_client.call_tool(43133, "token", "workspace_info", {})

    anyio.run(scenario)
