from __future__ import annotations

import os
import subprocess
import zipfile
from pathlib import Path

import anyio
import pytest

from application import BridgeToolService
from config import BridgeConfig


@pytest.mark.skipif(
    "RECAF_MCP_TEST_GENERATED_JVM_JAR" not in os.environ,
    reason="generated JVM transformation real-Worker fixture not requested",
)
def test_native_transform_phases_dry_run_save_and_execute(tmp_path: Path) -> None:
    input_path = Path(os.environ["RECAF_MCP_TEST_GENERATED_JVM_JAR"])
    original = input_path.read_bytes()
    config = BridgeConfig(
        recaf_jar=Path(os.environ["RECAF_MCP_TEST_RECAF_JAR"]),
        plugin_jar=Path(os.environ["RECAF_MCP_TEST_PLUGIN_JAR"]),
        session_root=tmp_path / "sessions", launch_timeout_sec=60, max_sessions=1,
    )
    phases = [
        {"id": "cleanup", "transformers": ["long_annotations"],
         "options": {"long_annotations": {"max_length": 40}}},
        {"id": "fold", "transformers": ["opaque_constants"]},
        {"id": "finish", "transformers": ["dead_code"]},
    ]

    async def scenario() -> None:
        service = BridgeToolService(config)
        try:
            tools = {tool.name: tool for tool in await service.list_tools()}
            schema = tools["transform_workspace"].inputSchema["properties"]
            assert schema["phases"]["maxItems"] == 8
            assert schema["phases"]["items"]["required"] == ["id", "transformers"]
            assert schema["options"]["properties"]["goto_inlining"]["properties"][
                "backwards_switch_flow"
            ]["default"] is False
            opened = await service.call("recaf_open", {"input_path": str(input_path)})
            assert not opened.isError, opened
            database = opened.structuredContent["database"]
            identity = {"database": database, "class_name": "sample/Obfuscated"}
            before = await service.call("get_class_bytecode", identity)
            assert not before.isError, before
            other = await service.call("get_class_bytecode", {
                "database": database, "class_name": "other/Untouched",
            })
            # Exercise each expanded alias and its upstream dependencies in a real Worker.
            for alias in (
                "long_annotations", "long_exceptions", "duplicate_catches",
                "variable_table_normalization", "opaque_constants", "opaque_predicates",
                "variable_folding", "static_value_inlining", "call_result_inlining",
                "invokedynamic_inlining",
            ):
                checked = await service.call("transform_workspace", {
                    "database": database, "transformers": [alias],
                    "class_prefix": "sample/", "dry_run": True,
                })
                assert not checked.isError, (alias, checked)
                assert checked.structuredContent["applied"] is False
            request = {"database": database, "class_prefix": "sample/", "phases": phases}
            preview = await service.call("transform_workspace", {**request, "dry_run": True})
            assert not preview.isError, preview
            assert preview.structuredContent["modified_class_count"] == 1
            assert [phase["id"] for phase in preview.structuredContent["phase_results"]] == [
                "cleanup", "fold", "finish",
            ]
            unchanged = await service.call("get_class_bytecode", identity)
            assert unchanged.structuredContent["sha256"] == before.structuredContent["sha256"]
            invalid = await service.call("transform_workspace", {
                **request, "transformers": ["dead_code"],
            })
            assert invalid.isError
            applied = await service.call("transform_workspace", request)
            assert not applied.isError, applied
            assert applied.structuredContent["applied"] is True
            assert applied.structuredContent["failure_count"] == 0
            assert applied.structuredContent["modified_classes"] == ["sample/Obfuscated"]
            untouched = await service.call("get_class_bytecode", {
                "database": database, "class_name": "other/Untouched",
            })
            assert untouched.structuredContent["sha256"] == other.structuredContent["sha256"]
            destination = tmp_path / "transformed.jar"
            saved = await service.call("recaf_save", {"database": database, "output_path": str(destination)})
            assert not saved.isError, saved
            closed = await service.call("recaf_close", {"database": database, "save": False})
            assert not closed.isError, closed
            reopened = await service.call("recaf_open", {"input_path": str(destination)})
            assert not reopened.isError, reopened
            assembly = await service.call("get_method_assembly", {
                "database": reopened.structuredContent["database"], "owner": "sample/Obfuscated",
                "name": "value", "descriptor": "()I",
            })
            assert not assembly.isError, assembly
            assert "iadd" not in assembly.structuredContent["assembly"]
            assert input_path.read_bytes() == original
            with zipfile.ZipFile(destination) as archive:
                assert archive.read("assets/message.txt") == b"keep me"
            for target in (input_path, destination):
                execution = subprocess.run(
                    [config.java_command, "-cp", str(target), "sample.Obfuscated"],
                    check=True, capture_output=True, text=True, timeout=15,
                )
                assert execution.stdout.strip() == "5"
        finally:
            await service.close()

    anyio.run(scenario)
