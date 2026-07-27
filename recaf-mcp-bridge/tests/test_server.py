from __future__ import annotations

import anyio

from mcp.shared.memory import create_connected_server_and_client_session

from server import create_server


def test_low_level_server_has_static_catalog_and_tool_results() -> None:
    async def scenario() -> None:
        server = create_server()
        async with create_connected_server_and_client_session(server) as client:
            first = await client.list_tools()
            second = await client.list_tools()

            expected = [
                "recaf_open",
                "recaf_save",
                "recaf_close",
                "recaf_list",
                "set_idle_ttl",
            ]
            assert [tool.name for tool in first.tools] == expected
            assert [tool.model_dump() for tool in first.tools] == [
                tool.model_dump() for tool in second.tools
            ]

            progress_updates: list[tuple[float, float | None, str | None]] = []

            async def progress(
                current: float,
                total: float | None,
                message: str | None,
            ) -> None:
                progress_updates.append((current, total, message))

            listed = await client.call_tool(
                "recaf_list",
                {},
                progress_callback=progress,
            )
            assert listed.isError is False
            assert listed.structuredContent == {"sessions": []}
            assert progress_updates == [
                (0.0, 1.0, "Starting recaf_list"),
                (1.0, 1.0, "Completed recaf_list"),
            ]

            invalid = await client.call_tool("recaf_open", {})
            assert invalid.isError is True
            assert "input_path" in invalid.content[0].text

            unknown = await client.call_tool("not_a_tool", {})
            assert unknown.isError is True
            assert unknown.structuredContent == {
                "error": "unknown tool",
                "tool": "not_a_tool",
            }

    anyio.run(scenario)
